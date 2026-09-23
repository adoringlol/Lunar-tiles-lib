package com.weave.lunarmods;

import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

/** A section heading that groups the settings below it. */
public final class Header extends Element<Header> {
    Header(LunarMod mod, String id, String label) {
        super(mod, id, label);
    }

    @Override
    Kind kind() {
        return Kind.LABEL;
    }

    @Override
    void describe(OptionSpec spec) {
    }
}
