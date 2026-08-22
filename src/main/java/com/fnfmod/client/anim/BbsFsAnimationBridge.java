package com.fnfmod.client.anim;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection boundary between native NeoForge/Mojmap code and BBS FS running
 * through Sinytra Connector. Keeping every BBS reference in this class avoids
 * compiling Blockified against Fabric/Yarn classes and makes future BBS API
 * adjustments local to one adapter.
 */
final class BbsFsAnimationBridge {

    private record OriginalForm(Object form) {}

    /** Texture/model override for one of BBS's built-in Steve/Alex form families. */
    record SkinSource(Player player, java.nio.file.Path file, boolean slim, String account) {
        static SkinSource player(Player player) {
            return player == null ? null : new SkinSource(player, null, false, "");
        }

        static SkinSource file(java.nio.file.Path file, boolean slim) {
            return file == null ? null : new SkinSource(null, file.toAbsolutePath().normalize(), slim, "");
        }

        static SkinSource account(String account) {
            String clean = account == null ? "" : account.trim();
            return clean.isBlank() ? null : new SkinSource(null, null, false, clean);
        }
    }

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
    // BBS Form.lighting (a ValueFloat, 1 = world-lit, 0 = full-bright); set through
    // its one-argument set method. Lets a performer render unlit/flat.
    private static Field formLightingField;
    private static Method lightingValueSet;
    /** Players forced to a non-default form lighting, re-applied when their form changes. */
    private static final Map<UUID, Float> FORCED_LIGHTING = new HashMap<>();
    private static Method formCopy;
    private static Method formPlayState;
    private static Field formStates;
    private static Method statesGetById;
    private static Method animationStatesGetAllTyped;
    private static Field animationStateProperties;
    private static Field formPropertiesMap;
    private static Method formGetId;
    private static Method formGetIdOrName;
    private static Method formGetDisplayName;
    private static Method getFormCategories;
    private static Method getAllCategories;
    private static Method categoryGetForms;
    private static Method sendPlayerForm;
    private static Method sendFormTrigger;
    // Optional ModelForm access used to substitute live Minecraft skins without
    // introducing a compile-time BBS dependency.
    private static Class<?> modelFormClass;
    private static Field modelFormModelField;
    private static Field modelFormTextureField;
    private static Method modelValueGet;
    private static Method modelValueSet;
    private static Method textureValueSet;

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
    private static Method getModels;
    private static Method modelManagerGetModel;
    private static Method modelManagerLoadModel;
    private static Field modelManagerModelsField;
    // Model-block bundling: read the Form off each placed BBS model block so its
    // models/textures can be copied into a shared world.
    private static Class<?> modelBlockEntityClass;
    private static Method modelBlockGetProperties;
    private static Method modelPropertiesGetForm;
    private static final java.util.Set<String> REGISTERED_PACKS = new java.util.HashSet<>();
    private static final Map<String, String> CUSTOM_SKIN_PACKS = new HashMap<>();
    private static final Map<String, Object> BUNDLED_FORMS = new HashMap<>();
    /** Last copy of each bundled editor form inserted into BBS's Recent category. */
    private static final Map<String, Object> RECENT_IMPORTED_FORMS = new HashMap<>();
    /** Official-account lookups stay in memory; Minecraft owns its normal texture cache. */
    private static final Map<String, CompletableFuture<PlayerSkin>> ACCOUNT_SKINS =
            new ConcurrentHashMap<>();
    /** Model ids referenced by each bundled form, so a cache hit can still ensure they are loaded. */
    private static final Map<String, java.util.List<String>> BUNDLED_MODEL_IDS = new HashMap<>();
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
            Class<?> animationStateClass = Class.forName("mchorse.bbs_mod.forms.states.AnimationState");
            Class<?> formPropertiesClass = Class.forName("mchorse.bbs_mod.film.replays.FormProperties");
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
            animationStatesGetAllTyped = findMethod(animationStatesClass, "getAllTyped", false, 0);
            animationStateProperties = animationStateClass.getField("properties");
            formPropertiesMap = formPropertiesClass.getField("properties");
            formGetId = findOptionalMethod(formClass, "getFormId", false, 0);
            formGetIdOrName = findOptionalMethod(formClass, "getFormIdOrName", false, 0);
            formGetDisplayName = findOptionalMethod(formClass, "getDisplayName", false, 0);
            getFormCategories = findMethod(bbsClientClass, "getFormCategories", true, 0);
            getAllCategories = findMethod(categoriesClass, "getAllCategories", false, 0);
            categoryGetForms = findMethod(categoryClass, "getForms", false, 0);
            sendPlayerForm = findMethod(clientNetworkClass, "sendPlayerForm", true, 1, formClass);
            sendFormTrigger = findMethod(clientNetworkClass, "sendFormTrigger", true, 2,
                    String.class, int.class);

            try {
                modelFormClass = Class.forName("mchorse.bbs_mod.forms.forms.ModelForm");
                Class<?> linkClass = Class.forName("mchorse.bbs_mod.resources.Link");
                modelFormModelField = modelFormClass.getField("model");
                modelFormTextureField = modelFormClass.getField("texture");
                modelValueGet = findMethod(modelFormModelField.getType(), "get", false, 0);
                modelValueSet = findMethod(modelFormModelField.getType(), "set", false, 1, String.class);
                textureValueSet = findMethod(modelFormTextureField.getType(), "set", false, 1, linkClass);
                linkCreate = findMethod(linkClass, "create", true, 1, String.class);
            } catch (Throwable ignored) {
                modelFormClass = null;
                modelFormModelField = null;
                modelFormTextureField = null;
                modelValueGet = modelValueSet = textureValueSet = null;
            }

            // Optional: full-bright control via the form's lighting value.
            try {
                formLightingField = formClass.getField("lighting");
                Method set = findOptionalMethod(formLightingField.getType(), "set", false, 1, float.class);
                if (set == null) set = findOptionalMethod(formLightingField.getType(), "set", false, 1, Float.class);
                if (set == null) set = findOptionalMethod(formLightingField.getType(), "set", false, 1);
                lightingValueSet = set;
            } catch (Throwable ignored) {
                formLightingField = null;
                lightingValueSet = null;
            }

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

    static synchronized boolean hasActiveForm(Player player) {
        if (!isAvailable() || player == null) return false;
        try {
            Object morph = morphGet.invoke(null, player);
            return morph != null && morphGetForm.invoke(morph) != null;
        } catch (Throwable error) {
            return false;
        }
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
        return prepare(player, requestedForm, bundledForm, (SkinSource) null);
    }

    static synchronized boolean prepare(Player player, String requestedForm,
                                        java.nio.file.Path bundledForm, Player skinSource) {
        return prepare(player, requestedForm, bundledForm, SkinSource.player(skinSource));
    }

    static synchronized boolean prepare(Player player, String requestedForm,
                                        java.nio.file.Path bundledForm, SkinSource skinSource) {
        if (!isAvailable() || player == null) return false;
        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return false;

            Object bundled = bundledForm == null ? null : loadBundledForm(bundledForm);
            if (bundled != null) {
                applyForm(player, morph, bundledKey(bundledForm), bundled, skinSource);
                return true;
            }
            if (requestedForm != null && !requestedForm.isBlank()) {
                applyForm(player, morph, key(requestedForm), FORM_CACHE.get(key(requestedForm)), skinSource);
                return true;
            }
        } catch (Throwable error) {
            warnOnce("Failed to preload a BBS FS form", error);
        }
        return false;
    }

    static synchronized boolean play(Player player, String requestedForm,
                                     java.nio.file.Path bundledForm, String state) {
        return play(player, requestedForm, bundledForm, state, (SkinSource) null);
    }

    static synchronized boolean play(Player player, String requestedForm,
                                     java.nio.file.Path bundledForm, String state, Player skinSource) {
        return play(player, requestedForm, bundledForm, state, SkinSource.player(skinSource));
    }

    static synchronized boolean play(Player player, String requestedForm,
                                     java.nio.file.Path bundledForm, String state, SkinSource skinSource) {
        if (!isAvailable() || player == null || state == null || state.isBlank()) return false;

        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return false;

            Object bundled = bundledForm == null ? null : loadBundledForm(bundledForm);
            if (bundled != null) {
                applyForm(player, morph, bundledKey(bundledForm), bundled, skinSource);
            } else if (requestedForm != null && !requestedForm.isBlank()) {
                applyForm(player, morph, key(requestedForm), FORM_CACHE.get(key(requestedForm)), skinSource);
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
            FORCED_LIGHTING.clear();
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
                if (isLocalPlayer(player)) {
                    sendPlayerForm.invoke(null, restored);
                    clearFormRecovery(player);
                }
            } catch (Throwable error) {
                warnOnce("Failed to restore a BBS FS form after gameplay", error);
            }
        }

        ORIGINAL_FORMS.clear();
        APPLIED_FORMS.clear();
        FORCED_LIGHTING.clear();
    }

    /** Restores one performer without disturbing the other song characters. */
    static synchronized void restore(Player player) {
        if (!isAvailable() || player == null) return;
        UUID id = player.getUUID();
        OriginalForm saved = ORIGINAL_FORMS.remove(id);
        APPLIED_FORMS.remove(id);
        FORCED_LIGHTING.remove(id);
        if (saved == null) return;

        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return;
            Object original = saved.form();
            Object restored = original == null ? null : formCopy.invoke(null, original);
            morphSetForm.invoke(morph, restored);
            if (isLocalPlayer(player)) {
                sendPlayerForm.invoke(null, restored);
                clearFormRecovery(player);
            }
        } catch (Throwable error) {
            warnOnce("Failed to restore one BBS FS performer", error);
        }
    }

    private static void applyForm(Player player, Object morph, String requestedKey, Object template,
                                  SkinSource skinSource)
            throws Exception {
        if (template == null) {
            refreshForms();
            template = FORM_CACHE.get(requestedKey);
        }
        if (template == null) return;

        String appliedKey = requestedKey + playerSkinKey(template, skinSource);
        if (requestedKey == null || appliedKey.equals(APPLIED_FORMS.get(player.getUUID()))) return;

        if (!ORIGINAL_FORMS.containsKey(player.getUUID())) {
            Object current = morphGetForm.invoke(morph);
            Object original = current == null ? null : formCopy.invoke(null, current);
            if (isLocalPlayer(player)) writeFormRecovery(player, original);
            ORIGINAL_FORMS.put(player.getUUID(), new OriginalForm(original));
        }

        Object copy = formCopy.invoke(null, template);
        if (applySelectedSkin(copy, skinSource)) suppressSkinReplacingTextureKeyframes(copy);
        morphSetForm.invoke(morph, copy);
        APPLIED_FORMS.put(player.getUUID(), appliedKey);
        // A newly applied form resets to default lighting; re-force it if requested.
        applyLighting(player, copy);
        if (isLocalPlayer(player)) sendPlayerForm.invoke(null, copy);
    }

    /**
     * Restores a local BBS form left in player NBT when Minecraft closed before
     * Blockified's normal song/editor teardown ran. The backup is scoped to the
     * player and current world/server, and is removed only after the restored form
     * has been applied locally and sent back to BBS's server handler.
     */
    static synchronized boolean recoverTemporaryForm(Player player) {
        if (!isAvailable() || player == null || !isLocalPlayer(player)
                || formFromData == null || dataFromString == null) return false;
        Path file = formRecoveryFile(player);
        if (!Files.isRegularFile(file)) return true;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!player.getUUID().toString().equals(json.has("player")
                    ? json.get("player").getAsString() : "")) return true;
            String session = json.has("session") ? json.get("session").getAsString() : "";
            if (!session.equals(formRecoverySession())) return true;

            Object restored = null;
            boolean empty = json.has("empty") && json.get("empty").getAsBoolean();
            if (!empty) {
                String encoded = json.has("form") ? json.get("form").getAsString() : "";
                if (encoded.isBlank()) return true;
                Object data = dataFromString.invoke(null, encoded);
                restored = data == null ? null : formFromData.invoke(null, data);
                if (restored == null) return true;
            }

            Object morph = morphGet.invoke(null, player);
            if (morph == null) return false;
            Object copy = restored == null ? null : formCopy.invoke(null, restored);
            morphSetForm.invoke(morph, copy);
            sendPlayerForm.invoke(null, copy);
            Files.deleteIfExists(file);
            ORIGINAL_FORMS.remove(player.getUUID());
            APPLIED_FORMS.remove(player.getUUID());
            FORCED_LIGHTING.remove(player.getUUID());
            FnfMod.LOGGER.info("Recovered the player's BBS form after an interrupted Blockified session");
            return true;
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not recover the player's BBS form: {}", error.toString());
            return false;
        }
    }

    private static void writeFormRecovery(Player player, Object original) {
        if (player == null || formToData == null || dataToString == null) return;
        Path file = formRecoveryFile(player);
        // The first backup is the real pre-Blockified form. Never replace it with
        // another temporary form if screens/songs transition without cleanup.
        if (Files.isRegularFile(file)) return;
        try {
            JsonObject json = new JsonObject();
            json.addProperty("player", player.getUUID().toString());
            json.addProperty("session", formRecoverySession());
            json.addProperty("empty", original == null);
            if (original != null) {
                Object data = formToData.invoke(null, original);
                if (data == null) return;
                json.addProperty("form", String.valueOf(dataToString.invoke(null, data, true)));
            }
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString());
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not back up the player's BBS form: {}", error.toString());
        }
    }

    private static void clearFormRecovery(Player player) {
        if (player == null) return;
        try {
            Files.deleteIfExists(formRecoveryFile(player));
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not clear the BBS form recovery backup: {}", error.toString());
        }
    }

    private static Path formRecoveryFile(Player player) {
        String identity = player.getUUID() + "|" + formRecoverySession();
        String key = Integer.toUnsignedString(identity.hashCode(), 36);
        return SongLibrary.root().resolve("recovery")
                .resolve("bbs-form-" + player.getUUID() + "-" + key + ".json");
    }

    private static String formRecoverySession() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
                return "world:" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
            }
            Method getter = minecraft.getClass().getMethod("getCurrentServer");
            Object server = getter.invoke(minecraft);
            if (server != null) {
                Field name = server.getClass().getField("name");
                Field ip = server.getClass().getField("ip");
                return "server:" + String.valueOf(name.get(server)) + "|" + String.valueOf(ip.get(server));
            }
        } catch (Throwable ignored) {}
        return "connection:unknown";
    }

    static synchronized boolean supportsPlayerSkin(String requestedForm,
                                                   java.nio.file.Path bundledForm) {
        if (!isAvailable()) return false;
        Object template = bundledForm == null ? null : loadBundledForm(bundledForm);
        if (template == null && requestedForm != null && !requestedForm.isBlank()) {
            refreshForms();
            template = FORM_CACHE.get(key(requestedForm));
        }
        return playerModel(template) != null;
    }

    private static String playerSkinKey(Object template, SkinSource skinSource) {
        if (skinSource == null || playerModel(template) == null) return "";
        try {
            PlayerSkin selected = selectedPlayerSkin(skinSource);
            if (selected != null) {
                PlayerSkin skin = selected;
                return "|skin:" + skin.model().id() + ":" + String.valueOf(skin.textureUrl());
            }
            java.nio.file.Path file = skinSource.file();
            if (file != null && java.nio.file.Files.isRegularFile(file)) {
                return "|skin:file:" + (skinSource.slim() ? "slim:" : "wide:") + file
                        + ":" + java.nio.file.Files.size(file)
                        + ":" + java.nio.file.Files.getLastModifiedTime(file).toMillis();
            }
            if (skinSource.account() != null && !skinSource.account().isBlank()) {
                return "|skin:account:" + skinSource.account().toLowerCase(Locale.ROOT) + ":pending";
            }
        } catch (Throwable ignored) {
            // Invalid/missing custom file means the form's authored skin is retained.
        }
        return "";
    }

    /** Applies a live or user-selected PNG only to BBS's Steve/Alex model families. */
    private static boolean applySelectedSkin(Object form, SkinSource skinSource) {
        String currentModel = playerModel(form);
        if (currentModel == null || skinSource == null || linkCreate == null) return false;
        try {
            boolean slim;
            Object textureLink;
            PlayerSkin selected = selectedPlayerSkin(skinSource);
            if (selected != null) {
                PlayerSkin skin = selected;
                slim = skin.model() == PlayerSkin.Model.SLIM;
                String textureUrl = skin.textureUrl();
                if (textureUrl == null || textureUrl.isBlank()) return false;
                textureLink = linkCreate.invoke(null, textureUrl);
            } else {
                java.nio.file.Path file = skinSource.file();
                if (file == null || !java.nio.file.Files.isRegularFile(file)) return false;
                slim = skinSource.slim();
                textureLink = customSkinLink(file);
                if (textureLink == null) return false;
            }
            String suffix = currentModel.substring((currentModel.startsWith("player/alex")
                    ? "player/alex" : "player/steve").length());
            String targetModel = "player/" + (slim ? "alex" : "steve") + suffix;
            Object modelValue = modelFormModelField.get(form);
            modelValueSet.invoke(modelValue, targetModel);
            ensureModelsLoaded(java.util.List.of(targetModel));
            Object textureValue = modelFormTextureField.get(form);
            textureValueSet.invoke(textureValue, textureLink);
            return true;
        } catch (Throwable error) {
            warnOnce("Failed to apply a Minecraft skin to a BBS player form", error);
            return false;
        }
    }

    private static PlayerSkin selectedPlayerSkin(SkinSource source) {
        if (source.player() instanceof AbstractClientPlayer clientPlayer) return clientPlayer.getSkin();
        if (source.account() == null || source.account().isBlank()) return null;
        String account = source.account().trim();
        if (!account.matches("[A-Za-z0-9_]{1,16}")) return null;
        CompletableFuture<PlayerSkin> future = ACCOUNT_SKINS.computeIfAbsent(
                account.toLowerCase(Locale.ROOT), ignored -> loadAccountSkin(account));
        if (!future.isDone() || future.isCompletedExceptionally()) return null;
        return future.getNow(null);
    }

    /** Starts/polls an official account lookup for settings UI feedback. */
    static String accountSkinStatus(String rawAccount) {
        String account = rawAccount == null ? "" : rawAccount.trim();
        if (!account.matches("[A-Za-z0-9_]{1,16}")) return "invalid";
        CompletableFuture<PlayerSkin> future = ACCOUNT_SKINS.computeIfAbsent(
                account.toLowerCase(Locale.ROOT), ignored -> loadAccountSkin(account));
        if (!future.isDone()) return "loading";
        if (future.isCompletedExceptionally()) return "failed";
        return future.getNow(null) == null ? "failed" : "ready";
    }

    /** Uses Mojang's official name lookup, then Minecraft's normal skin manager/cache. */
    private static CompletableFuture<PlayerSkin> loadAccountSkin(String account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + account))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return null;
                String compactId = JsonParser.parseString(response.body()).getAsJsonObject()
                        .get("id").getAsString();
                if (!compactId.matches("[0-9a-fA-F]{32}")) return null;
                String uuidText = compactId.replaceFirst(
                        "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                        "$1-$2-$3-$4-$5");
                Minecraft mc = Minecraft.getInstance();
                ProfileResult result = mc.getMinecraftSessionService().fetchProfile(
                        UUID.fromString(uuidText), true);
                GameProfile profile = result == null ? null : result.profile();
                return profile == null ? null : mc.getSkinManager().getOrLoad(profile).join();
            } catch (Throwable error) {
                FnfMod.LOGGER.warn("Could not load Minecraft account skin for {}: {}",
                        account, error.toString());
                return null;
            }
        });
    }

    /** Registers the PNG's parent under an isolated BBS source and returns its link. */
    private static Object customSkinLink(java.nio.file.Path file) throws Exception {
        if (!bundlingAvailable || externalPackCtor == null || providerRegister == null) return null;
        java.nio.file.Path canonicalFile = file.toRealPath();
        java.nio.file.Path parent = canonicalFile.getParent();
        if (parent == null) return null;
        String key = parent.toString();
        String source = CUSTOM_SKIN_PACKS.get(key);
        if (source == null) {
            String base = "blockified_skin_" + Integer.toUnsignedString(key.hashCode(), 36);
            source = base;
            int suffix = 2;
            while (CUSTOM_SKIN_PACKS.containsValue(source)) source = base + "_" + suffix++;
            Object provider = getProvider.invoke(null);
            Object pack = externalPackCtor.newInstance(source, parent.toFile());
            packProvidesFiles.invoke(pack);
            providerRegister.invoke(provider, pack);
            CUSTOM_SKIN_PACKS.put(key, source);
        }
        return linkCreate.invoke(null, source + ":" + canonicalFile.getFileName());
    }

    /**
     * A BBS state's texture channel writes a runtime value after the form's base
     * texture has been replaced, which would cover the selected Minecraft skin.
     * Remove only main-model texture channels from this private form copy. Nested
     * body-part textures and every non-texture animation channel remain authored.
     */
    private static void suppressSkinReplacingTextureKeyframes(Object form) {
        if (form == null || formStates == null || animationStatesGetAllTyped == null
                || animationStateProperties == null || formPropertiesMap == null) return;
        try {
            Object states = formStates.get(form);
            Object all = states == null ? null : animationStatesGetAllTyped.invoke(states);
            if (!(all instanceof Iterable<?> iterable)) return;
            for (Object state : iterable) {
                Object properties = animationStateProperties.get(state);
                Object rawMap = properties == null ? null : formPropertiesMap.get(properties);
                if (!(rawMap instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> channels = (Map<String, Object>) raw;
                channels.keySet().removeIf(key -> key != null
                        && (key.equalsIgnoreCase("texture")
                        || key.toLowerCase(Locale.ROOT).startsWith("texture.materials.")));
            }
        } catch (Throwable error) {
            warnOnce("Failed to preserve a selected Minecraft skin across BBS texture keyframes", error);
        }
    }

    private static String playerModel(Object form) {
        if (form == null || modelFormClass == null || !modelFormClass.isInstance(form)) return null;
        try {
            Object value = modelValueGet.invoke(modelFormModelField.get(form));
            String model = value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
            for (String suffix : new String[]{"", "_3d", "_bends", "_bends_3d"}) {
                if (model.equals("player/steve" + suffix) || model.equals("player/alex" + suffix)) {
                    return model;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Forces a performer's BBS form lighting (0 = full-bright/unlit, 1 = normal world
     * light). Persists across form swaps until reset to 1. Returns false if BBS or the
     * lighting value is unavailable.
     */
    static synchronized boolean setLighting(Player player, float value) {
        if (!isAvailable() || player == null || formLightingField == null || lightingValueSet == null) {
            return false;
        }
        if (value >= 1f) FORCED_LIGHTING.remove(player.getUUID());
        else FORCED_LIGHTING.put(player.getUUID(), value);
        try {
            Object morph = morphGet.invoke(null, player);
            if (morph == null) return false;
            applyLighting(player, morphGetForm.invoke(morph));
            return true;
        } catch (Throwable error) {
            warnOnce("Failed to set a BBS FS form lighting", error);
            return false;
        }
    }

    static synchronized boolean isLightingForced(Player player) {
        return player != null && FORCED_LIGHTING.containsKey(player.getUUID());
    }

    private static void applyLighting(Player player, Object form) {
        if (form == null || formLightingField == null || lightingValueSet == null) return;
        Float forced = FORCED_LIGHTING.get(player.getUUID());
        try {
            Object lightingValue = formLightingField.get(form);
            if (lightingValue != null) lightingValueSet.invoke(lightingValue, forced == null ? 1f : forced);
        } catch (Throwable ignored) {
            // Best effort; a missing lighting value just leaves normal lighting.
        }
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

            // Optional: force a bundled model to load up front so the morph is not
            // shown as the vanilla player while BBS lazily loads it on first render.
            try {
                Class<?> bbsClientClass = Class.forName("mchorse.bbs_mod.BBSModClient");
                Class<?> modelManagerClass = Class.forName("mchorse.bbs_mod.cubic.model.ModelManager");
                getModels = findMethod(bbsClientClass, "getModels", true, 0);
                modelManagerGetModel = modelManagerClass.getMethod("getModel", String.class);
                // loadModel is synchronous; getModel only queues an async load on a
                // background thread whose queue is unsynchronized and races its own
                // shutdown, so a model queued as the thread exits is never loaded.
                modelManagerLoadModel = modelManagerClass.getMethod("loadModel", String.class);
                modelManagerModelsField = modelManagerClass.getField("models");
            } catch (Throwable ignored) {
                getModels = null;
                modelManagerGetModel = null;
                modelManagerLoadModel = null;
                modelManagerModelsField = null;
            }
            // Optional: read forms off placed BBS model blocks for world bundling.
            try {
                modelBlockEntityClass = Class.forName("mchorse.bbs_mod.blocks.entities.ModelBlockEntity");
                modelBlockGetProperties = modelBlockEntityClass.getMethod("getProperties");
                modelPropertiesGetForm = modelBlockGetProperties.getReturnType().getMethod("getForm");
            } catch (Throwable ignored) {
                modelBlockEntityClass = null;
                modelBlockGetProperties = null;
                modelPropertiesGetForm = null;
            }

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

    /**
     * Imports a self-contained Character Editor form into BBS's Recent category.
     * This mirrors BBS's shared-form receiver: the Recent entry is a private copy,
     * while Blockified's cached template remains untouched. A form already present
     * in any BBS category with the same custom name (or raw ID when unnamed) wins;
     * Blockified does not add another copy to Recent.
     */
    static synchronized boolean importBundledFormToRecent(java.nio.file.Path formJson) {
        if (!isAvailable() || !bundlingAvailable || formJson == null) return false;
        Object bundled = loadBundledForm(formJson);
        if (bundled == null) return false;
        try {
            Object categories = getFormCategories.invoke(null);
            if (categories == null) return false;
            if (hasMatchingCategorizedForm(categories, bundled)) return false;
            Object recentSection = categories.getClass().getMethod("getRecentForms").invoke(categories);
            Object rawCategories = recentSection == null ? null
                    : recentSection.getClass().getMethod("getCategories").invoke(recentSection);
            if (!(rawCategories instanceof List<?> list) || list.isEmpty()) return false;
            Object recentCategory = list.get(0);

            String key = bundledKey(formJson);
            Object previous = RECENT_IMPORTED_FORMS.remove(key);
            if (previous != null) {
                recentCategory.getClass().getMethod("removeForm", formClass)
                        .invoke(recentCategory, previous);
            }

            Object imported = formCopy.invoke(null, bundled);
            if (imported == null) return false;
            recentCategory.getClass().getMethod("addForm", formClass)
                    .invoke(recentCategory, imported);
            RECENT_IMPORTED_FORMS.put(key, imported);
            registerForm(imported);
            return true;
        } catch (Throwable error) {
            warnOnce("Failed to import a Character Editor form into BBS Recent", error);
            return false;
        }
    }

    /** True when any installed/recent BBS form already owns this form identity. */
    private static boolean hasMatchingCategorizedForm(Object categories, Object candidate) throws Exception {
        java.util.Set<String> wanted = formIdentities(candidate);
        if (wanted.isEmpty()) return false;

        Object all = getAllCategories.invoke(categories);
        if (!(all instanceof Iterable<?> categoryIterable)) return false;
        for (Object category : categoryIterable) {
            Object forms = categoryGetForms.invoke(category);
            if (!(forms instanceof Iterable<?> formIterable)) continue;
            for (Object existing : formIterable) {
                for (String identity : formIdentities(existing)) {
                    if (wanted.contains(identity)) return true;
                }
            }
        }
        return false;
    }

    /**
     * A BBS raw ID identifies the form type, not necessarily one configured form.
     * Prefer the custom name; only unnamed forms use that raw ID for deduplication.
     */
    private static java.util.Set<String> formIdentities(Object form) {
        java.util.Set<String> identities = new java.util.LinkedHashSet<>();
        if (form == null || !formClass.isInstance(form)) return identities;

        String rawId = invokeString(formGetId, form);
        String idOrName = invokeString(formGetIdOrName, form);
        String displayName = invokeString(formGetDisplayName, form);
        boolean customName = idOrName != null && !idOrName.isBlank()
                && (rawId == null || !idOrName.equalsIgnoreCase(rawId));

        if (customName) {
            addIdentity(identities, idOrName);
            addIdentity(identities, displayName);
        } else {
            addIdentity(identities, rawId);
            addIdentity(identities, idOrName);
            addIdentity(identities, displayName);
        }
        return identities;
    }

    private static void addIdentity(java.util.Set<String> identities, String value) {
        if (value == null || value.isBlank()) return;
        identities.add(value.trim().toLowerCase(Locale.ROOT));
    }

    static synchronized void clearBundledForms() {
        BUNDLED_FORMS.clear();
        BUNDLED_MODEL_IDS.clear();
        // Keep Recent-entry references so reloading an animation can replace its
        // prior BBS category copy instead of leaving a duplicate behind.
    }

    private static Object loadBundledForm(java.nio.file.Path formJson) {
        if (!bundlingAvailable || formJson == null) return null;
        String key = bundledKey(formJson);
        if (BUNDLED_FORMS.containsKey(key)) {
            // Cache hit (e.g. a Change Character re-applying a form): still make
            // sure its models are loaded, since BBS may have dropped them since.
            ensureModelsLoaded(BUNDLED_MODEL_IDS.get(key));
            return BUNDLED_FORMS.get(key);
        }

        Object form = null;
        java.util.List<String> modelIds = java.util.List.of();
        try {
            if (java.nio.file.Files.isRegularFile(formJson)) {
                registerAssetPack(formJson.getParent());
                String json = java.nio.file.Files.readString(formJson);
                Object data = dataFromString.invoke(null, json);
                if (data != null) {
                    form = formFromData.invoke(null, data);
                    modelIds = modelIds(json);
                    ensureModelsLoaded(modelIds);
                }
            }
        } catch (Throwable error) {
            warnOnce("Failed to load a bundled BBS form", error);
        }
        BUNDLED_FORMS.put(key, form);
        BUNDLED_MODEL_IDS.put(key, modelIds);
        return form;
    }

    /** Model ids referenced by a form's JSON. */
    private static java.util.List<String> modelIds(String json) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"model\"\\s*:\\s*\"([A-Za-z0-9_./\\-]+)\"").matcher(json);
        while (matcher.find()) ids.add(matcher.group(1));
        return new java.util.ArrayList<>(ids);
    }

    /**
     * Loads the given bundled models up front and synchronously, so the character
     * shows its real model the instant it is morphed. BBS's own {@code getModel}
     * defers to a background loader that can drop a model (unsynchronized queue
     * racing its thread shutdown), which is why bundled models loaded only some of
     * the time; {@code loadModel} does it here on the spot instead. Safe to call on
     * every apply — already-loaded models are skipped.
     */
    private static void ensureModelsLoaded(java.util.List<String> modelIds) {
        if (getModels == null || modelManagerLoadModel == null || modelIds == null || modelIds.isEmpty()) {
            return;
        }
        try {
            Object manager = getModels.invoke(null);
            if (manager == null) return;
            Map<?, ?> loaded = modelManagerModelsField == null ? null
                    : (Map<?, ?>) modelManagerModelsField.get(manager);
            for (String id : modelIds) {
                // Skip only when a real model is already cached; a cached null means
                // a previous async attempt was dropped, so force the load again.
                if (loaded != null && loaded.get(id) != null) continue;
                modelManagerLoadModel.invoke(manager, id);
            }
        } catch (Throwable ignored) {
            // Best-effort; normal lazy loading still applies.
        }
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

    /**
     * Copies the models and textures referenced by every placed BBS model block in the
     * loaded chunks around the local player into {@code assetsFolder} and registers it as
     * a BBS asset source, so a shared world can still render those blocks where the
     * original files are absent. Only existing placed blocks contribute — nothing else is
     * saved. Returns the number of distinct forms bundled.
     */
    static synchronized int bundleModelBlockAssets(java.nio.file.Path assetsFolder) {
        if (!bundlingAvailable || assetsFolder == null || modelBlockEntityClass == null) return 0;
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) return 0;

        java.util.Set<String> seen = new java.util.HashSet<>();
        int bundled = 0;
        int radius = Math.max(8, minecraft.options.renderDistance().get());
        int centerX = player.chunkPosition().x;
        int centerZ = player.chunkPosition().z;
        try {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    net.minecraft.world.level.chunk.LevelChunk chunk =
                            level.getChunkSource().getChunkNow(centerX + dx, centerZ + dz);
                    if (chunk == null) continue;
                    for (net.minecraft.world.level.block.entity.BlockEntity blockEntity
                            : chunk.getBlockEntities().values()) {
                        if (!modelBlockEntityClass.isInstance(blockEntity)) continue;
                        Object properties = modelBlockGetProperties.invoke(blockEntity);
                        Object form = properties == null ? null : modelPropertiesGetForm.invoke(properties);
                        if (form == null || !formClass.isInstance(form)) continue;
                        Object data = formToData.invoke(null, form);
                        if (data == null) continue;
                        String json = String.valueOf(dataToString.invoke(null, data, true));
                        if (!seen.add(json)) continue; // identical form already bundled
                        copyReferencedAssets(json, assetsFolder);
                        bundled++;
                    }
                }
            }
            if (bundled > 0) registerAssetPack(assetsFolder);
        } catch (Throwable error) {
            warnOnce("Failed to bundle BBS model-block assets", error);
        }
        return bundled;
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
