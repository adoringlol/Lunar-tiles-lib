package com.weave.lunarmods;

import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.Manifests;
import com.weave.lunarmods.internal.Registry;
import com.weave.lunarmods.internal.TileDefinition;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Entry point for adding tiles to Lunar Client's mod menu.
 *
 * <p>Declare each tile (name, icon, creators) in an {@code lcmods.json} at the root of your jar,
 * then claim it from your mod's {@code init()} and add its settings:
 *
 * <pre>{@code
 * LunarMod mod = LunarMods.mod("my_tile");
 * Toggle sound = mod.toggle("sound", "Play Sound", true);
 * mod.onToggle(enabled -> ...);
 * }</pre>
 *
 * Only claimed tiles are added to the menu, so a manifest can list tiles a mod enables selectively.
 */
public final class LunarMods {
    private static final Map<String, LunarMod> mods = new ConcurrentHashMap<String, LunarMod>();

    private LunarMods() {
    }

    /**
     * The tile declared as {@code id} in an {@code lcmods.json}. Every call with the same id returns
     * the same object. An id no manifest declares is logged and returns a tile that keeps working
     * with default settings but never appears in the mod menu.
     */
    public static LunarMod mod(String id) {
        String key = id == null ? "" : id.trim();
        LunarMod existing = mods.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (mods) {
            existing = mods.get(key);
            if (existing != null) {
                return existing;
            }
            TileDefinition tile = Manifests.find(key);
            LunarMod mod;
            if (tile == null) {
                Log.warn("No " + Manifests.FILE_NAME + " declares a tile with id '" + key + "'; add it to your jar's "
                        + Manifests.FILE_NAME + " to show it in Lunar's mod menu.");
                mod = new LunarMod(TileDefinition.undeclared(key), false);
            } else {
                mod = new LunarMod(tile, true);
            }
            mods.put(key, mod);
            return mod;
        }
    }

    /**
     * One of Lunar's own mods, by the id Lunar gives it, so you can add settings to it. Media Player
     * is {@code "MEDIA_PLAYER"}; case, spaces, dashes and underscores are ignored when matching.
     * Every call with the same id returns the same object.
     *
     * <p>Settings added here appear at the end of that mod's settings panel, and their values are
     * saved by this library. {@link LunarMod#isEnabled()} and {@link LunarMod#onToggle} follow the
     * Lunar mod's own switch. Lunar's mods keep their own HUD element, so {@code hud} and
     * {@code textHud} are not available. An id Lunar does not have is logged, and its settings keep
     * working with their default values.
     */
    public static LunarMod lunarMod(String lunarId) {
        String key = "lunar:" + (lunarId == null ? "" : lunarId.trim().replaceAll("[\\s_-]+", "").toLowerCase(java.util.Locale.ROOT));
        synchronized (mods) {
            LunarMod existing = mods.get(key);
            if (existing == null) {
                existing = LunarMod.lunarOwned(lunarId == null ? "" : lunarId.trim());
                mods.put(key, existing);
            }
            return existing;
        }
    }

    /** {@link #lunarMod(String)} and configuration in one step. */
    public static LunarMod lunarMod(String lunarId, Consumer<LunarMod> setup) {
        LunarMod mod = lunarMod(lunarId);
        setup.accept(mod);
        return mod;
    }

    /** Claims a tile and configures it in one step: {@code LunarMods.mod("my_tile", mod -> { ... })}. */
    public static LunarMod mod(String id, Consumer<LunarMod> setup) {
        LunarMod mod = mod(id);
        setup.accept(mod);
        return mod;
    }

    /** Whether the tiles have been added to Lunar's mod menu (false outside Lunar or before the first tick). */
    public static boolean isInLunarMenu() {
        return Registry.isRunning();
    }
}
