package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.codegen.CodeGen;
import io.github.thelinuxseal.seal.vm.Trap;
import io.github.thelinuxseal.seal.vm.VM;

/**
 * Reference counting of class objects on the VM: each object must be freed exactly when its last
 * reference goes away, never earlier, and nothing may leak (except cycles, which reference counting
 * cannot free). Classes have no destructors, so the programs print how many objects are live, through
 * a native only these tests declare. `java -cp out io.github.thelinuxseal.seal.RefCountTest`.
 */
public final class RefCountTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final String RES = """
            class Res
            {
                public int id;
                public new(int i) { id = i; }
            }
            """;

    public static void main(String[] args) {
        // live() prints how many objects are live beyond those there when main started.
        run("leaving a scope frees", """
                { Res r = new Res(1); live(); }
                live();
                """, "10", 0);
        run("a second reference keeps it alive", """
                Res a = new Res(2);
                { Res b = a; live(); }
                live();
                a = null;
                live();
                """, "110", 0);
        run("reassigning frees the old object", """
                Res a = new Res(3);
                live();
                a = new Res(4);
                live();
                a = null;
                live();
                """, "110", 0);
        run("a temporary is freed at the end of its statement", """
                int x = new Res(5).id;
                live();
                """, "0", 0);
        run("passing to a function does not free", """
                Res a = new Res(6);
                keep(a);
                live();
                """, "k1", 0);
        run("returning a local hands it to the caller", """
                Res a = make(7);
                live();
                a = null;
                live();
                """, "10", 0);
        run("nested calls pass ownership through", """
                Res a = pass(pass(new Res(4)));
                live();
                System.stdout.print(a.id);
                """, "14", 0);
        run("copying a struct adds references", """
                Holder h;
                h.r = new Res(8);
                { Holder g = h; live(); }
                live();
                h.r = null;
                live();
                """, "110", 0);
        run("freeing an object frees what it holds", """
                {
                    Node a = new Node();
                    a.v = 1;
                    a.next = new Node();
                    a.next.v = 2;
                    a.next.next = new Node();
                    a.next.next.v = 3;
                    live();
                }
                live();
                """, "30", 0);
        run("a struct inside an object", """
                {
                    Box b = new Box();
                    b.h.r = new Res(7);
                    live();
                }
                live();
                """, "20", 0);
        run("arrays of handles", """
                {
                    array<Res><<3>> arr;
                    arr[0] = new Res(1);
                    arr[2] = new Res(3);
                    live();
                    arr[0] = new Res(2);
                    live();
                    arr[0] = arr[2];
                    live();
                }
                live();
                """, "2210", 0);
        run("casts keep the same object", """
                Animal a = new Dog();
                a.id = 5;
                Dog d = (Dog)a;
                a = null;
                live();
                System.stdout.print(d.id);
                d = null;
                live();
                """, "150", 0);
        run("temporaries on paths that don't run are not released twice", """
                for (int i = 0; i < 4; i += 1) {
                    if (i % 2 == 1 && make(i).id > 0) { System.stdout.print('y'); }
                }
                live();
                """, "yy0", 0);
        run("static fields keep objects until the program ends", """
                Registry.keep = new Res(6);
                live();
                """, "1", 0);
        run("cycles are not freed (a known limit of reference counting)", """
                {
                    Pair a = new Pair();
                    Pair b = new Pair();
                    a.other = b;
                    b.other = a;
                }
                live();
                """, "2", 2);
        run("many objects in a loop", """
                int made = 0;
                for (int i = 0; i < 1000; i += 1) {
                    Pair n = new Pair();
                    n.other = n.other;
                    made += 1;
                }
                System.stdout.print(made);
                System.stdout.print('|');
                live();
                """, "1000|0", 0);

        // Exceptions: everything the unwound code held is released (§41.4).
        library = true;
        run("an exception leaving a scope releases its objects", """
                try { Res a = new Res(1); Res b = new Res(2); live(); boom(); } catch (IllegalStateException e) { live(); }
                live();
                """, "210", 0);
        run("an exception through frames releases each frame's objects", """
                try { deep(3); } catch (IllegalStateException e) { live(); }
                live();
                """, "10", 0);
        run("a constructor that throws releases the half-made object", """
                try { Bad b = new Bad(); } catch (IllegalStateException e) { live(); }
                live();
                """, "10", 0);
        run("an argument made before a later one throws is released", """
                try { both(new Res(4), boom()); } catch (IllegalStateException e) { }
                try { Holder h; h.r = new Res(5); hold(h, boom()); } catch (IllegalStateException e) { }
                live();
                """, "0", 0);
        run("an array releases its elements when it goes", """
                { array<Res> rs = new array<Res>(2); rs[0] = new Res(1); rs[1] = new Res(2); rs[0] = rs[1]; live(); }
                live();
                """, "10", 0);
        run("the caught exception is released with its variable", """
                try { boom(); } catch (IllegalStateException e) { System.stdout.print('c'); }
                live();
                """, "c0", 0);
        run("try-with-resources releases its resource after closing it", """
                try (Door d = new Door()) { live(); }
                live();
                """, "1closed0", 0);
        run("copies of a string share one buffer", """
                {
                    string s = Integer.toString(12345);
                    string t = s;
                    live();
                    t += "!";            // adds after s's end, in the same buffer
                    live();
                    s += "?";            // s no longer ends where the buffer's used part does: a buffer of its own
                    live();
                    System.stdout.print(s + t);
                }
                live();
                """, "11212345?12345!0", 0);
        run("a string growing in a loop keeps one buffer", """
                string s = Integer.toString(1);
                for (int i = 0; i < 100; i++) { s += 'x'; }
                live();
                System.stdout.print(s.length());
                """, "1101", 0);
        library = false;

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Whether tests load the standard library (for its exceptions). */
    private static boolean library = false;
    private static final Map<String, String> LIBRARY = new LinkedHashMap<>();

    static {
        try {
            Main.readSources(java.nio.file.Path.of("stdlib"), LIBRARY);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void run(String name, String body, String expectedOutput, int expectedLive) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Res.seal", RES);
        files.put("Holder.seal", "struct Holder { public Res r; }");
        files.put("Box.seal", "class Box { public Holder h; }");
        files.put("Node.seal", "class Node { public Node next; public int v; }");
        files.put("Pair.seal", "class Pair { public Pair other; }");
        files.put("Animal.seal", "class Animal { public int id; }");
        files.put("Dog.seal", "class Dog extends(Animal) { }");
        files.put("Registry.seal", "struct Registry { public static Res keep; }");
        files.put("Main.seal", """
                struct Main
                {
                    static int start;
                    static void live() { System.stdout.print(Probe.live() - start); }
                    static void keep(Res r) { System.stdout.print('k'); }
                    static Res make(int i) { Res r = new Res(i); return r; }
                    static Res pass(Res r) { return r; }
                """ + (library ? """
                    public static int boom() { throw new IllegalStateException("boom"); }
                    static void both(Res r, int n) { }
                    static void hold(Holder h, int n) { }
                    static void deep(int n) { Res r = new Res(n); if (n == 1) { boom(); } deep(n - 1); }
                """ : "") + """
                    static int main()
                    {
                        start = Probe.live();
                """ + body + """
                        return 0;
                    }
                }
                """);
        if (library) {
            files.put("Bad.seal", "class Bad { Res held; public new() { held = new Res(9); Main.boom(); } }");
            files.put("Door.seal", "class Door implements(AutoCloseable) { public void close() { System.stdout.print(\"closed\"); } }");
        }
        Map<String, String> lib = new LinkedHashMap<>(library ? LIBRARY : Map.of());
        lib.put("Probe.seal", "struct Probe { @native(\"live_objects\") public static int live() { } }");
        Checker checker = Checker.analyze(files, lib);
        if (!checker.diagnostics().isEmpty()) {
            failures.add(name + ": " + checker.diagnostics());
            return;
        }
        CodeGen.Result gen = CodeGen.generate(checker, "Main");
        if (!gen.errors().isEmpty()) {
            failures.add(name + ": " + gen.errors());
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VM vm = new VM(gen.program(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        vm.setMaxSteps(10_000_000);
        String trap = null;
        try {
            vm.run();
        } catch (Trap t) {
            trap = t.getMessage();
        }
        String out = bytes.toString(StandardCharsets.UTF_8);
        int live = vm.liveObjects();
        if (trap == null && out.equals(expectedOutput) && live == expectedLive) {
            passed++;
        } else {
            failures.add(name + "\n    expected output \"" + expectedOutput + "\", " + expectedLive + " live"
                    + "\n    got             \"" + out + "\", " + live + " live" + (trap == null ? "" : ", trap: " + trap));
        }
    }
}
