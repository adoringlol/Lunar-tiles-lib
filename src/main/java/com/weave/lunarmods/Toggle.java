package com.weave.lunarmods;

import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

/** An on/off switch. */
public final class Toggle extends Setting<Boolean, Toggle> {
    Toggle(LunarMod mod, String id, String name, boolean defaultValue) {
        super(mod, id, name, defaultValue);
    }

    /** Shorthand for {@code get()} without unboxing noise. */
    public boolean isOn() {
        return get();
    }

    @Override
    Kind kind() {
        return Kind.BOOLEAN;
    }

    @Override
    void describe(OptionSpec spec) {
        spec.defaultValue = defaultValue();
    }

    @Override
    Object toLunar(Boolean value) {
        return value;
    }

    @Override
    Boolean fromLunar(Object value) {
        return (Boolean) value;
    }
}
