package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.glfw.GLFW;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Native companion windows for the chart editor. Panel windows mirror crops
 * from Minecraft's framebuffer so custom note skins and the real editor UI are
 * preserved; input is mapped back into the live editor on Minecraft's thread.
 */
final class ChartEditorDesktopWindows implements AutoCloseable {
    enum Panel { INFORMATION, GRID, CONTROLS }

    private static final long CAPTURE_INTERVAL_NS = 100_000_000L;

    private final ChartEditorScreen editor;
    private final EnumMap<Panel, MirrorWindow> mirrors = new EnumMap<>(Panel.class);
    private volatile JFrame helpFrame;
    private long lastCapture;
    private volatile boolean closed;
    private boolean captureFailureReported;

    ChartEditorDesktopWindows(ChartEditorScreen editor) {
        this.editor = editor;
    }

    boolean openHelp() {
        if (closed || GraphicsEnvironment.isHeadless()) return false;
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (helpFrame != null && helpFrame.isDisplayable()) {
                focus(helpFrame);
                return;
            }
            JTextArea text = new JTextArea(String.join("\n", ChartEditorScreen.helpLines()));
            text.setEditable(false);
            text.setFocusable(false);
            text.setBackground(new Color(16, 16, 20));
            text.setForeground(Color.WHITE);
            text.setCaretColor(Color.WHITE);
            text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
            text.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));

            JFrame frame = new JFrame("FNF Chart Editor - Help");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setContentPane(new JScrollPane(text));
            frame.setMinimumSize(new Dimension(540, 360));
            frame.setSize(680, 620);
            frame.setLocationByPlatform(true);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) {
                    synchronized (mirrors) {
                        if (helpFrame == frame) helpFrame = null;
                    }
                }
            });
            helpFrame = frame;
            frame.setVisible(true);
            focus(frame);
        });
        return true;
    }

    void open(Panel panel) {
        if (closed || GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            MirrorWindow existing;
            synchronized (mirrors) {
                existing = mirrors.get(panel);
            }
            if (existing != null && existing.frame.isDisplayable()) {
                focus(existing.frame);
                return;
            }
            MirrorWindow created = createMirror(panel);
            synchronized (mirrors) {
                mirrors.put(panel, created);
            }
            created.frame.setVisible(true);
            created.canvas.requestFocusInWindow();
        });
    }

    void openAll() {
        for (Panel panel : Panel.values()) open(panel);
    }

    private MirrorWindow createMirror(Panel panel) {
        String title = switch (panel) {
            case INFORMATION -> "Information";
            case GRID -> "Chart Grid";
            case CONTROLS -> "Editor Controls";
        };
        MirrorCanvas canvas = new MirrorCanvas();
        JFrame frame = new JFrame("FNF Chart Editor - " + title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setContentPane(canvas);
        frame.setMinimumSize(new Dimension(220, 180));
        switch (panel) {
            case INFORMATION -> frame.setSize(360, 420);
            case GRID -> frame.setSize(520, 820);
            case CONTROLS -> frame.setSize(660, 520);
        }
        frame.setLocationByPlatform(true);
        MirrorWindow window = new MirrorWindow(panel, frame, canvas);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                synchronized (mirrors) {
                    if (mirrors.get(panel) == window) mirrors.remove(panel);
                }
            }
        });
        return window;
    }

    /** Called after the editor has rendered the complete current frame. */
    void capture() {
        if (closed) return;
        long now = System.nanoTime();
        if (now - lastCapture < CAPTURE_INTERVAL_NS) return;

        List<MirrorWindow> targets;
        synchronized (mirrors) {
            targets = mirrors.values().stream()
                    .filter(window -> window.frame.isShowing()
                            && window.frame.getState() != JFrame.ICONIFIED)
                    .toList();
        }
        if (targets.isEmpty()) return;
        lastCapture = now;

        Minecraft minecraft = Minecraft.getInstance();
        try (NativeImage screenshot = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            double scaleX = screenshot.getWidth() / (double) Math.max(1, editor.width);
            double scaleY = screenshot.getHeight() / (double) Math.max(1, editor.height);
            for (MirrorWindow target : targets) {
                Rectangle region = editor.desktopRegion(target.panel);
                if (region == null || region.width <= 0 || region.height <= 0) continue;
                BufferedImage image = crop(screenshot, region, scaleX, scaleY);
                SwingUtilities.invokeLater(() -> target.canvas.update(image, region));
            }
        } catch (Throwable error) {
            if (!captureFailureReported) {
                captureFailureReported = true;
                FnfMod.LOGGER.warn("Could not update chart editor companion windows: {}", error.toString());
            }
        }
    }

    private static BufferedImage crop(NativeImage source, Rectangle guiRegion,
                                      double scaleX, double scaleY) {
        int x0 = clamp((int) Math.floor(guiRegion.x * scaleX), 0, source.getWidth() - 1);
        int y0 = clamp((int) Math.floor(guiRegion.y * scaleY), 0, source.getHeight() - 1);
        int x1 = clamp((int) Math.ceil((guiRegion.x + guiRegion.width) * scaleX), x0 + 1, source.getWidth());
        int y1 = clamp((int) Math.ceil((guiRegion.y + guiRegion.height) * scaleY), y0 + 1, source.getHeight());
        BufferedImage result = new BufferedImage(x1 - x0, y1 - y0, BufferedImage.TYPE_INT_ARGB);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int abgr = source.getPixelRGBA(x, y);
                int argb = (abgr & 0xFF00FF00)
                        | ((abgr & 0x000000FF) << 16)
                        | ((abgr & 0x00FF0000) >>> 16);
                result.setRGB(x - x0, y - y0, argb);
            }
        }
        return result;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void focus(JFrame frame) {
        frame.setState(JFrame.NORMAL);
        frame.toFront();
        frame.requestFocus();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        List<JFrame> frames = new ArrayList<>();
        synchronized (mirrors) {
            for (MirrorWindow mirror : mirrors.values()) frames.add(mirror.frame);
            mirrors.clear();
            if (helpFrame != null) frames.add(helpFrame);
            helpFrame = null;
        }
        SwingUtilities.invokeLater(() -> frames.forEach(JFrame::dispose));
    }

    private final class MirrorCanvas extends JPanel {
        private volatile BufferedImage image;
        private volatile Rectangle sourceRegion;
        private int heldButton = -1;

        MirrorCanvas() {
            setBackground(new Color(16, 16, 20));
            setFocusable(true);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    requestFocusInWindow();
                    heldButton = mouseButton(event);
                    dispatchMouse(event, heldButton, MouseAction.PRESS);
                }

                @Override public void mouseReleased(MouseEvent event) {
                    int button = heldButton >= 0 ? heldButton : mouseButton(event);
                    dispatchMouse(event, button, MouseAction.RELEASE);
                    heldButton = -1;
                }

                @Override public void mouseDragged(MouseEvent event) {
                    if (heldButton >= 0) dispatchMouse(event, heldButton, MouseAction.DRAG);
                }

                @Override public void mouseWheelMoved(MouseWheelEvent event) {
                    double[] point = map(event.getX(), event.getY());
                    if (point == null) return;
                    int modifiers = glfwModifiers(event.getModifiersEx());
                    Minecraft.getInstance().execute(() -> editor.desktopMouseScrolled(
                            point[0], point[1], -event.getPreciseWheelRotation(), modifiers));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent event) {
                    if (event.getKeyCode() == KeyEvent.VK_F1) {
                        openHelp();
                        return;
                    }
                    int key = glfwKey(event.getKeyCode());
                    if (key < 0) return;
                    int modifiers = glfwModifiers(event.getModifiersEx());
                    Minecraft.getInstance().execute(() -> editor.desktopKeyPressed(key, modifiers));
                }

                @Override public void keyTyped(KeyEvent event) {
                    char value = event.getKeyChar();
                    if (Character.isISOControl(value)) return;
                    int modifiers = glfwModifiers(event.getModifiersEx());
                    Minecraft.getInstance().execute(() -> editor.desktopCharTyped(value, modifiers));
                }
            });
        }

        void update(BufferedImage nextImage, Rectangle nextRegion) {
            image = nextImage;
            sourceRegion = new Rectangle(nextRegion);
            repaint();
        }

        private void dispatchMouse(MouseEvent event, int button, MouseAction action) {
            double[] point = map(event.getX(), event.getY());
            if (point == null || button < 0) return;
            int modifiers = glfwModifiers(event.getModifiersEx());
            Minecraft.getInstance().execute(() -> {
                switch (action) {
                    case PRESS -> editor.desktopMouseClicked(point[0], point[1], button, modifiers);
                    case DRAG -> editor.desktopMouseDragged(point[0], point[1], button, modifiers);
                    case RELEASE -> editor.desktopMouseReleased(point[0], point[1], button, modifiers);
                }
            });
        }

        private double[] map(int mouseX, int mouseY) {
            BufferedImage current = image;
            Rectangle region = sourceRegion;
            if (current == null || region == null) return null;
            double scale = Math.min(getWidth() / (double) current.getWidth(),
                    getHeight() / (double) current.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(current.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(current.getHeight() * scale));
            int drawX = (getWidth() - drawWidth) / 2;
            int drawY = (getHeight() - drawHeight) / 2;
            if (mouseX < drawX || mouseX >= drawX + drawWidth
                    || mouseY < drawY || mouseY >= drawY + drawHeight) return null;
            double x = region.x + (mouseX - drawX) * region.width / (double) drawWidth;
            double y = region.y + (mouseY - drawY) * region.height / (double) drawHeight;
            return new double[]{x, y};
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            BufferedImage current = image;
            if (current == null) {
                graphics.setColor(Color.LIGHT_GRAY);
                graphics.drawString("Waiting for chart editor frame...", 16, 24);
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double scale = Math.min(getWidth() / (double) current.getWidth(),
                    getHeight() / (double) current.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(current.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(current.getHeight() * scale));
            int drawX = (getWidth() - drawWidth) / 2;
            int drawY = (getHeight() - drawHeight) / 2;
            g.drawImage(current, drawX, drawY, drawWidth, drawHeight, null);
            g.setColor(new Color(100, 100, 100));
            g.setStroke(new BasicStroke(1f));
            g.drawRect(drawX, drawY, drawWidth - 1, drawHeight - 1);
            g.dispose();
        }
    }

    private static int mouseButton(MouseEvent event) {
        return switch (event.getButton()) {
            case MouseEvent.BUTTON1 -> 0;
            case MouseEvent.BUTTON3 -> 1;
            case MouseEvent.BUTTON2 -> 2;
            default -> -1;
        };
    }

    private static int glfwModifiers(int awtModifiers) {
        int result = 0;
        if ((awtModifiers & MouseEvent.SHIFT_DOWN_MASK) != 0) result |= GLFW.GLFW_MOD_SHIFT;
        if ((awtModifiers & MouseEvent.CTRL_DOWN_MASK) != 0) result |= GLFW.GLFW_MOD_CONTROL;
        if ((awtModifiers & MouseEvent.ALT_DOWN_MASK) != 0) result |= GLFW.GLFW_MOD_ALT;
        if ((awtModifiers & MouseEvent.META_DOWN_MASK) != 0) result |= GLFW.GLFW_MOD_SUPER;
        return result;
    }

    private static int glfwKey(int awtKey) {
        if (awtKey >= KeyEvent.VK_A && awtKey <= KeyEvent.VK_Z) return GLFW.GLFW_KEY_A + awtKey - KeyEvent.VK_A;
        if (awtKey >= KeyEvent.VK_0 && awtKey <= KeyEvent.VK_9) return GLFW.GLFW_KEY_0 + awtKey - KeyEvent.VK_0;
        if (awtKey >= KeyEvent.VK_F1 && awtKey <= KeyEvent.VK_F12) return GLFW.GLFW_KEY_F1 + awtKey - KeyEvent.VK_F1;
        return switch (awtKey) {
            case KeyEvent.VK_ESCAPE -> GLFW.GLFW_KEY_ESCAPE;
            case KeyEvent.VK_ENTER -> GLFW.GLFW_KEY_ENTER;
            case KeyEvent.VK_SPACE -> GLFW.GLFW_KEY_SPACE;
            case KeyEvent.VK_LEFT -> GLFW.GLFW_KEY_LEFT;
            case KeyEvent.VK_RIGHT -> GLFW.GLFW_KEY_RIGHT;
            case KeyEvent.VK_UP -> GLFW.GLFW_KEY_UP;
            case KeyEvent.VK_DOWN -> GLFW.GLFW_KEY_DOWN;
            case KeyEvent.VK_OPEN_BRACKET -> GLFW.GLFW_KEY_LEFT_BRACKET;
            case KeyEvent.VK_CLOSE_BRACKET -> GLFW.GLFW_KEY_RIGHT_BRACKET;
            case KeyEvent.VK_BACK_SPACE -> GLFW.GLFW_KEY_BACKSPACE;
            case KeyEvent.VK_DELETE -> GLFW.GLFW_KEY_DELETE;
            default -> -1;
        };
    }

    private enum MouseAction { PRESS, DRAG, RELEASE }

    private record MirrorWindow(Panel panel, JFrame frame, MirrorCanvas canvas) {}
}
