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

    public record FileManifestS2C(BlockPos pos, String songId, String difficulty, boolean duet,
                                  boolean opponentSide, byte playbackMode, boolean songAssets,
                                  List<FileMeta> files)
            implements CustomPacketPayload {
        public static final Type<FileManifestS2C> TYPE = new Type<>(FnfMod.id("file_manifest"));
        public static final StreamCodec<FriendlyByteBuf, FileManifestS2C> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.songId);
                    buf.writeUtf(v.difficulty);
                    buf.writeBoolean(v.duet);
                    buf.writeBoolean(v.opponentSide);
                    buf.writeByte(v.playbackMode);
                    buf.writeBoolean(v.songAssets);
                    buf.writeCollection(v.files, FileMeta::write);
                },
                buf -> new FileManifestS2C(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                        buf.readBoolean(), buf.readByte(), buf.readBoolean(),
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
                               int botEntityId, long startDelayMs)
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
                    buf.writeVarInt(v.botEntityId + 1);
                    buf.writeLong(v.startDelayMs);
                },
                buf -> new StartSongS2C(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                        buf.readOptional(b -> b.readUUID()), buf.readUtf(), buf.readUtf(),
                        buf.readVarInt() - 1, buf.readLong()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Confirms that the server restored a solo session and the client may rebuild gameplay. */
    public record RestartSongS2C(BlockPos pos, int botEntityId, long startDelayMs)
            implements CustomPacketPayload {
        public static final Type<RestartSongS2C> TYPE = new Type<>(FnfMod.id("restart_song_ack"));
        public static final StreamCodec<FriendlyByteBuf, RestartSongS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.pos());
                    buf.writeVarInt(value.botEntityId() + 1);
                    buf.writeLong(value.startDelayMs());
                },
                buf -> new RestartSongS2C(buf.readBlockPos(), buf.readVarInt() - 1, buf.readLong()));

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

    /** Server acknowledgement sent only after an Edit Chart rollback has completed. */
    public record RollbackCompleteS2C(BlockPos pos) implements CustomPacketPayload {
        public static final Type<RollbackCompleteS2C> TYPE = new Type<>(FnfMod.id("rollback_complete"));
        public static final StreamCodec<FriendlyByteBuf, RollbackCompleteS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBlockPos(value.pos()),
                buf -> new RollbackCompleteS2C(buf.readBlockPos()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenMachineEditorS2C(BlockPos pos, String profileId) implements CustomPacketPayload {
        public static final Type<OpenMachineEditorS2C> TYPE = new Type<>(FnfMod.id("open_machine_editor"));
        public static final StreamCodec<FriendlyByteBuf, OpenMachineEditorS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.pos());
                    buf.writeUtf(value.profileId(), 128);
                },
                buf -> new OpenMachineEditorS2C(buf.readBlockPos(), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record MachineEditorResultS2C(boolean success, String message, String profileId,
                                         boolean refresh) implements CustomPacketPayload {
        public static final Type<MachineEditorResultS2C> TYPE = new Type<>(FnfMod.id("machine_editor_result"));
        public static final StreamCodec<FriendlyByteBuf, MachineEditorResultS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBoolean(value.success());
                    buf.writeUtf(value.message(), 1024);
                    buf.writeUtf(value.profileId(), 128);
                    buf.writeBoolean(value.refresh());
                },
                buf -> new MachineEditorResultS2C(buf.readBoolean(), buf.readUtf(1024),
                        buf.readUtf(128), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenMachineMenuS2C(BlockPos pos, String profileId, String machineData,
                                     String modId, String packVersion, List<SongInfo> songs)
            implements CustomPacketPayload {
        public static final Type<OpenMachineMenuS2C> TYPE = new Type<>(FnfMod.id("open_machine_menu"));
        public static final StreamCodec<FriendlyByteBuf, OpenMachineMenuS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.pos());
                    buf.writeUtf(value.profileId(), 128);
                    buf.writeUtf(value.machineData(), 32767);
                    buf.writeUtf(value.modId(), 128);
                    buf.writeUtf(value.packVersion(), 128);
                    buf.writeCollection(value.songs(), SongInfo::write);
                },
                buf -> new OpenMachineMenuS2C(buf.readBlockPos(), buf.readUtf(128),
                        buf.readUtf(32767), buf.readUtf(128), buf.readUtf(128),
                        buf.readList(SongInfo::read)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Active bundled-mod scope handshake for integrated/LAN clients. Empty ID means no mod assets. */
    public record ModScopeS2C(String modId, String packVersion) implements CustomPacketPayload {
        public static final Type<ModScopeS2C> TYPE = new Type<>(FnfMod.id("mod_scope"));
        public static final StreamCodec<FriendlyByteBuf, ModScopeS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.modId(), 128);
                    buf.writeUtf(value.packVersion(), 128);
                },
                buf -> new ModScopeS2C(buf.readUtf(128), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenHitboxBuilderS2C(String profileId) implements CustomPacketPayload {
        public static final Type<OpenHitboxBuilderS2C> TYPE = new Type<>(FnfMod.id("open_hitbox_builder"));
        public static final StreamCodec<FriendlyByteBuf, OpenHitboxBuilderS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.profileId(), 128),
                buf -> new OpenHitboxBuilderS2C(buf.readUtf(128)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** stage: 0 clear, 1 choosing first point, 2 choosing second point, 3 ready for anchor. */
    public record HitboxSelectionStateS2C(byte stage, byte mode,
                                          double minX, double minY, double minZ,
                                          double maxX, double maxY, double maxZ) implements CustomPacketPayload {
        public static final Type<HitboxSelectionStateS2C> TYPE = new Type<>(FnfMod.id("hitbox_selection_state"));
        public static final StreamCodec<FriendlyByteBuf, HitboxSelectionStateS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.stage());
                    buf.writeByte(value.mode());
                    buf.writeDouble(value.minX()); buf.writeDouble(value.minY()); buf.writeDouble(value.minZ());
                    buf.writeDouble(value.maxX()); buf.writeDouble(value.maxY()); buf.writeDouble(value.maxZ());
                },
                buf -> new HitboxSelectionStateS2C(buf.readByte(), buf.readByte(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readDouble()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Opens a client confirmation screen before deleting a virtual-machine hitbox. */
    public record ConfirmHitboxRemovalS2C(BlockPos anchorPos, UUID groupId) implements CustomPacketPayload {
        public static final Type<ConfirmHitboxRemovalS2C> TYPE =
                new Type<>(FnfMod.id("confirm_hitbox_removal"));
        public static final StreamCodec<FriendlyByteBuf, ConfirmHitboxRemovalS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.anchorPos());
                    buf.writeUUID(value.groupId());
                },
                buf -> new ConfirmHitboxRemovalS2C(buf.readBlockPos(), buf.readUUID()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OpenChunkLoaderEditorS2C(BlockPos pos, String tag, int radius, boolean enabled)
            implements CustomPacketPayload {
        public static final Type<OpenChunkLoaderEditorS2C> TYPE =
                new Type<>(FnfMod.id("open_chunk_loader_editor"));
        public static final StreamCodec<FriendlyByteBuf, OpenChunkLoaderEditorS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.pos());
                    buf.writeUtf(value.tag(), 64);
                    buf.writeVarInt(value.radius());
                    buf.writeBoolean(value.enabled());
                },
                buf -> new OpenChunkLoaderEditorS2C(buf.readBlockPos(), buf.readUtf(64),
                        buf.readVarInt(), buf.readBoolean()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChunkLoaderEditorResultS2C(boolean success, String message,
                                              String tag, int radius, boolean enabled)
            implements CustomPacketPayload {
        public static final Type<ChunkLoaderEditorResultS2C> TYPE =
                new Type<>(FnfMod.id("chunk_loader_editor_result"));
        public static final StreamCodec<FriendlyByteBuf, ChunkLoaderEditorResultS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBoolean(value.success());
                    buf.writeUtf(value.message(), 512);
                    buf.writeUtf(value.tag(), 64);
                    buf.writeVarInt(value.radius());
                    buf.writeBoolean(value.enabled());
                },
                buf -> new ChunkLoaderEditorResultS2C(buf.readBoolean(), buf.readUtf(512),
                        buf.readUtf(64), buf.readVarInt(), buf.readBoolean()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ------------------------------------------------------------------ C2S

    /** playSide (solo only): 0 = player, 1 = opponent, 2 = both. */
    public record SelectSongC2S(BlockPos pos, String songId, String difficulty, boolean duet,
                                byte playSide, byte playbackMode)
            implements CustomPacketPayload {
        public static final Type<SelectSongC2S> TYPE = new Type<>(FnfMod.id("select_song"));
        public static final StreamCodec<FriendlyByteBuf, SelectSongC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.songId);
                    buf.writeUtf(v.difficulty);
                    buf.writeBoolean(v.duet);
                    buf.writeByte(v.playSide);
                    buf.writeByte(v.playbackMode);
                },
                buf -> new SelectSongC2S(buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                        buf.readByte(), buf.readByte()));

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

    public record ReadyC2S(BlockPos pos, String animSet, double offsetX, double offsetY,
                           double offsetZ, float rotationOffset) implements CustomPacketPayload {
        public static final Type<ReadyC2S> TYPE = new Type<>(FnfMod.id("ready"));
        public static final StreamCodec<FriendlyByteBuf, ReadyC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeUtf(v.animSet);
                    buf.writeDouble(v.offsetX);
                    buf.writeDouble(v.offsetY);
                    buf.writeDouble(v.offsetZ);
                    buf.writeFloat(v.rotationOffset);
                },
                buf -> new ReadyC2S(buf.readBlockPos(), buf.readUtf(), buf.readDouble(),
                        buf.readDouble(), buf.readDouble(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Requests an authoritative reset of an active solo song session. */
    public record RestartSongC2S(BlockPos pos) implements CustomPacketPayload {
        public static final Type<RestartSongC2S> TYPE = new Type<>(FnfMod.id("restart_song_request"));
        public static final StreamCodec<FriendlyByteBuf, RestartSongC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBlockPos(value.pos()),
                buf -> new RestartSongC2S(buf.readBlockPos()));

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

    /** Requests a tracked Lua command. Server preserves player/server runner semantics. */
    public record LuaCommandC2S(BlockPos pos, String command, String runner) implements CustomPacketPayload {
        public static final Type<LuaCommandC2S> TYPE = new Type<>(FnfMod.id("lua_command"));
        public static final StreamCodec<FriendlyByteBuf, LuaCommandC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.pos());
                    buf.writeUtf(value.command(), 32767);
                    buf.writeUtf(value.runner(), 16);
                },
                buf -> new LuaCommandC2S(buf.readBlockPos(), buf.readUtf(32767), buf.readUtf(16)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Asks the server to rescan its song library (requires op on dedicated servers).
     * The process/generation pair lets an integrated server reuse the client scan
     * that just ran in the same JVM instead of scanning the same directories twice.
     */
    public record ReloadC2S(long processNonce, int generation) implements CustomPacketPayload {
        public static final Type<ReloadC2S> TYPE = new Type<>(FnfMod.id("reload"));
        public static final StreamCodec<FriendlyByteBuf, ReloadC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeLong(value.processNonce());
                    buf.writeVarInt(value.generation());
                },
                buf -> new ReloadC2S(buf.readLong(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** finishedOnly = the song ended normally; just restore my position, don't cancel anything. */
    /** Vanilla HUD mode: drive Minecraft's real hearts and food while preserving pre-song state. */
    public record SyncVanillaHudC2S(float health, int foodLevel) implements CustomPacketPayload {
        public static final Type<SyncVanillaHudC2S> TYPE = new Type<>(FnfMod.id("sync_vanilla_hud"));
        public static final StreamCodec<FriendlyByteBuf, SyncVanillaHudC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeFloat(value.health);
                    buf.writeVarInt(value.foodLevel);
                },
                buf -> new SyncVanillaHudC2S(buf.readFloat(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Return target after leaving: world/no menu, built-in selector, or owning custom machine menu. */
    public record LeaveC2S(BlockPos pos, boolean finishedOnly, byte returnTarget) implements CustomPacketPayload {
        public static final byte RETURN_WORLD = 0;
        public static final byte RETURN_SELECTOR = 1;
        public static final byte RETURN_MACHINE_MENU = 2;
        /** Internal transition: acknowledge rollback, then let the client open its chart editor. */
        public static final byte RETURN_CHART_EDITOR = 3;
        public static final Type<LeaveC2S> TYPE = new Type<>(FnfMod.id("leave"));
        public static final StreamCodec<FriendlyByteBuf, LeaveC2S> CODEC = StreamCodec.of(
                (buf, v) -> {
                    buf.writeBlockPos(v.pos);
                    buf.writeBoolean(v.finishedOnly);
                    buf.writeByte(v.returnTarget);
                },
                buf -> new LeaveC2S(buf.readBlockPos(), buf.readBoolean(), buf.readByte()));

        public static byte normalizeReturnTarget(byte target) {
            return target == RETURN_SELECTOR || target == RETURN_MACHINE_MENU ? target : RETURN_WORLD;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** action: 0 save selection, 1 create starter profile, 2 reload active pack machines. */
    public record MachineEditC2S(BlockPos pos, byte action, String value) implements CustomPacketPayload {
        public static final Type<MachineEditC2S> TYPE = new Type<>(FnfMod.id("machine_edit"));
        public static final StreamCodec<FriendlyByteBuf, MachineEditC2S> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeBlockPos(payload.pos());
                    buf.writeByte(payload.action());
                    buf.writeUtf(payload.value(), 128);
                },
                buf -> new MachineEditC2S(buf.readBlockPos(), buf.readByte(), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** action: 0 open built-in song selector, 1 persist machineData SNBT. */
    public record MachineMenuActionC2S(BlockPos pos, byte action, String value) implements CustomPacketPayload {
        public static final Type<MachineMenuActionC2S> TYPE = new Type<>(FnfMod.id("machine_menu_action"));
        public static final StreamCodec<FriendlyByteBuf, MachineMenuActionC2S> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeBlockPos(payload.pos());
                    buf.writeByte(payload.action());
                    buf.writeUtf(payload.value(), 32767);
                },
                buf -> new MachineMenuActionC2S(buf.readBlockPos(), buf.readByte(), buf.readUtf(32767)));

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** action: 0 begin/restart selection, 1 cancel. mode: 0 full blocks, 1 precise. */
    public record HitboxBuilderC2S(byte action, byte mode, String profileId) implements CustomPacketPayload {
        public static final Type<HitboxBuilderC2S> TYPE = new Type<>(FnfMod.id("hitbox_builder"));
        public static final StreamCodec<FriendlyByteBuf, HitboxBuilderC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.action());
                    buf.writeByte(value.mode());
                    buf.writeUtf(value.profileId(), 128);
                },
                buf -> new HitboxBuilderC2S(buf.readByte(), buf.readByte(), buf.readUtf(128)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Confirms deletion only if the same anchor/group still exists and remains reachable. */
    public record ConfirmHitboxRemovalC2S(BlockPos anchorPos, UUID groupId) implements CustomPacketPayload {
        public static final Type<ConfirmHitboxRemovalC2S> TYPE =
                new Type<>(FnfMod.id("confirm_hitbox_removal_response"));
        public static final StreamCodec<FriendlyByteBuf, ConfirmHitboxRemovalC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.anchorPos());
                    buf.writeUUID(value.groupId());
                },
                buf -> new ConfirmHitboxRemovalC2S(buf.readBlockPos(), buf.readUUID()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Direct launch requested by a sandboxed custom machine menu. */
    public record MachineDirectPlayC2S(BlockPos pos, String songId, String difficulty,
                                       boolean duet, byte playSide, byte playbackMode)
            implements CustomPacketPayload {
        public static final Type<MachineDirectPlayC2S> TYPE = new Type<>(FnfMod.id("machine_direct_play"));
        public static final StreamCodec<FriendlyByteBuf, MachineDirectPlayC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.pos());
                    buf.writeUtf(value.songId(), 256);
                    buf.writeUtf(value.difficulty(), 128);
                    buf.writeBoolean(value.duet());
                    buf.writeByte(value.playSide());
                    buf.writeByte(value.playbackMode());
                },
                buf -> new MachineDirectPlayC2S(buf.readBlockPos(), buf.readUtf(256),
                        buf.readUtf(128), buf.readBoolean(), buf.readByte(), buf.readByte()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ChunkLoaderEditC2S(BlockPos pos, String tag, int radius, boolean enabled)
            implements CustomPacketPayload {
        public static final Type<ChunkLoaderEditC2S> TYPE = new Type<>(FnfMod.id("chunk_loader_edit"));
        public static final StreamCodec<FriendlyByteBuf, ChunkLoaderEditC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBlockPos(value.pos());
                    buf.writeUtf(value.tag(), 64);
                    buf.writeVarInt(value.radius());
                    buf.writeBoolean(value.enabled());
                },
                buf -> new ChunkLoaderEditC2S(buf.readBlockPos(), buf.readUtf(64),
                        buf.readVarInt(), buf.readBoolean()));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Gameplay Lua mutation: chunkLoadPoints.&lt;tag&gt;.(enabled|radius|tag). */
    public record ChunkLoaderPropertyC2S(BlockPos machinePos, String tag, String property, String value)
            implements CustomPacketPayload {
        public static final Type<ChunkLoaderPropertyC2S> TYPE =
                new Type<>(FnfMod.id("chunk_loader_property"));
        public static final StreamCodec<FriendlyByteBuf, ChunkLoaderPropertyC2S> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeBlockPos(payload.machinePos());
                    buf.writeUtf(payload.tag(), 64);
                    buf.writeUtf(payload.property(), 16);
                    buf.writeUtf(payload.value(), 128);
                },
                buf -> new ChunkLoaderPropertyC2S(buf.readBlockPos(), buf.readUtf(64),
                        buf.readUtf(16), buf.readUtf(128)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
