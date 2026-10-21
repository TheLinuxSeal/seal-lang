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
                struct Main
                {
                    static int main()
                    {
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
                && r.out().contains("; Main.seal:5") && r.out().contains("ldc \"hi\""), "dump: prints the class files " + r);

        // The VM, deprecated, behind --vm (docs/JVM.md §13); --jvm, the default, is still accepted.
        r = run("run", "--vm", ok.getParent().toString());
        check(r.code() == 7 && r.out().equals("hi\n") && r.err().equals(Main.VM_DEPRECATED + "\n"),
                "run --vm: on the VM, saying it is deprecated " + r);
        r = run("dump", "--vm", ok.getParent().toString());
        check(r.code() == 0 && r.out().contains(" Main.main  (registers") && r.out().contains("NATIVE")
                        && r.err().equals(Main.VM_DEPRECATED + "\n"), "dump --vm: lists the VM's functions " + r);
        r = run("check", "--vm", ok.getParent().toString());
        check(r.code() == 0 && r.err().isEmpty(), "check --vm: runs nothing, so says nothing " + r);
        r = run("build", "--vm", ok.getParent().toString());
        check(r.code() == 2 && r.err().contains("no --vm form"), "build --vm: is refused " + r);
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
                struct Main
                {
                    static int main() { int zero = 0; System.stdout.print("x"); return 1 / zero; }
                }
                """);
        r = run("run", "--jvm", divide.getParent().toString());
        check(r.code() == 70 && r.out().equals("x") && r.err().startsWith("trap: division by zero")
                        && r.err().contains("at Main.main (Main.seal:3)"),
                "run --jvm: a fault is a trap, with the Seal lines " + r);
        Path pointers = write(dir, "pointers/Main.seal", """
                struct Main
                {
                    static int main() { addr<int> p = new<int>(1); &p = 4; System.stdout.print(&p); delete p; delete p; return 0; }
                }
                """);
        r = run("run", "--jvm", pointers.getParent().toString());
        check(r.code() == 70 && r.out().equals("4") && r.err().startsWith("trap: delete of an address that was not allocated with new")
                        && r.err().contains("at Main.main (Main.seal:3)"),
                "run --jvm: addresses work, and a second delete is a trap " + r);

        Path bad = write(dir, "bad/Main.seal", """
                struct Main
                {
                    static int main() { int x = y; return 0; }
                }
                """);
        r = run("run", bad.getParent().toString());
        check(r.code() == 1 && r.err().contains("Main.seal:3:33: unknown name y") && r.out().isEmpty(),
                "run: compile errors exit 1 and go to stderr " + r);
        r = run("check", bad.toString());
        check(r.code() == 1 && r.err().contains("unknown name y"), "check: a single file " + r);

        Path lossy = write(dir, "lossy/Main.seal", """
                struct Main
                {
                    static int main()
                    {
                        int i = 10;
                        i += 2.5;
                        return i;
                    }
                }
                """);
        String widen = "lossy/Main.seal:6:9: warning: [-WautoCast] implicit cast from int to double in operand of +=";
        String narrow = "lossy/Main.seal:6:9: warning: [%s] implicit cast from double to int in compound assignment may lose data";
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
                class Main
                {
                    static int calls = 0;
                    static bit counted() { calls++; return true; }
                    static int main()
                    {
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
        check(r.code() == 70 && r.err().startsWith("uncaught AssertionError: x >= 0\n    at Main.main (Main.seal:9)"),
                "-Wassert runs asserts; a failure names the condition " + r);

        Path trap = write(dir, "trap/Main.seal", """
                struct Main
                {
                    static int main() { System.stdout.print(1); int z = 0; return 1 / z; }
                }
                """);
        r = run("run", trap.getParent().toString());
        check(r.code() == 70 && r.out().equals("1") && r.err().startsWith("trap: division by zero")
                && r.err().contains("at Main.main (Main.seal:3)"), "run: traps exit 70 with a stack trace " + r);

        Path uncaught = write(dir, "uncaught/Main.seal", """
                struct Main
                {
                    static void check(int n)
                    {
                        if (n < 0) { throw new IllegalArgumentException("negative: " + n); }
                    }

                    static int main()
                    {
                        check(-3);
                        return 0;
                    }
                }
                """);
        r = run("run", uncaught.getParent().toString());
        check(r.code() == 70 && r.err().equals("""
                uncaught IllegalArgumentException: negative: -3
                    at Main.check (Main.seal:5)
                    at Main.main (Main.seal:10)
                """), "run: an uncaught exception names its class, message and where it was thrown " + r);

        Path streams = write(dir, "streams/Main.seal", """
                struct Main
                {
                    static int main()
                    {
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
                struct Main
                {
                    static int divide(int a, int b)
                    {
                        int q = 0;
                        q = a / b;
                        return q;
                    }

                    static int main()
                    {
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
                    at Main.divide (Main.seal:6)
                    at Main.main (Main.seal:14)
                """), "run: each frame names its source line; a call spanning lines names its first " + r);

        Path instance = write(dir, "instance/Main.seal", """
                class Main
                {
                    int zero;

                    void main()
                    {
                        System.stdout.print(1 / zero);
                    }
                }
                """);
        r = run("run", instance.getParent().toString());
        check(r.code() == 70 && r.err().equals("""
                trap: division by zero
                    at Main.main (Main.seal:7)
                """), "run: an instance main's trace starts at main " + r);

        Path inLib = write(dir, "inlib/Main.seal", """
                struct Main
                {
                    static int main()
                    {
                        string s = "abc";
                        return (int)s.charAt(5);
                    }
                }
                """);
        r = run("run", inLib.getParent().toString());
        check(r.code() == 70 && r.err().contains("at string.charAt (string.seal:")
                && r.err().contains("at Main.main (Main.seal:6)"), "run: library frames name their file " + r);

        Path other = write(dir, "other/Start.seal", """
                struct Start
                {
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

        Path apps = write(dir, "apps/app/Main.seal", "package app;\n\nclass Main\n{\n    static int main() { return 3; }\n}\n");
        write(dir, "apps/tool/Main.seal", """
                package tool;

                class Main
                {
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
                && r.err().contains("at tool.Main.main (Main.seal:5)"), "traces name the package " + r);

        Path wrongName = write(dir, "named/Wrong.seal", "struct Right { }");
        r = run("check", wrongName.getParent().toString());
        check(r.code() == 1 && r.err().contains("struct Right must be in a file named Right"), "file name rule " + r);

        classPath(dir);
    }

    /**
     * --classpath: its entries must exist, and a program runs with them. A program reaches the classes
     * in them from T1 (docs/JAVA.md §8); the string bridge that reached them before is removed.
     */
    private static void classPath(Path dir) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("java/classes"));
        Path jar = dir.resolve("java/empty.jar");
        new java.util.jar.JarOutputStream(Files.newOutputStream(jar)).close();
        String prog = write(dir, "classpath/Main.seal", """
                class Main
                {
                    static void main() { System.stdout.println("ran"); }
                }
                """).getParent().toString();
        String both = folder + java.io.File.pathSeparator + jar;
        Run r = run("run", "--classpath", both, prog);
        check(r.code() == 0 && r.out().equals("ran\n") && r.err().isEmpty(), "run --classpath: a folder and a jar " + r);
        r = run("run", "-cp", both, prog);
        check(r.code() == 0 && r.out().equals("ran\n"), "run -cp " + r);
        r = run("run", "--classpath", dir.resolve("java/missing.jar").toString(), prog);
        check(r.code() == 2 && r.err().contains("class path entry not found: "), "run: a missing class path entry " + r);
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
