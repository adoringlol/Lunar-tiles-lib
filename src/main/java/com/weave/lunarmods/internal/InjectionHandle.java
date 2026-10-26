package com.weave.lunarmods.internal;

import com.google.gson.JsonObject;
import com.weave.lunarmods.internal.lunar.LunarBridge;

import java.util.List;

/**
 * What the registry needs from settings added to one of Lunar's own mods. Implemented privately by
 * the public tile handle, like {@link TileHandle}.
 */
public interface InjectionHandle {
    TileDefinition definition();

    /** Builds the Lunar options once; later calls return the same objects. */
    List<Object> options(LunarBridge bridge);

    /**
     * Lunar's mod was found and the options are about to be appended to its settings panel.
     * {@code saved} holds the values from the settings file, or is {@code null} on first use.
     */
    void attached(LunarBridge bridge, Object feature, JsonObject saved);

    /** The current values, keyed by setting id, for the settings file. */
    JsonObject save();

    /** Once per client tick on the main thread, after {@link #attached}. */
    void tick();

    /** The settings will never reach Lunar (not running under Lunar, or Lunar's mod was not found). */
    void standalone();
}
