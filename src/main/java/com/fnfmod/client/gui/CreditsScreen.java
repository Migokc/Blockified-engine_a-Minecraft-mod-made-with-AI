package com.fnfmod.client.gui;

import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.song.ModPackInfo;
import com.fnfmod.song.SongLibrary;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Blockified credits plus Psych/V-Slice credits discovered from installed packs. */
public final class CreditsScreen extends Screen {
    private static final int ROW = 28;
    private static final long SCROLL_NANOS = 200_000_000L;

    private record Credit(String name, String role, String url, int color,
                          boolean heading, Path icon) {}

    private final Screen parent;
    private final List<Credit> credits = new ArrayList<>();
    private double scrollPx, scrollTargetPx, scrollFromPx;
    private long scrollStarted;
    private boolean scrolling;
    private boolean draggingThumb;

    public CreditsScreen(Screen parent) {
        super(Component.literal("Credits"));
        this.parent = parent;
        buildCredits();
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 - 70, height - 30, 140, 20).build());
        clampScroll();
    }

    private void buildCredits() {
        credits.clear();
        // Hardcoded project credits are intentionally non-clickable, even where
        // a public profile exists. Only imported mod rows honor external URLs.
        heading("Blockified Engine");
        fixed("Migókc", "Creator, direction, design, testing and development");
        fixed("Giovanna", "Project contributor");
        fixed("ChatGPT", "AI-assisted programming and documentation");
        fixed("Claude", "AI-assisted programming and development");
        fixed("Blockified Engine GitHub contributors", "Additional project contributions");

        heading("Psych Engine");
        fixed("Shadow Mario", "Main programmer and head of Psych Engine");
        fixed("Riveren", "Main artist and animator");
        fixed("bbpanzu", "Former team programmer");
        fixed("crowplexus", "HScript Iris, Input System v3 and code contributions");
        fixed("SqirraRNG", "Crash handler and chart-editor waveform base code");
        fixed("EliteMasterEric", "Runtime shaders and code contributions");
        fixed("MAJigsaw77", "hxvlc video library");
        fixed("KadeDev", "Chart-editor fixes and code contributions");
        fixed("superpowers04", "LuaJIT fork");

        heading("Friday Night Funkin' / Funkin Crew");
        fixed("ninjamuffin99", "Lead programmer");
        fixed("EliteMasterEric", "Programmer");
        fixed("MtH", "Charting and additional programming");
        fixed("GeoKureli", "Additional programming");
        fixed("ZackDroid", "Lead mobile programmer");
        fixed("MAJigsaw77", "Mobile programmer");
        fixed("Karim-Akra", "Mobile programmer");
        fixed("Sector_5", "Mobile programmer");
        fixed("Luckydog7", "Mobile programmer");
        fixed("PhantomArcade3K", "Artist and animator");
        fixed("Evilsk8r", "Artist");
        fixed("Kawaisprite", "Musician");
        fixed("Funkin GitHub contributors", "Additional base-game contributions");

        heading("Codename Engine");
        fixed("Yoshman29", "Founder and original creator");
        fixed("Codename Crew", "Engine development team");
        fixed("Nex_isDumb", "Codename Crew owner");
        fixed("Lunarcleint", "Codename Crew owner");
        fixed("WizardMantis", "Codename Crew owner");
        fixed("Frakits", "Codename Crew owner");
        fixed("Raltyro", "Codename Crew owner");
        fixed("Codename Engine GitHub contributors", "Engine code contributions");
        fixed("FlxAnimate team", "Animate Atlas support");
        fixed("Smokey555", "Animate Atlas fallback code");
        fixed("MAJigsaw77", "hxvlc and hxdiscord_rpc");
        fixed("TheoDev", "FunkinModchart integration");

        appendInstalledModCredits();
    }

    private void heading(String name) {
        credits.add(new Credit(name, "", "", BlockifiedScreenStyle.ACCENT, true, null));
    }

    private void fixed(String name, String role) {
        credits.add(new Credit(name, role, "", 0xFFFFFFFF, false, null));
    }

    private void appendInstalledModCredits() {
        LinkedHashMap<Path, ModPackInfo> packs = new LinkedHashMap<>();
        collectPacks(SongLibrary.modsDir(), packs);
        for (String configured : SongLibrary.getExternalFolders()) {
            try { collectPacks(Path.of(configured), packs); }
            catch (RuntimeException ignored) {}
        }

        boolean addedHeader = false;
        for (ModPackInfo pack : packs.values()) {
            Path psychCredits = pack.root().resolve("data").resolve("credits.txt");
            boolean hasPsych = Files.isRegularFile(psychCredits);
            if (pack.contributors().isEmpty() && !hasPsych) continue;
            if (!addedHeader) {
                heading("Installed Mod Credits");
                addedHeader = true;
            }
            String suffix = pack.version().isBlank() ? "" : "  " + pack.version();
            heading(pack.name() + suffix);
            for (ModPackInfo.Contributor contributor : pack.contributors()) {
                credits.add(new Credit(contributor.name(), contributor.role(),
                        safeUrl(contributor.url()), 0xFFFFFFFF, false, null));
            }
            if (hasPsych) appendPsychCredits(psychCredits);
        }
    }

    private static void collectPacks(Path source, LinkedHashMap<Path, ModPackInfo> output) {
        if (source == null || !Files.isDirectory(source)) return;
        Path normalized = source.toAbsolutePath().normalize();
        // Psych permits a credits file directly in the naked mods root.
        if (Files.isRegularFile(normalized.resolve("data").resolve("credits.txt"))) {
            output.putIfAbsent(normalized, ModPackInfo.read(normalized));
        }
        for (ModPackInfo pack : SongLibrary.discoverModPacks(normalized)) {
            output.putIfAbsent(pack.root(), pack);
        }
    }

    private void appendPsychCredits(Path file) {
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.replace("\uFEFF", "").trim();
                if (line.isBlank()) continue;
                String[] fields = line.split("::", -1);
                if (fields.length == 1) {
                    heading(fields[0].trim());
                    continue;
                }
                String name = fields[0].trim();
                if (name.isBlank()) continue;
                String role = fields.length > 2 ? fields[2].trim() : "Contributor";
                String url = fields.length > 3 ? safeUrl(fields[3]) : "";
                int color = fields.length > 4 ? parseColor(fields[4]) : 0xFFFFFFFF;
                Path icon = null;
                if (fields.length > 1 && !fields[1].isBlank()) {
                    Path candidate = file.getParent().getParent().resolve("images").resolve("credits")
                            .resolve(fields[1].trim() + ".png").normalize();
                    if (Files.isRegularFile(candidate)) icon = candidate;
                }
                credits.add(new Credit(name, role, url, color, false, icon));
            }
        } catch (Exception ignored) {}
    }

    private static String safeUrl(String value) {
        String url = value == null ? "" : value.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? url : "";
    }

    private static int parseColor(String raw) {
        try {
            String value = raw.trim().replace("#", "");
            return 0xFF000000 | Integer.parseInt(value, 16) & 0xFFFFFFFF;
        } catch (Exception ignored) {
            return 0xFFFFFFFF;
        }
    }

    private int panelWidth() { return Math.min(600, Math.max(260, width - 24)); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int listTop() { return 30; }
    private int listBottom() { return Math.max(listTop() + ROW, height - 38); }
    private int listHeight() { return listBottom() - listTop(); }
    private double maxScroll() { return Math.max(0, credits.size() * (double) ROW - listHeight()); }

    private void clampScroll() {
        scrollPx = Mth.clamp(scrollPx, 0, maxScroll());
        scrollTargetPx = Mth.clamp(scrollTargetPx, 0, maxScroll());
    }

    private void scrollTo(double target) {
        updateScroll();
        scrollFromPx = scrollPx;
        scrollTargetPx = Mth.clamp(target, 0, maxScroll());
        scrollStarted = System.nanoTime();
        scrolling = Math.abs(scrollTargetPx - scrollFromPx) > 0.01;
    }

    private void updateScroll() {
        if (!scrolling) return;
        double progress = (System.nanoTime() - scrollStarted) / (double) SCROLL_NANOS;
        if (progress >= 1) {
            scrollPx = scrollTargetPx;
            scrolling = false;
        } else {
            scrollPx = scrollFromPx + (scrollTargetPx - scrollFromPx)
                    * Easing.apply("expoOut", progress);
        }
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        updateScroll();
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x = panelX(), w = panelWidth();
        BlockifiedScreenStyle.panel(gui, x, 6, w, height - 12);
        BlockifiedScreenStyle.inner(gui, x + 10, listTop() - 2, w - 20, listHeight() + 4);
        gui.drawCenteredString(font, "Credits", width / 2, 12, BlockifiedScreenStyle.TEXT);

        gui.enableScissor(x + 11, listTop(), x + w - 11, listBottom());
        for (int i = 0; i < credits.size(); i++) {
            Credit credit = credits.get(i);
            int rowY = listTop() + i * ROW - (int) Math.round(scrollPx);
            if (rowY + ROW < listTop() || rowY > listBottom()) continue;
            boolean linked = !credit.url().isBlank();
            boolean hovered = linked && mouseX >= x + 14 && mouseX < x + w - 14
                    && mouseY >= rowY && mouseY < rowY + ROW;
            if (credit.heading()) {
                gui.fill(x + 16, rowY + ROW - 5, x + w - 16, rowY + ROW - 4,
                        0x66754A92);
                gui.drawString(font, credit.name(), x + 18, rowY + 8, credit.color(), false);
            } else {
                if (hovered) gui.fill(x + 14, rowY + 1, x + w - 14, rowY + ROW - 1,
                        0x33FFFFFF);
                int textX = x + 20;
                if (credit.icon() != null) {
                    IconLibrary.drawFile(gui, credit.icon().toString(), 0,
                            x + 28, rowY + ROW / 2f, 20, false);
                    textX = x + 43;
                }
                gui.drawString(font, credit.name(), textX, rowY + 4,
                        hovered ? 0xFFFFFF88 : credit.color(), false);
                String role = trim(credit.role(), w - 48);
                gui.drawString(font, role, textX, rowY + 15,
                        BlockifiedScreenStyle.TEXT_MUTED, false);
            }
        }
        gui.disableScissor();
        renderScrollbar(gui);
        super.render(gui, mouseX, mouseY, partialTick);
    }

    private String trim(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return value == null ? "" : value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("..."))) + "...";
    }

    private void renderScrollbar(GuiGraphics gui) {
        if (maxScroll() <= 0) return;
        int x = panelX() + panelWidth() - 8;
        int height = listHeight();
        int thumb = Math.max(14, (int) (height * height / (double) (credits.size() * ROW)));
        int y = listTop() + (int) ((height - thumb) * (scrollPx / maxScroll()));
        gui.fill(x, listTop(), x + 3, listBottom(), 0x55000000);
        gui.fill(x, y, x + 3, y + thumb, BlockifiedScreenStyle.ACCENT);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int scrollbarX = panelX() + panelWidth() - 8;
            if (maxScroll() > 0 && mouseX >= scrollbarX - 2 && mouseX < scrollbarX + 6
                    && mouseY >= listTop() && mouseY < listBottom()) {
                draggingThumb = true;
                dragScroll(mouseY);
                return true;
            }
            if (mouseX >= panelX() + 14 && mouseX < panelX() + panelWidth() - 14
                    && mouseY >= listTop() && mouseY < listBottom()) {
                int index = (int) Math.floor((mouseY - listTop() + scrollPx) / ROW);
                if (index >= 0 && index < credits.size()) {
                    Credit credit = credits.get(index);
                    if (!credit.url().isBlank()) {
                        try { Util.getPlatform().openUri(URI.create(credit.url())); }
                        catch (Exception ignored) {}
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingThumb) {
            dragScroll(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingThumb = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragScroll(double mouseY) {
        double fraction = (mouseY - listTop()) / Math.max(1, listHeight());
        scrollPx = Mth.clamp(fraction, 0, 1) * maxScroll();
        scrollTargetPx = scrollFromPx = scrollPx;
        scrolling = false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scrollTo(scrollTargetPx - scrollY * 22);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
}
