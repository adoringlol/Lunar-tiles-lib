package com.weave.lunarmods.internal.lunar;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Text form of reflected members, so a resolution can be cached per Lunar build and restored with
 * direct lookups instead of scanning. Classes are stored by name, fields as {@code owner#name} and
 * methods/constructors as {@code owner#name(param,param)} using {@link Class#getName()} spellings.
 */
final class Members {
    private static final Map<String, Class<?>> PRIMITIVES = new HashMap<String, Class<?>>();

    static {
        for (Class<?> type : new Class<?>[]{boolean.class, byte.class, short.class, char.class, int.class,
                long.class, float.class, double.class, void.class}) {
            PRIMITIVES.put(type.getName(), type);
        }
    }

    private Members() {
    }

    static String encode(Object value) {
        if (value instanceof Class) {
            return "C:" + ((Class<?>) value).getName();
        }
        if (value instanceof Field) {
            Field field = (Field) value;
            return "F:" + field.getDeclaringClass().getName() + "#" + field.getName();
        }
        if (value instanceof Method) {
            Method method = (Method) value;
            return "M:" + method.getDeclaringClass().getName() + "#" + method.getName() + params(method.getParameterTypes());
        }
        if (value instanceof Constructor) {
            Constructor<?> constructor = (Constructor<?>) value;
            return "K:" + constructor.getDeclaringClass().getName() + "#<init>" + params(constructor.getParameterTypes());
        }
        throw new IllegalArgumentException("Cannot encode " + value);
    }

    static Object decode(String text, ClassLoader loader) throws ReflectiveOperationException {
        char kind = text.charAt(0);
        String body = text.substring(2);
        if (kind == 'C') {
            return type(body, loader);
        }
        int hash = body.indexOf('#');
        Class<?> owner = type(body.substring(0, hash), loader);
        String rest = body.substring(hash + 1);
        if (kind == 'F') {
            return accessible(owner.getDeclaredField(rest));
        }
        int open = rest.indexOf('(');
        String name = rest.substring(0, open);
        String list = rest.substring(open + 1, rest.length() - 1);
        String[] names = list.isEmpty() ? new String[0] : list.split(",");
        Class<?>[] parameters = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            parameters[i] = type(names[i], loader);
        }
        if (kind == 'K') {
            return accessible(owner.getDeclaredConstructor(parameters));
        }
        if (kind == 'M') {
            return accessible(owner.getDeclaredMethod(name, parameters));
        }
        throw new IllegalArgumentException("Unknown member kind in " + text);
    }

    static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> primitive = PRIMITIVES.get(name);
        return primitive != null ? primitive : Class.forName(name, false, loader);
    }

    static <T extends AccessibleObject> T accessible(T member) {
        try {
            member.setAccessible(true);
        } catch (RuntimeException ignored) {
            // Public members still work; anything else fails loudly at the first call.
        }
        return member;
    }

    static Class<?> owner(Object value) {
        if (value instanceof Class) {
            return (Class<?>) value;
        }
        return ((Member) value).getDeclaringClass();
    }

    private static String params(Class<?>[] types) {
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(types[i].getName());
        }
        return builder.append(')').toString();
    }
}
