package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Captures BBS's direct VAO model render into Minecraft's outline framebuffer.
 * BBS bypasses MultiBufferSource, so the ordinary entity-outline path never sees
 * its form. The second render is a flat alpha silhouette, not enlarged geometry.
 */
public final class BbsObjectBorderRenderer {
    private static ShaderInstance shader;
    private static ShaderInstance entityShader;
    private static ShaderInstance compositeShader;
    private static RenderTarget captureTarget;
    private static boolean capturing;
    private static float borderRed;
    private static float borderGreen;
    private static float borderBlue;
    private static float borderAlpha;
    private static boolean initialized;
    private static boolean available;

    private static Method morphGet;
    private static Method morphGetForm;
    private static Field morphEntity;
    private static Constructor<?> contextConstructor;
    private static Method contextSet;
    private static Method contextCamera;
    private static Method contextColor;
    private static Object entityRenderType;
    private static Method renderForm;

    private BbsObjectBorderRenderer() {}

    public static void registerShader(RegisterShadersEvent event) throws java.io.IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                        FnfMod.id("bbs_object_border"), DefaultVertexFormat.NEW_ENTITY),
                loaded -> shader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                        FnfMod.id("entity_object_border"), DefaultVertexFormat.POSITION_TEX_COLOR),
                loaded -> entityShader = loaded);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                        FnfMod.id("bbs_object_border_composite"), DefaultVertexFormat.BLIT_SCREEN),
                loaded -> compositeShader = loaded);
    }

    public static boolean capturing() {
        return capturing;
    }

    public static ShaderInstance shader() {
        return shader;
    }

    public static ShaderInstance entityShader() {
        return entityShader;
    }

    /** Re-applies the per-entity color immediately before ShaderInstance uploads uniforms. */
    public static void beforeShaderApply(ShaderInstance candidate) {
        if (!capturing || candidate != shader) return;
        Uniform color = candidate.getUniform("BorderColor");
        if (color != null) color.set(borderRed, borderGreen, borderBlue, borderAlpha);
    }

    /** BBS enables normal alpha blending around every material; masks need exact bytes. */
    public static void afterShaderApply(ShaderInstance candidate) {
        if (capturing && candidate == shader) RenderSystem.disableBlend();
    }

    public static void capture(AbstractClientPlayer player, PoseStack poseStack,
                               int packedLight, float tickDelta) {
        // IRLights renders the same BBS morph again from every shadow-map
        // camera. Those passes must cast shadows, but must never write into
        // Minecraft's visible entity-outline target: their light-relative
        // views otherwise accumulate there and make the border brightness
        // change as the performer moves through world Y.
        if (capturing || IrlightsShadowCompat.isBaking()) return;

        ObjectBorderRegistry.Capture border = ObjectBorderRegistry.capture(player);
        Minecraft minecraft = Minecraft.getInstance();
        if (border == null || shader == null || compositeShader == null
                || minecraft.levelRenderer == null) return;
        RenderTarget target = minecraft.levelRenderer.entityTarget();
        if (target == null || !initialize() || !prepareCaptureTarget(target)) return;

        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return;
            Object form = morphGetForm.invoke(morph);
            Object entity = morphEntity.get(morph);
            if (form == null || entity == null) return;

            Object context = contextConstructor.newInstance();
            int overlay = LivingEntityRenderer.getOverlayCoords(player, 0.0F);
            // A border is a flat screen-space effect. Never feed the player's
            // position-dependent block/sky light into BBS fallback renderers
            // or nested forms, otherwise moving the fake player vertically can
            // tint the captured silhouette before the outline post pass.
            context = contextSet.invoke(context, entityRenderType, entity, poseStack,
                    LightTexture.FULL_BRIGHT, overlay, tickDelta);
            context = contextCamera.invoke(context, minecraft.gameRenderer.getMainCamera());
            contextColor.invoke(context, -1);

            int rgb = border.color();
            Uniform color = shader.getUniform("BorderColor");
            if (color == null) return;
            borderRed = ((rgb >> 16) & 255) / 255.0F;
            borderGreen = ((rgb >> 8) & 255) / 255.0F;
            borderBlue = (rgb & 255) / 255.0F;
            borderAlpha = border.encodedAlpha() / 255.0F;
            color.set(borderRed, borderGreen, borderBlue, borderAlpha);

            // BBS can issue extra internal draws with its own shaders (hybrid
            // pieces, equipment and nested forms). Capture all of them in an
            // isolated mask so their texture/light RGB can never become a
            // second, usually black, outline seed.
            captureTarget.clear(Minecraft.ON_OSX);
            captureTarget.copyDepthFrom(minecraft.getMainRenderTarget());
            captureTarget.bindWrite(false);
            // This form can still be drawn by BBS/free-cam after its backing
            // chunk leaves Minecraft's normal entity render pass. In that case
            // LevelRenderer never sees an outlined entity and would skip the
            // outline post-process, exposing this target as a black silhouette.
            minecraft.levelRenderer.requestOutlineEffect();
            capturing = true;
            renderForm.invoke(null, form, context);
            capturing = false;
            compositeCapture(target);
        } catch (Throwable error) {
            available = false;
            FnfMod.LOGGER.warn("Failed to capture a BBS form outline: {}", error.toString());
        } finally {
            capturing = false;
            // The BBS capture intentionally disables blending so the outline
            // target receives the exact color/width bytes. Do not leak that
            // temporary state into Minecraft's world or GUI render passes.
            RenderSystem.enableBlend();
            minecraft.getMainRenderTarget().bindWrite(false);
        }
    }

    private static boolean prepareCaptureTarget(RenderTarget reference) {
        if (reference.width <= 0 || reference.height <= 0) return false;
        if (captureTarget != null
                && captureTarget.width == reference.width
                && captureTarget.height == reference.height) {
            return true;
        }
        if (captureTarget != null) captureTarget.destroyBuffers();
        captureTarget = new TextureTarget(reference.width, reference.height, true, Minecraft.ON_OSX);
        captureTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        return true;
    }

    /** Writes a single exact color/radius for every BBS fragment in the private mask. */
    private static void compositeCapture(RenderTarget destination) {
        destination.bindWrite(false);
        RenderSystem.viewport(0, 0, destination.width, destination.height);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.disableBlend();

        compositeShader.setSampler("DiffuseSampler", captureTarget.getColorTextureId());
        Uniform color = compositeShader.getUniform("BorderColor");
        if (color != null) color.set(borderRed, borderGreen, borderBlue, borderAlpha);
        compositeShader.apply();

        BufferBuilder builder = RenderSystem.renderThreadTesselator()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        builder.addVertex(0.0F, 0.0F, 0.0F);
        builder.addVertex(1.0F, 0.0F, 0.0F);
        builder.addVertex(1.0F, 1.0F, 0.0F);
        builder.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(builder.buildOrThrow());
        compositeShader.clear();

        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);
    }

    private static synchronized boolean initialize() {
        if (initialized) return available;
        initialized = true;
        try {
            Class<?> morphClass = Class.forName("mchorse.bbs_mod.morphing.Morph");
            Class<?> formClass = Class.forName("mchorse.bbs_mod.forms.forms.Form");
            Class<?> contextClass = Class.forName("mchorse.bbs_mod.forms.renderers.FormRenderingContext");
            Class<?> renderTypeClass = Class.forName("mchorse.bbs_mod.forms.renderers.FormRenderType");
            Class<?> entityClass = Class.forName("mchorse.bbs_mod.forms.entities.IEntity");
            Class<?> formUtilsClass = Class.forName("mchorse.bbs_mod.forms.FormUtilsClient");

            morphGet = morphClass.getMethod("getMorph", net.minecraft.world.entity.Entity.class);
            morphGetForm = morphClass.getMethod("getForm");
            morphEntity = morphClass.getField("entity");
            contextConstructor = contextClass.getConstructor();
            contextSet = contextClass.getMethod("set", renderTypeClass, entityClass,
                    PoseStack.class, int.class, int.class, float.class);
            contextCamera = contextClass.getMethod("camera", net.minecraft.client.Camera.class);
            contextColor = contextClass.getMethod("color", int.class);
            entityRenderType = renderTypeClass.getField("ENTITY").get(null);
            renderForm = formUtilsClass.getMethod("render", formClass, contextClass);
            available = true;
        } catch (Throwable error) {
            available = false;
            FnfMod.LOGGER.warn("BBS form outlines unavailable: {}", error.toString());
        }
        return available;
    }
}
