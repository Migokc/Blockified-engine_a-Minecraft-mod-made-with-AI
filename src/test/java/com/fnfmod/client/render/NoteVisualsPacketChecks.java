package com.fnfmod.client.render;

import com.fnfmod.net.FnfPayloads;
import com.fnfmod.net.NoteVisualsCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Verifies that Blockified-only per-part note transforms survive the ready handshake. */
public final class NoteVisualsPacketChecks {
    public static void main(String[] args) {
        NoteSkinConfig expected = new NoteSkinConfig(false,
                new NoteSkinConfig.Part(1.25f, 0.7f, 12.5f, -8f),
                new NoteSkinConfig.Part(0.9f, 0.6f, -3f, 5.25f),
                new NoteSkinConfig.Part(1.4f, 0.5f, 7f, 11f),
                new NoteSkinConfig.Part(0.8f, 0.4f, -9f, 2f),
                new NoteSkinConfig.Part(1.7f, 0.3f, 4.5f, 35f));
        FnfPayloads.NoteVisuals visuals = new FnfPayloads.NoteVisuals("test", "global",
                "spark", "current", "hold", "global", true,
                new int[]{1, 2, 3, 4}, new int[]{5, 6, 7, 8}, new int[]{9, 10, 11, 12}, expected);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NoteVisualsCodec.CODEC.encode(buffer, visuals);
            FnfPayloads.NoteVisuals received = NoteVisualsCodec.CODEC.decode(buffer);
            check(received.skinConfig().equals(expected),
                    "RGB and note/receptor/sustain/splash/hold-cover transforms must survive network round-trip");
        } finally {
            buffer.release();
        }
        System.out.println("Per-side note visual packet checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
