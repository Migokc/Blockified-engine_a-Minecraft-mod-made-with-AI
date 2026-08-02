package com.fnfmod.machine;

import com.fnfmod.FnfMod;
import com.fnfmod.world.ModContentScope;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

/** Loads machine profiles from the active pack's machines directory only. */
public final class MachineLibrary {

    private static volatile Map<String, MachineDefinition> definitions = builtIns();
    private static volatile int generation;
    private static volatile String packVersion = "built-in";

    private MachineLibrary() {}

    public static synchronized void rescan() {
        LinkedHashMap<String, MachineDefinition> found = new LinkedHashMap<>(builtIns());
        ModContentScope.activeMod().ifPresent(active -> scan(active.id(), active.root(), found));
        packVersion = ModContentScope.activeMod().map(active -> fingerprint(active.root())).orElse("built-in");
        definitions = Collections.unmodifiableMap(found);
        generation++;
        FnfMod.LOGGER.info("Machine library: {} profile(s), scope {}",
                definitions.size(), ModContentScope.mode());
    }

    public static int generation() {
        return generation;
    }

    public static String packVersion() {
        return packVersion;
    }

    public static Map<String, MachineDefinition> all() {
        return definitions;
    }

    public static MachineDefinition get(String id) {
        if (id == null) return definitions.get(MachineDefinition.DEFAULT_ID);
        return definitions.getOrDefault(canonical(id), definitions.get(MachineDefinition.DEFAULT_ID));
    }

    public static Optional<MachineDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(canonical(id)));
    }

    public static String canonical(String id) {
        return id == null ? MachineDefinition.DEFAULT_ID : id.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, MachineDefinition> builtIns() {
        MachineDefinition defaultMachine = new MachineDefinition(
                MachineDefinition.DEFAULT_ID, "Funkin' Machine", null, null,
                Map.of(), new JsonObject(), true);
        return Map.of(defaultMachine.id(), defaultMachine);
    }

    private static void scan(String modId, Path modRoot, Map<String, MachineDefinition> found) {
        Path machines = modRoot.resolve("machines").normalize();
        if (!ModContentScope.allowsContentPath(machines) || !Files.isDirectory(machines)) return;
        try (Stream<Path> directories = Files.list(machines)) {
            directories.filter(Files::isDirectory).sorted().forEach(directory -> {
                MachineDefinition definition = read(modId, directory);
                if (definition != null) found.put(definition.id(), definition);
            });
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not scan machine profiles in {}: {}", machines, e.toString());
        }
    }

    private static MachineDefinition read(String modId, Path directory) {
        Path jsonFile = directory.resolve("machine.json");
        if (!Files.isRegularFile(jsonFile) || !ModContentScope.allowsContentPath(jsonFile)) return null;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
            String localId = string(json, "id", directory.getFileName().toString());
            localId = safeSegment(localId);
            if (localId.isEmpty()) throw new IllegalArgumentException("invalid machine id");
            String id = safeSegment(modId) + ":" + localId;
            String displayName = string(json, "displayName", directory.getFileName().toString());

            Path menu = resolveFile(directory, string(json, "menu", "menu.lua"), false);
            LinkedHashMap<String, Path> textures = new LinkedHashMap<>();
            putTexture(textures, directory, "all", string(json, "texture", null));
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) continue;
                    String face = entry.getKey().toLowerCase(Locale.ROOT);
                    if (!isTextureFace(face)) continue;
                    putTexture(textures, directory, face, entry.getValue().getAsString());
                }
            }
            JsonObject behavior = json.has("behavior") && json.get("behavior").isJsonObject()
                    ? json.getAsJsonObject("behavior").deepCopy() : new JsonObject();
            return new MachineDefinition(id, displayName, directory, menu,
                    Collections.unmodifiableMap(textures), behavior, false);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Invalid machine profile {}: {}", jsonFile, e.toString());
            return null;
        }
    }

    private static void putTexture(Map<String, Path> textures, Path root, String face, String value) {
        Path file = resolveFile(root, value, true);
        if (file != null) textures.put(face, file);
    }

    private static Path resolveFile(Path machineRoot, String value, boolean requireFile) {
        if (value == null || value.isBlank()) return null;
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute()) return null;
            Path file = machineRoot.resolve(relative).normalize();
            if (!file.startsWith(machineRoot.toAbsolutePath().normalize())
                    || !ModContentScope.allowsContentPath(file)) return null;
            if (requireFile && !Files.isRegularFile(file)) return null;
            return Files.isRegularFile(file) ? file : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTextureFace(String face) {
        return switch (face) {
            case "all", "side", "front", "back", "left", "right", "top", "bottom" -> true;
            default -> false;
        };
    }

    private static String safeSegment(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-")
                .replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** Version label plus machine-file content fingerprint for LAN compatibility checks. */
    private static String fingerprint(Path modRoot) {
        String version = "unversioned";
        try {
            Path pack = modRoot.resolve("pack.json");
            if (Files.isRegularFile(pack)) {
                JsonElement parsed = JsonParser.parseString(Files.readString(pack));
                if (parsed.isJsonObject()) {
                    JsonObject object = parsed.getAsJsonObject();
                    version = string(object, "version", string(object, "modVersion", version));
                }
            }
        } catch (Exception ignored) {}
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path machines = modRoot.resolve("machines");
            if (Files.isDirectory(machines)) {
                try (Stream<Path> files = Files.walk(machines)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        String name = machines.relativize(file).toString().replace('\\', '/');
                        digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        try (var input = Files.newInputStream(file)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
                        }
                    }
                }
            }
            return version + "#" + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (Exception ignored) {
            return version;
        }
    }
}
