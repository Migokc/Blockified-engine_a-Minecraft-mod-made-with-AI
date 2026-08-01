package com.fnfmod.client.math;

import java.util.Locale;

/** Shared Psych/Flixel easing curves for Lua tweens and engine camera events. */
public final class Easing {
    public static final String[] BASES = {
            "smooth", "sine", "cubic", "quint", "circ", "elastic",
            "quad", "quart", "expo", "back", "bounce", "linear", "constant"
    };
    public static final String[] DIRECTIONS = {"in", "out", "inOut"};

    private enum Direction { IN, OUT, IN_OUT }

    private Easing() {}

    public static double apply(String rawName, double progress) {
        double t = Math.max(0, Math.min(1, progress));
        String name = normalize(rawName);
        if (name.isEmpty() || name.equals("linear")) return t;
        if (name.equals("constant") || name.equals("snap")) return 1;

        Direction direction = Direction.IN;
        if (name.endsWith("inout")) {
            direction = Direction.IN_OUT;
            name = name.substring(0, name.length() - 5);
        } else if (name.endsWith("out")) {
            direction = Direction.OUT;
            name = name.substring(0, name.length() - 3);
        } else if (name.endsWith("in")) {
            name = name.substring(0, name.length() - 2);
        } else if (name.startsWith("inout")) {
            direction = Direction.IN_OUT;
            name = name.substring(5);
        } else if (name.startsWith("out")) {
            direction = Direction.OUT;
            name = name.substring(3);
        } else if (name.startsWith("in")) {
            name = name.substring(2);
        }

        return switch (name) {
            case "sine", "sin", "sinusoidal" -> sine(t, direction);
            case "cubic", "cube" -> power(t, direction, 3);
            case "quint", "quintic" -> power(t, direction, 5);
            case "circ", "circular" -> circ(t, direction);
            case "elastic" -> elastic(t, direction);
            case "quad", "quadratic" -> power(t, direction, 2);
            case "quart", "quartic" -> power(t, direction, 4);
            case "expo", "exponential" -> expo(t, direction);
            case "back" -> back(t, direction);
            case "bounce" -> bounce(t, direction);
            case "smooth", "smoothstep" -> t * t * (3 - 2 * t);
            default -> t;
        };
    }

    public static String normalize(String raw) {
        String value = raw == null ? "linear" : raw.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        return value.startsWith("ease") ? value.substring(4) : value;
    }

    private static double sine(double t, Direction d) {
        return switch (d) {
            case IN -> 1 - Math.cos(t * Math.PI / 2);
            case OUT -> Math.sin(t * Math.PI / 2);
            case IN_OUT -> -(Math.cos(Math.PI * t) - 1) / 2;
        };
    }

    private static double power(double t, Direction d, int n) {
        return switch (d) {
            case IN -> Math.pow(t, n);
            case OUT -> 1 - Math.pow(1 - t, n);
            case IN_OUT -> t < 0.5 ? Math.pow(2, n - 1) * Math.pow(t, n)
                    : 1 - Math.pow(-2 * t + 2, n) / 2;
        };
    }

    private static double circ(double t, Direction d) {
        return switch (d) {
            case IN -> 1 - Math.sqrt(1 - t * t);
            case OUT -> Math.sqrt(1 - Math.pow(t - 1, 2));
            case IN_OUT -> t < 0.5
                    ? (1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2
                    : (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2;
        };
    }

    private static double expo(double t, Direction d) {
        if (t == 0 || t == 1) return t;
        return switch (d) {
            case IN -> Math.pow(2, 10 * t - 10);
            case OUT -> 1 - Math.pow(2, -10 * t);
            case IN_OUT -> t < 0.5 ? Math.pow(2, 20 * t - 10) / 2
                    : (2 - Math.pow(2, -20 * t + 10)) / 2;
        };
    }

    private static double back(double t, Direction d) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return switch (d) {
            case IN -> c3 * t * t * t - c1 * t * t;
            case OUT -> 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
            case IN_OUT -> {
                double c2 = c1 * 1.525;
                yield t < 0.5
                        ? Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2) / 2
                        : (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2;
            }
        };
    }

    private static double elastic(double t, Direction d) {
        if (t == 0 || t == 1) return t;
        double c4 = 2 * Math.PI / 3;
        double c5 = 2 * Math.PI / 4.5;
        return switch (d) {
            case IN -> -Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * c4);
            case OUT -> Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1;
            case IN_OUT -> t < 0.5
                    ? -(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * c5)) / 2
                    : Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * c5) / 2 + 1;
        };
    }

    private static double bounce(double t, Direction d) {
        return switch (d) {
            case IN -> 1 - bounceOut(1 - t);
            case OUT -> bounceOut(t);
            case IN_OUT -> t < 0.5 ? (1 - bounceOut(1 - 2 * t)) / 2
                    : (1 + bounceOut(2 * t - 1)) / 2;
        };
    }

    private static double bounceOut(double t) {
        double n1 = 7.5625;
        double d1 = 2.75;
        if (t < 1 / d1) return n1 * t * t;
        if (t < 2 / d1) {
            double p = t - 1.5 / d1;
            return n1 * p * p + 0.75;
        }
        if (t < 2.5 / d1) {
            double p = t - 2.25 / d1;
            return n1 * p * p + 0.9375;
        }
        double p = t - 2.625 / d1;
        return n1 * p * p + 0.984375;
    }
}
