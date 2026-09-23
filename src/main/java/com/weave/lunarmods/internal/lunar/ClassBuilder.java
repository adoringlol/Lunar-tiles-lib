package com.weave.lunarmods.internal.lunar;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the few small classes the library generates, so it needs no bytecode library (each Weave
 * version relocates its bundled ASM to a different package).
 *
 * <p>Only straight-line code is supported: without branches a Java 8 class needs no stack map
 * frames, and the maximum stack depth is tracked as instructions are added.
 */
final class ClassBuilder {
    static final int ACC_PUBLIC = 0x0001;
    static final int ACC_PROTECTED = 0x0004;
    static final int ACC_STATIC = 0x0008;
    private static final int ACC_SUPER = 0x0020;
    private static final int JAVA_8 = 52;

    private final Pool pool = new Pool();
    private final int thisClass;
    private final int superClass;
    private final List<byte[]> fields = new ArrayList<byte[]>();
    private final List<Code> methods = new ArrayList<Code>();

    /** @param name internal name of the new class, {@code a/b/C} */
    ClassBuilder(String name, String superName) {
        this.thisClass = pool.classRef(name);
        this.superClass = pool.classRef(superName);
    }

    void field(int access, String name, String descriptor) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeShort(access);
            out.writeShort(pool.utf8(name));
            out.writeShort(pool.utf8(descriptor));
            out.writeShort(0);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        fields.add(bytes.toByteArray());
    }

    /** Starts an instance method; its locals are {@code this} plus the descriptor's parameters. */
    Code method(int access, String name, String descriptor) {
        Code code = new Code(access, name, descriptor, 1 + argumentSlots(descriptor));
        methods.add(code);
        return code;
    }

    byte[] toByteArray() {
        int codeName = pool.utf8("Code");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            // Methods first: they add their names and descriptors to the pool.
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream methodOut = new DataOutputStream(body);
            for (Code method : methods) {
                method.write(methodOut, codeName);
            }
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(JAVA_8);
            pool.write(out);
            out.writeShort(ACC_PUBLIC | ACC_SUPER);
            out.writeShort(thisClass);
            out.writeShort(superClass);
            out.writeShort(0);
            out.writeShort(fields.size());
            for (byte[] field : fields) {
                out.write(field);
            }
            out.writeShort(methods.size());
            out.write(body.toByteArray());
            out.writeShort(0);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return bytes.toByteArray();
    }

    // ---- descriptors --------------------------------------------------------------------------

    static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return internalName(type);
        }
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        return "L" + internalName(type) + ";";
    }

    static String descriptor(Method method) {
        return parameters(method.getParameterTypes()) + descriptor(method.getReturnType());
    }

    static String descriptor(Constructor<?> constructor) {
        return parameters(constructor.getParameterTypes()) + "V";
    }

    private static String parameters(Class<?>[] types) {
        StringBuilder text = new StringBuilder("(");
        for (Class<?> type : types) {
            text.append(descriptor(type));
        }
        return text.append(')').toString();
    }

    private static int slots(Class<?> type) {
        return type == void.class ? 0 : type == long.class || type == double.class ? 2 : 1;
    }

    /** Stack slots taken by a method descriptor's parameters. */
    private static int argumentSlots(String descriptor) {
        int slots = 0;
        int i = 1;
        while (descriptor.charAt(i) != ')') {
            char c = descriptor.charAt(i);
            slots += c == 'J' || c == 'D' ? 2 : 1;
            while (descriptor.charAt(i) == '[') {
                i++;
            }
            i = descriptor.charAt(i) == 'L' ? descriptor.indexOf(';', i) + 1 : i + 1;
        }
        return slots;
    }

    private static int returnSlots(String descriptor) {
        char c = descriptor.charAt(descriptor.indexOf(')') + 1);
        return c == 'V' ? 0 : c == 'J' || c == 'D' ? 2 : 1;
    }

    private static int fieldSlots(String descriptor) {
        char c = descriptor.charAt(0);
        return c == 'J' || c == 'D' ? 2 : 1;
    }

    // ---- code ---------------------------------------------------------------------------------

    /** One method's straight-line bytecode. */
    final class Code {
        private final int access;
        private final String name;
        private final String descriptor;
        private final int maxLocals;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int stack;
        private int maxStack;

        private Code(int access, String name, String descriptor, int maxLocals) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.maxLocals = maxLocals;
        }

        /** Pushes local {@code slot}, typed by {@code type}. */
        Code load(Class<?> type, int slot) {
            int opcode;
            if (type == long.class) {
                opcode = 0x16;
            } else if (type == float.class) {
                opcode = 0x17;
            } else if (type == double.class) {
                opcode = 0x18;
            } else if (type.isPrimitive()) {
                opcode = 0x15;
            } else {
                opcode = 0x19;
            }
            op(opcode, slots(type));
            bytes.write(slot);
            return this;
        }

        Code loadThis() {
            return load(Object.class, 0);
        }

        /** Pushes every parameter of this method, in order, after {@code this}. */
        Code loadParameters(Class<?>[] types) {
            int slot = 1;
            for (Class<?> type : types) {
                load(type, slot);
                slot += slots(type);
            }
            return this;
        }

        Code getStatic(String owner, String field, String fieldDescriptor) {
            op(0xB2, fieldSlots(fieldDescriptor));
            u2(pool.member(9, owner, field, fieldDescriptor));
            return this;
        }

        Code invokeSpecial(String owner, String method, String methodDescriptor) {
            return invoke(0xB7, 10, owner, method, methodDescriptor, true);
        }

        Code invokeStatic(String owner, String method, String methodDescriptor) {
            return invoke(0xB8, 10, owner, method, methodDescriptor, false);
        }

        Code invokeInterface(String owner, String method, String methodDescriptor) {
            invoke(0xB9, 11, owner, method, methodDescriptor, true);
            bytes.write(1 + argumentSlots(methodDescriptor));
            bytes.write(0);
            return this;
        }

        private Code invoke(int opcode, int tag, String owner, String method, String methodDescriptor, boolean receiver) {
            op(opcode, returnSlots(methodDescriptor) - argumentSlots(methodDescriptor) - (receiver ? 1 : 0));
            u2(pool.member(tag, owner, method, methodDescriptor));
            return this;
        }

        Code checkCast(String internalName) {
            op(0xC0, 0);
            u2(pool.classRef(internalName));
            return this;
        }

        Code ldc(float value) {
            op(0x13, 1);
            u2(pool.floatConstant(value));
            return this;
        }

        Code i2f() {
            return op(0x86, 0);
        }

        Code fmul() {
            return op(0x6A, -1);
        }

        Code fadd() {
            return op(0x62, -1);
        }

        /** Returns the value on the stack (or nothing) as {@code type}. */
        void returnValue(Class<?> type) {
            if (type == void.class) {
                op(0xB1, 0);
            } else if (type == long.class) {
                op(0xAD, -2);
            } else if (type == float.class) {
                op(0xAE, -1);
            } else if (type == double.class) {
                op(0xAF, -2);
            } else if (type.isPrimitive()) {
                op(0xAC, -1);
            } else {
                op(0xB0, -1);
            }
        }

        private Code op(int opcode, int stackChange) {
            bytes.write(opcode);
            stack += stackChange;
            maxStack = Math.max(maxStack, stack);
            return this;
        }

        private void u2(int value) {
            bytes.write(value >>> 8);
            bytes.write(value);
        }

        private void write(DataOutputStream out, int codeName) throws IOException {
            byte[] code = bytes.toByteArray();
            out.writeShort(access);
            out.writeShort(pool.utf8(name));
            out.writeShort(pool.utf8(descriptor));
            out.writeShort(1);
            out.writeShort(codeName);
            out.writeInt(2 + 2 + 4 + code.length + 2 + 2);
            out.writeShort(maxStack);
            out.writeShort(maxLocals);
            out.writeInt(code.length);
            out.write(code);
            out.writeShort(0);
            out.writeShort(0);
        }
    }

    // ---- constant pool ------------------------------------------------------------------------

    /** Deduplicating constant pool; indexes start at 1. */
    private static final class Pool {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);
        private final Map<String, Integer> indexes = new HashMap<String, Integer>();
        private int next = 1;

        int utf8(String value) {
            Integer existing = indexes.get("U" + value);
            if (existing != null) {
                return existing;
            }
            try {
                out.writeByte(1);
                out.writeUTF(value);
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return register("U" + value);
        }

        int classRef(String internalName) {
            return entry("C" + internalName, 7, utf8(internalName), -1);
        }

        int floatConstant(float value) {
            Integer existing = indexes.get("F" + Float.floatToIntBits(value));
            if (existing != null) {
                return existing;
            }
            try {
                out.writeByte(4);
                out.writeInt(Float.floatToIntBits(value));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return register("F" + Float.floatToIntBits(value));
        }

        /** A field (tag 9), method (10) or interface method (11) reference. */
        int member(int tag, String owner, String name, String descriptor) {
            int nameAndType = entry("N" + name + " " + descriptor, 12, utf8(name), utf8(descriptor));
            return entry(tag + owner + "." + name + " " + descriptor, tag, classRef(owner), nameAndType);
        }

        private int entry(String key, int tag, int first, int second) {
            Integer existing = indexes.get(key);
            if (existing != null) {
                return existing;
            }
            try {
                out.writeByte(tag);
                out.writeShort(first);
                if (second >= 0) {
                    out.writeShort(second);
                }
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return register(key);
        }

        private int register(String key) {
            int index = next++;
            indexes.put(key, index);
            return index;
        }

        void write(DataOutputStream target) throws IOException {
            target.writeShort(next);
            target.write(bytes.toByteArray());
        }
    }
}
