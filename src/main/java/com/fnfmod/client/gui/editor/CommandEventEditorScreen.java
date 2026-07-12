package com.fnfmod.client.gui.editor;

import com.fnfmod.chart.CommandEventPlaceholders;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/** Opaque, dedicated editor for command-event Value 1. */
public final class CommandEventEditorScreen extends Screen {
    private static final List<String> PLACEHOLDERS = List.of(
            CommandEventPlaceholders.PLAYER,
            CommandEventPlaceholders.OPPONENT,
            CommandEventPlaceholders.SPEAKERS);

    private final Screen parent;
    private final String initialCommand;
    private final Consumer<String> onSave;
    private EditBox commandBox;

    public CommandEventEditorScreen(Screen parent, String initialCommand, Consumer<String> onSave) {
        super(Component.literal("Minecraft Command Event"));
        this.parent = parent;
        this.initialCommand = initialCommand == null ? "" : initialCommand;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        int margin = Math.max(16, width / 20);
        int boxWidth = Math.max(80, width - margin * 2);
        commandBox = addRenderableWidget(new EditBox(font, margin, 54, boxWidth, 20,
                Component.literal("Command")));
        commandBox.setMaxLength(Integer.MAX_VALUE);
        commandBox.setValue(initialCommand);
        commandBox.setCursorPosition(initialCommand.length());
        setInitialFocus(commandBox);

        int suggestionY = 104;
        int gap = 6;
        int suggestionWidth = Math.max(70, (boxWidth - gap * 2) / 3);
        for (int i = 0; i < PLACEHOLDERS.size(); i++) {
            String placeholder = PLACEHOLDERS.get(i);
            addRenderableWidget(Button.builder(Component.literal(placeholder),
                            b -> insertPlaceholder(placeholder))
                    .bounds(margin + i * (suggestionWidth + gap), suggestionY, suggestionWidth, 20)
                    .build());
        }

        int buttonY = Math.max(146, height - 34);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveAndClose())
                .bounds(width / 2 - 104, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> minecraft.setScreen(parent))
                .bounds(width / 2 + 4, buttonY, 100, 20).build());
    }

    private void insertPlaceholder(String placeholder) {
        if (commandBox == null) return;
        int cursor = commandBox.getCursorPosition();
        String value = commandBox.getValue();
        commandBox.setValue(value.substring(0, cursor) + placeholder + value.substring(cursor));
        commandBox.setCursorPosition(cursor + placeholder.length());
        setFocused(commandBox);
    }

    private boolean autocompletePlaceholder() {
        if (commandBox == null) return false;
        int cursor = commandBox.getCursorPosition();
        String value = commandBox.getValue();
        int start = value.lastIndexOf('<', Math.max(0, cursor - 1));
        String fragment = start >= 0 ? value.substring(start, cursor).toLowerCase() : "";
        String match = PLACEHOLDERS.stream()
                .filter(candidate -> fragment.isEmpty() || candidate.startsWith(fragment))
                .findFirst().orElse(null);
        if (match == null) return false;
        if (start < 0) start = cursor;
        commandBox.setValue(value.substring(0, start) + match + value.substring(cursor));
        commandBox.setCursorPosition(start + match.length());
        return true;
    }

    private void saveAndClose() {
        if (commandBox != null) onSave.accept(commandBox.getValue());
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB && autocompletePlaceholder()) return true;
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            saveAndClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            minecraft.setScreen(parent);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Fully opaque so the chart editor cannot show through long command text or suggestions.
        gui.fill(0, 0, width, height, 0xFF07070A);
        gui.drawCenteredString(font, "Minecraft Command Event - Value 1", width / 2, 18, 0xFFFFFFFF);
        gui.drawString(font, "Command (no practical character limit)", Math.max(16, width / 20), 42,
                0xFFDDDDDD, false);
        gui.drawString(font, "Event autocomplete - click a placeholder or type '<' and press Tab:",
                Math.max(16, width / 20), 88, 0xFFFFFFFF, false);
        gui.drawString(font, "<player> = player-side performer   <opponent> = opponent-side performer",
                Math.max(16, width / 20), 132, 0xFFBBBBBB, false);
        gui.drawString(font, "<speakers> = invisible target at the Funkin' Machine",
                Math.max(16, width / 20), 143, 0xFFBBBBBB, false);
        if (commandBox != null) {
            gui.drawString(font, "Characters: " + commandBox.getValue().length(),
                    Math.max(16, width / 20), 76, 0xFF888888, false);
        }
        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xFF07070A);
    }
}
