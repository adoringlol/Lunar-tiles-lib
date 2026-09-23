package com.weave.lunarmods;

import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.lunar.LunarBridge;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A setting with a value. Once the tile is in Lunar's mod menu the value lives in Lunar's option (so
 * the settings panel and {@link #get()} always agree); before that, or outside Lunar, it is kept here.
 *
 * @param <T> value type
 * @param <S> the concrete setting type, so chained calls keep it
 */
public abstract class Setting<T, S extends Setting<T, S>> extends Element<S> {
    private final T defaultValue;
    private final List<Consumer<? super T>> listeners = new CopyOnWriteArrayList<Consumer<? super T>>();
    private volatile T local;
    private T lastSeen;

    Setting(LunarMod mod, String id, String name, T defaultValue) {
        super(mod, id, name);
        this.defaultValue = defaultValue;
        this.local = defaultValue;
        this.lastSeen = defaultValue;
    }

    /** The current value. Cheap enough to call every frame. */
    public T get() {
        Object lunarOption = option;
        if (lunarOption != null) {
            try {
                return normalize(fromLunar(bridge.value(lunarOption)));
            } catch (Exception | LinkageError failed) {
                return local;
            }
        }
        return local;
    }

    /** Changes the value, as if the player had changed it in the settings panel. */
    public S set(T value) {
        T next = normalize(value);
        T previous = get();
        local = next;
        Object lunarOption = option;
        if (lunarOption != null) {
            try {
                bridge.setValue(lunarOption, toLunar(next));
            } catch (Exception | LinkageError failed) {
                Log.error("Could not update setting '" + id + "' of tile '" + mod.id() + "'", LunarBridge.unwrap(failed));
            }
        }
        if (!Objects.equals(previous, next)) {
            lastSeen = next;
            notifyListeners(next);
        }
        return self();
    }

    /** Restores the default value. */
    public S reset() {
        return set(defaultValue);
    }

    public T defaultValue() {
        return defaultValue;
    }

    /**
     * Runs {@code listener} on the client thread whenever the value changes, including changes made
     * in Lunar's settings panel (reported on the next tick) and when saved settings are loaded.
     */
    public S onChange(Consumer<? super T> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return self();
    }

    @Override
    void bound() throws Exception {
        // Keep a value set before registration; the saved config, loaded right after this, still wins
        // and poll() then reports it as a change.
        T current = local;
        if (!Objects.equals(current, defaultValue)) {
            bridge.setValue(option, toLunar(current));
        }
        lastSeen = current;
    }

    @Override
    void poll() {
        T current = get();
        if (!Objects.equals(current, lastSeen)) {
            lastSeen = current;
            local = current;
            notifyListeners(current);
        }
    }

    private void notifyListeners(T value) {
        for (Consumer<? super T> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException exception) {
                Log.error("A change listener of setting '" + id + "' in tile '" + mod.id() + "' failed", exception);
            }
        }
    }

    /** Makes an arbitrary value valid for this setting (clamping, falling back to the default). */
    T normalize(T value) {
        return value == null ? defaultValue : value;
    }

    /** The value in the form Lunar's option stores. */
    abstract Object toLunar(T value) throws Exception;

    /** The value from Lunar's option, converted back. */
    abstract T fromLunar(Object value) throws Exception;
}
