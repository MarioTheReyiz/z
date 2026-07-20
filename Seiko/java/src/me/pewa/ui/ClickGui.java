package me.pewa.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.pewa.Pewa;
import me.pewa.config.ConfigManager;
import me.pewa.module.Category;
import me.pewa.module.Module;
import me.pewa.module.ModuleManager;
import me.pewa.module.impl.ClickGuiModule;
import me.pewa.setting.BooleanOption;
import me.pewa.setting.ColorOption;
import me.pewa.setting.NumberOption;
import me.pewa.setting.OptionBase;
import me.pewa.setting.StringOption;
import me.pewa.setting.TextOption;
import me.pewa.util.FontUtil;
import me.pewa.util.GaussianBlur;
import me.pewa.util.FloatingLines;
import me.pewa.util.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

public final class ClickGui {
    private static final Color GLASS_PANEL = new Color(15, 15, 18, 178);
    private static final Color GLASS_PANEL_STRONG = new Color(20, 20, 24, 220);
    private static final Color GLASS_BORDER = new Color(255, 255, 255, 34);
    private static final Color GLASS_BORDER_HOVER = new Color(255, 255, 255, 70);
    private static final int TEXT_PRIMARY = color(244, 244, 245, 255);
    private static final int TEXT_SECONDARY = color(161, 161, 170, 255);
    private static final int TEXT_MUTED = color(113, 113, 122, 255);
    private static final int TEXT_DARK = color(39, 39, 42, 255);
    private static final int DANGER = color(239, 68, 68, 255);

    private static final float REF_WIDTH = 1280.0F;
    private static final float REF_HEIGHT = 720.0F;
    private static final float PANEL_WIDTH = 184.0F;
    private static final float HEADER_HEIGHT = 34.0F;
    private static final float MODULE_HEIGHT = 28.0F;
    private static final float PANEL_RADIUS = 10.0F;
    private static final float PANEL_GAP = 18.0F;

    private static final Map<Category, CategoryPanel> PANELS = new EnumMap<Category, CategoryPanel>(Category.class);
    private static final Map<String, Boolean> GROUP_EXPANDED = new HashMap<String, Boolean>();
    private static final Map<String, Float> GROUP_ANIMATIONS = new HashMap<String, Float>();
    private static final Map<ColorOption, Float> COLOR_PICKER_ANIMATIONS = new HashMap<ColorOption, Float>();
    private static final List<GridPoint> GRID_POINTS = new ArrayList<GridPoint>();

    private static volatile long lastWarnTime;

    private static boolean initialized;
    private static boolean open;
    private static boolean lastToggleKeyDown;
    private static boolean lastEscapeDown;
    private static boolean mouseGrabReleased;
    private static boolean mouseWasGrabbed;
    private static boolean lastLeftDown;
    private static boolean lastRightDown;
    private static boolean leftDown;
    private static boolean rightDown;
    private static boolean leftClicked;
    private static boolean rightClicked;
    private static boolean mouseConsumed;
    private static boolean waitingForKeybind;
    private static boolean uiEditMode;
    private static boolean isTypingSearch;
    private static boolean isTypingConfigName;
    private static boolean isTypingText;
    private static boolean configModalOpen;
    private static boolean draggingPanelActive;
    private static boolean draggingAnyElement;

    private static float mouseX;
    private static float mouseY;
    private static float deltaTime;
    private static float globalOpenAnimation;
    private static float configModalAnimation;
    private static float dropdownAnimation;
    private static float guiScale = 1.5F;
    private static float dragOffsetX;
    private static float dragOffsetY;
    private static float exitEditHover;
    private static float resetEditHover;

    private static int mouseWheel;
    private static int lastDisplayWidth;
    private static int lastDisplayHeight;
    private static int lastModuleCount;
    private static long lastFrameTime = System.currentTimeMillis();

    private static CategoryPanel draggingPanel;
    private static ContextMenu contextMenu;
    private static Module keybindModule;
    private static NumberOption draggingSlider;
    private static StringOption activeDropdown;
    private static TextOption activeTextOption;
    private static ColorOption activeColorPicker;
    private static ColorOption draggingColor;
    private static int draggingColorType;
    private static float dropdownX;
    private static float dropdownY;
    private static float dropdownW;
    private static String selectedConfig = "default";
    private static List<String> configList = new ArrayList<String>();

    private static final TextField searchField = new TextField("Search...", 32);
    private static final TextField configNameField = new TextField("New profile", 28);
    private static final TextField activeTextField = new TextField("Input...", 128);

    private ClickGui() {
    }

    public static void open() {
        open = true;
        releaseMouse();
        ensureInitialized();
        GaussianBlur.invalidateFrameCache();
        lastFrameTime = System.currentTimeMillis();
    }

    public static void close() {
        open = false;
        GaussianBlur.invalidateFrameCache();
        draggingPanel = null;
        draggingPanelActive = false;
        draggingAnyElement = false;
        contextMenu = null;
        activeDropdown = null;
        activeColorPicker = null;
        draggingSlider = null;
        draggingColor = null;
        waitingForKeybind = false;
        keybindModule = null;
        clearTyping();
        // Free GL resources used by optional backgrounds
        try { FloatingLines.cleanup(); } catch (Throwable ignored) {}
        lastFrameTime = System.currentTimeMillis();
    }

    public static boolean isOpen() {
        return open || globalOpenAnimation > 0.02F;
    }

    public static boolean isUiEditMode() {
        return uiEditMode;
    }

    public static boolean isDraggingAnyElement() {
        return draggingAnyElement || draggingPanelActive;
    }

    public static void setDraggingAnyElement(boolean dragging) {
        draggingAnyElement = dragging;
    }

    public static void renderFrame() {
        try {
            Module guiModule = getClickGuiModule();
            handleToggleKey(guiModule);

            updateDelta();
            updateScale();

            globalOpenAnimation = lerp(globalOpenAnimation, open ? 1.0F : 0.0F, deltaTime * 9.0F);
            configModalAnimation = lerp(configModalAnimation, configModalOpen ? 1.0F : 0.0F,
                    deltaTime * (configModalOpen ? 12.0F : 18.0F));

            if (!open && globalOpenAnimation < 0.02F) {
                globalOpenAnimation = 0.0F;
                updateMouseState();
                restoreMouse();
                return;
            }

            ensureInitialized();
            if (open) {
                releaseMouse();
            }

            GaussianBlur.beginFrame();
            updateMouseState();
            handleKeyboardEvents(guiModule);
            handleEscapeKey(guiModule);
            handleMouseWheel();

            float alpha = globalOpenAnimation;

            pushState();
            try {
                render(mouseX, mouseY, alpha);
            } finally {
                popState();
            }

            // In edit mode, render HUD after panels using its own ScissorUtil coords
            if (uiEditMode && alpha > 0.01F) {
                IngameHud.renderForEditMode(0.0F);
            }

            leftClicked = false;
            rightClicked = false;
        } catch (Throwable t) {
            warnThrottled("ClickGui render error: " + t.getMessage());
        }
    }

    private static void handleToggleKey(Module guiModule) {
        if (waitingForKeybind || isTyping()) {
            lastToggleKeyDown = false;
            return;
        }

        int key = guiModule == null ? Keyboard.KEY_RSHIFT : guiModule.getKeyBind();
        if (key <= 0) {
            key = Keyboard.KEY_RSHIFT;
        }

        boolean keyDown = isKeyDown(key);
        if (keyDown && !lastToggleKeyDown) {
            if (open) {
                requestClose(guiModule);
            } else if (guiModule != null) {
                guiModule.enable();
            } else {
                open();
            }
        }
        lastToggleKeyDown = keyDown;
    }

    private static void requestClose(Module guiModule) {
        if (uiEditMode) {
            uiEditMode = false;
            return;
        }
        if (configModalOpen) {
            configModalOpen = false;
            clearTyping();
            return;
        }
        if (contextMenu != null) {
            contextMenu.closing = true;
            activeDropdown = null;
            return;
        }
        if (guiModule != null && guiModule.isEnabled()) {
            guiModule.disable();
        } else {
            close();
        }
    }

    private static void handleEscapeKey(Module guiModule) {
        if (isTyping() || waitingForKeybind) {
            lastEscapeDown = false;
            return;
        }
        boolean escapeDown = isKeyDown(Keyboard.KEY_ESCAPE);
        if (escapeDown && !lastEscapeDown) {
            requestClose(guiModule);
        }
        lastEscapeDown = escapeDown;
    }

    private static void handleKeyboardEvents(Module guiModule) {
        try {
            while (Keyboard.next()) {
                if (!Keyboard.getEventKeyState()) {
                    continue;
                }

                int key = Keyboard.getEventKey();
                char keyChar = Keyboard.getEventCharacter();

                if (waitingForKeybind && keybindModule != null) {
                    if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE) {
                        keybindModule.setKeyBind(0);
                    } else if (key > 0) {
                        keybindModule.setKeyBind(key);
                    }
                    waitingForKeybind = false;
                    keybindModule = null;
                    mouseConsumed = true;
                    continue;
                }

                if (isTypingSearch) {
                    if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                        isTypingSearch = false;
                        searchField.setFocused(false);
                    } else {
                        searchField.handleKeyboard(key, keyChar);
                    }
                    continue;
                }

                if (isTypingConfigName) {
                    if (key == Keyboard.KEY_ESCAPE) {
                        isTypingConfigName = false;
                        configNameField.setFocused(false);
                    } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                        createConfigFromField();
                    } else {
                        configNameField.handleKeyboard(key, keyChar);
                    }
                    continue;
                }

                if (isTypingText && activeTextOption != null) {
                    if (key == Keyboard.KEY_ESCAPE) {
                        isTypingText = false;
                        activeTextField.setFocused(false);
                        activeTextOption = null;
                    } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                        activeTextOption.setText(activeTextField.getText());
                        isTypingText = false;
                        activeTextField.setFocused(false);
                        activeTextOption = null;
                    } else {
                        activeTextField.handleKeyboard(key, keyChar);
                    }
                    continue;
                }

                if (key == Keyboard.KEY_ESCAPE) {
                    requestClose(guiModule);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void handleMouseWheel() {
        if (!open || configModalOpen || uiEditMode) {
            return;
        }
        if (mouseWheel == 0) {
            return;
        }
        if (contextMenu != null && contextMenu.isHovered(mouseX, mouseY) && contextMenu.isScrollable()) {
            contextMenu.targetScrollOffset += mouseWheel < 0 ? s(42.0F) : -s(42.0F);
            contextMenu.clampTargetScroll();
            return;
        }

        CategoryPanel hovered = getTopHoveredPanel(mouseX, mouseY);
        if (hovered != null && hovered.isScrollable()) {
            hovered.targetScrollOffset += mouseWheel < 0 ? s(84.0F) : -s(84.0F);
            hovered.clampTargetScroll();
        }
    }

    private static void render(float mx, float my, float alpha) {
        int screenW = Display.getWidth();
        int screenH = Display.getHeight();

        // Dark overlay
        drawRect(0.0F, 0.0F, screenW, screenH,
                color(0, 0, 0, (int)(150.0F * alpha * optionNumber("Overlay Alpha", 0.58D).floatValue())));

        // Optional gaussian blur — captures screen BEFORE wave so wave is not blurred
        if (optionBoolean("Background Blur", false)) {
            renderBlurVeil(alpha, optionNumber("Blur Radius", 12.0D).floatValue());
        }

        // Wave rendered here — after blur capture, so blur doesn't affect it.
        // Uses GL_ALL_ATTRIB_BITS push/pop so it's fully self-contained.
        if (optionBoolean("Wave Background", true) && alpha > 0.01F) {
            try {
                Color accent = accentColor(1.0F);
                FloatingLines.setAccentColor(accent);
                float accentAlpha = accent.getAlpha() / 255.0F;
                FloatingLines.render(alpha * optionNumber("Wave Alpha", 0.36D).floatValue() * accentAlpha);
            } catch (Throwable t) {
                warnThrottled("FloatingLines render failed: " + t.getMessage());
            }
        }

        float globalScale = 0.92F + 0.08F * alpha;

        GL11.glPushMatrix();
        GL11.glTranslatef(screenW / 2.0F, screenH / 2.0F, 0.0F);
        GL11.glScalef(globalScale, globalScale, 1.0F);
        GL11.glTranslatef(-screenW / 2.0F, -screenH / 2.0F, 0.0F);

        if (uiEditMode) {
            renderEditModeOverlay(mx, my, alpha);
        } else {
            mouseConsumed = false;
            if (draggingPanel != null && !leftDown) {
                draggingPanel = null;
                draggingPanelActive = false;
            }

            List<Module> modules = getModules();
            for (CategoryPanel panel : PANELS.values()) {
                panel.ensureModuleAnimationSize(getModulesFor(panel.category, modules).size());
                panel.update(deltaTime, mx, my);
                panel.keepInside(screenW, screenH);
            }
            for (CategoryPanel panel : PANELS.values()) {
                panel.render(getModulesFor(panel.category, modules), mx, my, alpha);
            }

            if (contextMenu != null) {
                contextMenu.update(deltaTime);
                contextMenu.render(mx, my, alpha);
                if (leftClicked && !contextMenu.isHovered(mx, my) && activeDropdown == null && !mouseConsumed) {
                    contextMenu.closing = true;
                    mouseConsumed = true;
                }
                if (contextMenu.closing && contextMenu.openAnimation < 0.04F) {
                    contextMenu = null;
                    activeDropdown = null;
                    activeColorPicker = null;
                }
            }

            renderDropdownList(mx, my, alpha);
            renderEscHint(alpha);
        }

        if (configModalOpen || configModalAnimation > 0.01F) {
            renderConfigModal(mx, my, alpha);
        }
        GL11.glPopMatrix();
    }

    private static void renderBlurVeil(float alpha, float radius) {
        GaussianBlur.renderBlur(Math.max(1.0F, radius * clamp(alpha, 0.0F, 1.0F)));
    }

    private static void renderBackgroundGrid(float mx, float my, float alpha) {
        if (GRID_POINTS.isEmpty()) {
            rebuildGrid();
        }
        float time = System.currentTimeMillis();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.0F);

        for (GridPoint point : GRID_POINTS) {
            point.update(mx, my, time);
        }

        for (int i = 0; i < GRID_POINTS.size(); i++) {
            GridPoint a = GRID_POINTS.get(i);
            for (int j = i + 1; j < GRID_POINTS.size(); j++) {
                GridPoint b = GRID_POINTS.get(j);
                float dx = a.x - b.x;
                float dy = a.y - b.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < s(86.0F)) {
                    float lineAlpha = (1.0F - dist / s(86.0F)) * 0.11F * alpha;
                    GL11.glColor4f(1.0F, 1.0F, 1.0F, lineAlpha);
                    GL11.glBegin(GL11.GL_LINES);
                    GL11.glVertex2f(a.x, a.y);
                    GL11.glVertex2f(b.x, b.y);
                    GL11.glEnd();
                }
            }
        }

        for (GridPoint point : GRID_POINTS) {
            drawCircle(point.x, point.y, point.size, color(255, 255, 255, (int) (70.0F * point.alpha * alpha)));
        }

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void rebuildGrid() {
        GRID_POINTS.clear();
        int gap = (int) s(70.0F);
        if (gap < 34) {
            gap = 34;
        }
        for (int y = -gap; y <= Display.getHeight() + gap; y += gap) {
            for (int x = -gap; x <= Display.getWidth() + gap; x += gap) {
                GRID_POINTS.add(new GridPoint(x, y));
            }
        }
    }

    private static void renderEscHint(float alpha) {
        String esc = "ESC";
        String close = "to close";
        String hint = "right-click modules for settings";
        float scale = s(0.70F);
        float h = s(22.0F);
        float gap = s(7.0F);
        float pad = s(10.0F);
        float w = pad * 2.0F + textWidth(esc) * scale + gap + textWidth(close) * scale + gap
                + textWidth(hint) * scale;
        float x = Display.getWidth() / 2.0F - w / 2.0F;
        float y = s(12.0F);
        drawSoftShadow(x, y, w, h, h / 2.0F, alpha * 0.4F);
        drawRoundedRect(x, y, w, h, h / 2.0F, color(17, 17, 20, (int) (170.0F * alpha)));
        drawRoundedOutline(x, y, w, h, h / 2.0F, color(255, 255, 255, (int) (28.0F * alpha)));
        drawRoundedRect(x + pad - s(4.0F), y + s(4.0F), textWidth(esc) * scale + s(8.0F), h - s(8.0F),
                (h - s(8.0F)) / 2.0F, color(255, 255, 255, (int) (22.0F * alpha)));
        float textY = y + (h - fontHeight() * scale) / 2.0F;
        float cursor = x + pad;
        drawText(esc, cursor, textY, applyAlpha(TEXT_PRIMARY, alpha), false, scale);
        cursor += textWidth(esc) * scale + gap + s(4.0F);
        drawText(close, cursor, textY, applyAlpha(TEXT_SECONDARY, alpha), false, scale);
        cursor += textWidth(close) * scale + gap;
        drawText(hint, cursor, textY, applyAlpha(TEXT_MUTED, alpha), false, scale);
    }

    private static void renderEditModeOverlay(float mx, float my, float alpha) {
        drawEditGrid(Display.getWidth(), Display.getHeight(), alpha * 0.18F);
        // Title removed — edit mode is indicated by the blue borders on HUD elements.

        float buttonW = s(86.0F);
        float buttonH = s(30.0F);
        float gap = s(14.0F);
        float x = Display.getWidth() / 2.0F - (buttonW * 2.0F + gap) / 2.0F;
        float y = Display.getHeight() - buttonH - s(38.0F);

        boolean exitHovered = isInside(mx, my, x, y, buttonW, buttonH);
        boolean resetHovered = isInside(mx, my, x + buttonW + gap, y, buttonW, buttonH);
        exitEditHover = lerp(exitEditHover, exitHovered ? 1.0F : 0.0F, deltaTime * 14.0F);
        resetEditHover = lerp(resetEditHover, resetHovered ? 1.0F : 0.0F, deltaTime * 14.0F);

        renderEditButton("Exit", x, y, buttonW, buttonH, exitEditHover, alpha, false);
        renderEditButton("Reset", x + buttonW + gap, y, buttonW, buttonH, resetEditHover, alpha, true);

        if (exitHovered && leftClicked) {
            uiEditMode = false;
            leftClicked = false;
        } else if (resetHovered && leftClicked) {
            layoutPanels();
            leftClicked = false;
        }
    }

    private static void renderEditButton(String label, float x, float y, float w, float h, float hover, float alpha,
            boolean danger) {
        int bg = lerpColor(color(24, 24, 27, 190), danger ? color(70, 32, 36, 220) : color(42, 42, 48, 220), hover);
        int border = lerpColor(color(255, 255, 255, 35), danger ? color(255, 120, 120, 90) : color(255, 255, 255, 85),
                hover);
        drawSoftShadow(x, y, w, h, h / 2.0F, alpha * 0.35F);
        drawRoundedRect(x, y, w, h, h / 2.0F, applyAlpha(bg, alpha));
        drawRoundedOutline(x, y, w, h, h / 2.0F, applyAlpha(border, alpha));
        float scale = s(0.86F);
        drawText(label, x + (w - textWidth(label) * scale) / 2.0F, y + (h - fontHeight() * scale) / 2.0F,
                applyAlpha(danger ? color(255, 218, 218, 255) : TEXT_PRIMARY, alpha), false, scale);
    }

    private static void renderDropdownList(float mx, float my, float globalAlpha) {
        if (activeDropdown == null) {
            dropdownAnimation = 0.0F;
            return;
        }

        dropdownAnimation = lerp(dropdownAnimation, 1.0F, deltaTime * 16.0F);
        List<String> modes = activeDropdown.getModes();
        float itemH = s(19.0F);
        float totalH = itemH * modes.size() + s(5.0F);
        float actualY = dropdownY;
        if (dropdownY + totalH > Display.getHeight() - s(10.0F)) {
            actualY = dropdownY - totalH - s(23.0F);
        }

        float alpha = globalAlpha * dropdownAnimation;
        float scale = 0.95F + 0.05F * dropdownAnimation;
        float centerX = dropdownX + dropdownW / 2.0F;
        GL11.glPushMatrix();
        GL11.glTranslatef(0.0F, 0.0F, 400.0F);
        GL11.glTranslatef(centerX, actualY, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        GL11.glTranslatef(-centerX, -actualY, 0.0F);

        drawSoftShadow(dropdownX, actualY, dropdownW, totalH, s(7.0F), alpha * 0.5F);
        drawRoundedRect(dropdownX, actualY, dropdownW, totalH, s(7.0F), color(16, 16, 20, (int) (230.0F * alpha)));
        drawRoundedOutline(dropdownX, actualY, dropdownW, totalH, s(7.0F),
                color(255, 255, 255, (int) (42.0F * alpha)));

        float itemY = actualY + s(2.0F);
        boolean clickedInside = false;
        for (String mode : modes) {
            boolean selected = mode.equals(activeDropdown.getValue());
            boolean hovered = isInside(mx, my, dropdownX, itemY, dropdownW, itemH);
            if (selected || hovered) {
                drawRoundedRect(dropdownX + s(2.0F), itemY, dropdownW - s(4.0F), itemH, s(5.0F),
                        color(255, 255, 255, (int) ((selected ? 44.0F : 24.0F) * alpha)));
            }
            if (selected) {
                drawCheck(dropdownX + s(9.0F), itemY + itemH / 2.0F, s(0.75F), alpha);
            }
            float textScale = s(0.74F);
            drawText(mode, dropdownX + (selected ? s(22.0F) : s(10.0F)),
                    itemY + (itemH - fontHeight() * textScale) / 2.0F,
                    applyAlpha(selected || hovered ? TEXT_PRIMARY : TEXT_SECONDARY, alpha), false, textScale);
            if (hovered && leftClicked) {
                activeDropdown.setValue(mode);
                activeDropdown = null;
                clickedInside = true;
                leftClicked = false;
                break;
            }
            itemY += itemH;
        }
        GL11.glPopMatrix();

        if (leftClicked && !clickedInside) {
            activeDropdown = null;
            leftClicked = false;
        }
    }

    private static void renderConfigModal(float mx, float my, float globalAlpha) {
        if (configModalAnimation < 0.01F) {
            return;
        }

        float alpha = configModalAnimation * globalAlpha;
        float modalW = s(510.0F);
        float modalH = s(324.0F);
        float modalX = Display.getWidth() / 2.0F - modalW / 2.0F;
        float modalY = Display.getHeight() / 2.0F - modalH / 2.0F;
        float sidebarW = s(154.0F);
        float radius = s(14.0F);
        float scale = 0.95F + 0.05F * configModalAnimation;

        drawRect(0.0F, 0.0F, Display.getWidth(), Display.getHeight(), color(0, 0, 0, (int) (100.0F * alpha)));

        GL11.glPushMatrix();
        GL11.glTranslatef(Display.getWidth() / 2.0F, Display.getHeight() / 2.0F, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        GL11.glTranslatef(-Display.getWidth() / 2.0F, -Display.getHeight() / 2.0F, 0.0F);

        drawSoftShadow(modalX, modalY, modalW, modalH, radius, alpha * 0.75F);
        drawRoundedRect(modalX, modalY, modalW, modalH, radius, color(17, 17, 21, (int) (222.0F * alpha)));
        drawRoundedOutline(modalX, modalY, modalW, modalH, radius, color(255, 255, 255, (int) (45.0F * alpha)));
        drawRoundedRect(modalX, modalY, sidebarW, modalH, radius, color(0, 0, 0, (int) (42.0F * alpha)));
        drawRect(modalX + sidebarW - s(1.0F), modalY + s(14.0F), s(1.0F), modalH - s(28.0F),
                color(255, 255, 255, (int) (18.0F * alpha)));

        float searchX = modalX + s(10.0F);
        float searchY = modalY + s(15.0F);
        float searchW = sidebarW - s(20.0F);
        float searchH = s(23.0F);
        searchField.updateCoordinates(searchX, searchY, searchW, searchH);
        boolean searchHovered = isInside(mx, my, searchX, searchY, searchW, searchH);
        drawRoundedRect(searchX, searchY, searchW, searchH, s(7.0F),
                color(0, 0, 0, (int) ((isTypingSearch ? 118.0F : 70.0F) * alpha)));
        if (isTypingSearch) {
            drawRoundedOutline(searchX, searchY, searchW, searchH, s(7.0F), applyAlpha(TEXT_PRIMARY, alpha * 0.32F));
        }
        searchField.draw(alpha);

        if (searchHovered && leftClicked) {
            isTypingSearch = true;
            isTypingConfigName = false;
            searchField.setFocused(true);
            configNameField.setFocused(false);
            searchField.handleMouse(mx, my);
            leftClicked = false;
        }

        renderConfigList(mx, my, alpha, modalX, modalY, sidebarW, modalH, searchY + searchH + s(12.0F));
        renderConfigActions(mx, my, alpha, modalX + sidebarW + s(24.0F), modalY + s(25.0F),
                modalW - sidebarW - s(48.0F));

        GL11.glPopMatrix();

        if (leftClicked && !isInside(mx, my, modalX, modalY, modalW, modalH) && !isTypingConfigName && !isTypingSearch) {
            configModalOpen = false;
            clearTyping();
            leftClicked = false;
        }
    }

    private static void renderConfigList(float mx, float my, float alpha, float modalX, float modalY, float sidebarW,
            float modalH, float startY) {
        String filter = searchField.getText().toLowerCase();
        String current = ConfigManager.getInstance().getCurrentConfig();
        float itemH = s(26.0F);
        float y = startY;

        for (String config : configList) {
            if (filter.length() > 0 && !config.toLowerCase().contains(filter)) {
                continue;
            }
            if (y + itemH > modalY + modalH - s(10.0F)) {
                break;
            }
            boolean active = config.equals(current);
            boolean selected = config.equals(selectedConfig);
            boolean hovered = isInside(mx, my, modalX + s(6.0F), y, sidebarW - s(12.0F), itemH);
            if (active || selected || hovered) {
                drawRoundedRect(modalX + s(6.0F), y, sidebarW - s(12.0F), itemH, s(8.0F),
                        color(255, 255, 255, (int) ((selected ? 42.0F : hovered ? 20.0F : 14.0F) * alpha)));
                if (selected) {
                    drawRoundedRect(modalX + s(8.0F), y + s(7.0F), s(2.0F), itemH - s(14.0F), s(1.0F),
                            applyAlpha(TEXT_PRIMARY, alpha));
                }
            }
            float scale = s(0.62F);
            String name = trimToWidth(config, (int) (sidebarW - s(36.0F)), scale);
            drawText(name, modalX + (selected || active ? s(16.0F) : s(12.0F)),
                    y + (itemH - fontHeight() * scale) / 2.0F,
                    applyAlpha(selected || active ? TEXT_PRIMARY : TEXT_SECONDARY, alpha), false, scale);
            if (active) {
                drawCircle(modalX + sidebarW - s(14.0F), y + itemH / 2.0F, s(2.2F),
                        applyAlpha(TEXT_PRIMARY, alpha));
            }
            if (hovered && leftClicked) {
                selectedConfig = config;
                leftClicked = false;
            }
            y += itemH + s(2.0F);
        }
    }

    private static void renderConfigActions(float mx, float my, float alpha, float contentX, float contentY,
            float contentW) {
        drawRect(contentX, contentY + s(12.0F), contentW, s(1.0F), color(255, 255, 255, (int) (16.0F * alpha)));
        float labelScale = s(0.48F);
        drawText("SELECTED PROFILE", contentX, contentY + s(30.0F), applyAlpha(TEXT_SECONDARY, alpha), false,
                labelScale);

        String title = selectedConfig == null ? "default" : selectedConfig.toUpperCase();
        title = trimToWidth(title, (int) contentW, s(1.02F));
        drawText(title, contentX, contentY + s(43.0F), applyAlpha(TEXT_PRIMARY, alpha), true, s(1.02F));

        String current = ConfigManager.getInstance().getCurrentConfig();
        boolean active = selectedConfig != null && selectedConfig.equals(current);
        int statusColor = active ? TEXT_PRIMARY : TEXT_SECONDARY;
        float statusY = contentY + s(82.0F);
        drawRoundedRect(contentX, statusY + s(2.0F), s(40.0F), s(1.3F), s(1.0F), applyAlpha(statusColor, alpha));
        drawText(active ? "Status: ACTIVE" : "Status: READY TO LOAD", contentX + s(47.0F), statusY - s(2.0F),
                applyAlpha(statusColor, alpha), false, s(0.52F));

        float gridY = statusY + s(22.0F);
        float gap = s(8.0F);
        float buttonH = s(36.0F);
        float buttonW = (contentW - gap * 2.0F) / 3.0F;
        renderActionButton("LOAD", "Apply settings", contentX, gridY, buttonW, buttonH,
                isInside(mx, my, contentX, gridY, buttonW, buttonH), alpha, false);
        renderActionButton("SAVE", "Overwrite", contentX + buttonW + gap, gridY, buttonW, buttonH,
                isInside(mx, my, contentX + buttonW + gap, gridY, buttonW, buttonH), alpha, false);
        boolean canDelete = selectedConfig != null && !"default".equalsIgnoreCase(selectedConfig) && !active;
        renderActionButton("DELETE", "Permanent", contentX + (buttonW + gap) * 2.0F, gridY, buttonW, buttonH,
                isInside(mx, my, contentX + (buttonW + gap) * 2.0F, gridY, buttonW, buttonH) && canDelete, alpha,
                true, canDelete);

        if (leftClicked) {
            ModuleManager manager = getModuleManager();
            if (isInside(mx, my, contentX, gridY, buttonW, buttonH)) {
                ConfigManager.getInstance().loadConfig(manager, selectedConfig);
                leftClicked = false;
            } else if (isInside(mx, my, contentX + buttonW + gap, gridY, buttonW, buttonH)) {
                ConfigManager.getInstance().saveConfig(manager, selectedConfig);
                configList = ConfigManager.getInstance().getConfigList();
                leftClicked = false;
            } else if (canDelete && isInside(mx, my, contentX + (buttonW + gap) * 2.0F, gridY, buttonW, buttonH)) {
                if (ConfigManager.getInstance().deleteConfig(selectedConfig)) {
                    configList = ConfigManager.getInstance().getConfigList();
                    selectedConfig = ConfigManager.getInstance().getCurrentConfig();
                }
                leftClicked = false;
            }
        }

        float footerY = gridY + buttonH + s(16.0F);
        drawRect(contentX, footerY, contentW, s(1.0F), color(255, 255, 255, (int) (16.0F * alpha)));
        float inputY = footerY + s(15.0F);
        float inputW = contentW - s(60.0F);
        float inputH = s(27.0F);
        configNameField.updateCoordinates(contentX, inputY, inputW, inputH);
        boolean inputHovered = isInside(mx, my, contentX, inputY, inputW, inputH);
        drawRoundedRect(contentX, inputY, inputW, inputH, s(8.0F),
                color(0, 0, 0, (int) ((isTypingConfigName ? 120.0F : 78.0F) * alpha)));
        if (isTypingConfigName) {
            drawRoundedOutline(contentX, inputY, inputW, inputH, s(8.0F), applyAlpha(TEXT_PRIMARY, alpha * 0.32F));
        }
        configNameField.draw(alpha);

        float createX = contentX + inputW + s(8.0F);
        float createW = s(52.0F);
        boolean canCreate = configNameField.getText().trim().length() > 0;
        boolean createHovered = canCreate && isInside(mx, my, createX, inputY, createW, inputH);
        drawRoundedRect(createX, inputY, createW, inputH, s(8.0F),
                color(255, 255, 255, (int) ((createHovered ? 40.0F : 20.0F) * alpha * (canCreate ? 1.0F : 0.45F))));
        float doneScale = s(0.58F);
        drawText("DONE", createX + (createW - textWidth("DONE") * doneScale) / 2.0F,
                inputY + (inputH - fontHeight() * doneScale) / 2.0F,
                applyAlpha(TEXT_PRIMARY, canCreate ? alpha : alpha * 0.45F), false, doneScale);

        if (inputHovered && leftClicked) {
            isTypingConfigName = true;
            isTypingSearch = false;
            configNameField.setFocused(true);
            searchField.setFocused(false);
            configNameField.handleMouse(mx, my);
            leftClicked = false;
        } else if (createHovered && leftClicked) {
            createConfigFromField();
            leftClicked = false;
        } else if (leftClicked && !inputHovered) {
            isTypingConfigName = false;
            configNameField.setFocused(false);
        }
    }

    private static void renderActionButton(String title, String subtitle, float x, float y, float w, float h,
            boolean hovered, float alpha, boolean danger) {
        renderActionButton(title, subtitle, x, y, w, h, hovered, alpha, danger, true);
    }

    private static void renderActionButton(String title, String subtitle, float x, float y, float w, float h,
            boolean hovered, float alpha, boolean danger, boolean enabled) {
        int bg;
        if (!enabled) {
            bg = color(28, 28, 33, (int) (80.0F * alpha));
        } else if (danger) {
            bg = hovered ? color(200, 50, 50, (int) (86.0F * alpha)) : color(200, 50, 50, (int) (34.0F * alpha));
        } else {
            bg = color(255, 255, 255, (int) ((hovered ? 42.0F : 20.0F) * alpha));
        }
        drawRoundedRect(x, y, w, h, s(8.0F), bg);
        drawRoundedOutline(x, y, w, h, s(8.0F), color(255, 255, 255, (int) ((enabled ? 36.0F : 14.0F) * alpha)));
        drawText(title, x + s(8.0F), y + s(7.0F), applyAlpha(TEXT_PRIMARY, enabled ? alpha : alpha * 0.45F), false,
                s(0.56F));
        drawText(subtitle, x + s(8.0F), y + s(20.0F), applyAlpha(TEXT_SECONDARY, enabled ? alpha : alpha * 0.45F),
                false, s(0.46F));
    }

    private static void createConfigFromField() {
        String name = configNameField.getText().trim();
        if (name.length() == 0) {
            return;
        }
        ConfigManager.getInstance().saveConfig(getModuleManager(), name);
        configList = ConfigManager.getInstance().getConfigList();
        selectedConfig = ConfigManager.getInstance().getCurrentConfig();
        configNameField.setText("");
        isTypingConfigName = false;
        configNameField.setFocused(false);
    }

    private static void drawEditGrid(int width, int height, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, alpha);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINES);
        int step = Math.max(30, (int) s(48.0F));
        for (int x = 0; x <= width; x += step) {
            GL11.glVertex2f(x, 0);
            GL11.glVertex2f(x, height);
        }
        for (int y = 0; y <= height; y += step) {
            GL11.glVertex2f(0, y);
            GL11.glVertex2f(width, y);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void updateDelta() {
        long now = System.currentTimeMillis();
        deltaTime = clamp((now - lastFrameTime) / 1000.0F, 0.001F, 0.08F);
        lastFrameTime = now;
    }

    private static void updateScale() {
        double configured = optionNumber("UI Scale", 1.5D);
        float next = clamp((float) configured, 1.0F, 2.0F);
        boolean changed = Math.abs(next - guiScale) > 0.01F;
        guiScale = next;

        int displayW = Display.getWidth();
        int displayH = Display.getHeight();
        int moduleCount = getModules().size();
        if (changed || displayW != lastDisplayWidth || displayH != lastDisplayHeight || moduleCount != lastModuleCount) {
            if (!draggingPanelActive) {
                ensureInitialized();
                for (CategoryPanel panel : PANELS.values()) {
                    panel.keepInside(displayW, displayH);
                }
            }
            GRID_POINTS.clear();
            lastDisplayWidth = displayW;
            lastDisplayHeight = displayH;
            lastModuleCount = moduleCount;
        }
    }

    private static void updateMouseState() {
        mouseX = getMouseX();
        mouseY = getMouseY();
        leftDown = isMouseDown(0);
        rightDown = isMouseDown(1);
        leftClicked = leftDown && !lastLeftDown;
        rightClicked = rightDown && !lastRightDown;
        lastLeftDown = leftDown;
        lastRightDown = rightDown;
        try {
            mouseWheel = Mouse.getDWheel();
        } catch (Throwable ignored) {
            mouseWheel = 0;
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        layoutPanels();
        configList = ConfigManager.getInstance().getConfigList();
        selectedConfig = ConfigManager.getInstance().getCurrentConfig();
        initialized = true;
    }

    private static void layoutPanels() {
        PANELS.clear();
        float startX = s(74.0F);
        float startY = s(76.0F);
        float x = startX;
        float y = startY;
        float maxHeight = 0.0F;
        float screenW = Math.max(1, Display.getWidth());

        for (Category category : Category.values()) {
            CategoryPanel panel = new CategoryPanel(category, x, y);
            PANELS.put(category, panel);
            maxHeight = Math.max(maxHeight, panel.getEstimatedHeight());
            x += s(PANEL_WIDTH + PANEL_GAP);
            if (x + s(PANEL_WIDTH) > screenW - s(32.0F)) {
                x = startX;
                y += maxHeight + s(PANEL_GAP);
                maxHeight = 0.0F;
            }
        }
    }

    private static CategoryPanel getTopHoveredPanel(float mx, float my) {
        CategoryPanel top = null;
        for (CategoryPanel panel : PANELS.values()) {
            if (panel.isHovered(mx, my)) {
                top = panel;
            }
        }
        return top;
    }

    private static List<Module> getModulesFor(Category category) {
        return getModulesFor(category, getModules());
    }

    private static List<Module> getModulesFor(Category category, List<Module> modules) {
        List<Module> result = new ArrayList<Module>();
        for (Module module : modules) {
            if (module.getCategory() == category) {
                result.add(module);
            }
        }
        return result;
    }

    private static List<Module> getModules() {
        ModuleManager manager = getModuleManager();
        return manager == null ? new ArrayList<Module>() : manager.getModules();
    }

    private static ModuleManager getModuleManager() {
        try {
            return Pewa.getInstance().getModuleManager();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Module getClickGuiModule() {
        ModuleManager manager = getModuleManager();
        if (manager == null) {
            return null;
        }
        Module module = manager.getModule(ClickGuiModule.class);
        return module != null ? module : manager.getModule("ClickGUI");
    }

    private static OptionBase<?> guiOption(String name) {
        Module module = getClickGuiModule();
        return module == null ? null : module.getOption(name);
    }

    private static boolean optionBoolean(String name, boolean fallback) {
        OptionBase<?> option = guiOption(name);
        return option instanceof BooleanOption ? ((BooleanOption) option).getValue() : fallback;
    }

    private static Double optionNumber(String name, double fallback) {
        OptionBase<?> option = guiOption(name);
        return option instanceof NumberOption ? ((NumberOption) option).getValue() : fallback;
    }

    private static String optionString(String name, String fallback) {
        OptionBase<?> option = guiOption(name);
        return option instanceof StringOption ? ((StringOption) option).getValue() : fallback;
    }

    private static Color accentColor(float alpha) {
        OptionBase<?> option = guiOption("Accent");
        Color color = option instanceof ColorOption ? ((ColorOption) option).getValue() : new Color(228, 228, 231);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clampInt((int) (color.getAlpha() * alpha)));
    }

    private static boolean isTyping() {
        return isTypingSearch || isTypingConfigName || isTypingText;
    }

    private static void clearTyping() {
        isTypingSearch = false;
        isTypingConfigName = false;
        isTypingText = false;
        searchField.setFocused(false);
        configNameField.setFocused(false);
        activeTextField.setFocused(false);
        activeTextOption = null;
    }

    private static float getMouseX() {
        try {
            return Mouse.getX();
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }

    private static float getMouseY() {
        try {
            return Display.getHeight() - Mouse.getY();
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }

    private static boolean isKeyDown(int key) {
        try {
            return Keyboard.isKeyDown(key);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMouseDown(int button) {
        try {
            return Mouse.isButtonDown(button);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void releaseMouse() {
        try {
            if (!mouseGrabReleased) {
                mouseWasGrabbed = Mouse.isGrabbed();
                mouseGrabReleased = true;
            }
            if (Mouse.isGrabbed()) {
                Mouse.setGrabbed(false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void restoreMouse() {
        try {
            if (mouseGrabReleased && mouseWasGrabbed) {
                Mouse.setGrabbed(true);
            }
        } catch (Throwable ignored) {
        } finally {
            mouseGrabReleased = false;
            mouseWasGrabbed = false;
        }
    }

    private static void pushState() {
        int width = Math.max(1, Display.getWidth());
        int height = Math.max(1, Display.getHeight());

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT | GL11.GL_LINE_BIT | GL11.GL_SCISSOR_BIT
                | GL11.GL_CURRENT_BIT);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, width, height, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void popState() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();
    }

    private static void drawText(String text, float x, float y, int color, boolean shadow, float scale) {
        if (text == null || text.length() == 0 || alpha(color) <= 0) {
            return;
        }
        if (!FontUtil.drawString(text, x, y, color, shadow, scale)) {
            warnThrottled("ClickGui drawText skipped: FontRenderer mapping is not ready");
        }
    }

    private static int textWidth(String text) {
        return FontUtil.getStringWidth(text);
    }

    private static int fontHeight() {
        return FontUtil.getFontHeight();
    }

    private static void drawRect(float x, float y, float width, float height, int color) {
        if (width <= 0.0F || height <= 0.0F || alpha(color) <= 0) {
            return;
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        setColor(color);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawRoundedRect(float x, float y, float width, float height, float radius, Color color) {
        drawRoundedRect(x, y, width, height, radius, color.getRGB());
    }

    private static void drawRoundedRect(float x, float y, float width, float height, float radius, int color) {
        if (width <= 0.0F || height <= 0.0F || alpha(color) <= 0) {
            return;
        }
        radius = Math.min(radius, Math.min(width, height) / 2.0F);
        if (radius <= 0.0F) {
            drawRect(x, y, width, height, color);
            return;
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        setColor(color);
        drawQuad(x + radius, y, width - radius * 2.0F, height);
        drawQuad(x, y + radius, radius, height - radius * 2.0F);
        drawQuad(x + width - radius, y + radius, radius, height - radius * 2.0F);
        drawCorner(x + radius, y + radius, radius, 180, 270);
        drawCorner(x + width - radius, y + radius, radius, 270, 360);
        drawCorner(x + width - radius, y + height - radius, radius, 0, 90);
        drawCorner(x + radius, y + height - radius, radius, 90, 180);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawRoundedOutline(float x, float y, float width, float height, float radius, int color) {
        if (width <= 0.0F || height <= 0.0F || alpha(color) <= 0) {
            return;
        }
        radius = Math.min(radius, Math.min(width, height) / 2.0F);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.0F);
        setColor(color);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        addArcVertices(x + radius, y + radius, radius, 180, 270);
        addArcVertices(x + width - radius, y + radius, radius, 270, 360);
        addArcVertices(x + width - radius, y + height - radius, radius, 0, 90);
        addArcVertices(x + radius, y + height - radius, radius, 90, 180);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawSoftShadow(float x, float y, float w, float h, float radius, float alpha) {
        if (!optionBoolean("Soft Shadows", true)) {
            return;
        }
        for (int i = 1; i <= 6; i++) {
            float p = (float) Math.pow(1.0F - i / 7.0F, 2.7D);
            drawRoundedRect(x - i, y - i + s(2.0F), w + i * 2.0F, h + i * 2.0F, radius + i,
                    color(0, 0, 0, (int) (95.0F * p * alpha)));
        }
    }

    private static void drawCircle(float cx, float cy, float r, int color) {
        if (r <= 0.0F || alpha(color) <= 0) {
            return;
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        setColor(color);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 32; i++) {
            double angle = Math.PI * 2.0D * i / 32.0D;
            GL11.glVertex2f(cx + (float) Math.cos(angle) * r, cy + (float) Math.sin(angle) * r);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawGradientRect(float x, float y, float w, float h, int c1, int c2, boolean horizontal) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glBegin(GL11.GL_QUADS);
        if (horizontal) {
            setColor(c1);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + h);
            setColor(c2);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x + w, y);
        } else {
            setColor(c1);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + w, y);
            setColor(c2);
            GL11.glVertex2f(x + w, y + h);
            GL11.glVertex2f(x, y + h);
        }
        GL11.glEnd();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawQuad(float x, float y, float width, float height) {
        if (width <= 0.0F || height <= 0.0F) {
            return;
        }
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }

    private static void drawCorner(float cx, float cy, float radius, int startDeg, int endDeg) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int deg = startDeg; deg <= endDeg; deg += 8) {
            double rad = Math.toRadians(deg);
            GL11.glVertex2f(cx + (float) Math.cos(rad) * radius, cy + (float) Math.sin(rad) * radius);
        }
        double end = Math.toRadians(endDeg);
        GL11.glVertex2f(cx + (float) Math.cos(end) * radius, cy + (float) Math.sin(end) * radius);
        GL11.glEnd();
    }

    private static void addArcVertices(float cx, float cy, float radius, int startDeg, int endDeg) {
        for (int deg = startDeg; deg <= endDeg; deg += 8) {
            double rad = Math.toRadians(deg);
            GL11.glVertex2f(cx + (float) Math.cos(rad) * radius, cy + (float) Math.sin(rad) * radius);
        }
    }

    private static void drawCategoryIcon(Category category, float x, float y, float size, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        setColor(applyAlpha(TEXT_SECONDARY, alpha));
        GL11.glLineWidth(Math.max(1.0F, s(1.4F)));
        GL11.glBegin(GL11.GL_LINES);
        float cx = x + size / 2.0F;
        float cy = y + size / 2.0F;
        if (category == Category.COMBAT) {
            GL11.glVertex2f(cx - size * 0.25F, cy + size * 0.30F);
            GL11.glVertex2f(cx + size * 0.25F, cy - size * 0.30F);
            GL11.glVertex2f(cx - size * 0.18F, cy - size * 0.08F);
            GL11.glVertex2f(cx + size * 0.08F, cy + size * 0.18F);
        } else if (category == Category.MOVEMENT) {
            GL11.glVertex2f(cx - size * 0.25F, cy);
            GL11.glVertex2f(cx + size * 0.20F, cy);
            GL11.glVertex2f(cx + size * 0.20F, cy);
            GL11.glVertex2f(cx, cy - size * 0.20F);
            GL11.glVertex2f(cx + size * 0.20F, cy);
            GL11.glVertex2f(cx, cy + size * 0.20F);
        } else if (category == Category.RENDER) {
            GL11.glVertex2f(cx - size * 0.28F, cy);
            GL11.glVertex2f(cx, cy - size * 0.22F);
            GL11.glVertex2f(cx, cy - size * 0.22F);
            GL11.glVertex2f(cx + size * 0.28F, cy);
            GL11.glVertex2f(cx + size * 0.28F, cy);
            GL11.glVertex2f(cx, cy + size * 0.22F);
            GL11.glVertex2f(cx, cy + size * 0.22F);
            GL11.glVertex2f(cx - size * 0.28F, cy);
        } else {
            GL11.glVertex2f(cx - size * 0.23F, cy - size * 0.23F);
            GL11.glVertex2f(cx + size * 0.23F, cy + size * 0.23F);
            GL11.glVertex2f(cx + size * 0.23F, cy - size * 0.23F);
            GL11.glVertex2f(cx - size * 0.23F, cy + size * 0.23F);
        }
        GL11.glEnd();
        if (category == Category.PLAYER) {
            drawCircle(cx, cy - size * 0.18F, size * 0.13F, applyAlpha(TEXT_SECONDARY, alpha));
            drawRoundedOutline(cx - size * 0.22F, cy + size * 0.04F, size * 0.44F, size * 0.24F, size * 0.10F,
                    applyAlpha(TEXT_SECONDARY, alpha));
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawArrow(float cx, float cy, float scale, boolean collapsed, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        setColor(applyAlpha(TEXT_MUTED, alpha));
        GL11.glBegin(GL11.GL_TRIANGLES);
        if (collapsed) {
            GL11.glVertex2f(cx - scale, cy - scale * 0.6F);
            GL11.glVertex2f(cx - scale, cy + scale * 0.6F);
            GL11.glVertex2f(cx + scale * 0.8F, cy);
        } else {
            GL11.glVertex2f(cx - scale, cy - scale * 0.35F);
            GL11.glVertex2f(cx + scale, cy - scale * 0.35F);
            GL11.glVertex2f(cx, cy + scale * 0.8F);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawCheck(float x, float y, float scale, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        setColor(applyAlpha(TEXT_PRIMARY, alpha));
        GL11.glLineWidth(Math.max(1.0F, s(1.4F)));
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + s(3.0F) * scale, y + s(3.0F) * scale);
        GL11.glVertex2f(x + s(8.0F) * scale, y - s(4.0F) * scale);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawFolderIcon(float x, float y, float size, float alpha) {
        drawRoundedOutline(x, y + size * 0.25F, size, size * 0.58F, size * 0.10F, applyAlpha(TEXT_MUTED, alpha));
        drawRect(x + size * 0.10F, y + size * 0.12F, size * 0.34F, size * 0.18F, applyAlpha(TEXT_MUTED, alpha));
    }

    private static void drawEditIcon(float x, float y, float size, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        setColor(applyAlpha(TEXT_SECONDARY, alpha));
        GL11.glLineWidth(Math.max(1.0F, s(1.2F)));
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(x + size * 0.20F, y + size * 0.78F);
        GL11.glVertex2f(x + size * 0.74F, y + size * 0.24F);
        GL11.glVertex2f(x + size * 0.86F, y + size * 0.36F);
        GL11.glVertex2f(x + size * 0.32F, y + size * 0.90F);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void enableScissor(float x, float y, float w, float h) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int sx = Math.max(0, (int) x);
        int sy = Math.max(0, (int) (Display.getHeight() - (y + h)));
        int sw = Math.max(0, (int) w);
        int sh = Math.max(0, (int) h);
        GL11.glScissor(sx, sy, sw, sh);
    }

    private static void setColor(int color) {
        GL11.glColor4f(((color >>> 16) & 255) / 255.0F, ((color >>> 8) & 255) / 255.0F,
                (color & 255) / 255.0F, ((color >>> 24) & 255) / 255.0F);
    }

    private static int color(int r, int g, int b, int a) {
        return ((clampInt(a) & 255) << 24) | ((clampInt(r) & 255) << 16) | ((clampInt(g) & 255) << 8)
                | (clampInt(b) & 255);
    }

    private static int alpha(int color) {
        return (color >>> 24) & 255;
    }

    private static int applyAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (clampInt((int) (alpha(color) * clamp(alpha, 0.0F, 1.0F))) << 24);
    }

    private static int lerpColor(int a, int b, float t) {
        t = clamp(t, 0.0F, 1.0F);
        int ar = (a >>> 16) & 255;
        int ag = (a >>> 8) & 255;
        int ab = a & 255;
        int aa = (a >>> 24) & 255;
        int br = (b >>> 16) & 255;
        int bg = (b >>> 8) & 255;
        int bb = b & 255;
        int ba = (b >>> 24) & 255;
        return color((int) (ar + (br - ar) * t), (int) (ag + (bg - ag) * t), (int) (ab + (bb - ab) * t),
                (int) (aa + (ba - aa) * t));
    }

    private static float s(float value) {
        return value * guiScale;
    }

    private static float lerp(float current, float target, float speed) {
        if (Math.abs(target - current) < 0.0005F) {
            return target;
        }
        return current + (target - current) * clamp(speed, 0.0F, 1.0F);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static boolean isInside(float mx, float my, float x, float y, float width, float height) {
        return mx >= x && my >= y && mx <= x + width && my <= y + height;
    }

    private static String keyName(int key) {
        if (key <= 0) {
            return "None";
        }
        try {
            String name = Keyboard.getKeyName(key);
            return name == null ? "Key " + key : name;
        } catch (Throwable ignored) {
            return "Key " + key;
        }
    }

    private static String trimToWidth(String text, int maxWidth, float scale) {
        return FontUtil.trimToWidth(text, maxWidth, scale);
    }

    private static void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnTime < 5000L) {
            return;
        }
        lastWarnTime = now;
        Logger.warn(message);
    }

    private static final class GridPoint {
        private final float baseX;
        private final float baseY;
        private float x;
        private float y;
        private float size;
        private float alpha;

        private GridPoint(float x, float y) {
            this.baseX = x;
            this.baseY = y;
            this.x = x;
            this.y = y;
            this.size = s(1.4F);
            this.alpha = 0.6F;
        }

        private void update(float mouseX, float mouseY, float time) {
            float waveX = (float) Math.sin(time * 0.001D + baseY * 0.010D) * s(2.0F);
            float waveY = (float) Math.cos(time * 0.001D + baseX * 0.010D) * s(2.0F);
            float dx = mouseX - baseX;
            float dy = mouseY - baseY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float force = Math.max(0.0F, 1.0F - dist / s(190.0F));
            float angle = (float) Math.atan2(dy, dx);
            float push = force * s(16.0F);
            float targetX = baseX + waveX - (float) Math.cos(angle) * push;
            float targetY = baseY + waveY - (float) Math.sin(angle) * push;
            x = lerp(x, targetX, deltaTime * 5.0F);
            y = lerp(y, targetY, deltaTime * 5.0F);
            size = s(1.0F + force * 1.3F);
            alpha = 0.35F + force * 0.65F;
        }
    }

    private static final class CategoryPanel {
        private final Category category;
        private float x;
        private float y;
        private boolean collapsed;
        private float openAnimation;
        private float collapseAnimation = 1.0F;
        private float scrollOffset;
        private float targetScrollOffset;
        private float scrollbarAlpha;
        private float editHoverAnimation;
        private float configHoverAnimation;
        private float[] moduleAnimations = new float[0];
        private float[] moduleHoverAnimations = new float[0];

        private CategoryPanel(Category category, float x, float y) {
            this.category = category;
            this.x = x;
            this.y = y;
            ensureModuleAnimationSize(getModulesFor(category).size());
        }

        private void ensureModuleAnimationSize(int moduleCount) {
            if (moduleAnimations.length == moduleCount) {
                return;
            }
            float[] oldRows = moduleAnimations;
            float[] oldHover = moduleHoverAnimations;
            moduleAnimations = new float[moduleCount];
            moduleHoverAnimations = new float[moduleCount];
            System.arraycopy(oldRows, 0, moduleAnimations, 0, Math.min(oldRows.length, moduleAnimations.length));
            System.arraycopy(oldHover, 0, moduleHoverAnimations, 0, Math.min(oldHover.length, moduleHoverAnimations.length));
        }

        private float getEstimatedHeight() {
            return s(HEADER_HEIGHT) + getVisibleContentHeight();
        }

        private float getUtilityRowsHeight() {
            return category == Category.RENDER ? s(MODULE_HEIGHT * 2.0F) : 0.0F;
        }

        private float getFullContentHeight() {
            return getUtilityRowsHeight() + getModulesFor(category).size() * s(MODULE_HEIGHT) + s(8.0F);
        }

        private float getVisibleContentHeight() {
            return Math.min(getFullContentHeight(), Display.getHeight() * 0.65F);
        }

        private float getMaxScroll() {
            return Math.max(0.0F, getFullContentHeight() - getVisibleContentHeight());
        }

        private boolean isScrollable() {
            return getMaxScroll() > 0.0F && !collapsed && collapseAnimation > 0.02F;
        }

        private void clampTargetScroll() {
            targetScrollOffset = clamp(targetScrollOffset, 0.0F, getMaxScroll());
        }

        private void keepInside(float screenW, float screenH) {
            float height = s(HEADER_HEIGHT) + getVisibleContentHeight() * collapseAnimation;
            x = clamp(x, s(4.0F), Math.max(s(4.0F), screenW - s(PANEL_WIDTH) - s(4.0F)));
            y = clamp(y, s(4.0F), Math.max(s(4.0F), screenH - Math.min(height, screenH - s(8.0F)) - s(4.0F)));
        }

        private void update(float dt, float mx, float my) {
            openAnimation = lerp(openAnimation, 1.0F, dt * 8.0F);
            collapseAnimation = lerp(collapseAnimation, collapsed ? 0.0F : 1.0F, dt * 10.0F);
            clampTargetScroll();
            scrollOffset = lerp(scrollOffset, targetScrollOffset, dt * 14.0F);
            scrollbarAlpha = lerp(scrollbarAlpha, isScrollable() ? 1.0F : 0.0F, dt * 10.0F);

            if (leftClicked && contextMenu == null && activeDropdown == null && !configModalOpen && !mouseConsumed
                    && isInside(mx, my, x, y, s(PANEL_WIDTH), s(HEADER_HEIGHT)) && getTopHoveredPanel(mx, my) == this) {
                float arrowArea = s(24.0F);
                boolean arrowHovered = isInside(mx, my, x + s(PANEL_WIDTH) - arrowArea, y, arrowArea, s(HEADER_HEIGHT));
                if (arrowHovered) {
                    collapsed = !collapsed;
                    if (collapsed) {
                        targetScrollOffset = 0.0F;
                    }
                } else {
                    draggingPanel = this;
                    draggingPanelActive = true;
                    dragOffsetX = mx - x;
                    dragOffsetY = my - y;
                }
                mouseConsumed = true;
            }

            if (rightClicked && contextMenu == null && activeDropdown == null && !configModalOpen && !mouseConsumed
                    && isInside(mx, my, x, y, s(PANEL_WIDTH), s(HEADER_HEIGHT)) && getTopHoveredPanel(mx, my) == this) {
                collapsed = !collapsed;
                if (collapsed) {
                    targetScrollOffset = 0.0F;
                }
                mouseConsumed = true;
            }

            if (draggingPanel == this && leftDown) {
                x = mx - dragOffsetX;
                y = my - dragOffsetY;
                mouseConsumed = true;
            }

            float contentTop = y + s(HEADER_HEIGHT);
            float contentBottom = contentTop + getVisibleContentHeight() * collapseAnimation;
            float rowY = contentTop + s(4.0F) - scrollOffset;
            if (category == Category.RENDER) {
                boolean editHovered = isInside(mx, my, x, rowY, s(PANEL_WIDTH), s(MODULE_HEIGHT))
                        && rowY + s(MODULE_HEIGHT) > contentTop && rowY < contentBottom && getTopHoveredPanel(mx, my) == this
                        && contextMenu == null && activeDropdown == null && !configModalOpen;
                editHoverAnimation = lerp(editHoverAnimation, editHovered ? 1.0F : 0.0F, dt * 15.0F);
                rowY += s(MODULE_HEIGHT);
                boolean configHovered = isInside(mx, my, x, rowY, s(PANEL_WIDTH), s(MODULE_HEIGHT))
                        && rowY + s(MODULE_HEIGHT) > contentTop && rowY < contentBottom && getTopHoveredPanel(mx, my) == this
                        && contextMenu == null && activeDropdown == null && !configModalOpen;
                configHoverAnimation = lerp(configHoverAnimation, configHovered ? 1.0F : 0.0F, dt * 15.0F);
                rowY += s(MODULE_HEIGHT);
            }

            for (int i = 0; i < moduleAnimations.length; i++) {
                float delayed = Math.max(0.0F, openAnimation - i * 0.035F);
                moduleAnimations[i] = lerp(moduleAnimations[i], delayed > 0.25F ? 1.0F : 0.0F, dt * 12.0F);
                boolean hovered = isInside(mx, my, x, rowY, s(PANEL_WIDTH), s(MODULE_HEIGHT))
                        && rowY + s(MODULE_HEIGHT) > contentTop && rowY < contentBottom && getTopHoveredPanel(mx, my) == this
                        && contextMenu == null && activeDropdown == null && !configModalOpen;
                moduleHoverAnimations[i] = lerp(moduleHoverAnimations[i], hovered ? 1.0F : 0.0F, dt * 15.0F);
                rowY += s(MODULE_HEIGHT);
            }
        }

        private void render(List<Module> modules, float mx, float my, float globalAlpha) {
            if (openAnimation < 0.01F) {
                return;
            }
            float alpha = globalAlpha * openAnimation;
            float totalH = s(HEADER_HEIGHT) + getVisibleContentHeight() * collapseAnimation;
            float scale = 0.96F + 0.04F * openAnimation;

            GL11.glPushMatrix();
            GL11.glTranslatef(x + s(PANEL_WIDTH) / 2.0F, y, 0.0F);
            GL11.glScalef(scale, scale, 1.0F);
            GL11.glTranslatef(-(x + s(PANEL_WIDTH) / 2.0F), -y, 0.0F);

            drawSoftShadow(x, y, s(PANEL_WIDTH), totalH, s(PANEL_RADIUS), alpha * 0.7F);
            String style = optionString("Panel Style", "Glass");
            int panelAlpha = "Solid".equalsIgnoreCase(style) ? 232 : "Compact".equalsIgnoreCase(style) ? 205
                    : GLASS_PANEL.getAlpha();
            drawRoundedRect(x, y, s(PANEL_WIDTH), totalH, s(PANEL_RADIUS),
                    new Color(GLASS_PANEL.getRed(), GLASS_PANEL.getGreen(), GLASS_PANEL.getBlue(),
                            (int) (panelAlpha * alpha)));
            drawRoundedOutline(x, y, s(PANEL_WIDTH), totalH, s(PANEL_RADIUS),
                    new Color(GLASS_BORDER.getRed(), GLASS_BORDER.getGreen(), GLASS_BORDER.getBlue(),
                            (int) (GLASS_BORDER.getAlpha() * alpha)).getRGB());

            renderHeader(modules.size(), alpha);
            if (collapseAnimation > 0.02F) {
                drawRect(x + s(9.0F), y + s(HEADER_HEIGHT) - s(1.0F), s(PANEL_WIDTH) - s(18.0F), s(1.0F),
                        color(255, 255, 255, (int) (18.0F * alpha * collapseAnimation)));
                renderContent(modules, mx, my, alpha);
            }

            GL11.glPopMatrix();
        }

        private void renderHeader(int moduleCount, float alpha) {
            drawCategoryIcon(category, x + s(12.0F), y + s(9.0F), s(16.0F), alpha);
            String name = category.getDisplayName();
            float nameScale = s(0.94F);
            drawText(name, x + s(33.0F), y + (s(HEADER_HEIGHT) - fontHeight() * nameScale) / 2.0F,
                    applyAlpha(TEXT_PRIMARY, alpha), false, nameScale);
            float dividerX = x + s(33.0F) + textWidth(name) * nameScale + s(11.0F);
            drawRect(dividerX, y + s(HEADER_HEIGHT) / 2.0F - s(6.0F), s(1.0F), s(12.0F),
                    color(255, 255, 255, (int) (30.0F * alpha)));
            String count = String.valueOf(moduleCount + (category == Category.RENDER ? 2 : 0));
            float countScale = s(0.76F);
            drawText(count, x + s(PANEL_WIDTH) - s(35.0F), y + (s(HEADER_HEIGHT) - fontHeight() * countScale) / 2.0F,
                    applyAlpha(TEXT_MUTED, alpha), false, countScale);
            drawArrow(x + s(PANEL_WIDTH) - s(14.0F), y + s(HEADER_HEIGHT) / 2.0F, s(4.5F), collapsed, alpha);
        }

        private void renderContent(List<Module> modules, float mx, float my, float alpha) {
            float contentTop = y + s(HEADER_HEIGHT);
            float contentH = getVisibleContentHeight() * collapseAnimation;
            boolean scissor = collapseAnimation < 0.99F || isScrollable();
            if (scissor) {
                enableScissor(x, contentTop, s(PANEL_WIDTH), contentH);
            }

            float rowY = contentTop + s(4.0F) - scrollOffset;
            if (category == Category.RENDER) {
                renderUtilityRow("Edit UI Elements", rowY, editHoverAnimation, alpha, true, mx, my);
                rowY += s(MODULE_HEIGHT);
                renderUtilityRow("Config", rowY, configHoverAnimation, alpha, false, mx, my);
                rowY += s(MODULE_HEIGHT);
            }

            for (int i = 0; i < modules.size(); i++) {
                Module module = modules.get(i);
                if (rowY + s(MODULE_HEIGHT) >= contentTop && rowY <= contentTop + contentH) {
                    renderModuleRow(module, i, rowY, mx, my, alpha * collapseAnimation * moduleAnimations[i]);
                }
                rowY += s(MODULE_HEIGHT);
            }

            if (scissor) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }

            if (scrollbarAlpha > 0.02F && isScrollable()) {
                float trackTop = contentTop + s(5.0F);
                float trackH = contentH - s(10.0F);
                float ratio = getVisibleContentHeight() / getFullContentHeight();
                float thumbH = Math.max(s(16.0F), trackH * ratio);
                float progress = getMaxScroll() > 0.0F ? scrollOffset / getMaxScroll() : 0.0F;
                float thumbY = trackTop + (trackH - thumbH) * progress;
                drawRoundedRect(x + s(PANEL_WIDTH) - s(4.5F), thumbY, s(1.8F), thumbH, s(1.0F),
                        color(255, 255, 255, (int) (130.0F * alpha * scrollbarAlpha)));
            }
        }

        private void renderUtilityRow(String label, float rowY, float hover, float alpha, boolean edit, float mx,
                float my) {
            if (alpha < 0.03F) {
                return;
            }
            if (hover > 0.01F) {
                drawRoundedRect(x + s(5.0F), rowY, s(PANEL_WIDTH) - s(10.0F), s(MODULE_HEIGHT), s(5.0F),
                        color(255, 255, 255, (int) (22.0F * alpha * hover)));
            }
            if (edit) {
                drawEditIcon(x + s(13.0F), rowY + s(9.0F), s(10.0F), alpha);
            } else {
                drawFolderIcon(x + s(13.0F), rowY + s(8.0F), s(11.0F), alpha);
                String current = ConfigManager.getInstance().getCurrentConfig();
                float badgeScale = s(0.66F);
                float badgeW = textWidth(current) * badgeScale + s(9.0F);
                drawRoundedRect(x + s(PANEL_WIDTH) - badgeW - s(12.0F), rowY + s(8.0F), badgeW, s(12.5F), s(4.0F),
                        color(39, 39, 42, (int) (155.0F * alpha)));
                drawText(current, x + s(PANEL_WIDTH) - badgeW - s(7.5F), rowY + s(10.0F),
                        applyAlpha(TEXT_MUTED, alpha), false, badgeScale);
            }
            drawText(label, x + s(28.0F), rowY + (s(MODULE_HEIGHT) - fontHeight() * s(0.82F)) / 2.0F,
                    applyAlpha(TEXT_SECONDARY, alpha), false, s(0.82F));
            boolean hovered = isInside(mx, my, x, rowY, s(PANEL_WIDTH), s(MODULE_HEIGHT)) && getTopHoveredPanel(mx, my) == this;
            if (hovered && leftClicked && contextMenu == null && activeDropdown == null && !configModalOpen && !mouseConsumed) {
                if (edit) {
                    uiEditMode = true;
                } else {
                    configModalOpen = true;
                    configModalAnimation = 0.0F;
                    selectedConfig = ConfigManager.getInstance().getCurrentConfig();
                    configList = ConfigManager.getInstance().getConfigList();
                }
                mouseConsumed = true;
                leftClicked = false;
            }
        }

        private void renderModuleRow(Module module, int index, float rowY, float mx, float my, float alpha) {
            if (alpha < 0.03F) {
                return;
            }
            float offsetX = (1.0F - moduleAnimations[index]) * -s(8.0F);
            float rowX = x + offsetX;
            float hover = moduleHoverAnimations[index];
            boolean enabled = module.isEnabled();
            if (hover > 0.01F || enabled) {
                int bg = enabled ? color(accentColor(alpha).getRed(), accentColor(alpha).getGreen(), accentColor(alpha).getBlue(),
                        (int) (34.0F * alpha + 20.0F * alpha * hover)) : color(255, 255, 255, (int) (20.0F * alpha * hover));
                drawRoundedRect(rowX + s(5.0F), rowY, s(PANEL_WIDTH) - s(10.0F), s(MODULE_HEIGHT), s(5.0F), bg);
            }

            float indicator = s(8.0F);
            float indicatorX = rowX + s(13.0F);
            float indicatorY = rowY + (s(MODULE_HEIGHT) - indicator) / 2.0F;
            if (enabled) {
                drawRoundedRect(indicatorX, indicatorY, indicator, indicator, indicator / 2.0F, accentColor(alpha));
            } else {
                drawRoundedOutline(indicatorX, indicatorY, indicator, indicator, indicator / 2.0F,
                        color(113, 113, 122, (int) (190.0F * alpha)));
            }

            float nameScale = s(0.82F);
            String name = trimToWidth(module.getName(), (int) s(104.0F), nameScale);
            drawText(name, rowX + s(28.0F), rowY + (s(MODULE_HEIGHT) - fontHeight() * nameScale) / 2.0F,
                    applyAlpha(enabled ? TEXT_PRIMARY : TEXT_SECONDARY, alpha), false, nameScale);

            String key = waitingForKeybind && keybindModule == module ? "..." : keyName(module.getKeyBind());
            boolean showKey = module.getKeyBind() > 0 || hover > 0.3F || waitingForKeybind && keybindModule == module;
            float badgeX = 0.0F;
            float badgeY = 0.0F;
            float badgeW = 0.0F;
            float badgeH = s(13.0F);
            if (showKey) {
                float keyScale = s(0.65F);
                badgeW = textWidth(key) * keyScale + s(12.0F);
                badgeX = rowX + s(PANEL_WIDTH) - badgeW - s(12.0F);
                badgeY = rowY + (s(MODULE_HEIGHT) - badgeH) / 2.0F;
                drawRoundedRect(badgeX, badgeY, badgeW, badgeH, s(4.0F),
                        color(39, 39, 42, (int) ((module.getKeyBind() > 0 ? 160.0F : 100.0F * hover) * alpha)));
                drawText(key, badgeX + (badgeW - textWidth(key) * keyScale) / 2.0F,
                        badgeY + (badgeH - fontHeight() * keyScale) / 2.0F,
                        applyAlpha(module.getKeyBind() > 0 || waitingForKeybind && keybindModule == module ? TEXT_SECONDARY : TEXT_MUTED,
                                alpha),
                        false, keyScale);
            }

            boolean hovered = isInside(mx, my, x, rowY, s(PANEL_WIDTH), s(MODULE_HEIGHT)) && getTopHoveredPanel(mx, my) == this;
            if (hovered && contextMenu == null && activeDropdown == null && !configModalOpen && !mouseConsumed) {
                boolean keyHovered = showKey && isInside(mx, my, badgeX, badgeY, badgeW, badgeH);
                if (leftClicked && keyHovered) {
                    waitingForKeybind = true;
                    keybindModule = module;
                    mouseConsumed = true;
                    leftClicked = false;
                } else if (leftClicked) {
                    module.toggle();
                    mouseConsumed = true;
                    leftClicked = false;
                } else if (rightClicked) {
                    contextMenu = new ContextMenu(module, mx, my);
                    mouseConsumed = true;
                    rightClicked = false;
                }
            }
        }

        private boolean isHovered(float mx, float my) {
            float h = s(HEADER_HEIGHT) + getVisibleContentHeight() * collapseAnimation;
            return isInside(mx, my, x, y, s(PANEL_WIDTH), h);
        }
    }

    private static final class ContextMenu {
        private final Module module;
        private final List<OptionBase<?>> visibleOptions = new ArrayList<OptionBase<?>>();
        private float x;
        private float y;
        private float width;
        private float height;
        private float openAnimation;
        private boolean closing;
        private float[] settingAnimations = new float[0];
        private float scrollOffset;
        private float targetScrollOffset;
        private float scrollbarAlpha;

        private ContextMenu(Module module, float mx, float my) {
            this.module = module;
            this.width = s(188.0F);
            this.x = mx + s(10.0F);
            this.y = my;
            updateVisibleOptions();
            calculateHeight();
            if (x + width > Display.getWidth() - s(10.0F)) {
                x = mx - width - s(10.0F);
            }
            if (y + getVisibleHeight() > Display.getHeight() - s(10.0F)) {
                y = Display.getHeight() - getVisibleHeight() - s(10.0F);
            }
        }

        private void updateVisibleOptions() {
            visibleOptions.clear();
            for (OptionBase<?> option : module.getOptions()) {
                if (isOptionVisible(option)) {
                    visibleOptions.add(option);
                }
            }
            if (settingAnimations.length != visibleOptions.size()) {
                float[] old = settingAnimations;
                settingAnimations = new float[visibleOptions.size()];
                System.arraycopy(old, 0, settingAnimations, 0, Math.min(old.length, settingAnimations.length));
            }
        }

        private boolean isOptionVisible(OptionBase<?> option) {
            String dependency = option.getDependency();
            if (dependency == null || dependency.length() == 0) {
                return true;
            }
            String depName = dependency;
            String depValue = "true";
            int idx = dependency.indexOf(':');
            if (idx >= 0) {
                depName = dependency.substring(0, idx);
                depValue = dependency.substring(idx + 1);
            }
            OptionBase<?> dep = module.getOption(depName);
            if (dep instanceof BooleanOption) {
                return String.valueOf(((BooleanOption) dep).getValue()).equalsIgnoreCase(depValue);
            }
            if (dep instanceof StringOption) {
                return ((StringOption) dep).getValue().equalsIgnoreCase(depValue);
            }
            return true;
        }

        private void calculateHeight() {
            height = s(39.0F);
            String currentGroup = "";
            updateVisibleOptions();
            if (visibleOptions.isEmpty()) {
                height += s(35.0F);
            }
            for (OptionBase<?> option : visibleOptions) {
                String group = option.getGroup();
                if (group != null && group.length() > 0 && !group.equals(currentGroup)) {
                    height += (currentGroup.length() == 0 ? s(30.0F) : s(40.0F));
                    currentGroup = group;
                    if (!GROUP_EXPANDED.containsKey(group)) {
                        GROUP_EXPANDED.put(group, Boolean.TRUE);
                    }
                    if (!GROUP_ANIMATIONS.containsKey(group)) {
                        GROUP_ANIMATIONS.put(group, 1.0F);
                    }
                }
                float groupAnim = group == null || group.length() == 0 ? 1.0F : GROUP_ANIMATIONS.get(group);
                height += getOptionHeight(option) * groupAnim;
            }
            height += s(11.0F);
        }

        private float getOptionHeight(OptionBase<?> option) {
            if (option instanceof BooleanOption) {
                return s(32.0F);
            }
            if (option instanceof NumberOption) {
                return s(44.0F);
            }
            if (option instanceof StringOption) {
                return s(43.0F);
            }
            if (option instanceof TextOption) {
                return s(36.0F);
            }
            if (option instanceof ColorOption) {
                return s(25.0F) + s(104.0F) * COLOR_PICKER_ANIMATIONS.getOrDefault((ColorOption) option, 0.0F);
            }
            return s(26.0F);
        }

        private float getMaxHeight() {
            return Display.getHeight() * 0.62F;
        }

        private float getVisibleHeight() {
            return Math.min(height, getMaxHeight());
        }

        private float getMaxScroll() {
            return Math.max(0.0F, height - getMaxHeight());
        }

        private boolean isScrollable() {
            return getMaxScroll() > 0.0F;
        }

        private void clampTargetScroll() {
            targetScrollOffset = clamp(targetScrollOffset, 0.0F, getMaxScroll());
        }

        private boolean isHovered(float mx, float my) {
            return isInside(mx, my, x, y, width, getVisibleHeight());
        }

        private void update(float dt) {
            openAnimation = lerp(openAnimation, closing ? 0.0F : 1.0F, dt * 12.0F);
            boolean resize = false;
            for (Map.Entry<String, Boolean> entry : GROUP_EXPANDED.entrySet()) {
                float current = GROUP_ANIMATIONS.getOrDefault(entry.getKey(), 0.0F);
                float next = lerp(current, entry.getValue() ? 1.0F : 0.0F, dt * 12.0F);
                if (Math.abs(current - next) > 0.001F) {
                    GROUP_ANIMATIONS.put(entry.getKey(), next);
                    resize = true;
                }
            }
            for (OptionBase<?> option : visibleOptions) {
                if (option instanceof ColorOption) {
                    ColorOption color = (ColorOption) option;
                    float current = COLOR_PICKER_ANIMATIONS.getOrDefault(color, 0.0F);
                    float next = lerp(current, activeColorPicker == color ? 1.0F : 0.0F, dt * 15.0F);
                    if (Math.abs(current - next) > 0.001F) {
                        COLOR_PICKER_ANIMATIONS.put(color, next);
                        resize = true;
                    }
                }
            }
            updateVisibleOptions();
            if (resize || openAnimation < 0.99F) {
                calculateHeight();
            }
            clampTargetScroll();
            scrollOffset = lerp(scrollOffset, targetScrollOffset, dt * 14.0F);
            scrollbarAlpha = lerp(scrollbarAlpha, isScrollable() ? 1.0F : 0.0F, dt * 10.0F);
            for (int i = 0; i < settingAnimations.length; i++) {
                settingAnimations[i] = lerp(settingAnimations[i], openAnimation - i * 0.05F > 0.18F ? 1.0F : 0.0F,
                        dt * 15.0F);
            }
        }

        private void render(float mx, float my, float globalAlpha) {
            if (openAnimation < 0.01F) {
                return;
            }
            float alpha = openAnimation * globalAlpha;
            float visibleH = getVisibleHeight();
            float offsetY = (1.0F - openAnimation) * -s(5.0F);
            float scale = 0.95F + 0.05F * openAnimation;

            GL11.glPushMatrix();
            GL11.glTranslatef(x, y + offsetY, 0.0F);
            GL11.glScalef(scale, scale, 1.0F);
            GL11.glTranslatef(-x, -(y + offsetY), 0.0F);

            drawSoftShadow(x, y + offsetY, width, visibleH, s(PANEL_RADIUS), alpha * 0.8F);
            String style = optionString("Panel Style", "Glass");
            int menuAlpha = "Solid".equalsIgnoreCase(style) ? 244 : "Compact".equalsIgnoreCase(style) ? 224
                    : GLASS_PANEL_STRONG.getAlpha();
            drawRoundedRect(x, y + offsetY, width, visibleH, s(PANEL_RADIUS),
                    new Color(GLASS_PANEL_STRONG.getRed(), GLASS_PANEL_STRONG.getGreen(), GLASS_PANEL_STRONG.getBlue(),
                            (int) (menuAlpha * alpha)));
            drawRoundedOutline(x, y + offsetY, width, visibleH, s(PANEL_RADIUS),
                    new Color(GLASS_BORDER.getRed(), GLASS_BORDER.getGreen(), GLASS_BORDER.getBlue(),
                            (int) (GLASS_BORDER.getAlpha() * alpha)).getRGB());

            float headerY = y + offsetY + s(11.0F);
            drawCircle(x + s(15.0F), headerY + s(3.0F), s(3.0F), applyAlpha(TEXT_SECONDARY, alpha));
            drawText(module.getName(), x + s(25.0F), headerY - s(2.0F), applyAlpha(TEXT_PRIMARY, alpha), false,
                    s(0.9F));
            String settings = "settings";
            drawText(settings, x + width - textWidth(settings) * s(0.64F) - s(12.0F), headerY,
                    applyAlpha(TEXT_MUTED, alpha), false, s(0.64F));
            drawRect(x + s(10.0F), y + offsetY + s(29.0F), width - s(20.0F), s(1.0F),
                    color(255, 255, 255, (int) (22.0F * alpha)));

            float headerArea = s(38.0F);
            boolean scissor = isScrollable();
            if (scissor) {
                enableScissor(x, y + offsetY + headerArea, width, visibleH - headerArea);
            }

            float settingY = y + offsetY + headerArea - scrollOffset;
            if (visibleOptions.isEmpty()) {
                drawText("No settings", x + s(12.0F), settingY + s(8.0F), applyAlpha(TEXT_MUTED, alpha), false, s(0.72F));
            }
            String currentGroup = "";
            for (int i = 0; i < visibleOptions.size(); i++) {
                OptionBase<?> option = visibleOptions.get(i);
                String group = option.getGroup();
                boolean grouped = group != null && group.length() > 0;
                if (grouped && !group.equals(currentGroup)) {
                    if (currentGroup.length() > 0) {
                        settingY += s(10.0F);
                    }
                    renderGroupHeader(group, x + s(8.0F), settingY, width - s(16.0F), mx, my, alpha);
                    settingY += s(30.0F);
                    currentGroup = group;
                }
                float groupAnim = grouped ? GROUP_ANIMATIONS.getOrDefault(group, 1.0F) : 1.0F;
                if (grouped && groupAnim < 0.01F) {
                    continue;
                }

                float settingAlpha = alpha * settingAnimations[i] * (grouped ? (float) Math.pow(groupAnim, 3.0D) : 1.0F);
                float optionX = x + s(10.0F) + (grouped ? s(5.0F) : 0.0F);
                float optionW = width - s(20.0F) - (grouped ? s(5.0F) : 0.0F);
                if (settingAlpha > 0.03F) {
                    if (option instanceof BooleanOption) {
                        renderBooleanSetting((BooleanOption) option, optionX, settingY, optionW, mx, my, settingAlpha);
                    } else if (option instanceof NumberOption) {
                        renderSliderSetting((NumberOption) option, optionX, settingY, optionW, mx, my, settingAlpha);
                    } else if (option instanceof StringOption) {
                        renderModeSetting((StringOption) option, optionX, settingY, optionW, mx, my, settingAlpha);
                    } else if (option instanceof TextOption) {
                        renderTextSetting((TextOption) option, optionX, settingY, optionW, mx, my, settingAlpha);
                    } else if (option instanceof ColorOption) {
                        renderColorSetting((ColorOption) option, optionX, settingY, optionW, mx, my, settingAlpha);
                    }
                }
                settingY += getOptionHeight(option) * groupAnim;
            }

            if (scissor) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }

            if (scrollbarAlpha > 0.02F && isScrollable()) {
                float trackTop = y + offsetY + headerArea + s(3.0F);
                float trackH = visibleH - headerArea - s(8.0F);
                float thumbH = Math.max(s(13.0F), trackH * (visibleH / height));
                float progress = getMaxScroll() > 0.0F ? scrollOffset / getMaxScroll() : 0.0F;
                drawRoundedRect(x + width - s(4.5F), trackTop + (trackH - thumbH) * progress, s(1.8F), thumbH, s(1.0F),
                        color(255, 255, 255, (int) (120.0F * alpha * scrollbarAlpha)));
            }
            GL11.glPopMatrix();
        }

        private void renderGroupHeader(String name, float sx, float sy, float sw, float mx, float my, float alpha) {
            boolean expanded = GROUP_EXPANDED.getOrDefault(name, Boolean.TRUE);
            boolean hovered = isInside(mx, my, sx, sy, sw, s(25.0F));
            drawRoundedRect(sx, sy, sw, s(25.0F), s(6.0F),
                    color(255, 255, 255, (int) ((hovered ? 28.0F : 13.0F) * alpha)));
            String label = name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
            drawText(label, sx + s(12.0F), sy + (s(25.0F) - fontHeight() * s(0.74F)) / 2.0F,
                    applyAlpha(TEXT_SECONDARY, alpha), false, s(0.74F));
            drawArrow(sx + sw - s(13.0F), sy + s(12.5F), s(4.0F), !expanded, alpha);
            if (hovered && leftClicked && activeDropdown == null && !mouseConsumed) {
                GROUP_EXPANDED.put(name, !expanded);
                mouseConsumed = true;
                leftClicked = false;
            }
        }

        private void renderBooleanSetting(BooleanOption option, float sx, float sy, float sw, float mx, float my,
                float alpha) {
            drawText(option.getName(), sx, sy + s(8.0F), applyAlpha(TEXT_SECONDARY, alpha), false, s(0.78F));
            float switchW = s(29.0F);
            float switchH = s(14.0F);
            float switchX = sx + sw - switchW;
            float switchY = sy + s(8.0F);
            boolean hovered = isInside(mx, my, switchX - s(5.0F), switchY - s(3.0F), switchW + s(10.0F), switchH + s(6.0F));
            boolean on = option.getValue();
            if (on) {
                drawRoundedRect(switchX - s(3.0F), switchY - s(3.0F), switchW + s(6.0F), switchH + s(6.0F),
                        (switchH + s(6.0F)) / 2.0F, color(255, 255, 255, (int) (36.0F * alpha)));
            }
            drawRoundedRect(switchX, switchY, switchW, switchH, switchH / 2.0F,
                    on ? accentColor(alpha * 0.85F) : new Color(63, 63, 70, (int) (160.0F * alpha)));
            float thumb = s(10.0F);
            float thumbX = on ? switchX + switchW - thumb - s(2.0F) : switchX + s(2.0F);
            drawRoundedRect(thumbX, switchY + (switchH - thumb) / 2.0F, thumb, thumb, thumb / 2.0F,
                    color(255, 255, 255, (int) (255.0F * alpha)));
            if (hovered && leftClicked && activeDropdown == null && draggingSlider == null) {
                option.toggle();
                mouseConsumed = true;
                leftClicked = false;
            }
        }

        private void renderSliderSetting(NumberOption option, float sx, float sy, float sw, float mx, float my,
                float alpha) {
            drawText(option.getName(), sx, sy, applyAlpha(TEXT_SECONDARY, alpha), false, s(0.78F));
            String value = option.getIncrement() >= 1.0D ? String.valueOf((int) Math.round(option.getValue()))
                    : String.format("%.2f", option.getValue());
            float valueScale = s(0.74F);
            drawText(value, sx + sw - textWidth(value) * valueScale, sy, applyAlpha(TEXT_PRIMARY, alpha), false,
                    valueScale);

            float sliderY = sy + s(23.0F);
            float sliderH = s(4.0F);
            boolean hovered = isInside(mx, my, sx - s(2.0F), sliderY - s(6.0F), sw + s(4.0F), sliderH + s(12.0F));
            drawRoundedRect(sx, sliderY, sw, sliderH, sliderH / 2.0F, color(30, 30, 35, (int) (230.0F * alpha)));
            double percent = (option.getValue() - option.getMin()) / (option.getMax() - option.getMin());
            float fill = (float) (sw * percent);
            drawRoundedRect(sx, sliderY, fill, sliderH, sliderH / 2.0F, accentColor(alpha));
            float thumb = s(11.0F);
            float thumbX = sx + fill - thumb / 2.0F;
            float thumbY = sliderY + (sliderH - thumb) / 2.0F;
            if (hovered || draggingSlider == option) {
                drawRoundedRect(thumbX - s(1.5F), thumbY - s(1.5F), thumb + s(3.0F), thumb + s(3.0F),
                        (thumb + s(3.0F)) / 2.0F, color(255, 255, 255, (int) (42.0F * alpha)));
            }
            drawRoundedRect(thumbX, thumbY, thumb, thumb, thumb / 2.0F, color(255, 255, 255, (int) (255.0F * alpha)));

            if (hovered && leftClicked && activeDropdown == null && draggingSlider == null) {
                draggingSlider = option;
                leftClicked = false;
                mouseConsumed = true;
            }
            if (draggingSlider == option && leftDown) {
                double nextPercent = clamp((mx - sx) / sw, 0.0F, 1.0F);
                option.setValue(option.getMin() + (option.getMax() - option.getMin()) * nextPercent);
            }
            if (draggingSlider == option && !leftDown) {
                draggingSlider = null;
            }
        }

        private void renderModeSetting(StringOption option, float sx, float sy, float sw, float mx, float my, float alpha) {
            drawText(option.getName(), sx, sy, applyAlpha(TEXT_SECONDARY, alpha), false, s(0.78F));
            float buttonY = sy + s(20.0F);
            float buttonH = s(20.0F);
            boolean active = activeDropdown == option;
            boolean hovered = isInside(mx, my, sx, buttonY, sw, buttonH);
            drawRoundedRect(sx, buttonY, sw, buttonH, s(5.0F),
                    color(255, 255, 255, (int) ((active ? 45.0F : hovered ? 26.0F : 15.0F) * alpha)));
            if (active) {
                drawRoundedOutline(sx, buttonY, sw, buttonH, s(5.0F), applyAlpha(TEXT_PRIMARY, alpha * 0.4F));
            }
            drawText(option.getValue(), sx + s(8.0F), buttonY + (buttonH - fontHeight() * s(0.73F)) / 2.0F,
                    applyAlpha(TEXT_PRIMARY, alpha), false, s(0.73F));
            drawArrow(sx + sw - s(14.0F), buttonY + buttonH / 2.0F, s(4.0F), !active, alpha);
            if (hovered && leftClicked && draggingSlider == null && (activeDropdown == null || activeDropdown == option)) {
                if (activeDropdown == option) {
                    activeDropdown = null;
                } else {
                    activeDropdown = option;
                    dropdownX = sx;
                    dropdownY = buttonY + buttonH + s(3.0F);
                    dropdownW = sw;
                    dropdownAnimation = 0.0F;
                }
                mouseConsumed = true;
                leftClicked = false;
            }
        }

        private void renderTextSetting(TextOption option, float sx, float sy, float sw, float mx, float my, float alpha) {
            drawText(option.getName(), sx, sy, applyAlpha(TEXT_SECONDARY, alpha), false, s(0.78F));
            float inputY = sy + s(15.0F);
            float inputH = s(19.0F);
            boolean active = activeTextOption == option;
            boolean hovered = isInside(mx, my, sx, inputY, sw, inputH);
            drawRoundedRect(sx, inputY, sw, inputH, s(5.0F),
                    color(255, 255, 255, (int) ((active ? 38.0F : hovered ? 25.0F : 14.0F) * alpha)));
            if (active) {
                drawRoundedOutline(sx, inputY, sw, inputH, s(5.0F), applyAlpha(TEXT_PRIMARY, alpha * 0.35F));
                activeTextField.updateCoordinates(sx, inputY, sw, inputH);
                activeTextField.draw(alpha);
            } else {
                String text = trimToWidth(option.getText(), (int) (sw - s(12.0F)), s(0.72F));
                drawText(text, sx + s(7.0F), inputY + (inputH - fontHeight() * s(0.72F)) / 2.0F,
                        applyAlpha(text.length() == 0 ? TEXT_MUTED : TEXT_PRIMARY, alpha), false, s(0.72F));
            }
            if (hovered && leftClicked && activeDropdown == null && draggingSlider == null) {
                activeTextOption = option;
                activeTextField.setText(option.getText());
                activeTextField.setFocused(true);
                activeTextField.updateCoordinates(sx, inputY, sw, inputH);
                activeTextField.handleMouse(mx, my);
                isTypingText = true;
                mouseConsumed = true;
                leftClicked = false;
            } else if (leftClicked && active && !hovered) {
                option.setText(activeTextField.getText());
                activeTextField.setFocused(false);
                activeTextOption = null;
                isTypingText = false;
            }
        }

        private void renderColorSetting(ColorOption option, float sx, float sy, float sw, float mx, float my, float alpha) {
            boolean rowHovered = isInside(mx, my, sx, sy, sw, s(23.0F));
            if (rowHovered && activeDropdown == null && draggingSlider == null) {
                if (leftClicked) {
                    activeColorPicker = activeColorPicker == option ? null : option;
                    mouseConsumed = true;
                    leftClicked = false;
                } else if (rightClicked) {
                    option.setValue(option.getDefaultValue());
                    mouseConsumed = true;
                    rightClicked = false;
                }
            }
            drawText(option.getName(), sx, sy + s(4.0F), applyAlpha(TEXT_PRIMARY, alpha), false, s(0.78F));
            Color value = option.getValue();
            float preview = s(10.0F);
            float px = sx + sw - preview - s(2.0F);
            float py = sy + s(5.0F);
            drawRoundedRect(px - s(1.0F), py - s(1.0F), preview + s(2.0F), preview + s(2.0F), (preview + s(2.0F)) / 2.0F,
                    color(0, 0, 0, (int) (45.0F * alpha)));
            drawRoundedRect(px, py, preview, preview, preview / 2.0F,
                    color(value.getRed(), value.getGreen(), value.getBlue(), (int) (255.0F * alpha)));
            drawRoundedOutline(px, py, preview, preview, preview / 2.0F, color(255, 255, 255, (int) (95.0F * alpha)));

            float anim = COLOR_PICKER_ANIMATIONS.getOrDefault(option, 0.0F);
            if (anim <= 0.01F) {
                if (!leftDown && draggingColor == option) {
                    draggingColor = null;
                }
                return;
            }
            float pickerAlpha = alpha * anim;
            float boxY = sy + s(22.0F);
            float boxH = s(55.0F) * anim;
            float barH = s(8.0F) * anim;
            float[] hsb = option.getHSB();
            float hue = hsb[0];
            float sat = hsb[1];
            float bri = hsb[2];
            float alphaValue = value.getAlpha() / 255.0F;

            Color hueColor = Color.getHSBColor(hue, 1.0F, 1.0F);
            for (int i = 0; i < 12; i++) {
                float x1 = sx + sw * i / 12.0F;
                float x2 = sx + sw * (i + 1) / 12.0F;
                float mix1 = i / 12.0F;
                float mix2 = (i + 1) / 12.0F;
                int top1 = color((int) (255 + (hueColor.getRed() - 255) * mix1),
                        (int) (255 + (hueColor.getGreen() - 255) * mix1),
                        (int) (255 + (hueColor.getBlue() - 255) * mix1), (int) (255.0F * pickerAlpha));
                int top2 = color((int) (255 + (hueColor.getRed() - 255) * mix2),
                        (int) (255 + (hueColor.getGreen() - 255) * mix2),
                        (int) (255 + (hueColor.getBlue() - 255) * mix2), (int) (255.0F * pickerAlpha));
                drawGradientRect(x1, boxY, x2 - x1 + 1.0F, boxH, top1, top2, true);
                drawGradientRect(x1, boxY, x2 - x1 + 1.0F, boxH, color(0, 0, 0, 0),
                        color(0, 0, 0, (int) (255.0F * pickerAlpha)), false);
            }
            drawRoundedOutline(sx, boxY, sw, boxH, s(4.0F), color(255, 255, 255, (int) (45.0F * pickerAlpha)));

            float hueY = boxY + boxH + s(8.0F) * anim;
            for (int i = 0; i < 24; i++) {
                Color a = Color.getHSBColor(i / 24.0F, 1.0F, 1.0F);
                Color b = Color.getHSBColor((i + 1) / 24.0F, 1.0F, 1.0F);
                drawGradientRect(sx + sw * i / 24.0F, hueY, sw / 24.0F + 1.0F, barH,
                        color(a.getRed(), a.getGreen(), a.getBlue(), (int) (255.0F * pickerAlpha)),
                        color(b.getRed(), b.getGreen(), b.getBlue(), (int) (255.0F * pickerAlpha)), true);
            }
            drawRoundedOutline(sx, hueY, sw, barH, barH / 2.0F, color(255, 255, 255, (int) (40.0F * pickerAlpha)));

            float alphaY = hueY + barH + s(8.0F) * anim;
            Color full = Color.getHSBColor(hue, sat, bri);
            drawGradientRect(sx, alphaY, sw, barH, color(full.getRed(), full.getGreen(), full.getBlue(), 0),
                    color(full.getRed(), full.getGreen(), full.getBlue(), (int) (255.0F * pickerAlpha)), true);
            drawRoundedOutline(sx, alphaY, sw, barH, barH / 2.0F, color(255, 255, 255, (int) (40.0F * pickerAlpha)));

            renderKnob(sx + sat * sw, boxY + (1.0F - bri) * boxH, s(6.0F) * anim, pickerAlpha);
            renderKnob(sx + hue * sw, hueY + barH / 2.0F, s(7.0F) * anim, pickerAlpha);
            renderKnob(sx + alphaValue * sw, alphaY + barH / 2.0F, s(7.0F) * anim, pickerAlpha);

            if (leftClicked && activeDropdown == null && draggingSlider == null) {
                if (isInside(mx, my, sx, boxY, sw, boxH)) {
                    draggingColor = option;
                    draggingColorType = 1;
                    leftClicked = false;
                } else if (isInside(mx, my, sx, hueY - s(2.0F), sw, barH + s(4.0F))) {
                    draggingColor = option;
                    draggingColorType = 2;
                    leftClicked = false;
                } else if (isInside(mx, my, sx, alphaY - s(2.0F), sw, barH + s(4.0F))) {
                    draggingColor = option;
                    draggingColorType = 3;
                    leftClicked = false;
                }
            }
            if (draggingColor == option && leftDown) {
                float mxr = clamp((mx - sx) / sw, 0.0F, 1.0F);
                if (draggingColorType == 1) {
                    option.setHSB(hue, mxr, 1.0F - clamp((my - boxY) / Math.max(1.0F, boxH), 0.0F, 1.0F), alphaValue);
                } else if (draggingColorType == 2) {
                    option.setHSB(mxr, sat, bri, alphaValue);
                } else if (draggingColorType == 3) {
                    option.setHSB(hue, sat, bri, mxr);
                }
            }
            if (!leftDown && draggingColor == option) {
                draggingColor = null;
                draggingColorType = 0;
            }
        }

        private void renderKnob(float x, float y, float size, float alpha) {
            drawRoundedOutline(x - size / 2.0F, y - size / 2.0F, size, size, size / 2.0F,
                    color(10, 10, 12, (int) (220.0F * alpha)));
            drawRoundedRect(x - size / 2.0F, y - size / 2.0F, size, size, size / 2.0F,
                    color(255, 255, 255, (int) (255.0F * alpha)));
        }
    }

    private static final class TextField {
        private final String placeholder;
        private final int maxLength;
        private String text = "";
        private boolean focused;
        private float x;
        private float y;
        private float w;
        private float h;
        private int cursor;
        private long lastBlink;
        private boolean blinkOn = true;

        private TextField(String placeholder, int maxLength) {
            this.placeholder = placeholder;
            this.maxLength = maxLength;
        }

        private String getText() {
            return text;
        }

        private void setText(String text) {
            this.text = text == null ? "" : text;
            if (this.text.length() > maxLength) {
                this.text = this.text.substring(0, maxLength);
            }
            cursor = this.text.length();
        }

        private void setFocused(boolean focused) {
            this.focused = focused;
            this.lastBlink = System.currentTimeMillis();
            this.blinkOn = true;
        }

        private void updateCoordinates(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        private boolean isHovered(float mx, float my) {
            return isInside(mx, my, x, y, w, h);
        }

        private void handleMouse(float mx, float my) {
            if (!isHovered(mx, my)) {
                return;
            }
            float scale = s(0.72F);
            float local = mx - x - s(7.0F);
            cursor = 0;
            for (int i = 1; i <= text.length(); i++) {
                if (textWidth(text.substring(0, i)) * scale <= local) {
                    cursor = i;
                }
            }
        }

        private void handleKeyboard(int key, char keyChar) {
            if (key == Keyboard.KEY_BACK) {
                if (cursor > 0 && text.length() > 0) {
                    text = text.substring(0, cursor - 1) + text.substring(cursor);
                    cursor--;
                }
                return;
            }
            if (key == Keyboard.KEY_DELETE) {
                if (cursor < text.length()) {
                    text = text.substring(0, cursor) + text.substring(cursor + 1);
                }
                return;
            }
            if (key == Keyboard.KEY_LEFT) {
                cursor = Math.max(0, cursor - 1);
                return;
            }
            if (key == Keyboard.KEY_RIGHT) {
                cursor = Math.min(text.length(), cursor + 1);
                return;
            }
            if (key == Keyboard.KEY_HOME) {
                cursor = 0;
                return;
            }
            if (key == Keyboard.KEY_END) {
                cursor = text.length();
                return;
            }
            if (keyChar >= 32 && keyChar != 127 && text.length() < maxLength) {
                text = text.substring(0, cursor) + keyChar + text.substring(cursor);
                cursor++;
            }
        }

        private void draw(float alpha) {
            long now = System.currentTimeMillis();
            if (now - lastBlink > 500L) {
                blinkOn = !blinkOn;
                lastBlink = now;
            }
            float scale = s(0.72F);
            String visible = text.length() == 0 && !focused ? placeholder : text;
            int color = text.length() == 0 && !focused ? TEXT_MUTED : TEXT_PRIMARY;
            String clipped = trimToWidth(visible, (int) (w - s(14.0F)), scale);
            float textY = y + (h - fontHeight() * scale) / 2.0F;
            drawText(clipped, x + s(7.0F), textY, applyAlpha(color, alpha), false, scale);
            if (focused && blinkOn) {
                float cursorX = x + s(7.0F) + textWidth(text.substring(0, Math.min(cursor, text.length()))) * scale;
                drawRect(cursorX, y + s(5.0F), s(1.0F), h - s(10.0F), applyAlpha(TEXT_PRIMARY, alpha));
            }
        }
    }
}
