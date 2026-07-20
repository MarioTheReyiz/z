package me.pewa.setting;

import me.pewa.module.Module;

public final class TextOption extends OptionBase<String> {
    public TextOption(String name, String defaultValue, Module module) {
        super(name, defaultValue == null ? "" : defaultValue, module);
    }

    public String getText() {
        return getValue();
    }

    public void setText(String text) {
        setValue(text == null ? "" : text);
    }

    @Override
    public void fromConfigValue(Object value) {
        setText(value == null ? "" : String.valueOf(value));
    }
}
