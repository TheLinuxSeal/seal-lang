package io.github.thelinuxseal.seal.runtime;

/**
 * Integer helpers for compiled programs (docs/JVM.md §3.1). A value of a width W below the JVM's
 * own (32 for an int, 64 for a long) is kept normalized: sign-extended when signed, zero-extended
 * when unsigned. A uint<<32>> or uint<<64>> keeps its raw bits.
 */
public final class Ints {

    private Ints() {
    }

    /** v wrapped to width bits (1 to 32) and normalized. */
    public static int trunc(int v, int width, boolean signed) {
        if (width >= 32) {
            return v;
        }
        return signed ? (v << (32 - width)) >> (32 - width) : v & ((1 << width) - 1);
    }

    /** v wrapped to width bits (1 to 64) and normalized. */
    public static long trunc(long v, int width, boolean signed) {
        if (width >= 64) {
            return v;
        }
        return signed ? (v << (64 - width)) >> (64 - width) : v & ((1L << width) - 1);
    }

    /**
     * Seal's single shift operator, {@code v <<< amount} (§50), on a value of width bits held in an
     * int: left for a positive amount, right for a negative one (arithmetic for a signed type); an
     * amount of the width or more shifts everything out.
     */
    public static int shift(int v, int amount, int width, boolean signed) {
        if (amount >= 0) {
            return amount >= width ? 0 : trunc(v << amount, width, signed);
        }
        if (amount == Integer.MIN_VALUE || -amount >= width) {
            return signed && v < 0 ? -1 : 0;
        }
        return signed ? v >> -amount : v >>> -amount;
    }

    /** As {@link #shift(int, int, int, boolean)}, for a value held in a long. */
    public static long shift(long v, int amount, int width, boolean signed) {
        if (amount >= 0) {
            return amount >= width ? 0 : trunc(v << amount, width, signed);
        }
        if (amount == Integer.MIN_VALUE || -amount >= width) {
            return signed && v < 0 ? -1 : 0;
        }
        return signed ? v >> -amount : v >>> -amount;
    }

    /** A fixed array's index, checked: out of range stops the program, as the VM's traps do (§21.1). */
    public static int bounds(int i, int n) {
        if (i < 0 || i >= n) {
            throw new Trap("index " + i + " is out of bounds for length " + n);
        }
        return i;
    }

    /** A uint<<64>> as a double, rounded as the VM does. */
    public static double unsignedToDouble(long v) {
        return v >= 0 ? (double) v : (double) (v >>> 1) * 2.0 + (v & 1);
    }
}
