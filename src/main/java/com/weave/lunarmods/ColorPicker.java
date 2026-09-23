package com.weave.lunarmods;

import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

/**
 * Lunar's color picker, including its opacity slider and chroma options. The value is packed
 * {@code 0xAARRGGBB}, ready for Minecraft's font and GL color helpers.
 */
public final class ColorPicker extends Setting<Integer, ColorPicker> {
    ColorPicker(LunarMod mod, String id, String name, int argb) {
        super(mod, id, name, argb);
    }

    /** The color as {@code 0xAARRGGBB}. */
    public int argb() {
        return get();
    }

    public int alpha() {
        return argb() >>> 24;
    }

    public int red() {
        return (argb() >> 16) & 0xFF;
    }

    public int green() {
        return (argb() >> 8) & 0xFF;
    }

    public int blue() {
        return argb() & 0xFF;
    }

    @Override
    Kind kind() {
        return Kind.COLOR;
    }

    @Override
    void describe(OptionSpec spec) {
        spec.defaultValue = defaultValue();
    }

    @Override
    Object toLunar(Integer value) {
        return value;
    }

    @Override
    Integer fromLunar(Object value) {
        return (Integer) value;
    }
}
