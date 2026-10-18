package io.github.thelinuxseal.seal.vm;

import java.util.Arrays;

/**
 * Bit-addressed memory (§18, §22). Bit {@code a} is bit {@code a % 64} of
 * word {@code a / 64}, which gives LSB-first bit order and little-endian byte
 * order: byte k holds bits 8k to 8k+7.
 */
public final class Memory {

    private long[] words;
    private long sizeBits;

    public Memory(long sizeBits) {
        this.sizeBits = sizeBits;
        this.words = new long[(int) ((sizeBits + 63) >>> 6)];
    }

    public long sizeBits() {
        return sizeBits;
    }

    /** Grows or shrinks, keeping existing contents; new bits are zero. */
    public void resize(long newBits) {
        long[] w = Arrays.copyOf(words, (int) ((newBits + 63) >>> 6));
        if (newBits < sizeBits && (newBits & 63) != 0) {
            w[w.length - 1] &= (1L << (newBits & 63)) - 1;
        }
        words = w;
        sizeBits = newBits;
    }

    public boolean inRange(long addr, long bits) {
        return addr >= 0 && bits >= 0 && addr + bits <= sizeBits && addr + bits >= addr;
    }

    /** Reads {@code width} (1..64) bits at {@code addr}, zero-extended. */
    public long load(long addr, int width) {
        int word = (int) (addr >>> 6);
        int shift = (int) (addr & 63);
        long v = words[word] >>> shift;
        if (shift + width > 64) {
            v |= words[word + 1] << (64 - shift);
        }
        return width == 64 ? v : v & ((1L << width) - 1);
    }

    /** Writes the low {@code width} (1..64) bits of {@code value} at {@code addr}. */
    public void store(long addr, int width, long value) {
        long mask = width == 64 ? -1L : (1L << width) - 1;
        value &= mask;
        int word = (int) (addr >>> 6);
        int shift = (int) (addr & 63);
        words[word] = (words[word] & ~(mask << shift)) | (value << shift);
        if (shift + width > 64) {
            int spill = 64 - shift;
            long hiMask = mask >>> spill;
            words[word + 1] = (words[word + 1] & ~hiMask) | (value >>> spill);
        }
    }

    /** Copies {@code bits} bits between two memories (they may be the same; overlap is handled). */
    public static void copy(Memory dst, long dstAddr, Memory src, long srcAddr, long bits) {
        if (dst == src && dstAddr > srcAddr && dstAddr < srcAddr + bits) {
            // Overlapping, copy backwards.
            long done = bits;
            while (done > 0) {
                int n = (int) Math.min(64, done);
                done -= n;
                dst.store(dstAddr + done, n, src.load(srcAddr + done, n));
            }
            return;
        }
        long done = 0;
        while (done < bits) {
            int n = (int) Math.min(64, bits - done);
            dst.store(dstAddr + done, n, src.load(srcAddr + done, n));
            done += n;
        }
    }

    public void zero(long addr, long bits) {
        long done = 0;
        while (done < bits) {
            int n = (int) Math.min(64, bits - done);
            store(addr + done, n, 0);
            done += n;
        }
    }
}
