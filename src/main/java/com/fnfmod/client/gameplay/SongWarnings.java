package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Advisories shown before a song plays, for anything a player might want to know
 * about up front — a heavy render distance on a low-end machine, and whatever
 * else gets added later.
 *
 * <h2>Adding a warning</h2>
 * Write a {@link Check} and add it to {@link #CHECKS}. A check looks at the song
 * about to play and returns a message when it wants to warn, or empty otherwise.
 * Everything else — collecting, the screen, the disable setting — already works
 * for it. Keep a check read-only and cheap; it runs on the client thread right
 * before the song starts.
 */
public final class SongWarnings {

    /** Render distances above this are flagged, since they hit low-end machines hardest. */
    public static final int RENDER_DISTANCE_WARN_ABOVE = 10;

    /** Flag colour used when a warning does not choose its own. */
    public static final int DEFAULT_ACCENT = 0xFFFFCC33;

    /** One advisory: the text to show and the colour of its flag stripe. */
    public record Warning(String text, int accentColor) {
        public Warning(String text) {
            this(text, DEFAULT_ACCENT);
        }
    }

    /** What a check gets to look at: the song's id, its folder, and its entry. */
    public record Context(String songId, Path songFolder, SongEntry entry) {}

    /** One source of warnings. Returns every warning it wants to raise, or none. */
    @FunctionalInterface
    public interface Check {
        List<Warning> apply(Context context);
    }

    /**
     * Engine-detected warnings. Append here to add a hardcoded check; these show
     * when the setting is "on" or "blockified".
     */
    private static final List<Check> BLOCKIFIED_CHECKS = List.of(
            SongWarnings::checkRenderDistance
    );

    /**
     * Warnings authored by the song or pack. These show when the setting is "on"
     * or "song". Right now that is the warnings.txt loader; another author-driven
     * source would be added here.
     */
    private static final List<Check> SONG_CHECKS = List.of(
            SongWarnings::customWarnings
    );

    private SongWarnings() {}

    /**
     * Every warning that applies to this song. {@code includeBlockified} and
     * {@code includeSong} follow the player's Song Warnings setting, so the two
     * sources can be shown together, alone, or not at all.
     */
    public static List<Warning> collect(Context context, boolean includeBlockified, boolean includeSong) {
        List<Warning> warnings = new ArrayList<>();
        if (includeBlockified) run(BLOCKIFIED_CHECKS, context, warnings);
        if (includeSong) run(SONG_CHECKS, context, warnings);
        return warnings;
    }

    private static void run(List<Check> checks, Context context, List<Warning> out) {
        for (Check check : checks) {
            try {
                out.addAll(check.apply(context));
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Song warning check failed: {}", error.toString());
            }
        }
    }

    // ---------------------------------------------------------------- checks

    private static final Pattern SET_RENDER_DISTANCE =
            Pattern.compile("setRenderDistance\\s*\\(");

    /**
     * Warns when a song's Lua raises the render distance past the comfort limit.
     * The scripts are read as text rather than run, so the warning can be shown
     * before gameplay — and before the render distance is actually changed.
     */
    private static List<Warning> checkRenderDistance(Context context) {
        int highest = 0;
        for (Path script : luaScripts(context)) {
            try {
                highest = Math.max(highest, highestRenderDistance(Files.readString(script)));
            } catch (Exception ignored) {
                // Unreadable script: nothing to warn about from it.
            }
        }
        int clamped = Math.min(RenderDistanceControl.MAX, highest);
        if (clamped <= RENDER_DISTANCE_WARN_ABOVE) return List.of();
        // Kept short: this shows on a small flag, not a full screen.
        return List.of(new Warning("This song raises render distance to " + clamped
                + " chunks — may lag on low-end PCs."));
    }

    /**
     * Warnings written by the song's author. A pack ships a plain-text
     * {@code warnings.txt}: one in {@code data/} shows on every song in the pack,
     * and one in {@code data/<song>/} shows only for that song. Each non-empty
     * line is one flag, and a line may begin with a hex colour for its stripe:
     *
     * <pre>
     * #ff5555 Loud audio ahead — turn your volume down.
     * #55aaff Bright flashing lights.
     * A line with no colour uses the default.
     * // lines starting with two slashes are ignored
     * </pre>
     */
    private static List<Warning> customWarnings(Context context) {
        List<Warning> warnings = new ArrayList<>();
        for (Path file : warningFiles(context)) {
            try {
                for (String raw : Files.readAllLines(file)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("//")) continue;
                    warnings.add(parseWarningLine(line));
                }
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Could not read {}: {}", file, error.toString());
            }
        }
        return warnings;
    }

    private static final Pattern LEADING_HEX =
            Pattern.compile("^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})\\s+(.*)$");

    /** Splits an optional leading hex colour from the message on a warnings.txt line. */
    private static Warning parseWarningLine(String line) {
        Matcher matcher = LEADING_HEX.matcher(line);
        if (!matcher.matches()) return new Warning(line);
        long rgb = Long.parseLong(matcher.group(1), 16);
        // A 6-digit colour is opaque; an 8-digit one carries its own alpha.
        int color = matcher.group(1).length() == 6 ? (int) (0xFF000000L | rgb) : (int) rgb;
        String text = matcher.group(2).strip();
        return text.isEmpty() ? new Warning(line) : new Warning(text, color);
    }

    /**
     * The warnings.txt files for a song, in show order: the pack-wide
     * {@code data/warnings.txt} first, then the song's own. A pack-wide file lets
     * an author warn on every song at once.
     */
    private static List<Path> warningFiles(Context context) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        Path modRoot = context.entry() == null ? null : context.entry().modRoot;
        String id = context.songId();
        if (modRoot != null) {
            files.add(modRoot.resolve("data").resolve("warnings.txt")); // every song in the pack
            if (id != null && !id.isBlank()) {
                files.add(modRoot.resolve("data").resolve(id).resolve("warnings.txt"));
            }
        }
        if (context.songFolder() != null) {
            files.add(context.songFolder().resolve("warnings.txt"));
        }
        List<Path> present = new ArrayList<>();
        for (Path file : files) {
            if (Files.isRegularFile(file)) present.add(file);
        }
        return present;
    }

    /** Largest render distance any setRenderDistance call in this source resolves to. */
    private static int highestRenderDistance(String source) {
        int highest = 0;
        Matcher matcher = SET_RENDER_DISTANCE.matcher(source);
        while (matcher.find()) {
            String argument = balancedArgument(source, matcher.end());
            if (argument == null) continue;
            Integer value = evaluateChunks(argument);
            if (value != null) highest = Math.max(highest, value);
        }
        return highest;
    }

    /**
     * The text between {@code (} and its matching {@code )}, so a nested argument
     * like {@code (5 + 5) * 2} is captured whole. Returns null if the parentheses
     * never balance.
     */
    private static String balancedArgument(String source, int afterOpenParen) {
        int depth = 1;
        for (int i = afterOpenParen; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return source.substring(afterOpenParen, i).trim();
        }
        return null;
    }

    /**
     * Resolves the numeric value of a setRenderDistance argument.
     *
     * <p>A plain number is read directly. A pure-arithmetic argument such as
     * {@code 10 + 6} is worked out with a tiny sandbox: it holds only numbers,
     * operators, and parentheses, so it cannot call anything, loop, or reach the
     * game — it is arithmetic and nothing else. An argument that mentions a
     * variable or a function returns null, because its value is not knowable
     * without running the whole script, and the clamp still protects the player
     * at runtime either way.
     */
    private static Integer evaluateChunks(String argument) {
        if (argument.matches("\\d+")) {
            try {
                return Integer.parseInt(argument);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (!argument.matches("[-+*/%^().\\d\\s]+")) return null; // has an identifier or call
        try {
            // A bare Globals with only the compiler installed runs arithmetic and
            // nothing else: no io, os, string, or math library is present.
            org.luaj.vm2.Globals sandbox = new org.luaj.vm2.Globals();
            org.luaj.vm2.compiler.LuaC.install(sandbox);
            org.luaj.vm2.LuaValue result = sandbox.load("return " + argument).call();
            return result.isnumber() ? (int) Math.floor(result.todouble()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Every .lua file that could run for this song, matching the documented Psych
     * locations: global scripts, the pack's {@code scripts/}, {@code stages/},
     * {@code custom_events/} and {@code custom_notetypes/}, and the song's own
     * {@code data/<song>/} and folder. Scanning a pack's shared event and note
     * scripts can over-warn slightly — a heavy event the chart never fires still
     * counts — which is the safe direction for an advisory.
     */
    private static List<Path> luaScripts(Context context) {
        LinkedHashSet<Path> scripts = new LinkedHashSet<>();
        collectLua(SongLibrary.scriptsDir(), scripts); // global scripts run for every song

        Path modRoot = context.entry() == null ? null : context.entry().modRoot;
        if (modRoot != null) {
            for (String folder : new String[]{"scripts", "stages", "custom_events", "custom_notetypes"}) {
                collectLua(modRoot.resolve(folder), scripts);
            }
            String id = context.songId();
            if (id != null && !id.isBlank()) {
                collectLua(modRoot.resolve("data").resolve(id), scripts);
                collectLua(modRoot.resolve("songs").resolve(id), scripts);
            }
        }

        if (context.songFolder() != null) collectLua(context.songFolder(), scripts);
        return new ArrayList<>(scripts);
    }

    private static void collectLua(Path root, Collection<Path> out) {
        if (root == null || !Files.isDirectory(root)) return;
        try (Stream<Path> tree = Files.walk(root, 4)) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".lua"))
                    .forEach(out::add);
        } catch (Exception ignored) {
            // A missing or unreadable folder simply contributes no scripts.
        }
    }
}
