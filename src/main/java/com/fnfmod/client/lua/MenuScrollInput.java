package com.fnfmod.client.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;

/** Menu wheel callbacks plus non-consuming, per-update polling (including trackpads). */
final class MenuScrollInput {
    @FunctionalInterface
    interface Callback {
        void call(LuaValue function, LuaValue... args);
    }

    private final Globals globals;
    private final LuaTable cursor;
    private final Callback callback;
    private double x, y;

    MenuScrollInput(Globals globals, LuaTable cursor, Callback callback) {
        this.globals = globals;
        this.cursor = cursor;
        this.callback = callback;
        globals.set("getMouseWheel", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(y); }
        });
        globals.set("getMouseWheelX", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(x); }
        });
        reset();
    }

    boolean scroll(double dx, double dy, LuaTable target) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || dx == 0 && dy == 0) return false;
        x += dx;
        y += dy;
        cursor.set("scrollX", x);
        cursor.set("scrollY", y);
        LuaValue vertical = LuaValue.valueOf(dy);
        LuaValue horizontal = LuaValue.valueOf(dx);
        boolean handled = false;
        if (target != null && target.get("onScroll").isfunction()) {
            callback.call(target.get("onScroll"), target, vertical, horizontal);
            handled = true;
        }
        LuaValue global = globals.get("onScroll");
        if (global.isfunction()) {
            callback.call(global, vertical, horizontal);
            handled = true;
        }
        return handled;
    }

    /** Called after onUpdate, and before changing pages or suspending the menu. */
    void reset() {
        x = y = 0;
        cursor.set("scrollX", 0);
        cursor.set("scrollY", 0);
    }
}
