package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.JavaClasses;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;

/**
 * Java's classes used from Seal (docs/JAVA.md): imported, checked while compiling, and called as
 * the JVM calls them, with values crossing by seal.java's `of` methods and casts. The JDK's
 * classes, and those of a class folder compiled here, which also holds the Java program the first
 * test compares with. Run from the project root: `java -cp out io.github.thelinuxseal.seal.JavaInteropTest`.
 */
public final class JavaInteropTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();
    private static final Map<String, String> LIBRARY = new LinkedHashMap<>();
    /** A class folder compiled from {@link #DEMO} when the tests start. */
    private static Path demo;
    private static JavaClasses classPath;

    /** Java classes on the programs' class path: fields, overloads, varargs, exceptions; and Same, a Java program. */
    private static final Map<String, String> DEMO = Map.of("demo/Shape.java", """
            package demo;

            /** For Seal classes to extend (T4): protected members, an abstract method, Java code calling the overrides. */
            public abstract class Shape {
                protected String name;
                private final int sides;

                protected Shape(String name, int sides) { this.name = name; this.sides = sides; }

                public abstract double area();

                protected String unit() { return "cm"; }

                public final int sides() { return sides; }

                public void scale(byte times) { }

                public String describe() { return name + " with " + sides + " sides: " + area() + " " + unit() + "2"; }

                public static String describeAll(java.util.List<? extends Shape> shapes) {
                    StringBuilder out = new StringBuilder();
                    for (Shape s : shapes) { out.append(s.describe()).append("; "); }
                    return out.toString();
                }
            }
            """, "demo/Box.java", """
            package demo;

            /** A generic class to extend, whose show() calls the get() a subclass may override. */
            public class Box<T> {
                protected T held;
                public Box() { }
                public void put(T t) { held = t; }
                public T get() { return held; }
                public String show() { return "[" + get() + "]"; }
            }
            """, "demo/Sealed.java", """
            package demo;

            public final class Sealed { }
            """, "demo/Tools.java", """
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
                public static class Inner { public static String hello() { return "inner"; } }
            }
            """, "demo/Same.java", """
            package demo;

            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.time.LocalDate;
            import java.time.format.DateTimeFormatter;
            import java.time.temporal.ChronoUnit;
            import java.util.List;
            import java.util.Locale;
            import java.util.Random;
            import java.util.regex.Matcher;
            import java.util.regex.Pattern;

            /** The Java program JavaInteropTest's Seal program is written after: what it prints, as one string. */
            public class Same {
                public static String run() throws Exception {
                    StringBuilder out = new StringBuilder();
                    LocalDate start = LocalDate.of(2026, 1, 1);
                    LocalDate end = start.plusDays(291);
                    out.append(start + " to " + end + ": " + ChronoUnit.DAYS.between(start, end) + " days, a "
                            + end.getDayOfWeek() + "\\n");
                    out.append(end.format(DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", Locale.ROOT)) + "\\n");
                    Random r = new Random(42);
                    String rolls = "";
                    for (int i = 0; i < 5; i++) {
                        rolls += r.nextInt(100) + " ";
                    }
                    out.append(rolls + r.nextDouble() + "\\n");
                    Matcher m = Pattern.compile("(\\\\w+)@(\\\\w+)\\\\.com").matcher("ada@example.com, bo@test.com");
                    while (m.find()) {
                        out.append(m.group(1) + " at " + m.group(2) + "\\n");
                    }
                    Path dir = Files.createTempDirectory("seal");
                    Path file = dir.resolve("notes.txt");
                    Files.writeString(file, "one\\ntwo\\nthree\\n");
                    List<String> lines = Files.readAllLines(file);
                    out.append(lines + " " + Files.size(file) + "\\n");
                    Files.delete(file);
                    Files.delete(dir);
                    out.append(Files.exists(file) + "\\n");
                    return out.toString();
                }
            }
            """);

    public static void main(String[] args) throws Exception {
        Main.readSources(Path.of("stdlib"), LIBRARY);
        demo = compileDemo();
        classPath = new JavaClasses(List.of(demo));
        try {
            sameAsJava();
            valuesInAndOut();
            operators();
            choosingOverloads();
            fields();
            interfacesAndObjects();
            sealAndJavaTogether();
            generics();
            sealIntoJava();
            extendingJava();
            exceptions();
            javaArrays();
            imports();
            compileErrors();
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

    /** T1 is done when this prints what the same Java program prints (docs/JAVA.md §8). */
    private static void sameAsJava() throws Exception {
        String java;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{demo.toUri().toURL()})) {
            java = (String) loader.loadClass("demo.Same").getMethod("run").invoke(null);
        }
        output("java.time, java.util.Random, java.util.regex and java.nio.file, as the same Java program", """
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.time.LocalDate;
                import java.time.format.DateTimeFormatter;
                import java.time.temporal.ChronoUnit;
                import java.util.List;
                import java.util.Locale;
                import java.util.Random;
                import java.util.regex.Matcher;
                import java.util.regex.Pattern;
                """, """
                LocalDate start = LocalDate.of(JavaInt.of(2026), JavaInt.of(1), JavaInt.of(1));
                LocalDate end = start.plusDays(JavaLong.of(291));
                System.stdout.println(start + " to " + end + ": " + (int<<64>>)ChronoUnit.DAYS.between(start, end) + " days, a "
                        + end.getDayOfWeek());
                System.stdout.println(end.format(DateTimeFormatter.ofPattern(JavaString.of("EEEE d MMMM uuuu"), Locale.ROOT)));
                Random r = new Random(JavaLong.of(42));
                string rolls = "";
                for (int i = 0; i < 5; i++) {
                    rolls += (int)r.nextInt(JavaInt.of(100)) + " ";
                }
                System.stdout.println(rolls + (double)r.nextDouble());
                Matcher m = Pattern.compile(JavaString.of("(\\\\w+)@(\\\\w+)\\\\.com")).matcher(JavaString.of("ada@example.com, bo@test.com"));
                while ((bit)m.find()) {
                    System.stdout.println(m.group(JavaInt.of(1)) + " at " + m.group(JavaInt.of(2)));
                }
                Path dir = Files.createTempDirectory(JavaString.of("seal"));
                Path file = dir.resolve(JavaString.of("notes.txt"));
                Files.writeString(file, JavaString.of("one\\ntwo\\nthree\\n"));   // OpenOption...: none
                List lines = Files.readAllLines(file);
                System.stdout.println(lines + " " + (int<<64>>)Files.size(file));
                Files.delete(file);
                Files.delete(dir);
                System.stdout.println((bit)Files.exists(file));
                """, java);
    }

    private static void valuesInAndOut() {
        output("each of Java's primitives goes in by its class, and out by a cast", "", """
                System.stdout.println((char)JavaCharBoxed.toUpperCase(JavaChar.of('q'))
                        + " " + JavaString.valueOf(JavaChar.of('x')) + " " + JavaString.valueOf(JavaInt.of(65))
                        + " " + (int)JavaByteBoxed.toUnsignedInt(JavaByte.of(-1))
                        + " " + JavaShortBoxed.toString(JavaShort.of(-300))
                        + " " + JavaBooleanBoxed.toString(JavaBoolean.of(true))
                        + " " + (float)java.lang.Math.abs(JavaFloat.of(-2.5))
                        + " " + (int)java.lang.Math.round(JavaFloat.of(2.5)) + " " + (int<<64>>)java.lang.Math.round(JavaDouble.of(2.5))
                        + " " + (int<<64>>)JavaLongBoxed.MAX_VALUE
                        + " " + (int)JavaChar.of('A') + " " + (double)JavaInt.of(3) + " " + (int<<8>>)JavaInt.of(300));
                """, "Q x 65 255 -300 true 2.5 3 3 9223372036854775807 65 3.0 44\n");
        output("a Java value's cast to string is Java's text of it", "import java.time.LocalDate;", """
                LocalDate none = null;
                System.stdout.println((string)JavaInt.of(-5) + " " + (string)JavaByte.of(-1) + " " + (string)JavaBoolean.of(false)
                        + " " + (string)JavaChar.of('c') + " " + (string)JavaDouble.of(0.1) + " " + (string)none
                        + " " + (string)JavaString.of("same") + " " + JavaInt.of(7));
                """, "-5 -1 false c 0.1 null same 7\n");
        output("boxes: Java's objects holding numbers, unboxed by a cast, and from an Object", "import java.util.ArrayList;", """
                JavaIntBoxed b = JavaIntBoxed.of(41);
                ArrayList l = new ArrayList();
                l.add(b);
                l.add(JavaDoubleBoxed.of(2.5));
                l.add(JavaBooleanBoxed.of(true));
                System.stdout.println((int)b + 1 + " " + (double)b.doubleValue() + " " + (int)l.get(JavaInt.of(0))
                        + " " + (double)l.get(JavaInt.of(1)) + " " + (bit)l.get(JavaInt.of(2)) + " " + (char)JavaCharBoxed.of('z')
                        + " " + (int)JavaIntBoxed.valueOf(JavaInt.of(5)) + " " + JavaLongBoxed.valueOf(JavaInt.of(5)));
                """, "42 41.0 41 2.5 true z 5 5\n");
        trap("unboxing null stops the program, as a null handle does", "", """
                JavaIntBoxed none = null;
                int n = (int)none;
                """, "trap: null handle");
        trap("an Object of the wrong kind is checked", "import java.util.ArrayList;", """
                ArrayList l = new ArrayList();
                l.add(JavaString.of("x"));
                int n = (int)l.get(JavaInt.of(0));
                """, "trap: cannot cast");
    }

    /** Java's own operators on Java's primitives, as Java's; but Java's values and Seal's don't mix. */
    private static void operators() {
        output("arithmetic, comparisons and logic on Java's primitives, as Java's", "", """
                JavaInt a = JavaInt.of(7);
                JavaInt b = JavaInt.of(2);
                JavaByte x = JavaByte.of(100);
                JavaLong big = JavaLong.of(5000000000);
                System.stdout.println((a + b) + " " + (a - b) + " " + (a * b) + " " + (a / b) + " " + (a % b) + " " + (-a) + " " + (~b));
                System.stdout.println((x + x) + " " + (a + big) + " " + (a / JavaDouble.of(2.0)) + " " + (a & b) + " " + (a | b) + " " + (a ^ b)
                        + " " + (JavaInt.of(2147483647) + JavaInt.of(1)) + " " + (JavaChar.of('a') + JavaInt.of(1)));
                JavaBoolean t = JavaBoolean.of(true);
                JavaBoolean f = JavaBoolean.of(false);
                System.stdout.println((a < b) + " " + (a >= b) + " " + (a == JavaInt.of(7)) + " " + (x != JavaByte.of(100)) + " "
                        + (t && f) + " " + (t || f) + " " + (!t) + " " + (t ^ f) + " " + (t == f));
                if ((bit)(a > b && t)) {
                    System.stdout.println("a > b");
                }
                """, "9 5 14 3 1 -7 -3\n200 5000000007 3.5 2 7 5 -2147483648 98\nfalse true true false false true false true false\na > b\n");
        output("compound assignments and ++ narrow back, as Java's", "", """
                JavaInt a = JavaInt.of(7);
                JavaByte x = JavaByte.of(100);
                JavaChar c = JavaChar.of('a');
                JavaDouble d = JavaDouble.of(1.5);
                a += JavaInt.of(2);
                x += JavaByte.of(100);                    // (byte)200, as Java's b += c is b = (byte)(b + c)
                a++;
                x++;
                c++;
                d *= JavaDouble.of(4.0);
                System.stdout.println(a + " " + x + " " + c + " " + d);
                """, "10 -55 b 6.0\n");
        compileError("a JavaInt and an int don't mix", "", "JavaInt a = JavaInt.of(1);\nint b = 2;\nJavaInt c = a + b;",
                "operator + on JavaInt and int: Java's values and Seal's don't mix; make both Java's, as JavaInt.of(...), or"
                        + " both Seal's, as (int)x");
        compileError("nor a JavaInt and a literal", "", "JavaInt a = JavaInt.of(1);\na += 1;",
                "operator += on JavaInt and integer constant 1: Java's values and Seal's don't mix");
        compileError("a comparison of Java's values is a JavaBoolean", "", "if (JavaInt.of(1) < JavaInt.of(2)) { }",
                "condition must be a bit, not JavaBoolean; Java's boolean is a bit by a cast: (bit)x");
        compileError("a JavaBoolean isn't a number", "", "JavaInt c = JavaInt.of(1) + JavaBoolean.of(true);",
                "operator + takes two of Java's numbers, or two JavaBooleans, not JavaInt and JavaBoolean");
        compileError("Seal's <<< isn't Java's", "", "JavaInt c = JavaInt.of(1) <<< JavaInt.of(2);",
                "operator <<< isn't Java's, so it doesn't take JavaInt and JavaInt");
    }

    private static void choosingOverloads() {
        output("overloads are chosen as Java chooses them, but never by boxing", "import demo.Tools;", """
                System.stdout.println(Tools.kind(JavaInt.of(1)) + " " + Tools.kind(JavaLong.of(1)) + " " + Tools.kind(JavaShort.of(1))
                        + " " + Tools.kind(JavaChar.of('c')) + " " + Tools.kind(JavaString.of("s")) + " " + Tools.kind(JavaIntBoxed.of(5))
                        + " " + Tools.kind(JavaDoubleBoxed.of(1.5)) + " " + Tools.kind((JavaObject)JavaString.of("s"))
                        + " | " + Tools.join(JavaString.of(", "), JavaString.of("a"), JavaString.of("b"))
                        + " | " + Tools.join(JavaString.of(", ")) + " | " + Tools.join(JavaString.of("+"), JavaArray.of(["x", "y"]))
                        + " | " + (int)Tools.sum() + " " + (int)Tools.sum(JavaInt.of(1), JavaInt.of(2), JavaShort.of(3)));
                """, "int long int int String Integer Object Object | a, b |  | x+y | 0 6\n");
        output("an int widens to a long, as in Java", "", """
                System.stdout.println((int<<64>>)java.lang.Math.max(JavaInt.of(3), JavaLong.of(9)) + " "
                        + (double)java.lang.Math.max(JavaInt.of(3), JavaDouble.of(0.5)));
                """, "9 3.0\n");
        output("remove(int) and remove(Object), as Java picks them", "import java.util.ArrayList;", """
                ArrayList numbers = new ArrayList();
                for (int n = 10; n <= 40; n += 10) {
                    numbers.add(JavaIntBoxed.of(n));
                }
                numbers.remove(JavaInt.of(0));                 // remove(int index)
                numbers.remove(JavaIntBoxed.of(30));           // remove(Object)
                System.stdout.println(numbers);
                """, "[20, 40]\n");
        compileError("a double has no overload without boxing", "import demo.Tools;", "Tools.kind(JavaDouble.of(1.5));",
                "no overload of kind takes (JavaDouble)");
        compileError("ambiguous overloads say so", "import java.lang.StringBuilder as JavaBuilder;",
                "new JavaBuilder().append(null);", "ambiguous call to append");
        compileError("an int fits neither of pick(int, Object) and pick(Object, int) without boxing", "import demo.Tools;",
                "Tools.pick(JavaInt.of(1), JavaInt.of(2));", "no overload of pick takes (JavaInt, JavaInt)");
        compileError("Seal's values don't go into Java by themselves", "import java.time.LocalDate;",
                "LocalDate.of(2026, 1, 1);", "Seal's integer constant 2026 goes into Java by JavaInt.of(...)");
        compileError("a Seal string isn't Java's", "import demo.Tools;", "Tools.kind(\"s\");",
                "Seal's string literal goes into Java by JavaString.of(...)");
    }

    private static void fields() {
        output("fields, static and not, and constants", "import demo.Tools;", """
                Tools.count = JavaInt.of(3);
                Tools t = new Tools(JavaInt.of(4));
                t.x = JavaInt.of((int)t.x + 1);
                Tools named = new Tools(JavaString.of("named"));
                System.stdout.println((int)Tools.count + " " + Tools.NAME + " " + (int)t.x + " " + (int)t.y + " " + (int)t.twice()
                        + " " + named.label + " " + new Tools().label + " " + Tools.Inner.hello() + " " + (int)JavaIntBoxed.MAX_VALUE);
                """, "3 tools 5 7 10 named none inner 2147483647\n");
        compileError("a final field isn't assigned", "import demo.Tools;", "new Tools().y = JavaInt.of(1);",
                "Java's field y is final, so it can't be assigned");
        compileError("nor a static final one", "import demo.Tools;", "Tools.NAME = JavaString.of(\"x\");",
                "Java's field NAME is final");
        compileError("a field is read, not called", "import demo.Tools;", "new Tools().label();", "label is a field");
        compileError("an instance method needs an object", "import demo.Tools;", "Tools.twice();",
                "has no static method twice; it is an instance method");
    }

    private static void interfacesAndObjects() {
        output("interfaces, iterators and Object's methods", "import java.util.List;\nimport java.util.Iterator;", """
                List l = List.of(JavaString.of("a"), JavaString.of("b"));
                Iterator it = l.iterator();
                string seen = "";
                while ((bit)it.hasNext()) {
                    seen += (string)it.next();
                }
                System.stdout.println(seen + " " + (int)l.hashCode() + " " + l.toString() + " " + (bit)l.equals(List.of(JavaString.of("a"),
                        JavaString.of("b"))) + " " + (bit)l.contains(JavaString.of("b")) + " " + JavaString.of("x").getClass().getName());
                """, "ab 4066 [a, b] true true java.lang.String\n");
        output("nested classes, by their class", "import java.util.Map;\nimport java.util.TreeMap;", """
                TreeMap ages = new TreeMap();
                ages.put(JavaString.of("Grace"), JavaIntBoxed.of(85));
                ages.put(JavaString.of("Ada"), JavaIntBoxed.of(36));
                Map.Entry first = ages.firstEntry();
                System.stdout.println(ages + " " + first.getKey() + "=" + (int)first.getValue() + " " + ages.get(JavaString.of("Bo")));
                """, "{Ada=36, Grace=85} Ada=36 null\n");
        output("== is identity, and null is any Java object's", "import java.util.ArrayList;", """
                ArrayList a = new ArrayList();
                ArrayList b = a;
                JavaString none = null;
                System.stdout.println((a == b) + " " + (a == new ArrayList()) + " " + (none == null) + " " + (bit)a.equals(new ArrayList()));
                """, "true false true true\n");
    }

    private static void sealAndJavaTogether() {
        output("a Seal object goes into Java as JavaObject.of(o), and comes back by a cast", "import java.util.ArrayList;", """
                Point p = new Point();
                ArrayList list = new ArrayList();
                list.add(JavaObject.of(p));
                JavaObject back = list.get(JavaInt.of(0));
                Point again = (Point)back;
                bit isPoint = back instanceof Point;
                System.stdout.println((again == p) + " " + isPoint + " " + (back instanceof Point q && q == p));
                """, "true true true\n", "Point", "class Point { }");
        output("Java's values in Seal's fields, structs, arrays, collections and lambdas",
                "import java.time.LocalDate;\nimport seal.util.List;\nimport seal.util.HashMap;", """
                Holder h = new Holder();
                h.n = JavaInt.of(7);
                h.when = LocalDate.of(JavaInt.of(2000), JavaInt.of(2), JavaInt.of(29));
                Pair pr;
                pr.a = JavaInt.of(3);
                pr.b = JavaString.of("three");
                array<JavaString> names = new array<JavaString>(2);
                names[0] = JavaString.of("x");
                List<JavaString> sl = new List();
                sl.add(JavaString.of("listed"));
                HashMap<JavaObject, int> marks = new HashMap();
                JavaObject key = JavaObject.of(h);
                marks.put(key, 5);
                JavaString cap = JavaString.of("captured");
                Runnable r = () -> System.stdout.print(cap + " ");
                r.run();
                System.stdout.println((int)h.n + " " + h.when + " " + h.name + " " + (int)pr.a + " " + pr.b + " " + names[0] + " "
                        + names[1] + " " + sl.get(0) + " " + marks.get(key) + " " + marks.containsKey(JavaObject.of(new Holder())));
                """, "captured 7 2000-02-29 null 3 three x null listed 5 false\n",
                "Holder", "import java.time.LocalDate;\nimport seal.java.*;\nclass Holder { public JavaInt n; public LocalDate when; public JavaString name; }",
                "Pair", "import seal.java.*;\nstruct Pair { public JavaInt a; public JavaString b; }");
    }

    /** Java's generics (docs/JAVA.md §4.1), T2: type arguments, inference, wildcards, for-each, resources. */
    private static void generics() {
        output("T2's word count: a TreeMap<JavaString, JavaIntBoxed>, and for-each over a Java array and a Set",
                "import java.util.Map;\nimport java.util.TreeMap;", """
                string text = "the cat and the dog and the bird";
                TreeMap<JavaString, JavaIntBoxed> counts = new TreeMap();
                for (JavaString w : JavaString.of(text).split(JavaString.of(" "))) {
                    counts.put(w, JavaIntBoxed.of((int)counts.getOrDefault(w, JavaIntBoxed.of(0)) + 1));
                }
                for (Map.Entry<JavaString, JavaIntBoxed> e : counts.entrySet()) {
                    System.stdout.println(e.getKey() + ": " + (int)e.getValue());
                }
                """, "and: 2\nbird: 1\ncat: 1\ndog: 1\nthe: 3\n");
        output("type arguments from the arguments, the target and the parents; wildcards; Seal classes as type arguments",
                "import java.util.ArrayList;\nimport java.util.Arrays;\nimport java.util.Collections;\nimport java.util.List;\n"
                + "import java.util.HashMap;\nimport java.util.Optional;", """
                List<JavaString> words = Arrays.asList(JavaString.of("pear"), JavaString.of("fig"), JavaString.of("apple"));
                ArrayList<JavaString> copy = new ArrayList(words);
                copy.addAll(List.of(JavaString.of("kiwi")));           // addAll(Collection<? extends E>)
                Collections.sort(copy);
                JavaString first = copy.get(JavaInt.of(0));
                List<JavaString> none = List.of();
                List<JavaString> made = new ArrayList();               // an ArrayList<JavaString>, from its target
                System.stdout.println(JavaString.join(JavaString.of("+"), words) + " " + Collections.max(words) + " " + copy + " "
                        + first.toUpperCase() + " " + (int)none.size() + " " + Collections.<JavaString>emptyList() + made);
                ArrayList<Point> points = new ArrayList();
                points.add(new Point(3));
                Point p = points.get(JavaInt.of(0));                   // a Seal object, as its own type
                HashMap<JavaString, ArrayList<JavaString>> groups = new HashMap();
                for (JavaString w : copy) {
                    JavaString key = JavaString.valueOf(w.charAt(JavaInt.of(0)));
                    if (groups.get(key) == null) {
                        groups.put(key, new ArrayList());
                    }
                    groups.get(key).add(w);
                }
                Optional<JavaString> some = Optional.of(JavaString.of("here"));
                Optional<JavaString> nothing = Optional.empty();
                System.stdout.println(p.x + " " + groups.get(JavaString.of("p")) + " " + some.get() + " " + (bit)nothing.isPresent()
                        + " " + nothing.orElse(JavaString.of("default")));
                """, "pear+fig+apple pear [apple, fig, kiwi, pear] APPLE 0 [][]\n3 [pear] here false default\n",
                "Point", "class Point { public int x; public new(int x) { this.x = x; } }");
        output("raw types, as in T1, and raw to parameterized, unchecked as in Java", "import java.util.ArrayList;\nimport java.util.List;", """
                ArrayList untyped = new ArrayList();
                untyped.add(JavaString.of("u"));
                List<JavaString> typed = untyped;
                JavaObject o = untyped.get(JavaInt.of(0));
                System.stdout.println(typed.get(JavaInt.of(0)).length() + " " + o);
                """, "1 u\n");
        output("a Java array's elements, read and assigned in place, and its length", "import java.util.Arrays;\nimport java.util.ArrayList;", """
                JavaArray<JavaInt> nums = JavaArray.of([5, 6, 7]);
                nums[1]++;
                nums[2] += JavaInt.of(10);
                nums[0] = nums[0] * JavaInt.of(2);
                int total = 0;
                for (JavaInt n : nums) {
                    total += (int)n;
                }
                ArrayList<JavaString> l = new ArrayList();
                l.add(JavaString.of("x"));
                JavaArray<JavaString> arr = l.toArray(new JavaArray<JavaString>(0));
                System.stdout.println(total + " " + Arrays.toString(nums) + " " + nums.length + " " + arr[0] + arr.length);
                """, "34 [10, 7, 17] 3 x1\n");
        output("try-with-resources closes a Java AutoCloseable", "import java.io.BufferedReader;\nimport java.io.StringReader;", """
                try (BufferedReader r = new BufferedReader(new StringReader(JavaString.of("one\\ntwo")))) {
                    System.stdout.println(r.readLine() + "|" + r.readLine() + "|" + r.readLine());
                }
                """, "one|two|null\n");
        output("a catch of Java's Throwable doesn't catch System.exit", "import java.lang.Throwable as JavaThrowable;", """
                try {
                    System.stdout.println("before");
                    System.exit(3);
                } catch (JavaThrowable t) {
                    System.stdout.println("caught");
                }
                """, "before\n");
        compileError("Java's generics hold objects: JavaIntBoxed", "import java.util.ArrayList;", "ArrayList<JavaInt> l = null;",
                "Java's generics hold objects, so a type argument is JavaIntBoxed (an Integer), not JavaInt");
        compileError("not Seal's numbers", "import java.util.ArrayList;", "ArrayList<int> l = null;",
                "not int; Java's box of it is JavaIntBoxed");
        compileError("a Seal string is JavaString there", "import java.util.ArrayList;", "ArrayList<string> l = null;",
                "Seal's string is JavaString here");
        compileError("an element of another type", "import java.util.ArrayList;",
                "ArrayList<JavaString> l = new ArrayList();\nl.add(JavaIntBoxed.of(1));",
                "no overload of add takes (handle<JavaIntBoxed>)");
        compileError("type arguments are checked", "import java.util.ArrayList;",
                "ArrayList<JavaString> l = new ArrayList<JavaIntBoxed>();",
                "expected handle<ArrayList<handle<JavaString>>>, found handle<ArrayList<handle<JavaIntBoxed>>>");
        compileError("an element is its type argument's", "import java.util.ArrayList;",
                "ArrayList<JavaString> l = new ArrayList();\nfor (JavaIntBoxed x : l) { }",
                "initializer of x: expected handle<JavaIntBoxed>, found handle<JavaString>");
        compileError("for-each needs an Iterable or array", "import java.time.LocalDate;",
                "for (JavaObject o : LocalDate.now()) { }", "for-each goes over a Java Iterable or array, not LocalDate");
        compileError("the number of type arguments", "import java.util.Collections;",
                "Collections.<JavaString, JavaString>emptyList();", "emptyList takes 1 type argument(s), not 2");
        compileError("a Java array's length is fixed", "", "JavaArray<JavaInt> a = new JavaArray(2);\na.length = JavaInt.of(3);",
                "a Java array's length is fixed when it is made");
    }

    /** Seal handed to Java (docs/JAVA.md §5), T3: lambdas as Java's interfaces, Seal classes implementing them, Object's methods. */
    private static void sealIntoJava() {
        output("T3: a Java Thread runs Seal code, a Seal Comparator sorts a Java list, a Java HashMap holds Seal keys",
                "import java.lang.Thread;\nimport java.util.ArrayList;\nimport java.util.Collections;\nimport java.util.Comparator;\n"
                + "import java.util.HashMap;\nimport java.util.HashSet;", """
                Thread t = new Thread(() -> System.stdout.println("run from a Java thread"));
                t.start();
                t.join();
                ArrayList<JavaString> names = new ArrayList();
                names.add(JavaString.of("Linus"));
                names.add(JavaString.of("Ada"));
                names.add(JavaString.of("Grace"));
                names.sort((a, b) -> a.compareTo(b));                  // a lambda as a Comparator<? super JavaString>
                System.stdout.print(names + " ");
                names.sort(new ByLength());                            // a Seal class implementing Comparator
                System.stdout.print(names + " ");
                Collections.sort(names, (a, b) -> b.compareTo(a));
                Comparator<JavaString> shortFirst = (a, b) -> a.length() - b.length();
                System.stdout.println(names + " " + (int)shortFirst.compare(JavaString.of("ab"), JavaString.of("a")) + " "
                        + (int)shortFirst.reversed().compare(JavaString.of("ab"), JavaString.of("a")));
                HashMap<Point, JavaString> where = new HashMap();
                where.put(new Point(1, 2), JavaString.of("home"));
                HashSet<Point> seen = new HashSet();
                seen.add(new Point(3, 4));
                seen.add(new Point(3, 4));                             // equal by ==, hashed by hash()
                System.stdout.println(where.get(JavaObject.of(new Point(1, 2))) + " " + where + " "
                        + (bit)where.containsKey(JavaObject.of(new Point(2, 1))) + " " + (int)seen.size());
                """, "run from a Java thread\n[Ada, Grace, Linus] [Ada, Grace, Linus] [Linus, Grace, Ada] 1 -1\nhome {(1, 2)=home} false 1\n",
                "Point", """
                class Point
                {
                    public int x;
                    public int y;
                    public new(int x, int y) { this.x = x; this.y = y; }
                    @overload(Operator.EQUALS) public bit same(Point o) { return x == o.x && y == o.y; }
                    public uint<<64>> hash() { return (uint<<64>>)(x * 31 + y); }
                    @overload(Operator.CAST) public string text() { return "(" + x + ", " + y + ")"; }
                }
                """, "ByLength", """
                import java.util.Comparator;
                import seal.java.*;
                class ByLength implements(Comparator<JavaString>)
                {
                    public JavaInt compare(JavaString a, JavaString b) { return a.length() - b.length(); }
                }
                """);
        output("a Seal Comparable in a TreeSet, through the bridge javac would write", "import java.util.TreeSet;", """
                TreeSet<Version> versions = new TreeSet();
                versions.add(new Version(3));
                versions.add(new Version(1));
                versions.add(new Version(2));
                System.stdout.println(versions + " " + versions.first().major + " " + (int)new Version(5).compareTo(new Version(2)));
                """, "[v1, v2, v3] 1 3\n", "Version", """
                import java.lang.Comparable;
                import seal.java.*;
                class Version implements(Comparable<Version>)
                {
                    public int major;
                    public new(int major) { this.major = major; }
                    public JavaInt compareTo(Version o) { return JavaInt.of(major - o.major); }
                    @overload(Operator.CAST) public string text() { return "v" + major; }
                }
                """);
        output("streams and callbacks: a lambda's result gives a type argument; Callable or Runnable, as Java chooses",
                "import java.util.ArrayList;\nimport java.util.HashMap;\nimport java.util.List;\n"
                + "import java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;", """
                List<JavaString> names = List.of(JavaString.of("Linus"), JavaString.of("Ada"), JavaString.of("Grace"));
                List<JavaString> upper = names.stream().map(s -> s.toUpperCase()).toList();
                HashMap<JavaString, ArrayList<JavaString>> groups = new HashMap();
                for (JavaString n : names) {
                    groups.computeIfAbsent(JavaString.valueOf(n.length()), k -> new ArrayList()).add(n);
                }
                ExecutorService pool = Executors.newSingleThreadExecutor();
                JavaString done = pool.submit(() -> JavaString.of("from a Callable")).get();
                pool.submit(() -> System.stdout.println("from a Runnable")).get();
                pool.shutdown();
                names.forEach(n -> System.stdout.print(n + ";"));
                System.stdout.println(" " + upper + " " + groups + " " + done);
                """, "from a Runnable\nLinus;Ada;Grace; [LINUS, ADA, GRACE] {3=[Ada], 5=[Linus, Grace]} from a Callable\n");
        compileError("a lambda takes as many parameters as Java's method", "import java.util.ArrayList;",
                "ArrayList<JavaString> l = new ArrayList();\nl.sort(a -> a);",
                "a lambda goes where Java wants an interface with one abstract method taking as many parameters");
        compileError("and gives Java's types", "import java.util.ArrayList;", "ArrayList<JavaString> l = new ArrayList();\nl.sort((a, b) -> 1);",
                "lambda result: expected JavaInt, found integer constant 1; Seal's integer constant 1 goes into Java by JavaInt.of(...)");
        compileError("a Java interface's methods must be implemented", "", "", "A must implement Comparable.compareTo(A), or be declared `abstract class A`",
                "A", "import java.lang.Comparable;\nclass A implements(Comparable<A>) { }");
        compileError("with Java's types", "", "", "B.compare returns int, but Comparator.compare(JavaString, JavaString) returns JavaInt",
                "B", "import java.util.Comparator;\nimport seal.java.*;\nclass B implements(Comparator<JavaString>)\n{\n"
                + "    public int compare(JavaString a, JavaString b) { return 0; }\n}");
    }

    /** Seal classes extending Java classes (docs/JAVA.md §5), T4. */
    private static void extendingJava() {
        output("T4: Seal classes extend classes from the test's class folder, and its Java code calls the Seal overrides",
                "import demo.Box;\nimport demo.Shape;\nimport java.lang.Thread;\nimport java.util.List;", """
                Square sq = new Square(2.0);
                System.stdout.println(sq.describe());                     // Java's describe() calls Seal's area() and unit()
                sq.scale(JavaByte.of(3));
                Shape s = sq;
                System.stdout.println(s.describe() + " " + (int)s.sides() + " " + sq.label());
                System.stdout.println(Shape.describeAll(List.of(new Square(1.0), new Circle(1.0))));   // a List<Shape>
                Labels l = new Labels();
                l.put(JavaString.of("hi"));
                Box<JavaString> b = l;
                b.put(JavaString.of("via java"));                         // Box.put(Object), bridged to Labels.put
                System.stdout.println(l.get() + " " + l.show());
                Worker w = new Worker("the docs");
                w.start();
                w.join();
                Thread t = w;
                System.stdout.println((bit)(t instanceof Worker) + " " + ((Worker)t).job);
                """, "square with 4 sides: 4.0 m2\nsquare with 4 sides: 36.0 m2 4 square!\n"
                + "square with 4 sides: 1.0 m2; circle with 0 sides: 3.0 incm2; \nVIA JAVA [VIA JAVA]\nworking on the docs: true\ntrue the docs\n",
                "Square", """
                import demo.Shape;
                import seal.java.*;
                class Square extends(Shape)
                {
                    double side;
                    public new(double side) { super(JavaString.of("square"), JavaInt.of(4)); this.side = side; }
                    public JavaDouble area() { return JavaDouble.of(side * side); }
                    public JavaString unit() { return JavaString.of("m"); }        // overrides a protected method
                    public void scale(JavaByte times) { side = side * (int)times; } // Java's scale(byte), bridged
                    public string label() { return (string)name + "!"; }           // the parent's protected field
                }
                """, "Circle", """
                import demo.Shape;
                import seal.java.*;
                class Circle extends(Shape)
                {
                    double r;
                    public new(double r) { super(JavaString.of("circle"), JavaInt.of(0)); this.r = r; }
                    public JavaDouble area() { return JavaDouble.of(3.0 * r * r); }
                    public JavaString unit() { return JavaString.of("in").concat(super.unit()); }   // the parent's own
                }
                """, "Labels", """
                import demo.Box;
                import seal.java.*;
                class Labels extends(Box<JavaString>)
                {
                    public void put(JavaString t) { super.put(t.toUpperCase()); }
                }
                """, "Worker", """
                import java.lang.Thread;
                import seal.java.*;
                class Worker extends(Thread)
                {
                    public string job;
                    public new(string job) { this.job = job; }   // Thread's constructor that takes nothing
                    public void run() { System.stdout.println("working on " + job + ": " + getName().startsWith(JavaString.of("Thread"))); }
                }
                """);
        output("a Seal class extending a JDK collection, its defaults inherited", "", """
                Names n = new Names();
                n.add(JavaString.of("ada"));
                n.add(JavaString.of("bo"));
                System.stdout.println(n + " " + n.reversed() + " " + (int)n.size());
                """, "[ADA, BO] [BO, ADA] 2\n", "Names", """
                import java.util.ArrayList;
                import seal.java.*;
                class Names extends(ArrayList<JavaString>)
                {
                    public JavaBoolean add(JavaString s) { return super.add(s.toUpperCase()); }
                }
                """);
        compileError("a final class can't be extended", "", "", "Java's demo.Sealed is final, so a class can't extend it",
                "A", "import demo.Sealed;\nclass A extends(Sealed) { }");
        compileError("a parent without a constructor that takes nothing needs super(...)", "", "",
                "Java's demo.Shape has no constructor that takes nothing, so each constructor of C calls one of its others first",
                "C", "import demo.Shape;\nimport seal.java.*;\nclass C extends(Shape) { public JavaDouble area() { return JavaDouble.of(1.0); } }");
        compileError("abstract methods must be implemented", "", "", "B must implement Shape.area(), or be declared `abstract class B`",
                "B", "import demo.Shape;\nimport seal.java.*;\nclass B extends(Shape) { public new() { super(JavaString.of(\"b\"), JavaInt.of(1)); } }");
        compileError("a final method can't be overridden", "", "", "sides overrides Java's demo.Shape.sides, which is final",
                "D", "import demo.Shape;\nimport seal.java.*;\nclass D extends(Shape)\n{\n"
                + "    public new() { super(JavaString.of(\"d\"), JavaInt.of(1)); }\n"
                + "    public JavaDouble area() { return JavaDouble.of(1.0); }\n"
                + "    public JavaInt sides() { return JavaInt.of(9); }\n}");
        compileError("an override returns what Java's returns", "", "",
                "E.unit returns string, but Java's demo.Shape.unit, which it overrides, returns handle<JavaString>",
                "E", "import demo.Shape;\nimport seal.java.*;\nclass E extends(Shape)\n{\n"
                + "    public new() { super(JavaString.of(\"e\"), JavaInt.of(1)); }\n"
                + "    public JavaDouble area() { return JavaDouble.of(1.0); }\n"
                + "    public string unit() { return \"x\"; }\n}");
        compileError("protected members are for the classes below", "import demo.Box;",
                "Box<JavaString> b = new Box();\nJavaString h = b.held;",
                "the field held of Java's demo.Box is protected, so only code of a class below it can use it");
        compileError("super.m() of an abstract method", "", "", "area is abstract in Java's demo.Shape; there is no implementation to call",
                "F", "import demo.Shape;\nimport seal.java.*;\nclass F extends(Shape)\n{\n"
                + "    public new() { super(JavaString.of(\"f\"), JavaInt.of(1)); }\n"
                + "    public JavaDouble area() { return super.area(); }\n}");
    }

    private static void exceptions() {
        output("Java's exceptions are caught by their class, and finally runs", "import java.nio.file.Files;\n"
                + "import java.nio.file.Path;\nimport java.io.IOException;\nimport java.lang.IllegalStateException as JavaIllegalState;", """
                try {
                    Files.readString(Path.of(JavaString.of("missing.txt")));
                } catch (IOException e) {
                    System.stdout.println("no file: " + e.getMessage() + " (" + e.getClass().getName() + ")");
                } finally {
                    System.stdout.println("finally");
                }
                try {
                    try {
                        throw new JavaIllegalState(JavaString.of("thrown from Seal"));
                    } finally {
                        System.stdout.println("inner finally");
                    }
                } catch (JavaIllegalState e) {
                    System.stdout.println("caught " + e.getMessage());
                }
                try {
                    throw new IllegalStateException("Seal's");
                } catch (IOException e) {
                    System.stdout.println("not this");
                } catch (IllegalStateException e) {
                    System.stdout.println("Seal's own: " + e.getMessage());
                }
                """, "no file: missing.txt (java.nio.file.NoSuchFileException)\nfinally\ninner finally\ncaught thrown from Seal\n"
                + "Seal's own: Seal's\n");
        trap("one nothing catches stops the program, as a Seal exception does", "import demo.Tools;",
                "Tools.fail(JavaString.of(\"why\"));\n", "uncaught java.lang.IllegalStateException: why\n    at Main.main (Main.seal:");
        trap("a null Java object's method stops the program", "import java.util.ArrayList;",
                "ArrayList none = null;\nnone.size();\n", "trap: null handle");
        compileError("catch takes Java's Throwables", "import java.util.ArrayList;",
                "try { } catch (ArrayList e) { }", "catch takes a class extending Exception, not ArrayList; of Java's, one extending java.lang.Throwable");
    }

    private static void javaArrays() {
        output("Java's arrays: copied in by JavaArray.of, out by a cast", "import java.util.Arrays;", """
                array<uint<<8>>> bytes = (array<uint<<8>>>)JavaString.of("hé").getBytes(JavaString.of("UTF-8"));
                array<int<<8>>> signed = (array<int<<8>>>)JavaString.of("hé").getBytes(JavaString.of("UTF-8"));
                System.stdout.println(bytes.length() + " " + bytes[1] + " " + signed[1] + " "
                        + new JavaString(JavaArray.of(bytes), JavaString.of("UTF-8")));
                array<string> parts = (array<string>)JavaString.of("a,b,c").split(JavaString.of(","));
                JavaArray<JavaInt> filled = new JavaArray(4);
                Arrays.fill(filled, JavaInt.of(9));
                array<int> nines = (array<int>)filled;
                nines[0] = 1;
                System.stdout.println(parts.length() + " " + parts[2] + " " + Arrays.toString(filled) + " " + nines[0] + nines[1]
                        + " " + Arrays.toString(JavaArray.of([3.5, 1.5])) + " " + Arrays.toString(JavaArray.of([true, false])));
                """, "3 195 -61 hé\n3 c [9, 9, 9, 9] 19 [3.5, 1.5] [true, false]\n");
        compileError("a Java array's elements are Java's types", "", "JavaArray<int> a = null;",
                "JavaArray<T> is Java's T[], so T is one of Java's types");
        compileError("new JavaArray(n) needs the type it makes", "", "JavaObject o = new JavaArray(3);",
                "new JavaArray(n) makes the type it goes to");
    }

    private static void imports() {
        output("static imports, and full names", "import static java.lang.Math.max;\nimport static java.lang.Math.PI;", """
                System.stdout.println((int)max(JavaInt.of(3), JavaInt.of(7)) + " " + (double)PI + " "
                        + java.time.LocalDate.of(JavaInt.of(2026), JavaInt.of(10), JavaInt.of(20)).getDayOfWeek());
                """, "7 3.141592653589793 TUESDAY\n");
        output("a Java class takes a Seal name's place in its file, by name", "import java.lang.StringBuilder;", """
                StringBuilder sb = new StringBuilder();
                sb.append(JavaString.of("ab")).append(JavaInt.of(12)).append(JavaChar.of('!'));
                System.stdout.println((string)sb.toString() + " " + (int)sb.length());
                """, "ab12! 5\n");
        compileError("java.lang.String is JavaString", "import java.lang.String;", "",
                "Java's java.lang.String is JavaString in Seal (docs/JAVA.md §3.1): import seal.java.JavaString;");
        compileError("a * import doesn't hide seal.lang's names", "import java.lang.*;", "Math.max(1, 2);",
                "it could be java.lang.Math, which a * import brings, or seal.lang.Math");
        compileError("two * imports bringing one name", "import java.util.*;\nimport seal.util.*;", "List l = null;",
                "it could be java.util.List or seal.util.List");
        compileError("a class that isn't public", "import java.lang.AbstractStringBuilder;", "",
                "Java's java.lang.AbstractStringBuilder isn't public");
        compileError("no such class", "import java.util.Nope;", "", "no type java.util.Nope to import");
        Run r = run(program("System.stdout.println(Tools.NAME);", "import demo.Tools;"), JavaClasses.jdk());
        check(r.errors() != null && r.errors().contains("no type demo.Tools to import"),
                "the class path's classes need the class path: " + r);
    }

    private static void compileErrors() {
        compileError("a misspelled method", "import java.time.LocalDate;", "LocalDate.now().plusDayz(JavaLong.of(1));",
                "Java's java.time.LocalDate has no method plusDayz");
        compileError("a JavaBoolean is a bit by a cast", "import java.util.ArrayList;", "if (new ArrayList().isEmpty()) { }",
                "condition must be a bit, not JavaBoolean; Java's boolean is a bit by a cast: (bit)x");
        compileError("a Seal object needs JavaObject.of", "import java.util.ArrayList;", "new ArrayList().add(new Point());",
                "Seal's handle<Point> goes into Java by JavaObject.of(...)", "Point", "class Point { }");
        compileError("a cast doesn't take a Seal value into Java", "", "JavaInt j = (JavaInt)5;",
                "a Seal value goes into Java by JavaInt.of(...), not by a cast");
        compileError("a Java object isn't one of Seal's", "", "Object o = JavaString.of(\"x\");",
                "expected handle<Object>, found handle<JavaString>; Java's objects aren't Seal's");
        compileError("a Java value comes out by a cast", "", "string s = JavaString.of(\"x\");",
                "a cast takes a Java value out: (string)x");
        compileError("a class without a public constructor", "import java.time.LocalDate;", "new LocalDate();",
                "Java's java.time.LocalDate has no public constructor; its static methods make them: LocalDate.now(...)");
        compileError("a switch on a JavaInt needs a cast", "", "switch (JavaInt.of(1)) { default -> { } }",
                "not JavaInt; JavaInt is Java's int, which a cast makes Seal's: switch ((int)x)");
    }

    // ================================================================ helpers

    /** A Main whose main() runs body, after the imports and seal.java's; then pairs of file name and source. */
    private static Map<String, String> program(String body, String imports, String... more) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Main.seal", imports + "\nimport seal.java.*;\n\nclass Main\n{\n    static void main()\n    {\n"
                + body + "\n    }\n}\n");
        for (int i = 0; i + 1 < more.length; i += 2) {
            files.put(more[i] + ".seal", more[i + 1]);
        }
        return files;
    }

    private record Run(String output, String trap, String errors) {
    }

    private static Run run(Map<String, String> files, JavaClasses classes) {
        Checker checker = Checker.analyze(files, LIBRARY, Set.of(), classes);
        if (!checker.diagnostics().isEmpty()) {
            return new Run("", null, checker.diagnostics().toString());
        }
        JvmGen.Result gen = JvmGen.generate(checker, "Main");
        if (!gen.errors().isEmpty()) {
            return new Run("", null, gen.errors().toString());
        }
        List<String> problems = JvmRunner.verify(gen, classes);
        if (!problems.isEmpty()) {
            return new Run("", null, "verification fails: " + problems);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        JvmRunner.Outcome o = JvmRunner.run(gen, java.io.InputStream.nullInputStream(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), err, true, List.of(demo));
        return new Run(bytes.toString(StandardCharsets.UTF_8), o.fault() == null ? null : JvmRunner.describe(o.fault()), null);
    }

    private static void output(String name, String imports, String body, String expected, String... more) {
        Run r = run(program(body, imports, more), classPath);
        check(r.errors() == null && r.trap() == null && r.output().equals(expected),
                name + "\n    expected " + show(expected) + "\n    got      " + show(r.output())
                        + (r.trap() == null ? "" : "\n    trap     " + r.trap()) + (r.errors() == null ? "" : "\n    " + r.errors()));
    }

    /** Expects the program to stop with a report that starts so. */
    private static void trap(String name, String imports, String body, String expected) {
        Run r = run(program(body, imports), classPath);
        check(r.trap() != null && r.trap().startsWith(expected),
                name + "\n    expected a trap starting " + show(expected) + "\n    got " + r);
    }

    private static void compileError(String name, String imports, String body, String expected, String... more) {
        Run r = run(program(body, imports, more), classPath);
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
