package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
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
 * End-to-end tests for language features beyond the basics in {@link RunTest}:
 * inheritance chains, construction and destruction on every path, pointers
 * into objects, nested generics, layout, and numeric edge cases.
 * `java -cp out io.github.thelinuxseal.seal.LanguageTest`.
 */
@SuppressWarnings("deprecation") // runs programs on the deprecated VM too, while it is kept
public final class LanguageTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    /** Print helpers available to every test's Main class: pr(x) prints x and a space. */
    private static final String HELPERS = """
                static void pr(int x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(uint x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(int<<64>> x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(uint<<64>> x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(double x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(bit x) { System.stdout.print(x); System.stdout.print(' '); }
            """;

    /** The standard library, loaded once; tests run with it as programs normally do. */
    private static final Map<String, String> LIBRARY = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        Main.readSources(Path.of("stdlib"), LIBRARY);
        // Probe.live(): how many class objects are live, through a native only the VM's tests declare.
        LIBRARY.put("Probe.seal", "struct Probe { @native(\"live_objects\") public static int live() { } }");
        inheritance();
        resources();
        construction();
        destruction();
        pointers();
        generics();
        operators();
        numbers();
        layout();
        statements();
        handles();
        fixedPoint();
        uniques();
        statics();
        abstractAndInterfaces();
        shapesOnEveryKind();
        genericsOnEveryKind();
        operatorsOnEveryKind();
        temporariesOnSkippedPaths();
        arraysOfObjects();
        pureMethods();
        powersAndCompileTimeOperators();
        importsAs();

        String jvm = ON_JVM.finish(failures);
        System.out.println(passed + " passed, " + failures.size() + " failed" + jvm);
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ tests

    private static void inheritance() {
        output("three levels with overrides and super", files(
                "A", """
                        class A
                        {
                            public int x;
                            public new() { x = 1; }
                            public int who() { return 1; }
                            public int chain() { return who(); }
                        }
                        """,
                "B", """
                        class B extends(A)
                        {
                            public new() { super(); x += 10; }
                            public int who() { return 2; }
                        }
                        """,
                "C", """
                        class C extends(B)
                        {
                            public int y = 5;
                            public new() { super(); x += 100; }
                            public int who() { return super.who() * 10 + 3; }
                        }
                        """,
                "Main", main("""
                        handle<A> a = new C();
                        pr(a.chain());
                        pr(a.x);
                        handle<C> c = (handle<C>)a;
                        pr(c.y);
                        handle<B> b = new B();
                        pr(b.chain());
                        """)), "23 111 5 2 ");
        output("virtual methods returning objects", files(
                "Point", "struct Point { public int x; public int y; }",
                "Shape", """
                        class Shape
                        {
                            public int id = 7;
                            public Point corner() { Point p; p.x = id; return p; }
                            public int area() { return corner().x; }
                        }
                        """,
                "Square", """
                        class Square extends(Shape)
                        {
                            public Point corner() { Point p = super.corner(); p.y = p.x * 2; return p; }
                        }
                        """,
                "Main", main("""
                        handle<Shape> s = new Square();
                        Point c = s.corner();
                        pr(c.x); pr(c.y); pr(s.area());
                        """)), "7 14 7 ");
        compileError("overrides must keep the return type", files(
                "A", "class A { public int<<8>> f() { return 1; } }",
                "B", "class B extends(A) { public int f() { return 2; } }",
                "Main", main("handle<A> a = new B(); pr((int)a.f());")),
                "B.f returns int, but it overrides A.f, which returns int<<8>>");
        output("parent constructor with arguments", files(
                "Base", "class Base { public int v; public new(int a) { v = a; } }",
                "Derived", "class Derived extends(Base) { public new(int a, int b) { super(a * b); } }",
                "Main", main("handle<Derived> d = new Derived(3, 4); pr(d.v);")), "12 ");
        output("parent's no-argument constructor runs first", files(
                "P", "class P { public int v; public new() { v = 7; } }",
                "Q", "class Q extends(P) { public int w; public new() { w = v + 1; } }",
                "Main", main("handle<Q> q = new Q(); pr(q.v); pr(q.w);")), "7 8 ");
        output("inherited fields keep their offsets", files(
                "P", "class P { public int<<8>> a; public int b; }",
                "Q", "class Q extends(P) { public int<<8>> c; public int sum() { return (int)a + b + (int)c; } }",
                "Main", main("""
                        handle<Q> q = new Q();
                        q.a = 1; q.b = 20; q.c = 3;
                        handle<P> p = q;
                        pr(p.b);
                        pr(q.sum());
                        """)), "20 24 ");
    }

    private static void construction() {
        output("constructor with a binder", files(
                "Buf", """
                        struct Buf<<int N>>
                        {
                            public int total;
                            public new() { total = N; }
                            public new<<int N>>(Buf<<int K>> other) { total = N * 10 + K; }
                        }
                        """,
                "Main", main("Buf<<3>> a; Buf<<4>> b = new Buf(a); pr(a.total); pr(b.total);")), "3 43 ");
        output("constructors guarded per shape", files(
                "Two", """
                        struct Two<<int N>>
                        struct Two<<int N, int M>>
                        {
                            public int v;
                            public new<<int N>>() { v = N; }
                            public new<<int N, int M>>() { v = N * M; }
                        }
                        """,
                "Main", main("Two<<5>> a; Two<<3, 4>> b; pr(a.v + b.v);")), "17 ");
        output("inline fields are constructed before the body", files(
                "Inner", "struct Inner { public int v; public new() { v = 5; } }",
                "Outer", """
                        struct Outer
                        {
                            public Inner in;
                            public int w = 2;
                            public int seen;
                            public new() { seen = in.v * 10 + w; }
                        }
                        """,
                "Main", main("Outer o; pr(o.seen); addr<Outer> p = new Outer(); pr((&p).seen); delete p;")), "52 52 ");
        output("new on a generic struct into an address", files(
                "Cell", "struct Cell<T><<int N>> { public array<T><<N>> items; }",
                "Main", main("""
                        addr<Cell<int><<4>>> c = new Cell();
                        (&c).items[3] = 9;
                        pr((&c).items[3]);
                        delete c;
                        """)), "9 ");
    }

    private static void destruction() {
        String noisy = "unique Noisy<<int ID>> { delete() { System.stdout.print(ID); } }";
        output("destructors on every exit path", files("Noisy", noisy, "Main", """
                struct Main
                {
                    static int early(bit b)
                    {
                        Noisy<<1>> a;
                        if (b) {
                            Noisy<<2>> c;
                            return 9;
                        }
                        Noisy<<3>> d;
                        return 0;
                    }

                    static void loop()
                    {
                        for (int i = 0; i < 3; i += 1) {
                            Noisy<<4>> n;
                            if (i == 0) { continue; }
                            if (i == 2) { break; }
                            Noisy<<5>> m;
                        }
                    }

                    static int main()
                    {
                        early(true);
                        System.stdout.println();
                        early(false);
                        System.stdout.println();
                        loop();
                        return 0;
                    }
                }
                """), "21\n31\n4544");
        output("arrays of objects are destroyed in reverse", files("Noisy", noisy, "Main", main("""
                { array<Noisy<<6>>><<3>> arr; }
                System.stdout.println();
                """)), "666\n");
        output("a unique's body runs before its fields are destroyed, last first", files(
                "Noisy", noisy,
                "Pair", "unique Pair { Noisy<<1>> a; Noisy<<2>> b; delete() { System.stdout.print(9); } }",
                "Main", main("{ Pair p; System.stdout.print('|'); } System.stdout.println();")), "|921\n");
        compileError("a class can't have a destructor", files(
                "Holder", "class Holder { public int n; delete() { System.stdout.print(0); } }",
                "Main", main("Holder h = new Holder();")),
                "class Holder cannot have a destructor: the garbage collector frees class objects, at no set time");
        compileError("a class can't hold a unique with a destructor", files(
                "Noisy", noisy,
                "Holder", "class Holder { public Noisy<<7>> n; }",
                "Main", main("Holder h = new Holder();")),
                "class Holder can't hold Noisy<<7>> in n: Noisy<<7>> has a destructor");
        compileError("nor one inside a struct or a fixed array", files(
                "Noisy", noisy,
                "Wrap", "unique Wrap { public array<Noisy<<1>>><<2>> two; }",
                "Holder", "class Holder { public Wrap w; }",
                "Main", main("Holder h = new Holder();")),
                "class Holder can't hold Wrap in w: Wrap has a destructor");
        compileError("nor one given as a type argument", files(
                "Noisy", noisy,
                "Box", "class Box<T> { public T item; }",
                "Main", main("Box<Noisy<<3>>> b = new Box();")),
                "Box<Noisy<<3>>> can't hold Noisy<<3>> in item: Noisy<<3>> has a destructor");
        output("a class may hold a unique without a destructor, and an array<T>", files(
                "Quiet", "unique Quiet { public int n; }",
                "Holder", "class Holder { public Quiet q; public array<int> xs = new array<int>(3); public static Noisy<<5>> kept; }",
                "Noisy", noisy,
                "Main", main("Holder h = new Holder(); h.q.n = 4; h.xs[2] = 6; pr(h.q.n + h.xs[2]);")), "10 5");
    }

    private static void pointers() {
        String point = "struct Point { public int x; public int y; }";
        output("pointers into arrays and fields", files("Point", point, "Main", main("""
                array<int><<5>> arr;
                for (int i = 0; i < 5; i += 1) { arr[i] = i * 10; }
                addr<int> p = *arr[2];
                &p = 99;
                pr(arr[2]);
                addr<int> q = p + sizeof(int);
                pr(&q);
                Point pt;
                addr<int> py = *pt.y;
                &py = 4;
                pr(pt.y);
                addr<Point> pp = *pt;
                (&pp).x = 3;
                pr(pt.x);
                """)), "99 30 4 3 ");
        output("pointer to pointer", files("Main", main("""
                int a = 1;
                int b = 2;
                addr<int> p = *a;
                addr<addr<int>> pp = *p;
                &pp = *b;
                &p = 5;
                pr(a); pr(b);
                """)), "1 5 ");
        output("untyped addresses round-trip", files("Main", main("""
                addr<int> p = new<int>(1);
                &p = 77;
                addr<void> v = (addr<void>)p;
                addr<int> q = (addr<int>)v;
                pr(&q);
                pr(p == q);
                delete q;
                """)), "77 true ");
        output("address arithmetic and differences", files("Main", main("""
                addr<int<<64>>> base = new<int<<64>>>(4);
                addr<int<<64>>> third = base + 2 * sizeof(int<<64>>);
                pr(third - base);
                pr(third > base);
                delete base;
                """)), "128 true ");
    }

    private static void generics() {
        String cell = "struct Cell<T><<int N>> { public array<T><<N>> items; }";
        output("nested generic types", files("Cell", cell, "Main", main("""
                Cell<Cell<int><<2>>><<3>> grid;
                grid.items[1].items[0] = 5;
                grid.items[2].items[1] = 6;
                pr(grid.items[1].items[0] + grid.items[2].items[1]);
                pr(sizeof(Cell<Cell<int><<2>>><<3>>));
                """)), "11 192 ");
        output("generic over handles", files("Cell", cell,
                "Doc", "class Doc { public int pages; }",
                "Main", main("""
                        Cell<handle<Doc>><<2>> c;
                        c.items[0] = new Doc();
                        c.items[0].pages = 3;
                        pr(c.items[0].pages);
                        pr(c.items[1] == null);
                        """)), "3 true ");
        output("static method with a binder", files(
                "Box", "struct Box<<int N>> { }",
                "Util", "struct Util { public static int len(Box<<int N>> b) { return N; } }",
                "Main", main("Box<<3>> a; Box<<5>> b; pr(Util.len(a) + Util.len(b));")), "8 ");
        output("operators on type parameters", files(
                "Pair", """
                        struct Pair<T>
                        {
                            public T a;
                            public T b;
                            public T larger() { if (a < b) { return b; } return a; }
                            public T sum() { return a + b; }
                        }
                        """,
                "Main", main("""
                        Pair<int> i; i.a = 3; i.b = 9;
                        Pair<double> d; d.a = 2.5; d.b = 0.5;
                        pr(i.larger()); pr(i.sum()); pr(d.larger()); pr(d.sum());
                        """)), "9 12 2.5 3.0 ");
        output("bare and valued shapes side by side", files(
                "Buf", """
                        struct Buf<<>>
                        struct Buf<<int N>>
                        {
                            array<int><<N>> data<<int N>>;
                            array<int><<2>> data<<>>;
                            public int cap<<int N>>() { return N; }
                            public int cap<<>>() { return 2; }
                            public void put(int i, int v) { data[i] = v; }
                            public int get(int i) { return data[i]; }
                        }
                        """,
                "Main", main("""
                        Buf small;
                        Buf<<10>> big;
                        small.put(1, 4);
                        big.put(9, 5);
                        pr(small.cap() + big.cap());
                        pr(small.get(1) + big.get(9));
                        """)), "12 9 ");
    }

    private static void operators() {
        String num = """
                struct Num
                {
                    public int v;
                    @overload(Operator.NEGATE) public Num neg() { Num r; r.v = -v; return r; }
                    @overload(Operator.SUBTRACT) public Num sub(Num o) { Num r; r.v = v - o.v; return r; }
                    @overload(Operator.LESS) public bit lt(Num o) { return v < o.v; }
                    @overload(Operator.EQUALS) public bit eq(Num o) { return v == o.v; }
                    public Num twice() { Num r; r.v = v * 2; return r; }
                }
                """;
        output("unary, binary and derived operators", files("Num", num, "Main", main("""
                Num a; a.v = 3;
                Num b; b.v = 5;
                Num c = -a;
                pr(c.v);
                Num d = b - a;
                pr(d.v);
                pr(a > b); pr(a <= b); pr(a >= b); pr(b >= a); pr(a != b); pr(a == a);
                pr((b - a - a).v);
                pr((-a).twice().v);
                """)), "-3 2 false true false true true true -1 -6 ");
        output("operators on objects inside dynamic objects", files("Num", num,
                "Holder", "class Holder { public Num n; }",
                "Main", main("""
                        handle<Holder> h = new Holder();
                        h.n.v = 4;
                        Num m = -h.n;
                        pr(m.v);
                        pr(h.n == h.n);
                        """)), "-4 true ");
        output("numbers narrow where stored or passed, as C's do", files("Main", """
                class Main
                {
                    static int<<8>> clip(int x) { return x; }
                    static int half(double x) { return x / 2; }
                    static void show(int<<8>> b) { System.stdout.print("show " + b + " "); }

                    static void main()
                    {
                        int a = 2.5;
                        int<<8>> b = a;
                        int big = 1000;
                        int<<8>> c = big;
                        int code = 67;
                        char ch = code;
                        uint u = -1 + a - a;
                        int fromD = -3.9;
                        fixed<<8, 8>> fx = 2.75;
                        int fromFx = fx;
                        array<int<<8>>> xs = [a, big];
                        System.stdout.println(a + " " + b + " " + c + " " + ch + " " + u + " " + fromD + " " + fromFx
                                + " " + xs[1]);
                        show(big);
                        System.stdout.println(clip(300) + " " + half(7));
                    }
                }
                """), "2 2 -24 C 4294967295 -3 2 -24\nshow -24 44 3\n");
        output("string.format and printf, as Java's (§42.6)", files("Point", """
                class Point
                {
                    int x;
                    int y;
                    public new(int x, int y) { this.x = x; this.y = y; }
                    @overload(Operator.CAST) public string text() { return "(" + x + ", " + y + ")"; }
                }
                """, "Main", """
                class Main
                {
                    static void main()
                    {
                        string name = "Ada";
                        System.stdout.println(string.format("%-8s|%10.2f|%5d|", name, 1234.5, 42));
                        System.stdout.println(string.format("%,d %,.3f %+d % d %05d", 1234567, 9876543.21, 42, 42, 42));
                        System.stdout.println(string.format("%x %X %#x %o %#o %x %x", 255, 255, 255, 8, 8, -1, (int<<8>>)-1));
                        System.stdout.println(string.format("%e %.2E %f %.0f %.1f %.2f", 12345.678, 0.000123, 1.0 / 3, 2.5, 0.25, 1.005));
                        System.stdout.println(string.format("%s %S %.2s %c %C %b %B", "hello", "hello", "hello", 'q', 'q', true, false));
                        System.stdout.println(string.format("%s %s %s %s %d %s", 3, 2.5, 'z', true, 'A', new Point(3, 4)));
                        fixed<<16, 16>> fx = 2.75;
                        System.stdout.println(string.format("%s %.1f %08.2f %d", fx, fx, fx, Integer.MIN_INT64));
                        System.stdout.printf("[%6.2f] [%-6.1f] [%06.1f] 100%% %s%n", -3.14159, 2.0, -2.5, 0.0 / 0.0);
                    }
                }
                """), """
                Ada     |   1234.50|   42|
                1,234,567 9,876,543.210 +42  42 00042
                ff FF 0xff 10 010 ffffffff ff
                1.234568e+04 1.23E-04 0.333333 3 0.3 1.01
                hello HELLO he q Q true FALSE
                3 2.5 z true 65 (3, 4)
                2.75 2.8 00002.75 -9223372036854775808
                [ -3.14] [2.0   ] [-002.5] 100% NaN
                """);
        output("string.format errors, as exceptions", files("Main", """
                class Main
                {
                    static void attempt(string fmt, int n)
                    {
                        try {
                            System.stdout.println(string.format(fmt, n));
                        } catch (IllegalArgumentException e) {
                            System.stdout.println(e.getMessage());
                        }
                    }

                    static void main()
                    {
                        attempt("%q", 1);
                        attempt("%d %d", 1);
                        attempt("%.2d", 1);
                        attempt("%b", 1);
                        attempt("50%", 1);
                        attempt("ok %d", 7);
                    }
                }
                """), """
                unknown conversion %q; the conversions are d x X o f e E s S c C b B, %% and %n, in the format "%q"
                no value for %d, in the format "%d %d"
                %.2d takes no precision, in the format "%.2d"
                %b can't show an integer, in the format "%b"
                the format ends inside the specifier %, in the format "50%"
                ok 7
                """);
        output("packages and imports (§2.4)", files(
                "zoo/animals/Animal", """
                        package zoo.animals;

                        abstract class Animal implements(zoo.util.Named)
                        {
                            private<app.Main> int secret = 7;
                            public abstract string sound();
                            public string name() { return "animal"; }
                        }
                        """,
                "zoo/animals/Dog", """
                        package zoo.animals;

                        class Dog extends(Animal)
                        {
                            public string sound() { return "woof"; }
                            public string name() { return "dog"; }
                        }
                        """,
                "zoo/util/Named", "package zoo.util;\n\ninterface Named { string name(); }",
                "zoo/util/Box", """
                        package zoo.util;

                        import seal.util.function.Function;

                        class Box<T>
                        {
                            public T value;
                            public new(T v) { value = v; }
                            public <R> Box<R> map(Function<T, R> f) { return new Box<R>(f.apply(value)); }
                        }
                        """,
                "zoo/util/ZooException", """
                        package zoo.util;

                        class ZooException extends(RuntimeException) { public new(string m) { super(m); } }
                        """,
                "other/Box", "package other;\n\nclass Box { public static int n() { return 5; } }",
                "app/Main", """
                        package app;

                        import zoo.animals.*;
                        import zoo.util.Box;
                        import zoo.util.Named;

                        class Main
                        {
                            static void main()
                            {
                                Animal a = new Dog();
                                Named n = a;
                                Box<string> sounds = new Box<Animal>(a).map(x -> x.sound());
                                System.stdout.println(n.name() + " says " + sounds.value + " " + a.secret + " " + other.Box.n());
                                try {
                                    throw new zoo.util.ZooException("escaped");
                                } catch (zoo.util.ZooException e) {
                                    System.stdout.println("caught " + e.getMessage());
                                }
                            }
                        }
                        """), "dog says woof 7 5\ncaught escaped\n");
        output("abstract and default fields, default methods (§30.4)", files(
                "Named", """
                        interface Named
                        {
                            string name;                         // every class implementing Named holds one
                            default string greeting = "Hello";   // the value a class gets, unless it gives its own

                            default string greet() { return greeting + ", " + name + "!"; }
                            default void rename(string to) { name = to; }
                        }
                        """,
                "Shape", """
                        abstract class Shape implements(Named)
                        {
                            public abstract int sides;
                            public default string color = "red";

                            public string describe() { return color + " " + name + " with " + sides + " sides"; }
                            public void grow() { sides++; }
                        }
                        """,
                "Square", """
                        class Square extends(Shape)
                        {
                            public int sides = 4;
                            public string name;
                            public new(string n) { name = n; }
                        }
                        """,
                "Triangle", """
                        class Triangle extends(Shape)
                        {
                            public int sides = 3;
                            public string name = "triangle";
                            public string color = "blue";
                            string greeting = "Hi";
                        }
                        """,
                "Op", """
                        interface Op
                        {
                            int apply(int x);
                            default int twice(int x) { return apply(apply(x)); }
                        }
                        """,
                "Main", """
                        class Main
                        {
                            static void main()
                            {
                                Square sq = new Square("square");
                                Shape t = new Triangle();
                                System.stdout.println(sq.describe() + " | " + t.describe());
                                Named n = sq;
                                System.stdout.println(n.greet() + " " + t.greet() + " " + n.name + " " + n.greeting);
                                n.name = "box";
                                n.rename(n.name + "!");
                                t.sides += 2;
                                t.grow();
                                System.stdout.println(sq.name + " " + sq.greet() + " " + t.sides + " " + t.color + " " + sq.sides);
                                Op inc = x -> x + 1;
                                System.stdout.println(inc.twice(5));
                            }
                        }
                        """), """
                red square with 4 sides | blue triangle with 3 sides
                Hello, square! Hi, triangle! square Hello
                box! Hello, box!! 6 blue 4
                7
                """);
        output("records (§2.5)", files(
                "Point", """
                        record Point(int x, int y)
                        {
                            public static Point origin() { return new Point(0, 0); }
                            public Point plus(Point o) { return new Point(x + o.x, y + o.y()); }
                        }
                        """,
                "Range", """
                        record Range(int lo, int hi)
                        {
                            new
                            {
                                if (lo > hi) { throw new IllegalArgumentException("lo " + lo + " is above hi " + hi); }
                            }
                            public int size() { return hi - lo; }
                        }
                        """,
                "Animal", """
                        class Animal
                        {
                            string name;
                            public new(string name) { this.name = name; }
                            @overload(Operator.CAST) public string text() { return name; }
                        }
                        """,
                "Owner", """
                        record Owner(string name, Animal pet)
                        {
                            @overload(Operator.CAST) public string text() { return name + " and " + pet; }
                        }
                        """,
                "Pair", "record Pair<A, B>(A first, B second) { }",
                "Main", """
                        import seal.util.HashMap;
                        import seal.util.List;
                        class Main
                        {
                            static void main()
                            {
                                Point p = new Point(3, 4);
                                Point q = new Point(3, 4);
                                System.stdout.println(p + " " + p.x() + " " + p.y + " " + (p == q) + " " + (p != Point.origin())
                                        + " " + (p.hash() == q.hash()) + " " + p.plus(q));
                                System.stdout.println(new Range(2, 5) + " size " + new Range(2, 5).size());
                                try {
                                    Range bad = new Range(5, 2);
                                } catch (IllegalArgumentException e) {
                                    System.stdout.println("caught: " + e.getMessage());
                                }
                                Owner o = new Owner("Ada", new Animal("Tom"));
                                Animal pet = o.pet();
                                System.stdout.println(o + ", " + o.pet() + ", " + pet);
                                Pair<string, Point> pair = new Pair<string, Point>("here", p);
                                System.stdout.println(pair + " " + pair.second().x);
                                List<Point> pts = new List();
                                pts.add(p);
                                pts.add(Point.origin());
                                System.stdout.println(pts.contains(new Point(0, 0)) + " " + pts.indexOf(q));
                                HashMap<Point, string> names = new HashMap();
                                names.put(p, "p");
                                Point blank;
                                System.stdout.println(names.get(new Point(3, 4)) + " " + blank);
                            }
                        }
                        """), """
                Point[x=3, y=4] 3 4 true true true Point[x=6, y=8]
                Range[lo=2, hi=5] size 3
                caught: lo 5 is above hi 2
                Ada and Tom, Tom, Tom
                Pair[first=here, second=Point[x=3, y=4]] 3
                true 0
                p Point[x=0, y=0]
                """);
        compileError("a record's components are fixed", files("Point", "record Point(int x, int y) { }",
                "Main", main("Point p = new Point(1, 2); p.x = 3;")),
                "x is a component of record Point, fixed when it is made; make a new Point with the value instead");
        compileError("a record's own methods can't change its components", files("Point", """
                record Point(int x, int y)
                {
                    public void move() { x++; }
                }
                """, "Main", main("")), "x is a component of record Point, fixed when it is made");
        output("text blocks (§39)", files("Main", main("""
                string html = \"""
                    <ul>
                        <li>"one"</li>
                    </ul>
                    \""";
                string joined = \"""
                    a \\
                    b\\s
                    c\""";
                System.stdout.print(html + joined + "|" + html.length());
                """)), "<ul>\n    <li>\"one\"</li>\n</ul>\na b \nc|30");
        output("switch expressions (§41.3)", files("Color", "enum Color { RED, GREEN, BLUE; }",
                "Guard", """
                        unique Guard
                        {
                            int id;
                            public new(int id) { this.id = id; }
                            delete() { System.stdout.print("~" + id + " "); }
                        }
                        """,
                "Animal", """
                        class Animal
                        {
                            string name;
                            public new(string name) { this.name = name; }
                            @overload(Operator.CAST) public string text() { return name; }
                        }
                        """,
                "Main", """
                        class Main
                        {
                            static string describe(int n)
                            {
                                return switch (n) {
                                    case 0 -> "zero";
                                    case 1, 2, 3 -> "small";
                                    default -> {
                                        if (n < 0) { yield "negative"; }
                                        yield "big:" + n;
                                    }
                                };
                            }

                            static int score(Color c)
                            {
                                return switch (c) { case RED -> 1; case GREEN -> 2; case BLUE -> 3; };
                            }

                            static Animal pick(string kind)
                            {
                                return switch (kind) {
                                    case "cat" -> new Animal("Tom");
                                    case "dog" -> new Animal("Rex");
                                    default -> throw new IllegalArgumentException("no " + kind);
                                };
                            }

                            static string colon(int n)
                            {
                                return switch (n) {
                                    case 1:
                                    case 2:
                                        yield "low" + n;
                                    case 3:
                                        System.stdout.print("(3 falls through) ");
                                    default:
                                        yield "other";
                                };
                            }

                            static void main()
                            {
                                for (int n = -1; n <= 5; n += 2) { System.stdout.print(describe(n) + " "); }
                                System.stdout.println(score(Color.RED) + score(Color.BLUE) * 10);
                                double d = 1 + switch (score(Color.GREEN)) { case 2 -> 0.5; default -> 1; };
                                System.stdout.println(d + " " + pick("cat") + " " + pick("dog"));
                                try {
                                    pick("cow");
                                } catch (IllegalArgumentException e) {
                                    System.stdout.println("caught " + e.getMessage());
                                }
                                int x = 7;
                                int y = switch (x % 3) {
                                    case 0 -> 100;
                                    default -> {
                                        Guard g = new Guard(x);
                                        int total = 0;
                                        for (int i = 0; i < 10; i++) {
                                            if (i == 4) { break; }
                                            total += i;
                                        }
                                        try {
                                            yield total;
                                        } finally {
                                            System.stdout.print("finally ");
                                        }
                                    }
                                };
                                System.stdout.println("y=" + y + " t:" + switch (x) { case 7 -> "seven"; default -> "other"; });
                                System.stdout.println(colon(1) + " " + colon(3));
                            }
                        }
                        """), """
                negative small small big:5 31
                1.5 Tom Rex
                caught no cow
                finally ~7 y=6 t:seven
                (3 falls through) low1 other
                """);
        compileError("a switch expression on an enum names every constant, or has a default", files(
                "Color", "enum Color { RED, GREEN, BLUE; }",
                "Main", main("Color c = Color.RED; int n = switch (c) { case RED -> 1; case GREEN -> 2; };")),
                "a switch expression needs a value for every case: add a default, or cases for BLUE");
        output("labeled break and continue", files("Guard", """
                unique Guard
                {
                    int id;
                    public new(int id) { this.id = id; }
                    delete() { System.stdout.print("~" + id + " "); }
                }
                """, "Main", """
                import seal.util.List;
                class Main
                {
                    static void main()
                    {
                        array<int><<2, 3>> grid = [[1, 2, 3], [4, 5, 6]];
                        outer:
                        for (int r = 0; r < 2; r++) {
                            for (int c = 0; c < 3; c++) {
                                if (grid[r][c] == 5) { System.stdout.print("at " + r + "," + c + " "); break outer; }
                            }
                        }
                        rows:
                        for (int r = 0; r < 3; r++) {
                            for (int c = 0; c < 3; c++) {
                                if (c > r) { continue rows; }
                                System.stdout.print(r + "" + c + " ");
                            }
                        }
                        List<string> words = new List();
                        words.add("ab"); words.add("cd"); words.add("ef");
                        each:
                        for (string w : words) {
                            for (char ch : w) {
                                if (ch == 'd') { continue each; }
                                if (ch == 'e') { break each; }
                                System.stdout.print(ch);
                            }
                        }
                        System.stdout.println();
                        int n = 0;
                        spin:
                        while (n < 100) {
                            n++;
                            Guard g = new Guard(n);
                            try {
                                switch (n) {
                                    case 2 -> { continue spin; }
                                    case 3 -> { break spin; }
                                    default -> { }
                                }
                                System.stdout.print("body" + n + " ");
                            } finally {
                                System.stdout.print("fin" + n + " ");
                            }
                        }
                        System.stdout.println("n=" + n);
                    }
                }
                """), "at 1,1 00 10 11 20 21 22 abc\nbody1 fin1 ~1 fin2 ~2 fin3 ~3 n=3\n");
        output("a cast to string is an object's text, as Java's toString() (§46)", files(
                "Point", """
                        struct Point
                        {
                            public int x;
                            public int y;
                            public new() { }
                            public new(int x, int y) { this.x = x; this.y = y; }
                            @overload(Operator.CAST) public string text() { return "(" + x + ", " + y + ")"; }
                        }
                        """,
                "Account", """
                        class Account
                        {
                            string owner;
                            public new(string owner) { this.owner = owner; }
                            @overload(Operator.CAST) public string describe() { return "account of " + owner; }
                        }
                        """,
                "Color", "enum Color { RED, GREEN; }",
                "Suit", """
                        enum Suit
                        {
                            HEARTS, SPADES;
                            @overload(Operator.CAST) public string symbol() { if (this == HEARTS) { return "<3"; } return "^"; }
                        }
                        """,
                "Main", """
                        import seal.util.BigUInt;
                        import seal.util.List;
                        class Main
                        {
                            static void main()
                            {
                                Point p = new Point(1, 2);
                                Account a = new Account("Ada");
                                System.stdout.println("p " + p + ", " + a + ", " + Color.GREEN + ", " + Suit.HEARTS);
                                System.stdout.println(p + " first");
                                System.stdout.println(p);
                                System.stdout.print(Color.RED);
                                System.stdout.println(Suit.SPADES);
                                string s = "joined: ";
                                s += p;
                                s += Color.GREEN;
                                System.stdout.println(s);
                                System.stdout.println(string.format("[%s] [%S] [%-8s]", a, Color.RED, p));
                                List<Point> pts = new List();
                                pts.add(p);
                                for (Point q : pts) { System.stdout.println("in a list " + q + " " + (string)q); }
                                BigUInt<<128>> big;
                                big.set(12345678901234);
                                System.stdout.println("big " + big * big);
                            }
                        }
                        """), """
                p (1, 2), account of Ada, GREEN, <3
                (1, 2) first
                (1, 2)
                RED^
                joined: (1, 2)GREEN
                [account of Ada] [RED] [(1, 2)  ]
                in a list (1, 2) (1, 2)
                big 152415787532374345526722756
                """);
        compileError("+ on an object without a cast to string", files("Box", "class Box { public int v; }",
                "Main", main("Box b = new Box(); string s = \"box \" + b;")),
                "Box has no cast to string, which + needs to join it to a string; give it one, as Java's toString():"
                        + " @overload(Operator.CAST) public string text() { ... }");
        compileError("println of an object without a cast to string is reported where it's called", files(
                "Box", "class Box { public int v; }", "Main", main("Box b = new Box(); System.stdout.println(b);")),
                "in seal.io.Writer.println[T=handle<Box>]: Box has no cast to string");
        output("cast operators (§46)", files(
                "Meters", """
                        struct Meters
                        {
                            public double v;

                            public new(double v) { this.v = v; }

                            @overload(Operator.CAST) public double toDouble() { return v; }
                            @overload(Operator.CAST) public int toInt() { return (int)v; }
                            @overload(Operator.CAST) public Feet toFeet() { return new Feet(v * 3); }
                            @overload(Operator.CAST) public string toText() { return v + " m"; }
                            @overload(Operator.CAST) public Label toLabel() { return new Label("len " + v); }
                        }
                        """,
                "Feet", "struct Feet { public double v; public new(double v) { this.v = v; } }",
                "Label", "class Label { public string text; public new(string t) { text = t; } }",
                "Account", """
                        class Account
                        {
                            int<<64>> cents;

                            public new(int<<64>> c) { cents = c; }

                            @overload(Operator.CAST) public double dollars() { return cents / 100.0; }
                        }
                        """,
                "Savings", "class Savings extends(Account) { public new(int<<64>> c) { super(c); } }",
                "Main", """
                        class Main
                        {
                            static Meters make(double v) { return new Meters(v); }

                            static void main()
                            {
                                Meters m = new Meters(2.5);
                                Feet f = (Feet)m;
                                Label l = (Label)m;
                                System.stdout.println((double)m + " " + (int)m + " " + f.v + " " + (string)m + " " + l.text);
                                System.stdout.println((double)make(4) + " " + ((Feet)make(1)).v + " " + ((Label)make(7)).text);
                                Savings sv = new Savings(1234);
                                Account up = (Account)sv;
                                System.stdout.println((double)sv + " " + (double)up);
                            }
                        }
                        """), "2.5 2 7.5 2.5 m len 2.5\n4.0 3.0 len 7.0\n12.34 12.34\n");
    }

    private static void numbers() {
        output("integer edge cases", files("Main", main("""
                int<<64>> m = 9223372036854775807; m += 1; pr(m);
                uint<<64>> big = 18446744073709551615; pr(big > 1); pr(big / 3);
                int<<17>> x = 65535; x += 1; pr((int)x);
                int<<64>> s = 1; pr(s <<< 63);
                uint<<3>> t = 7; t += 1; pr((uint)t);
                int neg = -7; pr(neg % 3); pr(neg / 2);
                uint u = 4294967295; pr(u % 10);
                int<<8>> n8 = -128; n8 = -n8; pr((int)n8);
                """)), "-9223372036854775808 true 6148914691236517205 -65536 -9223372036854775808 0 -1 -3 5 -128 ");
        output("float edge cases", files("Main", main("""
                double neg = -2.9; pr((int)neg);
                double huge = 3000000000.0; pr((int)huge);
                pr((double)(float)0.1);
                double z = 0.0;
                double n = z / z;
                pr(n == n); pr(n != n);
                pr(1.0 / z);
                pr(-1.0 / z);
                float f = 16777216; f = f + 1; pr((double)f);
                """)), "-2 -1294967296 0.10000000149011612 false true Infinity -Infinity 1.6777216E7 ");
        output("bit and slice assignment", files("Main", main("""
                uint x = 0;
                x[3] = true;
                pr(x);
                x[3] ^= true;
                pr(x);
                x[0..4] = 9;
                x[0..4] += 1;
                pr(x);
                int<<8>> y = 0;
                y[4..8] = 15;
                pr((int)y);
                uint<<64>> w = 0;
                w[63] = true;
                pr(w);
                """)), "8 0 10 -16 9223372036854775808 ");
        output("string literals are string<<length>> when nothing else decides", files("Main", """
                struct Main
                {
                    static void show(string s) { System.stdout.print("bare "); }
                    static void show(string<<5>> s) { System.stdout.print("five "); }
                    static void show(string<<9>> s) { System.stdout.print("nine "); }
                    static int main()
                    {
                        System.stdout.print("hello".length()); System.stdout.print(" ");
                        show("hello");
                        string<<4>> ab = "ab" + "cd";
                        System.stdout.print(ab); System.stdout.print(" ");
                        string joined = "a" + "b" + "c";
                        System.stdout.print(joined); System.stdout.print(" ");
                        string bare = "x";
                        System.stdout.print("x" == bare); System.stdout.print(" ");
                        System.stdout.print("abc".charAt(1) == 'b'); System.stdout.print(" ");
                        System.stdout.print("abc" < "abd"); System.stdout.print(" ");
                        string<<10>> big = "abc";
                        System.stdout.print(big + "def");
                        return 0;
                    }
                }
                """), "5 five abcd abc true true true abcdef");
        compileError("no overload takes the literal's own length", files("Main", """
                struct Main
                {
                    static void show(string s) { }
                    static void show(string<<5>> s) { }
                    static int main() { show("hi"); return 0; }
                }
                """), "ambiguous call to show: 2 overloads match");
        compileError("a literal joined at run time is fixed-size", files("Main", main("""
                string<<2>> a = "ab";
                string<<3>> s = a + "cd";
                """)), "expected string<<3>>, found string<<4>>");
        output("array literals", files("Main", """
                struct Main
                {
                    static int sum(array<int><<3>> xs) { return xs[0] + xs[1] + xs[2]; }
                    static void show(array<int><<2>> xs) { System.stdout.print("int "); }
                    static void show(array<int<<64>>><<2>> xs) { System.stdout.print("long "); }
                    static int main()
                    {
                        array<int><<3>> a = [1, 2, 3];
                        a = [a[2], a[1], a[0]];
                        System.stdout.print(a[0] * 100 + a[1] * 10 + a[2]); System.stdout.print(" ");
                        array<uint<<8>>><<2>> small = [200, 255];
                        System.stdout.print((int)small[1]); System.stdout.print(" ");
                        System.stdout.print(sum([4, 5, 6])); System.stdout.print(" ");
                        System.stdout.print([10, 20, 30][1]); System.stdout.print(" ");
                        array<int><<2, 3>> grid = [[1, 2, 3], [4, 5, 6]];
                        System.stdout.print(grid[1][2]); System.stdout.print(" ");
                        array<char><<3>> letters = ['x', 'y', 'z'];
                        System.stdout.print(letters[2]); System.stdout.print(" ");
                        array<string><<2>> words = ["hi", "there"];
                        System.stdout.print(words[1]); System.stdout.print(" ");
                        System.stdout.print(["a", "bcd"][1]); System.stdout.print(" ");
                        array<double><<3>> ds = [1, 2.5, 3];
                        System.stdout.print(ds[1]); System.stdout.print(" ");
                        System.stdout.print([1, 2.5][0]); System.stdout.print(" ");
                        int x = 5;
                        System.stdout.print([x, 7][1]); System.stdout.print(" ");
                        show([1, 2]);
                        System.stdout.print([3000000000, 1][0]);
                        return 0;
                    }
                }
                """), "321 255 15 20 6 z there bcd 2.5 1.0 7 int 3000000000");
        String file = """
                unique Fd
                {
                    public int fd;
                    public new(int f) { fd = f; System.stdout.print(fd); System.stdout.print("+ "); }
                    delete() { System.stdout.print(fd); System.stdout.print("- "); }
                }
                """;
        output("an array literal of uniques takes fresh values", files("Fd", file, "Main", """
                struct Main
                {
                    static int main()
                    {
                        array<Fd><<2>> fs = [new Fd(1), new Fd(2)];
                        fs = [new Fd(3), new Fd(4)];
                        System.stdout.print(fs[0].fd + fs[1].fd); System.stdout.print(" ");
                        return 0;
                    }
                }
                """), "1+ 2+ 3+ 4+ 2- 1- 7 4- 3- ");
        compileError("an array literal can't copy a unique", files("Fd", file, "Main", main("""
                Fd f = new Fd(1);
                array<Fd><<1>> fs = [f];
                """)), "array element: Fd is unique and can't be copied");
        output("an array literal of handles keeps its own references", files("Box", """
                class Box
                {
                    public int v;
                    public new(int n) { v = n; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        int base = Probe.live();
                        Box keep = new Box(1);
                        array<Box><<2>> boxes = [keep, new Box(2)];
                        boxes = [boxes[1], null];
                        System.stdout.print(Probe.live() - base);
                        keep = null;
                        System.stdout.print(Probe.live() - base);
                        System.stdout.print(boxes[0].v);
                        return 0;
                    }
                }
                """), "212");
        output("joining strings with + and converting fixed strings", files("Main", """
                struct Main
                {
                    static string bare(string s) { return s; }
                    static string<<2>> two() { return "xy"; }
                    static int main()
                    {
                        int n = 42;
                        string name = "Ada";
                        System.stdout.print("n=" + n + " " + name + "! ");
                        string<<5>> five = "fixed";
                        string b = five;
                        b = two();
                        System.stdout.print(bare(five) + b + " ");
                        int<<8>> small = -5;
                        char c = 'A';
                        fixed<<16, 16>> f = 2.75;
                        float fl = 0.1;
                        System.stdout.print("" + small + c + true + f + " " + 0.5 + " " + fl + " " + 1e10 + " ");
                        string s = "count: ";
                        s += 3;
                        System.stdout.print(s);
                        return 0;
                    }
                }
                """), "n=42 Ada! fixedxy -5Atrue2.75 0.5 0.1 1.0E10 count: 3");
        output("a number before a string joins as text", files("Main", main("""
                int n = 3;
                System.stdout.print(n + " apples " + (1 + 2 + "!") + (n > 2) + " " + 2.5 + "");
                string s = 4 + "x";
                System.stdout.print(" " + s);
                """)), "3 apples 3!true 2.5 4x");
        compileError("@implicit is the library's", files("Meters", """
                struct Meters
                {
                    public int v;
                    public new() { }
                    @implicit public new(int x) { v = x; }
                }
                """, "Main", main("")), "@implicit is for the standard library's string only: an object is made with new");
        compileError("a struct is made with new, not from a number", files("Meters", """
                struct Meters
                {
                    public double v;
                    public new(double v) { this.v = v; }
                }
                """, "Main", main("Meters m = 2.5;")), "initializer of m: expected Meters, found floating-point constant");
        output("a string<<N>> still becomes a string where one is wanted", files("Main", main("""
                string<<5>> s = "hello";
                string t = s;
                pr(t.length());
                """)), "5 ");
        output("for-each over arrays, strings, lists and Iterables", files("Node", """
                class Node
                {
                    public int value;
                    public Node next;
                    public new(int v, Node n) { value = v; next = n; }
                }
                """, "Walk", """
                import seal.util.Iterator;
                class Walk implements(Iterator<int>)
                {
                    Node at;
                    public new(Node start) { at = start; }
                    public bit hasNext() { return at != null; }
                    public int next() { int v = at.value; at = at.next; return v; }
                }
                """, "Chain", """
                import seal.util.Iterator;
                class Chain implements(Iterable<int>)
                {
                    Node head;
                    public void push(int v) { head = new Node(v, head); }
                    public Iterator<int> iterator() { return new Walk(head); }
                }
                """, "Main", """
                import seal.util.List;
                struct Main
                {
                    static array<int><<3>> three() { return [7, 8, 9]; }
                    static int main()
                    {
                        array<int><<4>> xs = [1, 2, 3, 4];
                        int sum = 0;
                        for (int x : xs) { sum += x; }
                        System.stdout.print(sum); System.stdout.print(" ");
                        for (int x : three()) { System.stdout.print(x); }
                        for (int<<8>> x : [5, 6]) { System.stdout.print((int)x); }
                        System.stdout.print(" ");
                        for (char c : "hey") { System.stdout.print(Char.toUpper(c)); }
                        List<string> names = new List();
                        names.add("ann"); names.add("bob"); names.add("cy");
                        for (string n : names) {
                            if (n == "bob") { continue; }
                            System.stdout.print(" " + n);
                        }
                        Chain c = new Chain();
                        c.push(1); c.push(2); c.push(3);
                        for (int v : c) {
                            if (v == 1) { break; }
                            System.stdout.print(v);
                        }
                        array<int><<2, 3>> grid = [[1, 2, 3], [4, 5, 6]];
                        for (array<int><<3>> row : grid) { for (int v : row) { System.stdout.print(v); } }
                        return 0;
                    }
                }
                """), "10 78956 HEY ann cy32123456");
        output("++ and --", files("Main", """
                struct Main
                {
                    static class Counter
                    {
                        public int hits;
                        public static int made;
                    }

                    int n;
                    void bump() { n++; ++n; }

                    static <T> T twice(T x) { x++; x++; return x; }

                    static void main()
                    {
                        int i = 5;
                        int a = i++;
                        int b = ++i;
                        int c = i--;
                        int d = --i;
                        System.stdout.println(a + " " + b + " " + c + " " + d + " " + i);
                        int m = 3;
                        while (m-- > 0) { System.stdout.print(m); }
                        System.stdout.println(" " + m);
                        for (char x = 'a'; x <= 'e'; x++) { System.stdout.print(x); }
                        char z = 'z';
                        System.stdout.println(" " + z-- + z);
                        uint<<8>> u = 255;
                        u++;
                        int<<8>> s = 127;
                        s++;
                        System.stdout.println(u + " " + s);
                        double dd = 0.5;
                        float ff = 2.5;
                        fixed<<16, 16>> fx = 1.25;
                        dd++;
                        fx++;
                        System.stdout.println(dd + " " + ff-- + " " + ff + " " + fx);
                        array<int><<4>> inline;
                        int j = 0;
                        inline[j++] = 10;
                        inline[j++] = 20;
                        inline[1]++;
                        System.stdout.println(inline[0] + " " + inline[1] + " " + j);
                        array<int> counts = new array<int>(3);
                        counts[2]++;
                        counts[2]++;
                        --counts[0];
                        array<char> cs = new array<char>(1);
                        cs[0] = 'x';
                        cs[0]++;
                        System.stdout.println(counts[0] + " " + counts[2] + " " + cs[0]);
                        Counter cnt = new Counter();
                        cnt.hits++;
                        ++cnt.hits;
                        Counter.made--;
                        Main me = new Main();
                        me.bump();
                        me.n++;
                        System.stdout.println(cnt.hits + " " + Counter.made + " " + me.n);
                        int t = 0;
                        int r = t++ + t++;
                        int w = 1;
                        int sum = w++ + ++w;
                        System.stdout.println(r + " " + t + " " + sum + " " + w);
                        System.stdout.println(twice(1.5) + " " + twice('a') + " " + twice(40));
                    }
                }
                """), """
                5 7 7 5 5
                210 -1
                abcde zy
                0 -128
                1.5 2.5 1.5 2.25
                10 21 2
                -1 2 y
                2 -1 3
                1 2 4 3
                3.5 c 42
                """);
        output("a char is an int wherever one is wanted, as in Java", files("Main", """
                struct Main
                {
                    static class Box
                    {
                        public string made;
                        public new(char c) { made = "char"; }
                        public new(int n) { made = "int"; }
                    }

                    static int twice(int n) { return n * 2; }
                    static string kind(char c) { return "char"; }
                    static string kind(int n) { return "int"; }
                    static string pair(char c, int n) { return "char,int"; }
                    static string pair(int a, int b) { return "int,int"; }
                    static <T> T second(T a, T b) { return b; }

                    static void main()
                    {
                        char c = 'A';
                        int i = c;
                        System.stdout.println(i + " " + twice(c) + " " + kind(c) + " " + kind(i) + " " + pair(c, 1));
                        System.stdout.println(new Box(c).made + " " + new Box(i).made + " " + second(1, c));
                        string text = "hello";
                        array<int> freq = new array<int>(128);
                        for (int k = 0; k < text.length(); k++) { freq[text.charAt(k)]++; }
                        System.stdout.println(freq['l'] + " " + freq['h'] + " " + (true ? c : i) + " " + (true ? c : 5));
                        System.stdout.println(c);
                    }
                }
                """), """
                65 130 char int char,int
                char int A
                2 1 65 A
                A
                """);
        output("++ on elements reached through [] and []=", files("Main", """
                struct Main
                {
                    static int calls;
                    static int at(int i) { calls++; return i; }

                    static void main()
                    {
                        array<int> counts = new array<int>(4);
                        int v = counts[1]++;
                        int w = ++counts[1];
                        System.stdout.println(v + " " + w + " " + counts[1] + " " + (counts[1]-- + 1));
                        int k = 0;
                        counts[k++]++;
                        counts[k++] += 5;
                        System.stdout.println(counts[0] + " " + counts[1] + " " + counts[2] + " " + k);
                        counts[at(3)]++;
                        counts[at(3)] += 2;
                        int x = counts[at(3)]--;
                        System.stdout.println(counts[3] + " " + x + " " + calls);
                        array<char> cs = new array<char>(1);
                        cs[0] = 'a';
                        char c1 = cs[0]++;
                        char c2 = ++cs[0];
                        System.stdout.println(c1 + " " + c2 + " " + cs[0]);
                    }
                }
                """), """
                0 2 2 3
                1 6 0 2
                2 3 3
                a c c
                """);
        output("Math on every kind of number", files("Main", """
                struct Main
                {
                    static void main()
                    {
                        int i = -7;
                        int<<64>> big = -9000000000;
                        int<<8>> small = -3;
                        uint u = 12;
                        uint<<64>> ubig = 18000000000000000000;
                        float f = -2.5;
                        double d = 2.25;
                        fixed<<16, 16>> fx = -2.75;
                        ufixed<<8, 8>> ufx = 3.5;
                        char c = 'a';

                        System.stdout.println(Math.abs(i) + " " + Math.abs(big) + " " + Math.abs(small) + " " + Math.abs(u) + " " + Math.abs(f) + " " + Math.abs(d) + " " + Math.abs(fx) + " " + Math.abs(ufx) + " " + Math.abs(c));
                        System.stdout.println(Math.max(i, 3) + " " + Math.max(3, i) + " " + Math.min(big, 0) + " " + Math.min(0, big) + " " + Math.max(small, 5) + " " + Math.max(u, 20) + " " + Math.max(ubig, 1));
                        System.stdout.println(Math.max(f, 1) + " " + Math.min(d, 0.5) + " " + Math.max(fx, -3) + " " + Math.min(ufx, 1.25) + " " + Math.max(1.5, 2) + " " + Math.max(2, 3) + " " + Math.max(c, 'z'));
                        System.stdout.println(Math.clamp(i, 0, 10) + " " + Math.clamp(15, 0, 10) + " " + Math.clamp(fx, -1, 1) + " " + Math.clamp(f, 0, 1) + " " + Math.clamp(u, 1, 5));
                        System.stdout.println(Math.floor(fx) + " " + Math.ceil(fx) + " " + Math.round(fx) + " " + Math.floor(ufx) + " " + Math.ceil(ufx) + " " + Math.round(ufx));
                        fixed<<16, 16>> half = 2.5;
                        fixed<<16, 16>> nhalf = -2.5;
                        System.stdout.println(Math.round(half) + " " + Math.round(nhalf) + " " + Math.floor(f) + " " + Math.ceil(f) + " " + Math.round(f) + " " + Math.floor(i) + " " + Math.round(d));
                        System.stdout.println(Math.sqrt(2) + " " + Math.sqrt(u) + " " + Math.sqrt(f * f) + " " + Math.sqrt(d) + " " + Math.sqrt(ufx) + " " + Math.pow(2, 10) + " " + Math.pow(big, 2));
                        fixed<<16, 16>> angle = 0.5;
                        System.stdout.println(Math.sin(angle) + " " + Math.cos(angle) + " " + Math.hypot(3, 4) + " " + Math.hypot(fx, fx) + " " + Math.atan2(1, 1) + " " + Math.exp(1) + " " + Math.log(Math.E));
                    }
                }
                """), """
                7 9000000000 3 12 2.5 2.25 2.75 3.5 97
                3 3 -9000000000 -9000000000 5 20 18000000000000000000
                1.0 0.5 -2.75 1.25 2.0 3 122
                0 10 -1.0 0.0 5
                -3.0 -2.0 -3.0 3.0 4.0 4.0
                3.0 -2.0 -3.0 -2.0 -2.0 -7 2.0
                1.4142135623730951 3.4641016151377544 2.5 1.5 1.87109375 1024.0 8.1E19
                0.47943115234375 0.8775787353515625 5.0 3.8890838623046875 0.7853981633974483 2.718281828459045 1.0
                """);
        output("Math in generic code", files("Main", """
                struct Main
                {
                    static <T> T largest(array<T> xs)
                    {
                        T best = xs[0];
                        for (T x : xs) { best = Math.max(best, x); }
                        return best;
                    }

                    static void main()
                    {
                        array<int> ints = new array<int>(3);
                        ints[1] = 9;
                        array<double> ds = new array<double>(2);
                        ds[0] = -1.5;
                        ds[1] = -0.5;
                        array<fixed<<8, 8>>> fs = new array<fixed<<8, 8>>>(2);
                        fs[0] = 1.25;
                        System.stdout.println(largest(ints) + " " + largest(ds) + " " + largest(fs));
                    }
                }
                """), "9 -0.5 1.25\n");
        output("numbers widen as in Java", files("Main", """
                struct Main
                {
                    static double half(double x) { return x / 2; }
                    static int<<64>> twice(int<<64>> x) { return x * 2; }
                    static double asDouble(int x) { return x; }
                    static fixed<<16, 16>> scaled(fixed<<16, 16>> x) { return x; }

                    static void main()
                    {
                        int i = 7;
                        int<<8>> b = -3;
                        uint<<8>> ub = 255;
                        uint u = 4000000000;
                        float f = 1.5;
                        char c = 'A';
                        double d = i;
                        int<<64>> l = b;
                        int<<64>> lu = u;
                        System.stdout.println(d + " " + l + " " + lu + " " + half(i) + " " + twice(ub) + " " + asDouble(b));
                        System.stdout.println(i + f);
                        System.stdout.println(i / 2.0);
                        System.stdout.println(b + 1000);
                        System.stdout.println(l * i);
                        System.stdout.println((i < d) + " " + (c < -1) + " " + (b < ub) + " " + (u > i) + " " + (f == 1.5));
                        bit flag = true;
                        double pick = flag ? i : 2.5;
                        int<<64>> pick2 = !flag ? l : i;
                        System.stdout.println(pick + " " + pick2);
                        fixed<<16, 16>> fx = b;
                        fixed<<8, 8>> small = 1.25;
                        fixed<<16, 16>> wide = small;
                        ufixed<<8, 8>> uf = 2.5;
                        fixed<<16, 16>> fromU = uf;
                        System.stdout.println(fx + " " + wide + " " + fromU + " " + scaled(b) + " " + (fx + small) + " " + (fx * 2));
                        double fromFixed = small;
                        float ff = small;
                        System.stdout.println(fromFixed + " " + ff + " " + (small + d));
                        double acc = 0;
                        acc += i;
                        acc += f;
                        int<<64>> total = 0;
                        total += i;
                        total += b;
                        System.stdout.println(acc + " " + total);
                        array<double> ds = new array<double>(2);
                        ds[0] = i;
                        ds[1] = b;
                        array<int<<64>>><<3>> ls = [i, b, 5];
                        System.stdout.println(ds[0] + " " + ds[1] + " " + ls[0] + " " + ls[1] + " " + ls[2]);
                        double cd = c;
                        int<<64>> cl = c;
                        System.stdout.println(cd + " " + cl + " " + (c + 0.5));
                        System.stdout.println(Math.sqrt(i) + " " + Math.max(f, d) + " " + Math.pow(2.0, i));
                    }
                }
                """), """
                7.0 -3 4000000000 3.5 510 -3.0
                8.5
                3.5
                997
                -21
                false false true true true
                7.0 7
                -3.0 1.25 2.5 -3.0 -1.75 -6.0
                1.25 1.25 8.25
                8.5 4
                7.0 -3.0 7 -3 5
                65.0 65 65.5
                2.6457513110645907 7.0 128.0
                """);
        output("widening in lambdas and for-each", files("Main", """
                import seal.util.function.Function;
                import seal.util.function.Supplier;
                struct Main
                {
                    static void main()
                    {
                        Function<int, double> half = x -> x / 2.0;
                        Function<int, double> same = x -> x;
                        Supplier<int<<64>>> big = () -> 7;
                        array<int> xs = new array<int>(3);
                        xs[0] = 1; xs[1] = 2; xs[2] = 3;
                        double sum = 0;
                        for (double v : xs) { sum += v / 2; }
                        int<<64>> total = 0;
                        for (int<<64>> v : xs) { total += v * 1000000000; }
                        System.stdout.println(half.apply(3) + " " + same.apply(4) + " " + big.get() + " " + sum + " " + total);
                    }
                }
                """), "1.5 4.0 7 3.0 6000000000\n");
        output("compound assignments narrow back, as Java's do", files("Main", """
                struct Main
                {
                    static void main()
                    {
                        int i = 10;
                        i += 2.5;
                        int j = -1;
                        j += 0.5;
                        int k = 7;
                        k *= 1.5;
                        int<<8>> b = 100;
                        b += 1000;
                        char c = 'a';
                        c += 1;
                        c += k - 8;
                        int<<64>> big = 5000000000;
                        int n = 1;
                        n += big;
                        uint u = 5;
                        u += j - 1;
                        float f = 1.5;
                        double d = 0.25;
                        f += d;
                        array<int<<8>>> xs = new array<int<<8>>>(2);
                        xs[0] = 100;
                        xs[0] += 100;
                        xs[1] += 2.7;
                        int<<16>> word = 0;
                        word[0..4] += 20;
                        System.stdout.println(i + " " + j + " " + k + " " + b + " " + c + " " + n + " " + u + " " + f);
                        System.stdout.println(xs[0] + " " + xs[1] + " " + word);
                    }
                }
                """), "12 0 10 76 d 705032705 4 1.75\n-56 2 4\n");
        output("mixed signs meet in int, or else int<<64>>", files("Main", """
                struct Main
                {
                    static void main()
                    {
                        int i = -5;
                        uint u = 4000000000;
                        int<<64>> m = Math.max(i, u);
                        int<<64>> n = Math.min(i, u);
                        int<<8>> b = -2;
                        uint<<8>> ub = 200;
                        System.stdout.println(m + " " + n + " " + Math.max(b, ub) + " " + (i + u) + " " + (b * ub));
                    }
                }
                """), "4000000000 -5 200 3999999995 -400\n");
        output("Math widens its arguments as Java does", files("Main", """
                struct Main
                {
                    static void main()
                    {
                        int i = 3;
                        int<<64>> big = 5000000000;
                        int<<8>> small = 1;
                        double d = 2.5;
                        int<<64>> a = Math.max(i, big);
                        int b = Math.max(small, 1000);
                        double c = Math.max(i, d);
                        double p = Math.pow(d, i);
                        System.stdout.println(a + " " + b + " " + c + " " + p);
                    }
                }
                """), "5000000000 1000 3.0 15.625\n");
        compileError("ceil on a format that can't hold 1", files("Main", """
                struct Main
                {
                    static void main() { ufixed<<0, 8>> f = 0.5; Math.ceil(f); }
                }
                """), "in Math.ceil[M=8, N=0]: integer 1 does not fit in ufixed<<0, 8>>");
        compileError("++ in generic code is checked for each type", files("Main", """
                struct Main
                {
                    static <T> T next(T x) { return ++x; }
                    static void main() { next("a"); }
                }
                """), "++ needs a number or a char, not string");
        compileError("an enum's main() must be static", files("Main", """
                enum Main
                {
                    A, B;
                    void main() { }
                }
                """), "but Main is an enum, whose only values are its constants; make main() static");
        output("enums and switch", files("Color", "enum Color { RED, GREEN, BLUE }", "Planet", """
                enum Planet
                {
                    MERCURY(3, 24),
                    JUPITER(18980, 699);

                    int mass;
                    int radius;
                    new(int m, int r) { mass = m; radius = r; }
                    public bit isBig() { return mass > 100; }
                }
                """, "Main", """
                struct Main
                {
                    static string describe(Color c)
                    {
                        switch (c) {
                            case RED -> { return "warm"; }
                            case Color.BLUE -> { return "cool"; }
                            default -> { return "plain"; }
                        }
                    }
                    static int main()
                    {
                        for (Color c : Color.values()) { System.stdout.print(describe(c) + " "); }
                        Color d;
                        System.stdout.print(d.name() + Color.valueOf("BLUE").ordinal() + " ");
                        for (Planet p : Planet.values()) { System.stdout.print(p.name() + p.isBig() + " "); }
                        int total = 0;
                        for (int i = 0; i < 6; i += 1) {
                            switch (i) {
                                case 0:
                                case 1:
                                    total += 1;
                                case 2:
                                    total += 10;
                                    break;
                                case 3:
                                    continue;
                                case 4:
                                    total += 100;
                                    break;
                                default:
                                    total += 1000;
                            }
                            total += 5;
                        }
                        System.stdout.print(total);
                        switch ("stop") { case "go" -> System.stdout.print(" go"); case "stop" -> System.stdout.print(" stop"); }
                        switch ('b') { case 'a', 'b' -> System.stdout.print(" ab"); default -> System.stdout.print(" other"); }
                        return 0;
                    }
                }
                """), "warm plain cool RED2 MERCURYfalse JUPITERtrue 1157 stop ab");
        output("lambdas", files("Counter", """
                import seal.util.function.Supplier;
                class Counter
                {
                    int count;
                    public Runnable incrementer() { return () -> count += 1; }
                    public int get() { return count; }
                    public Supplier<int> peek() { return () -> get() * 10; }
                }
                """, "Main", """
                import seal.util.Comparator;
                import seal.util.List;
                import seal.util.function.BiFunction;
                import seal.util.function.Function;
                import seal.util.function.Predicate;
                import seal.util.function.Supplier;
                struct Main
                {
                    static int applyTwice(Function<int, int> f, int x) { return f.apply(f.apply(x)); }
                    static int main()
                    {
                        Function<int, int> twice = x -> x * 2;
                        int base = 100;
                        Function<int, int> addBase = x -> x + base;
                        base = 0;
                        System.stdout.print("" + twice.apply(21) + " " + addBase.apply(5) + " " + applyTwice(x -> x + 3, 10) + " ");
                        BiFunction<int, int, int> add = (a, b) -> a + b;
                        Predicate<string> longWord = s -> s.length() > 3;
                        System.stdout.print("" + add.apply(2, 3) + longWord.test("hi") + longWord.test("hello") + " ");
                        Counter c = new Counter();
                        Runnable inc = c.incrementer();
                        inc.run(); inc.run();
                        System.stdout.print("" + c.get() + c.peek().get() + " ");
                        Supplier<Function<int, int>> maker = () -> y -> y * base + 1;
                        Comparator<int> backwards = (int a, int b) -> {
                            if (a > b) { return -1; }
                            return 1;
                        };
                        System.stdout.print("" + maker.get().apply(7) + backwards.compare(1, 2) + " ");
                        List<string> names = new List();
                        names.add("carol"); names.add("al"); names.add("bob"); names.add("ed");
                        names.sort((a, b) -> a.length() - b.length());
                        names.removeIf(n -> n == "bob");
                        names.forEach(n -> System.stdout.print(n));
                        return 0;
                    }
                }
                """), "42 105 16 5falsetrue 220 11 aledcarol");
        compileError("a lambda needs a functional interface", files("Main", main("int f = x -> x;")),
                "a lambda needs a functional interface type");
        compileError("a lambda with the wrong number of parameters", files("Main", main("Function<int, int> f = (a, b) -> a;")),
                "Function.apply takes 1 parameter(s), but the lambda has 2");
        compileError("a lambda can't assign what it captured", files("Main", main("int n = 0; Runnable r = () -> n += 1;")),
                "a lambda can't assign n: it holds a copy");
        compileError("a lambda result of the wrong type", files("Main", main("Function<int, int> f = x -> \"no\";")),
                "lambda result: expected int, found string literal");
        compileError("a block lambda must return", files("Main", main("Function<int, int> f = x -> { if (x > 1) { return 1; } };")),
                "the lambda can reach its end without returning a int");
        compileError("a lambda in a struct method can't use its fields", files("P", """
                import seal.util.function.Supplier;
                struct P { int v; public Supplier<int> get() { return () -> v; } }
                """, "Main", main("")), "a lambda in a struct method can't use its fields or methods");
        compileError("an explicit parameter type must match", files("Main", main("Function<int, int> f = (bit b) -> 1;")),
                "lambda parameter b is bit, but Function.apply passes int");
        String res = """
                unique Res
                {
                    public int id;
                    public new(int i) { id = i; System.stdout.print("+" + i + " "); }
                    delete() { System.stdout.print("-" + id + " "); }
                }
                """;
        String box = """
                class Box
                {
                    public int v;
                    public new(int n) { v = n; }
                }
                """;
        output("an exception unwinds frames, destroying and releasing what they hold", files("Res", res, "Box", box,
                "BadInput", """
                class BadInput extends(IllegalArgumentException)
                {
                    public int code;
                    public new(string m, int c) { super(m); code = c; }
                }
                """, "Main", """
                struct Main
                {
                    static int depth(int n)
                    {
                        Res r = new Res(n);
                        Box b = new Box(n);
                        if (n == 0) { throw new BadInput("bottom", 42); }
                        return depth(n - 1);
                    }
                    static int main()
                    {
                        int base = Probe.live();
                        try {
                            depth(2);
                        } catch (BadInput e) {
                            System.stdout.print("caught " + e.getMessage() + " " + e.code);
                        }
                        System.stdout.print(", " + (Probe.live() - base) + " left");
                        return 0;
                    }
                }
                """), "+2 +1 +0 -0 -1 -2 caught bottom 42, 0 left");
        output("finally runs on every way out", files("Main", """
                struct Main
                {
                    static int withFinally(int x)
                    {
                        try {
                            if (x > 0) { return x; }
                            throw new IllegalStateException("x is " + x);
                        } finally {
                            System.stdout.print("[f" + x + "] ");
                        }
                    }
                    static int main()
                    {
                        System.stdout.print("" + withFinally(5) + " ");
                        try { withFinally(-1); } catch (RuntimeException e) { System.stdout.print(e.getMessage() + " "); }
                        for (int i = 0; i < 4; i += 1) {
                            try {
                                if (i == 1) { continue; }
                                if (i == 3) { break; }
                                System.stdout.print("b" + i + " ");
                            } finally {
                                System.stdout.print("f" + i + " ");
                            }
                        }
                        try {
                            try { Integer.parse("zz"); }
                            catch (IllegalStateException e) { System.stdout.print("wrong "); }
                            finally { System.stdout.print("inner "); }
                        } catch (NumberFormatException e) {
                            System.stdout.print("outer " + e.getMessage());
                        }
                        try {
                            try { throw new Exception("a"); }
                            catch (Exception e) { throw new RuntimeException("from catch"); }
                            finally { System.stdout.print(" fin"); }
                        } catch (RuntimeException e) {
                            System.stdout.print(" " + e.getMessage());
                        }
                        return 0;
                    }
                }
                """), "[f5] 5 [f-1] x is -1 b0 f0 f1 b2 f2 f3 inner outer not a decimal integer: \"zz\" fin from catch");
        trap("an uncaught exception stops the program with its class and message", files("Main", main("""
                List<int> xs = new List();
                xs.get(2);
                """)), "uncaught IndexOutOfBoundsException: List index 2 out of range for size 0");
        compileError("throw needs an exception", files("Main", main("throw 5;")),
                "throw needs an exception (an object of a class extending Exception), not integer constant 5");
        compileError("catch needs an exception class", files("Box", box, "Main", main("try { } catch (Box b) { }")),
                "catch takes a class extending Exception, not Box");
        compileError("a catch the one above already covers", files("Main", main(
                "try { } catch (RuntimeException e) { } catch (IllegalStateException e) { }")),
                "IllegalStateException is already caught by the catch for RuntimeException above");
        compileError("try needs catch or finally", files("Main", main("try { }")), "try needs a catch or a finally");
        compileError("the exception variable is scoped to its catch", files("Main", main(
                "try { } catch (Exception e) { } e.getMessage();")), "unknown name e");
        output("the conditional operator", files("Drawable", "interface Drawable { void draw(); }",
                "Named", "interface Named { string name(); }", "Thing", "interface Thing extends(Drawable, Named) { int id(); }",
                "Shape", """
                abstract class Shape implements(Drawable)
                {
                    public abstract int area();
                }
                """, "Square", """
                class Square extends(Shape)
                {
                    public int side;
                    public new(int s) { side = s; }
                    public int area() { return side * side; }
                    public void draw() { }
                }
                """, "Circle", """
                class Circle extends(Shape)
                {
                    public int r;
                    public new(int radius) { r = radius; }
                    public int area() { return 3 * r * r; }
                    public void draw() { }
                }
                """, "Main", """
                import seal.util.function.Function;
                struct Main
                {
                    static int sign(int x) { return x > 0 ? 1 : x < 0 ? -1 : 0; }
                    static int main()
                    {
                        System.stdout.print(sign(5) + " " + sign(-3) + " " + sign(0) + " ");
                        int<<8>> small = true ? 100 : 7;
                        double d = false ? 1 : 2.5;
                        System.stdout.print((int)small + " " + d + " ");
                        string bare = "x";
                        string w = 3 > 2 ? "yes" : "no";
                        System.stdout.print(w + (1 > 2 ? "big" : "small") + (true ? bare : "lit") + " ");
                        bit pick = true;
                        System.stdout.print((pick ? new Circle(1) : new Square(2)).area() + " ");
                        for (int i = 0; i < 3; i += 1) { System.stdout.print(i % 2 == 0 ? "e" + i : bare + i); }
                        Function<int, int> f = pick ? x -> x + 1 : x -> x - 1;
                        System.stdout.print(" " + f.apply(10));
                        return 0;
                    }
                }
                """), "1 -1 0 100 2.5 yessmallx 3 e0x1e2 11");
        compileError("?: sides without a common type", files("Main", main("bit c = true; int x = 1; string s = \"a\"; System.stdout.print(c ? x : s);")),
                "the two sides of ?: have different types: int and string");
        compileError("?: needs a bit condition", files("Main", main("int x = 1 ? 2 : 3;")), "condition must be a bit, not integer constant 1");
        output("char is its own type", files("Main", """
                struct Main
                {
                    static void show(char c) { System.stdout.print("char "); }
                    static void show(int n) { System.stdout.print("int "); }
                    static int main()
                    {
                        char c = 'q';
                        show(c); show('q'); show(113);
                        System.stdout.print(c);
                        System.stdout.print('!');
                        System.stdout.print(" " + c + 'r' + " ");
                        int offset = c - 'a';
                        char next = (char)(c + 1);
                        int code = 'A';
                        uint<<16>> raw = 'B';
                        char fromCode = 67;
                        System.stdout.print(offset + " " + next + " " + code + " " + (int)raw + " " + fromCode + " ");
                        string s = "hello";
                        char first = s.charAt(0);
                        System.stdout.print(first == 'h');
                        array<char><<3>> cs = ['x', 'y', 'z'];
                        System.stdout.print(" " + cs[2] + (c > 'a') + " ");
                        switch (c) { case 'q' -> System.stdout.print("q"); default -> { } }
                        return 0;
                    }
                }
                """), "char char int q! qr 16 r 65 66 C true ztrue q");
        output("a uint<<16>> narrows to a char where stored", files("Main", main("uint<<16>> u = 66; char c = u; System.stdout.print(c);")),
                "B");
        output("arithmetic on a char gives an int, which narrows back to a char", files("Main", main("""
                char c = 'a';
                char d = c + 1;
                pr(c + 1); System.stdout.print(d);
                """)), "98 b");
        output("a char next to a narrower int is an int", files("Main", main("char c = 'a'; int<<8>> b = -1; pr(c + b);")),
                "96 ");
        output("this", files("Point", """
                struct Point
                {
                    public int x;
                    public int y;
                    public new() { }
                    public new(int x, int y) { this.x = x; this.y = y; }
                    public Point moved(int dx) { Point p = this; p.x += dx; return p; }
                    public void shift(int d) { this.x += d; this.shift2(d); }
                    void shift2(int d) { this.y += d; }
                }
                """, "Node", """
                import seal.util.function.Supplier;
                class Node
                {
                    public int value;
                    public Node next;
                    public new(int value, Node next) { this.value = value; this.next = next; }
                    public Node prepend(int v) { return new Node(v, this); }
                    public bit same(Node other) { return this == other; }
                    public Supplier<int> getter() { return () -> this.value * 2; }
                }
                """, "Main", """
                import seal.util.function.Supplier;
                struct Main
                {
                    static int main()
                    {
                        Point p = new Point(1, 2);
                        Point q = p.moved(10);
                        p.shift(5);
                        System.stdout.print(p.x + " " + p.y + " " + q.x + " " + q.y + " ");
                        Node n = new Node(1, null).prepend(2);
                        System.stdout.print(n.value + " " + n.next.value + " " + n.same(n) + n.same(n.next) + " ");
                        Supplier<int> g = n.getter();
                        n = null;
                        System.stdout.print(g.get() + " ");
                        return 0;
                    }
                }
                """), "6 7 11 2 2 1 truefalse 4 ");
        compileError("no this in static code", files("Main", main("System.stdout.print(this == null);")),
                "this is only available in the methods and constructors of an object, not static code");
        compileError("this can't be assigned", files("P", "struct P { int x; public void f(P o) { this = o; } }", "Main", main("")),
                "cannot assign to this");
        compileError("this of a unique can't be copied", files("U", "unique U { public void f() { U other = this; } }", "Main", main("")),
                "U is unique and can't be copied");
        output("comparing with null never calls an overloaded ==", files("Name", """
                class Name
                {
                    public string text;
                    public new(string t) { text = t; }
                    @overload(Operator.EQUALS)
                    public bit same(Name other) { return other != null && text == other.text; }
                }
                """, "Main", main("""
                Name a = new Name("x");
                Name none = null;
                System.stdout.print("" + (a == new Name("x")) + (a != new Name("y")) + (a == null) + (null != a) + (none == null) + (a == none));
                """)), "truetruefalsetruetruefalse");
        output("System.rawAssembly does nothing in the VM", files("Main", main("""
                System.stdout.print("a");
                System.rawAssembly("mov rax, 60\\n" + "syscall");
                System.stdout.print("b");
                """)), "ab");
        compileError("System.rawAssembly needs a literal", files("Main", main("""
                string op = "nop";
                System.rawAssembly(op);
                """)), "System.rawAssembly needs its code as a string literal");
        compileError("a member T doesn't have is reported where T is chosen", files("Box", """
                class Box<T>
                {
                    public T item;
                    public new(T x) { item = x; }
                    public int size() { return item.length(); }
                }
                """, "Main", main("""
                Box<string> ok = new Box<string>("abc");
                pr(ok.size());
                Box<int> bad = new Box<int>(5);
                pr(bad.size());
                """)), "in Box<int>.size: int has no methods");
        output("generic methods", files("Box", """
                import seal.util.function.Function;
                class Box<T>
                {
                    public T value;
                    public new(T v) { value = v; }
                    public <R> Box<R> map(Function<T, R> f) { return new Box<R>(f.apply(value)); }
                }
                """, "Main", """
                import seal.util.List;
                import seal.util.function.Function;
                struct Main
                {
                    static <T> T largest(List<T> xs)
                    {
                        T best = xs.get(0);
                        for (T x : xs) { if (best < x) { best = x; } }
                        return best;
                    }
                    static <T> List<T> listOf(T a, T b, T c)
                    {
                        List<T> out = new List();
                        out.add(a); out.add(b); out.add(c);
                        return out;
                    }
                    static <T, R> List<R> mapAll(List<T> xs, Function<T, R> f)
                    {
                        List<R> out = new List();
                        for (T x : xs) { out.add(f.apply(x)); }
                        return out;
                    }
                    static <T> List<T> empty() { return new List(); }
                    static int main()
                    {
                        List<int> nums = listOf(3, 9, 4);
                        System.stdout.print(largest(nums) + " " + largest(listOf("pear", "apple", "zebra")) + " "
                                + largest(listOf(1.5, 0.5, 2.5)) + " ");
                        List<string> words = mapAll(nums, n -> "n" + n);
                        List<int> lengths = mapAll(words, w -> w.length());
                        List<bit> bigs = mapAll(nums, n -> {
                            if (n > 5) { return true; }
                            return false;
                        });
                        System.stdout.print(words.get(2) + " " + lengths.get(0) + " " + bigs.get(1) + " ");
                        System.stdout.print(Main.<string>empty().size() + " ");
                        Box<int> b = new Box<int>(20);
                        System.stdout.print(b.map(x -> "#" + x).value + " " + b.map(x -> x * 2).map(x -> x + 1).value);
                        return 0;
                    }
                }
                """), "9 zebra 2.5 n4 2 true 0 #20 41");
        compileError("a type parameter nothing gives", files("Main", """
                import seal.util.List;
                struct Main { static <T> List<T> empty() { return new List(); } static int main() { List<int> x = empty(); return 0; } }
                """), "can't tell T for empty from its arguments; give the types, as in x.<T>empty(...)");
        compileError("the wrong number of type arguments", files("Main", """
                struct Main { static <T> void f(T x) { } static int main() { Main.<int, int>f(1); return 0; } }
                """), "f takes 1 type argument(s), not 2");
        compileError("an operator T lacks", files("Main", """
                struct Main
                {
                    static <T> bit less(T a, T b) { return a < b; }
                    static int main() { bit x = less(new Object(), new Object()); return 0; }
                }
                """), "in Main.less[T=handle<Object>]: Object does not define operator <");
        compileError("no abstract generic methods", files("S", "abstract class S { public abstract <T> T f(T x); }", "Main", main("")),
                "a generic method can't be abstract or in an interface");
        compileError("a method's type parameter can't hide its class's", files("Box", "class Box<T> { public <T> void f(T x) { } }",
                "Main", main("")), "type parameter T of f hides Box's own T");
        output("arrays", files("Grid", """
                class Grid
                {
                    public array<int> cells;
                    public new(int n) { cells = new array<int>(n); }
                    public int total() { int t = 0; for (int c : cells) { t += c; } return t; }
                }
                """, "Main", """
                struct Main
                {
                    static void fill(array<int> xs, int v) { for (int i = 0; i < xs.length(); i += 1) { xs[i] = v + i; } }
                    static int sum(array<int> xs) { int t = 0; for (int x : xs) { t += x; } return t; }
                    static array<int> squares(int n)
                    {
                        array<int> out = new array<int>(n);
                        for (int i = 0; i < n; i += 1) { out[i] = i * i; }
                        return out;
                    }
                    static <T> int count(array<T> xs, T v) { int c = 0; for (T x : xs) { if (x == v) { c += 1; } } return c; }
                    static int main()
                    {
                        array<int> xs = new array<int>(5);
                        fill(xs, 10);
                        xs[2] += 100;
                        xs[3] = xs[2] * 2;
                        System.stdout.print(xs[0] + " " + xs[4] + " " + xs[2] + " " + xs[3] + " " + sum(xs) + " ");
                        array<int> sq = squares(6);
                        array<int> lit = [5, 3, 9, 1];
                        lit.sort();
                        System.stdout.print(sum(sq) + " " + lit[0] + lit[3] + " " + lit.indexOf(9) + " ");
                        array<string> words = ["pear", "fig", "apple"];
                        words.sort((a, b) -> a.length() - b.length());
                        array<string> more = words.copy();
                        more[0] = "kiwi";
                        System.stdout.print(words[0] + " " + more[0] + " " + count(words, "fig") + " " + sum([1, 2, 3]) + " ");
                        array<char><<3>> three = ['a', 'b', 'c'];
                        three[1] = 'z';
                        array<string> none;
                        Grid g = new Grid(4);
                        g.cells[2] = 7;
                        g.cells[3] += 1;
                        System.stdout.print("" + three[1] + three.length() + " " + none.length() + " " + g.total() + " ");
                        try { int bad = xs[10]; } catch (IndexOutOfBoundsException e) { System.stdout.print(e.getMessage()); }
                        return 0;
                    }
                }
                """), "10 14 112 224 371 55 19 3 fig kiwi 1 6 z3 0 8 index 10 out of range for an array of length 5");
        output("a class can define [] and []=", files("Ring", """
                class Ring
                {
                    array<int> slots;
                    public new(int n) { slots = new array<int>(n); }
                    @overload(Operator.INDEX) public int at(int i) { return slots[i % slots.length()]; }
                    @overload(Operator.INDEX_SET) public void put(int i, int v) { slots[i % slots.length()] = v; }
                }
                """, "Main", main("""
                Ring r = new Ring(3);
                r[4] = 9;
                r[7] += 1;
                pr(r[1]); pr(r[10]);
                """)), "10 10 ");
        compileError("an array can't be copied", files("Main", main("array<int> a = [1]; array<int> b = a;")),
                "array<int> is unique and can't be copied; for a second array, use .copy()");
        compileError("a List can't hold arrays", files("Main", main("List<array<int>> l;")),
                "a List copies its elements in and out, so it can't hold array<int>, a unique");
        compileError("no arrays of arrays", files("Main", main("array<array<int>> g;")),
                "an array copies its elements in and out, so it can't hold array<int>, a unique");
        output("Operator is an ordinary enum too", files("Main", main("""
                System.stdout.print(Operator.ADD.symbol() + Operator.INDEX_SET.symbol() + " " + Operator.values().length() + " " + Operator.valueOf("NEGATE").ordinal());
                """)), "+[]= 31 2");
        output("fixed arrays: values with methods, any length, nesting, exceptions", files("Holder", """
                class Holder { public array<int><<4>> nums = [4, 3, 2, 1]; }
                """, "Main", """
                struct Main
                {
                    static <T> int countIn(array<T><<int N>> xs, T v)
                    {
                        int c = 0;
                        for (int i = 0; i < N; i += 1) { if (xs[i] == v) { c += 1; } }
                        return c;
                    }
                    static int total(array<int><<int N>> xs) { int t = 0; for (int x : xs) { t += x; } return t; }
                    static int main()
                    {
                        array<int><<5>> a = [5, 1, 4, 2, 3];
                        array<int><<5>> b = a;
                        b.sort();
                        System.stdout.print(a[0] + " " + b[0] + " " + b.length() + " " + a.indexOf(4) + " " + b.contains(9) + " ");
                        System.stdout.print(total(a) + " " + total([1, 2]) + " " + countIn(["x", "y", "x"], "x") + " ");
                        Holder h = new Holder();
                        h.nums.sort();
                        array<array<int><<3>>> rows = new array<array<int><<3>>>(2);
                        array<int><<3>> r = [7, 8, 9];
                        rows[1] = r;
                        array<double><<2, 2>> grid = [[1.5, 2.5], [3.5, 4.5]];
                        System.stdout.print(h.nums[0] + " " + rows[1][2] + " " + grid[1][0] + " ");
                        int seven = 7;
                        try { int bad = a[seven]; } catch (IndexOutOfBoundsException e) { System.stdout.print(e.getMessage()); }
                        return 0;
                    }
                }
                """), "5 1 5 2 false 15 3 2 1 9 3.5 index 7 out of range for an array of length 5");
        compileError("the old array spelling is reported with the new one", files("Main", main("int[3] xs;")),
                "arrays are written array<int><<3>>, not int[3]");
        compileError("a constant index outside a fixed array is caught when compiling", files("Main", main(
                "array<int><<2>> xs; pr(xs[2]);")), "index 2 is out of bounds for array<int><<2>>");
        output("fixed arrays with several dimensions", files("Main", """
                struct Main
                {
                    static <T> int cells(array<T><<int R, int C>> g) { return R * C; }
                    static int trace(array<int><<int N, int M>> m) { int t = 0; for (int i = 0; i < N; i += 1) { t += m[i][i]; } return t; }
                    static int main()
                    {
                        array<int><<3, 3>> m = [[1, 2, 3], [4, 5, 6], [7, 8, 9]];
                        array<array<int><<3>>><<3>> same = m;
                        array<int><<3>> row = m[2];
                        row[0] = 70;
                        array<char><<2, 3, 4>> cube;
                        cube[1][2][3] = 'z';
                        System.stdout.print(m[1][2] + " " + trace(m) + " " + cells(m) + " " + same[2][0] + " " + row[0] + " " + m[2][0]
                                + " " + cube[1][2][3] + " " + cube[0].length() + " " + sizeof(array<char><<2, 3, 4>>));
                        return 0;
                    }
                }
                """), "6 15 9 7 70 7 z 3 384");
        compileError("several lengths print in the short form", files("Main", main("array<double><<2, 2>> g = 5;")),
                "expected array<double><<2, 2>>, found integer constant 5");
        output("nested types", files("Inventory", """
import seal.util.List;
class Inventory
{
    public enum Kind { TOOL, FOOD, BLOCK }

    public interface Visitor
    {
        void visit(Item item);
    }

    public static class Item
    {
        public string name;
        public Kind kind;
        int count;                       // private, but Inventory may use it
        public new(string name, Kind kind) { this.name = name; this.kind = kind; }

        public static class Stats
        {
            public static int made = 0;
        }
    }

    struct Slot                          // private: only for Inventory
    {
        public int index;
        public Item item;
    }

    class Counter implements(Visitor)
    {
        public int foods;
        public void visit(Item item) { if (item.kind == Kind.FOOD) { foods += item.count; } }
    }

    List<Slot> slots = new List();

    public void add(string name, Kind kind, int count)
    {
        Item item = new Item(name, kind);
        item.count = count;
        Item.Stats.made += 1;
        Slot s;
        s.index = slots.size();
        s.item = item;
        slots.add(s);
    }

    public void each(Visitor v) { for (Slot s : slots) { v.visit(s.item); } }

    public int foods()
    {
        Counter c = new Counter();
        each(c);
        return c.foods;
    }
}
""", "Tree", """
import seal.util.List;
class Tree<T>
{
    static class Node<V>
    {
        public V value;
        public Node<V> left;
        public Node<V> right;
        public new(V v) { value = v; }
    }

    Node<T> root;
    int size;

    public void add(T v)
    {
        size += 1;
        if (root == null) { root = new Node<T>(v); return; }
        Node<T> at = root;
        while (true) {
            if (v < at.value) {
                if (at.left == null) { at.left = new Node<T>(v); return; }
                at = at.left;
            } else {
                if (at.right == null) { at.right = new Node<T>(v); return; }
                at = at.right;
            }
        }
    }

    public List<T> inOrder()
    {
        List<T> out = new List();
        walk(root, out);
        return out;
    }

    void walk(Node<T> n, List<T> out)
    {
        if (n == null) { return; }
        walk(n.left, out);
        out.add(n.value);
        walk(n.right, out);
    }
}
""", "Main", """
struct Main
{
    enum Mode { FAST, SLOW }

    static int main()
    {
        Inventory inv = new Inventory();
        inv.add("apple", Inventory.Kind.FOOD, 3);
        inv.add("axe", Inventory.Kind.TOOL, 1);
        inv.add("bread", Inventory.Kind.FOOD, 2);
        System.stdout.println(inv.foods() + " " + Inventory.Item.Stats.made);
        Inventory.Item thing = new Inventory.Item("stone", Inventory.Kind.BLOCK);
        System.stdout.println(thing.name + " " + thing.kind.name());
        inv.each(item -> System.stdout.print(item.name + " "));
        System.stdout.println();
        Tree<string> t = new Tree();
        for (string w : "pear fig apple kiwi".split(' ')) { t.add(w); }
        for (string w : t.inOrder()) { System.stdout.print(w + " "); }
        System.stdout.println();
        Mode m = Mode.SLOW;
        switch (m) { case FAST -> System.stdout.println("fast"); case SLOW -> System.stdout.println("slow"); }
        return 0;
    }
}
"""), "5 3\nstone BLOCK\napple axe bread \napple fig kiwi pear \nslow\n");
        output("character literals", files("Main", main("""
                string s = "Hello, World";
                int upper = 0;
                for (int i = 0; i < s.length(); i += 1) {
                    char c = s.charAt(i);
                    if (c >= 'A' && c <= 'Z') { upper += 1; }
                }
                pr(upper);
                char e = s.charAt(1);
                pr((int)(e - 'a'));
                pr((int)'\\n'); pr((int)'\\''); pr((int)'"'); pr((int)'\\\\'); pr((int)'\\u00e9');
                System.stdout.print('o'); System.stdout.print('k'); System.stdout.print(' ');
                pr(s.charAt(4) == 'o');
                """)), "2 4 10 39 34 92 233 ok true ");
    }

    private static void layout() {
        output("bit-packed struct layout", files(
                "Packed", "struct Packed { public uint<<3>> a; public uint<<5>> b; public bit c; public uint<<7>> d; }",
                "Main", main("""
                        pr(sizeof(Packed));
                        pr(sizeof(array<Packed><<4>>));
                        array<Packed><<4>> ps;
                        ps[2].a = 5; ps[2].b = 17; ps[2].c = true; ps[2].d = 100;
                        ps[3].a = 7;
                        pr((int)ps[2].a + (int)ps[2].b + (int)ps[2].d);
                        pr(ps[2].c);
                        pr((int)ps[3].a);
                        """)), "16 64 122 true 7 ");
        output("alignment padding", files(
                "Mixed", "struct Mixed { int<<8>> a; int b; int<<8>> c; }",
                "Main", main("pr(sizeof(Mixed)); pr(alignof(Mixed)); pr(sizeof(array<Mixed><<2>>)); pr(alignof(int<<24>>));")),
                "96 32 192 1 ");
        output("odd widths pack without padding", files(
                "Rgb", "struct Rgb { public uint<<5>> b; public uint<<6>> g; public uint<<5>> r; }",
                "Main", main("""
                        pr(sizeof(Rgb)); pr(alignof(Rgb)); pr(sizeof(array<Rgb><<3>>));
                        array<uint<<3>>><<3>> before;
                        addr<uint<<3>>> keepInMemory = *before[0];
                        Rgb c;
                        c.r = 31; c.g = 63; c.b = 0;
                        addr<uint<<16>>> raw = (addr<uint<<16>>>)(addr<void>)*c;
                        pr((uint)&raw);
                        pr(*c.g - (addr<uint<<6>>>)(addr<void>)*c);
                        pr(*c.r - (addr<uint<<5>>>)(addr<void>)*c);
                        """)), "16 1 48 65504 5 11 ");
    }

    private static void statements() {
        String point = "struct Point { public int x; public int y; public Point twice() { Point r; r.x = x * 2; return r; } }";
        output("objects returned and used directly", files("Point", point, "Main", """
                struct Main
                {
                """ + HELPERS + """
                    static Point make(int v) { Point p; p.x = v; return p; }
                    static Point sum(int n)
                    {
                        if (n == 0) { Point zero; return zero; }
                        Point p = sum(n - 1);
                        p.x += n;
                        return p;
                    }
                    static int main()
                    {
                        pr(make(4).x);
                        pr(make(3).twice().x);
                        pr(sum(10).x);
                        int a; int b;
                        a = b = 3;
                        pr(a + b);
                        return 0;
                    }
                }
                """), "4 6 55 6 ");
        output("scopes and loops", files("Main", main("""
                int x = 1;
                { int x = 2; pr(x); }
                pr(x);
                int s = 0;
                for (int i = 0; i < 5; i += 1) { if (i % 2 == 0) { continue; } s += i; }
                pr(s);
                array<int><<3>> a;
                int i = 0;
                while (i < 3 && a[i] == 0) { i += 1; }
                pr(i);
                int n = 0;
                while (true) { n += 1; if (n >= 4) { break; } }
                pr(n);
                """)), "2 1 4 3 4 ");
        output("static fields of every kind", files(
                "Point", point,
                "Registry", """
                        struct Registry
                        {
                            public static array<int><<4>> slots;
                            public static Point origin;
                            public static int count = 2;
                            public static addr<int> shared;
                        }
                        """,
                "Main", main("""
                        Registry.slots[1] = 5;
                        Registry.origin.x = 7;
                        Registry.shared = new<int>(1);
                        &Registry.shared = 30;
                        pr(Registry.slots[1] + Registry.origin.x + Registry.count + &Registry.shared);
                        delete Registry.shared;
                        """)), "44 ");
    }

    private static void handles() {
        output("null handles, and deleting a null addr", files(
                "Doc", "class Doc { public int pages; }",
                "Main", main("""
                        handle<Doc> d = null;
                        pr(d == null);
                        d = new Doc();
                        pr(d != null);
                        addr<int> p = null;
                        delete p;
                        """)), "true true ");
        compileError("delete frees only addr memory", files(
                "Doc", "class Doc { public int pages; }",
                "Main", main("handle<Doc> d = new Doc(); delete d;")),
                "delete frees addr memory only; the garbage collector frees a class object once nothing refers to it"
                        + " (set the variable to null to let go of it)");
    }

    private static void resources() {
        compileError("try-with-resources needs an AutoCloseable", files("Doc", "class Doc { public void close() { } }", "Main", """
                struct Main
                {
                    void run() { try (Doc d = new Doc()) { } }
                }
                """), "a resource of try-with-resources must implement AutoCloseable, so that it can be closed; Doc doesn't"
                        + " (it has close(): add implements(AutoCloseable))");
        compileError("a resource can't be assigned", files("Doc", "class Doc implements(AutoCloseable) { public void close() { } }", "Main", """
                struct Main
                {
                    void run() { try (Doc d = new Doc()) { d = new Doc(); } }
                }
                """), "d is a resource of try-with-resources, closed at the end of its block, so it can't be assigned");
        output("try-with-resources with two resources, catch and finally", files(
                "Doc", "class Doc implements(AutoCloseable) { public int n; public new(int i) { n = i; } public void close() { System.stdout.print(\"~\" + n); } }",
                "Main", """
                struct Main
                {
                    static int run()
                    {
                        try (Doc a = new Doc(1); Doc b = new Doc(a.n + 1);) { return b.n; } catch (IllegalStateException e) { return -1; } finally { System.stdout.print(" f "); }
                    }
                    static int main() { System.stdout.print(run()); return 0; }
                }
                """), "~2~1 f 2");
        compileError("string methods that would change it", files("Main", """
                struct Main
                {
                    void run() { string s = "a"; s.append("b"); }
                }
                """), "string has no method append; strings are immutable: s += x stores a new string in s");
    }

    private static void fixedPoint() {
        output("fixed-point arithmetic", files("Main", main("""
                fixed<<8, 8>> a = 1.5;
                fixed<<8, 8>> b = 2.25;
                System.stdout.println(a + b);
                System.stdout.println(a * b);
                System.stdout.println(b / a);
                System.stdout.println(a - b);
                System.stdout.println(-a);
                System.stdout.println(a % 1);
                System.stdout.println(a * 2 + 1);
                """)), "3.75\n3.375\n1.5\n-0.75\n-1.5\n0.5\n4.0\n");
        output("fixed-point rounding and wrapping", files("Main", main("""
                fixed<<8, 8>> big = 127.5;
                big += 1.0;
                System.stdout.println(big);
                fixed<<8, 2>> q = 0.375;
                System.stdout.println(q);
                q = -0.375;
                System.stdout.println(q);
                fixed<<8, 2>> x = 0.25;
                System.stdout.println(x * x);
                x = 0.75;
                System.stdout.println(x * x);
                fixed<<8, 8>> third = 1.0 / 3.0;
                System.stdout.println(third);
                ufixed<<4, 4>> u = 15.9375;
                u += 0.0625;
                System.stdout.println(u);
                """)), "-127.5\n0.5\n-0.25\n0.0\n0.5\n0.33203125\n0.0\n");
        output("fixed-point casts", files("Main", main("""
                fixed<<8, 8>> n = -2.75;
                fixed<<8, 8>> p = 2.75;
                System.stdout.println((int)n);
                System.stdout.println((int)p);
                int three = 3;
                System.stdout.println((fixed<<8, 8>>)three);
                fixed<<8, 8>> tenth = 0.1;
                System.stdout.println((double)tenth);
                double d = 1.0 / 3.0;
                System.stdout.println((fixed<<8, 8>>)d);
                fixed<<8, 8>> fine = 1.40625;
                System.stdout.println((fixed<<8, 2>>)fine);
                System.stdout.println((fixed<<16, 8>>)n);
                float f = (float)p;
                System.stdout.println(f);
                ufixed<<8, 8>> un = (ufixed<<8, 8>>)p;
                System.stdout.println(un);
                """)), "-2\n2\n3.0\n0.1015625\n0.33203125\n1.5\n-2.75\n2.75\n2.75\n");
        output("fixed-point comparisons", files("Main", main("""
                fixed<<8, 8>> a = 1.5;
                fixed<<8, 8>> b = 2.25;
                System.stdout.print(a < b); System.stdout.print(a == 1.5); System.stdout.print(b >= 2); System.stdout.print(a != a);
                ufixed<<8, 8>> big = 200.0;
                ufixed<<8, 8>> small = 1.0;
                System.stdout.print(big > small);
                """)), "truetruetruefalsetrue");
        output("wide fixed-point values", files("Main", main("""
                fixed<<40, 24>> x = 100000.5;
                System.stdout.println(x * x);
                fixed<<40, 24>> m = 1000000.0;
                System.stdout.println(m / 3);
                fixed<<1, 63>> tiny = -1.0;
                System.stdout.println(tiny);
                ufixed<<0, 64>> half = 0.5;
                System.stdout.println(half);
                System.stdout.println((int)half);
                """)), "10000100000.25\n333333.333333313465118408203125\n-1.0\n0.5\n0\n");
        output("fixed-point in classes and arrays", files(
                "Pixel", "struct Pixel { public ufixed<<1, 7>> r; public ufixed<<1, 7>> g; public fixed<<4, 4>> offset; }",
                "Main", main("""
                        pr(sizeof(Pixel));
                        array<Pixel><<2>> ps;
                        ps[1].r = 0.75;
                        ps[1].offset = -3.5;
                        System.stdout.println(ps[1].r);
                        System.stdout.println(ps[1].offset);
                        array<fixed<<16, 16>>><<4>> xs;
                        fixed<<16, 16>> sum = 0;
                        for (int i = 0; i < 4; i += 1) {
                            xs[i] = (fixed<<16, 16>>)i;
                            xs[i] = xs[i] / 4;
                            sum += xs[i];
                        }
                        System.stdout.println(sum);
                        addr<fixed<<4, 4>>> o = *ps[1].offset;
                        &o = 1.25;
                        System.stdout.println(ps[1].offset);
                        """)), "24 0.75\n-3.5\n1.5\n1.25\n");
        output("fixed-point widths from struct values", files(
                "Q", "struct Q<<int I, int F>> { public fixed<<I, F>> v; public fixed<<I, F>> twice() { return v + v; } }",
                "Main", main("""
                        Q<<4, 4>> a;
                        a.v = 1.5;
                        System.stdout.println(a.twice());
                        Q<<20, 12>> b;
                        b.v = 1000.125;
                        System.stdout.println(b.twice());
                        pr(sizeof(Q<<20, 12>>));
                        """)), "3.0\n2000.25\n32 ");
    }

    private static void statics() {
        String shape = """
                class Shape
                {
                    public static int instances = 0;
                    public static Shape unit;
                    public int size;
                    public new(int s) { size = s; instances += 1; }
                    public static Shape make(int s) { return new Shape(s); }
                    public static int area(Shape s) { return s.size * s.size; }
                    public int bigger() { return make(size + 1).size; }
                }
                """;
        String square = """
                class Square extends(Shape)
                {
                    public new(int s) { super(s); }
                    public static int sides() { return 4; }
                    public static Shape twoOf() { return make(2); }
                }
                """;
        output("static members of a class", files("Shape", shape, "Square", square, "Main", main("""
                Shape s = Shape.make(3);
                Shape.unit = Shape.make(1);
                pr(Shape.area(s)); pr(s.bigger()); pr(Shape.unit.size); pr(Shape.instances);
                """)), "9 4 1 3 ");
        output("static members are inherited", files("Shape", shape, "Square", square, "Main", main("""
                Shape s = Square.make(2);
                pr(Square.instances); pr(Square.area(s)); pr(Square.sides()); pr(Square.twoOf().size);
                """)), "1 4 4 2 ");
        output("static members of a struct and a unique", files(
                "Vec", """
                        struct Vec
                        {
                            public static int made = 0;
                            public int x;
                            public static Vec of(int x) { made += 1; Vec v; v.x = x; return v; }
                            public static int twice(int a) { return a * 2; }
                            public int doubled() { return twice(x); }
                        }
                        """,
                "Fd", """
                        unique Fd
                        {
                            public static int open = 0;
                            public int fd;
                            public new(int f) { fd = f; open += 1; }
                            delete() { open -= 1; }
                            public static Fd openFile(int fd) { Fd f = new Fd(fd); return f; }
                        }
                        """,
                "Main", main("""
                        Vec v = Vec.of(21);
                        pr(v.doubled()); pr(Vec.twice(5)); pr(Vec.made);
                        {
                            Fd f = Fd.openFile(3);
                            pr(f.fd); pr(Fd.open);
                        }
                        pr(Fd.open);
                        """)), "42 10 1 3 1 0 ");
    }

    private static void shapesOnEveryKind() {
        output("shapes on a unique and a class", files(
                "Pool", """
                // unique with two shapes, per-shape field types, and per-shape destructors
                unique Pool<<>>
                unique Pool<<int N>>
                {
                    public int used;
                    array<int><<N>> slots<<int N>>;
                    array<int><<2>> slots<<>>;
                    public int capacity<<int N>>() { return N; }
                    public int capacity<<>>() { return 2; }
                    public void put(int v) { slots[used] = v; used += 1; }
                    public int total() { int t = 0; for (int i = 0; i < used; i += 1) { t += slots[i]; } return t; }
                    delete<<int N>>() { System.stdout.print("~Pool<<"); System.stdout.print(N); System.stdout.print(">> "); }
                    delete<<>>() { System.stdout.print("~Pool "); }
                }
                """,
                "Grid", """
                // class with two shapes, guarded members, per-shape fields, and a binder method
                class Grid<<int W>>
                class Grid<<int W, int H>>
                {
                    array<int><<W>> row<<int W>>;
                    array<int><<W, H>> cells<<int W, int H>>;
                    public int area<<int W>>() { return W; }
                    public int area<<int W, int H>>() { return W * H; }
                    public int describe() { return area() * 10; }        // unguarded, virtual, uses guarded area()
                    public int same<<int W>>(Grid<<int K>> other) { return W + K; }   // binder on a class method
                }
                """,
                "Big", """
                class Big<<int W>> extends(Grid<<W>>)
                {
                    public int describe() { return area() * 100; }       // overrides the parent's virtual method
                }
                """,
                "Main", """
                struct Main
                {
                    static int capacityOf(Pool<<int N>> p) { return N; }            // a unique lent to a binder parameter
                    static int fill(Pool<<int N>> p) { p.put(N); return p.total(); }

                    static int main()
                    {
                        {
                            Pool small;
                            Pool<<4>> large = new Pool();
                            small.put(1);
                            large.put(5);
                            System.stdout.print("unique: "); System.stdout.print(small.capacity()); System.stdout.print(" "); System.stdout.print(large.capacity());
                            System.stdout.print(" "); System.stdout.print(capacityOf(large)); System.stdout.print(" "); System.stdout.print(fill(large));
                            System.stdout.print(" "); System.stdout.print(large.used); System.stdout.print(" ");
                            System.stdout.print(sizeof(Pool)); System.stdout.print(" "); System.stdout.println(sizeof(Pool<<4>>));
                        }
                        System.stdout.println();
                        Grid<<3>> a = new Grid();
                        Grid<<3, 4>> b = new Grid();
                        Grid<<5>> c = new Grid();
                        System.stdout.print("class: "); System.stdout.print(a.describe()); System.stdout.print(" "); System.stdout.print(b.describe());
                        System.stdout.print(" "); System.stdout.print(a.same(c)); System.stdout.print(" ");
                        Grid<<3>> asParent = new Big<<3>>();
                        System.stdout.print(asParent.describe()); System.stdout.print(" ");
                        a = null;
                        System.stdout.println("| ");
                        return 0;
                    }
                }
                """),
                "unique: 2 4 4 9 2 96 160\n~Pool<<4>> ~Pool \nclass: 30 120 8 300 | \n");
    }

    private static void genericsOnEveryKind() {
        output("generics on a unique and a class", files(
                "Fd", """
                unique Fd
                {
                    public int fd;
                    public new() { fd = -1; }
                    public new(int f) { fd = f; }
                    delete() { System.stdout.print("~Fd"); System.stdout.print(fd); System.stdout.print(" "); }
                }
                """,
                "Owned", """
                // a generic unique: type and value parameters, deferred operators, a destructor
                unique Owned<T><<int N>>
                {
                    public array<T><<N>> items;
                    public int count;
                    public void add(T v) { items[count] = v; count += 1; }
                    public T largest() { T best = items[0]; for (int i = 1; i < count; i += 1) { if (best < items[i]) { best = items[i]; } } return best; }
                    delete() { System.stdout.print("~Owned "); }
                }
                """,
                "Slot", """
                // a generic unique holding another unique
                unique Slot<T>
                {
                    public T inner;
                }
                """,
                "Base", """
                // generic classes with inheritance between them
                class Base<T>
                {
                    public T value;
                    public T get() { return value; }
                    public int kind() { return 1; }
                }
                """,
                "Derived", """
                class Derived<T> extends(Base<T>)
                {
                    public int kind() { return 2; }
                    public T twice() { return value + value; }
                }
                """,
                "Holder", """
                // a generic class holding a unique (classes may hold uniques without destructors)
                class Holder<T>
                {
                    public T item;
                }
                """,
                "Pin", "unique Pin { public int n; public new() { } public new(int i) { n = i; } }",
                "Main", """
                struct Main
                {
                    static int peek(Owned<int><<int N>> o) { return N * 100 + o.count; }   // lent, with a binder

                    static int main()
                    {
                        {
                            Owned<int><<4>> o = new Owned();
                            o.add(3); o.add(9); o.add(5);
                            System.stdout.print("unique: "); System.stdout.print(o.largest()); System.stdout.print(" "); System.stdout.print(peek(o)); System.stdout.print(" ");
                            Owned<double><<2>> d = new Owned();
                            d.add(1.5); d.add(-2.0);
                            System.stdout.print(d.largest()); System.stdout.print(" ");
                            Slot<Fd> s = new Slot();
                            s.inner = new Fd(7);
                            System.stdout.print(s.inner.fd); System.stdout.print(" ");
                        }
                        System.stdout.println();

                        Derived<int> dv = new Derived();
                        dv.value = 21;
                        Base<int> b = dv;
                        System.stdout.print("class: "); System.stdout.print(b.get()); System.stdout.print(" "); System.stdout.print(b.kind()); System.stdout.print(" "); System.stdout.print(dv.twice()); System.stdout.print(" ");
                        Base<double> bd = new Base();
                        bd.value = 0.5;
                        System.stdout.print(bd.get()); System.stdout.print(" "); System.stdout.print(bd.kind()); System.stdout.print(" ");
                        Holder<Pin> h = new Holder();
                        h.item = new Pin(8);
                        System.stdout.print(h.item.n); System.stdout.print(" ");
                        h = null;
                        System.stdout.println("| ");

                        return 0;
                    }
                }
                """),
                "unique: 9 403 1.5 ~Fd-1 7 ~Fd7 ~Owned ~Owned \nclass: 21 2 42 0.5 1 8 | \n");
        compileError("a type argument without a no-argument constructor", files(
                "Fd", "unique Fd { public new(int f) { } }",
                "Slot", "unique Slot<T> { public T inner; }",
                "Main", main("Slot<Fd> s = new Slot();")),
                "field inner of type Fd needs an initializer: Fd has no no-argument constructor");
    }

    private static void operatorsOnEveryKind() {
        output("operators on a unique", files("Amount", """
                unique Amount
                {
                    public int cents;
                    public new() { }
                    public new(int c) { cents = c; }
                    delete() { System.stdout.print("~"); System.stdout.print(cents); System.stdout.print(" "); }
                    @overload(Operator.ADD) public Amount plus(Amount o) { return new Amount(cents + o.cents); }
                    @overload(Operator.ADD_ASSIGN) public void add(Amount o) { cents += o.cents; }
                    @overload(Operator.NEGATE) public Amount negate() { return new Amount(-cents); }
                    @overload(Operator.LESS) public bit less(Amount o) { return cents < o.cents; }
                    @overload(Operator.EQUALS) public bit same(Amount o) { return cents == o.cents; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Amount a = new Amount(100);
                        Amount b = new Amount(250);
                        Amount c = a + b;
                        System.stdout.print(c.cents); System.stdout.print(" ");
                        System.stdout.print((-a).cents); System.stdout.print(" ");
                        System.stdout.print(a < b); System.stdout.print(a > b); System.stdout.print(a != b); System.stdout.print(" ");
                        c += a;
                        System.stdout.print(c.cents); System.stdout.print(" | ");
                        return 0;
                    }
                }
                """),
                "350 -100~-100  truefalsetrue 450 | ~450 ~250 ~100 ");
        output("operators on a class", files(
                "Money", """
                class Money
                {
                    public int cents;
                    public new(int c) { cents = c; }
                    @overload(Operator.ADD) public Money plus(Money o) { return new Money(cents + o.cents); }
                    @overload(Operator.ADD_ASSIGN) public void add(Money o) { cents += o.cents; }
                    @overload(Operator.NEGATE) public Money negate() { return new Money(-cents); }
                    @overload(Operator.LESS) public bit less(Money o) { return cents < o.cents; }
                    @overload(Operator.MULTIPLY) public Money times(int k) { return new Money(cents * k); }
                    @overload(Operator.SHIFT_ASSIGN) public void scaleUp(int n) { cents = cents <<< n; }
                }
                """,
                "Euro", """
                class Euro extends(Money)
                {
                    public new(int c) { super(c); }
                }
                """,
                "Tag", """
                class Tag
                {
                    public int id;
                    @overload(Operator.EQUALS) public bit same(Tag o) { return id == o.id; }
                }
                """,
                "Main", """
                struct Main
                {
                    static int main()
                    {
                        Money a = new Money(100);
                        Money b = new Money(250);
                        Money c = a + b;
                        System.stdout.print(c.cents); System.stdout.print(" ");
                        System.stdout.print((-a).cents); System.stdout.print(" ");
                        System.stdout.print(a < b); System.stdout.print(a >= b); System.stdout.print(" ");
                        c += a;
                        System.stdout.print(c.cents); System.stdout.print(" ");
                        c <<= 1;
                        System.stdout.print(c.cents); System.stdout.print(" ");
                        System.stdout.print((a * 3 + b).cents); System.stdout.print(" ");
                        Money same = a;
                        System.stdout.print(same == a); System.stdout.print(new Money(100) == a); System.stdout.print(" ");
                        Euro e = new Euro(5);
                        System.stdout.print((e + e).cents); System.stdout.print(" ");
                        Tag t1 = new Tag(); t1.id = 4;
                        Tag t2 = new Tag(); t2.id = 4;
                        System.stdout.print(t1 == t2); System.stdout.print(t1 != t2); System.stdout.print(" | ");
                        return 0;
                    }
                }
                """),
                "350 -100 truefalse 450 900 550 truefalse 10 truefalse | ");
        compileError("a class without the operator", files(
                "Thing", "class Thing { }",
                "Main", main("Thing a = new Thing(); Thing b = a + a;")),
                "Thing does not define operator +");
    }

    private static void temporariesOnSkippedPaths() {
        output("objects on the skipped side of && are never destroyed", files(
                "Tk", "unique Tk { public int id; public new(int i) { id = i; } delete() { System.stdout.print(id); System.stdout.print(\"~ \"); } }",
                "Main", """
                        import seal.util.List;
                        struct Main
                        {
                            static Tk make(int i) { Tk t = new Tk(i); return t; }
                            static int main()
                            {
                                for (int i = 0; i < 4; i += 1) {
                                    if (i % 2 == 1 && make(i).id > 0) { System.stdout.print("y "); }
                                }
                                List<string> w = new List();
                                w.add("pear"); w.add("apple"); w.add("fig");
                                w.sort();
                                System.stdout.print(w.get(0));
                                return 0;
                            }
                        }
                        """), "1~ y 3~ y apple");
    }

    private static void arraysOfObjects() {
        String p = "struct P { public int x; public new() { x = 7; } }";
        String q = "struct Q { public int y = 5; public int z; }";
        String r = """
                unique R
                {
                    public int id;
                    public new() { System.stdout.print("+"); }
                    delete() { System.stdout.print("-"); }
                }
                """;
        output("struct elements are default-constructed", files("P", p, "Q", q, "Main", main("""
                array<P><<3>> a;
                pr(a[0].x + a[1].x + a[2].x);
                array<Q><<2>> b;
                pr(b[0].y + b[1].y + b[1].z);
                array<P><<2, 3>> grid;
                pr(grid[1][2].x);
                """)), "21 10 7 ");
        output("unique elements: constructed once, destroyed once", files("R", r, "Main", main("""
                { array<R><<3>> rs; System.stdout.print("|"); }
                System.stdout.println();
                """)), "+++|---\n");
        output("large arrays use a loop, in both directions", files("R", r, "Main", main("""
                { array<R><<40>> rs; rs[39].id = 1; System.stdout.print("|"); }
                System.stdout.println();
                """)), "+".repeat(40) + "|" + "-".repeat(40) + "\n");
        output("arrays in fields, class objects and static fields", files("P", p, "R", r,
                "Holder", "struct Holder { public array<P><<2>> ps; }",
                "Box", "class Box { public array<P><<3>> ps; }",
                "Reg", "struct Reg { public static array<P><<2>> shared; }",
                "Keep", "unique Keep { public static array<R><<2>> rs; }",
                "Main", main("""
                        Holder h;
                        pr(h.ps[1].x);
                        Box b = new Box();
                        pr(b.ps[2].x);
                        b = null;
                        System.stdout.print(" ");
                        pr(Reg.shared[1].x);
                        """)), "++7 7  7 --");
        output("a generic array of a type argument", files("P", p,
                "Stack", "struct Stack<T><<int N>> { public array<T><<N>> items; }",
                "Main", main("Stack<P><<5>> s; pr(s.items[4].x);")), "7 ");
        output("class elements are null until assigned", files("Doc", "class Doc { public int n; }",
                "Main", main("""
                        array<Doc><<3>> docs;
                        pr(docs[0] == null);
                        docs[1] = new Doc();
                        docs[1].n = 4;
                        pr(docs[1].n);
                        """)), "true 4 ");
        compileError("element type without a no-argument constructor", files(
                "F", "struct F { public int v; public new(int x) { v = x; } }",
                "Main", main("array<F><<2>> fs;")),
                "each element of variable fs (array<F><<2>>) is default-constructed, but F has no no-argument constructor");
        compileError("the same through a type argument", files(
                "F", "struct F { public int v; public new(int x) { v = x; } }",
                "Stack", "struct Stack<T><<int N>> { public array<T><<N>> items; }",
                "Main", main("Stack<F><<2>> s;")),
                "each element of field items (array<F><<2>>) is default-constructed, but F has no no-argument constructor");
    }

    private static void uniques() {
        String file = """
                unique Fd
                {
                    public int fd;
                    public new(int f) { fd = f; System.stdout.print(fd); System.stdout.print("+ "); }
                    delete() { System.stdout.print(fd); System.stdout.print("- "); }
                }
                """;
        output("lending lets a function change the caller's unique", files("Fd", file, "Main", """
                struct Main
                {
                    static void bump(Fd f) { f.fd += 10; }
                    static int main()
                    {
                        Fd a = new Fd(1);
                        bump(a);
                        bump(a);
                        System.stdout.print(a.fd);
                        System.stdout.print(" ");
                        return 0;
                    }
                }
                """), "1+ 21 21- ");
        output("a temporary argument is destroyed after the statement", files("Fd", file, "Main", """
                struct Main
                {
                    static int read(Fd f) { return f.fd; }
                    static int main()
                    {
                        int x = read(new Fd(5));
                        System.stdout.print("| ");
                        return 0;
                    }
                }
                """), "5+ 5- | ");
        output("returning a local moves it: destroyed once", files("Fd", file, "Main", """
                struct Main
                {
                    static Fd open(int fd) { Fd f = new Fd(fd); return f; }
                    static int main()
                    {
                        {
                            Fd a = open(7);
                            System.stdout.print("| ");
                        }
                        System.stdout.print("end ");
                        return 0;
                    }
                }
                """), "7+ | 7- end ");
        output("assigning a new value destroys the old one", files("Fd", file, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Fd a = new Fd(1);
                        a = new Fd(2);
                        System.stdout.print("| ");
                        return 0;
                    }
                }
                """), "1+ 2+ 1- | 2- ");
        output("a unique stored in a class object can be lent", files(
                "Fd", "unique Fd { public int fd; public new(int f) { fd = f; System.stdout.print(fd); System.stdout.print(\"+ \"); } }",
                "Holder", "class Holder { public Fd f = new Fd(3); }",
                "Main", """
                        struct Main
                        {
                            static void bump(Fd f) { f.fd += 1; }
                            static int main()
                            {
                                Holder h = new Holder();
                                bump(h.f);
                                System.stdout.print(h.f.fd);
                                System.stdout.print(" ");
                                return 0;
                            }
                        }
                        """), "3+ 4 ");
        output("arrays of uniques", files("Fd", """
                unique Fd
                {
                    public int fd;
                    public new() { }
                    delete() { System.stdout.print(fd); System.stdout.print("- "); }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        { array<Fd><<2>> fs; fs[0].fd = 8; fs[1].fd = 9; }
                        return 0;
                    }
                }
                """), "9- 8- ");
    }

    private static void abstractAndInterfaces() {
        String drawable = "interface Drawable { void draw(); }";
        String named = "interface Named { string name(); }";
        String thing = "interface Thing extends(Drawable, Named) { int id(); }";
        String shape = """
                abstract class Shape implements(Drawable)
                {
                    public int calls;
                    public new() { calls = 0; }
                    public abstract int area();
                    public void describe() { calls += 1; System.stdout.print(area()); System.stdout.print(" "); }
                }
                """;
        String square = """
                class Square extends(Shape) implements(Thing)
                {
                    public int side;
                    public new(int s) { super(); side = s; }
                    public int area() { return side * side; }
                    public void draw() { System.stdout.print("[] "); }
                    public string name() { return "square"; }
                    public int id() { return 7; }
                }
                """;
        String circle = """
                class Circle extends(Shape)
                {
                    public int r;
                    public new(int radius) { r = radius; }
                    public int area() { return 3 * r * r; }
                    public void draw() { System.stdout.print("() "); }
                }
                """;
        output("abstract methods and interface methods dispatch to the object's class", files("Drawable", drawable,
                "Named", named, "Thing", thing, "Shape", shape, "Square", square, "Circle", circle, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Shape s = new Circle(2);
                        s.describe();
                        s.draw();
                        array<Drawable><<2>> ds = [s, new Square(3)];
                        ds[0].draw(); ds[1].draw();
                        Thing t = (Thing)ds[1];
                        System.stdout.print(t.name()); System.stdout.print(" ");
                        Named n = t;
                        System.stdout.print(n.name()); System.stdout.print(t.id()); System.stdout.print(" ");
                        Square back = (Square)n;
                        back.describe();
                        System.stdout.print(back.calls); System.stdout.print(" ");
                        System.stdout.print(ds[0] == s); System.stdout.print(" ");
                        return 0;
                    }
                }
                """), "12 () () [] square square7 9 1 true ");
        output("a generic interface", files("Comparable", "interface Comparable<T> { int compareTo(T other); }",
                "Version", """
                class Version implements(Comparable<Version>)
                {
                    public int n;
                    public new(int v) { n = v; }
                    public int compareTo(Version other) { return n - other.n; }
                }
                """, "Main", """
                import seal.util.List;
                struct Main
                {
                    static int main()
                    {
                        List<Comparable<Version>> xs = new List();
                        xs.add(new Version(3));
                        System.stdout.print(xs.get(0).compareTo(new Version(1)));
                        return 0;
                    }
                }
                """), "2");
        output("instanceof tests and binds", files("Drawable", drawable,
                "Named", named, "Thing", thing, "Shape", shape, "Square", square, "Circle", circle, "Main", """
                struct Main
                {
                    static int main()
                    {
                        array<Drawable><<3>> ds = [new Square(2), new Circle(1), null];
                        for (int i = 0; i < 3; i += 1) {
                            if (ds[i] instanceof Thing t && t.id() == 7) { System.stdout.print(t.name()); }
                            else if (ds[i] instanceof Shape s) { System.stdout.print(s.area()); }
                            else { System.stdout.print("null"); }
                            System.stdout.print(" ");
                        }
                        System.stdout.print(ds[0] instanceof Named); System.stdout.print(ds[1] instanceof Named);
                        System.stdout.print(" ");
                        return 0;
                    }
                }
                """), "square 3 null truefalse ");
        trap("casting to an interface the object doesn't implement", files("Drawable", drawable,
                "Named", named, "Thing", thing, "Shape", shape, "Square", square, "Circle", circle, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Drawable d = new Circle(1);
                        Thing t = (Thing)d;
                        return 0;
                    }
                }
                """), "cannot cast Circle to Thing");
    }

    // ================================================================ helpers

    private static void importsAs() {
        String util = """
                package some.util;

                class HashMap
                {
                    public static int made = 0;
                    public new() { made++; }
                    public int size() { return 42; }
                    public static int twice(int x) { return 2 * x; }
                    public const int LIMIT = 7;
                    public struct Entry { public int k; }
                }
                """;
        output("import ... as and import static ... as: two types of one name, and members renamed (§2.4)", files(
                "some/util/HashMap", util,
                "Main", """
                        import seal.util.HashMap as SealHashMap;
                        import some.util.HashMap as UtilHashMap;
                        import static some.util.HashMap.twice as double2;
                        import static some.util.HashMap.LIMIT as MAX;
                        import static seal.lang.Math.max as biggest;
                        import static some.util.HashMap.made as count;

                        struct Main
                        {
                            static int main()
                            {
                                SealHashMap<string, int> ages = new SealHashMap();
                                ages.put("ada", 36);
                                UtilHashMap mine = new UtilHashMap();
                                UtilHashMap.Entry e;
                                e.k = 3;
                                array<int><<MAX>> xs;
                                count += 10;
                                seal.util.HashMap<string, int> plain = new seal.util.HashMap(); // by its full name, with no import
                                plain.put("x", 1);
                                System.stdout.println(ages.get("ada") + " " + mine.size() + " " + double2(21) + " " + MAX + " " + xs.length()
                                        + " " + biggest(3, 9) + " " + e.k + " " + count + " " + UtilHashMap.made + " " + plain.size());
                                return 0;
                            }
                        }
                        """), "36 42 42 7 7 9 3 11 11 1\n");
        compileError("a renamed import doesn't bring its own name", files("Main", """
                import static seal.lang.Math.max as biggest;

                struct Main { static int main() { return max(1, 2); } }
                """), "unknown method max; this file imports seal.lang.Math.max as biggest");
        compileError("two imports can't take one name", files("some/util/HashMap", util, "Main", """
                import some.util.HashMap as Map;
                import seal.util.HashMap as Map;

                struct Main { static int main() { return 0; } }
                """), "import seal.util.HashMap as Map clashes with the import of some.util.HashMap as Map");
        compileError("nor the file's own type's", files("some/util/HashMap", util, "Main", """
                import some.util.HashMap as Main;

                struct Main { static int main() { return 0; } }
                """), "import some.util.HashMap as Main clashes with Main, declared in this file");
        compileError("a type in no package can't take the name of one of seal.lang's", files("Integer", "class Integer { }",
                "Main", main("")), "class Integer is part of the standard library (seal.lang.Integer, which every file sees);"
                + " choose another name, or put it in a package");
        compileError("nor of a built-in one", files("string", "class string { }", "Main", main("")),
                "class string is part of the standard library (string, which every file sees)");
        compileError("nor a package of the library's", files("seal/util/Extra", "package seal.util; class Extra { }",
                "Main", main("")), "package seal.util is the standard library's; choose another name for yours");
        compileError("the library's other types need an import", files("Main", """
                struct Main { static int main() { List<int> xs = new List(); return xs.size(); } }
                """), "unknown type List; import it: import seal.util.List;");
        compileError("an import names its type in full", files("Main", """
                import static Math.max;

                struct Main { static int main() { return max(1, 2); } }
                """), "no type Math to import from; an import names a type in full, as in import static seal.lang.Math.max;");
        output("a program's own type in no package may take a name from seal.util: in its package, it comes before what"
                + " a * import brings", files("List", """
                class List
                {
                    public int size() { return 5; }
                }
                """, "Main", """
                import seal.util.*;

                struct Main
                {
                    static int main()
                    {
                        List mine = new List();
                        Queue<int> q = new Queue();
                        q.push(7);
                        System.stdout.println(mine.size() + " " + q.size());
                        return 0;
                    }
                }
                """), "5 1\n");
    }

    private static void powersAndCompileTimeOperators() {
        output("** is a power: it wraps as * does, and works on floating point", files("Main", main("""
                int x = 3;
                int<<8>> b = 3;
                uint u = 3;
                int<<64>> ten = 10;
                pr(x ** 4); pr(b ** 5); pr(u ** 21); pr(ten ** 18); pr(ten ** 19);
                pr(x ** -1); pr(-1 ** 3); pr((-1) ** 3); pr((-1) ** -3); pr(1 ** -5); pr(x ** 0);
                pr(2 ** 3 ** 2); pr(-2 ** 2); pr(2 ** -1); pr(2 * 3 ** 2);
                double d = 2.0;
                pr(d ** 0.5); pr(d ** -1); pr(9 ** 0.5); pr(d ** 10);
                x **= 3;
                d **= 2;
                pr(x); pr(d);
                """)), "81 -13 1870418611 1000000000000000000 -8446744073709551616 0 -1 -1 -1 1 1 512 -4 0 18 "
                + "1.4142135623730951 0.5 3.0 1024.0 27 4.0 ");
        trap("zero to a negative power divides by zero", files("Main", main("""
                int zero = 0;
                int minus = -1;
                pr(zero ** minus);
                """)), "division by zero");
        output("a struct defines its own **", files("Num", """
                struct Num
                {
                    public int v;
                    public new(int v) { this.v = v; }
                    @overload(Operator.POWER) public Num pow(int n) { Num r = new Num(1); for (int i = 0; i < n; i++) { r.v *= v; } return r; }
                    @overload(Operator.POWER_ASSIGN) public void raise(int n) { v = (this ** n).v; }
                }
                """, "Main", main("""
                Num two = new Num(2);
                pr((two ** 10).v);
                two **= 5;
                pr(two.v);
                """)), "1024 32 ");
        output("every operator in a compile-time value", files(
                "Word", """
                        struct Word<<int N>>
                        {
                            public int<<N > 32 ? 64 : 32>> value;
                            public array<bit><<2 ** N>> bits;
                            public array<int><<N % 2 == 0 ? N / 2 : (N + 1) / 2>> halves;
                        }
                        """,
                "Quantity", "struct Quantity<<string U>> { public string unit() { return U; } }",
                "Main", """
                        struct Main
                        {
                            const int LIMIT = 2 ** 16 - 1;
                            const int SQUARE = -2 ** 2;
                            const int XOR = 2 ^ 16 - 1;
                            const int PICK = LIMIT > 255 ? 16 : 8;
                            const int BYTE = (uint<<8>>)300;
                            const int TRUNC = (int)-3.7 * 2;
                            const int BITS = ~(-8) | (1 <<< 4) & 31 ^ 2;
                            const int LOGIC = !(1 > 2) && (2 <= 3 || 1 / 0 == 0) ? 1 : 0;
                            static int main()
                            {
                                array<int><<2 ** 16 - 1>> big;
                                Word<<5>> five;
                                Quantity<<"k" + "m">> q;
                                System.stdout.println(big.length() + " " + LIMIT + " " + SQUARE + " " + XOR + " " + PICK + " " + BYTE
                                        + " " + TRUNC + " " + BITS + " " + LOGIC);
                                System.stdout.println(sizeof(int<<5 > 32 ? 64 : 32>>) + " " + five.bits.length() + " " + five.halves.length()
                                        + " " + sizeof(int<<40 > 32 ? 64 : 32>>) + " " + sizeof(Word<<3>>) + " " + q.unit());
                                return 0;
                            }
                        }
                        """), "65535 65535 -4 13 16 44 -6 23 1\n32 32 3 64 128 km\n");
        compileError("an array too long to hold is an error when compiling", files(
                "Word", "struct Word<<int N>> { public array<bit><<2 ** N>> bits; }",
                "Main", main("Word<<40>> wide;\n")), "in Word<<40>>: an array's length must be less than 2 ** 31, not 1099511627776");
        compileError("and so is a value too large for memory", files("Main", main("array<int<<64>>><<2 ** 26>> wide;\n")),
                "offset or size 4294967296 is too large");
    }

    private static void pureMethods() {
        String bits = """
                struct Bits
                {
                    public static pure int<<64>> fib(int n)
                    {
                        if (n < 0) { throw new IllegalArgumentException("fib of a negative number"); }
                        int<<64>> a = 0;
                        int<<64>> b = 1;
                        for (int i = 0; i < n; i++) { int<<64>> t = a + b; a = b; b = t; }
                        return a;
                    }
                    public static pure int log2(int n) { int r = 0; while ((1 <<< (r + 1)) <= n) { r++; } return r; }
                    public static pure int bitsFor(int n) { return log2(n) + 1; }
                    public static pure string unit(int i) { return switch (i) { case 0 -> "m"; case 1 -> "s"; default -> "kg"; }; }
                    public static pure int sum(array<int><<4>> xs) { int s = 0; for (int x : xs) { s += x; } return s; }
                }
                """;
        output("pure methods run while compiling: in <<...>>, array sizes and const fields (§17.2)", files("Bits", bits,
                "Quantity", "struct Quantity<<string U>> { public double v; public string unit() { return U; } }",
                "Main", """
                struct Main
                {
                    const int SIZE = Bits.fib(10);
                    static uint<<Bits.bitsFor(1000)>> small = 1000;
                    static array<int><<Bits.fib(6)>> table;
                    static int main()
                    {
                        System.stdout.print(SIZE + " " + sizeof(uint<<Bits.bitsFor(1000)>>) + " " + small + " " + table.length());
                        Quantity<<Bits.unit(1)>> t;
                        System.stdout.print(" " + t.unit() + " " + sizeof(array<bit><<Bits.sum([1, 2, 3, 4])>>));
                        System.stdout.println(" " + Bits.fib(50) + " " + Bits.log2(4096)); // and while the program runs
                        return 0;
                    }
                }
                """), "55 10 1000 8 s 10 12586269025 12\n");
        output("a pure call on a generic class's values is worked out for each shape", files("Bits", bits,
                "Table", "struct Table<<int N>> { public array<int><<Bits.fib(N) + 1>> cells; }",
                "Main", main("""
                        Table<<5>> five;
                        Table<<10>> ten;
                        pr(five.cells.length()); pr(ten.cells.length()); pr(sizeof(Table<<7>>));
                        """)), "6 56 448 ");
        output("pure methods compute as the program does: widths, bits, fixed point, floats, arrays, recursion", files(
                "Calc", """
                        struct Calc
                        {
                            public static pure uint<<8>> wrap(uint<<8>> x) { return x * 3 + 7; }
                            public static pure int<<64>> bitsSet(uint<<64>> x) { int<<64>> n = 0; while (x != 0) { if (x[0]) { n++; } x = x <<< -1; } return n; }
                            public static pure uint<<16>> swap(uint<<16>> x) { uint<<16>> r = 0; r[0..8] = x[8..16]; r[8..16] = x[0..8]; return r; }
                            public static pure fixed<<8, 8>> half(fixed<<8, 8>> x) { return x / 2 + x * 0.25; }
                            public static pure double mean(array<double><<3>> xs) { double s = 0; for (double x : xs) { s += x; } return s / 3; }
                            public static pure int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }
                            public static pure int primes(int n)
                            {
                                array<bit><<200>> composite;
                                int count = 0;
                                for (int i = 2; i < n; i++) {
                                    if (!composite[i]) { count++; for (int j = i * i; j < n; j += i) { composite[j] = true; } }
                                }
                                return count;
                            }
                            public static pure int ackermann(int m, int n)
                            {
                                if (m == 0) { return n + 1; }
                                if (n == 0) { return ackermann(m - 1, 1); }
                                return ackermann(m - 1, ackermann(m, n - 1));
                            }
                            public static pure char upper(char c) { return c >= 'a' && c <= 'z' ? (char)(c - 32) : c; }
                        }
                        """,
                "Main", """
                        struct Main
                        {
                            const int WRAP = Calc.wrap(100);
                            const int SET = Calc.bitsSet(18446744073709551615);
                            const int SWAP = Calc.swap(4660);
                            const fixed<<8, 8>> HALF = Calc.half(3.0);
                            const double MEAN = Calc.mean([1.5, 2.5, 4.0]);
                            const int GCD = Calc.gcd(1071, 462);
                            const int PRIMES = Calc.primes(200);
                            const int ACK = Calc.ackermann(2, 3);
                            const char UP = Calc.upper('q');
                            static int main()
                            {
                                System.stdout.println(WRAP + " " + SET + " " + SWAP + " " + HALF + " " + MEAN + " " + GCD + " " + PRIMES
                                        + " " + ACK + " " + UP);
                                System.stdout.println(Calc.wrap(100) + " " + Calc.bitsSet(18446744073709551615) + " " + Calc.swap(4660)
                                        + " " + Calc.half(3.0) + " " + Calc.mean([1.5, 2.5, 4.0]) + " " + Calc.gcd(1071, 462)
                                        + " " + Calc.primes(200) + " " + Calc.ackermann(2, 3) + " " + Calc.upper('q'));
                                return 0;
                            }
                        }
                        """), "51 64 13330 2.25 2.6666666666666665 21 46 9 Q\n51 64 13330 2.25 2.6666666666666665 21 46 9 Q\n");
        output("each pure call with the same arguments runs once while compiling", files("Bits", bits, "Main", main("""
                        pr(sizeof(array<bit><<Bits.fib(90) % 1000>>));
                        """)), "120 ");
        compileError("a pure call that fails while compiling says why, and where", files("Bits", bits, "Main", main("""
                        int<<Bits.fib(-1) + 1>> x = 0;
                        """)), "Bits.fib(-1) can't be worked out while compiling: it threw IllegalArgumentException: "
                + "fib of a negative number (Bits.seal:5)");
        compileError("dividing by zero while compiling", files("Main", """
                struct Main
                {
                    static pure int ratio(int a, int b) { return a / b; }
                    static int main() { array<int><<ratio(4, 0)>> xs; return 0; }
                }
                """), "Main.ratio(4, 0) can't be worked out while compiling: it divided by zero (Main.seal:3)");
        compileError("only pure methods run while compiling", files("Main", """
                struct Main
                {
                    static int count(int n) { return n; }
                    static int main() { array<int><<count(3)>> xs; return 0; }
                }
                """), "Main.count isn't pure, so it can't run while compiling; declare it `static pure`");
        String stats = """
                struct Stats
                {
                    public static pure int sum(array<int> xs) { int s = 0; for (int x : xs) { s += x; } return s; }
                    public static pure array<int> squares(int n)
                    {
                        array<int> out = new array<int>(n);
                        for (int i = 0; i < n; i++) { out[i] = i * i; }
                        return out;
                    }
                    public static pure int median(array<int> xs) { array<int> sorted = xs.copy(); sorted.sort(); return sorted[sorted.length() / 2]; }
                    public static pure int primesBelow(int n)
                    {
                        array<bit> composite = new array<bit>(n);
                        int count = 0;
                        for (int i = 2; i < n; i++) {
                            if (!composite[i]) { count++; for (int j = i * i; j < n; j += i) { composite[j] = true; } }
                        }
                        return count;
                    }
                    public static pure int histogram(array<int> xs, int bucket)
                    {
                        array<int> counts = new array<int>(10);
                        for (int x : xs) { counts[x % 10]++; counts[x % 10] += 0; }
                        return counts[bucket];
                    }
                    public static pure int tail(array<int> xs)
                    {
                        array<int> t = xs.slice(1, xs.length());
                        t.reverse();
                        return t[0] * 100 + t.indexOf(2) + (t.contains(9) ? 1000 : 0);
                    }
                    public static pure int grow(int n) { array<int> a = squares(3).copyOf(n); a.fill(7); a.swap(0, 1); return a.length() * 10 + a[n - 1]; }
                    public static pure double spread(array<double> xs) { array<double> s = xs.copy(); s.sort(); return s[s.length() - 1] - s[0]; }
                }
                """;
        output("pure methods make and use arrays while compiling, as when the program runs", files("Stats", stats, "Main", """
                struct Main
                {
                    const int SUM = Stats.sum([1, 2, 3, 4]);
                    const int SQ = Stats.sum(Stats.squares(10));
                    const int MED = Stats.median([9, 1, 7, 3, 5]);
                    const int PRIMES = Stats.primesBelow(1000);
                    const int H = Stats.histogram([11, 21, 3, 31, 45], 1);
                    const int TAIL = Stats.tail([5, 2, 9, 4]);
                    const int GROW = Stats.grow(5);
                    const double SPREAD = Stats.spread([2.5, -1.0, 4.25]);
                    static int main()
                    {
                        array<int><<Stats.sum([1, 2, 3])>> six;
                        System.stdout.println(SUM + " " + SQ + " " + MED + " " + PRIMES + " " + H + " " + TAIL + " " + GROW + " " + SPREAD
                                + " " + six.length());
                        System.stdout.println(Stats.sum([1, 2, 3, 4]) + " " + Stats.sum(Stats.squares(10)) + " " + Stats.median([9, 1, 7, 3, 5])
                                + " " + Stats.primesBelow(1000) + " " + Stats.histogram([11, 21, 3, 31, 45], 1) + " " + Stats.tail([5, 2, 9, 4])
                                + " " + Stats.grow(5) + " " + Stats.spread([2.5, -1.0, 4.25]));
                        return 0;
                    }
                }
                """), "10 285 5 168 3 1402 57 5.25 6\n10 285 5 168 3 1402 57 5.25\n");
        compileError("an index outside an array, while compiling", files("Stats", stats, "Main", main("""
                        array<int><<Stats.median(Stats.squares(0))>> xs;
                        """)), "Stats.median(Stats.squares(0)) can't be worked out while compiling: it threw IndexOutOfBoundsException: "
                + "index 0 out of range for an array of length 0 (Stats.seal:10)");
        output("a pure method makes arrays and changes them, and reads the arrays it is given", files("Main", """
                struct Main
                {
                    static pure int f(array<int> given)
                    {
                        array<int> mine = given.copy();
                        mine[0] = given[1] + given.length();
                        mine[1]++;
                        mine[2] += 3;
                        mine.sort();
                        mine.reverse();
                        int s = 0;
                        for (int x : given) { s += x; }
                        array<int> more = new array<int>(4);
                        more.fill(s);
                        return mine[0] * 1000 + more.slice(1, 3).length() * 100 + given.indexOf(2) * 10 + more[3];
                    }
                    const int F = f([1, 2, 3]);
                    static int main() { System.stdout.println(F + " " + f([1, 2, 3])); return 0; }
                }
                """), "6216 6216\n");
        compileError("a pure method can't change an array it was given", files("Main", """
                struct Main { static pure int f(array<int> xs) { xs[0] = 1; return 0; } static int main() { return 0; } }
                """), "a pure method can't change an array it was given: xs is its caller's, lent to it; change a copy, xs.copy()");
        compileError("nor change it through its methods", files("Main", """
                struct Main { static pure int f(array<int> xs) { xs.sort(); return 0; } static int main() { return 0; } }
                """), "a pure method can't change an array it was given: xs is its caller's");
        compileError("nor step its elements", files("Main", """
                struct Main { static pure int f(array<int> xs) { xs[0]++; return 0; } static int main() { return 0; } }
                """), "a pure method can't change an array it was given: xs is its caller's");
        compileError("arrays are the one kind of object a pure method uses", files("Main", """
                import seal.util.List;
                struct Main { static pure int f(array<int> xs) { List<int> l = xs.toList(); return l.size(); } static int main() { return 0; } }
                """), "a pure method can use an array's own methods, but not toList(), which makes a List, a class object");
        compileError("a pure method that never ends is stopped", files("Main", """
                struct Main
                {
                    static pure int spin(int n) { while (n > 0) { n++; n--; } return n; }
                    static int main() { array<int><<spin(1)>> xs; return 0; }
                }
                """), "it ran for more than 50,000,000 steps; does it end?");
    }

    private static String main(String body) {
        return Imports.of(body) + "struct Main\n{\n" + HELPERS + "    static int main()\n    {\n" + body + "        return 0;\n    }\n}\n";
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i] + ".seal", pairs[i + 1]);
        }
        return m;
    }

    private record Result(String output, String trap, String errors) {
    }

    /** Every program that runs here runs on the JVM too (docs/JVM.md §12). */
    private static final OnJvm ON_JVM = new OnJvm();
    private static String current = "";

    private static Result execute(Map<String, String> files) {
        Checker checker = Checker.analyze(files, LIBRARY);
        if (!checker.diagnostics().isEmpty()) {
            return new Result("", null, "errors: " + checker.diagnostics());
        }
        CodeGen.Result gen = CodeGen.generate(checker, "Main");
        if (!gen.errors().isEmpty()) {
            return new Result("", null, "errors: " + gen.errors());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VM vm = new VM(gen.program(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        vm.setMaxSteps(10_000_000);
        try {
            long code = vm.run();
            String out = bytes.toString(StandardCharsets.UTF_8);
            if (!OnJvm.usesVmOnly(files)) {
                ON_JVM.compare(current, checker, "Main", java.io.InputStream.nullInputStream(), true, out, code, null);
            }
            return new Result(out, null, null);
        } catch (Trap t) {
            String out = bytes.toString(StandardCharsets.UTF_8);
            if (!OnJvm.usesVmOnly(files)) {
                ON_JVM.compare(current, checker, "Main", java.io.InputStream.nullInputStream(), true, out, -1, t);
            }
            return new Result(out, t.getMessage(), null);
        } catch (RuntimeException e) {
            return new Result(bytes.toString(StandardCharsets.UTF_8), "crash: " + e, null);
        }
    }

    private static void output(String name, Map<String, String> files, String expected) {
        current = name;
        Result r = execute(files);
        check(r.errors() == null && r.trap() == null && r.output().equals(expected),
                name + "\n    expected " + show(expected) + "\n    got      " + show(r.output())
                        + (r.trap() == null ? "" : "\n    trap     " + r.trap())
                        + (r.errors() == null ? "" : "\n    " + r.errors()));
    }

    private static void compileError(String name, Map<String, String> files, String expected) {
        Result r = execute(files);
        check(r.errors() != null && r.errors().contains(expected),
                name + "\n    expected a compile error containing " + show(expected) + "\n    got " + r);
    }

    private static void trap(String name, Map<String, String> files, String expected) {
        current = name;
        Result r = execute(files);
        check(r.trap() != null && r.trap().contains(expected),
                name + "\n    expected trap containing " + show(expected) + "\n    got trap " + r.trap()
                        + (r.errors() == null ? "" : "\n    " + r.errors()));
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
