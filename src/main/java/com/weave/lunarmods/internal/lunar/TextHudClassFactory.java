package com.weave.lunarmods.internal.lunar;

import net.weavemc.loader.impl.shaded.asm.ClassWriter;
import net.weavemc.loader.impl.shaded.asm.MethodVisitor;
import net.weavemc.loader.impl.shaded.asm.Opcodes;
import net.weavemc.loader.impl.shaded.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.BiFunction;

/**
 * Generates one subclass of Lunar's text HUD element per tile.
 *
 * <p>Lunar's own text element sizes its box from the "background width/height" sliders whenever the
 * background is on, which suits its short texts ("88 FPS") but lets longer text spill out of the box
 * the HUD editor drags by. The subclass keeps Lunar's sizing as a minimum and grows the box to the
 * content plus Lunar's own padding, so the box always covers everything drawn:
 * <ul>
 *   <li>the size getter returns {@link #SIZES};</li>
 *   <li>the value getter calls {@link #TEXT} with (element, editing);</li>
 *   <li>the box width and height return {@code max(lunar's size, content + padding)}.</li>
 * </ul>
 */
final class TextHudClassFactory {
    static final String SIZES = "sizes";
    static final String TEXT = "text";
    /** Lunar pads the content by this much in total when the background is on. */
    private static final float PADDING = 8.0f;

    private TextHudClassFactory() {
    }

    static Constructor<?> define(Constructor<?> superConstructor, Method sizes, Method value, Method width, Method height,
                                 String tileId, Object sizeValue, BiFunction<Object, Boolean, Object> text) throws Exception {
        Class<?> base = superConstructor.getDeclaringClass();
        String baseName = Type.getInternalName(base);
        String packageName = baseName.substring(0, baseName.lastIndexOf('/') + 1);
        String name = packageName + "WeaveTextHud$" + tileId.replaceAll("[^A-Za-z0-9_]", "_");
        byte[] bytes = generate(name, baseName, superConstructor, sizes, value, width, height);
        Class<?> type = TileClassFactory.defineIn(base, name.replace('/', '.'), bytes);
        type.getField(SIZES).set(null, sizeValue);
        type.getField(TEXT).set(null, text);
        return type.getConstructor(superConstructor.getParameterTypes());
    }

    private static byte[] generate(String name, String base, Constructor<?> superConstructor, Method sizes, Method value,
                                   Method width, Method height) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, base, null);
        int staticField = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        writer.visitField(staticField, SIZES, "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(staticField, TEXT, "Ljava/util/function/BiFunction;", null, null).visitEnd();

        // public <init>(float x, float y, Anchor anchor, boolean alignmentOption) { super(...); }
        String constructorDescriptor = Type.getConstructorDescriptor(superConstructor);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructorDescriptor, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Class<?> parameter : superConstructor.getParameterTypes()) {
            Type type = Type.getType(parameter);
            init.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
            slot += type.getSize();
        }
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", constructorDescriptor, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // public Sizes sizes() { return (Sizes) SIZES; }  (read by Lunar's field initializers, so static)
        MethodVisitor sizeGetter = writer.visitMethod(Opcodes.ACC_PUBLIC, sizes.getName(), Type.getMethodDescriptor(sizes), null, null);
        sizeGetter.visitCode();
        sizeGetter.visitFieldInsn(Opcodes.GETSTATIC, name, SIZES, "Ljava/lang/Object;");
        sizeGetter.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(sizes.getReturnType()));
        sizeGetter.visitInsn(Opcodes.ARETURN);
        sizeGetter.visitMaxs(0, 0);
        sizeGetter.visitEnd();

        // public Object value(boolean editing) { return TEXT.apply(this, Boolean.valueOf(editing)); }
        MethodVisitor valueGetter = writer.visitMethod(Opcodes.ACC_PUBLIC, value.getName(), Type.getMethodDescriptor(value), null, null);
        valueGetter.visitCode();
        valueGetter.visitFieldInsn(Opcodes.GETSTATIC, name, TEXT, "Ljava/util/function/BiFunction;");
        valueGetter.visitVarInsn(Opcodes.ALOAD, 0);
        valueGetter.visitVarInsn(Opcodes.ILOAD, 1);
        valueGetter.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        valueGetter.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", "apply",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        valueGetter.visitInsn(Opcodes.ARETURN);
        valueGetter.visitMaxs(0, 0);
        valueGetter.visitEnd();

        // float width(boolean background, T value, float contentWidth)
        coveringSize(writer, base, width, 3);
        // float height(boolean background, float contentHeight)
        coveringSize(writer, base, height, 2);

        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * {@code return Math.max(super.m(args), content + (background ? PADDING : 0));}, written without
     * branches ({@code background} is 0 or 1) so the method needs no stack map frames.
     */
    private static void coveringSize(ClassWriter writer, String base, Method method, int contentSlot) {
        String descriptor = Type.getMethodDescriptor(method);
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PROTECTED, method.getName(), descriptor, null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Class<?> parameter : method.getParameterTypes()) {
            Type type = Type.getType(parameter);
            visitor.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
            slot += type.getSize();
        }
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, base, method.getName(), descriptor, false);
        visitor.visitVarInsn(Opcodes.FLOAD, contentSlot);
        visitor.visitVarInsn(Opcodes.ILOAD, 1);
        visitor.visitInsn(Opcodes.I2F);
        visitor.visitLdcInsn(PADDING);
        visitor.visitInsn(Opcodes.FMUL);
        visitor.visitInsn(Opcodes.FADD);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false);
        visitor.visitInsn(Opcodes.FRETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }
}
