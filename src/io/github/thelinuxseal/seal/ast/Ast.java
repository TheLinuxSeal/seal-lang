package io.github.thelinuxseal.seal.ast;

import java.math.BigInteger;
import java.util.List;

/** Syntax tree node types. Every node records the position it started at. */
public final class Ast {

    private Ast() {
    }

    public record Pos(int line, int column) {
        @Override
        public String toString() {
            return line + ":" + column;
        }
    }

    // ================================================================ files

    /**
     * `import com.example.Vec2;`, or `import com.example.*;` ({@code all}) for every type of a
     * package (§2.4). With {@code isStatic}, `import static Math.max;` brings the static members
     * named max of Math, and `import static Math.*;` all of them; then {@code name} is the type
     * and the member, or the type alone.
     */
    public record Import(Pos pos, String name, boolean all, boolean isStatic) {
    }

    /**
     * One type: a source file's top-level type, or a type nested in another (§2), possibly with
     * several headers.
     *
     * @param nested the types declared inside it (§2.3)
     */
    public record ClassDecl(Pos pos, List<ClassHeader> headers, List<Member> members, List<NestedType> nested) {
        public ClassDecl(Pos pos, List<ClassHeader> headers, List<Member> members) {
            this(pos, headers, members, List.of());
        }
    }

    /** A type declared in another's body: `public static class Entry { ... }` (§2.3). */
    public record NestedType(Pos pos, Modifiers modifiers, ClassDecl decl) {
    }

    /**
     * The three kinds of type declaration:
     * <ul>
     *   <li>{@code struct}: an inline value that is copied on assignment</li>
     *   <li>{@code unique}: an inline value that cannot be copied</li>
     *   <li>{@code class}: a shared reference object that can inherit</li>
     * </ul>
     */
    public enum Kind {
        STRUCT("struct"), UNIQUE("unique"), CLASS("class"), INTERFACE("interface"),
        /** An enum: written as a list of constants, and checked as the struct it stands for (§38.1). */
        ENUM("enum"),
        /** A record: written as its components, and checked as the struct it stands for (§2.5). */
        RECORD("record");

        public final String keyword;

        Kind(String keyword) {
            this.keyword = keyword;
        }
    }

    /**
     * One header line, e.g. `class Dog<<int size>> extends(Pet<<size>>)`.
     *
     * @param valueParams null when the header has no `<<...>>` at all; an empty
     *                    list for `<<>>`
     * @param interfaces  a class's `implements(...)`, or the interfaces an interface `extends(...)`
     */
    public record ClassHeader(Pos pos, Kind kind, boolean isAbstract, String name, List<String> typeParams,
                              ValueList valueParams, List<ExtendsClause> extendsClauses, List<TypeRef> interfaces) {
        /** True for a `class` or `interface`: a shared reference type. */
        public boolean dynamic() {
            return kind == Kind.CLASS || kind == Kind.INTERFACE;
        }

    }

    /** `extends(Parent)` or `extends<<guard>>(Parent)`; guard is null when unguarded. */
    public record ExtendsClause(Pos pos, ValueList guard, TypeRef parent) {
    }

    // ================================================================ value lists

    /**
     * The contents of a `<<...>>`. Used for class value parameters, member
     * guards, extends guards, and value arguments in types. An empty item
     * list is `<<>>`.
     */
    public record ValueList(Pos pos, List<ValueItem> items) {
    }

    public sealed interface ValueItem permits Binder, ValueExpr {
        Pos pos();
    }

    /** A declaration of a compile-time variable, e.g. `int N`. */
    public record Binder(Pos pos, TypeRef type, String name) implements ValueItem {
    }

    /** A compile-time value, e.g. `64` or `N + K`. */
    public record ValueExpr(Pos pos, Expr expr) implements ValueItem {
    }

    // ================================================================ types

    /**
     * A type such as `int`, `addr<int>`, `Array<int><<64>>` or `uint<<16>>[N]`.
     *
     * @param name       a primitive keyword (`int`, `addr`, ...) or a class name
     * @param valueArgs  null when absent; an empty list for `<<>>`
     * @param arrayDims  one entry per `[...]`, outermost first; a null size is `[]`
     */
    public record TypeRef(Pos pos, String name, List<TypeRef> typeArgs, ValueList valueArgs,
                          List<Expr> arrayDims) {
    }

    // ================================================================ members

    public enum Access { PUBLIC, PRIVATE }

    /**
     * @param friends classes listed in `private<A, B>`; empty otherwise
     * @param isDefault a `default` method of an interface, with a body the classes implementing it
     *     get; or a `default` field of an interface or abstract class, with a value they get (§30.4)
     * @param isPure `pure` (§17.2): a static method that depends only on its arguments, so it can also run
     *     while compiling, in `<<...>>`
     */
    public record Modifiers(List<Decorator> decorators, Access access, List<TypeRef> friends,
                            boolean isStatic, boolean isConst, boolean isAbstract, boolean isDefault, boolean isPure) {
        public Modifiers(List<Decorator> decorators, Access access, List<TypeRef> friends,
                         boolean isStatic, boolean isConst, boolean isAbstract) {
            this(decorators, access, friends, isStatic, isConst, isAbstract, false, false);
        }
    }

    /** `@name` or `@name(args)`. */
    public record Decorator(Pos pos, String name, List<Expr> args) {
    }

    public sealed interface Member permits Field, Method, Constructor, Destructor {
        Pos pos();

        Modifiers modifiers();
    }

    /** @param guard null when unguarded; an empty list for `<<>>` */
    public record Field(Pos pos, Modifiers modifiers, TypeRef type, String name, ValueList guard,
                        Expr init) implements Member {
    }

    /**
     * @param typeParams a generic method's `<T, R>`, empty if none (§12.2)
     * @param body       null for an abstract or interface method, written with `;` instead of a body
     */
    public record Method(Pos pos, Modifiers modifiers, List<String> typeParams, TypeRef returnType, String name,
                         ValueList guard, List<Param> params, Block body) implements Member {
    }

    public record Constructor(Pos pos, Modifiers modifiers, ValueList guard, List<Param> params,
                              Block body) implements Member {
    }

    public record Destructor(Pos pos, Modifiers modifiers, ValueList guard, Block body) implements Member {
    }

    public record Param(Pos pos, TypeRef type, String name) {
    }

    // ================================================================ statements

    public sealed interface Stmt permits Block, VarDecl, ExprStmt, If, While, For, ForEach, Switch, Return, Break,
            Continue, Labeled, DeleteStmt, Throw, Try, Yield, Assert {
        Pos pos();
    }

    public record Block(Pos pos, List<Stmt> stmts) implements Stmt {
    }

    /** @param type null only in code the checker writes, where the type is the initializer's */
    public record VarDecl(Pos pos, TypeRef type, String name, Expr init) implements Stmt {
    }

    public record ExprStmt(Pos pos, Expr expr) implements Stmt {
    }

    public record If(Pos pos, Expr cond, Stmt then, Stmt otherwise) implements Stmt {
    }

    public record While(Pos pos, Expr cond, Stmt body) implements Stmt {
    }

    /** Any of init, cond, update may be null. */
    public record For(Pos pos, Stmt init, Expr cond, Expr update, Stmt body) implements Stmt {
    }

    /** `for (T name : iterable) body` (§41.3). */
    public record ForEach(Pos pos, TypeRef type, String name, Expr iterable, Stmt body) implements Stmt {
    }

    /** `switch (subject) { case ... }`, all with `:` (falling through) or all with `->` (§41.3). */
    public record Switch(Pos pos, Expr subject, List<Case> cases) implements Stmt {
    }

    /** One `case a, b:` or `case a, b ->` (labels empty for `default`) and its statements. */
    public record Case(Pos pos, List<Expr> labels, boolean isDefault, boolean arrow, List<Stmt> body) {
    }

    /** `throw e;` (§41.4). */
    public record Throw(Pos pos, Expr value) implements Stmt {
    }

    /**
     * `try { } catch (T e) { } ... finally { }`; finallyBlock is null when there is none. With resources,
     * `try (R r = ...; ...) { }`, each is closed at the end of the body (§41.4); resources is empty otherwise.
     */
    public record Try(Pos pos, List<VarDecl> resources, Block body, List<Catch> catches, Block finallyBlock) implements Stmt {
    }

    public record Catch(Pos pos, TypeRef type, String name, Block body) {
    }

    public record Return(Pos pos, Expr value) implements Stmt {
    }

    /**
     * `switch (x) { case 1 -> a; case 2 -> { ...; yield b; } default -> c; }` as an expression
     * (§41.3): each case's body ends in {@link Yield} (an expression case is one) or a throw.
     */
    public record SwitchExpr(Pos pos, Expr subject, List<Case> cases) implements Expr {
    }

    /** `yield value;`: the value of the switch expression a case block belongs to. */
    public record Yield(Pos pos, Expr value) implements Stmt {
    }

    /** `assert cond;` or `assert cond : message;`, checked with -Wassert (§41.4); {@code text} is cond as written. */
    public record Assert(Pos pos, Expr cond, Expr message, String text) implements Stmt {
    }

    /** `outer: for (...) { ... }`: a loop that `break outer;` and `continue outer;` can name from inside nested loops. */
    public record Labeled(Pos pos, String label, Stmt body) implements Stmt {
    }

    /** `break;`, or `break outer;` (label set) to leave the loop labeled outer. */
    public record Break(Pos pos, String label) implements Stmt {
    }

    /** `continue;`, or `continue outer;` (label set) to go on with the next round of the loop labeled outer. */
    public record Continue(Pos pos, String label) implements Stmt {
    }

    /** `delete p;` */
    public record DeleteStmt(Pos pos, Expr target) implements Stmt {
    }

    // ================================================================ expressions

    /** The direct subexpressions of {@code e}, left to right. */
    public static List<Expr> children(Expr e) {
        return switch (e) {
            case IntLit x -> List.of();
            case FloatLit x -> List.of();
            case StringLit x -> List.of();
            case BoolLit x -> List.of();
            case NullLit x -> List.of();
            case Name x -> List.of();
            case SuperRef x -> List.of();
            case This x -> List.of();
            case SizeOf x -> List.of();
            case SwitchExpr x -> List.of(x.subject()); // its cases are statements, each with its own
            case AlignOf x -> List.of();
            case ArrayLit a -> a.elements();
            case Select s -> List.of(s.target());
            case Call c -> {
                List<Expr> out = new java.util.ArrayList<>();
                out.add(c.callee());
                out.addAll(c.args());
                yield out;
            }
            case Index i -> List.of(i.target(), i.index());
            case Slice s -> List.of(s.target(), s.from(), s.to());
            case Unary u -> List.of(u.operand());
            case IncDec i -> List.of(i.target());
            case Binary b -> List.of(b.left(), b.right());
            case Conditional c -> List.of(c.cond(), c.then(), c.otherwise());
            case Assign a -> List.of(a.target(), a.value());
            case Cast c -> List.of(c.operand());
            case InstanceOf i -> List.of(i.operand());
            case Lambda l -> List.of(); // its body is a function of its own
            case New n -> n.args();
            case NewArray n -> List.of(n.count());
        };
    }

    public sealed interface Expr permits IntLit, FloatLit, StringLit, ArrayLit, BoolLit, NullLit, Name, SuperRef, This, Select,
            Call, Index, Slice, Unary, IncDec, Binary, Conditional, Assign, Cast, InstanceOf, Lambda, New, NewArray, SizeOf,
            AlignOf, SwitchExpr {
        Pos pos();
    }

    public record IntLit(Pos pos, String text, BigInteger value) implements Expr {
    }

    public record FloatLit(Pos pos, String text, double value) implements Expr {
    }

    /** @param value the string with escapes decoded */
    public record StringLit(Pos pos, String value) implements Expr {
    }

    /** `[a, b, c]`: an array whose type comes from context, like a number's (§21.2). */
    public record ArrayLit(Pos pos, List<Expr> elements) implements Expr {
    }

    public record BoolLit(Pos pos, boolean value) implements Expr {
    }

    public record NullLit(Pos pos) implements Expr {
    }

    public record Name(Pos pos, String name) implements Expr {
    }

    /** `super`, as in `super(args)` or `super.method()`. */
    public record SuperRef(Pos pos) implements Expr {
    }

    /** `this`: the object a method or constructor runs on (§12.1). */
    public record This(Pos pos) implements Expr {
    }

    /** Member access `target.name`. */
    public record Select(Pos pos, Expr target, String name) implements Expr {
    }

    /** @param typeArgs explicit type arguments of a generic method, `Main.<int>f(x)`; usually empty */
    public record Call(Pos pos, Expr callee, List<Expr> args, List<TypeRef> typeArgs) implements Expr {
        public Call(Pos pos, Expr callee, List<Expr> args) {
            this(pos, callee, args, List.of());
        }
    }

    /** `target[index]`: array element or integer bit (§40). */
    public record Index(Pos pos, Expr target, Expr index) implements Expr {
    }

    /** `target[from..to]` (§40). */
    public record Slice(Pos pos, Expr target, Expr from, Expr to) implements Expr {
    }

    public enum UnaryOp {
        NEG("-"), NOT("!"), BIT_NOT("~"),
        /** `&p`: dereference (§19). */
        DEREF("&"),
        /** `*x`: address-of (§19). */
        ADDRESS_OF("*");

        public final String symbol;

        UnaryOp(String symbol) {
            this.symbol = symbol;
        }
    }

    public record Unary(Pos pos, UnaryOp op, Expr operand) implements Expr {
    }

    /**
     * `++x`, `x++`, `--x` or `x--` (§41.5).
     *
     * @param op ADD for `++`, SUB for `--`
     * @param prefix true for `++x`, whose value is the new one; `x++` gives the old one
     */
    public record IncDec(Pos pos, BinaryOp op, boolean prefix, Expr target) implements Expr {
        public String symbol() {
            return op == BinaryOp.ADD ? "++" : "--";
        }
    }

    public enum BinaryOp {
        MUL("*"), DIV("/"), MOD("%"),
        ADD("+"), SUB("-"),
        SHIFT("<<<"),
        BIT_AND("&"), BIT_XOR("^"), BIT_OR("|"),
        EQ("=="), NE("!="), LT("<"), GT(">"), LE("<="), GE(">="),
        AND("&&"), OR("||");

        public final String symbol;

        BinaryOp(String symbol) {
            this.symbol = symbol;
        }
    }

    public record Binary(Pos pos, BinaryOp op, Expr left, Expr right) implements Expr {
    }

    /**
     * `target = value`, or a compound form like `target += value`.
     *
     * @param op null for plain `=`; otherwise the operator, with SHIFT meaning `<<=`
     */
    public record Assign(Pos pos, BinaryOp op, Expr target, Expr value) implements Expr {
    }

    public record Cast(Pos pos, TypeRef type, Expr operand) implements Expr {
    }

    /** `cond ? then : otherwise` (§44.1). */
    public record Conditional(Pos pos, Expr cond, Expr then, Expr otherwise) implements Expr {
    }

    /**
     * `x instanceof Shape`, or `x instanceof Shape s`, which also names the object as a
     * Shape where the test is known to have passed (§35.1).
     *
     * @param binding the name, or null
     */
    public record InstanceOf(Pos pos, Expr operand, TypeRef type, String binding) implements Expr {
    }

    /**
     * `x -> x + 1`, `(a, b) -> { ... }`, `(int a) -> a`: a function value of a functional
     * interface type (§30.3). Exactly one of {@code expr} and {@code block} is the body.
     * A parameter's type is null when it comes from the interface.
     */
    public record Lambda(Pos pos, List<Param> params, Expr expr, Block block) implements Expr {
    }

    /** `new Array(args)`. The shape comes from the declared type (§7). */
    public record New(Pos pos, TypeRef type, List<Expr> args) implements Expr {
    }

    /** `new<int>(n)`: runtime-sized allocation returning `addr<int>` (§24). */
    public record NewArray(Pos pos, TypeRef elementType, Expr count) implements Expr {
    }

    public record SizeOf(Pos pos, TypeRef type) implements Expr {
    }

    public record AlignOf(Pos pos, TypeRef type) implements Expr {
    }
}
