package com.fnfmod.client.lua;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.*;

/** Shared, plain-Lua effect properties. No asset loading, code evaluation or disk cache. */
public final class LuaLayerEffects {
    private LuaLayerEffects() {}
    public static final Map<String, Double> NUMBERS = Map.ofEntries(
            Map.entry("gradientAngle", 0d), Map.entry("gradientX", .5), Map.entry("gradientY", .5),
            Map.entry("gradientRadius", .5), Map.entry("gradientStrength", 1d),
            Map.entry("gradientColor1", 16777215d), Map.entry("gradientColor2", 0d),
            Map.entry("gradientAlpha1", 1d), Map.entry("gradientAlpha2", 1d),
            Map.entry("maskX", 0d), Map.entry("maskY", 0d), Map.entry("maskScaleX", 1d),
            Map.entry("maskScaleY", 1d), Map.entry("maskAngle", 0d), Map.entry("maskSoftness", 0d),
            Map.entry("clipX", 0d), Map.entry("clipY", 0d), Map.entry("clipWidth", 1d),
            Map.entry("clipHeight", 1d), Map.entry("clipRadius", .1), Map.entry("clipSoftness", 0d));
    public static final Map<String, String> STRINGS = Map.of(
            "blendMode", "normal", "gradientType", "none", "mask", "", "maskMode", "alpha",
            "clipType", "none", "group", "", "gradientStops", "", "parent", "");
    public static final Map<String, Boolean> BOOLEANS = Map.of(
            "maskInvert", false, "maskOnly", false, "maskHitTest", false);
    public static final List<String> BLENDS = List.of("normal", "add", "multiply", "screen",
            "subtract", "lighten", "darken", "difference", "overlay");
    public static boolean field(String name) {
        return NUMBERS.containsKey(name) || STRINGS.containsKey(name) || BOOLEANS.containsKey(name);
    }
    public static LuaValue value(LuaTable data, String name) {
        LuaValue value = data.get(name);
        if (!value.isnil()) return value;
        if (NUMBERS.containsKey(name)) return LuaValue.valueOf(NUMBERS.get(name));
        if (STRINGS.containsKey(name)) return LuaValue.valueOf(STRINGS.get(name));
        if (BOOLEANS.containsKey(name)) return LuaValue.valueOf(BOOLEANS.get(name));
        return LuaValue.NIL;
    }
    public static double number(LuaTable data, String name) {
        double result = value(data, name).optdouble(NUMBERS.getOrDefault(name, 0d));
        return Double.isFinite(result) ? result : NUMBERS.getOrDefault(name, 0d);
    }
    public static boolean active(LuaTable data) {
        return !value(data, "gradientType").tojstring().equals("none")
                || !value(data, "mask").tojstring().isBlank()
                || !value(data, "clipType").tojstring().equals("none")
                || !value(data, "blendMode").tojstring().equals("normal");
    }
    public static boolean hidden(LuaTable data) {
        return data.get("maskOnly").optboolean(false) || !data.get("group").optjstring("").isBlank();
    }
    public static int color(LuaValue value, int fallback) {
        if (value.isnumber()) return value.toint() & 0xFFFFFF;
        if (value.isnil()) return fallback;
        return PsychColor.parse(value.tojstring()) & 0xFFFFFF;
    }
    public record Stop(double at, int color, double alpha) {}
    /** Compact editable data, not metadata: the same string is a regular Lua property. */
    public static List<Stop> stops(LuaTable data) {
        List<Stop> result = new ArrayList<>();
        String encoded = data.get("gradientStops").optjstring("");
        if (!encoded.isBlank()) for (String part : encoded.split(";")) {
            if (result.size() == 16) break;
            try {
                String[] bits = part.trim().split(":");
                double at = Double.parseDouble(bits[0]);
                double alpha = bits.length > 2 ? Double.parseDouble(bits[2]) : 1;
                if (Double.isFinite(at) && Double.isFinite(alpha)) result.add(new Stop(
                        Math.clamp(at, 0, 1), color(LuaValue.valueOf(bits[1]), 0), Math.clamp(alpha, 0, 1)));
            } catch (RuntimeException ignored) {}
        }
        if (result.size() < 2) return List.of(
                new Stop(0, (int) number(data, "gradientColor1"), number(data, "gradientAlpha1")),
                new Stop(1, (int) number(data, "gradientColor2"), number(data, "gradientAlpha2")));
        result.sort(Comparator.comparingDouble(Stop::at));
        return result;
    }
    public interface Host {
        LuaTable data(String id);
        LuaTable create(String kind, String id, double x, double y, double width, double height);
        void tween(String tag, String id, String field, double target, double seconds, String easing);
    }
    private interface Function { LuaValue call(Varargs args); }
    private static void fn(Globals globals, String name, Function fn) {
        globals.set(name, new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return fn.call(args); }
        });
    }
    private static LuaTable require(Host host, String id) {
        LuaTable table = host.data(id);
        if (table == null) throw new LuaError("Unknown layer object: " + id);
        return table;
    }
    private static String choice(LuaValue value, String fallback, List<String> choices) {
        String result = value.optjstring(fallback).toLowerCase(Locale.ROOT);
        if (result.equals("additive")) result = "add";
        if (!choices.contains(result)) throw new LuaError("Expected " + String.join(" / ", choices));
        return result;
    }
    private static void gradient(LuaTable data, LuaValue type, LuaValue colors, LuaValue angle) {
        data.set("gradientType", choice(type, "linear", List.of("none", "linear", "radial", "angular")));
        data.set("gradientAngle", angle.optdouble(0));
        if (!colors.isnil()) {
            LuaTable stops = colors.checktable();
            if (stops.length() < 2 || stops.length() > 16) throw new LuaError("Use 2 to 16 gradient stops");
            StringJoiner encoded = new StringJoiner(";");
            for (int i = 1; i <= stops.length(); i++) {
                LuaValue stop = stops.get(i);
                double at = stop.istable() ? stop.get("at").optdouble((i - 1d) / (stops.length() - 1))
                        : (i - 1d) / (stops.length() - 1);
                int rgb = color(stop.istable() ? stop.get("color") : stop, 0xFFFFFF);
                double alpha = stop.istable() ? stop.get("alpha").optdouble(1) : 1;
                if (!Double.isFinite(at) || !Double.isFinite(alpha)) throw new LuaError("Gradient values must be finite");
                encoded.add(Math.clamp(at, 0, 1) + ":#" + String.format(Locale.ROOT, "%06X", rgb)
                        + ":" + Math.clamp(alpha, 0, 1));
            }
            data.set("gradientStops", encoded.toString());
        }
    }
    public static void install(Globals globals, Host host) {
        fn(globals, "setBlendMode", a -> {
            require(host, a.checkjstring(1)).set("blendMode", choice(a.arg(2), "normal", BLENDS));
            return LuaValue.TRUE;
        });
        fn(globals, "setObjectGradient", a -> {
            gradient(require(host, a.checkjstring(1)), a.arg(2), a.arg(3), a.arg(4)); return LuaValue.TRUE;
        });
        fn(globals, "removeObjectGradient", a -> {
            require(host, a.checkjstring(1)).set("gradientType", "none"); return LuaValue.TRUE;
        });
        fn(globals, "makeLuaGradient", a -> {
            LuaTable data = host.create("gradient", a.checkjstring(1), a.optdouble(2, 0),
                    a.optdouble(3, 0), a.optdouble(4, 256), a.optdouble(5, 256));
            gradient(data, a.arg(6), a.arg(7), a.arg(8)); return data;
        });
        fn(globals, "setObjectMask", a -> {
            String target = a.checkjstring(1), source = a.checkjstring(2);
            LuaTable data = require(host, target), mask = require(host, source);
            // Reject cycles at assignment. Renderer also guards direct property edits.
            Set<String> seen = new HashSet<>(); seen.add(target);
            for (String id = source; !id.isBlank();) {
                if (!seen.add(id)) throw new LuaError("Cyclic mask reference");
                LuaTable node = host.data(id); id = node == null ? "" : node.get("mask").optjstring("");
            }
            data.set("mask", source);
            data.set("maskMode", choice(a.arg(3), "alpha", List.of("alpha", "luminance")));
            data.set("maskInvert", a.arg(4).optboolean(false) ? LuaValue.TRUE : LuaValue.FALSE);
            mask.set("maskOnly", a.arg(5).optboolean(true) ? LuaValue.TRUE : LuaValue.FALSE);
            return LuaValue.TRUE;
        });
        fn(globals, "removeObjectMask", a -> {
            require(host, a.checkjstring(1)).set("mask", ""); return LuaValue.TRUE;
        });
        fn(globals, "setObjectClip", a -> {
            LuaTable data = require(host, a.checkjstring(1));
            data.set("clipType", choice(a.arg(2), "rect", List.of("none", "rect", "circle", "rounded")));
            String[] fields = {"clipX", "clipY", "clipWidth", "clipHeight", "clipRadius", "clipSoftness"};
            for (int i = 0; i < fields.length; i++) data.set(fields[i], a.optdouble(i + 3, NUMBERS.get(fields[i])));
            return LuaValue.TRUE;
        });
        fn(globals, "makeLuaGroup", a -> host.create("group", a.checkjstring(1), a.optdouble(2, 0),
                a.optdouble(3, 0), a.optdouble(4, 1280), a.optdouble(5, 720)));
        fn(globals, "addToGroup", a -> {
            String group = a.checkjstring(1), child = a.checkjstring(2);
            LuaTable container=require(host, group); LuaTable data = require(host, child);
            if(!container.get("kind").optjstring("").equals("group")) throw new LuaError("Target is not a compositing group");
            Set<String> seen = new HashSet<>(); seen.add(child);
            for (String id = group; !id.isBlank();) {
                if (!seen.add(id)) throw new LuaError("Cyclic group reference");
                LuaTable node = host.data(id); id = node == null ? "" : node.get("group").optjstring("");
            }
            data.set("group", group); return LuaValue.TRUE;
        });
        fn(globals, "removeFromGroup", a -> { require(host, a.checkjstring(1)).set("group", ""); return LuaValue.TRUE; });
        fn(globals, "doTweenEffect", a -> {
            String id = a.checkjstring(2), field = a.checkjstring(3);
            require(host, id);
            if (!NUMBERS.containsKey(field)) throw new LuaError("Not a numeric effect property: " + field);
            host.tween(a.checkjstring(1), id, field, a.checkdouble(4), a.optdouble(5, 1), a.optjstring(6, "linear"));
            return LuaValue.TRUE;
        });
    }
}
