package com.weave.lunarmods;

import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

/**
 * A slider over a numeric range. Created as {@code Slider<Integer>}, {@code Slider<Float>} or
 * {@code Slider<Double>} depending on the overload used; values outside the range are clamped.
 */
public final class Slider<N extends Number> extends Setting<N, Slider<N>> {
    private final Kind kind;
    private final N min;
    private final N max;

    Slider(LunarMod mod, String id, String name, Kind kind, N defaultValue, N min, N max) {
        super(mod, id, name, defaultValue);
        if (min.doubleValue() > max.doubleValue()) {
            throw new IllegalArgumentException("Slider '" + id + "' has min > max");
        }
        this.kind = kind;
        this.min = min;
        this.max = max;
    }

    public N min() {
        return min;
    }

    public N max() {
        return max;
    }

    @Override
    Kind kind() {
        return kind;
    }

    @Override
    void describe(OptionSpec spec) {
        spec.defaultValue = normalize(defaultValue());
        spec.min = min;
        spec.max = max;
    }

    @Override
    N normalize(N value) {
        if (value == null) {
            return defaultValue();
        }
        double clamped = Math.max(min.doubleValue(), Math.min(max.doubleValue(), value.doubleValue()));
        return convert(clamped == value.doubleValue() ? value : clamped);
    }

    @Override
    Object toLunar(N value) {
        return value;
    }

    @Override
    N fromLunar(Object value) {
        return convert((Number) value);
    }

    @SuppressWarnings("unchecked")
    private N convert(Number value) {
        switch (kind) {
            case INT_SLIDER:
                return (N) Integer.valueOf(value.intValue());
            case FLOAT_SLIDER:
                return (N) Float.valueOf(value.floatValue());
            default:
                return (N) Double.valueOf(value.doubleValue());
        }
    }
}
