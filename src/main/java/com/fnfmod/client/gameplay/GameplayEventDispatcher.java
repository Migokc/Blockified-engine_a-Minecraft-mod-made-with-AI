package com.fnfmod.client.gameplay;

import com.fnfmod.chart.ChartEventTypes;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.render.DirectionalShadingControl;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Executes built-in chart events and forwards every event to Lua. */
public final class GameplayEventDispatcher {
    private final BlockPos machinePosition;
    private final BooleanSupplier editorPlaytest;
    private final Consumer<SongChart.Event> cameraFocusHandler;
    private final Consumer<SongChart.Event> luaHandler;

    public GameplayEventDispatcher(BlockPos machinePosition, BooleanSupplier editorPlaytest,
                                   Consumer<SongChart.Event> cameraFocusHandler,
                                   Consumer<SongChart.Event> luaHandler) {
        this.machinePosition = machinePosition;
        this.editorPlaytest = editorPlaytest;
        this.cameraFocusHandler = cameraFocusHandler;
        this.luaHandler = luaHandler;
    }

    public void execute(int eventIndex, SongChart.Event event) {
        if (ChartEventTypes.isMinecraftCommand(event.name)) {
            executeCommand(eventIndex, event);
        } else if (ChartEventTypes.isCameraZoom(event.name)) {
            try {
                // value1 = zoom amount, value2 = duration seconds (empty = default),
                // value3 = easing. Older charts stored the easing in value2, so a
                // non-numeric value2 is treated as that legacy easing.
                double durationMs = 0;
                String easing = event.value3;
                String rawDuration = event.value2 == null ? "" : event.value2.trim();
                if (!rawDuration.isEmpty()) {
                    try {
                        durationMs = Double.parseDouble(rawDuration) * 1000.0;
                    } catch (NumberFormatException legacyEasing) {
                        if (easing == null || easing.isBlank()) easing = rawDuration;
                    }
                }
                GameplayCamera.zoomTo(Float.parseFloat(event.value1.trim()), durationMs, easing);
            } catch (NumberFormatException ignored) {}
        } else if (ChartEventTypes.isCameraFocus(event.name)) {
            cameraFocusHandler.accept(event);
        } else if (ChartEventTypes.isCameraBehavior(event.name)) {
            GameplayCamera.setCameraBehavior(event.value1, event.value2, event.value3);
        } else if (ChartEventTypes.is(event.name, ChartEventTypes.DIRECTIONAL_SHADING)) {
            DirectionalShadingControl.setBlockShading(
                    DirectionalShadingControl.parseToggle(event.value1, true));
            DirectionalShadingControl.setEntityShading(
                    DirectionalShadingControl.parseToggle(event.value2, true));
        }
        luaHandler.accept(event);
    }

    private void executeCommand(int eventIndex, SongChart.Event event) {
        if (!editorPlaytest.getAsBoolean()) {
            // Route both player- and server-run commands through SessionManager so
            // their world writes occur inside the per-song rollback transaction.
            PacketDistributor.sendToServer(new FnfPayloads.CommandEventC2S(machinePosition, eventIndex));
            return;
        }
        // An editor playtest has its own server rollback journal. Sending the raw
        // command through the Lua-command route keeps unsaved charts usable while
        // still tracking blocks, player state, time and gamemode.
        PacketDistributor.sendToServer(new FnfPayloads.LuaCommandC2S(
                machinePosition, event.value1, event.value2 == null ? "player" : event.value2));
    }

    /** Lua-facing command entry point. Uses same placeholders as chart events. */
    public boolean runLuaCommand(String rawCommand, String runner) {
        if (rawCommand == null || rawCommand.isBlank()) return false;
        // Server preserves runner permissions and journals mutations for both a
        // normal session and the isolated editor-playtest transaction.
        PacketDistributor.sendToServer(new FnfPayloads.LuaCommandC2S(
                machinePosition, rawCommand, runner == null ? "player" : runner));
        return true;
    }
}
