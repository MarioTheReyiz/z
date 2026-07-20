package me.pewa.ui;

import java.awt.Color;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import me.pewa.Pewa;
import me.pewa.mapping.MinecraftMapper;
import me.pewa.module.Module;
import me.pewa.module.impl.SpotifyWidget;
import me.pewa.notification.NotificationManager;
import me.pewa.util.FontUtil;
import me.pewa.util.GaussianBlur;
import me.pewa.util.Logger;
import me.pewa.util.MappingUtils;
import me.pewa.util.RoundedUtil;
import me.pewa.util.ScissorUtil;
import me.pewa.util.animation.Animation;
import me.pewa.util.animation.DecelerateAnimation;
import me.pewa.util.animation.Direction;

public final class IngameHud {

    // ── Slide-in animation ────────────────────────────────────────────────────
    private static final Animation OPEN_ANIM = new DecelerateAnimation(350, 1.0);

    // ── Module list sort cache ────────────────────────────────────────────────
    private static List<Module> sortedModules = new ArrayList<Module>();
    private static long lastSortTime = 0;

    // ── Per-module slide animations ───────────────────────────────────────────
    private static final Map<String, Float> anims = new HashMap<String, Float>();

    // ── Time cache ────────────────────────────────────────────────────────────
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");
    private static String cachedTime = "";
    private static long lastTimeUpdate = 0;

    // ── FPS counter ───────────────────────────────────────────────────────────
    private static long lastFpsTime = 0;
    private static int  frameCount  = 0;
    private static int  currentFps  = 0;

    // ── BlurText brand animation ──────────────────────────────────────────────
    // Two texts cycle: TEXTS[0] → TEXTS[1] → TEXTS[0] → ...
    private static final String[] TEXTS        = { "Seiko", "Evanescia" };
    // How long each text is fully visible (ms)
    private static final long     HOLD_MS      = 2500L;
    // How long the full out+in transition takes (ms)
    private static final long     TRANSITION_MS = 900L;
    // Per-character stagger delay (ms)
    private static final long     CHAR_DELAY_MS = 60L;

    // State machine: 0 = holding text A, 1 = transitioning A→B, 2 = holding B, 3 = transitioning B→A
    private static int  brandState     = 0;
    private static long brandStateStart = System.currentTimeMillis();
    private static int  brandTextIndex = 0;   // which text is currently "from"
    // Per-character animation progress [0..1] for the current transition
    // positive = fading in, negative = fading out (we store absolute progress)
    private static float[] charProgress = new float[0];

    // Animated brand section width (lerps toward target text width)
    private static float animatedBrandW = -1f;

    // ── Blur refresh throttle (30 fps = ~33ms) ────────────────────────────────
    private static long lastBlurRefresh = 0;
    private static final long BLUR_REFRESH_INTERVAL = 33L;
    public static float watermarkX = 8f;
    public static float watermarkY = 8f;
    private static boolean watermarkDragging = false;
    private static float   watermarkDragOffX = 0, watermarkDragOffY = 0;

    public static float arrayListX = 0f;   // right-edge offset
    public static float arrayListY = 10f;
    private static boolean arrayListDragging = false;
    private static float   arrayListDragOffX = 0, arrayListDragOffY = 0;

    // ── Draggable positions ───────────────────────────────────────────────────
    private static float lastWatermarkW = 0, lastWatermarkH = 0;

    // Expose watermark right edge for widgets that want to anchor to it
    public static float getWatermarkRight() {
        return watermarkX + lastWatermarkW;
    }
    private static float lastArrayListW = 0, lastArrayListH = 0;

    // ── Cached sizes for drag hit-testing ────────────────────────────────────
    private static volatile boolean firstRenderLogged;
    private static volatile boolean firstDrawLogged;
    private static volatile boolean mappingMissingLogged;
    private static volatile long    lastWarnTime;

    private static volatile Method drawStringMethod;

    private IngameHud() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry-points (called from C++ / ClickGui)
    // ─────────────────────────────────────────────────────────────────────────

    public static void renderFrame() {
        render(null, 0.0f);
    }

    public static void render(Object scaledResolution, float partialTicks) {
        try {
            if (!firstRenderLogged) {
                firstRenderLogged = true;
                Logger.info("IngameHud renderFrame reached");
                markHudStatus("JAVA_RENDER_FIRST_CALL");
            }

            // Keep FontRenderer check so the original mapping-missing path still works
            Object fontRenderer = MinecraftMapper.getFontRenderer();
            Method drawString   = getDrawStringMethod();
            if (fontRenderer == null || drawString == null) {
                if (!mappingMissingLogged) {
                    mappingMissingLogged = true;
                    markHudStatus("JAVA_RENDER_MAPPING_MISSING");
                }
                warnThrottled("IngameHud: FontRenderer mapping is not ready");
                return;
            }

            // ── Push the same GL state the original IngameHud used ────────────
            pushHudState();
            try {
                // ── Animate open ──────────────────────────────────────────────
                OPEN_ANIM.setDirection(Direction.FORWARDS);

                if (ClickGui.isUiEditMode()) {
                    ClickGui.renderFrame();
                    renderInternal(false, true, false, true);
                } else {
                    renderInternal();
                    ClickGui.renderFrame();
                }

                if (!firstDrawLogged) {
                    firstDrawLogged = true;
                    Logger.info("IngameHud draw completed");
                    markHudStatus("JAVA_RENDER_DRAW_OK");
                }
            } finally {
                popHudState();
            }

        } catch (Throwable t) {
            markHudStatus("JAVA_RENDER_EXCEPTION " + t.getClass().getName() + ": " + t.getMessage());
            warnThrottled("IngameHud render error: " + t.getMessage());
        }
    }

    /** Called by ClickGui when in UI-edit mode (renders after blur). */
    public static void renderForEditMode(float partialTicks) {
        pushHudState();
        try {
            renderInternal(true, false, true, false);
            renderSpotifyWidget(1.0f);
        } finally {
            popHudState();
        }
    }

    public static void resetDragState() {
        watermarkDragging = false;
        arrayListDragging = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core render
    // ─────────────────────────────────────────────────────────────────────────

    private static void renderInternal() {
        renderInternal(true, true, true, true);
    }

    private static void renderInternal(boolean drawLogo, boolean drawArrayList,
            boolean allowWatermarkDrag, boolean allowArrayListDrag) {
        float alpha = OPEN_ANIM.getOutput().floatValue();
        if (alpha < 0.01f) return;

        // Throttle blur cache invalidation to ~30 fps so the background
        // behind the watermark stays fresh without hammering the GPU.
        long now = System.currentTimeMillis();
        if (now - lastBlurRefresh >= BLUR_REFRESH_INTERVAL) {
            GaussianBlur.beginFrame();
            GaussianBlur.invalidateFrameCache();
            lastBlurRefresh = now;
        }

        // Screen size in scaled coords (ScissorUtil.getScaleFactor() == 2)
        int sw = ScissorUtil.getScaledWidth();
        int sh = ScissorUtil.getScaledHeight();

        // Mouse in scaled coords
        int mx = ScissorUtil.mapMouseX(Mouse.getX());
        int my = ScissorUtil.mapMouseY(Mouse.getY());

        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT |
                GL11.GL_COLOR_BUFFER_BIT |
                GL11.GL_DEPTH_BUFFER_BIT |
                GL11.GL_LIGHTING_BIT |
                GL11.GL_LINE_BIT |
                GL11.GL_TEXTURE_BIT |
                GL11.GL_TRANSFORM_BIT);
        GL11.glPushMatrix();

        // Drag handling only in edit mode
        if (ClickGui.isUiEditMode() && !Mouse.isGrabbed()) {
            if (allowWatermarkDrag) {
                handleWatermarkDrag(mx, my, sw, sh);
            }
            if (allowArrayListDrag) {
                handleArrayListDrag(mx, my, sw, sh);
            }
        }

        if (drawLogo) {
            renderLogo(sw, sh, alpha);
        }
        if (drawArrayList) {
            renderArrayList(sw, sh, alpha);
        }

        // SpotifyWidget render
        renderSpotifyWidget(alpha);

        // Notifications
        try { NotificationManager.render(); } catch (Throwable ignored) {}

        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Watermark / Logo
    // ─────────────────────────────────────────────────────────────────────────

    private static void renderLogo(int sw, int sh, float alpha) {
        updateTime();
        long time = System.currentTimeMillis();

        String fpsText   = getFPS() + " fps";
        String timeText  = cachedTime;

        float brandScale = 0.85f;
        float infoScale  = 0.75f;
        float sectionPad = 8f;
        float sepWidth   = 1f;

        // Brand width animates smoothly toward the currently displayed text's width.
        // During OUT phase we lerp toward the incoming text; during HOLD we snap to current.
        String currentBrandText = TEXTS[brandTextIndex];
        String nextBrandText    = TEXTS[(brandTextIndex + 1) % TEXTS.length];
        float currentBrandW = FontUtil.getStringWidth(currentBrandText) * brandScale;
        float nextBrandW    = FontUtil.getStringWidth(nextBrandText)    * brandScale;

        // Target: during OUT/IN transition lerp toward next width, otherwise current width
        float targetBrandW = (brandState == 1 || brandState == 2) ? nextBrandW : currentBrandW;
        if (animatedBrandW < 0) animatedBrandW = currentBrandW; // first frame init
        // Smooth lerp speed — fast enough to finish before IN phase ends
        animatedBrandW += (targetBrandW - animatedBrandW) * 0.08f;
        float brandW = animatedBrandW;

        float fixedFpsW  = FontUtil.getStringWidth("0000 fps") * infoScale;
        float fixedTimeW = FontUtil.getStringWidth("00:00")     * infoScale;

        float accentLineW = 1f;

        float sect1W = 4 + accentLineW + 4 + brandW + sectionPad;
        float sect2W = sectionPad + fixedFpsW + sectionPad;
        float sect3W = sectionPad + fixedTimeW + sectionPad;

        float width  = sect1W + sepWidth + sect2W + sepWidth + sect3W;
        float height = 22f;
        float radius = height / 2f;

        lastWatermarkW = width;
        lastWatermarkH = height;

        float x = watermarkX;
        float y = watermarkY + (1.0f - alpha) * -10;

        Color bgColor        = new Color(12, 12, 14,  (int)(70  * alpha));
        Color borderColor    = new Color(255,255,255, (int)(20  * alpha));
        Color separatorColor = new Color(255,255,255, (int)(60  * alpha));

        // 1. Shadow
        for (float s = 0.5f; s <= 4f; s += 0.5f) {
            float opacity = (float) Math.pow(1.0f - (s / 4f), 2.5f) * 0.08f * alpha;
            if (opacity < 0.003f) continue;
            RoundedUtil.roundedRect(x - s, y - s + 0.5f, width + s*2, height + s*2,
                    radius + s, new Color(0, 0, 0, (int)(255 * opacity)));
        }

        // 2. Blur (geometry-based rounded blur — no stencil)
        GaussianBlur.renderBlurRounded(x, y, width, height, radius, 10 * alpha);

        // 3. Background
        RoundedUtil.roundedRect(x, y, width, height, radius, bgColor);

        // 4. Border
        RoundedUtil.drawRoundOutline(x, y, width, height, radius, 0.5f, borderColor);

        // 4.5 Edit-mode border
        if (ClickGui.isUiEditMode()) {
            Color editBorder = watermarkDragging
                    ? new Color(100, 150, 255, (int)(150 * alpha))
                    : new Color(100, 150, 255, (int)( 60 * alpha));
            RoundedUtil.drawRoundOutline(x, y, width, height, radius, 1.5f, editBorder);
        }

        // 5. Separator lines
        float sepH = 12f;
        float sepY = y + (height - sepH) / 2f;

        float sep1X = x + sect1W;
        RoundedUtil.rect(sep1X, sepY, sepWidth, sepH, separatorColor);

        float sep2X = sep1X + sepWidth + sect2W;
        RoundedUtil.rect(sep2X, sepY, sepWidth, sepH, separatorColor);

        // 6. Animated bottom line
        float linePad     = radius + 2;
        float minLineW    = 8f;
        float cycleDur    = 4000f;
        float phase       = (time % (long) cycleDur) / cycleDur * (float) Math.PI * 2;
        float travelArea  = width - linePad * 2 - minLineW;

        float headF = (float) Math.sin(phase)        * 1.05f;
        float tailF = (float) Math.sin(phase - 0.7f) * 1.05f;
        headF = Math.max(-1f, Math.min(1f, headF)) * 0.5f + 0.5f;
        tailF = Math.max(-1f, Math.min(1f, tailF)) * 0.5f + 0.5f;

        float xHead   = x + linePad + travelArea * headF;
        float xTail   = x + linePad + travelArea * tailF;
        float lineX   = Math.min(xHead, xTail);
        float lineW   = Math.abs(xHead - xTail) + minLineW;

        float gradSpeed = 2000f;
        float gradPhase = (time % (long) gradSpeed) / gradSpeed;
        int bright1 = 60 + (int)(195 * (0.5 + 0.5 * Math.sin(gradPhase * Math.PI * 2)));
        int bright2 = 60 + (int)(195 * (0.5 + 0.5 * Math.sin((gradPhase + 0.5) * Math.PI * 2)));

        Color g1 = new Color(bright1, bright1, bright1, (int)(220 * alpha));
        Color g2 = new Color(bright2, bright2, bright2, (int)(255 * alpha));
        RoundedUtil.drawHorizontalGradientRoundedRect(lineX, y + height - 1, lineW, 0.5, 0.25, g1, g2);

        // 7. Brand text — BlurText animation (Seiko ↔ Evanescia)
        float brandY  = y + (height - FontUtil.getFontHeight() * brandScale) / 2f;
        float cursorX = x + 4 + accentLineW + 4;
        drawBlurText(cursorX, brandY, brandScale, alpha);

        // 8. FPS text
        int infoColor = new Color(180, 180, 190, (int)(255 * alpha)).getRGB();
        float infoY   = y + (height - FontUtil.getFontHeight() * infoScale) / 2f;

        float fpsSectionStart = x + sect1W + sepWidth;
        float fpsCenterX = fpsSectionStart + (sect2W - FontUtil.getStringWidth(fpsText) * infoScale) / 2f;
        GL11.glPushMatrix();
        GL11.glTranslatef(fpsCenterX, infoY, 0);
        GL11.glScalef(infoScale, infoScale, 1);
        FontUtil.drawString(fpsText, 0, 0, infoColor, false);
        GL11.glPopMatrix();

        // 9. Time text
        float timeSectionStart = sep2X + sepWidth;
        float timeCenterX = timeSectionStart + (sect3W - FontUtil.getStringWidth(timeText) * infoScale) / 2f;
        GL11.glPushMatrix();
        GL11.glTranslatef(timeCenterX, infoY, 0);
        GL11.glScalef(infoScale, infoScale, 1);
        FontUtil.drawString(timeText, 0, 0, infoColor, false);
        GL11.glPopMatrix();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ArrayList
    // ─────────────────────────────────────────────────────────────────────────

    private static void renderArrayList(int sw, int sh, float globalAlpha) {
        long now = System.currentTimeMillis();

        // Re-sort every second
        if (now - lastSortTime > 1000 || sortedModules.isEmpty()) {
            List<Module> all = Pewa.getInstance().getModuleManager().getModules();
            sortedModules = new ArrayList<Module>();
            for (Module m : all) {
                if (m.isEnabled() && !"HUD".equals(m.getName())) {
                    sortedModules.add(m);
                }
            }
            sortedModules.sort(new java.util.Comparator<Module>() {
                public int compare(Module a, Module b) {
                    return Float.compare(getModuleWidth(b), getModuleWidth(a));
                }
            });
            lastSortTime = now;
        }

        float barHeight = 13f;
        float barGap    = 1.5f;
        float textScale = 0.65f;

        float maxBarWidth = 0;
        for (Module mod : sortedModules) {
            float w = getModuleWidth(mod);
            if (w > maxBarWidth) maxBarWidth = w;
        }
        float totalHeight = sortedModules.size() * (barHeight + barGap);

        lastArrayListW = maxBarWidth + 4;
        lastArrayListH = totalHeight;

        // Edit-mode border
        if (ClickGui.isUiEditMode() && !sortedModules.isEmpty()) {
            float areaX = sw - maxBarWidth - arrayListX - 2;
            float areaY = arrayListY - 2;
            Color editBorder = arrayListDragging
                    ? new Color(100, 150, 255, (int)(150 * globalAlpha))
                    : new Color(100, 150, 255, (int)( 40 * globalAlpha));
            RoundedUtil.drawRoundOutline(areaX, areaY, maxBarWidth + 4, totalHeight + 4, 2f, 1f, editBorder);
        }

        float currentY = arrayListY;

        for (int i = 0; i < sortedModules.size(); i++) {
            Module mod  = sortedModules.get(i);
            String name = mod.getName();

            float modAnim  = animate("arr_" + name, 1f, 0.12f);
            float itemAlpha = modAnim * globalAlpha;
            if (itemAlpha < 0.01f) {
                currentY += barHeight + barGap;
                continue;
            }

            float textWidth = FontUtil.getStringWidth(name) * textScale;
            float barWidth  = textWidth + 8f + 2f;
            float slideOffset = (1.0f - modAnim) * 20;
            float xPos = sw - barWidth - arrayListX + slideOffset;

            // 1. Horizontal gradient background (transparent → dark glass)
            Color colTrans = new Color(12, 12, 14, 0);
            Color colDark  = new Color(12, 12, 14, (int)(120 * itemAlpha));
            RoundedUtil.drawHorizontalGradientRoundedRect(xPos, currentY, barWidth, barHeight, 0f, colTrans, colDark);

            // 2. Right accent line with wave
            float wave = (float) Math.sin((now / 600.0) + (currentY / 100.0)) * 0.5f + 0.5f;
            int brightness = 140 + (int)(115 * wave);
            Color accentColor = new Color(brightness, brightness, brightness, (int)(255 * itemAlpha));

            RoundedUtil.rect(xPos + barWidth - 2f, currentY, 2f, barHeight, accentColor);
            RoundedUtil.rect(xPos + barWidth - 4f, currentY, 6f, barHeight,
                    new Color(brightness, brightness, brightness, (int)(40 * itemAlpha)));

            // 3. Text with shimmer
            float waveProgress = (now % 2500L) / 2500f;
            float yNorm  = (currentY - 10f) / 500f;
            float wPhase = (yNorm * 0.7f + waveProgress) * (float) Math.PI * 2f;
            int textBright = 180 + (int)(75 * (Math.sin(wPhase) * 0.5 + 0.5));
            Color textColor = new Color(textBright, textBright, textBright, (int)(255 * itemAlpha));

            float textX = xPos + 4f;
            float textY = currentY + (barHeight - FontUtil.getFontHeight() * textScale) / 2f + 0.5f;

            GL11.glPushMatrix();
            GL11.glTranslatef(textX, textY, 0);
            GL11.glScalef(textScale, textScale, 1);
            FontUtil.drawString(name, 0, 0, textColor.getRGB(), false);
            GL11.glPopMatrix();

            currentY += barHeight + barGap;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drag handlers
    // ─────────────────────────────────────────────────────────────────────────

    private static void handleWatermarkDrag(int mx, int my, int sw, int sh) {
        float x = watermarkX, y = watermarkY;
        float w = lastWatermarkW > 0 ? lastWatermarkW : 150;
        float h = lastWatermarkH > 0 ? lastWatermarkH : 20;
        boolean inside = mx >= x && mx <= x + w && my >= y && my <= y + h;

        if (Mouse.isButtonDown(0)) {
            if (!watermarkDragging) {
                if (ClickGui.isDraggingAnyElement()) return;
                if (inside) {
                    watermarkDragging = true;
                    watermarkDragOffX = mx - x;
                    watermarkDragOffY = my - y;
                    ClickGui.setDraggingAnyElement(true);
                }
            }
            if (watermarkDragging) {
                watermarkX = Math.max(5, Math.min(sw - w - 5, mx - watermarkDragOffX));
                watermarkY = Math.max(5, Math.min(sh - h - 5, my - watermarkDragOffY));
            }
        } else {
            if (watermarkDragging) ClickGui.setDraggingAnyElement(false);
            watermarkDragging = false;
        }
    }

    private static void handleArrayListDrag(int mx, int my, int sw, int sh) {
        float w = lastArrayListW > 0 ? lastArrayListW : 100;
        float h = lastArrayListH > 0 ? lastArrayListH : 200;
        float x = sw - w - arrayListX;
        float y = arrayListY;
        boolean inside = mx >= x && mx <= x + w && my >= y && my <= y + h;

        if (Mouse.isButtonDown(0)) {
            if (!arrayListDragging) {
                if (ClickGui.isDraggingAnyElement()) return;
                if (inside) {
                    arrayListDragging = true;
                    arrayListDragOffX = mx - x;
                    arrayListDragOffY = my - y;
                    ClickGui.setDraggingAnyElement(true);
                }
            }
            if (arrayListDragging) {
                float newX = mx - arrayListDragOffX;
                float newY = my - arrayListDragOffY;
                arrayListX = Math.max(0, Math.min(sw - w - 5, sw - w - newX));
                arrayListY = Math.max(5, Math.min(sh - h - 5, newY));
            }
        } else {
            if (arrayListDragging) ClickGui.setDraggingAnyElement(false);
            arrayListDragging = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GL state push/pop  (identical to original IngameHud)
    // ─────────────────────────────────────────────────────────────────────────

    private static void pushHudState() {
        // Use scaled dimensions so HUD coordinates match GaussianBlur's GUI space.
        // ScissorUtil.getScaleFactor() == 2, so scaled = pixel / 2.
        int width  = Math.max(1, ScissorUtil.getScaledWidth());
        int height = Math.max(1, ScissorUtil.getScaledHeight());

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT
                | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT
                | GL11.GL_TRANSFORM_BIT);

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

    private static void popHudState() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void updateTime() {
        long now = System.currentTimeMillis();
        if (now - lastTimeUpdate > 1000) {
            cachedTime    = TIME_FORMAT.format(new java.util.Date(now));
            lastTimeUpdate = now;
        }
    }

    private static int getFPS() {
        if (lastFpsTime == 0) lastFpsTime = System.currentTimeMillis();
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            currentFps  = frameCount;
            frameCount  = 0;
            lastFpsTime = now;
        }
        return currentFps;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BlurText brand animation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Draws the cycling brand text with a per-character blur+fade+Y animation.
     *
     * State machine:
     *   0 = HOLD_A   — current text fully visible, waiting
     *   1 = OUT      — current text fading out char by char (top→down)
     *   2 = IN       — next text fading in char by char (top→down)
     *   (after IN completes → advance textIndex, go back to HOLD_A)
     */
    private static void drawBlurText(float x, float y, float scale, float globalAlpha) {
        long now = System.currentTimeMillis();
        long elapsed = now - brandStateStart;

        // ── State transitions ─────────────────────────────────────────────────
        if (brandState == 0 && elapsed >= HOLD_MS) {
            brandState      = 1;
            brandStateStart = now;
            elapsed         = 0;
        }

        String fromText = TEXTS[brandTextIndex];
        String toText   = TEXTS[(brandTextIndex + 1) % TEXTS.length];

        if (brandState == 1) {
            // OUT phase: all chars of fromText need to finish fading out
            // Last char starts at CHAR_DELAY_MS*(len-1), finishes at that + TRANSITION_MS/2
            long outDuration = CHAR_DELAY_MS * (fromText.length() - 1) + TRANSITION_MS / 2;
            if (elapsed >= outDuration) {
                brandState      = 2;
                brandStateStart = now;
                elapsed         = 0;
            }
        }

        if (brandState == 2) {
            // IN phase
            long inDuration = CHAR_DELAY_MS * (toText.length() - 1) + TRANSITION_MS / 2;
            if (elapsed >= inDuration) {
                // Transition complete — advance to next text
                brandTextIndex  = (brandTextIndex + 1) % TEXTS.length;
                brandState      = 0;
                brandStateStart = now;
                elapsed         = 0;
            }
        }

        // ── Draw ──────────────────────────────────────────────────────────────
        if (brandState == 0) {
            // Fully visible — draw with shimmer
            drawBrandChars(TEXTS[brandTextIndex], x, y, scale, globalAlpha, 1.0f, true);

        } else if (brandState == 1) {
            // Fading OUT: each char's progress goes 1→0
            String text = TEXTS[brandTextIndex];
            for (int i = 0; i < text.length(); i++) {
                long charStart = i * CHAR_DELAY_MS;
                float t = clamp01((elapsed - charStart) / (float)(TRANSITION_MS / 2));
                // decelerate ease: 1 - (t-1)^2  → but we want 1→0, so invert
                float eased = 1.0f - easeOut(t);
                drawBrandChar(text, i, x, y, scale, globalAlpha, eased, false);
            }

        } else {
            // brandState == 2: fading IN: each char's progress goes 0→1
            String text = TEXTS[(brandTextIndex + 1) % TEXTS.length];
            for (int i = 0; i < text.length(); i++) {
                long charStart = i * CHAR_DELAY_MS;
                float t = clamp01((elapsed - charStart) / (float)(TRANSITION_MS / 2));
                float eased = easeOut(t);
                drawBrandChar(text, i, x, y, scale, globalAlpha, eased, false);
            }
        }
    }

    /** Draw all chars of a text at full visibility, optionally with shimmer. */
    private static void drawBrandChars(String text, float x, float y,
                                        float scale, float globalAlpha,
                                        float charAlpha, boolean shimmer) {
        long time = System.currentTimeMillis();
        float cursor = 0f;
        for (int i = 0; i < text.length(); i++) {
            float finalAlpha = globalAlpha * charAlpha;
            int color;
            if (shimmer) {
                float shimmerCycle = 3000f;
                float shimmerPos   = (time % (long) shimmerCycle) / shimmerCycle;
                // shimmer sweeps left→right across the text
                float totalW = FontUtil.getStringWidth(text) * scale;
                float shimmerX = shimmerPos * (totalW + 20) - 10;
                float charX    = cursor * scale;
                boolean inShimmer = shimmerX > charX - 4 && shimmerX < charX + 4;
                int boost = inShimmer ? (int)(50 * Math.sin(((shimmerX - charX + 4) / 8f) * Math.PI)) : 0;
                color = new Color(255, 255, 255, Math.min(255, (int)(255 * finalAlpha) + boost)).getRGB();
            } else {
                color = new Color(255, 255, 255, (int)(255 * finalAlpha)).getRGB();
            }

            String ch = String.valueOf(text.charAt(i));
            GL11.glPushMatrix();
            GL11.glTranslatef(x + cursor * scale, y, 0);
            GL11.glScalef(scale, scale, 1);
            FontUtil.drawString(ch, 0, 0, color, false);
            GL11.glPopMatrix();

            cursor += FontUtil.getStringWidth(ch);
        }
    }

    /**
     * Draw a single character of a text with blur-text animation.
     * charT: 0 = invisible (blurred, offset), 1 = fully visible.
     */
    private static void drawBrandChar(String text, int charIndex,
                                       float x, float y,
                                       float scale, float globalAlpha,
                                       float charT, boolean shimmer) {
        // Compute cursor X up to this char
        float cursor = 0f;
        for (int i = 0; i < charIndex; i++) {
            cursor += FontUtil.getStringWidth(String.valueOf(text.charAt(i)));
        }

        // Y offset: chars come from above (like direction='top')
        float yOffset = (1.0f - charT) * -6f;  // -6 scaled units at t=0, 0 at t=1
        float alpha   = globalAlpha * charT;
        if (alpha < 0.005f) return;

        int color = new Color(255, 255, 255, (int)(255 * alpha)).getRGB();
        String ch = String.valueOf(text.charAt(charIndex));

        GL11.glPushMatrix();
        GL11.glTranslatef(x + cursor * scale, y + yOffset * scale, 0);
        GL11.glScalef(scale, scale, 1);
        FontUtil.drawString(ch, 0, 0, color, false);
        GL11.glPopMatrix();
    }

    /** Decelerate ease: 1 - (t-1)^2 */
    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1.0f - (t - 1.0f) * (t - 1.0f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float getModuleWidth(Module mod) {
        float textScale = 0.65f;
        float padX      = 8f;
        return padX + FontUtil.getStringWidth(mod.getName()) * textScale + padX + 8;
    }

    private static float animate(String key, float target, float speed) {
        float cur = anims.containsKey(key) ? anims.get(key) : 0f;
        cur += (target - cur) * speed;
        anims.put(key, cur);
        return cur;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FontRenderer method cache (kept from original IngameHud)
    // ─────────────────────────────────────────────────────────────────────────

    private static Method getDrawStringMethod() {
        Method cached = drawStringMethod;
        if (cached != null) return cached;
        Method mapped = MappingUtils.getMethod("FontRenderer.drawString");
        if (mapped == null) return null;
        mapped.setAccessible(true);
        drawStringMethod = mapped;
        return mapped;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status / logging (kept from original IngameHud)
    // ─────────────────────────────────────────────────────────────────────────

    private static void markHudStatus(String message) {
        FileWriter writer = null;
        try {
            writer = new FileWriter("C:\\pewa\\hud_hook.txt", true);
            writer.write(message);
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void renderSpotifyWidget(float alpha) {
        try {
            SpotifyWidget widget = Pewa.getInstance().getModuleManager().getModule(SpotifyWidget.class);
            if (widget == null || !widget.isEnabled()) return;
            if (ClickGui.isUiEditMode()) {
                widget.renderForEditMode();
            } else {
                widget.render();
            }
        } catch (Throwable t) {
            warnThrottled("SpotifyWidget render error: " + t.getMessage());
        }
    }

    private static void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarnTime < 5000L) return;
        lastWarnTime = now;
        Logger.warn(message);
    }
}
