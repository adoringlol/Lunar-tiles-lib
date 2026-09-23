package com.weave.lunarmods.internal.lunar;

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
        String baseName = ClassBuilder.internalName(base);
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
        ClassBuilder builder = new ClassBuilder(name, base);
        int staticField = ClassBuilder.ACC_PUBLIC | ClassBuilder.ACC_STATIC;
        builder.field(staticField, SIZES, "Ljava/lang/Object;");
        builder.field(staticField, TEXT, "Ljava/util/function/BiFunction;");

        // public <init>(float x, float y, Anchor anchor, boolean alignmentOption) { super(...); }
        String constructorDescriptor = ClassBuilder.descriptor(superConstructor);
        builder.method(ClassBuilder.ACC_PUBLIC, "<init>", constructorDescriptor)
                .loadThis()
                .loadParameters(superConstructor.getParameterTypes())
                .invokeSpecial(base, "<init>", constructorDescriptor)
                .returnValue(void.class);

        // public Sizes sizes() { return (Sizes) SIZES; }  (read by Lunar's field initializers, so static)
        builder.method(ClassBuilder.ACC_PUBLIC, sizes.getName(), ClassBuilder.descriptor(sizes))
                .getStatic(name, SIZES, "Ljava/lang/Object;")
                .checkCast(ClassBuilder.internalName(sizes.getReturnType()))
                .returnValue(sizes.getReturnType());

        // public Object value(boolean editing) { return TEXT.apply(this, Boolean.valueOf(editing)); }
        builder.method(ClassBuilder.ACC_PUBLIC, value.getName(), ClassBuilder.descriptor(value))
                .getStatic(name, TEXT, "Ljava/util/function/BiFunction;")
                .loadThis()
                .load(boolean.class, 1)
                .invokeStatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
                .invokeInterface("java/util/function/BiFunction", "apply",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
                .returnValue(value.getReturnType());

        // float width(boolean background, T value, float contentWidth)
        coveringSize(builder, base, width, 3);
        // float height(boolean background, float contentHeight)
        coveringSize(builder, base, height, 2);

        return builder.toByteArray();
    }

    /**
     * {@code return Math.max(super.m(args), content + (background ? PADDING : 0));}, written without
     * branches ({@code background} is 0 or 1) so the method needs no stack map frames.
     */
    private static void coveringSize(ClassBuilder builder, String base, Method method, int contentSlot) {
        String descriptor = ClassBuilder.descriptor(method);
        builder.method(ClassBuilder.ACC_PROTECTED, method.getName(), descriptor)
                .loadThis()
                .loadParameters(method.getParameterTypes())
                .invokeSpecial(base, method.getName(), descriptor)
                .load(float.class, contentSlot)
                .load(boolean.class, 1)
                .i2f()
                .ldc(PADDING)
                .fmul()
                .fadd()
                .invokeStatic("java/lang/Math", "max", "(FF)F")
                .returnValue(float.class);
    }
}
