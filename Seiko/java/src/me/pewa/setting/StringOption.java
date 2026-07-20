package me.pewa.setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.pewa.module.Module;

public final class StringOption extends OptionBase<String> {
    private final List<String> modes;

    public StringOption(String name, String defaultValue, Module module, String... modes) {
        super(name, defaultValue, module);
        List<String> values = new ArrayList<String>();
        if (modes != null) {
            for (String mode : modes) {
                if (mode != null && !values.contains(mode)) {
                    values.add(mode);
                }
            }
        }
        if (values.isEmpty()) {
            values.add(defaultValue);
        }
        this.modes = Collections.unmodifiableList(values);
        setValue(defaultValue);
    }

    public List<String> getModes() {
        return modes;
    }

    @Override
    public void setValue(String value) {
        if (value == null) {
            return;
        }
        if (modes.contains(value)) {
            super.setValue(value);
        }
    }

    @Override
    public void fromConfigValue(Object value) {
        if (value != null) {
            setValue(String.valueOf(value));
        }
    }
}
