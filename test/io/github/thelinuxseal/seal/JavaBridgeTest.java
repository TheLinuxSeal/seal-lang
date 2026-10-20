package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.codegen.CodeGen;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;

/**
 * The Java bridge, seal.java (docs/JAVA.md): Seal programs calling the JDK's classes, and classes
 * of a class folder compiled here, on the JVM. Run from the project root:
 * `java -cp out io.github.thelinuxseal.seal.JavaBridgeTest`.
 */
@SuppressWarnings("deprecation") // the VM's code generator, deprecated, refuses the bridge
public final class JavaBridgeTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();
    private static final Map<String, String> LIBRARY = new LinkedHashMap<>();
    /** A class folder of demo classes, compiled from {@link #DEMO} when the tests start. */
    private static Path demo;

    /** Java classes for the programs to reach through --classpath: fields, overloads, varargs, an abstract class. */
    private static final Map<String, String> DEMO = Map.of("demo/Tools.java", """
            package demo;

            public class Tools {
                public static int count = 0;
                public static final String NAME = "tools";
                public int x;
                public final int y = 7;
                public String label = "none";

                public Tools() { }
                public Tools(int x) { this.x = x; }
                public Tools(String label) { this.label = label; }

                public int twice() { return 2 * x; }
                public static String kind(int a) { return "int"; }
                public static String kind(long a) { return "long"; }
                public static String kind(Integer a) { return "Integer"; }
                public static String kind(Object a) { return "Object"; }
                public static String kind(String a) { return "String"; }
                public static String pick(int a, Object b) { return "int, Object"; }
                public static String pick(Object a, int b) { return "Object, int"; }
                public static String join(String sep, String... parts) { return String.join(sep, parts); }
                public static int sum(int... xs) { int s = 0; for (int x : xs) { s += x; } return s; }
                public static void fail(String why) { throw new IllegalStateException(why); }
            }
            """, "demo/Shape.java", """
            package demo;

            public abstract class Shape {
                public abstract double area();
            }
            """);

    public static void main(String[] args) throws IOException {
        Main.readSources(Path.of("stdlib"), LIBRARY);
        demo = compileDemo();
        try {
            jdkClasses();
            choosingOverloads();
            fields();
            failures();
            compileErrors();
            notOnTheVm();
        } finally {
            deleteRecursively(demo);
        }
        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ tests

    /** B1's program (docs/JAVA.md §11): what the same Java program prints, checked against Java once. */
    private static void jdkClasses() {
        output("ArrayList, HashMap, LocalDate and Random, as Java prints them", """
                // java.util.ArrayList: add, get, size, and remove(int) against remove(Object)
                JavaObject names = JavaBridge.create("java.util.ArrayList");
                names.invoke("add", [JavaString.of("Ada")]);
                names.invoke("add", [JavaString.of("Bo")]);
                names.invoke("add", [JavaString.of("Cy")]);
                names.invoke("add", [JavaInt.of(1), JavaString.of("Al")]);   // add(int, Object)
                System.stdout.println(names + " size " + (int)names.invoke("size"));
                names.invoke("remove", [JavaInt.of(0)]);                      // remove(int): the first
                names.invoke("remove", [JavaString.of("Cy")]);                // remove(Object)
                System.stdout.println(names + " first " + (string)names.invoke("get", [JavaInt.of(0)]));
                JavaClass collections = JavaBridge.get("java.util.Collections");
                collections.invokeStatic("sort", [names]);
                System.stdout.println("sorted " + names + ", has Bo: " + (bit)names.invoke("contains", [JavaString.of("Bo")]));

                // java.util.HashMap: put, get, getOrDefault
                JavaObject counts = JavaBridge.create("java.util.HashMap");
                array<string> words = ["to", "be", "or", "not", "to", "be"];
                for (string w : words) {
                    JavaObject old = counts.invoke("getOrDefault", [JavaString.of(w), JavaInt.of(0)]);
                    counts.invoke("put", [JavaString.of(w), JavaInt.of((int)old + 1)]);
                }
                System.stdout.println("to " + (int)counts.invoke("get", [JavaString.of("to")]) + ", or "
                        + (int)counts.invoke("get", [JavaString.of("or")]) + ", size " + (int)counts.invoke("size")
                        + ", missing " + counts.invoke("get", [JavaString.of("xyz")]));

                // java.time.LocalDate: a static factory, methods returning objects, and their text
                JavaObject date = JavaBridge.get("java.time.LocalDate").invokeStatic("of", [JavaInt.of(2026), JavaInt.of(10), JavaInt.of(19)]);
                JavaObject later = date.invoke("plusDays", [JavaLong.of(100)]);
                System.stdout.println("date " + date + " is a " + date.invoke("getDayOfWeek") + "; 100 days later is " + later
                        + ", day " + (int)later.invoke("getDayOfYear") + " of " + (int)later.invoke("getYear"));

                // java.util.Random with a seed: the same numbers Java gives
                JavaObject random = JavaBridge.create("java.util.Random", [JavaLong.of(42)]);
                string rolls = "";
                for (int i = 0; i < 5; i++) {
                    rolls = rolls + (int)random.invoke("nextInt", [JavaInt.of(100)]) + " ";
                }
                System.stdout.println("rolls " + rolls + "then " + (double)random.invoke("nextDouble"));

                // static fields, a class's name, Math.max(int, int) and Math.max(long, long), varargs
                JavaClass math = JavaBridge.get("java.lang.Math");
                System.stdout.println("pi " + (double)math.getStatic("PI") + ", max " + (int)math.invokeStatic("max", [JavaInt.of(3), JavaInt.of(9)])
                        + ", " + math.invokeStatic("max", [JavaLong.of(3), JavaInt.of(9)]).javaClass().name()
                        + ", " + names.javaClass().name() + ", varargs " + JavaBridge.get("java.util.List").invokeStatic("of",
                                [JavaString.of("x"), JavaString.of("y"), JavaString.of("z")]));
                """, """
                [Ada, Al, Bo, Cy] size 4
                [Al, Bo] first Al
                sorted [Al, Bo], has Bo: true
                to 2, or 1, size 4, missing null
                date 2026-10-19 is a MONDAY; 100 days later is 2027-01-27, day 27 of 2027
                rolls 30 63 48 84 70 then 0.9420735430282128
                pi 3.141592653589793, max 9, java.lang.Long, java.util.ArrayList, varargs [x, y, z]
                """);
        output("methods of private classes are called through the public types that have them", """
                JavaObject names = JavaBridge.get("java.util.List").invokeStatic("of", [JavaString.of("a"), JavaString.of("b")]);
                JavaObject it = names.invoke("iterator");                    // an ImmutableCollections inner class
                while ((bit)it.invoke("hasNext")) {
                    System.stdout.print((string)it.invoke("next") + " ");
                }
                System.stdout.println((int)names.invoke("size"));
                """, "a b 2\n");
        output("every kind of Seal value goes in, and comes out by a cast", """
                JavaClass character = JavaBridge.get("java.lang.Character");
                JavaClass strings = JavaBridge.get("java.lang.String");
                System.stdout.println((char)character.invokeStatic("toUpperCase", [JavaChar.of('q')])
                        + " " + strings.invokeStatic("valueOf", [JavaChar.of('x')])
                        + " " + strings.invokeStatic("valueOf", [JavaInt.of(65)])
                        + " " + (int)JavaBridge.get("java.lang.Byte").invokeStatic("toUnsignedInt", [JavaByte.of(-1)])
                        + " " + JavaBridge.get("java.lang.Short").invokeStatic("toString", [JavaShort.of(-300)])
                        + " " + JavaBridge.get("java.lang.Boolean").invokeStatic("toString", [JavaBoolean.of(true)])
                        + " " + (float)JavaBridge.get("java.lang.Math").invokeStatic("abs", [JavaFloat.of(-2.5)])
                        + " " + JavaBridge.get("java.lang.Math").invokeStatic("round", [JavaFloat.of(2.5)]).javaClass().name()
                        + " " + (int<<64>>)JavaBridge.get("java.lang.Long").getStatic("MAX_VALUE")
                        + " " + (int)JavaChar.of('A') + " " + (double)JavaInt.of(3));
                """, "Q x 65 255 -300 true 2.5 java.lang.Integer 9223372036854775807 65 3.0\n");
        output("Seal objects go into Java as themselves, and JavaObjects compare by identity", """
                Point p = new Point();
                JavaObject list = JavaBridge.create("java.util.ArrayList");
                list.invoke("add", [JavaObject.of(p)]);
                JavaObject back = list.invoke("get", [JavaInt.of(0)]);
                JavaObject nothing = list.invoke("clear");                  // void: null
                List<JavaObject> seen = new List();
                seen.add(back);
                HashMap<JavaObject, int> marks = new HashMap();
                marks.put(back, 5);
                JavaClass c = (JavaClass)JavaBridge.get("java.util.Random").javaClass().javaClass();
                System.stdout.println((back == JavaObject.of(p)) + " " + (back == list) + " " + (nothing == null) + " " + nothing
                        + " " + seen.indexOf(JavaObject.of(p)) + " " + marks.get(back) + " " + c.name()
                        + " " + JavaBridge.get("java.util.List").isInstance(list) + " " + JavaBridge.get("java.util.Map").isInstance(list));
                """, "true false true null 0 5 java.lang.Class true false\n", "Point", "class Point { }");
    }

    private static void choosingOverloads() {
        output("overloads are chosen as Java chooses them", """
                JavaClass tools = JavaBridge.get("demo.Tools");
                System.stdout.println(tools.invokeStatic("kind", [JavaInt.of(1)]) + " " + tools.invokeStatic("kind", [JavaLong.of(1)])
                        + " " + tools.invokeStatic("kind", [JavaShort.of(1)]) + " " + tools.invokeStatic("kind", [JavaString.of("s")])
                        + " " + tools.invokeStatic("kind", [JavaDouble.of(1.5)])
                        + " " + tools.invokeStatic("kind", [JavaBridge.get("java.lang.Integer").invokeStatic("valueOf", [JavaInt.of(5)])])
                        + " | " + tools.invokeStatic("join", [JavaString.of(", "), JavaString.of("a"), JavaString.of("b")])
                        + " | " + tools.invokeStatic("join", [JavaString.of(", ")])
                        + " | " + tools.invokeStatic("sum") + " " + tools.invokeStatic("sum", [JavaInt.of(1), JavaShort.of(2), JavaByte.of(3)])
                        + " | " + tools.create([JavaString.of("hi")]).getField("label") + " " + tools.create([JavaInt.of(4)]).getField("x")
                        + " " + JavaBridge.create("demo.Tools").getField("label"));
                """, "int long int String Object Integer | a, b |  | 0 6 | hi 4 none\n");
        trap("two overloads that fit equally well are ambiguous", """
                JavaBridge.get("demo.Tools").invokeStatic("pick", [JavaInt.of(1), JavaInt.of(2)]);
                """, "trap: the call to demo.Tools.pick is ambiguous: ");
        trap("null fits two unrelated parameters", """
                JavaObject nothing = null;
                JavaBridge.get("demo.Tools").invokeStatic("kind", [nothing]);
                """, "trap: the call to demo.Tools.kind is ambiguous: ");
    }

    private static void fields() {
        output("fields of objects and classes", """
                JavaClass tools = JavaBridge.get("demo.Tools");
                JavaObject t = tools.create([JavaInt.of(5)]);
                System.stdout.print((int)t.getField("x") + " " + (int)t.invoke("twice") + " " + (int)t.getField("y") + " ");
                t.setField("x", JavaInt.of(9));
                t.setField("label", JavaString.of("set"));
                tools.setStatic("count", JavaInt.of(3));
                System.stdout.println((int)t.invoke("twice") + " " + t.getField("label") + " " + (int)tools.getStatic("count")
                        + " " + tools.getStatic("NAME"));
                """, "5 10 7 18 set 3 tools\n");
        trap("a final field can't be set", """
                JavaBridge.get("demo.Tools").create().setField("y", JavaInt.of(1));
                """, "trap: can't set demo.Tools.y: it is final");
        trap("a field takes its own type", """
                JavaBridge.get("demo.Tools").create().setField("label", JavaInt.of(1));
                """, "trap: can't set demo.Tools.label, a java.lang.String, to a java.lang.Integer");
        trap("a static field is the class's", """
                JavaBridge.get("demo.Tools").create().getField("count");
                """, "trap: demo.Tools.count is static: use getStatic on the class");
        trap("only public fields", """
                JavaBridge.create("java.util.ArrayList").getField("size");
                """, "trap: java.util.ArrayList has no public field size");
    }

    private static void failures() {
        trap("a class that isn't there", """
                JavaBridge.get("demo.Nope");
                """, "trap: no Java class demo.Nope");
        trap("a class is named in full", """
                JavaBridge.create("ArrayList");
                """, "trap: no Java class ArrayList; name it in full, as java.util.ArrayList");
        trap("a method that isn't there", """
                JavaBridge.create("java.util.ArrayList").invoke("nope");
                """, "trap: java.util.ArrayList has no public method nope");
        trap("no overload takes the arguments", """
                JavaBridge.create("java.util.ArrayList").invoke("add", [JavaInt.of(1), JavaInt.of(2), JavaInt.of(3)]);
                """, "trap: no java.util.ArrayList.add takes (int, int, int); there are ");
        trap("a static method is called on the class", """
                JavaBridge.get("java.util.ArrayList").invokeStatic("size");
                """, "trap: java.util.ArrayList has no public static method size; it has an instance one: call it with invoke on an object");
        trap("an abstract class can't be created", """
                JavaBridge.create("demo.Shape");
                """, "trap: can't create a demo.Shape: it is abstract");
        trap("a cast takes the kind of value it names", """
                int n = (int)JavaString.of("5");
                """, "trap: cannot cast a java.lang.String to int: it takes a Number");
        trap("a cast of null", """
                JavaObject nothing = null;
                bit b = (bit)nothing;
                """, "trap: cannot cast null to bit: it takes a Boolean");
        trap("a method of null", """
                JavaObject nothing = null;
                nothing.invoke("size");
                """, "trap: invoke(\"size\") on a null JavaObject");
        trap("what Java throws stops the program, for now", """
                JavaBridge.get("demo.Tools").invokeStatic("fail", [JavaString.of("boom")]);
                """, "trap: java.lang.IllegalStateException: boom");
    }

    private static void compileErrors() {
        compileError("a JavaObject isn't one of Seal's objects", """
                Object o = JavaBridge.create("java.util.ArrayList");
                """, "initializer of o: expected handle<Object>, found handle<JavaObject>; a JavaObject is Java's object, not"
                + " Seal's: JavaObject.of(o) passes a Seal object into Java");
        compileError("nor the other way", """
                JavaObject o = new Point();
                """, "initializer of o: expected handle<JavaObject>, found handle<Point>; a JavaObject is Java's object, not Seal's",
                "Point", "class Point { }");
        compileError("JavaObjects come from the bridge, not new", """
                JavaObject o = new JavaObject();
                """, "private");
        compileError("a struct has no Java form", """
                Spot s;
                JavaObject o = JavaObject.of(s);
                """, "argument 1 of of: expected handle<Object>, found Spot", "Spot", "struct Spot { public int x; }");
        Run r = run(program("JavaBridge.create(\"java.util.ArrayList\");", null));
        check(r.errors() != null && r.errors().contains("JavaBridge"), "seal.java needs an import: " + r);
    }

    private static void notOnTheVm() {
        Map<String, String> files = program("JavaBridge.create(\"java.util.ArrayList\");", "import seal.java.*;");
        List<Diagnostic> errors = CodeGen.generate(Checker.analyze(files, LIBRARY), "Main").errors();
        check(errors.stream().anyMatch(d -> d.message().contains("JavaBridge.create runs only on the JVM: the VM is deprecated,"
                + " and gets no new features; run without --vm")), "the VM refuses the bridge: " + errors);
    }

    // ================================================================ helpers

    /** A Main whose main() runs body, importing seal.java and seal.util; then pairs of file name and source. */
    private static Map<String, String> program(String body, String imports, String... more) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Main.seal", (imports == null ? "" : imports) + "\n"
                + "import seal.util.List;\nimport seal.util.HashMap;\n\nclass Main\n{\n    static void main()\n    {\n" + body + "    }\n}\n");
        for (int i = 0; i + 1 < more.length; i += 2) {
            files.put(more[i] + ".seal", more[i + 1]);
        }
        return files;
    }

    private record Run(String output, String trap, String errors) {
    }

    private static Run run(Map<String, String> files) {
        Checker checker = Checker.analyze(files, LIBRARY);
        if (!checker.diagnostics().isEmpty()) {
            return new Run("", null, checker.diagnostics().toString());
        }
        JvmGen.Result gen = JvmGen.generate(checker, "Main");
        if (!gen.errors().isEmpty()) {
            return new Run("", null, gen.errors().toString());
        }
        List<String> problems = JvmRunner.verify(gen);
        if (!problems.isEmpty()) {
            return new Run("", null, "verification fails: " + problems);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        JvmRunner.Outcome o = JvmRunner.run(gen, java.io.InputStream.nullInputStream(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), err, true, List.of(demo));
        return new Run(bytes.toString(StandardCharsets.UTF_8), o.fault() == null ? null : JvmRunner.describe(o.fault()), null);
    }

    private static void output(String name, String body, String expected, String... more) {
        Run r = run(program(body, "import seal.java.*;", more));
        check(r.errors() == null && r.trap() == null && r.output().equals(expected),
                name + "\n    expected " + show(expected) + "\n    got      " + show(r.output())
                        + (r.trap() == null ? "" : "\n    trap     " + r.trap()) + (r.errors() == null ? "" : "\n    " + r.errors()));
    }

    /** Expects the program to stop with a report that starts so. */
    private static void trap(String name, String body, String expected) {
        Run r = run(program(body, "import seal.java.*;"));
        check(r.trap() != null && r.trap().startsWith(expected),
                name + "\n    expected a trap starting " + show(expected) + "\n    got " + r);
    }

    private static void compileError(String name, String body, String expected, String... more) {
        Run r = run(program(body, "import seal.java.*;", more));
        check(r.errors() != null && r.errors().contains(expected),
                name + "\n    expected a compile error containing " + show(expected) + "\n    got " + r);
    }

    /** Compiles {@link #DEMO} into a class folder of its own. */
    private static Path compileDemo() throws IOException {
        Path dir = Files.createTempDirectory("seal-demo");
        List<String> args = new ArrayList<>(List.of("-d", dir.toString()));
        for (Map.Entry<String, String> e : DEMO.entrySet()) {
            Path f = dir.resolve("src").resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            args.add(f.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("the demo classes don't compile");
        }
        return dir;
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var children = Files.list(p)) {
                for (Path c : children.toList()) {
                    deleteRecursively(c);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    private static String show(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
