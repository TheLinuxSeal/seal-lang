package io.github.thelinuxseal.seal.runtime;

import java.util.Arrays;

/**
 * array<T>'s sort() of numbers on the JVM (docs/JVM.md §8.1): an array of integers, chars or
 * fixed-point numbers sorted with Java's Arrays.sort, where the library would sort it with its own
 * introsort. Both give the same array, since numbers that are equal are the same. A value whose
 * width is the Java array's own (a uint in an int[], a uint<<64>> in a long[]) is compared without
 * its sign: its sign bit flipped, the array sorted, and flipped back.
 */
public final class Sorting {

    private Sorting() {
    }

    public static void sort(int[] a, int from, int to) {
        Arrays.sort(a, from, to);
    }

    public static void sort(long[] a, int from, int to) {
        Arrays.sort(a, from, to);
    }

    public static void sort(char[] a, int from, int to) {
        Arrays.sort(a, from, to);
    }

    public static void sortUnsigned(int[] a, int from, int to) {
        for (int i = from; i < to; i++) {
            a[i] ^= Integer.MIN_VALUE;
        }
        Arrays.sort(a, from, to);
        for (int i = from; i < to; i++) {
            a[i] ^= Integer.MIN_VALUE;
        }
    }

    public static void sortUnsigned(long[] a, int from, int to) {
        for (int i = from; i < to; i++) {
            a[i] ^= Long.MIN_VALUE;
        }
        Arrays.sort(a, from, to);
        for (int i = from; i < to; i++) {
            a[i] ^= Long.MIN_VALUE;
        }
    }
}
