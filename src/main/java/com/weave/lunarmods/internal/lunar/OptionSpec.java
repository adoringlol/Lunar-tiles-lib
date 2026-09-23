package com.weave.lunarmods.internal.lunar;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Everything needed to build one Lunar option. Values use Lunar's types (see {@link LunarBridge}). */
public final class OptionSpec {
    public final Kind kind;
    public final String id;
    public Supplier<String> name;
    /** True while the row should be hidden; Lunar re-checks it every frame the panel is open. */
    public BooleanSupplier hidden;
    /** Default value; for {@link Kind#KEYBIND} an LWJGL key code (mouse buttons are {@code button - 100}). */
    public Object defaultValue;
    public Number min;
    public Number max;
    public List<String> choices;
    public Function<String, String> labels;
    /** Button action, or key press callback for keybinds. */
    public Runnable action;

    public OptionSpec(Kind kind, String id) {
        this.kind = kind;
        this.id = id;
    }
}
