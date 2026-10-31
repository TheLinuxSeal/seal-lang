package io.github.thelinuxseal.seal.embed;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import io.github.thelinuxseal.seal.check.Diagnostic;
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

    SealScript(SealEngine engine, List<Diagnostic> errors, List<Diagnostic> warnings, Map<String, byte[]> classes) {
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
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

    /** Closes what the script left open, and flushes its output: once the host is done with it. */
    @Override
    public void close() {
        if (ok()) {
            finish();
        }
    }

    private void finish() {
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
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
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
