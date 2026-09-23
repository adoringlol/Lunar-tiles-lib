package com.weave.lunarmods.internal;

import com.weave.lunarmods.internal.lunar.LunarBridge;

/** What the registry needs from a claimed tile. Implemented privately by the public tile handle. */
public interface TileHandle {
    TileDefinition definition();

    /**
     * Adds the tile's settings to the list Lunar is collecting. Lunar may call this more than once
     * (whenever it rebuilds a mod's options); the same option objects must be added each time.
     */
    void contributeOptions(LunarBridge bridge, Object registrar);

    /** The tile's Lunar HUD object, or {@code null} for none. Called once, before registration. */
    Object createHud(LunarBridge bridge) throws Exception;

    /** The tile is registered with Lunar; {@code feature} is its Lunar mod object. */
    void attached(LunarBridge bridge, Object feature);

    /** Once per client tick on the main thread, after {@link #attached}. */
    void tick();

    /** The tile will never reach Lunar (not running under Lunar, or registration failed). */
    void standalone();
}
