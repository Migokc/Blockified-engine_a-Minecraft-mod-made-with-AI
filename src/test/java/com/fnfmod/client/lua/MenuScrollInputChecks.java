package com.fnfmod.client.lua;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Runs the actual wheel-to-Lua bridge without a window or a Minecraft world. */
public final class MenuScrollInputChecks {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        Globals globals = JsePlatform.standardGlobals();
        LuaTable cursor = new LuaTable();
        globals.set("cursor", cursor);
        MenuScrollInput input = new MenuScrollInput(globals, cursor,
                (function, values) -> function.invoke(LuaValue.varargsOf(values)));
        globals.load("""
            assert(getMouseWheel() == 0 and getMouseWheelX() == 0)
            assert(cursor.scrollX == 0 and cursor.scrollY == 0)
            calls = {}
            target = {id = 'panel'}
            function target:onScroll(dy, dx)
                assert(self.id == 'panel')
                table.insert(calls, {'widget', dy, dx})
            end
            function onScroll(dy, dx)
                table.insert(calls, {'global', dy, dx})
            end
            """).call();
        check(input.scroll(0.25, 1, globals.get("target").checktable()), "widget/global consume event");
        check(input.scroll(-0.5, -0.25, null), "empty-space global callback");
        globals.load("""
            assert(#calls == 3)
            assert(calls[1][1] == 'widget' and calls[2][1] == 'global')
            assert(calls[1][2] == 1 and calls[1][3] == 0.25)
            assert(calls[3][2] == -0.25 and calls[3][3] == -0.5)
            assert(getMouseWheel() == 0.75 and getMouseWheelX() == -0.25)
            assert(getMouseWheel() == 0.75) -- reads must not consume input
            assert(cursor.scrollY == 0.75 and cursor.scrollX == -0.25)
            """).call();
        check(!input.scroll(0, 0, null), "ignore empty event");
        check(!input.scroll(Double.NaN, 1, null), "ignore non-finite event");
        check(!input.scroll(0, Double.POSITIVE_INFINITY, null), "ignore infinite event");
        globals.load("assert(#calls == 3 and getMouseWheel() == 0.75)").call();
        input.reset();
        globals.load("assert(getMouseWheel() == 0 and cursor.scrollX == 0)").call();
        globals.set("onScroll", LuaValue.NIL); // page callbacks are cleared on navigation
        check(!input.scroll(2, 3, null), "polling without a callback");
        globals.load("assert(#calls == 3 and getMouseWheel() == 3 and getMouseWheelX() == 2)").call();
        input.reset(); // tick/page/pause reset
        globals.load("assert(getMouseWheel() == 0 and getMouseWheelX() == 0)").call();
        System.out.println("Menu Lua scroll callback/polling checks passed.");
    }
}
