package com.fnfmod.world;

import com.fnfmod.FnfMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Selects which installed content may be exposed in the current world.
 * Bundled worlds own exactly one pack; ordinary worlds expose all configured content.
 */
public final class ModContentScope {

    public enum Mode {
        /** A bundled world may use content from its owning pack only. */
        MOD_WORLD,
        /** Main menu or an ordinary world: all installed packs/directories are available. */
        ALL
    }

    public record ActiveMod(String id, String worldId, Path root, Path worldRoot) {}

    private record State(Mode mode, ActiveMod activeMod) {}

    private static volatile State state = new State(Mode.ALL, null);

    private ModContentScope() {}

    public static Path modsRoot() {
        return FMLPaths.CONFIGDIR.get().resolve("fnfmod").resolve("mods")
                .toAbsolutePath().normalize();
    }

    public static Mode mode() {
        return state.mode();
    }

    public static Optional<ActiveMod> activeMod() {
        return Optional.ofNullable(state.activeMod());
    }

    public static boolean isModWorld() {
        return state.mode() == Mode.MOD_WORLD && state.activeMod() != null;
    }

    /** Bind content visibility before the server scans its song library. */
    public static synchronized void bindWorld(Path worldRoot) {
        Optional<ActiveMod> detected = detect(worldRoot);
        if (detected.isPresent()) {
            ActiveMod active = detected.get();
            state = new State(Mode.MOD_WORLD, active);
            FnfMod.LOGGER.info("World {} uses FNF mod content from {}", active.worldId(), active.id());
            return;
        }
        state = new State(Mode.ALL, null);
        FnfMod.LOGGER.info("World has no bundled FNF mod owner; all configured FNF content is available");
    }

    public static synchronized void clear() {
        state = new State(Mode.ALL, null);
    }

    /** LAN client binding. Server supplies an ID; only an exact direct child of mods/ is accepted. */
    public static synchronized boolean bindClientMod(String modId) {
        if (modId == null || modId.isBlank() || modId.contains("/") || modId.contains("\\")) return false;
        Path mods = modsRoot();
        if (!Files.isDirectory(mods)) return false;
        try (Stream<Path> children = Files.list(mods)) {
            Path match = children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals(modId))
                    .findFirst().orElse(null);
            if (match == null || !isContainedByRealPath(mods, match)) return false;
            Path root = match.toAbsolutePath().normalize();
            state = new State(Mode.MOD_WORLD,
                    new ActiveMod(modId, "lan-remote", root, root.resolve("worlds")));
            return true;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    /** Detect exact config/fnfmod/mods/&lt;mod&gt;/worlds/&lt;world&gt; ownership. */
    public static Optional<ActiveMod> detect(Path worldRoot) {
        if (worldRoot == null) return Optional.empty();
        Path mods = modsRoot();
        Path world = worldRoot.toAbsolutePath().normalize();
        if (!world.startsWith(mods)) return Optional.empty();

        Path relative = mods.relativize(world);
        if (relative.getNameCount() != 3
                || !relative.getName(1).toString().equalsIgnoreCase("worlds")) {
            return Optional.empty();
        }

        String modId = relative.getName(0).toString();
        String worldId = relative.getName(2).toString();
        if (modId.isBlank() || worldId.isBlank()) return Optional.empty();
        Path modRoot = mods.resolve(modId).normalize();
        Path expectedWorld = modRoot.resolve("worlds").resolve(worldId).normalize();
        if (!world.equals(expectedWorld) || !Files.isRegularFile(world.resolve("level.dat"))) {
            return Optional.empty();
        }
        if (!isContainedByRealPath(modRoot, world)) return Optional.empty();
        return Optional.of(new ActiveMod(modId, worldId, modRoot, world));
    }

    /** True when current scope permits reading this pack/external-content path. */
    public static boolean allowsContentPath(Path candidate) {
        if (candidate == null) return false;
        State current = state;
        if (current.mode() == Mode.ALL) return true;
        if (current.mode() != Mode.MOD_WORLD || current.activeMod() == null) return false;
        return isContainedByRealPath(current.activeMod().root(), candidate);
    }

    /** Resolve an active-pack-relative path, rejecting absolute paths, traversal, and symlink escape. */
    public static Optional<Path> resolveActive(String relativePath) {
        State current = state;
        if (current.mode() != Mode.MOD_WORLD || current.activeMod() == null
                || relativePath == null) {
            return Optional.empty();
        }
        try {
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute()) return Optional.empty();
            Path target = current.activeMod().root().resolve(relative).normalize();
            return isContainedByRealPath(current.activeMod().root(), target)
                    ? Optional.of(target) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static boolean isContainedByRealPath(Path root, Path candidate) {
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedCandidate = candidate.toAbsolutePath().normalize();
            if (!normalizedCandidate.startsWith(normalizedRoot)) return false;

            Path realRoot = Files.exists(normalizedRoot)
                    ? normalizedRoot.toRealPath() : normalizedRoot;
            Path existing = normalizedCandidate;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null) return false;
            return existing.toRealPath().startsWith(realRoot);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }
}
