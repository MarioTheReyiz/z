package me.pewa.util;

import org.lwjgl.opengl.GL11;

/**
 * Thin GL11 wrapper — mirrors the adaptive.sex.utils.render.GlStateManager API
 * so IngameHud compiles without changes to its render logic.
 */
public final class GlStateManager {

    private GlStateManager() {}

    public static void pushMatrix()  { GL11.glPushMatrix(); }
    public static void popMatrix()   { GL11.glPopMatrix(); }

    public static void translate(float x, float y, float z)  { GL11.glTranslatef(x, y, z); }
    public static void translate(double x, double y, double z) { GL11.glTranslated(x, y, z); }

    public static void scale(float x, float y, float z)   { GL11.glScalef(x, y, z); }
    public static void scale(double x, double y, double z) { GL11.glScalef((float)x, (float)y, (float)z); }

    public static void rotate(float angle, float x, float y, float z) { GL11.glRotatef(angle, x, y, z); }

    public static void color(float r, float g, float b, float a) { GL11.glColor4f(r, g, b, a); }
    public static void color(float r, float g, float b)          { GL11.glColor3f(r, g, b); }
    public static void resetColor() { GL11.glColor4f(1f, 1f, 1f, 1f); }

    public static void enableBlend()  { GL11.glEnable(GL11.GL_BLEND); }
    public static void disableBlend() { GL11.glDisable(GL11.GL_BLEND); }

    public static void blendFunc(int src, int dst) { GL11.glBlendFunc(src, dst); }

    public static void enableTexture2D()  { GL11.glEnable(GL11.GL_TEXTURE_2D); }
    public static void disableTexture2D() { GL11.glDisable(GL11.GL_TEXTURE_2D); }

    public static void enableDepth()  { GL11.glEnable(GL11.GL_DEPTH_TEST); }
    public static void disableDepth() { GL11.glDisable(GL11.GL_DEPTH_TEST); }

    public static void enableAlpha()  { GL11.glEnable(GL11.GL_ALPHA_TEST); }
    public static void disableAlpha() { GL11.glDisable(GL11.GL_ALPHA_TEST); }

    public static void enableCull()  { GL11.glEnable(GL11.GL_CULL_FACE); }
    public static void disableCull() { GL11.glDisable(GL11.GL_CULL_FACE); }

    public static void bindTexture(int id) { GL11.glBindTexture(GL11.GL_TEXTURE_2D, id); }

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        GL11.glColorMask(r, g, b, a);
    }

    public static void depthMask(boolean flag) { GL11.glDepthMask(flag); }

    public static void shadeModel(int mode) { GL11.glShadeModel(mode); }

    public static void matrixMode(int mode) { GL11.glMatrixMode(mode); }
    public static void loadIdentity()       { GL11.glLoadIdentity(); }

    public static void viewport(int x, int y, int w, int h) { GL11.glViewport(x, y, w, h); }

    public static void clear(int mask) { GL11.glClear(mask); }

    public static int generateTexture() { return GL11.glGenTextures(); }
    public static void deleteTexture(int id) { GL11.glDeleteTextures(id); }
}
