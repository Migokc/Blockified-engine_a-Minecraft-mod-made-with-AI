package com.fnfmod.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Wire codec for the small set of local note-display settings shared at song start. */
public final class NoteVisualsCodec {
    private NoteVisualsCodec() {}

    public static final StreamCodec<FriendlyByteBuf, FnfPayloads.NoteVisuals> CODEC =
            StreamCodec.of(NoteVisualsCodec::write, NoteVisualsCodec::read);

    private static void write(FriendlyByteBuf buf, FnfPayloads.NoteVisuals visuals) {
        buf.writeUtf(visuals.noteSkin(), 128);
        buf.writeUtf(visuals.noteSkinSource(), 16);
        buf.writeUtf(visuals.splashSkin(), 128);
        buf.writeUtf(visuals.splashSkinSource(), 16);
        buf.writeUtf(visuals.holdSplashSkin(), 128);
        buf.writeUtf(visuals.holdSplashSkinSource(), 16);
        buf.writeBoolean(visuals.downscroll());
        for (int color : visuals.base()) buf.writeVarInt(color);
        for (int color : visuals.highlight()) buf.writeVarInt(color);
        for (int color : visuals.outline()) buf.writeVarInt(color);
        var skin = visuals.skinConfig();
        buf.writeBoolean(skin.rgb());
        writePart(buf, skin.note());
        writePart(buf, skin.receptor());
        writePart(buf, skin.sustain());
        writePart(buf, skin.splash());
        writePart(buf, skin.holdCover());
    }

    private static FnfPayloads.NoteVisuals read(FriendlyByteBuf buf) {
        String noteSkin = buf.readUtf(128), noteSource = buf.readUtf(16);
        String splashSkin = buf.readUtf(128), splashSource = buf.readUtf(16);
        String holdSkin = buf.readUtf(128), holdSource = buf.readUtf(16);
        boolean downscroll = buf.readBoolean();
        int[] base = new int[4], highlight = new int[4], outline = new int[4];
        for (int i = 0; i < 4; i++) base[i] = buf.readVarInt();
        for (int i = 0; i < 4; i++) highlight[i] = buf.readVarInt();
        for (int i = 0; i < 4; i++) outline[i] = buf.readVarInt();
        boolean rgb = buf.readBoolean();
        var skin = new com.fnfmod.client.render.NoteSkinConfig(rgb,
                readPart(buf), readPart(buf), readPart(buf), readPart(buf), readPart(buf));
        return new FnfPayloads.NoteVisuals(noteSkin, noteSource, splashSkin, splashSource,
                holdSkin, holdSource, downscroll, base, highlight, outline, skin);
    }

    private static void writePart(FriendlyByteBuf buf,
                                  com.fnfmod.client.render.NoteSkinConfig.Part part) {
        buf.writeFloat(part.scale());
        buf.writeFloat(part.alpha());
        buf.writeFloat(part.x());
        buf.writeFloat(part.y());
    }

    private static com.fnfmod.client.render.NoteSkinConfig.Part readPart(FriendlyByteBuf buf) {
        return new com.fnfmod.client.render.NoteSkinConfig.Part(
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }
}
