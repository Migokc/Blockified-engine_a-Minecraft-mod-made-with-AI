package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.client.lua.LuaLayerEffects;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.GlStateBackup;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.luaj.vm2.LuaTable;
import org.lwjgl.opengl.*;
import java.util.*;
import java.util.function.Consumer;

/** Local-space, GPU-only layer compositor shared by menus and gameplay. Render-thread only. */
public final class LuaLayerRenderer implements AutoCloseable {
    private static ShaderInstance effectShader, drawShader;
    private static int shaderGeneration, nextId;
    private static final long BUDGET = 64L * 1024 * 1024;
    private static final ByteBufferBuilder GUI_BUFFER = new ByteBufferBuilder(786432);
    private static final Map<ResourceLocation, Surface> SURFACES = new HashMap<>();
    private final LinkedHashMap<String, Surface> cache = new LinkedHashMap<>(16, .75f, true);
    private final Set<String> painting = new HashSet<>();
    private long bytes;
    private static TextureTarget backdrop;
    private static long revision;

    public static void registerShaders(RegisterShadersEvent event) throws java.io.IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), FnfMod.id("lua_layer_effect"),
                DefaultVertexFormat.BLIT_SCREEN), shader -> { effectShader = shader; shaderGeneration++; });
        event.registerShader(new ShaderInstance(event.getResourceProvider(), FnfMod.id("lua_layer_draw"),
                DefaultVertexFormat.POSITION_TEX_COLOR), shader -> drawShader = shader);
    }
    private static final class Texture extends AbstractTexture {
        Texture(int texture) { id = texture; }
        @Override public void load(ResourceManager manager) {}
        // The framebuffer owns the texture. Never delete the same GL name twice.
        @Override public void close() { id = -1; }
    }
    public static final class Surface {
        public final ResourceLocation texture;
        public final int width, height;
        public double logicalWidth, logicalHeight;
        public double offsetX, offsetY;
        private final TextureTarget raw, output;
        private final long bytes;
        private Object signature;
        private long revision;
        private int generation;
        private String blend = "normal";
        private long hitRevision = -1;
        private final byte[] hitAlpha = new byte[64 * 64];
        private Surface(int width, int height) {
            this.width = width; this.height = height; bytes = (long) width * height * 8;
            logicalWidth=width;logicalHeight=height;
            raw = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            output = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            raw.setClearColor(0, 0, 0, 0); output.setClearColor(0, 0, 0, 0);
            texture = FnfMod.id("lua_layer/" + ++nextId);
            Minecraft.getInstance().getTextureManager().register(texture, new Texture(output.getColorTextureId()));
            SURFACES.put(texture, this);
        }
        private void close() {
            SURFACES.remove(texture);
            Minecraft.getInstance().getTextureManager().release(texture);
            raw.destroyBuffers(); output.destroyBuffers();
        }
    }
    public boolean enter(String id) { return painting.size() < 16 && painting.add(id); }
    public void leave(String id) { painting.remove(id); }
    public void invalidate() { for (Surface surface : cache.values()) surface.signature = null; }
    public Surface get(String id) { return cache.get(id); }
    /** Optional coarse alpha picking, refreshed only when this layer's pixels change. */
    public boolean hit(String id, double u, double v) {
        Surface s=cache.get(id); if(s==null || u<0 || v<0 || u>=1 || v>=1) return false;
        if(s.hitRevision!=s.revision) try(State ignored=new State()) {
            TextureTarget pick=new TextureTarget(64,64,false,Minecraft.ON_OSX);
            try {
                RenderSystem.disableScissor();
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,s.output.frameBufferId);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,pick.frameBufferId);
                GL30.glBlitFramebuffer(0,0,s.width,s.height,0,0,64,64,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_LINEAR);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,pick.frameBufferId);
                var pixels=org.lwjgl.BufferUtils.createByteBuffer(64*64*4);
                GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                for(int y=0;y<64;y++) for(int x=0;x<64;x++) s.hitAlpha[y*64+x]=pixels.get(((63-y)*64+x)*4+3);
                s.hitRevision=s.revision;
            } finally { pick.destroyBuffers(); }
        }
        return (s.hitAlpha[Math.min(63,(int)(v*64))*64+Math.min(63,(int)(u*64))]&255)>8;
    }

    /** Size is raster resolution, independent of the object's logical/local dimensions. */
    public Surface paint(String id, int width, int height, Object fingerprint, LuaTable data,
                         Surface mask, Consumer<GuiGraphics> painter) {
        if (effectShader == null || drawShader == null) return null;
        int requestedWidth = Math.max(1, width), requestedHeight = Math.max(1, height);
        int max = Math.min(4096, RenderSystem.maxSupportedTextureSize());
        width = Math.clamp(width, 1, max); height = Math.clamp(height, 1, max);
        // Limit a single layer to half the budget; leave space for its source/mask.
        double shrink = Math.min(1, Math.sqrt((BUDGET / 2d) / ((double) width * height * 8)));
        width = Math.max(1, (int) (width * shrink)); height = Math.max(1, (int) (height * shrink));
        try (State ignored = new State()) {
            Surface surface = cache.get(id);
            if (surface != null && (surface.width != width || surface.height != height)) {
                bytes -= surface.bytes; cache.remove(id); surface.close(); surface = null;
            }
            if (surface == null) {
                long required = (long) width * height * 8;
                Iterator<Map.Entry<String, Surface>> it = cache.entrySet().iterator();
                while (bytes + required > BUDGET && it.hasNext()) {
                    var item = it.next();
                    if (item.getValue() == mask || painting.contains(item.getKey())) continue;
                    bytes -= item.getValue().bytes; item.getValue().close(); it.remove();
                }
                if (bytes + required > BUDGET) return null;
                surface = new Surface(width, height); cache.put(id, surface); bytes += surface.bytes;
            }
            StringBuilder properties = new StringBuilder();
            for (String key : LuaLayerEffects.NUMBERS.keySet()) properties.append(LuaLayerEffects.value(data, key)).append('|');
            for (String key : LuaLayerEffects.STRINGS.keySet()) properties.append(LuaLayerEffects.value(data, key)).append('|');
            for (String key : LuaLayerEffects.BOOLEANS.keySet()) properties.append(LuaLayerEffects.value(data, key)).append('|');
            Object signature = List.of(fingerprint, properties.toString(), mask == null ? -1L : mask.revision);
            surface.blend = LuaLayerEffects.value(data, "blendMode").tojstring();
            if (signature.equals(surface.signature) && surface.generation == shaderGeneration) return surface;
            RenderSystem.disableScissor(); RenderSystem.colorMask(true, true, true, true);
            RenderSystem.setShaderColor(1, 1, 1, 1); RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            surface.raw.clear(Minecraft.ON_OSX); surface.raw.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, width, height, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.getModelViewStack().identity().translate(0, 0, -11000);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
            RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
            GuiGraphics gui = new GuiGraphics(Minecraft.getInstance(), MultiBufferSource.immediate(GUI_BUFFER));
            gui.pose().scale(width / (float) requestedWidth, height / (float) requestedHeight, 1);
            painter.accept(gui); gui.flush();
            surface.output.bindWrite(true);
            RenderSystem.disableScissor(); RenderSystem.disableDepthTest(); RenderSystem.disableBlend();
            RenderSystem.colorMask(true, true, true, true);
            effectShader.setSampler("Source", surface.raw.getColorTextureId());
            effectShader.setSampler("Mask", mask == null ? surface.raw.getColorTextureId() : mask.output.getColorTextureId());
            uniform(effectShader, "HasMask", data.get("mask").optjstring("").isBlank() ? 0 : mask == null ? 2 : 1);
            uniform(effectShader, "MaskLuma", data.get("maskMode").optjstring("alpha").equals("luminance") ? 1 : 0);
            uniform(effectShader, "MaskInvert", data.get("maskInvert").optboolean(false) ? 1 : 0);
            uniform(effectShader, "MaskTransform", n(data,"maskX"), n(data,"maskY"), n(data,"maskScaleX"), n(data,"maskScaleY"));
            uniform(effectShader, "MaskStyle", n(data,"maskAngle"), n(data,"maskSoftness"), 0, 0);
            uniform(effectShader, "GradientType", switch (data.get("gradientType").optjstring("none")) {
                case "linear" -> 1; case "radial" -> 2; case "angular" -> 3; default -> 0;
            });
            uniform(effectShader,"GradientGeometry",n(data,"gradientX"),n(data,"gradientY"),n(data,"gradientRadius"),n(data,"gradientAngle"));
            uniform(effectShader,"GradientStrength", n(data,"gradientStrength"));
            List<LuaLayerEffects.Stop> stops = LuaLayerEffects.stops(data);
            uniform(effectShader,"StopCount",stops.size());
            for (int i=0; i<16; i++) {
                var stop = stops.get(Math.min(i,stops.size()-1)); int c = stop.color();
                uniform(effectShader,"Stop"+i, ((c>>16)&255)/255f, ((c>>8)&255)/255f, (c&255)/255f, (float)stop.alpha());
            }
            for (int i=0; i<4; i++) uniform(effectShader,"Positions"+i,
                    (float)stops.get(Math.min(i*4,stops.size()-1)).at(),
                    (float)stops.get(Math.min(i*4+1,stops.size()-1)).at(),
                    (float)stops.get(Math.min(i*4+2,stops.size()-1)).at(),
                    (float)stops.get(Math.min(i*4+3,stops.size()-1)).at());
            uniform(effectShader,"ClipType",switch(data.get("clipType").optjstring("none")) {
                case "rect" -> 1; case "circle" -> 2; case "rounded" -> 3; default -> 0;
            });
            uniform(effectShader,"ClipBounds",n(data,"clipX"),n(data,"clipY"),n(data,"clipWidth"),n(data,"clipHeight"));
            uniform(effectShader,"ClipStyle",n(data,"clipRadius"),n(data,"clipSoftness"), 1f/width, 1f/height);
            effectShader.apply(); RenderSystem.disableBlend();
            BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
            builder.addVertex(0,0,0); builder.addVertex(1,0,0); builder.addVertex(1,1,0); builder.addVertex(0,1,0);
            BufferUploader.draw(builder.buildOrThrow()); effectShader.clear();
            surface.signature = signature; surface.generation = shaderGeneration; surface.revision = ++revision;
            return surface;
        }
    }
    private static float n(LuaTable data, String key) { return (float)LuaLayerEffects.number(data,key); }
    private static void uniform(ShaderInstance shader,String name,int value) {
        var u=shader.getUniform(name); if(u!=null) u.set(value);
    }
    private static void uniform(ShaderInstance shader,String name,float value) {
        var u=shader.getUniform(name); if(u!=null) u.set(value);
    }
    private static void uniform(ShaderInstance shader,String name,float x,float y,float z,float w) {
        var u=shader.getUniform(name); if(u!=null) u.set(x,y,z,w);
    }
    public static boolean isSurface(ResourceLocation texture) { return SURFACES.containsKey(texture); }
    public static boolean drawTexture(Matrix4f pose, ResourceLocation texture, float x,float y,float width,float height,
                                      float alpha, boolean world, boolean seeThrough) {
        Surface surface=SURFACES.get(texture); if(surface==null) return false;
        draw(pose,surface,x,y,width,height,alpha,world,seeThrough); return true;
    }
    public static void draw(Matrix4f pose, Surface surface, float x,float y,float width,float height,
                            float alpha, boolean world, boolean seeThrough) {
        if(surface==null || drawShader==null || alpha<=0) return;
        try(State ignored=new State()) {
            int blend=Math.max(0,LuaLayerEffects.BLENDS.indexOf(surface.blend));
            int[] viewport=new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
            if(blend!=0) {
                int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                if(backdrop==null || backdrop.width!=viewport[2] || backdrop.height!=viewport[3]) {
                    if(backdrop!=null) backdrop.destroyBuffers();
                    backdrop=new TextureTarget(Math.max(1,viewport[2]),Math.max(1,viewport[3]),false,Minecraft.ON_OSX);
                    GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
                }
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,draw);
                RenderSystem.bindTexture(backdrop.getColorTextureId());
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,viewport[0],viewport[1],viewport[2],viewport[3]);
                drawShader.setSampler("Backdrop",backdrop.getColorTextureId());
            } else drawShader.setSampler("Backdrop",surface.output.getColorTextureId());
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.disableCull(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
            if(world && !seeThrough) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false); RenderSystem.setShaderColor(1,1,1,1);
            uniform(drawShader,"BlendMode",blend);
            uniform(drawShader,"Viewport",viewport[0],viewport[1],viewport[2],viewport[3]);
            drawShader.setSampler("Source",surface.output.getColorTextureId());
            RenderSystem.setShader(()->drawShader);
            BufferBuilder b=RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
            b.addVertex(pose,x,y+height,0).setUv(0,0).setColor(1f,1f,1f,alpha);
            b.addVertex(pose,x+width,y+height,0).setUv(1,0).setColor(1f,1f,1f,alpha);
            b.addVertex(pose,x+width,y,0).setUv(1,1).setColor(1f,1f,1f,alpha);
            b.addVertex(pose,x,y,0).setUv(0,1).setColor(1f,1f,1f,alpha);
            BufferUploader.drawWithShader(b.buildOrThrow());
        }
    }
    /** Complete framebuffer, matrix, scissor and shader restoration, including split Iris targets. */
    private static final class State implements AutoCloseable {
        final int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int[] viewport=new int[4];
        final int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int activeTexture=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int[] bindings=new int[2];
        final boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        final int[] scissorBox=new int[4];
        final GlStateBackup gl=new GlStateBackup();
        final Matrix4f projection=new Matrix4f(RenderSystem.getProjectionMatrix());
        final VertexSorting sorting=RenderSystem.getVertexSorting();
        final ShaderInstance shader=RenderSystem.getShader();
        final float[] color=RenderSystem.getShaderColor().clone();
        final float fog=RenderSystem.getShaderFogStart();
        final int texture=RenderSystem.getShaderTexture(0);
        State() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport); RenderSystem.backupGlState(gl);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,scissorBox);
            for(int i=0;i<2;i++) {GlStateManager._activeTexture(GL13.GL_TEXTURE0+i);bindings[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);}
            GlStateManager._activeTexture(activeTexture);
            RenderSystem.getModelViewStack().pushMatrix();
        }
        @Override public void close() {
            RenderSystem.getModelViewStack().popMatrix(); RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection,sorting);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
            GlStateManager._viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.setShader(()->shader); RenderSystem.setShaderTexture(0,texture);
            RenderSystem.setShaderFogStart(fog); RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
            RenderSystem.restoreGlState(gl);
            for(int i=0;i<2;i++) {GlStateManager._activeTexture(GL13.GL_TEXTURE0+i);GlStateManager._bindTexture(bindings[i]);}
            GlStateManager._activeTexture(activeTexture);GlStateManager._glUseProgram(program);
            if(scissor) RenderSystem.enableScissor(scissorBox[0],scissorBox[1],scissorBox[2],scissorBox[3]);
            else RenderSystem.disableScissor();
        }
    }
    @Override public void close() {
        for(Surface surface:cache.values()) surface.close(); cache.clear(); bytes=0;
        if(SURFACES.isEmpty() && backdrop!=null) { backdrop.destroyBuffers(); backdrop=null; }
    }
}
