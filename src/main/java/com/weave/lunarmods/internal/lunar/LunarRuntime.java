package com.weave.lunarmods.internal.lunar;

import com.weave.lunarmods.internal.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What the library needs from the environment to work with Lunar's obfuscated code, built on stock
 * Weave: detecting Lunar, listing its loaded classes (through the {@link Instrumentation} Weave
 * passes to {@code preInit}), and a cache that lasts until Lunar ships a new build.
 *
 * <p>Cache files live in {@code ~/.weave/cache/lunar/<namespace>/<build id>.properties}. A build is
 * identified by the hash of Lunar's {@code lunarBuildData.txt}; every class that owns a cached
 * member is also fingerprinted, so a changed class invalidates the file even within one build.
 */
public final class LunarRuntime {
    private static final String LUNAR_PACKAGE = "com.moonsworth.lunar.";
    private static final String BUILD_DATA = "lunarBuildData.txt";
    private static final String FORMAT_KEY = "format";
    private static final String FORMAT_VERSION = "1";
    private static final String BUILD_KEY = "build";
    private static final String ENTRY_PREFIX = "entry.";
    private static final String FINGERPRINT_PREFIX = "fingerprint.";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private static volatile Instrumentation instrumentation;

    private LunarRuntime() {
    }

    /** Called from the Weave entry point's {@code preInit}. */
    public static void install(Instrumentation value) {
        instrumentation = value;
    }

    /** Whether classes can be listed; false when Weave never called {@code preInit}. */
    public static boolean canListClasses() {
        return instrumentation != null;
    }

    /** Whether the game is Lunar Client: its build data is visible to {@code loader}, or its classes are loaded. */
    public static boolean isRunning(ClassLoader loader) {
        return buildData(loader) != null || !loadedClasses(LUNAR_PACKAGE).isEmpty();
    }

    /** Loaded classes whose binary names start with {@code prefix}; empty without instrumentation. */
    public static List<Class<?>> loadedClasses(String prefix) {
        Instrumentation current = instrumentation;
        if (current == null) {
            return Collections.emptyList();
        }
        List<Class<?>> matches = new ArrayList<Class<?>>();
        for (Class<?> type : current.getAllLoadedClasses()) {
            if (type.getName().startsWith(prefix)) {
                matches.add(type);
            }
        }
        return matches;
    }

    /**
     * The entries stored with {@link #writeCache} for the running Lunar build, or {@code null} when
     * nothing is cached, the build changed or a fingerprinted class no longer matches (the stale
     * file is deleted then, so callers only need to rediscover and write again).
     */
    public static Map<String, String> readCache(String namespace, ClassLoader loader) {
        if (!cacheEnabled()) {
            return null;
        }
        Build build = Build.detect(loader);
        if (build == null) {
            return null;
        }
        Path file = cacheFile(namespace, build.id);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            if (!FORMAT_VERSION.equals(properties.getProperty(FORMAT_KEY))) {
                invalidate(namespace, file, "unsupported format");
                return null;
            }
            Map<String, String> entries = new HashMap<String, String>();
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(FINGERPRINT_PREFIX)) {
                    Class<?> type = Class.forName(key.substring(FINGERPRINT_PREFIX.length()), false, loader);
                    if (!fingerprint(type).equals(properties.getProperty(key))) {
                        invalidate(namespace, file, "fingerprint of " + type.getName() + " changed");
                        return null;
                    }
                } else if (key.startsWith(ENTRY_PREFIX)) {
                    entries.put(key.substring(ENTRY_PREFIX.length()), properties.getProperty(key));
                }
            }
            Log.info("Loaded Lunar cache '" + namespace + "' for " + build.summary);
            return entries;
        } catch (Exception | LinkageError failed) {
            invalidate(namespace, file, String.valueOf(failed.getMessage()));
            return null;
        }
    }

    /** Stores {@code entries} for the running Lunar build, replacing the files of older builds. */
    public static boolean writeCache(String namespace, ClassLoader loader, Map<String, String> entries,
                                     Collection<Class<?>> fingerprinted) {
        if (!cacheEnabled()) {
            return false;
        }
        Build build = Build.detect(loader);
        if (build == null) {
            return false;
        }
        Path file = cacheFile(namespace, build.id);
        try {
            Properties properties = new Properties();
            properties.setProperty(FORMAT_KEY, FORMAT_VERSION);
            properties.setProperty(BUILD_KEY, build.summary);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                properties.setProperty(ENTRY_PREFIX + entry.getKey(), entry.getValue());
            }
            for (Class<?> type : fingerprinted) {
                properties.setProperty(FINGERPRINT_PREFIX + type.getName(), fingerprint(type));
            }
            writeAtomically(file, properties, "Lunar Mod API cache: " + namespace);
            deleteOtherBuilds(file);
            Log.info("Saved Lunar cache '" + namespace + "' for " + build.summary);
            return true;
        } catch (Exception | LinkageError failed) {
            Log.warn("Could not save Lunar cache '" + namespace + "': " + failed);
            return false;
        }
    }

    private static boolean cacheEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("weave.lunar.cache"));
    }

    private static Path cacheFile(String namespace, String buildId) {
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid cache namespace: " + namespace);
        }
        return cacheRoot().resolve("lunar").resolve(namespace).resolve(buildId + ".properties");
    }

    private static Path cacheRoot() {
        String override = System.getProperty("weave.lunar.cache.dir");
        return override == null || override.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".weave", "cache")
                : Paths.get(override);
    }

    private static void deleteOtherBuilds(Path current) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(current.getParent(), "*.properties")) {
            for (Path file : files) {
                if (!file.getFileName().equals(current.getFileName())) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // Best effort; a leftover file is never read for another build.
                    }
                }
            }
        }
    }

    private static void invalidate(String namespace, Path file, String reason) {
        Log.info("Invalidating Lunar cache '" + namespace + "': " + reason);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The next write replaces it.
        }
    }

    private static void writeAtomically(Path file, Properties properties, String comment) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, comment);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The class's bytecode hash, or a hash of its declared shape when the bytecode is not readable. */
    static String fingerprint(Class<?> type) throws IOException {
        InputStream stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class");
        if (stream != null) {
            try (InputStream input = stream) {
                return "bytecode:" + sha256(readAll(input));
            }
        }
        StringBuilder shape = new StringBuilder();
        shape.append(type.getName()).append('|').append(type.getModifiers()).append('|');
        TreeSet<String> parts = new TreeSet<String>();
        for (Field field : type.getDeclaredFields()) {
            parts.add(field.getName() + ":" + field.getType().getName() + ":" + field.getModifiers());
        }
        append(shape, parts);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            parts.add(parameters("", constructor.getParameterTypes()));
        }
        append(shape, parts);
        for (Method method : type.getDeclaredMethods()) {
            parts.add(parameters(method.getName(), method.getParameterTypes()) + ":"
                    + method.getReturnType().getName() + ":" + method.getModifiers());
        }
        append(shape, parts);
        return "shape:" + sha256(shape.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder shape, TreeSet<String> parts) {
        for (String part : parts) {
            shape.append(part).append(';');
        }
        shape.append('|');
        parts.clear();
    }

    private static String parameters(String name, Class<?>[] types) {
        StringBuilder text = new StringBuilder(name).append('(');
        for (int i = 0; i < types.length; i++) {
            text.append(i == 0 ? "" : ",").append(types[i].getName());
        }
        return text.append(')').toString();
    }

    private static byte[] buildData(ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        try (InputStream input = loader.getResourceAsStream(BUILD_DATA)) {
            return input == null ? null : readAll(input);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Identity of the running Lunar build, from its {@code lunarBuildData.txt}. */
    private static final class Build {
        final String id;
        final String summary;

        private Build(String id, String summary) {
            this.id = id;
            this.summary = summary;
        }

        static Build detect(ClassLoader loader) {
            byte[] bytes = buildData(loader);
            if (bytes == null) {
                return null;
            }
            Properties properties = new Properties();
            try {
                properties.load(new ByteArrayInputStream(bytes));
            } catch (IOException unreadable) {
                return null;
            }
            String version = value(properties, "lunarVersion");
            String git = properties.getProperty("gitHash") != null && !properties.getProperty("gitHash").trim().isEmpty()
                    ? properties.getProperty("gitHash") : value(properties, "fullGitHash");
            return new Build(sha256(bytes), version + "/" + git + "/" + value(properties, "proguardUuid"));
        }

        private static String value(Properties properties, String key) {
            String value = properties.getProperty(key);
            return value == null || value.trim().isEmpty() ? "unknown" : value;
        }
    }
}
