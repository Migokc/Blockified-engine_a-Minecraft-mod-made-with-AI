package com.fnfmod.client.gui;

import com.fnfmod.song.GameBananaModLinks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Paged FNF catalog and site-wide GameBanana search. Downloads open a separate file chooser. */
public final class GameBananaBrowserScreen extends Screen {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final int ROW_HEIGHT = 28;
    /** Search more than one API page before ranking, so a relevant title is not buried on page two. */
    private static final int SEARCH_PAGES_PER_VIEW = 3;
    private final Screen parent;
    private final Set<String> installedIds;
    private final List<Listing> listings = new ArrayList<>();
    private EditBox search;
    private String query = "";
    private long searchDueNanos;
    private int requestRevision;
    private int page = 1;
    private int pageCount = 1;
    private int remotePageCount = Integer.MAX_VALUE;
    private int scroll;
    private String status = "Loading GameBanana mods...";
    private boolean loading;

    record Listing(String id, String name, String url, String engine) {}
    private record SearchPage(List<Listing> listings, int pageCount) {}

    public GameBananaBrowserScreen(Screen parent) {
        super(Component.literal("GameBanana FNF Mods"));
        this.parent = parent;
        this.installedIds = new HashSet<>(GameBananaModLinks.installedIds());
    }

    @Override protected void init() {
        int x = panelX();
        int w = panelWidth();
        search = addRenderableWidget(new EditBox(font, x + 12, 42, Math.max(80, w - 24), 20,
                Component.literal("Search all FNF mods")));
        search.setHint(Component.literal("Search all FNF mods..."));
        search.setValue(query);
        search.setResponder(value -> {
            query = value.strip();
            page = 1;
            remotePageCount = Integer.MAX_VALUE;
            scroll = 0;
            listings.clear();
            requestRevision++;
            status = "Searching all FNF mods...";
            searchDueNanos = System.nanoTime() + 350_000_000L;
        });
        addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1))
                .bounds(x + 12, height - 35, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1))
                .bounds(x + 48, height - 35, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Search"), button -> {
            searchDueNanos = 0;
            requestRevision++;
            page = 1;
            fetchPage();
        }).bounds(x + 86, height - 35, Math.min(80, w / 4), 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(x + w - 72, height - 35, 60, 20).build());
        // A request completed while a file-detail screen was open is discarded;
        // returning here must start a fresh request instead of staying "loading".
        if (listings.isEmpty() && searchDueNanos == 0) {
            requestRevision++;
            fetchPage();
        }
    }

    private int panelWidth() { return Math.min(width - 16, 620); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int listTop() { return 86; }
    private int listBottom() { return height - 42; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT); }

    private void changePage(int amount) {
        if (page + amount < 1 || page + amount > pageCount) return;
        page += amount;
        scroll = 0;
        listings.clear();
        searchDueNanos = 0;
        requestRevision++;
        fetchPage();
    }

    private void fetchPage() {
        loading = true;
        status = "Loading page " + page + "...";
        int revision = requestRevision;
        int requestedPage = page;
        String requestedQuery = query;
        boolean searching = !requestedQuery.isBlank();
        int firstRemotePage = searching ? (requestedPage - 1) * SEARCH_PAGES_PER_VIEW + 1 : requestedPage;
        int lastRemotePage = searching ? Math.min(firstRemotePage + SEARCH_PAGES_PER_VIEW - 1,
                remotePageCount) : firstRemotePage;
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int remotePage = firstRemotePage; remotePage <= lastRemotePage; remotePage++) {
            URI uri = searching
                    ? URI.create("https://gamebanana.com/apiv11/Util/Search/Results?_sModelName=Mod"
                        + "&_sOrder=best_match&_idGameRow=8694&_nPage=" + remotePage
                        + "&_sSearchString=" + java.net.URLEncoder.encode(requestedQuery, StandardCharsets.UTF_8))
                    : URI.create("https://gamebanana.com/apiv11/Mod/Index?_nPage=" + remotePage
                        + "&_nPerpage=30&_aFilters%5BGeneric_Game%5D=8694");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").GET().build();
            requests.add(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).whenComplete((unused, ignored) -> {
            List<Listing> result = new ArrayList<>();
            String failure = null;
            int availableRemotePages = 1;
            Set<String> seen = new HashSet<>();
            for (CompletableFuture<HttpResponse<String>> pending : requests) {
                try {
                    HttpResponse<String> response = pending.join();
                    if (response.statusCode() != 200) {
                        failure = "GameBanana returned HTTP " + response.statusCode();
                        continue;
                    }
                    SearchPage parsed = parsePage(response.body());
                    availableRemotePages = Math.max(availableRemotePages, parsed.pageCount());
                    for (Listing item : parsed.listings()) if (seen.add(item.id())) result.add(item);
                } catch (Exception exception) {
                    failure = "Could not read GameBanana results. Try again later.";
                }
            }
            if (searching) rankListings(result, requestedQuery);
            int finalRemotePages = availableRemotePages;
            int finalPages = searching
                    ? Math.max(1, (availableRemotePages + SEARCH_PAGES_PER_VIEW - 1) / SEARCH_PAGES_PER_VIEW)
                    : availableRemotePages;
            String completedStatus = failure == null ? (result.isEmpty() ? "No matching FNF mods." :
                    (requestedQuery.isBlank() ? "FNF mods" : "Results for " + requestedQuery)
                            + " · page " + requestedPage + "/" + finalPages)
                    : result.isEmpty() ? failure : "Partial results · page " + requestedPage + "/" + finalPages;
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen != this || revision != requestRevision) return;
                listings.clear();
                listings.addAll(result);
                pageCount = finalPages;
                remotePageCount = finalRemotePages;
                status = completedStatus;
                loading = false;
            });
        });
    }

    private static SearchPage parsePage(String body) {
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        JsonObject metadata = data.has("_aMetadata") && data.get("_aMetadata").isJsonObject()
                ? data.getAsJsonObject("_aMetadata") : new JsonObject();
        int count = metadata.has("_nRecordCount") ? metadata.get("_nRecordCount").getAsInt() : 0;
        int perPage = metadata.has("_nPerpage") ? metadata.get("_nPerpage").getAsInt() : 30;
        int pages = Math.max(1, (count + Math.max(1, perPage) - 1) / Math.max(1, perPage));
        List<Listing> result = new ArrayList<>();
        JsonArray records = data.getAsJsonArray("_aRecords");
        if (records != null) for (JsonElement entry : records) {
            if (!entry.isJsonObject()) continue;
            JsonObject item = entry.getAsJsonObject();
            String id = value(item, "_idRow");
            String name = value(item, "_sName");
            String url = value(item, "_sProfileUrl");
            if (item.has("_aGame") && item.get("_aGame").isJsonObject()
                    && !"8694".equals(value(item.getAsJsonObject("_aGame"), "_idRow"))) continue;
            if (id.isEmpty() || name.isBlank() || GameBananaModLinks.modId(url).isEmpty()) continue;
            result.add(new Listing(id, name, url, classify(item)));
        }
        return new SearchPage(result, pages);
    }

    /** Stable sort keeps GameBanana's ranking when two results match the title equally well. */
    static void rankListings(List<Listing> items, String query) {
        String needle = searchWords(query);
        if (needle.isBlank()) return;
        items.sort(Comparator.comparingInt((Listing item) -> titleScore(item.name(), needle)).reversed());
    }

    private static int titleScore(String title, String needle) {
        String normalized = searchWords(title);
        if (normalized.equals(needle)) return 1000;
        if (normalized.startsWith(needle + " ")) return 800;
        if (normalized.contains(" " + needle + " ") || normalized.endsWith(" " + needle)) return 650;
        String[] terms = needle.split(" ");
        int matches = 0;
        for (String term : terms) {
            if (normalized.equals(term) || normalized.startsWith(term + " ")
                    || normalized.contains(" " + term + " ") || normalized.endsWith(" " + term)) {
                matches++;
            }
        }
        if (matches == terms.length) return 450 + matches * 10;
        return matches * 100;
    }

    private static String searchWords(String value) {
        String plain = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").strip().replaceAll(" +", " ");
    }

    private static String value(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
        catch (Exception ignored) { return ""; }
    }

    static String classify(JsonObject item) {
        StringBuilder evidence = new StringBuilder(value(item, "_sName"));
        for (String category : new String[]{"_aRootCategory", "_aSubCategory"}) {
            if (item.has(category) && item.get(category).isJsonObject())
                evidence.append(' ').append(value(item.getAsJsonObject(category), "_sName"));
        }
        if (item.has("_aTags") && item.get("_aTags").isJsonArray()) {
            for (JsonElement tag : item.getAsJsonArray("_aTags")) {
                if (tag.isJsonPrimitive()) evidence.append(' ').append(tag.getAsString());
            }
        }
        String lower = evidence.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("p-slice") || lower.contains("pslice") || lower.contains("p slice")) return "P-Slice";
        if (lower.contains("psych")) return "Psych";
        if (lower.contains("v-slice") || lower.contains("vslice") || lower.contains("v slice")) return "V-Slice";
        if (lower.contains("codename")) return "Codename";
        return "Unknown";
    }

    private static int engineColor(String engine) {
        return switch (engine) {
            case "Psych", "P-Slice" -> 0xFF5BDE7A;
            case "V-Slice" -> 0xFFE5C951;
            case "Codename" -> 0xFFEE6969;
            default -> 0xFF9992A1;
        };
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        if (searchDueNanos != 0 && System.nanoTime() >= searchDueNanos) {
            searchDueNanos = 0;
            fetchPage();
        }
        BlockifiedScreenStyle.backdrop(gui, width, height);
        int x = panelX(), w = panelWidth();
        BlockifiedScreenStyle.panel(gui, x, 8, w, height - 16);
        gui.drawCenteredString(font, "GAMEBANANA · FRIDAY NIGHT FUNKIN'", width / 2, 18,
                BlockifiedScreenStyle.ACCENT);
        gui.drawString(font, font.plainSubstrByWidth(status, w - 24), x + 12, 70,
                BlockifiedScreenStyle.TEXT_MUTED, false);
        BlockifiedScreenStyle.inner(gui, x + 10, listTop() - 2, w - 20, listBottom() - listTop() + 4);
        List<Listing> visible = listings;
        scroll = Mth.clamp(scroll, 0, Math.max(0, visible.size() - visibleRows()));
        gui.enableScissor(x + 12, listTop(), x + w - 12, listBottom());
        for (int i = scroll; i < visible.size() && i < scroll + visibleRows() + 1; i++) {
            Listing item = visible.get(i);
            int y = listTop() + (i - scroll) * ROW_HEIGHT;
            boolean hover = mouseX >= x + 14 && mouseX < x + w - 14
                    && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            gui.fill(x + 14, y, x + w - 14, y + ROW_HEIGHT - 2,
                    hover ? BlockifiedScreenStyle.ACCENT_DEEP : BlockifiedScreenStyle.PANEL_SOFT);
            gui.fill(x + 14, y, x + 17, y + ROW_HEIGHT - 2, engineColor(item.engine()));
            String name = font.plainSubstrByWidth(item.name(), Math.max(30, w - 180));
            gui.drawString(font, name, x + 22, y + 5, BlockifiedScreenStyle.TEXT, false);
            gui.drawString(font, item.engine(), x + w - 90, y + 5, engineColor(item.engine()), false);
            if (installedIds.contains(item.id())) gui.drawString(font, "Installed", x + 22, y + 16,
                    0xFF5BDE7A, false);
        }
        gui.disableScissor();
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawString(font, "Page " + page + "/" + pageCount + " · click a mod for files",
                x + 174, height - 28, BlockifiedScreenStyle.TEXT_MUTED, false);
    }

    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // render() draws the backdrop first; vanilla blur here would cover the menu.
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= listTop() && mouseY < listBottom()) {
            List<Listing> visible = listings;
            int index = scroll + ((int) mouseY - listTop()) / ROW_HEIGHT;
            if (mouseX >= panelX() + 14 && mouseX < panelX() + panelWidth() - 14
                    && index >= 0 && index < visible.size()) {
                Listing selected = visible.get(index);
                Minecraft.getInstance().setScreen(new GameBananaModFilesScreen(this,
                        selected.id(), selected.name(), selected.url(), installedIds.contains(selected.id())));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listTop() && mouseY < listBottom()) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 2,
                    0, Math.max(0, listings.size() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    void markInstalled(String modId) { installedIds.add(modId); }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
