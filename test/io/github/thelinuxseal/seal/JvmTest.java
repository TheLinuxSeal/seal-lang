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
import java.util.Random;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.codegen.CodeGen;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;
import io.github.thelinuxseal.seal.vm.Trap;
import io.github.thelinuxseal.seal.vm.VM;

/**
 * The JVM backend against the VM (docs/JVM.md §12): every program runs on both, and must print the
 * same and end with the same exit code; every class it generates must pass the bytecode verifier.
 * Random programs check integer widths, conversions and shifts in bulk. `java -cp out
 * io.github.thelinuxseal.seal.JvmTest [random programs] [seed]`.
 */
public final class JvmTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();
    private static final Map<String, String> LIBRARY = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        Main.readSources(Path.of("stdlib"), LIBRARY);
        int randomPrograms = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;

        examples();
        integers();
        conversions();
        control();
        calls();
        floatsAndBits();
        programs();
        classes();
        exceptions();
        lambdas();
        strings();
        collections();
        notYet();
        random(randomPrograms, seed);

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ cases

    private static void examples() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        Main.readSources(Path.of("examples/01-basics"), files);
        String expected = Files.readString(Path.of("examples/01-basics/expected.txt"));
        Run jvm = onJvm(files);
        check(jvm.error() == null && jvm.output().equals(expected) && jvm.code() == 0,
                "examples/01-basics on the JVM\n    expected " + show(expected) + "\n    got      " + jvm);
    }

    private static void integers() {
        same("wrapping at every width", """
                int<<8>> a = 127; a++; pr(a);
                uint<<4>> b = 15; b++; pr(b);
                int<<3>> c = -4; c--; pr(c);
                int<<17>> d = 65535; d = d * 3; pr(d);
                uint<<17>> e = 0; e--; pr(e);
                int<<31>> f = 1073741823; f += 1; pr(f);
                int g = 2147483647; g += 1; pr(g);
                uint h = 0; h -= 1; pr(h);
                int<<33>> i = 4294967295; i = i * 2 + 3; pr(i);
                uint<<33>> j = 0; j--; pr(j);
                int<<63>> k = 4611686018427387903; k++; pr(k);
                int<<64>> l = 9223372036854775807; l++; pr(l);
                uint<<64>> m = 0; m--; pr(m);
                uint<<1>> n = 1; n++; pr(n);
                int<<1>> o = -1; o--; pr(o);
                """);
        same("division and remainder, signed and unsigned", """
                int a = -7; pr(a / 2); pr(a % 2); pr(-2147483647 - 1 / -1);
                uint b = 4294967295; pr(b / 2); pr(b % 10); pr(b / 4000000000);
                uint<<64>> c = 18446744073709551615; pr(c / 3); pr(c % 7); pr(c / 18446744073709551614);
                int<<8>> d = -128; int<<8>> e = -1; pr(d / e); pr(d % 3);
                uint<<12>> f = 4000; pr(f / 7); pr(f % 7);
                int<<64>> g = -9223372036854775807 - 1; pr(g / -1); pr(g % -1);
                """);
        same("unsigned comparisons", """
                uint big = 4294967295; uint one = 1;
                pr(big > one); pr(big < one); pr(big >= 4294967295); pr(one <= big);
                uint<<64>> huge = 18446744073709551615; uint<<64>> small = 5;
                pr(huge > small); pr(huge < small); pr(huge == 18446744073709551615); pr(small != huge);
                uint<<16>> u = 65535; pr(u > 3);
                char ch = 'z'; pr(ch > 'a'); pr(ch < 'a');
                """);
        same("bitwise operators and negation", """
                int<<8>> a = 85; pr(~a); pr(-a); pr(a & 15); pr(a | 128); pr(a ^ 255);
                uint<<8>> b = 85; pr(~b); pr(b ^ 255); pr(b | 128);
                int<<40>> c = 549755813887; pr(~c); pr(-c);
                uint<<64>> d = 1; pr(~d); pr(-d);
                uint e = 1; pr(-e); pr(~e);
                """);
        same("shifts in both directions, past the width too", """
                int x = 40; pr(x <<< 2); pr(x <<< -2); pr(x <<< 40); pr(x <<< -40);
                int n = -40; pr(n <<< -2); pr(n <<< -50); pr(n <<< 31);
                uint<<8>> u = 200; pr(u <<< 1); pr(u <<< -3); pr(u <<< 8);
                int<<8>> s = -100; pr(s <<< 1); pr(s <<< -1); pr(s <<< -7); pr(s <<< -8);
                uint<<64>> w = 18446744073709551615; pr(w <<< -63); pr(w <<< 63); pr(w <<< -64);
                int<<64>> z = -1; pr(z <<< -63); pr(z <<< 64);
                uint<<32>> q = 4294967295; pr(q <<< -31); pr(q <<< 4);
                int amount = -3; int y = 1024; y <<= amount; pr(y); y <<= 5; pr(y);
                """);
        same("increments as expressions", """
                int i = 5; pr(i++); pr(i); pr(++i); pr(i--); pr(--i);
                int<<4>> j = 7; pr(++j); pr(j++); pr(j);
                char c = 'a'; c++; pr(c); pr(c++); pr(c);
                double d = 1.5; d++; pr(d);
                """);
        same("compound assignments", """
                int a = 10; a += 5; a -= 3; a *= 4; a /= 3; a %= 7; a &= 6; a |= 9; a ^= 3; pr(a);
                int<<8>> b = 100; b += 100; pr(b); b *= 3; pr(b);
                int c = 7; c += 2.9; pr(c); c *= 1.5; pr(c); c -= 0.5; pr(c);
                uint<<8>> d = 250; d += 10; pr(d);
                int<<64>> e = 1; e <<= 62; pr(e); e <<= 1; pr(e);
                int f = 3; pr(f += 4); pr(f);
                """);
    }

    private static void conversions() {
        same("casts between integer widths", """
                int<<64>> big = -1234567890123;
                pr((int)big); pr((uint)big); pr((int<<8>>)big); pr((uint<<8>>)big); pr((int<<40>>)big); pr((uint<<40>>)big);
                pr((uint<<64>>)big); pr((char)big); pr((int<<1>>)big); pr((uint<<1>>)big);
                uint u = 4294967295; pr((int<<64>>)u); pr((int)u); pr((uint<<64>>)u); pr((int<<16>>)u);
                int i = -1; pr((uint<<64>>)i); pr((uint)i); pr((int<<64>>)i);
                char c = 'A'; pr((int)c); pr((uint<<8>>)c); pr((int<<8>>)(c + 200));
                """);
        same("integers to floating point and back", """
                int<<64>> a = 9007199254740993; pr((double)a); pr((float)a);
                uint<<64>> b = 18446744073709551615; pr((double)b); pr((float)b);
                uint c = 4294967295; pr((double)c); pr((float)c);
                int d = -7; pr((double)d); pr((float)d);
                double e = 3.99; pr((int)e); pr((int)-e); pr((uint<<8>>)e);
                double big = 1e20; pr((int<<64>>)big); pr((int)big); pr((int<<8>>)big); pr((uint<<64>>)big);
                double neg = -1e20; pr((int<<64>>)neg); pr((int)neg);
                double nan = 0.0 / 0.0; pr((int)nan); pr((int<<64>>)nan);
                float f = 2.75; pr((int)f); pr((double)f); pr((int<<64>>)(f * 1e10));
                double small = -2.5; pr((uint)small); pr((uint<<64>>)small); pr((char)65.7);
                """);
        same("implicit narrowing where stored and passed", """
                int a = 2.5; pr(a);
                int<<8>> b = a + 300; pr(b);
                double d = 7.9; int e = d; pr(e);
                int minus = -1; uint<<8>> f = minus; pr(f);
                pr(half(9));
                pr(small(1000));
                """, """
                static int half(double x) { return x / 2; }
                static int<<8>> small(int x) { return x; }
                """);
        same("mixed arithmetic widens", """
                int i = 7; double d = 0.5; pr(i + d); pr(i * 2.0); pr(i / 2);
                int<<8>> s = 100; int t = 1000; pr(s + t);
                char c = 'a'; pr(c + 1); pr(c - 'a'); pr((char)(c + 1));
                float f = 1.5; pr(f + 1); pr(f * d);
                """);
    }

    private static void control() {
        same("if, while, for and labeled loops", """
                for (int i = 1; i <= 15; i++) {
                    if (i % 15 == 0) { System.stdout.print("FizzBuzz"); }
                    else if (i % 3 == 0) { System.stdout.print("Fizz"); }
                    else if (i % 5 == 0) { System.stdout.print("Buzz"); }
                    else { System.stdout.print(i); }
                    System.stdout.print(" ");
                }
                System.stdout.println();
                int n = 0;
                while (n < 100) { n += 7; if (n % 2 == 0) { continue; } n++; }
                pr(n);
                outer:
                for (int a = 0; a < 5; a++) {
                    for (int b = 0; b < 5; b++) {
                        if (b == 3) { continue outer; }
                        if (a == 3) { break outer; }
                        System.stdout.print(a * 10 + b);
                        System.stdout.print(' ');
                    }
                }
                System.stdout.println();
                int k = 0;
                while (true) { k++; if (k > 4) { break; } }
                pr(k);
                for (;;) { k--; if (k == 0) { break; } }
                pr(k);
                """);
        same("switch statements", """
                for (int i = 0; i < 7; i++) {
                    switch (i) {
                        case 0, 1 -> System.stdout.print("small ");
                        case 2 -> { System.stdout.print("two "); }
                        default -> System.stdout.print("many ");
                    }
                    switch (i) {
                        case 3: System.stdout.print("three ");
                        case 4: System.stdout.print("falls "); break;
                        case 5: System.stdout.print("five "); break;
                    }
                }
                System.stdout.println();
                char c = 'b';
                switch (c) { case 'a' -> pr(1); case 'b' -> pr(2); default -> pr(3); }
                """);
        same("short-circuit operators run only what they must", """
                pr(yes(1) || yes(2)); pr(no(3) && yes(4)); pr(no(5) || yes(6)); pr(!(yes(7) && no(8)));
                bit x = yes(9) ? no(10) : yes(11); pr(x);
                if (no(12) || (yes(13) && !no(14))) { pr(100); }
                """, """
                static bit yes(int i) { System.stdout.print(i); System.stdout.print(' '); return true; }
                static bit no(int i) { System.stdout.print(i); System.stdout.print(' '); return false; }
                """);
        same("the conditional operator", """
                int a = 5;
                pr(a > 3 ? 10 : 20); pr(a > 7 ? 1.5 : 2); pr(a < 0 ? 'n' : 'p');
                int<<64>> big = a > 3 ? 5000000000 : a; pr(big);
                """);
        same("a for-each over an Iterable's numbers isn't needed: while with early returns", """
                pr(firstOver(10)); pr(firstOver(1000));
                """, """
                static int firstOver(int limit)
                {
                    int i = 1;
                    while (i < 100) {
                        if (i * i > limit) { return i; }
                        i++;
                    }
                    return -1;
                }
                """);
    }

    private static void calls() {
        same("recursion", """
                pr(fib(20)); pr(fact(20)); pr(ack(2, 3)); pr(gcd(1071, 462)); pr(isEven(10)); pr(isEven(7));
                """, """
                static int fib(int n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); }
                static int<<64>> fact(int n) { if (n <= 1) { return 1; } return (int<<64>>)n * fact(n - 1); }
                static int ack(int m, int n)
                {
                    if (m == 0) { return n + 1; }
                    if (n == 0) { return ack(m - 1, 1); }
                    return ack(m - 1, ack(m, n - 1));
                }
                static int gcd(int a, int b) { while (b != 0) { int t = a % b; a = b; b = t; } return a; }
                static bit isEven(int n) { if (n == 0) { return true; } return isOdd(n - 1); }
                static bit isOdd(int n) { if (n == 0) { return false; } return isEven(n - 1); }
                """);
        same("overloads with the same JVM descriptor, and binders", """
                int<<8>> small = 5; int<<16>> mid = 300; int plain = 7; uint<<8>> u = 200;
                pr(f(small)); pr(f(mid)); pr(f(plain)); pr(f(u));
                pr(width(small)); pr(width(mid)); pr(width(plain)); pr(width(u)); pr(width((int<<64>>)1));
                pr(twice(small)); pr(twice(mid));
                """, """
                static int f(int<<8>> x) { return 8; }
                static int f(int<<16>> x) { return 16; }
                static int f(int x) { return 32; }
                static int f(uint<<8>> x) { return -8; }
                static int width(int<<int W>> x) { return W; }
                static int width(uint<<int W>> x) { return -W; }
                static int<<W>> twice(int<<int W>> x) { return x * 2; }
                """);
        same("static methods of other types and the library", """
                pr(Util.square(12)); pr(Util.LIMIT); pr(Math.max(3, 9)); pr(Math.abs(-5)); pr(Math.sqrt(2.0));
                pr(Math.pow(2.0, 10.0)); pr(Integer.max(4, -4)); pr(Integer.MAX_INT); pr(Math.abs((int<<8>>)-100));
                pr(Math.floor(-2.5)); pr(Math.PI); pr(Integer.gcd(12, 18));
                """, "", "Util", """
                struct Util
                {
                    public const int LIMIT = 10 * 10;
                    public static int square(int x) { return x * x; }
                }
                """);
        same("void methods and early returns", """
                count(5); count(-1);
                """, """
                static void count(int n)
                {
                    if (n < 0) { System.stdout.println("negative"); return; }
                    for (int i = 0; i < n; i++) { System.stdout.print(i); }
                    System.stdout.println();
                }
                """);
    }

    private static void floatsAndBits() {
        same("floating point and its printing", """
                double a = 0.1 + 0.2; pr(a); pr(1e10); pr(1.0 / 3); pr(-0.0); pr(100.0); pr(1e-5); pr(12345678.9);
                float f = 0.1; pr(f); pr(f * 3); pr((float)1e10); pr(f / 0);
                double nan = 0.0 / 0.0; pr(nan); pr(nan == nan); pr(nan != nan); pr(nan < 1.0); pr(nan >= 1.0);
                pr(1.0 / 0.0); pr(-1.0 / 0.0); pr(2.5 > 2); pr(-a);
                """);
        same("bits", """
                bit t = true; bit f = false;
                pr(t ^ f); pr(t & f); pr(t | f); pr(!t); pr(t == f); pr(t != f);
                bit x = 3 < 4 && 5 > 4; pr(x);
                """);
        same("characters", """
                char c = 'q'; System.stdout.print(c); System.stdout.println(c);
                for (char x = 'a'; x <= 'e'; x++) { System.stdout.print(x); }
                System.stdout.println();
                int code = 'A'; pr(code); char back = 66; pr(back);
                char next = (char)(c + 1); System.stdout.println(next);
                pr(c - 'a'); pr('\\n'); pr('\\u00e9' > 'z');
                """);
        same("constants and compile-time values", """
                pr(sizeof(int<<17>>)); pr(sizeof(double)); pr(1 + 2 * 3); pr(7 / 2); pr(7.0 / 2);
                pr(Integer.MIN_INT); pr(Integer.MAX_UINT64);
                System.stdout.println("text" + " joined" + " at compile time");
                System.stdout.println(\"""
                    a text block
                      indented
                    \""");
                """);
    }

    private static void programs() {
        program("exit codes come from main", Map.of("Main.seal", """
                struct Main { static int main() { System.stdout.println("bye"); return 3; } }
                """), "bye\n", 3);
        program("System.exit ends the program at once", Map.of("Main.seal", """
                struct Main
                {
                    static void stop(int code) { System.exit(code); }
                    static int main() { System.stdout.print("a"); stop(5); System.stdout.print("b"); return 0; }
                }
                """), "a", 5);
        program("a void main exits with 0", Map.of("Main.seal", """
                struct Main { static void main() { System.stderr.println("to stderr"); System.stdout.print(1); } }
                """), "1", 0);
        program("an int<<64>> main gives its low 32 bits", Map.of("Main.seal", """
                struct Main { static int<<64>> main() { return 4294967298; } }
                """), "", 2);
        program("a program in a package", Map.of("com/example/Main.seal", """
                package com.example;

                struct Main { static int main() { System.stdout.println(Helper.twice(21)); return 0; } }
                """, "com/example/Helper.seal", """
                package com.example;

                struct Helper { public static int twice(int x) { return x * 2; } }
                """), "42\n", 0, "com.example.Main");

        // Division by zero stops the program on both, after what it printed.
        Map<String, String> fault = Map.of("Main.seal", """
                struct Main
                {
                    static int divide(int a, int b) { return a / b; }
                    static int main() { System.stdout.print("before "); System.stdout.print(divide(1, 0)); return 0; }
                }
                """);
        Run vm = onVm(fault, "Main");
        Run jvm = onJvm(fault, "Main");
        check(vm.code() == 70 && jvm.code() == 70 && vm.output().equals(jvm.output()) && jvm.output().equals("before ")
                        && jvm.fault() != null && JvmRunner.describe(jvm.fault()).startsWith("trap: division by zero")
                        && JvmRunner.describe(jvm.fault()).contains("at Main.divide (Main.seal:3)"),
                "division by zero stops both\n    VM  " + vm + "\n    JVM " + jvm
                        + (jvm.fault() == null ? "" : "\n    " + JvmRunner.describe(jvm.fault())));
    }

    private static void classes() {
        same("inheritance, constructors, super and virtual calls", """
                Animal a = new Dog("Rex");
                Animal b = new Animal("Thing");
                pr(a.describe()); pr(b.describe()); pr(Animal.count); pr(a.legs);
                Dog d = (Dog)a;
                pr(d.fetch()); pr(a instanceof Dog); pr(b instanceof Dog);
                if (a instanceof Dog dog) { pr(dog.fetch()); }
                Animal none = null;
                pr(none == null); pr(a != b); pr(a == d);
                """, "", "Animal", """
                class Animal
                {
                    public static int count = 0;
                    public string name;
                    public int legs = 4;
                    public new(string n) { name = n; count++; }
                    public string sound() { return "..."; }
                    public string describe() { return name + " says " + sound() + " on " + legs + " legs"; }
                }
                """, "Dog", """
                class Dog extends(Animal)
                {
                    public new(string n) { super(n); legs = 4; }
                    public string sound() { return "woof, " + super.sound(); }
                    public string fetch() { return name + " fetches"; }
                }
                """);
        same("interfaces, default methods, abstract classes and abstract fields", """
                Shape s = new Square(3);
                Named n = new Square(2);
                pr(s.area()); pr(s.describe()); pr(n.label()); pr(n.name);
                Circle c = new Circle();
                c.r = 2;
                pr(c.area()); pr(c.describe()); pr(c.label());
                """, "", "Named", """
                interface Named
                {
                    string name;
                    default string label() { return "<" + name + ">"; }
                }
                """, "Shape", """
                abstract class Shape implements(Named)
                {
                    public abstract int area();
                    public string describe() { return name + " of area " + area(); }
                }
                """, "Square", """
                class Square extends(Shape)
                {
                    public string name = "square";
                    int side;
                    public new(int s) { side = s; }
                    public int area() { return side * side; }
                }
                """, "Circle", """
                class Circle extends(Shape)
                {
                    public string name = "circle";
                    public int r;
                    public int area() { return 3 * r * r; }
                    public string label() { return "(" + name + ")"; }
                }
                """);
        same("generic classes, instantiated per type", """
                Box<int> a = new Box(); a.value = 5;
                Box<string> b = new Box(); b.value = "five";
                Box<Box<int>> c = new Box(); c.value = a;
                pr(a.get() + 1); pr(b.get() + "!"); pr(c.get().get()); pr(a.twice()); pr(b.twice());
                pr(Pair.of(3, "x").second);
                """, "", "Box", """
                class Box<T>
                {
                    public T value;
                    public T get() { return value; }
                    public T twice() { return value + value; }
                }
                """, "Pair", """
                class Pair<A, B>
                {
                    public A first;
                    public B second;
                    public static <X, Y> Pair<X, Y> of(X x, Y y) { Pair<X, Y> p = new Pair(); p.first = x; p.second = y; return p; }
                }
                """);
        same("operators and casts to string on classes", """
                Money a = new Money(150);
                Money b = new Money(275);
                Money c = a + b;
                pr((string)c); pr("total: " + c); pr(a < b); pr(a > b); pr(a == new Money(150)); pr(a != b);
                c += a;
                pr(c.cents);
                """, "", "Money", """
                class Money
                {
                    public int cents;
                    public new(int c) { cents = c; }
                    @overload(Operator.ADD) public Money plus(Money o) { return new Money(cents + o.cents); }
                    @overload(Operator.ADD_ASSIGN) public void add(Money o) { cents += o.cents; }
                    @overload(Operator.LESS) public bit less(Money o) { return cents < o.cents; }
                    @overload(Operator.EQUALS) public bit same(Money o) { return cents == o.cents; }
                    @overload(Operator.CAST) public string text() { return "$" + cents / 100 + "." + cents % 100; }
                }
                """);
        program("an instance main runs on a new main object", Map.of("Main.seal", """
                class Main
                {
                    int count = 2;
                    new() { count += 3; }
                    int main() { count++; System.stdout.println("count " + count); return count; }
                }
                """), "count 6\n", 6);
        Map<String, String> ordered = new LinkedHashMap<>(); // the files in this order: Main's static fields first
        ordered.put("Main.seal", """
                class Main
                {
                    static int a = Other.b + 1;
                    static string s = "s" + a;
                    static int main() { System.stdout.println(a + " " + Other.b + " " + s); return 0; }
                }
                """);
        ordered.put("Other.seal", """
                class Other
                {
                    public static int b = 41;
                }
                """);
        program("static fields are initialized before main, in order", ordered, "1 41 s1\n", 0);
        program("a failed cast stops the program", Map.of("Main.seal", """
                class Main
                {
                    static int main() { Object o = new Main(); System.stdout.print("x"); Other t = (Other)o; return 0; }
                }
                """, "Other.seal", "class Other { }"), "x", 70);
    }

    private static void exceptions() {
        same("try, catch and finally, with every way out", """
                pr(f(0)); pr(f(1)); pr(f(2)); pr(f(3));
                for (int i = 0; i < 4; i++) {
                    try {
                        if (i == 1) { continue; }
                        if (i == 3) { break; }
                        System.stdout.print("body" + i + " ");
                    } finally {
                        System.stdout.print("fin" + i + " ");
                    }
                }
                try {
                    try { throw new IllegalStateException("inner"); }
                    finally { System.stdout.print("inner-fin "); }
                } catch (IllegalStateException e) {
                    System.stdout.print("caught " + e.getMessage() + " ");
                }
                try { nested(); } catch (Bad e) { pr(e.code); pr(e.getMessage()); }
                """, """
                static int f(int n)
                {
                    try {
                        if (n == 0) { return 10; }
                        if (n == 1) { throw new IllegalArgumentException("one"); }
                        if (n == 2) { throw new Bad("two", 2); }
                        return 30;
                    } catch (IllegalArgumentException e) {
                        System.stdout.print(e.getMessage() + " ");
                        return 11;
                    } catch (Exception e) {
                        System.stdout.print("other " + e.getMessage() + " ");
                        return 12;
                    } finally {
                        System.stdout.print("finally" + n + " ");
                    }
                }
                static void nested()
                {
                    try {
                        try { throw new Bad("deep", 7); }
                        catch (IllegalArgumentException e) { System.stdout.print("not this "); }
                    } finally {
                        System.stdout.print("outer-fin ");
                    }
                }
                """, "Bad", """
                class Bad extends(Exception)
                {
                    public int code;
                    public new(string m, int c) { super(m); code = c; }
                }
                """);
        same("try-with-resources closes, in reverse, and keeps what close throws", """
                try (Res a = new Res("a", false); Res b = new Res("b", false)) { pr(1); }
                try (Res a = new Res("c", true)) {
                    throw new IllegalArgumentException("body");
                } catch (IllegalArgumentException e) {
                    pr(e.getMessage() + " " + e.getSuppressed().length() + " " + e.getSuppressed()[0].getMessage());
                }
                """, "", "Res", """
                class Res implements(AutoCloseable)
                {
                    string name;
                    bit fails;
                    public new(string n, bit f) { name = n; fails = f; System.stdout.print("open " + n + " "); }
                    public void close()
                    {
                        System.stdout.print("close " + name + " ");
                        if (fails) { throw new IllegalStateException("close " + name); }
                    }
                }
                """);
        program("an uncaught exception ends the program, as on the VM", Map.of("Main.seal", """
                struct Main
                {
                    static int main() { System.stdout.print("a"); throw new IllegalStateException("broken"); }
                }
                """), "a", 70);
    }

    private static void lambdas() {
        same("lambdas capture copies, and this", """
                int base = 10;
                Function<int, int> add = x -> x + base;
                base = 99;
                pr(add.apply(5));
                Counter c = new Counter();
                Supplier<int> next = c.nextOne();
                pr(next.get()); pr(next.get()); pr(c.n);
                BiFunction<int, int, int> mul = (a, b) -> a * b;
                pr(mul.apply(6, 7));
                Runnable hi = () -> System.stdout.print("hi ");
                hi.run();
                Function<int, Function<int, int>> adder = a -> b -> a + b;
                pr(adder.apply(2).apply(3));
                List<int> xs = new List();
                for (int i = 0; i < 6; i++) { xs.add(i * 7 % 5); }
                xs.sort((a, b) -> b - a);
                xs.forEach(x -> System.stdout.print(x));
                pr(xs.removeIf(x -> x % 2 == 0));
                pr(xs.size());
                """, "", "Counter", """
                class Counter
                {
                    public int n;
                    public Supplier<int> nextOne() { return () -> { n++; return n * 100; }; }
                }
                """);
    }

    private static void strings() {
        same("strings: joining, comparing, searching, building", """
                string s = "Hello";
                s += ", world";
                s += 42;
                s += '!';
                pr(s); pr(s.length()); pr(s.charAt(4)); pr(s.indexOf("world")); pr(s.contains('w'));
                pr(s.substring(7, 12)); pr(s.toUpper()); pr(s.toLower()); pr(s.replace('l', 'L')); pr("ab".repeat(3));
                pr("  trim me  ".trim() + "|"); pr("b" < "a"); pr("apple" == "apple"); pr("x".compare("y"));
                pr(s.startsWith("Hell")); pr(s.endsWith("42!")); pr(s.lastIndexOf('o'));
                List<string> parts = "a,b,,c".split(',');
                pr(parts.size()); pr(parts.get(3));
                pr(Integer.toString(255, 16)); pr(Integer.parse("-1234") + 1); pr("123".toInt() * 2);
                pr("" + 1.5 + " " + (float)2.5 + " " + true + " " + 'c' + " " + (uint<<8>>)200 + " " + (int<<64>>)-5);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < 5; i++) { b.append(i).append(','); }
                b.setLength(b.length() - 1);
                pr(b.toString()); pr(b.reverse().toString());
                string<<5>> word = "fixed";
                pr(word + " and " + word.length());
                string abc = "abc";
                array<char> cs = abc.toCharArray();
                cs[0] = 'z';
                pr(new string(cs));
                try { pr(s.charAt(100)); } catch (IndexOutOfBoundsException e) { pr(e.getMessage()); }
                try { pr(Integer.parse("12x")); } catch (NumberFormatException e) { pr(e.getMessage()); }
                """);
    }

    private static void collections() {
        same("lists, queues, maps and arrays", """
                List<string> names = new List();
                names.add("cy"); names.add("al"); names.add("bo");
                names.sort();
                for (string n : names) { System.stdout.print(n + " "); }
                pr(names.indexOf("bo")); pr(names.contains("zz"));
                names.insert(1, "ab"); pr(names.removeAt(0)); pr(names.removeLast()); pr(names.size());
                Queue<int> q = new Queue();
                for (int i = 0; i < 20; i++) { q.push(i * i); }
                int sum = 0;
                while (!q.isEmpty()) { sum += q.pop(); }
                pr(sum);
                IntMap<string> m = new IntMap();
                for (int i = 0; i < 50; i++) { m.put((int<<64>>)i * 31, "v" + i); }
                m.remove(31);
                pr(m.size()); pr(m.get(62)); pr(m.has(31));
                HashMap<string, int> ages = new HashMap();
                ages.put("ada", 36); ages.put("bob", 40); ages.put("ada", 37);
                pr(ages.get("ada")); pr(ages.getOrDefault("cy", -1)); pr(ages.size());
                for (string k : ages.keys()) { System.stdout.print(k + "=" + ages.get(k) + " "); }
                array<int> xs = new array<int>(5);
                for (int i = 0; i < 5; i++) { xs[i] = 5 - i; }
                xs.sort();
                for (int x : xs) { System.stdout.print(x); }
                array<int> ys = [9, 8, 7];
                pr(ys.length()); pr(ys[1]); pr(xs.copyOf(7).length()); pr(xs.slice(1, 3)[0]);
                array<string> words = new array<string>(2);
                pr(words[0].length()); pr(words.contains(""));
                try { xs[9] = 1; } catch (IndexOutOfBoundsException e) { pr(e.getMessage()); }
                try { List<int> empty = new List(); empty.removeLast(); } catch (NoSuchElementException e) { pr(e.getMessage()); }
                """);
        same("++ and += on elements, through [] and []=", """
                array<int> counts = new array<int>(3);
                string word = "abcabca";
                for (int i = 0; i < word.length(); i++) { counts[word.charAt(i) - 'a']++; }
                pr(counts[0]); pr(counts[1]); pr(counts[2]);
                array<int> xs = new array<int>(1);
                xs[0] = 5;
                int before = xs[0]++;
                int after = ++xs[0];
                pr(before); pr(after); pr(xs[0]--); pr(--xs[0]);
                array<char> cs = new array<char>(1);
                cs[0] = 'a';
                cs[0]++;
                counts[1] += 10;
                counts[2] *= counts[1];
                pr(cs[0]); pr(counts[1]); pr(counts[2]);
                """);
        same("files, read and written", """
                string dir = Files.createTempDirectory("seal-jvm");
                Files.writeString(dir + "/a.txt", "one\\ntwo\\n");
                try (FileWriter w = new FileWriter(dir + "/a.txt", true)) { w.println("three"); }
                pr(Files.readString(dir + "/a.txt").length()); pr(Files.readAllLines(dir + "/a.txt").size());
                for (string line : new FileReader(dir + "/a.txt")) { System.stdout.print(line + ";"); }
                pr(new File(dir + "/a.txt").exists()); pr(new File(dir + "/a.txt").getExtension());
                try { Files.readString(dir + "/missing"); } catch (FileNotFoundException e) { pr(e.getMessage().startsWith("no such file")); }
                Files.deleteRecursively(dir);
                pr(Files.exists(dir));
                """);
    }

    /** What isn't on the JVM yet is reported, at its line, rather than generated wrong. */
    private static void notYet() {
        unsupported("structs", "Vec v = new Vec(); System.stdout.println(v.x);", "not available on the JVM yet");
        unsupported("fixed arrays", "array<int><<3>> xs; xs[0] = 1;", "not available on the JVM yet");
        unsupported("fixed point", "fixed<<8, 8>> x = 1.5; System.stdout.println(x);", "not available on the JVM yet");
        unsupported("addresses", "addr<int> p = new<int>(1); delete p;", "not available on the JVM yet");
    }

    // ================================================================ random programs

    private static final int[] WIDTHS = {1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 48, 63, 64};

    /**
     * Random programs over random integer widths, chars and floating point: casts, arithmetic,
     * shifts, comparisons, compound assignments and ++, each value printed, on both backends.
     */
    private static void random(int count, long seed) {
        Random rnd = new Random(seed);
        for (int p = 0; p < count; p++) {
            Gen g = new Gen(rnd);
            String body = g.program();
            Map<String, String> files = Map.of("Main.seal", "struct Main\n{\n" + HELPERS
                    + "    static int main()\n    {\n" + body + "        return 0;\n    }\n}\n");
            Run vm = onVm(files, "Main");
            if (vm.error() != null) {
                failures.add("random program " + p + " (seed " + seed + ") doesn't compile: " + vm.error() + "\n" + body);
                continue;
            }
            Run jvm = onJvm(files, "Main");
            check(jvm.error() == null && vm.output().equals(jvm.output()) && vm.code() == jvm.code(),
                    "random program " + p + " (seed " + seed + ")\n" + body + "\n    VM  " + vm + "\n    JVM " + jvm
                            + firstDifference(vm.output(), jvm.output()));
        }
    }

    private static String firstDifference(String a, String b) {
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) {
                return "\n    first difference at output line " + (i + 1) + ": VM " + x[i] + ", JVM " + y[i];
            }
        }
        return "";
    }

    private static final class Gen {
        final Random rnd;
        final List<String> vars = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final StringBuilder out = new StringBuilder();

        Gen(Random rnd) {
            this.rnd = rnd;
        }

        String intType() {
            if (rnd.nextInt(10) == 0) {
                return "char";
            }
            int w = WIDTHS[rnd.nextInt(WIDTHS.length)];
            return (rnd.nextBoolean() ? "int" : "uint") + "<<" + w + ">>";
        }

        String numberType() {
            return switch (rnd.nextInt(8)) {
                case 0 -> "double";
                case 1 -> "float";
                default -> intType();
            };
        }

        boolean isFloat(String t) {
            return t.equals("double") || t.equals("float");
        }

        String program() {
            int n = 3 + rnd.nextInt(4);
            for (int i = 0; i < n; i++) {
                String t = numberType();
                String v = "v" + i;
                String init = isFloat(t) ? (rnd.nextBoolean() ? "-" : "") + rnd.nextInt(100000) + "." + rnd.nextInt(1000)
                        + (rnd.nextInt(4) == 0 ? "e" + (rnd.nextInt(40) - 10) : "")
                        : Long.toString(rnd.nextLong() >> rnd.nextInt(64));
                out.append("        int<<64>> s").append(i).append(" = ").append(isFloat(t) ? "0" : init).append(";\n");
                out.append("        ").append(t).append(' ').append(v).append(" = ")
                        .append(isFloat(t) ? init : "(" + t + ")s" + i).append(";\n");
                vars.add(v);
                types.add(t);
            }
            int statements = 6 + rnd.nextInt(8);
            for (int i = 0; i < statements; i++) {
                int pick = rnd.nextInt(vars.size());
                String v = vars.get(pick);
                String t = types.get(pick);
                switch (rnd.nextInt(6)) {
                    case 0 -> out.append("        pr(").append(expr(numberType(), 3)).append(");\n");
                    case 1 -> out.append("        pr(").append(comparison()).append(");\n");
                    case 2 -> out.append("        ").append(v).append(" = ").append(expr(t, 3)).append(";\n");
                    case 3 -> out.append("        ").append(compound(v, t)).append(";\n");
                    case 4 -> out.append("        pr(").append(rnd.nextBoolean() ? v + "++" : "--" + v).append(");\n");
                    default -> out.append("        pr(").append(v).append(");\n");
                }
            }
            for (String v : vars) {
                out.append("        pr(").append(v).append(");\n");
            }
            return out.toString();
        }

        String compound(String v, String t) {
            if (isFloat(t)) {
                return v + " " + pickOf("+=", "-=", "*=", "/=") + " " + expr(t, 2);
            }
            String op = pickOf("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=");
            if (op.equals("<<=")) {
                return v + " <<= (int)(" + expr(intType(), 1) + ")";
            }
            if (t.equals("char")) {
                return v + " " + pickOf("+=", "-=") + " (char)(" + expr(intType(), 1) + ")";
            }
            if ((op.equals("/=") || op.equals("%=")) && !t.equals("int<<1>>")) {
                return v + " " + op + " (" + t + ")((" + expr(t, 1) + ") | (" + t + ")1)";
            }
            if (op.equals("/=") || op.equals("%=")) {
                return v + " += " + expr(t, 1);
            }
            if (rnd.nextInt(4) == 0 && !op.contains("&") && !op.contains("|") && !op.contains("^")
                    && !op.contains("%")) {
                return v + " " + op + " " + expr("double", 1); // narrowed back (§41.6)
            }
            return v + " " + op + " " + expr(t, 2);
        }

        String comparison() {
            String t = numberType();
            String op = pickOf("<", "<=", ">", ">=", "==", "!=");
            return "(" + expr(t, 2) + ") " + op + " (" + expr(t, 2) + ")";
        }

        String pickOf(String... options) {
            return options[rnd.nextInt(options.length)];
        }

        String leaf(String t) {
            int i = rnd.nextInt(vars.size());
            return "(" + t + ")" + vars.get(i);
        }

        /** An expression of type t (as a cast to t). */
        String expr(String t, int depth) {
            if (depth == 0 || rnd.nextInt(4) == 0) {
                return leaf(t);
            }
            boolean f = isFloat(t);
            switch (rnd.nextInt(5)) {
                case 0 -> {
                    return "(" + t + ")(" + expr(numberType(), depth - 1) + ")";
                }
                case 1 -> {
                    if (f) {
                        return "(" + t + ")(-(" + expr(t, depth - 1) + "))";
                    }
                    return "(" + t + ")(" + pickOf("-", "~") + "(" + expr(t, depth - 1) + "))";
                }
                case 2 -> {
                    return "(" + t + ")(" + comparison() + " ? " + expr(t, depth - 1) + " : " + expr(t, depth - 1) + ")";
                }
                case 3 -> {
                    if (f) {
                        return leaf(t);
                    }
                    return "(" + t + ")((" + expr(t, depth - 1) + ") <<< (int)(" + expr(intType(), depth - 1) + "))";
                }
                default -> {
                    String op = f ? pickOf("+", "-", "*", "/") : pickOf("+", "-", "*", "/", "%", "&", "|", "^");
                    String right = expr(t, depth - 1);
                    if ((op.equals("/") || op.equals("%")) && !f) {
                        if (t.equals("int<<1>>")) {
                            op = "+";
                        } else {
                            right = "(" + t + ")((" + right + ") | (" + t + ")1)";
                        }
                    }
                    return "(" + t + ")((" + expr(t, depth - 1) + ") " + op + " (" + right + "))";
                }
            }
        }
    }

    // ================================================================ running

    /** Helpers every program gets: pr(x) prints x and a space, for every kind of number, bit and string. */
    private static final String HELPERS = """
                static void pr(string x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(int<<int W>> x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(uint<<int W>> x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(int x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(char x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(double x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(float x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(bit x) { System.stdout.print(x); System.stdout.print(' '); }
            """;

    private record Run(String output, int code, String error, Throwable fault) {
        @Override
        public String toString() {
            return show(output) + ", exit " + code + (error == null ? "" : ", " + error)
                    + (fault == null ? "" : ", " + fault);
        }
    }

    /** body runs in main; extra are more members of Main; then pairs of file name and source. */
    private static void same(String name, String body, String... more) {
        Map<String, String> files = new LinkedHashMap<>();
        String extra = more.length > 0 ? more[0] : "";
        files.put("Main.seal", "struct Main\n{\n" + HELPERS + extra + "    static int main()\n    {\n" + body
                + "\n        System.stdout.println();\n        return 0;\n    }\n}\n");
        for (int i = 1; i + 1 < more.length; i += 2) {
            files.put(more[i] + ".seal", more[i + 1]);
        }
        Run vm = onVm(files, "Main");
        Run jvm = onJvm(files, "Main");
        check(vm.error() == null && jvm.error() == null && vm.output().equals(jvm.output()) && vm.code() == jvm.code(),
                name + "\n    VM  " + vm + "\n    JVM " + jvm + firstDifference(vm.output(), jvm.output()));
    }

    private static void program(String name, Map<String, String> files, String output, int code) {
        program(name, files, output, code, "Main");
    }

    private static void program(String name, Map<String, String> files, String output, int code, String main) {
        Run vm = onVm(files, main);
        Run jvm = onJvm(files, main);
        check(vm.output().equals(output) && vm.code() == code && jvm.output().equals(output) && jvm.code() == code
                        && jvm.error() == null,
                name + "\n    expected " + show(output) + ", exit " + code + "\n    VM  " + vm + "\n    JVM " + jvm);
    }

    private static void unsupported(String name, String body, String expected) {
        Map<String, String> files = Map.of("Main.seal", "struct Main\n{\n" + HELPERS + "    static int main()\n    {\n"
                + body + "\n        return 0;\n    }\n}\n", "Vec.seal", "struct Vec { public int x; }");
        Checker checker = Checker.analyze(files, LIBRARY);
        if (!checker.diagnostics().isEmpty()) {
            failures.add(name + ": doesn't compile: " + checker.diagnostics());
            return;
        }
        JvmGen.Result r = JvmGen.generate(checker, "Main");
        check(r.errors().stream().anyMatch(d -> d.toString().contains(expected) && d.line() >= 1),
                name + ": expected an error containing " + show(expected) + ", got " + r.errors());
    }

    private static Run onVm(Map<String, String> files, String main) {
        Checker checker = Checker.analyze(files, LIBRARY);
        if (!checker.diagnostics().isEmpty()) {
            return new Run("", -1, "errors: " + checker.diagnostics(), null);
        }
        CodeGen.Result gen = CodeGen.generate(checker, main);
        if (!gen.errors().isEmpty()) {
            return new Run("", -1, "errors: " + gen.errors(), null);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VM vm = new VM(gen.program(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        vm.setError(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        vm.setMaxSteps(50_000_000);
        try {
            long code = vm.run();
            return new Run(bytes.toString(StandardCharsets.UTF_8), (int) code, null, null);
        } catch (Trap t) {
            return new Run(bytes.toString(StandardCharsets.UTF_8), 70, null, t);
        }
    }

    private static Run onJvm(Map<String, String> files) {
        return onJvm(files, "Main");
    }

    private static Run onJvm(Map<String, String> files, String main) {
        Checker checker = Checker.analyze(files, LIBRARY);
        if (!checker.diagnostics().isEmpty()) {
            return new Run("", -1, "errors: " + checker.diagnostics(), null);
        }
        JvmGen.Result gen = JvmGen.generate(checker, main);
        if (!gen.errors().isEmpty()) {
            return new Run("", -1, "errors: " + gen.errors(), null);
        }
        List<String> problems = JvmRunner.verify(gen);
        if (!problems.isEmpty()) {
            return new Run("", -1, "verification fails: " + problems, null);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        JvmRunner.Outcome o = JvmRunner.run(gen, new PrintStream(bytes, true, StandardCharsets.UTF_8), err);
        return new Run(bytes.toString(StandardCharsets.UTF_8), o.code(), null, o.fault());
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
