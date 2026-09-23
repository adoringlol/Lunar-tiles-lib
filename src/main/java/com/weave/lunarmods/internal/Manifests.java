package com.weave.lunarmods.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads every {@code lcmods.json} on the mod class path. Each mod jar may declare any number of tiles:
 *
 * <pre>{@code
 * {
 *   "mods": [
 *     {
 *       "id": "my_tile",
 *       "name": "My Tile",
 *       "description": "What it does.",
 *       "icon": "assets/mymod/icon.png",
 *       "creators": ["Someone"],
 *       "categories": ["mechanic"],
 *       "enabledByDefault": false
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class Manifests {
    public static final String FILE_NAME = "lcmods.json";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,47}");

    private static Map<String, TileDefinition> tiles;

    private Manifests() {
    }

    /** The tile declared with {@code id} (case-insensitive), or {@code null}. */
    public static TileDefinition find(String id) {
        return all().get(TileDefinition.key(id));
    }

    public static synchronized Map<String, TileDefinition> all() {
        if (tiles == null) {
            tiles = Collections.unmodifiableMap(scan());
        }
        return tiles;
    }

    private static Map<String, TileDefinition> scan() {
        Map<String, TileDefinition> found = new LinkedHashMap<String, TileDefinition>();
        for (URL manifest : manifestUrls()) {
            try (Reader reader = new InputStreamReader(manifest.openStream(), StandardCharsets.UTF_8)) {
                JsonElement root = new JsonParser().parse(reader);
                JsonArray mods = root.isJsonObject() && root.getAsJsonObject().has("mods")
                        ? root.getAsJsonObject().getAsJsonArray("mods")
                        : null;
                if (mods == null) {
                    Log.warn(manifest + " has no \"mods\" array, skipping it.");
                    continue;
                }
                for (JsonElement element : mods) {
                    TileDefinition tile = parse(element, manifest);
                    if (tile == null) {
                        continue;
                    }
                    TileDefinition existing = found.get(TileDefinition.key(tile.id));
                    for (TileDefinition other : found.values()) {
                        if (other.lunarId.equals(tile.lunarId)) {
                            existing = other;
                        }
                    }
                    if (existing != null && existing.source.toString().equals(manifest.toString())) {
                        continue;
                    }
                    if (existing != null) {
                        Log.warn("Tile '" + tile.id + "' in " + manifest + " is already declared by "
                                + existing.source + "; the first declaration wins.");
                        continue;
                    }
                    found.put(TileDefinition.key(tile.id), tile);
                }
            } catch (Exception exception) {
                Log.error("Could not read " + manifest, exception);
            }
        }
        Log.info("Found " + found.size() + " tile(s) in " + FILE_NAME + " manifests.");
        return found;
    }

    private static Set<URL> manifestUrls() {
        Set<URL> urls = new LinkedHashSet<URL>();
        collect(urls, Manifests.class.getClassLoader());
        collect(urls, Thread.currentThread().getContextClassLoader());
        return urls;
    }

    private static void collect(Set<URL> urls, ClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            Enumeration<URL> resources = loader.getResources(FILE_NAME);
            while (resources.hasMoreElements()) {
                urls.add(resources.nextElement());
            }
        } catch (Exception exception) {
            Log.error("Could not list " + FILE_NAME + " files", exception);
        }
    }

    private static TileDefinition parse(JsonElement element, URL manifest) {
        if (!element.isJsonObject()) {
            Log.warn("Skipping a non-object entry in " + manifest);
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        String id = string(json, "id", null);
        if (id == null || !ID.matcher(id).matches()) {
            Log.warn("Skipping a tile in " + manifest + ": \"id\" must match " + ID.pattern());
            return null;
        }
        String name = string(json, "name", null);
        if (name == null || name.trim().isEmpty()) {
            Log.warn("Skipping tile '" + id + "' in " + manifest + ": \"name\" is required.");
            return null;
        }
        String iconPath = string(json, "icon", null);
        URL icon = null;
        if (iconPath != null && !iconPath.trim().isEmpty()) {
            icon = resolve(manifest, iconPath.trim());
            if (icon == null) {
                Log.warn("Icon '" + iconPath + "' of tile '" + id + "' was not found next to " + manifest);
            }
        }
        return new TileDefinition(
                id,
                name.trim(),
                string(json, "description", ""),
                strings(json, "creators"),
                strings(json, "categories"),
                json.has("enabledByDefault") && json.get("enabledByDefault").getAsBoolean(),
                icon,
                manifest);
    }

    /** Resolves {@code path} against the root of the jar (or directory) holding the manifest. */
    private static URL resolve(URL manifest, String path) {
        try {
            String relative = path.startsWith("/") ? path.substring(1) : path;
            String text = manifest.toString();
            int jarRoot = text.indexOf("!/");
            URL icon = jarRoot >= 0
                    ? new URL(text.substring(0, jarRoot + 2) + relative)
                    : new URL(manifest, relative);
            icon.openStream().close();
            return icon;
        } catch (Exception missing) {
            return null;
        }
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    /** Accepts either a single string or an array of strings. */
    private static List<String> strings(JsonObject json, String key) {
        JsonElement value = json.get(key);
        List<String> result = new ArrayList<String>();
        if (value == null || value.isJsonNull()) {
            return result;
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                String text = item.getAsString().trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        } else if (!value.getAsString().trim().isEmpty()) {
            result.add(value.getAsString().trim());
        }
        return Collections.unmodifiableList(result);
    }
}
