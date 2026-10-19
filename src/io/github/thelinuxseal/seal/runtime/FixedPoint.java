package io.github.thelinuxseal.seal.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Fixed point for compiled programs (docs/JVM.md §3.2). A fixed<<N, M>> is its scaled integer,
 * value × 2^M, held as an int or a long as an int<<N + M>> would be; these are the operations that
 * aren't integer arithmetic, computed exactly as the VM computes them. The int versions take
 * values normalized to their width: sign-extended when signed, zero-extended when not.
 */
public final class FixedPoint {

    private FixedPoint() {
    }

    /** round(a * b / 2^frac), ties up, from the exact product; not yet wrapped to the type's width. */
    public static long mul(long a, long b, int frac, boolean signed) {
        long lim = 1L << 31;
        if (a > -lim && a < lim && b > -lim && b < lim) {
            long p = a * b;
            return frac == 0 ? p : (p >> frac) + ((p >> (frac - 1)) & 1);
        }
        long lo = a * b;
        if (frac == 0) {
            return lo;
        }
        if (frac < 0 || frac > 64) {
            BigInteger p = big(a, signed).multiply(big(b, signed));
            return p.shiftRight(frac).add(p.testBit(frac - 1) ? BigInteger.ONE : BigInteger.ZERO).longValue();
        }
        // The 128-bit product's bits from frac up, and the bit below them for rounding.
        long hi = signed ? Math.multiplyHigh(a, b) : Math.unsignedMultiplyHigh(a, b);
        if (frac == 64) {
            return hi + (lo >>> 63);
        }
        return ((hi << (64 - frac)) | (lo >>> frac)) + ((lo >>> (frac - 1)) & 1);
    }

    public static long mul(int a, int b, int frac, boolean signed) {
        return mul(widen(a, signed), widen(b, signed), frac, signed);
    }

    /** round(a * 2^frac / b), ties up; division by zero stops the program, as on the VM. */
    public static long div(long a, long b, int frac, boolean signed) {
        if (b == 0) {
            throw new Trap("division by zero");
        }
        // floor((2 * a * 2^frac + b) / (2 * b)) rounds a * 2^frac / b to nearest, ties up.
        int s = frac + 1;
        if (s > 0 && s < 61 && (signed || (a >= 0 && b >= 0))) {
            long lim = 1L << (61 - s);
            long blim = 1L << 59;
            if (a > -lim && a < lim && b > -blim && b < blim) {
                return Math.floorDiv((a << s) + b, b << 1);
            }
        }
        BigInteger n = big(a, signed).shiftLeft(s);
        BigInteger d = big(b, signed).shiftLeft(1);
        BigInteger[] qr = n.add(big(b, signed)).divideAndRemainder(d);
        BigInteger q = qr[1].signum() != 0 && qr[1].signum() != d.signum() ? qr[0].subtract(BigInteger.ONE) : qr[0];
        return q.longValue();
    }

    public static long div(int a, int b, int frac, boolean signed) {
        return div(widen(a, signed), widen(b, signed), frac, signed);
    }

    /** Multiplies by 2^shift; for negative shifts divides, rounding to nearest (ties up) or toward zero. */
    public static long rescale(long v, int shift, boolean signed, boolean nearest) {
        if (shift >= 0) {
            return shift >= 64 ? 0 : v << shift;
        }
        int k = -shift;
        if (k >= 64) {
            // The value is below 1 in magnitude: toward zero gives 0, and rounding to nearest
            // gives 1 only for an unsigned value of at least one half (its top bit set, k == 64).
            return nearest && !signed && k == 64 ? (v >>> 63) & 1 : 0;
        }
        if (nearest) {
            return signed ? (v >> k) + ((v >> (k - 1)) & 1) : (v >>> k) + ((v >>> (k - 1)) & 1);
        }
        if (!signed) {
            return v >>> k;
        }
        // Toward zero: bias negative values by 2^k - 1 before the arithmetic shift.
        return (v + ((v >> 63) & ((1L << k) - 1))) >> k;
    }

    /** The exact value as decimal text, as print shows it: 2.75, -0.125, 3.0 (the library's Fixed.toString). */
    public static String text(long raw, int frac, boolean signed) {
        BigDecimal exact = exact(raw, frac, signed).stripTrailingZeros();
        String s = exact.toPlainString();
        return exact.scale() <= 0 ? s + ".0" : s;
    }

    public static String text(long raw, int frac) {
        return text(raw, frac, true);
    }

    public static String textUnsigned(long raw, int frac) {
        return text(raw, frac, false);
    }

    /** The value rounded to that many places, exactly (ties up, as Java's %f): 2.50 for %.2f (Format's). */
    public static String places(long raw, int places, int frac) {
        return exact(raw, frac, true).setScale(places, RoundingMode.HALF_UP).toPlainString();
    }

    public static String placesUnsigned(long raw, int places, int frac) {
        return exact(raw, frac, false).setScale(places, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal exact(long raw, int frac, boolean signed) {
        return new BigDecimal(big(raw, signed)).divide(new BigDecimal(BigInteger.ONE.shiftLeft(frac)));
    }

    private static long widen(int v, boolean signed) {
        return signed ? v : v & 0xffffffffL;
    }

    private static BigInteger big(long v, boolean signed) {
        BigInteger b = BigInteger.valueOf(v);
        return signed || v >= 0 ? b : b.add(BigInteger.ONE.shiftLeft(64));
    }
}
