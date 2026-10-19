package io.github.thelinuxseal.seal.runtime;

/**
 * Strings on the JVM (docs/JVM.md §8.2): a Seal string is a java.lang.String, and these are the
 * library methods marked {@code @jvm("Strings.name")} in stdlib/string.seal, Double.seal and
 * Float.seal, whose Seal bodies use the VM's memory. Each does what its Seal body does; checks
 * that throw Seal exceptions stay in the Seal code around them. A method that changes the string
 * being made in Seal (put, room) returns the new String here.
 */
public final class Strings {

    private Strings() {
    }

    /** The character at i, which the caller has checked is in range. */
    public static char at(String s, int i) {
        return s.charAt(i);
    }

    public static String put(String s, char c) {
        return s + c;
    }

    public static String put(String s, String more) {
        return s.concat(more);
    }

    /** Making room in a string's buffer: nothing to do for a String. */
    public static String room(String s, int n) {
        return s;
    }

    public static String substring(String s, int from, int to) {
        return s.substring(from, to);
    }

    /** As Seal's Char.toUpper: only a to z change. */
    public static String toUpper(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] >= 'a' && cs[i] <= 'z') {
                cs[i] = (char) (cs[i] - 'a' + 'A');
            }
        }
        return new String(cs);
    }

    /** As Seal's Char.toLower: only A to Z change. */
    public static String toLower(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] >= 'A' && cs[i] <= 'Z') {
                cs[i] = (char) (cs[i] - 'A' + 'a');
            }
        }
        return new String(cs);
    }

    public static String replace(String s, char old, char replacement) {
        return s.replace(old, replacement);
    }

    public static String repeat(String s, int n) {
        return s.repeat(n);
    }

    public static boolean equals(String a, String b) {
        return a.equals(b);
    }

    /** Negative, zero or positive, from the first differing character or else the lengths, as the VM's. */
    public static int compare(String a, String b) {
        return a.compareTo(b);
    }

    public static String of(double x) {
        return Double.toString(x);
    }

    public static String of(float x) {
        return Float.toString(x);
    }
}
