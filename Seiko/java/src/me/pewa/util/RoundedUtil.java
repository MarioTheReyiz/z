package me.pewa.util;

import org.lwjgl.opengl.GL11;
import java.awt.Color;

/**
 * Rounded rectangle / outline / gradient drawing utilities.
 * Pure GL11 — no external dependencies.
 */
public final class RoundedUtil {

    private static final float[] SIN = new float[361];
    private static final float[] COS = new float[361];

    static {
        for (int i = 0; i <= 360; i++) {
            SIN[i] = (float) Math.sin(Math.toRadians(i));
            COS[i] = (float) Math.cos(Math.toRadians(i));
        }
    }

    private RoundedUtil() {}

    // ─── Solid rounded rect ───────────────────────────────────────────────────

    public static void roundedRect(double x, double y, double w, double h, double radius, Color color) {
        roundedRect(x, y, w, h, radius, color.getRGB());
    }

    public static void roundedRect(double x, double y, double w, double h, double radius, int rgba) {
        float a = ((rgba >> 24) & 0xFF) / 255f;
        float r = ((rgba >> 16) & 0xFF) / 255f;
        float g = ((rgba >>  8) & 0xFF) / 255f;
        float b = ( rgba        & 0xFF) / 255f;
        drawGradientRounded((float)x,(float)y,(float)w,(float)h,(float)radius,
                r,g,b,a, r,g,b,a, false);
    }

    // ─── Horizontal gradient rounded rect ────────────────────────────────────

    public static void drawHorizontalGradientRoundedRect(double x, double y, double w, double h,
                                                          double radius, Color left, Color right) {
        float r1 = left.getRed()/255f,  g1 = left.getGreen()/255f,
              b1 = left.getBlue()/255f, a1 = left.getAlpha()/255f;
        float r2 = right.getRed()/255f, g2 = right.getGreen()/255f,
              b2 = right.getBlue()/255f,a2 = right.getAlpha()/255f;
        drawGradientRounded((float)x,(float)y,(float)w,(float)h,(float)radius,
                r1,g1,b1,a1, r2,g2,b2,a2, true);
    }

    // ─── Plain rect ──────────────────────────────────────────────────────────

    public static void rect(double x, double y, double w, double h, Color color) {
        rect(x, y, w, h, color.getRGB());
    }

    public static void rect(double x, double y, double w, double h, int rgba) {
        float a = ((rgba >> 24) & 0xFF) / 255f;
        float r = ((rgba >> 16) & 0xFF) / 255f;
        float g = ((rgba >>  8) & 0xFF) / 255f;
        float b = ( rgba        & 0xFF) / 255f;

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(x,     y);
        GL11.glVertex2d(x + w, y);
        GL11.glVertex2d(x + w, y + h);
        GL11.glVertex2d(x,     y + h);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1,1,1,1);
        GL11.glPopMatrix();
    }

    // ─── Rounded outline ─────────────────────────────────────────────────────

    public static void drawRoundOutline(float x, float y, float w, float h,
                                         float radius, float lineWidth, Color color) {
        drawRoundOutline(x, y, w, h, radius, lineWidth, color.getRGB());
    }

    public static void drawRoundOutline(float x, float y, float w, float h,
                                         float radius, float lineWidth, int rgba) {
        float a = ((rgba >> 24) & 0xFF) / 255f;
        float r = ((rgba >> 16) & 0xFF) / 255f;
        float g = ((rgba >>  8) & 0xFF) / 255f;
        float b = ( rgba        & 0xFF) / 255f;

        if (radius < 0) radius = 0;
        radius = Math.min(radius, Math.min(w, h) / 2f);
        int seg = (int) Math.min(Math.max(radius * 1.2f, 12), 36);

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glColor4f(r, g, b, a);
        GL11.glLineWidth(lineWidth);

        GL11.glBegin(GL11.GL_LINE_LOOP);
        drawArc(x + radius,     y + radius,      radius, seg, 180, 270);
        drawArc(x + w - radius, y + radius,      radius, seg, 270, 360);
        drawArc(x + w - radius, y + h - radius,  radius, seg,   0,  90);
        drawArc(x + radius,     y + h - radius,  radius, seg,  90, 180);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1,1,1,1);
        GL11.glPopMatrix();
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private static void drawGradientRounded(float x, float y, float w, float h, float radius,
                                             float r1, float g1, float b1, float a1,
                                             float r2, float g2, float b2, float a2,
                                             boolean horizontal) {
        if (radius < 0) radius = 0;
        radius = Math.min(radius, Math.min(w, h) / 2f);
        int seg = (int) Math.min(Math.max(radius * 1.2f, 12), 36);

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);

        GL11.glBegin(GL11.GL_POLYGON);
        renderCorner(x,y,w,h,radius,seg,180,270, r1,g1,b1,a1, r2,g2,b2,a2, horizontal);
        renderCorner(x,y,w,h,radius,seg,270,360, r1,g1,b1,a1, r2,g2,b2,a2, horizontal);
        renderCorner(x,y,w,h,radius,seg,  0, 90, r1,g1,b1,a1, r2,g2,b2,a2, horizontal);
        renderCorner(x,y,w,h,radius,seg, 90,180, r1,g1,b1,a1, r2,g2,b2,a2, horizontal);
        GL11.glEnd();

        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1,1,1,1);
        GL11.glPopMatrix();
    }

    private static void renderCorner(float x, float y, float w, float h, float radius, int seg,
                                      int start, int end,
                                      float r1, float g1, float b1, float a1,
                                      float r2, float g2, float b2, float a2,
                                      boolean horizontal) {
        float cx = x + (start == 180 || start == 90  ? radius : w - radius);
        float cy = y + (start == 180 || start == 270 ? radius : h - radius);

        for (int i = 0; i <= seg; i++) {
            int deg = start + (end - start) * i / seg;
            int idx = deg % 360;
            if (idx < 0) idx += 360;

            float px = cx + COS[idx] * radius;
            float py = cy + SIN[idx] * radius;

            float pct = horizontal ? (px - x) / w : (py - y) / h;
            float r = r1 + (r2 - r1) * pct;
            float g = g1 + (g2 - g1) * pct;
            float b = b1 + (b2 - b1) * pct;
            float a = a1 + (a2 - a1) * pct;

            GL11.glColor4f(r, g, b, a);
            GL11.glVertex2f(px, py);
        }
    }

    private static void drawArc(float cx, float cy, float radius, int seg, int start, int end) {
        for (int i = 0; i <= seg; i++) {
            int deg = start + (end - start) * i / seg;
            int idx = deg % 360;
            if (idx < 0) idx += 360;
            GL11.glVertex2f(cx + COS[idx] * radius, cy + SIN[idx] * radius);
        }
    }
}
