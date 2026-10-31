package io.github.thelinuxseal.seal.embed;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.runtime.Exit;

/**
 * A snippet (docs/EMBED.md §4): statements a host compiles while it runs, a chat command's code
 * or a setting's formula, as the body of a method that takes the Java values the host binds by
 * name, after a prelude of the host's own statements. It is compiled with the engine's library,
 * and is a script of its own: its static fields, streams and files are its own.
 *
 * <pre>
 *   SealSnippet hi = seal.snippet("game.say(\"hi at tick \" + game.ticks());")
 *           .imports("api.*")
 *           .bind("ctx", Context.class, ctx)
 *           .prelude("Game game = new Game(ctx);")
 *           .compile();
 *   if (hi.ok()) {
 *       hi.run();                     // with ctx; hi.run(other) with another
 *   }
 * </pre>
 *
 * Its errors are in {@link #FILE}, at their lines and columns in the code: the code's first line
 * is the file's first. An error in what the host bound, imported or put in the prelude is at line 0.
 */
public final class SealSnippet implements AutoCloseable {

    /** The file a snippet's code is in, in its errors and in a fault's lines. */
    public static final String FILE = "Snippet.seal";
    /** The struct the snippet is compiled into, which holds its method. */
    private static final String CLASS = "Snippet";

    private final SealScript script;
    private final List<Diagnostic> errors;
    private final List<Diagnostic> warnings;
    private final List<Class<?>> types;
    private final List<Object> values;

    private SealSnippet(SealScript script, int header, List<Class<?>> types, List<Object> values) {
        this.script = script;
        this.errors = place(script.errors(), header);
        this.warnings = place(script.warnings(), header);
        this.types = List.copyOf(types);
        this.values = new ArrayList<>(values);
    }

    /** Whether it compiled: it has no errors, and runs. */
    public boolean ok() {
        return script.ok();
    }

    /** Its errors, in {@link #FILE}, at their places in the code. */
    public List<Diagnostic> errors() {
        return errors;
    }

    /** The warnings the engine asked for, as its errors are placed. */
    public List<Diagnostic> warnings() {
        return warnings;
    }

    /** Runs it with the values bound. */
    public void run() {
        run(values.toArray());
    }

    /**
     * Runs it with other values, one for each binding, in the order they were bound. A fault
     * nothing caught is thrown as a {@link SealException}; System.exit ends the snippet. As it
     * ends, what it left open is closed.
     */
    public void run(Object... args) {
        if (!ok()) {
            throw new IllegalStateException("the snippet didn't compile: " + errors.get(0));
        }
        if (args.length != types.size()) {
            throw new IllegalArgumentException("the snippet takes " + types.size() + " values, one for each binding, not "
                    + args.length);
        }
        Method method;
        try {
            method = script.load(CLASS).getMethod("run", types.toArray(Class<?>[]::new));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("the snippet's method is missing", e);
        }
        try {
            method.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof Exit)) {
                throw new SealException(e.getCause());
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (ExceptionInInitializerError e) {
            throw new SealException(e.getCause());
        } finally {
            script.finish();
        }
    }

    /** Lets it take this many more steps, counted from now, as {@link SealScript#limitSteps} does a script. */
    public void limitSteps(long steps) {
        script.limitSteps(steps);
    }

    /** Lets it run for this long from now, as {@link SealScript#limitTime} does a script. */
    public void limitTime(java.time.Duration time) {
        script.limitTime(time);
    }

    /** Stops it at its next step, from any thread, as {@link SealScript#stop} does a script. */
    public void stop() {
        script.stop();
    }

    /** Closes what it left open, and flushes its output. */
    @Override
    public void close() {
        script.close();
    }

    /**
     * The snippet's own diagnostics at their places in its code: the header the code follows on
     * the first line is {@code header} characters long, and what is in it is at line 0.
     */
    private static List<Diagnostic> place(List<Diagnostic> found, int header) {
        // A set: two errors in the header, at their own columns, may be one error at line 0.
        Set<Diagnostic> out = new LinkedHashSet<>();
        for (Diagnostic d : found) {
            if (!d.file().equals(FILE) || d.line() != 1) {
                out.add(d);
            } else if (d.column() <= header) {
                out.add(new Diagnostic(FILE, 0, 0, d.message(), d.warning()));
            } else {
                out.add(new Diagnostic(FILE, 1, d.column() - header, d.message(), d.warning()));
            }
        }
        return List.copyOf(out);
    }

    /** A snippet being put together, by {@link SealEngine#snippet}. */
    public static final class Builder {
        private final SealEngine engine;
        private final String code;
        private final Set<String> imports = new LinkedHashSet<>();
        private final List<String> names = new ArrayList<>();
        private final List<Class<?>> types = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();
        private final StringBuilder prelude = new StringBuilder();

        Builder(SealEngine engine, String code) {
            this.engine = engine;
            this.code = code;
        }

        /** Imports for the code, as a file's: "api.*", "java.util.List". seal.java's types are imported already. */
        public Builder imports(String... names) {
            imports.addAll(List.of(names));
            return this;
        }

        /**
         * A Java value the code uses by name, of the Java type given, as Seal names it: a Context
         * is a Context, an int a JavaInt, a String a JavaString (docs/JAVA.md §3).
         */
        public Builder bind(String name, Class<?> type, Object value) {
            if (type.isArray()) {
                throw new IllegalArgumentException("a snippet can't bind an array (" + name + "): bind a List");
            }
            if (type == void.class || (!type.isPrimitive() && type.getCanonicalName() == null)) {
                throw new IllegalArgumentException("a snippet can't bind a value of type " + type.getName() + " (" + name + ")");
            }
            Class<?> boxed = type.isPrimitive() ? BOXES.get(type) : type;
            if (type.isPrimitive() ? !boxed.isInstance(value) : value != null && !type.isInstance(value)) {
                throw new IllegalArgumentException(name + " is bound as a " + type.getName() + ", but its value is a "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            names.add(name);
            types.add(type);
            values.add(value);
            return this;
        }

        /**
         * Statements of the host's that run before the code, on the bound values: the code's own
         * values, made from them, as {@code Game game = new Game(ctx);}.
         */
        public Builder prelude(String statements) {
            prelude.append(statements.replace('\n', ' ').replace('\r', ' ')).append(' ');
            return this;
        }

        /** Compiles it, with the engine's library: its errors are the snippet's {@link SealSnippet#errors}. */
        public SealSnippet compile() {
            // One line before the code, so that the code's lines are the file's.
            StringBuilder header = new StringBuilder("import seal.java.*; ");
            Set<String> all = new LinkedHashSet<>(imports);
            List<String> params = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                Class<?> t = types.get(i);
                params.add(sealName(t, all) + " " + names.get(i));
            }
            for (String i : all) {
                header.append("import ").append(i).append("; ");
            }
            header.append("struct ").append(CLASS).append(" { public static void run(").append(String.join(", ", params))
                    .append(") { ").append(prelude);
            String file = header + code + "\n}\n}\n";
            // The types of what the host binds are its to give: the snippet may use them.
            List<String> bound = types.stream().filter(t -> !t.isPrimitive()).map(Class::getName).toList();
            SealScript script = engine.compile(Map.of(FILE, file), engine.javaAllowed().with(bound));
            return new SealSnippet(script, header.length(), types, values);
        }

        /** How Seal names a Java type: seal.java's own for the primitives, String, Object and the boxes; else imported. */
        private static String sealName(Class<?> t, Set<String> imports) {
            String own = SEAL_JAVA.get(t);
            if (own != null) {
                return own;
            }
            Class<?> top = t;
            while (top.getEnclosingClass() != null) {
                top = top.getEnclosingClass();
            }
            imports.add(top.getCanonicalName());
            return t.getCanonicalName().substring(top.getPackageName().isEmpty() ? 0 : top.getPackageName().length() + 1);
        }

        private static final Map<Class<?>, Class<?>> BOXES = Map.of(int.class, Integer.class, long.class, Long.class,
                short.class, Short.class, byte.class, Byte.class, char.class, Character.class, boolean.class, Boolean.class,
                float.class, Float.class, double.class, Double.class);

        private static final Map<Class<?>, String> SEAL_JAVA = Map.ofEntries(Map.entry(int.class, "JavaInt"),
                Map.entry(long.class, "JavaLong"), Map.entry(short.class, "JavaShort"), Map.entry(byte.class, "JavaByte"),
                Map.entry(char.class, "JavaChar"), Map.entry(boolean.class, "JavaBoolean"), Map.entry(float.class, "JavaFloat"),
                Map.entry(double.class, "JavaDouble"), Map.entry(String.class, "JavaString"), Map.entry(Object.class, "JavaObject"),
                Map.entry(Integer.class, "JavaIntBoxed"), Map.entry(Long.class, "JavaLongBoxed"),
                Map.entry(Short.class, "JavaShortBoxed"), Map.entry(Byte.class, "JavaByteBoxed"),
                Map.entry(Character.class, "JavaCharBoxed"), Map.entry(Boolean.class, "JavaBooleanBoxed"),
                Map.entry(Float.class, "JavaFloatBoxed"), Map.entry(Double.class, "JavaDoubleBoxed"));
    }
}
