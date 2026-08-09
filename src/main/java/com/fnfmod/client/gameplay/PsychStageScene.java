package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.fnfmod.client.render.SpriteImageCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Static visual objects declared by Psych 1.0 stage JSON files. */
final class PsychStageScene implements AutoCloseable {
    static final class Element implements AutoCloseable {
        final String type;
        final double x, y, scaleX, scaleY, scrollX, scrollY, alpha, angle;
        final boolean flipX, flipY;
        final int color;
        final ResourceLocation texture;
        final DynamicTexture dynamicTexture;
        final SpriteImageCache.Handle imageHandle;
        final int width, height;

        private Element(String type) {
            this.type = type;
            x = y = 0;
            scaleX = scaleY = scrollX = scrollY = 1;
            alpha = 1;
            angle = 0;
            flipX = flipY = false;
            color = 0xFFFFFFFF;
            texture = null;
            dynamicTexture = null;
            imageHandle = null;
            width = height = 0;
        }

        private Element(JsonObject object, Path image) throws Exception {
            type = "sprite";
            x = number(object, "x", 0);
            y = number(object, "y", 0);
            double[] scale = pair(object, "scale", 1, 1);
            scaleX = scale[0];
            scaleY = scale[1];
            double[] scroll = pair(object, "scroll", 1, 1);
            scrollX = scroll[0];
            scrollY = scroll[1];
            alpha = number(object, "alpha", 1);
            angle = number(object, "angle", 0);
            flipX = bool(object, "flipX", false);
            flipY = bool(object, "flipY", false);
            color = color(string(object, "color", "FFFFFF"));
            imageHandle = SpriteImageCache.acquire(image, bool(object, "antialiasing", true));
            if (imageHandle == null) throw new IllegalStateException("image could not be loaded");
            width = imageHandle.width();
            height = imageHandle.height();
            dynamicTexture = imageHandle.dynamicTexture();
            texture = imageHandle.textureId();
        }

        static Element role(String role) { return new Element(role); }

        boolean isRole() {
            return type.equals("gf") || type.equals("dad") || type.equals("boyfriend");
        }

        void render(GuiGraphics gui, double cameraX, double cameraY) {
            if (texture == null || alpha <= 0) return;
            double scrollLeft = cameraX - 640;
            double scrollTop = cameraY - 360;
            double drawX = x + scrollLeft * (1 - scrollX);
            double drawY = y + scrollTop * (1 - scrollY);
            int a = Math.max(0, Math.min(255, (int) Math.round(alpha * 255)));
            float red = ((color >> 16) & 255) / 255f;
            float green = ((color >> 8) & 255) / 255f;
            float blue = (color & 255) / 255f;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            gui.setColor(red, green, blue, a / 255f);
            gui.pose().pushPose();
            gui.pose().translate(drawX + width * scaleX * 0.5, drawY + height * scaleY * 0.5, 0);
            if (angle != 0) gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) angle));
            gui.pose().scale((float) (flipX ? -scaleX : scaleX), (float) (flipY ? -scaleY : scaleY), 1);
            gui.blit(texture, -width / 2, -height / 2, 0, 0, width, height, width, height);
            gui.pose().popPose();
            gui.setColor(1, 1, 1, 1);
        }

        @Override public void close() {
            if (imageHandle != null) imageHandle.close();
        }
    }

    private final List<Element> elements;

    private PsychStageScene(List<Element> elements) { this.elements = elements; }

    static PsychStageScene load(JsonObject stage, PsychAssetResolver assets) {
        List<Element> elements = new ArrayList<>();
        if (stage == null || !stage.has("objects") || !stage.get("objects").isJsonArray()) {
            return new PsychStageScene(elements);
        }
        for (JsonElement raw : stage.getAsJsonArray("objects")) {
            if (!raw.isJsonObject()) continue;
            JsonObject object = raw.getAsJsonObject();
            String type = string(object, "type", "sprite").toLowerCase(Locale.ROOT);
            if (type.equals("gf") || type.equals("dad") || type.equals("boyfriend")) {
                elements.add(Element.role(type));
                continue;
            }
            if (!type.equals("sprite")) continue;
            Path image = assets == null ? null : assets.image(string(object, "image", ""));
            if (image == null) continue;
            try {
                elements.add(new Element(object, image));
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Could not load Psych stage sprite {}: {}", image, error.toString());
            }
        }
        return new PsychStageScene(elements);
    }

    List<Element> elements() { return elements; }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static double number(JsonObject object, String key, double fallback) {
        try { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static double[] pair(JsonObject object, String key, double x, double y) {
        try {
            JsonArray array = object.getAsJsonArray(key);
            return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble()};
        } catch (Exception ignored) { return new double[]{x, y}; }
    }

    private static int color(String raw) {
        try { return (int) (Long.parseLong(raw.replace("#", ""), 16) | 0xFF000000L); }
        catch (Exception ignored) { return 0xFFFFFFFF; }
    }

    @Override public void close() {
        for (Element element : elements) element.close();
        elements.clear();
    }
}
