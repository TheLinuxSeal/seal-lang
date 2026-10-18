package lang;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lang.check.Checker;
import lang.codegen.CodeGen;
import lang.vm.Trap;
import lang.vm.VM;

/**
 * Reference counting of class objects: each object must be freed exactly when
 * its last reference goes away, never earlier, and nothing may leak (except
 * cycles, which reference counting cannot free). Destructors print their id
 * so the order of frees is visible. `java -cp out lang.RefCountTest`.
 */
public final class RefCountTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final String RES = """
            class Res
            {
                public int id;
                public new(int i) { id = i; }
                delete() { System.stdout.print(id); }
            }
            """;

    public static void main(String[] args) {
        run("leaving a scope frees", """
                { Res r = new Res(1); }
                System.stdout.print('|');
                """, "1|", 0);
        run("a second reference keeps it alive", """
                Res a = new Res(2);
                { Res b = a; }
                System.stdout.print('|');
                """, "|2", 0);
        run("reassigning frees the old object", """
                Res a = new Res(3);
                System.stdout.print('<');
                a = new Res(4);
                System.stdout.print('>');
                """, "<3>4", 0);
        run("a temporary is freed at the end of its statement", """
                int x = new Res(5).id;
                System.stdout.print('|');
                """, "5|", 0);
        run("passing to a function does not free", """
                Res a = new Res(6);
                keep(a);
                System.stdout.print('|');
                """, "k|6", 0);
        run("returning a local hands it to the caller", """
                Res a = make(7);
                System.stdout.print('|');
                """, "|7", 0);
        run("nested calls pass ownership through", """
                Res a = pass(pass(new Res(4)));
                System.stdout.print('|');
                """, "|4", 0);
        run("copying a struct adds references", """
                Holder h;
                h.r = new Res(8);
                { Holder g = h; }
                System.stdout.print('|');
                """, "|8", 0);
        run("freeing an object frees what it holds", """
                {
                    Node a = new Node();
                    a.v = 1;
                    a.next = new Node();
                    a.next.v = 2;
                    a.next.next = new Node();
                    a.next.next.v = 3;
                }
                System.stdout.print('|');
                """, "123|", 0);
        run("a struct inside an object", """
                {
                    Box b = new Box();
                    b.h.r = new Res(7);
                }
                System.stdout.print('|');
                """, "7|", 0);
        run("arrays of handles", """
                {
                    array<Res><<3>> arr;
                    arr[0] = new Res(1);
                    arr[2] = new Res(3);
                    arr[0] = new Res(2);
                }
                System.stdout.print('|');
                """, "132|", 0);
        run("casts keep the same object", """
                Animal a = new Dog();
                a.id = 5;
                Dog d = (Dog)a;
                a = null;
                System.stdout.print('|');
                """, "|5", 0);
        run("temporaries on paths that don't run are not released twice", """
                for (int i = 0; i < 4; i += 1) {
                    if (i % 2 == 1 && make(i).id > 0) { System.stdout.print('y'); }
                }
                """, "1y3y", 0);
        run("explicit delete with another reference", """
                Res a = new Res(9);
                Res b = a;
                delete a;
                System.stdout.print('|');
                """, "9|", 0);
        run("static fields keep objects until the program ends", """
                Registry.keep = new Res(6);
                System.stdout.print('|');
                """, "|6", 0);
        run("cycles are not freed (a known limit of reference counting)", """
                {
                    Pair a = new Pair();
                    Pair b = new Pair();
                    a.other = b;
                    b.other = a;
                }
                System.stdout.print('|');
                """, "|", 2);
        run("many objects in a loop", """
                int made = 0;
                for (int i = 0; i < 1000; i += 1) {
                    Pair n = new Pair();
                    n.other = n.other;
                    made += 1;
                }
                System.stdout.print(made);
                """, "1000", 0);

        // Exceptions: everything the unwound code held is released (§41.4).
        library = true;
        run("an exception leaving a scope releases its objects", """
                try { Res a = new Res(1); Res b = new Res(2); boom(); } catch (IllegalStateException e) { }
                System.stdout.print('|');
                """, "21|", 0);
        run("an exception through frames releases each frame's objects", """
                try { deep(3); } catch (IllegalStateException e) { System.stdout.print('|'); }
                """, "123|", 0);
        run("a constructor that throws releases the half-made object", """
                try { Bad b = new Bad(); } catch (IllegalStateException e) { }
                System.stdout.print('|');
                """, "9|", 0);
        run("an argument made before a later one throws is released", """
                try { both(new Res(4), boom()); } catch (IllegalStateException e) { }
                try { Holder h; h.r = new Res(5); hold(h, boom()); } catch (IllegalStateException e) { }
                System.stdout.print('|');
                """, "45|", 0);
        run("an array releases its elements when it goes", """
                { array<Res> rs = new array<Res>(2); rs[0] = new Res(1); rs[1] = new Res(2); rs[0] = rs[1]; }
                System.stdout.print('|');
                """, "12|", 0);
        run("the caught exception is released with its variable", """
                try { boom(); } catch (IllegalStateException e) { System.stdout.print('c'); }
                System.stdout.print('|');
                """, "c|", 0);
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
        files.put("Res.lang", RES);
        files.put("Holder.lang", "struct Holder { public Res r; }");
        files.put("Box.lang", "class Box { public Holder h; }");
        files.put("Node.lang", "class Node { public Node next; public int v; delete() { System.stdout.print(v); } }");
        files.put("Pair.lang", "class Pair { public Pair other; }");
        files.put("Animal.lang", "class Animal { public int id; delete() { System.stdout.print(id); } }");
        files.put("Dog.lang", "class Dog extends(Animal) { }");
        files.put("Registry.lang", "struct Registry { public static Res keep; }");
        files.put("Main.lang", """
                struct Main
                {
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
                """ + body + """
                        return 0;
                    }
                }
                """);
        if (library) {
            files.put("Bad.lang", "class Bad { Res held; public new() { held = new Res(9); Main.boom(); } }");
        }
        Checker checker = library ? Checker.analyze(files, LIBRARY) : Checker.analyze(files);
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
