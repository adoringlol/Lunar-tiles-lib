package com.weave.lunarmods;

import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.lunar.Kind;
import com.weave.lunarmods.internal.lunar.OptionSpec;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A key the player can rebind in the tile's settings, using the same key recorder as Lunar's own
 * mod keybinds (modifier combos like Ctrl+K included).
 *
 * <p>The value is an LWJGL key code ({@link Keyboard#KEY_NONE} when unbound); mouse buttons follow
 * Minecraft's convention of {@code button - 100}.
 */
public final class Keybind extends Setting<Integer, Keybind> {
    private final List<Runnable> pressListeners = new CopyOnWriteArrayList<Runnable>();
    private boolean wasDown;

    Keybind(LunarMod mod, String id, String name, int defaultKey) {
        super(mod, id, name, defaultKey);
    }

    /**
     * Runs {@code action} each time the key is pressed while the tile is enabled and no screen is
     * open (Lunar delivers the press, so modifier combos behave exactly like its own keybinds).
     */
    public Keybind onPress(Runnable action) {
        pressListeners.add(Objects.requireNonNull(action, "action"));
        return this;
    }

    /** Whether the bound key (and its modifiers) is held right now. False when unbound. */
    public boolean isDown() {
        Object lunarOption = option;
        if (lunarOption != null) {
            return bridge.isKeyDown(lunarOption);
        }
        int key = get();
        if (key == Keyboard.KEY_NONE) {
            return false;
        }
        return key < 0 ? Mouse.isButtonDown(key + 100) : Keyboard.isKeyDown(key);
    }

    /** Display name of the bound key, e.g. {@code "K"} or {@code "NONE"}. */
    public String keyName() {
        int key = get();
        if (key < 0) {
            return "MOUSE" + (key + 100 + 1);
        }
        String name = Keyboard.getKeyName(key);
        return name == null ? "NONE" : name;
    }

    @Override
    Kind kind() {
        return Kind.KEYBIND;
    }

    @Override
    void describe(OptionSpec spec) {
        spec.defaultValue = defaultValue();
        spec.action = new Runnable() {
            @Override
            public void run() {
                if (Minecraft.getMinecraft().currentScreen == null) {
                    firePress();
                }
            }
        };
    }

    @Override
    void poll() {
        super.poll();
        if (option != null && bridge.hasPressListener()) {
            return;
        }
        // Fallback when Lunar exposes no press callback (or outside Lunar): detect the press here.
        boolean down = Minecraft.getMinecraft().currentScreen == null && isDown();
        if (down && !wasDown) {
            firePress();
        }
        wasDown = down;
    }

    private void firePress() {
        if (!mod.isEnabled()) {
            return;
        }
        for (Runnable listener : pressListeners) {
            try {
                listener.run();
            } catch (RuntimeException exception) {
                Log.error("Key press handler of '" + id + "' in tile '" + mod.id() + "' failed", exception);
            }
        }
    }

    @Override
    Object toLunar(Integer value) throws Exception {
        return bridge.keyCombo(value);
    }

    @Override
    Integer fromLunar(Object value) throws Exception {
        return bridge.keyCode(value);
    }
}
