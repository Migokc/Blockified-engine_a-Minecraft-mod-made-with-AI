package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/** Client-side gameplay options, stored in config/fnfmod/options.json. */
public class ClientOptions {
    public boolean downscroll = false;
    /** Your strumline centered, opponent notes split to the screen edges. */
    public boolean middlescroll = false;
    public boolean ghostTapping = true;
    public double offsetMs = 0;
    public double scrollSpeedMult = 1.0;
    /** true = constant scroll speed (overrides chart), false = multiplier of chart speed. */
    public boolean constantScrollSpeed = false;
    /** Rating popup position, as a fraction of the screen (-1 = use default). */
    public double ratingX = -1;
    public double ratingY = -1;
    /** Which animation set (folder in config/fnfmod/animations/) plays on your character. */
    public String animationSet = "default";
    /** Solo mode side: 0 = player, 1 = opponent, 2 = both. */
    public int playAs = 0;
    /** Note skin folder under config/fnfmod/skins/. */
    public String noteSkin = "default";
    /** Health icons ("" = none). */
    public String playerIcon = "";
    public String botIcon = "";
    /** HUD style: default, abbreviated, numbers, vanilla, fnf. */
    public String hudStyle = "default";
    /** Splash pair name in config/fnfmod/splashes/ ("" = off). Ignored when the note skin ships its own. */
    public String splashSkin = "";
    /** Hitsound file name in config/fnfmod/hitsounds/ ("" = off). */
    public String hitsound = "";
    public double hitsoundVolume = 1.0;

    /** Chart editor playback hitsounds, per chart side. */
    public boolean editorHitsoundPlayer = false;
    public boolean editorHitsoundOpponent = false;

    /** Psych-style RGB note colors (applies to skins authored with the red/green/blue template). */
    public boolean noteColorsEnabled = true;
    public int[] noteColorBase = defaultBase();
    public int[] noteColorOutline = defaultOutline();

    public static int[] defaultBase() {
        return new int[]{0xC24B99, 0x00FFFF, 0x12FA05, 0xF9393F};
    }

    public static int[] defaultOutline() {
        return new int[]{0x3C1F56, 0x1542B7, 0x0A4447, 0x651038};
    }

    private static ClientOptions instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ClientOptions get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        Path file = SongLibrary.root().resolve("options.json");
        try {
            if (Files.isRegularFile(file)) {
                instance = GSON.fromJson(Files.readString(file), ClientOptions.class);
            }
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not read options.json: {}", e.toString());
        }
        if (instance == null) instance = new ClientOptions();
        if (instance.noteColorBase == null || instance.noteColorBase.length != 4) {
            instance.noteColorBase = defaultBase();
        }
        if (instance.noteColorOutline == null || instance.noteColorOutline.length != 4) {
            instance.noteColorOutline = defaultOutline();
        }
    }

    /** HUD style with "vanilla" downgraded to "default" in creative (can't take real damage). */
    public static String effectiveHudStyle() {
        String s = get().hudStyle;
        if ("vanilla".equals(s)) {
            var p = net.minecraft.client.Minecraft.getInstance().player;
            if (p != null && p.getAbilities().instabuild) return "default";
        }
        return s;
    }

    public static void save() {
        Path file = SongLibrary.root().resolve("options.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(get()));
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not save options.json: {}", e.toString());
        }
    }
}
