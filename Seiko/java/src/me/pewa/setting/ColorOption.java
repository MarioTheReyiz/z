package me.pewa.setting;

import java.awt.Color;
import java.util.Map;
import me.pewa.module.Module;

public final class ColorOption extends OptionBase<Color> {
    public ColorOption(String name, Color defaultValue, Module module) {
        super(name, defaultValue == null ? Color.WHITE : defaultValue, module);
    }

    public float[] getHSB() {
        Color color = getValue();
        return Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
    }

    public void setHSB(float hue, float saturation, float brightness, float alpha) {
        int rgb = Color.HSBtoRGB(clamp(hue), clamp(saturation), clamp(brightness));
        Color rgbColor = new Color(rgb);
        setValue(new Color(rgbColor.getRed(), rgbColor.getGreen(), rgbColor.getBlue(), Math.round(clamp(alpha) * 255.0F)));
    }

    @Override
    public Object toConfigValue() {
        Color color = getValue();
        return color.getRGB();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void fromConfigValue(Object value) {
        if (value instanceof Number) {
            setValue(new Color(((Number) value).intValue(), true));
        } else if (value instanceof Map) {
            Map map = (Map) value;
            int r = number(map.get("r"), getValue().getRed());
            int g = number(map.get("g"), getValue().getGreen());
            int b = number(map.get("b"), getValue().getBlue());
            int a = number(map.get("a"), getValue().getAlpha());
            setValue(new Color(clampInt(r), clampInt(g), clampInt(b), clampInt(a)));
        } else if (value != null) {
            try {
                setValue(new Color((int) Long.parseLong(String.valueOf(value)), true));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int clampInt(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
