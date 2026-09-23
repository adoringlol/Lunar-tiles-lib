package com.weave.lunarmods.internal;

import java.net.URL;
import java.util.List;
import java.util.Locale;

/** One tile declared in a mod's {@code lcmods.json}. */
public final class TileDefinition {
    /** The id mods use in code, as written in the manifest. */
    public final String id;
    /** The id Lunar stores the tile under; prefixed so it can never collide with a built-in mod. */
    public final String lunarId;
    public final String name;
    public final String description;
    public final List<String> creators;
    public final List<String> categories;
    public final boolean enabledByDefault;
    /** The icon image, or {@code null} to let Lunar draw the tile name instead. */
    public final URL icon;
    /** The manifest that declared the tile, for error messages. */
    public final URL source;

    TileDefinition(String id, String name, String description, List<String> creators, List<String> categories,
                   boolean enabledByDefault, URL icon, URL source) {
        this.id = id;
        this.lunarId = "WEAVE_" + id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        this.name = name;
        this.description = description;
        this.creators = creators;
        this.categories = categories;
        this.enabledByDefault = enabledByDefault;
        this.icon = icon;
        this.source = source;
    }

    /** Stand-in for an id no manifest declares, so the calling mod keeps working with local settings. */
    public static TileDefinition undeclared(String id) {
        return new TileDefinition(id, id, "", java.util.Collections.<String>emptyList(),
                java.util.Collections.<String>emptyList(), true, null, null);
    }

    static String key(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
