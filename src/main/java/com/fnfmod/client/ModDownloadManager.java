package com.fnfmod.client;

import com.fnfmod.client.gui.ModDownloadProgressScreen;
import com.fnfmod.song.GameBananaInstaller;
import com.fnfmod.song.ModDownloadLinks;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One bounded, cancellable mod download at a time; no network/archive IO on the render thread. */
public final class ModDownloadManager {
    private static final long RESULT_OVERLAY_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(3);

    public static final class Job implements GameBananaInstaller.Progress {
        private final String name;
        private volatile String phase = "Queued";
        private volatile long done, total = -1;
        private volatile boolean paused, cancelled, finished, started;
        private volatile long finishedAtNanos;
        private volatile Path installed;
        private volatile String error = "";
        private volatile Future<?> future;
        private volatile Runnable afterInstall;

        private Job(String name) { this.name = name; }
        public String name() { return name; }
        public String phase() { return phase; }
        public long done() { return done; }
        public long total() { return total; }
        public boolean paused() { return paused; }
        public boolean finished() { return finished; }
        public boolean cancelled() { return cancelled; }
        public Path installed() { return installed; }
        public String error() { return error; }

        @Override public void update(String phase, long done, long total) throws IOException {
            this.phase = phase;
            this.done = Math.max(0, done);
            this.total = total;
            checkActive();
        }

        @Override public void checkActive() throws IOException {
            synchronized (this) {
                while (paused && !cancelled) {
                    try { wait(250); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download cancelled", interrupted);
                    }
                }
            }
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new IOException("Download cancelled");
        }

        public synchronized void pause() {
            if (!finished && !cancelled) paused = true;
        }
        public synchronized void resume() {
            paused = false;
            notifyAll();
        }
        public synchronized void cancel() {
            if (finished || installed != null) return;
            cancelled = true;
            paused = false;
            notifyAll();
            if (future != null) future.cancel(true);
            if (!started) {
                phase = "Cancelled";
                error = "Cancelled";
                finishedAtNanos = System.nanoTime();
                finished = true;
            }
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Blockified mod download");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Job current;
    private static volatile boolean collapsed;

    private ModDownloadManager() {}

    public static Job current() { return current; }

    public static synchronized Job startGameBanana(String modId, String name,
                                                    GameBananaInstaller.FileOption file,
                                                    Runnable afterInstall) {
        return start(name, job -> GameBananaInstaller.install(modId, name, file, job), afterInstall);
    }

    public static synchronized Job startExternal(ModDownloadLinks.Link link, String name,
                                                 Runnable afterInstall) {
        return start(name, job -> GameBananaInstaller.installExternal(link, name, job), afterInstall);
    }

    private interface Installer { Path run(Job job) throws Exception; }

    private static Job start(String name, Installer installer, Runnable afterInstall) {
        if (current != null && !current.finished) return null;
        Job job = new Job(name);
        job.afterInstall = afterInstall;
        current = job;
        collapsed = false;
        job.future = WORKER.submit(() -> {
            job.started = true;
            try {
                job.checkActive();
                job.installed = installer.run(job);
                job.phase = "Indexing songs";
                job.done = 0;
                job.total = -1;
                SongLibrary.rescan();
                job.phase = "Installed";
            } catch (Exception error) {
                job.error = job.cancelled ? "Cancelled" : friendlyError(error);
                job.phase = job.cancelled ? "Cancelled" : "Failed";
            } finally {
                job.finishedAtNanos = System.nanoTime();
                job.finished = true;
                Minecraft.getInstance().execute(() -> {
                    if (job.installed != null && !job.cancelled && job.afterInstall != null)
                        job.afterInstall.run();
                });
            }
        });
        return job;
    }

    private static String friendlyError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    public static void showDetails(Screen returnTo) {
        Job job = current;
        if (job == null) return;
        collapsed = false;
        Minecraft.getInstance().setScreen(new ModDownloadProgressScreen(returnTo));
    }

    public static void collapse() { collapsed = true; }

    public static void renderOverlay(GuiGraphics gui) {
        Job job = current;
        if (!collapsed || job == null) return;
        if (job.finished && System.nanoTime() - job.finishedAtNanos >= RESULT_OVERLAY_NANOS) return;
        int width = gui.guiWidth();
        int x = Math.max(2, width - 178), y = 6;
        gui.fill(x, y, x + 170, y + 34, 0xD81D1128);
        gui.fill(x, y, x + 3, y + 34, 0xFFFF702E);
        String title = job.finished ? job.phase : job.paused ? "Paused" : job.phase;
        gui.drawString(Minecraft.getInstance().font,
                Minecraft.getInstance().font.plainSubstrByWidth(title, 154),
                x + 7, y + 4, 0xFFFFFFFF, false);
        gui.drawString(Minecraft.getInstance().font, "/fnf download details",
                x + 7, y + 14, 0xFFBFB1CA, false);
        float ratio = job.total > 0 ? Math.min(1f, job.done / (float) job.total) : 0f;
        gui.fill(x + 7, y + 23, x + 163, y + 27, 0xFF52415B);
        gui.fill(x + 7, y + 23, x + 7 + (int) (156 * ratio), y + 27, 0xFFFF702E);
    }

    public static int command(String action) {
        Job job = current;
        Minecraft mc = Minecraft.getInstance();
        if (job == null) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("No mod download is active."), false);
            return 0;
        }
        switch (action) {
            case "pause" -> job.pause();
            case "resume" -> job.resume();
            case "cancel" -> job.cancel();
            case "details" -> { showDetails(mc.screen); return 1; }
            default -> { return 0; }
        }
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("Download: " + action), false);
        return 1;
    }
}
