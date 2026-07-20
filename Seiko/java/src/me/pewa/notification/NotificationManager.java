package me.pewa.notification;

import me.pewa.util.FontUtil;
import me.pewa.util.GaussianBlur;
import me.pewa.util.GlStateManager;
import me.pewa.util.RoundedUtil;
import me.pewa.util.ScissorUtil;
import me.pewa.util.StencilUtil;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public class NotificationManager {

    private static Notification currentNotification = null;
    private static final Map<String, Float> anims = new HashMap<>();

    private static final Color EMERALD = new Color(16, 185, 129);

    public static void post(String title, String message, Notification.Type type, long duration) {
        currentNotification = new Notification(title, message, type, duration);
    }

    public static void postModule(String moduleName, boolean enabled) {
        post(moduleName,
             enabled ? "Enabled" : "Disabled",
             enabled ? Notification.Type.ENABLED : Notification.Type.DISABLED,
             2000);
    }

    public static Notification getCurrentNotification() {
        if (currentNotification != null) {
            if (System.currentTimeMillis() - currentNotification.getStartTime()
                    > currentNotification.getDuration() + 400) {
                currentNotification = null;
            }
        }
        return currentNotification;
    }

    public static void render() {
        int sw = ScissorUtil.getScaledWidth();
        int sh = ScissorUtil.getScaledHeight();

        Notification n = getCurrentNotification();
        if (n == null) {
            anims.put("notif_anim",     0f);
            anims.put("notif_progress", 0f);
            return;
        }

        boolean isClosing  = n.isFinished();
        float   slideAnim  = animate("notif_anim", isClosing ? 0.0f : 1.0f, 0.12f);

        if (slideAnim < 0.01f && isClosing) return;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();

        float eased          = 1f - (float) Math.pow(1 - slideAnim, 3);
        float targetProgress = 1.0f - n.getProgress();
        float smoothProgress = animate("notif_progress", targetProgress, 0.1f);

        // Layout
        float pillHeight = 32f;
        float pillRadius = 6f;
        float padX       = 10f;
        float margin     = 10f;

        String title   = n.getTitle();
        String message = n.getMessage();
        float  textScale = 0.65f;

        float titleW   = FontUtil.getStringWidth(title)   * textScale;
        float msgW     = FontUtil.getStringWidth(message) * textScale;
        float contentW = Math.max(titleW, msgW);

        float pillWidth = Math.max(100f, contentW + padX * 2 + 14);

        // Slide from right
        float slideOffset = (1f - eased) * (pillWidth + margin + 20);
        float x = sw - pillWidth - margin + slideOffset;
        float y = sh - pillHeight - margin - 12;

        // Accent color
        Color accent = new Color(140, 140, 150);
        if      (n.getType() == Notification.Type.ENABLED)  accent = EMERALD;
        else if (n.getType() == Notification.Type.DISABLED) accent = new Color(239, 68, 68);

        float alpha = eased;

        // Shadow
        for (float s = 1; s <= 3; s++) {
            float ext         = s * 1.2f;
            float shadowAlpha = 5f * (1f - (s / 4f)) * alpha;
            RoundedUtil.roundedRect(x - ext, y + 1 - ext / 2f,
                    pillWidth + ext * 2, pillHeight + ext,
                    pillRadius + ext, new Color(0, 0, 0, (int) shadowAlpha));
        }

        // Blur background
        GaussianBlur.renderBlurRounded(x, y, pillWidth, pillHeight, pillRadius, 8f * alpha);

        // Dark overlay
        RoundedUtil.roundedRect(x, y, pillWidth, pillHeight, pillRadius,
                new Color(12, 12, 14, (int) (70 * alpha)));

        // Left accent dot
        float dotSize = 6f;
        float dotX    = x + 8f;
        float dotY    = y + (pillHeight - dotSize) / 2f;
        RoundedUtil.roundedRect(dotX, dotY, dotSize, dotSize, dotSize / 2f,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int) (255 * alpha)));

        // Text
        float textX = dotX + dotSize + 8;
        float textY = y + 6;

        int titleColor = new Color(255, 255, 255, (int) (255 * alpha)).getRGB();
        int msgColor   = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int) (200 * alpha)).getRGB();

        // Title
        GL11.glPushMatrix();
        GL11.glTranslatef(textX, textY, 0);
        GL11.glScalef(textScale, textScale, 1f);
        FontUtil.drawString(title, 0, 0, titleColor, false);
        GL11.glPopMatrix();

        // Message
        float msgScale = textScale * 0.9f;
        float msgY     = textY + FontUtil.getFontHeight() * textScale + 2;
        GL11.glPushMatrix();
        GL11.glTranslatef(textX, msgY, 0);
        GL11.glScalef(msgScale, msgScale, 1f);
        FontUtil.drawString(message, 0, 0, msgColor, false);
        GL11.glPopMatrix();

        // Bottom progress line
        float lineH      = 1.5f;
        float lineY      = y + pillHeight - 4;
        float availableW = pillWidth - 16;
        float lineW      = availableW * smoothProgress;

        RoundedUtil.roundedRect(x + 8, lineY, lineW, lineH, lineH / 2f,
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int) (180 * alpha)));

        GL11.glPopMatrix();
        GL11.glPopAttrib();
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static float animate(String key, float target, float speed) {
        float cur = anims.getOrDefault(key, 0f);
        cur += (target - cur) * speed;
        anims.put(key, cur);
        return cur;
    }
}
