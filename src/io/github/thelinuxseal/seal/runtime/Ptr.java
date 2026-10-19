package io.github.thelinuxseal.seal.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An addr<T> on the JVM (docs/JVM.md §4.4): a block and a bit address in it. The null address is
 * null; arithmetic on it gives an address with no block, which nothing can load from. Loads and
 * stores through an address are checked as the VM checks them: not null, inside the block, and
 * aligned (§22).
 */
public final class Ptr {

    public final Block block;
    public final long off;

    private static final AtomicLong ALLOCATIONS = new AtomicLong();

    public Ptr(Block block, long off) {
        this.block = block;
        this.off = off;
    }

    /** The address of a value in a block: `*x`. */
    public static Ptr of(Block b, int off) {
        return new Ptr(b, off);
    }

    /** p + bits (bits may be negative): an address in the same block. */
    public static Ptr add(Ptr p, long bits) {
        if (p == null) {
            return bits == 0 ? null : new Ptr(null, bits);
        }
        long o = p.off + bits;
        return p.block == null && o == 0 ? null : new Ptr(p.block, o);
    }

    /** a - b, in bits; both must be addresses in the same allocation. */
    public static long diff(Ptr a, Ptr b) {
        if (blockOf(a) != blockOf(b)) {
            throw new Trap("the difference of two addresses in different allocations");
        }
        return offOf(a) - offOf(b);
    }

    /** Orders addresses: by place within one allocation, and allocations in an order that holds while the program runs; null first. */
    public static int compare(Ptr a, Ptr b) {
        Block ab = blockOf(a);
        Block bb = blockOf(b);
        if (ab == bb) {
            return Long.compare(offOf(a), offOf(b));
        }
        return Long.compare(id(ab), id(bb));
    }

    /**
     * The block holding the value of {@code bits} bits at p, checked as the VM checks a load or
     * store: an address that isn't null, inside its block, aligned to {@code align} bits.
     */
    public static Block at(Ptr p, int bits, int align) {
        if (p == null || p.block == null) {
            long a = p == null ? 0 : p.off;
            throw new Trap(a >= 0 && a < 64 ? "null address" : "address " + a + " is outside memory");
        }
        if (p.off < 0 || p.off + bits > (long) p.block.bits.length * 64) {
            throw new Trap("address " + p.off + " is outside its allocation (" + (long) p.block.bits.length * 64 + " bits)");
        }
        if (align > 1 && p.off % align != 0) {
            throw new Trap("misaligned access: " + align + "-bit value at bit address " + p.off
                    + " (must be a multiple of " + align + ")");
        }
        return p.block;
    }

    public static int offset(Ptr p) {
        return (int) p.off;
    }

    /** new<T>(n) and new T() into an address: zeroed memory, rounded up to whole 64-bit words (§22). */
    public static Ptr allocate(long bits) {
        if (bits < 0) {
            throw new Trap("negative allocation size");
        }
        Block b = new Block(Math.max(64, bits));
        b.heap = true;
        return new Ptr(b, 0);
    }

    /** delete p: frees what new made, once; deleting anything else stops the program, as on the VM. */
    public static void free(Ptr p) {
        if (p.block == null || !p.block.heap || p.block.freed || p.off != 0) {
            throw new Trap("delete of an address that was not allocated with new");
        }
        p.block.freed = true;
    }

    private static Block blockOf(Ptr p) {
        return p == null ? null : p.block;
    }

    private static long offOf(Ptr p) {
        return p == null ? 0 : p.off;
    }

    private static long id(Block b) {
        if (b == null) {
            return 0;
        }
        if (b.id == 0) {
            b.id = ALLOCATIONS.incrementAndGet();
        }
        return b.id;
    }
}
