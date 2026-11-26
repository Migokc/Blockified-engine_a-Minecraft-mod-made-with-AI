package com.fnfmod.client.gui;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Conservative compatibility colours must not claim unknown mods are Psych-ready. */
public final class GameBananaBrowserChecks {
    public static void main(String[] args) {
        expect("P-Slice", "A P-Slice port");
        expect("Psych", "Psych Engine port");
        expect("V-Slice", "FNF V-Slice mod");
        expect("Codename", "Codename Engine chart");
        expect("Unknown", "Cool FNF mod");
        List<GameBananaBrowserScreen.Listing> results = new ArrayList<>(List.of(
                listing("1", "Metal Mania Madness"),
                listing("2", "Sonic the Sketchhog"),
                listing("3", "Sonic"),
                listing("4", "FNF Sonic collection"),
                listing("5", "Sonic EXE")));
        GameBananaBrowserScreen.rankListings(results, "sonic");
        require(results.get(0).id().equals("3"), "exact title should rank first");
        require(results.get(1).id().equals("2") && results.get(2).id().equals("5"),
                "title-prefix matches should beat buried or description-only matches");
        require(results.get(4).id().equals("1"), "description-only match should rank last");

        GameBananaBrowserScreen.rankListings(results, "sonic.exe");
        require(results.get(0).id().equals("5"), "punctuation-insensitive title search failed");
        System.out.println("GameBanana engine marker checks passed.");
    }

    private static GameBananaBrowserScreen.Listing listing(String id, String name) {
        return new GameBananaBrowserScreen.Listing(id, name, "https://gamebanana.com/mods/" + id, "Unknown");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(String expected, String name) {
        JsonObject item = new JsonObject();
        item.addProperty("_sName", name);
        String actual = GameBananaBrowserScreen.classify(item);
        if (!expected.equals(actual)) throw new AssertionError(name + " => " + actual);
    }
}
