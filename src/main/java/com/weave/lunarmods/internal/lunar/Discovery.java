package com.weave.lunarmods.internal.lunar;

import com.weave.lunarmods.internal.Log;
import net.weavemc.api.lunar.LunarClient;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Finds the obfuscated Lunar members the library needs.
 *
 * <p>Lunar renames its classes and members every release, so nothing here refers to a name. Each
 * member is identified by structure: types, signatures, the few names Lunar keeps ({@code getId},
 * {@code load}, {@code get}, enum constants) and, for option builders, the UI type a builder reports.
 * The result is cached per Lunar build through the loader's {@link LunarClient#writeCache}, so this
 * scan runs once after each Lunar update and later launches only restore the listed members.
 */
final class Discovery {
    static final String NAMESPACE = "lunar-mod-api";
    /** Bump when the set or meaning of cached keys changes. */
    private static final String SCHEMA = "3";
    private static final String CLIENT_PACKAGE = "com.moonsworth.lunar.client.";
    private static final String PROBE_ID = "lunarModApiProbe";

    private Discovery() {
    }

    /** Restores a cached resolution for this Lunar build, or returns {@code null} to request a scan. */
    static Map<String, Object> fromCache(ClassLoader loader) {
        Map<String, String> entries;
        try {
            entries = LunarClient.readCache(NAMESPACE, loader);
        } catch (LinkageError oldLoader) {
            return null;
        }
        if (entries == null || !SCHEMA.equals(entries.get("schema"))) {
            return null;
        }
        Map<String, Object> members = new HashMap<String, Object>();
        try {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                if (!entry.getKey().equals("schema")) {
                    members.put(entry.getKey(), Members.decode(entry.getValue(), loader));
                }
            }
            return members;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError stale) {
            Log.warn("Cached Lunar lookup no longer matches, rescanning: " + stale);
            return null;
        }
    }

    /** Full structural scan. Safe to run off the main thread: it only reflects and builds throwaway builders. */
    static Map<String, Object> scan() throws Exception {
        long started = System.nanoTime();
        Map<String, Object> found = new LinkedHashMap<String, Object>();

        Class<?> main = findMainClass();
        Method instance = single(main, "Lunar singleton getter", m -> isStatic(m)
                && m.getParameterCount() == 0 && m.getReturnType() == main);
        found.put(Keys.MAIN_INSTANCE, instance);
        Object lunar = instance.invoke(null);
        if (lunar == null) {
            throw new LunarNotReadyException("Lunar has not finished starting");
        }

        findFeatureManager(main, lunar, found);
        Class<?> feature = (Class<?>) found.get(Keys.FEATURE);
        Class<?> manager = ((Method) found.get(Keys.MAIN_FEATURES)).getReturnType();
        Object managerInstance = ((Method) found.get(Keys.MAIN_FEATURES)).invoke(lunar);

        Field items = managerItems(manager);
        Class<?> base = findBaseFeature(feature, (Collection<?>) items.get(managerInstance));
        resolveMembers(manager, feature, base, found);
        try {
            resolveHud(feature, base, new ClassSource() {
                @Override
                public List<Class<?>> classesIn(String packageName) {
                    return LunarClient.loadedClasses(packageName + ".");
                }
            }, found);
        } catch (Exception | LinkageError failed) {
            Log.warn("Lunar's HUD editor was not found in this Lunar version; tiles work, but their HUD elements are not shown: " + failed);
        }

        Log.info(String.format("Resolved Lunar internals in %.1f ms.", (System.nanoTime() - started) / 1e6));
        return found;
    }

    /** Everything below the manager, feature interface and base class; needs no running instance. */
    static void resolveMembers(Class<?> manager, final Class<?> feature, Class<?> base, Map<String, Object> found) throws Exception {
        found.put(Keys.FEATURE, feature);
        found.put(Keys.MANAGER_REGISTER, single(manager, "feature registration", m -> !isStatic(m)
                && !Modifier.isPrivate(m.getModifiers()) && m.getReturnType() == void.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{feature})));
        found.put(Keys.MANAGER_ENABLE, inHierarchy(manager.getSuperclass(), null, "feature enable lifecycle",
                m -> isStatic(m) && Modifier.isPublic(m.getModifiers()) && m.getReturnType() == void.class
                        && Arrays.equals(m.getParameterTypes(), new Class<?>[]{feature})));
        Method refresh = optional(manager, m -> !isStatic(m) && Modifier.isPublic(m.getModifiers())
                && m.getParameterCount() == 0 && m.getReturnType() == void.class
                && !m.getName().equals("init") && !m.getName().equals("close"));
        if (refresh != null) {
            found.put(Keys.MANAGER_REFRESH, refresh);
        }
        found.put(Keys.MANAGER_ITEMS, managerItems(manager));
        found.put(Keys.BASE, base);
        found.put(Keys.BASE_CONSTRUCTOR, Members.accessible(base.getDeclaredConstructor(boolean.class)));
        final Class<?> json = jsonObject(base);
        found.put(Keys.BASE_SAVE, single(base, "feature save", m -> !isStatic(m) && Modifier.isPublic(m.getModifiers())
                && m.getReturnType() == void.class && !m.getName().equals("load")
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{json})));
        found.put(Keys.BASE_LOAD, Members.accessible(base.getDeclaredMethod("load", json)));

        Method hook = single(feature, "options hook", m -> !isStatic(m) && m.getReturnType() == void.class
                && m.getParameterCount() == 1 && !m.getParameterTypes()[0].isPrimitive() && !m.isSynthetic());
        found.put(Keys.FEATURE_HOOK, hook);

        Method metadata = single(base, "metadata getter", m -> !isStatic(m) && m.getParameterCount() == 0
                && m.getReturnType().isInterface() && hasPublicMethod(m.getReturnType(), "getDescription"));
        found.put(Keys.BASE_METADATA, metadata);
        findMetadataBuilder(metadata.getReturnType(), feature, found);

        Method display = single(base, "display getter", m -> !isStatic(m) && m.getParameterCount() == 0
                && m.getReturnType().isInterface() && displayFactory(m.getReturnType()) != null);
        found.put(Keys.BASE_DISPLAY, display);
        Method displayFactory = displayFactory(display.getReturnType());
        found.put(Keys.DISPLAY_FACTORY, displayFactory);
        Class<?> resourceLocation = displayFactory.getParameterTypes()[0];
        found.put(Keys.RL_CREATE, Members.accessible(resourceLocation.getMethod("create", String.class, String.class)));

        findOptions(hook.getParameterTypes()[0], found);
    }

    /** Lists the loaded classes of one package; the live scan asks the loader, offline tests read jars. */
    interface ClassSource {
        List<Class<?>> classesIn(String packageName);
    }

    /**
     * Lunar's HUD elements are a trait of a mod: a mod carrying the "hud_renderer" trait is drawn,
     * dragged and scaled by Lunar's HUD editor while it is enabled. This finds the trait, the setter
     * that attaches it, and the factories of the two HUD kinds (a free-drawn one and Lunar's styled
     * text one). Everything is added to {@code found} together, or nothing is.
     */
    static void resolveHud(Class<?> feature, Class<?> base, ClassSource classes, Map<String, Object> found) throws Exception {
        Map<String, Object> hud = new LinkedHashMap<String, Object>();

        Set<Class<?>> keys = new LinkedHashSet<Class<?>>();
        for (Method method : feature.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!isStatic(method) && parameters.length >= 1 && isTraitKey(parameters[0])) {
                keys.add(parameters[0]);
            }
        }
        if (keys.size() != 1) {
            throw new IllegalStateException("Expected one mod trait key type, found " + keys);
        }
        final Class<?> key = keys.iterator().next();
        hud.put(Keys.TRAIT_SET, inHierarchy(base, Object.class, "mod trait setter", m -> !isStatic(m)
                && Modifier.isPublic(m.getModifiers()) && m.getReturnType() == Object.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{key, Object.class})));

        Field trait = null;
        String featurePackage = packageOf(feature);
        for (Class<?> holder : classes.classesIn(featurePackage)) {
            if (!packageOf(holder).equals(featurePackage)) {
                continue;
            }
            for (Field field : holder.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != key
                        || !(field.getGenericType() instanceof ParameterizedType)) {
                    continue;
                }
                Type argument = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                if (argument instanceof Class && isHudInterface((Class<?>) argument)) {
                    if (trait != null) {
                        throw new IllegalStateException("Several HUD trait keys: " + trait + ", " + field);
                    }
                    trait = field;
                }
            }
        }
        if (trait == null) {
            throw new IllegalStateException("The HUD trait key was not found in " + featurePackage);
        }
        hud.put(Keys.HUD_TRAIT, Members.accessible(trait));
        Class<?> hudType = (Class<?>) ((ParameterizedType) trait.getGenericType()).getActualTypeArguments()[0];

        Class<?> anchor = null;
        for (Method method : hudType.getMethods()) {
            Class<?> type = method.getReturnType();
            if (type.isEnum() && hasConstant(type, "TOP_LEFT") && hasConstant(type, "BOTTOM_RIGHT")) {
                anchor = type;
            }
        }
        if (anchor == null) {
            throw new IllegalStateException("The HUD anchor type was not found on " + hudType.getName());
        }
        hud.put(Keys.HUD_ANCHOR, anchor);

        List<Method> custom = new ArrayList<Method>();
        List<Method> text = new ArrayList<Method>();
        String hudPackage = packageOf(hudType);
        for (Class<?> type : classes.classesIn(hudPackage)) {
            if (!packageOf(type).equals(hudPackage) || !hudType.isAssignableFrom(type)) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!isStatic(method) || !Modifier.isPublic(method.getModifiers()) || !hudType.isAssignableFrom(method.getReturnType())
                        || parameters.length < 5 || parameters[0] != float.class || parameters[1] != float.class || parameters[2] != anchor) {
                    continue;
                }
                if (parameters.length == 5 && parameters[3] == parameters[4] && isHudRenderer(parameters[3])) {
                    custom.add(method);
                } else if (parameters.length == 6 && parameters[3] == boolean.class && parameters[5] == BiFunction.class) {
                    text.add(method);
                }
            }
        }
        if (custom.size() != 1 || text.size() != 1) {
            throw new IllegalStateException("Expected one custom and one text HUD factory, found " + custom + " and " + text);
        }
        hud.put(Keys.HUD_CUSTOM, Members.accessible(custom.get(0)));

        // The text element is subclassed (see TextHudClassFactory), so resolve what the subclass needs.
        Class<?> textType = text.get(0).getDeclaringClass();
        final Class<?> sizes = text.get(0).getParameterTypes()[4];
        hud.put(Keys.HUD_TEXT_CONSTRUCTOR, Members.accessible(textType.getDeclaredConstructor(float.class, float.class, anchor, boolean.class)));
        hud.put(Keys.HUD_TEXT_SIZES, single(textType, "text HUD size getter", m -> Modifier.isAbstract(m.getModifiers())
                && m.getParameterCount() == 0 && m.getReturnType() == sizes));
        hud.put(Keys.HUD_TEXT_VALUE, single(textType, "text HUD value getter", m -> Modifier.isAbstract(m.getModifiers())
                && m.getReturnType() == Object.class && Arrays.equals(m.getParameterTypes(), new Class<?>[]{boolean.class})));
        hud.put(Keys.HUD_TEXT_WIDTH, single(textType, "text HUD box width", m -> !isStatic(m) && !Modifier.isPrivate(m.getModifiers())
                && m.getReturnType() == float.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{boolean.class, Object.class, float.class})));
        hud.put(Keys.HUD_TEXT_HEIGHT, single(textType, "text HUD box height", m -> !isStatic(m) && !Modifier.isPrivate(m.getModifiers())
                && m.getReturnType() == float.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{boolean.class, float.class})));
        hud.put(Keys.HUD_SIZE, single(sizes, "HUD size factory", m -> isStatic(m) && m.getReturnType() == sizes
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class})));

        found.putAll(hud);
    }

    /** Trait keys only carry an id: {@code getId()} is their one public method besides Object's. */
    private static boolean isTraitKey(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) {
            return false;
        }
        boolean id = false;
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (!method.getName().equals("getId") || method.getParameterCount() != 0) {
                return false;
            }
            id = true;
        }
        return id;
    }

    private static boolean isHudInterface(Class<?> type) {
        return type.isInterface() && hasPublicMethod(type, "getWidth") && hasPublicMethod(type, "getHeight")
                && hasPublicMethod(type, "getX") && hasPublicMethod(type, "getY") && hasPublicMethod(type, "setScale");
    }

    /** Lunar's draw callback: {@code clearCache}, {@code getWidth}, {@code getHeight} and one {@code (x, y, context)} draw. */
    private static boolean isHudRenderer(Class<?> type) {
        if (!type.isInterface() || !Modifier.isPublic(type.getModifiers()) || !hasPublicMethod(type, "clearCache")
                || !hasPublicMethod(type, "getWidth") || !hasPublicMethod(type, "getHeight")) {
            return false;
        }
        int draws = 0;
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isAbstract(method.getModifiers()) && parameters.length == 3
                    && parameters[0] == float.class && parameters[1] == float.class) {
                draws++;
            }
        }
        return draws == 1;
    }

    private static String packageOf(Class<?> type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    static Field managerItems(Class<?> manager) {
        return fieldInHierarchy(manager, f -> !Modifier.isStatic(f.getModifiers()) && f.getType() == Set.class);
    }

    /** Stores a scan result; every class that owns a member is fingerprinted so a changed build is detected. */
    static void store(ClassLoader loader, Map<String, Object> members) {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("schema", SCHEMA);
        Set<Class<?>> owners = new LinkedHashSet<Class<?>>();
        for (Map.Entry<String, Object> entry : members.entrySet()) {
            entries.put(entry.getKey(), Members.encode(entry.getValue()));
            owners.add(Members.owner(entry.getValue()));
        }
        try {
            LunarClient.writeCache(NAMESPACE, loader, entries, owners);
        } catch (LinkageError oldLoader) {
            // Older loaders have no cache API; the scan simply runs again next launch.
        }
    }

    // ---- entry points -------------------------------------------------------------------------

    private static Class<?> findMainClass() {
        List<Class<?>> classes = LunarClient.loadedClasses(CLIENT_PACKAGE);
        for (Class<?> candidate : classes) {
            String name = candidate.getName();
            if (name.indexOf('.', CLIENT_PACKAGE.length()) >= 0 || candidate.isInterface()) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (isStatic(method) && method.getParameterCount() == 0 && method.getReturnType() == candidate) {
                    return candidate;
                }
            }
        }
        throw new LunarNotReadyException("Lunar's main class is not loaded (" + classes.size() + " client classes seen)");
    }

    private static void findFeatureManager(Class<?> main, Object lunar, Map<String, Object> found) throws Exception {
        for (Method getter : sorted(main.getDeclaredMethods())) {
            if (isStatic(getter) || !Modifier.isPublic(getter.getModifiers()) || getter.getParameterCount() != 0) {
                continue;
            }
            Class<?> managerType = getter.getReturnType();
            Class<?> feature = featureTypeOf(managerType);
            if (feature == null) {
                continue;
            }
            Object manager = getter.invoke(lunar);
            if (manager == null) {
                continue;
            }
            for (Method fileName : managerType.getDeclaredMethods()) {
                if (!isStatic(fileName) && fileName.getParameterCount() == 0 && fileName.getReturnType() == String.class) {
                    Members.accessible(fileName);
                    if ("mods.json".equals(fileName.invoke(manager))) {
                        found.put(Keys.MAIN_FEATURES, Members.accessible(getter));
                        found.put(Keys.FEATURE, feature);
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("Lunar's mod manager was not found");
    }

    /** The feature interface a manager is parameterized with, recognized by the names Lunar keeps. */
    static Class<?> featureTypeOf(Class<?> managerType) {
        if (managerType.isPrimitive() || managerType.isInterface() || managerType.isArray()) {
            return null;
        }
        Type superType = managerType.getGenericSuperclass();
        if (!(superType instanceof ParameterizedType)) {
            return null;
        }
        Type[] arguments = ((ParameterizedType) superType).getActualTypeArguments();
        if (arguments.length != 1 || !(arguments[0] instanceof Class)) {
            return null;
        }
        Class<?> feature = (Class<?>) arguments[0];
        return feature.isInterface() && hasPublicMethod(feature, "getId") && hasPublicMethod(feature, "isEnabled")
                && hasPublicMethod(feature, "updateEnabled") ? feature : null;
    }

    private static Class<?> findBaseFeature(Class<?> feature, Collection<?> features) {
        for (Object registered : features) {
            for (Class<?> type = registered.getClass(); type != null; type = type.getSuperclass()) {
                if (Arrays.asList(type.getInterfaces()).contains(feature)) {
                    try {
                        type.getDeclaredConstructor(boolean.class);
                        if (Modifier.isAbstract(type.getModifiers())) {
                            return type;
                        }
                    } catch (NoSuchMethodException notBase) {
                        // Keep walking; the shared base takes the enabled-by-default flag.
                    }
                }
            }
        }
        throw new IllegalStateException("Lunar's base mod class was not found");
    }

    private static void findMetadataBuilder(Class<?> metadata, Class<?> feature, Map<String, Object> found) throws NoSuchMethodException {
        Method factory = single(metadata, "metadata builder factory", m -> isStatic(m)
                && m.getParameterCount() == 0 && !m.getReturnType().isPrimitive());
        found.put(Keys.META_BUILDER, factory);
        Class<?> builder = factory.getReturnType();
        found.put(Keys.META_CATEGORIES, single(builder, "metadata categories", m -> !isStatic(m)
                && m.getParameterCount() == 1 && m.getParameterTypes()[0].isArray()
                && m.getParameterTypes()[0].getComponentType() != String.class));
        List<Method> texts = all(builder, m -> !isStatic(m) && Arrays.equals(m.getParameterTypes(), new Class<?>[]{String[].class}));
        List<Method> suppliers = all(builder, m -> !isStatic(m) && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Supplier.class}));
        if (texts.size() != 2 || suppliers.size() != 2) {
            throw new IllegalStateException("Unexpected metadata builder shape: " + texts.size() + " list setters, "
                    + suppliers.size() + " text setters");
        }
        found.put(Keys.META_LIST_A, texts.get(0));
        found.put(Keys.META_LIST_B, texts.get(1));
        found.put(Keys.META_TEXT_A, suppliers.get(0));
        found.put(Keys.META_TEXT_B, suppliers.get(1));
        found.put(Keys.META_BUILD, single(builder, "metadata build", m -> !isStatic(m)
                && m.getReturnType() == metadata && Arrays.equals(m.getParameterTypes(), new Class<?>[]{feature})));
        found.put(Keys.META_NAME, Members.accessible(publicMethod(metadata, "getName")));
        Class<?> category = ((Method) found.get(Keys.META_CATEGORIES)).getParameterTypes()[0].getComponentType();
        found.put(Keys.CATEGORY_VALUES, single(category, "category list", m -> isStatic(m)
                && m.getName().equals("values") && m.getParameterCount() == 0));
    }

    private static Method displayFactory(Class<?> display) {
        for (Method method : display.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (isStatic(method) && method.getReturnType() == display && parameters.length == 6
                    && parameters[0] == parameters[1] && parameters[0].isInterface() && parameters[2] == Supplier.class) {
                return Members.accessible(method);
            }
        }
        return null;
    }

    // ---- options ------------------------------------------------------------------------------

    private static void findOptions(Class<?> registrar, Map<String, Object> found) throws Exception {
        List<Method> adders = new ArrayList<Method>();
        for (Class<?> type = registrar; type != null && type != Object.class; type = type.getSuperclass()) {
            adders.addAll(all(type, m -> !isStatic(m) && Modifier.isPublic(m.getModifiers())
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0].isArray()
                    && m.getParameterTypes()[0].getComponentType().isInterface() && m.getReturnType() != void.class));
        }
        if (adders.isEmpty()) {
            throw new IllegalStateException("Lunar's option registrar has no add method");
        }
        // Lunar has an append and a prepend of this shape; either works because the caller moves the
        // new row to the end of the registrar's list afterwards.
        found.put(Keys.REGISTRAR_ADD, adders.get(0));
        found.put(Keys.REGISTRAR_LIST, fieldInHierarchy(registrar, f -> !Modifier.isStatic(f.getModifiers())
                && f.getType() == LinkedList.class));

        Class<?> option = adders.get(0).getParameterTypes()[0].getComponentType();
        found.put(Keys.OPTION_GET, Members.accessible(publicMethod(option, "get")));
        found.put(Keys.OPTION_RESET, Members.accessible(publicMethod(option, "reset")));
        found.put(Keys.OPTION_SET, single(option, "option update", m -> !isStatic(m) && m.getReturnType() == void.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Object.class, boolean.class})));

        Class<?> factories = null;
        int most = 0;
        for (Class<?> candidate : option.getInterfaces()) {
            int count = all(candidate, m -> isStatic(m) && isStringFactory(m)).size();
            if (count > most) {
                most = count;
                factories = candidate;
            }
        }
        if (factories == null) {
            throw new IllegalStateException("Lunar's option factory was not found");
        }
        List<Method> builders = all(factories, m -> isStatic(m) && isStringFactory(m));
        Class<?> root = builders.get(0).getReturnType();
        while (root.getSuperclass() != null && root.getSuperclass() != Object.class) {
            root = root.getSuperclass();
        }
        final Class<?> builderRoot = root;
        found.put(Keys.BUILDER_BUILD, single(root, "option build", m -> !isStatic(m) && Modifier.isPublic(m.getModifiers())
                && m.getParameterCount() == 0 && m.getReturnType() == option));
        found.put(Keys.BUILDER_NAME, single(root, "option display name", m -> !isStatic(m)
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Supplier.class})));
        found.put(Keys.BUILDER_HIDDEN, single(root, "option hidden condition", m -> !isStatic(m)
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{BooleanSupplier.class})));
        Field uiType = fieldInHierarchy(root, f -> !Modifier.isStatic(f.getModifiers()) && f.getType().isEnum()
                && hasConstant(f.getType(), "SLIDER"));

        Map<Kind, Candidate> chosen = new HashMap<Kind, Candidate>();
        for (Method factory : builders) {
            Object probe;
            try {
                probe = factory.invoke(null, PROBE_ID);
            } catch (Exception | LinkageError unusable) {
                continue;
            }
            if (probe == null) {
                continue;
            }
            Candidate candidate = classify(factory, ((Enum<?>) uiType.get(probe)).name(), builderRoot);
            if (candidate != null) {
                Candidate current = chosen.get(candidate.kind);
                if (current == null || candidate.rank() < current.rank()) {
                    chosen.put(candidate.kind, candidate);
                }
            }
        }
        for (Kind kind : Kind.values()) {
            Candidate candidate = chosen.get(kind);
            if (candidate == null) {
                Log.warn("Lunar has no usable builder for " + kind + " settings; they will be skipped.");
                continue;
            }
            found.put(Keys.kind(kind, Keys.FACTORY), candidate.factory);
            for (Map.Entry<String, Object> member : candidate.members.entrySet()) {
                found.put(Keys.kind(kind, member.getKey()), member.getValue());
            }
        }
    }

    /** Result of matching one option factory to a {@link Kind}. */
    private static final class Candidate {
        final Kind kind;
        final Method factory;
        final Map<String, Object> members = new LinkedHashMap<String, Object>();

        Candidate(Kind kind, Method factory) {
            this.kind = kind;
            this.factory = Members.accessible(factory);
        }

        /** Prefer the plainest builder when several fit: shallow hierarchy, few extra methods. */
        int rank() {
            int depth = 0;
            for (Class<?> type = factory.getReturnType(); type != null; type = type.getSuperclass()) {
                depth++;
            }
            return depth * 1000 + factory.getReturnType().getDeclaredMethods().length;
        }
    }

    private static Candidate classify(Method factory, String ui, Class<?> root) {
        Class<?> builder = factory.getReturnType();
        Class<?> value = typeArgument(builder, root, 2);
        try {
            switch (ui) {
                case "CHECKBOX":
                case "TOGGLE":
                    if (value == Boolean.class) {
                        return with(Kind.BOOLEAN, factory, Keys.DEFAULT, primitiveSetter(builder, root, boolean.class, 1));
                    }
                    return null;
                case "SLIDER": {
                    Class<?> primitive = value == Integer.class ? int.class
                            : value == Float.class ? float.class : value == Double.class ? double.class : null;
                    if (primitive == null) {
                        return null;
                    }
                    Kind kind = primitive == int.class ? Kind.INT_SLIDER
                            : primitive == float.class ? Kind.FLOAT_SLIDER : Kind.DOUBLE_SLIDER;
                    Candidate candidate = with(kind, factory, Keys.DEFAULT, primitiveSetter(builder, root, primitive, 1));
                    candidate.members.put(Keys.RANGE, primitiveSetter(builder, root, primitive, 2));
                    return candidate;
                }
                case "COLOR_PICKER":
                    if (value == Integer.class) {
                        return with(Kind.COLOR, factory, Keys.DEFAULT, primitiveSetter(builder, root, int.class, 1));
                    }
                    return null;
                case "TEXT":
                    if (value == String.class) {
                        return with(Kind.TEXT, factory, Keys.DEFAULT, genericDefault(builder, root));
                    }
                    return null;
                case "DROPDOWN": {
                    Method choices = optional(builder, m -> !isStatic(m) && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Callable.class}));
                    Method labels = optional(builder, m -> !isStatic(m) && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Function.class}));
                    if (value != String.class || choices == null || labels == null) {
                        return null;
                    }
                    Candidate candidate = with(Kind.DROPDOWN, factory, Keys.DEFAULT, genericDefault(builder, root));
                    candidate.members.put(Keys.CHOICES, choices);
                    candidate.members.put(Keys.LABELS, labels);
                    return candidate;
                }
                case "KEYBIND":
                    return keybind(factory, builder, root, value);
                case "BUTTON": {
                    Method action = optional(builder, m -> !isStatic(m) && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Runnable.class}));
                    return action == null ? null : with(Kind.BUTTON, factory, Keys.ACTION, action);
                }
                case "CATEGORY": {
                    Method header = optional(builder, m -> !isStatic(m) && Arrays.equals(m.getParameterTypes(), new Class<?>[]{boolean.class}));
                    Method name = optional(builder, m -> !isStatic(m) && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Supplier.class}));
                    if (header == null || name == null) {
                        return null;
                    }
                    Candidate candidate = with(Kind.LABEL, factory, Keys.HEADER, header);
                    candidate.members.put(Keys.LABEL_NAME, name);
                    return candidate;
                }
                default:
                    return null;
            }
        } catch (IllegalStateException | NoSuchMethodException doesNotFit) {
            return null;
        }
    }

    /** Only the combo keybind (the control behind Lunar's own toggle keybinds) is used. */
    private static Candidate keybind(Method factory, Class<?> builder, Class<?> root, Class<?> combo) throws NoSuchMethodException {
        if (combo == null || combo.isEnum() || combo == String.class) {
            return null;
        }
        Method defaultKey = null;
        for (Class<?> type = builder; type != null && type != root; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!isStatic(method) && !method.isSynthetic() && parameters.length == 1
                        && parameters[0].isEnum() && hasConstant(parameters[0], "KEY_NONE")) {
                    defaultKey = method;
                }
            }
        }
        if (defaultKey == null) {
            return null;
        }
        Class<?> key = defaultKey.getParameterTypes()[0];
        Class<?> option = typeArgument(builder, root, 1);
        if (option == null) {
            return null;
        }
        Method press = null;
        for (Class<?> type = option; type != null && type != Object.class && press == null; type = type.getSuperclass()) {
            press = optional(type, m -> !isStatic(m) && Modifier.isPublic(m.getModifiers())
                    && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Runnable.class}));
        }
        Method comboKey = optional(combo, m -> !isStatic(m) && m.getParameterCount() == 0 && m.getReturnType() == key);
        if (comboKey == null) {
            return null;
        }
        Candidate candidate = with(Kind.KEYBIND, factory, Keys.DEFAULT, Members.accessible(defaultKey));
        candidate.members.put(Keys.KEY_DOWN, Members.accessible(publicMethod(option, "isKeyDown")));
        candidate.members.put(Keys.COMBO, Members.accessible(combo.getConstructor(boolean.class, boolean.class, boolean.class, key)));
        candidate.members.put(Keys.COMBO_KEY, comboKey);
        if (press != null) {
            candidate.members.put(Keys.PRESS, press);
        }
        return candidate;
    }

    private static Candidate with(Kind kind, Method factory, String key, Object member) {
        Candidate candidate = new Candidate(kind, factory);
        candidate.members.put(key, member);
        return candidate;
    }

    /**
     * A setter taking {@code count} parameters of {@code primitive}, declared between the builder and
     * the root (the root's own boolean setter is an unrelated flag).
     */
    private static Method primitiveSetter(Class<?> builder, Class<?> root, Class<?> primitive, int count) {
        Class<?>[] wanted = new Class<?>[count];
        Arrays.fill(wanted, primitive);
        for (Class<?> type = builder; type != null && type != root; type = type.getSuperclass()) {
            Method match = optional(type, m -> !isStatic(m) && Modifier.isPublic(m.getModifiers())
                    && m.getReturnType() != void.class && Arrays.equals(m.getParameterTypes(), wanted));
            if (match != null) {
                return match;
            }
        }
        throw new IllegalStateException("no " + primitive + " setter");
    }

    /** The {@code (T)} default setter on the shared generic base (the one that declares {@code defaultValue}). */
    private static Method genericDefault(Class<?> builder, Class<?> root) {
        for (Class<?> type = builder; type != null && type != root; type = type.getSuperclass()) {
            try {
                type.getDeclaredField("defaultValue");
            } catch (NoSuchFieldException notHere) {
                continue;
            }
            Method setter = optional(type, m -> !isStatic(m) && Modifier.isPublic(m.getModifiers())
                    && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Object.class}));
            if (setter != null) {
                return setter;
            }
        }
        throw new IllegalStateException("no default setter");
    }

    // ---- generics -----------------------------------------------------------------------------

    /** Resolves type parameter {@code index} of {@code root} as seen from {@code type}, erased to a class. */
    static Class<?> typeArgument(Class<?> type, Class<?> root, int index) {
        Map<TypeVariable<?>, Type> bindings = Collections.emptyMap();
        Class<?> current = type;
        while (current != null && current != root) {
            Type superType = current.getGenericSuperclass();
            Class<?> raw = current.getSuperclass();
            Map<TypeVariable<?>, Type> next = new HashMap<TypeVariable<?>, Type>();
            if (superType instanceof ParameterizedType && raw != null) {
                Type[] arguments = ((ParameterizedType) superType).getActualTypeArguments();
                TypeVariable<?>[] variables = raw.getTypeParameters();
                for (int i = 0; i < variables.length && i < arguments.length; i++) {
                    Type argument = arguments[i];
                    if (argument instanceof TypeVariable && bindings.containsKey(argument)) {
                        argument = bindings.get(argument);
                    }
                    next.put(variables[i], argument);
                }
            }
            bindings = next;
            current = raw;
        }
        if (current != root || root.getTypeParameters().length <= index) {
            return null;
        }
        return erase(bindings.get(root.getTypeParameters()[index]));
    }

    private static Class<?> erase(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return erase(((ParameterizedType) type).getRawType());
        }
        if (type instanceof TypeVariable) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : erase(bounds[0]);
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = erase(((GenericArrayType) type).getGenericComponentType());
            return component == null ? null : java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        return null;
    }

    // ---- reflection helpers -------------------------------------------------------------------

    private interface Test<T> {
        boolean test(T value);
    }

    private static boolean isStatic(Method method) {
        return Modifier.isStatic(method.getModifiers());
    }

    private static boolean isStringFactory(Method method) {
        return Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class})
                && !method.getReturnType().isPrimitive() && !method.getReturnType().isInterface();
    }

    private static boolean hasPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Method publicMethod(Class<?> type, String name) throws NoSuchMethodException {
        return type.getMethod(name);
    }

    private static boolean hasConstant(Class<?> type, String name) {
        Object[] constants = type.getEnumConstants();
        if (constants == null) {
            return false;
        }
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> jsonObject(Class<?> base) throws ClassNotFoundException {
        return Class.forName("com.google.gson.JsonObject", false, base.getClassLoader());
    }

    /** Declared, non-synthetic methods matching {@code test}, sorted by name for a stable order. */
    private static List<Method> all(Class<?> type, Test<Method> test) {
        List<Method> matches = new ArrayList<Method>();
        for (Method method : sorted(type.getDeclaredMethods())) {
            if (!method.isSynthetic() && !method.isBridge() && test.test(method)) {
                matches.add(Members.accessible(method));
            }
        }
        return matches;
    }

    private static Method optional(Class<?> type, Test<Method> test) {
        List<Method> matches = all(type, test);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static Method single(Class<?> type, String what, Test<Method> test) {
        List<Method> matches = all(type, test);
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one " + what + " in " + type.getName() + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static Method inHierarchy(Class<?> type, Class<?> stop, String what, Test<Method> test) {
        for (Class<?> current = type; current != null && current != stop; current = current.getSuperclass()) {
            Method match = optional(current, test);
            if (match != null) {
                return match;
            }
        }
        throw new IllegalStateException(what + " was not found above " + type.getName());
    }

    private static Field fieldInHierarchy(Class<?> type, Test<Field> test) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (test.test(field)) {
                    return Members.accessible(field);
                }
            }
        }
        throw new IllegalStateException("Field not found in " + type.getName());
    }

    private static Method[] sorted(Method[] methods) {
        Method[] copy = methods.clone();
        Arrays.sort(copy, new Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                int byName = a.getName().compareTo(b.getName());
                return byName != 0 ? byName : Arrays.toString(a.getParameterTypes()).compareTo(Arrays.toString(b.getParameterTypes()));
            }
        });
        return copy;
    }
}
