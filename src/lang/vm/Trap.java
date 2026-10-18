package lang.vm;

import java.util.List;

/** A runtime error raised by the VM, with the call stack at the point of failure. */
public final class Trap extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final transient List<String> stack;
    /** True for an exception nothing caught, rather than a fault in the VM. */
    public final boolean uncaught;

    public Trap(String message, List<String> stack) {
        this(message, stack, false);
    }

    public Trap(String message, List<String> stack, boolean uncaught) {
        super(message);
        this.stack = stack;
        this.uncaught = uncaught;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder((uncaught ? "" : "trap: ") + getMessage());
        for (String s : stack) {
            sb.append("\n    at ").append(s);
        }
        return sb.toString();
    }
}
