package io.github.thelinuxseal.seal.runtime;

import java.util.Arrays;

/**
 * Storage for values on the JVM (docs/JVM.md §4.1): structs, uniques, records, enums and fixed
 * arrays live in blocks, laid out as on the VM. A method's frame, an object's struct fields and a
 * class's static ones each have a block, and an array<T> of structs is one. Bits are numbered as
 * on the VM: bit {@code a} is bit {@code a % 64} of word {@code a / 64}.
 *
 * <p>A reference inside a value (a handle, a string, an array<T>) keeps its place in the bits,
 * unused, and is held in {@link #refs} at its offset / 32: every value that holds one is aligned to
 * at least 32 bits, so no two share an index. The garbage collector sees them there.
 */
public final class Block {

    public final long[] bits;
    /** The references, made when the first one is stored. */
    public Object[] refs;
    /** For an array<T> of structs: how many elements. */
    public final int count;

    public Block(long size) {
        this(size, 0);
    }

    public Block(long size, int count) {
        this.bits = new long[(int) ((size + 63) >>> 6)];
        this.count = count;
    }

    /** {@code width} (1 to 64) bits at {@code off}, zero-extended. */
    public static long load(Block b, int off, int width) {
        long[] w = b.bits;
        int word = off >>> 6;
        int shift = off & 63;
        long v = w[word] >>> shift;
        if (shift + width > 64) {
            v |= w[word + 1] << (64 - shift);
        }
        return width == 64 ? v : v & ((1L << width) - 1);
    }

    /** Stores the low {@code width} (1 to 64) bits of {@code value} at {@code off}. */
    public static void store(Block b, int off, long value, int width) {
        long[] w = b.bits;
        long mask = width == 64 ? -1L : (1L << width) - 1;
        value &= mask;
        int word = off >>> 6;
        int shift = off & 63;
        w[word] = (w[word] & ~(mask << shift)) | (value << shift);
        if (shift + width > 64) {
            int spill = 64 - shift;
            long hi = mask >>> spill;
            w[word + 1] = (w[word + 1] & ~hi) | (value >>> spill);
        }
    }

    public static Object ref(Block b, int off) {
        return b.refs == null ? null : b.refs[off >>> 5];
    }

    /** A string field's value: a field never set holds "", as a zeroed string does on the VM. */
    public static String string(Block b, int off) {
        Object s = ref(b, off);
        return s == null ? "" : (String) s;
    }

    public static void setRef(Block b, int off, Object value) {
        if (b.refs == null) {
            if (value == null) {
                return;
            }
            b.refs = new Object[b.bits.length * 2];
        }
        b.refs[off >>> 5] = value;
    }

    /** Copies {@code bits} bits, and the references among them, between blocks (or within one; overlap is handled). */
    public static void copy(Block dst, int dstOff, Block src, int srcOff, int bits) {
        if (bits <= 0) {
            return;
        }
        if (((dstOff | srcOff | bits) & 63) == 0) {
            System.arraycopy(src.bits, srcOff >>> 6, dst.bits, dstOff >>> 6, bits >>> 6);
        } else if (dst == src && dstOff > srcOff && dstOff < srcOff + bits) {
            int done = bits;
            while (done > 0) {
                int n = Math.min(64, done);
                done -= n;
                store(dst, dstOff + done, load(src, srcOff + done, n), n);
            }
        } else {
            int done = 0;
            while (done < bits) {
                int n = Math.min(64, bits - done);
                store(dst, dstOff + done, load(src, srcOff + done, n), n);
                done += n;
            }
        }
        if (src.refs != null || dst.refs != null) {
            int from = srcOff >>> 5;
            int to = dstOff >>> 5;
            int n = (bits + 31) >>> 5;
            if (src.refs == null) {
                Arrays.fill(dst.refs, to, Math.min(to + n, dst.refs.length), null);
            } else {
                if (dst.refs == null) {
                    dst.refs = new Object[dst.bits.length * 2];
                }
                System.arraycopy(src.refs, from, dst.refs, to, Math.min(n, Math.min(src.refs.length - from, dst.refs.length - to)));
            }
        }
    }

    /** Clears {@code bits} bits at {@code off}, and the references among them. */
    public static void zero(Block b, int off, int bits) {
        int done = 0;
        while (done < bits) {
            int n = Math.min(64, bits - done);
            store(b, off + done, 0, n);
            done += n;
        }
        if (b.refs != null) {
            Arrays.fill(b.refs, off >>> 5, Math.min((off + bits + 31) >>> 5, b.refs.length), null);
        }
    }

    /** The element count of an array<T> of structs, which a Block holds. */
    public static int count(Block b) {
        return b.count;
    }
}
