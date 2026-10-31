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
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.Warning;
import io.github.thelinuxseal.seal.embed.SealEngine;
import io.github.thelinuxseal.seal.embed.SealException;
import io.github.thelinuxseal.seal.embed.SealScript;
import io.github.thelinuxseal.seal.embed.SealSnippet;
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
        SealEngine a = SealEngine.builder().out(new PrintStream(outA, true, StandardCharsets.UTF_8))
                .allowJava(IntUnaryOperator.class).build();

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
        SealEngine withLib = SealEngine.builder().classPath(lib).allowJava("demo.Greeter")
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
            SealEngine seal = SealEngine.builder().parent(host).library(api).allowJava("demo.*").build();
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
                SealEngine inGame = SealEngine.builder().parent(game).allowJava("demo.*").build();
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

            entries(host, api);
            snippets(host, api);
            allowlist(host, api);
        }
    }

    /** E4: scripts use no Java class the host doesn't allow, by its name or through an object; the library uses any. */
    private static void allowlist(URLClassLoader host, Path api) throws Exception {
        Class<?> context = host.loadClass("demo.Context");
        // A library that hands out the host's Java object, and one with a Java parent.
        Map<String, String> leaky = new LinkedHashMap<>(API);
        leaky.put("api/Raw.seal", "package api;\n\nimport demo.Context;\nimport seal.java.*;\n\n"
                + "class Raw {\n    public static Context make(int ticks) { return new Context(JavaInt.of(ticks)); }\n}\n");
        leaky.put("api/Dice.seal", "package api;\n\nimport java.util.Random;\n\nclass Dice extends(Random) {\n}\n");
        SealEngine seal = SealEngine.builder().parent(host).library(leaky).build();
        check(seal.compile("Hello.seal", HELLO).ok(), "allowlist: the library uses any Java class; a script, its Seal types");

        // Named: imported, written in full, statically imported.
        expectDenied(seal, "import java.io.File;\nstruct Main { }\n", "Main.seal:1:1: Java's java.io.File isn't one the host lets scripts use");
        expectDenied(seal, "struct Main {\n    static void f() { java.lang.System.exit(0); }\n}\n",
                "Main.seal:2:23: Java's java.lang.System isn't one the host lets scripts use");
        expectDenied(seal, "import static java.lang.Math.max;\nstruct Main { }\n",
                "Main.seal:1:1: Java's java.lang.Math isn't one the host lets scripts use");
        // Unnamed: a Java object the library gives, a Java parent's method, Object's getClass().
        String raw = "import api.*;\nimport seal.java.*;\n\nstruct Main {\n    static void f() { Raw.make(1).say(JavaString.of(\"x\")); }\n}\n";
        expectDenied(seal, raw, "Main.seal:5:23: Java's demo.Context isn't one the host lets scripts use");
        expectDenied(seal, "import api.*;\nimport seal.java.*;\n\nstruct Main {\n"
                        + "    static int f() { Dice d = new Dice(); return (int) d.nextInt(JavaInt.of(6)); }\n}\n",
                "Main.seal:5:56: Java's java.util.Random isn't one the host lets scripts use");
        expectDenied(seal, "import seal.java.*;\n\nstruct Main {\n"
                        + "    static string f(JavaObject o) { return (string) o.getClass().getName(); }\n}\n",
                "Main.seal:4:53: Java's java.lang.Class isn't one the host lets scripts use");
        // seal.java's types, and a for-each over a Java collection, need no leave.
        check(seal.compile("Main.seal", "import seal.java.*;\n\nstruct Main {\n"
                + "    static int f() { return (int) JavaString.of(\"abc\").length(); }\n}\n").ok(), "allowlist: seal.java's types");

        // Allowed: a class, a package's classes (not its subpackages'), a class's nested ones, every class.
        check(SealEngine.builder().parent(host).library(leaky).allowJava(context).build().compile("Main.seal", raw).ok(),
                "allowJava(class): a Java object the library gives");
        SealEngine util = SealEngine.builder().allowJava("java.util.*").build();
        SealScript list = util.compile("Main.seal", """
                import java.util.ArrayList;
                import seal.java.*;

                struct Main {
                    static int main() {
                        ArrayList<JavaString> xs = new ArrayList();
                        xs.add(JavaString.of("ab"));
                        xs.add(JavaString.of("c"));
                        int n = 0;
                        for (JavaString s : xs) { n += (int) s.length(); }
                        return n;
                    }
                }
                """);
        check(list.ok() && list.run() == 3, "allowJava(\"java.util.*\"): a package's classes, and a for-each " + list.errors());
        expectDenied(util, "import java.util.concurrent.ConcurrentHashMap;\nstruct Main { }\n",
                "Main.seal:1:1: Java's java.util.concurrent.ConcurrentHashMap isn't one the host lets scripts use");
        String entry = "import java.util.Map;\nimport seal.java.*;\n\nstruct Main {\n"
                + "    static JavaObject key(Map.Entry<JavaString, JavaString> e) { return e.getKey(); }\n}\n";
        check(SealEngine.builder().allowJava("java.util.Map").build().compile("Main.seal", entry).ok(),
                "allowJava(\"java.util.Map\"): the classes nested in it");
        expectDenied(SealEngine.builder().allowJava("java.util.HashMap").build(), entry,
                "Main.seal:1:1: Java's java.util.Map isn't one the host lets scripts use",
                "Main.seal:5:27: Java's java.util.Map$Entry isn't one the host lets scripts use");
        check(SealEngine.builder().allowJava("*").build().compile("Main.seal", "import java.io.File;\nstruct Main { }\n").ok(),
                "allowJava(\"*\"): every class");
        check(throwsWith(IllegalArgumentException.class, "can't allow Java's \"java.**\": allow a class (java.util.ArrayList),"
                        + " a package's classes (java.util.*), or every class (*)",
                () -> SealEngine.builder().allowJava("java.**").build()), "allowJava: a pattern it doesn't take");

        // A snippet may use what the host binds, and no other.
        Object ctx = context.getConstructor(int.class).newInstance(1);
        SealSnippet says = seal.snippet("ctx.say(JavaString.of(\"x\"));").bind("ctx", context, ctx).compile();
        check(says.ok(), "snippet: the bound values' types are allowed " + says.errors());
        SealSnippet files = seal.snippet("java.io.File f = null;").compile();
        check(!files.ok() && files.errors().get(0).message().equals("Java's java.io.File isn't one the host lets scripts use"),
                "snippet: no other Java class " + files.errors());
    }

    /** That a script doesn't compile, with these errors alone. */
    private static void expectDenied(SealEngine seal, String code, String... errors) {
        SealScript s = seal.compile("Main.seal", code);
        check(s.errors().stream().map(Diagnostic::toString).toList().equals(List.of(errors)),
                "allowlist: " + List.of(errors) + "; got " + s.errors());
    }

    private static String macro(String name, String title, String extra) {
        return """
                import api.*;

                class %s extends(Macro) {%s
                    public string title() { return "%s"; }
                    public int act(Game game) { game.say("%s"); return 1; }
                }
                """.formatted(name, extra, title, title);
    }

    /** E3: the host's entry types, checked while compiling, and the script's classes of them, made by create. */
    private static void entries(URLClassLoader host, Path api) throws Exception {
        Class<?> hook = host.loadClass("demo.Hook");
        Class<?> context = host.loadClass("demo.Context");
        SealEngine seal = SealEngine.builder().parent(host).library(api).entry(hook).build();

        // Each of the script's classes that is a Hook, in the files' order; not an abstract one, nor the library's.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Hello.seal", macro("Hello", "hello", ""));
        files.put("Base.seal", "import api.*;\n\nabstract class Base extends(Macro) {\n    public string title() { return \"base\"; }\n}\n");
        files.put("Bye.seal", macro("Bye", "bye", "\n    public new() { }"));
        files.put("Helper.seal", "class Helper {\n    public new(int x) { }\n}\n");
        SealScript two = seal.compile(files);
        check(two.ok(), "entries: a script with two macros compiles " + two.errors());
        if (two.ok()) {
            List<?> made = two.create(hook);
            List<Object> names = new ArrayList<>();
            for (Object m : made) {
                names.add(hook.getMethod("name").invoke(m));
            }
            check(names.equals(List.of("hello", "bye")), "create: each of the script's Hooks, in order " + names);
            check(two.create(hook).get(0) != made.get(0), "create: new objects each time");
            Object ctx = context.getConstructor(int.class).newInstance(1);
            hook.getMethod("run", context).invoke(made.get(1), ctx);
            check(List.of("bye").equals(context.getField("said").get(ctx)), "create: the host calls what it made");
        }

        // A Hook the host can't make with new() is an error where the class is.
        SealScript noNew = seal.compile("Bye.seal", macro("Bye", "bye", "\n    public new(int x) { }"));
        check(!noNew.ok() && noNew.errors().size() == 1 && noNew.errors().get(0).toString().equals(
                        "Bye.seal:3:1: Bye is a Hook, which the host makes with new(), but Bye has no constructor without parameters"),
                "entries: one without new() " + noNew.errors());
        SealScript privateNew = seal.compile("Bye.seal", macro("Bye", "bye", "\n    private new() { }"));
        check(!privateNew.ok() && privateNew.errors().get(0).message().endsWith("but Bye's new() is private"),
                "entries: one whose new() is private " + privateNew.errors());

        // The library's own Hooks aren't the script's.
        Map<String, String> withDefault = new LinkedHashMap<>(API);
        withDefault.put("api/Default.seal", "package api;\n\nclass Default extends(Macro) {\n"
                + "    public string title() { return \"default\"; }\n    public int act(Game game) { return 0; }\n}\n");
        SealEngine defaults = SealEngine.builder().parent(host).library(withDefault).entry(hook).build();
        check(defaults.compile("Hello.seal", macro("Hello", "hello", "")).create(hook).size() == 1,
                "create: the library's Hooks aren't the script's");

        // A fault while one is made.
        SealScript throwing = seal.compile("Bad.seal", macro("Bad", "bad", "\n    public new() { throw new IllegalStateException(\"no\"); }"));
        check(throwsWith(SealException.class, "uncaught IllegalStateException: no", () -> throwing.create(hook)),
                "create: a fault in a constructor is a SealException");

        // The engine's entry types: create needs one; build checks the parent has it.
        check(throwsWith(IllegalArgumentException.class,
                "java.util.function.IntSupplier isn't one of the engine's entry types; name it with SealEngine.Builder.entry",
                () -> two.create(IntSupplier.class)), "create: a type that isn't an entry type");
        check(throwsWith(IllegalArgumentException.class,
                "the parent class loader hasn't got the entry type demo.Hook, so scripts can't implement it",
                () -> SealEngine.builder().entry(hook).build()), "build: an entry type the parent hasn't got");
        SealEngine jdk = SealEngine.builder().entry(IntSupplier.class).build();
        SealScript seven = jdk.compile("Seven.seal", """
                import java.util.function.IntSupplier;
                import seal.java.*;

                class Seven implements(IntSupplier) {
                    public JavaInt getAsInt() { return JavaInt.of(7); }
                }
                """);
        check(seven.ok() && seven.create(IntSupplier.class).get(0).getAsInt() == 7, "entries: a JDK interface " + seven.errors());
        check(seven.ok() && SealEngine.builder().allowJava(IntSupplier.class).build().compile("Seven.seal", """
                import java.util.function.IntSupplier;
                import seal.java.*;

                class Seven implements(IntSupplier) {
                    public new(int x) { }
                    public JavaInt getAsInt() { return JavaInt.of(7); }
                }
                """).ok(), "entries: without entry types, a class needn't have new()");
    }

    /** E3: snippets, statements compiled with Java values bound by name, and a prelude of the host's. */
    private static void snippets(URLClassLoader host, Path api) throws Exception {
        Class<?> context = host.loadClass("demo.Context");
        SealEngine seal = SealEngine.builder().parent(host).library(api).build();
        Object ctx = context.getConstructor(int.class).newInstance(41);
        SealSnippet hi = seal.snippet("game.say(\"hi at tick \" + (game.ticks() + 1));\nint n = 3;\ngame.say(\"n \" + n);")
                .imports("api.*")
                .bind("ctx", context, ctx)
                .prelude("Game game = new Game(ctx);")
                .compile();
        check(hi.ok() && hi.errors().isEmpty(), "snippet: compiles with the library " + hi.errors());
        if (hi.ok()) {
            hi.run();
            check(List.of("hi at tick 42", "n 3").equals(context.getField("said").get(ctx)),
                    "snippet: runs with the value bound " + context.getField("said").get(ctx));
            Object other = context.getConstructor(int.class).newInstance(7);
            hi.run(other);
            check(List.of("hi at tick 8", "n 3").equals(context.getField("said").get(other)), "snippet: run(values), with others");
            check(throwsWith(IllegalArgumentException.class, "the snippet takes 1 values, one for each binding, not 0",
                    () -> hi.run(new Object[0])), "snippet: run with too few values");
        }

        // Errors at their places in the code; the host's own at line 0.
        SealSnippet typo = seal.snippet("game.sya(\"x\");\n  int y = \"s\";").imports("api.*")
                .bind("ctx", context, ctx).prelude("Game game = new Game(ctx);").compile();
        check(typo.errors().stream().map(Diagnostic::toString).toList().equals(List.of(
                        "Snippet.seal:1:1: Game has no method sya",
                        "Snippet.seal:2:11: initializer of y: expected int, found string literal")),
                "snippet: errors at the code's lines and columns " + typo.errors());
        SealSnippet noImport = seal.snippet("int z = 1;").bind("ctx", context, ctx).prelude("Game game = new Game(ctx);").compile();
        check(noImport.errors().size() == 1 && noImport.errors().get(0).line() == 0
                        && noImport.errors().get(0).message().startsWith("unknown type Game"),
                "snippet: an error in the prelude is at line 0, once " + noImport.errors());
        SealEngine warns = SealEngine.builder().warn(Warning.AUTO_CAST).build();
        SealSnippet widen = warns.snippet("double d = 1.5; int i = 2; d += i;").compile();
        check(widen.ok() && widen.warnings().size() == 1 && widen.warnings().get(0).line() == 1
                        && widen.warnings().get(0).column() == 33,
                "snippet: warnings placed as errors are " + widen.warnings());

        // Faults, at the code's lines; System.exit ends the snippet.
        SealSnippet divide = seal.snippet("int zero = 0;\nint w = 1 / zero;").compile();
        check(throwsWith(SealException.class, "trap: division by zero\n    at Snippet.run (Snippet.seal:2)", divide::run),
                "snippet: a fault, at the code's line");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SealEngine printing = SealEngine.builder().out(new PrintStream(out, true, StandardCharsets.UTF_8)).build();
        SealSnippet exits = printing.snippet("System.stdout.println(\"before\");\nSystem.exit(2);\nSystem.stdout.println(\"after\");")
                .compile();
        exits.run();
        check(text(out).equals("before\n"), "snippet: System.exit ends it " + text(out));

        // Java's types as Seal names them: an int is a JavaInt, a String a JavaString.
        out.reset();
        printing.snippet("System.stdout.println((string) who + \" \" + ((int) n + 1));")
                .bind("who", String.class, "Ada").bind("n", int.class, 41).compile().run();
        check(text(out).equals("Ada 42\n"), "snippet: an int and a String bound " + text(out));
        check(throwsWith(IllegalArgumentException.class, "a snippet can't bind an array (xs): bind a List",
                () -> seal.snippet("").bind("xs", int[].class, new int[0])), "snippet: no arrays");
        check(throwsWith(IllegalArgumentException.class, "n is bound as a int, but its value is a java.lang.String",
                () -> seal.snippet("").bind("n", int.class, "4")), "snippet: a value of the wrong type");
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
