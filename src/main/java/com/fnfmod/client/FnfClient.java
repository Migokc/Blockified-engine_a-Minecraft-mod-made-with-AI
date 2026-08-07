package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.audio.HitsoundPlayer;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.gui.editor.ChartEditorScreen;
import com.fnfmod.client.render.IconLibrary;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModContentScope;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class FnfClient {

    private FnfClient() {}

    @EventBusSubscriber(modid = FnfMod.MODID, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            for (var key : FnfKeys.NOTE_KEYS) event.register(key);
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                ModContentScope.clear();
                SongLibrary.ensureFolders();
                SongLibrary.pruneCache(30); // drop server downloads unused for a month
                SongLibrary.rescan();
                MachineLibrary.rescan();
                com.fnfmod.client.render.IconLibrary.rescan();
                ClientOptions.load();
                CharacterAnimations.init();
            });
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(FnfMod.FUNKIN_MACHINE_BLOCK_ENTITY.get(),
                    com.fnfmod.client.render.FunkinMachineRenderer::new);
            event.registerBlockEntityRenderer(FnfMod.MACHINE_ANCHOR_BLOCK_ENTITY.get(),
                    com.fnfmod.client.render.MachineAnchorRenderer::new);
            event.registerEntityRenderer(FnfMod.MACHINE_HITBOX_ENTITY.get(),
                    com.fnfmod.client.render.MachineHitboxEntityRenderer::new);
        }
    }

    @EventBusSubscriber(modid = FnfMod.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        /** Overlay a bundled mod world's forced settings once the world scope is bound. */
        @SubscribeEvent
        public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            // Re-read options.json from disk each time any world is entered, so
            // settings edited outside the game (including note colors) take effect,
            // then overlay a mod world's forced settings on top.
            ClientOptions.load();
            ClientOptions.applyWorldOverrides(ModContentScope.isModWorld()
                    ? ModContentScope.activeMod().map(ModContentScope.ActiveMod::worldRoot).orElse(null)
                    : null);
            // The skin/colors may have changed; rebuild so the effective values render.
            com.fnfmod.client.render.NoteStyle.reload();
        }

        @SubscribeEvent
        public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientSession.reset();
            ClientOptions.applyWorldOverrides(null);
            com.fnfmod.client.render.MachineHitboxPreview.clear();
            com.fnfmod.client.render.MachineAtlasCache.clear();
            com.fnfmod.client.render.MachineTextureCache.clear();
            ModContentScope.clear();
            SongLibrary.rescan();
            MachineLibrary.rescan();
            IconLibrary.rescan();
        }

        /** Add a "Mod Worlds" button to the singleplayer world-selection screen. */
        @SubscribeEvent
        public static void onScreenInit(net.neoforged.neoforge.client.event.ScreenEvent.Init.Post event) {
            if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen select)) {
                return;
            }
            event.addListener(net.minecraft.client.gui.components.Button.builder(
                            Component.literal("Mod Worlds"),
                            b -> Minecraft.getInstance().setScreen(
                                    new com.fnfmod.client.gui.ModWorldSelectScreen(select)))
                    .bounds(6, 6, 90, 20).build());
        }

        /** FNF-style beat zoom: pinch the FOV while the gameplay camera is active. */
        @SubscribeEvent
        public static void onComputeFov(ViewportEvent.ComputeFov event) {
            float scale = com.fnfmod.client.camera.GameplayCamera.fovScale();
            if (scale != 1f) {
                event.setFOV(event.getFOV() * scale);
            }
        }

        private static net.minecraft.resources.ResourceLocation translatedLayer;

        /** Hide unrelated vanilla HUD layers; vanilla style keeps Minecraft's real hearts and food. */
        @SubscribeEvent
        public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
            if (!(Minecraft.getInstance().screen instanceof GameplayScreen gameplay)) return;
            var name = event.getName();
            String style = gameplay.effectiveHudStyle();
            boolean keepHotbar = !"fnf".equals(style) && VanillaGuiLayers.HOTBAR.equals(name);
            boolean keepVanillaStatus = "vanilla".equals(style)
                    && (VanillaGuiLayers.PLAYER_HEALTH.equals(name)
                    || VanillaGuiLayers.FOOD_LEVEL.equals(name));
            if (!keepHotbar && !keepVanillaStatus) {
                event.setCanceled(true);
                return;
            }
            // Downscroll mirrors the native cluster to the top while retaining
            // Minecraft's own rendering and GUI-scale behavior.
            if (ClientOptions.get().downscroll) {
                var gui = event.getGuiGraphics();
                gui.pose().pushPose();
                double offset = keepHotbar ? -(gui.guiHeight() - 22) : 63 - gui.guiHeight();
                gui.pose().translate(0, offset, 0);
                translatedLayer = name;
            }
        }

        @SubscribeEvent
        public static void onRenderGuiLayerPost(RenderGuiLayerEvent.Post event) {
            if (translatedLayer != null && translatedLayer.equals(event.getName())) {
                event.getGuiGraphics().pose().popPose();
                translatedLayer = null;
            }
        }

        /** Draw Lua objects assigned to the world camera into the level itself. */
        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            com.fnfmod.client.render.MachineHitboxPreview.render(event.getPoseStack(), event.getCamera());
            if (Minecraft.getInstance().screen instanceof GameplayScreen gameplay) {
                gameplay.renderLuaWorld(event.getPoseStack(), event.getCamera());
            }
        }

        @SubscribeEvent
        public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(literal("fnf")
                    .then(literal("editor")
                            .executes(ctx -> {
                                openEditor(null);
                                return 1;
                            })
                            .then(argument("song", StringArgumentType.word())
                                    .executes(ctx -> {
                                        openEditor(StringArgumentType.getString(ctx, "song"));
                                        return 1;
                                    })))
                    .then(literal("reload")
                            .executes(ctx -> reloadAll(ctx.getSource()))
                            .then(literal("all").executes(ctx -> reloadAll(ctx.getSource())))
                            .then(literal("songs").executes(ctx -> reloadSongs(ctx.getSource())))
                            .then(literal("skins").executes(ctx -> reloadSkins(ctx.getSource(), "skins")))
                            .then(literal("splashes").executes(ctx -> reloadSkins(ctx.getSource(), "splashes")))
                            .then(literal("animations").executes(ctx -> {
                                CharacterAnimations.reload();
                                feedback(ctx.getSource(), "Reloaded animations.");
                                return 1;
                            }))
                            .then(literal("icons").executes(ctx -> {
                                IconLibrary.rescan();
                                feedback(ctx.getSource(), "Reloaded icons.");
                                return 1;
                            }))
                            .then(literal("hitsounds").executes(ctx -> {
                                HitsoundPlayer.reload();
                                feedback(ctx.getSource(), "Reloaded hitsounds.");
                                return 1;
                            }))
                            .then(literal("fonts").executes(ctx -> {
                                if (Minecraft.getInstance().screen instanceof GameplayScreen gameplay) {
                                    gameplay.reloadLuaFonts();
                                }
                                feedback(ctx.getSource(), "Reloaded Lua fonts.");
                                return 1;
                            }))
                            .then(literal("options").executes(ctx -> {
                                ClientOptions.load();
                                NoteStyle.reload();
                                HitsoundPlayer.reload();
                                feedback(ctx.getSource(), "Reloaded options.");
                                return 1;
                            }))
                            .then(literal("scores").executes(ctx -> {
                                ScoreStore.reload();
                                feedback(ctx.getSource(), "Reloaded scores.");
                                return 1;
                            }))));
        }

        private static int reloadAll(CommandSourceStack source) {
            ClientOptions.load();
            SongLibrary.rescan();
            MachineLibrary.rescan();
            IconLibrary.rescan();
            CharacterAnimations.reload();
            NoteStyle.reload();
            HitsoundPlayer.reload();
            ScoreStore.reload();
            if (Minecraft.getInstance().screen instanceof GameplayScreen gameplay) {
                gameplay.reloadLuaFonts();
            }
            feedback(source, "Reloaded all FNF content. "
                    + SongLibrary.getSongs().size() + " song(s) found.");
            requestServerSongReload();
            return 1;
        }

        private static int reloadSongs(CommandSourceStack source) {
            SongLibrary.rescan();
            MachineLibrary.rescan();
            // Song/mod folders can also provide icons.
            IconLibrary.rescan();
            feedback(source, "Reloaded songs. " + SongLibrary.getSongs().size() + " song(s) found.");
            requestServerSongReload();
            return 1;
        }

        private static int reloadSkins(CommandSourceStack source, String label) {
            // Skin and splash atlases share NoteStyle's loaded texture state.
            NoteStyle.reload();
            feedback(source, "Reloaded " + label + ".");
            return 1;
        }

        private static void requestServerSongReload() {
            // Server library is authoritative; dedicated servers require op.
            PacketDistributor.sendToServer(new FnfPayloads.ReloadC2S());
        }

        private static void openEditor(String songId) {
            Minecraft mc = Minecraft.getInstance();
            // opening a screen from a command needs to be deferred a tick
            mc.execute(() -> mc.setScreen(new ChartEditorScreen(songId)));
        }

        private static void feedback(CommandSourceStack source, String msg) {
            source.sendSuccess(() -> Component.literal(msg), false);
        }
    }
}
