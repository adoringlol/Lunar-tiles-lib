package com.weave.lunarmods;

import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** A dropdown over a fixed list of text choices. Unknown values fall back to the default. */
public final class Dropdown extends Setting<String, Dropdown> {
    private final List<String> choices;

    Dropdown(LunarMod mod, String id, String name, String defaultValue, List<String> choices) {
        super(mod, id, name, defaultValue);
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("Dropdown '" + id + "' needs at least one choice");
        }
        if (!choices.contains(defaultValue)) {
            throw new IllegalArgumentException("Default '" + defaultValue + "' of dropdown '" + id + "' is not one of its choices");
        }
        this.choices = Collections.unmodifiableList(new ArrayList<String>(choices));
    }

    public List<String> choices() {
        return choices;
    }

    /** Position of the current value in {@link #choices()}. */
    public int index() {
        return choices.indexOf(get());
    }

    @Override
    Kind kind() {
        return Kind.DROPDOWN;
    }

    @Override
    void describe(OptionSpec spec) {
        spec.defaultValue = defaultValue();
        spec.choices = choices;
        spec.labels = Function.identity();
    }

    @Override
    String normalize(String value) {
        return value != null && choices.contains(value) ? value : defaultValue();
    }

    @Override
    Object toLunar(String value) {
        return value;
    }

    @Override
    String fromLunar(Object value) {
        return value == null ? null : value.toString();
    }
}
