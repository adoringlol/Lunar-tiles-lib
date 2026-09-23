package com.weave.lunarmods;

import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;

/** A clickable button. The action runs on the client thread when the player clicks it. */
public final class Button extends Element<Button> {
    private final Runnable action;

    Button(LunarMod mod, String id, String name, Runnable action) {
        super(mod, id, name);
        this.action = action;
    }

    /** Runs the button's action as if it had been clicked. */
    public void click() {
        try {
            action.run();
        } catch (RuntimeException exception) {
            Log.error("Button '" + id + "' of tile '" + mod.id() + "' failed", exception);
        }
    }

    @Override
    Kind kind() {
        return Kind.BUTTON;
    }

    @Override
    void describe(OptionSpec spec) {
        spec.action = new Runnable() {
            @Override
            public void run() {
                click();
            }
        };
    }
}
