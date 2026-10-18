package io.github.thelinuxseal.seal.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free test runner: `java -cp out io.github.thelinuxseal.seal.check.CheckerTest`. */
public final class CheckerTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    // ================================================================ shared classes

    public static final String ARRAY = """
            struct Array<T><<int N>>
            struct Array<T><<int N, int K>>
            {
                int occupied;

                array<T><<N>> data<<int N>>;
                array<T><<N, K>> data<<int N, int K>>;

                public int getLength<<int N>>() { return N; }
                public int getLength<<int N, int K>>() { return N * K; }

                public float getOccFrac<<int N>>() { return (float)occupied / N; }
                public float getOccFrac<<int N, int K>>() { return (float)occupied / (N * K); }

                public int getOccupied() { return occupied; }

                public T get<<int N>>(int x) { return data[x]; }
                public T get<<int N, int K>>(int x, int y) { return data[x][y]; }

                public void set<<int N>>(int x, T value) { data[x] = value; }
            }
            """;

    public static final String STRING = """
            struct string<<>>
            struct string<<int N>>
            {
                int length;

                array<uint<<16>>><<N>> data<<int N>>;
                addr<uint<<16>>> data<<>>;
                int capacity<<>>;

                public uint<<16>> charAt<<int N>>(int i)
                {
                    return data[i];
                }

                public uint<<16>> charAt<<>>(int i)
                {
                    return &(data + i * sizeof(uint<<16>>));
                }

                public string<<N + K>> add<<int N>>(string<<int K>> other)
                {
                    string<<N + K>> result;
                    for (int i = 0; i < N; i += 1) {
                        result.data[i] = data[i];
                    }
                    for (int i = 0; i < K; i += 1) {
                        result.data[N + i] = other.data[i];
                    }
                    result.length = length + other.length;
                    return result;
                }
            }
            """;

    public static final String BIGUINT = """
            struct BigUInt<<int BITS>>
            {
                array<uint<<64>>><<(BITS + 63) / 64>> words;

                @overload(Operator.ADD)
                public BigUInt<<BITS>> add(BigUInt<<BITS>> o) { BigUInt<<BITS>> r; return r; }

                @overload(Operator.ADD_ASSIGN)
                public void addInPlace(BigUInt<<BITS>> o) { }

                @overload(Operator.LESS)
                public bit isLess(BigUInt<<BITS>> o) { return false; }

                @overload(Operator.EQUALS)
                public bit isEqual(BigUInt<<BITS>> o) { return true; }

                @overload(Operator.SHIFT)
                public BigUInt<<BITS>> shifted(int n) { BigUInt<<BITS>> r; return r; }
            }
            """;

    public static void main(String[] args) {
        specExamples();
        shapesAndGuards();
        valueBinders();
        constructors();
        fixedAndDynamic();
        inheritance();
        abstractAndInterfaces();
        addresses();
        typesAndConversions();
        packages();
        instantiation();
        operators();
        accessAndStatic();
        statements();
        files();
        dynamicPlacement();
        typeParameters();
        callsAndReturns();
        fixedPointErrors();
        uniqueRules();
        accessOnEveryKind();

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ tests

    private static void specExamples() {
        ok("Array", files("Array", ARRAY));
        ok("string", files("string", STRING));
        ok("BigUInt", files("BigUInt", BIGUINT));
        ok("Array used", files("Array", ARRAY, "Main", """
                struct Main
                {
                    void run()
                    {
                        Array<int><<64>> a = new Array();
                        Array<float><<4, 4>> m = new Array();
                        int n = a.getLength();
                        int x = a.get(3);
                        float f = m.get(1, 2);
                        float frac = m.getOccFrac();
                        a.set(0, 7);
                    }
                }
                """));
        ok("string add", files("string", STRING, "Main", """
                struct Main
                {
                    void run()
                    {
                        string<<3>> a = "abc";
                        string<<4>> b = "defg";
                        string<<7>> c = a.add(b);
                        string s = "hello";
                        uint<<16>> ch = s.charAt(0);
                    }
                }
                """));
    }

    private static void shapesAndGuards() {
        error("coverage rule", """
                struct Foo<<int N>>
                struct Foo<<int N, int K>>
                {
                    array<int><<N>> data<<int N>>;
                    int first() { return data[0]; }
                }
                """, "`data` does not exist in shape Foo<<int N, int K>>");
        ok("coverage satisfied", files("Foo", """
                struct Foo<<int N>>
                struct Foo<<int N, int K>>
                {
                    int size<<int N>>() { return N; }
                    int size<<int N, int K>>() { return N * K; }
                    int twice() { return size() * 2; }
                }
                """));
        error("all-or-nothing", """
                struct Foo<<int N>>
                {
                    int size;
                    int size<<int N>>() { return N; }
                }
                """, "`size` is used both with and without a shape guard");
        error("field and method share a name", """
                struct Foo
                {
                    int size;
                    int size() { return 1; }
                }
                """, "`size` is already a field");
        error("guard matches no header", """
                struct Foo<<int N>>
                {
                    int get<<int N, int K>>() { return N; }
                }
                """, "guard <<int, int>> matches no header of Foo");
        error("duplicate shape", """
                struct Foo<<int N>>
                struct Foo<<int M>>
                {
                }
                """, "duplicate shape: Foo<<int N>> is already declared");
        error("header with a fixed value", """
                struct Foo<<3>>
                {
                }
                """, "type headers declare values");
        error("mismatched type params", """
                struct Foo<T><<int N>>
                struct Foo<U><<int N, int K>>
                {
                }
                """, "every header of Foo must have the same type parameters <T>");
        ok("empty guard and per-shape types", files("Buf", """
                struct Buf<<>>
                struct Buf<<int N>>
                {
                    array<uint<<16>>><<N>> data<<int N>>;
                    array<uint<<16>>><<16>> data<<>>;
                    addr<int> extra<<>>;
                    public int count<<int N>>() { return N; }
                    public int count<<>>() { return 16; }
                    int total() { return count(); }
                }
                """, "Main", """
                struct Main
                {
                    void run()
                    {
                        Buf b;
                        Buf<<32>> c;
                        int n = b.count() + c.count();
                    }
                }
                """));
        error("per-shape type checked in unguarded code", """
                struct Buf<<>>
                struct Buf<<int N>>
                {
                    array<uint<<16>>><<N>> data<<int N>>;
                    addr<uint<<16>>> data<<>>;
                    uint<<16>> first() { return data[0]; }
                }
                """, "addresses cannot be indexed");
        error("shape selection", """
                struct Foo<<int N>>
                {
                    void f() { Foo<<1, 2>> x; }
                }
                """, "Foo<<1, 2>> matches no shape of Foo; its shapes are Foo<<int N>>");
        error("missing values", """
                struct Foo<<int N>>
                {
                    void f() { Foo x; }
                }
                """, "Foo needs <<...>> values; its shapes are Foo<<int N>>");
        ok("shape identity by value", files("Foo", """
                struct Foo<<int N>>
                {
                    void f()
                    {
                        Foo<<4>> a;
                        Foo<<2 + 2>> b = a;
                    }
                }
                """));
        error("distinct values are distinct types", """
                struct Foo<<int N>>
                {
                    void f()
                    {
                        Foo<<4>> a;
                        Foo<<5>> b = a;
                    }
                }
                """, "expected Foo<<5>>, found Foo<<4>>");
    }

    private static void valueBinders() {
        error("binder result type is checked", files("string", STRING, "Main", """
                struct Main
                {
                    void run()
                    {
                        string<<3>> a;
                        string<<4>> b;
                        string<<8>> c = a.add(b);
                    }
                }
                """), "expected string<<8>>, found string<<7>>");
        error("binder only in parameters", """
                struct Foo<<int N>>
                {
                    int<<int W>> f<<int N>>() { return 0; }
                }
                """, "binders are only allowed in parameter types");
        error("binder in a field", """
                struct Foo<<int N>>
                {
                    Foo<<int K>> other;
                }
                """, "binders are only allowed in parameter types");
        error("binder shadows struct value", """
                struct Foo<<int N>>
                {
                    void f(Foo<<int N>> other) { }
                }
                """, "binder N shadows an existing compile-time value");
        error("binder does not accept bare shape", files("string", STRING, "Main", """
                struct Main
                {
                    void run()
                    {
                        string<<3>> a;
                        string b;
                        a.add(b);
                    }
                }
                """), "expected string<<K>>, found string");
        ok("binders across parameters", files("Pair", """
                struct Pair<<int N>>
                {
                    public int both<<int N>>(Pair<<int K>> a, Pair<<K>> b) { return N + K; }
                    void f()
                    {
                        Pair<<2>> x;
                        Pair<<2>> y;
                        int r = both(x, y);
                    }
                }
                """));
    }

    private static void constructors() {
        error("constructor coverage", """
                struct Foo<<int N>>
                struct Foo<<int N, int K>>
                {
                    public new<<int N>>() { }
                }
                """, "no constructor for shape Foo<<int N, int K>>");
        ok("unguarded constructor covers all", files("Foo", """
                struct Foo<<int N>>
                struct Foo<<int N, int K>>
                {
                    int x;
                    public new() { x = 0; }
                }
                """));
        error("constructor arguments", files("Foo", """
                struct Foo
                {
                    int x;
                    public new(int v) { x = v; }
                }
                """, "Main", """
                struct Main
                {
                    void run() { Foo f = new Foo(true); }
                }
                """), "constructor argument 1: expected int, found bit");
        error("inline object needs no-arg constructor", files("Foo", """
                struct Foo
                {
                    int x;
                    public new(int v) { x = v; }
                }
                """, "Main", """
                struct Main
                {
                    void run() { Foo f; }
                }
                """), "variable f of type Foo needs an initializer");
        error("new needs a shape", files("Foo", """
                struct Foo<<int N>>
                {
                }
                """, "Main", """
                struct Main
                {
                    void run() { addr<int> p = new Foo(); }
                }
                """), "cannot tell which shape of Foo to create");
    }

    private static void fixedAndDynamic() {
        error("fixed struct contains itself", """
                struct Node
                {
                    Node next;
                }
                """, "struct Node contains itself inline; make it a class, or use addr<Node>");
        ok("fixed struct holds address of itself", files("Node", """
                struct Node
                {
                    addr<Node> next;
                    int value;
                }
                """));
        error("indirect inline cycle", files("A", """
                struct A
                {
                    B b;
                }
                """, "B", """
                struct B
                {
                    A a;
                }
                """), "struct A contains itself inline");
        String doc = """
                class Doc
                {
                    public int pages;
                    public int count() { return pages; }
                }
                """;
        ok("a class-typed field in a struct is a handle", files("Doc", doc, "Holder", """
                struct Holder
                {
                    Doc d;
                    array<int><<sizeof(Doc)>> check;   // a handle is 64 bits
                    void f() { d = new Doc(); int p = d.pages; }
                }
                """));
        ok("a class as a type argument is a handle", files("Doc", doc, "Array", ARRAY, "Holder", """
                struct Holder
                {
                    Array<Doc><<4>> docs;
                    void f() { docs.set(0, new Doc()); handle<Doc> same = docs.get(0); }
                }
                """));
        ok("handles to dynamic", files("Doc", doc, "Array", ARRAY, "Holder", """
                struct Holder
                {
                    Array<handle<Doc>><<4>> docs;
                    handle<Doc> one;
                    void run()
                    {
                        one = new Doc();
                        int p = one.pages + one.count();
                        one.pages = 3;
                        delete one;
                    }
                }
                """));
        error("addr of dynamic", files("Doc", doc, "Holder", """
                struct Holder
                {
                    addr<Doc> d;
                }
                """), "class objects can move, so they have no address; a Doc variable already refers to one");
        error("handle of fixed", """
                struct Holder
                {
                    handle<int> h;
                }
                """, "handle<...> needs a class, not int");
        error("no address inside dynamic object", files("Doc", doc, "Holder", """
                struct Holder
                {
                    void run()
                    {
                        handle<Doc> d = new Doc();
                        addr<int> p = *d.pages;
                    }
                }
                """), "cannot take an address inside a class object");
        error("handles are not dereferenced", files("Doc", doc, "Holder", """
                struct Holder
                {
                    void run()
                    {
                        handle<Doc> d = new Doc();
                        int x = &d;
                    }
                }
                """), "handles cannot be dereferenced");
        error("T[] and T[N] are no longer how arrays are written", """
                struct Foo
                {
                    int[] items;
                    int[4][2] grid;
                }
                """, "arrays are written array<int>, not int[]");
        error("T[N][K] has its new spelling suggested", """
                struct Foo
                {
                    int[4][2] grid;
                }
                """, "arrays are written array<int><<4, 2>>, not int[4][2]");
    }

    private static void inheritance() {
        String animals = """
                class Animal
                {
                    public int legs;
                    public int speak() { return 0; }
                }
                """;
        String dog = """
                class Dog extends(Animal)
                {
                    public new() { super(); legs = 4; }
                    public int bark() { return speak() + super.speak(); }
                }
                """;
        ok("inheritance", files("Animal", animals, "Dog", dog, "Main", """
                struct Main
                {
                    void run()
                    {
                        handle<Dog> d = new Dog();
                        handle<Animal> a = d;
                        handle<Dog> back = (handle<Dog>)a;
                        int n = d.legs + d.bark();
                        bit same = a == d;
                    }
                }
                """));
        error("no implicit downcast", files("Animal", animals, "Dog", dog, "Main", """
                struct Main
                {
                    void run()
                    {
                        handle<Animal> a = new Dog();
                        handle<Dog> d = a;
                    }
                }
                """), "expected handle<Dog>, found handle<Animal>");
        error("structs cannot extend", """
                struct Foo extends(Object)
                {
                }
                """, "only classes can use extends; a struct uses composition instead");
        error("cannot extend fixed", files("Base", "struct Base { }", "Foo", """
                class Foo extends(Base)
                {
                }
                """), "cannot extend struct Base; parents must be classes");
        error("inheritance cycle", files("A", "class A extends(B) { }", "B", "class B extends(A) { }"),
                "class A inherits from itself");
        ok("shape-dependent parents", files("Base", "class Base { public int base; }",
                "Circle", "class Circle { public int radius; }",
                "Square", "class Square { public int side; }",
                "Shape", """
                class Shape<<int KIND>>
                    extends(Base)
                    extends<<0>>(Circle)
                    extends<<1>>(Square)
                {
                }
                """, "Main", """
                struct Main
                {
                    void run()
                    {
                        handle<Shape<<0>>> c = new Shape();
                        handle<Shape<<1>>> s = new Shape();
                        handle<Shape<<7>>> b = new Shape();
                        int r = c.radius + s.side + b.base;
                        handle<Circle> asCircle = c;
                    }
                }
                """));
        error("parent follows shape", files("Circle", "class Circle { public int radius; }",
                "Square", "class Square { public int side; }",
                "Shape", """
                class Shape<<int KIND>>
                    extends<<0>>(Circle)
                    extends<<1>>(Square)
                {
                }
                """, "Main", """
                struct Main
                {
                    void run()
                    {
                        handle<Shape<<1>>> s = new Shape();
                        int r = s.radius;
                    }
                }
                """), "Shape has no member radius");
        error("duplicate extends guard", files("A", "class A { }", "Foo", """
                class Foo<<int K>> extends<<0>>(A) extends<<0>>(A)
                {
                }
                """), "duplicate extends guard");
    }

    private static void abstractAndInterfaces() {
        ok("a class implements an interface and an abstract parent", files("Drawable", "interface Drawable { void draw(); }", "Shape", "abstract class Shape { public abstract double area(); }", "Circle", """
                class Circle extends(Shape) implements(Drawable)
                {
                    public double area() { return 3.0; }
                    public void draw() { }
                }
                """, "Main", """
                struct Main
                {
                    static void run()
                    {
                        Drawable d = new Circle();
                        Shape s = new Circle();
                        d.draw();
                        double a = s.area();
                        Circle c = (Circle)d;
                        Drawable again = (Drawable)s;
                    }
                }
                """));
        ok("interfaces extend interfaces, and generic ones bind", files("Drawable", "interface Drawable { void draw(); }",
                "Thing", "interface Thing extends(Drawable) { int id(); }",
                "Comparable", "interface Comparable<T> { int compareTo(T other); }",
                "Box", """
                class Box implements(Thing, Comparable<Box>)
                {
                    public void draw() { }
                    public int id() { return 1; }
                    public int compareTo(Box other) { return 0; }
                }
                """, "Main", """
                struct Main
                {
                    static void run()
                    {
                        Thing t = new Box();
                        Drawable d = t;
                        t.draw();
                        Comparable<Box> c = new Box();
                        int r = c.compareTo(new Box());
                    }
                }
                """));
        ok("an abstract class may leave interface methods to subclasses", files("Drawable", "interface Drawable { void draw(); }",
                "Base", "abstract class Base implements(Drawable) { public void twice() { draw(); draw(); } }",
                "Leaf", "class Leaf extends(Base) { public void draw() { } }"));
        error("missing interface method", files("Drawable", "interface Drawable { void draw(); }", "Circle", "class Circle implements(Drawable) { }"),
                "Circle must implement Drawable.draw(), or be declared `abstract class Circle`");
        error("missing abstract method", files("Shape", "abstract class Shape { public abstract double area(); }", "Circle", "class Circle extends(Shape) { }"),
                "Circle must implement Shape.area(), or be declared `abstract class Circle`");
        error("wrong return type", files("Drawable", "interface Drawable { void draw(); }", "Circle", "class Circle implements(Drawable) { public int draw() { return 1; } }"),
                "Circle.draw returns int, but Drawable.draw() returns void");
        error("implementation must be public", files("Drawable", "interface Drawable { void draw(); }", "Circle", "class Circle implements(Drawable) { void draw() { } }"),
                "Circle.draw implements Drawable.draw, so it must be public");
        error("handle parameters are shown by name", files("Visitor", "interface Visitor { void visit(Visitor v); }",
                "V", "class V implements(Visitor) { }"),
                "V must implement Visitor.visit(Visitor), or be declared `abstract class V`");
        error("new on an abstract class", files("Shape", "abstract class Shape { public abstract double area(); }", "Main", "struct Main { static void run() { Shape s = new Shape(); } }"),
                "cannot create abstract class Shape with new; create a class that extends it");
        error("new on an interface", files("Drawable", "interface Drawable { void draw(); }", "Main", "struct Main { static void run() { Drawable d = new Drawable(); } }"),
                "cannot create interface Drawable with new; create a class that implements it");
        error("abstract method in a concrete class", "class Main { public abstract void f(); }",
                "f is abstract, so Main must be too: abstract class Main");
        error("abstract method with a body", "abstract class Main { public abstract void f() { } }",
                "abstract method f can't have a body; end it with ';'");
        error("private abstract method", "abstract class Main { abstract void f(); }",
                "abstract method f must be public: subclasses implement it");
        error("method without a body", "class Main { void f(); }",
                "method f needs a body, or `abstract` in an abstract class");
        error("super call to an abstract method", files("Shape", "abstract class Shape { public abstract double area(); }", "Circle", """
                class Circle extends(Shape)
                {
                    public double area() { return super.area(); }
                }
                """), "area is abstract in Shape; there is no implementation to call");
        error("interface with a field", "interface Main { int x; }", "interfaces can't have fields; only method signatures");
        error("interface with a body", "interface Main { void f() { } }",
                "interface methods have no body; end f(...) with ';' and implement it in the classes");
        error("interface with a constructor", "interface Main { new() { } }", "interfaces can't have constructors");
        error("interface with a static method", "interface Main { static void f(); }", "interfaces can't have static methods");
        error("interface with shapes", "interface Main<<int N>> { void f(); }",
                "an interface has a single shape; it can't have value parameters <<...>>");
        error("extends an interface", files("Drawable", "interface Drawable { void draw(); }", "Circle", "class Circle extends(Drawable) { }"),
                "Drawable is an interface; use implements(Drawable) instead of extends");
        error("implements a class", files("Shape", "abstract class Shape { public abstract double area(); }", "Circle", "class Circle implements(Shape) { public double area() { return 1.0; } }"),
                "Shape is a class, not an interface; use extends(Shape) for a parent class");
        error("an interface extends a class", files("Shape", "abstract class Shape { public abstract double area(); }", "Main", "interface Main extends(Shape) { }"),
                "an interface can only extend interfaces, not Shape");
        error("abstract struct", "abstract struct Main { }", "only classes can be abstract, not a struct");
        error("abstract interface", "abstract interface Main { }", "interfaces are always abstract; leave out `abstract`");
        error("struct implements", files("Drawable", "interface Drawable { void draw(); }", "Main", "struct Main implements(Drawable) { }"),
                "only classes can implement interfaces; a struct uses composition and generics instead");
        error("unique implements", files("Drawable", "interface Drawable { void draw(); }", "Main", "unique Main implements(Drawable) { }"),
                "only classes can implement interfaces; a unique uses composition and generics instead");
        error("abstract method in a struct", "struct Main { public abstract void f(); }",
                "only classes can have abstract methods, not a struct");
        error("abstract on a field", "abstract class Main { public abstract int x; }", "only methods can be abstract");
        error("interface cycle", files("A", "interface A extends(B) { }", "B", "interface B extends(A) { }"),
                "class A inherits from itself");
        ok("instanceof bindings are visible where the test passed", files("Drawable", "interface Drawable { void draw(); }", "Shape", "abstract class Shape { public abstract double area(); }", "Circle",
                "class Circle extends(Shape) implements(Drawable) { public double area() { return 1.0; } public void draw() { } }",
                "Main", """
                struct Main
                {
                    static void run(Drawable d)
                    {
                        if (d instanceof Circle c) { double a = c.area(); }
                        bit b = d instanceof Shape s && s.area() > 0.5;
                        while (d instanceof Circle w) { d = null; }
                        for (int i = 0; d instanceof Circle f && i < 2; i += 1) { f.draw(); }
                    }
                }
                """));
        error("[]= takes an index and a value", """
                struct Main { @overload(Operator.INDEX_SET) public void set(int i) { } }
                """, "Operator.INDEX_SET (x[i] = v) takes two parameters, the index and the value");
        ok("nested types by their bare and dotted names", files("Outer", """
                class Outer
                {
                    public static struct Point { public int x; int secret; }
                    struct Hidden { public int y; }
                    public int f(Point p, Hidden h) { return p.secret + h.y; }   // Outer sees Point's private secret
                }
                """, "Main", """
                struct Main { static int run() { Outer.Point p; return p.x; } }
                """));
        error("a private nested type outside its outer type", files("Outer", """
                class Outer { struct Hidden { public int y; } }
                """, "Main", "struct Main { static void run() { Outer.Hidden h; } }"),
                "Outer.Hidden is private to Outer; make it public to use it outside");
        error("a nested type doesn't see its outer type's parameters", """
                class Main<T> { public static class Box { public T item; } }
                """, "T is a type parameter of Main; a nested type doesn't see its outer type's parameters (give it its own: Box<T>)");
        error("a nested type declared twice", "class Main { struct A { } struct A { } }", "Main.A is declared twice");
        error("a nested type named like a member", "class Main { int A; struct A { } }",
                "Main already has a member named A; a nested type needs a name of its own");
        error("an unknown nested type", "struct Main { static void run() { Main.Nope x; } }", "unknown type Main.Nope");
        error("duplicate enum constant", "enum Main { A, B, A }", "duplicate enum constant A");
        error("reserved enum constant", "enum Main { A, values }", "enum constant can't be named values");
        error("enum with type parameters", "enum Main<T> { A }", "an enum has no type parameters, shapes, parents or interfaces");
        error("unknown enum constant", files("Color", "enum Color { RED }", "Main",
                "struct Main { static void run() { Color c = Color.PINK; } }"), "PINK");
        error("mixed switch styles", "struct Main { static void run(int x) { switch (x) { case 1 -> { } case 2: break; } } }",
                "a switch uses either `case x:` or `case x ->` throughout, not both");
        error("duplicate case", "struct Main { static void run(int x) { switch (x) { case 1: break; case 1: break; } } }",
                "duplicate case 1");
        error("non-constant case", "struct Main { static void run(int x, int y) { switch (x) { case y: break; } } }",
                "a case label must be a constant");
        error("two defaults", "struct Main { static void run(int x) { switch (x) { default: break; default: break; } } }",
                "a switch has only one default");
        error("switch on a double", "struct Main { static void run(double d) { switch (d) { default: break; } } }",
                "switch works on integers, characters, strings and enums, not double");
        error("continue in a switch outside a loop", "struct Main { static void run(int x) { switch (x) { default: continue; } } }",
                "continue outside a loop");
        ok("a switch whose cases all return returns", files("Main", "struct Main { static int f(int x) { switch (x) { case 1 -> { return 1; } default -> { return 2; } } } }"));
        error("for-each over a number", "struct Main { static void run() { int n = 3; for (int x : n) { } } }",
                "for-each goes over an array, a string, an Iterable, or an object with size() and get(int); int is none of those");
        error("for-each variable of the wrong type", "struct Main { static void run() { array<int><<2>> a; for (bit x : a) { } } }",
                "initializer of x: expected bit, found int");
        error("for-each variable is scoped to the loop", "struct Main { static void run() { array<int><<2>> a; for (int x : a) { } x = 1; } }",
                "unknown name x");
        error("for-each over uniques would copy them", files("F", "unique F { public new() { } }", "Main",
                "struct Main { static void run() { array<F><<2>> fs; for (F f : fs) { } } }"),
                "F is unique and can't be copied");
        error("a binding is not visible in else", files("Drawable", "interface Drawable { void draw(); }", "Main", """
                struct Main { static void run(Drawable d) { if (d instanceof Drawable x) { } else { x.draw(); } } }
                """), "unknown name x");
        error("a binding is not visible after the if", files("Drawable", "interface Drawable { void draw(); }", "Main", """
                struct Main { static void run(Drawable d) { if (d instanceof Drawable x) { } x.draw(); } }
                """), "unknown name x");
        error("a binding is not visible across ||", files("Drawable", "interface Drawable { void draw(); }", "Main", """
                struct Main { static void run(Drawable d) { bit b = d instanceof Drawable x || x == null; } }
                """), "unknown name x");
        error("instanceof on a number", files("Drawable", "interface Drawable { void draw(); }", "Main", """
                struct Main { static void run() { int n = 1; bit b = n instanceof Drawable; } }
                """), "instanceof needs an object (a class or interface handle), not int");
        error("instanceof a struct", files("P", "struct P { }", "Main", """
                struct Main { static void run(Object o) { bit b = o instanceof P; } }
                """), "instanceof tests for a class or interface, not P");
        error("unrelated classes", files("A", "class A { }", "B", "class B { }", "Main", """
                struct Main { static void run(A a) { bit b = a instanceof B; } }
                """), "handle<A> can never be a B");
        error("implicit downcast from an interface", files("Drawable", "interface Drawable { void draw(); }", "Circle",
                "class Circle implements(Drawable) { public void draw() { } }", "Main", """
                struct Main { static void run() { Drawable d = new Circle(); Circle c = d; } }
                """), "expected handle<Circle>, found handle<Drawable>");
    }

    private static void addresses() {
        ok("address operators", files("Main", """
                struct Main
                {
                    int field;
                    void run()
                    {
                        int x = 5;
                        addr<int> p = *x;
                        &p = 32;
                        int v = &p;
                        addr<addr<int>> pp = *p;
                        int w = &(&pp);
                        addr<int> buf = new<int>(10);
                        &(buf + 3 * sizeof(int)) = 1;
                        addr<int> f = *field;
                        bit isNull = p == null;
                        addr<void> raw = (addr<void>)p;
                        delete buf;
                    }
                }
                """));
        error("address-of is not assignable", """
                struct Main
                {
                    void run() { int x; *x = 3; }
                }
                """, "*x is an address, not a location; to store through an address p write &p = value");
        error("dereference needs an address", """
                struct Main
                {
                    void run() { int x; int y = &x; }
                }
                """, "& dereferences an address, but this is a int; to take an address use *x");
        error("address of a temporary", """
                struct Main
                {
                    void run() { addr<int> p = *(1 + 2); }
                }
                """, "*x takes the address of a variable, field or element");
        error("member access through address", """
                struct Point
                {
                    public int x;
                    void run(addr<Point> p) { int a = p.x; }
                }
                """, "cannot use . on an address; dereference first: (&p).x");
        ok("member access after dereference", files("Point", """
                struct Point
                {
                    public int x;
                    void run(addr<Point> p) { (&p).x = 3; int a = (&p).x; }
                }
                """));
        ok("labeled break and continue", files("Main", """
                struct Main
                {
                    static int first(int n)
                    {
                        outer:
                        while (true) {
                            for (int i = 0; i < n; i++) {
                                if (i == 3) { return i; }
                                if (i == 5) { continue outer; }
                            }
                        }
                    }

                    void run()
                    {
                        rows:
                        for (int r = 0; r < 3; r++) {
                            cols:
                            for (int c = 0; c < 3; c++) {
                                if (c > r) { continue rows; }
                                if (c == 2) { break cols; }
                                if (r == 2) { break rows; }
                            }
                        }
                    }
                }
                """));
        ok("switch expressions (§41.3)", files("Main", """
                struct Main
                {
                    static int size(int n)
                    {
                        return switch (n) {
                            case 0 -> 0;
                            case 1, 2, 3 -> 1;
                            default -> {
                                int digits = 0;
                                while (n > 0) { digits++; n = n / 10; }
                                yield digits;
                            }
                        };
                    }
                    void run() { double d = 1 + switch (size(5)) { case 1 -> 0.5; default -> 2; }; }
                }
                """));
        error("a switch expression needs a default", """
                struct Main
                {
                    int f(int n) { return switch (n) { case 1 -> 10; }; }
                }
                """, "a switch expression needs a value for every case: add a default");
        error("a case block ends with yield", """
                struct Main
                {
                    int f(int n) { return switch (n) { default -> { int z = 2; } }; }
                }
                """, "a case block of a switch expression ends with yield and its value, or a throw");
        error("a case can't return", """
                struct Main
                {
                    int f(int n) { int x = switch (n) { default -> { return 1; } }; return x; }
                }
                """, "a switch expression's case can't return; give its value with yield");
        error("a case can't break out of a loop around the switch", """
                struct Main
                {
                    void f() { for (;;) { int x = switch (1) { default -> { break; } }; } }
                }
                """, "break can't leave a switch expression's case; give its value with yield");
        error("yield outside a switch expression", """
                struct Main
                {
                    void f() { yield 3; }
                }
                """, "yield gives a switch expression's value, so it goes in a case block of one");
        error("a switch expression's cases must meet in a type", """
                struct Main
                {
                    void f(int n) { int x = switch (n) { case 1 -> 1; default -> true; }; }
                }
                """, "the cases of a switch expression give different types");
        error("break outer makes a loop's end reachable", """
                struct Main
                {
                    static int f()
                    {
                        outer:
                        while (true) {
                            for (int i = 0; i < 3; i++) { if (i == 2) { break outer; } }
                        }
                    }
                }
                """, "method f can reach its end without returning");
        error("a label goes on a loop", """
                struct Main
                {
                    void run() { int x = 0; block: if (x > 0) { x++; } }
                }
                """, "a label goes on a loop (for, for-each or while), not on this statement");
        error("a label can't be reused inside its loop", """
                struct Main
                {
                    void run() { outer: for (;;) { outer: for (;;) { } } }
                }
                """, "label outer is already used by a loop around this one");
        error("break with an unknown label", """
                struct Main
                {
                    void run() { outer: for (;;) { break inner; } }
                }
                """, "break inner: no loop around it is labeled inner; the labels here are outer");
        error("a lambda can't continue an outer loop", files("Task", "interface Task { void go(); }", "Main", """
                struct Main
                {
                    void run() { outer: for (;;) { Task t = () -> { continue outer; }; } }
                }
                """), "continue outer: no loop around it is labeled outer");
        error("delete an inline object", """
                struct Main
                {
                    void run() { int x; delete x; }
                }
                """, "delete needs an addr<...> or handle<...>, not int");
    }

    private static void packages() {
        Map<String, String> lib = files(
                "com/example/util/Vec2", """
                        package com.example.util;

                        struct Vec2
                        {
                            public int x;
                            public static struct Unit { public static int one() { return 1; } }
                        }
                        """,
                "com/example/util/Tools", """
                        package com.example.util;

                        class Tools { public static Vec2 zero() { Vec2 v; return v; } }
                        """,
                "org/other/Tools", """
                        package org.other;

                        class Tools { public static int n() { return 2; } }
                        """);
        Map<String, String> program = new LinkedHashMap<>(lib);
        program.putAll(files("com/example/game/Main", """
                package com.example.game;

                import com.example.util.Vec2;
                import com.example.util.*;

                struct Main
                {
                    void run()
                    {
                        Vec2 v = Tools.zero();
                        int one = Vec2.Unit.one();
                        int two = org.other.Tools.n();
                        com.example.util.Vec2 w = com.example.util.Tools.zero();
                        Helper h;
                    }
                }
                """, "com/example/game/Helper", """
                package com.example.game;

                struct Helper { }
                """));
        ok("packages: imports by name and with *, the same package, and full names (§2.4)", program);
        Map<String, String> ambiguous = new LinkedHashMap<>(lib);
        ambiguous.putAll(files("app/Main", """
                package app;

                import com.example.util.*;
                import org.other.*;

                struct Main
                {
                    void run() { int n = Tools.n(); }
                }
                """));
        error("two * imports that bring one name", ambiguous, "unknown name Tools: it could be com.example.util.Tools or"
                + " org.other.Tools, which * imports bring; import the one you mean by name");
        Map<String, String> missing = new LinkedHashMap<>(lib);
        missing.putAll(files("app/Main", """
                package app;

                struct Main
                {
                    void run() { Vec2 v; }
                }
                """));
        error("a type from another package needs an import", missing, "unknown type Vec2; import it: import com.example.util.Vec2;");
        error("an import of a type that doesn't exist", files("app/Main", """
                package app;

                import com.example.Nope;

                struct Main { }
                """), "no type com.example.Nope to import");
        error("an import of a package that doesn't exist", files("app/Main", """
                package app;

                import com.example.*;

                struct Main { }
                """), "no package com.example to import from");
        error("a package goes in its folder", files("Main", """
                package app;

                struct Main { }
                """), "package app goes in a folder app, but Main is in no folder");
        error("a type in a package can't use one in no package", files("Loose", "struct Loose { }", "app/Main", """
                package app;

                struct Main { Loose l; }
                """), "unknown type Loose; Loose is in no package, and as in Java, a type in a package can't use one that isn't");
        Map<String, String> clash = new LinkedHashMap<>(lib);
        clash.putAll(files("app/Main", """
                package app;

                import com.example.util.Tools;
                import org.other.Tools;

                struct Main { }
                """));
        error("two imports of one name", clash, "import org.other.Tools clashes with the import of com.example.util.Tools");
        Map<String, String> statics = files("com/example/util/Calc", """
                package com.example.util;

                class Calc
                {
                    public const int SIZE = 3;
                    public static int count = 0;
                    public static int twice(int x) { return x * 2; }
                    public static int twice(int x, int y) { return x * y * 2; }
                    static int hidden() { return 0; }
                    public int instanceOnly() { return 1; }
                    public static struct Pair { public int a; }
                }
                """, "com/example/util/Other", """
                package com.example.util;

                class Other { public const int SIZE = 4; public static int thrice(int x) { return x * 3; } }
                """);
        Map<String, String> usesStatics = new LinkedHashMap<>(statics);
        usesStatics.putAll(files("app/Main", """
                package app;

                import static com.example.util.Calc.twice;
                import static com.example.util.Calc.SIZE;
                import static com.example.util.Calc.count;
                import static com.example.util.Calc.Pair;
                import static com.example.util.Other.*;

                struct Main
                {
                    static int thrice(int x) { return x; }   // its own method comes first
                    void run()
                    {
                        int a = twice(1) + twice(2, 3) + thrice(4) + SIZE;
                        count = count + 1;
                        array<int><<SIZE>> xs;
                        Pair p;
                    }
                }
                """));
        ok("static imports: methods, constants, fields and nested types (§2.4)", usesStatics);
        Map<String, String> badStatics = new LinkedHashMap<>(statics);
        badStatics.putAll(files("app/Main", """
                package app;

                import static com.example.util.Calc.instanceOnly;
                import static com.example.util.Calc.hidden;
                import static com.example.util.Calc.nothing;

                struct Main { }
                """));
        error("a static import of an instance member", badStatics, "instanceOnly of Calc is not static, so it can't be imported");
        error("a static import of a private member", badStatics, "hidden of Calc is private");
        error("a static import of a member that doesn't exist", badStatics, "Calc has no static member nothing");
        Map<String, String> twoConsts = new LinkedHashMap<>(statics);
        twoConsts.putAll(files("app/Main", """
                package app;

                import static com.example.util.Calc.*;
                import static com.example.util.Other.*;

                struct Main { void run() { int n = SIZE; } }
                """));
        error("two static * imports with a field of one name", twoConsts,
                "SIZE could be com.example.util.Calc.SIZE or com.example.util.Other.SIZE, which static imports bring");
    }

    private static void typesAndConversions() {
        ok("numbers widen as in Java (§41.6)", files("Main", """
                struct Main
                {
                    static double half(double x) { return x / 2; }
                    void run()
                    {
                        int i; float f; int<<8>> b; uint u; int<<64>> big; char c; double d;
                        fixed<<16, 16>> fx; ufixed<<8, 8>> ufx;
                        float r = i + f;
                        int sum = i + b;
                        big = i;
                        big = u;
                        d = i;
                        d = f;
                        d = fx;
                        fx = b;
                        fx = ufx;
                        d = half(i);
                        double avg = (double)sum / 2 + i;
                        bit less = i < d;
                        int<<64>> e = c;
                        double cd = c;
                        int x = b < 0 ? b : i;
                        int y = c + b;
                    }
                }
                """));
        ok("an int and a uint meet in an int<<64>>, as Java promotes to long", files("Main", """
                struct Main
                {
                    void run() { int a; uint b; int<<64>> c = a + b; bit less = a < b; int<<8>> s; uint<<8>> u; int d = s + u; }
                }
                """));
        error("a uint<<64>> and an int have no common type", """
                struct Main
                {
                    void run() { int a; uint<<64>> b; int<<64>> c = a + b; }
                }
                """, "operator + needs matching types, not int and uint<<64>>; use a cast");
        ok("compound assignments narrow back, as Java's do (§41.6)", files("Main", """
                struct Main
                {
                    void run()
                    {
                        int i; int<<8>> b; int<<64>> big; uint u; char c; float f; double d; fixed<<8, 8>> fx;
                        i += 2.5;
                        i *= d;
                        i += big;
                        b += i;
                        b += 1000;
                        b |= i;
                        u += i;
                        c += 1;
                        c += i;
                        f += d;
                        fx *= d;
                    }
                }
                """));
        ok("numbers narrow where they are stored or passed, as C's do (§41.6)", files("Main", """
                struct Main
                {
                    static int<<8>> clip(int x) { return x; }
                    static void take(char c) { }
                    void run()
                    {
                        int<<64>> big; int i = big;
                        double d; float f = d;
                        uint<<64>> u = i;
                        int a = i + 2.5;
                        int b = 2.5;
                        fixed<<8, 0>> x; int fromFixed = x;
                        fixed<<8, 8>> y; fixed<<16, 4>> fewer = y;
                        fixed<<16, 16>> fromInt = i;
                        char c = i;
                        uint<<16>> u16 = c;
                        take(i);
                        new Main().run();
                    }
                }
                """));
        error("a floating-point constant must fit when narrowed", """
                struct Main
                {
                    void run() { int<<8>> b = 300.5; }
                }
                """, "initializer of b: expected int<<8>>, found floating-point constant");
        error("a bit is not a number", """
                struct Main
                {
                    void run() { bit flag; int i = flag; }
                }
                """, "initializer of i: expected int, found bit");
        ok("an argument narrows only when no overload takes it as it is", files("Main", """
                struct Main
                {
                    static int<<8>> f(int<<8>> x) { return x; }
                    static double f(double x) { return x; }
                    static void g(int<<8>> x) { }
                    void run() { int i; double d = f(i); g(i); g(2.5); }
                }
                """));
        error("several overloads that would narrow are ambiguous", """
                struct Main
                {
                    static void f(int<<8>> x) { }
                    static void f(float x) { }
                    void run() { double d; f(d); }
                }
                """, "ambiguous call to f: no overload takes (double) as they are, and 2 do once narrowed; use a cast");
        error("a compound assignment needs an operator the types have", """
                struct Main
                {
                    void run() { int<<64>> a; uint<<64>> b; a += b; }
                }
                """, "operator + needs matching types, not int<<64>> and uint<<64>>");
        Map<String, String> casts = files("Main", """
                struct Main
                {
                    static double half(double x) { return x / 2; }

                    void run()
                    {
                        int i; int<<8>> b; char c; double d; float f; int<<64>> big;
                        d = i;
                        big = i + big;
                        d = half(b);
                        f = i;
                        d = c < 1 ? f : d;
                        i += d;
                        c += 1;
                        b += 1;
                        i = (int)d;
                        d = 1;
                        f = b;
                    }
                }
                """);
        warnings("-WautoCast reports every number converted without a cast", Set.of(Warning.AUTO_CAST), casts,
                "8: [-WautoCast] implicit cast from int to double in assignment",
                "9: [-WautoCast] implicit cast from int to int<<64>> in operand of +",
                "10: [-WautoCast] implicit cast from int<<8>> to double in argument 1 of half",
                "11: [-WautoCast] implicit cast from int to float in assignment may round",
                "12: [-WautoCast] implicit cast from float to double in ?:",
                "13: [-WautoCast] implicit cast from int to double in operand of +=",
                "13: [-WautoCast] implicit cast from double to int in compound assignment may lose data",
                "14: [-WautoCast] implicit cast from char to int in operand of +=",
                "14: [-WautoCast] implicit cast from int to char in compound assignment",
                "18: [-WautoCast] implicit cast from int<<8>> to float in assignment");
        warnings("-WautoCastLossy reports only casts that may lose data", Set.of(Warning.AUTO_CAST_LOSSY), casts,
                "11: [-WautoCastLossy] implicit cast from int to float in assignment may round",
                "13: [-WautoCastLossy] implicit cast from double to int in compound assignment may lose data");
        warnings("-Wall reports each cast once, as lossy where it may lose data", Set.of(Warning.values()), casts,
                "8: [-WautoCast] implicit cast from int to double in assignment",
                "9: [-WautoCast] implicit cast from int to int<<64>> in operand of +",
                "10: [-WautoCast] implicit cast from int<<8>> to double in argument 1 of half",
                "11: [-WautoCastLossy] implicit cast from int to float in assignment may round",
                "12: [-WautoCast] implicit cast from float to double in ?:",
                "13: [-WautoCast] implicit cast from int to double in operand of +=",
                "13: [-WautoCastLossy] implicit cast from double to int in compound assignment may lose data",
                "14: [-WautoCast] implicit cast from char to int in operand of +=",
                "14: [-WautoCast] implicit cast from int to char in compound assignment",
                "18: [-WautoCast] implicit cast from int<<8>> to float in assignment");
        Map<String, String> library = files("Lib", """
                struct Lib
                {
                    public static int addHalf(int i) { i += 0.5; return i; }
                }
                """);
        Map<String, String> program = files("Main", """
                struct Main
                {
                    void run() { int i = Lib.addHalf(1); }
                }
                """);
        Checker withLibrary = Checker.analyze(program, library, Set.of(Warning.values()));
        check(withLibrary.diagnostics().isEmpty() && withLibrary.warnings().isEmpty(),
                "no warnings for the library's code: got " + withLibrary.diagnostics() + withLibrary.warnings());
        error("literal does not fit", """
                struct Main
                {
                    void run() { int<<8>> x = 300; }
                }
                """, "integer 300 does not fit in int<<8>>");
        error("array literal of the wrong size", """
                struct Main
                {
                    void run() { array<int><<3>> a = [1, 2]; }
                }
                """, "initializer of a: the array literal has 2 elements, but array<int><<3>> holds 3");
        error("array element does not fit", """
                struct Main
                {
                    void run() { array<uint<<8>>><<2>> a = [1, 300]; }
                }
                """, "integer 300 does not fit in uint<<8>>");
        error("empty array literal", """
                struct Main
                {
                    void run() { array<int><<1>> a = []; }
                }
                """, "an array literal needs at least one element");
        error("array elements without a common type", """
                struct Main
                {
                    void run() { int d; double e; int x = [d, e][0]; }
                }
                """, "array elements don't share a type (int, double); declare the array's type, as in `array<T><<2>> a = [...];`");
        error("array literal argument of the wrong size", """
                struct Main
                {
                    static void f(array<int><<3>> xs) { }
                    void run() { f([1, 2]); }
                }
                """, "argument 1 of f: expected array<int><<3>>, found array literal of 2 elements");
        error("character literal does not fit", """
                struct Main
                {
                    void run() { int<<8>> x = '\\u00e9'; }
                }
                """, "integer 233 does not fit in int<<8>>");
        ok("literals adapt", files("Main", """
                struct Main
                {
                    void run()
                    {
                        int<<8>> a = 100;
                        uint<<4>> b = 15;
                        int<<8>> c = a + 1;
                        float f = 2;
                        double d = 1.5;
                        float g = f + 1.5;
                    }
                }
                """));
        error("condition must be bit", """
                struct Main
                {
                    void run() { int x; if (x) { } }
                }
                """, "condition must be a bit, not int; compare explicitly, e.g. x != 0");
        error("bit is not uint<<1>>", """
                struct Main
                {
                    void run() { uint<<1>> u = 1; bit b = u; }
                }
                """, "expected bit, found uint<<1>>");
        ok("bits and slices", files("Main", """
                struct Main
                {
                    void run()
                    {
                        uint x = 255;
                        bit b = x[3];
                        uint<<4>> nibble = x[4..8];
                        bit both = b && true || !b;
                    }
                }
                """));
        error("slice bounds must be constant", """
                struct Main
                {
                    void run() { uint x; int i; uint<<4>> n = x[i..8]; }
                }
                """, "`i` is not a compile-time constant");
        error("unknown type", """
                struct Main
                {
                    Missing m;
                }
                """, "unknown type Missing");
        error("int width too large", """
                struct Main
                {
                    int<<65>> big;
                }
                """, "integer width must be between 1 and 64, not 65");
        error("wrong cast", """
                struct Main
                {
                    void run() { addr<int> p; int x = (int)p; }
                }
                """, "cannot cast addr<int> to int");
        error("shift amount must be int", """
                struct Main
                {
                    void run() { uint x; uint n; uint y = x <<< n; }
                }
                """, "the shift amount must be an int, not uint");
    }

    private static void instantiation() {
        String buf = """
                struct Buf<<int N>>
                {
                    array<uint<<8>>><<N - 1>> data;
                }
                """;
        error("negative size at instantiation", files("Buf", buf, "Main", """
                struct Main
                {
                    Buf<<0>> b;
                }
                """), "in Buf<<0>>: an array's length can't be negative: -1");
        ok("valid instantiation", files("Buf", buf, "Main", """
                struct Main
                {
                    Buf<<16>> b;
                }
                """));
        error("width from value", files("Word", """
                struct Word<<int W>>
                {
                    uint<<W>> value;
                }
                """, "Main", """
                struct Main
                {
                    Word<<100>> w;
                }
                """), "in Word<<100>>: integer width must be between 1 and 64, not 100");
        ok("sizeof", files("Main", """
                struct Main
                {
                    int<<8>> a;
                    int b;
                }
                """, "Sizes", """
                struct Sizes<<int N>>
                {
                    array<uint<<8>>><<N>> bytes;
                    void f()
                    {
                        Sizes<<sizeof(Main)>> s;
                        Sizes<<sizeof(array<uint<<16>>><<3>>)>> t;
                    }
                }
                """));
        // Main is int<<8>> then int (32-bit aligned): 8 bits, padded to 32, plus 32 = 64.
        error("sizeof value", files("Main", """
                struct Main
                {
                    int<<8>> a;
                    int b;
                }
                """, "Check", """
                struct Check<<int N>>
                {
                    void f()
                    {
                        Check<<sizeof(Main)>> s;
                        Check<<65>> t = s;
                    }
                }
                """), "expected Check<<65>>, found Check<<64>>");
        error("NaN argument", """
                struct Main<<double D>>
                {
                    void f() { Main<<0.0 / 0.0>> m; }
                }
                """, "NaN cannot be a compile-time argument");
        error("compile-time division by zero", """
                struct Main<<int N>>
                {
                    void f() { Main<<1 / 0>> m; }
                }
                """, "divides by zero");
    }

    private static void operators() {
        ok("overloaded operators", files("BigUInt", BIGUINT, "Main", """
                struct Main
                {
                    void run()
                    {
                        BigUInt<<128>> a;
                        BigUInt<<128>> b;
                        BigUInt<<128>> c = a + b;
                        bit less = a < b;
                        bit more = a > b;
                        bit ne = a != b;
                        BigUInt<<128>> d = a <<< 3;
                        c += a;
                        BigUInt<<128>> e = a.add(b);
                    }
                }
                """));
        String temp = """
                struct Temp
                {
                    public double c;

                    @overload(Operator.CAST) public double celsius() { return c; }
                    @overload(Operator.CAST) int secret() { return (int)c; }
                }
                """;
        ok("a cast operator: (R)x calls the one returning R (§46)", files("Temp", temp, "Main", """
                struct Main
                {
                    void run() { Temp t; double d = (double)t; double k = (double)t + 1; }
                }
                """));
        error("no cast operator to that type", files("Temp", temp, "Main", """
                struct Main
                {
                    void run() { Temp t; float f = (float)t; }
                }
                """), "cannot cast Temp to float; Temp casts only to double, int");
        error("a private cast operator", files("Temp", temp, "Main", """
                struct Main
                {
                    void run() { Temp t; int i = (int)t; }
                }
                """), "the cast of Temp to int is private");
        error("two cast operators to one type", """
                struct Temp
                {
                    public double c;
                    @overload(Operator.CAST) public double celsius() { return c; }
                    @overload(Operator.CAST) public double kelvin() { return c + 273.15; }
                }
                """, "Temp already casts to double, with celsius()");
        error("a cast operator returns what it casts to", """
                struct Temp
                {
                    @overload(Operator.CAST) public void nothing() { }
                }
                """, "Operator.CAST returns the type it casts to, not void");
        error("a cast operator takes no parameters", """
                struct Temp
                {
                    @overload(Operator.CAST) public int scaled(int k) { return k; }
                }
                """, "Operator.CAST ((T)x) takes no parameters; it casts to its return type");
        error("<<= needs its own overload", files("BigUInt", BIGUINT, "Main", """
                struct Main
                {
                    void run() { BigUInt<<128>> a; a <<= 3; }
                }
                """), "BigUInt<<128>> does not define operator <<=");
        error("undefined operator", files("BigUInt", BIGUINT, "Main", """
                struct Main
                {
                    void run() { BigUInt<<128>> a; BigUInt<<128>> b; BigUInt<<128>> c = a * b; }
                }
                """), "BigUInt<<128>> does not define operator *");
        error("operator on different shape", files("BigUInt", BIGUINT, "Main", """
                struct Main
                {
                    void run() { BigUInt<<128>> a; BigUInt<<256>> b; BigUInt<<128>> c = a + b; }
                }
                """), "expected BigUInt<<128>>, found BigUInt<<256>>");
        error("comparison must return bit", """
                struct Foo
                {
                    @overload(Operator.LESS)
                    public int less(Foo o) { return 0; }
                }
                """, "comparison operator < must return bit");
        error("unknown decorator", """
                struct Foo
                {
                    @pure
                    int f() { return 0; }
                }
                """, "unknown decorator @pure");
        ok("overloading in a class", files("Foo", """
                class Foo
                {
                    @overload(Operator.ADD)
                    public Foo add(Foo o) { return o; }
                }
                """));
        error("a string names no operator now", """
                struct Main { @overload("+") public Main add(Main o) { return o; } }
                """, "@overload takes an Operator constant: use @overload(Operator.ADD) for \"+\"");
        error("a string - could be either", """
                struct Main { @overload("-") public Main sub(Main o) { return o; } }
                """, "use @overload(Operator.SUBTRACT) or @overload(Operator.NEGATE) for \"-\"");
        error("an Operator that doesn't exist", """
                struct Main { @overload(Operator.PLUS) public Main add(Main o) { return o; } }
                """, "Operator has no constant PLUS; the operators are ADD, SUBTRACT, NEGATE");
        error("NEGATE takes nothing", """
                struct Main { @overload(Operator.NEGATE) public Main neg(Main o) { return o; } }
                """, "Operator.NEGATE (-x) takes no parameters; for x - y, use Operator.SUBTRACT");
        error("SUBTRACT takes one", """
                struct Main { @overload(Operator.SUBTRACT) public Main neg() { return this; } }
                """, "Operator.SUBTRACT (x - y) takes one parameter; for -x, use Operator.NEGATE");
        error("not overloadable", """
                struct Foo
                {
                    @overload("=")
                    public void assign(Foo o) { }
                }
                """, "operator = cannot be overloaded");
    }

    private static void accessAndStatic() {
        String counter = """
                struct Counter
                {
                    int secret;
                    private<Friend> int shared;
                    public int open;
                    public static int twice(int x) { return x * 2; }
                    public int get() { return secret; }
                }
                """;
        error("private member", files("Counter", counter, "Main", """
                struct Main
                {
                    void run() { Counter c; int x = c.secret; }
                }
                """), "`secret` is private to Counter");
        ok("public and friend access", files("Counter", counter, "Friend", """
                struct Friend
                {
                    void run()
                    {
                        Counter c;
                        int x = c.shared + c.open + c.get() + Counter.twice(3);
                    }
                }
                """));
        error("friend list is exclusive", files("Counter", counter, "Main", """
                struct Main
                {
                    void run() { Counter c; int x = c.shared; }
                }
                """), "`shared` is private to Counter");
        error("instance method through class", files("Counter", counter, "Main", """
                struct Main
                {
                    void run() { int x = Counter.get(); }
                }
                """), "`get` is an instance member of Counter and needs an object");
        error("instance field in static method", """
                struct Main
                {
                    int x;
                    static int f() { return x; }
                }
                """, "`x` is an instance member of Main and needs an object");
        ok("a subclass listed in private<...>", files("Base", """
                class Base
                {
                    private<Child> int inner;
                }
                """, "Child", """
                class Child extends(Base)
                {
                    int peek() { return inner; }
                }
                """));
    }

    private static void statements() {
        error("missing return", """
                struct Main
                {
                    int f(bit b) { if (b) { return 1; } }
                }
                """, "method f can reach its end without returning a int");
        ok("returns on all paths", files("Main", """
                struct Main
                {
                    int f(bit b) { if (b) { return 1; } else { return 2; } }
                    int g() { while (true) { } }
                }
                """));
        ok("a char is an int wherever one is wanted", files("Main", """
                struct Main
                {
                    static int twice(int n) { return n * 2; }
                    static int code(char c) { return c; }
                    void f(bit b)
                    {
                        char c = 'a';
                        int i = c;
                        i = c;
                        int d = twice(c);
                        int e = b ? c : 5;
                        array<int><<128>> counts;
                        counts[c] = 1;
                    }
                }
                """));
        ok("variables decide a binder before constants do", files("Main", """
                struct Main
                {
                    static int<<W>> pick(int<<int W>> a, int<<W>> b) { return b; }
                    void f()
                    {
                        int<<64>> big = 5;
                        int<<64>> x = pick(0, big);
                        int<<64>> y = pick(big, 5000000000);
                        int z = pick(1, 2);
                        char c = 'a';
                        int fromChar = pick(c, 1);
                    }
                }
                """));
        ok("a constant can decide a binder that a narrower variable widens to", files("Main", """
                struct Main
                {
                    static int<<W>> pick(int<<int W>> a, int<<W>> b) { return b; }
                    void f() { int<<8>> small = 1; int x = pick(small, 1000); }
                }
                """));
        error("a constant must fit the width a binder got", """
                struct Main
                {
                    static uint<<W>> pick(uint<<int W>> a, uint<<W>> b) { return b; }
                    void f() { uint<<8>> small = 1; pick(small, -1); }
                }
                """, "integer -1 does not fit in uint<<8>>");
        error("a constant that doesn't fit beside a variable", """
                struct Main
                {
                    void f() { ufixed<<0, 8>> x = 0.5; x = x + 1; }
                }
                """, "integer 1 does not fit in ufixed<<0, 8>>");
        ok("++ and -- keep the variable's type", files("Main", """
                struct Main
                {
                    int<<8>> small;
                    static int count;
                    void f()
                    {
                        char c = 'a';
                        char next = c++;
                        char prev = --c;
                        int<<8>> b = small++;
                        double d = 1.5;
                        double e = d--;
                        fixed<<8, 8>> x = 0.5;
                        fixed<<8, 8>> y = ++x;
                        array<int><<3>> xs;
                        xs[count++]++;
                        Main.count--;
                    }
                }
                """));
        error("++ on a struct", """
                struct Main
                {
                    void f() { Main m; m++; }
                }
                """, "++ needs a number or a char, not Main");
        error("-- on a bit", """
                struct Main
                {
                    void f() { bit b = true; b--; }
                }
                """, "-- needs a number or a char, not bit");
        error("++ on an address", """
                struct Main
                {
                    void f() { addr<int> p = null; ++p; }
                }
                """, "++ needs a number or a char, not addr<int>");
        error("++ on a value", """
                struct Main
                {
                    void f() { int x = 1; 5++; }
                }
                """, "cannot assign to this expression");
        error("x++++", """
                struct Main
                {
                    void f() { int x = 1; x++++; }
                }
                """, "cannot assign to this expression");
        error("++ on a const", """
                struct Main
                {
                    const int LIMIT = 3;
                    void f() { LIMIT++; }
                }
                """, "LIMIT is const");
        error("++ on a compile-time value", """
                struct Main<<int N>>
                {
                    void f() { N++; }
                }
                """, "N is a compile-time value and cannot be assigned");
        error("++ on a bit slice", """
                struct Main
                {
                    void f() { uint x = 0; x[0..4]++; }
                }
                """, "++ can't change a bit slice; write x[a..b] += 1");
        error("++ on a fixed point type that can't hold 1", """
                struct Main
                {
                    void f() { ufixed<<0, 8>> x = 0.5; x++; }
                }
                """, "++ steps by 1, which ufixed<<0, 8>> can't hold");
        error("++ in a lambda on a captured local", files("Main", """
                struct Main
                {
                    void f() { int x = 1; Runnable r = () -> { x++; }; }
                }
                """, "Runnable", "interface Runnable { public void run(); }"),
                "a lambda can't assign x: it holds a copy");
        error("break outside loop", """
                struct Main
                {
                    void f() { break; }
                }
                """, "break outside a loop");
        error("assign compile-time value", """
                struct Main<<int N>>
                {
                    void f() { N = 3; }
                }
                """, "N is a compile-time value and cannot be assigned");
        error("assign const", """
                struct Main
                {
                    const int MAX = 10;
                    void f() { MAX = 3; }
                }
                """, "MAX is const");
        ok("const in <<>>", files("Main", """
                struct Main
                {
                    const int SIZE = 4;
                    array<int><<SIZE * 2>> data;
                    void f() { Main x; int n = SIZE; }
                }
                """));
        error("duplicate local", """
                struct Main
                {
                    void f() { int x; int x; }
                }
                """, "variable x is already declared in this scope");
        error("unknown name", """
                struct Main
                {
                    void f() { int x = y; }
                }
                """, "unknown name y");
        error("void return value", """
                struct Main
                {
                    void f() { return 3; }
                }
                """, "void method f cannot return a value");
    }

    private static void files() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("src/Wrong.seal", "struct Right { }");
        List<Diagnostic> d = Checker.check(m);
        check(d.stream().anyMatch(x -> x.message().contains("struct Right must be in a file named Right")),
                "file name must match: " + d);

        m = new LinkedHashMap<>();
        m.put("Foo.seal", "struct Foo { int x = ; }");
        d = Checker.check(m);
        check(d.size() == 1 && d.get(0).file().equals("Foo.seal") && d.get(0).line() == 1,
                "parse errors come through with file names: " + d);
    }

    private static void dynamicPlacement() {
        String doc = "class Doc { public int pages; }";
        // A class name used as a type means a handle to it, everywhere a type can appear.
        ok("class types are handles everywhere", files("Doc", doc, "H", """
                struct H
                {
                    Doc one;
                    array<Doc><<4>> many;
                    Doc f(Doc d) { Doc local = d; many[0] = local; return many[0]; }
                }
                """));
        ok("a class inside a class is a handle", files("Doc", doc, "H", "class H { Doc inner; }"));
        ok("implicit and explicit handles are the same type", files("Doc", doc, "H", """
                struct H
                {
                    void f() { Doc a = new Doc(); handle<Doc> b = a; Doc c = b; }
                }
                """));
        error("a struct still cannot hold a class object by address", files("Doc", doc, "H", "struct H { addr<Doc> d; }"),
                "class objects can move, so they have no address");
        error("handles need a class", "struct H { handle<int> h; }", "handle<...> needs a class, not int");
    }

    private static void typeParameters() {
        String pair = """
                struct Pair<T>
                {
                    public T a;
                    public T b;
                    public bit less() { return a < b; }
                    public T sum() { return a + b; }
                }
                """;
        ok("operators on T are accepted in the definition", files("Pair", pair));
        ok("and checked when instantiated with a type that supports them", files("Pair", pair, "Main", """
                struct Main { static int main() { Pair<int> p; bit x = p.less(); return 0; } }
                """));
        String doc = "class Doc { }";
        genError("instantiating with a type that lacks the operator", files("Pair", pair, "Doc", doc, "Main", """
                struct Main { static int main() { Pair<handle<Doc>> p; bit x = p.less(); return 0; } }
                """), "Pair.seal:5:32: in Pair<handle<Doc>>.less");
        genError("the instantiation error names the operator", files("Pair", pair, "Doc", doc, "Main", """
                struct Main { static int main() { Pair<handle<Doc>> p; bit x = p.less(); return 0; } }
                """), "Doc does not define operator <");
        genErrorCount("only the real error is reported", files("Pair", pair, "Doc", doc, "Main", """
                struct Main { static int main() { Pair<handle<Doc>> p; bit x = p.less(); return 0; } }
                """), 1);
        ok("unused methods are never instantiated", files("Pair", pair, "Doc", doc, "Main", """
                struct Main { static int main() { Pair<handle<Doc>> p; p.a = null; return 0; } }
                """));
        ok("members of T are checked for each type it is used with", files("Main", """
                struct Main<T>
                {
                    T item;
                    int f() { return item.size(); }
                }
                """));
        error("T in a static member", """
                struct Box<T>
                {
                    static T make() { }
                }
                """, "type parameter T is not available in a static member");
        error("native in a program", """
                struct Main
                {
                    @native("print_int") static void p(int x) { }
                }
                """, "unknown decorator @native");
    }

    private static void callsAndReturns() {
        error("wrong argument count", """
                struct Main
                {
                    static void f(int a) { }
                    static void g() { f(1, 2); }
                }
                """, "no overload of f takes (integer constant 1, integer constant 2)");
        error("ambiguous overloads", """
                struct Main
                {
                    static void f(int<<8>> a) { }
                    static void f(uint<<8>> a) { }
                    static void g() { f(5); }
                }
                """, "ambiguous call to f: 2 overloads match");
        ok("constants prefer int", files("Main", """
                struct Main
                {
                    static void f(int a) { }
                    static void f(int<<64>> a) { }
                    static void g() { f(5); }
                }
                """));
        error("return type mismatch", """
                struct Main
                {
                    static int f() { return true; }
                }
                """, "return value: expected int, found bit");
        error("void used as a value", """
                struct Main
                {
                    static void f() { }
                    static void g() { int x = f(); }
                }
                """, "initializer of x: expression has no value");
        error("assigning to a call", """
                struct Main
                {
                    static int f() { return 1; }
                    static void g() { f() = 3; }
                }
                """, "cannot assign to this expression");
        error("value parameter out of range", """
                struct Small<<int<<8>> N>>
                {
                    void f() { Small<<300>> x; }
                }
                """, "Small<<300>> matches no shape of Small");
        error("const defined in terms of itself", """
                struct Main
                {
                    const int A = B;
                    const int B = A;
                    array<int><<A>> data;
                }
                """, "defined in terms of itself");
        error("comparing different address types", """
                struct Main
                {
                    static bit f(addr<int> a, addr<uint> b) { return a < b; }
                }
                """, "cannot compare addr<int> with addr<uint>");
        ok("comparing addresses of one type", files("Main", """
                struct Main
                {
                    static bit f(addr<int> a, addr<int> b) { return a < b; }
                }
                """));
        error("private constructor", files("A", "struct A { private new() { } }", "B", """
                struct B
                {
                    void f() { A a = new A(); }
                }
                """), "the constructor of A taking () is private");
    }

    private static void fixedPointErrors() {
        ok("fixed-point basics", files("Main", """
                struct Main
                {
                    static void f()
                    {
                        fixed<<8, 8>> a = 1.5;
                        fixed<<8, 8>> b = a * 2 - 0.25;
                        ufixed<<0, 8>> frac = 0.5;
                        bit less = a < b;
                        double d = (double)a;
                        int i = (int)a;
                        fixed<<16, 16>> wider = (fixed<<16, 16>>)a;
                    }
                }
                """));
        error("fixed needs two values", "struct Main { fixed<<8>> x; }",
                "fixed needs two values, fixed<<N, M>>");
        error("signed fixed needs a sign bit", "struct Main { fixed<<0, 8>> x; }",
                "fixed<<N, M>> needs N >= 1; N includes the sign bit");
        ok("unsigned fixed may have no integer bits", files("Main", "struct Main { ufixed<<0, 8>> x; }"));
        error("at most 64 bits", "struct Main { fixed<<40, 40>> x; }",
                "fixed<<40, 40>> has 80 bits; fixed-point types have 1 to 64");
        error("negative fraction bits", "struct Main { ufixed<<8, -1>> x; }", "ufixed<<N, M>> needs M >= 0");
        error("literal out of range", "struct Main { void f() { fixed<<8, 8>> x = 200.0; } }",
                "200.0 does not fit in fixed<<8, 8>>");
        error("integer literal out of range", "struct Main { void f() { ufixed<<4, 4>> x = 16; } }",
                "integer 16 does not fit in ufixed<<4, 4>>");
        error("negative into unsigned", "struct Main { void f() { ufixed<<4, 4>> x = -0.5; } }",
                "-0.5 does not fit in ufixed<<4, 4>>");
        error("mixing formats needs a cast", """
                struct Main { void f() { fixed<<8, 8>> a = 1.0; fixed<<4, 12>> b = 1.0; fixed<<8, 8>> c = a + b; } }
                """, "operator + needs matching types, not fixed<<8, 8>> and fixed<<4, 12>>; use a cast");
        error("mixing with int needs a cast", """
                struct Main { void f() { fixed<<8, 8>> a = 1.0; int i = 2; fixed<<8, 8>> c = a * i; } }
                """, "operator * needs matching types, not fixed<<8, 8>> and int; use a cast");
        error("no bitwise operators", """
                struct Main { void f() { fixed<<8, 8>> a = 1.0; fixed<<8, 8>> b = a & a; } }
                """, "operator & needs integers or bits");
        error("no shifts", """
                struct Main { void f() { fixed<<8, 8>> a = 1.0; fixed<<8, 8>> b = a <<< 1; } }
                """, "<<< shifts an integer");
        error("no bit indexing", """
                struct Main { void f() { fixed<<8, 8>> a = 1.0; bit b = a[0]; } }
                """, "fixed<<8, 8>> cannot be indexed");
        error("fixed is not a compile-time value type", "struct Main<<fixed<<8, 8>> X>> { }",
                "compile-time values must be integers, bit, float, double or string");
        error("width from a value at instantiation", files("Q", "struct Q<<int F>> { fixed<<60, F>> v; }",
                "Main", "struct Main { Q<<10>> q; }"), "in Q<<10>>: fixed<<60, 10>> has 70 bits");
    }

    private static void uniqueRules() {
        String file = """
                unique File
                {
                    public int fd;
                    public new() { }
                    public new(int f) { fd = f; }
                    delete() { }
                }
                """;
        error("struct with a destructor", "struct S { delete() { } }",
                "struct S cannot have a destructor: copies of it would run it more than once; declare it `unique S` instead");
        error("struct holding a unique", files("File", file, "S", "struct S { File f; }"),
                "struct S cannot contain File, which is unique: copying S would copy it; declare `unique S` instead");
        error("struct holding an array of uniques", files("File", file, "S", "struct S { array<File><<2>> fs; }"),
                "struct S cannot contain array<File><<2>>, which is unique");
        ok("unique holding a unique", files("File", file, "U", "unique U { File f; }"));
        ok("class holding a unique", files("File", file, "C", "class C { File f; }"));
        error("a generic struct made to hold a unique", files("File", file,
                "Box", "struct Box<T> { T item; }",
                "Main", "struct Main { static int main() { Box<File> b; return 0; } }"),
                "in Box<File>: struct Box cannot contain File, which is unique");

        error("copying in a declaration", files("File", file, "Main", """
                struct Main { static void f() { File a = new File(1); File b = a; } }
                """), "initializer of b: File is unique and can't be copied");
        error("copying in an assignment", files("File", file, "Main", """
                struct Main { static void f() { File a = new File(1); File b = new File(2); b = a; } }
                """), "assignment: File is unique and can't be copied");
        error("copying a field", files("File", file, "Holder", "class Holder { public File f; }", "Main", """
                struct Main { static void f(Holder h) { File a = h.f; } }
                """), "initializer of a: File is unique and can't be copied");
        error("returning a lent parameter", files("File", file, "Main", """
                struct Main { static File f(File lent) { return lent; } }
                """), "return value: File is unique and can't be copied");
        error("copying in a field initializer", files("File", file, "Main", """
                unique Main { static File shared; File mine = shared; }
                """), "initializer of mine: File is unique and can't be copied");
        ok("everything that doesn't copy", files("File", file, "Holder", "class Holder { public File f; }", "Main", """
                struct Main
                {
                    static File open(int fd) { File f = new File(fd); return f; }   // moved out
                    static int use(File f) { return f.fd; }                          // lent
                    static void g(Holder h)
                    {
                        File a = new File(1);
                        File b = open(2);
                        a = new File(3);
                        h.f = open(4);
                        int x = use(a) + use(h.f) + use(new File(5));
                    }
                }
                """));
    }

    private static void accessOnEveryKind() {
        for (String kind : List.of("struct", "unique", "class")) {
            String t = """
                    KIND T
                    {
                        public int open;
                        int secret;
                        private<Friend> int shared;
                        public new() { }
                        private new(int s) { secret = s; }
                        public static T make() { return new T(7); }
                        int hidden() { return secret; }
                        public int peek() { return hidden() + secret; }
                        private static int counter() { return 1; }
                        @overload(Operator.ADD) private int plus(int x) { return secret + x; }
                    }
                    """.replace("KIND", kind);
            String friend = """
                    struct Friend<<int N>>
                    {
                        public static int read(T x) { return x.shared; }
                    }
                    """;
            ok(kind + ": allowed accesses", files("T", t, "Friend", friend, "Main", """
                    struct Main { static int main() { T x = T.make(); return x.open + x.peek() + Friend<<3>>.read(x); } }
                    """.replace("Friend<<3>>.read(x)", "0")));
            String[][] cases = {
                    {"int v = x.secret;", "`secret` is private to T"},
                    {"int v = x.hidden();", "`hidden` is private to T"},
                    {"T y = new T(5);", "the constructor of T taking (integer constant 5) is private"},
                    {"int v = T.counter();", "`counter` is private to T"},
                    {"int v = x + 1;", "operator + of T is private"},
                    {"int v = x.shared;", "`shared` is private to T"},
            };
            for (String[] c : cases) {
                error(kind + ": " + c[1], files("T", t, "Friend", friend, "Main",
                        "struct Main { static int main() { T x = T.make(); " + c[0] + " return 0; } }"), c[1]);
            }
        }
        String base = """
                class Base
                {
                    int mine;
                    private<Child> int family;
                }
                """;
        error("a subclass cannot see its parent's private members", files("Base", base,
                "Child", "class Child extends(Base) { int f() { return mine; } }"), "`mine` is private to Base");
        ok("a subclass named in private<...> can see the member", files("Base", base,
                "Child", "class Child extends(Base) { int f() { return family; } }"));
        error("there is no private<subclasses> shorthand", files("Base", "class Base { private<subclasses> int x; }"),
                "unknown type subclasses in private<...>");
        ok("a friend's every shape has access", files("Holder", "class Holder { private<Reader> int v; }",
                "Reader", """
                        struct Reader<<int N>>
                        {
                            int get(Holder h) { return h.v + N; }
                        }
                        """));
    }

    // ================================================================ helpers

    private static void genErrorCount(String name, Map<String, String> files, int count) {
        List<Diagnostic> d = io.github.thelinuxseal.seal.codegen.CodeGen.generate(Checker.analyze(files), "Main").errors();
        check(d.size() == count, name + ": expected " + count + " error(s), got " + d);
    }

    /** Expects the program to pass checking but fail during code generation (instantiation). */
    private static void genError(String name, Map<String, String> files, String expected) {
        Checker c = Checker.analyze(files);
        if (!c.diagnostics().isEmpty()) {
            check(false, name + ": expected checking to pass, got " + c.diagnostics());
            return;
        }
        List<Diagnostic> d = io.github.thelinuxseal.seal.codegen.CodeGen.generate(c, "Main").errors();
        check(d.stream().anyMatch(x -> x.toString().contains(expected)),
                name + ": expected a code generation error containing \"" + expected + "\", got\n        "
                        + d.stream().map(Diagnostic::toString).reduce((a, b) -> a + "\n        " + b).orElse("(none)"));
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i] + ".seal", pairs[i + 1]);
        }
        return m;
    }

    private static void ok(String name, Map<String, String> files) {
        List<Diagnostic> d = Checker.check(files);
        check(d.isEmpty(), name + ": expected no errors, got\n        "
                + d.stream().map(Diagnostic::toString).reduce((a, b) -> a + "\n        " + b).orElse(""));
    }

    /** The warnings the given flags turn on, as "line: [flag] message"; none without them. */
    private static void warnings(String name, Set<Warning> flags, Map<String, String> files, String... expected) {
        Checker quiet = Checker.analyze(files, Map.of());
        Checker c = Checker.analyze(files, Map.of(), flags);
        List<String> found = c.warnings().stream()
                .map(w -> w.line() + ": [" + w.warning().flag() + "] " + w.message()).toList();
        check(c.diagnostics().isEmpty() && quiet.diagnostics().isEmpty() && quiet.warnings().isEmpty()
                        && found.equals(List.of(expected)),
                name + ": expected warnings " + List.of(expected) + ", got " + found + " and errors " + c.diagnostics()
                        + ", and without flags " + quiet.warnings());
    }

    /** One-file error test: the class name is taken from the source. */
    private static void error(String name, String source, String expected) {
        String cls = source.replaceAll("(?s).*?\\b(?:struct|unique|class)\\s+(\\w+).*", "$1");
        error(name, files(cls, source), expected);
    }

    private static void error(String name, Map<String, String> files, String expected) {
        List<Diagnostic> d = Checker.check(files);
        check(d.stream().anyMatch(x -> x.message().contains(expected)),
                name + ": expected an error containing \"" + expected + "\", got\n        "
                        + d.stream().map(Diagnostic::toString).reduce((a, b) -> a + "\n        " + b).orElse("(none)"));
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
