package io.github.thelinuxseal.seal.runtime;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What the library's seal.java does on the JVM (docs/JAVA.md): JavaBridge, JavaClass and
 * JavaObject call these with the object or class first. A JavaObject is the Java object itself,
 * and a JavaClass its java.lang.Class; a Seal value passed in by JavaInt.of and the others is a
 * {@link JavaPrimitive}, unwrapped wherever it meets Java.
 *
 * <p>Classes are found by name through the class loader of the Seal class calling in. Methods,
 * constructors and fields are the public ones, reached through public classes; a method is chosen
 * as Java chooses among overloads (§4), and the choice is kept for the next call with the same
 * kinds of arguments.
 *
 * <p>What goes wrong throws one of seal.java's exceptions (§7), which this class can't name, as
 * they are compiled with each program: it makes them by name through the program's class loader.
 * What the Java code throws comes out in a JavaThrownException, and a Seal exception that went
 * through Java as itself. A null JavaObject or JavaClass stops the program, as calling a method of
 * a null Seal object does.
 */
public final class JavaBridge {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /** Where seal.java's classes are on the JVM, and the root of Seal's exceptions. */
    private static final String SEAL_JAVA = "io.github.thelinuxseal.seal.java.";
    private static final String SEAL_EXCEPTION = "io.github.thelinuxseal.seal.lang.Exception";
    private static final Object[] NONE = {};
    /** The method or constructor chosen for a class, a name and the kinds of the arguments (§4). */
    private static final Map<List<Object>, Executable> CHOSEN = new ConcurrentHashMap<>();
    private static final List<Class<?>> PRIMITIVES = List.of(byte.class, short.class, char.class, int.class, long.class,
            float.class, double.class, boolean.class);
    private static final List<Class<?>> BOXES = List.of(Byte.class, Short.class, Character.class, Integer.class, Long.class,
            Float.class, Double.class, Boolean.class);

    private JavaBridge() {
    }

    // ================================================================ JavaBridge

    /** JavaBridge.get(name): the class so named, as the calling Seal class's loader finds it. */
    public static Object get(String name) {
        return find(name, WALKER.getCallerClass());
    }

    /** JavaBridge.create(name): a new object, by the constructor that takes no arguments. */
    public static Object createNamed(String name) {
        return construct(find(name, WALKER.getCallerClass()), NONE);
    }

    /** JavaBridge.create(name, args): a new object, by the constructor the arguments choose. */
    public static Object createNamed(String name, Object[] args) {
        return construct(find(name, WALKER.getCallerClass()), args);
    }

    private static Class<?> find(String name, Class<?> caller) {
        try {
            return Class.forName(name, false, caller.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw fail("JavaClassNotFoundException", "no Java class " + name + (name.indexOf('.') < 0 ? "; name it in full, as java.util.ArrayList"
                    : name.contains("/") ? "; name it with dots, as java.util.ArrayList" : ""));
        }
    }

    // ================================================================ JavaClass

    public static Object invokeStatic(Object cls, String method) {
        return invokeStatic(cls, method, NONE);
    }

    public static Object invokeStatic(Object cls, String method, Object[] args) {
        Class<?> c = asClass(cls, "invokeStatic(\"" + method + "\")");
        return call((Method) choose(c, method, true, args), null, args);
    }

    public static Object create(Object cls) {
        return construct(asClass(cls, "create()"), NONE);
    }

    public static Object create(Object cls, Object[] args) {
        return construct(asClass(cls, "create(...)"), args);
    }

    public static Object getStatic(Object cls, String field) {
        Class<?> c = asClass(cls, "getStatic(\"" + field + "\")");
        Field f = field(c, field, true);
        try {
            return f.get(null);
        } catch (IllegalAccessException e) {
            throw fail("JavaException", "can't read " + c.getName() + "." + field + ": " + e.getMessage());
        }
    }

    public static void setStatic(Object cls, String field, Object value) {
        Class<?> c = asClass(cls, "setStatic(\"" + field + "\", ...)");
        store(field(c, field, true), null, value);
    }

    public static String name(Object cls) {
        return asClass(cls, "name()").getName();
    }

    public static boolean isInstance(Object cls, Object o) {
        return asClass(cls, "isInstance(...)").isInstance(unwrap(o));
    }

    private static Class<?> asClass(Object cls, String what) {
        if (cls == null) {
            throw new Trap(what + " on a null JavaClass");
        }
        return (Class<?>) cls;
    }

    // ================================================================ JavaObject

    public static Object invoke(Object target, String method) {
        return invoke(target, method, NONE);
    }

    public static Object invoke(Object target, String method, Object[] args) {
        Object t = target(target, "invoke(\"" + method + "\")");
        return call((Method) choose(t.getClass(), method, false, args), t, args);
    }

    public static Object getField(Object target, String field) {
        Object t = target(target, "getField(\"" + field + "\")");
        Field f = field(t.getClass(), field, false);
        try {
            return f.get(t);
        } catch (IllegalAccessException e) {
            throw fail("JavaException", "can't read " + t.getClass().getName() + "." + field + ": " + e.getMessage());
        }
    }

    public static void setField(Object target, String field, Object value) {
        Object t = target(target, "setField(\"" + field + "\", ...)");
        store(field(t.getClass(), field, false), t, value);
    }

    /** javaClass(): the object's class; a number passed in is its box's, as Java would box it. */
    public static Object javaClass(Object o) {
        return target(o, "javaClass()").getClass();
    }

    /** hash(): by identity, as == compares JavaObjects. */
    public static long hash(Object o) {
        return Natives.identity_hash(o);
    }

    /** A JavaObject's text, its cast to string: Java's String.valueOf, so a String is itself and null is "null". */
    public static String text(Object o) {
        return String.valueOf(unwrap(o));
    }

    private static Object target(Object o, String what) {
        if (o == null) {
            throw new Trap(what + " on a null JavaObject");
        }
        return unwrap(o);
    }

    // ================================================================ Seal values in (§3.1)

    public static Object of(Object o) {
        return o;
    }

    public static Object ofString(String s) {
        return s;
    }

    public static Object ofByte(int x) {
        return new JavaPrimitive(byte.class, (byte) x);
    }

    public static Object ofShort(int x) {
        return new JavaPrimitive(short.class, (short) x);
    }

    public static Object ofInt(int x) {
        return new JavaPrimitive(int.class, x);
    }

    public static Object ofLong(long x) {
        return new JavaPrimitive(long.class, x);
    }

    public static Object ofFloat(float x) {
        return new JavaPrimitive(float.class, x);
    }

    public static Object ofDouble(double x) {
        return new JavaPrimitive(double.class, x);
    }

    public static Object ofChar(char c) {
        return new JavaPrimitive(char.class, c);
    }

    public static Object ofBoolean(boolean b) {
        return new JavaPrimitive(boolean.class, b);
    }

    // ================================================================ Java values out (§3.2)

    public static int toInt(Object o) {
        return unwrap(o) instanceof Character c ? c : number(o, "int").intValue();
    }

    public static long toLong(Object o) {
        return unwrap(o) instanceof Character c ? c : number(o, "int<<64>>").longValue();
    }

    public static double toDouble(Object o) {
        return unwrap(o) instanceof Character c ? c : number(o, "double").doubleValue();
    }

    public static float toFloat(Object o) {
        return unwrap(o) instanceof Character c ? c : number(o, "float").floatValue();
    }

    public static char toChar(Object o) {
        if (unwrap(o) instanceof Character c) {
            return c;
        }
        throw fail("JavaCastException", cannotCast(o, "char", "a Character"));
    }

    public static boolean toBit(Object o) {
        if (unwrap(o) instanceof Boolean b) {
            return b;
        }
        throw fail("JavaCastException", cannotCast(o, "bit", "a Boolean"));
    }

    private static Number number(Object o, String to) {
        if (unwrap(o) instanceof Number n) {
            return n;
        }
        throw fail("JavaCastException", cannotCast(o, to, "a Number"));
    }

    private static String cannotCast(Object o, String to, String needs) {
        Object v = unwrap(o);
        return "cannot cast " + (v == null ? "null" : "a " + v.getClass().getTypeName()) + " to " + to + ": it takes " + needs;
    }

    // ================================================================ Java arrays (§3.1)

    // A Seal array goes in as a new Java array of its own: an array<T> is a unique, which nothing else
    // may hold. An array of int<<8>> or uint<<8>> is an int[] on the JVM, and becomes a byte[].

    public static Object arrayOfBytes(int[] xs) {
        byte[] out = new byte[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = (byte) xs[i];
        }
        return out;
    }

    public static Object arrayOfShorts(int[] xs) {
        short[] out = new short[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = (short) xs[i];
        }
        return out;
    }

    public static Object arrayOfInts(int[] xs) {
        return xs.clone();
    }

    public static Object arrayOfLongs(long[] xs) {
        return xs.clone();
    }

    public static Object arrayOfFloats(float[] xs) {
        return xs.clone();
    }

    public static Object arrayOfDoubles(double[] xs) {
        return xs.clone();
    }

    public static Object arrayOfChars(char[] xs) {
        return xs.clone();
    }

    public static Object arrayOfBooleans(boolean[] xs) {
        return xs.clone();
    }

    public static Object arrayOfStrings(String[] xs) {
        return xs.clone();
    }

    public static Object arrayOfObjects(Object[] xs) {
        Object[] out = new Object[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = unwrap(xs[i]);
        }
        return out;
    }

    /** JavaArray.of(elementClass, xs): an array of that class, as the calling Seal class's loader finds it. */
    public static Object arrayOfClass(String elementClass, Object[] xs) {
        Class<?> type = elementType(elementClass, WALKER.getCallerClass());
        Object out = Array.newInstance(type, xs.length);
        for (int i = 0; i < xs.length; i++) {
            Object v = unwrap(xs[i]);
            try {
                Array.set(out, i, v);
            } catch (IllegalArgumentException e) {
                throw fail("JavaCastException", "element " + i + " of an array of " + type.getName() + " can't be "
                        + (v == null ? "null" : "a " + v.getClass().getName()));
            }
        }
        return out;
    }

    /** JavaArray.create(elementClass, length): a new array of Java's defaults. */
    public static Object newArray(String elementClass, int length) {
        Class<?> type = elementType(elementClass, WALKER.getCallerClass());
        if (length < 0) {
            throw fail("JavaException", "an array of " + type.getName() + " can't have the length " + length);
        }
        return Array.newInstance(type, length);
    }

    /** A class by name, or a primitive type by Java's name for it ("int"). */
    private static Class<?> elementType(String name, Class<?> caller) {
        for (Class<?> p : PRIMITIVES) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return find(name, caller);
    }

    // Casts copy a Java array out, as a new Seal array.

    public static int[] toBytes(Object o) {
        byte[] xs = array(o, byte[].class, "array<int<<8>>>", "a byte[]");
        int[] out = new int[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = xs[i];
        }
        return out;
    }

    public static int[] toUnsignedBytes(Object o) {
        byte[] xs = array(o, byte[].class, "array<uint<<8>>>", "a byte[]");
        int[] out = new int[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = xs[i] & 0xff;
        }
        return out;
    }

    public static int[] toShorts(Object o) {
        short[] xs = array(o, short[].class, "array<int<<16>>>", "a short[]");
        int[] out = new int[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = xs[i];
        }
        return out;
    }

    public static int[] toInts(Object o) {
        return array(o, int[].class, "array<int>", "an int[]").clone();
    }

    public static long[] toLongs(Object o) {
        return array(o, long[].class, "array<int<<64>>>", "a long[]").clone();
    }

    public static float[] toFloats(Object o) {
        return array(o, float[].class, "array<float>", "a float[]").clone();
    }

    public static double[] toDoubles(Object o) {
        return array(o, double[].class, "array<double>", "a double[]").clone();
    }

    public static char[] toChars(Object o) {
        return array(o, char[].class, "array<char>", "a char[]").clone();
    }

    public static boolean[] toBooleans(Object o) {
        return array(o, boolean[].class, "array<bit>", "a boolean[]").clone();
    }

    /** (array<string>): an array whose elements are all Strings (a String[], or an Object[] such as List.toArray gives). */
    public static String[] toStrings(Object o) {
        Object[] xs = array(o, Object[].class, "array<string>", "an array of Strings");
        String[] out = new String[xs.length];
        for (int i = 0; i < xs.length; i++) {
            if (!(xs[i] instanceof String s)) {
                throw fail("JavaCastException", "cannot cast to array<string> an array whose element " + i + " is "
                        + (xs[i] == null ? "null" : "a " + xs[i].getClass().getName()) + ": it takes Strings");
            }
            out[i] = s;
        }
        return out;
    }

    /** (array<JavaObject>): any Java array, a primitive one's elements boxed. */
    public static Object[] toObjects(Object o) {
        Object v = unwrap(o);
        if (v == null || !v.getClass().isArray()) {
            throw fail("JavaCastException", cannotCast(o, "array<JavaObject>", "a Java array"));
        }
        Object[] out = new Object[Array.getLength(v)];
        for (int i = 0; i < out.length; i++) {
            out[i] = Array.get(v, i);
        }
        return out;
    }

    private static <A> A array(Object o, Class<A> type, String to, String needs) {
        Object v = unwrap(o);
        if (!type.isInstance(v)) {
            throw fail("JavaCastException", cannotCast(o, to, needs));
        }
        return type.cast(v);
    }

    // ================================================================ choosing (§4)

    private static Object unwrap(Object o) {
        return o instanceof JavaPrimitive p ? p.box() : o;
    }

    /** What an argument is to overload resolution: a primitive type, a class, or null for null. */
    private static Class<?> kind(Object arg) {
        return arg == null ? null : arg instanceof JavaPrimitive p ? p.type() : arg.getClass();
    }

    /**
     * The public method (or, with a null name, constructor) of c the arguments choose, as Java
     * chooses (JLS 15.12.2): those that take them without boxing, else with it, else through
     * varargs; of those, the most specific.
     */
    private static Executable choose(Class<?> c, String name, boolean isStatic, Object[] args) {
        List<Object> key = new ArrayList<>(args.length + 3);
        key.add(c);
        key.add(name);
        key.add(isStatic);
        for (Object a : args) {
            key.add(kind(a));
        }
        Executable done = CHOSEN.get(key);
        if (done != null) {
            return done;
        }
        Class<?>[] kinds = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            kinds[i] = kind(args[i]);
        }
        List<Executable> all = name == null ? constructors(c) : methods(c, name, isStatic);
        Executable chosen = null;
        for (int phase = 1; phase <= 3 && chosen == null; phase++) {
            List<Executable> fit = new ArrayList<>();
            for (Executable e : all) {
                if (applies(e, kinds, phase)) {
                    fit.add(e);
                }
            }
            if (!fit.isEmpty()) {
                chosen = mostSpecific(fit, kinds.length, phase, c, name);
            }
        }
        if (chosen == null) {
            throw fail("JavaMethodNotFoundException", noneFits(c, name, isStatic, kinds, all));
        }
        CHOSEN.put(key, chosen);
        return chosen;
    }

    private static List<Executable> constructors(Class<?> c) {
        if (!accessible(c)) {
            throw fail("JavaMethodNotFoundException", "can't create a " + c.getName() + ": the class isn't public");
        }
        if (Modifier.isAbstract(c.getModifiers())) {
            throw fail("JavaMethodNotFoundException", "can't create a " + c.getName() + ": it is "
                    + (c.isInterface() ? "an interface" : "abstract"));
        }
        return List.of(c.getConstructors());
    }

    /** The public methods of c so named, each as a public class or interface has it, so that it can be called. */
    private static List<Executable> methods(Class<?> c, String name, boolean isStatic) {
        List<Executable> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.isBridge() || m.isSynthetic() || Modifier.isStatic(m.getModifiers()) != isStatic) {
                continue;
            }
            Method reachable = accessible(m.getDeclaringClass()) ? m : publicView(c, m);
            if (reachable != null && seen.add(Arrays.toString(reachable.getParameterTypes()))) {
                out.add(reachable);
            }
        }
        return out;
    }

    /** The same method as a public supertype of c declares it: Iterator.hasNext for a private iterator class's. */
    private static Method publicView(Class<?> c, Method m) {
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        todo.add(c);
        while (!todo.isEmpty()) {
            Class<?> t = todo.poll();
            if (!seen.add(t)) {
                continue;
            }
            if (accessible(t)) {
                try {
                    Method found = t.getMethod(m.getName(), m.getParameterTypes());
                    if (accessible(found.getDeclaringClass())) {
                        return found;
                    }
                } catch (NoSuchMethodException e) {
                    // not here; look further up
                }
            }
            if (t.getSuperclass() != null) {
                todo.add(t.getSuperclass());
            }
            todo.addAll(Arrays.asList(t.getInterfaces()));
        }
        return null;
    }

    /** Whether code outside c's module may use c's public members: a public class in an exported package. */
    private static boolean accessible(Class<?> c) {
        for (Class<?> t = c; t != null; t = t.getDeclaringClass()) {
            if (!Modifier.isPublic(t.getModifiers())) {
                return false;
            }
        }
        return c.getModule().isExported(c.getPackageName());
    }

    /** Whether e takes arguments of these kinds: in phase 1 without boxing, in 2 with it, in 3 through varargs. */
    private static boolean applies(Executable e, Class<?>[] kinds, int phase) {
        Class<?>[] ps = e.getParameterTypes();
        if (phase < 3) {
            if (ps.length != kinds.length) {
                return false;
            }
            for (int i = 0; i < ps.length; i++) {
                if (!fits(kinds[i], ps[i], phase == 2)) {
                    return false;
                }
            }
            return true;
        }
        if (!e.isVarArgs() || kinds.length < ps.length - 1) {
            return false;
        }
        for (int i = 0; i < kinds.length; i++) {
            Class<?> p = i < ps.length - 1 ? ps[i] : ps[ps.length - 1].getComponentType();
            if (!fits(kinds[i], p, true)) {
                return false;
            }
        }
        return true;
    }

    /** Whether an argument of this kind goes to a parameter of type p, with or without boxing and unboxing. */
    private static boolean fits(Class<?> kind, Class<?> p, boolean boxing) {
        if (kind == null) {
            return !p.isPrimitive();
        }
        if (kind.isPrimitive()) {
            return p.isPrimitive() ? widens(kind, p) : boxing && p.isAssignableFrom(box(kind));
        }
        if (!p.isPrimitive()) {
            return p.isAssignableFrom(kind);
        }
        Class<?> unboxed = unbox(kind);
        return boxing && unboxed != null && widens(unboxed, p);
    }

    /** Java's widening of primitives (JLS 5.1.2), and identity. */
    private static boolean widens(Class<?> from, Class<?> to) {
        if (from == to) {
            return true;
        }
        if (from == boolean.class || to == boolean.class || to == char.class || to == byte.class) {
            return false;
        }
        if (from == char.class) {
            return to == int.class || to == long.class || to == float.class || to == double.class;
        }
        int f = PRIMITIVES.indexOf(from);
        int t = PRIMITIVES.indexOf(to);
        return f < t && to != char.class; // byte < short < (char) < int < long < float < double
    }

    private static Class<?> box(Class<?> primitive) {
        return BOXES.get(PRIMITIVES.indexOf(primitive));
    }

    private static Class<?> unbox(Class<?> c) {
        int i = BOXES.indexOf(c);
        return i < 0 ? null : PRIMITIVES.get(i);
    }

    /** The one of these that is more specific than the others (JLS 15.12.2.5); several, if they tie, are an error. */
    private static Executable mostSpecific(List<Executable> fit, int args, int phase, Class<?> c, String name) {
        List<Executable> best = new ArrayList<>();
        for (Executable e : fit) {
            boolean beaten = false;
            for (Executable o : fit) {
                if (o != e && moreSpecific(o, e, args, phase) && !moreSpecific(e, o, args, phase)) {
                    beaten = true;
                    break;
                }
            }
            if (!beaten) {
                best.add(e);
            }
        }
        if (best.size() == 1) {
            return best.get(0);
        }
        throw fail("JavaAmbiguousCallException", "the call to " + what(c, name) + " is ambiguous: " + best.stream().map(JavaBridge::signature)
                .collect(Collectors.joining(" and ")) + " both fit; pass arguments of the exact types, as JavaLong.of(x)");
    }

    /** Whether a's parameters are each b's or a subtype of it (int of long too), as far as the call reaches. */
    private static boolean moreSpecific(Executable a, Executable b, int args, int phase) {
        int n = phase == 3 ? Math.max(args, Math.max(a.getParameterCount(), b.getParameterCount())) : args;
        for (int i = 0; i < n; i++) {
            Class<?> pa = parameter(a, i, phase);
            Class<?> pb = parameter(b, i, phase);
            if (pa == null || pb == null) {
                break;
            }
            boolean sub = pa.isPrimitive() && pb.isPrimitive() ? widens(pa, pb)
                    : !pa.isPrimitive() && !pb.isPrimitive() && pb.isAssignableFrom(pa);
            if (!sub) {
                return false;
            }
        }
        return true;
    }

    /** The type of e's i'th parameter, a varargs parameter's elements' in phase 3; null past the end. */
    private static Class<?> parameter(Executable e, int i, int phase) {
        Class<?>[] ps = e.getParameterTypes();
        if (phase == 3 && i >= ps.length - 1) {
            return ps[ps.length - 1].getComponentType();
        }
        return i < ps.length ? ps[i] : null;
    }

    private static String noneFits(Class<?> c, String name, boolean isStatic, Class<?>[] kinds, List<Executable> all) {
        String given = Arrays.stream(kinds).map(k -> k == null ? "null" : k.getName()).collect(Collectors.joining(", ", "(", ")"));
        if (all.isEmpty()) {
            if (name == null) {
                return c.getName() + " has no public constructor";
            }
            boolean other = !methods(c, name, !isStatic).isEmpty();
            return c.getName() + " has no public " + (isStatic ? "static " : "") + "method " + name
                    + (other ? "; it has " + (isStatic ? "an instance" : "a static") + " one: call it with "
                    + (isStatic ? "invoke on an object" : "invokeStatic on the class") : "");
        }
        return "no " + what(c, name) + " takes " + given + "; there " + (all.size() == 1 ? "is " : "are ")
                + all.stream().map(JavaBridge::signature).collect(Collectors.joining(", "));
    }

    private static String what(Class<?> c, String name) {
        return name == null ? "constructor of " + c.getName() : c.getName() + "." + name;
    }

    private static String signature(Executable e) {
        return (e instanceof Constructor ? e.getDeclaringClass().getSimpleName() : e.getName())
                + Arrays.stream(e.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(", ", "(", ")"));
    }

    // ================================================================ calling

    private static Object construct(Class<?> c, Object[] args) {
        Constructor<?> k = (Constructor<?>) choose(c, null, false, args);
        try {
            return k.newInstance(arguments(k, args));
        } catch (InvocationTargetException e) {
            throw thrown(e.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw fail("JavaException", "can't create a " + c.getName() + ": " + e);
        }
    }

    private static Object call(Method m, Object target, Object[] args) {
        try {
            return m.invoke(target, arguments(m, args));
        } catch (InvocationTargetException e) {
            throw thrown(e.getCause());
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw fail("JavaException", "can't call " + m.getDeclaringClass().getName() + "." + m.getName() + ": " + e);
        }
    }

    /** The arguments as reflection takes them: unwrapped, and the varargs ones in an array of their own. */
    private static Object[] arguments(Executable e, Object[] args) {
        Class<?>[] ps = e.getParameterTypes();
        boolean spread = e.isVarArgs() && !(args.length == ps.length && fits(kind(args[args.length - 1]), ps[ps.length - 1], true));
        if (!spread) {
            Object[] out = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                out[i] = unwrap(args[i]);
            }
            return out;
        }
        Object[] out = new Object[ps.length];
        for (int i = 0; i < ps.length - 1; i++) {
            out[i] = unwrap(args[i]);
        }
        Object rest = Array.newInstance(ps[ps.length - 1].getComponentType(), args.length - ps.length + 1);
        for (int i = ps.length - 1; i < args.length; i++) {
            Array.set(rest, i - ps.length + 1, unwrap(args[i]));
        }
        out[ps.length - 1] = rest;
        return out;
    }

    /** The public field of c so named, static or not as asked. */
    private static Field field(Class<?> c, String name, boolean isStatic) {
        Field f;
        try {
            f = c.getField(name);
        } catch (NoSuchFieldException e) {
            throw fail("JavaFieldNotFoundException", c.getName() + " has no public field " + name);
        }
        if (Modifier.isStatic(f.getModifiers()) != isStatic) {
            throw fail("JavaFieldNotFoundException", c.getName() + "." + name + " is " + (isStatic ? "not static: use getField on an object"
                    : "static: use getStatic on the class"));
        }
        if (!accessible(f.getDeclaringClass())) {
            throw fail("JavaFieldNotFoundException", c.getName() + "." + name + " is in a class that isn't public");
        }
        return f;
    }

    private static void store(Field f, Object target, Object value) {
        try {
            f.set(target, unwrap(value));
        } catch (IllegalAccessException e) {
            throw fail("JavaException", "can't set " + f.getDeclaringClass().getName() + "." + f.getName()
                    + (Modifier.isFinal(f.getModifiers()) ? ": it is final" : ": " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            Object v = unwrap(value);
            throw fail("JavaCastException", "can't set " + f.getDeclaringClass().getName() + "." + f.getName() + ", a "
                    + f.getType().getName() + ", to " + (v == null ? "null" : "a " + v.getClass().getName()));
        }
    }

    // ================================================================ exceptions (§7)

    /**
     * One of seal.java's exceptions, made by name through the class loader of the Seal code that
     * called in (the first class on the stack that isn't this one); a Trap if the program was
     * compiled without them.
     */
    private static RuntimeException fail(String kind, String message) {
        try {
            Class<?> c = Class.forName(SEAL_JAVA + kind, true, sealCaller().getClassLoader());
            return (RuntimeException) c.getConstructor(String.class).newInstance(message);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return new Trap(message);
        }
    }

    /**
     * What the Java code threw, as Seal sees it: a Seal exception as itself (a Seal method the
     * bridge called threw it), and an Error or the runtime's own (a trap, an exit) as they are; a
     * Java exception in a JavaThrownException holding it, whose message is Java's text of it.
     */
    private static RuntimeException thrown(Throwable t) {
        if (t instanceof Error || t instanceof Trap || t instanceof Exit || isSeal(t.getClass())) {
            throw JavaBridge.<RuntimeException>rethrow(t);
        }
        try {
            Class<?> c = Class.forName(SEAL_JAVA + "JavaThrownException", true, sealCaller().getClassLoader());
            return (RuntimeException) c.getConstructor(String.class, Object.class).newInstance(t.toString(), t);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw JavaBridge.<RuntimeException>rethrow(t);
        }
    }

    private static boolean isSeal(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(SEAL_EXCEPTION)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> sealCaller() {
        return WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c != JavaBridge.class).findFirst().orElse(JavaBridge.class));
    }

    /** Throws t as it is, checked or not. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T rethrow(Throwable t) throws T {
        throw (T) t;
    }
}
