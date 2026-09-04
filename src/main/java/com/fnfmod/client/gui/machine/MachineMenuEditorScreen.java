package com.fnfmod.client.gui.machine;

import com.fnfmod.client.camera.MenuCameraController;
import com.fnfmod.client.gui.BlockifiedScreenStyle;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.lua.MachineMenuRuntime;
import com.fnfmod.client.lua.PsychColor;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Live-world visual editor for one custom machine menu. */
public final class MachineMenuEditorScreen extends Screen implements MachineMenuRuntime.Host {
    private enum Tab { OBJECTS, LAYERS, EFFECTS, CAMERA, TWEENS }
    private enum Transform { NONE, MOVE, ROTATE, SCALE }

    private static final int HEADER_H = 44;
    private static final int FOOTER_H = 34;
    private static final int GAP = 8;
    private static final int LAYERS_LIST_Y = 86;
    private final Screen parent;
    private final MachineDefinition definition;
    /** Authored camera saved to Lua. The free camera is viewport navigation only. */
    private final MenuCameraController camera = new MenuCameraController();
    private final MenuCameraController freeCamera = new MenuCameraController();
    private boolean freeCameraMode;
    private MachineMenuRuntime runtime;
    private Tab tab = Tab.OBJECTS;
    private Transform transform = Transform.NONE;
    private String selectedId = "";
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private String status = "Click object to select · G/R/S transform · right-drag camera · Tab preview";
    private boolean objectDragging;
    private boolean cameraDragging;
    private boolean cameraOrbiting;
    private boolean cameraIgnoreWarpMotion;
    private boolean viewportFocused;
    private boolean previewMode;
    private int transformAxis;
    private boolean transformPlane;
    private boolean transformTrackball;
    /** Repeated rotation-axis presses cycle Local -> Global -> Local. */
    private boolean transformRotationGlobal;
    private boolean transformModal;
    private boolean transformIgnoreWarpMotion;
    private String transformNumeric = "";
    private double transformLastMouseX, transformLastMouseY;
    private double transformMouseDx, transformMouseDy, transformDegrees;
    private double baseX, baseY, baseZ, baseWidth, baseHeight;
    private double baseAngle, baseRotationX, baseRotationY;
    private record TransformBase(double x, double y, double z, double width, double height,
                                 double angle, double rotationX, double rotationY, String space) {}
    private final Map<String, TransformBase> transformBases = new LinkedHashMap<>();
    private final EnumMap<Tab, Double> panelScroll = new EnumMap<>(Tab.class);
    private final EnumMap<Tab, Double> panelScrollTarget = new EnumMap<>(Tab.class);
    private final EnumMap<Tab, Double> panelScrollFrom = new EnumMap<>(Tab.class);
    private final Map<AbstractWidget, Integer> panelWidgetBaseY = new IdentityHashMap<>();
    private final List<AbstractWidget> panelWidgets = new ArrayList<>();
    private final List<AbstractWidget> objectPropertyWidgets = new ArrayList<>();
    private final Map<String, AbstractWidget> layerRows = new LinkedHashMap<>();
    private final List<String> layerDisplayOrder = new ArrayList<>();
    private final Map<String, Double> layerVisualY = new LinkedHashMap<>();
    private int panelMaxScroll;
    private long panelScrollStarted;
    private boolean panelScrollTween;
    private String draggedLayer = "";
    private double layerPointerY;
    private double layerGrabOffset;
    private long lastLayerFrameNanos;
    private long lastCameraFrameNanos;
    private int tweenIndex;
    private boolean tweenCamera;
    private String tweenDuration = "1";
    private String tweenEasing = "expoOut";
    private BlockPos worldOrigin = BlockPos.ZERO;
    private static final int HISTORY_LIMIT = 100;
    private record HistoryEntry(MachineMenuRuntime.EditorHistoryState runtime,
                                List<String> selection, String primary) {}
    private final Deque<HistoryEntry> undoHistory = new ArrayDeque<>();
    private final Deque<HistoryEntry> redoHistory = new ArrayDeque<>();
    private boolean directDragCheckpoint;
    private boolean layerDragCheckpoint;
    private EditBox objectNameField;
    private String objectNameOriginal = "";
    private MachineMenuRuntime.EditorProperties copiedObjectProperties;
    private String copiedObjectName = "";
    private boolean gridEnabled;
    /** Spacing in Psych's fixed 1280x720 canvas pixels. */
    private double gridSize = 32.0;
    private double gridDragRemainderX, gridDragRemainderY;

    private int panelWidth() { return previewMode ? 0 : Math.min(310, Math.max(246, width / 3)); }
    private int panelX() { return width - panelWidth(); }
    private int previewY() { return previewMode ? 0 : HEADER_H + GAP; }
    private int previewW() {
        int available = previewMode ? width : Math.max(80, panelX() - GAP * 2);
        int usableHeight = previewMode ? height
                : Math.max(45, height - previewY() - FOOTER_H - GAP);
        int byHeight = Math.max(80, (int) Math.floor(usableHeight * 16.0 / 9.0));
        return Math.min(available, byHeight);
    }
    private int previewH() { return Math.max(45, (int) Math.floor(previewW() * 9.0 / 16.0)); }
    private int actualPreviewX() {
        int available = previewMode ? width : panelX();
        return Math.max(previewMode ? 0 : GAP, (available - previewW()) / 2);
    }
    private int actualPreviewY() {
        if (previewMode) return Math.max(0, (height - previewH()) / 2);
        int usableHeight = Math.max(previewH(), height - previewY() - FOOTER_H - GAP);
        return previewY() + Math.max(0, (usableHeight - previewH()) / 2);
    }

    public MachineMenuEditorScreen(Screen parent, MachineDefinition definition) {
        super(Component.literal("Menu Lua Editor"));
        this.parent = parent;
        this.definition = definition;
    }

    @Override protected void init() {
        objectNameField = null;
        objectNameOriginal = "";
        objectPropertyWidgets.clear();
        panelWidgetBaseY.clear();
        panelWidgets.clear();
        layerRows.clear();
        viewCamera().activate();
        viewCamera().engage();
        if (minecraft.player != null) worldOrigin = minecraft.player.blockPosition();
        if (runtime == null) {
            runtime = new MachineMenuRuntime(definition, "", "{}", List.of(), this, camera);
            for (Runnable task : runtime.takeBackgroundPreloadTasks()) task.run();
            for (Runnable task : runtime.takeMainPreloadTasks()) task.run();
            runtime.open();
            // The visual editor exposes actual Minecraft X/Y/Z. Migrate legacy
            // editor placements without changing where they currently appear.
            runtime.editorUseMinecraftCoordinates(worldOrigin, Direction.NORTH);
            selectFallback();
        }
        runtime.setEditorWorldViewport(actualPreviewX(), actualPreviewY(), previewW(), previewH());
        if (previewMode) return;
        int px = panelX() + 9, pw = panelWidth() - 18;
        int machineWidth = (pw - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Machine: " + definition.displayName()), ignored -> {
            if (minecraft != null) minecraft.setScreen(new MachineMenuEditorSelectScreen(this));
        }).bounds(px, 8, machineWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Screen: " + runtime.editorCurrentScreen()), ignored ->
                cycleEditorScreen()).bounds(px + machineWidth + 4, 8,
                pw - machineWidth - 4, 20).build());
        int tabW = (pw - 3 * (Tab.values().length - 1)) / Tab.values().length;
        int tabX = px;
        for (Tab value : Tab.values()) {
            Button button = addRenderableWidget(Button.builder(Component.literal(tabName(value)), ignored -> switchTab(value))
                    .bounds(tabX, 34, tabW, 20).build());
            button.active = tab != value;
            tabX += tabW + 3;
        }
        int contentStart = children().size();
        switch (tab) {
            case OBJECTS -> initObjects(px, pw);
            case LAYERS -> initLayers(px, pw);
            case EFFECTS -> initEffects(px, pw);
            case CAMERA -> initCamera(px, pw);
            case TWEENS -> initTweens(px, pw);
        }
        layoutScrollableContent(contentStart);
        addRenderableWidget(Button.builder(Component.literal("Save"), ignored -> save())
                .bounds(px, height - 28, (pw - 4) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose())
                .bounds(px + (pw - 4) / 2 + 4, height - 28, pw - (pw - 4) / 2 - 4, 20).build());
    }

    private static String tabName(Tab value) {
        return switch (value) {
            case OBJECTS -> "Objects"; case LAYERS -> "Layers";
            case EFFECTS -> "Effects"; case CAMERA -> "Camera"; case TWEENS -> "Keys";
        };
    }

    private MenuCameraController viewCamera() { return freeCameraMode ? freeCamera : camera; }

    private void toggleCameraMode() {
        cancelWorldTransform();
        cameraDragging = false;
        cameraOrbiting = false;
        if (!freeCameraMode) {
            Vec3 p = camera.position();
            freeCameraMode = true;
            freeCamera.activate();
            freeCamera.engage();
            freeCamera.setPosition(p.x, p.y, p.z, 0, "linear", "");
            freeCamera.setRotation(camera.pitch(), camera.yaw(), camera.roll(), 0, "linear", "");
            freeCamera.setFov(camera.fov());
            freeCamera.setZoom(camera.zoom(), 0, "linear", "");
            freeCamera.setStoredOrbitPivot(camera.storedOrbitPivot());
            status = "Free camera · viewport movement will not change saved camera values";
        } else {
            freeCamera.endOrbit();
            freeCameraMode = false;
            camera.activate();
            camera.engage();
            status = "Normal camera · viewport movement edits the saved menu camera";
        }
        rebuildWidgets();
    }

    private void cycleEditorScreen() {
        List<String> screens = runtime.editorScreens();
        if (screens.size() < 2) {
            status = "Add screens/<name>.lua to this machine to create another screen";
            return;
        }
        int current = Math.max(0, screens.indexOf(runtime.editorCurrentScreen()));
        String next = screens.get(cycleIndex(current, screens.size()));
        if (!runtime.saveEditorLayout()) {
            status = "Could not save the current screen before switching";
            return;
        }
        if (runtime.editorOpenScreen(next)) {
            selectedIds.clear(); selectedId = ""; selectFallback(); tweenIndex = 0;
            undoHistory.clear(); redoHistory.clear();
            status = "Editing screen: " + next;
            rebuildWidgets();
        } else status = "Could not open screen: " + next;
    }

    private void initObjects(int x, int w) {
        int firstPropertyChild = children().size();
        int y = 60;
        var widget = selectedWidget();
        if (widget == null) {
            addRenderableWidget(Button.builder(Component.literal("Select an object in the viewport"), ignored -> {})
                    .bounds(x, y, w, 20).build()).active = false;
        } else {
            int kindWidth = Math.min(92, Math.max(62, w / 3));
            objectNameOriginal = widget.id();
            objectNameField = addRenderableWidget(new EditBox(font, x, y,
                    w - kindWidth - 4, 20, Component.literal("Object ID")));
            objectNameField.setMaxLength(64);
            objectNameField.setHint(Component.literal("Object ID"));
            objectNameField.setValue(widget.id());
            objectNameField.setResponder(value -> {
                String candidate = value == null ? "" : value.trim();
                if (candidate.isEmpty()) status = "Object name cannot be empty";
                else if (!candidate.matches("[A-Za-z_][A-Za-z0-9_]*"))
                    status = "Use a Lua name: letters, numbers, and underscores";
                else if (!candidate.equals(selectedId) && runtime.editorWidget(candidate) != null)
                    status = "An object named " + candidate + " already exists";
            });
            addRenderableWidget(Button.builder(Component.literal(widget.kind()), ignored -> {})
                    .bounds(x + w - kindWidth, y, kindWidth, 20).build()).active = false;
        }
        y += 24;
        if (widget != null) {
            addRenderableWidget(Button.builder(Component.literal("Space: " + widget.space()), ignored -> {
                checkpoint();
                if (widget.space().equalsIgnoreCase("world")) {
                    runtime.editorSetString(selectedId, "space", "screen");
                } else {
                    net.minecraft.client.Camera live = minecraft.gameRenderer.getMainCamera();
                    Vec3 placement = viewCamera().position().add(new Vec3(live.getLookVector()).normalize().scale(3.0));
                    runtime.editorSetString(selectedId, "space", "world");
                    runtime.editorSetString(selectedId, "coordinates", "minecraft");
                    runtime.editorSetNumber(selectedId, "x", placement.x);
                    runtime.editorSetNumber(selectedId, "y", placement.y);
                    runtime.editorSetNumber(selectedId, "z", placement.z);
                }
                rebuildWidgets();
            }).bounds(x, y, w, 20).build());
            y += 24;
            if (widget.space().equalsIgnoreCase("world")) {
                boolean minecraftCoordinates = runtime.editorString(widget.id(), "coordinates")
                        .equalsIgnoreCase("minecraft");
                addRenderableWidget(Button.builder(Component.literal(minecraftCoordinates
                                ? "Coordinates: Minecraft" : "Coordinates: Machine-relative"), ignored -> {
                    checkpoint();
                    runtime.editorSetMinecraftCoordinates(selectedId, !minecraftCoordinates,
                            worldOrigin, Direction.NORTH);
                    rebuildWidgets();
                }).bounds(x, y, w, 20).build());
                y += 24;
            }
            String contentField = switch (widget.kind()) {
                case "label", "button" -> "text";
                case "image", "animatedSprite" -> "path";
                case "graph" -> "shape";
                default -> "";
            };
            if (!contentField.isEmpty()) {
                EditBox content = addRenderableWidget(new EditBox(font, x, y, w, 18,
                        Component.literal(contentField)));
                content.setHint(Component.literal(contentField));
                content.setValue(runtime.editorString(widget.id(), contentField));
                content.setResponder(value -> {
                    checkpoint(); runtime.editorSetString(selectedId, contentField, value);
                });
                y += 22;
            }
            boolean textObject = widget.kind().equals("label") || widget.kind().equals("button")
                    || widget.kind().equals("toggle") || widget.kind().equals("slider");
            if (textObject) {
                EditBox fontPath = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal("Font")));
                fontPath.setHint(Component.literal("Font path (for example fonts/custom.ttf)"));
                fontPath.setValue(runtime.editorString(widget.id(), "font"));
                fontPath.setResponder(value -> {
                    checkpoint(); runtime.editorSetString(selectedId, "font", value);
                });
                y += 22;
                addPair(x, y, w, "Text scale", runtime.editorNumber(widget.id(), "fontScale", 1), "fontScale",
                        "Border", runtime.editorNumber(widget.id(), "borderSize", 0), "borderSize"); y += 22;
                addField(x, y, w, "Font quality", runtime.editorNumber(widget.id(), "fontQuality", 8),
                        "fontQuality"); y += 22;
                addColorPair(x, y, w, "Text color", (int) runtime.editorNumber(widget.id(), "color", 0xFFFFFF), "color",
                        "Border color", (int) runtime.editorNumber(widget.id(), "borderColor", 0), "borderColor"); y += 22;
                if (widget.kind().equals("button")) {
                    addColorPair(x, y, w, "Button color",
                            (int) runtime.editorNumber(widget.id(), "backgroundColor", 0x333333), "backgroundColor",
                            "Hover color", (int) runtime.editorNumber(widget.id(), "hoverColor", 0x555555), "hoverColor");
                    y += 22;
                }
                int half = (w - 4) / 2;
                addRenderableWidget(Button.builder(Component.literal("Align: "
                                + runtime.editorString(widget.id(), "alignment")), ignored -> {
                    cycleTextString(selectedId, "alignment", new String[]{"left", "center", "right"});
                }).bounds(x, y, half, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Border: "
                                + runtime.editorString(widget.id(), "borderStyle")), ignored -> {
                    cycleTextString(selectedId, "borderStyle", new String[]{"none", "outline", "shadow"});
                }).bounds(x + half + 4, y, w - half - 4, 20).build()); y += 24;
                addRenderableWidget(Button.builder(Component.literal(runtime.editorBoolean(widget.id(), "shadow", true)
                                ? "MC Shadow: On" : "MC Shadow: Off"), ignored -> {
                    checkpoint(); runtime.editorToggle(selectedId, "shadow"); rebuildWidgets();
                }).bounds(x, y, half, 20).build());
                addRenderableWidget(Button.builder(Component.literal(runtime.editorBoolean(widget.id(), "italic", false)
                                ? "Italic: On" : "Italic: Off"), ignored -> {
                    checkpoint(); runtime.editorToggle(selectedId, "italic"); rebuildWidgets();
                }).bounds(x + half + 4, y, w - half - 4, 20).build()); y += 24;
                addPair(x, y, w, "Line gap", runtime.editorNumber(widget.id(), "lineSpacing", 0), "lineSpacing",
                        "Letter gap", runtime.editorNumber(widget.id(), "letterSpacing", 0), "letterSpacing"); y += 22;
            }
            boolean worldObject = widget.space().equalsIgnoreCase("world");
            addPair(x, y, w, "X", widget.x(), "x", "Y", widget.y(), "y"); y += 22;
            if (worldObject) {
                addPair(x, y, w, "Z", widget.z(), "z", "Width", widget.width(), "width"); y += 22;
                addPair(x, y, w, "Height", widget.height(), "height", "Alpha", widget.alpha(), "alpha"); y += 22;
                addPair(x, y, w, "Roll", widget.angle(), "angle", "Pitch", widget.rotationX(), "rotationX"); y += 22;
                addField(x, y, w, "Yaw", widget.rotationY(), "rotationY"); y += 24;
            } else {
                addPair(x, y, w, "Width", widget.width(), "width", "Height", widget.height(), "height"); y += 22;
                addPair(x, y, w, "Alpha", widget.alpha(), "alpha", "Angle", widget.angle(), "angle"); y += 24;
            }
            int half = (w - 4) / 2;
            if (worldObject) addRenderableWidget(Button.builder(Component.literal(widget.billboard()
                            ? "Billboard: On" : "Billboard: Off"), ignored -> {
                checkpoint(); runtime.editorToggle(selectedId, "billboard"); rebuildWidgets();
            }).bounds(x, y, half, 20).build());
            addRenderableWidget(Button.builder(Component.literal(widget.visible() ? "Visible" : "Hidden"), ignored -> {
                checkpoint(); runtime.editorToggle(selectedId, "visible"); rebuildWidgets();
            }).bounds(worldObject ? x + half + 4 : x, y,
                    worldObject ? w - half - 4 : w, 20).build());
            y += 24;
            if (worldObject) {
                String mode = widget.renderMode();
                addRenderableWidget(Button.builder(Component.literal("Render: "
                                + mode.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                                + mode.substring(1)), ignored -> {
                    checkpoint();
                    String next = switch (widget.renderMode().toLowerCase(java.util.Locale.ROOT)) {
                        case "lit" -> "flat"; case "flat" -> "emissive"; default -> "lit";
                    };
                    runtime.editorSetString(selectedId, "renderMode", next);
                    rebuildWidgets();
                }).bounds(x, y, half, 20).build());
                addRenderableWidget(Button.builder(Component.literal(widget.seeThrough() ? "See-through: On" : "See-through: Off"), ignored -> {
                    checkpoint(); runtime.editorToggle(selectedId, "seeThrough"); rebuildWidgets();
                }).bounds(x + half + 4, y, w - half - 4, 20).build());
                y += 24;
            }
        }
        for (int i = firstPropertyChild; i < children().size(); i++) {
            if (children().get(i) instanceof AbstractWidget property) objectPropertyWidgets.add(property);
        }
        int third = (w - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("+ Text"), ignored -> add("label"))
                .bounds(x, y, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Button"), ignored -> add("button"))
                .bounds(x + third + 4, y, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Image"), ignored -> add("image"))
                .bounds(x + (third + 4) * 2, y, w - (third + 4) * 2, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("+ Animated"), ignored -> add("animatedSprite"))
                .bounds(x, y, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Panel"), ignored -> add("panel"))
                .bounds(x + third + 4, y, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Graph"), ignored -> add("graph"))
                .bounds(x + (third + 4) * 2, y, w - (third + 4) * 2, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("+ Gradient"), ignored -> add("gradient"))
                .bounds(x, y, (w-4)/2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Group"), ignored -> add("group"))
                .bounds(x+(w-4)/2+4, y, w-(w-4)/2-4, 20).build());
    }

    private void initEffects(int x,int w) {
        var selected=selectedWidget(); if(selected==null) return;
        int y=60;
        y=effectChoice(x,y,w,"Blend","blendMode",com.fnfmod.client.lua.LuaLayerEffects.BLENDS.toArray(String[]::new));
        y=effectChoice(x,y,w,"Gradient","gradientType",new String[]{"none","linear","radial","angular"});
        String gradient=runtime.editorString(selectedId,"gradientType");
        if(!gradient.isBlank() && !gradient.equals("none")) {
            addColorPair(x,y,w,"Start color",(int)runtime.editorNumber(selectedId,"gradientColor1",0xFFFFFF),"gradientColor1",
                    "End color",(int)runtime.editorNumber(selectedId,"gradientColor2",0),"gradientColor2");y+=22;
            addPair(x,y,w,"Start alpha",runtime.editorNumber(selectedId,"gradientAlpha1",1),"gradientAlpha1",
                    "End alpha",runtime.editorNumber(selectedId,"gradientAlpha2",1),"gradientAlpha2");y+=22;
            addPair(x,y,w,"Angle",runtime.editorNumber(selectedId,"gradientAngle",0),"gradientAngle",
                    "Radius",runtime.editorNumber(selectedId,"gradientRadius",.5),"gradientRadius");y+=22;
            addPair(x,y,w,"Center X",runtime.editorNumber(selectedId,"gradientX",.5),"gradientX",
                    "Center Y",runtime.editorNumber(selectedId,"gradientY",.5),"gradientY");y+=22;
            y=effectText(x,y,w,"Stops: 0:#FFFFFF:1;1:#000000:0","gradientStops");
        }
        y=effectText(x,y,w,"Mask object ID (empty = none)","mask");
        y=effectChoice(x,y,w,"Mask reads","maskMode",new String[]{"alpha","luminance"});
        y=effectToggle(x,y,w,"Invert mask","maskInvert");
        y=effectToggle(x,y,w,"Use this only as a mask","maskOnly");
        y=effectToggle(x,y,w,"Click visible pixels only","maskHitTest");
        addPair(x,y,w,"Mask offset X",runtime.editorNumber(selectedId,"maskX",0),"maskX",
                "Mask offset Y",runtime.editorNumber(selectedId,"maskY",0),"maskY");y+=22;
        addPair(x,y,w,"Mask scale X",runtime.editorNumber(selectedId,"maskScaleX",1),"maskScaleX",
                "Mask scale Y",runtime.editorNumber(selectedId,"maskScaleY",1),"maskScaleY");y+=22;
        addPair(x,y,w,"Mask angle",runtime.editorNumber(selectedId,"maskAngle",0),"maskAngle",
                "Mask softness",runtime.editorNumber(selectedId,"maskSoftness",0),"maskSoftness");y+=22;
        y=effectChoice(x,y,w,"Clip","clipType",new String[]{"none","rect","circle","rounded"});
        addPair(x,y,w,"Clip X",runtime.editorNumber(selectedId,"clipX",0),"clipX",
                "Clip Y",runtime.editorNumber(selectedId,"clipY",0),"clipY");y+=22;
        addPair(x,y,w,"Clip width",runtime.editorNumber(selectedId,"clipWidth",1),"clipWidth",
                "Clip height",runtime.editorNumber(selectedId,"clipHeight",1),"clipHeight");y+=22;
        addPair(x,y,w,"Corner radius",runtime.editorNumber(selectedId,"clipRadius",.1),"clipRadius",
                "Edge softness",runtime.editorNumber(selectedId,"clipSoftness",0),"clipSoftness");y+=22;
        y=effectText(x,y,w,"Compositing group ID (empty = none)","group");
        EditBox parent=addRenderableWidget(new EditBox(font,x,y,w,18,Component.literal("Parent object ID")));
        parent.setHint(Component.literal("Parent object ID"));parent.setValue(runtime.editorString(selectedId,"parent"));y+=22;
        final EditBox parentField=parent;
        addRenderableWidget(Button.builder(Component.literal("Attach at center"),ignored->{
            checkpoint();if(runtime.editorParent(selectedId,parentField.getValue(),false)) {status="Attached · X/Y/Z now local to parent center";rebuildWidgets();}
            else status="Invalid parent: choose another object in the same space";
        }).bounds(x,y,(w-4)/2,20).build());
        addRenderableWidget(Button.builder(Component.literal("Keep position"),ignored->{
            checkpoint();if(runtime.editorParent(selectedId,parentField.getValue(),true)) rebuildWidgets();
            else status="Invalid parent: choose another object in the same space";
        }).bounds(x+(w-4)/2+4,y,w-(w-4)/2-4,20).build());y+=24;
        addRenderableWidget(Button.builder(Component.literal("Detach (keep position)"),ignored->{
            checkpoint();runtime.editorParent(selectedId,"",true);rebuildWidgets();
        }).bounds(x,y,w,20).build());
    }
    private int effectChoice(int x,int y,int w,String label,String field,String[] values) {
        String current=runtime.editorString(selectedId,field);
        if(current.isBlank()) current=values[0];
        addRenderableWidget(Button.builder(Component.literal(label+": "+current),ignored->cycleTextString(selectedId,field,values))
                .bounds(x,y,w,20).build());return y+24;
    }
    private int effectToggle(int x,int y,int w,String label,String field) {
        boolean enabled=runtime.editorBoolean(selectedId,field,false);
        addRenderableWidget(Button.builder(Component.literal(label+": "+(enabled?"On":"Off")),ignored->{
            checkpoint();runtime.editorToggle(selectedId,field);rebuildWidgets();
        }).bounds(x,y,w,20).build());return y+24;
    }
    private int effectText(int x,int y,int w,String hint,String field) {
        EditBox box=addRenderableWidget(new EditBox(font,x,y,w,18,Component.literal(hint)));
        box.setMaxLength(2048);box.setHint(Component.literal(hint));box.setValue(runtime.editorString(selectedId,field));
        box.setResponder(value->{checkpoint();runtime.editorSetString(selectedId,field,value);});return y+22;
    }

    private void initLayers(int x, int w) {
        List<MachineMenuRuntime.EditorWidget> values = new ArrayList<>(widgets());
        Collections.reverse(values); // Image editors show the front/top layer first.
        layerDisplayOrder.clear();
        int y = 60;
        int half = (w - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal(gridEnabled
                        ? "2D Grid: On" : "2D Grid: Off"), ignored -> {
            gridEnabled = !gridEnabled;
            status = gridEnabled ? "2D grid enabled · movement snaps to " + compact(gridSize) + " px"
                    : "2D grid disabled";
            rebuildWidgets();
        }).bounds(x, y, half, 20).build());
        EditBox gridScale = addRenderableWidget(new EditBox(font, x + half + 4, y,
                w - half - 4, 20, Component.literal("Grid size")));
        gridScale.setHint(Component.literal("Grid size (px)"));
        gridScale.setMaxLength(8);
        gridScale.setValue(compact(gridSize));
        gridScale.setResponder(text -> {
            try {
                double value = Double.parseDouble(text.trim());
                if (Double.isFinite(value)) gridSize = Math.max(1, Math.min(640, value));
            } catch (NumberFormatException ignored) {}
        });
        y = LAYERS_LIST_Y;
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            layerDisplayOrder.add(value.id());
            String prefix = selectedIds.contains(value.id()) ? "◆  " : "☰  ";
            AbstractWidget row = addRenderableWidget(Button.builder(Component.literal(prefix + value.id()), ignored -> {
                selectObject(value.id(), hasShiftDown()); rebuildWidgets();
            }).bounds(x, y + i * 22, w, 20).build());
            layerRows.put(value.id(), row);
            layerVisualY.putIfAbsent(value.id(), (double) (y + i * 22));
        }
        layerVisualY.keySet().retainAll(layerDisplayOrder);
        y += values.size() * 22 + 4;
        Button delete = addRenderableWidget(Button.builder(Component.literal("Delete selected"), ignored -> deleteSelected())
                .bounds(x, y, w, 20).build());
        delete.active = selectedWidget() != null;
    }

    private void initCamera(int x, int w) {
        int y = 60;
        addRenderableWidget(Button.builder(Component.literal(freeCameraMode
                        ? "Camera mode: Free" : "Camera mode: Normal"), ignored -> toggleCameraMode())
                .bounds(x, y, w, 20).build());
        y += 24;
        if (freeCameraMode) {
            Button help = addRenderableWidget(Button.builder(Component.literal(
                            "Navigation only · saved camera stays unchanged"), ignored -> {})
                    .bounds(x, y, w, 20).build());
            help.active = false;
            y += 24;
            Vec3 authored = camera.position();
            Button position = addRenderableWidget(Button.builder(Component.literal("Normal position  "
                            + compact(authored.x) + ", " + compact(authored.y) + ", " + compact(authored.z)), ignored -> {})
                    .bounds(x, y, w, 20).build());
            position.active = false;
            y += 22;
            Button rotation = addRenderableWidget(Button.builder(Component.literal("Normal rotation  "
                            + compact(camera.pitch()) + ", " + compact(camera.yaw()) + ", "
                            + compact(camera.roll())), ignored -> {})
                    .bounds(x, y, w, 20).build());
            rotation.active = false;
            y += 22;
            Button lens = addRenderableWidget(Button.builder(Component.literal("Normal FOV "
                            + compact(camera.fov()) + "  ·  Zoom " + compact(camera.zoom())), ignored -> {})
                    .bounds(x, y, w, 20).build());
            lens.active = false;
            y += 26;
            addRenderableWidget(Button.builder(Component.literal("Fullscreen preview  [Tab]"), ignored -> togglePreview())
                    .bounds(x, y, w, 20).build());
            return;
        }
        Vec3 p = camera.position();
        addPair(x, y, w, "X", p.x, "cameraX", "Y", p.y, "cameraY"); y += 22;
        addPair(x, y, w, "Z", p.z, "cameraZ", "Pitch", camera.pitch(), "cameraPitch"); y += 22;
        addPair(x, y, w, "Yaw", camera.yaw(), "cameraYaw", "Roll", camera.roll(), "cameraRoll"); y += 22;
        addField(x, y, w, "FOV", camera.fov(), "cameraFov"); y += 22;
        addField(x, y, w, "Zoom", camera.zoom(), "cameraZoom"); y += 26;
        addRenderableWidget(Button.builder(Component.literal("Reset to live camera"), ignored -> {
            checkpoint(); camera.reset(0, "linear", ""); rebuildWidgets();
        }).bounds(x, y, w, 20).build());
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Fullscreen preview  [Tab]"), ignored -> togglePreview())
                .bounds(x, y, w, 20).build());
    }

    private void initTweens(int x, int w) {
        int y = 60;
        String target = tweenCamera ? "Camera" : selectedIds.isEmpty() ? "No object selected"
                : selectedIds.size() == 1 ? selectedId : selectedIds.size() + " selected objects";
        addRenderableWidget(Button.builder(Component.literal("Target: " + target), ignored -> {
            tweenCamera = !tweenCamera; rebuildWidgets();
        }).bounds(x, y, w, 20).build()); y += 24;
        Button help = addRenderableWidget(Button.builder(Component.literal(
                "Capture start · edit values · capture destination"), ignored -> {})
                .bounds(x, y, w, 20).build());
        help.active = false; y += 24;
        int half = (w - 4) / 2;
        EditBox duration = addRenderableWidget(new EditBox(font, x, y, half, 18, Component.literal("Duration")));
        duration.setHint(Component.literal("Seconds")); duration.setValue(tweenDuration);
        duration.setResponder(text -> tweenDuration = text);
        EditBox easing = addRenderableWidget(new EditBox(font, x + half + 4, y, w - half - 4, 18, Component.literal("Easing")));
        easing.setHint(Component.literal("Easing")); easing.setValue(tweenEasing);
        easing.setResponder(text -> tweenEasing = text); y += 24;
        addRenderableWidget(Button.builder(Component.literal("Capture current keyframe"), ignored -> addTween())
                .bounds(x, y, w, 20).build()); y += 27;
        List<MachineMenuRuntime.EditorTween> tweens = runtime.editorTweens();
        if (!tweens.isEmpty()) {
            tweenIndex = Math.floorMod(tweenIndex, tweens.size());
            var tween = tweens.get(tweenIndex);
            boolean detectedLua = runtime.editorTweenIsDetected(tweenIndex);
            addRenderableWidget(Button.builder(Component.literal((tweenIndex + 1) + "/" + tweens.size() + "  "
                    + (detectedLua ? "Lua · " : "") + "at " + compact(tween.delay()) + "s · "
                    + compact(tween.duration()) + "s  "
                    + tween.target() + "." + tween.property()
                    + "  " + compact(tween.from()) + "→" + compact(tween.to())), ignored -> {
                tweenIndex = cycleIndex(tweenIndex, runtime.editorTweens().size()); rebuildWidgets();
            }).bounds(x, y, w, 20).build()); y += 24;
            Button remove = addRenderableWidget(Button.builder(Component.literal(detectedLua
                            ? "Detected in Lua — edit source" : "Remove selected keyframe"), ignored -> {
                checkpoint(); runtime.editorRemoveTween(tweenIndex); tweenIndex = 0; rebuildWidgets();
            }).bounds(x, y, w, 20).build());
            remove.active = !detectedLua;
        }
    }

    private void addTween() {
        try {
            double seconds = Double.parseDouble(tweenDuration.trim());
            List<String> targets = tweenCamera ? List.of("camera") : new ArrayList<>(selectedIds);
            if (targets.isEmpty()) {
                status = "Select at least one object, or switch the target to Camera";
                return;
            }
            checkpoint();
            int baselines = 0, changed = 0, valid = 0;
            for (String target : targets) {
                MachineMenuRuntime.EditorCaptureResult result = runtime.editorCaptureSnapshot(
                        target, seconds, tweenEasing);
                if (!result.valid()) continue;
                valid++;
                if (result.baseline()) baselines++;
                changed += result.changedProperties();
            }
            if (valid == 0) status = "Could not capture the selected target";
            else if (changed > 0) status = "Captured " + changed + " changed propert"
                    + (changed == 1 ? "y" : "ies") + " as Lua tweens";
            else if (baselines > 0) status = "Starting keyframe captured; change the object, then capture again";
            else status = "No tweenable properties changed since the previous keyframe";
            rebuildWidgets();
        } catch (NumberFormatException ignored) { status = "Keyframe duration must be a number"; }
    }

    private void addPair(int x, int y, int w, String a, double av, String ap,
                         String b, double bv, String bp) {
        int half = (w - 4) / 2;
        addField(x, y, half, a, av, ap);
        addField(x + half + 4, y, w - half - 4, b, bv, bp);
    }

    private void addColorPair(int x, int y, int w, String a, int av, String ap,
                              String b, int bv, String bp) {
        int half = (w - 4) / 2;
        addColorField(x, y, half, a, av, ap);
        addColorField(x + half + 4, y, w - half - 4, b, bv, bp);
    }

    private void addColorField(int x, int y, int w, String label, int value, String property) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal(label)));
        box.setHint(Component.literal(label));
        box.setMaxLength(32);
        box.setValue(String.format(java.util.Locale.ROOT, "%06X", value & 0xFFFFFF));
        box.setResponder(text -> {
            if (text == null || text.isBlank()) return;
            checkpoint();
            runtime.editorSetNumber(selectedId, property, PsychColor.parse(text) & 0xFFFFFF);
            if(property.startsWith("gradientColor")) runtime.editorSetString(selectedId,"gradientStops","");
        });
    }

    private void addField(int x, int y, int w, String label, double value, String property) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal(label)));
        box.setHint(Component.literal(label));
        box.setValue(compact(value));
        box.setResponder(text -> applyField(property, stripLabel(text)));
    }

    private void applyField(String property, String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            checkpoint();
            if (property.startsWith("camera")) {
                Vec3 p = camera.position();
                double x = property.equals("cameraX") ? value : p.x;
                double y = property.equals("cameraY") ? value : p.y;
                double z = property.equals("cameraZ") ? value : p.z;
                if (property.equals("cameraX") || property.equals("cameraY") || property.equals("cameraZ"))
                    camera.setPosition(x, y, z, 0, "linear", "");
                else if (property.equals("cameraFov")) camera.setFov(value);
                else if (property.equals("cameraZoom")) camera.setZoom(value, 0, "linear", "");
                else camera.setRotation(property.equals("cameraPitch") ? value : camera.pitch(),
                        property.equals("cameraYaw") ? value : camera.yaw(),
                        property.equals("cameraRoll") ? value : camera.roll(), 0, "linear", "");
            } else if (selectedWidget() != null) runtime.editorSetNumber(selectedId, property, value);
        } catch (NumberFormatException ignored) {}
    }

    private void cycleTextString(String id, String property, String[] values) {
        checkpoint();
        String current = runtime.editorString(id, property);
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(current)) { index = i; break; }
        runtime.editorSetString(id, property, values[cycleIndex(index, values.length)]);
        rebuildWidgets();
    }

    private static int cycleIndex(int current, int size) {
        if (size <= 0) return 0;
        return Math.floorMod(current + (hasShiftDown() ? -1 : 1), size);
    }

    private HistoryEntry currentHistoryEntry() {
        return new HistoryEntry(runtime.editorCaptureHistory(), new ArrayList<>(selectedIds), selectedId);
    }

    private void checkpoint() {
        if (runtime == null) return;
        undoHistory.addLast(currentHistoryEntry());
        while (undoHistory.size() > HISTORY_LIMIT) undoHistory.removeFirst();
        redoHistory.clear();
    }

    private void restoreHistory(HistoryEntry entry, String message) {
        cancelWorldTransform();
        runtime.editorRestoreHistory(entry.runtime());
        selectedIds.clear(); selectedIds.addAll(entry.selection());
        selectedId = entry.primary(); selectFallback();
        tweenIndex = 0;
        status = message;
        rebuildWidgets();
    }

    private void undo() {
        if (undoHistory.isEmpty() || runtime == null) { status = "Nothing to undo"; return; }
        redoHistory.addLast(currentHistoryEntry());
        restoreHistory(undoHistory.removeLast(), "Undo");
    }

    private void redo() {
        if (redoHistory.isEmpty() || runtime == null) { status = "Nothing to redo"; return; }
        undoHistory.addLast(currentHistoryEntry());
        restoreHistory(redoHistory.removeLast(), "Redo");
    }

    private static String stripLabel(String text) {
        int colon = text.indexOf(':');
        return colon >= 0 ? text.substring(colon + 1) : text;
    }

    private void switchTab(Tab value) { cancelWorldTransform(); tab = value; transform = Transform.NONE; rebuildWidgets(); }
    private List<MachineMenuRuntime.EditorWidget> widgets() { return runtime == null ? List.of() : runtime.editorWidgets(); }
    private MachineMenuRuntime.EditorWidget selectedWidget() { return runtime == null ? null : runtime.editorWidget(selectedId); }
    private void selectFallback() {
        selectedIds.removeIf(id -> runtime == null || runtime.editorWidget(id) == null);
        if (selectedWidget() == null) selectedId = selectedIds.isEmpty()
                ? (widgets().isEmpty() ? "" : widgets().getLast().id())
                : selectedIds.iterator().next();
        if (!selectedId.isBlank()) selectedIds.add(selectedId);
    }

    /** Commits the Objects-tab ID field as one undoable structural edit. */
    private void commitObjectName() {
        if (objectNameField == null || runtime == null || objectNameOriginal.isBlank()) return;
        String candidate = objectNameField.getValue() == null ? "" : objectNameField.getValue().trim();
        String oldId = objectNameOriginal;
        if (candidate.equals(oldId)) return;
        if (candidate.isEmpty() || !candidate.matches("[A-Za-z_][A-Za-z0-9_]*")
                || candidate.length() > 64
                || runtime.editorWidget(candidate) != null) {
            objectNameField.setValue(oldId);
            status = candidate.isEmpty() ? "Object name cannot be empty"
                    : runtime.editorWidget(candidate) != null
                    ? "An object named " + candidate + " already exists"
                    : "Use a Lua name: letters, numbers, and underscores";
            return;
        }
        checkpoint();
        if (!runtime.editorRename(oldId, candidate)) {
            objectNameField.setValue(oldId);
            status = "Could not rename " + oldId;
            return;
        }
        if (selectedIds.remove(oldId)) selectedIds.add(candidate);
        if (selectedId.equals(oldId)) selectedId = candidate;
        objectNameOriginal = candidate;
        status = "Renamed " + oldId + " to " + candidate;
    }

    private void selectObject(String id, boolean toggle) {
        if (!toggle) selectedIds.clear();
        if (id == null || id.isBlank()) {
            if (!toggle) selectedId = "";
            else if (!selectedIds.contains(selectedId)) selectFallback();
            return;
        }
        if (toggle && selectedIds.contains(id)) {
            selectedIds.remove(id);
            if (id.equals(selectedId)) selectedId = selectedIds.isEmpty() ? "" : selectedIds.iterator().next();
        } else {
            selectedIds.add(id);
            selectedId = id;
        }
    }
    private void clickSelectObject(String id, boolean toggle) {
        if (!toggle && id != null && selectedIds.size() > 1 && selectedIds.contains(id)) {
            selectedId = id;
            return;
        }
        selectObject(id, toggle);
    }
    private void add(String kind) {
        checkpoint();
        var added = runtime.editorAdd(kind);
        if (added != null) selectObject(added.id(), false);
        rebuildWidgets();
    }
    private void deleteSelected() {
        checkpoint();
        boolean changed = false;
        for (String id : List.copyOf(selectedIds)) changed |= runtime.editorDelete(id);
        if (!changed && !selectedId.isBlank()) changed = runtime.editorDelete(selectedId);
        if (changed) {
            selectedIds.clear(); selectedId = ""; selectFallback(); rebuildWidgets();
        } else if (!undoHistory.isEmpty()) undoHistory.removeLast();
    }
    private void moveLayer(int amount) { if (runtime.editorMoveLayer(selectedId, amount)) rebuildWidgets(); }

    private boolean cursorOverObjectProperties() {
        if (previewMode || tab != Tab.OBJECTS || selectedWidget() == null) return false;
        double mouseX = currentMouseX(), mouseY = currentMouseY();
        if (mouseX < panelX() || mouseY < 58 || mouseY >= height - 50) return false;
        for (AbstractWidget property : objectPropertyWidgets) {
            if (property.visible && property.isMouseOver(mouseX, mouseY)) return true;
        }
        return false;
    }

    private void copyObjectProperties() {
        var copied = runtime == null ? null : runtime.editorCopyProperties(selectedId);
        if (copied == null) {
            status = "Select an object before copying properties";
            return;
        }
        copiedObjectProperties = copied;
        copiedObjectName = selectedId;
        status = "Copied properties from " + selectedId + " · Ctrl+V over another object's properties";
    }

    private void pasteObjectProperties() {
        if (runtime == null || selectedWidget() == null) {
            status = "Select a destination object first";
            return;
        }
        if (copiedObjectProperties == null) {
            status = "No copied object properties";
            return;
        }
        checkpoint();
        int changed = runtime.editorPasteProperties(selectedId, copiedObjectProperties);
        if (changed <= 0) {
            if (!undoHistory.isEmpty()) undoHistory.removeLast();
            status = "The properties already match " + copiedObjectName;
            return;
        }
        status = "Pasted " + changed + " properties from " + copiedObjectName + " to " + selectedId;
        rebuildWidgets();
    }

    /** Keeps tab content between the tab row and the fixed Save/Back row. */
    private void layoutScrollableContent(int firstChild) {
        int top = 58, bottom = Math.max(top + 20, height - 50);
        int rawBottom = top;
        for (int i = firstChild; i < children().size(); i++) {
            if (children().get(i) instanceof AbstractWidget widget) {
                panelWidgets.add(widget);
                panelWidgetBaseY.put(widget, widget.getY());
                rawBottom = Math.max(rawBottom, widget.getY() + widget.getHeight());
            }
        }
        panelMaxScroll = Math.max(0, rawBottom - bottom);
        double scroll = Math.max(0, Math.min(panelMaxScroll, panelScroll.getOrDefault(tab, 0.0)));
        panelScroll.put(tab, scroll);
        panelScrollTarget.put(tab, Math.max(0, Math.min(panelMaxScroll,
                panelScrollTarget.getOrDefault(tab, scroll))));
        applyPanelScroll();
    }

    private void applyPanelScroll() {
        int top = 58, bottom = Math.max(top + 20, height - 50);
        int offset = (int) Math.round(panelScroll.getOrDefault(tab, 0.0));
        for (AbstractWidget widget : panelWidgets) {
            Integer base = panelWidgetBaseY.get(widget);
            if (base == null) continue;
            widget.setY(base - offset);
            widget.visible = widget.getY() + widget.getHeight() > top && widget.getY() < bottom;
        }
    }

    private void scrollPanelTo(double target) {
        updatePanelScroll();
        double current = panelScroll.getOrDefault(tab, 0.0);
        panelScrollFrom.put(tab, current);
        panelScrollTarget.put(tab, Math.max(0, Math.min(panelMaxScroll, target)));
        panelScrollStarted = System.nanoTime();
        panelScrollTween = Math.abs(panelScrollTarget.get(tab) - current) > 0.01;
    }

    private void updatePanelScroll() {
        if (!panelScrollTween) return;
        double progress = (System.nanoTime() - panelScrollStarted) / 200_000_000.0;
        double from = panelScrollFrom.getOrDefault(tab, 0.0);
        double target = panelScrollTarget.getOrDefault(tab, from);
        if (progress >= 1) {
            panelScroll.put(tab, target);
            panelScrollTween = false;
        } else panelScroll.put(tab, from + (target - from) * Easing.apply("expoOut", progress));
        applyPanelScroll();
    }

    @Override public void tick() {
        if (runtime != null) runtime.tick();
        if (freeCameraMode) camera.tickDetached();
    }

    /** Per-render-frame polling gives the editor the same smooth motion as gameplay free cam. */
    private void updateCameraNavigation() {
        long now = System.nanoTime();
        double dt = lastCameraFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastCameraFrameNanos) / 1_000_000_000.0);
        lastCameraFrameNanos = now;
        if (!viewportFocused || minecraft == null || getFocused() instanceof EditBox || worldTransforming()
                || !cameraDragging || cameraOrbiting) return;
        long window = minecraft.getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            cameraDragging = false;
            return;
        }
        double speed = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) ? 0.5 : 2.4;
        double forward = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_W) ? 1 : 0)
                - (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_S) ? 1 : 0);
        double strafe = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_D) ? 1 : 0)
                - (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_A) ? 1 : 0);
        double vertical = (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_E) ? 1 : 0)
                - (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Q) ? 1 : 0);
        double length = Math.sqrt(forward * forward + strafe * strafe + vertical * vertical);
        if (length > 0) viewCamera().moveRelative(forward / length, strafe / length,
                vertical / length, speed * dt);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_Z) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) redo(); else undo();
            return true;
        }
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_Y) {
            redo(); return true;
        }
        // A focused text field owns the ordinary OS clipboard shortcuts. Object
        // property copy/paste is only active while no field is being edited.
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && !(getFocused() instanceof EditBox) && cursorOverObjectProperties()) {
            if (key == GLFW.GLFW_KEY_C) { copyObjectProperties(); return true; }
            if (key == GLFW.GLFW_KEY_V) { pasteObjectProperties(); return true; }
        }
        if (getFocused() instanceof EditBox box
                && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            if (box == objectNameField) commitObjectName();
            box.setFocused(false);
            setFocused(null);
            viewportFocused = false;
            return true;
        }
        if (worldTransforming() && key == GLFW.GLFW_KEY_ESCAPE) { cancelWorldTransform(); return true; }
        if (key == GLFW.GLFW_KEY_TAB && !(getFocused() instanceof EditBox)) { togglePreview(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE && previewMode) { togglePreview(); return true; }
        if (getFocused() instanceof EditBox) return super.keyPressed(key, scan, modifiers);
        if (key == GLFW.GLFW_KEY_KP_DECIMAL) { focusSelectedWorldObject(); return true; }
        // When the viewport owns input these are navigation keys. In particular,
        // S must never also enter the selected object's Scale transform.
        if (viewportFocused && cameraDragging && !cameraOrbiting && !worldTransforming()
                && (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_A
                || key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_D || key == GLFW.GLFW_KEY_Q
                || key == GLFW.GLFW_KEY_E)) return true;
        var selected = selectedWidget();
        if (selected != null) {
            boolean world = selected.space().equalsIgnoreCase("world");
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
            if (worldTransforming() && key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!transformNumeric.isEmpty()) transformNumeric = transformNumeric.substring(0, transformNumeric.length() - 1);
                updateWorldTransform(currentMouseX(), currentMouseY()); return true;
            }
            if (worldTransforming() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
                confirmWorldTransform(); return true;
            }
            if (worldTransforming() && (key == GLFW.GLFW_KEY_X || key == GLFW.GLFW_KEY_Y || key == GLFW.GLFW_KEY_Z)) {
                int next = key == GLFW.GLFW_KEY_X ? 1 : key == GLFW.GLFW_KEY_Y ? 2 : 3;
                if (!world && (next == 3 || transform == Transform.ROTATE)) {
                    status = transform == Transform.ROTATE
                            ? "2D rotation uses Angle" : "2D objects have no Z axis";
                    return true;
                }
                if (transform == Transform.ROTATE) {
                    restoreRotationPreview();
                    transformTrackball = false;
                    transformPlane = false;
                    if (transformAxis == next) transformRotationGlobal = !transformRotationGlobal;
                    else {
                        transformAxis = next;
                        transformRotationGlobal = false;
                    }
                    resetRotationInput();
                    updateWorldTransform(currentMouseX(), currentMouseY());
                    return true;
                }
                boolean nextPlane = shift && transform != Transform.ROTATE;
                if (transformAxis == next && transformPlane == nextPlane) { transformAxis = 0; transformPlane = false; }
                else { transformAxis = next; transformPlane = nextPlane; }
                updateWorldTransform(currentMouseX(), currentMouseY()); return true;
            }
            if (key == GLFW.GLFW_KEY_G) {
                if (alt) resetWorldProperty("Position");
                else beginWorldTransform(Transform.MOVE);
            }
            else if (key == GLFW.GLFW_KEY_R) {
                if (alt) resetWorldProperty("Rotation");
                else {
                    if (worldTransforming() && transform == Transform.ROTATE) transformTrackball = !transformTrackball;
                    else beginWorldTransform(Transform.ROTATE);
                    if (!world) transformTrackball = false;
                }
            }
            else if (key == GLFW.GLFW_KEY_S) {
                if (alt) resetWorldProperty("Scale");
                else beginWorldTransform(Transform.SCALE);
            }
            else if (key == GLFW.GLFW_KEY_DELETE) { deleteSelected(); return true; }
            else if (key == GLFW.GLFW_KEY_ESCAPE && transform != Transform.NONE) transform = Transform.NONE;
            else return super.keyPressed(key, scan, modifiers);
            status = worldTransforming() ? worldTransformStatus()
                    : transform == Transform.NONE ? "Transform cancelled" : transform + " active";
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void focusSelectedWorldObject() {
        var selected = selectedWidget();
        if (selected == null || !selected.space().equalsIgnoreCase("world") || runtime == null) {
            status = "Numpad Decimal focus needs a selected 3D object";
            return;
        }
        Vec3 target = runtime.editorWorldPosition(selected.id(), worldOrigin, Direction.NORTH);
        double halfSize = runtime.editorWorldSizeBlocks(selected.id()) * 0.5;
        MenuCameraController view = viewCamera();
        double fov = Math.toRadians(view.effectiveFov());
        double distance = Math.max(2.5, Math.min(40.0, halfSize / Math.tan(fov * 0.5) / 0.6));
        if (!freeCameraMode) checkpoint();
        view.focus(target, distance, 0.3, "expoOut");
        status = "Focusing " + selected.id();
    }

    @Override public boolean charTyped(char codePoint, int modifiers) {
        if (worldTransforming() && ((codePoint >= '0' && codePoint <= '9') || codePoint == '-'
                || codePoint == '+' || codePoint == '.' || codePoint == ',')) {
            if (codePoint == '-') transformNumeric = transformNumeric.startsWith("-")
                    ? transformNumeric.substring(1) : "-" + transformNumeric;
            else if (codePoint == '+') transformNumeric = transformNumeric.startsWith("-")
                    ? transformNumeric.substring(1) : transformNumeric;
            else if ((codePoint == '.' || codePoint == ',') && !transformNumeric.contains("."))
                transformNumeric += transformNumeric.isEmpty() || transformNumeric.equals("-") ? "0." : ".";
            else if (codePoint >= '0' && codePoint <= '9') transformNumeric += codePoint;
            updateWorldTransform(currentMouseX(), currentMouseY());
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void togglePreview() {
        previewMode = !previewMode;
        viewportFocused = previewMode;
        rebuildWidgets();
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (objectNameField != null && objectNameField.isFocused()
                && !objectNameField.isMouseOver(x, y)) commitObjectName();
        if (worldTransforming()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) confirmWorldTransform();
            else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) cancelWorldTransform();
            return true;
        }
        if (!previewMode && tab == Tab.LAYERS && button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && x >= panelX()) {
            for (var entry : layerRows.entrySet()) {
                AbstractWidget row = entry.getValue();
                if (row.visible && row.isMouseOver(x, y)) {
                    clickSelectObject(entry.getKey(), hasShiftDown());
                    draggedLayer = entry.getKey();
                    layerPointerY = y;
                    layerGrabOffset = y - row.getY();
                    return true;
                }
            }
        }
        if (insidePreview(x, y)) {
            viewportFocused = true;
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                if (!freeCameraMode) checkpoint();
                cameraDragging = true;
                cameraOrbiting = true;
                cameraIgnoreWarpMotion = false;
                // MMB is viewport navigation only. Object-targeted focusing belongs
                // exclusively to Numpad Decimal, matching gameplay Free Cam.
                viewCamera().beginOrbit(null);
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                if (!freeCameraMode) checkpoint();
                cameraDragging = true;
                cameraOrbiting = false;
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                String hit = runtime == null ? "" : runtime.editorHitTest(x, y,
                        actualPreviewX(), actualPreviewY(), previewW(), previewH());
                boolean additive = hasShiftDown();
                clickSelectObject(hit, additive);
                var widget = selectedWidget();
                objectDragging = !additive && widget != null && !widget.space().equalsIgnoreCase("world");
                if (objectDragging && transform == Transform.NONE) {
                    transform = Transform.MOVE;
                    transformModal = false;
                    directDragCheckpoint = false;
                    gridDragRemainderX = gridDragRemainderY = 0;
                }
                if (!previewMode) rebuildWidgets();
                return true;
            }
        } else viewportFocused = false;
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        double precision = hasShiftDown() ? 0.2 : 1.0;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !draggedLayer.isBlank() && tab == Tab.LAYERS) {
            layerPointerY = y;
            int offset = (int) Math.round(panelScroll.getOrDefault(tab, 0.0));
            int displayIndex = (int) Math.round((y + offset - LAYERS_LIST_Y - 10) / 22.0);
            displayIndex = Math.max(0, Math.min(Math.max(0, layerRows.size() - 1), displayIndex));
            int oldIndex = layerDisplayOrder.indexOf(draggedLayer);
            if (oldIndex >= 0 && oldIndex != displayIndex) {
                if (!layerDragCheckpoint) { checkpoint(); layerDragCheckpoint = true; }
                layerDisplayOrder.remove(oldIndex);
                layerDisplayOrder.add(displayIndex, draggedLayer);
                runtime.editorMoveLayerToDisplayIndex(draggedLayer, displayIndex);
            }
            return true;
        }
        if ((button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) && cameraDragging) {
            if (cameraIgnoreWarpMotion) { cameraIgnoreWarpMotion = false; return true; }
            if (cameraOrbiting && button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                MenuCameraController view = viewCamera();
                if (hasShiftDown()) view.panOrbit(dx, dy, previewH(), view.effectiveFov());
                else if (hasControlDown()) view.dollyOrbit(-dy * 0.06);
                else view.orbit(dx, dy);
                wrapCameraCursor(x, y);
            } else viewCamera().turn(dx * 0.32 * precision, dy * 0.32 * precision);
            return true;
        }
        var widget = selectedWidget();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && objectDragging && widget != null) {
            if (transformIgnoreWarpMotion) {
                transformIgnoreWarpMotion = false;
                transformLastMouseX = x; transformLastMouseY = y;
                return true;
            }
            double scale = Math.max(1.0e-6, Math.min(previewW() / 1280.0, previewH() / 720.0));
            if (!directDragCheckpoint) { checkpoint(); directDragCheckpoint = true; }
            double cx = dx / scale * precision, cy = dy / scale * precision;
            if (gridEnabled && transform == Transform.MOVE) {
                gridDragRemainderX += cx;
                gridDragRemainderY += cy;
                double snappedX = Math.rint(gridDragRemainderX / gridSize) * gridSize;
                double snappedY = Math.rint(gridDragRemainderY / gridSize) * gridSize;
                gridDragRemainderX -= snappedX;
                gridDragRemainderY -= snappedY;
                cx = snappedX;
                cy = snappedY;
            }
            switch (transform) {
                case MOVE -> {
                    for (String id : selectedIds) {
                        var value = runtime.editorWidget(id);
                        if (value == null || value.space().equalsIgnoreCase("world")) continue;
                        boolean parented=runtime.editorIsParented(id);
                        Vec3 local=runtime.editorParentDelta(id,new Vec3(cx,cy,0));
                        runtime.editorSetNumber(id, "x", !parented&&normalized(value.x()) ? value.x() + local.x / 1280.0 : value.x() + local.x);
                        runtime.editorSetNumber(id, "y", !parented&&normalized(value.y()) ? value.y() + local.y / 720.0 : value.y() + local.y);
                    }
                }
                case ROTATE -> {
                    for (String id : selectedIds) {
                        var value = runtime.editorWidget(id);
                        if (value != null && !value.space().equalsIgnoreCase("world"))
                            runtime.editorSetNumber(id, "angle", value.angle() + dx * precision);
                    }
                }
                case SCALE -> {
                    double factor = Math.max(0.05, 1 + dx * 0.01 * precision);
                    for (String id : selectedIds) {
                        var value = runtime.editorWidget(id);
                        if (value == null || value.space().equalsIgnoreCase("world")) continue;
                        runtime.editorSetNumber(id, "width", value.width() * factor);
                        runtime.editorSetNumber(id, "height", value.height() * factor);
                    }
                }
                default -> {}
            }
            wrapTransformCursor(x, y);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public void mouseMoved(double x, double y) {
        if (worldTransforming()) {
            if (transformIgnoreWarpMotion) {
                transformIgnoreWarpMotion = false;
                transformLastMouseX = x; transformLastMouseY = y;
                super.mouseMoved(x, y);
                return;
            }
            updateWorldTransform(x, y);
            wrapTransformCursor(x, y);
        }
        super.mouseMoved(x, y);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            boolean finishedDirectDrag = objectDragging && !transformModal;
            objectDragging = false;
            boolean finishedLayerDrag = !draggedLayer.isBlank();
            draggedLayer = "";
            directDragCheckpoint = false;
            layerDragCheckpoint = false;
            gridDragRemainderX = gridDragRemainderY = 0;
            if (finishedDirectDrag) transform = Transform.NONE;
            if (finishedLayerDrag) rebuildWidgets();
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            cameraDragging = false;
            cameraOrbiting = false;
            cameraIgnoreWarpMotion = false;
            viewCamera().endOrbit();
        }
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (previewMode && insidePreview(x, y)) {
            if (runtime != null && runtime.loaded()) runtime.mouseScrolledInViewport(x, y, sx, sy,
                    actualPreviewX(), actualPreviewY(), previewW(), previewH());
            return true;
        }
        if (worldTransforming()) return true;
        if (!previewMode && x >= panelX() && y >= 56 && y < height - 48 && panelMaxScroll > 0) {
            double target = panelScrollTarget.getOrDefault(tab, panelScroll.getOrDefault(tab, 0.0));
            scrollPanelTo(target - sy * 22.0);
            return true;
        }
        if (insidePreview(x, y)) {
            MenuCameraController view = viewCamera();
            double yaw = Math.toRadians(view.yaw()), pitch = Math.toRadians(view.pitch());
            Vec3 look = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch));
            if (!freeCameraMode) checkpoint();
            view.move(look.scale(sy * (hasShiftDown() ? 0.1 : 0.5)));
            viewportFocused = true;
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    private boolean worldTransforming() {
        var widget = selectedWidget();
        return transformModal && transform != Transform.NONE && widget != null;
    }

    private void beginWorldTransform(Transform next) {
        var widget = selectedWidget();
        if (widget == null) return;
        checkpoint();
        transform = next;
        transformModal = true;
        transformAxis = 0;
        transformPlane = false;
        transformTrackball = false;
        transformRotationGlobal = false;
        transformIgnoreWarpMotion = false;
        transformNumeric = "";
        transformLastMouseX = currentMouseX();
        transformLastMouseY = currentMouseY();
        transformMouseDx = transformMouseDy = transformDegrees = 0;
        baseX = widget.x(); baseY = widget.y(); baseZ = widget.z();
        baseWidth = widget.width(); baseHeight = widget.height();
        baseAngle = widget.angle(); baseRotationX = widget.rotationX(); baseRotationY = widget.rotationY();
        transformBases.clear();
        for (String id : selectedIds) {
            var value = runtime.editorWidget(id);
            if (value != null && value.space().equalsIgnoreCase(widget.space()))
                transformBases.put(id, new TransformBase(value.x(), value.y(), value.z(),
                        value.width(), value.height(), value.angle(), value.rotationX(),
                        value.rotationY(), value.space()));
        }
        if (!transformBases.containsKey(widget.id())) transformBases.put(widget.id(),
                new TransformBase(baseX, baseY, baseZ, baseWidth, baseHeight,
                        baseAngle, baseRotationX, baseRotationY, widget.space()));
        status = worldTransformStatus();
    }

    private void confirmWorldTransform() {
        if (!worldTransforming()) return;
        transform = Transform.NONE;
        transformModal = false;
        transformAxis = 0;
        transformPlane = false;
        transformTrackball = false;
        transformRotationGlobal = false;
        transformIgnoreWarpMotion = false;
        transformNumeric = "";
        transformBases.clear();
        status = "Transform confirmed";
        if (!previewMode) rebuildWidgets();
    }

    private void cancelWorldTransform() {
        if (!worldTransforming()) return;
        for (var entry : transformBases.entrySet()) {
            TransformBase value = entry.getValue(); String id = entry.getKey();
            runtime.editorSetNumber(id, "x", value.x()); runtime.editorSetNumber(id, "y", value.y());
            runtime.editorSetNumber(id, "z", value.z()); runtime.editorSetNumber(id, "width", value.width());
            runtime.editorSetNumber(id, "height", value.height()); runtime.editorSetNumber(id, "angle", value.angle());
            runtime.editorSetNumber(id, "rotationX", value.rotationX());
            runtime.editorSetNumber(id, "rotationY", value.rotationY());
        }
        transform = Transform.NONE;
        transformModal = false;
        transformAxis = 0;
        transformPlane = false;
        transformTrackball = false;
        transformRotationGlobal = false;
        transformIgnoreWarpMotion = false;
        transformNumeric = "";
        transformBases.clear();
        status = "Transform cancelled";
        if (!previewMode) rebuildWidgets();
    }

    /** Restores every selected object's rotation to its pre-transform snapshot. */
    private void restoreRotationPreview() {
        if (runtime == null) return;
        for (var entry : transformBases.entrySet()) {
            TransformBase value = entry.getValue();
            runtime.editorSetNumber(entry.getKey(), "angle", value.angle());
            runtime.editorSetNumber(entry.getKey(), "rotationX", value.rotationX());
            runtime.editorSetNumber(entry.getKey(), "rotationY", value.rotationY());
        }
    }

    private void resetWorldProperty(String property) {
        var widget = selectedWidget();
        if (widget == null) return;
        cancelWorldTransform();
        checkpoint();
        for (String id : selectedIds) {
            var value = runtime.editorWidget(id);
            if (value == null || !value.space().equalsIgnoreCase(widget.space())) continue;
            boolean world = value.space().equalsIgnoreCase("world");
            switch (property) {
                case "Position" -> {
                    runtime.editorSetNumber(id, "x", world ? 0 : 0.5);
                    runtime.editorSetNumber(id, "y", world ? 0 : 0.5);
                    if (world) runtime.editorSetNumber(id, "z", 0);
                }
                case "Rotation" -> {
                    runtime.editorSetNumber(id, "angle", 0);
                    if (world) {
                        runtime.editorSetNumber(id, "rotationX", 0);
                        runtime.editorSetNumber(id, "rotationY", 0);
                    }
                }
                case "Scale" -> {
                    runtime.editorSetNumber(id, "width", world ? 64 : 1);
                    runtime.editorSetNumber(id, "height", world ? 64 : 1);
                }
            }
        }
        status = property + " reset";
        if (!previewMode) rebuildWidgets();
    }

    private void updateWorldTransform(double mouseX, double mouseY) {
        var widget = selectedWidget();
        if (!worldTransforming() || widget == null || minecraft == null) return;
        double fine = hasShiftDown() ? 0.2 : 1.0;
        double stepX = mouseX - transformLastMouseX;
        double stepY = mouseY - transformLastMouseY;
        transformMouseDx += stepX * fine;
        transformMouseDy += stepY * fine;
        boolean world = widget.space().equalsIgnoreCase("world");
        if (transform == Transform.ROTATE) {
            double centerX = actualPreviewX() + previewW() * 0.5;
            double centerY = actualPreviewY() + previewH() * 0.5;
            if (!world) {
                MachineMenuRuntime.EditorBounds bounds = runtime.editorBounds(selectedId,
                        actualPreviewX(), actualPreviewY(), previewW(), previewH());
                if (bounds != null) {
                    centerX = (bounds.left() + bounds.right()) * 0.5;
                    centerY = (bounds.top() + bounds.bottom()) * 0.5;
                }
            }
            double previous = Math.atan2(transformLastMouseY - centerY, transformLastMouseX - centerX);
            double current = Math.atan2(mouseY - centerY, mouseX - centerX);
            double degrees = Math.toDegrees(current - previous);
            if (degrees > 180) degrees -= 360;
            else if (degrees < -180) degrees += 360;
            transformDegrees += degrees * fine;
        }
        transformLastMouseX = mouseX;
        transformLastMouseY = mouseY;
        boolean numeric = !transformNumeric.isEmpty() && !transformNumeric.equals("-");
        double entered = numericValue();
        boolean snap = hasControlDown();

        switch (transform) {
            case MOVE -> { if (world) updateWorldMove(numeric, entered, snap); else updateScreenMove(numeric, entered, snap); }
            case ROTATE -> { if (world) updateWorldRotation(numeric, entered, snap); else updateScreenRotation(numeric, entered, snap); }
            case SCALE -> { if (world) updateWorldScale(numeric, entered, snap); else updateScreenScale(numeric, entered, snap); }
            default -> {}
        }
        applyGroupedTransform(widget);
        status = worldTransformStatus();
    }

    private void applyGroupedTransform(MachineMenuRuntime.EditorWidget primary) {
        if (transformBases.size() <= 1) return;
        TransformBase primaryBase = transformBases.get(primary.id());
        var current = runtime.editorWidget(primary.id());
        if (primaryBase == null || current == null) return;
        double dx = current.x() - primaryBase.x(), dy = current.y() - primaryBase.y();
        double dz = current.z() - primaryBase.z(), da = current.angle() - primaryBase.angle();
        double drx = current.rotationX() - primaryBase.rotationX();
        double dry = current.rotationY() - primaryBase.rotationY();
        double widthFactor = primaryBase.width() == 0 ? 1 : current.width() / primaryBase.width();
        double heightFactor = primaryBase.height() == 0 ? 1 : current.height() / primaryBase.height();
        for (var entry : transformBases.entrySet()) {
            if (entry.getKey().equals(primary.id())) continue;
            String id = entry.getKey(); TransformBase base = entry.getValue();
            switch (transform) {
                case MOVE -> {
                    runtime.editorSetNumber(id, "x", round(base.x() + dx));
                    runtime.editorSetNumber(id, "y", round(base.y() + dy));
                    runtime.editorSetNumber(id, "z", round(base.z() + dz));
                }
                case ROTATE -> {
                    if (!transformTrackball && transformAxis != 0) {
                        applyAxisRotation(id, base, transformAxis, currentAxisRotationAmount());
                    } else {
                        runtime.editorSetNumber(id, "angle", round(base.angle() + da));
                        runtime.editorSetNumber(id, "rotationX", round(base.rotationX() + drx));
                        runtime.editorSetNumber(id, "rotationY", round(base.rotationY() + dry));
                    }
                }
                case SCALE -> {
                    runtime.editorSetNumber(id, "width", round(Math.max(1, base.width() * widthFactor)));
                    runtime.editorSetNumber(id, "height", round(Math.max(1, base.height() * heightFactor)));
                }
                default -> {}
            }
        }
    }

    private void updateWorldMove(boolean numeric, double entered, boolean snap) {
        net.minecraft.client.Camera live = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraRight = new Vec3(live.getLeftVector()).scale(-1).normalize();
        Vec3 cameraUp = new Vec3(live.getUpVector()).normalize();
        Vec3 cameraForward = new Vec3(live.getLookVector()).normalize();
        boolean absolute = usesMinecraftCoordinates();
        Vec3 start = localToWorld(baseX, baseY, baseZ, absolute);
        if(runtime.editorIsParented(selectedId)) {
            Vec3 resolved=runtime.editorWorldPosition(selectedId,worldOrigin,Direction.NORTH);
            if(resolved!=null) start=resolved;
        }
        Vec3 fromCamera = start.subtract(live.getPosition());
        double depth = fromCamera.dot(cameraForward);
        if (depth < 0.05) depth = Math.max(0.05, fromCamera.length());
        double fov = viewCamera().effectiveFov();
        double worldPerPixel = 2 * depth * Math.tan(Math.toRadians(fov * 0.5)) / Math.max(1, previewH());
        Vec3 delta = cameraRight.scale(transformMouseDx * worldPerPixel)
                .add(cameraUp.scale(-transformMouseDy * worldPerPixel));
        delta = constrainWorldMove(delta, cameraRight, cameraUp, transformMouseDy, worldPerPixel);
        if (numeric) {
            Vec3 direction = transformAxis != 0 && !transformPlane ? worldAxis(transformAxis, absolute) : delta;
            if (direction.lengthSqr() < 1.0e-10) direction = cameraRight;
            delta = direction.normalize().scale(entered);
        }
        Direction forward = Direction.NORTH;
        Direction right = forward.getCounterClockWise();
        if(runtime.editorIsParented(selectedId)) {
            delta=runtime.editorParentDelta(selectedId,delta);
            runtime.editorSetNumber(selectedId,"x",round(baseX+delta.x));
            runtime.editorSetNumber(selectedId,"y",round(baseY+delta.y));
            runtime.editorSetNumber(selectedId,"z",round(baseZ+delta.z));return;
        }
        double x = absolute ? baseX + delta.x
                : baseX + delta.x * right.getStepX() + delta.z * right.getStepZ();
        double y = baseY + delta.y;
        double z = absolute ? baseZ + delta.z
                : baseZ + delta.x * forward.getStepX() + delta.z * forward.getStepZ();
        if (snap && !numeric) { x = Math.rint(x); y = Math.rint(y); z = Math.rint(z); }
        runtime.editorSetNumber(selectedId, "x", round(x));
        runtime.editorSetNumber(selectedId, "y", round(y));
        runtime.editorSetNumber(selectedId, "z", round(z));
    }

    private void updateWorldRotation(boolean numeric, double entered, boolean snap) {
        double sensitivity = 0.5;
        if (transformTrackball) {
            double x = baseRotationX + (numeric ? -entered : -transformMouseDy * sensitivity);
            double y = baseRotationY + (numeric ? entered : transformMouseDx * sensitivity);
            if (snap && !numeric) { x = snap(x, 15); y = snap(y, 15); }
            runtime.editorSetNumber(selectedId, "rotationX", round(x));
            runtime.editorSetNumber(selectedId, "rotationY", round(y));
            return;
        }
        double amount = numeric ? entered : transformAxis == 0 ? transformDegrees : transformMouseDx * sensitivity;
        if (snap && !numeric) amount = snap(amount, 15);
        TransformBase base = transformBases.get(selectedId);
        if (base == null) base = new TransformBase(baseX, baseY, baseZ, baseWidth, baseHeight,
                baseAngle, baseRotationX, baseRotationY, "world");
        if (transformAxis == 0) runtime.editorSetNumber(selectedId, "angle", round(base.angle() + amount));
        else applyAxisRotation(selectedId, base, transformAxis, amount);
    }

    private double currentAxisRotationAmount() {
        boolean numeric = !transformNumeric.isEmpty() && !transformNumeric.equals("-");
        double amount = numeric ? numericValue() : transformMouseDx * 0.5;
        return hasControlDown() && !numeric ? snap(amount, 15) : amount;
    }

    /** Applies a genuine local or Minecraft-global axis rotation, then stores XYZ Euler fields. */
    private void applyAxisRotation(String id, TransformBase base, int axis, double degrees) {
        // Switching axis/space must restore the exact stored values, including at
        // Euler singularities where a matrix round-trip has multiple valid answers.
        if (Math.abs(degrees) < 0.0000001) {
            runtime.editorSetNumber(id, "rotationX", base.rotationX());
            runtime.editorSetNumber(id, "rotationY", base.rotationY());
            runtime.editorSetNumber(id, "angle", base.angle());
            return;
        }
        double radians = Math.toRadians(degrees);
        org.joml.Matrix3d local = new org.joml.Matrix3d().rotationXYZ(
                Math.toRadians(base.rotationX()), Math.toRadians(base.rotationY()),
                Math.toRadians(base.angle()));
        if (transformRotationGlobal) {
            org.joml.Matrix3d orientation;
            var widget = runtime.editorWidget(id);
            if (widget != null && widget.billboard()) {
                orientation = new org.joml.Matrix3d().rotation(
                        minecraft.gameRenderer.getMainCamera().rotation());
            } else {
                orientation = new org.joml.Matrix3d().rotationY(
                        Math.toRadians(-Direction.NORTH.toYRot()));
            }
            org.joml.Matrix3d world = new org.joml.Matrix3d(orientation).mul(local);
            if (axis == 1) world.rotateLocalX(radians);
            else if (axis == 2) world.rotateLocalY(radians);
            else world.rotateLocalZ(radians);
            local = new org.joml.Matrix3d(orientation).invert().mul(world);
        } else {
            if (axis == 1) local.rotateX(radians);
            else if (axis == 2) local.rotateY(radians);
            else local.rotateZ(radians);
        }
        org.joml.Vector3d euler = local.getEulerAnglesXYZ(new org.joml.Vector3d());
        runtime.editorSetNumber(id, "rotationX", round(Math.toDegrees(euler.x)));
        runtime.editorSetNumber(id, "rotationY", round(Math.toDegrees(euler.y)));
        runtime.editorSetNumber(id, "angle", round(Math.toDegrees(euler.z)));
    }

    private void resetRotationInput() {
        transformMouseDx = transformMouseDy = transformDegrees = 0;
        transformNumeric = "";
        transformLastMouseX = currentMouseX();
        transformLastMouseY = currentMouseY();
    }

    private void updateWorldScale(boolean numeric, double entered, boolean snap) {
        double factor = numeric ? entered : Math.max(0.05, 1 + transformMouseDx * 0.005);
        if (snap && !numeric) factor = Math.max(0.05, Math.round(factor * 10) / 10.0);
        boolean setWidth = transformAxis == 0 || transformAxis == 3
                || (transformAxis == 1 && !transformPlane) || (transformAxis == 2 && transformPlane);
        boolean setHeight = transformAxis == 0 || transformAxis == 3
                || (transformAxis == 2 && !transformPlane) || (transformAxis == 1 && transformPlane);
        if (setWidth) runtime.editorSetNumber(selectedId, "width", round(Math.max(1, baseWidth * factor)));
        if (setHeight) runtime.editorSetNumber(selectedId, "height", round(Math.max(1, baseHeight * factor)));
    }

    private void updateScreenMove(boolean numeric, double entered, boolean snap) {
        double viewportScale = Math.max(1.0e-6,
                Math.min(previewW() / 1280.0, previewH() / 720.0));
        double dx = transformMouseDx / viewportScale;
        double dy = transformMouseDy / viewportScale;
        if (transformAxis == 1) {
            if (transformPlane) dx = 0; else dy = 0;
        } else if (transformAxis == 2) {
            if (transformPlane) dy = 0; else dx = 0;
        }
        if (numeric) {
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length < 1.0e-8) {
                dx = transformAxis == 2 ? 0 : 1;
                dy = transformAxis == 2 ? 1 : 0;
                length = 1;
            }
            dx = dx / length * entered;
            dy = dy / length * entered;
        }
        boolean parented=runtime.editorIsParented(selectedId);
        if(parented) {Vec3 local=runtime.editorParentDelta(selectedId,new Vec3(dx,dy,0));dx=local.x;dy=local.y;}
        boolean normalizedX = !parented&&normalized(baseX), normalizedY = !parented&&normalized(baseY);
        double x = baseX + (normalizedX ? dx / 1280.0 : dx);
        double y = baseY + (normalizedY ? dy / 720.0 : dy);
        if ((snap || gridEnabled) && !numeric) {
            double step = gridEnabled ? gridSize : 1.0;
            x = normalizedX ? Math.rint(x * 1280.0 / step) * step / 1280.0
                    : Math.rint(x / step) * step;
            y = normalizedY ? Math.rint(y * 720.0 / step) * step / 720.0
                    : Math.rint(y / step) * step;
        }
        runtime.editorSetNumber(selectedId, "x", round(x));
        runtime.editorSetNumber(selectedId, "y", round(y));
    }

    private void updateScreenRotation(boolean numeric, double entered, boolean snap) {
        double angle = baseAngle + (numeric ? entered : transformDegrees);
        if (snap && !numeric) angle = snap(angle, 15);
        runtime.editorSetNumber(selectedId, "angle", round(angle));
    }

    private void updateScreenScale(boolean numeric, double entered, boolean snap) {
        double factor = numeric ? entered : Math.max(0.05, 1 + transformMouseDx * 0.005);
        if (snap && !numeric) factor = Math.max(0.05, Math.round(factor * 10) / 10.0);
        boolean setWidth = transformAxis == 0 || (transformAxis == 1 && !transformPlane)
                || (transformAxis == 2 && transformPlane);
        boolean setHeight = transformAxis == 0 || (transformAxis == 2 && !transformPlane)
                || (transformAxis == 1 && transformPlane);
        if (setWidth) runtime.editorSetNumber(selectedId, "width", round(Math.max(1, baseWidth * factor)));
        if (setHeight) runtime.editorSetNumber(selectedId, "height", round(Math.max(1, baseHeight * factor)));
    }

    private Vec3 constrainWorldMove(Vec3 screenDelta, Vec3 cameraRight, Vec3 cameraUp,
                                    double rawY, double worldPerPixel) {
        if (transformAxis == 0) return screenDelta;
        boolean absolute = usesMinecraftCoordinates();
        Vec3 selectedAxis = worldAxis(transformAxis, absolute);
        if (!transformPlane) {
            double right = selectedAxis.dot(cameraRight), up = selectedAxis.dot(cameraUp);
            double length = right * right + up * up;
            if (length < 1.0e-8) return selectedAxis.scale(-rawY * worldPerPixel);
            double amount = (screenDelta.dot(cameraRight) * right + screenDelta.dot(cameraUp) * up) / length;
            return selectedAxis.scale(amount);
        }
        Vec3 xAxis = worldAxis(1, absolute), yAxis = worldAxis(2, absolute),
                zAxis = worldAxis(3, absolute);
        Vec3 first = transformAxis == 1 ? yAxis : xAxis;
        Vec3 second = transformAxis == 3 ? yAxis : zAxis;
        double a11 = first.dot(cameraRight), a12 = second.dot(cameraRight);
        double a21 = first.dot(cameraUp), a22 = second.dot(cameraUp);
        double determinant = a11 * a22 - a12 * a21;
        if (Math.abs(determinant) < 1.0e-8)
            return screenDelta.subtract(selectedAxis.scale(screenDelta.dot(selectedAxis)));
        double desiredRight = screenDelta.dot(cameraRight), desiredUp = screenDelta.dot(cameraUp);
        double firstAmount = (desiredRight * a22 - a12 * desiredUp) / determinant;
        double secondAmount = (a11 * desiredUp - desiredRight * a21) / determinant;
        return first.scale(firstAmount).add(second.scale(secondAmount));
    }

    private static Vec3 worldAxis(int axis, boolean absolute) {
        if (absolute) return switch (axis) {
            case 1 -> new Vec3(1, 0, 0);
            case 2 -> new Vec3(0, 1, 0);
            default -> new Vec3(0, 0, 1);
        };
        Direction forward = Direction.NORTH;
        Direction right = forward.getCounterClockWise();
        return switch (axis) {
            case 1 -> new Vec3(right.getStepX(), 0, right.getStepZ());
            case 2 -> new Vec3(0, 1, 0);
            default -> new Vec3(forward.getStepX(), 0, forward.getStepZ());
        };
    }

    private Vec3 localToWorld(double x, double y, double z, boolean absolute) {
        if (absolute) return new Vec3(x, y, z);
        Direction forward = Direction.NORTH;
        Direction right = forward.getCounterClockWise();
        return Vec3.atCenterOf(worldOrigin).add(right.getStepX() * x + forward.getStepX() * z,
                y, right.getStepZ() * x + forward.getStepZ() * z);
    }

    private boolean usesMinecraftCoordinates() {
        return runtime != null && runtime.editorString(selectedId, "coordinates")
                .equalsIgnoreCase("minecraft");
    }

    private double numericValue() {
        try { return transformNumeric.isEmpty() || transformNumeric.equals("-") ? 0 : Double.parseDouble(transformNumeric); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String worldTransformStatus() {
        if (!worldTransforming()) return status;
        String value = switch (transform) {
            case MOVE -> "Move"; case ROTATE -> transformTrackball ? "Rotate (trackball)" : "Rotate";
            case SCALE -> "Scale"; default -> "";
        };
        if (transformAxis != 0) {
            String axis = transformAxis == 1 ? "X" : transformAxis == 2 ? "Y" : "Z";
            value += transformPlane ? " (not " + axis + ")" : " " + axis;
            if (transform == Transform.ROTATE)
                value += transformRotationGlobal ? " · Global" : " · Local";
        }
        if (!transformNumeric.isEmpty()) value += " = " + transformNumeric;
        return value + " · LMB/Enter confirm · RMB/Esc cancel";
    }

    private void wrapTransformCursor(double mouseX, double mouseY) {
        int margin = 3;
        double nx = mouseX, ny = mouseY;
        int left = actualPreviewX(), right = left + previewW();
        int top = actualPreviewY(), bottom = top + previewH();
        if (mouseX <= left + margin) nx = right - margin - 2;
        else if (mouseX >= right - margin) nx = left + margin + 2;
        if (mouseY <= top + margin) ny = bottom - margin - 2;
        else if (mouseY >= bottom - margin) ny = top + margin + 2;
        if (nx == mouseX && ny == mouseY) return;
        transformLastMouseX = nx;
        transformLastMouseY = ny;
        transformIgnoreWarpMotion = true;
        warpCursor(nx, ny);
    }

    private double currentMouseX() {
        if (minecraft == null) return 0;
        return minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                / Math.max(1.0, minecraft.getWindow().getScreenWidth());
    }

    private double currentMouseY() {
        if (minecraft == null) return 0;
        return minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                / Math.max(1.0, minecraft.getWindow().getScreenHeight());
    }

    private static double snap(double value, double step) { return Math.round(value / step) * step; }
    private static double round(double value) { return Math.round(value * 10000.0) / 10000.0; }

    private boolean insidePreview(double x, double y) {
        return x >= actualPreviewX() && x < actualPreviewX() + previewW()
                && y >= actualPreviewY() && y < actualPreviewY() + previewH();
    }
    private static boolean normalized(double value) { return Math.abs(value) <= 1; }

    private void wrapCameraCursor(double mouseX, double mouseY) {
        int margin = 3;
        double nx = mouseX, ny = mouseY;
        int left = actualPreviewX(), right = left + previewW();
        int top = actualPreviewY(), bottom = top + previewH();
        if (mouseX <= left + margin) nx = right - margin - 2;
        else if (mouseX >= right - margin) nx = left + margin + 2;
        if (mouseY <= top + margin) ny = bottom - margin - 2;
        else if (mouseY >= bottom - margin) ny = top + margin + 2;
        if (nx == mouseX && ny == mouseY) return;
        cameraIgnoreWarpMotion = true;
        warpCursor(nx, ny);
    }

    private void warpCursor(double guiX, double guiY) {
        var window = minecraft.getWindow();
        double mappedX = guiX * window.getScreenWidth() / Math.max(1.0, window.getGuiScaledWidth());
        double mappedY = guiY * window.getScreenHeight() / Math.max(1.0, window.getGuiScaledHeight());
        double rawX = com.fnfmod.client.render.PsychResolutionController.unmapMouseX(mappedX);
        double rawY = com.fnfmod.client.render.PsychResolutionController.unmapMouseY(mappedY);
        GLFW.glfwSetCursorPos(window.getWindow(), rawX, rawY);
    }

    @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        updateCameraNavigation();
        updatePanelScroll();
        updateLayerAnimation();
        int vx = actualPreviewX(), vy = actualPreviewY(), vw = previewW(), vh = previewH();
        if (runtime != null) runtime.setEditorWorldViewport(vx, vy, vw, vh);
        gui.fill(0, 0, width, height, 0xFF09090D);
        com.fnfmod.client.render.MenuEditorViewport.draw(gui, vx, vy, vw, vh);
        if (gridEnabled) draw2DGrid(gui, vx, vy, vw, vh);
        if (runtime != null && runtime.loaded()) runtime.renderInViewport(gui, font, mouseX, mouseY, vx, vy, vw, vh);
        if (runtime != null) for (String id : selectedIds) {
            var selected = runtime.editorWidget(id);
            if (selected != null && !selected.space().equalsIgnoreCase("world"))
                drawSelection(gui, runtime.editorBounds(id, vx, vy, vw, vh), id.equals(selectedId));
        }
        if (!previewMode) {
            int accent = BlockifiedScreenStyle.ACCENT;
            gui.fill(vx - 2, vy - 2, vx + vw + 2, vy, accent);
            gui.fill(vx - 2, vy + vh, vx + vw + 2, vy + vh + 2, accent);
            gui.fill(vx - 2, vy, vx, vy + vh, accent);
            gui.fill(vx + vw, vy, vx + vw + 2, vy + vh, accent);
            BlockifiedScreenStyle.panel(gui, panelX(), 0, panelWidth(), height);
            gui.fill(0, 0, panelX(), HEADER_H, 0xE8101018);
            gui.drawString(font, "MENU LUA EDITOR", 10, 8, accent, false);
            gui.drawString(font, fitText("Target: current/default machine · change it with the Machine button",
                            panelX() - 20), 10, 25,
                    BlockifiedScreenStyle.TEXT_MUTED, false);
            gui.fill(0, height - FOOTER_H, panelX(), height, 0xE8101018);
            gui.drawString(font, fitText(status, panelX() - 20), 10, height - 29, 0xFFDDDDDD, false);
            gui.drawString(font, fitText("Ctrl+Z/Y undo/redo · Numpad . focus · RMB move · MMB orbit",
                            panelX() - 20), 10, height - 16,
                    BlockifiedScreenStyle.TEXT_MUTED, false);
            renderEditorControls(gui, mouseX, mouseY, partialTick);
        } else gui.drawString(font, "Tab / Esc: return to editor", 8, 8, 0x99FFFFFF, false);
        if (worldTransforming()) {
            String transformStatus = worldTransformStatus();
            gui.drawCenteredString(font, transformStatus, vx + vw / 2, vy + vh / 2 + 16, 0xFFFFEE66);
        }
    }

    private void draw2DGrid(GuiGraphics gui, int viewportX, int viewportY,
                            int viewportWidth, int viewportHeight) {
        double scale = Math.max(1.0e-6, Math.min(viewportWidth / 1280.0, viewportHeight / 720.0));
        double left = viewportX + (viewportWidth - 1280 * scale) * 0.5;
        double top = viewportY + (viewportHeight - 720 * scale) * 0.5;
        int right = (int) Math.round(left + 1280 * scale);
        int bottom = (int) Math.round(top + 720 * scale);
        double spacing = Math.max(1, Math.min(640, gridSize));
        gui.enableScissor((int) Math.floor(left), (int) Math.floor(top), right, bottom);
        try {
            for (double canvasX = 0; canvasX <= 1280.0001; canvasX += spacing) {
                int x = (int) Math.round(left + canvasX * scale);
                gui.fill(x, (int) Math.floor(top), x + 1, bottom, 0x35B82BFF);
            }
            for (double canvasY = 0; canvasY <= 720.0001; canvasY += spacing) {
                int y = (int) Math.round(top + canvasY * scale);
                gui.fill((int) Math.floor(left), y, right, y + 1, 0x35B82BFF);
            }
            int centerX = (int) Math.round(left + 640 * scale);
            int centerY = (int) Math.round(top + 360 * scale);
            gui.fill(centerX, (int) Math.floor(top), centerX + 1, bottom, 0x80FF702E);
            gui.fill((int) Math.floor(left), centerY, right, centerY + 1, 0x80FF702E);
        } finally {
            gui.disableScissor();
        }
    }

    private void updateLayerAnimation() {
        if (tab != Tab.LAYERS || layerRows.isEmpty()) return;
        long now = System.nanoTime();
        double dt = lastLayerFrameNanos == 0 ? 1.0 / 60.0
                : Math.min(0.1, (now - lastLayerFrameNanos) / 1_000_000_000.0);
        lastLayerFrameNanos = now;
        int scroll = (int) Math.round(panelScroll.getOrDefault(tab, 0.0));
        double blend = 1.0 - Math.pow(2.0, -18.0 * dt);
        int top = 58, bottom = Math.max(top + 20, height - 50);
        for (int i = 0; i < layerDisplayOrder.size(); i++) {
            String id = layerDisplayOrder.get(i);
            AbstractWidget row = layerRows.get(id);
            if (row == null) continue;
            double target = LAYERS_LIST_Y + i * 22 - scroll;
            double current = layerVisualY.getOrDefault(id, target);
            current = id.equals(draggedLayer) ? layerPointerY - layerGrabOffset
                    : current + (target - current) * blend;
            layerVisualY.put(id, current);
            row.setX(id.equals(draggedLayer) ? panelX() + 5 : panelX() + 9);
            row.setY((int) Math.round(current));
            row.visible = row.getY() + row.getHeight() > top && row.getY() < bottom;
        }
    }

    /** Same interpolated, clipped content treatment used by Blockified settings pages. */
    private void renderEditorControls(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        Map<AbstractWidget, Boolean> visibility = new IdentityHashMap<>();
        for (AbstractWidget widget : panelWidgets) {
            visibility.put(widget, widget.visible);
            widget.visible = false;
        }
        super.render(gui, mouseX, mouseY, partialTick); // fixed machine/tabs/save/back controls
        for (AbstractWidget widget : panelWidgets) widget.visible = visibility.getOrDefault(widget, false);
        int top = 58, bottom = Math.max(top + 20, height - 50);
        gui.enableScissor(panelX(), top, width, bottom);
        try {
            for (AbstractWidget widget : panelWidgets) {
                if (widget.visible && widget != layerRows.get(draggedLayer))
                    widget.render(gui, mouseX, mouseY, partialTick);
            }
            AbstractWidget heldLayer = layerRows.get(draggedLayer);
            if (heldLayer != null && heldLayer.visible) heldLayer.render(gui, mouseX, mouseY, partialTick);
        } finally {
            gui.disableScissor();
        }
    }

    private static void drawSelection(GuiGraphics gui, MachineMenuRuntime.EditorBounds bounds, boolean primary) {
        if (bounds == null) return;
        int left = (int) Math.floor(bounds.left()) - 2, top = (int) Math.floor(bounds.top()) - 2;
        int right = (int) Math.ceil(bounds.right()) + 2, bottom = (int) Math.ceil(bounds.bottom()) + 2;
        int color = primary ? 0xFFFFB52E : 0xFFB82BFF;
        gui.fill(left, top, right, top + 1, color); gui.fill(left, bottom - 1, right, bottom, color);
        gui.fill(left, top, left + 1, bottom, color); gui.fill(right - 1, top, right, bottom, color);
    }

    public void renderLuaWorld(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.Camera liveCamera) {
        if (runtime != null && runtime.loaded()) {
            runtime.renderWorldEditor(pose, liveCamera, worldOrigin, Direction.NORTH);
            for (String id : selectedIds)
                runtime.renderEditorSelection(pose, liveCamera, worldOrigin, Direction.NORTH, id);
            if (freeCameraMode) {
                Vector3f[] basis = cameraMarkerBasis(camera);
                com.fnfmod.client.render.FreeCamCameraMarker.render(
                        pose, liveCamera, camera.position(), basis[0], basis[1], basis[2]);
            }
        }
    }

    /** Marker basis in the same left/up/look convention used by playtest Free Cam. */
    private static Vector3f[] cameraMarkerBasis(MenuCameraController controller) {
        double yaw = Math.toRadians(controller.yaw());
        double pitch = Math.toRadians(controller.pitch());
        Vec3 look = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch)).normalize();
        Vec3 right = new Vec3(Math.cos(yaw), 0, Math.sin(yaw)).normalize();
        Vec3 up = look.cross(right).normalize();
        double roll = Math.toRadians(controller.roll());
        Vec3 rolledRight = right.scale(Math.cos(roll)).add(up.scale(Math.sin(roll))).normalize();
        Vec3 rolledUp = up.scale(Math.cos(roll)).subtract(right.scale(Math.sin(roll))).normalize();
        return new Vector3f[]{new Vector3f((float) -rolledRight.x, (float) -rolledRight.y,
                (float) -rolledRight.z), new Vector3f((float) rolledUp.x, (float) rolledUp.y,
                (float) rolledUp.z), new Vector3f((float) look.x, (float) look.y, (float) look.z)};
    }

    private String fitText(String text, int maxWidth) {
        if (text == null || maxWidth <= 0 || font.width(text) <= maxWidth) return text == null ? "" : text;
        String suffix = "…";
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(suffix))) + suffix;
    }

    private void save() {
        boolean ok = runtime != null && runtime.saveEditorLayout();
        if (ok) MachineLibrary.rescan();
        status = ok ? "Saved objects, layers, camera, and keyframe tweens into menu.lua"
                : "Could not save menu.lua";
    }

    private static String compact(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001) return Long.toString(Math.round(value));
        return String.format(java.util.Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override public void removed() {
        viewCamera().deactivate();
        camera.deactivate();
        freeCamera.deactivate();
        com.fnfmod.client.render.MenuEditorViewport.release();
        if (runtime != null) { runtime.close(); runtime = null; }
        super.removed();
    }
    @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public int screenWidth() { return 1280; }
    @Override public int screenHeight() { return 720; }
    @Override public void openSongSelect(byte returnTarget) {}
    @Override public boolean openSongDetails(String songId) { return false; }
    @Override public boolean playSong(String songId, String difficulty, boolean duet, byte playSide,
                                     byte playbackMode, byte returnTarget, String targetMachine) { return false; }
    @Override public void openSettings() {}
    @Override public void openCharacterEditor() {}
    @Override public void openChartEditor(String songId, String difficulty) {}
    @Override public void closeMenu() {}
    @Override public boolean exitWorld() { return false; } // Editing must never disconnect the world.
    @Override public void saveData(String snbt) {}
}
