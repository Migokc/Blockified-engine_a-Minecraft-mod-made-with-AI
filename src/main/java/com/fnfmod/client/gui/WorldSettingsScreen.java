package com.fnfmod.client.gui;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.client.world.WorldSettingsIO;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModWorldOptions;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;

/**
 * Tabbed in-menu editor for a bundled mod world's {@code blockified-options.json}, styled to
 * match the Character Editor for continuity. Reachable from the song selector only while
 * hosting a verified mod world. Writes the world controls plus, optionally, a forced copy of
 * the player's gameplay settings, and can bundle placed BBS model-block assets into the world.
 */
public class WorldSettingsScreen extends Screen {

    private static final int PANEL = 0xFF181820;

    private enum Tab {
        WORLD("World"), GAMEPLAY("Gameplay"), ASSETS("Assets");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private final Screen parent;
    private final Path worldRoot;
    private JsonObject settings;
    private Tab activeTab = Tab.WORLD;
    private String bundleStatus = "";

    public WorldSettingsScreen(Screen parent, Path worldRoot) {
        super(Component.literal("World Settings"));
        this.parent = parent;
        this.worldRoot = worldRoot;
    }

    private int panelX() { return width / 2 - 150; }
    private int panelWidth() { return 300; }

    @Override
    protected void init() {
        if (settings == null) settings = WorldSettingsIO.read(worldRoot);
        int x = panelX();
        int w = panelWidth();

        int tabWidth = (w - 4) / Tab.values().length;
        int tabX = x;
        for (Tab tab : Tab.values()) {
            Button button = addRenderableWidget(Button.builder(Component.literal(tab.label), b -> switchTab(tab))
                    .bounds(tabX, 30, tabWidth, 20).build());
            button.active = tab != activeTab;
            tabX += tabWidth + 2;
        }

        int y = 64;
        int step = 24;
        switch (activeTab) {
            case WORLD -> {
                add(x, y, w, cheatsLabel(), b -> { cycleCheats(); b.setMessage(cheatsLabel()); },
                        "Default keeps the world's own level.dat cheats. On/Off force it for this world.");
                add(x, y += step, w, boolLabel("Save changes to world", "saveOnExit", true),
                        b -> { toggle("saveOnExit", true); b.setMessage(boolLabel("Save changes to world", "saveOnExit", true)); },
                        "Off: block/entity/player/time changes are not persisted when leaving. Good for a fixed showcase.");
                add(x, y += step, w, externalLabel(), b -> { toggle("allowExternalContent", false); b.setMessage(externalLabel()); },
                        "Only this mod: solely this pack's songs/Lua/assets are usable (best for sharing). "
                                + "Allow external: also use installed packs and configured directories.");
                add(x, y += step, w, boolLabel("Hide this button", "hideSettingsButton", false),
                        b -> { toggle("hideSettingsButton", false); b.setMessage(boolLabel("Hide this button", "hideSettingsButton", false)); },
                        "Hides this World Settings button in the menu. Re-enable by editing blockified-options.json.");
            }
            case GAMEPLAY -> add(x, y, w, forcedLabel(), b -> {
                if (WorldSettingsIO.hasForcedGameplay(settings)) WorldSettingsIO.clearForcedGameplay(settings);
                else WorldSettingsIO.copyPlayerGameplay(settings);
                b.setMessage(forcedLabel());
            }, "Force this world's players to use YOUR current gameplay/visual settings (note colors, scroll, HUD, "
                    + "skins, icons...). Saved in the world file. Mods and directories are never forced. Toggle to clear.");
            case ASSETS -> add(x, y, w, Component.literal("Bundle BBS model-block assets"), b -> {
                Path assets = ModWorldOptions.bundledAssetsRoot();
                int n = assets == null ? 0 : CharacterAnimations.bundleWorldModelBlocks(assets);
                bundleStatus = n > 0 ? ("Bundled " + n + " model-block form(s) into the world.")
                        : "No placed model blocks found nearby.";
            }, "Copies placed BBS model blocks' models/textures into the world folder so they render for anyone "
                    + "you share the world with. Only existing placed blocks are bundled.");
        }

        int actionY = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Save & Close"), b -> saveAndClose())
                .bounds(x, actionY, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x + w / 2 + 2, actionY, w / 2 - 2, 20).build());
    }

    private void add(int x, int y, int w, Component label, Button.OnPress onPress, String tooltip) {
        Button button = addRenderableWidget(Button.builder(label, onPress).bounds(x, y, w, 20).build());
        button.setTooltip(Tooltip.create(Component.literal(tooltip)));
    }

    private void switchTab(Tab tab) {
        if (tab == activeTab) return;
        activeTab = tab;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- labels

    private Component cheatsLabel() {
        String value = !settings.has("allowCheats") ? "Default (world)"
                : settings.get("allowCheats").getAsBoolean() ? "On" : "Off";
        return Component.literal("Cheats: " + value);
    }

    private Component externalLabel() {
        boolean allow = settings.has("allowExternalContent") && settings.get("allowExternalContent").getAsBoolean();
        return Component.literal("Content: " + (allow ? "Allow external packs/dirs" : "Only this mod"));
    }

    private Component forcedLabel() {
        return Component.literal(WorldSettingsIO.hasForcedGameplay(settings)
                ? "Forced settings: ON (click to clear)"
                : "Force my gameplay settings");
    }

    private Component boolLabel(String name, String key, boolean fallback) {
        boolean on = settings.has(key) ? settings.get(key).getAsBoolean() : fallback;
        return Component.literal(name + ": " + (on ? "Yes" : "No"));
    }

    // ---------------------------------------------------------------- edits

    private void toggle(String key, boolean fallback) {
        boolean current = settings.has(key) ? settings.get(key).getAsBoolean() : fallback;
        settings.add(key, new JsonPrimitive(!current));
    }

    /** Cheats cycle: Default (absent) -> On -> Off -> Default. */
    private void cycleCheats() {
        if (!settings.has("allowCheats")) settings.add("allowCheats", new JsonPrimitive(true));
        else if (settings.get("allowCheats").getAsBoolean()) settings.add("allowCheats", new JsonPrimitive(false));
        else settings.remove("allowCheats");
    }

    private void saveAndClose() {
        if (WorldSettingsIO.write(worldRoot, settings)) {
            ModWorldOptions.loadActiveWorld();
            ClientOptions.applyWorldOverrides(worldRoot);
            SongLibrary.rescan();
            IconLibrary.rescan();
            PacketDistributor.sendToServer(new FnfPayloads.ReloadC2S(
                    SongLibrary.processNonce(), SongLibrary.rescanGeneration()));
            com.fnfmod.client.render.NoteStyle.reload();
        }
        onClose();
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xFF101014);
        gui.fill(panelX() - 6, 24, panelX() + panelWidth() + 6, height - 34, PANEL);
        gui.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        super.render(gui, mouseX, mouseY, partialTick);
        String hint = switch (activeTab) {
            case WORLD -> "Controls for this bundled world.";
            case GAMEPLAY -> "Force your visual/gameplay settings onto this world (stored in its JSON).";
            case ASSETS -> "Ship BBS model-block assets inside the world for sharing.";
        };
        gui.drawCenteredString(font, Component.literal(hint), width / 2, 54 - 4, 0xFFB0B0C0);
        if (!bundleStatus.isEmpty()) {
            gui.drawCenteredString(font, Component.literal(bundleStatus), width / 2, height - 46, 0xA0FFA0);
        }
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Custom opaque background painted in render(); skip the vanilla blur/dirt.
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
