package com.weave.lunarmods.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.weave.lunarmods.internal.lunar.LunarBridge;
import com.weave.lunarmods.internal.lunar.LunarNotReadyException;
import com.weave.lunarmods.internal.lunar.LunarRuntime;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Registers claimed tiles with Lunar and keeps their settings saved.
 *
 * <p>Everything runs on the client thread from the tick hook, except the one-time scan of Lunar's
 * classes after a Lunar update, which runs on a background thread so the game never stalls on it.
 */
public final class Registry {
    private static final int SAVE_INTERVAL_TICKS = 100;
    /** Lunar normally finishes starting before the first tick; this covers slow machines. */
    private static final int MAX_ATTEMPTS = 10;
    private static final int RETRY_DELAY_TICKS = 40;

    private enum State { WAITING, DISCOVERING, RUNNING, OFF }

    private static final Map<String, TileHandle> claimed = new LinkedHashMap<String, TileHandle>();
    /** Tiles that tick: those registered with Lunar plus those running without it. */
    private static final List<TileHandle> active = new ArrayList<TileHandle>();
    private static final Map<String, Object> features = new LinkedHashMap<String, Object>();

    private static State state = State.WAITING;
    private static FutureTask<LunarBridge> discovery;
    private static LunarBridge bridge;
    /** Everything in the settings file, including tiles of mods that are not installed right now. */
    private static JsonObject stored;
    private static String lastWritten;
    private static int ticksUntilSave = SAVE_INTERVAL_TICKS;
    private static int attempts;
    private static int retryIn;

    private Registry() {
    }

    /** Called when a mod first asks for a tile. Registration happens on the next client tick. */
    public static synchronized void claim(TileHandle handle) {
        String id = handle.definition().lunarId;
        if (claimed.containsKey(id)) {
            return;
        }
        claimed.put(id, handle);
        if (state == State.OFF) {
            standalone(handle);
        } else if (state == State.RUNNING) {
            Log.warn("Tile '" + handle.definition().id + "' was claimed after Lunar's mod list was built; "
                    + "claim tiles from your mod's init() so they appear in the mod menu.");
        }
    }

    public static synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    public static void tick() {
        TileHandle[] ticking;
        synchronized (Registry.class) {
            if (state == State.WAITING && !claimed.isEmpty() && --retryIn <= 0) {
                start();
            } else if (state == State.DISCOVERING && discovery.isDone()) {
                finishDiscovery();
            }
            if (active.isEmpty()) {
                return;
            }
            ticking = active.toArray(new TileHandle[0]);
        }
        for (TileHandle handle : ticking) {
            try {
                handle.tick();
            } catch (RuntimeException exception) {
                Log.error("Tile '" + handle.definition().id + "' failed while ticking", exception);
            }
        }
        if (--ticksUntilSave <= 0) {
            ticksUntilSave = SAVE_INTERVAL_TICKS;
            save();
        }
    }

    /** Ticks a tile that is not declared in any manifest, so its listeners still work locally. */
    public static synchronized void runWithoutLunar(TileHandle handle) {
        standalone(handle);
    }

    public static void shutdown() {
        save();
    }

    private static void start() {
        // The game's context loader sees Lunar's classes and build data.
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        final ClassLoader loader = context != null ? context : Minecraft.class.getClassLoader();
        if (!LunarRuntime.isRunning(loader)) {
            Log.info("Lunar Client was not detected; tiles keep their default settings and no mod-menu entries are added.");
            off();
            return;
        }
        if (!LunarRuntime.canListClasses()) {
            Log.warn("Weave did not pass Instrumentation to preInit, so Lunar's classes cannot be found; tiles are unavailable.");
            off();
            return;
        }
        attempts++;
        LunarBridge cached;
        try {
            cached = LunarBridge.fromCache(loader);
        } catch (LunarNotReadyException notReady) {
            retryLater(notReady);
            return;
        }
        if (cached != null) {
            attachAll(cached);
            return;
        }
        Log.info("Lunar changed since the last launch (or first run); looking up its internals in the background.");
        discovery = new FutureTask<LunarBridge>(new Callable<LunarBridge>() {
            @Override
            public LunarBridge call() throws Exception {
                return LunarBridge.scan(loader);
            }
        });
        Thread thread = new Thread(discovery, "Lunar Mod API discovery");
        thread.setDaemon(true);
        thread.start();
        state = State.DISCOVERING;
    }

    private static void finishDiscovery() {
        try {
            attachAll(discovery.get());
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof LunarNotReadyException) {
                retryLater(failed.getCause());
                return;
            }
            Log.error("Could not find Lunar's mod system in this Lunar version; tiles are unavailable",
                    LunarBridge.unwrap(failed.getCause()));
            off();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            off();
        } finally {
            discovery = null;
        }
    }

    private static void attachAll(LunarBridge resolved) {
        bridge = resolved;
        stored = Storage.read();
        for (TileHandle handle : claimed.values()) {
            attach(handle);
        }
        bridge.refresh();
        state = State.RUNNING;
        lastWritten = snapshot();
        Log.info("Added " + features.size() + " tile(s) to Lunar's mod menu; settings are saved to " + Storage.file());
    }

    private static void attach(final TileHandle handle) {
        TileDefinition tile = handle.definition();
        try {
            Object[] icons = Icons.register(bridge, tile);
            Object feature = bridge.createTile(tile, icons, (lunarFeature, registrar) -> handle.contributeOptions(bridge, registrar));
            attachHud(handle, feature);
            JsonElement saved = stored.get(tile.lunarId);
            bridge.register(feature, saved != null && saved.isJsonObject() ? saved.getAsJsonObject() : null);
            features.put(tile.lunarId, feature);
            active.add(handle);
            handle.attached(bridge, feature);
        } catch (Exception | LinkageError failed) {
            Log.error("Could not add tile '" + tile.id + "' to Lunar", LunarBridge.unwrap(failed));
            standalone(handle);
        }
    }

    /** A broken HUD element only costs the tile its HUD, not its place in the mod menu. */
    private static void attachHud(TileHandle handle, Object feature) {
        try {
            Object hud = handle.createHud(bridge);
            if (hud != null) {
                bridge.attachHud(feature, hud);
            }
        } catch (Exception | LinkageError failed) {
            Log.error("Could not add the HUD element of tile '" + handle.definition().id + "'", LunarBridge.unwrap(failed));
        }
    }

    private static void retryLater(Throwable reason) {
        if (attempts >= MAX_ATTEMPTS) {
            Log.warn("Lunar never finished starting (" + reason.getMessage() + "); tiles are unavailable.");
            off();
            return;
        }
        state = State.WAITING;
        retryIn = RETRY_DELAY_TICKS;
    }

    private static void off() {
        state = State.OFF;
        for (TileHandle handle : claimed.values()) {
            standalone(handle);
        }
    }

    private static void standalone(TileHandle handle) {
        if (!active.contains(handle)) {
            active.add(handle);
        }
        try {
            handle.standalone();
        } catch (RuntimeException exception) {
            Log.error("Tile '" + handle.definition().id + "' failed to start", exception);
        }
    }

    /** Saves when anything changed since the last write; cheap enough to run every few seconds. */
    private static synchronized void save() {
        if (state != State.RUNNING) {
            return;
        }
        String current = snapshot();
        if (current == null || current.equals(lastWritten)) {
            return;
        }
        try {
            Storage.write(current);
            lastWritten = current;
        } catch (Exception exception) {
            Log.error("Could not save tile settings to " + Storage.file(), exception);
        }
    }

    private static String snapshot() {
        JsonObject tiles = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : stored.entrySet()) {
            tiles.add(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Object> entry : features.entrySet()) {
            try {
                tiles.add(entry.getKey(), bridge.save(entry.getValue()));
            } catch (Exception | LinkageError failed) {
                Log.error("Could not read settings of tile " + entry.getKey(), LunarBridge.unwrap(failed));
                return null;
            }
        }
        return Storage.serialize(tiles);
    }
}
