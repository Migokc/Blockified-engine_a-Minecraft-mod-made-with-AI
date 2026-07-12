package com.fnfmod.client.gui.editor;

import com.fnfmod.chart.CommandEventPlaceholders;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Opaque, dedicated editor for command-event Value 1. */
public final class CommandEventEditorScreen extends Screen {
    private static final List<String> PLACEHOLDERS = List.of(
            CommandEventPlaceholders.PLAYER,
            CommandEventPlaceholders.OPPONENT,
            CommandEventPlaceholders.SPEAKERS,
            "<left:1>",
            "<right:1>",
            "<forward:1>",
            "<backward:1>",
            "<up:1>",
            "<down:1>",
            "<forward:2,left:5>",
            CommandEventPlaceholders.CHARACTER_ROTATION,
            CommandEventPlaceholders.CAMERA_ROTATION);
    private static final List<String> DIRECTION_MACROS = List.of(
            "left:1>", "right:1>", "forward:1>", "backward:1>", "up:1>", "down:1>");

    private final Screen parent;
    private final String initialCommand;
    private final Consumer<String> onSave;
    private EditBox commandBox;
    private CommandSuggestions commandSuggestions;
    private final List<String> placeholderSuggestions = new ArrayList<>();
    private int placeholderStart = -1;
    private int placeholderSelection;
    private int suggestionX;
    private int suggestionY;
    private int suggestionWidth;
    private boolean syncingParserText;

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
        commandBox.setResponder(value -> {
            if (!syncingParserText) updateSuggestions();
        });
        setInitialFocus(commandBox);

        commandSuggestions = new CommandSuggestions(minecraft, this, commandBox, font,
                true, true, 0, 10, false, 0xD0000000);
        commandSuggestions.setAllowSuggestions(true);
        commandSuggestions.setAllowHiding(false);
        suggestionX = margin;
        suggestionY = 76;
        suggestionWidth = Math.max(100, Math.min(220, boxWidth));
        updateSuggestions();

        int buttonY = Math.max(146, height - 34);
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> saveAndClose())
                .bounds(width / 2 - 104, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> minecraft.setScreen(parent))
                .bounds(width / 2 + 4, buttonY, 100, 20).build());
    }

    private void updateSuggestions() {
        if (commandBox == null || commandSuggestions == null) return;
        placeholderSuggestions.clear();
        int cursor = commandBox.getCursorPosition();
        String value = commandBox.getValue();
        int start = value.lastIndexOf('<', Math.max(0, cursor - 1));
        if (start >= 0) {
            String fragment = value.substring(start, cursor).toLowerCase();
            if (!fragment.contains(">")) {
                placeholderStart = start;
                int comma = fragment.lastIndexOf(',');
                if (comma >= 0) {
                    String prefix = fragment.substring(0, comma + 1);
                    String afterComma = fragment.substring(comma + 1);
                    String spacing = afterComma.startsWith(" ") ? " " : "";
                    String tail = afterComma.trim();
                    if (tail.matches("(left|right|forward|backward|up|down):-?\\d*(?:\\.\\d*)?")) {
                        placeholderSuggestions.add(prefix + spacing + tail + ">");
                    } else {
                        DIRECTION_MACROS.stream().filter(candidate -> candidate.startsWith(tail))
                                .map(candidate -> prefix + spacing + candidate)
                                .forEach(placeholderSuggestions::add);
                    }
                } else if (fragment.matches("<(left|right|forward|backward|up|down):-?\\d*(?:\\.\\d*)?")) {
                    placeholderSuggestions.add(fragment + ">");
                } else {
                    PLACEHOLDERS.stream().filter(candidate -> candidate.startsWith(fragment))
                            .forEach(placeholderSuggestions::add);
                }
            }
        }
        placeholderSelection = Math.min(placeholderSelection, Math.max(0, placeholderSuggestions.size() - 1));
        if (placeholderSuggestions.isEmpty()) {
            placeholderStart = -1;
            commandSuggestions.setAllowSuggestions(true);
            updateVanillaSuggestions(value, cursor);
        } else {
            commandSuggestions.hide();
        }
    }

    private void updateVanillaSuggestions(String visibleCommand, int cursor) {
        String parserCommand = CommandEventPlaceholders.forAutocomplete(visibleCommand);
        if (parserCommand.equals(visibleCommand)) {
            commandSuggestions.updateCommandInfo();
            return;
        }
        syncingParserText = true;
        commandBox.setValue(parserCommand);
        commandBox.setCursorPosition(Math.min(cursor, parserCommand.length()));
        commandSuggestions.updateCommandInfo();
        commandBox.setValue(visibleCommand);
        commandBox.setCursorPosition(Math.min(cursor, visibleCommand.length()));
        syncingParserText = false;
    }

    private void applyPlaceholderSuggestion() {
        if (placeholderSuggestions.isEmpty() || placeholderStart < 0) return;
        String placeholder = placeholderSuggestions.get(placeholderSelection);
        int cursor = commandBox.getCursorPosition();
        String value = commandBox.getValue();
        commandBox.setValue(value.substring(0, placeholderStart) + placeholder + value.substring(cursor));
        commandBox.setCursorPosition(placeholderStart + placeholder.length());
        setFocused(commandBox);
        updateSuggestions();
    }

    private void saveAndClose() {
        if (commandBox != null) onSave.accept(commandBox.getValue());
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!placeholderSuggestions.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_UP) {
                placeholderSelection = Math.floorMod(placeholderSelection - 1, placeholderSuggestions.size());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                placeholderSelection = (placeholderSelection + 1) % placeholderSuggestions.size();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER
                    || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyPlaceholderSuggestion();
                return true;
            }
        }
        if (commandSuggestions != null && commandSuggestions.keyPressed(keyCode, scanCode, modifiers)) return true;
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!placeholderSuggestions.isEmpty() && button == 0
                && mouseX >= suggestionX && mouseX < suggestionX + suggestionWidth
                && mouseY >= suggestionY && mouseY < suggestionY + placeholderSuggestions.size() * 12) {
            placeholderSelection = (int) ((mouseY - suggestionY) / 12);
            applyPlaceholderSuggestion();
            return true;
        }
        if (commandSuggestions != null && commandSuggestions.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!placeholderSuggestions.isEmpty()) {
            placeholderSelection = Math.floorMod(placeholderSelection - (int) Math.signum(scrollY),
                    placeholderSuggestions.size());
            return true;
        }
        if (commandSuggestions != null && commandSuggestions.mouseScrolled(scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Fully opaque so the chart editor cannot show through long command text or suggestions.
        gui.fill(0, 0, width, height, 0xFF07070A);
        gui.drawCenteredString(font, "Minecraft Command Event - Value 1", width / 2, 18, 0xFFFFFFFF);
        gui.drawString(font, "Command (no practical character limit)", Math.max(16, width / 20), 42,
                0xFFDDDDDD, false);
        gui.drawString(font, "Minecraft + FNF autocomplete; type '<' for FNF targets/macros:",
                Math.max(16, width / 20), 88, 0xFFFFFFFF, false);
        gui.drawString(font, "<player> = player-side performer   <opponent> = opponent-side performer",
                Math.max(16, width / 20), 132, 0xFFBBBBBB, false);
        gui.drawString(font, "<speakers> = invisible target at the Funkin' Machine",
                Math.max(16, width / 20), 143, 0xFFBBBBBB, false);
        gui.drawString(font, "Directions = camera-relative XYZ; combine: <forward:2,left:5>",
                Math.max(16, width / 20), 154, 0xFFBBBBBB, false);
        gui.drawString(font, "<camera_rotation> = camera yaw and pitch (use after XYZ in tp)",
                Math.max(16, width / 20), 165, 0xFFBBBBBB, false);
        gui.drawString(font, "<character_rotation:0> = stage character yaw + degrees (use after XYZ in tp)",
                Math.max(16, width / 20), 176, 0xFFBBBBBB, false);
        if (commandBox != null) {
            gui.drawString(font, "Characters: " + commandBox.getValue().length(),
                    Math.max(16, width / 20), 76, 0xFF888888, false);
        }
        super.render(gui, mouseX, mouseY, partialTick);
        gui.pose().pushPose();
        gui.pose().translate(0, 0, 300);
        if (placeholderSuggestions.isEmpty()) {
            if (commandSuggestions != null) commandSuggestions.render(gui, mouseX, mouseY);
        } else {
            renderPlaceholderSuggestions(gui);
        }
        gui.pose().popPose();
    }

    private void renderPlaceholderSuggestions(GuiGraphics gui) {
        int width = placeholderSuggestions.stream().mapToInt(font::width).max().orElse(80) + 10;
        suggestionWidth = Math.max(100, Math.min(this.width - suggestionX - 8, width));
        for (int i = 0; i < placeholderSuggestions.size(); i++) {
            int y = suggestionY + i * 12;
            gui.fill(suggestionX, y, suggestionX + suggestionWidth, y + 12,
                    i == placeholderSelection ? 0xE0666666 : 0xE0101010);
            gui.drawString(font, placeholderSuggestions.get(i), suggestionX + 4, y + 2, 0xFFFFFFFF, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xFF07070A);
    }
}
