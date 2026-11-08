package io.github.thelinuxseal.seal.embed;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.thelinuxseal.seal.ast.Ast;
import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.ClassInfo;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.MemoryClassLoader;
import io.github.thelinuxseal.seal.runtime.Exit;

/**
 * A compiled script (docs/EMBED.md): its errors and warnings, and, when it has no errors, its
 * classes, loaded in the host's JVM. Each script is a program of its own: its static fields, its
 * open files and its standard streams are its own, and two scripts never see each other's. A
 * script's code runs on one thread at a time, as a Lua state's does.
 */
public final class SealScript implements AutoCloseable {

    /** The runtime's class that holds a program's state; each script gets a copy of its own. */
    private static final String NATIVES = "io.github.thelinuxseal.seal.runtime.Natives";
    private static final String RUNTIME = "io.github.thelinuxseal.seal.runtime.";

    private final List<Diagnostic> errors;
    private final List<Diagnostic> warnings;
    private final Loader loader;
    private final Class<?> natives;
    private final boolean countsSteps;
    /** For each of the engine's entry types, the binary names of the script's classes that are one, in order. */
    private final Map<Class<?>, List<String>> entries = new LinkedHashMap<>();

    SealScript(SealEngine engine, List<Diagnostic> errors, List<Diagnostic> warnings, Map<String, byte[]> classes) {
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
        this.countsSteps = engine.countSteps;
        if (classes == null) {
            this.loader = null;
            this.natives = null;
            return;
        }
        this.loader = new Loader(classes, engine.loader());
        try {
            this.natives = Class.forName(NATIVES, true, loader);
            natives.getField("in").set(null, engine.in);
            natives.getField("out").set(null, engine.out);
            natives.getField("err").set(null, engine.err);
            natives.getField("fileAccess").setBoolean(null, engine.files);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the runtime's Natives can't be set up for a script", e);
        }
    }

    /** Whether it compiled: it has no errors, and its classes can be used. */
    public boolean ok() {
        return loader != null;
    }

    /** Its errors, as `seal check` prints them: file, line, column and message. */
    public List<Diagnostic> errors() {
        return errors;
    }

    /** The warnings the engine asked for (its {@code warn}). */
    public List<Diagnostic> warnings() {
        return warnings;
    }

    /**
     * One of its classes, by its binary name, as Class.forName takes it: com.example.Tool, and
     * Outer$Inner for a class inside another. Its static fields are set up, in Seal's order, the
     * first time one of the script's classes is used, as a library's are (docs/JAVA.md §5).
     */
    public Class<?> load(String name) {
        requireOk();
        try {
            return Class.forName(name, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("the script has no class " + name);
        }
    }

    /**
     * Makes each class of the script's own files (not the library's) that is the entry type given,
     * and isn't abstract, with its constructor without parameters, in the order the files and
     * their classes are in: a macro mod's macros, a program's plugins. The type must be one the
     * engine was built with ({@link SealEngine.Builder#entry}), which compiling checked the
     * classes against. A fault while one is made, in its constructor or the script's static
     * fields, is thrown as a {@link SealException}.
     */
    public <T> List<T> create(Class<T> type) {
        requireOk();
        List<String> names = entries.get(type);
        if (names == null) {
            throw new IllegalArgumentException(type.getName() + " isn't one of the engine's entry types; name it with "
                    + "SealEngine.Builder.entry");
        }
        List<T> made = new ArrayList<>();
        for (String name : names) {
            try {
                made.add(type.cast(Class.forName(name, true, loader).getConstructor().newInstance()));
            } catch (InvocationTargetException e) {
                throw new SealException(e.getCause());
            } catch (ExceptionInInitializerError e) {
                throw new SealException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("the script's " + name + " can't be made", e);
            }
        }
        return made;
    }

    /**
     * Finds the classes of the script's files that are each entry type, which the host makes:
     * those that aren't abstract need a constructor without parameters that isn't private; an
     * error for each that hasn't one. A class with type parameters or shapes isn't one class on
     * the JVM, and isn't one the host makes.
     */
    List<Diagnostic> findEntries(Checker checker, Set<String> files, Collection<Class<?>> types) {
        List<Diagnostic> errors = new ArrayList<>();
        for (Class<?> type : types) {
            entries.put(type, new ArrayList<>());
        }
        if (types.isEmpty()) {
            return errors;
        }
        for (ClassInfo c : checker.allClasses()) {
            if (!files.contains(c.file) || c.builtin || c.java != null || !c.dynamic || !c.typeParams.isEmpty()
                    || c.shapes.size() != 1 || !c.shapes.get(0).binders.isEmpty()) {
                continue;
            }
            Class<?> k;
            try {
                k = Class.forName(JvmGen.binaryName(c), false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (k.isInterface() || Modifier.isAbstract(k.getModifiers())) {
                continue;
            }
            for (Class<?> type : types) {
                if (!type.isAssignableFrom(k)) {
                    continue;
                }
                String cannot = cannotMake(c);
                if (cannot == null) {
                    entries.get(type).add(k.getName());
                } else {
                    Ast.Pos at = c.decl.pos();
                    errors.add(new Diagnostic(c.file, at.line(), at.column(), c.name + " is a " + type.getSimpleName()
                            + ", which the host makes with new(), but " + cannot));
                }
            }
        }
        return errors;
    }

    /** Why the host can't make a class with new(), or null if it can. */
    private static String cannotMake(ClassInfo c) {
        if (c.constructors.isEmpty()) {
            return null;
        }
        for (var m : c.constructors) {
            Ast.Constructor k = (Ast.Constructor) m.member;
            if (k.params().isEmpty()) {
                return k.modifiers().access() == Ast.Access.PRIVATE ? c.name + "'s new() is private" : null;
            }
        }
        return c.name + " has no constructor without parameters";
    }

    /** Runs Main's main(), as `seal run` does; gives its exit code. */
    public int run() {
        return run("Main");
    }

    /**
     * Runs the static main() of the class named, as `seal run --main` does, and gives its exit code:
     * main's result, 0 for a void main, or System.exit's code. A fault nothing caught is thrown as a
     * {@link SealException}. When main ends, what the script left open is closed, as when a program
     * ends; its static fields keep their values, as Java's do when main is called again.
     */
    public int run(String mainClass) {
        Class<?> main = load(mainClass);
        Method entry;
        try {
            entry = main.getMethod("main");
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no entry point: expected a static `void main()` or `int main()` in class "
                    + mainClass);
        }
        if (!Modifier.isStatic(entry.getModifiers())) {
            throw new IllegalArgumentException(mainClass + "'s main() isn't static; a script's main must be");
        }
        try {
            return switch (entry.invoke(null)) {
                case null -> 0;
                case Number n -> n.intValue();
                default -> 0;
            };
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exit exit) {
                return exit.code;
            }
            throw new SealException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } finally {
            finish();
        }
    }

    /**
     * Lets the script take {@code steps} more steps, counted from now (docs/EMBED.md §6): rounds of
     * loops, and calls. At the next one after, wherever it is, it stops: the call the host made
     * into it throws a {@code Stopped}, which a {@link SealException} of it says
     * ({@link SealException#stopped}), and so does every step after, until the host sets the limit
     * again. Without a limit, a script takes as many as it does.
     */
    public void limitSteps(long steps) {
        requireCounting();
        try {
            natives.getMethod("limitSteps", long.class).invoke(null, steps);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the runtime's Natives.limitSteps failed", e);
        }
    }

    /** As above, a time: the script stops at its first step past {@code time} from now. */
    public void limitTime(Duration time) {
        requireCounting();
        long deadline;
        try {
            deadline = Math.addExact(System.nanoTime(), time.toNanos());
        } catch (ArithmeticException e) {
            deadline = Long.MAX_VALUE; // longer than a JVM runs
        }
        setNatives("stepDeadline", deadline);
    }

    /**
     * Stops the script at its next step, wherever it is running: from another thread, a watchdog,
     * a player's command. Running, it stops within a few steps; waiting in a call to the host that
     * blocks it, at its first step after the call returns. It stays stopped; to run it again,
     * compile it again.
     */
    public void stop() {
        requireCounting();
        try {
            natives.getMethod("requestStop").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the runtime's Natives.requestStop failed", e);
        }
    }

    private void requireCounting() {
        requireOk();
        if (!countsSteps) {
            throw new IllegalStateException("the script doesn't count its steps, so it can't be limited or stopped:"
                    + " its engine was built with countSteps(false)");
        }
    }

    private void setNatives(String field, Object value) {
        try {
            natives.getField(field).set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the runtime's Natives." + field + " can't be set", e);
        }
    }

    /** Closes what the script left open, and flushes its output: once the host is done with it. */
    @Override
    public void close() {
        if (ok()) {
            finish();
        }
    }

    void finish() {
        try {
            natives.getMethod("finish").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the runtime's Natives.finish failed", e);
        }
    }

    private void requireOk() {
        if (!ok()) {
            throw new IllegalStateException("the script didn't compile: " + errors.get(0));
        }
    }

    /**
     * Loads the script's classes, and its own copy of the runtime's Natives, which holds a
     * program's state; the rest of the runtime is the engine's, shared, and every other class the
     * parent's.
     */
    private static final class Loader extends MemoryClassLoader {
        Loader(Map<String, byte[]> classes, ClassLoader parent) {
            super(classes, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    byte[] bytes = classes.get(name);
                    if (bytes == null && (name.equals(NATIVES) || name.startsWith(NATIVES + "$"))) {
                        bytes = runtimeBytes(name);
                    }
                    if (bytes != null) {
                        // The script's own first, even where the parent has a class of the name: a Seal library's.
                        c = defineClass(name, bytes, 0, bytes.length);
                    } else if (name.startsWith(RUNTIME)) {
                        c = SealScript.class.getClassLoader().loadClass(name);
                    } else {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        private static byte[] runtimeBytes(String name) throws ClassNotFoundException {
            try (InputStream in = SealScript.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name + " is missing from the compiler");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
