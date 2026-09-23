package com.weave.lunarmods.internal.lunar;

/** The Lunar option types this library builds. Each maps to one of Lunar's option builders. */
public enum Kind {
    /** Checkbox/toggle switch holding a {@code Boolean}. */
    BOOLEAN,
    INT_SLIDER,
    FLOAT_SLIDER,
    DOUBLE_SLIDER,
    /** Color picker with opacity and chroma controls, holding an ARGB {@code Integer}. */
    COLOR,
    TEXT,
    /** Dropdown over a fixed list of strings. */
    DROPDOWN,
    /** Key combo recorder; the same control Lunar uses for its own mod keybinds. */
    KEYBIND,
    BUTTON,
    /** Section label; Lunar calls these categories. */
    LABEL
}
