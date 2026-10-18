package io.github.thelinuxseal.seal.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.github.thelinuxseal.seal.runtime.Exit;
import io.github.thelinuxseal.seal.runtime.Natives;
import io.github.thelinuxseal.seal.runtime.Trap;

/**
 * Runs a compiled program in this JVM, its classes in a class loader of their own, or writes its
 * class files so that {@code java} runs it (docs/JVM.md §2).
 */
public final class JvmRunner {

    private JvmRunner() {
    }

    /** How a run ended: main's exit code, or the fault that stopped the program (then the code is 70). */
    public record Outcome(int code, Throwable fault) {
    }

    /** Runs the program with its standard output and error going to {@code out} and {@code err}. */
    public static Outcome run(JvmGen.Result program, PrintStream out, PrintStream err) {
        PrintStream oldOut = Natives.out;
        PrintStream oldErr = Natives.err;
        Natives.out = out;
        Natives.err = err;
        try {
            Class<?> main = new Loader(program.classes(), JvmRunner.class.getClassLoader()).loadClass(program.mainClass());
            MethodHandle run = MethodHandles.publicLookup().findStatic(main, JvmGen.RUN_METHOD,
                    MethodType.methodType(int.class));
            int code;
            try {
                code = (int) run.invokeExact();
            } catch (Exit e) {
                code = e.code;
            }
            return new Outcome(code, null);
        } catch (Throwable t) {
            return new Outcome(70, t);
        } finally {
            out.flush();
            err.flush();
            Natives.out = oldOut;
            Natives.err = oldErr;
        }
    }

    /**
     * A fault as the VM reports a trap: what happened, then the Seal methods it happened in, from
     * the line numbers the class files carry.
     */
    public static String describe(Throwable t) {
        String what = switch (t) {
            case Trap trap -> trap.getMessage();
            case ArithmeticException a when "/ by zero".equals(a.getMessage()) -> "division by zero";
            case StackOverflowError s -> "call stack overflow";
            default -> t.toString();
        };
        StringBuilder sb = new StringBuilder("trap: " + what);
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getFileName() != null && e.getFileName().endsWith(".seal")) {
                String cls = e.getClassName();
                if (cls.startsWith(JvmGen.LIBRARY_PACKAGE + ".")) {
                    cls = cls.substring(JvmGen.LIBRARY_PACKAGE.length() + 1);
                }
                sb.append("\n    at ").append(cls.replace('$', '.')).append('.').append(e.getMethodName())
                        .append(" (").append(e.getFileName()).append(':').append(e.getLineNumber()).append(')');
            }
        }
        return sb.toString();
    }

    /**
     * Writes the program's class files under {@code dir}, and the runtime's beside them, so that
     * {@code java -cp dir Main} runs it.
     */
    public static void write(JvmGen.Result program, Path dir) throws IOException {
        for (Map.Entry<String, byte[]> e : program.classes().entrySet()) {
            writeClass(dir, e.getKey(), e.getValue());
        }
        for (String name : JvmGen.RUNTIME_CLASSES) {
            String resource = "/" + name.replace('.', '/') + ".class";
            try (InputStream in = JvmRunner.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("the runtime class " + name + " is missing from the compiler");
                }
                writeClass(dir, name, in.readAllBytes());
            }
        }
    }

    private static void writeClass(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** Defines the program's classes, and leaves the runtime's and the JDK's to its parent. */
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
