package com.weave.lunarmods;

import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

/** A single-line text field. */
public final class TextInput extends Setting<String, TextInput> {
    TextInput(LunarMod mod, String id, String name, String defaultValue) {
        super(mod, id, name, defaultValue == null ? "" : defaultValue);
    }

    @Override
    Kind kind() {
        return Kind.TEXT;
    }

    @Override
    void describe(OptionSpec spec) {
        spec.defaultValue = defaultValue();
    }

    @Override
    Object toLunar(String value) {
        return value;
    }

    @Override
    String fromLunar(Object value) {
        return value == null ? "" : value.toString();
    }
}
