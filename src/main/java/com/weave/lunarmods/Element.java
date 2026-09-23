package com.weave.lunarmods;

import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.LunarBridge;
import com.weave.lunarmods.internal.lunar.OptionSpec;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One row in a tile's settings panel.
 *
 * @param <S> the concrete type, so chained calls keep it
 */
public abstract class Element<S extends Element<S>> {
    final LunarMod mod;
    final String id;
    final String name;
    private volatile BooleanSupplier visibleWhen;
    LunarBridge bridge;
    Object option;
    private boolean buildFailed;

    Element(LunarMod mod, String id, String name) {
        this.mod = mod;
        this.id = id;
        this.name = name;
    }

    /** The id the value is saved under; unique within its tile. */
    public final String id() {
        return id;
    }

    /** The label shown in Lunar's settings panel. */
    public final String name() {
        return name;
    }

    /**
     * Shows this row only while {@code condition} is true, e.g. {@code visibleWhen(showTimer::get)}.
     * Lunar re-checks it every frame the panel is open, so keep it cheap.
     */
    public final S visibleWhen(BooleanSupplier condition) {
        this.visibleWhen = condition;
        return self();
    }

    @SuppressWarnings("unchecked")
    final S self() {
        return (S) this;
    }

    abstract Kind kind();

    /** Fills in the kind-specific parts of the Lunar option. */
    abstract void describe(OptionSpec spec);

    /** Builds the Lunar option once; later calls return the same object, as Lunar requires. */
    final Object build(LunarBridge lunar) {
        if (option != null || buildFailed) {
            return option;
        }
        if (!lunar.supports(kind())) {
            buildFailed = true;
            Log.warn("Setting '" + id + "' of tile '" + mod.id() + "' is not shown: this Lunar version has no " + kind() + " control.");
            return null;
        }
        OptionSpec spec = new OptionSpec(kind(), id);
        final String label = name;
        spec.name = new Supplier<String>() {
            @Override
            public String get() {
                return label;
            }
        };
        // Lunar's row condition answers "is this row hidden?", the inverse of visibleWhen.
        spec.hidden = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                BooleanSupplier condition = visibleWhen;
                if (condition == null) {
                    return false;
                }
                try {
                    return !condition.getAsBoolean();
                } catch (RuntimeException exception) {
                    return false;
                }
            }
        };
        try {
            this.bridge = lunar;
            describe(spec);
            option = lunar.buildOption(spec);
            bound();
        } catch (Exception | LinkageError failed) {
            buildFailed = true;
            option = null;
            Log.error("Could not create setting '" + id + "' of tile '" + mod.id() + "'", LunarBridge.unwrap(failed));
        }
        return option;
    }

    /** Called once the Lunar option exists. */
    void bound() throws Exception {
    }

    /** Called every client tick while the tile is registered. */
    void poll() {
    }
}
