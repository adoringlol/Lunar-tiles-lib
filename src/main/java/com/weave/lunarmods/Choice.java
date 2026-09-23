package com.weave.lunarmods;

import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A dropdown over the constants of an enum. Each constant is shown by its {@code toString()} and
 * saved by its {@code name()}, so labels can change without losing saved choices.
 */
public final class Choice<E extends Enum<E>> extends Setting<E, Choice<E>> {
    private final Class<E> type;

    Choice(LunarMod mod, String id, String name, E defaultValue) {
        super(mod, id, name, defaultValue);
        this.type = defaultValue.getDeclaringClass();
    }

    @Override
    Kind kind() {
        return Kind.DROPDOWN;
    }

    @Override
    void describe(OptionSpec spec) {
        List<String> names = new ArrayList<String>();
        for (E constant : type.getEnumConstants()) {
            names.add(constant.name());
        }
        spec.defaultValue = defaultValue().name();
        spec.choices = names;
        spec.labels = new Function<String, String>() {
            @Override
            public String apply(String name) {
                E constant = parse(name);
                return constant == null ? name : constant.toString();
            }
        };
    }

    @Override
    Object toLunar(E value) {
        return value.name();
    }

    @Override
    E fromLunar(Object value) {
        return value == null ? null : parse(value.toString());
    }

    private E parse(String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
