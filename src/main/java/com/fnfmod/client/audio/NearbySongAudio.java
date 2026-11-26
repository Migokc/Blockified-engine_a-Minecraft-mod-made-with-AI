package com.fnfmod.client.audio;

import com.fnfmod.chart.SongChart;
import com.fnfmod.chart.Conductor;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.anim.ExtraCharacterRoster;
import com.fnfmod.client.gameplay.PsychAssetResolver;
import com.fnfmod.client.gameplay.WorldCharacterLibrary;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Local streamed playback and performer visuals for non-participants near a running machine. */
public final class NearbySongAudio {
    private static final double MAX_DISTANCE = 64.0;
    private static UUID sessionId;
    private static UUID failedId;
    private static volatile FnfPayloads.NearbySongS2C latest;
    private static volatile long receivedNano;
    private static int generation;
    private static boolean loading;
    private static boolean pausedState;
    private static boolean audioEverActive;
    private static long audioStartedNano;
    private static SongEntry songEntry;
    private static List<Path> audioPaths = List.of();
    private static final List<NearbySongStreamSound> audioSounds = new ArrayList<>();
    private static SongChart chart;
    private static Conductor conductor;
    private static ExtraCharacterRoster roster;
    private static int lastBeat = Integer.MIN_VALUE;
    private static final Map<UUID, String> actions = new HashMap<>();

    private record PreparedSong(SongChart chart, List<Path> audioPaths) {}

    private NearbySongAudio() {}

    public static void onSnapshot(FnfPayloads.NearbySongS2C packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen instanceof GameplayScreen) return;
        if (!Double.isFinite(packet.songMs()) || packet.songMs() < -30_000
                || packet.songMs() > 86_400_000) return;
        if (sessionId != null && !sessionId.equals(packet.sessionId()) && latest != null) {
            double previous = mc.player.distanceToSqr(Vec3.atCenterOf(latest.pos()));
            double incoming = mc.player.distanceToSqr(Vec3.atCenterOf(packet.pos()));
            if (previous <= incoming && System.nanoTime() - receivedNano < 2_000_000_000L) return;
        }
        if (!packet.sessionId().equals(sessionId)) {
            clear();
            sessionId = packet.sessionId();
            latest = packet;
            receivedNano = System.nanoTime();
            loadLocalSong(packet);
            return;
        }
        latest = packet;
        receivedNano = System.nanoTime();
    }

    private static void loadLocalSong(FnfPayloads.NearbySongS2C packet) {
        SongEntry entry = SongLibrary.get(packet.songId());
        if (entry == null || entry.difficulties.stream().noneMatch(
                value -> value.equalsIgnoreCase(packet.difficulty()))) {
            missing(packet.sessionId());
            return;
        }
        int request = ++generation;
        loading = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                SongChart chart = SongLibrary.loadChart(entry, packet.difficulty());
                List<Path> tracks = new ArrayList<>();
                Path inst = entry.instFor(packet.difficulty());
                if (inst == null || !Files.isRegularFile(inst))
                    throw new java.io.IOException("Missing instrumental track");
                tracks.add(inst);
                if (chart.needsVoices) {
                    Path playerVoices = entry.voicesPlayerFor(packet.difficulty());
                    Path opponentVoices = entry.voicesOpponentFor(packet.difficulty());
                    if (playerVoices != null || opponentVoices != null) {
                        if (playerVoices != null && Files.isRegularFile(playerVoices)) tracks.add(playerVoices);
                        if (opponentVoices != null && Files.isRegularFile(opponentVoices)) tracks.add(opponentVoices);
                    } else {
                        Path voices = entry.voicesFor(packet.difficulty());
                        if (voices != null && Files.isRegularFile(voices)) tracks.add(voices);
                    }
                }
                return new PreparedSong(chart, List.copyOf(tracks));
            } catch (Exception error) { return null; }
        }).thenAccept(prepared -> Minecraft.getInstance().execute(() -> {
            if (request != generation || !packet.sessionId().equals(sessionId)) return;
            loading = false;
            if (prepared == null) { missing(packet.sessionId()); return; }
            chart = prepared.chart();
            conductor = new Conductor(chart);
            songEntry = entry;
            audioPaths = prepared.audioPaths();
            if (audioPaths.isEmpty()) { missing(packet.sessionId()); return; }
            preparePerformers(entry);
        }));
    }

    private static void preparePerformers(SongEntry entry) {
        if (latest == null || chart == null) return;
        CharacterAnimations.useSongFolder(entry.animationRoot(), chart.player1, chart.player2);
        roster = new ExtraCharacterRoster(latest.pos());
        PsychAssetResolver resolver = new PsychAssetResolver(entry.folder, entry,
                new PlaybackPolicy(PlaybackMode.FNF, true, false));
        roster.setCharacterResolver(name -> {
            String id = WorldCharacterLibrary.isSelection(name)
                    ? WorldCharacterLibrary.idFromSelection(name) : name;
            Path owned = resolver.character(id);
            return owned != null ? owned : WorldCharacterLibrary.resolve(id);
        });
        for (FnfPayloads.NearbyPerformer visual : latest.performers()) {
            String selection = visual.animationSet();
            if (CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(selection))
                selection = visual.playerSide() ? chart.player1 : chart.player2;
            if (ClientOptions.PERFORMER_TYPE_2D.equals(
                    ClientOptions.normalizePerformerType(visual.animationType()))) {
                String tag = visual.id().toString();
                if (roster.create(tag, selection, visual.playerSide() ? 1.5 : -1.5,
                        0, 2.0, 0, "idle", visual.playerSide() ? "player" : "opponent")) {
                    roster.anchorBottomToFloor(tag);
                    roster.setBillboard(tag, false);
                    roster.setLighting(tag, false);
                    roster.setRenderMode(tag, "flat");
                    roster.setShadow(tag, false);
                    if (visual.flipped()) roster.setFlipX(tag, !roster.flipX(tag));
                }
            } else {
                Player remote = Minecraft.getInstance().level.getPlayerByUUID(visual.id());
                if (remote != null) CharacterAnimations.prepare(remote, selection,
                        visual.playerSide() ? "player" : "opponent");
            }
        }
    }

    public static boolean replaces(Entity entity) {
        if (entity == null || latest == null || roster == null) return false;
        for (FnfPayloads.NearbyPerformer visual : latest.performers()) {
            if (entity.getUUID().equals(visual.id()) && roster.exists(visual.id().toString())) return true;
        }
        return false;
    }

    public static void onNote(FnfPayloads.NearbyNoteS2C packet) {
        if (sessionId == null || !sessionId.equals(packet.sessionId()) || latest == null
                || packet.lane() < 0 || packet.lane() > 3) return;
        for (FnfPayloads.NearbyPerformer visual : latest.performers()) {
            if (!visual.id().equals(packet.performerId())) continue;
            int lane = visual.directionsFlipped() ? switch (packet.lane()) {
                case 0 -> 3;
                case 3 -> 0;
                default -> packet.lane();
            } : packet.lane();
            String direction = new String[]{"left", "down", "up", "right"}[lane];
            if (roster != null && roster.exists(visual.id().toString()))
                roster.sing(visual.id().toString(), direction, packet.miss(), "");
            else if (Minecraft.getInstance().level != null) {
                Player remote = Minecraft.getInstance().level.getPlayerByUUID(visual.id());
                String action = packet.miss() ? "miss" : direction;
                actions.put(visual.id(), action);
                if (remote != null) CharacterAnimations.play(remote, visual.animationSet(),
                        visual.playerSide() ? "player" : "opponent", action);
            }
            return;
        }
    }

    private static void missing(UUID id) {
        if (id.equals(failedId)) return;
        failedId = id;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("Couldn't Load Song."), true);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen instanceof GameplayScreen) {
            clear();
            return;
        }
        if (latest == null) return;
        long now = System.nanoTime();
        double distance = Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(latest.pos())));
        if (now - receivedNano > 2_000_000_000L || distance > MAX_DISTANCE + 4) {
            clear();
            return;
        }
        double position = latest.songMs();
        if (!latest.paused()) position += (now - receivedNano) / 1_000_000.0;
        if (position < 0) return; // decoded before the countdown ends
        if (failedId != null && failedId.equals(sessionId)) return;
        if (roster != null && conductor != null && chart != null) {
            double songTime = Math.max(0, position + chart.offsetMs);
            double bpm = Math.max(1, conductor.bpmAt(songTime));
            double stepLength = 15_000.0 / bpm;
            int beat = (int) Math.floor(conductor.beatAt(songTime));
            if (beat != lastBeat) {
                lastBeat = beat;
                roster.beat(beat, 1);
                for (FnfPayloads.NearbyPerformer visual : latest.performers()) {
                    if (roster.exists(visual.id().toString())) continue;
                    Player remote = mc.level.getPlayerByUUID(visual.id());
                    if (remote == null) continue;
                    String role = visual.playerSide() ? "player" : "opponent";
                    String action = actions.get(visual.id());
                    if (action != null && CharacterAnimations.isActionPlaying(remote,
                            visual.animationSet(), role, action)) continue;
                    actions.remove(visual.id());
                    if (!CharacterAnimations.loopIdle(visual.animationSet(), role))
                        CharacterAnimations.play(remote, visual.animationSet(), role, "idle");
                }
            }
            roster.update(stepLength, 1.0, latest.paused() ? 0.0 : Double.NaN);
            for (FnfPayloads.NearbyPerformer visual : latest.performers()) {
                if (!roster.exists(visual.id().toString())) continue;
                Player source = mc.level.getPlayerByUUID(visual.id());
                if (source != null) roster.followFloor(visual.id().toString(), source);
            }
        }

        if (latest.paused()) {
            if (!pausedState) stopAudio();
            pausedState = true;
            return;
        }
        if (pausedState) {
            pausedState = false;
            stopAudio(); // restart from the server's paused position
        }
        if (audioSounds.isEmpty() && !loading && songEntry != null && !audioPaths.isEmpty()) {
            startAudio();
        }
        if (!audioSounds.isEmpty()) {
            Vec3 origin = Vec3.atCenterOf(latest.pos());
            for (NearbySongStreamSound sound : audioSounds) sound.setOrigin(origin);
            if (Minecraft.getInstance().getSoundManager().isActive(audioSounds.get(0))) {
                audioEverActive = true;
            } else if (audioEverActive) {
                clear(); // the instrumental stream reached EOF or the sound device stopped it
            }
        }
    }

    private static void startAudio() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || latest == null || audioPaths.isEmpty()) return;
        stopAudio();
        UUID thisSession = sessionId;
        int thisGeneration = generation;
        Vec3 origin = Vec3.atCenterOf(latest.pos());
        audioStartedNano = System.nanoTime();
        try {
            for (Path path : audioPaths) {
                NearbySongStreamSound sound = new NearbySongStreamSound(path, origin,
                        NearbySongAudio::currentPositionMs, NearbySongAudio::currentOutputGain,
                        () -> onAudioFailure(thisSession, thisGeneration));
                audioSounds.add(sound);
                mc.getSoundManager().play(sound);
            }
        } catch (RuntimeException error) {
            onAudioFailure(thisSession, thisGeneration);
        }
        audioEverActive = false;
    }

    private static double currentPositionMs() {
        FnfPayloads.NearbySongS2C packet = latest;
        if (packet == null) return 0;
        double position = packet.songMs();
        if (!packet.paused()) position += Math.max(0, System.nanoTime() - receivedNano) / 1_000_000.0;
        return Double.isFinite(position) ? Math.max(0, position) : 0;
    }

    private static double currentOutputGain() {
        Minecraft mc = Minecraft.getInstance();
        FnfPayloads.NearbySongS2C packet = latest;
        if (mc.player == null || packet == null) return 0;
        double distance = Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(packet.pos())));
        double attenuation = Math.max(0, 1 - distance / MAX_DISTANCE);
        double fade = Math.min(1, Math.max(0, System.nanoTime() - audioStartedNano) / 350_000_000.0);
        return attenuation * attenuation * fade;
    }

    private static void onAudioFailure(UUID failedSession, int failedGeneration) {
        if (failedGeneration != generation || failedSession == null || !failedSession.equals(sessionId)) return;
        stopAudio();
        missing(failedSession);
    }

    private static void stopAudio() {
        Minecraft mc = Minecraft.getInstance();
        for (NearbySongStreamSound sound : audioSounds) {
            sound.finish();
            try { mc.getSoundManager().stop(sound); } catch (RuntimeException ignored) {}
        }
        audioSounds.clear();
        audioEverActive = false;
        audioStartedNano = 0;
    }

    public static void clear() {
        generation++;
        stopAudio();
        if (roster != null) roster.close();
        if (latest != null && Minecraft.getInstance().level != null) {
            for (FnfPayloads.NearbyPerformer visual : latest.performers()) {
                if (ClientOptions.PERFORMER_TYPE_2D.equals(
                        ClientOptions.normalizePerformerType(visual.animationType()))) continue;
                Player remote = Minecraft.getInstance().level.getPlayerByUUID(visual.id());
                if (remote != null) CharacterAnimations.release(remote);
            }
        }
        roster = null;
        chart = null;
        conductor = null;
        songEntry = null;
        audioPaths = List.of();
        actions.clear();
        lastBeat = Integer.MIN_VALUE;
        latest = null;
        sessionId = null;
        loading = false;
        pausedState = false;
        failedId = null;
    }
}
