package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Tests for the command-line driver, run from the project root: `java -cp out io.github.thelinuxseal.seal.CliTest`. */
public final class CliTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private record Run(int code, String out, String err) {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("seal-cli-test");
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

    private static void tests(Path dir) throws IOException {
        Path ok = write(dir, "ok/Main.seal", """
                struct Main {
                    static int main() {
                        System.stdout.println("hi");
                        return 7;
                    }
                }
                """);
        Run r = run("run", ok.getParent().toString());
        check(r.code() == 7 && r.out().equals("hi\n") && r.err().isEmpty(), "run: exit code is main's result " + r);

        r = run("check", ok.getParent().toString());
        check(r.code() == 0 && r.out().isEmpty() && r.err().isEmpty(), "check: a correct program " + r);

        r = run("dump", ok.getParent().toString());
        check(r.code() == 0 && r.out().startsWith("class Main  (Main.seal)") && r.out().contains("static main()int")
                && r.out().contains("; Main.seal:3") && r.out().contains("ldc \"hi\""), "dump: prints the class files " + r);

        // Seal's VM is removed (docs/JVM.md §13): --vm says so; --jvm, the only backend, is still accepted.
        r = run("run", "--vm", ok.getParent().toString());
        check(r.code() == 2 && r.out().isEmpty() && r.err().equals(Main.VM_REMOVED + "\n"), "run --vm: says the VM is removed " + r);
        r = run("run", "--jvm", ok.getParent().toString());
        check(r.code() == 7 && r.out().equals("hi\n") && r.err().isEmpty(), "run --jvm: still runs on the JVM " + r);
        Path classes = dir.resolve("classes");
        r = run("build", "-d", classes.toString(), ok.getParent().toString());
        check(r.code() == 0 && Files.isRegularFile(classes.resolve("Main.class"))
                        && Files.isRegularFile(classes.resolve("io/github/thelinuxseal/seal/runtime/Natives.class")),
                "build: writes the program's classes and the runtime's " + r);
        if (Files.isRegularFile(classes.resolve("Main.class"))) {
            Process java = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classes.toString(), "Main").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String printed = new String(java.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code;
            try {
                code = java.waitFor();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            check(code == 7 && printed.equals("hi\n"), "java runs what build wrote: exit " + code + ", " + printed);
        }
        Path divide = write(dir, "divide/Main.seal", """
                struct Main {
                    static int main() { int zero = 0; System.stdout.print("x"); return 1 / zero; }
                }
                """);
        r = run("run", "--jvm", divide.getParent().toString());
        check(r.code() == 70 && r.out().equals("x") && r.err().startsWith("trap: division by zero")
                        && r.err().contains("at Main.main (Main.seal:2)"),
                "run --jvm: a fault is a trap, with the Seal lines " + r);
        Path pointers = write(dir, "pointers/Main.seal", """
                struct Main {
                    static int main() { addr<int> p = new<int>(1); &p = 4; System.stdout.print(&p); delete p; delete p; return 0; }
                }
                """);
        r = run("run", "--jvm", pointers.getParent().toString());
        check(r.code() == 70 && r.out().equals("4") && r.err().startsWith("trap: delete of an address that was not allocated with new")
                        && r.err().contains("at Main.main (Main.seal:2)"),
                "run --jvm: addresses work, and a second delete is a trap " + r);

        Path bad = write(dir, "bad/Main.seal", """
                struct Main {
                    static int main() { int x = y; return 0; }
                }
                """);
        r = run("run", bad.getParent().toString());
        check(r.code() == 1 && r.err().contains("Main.seal:2:33: unknown name y") && r.out().isEmpty(),
                "run: compile errors exit 1 and go to stderr " + r);
        r = run("check", bad.toString());
        check(r.code() == 1 && r.err().contains("unknown name y"), "check: a single file " + r);

        Path lossy = write(dir, "lossy/Main.seal", """
                struct Main {
                    static int main() {
                        int i = 10;
                        i += 2.5;
                        return i;
                    }
                }
                """);
        String widen = "lossy/Main.seal:4:9: warning: [-WautoCast] implicit cast from int to double in operand of +=";
        String narrow = "lossy/Main.seal:4:9: warning: [%s] implicit cast from double to int in compound assignment may lose data";
        r = run("run", lossy.getParent().toString());
        check(r.code() == 12 && r.err().isEmpty(), "run: a narrowing compound assignment, no warning by default " + r);
        r = run("run", "-WautoCast", lossy.getParent().toString());
        check(r.code() == 12 && endsWithLines(r.err(), widen, narrow.formatted("-WautoCast")), "run -WautoCast: warns, then runs " + r);
        r = run("check", "-WautoCastLossy", lossy.toString());
        check(r.code() == 0 && endsWithLines(r.err(), narrow.formatted("-WautoCastLossy")) && !r.err().contains("operand"),
                "check -WautoCastLossy: only the lossy cast, and exit 0 " + r);
        r = run("check", "-Wall", lossy.toString());
        check(r.code() == 0 && endsWithLines(r.err(), widen, narrow.formatted("-WautoCastLossy")), "check -Wall: each cast once " + r);
        r = run("check", "-WautoCasts", lossy.toString());
        check(r.code() == 2 && r.err().equals(
                        "unknown flag -WautoCasts; the -W flags are -WautoCast, -WautoCastLossy, -Wassert, and -Wall for all of them\n"),
                "-W with an unknown name " + r);

        Path asserts = write(dir, "asserts/Main.seal", """
                class Main {
                    static int calls = 0;
                    static bit counted() { calls++; return true; }
                    static int main() {
                        assert counted();
                        int x = -3;
                        assert x >= 0;
                        return calls;
                    }
                }
                """);
        r = run("run", asserts.getParent().toString());
        check(r.code() == 0 && r.err().isEmpty(), "asserts are skipped, conditions and all, without -Wassert " + r);
        r = run("run", "-Wassert", asserts.getParent().toString());
        check(r.code() == 70 && r.err().startsWith("uncaught AssertionError: x >= 0\n    at Main.main (Main.seal:7)"),
                "-Wassert runs asserts; a failure names the condition " + r);

        Path trap = write(dir, "trap/Main.seal", """
                struct Main {
                    static int main() { System.stdout.print(1); int z = 0; return 1 / z; }
                }
                """);
        r = run("run", trap.getParent().toString());
        check(r.code() == 70 && r.out().equals("1") && r.err().startsWith("trap: division by zero")
                && r.err().contains("at Main.main (Main.seal:2)"), "run: traps exit 70 with a stack trace " + r);

        Path uncaught = write(dir, "uncaught/Main.seal", """
                struct Main {
                    static void check(int n) {
                        if (n < 0) { throw new IllegalArgumentException("negative: " + n); }
                    }

                    static int main() {
                        check(-3);
                        return 0;
                    }
                }
                """);
        r = run("run", uncaught.getParent().toString());
        check(r.code() == 70 && r.err().equals("""
                uncaught IllegalArgumentException: negative: -3
                    at Main.check (Main.seal:3)
                    at Main.main (Main.seal:7)
                """), "run: an uncaught exception names its class, message and where it was thrown " + r);

        Path streams = write(dir, "streams/Main.seal", """
                struct Main {
                    static int main() {
                        System.stdout.print("name? ");
                        string name = System.stdin.readLine();
                        int total = 0;
                        for (string line : System.stdin) { total += (int)Integer.parse(line); }
                        System.stdout.println("hi " + name + ", sum " + total);
                        System.stderr.println("note: " + total);
                        if (total > 10) { System.exit(4); }
                        return 0;
                    }
                }
                """);
        r = runWithInput("Ada\n1\n2\n", "run", streams.getParent().toString());
        check(r.code() == 0 && r.out().equals("name? hi Ada, sum 3\n") && r.err().equals("note: 3\n"),
                "run: System.stdin, stdout and stderr are the process's " + r);
        r = runWithInput("Bo\n20\n", "run", streams.getParent().toString());
        check(r.code() == 4 && r.out().equals("name? hi Bo, sum 20\n") && r.err().equals("note: 20\n"),
                "run: System.exit sets the exit code " + r);
        r = runWithInput("", "run", streams.getParent().toString());
        check(r.code() == 70 && r.err().startsWith("uncaught seal.util.NoSuchElementException: no more lines to read"),
                "run: reading past the end of input throws " + r);

        Path asm = write(dir, "asm/Main.seal", """
                struct Main { static int main() { System.rawAssembly("nop"); return 0; } }
                """);
        r = run("dump", asm.getParent().toString());
        check(r.code() == 0 && !r.out().contains("rawAssembly"), "dump: System.rawAssembly compiles to nothing " + r);

        Path fail = write(dir, "fail/Main.seal", """
                struct Main { static int main() { System.stdout.print("before "); System.fail("gave up"); return 0; } }
                """);
        r = run("run", fail.getParent().toString());
        check(r.code() == 70 && r.out().equals("before ") && r.err().startsWith("gave up\ntrap: abort"),
                "run: System.fail prints to stderr and stops " + r);

        Path deep = write(dir, "deep/Main.seal", """
                struct Main {
                    static int divide(int a, int b) {
                        int q = 0;
                        q = a / b;
                        return q;
                    }

                    static int main() {
                        string s = "abc";
                        System.stdout.print(divide(4, 2));
                        System.stdout.print(divide(1,
                                             0));
                        return 0;
                    }
                }
                """);
        r = run("run", deep.getParent().toString());
        check(r.code() == 70 && r.err().equals("""
                trap: division by zero
                    at Main.divide (Main.seal:4)
                    at Main.main (Main.seal:11)
                """), "run: each frame names its source line; a call spanning lines names its first " + r);

        Path instance = write(dir, "instance/Main.seal", """
                class Main {
                    int zero;

                    void main() {
                        System.stdout.print(1 / zero);
                    }
                }
                """);
        r = run("run", instance.getParent().toString());
        check(r.code() == 70 && r.err().equals("""
                trap: division by zero
                    at Main.main (Main.seal:5)
                """), "run: an instance main's trace starts at main " + r);

        Path inLib = write(dir, "inlib/Main.seal", """
                struct Main {
                    static int main() {
                        string s = "abc";
                        return (int)s.charAt(5);
                    }
                }
                """);
        r = run("run", inLib.getParent().toString());
        check(r.code() == 70 && r.err().contains("at string.charAt (string.seal:")
                && r.err().contains("at Main.main (Main.seal:4)"), "run: library frames name their file " + r);

        Path other = write(dir, "other/Start.seal", """
                struct Start {
                    static void main() { System.stdout.print(5); }
                }
                """);
        r = run("run", "--main", "Start", other.getParent().toString());
        check(r.code() == 0 && r.out().equals("5"), "run: --main picks the entry class " + r);
        r = run("run", other.getParent().toString());
        check(r.code() == 1 && r.err().contains("no entry point"), "run: missing Main is reported " + r);

        // Several directories and files at once; classes refer to each other.
        Path lib = write(dir, "multi/lib/Helper.seal", "struct Helper { public static int seven() { return 7; } }");
        Path app = write(dir, "multi/app/Main.seal", "struct Main { static int main() { return Helper.seven() * 6; } }");
        r = run("run", lib.getParent().toString(), app.toString());
        check(r.code() == 42, "run: several paths " + r);

        // Without the standard library, a built-in System.stdout prints numbers, characters and bits.
        Path plain = write(dir, "plain/Main.seal", """
                struct Main { static int main() { System.stdout.print(3); return 0; } }
                """);
        r = run("run", "--no-stdlib", plain.getParent().toString());
        check(r.code() == 0 && r.out().equals("3"), "run: --no-stdlib " + r);
        r = run("run", "--no-stdlib", ok.getParent().toString());
        check(r.code() == 1 && r.err().contains("no overload of println"), "run: strings need the standard library " + r);
        r = run("run", "--stdlib", dir.resolve("missing").toString(), plain.getParent().toString());
        check(r.code() == 2 && r.err().contains("standard library not found"), "run: missing stdlib directory " + r);

        Path clash = write(dir, "clash/Math.seal", "struct Math { }");
        write(dir, "clash/Main.seal", "struct Main { static int main() { return 0; } }");
        r = run("check", clash.getParent().toString());
        check(r.code() == 1 && r.err().contains("struct Math is part of the standard library"), "check: stdlib name clash " + r);

        r = run("run");
        check(r.code() == 2 && r.err().contains("usage"), "usage message " + r);

        Path apps = write(dir, "apps/app/Main.seal", "package app;\n\nclass Main {\n    static int main() { return 3; }\n}\n");
        write(dir, "apps/tool/Main.seal", """
                package tool;

                class Main {
                    static void main() { throw new IllegalStateException("from tool"); }
                }
                """);
        String root = apps.getParent().getParent().toString();
        r = run("run", root);
        check(r.code() == 1 && r.err().contains("several classes are named Main: app.Main, tool.Main; choose one with --main,"
                + " as in --main app.Main"), "two Mains in packages need --main " + r);
        r = run("run", "--main", "app.Main", root);
        check(r.code() == 3, "--main with a package " + r);
        r = run("run", "--main", "tool.Main", root);
        check(r.code() == 70 && r.err().contains("uncaught IllegalStateException: from tool")
                && r.err().contains("at tool.Main.main (Main.seal:4)"), "traces name the package " + r);

        Path wrongName = write(dir, "named/Wrong.seal", "struct Right { }");
        r = run("check", wrongName.getParent().toString());
        check(r.code() == 1 && r.err().contains("struct Right must be in a file named Right"), "file name rule " + r);

        classPath(dir);
        jarsAndLibraries(dir);
        javaFiles(dir);
    }

    /**
     * A program's own .java files (docs/JAVA.md §11): those that use none of its Seal classes are
     * compiled first, and Seal's code uses theirs; those that do, in a library, after Seal's
     * classes, against them, as a Fabric mod's mixins are.
     */
    private static void javaFiles(Path dir) throws IOException {
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        String plain = write(dir, "java-files/plain/Main.seal", """
                import seal.java.*;

                class Main {
                    static void main() {
                        System.stdout.println((string) Helper.shout(JavaString.of("hi")) + " " + (int) Helper.twice(JavaInt.of(21)));
                    }
                }
                """).getParent().toString();
        Path helper = write(dir, "java-files/plain/Helper.java", """
                public class Helper {
                    public static String shout(String s) { return s.toUpperCase() + "!"; }
                    public static int twice(int x) { return 2 * x; }
                }
                """);
        Run r = run("run", plain);
        check(r.code() == 0 && r.out().equals("HI! 42\n") && r.err().isEmpty(), "run: Seal uses a .java file's class " + r);
        r = run("check", plain);
        check(r.code() == 0 && r.err().isEmpty(), "check: with a .java file " + r);
        r = run("run", plain + "/Main.seal", helper.toString());
        check(r.code() == 0 && r.out().equals("HI! 42\n"), "run: a .java file given by itself " + r);

        String pkg = write(dir, "java-files/pkg/com/example/Main.seal", """
                package com.example;

                import com.example.util.Counter;
                import seal.java.*;

                class Main {
                    static void main() {
                        Counter c = new Counter();
                        c.add(JavaInt.of(3));
                        System.stdout.println((string) Names.joined() + " " + (int) c.total());
                    }
                }
                """).getParent().getParent().getParent().toString();
        write(dir, "java-files/pkg/com/example/Names.java", """
                package com.example;

                public class Names {
                    public static String joined() { return String.join(", ", java.util.List.of("Ada", "Grace")); }
                }
                """);
        write(dir, "java-files/pkg/com/example/util/Counter.java", """
                package com.example.util;

                public class Counter {
                    private int total;
                    public void add(int n) { total += n; }
                    public int total() { return total; }
                }
                """);
        r = run("run", "--main", "com.example.Main", pkg);
        check(r.code() == 0 && r.out().equals("Ada, Grace 3\n"), "run: a Java class of the same package needs no import " + r);
        Path classes = dir.resolve("java-files/pkg-classes");
        r = run("build", "--main", "com.example.Main", "-d", classes.toString(), pkg);
        check(r.code() == 0 && Files.isRegularFile(classes.resolve("com/example/Names.class"))
                && Files.isRegularFile(classes.resolve("com/example/util/Counter.class")), "build: writes the Java classes too " + r);
        String got = runProcess(dir, javaCommand, "-cp", classes.toString(), "com.example.Main");
        check(got.equals("Ada, Grace 3\n"), "java runs what build wrote: " + got);
        r = run("dump", "--main", "com.example.Main", pkg);
        check(r.code() == 0 && r.out().contains("class com.example.Names  (Names.java)"), "dump: prints the Java classes " + r);

        // A library: Seal uses Format, a Java class, and Caller, a Java class, uses Seal's Hooks.
        String lib = write(dir, "java-files/lib/com/example/Hooks.seal", """
                package com.example;

                import com.example.glue.Format;
                import seal.java.*;

                class Hooks {
                    static int calls = 0;

                    public static JavaString called(JavaString who) {
                        calls++;
                        return Format.bracket(JavaString.of((string) who + " #" + calls));
                    }
                }
                """).getParent().getParent().getParent().toString();
        write(dir, "java-files/lib/com/example/glue/Format.java", """
                package com.example.glue;

                public class Format {
                    public static String bracket(String s) { return "[" + s + "]"; }
                }
                """);
        write(dir, "java-files/lib/com/example/hooks/Caller.java", """
                package com.example.hooks;

                import com.example.*;

                public class Caller {
                    public static void main(String[] args) {
                        System.out.println(Hooks.called("Ada") + " " + com.example.hooks.Again.twice());
                    }
                }
                """);
        write(dir, "java-files/lib/com/example/hooks/Again.java", """
                package com.example.hooks;

                public class Again {
                    public static String twice() { return com.example.Hooks.called("Grace"); }
                }
                """);
        Path libJar = dir.resolve("java-files/lib.jar");
        r = run("build", "--library", "--jar", libJar.toString(), lib);
        check(r.code() == 0 && r.err().isEmpty(), "build --library: Java that uses Seal, after it " + r);
        got = runProcess(dir, javaCommand, "-cp", libJar.toString(), "com.example.hooks.Caller");
        check(got.equals("[Ada #1] [Grace #2]\n"), "Java's code calls Seal's, which calls Java's: " + got);
        r = run("check", lib);
        check(r.code() == 0 && r.err().isEmpty(), "check: compiles the Java that uses Seal, against it " + r);
        r = run("run", lib);
        check(r.code() == 2 && r.err().contains("Again.java uses Seal's classes, which only a library's .java files can"),
                "run: a program's Java can't use Seal's classes " + r);

        Path uses = write(dir, "java-files/lib/com/example/Uses.seal", """
                package com.example;

                import com.example.hooks.Caller;

                class Uses {
                    static void go() { Again.twice(); }
                }
                """);
        r = run("check", lib);
        check(r.code() == 1 && r.err().contains("no type com.example.hooks.Caller to import; com.example.hooks.Caller is in ")
                && r.err().contains("Caller.java, which uses Seal's classes, so it is compiled after them"),
                "check: Seal can't import what is compiled after it " + r);
        Files.writeString(uses, """
                package com.example;

                import com.example.hooks.*;

                class Uses {
                    static void go() { Again.twice(); }
                }
                """);
        r = run("check", lib);
        check(r.code() == 1 && r.err().contains("unknown name Again; com.example.hooks.Again is in ")
                && r.err().contains("which uses Seal's classes"), "check: nor use it by a * import " + r);
        Files.delete(uses);

        Path twice = write(dir, "java-files/lib/com/example/Hooks.java", "package com.example;\npublic class Hooks { }\n");
        r = run("check", lib);
        check(r.code() == 1 && r.err().contains("com.example.Hooks is declared twice: in ") && r.err().contains("Hooks.java"),
                "a class in Seal and in Java " + r);
        Files.delete(twice);
        Path bad = write(dir, "java-files/lib/com/example/glue/Bad.java", "package com.example.glue;\npublic class Bad { int x = \"no\"; }\n");
        r = run("build", "--library", "--jar", libJar.toString(), lib);
        check(r.code() == 1 && r.err().contains("Bad.java:2: error: incompatible types"), "javac's errors, before Seal's code " + r);
        Files.writeString(bad, "package com.example.hooks;\npublic class Bad { void f() { com.example.Hooks.nope(); } }\n");
        r = run("build", "--library", "--jar", libJar.toString(), lib);
        check(r.code() == 1 && r.err().contains("Bad.java:2: error: cannot find symbol") && r.err().contains("method nope()"),
                "javac's errors, after Seal's code " + r);
        Files.delete(bad);
    }

    /**
     * build --jar, --resources and --library (docs/JAVA.md, T5): a program's jar runs with java -jar;
     * a library's classes are Java's to use, its static fields set up the first time Java's code
     * touches one of its classes, as Fabric creates a mod's.
     */
    private static void jarsAndLibraries(Path dir) throws IOException {
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        String app = write(dir, "jar-app/Main.seal", """
                class Main {
                    static string greeting = "from a jar";
                    static void main() { System.stdout.println(greeting); }
                }
                """).getParent().toString();
        Path appJar = dir.resolve("out/app.jar");
        Path res = write(dir, "res/notes/readme.txt", "kept as it is\n").getParent().getParent();
        Run r = run("build", "--jar", appJar.toString(), "--resources", res.toString(), app);
        check(r.code() == 0 && Files.isRegularFile(appJar), "build --jar " + r);
        String got = runProcess(dir, javaCommand, "-jar", appJar.toString());
        check(got.equals("from a jar\n"), "java -jar runs a program's jar: " + got);
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(appJar.toFile())) {
            check(jf.getEntry("notes/readme.txt") != null && jf.getEntry("io/github/thelinuxseal/seal/runtime/Natives.class") != null,
                    "the jar holds the resources and the runtime");
        }
        Path classes = dir.resolve("out/app-classes");
        r = run("build", "-d", classes.toString(), "--resources", res.toString(), app);
        check(r.code() == 0 && Files.isRegularFile(classes.resolve("notes/readme.txt")), "build -d copies the resources " + r);

        String lib = write(dir, "jar-lib/com/example/Greeter.seal", """
                package com.example;

                class Greeter {
                    static string prefix = "hello, ";
                    static int made = 0;
                    string name;
                    public new(string name) { this.name = name; made++; }
                    public string greet() { return prefix + name + " (" + made + ")"; }
                    public static int count() { return made; }
                }
                """).getParent().getParent().getParent().toString();
        Path libJar = dir.resolve("out/lib.jar");
        r = run("build", "--library", "--jar", libJar.toString(), lib);
        check(r.code() == 0, "build --library --jar " + r);
        Path user = write(dir, "java-user/UseGreeter.java", """
                import com.example.Greeter;

                public class UseGreeter {
                    public static void main(String[] args) {
                        Greeter g = new Greeter("Ada");
                        System.out.println(g.greet() + " " + Greeter.count());
                    }
                }
                """);
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        check(javac.run(null, null, null, "-cp", libJar.toString(), "-d", user.getParent().toString(), user.toString()) == 0,
                "Java code compiles against a Seal library");
        got = runProcess(dir, javaCommand, "-cp", libJar + java.io.File.pathSeparator + user.getParent(), "UseGreeter");
        check(got.equals("hello, Ada (1) 1\n"), "Java's code uses the library, its static fields set up first: " + got);
        r = run("run", "--library", lib);
        check(r.code() == 2 && r.err().contains("a library has no main to run"), "run --library " + r);
        r = run("build", "--resources", dir.resolve("no-such-folder").toString(), app);
        check(r.code() == 2 && r.err().contains("resources folder not found"), "a missing resources folder " + r);
    }

    /** What a separate JVM prints, standard output and error together. */
    private static String runProcess(Path dir, String... command) throws IOException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        try {
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** --classpath: Java classes of a class folder and of a jar, which a program imports (docs/JAVA.md §6). */
    private static void classPath(Path dir) throws IOException {
        Path src = write(dir, "java/src/demo/Greeter.java", """
                package demo;

                public class Greeter {
                    public String greet(String who) { return "hello, " + who; }
                }
                """);
        Path other = write(dir, "java/src/demo/Counter.java", """
                package demo;

                public class Counter {
                    public static int next = 41;
                    public static int take() { return ++next; }
                }
                """);
        Path folder = dir.resolve("java/classes");
        Path jarClasses = dir.resolve("java/jar-classes");
        javax.tools.JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        check(javac.run(null, null, null, "-d", folder.toString(), src.toString()) == 0
                && javac.run(null, null, null, "-d", jarClasses.toString(), other.toString()) == 0, "the Java classes compile");
        Path jar = dir.resolve("java/counter.jar");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("demo/Counter.class"));
            out.write(Files.readAllBytes(jarClasses.resolve("demo/Counter.class")));
            out.closeEntry();
        }
        String prog = write(dir, "uses-java/Main.seal", """
                import demo.Greeter;
                import demo.Counter;
                import seal.java.*;

                class Main {
                    static void main() {
                        Greeter g = new Greeter();
                        System.stdout.println(g.greet(JavaString.of("Ada")) + " " + (int)Counter.take());
                    }
                }
                """).getParent().toString();
        String both = folder + java.io.File.pathSeparator + jar;
        Run r = run("run", "--classpath", both, prog);
        check(r.code() == 0 && r.out().equals("hello, Ada 42\n") && r.err().isEmpty(), "run --classpath: a folder and a jar " + r);
        r = run("run", "-cp", both, prog);
        check(r.code() == 0 && r.out().equals("hello, Ada 42\n"), "run -cp " + r);
        r = run("check", "-cp", both, prog);
        check(r.code() == 0 && r.err().isEmpty(), "check -cp " + r);
        r = run("run", prog);
        check(r.code() == 1 && r.err().contains("no type demo.Greeter to import"), "run: without the class path " + r);
        r = run("run", "--classpath", dir.resolve("java/missing.jar").toString(), prog);
        check(r.code() == 2 && r.err().contains("class path entry not found: "), "run: a missing class path entry " + r);
        Path classes = dir.resolve("uses-java-classes");
        r = run("build", "-cp", both, "-d", classes.toString(), prog);
        check(r.code() == 0 && Files.isRegularFile(classes.resolve("Main.class"))
                && Files.isRegularFile(classes.resolve("io/github/thelinuxseal/seal/runtime/JavaValues.class"))
                && !Files.exists(classes.resolve("demo")), "build: writes the program and the runtime, not the class path's " + r);
    }

    /** Whether the text is exactly these lines, each ending as given (after the directory part of its path). */
    private static boolean endsWithLines(String text, String... ends) {
        String[] lines = text.split("\n");
        if (lines.length != ends.length) {
            return false;
        }
        for (int i = 0; i < ends.length; i++) {
            if (!lines[i].endsWith(ends[i])) {
                return false;
            }
        }
        return true;
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private static Run runWithInput(String input, String... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Run run(String... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
