package com.weave.lunarmods;

import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.Registry;
import com.weave.lunarmods.internal.TileDefinition;
import com.weave.lunarmods.internal.TileHandle;
import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.LunarBridge;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A tile in Lunar's mod menu, declared in your jar's {@code lcmods.json} and claimed with
 * {@link LunarMods#mod(String)}.
 *
 * <p>Add settings from your mod's {@code init()}; they appear in the tile's settings panel in the
 * order they were added, below Lunar's own enable switch, and above the toggle keybind Lunar adds to
 * every mod. All values are saved in the Weave folder.
 */
public final class LunarMod {
    private final TileDefinition tile;
    private final List<Element<?>> elements = new CopyOnWriteArrayList<Element<?>>();
    private final Set<String> ids = new HashSet<String>();
    private final List<Consumer<Boolean>> toggleListeners = new CopyOnWriteArrayList<Consumer<Boolean>>();
    private volatile LunarBridge bridge;
    private volatile Object feature;
    private volatile Hud hud;
    private boolean lastEnabled;
    private boolean started;
    private int headers;

    LunarMod(TileDefinition tile, boolean declared) {
        this.tile = tile;
        this.lastEnabled = tile.enabledByDefault;
        if (declared) {
            Registry.claim(new Handle());
        } else {
            Registry.runWithoutLunar(new Handle());
        }
    }

    /** The id from {@code lcmods.json}. */
    public String id() {
        return tile.id;
    }

    /** The tile's display name from {@code lcmods.json}. */
    public String name() {
        return tile.name;
    }

    /**
     * Whether the player has the tile switched on. Before Lunar has registered the tile (or outside
     * Lunar) this is the manifest's {@code enabledByDefault}.
     */
    public boolean isEnabled() {
        Object lunarFeature = feature;
        return lunarFeature != null ? bridge.isEnabled(lunarFeature) : tile.enabledByDefault;
    }

    /**
     * Runs {@code listener} with the new state whenever the tile is switched on or off (from the
     * mod menu or its toggle keybind). It also runs once with {@code true} when the tile starts
     * enabled, so a mod can do all its setup here.
     */
    public LunarMod onToggle(Consumer<Boolean> listener) {
        toggleListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public LunarMod onEnable(final Runnable action) {
        return onToggle(enabled -> {
            if (enabled) {
                action.run();
            }
        });
    }

    public LunarMod onDisable(final Runnable action) {
        return onToggle(enabled -> {
            if (!enabled) {
                action.run();
            }
        });
    }

    // ---- settings -----------------------------------------------------------------------------

    /** An on/off switch. */
    public Toggle toggle(String id, String name, boolean defaultValue) {
        return add(new Toggle(this, id, name, defaultValue));
    }

    /** A whole-number slider. */
    public Slider<Integer> slider(String id, String name, int defaultValue, int min, int max) {
        return add(new Slider<Integer>(this, id, name, Kind.INT_SLIDER, defaultValue, min, max));
    }

    /** A decimal slider. */
    public Slider<Float> slider(String id, String name, float defaultValue, float min, float max) {
        return add(new Slider<Float>(this, id, name, Kind.FLOAT_SLIDER, defaultValue, min, max));
    }

    /** A decimal slider with double precision. */
    public Slider<Double> slider(String id, String name, double defaultValue, double min, double max) {
        return add(new Slider<Double>(this, id, name, Kind.DOUBLE_SLIDER, defaultValue, min, max));
    }

    /** A color picker; {@code argb} is {@code 0xAARRGGBB} (use {@code 0xFF......} for opaque). */
    public ColorPicker color(String id, String name, int argb) {
        return add(new ColorPicker(this, id, name, argb));
    }

    /** A single-line text field. */
    public TextInput text(String id, String name, String defaultValue) {
        return add(new TextInput(this, id, name, defaultValue));
    }

    /** A dropdown of fixed text choices; {@code defaultValue} must be one of them. */
    public Dropdown dropdown(String id, String name, String defaultValue, String... choices) {
        return add(new Dropdown(this, id, name, defaultValue, Arrays.asList(choices)));
    }

    /** A dropdown of every constant of {@code defaultValue}'s enum, labeled by {@code toString()}. */
    public <E extends Enum<E>> Choice<E> choice(String id, String name, E defaultValue) {
        return add(new Choice<E>(this, id, name, Objects.requireNonNull(defaultValue, "defaultValue")));
    }

    /** A rebindable key; pass {@link Keyboard#KEY_NONE} to start unbound. */
    public Keybind keybind(String id, String name, int defaultKey) {
        return add(new Keybind(this, id, name, defaultKey));
    }

    /** A button that runs {@code action} when clicked. */
    public Button button(String id, String name, Runnable action) {
        return add(new Button(this, id, name, Objects.requireNonNull(action, "action")));
    }

    /** A section heading above the settings added after it. */
    public Header header(String label) {
        synchronized (this) {
            headers++;
            return add(new Header(this, "header" + headers, label));
        }
    }

    // ---- HUD element --------------------------------------------------------------------------

    /**
     * Gives the tile a custom-drawn element in Lunar's HUD editor. A tile has at most one HUD
     * element, and it is tied to the tile's on/off switch; see {@link Hud}.
     */
    public Hud hud(HudRenderer renderer) {
        return setHud(new Hud(this, Objects.requireNonNull(renderer, "renderer"), null));
    }

    /**
     * Gives the tile a text element in Lunar's HUD editor, styled like Lunar's own FPS and CPS
     * displays: Lunar adds its text color, shadow, brackets, background and border settings to the
     * tile. A tile has at most one HUD element, and it is tied to the tile's on/off switch; see
     * {@link Hud}.
     */
    public Hud textHud(HudText text) {
        return setHud(new Hud(this, null, Objects.requireNonNull(text, "text")));
    }

    /** This tile's HUD element, or {@code null} if it has none. */
    public Hud hudElement() {
        return hud;
    }

    private synchronized Hud setHud(Hud created) {
        if (hud != null) {
            throw new IllegalStateException("Tile '" + tile.id + "' already has a HUD element; a tile has one, shown while the tile is on");
        }
        if (feature != null) {
            Log.warn("The HUD element of tile '" + tile.id + "' was added after the tile was registered; "
                    + "add it from your mod's init() so it appears in the HUD editor.");
        }
        hud = created;
        return created;
    }

    /** Every setting and row of this tile, in panel order. */
    public List<Element<?>> elements() {
        return new ArrayList<Element<?>>(elements);
    }

    private synchronized <E extends Element<?>> E add(E element) {
        String id = element.id;
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Setting ids of tile '" + tile.id + "' must not be empty");
        }
        if (!ids.add(id)) {
            throw new IllegalArgumentException("Tile '" + tile.id + "' already has a setting with id '" + id + "'");
        }
        if (feature != null) {
            Log.warn("Setting '" + id + "' was added to tile '" + tile.id + "' after it was registered; "
                    + "add settings from your mod's init() so they appear in the settings panel.");
        }
        elements.add(element);
        return element;
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    private void pollToggle() {
        boolean enabled = isEnabled();
        if (!started) {
            started = true;
            lastEnabled = enabled;
            if (enabled) {
                fireToggle(true);
            }
        } else if (enabled != lastEnabled) {
            lastEnabled = enabled;
            fireToggle(enabled);
        }
    }

    private void fireToggle(boolean enabled) {
        for (Consumer<Boolean> listener : toggleListeners) {
            try {
                listener.accept(enabled);
            } catch (RuntimeException exception) {
                Log.error("Toggle listener of tile '" + tile.id + "' failed", exception);
            }
        }
    }

    /** Bridges the registry to this tile without exposing these hooks on the public class. */
    private final class Handle implements TileHandle {
        @Override
        public TileDefinition definition() {
            return tile;
        }

        @Override
        public void contributeOptions(LunarBridge lunar, Object registrar) {
            for (Element<?> element : elements) {
                Object option = element.build(lunar);
                if (option == null) {
                    continue;
                }
                try {
                    lunar.addOption(registrar, option);
                } catch (Exception | LinkageError failed) {
                    Log.error("Could not add setting '" + element.id + "' to tile '" + tile.id + "'", LunarBridge.unwrap(failed));
                }
            }
        }

        @Override
        public Object createHud(LunarBridge lunar) throws Exception {
            Hud current = hud;
            return current == null ? null : current.create(lunar);
        }

        @Override
        public void attached(LunarBridge lunar, Object lunarFeature) {
            bridge = lunar;
            feature = lunarFeature;
        }

        @Override
        public void tick() {
            for (Element<?> element : elements) {
                try {
                    element.poll();
                } catch (RuntimeException exception) {
                    Log.error("Setting '" + element.id + "' of tile '" + tile.id + "' failed", exception);
                }
            }
            pollToggle();
        }

        @Override
        public void standalone() {
        }
    }
}
