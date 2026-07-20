package me.pewa.setting;

import me.pewa.module.Module;

public final class BooleanOption extends OptionBase<Boolean> {
    public BooleanOption(String name, boolean defaultValue, Module module) {
        super(name, defaultValue, module);
    }

    public void toggle() {
        setValue(!getValue());
    }

    @Override
    public void fromConfigValue(Object value) {
        if (value instanceof Boolean) {
            setValue((Boolean) value);
        } else if (value instanceof String) {
            setValue(Boolean.parseBoolean((String) value));
        }
    }
}
