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

    /**
     * {@code base ** exp} (§41): repeated multiplication, so it wraps as * does, the result's low bits
     * the same for any width. A negative exponent of a signed type is 1 / base ** -exp in integer
     * division: 0, but 1 for a base of 1, ±1 for -1, and division by zero for 0. An unsigned
     * exponent is never negative.
     */
    public static long pow(long base, long exp, boolean signed) {
        if (signed && exp < 0) {
            if (base == 0) {
                throw new Trap("division by zero");
            }
            return base == 1 ? 1 : base == -1 ? ((exp & 1) == 0 ? 1 : -1) : 0;
        }
        long result = 1;
        long b = base;
        for (long e = exp; e != 0; e >>>= 1) {
            if ((e & 1) != 0) {
                result *= b;
            }
            b *= b;
        }
        return result;
    }

    /** As above, for values held in an int: an unsigned one is zero-extended. */
    public static int pow(int base, int exp, boolean signed) {
        return (int) pow(signed ? base : base & 0xffffffffL, signed ? exp : exp & 0xffffffffL, signed);
    }

    /** {@code a ** b} on floating point: StrictMath's, so every backend and the compiler give the same bits. */
    public static double pow(double a, double b) {
        return StrictMath.pow(a, b);
    }

    public static float pow(float a, float b) {
        return (float) StrictMath.pow(a, b); // through a double, as the VM computes float arithmetic
    }

    /** A fixed array's index, checked: out of range stops the program, as the VM's traps do (§21.1). */
    public static int bounds(int i, int n) {
        if (i < 0 || i >= n) {
            throw new Trap("index " + i + " is out of bounds for length " + n);
        }
        return i;
    }

    /** A value becoming a size (§37.2), which holds 0 to 64: anything else stops the program, where other integers wrap. */
    public static int size(long v) {
        if (v < 0 || v > 64) {
            throw new Trap(v + " isn't a size: a size is 0 to 64");
        }
        return (int) v;
    }

    /** As above, for a value held in an int. */
    public static int size(int v) {
        return size((long) v);
    }

    /** A uint<<64>> as a double, rounded to nearest. */
    public static double unsignedToDouble(long v) {
        return v >= 0 ? (double) v : (double) (v >>> 1) * 2.0 + (v & 1);
    }
}
