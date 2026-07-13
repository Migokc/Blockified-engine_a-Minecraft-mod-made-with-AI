package com.fnfmod.client.gameplay;

import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.chart.ChartEventTypes;
import com.fnfmod.chart.CommandEventPlaceholders;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
                GameplayCamera.zoomTo(Float.parseFloat(event.value1.trim()), event.value2);
            } catch (NumberFormatException ignored) {}
        } else if (ChartEventTypes.isCameraFocus(event.name)) {
            cameraFocusHandler.accept(event);
        }
        luaHandler.accept(event);
    }

    private void executeCommand(int eventIndex, SongChart.Event event) {
        if ("server".equalsIgnoreCase(event.value2.trim()) && !editorPlaytest.getAsBoolean()) {
            PacketDistributor.sendToServer(new FnfPayloads.CommandEventC2S(machinePosition, eventIndex));
            return;
        }
        runPlayerCommand(event.value1);
    }

    private void runPlayerCommand(String rawCommand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null || rawCommand == null) return;
        Direction facing = Direction.NORTH;
        if (minecraft.level != null) {
            var state = minecraft.level.getBlockState(machinePosition);
            if (state.hasProperty(FunkinMachineBlock.FACING)) facing = state.getValue(FunkinMachineBlock.FACING);
        }
        String command = CommandEventPlaceholders.expand(rawCommand, machinePosition, facing).trim();
        while (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isEmpty()) return;
        try {
            minecraft.player.connection.sendCommand(command);
        } catch (Exception error) {
            minecraft.player.displayClientMessage(
                    Component.literal("FNF event command failed: " + error.getMessage()), false);
        }
    }
}
