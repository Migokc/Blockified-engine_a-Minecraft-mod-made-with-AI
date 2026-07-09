package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.gui.editor.ChartEditorScreen;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongLibrary;
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
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
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
                SongLibrary.ensureFolders();
                SongLibrary.pruneCache(30); // drop server downloads unused for a month
                SongLibrary.rescan();
                com.fnfmod.client.render.IconLibrary.rescan();
                ClientOptions.load();
                CharacterAnimations.init();
            });
        }
    }

    @EventBusSubscriber(modid = FnfMod.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        /** FNF-style beat zoom: pinch the FOV while the gameplay camera is active. */
        @SubscribeEvent
        public static void onComputeFov(ViewportEvent.ComputeFov event) {
            float scale = com.fnfmod.client.camera.GameplayCamera.fovScale();
            if (scale != 1f) {
                event.setFOV(event.getFOV() * scale);
            }
        }

        private static boolean hotbarTranslated = false;

        /** Hide vanilla HUD during gameplay; only the hotbar stays (except FNF). */
        @SubscribeEvent
        public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
            if (!(Minecraft.getInstance().screen instanceof com.fnfmod.client.gui.GameplayScreen)) return;
            var name = event.getName();
            String style = ClientOptions.effectiveHudStyle();
            boolean keepHotbar = !"fnf".equals(style) && VanillaGuiLayers.HOTBAR.equals(name);
            if (!keepHotbar) {
                event.setCanceled(true);
                return;
            }
            // downscroll: move the hotbar flush against the top of the screen
            if (ClientOptions.get().downscroll) {
                var gui = event.getGuiGraphics();
                gui.pose().pushPose();
                gui.pose().translate(0, -(gui.guiHeight() - 22), 0);
                hotbarTranslated = true;
            }
        }

        @SubscribeEvent
        public static void onRenderGuiLayerPost(RenderGuiLayerEvent.Post event) {
            if (hotbarTranslated && VanillaGuiLayers.HOTBAR.equals(event.getName())) {
                event.getGuiGraphics().pose().popPose();
                hotbarTranslated = false;
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
                            .executes(ctx -> {
                                SongLibrary.rescan();
                                com.fnfmod.client.render.IconLibrary.rescan();
                                CharacterAnimations.reload();
                                NoteStyle.reload();
                                feedback(ctx.getSource(), "Reloaded songs, skins and animations. "
                                        + SongLibrary.getSongs().size() + " song(s) found.");
                                // also ask the server to rescan its library (op-gated on dedicated)
                                PacketDistributor.sendToServer(new FnfPayloads.ReloadC2S());
                                return 1;
                            })));
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
