package me.pewa.util;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import java.awt.Color;

/**
 * FloatingLines — GLSL 1.20 (OpenGL 2.1)
 * %100 Birebir Three.js Portu
 */
public final class FloatingLines {

    // ── Shader Sources ────────────────────────────────────────────────────────

    private static final String VERT_SIMPLE = ""
        + "#version 120\n"
        + "void main() {\n"
        + "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n"
        + "}\n";

    private static final String FRAG = ""
        + "#version 120\n"
        + "\n"
        + "uniform float iTime;\n"
        + "uniform vec3  iResolution;\n"
        + "uniform float animationSpeed;\n"
        + "uniform bool  enableTop;\n"
        + "uniform bool  enableMiddle;\n"
        + "uniform bool  enableBottom;\n"
        + "uniform int   topLineCount;\n"
        + "uniform int   middleLineCount;\n"
        + "uniform int   bottomLineCount;\n"
        + "uniform float topLineDistance;\n"
        + "uniform float middleLineDistance;\n"
        + "uniform float bottomLineDistance;\n"
        + "uniform vec3  topWavePosition;\n"
        + "uniform vec3  middleWavePosition;\n"
        + "uniform vec3  bottomWavePosition;\n"
        + "uniform vec2  iMouse;\n"
        + "uniform bool  interactive;\n"
        + "uniform float bendRadius;\n"
        + "uniform float bendStrength;\n"
        + "uniform float bendInfluence;\n"
        + "uniform bool  parallax;\n"
        + "uniform float parallaxStrength;\n"
        + "uniform vec2  parallaxOffset;\n"
        + "uniform vec3  lineGradient[8];\n"
        + "uniform int   lineGradientCount;\n"
        + "uniform float globalAlpha;\n"
        + "\n"
        + "const vec3 BLACK = vec3(0.0);\n"
        + "const vec3 PINK  = vec3(233.0, 71.0, 245.0) / 255.0;\n"
        + "const vec3 BLUE  = vec3(47.0,  75.0, 162.0) / 255.0;\n"
        + "\n"
        + "mat2 rotate(float r) {\n"
        + "    return mat2(cos(r), sin(r), -sin(r), cos(r));\n"
        + "}\n"
        + "\n"
        + "vec3 background_color(vec2 uv) {\n"
        + "    vec3 col = vec3(0.0);\n"
        + "    float y = sin(uv.x - 0.2) * 0.3 - 0.1;\n"
        + "    float m = uv.y - y;\n"
        + "    col += mix(BLUE, BLACK, smoothstep(0.0, 1.0, abs(m)));\n"
        + "    col += mix(PINK, BLACK, smoothstep(0.0, 1.0, abs(m - 0.8)));\n"
        + "    return col * 0.5;\n"
        + "}\n"
        + "\n"
        // GLSL 1.20: no dynamic indexing on arrays — use a lookup function
        + "vec3 gradLookup(float t) {\n"
        + "    if (lineGradientCount <= 0) return vec3(0.0);\n"
        + "    if (lineGradientCount == 1) return lineGradient[0];\n"
        + "    float scaled = clamp(t, 0.0, 0.9999) * float(lineGradientCount - 1);\n"
        + "    float f = fract(scaled);\n"
        + "    int   i = int(floor(scaled));\n"
        + "    vec3 a = lineGradient[0];\n"
        + "    vec3 b = lineGradient[0];\n"
        + "    if (i == 0) { a = lineGradient[0]; b = lineGradient[1]; }\n"
        + "    else if (i == 1) { a = lineGradient[1]; b = lineGradient[2]; }\n"
        + "    else if (i == 2) { a = lineGradient[2]; b = lineGradient[3]; }\n"
        + "    else if (i == 3) { a = lineGradient[3]; b = lineGradient[4]; }\n"
        + "    else if (i == 4) { a = lineGradient[4]; b = lineGradient[5]; }\n"
        + "    else if (i == 5) { a = lineGradient[5]; b = lineGradient[6]; }\n"
        + "    else             { a = lineGradient[6]; b = lineGradient[7]; }\n"
        + "    return mix(a, b, f) * 0.5;\n"
        + "}\n"
        + "\n"
        + "float wave(vec2 uv, float offset, vec2 screenUv, vec2 mouseUv, bool shouldBend) {\n"
        + "    float time       = iTime * animationSpeed;\n"
        + "    float x_movement = time * 0.1;\n"
        + "    float amp        = sin(offset + time * 0.2) * 0.3;\n"
        + "    float y          = sin(uv.x + offset + x_movement) * amp;\n"
        + "    if (shouldBend) {\n"
        + "        vec2  d         = screenUv - mouseUv;\n"
        + "        float influence = exp(-dot(d, d) * bendRadius);\n"
        + "        float bendOff   = (mouseUv.y - screenUv.y) * influence * bendStrength * bendInfluence;\n"
        + "        y += bendOff;\n"
        + "    }\n"
        + "    float m = uv.y - y;\n"
        + "    return 0.0175 / max(abs(m) + 0.01, 0.001) + 0.01;\n"
        + "}\n"
        + "\n"
        + "void main() {\n"
        + "    vec2 fragCoord = gl_FragCoord.xy;\n"
        + "    vec2 baseUv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;\n"
        + "    baseUv.y *= -1.0;\n"
        + "    if (parallax) baseUv += parallaxOffset;\n"
        + "\n"
        + "    vec3 col = vec3(0.0);\n"
        + "    vec3 b   = (lineGradientCount > 0) ? vec3(0.0) : background_color(baseUv);\n"
        + "\n"
        + "    vec2 mouseUv = vec2(0.0);\n"
        + "    if (interactive) {\n"
        + "        mouseUv = (2.0 * iMouse - iResolution.xy) / iResolution.y;\n"
        + "        mouseUv.y *= -1.0;\n"
        + "    }\n"
        + "\n"
        // Bottom lines (6 unrolled)
        + "    if (enableBottom) {\n"
        + "        float bCount = float(bottomLineCount);\n"
        + "        float angle0 = bottomWavePosition.z * log(length(baseUv) + 1.0);\n"
        + "        vec2  ruv0   = baseUv * rotate(angle0);\n"
        + "        for (int i = 0; i < 6; ++i) {\n"
        + "            if (i >= bottomLineCount) break;\n"
        + "            float fi = float(i);\n"
        + "            float t  = fi / max(bCount - 1.0, 1.0);\n"
        + "            vec3  lc = (lineGradientCount > 0) ? gradLookup(t) : b;\n"
        + "            col += lc * wave(ruv0 + vec2(bottomLineDistance * fi + bottomWavePosition.x, bottomWavePosition.y),\n"
        + "                             1.5 + 0.2 * fi, baseUv, mouseUv, interactive) * 0.2;\n"
        + "        }\n"
        + "    }\n"
        + "\n"
        // Middle lines (6 unrolled)
        + "    if (enableMiddle) {\n"
        + "        float mCount = float(middleLineCount);\n"
        + "        float angle1 = middleWavePosition.z * log(length(baseUv) + 1.0);\n"
        + "        vec2  ruv1   = baseUv * rotate(angle1);\n"
        + "        for (int i = 0; i < 6; ++i) {\n"
        + "            if (i >= middleLineCount) break;\n"
        + "            float fi = float(i);\n"
        + "            float t  = fi / max(mCount - 1.0, 1.0);\n"
        + "            vec3  lc = (lineGradientCount > 0) ? gradLookup(t) : b;\n"
        + "            col += lc * wave(ruv1 + vec2(middleLineDistance * fi + middleWavePosition.x, middleWavePosition.y),\n"
        + "                             2.0 + 0.15 * fi, baseUv, mouseUv, interactive);\n"
        + "        }\n"
        + "    }\n"
        + "\n"
        // Top lines (6 unrolled)
        + "    if (enableTop) {\n"
        + "        float tCount = float(topLineCount);\n"
        + "        float angle2 = topWavePosition.z * log(length(baseUv) + 1.0);\n"
        + "        vec2  ruv2   = baseUv * rotate(angle2);\n"
        + "        ruv2.x *= -1.0;\n"
        + "        for (int i = 0; i < 6; ++i) {\n"
        + "            if (i >= topLineCount) break;\n"
        + "            float fi = float(i);\n"
        + "            float t  = fi / max(tCount - 1.0, 1.0);\n"
        + "            vec3  lc = (lineGradientCount > 0) ? gradLookup(t) : b;\n"
        + "            col += lc * wave(ruv2 + vec2(topLineDistance * fi + topWavePosition.x, topWavePosition.y),\n"
        + "                             1.0 + 0.2 * fi, baseUv, mouseUv, interactive) * 0.1;\n"
        + "        }\n"
        + "    }\n"
        + "\n"
        + "    gl_FragColor = vec4(clamp(col, 0.0, 1.0), globalAlpha);\n"
        + "}\n";

    // ── GL Resources ──────────────────────────────────────────────────────────

    private static int  program        = -1;
    private static boolean initFailed  = false;
    private static long startTime      = System.currentTimeMillis();

    private static float smoothMouseX  = -1000f;
    private static float smoothMouseY  = -1000f;
    private static float bendInfluence = 0f;
    private static float parallaxX     = 0f;
    private static float parallaxY     = 0f;

    private static final float[][] DEFAULT_GRADIENT = {
        { 47f/255f,  75f/255f, 162f/255f },
        { 120f/255f, 60f/255f, 200f/255f },
        { 233f/255f, 71f/255f, 245f/255f },
    };

    private static float[][] dynamicGradient = null;

    private FloatingLines() {}

    public static void render(float alpha) {
        alpha = Math.max(0.0f, Math.min(0.45f, alpha));
        if (alpha < 0.01f) return;
        if (!ensureProgram()) return;

        int screenW = Display.getWidth();
        int screenH = Display.getHeight();
        if (screenW <= 0 || screenH <= 0) return;

        // Mouse & Parallax Lerp (Damping: 0.05)
        float rawMX = Mouse.getX();
        float rawMY = Mouse.getY();
        float damping = 0.05f;

        if (smoothMouseX < -900f) { smoothMouseX = rawMX; smoothMouseY = rawMY; }
        smoothMouseX += (rawMX - smoothMouseX) * damping;
        smoothMouseY += (rawMY - smoothMouseY) * damping;

        float targetInf = (rawMX >= 0 && rawMX <= screenW && rawMY >= 0 && rawMY <= screenH) ? 1.0f : 0.0f;
        bendInfluence += (targetInf - bendInfluence) * damping;

        float pStrength = 0.2f;
        float centerX = screenW / 2.0f;
        float centerY = screenH / 2.0f;
        float targetPX = ((rawMX - centerX) / screenW) * pStrength;
        float targetPY = -((rawMY - centerY) / screenH) * pStrength;
        parallaxX += (targetPX - parallaxX) * damping;
        parallaxY += (targetPY - parallaxY) * damping;

        float iTime = (System.currentTimeMillis() - startTime) / 1000f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, screenW, 0, screenH, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);

        GL20.glUseProgram(program);

        // Uniforms
        setFloat("iTime", iTime);
        setFloat3("iResolution", (float)screenW, (float)screenH, 1.0f);
        setFloat("animationSpeed", 1.0f);
        setBool("enableTop", true);
        setBool("enableMiddle", true);
        setBool("enableBottom", true);
        setInt("topLineCount", 6);
        setInt("middleLineCount", 6);
        setInt("bottomLineCount", 6);
        setFloat("topLineDistance", 0.05f);
        setFloat("middleLineDistance", 0.05f);
        setFloat("bottomLineDistance", 0.05f);
        setFloat3("topWavePosition", 10.0f, 0.5f, -0.4f);
        setFloat3("middleWavePosition", 5.0f, 0.0f, 0.2f);
        setFloat3("bottomWavePosition", 2.0f, -0.7f, 0.4f);
        setFloat2("iMouse", smoothMouseX, smoothMouseY);
        setBool("interactive", true);
        setFloat("bendRadius", 5.0f);
        setFloat("bendStrength", -0.5f);
        setFloat("bendInfluence", bendInfluence);
        setBool("parallax", true);
        setFloat("parallaxStrength", pStrength);
        setFloat2("parallaxOffset", parallaxX, parallaxY);
        setFloat("globalAlpha", alpha);

        float[][] grads = dynamicGradient != null ? dynamicGradient : DEFAULT_GRADIENT;
        setInt("lineGradientCount", grads.length);
        for (int i = 0; i < grads.length; i++) {
            setFloat3("lineGradient[" + i + "]", grads[i][0], grads[i][1], grads[i][2]);
        }

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(screenW, 0);
        GL11.glVertex2f(screenW, screenH);
        GL11.glVertex2f(0, screenH);
        GL11.glEnd();

        GL20.glUseProgram(0);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private static boolean ensureProgram() {
        if (program != -1) return true;
        if (initFailed) return false;
        try {
            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, VERT_SIMPLE);
            GL20.glCompileShader(vs);
            if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == 0) {
                String log = GL20.glGetShaderInfoLog(vs, 2048);
                Logger.warn("[FloatingLines] Vertex shader compile failed: " + log);
                GL20.glDeleteShader(vs);
                initFailed = true;
                return false;
            }
            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, FRAG);
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == 0) {
                String log = GL20.glGetShaderInfoLog(fs, 2048);
                Logger.warn("[FloatingLines] Fragment shader compile failed: " + log);
                GL20.glDeleteShader(vs);
                GL20.glDeleteShader(fs);
                initFailed = true;
                return false;
            }
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(program, 2048);
                Logger.warn("[FloatingLines] Program link failed: " + log);
                GL20.glDeleteProgram(program);
                program = -1;
                GL20.glDeleteShader(vs);
                GL20.glDeleteShader(fs);
                initFailed = true;
                return false;
            }
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            Logger.info("[FloatingLines] Shader compiled and linked OK.");
            return true;
        } catch (Exception e) {
            Logger.warn("[FloatingLines] Shader init exception: " + e.getMessage());
            initFailed = true;
            return false;
        }
    }

    private static void setFloat(String n, float v) { int l = GL20.glGetUniformLocation(program, n); if (l >= 0) GL20.glUniform1f(l, v); }
    private static void setInt(String n, int v) { int l = GL20.glGetUniformLocation(program, n); if (l >= 0) GL20.glUniform1i(l, v); }
    private static void setBool(String n, boolean v) { int l = GL20.glGetUniformLocation(program, n); if (l >= 0) GL20.glUniform1i(l, v ? 1 : 0); }
    private static void setFloat2(String n, float x, float y) { int l = GL20.glGetUniformLocation(program, n); if (l >= 0) GL20.glUniform2f(l, x, y); }
    private static void setFloat3(String n, float x, float y, float z) { int l = GL20.glGetUniformLocation(program, n); if (l >= 0) GL20.glUniform3f(l, x, y, z); }

    public static void setAccentColor(Color c) {
        if (c == null) { dynamicGradient = null; return; }
        float r = c.getRed()/255f, g = c.getGreen()/255f, b = c.getBlue()/255f;

        // Brightness of the color (0=black, 1=white)
        float brightness = Math.max(r, Math.max(g, b));

        // When color is very dark, add a subtle white outline so waves are still visible.
        // We blend toward a dim white gradient as brightness approaches 0.
        float darkBlend = Math.max(0f, 1f - brightness * 4f); // 1.0 at pure black, 0.0 at brightness >= 0.25

        float[] dark1 = { r * 0.4f + 0.04f * darkBlend, g * 0.4f + 0.04f * darkBlend, b * 0.4f + 0.04f * darkBlend };
        float[] mid   = { r       + 0.10f * darkBlend, g       + 0.10f * darkBlend, b       + 0.10f * darkBlend };
        float[] bright= { Math.min(r * 1.2f + 0.18f * darkBlend, 1f),
                          Math.min(g * 1.2f + 0.18f * darkBlend, 1f),
                          Math.min(b * 1.2f + 0.18f * darkBlend, 1f) };

        dynamicGradient = new float[][]{ dark1, mid, bright };
    }

    public static void cleanup() {
        if (program != -1) GL20.glDeleteProgram(program);
        program = -1;
        initFailed = false;
    }
}
