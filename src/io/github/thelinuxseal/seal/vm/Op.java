package io.github.thelinuxseal.seal.vm;

/**
 * VM instructions. Code is an {@code int[]}: an opcode followed by its
 * operands. The operand letters say how each operand is used:
 *
 * <ul>
 *   <li>{@code r} register (-1 means "none" where allowed)</li>
 *   <li>{@code i} immediate integer</li>
 *   <li>{@code k} index into the function's 64-bit constant pool</li>
 *   <li>{@code l} jump target (code index)</li>
 *   <li>{@code f} function index, {@code c} class index, {@code v} virtual slot,
 *       {@code n} native index, {@code s} string index</li>
 * </ul>
 *
 * <p>Registers hold 64 bits. Integers are kept normalized: a signed N-bit
 * value is sign-extended to 64 bits and an unsigned one zero-extended, so
 * 64-bit comparisons give the right answer. Floats are kept as raw IEEE bits.
 * Addresses are bit addresses into the fixed pool.
 */
public enum Op {
    CONST("rk"),
    MOV("rr"),

    ADD("rrr"), SUB("rrr"), MUL("rrr"),
    DIVS("rrr"), DIVU("rrr"), REMS("rrr"), REMU("rrr"),
    /** POW dst, base, exp, signed: base ** exp (§41), wrapping; a negative signed exponent divides. */
    POW("rrri"),
    AND("rrr"), OR("rrr"), XOR("rrr"),
    /** dst = a + immediate. */
    ADDI("rri"),
    /** dst = a * immediate. */
    MULI("rri"),
    NEG("rr"),
    /** Bitwise not. */
    NOT("rr"),
    /** Logical not of a bit (0/1). */
    LNOT("rr"),
    /** Wrap to `width` bits and re-normalize: TRUNC dst, src, width, signed. */
    TRUNC("rrii"),
    /** The language's `<<<` (§50): SHIFT dst, value, amount, width, signed. */
    SHIFT("rrrii"),
    /** Logical shift left / right by a register amount in 0..63. */
    SHL("rrr"), SHRU("rrr"),

    EQ("rrr"), NE("rrr"), LTS("rrr"), LES("rrr"), LTU("rrr"), LEU("rrr"),

    /** Floating point: the last operand is 1 for double, 0 for float. */
    FADD("rrri"), FSUB("rrri"), FMUL("rrri"), FDIV("rrri"), FNEG("rri"),
    /** FPOW dst, a, b, double: a ** b with StrictMath, through a double for a float. */
    FPOW("rrri"),
    FEQ("rrri"), FNE("rrri"), FLT("rrri"), FLE("rrri"),
    /** Integer to float: I2F dst, src, signed, double. */
    I2F("rrii"),
    /** Float to 64-bit integer, truncating toward zero: F2I dst, src, double. */
    F2I("rri"),
    F2D("rr"), D2F("rr"),
    /** Float to 64-bit integer, rounding to nearest (ties up): F2IR dst, src, double. */
    F2IR("rri"),

    /**
     * Fixed-point multiply (§37.1): XMUL dst, a, b, frac, signed. Computes
     * round(a * b / 2^frac) with ties rounded up, using the exact 128-bit
     * product. The result is not yet wrapped to the type's width.
     */
    XMUL("rrrii"),
    /** Fixed-point divide: XDIV dst, a, b, frac, signed. Computes round(a * 2^frac / b), ties up. */
    XDIV("rrrii"),
    /**
     * Rescales a fixed-point value: XSCALE dst, src, shift, signed, round.
     * A positive shift multiplies by 2^shift. A negative shift divides by
     * 2^-shift, rounding to nearest with ties up (round = 1) or toward zero
     * (round = 0).
     */
    XSCALE("rriii"),

    JMP("l"), JZ("rl"), JNZ("rl"),

    /** LOAD dst, addr, offset, width, signed: read `width` bits at addr + offset. */
    LOAD("rriii"),
    /** STORE addr, offset, src, width. */
    STORE("rrri"),
    /** MCOPY dst, src, bits. */
    MCOPY("rri"),
    /** MZERO addr, bits. */
    MZERO("ri"),
    /** dst = frame base + offset. */
    FRAME("ri"),
    /** dst = static data base + offset. */
    STATIC("ri"),
    /** ALLOC dst, bitsReg: allocate in the fixed pool, zeroed. */
    ALLOC("rr"),
    FREE("r"),

    /** HNEW dst, class, bits: a new zeroed dynamic object. */
    HNEW("rci"),
    /** HLOAD dst, handle, offsetReg, offset, width, signed. */
    HLOAD("rrriii"),
    /** HSTORE handle, offsetReg, offset, src, width. */
    HSTORE("rrrri"),
    /** HCOPYOUT dstAddr, handle, offsetReg, offset, bits: dynamic object to fixed memory. */
    HCOPYOUT("rrrii"),
    /** HCOPYIN handle, offsetReg, offset, srcAddr, bits: fixed memory to dynamic object. */
    HCOPYIN("rrrri"),
    HDELETE("r"),
    /** Adds a reference to a class object (null and stale handles are ignored). */
    HRETAIN("r"),
    /**
     * Drops a reference to a class object. When the last one goes, the
     * class's destroy function runs and then the object is freed. Null and
     * stale handles are ignored, so a handle that was explicitly deleted
     * can still be dropped safely.
     */
    HRELEASE("r"),
    /** CHECKCAST dst, handle, class: runtime-checked downcast (§35). Null passes. */
    CHECKCAST("rrc"),
    /** THROW exception: throws the exception (a handle, owned), recording where (§41.4). */
    THROW("r"),
    /** RETHROW exception: passes an exception on after cleaning up, keeping where it was first thrown. */
    RETHROW("r"),
    /** INSTANCEOF dst, handle, class: 1 if the handle is non-null and its class is, extends or implements class. */
    INSTANCEOF("rrc"),
    /** Trap unless 0 <= index < length: BOUNDS index, length. */
    BOUNDS("ri"),

    /** CALL fn, argBase, argc, dst. Arguments are in consecutive registers. */
    CALL("frir"),
    /** VCALL slot, argBase, argc, dst. The receiver handle is the first argument. */
    VCALL("vrir"),
    NATIVE("nrir"),
    RET("r"),
    RETV(""),
    TRAP("s");

    public final String operands;

    Op(String operands) {
        this.operands = operands;
    }

    public int size() {
        return 1 + operands.length();
    }

    private static final Op[] VALUES = values();

    public static Op of(int code) {
        return VALUES[code];
    }
}
