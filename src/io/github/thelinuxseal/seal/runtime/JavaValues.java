package io.github.thelinuxseal.seal.runtime;

import java.util.Arrays;

/**
 * Seal values into Java and back (docs/JAVA.md §3.1): what seal.java's `of` methods do, and the
 * copies a cast makes of a Java array. A Seal number already is the Java primitive of its values
 * (an int a Java int), and a string a java.lang.String, so most `of`s give back what they get.
 */
public final class JavaValues {

    private JavaValues() {
    }

    public static int intOf(int x) {
        return x;
    }

    public static long longOf(long x) {
        return x;
    }

    /** A short is held as an int, as Seal's int<<16>> is. */
    public static int shortOf(int x) {
        return x;
    }

    public static int byteOf(int x) {
        return x;
    }

    public static float floatOf(float x) {
        return x;
    }

    public static double doubleOf(double x) {
        return x;
    }

    public static char charOf(char x) {
        return x;
    }

    public static boolean booleanOf(boolean x) {
        return x;
    }

    public static String stringOf(String s) {
        return s;
    }

    /** An object of a Seal class, which is a JVM object already. */
    public static Object objectOf(Object o) {
        return o;
    }

    /** A Java object's hash for Seal's HashMap: by identity, as Seal's == compares Java objects. */
    public static long hash(Object o) {
        return System.identityHashCode(o);
    }

    public static Integer intBoxed(int x) {
        return x;
    }

    public static Long longBoxed(long x) {
        return x;
    }

    public static Short shortBoxed(int x) {
        return (short) x;
    }

    public static Byte byteBoxed(int x) {
        return (byte) x;
    }

    public static Float floatBoxed(float x) {
        return x;
    }

    public static Double doubleBoxed(double x) {
        return x;
    }

    public static Character charBoxed(char x) {
        return x;
    }

    public static Boolean booleanBoxed(boolean x) {
        return x;
    }

    // ---------------------------------------------------------------- Seal arrays into Java, copied

    /** An array<int<<8>>>, or an array<uint<<8>>> by its bits, as a byte[]. */
    public static byte[] bytesIn(int[] xs) {
        byte[] out = new byte[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = (byte) xs[i];
        }
        return out;
    }

    public static short[] shortsIn(int[] xs) {
        short[] out = new short[xs.length];
        for (int i = 0; i < xs.length; i++) {
            out[i] = (short) xs[i];
        }
        return out;
    }

    public static int[] intsIn(int[] xs) {
        return xs.clone();
    }

    public static long[] longsIn(long[] xs) {
        return xs.clone();
    }

    public static float[] floatsIn(float[] xs) {
        return xs.clone();
    }

    public static double[] doublesIn(double[] xs) {
        return xs.clone();
    }

    public static char[] charsIn(char[] xs) {
        return xs.clone();
    }

    public static boolean[] booleansIn(boolean[] xs) {
        return xs.clone();
    }

    public static String[] stringsIn(String[] xs) {
        return xs.clone();
    }

    public static Object[] objectsIn(Object[] xs) {
        return Arrays.copyOf(xs, xs.length, Object[].class);
    }

    // ---------------------------------------------------------------- Java arrays into Seal, copied

    public static int[] bytesOut(byte[] a) {
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i];
        }
        return out;
    }

    /** A byte[] as an array<uint<<8>>>: each byte's bits, 0 to 255. */
    public static int[] unsignedBytesOut(byte[] a) {
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] & 0xff;
        }
        return out;
    }

    public static int[] shortsOut(short[] a) {
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i];
        }
        return out;
    }

    public static int[] intsOut(int[] a) {
        return a.clone();
    }

    public static long[] longsOut(long[] a) {
        return a.clone();
    }

    public static float[] floatsOut(float[] a) {
        return a.clone();
    }

    public static double[] doublesOut(double[] a) {
        return a.clone();
    }

    public static char[] charsOut(char[] a) {
        return a.clone();
    }

    public static boolean[] booleansOut(boolean[] a) {
        return a.clone();
    }

    /** An array of objects, of its own class (a String[] stays one). */
    public static Object[] objectsOut(Object[] a) {
        return a.clone();
    }
}
