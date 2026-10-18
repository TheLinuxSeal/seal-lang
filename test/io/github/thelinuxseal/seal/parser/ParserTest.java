package io.github.thelinuxseal.seal.parser;

import java.util.ArrayList;
import java.util.List;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.ast.AstPrinter;

/** Dependency-free test runner: `java -cp out io.github.thelinuxseal.seal.parser.ParserTest`. */
public final class ParserTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        precedence();
        addressOperators();
        castsAndGenericsInExpressions();
        statements();
        types();
        members();
        headers();
        specExamples();
        nestedTypes();
        packages();
        records();
        errors();

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ expressions

    private static void precedence() {
        expr("a + b * c", "(+ a (* b c))");
        expr("a - b - c", "(- (- a b) c)");
        expr("a <<< 3 + 1", "(<<< a (+ 3 1))");
        expr("x <<< -3", "(<<< x (- 3))");
        expr("a + 1 < b <<< 2", "(< (+ a 1) (<<< b 2))");
        expr("a & b == c", "(== (& a b) c)");
        expr("a | b ^ c & d", "(| a (^ b (& c d)))");
        expr("a < b && c || d", "(|| (&& (< a b) c) d)");
        expr("x = y = 3", "(= x (= y 3))");
        expr("x += 1", "(+= x 1)");
        expr("i++", "(x++ i)");
        expr("--i", "(--x i)");
        expr("-i++", "(- (x++ i))");
        expr("++a.b[i]", "(++x (index (. a b) i))");
        expr("a[i++]--", "(x-- (index a (x++ i)))");
        expr("a+++b", "(+ (x++ a) b)");
        expr("a - --b", "(- a (--x b))");
        expr("(int)++x", "(cast int (++x x))");
        expr("(a)++", "(x++ a)");
        expr("x = y++", "(= x (x++ y))");
        expr("x <<= n", "(<<= x n)");
        expr("-a * !b", "(* (- a) (! b))");
        expr("~x[3]", "(~ (index x 3))");
        expr("x[4..8]", "(slice x 4 8)");
        expr("a.b.c(1, 2)[0]", "(index (call (. (. a b) c) 1 2) 0)");
        expr("Integer.toString(32)", "(call (. Integer toString) 32)");
        expr("\"a\\nb\"", "\"a\\nb\"");
        expr("0x_FF", null); // invalid literal is a lexer error
        expr("0xFF + 1_000", "(+ 0xFF 1_000)");
        expr("1.5e3", "1.5e3");
        expr("true && !false", "(&& true (! false))");
        expr("null", "null");
        expr("super(1)", "(call super 1)");
        expr("super.foo()", "(call (. super foo))");
    }

    private static void addressOperators() {
        expr("&p", "(& p)");
        expr("*x", "(* x)");
        expr("&(&pp)", "(& (& pp))");
        expr("(&p).field", "(. (& p) field)");
        expr("&p = 32", "(= (& p) 32)");
        expr("(&p).field = 32", "(= (. (& p) field) 32)");
        expr("a && &p", "(&& a (& p))");
        expr("a&&&p", "(&& a (& p))");
        expr("a & *b", "(& a (* b))");
        expr("a * *b", "(* a (* b))");
        expr("&(data + i * sizeof(uint<<16>>))", "(& (+ data (* i (sizeof uint<<16>>))))");
    }

    private static void castsAndGenericsInExpressions() {
        expr("(float)occupied / N", "(/ (cast float occupied) N)");
        expr("(float)occupied / (N * K)", "(/ (cast float occupied) (* N K))");
        expr("(int)-x", "(cast int (- x))");
        expr("(Foo)x", "(cast Foo x)");
        expr("[1, 2 + 3, f(x)]", "[1 (+ 2 3) (call f x)]");
        expr("[[1, 2], [3]]", "[[1 2] [3]]");
        expr("[1, 2,]", "[1 2]");
        expr("[]", "[]");
        expr("[1, 2][i]", "(index [1 2] i)");
        expr("f(a, b,)", "(call f a b)");
        stmt("for (int x : xs) { }", "(foreach int x xs (block))");
        stmt("switch (x) { case 1, 2: f(); break; default: g(); }",
                "(switch x (case 1 2 : (call f) (break)) (default : (call g)))");
        stmt("switch (x) { case A -> f(); default -> { } }", "(switch x (case A -> (call f)) (default -> (block)))");
        stmt("for (List<int> row : grid.rows()) f(row);", "(foreach List<int> row (call (. grid rows)) (call f row))");
        stmt("throw new E(\"x\");", "(throw (new E \"x\"))");
        stmt("try { f(); } catch (E e) { g(); } finally { h(); }",
                "(try (block (call f)) (catch E e (block (call g))) (finally (block (call h))))");
        stmt("try { } catch (A a) { } catch (B b) { }", "(try (block) (catch A a (block)) (catch B b (block)))");
        expr("a ? b : c", "(? a b c)");
        expr("Main.<int>largest(xs)", "(call (. Main largest) <int> xs)");
        expr("new array<int>(n + 1)", "(new array<int> (+ n 1))");
        expr("new Point(1, 2)", "(new Point 1 2)");
        expr("xs.<string, List<int>>map(f)", "(call (. xs map) <string, List<int>> f)");
        member("public static <T> T largest(List<T> xs) { }", "(method public static <T> T largest (List<T> xs) (block))");
        member("<A, B> void pair(A a, B b) { }", "(method private <A, B> void pair (A a, B b) (block))");
        expr("this.x = x", "(= (. this x) x)");
        expr("f(this)", "(call f this)");
        expr("(char)(c + 1)", "(cast char (+ c 1))");
        expr("a ? b : c ? d : e", "(? a b (? c d e))");
        expr("x = a || b ? 1 : 2", "(= x (? (|| a b) 1 2))");
        expr("a ? x -> x : y -> y", "(? a (lambda (x) x) (lambda (y) y))");
        stmt("switch (x) { case 1: f(a ? b : c); }", "(switch x (case 1 : (call f (? a b c))))");
        expr("x -> x + 1", "(lambda (x) (+ x 1))");
        expr("(a, b) -> a * b", "(lambda (a b) (* a b))");
        expr("() -> { f(); }", "(lambda () (block (call f)))");
        expr("(int a, List<int> b) -> a", "(lambda (int a List<int> b) a)");
        expr("f(x -> x, y)", "(call f (lambda (x) x) y)");
        expr("(a) - b", "(- a b)");
        stmt("switch (x) { case a -> f(); }", "(switch x (case a -> (call f)))");
        expr("x instanceof Shape", "(instanceof x Shape)");
        expr("x instanceof Shape s && s.f()", "(&& (instanceof x Shape s) (call (. s f)))");
        expr("a.b instanceof List<int> xs", "(instanceof (. a b) List<int> xs)");
        exprError("x instanceof A == true", "comparisons cannot be chained; use parentheses");
        expr("f(\n    a,\n    b,\n)", "(call f a b)");
        exprError("f(,)", "expected an expression, found ','");
        expr("(a)[0]", "(index a 0)");
        expr("(Foo)'a'", "(cast Foo 'a')");
        expr("c - 'a' + 'A'", "(+ (- c 'a') 'A')");
        expr("(Foo)(x)", "(cast Foo x)");
        expr("(a) - b", "(- a b)");
        expr("(a) & b", "(& a b)");
        expr("(a)", "a");
        expr("(addr<int>)p", "(cast addr<int> p)");
        expr("(a < b)", "(< a b)");
        expr("new Array()", "(new Array)");
        expr("new Array(1, 2)", "(new Array 1 2)");
        expr("new<int>(n)", "(new<int> n)");
        expr("new<addr<int>>(n)", "(new<addr<int>> n)");
        expr("sizeof(Array<int><<64>>)", "(sizeof Array<int><<64>>)");
        expr("alignof(T)", "(alignof T)");
    }

    // ================================================================ statements

    private static void statements() {
        stmt("addr<int> p = *x;", "(var addr<int> p (* x))");
        stmt("addr<addr<int>> pp = *p;", "(var addr<addr<int>> pp (* p))");
        stmt("int v = &(&pp);", "(var int v (& (& pp)))");
        stmt("Array<int><<64>> x = new Array();", "(var Array<int><<64>> x (new Array))");
        stmt("Array<addr<int>> <<8>> x;", "(var Array<addr<int>><<8>> x)");
        stmt("Array<addr<int>><<8>> x;", "(var Array<addr<int>><<8>> x)");
        stmt("Array<Foo<<3>>> x;", "(var Array<Foo<<3>>> x)");
        stmt("handle<Buffer<int><<16>>> b;", "(var handle<Buffer<int><<16>>> b)");
        stmt("Map<A<B<C>>, D> m;", "(var Map<A<B<C>>, D> m)");
        stmt("string s;", "(var string s)");
        stmt("fixed<<8, 8>> x = 1.5;", "(var fixed<<8, 8>> x 1.5)");
        stmt("array<ufixed<<0, 16>>><<4>> xs;", "(var array<ufixed<<0, 16>>><<4>> xs)");
        expr("(fixed<<4, 4>>)x", "(cast fixed<<4, 4>> x)");
        stmt("Buf<<>> b;", "(var Buf<<>> b)");
        stmt("x = 3;", "(= x 3)");
        stmt("a < b;", "(< a b)");
        stmt("x[3] = 1;", "(= (index x 3) 1)");
        stmt("foo(1);", "(call foo 1)");
        stmt("delete p;", "(delete p)");
        stmt("return;", "(return)");
        stmt("return N * K;", "(return (* N K))");
        stmt("if (a) b(); else { c(); }", "(if a (call b) (block (call c)))");
        stmt("if (a) if (b) x(); else y();", "(if a (if b (call x) (call y)))");
        stmt("while (i < n) i += 1;", "(while (< i n) (+= i 1))");
        stmt("for (int i = 0; i < N; i += 1) { sum += i; }",
                "(for (var int i 0) (< i N) (+= i 1) (block (+= sum i)))");
        stmt("for (;;) break;", "(for _ _ _ (break))");
        stmt("for (Foo x = y; ; ) continue;", "(for (var Foo x y) _ _ (continue))");
        stmt("outer: for (;;) { while (x) { break outer; } continue outer; }",
                "(label outer (for _ _ _ (block (while x (block (break outer))) (continue outer))))");
        stmt("each: for (int v : xs) break;", "(label each (foreach int v xs (break)))");
        stmt("y = switch (x) { case 1, 2 -> 10; case 3 -> throw e; default -> { int z = 1; yield z; } };",
                "(= y (switch-expr x (case 1 2 -> (yield 10)) (case 3 -> (throw e)) (default -> (var int z 1) (yield z))))");
        stmt("y = switch (x) { case 1: yield 2; default: yield 3; };",
                "(= y (switch-expr x (case 1 -> (yield 2)) (default -> (yield 3))))");
        stmt("assert ok;", "(assert ok)");
        stmt("assert n > 0 : msg;", "(assert (> n 0) msg)");
        stmt("yield = 3;", "(= yield 3)");
        stmt("s = \"\"\"\n        <p>\n          \"hi\"\n        </p>\n        \"\"\";", "(= s \"<p>\\n  \\\"hi\\\"\\n</p>\\n\")");
        stmt("s = \"\"\"\n    a   \n    b\\s\n    c \\\n    d\"\"\";", "(= s \"a\\nb \\nc d\")");
    }

    private static void types() {
        stmt("array<uint<<16>>><<N>> data;", "(var array<uint<<16>>><<N>> data)");
        stmt("array<T><<N, K>> grid;", "(var array<T><<N, K>> grid)");
        stmt("array<T> items;", "(var array<T> items)");
        stmt("array<uint<<64>>><<(BITS + 63) / 64>> words;", "(var array<uint<<64>>><<(/ (+ BITS 63) 64)>> words)");
        stmt("Foo<<2 + 2>> f;", "(var Foo<<(+ 2 2)>> f)");
        stmt("Foo<<-1>> f;", "(var Foo<<(- 1)>> f)");
        stmt("Foo<<N > 3>> f;", "(var Foo<<(> N 3)>> f)");
        stmt("Foo<<1.5, \"hi\">> f;", "(var Foo<<1.5, \"hi\">> f)");
    }

    // ================================================================ members and headers

    private static void members() {
        member("int occupied;", "(field private int occupied)");
        member("array<T><<N>> data<<int N>>;", "(field private array<T><<N>> data<<int N>>)");
        member("array<uint<<16>>><<16>> data<<>>;", "(field private array<uint<<16>>><<16>> data<<>>)");
        member("addr<uint<<16>>> data<<>>;", "(field private addr<uint<<16>>> data<<>>)");
        member("public const int MAX = 10;", "(field public const int MAX = 10)");
        member("private<A, B> int shared;", "(field private<A, B> int shared)");
        member("int get<<int N>>() { return N; }",
                "(method private int get<<int N>> () (block (return N)))");
        member("public T get<<int N, int K>>(int x, int y) { return data[x][y]; }",
                "(method public T get<<int N, int K>> (int x, int y) (block (return (index (index data x) y))))");
        member("public string<<N + K>> add<<int N>>(string<<int K>> other) { }",
                "(method public string<<(+ N K)>> add<<int N>> (string<<int K>> other) (block))");
        member("public static string toString(int x) { }",
                "(method public static string toString (int x) (block))");
        member("@overload(Operator.ADD) public BigUInt<<BITS>> add(BigUInt<<BITS>> o) { }",
                "(method @overload((. Operator ADD)) public BigUInt<<BITS>> add (BigUInt<<BITS>> o) (block))");
        member("@overload(Operator.SHIFT_ASSIGN) public void shiftInPlace(int n) { }",
                "(method @overload((. Operator SHIFT_ASSIGN)) public void shiftInPlace (int n) (block))");
        member("public new<<int N>>(int initial) { occupied = initial; }",
                "(new public<<int N>> (int initial) (block (= occupied initial)))");
        member("public new() { }", "(new public () (block))");
        member("new<<>>() { }", "(new private<<>> () (block))");
        member("delete() { }", "(delete private (block))");
        member("delete<<int N>>() { }", "(delete private<<int N>> (block))");
    }

    private static void headers() {
        header("struct Array<T><<int N>>", "(header struct Array<T><<int N>>)");
        header("struct Foo", "(header struct Foo)");
        header("struct Foo<<>>", "(header struct Foo<<>>)");
        header("struct Pair<A, B>", "(header struct Pair<A, B>)");
        header("unique File", "(header unique File)");
        header("class Dog extends(Animal)", "(header class Dog extends(Animal))");
        header("class Dog<<int size>> extends(Pet<<size>>)",
                "(header class Dog<<int size>> extends(Pet<<size>>))");
        header("class Shape<<int kind>> extends(Base) extends<<0>>(Circle) extends<<1>>(Square)",
                "(header class Shape<<int kind>> extends(Base) extends<<0>>(Circle) extends<<1>>(Square))");
        header("class X<<int n>> extends<<3>>(Object)", "(header class X<<int n>> extends<<3>>(Object))");
    }

    private static void specExamples() {
        String array = """
                struct Array<T><<int N>>
                struct Array<T><<int N, int K>>
                {
                    int occupied;

                    array<T><<N>> data<<int N>>;
                    array<T><<N, K>> data<<int N, int K>>;

                    int getLength<<int N>>() {
                        return N;
                    }

                    int getLength<<int N, int K>>() {
                        return N * K;
                    }

                    float getOccFrac<<int N>>() {
                        return (float)occupied / N;
                    }

                    float getOccFrac<<int N, int K>>() {
                        return (float)occupied / (N * K);
                    }

                    int getOccupied() {
                        return occupied;
                    }

                    T get<<int N>>(int x) {
                        return data[x];
                    }

                    T get<<int N, int K>>(int x, int y) {
                        return data[x][y];
                    }
                }
                """;
        Parser.Result r = Parser.parse(array);
        check(r.ok(), "Array example parses: " + r.errors());
        check(r.ast().headers().size() == 2, "Array example has 2 headers");
        check(r.ast().members().size() == 10, "Array example has 10 members: " + r.ast().members().size());

        String string = """
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
                        return result;
                    }
                }
                """;
        r = Parser.parse(string);
        check(r.ok(), "string example parses: " + r.errors());
        check(r.ast().members().size() == 7, "string example has 7 members: " + r.ast().members().size());

        String bigint = """
                struct BigUInt<<int BITS>>
                {
                    array<uint<<64>>><<(BITS + 63) / 64>> words;

                    @overload(Operator.ADD)
                    public BigUInt<<BITS>> add(BigUInt<<BITS>> o) { }

                    @overload(Operator.LESS)
                    public bit isLess(BigUInt<<BITS>> o) { return false; }

                    @overload(Operator.SHIFT)
                    public BigUInt<<BITS>> shifted(int n) { }

                    @overload(Operator.SHIFT_ASSIGN)
                    public void shiftInPlace(int n) { }
                }
                """;
        r = Parser.parse(bigint);
        check(r.ok(), "BigUInt example parses: " + r.errors());

        String dog = """
                class Dog extends(Animal)
                class Dog<<int size>> extends(Pet<<size>>)
                class Dog<<int size, int weight>>
                {
                    public new() { super(); }
                    delete() { }
                }
                """;
        r = Parser.parse(dog);
        check(r.ok(), "Dog example parses: " + r.errors());
        check(r.ast().headers().size() == 3, "Dog example has 3 headers");
    }

    // ================================================================ errors

    private static void packages() {
        Parser.Result r = Parser.parse("""
                package com.example.game;

                import com.example.util.Vec2;
                import com.example.util.*;

                class Player { }
                """);
        check(r.ok(), "package and imports parse: " + r.errors());
        check("com.example.game".equals(r.pkg()), "package name: " + r.pkg());
        check(r.imports().size() == 2 && r.imports().get(0).name().equals("com.example.util.Vec2") && !r.imports().get(0).all()
                && r.imports().get(1).name().equals("com.example.util") && r.imports().get(1).all(), "imports: " + r.imports());
        r = Parser.parse("import static Math.*;\nimport static com.x.Tools.twice;\nclass C { }");
        check(r.ok() && r.imports().size() == 2 && r.imports().get(0).isStatic() && r.imports().get(0).all()
                && r.imports().get(0).name().equals("Math") && r.imports().get(1).isStatic() && !r.imports().get(1).all()
                && r.imports().get(1).name().equals("com.x.Tools.twice"), "static imports: " + r.imports());
        r = Parser.parse("import static Math;\nclass C { }");
        check(r.errors().size() == 1 && r.errors().get(0).message().startsWith("import static names a type and its member"),
                "a static import needs a member: " + r.errors());
        r = Parser.parse("class Plain { }");
        check(r.ok() && r.pkg() == null && r.imports().isEmpty(), "no package: " + r.pkg());
        r = Parser.parse("import com.x.A;\npackage p;\nclass C { }");
        check(r.errors().size() == 1 && r.errors().get(0).message().equals("the package comes first, before the imports"),
                "package after imports: " + r.errors());
        r = Parser.parse("import Foo;\nclass C { }");
        check(r.errors().size() == 1 && r.errors().get(0).message().startsWith("import names a type in a package"),
                "import of a name without a package: " + r.errors());
    }

    private static void records() {
        Parser.Result r = Parser.parse("""
                record Range(int lo, int hi)
                {
                    new { if (lo > hi) { throw new IllegalArgumentException("backwards"); } }
                    public int size() { return hi - lo; }
                }
                """);
        check(r.ok(), "a record parses: " + r.errors());
        check(r.ast().headers().get(0).kind() == Kind.RECORD, "a record's kind");
        List<String> members = r.ast().members().stream().map(m -> switch (m) {
            case Field f -> "field " + f.name();
            case Constructor k -> "new/" + k.params().size();
            case Method md -> md.name();
            default -> "?";
        }).toList();
        check(members.equals(List.of("field lo", "field hi", "new/0", "__equal", "hash", "__string", "new/2", "size")),
                "a record's members: " + members);
        r = Parser.parse("class Box { new { } }");
        check(r.errors().size() == 1 && r.errors().get(0).message().startsWith("only a record's constructor can leave out"),
                "a compact constructor outside a record: " + r.errors());
        r = Parser.parse("record P(int x) { int y; public int x() { return 1; } }");
        check(r.errors().stream().map(ParseError::message).toList().equals(List.of(
                "a record's fields are its components: put y in P(...), or make it static",
                "a record's component x is read as p.x or p.x(), so a method can't have its name")), "record rules: " + r.errors());
        r = Parser.parse("class C { int record = 1; void f() { record = 2; } }");
        check(r.ok(), "record is still a name: " + r.errors());
    }

    private static void nestedTypes() {
        Parser.Result r = Parser.parse("""
                class Outer
                {
                    public static struct Point { public int x; }
                    abstract class Shape { public abstract int area(); }
                    enum Kind { A, B; int weight; }
                    interface Visitor { void visit(int x); }
                    public int f() { return 1; }
                }
                """);
        check(r.ok() && r.ast().nested().size() == 4 && r.ast().members().size() == 1, "four nested types and a method: " + r.errors());
        check(r.ok() && r.ast().nested().get(0).modifiers().isStatic()
                && r.ast().nested().get(1).decl().headers().get(0).isAbstract()
                && r.ast().nested().get(2).decl().headers().get(0).kind() == Kind.ENUM,
                "nested types keep their modifiers and kinds");
        expr("Outer.Inner.make()", "(call (. (. Outer Inner) make))");
        stmt("Outer.Inner x = y;", "(var Outer.Inner x y)");
        stmt("Outer.Inner<int> x;", "(var Outer.Inner<int> x)");
        fileError("class A { } class B { }",
                "unexpected 'class' after the type's body; each file holds one top-level type (others can be nested inside it)");
    }

    private static void errors() {
        exprError("a << b", "'<<' is not a shift operator; use '<<<' (e.g. x <<< 3)");
        exprError("a >> b", "'>>' is not a shift operator; use '<<<' with a negative amount (e.g. x <<< -3)");
        exprError("&&pp", "'&&' is logical AND; to dereference twice write &(&p)");
        exprError("& &pp", "nested dereference requires parentheses: write &(&p)");
        exprError("a < b < c", "comparisons cannot be chained; use parentheses");
        exprError("a +", "expected an expression but reached the end of the file");
        exprError("(a", "expected ')', found end of file");

        fileError("struct Foo<<int N>><T> { }", "type parameters <...> must come before value parameters <<...>>");
        fileError("struct A { void f(int a, int b,) { } }", "a parameter list can't end with a comma");
        fileError("struct A { } struct B { }", "unexpected 'struct' after the type's body; each file holds one top-level type (others can be nested inside it)");
        fileError("{ }", "expected a type declaration: struct, unique, class, interface or enum");

        // Recovery: one bad member doesn't stop the rest from parsing.
        Parser.Result r = Parser.parse("""
                struct Foo
                {
                    int a = ;
                    int b;
                    void f() { x = ; y = 2; }
                    int c;
                }
                """);
        check(r.errors().size() == 2, "recovery reports each error once: " + r.errors());
        check(r.ast().members().size() == 3, "recovery keeps the good members: " + r.ast().members().size());
        Method f = (Method) r.ast().members().get(1);
        check(f.body().stmts().size() == 1, "recovery keeps the good statement in f");

        // Errors carry positions.
        r = Parser.parse("struct Foo\n{\n    int x = a << b;\n}");
        check(r.errors().size() == 1 && r.errors().get(0).line() == 3 && r.errors().get(0).column() == 15,
                "error position: " + r.errors());
    }

    // ================================================================ helpers

    private static void expr(String src, String expected) {
        List<ParseError> errors = new ArrayList<>();
        Expr e = Parser.parseExpression(src, errors);
        if (expected == null) {
            check(!errors.isEmpty(), src + ": expected an error");
            return;
        }
        String actual = e == null ? "<null>" : AstPrinter.print(e);
        check(errors.isEmpty() && actual.equals(expected),
                src + "\n    expected " + expected + "\n    got      " + actual + (errors.isEmpty() ? "" : "\n    errors   " + errors));
    }

    private static void exprError(String src, String expected) {
        List<ParseError> errors = new ArrayList<>();
        Parser.parseExpression(src, errors);
        List<String> messages = errors.stream().map(ParseError::message).toList();
        check(messages.equals(List.of(expected)), src + "\n    expected error " + expected + "\n    got    " + messages);
    }

    private static void stmt(String src, String expected) {
        List<ParseError> errors = new ArrayList<>();
        Stmt s = Parser.parseStatement(src, errors);
        String actual = s == null ? "<null>" : AstPrinter.print(s);
        check(errors.isEmpty() && actual.equals(expected),
                src + "\n    expected " + expected + "\n    got      " + actual + (errors.isEmpty() ? "" : "\n    errors   " + errors));
    }

    private static void member(String src, String expected) {
        Parser.Result r = Parser.parse("struct Test {\n" + src + "\n}");
        String actual = r.ast().members().size() == 1 ? AstPrinter.print(r.ast().members().get(0)) : "<" + r.ast().members().size() + " members>";
        check(r.ok() && actual.equals(expected),
                src + "\n    expected " + expected + "\n    got      " + actual + (r.ok() ? "" : "\n    errors   " + r.errors()));
    }

    private static void header(String src, String expected) {
        Parser.Result r = Parser.parse(src + " { }");
        String actual = r.ast().headers().isEmpty() ? "<none>" : AstPrinter.print(r.ast().headers().get(0));
        check(r.ok() && actual.equals(expected),
                src + "\n    expected " + expected + "\n    got      " + actual + (r.ok() ? "" : "\n    errors   " + r.errors()));
    }

    private static void fileError(String src, String expected) {
        List<String> messages = Parser.parse(src).errors().stream().map(ParseError::message).toList();
        check(messages.contains(expected), src + "\n    expected error " + expected + "\n    got    " + messages);
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
