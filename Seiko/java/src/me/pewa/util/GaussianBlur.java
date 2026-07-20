package me.pewa.util;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.glUniform1;

/**
 * Gaussian Blur - Geometry-Based Rounded Masking.
 *
 * NO glScissor. NO Stencil Buffer. NO SDF Shader.
 * Rounded corners come from drawing a textured polygon (RoundedUtil geometry).
 * Works on ALL hardware: Intel HD, AMD, NVIDIA.
 *
 * Usage:
 *   GaussianBlur.renderBlur(radius);                                    // Full screen
 *   GaussianBlur.renderBlur(x, y, w, h, radius);                       // Regional rect
 *   GaussianBlur.renderBlurRounded(x, y, w, h, cornerRadius, blurRadius); // Rounded rect
 */
public class GaussianBlur {

    // --- Shader (Lazy Init) ---
    private static int blurProgram = -1;
    private static boolean shaderInitFailed = false;

    // --- FBO Resources ---
    private static int texture1 = -1;
    private static int texture2 = -1;
    private static int fbo2 = -1;
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    /** Frame-scoped blur cache: one screen capture + horizontal pass per frame. */
    private static long renderFrameId = 0;
    private static long preparedFrameId = -1;
    private static float preparedRadius = -1f;

    public static void beginFrame() {
        renderFrameId++;
    }

    /** HUD widget cache'ini sıfırla (ClickGUI aç/kapa sonrası ghost blur için). */
    public static void invalidateFrameCache() {
        preparedFrameId = -1;
        preparedRadius = -1f;
    }

    private static final float RADIUS_CACHE_TOLERANCE = 0.35f;

    // --- SIN/COS cache for rounded polygon ---
    private static final float[] SIN = new float[361];
    private static final float[] COS = new float[361];
    static {
        for (int i = 0; i <= 360; i++) {
            SIN[i] = (float) Math.sin(Math.toRadians(i));
            COS[i] = (float) Math.cos(Math.toRadians(i));
        }
    }

    // =============================================
    // Shader Source (GLSL 120 - max compat)
    // =============================================

    private static final String VERT_SRC =
            "#version 120\n" +
            "void main() {\n" +
            "    gl_TexCoord[0] = gl_MultiTexCoord0;\n" +
            "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n" +
            "}";

    private static final String FRAG_SRC =
            "#version 120\n" +
            "uniform sampler2D textureIn;\n" +
            "uniform vec2 texelSize;\n" +
            "uniform vec2 direction;\n" +
            "uniform float radius;\n" +
            "uniform float weights[256];\n" +
            "void main() {\n" +
            "    vec2 off = texelSize * direction;\n" +
            "    vec3 col = texture2D(textureIn, gl_TexCoord[0].st).rgb * weights[0];\n" +
            "    for (float f = 1.0; f <= radius; f++) {\n" +
            "        int idx = int(f);\n" +
            "        col += texture2D(textureIn, gl_TexCoord[0].st + f * off).rgb * weights[idx];\n" +
            "        col += texture2D(textureIn, gl_TexCoord[0].st - f * off).rgb * weights[idx];\n" +
            "    }\n" +
            "    gl_FragColor = vec4(col, 1.0);\n" +
            "}";

    // =============================================
    // Lazy Shader Init (with error checking)
    // =============================================

    private static boolean ensureShader() {
        if (blurProgram > 0) return true;
        if (shaderInitFailed) return false;

        try {
            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, VERT_SRC);
            GL20.glCompileShader(vs);
            if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                System.err.println("[GaussianBlur] Vertex shader compile error: " +
                        GL20.glGetShaderInfoLog(vs, 1024));
                shaderInitFailed = true;
                return false;
            }

            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, FRAG_SRC);
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                System.err.println("[GaussianBlur] Fragment shader compile error: " +
                        GL20.glGetShaderInfoLog(fs, 1024));
                shaderInitFailed = true;
                return false;
            }

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glLinkProgram(prog);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.err.println("[GaussianBlur] Program link error: " +
                        GL20.glGetProgramInfoLog(prog, 1024));
                shaderInitFailed = true;
                return false;
            }

            blurProgram = prog;
            System.out.println("[GaussianBlur] Shader compiled OK.");
            return true;
        } catch (Exception e) {
            System.err.println("[GaussianBlur] Shader init exception: " + e.getMessage());
            shaderInitFailed = true;
            return false;
        }
    }

    // =============================================
    // Context Loss Detection
    // =============================================
    
    private static boolean isTextureValid(int textureId) {
        if (textureId <= 0) return false;
        try {
            return GL11.glIsTexture(textureId);
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean isFBOValid(int fboId) {
        if (fboId <= 0) return false;
        try {
            return GL30.glIsFramebuffer(fboId);
        } catch (Exception e) {
            return false;
        }
    }
    
    private static void cleanupResources() {
        // Sadece geçerli ID'leri sil
        if (texture1 > 0 && isTextureValid(texture1)) {
            GL11.glDeleteTextures(texture1);
        }
        if (texture2 > 0 && isTextureValid(texture2)) {
            GL11.glDeleteTextures(texture2);
        }
        if (fbo2 > 0 && isFBOValid(fbo2)) {
            GL30.glDeleteFramebuffers(fbo2);
        }
        texture1 = -1;
        texture2 = -1;
        fbo2 = -1;
        lastWidth = 0;
        lastHeight = 0;
    }

    // =============================================
    // FBO Setup (minimal - no stencil, no depth)
    // =============================================

    private static boolean checkSetupFBO() {
        int w = Display.getWidth();
        int h = ScissorUtil.getActualHeight();
        if (w <= 0 || h <= 0) return false;

        // Context loss detection - eğer kaynaklar geçersizse temizle
        if (texture1 != -1 && !isTextureValid(texture1)) {
            System.out.println("[GaussianBlur] Context loss detected, cleaning up...");
            cleanupResources();
        }

        if (texture1 != -1 && w == lastWidth && h == lastHeight) return true;

        invalidateFrameCache();
        // Cleanup old
        cleanupResources();

        // Texture 1: screen capture target
        texture1 = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, texture1);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // Texture 2: horizontal blur result
        texture2 = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, texture2);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        // FBO 2: intermediate pass target
        fbo2 = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo2);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture2, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[GaussianBlur] FBO not complete! Status: 0x" + Integer.toHexString(status));
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            return false;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        lastWidth = w;
        lastHeight = h;
        System.out.println("[GaussianBlur] FBO created: " + w + "x" + h);
        return true;
    }

    // =============================================
    // Uniform Setup
    // =============================================

    private static final java.nio.FloatBuffer weightBuffer = BufferUtils.createFloatBuffer(256);

    private static void setBlurUniforms(float dirX, float dirY, float radius) {
        int w = Display.getWidth();
        int h = ScissorUtil.getActualHeight();

        GL20.glUniform1i(GL20.glGetUniformLocation(blurProgram, "textureIn"), 0);
        GL20.glUniform2f(GL20.glGetUniformLocation(blurProgram, "texelSize"), 1.0f / w, 1.0f / h);
        GL20.glUniform2f(GL20.glGetUniformLocation(blurProgram, "direction"), dirX, dirY);
        GL20.glUniform1f(GL20.glGetUniformLocation(blurProgram, "radius"), radius);

        ((java.nio.Buffer) weightBuffer).clear();
        float total = 0;
        int r = (int) radius;
        float sigma = radius / 2.0f;
        float[] weights = new float[r + 1];

        for (int i = 0; i <= r; i++) {
            weights[i] = gaussian(i, sigma);
            total += (i == 0) ? weights[i] : weights[i] * 2.0f;
        }
        for (int i = 0; i <= r; i++) {
            weightBuffer.put(weights[i] / total);
        }
        ((java.nio.Buffer) weightBuffer).flip();
        glUniform1(GL20.glGetUniformLocation(blurProgram, "weights"), weightBuffer);
    }

    private static float gaussian(float x, float sigma) {
        return (float) (Math.exp(-(x * x) / (2.0 * sigma * sigma)) / (Math.sqrt(2.0 * Math.PI) * sigma));
    }

    // =============================================
    // Draw Helpers
    // =============================================

    /** Draw a full-screen textured quad (for FBO passes) */
    private static void drawScreenQuad(float x, float y, float w, float h, int sw, int sh) {
        float u1 = x / sw;
        float v1 = (sh - y) / sh;
        float u2 = (x + w) / sw;
        float v2 = (sh - y - h) / sh;

        GL11.glBegin(GL_QUADS);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u1, v2); GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u2, v2); GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u2, v1); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
    }

    /**
     * Draw a TEXTURED rounded rectangle polygon.
     * UV coordinates are computed from screen-space position.
     * This is the magic: rounded clipping comes from the polygon itself!
     */
    private static void drawTexturedRoundedRect(float x, float y, float w, float h,
                                                  float radius, int sw, int sh) {
        radius = Math.min(radius, Math.min(w, h) / 2f);
        if (radius < 0) radius = 0;
        int segments = (int) Math.min(Math.max(radius * 1.2f, 12), 36);

        GL11.glBegin(GL_POLYGON);
        // Top-left corner
        emitCornerVerts(x, y, w, h, radius, segments, 180, 270, sw, sh);
        // Top-right corner
        emitCornerVerts(x, y, w, h, radius, segments, 270, 360, sw, sh);
        // Bottom-right corner
        emitCornerVerts(x, y, w, h, radius, segments, 0, 90, sw, sh);
        // Bottom-left corner
        emitCornerVerts(x, y, w, h, radius, segments, 90, 180, sw, sh);
        GL11.glEnd();
    }

    private static void emitCornerVerts(float x, float y, float w, float h,
                                         float radius, int segments,
                                         int startAngle, int endAngle,
                                         int sw, int sh) {
        float cx = x + (startAngle == 180 || startAngle == 90 ? radius : w - radius);
        float cy = y + (startAngle == 180 || startAngle == 270 ? radius : h - radius);

        for (int i = 0; i <= segments; i++) {
            int angleDeg = startAngle + (endAngle - startAngle) * i / segments;
            int idx = angleDeg % 360;
            if (idx < 0) idx += 360;

            float px = cx + COS[idx] * radius;
            float py = cy + SIN[idx] * radius;

            // Screen-space UV: map pixel position to texture coordinate
            float u = px / sw;
            float v = (sh - py) / sh; // flip Y for GL texture

            GL11.glTexCoord2f(u, v);
            GL11.glVertex2f(px, py);
        }
    }

    // =============================================
    // Core Blur Engine (2-pass Gaussian)
    // =============================================

    /**
     * Performs 2-pass Gaussian blur.
     * After this call, texture2 contains the blurred result.
     *
     * @return true if blur was performed, false on error
     */


    /**
     * Captures the framebuffer once per frame and runs a full-screen horizontal blur into texture2.
     */
    private static boolean ensureFrameHorizontalBlur(float blurRadius) {
        if (blurRadius <= 0)
            return false;

        if (preparedFrameId == renderFrameId
                && Math.abs(blurRadius - preparedRadius) <= RADIUS_CACHE_TOLERANCE)
            return true;

        int screenW = Display.getWidth();
        int screenH = ScissorUtil.getActualHeight();
        if (screenW <= 0 || screenH <= 0)
            return false;
        if (!ensureShader() || !checkSetupFBO())
            return false;

        int lastFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        try {
            GL11.glDisable(GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);

            GL11.glMatrixMode(GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, screenW, screenH, 0, -1, 1);
            GL11.glMatrixMode(GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            GL11.glDisable(GL_STENCIL_TEST);
            GL11.glDisable(GL_DEPTH_TEST);
            GL11.glDisable(GL_ALPHA_TEST);
            GL11.glDisable(GL_BLEND);
            GL11.glDisable(GL_CULL_FACE);
            GL11.glDisable(GL_LIGHTING);
            GL11.glEnable(GL_TEXTURE_2D);

            GL11.glBindTexture(GL_TEXTURE_2D, texture1);
            GL11.glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, screenW, screenH);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo2);
            GL11.glViewport(0, 0, screenW, screenH);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL_COLOR_BUFFER_BIT);

            GL20.glUseProgram(blurProgram);
            setBlurUniforms(1, 0, blurRadius);
            GL11.glBindTexture(GL_TEXTURE_2D, texture1);
            drawScreenQuad(0, 0, screenW, screenH, screenW, screenH);
            GL20.glUseProgram(0);

            GL11.glMatrixMode(GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL_MODELVIEW);
            GL11.glPopMatrix();

            preparedFrameId = renderFrameId;
            preparedRadius = blurRadius;
            return true;
        } catch (Exception e) {
            invalidateFrameCache();
            return false;
        } finally {
            GL11.glPopAttrib();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lastFBO);
        }
    }

    private static boolean performBlur(float radius, float drawX, float drawY, float drawW, float drawH) {
        int w = Display.getWidth();
        int h = ScissorUtil.getActualHeight();

        if (!ensureShader() || !checkSetupFBO()) return false;

        int lastFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        // Use GL_ALL_ATTRIB_BITS to prevent ANY state leak (as requested)
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        // Explicit state cleanup before blur
        GL11.glDisable(GL_SCISSOR_TEST);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);

        GL11.glMatrixMode(GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        // Disable everything that could interfere
        GL11.glDisable(GL_STENCIL_TEST);
        GL11.glDisable(GL_DEPTH_TEST);
        GL11.glDisable(GL_ALPHA_TEST);
        GL11.glDisable(GL_BLEND);
        GL11.glDisable(GL_CULL_FACE); // Important!
        GL11.glDisable(GL_LIGHTING);   // Important!
        GL11.glEnable(GL_TEXTURE_2D);

        // 1. Copy current screen to texture1
        GL11.glBindTexture(GL_TEXTURE_2D, texture1);
        GL11.glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);

        // 2. Horizontal blur: texture1 -> FBO2 (texture2)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo2);
        GL11.glViewport(0, 0, w, h);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL_COLOR_BUFFER_BIT);

        GL20.glUseProgram(blurProgram);
        setBlurUniforms(1, 0, radius);
        GL11.glBindTexture(GL_TEXTURE_2D, texture1);
        drawScreenQuad(drawX, drawY, drawW, drawH, w, h);
        GL20.glUseProgram(0);

        // 3. Vertical blur: texture2 -> back to original FBO
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lastFBO);
        GL11.glViewport(0, 0, w, h);

        // Restore matrices
        GL11.glMatrixMode(GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL_MODELVIEW);
        GL11.glPopMatrix();

        // Restore attribs (scissor, enable, color_buffer, viewport, texture)
        GL11.glPopAttrib();

        return true;
    }

    // =============================================
    // PUBLIC API
    // =============================================

    /**
     * Full-screen blur.
     */
    public static void renderBlur(float radius) {
        int w = Display.getWidth();
        int h = ScissorUtil.getActualHeight();
        renderBlurRegion(0, 0, w, h, radius);
    }

    /**
     * Regional blur (GUI coordinates).
     */
    public static void renderBlur(float x, float y, float width, float height, float radius) {
        renderBlur(x, y, width, height, radius, 1f);
    }

    public static void renderBlur(float x, float y, float width, float height, float radius, float alpha) {
        int scale = ScissorUtil.getScaleFactor();
        renderBlurRegion(x * scale, y * scale, width * scale, height * scale, radius, alpha);
    }

    /**
     * Pixel-perfect ROUNDED blur (GUI coordinates).
     * No Stencil, no Scissor, no SDF.
     * Uses a textured rounded polygon for clipping.
     */
    public static void renderBlurRounded(float x, float y, float width, float height,
                                          float cornerRadius, float blurRadius) {
        if (blurRadius <= 0) return;
        int scale = ScissorUtil.getScaleFactor();
        float sx = x * scale, sy = y * scale;
        float sw = width * scale, sh = height * scale;
        float sr = cornerRadius * scale;

        float pad = blurRadius + 4;
        renderBlurRoundedImpl(blurRadius, sx, sy, sw, sh, sr, pad);
    }

    // =============================================
    // Implementation: Regional Blur
    // =============================================

    private static void renderBlurRegion(float x, float y, float w, float h, float radius) {
        renderBlurRegion(x, y, w, h, radius, 1f);
    }

    private static void renderBlurRegion(float x, float y, float w, float h, float radius, float alpha) {
        int screenW = Display.getWidth();
        int screenH = ScissorUtil.getActualHeight();
        if (screenW <= 0 || screenH <= 0 || radius <= 0 || alpha <= 0.01f)
            return;
        if (!ensureShader() || !checkSetupFBO())
            return;
        if (!ensureFrameHorizontalBlur(radius))
            return;

        int lastFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        try {
            GL11.glDisable(GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);

            GL11.glMatrixMode(GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, screenW, screenH, 0, -1, 1);
            GL11.glMatrixMode(GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            GL11.glDisable(GL_STENCIL_TEST);
            GL11.glDisable(GL_DEPTH_TEST);
            GL11.glDisable(GL_ALPHA_TEST);
            GL11.glEnable(GL_BLEND);
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL_CULL_FACE);
            GL11.glDisable(GL_LIGHTING);
            GL11.glEnable(GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, alpha);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lastFBO);
            GL11.glViewport(0, 0, screenW, screenH);

            GL20.glUseProgram(blurProgram);
            setBlurUniforms(0, 1, radius);
            GL11.glBindTexture(GL_TEXTURE_2D, texture2);
            drawScreenQuad(x, y, w, h, screenW, screenH);
            GL20.glUseProgram(0);

            GL11.glColor4f(1f, 1f, 1f, 1f);

            GL11.glMatrixMode(GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL_MODELVIEW);
            GL11.glPopMatrix();

        } catch (Exception e) {
            System.err.println("[GaussianBlur] renderBlurRegion error: " + e.getMessage());
        } finally {
            GL11.glPopAttrib();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lastFBO);
        }
    }

    // =============================================
    // Implementation: Rounded Blur (GEOMETRY BASED)
    // =============================================

    private static void renderBlurRoundedImpl(float blurRadius,
                                               float rx, float ry, float rw, float rh,
                                               float cornerRad, float pad) {
        int screenW = Display.getWidth();
        int screenH = ScissorUtil.getActualHeight();
        if (screenW <= 0 || screenH <= 0 || blurRadius <= 0) return;
        if (!ensureShader() || !checkSetupFBO()) return;
        if (!ensureFrameHorizontalBlur(blurRadius))
            return;

        int lastFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        try {
            GL11.glDisable(GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);

            GL11.glMatrixMode(GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, screenW, screenH, 0, -1, 1);
            GL11.glMatrixMode(GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            GL11.glDisable(GL_STENCIL_TEST);
            GL11.glDisable(GL_DEPTH_TEST);
            GL11.glDisable(GL_ALPHA_TEST);
            GL11.glDisable(GL_BLEND);
            GL11.glDisable(GL_CULL_FACE);
            GL11.glDisable(GL_LIGHTING);
            GL11.glEnable(GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lastFBO);
            GL11.glViewport(0, 0, screenW, screenH);

            GL20.glUseProgram(blurProgram);
            setBlurUniforms(0, 1, blurRadius);
            GL11.glBindTexture(GL_TEXTURE_2D, texture2);
            drawTexturedRoundedRect(rx, ry, rw, rh, cornerRad, screenW, screenH);
            GL20.glUseProgram(0);

            GL11.glMatrixMode(GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL_MODELVIEW);
            GL11.glPopMatrix();

        } catch (Exception e) {
            System.err.println("[GaussianBlur] renderBlurRounded error: " + e.getMessage());
        } finally {
            GL11.glPopAttrib();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, lastFBO);
        }
    }
}
