package io.github.thelinuxseal.seal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.Warning;
import io.github.thelinuxseal.seal.embed.SealEngine;
import io.github.thelinuxseal.seal.embed.SealException;
import io.github.thelinuxseal.seal.embed.SealScript;
import io.github.thelinuxseal.seal.runtime.Natives;

/**
 * Seal inside a Java program (docs/EMBED.md, E1): scripts compiled by an engine, their errors as
 * data, their classes used from Java, each script a program of its own. Run from the project root:
 * `java -cp out io.github.thelinuxseal.seal.EmbedTest`.
 */
public final class EmbedTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("seal-embed-test");
        try {
            tests(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static final String COUNTER = """
            struct Main {
                static int count = 0;
                static int base = 40;

                static int main() {
                    count++;
                    System.stdout.println("count " + count);
                    return count;
                }

                static int answer() { return base + 2; }
            }
            """;

    private static void tests(Path dir) throws Exception {
        ByteArrayOutputStream outA = new ByteArrayOutputStream();
        SealEngine a = SealEngine.builder().out(new PrintStream(outA, true, StandardCharsets.UTF_8)).build();

        // run(): main's result is the exit code; run again, the static fields keep their values.
        SealScript counter = a.compile("Main.seal", COUNTER);
        check(counter.ok() && counter.errors().isEmpty() && counter.warnings().isEmpty(), "compiles " + counter.errors());
        int first = counter.run();
        int second = counter.run();
        check(first == 1 && second == 2 && text(outA).equals("count 1\ncount 2\n"),
                "run: main's result, and the static fields keep their values: " + first + ", " + second + ", " + text(outA));

        // Each script a program of its own: another compile of the same code, from the same engine, has its own fields.
        SealScript again = a.compile("Main.seal", COUNTER);
        check(again.run() == 1, "two scripts don't share static fields");

        // And its own streams: an engine's scripts write where it says, not where another's do.
        ByteArrayOutputStream outB = new ByteArrayOutputStream();
        SealEngine b = SealEngine.builder().out(new PrintStream(outB, true, StandardCharsets.UTF_8)).build();
        outA.reset();
        SealScript other = b.compile("Main.seal", COUNTER);
        other.run();
        counter.run();
        check(text(outB).equals("count 1\n") && text(outA).equals("count 3\n"),
                "two engines' scripts write to their own streams: A " + text(outA) + ", B " + text(outB));
        check(Natives.out == System.out, "a script's streams are its own copy's, not the runtime's shared Natives");

        // A script's classes, from Java: static methods, with the static fields set up first.
        Object answer = again.load("Main").getMethod("answer").invoke(null);
        check(Integer.valueOf(42).equals(answer), "load: a static method, its static fields set up " + answer);
        SealScript fresh = a.compile("Main.seal", COUNTER);
        Object freshAnswer = fresh.load("Main").getMethod("answer").invoke(null);
        check(Integer.valueOf(42).equals(freshAnswer), "load: static fields set up when Java first uses the class " + freshAnswer);

        // A Seal class implementing Java's interface, which Java's code creates and calls.
        SealScript doubler = a.compile("Doubler.seal", """
                import java.util.function.IntUnaryOperator;
                import seal.java.*;

                class Doubler implements(IntUnaryOperator) {
                    public JavaInt applyAsInt(JavaInt x) { return x + x; }
                }
                """);
        check(doubler.ok(), "a class implementing Java's interface compiles " + doubler.errors());
        if (doubler.ok()) {
            IntUnaryOperator op = (IntUnaryOperator) doubler.load("Doubler").getConstructor().newInstance();
            check(op.applyAsInt(21) == 42, "Java calls a script's class through its interface");
        }

        // Errors as data: file, line, column, message; and no classes.
        SealScript bad = a.compile("Main.seal", """
                struct Main {
                    static void main() {
                        printn(1);
                    }
                }
                """);
        check(!bad.ok() && bad.errors().size() == 1 && bad.errors().get(0).file().equals("Main.seal")
                        && bad.errors().get(0).line() == 3 && bad.errors().get(0).column() == 9
                        && bad.errors().get(0).message().equals("unknown method printn"),
                "errors: file, line, column and message " + bad.errors());
        check(throwsWith(IllegalStateException.class, "the script didn't compile: Main.seal:3:9: unknown method printn",
                () -> bad.load("Main")), "load: a script that didn't compile says why");

        // The warnings the engine asks for.
        SealEngine warns = SealEngine.builder().warn(Warning.AUTO_CAST).build();
        SealScript widened = warns.compile("Main.seal", """
                struct Main {
                    static double main2() {
                        int i = 2;
                        double d = 1.5;
                        d += i;
                        return d;
                    }
                }
                """);
        List<Diagnostic> w = widened.warnings();
        check(widened.ok() && w.size() == 1 && w.get(0).warning() == Warning.AUTO_CAST && w.get(0).line() == 5,
                "warnings: those the engine asks for, as data " + w);
        check(a.compile("Main.seal", "struct Main {\n    static double f() { double d = 1.5; d += 2; return d; }\n}\n")
                .warnings().isEmpty(), "warnings: none unless asked for");

        // A fault: a SealException, with what `seal run` says and the fault as its cause.
        SealScript divide = a.compile("Main.seal", """
                struct Main {
                    static int main() {
                        int zero = 0;
                        return 1 / zero;
                    }
                }
                """);
        try {
            divide.run();
            check(false, "a fault throws");
        } catch (SealException e) {
            check(e.getMessage().equals("trap: division by zero\n    at Main.main (Main.seal:4)")
                    && e.getCause() instanceof ArithmeticException, "a fault: Seal's report, and its cause " + e.getMessage());
        }
        SealScript thrown = a.compile("Main.seal", """
                struct Main {
                    static void main() { throw new IllegalArgumentException("no"); }
                }
                """);
        check(throwsWith(SealException.class, "uncaught IllegalArgumentException: no\n    at Main.main (Main.seal:2)",
                thrown::run), "an uncaught exception: its class, message and lines");
        SealScript exits = a.compile("Main.seal", """
                struct Main {
                    static void main() { System.exit(3); }
                }
                """);
        check(exits.run() == 3, "System.exit's code is run's");

        // Standard input.
        SealEngine reads = SealEngine.builder()
                .in(new ByteArrayInputStream("Ada\n".getBytes(StandardCharsets.UTF_8)))
                .out(new PrintStream(outA, true, StandardCharsets.UTF_8)).build();
        outA.reset();
        reads.compile("Main.seal", """
                struct Main {
                    static void main() { System.stdout.println("hi " + System.stdin.readLine()); }
                }
                """).run();
        check(text(outA).equals("hi Ada\n"), "in: what scripts read " + text(outA));

        // Files: off unless the engine allows them.
        String writeFile = """
                import seal.io.*;

                struct Main {
                    static void main() { Files.writeString("%s", "hi"); }
                }
                """.formatted(dir.resolve("note.txt").toString().replace("\\", "\\\\"));
        check(throwsWith(SealException.class, "uncaught seal.io.IOException: file access is disabled",
                () -> a.compile("Main.seal", writeFile).run()) && !Files.exists(dir.resolve("note.txt")),
                "files: off by default");
        SealEngine withFiles = SealEngine.builder().files(true).build();
        withFiles.compile("Main.seal", writeFile).run();
        check(Files.isRegularFile(dir.resolve("note.txt")) && Files.readString(dir.resolve("note.txt")).equals("hi"),
                "files(true): scripts may use files");

        // A folder of files; a .java file among them is the host's to compile.
        Path folder = dir.resolve("scripts");
        write(folder.resolve("Main.seal"), "struct Main {\n    static int main() { return Helper.seven(); }\n}\n");
        write(folder.resolve("Helper.seal"), "struct Helper {\n    public static int seven() { return 7; }\n}\n");
        check(a.compile(folder).run() == 7, "compile(Path): a folder's files, as one script");
        write(folder.resolve("Tool.java"), "class Tool { }\n");
        SealScript withJava = a.compile(folder);
        check(!withJava.ok() && withJava.errors().get(0).message().startsWith("a script can't have .java files"),
                "compile(Path): a .java file is an error " + withJava.errors());

        // No such class, no main.
        check(throwsWith(IllegalArgumentException.class, "the script has no class Nope", () -> counter.load("Nope")),
                "load: a class the script hasn't got");
        check(throwsWith(IllegalArgumentException.class,
                "no entry point: expected a static `void main()` or `int main()` in class Doubler",
                () -> doubler.run("Doubler")), "run: a class without main");

        // Java classes from the class path the engine is given.
        Path lib = dir.resolve("lib");
        write(lib.resolve("demo/Greeter.java"), """
                package demo;
                public final class Greeter {
                    public static String greet(String name) { return "hello, " + name; }
                }
                """);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        check(javac.run(null, null, null, "-d", lib.toString(), lib.resolve("demo/Greeter.java").toString()) == 0,
                "javac compiles the class path's class");
        outA.reset();
        SealEngine withLib = SealEngine.builder().classPath(lib)
                .out(new PrintStream(outA, true, StandardCharsets.UTF_8)).build();
        SealScript greets = withLib.compile("Main.seal", """
                import demo.Greeter;
                import seal.java.*;

                struct Main {
                    static void main() { System.stdout.println((string) Greeter.greet(JavaString.of("Ada"))); }
                }
                """);
        check(greets.ok(), "classPath: scripts import its classes " + greets.errors());
        if (greets.ok()) {
            greets.run();
            check(text(outA).equals("hello, Ada\n"), "classPath: and call them " + text(outA));
        }
        check(!a.compile("Main.seal", "import demo.Greeter;\nstruct Main { }\n").ok(),
                "classPath: another engine's scripts don't see it");

        // The standard library from a class loader's resources, as seal.jar has it (jar.sh).
        Path res = dir.resolve("res");
        Map<String, String> folderLib = new LinkedHashMap<>();
        Sources.read(Path.of("stdlib"), folderLib);
        List<String> index = new ArrayList<>();
        for (Map.Entry<String, String> e : folderLib.entrySet()) {
            String relative = Path.of("stdlib").relativize(Path.of(e.getKey())).toString().replace('\\', '/');
            index.add(relative);
            write(res.resolve(Sources.BUNDLED + relative), e.getValue());
        }
        write(res.resolve(Sources.BUNDLED + "index"), String.join("\n", index) + "\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {res.toUri().toURL()}, null)) {
            Map<String, String> bundled = Sources.bundled(loader);
            check(bundled != null && bundled.size() == folderLib.size() && bundled.size() > 50
                            && List.copyOf(bundled.values()).equals(List.copyOf(folderLib.values()))
                            && bundled.containsKey("stdlib/seal/lang/Math.seal"),
                    "Sources.bundled: the library from resources, by its index " + (bundled == null ? null : bundled.size()));
        }
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            check(Sources.bundled(empty) == null, "Sources.bundled: null without an index");
        }

        library(dir);
    }

    /** The host's own Java classes, which its loader has: what it calls scripts through, and what it gives them. */
    private static final Map<String, String> HOST = Map.of("demo/Hook.java", """
            package demo;

            /** What the host calls a script's macro through. */
            public interface Hook {
                String name();
                int run(Context ctx);
            }
            """, "demo/Context.java", """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            /** The host's object a macro gets: the tick it runs at, and what it said. */
            public final class Context {
                public final List<String> said = new ArrayList<>();
                private final int ticks;
                public Context(int ticks) { this.ticks = ticks; }
                public int ticks() { return ticks; }
                public void say(String text) { said.add(text); }
            }
            """);

    /** A host's Seal API (E2): Seal's types for its scripts, over the host's Java objects. */
    private static final Map<String, String> API = Map.of("api/Game.seal", """
            package api;

            import demo.Context;
            import seal.java.*;

            /** The game, as a macro sees it: Seal's types, over the host's Context. */
            class Game {
                static int made = 0;
                Context ctx;

                public new(Context ctx) {
                    this.ctx = ctx;
                    made++;
                }

                public void say(string text) { ctx.say(JavaString.of(text)); }

                public int ticks() { return (int) ctx.ticks(); }

                public static int games() { return made; }
            }
            """, "api/Macro.seal", """
            package api;

            import demo.*;
            import seal.java.*;

            /** A macro: the host calls it through Hook, in Java's types; a macro has Seal's. */
            abstract class Macro implements(Hook) {
                public abstract string title();
                public abstract int act(Game game);

                public JavaString name() { return JavaString.of(title()); }
                public JavaInt run(Context ctx) { return JavaInt.of(act(new Game(ctx))); }
            }
            """);

    private static final String HELLO = """
            import api.*;

            class Hello extends(Macro) {
                public string title() { return "hello"; }

                public int act(Game game) {
                    game.say("hi at tick " + (game.ticks() + 1) + ", game " + Game.games());
                    return 1;
                }
            }
            """;

    /**
     * E2: the host's Seal library, compiled with every script, and Java's classes from the host's
     * class loader: a script implements the host's Java interface through the library's class, and
     * the host calls it with its own objects.
     */
    private static void library(Path dir) throws Exception {
        Path hostClasses = dir.resolve("host");
        List<String> javaFiles = new ArrayList<>();
        for (Map.Entry<String, String> e : HOST.entrySet()) {
            write(dir.resolve("host-src").resolve(e.getKey()), e.getValue());
            javaFiles.add(dir.resolve("host-src").resolve(e.getKey()).toString());
        }
        List<String> javacArgs = new ArrayList<>(List.of("-d", hostClasses.toString()));
        javacArgs.addAll(javaFiles);
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, javacArgs.toArray(String[]::new)) == 0,
                "javac compiles the host's classes");
        Path api = dir.resolve("api");
        for (Map.Entry<String, String> e : API.entrySet()) {
            write(api.resolve(e.getKey()), e.getValue());
        }

        try (URLClassLoader host = new URLClassLoader(new URL[] {hostClasses.toUri().toURL()}, EmbedTest.class.getClassLoader())) {
            Class<?> hook = host.loadClass("demo.Hook");
            Class<?> context = host.loadClass("demo.Context");
            SealEngine seal = SealEngine.builder().parent(host).library(api).build();
            SealScript hello = seal.compile("Hello.seal", HELLO);
            check(hello.ok(), "a script extends the library's class, which implements the host's interface " + hello.errors());
            if (hello.ok()) {
                Object macro = hello.load("Hello").getConstructor().newInstance();
                Object ctx = context.getConstructor(int.class).newInstance(41);
                Object result = hook.getMethod("run", context).invoke(macro, ctx);
                check(hook.isInstance(macro) && "hello".equals(hook.getMethod("name").invoke(macro))
                                && Integer.valueOf(1).equals(result)
                                && List.of("hi at tick 42, game 1").equals(context.getField("said").get(ctx)),
                        "the host calls the script through its interface, with its own objects: " + context.getField("said").get(ctx));
                // The library's classes are the script's own: another script's static fields start again.
                SealScript other = seal.compile("Hello.seal", HELLO);
                Object ctx2 = context.getConstructor(int.class).newInstance(1);
                hook.getMethod("run", context).invoke(other.load("Hello").getConstructor().newInstance(), ctx2);
                hook.getMethod("run", context).invoke(macro, ctx);
                check(List.of("hi at tick 2, game 1").equals(context.getField("said").get(ctx2))
                                && List.of("hi at tick 42, game 1", "hi at tick 42, game 2").equals(context.getField("said").get(ctx)),
                        "each script has its own of the library's classes " + context.getField("said").get(ctx));
            }

            // Java's classes from the parent loader alone, with no class path: one by one, and a package's (import demo.*).
            check(seal.compile("Main.seal", "import demo.Context;\nstruct Main { }\n").ok()
                    && seal.compile("Main.seal", "import demo.*;\nstruct Main { static int f(Context c) { return (int) c.ticks(); } }\n").ok(),
                    "parent: scripts use the Java classes of the loader they are loaded under");
            // A jar with no folder entries (as `seal build --jar` writes one): its package is found once a class of it is loaded.
            Path flat = dir.resolve("flat.jar");
            try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(flat))) {
                for (String name : List.of("demo/Hook.class", "demo/Context.class")) {
                    out.putNextEntry(new java.util.jar.JarEntry(name));
                    out.write(Files.readAllBytes(hostClasses.resolve(name)));
                    out.closeEntry();
                }
            }
            try (URLClassLoader game = new URLClassLoader(new URL[] {flat.toUri().toURL()}, EmbedTest.class.getClassLoader())) {
                SealEngine inGame = SealEngine.builder().parent(game).build();
                String wildcard = "import demo.*;\nstruct Main { static int f(Context c) { return (int) c.ticks(); } }\n";
                check(!inGame.compile("Main.seal", wildcard).ok() && inGame.compile("Main.seal", "import demo.Context;\nstruct Main { }\n").ok(),
                        "parent: a jar without folders has its classes, but no package to import with * before one is loaded");
                game.loadClass("demo.Context");
                check(inGame.compile("Main.seal", wildcard).ok(), "parent: a package the loader has defined, by loading a class of it");
            }
            SealEngine plain = SealEngine.builder().build();
            SealScript unseen = plain.compile("Main.seal", "import demo.Context;\nstruct Main { }\n");
            check(!unseen.ok() && unseen.errors().get(0).message().startsWith("no type demo.Context to import"),
                    "parent: another loader's classes aren't seen " + unseen.errors());

            // The library from a class loader's resources, by its index, as a host's jar has it.
            Path res = dir.resolve("api-res");
            for (Map.Entry<String, String> e : API.entrySet()) {
                write(res.resolve("macros/api/").resolve(e.getKey()), e.getValue());
            }
            write(res.resolve("macros/api/index"), "api/Game.seal\napi/Macro.seal\n");
            try (URLClassLoader jar = new URLClassLoader(new URL[] {res.toUri().toURL()}, null)) {
                SealEngine fromJar = SealEngine.builder().parent(host).library(jar, "macros/api").build();
                SealScript bad = fromJar.compile("Hello.seal", HELLO.replace("game.ticks() + 1", "game.tick()"));
                check(!bad.ok() && bad.errors().get(0).toString().startsWith("Hello.seal:7:"),
                        "library(loader, dir): the library from resources " + bad.errors());
                check(fromJar.compile("Hello.seal", HELLO).ok(), "library(loader, dir): a script compiles with it");
                check(throwsWith(IOException.class, "no library at nothing/: it has no index of its files",
                        () -> SealEngine.builder().library(jar, "nothing").build()), "library(loader, dir): no index");
            }

            // A library's errors when the engine is built; its warnings aren't a script's.
            Map<String, String> broken = new LinkedHashMap<>(API);
            broken.put("api/Broken.seal", "package api;\nclass Broken {\n    int f() { return g(); }\n}\n");
            check(throwsWith(IllegalArgumentException.class,
                    "the engine's library has errors:\napi/Broken.seal:3:22: unknown method g",
                    () -> SealEngine.builder().parent(host).library(broken).build()), "build: the library's errors");
            Map<String, String> widens = new LinkedHashMap<>(API);
            widens.put("api/Widen.seal", "package api;\nclass Widen {\n    static double f(int i) { double d = 1.5; d += i; return d; }\n}\n");
            SealEngine warns = SealEngine.builder().parent(host).library(widens).warn(Warning.AUTO_CAST).build();
            SealScript mine = warns.compile("Main.seal", "struct Main {\n    static double g(int i) { double d = 0.5; d += i; return d; }\n}\n");
            check(mine.ok() && mine.warnings().size() == 1 && mine.warnings().get(0).file().equals("Main.seal"),
                    "warnings: the script's, not the library's " + mine.warnings());
            SealScript clash = warns.compile("api/Game.seal", "struct Game { }\n");
            check(!clash.ok() && clash.errors().get(0).message().startsWith("the engine's library has a file named api/Game.seal"),
                    "a script's file can't have a library file's name " + clash.errors());
        }
    }

    private static boolean throwsWith(Class<? extends Throwable> type, String message, ThrowingRunnable r) {
        try {
            r.run();
            failures.add("expected " + type.getSimpleName() + ": " + message);
            return false;
        } catch (Throwable t) {
            // The message, or a fault's report that starts with it, then the lines it happened at.
            if (type.isInstance(t) && t.getMessage() != null
                    && (t.getMessage().equals(message) || t.getMessage().startsWith(message + "\n    at "))) {
                return true;
            }
            failures.add("expected " + type.getSimpleName() + ": " + message + "; got " + t);
            return false;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String text(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
