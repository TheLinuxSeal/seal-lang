package io.github.thelinuxseal.seal.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        return run(program, InputStream.nullInputStream(), out, err, true);
    }

    /** As above, with standard input, and whether the program may use files. */
    public static Outcome run(JvmGen.Result program, InputStream in, PrintStream out, PrintStream err, boolean files) {
        return run(program, in, out, err, files, List.of());
    }

    /**
     * As above, with the jars and class folders of the Java classes the program uses (docs/JAVA.md
     * §6): its classes' loader sees them, after the JDK's.
     */
    public static Outcome run(JvmGen.Result program, InputStream in, PrintStream out, PrintStream err, boolean files,
                              List<Path> classPath) {
        InputStream oldIn = Natives.in;
        PrintStream oldOut = Natives.out;
        PrintStream oldErr = Natives.err;
        boolean oldFiles = Natives.fileAccess;
        Natives.reset();
        Natives.in = in;
        Natives.out = out;
        Natives.err = err;
        Natives.fileAccess = files;
        try (URLClassLoader jars = new URLClassLoader(urls(classPath), JvmRunner.class.getClassLoader())) {
            Class<?> main = Class.forName(program.mainClass(), true, new Loader(program.classes(), jars));
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
            Natives.finish();
            Natives.in = oldIn;
            Natives.out = oldOut;
            Natives.err = oldErr;
            Natives.fileAccess = oldFiles;
        }
    }

    private static URL[] urls(List<Path> classPath) {
        URL[] out = new URL[classPath.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = classPath.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("class path entry " + classPath.get(i) + ": " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * A fault as the VM reports a trap: what happened, then the Seal methods it happened in, from
     * the line numbers the class files carry.
     */
    public static String describe(Throwable t) {
        String seal = sealException(t.getClass());
        if (seal != null) {
            // A Seal exception nothing caught (§41.4): its class and message, as the VM reports it.
            String message = t.getMessage();
            return frames(t, new StringBuilder("uncaught " + seal + (message == null || message.isEmpty() ? "" : ": " + message)));
        }
        if (thrownByJava(t)) {
            // What Java code threw and nothing caught (docs/JAVA.md §4), reported as a Seal exception is.
            String message = t.getMessage();
            return frames(t, new StringBuilder("uncaught " + t.getClass().getName()
                    + (message == null || message.isEmpty() ? "" : ": " + message)));
        }
        String what = switch (t) {
            case Trap trap -> trap.getMessage();
            case ArithmeticException a when "/ by zero".equals(a.getMessage()) -> "division by zero";
            case StackOverflowError s -> "call stack overflow";
            case LinkageError l -> "the generated classes are wrong (a fault in the JVM backend): " + l;
            case NullPointerException n -> "null handle";
            case ClassCastException cc -> "cannot cast: " + cc.getMessage();
            default -> t.toString();
        };
        return frames(t, new StringBuilder("trap: " + what));
    }

    /**
     * Whether an exception is one Java code threw, rather than a fault of Seal code the JVM or the
     * runtime reports (a null handle, division by zero): one thrown above the program's own code,
     * in the JDK or a jar's class, and not on the runtime's behalf.
     */
    private static boolean thrownByJava(Throwable t) {
        if (t instanceof Trap || t instanceof Error) {
            return false;
        }
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < frames.length; i++) {
            String cls = frames[i].getClassName();
            if (cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
                    || cls.startsWith("com.sun.")) {
                continue;
            }
            if (cls.startsWith(JvmGen.RUNTIME)) {
                return false; // the runtime's own work, as a division
            }
            boolean seal = frames[i].getFileName() != null && frames[i].getFileName().endsWith(".seal");
            // What the JVM raises at an instruction of the program's own code is a fault; what that code throws isn't.
            boolean raised = t instanceof NullPointerException || t instanceof ArithmeticException
                    || t instanceof ClassCastException || t instanceof ArrayIndexOutOfBoundsException
                    || t instanceof NegativeArraySizeException || t instanceof ArrayStoreException;
            return !(seal && i == 0 && raised);
        }
        return true;
    }

    /** The Seal name of a Seal exception's class, or null for a Java one: io.github...lang.IllegalStateException is IllegalStateException. */
    private static String sealException(Class<?> c) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(JvmGen.LIBRARY_PACKAGE + ".Exception")) {
                return JvmGen.sealName(c.getName());
            }
        }
        return null;
    }

    private static String frames(Throwable t, StringBuilder sb) {
        for (StackTraceElement e : t.getStackTrace()) {
            // Seal's own methods only: not the generated entry points ($run, $init), which have no Seal line.
            if (e.getFileName() != null && e.getFileName().endsWith(".seal") && e.getLineNumber() > 0) {
                String cls = JvmGen.sealName(e.getClassName());
                sb.append("\n    at ").append(cls).append('.').append(e.getMethodName())
                        .append(" (").append(e.getFileName()).append(':').append(e.getLineNumber()).append(')');
            }
        }
        return sb.toString();
    }

    /**
     * Runs the JVM's bytecode verifier on every class, knowing the program's own classes; gives
     * each problem with the class it is in (none for a correct program).
     */
    public static java.util.List<String> verify(JvmGen.Result program) {
        return verify(program, io.github.thelinuxseal.seal.check.JavaClasses.jdk());
    }

    /** As above, knowing the Java classes of the class path the program uses too (docs/JAVA.md §6). */
    public static java.util.List<String> verify(JvmGen.Result program, io.github.thelinuxseal.seal.check.JavaClasses classPath) {
        java.lang.classfile.ClassFile plain = java.lang.classfile.ClassFile.of();
        java.util.Set<java.lang.constant.ClassDesc> interfaces = new java.util.HashSet<>();
        Map<java.lang.constant.ClassDesc, java.lang.constant.ClassDesc> supers = new java.util.HashMap<>();
        for (byte[] bytes : program.classes().values()) {
            java.lang.classfile.ClassModel m = plain.parse(bytes);
            java.lang.constant.ClassDesc self = m.thisClass().asSymbol();
            if (m.flags().has(java.lang.reflect.AccessFlag.INTERFACE)) {
                interfaces.add(self);
            } else {
                supers.put(self, m.superclass().map(e -> e.asSymbol()).orElse(null));
            }
        }
        java.lang.classfile.ClassFile cf = java.lang.classfile.ClassFile.of(
                java.lang.classfile.ClassFile.ClassHierarchyResolverOption.of(
                        java.lang.classfile.ClassHierarchyResolver.of(interfaces, supers)
                                .orElse(java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(classPath::open))
                                .orElse(java.lang.classfile.ClassHierarchyResolver.defaultResolver())));
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (Map.Entry<String, byte[]> e : program.classes().entrySet()) {
            for (VerifyError v : cf.verify(e.getValue())) {
                problems.add(e.getKey() + ": " + v.getMessage());
            }
        }
        return problems;
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
