package com.fnfmod.net;

import com.fnfmod.FnfMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All custom payloads for the mod. */
public final class FnfPayloads {

    private FnfPayloads() {}

    public record SongInfo(String id, String name, List<String> difficulties, String opponentIcon, String opponentIconPath) {
        static void write(FriendlyByteBuf buf, SongInfo v) {
            buf.writeUtf(v.id);
            buf.writeUtf(v.name);
            buf.writeCollection(v.difficulties, FriendlyByteBuf::writeUtf);
            buf.writeUtf(v.opponentIcon);
            buf.writeUtf(v.opponentIconPath);
        }

        static SongInfo read(FriendlyByteBuf buf) {
            return new SongInfo(buf.readUtf(), buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf),
                    buf.readUtf(), buf.readUtf());
        }
    }

    public record FileMeta(String name, long size, String sha1) {
        static void write(FriendlyByteBuf buf, FileMeta v) {
            buf.writeUtf(v.name);
            buf.writeVarLong(v.size);
            buf.writeUtf(v.sha1);
        }

        static FileMeta read(FriendlyByteBuf buf) {
            return new FileMeta(buf.readUtf(), buf.readVarLong(), buf.readUtf());
        }
    }

    // ------------------------------------------------------------------ S2C

    /** role: 0 = host (choose a song), 1 = guest joining, 2 = machine busy */
    public record OpenMenuS2C(BlockPos pos, byte role, String hostName, List<SongInfo> songs)
            implements CustomPacketPayload {
        public static final Type<OpenMenuS2C> TYPE = new Type<>(FnfMod.id("open_menu"));
        public static final StreamCodec<FriendlyByteBuf, OpenMenuS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeByte(v.role);
                    buf.writeUtf(v.hostName);
                    buf.writeCollection(v.songs, SongInfo::write);
                },
                buf -> new OpenMenuS2C(buf.readBlockPos(), buf.readByte(), buf.readUtf(),
                        buf.readList(SongInfo::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** state: 0 = waiting for player 2, 1 = partner joined / preparing */
    public record SessionStateS2C(BlockPos pos, byte state, String songId, String difficulty, String partnerName)
            implements CustomPacketPayload {
        public static final Type<SessionStateS2C> TYPE = new Type<>(FnfMod.id("session_state"));
        public static final StreamCodec<FriendlyByteBuf, SessionStateS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeByte(v.state);
                    buf.writeUtf(v.songId);
                    buf.writeUtf(v.difficulty);
                    buf.writeUtf(v.partnerName);
                },
                buf -> new SessionStateS2C(buf.readBlockPos(), buf.readByte(), buf.readUtf(), buf.readUtf(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record FileManifestS2C(BlockPos pos, String songId, String difficulty, boolean duet, List<FileMeta> files)
            implements CustomPacketPayload {
        public static final Type<FileManifestS2C> TYPE = new Type<>(FnfMod.id("file_manifest"));
        public static final StreamCodec<FriendlyByteBuf, FileManifestS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.songId);
                    buf.writeUtf(v.difficulty);
                    buf.writeBoolean(v.duet);
                    buf.writeCollection(v.files, FileMeta::write);
                },
                buf -> new FileManifestS2C(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                        buf.readList(FileMeta::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record FileChunkS2C(String songId, String fileName, int chunkIndex, int totalChunks, byte[] data)
            implements CustomPacketPayload {
        public static final Type<FileChunkS2C> TYPE = new Type<>(FnfMod.id("file_chunk"));
        public static final StreamCodec<FriendlyByteBuf, FileChunkS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeUtf(v.songId);
                    buf.writeUtf(v.fileName);
                    buf.writeVarInt(v.chunkIndex);
                    buf.writeVarInt(v.totalChunks);
                    buf.writeByteArray(v.data);
                },
                buf -> new FileChunkS2C(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                        buf.readByteArray()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record StartSongS2C(BlockPos pos, String songId, String difficulty, boolean playerSide,
                               Optional<UUID> partnerId, String partnerName, String partnerAnimSet,
                               long startDelayMs)
            implements CustomPacketPayload {
        public static final Type<StartSongS2C> TYPE = new Type<>(FnfMod.id("start_song"));
        public static final StreamCodec<FriendlyByteBuf, StartSongS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.songId);
                    buf.writeUtf(v.difficulty);
                    buf.writeBoolean(v.playerSide);
                    buf.writeOptional(v.partnerId, (b, u) -> b.writeUUID(u));
                    buf.writeUtf(v.partnerName);
                    buf.writeUtf(v.partnerAnimSet);
                    buf.writeLong(v.startDelayMs);
                },
                buf -> new StartSongS2C(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                        buf.readOptional(b -> b.readUUID()), buf.readUtf(), buf.readUtf(), buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** judgement: 0=sick 1=good 2=bad 3=shit 4=miss */
    public record PartnerNoteS2C(int lane, byte judgement, int combo, int score) implements CustomPacketPayload {
        public static final Type<PartnerNoteS2C> TYPE = new Type<>(FnfMod.id("partner_note"));
        public static final StreamCodec<FriendlyByteBuf, PartnerNoteS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.lane);
                    buf.writeByte(v.judgement);
                    buf.writeVarInt(v.combo);
                    buf.writeVarInt(v.score);
                },
                buf -> new PartnerNoteS2C(buf.readVarInt(), buf.readByte(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record PartnerEndS2C(int score, int misses, float accuracy, boolean failed) implements CustomPacketPayload {
        public static final Type<PartnerEndS2C> TYPE = new Type<>(FnfMod.id("partner_end"));
        public static final StreamCodec<FriendlyByteBuf, PartnerEndS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeVarInt(v.score);
                    buf.writeVarInt(v.misses);
                    buf.writeFloat(v.accuracy);
                    buf.writeBoolean(v.failed);
                },
                buf -> new PartnerEndS2C(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SessionCancelS2C(BlockPos pos, String reason) implements CustomPacketPayload {
        public static final Type<SessionCancelS2C> TYPE = new Type<>(FnfMod.id("session_cancel"));
        public static final StreamCodec<FriendlyByteBuf, SessionCancelS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.reason);
                },
                buf -> new SessionCancelS2C(buf.readBlockPos(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ------------------------------------------------------------------ C2S

    /** playSide (solo only): 0 = player, 1 = opponent, 2 = both */
    public record SelectSongC2S(BlockPos pos, String songId, String difficulty, boolean duet, byte playSide)
            implements CustomPacketPayload {
        public static final Type<SelectSongC2S> TYPE = new Type<>(FnfMod.id("select_song"));
        public static final StreamCodec<FriendlyByteBuf, SelectSongC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.songId);
                    buf.writeUtf(v.difficulty);
                    buf.writeBoolean(v.duet);
                    buf.writeByte(v.playSide);
                },
                buf -> new SelectSongC2S(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                        buf.readByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RequestFilesC2S(BlockPos pos, String songId, List<String> fileNames) implements CustomPacketPayload {
        public static final Type<RequestFilesC2S> TYPE = new Type<>(FnfMod.id("request_files"));
        public static final StreamCodec<FriendlyByteBuf, RequestFilesC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.songId);
                    buf.writeCollection(v.fileNames, FriendlyByteBuf::writeUtf);
                },
                buf -> new RequestFilesC2S(buf.readBlockPos(), buf.readUtf(), buf.readList(FriendlyByteBuf::readUtf)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ReadyC2S(BlockPos pos, String animSet) implements CustomPacketPayload {
        public static final Type<ReadyC2S> TYPE = new Type<>(FnfMod.id("ready"));
        public static final StreamCodec<FriendlyByteBuf, ReadyC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.animSet);
                },
                buf -> new ReadyC2S(buf.readBlockPos(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record NoteEventC2S(BlockPos pos, int lane, byte judgement, int combo, int score)
            implements CustomPacketPayload {
        public static final Type<NoteEventC2S> TYPE = new Type<>(FnfMod.id("note_event"));
        public static final StreamCodec<FriendlyByteBuf, NoteEventC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeVarInt(v.lane);
                    buf.writeByte(v.judgement);
                    buf.writeVarInt(v.combo);
                    buf.writeVarInt(v.score);
                },
                buf -> new NoteEventC2S(buf.readBlockPos(), buf.readVarInt(), buf.readByte(), buf.readVarInt(),
                        buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SongEndC2S(BlockPos pos, int score, int misses, float accuracy, boolean failed)
            implements CustomPacketPayload {
        public static final Type<SongEndC2S> TYPE = new Type<>(FnfMod.id("song_end"));
        public static final StreamCodec<FriendlyByteBuf, SongEndC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeVarInt(v.score);
                    buf.writeVarInt(v.misses);
                    buf.writeFloat(v.accuracy);
                    buf.writeBoolean(v.failed);
                },
                buf -> new SongEndC2S(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(),
                        buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Requests one server-run command event; the server validates it against the active chart. */
    public record CommandEventC2S(BlockPos pos, int eventIndex) implements CustomPacketPayload {
        public static final Type<CommandEventC2S> TYPE = new Type<>(FnfMod.id("command_event"));
        public static final StreamCodec<FriendlyByteBuf, CommandEventC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeVarInt(v.eventIndex());
                },
                buf -> new CommandEventC2S(buf.readBlockPos(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Asks the server to rescan its song library (requires op on dedicated servers). */
    public record ReloadC2S() implements CustomPacketPayload {
        public static final Type<ReloadC2S> TYPE = new Type<>(FnfMod.id("reload"));
        public static final StreamCodec<FriendlyByteBuf, ReloadC2S> CODEC = StreamCodec.unit(new ReloadC2S());

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** finishedOnly = the song ended normally; just restore my position, don't cancel anything. */
    /** Vanilla HUD mode: drive the real hearts. health clamped server-side, never lethal. */
    public record SetHealthC2S(float health) implements CustomPacketPayload {
        public static final Type<SetHealthC2S> TYPE = new Type<>(FnfMod.id("set_health"));
        public static final StreamCodec<FriendlyByteBuf, SetHealthC2S> CODEC = StreamCodec.of(
                (buf, v) -> buf.writeFloat(v.health),
                buf -> new SetHealthC2S(buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** reopenMenu = after leaving, recreate the session and push the song menu instead of returning to the world. */
    public record LeaveC2S(BlockPos pos, boolean finishedOnly, boolean reopenMenu) implements CustomPacketPayload {
        public static final Type<LeaveC2S> TYPE = new Type<>(FnfMod.id("leave"));
        public static final StreamCodec<FriendlyByteBuf, LeaveC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeBoolean(v.finishedOnly);
                    buf.writeBoolean(v.reopenMenu);
                },
                buf -> new LeaveC2S(buf.readBlockPos(), buf.readBoolean(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
