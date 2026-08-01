package com.fnfmod.client.anim;

import com.fnfmod.FnfMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reflection boundary between native NeoForge/Mojmap code and BBS FS running
 * through Sinytra Connector. Keeping every BBS reference in this class avoids
 * compiling Blockified against Fabric/Yarn classes and makes future BBS API
 * adjustments local to one adapter.
 */
final class BbsFsAnimationBridge {

    private record OriginalForm(Object form) {}

    private static final Map<UUID, OriginalForm> ORIGINAL_FORMS = new HashMap<>();
    private static final Map<UUID, String> APPLIED_FORMS = new HashMap<>();
    private static final Map<String, Object> FORM_CACHE = new LinkedHashMap<>();
    private static final Map<String, String> FORM_LABELS = new LinkedHashMap<>();

    private static boolean initialized;
    private static boolean available;
    private static boolean warned;

    private static Class<?> formClass;
    private static Method morphGet;
    private static Method morphGetForm;
    private static Method morphSetForm;
    private static Method formCopy;
    private static Method formPlayState;
    private static Field formStates;
    private static Method statesGetById;
    private static Method formGetId;
    private static Method formGetIdOrName;
    private static Method formGetDisplayName;
    private static Method getFormCategories;
    private static Method getAllCategories;
    private static Method categoryGetForms;
    private static Method sendPlayerForm;
    private static Method sendFormTrigger;

    // Bundled-asset support: register animation folders as BBS asset packs and
    // (de)serialize forms so a character can travel with its own model + texture.
    private static Method getProvider;
    private static Method providerRegister;
    private static Method providerGetFile;
    private static Method providerGetLinks;
    private static java.lang.reflect.Constructor<?> externalPackCtor;
    private static Method packProvidesFiles;
    private static Method linkCreate;
    private static Method linkAssets;
    private static Method dataToString;
    private static Method dataFromString;
    private static Method formToData;
    private static Method formFromData;
    private static Field linkPathField;
    private static final java.util.Set<String> REGISTERED_PACKS = new java.util.HashSet<>();
    private static final Map<String, Object> BUNDLED_FORMS = new HashMap<>();
    private static boolean bundlingAvailable;

    private BbsFsAnimationBridge() {}

    static synchronized boolean init() {
        if (initialized) return available;
        initialized = true;

        try {
            Class<?> morphClass = Class.forName("mchorse.bbs_mod.morphing.Morph");
            formClass = Class.forName("mchorse.bbs_mod.forms.forms.Form");
            Class<?> formUtilsClass = Class.forName("mchorse.bbs_mod.forms.FormUtils");
            Class<?> animationStatesClass = Class.forName("mchorse.bbs_mod.forms.states.AnimationStates");
            Class<?> bbsClientClass = Class.forName("mchorse.bbs_mod.BBSModClient");
            Class<?> categoriesClass = Class.forName("mchorse.bbs_mod.forms.FormCategories");
            Class<?> categoryClass = Class.forName("mchorse.bbs_mod.forms.categories.FormCategory");
            Class<?> clientNetworkClass = Class.forName("mchorse.bbs_mod.network.ClientNetwork");

            morphGet = findMethod(morphClass, "getMorph", true, 1, Entity.class);
            morphGetForm = findMethod(morphClass, "getForm", false, 0);
            morphSetForm = findMethod(morphClass, "setForm", false, 1, formClass);
            formCopy = findMethod(formUtilsClass, "copy", true, 1, formClass);
            formPlayState = findMethod(formClass, "playState", false, 1, String.class);
            formStates = formClass.getField("states");
            statesGetById = findMethod(animationStatesClass, "getById", false, 1, String.class);
            formGetId = findOptionalMethod(formClass, "getFormId", false, 0);
            formGetIdOrName = findOptionalMethod(formClass, "getFormIdOrName", false, 0);
            formGetDisplayName = findOptionalMethod(formClass, "getDisplayName", false, 0);
            getFormCategories = findMethod(bbsClientClass, "getFormCategories", true, 0);
            getAllCategories = findMethod(categoriesClass, "getAllCategories", false, 0);
            categoryGetForms = findMethod(categoryClass, "getForms", false, 0);
            sendPlayerForm = findMethod(clientNetworkClass, "sendPlayerForm", true, 1, formClass);
            sendFormTrigger = findMethod(clientNetworkClass, "sendFormTrigger", true, 2,
                    String.class, int.class);


            available = true;
            initBundling();
            refreshForms();
            FnfMod.LOGGER.info("BBS FS animation bridge ready ({} selectable forms)", FORM_LABELS.size());
        } catch (Throwable error) {
            available = false;
            FnfMod.LOGGER.error("BBS FS is unavailable; character animation playback is disabled. "
                    + "Install BBS FS, Sinytra Connector and Forgified Fabric API for Minecraft 1.21.1.", error);
        }

        return available;
    }

    static synchronized boolean isAvailable() {
        return initialized ? available : init();
    }

    static synchronized List<String> listForms() {
        if (!isAvailable()) return List.of();
        refreshForms();
        return FORM_LABELS.values().stream()
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    static synchronized boolean hasForm(String name) {
        if (!isAvailable() || name == null || name.isBlank()) return false;
        refreshForms();
        return FORM_CACHE.containsKey(key(name));
    }

    static synchronized boolean play(Player player, String requestedForm, String state) {
        return play(player, requestedForm, null, state);
    }

    /**
     * Applies the character's form without playing a state. Called while the song
     * counts in so the morph (and any bundled model) is already resolved before
     * the first animation, instead of the vanilla player showing for a moment.
     */
    static synchronized boolean prepare(Player player, String requestedForm,
                                        java.nio.file.Path bundledForm) {
        if (!isAvailable() || player == null) return false;
        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return false;

            Object bundled = bundledForm == null ? null : loadBundledForm(bundledForm);
            if (bundled != null) {
                applyForm(player, morph, bundledKey(bundledForm), bundled);
                return true;
            }
            if (requestedForm != null && !requestedForm.isBlank()) {
                applyForm(player, morph, key(requestedForm), FORM_CACHE.get(key(requestedForm)));
                return true;
            }
        } catch (Throwable error) {
            warnOnce("Failed to preload a BBS FS form", error);
        }
        return false;
    }

    static synchronized boolean play(Player player, String requestedForm,
                                     java.nio.file.Path bundledForm, String state) {
        if (!isAvailable() || player == null || state == null || state.isBlank()) return false;

        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return false;

            Object bundled = bundledForm == null ? null : loadBundledForm(bundledForm);
            if (bundled != null) {
                applyForm(player, morph, bundledKey(bundledForm), bundled);
            } else if (requestedForm != null && !requestedForm.isBlank()) {
                applyForm(player, morph, key(requestedForm), FORM_CACHE.get(key(requestedForm)));
            }

            Object form = morphGetForm.invoke(morph);
            if (form == null) return false;

            Object states = formStates.get(form);
            if (states == null || statesGetById.invoke(states, state) == null) return false;

            formPlayState.invoke(form, state);
            if (isLocalPlayer(player)) {
                // BBS's trigger packet is intentionally not echoed back to its sender;
                // play locally first, then synchronize it to tracking clients.
                sendFormTrigger.invoke(null, state, 0);
            }
            return true;
        } catch (Throwable error) {
            warnOnce("Failed to play a BBS FS animation state", error);
            return false;
        }
    }


    static synchronized void restoreAll() {
        if (!isAvailable() || ORIGINAL_FORMS.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ORIGINAL_FORMS.clear();
            APPLIED_FORMS.clear();
            return;
        }

        for (Map.Entry<UUID, OriginalForm> entry : new ArrayList<>(ORIGINAL_FORMS.entrySet())) {
            Player player = minecraft.level.getPlayerByUUID(entry.getKey());
            if (player == null) continue;

            try {
                Object morph = morphGet.invoke(null, player);
                if (morph == null) continue;
                Object original = entry.getValue().form();
                Object restored = original == null ? null : formCopy.invoke(null, original);
                morphSetForm.invoke(morph, restored);
                if (isLocalPlayer(player)) sendPlayerForm.invoke(null, restored);
            } catch (Throwable error) {
                warnOnce("Failed to restore a BBS FS form after gameplay", error);
            }
        }

        ORIGINAL_FORMS.clear();
        APPLIED_FORMS.clear();
    }

    /** Restores one performer without disturbing the other song characters. */
    static synchronized void restore(Player player) {
        if (!isAvailable() || player == null) return;
        UUID id = player.getUUID();
        OriginalForm saved = ORIGINAL_FORMS.remove(id);
        APPLIED_FORMS.remove(id);
        if (saved == null) return;

        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return;
            Object original = saved.form();
            Object restored = original == null ? null : formCopy.invoke(null, original);
            morphSetForm.invoke(morph, restored);
            if (isLocalPlayer(player)) sendPlayerForm.invoke(null, restored);
        } catch (Throwable error) {
            warnOnce("Failed to restore one BBS FS performer", error);
        }
    }

    private static void applyForm(Player player, Object morph, String requestedKey, Object template)
            throws Exception {
        if (requestedKey == null || requestedKey.equals(APPLIED_FORMS.get(player.getUUID()))) return;
        if (template == null) {
            refreshForms();
            template = FORM_CACHE.get(requestedKey);
        }
        if (template == null) return;

        if (!ORIGINAL_FORMS.containsKey(player.getUUID())) {
            Object current = morphGetForm.invoke(morph);
            Object original = current == null ? null : formCopy.invoke(null, current);
            ORIGINAL_FORMS.put(player.getUUID(), new OriginalForm(original));
        }

        Object copy = formCopy.invoke(null, template);
        morphSetForm.invoke(morph, copy);
        APPLIED_FORMS.put(player.getUUID(), requestedKey);
        if (isLocalPlayer(player)) sendPlayerForm.invoke(null, copy);
    }

    private static void refreshForms() {
        if (!available) return;

        try {
            Object categories = getFormCategories.invoke(null);
            if (categories == null) return;
            Object all = getAllCategories.invoke(categories);
            if (!(all instanceof Iterable<?> iterable)) return;

            FORM_CACHE.clear();
            FORM_LABELS.clear();
            for (Object category : iterable) {
                Object forms = categoryGetForms.invoke(category);
                if (!(forms instanceof Iterable<?> formIterable)) continue;
                for (Object form : formIterable) registerForm(form);
            }
        } catch (Throwable error) {
            warnOnce("Failed to enumerate BBS FS forms", error);
        }
    }

    private static void registerForm(Object form) {
        if (form == null || !formClass.isInstance(form)) return;

        List<String> names = new ArrayList<>();
        addName(names, invokeString(formGetIdOrName, form));
        addName(names, invokeString(formGetDisplayName, form));
        addName(names, invokeString(formGetId, form));
        if (names.isEmpty()) return;

        String label = names.get(0);
        for (String name : names) {
            FORM_CACHE.putIfAbsent(key(name), form);
            FORM_LABELS.putIfAbsent(key(name), label);
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
            if (slash >= 0 && slash + 1 < name.length()) {
                FORM_CACHE.putIfAbsent(key(name.substring(slash + 1)), form);
                FORM_LABELS.putIfAbsent(key(name.substring(slash + 1)), label);
            }
        }
    }

    private static void addName(List<String> names, String name) {
        if (name != null && !name.isBlank() && !names.contains(name)) names.add(name.trim());
    }

    private static String invokeString(Method method, Object owner) {
        if (method == null) return null;
        try {
            Object value = method.invoke(owner);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- bundling

    private static void initBundling() {
        try {
            Class<?> bbsMod = Class.forName("mchorse.bbs_mod.BBSMod");
            Class<?> providerClass = Class.forName("mchorse.bbs_mod.resources.AssetProvider");
            Class<?> packClass = Class.forName("mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack");
            Class<?> sourcePackClass = Class.forName("mchorse.bbs_mod.resources.ISourcePack");
            Class<?> linkClass = Class.forName("mchorse.bbs_mod.resources.Link");
            Class<?> dataToStringClass = Class.forName("mchorse.bbs_mod.data.DataToString");
            Class<?> baseTypeClass = Class.forName("mchorse.bbs_mod.data.types.BaseType");
            Class<?> formUtilsClass = Class.forName("mchorse.bbs_mod.forms.FormUtils");

            getProvider = findMethod(bbsMod, "getProvider", true, 0);
            providerRegister = providerClass.getMethod("register", sourcePackClass);
            providerGetFile = providerClass.getMethod("getFile", linkClass);
            providerGetLinks = providerClass.getMethod("getLinksFromPath", linkClass, boolean.class);
            externalPackCtor = packClass.getConstructor(String.class, java.io.File.class);
            packProvidesFiles = packClass.getMethod("providesFiles");
            linkCreate = linkClass.getMethod("create", String.class);
            linkAssets = linkClass.getMethod("assets", String.class);
            linkPathField = linkClass.getField("path");
            dataToString = dataToStringClass.getMethod("toString", baseTypeClass, boolean.class);
            dataFromString = dataToStringClass.getMethod("fromString", String.class);
            formToData = formUtilsClass.getMethod("toData", formClass);
            formFromData = formUtilsClass.getMethod("fromData", baseTypeClass);
            bundlingAvailable = true;
        } catch (Throwable error) {
            bundlingAvailable = false;
            FnfMod.LOGGER.warn("BBS FS asset bundling unavailable ({}); bundled characters will not load.",
                    error.toString());
        }
    }

    /** Adds an animation folder to BBS so its models/ and textures/ resolve. */
    static synchronized void registerAssetPack(java.nio.file.Path folder) {
        if (!bundlingAvailable || folder == null) return;
        try {
            java.io.File dir = folder.toFile();
            if (!dir.isDirectory()) return;
            String canonical = dir.getCanonicalPath();
            if (!REGISTERED_PACKS.add(canonical)) return;
            Object provider = getProvider.invoke(null);
            Object pack = externalPackCtor.newInstance("assets", dir);
            packProvidesFiles.invoke(pack);
            providerRegister.invoke(provider, pack);
            FnfMod.LOGGER.info("Registered bundled BBS asset folder {}", canonical);
        } catch (Throwable error) {
            warnOnce("Failed to register a bundled BBS asset folder", error);
        }
    }

    static synchronized boolean hasBundledForm(java.nio.file.Path formJson) {
        return bundlingAvailable && loadBundledForm(formJson) != null;
    }

    static synchronized void clearBundledForms() {
        BUNDLED_FORMS.clear();
    }

    private static Object loadBundledForm(java.nio.file.Path formJson) {
        if (!bundlingAvailable || formJson == null) return null;
        String key = bundledKey(formJson);
        if (BUNDLED_FORMS.containsKey(key)) return BUNDLED_FORMS.get(key);

        Object form = null;
        try {
            if (java.nio.file.Files.isRegularFile(formJson)) {
                registerAssetPack(formJson.getParent());
                Object data = dataFromString.invoke(null, java.nio.file.Files.readString(formJson));
                if (data != null) form = formFromData.invoke(null, data);
            }
        } catch (Throwable error) {
            warnOnce("Failed to load a bundled BBS form", error);
        }
        BUNDLED_FORMS.put(key, form);
        return form;
    }

    private static String bundledKey(java.nio.file.Path formJson) {
        return "file:" + formJson.toAbsolutePath().normalize();
    }

    /**
     * Serializes a BBS-registered form to {@code <baseName>.form.json} in the
     * target folder and copies its models/ + textures/ so the character becomes
     * self-contained and shareable. Returns false if BBS or the form is missing.
     */
    static synchronized boolean exportForm(String formName, java.nio.file.Path targetFolder,
                                           String baseName) {
        if (!isAvailable() || !bundlingAvailable || targetFolder == null) return false;
        refreshForms();
        Object template = formName == null ? null : FORM_CACHE.get(key(formName));
        if (template == null) return false;

        try {
            Object data = formToData.invoke(null, template);
            if (data == null) return false;
            String json = String.valueOf(dataToString.invoke(null, data, true));
            java.nio.file.Files.createDirectories(targetFolder);
            java.nio.file.Path formFile = targetFolder.resolve(baseName + ".form.json");
            java.nio.file.Files.writeString(formFile, json);
            copyReferencedAssets(json, targetFolder);
            BUNDLED_FORMS.remove(bundledKey(formFile));
            registerAssetPack(targetFolder);
            return true;
        } catch (Throwable error) {
            warnOnce("Failed to export a BBS form bundle", error);
            return false;
        }
    }

    private static void copyReferencedAssets(String json, java.nio.file.Path targetFolder) {
        java.util.LinkedHashSet<String> links = new java.util.LinkedHashSet<>();
        // Every texture reference — the base texture, per-material textures, and
        // per-material animation KEYFRAMES — serializes as an "assets:<path>"
        // string, so scanning the whole form (not just the texture field) copies
        // keyframe-swapped textures too. Stop only at real delimiters so unusual
        // filenames inside keyframes are not truncated.
        java.util.regex.Matcher assets = java.util.regex.Pattern
                .compile("assets:[^\"\\s,}\\]\\[]+").matcher(json);
        while (assets.find()) links.add(assets.group());
        java.util.regex.Matcher models = java.util.regex.Pattern
                .compile("\"model\"\\s*:\\s*\"([A-Za-z0-9_./\\-]+)\"").matcher(json);
        while (models.find()) links.add("assets:models/" + models.group(1));

        for (String link : links) copyLinkTree(link, targetFolder);
    }

    private static void copyLinkTree(String linkString, java.nio.file.Path targetFolder) {
        try {
            Object provider = getProvider.invoke(null);
            Object link = linkCreate.invoke(null, linkString);
            boolean copiedAny = false;
            try {
                Object children = providerGetLinks.invoke(provider, link, true);
                if (children instanceof java.util.Collection<?> collection) {
                    for (Object child : collection) copiedAny |= copyOneLink(provider, child, targetFolder);
                }
            } catch (Throwable ignored) {
                // A single-file link has no children; fall through to copy it directly.
            }
            if (!copiedAny) copyOneLink(provider, link, targetFolder);
        } catch (Throwable error) {
            warnOnce("Failed to copy a bundled BBS asset", error);
        }
    }

    private static boolean copyOneLink(Object provider, Object link, java.nio.file.Path targetFolder) {
        try {
            java.io.File file = (java.io.File) providerGetFile.invoke(provider, link);
            if (file == null || !file.isFile()) return false;
            String relative = String.valueOf(linkPathField.get(link));
            java.nio.file.Path dest = targetFolder.resolve(relative);
            if (dest.getParent() != null) java.nio.file.Files.createDirectories(dest.getParent());
            java.nio.file.Files.copy(file.toPath(), dest,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLocalPlayer(Player player) {
        return Minecraft.getInstance().player == player;
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('\\', '/');
    }

    private static Method findMethod(Class<?> owner, String name, boolean requireStatic,
                                     int parameterCount, Class<?>... preferredTypes) throws NoSuchMethodException {
        Method method = findOptionalMethod(owner, name, requireStatic, parameterCount, preferredTypes);
        if (method == null) throw new NoSuchMethodException(owner.getName() + "#" + name);
        return method;
    }

    private static Method findOptionalMethod(Class<?> owner, String name, boolean requireStatic,
                                             int parameterCount, Class<?>... preferredTypes) {
        if (preferredTypes.length == parameterCount) {
            try {
                Method exact = owner.getMethod(name, preferredTypes);
                if (!requireStatic || Modifier.isStatic(exact.getModifiers())) return exact;
            } catch (ReflectiveOperationException ignored) {}
        }

        return java.util.Arrays.stream(owner.getMethods())
                .filter(method -> method.getName().equals(name))
                .filter(method -> method.getParameterCount() == parameterCount)
                .filter(method -> !requireStatic || Modifier.isStatic(method.getModifiers()))
                .sorted(Comparator.comparing(Method::toGenericString))
                .findFirst()
                .orElse(null);
    }


    private static void warnOnce(String message, Throwable error) {
        if (warned) return;
        warned = true;
        FnfMod.LOGGER.warn("{}: {}", message, error.toString());
    }
}
