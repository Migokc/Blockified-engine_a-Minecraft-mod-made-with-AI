package com.fnfmod.client.render;

import com.fnfmod.FnfMod;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

/** Keeps BBS FS's downloaded URL-image cache session-only. */
public final class BbsUrlCacheControl {
    private static final String URL_PACK_CLASS = "mchorse.bbs_mod.resources.packs.URLSourcePack";
    private static volatile Path cacheFolder;
    private static boolean shutdownHookInstalled;

    private BbsUrlCacheControl() {}

    /**
     * Removes anything left by a crash/forced close, then installs the normal
     * game-shutdown cleanup. Called after all mods have initialized their clients.
     */
    public static synchronized void initialize() {
        resolveCacheFolder();
        clean(true);

        if (!shutdownHookInstalled && cacheFolder != null) {
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> deleteCacheFolder(false),
                    "Blockified URL cache cleanup"));
        }
    }

    /** Clears both BBS's live index and its files when a world is closed. */
    public static synchronized void cleanAfterWorld() {
        resolveCacheFolder();
        clean(true);
    }

    private static void clean(boolean recreate) {
        clearBbsRepositoryIndex();
        deleteCacheFolder(recreate);
    }

    private static void resolveCacheFolder() {
        try {
            Class<?> bbsMod = Class.forName("mchorse.bbs_mod.BBSMod");
            Method getSettingsFolder = bbsMod.getMethod("getSettingsFolder");
            Object value = getSettingsFolder.invoke(null);

            if (value instanceof File settings && settings.getParentFile() != null) {
                Path resolved = settings.getParentFile().toPath().resolve("url_cache")
                        .toAbsolutePath().normalize();
                if (isExactCacheFolder(resolved)) cacheFolder = resolved;
            }
        } catch (Throwable error) {
            FnfMod.LOGGER.debug("BBS URL cache path is not available yet: {}", error.toString());
        }
    }

    /**
     * BBS keeps URL-to-file entries after a file is deleted. Clear the shared
     * repository used by both the http and https source packs so a later world
     * redownloads its textures instead of trying to open vanished files.
     */
    private static void clearBbsRepositoryIndex() {
        try {
            Class<?> bbsMod = Class.forName("mchorse.bbs_mod.BBSMod");
            Object provider = bbsMod.getMethod("getProvider").invoke(null);
            if (provider == null) return;

            Field sourcePacksField = findField(provider.getClass(), "sourcePacks");
            if (sourcePacksField == null) return;
            sourcePacksField.setAccessible(true);
            Object sourcePacksValue = sourcePacksField.get(provider);
            if (!(sourcePacksValue instanceof Map<?, ?> sourcePacks)) return;

            for (Object packListValue : sourcePacks.values()) {
                if (!(packListValue instanceof List<?> packs)) continue;
                for (Object pack : packs) {
                    if (pack == null || !URL_PACK_CLASS.equals(pack.getClass().getName())) continue;
                    Field repositoryField = findField(pack.getClass(), "repository");
                    if (repositoryField == null) continue;
                    repositoryField.setAccessible(true);
                    Object repository = repositoryField.get(pack);
                    if (repository == null) continue;

                    Object index = repository.getClass().getMethod("getCache").invoke(repository);
                    if (index instanceof Map<?, ?> cache) cache.clear();
                }
            }
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not clear BBS's URL cache index: {}", error.toString());
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static void deleteCacheFolder(boolean recreate) {
        Path target = cacheFolder;
        if (!isExactCacheFolder(target)) return;

        try {
            if (Files.exists(target)) {
                Files.walkFileTree(target, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            if (recreate) Files.createDirectories(target);
        } catch (IOException error) {
            FnfMod.LOGGER.warn("Could not clean BBS URL cache {}: {}", target, error.toString());
        }
    }

    private static boolean isExactCacheFolder(Path path) {
        return path != null && path.getFileName() != null
                && "url_cache".equals(path.getFileName().toString());
    }
}
