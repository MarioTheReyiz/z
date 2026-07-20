package me.pewa.setting;

import me.pewa.module.Module;

public abstract class OptionBase<T> {
    private final String name;
    private final T defaultValue;
    private final Module module;
    private String group = "";
    private String dependency = "";
    protected T value;

    protected OptionBase(String name, T defaultValue, Module module) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.module = module;
    }

    public String getName() {
        return name;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public Module getModule() {
        return module;
    }

    public String getGroup() {
        return group;
    }

    public OptionBase<T> setGroup(String group) {
        this.group = group == null ? "" : group;
        return this;
    }

    public String getDependency() {
        return dependency;
    }

    public OptionBase<T> setDependency(String dependency) {
        this.dependency = dependency == null ? "" : dependency;
        return this;
    }

    public Object toConfigValue() {
        return value;
    }

    public abstract void fromConfigValue(Object value);
}
