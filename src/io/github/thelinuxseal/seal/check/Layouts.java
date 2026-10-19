package io.github.thelinuxseal.seal.check;

import java.math.BigInteger;

import java.util.HashMap;
import java.util.Map;

import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Type.ArrayT;
import io.github.thelinuxseal.seal.check.Type.ClassT;

/**
 * Where each field of a concrete type is, in bits (§21, §22): the layout both backends use, so
 * that sizeof, alignof and addresses mean the same on the VM and the JVM (docs/JVM.md §4.1). A
 * class starts with its parent's fields; fields are aligned to their own alignment, and a type's
 * size is rounded up to its alignment.
 */
public final class Layouts {

    /** Field offsets of a concrete type, in bits, and the fields' types. */
    public record Layout(long size, long align, Map<MemberInfo, Long> offsets, Map<MemberInfo, Type> types) {
    }

    private final Checker ck;
    private final Map<ClassT, Layout> layouts = new HashMap<>();

    public Layouts(Checker ck) {
        this.ck = ck;
    }

    public Layout of(ClassT t) {
        Layout cached = layouts.get(t);
        if (cached != null) {
            return cached;
        }
        Map<MemberInfo, Long> offsets = new HashMap<>();
        Map<MemberInfo, Type> types = new HashMap<>();
        long end = 0;
        long align = 1;
        if (t.cls().dynamic) {
            ClassT parent = ck.parentType(t);
            if (parent != null) {
                Layout p = of(parent);
                offsets.putAll(p.offsets());
                types.putAll(p.types());
                end = p.size();
                align = p.align();
            }
        }
        for (MemberInfo m : t.cls().storedFields(t.header())) {
            Type ft = ck.fieldTypeOf(t, m);
            long size = ft instanceof ArrayT a && a.size() == null ? 0 : sizeOf(ft);
            long al = alignOf(ft);
            long off = roundUp(end, al);
            offsets.put(m, off);
            types.put(m, ft);
            end = add(off, size);
            align = Math.max(align, al);
        }
        Layout l = new Layout(roundUp(end, align), align, offsets, types);
        layouts.put(t, l);
        return l;
    }

    public long sizeOf(Type t) {
        if (t instanceof ClassT ct) {
            return of(ct).size();
        }
        if (t instanceof ArrayT a) {
            if (a.size() == null) {
                return 0;
            }
            BigInteger n = a.size().intValue();
            if (n == null || n.bitLength() > 62) {
                return Long.MAX_VALUE;
            }
            long stride = stride(a.element());
            return stride != 0 && n.longValue() > Long.MAX_VALUE / stride ? Long.MAX_VALUE : n.longValue() * stride;
        }
        long s = ck.sizeBits(t);
        if (s < 0) {
            throw new IllegalStateException("no size for " + t);
        }
        return s;
    }

    public long alignOf(Type t) {
        if (t instanceof ClassT ct) {
            return of(ct).align();
        }
        if (t instanceof ArrayT a) {
            return alignOf(a.element());
        }
        long a = ck.alignBits(t);
        return a < 0 ? 1 : a;
    }

    /** The distance between elements of an array of this type. */
    public long stride(Type element) {
        return roundUp(sizeOf(element), alignOf(element));
    }

    public static long roundUp(long v, long a) {
        return a <= 1 || v > Long.MAX_VALUE - a ? v : (v + a - 1) / a * a;
    }

    /**
     * a + b, staying at Long.MAX_VALUE: a size too large to hold, which the backends report
     * (an array of 2 ** N elements in a class used with a large N).
     */
    private static long add(long a, long b) {
        long s = a + b;
        return ((a ^ s) & (b ^ s)) < 0 ? Long.MAX_VALUE : s;
    }
}
