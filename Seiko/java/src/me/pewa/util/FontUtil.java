package me.pewa.util;

import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;

import me.pewa.mapping.MinecraftMapper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public final class FontUtil {
    public static final int DEFAULT_HEIGHT = 16;

    private static final String[] FONT_PATHS = {
            "C:\\pewa\\fonts\\wachenfont.ttf",
            "C:\\pewa\\fonts\\ammminoglufont.ttf"
    };
    private static final int FONT_SIZE = 18;
    private static final int FIRST_CHAR = 32;
    private static final int LAST_CHAR = 1024;
    private static final int GLYPH_PADDING = 4;

    private static final Glyph[] GLYPHS = new Glyph[LAST_CHAR + 1];
    private static volatile Method drawStringMethod;
    private static volatile Method stringWidthMethod;
    private static boolean customFontAttempted;
    private static boolean customFontReady;
    private static int textureId = -1;
    private static int atlasWidth;
    private static int atlasHeight;
    private static int lineHeight = DEFAULT_HEIGHT;
    private static float heightScale = 1.0F;

    private FontUtil() {
    }

    public static boolean isReady() {
        return ensureCustomFont() || (getFontRenderer() != null && getDrawStringMethod() != null);
    }

    public static boolean drawString(String text, float x, float y, int color) {
        return drawString(text, x, y, color, false, 1.0F);
    }

    public static boolean drawStringWithShadow(String text, float x, float y, int color) {
        return drawString(text, x, y, color, true, 1.0F);
    }

    public static boolean drawString(String text, float x, float y, int color, boolean shadow) {
        return drawString(text, x, y, color, shadow, 1.0F);
    }

    public static boolean drawString(String text, float x, float y, int color, boolean shadow, float scale) {
        if (text == null || text.length() == 0 || alpha(color) <= 0 || scale <= 0.0F) {
            return false;
        }
        if (ensureCustomFont()) {
            if (shadow) {
                drawCustomString(text, x + scale, y + scale, shadowColor(color), scale);
            }
            drawCustomString(text, x, y, color, scale);
            return true;
        }
        return drawMinecraftString(text, x, y, color, shadow, scale);
    }

    public static boolean drawCenteredString(String text, float centerX, float y, int color, boolean shadow,
            float scale) {
        return drawString(text, centerX - getStringWidth(text) * scale / 2.0F, y, color, shadow, scale);
    }

    public static int getStringWidth(String text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        if (ensureCustomFont()) {
            float width = 0.0F;
            for (int i = 0; i < text.length(); i++) {
                Glyph glyph = glyph(text.charAt(i));
                width += glyph == null ? DEFAULT_HEIGHT / heightScale : glyph.advance;
            }
            return Math.round(width * heightScale);
        }
        return getMinecraftStringWidth(text);
    }

    public static int getFontHeight() {
        return DEFAULT_HEIGHT;
    }

    public static String trimToWidth(String text, int maxWidth, float scale) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0 || scale <= 0.0F) {
            return "";
        }
        if (getStringWidth(text) * scale <= maxWidth) {
            return text;
        }

        String suffix = "...";
        String trimmed = text;
        while (trimmed.length() > 0 && getStringWidth(trimmed + suffix) * scale > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.length() == 0 ? suffix : trimmed + suffix;
    }

    private static boolean ensureCustomFont() {
        if (customFontReady && textureId > 0 && GL11.glIsTexture(textureId)) {
            return true;
        }
        if (customFontAttempted && !customFontReady) {
            return false;
        }
        customFontAttempted = true;

        try {
            File file = findFontFile();
            if (file == null) {
                Logger.warn("FontUtil custom font not found in C:\\pewa\\fonts");
                return false;
            }

            Font font = Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(Font.PLAIN, (float) FONT_SIZE);
            BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D measureGraphics = measureImage.createGraphics();
            setupGraphics(measureGraphics);
            measureGraphics.setFont(font);
            FontMetrics metrics = measureGraphics.getFontMetrics();
            lineHeight = Math.max(DEFAULT_HEIGHT, metrics.getHeight());
            heightScale = (float) DEFAULT_HEIGHT / (float) lineHeight;

            atlasWidth = 1024;
            int rowHeight = lineHeight + GLYPH_PADDING * 2;
            atlasHeight = nextPowerOfTwo(calculateAtlasHeight(metrics, rowHeight));
            BufferedImage atlas = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = atlas.createGraphics();
            setupGraphics(graphics);
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(0, 0, atlasWidth, atlasHeight);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setFont(font);
            graphics.setColor(java.awt.Color.WHITE);

            int x = GLYPH_PADDING;
            int y = GLYPH_PADDING;
            for (int c = FIRST_CHAR; c <= LAST_CHAR; c++) {
                int advance = Math.max(1, metrics.charWidth((char) c));
                int glyphWidth = advance + GLYPH_PADDING * 2;
                if (x + glyphWidth >= atlasWidth) {
                    x = GLYPH_PADDING;
                    y += rowHeight;
                }
                if (y + rowHeight >= atlasHeight) {
                    break;
                }

                graphics.drawString(String.valueOf((char) c), x + GLYPH_PADDING, y + GLYPH_PADDING + metrics.getAscent());
                GLYPHS[c] = new Glyph(x, y, glyphWidth, rowHeight, advance);
                x += glyphWidth;
            }

            measureGraphics.dispose();
            graphics.dispose();
            uploadTexture(atlas);
            customFontReady = true;
            Logger.info("FontUtil loaded custom font: " + file.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Logger.warn("FontUtil custom font failed: " + t.getMessage());
            customFontReady = false;
            return false;
        }
    }

    private static File findFontFile() {
        for (String path : FONT_PATHS) {
            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static void drawCustomString(String text, float x, float y, int color, float scale) {
        float renderScale = scale * heightScale;
        float cursor = 0.0F;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0.0F);
            GL11.glScalef(renderScale, renderScale, 1.0F);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            setColor(color);

            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < text.length(); i++) {
                Glyph glyph = glyph(text.charAt(i));
                if (glyph == null) {
                    cursor += DEFAULT_HEIGHT / heightScale;
                    continue;
                }
                float x1 = cursor - GLYPH_PADDING;
                float y1 = -GLYPH_PADDING;
                float x2 = x1 + glyph.width;
                float y2 = y1 + glyph.height;
                float u1 = (glyph.x + 0.5F) / (float) atlasWidth;
                float v1 = (glyph.y + 0.5F) / (float) atlasHeight;
                float u2 = (glyph.x + glyph.width - 0.5F) / (float) atlasWidth;
                float v2 = (glyph.y + glyph.height - 0.5F) / (float) atlasHeight;

                GL11.glTexCoord2f(u1, v1);
                GL11.glVertex2f(x1, y1);
                GL11.glTexCoord2f(u1, v2);
                GL11.glVertex2f(x1, y2);
                GL11.glTexCoord2f(u2, v2);
                GL11.glVertex2f(x2, y2);
                GL11.glTexCoord2f(u2, v1);
                GL11.glVertex2f(x2, y1);
                cursor += glyph.advance;
            }
            GL11.glEnd();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static boolean drawMinecraftString(String text, float x, float y, int color, boolean shadow, float scale) {
        try {
            Object fontRenderer = getFontRenderer();
            Method drawString = getDrawStringMethod();
            if (fontRenderer == null || drawString == null) {
                return false;
            }

            GL11.glPushMatrix();
            try {
                GL11.glScalef(scale, scale, 1.0F);
                drawString.invoke(fontRenderer, text, x / scale, y / scale, color, shadow);
            } finally {
                GL11.glPopMatrix();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int getMinecraftStringWidth(String text) {
        try {
            Object fontRenderer = getFontRenderer();
            Method method = getStringWidthMethod(fontRenderer);
            if (fontRenderer != null && method != null) {
                Object result = method.invoke(fontRenderer, text);
                if (result instanceof Number) {
                    return ((Number) result).intValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return text.length() * 6;
    }

    private static Object getFontRenderer() {
        return MinecraftMapper.getFontRenderer();
    }

    private static Method getDrawStringMethod() {
        Method cached = drawStringMethod;
        if (cached != null) {
            return cached;
        }
        Method mapped = MappingUtils.getMethod("FontRenderer.drawString");
        if (mapped == null) {
            return null;
        }
        mapped.setAccessible(true);
        drawStringMethod = mapped;
        return mapped;
    }

    private static Method getStringWidthMethod(Object fontRenderer) {
        Method cached = stringWidthMethod;
        if (cached != null) {
            return cached;
        }
        if (fontRenderer == null) {
            return null;
        }
        Method mapped = MappingUtils.getMethod("FontRenderer.getStringWidth");
        if (mapped != null) {
            mapped.setAccessible(true);
            stringWidthMethod = mapped;
            return mapped;
        }
        Method[] methods = fontRenderer.getClass().getDeclaredMethods();
        for (Method method : methods) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == String.class && method.getReturnType() == int.class) {
                method.setAccessible(true);
                stringWidthMethod = method;
                return method;
            }
        }
        return null;
    }

    private static Glyph glyph(char value) {
        if (value >= FIRST_CHAR && value <= LAST_CHAR) {
            return GLYPHS[value];
        }
        return GLYPHS['?'];
    }

    private static void uploadTexture(BufferedImage image) {
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
        ByteBuffer buffer = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);
        for (int pixel : pixels) {
            buffer.put((byte) ((pixel >> 16) & 255));
            buffer.put((byte) ((pixel >> 8) & 255));
            buffer.put((byte) (pixel & 255));
            buffer.put((byte) ((pixel >> 24) & 255));
        }
        ((Buffer) buffer).flip();

        textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, image.getWidth(), image.getHeight(), 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void setupGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    private static int calculateAtlasHeight(FontMetrics metrics, int rowHeight) {
        int x = GLYPH_PADDING;
        int y = GLYPH_PADDING;
        for (int c = FIRST_CHAR; c <= LAST_CHAR; c++) {
            int advance = Math.max(1, metrics.charWidth((char) c));
            int glyphWidth = advance + GLYPH_PADDING * 2;
            if (x + glyphWidth >= atlasWidth) {
                x = GLYPH_PADDING;
                y += rowHeight;
            }
            x += glyphWidth;
        }
        return y + rowHeight + GLYPH_PADDING;
    }

    private static void setColor(int color) {
        float a = (float) alpha(color) / 255.0F;
        float r = (float) ((color >> 16) & 255) / 255.0F;
        float g = (float) ((color >> 8) & 255) / 255.0F;
        float b = (float) (color & 255) / 255.0F;
        GL11.glColor4f(r, g, b, a);
    }

    private static int shadowColor(int color) {
        int a = alpha(color);
        return a << 24;
    }

    private static int alpha(int color) {
        return color >>> 24 & 255;
    }

    private static final class Glyph {
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int advance;

        private Glyph(int x, int y, int width, int height, int advance) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.advance = advance;
        }
    }
}
