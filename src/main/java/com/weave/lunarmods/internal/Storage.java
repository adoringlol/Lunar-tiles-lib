package com.weave.lunarmods.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Tile settings live in the Weave folder, not in Lunar's profile, so they survive Lunar profile
 * switches, reinstalls and the tiles being missing from Lunar's own {@code mods.json} at startup.
 * One JSON object per tile, keyed by the tile's Lunar id, in the same shape Lunar saves its mods.
 */
public final class Storage {
    private static final File FILE = new File(System.getProperty("user.home"),
            ".weave" + File.separator + "mod-data" + File.separator + "1.8.9" + File.separator
                    + "lunar-mod-api" + File.separator + "settings.json");

    private Storage() {
    }

    public static File file() {
        return FILE;
    }

    /** The stored tiles, or an empty object when nothing was saved yet or the file is unreadable. */
    public static JsonObject read() {
        if (!FILE.isFile()) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(FILE.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root.isJsonObject() && root.getAsJsonObject().has("tiles")
                    && root.getAsJsonObject().get("tiles").isJsonObject()) {
                return root.getAsJsonObject().getAsJsonObject("tiles");
            }
            Log.warn(FILE + " has no \"tiles\" object; starting from defaults.");
        } catch (Exception exception) {
            Log.error("Could not read " + FILE + "; starting from defaults", exception);
            backupBroken();
        }
        return new JsonObject();
    }

    /** Serialized form written by {@link #write}; compared by callers to skip unchanged saves. */
    public static String serialize(JsonObject tiles) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add("tiles", tiles);
        return root.toString();
    }

    /** Writes through a temporary file so a crash mid-write never leaves a truncated settings file. */
    public static void write(String serialized) throws IOException {
        File directory = FILE.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        File temporary = new File(directory, FILE.getName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary.toPath(), StandardCharsets.UTF_8)) {
            writer.write(serialized);
        }
        try {
            Files.move(temporary.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temporary.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Keeps an unreadable file aside so the next save does not destroy what the user had. */
    private static void backupBroken() {
        try {
            Files.copy(FILE.toPath(), new File(FILE.getParentFile(), FILE.getName() + ".broken").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best effort only.
        }
    }
}
