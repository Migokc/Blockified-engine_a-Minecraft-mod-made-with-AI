package com.fnfmod.song;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.CodenameChartParser;
import com.fnfmod.chart.LegacyChartParser;
import com.fnfmod.chart.SongChart;
import com.fnfmod.chart.VSliceChartParser;
import com.fnfmod.character.CharacterDefinitionPaths;
import com.fnfmod.world.ModContentScope;
import com.fnfmod.world.ModWorldOptions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Scans lightweight config/fnfmod/songs entries and complete config/fnfmod/mods packs.
 * Each side (server, client) scans its own folder; in multiplayer the
 * server's library is authoritative and files are streamed to clients.
 */
public class SongLibrary {

    /** Optional resource groups that can be enabled independently for every external directory. */
    public enum ExternalContent {
        CHARTS("Charts"), AUDIO("Song Audio"), EVENTS("Events"), LUA("Lua"),
        IMAGES("Images"), ICONS("Icons"), CHARACTERS("Characters"), FONTS("Fonts");

        public final String label;

        ExternalContent(String label) {
            this.label = label;
        }
    }

    public static EnumSet<ExternalContent> allExternalContent() {
        return EnumSet.allOf(ExternalContent.class);
    }

    /** Lightweight config/fnfmod/songs entries intentionally have no runtime asset surface. */
    public static EnumSet<ExternalContent> basicSongContent() {
        return EnumSet.of(ExternalContent.CHARTS, ExternalContent.AUDIO, ExternalContent.EVENTS);
    }

    /** A chart-only local override uses this file to inherit assets from its original mod. */
    public static final String ORIGINAL_DIRECTORY_FILE = "original_directory.txt";

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("fnfmod");
    }

    public static Path songsDir() {
        return root().resolve("songs");
    }

    /** Complete FNF engine-style mod packs, one folder per mod. */
    public static Path modsDir() {
        return root().resolve("mods");
    }

    /**
     * Subfolder names inside {@code mods/} that are the naked global mod's own
     * content, never a named mod pack. Skipped when enumerating mods/ subfolders.
     */
    public static final Set<String> RESERVED_MOD_SUBDIRS = Set.of(
            "animations", "scripts", "fonts", "images", "songs", "data", "characters",
            "stages", "weeks", "sounds", "music", "videos", "machines", "worlds",
            "custom_events", "custom_notetypes", "notetypes", "events", "shaders",
            "achievements", "credits", "source", "shared", "assets", "_merge");

    /** Global Psych Lua scripts that run for every song. */
    public static Path scriptsDir() {
        return modsDir().resolve("scripts");
    }

    /** Loose TTF/OTF files available to every Lua script. */
    public static Path fontsDir() {
        return modsDir().resolve("fonts");
    }

    public static Path cacheDir() {
        return root().resolve("cache");
    }

    public static Path skinsDir() {
        return root().resolve("skins");
    }

    public static Path animationsDir() {
        return modsDir().resolve("animations");
    }

    public static Path hitsoundsDir() {
        return root().resolve("hitsounds");
    }

    public static Path splashesDir() {
        return root().resolve("splashes");
    }

    /** Global health icons, Psych-style, under the naked global mod's images/icons. */
    public static Path iconsDir() {
        return modsDir().resolve("images").resolve("icons");
    }

    private static Map<String, SongEntry> songs = new LinkedHashMap<>();
    /** icon name -> png discovered while scanning song/mod folders */
    private static Map<String, Path> extraIcons = new LinkedHashMap<>();

    public static synchronized Map<String, Path> getExtraIcons() {
        return extraIcons;
    }

    /** Bumped on every rescan so client caches (IconLibrary) can auto-refresh. */
    private static int rescanGen;
    /** Identifies this JVM so integrated-server reloads are not repeated. */
    private static final long PROCESS_NONCE = ThreadLocalRandom.current().nextLong();

    public static synchronized int rescanGeneration() {
        return rescanGen;
    }

    public static long processNonce() {
        return PROCESS_NONCE;
    }

    private static boolean migrated;

    public static void ensureFolders() {
        migrateGlobalFolders();
        try {
            Files.createDirectories(songsDir());
            Files.createDirectories(modsDir());
            Files.createDirectories(scriptsDir());
            Files.createDirectories(fontsDir());
            Files.createDirectories(skinsDir());
            Files.createDirectories(animationsDir());
            Files.createDirectories(hitsoundsDir());
            Files.createDirectories(splashesDir());
            Files.createDirectories(splashesDir().resolve("holdSplashes"));
            Files.createDirectories(iconsDir());
        } catch (IOException e) {
            FnfMod.LOGGER.error("Could not create fnfmod config folders", e);
        }
    }

    /**
     * One-time move of the pre-2.2 global folders into the naked global mod:
     * config/fnfmod/{animations,scripts,fonts} -&gt; mods/{...} and the old flat
     * config/fnfmod/icons -&gt; mods/images/icons. Runs once per process; each old
     * folder disappears after it is moved, so subsequent starts are no-ops.
     */
    private static synchronized void migrateGlobalFolders() {
        if (migrated) return;
        migrated = true;
        moveFolderContents(root().resolve("animations"), animationsDir());
        moveFolderContents(root().resolve("scripts"), scriptsDir());
        moveFolderContents(root().resolve("fonts"), fontsDir());
        moveFolderContents(root().resolve("icons"), iconsDir());
    }

    /** Moves every child of {@code oldDir} into {@code newDir}, then removes an emptied {@code oldDir}. */
    private static void moveFolderContents(Path oldDir, Path newDir) {
        try {
            if (oldDir == null || newDir == null) return;
            oldDir = oldDir.toAbsolutePath().normalize();
            newDir = newDir.toAbsolutePath().normalize();
            if (oldDir.equals(newDir) || !Files.isDirectory(oldDir)) return;
            Files.createDirectories(newDir);
            try (Stream<Path> children = Files.list(oldDir)) {
                for (Path child : children.toList()) {
                    Path target = newDir.resolve(child.getFileName().toString());
                    if (Files.exists(target)) continue; // never clobber content already in the new layout
                    try {
                        Files.move(child, target);
                    } catch (IOException moveError) {
                        FnfMod.LOGGER.warn("Could not migrate {} -> {}: {}", child, target, moveError.toString());
                    }
                }
            }
            try (Stream<Path> remaining = Files.list(oldDir)) {
                if (remaining.findAny().isEmpty()) Files.delete(oldDir);
            }
            FnfMod.LOGGER.info("Migrated global folder {} into {}", oldDir, newDir);
        } catch (IOException e) {
            FnfMod.LOGGER.warn("Failed to migrate global folder {}: {}", oldDir, e.toString());
        }
    }

    public static synchronized void rescan() {
        long started = System.nanoTime();
        ensureFolders();
        // A mod world normally sees only its owning pack; a world that opted into external
        // content also scans installed packs and configured directories, like an ALL world.
        boolean modWorldExternal = ModContentScope.mode() == ModContentScope.Mode.MOD_WORLD
                && ModWorldOptions.allowExternalContent();
        boolean scanEverything = ModContentScope.mode() == ModContentScope.Mode.ALL || modWorldExternal;
        List<String> externalFolders = scanEverything ? getExternalFolders() : List.of();
        Map<String, EnumSet<ExternalContent>> externalContent = new LinkedHashMap<>();
        List<Path> fingerprintRoots = new ArrayList<>();
        StringBuilder fingerprintConfig = new StringBuilder("mode=").append(ModContentScope.mode());
        if (!ModContentScope.isModWorld()) fingerprintRoots.add(songsDir());
        if (!ModContentScope.isModWorld()) addOriginalDirectoryRoots(fingerprintRoots);
        if (ModContentScope.mode() == ModContentScope.Mode.MOD_WORLD) {
            ModContentScope.activeMod().ifPresent(active -> {
                fingerprintConfig.append("\nactive=").append(active.id()).append('|')
                        .append(active.root().toAbsolutePath().normalize());
                fingerprintRoots.add(active.root());
            });
        }
        if (scanEverything) {
            fingerprintRoots.add(modsDir());
            String installedSource = modsDir().toAbsolutePath().normalize().toString();
            fingerprintConfig.append("\ninstalled=").append(getExternalFolderContent(installedSource));
            for (ModPackInfo pack : discoverModPacks(modsDir())) {
                fingerprintConfig.append("\ninstalled-pack=").append(pack.root()).append('|')
                        .append(getExternalPackContent(installedSource, pack.root()));
            }
            for (String folder : externalFolders) {
                EnumSet<ExternalContent> content = getExternalFolderContent(folder);
                externalContent.put(folder, content);
                fingerprintConfig.append("\nexternal=").append(folder).append('|').append(content);
                try {
                    Path source = Path.of(folder);
                    fingerprintRoots.add(source);
                    for (ModPackInfo pack : discoverModPacks(source)) {
                        fingerprintConfig.append("\nexternal-pack=").append(pack.root()).append('|')
                                .append(getExternalPackContent(folder, pack.root()));
                    }
                } catch (Exception ignored) {}
            }
        }
        String fingerprint = SongLibraryIndex.fingerprint(fingerprintConfig.toString(), fingerprintRoots);
        SongLibraryIndex.Snapshot cached = SongLibraryIndex.load(
                SongLibraryIndex.file(cacheDir()), fingerprint);
        if (cached != null) {
            songs = cached.songs();
            extraIcons = cached.icons();
            rescanGen++;
            FnfMod.LOGGER.info("FNF song library: {} song(s), {} extra icon(s), cached in {} ms",
                    songs.size(), extraIcons.size(), elapsedMillis(started));
            return;
        }

        Map<String, SongEntry> found = new LinkedHashMap<>();
        Map<String, Path> icons = new LinkedHashMap<>();
        // A bundled mod world is intentionally isolated to its owning pack.
        // Ordinary worlds retain the complete lightweight/global library.
        if (!ModContentScope.isModWorld()) {
            try (Stream<Path> dirs = Files.list(songsDir())) {
                dirs.filter(Files::isDirectory).sorted().forEach(dir -> {
                    SongEntry entry = scanSong(dir);
                    if (entry != null) found.put(entry.id, entry);
                });
            } catch (IOException e) {
                FnfMod.LOGGER.error("Failed to scan songs folder", e);
            }
        }
        // A bundled world sees only its owner. Every other world sees all installed
        // packs and configured external directories.
        if (ModContentScope.mode() == ModContentScope.Mode.MOD_WORLD) {
            ModContentScope.activeMod().ifPresent(active -> scanPsychRoot(active.root(), found, icons));
        }
        if (scanEverything) {
            scanModsDir(found, icons);
            for (String folder : externalFolders) {
                try {
                    EnumSet<ExternalContent> content = Objects.requireNonNullElseGet(
                            externalContent.get(folder), SongLibrary::allExternalContent);
                    scanExternalRoot(folder, Path.of(folder), found, icons, content);
                } catch (Exception e) {
                    FnfMod.LOGGER.warn("Failed to scan external folder {}: {}", folder, e.toString());
                }
            }
        }
        // assign difficulty keys now that every folder's variations are gathered
        found.values().removeIf(e -> {
            if (e.format != SongEntry.Format.VSLICE) return false;
            finalizeVSlice(e);
            return e.difficulties.isEmpty();
        });
        // resolve each song's opponent icon from ITS OWN mod (names can clash across mods)
        for (SongEntry e : found.values()) resolveOpponentIcon(e);
        songs = found;
        extraIcons = icons;
        rescanGen++;
        SongLibraryIndex.save(SongLibraryIndex.file(cacheDir()), fingerprint, songs, icons);
        FnfMod.LOGGER.info("FNF song library: {} song(s), {} extra icon(s), rebuilt in {} ms",
                songs.size(), icons.size(), elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    /** Referenced source packs also invalidate the index when their inherited assets change. */
    private static void addOriginalDirectoryRoots(List<Path> roots) {
        if (!Files.isDirectory(songsDir())) return;
        try (Stream<Path> directories = Files.list(songsDir())) {
            directories.filter(Files::isDirectory).sorted().forEach(directory -> {
                Path reference = directory.resolve(ORIGINAL_DIRECTORY_FILE);
                if (!Files.isRegularFile(reference)) return;
                try {
                    List<String> lines = Files.readAllLines(reference);
                    if (lines.isEmpty() || lines.get(0).isBlank()) return;
                    Path source = Path.of(lines.get(0).trim());
                    if (!source.isAbsolute()) source = directory.resolve(source);
                    source = source.toAbsolutePath().normalize();
                    if (Files.isDirectory(source) && !roots.contains(source)) roots.add(source);
                } catch (Exception ignored) {}
            });
        } catch (IOException ignored) {}
    }

    /** Resolves a song's opponent icon to a file within its own mod (falls back to a bare name). */
    private static void resolveOpponentIcon(SongEntry e) {
        if (e.opponentIcon == null || e.opponentIcon.isEmpty()) return;
        String charId = e.opponentIcon;
        Path characterRoot = e.characterRoot != null ? e.characterRoot
                : (e.modRoot != null ? e.modRoot : e.folder);
        String iconName = charId;
        if (characterRoot != null) {
            // the character json names the actual health icon (V-Slice healthIcon.id / Psych healthicon)
            boolean characterDefinitionFound = false;
            if (e.allows(ExternalContent.CHARACTERS)) {
                for (String sub : new String[]{"data/characters", "characters"}) {
                    Path cj = characterRoot.resolve(sub).resolve(charId + ".json");
                    if (Files.isRegularFile(cj)) {
                        characterDefinitionFound = true;
                        String hi = readHealthIconName(cj);
                        if (hi != null && !hi.isEmpty()) iconName = hi;
                        break;
                    }
                }

                // Blockified-only characters may exist solely as BBS animation
                // definitions. Use their authored icon when no Psych/V-Slice
                // character JSON exists; a real character definition always wins
                // when both use the same name.
                if (!characterDefinitionFound) {
                    Path animationJson = CharacterDefinitionPaths.modCharacterJson(
                            e.animationRoot(), charId, true);
                    if (animationJson == null) {
                        animationJson = CharacterDefinitionPaths.globalCharacterJson(charId, true);
                    }
                    String animationIcon = readBlockifiedAnimationIcon(animationJson);
                    if (animationIcon != null && !animationIcon.isEmpty()) iconName = animationIcon;
                }
            }
            // find the icon png inside this mod
            if (e.allows(ExternalContent.ICONS)) {
                LinkedHashMap<Path, Boolean> roots = new LinkedHashMap<>();
                if (e.modRoot != null) roots.put(e.modRoot, Boolean.TRUE);
                roots.put(characterRoot, Boolean.TRUE);
                for (Path root : roots.keySet()) {
                    for (String sub : new String[]{"images/icons", "icons", "images/characters", ""}) {
                        Path dir = sub.isEmpty() ? root : root.resolve(sub);
                        for (String fn : new String[]{"icon-" + iconName + ".png", iconName + ".png"}) {
                            Path p = dir.resolve(fn);
                            if (Files.isRegularFile(p)) {
                                e.opponentIconFile = p;
                                e.opponentIcon = iconName;
                                return;
                            }
                        }
                    }
                }
            }
        }
        // no file in this mod: keep the name for the client's global icon lookup
        e.opponentIcon = iconName;
    }

    private static String readBlockifiedAnimationIcon(Path animationJson) {
        if (animationJson == null || !Files.isRegularFile(animationJson)) return null;
        try {
            JsonObject object = JsonParser.parseString(Files.readString(animationJson)).getAsJsonObject();
            return LegacyChartParser.optString(object, "icon", null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readHealthIconName(Path characterJson) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(characterJson)).getAsJsonObject();
            if (o.has("healthIcon")) {
                var hi = o.get("healthIcon");
                if (hi.isJsonObject() && hi.getAsJsonObject().has("id")) {
                    return hi.getAsJsonObject().get("id").getAsString();
                }
                if (hi.isJsonPrimitive()) return hi.getAsString();
            }
            return LegacyChartParser.optString(o, "healthicon", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Collects icon-*.png / health icon pngs from a folder tree (Psych + V-Slice layouts). */
    private static void collectIcons(Path dir, Map<String, Path> icons) {
        // Psych: images/icons/icon-<char>.png ; V-Slice: images/icons/icon-<char>.png too
        for (String sub : new String[]{"images/icons", "icons", "images/characters"}) {
            Path p = dir.resolve(sub);
            if (!Files.isDirectory(p)) continue;
            boolean iconDir = sub.endsWith("icons"); // dedicated icon dirs also hold bare <name>.png (Codename)
            try (Stream<Path> files = Files.list(p)) {
                files.filter(Files::isRegularFile).forEach(f -> {
                    String orig = f.getFileName().toString();
                    String n = orig.toLowerCase(Locale.ROOT);
                    if (!n.endsWith(".png")) return;
                    if (n.startsWith("icon-")) {
                        icons.putIfAbsent(orig.substring(5, orig.length() - 4), f);
                    } else if (iconDir) {
                        icons.putIfAbsent(orig.substring(0, orig.length() - 4), f);
                    }
                });
            } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------ download cache

    public static long cacheSizeBytes() {
        return SongCache.sizeBytes(cacheDir());
    }

    public static void clearCache() {
        SongCache.clear(cacheDir());
    }

    /** Deletes cached songs that haven't been used for the given number of days. */
    public static void pruneCache(int days) {
        SongCache.prune(cacheDir(), days);
    }

    /** Refreshes a cached song's timestamp so pruning knows it's still in use. */
    public static void touchCacheEntry(Path dir) {
        SongCache.touch(cacheDir(), dir);
    }

    // ------------------------------------------------------------ external Psych folders

    /** User-selected folders scanned with the Psych Engine mod layout. */
    public static List<String> getExternalFolders() {
        return new ExternalDirectoryConfig(root()).folders();
    }

    public static void setExternalFolders(List<String> folders) {
        new ExternalDirectoryConfig(root()).setFolders(folders);
    }

    /** Missing entries deliberately mean every category, preserving pre-checklist installations. */
    public static EnumSet<ExternalContent> getExternalFolderContent(String folder) {
        return new ExternalDirectoryConfig(root()).content(folder);
    }

    public static void setExternalFolderContent(String folder, ExternalContent content, boolean enabled) {
        new ExternalDirectoryConfig(root()).setContent(folder, content, enabled);
    }

    public static String packFilterKey(String source, Path pack) {
        return ExternalDirectoryConfig.packKey(source, pack);
    }

    /** A pack inherits its source checklist until it receives an explicit override. */
    public static EnumSet<ExternalContent> getExternalPackContent(String source, Path pack) {
        ExternalDirectoryConfig config = new ExternalDirectoryConfig(root());
        EnumSet<ExternalContent> selected = config.contentOrNull(packFilterKey(source, pack));
        return selected == null ? config.content(source) : selected;
    }

    public static void setExternalPackContent(String source, Path pack, ExternalContent content, boolean enabled) {
        String key = packFilterKey(source, pack);
        ExternalDirectoryConfig config = new ExternalDirectoryConfig(root());
        // First edit starts from the inherited source selection, not from All.
        if (config.contentOrNull(key) == null) config.setContent(key, config.content(source));
        config.setContent(key, content, enabled);
    }

    /**
     * Finds isolated Psych-style packs immediately inside a selected mods root.
     * A non-reserved child is a pack even without pack.json/pack.png, matching
     * Psych's convention. If there are no such children, the selected directory
     * itself is exposed as a single pack when it contains mod content/metadata.
     */
    public static List<ModPackInfo> discoverModPacks(Path source) {
        if (source == null || !Files.isDirectory(source)) return List.of();
        if (isDirectModSelection(source)) return List.of(ModPackInfo.read(source));
        List<ModPackInfo> packs = new ArrayList<>();
        try (Stream<Path> children = Files.list(source)) {
            children.filter(Files::isDirectory).sorted().forEach(child -> {
                String key = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!RESERVED_MOD_SUBDIRS.contains(key)) packs.add(ModPackInfo.read(child));
            });
        } catch (IOException ignored) {}
        return List.copyOf(packs);
    }

    public static boolean isDirectModPath(Path source) {
        return source != null && Files.isDirectory(source) && isDirectModSelection(source);
    }

    private static boolean isDirectModSelection(Path source) {
        if (source.toAbsolutePath().normalize().equals(modsDir().toAbsolutePath().normalize())) return false;
        if (Files.isRegularFile(source.resolve("pack.json"))
                || Files.isRegularFile(source.resolve("pack.png"))
                || Files.isRegularFile(source.resolve("_polymod_meta.json"))
                || Files.isRegularFile(source.resolve("_polymod_icon.png"))) return true;
        Path name = source.getFileName();
        if (!isModFolder(source)) return false;
        if (name == null || !name.toString().equalsIgnoreCase("mods")) return true;
        // A path ending in "mods" is ambiguous: Psych commonly uses it as a
        // container, while some standalone packs put their content directly in
        // a folder with that name. Non-reserved child folders make it a
        // container; otherwise its standard content folders make it one mod.
        try (Stream<Path> children = Files.list(source)) {
            return children.filter(Files::isDirectory).noneMatch(child ->
                    !RESERVED_MOD_SUBDIRS.contains(child.getFileName().toString().toLowerCase(Locale.ROOT)));
        } catch (IOException ignored) {
            return true;
        }
    }

    /**
     * The naked global mod ({@code config/fnfmod/mods} root) as a Psych-style shared
     * asset base: its loose {@code characters/}, {@code images/}, {@code stages/},
     * {@code sounds/}, etc. fall through to every song. Returns {@code null} inside a
     * bundled mod world, which stays isolated to its owning pack.
     */
    public static Path globalSharedAssetRoot() {
        if (ModContentScope.mode() != ModContentScope.Mode.ALL) return null;
        Path root = modsDir();
        return Files.isDirectory(root) ? root : null;
    }

    /**
     * Shared Psych assets come only from the first configured directory. This
     * keeps directory order meaningful without leaking same-named assets from
     * unrelated packs lower in the song-search list.
     */
    public static Path primaryExternalAssetRoot(ExternalContent content) {
        if (ModContentScope.mode() != ModContentScope.Mode.ALL) return null;
        List<String> folders = getExternalFolders();
        if (folders.isEmpty()) return null;
        String first = folders.get(0);
        if (!getExternalFolderContent(first).contains(content)) return null;
        try {
            Path root = Path.of(first).toAbsolutePath().normalize();
            return Files.isDirectory(root) ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Scans a folder with the Psych Engine mod structure:
     * data/&lt;song&gt;/*.json charts + songs/&lt;song&gt;/*.ogg audio.
     * If the folder itself isn't a mod, its direct subfolders are checked
     * (so a whole Psych "mods" directory can be added at once).
     */
    private static void scanPsychRoot(Path root, Map<String, SongEntry> found, Map<String, Path> icons) {
        scanPsychRoot(root, found, icons, allExternalContent());
    }

    /**
     * Scans the mods/ directory, which is a hybrid: it is both the naked global
     * mod (its own loose data/songs/images, e.g. images/icons for global health
     * icons) and the container of named mod packs (mods/&lt;NAME&gt;). Because the
     * naked global mod's images/ folder would make {@link #isModFolder} treat all
     * of mods/ as a single pack, both passes run explicitly here, and the global
     * folders in {@link #RESERVED_MOD_SUBDIRS} are never scanned as named mods.
     */
    private static void scanModsDir(Map<String, SongEntry> found, Map<String, Path> icons) {
        Path mods = modsDir();
        if (!Files.isDirectory(mods)) return;
        String source = mods.toAbsolutePath().normalize().toString();
        Set<ExternalContent> sourceContent = getExternalFolderContent(source);
        // Naked global mod: its own loose content (songs, images/icons, ...).
        if (isModFolder(mods)) scanPsychMod(mods, found, icons, sourceContent);
        else collectIcons(mods, icons);
        // Named mod packs: every subfolder that is not a reserved global folder.
        try (Stream<Path> subs = Files.list(mods)) {
            subs.filter(Files::isDirectory).sorted().forEach(sub -> {
                if (RESERVED_MOD_SUBDIRS.contains(sub.getFileName().toString().toLowerCase(Locale.ROOT))) return;
                if (isModFolder(sub)) scanPsychMod(sub, found, icons,
                        getExternalPackContent(source, sub));
            });
        } catch (IOException ignored) {}
    }

    private static void scanPsychRoot(Path root, Map<String, SongEntry> found, Map<String, Path> icons,
                                      Set<ExternalContent> content) {
        if (!Files.isDirectory(root)) return;
        if (isDirectModSelection(root)) {
            scanPsychMod(root, found, icons, content);
            return;
        }
        // Scan loose/shared content at the selected root, then every named child
        // independently. Previously an images/ folder made a Psych mods container
        // look like one giant mod and blended all child packs together.
        if (isModFolder(root)) scanPsychMod(root, found, icons, content);
        try (Stream<Path> subs = Files.list(root)) {
            subs.filter(Files::isDirectory).sorted().forEach(sub -> {
                String key = sub.getFileName().toString().toLowerCase(Locale.ROOT);
                if (RESERVED_MOD_SUBDIRS.contains(key)) return;
                scanPsychMod(sub, found, icons, content);
            });
        } catch (IOException ignored) {}
    }

    private static void scanExternalRoot(String source, Path root, Map<String, SongEntry> found,
                                         Map<String, Path> icons, Set<ExternalContent> sourceContent) {
        if (!Files.isDirectory(root)) return;
        if (isDirectModSelection(root)) {
            scanPsychMod(root, found, icons, getExternalPackContent(source, root));
            return;
        }
        if (isModFolder(root)) scanPsychMod(root, found, icons, sourceContent);
        for (ModPackInfo pack : discoverModPacks(root)) {
            scanPsychMod(pack.root(), found, icons, getExternalPackContent(source, pack.root()));
        }
    }

    private static boolean isModFolder(Path p) {
        return Files.isDirectory(p.resolve("data")) || Files.isDirectory(p.resolve("songs"))
                || Files.isDirectory(p.resolve("images")) || Files.isDirectory(p.resolve("characters"))
                || Files.isDirectory(p.resolve("animations")) || Files.isDirectory(p.resolve("scripts"))
                || Files.isDirectory(p.resolve("machines")) || Files.isDirectory(p.resolve("worlds"))
                || Files.isDirectory(p.resolve("custom_events"))
                || Files.isDirectory(p.resolve("custom_notetypes"));
    }

    private static void scanPsychMod(Path mod, Map<String, SongEntry> found, Map<String, Path> icons,
                                     Set<ExternalContent> content) {
        if (content.contains(ExternalContent.ICONS)) collectIcons(mod, icons);
        // Optional resources may still be discovered, but a playable song needs both groups.
        if (!content.contains(ExternalContent.CHARTS) || !content.contains(ExternalContent.AUDIO)) return;
        // Codename Engine: songs/<song>/{charts/,meta.json,song/} — check before Psych/V-Slice
        if (isCodenameMod(mod)) {
            scanCodenameMod(mod, found, icons, content);
            return;
        }
        Path data = mod.resolve("data");
        Path songsDir = mod.resolve("songs");
        // V-Slice keeps charts in data/songs/<song>/ instead of data/<song>/
        if (Files.isDirectory(data.resolve("songs")) || !Files.isDirectory(data)) {
            scanVSliceMod(mod, found, content);
            return;
        }

        try (Stream<Path> dirs = Files.list(data)) {
            dirs.filter(Files::isDirectory).sorted().forEach(songDir -> {
                String id = songDir.getFileName().toString();
                if (found.containsKey(id)) return; // local config songs win

                SongEntry entry = new SongEntry();
                entry.id = id;
                entry.displayName = id;
                entry.folder = songDir;
                entry.format = SongEntry.Format.LEGACY;
                entry.modRoot = mod;
                entry.fullModLayout = true;
                entry.externalContent = EnumSet.copyOf(content);

                try (Stream<Path> files = Files.list(songDir)) {
                    files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                            .sorted()
                            .forEach(j -> {
                                String base = j.getFileName().toString();
                                base = base.substring(0, base.length() - 5).toLowerCase(Locale.ROOT);
                                if (base.equals("events")) {
                                    if (content.contains(ExternalContent.EVENTS)) entry.eventsFile = j;
                                    return;
                                }
                                try {
                                    JsonObject chartRoot = JsonParser.parseString(Files.readString(j)).getAsJsonObject();
                                    if (!LegacyChartParser.looksLikeLegacy(chartRoot)) return;
                                    String diff = difficultyFromFilename(base, id.toLowerCase(Locale.ROOT));
                                    entry.legacyChartFiles.put(diff, j);
                                    JsonObject songObj = chartRoot.has("song") && chartRoot.get("song").isJsonObject()
                                            ? chartRoot.getAsJsonObject("song") : chartRoot;
                                    if (entry.displayName.equals(id)) {
                                        entry.displayName = LegacyChartParser.optString(songObj, "song", id);
                                    }
                                    if (entry.opponentIcon.isEmpty()) {
                                        entry.opponentIcon = LegacyChartParser.optString(songObj, "player2", "dad");
                                        entry.modRoot = mod;
                                    }
                                } catch (Exception ignored) {}
                            });
                } catch (IOException ignored) {}
                if (entry.legacyChartFiles.isEmpty()) return;
                entry.difficulties.addAll(entry.legacyChartFiles.keySet());

                Path audioDir = songsDir.resolve(id);
                if (content.contains(ExternalContent.AUDIO) && Files.isDirectory(audioDir)) {
                    try (Stream<Path> audio = Files.list(audioDir)) {
                        audio.forEach(f -> {
                            String lower = f.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (lower.endsWith(".ogg")) classifyAudio(entry, f, lower);
                        });
                    } catch (IOException ignored) {}
                }
                resolveCharacterVocals(entry, audioDir, mod);
                if (entry.instFile == null) {
                    FnfMod.LOGGER.warn("Psych song {} in {} has no Inst.ogg — skipping", id, mod);
                    return;
                }
                found.put(id, entry);
            });
        } catch (IOException ignored) {}
    }

    /**
     * Scans a V-Slice (FNF 0.3+) mod: charts in data/songs/&lt;song&gt;/&lt;song&gt;-chart.json
     * + -metadata.json, audio in songs/&lt;song&gt;/ or manifest/../songs.
     */
    private static void scanVSliceMod(Path mod, Map<String, SongEntry> found,
                                      Set<ExternalContent> content) {
        Path chartsRoot = mod.resolve("data").resolve("songs");
        if (!Files.isDirectory(chartsRoot)) return;
        Path audioRoot = mod.resolve("songs");

        try (Stream<Path> dirs = Files.list(chartsRoot)) {
            dirs.filter(Files::isDirectory).sorted().forEach(songDir -> {
                String id = songDir.getFileName().toString();

                List<Path> jsons = new ArrayList<>();
                try (Stream<Path> files = Files.list(songDir)) {
                    files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                            .forEach(jsons::add);
                } catch (IOException ignored) {}
                boolean hasChart = jsons.stream().anyMatch(j -> {
                    String l = j.getFileName().toString().toLowerCase(Locale.ROOT);
                    return l.contains("-chart") || l.equals("chart.json");
                });
                if (!hasChart) return;

                // merge into an existing V-Slice song so mods can add variations to base songs
                SongEntry entry = found.get(id);
                boolean isNew = entry == null;
                if (isNew) {
                    entry = new SongEntry();
                    entry.id = id;
                    entry.displayName = id;
                    entry.folder = songDir;
                    entry.format = SongEntry.Format.VSLICE;
                    entry.modRoot = mod;
                    entry.fullModLayout = true;
                    entry.externalContent = EnumSet.copyOf(content);
                } else if (entry.format != SongEntry.Format.VSLICE) {
                    return; // don't mix with a legacy song of the same id
                } else if (!entry.fullModLayout) {
                    return; // lightweight songs/ entries keep priority over complete packs
                }
                if (content.contains(ExternalContent.EVENTS)) {
                    for (Path json : jsons) {
                        if (json.getFileName().toString().equalsIgnoreCase("events.json")) {
                            entry.eventsFile = json;
                            break;
                        }
                    }
                }
                Path audioDir = Files.isDirectory(audioRoot.resolve(id)) ? audioRoot.resolve(id) : songDir;
                buildVSliceVariations(entry, jsons, audioDir, mod, content);
                if (isNew && !entry.rawVars.isEmpty()) found.put(id, entry);
            });
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------ Codename Engine

    /** A Codename mod keeps each song in songs/&lt;song&gt;/ with a meta.json and/or a charts/ folder. */
    private static boolean isCodenameMod(Path mod) {
        Path songs = mod.resolve("songs");
        if (!Files.isDirectory(songs)) return false;
        try (Stream<Path> s = Files.list(songs)) {
            return s.filter(Files::isDirectory).anyMatch(d ->
                    Files.isRegularFile(d.resolve("meta.json")) || Files.isDirectory(d.resolve("charts")));
        } catch (IOException e) {
            return false;
        }
    }

    private static void scanCodenameMod(Path mod, Map<String, SongEntry> found, Map<String, Path> icons,
                                        Set<ExternalContent> content) {
        Path songsRoot = mod.resolve("songs");
        if (!Files.isDirectory(songsRoot)) return;
        try (Stream<Path> dirs = Files.list(songsRoot)) {
            dirs.filter(Files::isDirectory).sorted().forEach(songDir -> {
                String id = songDir.getFileName().toString();
                if (found.containsKey(id)) return; // earlier (local/config) songs win
                SongEntry entry = scanCodenameSong(songDir, mod, content);
                if (entry != null) found.put(id, entry);
            });
        } catch (IOException ignored) {}
    }

    /**
     * Scans one Codename song folder. Charts live in charts/&lt;diff&gt;.json (mod layout)
     * or directly in the folder (flattened download cache); audio in song/ or the folder
     * itself; bpm/needsVoices/icon come from meta.json.
     */
    private static SongEntry scanCodenameSong(Path dir, Path modRoot) {
        return scanCodenameSong(dir, modRoot, allExternalContent());
    }

    private static SongEntry scanCodenameSong(Path dir, Path modRoot, Set<ExternalContent> content) {
        Path chartsDir = dir.resolve("charts");
        Path chartSource = Files.isDirectory(chartsDir) ? chartsDir : dir;
        Path metaFile = dir.resolve("meta.json");

        SongEntry entry = new SongEntry();
        entry.id = dir.getFileName().toString();
        entry.displayName = entry.id;
        entry.folder = dir;
        entry.format = SongEntry.Format.CODENAME;
        entry.modRoot = modRoot;
        entry.fullModLayout = modRoot != null && !inside(modRoot, songsDir());
        entry.externalContent = EnumSet.copyOf(content);
        Path eventsFile = chartSource.resolve("events.json");
        if (content.contains(ExternalContent.EVENTS) && Files.isRegularFile(eventsFile)) {
            entry.eventsFile = eventsFile;
        }
        if (Files.isRegularFile(metaFile)) {
            entry.metaFile = metaFile;
            try {
                JsonObject m = JsonParser.parseString(Files.readString(metaFile)).getAsJsonObject();
                entry.displayName = LegacyChartParser.optString(m, "displayName",
                        LegacyChartParser.optString(m, "name", entry.id));
                entry.opponentIcon = LegacyChartParser.optString(m, "icon", "");
            } catch (Exception ignored) {}
        }

        try (Stream<Path> files = Files.list(chartSource)) {
            files.filter(f -> {
                String l = f.getFileName().toString().toLowerCase(Locale.ROOT);
                return l.endsWith(".json") && !l.equals("meta.json") && !l.equals("events.json")
                        && !l.endsWith("-metadata.json") && !l.equals("metadata.json");
            }).sorted().forEach(j -> {
                String base = j.getFileName().toString();
                base = base.substring(0, base.length() - 5).toLowerCase(Locale.ROOT);
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(j)).getAsJsonObject();
                    if (!CodenameChartParser.looksLikeCodename(root) && !LegacyChartParser.looksLikeLegacy(root)) {
                        return;
                    }
                    String diff = difficultyFromFilename(base, entry.id.toLowerCase(Locale.ROOT));
                    entry.legacyChartFiles.put(diff, j);
                    // legacy-wrapped Codename charts carry their own title/opponent
                    JsonObject songObj = root.has("song") && root.get("song").isJsonObject()
                            ? root.getAsJsonObject("song") : null;
                    if (songObj != null) {
                        if (entry.displayName.equals(entry.id)) {
                            entry.displayName = LegacyChartParser.optString(songObj, "song", entry.id);
                        }
                        if (entry.opponentIcon.isEmpty()) {
                            entry.opponentIcon = LegacyChartParser.optString(songObj, "player2", "");
                        }
                    }
                } catch (Exception ignored) {}
            });
        } catch (IOException ignored) {}
        if (entry.legacyChartFiles.isEmpty()) return null;
        orderDifficulties(entry);

        Path songSub = dir.resolve("song");
        Path audioDir = Files.isDirectory(songSub) ? songSub : dir;
        Map<String, Path> audio = new LinkedHashMap<>();
        if (content.contains(ExternalContent.AUDIO)) {
            try (Stream<Path> as = Files.list(audioDir)) {
                as.filter(Files::isRegularFile).forEach(f -> {
                    String lower = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".ogg")) audio.put(lower, f);
                });
            } catch (IOException ignored) {}
        }

        entry.instFile = firstAudio(audio, "inst.ogg");
        if (entry.instFile == null) {
            for (var en : audio.entrySet()) {
                if (en.getKey().startsWith("inst")) { entry.instFile = en.getValue(); break; }
            }
        }
        // Codename names each vocal file after its strumline's vocalsSuffix
        // (Voices<suffix>.ogg), which can be anything — "-Player"/"-Opponent" but also
        // " boyfriend"/" smiley" or "-Pico". Resolve from the chart, not the filename.
        resolveCodenameVocals(entry, audio);
        // legacy-wrapped charts (or unresolved): fall back to name-based classification
        if (entry.voicesFile == null && entry.voicesPlayerFile == null && entry.voicesOpponentFile == null) {
            for (var en : audio.entrySet()) {
                if (en.getKey().startsWith("voices") || en.getKey().startsWith("vocals")) {
                    classifyAudio(entry, en.getValue(), en.getKey());
                }
            }
        }
        resolveCharacterVocals(entry, audioDir, modRoot != null ? modRoot : dir);
        if (entry.instFile == null) {
            FnfMod.LOGGER.warn("Codename song {} has no Inst.ogg — skipping", entry.id);
            return null;
        }
        return entry;
    }

    /**
     * Resolves a Codename song's vocals from its chart's strumLines: each line's
     * {@code vocalsSuffix} names its file (Voices&lt;suffix&gt;.ogg) and its
     * {@code type} (0 = opponent, 1 = player) picks the side. An empty suffix is
     * a single combined Voices.ogg. Only reads a modern chart; legacy-wrapped
     * charts have no strumLines and fall back to name-based classification.
     */
    private static void resolveCodenameVocals(SongEntry entry, Map<String, Path> audio) {
        for (Path chartFile : entry.legacyChartFiles.values()) {
            JsonObject root;
            try {
                root = JsonParser.parseString(Files.readString(chartFile)).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }
            if (!root.has("strumLines") || !root.get("strumLines").isJsonArray()) continue;
            for (JsonElement slEl : root.getAsJsonArray("strumLines")) {
                if (!slEl.isJsonObject()) continue;
                JsonObject sl = slEl.getAsJsonObject();
                boolean player = (int) LegacyChartParser.optDouble(sl, "type", 0) == 1;
                String suffix = LegacyChartParser.optString(sl, "vocalsSuffix", "");
                Path f = audio.get(("voices" + suffix + ".ogg").toLowerCase(Locale.ROOT));
                if (f == null) continue;
                if (suffix.isEmpty()) {
                    entry.voicesFile = f;              // single combined track
                } else if (player) {
                    entry.voicesPlayerFile = f;
                } else {
                    entry.voicesOpponentFile = f;
                }
            }
            return; // one chart's strumlines define the vocals for the whole song
        }
    }

    /** Sorts a song's difficulties into a natural easy→normal→hard order. */
    private static void orderDifficulties(SongEntry entry) {
        List<String> order = List.of("easy", "normal", "hard", "erect", "nightmare");
        List<String> keys = new ArrayList<>(entry.legacyChartFiles.keySet());
        keys.sort((a, b) -> {
            int ia = order.indexOf(a), ib = order.indexOf(b);
            if (ia < 0) ia = order.size();
            if (ib < 0) ib = order.size();
            return ia != ib ? Integer.compare(ia, ib) : a.compareTo(b);
        });
        entry.difficulties.clear();
        entry.difficulties.addAll(keys);
    }

    public static synchronized Map<String, SongEntry> getSongs() {
        return songs;
    }

    public static synchronized SongEntry get(String id) {
        return songs.get(id);
    }

    /** Scans a single song folder (used by clients on downloaded/cached folders). */
    public static SongEntry scanSongDir(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        SongEntry e = scanSong(dir);
        if (e != null && e.format == SongEntry.Format.VSLICE) {
            finalizeVSlice(e); // standalone scan (e.g. download cache) finalizes immediately
            if (e.difficulties.isEmpty()) return null;
        }
        return e;
    }

    private static SongEntry scanSong(Path dir) {
        boolean basicLocal = inside(dir, songsDir());
        // Codename Engine song folder: meta.json (+ charts/ or a flattened download cache)
        if (Files.isRegularFile(dir.resolve("meta.json")) || Files.isDirectory(dir.resolve("charts"))) {
            SongEntry cn = scanCodenameSong(dir, basicLocal ? null : dir,
                    basicLocal ? basicSongContent() : allExternalContent());
            if (cn != null) return cn;
        }

        SongEntry entry = new SongEntry();
        entry.id = dir.getFileName().toString();
        entry.displayName = entry.id;
        entry.folder = dir;
        entry.fullModLayout = !basicLocal && looksLikeTransferredMod(dir);
        entry.modRoot = entry.fullModLayout ? dir : null;
        entry.externalContent = basicLocal ? basicSongContent() : allExternalContent();

        List<Path> jsons = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                String name = f.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".json")) {
                    jsons.add(f);
                } else if (lower.endsWith(".ogg")) {
                    classifyAudio(entry, f, lower);
                }
            });
        } catch (IOException e) {
            return null;
        }

        jsons.stream().filter(j -> j.getFileName().toString().equalsIgnoreCase("events.json"))
                .findFirst().ifPresent(j -> entry.eventsFile = j);

        // V-Slice? any *-chart[-variation].json present. Audio lives in the same folder.
        boolean hasVslice = jsons.stream().anyMatch(j -> {
            String l = j.getFileName().toString().toLowerCase(Locale.ROOT);
            return l.endsWith("-chart.json") || l.equals("chart.json") || l.contains("-chart-");
        });
        if (hasVslice) {
            entry.format = SongEntry.Format.VSLICE;
            buildVSliceVariations(entry, jsons, dir, entry.modRoot, entry.externalContent); // fills rawVars; finalized after all scanning
            if (entry.rawVars.isEmpty()) return null;
            if (entry.rawVars.stream().allMatch(r -> r.files.instFile == null)) {
                FnfMod.LOGGER.warn("V-Slice song {} has charts but no matching Inst.ogg in the same folder "
                        + "— the base game keeps audio in a separate songs/ tree; add the whole mod as a "
                        + "Directory instead, or copy the audio alongside the charts", entry.id);
                return null;
            }
            return entry;
        }

        // legacy / psych: every json with a "song" object is one difficulty
        for (Path j : jsons) {
            String base = j.getFileName().toString();
            base = base.substring(0, base.length() - 5); // strip .json
            String lower = base.toLowerCase(Locale.ROOT);
            if (lower.endsWith("-metadata") || lower.equals("metadata")) continue;
            if (lower.equals("events")) {
                entry.eventsFile = j;
                continue;
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(j)).getAsJsonObject();
                if (!LegacyChartParser.looksLikeLegacy(root)) continue;
                String diff = difficultyFromFilename(lower, entry.id.toLowerCase(Locale.ROOT));
                entry.format = SongEntry.Format.LEGACY;
                entry.legacyChartFiles.put(diff, j);
                JsonObject songObj = root.has("song") && root.get("song").isJsonObject()
                        ? root.getAsJsonObject("song") : root;
                if (entry.displayName.equals(entry.id)) {
                    entry.displayName = LegacyChartParser.optString(songObj, "song", entry.id);
                }
                if (entry.opponentIcon.isEmpty()) {
                    entry.opponentIcon = LegacyChartParser.optString(songObj, "player2", "dad");
                }
            } catch (Exception ignored) {}
        }
        entry.difficulties.addAll(entry.legacyChartFiles.keySet());
        resolveCharacterVocals(entry, dir, entry.modRoot != null ? entry.modRoot : dir);
        SongEntry linked = linkChartOverrideToOriginal(dir, entry);
        if (linked != null) return linked;
        if (entry.difficulties.isEmpty()) return null;

        if (entry.instFile == null) {
            if (entry.instFile == null && !entry.difficulties.isEmpty()) {
                FnfMod.LOGGER.warn("Song {} has charts but no Inst.ogg — skipping", entry.id);
            }
            return entry;
        }
        return entry;
    }

    /** Combines a local edited chart with audio and assets from its referenced source mod. */
    private static SongEntry linkChartOverrideToOriginal(Path localDir, SongEntry override) {
        Path referenceFile = localDir.resolve(ORIGINAL_DIRECTORY_FILE);
        if (!Files.isRegularFile(referenceFile)) return null;
        try {
            List<String> reference = Files.readAllLines(referenceFile);
            if (reference.isEmpty() || reference.get(0).isBlank()) return null;
            Path source = Path.of(reference.get(0).trim());
            String originalChartName = override.id;
            for (int i = 1; i < reference.size(); i++) {
                String line = reference.get(i).trim();
                if (line.regionMatches(true, 0, "chart=", 0, 6) && line.length() > 6) {
                    originalChartName = line.substring(6).trim();
                }
            }
            if (!source.isAbsolute()) source = localDir.resolve(source);
            source = source.toAbsolutePath().normalize();
            if (source.equals(localDir.toAbsolutePath().normalize()) || !Files.isDirectory(source)) return null;
            if (!ModContentScope.allowsContentPath(source)) {
                FnfMod.LOGGER.warn("Chart override {} cannot access out-of-scope source {}",
                        override.id, source);
                return null;
            }

            Map<String, SongEntry> sourceSongs = new LinkedHashMap<>();
            scanPsychRoot(source, sourceSongs, new LinkedHashMap<>());
            SongEntry original = sourceSongs.get(originalChartName);
            if (original == null) {
                String targetChartName = originalChartName;
                original = sourceSongs.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(targetChartName)
                                || normalizedDifficultyKey(e.getKey()).equals(normalizedDifficultyKey(targetChartName)))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
            if (original == null) original = scanSongDir(source);
            if (original == null) {
                FnfMod.LOGGER.warn("Chart override {} references {}, but no matching song was found",
                        override.id, source);
                return null;
            }
            if (original.isVslice()) finalizeVSlice(original);

            // The locally saved chart wins, while the exact source pack remains
            // its complete runtime resource root. This keeps edited charts fully
            // playable without copying a potentially large mod into songs/.
            original.chartOriginRoot = source;
            original.folder = localDir;
            original.fullModLayout = original.modRoot != null;
            original.externalContent = allExternalContent();
            // A separately saved events.json replaces the source events. Without
            // one, keep the source event timeline just like every other asset.
            if (override.eventsFile != null) {
                original.eventsFile = override.eventsFile;
                original.eventsOverride = true;
            }
            // A complete local import must take priority over the referenced
            // source while keeping the reference as a fallback for missing data.
            if (override.instFile != null) original.instFile = override.instFile;
            if (override.voicesFile != null) original.voicesFile = override.voicesFile;
            if (override.voicesPlayerFile != null) original.voicesPlayerFile = override.voicesPlayerFile;
            if (override.voicesOpponentFile != null) original.voicesOpponentFile = override.voicesOpponentFile;
            for (var local : override.legacyChartFiles.entrySet()) {
                String difficulty = resolveOverrideDifficulty(original, local.getKey());
                Path previous = original.chartOverrides.get(difficulty);
                // Old editor builds could create hard-2/easy-2/etc. Treat those
                // as revisions of the matching V-Slice difficulty and keep the
                // newest file instead of exposing each revision as a new difficulty.
                if (previous == null || modifiedAt(local.getValue()) >= modifiedAt(previous)) {
                    original.chartOverrides.put(difficulty, local.getValue());
                }
                if (original.difficulties.stream().noneMatch(value -> value.equalsIgnoreCase(difficulty))) {
                    original.difficulties.add(difficulty);
                }
            }
            FnfMod.LOGGER.info("Edited chart {} inherits complete runtime assets from {}",
                    override.id, source);
            return original;
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not resolve chart override source for {}: {}", override.id, e.toString());
            return null;
        }
    }

    private static String normalizedDifficultyKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private static String resolveOverrideDifficulty(SongEntry original, String localDifficulty) {
        String candidate = localDifficulty == null || localDifficulty.isBlank() ? "normal" : localDifficulty;
        String match = findDifficulty(original.difficulties, candidate);
        if (match != null) return match;

        // Recover files produced after a duplicate had already entered the list
        // (hard-2, hard-3, normal-2...). Exact real difficulty names still win.
        String withoutRevision = candidate;
        while (withoutRevision.matches("(?i).+-[0-9]+")) {
            withoutRevision = withoutRevision.replaceFirst("-[0-9]+$", "");
            match = findDifficulty(original.difficulties, withoutRevision);
            if (match != null) return match;
        }
        return candidate;
    }

    private static String findDifficulty(List<String> values, String wanted) {
        String normalized = normalizedDifficultyKey(wanted);
        return values.stream().filter(value -> normalizedDifficultyKey(value).equals(normalized))
                .findFirst().orElse(null);
    }

    private static long modifiedAt(Path file) {
        try { return file == null ? Long.MIN_VALUE : Files.getLastModifiedTime(file).toMillis(); }
        catch (Exception ignored) { return Long.MIN_VALUE; }
    }

    private static boolean inside(Path path, Path root) {
        if (path == null || root == null) return false;
        return path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    /** Download caches preserve these directories when a complete mod is transferred. */
    private static boolean looksLikeTransferredMod(Path root) {
        for (String folder : new String[]{"scripts", "custom_events", "custom_notetypes", "images",
                "characters", "stages", "sounds", "fonts", "animations", "data", "songs"}) {
            if (Files.isDirectory(root.resolve(folder))) return true;
        }
        return false;
    }

    /**
     * Builds V-Slice variations from a set of chart/metadata jsons. Each
     * "&lt;song&gt;-chart[-variation].json" pairs with its metadata and its own
     * audio (Inst[-variation].ogg, Voices-bf[-variation].ogg, ...) so the base
     * game's erect/nightmare/pico difficulties each get the right song.
     */
    private static void buildVSliceVariations(SongEntry entry, List<Path> jsons, Path audioDir, Path modRoot,
                                              Set<ExternalContent> content) {
        // index audio by lowercase name
        Map<String, Path> audio = new LinkedHashMap<>();
        if (content.contains(ExternalContent.AUDIO)) {
            try (Stream<Path> files = Files.list(audioDir)) {
                files.filter(Files::isRegularFile).forEach(f -> {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".ogg")) audio.put(n, f);
                });
            } catch (IOException ignored) {}
        }

        // collect (chart, variation), default variation first so it owns base difficulties
        record ChartVar(Path chart, String variation) {}
        List<ChartVar> charts = new ArrayList<>();
        for (Path chart : jsons) {
            String lower = chart.getFileName().toString().toLowerCase(Locale.ROOT);
            int ci = lower.indexOf("-chart");
            boolean isChart = lower.endsWith("-chart.json") || lower.equals("chart.json") || lower.contains("-chart-");
            if (!isChart) continue;
            String variation = "";
            if (ci >= 0) {
                String after = lower.substring(ci + "-chart".length(), lower.length() - 5);
                if (after.startsWith("-")) variation = after.substring(1);
            } else if (lower.startsWith("chart-")) {
                variation = lower.substring("chart-".length(), lower.length() - 5);
            }
            charts.add(new ChartVar(chart, variation));
        }
        charts.sort((a, b) -> Boolean.compare(!a.variation.isEmpty(), !b.variation.isEmpty()));

        for (ChartVar cv : charts) {
            Path chart = cv.chart;
            String name = chart.getFileName().toString();
            String variation = cv.variation;

            // matching metadata file
            Path meta = chart.resolveSibling(name.replace("-chart", "-metadata").replace("chart", "metadata"));
            if (!Files.isRegularFile(meta)) {
                // fallback: any *-metadata*.json with the same variation
                final String var = variation;
                meta = jsons.stream().filter(j -> {
                    String l = j.getFileName().toString().toLowerCase(Locale.ROOT);
                    return l.contains("metadata") && matchesVariation(l, var);
                }).findFirst().orElse(null);
            }
            if (meta == null) continue;

            SongEntry.VSliceVariation v = new SongEntry.VSliceVariation();
            v.chartFile = chart;
            v.metadataFile = meta;
            String suffix = variation.isEmpty() ? "" : "-" + variation;

            // read difficulties + the variation's character ids (vocals are named after them)
            List<String> diffs;
            String songName = entry.id;
            String player = "bf", opponent = "dad";
            try {
                JsonObject m = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
                songName = LegacyChartParser.optString(m, "songName", entry.id);
                diffs = new ArrayList<>();
                if (m.has("playData") && m.get("playData").isJsonObject()) {
                    JsonObject pd = m.getAsJsonObject("playData");
                    if (pd.has("difficulties") && pd.get("difficulties").isJsonArray()) {
                        for (var d : pd.getAsJsonArray("difficulties")) diffs.add(d.getAsString());
                    }
                    if (pd.has("characters") && pd.get("characters").isJsonObject()) {
                        JsonObject ch = pd.getAsJsonObject("characters");
                        player = LegacyChartParser.optString(ch, "player", "bf");
                        opponent = LegacyChartParser.optString(ch, "opponent", "dad");
                    }
                }
                // default variation's opponent (and its mod) drive the song-list icon
                if (variation.isEmpty() || entry.opponentIcon.isEmpty()) {
                    entry.opponentIcon = opponent;
                    entry.modRoot = modRoot;
                }
                if (diffs.isEmpty()) diffs = VSliceChartParser.listDifficulties(Files.readString(chart));
            } catch (Exception e) {
                continue;
            }

            // V-Slice vocals. The vocal file is named after the character id, but the
            // "playable" variant of a character often drops "-playable" (spooky-playable
            // -> Voices-spooky). Never fall back to bf/dad for a variation.
            v.instFile = firstAudio(audio, "inst" + suffix + ".ogg", "inst.ogg");
            v.voicesFile = firstAudio(audio, "voices" + suffix + ".ogg");
            String playerBase = player.replace("-playable", "");
            v.voicesOpponentFile = CharacterVocalResolver.resolve(
                    audio, modRoot, opponent, true, suffix);
            if (v.voicesOpponentFile == null) v.voicesOpponentFile = firstAudio(audio,
                    "voices-" + opponent + suffix + ".ogg",
                    "voices-" + opponent.replace("-playable", "") + suffix + ".ogg",
                    "voices-" + opponent + ".ogg",
                    "voices-dad" + suffix + ".ogg",
                    "voices-opponent" + suffix + ".ogg");
            v.voicesPlayerFile = CharacterVocalResolver.resolve(
                    audio, modRoot, player, false, suffix);
            if (v.voicesPlayerFile == null) v.voicesPlayerFile = firstAudio(audio,
                    "voices-" + player + suffix + ".ogg",
                    "voices-" + playerBase + suffix + ".ogg",
                    "voices-" + player + ".ogg",
                    "voices-bf" + suffix + ".ogg",
                    "voices-player" + suffix + ".ogg");
            // last resort: any suffixed vocal that isn't the opponent's (covers odd id/file mismatches)
            if (v.voicesPlayerFile == null) {
                v.voicesPlayerFile = anyVoiceWithSuffix(audio, suffix, v.voicesOpponentFile);
            }
            if (v.voicesOpponentFile == null && !variation.isEmpty()) {
                v.voicesOpponentFile = anyVoiceWithSuffix(audio, suffix, v.voicesPlayerFile);
            }
            // default variation may only have a single combined Voices.ogg
            if (v.voicesPlayerFile == null && v.voicesOpponentFile == null && v.voicesFile == null && suffix.isEmpty()) {
                v.voicesPlayerFile = firstAudio(audio, "voices-bf.ogg", "voices.ogg");
                v.voicesOpponentFile = firstAudio(audio, "voices-dad.ogg");
            }

            SongEntry.RawVar raw = new SongEntry.RawVar();
            raw.variation = variation;
            raw.files = v;
            raw.diffs = diffs;
            entry.rawVars.add(raw);

            if (entry.displayName == null || entry.displayName.equals(entry.id)) {
                if (variation.isEmpty()) entry.displayName = songName;
                else if (entry.displayName == null) entry.displayName = songName;
            }
        }
        if (entry.displayName == null) entry.displayName = entry.id;
    }

    /** Any "voices-*[suffix].ogg" that isn't the excluded (opponent) file. */
    private static Path anyVoiceWithSuffix(Map<String, Path> audio, String suffix, Path exclude) {
        String suf = suffix + ".ogg";
        for (var e : audio.entrySet()) {
            String n = e.getKey();
            if (!n.startsWith("voices-") || !n.endsWith(suf)) continue;
            if (exclude != null && e.getValue().equals(exclude)) continue;
            return e.getValue();
        }
        return null;
    }

    /**
     * Assigns difficulty keys from the collected raw variations. The default
     * (empty) variation and any uniquely-named difficulty get the plain name;
     * a difficulty name provided by several variations gets " (variation)"
     * suffixes so re-used names (pico/spookymod easy/normal/hard) stay distinct.
     */
    private static void finalizeVSlice(SongEntry entry) {
        entry.vsliceVariations.clear();
        entry.vsliceRealDiff.clear();
        entry.difficulties.clear();

        Map<String, List<SongEntry.RawVar>> providers = new LinkedHashMap<>();
        for (SongEntry.RawVar r : entry.rawVars) {
            for (String d : r.diffs) providers.computeIfAbsent(d, k -> new ArrayList<>()).add(r);
        }
        for (SongEntry.RawVar r : entry.rawVars) {
            for (String d : r.diffs) {
                List<SongEntry.RawVar> provs = providers.get(d);
                boolean plain;
                if (r.variation.isEmpty()) {
                    plain = true;
                } else {
                    boolean defaultProvides = provs.stream().anyMatch(p -> p.variation.isEmpty());
                    plain = !defaultProvides && provs.size() == 1;
                }
                String key = plain ? d : d + " (" + r.variation + ")";
                String finalKey = key;
                int n = 2;
                while (entry.vsliceVariations.containsKey(finalKey)) finalKey = key + " " + n++;
                if (r.files.instFile == null) continue; // unplayable without audio
                entry.vsliceVariations.put(finalKey, r.files);
                entry.vsliceRealDiff.put(finalKey, d);
                entry.difficulties.add(finalKey);
            }
        }
    }

    private static boolean matchesVariation(String lowerName, String variation) {
        String noExt = lowerName.endsWith(".json") ? lowerName.substring(0, lowerName.length() - 5) : lowerName;
        if (variation.isEmpty()) {
            return noExt.endsWith("-metadata") || noExt.equals("metadata");
        }
        return noExt.endsWith("-metadata-" + variation) || noExt.endsWith("metadata-" + variation);
    }

    private static Path firstAudio(Map<String, Path> audio, String... names) {
        for (String n : names) {
            Path p = audio.get(n.toLowerCase(Locale.ROOT));
            if (p != null) return p;
        }
        return null;
    }

    private static String difficultyFromFilename(String lowerBase, String songId) {
        if (lowerBase.equals(songId) || lowerBase.equals("chart")) return "normal";
        // "songname-hard" -> "hard"
        if (lowerBase.startsWith(songId + "-")) {
            String d = lowerBase.substring(songId.length() + 1);
            return d.isEmpty() ? "normal" : d;
        }
        int dash = lowerBase.lastIndexOf('-');
        if (dash > 0 && dash < lowerBase.length() - 1) {
            String d = lowerBase.substring(dash + 1);
            if (d.equals("easy") || d.equals("hard") || d.equals("normal") || d.equals("erect") || d.equals("nightmare")) {
                return d;
            }
        }
        return lowerBase;
    }

    private static void classifyAudio(SongEntry entry, Path f, String lower) {
        if (lower.startsWith("inst")) {
            entry.instFile = f;
        } else if (lower.startsWith("voices") || lower.startsWith("vocals")) {
            if (lower.contains("player") || lower.contains("-bf") || lower.contains("_bf")) {
                entry.voicesPlayerFile = f;
            } else if (lower.contains("opponent") || lower.contains("-dad") || lower.contains("_dad") || lower.contains("opp")) {
                entry.voicesOpponentFile = f;
            } else {
                entry.voicesFile = f;
            }
        }
    }

    /** Character JSON vocals_file/vocal-prefix fields override generic filename guessing. */
    private static void resolveCharacterVocals(SongEntry entry, Path audioDirectory, Path definitionRoot) {
        if (entry == null || audioDirectory == null || !Files.isDirectory(audioDirectory)) return;
        String player = "bf", opponent = "dad";
        boolean foundCharacters = false;
        for (Path chartFile : entry.legacyChartFiles.values()) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(chartFile)).getAsJsonObject();
                JsonObject song = root.has("song") && root.get("song").isJsonObject()
                        ? root.getAsJsonObject("song") : root;
                if (!LegacyChartParser.looksLikeLegacy(root)) continue;
                player = LegacyChartParser.optString(song, "player1", player);
                opponent = LegacyChartParser.optString(song, "player2", opponent);
                foundCharacters = true;
                break;
            } catch (Exception ignored) {}
        }
        if (!foundCharacters) return;
        Map<String, Path> audio = CharacterVocalResolver.indexAudio(audioDirectory);
        Path playerVoice = CharacterVocalResolver.resolve(audio, definitionRoot, player, false, "");
        Path opponentVoice = CharacterVocalResolver.resolve(audio, definitionRoot, opponent, true, "");
        if (playerVoice != null) entry.voicesPlayerFile = playerVoice;
        if (opponentVoice != null) entry.voicesOpponentFile = opponentVoice;
        if (entry.voicesFile != null && (entry.voicesFile.equals(playerVoice)
                || entry.voicesFile.equals(opponentVoice))) entry.voicesFile = null;
    }

    /** Loads and parses a chart for the given difficulty. */
    public static SongChart loadChart(SongEntry entry, String difficulty) throws IOException {
        SongChart chart;
        Path chartOverride = entry.chartOverrideFor(difficulty);
        if (chartOverride != null && Files.isRegularFile(chartOverride)) {
            chart = LegacyChartParser.parse(Files.readString(chartOverride));
        } else if (entry.format == SongEntry.Format.VSLICE) {
            SongEntry.VSliceVariation v = entry.variationFor(difficulty);
            if (v == null) throw new IOException("No variation for difficulty " + difficulty);
            String chartJson = Files.readString(v.chartFile);
            String metaJson = Files.readString(v.metadataFile);
            chart = VSliceChartParser.parse(chartJson, metaJson, entry.realDifficulty(difficulty));
        } else if (entry.format == SongEntry.Format.CODENAME) {
            Path f = entry.legacyChartFor(difficulty);
            if (f == null) throw new IOException("No chart for difficulty " + difficulty);
            String metaJson = entry.metaFile != null && Files.isRegularFile(entry.metaFile)
                    ? Files.readString(entry.metaFile) : null;
            chart = CodenameChartParser.parse(Files.readString(f), metaJson, difficulty);
        } else {
            Path f = entry.legacyChartFor(difficulty);
            if (f == null) throw new IOException("No chart for difficulty " + difficulty);
            chart = LegacyChartParser.parse(Files.readString(f));
        }
        if (entry.eventsFile != null && Files.isRegularFile(entry.eventsFile)
                && (entry.eventsOverride || chartOverride == null)) {
            chart.events.clear();
            chart.events.addAll(LegacyChartParser.parseEvents(Files.readString(entry.eventsFile)));
        }
        chart.sortEvents();
        return chart;
    }

}
