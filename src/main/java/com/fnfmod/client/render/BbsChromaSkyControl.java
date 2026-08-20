package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Temporarily forces BBS's "Chroma sky" to an opaque black sky (terrain kept visible) and
 * restores it, using reflection so Blockified never hard-depends on BBS.
 *
 * <p>BBS persists its settings on change ({@code Settings.saveLater}), so the forced value can
 * reach disk. To guarantee the player's own configuration is never left changed after a crash
 * or force-close, the original values are written to a small backup file before the change and
 * removed on restore; {@link #recoverIfNeeded()} restores from that file on the next launch if
 * the transition never finished.</p>
 */
public final class BbsChromaSkyControl {

    private static final int BLACK = 0xFF000000;

    private static boolean initialized;
    private static Object enabledValue;
    private static Object colorValue;
    private static Object terrainValue;
    private static Method boolSet;
    private static Method intSet;

    private static boolean applied;

    private BbsChromaSkyControl() {}

    private static Path backupFile() {
        return SongLibrary.root().resolve("chroma-sky-backup.json");
    }

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> settings = Class.forName("mchorse.bbs_mod.BBSSettings");
            enabledValue = fieldValue(settings, "chromaSkyEnabled");
            colorValue = fieldValue(settings, "chromaSkyColor");
            terrainValue = fieldValue(settings, "chromaSkyTerrain");
            boolSet = setter(enabledValue);
            intSet = setter(colorValue);
        } catch (Throwable ignored) {
            enabledValue = null;
        }
    }

    public static boolean available() {
        init();
        return enabledValue != null && colorValue != null && terrainValue != null
                && boolSet != null && intSet != null;
    }

    /** Forces an opaque black chroma sky, backing up the current values to disk first. */
    public static synchronized void applyBlack() {
        if (applied || !available()) return;
        try {
            boolean enabled = (Boolean) get(enabledValue);
            int color = (Integer) get(colorValue);
            boolean terrain = (Boolean) get(terrainValue);
            writeBackup(enabled, color, terrain);

            boolSet.invoke(enabledValue, Boolean.TRUE);
            intSet.invoke(colorValue, Integer.valueOf(BLACK));
            boolSet.invoke(terrainValue, Boolean.TRUE); // keep terrain visible under the black sky
            applied = true;
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not apply BBS chroma sky: {}", error.toString());
        }
    }

    /** Restores the player's own chroma sky values and removes the crash-recovery backup. */
    public static synchronized void restore() {
        applyFromBackup();
        applied = false;
        try {
            Files.deleteIfExists(backupFile());
        } catch (Exception ignored) {
        }
    }

    /**
     * On launch: if a backup file survived a crash/force-close during a transition, restore the
     * player's chroma sky from it and delete it. Safe to call whenever BBS is loaded.
     */
    public static synchronized void recoverIfNeeded() {
        Path file = backupFile();
        if (!Files.isRegularFile(file)) return;
        FnfMod.LOGGER.info("Recovering BBS chroma sky from an unfinished transition");
        applyFromBackup();
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }

    private static void applyFromBackup() {
        Path file = backupFile();
        if (!available() || !Files.isRegularFile(file)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            boolSet.invoke(enabledValue, json.get("enabled").getAsBoolean());
            intSet.invoke(colorValue, json.get("color").getAsInt());
            boolSet.invoke(terrainValue, json.get("terrain").getAsBoolean());
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not restore BBS chroma sky from backup: {}", error.toString());
        }
    }

    private static void writeBackup(boolean enabled, int color, boolean terrain) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("enabled", enabled);
            json.addProperty("color", color);
            json.addProperty("terrain", terrain);
            Path file = backupFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString());
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not write chroma sky backup: {}", error.toString());
        }
    }

    private static Object fieldValue(Class<?> owner, String name) throws Exception {
        Field field = owner.getField(name);
        return field.get(null);
    }

    private static Object get(Object value) throws Exception {
        return value.getClass().getMethod("get").invoke(value);
    }

    private static Method setter(Object value) {
        for (Method method : value.getClass().getMethods()) {
            if (method.getName().equals("set") && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }
}
