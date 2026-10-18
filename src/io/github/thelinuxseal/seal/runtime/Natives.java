package io.github.thelinuxseal.seal.runtime;

import java.io.PrintStream;
import java.lang.invoke.MethodHandle;

/**
 * The standard library's natives on the JVM (docs/JVM.md §8.3): static methods named after their
 * {@code @native} names, which compiled programs call. They do what the VM's do (vm/VM.java), so a
 * program prints the same on both. So far: the standard streams, math, time and exiting.
 */
public final class Natives {

    private Natives() {
    }

    /** Where the program's standard output and error go; a runner or a test may point them elsewhere. */
    public static PrintStream out = System.out;
    public static PrintStream err = System.err;

    /** The file ids of the standard streams, as the library's System makes its Readers and Writers (§42.2). */
    private static final long STDIN = 1;
    private static final long STDOUT = 2;
    private static final long STDERR = 3;
    /** A file operation's status for an error (its message is the text the library then takes). */
    private static final long IO_ERROR = -1;

    // ---------------------------------------------------------------- writing

    public static long io_write(long file, String text) {
        return write(file, text);
    }

    public static long io_write_char(long file, char c) {
        return write(file, String.valueOf(c));
    }

    public static long io_write_int(long file, long x) {
        return write(file, Long.toString(x));
    }

    public static long io_write_uint(long file, long x) {
        return write(file, Long.toUnsignedString(x));
    }

    public static long io_write_float(long file, float x) {
        return write(file, Float.toString(x));
    }

    public static long io_write_double(long file, double x) {
        return write(file, Double.toString(x));
    }

    public static long io_write_bit(long file, boolean x) {
        return write(file, x ? "true" : "false");
    }

    public static long io_flush(long file) {
        if (file == STDOUT || file == STDERR) {
            (file == STDERR ? err : out).flush();
            return 0;
        }
        return IO_ERROR;
    }

    /** Text to a standard stream, flushed at once so output and errors interleave as written, as on the VM. */
    private static long write(long file, String text) {
        if (file == STDOUT || file == STDERR) {
            PrintStream to = file == STDERR ? err : out;
            to.print(text);
            to.flush();
            return 0;
        }
        if (file == STDIN) {
            return IO_ERROR;
        }
        throw new UnsupportedOperationException("files are not available on the JVM yet");
    }

    // ---------------------------------------------------------------- math (§42.5)

    public static double sqrt(double x) {
        return Math.sqrt(x);
    }

    public static double sin(double x) {
        return Math.sin(x);
    }

    public static double cos(double x) {
        return Math.cos(x);
    }

    public static double tan(double x) {
        return Math.tan(x);
    }

    public static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static double exp(double x) {
        return Math.exp(x);
    }

    public static double log(double x) {
        return Math.log(x);
    }

    public static double pow(double x, double y) {
        return Math.pow(x, y);
    }

    public static double floor(double x) {
        return Math.floor(x);
    }

    public static double ceil(double x) {
        return Math.ceil(x);
    }

    public static long double_bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    public static long float_bits(float x) {
        return Float.floatToRawIntBits(x) & 0xffffffffL;
    }

    // ---------------------------------------------------------------- the process

    public static long time_ns() {
        return System.nanoTime();
    }

    /** System.exit(code): ends the program at once, through {@link Exit}, which its entry point catches. */
    public static void exit(int code) {
        throw new Exit(code);
    }

    /** System.fail's end: stops the program, as a VM trap does. */
    public static void abort() {
        out.flush();
        throw new Trap("abort");
    }

    /**
     * A compiled program's {@code main(String[])}: runs it ({@code $run}, which gives main's result),
     * and ends the process as the VM's runner does.
     */
    public static void start(MethodHandle run) {
        int code;
        try {
            code = (int) run.invokeExact();
        } catch (Exit e) {
            code = e.code;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        end(code);
    }

    /** Flushes the streams and, for a code other than 0, exits with it. */
    public static void end(int code) {
        out.flush();
        err.flush();
        if (code != 0) {
            System.exit(code);
        }
    }
}
