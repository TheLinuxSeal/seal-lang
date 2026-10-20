package io.github.thelinuxseal.seal.vm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A compiled program: everything the VM needs to run. */
@Deprecated // the VM, behind --vm, is to be removed (docs/JVM.md §13)
public final class Program {

    public static final class Function {
        public final String name;
        public final int[] code;
        public final long[] constants;
        public final int registers;
        /** Bits of fixed memory for locals that live in memory (address taken, or aggregates). */
        public final long frameBits;
        /** Source positions, in order of pc: each applies from its pc up to the next one's. */
        public final List<Line> lines;
        /** Where an exception thrown in [start, end) goes: code that cleans up, then catches or rethrows (§41.4). */
        public final List<Handler> handlers;
        /** The register a handler finds the exception in, or -1 if there are no handlers. */
        public final int excReg;

        public Function(String name, int[] code, long[] constants, int registers, long frameBits) {
            this(name, code, constants, registers, frameBits, List.of());
        }

        public Function(String name, int[] code, long[] constants, int registers, long frameBits, List<Line> lines) {
            this(name, code, constants, registers, frameBits, lines, List.of(), -1);
        }

        public Function(String name, int[] code, long[] constants, int registers, long frameBits, List<Line> lines,
                        List<Handler> handlers, int excReg) {
            this.name = name;
            this.code = code;
            this.constants = constants;
            this.registers = registers;
            this.frameBits = frameBits;
            this.lines = lines;
            this.handlers = handlers;
            this.excReg = excReg;
        }

        /** The source line of the instruction at {@code pc}, or null if unknown. */
        public Line lineAt(int pc) {
            Line found = null;
            for (Line l : lines) {
                if (l.pc() > pc) {
                    break;
                }
                found = l;
            }
            return found;
        }
    }

    /** An exception thrown by an instruction in [start, end) jumps to pad. */
    public record Handler(int start, int end, int pad) {
    }

    /** From instruction {@code pc} on, code comes from {@code file} at {@code line}. */
    public record Line(int pc, String file, int line) {
        @Override
        public String toString() {
            return java.nio.file.Path.of(file).getFileName() + ":" + line;
        }
    }

    /**
     * A concrete dynamic class at run time.
     *
     * @param parent index of the parent class, or -1 for the root
     * @param vtable virtual slot to function index; every class has a `__destroy` slot run by `delete`
     * @param interfaces ids of the interfaces it implements, directly or through parents (§30.2)
     */
    public record RtClass(String name, int parent, Map<Integer, Integer> vtable, List<Integer> interfaces) {
        public RtClass(String name, int parent, Map<Integer, Integer> vtable) {
            this(name, parent, vtable, List.of());
        }
    }

    public final List<Function> functions = new ArrayList<>();
    public final List<RtClass> classes = new ArrayList<>();
    public final List<String> natives = new ArrayList<>();
    public final List<String> strings = new ArrayList<>();
    /** Virtual slot names, for messages and disassembly. */
    public final List<String> slots = new ArrayList<>();
    public int entry = -1;
    /**
     * True when the entry point is the generated function that creates the main class's
     * object and calls its instance main() (§56.1); stack traces leave it out.
     */
    public boolean entryCreatesMain;
    /** Runs before the entry point to initialize static fields, or -1. */
    public int init = -1;
    /** Runs after the entry point returns, releasing static fields; or -1. */
    public int fini = -1;
    public long staticBits = 0;
    /** The virtual slot every class uses for its destroy function, or -1. */
    public int destroySlot = -1;
    /**
     * Where an uncaught exception's message is, for the error report: the bit offset of
     * Exception's `message` field in the object, and of `size` and `chars` in that string.
     * Null without the standard library.
     */
    public long[] messageLayout;
    /** Bit offsets of `size` and `chars` in a bare `string`, for natives that take strings. Null without one. */
    public long[] stringLayout;
    /** Initial contents of static data (string literal characters), copied in at startup; may be null. */
    public Memory staticImage;

    /** Human-readable listing of every function. */
    public String disassemble() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < functions.size(); i++) {
            Function f = functions.get(i);
            sb.append("fn ").append(i).append(' ').append(f.name)
                    .append("  (registers ").append(f.registers).append(", frame ").append(f.frameBits).append(" bits)\n");
            int pc = 0;
            int nextLine = 0;
            while (pc < f.code.length) {
                while (nextLine < f.lines.size() && f.lines.get(nextLine).pc() <= pc) {
                    sb.append("        ; ").append(f.lines.get(nextLine++)).append('\n');
                }
                Op op = Op.of(f.code[pc]);
                sb.append(String.format("  %4d  %-9s", pc, op.name()));
                for (int k = 0; k < op.operands.length(); k++) {
                    int v = f.code[pc + 1 + k];
                    sb.append(k == 0 ? " " : ", ");
                    sb.append(switch (op.operands.charAt(k)) {
                        case 'r' -> v < 0 ? "-" : "r" + v;
                        case 'k' -> "#" + f.constants[v];
                        case 'l' -> "@" + v;
                        case 'f' -> functions.get(v).name;
                        case 'c' -> classes.get(v).name();
                        case 'v' -> slots.get(v);
                        case 'n' -> natives.get(v);
                        case 's' -> "\"" + strings.get(v) + "\"";
                        default -> String.valueOf(v);
                    });
                }
                sb.append('\n');
                pc += op.size();
            }
        }
        return sb.toString();
    }
}
