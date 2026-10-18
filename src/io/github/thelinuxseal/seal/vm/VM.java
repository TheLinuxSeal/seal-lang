package io.github.thelinuxseal.seal.vm;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.thelinuxseal.seal.vm.Program.Function;
import io.github.thelinuxseal.seal.vm.Program.RtClass;

/**
 * The register VM (§55, §56).
 *
 * <p>Memory layout of the fixed pool, in bits:
 * <pre>
 *   [0, 64)                  null: byte 0 and the rest of its word (§23)
 *   [64, staticEnd)          static fields
 *   [staticEnd, stackStart)  heap for new / new&lt;T&gt;(n)
 *   [stackStart, end)        call frames for locals that live in memory
 * </pre>
 * Dynamic objects live outside the pool, each in its own resizable
 * {@link Memory}, reached through a handle table (§26).
 */
public final class VM {

    public static final long DEFAULT_POOL_BYTES = 16L << 20;
    public static final long DEFAULT_STACK_BYTES = 1L << 20;
    private static final int MAX_CALL_DEPTH = 10_000;

    private final Program program;
    private final PrintStream out;
    /** The program's standard error and input (§42.2); input is empty unless set, so nothing waits for a keyboard. */
    private PrintStream err = System.err;
    private java.io.InputStream input = java.io.InputStream.nullInputStream();
    private java.io.BufferedReader stdinLines;
    private final Memory pool;
    private final long staticBase = 64;
    private final long heapStart;
    private final long stackStart;
    private long stackTop;

    /** Free heap blocks, start to size in bits. */
    private final TreeMap<Long, Long> free = new TreeMap<>();
    /** Allocated heap blocks, start to size in bits. */
    private final Map<Long, Long> allocated = new HashMap<>();

    // Handle table. Index 0 is never used, so a handle of 0 is null.
    private final List<Memory> objects = new ArrayList<>();
    private final List<Integer> generations = new ArrayList<>();
    private final List<Integer> objectClass = new ArrayList<>();
    /** Reference counts; -1 while an object's destroy function is running. */
    private final List<Integer> refCounts = new ArrayList<>();
    private final Deque<Integer> freeHandles = new ArrayDeque<>();

    private long steps = 0;
    private long maxSteps = Long.MAX_VALUE;

    private final class Frame {
        final Function fn;
        final long[] r;
        final long base;
        int pc;
        /** Register in the caller that receives the return value, or -1. */
        final int resultReg;
        /** For a destroy function run by HRELEASE: the object to free when it returns, or 0. */
        long freeOnReturn;

        Frame(Function fn, long base, int resultReg) {
            this.fn = fn;
            this.r = new long[Math.max(1, fn.registers)];
            this.base = base;
            this.resultReg = resultReg;
        }
    }

    private final Deque<Frame> frames = new ArrayDeque<>();

    public VM(Program program, PrintStream out) {
        this(program, out, DEFAULT_POOL_BYTES, DEFAULT_STACK_BYTES);
    }

    public VM(Program program, PrintStream out, long poolBytes, long stackBytes) {
        this.program = program;
        this.out = out;
        this.pool = new Memory(poolBytes * 8);
        long staticEnd = roundUp(staticBase + program.staticBits, 64);
        this.heapStart = staticEnd;
        this.stackStart = pool.sizeBits() - stackBytes * 8;
        if (stackStart <= heapStart) {
            throw new IllegalArgumentException("pool too small");
        }
        this.stackTop = stackStart;
        free.put(heapStart, stackStart - heapStart);
        if (program.staticImage != null) {
            Memory.copy(pool, staticBase, program.staticImage, 0, Math.min(program.staticImage.sizeBits(), program.staticBits));
        }
        objects.add(null);
        generations.add(0);
        objectClass.add(-1);
        refCounts.add(0);
    }

    /** Stops with a trap after this many instructions; for tests. */
    public void setMaxSteps(long maxSteps) {
        this.maxSteps = maxSteps;
    }

    /** Runs static initialization and the entry point; returns the entry point's result (0 for void). */
    public long run() {
        try {
            if (program.init >= 0) {
                call(program.init, new long[0]);
            }
            long result = call(program.entry, new long[0]);
            if (program.fini >= 0) {
                call(program.fini, new long[0]); // static fields let go of what they hold
            }
            return result;
        } catch (Exit e) {
            return e.code;
        } finally {
            out.flush();
            err.flush();
            closeFiles(); // what the program left open is still written out
        }
    }

    public void setInput(java.io.InputStream in) {
        this.input = in;
        this.stdinLines = null;
    }

    public void setError(PrintStream err) {
        this.err = err;
    }

    /** System.exit: ends the program at once with this exit code. */
    private static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final long code;

        Exit(long code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    /** Whether the program may use files (§42.1). Off, every file operation fails with an IOException. */
    public void setFileAccess(boolean allowed) {
        this.fileAccess = allowed;
    }

    /** Calls a function with the given arguments and runs until it returns. */
    public long call(int fn, long[] args) {
        long[] result = new long[1];
        enter(program.functions.get(fn), args, 0, args.length, -1, null);
        int depth = frames.size();
        execute(depth, result);
        return result[0];
    }

    private static long roundUp(long v, long a) {
        return (v + a - 1) / a * a;
    }

    // ================================================================ traps

    private Trap trap(String message) {
        List<String> stack = new ArrayList<>();
        for (Frame f : frames) {
            if (program.entryCreatesMain && f.fn == program.functions.get(program.entry)) {
                continue; // generated, not the program's own code
            }
            // f.pc is already past the instruction that failed (or the call that is running).
            Program.Line line = f.pc > 0 ? f.fn.lineAt(f.pc - 1) : null;
            stack.add(f.fn.name + (line != null ? " (" + line + ")" : " @" + f.pc));
        }
        return new Trap(message, stack);
    }

    // ================================================================ exceptions

    /**
     * Passes an exception to the innermost handler covering the current instruction of
     * each frame, dropping frames that have none (§41.4).
     */
    private void unwind(long exception) {
        while (frames.size() >= stopDepth) {
            Frame f = frames.peek();
            int at = f.pc - 1; // within the instruction that threw, or the call that did
            for (Program.Handler h : f.fn.handlers) {
                if (h.start() <= at && at < h.end()) {
                    f.r[f.fn.excReg] = exception;
                    f.pc = h.pad();
                    return;
                }
            }
            Frame dropped = frames.pop();
            stackTop = dropped.base;
            if (dropped.freeOnReturn != 0 && isLive(dropped.freeOnReturn)) {
                deleteObject(dropped.freeOnReturn);
            }
        }
        throw new Trap("uncaught " + program.classes.get(classOf(exception)).name() + messageOf(exception), thrownAt, true);
    }

    /** ": " and the exception's message, read from its `message` string; empty if there is none. */
    private String messageOf(long exception) {
        long[] l = program.messageLayout;
        if (l == null || !isLive(exception)) {
            return "";
        }
        Memory obj = object(exception);
        int size = (int) obj.load(l[0] + l[1], 32);
        long chars = obj.load(l[0] + l[2], 64);
        if (size <= 0 || !pool.inRange(chars, size * 16L)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(": ");
        for (int i = 0; i < size; i++) {
            sb.append((char) pool.load(chars + i * 16L, 16));
        }
        return sb.toString();
    }

    // ================================================================ calls

    private void enter(Function fn, long[] src, int argBase, int argc, int resultReg, Frame caller) {
        if (frames.size() >= MAX_CALL_DEPTH) {
            throw trap("call stack overflow (more than " + MAX_CALL_DEPTH + " nested calls)");
        }
        long bits = roundUp(fn.frameBits, 64);
        if (stackTop + bits > pool.sizeBits()) {
            throw trap("out of stack memory");
        }
        Frame f = new Frame(fn, stackTop, resultReg);
        pool.zero(stackTop, bits);
        stackTop += bits;
        f.r[0] = f.base;
        System.arraycopy(src, argBase, f.r, 1, argc);
        frames.push(f);
    }

    private void leave(long value, boolean hasValue, long[] result) {
        Frame f = frames.pop();
        stackTop = f.base;
        if (f.freeOnReturn != 0 && isLive(f.freeOnReturn)) {
            deleteObject(f.freeOnReturn);
        }
        Frame caller = frames.peek();
        if (caller != null && f.resultReg >= 0 && hasValue) {
            caller.r[f.resultReg] = value;
        }
        result[0] = value;
    }

    // ================================================================ memory checks

    private void checkFixed(long addr, long bits, int alignWidth) {
        if (addr >= 0 && addr < 64) {
            throw trap("null address");
        }
        if (!pool.inRange(addr, bits)) {
            throw trap("address " + addr + " is outside memory");
        }
        if (alignWidth > 0) {
            long align = alignFor(alignWidth);
            if (addr % align != 0) {
                throw trap("misaligned access: " + alignWidth + "-bit value at bit address " + addr
                        + " (must be a multiple of " + align + ")");
            }
        }
    }

    /** Alignment of a value of the given width (§22): 8, 16, 32 and 64 bits align to their size; others to 1. */
    static long alignFor(long width) {
        return width == 8 || width == 16 || width == 32 || width == 64 ? width : 1;
    }

    private Memory object(long handle) {
        if (handle == 0) {
            throw trap("null handle");
        }
        int index = (int) (handle >>> 32);
        int gen = (int) handle;
        if (index <= 0 || index >= objects.size() || objects.get(index) == null || generations.get(index) != gen) {
            throw trap("stale handle: the object was deleted");
        }
        return objects.get(index);
    }

    private int classOf(long handle) {
        object(handle);
        return objectClass.get((int) (handle >>> 32));
    }

    private void checkObject(Memory m, long offset, long bits) {
        if (!m.inRange(offset, bits)) {
            throw trap("access at bit " + offset + " is outside the object (" + m.sizeBits() + " bits)");
        }
    }

    private long alloc(long bits) {
        if (bits < 0) {
            throw trap("negative allocation size");
        }
        long size = Math.max(64, roundUp(bits, 64));
        for (Map.Entry<Long, Long> e : free.entrySet()) {
            // Read both before removing: TreeMap may reuse a removed entry's node
            // for the next entry, so `e` must not be read afterwards.
            long start = e.getKey();
            long blockSize = e.getValue();
            if (blockSize >= size) {
                free.remove(start);
                if (blockSize > size) {
                    free.put(start + size, blockSize - size);
                }
                allocated.put(start, size);
                pool.zero(start, size);
                return start;
            }
        }
        throw trap("out of memory: cannot allocate " + bits + " bits");
    }

    private void release(long addr) {
        Long size = allocated.remove(addr);
        if (size == null) {
            throw trap(addr == 0 ? "delete of null" : "delete of an address that was not allocated with new");
        }
        long start = addr;
        long len = size;
        Map.Entry<Long, Long> before = free.floorEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            free.remove(before.getKey());
            start = before.getKey();
            len += before.getValue();
        }
        Long after = free.get(addr + size);
        if (after != null) {
            free.remove(addr + size);
            len += after;
        }
        free.put(start, len);
    }

    private long newObject(int cls, long bits) {
        int index;
        if (!freeHandles.isEmpty()) {
            index = freeHandles.pop();
            objects.set(index, new Memory(bits));
            objectClass.set(index, cls);
            refCounts.set(index, 1);
        } else {
            index = objects.size();
            objects.add(new Memory(bits));
            generations.add(1);
            objectClass.add(cls);
            refCounts.add(1);
        }
        return ((long) index << 32) | (generations.get(index) & 0xffffffffL);
    }

    /** Whether a handle refers to an object that still exists. */
    private boolean isLive(long handle) {
        int index = (int) (handle >>> 32);
        return handle != 0 && index > 0 && index < objects.size() && objects.get(index) != null
                && generations.get(index) == (int) handle;
    }

    private void retainObject(long handle) {
        if (isLive(handle)) {
            int index = (int) (handle >>> 32);
            int c = refCounts.get(index);
            if (c > 0) {
                refCounts.set(index, c + 1);
            }
        }
    }

    /** Drops a reference; at zero, starts the class's destroy function, which frees the object when it returns. */
    private void releaseObject(long handle) {
        if (!isLive(handle)) {
            return;
        }
        int index = (int) (handle >>> 32);
        int c = refCounts.get(index);
        if (c <= 0) {
            return; // already being destroyed
        }
        if (c > 1) {
            refCounts.set(index, c - 1);
            return;
        }
        refCounts.set(index, -1);
        Integer fn = program.destroySlot < 0 ? null
                : program.classes.get(objectClass.get(index)).vtable().get(program.destroySlot);
        if (fn == null) {
            deleteObject(handle);
            return;
        }
        enter(program.functions.get(fn), new long[]{handle}, 0, 1, -1, frames.peek());
        frames.peek().freeOnReturn = handle;
    }

    /** Number of class objects currently alive; for tests. */
    public int liveObjects() {
        int n = 0;
        for (int i = 1; i < objects.size(); i++) {
            if (objects.get(i) != null) {
                n++;
            }
        }
        return n;
    }

    private void deleteObject(long handle) {
        object(handle);
        int index = (int) (handle >>> 32);
        objects.set(index, null);
        generations.set(index, generations.get(index) + 1);
        freeHandles.push(index);
    }

    /** Whether class {@code cls} is, extends or implements {@code target} (a class or interface). */
    private boolean isSubclass(int cls, int target) {
        for (int c = cls; c >= 0; c = program.classes.get(c).parent()) {
            if (c == target || program.classes.get(c).interfaces().contains(target)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ arithmetic helpers

    private static long trunc(long v, int width, boolean signed) {
        if (width >= 64) {
            return v;
        }
        return signed ? (v << (64 - width)) >> (64 - width) : v & ((1L << width) - 1);
    }

    /** The language's shift (§50): positive amounts shift left, negative right; no undefined cases. */
    static long shift(long v, long amount, int width, boolean signed) {
        if (amount >= 0) {
            return amount >= width ? 0 : trunc(v << amount, width, signed);
        }
        if (amount == Long.MIN_VALUE || -amount >= width) {
            return signed && v < 0 ? trunc(-1, width, true) : 0;
        }
        return signed ? v >> -amount : v >>> -amount;
    }

    // ================================================================ fixed point (§37.1)

    private static java.math.BigInteger big(long v, boolean signed) {
        java.math.BigInteger b = java.math.BigInteger.valueOf(v);
        return signed || v >= 0 ? b : b.add(java.math.BigInteger.ONE.shiftLeft(64));
    }

    /** floor(x / y) for any signs. */
    private static java.math.BigInteger floorDiv(java.math.BigInteger x, java.math.BigInteger y) {
        java.math.BigInteger[] qr = x.divideAndRemainder(y);
        return qr[1].signum() != 0 && qr[1].signum() != y.signum() ? qr[0].subtract(java.math.BigInteger.ONE) : qr[0];
    }

    /** round(a * b / 2^frac), ties up. */
    static long fixedMul(long a, long b, int frac, boolean signed) {
        long lim = 1L << 31;
        if (a > -lim && a < lim && b > -lim && b < lim) {
            long p = a * b;
            return frac == 0 ? p : (p >> frac) + ((p >> (frac - 1)) & 1);
        }
        java.math.BigInteger p = big(a, signed).multiply(big(b, signed));
        if (frac == 0) {
            return p.longValue();
        }
        return p.shiftRight(frac).add(p.testBit(frac - 1) ? java.math.BigInteger.ONE : java.math.BigInteger.ZERO).longValue();
    }

    /** round(a * 2^frac / b), ties up. */
    private long fixedDiv(long a, long b, int frac, boolean signed) {
        if (b == 0) {
            throw trap("division by zero");
        }
        // floor((2 * a * 2^frac + b) / (2 * b)) rounds a * 2^frac / b to nearest, ties up.
        java.math.BigInteger n = big(a, signed).shiftLeft(frac + 1);
        java.math.BigInteger d = big(b, signed).shiftLeft(1);
        return floorDiv(n.add(big(b, signed)), d).longValue();
    }

    /** Multiplies by 2^shift; for negative shifts divides, rounding to nearest (ties up) or toward zero. */
    static long rescale(long v, int shift, boolean signed, boolean nearest) {
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

    // ================================================================ files (§42.1)

    private boolean fileAccess = true;
    /** The text the last file operation produced: file contents, a line, a listing, or an error message. */
    private String ioText = "";
    private byte[] ioBytes = new byte[0];
    /** Open files by id: java.io.BufferedReader or BufferedWriter. */
    private final Map<Long, java.io.Closeable> openFiles = new HashMap<>();
    /** Ids 1 to 3 are always open: the program's standard input, output and error. */
    private static final long STDIN = 1;
    private static final long STDOUT = 2;
    private static final long STDERR = 3;
    private static final java.util.Set<String> STREAM_OPS = java.util.Set.of("io_read_line", "io_flush", "io_close");
    private static final java.util.Set<String> WRITES = java.util.Set.of("io_write", "io_write_int", "io_write_uint",
            "io_write_float", "io_write_double", "io_write_bit", "io_write_char", "io_write_fixed", "io_write_ufixed");

    /** Writes text to a standard stream (always allowed) or an open file. */
    private long writeTo(long id, String text) {
        if (id == STDOUT || id == STDERR) {
            PrintStream to = id == STDERR ? err : out;
            to.print(text);
            to.flush();
            return 0;
        }
        if (id == STDIN) {
            ioText = "standard input can't be written";
            return IO_ERROR;
        }
        if (!fileAccess) {
            ioText = "file access is disabled";
            return IO_ERROR;
        }
        if (!(openFiles.get(id) instanceof java.io.BufferedWriter w)) {
            ioText = "the file is closed";
            return IO_ERROR;
        }
        try {
            w.write(text);
            return 0;
        } catch (java.io.IOException e) {
            ioText = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return IO_ERROR;
        }
    }
    private long nextFileId = 4;

    private long standardStream(String name, long[] r, int base) {
        long id = r[base];
        PrintStream to = id == STDERR ? err : out;
        switch (name) {
            case "io_read_line" -> {
                if (id != STDIN) {
                    ioText = "standard output can't be read";
                    return IO_ERROR;
                }
                try {
                    if (stdinLines == null) {
                        stdinLines = new java.io.BufferedReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
                    }
                    out.flush(); // a prompt written before reading is seen first
                    String line = stdinLines.readLine();
                    if (line == null) {
                        return IO_END;
                    }
                    ioText = line;
                    return line.length();
                } catch (java.io.IOException e) {
                    ioText = "standard input: " + e.getMessage();
                    return IO_ERROR;
                }
            }
            default -> {
                to.flush(); // flush and close: the standard streams stay open
                return 0;
            }
        }
    }

    /** Status of a file operation: the result (>= 0), or an error with its message in ioText. */
    private static final long IO_ERROR = -1;
    private static final long IO_NOT_FOUND = -2;
    private static final long IO_END = -3;

    /**
     * The file natives. Strings arrive as the address of a bare string; text going back is
     * left in ioText, for io_text_take to copy into a string the library has made room in.
     */
    private long io(String name, long[] r, int base) {
        // Taking the text of the last result (or error) is always allowed.
        if (name.equals("io_text_take")) {
            return writeText(ioText, r[base]);
        }
        if (name.equals("io_text_length")) {
            return ioText.length();
        }
        if (WRITES.contains(name)) {
            // A value's text, written to a standard stream or an open file.
            long v = r[base + 1];
            return writeTo(r[base], switch (name) {
                case "io_write" -> readString(v);
                case "io_write_int" -> Long.toString(v);
                case "io_write_uint" -> Long.toUnsignedString(v);
                case "io_write_float" -> Float.toString(Float.intBitsToFloat((int) v));
                case "io_write_double" -> Double.toString(Double.longBitsToDouble(v));
                case "io_write_bit" -> v != 0 ? "true" : "false";
                case "io_write_char" -> String.valueOf((char) v);
                case "io_write_fixed" -> formatFixed(v, (int) r[base + 2], true);
                default -> formatFixed(v, (int) r[base + 2], false); // io_write_ufixed
            });
        }
        if (r[base] >= STDIN && r[base] <= STDERR && STREAM_OPS.contains(name)) {
            return standardStream(name, r, base); // not files: allowed even with file access off
        }
        if (!fileAccess) {
            ioText = "file access is disabled";
            return IO_ERROR;
        }
        try {
            switch (name) {
                case "io_read_text" -> {
                    ioText = new String(java.nio.file.Files.readAllBytes(path(r[base])), java.nio.charset.StandardCharsets.UTF_8);
                    return ioText.length();
                }
                case "io_write_text" -> {
                    java.nio.file.Files.writeString(path(r[base]), readString(r[base + 1]), java.nio.charset.StandardCharsets.UTF_8,
                            writeOptions(r[base + 2] != 0));
                    return 0;
                }
                case "io_exists" -> {
                    return java.nio.file.Files.exists(path(r[base])) ? 1 : 0;
                }
                case "io_is_file" -> {
                    return java.nio.file.Files.isRegularFile(path(r[base])) ? 1 : 0;
                }
                case "io_is_dir" -> {
                    return java.nio.file.Files.isDirectory(path(r[base])) ? 1 : 0;
                }
                case "io_size" -> {
                    return java.nio.file.Files.size(path(r[base]));
                }
                case "io_modified" -> {
                    return java.nio.file.Files.getLastModifiedTime(path(r[base])).toMillis();
                }
                case "io_delete" -> {
                    java.nio.file.Files.delete(path(r[base]));
                    return 0;
                }
                case "io_mkdir" -> {
                    java.nio.file.Path p = path(r[base]);
                    if (r[base + 1] != 0) {
                        java.nio.file.Files.createDirectories(p);
                    } else {
                        java.nio.file.Files.createDirectory(p);
                    }
                    return 0;
                }
                case "io_list" -> {
                    // Names sorted, so a program sees the same order on every system; joined with
                    // NUL, which no file name contains.
                    try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(path(r[base]))) {
                        List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
                        ioText = String.join("\0", names);
                        return names.size();
                    }
                }
                case "io_copy" -> {
                    java.nio.file.Files.copy(path(r[base]), path(r[base + 1]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return 0;
                }
                case "io_move" -> {
                    java.nio.file.Files.move(path(r[base]), path(r[base + 1]), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return 0;
                }
                case "io_absolute" -> {
                    ioText = path(r[base]).toAbsolutePath().normalize().toString();
                    return ioText.length();
                }
                case "io_temp_dir" -> {
                    ioText = java.nio.file.Files.createTempDirectory(readString(r[base])).toString();
                    return ioText.length();
                }
                case "io_open" -> {
                    java.nio.file.Path p = path(r[base]);
                    java.io.Closeable f = r[base + 1] == 0
                            ? new java.io.BufferedReader(new java.io.InputStreamReader(
                                    java.nio.file.Files.newInputStream(p), java.nio.charset.StandardCharsets.UTF_8))
                            : new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                                    java.nio.file.Files.newOutputStream(p, writeOptions(r[base + 1] == 2)),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    long id = nextFileId++;
                    openFiles.put(id, f);
                    return id;
                }
                case "io_read_line" -> {
                    if (!(openFiles.get(r[base]) instanceof java.io.BufferedReader in)) {
                        ioText = "the file is closed";
                        return IO_ERROR;
                    }
                    String line = in.readLine();
                    if (line == null) {
                        return IO_END;
                    }
                    ioText = line;
                    return line.length();
                }
                case "io_flush" -> {
                    if (openFiles.get(r[base]) instanceof java.io.BufferedWriter out) {
                        out.flush();
                    }
                    return 0;
                }
                case "io_close" -> {
                    java.io.Closeable f = openFiles.remove(r[base]);
                    if (f != null) {
                        f.close();
                    }
                    return 0;
                }
                case "io_read_bytes" -> {
                    ioBytes = java.nio.file.Files.readAllBytes(path(r[base]));
                    return ioBytes.length;
                }
                case "io_bytes_take" -> {
                    long at = r[base];
                    checkFixed(at, ioBytes.length * 8L, 8);
                    for (int i = 0; i < ioBytes.length; i++) {
                        pool.store(at + i * 8L, 8, ioBytes[i] & 0xff);
                    }
                    return ioBytes.length;
                }
                case "io_write_bytes" -> {
                    long at = r[base + 1];
                    int n = (int) r[base + 2];
                    checkFixed(at, n * 8L, 8);
                    byte[] data = new byte[n];
                    for (int i = 0; i < n; i++) {
                        data[i] = (byte) pool.load(at + i * 8L, 8);
                    }
                    java.nio.file.Files.write(path(r[base]), data, writeOptions(r[base + 3] != 0));
                    return 0;
                }
                default -> throw trap("unknown native function " + name);
            }
        } catch (java.nio.file.NoSuchFileException e) {
            ioText = "no such file or directory: " + e.getFile();
            return IO_NOT_FOUND;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            ioText = "already exists: " + e.getFile();
            return IO_ERROR;
        } catch (java.nio.file.DirectoryNotEmptyException e) {
            ioText = "folder is not empty: " + e.getFile();
            return IO_ERROR;
        } catch (java.nio.file.NotDirectoryException e) {
            ioText = "not a folder: " + e.getFile();
            return IO_ERROR;
        } catch (java.nio.file.AccessDeniedException e) {
            ioText = "permission denied: " + e.getFile();
            return IO_ERROR;
        } catch (java.io.IOException e) {
            ioText = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return IO_ERROR;
        } catch (java.nio.file.InvalidPathException e) {
            ioText = "invalid path: " + e.getMessage();
            return IO_ERROR;
        }
    }

    private static java.nio.file.OpenOption[] writeOptions(boolean append) {
        return append
                ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND}
                : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE};
    }

    private java.nio.file.Path path(long stringAddr) {
        return java.nio.file.Path.of(readString(stringAddr));
    }

    /** The text of a bare string at this address, read with the layout the code generator gives. */
    private String readString(long addr) {
        long[] l = program.stringLayout;
        if (l == null) {
            throw trap("strings need the standard library");
        }
        int size = (int) pool.load(addr + l[0], 32);
        long chars = pool.load(addr + l[1], 64);
        if (size <= 0) {
            return "";
        }
        checkFixed(chars, size * 16L, 16);
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) pool.load(chars + i * 16L, 16));
        }
        return sb.toString();
    }

    /** Closes what the program left open, writing out what it wrote. */
    private void closeFiles() {
        for (java.io.Closeable f : openFiles.values()) {
            try {
                f.close();
            } catch (java.io.IOException e) {
                // the program has finished; nothing to report it to
            }
        }
        openFiles.clear();
    }

    /** Writes {@code s} as 16-bit characters at bit address {@code addr}. Returns its length. */
    private long writeText(String s, long addr) {
        checkFixed(addr, (long) s.length() * 16, 16);
        for (int i = 0; i < s.length(); i++) {
            pool.store(addr + i * 16L, 16, s.charAt(i));
        }
        return s.length();
    }

    /**
     * The exact decimal value of a fixed-point number, with at least one
     * fraction digit. Every fixed-point value has a finite decimal expansion
     * (at most M fraction digits), so nothing is rounded.
     */
    static String formatFixed(long raw, int frac, boolean signed) {
        java.math.BigDecimal exact = new java.math.BigDecimal(big(raw, signed)).divide(
                new java.math.BigDecimal(java.math.BigInteger.ONE.shiftLeft(frac))).stripTrailingZeros();
        String s = exact.toPlainString();
        return exact.scale() <= 0 ? s + ".0" : s;
    }

    private static double fl(long bits, boolean dbl) {
        return dbl ? Double.longBitsToDouble(bits) : Float.intBitsToFloat((int) bits);
    }

    private static long fb(double v, boolean dbl) {
        return dbl ? Double.doubleToRawLongBits(v) : Float.floatToRawIntBits((float) v) & 0xffffffffL;
    }

    // ================================================================ interpreter

    /** Frames below this belong to whoever called {@link #call}; an exception reaching it is uncaught. */
    private int stopDepth;
    /** The stack where the exception being thrown was thrown, for its report if nothing catches it. */
    private List<String> thrownAt = List.of();

    private void execute(int stopDepth, long[] result) {
        this.stopDepth = stopDepth;
        while (frames.size() >= stopDepth) {
            Frame f = frames.peek();
            int[] c = f.fn.code;
            long[] r = f.r;
            if (++steps > maxSteps) {
                throw trap("step limit of " + maxSteps + " instructions exceeded");
            }
            int pc = f.pc;
            Op op = Op.of(c[pc]);
            f.pc = pc + op.size();
            int a = op.operands.length() > 0 ? c[pc + 1] : 0;
            int b = op.operands.length() > 1 ? c[pc + 2] : 0;
            int d = op.operands.length() > 2 ? c[pc + 3] : 0;
            switch (op) {
                case CONST -> r[a] = f.fn.constants[b];
                case MOV -> r[a] = r[b];
                case ADD -> r[a] = r[b] + r[d];
                case SUB -> r[a] = r[b] - r[d];
                case MUL -> r[a] = r[b] * r[d];
                case DIVS -> r[a] = divisor(r[d]) == -1 ? -r[b] : r[b] / r[d];
                case DIVU -> r[a] = Long.divideUnsigned(r[b], divisor(r[d]));
                case REMS -> r[a] = divisor(r[d]) == -1 ? 0 : r[b] % r[d];
                case REMU -> r[a] = Long.remainderUnsigned(r[b], divisor(r[d]));
                case AND -> r[a] = r[b] & r[d];
                case OR -> r[a] = r[b] | r[d];
                case XOR -> r[a] = r[b] ^ r[d];
                case ADDI -> r[a] = r[b] + d;
                case MULI -> r[a] = r[b] * d;
                case NEG -> r[a] = -r[b];
                case NOT -> r[a] = ~r[b];
                case LNOT -> r[a] = r[b] == 0 ? 1 : 0;
                case TRUNC -> r[a] = trunc(r[b], d, c[pc + 4] != 0);
                case SHIFT -> r[a] = shift(r[b], r[d], c[pc + 4], c[pc + 5] != 0);
                case SHL -> r[a] = r[b] << r[d];
                case SHRU -> r[a] = r[b] >>> r[d];
                case EQ -> r[a] = r[b] == r[d] ? 1 : 0;
                case NE -> r[a] = r[b] != r[d] ? 1 : 0;
                case LTS -> r[a] = r[b] < r[d] ? 1 : 0;
                case LES -> r[a] = r[b] <= r[d] ? 1 : 0;
                case LTU -> r[a] = Long.compareUnsigned(r[b], r[d]) < 0 ? 1 : 0;
                case LEU -> r[a] = Long.compareUnsigned(r[b], r[d]) <= 0 ? 1 : 0;
                case FADD, FSUB, FMUL, FDIV -> {
                    boolean dbl = c[pc + 4] != 0;
                    double x = fl(r[b], dbl);
                    double y = fl(r[d], dbl);
                    double z = switch (op) {
                        case FADD -> dbl ? x + y : (float) x + (float) y;
                        case FSUB -> dbl ? x - y : (float) x - (float) y;
                        case FMUL -> dbl ? x * y : (float) x * (float) y;
                        default -> dbl ? x / y : (float) x / (float) y;
                    };
                    r[a] = fb(z, dbl);
                }
                case FNEG -> r[a] = fb(-fl(r[b], d != 0), d != 0);
                case FEQ, FNE, FLT, FLE -> {
                    boolean dbl = c[pc + 4] != 0;
                    double x = fl(r[b], dbl);
                    double y = fl(r[d], dbl);
                    boolean v = switch (op) {
                        case FEQ -> x == y;
                        case FNE -> x != y;
                        case FLT -> x < y;
                        default -> x <= y;
                    };
                    r[a] = v ? 1 : 0;
                }
                case I2F -> {
                    boolean signed = d != 0;
                    boolean dbl = c[pc + 4] != 0;
                    long v = r[b];
                    double x = signed || v >= 0 ? (double) v : (double) (v >>> 1) * 2.0 + (v & 1);
                    r[a] = fb(x, dbl);
                }
                case F2I -> r[a] = (long) fl(r[b], d != 0);
                case F2D -> r[a] = fb(fl(r[b], false), true);
                case F2IR -> r[a] = (long) Math.floor(fl(r[b], d != 0) + 0.5);
                case XMUL -> r[a] = fixedMul(r[b], r[d], c[pc + 4], c[pc + 5] != 0);
                case XDIV -> r[a] = fixedDiv(r[b], r[d], c[pc + 4], c[pc + 5] != 0);
                case XSCALE -> r[a] = rescale(r[b], d, c[pc + 4] != 0, c[pc + 5] != 0);
                case D2F -> r[a] = fb(fl(r[b], true), false);
                case JMP -> f.pc = a;
                case JZ -> {
                    if (r[a] == 0) {
                        f.pc = b;
                    }
                }
                case JNZ -> {
                    if (r[a] != 0) {
                        f.pc = b;
                    }
                }
                case LOAD -> {
                    int width = c[pc + 4];
                    long addr = r[b] + d;
                    checkFixed(addr, width, width);
                    r[a] = trunc(pool.load(addr, width), width, c[pc + 5] != 0);
                }
                case STORE -> {
                    int width = c[pc + 4];
                    long addr = r[a] + b;
                    checkFixed(addr, width, width);
                    pool.store(addr, width, r[d]);
                }
                case MCOPY -> {
                    if (d > 0) {
                        checkFixed(r[a], d, 0);
                        checkFixed(r[b], d, 0);
                        Memory.copy(pool, r[a], pool, r[b], d);
                    }
                }
                case MZERO -> {
                    if (b > 0) {
                        checkFixed(r[a], b, 0);
                        pool.zero(r[a], b);
                    }
                }
                case FRAME -> r[a] = f.base + b;
                case STATIC -> r[a] = staticBase + b;
                case ALLOC -> r[a] = alloc(r[b]);
                case FREE -> release(r[a]);
                case HNEW -> r[a] = newObject(b, d);
                case HLOAD -> {
                    Memory m = object(r[b]);
                    long off = (d < 0 ? 0 : r[d]) + c[pc + 4];
                    int width = c[pc + 5];
                    checkObject(m, off, width);
                    r[a] = trunc(m.load(off, width), width, c[pc + 6] != 0);
                }
                case HSTORE -> {
                    Memory m = object(r[a]);
                    long off = (b < 0 ? 0 : r[b]) + d;
                    int width = c[pc + 5];
                    checkObject(m, off, width);
                    m.store(off, width, r[c[pc + 4]]);
                }
                case HCOPYOUT -> {
                    Memory m = object(r[b]);
                    long off = (d < 0 ? 0 : r[d]) + c[pc + 4];
                    long bits = c[pc + 5];
                    checkObject(m, off, bits);
                    checkFixed(r[a], bits, 0);
                    Memory.copy(pool, r[a], m, off, bits);
                }
                case HCOPYIN -> {
                    Memory m = object(r[a]);
                    long off = (b < 0 ? 0 : r[b]) + d;
                    long src = r[c[pc + 4]];
                    long bits = c[pc + 5];
                    checkObject(m, off, bits);
                    checkFixed(src, bits, 0);
                    Memory.copy(m, off, pool, src, bits);
                }
                case HDELETE -> deleteObject(r[a]);
                case HRETAIN -> retainObject(r[a]);
                case HRELEASE -> releaseObject(r[a]);
                case CHECKCAST -> {
                    long h = r[b];
                    if (h != 0 && !isSubclass(classOf(h), d)) {
                        throw trap("cannot cast " + program.classes.get(classOf(h)).name() + " to "
                                + program.classes.get(d).name());
                    }
                    r[a] = h;
                }
                case THROW -> {
                    if (r[a] == 0) {
                        throw trap("throw null");
                    }
                    thrownAt = trap("").stack;
                    unwind(r[a]);
                }
                case RETHROW -> unwind(r[a]);
                case INSTANCEOF -> r[a] = r[b] != 0 && isSubclass(classOf(r[b]), d) ? 1 : 0;
                case BOUNDS -> {
                    if (r[a] < 0 || r[a] >= b) {
                        throw trap("index " + r[a] + " is out of bounds for length " + b);
                    }
                }
                case CALL -> enter(program.functions.get(a), r, b, d, c[pc + 4], f);
                case VCALL -> {
                    int cls = classOf(r[b]);
                    Integer fn = program.classes.get(cls).vtable().get(a);
                    if (fn == null) {
                        throw trap(program.classes.get(cls).name() + " has no method " + program.slots.get(a));
                    }
                    enter(program.functions.get(fn), r, b, d, c[pc + 4], f);
                }
                case NATIVE -> {
                    long v = callNative(program.natives.get(a), r, b, d);
                    if (c[pc + 4] >= 0) {
                        r[c[pc + 4]] = v;
                    }
                }
                case RET -> leave(r[a], true, result);
                case RETV -> leave(0, false, result);
                case TRAP -> throw trap(program.strings.get(a));
            }
        }
    }

    private long divisor(long v) {
        if (v == 0) {
            throw trap("division by zero");
        }
        return v;
    }

    private long callNative(String name, long[] r, int base, int argc) {
        long x = argc > 0 ? r[base] : 0;
        long y = argc > 1 ? r[base + 1] : 0;
        switch (name) {
            case "print_int" -> out.print(x);
            case "print_uint" -> out.print(Long.toUnsignedString(x));
            case "print_float" -> out.print(Float.intBitsToFloat((int) x));
            case "print_double" -> out.print(Double.longBitsToDouble(x));
            case "print_bit" -> out.print(x != 0);
            case "print_char" -> out.print((char) x);
            case "print_fixed" -> out.print(formatFixed(x, (int) y, true));
            case "print_ufixed" -> out.print(formatFixed(x, (int) y, false));
            case "println" -> out.println();
            case "abort" -> {
                out.flush();
                throw trap("abort");
            }
            case "exit" -> throw new Exit(x);
            case "identity_hash" -> {
                return x * 0x9E3779B97F4A7C15L; // the handle, spread over all the bits
            }
            case "double_bits", "float_bits" -> {
                return x; // registers hold a float's raw bits already
            }
            // Number text, written as 16-bit characters at a buffer address; returns the length.
            case "text_float" -> {
                return writeText(Float.toString(Float.intBitsToFloat((int) x)), y);
            }
            case "text_double" -> {
                return writeText(Double.toString(Double.longBitsToDouble(x)), y);
            }
            case "text_fixed" -> {
                return writeText(formatFixed(x, (int) r[base + 2], true), y); // value, buffer, fraction bits
            }
            case "text_ufixed" -> {
                return writeText(formatFixed(x, (int) r[base + 2], false), y);
            }
            // For string.format (§42.6): a double with a number of places, as Java's %.Nf or %.Ne
            // (conversion 'f' or 'e'); value, conversion, places, buffer.
            case "text_places" -> {
                String spec = "%." + r[base + 2] + (y == 'e' || y == 'E' ? "e" : "f");
                return writeText(String.format(java.util.Locale.ROOT, spec, Double.longBitsToDouble(x)), r[base + 3]);
            }
            // A fixed-point value rounded to a number of places, exactly (ties up, as Java's %f):
            // value, buffer, places, fraction bits.
            case "text_fixed_places", "text_ufixed_places" -> {
                java.math.BigDecimal exact = new java.math.BigDecimal(big(x, name.equals("text_fixed_places"))).divide(
                        new java.math.BigDecimal(java.math.BigInteger.ONE.shiftLeft((int) r[base + 3])));
                return writeText(exact.setScale((int) r[base + 2], java.math.RoundingMode.HALF_UP).toPlainString(), y);
            }
            case "time_ns" -> {
                return System.nanoTime();
            }
            case "live_objects" -> {
                // How many class objects are live, for the VM's own tests (RefCountTest) to see when objects are
                // freed; the standard library doesn't declare it.
                return liveObjects();
            }
            default -> {
                if (name.startsWith("io_")) {
                    return io(name, r, base);
                }
                return math(name, Double.longBitsToDouble(x), Double.longBitsToDouble(y));
            }
        }
        out.flush();
        return 0;
    }

    /** Math natives take and return doubles as raw bits. */
    private long math(String name, double x, double y) {
        double v = switch (name) {
            case "sqrt" -> Math.sqrt(x);
            case "sin" -> Math.sin(x);
            case "cos" -> Math.cos(x);
            case "tan" -> Math.tan(x);
            case "atan2" -> Math.atan2(x, y);
            case "exp" -> Math.exp(x);
            case "log" -> Math.log(x);
            case "pow" -> Math.pow(x, y);
            case "floor" -> Math.floor(x);
            case "ceil" -> Math.ceil(x);
            default -> throw trap("unknown native function " + name);
        };
        return Double.doubleToRawLongBits(v);
    }
}
