package com.weave.lunarmods.internal.lunar;

import com.google.gson.JsonObject;
import com.weave.lunarmods.internal.Log;
import com.weave.lunarmods.internal.TileDefinition;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Typed operations over Lunar's resolved internals. Every call goes through members found by
 * {@link Discovery}; callers deal only in plain Java values and opaque Lunar objects.
 */
public final class LunarBridge {
    /** Passed as the config version so Lunar never runs its own config migrations on tile data. */
    private static final int CURRENT_CONFIG = Integer.MAX_VALUE;
    private static final String[][] KEY_ALIASES = {
            {"PRIOR", "PGUP"}, {"NEXT", "PGDOWN"}, {"SYSRQ", "PRINTSC"}, {"LMETA", "LWINDOWS"}, {"RMETA", "RWINDOWS"},
    };

    private final Map<String, Object> members;
    private final Object manager;
    private final Class<?> optionType;
    private final Class<?> categoryType;
    private final Method isEnabled;
    private final Class<?> keyType;
    /** Which of the two metadata text setters is the name; probed on first use. */
    private Boolean nameIsTextA;

    private LunarBridge(Map<String, Object> members) throws Exception {
        this(members, manager(members));
    }

    /** Also used directly by offline tests, which have no running Lunar to take the manager from. */
    LunarBridge(Map<String, Object> members, Object manager) throws Exception {
        this.members = members;
        this.manager = manager;
        this.optionType = method(Keys.REGISTRAR_ADD).getParameterTypes()[0].getComponentType();
        this.categoryType = method(Keys.META_CATEGORIES).getParameterTypes()[0].getComponentType();
        this.isEnabled = ((Class<?>) members.get(Keys.FEATURE)).getMethod("isEnabled");
        Method defaultKey = (Method) members.get(Keys.kind(Kind.KEYBIND, Keys.DEFAULT));
        this.keyType = defaultKey == null ? null : defaultKey.getParameterTypes()[0];
    }

    private static Object manager(Map<String, Object> members) throws Exception {
        Object lunar = ((Method) members.get(Keys.MAIN_INSTANCE)).invoke(null);
        if (lunar == null) {
            throw new LunarNotReadyException("Lunar has not finished starting");
        }
        Object manager = ((Method) members.get(Keys.MAIN_FEATURES)).invoke(lunar);
        if (manager == null) {
            throw new LunarNotReadyException("Lunar's mod manager is not ready");
        }
        return manager;
    }

    /** Restores the cached resolution for this Lunar build; {@code null} when a scan is needed. */
    public static LunarBridge fromCache(ClassLoader loader) {
        Map<String, Object> members = Discovery.fromCache(loader);
        if (members == null) {
            return null;
        }
        try {
            return new LunarBridge(members);
        } catch (LunarNotReadyException notReady) {
            throw notReady;
        } catch (Exception | LinkageError failed) {
            Log.warn("Cached Lunar lookup is unusable, rescanning: " + failed);
            return null;
        }
    }

    /**
     * Scans Lunar (slow path, run off the main thread) and caches the result for this build. A
     * result without the HUD editor is not cached: the cache is only renewed when Lunar changes, so
     * caching it would keep HUD elements hidden even after a library update that finds the editor.
     * The scan then simply runs again next launch.
     */
    public static LunarBridge scan(ClassLoader loader) throws Exception {
        Map<String, Object> members = Discovery.scan();
        LunarBridge bridge = new LunarBridge(members);
        if (bridge.supportsHud()) {
            Discovery.store(loader, members);
        }
        return bridge;
    }

    // ---- tiles --------------------------------------------------------------------------------

    /**
     * Creates the Lunar mod object for a tile. Nothing is registered yet; see {@link #register}.
     *
     * @param icons the 20px list icon and 52px grid icon as Lunar resource locations, or {@code null}
     * @param options called with (feature, registrar) whenever Lunar collects the tile's options
     */
    public Object createTile(final TileDefinition tile, final Object[] icons, BiConsumer<Object, Object> options) throws Exception {
        Class<?> type = TileClassFactory.define(
                clazz(Keys.BASE), method(Keys.BASE_METADATA), method(Keys.BASE_DISPLAY), method(Keys.FEATURE_HOOK),
                tile.lunarId, tile.enabledByDefault,
                new Function<Object, Object>() {
                    @Override
                    public Object apply(Object feature) {
                        return metadata(feature, tile);
                    }
                },
                new Function<Object, Object>() {
                    @Override
                    public Object apply(Object feature) {
                        return display(tile, icons);
                    }
                },
                options);
        return type.getConstructor().newInstance();
    }

    /**
     * Registers a tile with Lunar's mod manager the same way Lunar registers its own mods: init
     * (which builds options and the toggle keybind), add to the mod list, load config, then run the
     * enable lifecycle.
     *
     * @param saved the tile's stored config, or {@code null} to start from defaults
     */
    public void register(Object feature, JsonObject saved) throws Exception {
        method(Keys.MANAGER_REGISTER).invoke(manager, feature);
        @SuppressWarnings("unchecked")
        Collection<Object> items = (Collection<Object>) field(Keys.MANAGER_ITEMS).get(manager);
        items.add(feature);
        if (saved != null) {
            JsonObject config = new JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : saved.entrySet()) {
                config.add(entry.getKey(), entry.getValue());
            }
            config.addProperty("version", CURRENT_CONFIG);
            method(Keys.BASE_LOAD).invoke(feature, config);
        }
        method(Keys.MANAGER_ENABLE).invoke(null, feature);
    }

    /** Pushes the updated mod list to Lunar's web UI; the in-game mod menu reads the list directly. */
    public void refresh() {
        Method refresh = (Method) members.get(Keys.MANAGER_REFRESH);
        if (refresh != null) {
            try {
                refresh.invoke(manager);
            } catch (Exception | LinkageError failed) {
                Log.warn("Could not refresh Lunar's mod list: " + unwrap(failed));
            }
        }
    }

    public boolean isEnabled(Object feature) {
        try {
            return (Boolean) isEnabled.invoke(feature);
        } catch (Exception failed) {
            return false;
        }
    }

    /** The tile's full config (enabled state and every option, including the toggle keybind). */
    public JsonObject save(Object feature) throws Exception {
        JsonObject config = new JsonObject();
        method(Keys.BASE_SAVE).invoke(feature, config);
        return config;
    }

    /** Lunar's own resource-location factory; only needed when the game's class is not Lunar's type. */
    public Object resourceLocation(String domain, String path) throws Exception {
        if (!members.containsKey(Keys.RL_CREATE)) {
            throw new IllegalStateException("Lunar's resource location factory was not found in this Lunar version");
        }
        return method(Keys.RL_CREATE).invoke(null, domain, path);
    }

    public Class<?> resourceLocationType() {
        return method(Keys.DISPLAY_FACTORY).getParameterTypes()[0];
    }

    private Object metadata(Object feature, TileDefinition tile) {
        try {
            if (nameIsTextA == null) {
                Object probe = method(Keys.META_BUILDER).invoke(null);
                method(Keys.META_TEXT_A).invoke(probe, constant("\u0000a"));
                method(Keys.META_TEXT_B).invoke(probe, constant("\u0000b"));
                Object built = method(Keys.META_BUILD).invoke(probe, feature);
                nameIsTextA = "\u0000a".equals(method(Keys.META_NAME).invoke(built));
            }
            Object builder = method(Keys.META_BUILDER).invoke(null);
            Object categories = categories(tile.categories);
            if (Array.getLength(categories) > 0) {
                method(Keys.META_CATEGORIES).invoke(builder, categories);
            }
            if (!tile.creators.isEmpty()) {
                // One of these lists feeds the "Original Authors" line, the other search aliases;
                // creators fit both, so there is no need to tell them apart.
                String[] creators = tile.creators.toArray(new String[0]);
                method(Keys.META_LIST_A).invoke(builder, (Object) creators);
                method(Keys.META_LIST_B).invoke(builder, (Object) creators.clone());
            }
            Supplier<String> name = constant(tile.name);
            Supplier<String> description = constant(tile.description);
            method(Keys.META_TEXT_A).invoke(builder, nameIsTextA ? name : description);
            method(Keys.META_TEXT_B).invoke(builder, nameIsTextA ? description : name);
            return method(Keys.META_BUILD).invoke(builder, feature);
        } catch (Exception failed) {
            throw new IllegalStateException("Could not build metadata for tile " + tile.id, unwrap(failed));
        }
    }

    private Object categories(List<String> names) throws Exception {
        List<Object> matched = new ArrayList<Object>();
        if (!names.isEmpty()) {
            Collection<?> all = (Collection<?>) method(Keys.CATEGORY_VALUES).invoke(null);
            for (String wanted : names) {
                Object match = null;
                for (Object category : all) {
                    if (wanted.equalsIgnoreCase(String.valueOf(category)) || wanted.equalsIgnoreCase(categoryName(category))) {
                        match = category;
                        break;
                    }
                }
                if (match == null) {
                    Log.warn("Unknown tile category '" + wanted + "'; Lunar has " + all);
                } else if (!matched.contains(match)) {
                    matched.add(match);
                }
            }
        }
        Object array = Array.newInstance(categoryType, matched.size());
        for (int i = 0; i < matched.size(); i++) {
            Array.set(array, i, matched.get(i));
        }
        return array;
    }

    private static String categoryName(Object category) {
        try {
            return String.valueOf(category.getClass().getMethod("getName").invoke(category));
        } catch (Exception unavailable) {
            return "";
        }
    }

    private Object display(TileDefinition tile, Object[] icons) {
        try {
            Object list = icons == null ? null : icons[0];
            Object grid = icons == null ? null : icons[1];
            return method(Keys.DISPLAY_FACTORY).invoke(null, list, grid, constant(tile.name), false, 0L, false);
        } catch (Exception failed) {
            throw new IllegalStateException("Could not build the icon for tile " + tile.id, unwrap(failed));
        }
    }

    // ---- HUD elements -------------------------------------------------------------------------

    /** Whether this Lunar build's HUD editor was found; tiles still work without it. */
    public boolean supportsHud() {
        return members.containsKey(Keys.HUD_TRAIT);
    }

    /**
     * Lunar's styled text element (the one its FPS, CPS and similar mods use), with Lunar's text,
     * background, border and color options. {@code text} gets whether the HUD editor is open and
     * returns the line to show, or {@code null} to hide it. Unlike Lunar's own text elements, the
     * box always grows to cover the text; Lunar's background size settings act as a minimum.
     */
    public Object createTextHud(String tileId, String anchor, float x, float y, final Function<Boolean, String> text) throws Exception {
        // Background height 10..40 (default 18) and width 20..300 (default 56), in GUI pixels.
        Object sizes = method(Keys.HUD_SIZE).invoke(null, 10, 18, 40, 20, 56, 300);
        BiFunction<Object, Boolean, Object> value = new BiFunction<Object, Boolean, Object>() {
            @Override
            public Object apply(Object hud, Boolean editing) {
                return text.apply(editing);
            }
        };
        Constructor<?> type = TextHudClassFactory.define((Constructor<?>) members.get(Keys.HUD_TEXT_CONSTRUCTOR),
                method(Keys.HUD_TEXT_SIZES), method(Keys.HUD_TEXT_VALUE), method(Keys.HUD_TEXT_WIDTH),
                method(Keys.HUD_TEXT_HEIGHT), tileId, sizes, value);
        return type.newInstance(x, y, anchor(anchor), false);
    }

    /**
     * A freely drawn element. Lunar asks for the size and calls draw every frame, with the GL matrix
     * already moved to the element's scale; {@code x}, {@code y} is its top-left corner.
     */
    public Object createCustomHud(String anchor, float x, float y, HudCallbacks callbacks) throws Exception {
        Method factory = method(Keys.HUD_CUSTOM);
        Class<?> renderer = factory.getParameterTypes()[3];
        // Lunar takes one renderer for normal play and one for the HUD editor.
        return factory.invoke(null, x, y, anchor(anchor), renderer(renderer, callbacks, false), renderer(renderer, callbacks, true));
    }

    /** Gives the tile its HUD element; must happen before {@link #register}, which is when Lunar reads it. */
    public void attachHud(Object feature, Object hud) throws Exception {
        method(Keys.TRAIT_SET).invoke(feature, field(Keys.HUD_TRAIT).get(null), hud);
    }

    /** Size and drawing of a custom HUD element. */
    public interface HudCallbacks {
        float width(boolean editing);

        float height(boolean editing);

        void draw(float x, float y, boolean editing);
    }

    private static Object renderer(Class<?> type, final HudCallbacks callbacks, final boolean editing) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    if (name.equals("equals")) {
                        return proxy == arguments[0];
                    }
                    return name.equals("hashCode") ? (Object) System.identityHashCode(proxy) : "Lunar Mod API HUD renderer";
                }
                if (name.equals("getWidth")) {
                    return callbacks.width(editing);
                }
                if (name.equals("getHeight")) {
                    return callbacks.height(editing);
                }
                if (arguments != null && arguments.length == 3 && arguments[0] instanceof Float) {
                    callbacks.draw((Float) arguments[0], (Float) arguments[1], editing);
                }
                // clearCache: sizes are asked for fresh every frame, so there is nothing to clear.
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object anchor(String name) {
        Class type = clazz(Keys.HUD_ANCHOR);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            Log.warn("Lunar has no HUD anchor " + name + "; using TOP_LEFT.");
            return Enum.valueOf(type, "TOP_LEFT");
        }
    }

    // ---- options ------------------------------------------------------------------------------

    public boolean supports(Kind kind) {
        return members.containsKey(Keys.kind(kind, Keys.FACTORY));
    }

    public Object buildOption(OptionSpec spec) throws Exception {
        Object builder = kindMethod(spec.kind, Keys.FACTORY).invoke(null, spec.id);
        switch (spec.kind) {
            case BOOLEAN:
            case COLOR:
            case TEXT:
                kindMethod(spec.kind, Keys.DEFAULT).invoke(builder, spec.defaultValue);
                break;
            case INT_SLIDER:
                kindMethod(spec.kind, Keys.RANGE).invoke(builder, spec.min.intValue(), spec.max.intValue());
                kindMethod(spec.kind, Keys.DEFAULT).invoke(builder, spec.defaultValue);
                break;
            case FLOAT_SLIDER:
                kindMethod(spec.kind, Keys.RANGE).invoke(builder, spec.min.floatValue(), spec.max.floatValue());
                kindMethod(spec.kind, Keys.DEFAULT).invoke(builder, spec.defaultValue);
                break;
            case DOUBLE_SLIDER:
                kindMethod(spec.kind, Keys.RANGE).invoke(builder, spec.min.doubleValue(), spec.max.doubleValue());
                kindMethod(spec.kind, Keys.DEFAULT).invoke(builder, spec.defaultValue);
                break;
            case DROPDOWN: {
                final List<String> choices = Collections.unmodifiableList(new ArrayList<String>(spec.choices));
                kindMethod(spec.kind, Keys.CHOICES).invoke(builder, new Callable<List<String>>() {
                    @Override
                    public List<String> call() {
                        return choices;
                    }
                });
                kindMethod(spec.kind, Keys.LABELS).invoke(builder, spec.labels);
                kindMethod(spec.kind, Keys.DEFAULT).invoke(builder, spec.defaultValue);
                break;
            }
            case KEYBIND:
                kindMethod(spec.kind, Keys.DEFAULT).invoke(builder, keyConstant((Integer) spec.defaultValue));
                break;
            case BUTTON:
                kindMethod(spec.kind, Keys.ACTION).invoke(builder, spec.action);
                break;
            case LABEL:
                // The flag is inverted in Lunar: passing false marks the label as a section header.
                kindMethod(spec.kind, Keys.HEADER).invoke(builder, false);
                kindMethod(spec.kind, Keys.LABEL_NAME).invoke(builder, spec.name);
                break;
            default:
                throw new IllegalArgumentException(String.valueOf(spec.kind));
        }
        if (spec.kind != Kind.LABEL && spec.name != null) {
            method(Keys.BUILDER_NAME).invoke(builder, spec.name);
        }
        if (spec.hidden != null) {
            method(Keys.BUILDER_HIDDEN).invoke(builder, spec.hidden);
        }
        Object option = method(Keys.BUILDER_BUILD).invoke(builder);
        if (spec.kind == Kind.KEYBIND && spec.action != null) {
            Method press = (Method) members.get(Keys.kind(Kind.KEYBIND, Keys.PRESS));
            if (press != null) {
                press.invoke(option, spec.action);
            }
        }
        return option;
    }

    /** Whether Lunar delivers key presses itself; otherwise callers poll {@link #isKeyDown}. */
    public boolean hasPressListener() {
        return members.containsKey(Keys.kind(Kind.KEYBIND, Keys.PRESS));
    }

    /**
     * Appends an option to the settings list Lunar is collecting. Lunar's registrar has an append
     * and a prepend of the same shape, so the new row is moved to the end to keep declaration order.
     */
    public void addOption(Object registrar, Object option) throws Exception {
        Object array = Array.newInstance(optionType, 1);
        Array.set(array, 0, option);
        Object row = method(Keys.REGISTRAR_ADD).invoke(registrar, array);
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) field(Keys.REGISTRAR_LIST).get(registrar);
        if (!rows.isEmpty() && rows.get(rows.size() - 1) != row) {
            for (Iterator<Object> iterator = rows.iterator(); iterator.hasNext(); ) {
                if (iterator.next() == row) {
                    iterator.remove();
                    rows.add(row);
                    break;
                }
            }
        }
    }

    public Object value(Object option) throws Exception {
        return method(Keys.OPTION_GET).invoke(option);
    }

    public void setValue(Object option, Object value) throws Exception {
        method(Keys.OPTION_SET).invoke(option, value, false);
    }

    public void reset(Object option) throws Exception {
        method(Keys.OPTION_RESET).invoke(option);
    }

    public boolean isKeyDown(Object option) {
        try {
            return (Boolean) kindMethod(Kind.KEYBIND, Keys.KEY_DOWN).invoke(option);
        } catch (Exception failed) {
            return false;
        }
    }

    /** Converts an LWJGL key code to Lunar's key combo value (no modifiers). */
    public Object keyCombo(int code) throws Exception {
        Constructor<?> combo = (Constructor<?>) members.get(Keys.kind(Kind.KEYBIND, Keys.COMBO));
        return combo.newInstance(false, false, false, keyConstant(code));
    }

    /** The LWJGL key code of a Lunar key combo; mouse buttons are {@code button - 100}, none is 0. */
    public int keyCode(Object combo) throws Exception {
        Enum<?> key = (Enum<?>) kindMethod(Kind.KEYBIND, Keys.COMBO_KEY).invoke(combo);
        String name = key.name().startsWith("KEY_") ? key.name().substring(4) : key.name();
        if (name.equals("NONE")) {
            return Keyboard.KEY_NONE;
        }
        if (name.startsWith("MOUSE")) {
            try {
                return Integer.parseInt(name.substring(5)) - 1 - 100;
            } catch (NumberFormatException unknown) {
                return Keyboard.KEY_NONE;
            }
        }
        for (String[] alias : KEY_ALIASES) {
            if (alias[1].equals(name)) {
                name = alias[0];
            }
        }
        return Keyboard.getKeyIndex(name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object keyConstant(int code) {
        String name;
        if (code < 0) {
            name = "MOUSE" + (code + 100 + 1);
        } else if (code == Keyboard.KEY_NONE) {
            name = "NONE";
        } else {
            name = Keyboard.getKeyName(code);
            if (name == null) {
                name = "NONE";
            }
            for (String[] alias : KEY_ALIASES) {
                if (alias[0].equals(name)) {
                    name = alias[1];
                }
            }
        }
        try {
            return Enum.valueOf((Class) keyType, "KEY_" + name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            Log.warn("Lunar has no key named " + name + "; using none.");
            return Enum.valueOf((Class) keyType, "KEY_NONE");
        }
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private static Supplier<String> constant(final String text) {
        return new Supplier<String>() {
            @Override
            public String get() {
                return text;
            }
        };
    }

    private Method method(String key) {
        Object member = members.get(key);
        if (!(member instanceof Method)) {
            throw new IllegalStateException("Lunar member '" + key + "' was not resolved");
        }
        return (Method) member;
    }

    private Method kindMethod(Kind kind, String member) {
        return method(Keys.kind(kind, member));
    }

    private Field field(String key) {
        return (Field) members.get(key);
    }

    private Class<?> clazz(String key) {
        return (Class<?>) members.get(key);
    }

    /** Unwraps reflection wrappers so logs show Lunar's actual failure. */
    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
