package com.weave.lunarmods.internal.lunar;

import net.weavemc.loader.impl.shaded.asm.ClassWriter;
import net.weavemc.loader.impl.shaded.asm.MethodVisitor;
import net.weavemc.loader.impl.shaded.asm.Opcodes;
import net.weavemc.loader.impl.shaded.asm.Type;

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
        String baseName = Type.getInternalName(base);
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
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, base, null);
        int staticField = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        writer.visitField(staticField, ID, "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(staticField, ENABLED_BY_DEFAULT, "Z", null, null).visitEnd();
        writer.visitField(staticField, METADATA, "Ljava/util/function/Function;", null, null).visitEnd();
        writer.visitField(staticField, DISPLAY, "Ljava/util/function/Function;", null, null).visitEnd();
        writer.visitField(staticField, OPTIONS, "Ljava/util/function/BiConsumer;", null, null).visitEnd();

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETSTATIC, name, ENABLED_BY_DEFAULT, "Z");
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", "(Z)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor getId = writer.visitMethod(Opcodes.ACC_PUBLIC, "getId", "()Ljava/lang/String;", null, null);
        getId.visitCode();
        getId.visitFieldInsn(Opcodes.GETSTATIC, name, ID, "Ljava/lang/String;");
        getId.visitInsn(Opcodes.ARETURN);
        getId.visitMaxs(0, 0);
        getId.visitEnd();

        functionGetter(writer, name, metadata, METADATA);
        functionGetter(writer, name, display, DISPLAY);

        MethodVisitor hook = writer.visitMethod(Opcodes.ACC_PUBLIC, optionsHook.getName(),
                Type.getMethodDescriptor(optionsHook), null, null);
        hook.visitCode();
        hook.visitFieldInsn(Opcodes.GETSTATIC, name, OPTIONS, "Ljava/util/function/BiConsumer;");
        hook.visitVarInsn(Opcodes.ALOAD, 0);
        hook.visitVarInsn(Opcodes.ALOAD, 1);
        hook.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiConsumer", "accept",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        hook.visitInsn(Opcodes.RETURN);
        hook.visitMaxs(0, 0);
        hook.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code public R method() { return (R) FIELD.apply(this); }} */
    private static void functionGetter(ClassWriter writer, String owner, Method method, String field) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, method.getName(),
                Type.getMethodDescriptor(method), null, null);
        visitor.visitCode();
        visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, field, "Ljava/util/function/Function;");
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        visitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(method.getReturnType()));
        visitor.visitInsn(Opcodes.ARETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
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
