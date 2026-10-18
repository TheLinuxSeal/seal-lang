package lang.vm;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import lang.vm.Program.Function;
import lang.vm.Program.RtClass;

/** Unit tests for the VM and its memory, independent of the compiler. `java -cp out lang.vm.VMTest`. */
public final class VMTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        memoryAgainstModel();
        memoryCopy();
        memoryResize();
        shifts();
        alignment();
        arithmetic();
        floats();
        allocator();
        allocatorStress();
        handles();
        calls();
        dispatch();
        traps();
        staticImage();
        fixedPoint();

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ memory

    /** Random loads and stores of every width at every alignment, checked against a bit-by-bit model. */
    private static void memoryAgainstModel() {
        Random rnd = new Random(1);
        int bits = 1024;
        Memory m = new Memory(bits);
        boolean[] model = new boolean[bits];
        boolean ok = true;
        String firstFailure = null;
        for (int i = 0; i < 20_000 && ok; i++) {
            int width = 1 + rnd.nextInt(64);
            long addr = rnd.nextInt(bits - width + 1);
            if (rnd.nextBoolean()) {
                long v = rnd.nextLong();
                m.store(addr, width, v);
                for (int b = 0; b < width; b++) {
                    model[(int) addr + b] = ((v >>> b) & 1) != 0;
                }
            } else {
                long expected = 0;
                for (int b = 0; b < width; b++) {
                    if (model[(int) addr + b]) {
                        expected |= 1L << b;
                    }
                }
                long got = m.load(addr, width);
                if (got != expected) {
                    ok = false;
                    firstFailure = "load(" + addr + ", " + width + ") = " + Long.toHexString(got)
                            + ", expected " + Long.toHexString(expected);
                }
            }
        }
        check(ok, "memory matches bit model: " + firstFailure);

        // LSB-first bits and little-endian bytes (§22).
        Memory le = new Memory(64);
        le.store(0, 32, 0x11223344L);
        check(le.load(0, 8) == 0x44 && le.load(8, 8) == 0x33 && le.load(24, 8) == 0x11,
                "little-endian byte order");
        le.store(40, 1, 1);
        check(le.load(32, 16) == 0x100, "bit 40 is bit 0 of byte 5");
    }

    private static void memoryCopy() {
        Random rnd = new Random(2);
        boolean ok = true;
        for (int trial = 0; trial < 500 && ok; trial++) {
            int bits = 512;
            Memory m = new Memory(bits);
            long[] model = new long[bits];
            for (int i = 0; i < bits; i++) {
                int b = rnd.nextInt(2);
                m.store(i, 1, b);
                model[i] = b;
            }
            int len = rnd.nextInt(200);
            int src = rnd.nextInt(bits - len + 1);
            int dst = rnd.nextInt(bits - len + 1);
            Memory.copy(m, dst, m, src, len);
            long[] copy = model.clone();
            System.arraycopy(copy, src, model, dst, len);
            for (int i = 0; i < bits && ok; i++) {
                if (m.load(i, 1) != model[i]) {
                    ok = false;
                }
            }
        }
        check(ok, "overlapping copies in both directions");

        Memory a = new Memory(256);
        Memory b = new Memory(256);
        a.store(3, 64, -1L);
        a.store(67, 64, 0x0123456789abcdefL);
        Memory.copy(b, 50, a, 3, 128);
        check(b.load(50, 64) == -1L && b.load(114, 64) == 0x0123456789abcdefL, "copy between memories at odd offsets");
        b.zero(60, 100);
        check(b.load(50, 10) == 0x3ff && b.load(60, 64) == 0
                && b.load(160, 18) == ((0x0123456789abcdefL >>> 46) & 0x3ffff), "zero a range, leaving the rest");
    }

    private static void memoryResize() {
        Memory m = new Memory(100);
        m.store(36, 64, -1L);
        m.resize(70);
        m.resize(200);
        check(m.load(36, 34) == (1L << 34) - 1 && m.load(70, 30) == 0, "shrink clears the cut-off bits, grow adds zeros");
        check(m.inRange(0, 200) && !m.inRange(1, 200) && !m.inRange(-1, 1) && !m.inRange(10, -1), "range checks");
    }

    // ================================================================ instruction semantics

    private static void shifts() {
        // (value, amount, width, signed) -> expected, per §50
        long[][] cases = {
                {1, 3, 32, 1, 8},
                {-16, -2, 32, 1, -4},
                {16, -2, 32, 0, 4},
                {1, 32, 32, 1, 0},
                {1, 40, 32, 0, 0},
                {-16, -40, 32, 1, -1},
                {16, -40, 32, 0, 0},
                {0xff, 4, 8, 0, 0xf0},
                {0x40, 1, 8, 1, -128},
                {-1, -63, 64, 1, -1},
                {Long.MIN_VALUE, -63, 64, 0, 1},
                {5, Long.MIN_VALUE, 64, 0, 0},
                {-5, Long.MIN_VALUE, 64, 1, -1},
                {1, 63, 64, 1, Long.MIN_VALUE},
                {3, 1, 2, 0, 2},
        };
        for (long[] c : cases) {
            long got = VM.shift(c[0], c[1], (int) c[2], c[3] != 0);
            check(got == c[4], "shift(" + c[0] + ", " + c[1] + ", width " + c[2] + (c[3] != 0 ? " signed" : " unsigned")
                    + ") = " + got + ", expected " + c[4]);
        }
    }

    private static void alignment() {
        // 8, 16, 32 and 64 bits align to their size; every other width to 1 bit (§22).
        long[][] cases = {{0, 1}, {1, 1}, {3, 1}, {6, 1}, {8, 8}, {12, 1}, {16, 16}, {24, 1}, {32, 32}, {48, 1}, {64, 64}, {17, 1}};
        for (long[] c : cases) {
            check(VM.alignFor(c[0]) == c[1], "alignFor(" + c[0] + ") = " + VM.alignFor(c[0]) + ", expected " + c[1]);
        }
    }

    private static void arithmetic() {
        // TRUNC wraps and re-normalizes.
        check(run(fn("t", 3, 0, new long[]{300},
                Op.CONST, 1, 0, Op.TRUNC, 2, 1, 8, 1, Op.RET, 2)) == 44, "trunc 300 to int<<8>>");
        check(run(fn("t", 3, 0, new long[]{-1},
                Op.CONST, 1, 0, Op.TRUNC, 2, 1, 4, 0, Op.RET, 2)) == 15, "trunc -1 to uint<<4>>");
        check(run(fn("t", 3, 0, new long[]{0x80},
                Op.CONST, 1, 0, Op.TRUNC, 2, 1, 8, 1, Op.RET, 2)) == -128, "trunc 0x80 to int<<8>> sign-extends");
        // Division edge cases.
        check(run(fn("t", 4, 0, new long[]{Long.MIN_VALUE, -1},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.DIVS, 3, 1, 2, Op.RET, 3)) == Long.MIN_VALUE, "MIN / -1 wraps");
        check(run(fn("t", 4, 0, new long[]{Long.MIN_VALUE, -1},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.REMS, 3, 1, 2, Op.RET, 3)) == 0, "MIN % -1 is 0");
        check(run(fn("t", 4, 0, new long[]{-1, 2},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.DIVU, 3, 1, 2, Op.RET, 3)) == Long.MAX_VALUE, "unsigned divide");
        check(run(fn("t", 4, 0, new long[]{-1, 1},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.LTU, 3, 2, 1, Op.RET, 3)) == 1, "unsigned compare");
        check(run(fn("t", 4, 0, new long[]{-1, 1},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.LTS, 3, 2, 1, Op.RET, 3)) == 0, "signed compare");
        // Branches: sum 1..10.
        check(run(fn("t", 5, 0, new long[]{0, 1, 10},
                Op.CONST, 1, 0,          // sum = 0
                Op.CONST, 2, 1,          // i = 1
                Op.CONST, 3, 2,          // limit = 10
                Op.LES, 4, 2, 3,         // 9: i <= limit
                Op.JZ, 4, 26,
                Op.ADD, 1, 1, 2,         // 16
                Op.ADDI, 2, 2, 1,        // 20
                Op.JMP, 9,               // 24
                Op.RET, 1)) == 55, "loop with branches");
    }

    private static void floats() {
        long a = Double.doubleToRawLongBits(1.5);
        long b = Double.doubleToRawLongBits(0.25);
        long r = run(fn("t", 4, 0, new long[]{a, b},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.FDIV, 3, 1, 2, 1, Op.RET, 3));
        check(Double.longBitsToDouble(r) == 6.0, "double divide");
        long f = Float.floatToRawIntBits(0.1f) & 0xffffffffL;
        r = run(fn("t", 4, 0, new long[]{f},
                Op.CONST, 1, 0, Op.FADD, 2, 1, 1, 0, Op.F2D, 3, 2, Op.RET, 3));
        check(Double.longBitsToDouble(r) == (double) (0.1f + 0.1f), "float add rounds to float, then widens");
        r = run(fn("t", 3, 0, new long[]{-1},
                Op.CONST, 1, 0, Op.I2F, 2, 1, 0, 1, Op.RET, 2));
        check(Double.longBitsToDouble(r) == 18446744073709551615.0, "unsigned 64-bit to double");
        r = run(fn("t", 3, 0, new long[]{Double.doubleToRawLongBits(-2.9)},
                Op.CONST, 1, 0, Op.F2I, 2, 1, 1, Op.RET, 2));
        check(r == -2, "double to int truncates toward zero");
        long nan = Double.doubleToRawLongBits(Double.NaN);
        r = run(fn("t", 4, 0, new long[]{nan},
                Op.CONST, 1, 0, Op.FEQ, 2, 1, 1, 1, Op.FNE, 3, 1, 1, 1, Op.ADD, 2, 2, 3, Op.RET, 2));
        check(r == 1, "NaN is not equal to itself");
    }

    // ================================================================ allocator and handles

    private static void allocator() {
        // a = alloc 64; b = alloc 64; c = alloc 64; free b; d = alloc 64 -> d == b (first fit)
        long r = run(fn("t", 9, 0, new long[]{64},
                Op.CONST, 1, 0,
                Op.ALLOC, 2, 1, Op.ALLOC, 3, 1, Op.ALLOC, 4, 1,
                Op.FREE, 3,
                Op.ALLOC, 5, 1,
                Op.EQ, 6, 5, 3,
                Op.RET, 6));
        check(r == 1, "freed block is reused");

        // Freeing everything coalesces: a block as big as all three fits where they were.
        r = run(fn("t", 9, 0, new long[]{64, 192},
                Op.CONST, 1, 0, Op.CONST, 7, 1,
                Op.ALLOC, 2, 1, Op.ALLOC, 3, 1, Op.ALLOC, 4, 1,
                Op.FREE, 3, Op.FREE, 2, Op.FREE, 4,
                Op.ALLOC, 5, 7,
                Op.EQ, 6, 5, 2,
                Op.RET, 6));
        check(r == 1, "free blocks coalesce");

        // Allocations are zeroed, even when reusing freed memory.
        r = run(fn("t", 9, 0, new long[]{64, 12345},
                Op.CONST, 1, 0, Op.CONST, 6, 1,
                Op.ALLOC, 2, 1, Op.STORE, 2, 0, 6, 64, Op.FREE, 2,
                Op.ALLOC, 3, 1, Op.LOAD, 4, 3, 0, 64, 0,
                Op.RET, 4));
        check(r == 0, "reused memory is zeroed");

        // Sizes round up to whole 64-bit words, so tiny allocations don't overlap.
        r = run(fn("t", 9, 0, new long[]{3},
                Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.ALLOC, 3, 1, Op.SUB, 4, 3, 2, Op.RET, 4));
        check(r == 64, "a 3-bit allocation takes a whole word");

        trapCheck(fn("t", 9, 0, new long[]{64},
                Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.FREE, 2, Op.FREE, 2, Op.RETV),
                "delete of an address that was not allocated with new", "double free");
        trapCheck(fn("t", 9, 0, new long[]{64},
                Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.ADDI, 3, 2, 64, Op.FREE, 3, Op.RETV),
                "delete of an address that was not allocated with new", "free of an interior address");
        trapCheck(fn("t", 9, 0, new long[]{1L << 40},
                Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.RETV),
                "out of memory", "allocation too large");
        trapCheck(fn("t", 9, 0, new long[]{-8},
                Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.RETV),
                "negative allocation size", "negative allocation");
    }

    /**
     * Random allocations and frees. Each live block is filled with its own
     * tag; afterwards every block must still hold its tag, so no allocation
     * ever overlapped a live one.
     */
    private static void allocatorStress() {
        Random rnd = new Random(3);
        boolean ok = true;
        for (int round = 0; round < 20 && ok; round++) {
            // Registers: r1 scratch size, r2 tag, r3 address; the program is built as straight-line code.
            List<Object> code = new ArrayList<>();
            List<Long> consts = new ArrayList<>();
            List<Integer> liveRegs = new ArrayList<>();
            List<Long> liveSizes = new ArrayList<>();
            int nextReg = 10;
            for (int step = 0; step < 60; step++) {
                if (!liveRegs.isEmpty() && rnd.nextInt(3) == 0) {
                    int k = rnd.nextInt(liveRegs.size());
                    code.add(Op.FREE);
                    code.add(liveRegs.remove(k));
                    liveSizes.remove(k);
                } else {
                    long size = 64L * (1 + rnd.nextInt(40));
                    int reg = nextReg++;
                    consts.add(size);
                    code.addAll(List.of(Op.CONST, 1, consts.size() - 1, Op.ALLOC, reg, 1));
                    // Fill the block with this register number as its tag.
                    consts.add((long) reg);
                    code.addAll(List.of(Op.CONST, 2, consts.size() - 1));
                    for (long off = 0; off < size; off += 64) {
                        code.addAll(List.of(Op.STORE, reg, (int) off, 2, 64));
                    }
                    liveRegs.add(reg);
                    liveSizes.add(size);
                }
            }
            // Check every live block: count words that lost their tag.
            consts.add(0L);
            int zero = consts.size() - 1;
            code.addAll(List.of(Op.CONST, 5, zero));
            for (int k = 0; k < liveRegs.size(); k++) {
                int reg = liveRegs.get(k);
                consts.add((long) reg);
                code.addAll(List.of(Op.CONST, 2, consts.size() - 1));
                for (long off = 0; off < liveSizes.get(k); off += 64) {
                    code.addAll(List.of(Op.LOAD, 3, reg, (int) off, 64, 0, Op.NE, 4, 3, 2, Op.ADD, 5, 5, 4));
                }
            }
            code.addAll(List.of(Op.RET, 5));
            long[] k = consts.stream().mapToLong(Long::longValue).toArray();
            long broken = run(fn("stress", nextReg + 1, 0, k, code.toArray()));
            if (broken != 0) {
                ok = false;
                check(false, "allocator stress round " + round + ": " + broken + " words were overwritten");
            }
        }
        if (ok) {
            check(true, "allocator stress");
        }
    }

    private static void handles() {
        Program p = new Program();
        p.classes.add(new RtClass("Box", -1, Map.of()));
        // h = new Box; store 7; delete; h2 = new Box (reuses the slot); h2 != h; load via h traps
        p.functions.add(fn("t", 9, 0, new long[]{7},
                Op.HNEW, 1, 0, 128,
                Op.CONST, 2, 0,
                Op.HSTORE, 1, -1, 64, 2, 32,
                Op.HLOAD, 3, 1, -1, 64, 32, 1,
                Op.HDELETE, 1,
                Op.HNEW, 4, 0, 128,
                Op.NE, 5, 4, 1,
                Op.HLOAD, 6, 4, -1, 64, 32, 1,  // fresh object is zero
                Op.ADD, 5, 5, 6,
                Op.ADD, 5, 5, 3,               // 1 + 0 + 7
                Op.RET, 5));
        p.entry = 0;
        check(newVM(p).run() == 8, "handle store/load, slot reuse with a new generation");

        Program stale = new Program();
        stale.classes.add(new RtClass("Box", -1, Map.of()));
        stale.functions.add(fn("t", 9, 0, new long[0],
                Op.HNEW, 1, 0, 64, Op.HDELETE, 1, Op.HNEW, 2, 0, 64, Op.HLOAD, 3, 1, -1, 0, 8, 0, Op.RET, 3));
        stale.entry = 0;
        trapCheck(stale, "stale handle", "old handle after the slot is reused");

        Program bounds = new Program();
        bounds.classes.add(new RtClass("Box", -1, Map.of()));
        bounds.functions.add(fn("t", 9, 0, new long[0],
                Op.HNEW, 1, 0, 64, Op.HLOAD, 2, 1, -1, 60, 8, 0, Op.RET, 2));
        bounds.entry = 0;
        trapCheck(bounds, "outside the object", "read past the end of a dynamic object");

        Program nul = new Program();
        nul.functions.add(fn("t", 9, 0, new long[]{0},
                Op.CONST, 1, 0, Op.HLOAD, 2, 1, -1, 0, 8, 0, Op.RET, 2));
        nul.entry = 0;
        trapCheck(nul, "null handle", "null handle");

        // Copy between fixed memory and a dynamic object.
        Program copies = new Program();
        copies.classes.add(new RtClass("Box", -1, Map.of()));
        copies.functions.add(fn("t", 9, 256, new long[]{0x1234},
                Op.HNEW, 1, 0, 128,
                Op.FRAME, 2, 0,
                Op.CONST, 3, 0,
                Op.STORE, 2, 16, 3, 16,
                Op.HCOPYIN, 1, -1, 3, 2, 64,     // object bits 3..66 = frame bits 0..63
                Op.FRAME, 4, 128,
                Op.HCOPYOUT, 4, 1, -1, 3, 64,    // back out to frame bits 128..191
                Op.LOAD, 5, 4, 16, 16, 0,
                Op.RET, 5));
        copies.entry = 0;
        check(newVM(copies).run() == 0x1234, "copy into and out of a dynamic object at an odd offset");
    }

    // ================================================================ calls

    private static void calls() {
        // fn 0: main calls fn 1 (add three args) with args 1, 2, 3
        Program p = new Program();
        p.functions.add(fn("main", 8, 0, new long[]{1, 2, 3},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.CONST, 3, 2,
                Op.CALL, 1, 1, 3, 4,
                Op.RET, 4));
        p.functions.add(fn("add3", 5, 0, new long[0],
                Op.ADD, 4, 1, 2, Op.ADD, 4, 4, 3, Op.RET, 4));
        p.entry = 0;
        check(newVM(p).run() == 6, "call with arguments and result");

        // Frames: each call gets its own zeroed frame memory; the caller's survives.
        Program frames = new Program();
        frames.functions.add(fn("main", 8, 64, new long[]{99},
                Op.FRAME, 1, 0, Op.CONST, 2, 0, Op.STORE, 1, 0, 2, 64,
                Op.CALL, 1, 1, 0, 3,
                Op.LOAD, 4, 1, 0, 64, 0,
                Op.ADD, 4, 4, 3,
                Op.RET, 4));
        frames.functions.add(fn("callee", 4, 64, new long[]{5},
                Op.FRAME, 1, 0, Op.LOAD, 2, 1, 0, 64, 0,   // 0: fresh frame is zeroed
                Op.CONST, 3, 0, Op.STORE, 1, 0, 3, 64,
                Op.ADD, 2, 2, 3,
                Op.RET, 2));
        frames.entry = 0;
        check(newVM(frames).run() == 104, "frames are separate and zeroed");

        // Static data and the init function.
        Program statics = new Program();
        statics.staticBits = 64;
        statics.functions.add(fn("main", 4, 0, new long[0],
                Op.STATIC, 1, 0, Op.LOAD, 2, 1, 0, 64, 0, Op.RET, 2));
        statics.functions.add(fn("init", 4, 0, new long[]{41},
                Op.STATIC, 1, 0, Op.CONST, 2, 0, Op.ADDI, 2, 2, 1, Op.STORE, 1, 0, 2, 64, Op.RETV));
        statics.entry = 0;
        statics.init = 1;
        check(newVM(statics).run() == 42, "init runs before main");

        // Natives write to the output stream.
        Program natives = new Program();
        natives.natives.add("print_int");
        natives.natives.add("print_char");
        natives.functions.add(fn("main", 4, 0, new long[]{-7, 33},
                Op.CONST, 1, 0, Op.NATIVE, 0, 1, 1, -1, Op.CONST, 2, 1, Op.NATIVE, 1, 2, 1, -1, Op.RETV));
        natives.entry = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new VM(natives, new PrintStream(bytes, true, StandardCharsets.UTF_8)).run();
        check(bytes.toString(StandardCharsets.UTF_8).equals("-7!"), "natives print");
    }

    private static void dispatch() {
        // Classes: 0 Animal, 1 Dog extends Animal, 2 Cat extends Animal (no override)
        Program p = new Program();
        p.slots.add("speak()");
        p.classes.add(new RtClass("Animal", -1, Map.of(0, 1)));
        p.classes.add(new RtClass("Dog", 0, Map.of(0, 2)));
        p.classes.add(new RtClass("Cat", 0, Map.of(0, 1)));
        p.functions.add(fn("main", 9, 0, new long[]{10},
                Op.HNEW, 1, 1, 64,
                Op.VCALL, 0, 1, 1, 2,           // Dog.speak -> 2
                Op.HNEW, 3, 2, 64,
                Op.VCALL, 0, 3, 1, 4,           // Cat inherits Animal.speak -> 1
                Op.CONST, 5, 0,
                Op.MUL, 2, 2, 5,
                Op.ADD, 2, 2, 4,                // 21
                Op.CHECKCAST, 6, 1, 0,          // Dog to Animal: fine
                Op.RET, 2));
        p.functions.add(fn("Animal.speak", 3, 0, new long[]{1}, Op.CONST, 2, 0, Op.RET, 2));
        p.functions.add(fn("Dog.speak", 3, 0, new long[]{2}, Op.CONST, 2, 0, Op.RET, 2));
        p.entry = 0;
        check(newVM(p).run() == 21, "virtual dispatch and inherited slots");

        Program bad = new Program();
        bad.classes.add(new RtClass("Animal", -1, Map.of()));
        bad.classes.add(new RtClass("Dog", 0, Map.of()));
        bad.functions.add(fn("main", 4, 0, new long[0], Op.HNEW, 1, 0, 64, Op.CHECKCAST, 2, 1, 1, Op.RETV));
        bad.entry = 0;
        trapCheck(bad, "cannot cast Animal to Dog", "downcast of a parent instance");

        Program itf = new Program();
        itf.classes.add(new RtClass("Drawable", -1, Map.of()));                 // an interface: no objects
        itf.classes.add(new RtClass("Animal", -1, Map.of(), List.of(0)));        // implements Drawable
        itf.classes.add(new RtClass("Dog", 1, Map.of()));                       // inherits that
        itf.classes.add(new RtClass("Rock", -1, Map.of()));
        itf.functions.add(fn("main", 4, 0, new long[]{5}, Op.HNEW, 1, 2, 64, Op.CHECKCAST, 2, 1, 0,
                Op.CONST, 3, 0, Op.RET, 3));
        itf.entry = 0;
        check(newVM(itf).run() == 5, "a class implements its parent's interfaces");
        Program notItf = new Program();
        notItf.classes.addAll(itf.classes);
        notItf.functions.add(fn("main", 4, 0, new long[0], Op.HNEW, 1, 3, 64, Op.CHECKCAST, 2, 1, 0, Op.RETV));
        notItf.entry = 0;
        trapCheck(notItf, "cannot cast Rock to Drawable", "cast to an interface the class doesn't implement");

        Program nullCast = new Program();
        nullCast.classes.add(new RtClass("Dog", -1, Map.of()));
        nullCast.functions.add(fn("main", 4, 0, new long[]{0}, Op.CONST, 1, 0, Op.CHECKCAST, 2, 1, 0, Op.RET, 2));
        nullCast.entry = 0;
        check(newVM(nullCast).run() == 0, "null passes a downcast");

        Program missing = new Program();
        missing.slots.add("fly()");
        missing.classes.add(new RtClass("Dog", -1, Map.of()));
        missing.functions.add(fn("main", 4, 0, new long[0], Op.HNEW, 1, 0, 64, Op.VCALL, 0, 1, 1, -1, Op.RETV));
        missing.entry = 0;
        trapCheck(missing, "Dog has no method fly()", "missing vtable slot");
    }

    // ================================================================ traps

    private static void traps() {
        trapCheck(fn("t", 4, 0, new long[]{0}, Op.CONST, 1, 0, Op.LOAD, 2, 1, 0, 8, 0, Op.RET, 2),
                "null address", "load from null");
        trapCheck(fn("t", 4, 0, new long[]{63}, Op.CONST, 1, 0, Op.LOAD, 2, 1, 0, 1, 0, Op.RET, 2),
                "null address", "every bit of the null word is protected");
        trapCheck(fn("t", 4, 0, new long[]{Long.MAX_VALUE - 10}, Op.CONST, 1, 0, Op.LOAD, 2, 1, 0, 64, 0, Op.RET, 2),
                "outside memory", "load past the end of memory");
        trapCheck(fn("t", 4, 0, new long[]{64}, Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.LOAD, 3, 2, 8, 32, 0, Op.RET, 3),
                "misaligned access", "32-bit load at an 8-bit offset");
        check(run(fn("t", 4, 0, new long[]{64}, Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.LOAD, 3, 2, 5, 24, 0, Op.RET, 3)) == 0,
                "a 24-bit load can start at any bit");
        check(run(fn("t", 5, 0, new long[]{64, 45}, Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.CONST, 4, 1,
                Op.STORE, 2, 5, 4, 6, Op.LOAD, 3, 2, 5, 6, 0, Op.RET, 3)) == 45,
                "a 6-bit value at an odd bit address");
        trapCheck(fn("t", 4, 0, new long[]{64}, Op.CONST, 1, 0, Op.ALLOC, 2, 1, Op.LOAD, 3, 2, 4, 16, 0, Op.RET, 3),
                "misaligned access", "a 16-bit load must be 16-bit aligned");
        trapCheck(fn("t", 4, 0, new long[]{0, 5}, Op.CONST, 1, 0, Op.CONST, 2, 1, Op.DIVS, 3, 2, 1, Op.RET, 3),
                "division by zero", "divide by zero");
        trapCheck(fn("t", 4, 0, new long[]{5}, Op.CONST, 1, 0, Op.BOUNDS, 1, 5, Op.RETV),
                "index 5 is out of bounds for length 5", "bounds check at the length");
        trapCheck(fn("t", 4, 0, new long[]{-1}, Op.CONST, 1, 0, Op.BOUNDS, 1, 5, Op.RETV),
                "index -1 is out of bounds", "bounds check below zero");

        Program loop = new Program();
        loop.functions.add(fn("spin", 1, 0, new long[0], Op.JMP, 0));
        loop.entry = 0;
        VM vm = newVM(loop);
        vm.setMaxSteps(1000);
        check(trapMessage(vm).contains("step limit of 1000"), "step limit");

        Program recurse = new Program();
        recurse.functions.add(fn("down", 2, 0, new long[0], Op.CALL, 0, 1, 0, -1, Op.RETV));
        recurse.entry = 0;
        trapCheck(recurse, "call stack overflow", "unbounded recursion");

        Program bigFrames = new Program();
        bigFrames.functions.add(fn("down", 2, 1L << 20, new long[0], Op.CALL, 0, 1, 0, -1, Op.RETV));
        bigFrames.entry = 0;
        trapCheck(bigFrames, "out of stack memory", "frames larger than the stack");

        Program msg = new Program();
        msg.strings.add("custom failure");
        msg.functions.add(fn("main", 1, 0, new long[0], Op.CALL, 1, 1, 0, -1, Op.RETV));
        msg.functions.add(fn("inner", 1, 0, new long[0], Op.TRAP, 0));
        msg.entry = 0;
        try {
            newVM(msg).run();
            check(false, "TRAP instruction should trap");
        } catch (Trap t) {
            check(t.getMessage().equals("custom failure") && t.stack.size() == 2
                    && t.stack.get(0).startsWith("inner") && t.stack.get(1).startsWith("main"),
                    "trap message and stack trace: " + t);
        }
    }

    private static void staticImage() {
        Program p = new Program();
        p.staticBits = 128;
        p.staticImage = new Memory(128);
        p.staticImage.store(64, 16, 'H');
        p.staticImage.store(80, 16, 'i');
        p.functions.add(fn("main", 4, 0, new long[0],
                Op.STATIC, 1, 64, Op.LOAD, 2, 1, 16, 16, 0, Op.RET, 2));
        p.entry = 0;
        check(newVM(p).run() == 'i', "static image is copied in at startup");
        check(!p.disassemble().isEmpty(), "disassembly");
    }

    // ================================================================ fixed point

    private static void fixedPoint() {
        check(VM.fixedMul(384, 576, 8, true) == 864, "1.5 * 2.25 = 3.375 in 8.8");
        check(VM.fixedMul(1, 1, 1, true) == 1, "0.5 * 0.5 rounds a tie up to 0.5");
        check(VM.fixedMul(-1, 1, 1, true) == 0, "-0.5 * 0.5 rounds a tie up to 0");
        check(VM.fixedMul(3, 3, 2, true) == 2, "0.75 * 0.75 = 0.5625 rounds to 0.5 in 2 fraction bits");
        check(VM.fixedMul(1L << 40, 1L << 40, 32, true) == 1L << 48, "128-bit product");
        check(VM.fixedMul(-(1L << 40), 1L << 40, 32, true) == -(1L << 48), "128-bit negative product");
        check(VM.fixedMul(-1L, 2, 1, false) == -1L, "unsigned 64-bit operand");
        check(VM.fixedMul(7, -3, 0, true) == -21, "no fraction bits is plain multiplication");

        check(run(fn("t", 4, 0, new long[]{384, 576},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.XDIV, 3, 1, 2, 8, 1, Op.RET, 3)) == 171,
                "1.5 / 2.25 = 0.666... rounds to 171/256");
        check(run(fn("t", 4, 0, new long[]{-384, 576},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.XDIV, 3, 1, 2, 8, 1, Op.RET, 3)) == -171,
                "-1.5 / 2.25 rounds to -171/256");
        check(run(fn("t", 4, 0, new long[]{384, -576},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.XDIV, 3, 1, 2, 8, 1, Op.RET, 3)) == -171,
                "negative divisor");
        trapCheck(fn("t", 4, 0, new long[]{384, 0},
                Op.CONST, 1, 0, Op.CONST, 2, 1, Op.XDIV, 3, 1, 2, 8, 1, Op.RET, 3),
                "division by zero", "fixed-point division by zero");

        check(VM.rescale(5, 3, true, true) == 40, "rescale up");
        check(VM.rescale(-7, -1, true, true) == -3, "rescale down rounds -3.5 up to -3");
        check(VM.rescale(-7, -1, true, false) == -3, "rescale toward zero: -3.5 to -3");
        check(VM.rescale(-9, -2, true, false) == -2, "rescale toward zero: -2.25 to -2");
        check(VM.rescale(-9, -2, true, true) == -2, "rescale nearest: -2.25 to -2");
        check(VM.rescale(-11, -2, true, true) == -3, "rescale nearest: -2.75 to -3");
        check(VM.rescale(7, -1, false, true) == 4, "unsigned 3.5 rounds to 4");
        check(VM.rescale(1L << 63, -64, false, true) == 1, "ufixed<<0, 64>> one half rounds to 1");
        check(VM.rescale(-1L, -64, false, false) == 0, "ufixed<<0, 64>> truncates to 0");

        check(VM.formatFixed(26, 8, true).equals("0.1015625"), "0.1 stored in 8.8 prints its exact value");
        check(VM.formatFixed(-384, 8, true).equals("-1.5"), "negative");
        check(VM.formatFixed(512, 8, true).equals("2.0"), "whole numbers keep one fraction digit");
        check(VM.formatFixed(1, 8, true).equals("0.00390625"), "1/256");
        check(VM.formatFixed(-1, 2, true).equals("-0.25"), "-0.25 in 2 fraction bits prints exactly, not as -0.2");
        check(VM.formatFixed(3, 1, true).equals("1.5"), "one fraction bit");
        check(VM.formatFixed(-1L, 0, false).equals("18446744073709551615.0"), "unsigned with no fraction bits");
        check(VM.formatFixed(85, 8, true).equals("0.33203125"), "one third in 8.8");
    }

    // ================================================================ helpers

    /** Builds a function from a mixed list of Ops and integer operands. */
    private static Function fn(String name, int registers, long frameBits, long[] consts, Object... code) {
        List<Integer> out = new ArrayList<>();
        for (Object o : code) {
            out.add(o instanceof Op op ? op.ordinal() : (Integer) o);
        }
        return new Function(name, out.stream().mapToInt(Integer::intValue).toArray(), consts, registers, frameBits);
    }

    private static VM newVM(Program p) {
        VM vm = new VM(p, new PrintStream(new ByteArrayOutputStream()), 1 << 16, 1 << 14);
        vm.setMaxSteps(1_000_000);
        return vm;
    }

    private static long run(Function f) {
        Program p = new Program();
        p.functions.add(f);
        p.entry = 0;
        return newVM(p).run();
    }

    private static String trapMessage(VM vm) {
        try {
            vm.run();
            return "(no trap)";
        } catch (Trap t) {
            return t.getMessage();
        }
    }

    private static void trapCheck(Function f, String expected, String name) {
        Program p = new Program();
        p.functions.add(f);
        p.entry = 0;
        trapCheck(p, expected, name);
    }

    private static void trapCheck(Program p, String expected, String name) {
        String msg = trapMessage(newVM(p));
        check(msg.contains(expected), name + ": expected trap containing \"" + expected + "\", got \"" + msg + "\"");
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
