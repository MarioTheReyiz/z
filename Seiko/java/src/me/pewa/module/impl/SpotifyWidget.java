package me.pewa.module.impl;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import me.pewa.module.Category;
import me.pewa.ui.IngameHud;
import me.pewa.module.Module;
import me.pewa.ui.ClickGui;
import me.pewa.util.FontUtil;
import me.pewa.util.GaussianBlur;
import me.pewa.util.GlStateManager;
import me.pewa.util.RoundedUtil;
import me.pewa.util.ScissorUtil;
import me.pewa.util.StencilUtil;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * SpotifyWidget — native backend'den gelen medya bilgisini render eder.
 * Native (C++) taraf şu static volatile field'lara yazar:
 *   nativeTrack, nativeArtist, nativePlaying, nativeAvailable,
 *   nativePosition, nativeDuration, nativeCommand (Java→C++ komut kanalı)
 */
public class SpotifyWidget extends Module {

    // ── Native bridge (C++ bu field'lara yazar) ───────────────────────────────
    public static volatile String  nativeTrack     = "No Track";
    public static volatile String  nativeArtist    = "";
    public static volatile boolean nativePlaying   = false;
    public static volatile boolean nativeAvailable = false;
    public static volatile long    nativePosition  = 0;
    public static volatile long    nativeDuration  = 0;
    /** 0=none  1=prev  2=play/pause  3=next  — Java yazar, C++ okur & sıfırlar */
    public static volatile int     nativeCommand   = 0;

    // ── Position & size ───────────────────────────────────────────────────────
    public static float posX   = 10f;
    public static float posY   = 10f;
    public static float width  = 210f;
    public static float height = 62f;

    private static final float MIN_W = 180f, MAX_W = 420f;
    private static final float MIN_H = 50f,  MAX_H = 110f;

    // ── Drag / resize state ───────────────────────────────────────────────────
    private static boolean isDragging  = false;
    private static boolean isResizing  = false;
    private static float   dragOffX    = 0f, dragOffY = 0f;
    private long lastClickTime = 0L;
    private static final long CLICK_COOLDOWN = 200L;

    public static void resetDragState() {
        isDragging = false;
        isResizing = false;
    }

    // ── Album art ─────────────────────────────────────────────────────────────
    private int            albumArtTex    = -1;
    private boolean        texLoaded      = false;
    private AtomicBoolean  isLoading      = new AtomicBoolean(false);
    private BufferedImage  pendingImage   = null;
    private long           lastArtCheck   = 0L;
    private long           lastArtMod     = 0L;

    // ── Animations ────────────────────────────────────────────────────────────
    private float showAnim        = 0f;
    private float progressAnim    = 0f;
    private float trackChangeAnim = 1f;
    private String lastTrackUID   = "";

    // ── Truncation cache ──────────────────────────────────────────────────────
    private String cachedTrack  = "";
    private String cachedArtist = "";
    private float  lastAvailW   = 0f;
    private String lastRawTrack = "";
    private String lastRawArtist = "";

    // ── Style constants ───────────────────────────────────────────────────────
    private static final float RADIUS          = 6f;
    private static final Color BG_COLOR        = new Color(12, 12, 14, 70);
    private static final Color ACCENT_COLOR    = new Color(140, 140, 150);

    // ── Constructor ───────────────────────────────────────────────────────────
    public SpotifyWidget() {
        super("SpotifyWidget", "Spotify media widget", Category.RENDER, 0);
        try {
            // Anchor next to watermark by default
            float right = IngameHud.getWatermarkRight();
            posX = right + 8f;
            posY = IngameHud.watermarkY;
        } catch (Throwable ignored) {}
    }

    @Override
    public void onEnable() {
        showAnim       = 0f;
        progressAnim   = 0f;
        texLoaded      = false;
        isLoading.set(false);
        pendingImage   = null;
        lastArtMod     = 0L;
        if (albumArtTex != -1) {
            GL11.glDeleteTextures(albumArtTex);
            albumArtTex = -1;
        }
    }

    @Override
    public void onDisable() {
        if (albumArtTex != -1) {
            GL11.glDeleteTextures(albumArtTex);
            albumArtTex = -1;
        }
    }

    @Override
    public void onUpdate() { /* polling native tarafta */ }

    // ── Public render entry-points (IngameHud çağırır) ────────────────────────

    /** Normal HUD render — edit modunda çağrılmaz. */
    public void render() {
        if (!isEnabled()) return;
        if (ClickGui.isUiEditMode()) return;
        renderInternal();
    }

    /** Edit modunda ClickGui tarafından çağrılır (blur sonrası). */
    public void renderForEditMode() {
        if (!isEnabled()) return;
        renderInternal();
    }

    // ── Core render ───────────────────────────────────────────────────────────
    private void renderInternal() {

        // Veri güncelle
        String track  = nativeTrack  != null ? nativeTrack  : "No Track";
        String artist = nativeArtist != null ? nativeArtist : "";

        if (!nativeAvailable) {
            track  = "Spotify";
            artist = "Not Connected";
        }

        String uid = track + artist;
        if (!uid.equals(lastTrackUID)) {
            if (!lastTrackUID.isEmpty()) trackChangeAnim = 0f;
            lastTrackUID = uid;
        }

        showAnim        += (1f - showAnim)        * 0.15f;
        trackChangeAnim += (1f - trackChangeAnim) * 0.10f;

        int sw = ScissorUtil.getScaledWidth();
        int sh = ScissorUtil.getScaledHeight();
        int mx = ScissorUtil.mapMouseX(Mouse.getX());
        int my = ScissorUtil.mapMouseY(Mouse.getY());

        boolean guiOpen = ClickGui.isUiEditMode();
        boolean mouseFree = !Mouse.isGrabbed();

        if (mouseFree) handleInput(mx, my, sw, sh);

        // Clamp
        posX = Math.max(5f, Math.min(sw - width  - 5f, posX));
        posY = Math.max(5f, Math.min(sh - height - 5f, posY));

        float alpha = showAnim;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, posY, 0f);

        float x = 0f, y = 0f;

        // 1. Shadow
        if (!isDragging && !isResizing) {
            for (float s = 1f; s <= 3f; s++) {
                float ext = s * 1.2f;
                float sa  = 5f * (1f - s / 4f) * alpha;
                RoundedUtil.roundedRect(x - ext, y + 1 - ext * 0.5f,
                        width + ext * 2, height + ext,
                        RADIUS + ext, new Color(0, 0, 0, (int) sa));
            }
        }

        // 2. Blur
        if (!isDragging && !isResizing) {
            GaussianBlur.renderBlurRounded(posX, posY, width, height, RADIUS, 8f * alpha);
        }

        // 3. BG overlay
        RoundedUtil.roundedRect(x, y, width, height, RADIUS, applyAlpha(BG_COLOR, alpha));

        // 4. Left accent line
        float accentW = 1f;
        RoundedUtil.roundedRect(x + 4f, y + 6f, accentW, height - 12f,
                accentW * 0.5f, applyAlpha(ACCENT_COLOR, alpha));

        // 5. Border (edit mode)
        if (guiOpen) {
            int ba = (isDragging || isResizing) ? 80 : 30;
            Color bc = (isDragging || isResizing)
                    ? new Color(100, 150, 255, ba)
                    : new Color(255, 255, 255, ba);
            RoundedUtil.drawRoundOutline(x, y, width, height, RADIUS, 1f, bc);

            // Resize handle
            float hs = 8f;
            RoundedUtil.roundedRect(x + width - hs - 3f, y + height - hs - 3f, hs, hs, 1.5f,
                    isResizing ? new Color(100, 150, 255, 150) : new Color(255, 255, 255, 40));
        }

        // ── Content ───────────────────────────────────────────────────────────
        float contentX = x + 4f + accentW + 6f;

        // Album art
        float artSize = height - 12f;
        float artX    = contentX;
        float artY    = y + 6f;

        if (!isDragging && !isResizing) {
            checkAndLoadAlbumArt("C:\\pewa\\spotify_art.png");
            if (pendingImage != null) uploadTexture();
        }

        // Art scale bounce on track change
        float artScale = 1f;
        if (trackChangeAnim < 1f)
            artScale = 1f + (float) Math.sin(trackChangeAnim * Math.PI) * 0.1f;

        if (artScale != 1f) {
            GL11.glPushMatrix();
            GL11.glTranslatef(artX + artSize * 0.5f, artY + artSize * 0.5f, 0f);
            GL11.glScalef(artScale, artScale, 1f);
            GL11.glTranslatef(-(artX + artSize * 0.5f), -(artY + artSize * 0.5f), 0f);
        }

        if (texLoaded && albumArtTex != -1 && !isDragging && !isResizing) {
            drawRoundedTexture(albumArtTex, artX, artY, artSize, alpha);
        } else {
            RoundedUtil.roundedRect(artX, artY, artSize, artSize, 6f,
                    applyAlpha(new Color(30, 30, 30), alpha));
        }

        if (artScale != 1f) GL11.glPopMatrix();

        // Text area
        float textX  = artX + artSize + 8f;
        float textW  = width - (textX - x) - 6f;
        float availW = textW - 90f; // ruang untuk icon kontrol

        float centerY = y + height * 0.5f - 6f;

        if (!track.equals(lastRawTrack) || !artist.equals(lastRawArtist)
                || Math.abs(availW - lastAvailW) > 2f) {
            cachedTrack  = FontUtil.trimToWidth(sanitize(track),  (int) availW, 0.75f);
            cachedArtist = FontUtil.trimToWidth(sanitize(artist), (int) availW, 0.60f);
            lastRawTrack  = track;
            lastRawArtist = artist;
            lastAvailW    = availW;
        }

        float textAlpha = alpha;
        float textOffY  = 0f;
        if (trackChangeAnim < 1f) {
            textAlpha *= trackChangeAnim;
            textOffY   = (1f - trackChangeAnim) * 3f;
        }

        // Song title
        int titleCol  = new Color(255, 255, 255, (int)(255 * textAlpha)).getRGB();
        int artistCol = new Color(180, 180, 180, (int)(255 * textAlpha)).getRGB();

        GL11.glPushMatrix();
        GL11.glTranslatef(textX, centerY - 7f + textOffY, 0f);
        GL11.glScalef(0.75f, 0.75f, 1f);
        FontUtil.drawString(cachedTrack, 0, 0, titleCol, false);
        GL11.glPopMatrix();

        GL11.glPushMatrix();
        GL11.glTranslatef(textX, centerY + 3f + textOffY, 0f);
        GL11.glScalef(0.60f, 0.60f, 1f);
        FontUtil.drawString(cachedArtist, 0, 0, artistCol, false);
        GL11.glPopMatrix();

        // Progress bar
        if (nativeDuration > 0) {
            float barY = centerY + 22f;
            float barW = textW - 5f;
            float barH = 1.5f;

            float target = (float) nativePosition / (float) nativeDuration;
            progressAnim += (target - progressAnim) * 0.15f;
            progressAnim  = Math.max(0f, Math.min(1f, progressAnim));

            RoundedUtil.roundedRect(textX, barY, barW, barH, 0.75f,
                    applyAlpha(new Color(60, 60, 60), alpha));
            RoundedUtil.roundedRect(textX, barY, Math.max(1f, barW * progressAnim), barH, 0.75f,
                    applyAlpha(Color.WHITE, alpha));
        }

        // ── Controls ──────────────────────────────────────────────────────────
        float ctrlX   = x + width - 40f;
        float ctrlY   = y + height * 0.5f;
        float iconGap = 24f;

        // Local mouse (widget-relative)
        float lmx = mx - posX;
        float lmy = my - posY;

        boolean hPrev = hovered(lmx, lmy, ctrlX - iconGap - 5f, ctrlY - 5f, 10f, 10f);
        boolean hPlay = hovered(lmx, lmy, ctrlX - 5f,           ctrlY - 5f, 10f, 10f);
        boolean hNext = hovered(lmx, lmy, ctrlX + iconGap - 5f, ctrlY - 5f, 10f, 10f);

        Color cPrev = hPrev ? Color.WHITE : new Color(200, 200, 200);
        Color cPlay = hPlay ? Color.WHITE : new Color(200, 200, 200);
        Color cNext = hNext ? Color.WHITE : new Color(200, 200, 200);

        drawPrevIcon(ctrlX - iconGap, ctrlY, 10f, cPrev, alpha);
        if (nativePlaying) drawPauseIcon(ctrlX, ctrlY, 11f, cPlay, alpha);
        else               drawPlayIcon (ctrlX, ctrlY, 11f, cPlay, alpha);
        drawNextIcon(ctrlX + iconGap, ctrlY, 10f, cNext, alpha);

        if (Mouse.isButtonDown(0)) {
            if (hPrev && cooldown()) nativeCommand = 1;
            if (hPlay && cooldown()) nativeCommand = 2;
            if (hNext && cooldown()) nativeCommand = 3;
        }

        GlStateManager.popMatrix();
        GL11.glPopAttrib();
    }

    // ── Input handling ────────────────────────────────────────────────────────
    private void handleInput(int mx, int my, int sw, int sh) {
        if (!ClickGui.isUiEditMode()) {
            isDragging = false;
            isResizing = false;
            return;
        }

        float x = posX, y = posY, w = width, h = height;
        boolean inside   = mx >= x && mx <= x + w && my >= y && my <= y + h;
        boolean onResize = mx >= x + w - 20f && mx <= x + w + 5f
                        && my >= y + h - 20f && my <= y + h + 5f;

        if (Mouse.isButtonDown(0)) {
            if (!isDragging && !isResizing) {
                if (ClickGui.isDraggingAnyElement()) return;
                if (onResize) {
                    isResizing = true;
                    ClickGui.setDraggingAnyElement(true);
                } else if (inside) {
                    boolean onControls = mx >= x + w - 60f && mx <= x + w;
                    if (!onControls) {
                        isDragging = true;
                        dragOffX   = mx - x;
                        dragOffY   = my - y;
                        ClickGui.setDraggingAnyElement(true);
                    }
                }
            }
            if (isDragging) {
                posX = Math.max(5f, Math.min(sw - width  - 5f, mx - dragOffX));
                posY = Math.max(5f, Math.min(sh - height - 5f, my - dragOffY));
            }
            if (isResizing) {
                width  = Math.max(MIN_W, Math.min(MAX_W, mx - x));
                height = Math.max(MIN_H, Math.min(MAX_H, my - y));
            }
        } else {
            if (isDragging || isResizing) ClickGui.setDraggingAnyElement(false);
            isDragging = false;
            isResizing = false;
        }
    }

    // ── Album art helpers ─────────────────────────────────────────────────────
    private void checkAndLoadAlbumArt(String path) {
        if (isLoading.get() || pendingImage != null) return;
        long now = System.currentTimeMillis();
        if (now - lastArtCheck < 2000L) return;
        lastArtCheck = now;

        File f = new File(path);
        if (!f.exists()) return;

        long mod = f.lastModified();
        if (texLoaded && mod == lastArtMod) return;
        lastArtMod = mod;
        isLoading.set(true);

        new Thread(() -> {
            try {
                BufferedImage img = ImageIO.read(f);
                if (img != null) pendingImage = img;
            } catch (Exception ignored) {
            } finally {
                isLoading.set(false);
            }
        }, "SpotifyArtLoader").start();
    }

    private void uploadTexture() {
        if (pendingImage == null) return;
        try {
            if (albumArtTex != -1) GL11.glDeleteTextures(albumArtTex);
            albumArtTex = GL11.glGenTextures();

            BufferedImage img = pendingImage;
            int w = img.getWidth(), h = img.getHeight();
            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);

            java.nio.ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int px : pixels) {
                buf.put((byte)((px >> 16) & 0xFF));
                buf.put((byte)((px >>  8) & 0xFF));
                buf.put((byte)( px        & 0xFF));
                buf.put((byte)((px >> 24) & 0xFF));
            }
            ((java.nio.Buffer) buf).flip();

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, albumArtTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            texLoaded = true;
        } catch (Exception ignored) {
        }
        pendingImage = null;
    }

    private void drawRoundedTexture(int tex, float x, float y, float size, float alpha) {
        StencilUtil.initStencilToWrite();
        RoundedUtil.roundedRect(x, y, size, size, 6f, Color.WHITE);
        StencilUtil.readStencilBuffer(1);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(x,        y);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(x,        y + size);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(x + size, y + size);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(x + size, y);
        GL11.glEnd();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        StencilUtil.uninitStencilBuffer();
    }

    // ── Icon draw helpers ─────────────────────────────────────────────────────
    private void drawPrevIcon(float cx, float cy, float size, Color color, float alpha) {
        prepareIcon(color, alpha);
        float s = size * 0.45f;
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - s, cy - s); GL11.glVertex2f(cx - s, cy + s);
        GL11.glEnd();
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(cx + s, cy - s);
        GL11.glVertex2f(cx - s * 0.2f, cy);
        GL11.glVertex2f(cx + s, cy + s);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private void drawNextIcon(float cx, float cy, float size, Color color, float alpha) {
        prepareIcon(color, alpha);
        float s = size * 0.45f;
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx + s, cy - s); GL11.glVertex2f(cx + s, cy + s);
        GL11.glEnd();
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(cx - s, cy - s);
        GL11.glVertex2f(cx + s * 0.2f, cy);
        GL11.glVertex2f(cx - s, cy + s);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private void drawPlayIcon(float cx, float cy, float size, Color color, float alpha) {
        prepareIcon(color, alpha);
        float s = size * 0.5f;
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(cx - s * 0.4f, cy - s);
        GL11.glVertex2f(cx + s * 0.6f, cy);
        GL11.glVertex2f(cx - s * 0.4f, cy + s);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private void drawPauseIcon(float cx, float cy, float size, Color color, float alpha) {
        prepareIcon(color, alpha);
        float s   = size * 0.45f;
        float gap = s * 0.35f;
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - gap, cy - s); GL11.glVertex2f(cx - gap, cy + s);
        GL11.glVertex2f(cx + gap, cy - s); GL11.glVertex2f(cx + gap, cy + s);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
    }

    private void prepareIcon(Color color, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.5f);
        Color c = applyAlpha(color, alpha);
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f,
                       c.getBlue() / 255f, c.getAlpha() / 255f);
    }

    // ── Util helpers ──────────────────────────────────────────────────────────
    private Color applyAlpha(Color color, float alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                (int)(color.getAlpha() * alpha));
    }

    private boolean hovered(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private boolean cooldown() {
        long now = System.currentTimeMillis();
        if (now - lastClickTime > CLICK_COOLDOWN) {
            lastClickTime = now;
            return true;
        }
        return false;
    }

    private String sanitize(String text) {
        if (text == null) return "";
        return text
            .replace('\u015f', 's').replace('\u015e', 'S')
            .replace('\u0131', 'i').replace('\u0130', 'I')
            .replace('\u011f', 'g').replace('\u011e', 'G')
            .replace('\u00fc', 'u').replace('\u00dc', 'U')
            .replace('\u00f6', 'o').replace('\u00d6', 'O')
            .replace('\u00e7', 'c').replace('\u00c7', 'C');
    }
}
