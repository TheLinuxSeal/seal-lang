package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;

/**
 * The JVM backend (docs/JVM.md §12): every program must print what is expected and end with the
 * expected exit code, and every class it generates must pass the bytecode verifier. Random
 * programs check integer widths, conversions and shifts in bulk, against the output recorded in
 * test/random-programs.txt, which the VM gave before it was removed and the JVM gave the same.
 * `java -cp out io.github.thelinuxseal.seal.JvmTest [random programs] [seed]`; with
 * -Dseal.random.record=true, it records the outputs of the random programs anew, after a change
 * to what they are.
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
        values();
        fixedPoint();
        pointers();
        random(randomPrograms, seed);
        randomPure(Math.max(1, randomPrograms / 3), seed);
        writeRecorded();

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
        same("wrapping at every width", "-128 0 3 65533 131071 -1073741824 -2147483648 4294967295 1 8589934591 -4611686018427387904 -9223372036854775808 18446744073709551615 0 0 \n", """
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
        same("division and remainder, signed and unsigned", "-3 -1 -2147483646 2147483647 5 1 6148914691236517205 1 1 -128 -2 571 3 -9223372036854775808 0 \n", """
                int a = -7; pr(a / 2); pr(a % 2); pr(-2147483647 - 1 / -1);
                uint b = 4294967295; pr(b / 2); pr(b % 10); pr(b / 4000000000);
                uint<<64>> c = 18446744073709551615; pr(c / 3); pr(c % 7); pr(c / 18446744073709551614);
                int<<8>> d = -128; int<<8>> e = -1; pr(d / e); pr(d % 3);
                uint<<12>> f = 4000; pr(f / 7); pr(f % 7);
                int<<64>> g = -9223372036854775807 - 1; pr(g / -1); pr(g % -1);
                """);
        same("unsigned comparisons", "true false true true true false true true true true false \n", """
                uint big = 4294967295; uint one = 1;
                pr(big > one); pr(big < one); pr(big >= 4294967295); pr(one <= big);
                uint<<64>> huge = 18446744073709551615; uint<<64>> small = 5;
                pr(huge > small); pr(huge < small); pr(huge == 18446744073709551615); pr(small != huge);
                uint<<16>> u = 65535; pr(u > 3);
                char ch = 'z'; pr(ch > 'a'); pr(ch < 'a');
                """);
        same("bitwise operators and negation", "-86 -85 5 213 170 170 170 213 -549755813888 -549755813887 18446744073709551614 18446744073709551615 4294967295 4294967294 \n", """
                int<<8>> a = 85; pr(~a); pr(-a); pr(a & 15); pr(a | 128); pr(a ^ 255);
                uint<<8>> b = 85; pr(~b); pr(b ^ 255); pr(b | 128);
                int<<40>> c = 549755813887; pr(~c); pr(-c);
                uint<<64>> d = 1; pr(~d); pr(-d);
                uint e = 1; pr(-e); pr(~e);
                """);
        same("shifts in both directions, past the width too", "160 10 0 0 -10 -1 0 144 25 0 56 -50 -1 -1 1 9223372036854775808 0 -1 0 1 4294967280 128 4096 \n", """
                int x = 40; pr(x <<< 2); pr(x <<< -2); pr(x <<< 40); pr(x <<< -40);
                int n = -40; pr(n <<< -2); pr(n <<< -50); pr(n <<< 31);
                uint<<8>> u = 200; pr(u <<< 1); pr(u <<< -3); pr(u <<< 8);
                int<<8>> s = -100; pr(s <<< 1); pr(s <<< -1); pr(s <<< -7); pr(s <<< -8);
                uint<<64>> w = 18446744073709551615; pr(w <<< -63); pr(w <<< 63); pr(w <<< -64);
                int<<64>> z = -1; pr(z <<< -63); pr(z <<< 64);
                uint<<32>> q = 4294967295; pr(q <<< -31); pr(q <<< 4);
                int amount = -3; int y = 1024; y <<= amount; pr(y); y <<= 5; pr(y);
                """);
        same("increments as expressions", "5 6 7 7 5 -8 -8 -7 b b c 2.5 \n", """
                int i = 5; pr(i++); pr(i); pr(++i); pr(i--); pr(--i);
                int<<4>> j = 7; pr(++j); pr(j++); pr(j);
                char c = 'a'; c++; pr(c); pr(c++); pr(c);
                double d = 1.5; d++; pr(d);
                """);
        same("compound assignments", "8 -56 88 9 13 12 4 4611686018427387904 -9223372036854775808 7 7 \n", """
                int a = 10; a += 5; a -= 3; a *= 4; a /= 3; a %= 7; a &= 6; a |= 9; a ^= 3; pr(a);
                int<<8>> b = 100; b += 100; pr(b); b *= 3; pr(b);
                int c = 7; c += 2.9; pr(c); c *= 1.5; pr(c); c -= 0.5; pr(c);
                uint<<8>> d = 250; d += 10; pr(d);
                int<<64>> e = 1; e <<= 62; pr(e); e <<= 1; pr(e);
                int f = 3; pr(f += 4); pr(f);
                """);
    }

    private static void conversions() {
        same("casts between integer widths", "-1912276171 2382691125 53 53 -135056262347 964455365429 18446742839141661493 \ufb35 -1 1 4294967295 -1 4294967295 -1 18446744073709551615 4294967295 -1 65 65 9 \n", """
                int<<64>> big = -1234567890123;
                pr((int)big); pr((uint)big); pr((int<<8>>)big); pr((uint<<8>>)big); pr((int<<40>>)big); pr((uint<<40>>)big);
                pr((uint<<64>>)big); pr((char)big); pr((int<<1>>)big); pr((uint<<1>>)big);
                uint u = 4294967295; pr((int<<64>>)u); pr((int)u); pr((uint<<64>>)u); pr((int<<16>>)u);
                int i = -1; pr((uint<<64>>)i); pr((uint)i); pr((int<<64>>)i);
                char c = 'A'; pr((int)c); pr((uint<<8>>)c); pr((int<<8>>)(c + 200));
                """);
        same("integers to floating point and back", "9.007199254740992E15 9.007199E15 1.8446744073709552E19 1.8446744E19 4.294967295E9 4.2949673E9 -7.0 -7.0 3 -3 3 9223372036854775807 -1 -1 9223372036854775807 -9223372036854775808 0 0 0 2 2.75 27499999232 4294967294 18446744073709551614 A \n", """
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
        same("implicit narrowing where stored and passed", "2 46 7 255 4 -24 \n", """
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
        same("mixed arithmetic widens", "7.5 14.0 3 1100 98 0 b 2.5 0.75 \n", """
                int i = 7; double d = 0.5; pr(i + d); pr(i * 2.0); pr(i / 2);
                int<<8>> s = 100; int t = 1000; pr(s + t);
                char c = 'a'; pr(c + 1); pr(c - 'a'); pr((char)(c + 1));
                float f = 1.5; pr(f + 1); pr(f * d);
                """);
    }

    private static void control() {
        same("if, while, for and labeled loops", "1 2 Fizz 4 Buzz Fizz 7 8 Fizz Buzz 11 Fizz 13 14 FizzBuzz \n104 0 1 2 10 11 12 20 21 22 \n5 0 \n", """
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
        same("switch statements", "small small two many three falls many falls many five many \n2 \n", """
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
        same("short-circuit operators run only what they must", "1 true 3 false 5 6 true 7 8 true 9 10 false 12 13 14 100 \n", """
                pr(yes(1) || yes(2)); pr(no(3) && yes(4)); pr(no(5) || yes(6)); pr(!(yes(7) && no(8)));
                bit x = yes(9) ? no(10) : yes(11); pr(x);
                if (no(12) || (yes(13) && !no(14))) { pr(100); }
                """, """
                static bit yes(int i) { System.stdout.print(i); System.stdout.print(' '); return true; }
                static bit no(int i) { System.stdout.print(i); System.stdout.print(' '); return false; }
                """);
        same("the conditional operator", "10 2.0 p 5000000000 \n", """
                int a = 5;
                pr(a > 3 ? 10 : 20); pr(a > 7 ? 1.5 : 2); pr(a < 0 ? 'n' : 'p');
                int<<64>> big = a > 3 ? 5000000000 : a; pr(big);
                """);
        same("a for-each over an Iterable's numbers isn't needed: while with early returns", "4 32 \n", """
                pr(firstOver(10)); pr(firstOver(1000));
                """, """
                static int firstOver(int limit) {
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
        same("recursion", "6765 2432902008176640000 9 21 true false \n", """
                pr(fib(20)); pr(fact(20)); pr(ack(2, 3)); pr(gcd(1071, 462)); pr(isEven(10)); pr(isEven(7));
                """, """
                static int fib(int n) { if (n < 2) { return n; } return fib(n - 1) + fib(n - 2); }
                static int<<64>> fact(int n) { if (n <= 1) { return 1; } return (int<<64>>)n * fact(n - 1); }
                static int ack(int m, int n) {
                    if (m == 0) { return n + 1; }
                    if (n == 0) { return ack(m - 1, 1); }
                    return ack(m - 1, ack(m, n - 1));
                }
                static int gcd(int a, int b) { while (b != 0) { int t = a % b; a = b; b = t; } return a; }
                static bit isEven(int n) { if (n == 0) { return true; } return isOdd(n - 1); }
                static bit isOdd(int n) { if (n == 0) { return false; } return isEven(n - 1); }
                """);
        same("overloads with the same JVM descriptor, and binders", "8 16 32 -8 8 16 32 -8 64 10 600 \n", """
                int<<8>> small = 5; int<<16>> mid = 300; int plain = 7; uint<<8>> u = 200;
                pr(f(small)); pr(f(mid)); pr(f(plain)); pr(f(u));
                pr(width(small)); pr(width(mid)); pr(width(plain)); pr(width(u)); pr(width((int<<64>>)1));
                pr(twice(small)); pr(twice(mid));
                """, """
                static int f(int<<8>> x) { return 8; }
                static int f(int<<16>> x) { return 16; }
                static int f(int x) { return 32; }
                static int f(uint<<8>> x) { return -8; }
                static int width(int<<size W>> x) { return W; }
                static int width(uint<<size W>> x) { return -W; }
                static int<<W>> twice(int<<size W>> x) { return x * 2; }
                """);
        same("static methods of other types and the library", "144 100 9 5 1.4142135623730951 1024.0 4 2147483647 100 -3.0 3.141592653589793 6 \n", """
                pr(Util.square(12)); pr(Util.LIMIT); pr(Math.max(3, 9)); pr(Math.abs(-5)); pr(Math.sqrt(2.0));
                pr(Math.pow(2.0, 10.0)); pr(Integer.max(4, -4)); pr(Integer.MAX_INT); pr(Math.abs((int<<8>>)-100));
                pr(Math.floor(-2.5)); pr(Math.PI); pr(Integer.gcd(12, 18));
                """, "", "Util", """
                struct Util {
                    public const int LIMIT = 10 * 10;
                    public static int square(int x) { return x * x; }
                }
                """);
        same("void methods and early returns", "01234\nnegative\n\n", """
                count(5); count(-1);
                """, """
                static void count(int n) {
                    if (n < 0) { System.stdout.println("negative"); return; }
                    for (int i = 0; i < n; i++) { System.stdout.print(i); }
                    System.stdout.println();
                }
                """);
    }

    private static void floatsAndBits() {
        same("floating point and its printing", "0.30000000000000004 1.0E10 0.3333333333333333 -0.0 100.0 1.0E-5 1.23456789E7 0.1 0.3 1.0E10 Infinity NaN false true false false Infinity -Infinity true -0.30000000000000004 \n", """
                double a = 0.1 + 0.2; pr(a); pr(1e10); pr(1.0 / 3); pr(-0.0); pr(100.0); pr(1e-5); pr(12345678.9);
                float f = 0.1; pr(f); pr(f * 3); pr((float)1e10); pr(f / 0);
                double nan = 0.0 / 0.0; pr(nan); pr(nan == nan); pr(nan != nan); pr(nan < 1.0); pr(nan >= 1.0);
                pr(1.0 / 0.0); pr(-1.0 / 0.0); pr(2.5 > 2); pr(-a);
                """);
        same("bits", "true false true false false true true \n", """
                bit t = true; bit f = false;
                pr(t ^ f); pr(t & f); pr(t | f); pr(!t); pr(t == f); pr(t != f);
                bit x = 3 < 4 && 5 > 4; pr(x);
                """);
        same("characters", "qq\nabcde\n65 B r\n16 \n true \n", """
                char c = 'q'; System.stdout.print(c); System.stdout.println(c);
                for (char x = 'a'; x <= 'e'; x++) { System.stdout.print(x); }
                System.stdout.println();
                int code = 'A'; pr(code); char back = 66; pr(back);
                char next = (char)(c + 1); System.stdout.println(next);
                pr(c - 'a'); pr('\\n'); pr('\\u00e9' > 'z');
                """);
        same("constants and compile-time values", "17 64 7 3 3.5 -2147483648 18446744073709551615 text joined at compile time\na text block\n  indented\n\n\n", """
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
                struct Main {
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
                struct Main {
                    static int divide(int a, int b) { return a / b; }
                    static int main() { System.stdout.print("before "); System.stdout.print(divide(1, 0)); return 0; }
                }
                """);
        Run jvm = onJvm(fault, "Main");
        check(jvm.code() == 70 && jvm.output().equals("before ")
                        && jvm.fault() != null && JvmRunner.describe(jvm.fault()).startsWith("trap: division by zero")
                        && JvmRunner.describe(jvm.fault()).contains("at Main.divide (Main.seal:2)"),
                "division by zero stops the program\n    JVM " + jvm
                        + (jvm.fault() == null ? "" : "\n    " + JvmRunner.describe(jvm.fault())));
    }

    private static void classes() {
        same("inheritance, constructors, super and virtual calls", "Rex says woof, ... on 4 legs Thing says ... on 4 legs 2 4 Rex fetches true false Rex fetches true true true \n", """
                Animal a = new Dog("Rex");
                Animal b = new Animal("Thing");
                pr(a.describe()); pr(b.describe()); pr(Animal.count); pr(a.legs);
                Dog d = (Dog)a;
                pr(d.fetch()); pr(a instanceof Dog); pr(b instanceof Dog);
                if (a instanceof Dog dog) { pr(dog.fetch()); }
                Animal none = null;
                pr(none == null); pr(a != b); pr(a == d);
                """, "", "Animal", """
                class Animal {
                    public static int count = 0;
                    public string name;
                    public int legs = 4;
                    public new(string n) { name = n; count++; }
                    public string sound() { return "..."; }
                    public string describe() { return name + " says " + sound() + " on " + legs + " legs"; }
                }
                """, "Dog", """
                class Dog extends(Animal) {
                    public new(string n) { super(n); legs = 4; }
                    public string sound() { return "woof, " + super.sound(); }
                    public string fetch() { return name + " fetches"; }
                }
                """);
        same("interfaces, default methods, abstract classes and abstract fields", "9 square of area 9 <square> square 12 circle of area 12 (circle) \n", """
                Shape s = new Square(3);
                Named n = new Square(2);
                pr(s.area()); pr(s.describe()); pr(n.label()); pr(n.name);
                Circle c = new Circle();
                c.r = 2;
                pr(c.area()); pr(c.describe()); pr(c.label());
                """, "", "Named", """
                interface Named {
                    string name;
                    default string label() { return "<" + name + ">"; }
                }
                """, "Shape", """
                abstract class Shape implements(Named) {
                    public abstract int area();
                    public string describe() { return name + " of area " + area(); }
                }
                """, "Square", """
                class Square extends(Shape) {
                    public string name = "square";
                    int side;
                    public new(int s) { side = s; }
                    public int area() { return side * side; }
                }
                """, "Circle", """
                class Circle extends(Shape) {
                    public string name = "circle";
                    public int r;
                    public int area() { return 3 * r * r; }
                    public string label() { return "(" + name + ")"; }
                }
                """);
        same("generic classes, instantiated per type", "6 five! 5 10 fivefive x \n", """
                Box<int> a = new Box(); a.value = 5;
                Box<string> b = new Box(); b.value = "five";
                Box<Box<int>> c = new Box(); c.value = a;
                pr(a.get() + 1); pr(b.get() + "!"); pr(c.get().get()); pr(a.twice()); pr(b.twice());
                pr(Pair.of(3, "x").second);
                """, "", "Box", """
                class Box<T> {
                    public T value;
                    public T get() { return value; }
                    public T twice() { return value + value; }
                }
                """, "Pair", """
                class Pair<A, B> {
                    public A first;
                    public B second;
                    public static <X, Y> Pair<X, Y> of(X x, Y y) { Pair<X, Y> p = new Pair(); p.first = x; p.second = y; return p; }
                }
                """);
        same("operators and casts to string on classes", "$4.25 total: $4.25 true false true true 575 \n", """
                Money a = new Money(150);
                Money b = new Money(275);
                Money c = a + b;
                pr((string)c); pr("total: " + c); pr(a < b); pr(a > b); pr(a == new Money(150)); pr(a != b);
                c += a;
                pr(c.cents);
                """, "", "Money", """
                class Money {
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
                class Main {
                    int count = 2;
                    new() { count += 3; }
                    int main() { count++; System.stdout.println("count " + count); return count; }
                }
                """), "count 6\n", 6);
        Map<String, String> ordered = new LinkedHashMap<>(); // the files in this order: Main's static fields first
        ordered.put("Main.seal", """
                class Main {
                    static int a = Other.b + 1;
                    static string s = "s" + a;
                    static int main() { System.stdout.println(a + " " + Other.b + " " + s); return 0; }
                }
                """);
        ordered.put("Other.seal", """
                class Other {
                    public static int b = 41;
                }
                """);
        program("static fields are initialized before main, in order", ordered, "1 41 s1\n", 0);
        program("a failed cast stops the program", Map.of("Main.seal", """
                class Main {
                    static int main() { Object o = new Main(); System.stdout.print("x"); Other t = (Other)o; return 0; }
                }
                """, "Other.seal", "class Other { }"), "x", 70);
    }

    private static void exceptions() {
        same("try, catch and finally, with every way out", "finally0 10 one finally1 11 other two finally2 12 finally3 30 body0 fin0 fin1 body2 fin2 fin3 inner-fin caught inner outer-fin 7 deep \n", """
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
                static int f(int n) {
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
                static void nested() {
                    try {
                        try { throw new Bad("deep", 7); }
                        catch (IllegalArgumentException e) { System.stdout.print("not this "); }
                    } finally {
                        System.stdout.print("outer-fin ");
                    }
                }
                """, "Bad", """
                class Bad extends(Exception) {
                    public int code;
                    public new(string m, int c) { super(m); code = c; }
                }
                """);
        same("try-with-resources closes, in reverse, and keeps what close throws", "open a open b 1 close b close a open c close c body 1 close c \n", """
                try (Res a = new Res("a", false); Res b = new Res("b", false)) { pr(1); }
                try (Res a = new Res("c", true)) {
                    throw new IllegalArgumentException("body");
                } catch (IllegalArgumentException e) {
                    pr(e.getMessage() + " " + e.getSuppressed().length() + " " + e.getSuppressed()[0].getMessage());
                }
                """, "", "Res", """
                class Res implements(AutoCloseable) {
                    string name;
                    bit fails;
                    public new(string n, bit f) { name = n; fails = f; System.stdout.print("open " + n + " "); }
                    public void close() {
                        System.stdout.print("close " + name + " ");
                        if (fails) { throw new IllegalStateException("close " + name); }
                    }
                }
                """);
        program("an uncaught exception ends the program, as on the VM", Map.of("Main.seal", """
                struct Main {
                    static int main() { System.stdout.print("a"); throw new IllegalStateException("broken"); }
                }
                """), "a", 70);
    }

    private static void lambdas() {
        same("lambdas capture copies, and this", "15 100 200 2 42 hi 5 432100true 2 \n", """
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
                import seal.util.function.Supplier;
                class Counter {
                    public int n;
                    public Supplier<int> nextOne() { return () -> { n++; return n * 100; }; }
                }
                """);
    }

    private static void strings() {
        same("strings: joining, comparing, searching, building", "Hello, world42! 15 o 7 true world HELLO, WORLD42! hello, world42! HeLLo, worLd42! ababab trim me| false true -1 true true 8 4 c ff -1233 246 1.5 2.5 true c 200 -5 0,1,2,3,4 4,3,2,1,0 fixed and 5 zbc string index 100 out of range for length 15 not a decimal integer: \"12x\" \n", """
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
        same("lists, queues, maps and arrays", "al bo cy 1 false al cy 2 2470 49 v2 false 37 -1 2 ada=37 bob=40 123453 8 7 2 0 true index 9 out of range for an array of length 5 removeLast on an empty List \n", """
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
        same("++ and += on elements, through [] and []=", "3 2 2 5 7 7 5 b 12 24 \n", """
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
        same("files, read and written", "14 3 one;two;three;true txt true false \n", """
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

    /** Structs, uniques, records, enums and fixed arrays, in storage blocks (docs/JVM.md §4, M3). */
    private static void values() {
        same("structs are values: copied, passed, returned, changed in place", "1 10 101 1 11 4 33 1233 1 99 7 10 11 4 6 0 0 true true \n", """
                Vec a = new Vec(1, 2);
                Vec b = a;
                b.x = 10;
                pr(a.x); pr(b.x);
                bump(a); pr(a.x);
                Vec c = a.plus(b);
                pr(c.x); pr(c.y);
                c.scale(3); pr(c.x); pr(c.len2());
                Line l;
                l.from = a; l.to.y = 7;
                Line m = l;
                m.from.x = 99;
                pr(l.from.x); pr(m.from.x); pr(l.to.y);
                Holder h = new Holder();
                h.v.x = 5; h.v.scale(2);
                pr(h.v.x); pr(h.v.plus(a).x);
                Holder.origin.y = 4;
                pr(Holder.origin.y); pr(Holder.origin.plus(a).y);
                Vec z;
                pr(z.x); pr(z.y);
                pr(a == new Vec(1, 2)); pr(a != b);
                """, """
                    static void bump(Vec v) { v.x += 100; pr(v.x); }
                """, "Vec", """
                struct Vec {
                    public int x;
                    public int y;
                    public new() { }
                    public new(int x, int y) { this.x = x; this.y = y; }
                    public Vec plus(Vec o) { return new Vec(x + o.x, y + o.y); }
                    public void scale(int k) { x *= k; y *= k; }
                    public int len2() { return x * x + y * y; }
                    @overload(Operator.EQUALS) public bit eq(Vec o) { return x == o.x && y == o.y; }
                }
                """, "Line", "struct Line { public Vec from; public Vec to; }", "Holder", """
                class Holder {
                    public static Vec origin;
                    public Vec v;
                }
                """);
        same("operators on structs, and structs in arrays", "8 2 -3 true false 8 1 7 4 14 8 2 \n", """
                Num a = new Num(3);
                Num b = new Num(5);
                pr((a + b).v); pr((b - a).v); pr((-a).v); pr(a < b); pr(a >= b);
                a += b; pr(a.v);
                array<Num> ns = new array<Num>(4);
                for (int i = 0; i < ns.length(); i++) { ns[i] = new Num(i * i); }
                ns[1].v = 42;
                Num got = ns[1];
                got.v = 7;
                pr(ns[1].v); pr(got.v); pr(ns.length());
                int total = 0;
                for (Num n : ns) { total += n.v; }
                pr(total);
                List<Num> list = new List();
                list.add(a); list.add(b);
                a.v = 0;
                pr(list.get(0).v); pr(list.size());
                """, "", "Num", """
                struct Num {
                    public int v;
                    public new() { }
                    public new(int v) { this.v = v; }
                    @overload(Operator.ADD) public Num add(Num o) { return new Num(v + o.v); }
                    @overload(Operator.ADD_ASSIGN) public void addTo(Num o) { v += o.v; }
                    @overload(Operator.SUBTRACT) public Num sub(Num o) { return new Num(v - o.v); }
                    @overload(Operator.NEGATE) public Num neg() { return new Num(-v); }
                    @overload(Operator.LESS) public bit lt(Num o) { return v < o.v; }
                    @overload(Operator.EQUALS) public bit eq(Num o) { return v == o.v; }
                }
                """);
        same("records and enums", "4 6 true false Point[x=1, y=2] lo 5 is above hi 1 7 GREEN 1 true 3 RED GREEN BLUE g blue 40 planet 0 \n", """
                Point p = new Point(1, 2);
                Point q = p.plus(new Point(3, 4));
                pr(q.x()); pr(q.y); pr(p == new Point(1, 2)); pr(p == q); pr("" + p);
                try { Range r = new Range(5, 1); } catch (IllegalArgumentException e) { pr(e.getMessage()); }
                pr(new Range(2, 9).size());
                Color c = Color.GREEN;
                pr("" + c); pr(c.ordinal()); pr(c == Color.GREEN); pr(Color.values().length());
                for (Color k : Color.values()) { System.stdout.print(k + " "); }
                switch (c) {
                    case RED -> pr("r");
                    case GREEN -> pr("g");
                    case BLUE -> pr("b");
                }
                string name = switch (Color.BLUE) { case RED -> "red"; case GREEN -> "green"; case BLUE -> "blue"; };
                pr(name); pr(Planet.MARS.weight(10)); pr("" + Planet.EARTH);
                """, "", "Point", """
                record Point(int x, int y) {
                    public Point plus(Point o) { return new Point(x + o.x, y + o.y()); }
                }
                """, "Range", """
                record Range(int lo, int hi) {
                    new {
                        if (lo > hi) { throw new IllegalArgumentException("lo " + lo + " is above hi " + hi); }
                    }
                    public int size() { return hi - lo; }
                }
                """, "Color", "enum Color { RED, GREEN, BLUE; }", "Planet", """
                enum Planet {
                    EARTH(10), MARS(4);
                    int g;
                    new(int g) { this.g = g; }
                    public int weight(int mass) { return mass * g; }
                    @overload(Operator.CAST) public string text() { return "planet " + ordinal(); }
                }
                """);
        same("fixed arrays: copies, nesting, loops, length parameters, bounds", "5 1 5 2 75 18 15 4 0 8 0 77 5 6 4 index 9 out of range for an array of length 5 index -1 out of range for an array of length 5 \n", """
                array<int><<5>> a = [5, 1, 4, 2, 3];
                array<int><<5>> b = a;
                b.sort();
                pr(a[0]); pr(b[0]); pr(b.length()); pr(a.indexOf(4)); pr(sum(a)); pr(sum([1, 2, 3]));
                array<int><<3, 3>> m = [[1, 2, 3], [4, 5, 6], [7, 8, 9]];
                int t = 0;
                for (int i = 0; i < 3; i++) { t += m[i][i]; }
                pr(t);
                array<int><<3>> row = m[1];
                row[0] = 0;
                pr(m[1][0]); pr(row[0]);
                array<Vec><<3>> vs;
                vs[2].x = 8;
                pr(vs[2].x); pr(vs[0].x);
                array<int> dyn = a;
                dyn[0] = 77;
                pr(dyn[0]); pr(a[0]);
                Grid g = new Grid();
                g.cells[1] = 6;
                pr(g.cells[1]); pr(g.cells.length());
                int nine = 9;
                try { pr(a[nine]); } catch (IndexOutOfBoundsException e) { pr(e.getMessage()); }
                int below = -1;
                try { pr(a[below]); } catch (IndexOutOfBoundsException e) { pr(e.getMessage()); }
                """, """
                    static int sum(array<int><<int N>> xs) { int s = 0; for (int x : xs) { s += x; } return s * N; }
                """, "Vec", "struct Vec { public int x; }", "Grid", "class Grid { public array<int><<4>> cells; }");
        same("uniques: destroyed in reverse order, on every way out of their scope", "in ~2 ~1 ~9 ~8 1 ~8 0 0 ~4 ~4 ~4 ~5 caught making made pair ~7 ~7 ~7 end \n~pair ~2 ~1 ~6 ", """
                { Noisy<<1>> a; Noisy<<2>> b; pr("in"); }
                pr(early(true)); pr(early(false));
                for (int i = 0; i < 3; i++) {
                    Noisy<<4>> n;
                    if (i == 1) { continue; }
                    if (i == 2) { break; }
                    pr(i);
                }
                try { Noisy<<5>> n; boom(); } catch (IllegalStateException e) { pr("caught"); }
                Noisy<<6>> kept = make();
                pr("made");
                Pair p;
                pr("pair");
                { array<Noisy<<7>>><<3>> three; }
                pr("end");
                """, """
                    static int boom() { throw new IllegalStateException("boom"); }
                    static int early(bit b) { Noisy<<8>> a; if (b) { Noisy<<9>> c; return 1; } return 0; }
                    static Noisy<<6>> make() { Noisy<<6>> n; pr("making"); return n; }
                """, "Noisy", "unique Noisy<<int ID>> { delete() { System.stdout.print(\"~\" + ID + \" \"); } }",
                "Pair", "unique Pair { Noisy<<1>> a; Noisy<<2>> b; delete() { System.stdout.print(\"~pair \"); } }");
        same("uniques: assigned, as temporaries, in conditions, in static fields", "+1 +2 ~1 after +3 3 ~3 +11 ~11 y +4 ~0 +5 ~5 caught +6 ~6 caught +7 ~7 caught end \n~2 ~4 ", """
                Tag a = new Tag(1);
                a = new Tag(2);
                pr("after");
                pr(new Tag(3).id);
                for (int i = 0; i < 3; i++) {
                    if (i % 2 == 1 && new Tag(10 + i).id > 0) { pr("y"); }
                }
                Registry.tag = new Tag(4);
                try { int x = both(new Tag(5), boom()); } catch (IllegalStateException e) { pr("caught"); }
                try { if (new Tag(6).id + boom() > 0) { pr("no"); } } catch (IllegalStateException e) { pr("caught"); }
                try { pr(new Tag(7).id + boom()); } catch (IllegalStateException e) { pr("caught"); }
                pr("end");
                """, """
                    static int boom() { throw new IllegalStateException("boom"); }
                    static int both(Tag t, int n) { return n; }
                """, "Tag", """
                unique Tag {
                    public int id;
                    public new() { }
                    public new(int id) { this.id = id; System.stdout.print("+" + id + " "); }
                    delete() { System.stdout.print("~" + id + " "); }
                }
                """, "Registry", "unique Registry { public static Tag tag; }");
        Run wide = onJvm(Map.of("Main.seal", "struct Main { static int main() { array<int<<64>>><<2 ** 26>> xs; return 0; } }"));
        check(wide.error() != null && wide.error().contains("offset or size 4294967296 is too large: a block of values on the JVM holds at most 2147483647 bits"),
                "a value too large for a block is an error " + wide);
        program("an instance main() of a unique", Map.of("Main.seal", """
                unique Main {
                    int n = 3;
                    delete() { System.stdout.print(" bye"); }
                    int main() { System.stdout.print("n " + n); return n; }
                }
                """), "n 3 bye", 3);
    }

    /** Fixed point (docs/JVM.md §3.2, M4): its scaled integer wherever a number goes, and its text. */
    private static void fixedPoint() {
        same("fixed point in fields, structs, arrays and statics, as text, through Math and Format", "-3.375 -0.66796875 1.5 2.25 false true -2 -2.25 -2.25 -0.125 -0.5 6.25 0.0 -1.5;0.25;7.9375;3.75 -2.25 1.5 -2.250 1.5 0.3 -3.0 -2.0 2.0 2.25 1.414215087890625 -3.375 0.75 0.5 0.25 0.5 152415765.279024660587310791015625 1763.66828572750091552734375 57.6796875 \n", """
                fixed<<8, 8>> a = 1.5;
                fixed<<8, 8>> b = -2.25;
                pr(a * b); pr(a / b); pr(a % b); pr(-b); pr(a < b); pr(a == 1.5); pr((int)b); pr((double)b);
                Body p = new Body();
                p.mass = b;
                p.at.x = -0.125;
                Spot s = p.at;
                s.x *= 4;
                pr(p.mass); pr(p.at.x); pr(s.x);
                array<ufixed<<4, 12>>> ws = new array<ufixed<<4, 12>>>(3);
                ws[1] = 2.5;
                ws[2] = ws[1] * ws[1];
                pr(ws[2]); pr(ws[0]);
                array<fixed<<4, 4>>><<3>> fs = [-1.5, 0.25, 7.9375];
                for (fixed<<4, 4>> f : fs) { System.stdout.print(f + ";"); }
                Spot.origin.x = 3.75;
                pr(Spot.origin.x);
                pr(Fixed.toString(b)); pr("" + a); pr(string.format("%.3f %s %.1f", b, a, (fixed<<16, 16>>)0.25));
                pr(Math.floor(b)); pr(Math.ceil(b)); pr(Math.round(a)); pr(Math.abs(b)); pr(Math.sqrt((fixed<<16, 16>>)2));
                pr(Fixed.mulExact(a, b)); pr(Fixed.fraction(b));
                ufixed<<0, 64>> half = 0.5;
                pr(half); pr(half * half); pr((double)half);
                fixed<<40, 24>> big = 12345.678;
                pr(big * big); pr(big / 7); pr((fixed<<8, 8>>)big);
                """, "", "Body", "class Body { public fixed<<8, 8>> mass; public Spot at; }",
                "Spot", "struct Spot { public static Spot origin; public fixed<<12, 20>> x; }");
        program("fixed-point division by zero stops the program", Map.of("Main.seal", """
                struct Main {
                    static int main() {
                        fixed<<8, 8>> a = 1.5;
                        fixed<<8, 8>> z = 0;
                        System.stdout.print("before ");
                        System.stdout.print(a / z);
                        return 0;
                    }
                }
                """), "before ", 70);
    }

    /** Addresses (docs/JVM.md §4.4, M5): a block and a bit offset, checked as the VM checks them. */
    private static void pointers() {
        same("addresses of locals, parameters, fields, elements and statics; arithmetic; new and delete", "43 6 3 7 32 3 4 1 true true true 40 28 4321made ~9  hi 4294967295 \n", """
                int x = 10;
                addr<int> p = *x;
                &p = 42;
                addr<addr<int>> pp = *p;
                &(&pp) += 1;
                pr(x);
                bump(5);
                Vec v;
                addr<int> vy = *v.y;
                &vy = 7;
                addr<Vec> pv = *v;
                (&pv).x = 3;
                pr(v.x); pr(v.y); pr(vy - (addr<int>)(addr<void>)pv);
                array<int><<4>> arr = [1, 2, 3, 4];
                addr<int> third = *arr[2];
                pr(&third); pr(&(third + sizeof(int))); pr(&(third - 2 * sizeof(int)));
                pr(third > *arr[0]); pr(third == *arr[2]); pr(third != null);
                Counter.n = 4;
                addr<int> cn = *Counter.n;
                &cn *= 10;
                pr(Counter.n);
                addr<uint<<3>>> tiny = new<uint<<3>>>(8);
                for (int i = 0; i < 8; i++) { &(tiny + i * 3) = (uint<<3>>)i; }
                int total = 0;
                for (int i = 0; i < 8; i++) { total += (int)&(tiny + i * 3); }
                pr(total);
                delete tiny;
                addr<Node> head = null;
                for (int i = 1; i <= 4; i++) { addr<Node> n = new Node(i); (&n).next = head; head = n; }
                for (addr<Node> n = head; n != null; n = (&n).next) { System.stdout.print((&n).value); }
                while (head != null) { addr<Node> next = (&head).next; delete head; head = next; }
                addr<Tag> t = new Tag(9);
                pr("made");
                delete t;
                addr<string> sp = new<string>(2);
                &(sp + sizeof(string)) = "hi";
                pr(&sp); pr(&(sp + sizeof(string)));
                delete sp;
                Vec w;
                addr<uint<<64>>> raw = (addr<uint<<64>>>)(addr<void>)*w;
                w.x = -1;
                pr(&raw);
                """, """
                    static void bump(int n) { addr<int> q = *n; &q += 1; pr(n); }
                """, "Vec", "struct Vec { public int x; public int y; }",
                "Counter", "struct Counter { public static int n; }",
                "Node", "struct Node { public int value; public addr<Node> next; public new(int v) { value = v; } }",
                "Tag", "unique Tag { public int id; public new(int id) { this.id = id; } delete() { System.stdout.print(\"~\" + id + \" \"); } }");
        program("a null address is a trap", Map.of("Main.seal", """
                struct Main { static int main() { addr<int> p = null; System.stdout.print("a"); return &p; } }
                """), "a", 70);
        program("a misaligned access is a trap", Map.of("Main.seal", """
                struct Main { static int main() { addr<int> p = new<int>(2); System.stdout.print("a"); return &(p + 1); } }
                """), "a", 70);
        program("deleting twice is a trap", Map.of("Main.seal", """
                struct Main { static int main() { addr<int> p = new<int>(1); delete p; System.stdout.print("a"); delete p; return 0; } }
                """), "a", 70);
    }

    // ================================================================ random programs

    private static final int[] WIDTHS = {1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 48, 63, 64};

    /** Where the random programs' outputs are recorded, for the default seed and count. */
    private static final Path RECORDED = Path.of("test/random-programs.txt");
    private static final Map<String, String> recorded = new LinkedHashMap<>();
    private static final Map<String, String> recording = new LinkedHashMap<>();

    /**
     * Random programs over random integer widths, chars, fixed point and floating point: casts,
     * arithmetic, shifts, comparisons, compound assignments and ++, each value printed, against
     * their recorded output (with another seed, there is none: they need only run).
     */
    private static void random(int count, long seed) throws IOException {
        Random rnd = new Random(seed);
        for (int p = 0; p < count; p++) {
            Gen g = new Gen(rnd);
            String body = g.program();
            Map<String, String> files = Map.of("Main.seal", "struct Main {\n" + HELPERS
                    + "    static int main() {\n" + body + "        return 0;\n    }\n}\n");
            Run jvm = onJvm(files, "Main");
            String where = "random program " + p + " (seed " + seed + ")\n" + body + "\n    JVM " + jvm;
            compareRecorded("random program " + p, seed, jvm, where);
        }
    }

    /**
     * Checks a random program's run against its recorded output, or (recording) records it: by its
     * key, for the default seed alone.
     */
    private static void compareRecorded(String key, long seed, Run jvm, String where) throws IOException {
        if (jvm.error() != null || jvm.fault() != null) {
            failures.add(where);
            return;
        }
        String line = jvm.code() + "\t" + escape(jvm.output());
        if (seed != 1) {
            check(true, where);
        } else if (Boolean.getBoolean("seal.random.record")) {
            recording.put(key, line);
        } else {
            if (recorded.isEmpty()) {
                for (String l : Files.readAllLines(RECORDED)) {
                    int tab = l.indexOf('\t');
                    recorded.put(l.substring(0, tab), l.substring(tab + 1));
                }
            }
            String expected = recorded.get(key);
            check(line.equals(expected), where + (expected == null ? "\n    no output recorded for it"
                    : firstDifference(unescape(expected.substring(expected.indexOf('\t') + 1)), jvm.output())));
        }
    }

    /** Writes what {@link #compareRecorded} recorded. */
    private static void writeRecorded() throws IOException {
        if (!recording.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            recording.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
            Files.writeString(RECORDED, sb);
        }
    }

    /** An output on one line: a backslash doubled, and what isn't printable ASCII as \\uXXXX. */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '\\') {
                sb.append("\\\\");
            } else if (ch < 0x20 || ch > 0x7e) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && s.charAt(i + 1) == 'u') {
                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 5;
            } else if (ch == '\\') {
                sb.append(s.charAt(++i));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Random pure methods (§17.2), each worked out twice: while compiling, as a const field's value,
     * and while the program runs; each line prints both, which must be the same, and as recorded.
     */
    private static void randomPure(int count, long seed) throws IOException {
        Random rnd = new Random(seed);
        for (int p = 0; p < count; p++) {
            StringBuilder members = new StringBuilder();
            StringBuilder body = new StringBuilder();
            int functions = 4;
            for (int f = 0; f < functions; f++) {
                Gen g = new Gen(rnd);
                String ret = g.numberType();
                while (ret.contains("fixed") && width(ret) > 52) {
                    ret = g.numberType(); // a fixed-point compile-time value is kept as a double
                }
                members.append(g.pureFunction("f" + f, ret));
                members.append("    const ").append(ret).append(" C").append(f).append(" = f").append(f).append("();\n");
                String shown = ret.equals("char") ? "(int)" : ""; // a character may be a space or a newline
                body.append("        pr(").append(shown).append("C").append(f).append("); pr(").append(shown).append("f").append(f)
                        .append("()); System.stdout.println();\n");
            }
            Map<String, String> files = Map.of("Main.seal", "struct Main {\n" + HELPERS + members
                    + "    static int main() {\n" + body + "        return 0;\n    }\n}\n");
            Run jvm = onJvm(files, "Main");
            String where = "random pure methods " + p + " (seed " + seed + ")\n" + members + "\n    JVM " + jvm;
            boolean same = true;
            for (String line : jvm.output().split("\n")) {
                String[] two = line.trim().split(" ");
                same &= two.length == 2 && two[0].equals(two[1]);
            }
            if (!same) {
                failures.add(where + "\n    the value worked out while compiling isn't the one worked out while running");
                continue;
            }
            compareRecorded("random pure methods " + p, seed, jvm, where);
        }
    }

    /** N + M of a fixed-point type's name. */
    private static int width(String fixedType) {
        String[] nm = fixedType.substring(fixedType.indexOf("<<") + 2, fixedType.indexOf(">>")).split(", ");
        return Integer.parseInt(nm[0]) + Integer.parseInt(nm[1]);
    }

    private static String firstDifference(String a, String b) {
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) {
                return "\n    first difference at output line " + (i + 1) + ": expected " + x[i] + ", got " + y[i];
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
            return switch (rnd.nextInt(10)) {
                case 0 -> "double";
                case 1 -> "float";
                case 2, 3 -> fixedType();
                default -> intType();
            };
        }

        /** A fixed-point format with at least one fraction bit, up to 64 bits wide. */
        String fixedType() {
            int w = 2 + rnd.nextInt(63);
            boolean signed = rnd.nextBoolean();
            int m = 1 + rnd.nextInt(signed ? w - 1 : w);
            return (signed ? "fixed" : "ufixed") + "<<" + (w - m) + ", " + m + ">>";
        }

        boolean isFloat(String t) {
            return t.equals("double") || t.equals("float");
        }

        boolean isFixed(String t) {
            return t.contains("fixed");
        }

        /** Whether an exponent of 0 to 3 stays one in t: not in a signed type of 1 or 2 bits. */
        boolean canPower(String t) {
            if (t.equals("char") || t.startsWith("uint")) {
                return true;
            }
            return Integer.parseInt(t.substring(t.indexOf("<<") + 2, t.indexOf(">>"))) >= 3;
        }

        /** Whether ++ and -- apply: a fixed-point type must hold 1. */
        boolean canStep(String t) {
            if (!isFixed(t)) {
                return true;
            }
            int n = Integer.parseInt(t.substring(t.indexOf("<<") + 2, t.indexOf(", ")));
            return n >= (t.startsWith("fixed") ? 2 : 1);
        }

        /** The smallest positive value of a fixed-point type, 2^-M, as a literal. */
        String smallest(String t) {
            int m = Integer.parseInt(t.substring(t.indexOf(", ") + 2, t.indexOf(">>")));
            return new BigDecimal(BigInteger.ONE).divide(new BigDecimal(BigInteger.ONE.shiftLeft(m))).toPlainString();
        }

        /** r as a divisor of fixed-point type t: never zero. */
        String divisor(String t, String r) {
            return "(" + t + ")((" + r + ") != (" + t + ")0 ? (" + r + ") : (" + t + ")" + smallest(t) + ")";
        }

        String program() {
            declare();
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
                    case 4 -> out.append("        pr(").append(!canStep(t) ? v : rnd.nextBoolean() ? v + "++" : "--" + v).append(");\n");
                    default -> out.append("        pr(").append(v).append(");\n");
                }
            }
            for (String v : vars) {
                out.append("        pr(").append(v).append(");\n");
            }
            return out.toString();
        }

        /** A pure method computing a ret from variables of random types, as program() does, without printing. */
        String pureFunction(String name, String ret) {
            declare();
            // Sometimes an array<T>, whose elements serve as more variables, and which it sorts, reverses and swaps.
            int length = rnd.nextBoolean() ? 2 + rnd.nextInt(5) : 0;
            if (length > 0) {
                String et = numberType();
                out.append("        array<").append(et).append("> a = new array<").append(et).append(">(").append(length).append(");\n");
                for (int i = 0; i < length; i++) {
                    out.append("        a[").append(i).append("] = ").append(expr(et, 1)).append(";\n");
                }
                for (int i = 0; i < length; i++) {
                    vars.add("a[" + i + "]");
                    types.add(et);
                }
            }
            int statements = 3 + rnd.nextInt(6);
            for (int i = 0; i < statements; i++) {
                int pick = rnd.nextInt(vars.size());
                String v = vars.get(pick);
                String t = types.get(pick);
                switch (rnd.nextInt(length > 0 ? 4 : 3)) {
                    case 0 -> out.append("        ").append(v).append(" = ").append(expr(t, 3)).append(";\n");
                    case 1 -> out.append("        ").append(compound(v, t)).append(";\n");
                    case 2 -> out.append("        ").append(canStep(t) ? v + "++" : v + " = " + expr(t, 1)).append(";\n");
                    default -> out.append("        a.").append(pickOf("sort()", "reverse()", "swap(0, " + (length - 1) + ")"))
                            .append(";\n");
                }
            }
            return "    static pure " + ret + " " + name + "() {\n" + out + "        return (" + ret + ")("
                    + expr(ret, 3) + ");\n    }\n";
        }

        /** The variables, of random types, with random values. */
        void declare() {
            int n = 3 + rnd.nextInt(4);
            for (int i = 0; i < n; i++) {
                String t = numberType();
                String v = "v" + i;
                boolean real = isFloat(t) || (isFixed(t) && rnd.nextBoolean());
                String init = real ? (rnd.nextBoolean() ? "-" : "") + rnd.nextInt(100000) + "." + rnd.nextInt(1000)
                        + (rnd.nextInt(4) == 0 ? "e" + (rnd.nextInt(40) - 10) : "")
                        : Long.toString(rnd.nextLong() >> rnd.nextInt(64));
                out.append("        int<<64>> s").append(i).append(" = ").append(real ? "0" : init).append(";\n");
                if (real && !isFloat(t)) {
                    out.append("        double r").append(i).append(" = ").append(init).append(";\n"); // wraps at run time
                }
                out.append("        ").append(t).append(' ').append(v).append(" = ")
                        .append(isFloat(t) ? init : real ? "(" + t + ")r" + i : "(" + t + ")s" + i).append(";\n");
                vars.add(v);
                types.add(t);
            }
        }

        String compound(String v, String t) {
            if (isFloat(t)) {
                return v + " " + pickOf("+=", "-=", "*=", "/=") + " " + expr(t, 2);
            }
            if (isFixed(t)) {
                String op = pickOf("+=", "-=", "*=", "/=", "%=");
                if (op.equals("/=") || op.equals("%=")) {
                    return v + " " + op + " " + divisor(t, expr(t, 1));
                }
                if (rnd.nextInt(4) == 0) {
                    return v + " " + op + " " + expr("double", 1); // narrowed back (§41.6)
                }
                return v + " " + op + " " + expr(t, 2);
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
                    if (f || isFixed(t)) {
                        return "(" + t + ")(-(" + expr(t, depth - 1) + "))";
                    }
                    return "(" + t + ")(" + pickOf("-", "~") + "(" + expr(t, depth - 1) + "))";
                }
                case 2 -> {
                    return "(" + t + ")(" + comparison() + " ? " + expr(t, depth - 1) + " : " + expr(t, depth - 1) + ")";
                }
                case 3 -> {
                    if (f || isFixed(t)) {
                        return leaf(t);
                    }
                    return "(" + t + ")((" + expr(t, depth - 1) + ") <<< (int)(" + expr(intType(), depth - 1) + "))";
                }
                default -> {
                    String op = f ? pickOf("+", "-", "*", "/", "**") : isFixed(t) ? pickOf("+", "-", "*", "/", "%")
                            : pickOf("+", "-", "*", "/", "%", "&", "|", "^", "**");
                    String right = expr(t, depth - 1);
                    if (op.equals("**") && !f) {
                        if (!canPower(t)) {
                            op = "*";
                        } else {
                            right = "(" + t + ")((uint<<2>>)(" + right + "))"; // 0 to 3: never a negative exponent
                        }
                    }
                    if ((op.equals("/") || op.equals("%")) && isFixed(t)) {
                        right = divisor(t, right);
                    } else if ((op.equals("/") || op.equals("%")) && !f) {
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
                static void pr(int<<size W>> x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(uint<<size W>> x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(int x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(char x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(double x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(float x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(bit x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(fixed<<size N, size M>> x) { System.stdout.print(x); System.stdout.print(' '); }
                static void pr(ufixed<<size N, size M>> x) { System.stdout.print(x); System.stdout.print(' '); }
            """;

    private record Run(String output, int code, String error, Throwable fault) {
        @Override
        public String toString() {
            return show(output) + ", exit " + code + (error == null ? "" : ", " + error)
                    + (fault == null ? "" : ", " + fault);
        }
    }

    /** body runs in main, which prints expected; extra are more members of Main; then pairs of file name and source. */
    private static void same(String name, String expected, String body, String... more) {
        Map<String, String> files = new LinkedHashMap<>();
        String extra = more.length > 0 ? more[0] : "";
        String main = "struct Main {\n" + HELPERS + extra + "    static int main() {\n" + body
                + "\n        System.stdout.println();\n        return 0;\n    }\n}\n";
        files.put("Main.seal", Imports.of(main) + main);
        for (int i = 1; i + 1 < more.length; i += 2) {
            files.put(more[i] + ".seal", more[i + 1]);
        }
        Run jvm = onJvm(files, "Main");
        check(jvm.error() == null && jvm.fault() == null && jvm.output().equals(expected) && jvm.code() == 0,
                name + "\n    expected " + show(expected) + "\n    JVM " + jvm + firstDifference(expected, jvm.output()));
    }

    private static void program(String name, Map<String, String> files, String output, int code) {
        program(name, files, output, code, "Main");
    }

    private static void program(String name, Map<String, String> files, String output, int code, String main) {
        Run jvm = onJvm(files, main);
        check(jvm.output().equals(output) && jvm.code() == code && jvm.error() == null,
                name + "\n    expected " + show(output) + ", exit " + code + "\n    JVM " + jvm);
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
