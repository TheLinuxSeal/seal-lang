package io.github.thelinuxseal.seal.jvm;

import static java.lang.classfile.ClassFile.ACC_FINAL;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_char;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_float;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.CVal;
import io.github.thelinuxseal.seal.check.CVal.BitV;
import io.github.thelinuxseal.seal.check.CVal.FloatV;
import io.github.thelinuxseal.seal.check.CVal.IntV;
import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Checker.CallInfo;
import io.github.thelinuxseal.seal.check.Checker.CallKind;
import io.github.thelinuxseal.seal.check.Checker.TypedBody;
import io.github.thelinuxseal.seal.check.ClassInfo;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.Type;
import io.github.thelinuxseal.seal.check.Type.*;

/**
 * Generates JVM class files from a checked program (docs/JVM.md). Each Seal type whose code the
 * program reaches becomes a JVM class of the same name; the standard library's go in {@link
 * #LIBRARY_PACKAGE}. As CodeGen does for the VM, it generates only the methods reachable from main,
 * one per instantiation.
 *
 * <p>So far (milestone M1, docs/JVM.md §13): static methods; integers of every width, held in a JVM
 * int up to 32 bits and a long up to 64 and normalized after arithmetic as on the VM (§3.1);
 * {@code char}, {@code bit}, {@code float} and {@code double}; control flow, calls and recursion;
 * and printing to the standard streams. Anything else is reported as not available on the JVM yet.
 */
public final class JvmGen {

    /**
     * The generated classes by binary name ("com.example.Main"), the main class's binary name, and
     * the errors (when there are any, there are no classes).
     */
    public record Result(Map<String, byte[]> classes, String mainClass, List<Diagnostic> errors) {
    }

    /** The package of the standard library's classes on the JVM (docs/JVM.md §8.3). */
    public static final String LIBRARY_PACKAGE = "io.github.thelinuxseal.seal.lang";

    /** The runtime's classes, which compiled programs use and `seal build` copies beside them (§9). */
    public static final List<String> RUNTIME_CLASSES = List.of(
            "io.github.thelinuxseal.seal.runtime.Natives",
            "io.github.thelinuxseal.seal.runtime.Ints",
            "io.github.thelinuxseal.seal.runtime.Exit",
            "io.github.thelinuxseal.seal.runtime.Trap");

    /** The method every main class gets that runs the program and returns its exit code. */
    public static final String RUN_METHOD = "$run";

    private static final ClassDesc NATIVES = ClassDesc.of("io.github.thelinuxseal.seal.runtime.Natives");
    private static final ClassDesc INTS = ClassDesc.of("io.github.thelinuxseal.seal.runtime.Ints");
    private static final ClassDesc CD_Integer = ClassDesc.of("java.lang.Integer");
    private static final ClassDesc CD_Long = ClassDesc.of("java.lang.Long");
    private static final ClassDesc CD_MethodHandle = ClassDesc.of("java.lang.invoke.MethodHandle");

    /** Generates the classes of a program whose entry point is main() in {@code mainClass}. */
    public static Result generate(Checker checker, String mainClass) {
        JvmGen g = new JvmGen(checker);
        return g.run(mainClass);
    }

    // ================================================================ state

    /** One generated method: a static method of a class, for one set of binder values. */
    private record FnKey(ClassInfo cls, MemberInfo member, Map<String, CVal> binders) {
    }

    /** Where a method is on the JVM: its class, name and descriptor. */
    private record MethodRef(ClassDesc owner, String name, MethodTypeDesc type) {
    }

    /** Thrown for code the JVM backend can't generate yet; reported as an error at its position. */
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Pos pos;

        Unsupported(Pos pos, String message) {
            super(message, null, false, false);
            this.pos = pos;
        }
    }

    private final Checker ck;
    private final List<Diagnostic> errors = new ArrayList<>();
    private final Map<FnKey, MethodRef> methods = new HashMap<>();
    private final Deque<FnKey> work = new ArrayDeque<>();
    /** The methods of each class, in the order they were reached. */
    private final Map<ClassInfo, List<FnKey>> byClass = new LinkedHashMap<>();
    /** The names and descriptors each class's methods have taken: two Seal methods can map to one. */
    private final Map<ClassInfo, Set<String>> taken = new HashMap<>();

    private JvmGen(Checker ck) {
        this.ck = ck;
    }

    // ================================================================ driver

    private Result run(String mainClass) {
        List<ClassInfo> named = ck.mainCandidates(mainClass);
        if (named.size() > 1) {
            return failed(new Diagnostic("<program>", 1, 1, "several classes are named " + mainClass + ": "
                    + named.stream().map(ClassInfo::qualifiedName).sorted().collect(Collectors.joining(", "))
                    + "; choose one with --main, as in --main " + named.get(0).qualifiedName()));
        }
        ClassInfo main = named.isEmpty() ? null : named.get(0);
        Checker.EntryPoint ep = main == null ? null : ck.entryPoint(main);
        if (ep == null) {
            return failed(new Diagnostic(main == null ? "<program>" : main.file, 1, 1,
                    "no entry point: expected `void main()` or `int main()` in class " + mainClass));
        }
        Pos at = ep.method().member.pos();
        Type ret = ck.returnTypeOf(ep.owner(), ep.method(), Map.of());
        if (!(ret instanceof VoidT) && !(ret instanceof IntT)) {
            return failed(new Diagnostic(ep.declaring().file, at.line(), at.column(),
                    "main must return void or an integer, not " + ret));
        }
        if (!ep.isStatic()) {
            return failed(new Diagnostic(ep.declaring().file, at.line(), at.column(),
                    "main() is not static; on the JVM, an instance main() comes with classes (docs/JVM.md §13, M2)"));
        }
        FnKey entry = new FnKey(ep.declaring(), ep.method(), Map.of());
        MethodRef entryRef = ref(entry, at);

        // First every method main reaches, each generated once to find what it calls and whether it can
        // be; then each class with all of its methods.
        while (!work.isEmpty()) {
            FnKey k = work.pop();
            try {
                ClassFile.of().build(ClassDesc.of("Scratch"), cb -> method(cb, k));
            } catch (Unsupported u) {
                if (u.getMessage() != null) {
                    errors.add(new Diagnostic(k.cls().file, u.pos.line(), u.pos.column(), u.getMessage()));
                }
            }
        }
        if (!errors.isEmpty()) {
            return new Result(Map.of(), null, errors);
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        ClassInfo mainInfo = ep.declaring();
        byClass.putIfAbsent(mainInfo, new ArrayList<>());
        for (Map.Entry<ClassInfo, List<FnKey>> e : byClass.entrySet()) {
            ClassInfo c = e.getKey();
            classes.put(binaryName(c), ClassFile.of().build(classDesc(c), cb -> {
                cb.withVersion(ClassFile.JAVA_25_VERSION, 0);
                cb.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
                cb.with(SourceFileAttribute.of(Path.of(c.file).getFileName().toString()));
                for (FnKey k : e.getValue()) {
                    method(cb, k);
                }
                if (c == mainInfo) {
                    entryPoints(cb, entryRef, ret);
                }
            }));
        }
        return new Result(classes, binaryName(mainInfo), List.of());
    }

    private Result failed(Diagnostic d) {
        return new Result(Map.of(), null, List.of(d));
    }

    /**
     * {@code static int $run()}, which calls main and gives the exit code (0 for a void main), and
     * {@code public static void main(String[])}, so that {@code java Main} runs the program (§10).
     */
    private void entryPoints(ClassBuilder cb, MethodRef main, Type ret) {
        cb.withMethodBody(RUN_METHOD, MethodTypeDesc.of(CD_int), ACC_PUBLIC | ACC_STATIC, c -> {
            c.invokestatic(main.owner(), main.name(), main.type());
            if (ret instanceof VoidT) {
                c.iconst_0();
            } else if (kind(ret) == TypeKind.LONG) {
                c.l2i(); // as the VM's runner takes the low 32 bits of main's result
            }
            c.ireturn();
        });
        MethodTypeDesc mainType = MethodTypeDesc.of(CD_void, CD_String.arrayType());
        cb.withMethodBody("main", mainType, ACC_PUBLIC | ACC_STATIC, c -> {
            c.loadConstant(MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, main.owner(), RUN_METHOD,
                    MethodTypeDesc.of(CD_int)));
            c.invokestatic(NATIVES, "start", MethodTypeDesc.of(CD_void, CD_MethodHandle));
            c.return_();
        });
    }

    // ================================================================ naming

    /** A class's JVM binary name: as in Seal, with nested types after a $; the library's in its own package. */
    static String binaryName(ClassInfo c) {
        String name = c.name.replace('.', '$');
        if (c.builtin) {
            return LIBRARY_PACKAGE + "." + name;
        }
        return c.pkg.isEmpty() ? name : c.pkg + "." + name;
    }

    private static ClassDesc classDesc(ClassInfo c) {
        return ClassDesc.of(binaryName(c));
    }

    /**
     * The JVM method a call reaches: a native's in the runtime, or a generated one, given a name once,
     * when first reached. A method with value binders gets one per set of values, named after them
     * (abs$8, docs/JVM.md §5.2); two methods whose JVM descriptors coincide (f(int<<8>>) and f(int),
     * both (I)) are told apart by a number.
     */
    private MethodRef ref(FnKey k, Pos at) {
        MethodRef known = methods.get(k);
        if (known != null) {
            return known;
        }
        Method md = (Method) k.member().member;
        List<Type> params = ck.paramTypes(null, k.member(), k.binders());
        Type ret = ck.returnTypeOf(null, k.member(), k.binders());
        String nat = Checker.nativeName(md);
        MethodTypeDesc type;
        try {
            type = MethodTypeDesc.of(desc(ret), params.stream().map(JvmGen::desc).toArray(ClassDesc[]::new));
        } catch (Unsupported u) {
            throw new Unsupported(at, "calling " + (nat != null ? "the native " + nat : md.name() + "(...)") + ": "
                    + u.getMessage());
        }
        if (nat != null) {
            MethodRef r = new MethodRef(NATIVES, nat, type);
            methods.put(k, r);
            return r;
        }
        String name = md.name();
        if (!k.binders().isEmpty()) {
            name += k.binders().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> "$" + binderText(e.getValue())).collect(Collectors.joining());
        }
        Set<String> used = taken.computeIfAbsent(k.cls(), c -> new java.util.HashSet<>());
        String unique = name;
        for (int n = 2; !used.add(unique + type.descriptorString()); n++) {
            unique = name + "$" + n;
        }
        MethodRef r = new MethodRef(classDesc(k.cls()), unique, type);
        methods.put(k, r);
        byClass.computeIfAbsent(k.cls(), c -> new ArrayList<>()).add(k);
        work.add(k);
        return r;
    }

    /** A binder's value in a method name: 8, n1 for -1 (docs/JVM.md §5.2). */
    private static String binderText(CVal v) {
        if (v instanceof IntV i) {
            return i.value().signum() < 0 ? "n" + i.value().negate() : i.value().toString();
        }
        if (v instanceof BitV b) {
            return String.valueOf(b.value());
        }
        String s = v.toString();
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            out.append(Character.isLetterOrDigit(ch) ? String.valueOf(ch) : "_" + Integer.toHexString(ch));
        }
        return out.toString();
    }

    // ================================================================ types

    /** How a value of this type is held on the JVM (docs/JVM.md §3). */
    static TypeKind kind(Type t) {
        return switch (t) {
            case BitT b -> TypeKind.BOOLEAN;
            case IntT it when it.isChar() -> TypeKind.CHAR;
            case IntT it -> width(it) <= 32 ? TypeKind.INT : TypeKind.LONG;
            case FloatT f -> TypeKind.FLOAT;
            case DoubleT d -> TypeKind.DOUBLE;
            case VoidT v -> TypeKind.VOID;
            default -> throw new Unsupported(null, "values of type " + t + " are not available on the JVM yet");
        };
    }

    static ClassDesc desc(Type t) {
        return switch (kind(t)) {
            case BOOLEAN -> CD_boolean;
            case CHAR -> CD_char;
            case INT -> CD_int;
            case LONG -> CD_long;
            case FLOAT -> CD_float;
            case DOUBLE -> CD_double;
            case VOID -> CD_void;
            default -> throw new IllegalStateException();
        };
    }

    static int width(Type t) {
        return switch (t) {
            case IntT it -> ((IntV) it.width()).value().intValueExact();
            default -> throw new IllegalStateException("no width: " + t);
        };
    }

    static boolean isNumber(Type t) {
        return t instanceof IntT || t instanceof FloatT || t instanceof DoubleT;
    }

    /** An unsigned integer that fills its JVM type, uint<<32>> or uint<<64>>: its comparisons and division need care. */
    static boolean fullUnsigned(Type t) {
        return t instanceof IntT it && !it.signed() && !it.isChar() && (width(it) == 32 || width(it) == 64);
    }

    /** The concrete type a constant's value is generated in where nothing asks for one. */
    static Type concrete(Type t) {
        if (t instanceof IntConstT) {
            return new IntT(CVal.of(64), true);
        }
        if (t instanceof FloatConstT) {
            return Type.DOUBLE;
        }
        return t;
    }

    // ================================================================ methods

    private void method(ClassBuilder cb, FnKey k) {
        MethodRef r = methods.get(k);
        Method md = (Method) k.member().member;
        TypedBody tb = ck.typeBody(k.cls(), null, k.member(), k.binders());
        if (!tb.errors.isEmpty()) {
            for (Diagnostic d : tb.errors) {
                errors.add(d);
            }
            throw new Unsupported(md.pos(), null); // reported above
        }
        cb.withMethodBody(r.name(), r.type(), ACC_PUBLIC | ACC_STATIC, c -> new Body(c, tb, md).generate());
    }

    /** The code of one method. */
    private final class Body {
        final CodeBuilder c;
        final TypedBody tb;
        final Method md;
        final Type returnType;
        /** Local variables by name, innermost scope first. */
        final Deque<Map<String, Local>> scopes = new ArrayDeque<>();
        final Deque<Loop> loops = new ArrayDeque<>();
        /** The label of the loop being generated next (`outer: for ...`), if any. */
        String pendingLabel;
        int line = -1;
        Pos pos;

        /** A local variable: its type and JVM slot. */
        record Local(Type type, int slot) {
        }

        /** Where break and continue go; continueTo is null for a switch. */
        record Loop(Label breakTo, Label continueTo, String name) {
        }

        Body(CodeBuilder c, TypedBody tb, Method md) {
            this.c = c;
            this.tb = tb;
            this.md = md;
            this.returnType = tb.returnType;
            this.pos = md.pos();
        }

        void generate() {
            try {
                body();
            } catch (Unsupported u) {
                // From where a type was looked at: report it at the code being generated.
                throw u.pos == null && u.getMessage() != null ? new Unsupported(pos, u.getMessage()) : u;
            }
        }

        void body() {
            Map<String, Local> params = new HashMap<>();
            for (int i = 0; i < md.params().size(); i++) {
                Type t = tb.params.get(i);
                kind(t);
                params.put(md.params().get(i).name(), new Local(t, c.parameterSlot(i)));
            }
            scopes.push(params);
            block(md.body());
            if (returnType instanceof VoidT) {
                c.return_();
            } else {
                // The checker makes sure every path returns; this end can't be reached.
                c.new_(ClassDesc.of("java.lang.IllegalStateException"));
                c.dup();
                c.loadConstant("reached the end of " + md.name() + " without returning");
                c.invokespecial(ClassDesc.of("java.lang.IllegalStateException"), "<init>",
                        MethodTypeDesc.of(CD_void, CD_String));
                c.athrow();
            }
        }

        Type type(Expr e) {
            Type t = tb.types.get(e);
            if (t == null) {
                throw new Unsupported(e.pos(), "no type for an expression");
            }
            return t;
        }

        Unsupported unsupported(Pos at, String what) {
            return new Unsupported(at == null ? pos : at, what + " is not available on the JVM yet");
        }

        void at(Pos p) {
            pos = p;
            if (p != null && p.line() != line) {
                line = p.line();
                c.lineNumber(line);
            }
        }

        // ------------------------------------------------------------ statements

        void block(Block b) {
            scopes.push(new HashMap<>());
            for (Stmt s : b.stmts()) {
                stmt(s);
            }
            scopes.pop();
        }

        /** A statement that forms its own scope: the body of an if, while or for. */
        void scoped(Stmt s) {
            scopes.push(new HashMap<>());
            stmt(s);
            scopes.pop();
        }

        void stmt(Stmt s) {
            if (tb.statementSteps.containsKey(s)) {
                stmt(tb.statementSteps.get(s));
                return;
            }
            at(s.pos());
            switch (s) {
                case Block b -> block(b);
                case VarDecl v -> {
                    Type t = tb.locals.get(v);
                    TypeKind k = kind(t);
                    int slot = c.allocateLocal(k);
                    if (v.init() != null) {
                        value(v.init(), t);
                    } else {
                        zero(t);
                    }
                    c.storeLocal(k.asLoadable(), slot);
                    scopes.peek().put(v.name(), new Local(t, slot));
                }
                case ExprStmt e -> effect(e.expr());
                case ForEach fe -> stmt(tb.desugared.get(fe));
                case If i -> {
                    Label otherwise = c.newLabel();
                    Label end = c.newLabel();
                    branchFalse(i.cond(), otherwise);
                    scoped(i.then());
                    if (i.otherwise() != null) {
                        c.goto_(end);
                    }
                    c.labelBinding(otherwise);
                    if (i.otherwise() != null) {
                        scoped(i.otherwise());
                    }
                    c.labelBinding(end);
                }
                case While w -> {
                    String name = takeLabel();
                    Label top = c.newLabel();
                    Label end = c.newLabel();
                    c.labelBinding(top);
                    branchFalse(w.cond(), end);
                    loops.push(new Loop(end, top, name));
                    scoped(w.body());
                    loops.pop();
                    c.goto_(top);
                    c.labelBinding(end);
                }
                case For f -> {
                    String name = takeLabel();
                    scopes.push(new HashMap<>());
                    if (f.init() != null) {
                        stmt(f.init());
                    }
                    Label top = c.newLabel();
                    Label next = c.newLabel();
                    Label end = c.newLabel();
                    c.labelBinding(top);
                    if (f.cond() != null) {
                        branchFalse(f.cond(), end);
                    }
                    loops.push(new Loop(end, next, name));
                    scoped(f.body());
                    loops.pop();
                    c.labelBinding(next);
                    if (f.update() != null) {
                        effect(f.update());
                    }
                    c.goto_(top);
                    c.labelBinding(end);
                    scopes.pop();
                }
                case Labeled l -> {
                    pendingLabel = l.label();
                    stmt(l.body());
                }
                case Break b -> c.goto_((b.label() == null ? loops.peek() : labeled(b.label())).breakTo());
                case Continue co -> {
                    Loop l = co.label() == null
                            ? loops.stream().filter(x -> x.continueTo() != null).findFirst().orElseThrow()
                            : labeled(co.label());
                    c.goto_(l.continueTo());
                }
                case Return r -> {
                    if (r.value() == null) {
                        c.return_();
                    } else {
                        value(r.value(), returnType);
                        c.return_(kind(returnType).asLoadable());
                    }
                }
                case Switch sw -> switchStatement(sw);
                case Assert a -> {
                    Stmt check = tb.assertions.get(a);
                    if (check != null) {
                        stmt(check); // with -Wassert: the if-and-throw it stands for
                    }
                }
                case Try t when !t.resources().isEmpty() -> stmt(tb.desugared.get(t));
                case Try t -> throw unsupported(t.pos(), "try");
                case Throw t -> throw unsupported(t.pos(), "throw");
                case DeleteStmt d -> throw unsupported(d.pos(), "delete");
                case Yield y -> throw unsupported(y.pos(), "yield");
            }
        }

        String takeLabel() {
            String l = pendingLabel;
            pendingLabel = null;
            return l;
        }

        Loop labeled(String name) {
            for (Loop l : loops) {
                if (name.equals(l.name())) {
                    return l;
                }
            }
            throw new IllegalStateException("unknown label " + name);
        }

        /** A switch statement: the subject in a hidden local, then each case's tests in order (§41.3). */
        void switchStatement(Switch sw) {
            Checker.SwitchInfo info = tb.switches.get(sw);
            scopes.push(new HashMap<>());
            stmt(info.subject());
            Label end = c.newLabel();
            List<Label> starts = new ArrayList<>();
            Label otherwise = end;
            for (int i = 0; i < sw.cases().size(); i++) {
                starts.add(c.newLabel());
                if (sw.cases().get(i).isDefault()) {
                    otherwise = starts.get(i);
                }
            }
            for (int i = 0; i < sw.cases().size(); i++) {
                for (Expr test : info.tests().get(i)) {
                    Label next = c.newLabel();
                    branchFalse(test, next);
                    c.goto_(starts.get(i));
                    c.labelBinding(next);
                }
            }
            c.goto_(otherwise);
            loops.push(new Loop(end, null, takeLabel()));
            for (int i = 0; i < sw.cases().size(); i++) {
                Case cs = sw.cases().get(i);
                c.labelBinding(starts.get(i));
                scoped(new Block(cs.pos(), cs.body()));
                if (cs.arrow()) {
                    c.goto_(end); // no falling through
                }
            }
            loops.pop();
            c.labelBinding(end);
            scopes.pop();
        }

        Local lookup(String name) {
            for (Map<String, Local> s : scopes) {
                Local l = s.get(name);
                if (l != null) {
                    return l;
                }
            }
            throw new IllegalStateException("unknown local " + name);
        }

        /** Evaluates an expression for its side effects only. */
        void effect(Expr e) {
            if (tb.rewritten.containsKey(e)) {
                effect(tb.rewritten.get(e));
                return;
            }
            switch (e) {
                case Assign a -> assign(a, false);
                case IncDec i -> incDec(i, false);
                default -> pop(value(e, null));
            }
        }

        void pop(Type t) {
            if (t instanceof VoidT) {
                return;
            }
            if (kind(t).slotSize() == 2) {
                c.pop2();
            } else {
                c.pop();
            }
        }

        void zero(Type t) {
            switch (kind(t)) {
                case LONG -> c.lconst_0();
                case FLOAT -> c.fconst_0();
                case DOUBLE -> c.dconst_0();
                default -> c.iconst_0();
            }
        }

        // ------------------------------------------------------------ values

        /**
         * Pushes e's value converted to {@code want} (in e's own type if null), and returns the type of
         * what it pushed. As in CodeGen: a value narrowed where it is stored or passed is converted as a
         * cast would (§41.6), and code the checker rewrote is generated in its place.
         */
        Type value(Expr e, Type want) {
            Type narrowed = tb.implicitCasts.get(e);
            if (narrowed != null) {
                implicitCast(e, narrowed);
                if (want == null) {
                    return narrowed;
                }
                widen(narrowed, want);
                return want;
            }
            return valueUncast(e, want);
        }

        /** int<<8>> b = i: a value narrowed where it is stored or passed, as a cast would (§41.6). */
        void implicitCast(Expr e, Type to) {
            Type from = type(e);
            if (from instanceof FloatConstT && to instanceof IntT) {
                double d = ((FloatV) ck.constantIn(tb, e)).value(); // int a = 2.5: toward zero, as (int)2.5
                intConstant(BigInteger.valueOf((long) d), to);
                return;
            }
            Type pushed = valueUncast(e, null);
            convert(pushed, to);
        }

        Type valueUncast(Expr e, Type want) {
            if (tb.rewritten.containsKey(e)) {
                return value(tb.rewritten.get(e), want);
            }
            Pos outer = pos;
            pos = e.pos();
            try {
                Type t = type(e);
                if (t instanceof IntConstT || t instanceof FloatConstT
                        || (e instanceof Name n && "value".equals(tb.nameKinds.get(n)))) {
                    Type as = want == null || want instanceof IntConstT || want instanceof FloatConstT ? concrete(t) : want;
                    constant(ck.constantIn(tb, e), as, e.pos());
                    return as;
                }
                Type pushed = valueAt(e, t, want);
                if (want == null) {
                    return pushed;
                }
                widen(pushed, want);
                return want;
            } finally {
                pos = outer;
            }
        }

        /** Pushes e's value, of type t, and returns the type pushed. */
        Type valueAt(Expr e, Type t, Type want) {
            switch (e) {
                case BoolLit b -> {
                    c.loadConstant(b.value() ? 1 : 0);
                    return t;
                }
                case Name n -> {
                    String kind = tb.nameKinds.get(n);
                    if ("const".equals(kind)) {
                        MemberInfo m = tb.fieldOf.get(n);
                        constant(ck.constFieldValue(ck.declaringClassOf(m), (Field) m.member), t, n.pos());
                        return t;
                    }
                    if ("local".equals(kind)) {
                        Local l = lookup(n.name());
                        c.loadLocal(kind(l.type()).asLoadable(), l.slot());
                        return l.type();
                    }
                    throw unsupported(n.pos(), "a field (" + n.name() + ")");
                }
                case Select s -> {
                    MemberInfo m = tb.selected.get(s);
                    if (m != null && m.member instanceof Field f && f.modifiers().isConst()) {
                        constant(ck.constFieldValue(ck.declaringClassOf(m), f), t, s.pos());
                        return t;
                    }
                    throw unsupported(s.pos(), "a field (" + s.name() + ")");
                }
                case Call call -> {
                    return call(call);
                }
                case Unary u -> {
                    return unary(u, t);
                }
                case Binary b -> {
                    return binary(b, t);
                }
                case Assign a -> {
                    assign(a, true);
                    return type(a.target());
                }
                case IncDec i -> {
                    incDec(i, true);
                    return type(i.target());
                }
                case Cast cast -> {
                    return cast(cast);
                }
                case Conditional cond -> {
                    Type want2 = t instanceof IntConstT || t instanceof FloatConstT ? want : t;
                    Label otherwise = c.newLabel();
                    Label end = c.newLabel();
                    branchFalse(cond.cond(), otherwise);
                    Type pushed = value(cond.then(), want2);
                    c.goto_(end);
                    c.labelBinding(otherwise);
                    value(cond.otherwise(), want2 == null ? pushed : want2);
                    c.labelBinding(end);
                    return pushed;
                }
                default -> throw unsupported(e.pos(), describe(e));
            }
        }

        String describe(Expr e) {
            return switch (e) {
                case StringLit s -> "a string here";
                case New n -> "new";
                case Lambda l -> "a lambda";
                case Index i -> "indexing";
                case Slice s -> "a bit slice";
                case InstanceOf i -> "instanceof";
                case SwitchExpr s -> "a switch expression";
                case This t -> "this";
                default -> "this expression";
            };
        }

        // ------------------------------------------------------------ constants

        /** Pushes a compile-time value as type {@code as}. */
        void constant(CVal v, Type as, Pos at) {
            switch (v) {
                case IntV i when as instanceof IntT -> intConstant(i.value(), as);
                case IntV i when as instanceof FloatT -> c.loadConstant(i.value().floatValue());
                case IntV i when as instanceof DoubleT -> c.loadConstant(i.value().doubleValue());
                case FloatV f when as instanceof FloatT -> c.loadConstant(f.value().floatValue());
                case FloatV f when as instanceof DoubleT -> c.loadConstant(f.value());
                case BitV b -> c.loadConstant(b.value() ? 1 : 0);
                default -> throw unsupported(at, "the constant " + v + " as " + as);
            }
        }

        /** An integer constant wrapped to the type's width, as the VM's constants are. */
        void intConstant(BigInteger x, Type as) {
            long bits = x.longValue();
            int w = width(as);
            if (w < 64) {
                bits = ((IntT) as).signed() ? (bits << (64 - w)) >> (64 - w) : bits & ((1L << w) - 1);
            }
            if (kind(as) == TypeKind.LONG) {
                c.loadConstant(bits);
            } else {
                c.loadConstant((int) bits);
            }
        }

        // ------------------------------------------------------------ conversions

        /**
         * A number where it meets another numeric type without a cast (§41.6): an int<<8>> stored in
         * an int, an int added to a double. Between integers only the JVM representation can change
         * (int to long); the value always fits.
         */
        void widen(Type from, Type to) {
            if (from.equals(to) || !isNumber(from) || !isNumber(to)) {
                return;
            }
            if (from instanceof IntT f && to instanceof IntT) {
                if (kind(f).asLoadable() == TypeKind.INT && kind(to) == TypeKind.LONG) {
                    toLong(f);
                }
                return;
            }
            convert(from, to);
        }

        /** An int-held integer as a long: sign-extended if signed, zero-extended otherwise. */
        void toLong(IntT from) {
            c.i2l();
            if (fullUnsigned(from)) {
                c.loadConstant(0xffffffffL);
                c.land();
            }
        }

        /** Converts between numeric types, as a cast does (§41.1); the value is on the stack. */
        void convert(Type from, Type to) {
            if (from.equals(to)) {
                return;
            }
            boolean fromFloat = from instanceof FloatT || from instanceof DoubleT;
            boolean toFloat = to instanceof FloatT || to instanceof DoubleT;
            if (from instanceof IntT f && to instanceof IntT t) {
                TypeKind fk = kind(f).asLoadable();
                TypeKind tk = kind(t).asLoadable();
                if (fk == TypeKind.INT && tk == TypeKind.LONG) {
                    toLong(f);
                } else if (fk == TypeKind.LONG && tk == TypeKind.INT) {
                    c.l2i();
                }
                if (!fits(f, t)) {
                    normalize(t);
                }
                return;
            }
            if (from instanceof IntT f && toFloat) {
                if (kind(f).asLoadable() == TypeKind.INT && !fullUnsigned(f)) {
                    c.i2d();
                } else {
                    if (kind(f).asLoadable() == TypeKind.INT) {
                        toLong(f);
                    }
                    if (fullUnsigned(f) && width(f) == 64) {
                        c.invokestatic(INTS, "unsignedToDouble", MethodTypeDesc.of(CD_double, CD_long));
                    } else {
                        c.l2d();
                    }
                }
                if (to instanceof FloatT) {
                    c.d2f(); // through a double, as the VM converts
                }
                return;
            }
            if (fromFloat && to instanceof IntT t) {
                // Toward zero into a long, then wrapped to the type, as the VM does (§41.1).
                if (from instanceof FloatT) {
                    c.f2l();
                } else {
                    c.d2l();
                }
                if (kind(t).asLoadable() == TypeKind.INT) {
                    c.l2i();
                }
                normalize(t);
                return;
            }
            if (from instanceof FloatT && to instanceof DoubleT) {
                c.f2d();
                return;
            }
            if (from instanceof DoubleT && to instanceof FloatT) {
                c.d2f();
                return;
            }
            throw unsupported(pos, "converting " + from + " to " + to);
        }

        /** Whether every value of integer type f is a value of t, so converting needs no wrapping. */
        boolean fits(IntT f, IntT t) {
            int fw = width(f);
            int tw = width(t);
            boolean fs = f.signed() && !f.isChar();
            boolean ts = t.signed() && !t.isChar();
            if (t.isChar() && !f.isChar()) {
                return !fs && fw <= 16;
            }
            if (fs == ts) {
                return fw <= tw;
            }
            return !fs && fw < tw; // unsigned into a wider signed type
        }

        /** Wraps the integer on the stack to its type's width and normalizes it (§3.1). */
        void normalize(Type t) {
            if (!(t instanceof IntT it)) {
                return;
            }
            int w = width(it);
            if (kind(it) == TypeKind.CHAR) {
                c.i2c();
            } else if (kind(it) == TypeKind.INT) {
                if (w == 32) {
                    return;
                }
                if (it.signed()) {
                    if (w == 8) {
                        c.i2b();
                    } else if (w == 16) {
                        c.i2s();
                    } else {
                        c.loadConstant(32 - w);
                        c.ishl();
                        c.loadConstant(32 - w);
                        c.ishr();
                    }
                } else {
                    c.loadConstant((1 << w) - 1);
                    c.iand();
                }
            } else {
                if (w == 64) {
                    return;
                }
                if (it.signed()) {
                    c.loadConstant(64 - w);
                    c.lshl();
                    c.loadConstant(64 - w);
                    c.lshr();
                } else {
                    c.loadConstant((1L << w) - 1);
                    c.land();
                }
            }
        }

        Type cast(Cast cast) {
            if (tb.calls.containsKey(cast)) {
                throw unsupported(cast.pos(), "a cast operator");
            }
            Type to = type(cast);
            Type from = type(cast.operand());
            if (from instanceof IntConstT || from instanceof FloatConstT) {
                if (to instanceof IntT && from instanceof FloatConstT) {
                    double d = ((FloatV) ck.constantIn(tb, cast.operand())).value();
                    intConstant(BigInteger.valueOf((long) d), to);
                    return to;
                }
                return value(cast.operand(), to);
            }
            Type pushed = value(cast.operand(), from);
            convert(pushed, to);
            return to;
        }

        // ------------------------------------------------------------ operators

        Type unary(Unary u, Type t) {
            if (tb.calls.containsKey(u)) {
                throw unsupported(u.pos(), "an operator method");
            }
            switch (u.op()) {
                case NEG -> {
                    value(u.operand(), t);
                    switch (kind(t).asLoadable()) {
                        case INT -> c.ineg();
                        case LONG -> c.lneg();
                        case FLOAT -> c.fneg();
                        case DOUBLE -> c.dneg();
                        default -> throw new IllegalStateException();
                    }
                    normalize(t);
                    return t;
                }
                case BIT_NOT -> {
                    value(u.operand(), t);
                    if (kind(t) == TypeKind.LONG) {
                        c.loadConstant(-1L);
                        c.lxor();
                    } else {
                        c.iconst_m1();
                        c.ixor();
                    }
                    normalize(t);
                    return t;
                }
                case NOT -> {
                    value(u.operand(), Type.BIT);
                    c.iconst_1();
                    c.ixor();
                    return Type.BIT;
                }
                default -> throw unsupported(u.pos(), "addresses");
            }
        }

        Type binary(Binary b, Type t) {
            if (tb.calls.containsKey(b)) {
                throw unsupported(b.pos(), "an operator method");
            }
            switch (b.op()) {
                case AND, OR, EQ, NE, LT, GT, LE, GE -> {
                    Label no = c.newLabel();
                    Label end = c.newLabel();
                    branchFalse(b, no);
                    c.iconst_1();
                    c.goto_(end);
                    c.labelBinding(no);
                    c.iconst_0();
                    c.labelBinding(end);
                    return Type.BIT;
                }
                case SHIFT -> {
                    value(b.left(), t);
                    value(b.right(), Type.INT);
                    shift(t);
                    return t;
                }
                default -> {
                    if (!isNumber(t) && !(t instanceof BitT)) {
                        throw unsupported(b.pos(), "operator " + b.op().symbol + " on " + t);
                    }
                    value(b.left(), t);
                    value(b.right(), t);
                    arithmetic(b.op(), t, b.pos());
                    return t;
                }
            }
        }

        /** Seal's <<< (§50): value and amount (an int) on the stack, through the runtime. */
        void shift(Type t) {
            c.loadConstant(width(t));
            c.loadConstant(((IntT) t).signed() ? 1 : 0);
            ClassDesc v = kind(t) == TypeKind.LONG ? CD_long : CD_int;
            c.invokestatic(INTS, "shift", MethodTypeDesc.of(v, v, CD_int, CD_int, CD_boolean));
        }

        /** Both operands of type t on the stack: the result, normalized to t. */
        void arithmetic(BinaryOp op, Type t, Pos at) {
            TypeKind k = kind(t).asLoadable();
            if (t instanceof BitT) {
                switch (op) {
                    case BIT_AND -> c.iand();
                    case BIT_OR -> c.ior();
                    case BIT_XOR -> c.ixor();
                    default -> throw unsupported(at, "operator " + op.symbol + " on bit");
                }
                return;
            }
            if (k == TypeKind.FLOAT || k == TypeKind.DOUBLE) {
                boolean d = k == TypeKind.DOUBLE;
                switch (op) {
                    case ADD -> { if (d) c.dadd(); else c.fadd(); }
                    case SUB -> { if (d) c.dsub(); else c.fsub(); }
                    case MUL -> { if (d) c.dmul(); else c.fmul(); }
                    case DIV -> { if (d) c.ddiv(); else c.fdiv(); }
                    default -> throw unsupported(at, "operator " + op.symbol + " on " + t);
                }
                return;
            }
            boolean l = k == TypeKind.LONG;
            boolean unsignedDivision = fullUnsigned(t);
            switch (op) {
                case ADD -> { if (l) c.ladd(); else c.iadd(); }
                case SUB -> { if (l) c.lsub(); else c.isub(); }
                case MUL -> { if (l) c.lmul(); else c.imul(); }
                case DIV -> {
                    if (unsignedDivision) {
                        unsignedOp("divideUnsigned", l);
                    } else if (l) {
                        c.ldiv();
                    } else {
                        c.idiv();
                    }
                }
                case MOD -> {
                    if (unsignedDivision) {
                        unsignedOp("remainderUnsigned", l);
                    } else if (l) {
                        c.lrem();
                    } else {
                        c.irem();
                    }
                }
                case BIT_AND -> { if (l) c.land(); else c.iand(); }
                case BIT_OR -> { if (l) c.lor(); else c.ior(); }
                case BIT_XOR -> { if (l) c.lxor(); else c.ixor(); }
                case SHIFT -> {
                    shift(t);
                    return;
                }
                default -> throw unsupported(at, "operator " + op.symbol + " on " + t);
            }
            normalize(t);
        }

        void unsignedOp(String name, boolean isLong) {
            ClassDesc v = isLong ? CD_long : CD_int;
            c.invokestatic(isLong ? CD_Long : CD_Integer, name, MethodTypeDesc.of(v, v, v));
        }

        /** The type both operands of a comparison are converted to, as in CodeGen. */
        Type operandType(Type l, Type r) {
            Type common = ck.operandType(l, r);
            if (common != null && isNumber(common)) {
                return common;
            }
            boolean lc = l instanceof IntConstT || l instanceof FloatConstT;
            boolean rc = r instanceof IntConstT || r instanceof FloatConstT;
            if (lc && !rc) {
                return r;
            }
            if (rc && !lc) {
                return l;
            }
            if (lc) {
                return l instanceof FloatConstT || r instanceof FloatConstT ? Type.DOUBLE : new IntT(CVal.of(64), true);
            }
            return l;
        }

        /** Jumps to {@code no} unless the condition (a bit) holds. */
        void branchFalse(Expr cond, Label no) {
            if (tb.rewritten.containsKey(cond)) {
                branchFalse(tb.rewritten.get(cond), no);
                return;
            }
            switch (cond) {
                case BoolLit b -> {
                    if (!b.value()) {
                        c.goto_(no);
                    }
                }
                case Unary u when u.op() == UnaryOp.NOT && !tb.calls.containsKey(u) -> {
                    Label yes = c.newLabel();
                    branchFalse(u.operand(), yes);
                    c.goto_(no);
                    c.labelBinding(yes);
                }
                case Binary b when b.op() == BinaryOp.AND && !tb.calls.containsKey(b) -> {
                    branchFalse(b.left(), no);
                    branchFalse(b.right(), no);
                }
                case Binary b when b.op() == BinaryOp.OR && !tb.calls.containsKey(b) -> {
                    Label yes = c.newLabel();
                    Label tryRight = c.newLabel();
                    branchFalse(b.left(), tryRight);
                    c.goto_(yes);
                    c.labelBinding(tryRight);
                    branchFalse(b.right(), no);
                    c.labelBinding(yes);
                }
                case Binary b when isComparison(b.op()) && !tb.calls.containsKey(b) -> {
                    Type ot = operandType(type(b.left()), type(b.right()));
                    value(b.left(), ot);
                    value(b.right(), ot);
                    compareFalse(b.op(), ot, no);
                }
                default -> {
                    value(cond, Type.BIT);
                    c.ifeq(no);
                }
            }
        }

        boolean isComparison(BinaryOp op) {
            return op == BinaryOp.EQ || op == BinaryOp.NE || op == BinaryOp.LT || op == BinaryOp.GT
                    || op == BinaryOp.LE || op == BinaryOp.GE;
        }

        /** Two operands of type t on the stack: jumps to {@code no} unless {@code op} holds between them. */
        void compareFalse(BinaryOp op, Type t, Label no) {
            TypeKind k = kind(t).asLoadable();
            if (k == TypeKind.FLOAT || k == TypeKind.DOUBLE) {
                // NaN compares false either way: fcmpg makes it "greater" for < and <=, fcmpl "less" for the rest.
                boolean greaterOnNaN = op == BinaryOp.LT || op == BinaryOp.LE;
                if (k == TypeKind.DOUBLE) {
                    if (greaterOnNaN) c.dcmpg(); else c.dcmpl();
                } else {
                    if (greaterOnNaN) c.fcmpg(); else c.fcmpl();
                }
                jumpUnless(op, no);
                return;
            }
            if (fullUnsigned(t)) {
                ClassDesc v = k == TypeKind.LONG ? CD_long : CD_int;
                c.invokestatic(k == TypeKind.LONG ? CD_Long : CD_Integer, "compareUnsigned", MethodTypeDesc.of(CD_int, v, v));
                jumpUnless(op, no);
                return;
            }
            if (k == TypeKind.LONG) {
                c.lcmp();
                jumpUnless(op, no);
                return;
            }
            switch (op) {
                case EQ -> c.if_icmpne(no);
                case NE -> c.if_icmpeq(no);
                case LT -> c.if_icmpge(no);
                case LE -> c.if_icmpgt(no);
                case GT -> c.if_icmple(no);
                case GE -> c.if_icmplt(no);
                default -> throw new IllegalStateException();
            }
        }

        /** An int comparing two values (negative, zero, positive) on the stack: jumps to no unless op holds. */
        void jumpUnless(BinaryOp op, Label no) {
            switch (op) {
                case EQ -> c.ifne(no);
                case NE -> c.ifeq(no);
                case LT -> c.ifge(no);
                case LE -> c.ifgt(no);
                case GT -> c.ifle(no);
                case GE -> c.iflt(no);
                default -> throw new IllegalStateException();
            }
        }

        // ------------------------------------------------------------ assignment

        Local target(Expr target) {
            if (target instanceof Name n && "local".equals(tb.nameKinds.get(n))) {
                return lookup(n.name());
            }
            throw unsupported(target.pos(), target instanceof Name n ? "assigning a field (" + n.name() + ")"
                    : "assigning " + describe(target));
        }

        /** An assignment; with {@code keep}, its value stays on the stack. */
        void assign(Assign a, boolean keep) {
            if (tb.calls.containsKey(a)) {
                throw unsupported(a.pos(), "an operator method");
            }
            Type t = type(a.target());
            Local l = target(a.target());
            TypeKind k = kind(t).asLoadable();
            if (a.op() == null) {
                value(a.value(), t);
            } else {
                c.loadLocal(k, l.slot());
                if (a.op() == BinaryOp.SHIFT) {
                    value(a.value(), Type.INT);
                    shift(t);
                } else {
                    Type wide = tb.narrowed.get(a);
                    if (wide == null) {
                        value(a.value(), t);
                        arithmetic(a.op(), t, a.pos());
                    } else {
                        // i += 2.5: in the wider type the operator gives, narrowed back as a cast would (§41.6).
                        widen(t, wide);
                        value(a.value(), wide);
                        arithmetic(a.op(), wide, a.pos());
                        convert(wide, t);
                    }
                }
            }
            if (keep) {
                dup(k);
            }
            c.storeLocal(k, l.slot());
        }

        /** `++x` gives the new value, `x++` the old one (§41.5); with {@code keep} it stays on the stack. */
        void incDec(IncDec i, boolean keep) {
            if (tb.elementSteps.containsKey(i)) {
                throw unsupported(i.pos(), "++ and -- through an operator");
            }
            Type t = type(i.target());
            Local l = target(i.target());
            TypeKind k = kind(t).asLoadable();
            c.loadLocal(k, l.slot());
            if (keep && !i.prefix()) {
                dup(k);
            }
            constant(new IntV(BigInteger.ONE), t, i.pos());
            arithmetic(i.op(), t, i.pos());
            if (keep && i.prefix()) {
                dup(k);
            }
            c.storeLocal(k, l.slot());
        }

        void dup(TypeKind k) {
            if (k.slotSize() == 2) {
                c.dup2();
            } else {
                c.dup();
            }
        }

        // ------------------------------------------------------------ calls

        Type call(Call call) {
            CallInfo ci = tb.calls.get(call);
            if (ci == null) {
                throw unsupported(call.pos(), "this call");
            }
            Type rt = type(call);
            if (ck.isRawAssembly(ci)) {
                return Type.VOID; // assembly is for a native compiler (§42.3)
            }
            if (ci.kind() == CallKind.STATIC) {
                MemberInfo m = ci.member();
                List<Type> params = ck.paramTypes(null, m, ci.binders());
                for (int i = 0; i < call.args().size(); i++) {
                    value(call.args().get(i), params.get(i));
                }
                MethodRef r = ref(new FnKey(ck.declaringClassOf(m), m, ci.binders()), call.pos());
                c.invokestatic(r.owner(), r.name(), r.type());
                return ck.returnTypeOf(null, m, ci.binders());
            }
            if (printed(call, ci)) {
                return Type.VOID;
            }
            throw unsupported(call.pos(), "calling a method on an object");
        }

        /**
         * System.stdout.print(x), println and write, and the same on System.stderr, until Writer
         * itself can be compiled (with classes, M2): each becomes the native Writer's method calls.
         */
        boolean printed(Call call, CallInfo ci) {
            ClassInfo owner = ck.declaringClassOf(ci.member());
            if (!owner.builtin || !owner.name.equals("Writer") || !(call.callee() instanceof Select s)
                    || !(s.target() instanceof Select stream) || !(tb.selected.get(stream) instanceof MemberInfo field)
                    || !ck.declaringClassOf(field).name.equals("System") || !ck.declaringClassOf(field).builtin) {
                return false;
            }
            long file = switch (stream.name()) {
                case "stdout" -> 2;
                case "stderr" -> 3;
                default -> 0;
            };
            String name = ((Method) ci.member().member).name();
            if (file == 0 || !Set.of("print", "println", "write", "writeLine", "flush").contains(name)) {
                return false;
            }
            if (name.equals("flush")) {
                c.loadConstant(file);
                c.invokestatic(NATIVES, "io_flush", MethodTypeDesc.of(CD_long, CD_long));
                c.pop2();
                return true;
            }
            if (call.args().size() == 1) {
                Expr arg = call.args().get(0);
                Type pt = ck.paramTypes(ci.owner(), ci.member(), ci.binders()).get(0);
                c.loadConstant(file);
                String nat;
                ClassDesc argDesc;
                switch (pt) {
                    case IntT it when it.isChar() -> {
                        value(arg, pt);
                        nat = "io_write_char";
                        argDesc = CD_char;
                    }
                    case IntT it -> {
                        Type pushed = value(arg, pt);
                        if (kind(pushed).asLoadable() == TypeKind.INT) {
                            toLong(it);
                        }
                        nat = it.signed() ? "io_write_int" : "io_write_uint";
                        argDesc = CD_long;
                    }
                    case FloatT f -> {
                        value(arg, pt);
                        nat = "io_write_float";
                        argDesc = CD_float;
                    }
                    case DoubleT d -> {
                        value(arg, pt);
                        nat = "io_write_double";
                        argDesc = CD_double;
                    }
                    case BitT b -> {
                        value(arg, pt);
                        nat = "io_write_bit";
                        argDesc = CD_boolean;
                    }
                    case ClassT ct when ct.cls().name.equals("string") && ct.cls().builtin -> {
                        c.loadConstant(literalText(arg));
                        nat = "io_write";
                        argDesc = CD_String;
                    }
                    default -> throw unsupported(arg.pos(), "printing " + pt);
                }
                c.invokestatic(NATIVES, nat, MethodTypeDesc.of(CD_long, CD_long, argDesc));
                c.pop2();
            } else if (!call.args().isEmpty()) {
                return false;
            }
            if (name.equals("println") || name.equals("writeLine")) {
                c.loadConstant(file);
                c.loadConstant((int) '\n');
                c.invokestatic(NATIVES, "io_write_char", MethodTypeDesc.of(CD_long, CD_long, CD_char));
                c.pop2();
            }
            return true;
        }

        /** The text of a string literal, or literals joined with + (§39); other strings come later. */
        String literalText(Expr e) {
            if (tb.rewritten.containsKey(e)) {
                return literalText(tb.rewritten.get(e));
            }
            if (e instanceof StringLit s) {
                return s.value();
            }
            if (tb.types.get(e) instanceof StrConstT sc && sc.value() != null) {
                return sc.value(); // literals joined with +, at compile time
            }
            throw unsupported(e.pos(), "a string that isn't a literal");
        }
    }
}
