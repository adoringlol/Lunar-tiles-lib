package com.weave.lunarmods.internal.lunar;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Generates one subclass of Lunar's base mod class per tile.
 *
 * <p>Lunar only lists real subclasses of its base mod class, and the base wires up the enabled
 * switch, toggle keybind, options and mod-menu panel in its constructor and init. A generated
 * subclass gets all of that for free and only overrides what differs per tile:
 * <ul>
 *   <li>{@code getId()} returns {@link #ID};</li>
 *   <li>the metadata and display getters call {@link #METADATA} and {@link #DISPLAY};</li>
 *   <li>the options hook calls {@link #OPTIONS}.</li>
 * </ul>
 * The class is defined in the base class's own package and loader, so it can see Lunar's types,
 * and its static fields only use JDK types, so it never links back to this library's loader.
 */
final class TileClassFactory {
    static final String ID = "id";
    static final String ENABLED_BY_DEFAULT = "enabledByDefault";
    static final String METADATA = "metadata";
    static final String DISPLAY = "display";
    static final String OPTIONS = "options";

    private TileClassFactory() {
    }

    static Class<?> define(Class<?> base, Method metadata, Method display, Method optionsHook, String tileId,
                           boolean enabledByDefault, Function<Object, Object> metadataFactory,
                           Function<Object, Object> displayFactory, BiConsumer<Object, Object> options) throws Exception {
        String baseName = ClassBuilder.internalName(base);
        String packageName = baseName.substring(0, baseName.lastIndexOf('/') + 1);
        String name = packageName + "WeaveTile$" + tileId.replaceAll("[^A-Za-z0-9_]", "_");
        byte[] bytes = generate(name, baseName, metadata, display, optionsHook);
        Class<?> type = defineIn(base, name.replace('/', '.'), bytes);
        type.getField(ID).set(null, tileId);
        type.getField(ENABLED_BY_DEFAULT).setBoolean(null, enabledByDefault);
        type.getField(METADATA).set(null, metadataFactory);
        type.getField(DISPLAY).set(null, displayFactory);
        type.getField(OPTIONS).set(null, options);
        return type;
    }

    private static byte[] generate(String name, String base, Method metadata, Method display, Method optionsHook) {
        ClassBuilder builder = new ClassBuilder(name, base);
        int staticField = ClassBuilder.ACC_PUBLIC | ClassBuilder.ACC_STATIC;
        builder.field(staticField, ID, "Ljava/lang/String;");
        builder.field(staticField, ENABLED_BY_DEFAULT, "Z");
        builder.field(staticField, METADATA, "Ljava/util/function/Function;");
        builder.field(staticField, DISPLAY, "Ljava/util/function/Function;");
        builder.field(staticField, OPTIONS, "Ljava/util/function/BiConsumer;");

        builder.method(ClassBuilder.ACC_PUBLIC, "<init>", "()V")
                .loadThis()
                .getStatic(name, ENABLED_BY_DEFAULT, "Z")
                .invokeSpecial(base, "<init>", "(Z)V")
                .returnValue(void.class);

        builder.method(ClassBuilder.ACC_PUBLIC, "getId", "()Ljava/lang/String;")
                .getStatic(name, ID, "Ljava/lang/String;")
                .returnValue(String.class);

        functionGetter(builder, name, metadata, METADATA);
        functionGetter(builder, name, display, DISPLAY);

        builder.method(ClassBuilder.ACC_PUBLIC, optionsHook.getName(), ClassBuilder.descriptor(optionsHook))
                .getStatic(name, OPTIONS, "Ljava/util/function/BiConsumer;")
                .loadThis()
                .load(Object.class, 1)
                .invokeInterface("java/util/function/BiConsumer", "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V")
                .returnValue(void.class);

        return builder.toByteArray();
    }

    /** {@code public R method() { return (R) FIELD.apply(this); }} */
    private static void functionGetter(ClassBuilder builder, String owner, Method method, String field) {
        builder.method(ClassBuilder.ACC_PUBLIC, method.getName(), ClassBuilder.descriptor(method))
                .getStatic(owner, field, "Ljava/util/function/Function;")
                .loadThis()
                .invokeInterface("java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;")
                .checkCast(ClassBuilder.internalName(method.getReturnType()))
                .returnValue(method.getReturnType());
    }

    /**
     * Java 9+ defines through a private lookup on the base class (same loader and package). The
     * library targets Java 8 bytecode, so those APIs are called reflectively, with the classic
     * {@code ClassLoader.defineClass} as the fallback for Java 8 runtimes.
     */
    static Class<?> defineIn(Class<?> base, String name, byte[] bytes) throws Exception {
        try {
            Method privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
            Object lookup = privateLookupIn.invoke(null, base, MethodHandles.lookup());
            Method defineClass = MethodHandles.Lookup.class.getMethod("defineClass", byte[].class);
            return (Class<?>) defineClass.invoke(lookup, (Object) bytes);
        } catch (NoSuchMethodException java8) {
            Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            return (Class<?>) defineClass.invoke(base.getClassLoader(), name, bytes, 0, bytes.length);
        }
    }
}
