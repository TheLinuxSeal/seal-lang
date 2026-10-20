package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.CheckerTest;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.codegen.CodeGen;
import io.github.thelinuxseal.seal.vm.Trap;
import io.github.thelinuxseal.seal.vm.VM;

/** End-to-end tests: compile programs, run them on the VM, and check output. `java -cp out io.github.thelinuxseal.seal.RunTest`. */
@SuppressWarnings("deprecation") // runs programs on the deprecated VM too, while it is kept
public final class RunTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        integers();
        floats();
        bits();
        control();
        generics();
        strings();
        addresses();
        objects();
        destructors();
        dynamicClasses();
        operators();
        statics();
        entryPoints();
        traps();

        String jvm = ON_JVM.finish(failures);
        System.out.println(passed + " passed, " + failures.size() + " failed" + jvm);
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ tests

    private static void integers() {
        output("exit code", main("return 42;"), "", 42);
        output("wrapping", main("""
                int<<8>> a = 127; a += 1; p(a);
                uint<<8>> b = 255; b += 1; p(b);
                uint<<4>> c = 0; c -= 1; p(c);
                int big = 2147483647; big += 1; p(big);
                """), "-128 0 15 -2147483648 ");
        output("division", main("""
                int a = -7; p(a / 2); p(a % 2);
                uint b = 4294967295; p(b / 2);
                int<<64>> m = 9000000000; p(m * 2);
                """), "-3 -1 2147483647 18000000000 ");
        output("shifts", main("""
                int one = 1; p(one <<< 3);
                int neg = -16; p(neg <<< -2);
                uint u = 16; p(u <<< -2);
                p(one <<< 40);
                p(neg <<< -40);
                uint<<8>> top = 255; p(top <<< 4);
                """), "8 -4 4 0 -1 240 ");
        output("comparisons", main("""
                uint big = 4000000000; uint small = 1;
                System.stdout.print(big > small);
                int a = -1; int b = 1; System.stdout.print(a < b);
                """), "truetrue");
        output("casts", main("""
                int x = 300; p((int<<8>>)x); p((uint<<8>>)x);
                int<<8>> n = -1; p((uint)n); p((int<<64>>)n);
                """), "44 44 4294967295 -1 ");
    }

    private static void floats() {
        output("float arithmetic", main("""
                float f = (float)7 / 2; System.stdout.print(f); System.stdout.println();
                double d = 1.5; d = d * 4; System.stdout.print(d); System.stdout.println();
                p((int)(d + 0.75));
                System.stdout.print((double)f + 0.25); System.stdout.println();
                System.stdout.print(-f); System.stdout.println();
                """), "3.5\n6.0\n6 3.75\n-3.5\n");
        output("float compare", main("""
                double a = 0.1 + 0.2; System.stdout.print(a > 0.3); System.stdout.print(a == a);
                """), "truetrue");
    }

    private static void bits() {
        output("bit index and slice", main("""
                uint x = 172;
                System.stdout.print(x[2]); System.stdout.print(x[0]); System.stdout.println();
                p(x[4..8]);
                x[0] = true; p(x);
                x[4..8] = 1; p(x);
                int<<8>> s = 0; s[7] = true; p(s);
                """), "truefalse\n10 173 29 -128 ");
        output("bitwise", main("""
                uint a = 12; uint b = 10;
                p(a & b); p(a | b); p(a ^ b); p(~a);
                bit t = true; bit f = false; System.stdout.print(t ^ f); System.stdout.print(!t);
                """), "8 14 6 4294967283 truefalse");
    }

    private static void control() {
        output("loops", main("""
                int i = 0;
                while (true) {
                    i += 1;
                    if (i == 3) { continue; }
                    if (i > 5) { break; }
                    p(i);
                }
                for (int j = 10; j > 0; j -= 4) { p(j); }
                """), "1 2 4 5 10 6 2 ");
        output("short circuit", files("Main", """
                struct Main
                {
                    static int calls;
                    static bit hit() { calls += 1; return true; }
                    static int main()
                    {
                        bit a = false && hit();
                        bit b = true || hit();
                        bit c = true && hit();
                        System.stdout.print(calls);
                        return 0;
                    }
                }
                """), "1");
        output("recursion", files("Main", """
                struct Main
                {
                    static int fib(int n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); }
                    static int<<64>> fact(int<<64>> n) { if (n <= 1) { return 1; } return n * fact(n - 1); }
                    static int main()
                    {
                        System.stdout.print(fib(20)); System.stdout.println();
                        System.stdout.print(fact(20));
                        return 0;
                    }
                }
                """), "6765\n2432902008176640000");
    }

    private static void generics() {
        output("Array from the spec", files("Array", CheckerTest.ARRAY, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Array<int><<5>> a = new Array();
                        for (int i = 0; i < 5; i += 1) { a.set(i, i * i); }
                        int sum = 0;
                        for (int i = 0; i < a.getLength(); i += 1) { sum += a.get(i); }
                        System.stdout.print(sum); System.stdout.println();
                        Array<double><<2, 3>> m = new Array();
                        System.stdout.print(m.getLength()); System.stdout.println();
                        System.stdout.print(m.getOccFrac()); System.stdout.println();
                        System.stdout.print(sizeof(Array<int><<5>>));
                        return 0;
                    }
                }
                """), "30\n6\n0.0\n192");
        output("distinct instantiations", files("Box", """
                struct Box<<int N>>
                {
                    public array<int><<N>> items;
                    public int count() { return N; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Box<<2>> a;
                        Box<<7>> b;
                        System.stdout.print(a.count() + b.count());
                        System.stdout.print(sizeof(Box<<2>>) + sizeof(Box<<7>>));
                        return 0;
                    }
                }
                """), "9288");
        output("value binders", files("Pair", """
                struct Pair<<int N>>
                {
                    public int both<<int N>>(Pair<<int K>> other) { return N * 10 + K; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Pair<<3>> a;
                        Pair<<4>> b;
                        Pair<<9>> c;
                        System.stdout.print(a.both(b)); System.stdout.print(a.both(c)); System.stdout.print(c.both(a));
                        return 0;
                    }
                }
                """), "343993");
        output("type parameters", files("Stack", """
                struct Stack<T><<int N>>
                {
                    array<T><<N>> items;
                    int top;
                    public void push(T v) { items[top] = v; top += 1; }
                    public T pop() { top -= 1; return items[top]; }
                    public bit empty() { return top == 0; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Stack<double><<4>> s;
                        s.push(1.5); s.push(2.5);
                        Stack<int<<8>>><<3>> t;
                        t.push(-5);
                        System.stdout.print(s.pop() + s.pop()); System.stdout.print((int)t.pop()); System.stdout.print(s.empty());
                        return 0;
                    }
                }
                """), "4.0-5true");
    }

    private static void strings() {
        String string = """
                struct string<<>>
                struct string<<int N>>
                {
                    int size;
                    array<uint<<16>>><<N>> chars<<int N>>;
                    addr<uint<<16>>> chars<<>>;
                    int capacity<<>>;

                    public int length() { return size; }
                    public uint<<16>> charAt<<int N>>(int i) { return chars[i]; }
                    public uint<<16>> charAt<<>>(int i) { return &(chars + i * sizeof(uint<<16>>)); }

                    public string<<N + K>> add<<int N>>(string<<int K>> other)
                    {
                        string<<N + K>> result;
                        for (int i = 0; i < N; i += 1) { result.chars[i] = chars[i]; }
                        for (int i = 0; i < K; i += 1) { result.chars[N + i] = other.charAt(i); }
                        result.size = size + other.length();
                        return result;
                    }
                }
                """;
        output("string literals and add", files("string", string, "Main", """
                struct Main
                {
                    static int main()
                    {
                        string<<5>> a = "Hello";
                        string<<6>> b = ", you";
                        string<<11>> c = a.add(b);
                        for (int i = 0; i < c.length(); i += 1) { System.stdout.print((char)c.charAt(i)); }
                        System.stdout.println();
                        string s = "dynamic";
                        for (int i = 0; i < s.length(); i += 1) { System.stdout.print((char)s.charAt(i)); }
                        return 0;
                    }
                }
                """), "Hello, you\ndynamic");
    }

    private static void addresses() {
        output("address operators", main("""
                int x = 5;
                addr<int> ptr = *x;
                &ptr = 32;
                p(x);
                addr<addr<int>> pp = *ptr;
                &(&pp) = 7;
                p(x);
                addr<int> buf = new<int>(4);
                for (int i = 0; i < 4; i += 1) { &(buf + i * sizeof(int)) = i * 10; }
                int total = 0;
                for (int i = 0; i < 4; i += 1) { total += &(buf + i * sizeof(int)); }
                p(total);
                delete buf;
                """), "32 7 60 ");
        output("objects through addresses", files("Point", """
                struct Point
                {
                    public int x;
                    public int y;
                    public new(int a, int b) { x = a; y = b; }
                    public int sum() { return x + y; }
                }
                """, "Main", """
                struct Main
                {
                    static void bump(addr<Point> p) { (&p).x += 100; }
                    static int main()
                    {
                        addr<Point> p = new Point(1, 2);
                        Point local = new Point(3, 4);
                        bump(p);
                        bump(*local);
                        System.stdout.print((&p).sum()); System.stdout.print(' '); System.stdout.print(local.x);
                        delete p;
                        return 0;
                    }
                }
                """), "103 103");
        output("bit-packed memory", main("""
                addr<uint<<3>>> b = (addr<uint<<3>>>)new<uint<<3>>>(8);
                for (int i = 0; i < 8; i += 1) { &(b + i * 3) = (uint<<3>>)i; }
                int total = 0;
                for (int i = 0; i < 8; i += 1) { total += (int)&(b + i * 3); }
                p(total);
                p(sizeof(array<uint<<3>>><<8>>));
                """), "28 24 ");
    }

    private static void objects() {
        String point = """
                struct Point
                {
                    public int x;
                    public int y;
                }
                """;
        output("value semantics", files("Point", point, "Main", """
                struct Main
                {
                    static void change(Point p) { p.x = 99; }
                    static Point make(int v) { Point p; p.x = v; p.y = v + 1; return p; }
                    static int main()
                    {
                        Point a;
                        a.x = 1;
                        Point b = a;
                        b.x = 2;
                        change(a);
                        Point c = make(7);
                        System.stdout.print(a.x); System.stdout.print(b.x); System.stdout.print(c.x + c.y);
                        return 0;
                    }
                }
                """), "1215");
        output("constructors and field initializers", files("Counter", """
                struct Counter
                {
                    public int start = 10;
                    public int step;
                    public new() { step = 1; }
                    public new(int s) { step = s; }
                    public int next() { start += step; return start; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Counter a;
                        Counter b = new Counter(5);
                        a.next();
                        System.stdout.print(a.next()); System.stdout.print(' '); System.stdout.print(b.next());
                        return 0;
                    }
                }
                """), "12 15");
        output("nested objects", files("Point", point, "Line", """
                struct Line
                {
                    public Point from;
                    public Point to;
                    public int length() { return to.x - from.x + to.y - from.y; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Line l;
                        l.to.x = 3; l.to.y = 4;
                        System.stdout.print(l.length());
                        return 0;
                    }
                }
                """), "7");
    }

    private static void destructors() {
        output("destruction order", files("Noisy", """
                unique Noisy<<int ID>>
                {
                    delete() { System.stdout.print(ID); }
                }
                """, "Pair", """
                unique Pair
                {
                    Noisy<<1>> first;
                    Noisy<<2>> second;
                    delete() { System.stdout.print(0); }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        {
                            Pair p;
                            Noisy<<3>> n;
                        }
                        System.stdout.println();
                        addr<Pair> h = new Pair();
                        delete h;
                        System.stdout.println();
                        for (int i = 0; i < 2; i += 1) {
                            Noisy<<5>> n;
                            if (i == 1) { break; }
                        }
                        return 0;
                    }
                }
                """), "3021\n021\n55");
    }

    private static void dynamicClasses() {
        String animals = """
                class Animal
                {
                    public int legs;
                    public new() { legs = 2; }
                    public int speak() { return 1; }
                    public int describe() { return speak() * 100 + legs; }
                }
                """;
        String dog = """
                class Dog extends(Animal)
                {
                    public new() { super(); legs = 4; }
                    public int speak() { return 2; }
                    public int both() { return speak() * 10 + super.speak(); }
                }
                """;
        output("virtual dispatch", files("Animal", animals, "Dog", dog, "Main", """
                struct Main
                {
                    static int main()
                    {
                        handle<Animal> a = new Animal();
                        handle<Animal> d = new Dog();
                        System.stdout.print(a.describe()); System.stdout.print(' ');
                        System.stdout.print(d.describe()); System.stdout.print(' ');
                        handle<Dog> dog = (handle<Dog>)d;
                        System.stdout.print(dog.both());
                        return 0;
                    }
                }
                """), "102 204 21");
        output("a unique's destructor runs at the end of its scope", files("Res", """
                unique Res
                {
                    public int id;
                    public new(int i) { id = i; }
                    delete() { System.stdout.print(id); }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        { Res r = new Res(7); System.stdout.print('|'); }
                        System.stdout.print('|');
                        return 0;
                    }
                }
                """), "|7|");
        output("fixed values inside dynamic objects", files("Point", """
                struct Point
                {
                    public int x;
                    public void move(int d) { x += d; }
                }
                """, "Holder", """
                class Holder
                {
                    public Point p;
                    public array<int><<3>> nums;
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        handle<Holder> h = new Holder();
                        h.p.x = 5;
                        h.p.move(10);
                        h.nums[2] = 4;
                        System.stdout.print(h.p.x + h.nums[2]);
                        return 0;
                    }
                }
                """), "19");
        output("shape-dependent parents", files("Circle", """
                class Circle { public int kind() { return 0; } }
                """, "Square", """
                class Square { public int kind() { return 1; } }
                """, "Shape", """
                class Shape<<int K>>
                    extends<<0>>(Circle)
                    extends<<1>>(Square)
                {
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        handle<Shape<<0>>> c = new Shape();
                        handle<Shape<<1>>> s = new Shape();
                        System.stdout.print(c.kind()); System.stdout.print(s.kind());
                        return 0;
                    }
                }
                """), "01");
    }

    private static void operators() {
        output("overloaded operators", files("BigUInt", """
                struct BigUInt<<int BITS>>
                {
                    public array<uint<<64>>><<(BITS + 63) / 64>> words;

                    @overload(Operator.ADD)
                    public BigUInt<<BITS>> add(BigUInt<<BITS>> o)
                    {
                        BigUInt<<BITS>> r;
                        uint<<64>> carry = 0;
                        for (int i = 0; i < (BITS + 63) / 64; i += 1) {
                            uint<<64>> s = words[i] + o.words[i];
                            uint<<64>> c1 = 0;
                            if (s < words[i]) { c1 = 1; }
                            uint<<64>> t = s + carry;
                            if (t < s) { c1 = 1; }
                            r.words[i] = t;
                            carry = c1;
                        }
                        return r;
                    }

                    @overload(Operator.EQUALS)
                    public bit same(BigUInt<<BITS>> o)
                    {
                        for (int i = 0; i < (BITS + 63) / 64; i += 1) {
                            if (words[i] != o.words[i]) { return false; }
                        }
                        return true;
                    }

                    @overload(Operator.SHIFT_ASSIGN)
                    public void shiftInPlace(int n) { words[0] = words[0] <<< n; }

                    @overload(Operator.ADD_ASSIGN)
                    public void addInPlace(BigUInt<<BITS>> o) { words = add(o).words; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        BigUInt<<128>> a;
                        BigUInt<<128>> b;
                        a.words[0] = 18446744073709551615;
                        b.words[0] = 1;
                        BigUInt<<128>> c = a + b;
                        System.stdout.print(c.words[0]); System.stdout.print(' '); System.stdout.print(c.words[1]); System.stdout.print(' ');
                        System.stdout.print(c == c); System.stdout.print(c != a);
                        b <<= 4;
                        System.stdout.print(' '); System.stdout.print(b.words[0]);
                        c += b;
                        System.stdout.print(' '); System.stdout.print(c.words[0]);
                        return 0;
                    }
                }
                """), "0 1 truetrue 16 16");
    }

    private static void statics() {
        output("static fields", files("Config", """
                struct Config
                {
                    public static int count = 3;
                    public static double scale = 1.5;
                    public const int LIMIT = 4 * 8;
                    public static int bump() { count += 1; return count; }
                }
                """, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Config.bump();
                        System.stdout.print(Config.bump()); System.stdout.print(' ');
                        System.stdout.print(Config.scale); System.stdout.print(' ');
                        System.stdout.print(Config.LIMIT);
                        return 0;
                    }
                }
                """), "5 1.5 32");
    }

    private static void entryPoints() {
        output("instance main runs on a new Main", files("Main", """
                class Main
                {
                    int count = 2;
                    new() { count += 3; }
                    int main() { bump(); System.stdout.print(count); return count; }
                    void bump() { count += 1; }
                }
                """), "6", 6);
        output("a unique in a static field is destroyed after main returns", files("Last", """
                unique Last
                {
                    delete() { System.stdout.print(2); }
                }
                """, "Main", """
                class Main
                {
                    static Last last;
                    void main() { System.stdout.print(0); }
                }
                """), "02");
        output("instance main of a struct", files("Main", """
                struct Main
                {
                    int n = 4;
                    void main() { n += 1; System.stdout.print(n); }
                }
                """), "5");
        output("instance main of a unique", files("Main", """
                unique Main
                {
                    int n;
                    new() { n = 7; }
                    delete() { System.stdout.print(-n); }
                    void main() { System.stdout.print(n); }
                }
                """), "7-7");
        output("an inherited main", files("App", """
                abstract class App
                {
                    public abstract int setup();
                    void main() { System.stdout.print(setup()); }
                }
                """, "Main", """
                class Main extends(App)
                {
                    public int setup() { return 9; }
                }
                """), "9");
        output("a private constructor is fine", files("Main", """
                class Main
                {
                    private new() { System.stdout.print(1); }
                    void main() { }
                }
                """), "1");
        output("static main comes first", files("Main", """
                class Main
                {
                    new() { System.stdout.print(1); }
                    static void main() { System.stdout.print(2); }
                }
                """), "2");
        output("instance main of an abstract class", files("Main", """
                abstract class Main
                {
                    void main() { }
                }
                """), "error: main() is not static, so the program creates a Main to run it, but Main is abstract; make main() static", -1);
        output("instance main without a no-argument constructor", files("Main", """
                class Main
                {
                    new(int x) { }
                    void main() { }
                }
                """), "but Main has no no-argument constructor; make main() static", -1);
        output("instance main of a generic class", files("Main", """
                class Main<T>
                {
                    void main() { }
                }
                """), "but Main has type parameters or shapes", -1);
        output("no main at all", files("Main", """
                class Main
                {
                    void main(int x) { }
                }
                """), "no entry point: expected `void main()` or `int main()` in class Main", -1);
    }

    private static void traps() {
        trap("division by zero", main("int z = 0; p(1 / z);"), "division by zero");
        trap("array bounds", files("Array", CheckerTest.ARRAY, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Array<int><<3>> a = new Array();
                        int i = 3;
                        return a.get(i);
                    }
                }
                """), "index 3 is out of bounds for length 3");
        trap("null address", main("addr<int> ptr = null; p(&ptr);"), "null address");
        trap("misaligned access", main("addr<int> ptr = new<int>(2); p(&(ptr + 1));"), "misaligned access");
        trap("double delete", main("addr<int> p = new<int>(1); delete p; delete p;"),
                "delete of an address that was not allocated with new");
        String box = "class Box { public int v; }";
        trap("using a variable set to null", files("Box", box, "Main", """
                struct Main
                {
                    static int main()
                    {
                        Box b = new Box();
                        b = null;
                        return b.v;
                    }
                }
                """), "null handle");
        trap("bad downcast", files("Box", box, "Other", "class Other extends(Box) { }", "Main", """
                struct Main
                {
                    static int main()
                    {
                        handle<Box> b = new Box();
                        handle<Other> o = (handle<Other>)b;
                        return 0;
                    }
                }
                """), "cannot cast Box to Other");
        trap("deep recursion", files("Main", """
                struct Main
                {
                    static int down(int n) { return down(n + 1); }
                    static int main() { return down(0); }
                }
                """), "call stack overflow");
        output("instantiation errors are reported", files("Buf", """
                struct Buf<<int N>>
                {
                    public int get() { array<int><<N>> a; return a[5]; }
                }
                """, "Main", """
                struct Main
                {
                    static int main() { Buf<<3>> b; return b.get(); }
                }
                """), "error: index 5 is out of bounds for array<int><<3>>", -1);
    }

    // ================================================================ helpers

    /** A Main class whose body is {@code body}, with a helper p(x) that prints an int and a space. */
    private static Map<String, String> main(String body) {
        return files("Main", """
                struct Main
                {
                    static void p(int<<64>> x) { System.stdout.print(x); System.stdout.print(' '); }
                    static void p(uint<<64>> x) { System.stdout.print(x); System.stdout.print(' '); }
                    static void p(int x) { System.stdout.print(x); System.stdout.print(' '); }
                    static void p(uint x) { System.stdout.print(x); System.stdout.print(' '); }
                    static void p(int<<8>> x) { System.stdout.print((int)x); System.stdout.print(' '); }
                    static void p(uint<<8>> x) { System.stdout.print((uint)x); System.stdout.print(' '); }
                    static void p(uint<<4>> x) { System.stdout.print((uint)x); System.stdout.print(' '); }
                    static int main()
                    {
                """ + body + """
                        return 0;
                    }
                }
                """);
    }

    private static Map<String, String> files(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i] + ".seal", pairs[i + 1]);
        }
        return m;
    }

    private record Run(String output, long exit, String error) {
    }

    /** Every program that runs here runs on the JVM too (docs/JVM.md §12). */
    private static final OnJvm ON_JVM = new OnJvm();
    private static String current = "";

    private static Run execute(Map<String, String> files) {
        Checker checker = Checker.analyze(files);
        List<Diagnostic> errors = checker.diagnostics();
        if (!errors.isEmpty()) {
            return new Run("", -1, "error: " + errors);
        }
        CodeGen.Result result = CodeGen.generate(checker, "Main");
        if (!result.errors().isEmpty()) {
            return new Run("", -1, "error: " + result.errors());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        VM vm = new VM(result.program(), out);
        vm.setMaxSteps(5_000_000);
        try {
            long code = vm.run();
            String text = bytes.toString(StandardCharsets.UTF_8);
            ON_JVM.compare(current, checker, "Main", java.io.InputStream.nullInputStream(), true, text, code, null);
            return new Run(text, code, null);
        } catch (Trap t) {
            String text = bytes.toString(StandardCharsets.UTF_8);
            ON_JVM.compare(current, checker, "Main", java.io.InputStream.nullInputStream(), true, text, -1, t);
            return new Run(text, -1, t.getMessage());
        } catch (RuntimeException e) {
            return new Run(bytes.toString(StandardCharsets.UTF_8), -1, "crash: " + e);
        }
    }

    private static void output(String name, Map<String, String> files, String expected) {
        output(name, files, expected, 0);
    }

    private static void output(String name, Map<String, String> files, String expected, long exit) {
        current = name;
        Run r = execute(files);
        boolean ok = exit == -1 ? r.error() != null && r.error().contains(expected.replace("error: ", ""))
                : r.error() == null && r.output().equals(expected) && r.exit() == exit;
        check(ok, name + "\n    expected " + show(expected) + (exit > 0 ? " exit " + exit : "")
                + "\n    got      " + show(r.output()) + " exit " + r.exit()
                + (r.error() == null ? "" : "\n    error    " + r.error()));
    }

    private static void trap(String name, Map<String, String> files, String expectedTrap) {
        current = name;
        Run r = execute(files);
        check(r.error() != null && r.error().contains(expectedTrap),
                name + "\n    expected trap containing " + show(expectedTrap) + "\n    got      "
                        + (r.error() == null ? "no trap, output " + show(r.output()) : r.error()));
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
