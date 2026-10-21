package io.github.thelinuxseal.seal.check;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.CVal.*;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.ClassInfo.Shape;
import io.github.thelinuxseal.seal.check.Type.*;
import io.github.thelinuxseal.seal.parser.ParseError;
import io.github.thelinuxseal.seal.parser.Parser;

/**
 * Semantic checker for a whole program (a set of source files, one class each).
 *
 * <p>Generic code is checked once per class shape, with that shape's
 * compile-time values kept symbolic (§12). Unguarded members are therefore
 * checked against every shape, which enforces the coverage rule (§10) and
 * per-shape member types (§9.2). Checks that depend on actual values, such
 * as a negative array size, run when a concrete instantiation like
 * `Array<int><<-1>>` is used, and are reported at that use.
 */
public final class Checker {

    /** Source of the implicit root class (§31). */
    private static final String PRELUDE_OBJECT = """
            package seal.lang;
            class Object {
                /**
                 * A hash code for HashMap keys (§42.4): by default from the object's identity,
                 * matching the default ==. A class that defines its own == should define hash()
                 * to match: equal objects must have equal hashes.
                 */
                public uint<<64>> hash() { return identityHash(this); }

                @native("identity_hash") static uint<<64>> identityHash(Object o) { }
            }
            """;

    /**
     * Built-in output for programs compiled without the standard library: System.stdout
     * and System.stderr, writing numbers, characters and bits (§42.2). The library's
     * Writer and System replace these. `@native` is only allowed in prelude and
     * library classes.
     */
    private static final String PRELUDE_WRITER = """
            package seal.io;
            class Writer {
                int<<64>> file;
                private<System> new(int<<64>> f) { file = f; }
                @native("io_write_char") static int<<64>> nChar(int<<64>> file, char c) { }
                @native("io_write_int") static int<<64>> nInt(int<<64>> file, int<<64>> x) { }
                @native("io_write_uint") static int<<64>> nUint(int<<64>> file, uint<<64>> x) { }
                @native("io_write_float") static int<<64>> nFloat(int<<64>> file, float x) { }
                @native("io_write_double") static int<<64>> nDouble(int<<64>> file, double x) { }
                @native("io_write_bit") static int<<64>> nBit(int<<64>> file, bit x) { }
                @native("io_write_fixed") static int<<64>> nFixed(int<<64>> file, fixed<<size N, size M>> x) { }
                @native("io_write_ufixed") static int<<64>> nUfixed(int<<64>> file, ufixed<<size N, size M>> x) { }
                public void print(char c) { nChar(file, c); }
                public void println(char c) { print(c); nChar(file, '\\n'); }
                public void print(int x) { nInt(file, (int<<64>>)x); }
                public void println(int x) { print(x); nChar(file, '\\n'); }
                public void print(int<<size W>> x) { nInt(file, (int<<64>>)x); }
                public void println(int<<size W>> x) { print(x); nChar(file, '\\n'); }
                public void print(uint<<size W>> x) { nUint(file, (uint<<64>>)x); }
                public void println(uint<<size W>> x) { print(x); nChar(file, '\\n'); }
                public void print(float x) { nFloat(file, x); }
                public void println(float x) { print(x); nChar(file, '\\n'); }
                public void print(double x) { nDouble(file, x); }
                public void println(double x) { print(x); nChar(file, '\\n'); }
                public void print(bit x) { nBit(file, x); }
                public void println(bit x) { print(x); nChar(file, '\\n'); }
                public void print(fixed<<size A, size B>> x) { nFixed(file, x); }
                public void println(fixed<<size A, size B>> x) { print(x); nChar(file, '\\n'); }
                public void print(ufixed<<size A, size B>> x) { nUfixed(file, x); }
                public void println(ufixed<<size A, size B>> x) { print(x); nChar(file, '\\n'); }
                public void println() { nChar(file, '\\n'); }
            }
            """;

    private static final String PRELUDE_SYSTEM = """
            package seal.lang;
            import seal.io.Writer;
            struct System {
                public static Writer stdout = new Writer(2);
                public static Writer stderr = new Writer(3);
            }
            """;

    private static final int MAX_INSTANTIATION_DEPTH = 64;

    /** The standard library's package that every file sees without an import, as Java's java.lang. */
    public static final String LANG = "seal.lang";

    /** seal.java's names for the Java classes Seal has its own of, or names its own way (docs/JAVA.md §3.1). */
    private static final Map<String, String> JAVA_NAMES = Map.ofEntries(
            Map.entry("JavaObject", "java.lang.Object"), Map.entry("JavaString", "java.lang.String"),
            Map.entry("JavaIntBoxed", "java.lang.Integer"), Map.entry("JavaLongBoxed", "java.lang.Long"),
            Map.entry("JavaShortBoxed", "java.lang.Short"), Map.entry("JavaByteBoxed", "java.lang.Byte"),
            Map.entry("JavaFloatBoxed", "java.lang.Float"), Map.entry("JavaDoubleBoxed", "java.lang.Double"),
            Map.entry("JavaCharBoxed", "java.lang.Character"), Map.entry("JavaBooleanBoxed", "java.lang.Boolean"));

    /** seal.java's names for Java's primitive types, and their descriptors. */
    private static final Map<String, Character> JAVA_PRIMITIVES = Map.of("JavaInt", 'I', "JavaLong", 'J', "JavaShort", 'S',
            "JavaByte", 'B', "JavaFloat", 'F', "JavaDouble", 'D', "JavaChar", 'C', "JavaBoolean", 'Z');

    /** The file Java's classes say they are in, in a message. */
    private static final String JAVA_FILE = "<java>";

    /** Library types whose elements are copied in and out, so can't be uniques. */
    private static final Set<String> COPYING_COLLECTIONS = Set.of("array", "List", "Queue", "HashMap", "IntMap", "HashMap.Entry");

    /** Checks the given files (path to source text) and returns all diagnostics, in order. */
    public static List<Diagnostic> check(Map<String, String> sources) {
        return analyze(sources).diagnostics();
    }

    /** Checks the given files and keeps the analysis, for code generation. */
    public static Checker analyze(Map<String, String> sources) {
        return analyze(sources, Map.of());
    }

    /**
     * Checks the given files together with library files (the standard
     * library). Library classes may use `@native`, replace built-in classes of
     * the same name, and cannot be redefined by the program.
     */
    public static Checker analyze(Map<String, String> sources, Map<String, String> library) {
        return analyze(sources, library, Set.of());
    }

    /** As above, also reporting the warnings asked for (§56.1), in the program's own files. */
    public static Checker analyze(Map<String, String> sources, Map<String, String> library, Set<Warning> warn) {
        return analyze(sources, library, warn, JavaClasses.jdk());
    }

    /** As above, with the Java classes the program may import: the JDK's and the class path's (docs/JAVA.md §6). */
    public static Checker analyze(Map<String, String> sources, Map<String, String> library, Set<Warning> warn,
                                  JavaClasses java) {
        Checker c = new Checker();
        c.warn.addAll(warn);
        c.javaClasses = java;
        c.run(sources, library);
        return c;
    }

    /** The Java classes the program sees (docs/JAVA.md §6). */
    public JavaClasses javaClasses() {
        return javaClasses;
    }

    /** The errors. */
    public List<Diagnostic> diagnostics() {
        return new ArrayList<>(diagnostics);
    }

    /** The warnings that were turned on and found, in order. They don't stop a program from running. */
    public List<Diagnostic> warnings() {
        return new ArrayList<>(warnings);
    }

    // ================================================================ results for code generation

    public enum CallKind {
        /** An instance method; virtual when the owner is a dynamic class. */
        METHOD,
        STATIC,
        /** `super.m(...)`: the parent's method, called directly. */
        SUPER_METHOD,
        CONSTRUCTOR,
        /** `super(...)` in a constructor. */
        SUPER_CONSTRUCTOR,
        /** An overloaded operator; see {@link CallInfo#swap()} and {@link CallInfo#negate()}. */
        OPERATOR
    }

    /**
     * What a call, `new`, or overloaded operator resolved to.
     *
     * @param member  the method or constructor; null for a class without constructors
     * @param owner   the instance type the member is called on; null for static methods
     * @param binders values inferred for the method's binders (§9.3)
     * @param swap    for derived comparisons: call the operator with the operands swapped
     * @param negate  for derived comparisons: negate the result
     */
    public record CallInfo(CallKind kind, MemberInfo member, ClassT owner, Map<String, CVal> binders,
                           boolean swap, boolean negate) {
    }

    /**
     * A call of a Java method or constructor (docs/JAVA.md §4): the class the JVM's call names (the
     * object's type, or the class written before a static method's name), and, for a call through
     * varargs, how many arguments come before those put in the array (-1 for none); {@code special}
     * for super.m() in a Seal class extending a Java one, which calls the parent's own (§5).
     */
    public record JavaCall(ClassT qualifier, int varargsFrom, boolean special) {
        public JavaCall(ClassT qualifier, int varargsFrom) {
            this(qualifier, varargsFrom, false);
        }
    }

    /**
     * A checked switch: the subject, held in a hidden local, and for each case the
     * `$subject == label` tests that select it (empty for default).
     */
    public record SwitchInfo(VarDecl subject, List<List<Expr>> tests) {
    }

    /**
     * A checked lambda (§30.3): the interface and method it implements, the parameter and
     * return types, the variables it copies when created (in order), and the enclosing
     * object's type if it uses that object's fields or methods (null otherwise); and the
     * interface its object is, which may extend the one declaring the method (Java's
     * BinaryOperator, whose method is BiFunction's, docs/JAVA.md §5).
     */
    public record LambdaInfo(ClassT iface, MemberInfo method, List<Type> params, Type returnType,
                             List<String> captureNames, List<Type> captureTypes, ClassT thisType, ClassT target) {
    }

    /** While checking a lambda body: the scope depth where it starts, and what it captures. */
    private static final class LambdaCtx {
        final int base;
        final Map<String, Type> captures = new LinkedHashMap<>();
        boolean usesThis;

        LambdaCtx(int base) {
            this.base = base;
        }
    }

    /** One member body typed for a concrete instantiation. */
    public static final class TypedBody {
        public final Map<Expr, Type> types;
        public final Map<Name, String> nameKinds;
        public final Map<Name, MemberInfo> fieldOf;
        public final Map<Select, MemberInfo> selected;
        public final Map<Expr, ClassT> owners;
        public final Map<Expr, CallInfo> calls;
        public final Map<VarDecl, Type> locals;
        /** The statements for-each loops and try-with-resources stand for; generate those instead. */
        public final Map<Stmt, Stmt> desugared;
        public final Map<Switch, SwitchInfo> switches;
        /** A switch expression's hidden subject and its tests, as for a switch statement (§41.3). */
        public final Map<SwitchExpr, SwitchInfo> switchExprs;
        /** Asserts that run (with -Wassert), as the if-and-throw each stands for (§41.4). */
        public final Map<Assert, Stmt> assertions;
        /** Statements to generate in place of others: xs[i] += v on an object element (§46). */
        public final Map<Stmt, Stmt> statementSteps;
        public final Map<Lambda, LambdaInfo> lambdas;
        /** The class each catch clause catches. */
        public final Map<Catch, ClassT> catchTypes;
        /** Expressions to generate in place of others: 5 + "x" is "" + 5 + "x". */
        public final Map<Expr, Expr> rewritten;
        /** xs[i]++ on an object with a []= operator: the xs[i] = xs[i] + 1 it runs, reading xs[i] once (§41.5). */
        public final Map<IncDec, Assign> elementSteps;
        /** Calls of Java's methods and constructors, with what the JVM's call needs (docs/JAVA.md §4). */
        public final Map<Expr, JavaCall> javaCalls;
        /** i += 2.5: the type the operator computes in, narrowed back to i's as a cast does (§41.6). */
        public final Map<Assign, Type> narrowed;
        /** int<<8>> b = i: values converted to a narrower number type where stored or passed, as a cast would (§41.6). */
        public final Map<Expr, Type> implicitCasts;
        public final List<Type> params;
        public final Type returnType;
        public final List<Diagnostic> errors;
        /** The source file the body comes from. */
        public final String file;
        final Env env;

        TypedBody(String file, Body b, List<Type> params, Type returnType, List<Diagnostic> errors) {
            this.file = file;
            this.types = b.types;
            this.nameKinds = b.nameKinds;
            this.fieldOf = b.fieldOf;
            this.selected = b.selected;
            this.owners = b.owners;
            this.calls = b.calls;
            this.locals = b.locals;
            this.desugared = b.desugared;
            this.switches = b.switches;
            this.switchExprs = b.switchExprInfo;
            this.assertions = b.assertions;
            this.statementSteps = b.statementSteps;
            this.lambdas = b.lambdas;
            this.catchTypes = b.catchTypes;
            this.rewritten = b.rewritten;
            this.elementSteps = b.elementSteps;
            this.javaCalls = b.javaCalls;
            this.narrowed = b.narrowed;
            this.implicitCasts = b.implicitCasts;
            this.params = params;
            this.returnType = returnType;
            this.errors = errors;
            this.env = b.env;
        }
    }

    /** A type of the standard library every file sees, by its simple name: a built-in one (string, array) or seal.lang's. */
    public ClassInfo classNamed(String name) {
        ClassInfo c = classes.get(name);
        return c != null && c.builtin ? c : classes.get(LANG + "." + name);
    }

    /** Whether a file is the standard library's (or the built-in prelude), not the program's. */
    public boolean isLibraryFile(String path) {
        return libraryFiles.contains(path) || path.equals("<builtin>");
    }

    /**
     * The classes `--main name` may mean (§56.1): the one with that name and its package
     * (com.example.Main), or else every top-level class of the program with that plain name.
     */
    public List<ClassInfo> mainCandidates(String name) {
        ClassInfo exact = classes.get(name);
        if (exact != null && !exact.builtin) {
            return List.of(exact);
        }
        return classes.values().stream().filter(c -> !c.builtin && c.outer == null && c.simpleName.equals(name)).toList();
    }

    /** Every class, Java's read so far among them (a copy: using one may read more). */
    public java.util.Collection<ClassInfo> allClasses() {
        return new ArrayList<>(classes.values());
    }

    /**
     * Types the body of {@code m} (or a field's initializer) for a concrete
     * owner, with the method's binders set to {@code binders}. Errors that only
     * show up with these values are returned rather than reported.
     *
     * @param owner the concrete instance type, or null for static members
     */
    public TypedBody typeBody(ClassInfo cls, ClassT owner, MemberInfo m, Map<String, CVal> binders) {
        List<Diagnostic> found = new ArrayList<>();
        String saved = file;
        file = cls.file;
        captures.push(new Capture(found, true));
        try {
            boolean isStatic = m.member.modifiers().isStatic();
            Env env = owner == null ? Env.empty(cls) : instanceEnv(owner, m);
            addMethodTypes(env, m, binders);
            Map<String, Const> bound = new HashMap<>();
            Map<String, Type> binderTypes = new LinkedHashMap<>();
            switch (m.member) {
                case Method md -> collectBinderTypes(md.params(), cls, binderTypes);
                case Constructor c -> collectBinderTypes(c.params(), cls, binderTypes);
                default -> {
                }
            }
            for (Map.Entry<String, CVal> e : binders.entrySet()) {
                if (e.getValue() instanceof TypeV) {
                    continue; // a type, in env.typeVars
                }
                Const c = new Const(e.getValue(), binderTypes.getOrDefault(e.getKey(), Type.INT));
                bound.put(e.getKey(), c);
                env.values.put(e.getKey(), c);
            }
            Opts paramOpts = new Opts(false, false, false, null, bound);
            int shape = owner == null ? -1 : owner.header();
            switch (m.member) {
                case Field f -> {
                    Type ft = resolveType(f.type(), env, new Opts(false, false, cls.dynamic, null));
                    Body b = new Body(cls, shape, env, owner, isStatic, false, Type.VOID, f.name());
                    if (f.init() != null && !f.modifiers().isConst()) {
                        requireConvertible(b, f.init(), checkExpr(b, f.init(), ft), ft, "initializer of " + f.name());
                    } else if (f.init() != null) {
                        checkExpr(b, f.init(), ft);
                    }
                    return new TypedBody(cls.file, b, List.of(), ft, found);
                }
                case Method md -> {
                    List<Type> params = new ArrayList<>();
                    for (Param p : md.params()) {
                        params.add(resolveType(p.type(), env, paramOpts));
                    }
                    Type ret = resolveType(md.returnType(), env, new Opts(true, false, false, null));
                    Body b = new Body(cls, shape, env, owner, isStatic, false, ret, md.name());
                    for (int i = 0; i < params.size(); i++) {
                        b.scopes.peek().put(md.params().get(i).name(), params.get(i));
                        b.paramNames.add(md.params().get(i).name());
                    }
                    if (!isNative(md) && md.body() != null) {
                        checkBlock(b, md.body(), false);
                    }
                    return new TypedBody(cls.file, b, params, ret, found);
                }
                case Constructor ctor -> {
                    List<Type> params = new ArrayList<>();
                    for (Param p : ctor.params()) {
                        params.add(resolveType(p.type(), env, paramOpts));
                    }
                    Body b = new Body(cls, shape, env, owner, false, true, Type.VOID, "new");
                    for (int i = 0; i < params.size(); i++) {
                        b.scopes.peek().put(ctor.params().get(i).name(), params.get(i));
                        b.paramNames.add(ctor.params().get(i).name());
                    }
                    checkBlock(b, ctor.body(), false);
                    return new TypedBody(cls.file, b, params, Type.VOID, found);
                }
                case Destructor d -> {
                    Body b = new Body(cls, shape, env, owner, false, false, Type.VOID, "delete");
                    checkBlock(b, d.body(), false);
                    return new TypedBody(cls.file, b, List.of(), Type.VOID, found);
                }
            }
        } finally {
            captures.pop();
            file = saved;
        }
    }

    private void collectBinderTypes(List<Param> params, ClassInfo cls, Map<String, Type> out) {
        for (Param p : params) {
            collectBinderTypes(p.type(), cls, out);
        }
    }

    private void collectBinderTypes(TypeRef t, ClassInfo cls, Map<String, Type> out) {
        if (t.valueArgs() != null) {
            for (ValueItem item : t.valueArgs().items()) {
                if (item instanceof Binder b) {
                    out.put(b.name(), quietly(() -> resolveBinderType(b.type(), cls)));
                }
            }
        }
        for (TypeRef a : t.typeArgs()) {
            collectBinderTypes(a, cls, out);
        }
    }

    /** Evaluates a constant expression (one typed as an integer or float constant) in a typed body. */
    public CVal constantIn(TypedBody tb, Expr e) {
        return quietly(() -> evalConst(e, tb.env)).value();
    }

    /** The value of a const field. */
    public CVal constFieldValue(ClassInfo c, Field f) {
        return quietly(() -> evalConstField(c, f, f.pos())).value();
    }

    /** The declared type of a static field. */
    public Type staticFieldType(ClassInfo c, Field f) {
        String saved = file;
        file = c.file;
        try {
            return quietly(() -> resolveType(f.type(), Env.empty(c), Opts.PLAIN));
        } finally {
            file = saved;
        }
    }

    /** The type of a stored field of a concrete instance. */
    public Type fieldTypeOf(ClassT owner, MemberInfo m) {
        return quietly(() -> fieldType(owner, m));
    }

    /** Size in bits of a concrete type, or -1 if it has none. */
    public long sizeBits(Type t) {
        BigInteger v = quietly(() -> layout(t, new Pos(0, 0))).size().intValue();
        return v == null ? -1 : v.longValueExact();
    }

    public long alignBits(Type t) {
        BigInteger v = quietly(() -> layout(t, new Pos(0, 0))).align().intValue();
        return v == null ? -1 : v.longValueExact();
    }

    /** The parent of a concrete dynamic class instance; Object's parent is null. */
    public ClassT parentType(ClassT t) {
        return parentOf(t);
    }

    /**
     * A program's entry point (§56.1): a `main()` with no parameters, declared in the
     * main class or inherited. {@code owner} is the type the method is seen through, or
     * null for a static main, or for an instance main of a class with type parameters or
     * shapes (which the program can't create).
     */
    public record EntryPoint(MemberInfo method, ClassInfo declaring, ClassT owner, boolean isStatic) {
    }

    /** The entry point of {@code main}, as Java chooses it: a static main() first, then an instance one; or null. */
    public EntryPoint entryPoint(ClassInfo main) {
        boolean plain = main.typeParams.isEmpty() && main.shapes.size() == 1 && main.shapes.get(0).binders.isEmpty();
        ClassT self = plain ? new ClassT(main, List.of(), List.of(), 0) : null;
        for (boolean wantStatic : new boolean[]{true, false}) {
            ClassInfo c = main;
            ClassT t = self;
            while (c != null) {
                for (MemberInfo m : c.members.getOrDefault("main", List.of())) {
                    if (m.member instanceof Method md && md.params().isEmpty() && md.body() != null
                            && md.modifiers().isStatic() == wantStatic && md.typeParams().isEmpty()) {
                        return new EntryPoint(m, c, wantStatic ? null : t, wantStatic);
                    }
                }
                t = t == null ? null : parentOf(t);
                c = t == null ? null : t.cls();
            }
        }
        return null;
    }

    /** Parameter types of a method or constructor as seen through {@code owner}, with binders set. */
    public List<Type> paramTypes(ClassT owner, MemberInfo m, Map<String, CVal> binders) {
        ClassInfo declaring = owner != null ? owner.cls() : declaringClass(m);
        Env env = owner != null ? instanceEnv(owner, m) : Env.empty(declaring);
        addMethodTypes(env, m, binders);
        List<Param> params = switch (m.member) {
            case Method md -> md.params();
            case Constructor c -> c.params();
            default -> List.of();
        };
        Map<String, Type> binderTypes = new LinkedHashMap<>();
        collectBinderTypes(params, declaring, binderTypes);
        Map<String, Const> bound = new HashMap<>();
        binders.forEach((k, v) -> {
            if (!(v instanceof TypeV)) {
                bound.put(k, new Const(v, binderTypes.getOrDefault(k, Type.INT)));
                env.values.put(k, bound.get(k)); // for later parameters that use it: min(int<<size W>> a, int<<W>> b)
            }
        });
        List<Type> out = new ArrayList<>();
        for (Param p : params) {
            out.add(quietly(() -> resolveType(p.type(), env, new Opts(false, false, false, null, bound))));
        }
        return out;
    }

    /** Return type of a method as seen through {@code owner}, with binders set. */
    public Type returnTypeOf(ClassT owner, MemberInfo m, Map<String, CVal> binders) {
        Method md = (Method) m.member;
        ClassInfo declaring = owner != null ? owner.cls() : declaringClass(m);
        Env env = owner != null ? instanceEnv(owner, m) : Env.empty(declaring);
        addMethodTypes(env, m, binders);
        Map<String, Type> binderTypes = new LinkedHashMap<>();
        collectBinderTypes(md.params(), declaring, binderTypes);
        binders.forEach((k, v) -> {
            if (!(v instanceof TypeV)) {
                env.values.put(k, new Const(v, binderTypes.getOrDefault(k, Type.INT)));
            }
        });
        return quietly(() -> resolveType(md.returnType(), env, new Opts(true, false, false, null)));
    }

    public static boolean isNative(Method md) {
        return nativeName(md) != null;
    }

    /** The runtime's function (Natives) implementing a `@native("name")` method, or null. */
    public static String nativeName(Method md) {
        for (Decorator d : md.modifiers().decorators()) {
            if (d.name().equals("native") && d.args().size() == 1 && d.args().get(0) instanceof StringLit s) {
                return s.value();
            }
        }
        return null;
    }

    /**
     * What stands for a library method on the JVM (docs/JVM.md §8): `@jvm("Strings.at")`, a static
     * method of the runtime's class Strings, or `@jvm("array.get")`, which the JVM backend generates
     * itself; null if the method compiles as it is.
     */
    public static String jvmName(Method md) {
        for (Decorator d : md.modifiers().decorators()) {
            if (d.name().equals("jvm") && d.args().size() == 1 && d.args().get(0) instanceof StringLit s) {
                return s.value();
            }
        }
        return null;
    }

    /** A library method the JVM's runtime does, and that has no Seal body. */
    public static boolean isJvmOnly(Method md) {
        return md.body() == null && jvmName(md) != null;
    }

    public ClassInfo declaringClassOf(MemberInfo m) {
        return declaringClass(m);
    }

    // ================================================================ state

    /** A compile-time value together with its type (§8). */
    record Const(CVal value, Type type) {
    }

    /** What a name in `<<...>>` or a type refers to. */
    static final class Env {
        final ClassInfo cls;
        final Map<String, Type> typeVars;
        final Map<String, Const> values;

        Env(ClassInfo cls, Map<String, Type> typeVars, Map<String, Const> values) {
            this.cls = cls;
            this.typeVars = typeVars;
            this.values = values;
        }

        Env copy() {
            return new Env(cls, new HashMap<>(typeVars), new LinkedHashMap<>(values));
        }

        static Env empty(ClassInfo cls) {
            return new Env(cls, new HashMap<>(), new LinkedHashMap<>());
        }
    }

    /**
     * Options for resolving a type reference.
     *
     * @param binders when non-null, binders such as `<<int K>>` are allowed and collected here
     * @param bound   when non-null, binders take these already-inferred values instead
     */
    record Opts(boolean allowVoid, boolean allowDynamic, boolean allowTail, Map<String, Type> binders,
                Map<String, Const> bound) {
        static final Opts PLAIN = new Opts(false, false, false, null);

        Opts(boolean allowVoid, boolean allowDynamic, boolean allowTail, Map<String, Type> binders) {
            this(allowVoid, allowDynamic, allowTail, binders, null);
        }

        Opts inner(boolean dynamic) {
            return new Opts(false, dynamic, false, binders, bound);
        }
    }

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    /** Java's classes (docs/JAVA.md §6): the JDK's, and the class path's. */
    private JavaClasses javaClasses = JavaClasses.jdk();
    /** The Java classes read so far, by binary name (java.util.Map$Entry); java.lang.String's is seal.java's JavaString. */
    private final Map<String, ClassInfo> javaByBinary = new HashMap<>();
    /** Binary names that name no Java class. */
    private final Set<String> notJava = new HashSet<>();
    /** The class-file member each of a Java class's members was made from. */
    private final Map<Member, JavaClasses.JMember> javaMembers = new IdentityHashMap<>();
    /** What each type parameter of a Java class (by its header) or a Java method erases to (docs/JAVA.md §4.1). */
    private final Map<Object, Map<String, TypeRef>> javaErasures = new IdentityHashMap<>();
    /** The class each member is declared in, as found. */
    private final Map<MemberInfo, ClassInfo> declaringCache = new IdentityHashMap<>();
    private final Set<Diagnostic> diagnostics = new LinkedHashSet<>();
    private final Set<Diagnostic> warnings = new LinkedHashSet<>();
    /** The standard library's files, which give no warnings. */
    private final Set<String> libraryFiles = new HashSet<>();
    /** The warnings to report. */
    private final Set<Warning> warn = java.util.EnumSet.noneOf(Warning.class);

    /** File that positions currently refer to. */
    private String file = "";

    /**
     * While non-empty, diagnostics are captured instead of reported. A capture
     * either keeps everything ({@code all}) or only value-dependent messages
     * (see {@link #reportValue}).
     */
    private record Capture(List<Diagnostic> found, boolean all) {
    }

    private final Deque<Capture> captures = new ArrayDeque<>();

    private final Map<ClassT, Lay> layoutCache = new HashMap<>();
    private final Set<ClassT> validated = new HashSet<>();
    private final Set<ClassT> layoutInProgress = new HashSet<>();
    private int instantiationDepth = 0;
    private final Set<Field> constInProgress = new HashSet<>();
    /** Set when evaluating a const field reaches itself again; reported by the outermost evaluation. */
    private Field constCycle;

    private Checker() {
    }

    // ================================================================ reporting

    private void report(Pos pos, String message) {
        Diagnostic d = new Diagnostic(file, pos.line(), pos.column(), message);
        if (captures.isEmpty()) {
            diagnostics.add(d);
        } else if (captures.peek().all()) {
            captures.peek().found().add(d);
        }
    }

    /**
     * Reports an error that depends on actual compile-time values. When
     * validating a concrete instantiation these are captured and re-reported
     * at the place the instantiation is used.
     */
    private void reportValue(Pos pos, String message) {
        Diagnostic d = new Diagnostic(file, pos.line(), pos.column(), message);
        if (captures.isEmpty()) {
            diagnostics.add(d);
        } else {
            captures.peek().found().add(d);
        }
    }

    /**
     * Reports a warning, when it is turned on and the code is the program's own. Code checked
     * again for a concrete instantiation (or quietly) gives none: it was warned about already.
     */
    private void warn(Warning w, Pos pos, String message) {
        if (warn.contains(w) && captures.isEmpty() && !libraryFiles.contains(file) && !file.equals("<builtin>")) {
            warnings.add(new Diagnostic(file, pos.line(), pos.column(), message, w));
        }
    }

    /**
     * A number converted to another number type without a cast, at {@code pos} (§41.6), for
     * -WautoCast; one that may lose data is for -WautoCastLossy too, and is reported once, as
     * that. Constants aren't converted: they take the type they are used as.
     */
    private void autoCast(Pos pos, Type from, Type to, String where) {
        if (isNumber(from) && isNumber(to) && !from.equals(to)) {
            autoCast(pos, from, to, where, !widens(from, to) || rounds(from, to));
        }
    }

    private void autoCast(Pos pos, Type from, Type to, String where, boolean lossy) {
        String message = "implicit cast from " + from + " to " + to + " in " + where
                + (!lossy ? "" : widens(from, to) ? " may round" : " may lose data");
        warn(lossy && warn.contains(Warning.AUTO_CAST_LOSSY) ? Warning.AUTO_CAST_LOSSY : Warning.AUTO_CAST, pos, message);
    }

    /**
     * Whether widening {@code from} to float or double can round: it has more significant bits
     * than their 24 or 53, as int to float and int<<64>> to double do.
     */
    private static boolean rounds(Type from, Type to) {
        int mantissa = to instanceof FloatT ? 24 : to instanceof DoubleT ? 53 : Integer.MAX_VALUE;
        CVal width = switch (from) {
            case IntT it -> it.width();
            case FixedT ft -> ft.width();
            default -> null;
        };
        if (width == null) {
            return false; // float to double is exact
        }
        boolean signed = from instanceof IntT it ? it.signed() : ((FixedT) from).signed();
        BigInteger w = width.intValue();
        return w == null || w.longValue() - (signed ? 1 : 0) > mantissa;
    }

    /** Runs {@code body} with every diagnostic suppressed. */
    private <T> T quietly(java.util.function.Supplier<T> body) {
        captures.push(new Capture(new ArrayList<>(), false));
        try {
            return body.get();
        } finally {
            captures.pop();
        }
    }

    // ================================================================ driver

    private void run(Map<String, String> sources, Map<String, String> library) {
        List<ClassInfo> order = new ArrayList<>();
        for (String prelude : List.of(PRELUDE_OBJECT, PRELUDE_WRITER, PRELUDE_SYSTEM)) {
            ClassInfo builtin = load("<builtin>", prelude, true);
            classes.put(builtin.qualifiedName(), builtin);
        }
        libraryFiles.addAll(library.keySet());
        for (Map.Entry<String, String> e : library.entrySet()) {
            ClassInfo info = load(e.getKey(), e.getValue(), true);
            if (info != null && info.pkg.equals("seal.java") && JAVA_NAMES.containsKey(info.name)) {
                info = javaBacked(info);
            }
            if (info != null) {
                addClass(e.getKey(), info, true, order);
            }
        }
        for (Map.Entry<String, String> e : sources.entrySet()) {
            ClassInfo info = load(e.getKey(), e.getValue(), false);
            if (info != null) {
                file = e.getKey();
                // The types every file sees, seal.lang's and the built-in ones, keep their names where they are seen
                // without an import: in no package.
                ClassInfo seen = info.pkg.isEmpty() ? classNamed(info.name) : null;
                if (info.name.equals("size")) {
                    report(info.decl.pos(), "size is a primitive type, as int is (§37.2); choose another name");
                    continue;
                }
                if (seen != null) {
                    report(info.decl.pos(), info.kind.keyword + " " + info.name + " is part of the standard library ("
                            + seen.qualifiedName() + ", which every file sees); choose another name, or put it in a package");
                    continue;
                }
                if (info.pkg.equals("seal") || info.pkg.startsWith("seal.")) {
                    report(info.pkgPos, "package " + info.pkg + " is the standard library's; choose another name for yours");
                    continue;
                }
                addClass(e.getKey(), info, false, order);
            }
        }

        // Java's classes come in as they are named, while these run: each loop goes over the classes there were.
        order.removeIf(Checker::isJavaType); // Java's own are checked by javac (docs/JAVA.md §6)
        for (ClassInfo c : allClasses()) {
            file = c.file;
            if (c.java != null) {
                declareJava(c);
            } else {
                declareHeaders(c);
            }
        }
        synthesizeVirtualFields();
        for (ClassInfo c : allClasses()) {
            if (c.java == null) {
                file = c.file;
                declareMembers(c);
            }
        }
        for (ClassInfo c : allClasses()) {
            if (!c.builtin && c.outer == null) {
                file = c.file;
                checkImports(c); // after the members, which static imports name
            }
        }
        for (ClassInfo c : allClasses()) {
            if (c.outer != null && c.outer.members.containsKey(c.simpleName)) {
                file = c.file;
                report(c.decl.pos(), c.outer.name + " already has a member named " + c.simpleName
                        + "; a nested type needs a name of its own");
            }
        }
        for (ClassInfo c : order) {
            file = c.file;
            checkExtends(c);
            checkSignatures(c);
            checkConstructorCoverage(c);
            checkDestructorRules(c);
        }
        checkInheritanceCycles(order);
        for (ClassInfo c : order) {
            file = c.file;
            checkAbstractMembers(c);
            checkImplements(c);
            checkJavaOverrides(c);
            checkVirtualFieldTypes(c);
        }
        checkInlineCycles(order);
        for (ClassInfo c : order) {
            file = c.file;
            checkBodies(c);
        }
    }

    /** Registers a loaded class; library classes replace built-in prelude classes of the same name. */
    private boolean addClass(String path, ClassInfo info, boolean isLibrary, List<ClassInfo> order) {
        file = path;
        String base = baseName(path);
        if (!base.isEmpty() && !base.equals(info.name)) {
            report(info.decl.pos(), info.kind.keyword + " " + info.name + " must be in a file named " + info.name
                    + " (found " + base + ")");
        }
        if (!info.pkg.isEmpty()) {
            // As in Java, package com.example lives in a folder com/example (§2.4).
            List<String> want = List.of(info.pkg.split("\\."));
            List<String> dirs = folders(path);
            if (dirs.size() < want.size() || !dirs.subList(dirs.size() - want.size(), dirs.size()).equals(want)) {
                List<String> near = dirs.subList(Math.max(0, dirs.size() - want.size()), dirs.size());
                report(info.pkgPos, "package " + info.pkg + " goes in a folder " + String.join("/", want) + ", but "
                        + baseName(path) + " is in " + (dirs.isEmpty() ? "no folder" : (near.size() < dirs.size() ? ".../" : "")
                        + String.join("/", near)));
            }
        }
        ClassInfo existing = classes.get(info.qualifiedName());
        boolean replacesPrelude = existing != null && isLibrary && existing.file.equals("<builtin>")
                && !existing.isRoot();
        if (existing != null && !replacesPrelude) {
            report(info.decl.pos(), info.kind.keyword + " " + info.qualifiedName() + " is already defined in " + existing.file);
            return false;
        }
        classes.put(info.qualifiedName(), info);
        order.add(info);
        addNested(path, info, isLibrary, order);
        return true;
    }

    /** Registers the types declared inside {@code outer}, as Outer.Name, and theirs in turn (§2.3). */
    private void addNested(String path, ClassInfo outer, boolean isLibrary, List<ClassInfo> order) {
        for (NestedType n : outer.decl.nested()) {
            if (n.decl().headers().isEmpty()) {
                continue; // a parse error, reported
            }
            Modifiers m = n.modifiers();
            if (m.isConst() || !m.friends().isEmpty() || !m.decorators().isEmpty()) {
                report(n.pos(), "a nested type is public or private, and may be static; nothing else");
            }
            ClassInfo info = new ClassInfo(path, n.decl(), isLibrary, outer, m.access() == Access.PUBLIC);
            if (classes.containsKey(info.qualifiedName())) {
                report(n.pos(), info.name + " is declared twice");
                continue;
            }
            if (outer.typeParams.contains(info.simpleName)) {
                report(n.pos(), outer.name + " already has a type parameter named " + info.simpleName);
            }
            classes.put(info.qualifiedName(), info);
            order.add(info);
            addNested(path, info, isLibrary, order);
        }
    }

    /**
     * The type a name means where {@code from} is written, as in Java (§2.4): the type itself
     * or a type nested in it or in a type enclosing it; then a type the file imports by name;
     * one in the same package (for a file in no package, the program's other types in no
     * package); one a `*` import brings; then seal.lang's, which every file sees, and the
     * built-in string and array. `A.B` is B nested in what A means, or the type B in package A.
     * Null if there is none, or if two `*` imports bring one each ({@link #unknownTypeHint} says
     * which).
     */
    public ClassInfo lookupClass(String name, ClassInfo from) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            ClassInfo head = lookupClass(name.substring(0, dot), from);
            if (head != null) {
                ClassInfo inner = classes.get(head.qualifiedName() + name.substring(dot));
                if (inner == null && head.java != null) {
                    inner = javaClass(head.java.name() + name.substring(dot).replace('.', '$')); // Map.Entry, Java's nested class
                }
                return inner;
            }
            ClassInfo full = classes.get(name); // com.example.Vec2, com.example.Outer.Inner
            if (full == null) {
                full = javaNamed(name); // java.util.ArrayList (docs/JAVA.md §3.2)
            }
            return full != null && !full.pkg.isEmpty() ? full : null;
        }
        for (ClassInfo c = from; c != null; c = c.outer) {
            if (c.simpleName.equals(name)) {
                return c;
            }
            ClassInfo inner = classes.get(c.qualifiedName() + "." + name);
            if (inner != null) {
                return inner;
            }
        }
        String pkg = from == null ? "" : from.pkg;
        List<Import> imports = from == null ? List.of() : from.imports;
        for (Import i : imports) {
            if (!i.all() && i.bound().equals(name) && importedType(i, from) instanceof ClassInfo c) {
                return c;
            }
        }
        if (!pkg.isEmpty() && classes.get(pkg + "." + name) instanceof ClassInfo c) {
            return c;
        }
        // In no package, the program's other types in no package; the library's files there (string, array) see only the library's.
        if (pkg.isEmpty() && classes.get(name) instanceof ClassInfo c && !c.builtin && (from == null || !from.builtin)) {
            return c;
        }
        // A Java class of the same package, as in Java: a program's own .java file's (docs/JAVA.md §11).
        if (from != null && !from.builtin && javaClass(pkg.isEmpty() ? name : pkg + "." + name) instanceof ClassInfo c) {
            return c;
        }
        List<ClassInfo> brought = imported(name, imports);
        if (!brought.isEmpty()) {
            if (brought.size() == 1 && brought.get(0).java != null && classNamed(name) != null) {
                return null; // import java.lang.*; and Math: Seal's or Java's? (docs/JAVA.md §3.2)
            }
            return brought.size() == 1 ? brought.get(0) : null;
        }
        return classNamed(name);
    }

    // ================================================================ Java's classes (docs/JAVA.md)

    /**
     * Whether a type is one of Java's (docs/JAVA.md §3): a class read from its class file (seal.java's
     * JavaString and the others among them), or seal.java's JavaArray, Java's arrays.
     */
    public static boolean isJavaType(ClassInfo c) {
        return c.java != null || isJavaArray(c);
    }

    /** seal.java's JavaArray<T>: Java's T[]. */
    public static boolean isJavaArray(ClassInfo c) {
        return c.builtin && c.java == null && c.pkg.equals("seal.java") && c.name.equals("JavaArray");
    }

    /** seal.java's JavaObject: java.lang.Object, which every Java type extends. */
    public static boolean isJavaObject(ClassInfo c) {
        return c.java != null && c.java.name().equals("java.lang.Object");
    }

    /** The class-file member a Java class's member was made from; null for Seal's (JavaString's `of`). */
    public JavaClasses.JMember javaMember(Member m) {
        return javaMembers.get(m);
    }

    /**
     * A method of a Java interface that a Seal class implements, and the Seal method implementing
     * it (docs/JAVA.md §5): where their JVM descriptors differ (compareTo(Object) and
     * compareTo(Version)), the backend writes a bridge, as javac does.
     */
    public record JavaImpl(JavaClasses.JMember java, ClassT implOwner, MemberInfo impl) {
    }

    /**
     * The methods of Java's that {@code t}'s objects answer with a Seal method of t (or a Seal parent):
     * the abstract methods of the Java interfaces it implements, and the methods of a Java class above
     * it that a Seal method overrides (docs/JAVA.md §5).
     */
    public List<JavaImpl> javaImplementations(ClassT t) {
        List<JavaImpl> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int depth = 0;
        for (ClassT p = parentOf(t); p != null && depth++ < MAX_INSTANTIATION_DEPTH; p = parentOf(p)) {
            if (p.cls().java == null) {
                continue;
            }
            for (MemberInfo m : p.cls().all) {
                if (!(m.member instanceof Method md) || !(javaMembers.get(md) instanceof JavaClasses.JMember jm) || jm.isStatic()
                        || jm.isFinal() || !seen.add(jm.name() + jm.descriptor())) {
                    continue;
                }
                ClassT owner = p;
                Impl impl = quietly(() -> findImplementation(t, md.name(), paramTypes(owner, m, Map.of())));
                if (impl != null && !isJavaType(impl.owner().cls()) && ((Method) impl.member().member).body() != null) {
                    out.add(new JavaImpl(jm, impl.owner(), impl.member()));
                }
            }
        }
        for (ClassT i : allInterfacesOf(t)) {
            if (i.cls().java == null) {
                continue;
            }
            for (MemberInfo m : i.cls().all) {
                if (!(m.member instanceof Method md) || md.modifiers().isStatic() || !(javaMembers.get(md) instanceof JavaClasses.JMember jm)
                        || (jm.flags() & JavaClasses.ABSTRACT) == 0 || isObjectMethod(jm) || !seen.add(jm.name() + jm.descriptor())) {
                    continue;
                }
                Impl impl = quietly(() -> findImplementation(t, md.name(), paramTypes(i, m, Map.of())));
                if (impl != null && !isJavaType(impl.owner().cls()) && ((Method) impl.member().member).body() != null) {
                    out.add(new JavaImpl(jm, impl.owner(), impl.member()));
                }
            }
        }
        return out;
    }

    /** Whether a method is a type's cast to the bare string (§46), which Java's toString() gives (docs/JAVA.md §5). */
    public boolean isStringCast(ClassT owner, MemberInfo m) {
        return m.member instanceof Method md && overloads(md, CAST) && md.params().isEmpty() && !md.modifiers().isStatic()
                && bareString() != null && bareString().equals(quietly(() -> returnTypeOf(owner, m, Map.of())));
    }

    /** Whether a method is a class's == (§48), which Java's equals(Object) gives (docs/JAVA.md §5). */
    public static boolean isEqualsOperator(Method md) {
        return overloads(md, "==") && md.params().size() == 1 && !md.modifiers().isStatic();
    }

    /** An object of a Seal class extending a Java one that has no cast to string of its own: its text is Java's toString(). */
    private boolean javaTextOnly(Type t) {
        if (!(t instanceof HandleT h && h.target() instanceof ClassT ct) || !extendsJava(ct.cls())) {
            return false;
        }
        ClassT owner = operatorOwner(ct, CAST);
        return owner == null || owner.cls().all.stream().noneMatch(m -> isStringCast(owner, m));
    }

    /** Whether a Seal class has a Java class above it (docs/JAVA.md §5): its objects are Java's too. */
    public boolean extendsJava(ClassInfo c) {
        return !isJavaType(c) && c.dynamic && !c.isInterface && inheritsFromJava(c, 0);
    }

    private boolean inheritsFromJava(ClassInfo c, int depth) {
        for (Shape s : c.shapes) {
            for (ExtendsClause e : s.header.extendsClauses()) {
                ClassInfo p = lookupClass(e.parent().name(), c);
                if (p != null && (p.java != null || (depth < MAX_INSTANTIATION_DEPTH && inheritsFromJava(p, depth + 1)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One of Java's values: of one of Java's primitive types, or a reference to a Java object. */
    public static boolean isJavaValue(Type t) {
        return t instanceof JavaPrimT || (t instanceof HandleT h && h.target() instanceof ClassT ct && isJavaType(ct.cls()));
    }

    /**
     * A library class of seal.java that names a Java class (JavaString is java.lang.String): the
     * Java class's members, with the library's added (its `of`), and the Java class's parents.
     */
    private ClassInfo javaBacked(ClassInfo lib) {
        JavaClasses.JClass jc = javaClasses.find(JAVA_NAMES.get(lib.name));
        if (jc == null) {
            return lib;
        }
        ClassDecl java = javaDecl(jc, lib.name);
        List<Member> members = new ArrayList<>(lib.decl.members());
        members.addAll(java.members());
        ClassInfo c = new ClassInfo(lib.file, new ClassDecl(lib.decl.pos(), java.headers(), members), true);
        c.pkg = lib.pkg;
        c.pkgPos = lib.pkgPos;
        c.imports = lib.imports;
        c.java = jc;
        javaByBinary.put(jc.name(), c);
        return c;
    }

    /** seal.java's name for a Java class Seal names its own way (JavaString for java.lang.String), or null. */
    private static String javaSealName(String binary) {
        for (Map.Entry<String, String> e : JAVA_NAMES.entrySet()) {
            if (e.getValue().equals(binary)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The Java class of this binary name (java.util.Map$Entry), read when first asked for; null if there is none. */
    private ClassInfo javaClass(String binary) {
        ClassInfo known = javaByBinary.get(binary);
        if (known != null || notJava.contains(binary)) {
            return known;
        }
        JavaClasses.JClass jc = javaClasses.find(binary);
        if (jc == null) {
            notJava.add(binary);
            return null;
        }
        ClassInfo outer = jc.outerName() == null ? null : javaClass(jc.outerName());
        ClassInfo c = new ClassInfo(JAVA_FILE, javaDecl(jc, jc.simpleName()), true, outer, jc.visible());
        if (outer == null) {
            c.pkg = jc.pkg();
        }
        c.java = jc;
        javaByBinary.put(binary, c);
        classes.putIfAbsent(c.qualifiedName(), c);
        declareJava(c);
        return c;
    }

    /**
     * A Java class by the name Seal writes it with (java.util.Map.Entry, a nested class after its
     * class) or its binary name (java.util.Map$Entry); null if there is none. Without the standard
     * library, which names Java's types, there are none; and none are in Seal's own packages.
     */
    private ClassInfo javaNamed(String name) {
        if (name.equals("seal") || name.startsWith("seal.") || !classes.containsKey("seal.java.JavaObject")) {
            return null;
        }
        String binary = name;
        while (true) {
            ClassInfo c = javaClass(binary);
            if (c != null) {
                return c;
            }
            int dot = binary.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            binary = binary.substring(0, dot) + "$" + binary.substring(dot + 1);
        }
    }

    /**
     * A Java class's members, as declarations made from its class file: its public ones, of their
     * generic types where it has their signatures (docs/JAVA.md §4.1), of their erased types where
     * it has none; and its type parameters, with what each erases to.
     */
    private ClassDecl javaDecl(JavaClasses.JClass jc, String name) {
        Pos p = new Pos(0, 0);
        boolean itf = jc.isInterface();
        java.lang.classfile.ClassSignature cs = null;
        if (jc.signature() != null) {
            try {
                cs = java.lang.classfile.ClassSignature.parseFrom(jc.signature());
            } catch (IllegalArgumentException e) {
                cs = null; // a signature the JDK's parser can't read: the class as erased
            }
        }
        List<ExtendsClause> ext;
        List<TypeRef> itfs;
        List<String> typeParams = List.of();
        if (cs != null) {
            typeParams = cs.typeParameters().stream().map(java.lang.classfile.Signature.TypeParam::identifier).toList();
            ext = jc.superName() == null ? List.of() : List.of(new ExtendsClause(p, null, sigRef(cs.superclassSignature())));
            itfs = cs.superinterfaceSignatures().stream().map(this::sigRef).toList();
        } else {
            ext = jc.superName() == null ? List.of() : List.of(new ExtendsClause(p, null, javaRef(jc.superName())));
            itfs = jc.interfaces().stream().map(this::javaRef).toList();
        }
        ClassHeader h = new ClassHeader(p, itf ? Kind.INTERFACE : Kind.CLASS,
                !itf && (jc.flags() & JavaClasses.ABSTRACT) != 0, name, typeParams, null, ext, itfs);
        if (cs != null) {
            javaErasures.put(h, erasures(cs.typeParameters()));
        }
        List<Member> members = new ArrayList<>();
        for (JavaClasses.JMember m : jc.members()) {
            if ((m.flags() & (JavaClasses.PUBLIC | JavaClasses.PROTECTED)) == 0
                    || (m.flags() & (JavaClasses.SYNTHETIC | JavaClasses.BRIDGE)) != 0 || m.name().equals("<clinit>")) {
                continue; // a protected one is a Seal subclass's alone (javaAccessible)
            }
            Modifiers mods = new Modifiers(List.of(), Access.PUBLIC, List.of(), m.isStatic(), false,
                    (m.flags() & JavaClasses.ABSTRACT) != 0);
            int[] at = {0};
            Member made;
            if (m.isField()) {
                TypeRef type = null;
                if (m.signature() != null) {
                    try {
                        type = sigRef(java.lang.classfile.Signature.parseFrom(m.signature()));
                    } catch (IllegalArgumentException e) {
                        type = null;
                    }
                }
                made = new Field(p, mods, type != null ? type : javaRef(m.descriptor(), at), m.name(), null, null);
            } else {
                at[0] = 1;
                List<Param> params = new ArrayList<>();
                while (m.descriptor().charAt(at[0]) != ')') {
                    params.add(new Param(p, javaRef(m.descriptor(), at), "p" + params.size()));
                }
                at[0]++;
                TypeRef ret = javaRef(m.descriptor(), at);
                List<String> methodTypeParams = List.of();
                Map<String, TypeRef> methodErasures = Map.of();
                java.lang.classfile.MethodSignature ms = null;
                if (m.signature() != null) {
                    try {
                        ms = java.lang.classfile.MethodSignature.parseFrom(m.signature());
                    } catch (IllegalArgumentException e) {
                        ms = null;
                    }
                }
                // An enum's or an inner class's constructor has parameters its signature leaves out: as erased, then.
                if (ms != null && ms.arguments().size() == params.size()) {
                    for (int i = 0; i < params.size(); i++) {
                        params.set(i, new Param(p, sigRef(ms.arguments().get(i)), "p" + i));
                    }
                    ret = sigRef(ms.result());
                    methodTypeParams = ms.typeParameters().stream().map(java.lang.classfile.Signature.TypeParam::identifier).toList();
                    methodErasures = erasures(ms.typeParameters());
                }
                if (m.isConstructor()) {
                    made = new Constructor(p, mods, null, params, null);
                } else {
                    made = new Method(p, mods, methodTypeParams, ret, m.name(), null, params, null);
                    if (!methodTypeParams.isEmpty()) {
                        javaErasures.put(made, methodErasures);
                    }
                }
            }
            javaMembers.put(made, m);
            members.add(made);
        }
        return new ClassDecl(p, List.of(h), members);
    }

    /** What each type parameter erases to: its class bound, or else its first interface bound, as a raw type. */
    private Map<String, TypeRef> erasures(List<java.lang.classfile.Signature.TypeParam> params) {
        Map<String, TypeRef> out = new LinkedHashMap<>();
        for (java.lang.classfile.Signature.TypeParam tp : params) {
            java.lang.classfile.Signature.RefTypeSig bound = tp.classBound().orElse(
                    tp.interfaceBounds().isEmpty() ? null : tp.interfaceBounds().get(0));
            out.put(tp.identifier(), bound instanceof java.lang.classfile.Signature.ClassTypeSig c ? javaRef(classBinary(c))
                    : bound instanceof java.lang.classfile.Signature.TypeVarSig v && out.containsKey(v.identifier())
                    ? out.get(v.identifier()) : javaRef("java.lang.Object"));
        }
        return out;
    }

    /** The Seal type of a Java type from a generic signature: a class with its type arguments, a type variable, a wildcard. */
    private TypeRef sigRef(java.lang.classfile.Signature s) {
        Pos p = new Pos(0, 0);
        return switch (s) {
            case java.lang.classfile.Signature.BaseTypeSig b -> b.baseType() == 'V'
                    ? new TypeRef(p, "void", List.of(), null, List.of())
                    : new TypeRef(p, "seal.java." + new JavaPrimT(b.baseType()), List.of(), null, List.of());
            case java.lang.classfile.Signature.TypeVarSig v -> new TypeRef(p, v.identifier(), List.of(), null, List.of());
            case java.lang.classfile.Signature.ArrayTypeSig a ->
                    new TypeRef(p, "seal.java.JavaArray", List.of(sigRef(a.componentSignature())), null, List.of());
            case java.lang.classfile.Signature.ClassTypeSig c -> new TypeRef(p, javaRef(classBinary(c)).name(),
                    c.typeArgs().stream().map(this::argRef).toList(), null, List.of());
        };
    }

    /** A class in a signature by its binary name: java.util.Map$Entry for Map's Entry. */
    private static String classBinary(java.lang.classfile.Signature.ClassTypeSig c) {
        return c.outerType().map(o -> classBinary(o) + "$" + c.className()).orElse(c.className().replace('/', '.'));
    }

    /** A type argument: a type, or a wildcard, written ?, ?extends or ?super, which only Java's types have. */
    private TypeRef argRef(java.lang.classfile.Signature.TypeArg a) {
        Pos p = new Pos(0, 0);
        if (!(a instanceof java.lang.classfile.Signature.TypeArg.Bounded b)) {
            return new TypeRef(p, "?", List.of(), null, List.of());
        }
        return switch (b.wildcardIndicator()) {
            case NONE -> sigRef(b.boundType());
            case EXTENDS -> new TypeRef(p, "?extends", List.of(sigRef(b.boundType())), null, List.of());
            case SUPER -> new TypeRef(p, "?super", List.of(sigRef(b.boundType())), null, List.of());
        };
    }

    /** The Seal type of the Java type a descriptor names from {@code at[0]}, which moves past it. */
    private TypeRef javaRef(String desc, int[] at) {
        Pos p = new Pos(0, 0);
        char ch = desc.charAt(at[0]++);
        return switch (ch) {
            case 'V' -> new TypeRef(p, "void", List.of(), null, List.of());
            case '[' -> new TypeRef(p, "seal.java.JavaArray", List.of(javaRef(desc, at)), null, List.of());
            case 'L' -> {
                int end = desc.indexOf(';', at[0]);
                String binary = desc.substring(at[0], end).replace('/', '.');
                at[0] = end + 1;
                yield javaRef(binary);
            }
            default -> new TypeRef(p, "seal.java." + new JavaPrimT(ch), List.of(), null, List.of());
        };
    }

    /** A Java class as a type, by its binary name; seal.java's name for one Seal names its own way. */
    private TypeRef javaRef(String binary) {
        String seal = javaSealName(binary);
        return new TypeRef(new Pos(0, 0), seal != null ? "seal.java." + seal : binary, List.of(), null, List.of());
    }

    /**
     * Why a type can't be a Java generic's type argument, or null if it can: Java's generics hold
     * objects (docs/JAVA.md §4.1), Java's and Seal's classes', not Java's primitives or Seal's values.
     */
    private String javaTypeArgument(Type t) {
        if (t instanceof HandleT || t instanceof ParamT || t instanceof WildT || t.isError()) {
            return null;
        }
        if (t instanceof JavaPrimT j) {
            return "Java's generics hold objects, so a type argument is " + j + "Boxed (an " + boxName(j) + "), not " + j;
        }
        String of = javaOf(t);
        if (of != null && of.startsWith("JavaString")) {
            return "Java's generics hold Java's objects, so Seal's " + t + " is JavaString here";
        }
        JavaPrimT twin = unboxedAs(t);
        return "Java's generics hold objects, so a type argument is one of Java's classes or Seal's, not " + t
                + (twin != null ? "; Java's box of it is " + twin + "Boxed" : "");
    }

    /** java.lang.Integer for JavaInt. */
    private static String boxName(JavaPrimT j) {
        return switch (j.code()) {
            case 'I' -> "Integer";
            case 'C' -> "Character";
            default -> Character.toUpperCase(j.javaName().charAt(0)) + j.javaName().substring(1);
        };
    }

    /** seal.java's JavaObject as a value's type: java.lang.Object. */
    private Type javaObjectHandle() {
        ClassInfo o = classes.get("seal.java.JavaObject");
        return o == null ? Type.ERROR : new HandleT(new ClassT(o, List.of(), List.of(), 0));
    }

    /** A Java class's one shape and its members, as declareHeaders and declareMembers give a Seal class's. */
    private void declareJava(ClassInfo c) {
        if (!c.shapes.isEmpty()) {
            return;
        }
        c.shapes.add(new Shape(0, c.decl.headers().get(0), List.of()));
        for (Member m : c.decl.members()) {
            MemberInfo info = new MemberInfo(m);
            c.all.add(info);
            declaringCache.put(info, c);
            if (m instanceof Constructor) {
                c.constructors.add(info);
            } else {
                c.members.computeIfAbsent(info.name(), k -> new ArrayList<>()).add(info);
            }
        }
    }

    /**
     * The type an import by name brings: one in a package, or with `as`, one in no package too (a
     * library type renamed, `import HashMap as SealHashMap;`); null if none.
     */
    private ClassInfo importedType(Import i, ClassInfo from) {
        ClassInfo t = classes.containsKey(i.name()) || i.isStatic() ? classes.get(i.name()) : javaNamed(i.name());
        if (t == null || (t.pkg.isEmpty() && (i.alias() == null || i.isStatic() || (!t.builtin && from != null && !from.pkg.isEmpty())))) {
            return null;
        }
        return t;
    }

    /** A static member and the type it is in. */
    private record StaticMember(ClassInfo cls, MemberInfo m) {
    }

    /**
     * The static members named {@code name} (methods, fields and constants) that the static
     * imports of {@code from}'s file bring, as in Java: those of the imports that name it, or
     * if none do, those of the `import static T.*;` ones. Private ones aren't brought.
     */
    private List<StaticMember> staticImports(ClassInfo from, String name) {
        if (from == null) {
            return List.of();
        }
        List<StaticMember> named = new ArrayList<>();
        List<StaticMember> all = new ArrayList<>();
        for (Import i : from.imports) {
            if (!i.isStatic()) {
                continue;
            }
            String type = i.all() ? i.name() : i.name().substring(0, i.name().lastIndexOf('.'));
            if (!i.all() && !i.bound().equals(name)) {
                continue;
            }
            ClassInfo c = classes.containsKey(type) ? classes.get(type) : javaNamed(type);
            if (c == null || (c.pkg.isEmpty() && !c.builtin && !from.pkg.isEmpty())) {
                continue; // reported by checkImports
            }
            String member = i.all() ? name : i.name().substring(i.name().lastIndexOf('.') + 1); // max, for maximum
            for (MemberInfo m : c.members.getOrDefault(member, List.of())) {
                boolean isStatic = m.member.modifiers().isStatic() || (m.member instanceof Field f && f.modifiers().isConst());
                if (isStatic && !m.guarded && canAccess(from, c, m.member.modifiers())) {
                    (i.all() ? all : named).add(new StaticMember(c, m));
                }
            }
        }
        return named.isEmpty() ? all : named;
    }

    /** For an unknown method: the library types with a static method of that name, to call or import. */
    private String staticMethodHint(String name) {
        List<String> owners = classes.values().stream().filter(c -> c.builtin && c.outer == null && c.java == null
                && c.members.getOrDefault(name, List.of()).stream().anyMatch(m -> m.member instanceof Method md
                        && md.modifiers().isStatic() && md.modifiers().access() == Access.PUBLIC))
                .map(c -> c.name).sorted(java.util.Comparator.comparing((String n) -> !n.equals("Math")) // the general one first
                        .thenComparing(n -> n)).toList();
        if (owners.isEmpty()) {
            return "";
        }
        String list = owners.size() == 1 ? owners.get(0)
                : String.join(", ", owners.subList(0, owners.size() - 1)) + " and " + owners.get(owners.size() - 1);
        ClassInfo first = classes.values().stream().filter(c -> c.builtin && c.outer == null && c.java == null
                        && c.name.equals(owners.get(0)))
                .findFirst().orElseThrow();
        String imported = first.pkg.isEmpty() || first.pkg.equals(LANG) ? "" : " (with import " + first.qualifiedName() + ";)";
        return "; it is a static method of " + list + ": call " + owners.get(0) + "." + name + "(...)" + imported
                + ", or import static " + first.qualifiedName() + "." + name + ";";
    }

    /** The types named {@code name} that `*` imports bring. */
    private List<ClassInfo> imported(String name, List<Import> imports) {
        List<ClassInfo> found = new ArrayList<>();
        for (Import i : imports) {
            if (!i.all()) {
                continue;
            }
            ClassInfo c = classes.get(i.name() + "." + name);
            if (c == null && javaNamed(i.name() + "." + name) instanceof ClassInfo j && j.java.visible()
                    && !j.pkg.equals("seal.java")) {
                c = j; // a Java class, but not java.lang.String, which is JavaString (docs/JAVA.md §3.2)
            }
            if (c != null && !found.contains(c)) {
                found.add(c);
            }
        }
        return found;
    }

    /** "; this file imports Math.max as biggest": when the name is one the file imported under another (§2.4). */
    private static String renamedHint(ClassInfo from, String name, boolean member) {
        if (from == null) {
            return "";
        }
        for (Import i : from.imports) {
            boolean original = i.name().endsWith("." + name) || (!member && i.name().equals(name)); // HashMap as SealHashMap
            if (i.alias() != null && i.isStatic() == member && original) {
                return "; this file imports " + i.name() + " as " + i.alias();
            }
        }
        return "";
    }

    /**
     * Why Seal's code can't use a class of one of the program's .java files, if it is one compiled
     * after Seal's code, as it uses it (docs/JAVA.md §11); empty otherwise.
     */
    private String afterSealHint(String binary) {
        String file = javaClasses.afterSealFile(binary);
        return file == null ? "" : "; " + binary + " is in " + file + ", which uses Seal's classes, so it is compiled"
                + " after them, and Seal's code can't use it (docs/JAVA.md §11)";
    }

    /** Why a type name means nothing where {@code from} is written, when there is more to say, as "; ...". */
    private String unknownTypeHint(String name, ClassInfo from) {
        if (name.indexOf('.') >= 0) {
            return afterSealHint(name);
        }
        if (from != null) {
            List<String> where = new ArrayList<>(List.of(from.pkg.isEmpty() ? name : from.pkg + "." + name));
            from.imports.stream().filter(i -> i.all() && !i.isStatic()).forEach(i -> where.add(i.name() + "." + name));
            for (String binary : where) {
                if (!afterSealHint(binary).isEmpty()) {
                    return afterSealHint(binary);
                }
            }
        }
        String renamed = renamedHint(from, name, false);
        if (renamed.isEmpty()) {
            renamed = renamedHint(from, name, true); // a static field imported as another name
        }
        if (!renamed.isEmpty()) {
            return renamed;
        }
        List<ClassInfo> brought = imported(name, from == null ? List.of() : from.imports);
        if (brought.size() == 1 && brought.get(0).java != null && classNamed(name) != null) {
            return ": it could be " + brought.get(0).qualifiedName() + ", which a * import brings, or "
                    + classNamed(name).qualifiedName() + "; import the one you mean by name, as in import "
                    + brought.get(0).qualifiedName() + ";";
        }
        for (String javaName : List.of("Object", "String", "Integer", "Long", "Short", "Byte", "Float", "Double",
                "Character", "Boolean")) {
            if (name.equals(javaName) && classNamed(name) == null) {
                return "; Java's java.lang." + name + " is " + javaSealName("java.lang." + name) + " (import seal.java.*;)";
            }
        }
        if (brought.size() > 1) {
            return ": it could be " + brought.stream().map(ClassInfo::qualifiedName).collect(Collectors.joining(" or "))
                    + ", which * imports bring; import the one you mean by name, as in import "
                    + brought.get(0).qualifiedName() + ";";
        }
        ClassInfo outside = classes.get(name);
        if (outside != null && !outside.builtin && from != null && !from.pkg.isEmpty()) {
            return "; " + name + " is in no package, and as in Java, a type in a package can't use one that isn't";
        }
        List<String> elsewhere = classes.values().stream()
                .filter(c -> c.simpleName.equals(name) && !c.pkg.isEmpty() && (from == null || !c.pkg.equals(from.pkg)))
                .map(ClassInfo::qualifiedName).distinct().sorted().toList();
        if (!elsewhere.isEmpty()) {
            return "; import it: import " + String.join("; or import ", elsewhere) + ";";
        }
        return "";
    }

    /** The full names of the types in packages named {@code simple}: [seal.lang.Math] for Math. */
    private List<String> inPackages(String simple) {
        return classes.values().stream().filter(c -> c.outer == null && c.simpleName.equals(simple) && !c.pkg.isEmpty())
                .map(ClassInfo::qualifiedName).distinct().sorted().toList();
    }

    /** `import static T.member;` needs a static member (or nested type) of T so named; `import static T.*;` a type T. */
    private void checkStaticImport(ClassInfo c, Import i) {
        String type = i.all() ? i.name() : i.name().substring(0, i.name().lastIndexOf('.'));
        ClassInfo t = classes.containsKey(type) ? classes.get(type) : javaNamed(type);
        if (t == null || (t.pkg.isEmpty() && !t.builtin && !c.pkg.isEmpty())) {
            List<String> full = type.indexOf('.') < 0 ? inPackages(type) : List.of();
            report(i.pos(), "no type " + type + " to import from" + (t != null ? "; " + type
                    + " is in no package, and as in Java, a type in a package can't use one that isn't"
                    : !full.isEmpty() ? "; an import names a type in full, as in import static " + full.get(0)
                    + i.name().substring(type.length()) + (i.all() ? ".*" : "") + ";" : unknownTypeHint(type, c)));
            return;
        }
        if (!canSeeType(c, t)) {
            report(i.pos(), t.name + " is private to " + t.outer.name);
            return;
        }
        if (t.java != null && !t.java.visible()) {
            report(i.pos(), "Java's " + t.java.name() + " isn't public, so a program can't use it");
            return;
        }
        if (i.all()) {
            return;
        }
        String member = i.name().substring(i.name().lastIndexOf('.') + 1);
        if (classes.get(type + "." + member) != null) {
            return; // a nested type
        }
        List<MemberInfo> found = t.members.getOrDefault(member, List.of());
        if (found.isEmpty()) {
            report(i.pos(), t.name + " has no static member " + member);
        } else if (found.stream().noneMatch(m -> m.member.modifiers().isStatic()
                || (m.member instanceof Field f && f.modifiers().isConst()))) {
            report(i.pos(), member + " of " + t.name + " is not static, so it can't be imported; it needs an object");
        } else if (found.stream().noneMatch(m -> canAccess(c, t, m.member.modifiers()))) {
            report(i.pos(), member + " of " + t.name + " is private");
        }
    }

    /** Each import must name a type, or with *, a package or type, that exists; two can't bring one name twice. */
    private void checkImports(ClassInfo c) {
        Map<String, ClassInfo> named = new HashMap<>();
        for (Import i : c.imports) {
            if (i.isStatic()) {
                checkStaticImport(c, i);
                continue;
            }
            if (i.all()) {
                boolean exists = classes.values().stream().anyMatch(k -> k.pkg.equals(i.name()) || k.qualifiedName().equals(i.name()))
                        || (!i.name().startsWith("seal.") && javaClasses.hasPackage(i.name()));
                if (!exists) {
                    report(i.pos(), "no package " + i.name() + " to import from");
                }
                continue;
            }
            ClassInfo t = importedType(i, c);
            String simple = i.name().substring(i.name().lastIndexOf('.') + 1);
            String bound = i.bound();
            String what = "import " + i.name() + (i.alias() == null ? "" : " as " + i.alias());
            if (t == null) {
                ClassInfo outside = classes.get(i.name());
                report(i.pos(), "no type " + i.name() + " to import" + (!afterSealHint(i.name()).isEmpty() ? afterSealHint(i.name())
                        : outside != null && !outside.builtin && outside.pkg.isEmpty() && !c.pkg.isEmpty()
                                ? "; " + i.name() + " is in no package, and as in Java, a type in a package can't use one that isn't"
                                : unknownTypeHint(simple, c)));
            } else if (!canSeeType(c, t)) {
                report(i.pos(), t.name + " is private to " + t.outer.name);
            } else if (t.java != null && !t.java.visible()) {
                report(i.pos(), "Java's " + t.java.name() + " isn't public, so a program can't use it");
            } else if (t.java != null && t.pkg.equals("seal.java") && !i.name().startsWith("seal.")) {
                report(i.pos(), "Java's " + t.java.name() + " is " + t.name + " in Seal (docs/JAVA.md §3.1): import seal.java."
                        + t.name + ";");
            } else if (bound.equals(c.simpleName) && t != c) {
                report(i.pos(), what + " clashes with " + c.qualifiedName() + ", declared in this file");
            } else if (named.containsKey(bound) && named.get(bound) != t) {
                ClassInfo other = named.get(bound);
                report(i.pos(), what + " clashes with the import of " + other.qualifiedName()
                        + (other.simpleName.equals(bound) ? "" : " as " + bound));
            } else {
                named.put(bound, t);
            }
        }
    }

    /** Whether code in {@code from} may use the type {@code c}: a private nested type only inside its outer type. */
    private boolean canSeeType(ClassInfo from, ClassInfo c) {
        for (ClassInfo t = c; t.outer != null; t = t.outer) {
            if (!t.isPublic && (from == null || from.topLevel() != t.topLevel())) {
                return false;
            }
        }
        return true;
    }

    /** Reports, and returns null for, a type `from` may not use. */
    private ClassInfo visibleClass(ClassInfo c, ClassInfo from, Pos pos) {
        if (c != null && !canSeeType(from, c)) {
            report(pos, c.name + " is private to " + c.outer.name + "; make it public to use it outside");
            return null;
        }
        return c;
    }

    private ClassInfo load(String path, String source, boolean builtin) {
        Parser.Result r = Parser.parse(source);
        for (ParseError e : r.errors()) {
            diagnostics.add(new Diagnostic(path, e.line(), e.column(), e.message()));
        }
        if (r.ast().headers().isEmpty()) {
            return null;
        }
        ClassInfo info = new ClassInfo(path, r.ast(), builtin);
        if (r.pkg() != null) {
            info.pkg = r.pkg();
            info.pkgPos = r.pkgPos();
        }
        info.imports = r.imports();
        return info;
    }

    /** The folders a path is in, outermost first: a/b/C.seal is [a, b]. */
    private static List<String> folders(String path) {
        List<String> parts = new ArrayList<>(List.of(path.split("[/\\\\]")));
        parts.remove(parts.size() - 1);
        parts.removeIf(p -> p.isEmpty() || p.equals("."));
        return parts;
    }

    private static String baseName(String path) {
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    // ================================================================ declarations: headers

    private void declareHeaders(ClassInfo c) {
        List<ClassHeader> headers = c.decl.headers();
        for (int i = 0; i < headers.size(); i++) {
            ClassHeader h = headers.get(i);
            if (!h.name().equals(c.simpleName)) {
                report(h.pos(), "all headers must name the same class: expected " + c.simpleName + ", found " + h.name());
            }
            if (h.kind() != c.kind) {
                report(h.pos(), "every header of " + c.name + " must use the same keyword: " + c.kind.keyword
                        + ", not " + h.kind().keyword);
            }
            if (h.isAbstract() && c.kind != Kind.CLASS) {
                report(h.pos(), h.kind() == Kind.INTERFACE ? "interfaces are always abstract; leave out `abstract`"
                        : "only classes can be abstract, not a " + h.kind().keyword);
            } else if (h.isAbstract() != c.isAbstract && c.kind == Kind.CLASS) {
                report(h.pos(), "every header of " + c.name + " must say `abstract`, or none");
            }
            if (!h.interfaces().isEmpty() && (c.kind == Kind.STRUCT || c.kind == Kind.UNIQUE)) {
                report(h.pos(), "only classes can implement interfaces; a " + c.kind.keyword
                        + " uses composition and generics instead (§30)");
            }
            if (c.kind == Kind.INTERFACE && (h.valueParams() != null || headers.size() > 1)) {
                report(h.pos(), "an interface has a single shape; it can't have value parameters <<...>>");
            }
            if (!h.typeParams().equals(c.typeParams)) {
                report(h.pos(), "every header of " + c.name + " must have the same type parameters <"
                        + String.join(", ", c.typeParams) + ">");
            }
            if (new HashSet<>(h.typeParams()).size() != h.typeParams().size()) {
                report(h.pos(), "duplicate type parameter name");
            }

            List<Binder> binders = new ArrayList<>();
            if (h.valueParams() != null) {
                for (ValueItem item : h.valueParams().items()) {
                    if (item instanceof Binder b) {
                        binders.add(b);
                    } else {
                        report(item.pos(), "type headers declare values, e.g. <<int N>>; a fixed value is not allowed here");
                    }
                }
            }
            Shape shape = new Shape(i, h, binders);
            Set<String> names = new HashSet<>();
            for (Binder b : binders) {
                if (!names.add(b.name())) {
                    report(b.pos(), "duplicate value parameter " + b.name());
                }
                if (c.typeParams.contains(b.name())) {
                    report(b.pos(), "value parameter " + b.name() + " has the same name as a type parameter");
                }
                shape.binderTypes.add(resolveBinderType(b.type(), c));
            }
            for (Shape other : c.shapes) {
                if (other.binderTypes.equals(shape.binderTypes)) {
                    report(h.pos(), "duplicate shape: " + describeShape(c, other.index) + " is already declared");
                }
            }
            c.shapes.add(shape);
        }
    }

    /** Types allowed for compile-time values (§8). `string` means a compile-time string. */
    private Type resolveBinderType(TypeRef ref, ClassInfo c) {
        if (ref.name().equals("string") && ref.typeArgs().isEmpty() && ref.valueArgs() == null
                && ref.arrayDims().isEmpty()) {
            return new StrConstT(null);
        }
        Type t = resolveType(ref, Env.empty(c), Opts.PLAIN);
        if (t instanceof IntT i && !i.width().isConstant()) {
            report(ref.pos(), "the width of a value parameter's type must be a constant");
        } else if (!(t instanceof IntT || t instanceof BitT || t instanceof FloatT || t instanceof DoubleT
                || t.isError())) {
            report(ref.pos(), "compile-time values must be integers, bit, float, double or string, not " + t);
            return Type.ERROR;
        }
        return t;
    }

    private String describeShape(ClassInfo c, int shape) {
        StringBuilder sb = new StringBuilder(c.name);
        if (!c.typeParams.isEmpty()) {
            sb.append("<").append(String.join(", ", c.typeParams)).append(">");
        }
        if (shape < 0 || shape >= c.shapes.size()) {
            return sb.toString();
        }
        Shape s = c.shapes.get(shape);
        sb.append("<<");
        for (int i = 0; i < s.binders.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(typeName(s.binderTypes.get(i))).append(' ').append(s.binders.get(i).name());
        }
        return sb.append(">>").toString();
    }

    private static String typeName(Type t) {
        return t instanceof StrConstT ? "string" : t.toString();
    }

    // ================================================================ declarations: members

    private void declareMembers(ClassInfo c) {
        List<Member> members = new ArrayList<>(c.decl.members());
        members.addAll(c.synthesized);
        for (Member m : members) {
            MemberInfo info = new MemberInfo(m);
            c.all.add(info);
            ValueList guard = info.guard();
            if (guard != null) {
                info.shape = matchGuard(c, guard);
            }
            switch (m) {
                case Constructor ctor -> c.constructors.add(info);
                case Destructor d -> c.destructors.add(info);
                default -> c.members.computeIfAbsent(info.name(), k -> new ArrayList<>()).add(info);
            }
            if (m.modifiers().isStatic() && guard != null) {
                report(m.pos(), "static members cannot have a shape guard");
            }
            if (m.modifiers().isStatic() && (m instanceof Constructor || m instanceof Destructor)) {
                report(m.pos(), "constructors and destructors cannot be static");
            }
            if (m.modifiers().isConst() && !(m instanceof Field)) {
                report(m.pos(), "only fields can be const");
            }
            if (m.modifiers().isPure() && !(m instanceof Method)) {
                report(m.pos(), "only methods can be pure");
            } else if (m.modifiers().isPure() && !m.modifiers().isStatic()) {
                report(m.pos(), "a pure method must be static: it depends only on its arguments, not on an object;"
                        + " write `static pure`");
            }
            if (m instanceof Field f && f.modifiers().isConst() && f.init() == null) {
                report(f.pos(), "const field " + f.name() + " needs an initializer");
            }
            if (m instanceof Field f && !f.modifiers().decorators().isEmpty()) {
                report(f.pos(), "decorators apply to methods and constructors only");
            }
            if (m instanceof Constructor k) {
                for (Decorator d : k.modifiers().decorators()) {
                    if (!d.name().equals("implicit")) {
                        report(d.pos(), "unknown decorator @" + d.name() + " on a constructor; only @implicit applies");
                    } else if (!c.builtin) {
                        report(d.pos(), "@implicit is for the standard library's string only: an object is made with new,"
                                + " as in new " + c.name + "(...)");
                    } else if (!d.args().isEmpty()) {
                        report(d.pos(), "@implicit takes no arguments");
                    } else if (k.params().size() != 1) {
                        report(d.pos(), "an @implicit constructor converts one value, so it takes exactly one parameter");
                    } else if (c.dynamic) {
                        report(d.pos(), "only structs and uniques can have @implicit constructors; a class object"
                                + " would be created without `new`");
                    }
                }
            }
            for (TypeRef friend : m.modifiers().friends()) {
                if (lookupClass(friend.name(), c) == null) {
                    report(friend.pos(), "unknown type " + friend.name() + " in private<...>" + unknownTypeHint(friend.name(), c));
                }
            }
        }

        // All-or-nothing guarding (§11), and one kind of member per name.
        for (Map.Entry<String, List<MemberInfo>> e : c.members.entrySet()) {
            List<MemberInfo> list = e.getValue();
            boolean anyGuarded = list.stream().anyMatch(x -> x.guarded);
            boolean anyUnguarded = list.stream().anyMatch(x -> !x.guarded);
            if (anyGuarded && anyUnguarded) {
                MemberInfo first = list.stream().filter(x -> x.guarded != list.get(0).guarded).findFirst().orElseThrow();
                report(first.member.pos(), "`" + e.getKey() + "` is used both with and without a shape guard; "
                        + "a name must be entirely guarded or entirely unguarded");
            }
            boolean anyField = list.stream().anyMatch(x -> x.member instanceof Field);
            boolean anyMethod = list.stream().anyMatch(x -> x.member instanceof Method);
            if (anyField && anyMethod) {
                MemberInfo second = list.stream().filter(x -> x.member instanceof Method).findFirst().orElseThrow();
                report(second.member.pos(), "`" + e.getKey() + "` is already a field; a method cannot share its name");
            }
            if (anyField) {
                // At most one field per shape.
                for (int i = 0; i < list.size(); i++) {
                    for (int j = 0; j < i; j++) {
                        MemberInfo a = list.get(j);
                        MemberInfo b = list.get(i);
                        if (a.member instanceof Field && b.member instanceof Field && overlap(c, a, b)) {
                            report(b.member.pos(), "field `" + e.getKey() + "` is already declared for this shape");
                        }
                    }
                }
            }
        }
    }

    /** Whether two members exist in at least one common shape. */
    private static boolean overlap(ClassInfo c, MemberInfo a, MemberInfo b) {
        for (int s = 0; s < c.shapes.size(); s++) {
            if (a.existsIn(s) && b.existsIn(s)) {
                return true;
            }
        }
        return c.shapes.isEmpty();
    }

    /** Returns the index of the shape a member guard selects, or -1 (reported). */
    private int matchGuard(ClassInfo c, ValueList guard) {
        List<Type> types = new ArrayList<>();
        for (ValueItem item : guard.items()) {
            if (item instanceof Binder b) {
                types.add(resolveBinderType(b.type(), c));
            } else {
                report(item.pos(), "member guards declare values, e.g. <<int N>>; a fixed value is not allowed here");
                return -1;
            }
        }
        for (Shape s : c.shapes) {
            if (s.binderTypes.equals(types)) {
                return s.index;
            }
        }
        String got = "<<" + types.stream().map(Checker::typeName).collect(Collectors.joining(", ")) + ">>";
        report(guard.pos(), "guard " + got + " matches no header of " + c.name + "; its shapes are "
                + allShapes(c));
        return -1;
    }

    private String allShapes(ClassInfo c) {
        List<String> out = new ArrayList<>();
        for (Shape s : c.shapes) {
            out.add(describeShape(c, s.index));
        }
        return String.join(", ", out);
    }

    // ================================================================ declarations: inheritance

    private void checkExtends(ClassInfo c) {
        for (Shape s : c.shapes) {
            List<ExtendsClause> clauses = s.header.extendsClauses();
            if (clauses.isEmpty()) {
                continue;
            }
            if (!c.dynamic) {
                report(clauses.get(0).pos(), "only classes can use extends; a " + c.kind.keyword
                        + " uses composition instead (§30)");
                continue;
            }
            int unguarded = 0;
            Set<String> seenGuards = new HashSet<>();
            int bindingGuards = 0;
            for (ExtendsClause e : clauses) {
                Env env = shapeEnv(c, s.index, null);
                if (e.guard() == null) {
                    if (++unguarded == 2) {
                        report(e.pos(), "a header can have only one unguarded extends");
                    }
                } else {
                    if (e.guard().items().size() != s.binders.size()) {
                        report(e.guard().pos(), "extends guard must give " + s.binders.size()
                                + " value(s) to match " + describeShape(c, s.index));
                        continue;
                    }
                    boolean binding = e.guard().items().stream().allMatch(x -> x instanceof Binder);
                    boolean literal = e.guard().items().stream().noneMatch(x -> x instanceof Binder);
                    if (!binding && !literal) {
                        report(e.guard().pos(), "an extends guard is either all values, e.g. <<0>>, or all binders, e.g. <<int n>>");
                        continue;
                    }
                    List<String> key = new ArrayList<>();
                    for (int i = 0; i < e.guard().items().size(); i++) {
                        ValueItem item = e.guard().items().get(i);
                        if (item instanceof Binder b) {
                            Type bt = resolveBinderType(b.type(), c);
                            if (!bt.equals(s.binderTypes.get(i)) && !bt.isError()) {
                                report(b.pos(), "binder type " + typeName(bt) + " does not match "
                                        + typeName(s.binderTypes.get(i)) + " in " + describeShape(c, s.index));
                            }
                            key.add("*");
                        } else {
                            Const v = evalConst(((ValueExpr) item).expr(), Env.empty(c));
                            if (!v.value().isConstant() && !(v.value() instanceof ErrV)) {
                                report(item.pos(), "extends guard values must be constants");
                            } else if (!constFits(v, s.binderTypes.get(i))) {
                                report(item.pos(), v.value() + " is not a valid " + typeName(s.binderTypes.get(i)));
                            }
                            key.add(v.value().toString());
                        }
                    }
                    if (binding && ++bindingGuards == 2) {
                        report(e.pos(), "at most one binding guard may match a header");
                    }
                    if (!seenGuards.add(String.join(",", key))) {
                        report(e.pos(), "duplicate extends guard");
                    }
                    if (binding) {
                        for (int i = 0; i < s.binders.size(); i++) {
                            Binder b = (Binder) e.guard().items().get(i);
                            env.values.put(b.name(), env.values.get(s.binders.get(i).name()));
                        }
                    }
                }
                Type parent = resolveType(e.parent(), env, new Opts(false, true, false, null));
                if (parent instanceof ClassT pc && pc.cls().java != null && !pc.cls().isInterface) {
                    checkJavaParent(c, pc, e.parent().pos()); // a Java class (docs/JAVA.md §5)
                } else if (parent instanceof ClassT pc && isJavaArray(pc.cls())) {
                    report(e.parent().pos(), "a Java array's class can't be extended");
                } else if (parent instanceof ClassT pc && pc.cls().isInterface) {
                    report(e.parent().pos(), pc.cls().name + " is an interface; use implements(" + pc.cls().name
                            + ") instead of extends");
                } else if (parent instanceof ClassT pc && !pc.cls().dynamic) {
                    report(e.parent().pos(), "cannot extend " + pc.cls().kind.keyword + " " + pc.cls().name
                            + "; parents must be classes");
                } else if (!(parent instanceof ClassT) && !parent.isError()) {
                    report(e.parent().pos(), "cannot extend " + parent + "; parents must be classes");
                }
            }
        }
    }

    /** Reports classes that (by name) inherit from themselves. */
    private void checkInheritanceCycles(List<ClassInfo> order) {
        for (ClassInfo c : order) {
            file = c.file;
            Deque<String> todo = new ArrayDeque<>(parentNames(c));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String n = todo.pop();
                ClassInfo p = lookupClass(n, c);
                if (p == c) {
                    report(c.decl.pos(), "class " + c.name + " inherits from itself");
                    break;
                }
                if (p != null && seen.add(n)) {
                    todo.addAll(parentNames(p));
                }
            }
        }
    }

    private static List<String> parentNames(ClassInfo c) {
        List<String> out = new ArrayList<>();
        for (Shape s : c.shapes) {
            for (ExtendsClause e : s.header.extendsClauses()) {
                out.add(e.parent().name());
            }
            for (TypeRef i : s.header.interfaces()) {
                out.add(i.name());
            }
        }
        return out;
    }

    /** A fixed class cannot contain itself inline, directly or through other classes (§21). */
    private void checkInlineCycles(List<ClassInfo> order) {
        for (ClassInfo c : order) {
            if (c.dynamic) {
                continue;
            }
            file = c.file;
            Deque<ClassInfo> todo = new ArrayDeque<>(inlineClasses(c));
            Set<ClassInfo> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                ClassInfo other = todo.pop();
                if (other == c) {
                    report(c.decl.pos(), c.kind.keyword + " " + c.name + " contains itself inline; make it a class, or use addr<" + c.name + ">");
                    break;
                }
                if (!other.dynamic && seen.add(other)) {
                    todo.addAll(inlineClasses(other));
                }
            }
        }
    }

    /** The classes a type's instance fields hold inline (not through addr or handle), each name read in its own file. */
    private List<ClassInfo> inlineClasses(ClassInfo c) {
        List<ClassInfo> out = new ArrayList<>();
        for (MemberInfo m : c.all) {
            if (m.member instanceof Field f && !f.modifiers().isStatic()) {
                switch (f.type().name()) {
                    case "addr", "handle", "int", "uint", "bit", "float", "double", "void" -> {
                    }
                    default -> {
                        if (lookupClass(f.type().name(), c) instanceof ClassInfo held) {
                            out.add(held);
                        }
                    }
                }
            }
        }
        return out;
    }

    // ================================================================ declarations: signatures

    private void checkSignatures(ClassInfo c) {
        // Resolve every field and method signature in each shape it exists in.
        Map<String, List<String>> overloadKeys = new HashMap<>();
        for (MemberInfo m : c.all) {
            List<Integer> shapes = shapesOf(c, m);
            for (int s : shapes) {
                Env env = shapeEnv(c, s, m);
                switch (m.member) {
                    case Field f -> {
                        Type t = resolveType(f.type(), env, new Opts(false, false, c.dynamic, null));
                        if (f.init() == null) {
                            requireDefaultConstructible(t, f.pos(), "field " + f.name());
                        }
                        if (!c.dynamic && !c.unique && isUnique(t)) {
                            report(f.pos(), "struct " + c.name + " cannot contain " + t + ", which is unique: copying "
                                    + c.name + " would copy it; declare `unique " + c.name + "` instead");
                        }
                        if (c.dynamic && !c.isInterface && !f.modifiers().isStatic()) {
                            requireNoDestructor(f.pos(), "class " + c.name, f.name(), t);
                        }
                    }
                    case Method md -> {
                        Env menv = env.copy();
                        addMethodTypes(menv, m, null);
                        Set<String> typeNames = new HashSet<>();
                        for (String t : md.typeParams()) {
                            if (!typeNames.add(t)) {
                                report(md.pos(), "duplicate type parameter " + t);
                            } else if (c.typeParams.contains(t)) {
                                report(md.pos(), "type parameter " + t + " of " + md.name() + " hides " + c.name + "'s own " + t);
                            }
                        }
                        if (!md.typeParams().isEmpty() && (md.body() == null || c.isInterface)) {
                            report(md.pos(), "a generic method can't be abstract or in an interface: each use makes its own copy,"
                                    + " so it can't be overridden");
                        }
                        Map<String, Type> binders = new LinkedHashMap<>();
                        List<String> paramTypes = new ArrayList<>();
                        Set<String> paramNames = new HashSet<>();
                        for (Param p : md.params()) {
                            paramTypes.add(resolveType(p.type(), menv, new Opts(false, false, false, binders)).toString());
                            if (!paramNames.add(p.name())) {
                                report(p.pos(), "duplicate parameter " + p.name());
                            }
                        }
                        resolveType(md.returnType(), menv, new Opts(true, false, false, null));
                        String key = s + "|" + md.name() + "(" + String.join(", ", paramTypes) + ")";
                        List<String> keys = overloadKeys.computeIfAbsent(md.name(), k -> new ArrayList<>());
                        if (keys.contains(key)) {
                            report(md.pos(), "method " + md.name() + "(" + String.join(", ", paramTypes)
                                    + ") is already declared for " + describeShape(c, s));
                        } else {
                            keys.add(key);
                        }
                        checkDecorators(c, md, s);
                    }
                    case Constructor ctor -> {
                        Env menv = env.copy();
                        Map<String, Type> binders = new LinkedHashMap<>();
                        List<String> paramTypes = new ArrayList<>();
                        for (Param p : ctor.params()) {
                            paramTypes.add(resolveType(p.type(), menv, new Opts(false, false, false, binders)).toString());
                        }
                        String key = s + "|new(" + String.join(", ", paramTypes) + ")";
                        List<String> keys = overloadKeys.computeIfAbsent("new", k -> new ArrayList<>());
                        if (keys.contains(key)) {
                            report(ctor.pos(), "constructor new(" + String.join(", ", paramTypes)
                                    + ") is already declared for " + describeShape(c, s));
                        } else {
                            keys.add(key);
                        }
                    }
                    case Destructor d -> {
                        String key = s + "|delete";
                        List<String> keys = overloadKeys.computeIfAbsent("delete", k -> new ArrayList<>());
                        if (keys.contains(key)) {
                            report(d.pos(), "a destructor is already declared for " + describeShape(c, s));
                        } else {
                            keys.add(key);
                        }
                    }
                }
            }
        }
    }

    private MemberInfo lastField(ClassInfo c, int shape) {
        MemberInfo last = null;
        for (MemberInfo m : c.all) {
            if (m.member instanceof Field f && !f.modifiers().isStatic() && m.existsIn(shape)) {
                last = m;
            }
        }
        return last;
    }

    /** Shapes a member is checked in: its guard's shape, every shape if unguarded, or none for a bad guard. */
    private static List<Integer> shapesOf(ClassInfo c, MemberInfo m) {
        if (m.member.modifiers().isStatic()) {
            return List.of(-1);
        }
        if (m.guarded) {
            return m.shape < 0 ? List.of() : List.of(m.shape);
        }
        List<Integer> out = new ArrayList<>();
        for (Shape s : c.shapes) {
            out.add(s.index);
        }
        return out;
    }

    private void checkDecorators(ClassInfo c, Method md, int shape) {
        for (Decorator d : md.modifiers().decorators()) {
            if (d.name().equals("native") && c.builtin) {
                continue;
            }
            if (d.name().equals("jvm") && c.builtin) {
                if (jvmName(md) == null) {
                    report(d.pos(), "@jvm takes the runtime method that stands for this one on the JVM, as a string");
                }
                continue;
            }
            if (!d.name().equals("overload")) {
                report(d.pos(), "unknown decorator @" + d.name());
                continue;
            }
            String constant = operatorConstant(d);
            if (constant == null) {
                report(d.pos(), overloadArgumentError(d));
                continue;
            }
            String sym = OPERATORS.get(constant);
            if (md.modifiers().isStatic()) {
                report(d.pos(), "operator methods cannot be static");
            }
            int n = md.params().size();
            int wanted = switch (constant) {
                case "NEGATE", "BIT_NOT", "CAST" -> 0;
                case "INDEX_SET" -> 2;
                default -> 1;
            };
            if (n != wanted) {
                report(d.pos(), "Operator." + constant + " (" + (wanted == 0 ? sym + "x" : constant.equals("INDEX_SET") ? "x[i] = v" : "x " + sym + " y")
                        + ") takes " + (wanted == 0 ? "no parameters" : wanted == 2 ? "two parameters, the index and the value" : "one parameter")
                        + (constant.equals("SUBTRACT") && n == 0 ? "; for -x, use Operator.NEGATE" : "")
                        + (constant.equals("NEGATE") && n == 1 ? "; for x - y, use Operator.SUBTRACT" : "")
                        + (constant.equals("CAST") ? "; it casts to its return type" : ""));
            }
            if (constant.equals("CAST")) {
                checkCastOperator(c, md, shape, d);
            }
            if (constant.endsWith("_ASSIGN")) {
                Type r = quietly(() -> resolveType(md.returnType(), shapeEnv(c, shape, null), new Opts(true, false, false, null)));
                if (!(r instanceof VoidT) && !r.isError()) {
                    report(md.returnType().pos(), "Operator." + constant + " changes the object itself, so it returns void, not " + r);
                }
            }
            if (Set.of("==", "!=", "<", ">", "<=", ">=").contains(sym)) {
                Type r = quietly(() -> resolveType(md.returnType(), shapeEnv(c, shape, null), new Opts(true, false, false, null)));
                if (!(r instanceof BitT) && !r.isError()) {
                    report(md.returnType().pos(), "comparison operator " + sym + " must return bit");
                }
            }
        }
    }

    /**
     * An @overload(Operator.CAST) method: (R)x calls the one returning R (§46), so it must
     * return something, and two can't return the same type.
     */
    private void checkCastOperator(ClassInfo c, Method md, int shape, Decorator d) {
        Env env = shapeEnv(c, shape, null);
        Type r = quietly(() -> resolveType(md.returnType(), env, new Opts(true, false, false, null)));
        if (r instanceof VoidT) {
            report(md.returnType().pos(), "Operator.CAST returns the type it casts to, not void");
            return;
        }
        for (MemberInfo m : c.all) {
            if (m.member == md) {
                return;
            }
            if (m.member instanceof Method other && overloads(other, CAST) && other.params().isEmpty() && !r.isError()
                    && r.equals(quietly(() -> resolveType(other.returnType(), env, new Opts(true, false, false, null))))) {
                report(d.pos(), c.name + " already casts to " + r + ", with " + other.name() + "()");
                return;
            }
        }
    }

    /**
     * Only a unique has a destructor (§16): a struct is copied freely, so copies would run it more than once, and
     * a class object is freed by the garbage collector, at no set time (§26).
     */
    private void checkDestructorRules(ClassInfo c) {
        if (c.unique || c.isInterface) {
            return;
        }
        for (MemberInfo d : c.destructors) {
            report(d.member.pos(), c.dynamic
                    ? "class " + c.name + " cannot have a destructor: the garbage collector frees class objects, at no set"
                            + " time; to release something, give it a close() method, implement AutoCloseable, and use"
                            + " try-with-resources"
                    : "struct " + c.name + " cannot have a destructor: copies of it would run it more than once;"
                            + " declare it `unique " + c.name + "` instead");
        }
    }

    /**
     * Whether a value of this type runs a destructor when it goes: a unique's own, or one of a unique inside it
     * (§16). array<T> has none: it is the language's own type, and its memory is freed with it.
     */
    private boolean hasDestructor(Type t) {
        if (t instanceof ArrayT a) {
            return hasDestructor(a.element());
        }
        if (!(t instanceof ClassT ct) || ct.cls().dynamic || isArrayObject(ct)) {
            return false;
        }
        Boolean known = destructorCache.get(ct);
        if (known != null) {
            return known;
        }
        destructorCache.put(ct, false); // a layout can't hold itself (reported elsewhere); this stops the recursion
        boolean r = ct.cls().destructors.stream().anyMatch(m -> m.existsIn(ct.header()));
        for (MemberInfo m : ct.cls().storedFields(ct.header())) {
            r = r || hasDestructor(quietly(() -> fieldType(ct, m)));
        }
        destructorCache.put(ct, r);
        return r;
    }

    private final Map<ClassT, Boolean> destructorCache = new HashMap<>();

    /** A class object is freed at no set time, so it can't hold a unique whose destructor must run at one (§26). */
    private void requireNoDestructor(Pos pos, String owner, String field, Type t) {
        if (hasDestructor(t)) {
            report(pos, owner + " can't hold " + t + " in " + field + ": " + t + " has a destructor, and the garbage"
                    + " collector frees a class object at no set time, so it would never run when it should; keep it in"
                    + " a local variable or in a unique");
        }
    }

    /** Whether a value of this type is, or contains, a unique (so it must not be copied). */
    public static boolean isUnique(Type t) {
        return (t instanceof ClassT ct && ct.cls().unique) || (t instanceof ArrayT a && isUnique(a.element()));
    }

    /** Expressions that produce a fresh value rather than naming an existing one. */
    private static boolean isTemporary(Expr e) {
        return e instanceof New || e instanceof Call || e instanceof Binary || e instanceof ArrayLit
                || e instanceof Unary u && u.op() != UnaryOp.DEREF
                || e instanceof Conditional c && isTemporary(c.then()) && isTemporary(c.otherwise());
    }

    /** A unique can be initialized or assigned only from a fresh value, never copied from a variable. */
    private void requireNotCopied(Body b, Expr src, Type t, String what) {
        Type from = b.types.get(src);
        if (from != null && !isUnique(from)) {
            return; // a conversion (a T[N] into an array<T>) makes a new value; a wrong type is reported elsewhere
        }
        // A cast of a value that isn't unique to one that is calls the value's cast method, which makes a new one.
        boolean made = src instanceof Cast c && b.types.get(c.operand()) instanceof Type ot && !isUnique(ot);
        if (isUnique(t) && !isTemporary(src) && !made) {
            report(src.pos(), what + ": " + t + " is unique and can't be copied; "
                    + (isArrayObject(t) ? "for a second array, use .copy()" : "create a new one, return it from a"
                    + " function, or pass it to a function, which lends it"));
        }
    }

    /** Every shape must have a constructor, unless the class declares none (§15). */
    private void checkConstructorCoverage(ClassInfo c) {
        if (c.constructors.isEmpty()) {
            return;
        }
        for (Shape s : c.shapes) {
            boolean covered = c.constructors.stream().anyMatch(m -> m.existsIn(s.index));
            if (!covered) {
                report(s.header.pos(), "no constructor for shape " + describeShape(c, s.index)
                        + "; add new" + (s.binders.isEmpty() ? "<<>>" : "<<...>>") + "(...) or an unguarded new(...)");
            }
        }
    }

    private void requireNoArgConstructor(ClassT t, Pos pos, String what) {
        if (t.cls().dynamic || t.cls().constructors.isEmpty()) {
            return;
        }
        boolean ok = t.cls().constructors.stream().anyMatch(m ->
                m.existsIn(t.header()) && ((Constructor) m.member).params().isEmpty());
        if (!ok) {
            report(pos, what + " of type " + t + " needs an initializer: " + t.cls().name
                    + " has no no-argument constructor for this shape");
        }
    }

    /**
     * A value declared without an initializer is default-constructed: a struct
     * or unique through its no-argument constructor, and an array of them
     * element by element. Class elements are null handles and need nothing.
     */
    private void requireDefaultConstructible(Type t, Pos pos, String what) {
        if (t instanceof ClassT ct) {
            requireNoArgConstructor(ct, pos, what);
            return;
        }
        Type element = t;
        while (element instanceof ArrayT a) {
            element = a.element();
        }
        if (t instanceof ArrayT && element instanceof ClassT ct && !ct.cls().dynamic && !ct.cls().constructors.isEmpty()
                && ct.cls().constructors.stream().noneMatch(m ->
                        m.existsIn(ct.header()) && ((Constructor) m.member).params().isEmpty())) {
            report(pos, "each element of " + what + " (" + t + ") is default-constructed, but " + ct.cls().name
                    + " has no no-argument constructor for this shape");
        }
    }

    // ================================================================ environments

    /**
     * The environment for checking a member of class {@code c} in shape
     * {@code shape}, with the shape's values symbolic. Guard binder names are
     * aliases of the header's names. Shape -1 is the static context: no
     * type parameters or values.
     */
    private Env shapeEnv(ClassInfo c, int shape, MemberInfo member) {
        Env env = Env.empty(c);
        if (shape < 0) {
            return env;
        }
        for (String t : c.typeParams) {
            env.typeVars.put(t, new ParamT(t));
        }
        Shape s = c.shapes.get(shape);
        for (int i = 0; i < s.binders.size(); i++) {
            env.values.put(s.binders.get(i).name(), new Const(new Sym(s.binders.get(i).name()), s.binderTypes.get(i)));
        }
        aliasGuard(env, s, member);
        return env;
    }

    /**
     * Puts a generic method's type parameters into env: the types inferred for a call or
     * instantiation where binders give them, otherwise symbolic, as a generic class's are while
     * its own body is checked (§12.2).
     */
    private static void addMethodTypes(Env env, MemberInfo m, Map<String, CVal> binders) {
        if (m != null && m.member instanceof Method md) {
            for (String t : md.typeParams()) {
                env.typeVars.put(t, binders != null && binders.get(t) instanceof TypeV tv ? tv.type() : new ParamT(t));
            }
        }
    }

    /** The environment for a member as seen through a concrete (or symbolic) instance type. */
    private Env instanceEnv(ClassT owner, MemberInfo member) {
        ClassInfo c = owner.cls();
        Env env = Env.empty(c);
        for (int i = 0; i < c.typeParams.size() && i < owner.typeArgs().size(); i++) {
            env.typeVars.put(c.typeParams.get(i), owner.typeArgs().get(i));
        }
        if (c.java != null && owner.typeArgs().isEmpty() && javaErasures.get(c.decl.headers().get(0)) instanceof Map<String, TypeRef> erased) {
            // A raw Java type (docs/JAVA.md §4.1): each type variable is what it erases to.
            erased.forEach((name, ref) -> env.typeVars.put(name, quietly(() -> resolveType(ref, Env.empty(c), Opts.PLAIN))));
        }
        if (owner.header() < 0 || owner.header() >= c.shapes.size()) {
            return env;
        }
        Shape s = c.shapes.get(owner.header());
        for (int i = 0; i < s.binders.size() && i < owner.valueArgs().size(); i++) {
            env.values.put(s.binders.get(i).name(), new Const(owner.valueArgs().get(i), s.binderTypes.get(i)));
        }
        aliasGuard(env, s, member);
        return env;
    }

    private static void aliasGuard(Env env, Shape s, MemberInfo member) {
        if (member == null || member.guard() == null) {
            return;
        }
        List<ValueItem> items = member.guard().items();
        for (int i = 0; i < items.size() && i < s.binders.size(); i++) {
            if (items.get(i) instanceof Binder b) {
                env.values.put(b.name(), env.values.get(s.binders.get(i).name()));
            }
        }
    }

    private ClassT selfType(ClassInfo c, int shape) {
        List<Type> targs = c.typeParams.stream().map(t -> (Type) new ParamT(t)).toList();
        List<CVal> vals = c.shapes.get(shape).binders.stream().map(b -> (CVal) new Sym(b.name())).toList();
        return new ClassT(c, targs, vals, shape);
    }

    // ================================================================ types

    private Type resolveType(TypeRef ref, Env env, Opts o) {
        boolean hasDims = !ref.arrayDims().isEmpty();
        Type t = resolveBase(ref, env, hasDims ? new Opts(o.allowVoid(), false, false, o.binders(), o.bound()) : o);
        if (!hasDims) {
            return t;
        }
        if (t instanceof VoidT) {
            report(ref.pos(), "cannot make an array of void");
            return Type.ERROR;
        }
        // Arrays are written array<T><<N>> and array<T>, not T[N] and T[] (§21.1).
        report(ref.pos(), "arrays are written " + arraySpelling(ref) + ", not " + io.github.thelinuxseal.seal.ast.AstPrinter.print(ref));
        return Type.ERROR;
    }

    /** How T[N][K] (or T[]) is written now: array<T><<N, K>>, array<T>. */
    private static String arraySpelling(TypeRef ref) {
        TypeRef base = new TypeRef(ref.pos(), ref.name(), ref.typeArgs(), ref.valueArgs(), List.of());
        String s = io.github.thelinuxseal.seal.ast.AstPrinter.print(base);
        List<Expr> dims = ref.arrayDims();
        if (dims.contains(null)) {
            for (int i = dims.size() - 1; i >= 0; i--) {
                s = dims.get(i) == null ? "array<" + s + ">" : "array<" + s + "><<" + io.github.thelinuxseal.seal.ast.AstPrinter.print(dims.get(i)) + ">>";
            }
            return s;
        }
        return "array<" + s + "><<" + dims.stream().map(io.github.thelinuxseal.seal.ast.AstPrinter::print).collect(Collectors.joining(", ")) + ">>";
    }

    /** A fixed array's methods come from the library struct FixedArray<T><<N>>, which has its layout (§21.1). */
    public ClassT fixedArrayMethods(ArrayT a) {
        ClassInfo c = classes.get("FixedArray");
        if (c == null || !c.builtin || a.size() == null) {
            return null;
        }
        return new ClassT(c, List.of(a.element()), List.of(a.size()), 0);
    }

    /** array<T> in its bare shape, whose length is chosen at run time; null (reported) without the library's array. */
    private ClassT bareArray(Type element, Pos pos) {
        ClassInfo a = classes.get("array");
        if (a == null || !a.builtin) {
            report(pos, "T[] needs the standard library's array type");
            return null;
        }
        for (Shape s : a.shapes) {
            if (s.binders.isEmpty()) {
                return new ClassT(a, List.of(element), List.of(), s.index);
            }
        }
        return null;
    }

    /** Whether t is the standard library's array<T> (either shape). */
    private boolean isArrayObject(Type t) {
        return t instanceof ClassT ct && ct.cls().name.equals("array") && ct.cls().builtin;
    }

    private Type resolveBase(TypeRef ref, Env env, Opts o) {
        String n = ref.name();
        if (n.equals("array") && ref.valueArgs() != null) {
            // array<T><<N>>: the built-in fixed array, N elements inline; array<T><<N, M, ...>>
            // has a dimension per length: N rows of array<T><<M, ...>> (§21.1).
            if (ref.typeArgs().size() != 1 || ref.valueArgs().items().isEmpty()) {
                report(ref.pos(), "a fixed array is array<T><<N>>, or array<T><<N, M, ...>>: one element type and its lengths");
                return Type.ERROR;
            }
            Type element = resolveType(ref.typeArgs().get(0), env, o.inner(false));
            if (element.isError()) {
                return Type.ERROR;
            }
            if (element instanceof VoidT) {
                report(ref.pos(), "cannot make an array of void");
                return Type.ERROR;
            }
            List<CVal> lengths = new ArrayList<>();
            for (ValueItem item : ref.valueArgs().items()) {
                Const len = valueItem(item, env, o);
                if (len.value() instanceof ErrV) {
                    return Type.ERROR;
                }
                if (!len.type().isInteger()) {
                    report(item.pos(), "an array's length must be an integer, not " + len.type());
                    return Type.ERROR;
                }
                BigInteger v = len.value().intValue();
                if (v != null && v.signum() < 0) {
                    reportValue(item.pos(), "an array's length can't be negative: " + v);
                    return Type.ERROR;
                }
                if (v != null && v.bitLength() > 31) {
                    reportValue(item.pos(), "an array's length must be less than 2 ** 31, not " + v);
                    return Type.ERROR;
                }
                lengths.add(len.value());
            }
            Type t = element;
            for (int i = lengths.size() - 1; i >= 0; i--) {
                t = new ArrayT(t, lengths.get(i));
            }
            return t;
        }
        switch (n) {
            case "size": {
                noTypeArgs(ref);
                noValueArgs(ref);
                return Type.SIZE;
            }
            case "int", "uint": {
                noTypeArgs(ref);
                CVal width = CVal.of(32);
                if (ref.valueArgs() != null) {
                    List<ValueItem> items = ref.valueArgs().items();
                    if (items.size() != 1) {
                        report(ref.pos(), n + "<<W>> takes exactly one width");
                        return Type.ERROR;
                    }
                    Const w = valueItem(items.get(0), env, o);
                    if (w.value() instanceof ErrV) {
                        return Type.ERROR;
                    }
                    if (!widthBinder(items.get(0), w, n + "<<" + widthItem(items.get(0), "W") + ">>")) {
                        return Type.ERROR;
                    }
                    if (!w.type().isInteger()) {
                        report(items.get(0).pos(), "integer width must be an integer, not " + w.type());
                        return Type.ERROR;
                    }
                    width = w.value();
                    BigInteger v = width.intValue();
                    if (v != null && (v.signum() <= 0 || v.compareTo(BigInteger.valueOf(64)) > 0)) {
                        reportValue(items.get(0).pos(), "integer width must be between 1 and 64, not " + v
                                + (v.signum() > 0 ? "; use a class such as BigUInt<<" + v + ">> for wider values" : ""));
                        return Type.ERROR;
                    }
                }
                return new IntT(width, n.equals("int"));
            }
            case "fixed", "ufixed": {
                noTypeArgs(ref);
                boolean signed = n.equals("fixed");
                if (ref.valueArgs() == null || ref.valueArgs().items().size() != 2) {
                    report(ref.pos(), n + " needs two values, " + n + "<<N, M>>: N bits before the point and M after");
                    return Type.ERROR;
                }
                List<ValueItem> items = ref.valueArgs().items();
                Const ib = valueItem(items.get(0), env, o);
                Const fb = valueItem(items.get(1), env, o);
                if (ib.value() instanceof ErrV || fb.value() instanceof ErrV) {
                    return Type.ERROR;
                }
                String written = n + "<<" + widthItem(items.get(0), "N") + ", " + widthItem(items.get(1), "M") + ">>";
                if (!widthBinder(items.get(0), ib, written) | !widthBinder(items.get(1), fb, written)) {
                    return Type.ERROR;
                }
                if (!ib.type().isInteger() || !fb.type().isInteger()) {
                    report(ref.pos(), "the bit counts of " + n + "<<N, M>> must be integers");
                    return Type.ERROR;
                }
                BigInteger nv = ib.value().intValue();
                BigInteger mv = fb.value().intValue();
                if (nv != null && nv.signum() < (signed ? 1 : 0)) {
                    reportValue(items.get(0).pos(), signed ? "fixed<<N, M>> needs N >= 1; N includes the sign bit"
                            : "ufixed<<N, M>> needs N >= 0");
                    return Type.ERROR;
                }
                if (mv != null && mv.signum() < 0) {
                    reportValue(items.get(1).pos(), n + "<<N, M>> needs M >= 0");
                    return Type.ERROR;
                }
                if (nv != null && mv != null) {
                    BigInteger total = nv.add(mv);
                    if (total.signum() <= 0 || total.compareTo(BigInteger.valueOf(64)) > 0) {
                        reportValue(ref.pos(), n + "<<" + nv + ", " + mv + ">> has " + total
                                + " bits; fixed-point types have 1 to 64");
                        return Type.ERROR;
                    }
                }
                return new FixedT(ib.value(), fb.value(), signed);
            }
            case "bit", "float", "double", "char": {
                noTypeArgs(ref);
                noValueArgs(ref);
                return switch (n) {
                    case "bit" -> Type.BIT;
                    case "float" -> Type.FLOAT;
                    case "char" -> Type.CHAR;
                    default -> Type.DOUBLE;
                };
            }
            case "void": {
                noTypeArgs(ref);
                noValueArgs(ref);
                if (!o.allowVoid()) {
                    report(ref.pos(), "void is only allowed as a return type or in addr<void>");
                    return Type.ERROR;
                }
                return Type.VOID;
            }
            case "addr": {
                noValueArgs(ref);
                if (ref.typeArgs().size() != 1) {
                    report(ref.pos(), "addr takes one type argument, e.g. addr<int>");
                    return Type.ERROR;
                }
                Type target = resolveType(ref.typeArgs().get(0), env, new Opts(true, true, false, o.binders(), o.bound()));
                if (target instanceof ClassT ct && ct.cls().dynamic) {
                    report(ref.pos(), "class objects can move, so they have no address; a " + ct.cls().name
                            + " variable already refers to one");
                    return Type.ERROR;
                }
                return target.isError() ? target : new AddrT(target);
            }
            case "?", "?extends", "?super": {
                if (env.cls != null && env.cls.java != null) {
                    // A wildcard of a Java signature (docs/JAVA.md §4.1); no program writes one.
                    Type bound = n.equals("?") ? javaObjectHandle() : resolveType(ref.typeArgs().get(0), env, o.inner(false));
                    return bound.isError() ? bound : new WildT(bound, n.equals("?") ? 0 : n.equals("?extends") ? 1 : 2);
                }
                break;
            }
            case "handle": {
                noValueArgs(ref);
                if (ref.typeArgs().size() != 1) {
                    report(ref.pos(), "handle takes one type argument, e.g. handle<Foo>");
                    return Type.ERROR;
                }
                Type target = resolveType(ref.typeArgs().get(0), env, new Opts(false, true, false, o.binders(), o.bound()));
                if (target.isError()) {
                    return target;
                }
                if (!(target instanceof ClassT ct && ct.cls().dynamic) && !(target instanceof ParamT)) {
                    report(ref.pos(), "handle<...> needs a class, not " + target + "; use addr<" + target + "> for struct values");
                    return Type.ERROR;
                }
                return new HandleT(target);
            }
            default:
                break;
        }

        Type var = env.typeVars.get(n);
        if (var != null) {
            noTypeArgs(ref);
            noValueArgs(ref);
            return var;
        }
        if (env.values.containsKey(n) && lookupClass(n, env.cls) == null) {
            report(ref.pos(), n + " is a compile-time value, not a type");
            return Type.ERROR;
        }
        ClassInfo c = lookupClass(n, env.cls);
        if (c != null && visibleClass(c, env.cls, ref.pos()) == null) {
            return Type.ERROR;
        }
        if (c != null && c.builtin && c.pkg.equals("seal.java") && JAVA_PRIMITIVES.containsKey(c.name)) {
            noTypeArgs(ref);
            noValueArgs(ref);
            return new JavaPrimT(JAVA_PRIMITIVES.get(c.name)); // JavaInt: Java's int (docs/JAVA.md §3.1)
        }
        if (c != null && c.java != null && !c.java.visible() && (env.cls == null || env.cls.java == null)) {
            report(ref.pos(), "Java's " + c.java.name() + " isn't public, so a program can't use it");
            return Type.ERROR;
        }
        if (c == null) {
            ClassInfo owner = env.cls == null ? null : env.cls.outer;
            while (owner != null && !owner.typeParams.contains(n)) {
                owner = owner.outer;
            }
            if (owner != null) {
                report(ref.pos(), n + " is a type parameter of " + owner.name + "; a nested type doesn't see its outer"
                        + " type's parameters (give it its own: " + env.cls.simpleName + "<" + n + ">)");
                return Type.ERROR;
            }
            if (env.cls != null && env.cls.typeParams.contains(n)) {
                report(ref.pos(), "type parameter " + n + " is not available in a static member");
            } else {
                report(ref.pos(), "unknown type " + n + unknownTypeHint(n, env.cls));
            }
            return Type.ERROR;
        }

        boolean raw = c.java != null && ref.typeArgs().isEmpty(); // a Java generic without its arguments, as Java allows
        if (ref.typeArgs().size() != c.typeParams.size() && !raw) {
            report(ref.pos(), c.name + " takes " + c.typeParams.size() + " type argument"
                    + (c.typeParams.size() == 1 ? "" : "s") + ", not " + ref.typeArgs().size());
            return Type.ERROR;
        }
        List<Type> targs = new ArrayList<>();
        for (TypeRef a : ref.typeArgs()) {
            Type t = resolveType(a, env, o.inner(false));
            if (t instanceof VoidT) {
                report(a.pos(), "void is not a valid type argument");
                t = Type.ERROR;
            }
            targs.add(t);
        }
        if (targs.stream().anyMatch(Type::isError)) {
            return Type.ERROR;
        }
        if (c.java != null && (env.cls == null || env.cls.java == null)) {
            for (int i = 0; i < targs.size(); i++) {
                String why = javaTypeArgument(targs.get(i));
                if (why != null) {
                    report(ref.typeArgs().get(i).pos(), why);
                    return Type.ERROR;
                }
            }
        }
        if (isJavaArray(c) && !isJavaValue(targs.get(0)) && !(targs.get(0) instanceof ParamT)) {
            report(ref.pos(), "JavaArray<T> is Java's T[], so T is one of Java's types (JavaByte, JavaString, java.time.LocalDate),"
                    + " not " + targs.get(0));
            return Type.ERROR;
        }

        List<Const> vals = null;
        if (ref.valueArgs() != null) {
            vals = new ArrayList<>();
            for (ValueItem item : ref.valueArgs().items()) {
                Const v = valueItem(item, env, o);
                if (v.value() instanceof ErrV) {
                    return Type.ERROR;
                }
                vals.add(v);
            }
        }
        int shape = selectShape(c, vals, ref.pos());
        if (shape < 0) {
            return Type.ERROR;
        }
        ClassT t = new ClassT(c, targs, vals == null ? List.of() : vals.stream().map(Const::value).toList(), shape);
        if (c.builtin && COPYING_COLLECTIONS.contains(c.name) && targs.stream().anyMatch(Checker::isUnique)) {
            report(ref.pos(), "a" + (c.name.equals("array") ? "n " : " ") + c.name + " copies its elements in and out,"
                    + " so it can't hold " + targs.stream().filter(Checker::isUnique).findFirst().orElseThrow()
                    + ", a unique; keep uniques in fields or locals"
                    + (c.name.equals("array") ? ", and for a grid use a fixed array, array<T><<N, K>>, or one array indexed by row * width + column" : ""));
            return Type.ERROR;
        }
        if (c.dynamic && !o.allowDynamic()) {
            // A class used as a type means a handle to it: `Animal a` is a handle<Animal>.
            validateClassInstance(t, ref.pos());
            return new HandleT(t);
        }
        validateInstance(t, ref.pos());
        return t;
    }

    private void noTypeArgs(TypeRef ref) {
        if (!ref.typeArgs().isEmpty()) {
            report(ref.pos(), ref.name() + " takes no type arguments");
        }
    }

    /**
     * A binder of a width, int<<size W>> or fixed<<size N, size M>>, takes a size (§37.2): true if
     * it does, or the item binds nothing; else reports, with how to write the type.
     */
    private boolean widthBinder(ValueItem item, Const c, String written) {
        if (item instanceof Binder b && !c.type().equals(Type.SIZE) && !c.type().isError()) {
            report(b.pos(), "a width is a size, which holds 0 to 64: " + written + ", not " + b.type().name() + " " + b.name());
            return false;
        }
        return true;
    }

    /** A width item as a type with size binders writes it: size W for a binder, else the placeholder. */
    private static String widthItem(ValueItem item, String placeholder) {
        return item instanceof Binder b ? "size " + b.name() : placeholder;
    }

    private void noValueArgs(TypeRef ref) {
        if (ref.valueArgs() != null) {
            report(ref.pos(), ref.name() + " takes no <<...>> values");
        }
    }

    private Const valueItem(ValueItem item, Env env, Opts o) {
        if (item instanceof Binder b) {
            if (o.bound() != null && o.bound().containsKey(b.name())) {
                return o.bound().get(b.name());
            }
            if (o.binders() == null) {
                report(b.pos(), "<<" + b.type().name() + " " + b.name() + ">> declares a value; "
                        + "binders are only allowed in parameter types");
                return new Const(new ErrV(), Type.ERROR);
            }
            if (env.values.containsKey(b.name())) {
                report(b.pos(), "binder " + b.name() + " shadows an existing compile-time value");
                return new Const(new ErrV(), Type.ERROR);
            }
            Type t = resolveBinderType(b.type(), env.cls);
            Const c = new Const(new Sym(b.name()), t);
            o.binders().put(b.name(), t);
            env.values.put(b.name(), c);
            return c;
        }
        Const c = evalConst(((ValueExpr) item).expr(), env);
        if (c.value() instanceof FloatV f && f.value().isNaN()) {
            reportValue(item.pos(), "NaN cannot be a compile-time argument");
            return new Const(new ErrV(), Type.ERROR);
        }
        return c;
    }

    /** Picks the header matching the given values, or reports and returns -1. */
    private int selectShape(ClassInfo c, List<Const> vals, Pos pos) {
        List<Integer> matches = new ArrayList<>();
        for (Shape s : c.shapes) {
            if (vals == null) {
                if (s.binders.isEmpty()) {
                    matches.add(s.index);
                }
                continue;
            }
            if (s.binders.size() != vals.size()) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < vals.size() && ok; i++) {
                ok = constFits(vals.get(i), s.binderTypes.get(i));
            }
            if (ok) {
                matches.add(s.index);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            if (vals == null) {
                report(pos, c.name + " needs <<...>> values; its shapes are " + allShapes(c));
            } else {
                String got = vals.stream().map(v -> v.value().toString()).collect(Collectors.joining(", ", "<<", ">>"));
                boolean arityMatch = c.shapes.stream().anyMatch(s -> s.binders.size() == vals.size());
                String msg = c.name + got + " matches no shape of " + c.name + "; its shapes are " + allShapes(c);
                if (arityMatch && vals.stream().allMatch(v -> v.value().isConstant())) {
                    reportValue(pos, msg);
                } else {
                    report(pos, msg);
                }
            }
            return -1;
        }
        report(pos, "ambiguous: " + c.name + " matches shapes "
                + matches.stream().map(i -> describeShape(c, i)).collect(Collectors.joining(" and ")));
        return -1;
    }

    /** Whether a compile-time value can bind to a parameter of the given type. */
    private static boolean constFits(Const v, Type binderType) {
        Type vt = v.type();
        if (vt.isError() || binderType.isError()) {
            return true;
        }
        return switch (binderType) {
            case IntT bt -> switch (vt) {
                case IntConstT ic -> ic.value() == null || v.value().intValue() == null
                        || fits(v.value().intValue(), bt);
                case IntT it -> it.equals(bt) || (v.value().intValue() != null && fits(v.value().intValue(), bt));
                default -> false;
            };
            case BitT b -> vt instanceof BitT;
            case FloatT f -> vt instanceof FloatConstT || vt instanceof FloatT;
            case DoubleT d -> vt instanceof FloatConstT || vt instanceof DoubleT;
            case StrConstT s -> vt instanceof StrConstT;
            default -> false;
        };
    }

    private static boolean fits(BigInteger v, IntT t) {
        if (t.isSize()) {
            return v.signum() >= 0 && v.compareTo(BigInteger.valueOf(Type.MAX_SIZE)) <= 0;
        }
        BigInteger w = t.width().intValue();
        if (w == null) {
            return true;
        }
        int bits = w.intValue();
        if (t.signed()) {
            BigInteger lim = BigInteger.ONE.shiftLeft(bits - 1);
            return v.compareTo(lim.negate()) >= 0 && v.compareTo(lim) < 0;
        }
        return v.signum() >= 0 && v.compareTo(BigInteger.ONE.shiftLeft(bits)) < 0;
    }

    // ================================================================ compile-time evaluation

    private Const evalConst(Expr e, Env env) {
        switch (e) {
            case IntLit i:
                return new Const(new IntV(i.value()), new IntConstT(i.value()));
            case FloatLit f:
                return new Const(new FloatV(f.value()), new FloatConstT(exactLiteral(f)));
            case StringLit s:
                return new Const(new StrV(s.value()), new StrConstT(s.value()));
            case BoolLit b:
                return new Const(new BitV(b.value()), Type.BIT);
            case Name n: {
                Const c = env.values.get(n.name());
                if (c != null) {
                    return c;
                }
                Field f = constField(env.cls, n.name());
                if (f != null) {
                    return evalConstField(env.cls, f, n.pos());
                }
                for (StaticMember sm : staticImports(env.cls, n.name())) {
                    if (sm.m().member instanceof Field cf && constField(sm.cls(), cf.name()) == cf) {
                        return evalConstField(sm.cls(), cf, n.pos()); // import static Integer.MAX_INT;
                    }
                }
                report(n.pos(), "`" + n.name() + "` is not a compile-time constant");
                return new Const(new ErrV(), Type.ERROR);
            }
            case Unary u: {
                Const a = evalConst(u.operand(), env);
                if (a.value() instanceof ErrV) {
                    return a;
                }
                String op = switch (u.op()) {
                    case NEG -> "-";
                    case NOT -> "!";
                    case BIT_NOT -> "~";
                    default -> null;
                };
                CVal r = op == null ? null : CVal.unary(op, a.value());
                if (r == null) {
                    report(u.pos(), "operator " + u.op().symbol + " is not valid on " + typeName(a.type())
                            + " in a compile-time expression");
                    return new Const(new ErrV(), Type.ERROR);
                }
                return new Const(r, constResultType(a.type(), r));
            }
            case Binary b: {
                Const l = evalConst(b.left(), env);
                if ((b.op() == BinaryOp.AND || b.op() == BinaryOp.OR) && l.value() instanceof BitV lb
                        && lb.value() == (b.op() == BinaryOp.OR)) {
                    return l; // false && x, true || x: x isn't worked out, as when the program runs
                }
                Const r = evalConst(b.right(), env);
                if (l.value() instanceof ErrV || r.value() instanceof ErrV) {
                    return new Const(new ErrV(), Type.ERROR);
                }
                // An integer next to a floating-point value widens to it, as at run time: 1.0 / 3 (§41.6).
                if (constKind(l.type()).equals("float") && constKind(r.type()).equals("int") && r.value() instanceof IntV ri) {
                    r = new Const(new FloatV(ri.value().doubleValue()), new FloatConstT(new java.math.BigDecimal(ri.value())));
                } else if (constKind(l.type()).equals("int") && constKind(r.type()).equals("float") && l.value() instanceof IntV li) {
                    l = new Const(new FloatV(li.value().doubleValue()), new FloatConstT(new java.math.BigDecimal(li.value())));
                }
                if (!constKind(l.type()).equals(constKind(r.type()))) {
                    report(b.pos(), "cannot combine " + typeName(l.type()) + " and " + typeName(r.type())
                            + " in a compile-time expression");
                    return new Const(new ErrV(), Type.ERROR);
                }
                CVal v = CVal.binary(b.op().symbol, l.value(), r.value());
                if (v == null) {
                    report(b.pos(), "operator " + b.op().symbol + " is not valid on " + typeName(l.type())
                            + " in a compile-time expression");
                    return new Const(new ErrV(), Type.ERROR);
                }
                if (v instanceof ErrV) {
                    reportValue(b.pos(), "invalid compile-time arithmetic: " + l.value() + " " + b.op().symbol
                            + " " + r.value() + (b.op() == BinaryOp.DIV || b.op() == BinaryOp.MOD ? " divides by zero"
                            : b.op() == BinaryOp.POW ? (l.value().intValue() != null && l.value().intValue().signum() == 0
                                    ? " divides by zero" : " has more than " + CVal.MAX_POWER_BITS + " bits") : ""));
                    return new Const(v, Type.ERROR);
                }
                boolean comparison = switch (b.op()) {
                    case EQ, NE, LT, GT, LE, GE -> true;
                    default -> false;
                };
                Type t = comparison ? Type.BIT : l.type() instanceof IntT ? l.type() : r.type() instanceof IntT ? r.type() : l.type();
                return new Const(v, constResultType(t, v));
            }
            case SizeOf s: {
                Type t = resolveType(s.type(), env, Opts.PLAIN);
                if (t.isError()) {
                    return new Const(new ErrV(), Type.ERROR);
                }
                CVal v = layout(t, s.pos()).size();
                return new Const(v, new IntConstT(v.intValue()));
            }
            case AlignOf a: {
                Type t = resolveType(a.type(), env, Opts.PLAIN);
                if (t.isError()) {
                    return new Const(new ErrV(), Type.ERROR);
                }
                CVal v = layout(t, a.pos()).align();
                return new Const(v, new IntConstT(v.intValue()));
            }
            case Call call:
                return pureCall(call, env);
            case Conditional cd: {
                Const c = evalConst(cd.cond(), env);
                if (c.value() instanceof ErrV) {
                    return c;
                }
                if (!(c.type() instanceof BitT)) {
                    report(cd.cond().pos(), "the condition of ?: must be a bit, not " + typeName(c.type()));
                    return new Const(new ErrV(), Type.ERROR);
                }
                if (c.value() instanceof BitV bit) {
                    return evalConst(bit.value() ? cd.then() : cd.otherwise(), env); // only the branch it picks, as at run time
                }
                // Not known yet (N > 8 inside a generic class): both branches, and the one it picks once it is.
                Const a = evalConst(cd.then(), env);
                Const o = evalConst(cd.otherwise(), env);
                if (a.value() instanceof ErrV || o.value() instanceof ErrV) {
                    return new Const(new ErrV(), Type.ERROR);
                }
                if (!constKind(a.type()).equals(constKind(o.type()))) {
                    report(cd.pos(), "the branches of ?: give " + typeName(a.type()) + " and " + typeName(o.type())
                            + ", which a compile-time value can't choose between");
                    return new Const(new ErrV(), Type.ERROR);
                }
                CVal v = CVal.cond(c.value(), a.value(), o.value());
                return new Const(v, a.type() instanceof IntConstT ? new IntConstT(null) : a.type());
            }
            case Cast cast: {
                Type to = resolveType(cast.type(), env, Opts.PLAIN);
                Const v = evalConst(cast.operand(), env);
                if (to.isError() || v.value() instanceof ErrV) {
                    return new Const(new ErrV(), Type.ERROR);
                }
                return constCast(v, to, cast.pos());
            }
            default:
                report(e.pos(), "not a compile-time constant; <<...>> values and array sizes may use literals, "
                        + "arithmetic, const fields, sizeof, calls to pure methods and other compile-time values");
                return new Const(new ErrV(), Type.ERROR);
        }
    }

    /** Runs pure methods while compiling (§17.2); one for the whole program, so each call's result is kept. */
    private final PureEval pure = new PureEval(this);
    /** The compile-time calls being resolved, to catch one in its own method's types. */
    private final Set<Call> resolvingPure = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * A call in a compile-time value (§17.2): to a static pure method, found as any call is, and
     * run now; or, on values not known yet (`Fib.of(N)` in a generic class), a value computed when
     * they are.
     */
    private Const pureCall(Call call, Env env) {
        return guarded(call, () -> pureCallOnce(call, env), new Const(new ErrV(), Type.ERROR));
    }

    /** Works out a compile-time call, catching one met again while it is worked out: f(1) in f's own signature. */
    private <T> T guarded(Call call, java.util.function.Supplier<T> body, T onCycle) {
        if (resolvingPure.contains(call)) {
            // Finding and running f needs its parameters' types, which need f(1).
            throw new PureCycle(call);
        }
        resolvingPure.add(call);
        try {
            return body.get();
        } catch (PureCycle c) {
            if (c.call != call) {
                throw c;
            }
            report(call.pos(), "this call is in the types of the method it calls, which it needs to be found");
            return onCycle;
        } finally {
            resolvingPure.remove(call);
        }
    }

    /** A pure method a compile-time call reaches, with its binders, its name (Fib.of) and its result's type. */
    private record PureTarget(MemberInfo m, Map<String, CVal> binders, String name, Type ret) {
    }

    /** Finds the method a compile-time call calls, as any call is found; null, reported, if it isn't a pure one. */
    private PureTarget resolvePure(Call call, Env env) {
        Body b = new Body(env.cls, -1, env, true, false, Type.VOID, "<<...>>");
        if (checkExpr(b, call, null).isError()) {
            return null;
        }
        CallInfo ci = b.calls.get(call);
        if (ci == null || ci.kind() != CallKind.STATIC || !(ci.member().member instanceof Method md)) {
            report(call.pos(), "a compile-time value can call only static pure methods (§17.2)");
            return null;
        }
        String name = declaringClass(ci.member()).name + "." + md.name();
        if (!md.modifiers().isPure()) {
            report(call.pos(), name + " isn't pure, so it can't run while compiling; declare it `static pure` (§17.2)");
            return null;
        }
        return new PureTarget(ci.member(), ci.binders(), name, returnTypeOf(null, ci.member(), ci.binders()));
    }

    /** A pure call's arguments while compiling; null after an error. */
    private List<Object> pureArguments(Call call, Env env) {
        List<Object> args = new ArrayList<>();
        for (Expr a : call.args()) {
            Object v = pureArgument(a, env);
            if (v == null) {
                return null;
            }
            args.add(v);
        }
        return args;
    }

    /** An argument already made while compiling: the array a pure call gave, passed on to another as it is. */
    private record Made(Object value, String text) {
    }

    private static final Object NOT_AN_ARRAY = new Object();

    /** Stats.sum(Stats.squares(10)): a pure call giving an array, run now, its array handed on; else NOT_AN_ARRAY. */
    private Object arrayArgument(Call call, Env env) {
        PureTarget t = quietly(() -> resolvePure(call, env));
        if (t == null || !(Purity.isArray(t.ret()) || t.ret() instanceof ArrayT)) {
            return NOT_AN_ARRAY; // worked out, and reported, as a compile-time value
        }
        List<Object> args = pureArguments(call, env);
        if (args == null) {
            return null;
        }
        String text = t.name() + "(" + String.join(", ", args.stream().map(Checker::argumentText).toList()) + ")";
        if (!args.stream().allMatch(Checker::known) || !t.binders().values().stream().allMatch(v -> v.isConstant() || v instanceof TypeV)) {
            report(call.pos(), text + " gives an array, so it can run only on values known now");
            return null;
        }
        try {
            return new Made(pure.call(t.m(), t.binders(), pureValues(t, args)), text);
        } catch (PureEval.Failure f) {
            report(call.pos(), cantWorkOut(text, f));
            return null;
        }
    }

    private List<Object> pureValues(PureTarget t, List<Object> args) {
        List<Type> params = paramTypes(null, t.m(), t.binders());
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            values.add(pureValue(args.get(i), params.get(i)));
        }
        return values;
    }

    private static String cantWorkOut(String call, PureEval.Failure f) {
        return call + " can't be worked out while compiling: it " + f.getMessage() + (f.where == null ? "" : " (" + f.where + ")");
    }

    /** A compile-time call met again while it is being worked out; the first one reports it. */
    private static final class PureCycle extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Call call;

        PureCycle(Call call) {
            super(null, null, false, false);
            this.call = call;
        }
    }

    private Const pureCallOnce(Call call, Env env) {
        Const error = new Const(new ErrV(), Type.ERROR);
        PureTarget t = resolvePure(call, env);
        if (t == null) {
            return error;
        }
        // Each argument is a compile-time value; for an array, an array literal (a list of them) or one a pure call made.
        List<Object> args = pureArguments(call, env);
        if (args == null) {
            return error;
        }
        String name = t.name();
        MemberInfo m = t.m();
        Type ret = t.ret();
        boolean known = args.stream().allMatch(Checker::known)
                && t.binders().values().stream().allMatch(v -> v.isConstant() || v instanceof TypeV);
        if (!known) {
            if (!t.binders().isEmpty() || args.stream().anyMatch(a -> !(a instanceof CVal))) {
                report(call.pos(), name + " can't run on values not known yet when it has binders or takes arrays");
                return error;
            }
            String at = file;
            List<CVal> symbolic = args.stream().map(a -> (CVal) a).toList();
            PureTarget unbound = new PureTarget(m, Map.of(), name, ret);
            return new Const(new CVal.PureCall(name, m, symbolic,
                    now -> runPure(unbound, new ArrayList<>(now), call.pos(), at, true)), pureType(ret, null));
        }
        CVal v = runPure(t, args, call.pos(), file, false);
        return v instanceof ErrV ? error : new Const(v, pureType(ret, v));
    }

    /** A pure call's argument while compiling: a compile-time value, the values of an array literal, or an array made; null after an error. */
    private Object pureArgument(Expr a, Env env) {
        if (a instanceof Call call) {
            Object made = guarded(call, () -> arrayArgument(call, env), null);
            if (made != NOT_AN_ARRAY) {
                return made;
            }
        }
        if (a instanceof ArrayLit al) {
            List<Object> items = new ArrayList<>();
            for (Expr e : al.elements()) {
                Object v = pureArgument(e, env);
                if (v == null) {
                    return null;
                }
                items.add(v);
            }
            return items;
        }
        Const c = evalConst(a, env);
        return c.value() instanceof ErrV ? null : c.value();
    }

    private static boolean known(Object arg) {
        return switch (arg) {
            case List<?> items -> items.stream().allMatch(Checker::known);
            case Made m -> true;
            default -> ((CVal) arg).isConstant();
        };
    }

    private static String argumentText(Object arg) {
        return switch (arg) {
            case List<?> items -> "[" + String.join(", ", items.stream().map(Checker::argumentText).toList()) + "]";
            case Made m -> m.text();
            default -> arg.toString();
        };
    }

    /** m(args) worked out now; on failure, the reason is reported at the call, and the value is an error. */
    private CVal runPure(PureTarget t, List<Object> args, Pos at, String callFile, boolean later) {
        try {
            return PureEval.toCVal(pure.call(t.m(), t.binders(), pureValues(t, args)), t.ret());
        } catch (PureEval.Failure f) {
            String message = cantWorkOut(t.name() + "(" + String.join(", ", args.stream().map(Checker::argumentText).toList())
                    + ")", f);
            String saved = file;
            file = callFile;
            try {
                if (later) {
                    reportValue(at, message);
                } else {
                    report(at, message);
                }
            } finally {
                file = saved;
            }
            return new ErrV();
        }
    }

    private Object pureValue(Object arg, Type t) {
        if (arg instanceof Made m) {
            return m.value();
        }
        if (arg instanceof List<?> items && Purity.isArray(t)) {
            Type element = ((ClassT) t).typeArgs().get(0);
            Object[] out = new Object[items.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = pureValue(items.get(i), element);
            }
            return out;
        }
        if (arg instanceof List<?> items) {
            if (!(t instanceof ArrayT at) || at.size() == null || at.size().intValue() == null
                    || at.size().intValue().intValue() != items.size()) {
                throw new PureEval.Failure("was given an array literal for a " + t, null);
            }
            Object[] out = new Object[items.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = pureValue(items.get(i), at.element());
            }
            return out;
        }
        return pure.fromCVal((CVal) arg, t);
    }

    /**
     * The type of a pure call's value while compiling: as a literal's, a known number that must fit
     * where it goes, whatever type the method computed it in.
     */
    private static Type pureType(Type ret, CVal v) {
        return switch (v) {
            case StrV s -> new StrConstT(s.value());
            case IntV i -> new IntConstT(i.value());
            case FloatV f -> new FloatConstT(exactDouble(f.value()));
            case null -> ret instanceof IntT ? new IntConstT(null) : ret;
            default -> ret;
        };
    }

    /** (T)v on a compile-time value, as the cast converts it at run time (§41.1). */
    private Const constCast(Const v, Type to, Pos at) {
        CVal x = v.value();
        if (!x.isConstant()) {
            if (to instanceof IntT && (v.type() instanceof IntT || v.type() instanceof IntConstT) && v.type().equals(to)) {
                return new Const(x, to);
            }
            report(at, "a cast in a compile-time value needs a value known here, not " + x);
            return new Const(new ErrV(), Type.ERROR);
        }
        switch (to) {
            case IntT it when x instanceof IntV || x instanceof FloatV -> {
                // Toward zero into a long for a floating-point value, then wrapped to the width, as at run time.
                BigInteger n = x instanceof IntV i ? i.value() : BigInteger.valueOf((long) ((FloatV) x).value().doubleValue());
                BigInteger w = it.width().intValue();
                if (w == null) {
                    report(at, "a cast in a compile-time value needs a known width, not " + to);
                    return new Const(new ErrV(), Type.ERROR);
                }
                if (it.isSize()) {
                    if (!fits(n, it)) {
                        reportValue(at, n + " isn't a size: a size is 0 to " + Type.MAX_SIZE);
                        return new Const(new ErrV(), Type.ERROR);
                    }
                    return new Const(new IntV(n), to);
                }
                BigInteger mod = BigInteger.ONE.shiftLeft(it.isChar() ? 16 : w.intValueExact());
                BigInteger r = n.mod(mod);
                if (it.signed() && !it.isChar() && r.testBit(w.intValueExact() - 1)) {
                    r = r.subtract(mod);
                }
                return new Const(new IntV(r), to);
            }
            case FloatT f when x instanceof IntV || x instanceof FloatV -> {
                double d = x instanceof IntV i ? i.value().doubleValue() : ((FloatV) x).value();
                return new Const(new FloatV((double) (float) d), to);
            }
            case DoubleT d when x instanceof IntV || x instanceof FloatV -> {
                return new Const(new FloatV(x instanceof IntV i ? i.value().doubleValue() : ((FloatV) x).value()), to);
            }
            case BitT b when x instanceof BitV -> {
                return new Const(x, to);
            }
            default -> {
                report(at, "can't cast " + typeName(v.type()) + " to " + to + " in a compile-time value");
                return new Const(new ErrV(), Type.ERROR);
            }
        }
    }

    private static Type constResultType(Type t, CVal v) {
        if (t instanceof FloatConstT) {
            return new FloatConstT(v instanceof FloatV f ? exactDouble(f.value()) : null);
        }
        return t instanceof IntConstT ? new IntConstT(v.intValue()) : t;
    }

    private static String constKind(Type t) {
        return switch (t) {
            case IntT i -> "int";
            case IntConstT i -> "int";
            case FloatT f -> "float";
            case DoubleT d -> "float";
            case FloatConstT f -> "float";
            case BitT b -> "bit";
            case StrConstT s -> "string";
            default -> t.toString();
        };
    }

    private static Field constField(ClassInfo c, String name) {
        if (c == null) {
            return null;
        }
        List<MemberInfo> list = c.members.get(name);
        if (list == null) {
            return null;
        }
        for (MemberInfo m : list) {
            if (m.member instanceof Field f && f.modifiers().isConst() && !m.guarded && f.init() != null) {
                return f;
            }
        }
        return null;
    }

    private Const evalConstField(ClassInfo c, Field f, Pos use) {
        if (!constInProgress.add(f)) {
            constCycle = f;
            return new Const(new ErrV(), Type.ERROR);
        }
        try {
            Const v = quietly(() -> evalConst(f.init(), Env.empty(c)));
            if (v.value() instanceof ErrV) {
                if (constCycle != null) {
                    report(use, "const field " + constCycle.name() + " is defined in terms of itself");
                } else {
                    report(use, "`" + f.name() + "` is not a compile-time constant");
                }
                return v;
            }
            Type declared = quietly(() -> resolveType(f.type(), Env.empty(c), Opts.PLAIN));
            if (declared instanceof IntT it && v.type() instanceof IntConstT) {
                return new Const(v.value(), it);
            }
            return v;
        } finally {
            constInProgress.remove(f);
            if (constInProgress.isEmpty()) {
                constCycle = null;
            }
        }
    }

    // ================================================================ layout (§21, §22)

    /** Size and alignment in bits. */
    record Lay(CVal size, CVal align) {
    }

    private Lay layout(Type t, Pos pos) {
        return switch (t) {
            case IntT i -> new Lay(i.width(), orErr(CVal.unary("alignfor", i.width())));
            case FixedT f -> new Lay(f.width(), orErr(CVal.unary("alignfor", f.width())));
            case BitT b -> new Lay(CVal.of(1), CVal.of(1));
            case FloatT f -> new Lay(CVal.of(32), CVal.of(32));
            case DoubleT d -> new Lay(CVal.of(64), CVal.of(64));
            case AddrT a -> new Lay(CVal.of(64), CVal.of(64));
            case HandleT h -> new Lay(CVal.of(64), CVal.of(64));
            case ArrayT a -> {
                if (a.size() == null) {
                    report(pos, "a resizable tail has no fixed size");
                    yield new Lay(new ErrV(), new ErrV());
                }
                Lay e = layout(a.element(), pos);
                CVal stride = orErr(CVal.binary("roundup", e.size(), e.align()));
                yield new Lay(orErr(CVal.binary("*", a.size(), stride)), e.align());
            }
            case ClassT c -> classLayout(c, pos);
            case JavaPrimT j -> layout(j.sealType(), pos); // held as the Seal number with its values
            case ParamT p -> new Lay(new LayoutOf("sizeof", p), new LayoutOf("alignof", p));
            case ErrorT e -> new Lay(new ErrV(), new ErrV());
            default -> {
                report(pos, t + " has no size");
                yield new Lay(new ErrV(), new ErrV());
            }
        };
    }

    private static CVal orErr(CVal v) {
        return v == null ? new ErrV() : v;
    }

    private Lay classLayout(ClassT t, Pos pos) {
        if (t.cls().dynamic) {
            report(pos, "class " + t.cls().name + " has no fixed size; a variable of type " + t.cls().name
                    + " is a 64-bit handle");
            return new Lay(new ErrV(), new ErrV());
        }
        if (!isConcrete(t)) {
            return new Lay(new LayoutOf("sizeof", t), new LayoutOf("alignof", t));
        }
        Lay cached = layoutCache.get(t);
        if (cached != null) {
            return cached;
        }
        if (!layoutInProgress.add(t)) {
            // Contains itself inline; reported by checkInlineCycles.
            return new Lay(new ErrV(), new ErrV());
        }
        try {
            CVal offset = CVal.of(0);
            CVal align = CVal.of(1);
            for (MemberInfo m : t.cls().storedFields(t.header())) {
                Type ft = fieldType(t, m);
                Lay fl = layout(ft, pos);
                offset = orErr(CVal.binary("+", orErr(CVal.binary("roundup", offset, fl.align())), fl.size()));
                align = orErr(CVal.binary("max", align, fl.align()));
            }
            Lay result = new Lay(orErr(CVal.binary("roundup", offset, align)), align);
            layoutCache.put(t, result);
            return result;
        } finally {
            layoutInProgress.remove(t);
        }
    }

    private static boolean isConcrete(Type t) {
        return switch (t) {
            case ClassT c -> c.valueArgs().stream().allMatch(CVal::isConstant)
                    && c.typeArgs().stream().allMatch(Checker::isConcrete);
            case IntT i -> i.width().isConstant();
            case FixedT f -> f.intBits().isConstant() && f.fracBits().isConstant();
            case ArrayT a -> a.size() != null && a.size().isConstant() && isConcrete(a.element());
            case AddrT a -> isConcrete(a.target());
            case HandleT h -> isConcrete(h.target());
            case ParamT p -> false;
            default -> true;
        };
    }

    /**
     * Checks a concrete instantiation such as `Array<int><<-1>>` by working out
     * its layout with the actual values. Value-dependent errors inside the
     * class are reported here, at the use site (§12).
     */
    private void validateInstance(ClassT t, Pos use) {
        if (t.cls().dynamic || !isConcrete(t) || !validated.add(t)) {
            return;
        }
        if (instantiationDepth >= MAX_INSTANTIATION_DEPTH) {
            report(use, "instantiation of " + t + " is nested more than " + MAX_INSTANTIATION_DEPTH + " levels deep");
            return;
        }
        List<Diagnostic> found = new ArrayList<>();
        captures.push(new Capture(found, false));
        instantiationDepth++;
        try {
            for (MemberInfo m : t.cls().storedFields(t.header())) {
                Type ft = fieldType(t, m);
                if (!t.cls().unique && isUnique(ft)) {
                    reportValue(m.member.pos(), "struct " + t.cls().name + " cannot contain " + ft + ", which is unique");
                }
            }
        } finally {
            instantiationDepth--;
            captures.pop();
        }
        for (String msg : new LinkedHashSet<>(found.stream().map(Diagnostic::message).toList())) {
            reportValue(use, "in " + t + ": " + msg);
        }
    }

    /** A generic class's fields with the actual type arguments, as Box<File>: none may have a destructor (§26). */
    private void validateClassInstance(ClassT t, Pos use) {
        if (t.typeArgs().isEmpty() || isJavaType(t.cls()) || !isConcrete(t) || !validated.add(t)) {
            return;
        }
        for (MemberInfo m : t.cls().storedFields(t.header())) {
            Field f = (Field) m.member;
            String saved = file;
            file = t.cls().file;
            Type declared;
            try {
                declared = quietly(() -> resolveType(f.type(), shapeEnv(t.cls(), t.header(), m),
                        new Opts(false, false, true, null)));
            } finally {
                file = saved;
            }
            if (!hasDestructor(declared)) { // one that has is reported at the field itself
                requireNoDestructor(use, t.toString(), f.name(), quietly(() -> fieldType(t, m)));
            }
        }
    }

    private Type fieldType(ClassT owner, MemberInfo m) {
        Field f = (Field) m.member;
        String saved = file;
        file = owner.cls().file;
        try {
            return resolveType(f.type(), instanceEnv(owner, m), new Opts(false, false, owner.cls().dynamic, null));
        } finally {
            file = saved;
        }
    }

    // ================================================================ inheritance at the type level

    /** The parent of a dynamic class instance (§33), or null when it depends on unknown values. */
    private ClassT parentOf(ClassT t) {
        ClassInfo c = t.cls();
        if (!c.dynamic || c.isRoot() || c.isInterface || t.header() < 0) {
            return null;
        }
        Shape s = c.shapes.get(t.header());
        ExtendsClause literal = null;
        ExtendsClause binding = null;
        ExtendsClause unguarded = null;
        boolean undetermined = false;
        for (ExtendsClause e : s.header.extendsClauses()) {
            if (e.guard() == null) {
                unguarded = e;
            } else if (e.guard().items().stream().allMatch(x -> x instanceof Binder)) {
                binding = e;
            } else if (e.guard().items().size() == t.valueArgs().size()) {
                Boolean m = literalGuardMatches(c, e.guard(), t.valueArgs());
                if (m == null) {
                    undetermined = true;
                } else if (m) {
                    literal = e;
                }
            }
        }
        ExtendsClause chosen = literal != null ? literal : undetermined ? null : binding != null ? binding : unguarded;
        if (chosen == null) {
            return undetermined || c.java != null ? null : objectType(); // java.lang.Object has no parent
        }
        Env env = instanceEnv(t, null);
        if (chosen.guard() != null && chosen == binding) {
            for (int i = 0; i < s.binders.size(); i++) {
                Binder b = (Binder) chosen.guard().items().get(i);
                env.values.put(b.name(), env.values.get(s.binders.get(i).name()));
            }
        }
        String saved = file;
        file = c.file;
        try {
            Type p = quietly(() -> resolveType(chosen.parent(), env, new Opts(false, true, false, null)));
            if (p instanceof ClassT pc && isRawJava(t) && pc.cls().java != null) {
                return new ClassT(pc.cls(), List.of(), List.of(), 0); // a raw type's parent is raw, as in Java
            }
            return p instanceof ClassT pc && pc.cls().dynamic && !pc.cls().isInterface ? pc : null;
        } finally {
            file = saved;
        }
    }

    /** Whether a literal extends guard such as <<0>> matches the values; null if a value is not yet known. */
    private Boolean literalGuardMatches(ClassInfo c, ValueList guard, List<CVal> values) {
        for (int i = 0; i < values.size(); i++) {
            if (!values.get(i).isConstant()) {
                return null;
            }
            Expr e = ((ValueExpr) guard.items().get(i)).expr();
            Const g = quietly(() -> evalConst(e, Env.empty(c)));
            if (!g.value().equals(values.get(i))) {
                return false;
            }
        }
        return true;
    }

    private ClassT objectType() {
        ClassInfo o = classNamed("Object");
        return new ClassT(o, List.of(), List.of(), 0);
    }

    /** Whether {@code sub} is {@code sup}, inherits from it, or implements it (an interface). */
    private boolean isSubclass(ClassT sub, ClassT sup) {
        if (sup.cls().isRoot() && sub.cls().dynamic) {
            return !isJavaType(sub.cls()); // every class and interface handle is an Object, but a Java object isn't Seal's
        }
        if (isJavaObject(sup.cls()) && isJavaType(sub.cls())) {
            return true; // every Java class, interface and array is a java.lang.Object
        }
        ClassT t = sub;
        for (int i = 0; t != null && i < MAX_INSTANTIATION_DEPTH; i++) {
            if (sameInstance(t, sup) || (sup.cls().isInterface && implementsInterface(t, sup, 0))) {
                return true;
            }
            t = parentOf(t);
        }
        return false;
    }

    private boolean implementsInterface(ClassT t, ClassT itf, int depth) {
        if (depth > MAX_INSTANTIATION_DEPTH) {
            return false; // a cycle, reported elsewhere
        }
        for (ClassT i : interfacesOf(t)) {
            if (sameInstance(i, itf) || implementsInterface(i, itf, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** A Java generic without its type arguments: ArrayList where an ArrayList<JavaString> would say them. */
    private static boolean isRawJava(ClassT t) {
        return t.cls().java != null && t.typeArgs().isEmpty() && !t.cls().typeParams.isEmpty();
    }

    /**
     * Whether {@code t} is {@code sup}: the same type, or for one of Java's generics, the same class
     * with arguments sup's take, as Java's wildcards do (List<JavaString> is a List<? extends
     * JavaObject>); a raw one is any of its class, unchecked, as in Java (docs/JAVA.md §4.1).
     */
    private boolean sameInstance(ClassT t, ClassT sup) {
        if (t.equals(sup)) {
            return true;
        }
        if (t.cls() != sup.cls() || t.cls().java == null) {
            return false;
        }
        if (t.typeArgs().isEmpty() || sup.typeArgs().isEmpty()) {
            return true;
        }
        for (int i = 0; i < t.typeArgs().size() && i < sup.typeArgs().size(); i++) {
            if (!contains(sup.typeArgs().get(i), t.typeArgs().get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a type argument fits where another is wanted: the same type, or one the wanted wildcard takes. */
    private boolean contains(Type wanted, Type arg) {
        if (wanted.equals(arg)) {
            return true;
        }
        if (!(wanted instanceof WildT w)) {
            return false;
        }
        Type a = arg instanceof WildT aw ? aw.bound() : arg;
        int kind = arg instanceof WildT aw ? aw.kind() : -1;
        return switch (w.kind()) {
            case 0 -> true;
            case 1 -> kind != 2 && refSubtype(a, w.bound());
            default -> (kind == -1 || kind == 2) && refSubtype(w.bound(), a);
        };
    }

    /** Whether a reference type is another, or below it; any reference is below JavaObject, as a type argument. */
    private boolean refSubtype(Type a, Type b) {
        if (a.equals(b) || a instanceof NullT || (b instanceof HandleT bh && bh.target() instanceof ClassT bc && isJavaObject(bc.cls()))) {
            return true;
        }
        return a instanceof HandleT ah && ah.target() instanceof ClassT ac && b instanceof HandleT bh && bh.target() instanceof ClassT bc
                && isSubclass(ac, bc);
    }

    /**
     * The interfaces a class instance lists in implements(...), or that an interface
     * extends, with its type arguments applied. Not those of its parents.
     */
    public List<ClassT> interfacesOf(ClassT t) {
        ClassInfo c = t.cls();
        if (!c.dynamic || c.isRoot() || t.header() < 0) {
            return List.of();
        }
        List<TypeRef> refs = c.shapes.get(t.header()).header.interfaces();
        if (refs.isEmpty()) {
            return List.of();
        }
        Env env = instanceEnv(t, null);
        String saved = file;
        file = c.file;
        try {
            List<ClassT> out = new ArrayList<>();
            for (TypeRef r : refs) {
                Type it = quietly(() -> resolveType(r, env, new Opts(false, true, false, null)));
                if (it instanceof ClassT ic && ic.cls().isInterface) {
                    out.add(isRawJava(t) && ic.cls().java != null ? new ClassT(ic.cls(), List.of(), List.of(), 0) : ic); // a raw type's are raw
                }
            }
            return out;
        } finally {
            file = saved;
        }
    }

    /** Every interface a class instance implements, through its parents and extended interfaces. */
    public List<ClassT> allInterfacesOf(ClassT t) {
        Set<ClassT> out = new LinkedHashSet<>();
        Deque<ClassT> todo = new ArrayDeque<>();
        ClassT c = t;
        for (int depth = 0; c != null && depth < MAX_INSTANTIATION_DEPTH; depth++, c = parentOf(c)) {
            todo.addAll(interfacesOf(c)); // the limit stops at inheritance cycles, reported elsewhere
        }
        while (!todo.isEmpty() && out.size() < MAX_INSTANTIATION_DEPTH) {
            ClassT i = todo.pop();
            if (out.add(i)) {
                todo.addAll(interfacesOf(i));
            }
        }
        return new ArrayList<>(out);
    }

    // ================================================================ abstract classes and interfaces

    /** Rules for abstract methods and for what an interface may contain (§30.1, §30.2). */
    private void checkAbstractMembers(ClassInfo c) {
        for (MemberInfo m : c.all) {
            Member member = m.member;
            boolean isAbstract = member.modifiers().isAbstract();
            if (c.isInterface) {
                switch (member) {
                    case Method md when md.name().contains(" ") -> { } // a field's accessors, written by the checker
                    case Method md when md.modifiers().isDefault() && md.body() == null -> report(md.pos(),
                            "a default method has the body the classes get: default ... " + md.name() + "(...) { ... }");
                    case Method md when md.modifiers().isDefault() && md.modifiers().isStatic() -> report(md.pos(),
                            "a default method belongs to each object, so it can't be static");
                    case Method md when md.body() != null && !md.modifiers().isDefault() -> report(md.pos(),
                            "interface methods have no body; end " + md.name() + "(...) with ';' and implement it in the"
                                    + " classes, or write `default` before it to give them this body");
                    case Method md when md.modifiers().isStatic() -> report(md.pos(), "interfaces can't have static methods");
                    case Method md when !md.modifiers().decorators().isEmpty() -> report(md.pos(),
                            "interface methods can't have decorators");
                    case Method md when isAbstract -> report(md.pos(), "interface methods are always abstract; leave out `abstract`");
                    case Method md -> { }
                    case Field f when f.modifiers().isStatic() || f.modifiers().isConst() -> report(f.pos(),
                            "an interface's fields belong to the classes implementing it, so they can't be static or const");
                    case Field f when f.modifiers().isAbstract() -> report(f.pos(),
                            "interface fields are abstract already; leave out `abstract`");
                    case Field f -> checkVirtualField(c, f);
                    case Constructor k -> report(k.pos(), "interfaces can't have constructors");
                    case Destructor d -> report(d.pos(), "interfaces can't have destructors");
                }
                continue;
            }
            if (member instanceof Field f && (f.modifiers().isAbstract() || f.modifiers().isDefault())) {
                String kind = f.modifiers().isAbstract() ? "abstract" : "default";
                if (f.modifiers().isAbstract() && f.modifiers().isDefault()) {
                    report(f.pos(), "a field is abstract (the classes below give it a value) or default (they get"
                            + " this one), not both");
                } else if (c.kind != Kind.CLASS || !c.isAbstract) {
                    report(f.pos(), kind + " fields belong in interfaces and abstract classes, for the classes below them"
                            + " to hold; " + c.name + " isn't one");
                } else if (f.modifiers().isStatic() || f.modifiers().isConst()) {
                    report(f.pos(), "a " + kind + " field belongs to each object, so it can't be static or const");
                } else {
                    checkVirtualField(c, f);
                }
                continue;
            }
            if (!(member instanceof Method md)) {
                if (isAbstract) {
                    report(member.pos(), "only methods and fields can be abstract");
                }
                continue;
            }
            if (md.modifiers().isDefault() && !md.name().contains(" ")) {
                report(md.pos(), "default is for an interface's methods, which have no body otherwise; a class's methods"
                        + " have one anyway");
            }
            if (isAbstract) {
                if (c.kind != Kind.CLASS) {
                    report(md.pos(), "only classes can have abstract methods, not a " + c.kind.keyword);
                } else if (!c.isAbstract) {
                    report(md.pos(), md.name() + " is abstract, so " + c.name + " must be too: abstract class " + c.name);
                }
                if (md.body() != null) {
                    report(md.pos(), "abstract method " + md.name() + " can't have a body; end it with ';'");
                }
                if (md.modifiers().isStatic()) {
                    report(md.pos(), "static methods can't be abstract");
                }
                if (md.modifiers().access() == Access.PRIVATE && md.modifiers().friends().isEmpty()) {
                    report(md.pos(), "abstract method " + md.name() + " must be public: subclasses implement it");
                }
                if (hasValueBinders(md)) {
                    report(md.pos(), "abstract methods can't have value binders <<int K>>");
                }
            } else if (md.body() == null && !(c.builtin && isJvmOnly(md))) {
                report(md.pos(), "method " + md.name() + " needs a body"
                        + (c.kind == Kind.CLASS ? ", or `abstract` in an abstract class" : ""));
            }
        }
        if (c.isInterface) {
            for (Shape s : c.shapes) {
                for (TypeRef r : s.header.interfaces()) {
                    Type t = quietly(() -> resolveType(r, shapeEnv(c, s.index, null), new Opts(false, true, false, null)));
                    if (!(t instanceof ClassT ic && ic.cls().isInterface) && !t.isError()) {
                        report(r.pos(), "an interface can only extend interfaces, not " + t);
                    }
                }
            }
        }
    }

    /** An abstract field has no value, and a default one has the value the classes get (§30.4). */
    private void checkVirtualField(ClassInfo c, Field f) {
        if (f.modifiers().isDefault() && f.init() == null) {
            report(f.pos(), "a default field has the value the classes get: default " + typeText(f.type()) + " " + f.name()
                    + " = ...;");
        } else if (!f.modifiers().isDefault() && f.init() != null) {
            report(f.pos(), "an abstract field has no value of its own: each class below gives it one; for a value they"
                    + " all get, write default " + typeText(f.type()) + " " + f.name() + " = ...;");
        } else if (f.modifiers().isDefault() && mentions(f.type(), c.typeParams)) {
            report(f.type().pos(), "a default field's type can't use " + String.join(", ", c.typeParams) + ", the type"
                    + " parameters of " + c.name + ": the classes get the field as it is written");
        }
    }

    private static boolean mentions(TypeRef t, List<String> names) {
        return names.contains(t.name()) || t.typeArgs().stream().anyMatch(a -> mentions(a, names));
    }

    private static String typeText(TypeRef t) {
        return io.github.thelinuxseal.seal.ast.AstPrinter.print(t);
    }

    // ================================================================ abstract and default fields (§30.4)

    /** An abstract or default field, and the interface or abstract class that names it. */
    private record VirtualField(ClassInfo owner, Field field) {
    }

    /**
     * Abstract and default fields (§30.4). The type declaring one gets abstract accessors,
     * `get name()` and `set name(value)`. Each class that holds it gets accessors reading and
     * writing its own field: a class holds it when it declares the field, or, for a default
     * one, when no class above it holds it and it isn't abstract, in which case it gets the
     * field with the default value. Code reaching the field through the declaring type calls
     * the accessors; code reaching it through the class reads the field itself.
     */
    private void synthesizeVirtualFields() {
        for (ClassInfo x : allClasses()) {
            if (!x.dynamic || isJavaType(x)) {
                continue;
            }
            for (Member m : x.decl.members()) {
                if (m instanceof Field f && x.isVirtual(f) && !f.modifiers().isConst()) {
                    Modifiers mods = new Modifiers(List.of(), Access.PUBLIC, List.of(), false, false, !x.isInterface);
                    x.synthesized.add(getter(f.pos(), f.name(), f.type(), mods, false));
                    x.synthesized.add(setter(f.pos(), f.name(), f.type(), mods, false));
                }
            }
        }
        Set<ClassInfo> done = new HashSet<>();
        for (ClassInfo c : allClasses()) {
            if (c.dynamic && !c.isInterface && !isJavaType(c)) {
                fillVirtualFields(c, done);
            }
        }
    }

    private static Method getter(Pos p, String name, TypeRef type, Modifiers mods, boolean withBody) {
        Block body = withBody ? new Block(p, List.of(new Return(p, new Select(p, new This(p), name)))) : null;
        return new Method(p, mods, List.of(), type, "get " + name, null, List.of(), body);
    }

    private static Method setter(Pos p, String name, TypeRef type, Modifiers mods, boolean withBody) {
        Block body = withBody ? new Block(p, List.of(new ExprStmt(p, new Assign(p, null, new Select(p, new This(p), name),
                new Name(p, "value"))))) : null;
        return new Method(p, mods, List.of(), new TypeRef(p, "void", List.of(), null, List.of()), "set " + name, null,
                List.of(new Param(p, type, "value")), body);
    }

    /** The abstract and default fields a class's interfaces and the classes above it name, by name. */
    private Map<String, VirtualField> wantedFields(ClassInfo c) {
        Map<String, VirtualField> wanted = new LinkedHashMap<>();
        if (c.shapes.isEmpty()) {
            return wanted;
        }
        ClassT self = selfType(c, 0);
        List<ClassInfo> owners = new ArrayList<>();
        for (ClassT p = self; p != null && owners.size() < MAX_INSTANTIATION_DEPTH; p = parentOf(p)) {
            owners.add(p.cls());
        }
        allInterfacesOf(self).forEach(i -> owners.add(i.cls()));
        for (ClassInfo x : owners) {
            for (Member m : x.decl.members()) {
                if (m instanceof Field f && x.isVirtual(f) && !f.modifiers().isConst()) {
                    VirtualField had = wanted.get(f.name());
                    if (had == null || (!had.field().modifiers().isDefault() && f.modifiers().isDefault())) {
                        wanted.put(f.name(), new VirtualField(x, f)); // a default one supplies the value
                    }
                }
            }
        }
        return wanted;
    }

    /** The field a class itself holds by this name (declared, or given it by the checker), or null. */
    private static Field heldField(ClassInfo c, String name) {
        List<Member> members = new ArrayList<>(c.decl.members());
        members.addAll(c.synthesized);
        for (Member m : members) {
            if (m instanceof Field f && f.name().equals(name) && !f.modifiers().isStatic() && !c.isVirtual(f)) {
                return f;
            }
        }
        return null;
    }

    private void fillVirtualFields(ClassInfo c, Set<ClassInfo> done) {
        if (!done.add(c) || c.shapes.isEmpty()) {
            return;
        }
        ClassT parent = parentOf(selfType(c, 0));
        if (parent != null && !parent.cls().isInterface) {
            fillVirtualFields(parent.cls(), done); // what the classes above hold first
        }
        file = c.file;
        Modifiers concrete = new Modifiers(List.of(), Access.PUBLIC, List.of(), false, false, false);
        for (VirtualField v : wantedFields(c).values()) {
            String name = v.field().name();
            Field own = heldField(c, name);
            boolean above = false;
            for (ClassT p = parent; p != null && !above; p = parentOf(p)) {
                above = heldField(p.cls(), name) != null;
            }
            if (own != null) {
                if (!c.synthesized.contains(own)) {
                    requireValue(c, own, v);
                }
                c.synthesized.add(getter(own.pos(), name, own.type(), concrete, true));
                c.synthesized.add(setter(own.pos(), name, own.type(), concrete, true));
            } else if (above || c.isAbstract) {
                // held by a class above, or left to the classes below
            } else if (v.field().modifiers().isDefault()) {
                Field f = v.field();
                TypeRef type = qualifiedRef(f.type(), v.owner());
                Access access = v.owner().isInterface ? Access.PUBLIC : f.modifiers().access();
                Field held = new Field(f.pos(), new Modifiers(List.of(), access, f.modifiers().friends(), false, false, false),
                        type, name, null, f.init());
                c.synthesized.add(held);
                c.synthesized.add(getter(f.pos(), name, type, concrete, true));
                c.synthesized.add(setter(f.pos(), name, type, concrete, true));
            } else {
                report(c.decl.pos(), c.name + " must hold the field " + name + " of " + v.owner().name + ": declare it, as in "
                        + typeText(v.field().type()) + " " + name + " = ...;");
            }
        }
    }

    /**
     * A class filling in an abstract or default field gives it a value (§30.4): where it declares
     * it, or by assigning it in every constructor it has.
     */
    private void requireValue(ClassInfo c, Field own, VirtualField v) {
        if (own.init() != null) {
            return;
        }
        List<Constructor> ctors = c.decl.members().stream().filter(m -> m instanceof Constructor)
                .map(m -> (Constructor) m).toList();
        boolean everyOne = !ctors.isEmpty() && ctors.stream().allMatch(k -> k.body().stmts().stream().anyMatch(st ->
                st instanceof ExprStmt es && es.expr() instanceof Assign a && a.op() == null
                        && ((a.target() instanceof Name n && n.name().equals(own.name()))
                            || (a.target() instanceof Select sel && sel.target() instanceof This && sel.name().equals(own.name())))));
        if (!everyOne) {
            report(own.pos(), own.name() + " fills in the " + (v.field().modifiers().isDefault() ? "default" : "abstract")
                    + " field of " + v.owner().name + ", so it needs a value: give it one here (" + typeText(own.type()) + " "
                    + own.name() + " = ...), or assign it in every constructor");
        }
    }

    /** A type as written in one type's file, with its classes named in full, to use in another's (§2.4). */
    private TypeRef qualifiedRef(TypeRef t, ClassInfo context) {
        ClassInfo named = lookupClass(t.name(), context);
        String name = named != null ? named.qualifiedName() : t.name();
        List<TypeRef> args = t.typeArgs().stream().map(a -> qualifiedRef(a, context)).toList();
        return new TypeRef(t.pos(), name, args, t.valueArgs(), t.arrayDims());
    }

    /** A field filling in another's must have its type (§30.4), and can't be a unique, which its getter would copy. */
    private void checkVirtualFieldTypes(ClassInfo c) {
        if (!c.dynamic || c.shapes.isEmpty()) {
            return;
        }
        ClassT self = selfType(c, 0);
        List<ClassT> owners = new ArrayList<>(allInterfacesOf(self));
        for (ClassT p = self; p != null && owners.size() < MAX_INSTANTIATION_DEPTH; p = parentOf(p)) {
            owners.add(p);
        }
        for (MemberInfo m : c.all) {
            if (!(m.member instanceof Field own) || own.modifiers().isStatic() || c.isVirtual(own)) {
                continue;
            }
            for (ClassT x : owners) {
                if (x.cls() == c && !c.isVirtual(own)) {
                    continue;
                }
                for (MemberInfo xm : x.cls().all) {
                    if (xm.member instanceof Field xf && xf.name().equals(own.name()) && x.cls().isVirtual(xf)) {
                        Type want = quietly(() -> fieldType(x, xm));
                        Type got = quietly(() -> fieldType(self, m));
                        if (!want.isError() && !got.isError() && !want.equals(got)) {
                            report(own.pos(), c.name + "." + own.name() + " is " + got + ", but the field of " + x.cls().name
                                    + " it fills in is " + want);
                        } else if (isUnique(got)) {
                            report(own.pos(), own.name() + " fills in " + x.cls().name + "'s field, which is read through a"
                                    + " getter that would copy it, so it can't be a unique (" + got + ")");
                        }
                    }
                }
            }
        }
    }

    private static boolean hasValueBinders(Method md) {
        return md.params().stream().anyMatch(p -> containsBinder(p.type()));
    }

    private static boolean containsBinder(TypeRef t) {
        if (t.valueArgs() != null && t.valueArgs().items().stream().anyMatch(i -> i instanceof Binder)) {
            return true;
        }
        return t.typeArgs().stream().anyMatch(Checker::containsBinder);
    }

    /**
     * A class's implements(...) must name interfaces, and a class that isn't abstract
     * must implement every method of its interfaces and every abstract method it inherits.
     */
    private void checkImplements(ClassInfo c) {
        if (c.kind != Kind.CLASS) {
            return;
        }
        for (Shape s : c.shapes) {
            Env env = shapeEnv(c, s.index, null);
            for (TypeRef r : s.header.interfaces()) {
                Type t = resolveType(r, env, new Opts(false, true, false, null));
                if (t instanceof ClassT ic && !ic.cls().isInterface) {
                    report(r.pos(), ic.cls().name + " is a " + ic.cls().kind.keyword + ", not an interface"
                            + (ic.cls().dynamic ? "; use extends(" + ic.cls().name + ") for a parent class" : ""));
                } else if (!(t instanceof ClassT) && !t.isError()) {
                    report(r.pos(), t + " is not an interface");
                }
            }
            ClassT self = selfType(c, s.index);
            // What must be implemented: interface methods, and abstract methods of the class and its parents.
            List<ClassT> owners = new ArrayList<>(allInterfacesOf(self));
            // The class's own abstract methods are reported by checkAbstractMembers if it isn't abstract.
            ClassT p = parentOf(self);
            for (int depth = 0; p != null && depth < MAX_INSTANTIATION_DEPTH; depth++, p = parentOf(p)) {
                owners.add(p);
            }
            Set<String> reported = new HashSet<>();
            for (ClassT owner : owners) {
                for (MemberInfo m : owner.cls().all) {
                    if (!(m.member instanceof Method md) || md.body() != null || isJvmOnly(md) || !m.existsIn(owner.header())
                            || md.modifiers().isStatic() || md.name().contains(" ")) {
                        continue; // a field's accessors: its holder is checked by fillVirtualFields
                    }
                    if (javaMembers.get(md) instanceof JavaClasses.JMember jm
                            && ((jm.flags() & JavaClasses.ABSTRACT) == 0 || isObjectMethod(jm))) {
                        continue; // a Java interface's default methods, and Object's, are implemented already
                    }
                    List<Type> params = paramTypes(owner, m, Map.of());
                    String sig = md.name() + params.stream()
                            .map(t -> t instanceof HandleT h ? h.target().toString() : t.toString()) // as written: Animal
                            .collect(Collectors.joining(", ", "(", ")"));
                    Impl impl = findImplementation(self, md.name(), params);
                    if ((impl == null || ((Method) impl.member.member).body() == null) && javaMembers.containsKey(md)
                            && javaProvides(self, md.name(), params)) {
                        continue; // a Java class or interface above has a method doing it: ArrayList's, List's default reversed()
                    }
                    if (impl == null || ((Method) impl.member.member).body() == null) {
                        if (!c.isAbstract && reported.add(sig)) {
                            report(s.header.pos(), c.name + " must implement " + owner.cls().name + "." + sig
                                    + ", or be declared `abstract class " + c.name + "`");
                        }
                        continue;
                    }
                    if (impl.owner.cls() != c) {
                        continue; // inherited: checked where it is declared
                    }
                    Method im = (Method) impl.member.member;
                    Type want = returnTypeOf(owner, m, Map.of());
                    Type got = returnTypeOf(impl.owner, impl.member, Map.of());
                    if (!want.equals(got) && !want.isError() && !got.isError() && reported.add(sig + " return")) {
                        report(im.pos(), c.name + "." + im.name() + " returns " + got + ", but "
                                + owner.cls().name + "." + sig + " returns " + want);
                    }
                    if (owner.cls().isInterface && im.modifiers().access() != Access.PUBLIC
                            && reported.add(sig + " access")) {
                        report(im.pos(), c.name + "." + im.name() + " implements " + owner.cls().name + "." + md.name()
                                + ", so it must be public");
                    }
                }
            }
        }
    }

    private record Impl(ClassT owner, MemberInfo member) {
    }

    /** The most derived instance method named {@code name} with these parameter types, or null. */
    private Impl findImplementation(ClassT t, String name, List<Type> params) {
        ClassT c = t;
        for (int depth = 0; c != null && depth < MAX_INSTANTIATION_DEPTH; depth++, c = parentOf(c)) {
            List<MemberInfo> list = c.cls().members.get(name);
            if (list == null) {
                continue;
            }
            for (MemberInfo m : list) {
                if (m.member instanceof Method md && !md.modifiers().isStatic() && m.existsIn(c.header())
                        && paramTypes(c, m, Map.of()).equals(params)) {
                    return new Impl(c, m);
                }
            }
        }
        return null;
    }

    // ================================================================ bodies

    /** State while checking one member body in one shape. */
    private final class Body {
        final ClassInfo cls;
        final int shape;
        final Env env;
        final ClassT self;
        final boolean isStatic;
        final boolean inConstructor;
        Type returnType;
        String memberName;
        int loopDepth = 0;
        /** The labels of the loops around the statement being checked, innermost first. */
        Deque<String> labels = new ArrayDeque<>();
        /** The switch expressions whose case blocks are being checked, innermost first. */
        Deque<SwitchCtx> switchExprs = new ArrayDeque<>();
        final Map<SwitchExpr, SwitchInfo> switchExprInfo = new IdentityHashMap<>();
        /** With -Wassert, each assert as the if-and-throw it runs. */
        final Map<Assert, Stmt> assertions = new IdentityHashMap<>();
        /** Statements that stand for others the checker wrote: xs[i] += v on an object element (§46). */
        final Map<Stmt, Stmt> statementSteps = new IdentityHashMap<>();
        /** p.x() calls that read a record's component, with the component's type. */
        final Map<Call, Type> componentReads = new IdentityHashMap<>();
        /** While a block lambda is checked only to learn what it returns: the types of its returns. */
        List<Type> inferredReturns;
        /** The lambdas being checked, innermost first, collecting what they capture. */
        final Deque<LambdaCtx> lambdaCtx = new ArrayDeque<>();
        final Map<Lambda, LambdaInfo> lambdas = new IdentityHashMap<>();
        final Deque<Map<String, Type>> scopes = new ArrayDeque<>();
        /** Resolved types of expressions, for lvalue analysis. */
        final Map<Expr, Type> types = new IdentityHashMap<>();
        /** What each Name resolved to. */
        final Map<Name, String> nameKinds = new IdentityHashMap<>();
        /** Members found by each Select. */
        final Map<Select, MemberInfo> selected = new IdentityHashMap<>();
        /** For field Names and Selects: the instance type the field belongs to (a parent, if inherited). */
        final Map<Expr, ClassT> owners = new IdentityHashMap<>();
        /** The method, constructor or operator each call resolved to. */
        final Map<Expr, CallInfo> calls = new IdentityHashMap<>();
        final Map<VarDecl, Type> locals = new IdentityHashMap<>();
        /** Each for-each loop as the ordinary loop it stands for (§41.3). */
        final Map<Stmt, Stmt> desugared = new IdentityHashMap<>();
        /** The variables of the try-with-resources being checked, which can't be assigned (§41.4). */
        final Set<String> resources = new HashSet<>();
        final Map<Switch, SwitchInfo> switches = new IdentityHashMap<>();
        final Map<Catch, ClassT> catchTypes = new IdentityHashMap<>();
        /** Expressions that stand for others the checker wrote, e.g. 5 + "x" for "" + 5 + "x". */
        final Map<Expr, Expr> rewritten = new IdentityHashMap<>();
        final Map<IncDec, Assign> elementSteps = new IdentityHashMap<>();
        final Map<Expr, JavaCall> javaCalls = new IdentityHashMap<>();
        /** The type each call's value goes to, where one is wanted. */
        final Map<Call, Type> callExpected = new IdentityHashMap<>();
        /** Compound assignments computed in a wider type and narrowed back: i += 2.5 (§41.6). */
        final Map<Assign, Type> narrowed = new IdentityHashMap<>();
        /**
         * Operators the checker wrote: x + v for x += v, which -WautoCast names as +=, and
         * xs[i] + 1 for xs[i]++ (mapped to null), whose operand casts it doesn't report.
         */
        final Map<Binary, String> written = new IdentityHashMap<>();
        /** Values narrowed where they are stored or passed, to the type each becomes: int<<8>> b = i (§41.6). */
        final Map<Expr, Type> implicitCasts = new IdentityHashMap<>();
        int switchDepth = 0;
        /** Parameter names: a unique parameter is lent, so it can't be returned or stored. */
        final Set<String> paramNames = new HashSet<>();
        /** For Names that refer to fields: which field. */
        final Map<Name, MemberInfo> fieldOf = new IdentityHashMap<>();

        Body(ClassInfo cls, int shape, Env env, boolean isStatic, boolean inConstructor, Type returnType, String memberName) {
            this(cls, shape, env, shape < 0 ? null : selfType(cls, shape), isStatic, inConstructor, returnType, memberName);
        }

        Body(ClassInfo cls, int shape, Env env, ClassT self, boolean isStatic, boolean inConstructor, Type returnType,
             String memberName) {
            this.cls = cls;
            this.shape = shape;
            this.env = env;
            this.self = self;
            this.isStatic = isStatic;
            this.inConstructor = inConstructor;
            this.returnType = returnType;
            this.memberName = memberName;
            scopes.push(new HashMap<>());
        }
    }

    private void checkBodies(ClassInfo c) {
        for (MemberInfo m : c.all) {
            for (int s : shapesOf(c, m)) {
                Env env = shapeEnv(c, s, m);
                boolean isStatic = m.member.modifiers().isStatic();
                switch (m.member) {
                    case Field f -> {
                        if (f.init() != null) {
                            Type ft = quietly(() -> resolveType(f.type(), env, new Opts(false, false, c.dynamic, null)));
                            Body b = new Body(c, s, env, isStatic, false, Type.VOID, f.name());
                            if (f.modifiers().isConst()) {
                                Const v = evalConst(f.init(), env);
                                if (!(v.value() instanceof ErrV)) {
                                    requireAssignable(b, v.type(), ft, f.init().pos(), "initializer of " + f.name());
                                }
                            } else {
                                Type t = checkExpr(b, f.init(), ft);
                                requireConvertible(b, f.init(), t, ft, "initializer of " + f.name());
                                requireNotCopied(b, f.init(), ft, "initializer of " + f.name());
                            }
                        }
                    }
                    case Method md when isNative(md) -> {
                        // Implemented by the runtime's Natives.
                    }
                    case Method md -> {
                        Env menv = env.copy();
                        addMethodTypes(menv, m, null);
                        List<Type> params = new ArrayList<>();
                        Map<String, Type> binders = new LinkedHashMap<>();
                        for (Param p : md.params()) {
                            params.add(quietly(() -> resolveType(p.type(), menv, new Opts(false, false, false, binders))));
                        }
                        Type ret = quietly(() -> resolveType(md.returnType(), menv, new Opts(true, false, false, null)));
                        Body b = new Body(c, s, menv, isStatic, false, ret, md.name());
                        for (int i = 0; i < params.size(); i++) {
                            b.scopes.peek().put(md.params().get(i).name(), params.get(i));
                        b.paramNames.add(md.params().get(i).name());
                        }
                        if (md.body() == null) {
                            continue; // abstract: only its signature is checked
                        }
                        checkBlock(b, md.body(), false);
                        if (!(ret instanceof VoidT) && !ret.isError() && !returns(md.body())) {
                            report(md.pos(), "method " + md.name() + " can reach its end without returning a " + ret);
                        }
                        if (md.modifiers().isPure()) {
                            for (Purity.Problem p : Purity.of(this, new TypedBody(c.file, b, params, ret, List.of()), md)) {
                                report(p.pos(), p.message());
                            }
                        }
                    }
                    case Constructor ctor -> {
                        Env menv = env.copy();
                        Body b = new Body(c, s, menv, false, true, Type.VOID, "new");
                        Map<String, Type> binders = new LinkedHashMap<>();
                        for (Param p : ctor.params()) {
                            b.scopes.peek().put(p.name(), quietly(() -> resolveType(p.type(), menv, new Opts(false, false, false, binders))));
                            b.paramNames.add(p.name());
                        }
                        checkBlock(b, ctor.body(), false);
                    }
                    case Destructor d -> {
                        Body b = new Body(c, s, env, false, false, Type.VOID, "delete");
                        checkBlock(b, d.body(), false);
                    }
                }
            }
        }
    }

    private void checkSwitch(Body b, Switch sw) {
        SwitchParts parts = checkSwitchParts(b, sw.pos(), sw.subject(), sw.cases(), c -> {
            b.switchDepth++;
            b.scopes.push(new HashMap<>());
            for (Stmt s : c.body()) {
                checkStmt(b, s);
            }
            b.scopes.pop();
            b.switchDepth--;
        });
        b.switches.put(sw, parts.info());
    }

    /** What checking a switch's subject and labels finds: its tests, and which constants it covers. */
    private record SwitchParts(SwitchInfo info, Type subject, ClassInfo enumClass, boolean hasDefault, Set<String> keys) {
    }

    /**
     * The subject (kept in a hidden local) and labels of a switch statement or expression, with
     * each case's body checked by {@code body} while the subject is in scope.
     */
    private SwitchParts checkSwitchParts(Body b, Pos p, Expr subjectExpr, List<Case> cases,
                                         java.util.function.Consumer<Case> body) {
        b.scopes.push(new HashMap<>());
        String hidden = "$switch" + (++hiddenNames);
        VarDecl subject = new VarDecl(p, null, hidden, subjectExpr);
        checkStmt(b, subject);
        Type t = b.locals.get(subject);
        ClassInfo enumClass = t instanceof ClassT ct && ct.cls().kind == Kind.ENUM ? ct.cls() : null;
        if (t instanceof JavaPrimT j) {
            report(subjectExpr.pos(), "switch works on integers, characters, strings and enums, not " + t + "; " + t
                    + " is Java's " + j.javaName() + ", which a cast makes Seal's: switch ((" + j.sealType() + ")x)");
            b.scopes.peek().put(hidden, Type.ERROR); // its cases aren't checked against it
        } else if (!t.isError() && !t.isInteger() && !(t instanceof ClassT ct && (ct.cls().name.equals("string") || enumClass != null))) {
            report(subjectExpr.pos(), "switch works on integers, characters, strings and enums, not " + t);
        }
        boolean arrows = !cases.isEmpty() && cases.get(0).arrow();
        boolean seenDefault = false;
        Set<String> seen = new HashSet<>();
        List<List<Expr>> tests = new ArrayList<>();
        for (Case c : cases) {
            if (c.arrow() != arrows) {
                report(c.pos(), "a switch uses either `case x:` or `case x ->` throughout, not both");
            }
            if (c.isDefault() && seenDefault) {
                report(c.pos(), "a switch has only one default");
            }
            seenDefault |= c.isDefault();
            List<Expr> caseTests = new ArrayList<>();
            for (Expr label : c.labels()) {
                Expr l = label;
                if (enumClass != null && label instanceof Name n && enumClass.members.containsKey(n.name())) {
                    l = new Select(n.pos(), new Name(n.pos(), enumClass.name), n.name()); // case RED: means Color.RED
                }
                String key = switchLabelKey(b, l, enumClass);
                if (key == null) {
                    report(label.pos(), "a case label must be a constant: a number, character, string literal or enum constant");
                    continue;
                }
                if (!seen.add(key)) {
                    report(label.pos(), "duplicate case " + key);
                }
                Expr test = new Binary(label.pos(), BinaryOp.EQ, new Name(label.pos(), hidden), l);
                if (!t.isError()) {
                    checkExpr(b, test, null);
                }
                caseTests.add(test);
            }
            tests.add(caseTests);
            body.accept(c);
        }
        b.scopes.pop();
        return new SwitchParts(new SwitchInfo(subject, tests), t, enumClass, seenDefault, seen);
    }

    /**
     * A switch used as a value (§41.3). Each case gives one, with an expression or with yield
     * in a block, or throws; the value's type is what the cases' values meet in, as for ?:.
     * There must be a default, unless the cases name every constant of an enum.
     */
    private Type checkSwitchExpr(Body b, SwitchExpr se, Type expected) {
        SwitchCtx ctx = new SwitchCtx(expected instanceof LambdaT ? null : expected, new ArrayList<>());
        int savedLoops = b.loopDepth;
        int savedSwitches = b.switchDepth;
        Deque<String> savedLabels = b.labels;
        SwitchParts parts = checkSwitchParts(b, se.pos(), se.subject(), se.cases(), c -> {
            // A case can't leave the switch but by giving its value or throwing: no return, break or continue out.
            b.switchExprs.push(ctx);
            b.loopDepth = 0;
            b.switchDepth = 0;
            b.labels = new ArrayDeque<>();
            b.scopes.push(new HashMap<>());
            for (Stmt s : c.body()) {
                checkStmt(b, s);
            }
            b.scopes.pop();
            b.switchExprs.pop();
            b.loopDepth = savedLoops;
            b.switchDepth = savedSwitches;
            b.labels = savedLabels;
            boolean last = c == se.cases().get(se.cases().size() - 1);
            Stmt end = c.body().isEmpty() ? null : c.body().get(c.body().size() - 1);
            boolean reported = end instanceof Return || end instanceof Break || end instanceof Continue;
            if ((c.arrow() || last) && !reported && c.body().stream().noneMatch(Checker::endsCase)) {
                report(c.pos(), c.arrow() ? "a case block of a switch expression ends with yield and its value, or a throw"
                        : "the last case of a switch expression ends with yield and its value, or a throw");
            }
        });
        b.switchExprInfo.put(se, parts.info());
        if (parts.subject().isError()) {
            return Type.ERROR;
        }
        if (!parts.hasDefault()) {
            List<String> missing = parts.enumClass() == null ? null : enumConstants(parts.enumClass()).stream()
                    .filter(k -> !parts.keys().contains(parts.enumClass().name + "." + k)).toList();
            if (missing == null || !missing.isEmpty()) {
                report(se.pos(), "a switch expression needs a value for every case: add a default"
                        + (missing == null ? "" : ", or cases for " + String.join(", ", missing)));
                return Type.ERROR;
            }
        }
        List<Type> types = new ArrayList<>();
        for (Yield y : ctx.yields()) {
            Type yt = b.types.get(y.value());
            if (yt == null || yt.isError()) {
                return Type.ERROR;
            }
            types.add(yt);
        }
        if (types.isEmpty()) {
            if (se.cases().stream().allMatch(c -> !c.body().isEmpty() && c.body().get(c.body().size() - 1) instanceof Throw)) {
                report(se.pos(), "every case of this switch expression throws, so it has no value");
            }
            return Type.ERROR;
        }
        Type t;
        if (ctx.expected() != null && types.stream().allMatch(x -> assignable(x, ctx.expected()))) {
            t = ctx.expected();
        } else {
            t = types.get(0);
            for (int i = 1; i < types.size(); i++) {
                Type met = meet(t, types.get(i));
                if (met == null) {
                    report(ctx.yields().get(i).value().pos(), "the cases of a switch expression give different types: "
                            + t + " and " + types.get(i));
                    return Type.ERROR;
                }
                t = met;
            }
            t = conditionalDefault(t); // all constants: an int, a double or a string
        }
        if (isUnique(t)) {
            report(se.pos(), "a switch expression can't give a unique (" + t + "), which can't be copied out of its case");
            return Type.ERROR;
        }
        for (Yield y : ctx.yields()) {
            autoCast(y.value().pos(), b.types.get(y.value()), t, "switch case");
        }
        return t;
    }

    /** A switch expression being checked: the type its context wants, and its cases' yields. */
    private record SwitchCtx(Type expected, List<Yield> yields) {
    }

    /** Whether a statement of a switch expression's case block ends it: yield, throw, or both branches of an if. */
    private static boolean endsCase(Stmt s) {
        return switch (s) {
            case Yield y -> true;
            case Throw t -> true;
            case Block b -> b.stmts().stream().anyMatch(Checker::endsCase);
            case If i -> i.otherwise() != null && endsCase(i.then()) && endsCase(i.otherwise());
            case Try t -> (t.finallyBlock() != null && endsCase(t.finallyBlock()))
                    || (endsCase(t.body()) && t.catches().stream().allMatch(c -> endsCase(c.body())));
            default -> false;
        };
    }

    /** An enum's constants, in order: its static fields made with new (only constants may call its constructors). */
    private static List<String> enumConstants(ClassInfo e) {
        List<String> out = new ArrayList<>();
        for (MemberInfo m : e.all) {
            if (m.member instanceof Field f && f.modifiers().isStatic() && f.init() instanceof New) {
                out.add(f.name());
            }
        }
        return out;
    }

    /**
     * `assert cond : message;` (§41.4). With -Wassert it is `if (!cond) throw new
     * AssertionError(message)`, the message being the condition as written if none is given;
     * without, it is still checked, but does nothing, as in Java.
     */
    private void checkAssert(Body b, Assert a) {
        Pos p = a.pos();
        Expr message = a.message() == null ? new StringLit(p, a.text())
                : new Binary(p, BinaryOp.ADD, new StringLit(p, ""), a.message());
        if (warn.contains(Warning.ASSERT)) {
            TypeRef error = new TypeRef(p, "AssertionError", List.of(), null, List.of());
            Stmt check = new If(p, new Unary(p, UnaryOp.NOT, a.cond()), new Throw(p, new New(p, error, List.of(message))), null);
            b.assertions.put(a, check);
            checkStmt(b, check);
        } else {
            requireCondition(b, a.cond());
            if (a.message() != null) {
                checkExpr(b, message, null);
            }
        }
    }

    /** A constant case label as text, to find duplicates; null if it isn't a constant. */
    private String switchLabelKey(Body b, Expr label, ClassInfo enumClass) {
        return switch (label) {
            case IntLit i -> i.value().toString();
            case Unary u when u.op() == UnaryOp.NEG && u.operand() instanceof IntLit i -> i.value().negate().toString();
            case StringLit s -> "\"" + s.value() + "\"";
            case Select s when enumClass != null && s.target() instanceof Name n && n.name().equals(enumClass.name)
                    && enumClass.members.containsKey(s.name()) -> enumClass.name + "." + s.name();
            default -> null;
        };
    }

    /** Whether a call is to System.rawAssembly (§42.3), which generates no code. */
    public boolean isRawAssembly(CallInfo ci) {
        if (ci == null || ci.member() == null || !(ci.member().member instanceof Method md)
                || !md.name().equals("rawAssembly") || !md.modifiers().isStatic()) {
            return false;
        }
        ClassInfo c = declaringClass(ci.member());
        return c.builtin && c.name.equals("System");
    }

    /** How a compound operator is written: += for ADD, <<= for SHIFT. */
    private static String compoundSymbol(BinaryOp op) {
        return op == BinaryOp.SHIFT ? "<<=" : op.symbol + "=";
    }

    /** Why x op= y can't be used on a type: its compound operator isn't declared, and doesn't come from op. */
    private String noCompound(ClassT ct, BinaryOp op) {
        String sym = compoundSymbol(op);
        String constant = OPERATORS.entrySet().stream().filter(e -> e.getValue().equals(sym)).map(Map.Entry::getKey)
                .findFirst().orElse("?");
        return ct + " does not define operator " + sym + "; declare it with @overload(Operator." + constant
                + "), a method that changes the object itself" + (hasOperator(ct, op.symbol)
                        ? " (it doesn't come from " + op.symbol + ", §46)" : "");
    }

    /** Whether a type is a handle to an Exception (or a subclass), or to one of Java's Throwables (docs/JAVA.md §4). */
    private boolean isException(Type t) {
        ClassInfo e = classNamed("Exception");
        if (t instanceof HandleT h && h.target() instanceof ClassT ct && ct.cls().java != null) {
            ClassInfo throwable = javaClass("java.lang.Throwable");
            return throwable != null && isSubclass(ct, new ClassT(throwable, List.of(), List.of(), 0));
        }
        return e != null && t instanceof HandleT h && h.target() instanceof ClassT ct
                && isSubclass(ct, new ClassT(e, List.of(), List.of(), 0));
    }

    /** The type of a checker-written `var`: its initializer's, with constants at their defaults. */
    private Type inferredType(Body b, Expr init) {
        Type t = defaultOf(checkExpr(b, init, null));
        return t instanceof IntConstT ic ? (ic.isChar() ? Type.CHAR : Type.INT) : t instanceof FloatConstT ? Type.DOUBLE : t;
    }

    private int hiddenNames = 0;

    /**
     * Rewrites `for (T x : items) body` as an ordinary loop over an array (by index), a
     * string (its characters), an Iterable (its iterator), or an object with size() and
     * get(int) (by index). Null, with an error reported, if `items` is none of those.
     */
    private Stmt desugarForEach(Body b, ForEach fe) {
        Pos p = fe.pos();
        Expr items = fe.iterable();
        Type t;
        if (items instanceof ArrayLit al) {
            // [1, 2, 3] takes the loop variable's type for its elements.
            Type elem = resolveType(fe.type(), b.env, Opts.PLAIN);
            if (elem.isError()) {
                return null;
            }
            t = checkExpr(b, items, new ArrayT(elem, null));
            if (t.isError()) {
                return null;
            }
            TypeRef arrayType = new TypeRef(p, "array", List.of(fe.type()),
                    new ValueList(p, List.of(new ValueExpr(p, intLit(p, al.elements().size())))), List.of());
            String a = "$items" + (++hiddenNames);
            return new Block(p, List.of(new VarDecl(p, arrayType, a, items),
                    indexLoop(fe, new Name(p, a), intLit(p, al.elements().size()), "")));
        }
        t = defaultLiteral(b, items, checkExpr(b, items, null));
        if (t.isError()) {
            return null;
        }
        ClassInfo iterable = isJavaValue(t) && javaElement(t) == null ? javaClass("java.lang.Iterable") : null;
        if (iterable != null && !(t instanceof HandleT h && h.target() instanceof ClassT ct && asSuper(ct, iterable) != null)) {
            report(items.pos(), "for-each goes over a Java Iterable or array, not " + (t instanceof HandleT h
                    && h.target() instanceof ClassT ct ? ct.cls().name : t));
            return null;
        }
        boolean simple = isSimplePath(items);
        String hidden = "$items" + (++hiddenNames);
        Expr source = simple ? items : new Name(p, hidden);
        List<Stmt> out = new ArrayList<>();
        if (!simple) {
            out.add(new VarDecl(p, null, hidden, items)); // evaluated once
        }
        if (javaElement(t) != null) {
            // Over a Java array: an index loop to its length, a JavaInt.
            out.add(indexLoop(fe, source, new Cast(p, new TypeRef(p, "int", List.of(), null, List.of()),
                    new Select(p, source, "length")), ""));
            return new Block(p, out);
        }
        if (iterable != null) {
            // Over a Java Iterable (docs/JAVA.md §4.1): its iterator, whose hasNext() is a JavaBoolean.
            String it = "$iterator" + (++hiddenNames);
            out.add(new VarDecl(p, null, it, call(p, source, "iterator")));
            Block body = new Block(p, List.of(new VarDecl(p, fe.type(), fe.name(), call(p, new Name(p, it), "next")),
                    fe.body()));
            out.add(new While(p, new Cast(p, new TypeRef(p, "bit", List.of(), null, List.of()), call(p, new Name(p, it), "hasNext")),
                    body));
            return new Block(p, out);
        }
        switch (t) {
            case ArrayT at -> {
                BigInteger n = at.size() == null ? null : at.size().intValue();
                if (n != null) {
                    out.add(indexLoop(fe, source, intLit(p, n.longValueExact()), ""));
                } else if (fixedArrayMethods(at) != null) {
                    out.add(indexLoop(fe, source, call(p, source, "length"), "")); // array<T><<N>> with N not yet known
                } else {
                    report(items.pos(), "for-each needs an array of known size, not " + t);
                    return null;
                }
            }
            case ClassT ct when ct.cls().name.equals("string") ->
                    out.add(indexLoop(fe, source, call(p, source, "length"), "charAt"));
            case HandleT h when h.target() instanceof ClassT ct && implementsIterable(ct) -> {
                String it = "$iterator" + (++hiddenNames);
                out.add(new VarDecl(p, null, it, call(p, source, "iterator")));
                Block body = new Block(p, List.of(new VarDecl(p, fe.type(), fe.name(), call(p, new Name(p, it), "next")),
                        fe.body()));
                out.add(new While(p, call(p, new Name(p, it), "hasNext"), body));
            }
            case HandleT h when h.target() instanceof ClassT ct && hasMethod(ct, "size") && hasMethod(ct, "get") ->
                    out.add(indexLoop(fe, source, call(p, source, "size"), "get"));
            case ClassT ct when hasMethod(ct, "length") && hasMethod(ct, "get") ->
                    out.add(indexLoop(fe, source, call(p, source, "length"), "get")); // array<T>
            default -> {
                report(items.pos(), "for-each goes over an array, a string, an Iterable, or an object with"
                        + " size() and get(int); " + t + " is none of those");
                return null;
            }
        }
        return new Block(p, out);
    }

    /** for (int $i = 0; $i < count; $i += 1) { T x = items[$i] (or items.getter($i)); body } */
    private Stmt indexLoop(ForEach fe, Expr items, Expr count, String getter) {
        Pos p = fe.pos();
        String i = "$index" + (++hiddenNames);
        TypeRef intType = new TypeRef(p, "int", List.of(), null, List.of());
        Expr element = getter.isEmpty() ? new Index(p, items, new Name(p, i))
                : new Call(p, new Select(p, items, getter), List.of(new Name(p, i)));
        Block body = new Block(p, List.of(new VarDecl(p, fe.type(), fe.name(), element), fe.body()));
        return new For(p, new VarDecl(p, intType, i, intLit(p, 0)),
                new Binary(p, BinaryOp.LT, new Name(p, i), count),
                new Assign(p, BinaryOp.ADD, new Name(p, i), intLit(p, 1)), body);
    }

    private static Expr call(Pos p, Expr target, String method) {
        return new Call(p, new Select(p, target, method), List.of());
    }

    /**
     * What try-with-resources becomes (§41.4): each resource is declared in turn, and closed after the rest of the
     * statement however it is left, the last first; a null one isn't. An exception close() throws while another is
     * on its way is added to that one with addSuppressed, as in Java. Then the statement's own catch and finally
     * blocks, if any, go around all of it, so they run after the resources are closed.
     */
    private Stmt desugarResources(Body b, Try tr) {
        boolean closable = true;
        for (VarDecl r : tr.resources()) {
            Type t = resolveType(r.type(), b.env, Opts.PLAIN);
            if (!t.isError() && !(t instanceof HandleT h && h.target() instanceof ClassT ct && implementsInterface(ct, "AutoCloseable"))) {
                boolean hasClose = t instanceof HandleT h && h.target() instanceof ClassT ct && hasMethod(ct, "close");
                report(r.type().pos(), "a resource of try-with-resources must implement AutoCloseable, so that it can be"
                        + " closed; " + (t instanceof HandleT h ? h.target() : t) + " doesn't"
                        + (hasClose ? " (it has close(): add implements(AutoCloseable))" : ""));
                closable = false;
            }
        }
        Block inner = tr.body();
        for (int i = tr.resources().size() - 1; i >= 0; i--) {
            VarDecl r = tr.resources().get(i);
            boolean java = isJavaValue(quietly(() -> resolveType(r.type(), b.env, Opts.PLAIN)));
            inner = closing(r, inner, closable, java);
        }
        if (tr.catches().isEmpty() && tr.finallyBlock() == null) {
            return inner;
        }
        return new Try(tr.pos(), List.of(), inner, tr.catches(), tr.finallyBlock());
    }

    /**
     * R r = init; Exception $primary = null;
     * try { body } catch (Exception $thrown) { $primary = $thrown; throw $thrown; }
     * finally { if (r != null) { if ($primary != null) { try { r.close(); } catch (Exception $later) { $primary.addSuppressed($later); } } else { r.close(); } } }
     */
    private Block closing(VarDecl r, Block body, boolean closable, boolean java) {
        Pos p = r.pos();
        String primary = "$primary" + (++hiddenNames);
        String thrown = "$thrown" + (++hiddenNames);
        String later = "$suppressed" + (++hiddenNames);
        // A Java resource's close() may throw any of Java's exceptions, as its body may (docs/JAVA.md §4.1).
        java.util.function.Supplier<TypeRef> exception = () -> new TypeRef(p, java ? "java.lang.Throwable" : "Exception",
                List.of(), null, List.of());
        java.util.function.Supplier<Block> close = () -> new Block(p, List.of(new ExprStmt(p, call(p, new Name(p, r.name()), "close"))));
        List<Stmt> fin = List.of();
        if (closable) {
            Stmt suppressing = new Try(p, List.of(), close.get(), List.of(new Catch(p, exception.get(), later, new Block(p, List.of(
                    new ExprStmt(p, new Call(p, new Select(p, new Name(p, primary), "addSuppressed"), List.of(new Name(p, later)))))))),
                    null);
            Stmt either = new If(p, new Binary(p, BinaryOp.NE, new Name(p, primary), new NullLit(p)),
                    new Block(p, List.of(suppressing)), close.get());
            fin = List.of(new If(p, new Binary(p, BinaryOp.NE, new Name(p, r.name()), new NullLit(p)), new Block(p, List.of(either)), null));
        }
        Catch record = new Catch(p, exception.get(), thrown, new Block(p, List.of(
                new ExprStmt(p, new Assign(p, null, new Name(p, primary), new Name(p, thrown))),
                new Throw(p, new Name(p, thrown)))));
        return new Block(p, List.of(r, new VarDecl(p, exception.get(), primary, new NullLit(p)),
                new Try(p, List.of(), body, List.of(record), new Block(p, fin))));
    }

    /** For a method that would change a string, which strings don't have: how to get the new text instead (§39). */
    private static String stringMethodHint(ClassInfo cls, String method) {
        if (!cls.name.equals("string") || !cls.builtin) {
            return "";
        }
        return switch (method) {
            case "append", "insert", "reserve" -> "; strings are immutable: s += x stores a new string in s, and a"
                    + " StringBuilder builds text a piece at a time";
            case "set", "setCharAt" -> "; strings are immutable: make the new text with a StringBuilder (its"
                    + " setCharAt), or change toCharArray() and make new string(chars)";
            case "clear" -> "; strings are immutable: s = \"\" stores an empty one in s";
            default -> "";
        };
    }

    /** Whether the class is, extends or implements the named interface. */
    private boolean implementsInterface(ClassT ct, String name) {
        if (ct.cls().name.equals(name) && ct.cls().isInterface) {
            return true;
        }
        return allInterfacesOf(ct).stream().anyMatch(i -> i.cls().name.equals(name));
    }

    private static IntLit intLit(Pos p, long v) {
        return new IntLit(p, String.valueOf(v), BigInteger.valueOf(v));
    }

    /** A variable or field path, safe to evaluate more than once: a, a.b.c. */
    private static boolean isSimplePath(Expr e) {
        return e instanceof Name || (e instanceof Select s && isSimplePath(s.target()));
    }

    private boolean implementsIterable(ClassT ct) {
        if (ct.cls().name.equals("Iterable") && ct.cls().isInterface) {
            return true;
        }
        return allInterfacesOf(ct).stream().anyMatch(i -> i.cls().name.equals("Iterable"));
    }

    /** Whether an instance method of this name exists on the class, its parents or interfaces. */
    private boolean hasMethod(ClassT ct, String name) {
        ClassT c = ct;
        for (int depth = 0; c != null && depth < MAX_INSTANTIATION_DEPTH; depth++, c = parentOf(c)) {
            if (c.cls().members.getOrDefault(name, List.of()).stream().anyMatch(m -> m.member instanceof Method)) {
                return true;
            }
        }
        return allInterfacesOf(ct).stream().anyMatch(i -> i.cls().members.containsKey(name));
    }

    /** Whether a statement always returns (conservatively). */
    private static boolean returns(Stmt s) {
        return switch (s) {
            case Return r -> true;
            case Block b -> b.stmts().stream().anyMatch(Checker::returns);
            case If i -> i.otherwise() != null && returns(i.then()) && returns(i.otherwise());
            case While w -> w.cond() instanceof BoolLit bl && bl.value() && !breaks(w.body(), null, true);
            case Labeled l -> switch (l.body()) {
                case While w -> w.cond() instanceof BoolLit bl && bl.value() && !breaks(w.body(), l.label(), true);
                case For f -> (f.cond() == null || f.cond() instanceof BoolLit bl && bl.value())
                        && !breaks(f.body(), l.label(), true);
                default -> false;
            };
            case For f -> (f.cond() == null || f.cond() instanceof BoolLit bl && bl.value()) && !breaks(f.body(), null, true);
            case Switch sw -> sw.cases().stream().anyMatch(Case::isDefault) && sw.cases().stream().allMatch(c ->
                    c.body().stream().anyMatch(Checker::returns) && c.body().stream().noneMatch(x -> breaks(x, null, true)));
            case Throw t -> true;
            case Try t -> (t.finallyBlock() != null && returns(t.finallyBlock()))
                    || (returns(t.body()) && t.catches().stream().allMatch(c -> returns(c.body())));
            default -> false;
        };
    }

    /**
     * Whether a loop body contains a `break` for that loop: a plain one ({@code plain}, until a
     * nested loop or switch takes those over), or one naming the loop's label from any depth.
     */
    private static boolean breaks(Stmt s, String label, boolean plain) {
        return switch (s) {
            case Break b -> b.label() == null ? plain : b.label().equals(label);
            case Block b -> b.stmts().stream().anyMatch(x -> breaks(x, label, plain));
            case If i -> breaks(i.then(), label, plain) || (i.otherwise() != null && breaks(i.otherwise(), label, plain));
            case Try t -> breaks(t.body(), label, plain) || t.catches().stream().anyMatch(c -> breaks(c.body(), label, plain))
                    || (t.finallyBlock() != null && breaks(t.finallyBlock(), label, plain));
            case Labeled l -> breaks(l.body(), label, plain);
            // Nested loops and switches own their plain breaks; `break outer;` inside them still leaves this loop.
            case While w -> label != null && breaks(w.body(), label, false);
            case For f -> label != null && breaks(f.body(), label, false);
            case ForEach f -> label != null && breaks(f.body(), label, false);
            case Switch sw -> label != null && sw.cases().stream().anyMatch(c -> c.body().stream()
                    .anyMatch(x -> breaks(x, label, false)));
            default -> false;
        };
    }

    // ================================================================ statements

    /** `break outer;` and `continue outer;` need a loop labeled outer around them, in the same method or lambda. */
    private void requireLabel(Body b, Pos pos, String label, String what) {
        if (!b.labels.contains(label)) {
            report(pos, what + " " + label + ": no loop around it is labeled " + label
                    + (b.labels.isEmpty() ? "" : "; the labels here are " + String.join(", ", b.labels)));
        }
    }

    private void checkBlock(Body b, Block block, boolean newScope) {
        if (newScope) {
            b.scopes.push(new HashMap<>());
        }
        for (Stmt s : block.stmts()) {
            checkStmt(b, s);
        }
        if (newScope) {
            b.scopes.pop();
        }
    }

    private void checkStmt(Body b, Stmt s) {
        switch (s) {
            case Yield y -> {
                if (b.switchExprs.isEmpty()) {
                    report(y.pos(), "yield gives a switch expression's value, so it goes in a case block of one");
                    checkExpr(b, y.value(), null);
                } else {
                    checkExpr(b, y.value(), b.switchExprs.peek().expected());
                    b.switchExprs.peek().yields().add(y);
                }
            }
            case Assert a -> checkAssert(b, a);
            case Block block -> checkBlock(b, block, true);
            case VarDecl v -> {
                Type t = v.type() != null ? resolveType(v.type(), b.env, Opts.PLAIN) : inferredType(b, v.init());
                b.locals.put(v, t);
                if (b.scopes.peek().containsKey(v.name())) {
                    report(v.pos(), "variable " + v.name() + " is already declared in this scope");
                }
                if (v.init() != null) {
                    Type it = checkExpr(b, v.init(), t);
                    requireConvertible(b, v.init(), it, t, "initializer of " + v.name());
                    requireNotCopied(b, v.init(), t, "initializer of " + v.name());
                } else {
                    requireDefaultConstructible(t, v.pos(), "variable " + v.name());
                }
                b.scopes.peek().put(v.name(), t);
            }
            case ExprStmt e when virtualCompound(b, e) != null -> {
                Block steps = virtualCompound(b, e);
                b.statementSteps.put(e, steps);
                checkStmt(b, steps);
            }
            case ExprStmt e when elementCompound(b, e) != null -> {
                Block steps = elementCompound(b, e);
                b.statementSteps.put(e, steps);
                checkStmt(b, steps);
            }
            case ExprStmt e -> checkExpr(b, e.expr(), null);
            case ForEach fe -> {
                Stmt loop = desugarForEach(b, fe);
                if (loop != null) {
                    b.desugared.put(fe, loop);
                    checkStmt(b, loop);
                }
            }
            case If i -> {
                requireCondition(b, i.cond());
                withBindings(b, i.cond(), () -> checkStmt(b, i.then()));
                if (i.otherwise() != null) {
                    checkStmt(b, i.otherwise());
                }
            }
            case While w -> {
                requireCondition(b, w.cond());
                b.loopDepth++;
                withBindings(b, w.cond(), () -> checkStmt(b, w.body()));
                b.loopDepth--;
            }
            case For f -> {
                b.scopes.push(new HashMap<>());
                if (f.init() != null) {
                    checkStmt(b, f.init());
                }
                if (f.cond() != null) {
                    requireCondition(b, f.cond());
                }
                b.scopes.push(new HashMap<>());
                if (f.cond() != null) {
                    bindings(f.cond()).forEach(io -> b.scopes.peek().put(io.binding(), bindingType(b, io)));
                }
                if (f.update() != null) {
                    checkExpr(b, f.update(), null);
                }
                b.loopDepth++;
                checkStmt(b, f.body());
                b.loopDepth--;
                b.scopes.pop();
                b.scopes.pop();
            }
            case Return r -> {
                if (!b.switchExprs.isEmpty()) {
                    report(r.pos(), "a switch expression's case can't return; give its value with yield");
                }
                if (b.returnType == INFER_RETURN) {
                    // Only learning what a lambda returns (§12.2).
                    b.inferredReturns.add(r.value() == null ? Type.VOID : checkExpr(b, r.value(), null));
                } else if (r.value() == null) {
                    if (!(b.returnType instanceof VoidT) && !b.returnType.isError()) {
                        report(r.pos(), b.memberName + " must return a " + b.returnType);
                    }
                } else if (b.returnType instanceof VoidT) {
                    Type t = checkExpr(b, r.value(), null);
                    if (!t.isError()) {
                        report(r.pos(), b.inConstructor || b.memberName.equals("delete")
                                ? b.memberName + " cannot return a value"
                                : "void method " + b.memberName + " cannot return a value");
                    }
                } else {
                    Type t = checkExpr(b, r.value(), b.returnType);
                    requireConvertible(b, r.value(), t, b.returnType, "return value");
                    // Returning a local moves it out; anything else would copy it.
                    boolean returnsLocal = r.value() instanceof Name n && "local".equals(b.nameKinds.get(n))
                            && !b.paramNames.contains(n.name());
                    if (!returnsLocal) {
                        requireNotCopied(b, r.value(), b.returnType, "return value");
                    }
                }
            }
            case Break br -> {
                if (br.label() != null) {
                    requireLabel(b, br.pos(), br.label(), "break");
                } else if (b.loopDepth == 0 && b.switchDepth == 0) {
                    report(br.pos(), b.switchExprs.isEmpty() ? "break outside a loop or switch"
                            : "break can't leave a switch expression's case; give its value with yield");
                }
            }
            case Labeled l -> {
                if (!(l.body() instanceof While || l.body() instanceof For || l.body() instanceof ForEach)) {
                    report(l.pos(), "a label goes on a loop (for, for-each or while), not on this statement");
                } else if (b.labels.contains(l.label())) {
                    report(l.pos(), "label " + l.label() + " is already used by a loop around this one");
                }
                b.labels.push(l.label());
                checkStmt(b, l.body());
                b.labels.pop();
            }
            case Switch sw -> checkSwitch(b, sw);
            case Throw th -> {
                Type t = checkExpr(b, th.value(), null);
                if (!t.isError() && !isException(t)) {
                    report(th.value().pos(), "throw needs an exception (an object of a class extending Exception), not " + t);
                }
            }
            case Try tr when !tr.resources().isEmpty() -> {
                Stmt steps = desugarResources(b, tr);
                b.desugared.put(tr, steps);
                List<String> names = tr.resources().stream().map(VarDecl::name).filter(b.resources::add).toList();
                checkStmt(b, steps);
                names.forEach(b.resources::remove);
            }
            case Try tr -> {
                checkStmt(b, tr.body());
                List<ClassT> caught = new ArrayList<>();
                for (Catch c : tr.catches()) {
                    Type t = resolveType(c.type(), b.env, new Opts(false, true, false, null));
                    if (!t.isError() && !(t instanceof ClassT ct && isException(new HandleT(ct)))) {
                        report(c.type().pos(), "catch takes a class extending Exception, not " + t
                                + (t instanceof ClassT jt && isJavaType(jt.cls()) ? "; of Java's, one extending java.lang.Throwable" : ""));
                        t = Type.ERROR;
                    }
                    if (t instanceof ClassT ct) {
                        for (ClassT earlier : caught) {
                            if (isSubclass(ct, earlier)) {
                                report(c.pos(), ct + " is already caught by the catch for " + earlier + " above");
                            }
                        }
                        caught.add(ct);
                        b.catchTypes.put(c, ct);
                    }
                    b.scopes.push(new HashMap<>());
                    b.scopes.peek().put(c.name(), t instanceof ClassT ct ? new HandleT(ct) : Type.ERROR);
                    checkStmt(b, c.body());
                    b.scopes.pop();
                }
                if (tr.finallyBlock() != null) {
                    checkStmt(b, tr.finallyBlock());
                }
            }
            case Continue co -> {
                if (co.label() != null) {
                    requireLabel(b, co.pos(), co.label(), "continue");
                } else if (b.loopDepth == 0) {
                    report(co.pos(), b.switchExprs.isEmpty() ? "continue outside a loop"
                            : "continue can't leave a switch expression's case; give its value with yield");
                }
            }
            case DeleteStmt d -> {
                Type t = checkExpr(b, d.target(), null);
                if (t instanceof HandleT) {
                    report(d.pos(), "delete frees addr memory only; the garbage collector frees a class object once"
                            + " nothing refers to it (set " + (d.target() instanceof Name ? "the variable" : "it")
                            + " to null to let go of it), and close() releases what it holds");
                } else if (!(t instanceof AddrT) && !t.isError()) {
                    report(d.pos(), "delete needs an addr<...>, not " + t
                            + (t instanceof ClassT ? "; inline objects are destroyed automatically at scope exit" : ""));
                }
            }
        }
    }

    private void requireCondition(Body b, Expr cond) {
        Type t = checkExpr(b, cond, Type.BIT);
        if (!(t instanceof BitT) && !t.isError()) {
            report(cond.pos(), "condition must be a bit, not " + t + (t.isInteger() ? "; compare explicitly, e.g. x != 0"
                    : t instanceof JavaPrimT j && j.code() == 'Z' ? "; Java's boolean is a bit by a cast: (bit)x" : ""));
        }
    }

    // ================================================================ assignability

    private boolean assignable(Type from, Type to) {
        if (from.isError() || to.isError() || from.equals(to)) {
            return true;
        }
        return switch (to) {
            case IntT it -> (from instanceof IntConstT ic && (ic.value() == null || fits(ic.value(), it))) || widens(from, it);
            case FixedT ft -> constFitsFixed(from, ft) || widens(from, ft);
            case FloatT f -> from instanceof IntConstT || from instanceof FloatConstT || widens(from, f);
            case DoubleT d -> from instanceof IntConstT || from instanceof FloatConstT || widens(from, d);
            case AddrT a -> from instanceof NullT;
            case HandleT h -> (from instanceof LambdaT lt && functionalMethod(h) instanceof Impl fn
                        && ((Method) fn.member().member).params().size() == lt.lambda().params().size())
                    || from instanceof NullT
                    || (from instanceof HandleT fh && fh.target() instanceof ClassT fc
                        && h.target() instanceof ClassT tc && isSubclass(fc, tc));
            case ClassT ct -> (from instanceof StrConstT s && ct.cls().name.equals("string") && stringFits(s, ct))
                    || (from instanceof ArrayConstT ac && isArrayObject(ct) && arrayObjectFits(ac, ct))
                    || (from instanceof ArrayT fa && fa.size() != null && isArrayObject(ct) && ct.valueArgs().isEmpty()
                        && ct.typeArgs().size() == 1 && ct.typeArgs().get(0).equals(fa.element())) // T[N] to array<T>: a copy
                    || implicitConversion(from, ct) != null;
            case ArrayT at -> from instanceof ArrayConstT ac && arrayFits(ac, at);
            case JavaPrimT jp -> from instanceof JavaPrimT f && javaWidens(f, jp); // as Java widens (docs/JAVA.md §4)
            default -> false;
        };
    }

    /**
     * Whether a number of type {@code from} widens to {@code to} without a cast, as Java's
     * numbers do (§41.6): an integer or fixed-point type to one that holds every value it can
     * have, any integer or fixed-point number to float or double, and float to double. Nothing
     * becomes a char, and fixed point never becomes a plain integer.
     */
    public static boolean widens(Type from, Type to) {
        if (from.equals(to) || to.equals(Type.SIZE)) {
            return false; // nothing becomes a size but a constant that is one, or by a cast (§37.2)
        }
        boolean exactFrom = from instanceof IntT || from instanceof FixedT;
        if (to instanceof DoubleT) {
            return exactFrom || from instanceof FloatT;
        }
        if (to instanceof FloatT) {
            return exactFrom;
        }
        if (!exactFrom || to.equals(Type.CHAR) || (to instanceof IntT && from instanceof FixedT)) {
            return false;
        }
        int[] f = bits(from);
        int[] t = bits(to);
        if (f == null || t == null) {
            return false; // a width that isn't known yet
        }
        boolean fromSigned = f[0] == 1;
        boolean toSigned = t[0] == 1;
        if (fromSigned && !toSigned) {
            return false; // a negative number has no unsigned form
        }
        if (from.equals(Type.CHAR) && to instanceof IntT && !toSigned && t[1] == 16) {
            return false; // char and uint<<16>> are kept apart (§39)
        }
        // Fraction bits are kept, and the integer bits hold every value (one more for a sign).
        return t[2] >= f[2] && t[1] >= f[1] + (!fromSigned && toSigned ? 1 : 0);
    }

    /** {sign, integer bits, fraction bits} of an integer or fixed-point type, or null if a width isn't known. */
    private static int[] bits(Type t) {
        return switch (t) {
            case IntT i when i.width() instanceof IntV w -> new int[]{i.signed() ? 1 : 0, w.value().intValueExact(), 0};
            case FixedT x when x.intBits() instanceof IntV n && x.fracBits() instanceof IntV m ->
                    new int[]{x.signed() ? 1 : 0, n.value().intValueExact(), m.value().intValueExact()};
            default -> null;
        };
    }

    /** Whether an integer or floating-point constant can be stored in a fixed-point type (after rounding). */
    private static boolean constFitsFixed(Type from, FixedT t) {
        java.math.BigDecimal v;
        if (from instanceof IntConstT ic) {
            if (ic.value() == null) {
                return true;
            }
            v = new java.math.BigDecimal(ic.value());
        } else if (from instanceof FloatConstT fc) {
            if (fc.value() == null) {
                return true;
            }
            v = fc.value();
        } else {
            return false;
        }
        BigInteger n = t.intBits().intValue();
        BigInteger m = t.fracBits().intValue();
        if (n == null || m == null) {
            return true;
        }
        BigInteger raw = fixedRaw(v, m.intValue());
        int w = n.intValue() + m.intValue();
        if (t.signed()) {
            BigInteger lim = BigInteger.ONE.shiftLeft(w - 1);
            return raw.compareTo(lim.negate()) >= 0 && raw.compareTo(lim) < 0;
        }
        return raw.signum() >= 0 && raw.compareTo(BigInteger.ONE.shiftLeft(w)) < 0;
    }

    /** The exact decimal value of a float literal, from its digits rather than its double approximation. */
    public static java.math.BigDecimal exactLiteral(FloatLit f) {
        try {
            return new java.math.BigDecimal(f.text().replace("_", ""));
        } catch (NumberFormatException e) {
            return exactDouble(f.value());
        }
    }

    /** A double as an exact decimal, or null for NaN and infinities. */
    static java.math.BigDecimal exactDouble(Double d) {
        return d == null || d.isNaN() || d.isInfinite() ? null : new java.math.BigDecimal(d);
    }

    private static java.math.BigDecimal exactConst(Type t) {
        if (t instanceof FloatConstT f) {
            return f.value();
        }
        return t instanceof IntConstT i && i.value() != null ? new java.math.BigDecimal(i.value()) : null;
    }

    /** The raw integer for a value in a fixed-point type with M fraction bits: round(v * 2^M), ties up. */
    public static BigInteger fixedRaw(java.math.BigDecimal v, int fracBits) {
        return v.multiply(new java.math.BigDecimal(BigInteger.ONE.shiftLeft(fracBits)))
                .add(new java.math.BigDecimal("0.5"))
                .setScale(0, java.math.RoundingMode.FLOOR)
                .toBigIntegerExact();
    }

    /** A string literal fits a `string` of the bare shape, or `string<<N>>` with N at least its length. */
    private static boolean stringFits(StrConstT s, ClassT ct) {
        if (ct.valueArgs().isEmpty()) {
            return true;
        }
        BigInteger n = ct.valueArgs().get(0).intValue();
        return n == null || s.value() == null || n.compareTo(BigInteger.valueOf(s.value().length())) >= 0;
    }

    /**
     * The `@implicit` constructor of {@code to} that converts a {@code from} value, as a call to
     * make, or null. Its parameter must be exactly {@code from} (or bind to it), or a number
     * constant must fit it, so conversions never chain.
     */
    public CallInfo implicitConversion(Type from, ClassT to) {
        if (from.equals(to) || from.isError()) {
            return null;
        }
        for (MemberInfo m : to.cls().constructors) {
            Constructor k = (Constructor) m.member;
            if (!m.existsIn(to.header()) || k.params().size() != 1 || !isImplicit(k)) {
                continue;
            }
            Signature sig = signature(to, m, k.params(), null);
            Type p = sig.params.get(0);
            Map<String, CVal> bound = new HashMap<>();
            boolean constant = from instanceof IntConstT || from instanceof FloatConstT; // Meters m = 5;
            boolean fits = sig.binders.isEmpty() ? from.equals(p) || (constant && assignable(from, p))
                    : unify(p, from, sig.binders.keySet(), bound) && bound.keySet().containsAll(sig.binders.keySet());
            if (fits) {
                return new CallInfo(CallKind.CONSTRUCTOR, m, to, Map.copyOf(bound), false, false);
            }
        }
        return null;
    }

    private static boolean isImplicit(Constructor k) {
        return k.modifiers().decorators().stream().anyMatch(d -> d.name().equals("implicit"));
    }

    /** An array literal fits array<T> if each element fits T, and array<T><<N>> if it also has N of them. */
    private boolean arrayObjectFits(ArrayConstT ac, ClassT array) {
        if (array.typeArgs().size() != 1) {
            return false;
        }
        if (!array.valueArgs().isEmpty()) {
            BigInteger n = array.valueArgs().get(0).intValue();
            if (n != null && !n.equals(BigInteger.valueOf(ac.elements().size()))) {
                return false;
            }
        }
        Type element = array.typeArgs().get(0);
        return ac.elements().stream().allMatch(e -> assignable(e, element));
    }

    /** An array literal fits {@code T[n]} if it has n elements and each fits T. */
    private boolean arrayFits(ArrayConstT ac, ArrayT at) {
        BigInteger n = at.size() == null ? null : at.size().intValue();
        if (n != null && !n.equals(BigInteger.valueOf(ac.elements().size()))) {
            return false;
        }
        return ac.elements().stream().allMatch(e -> assignable(e, at.element()));
    }

    private static boolean isAdaptive(Type t) {
        return t instanceof IntConstT || t instanceof FloatConstT || t instanceof StrConstT
                || t instanceof ArrayConstT || t instanceof NullT;
    }

    /**
     * The type an array literal takes when nothing else decides it: the type of its first
     * element that isn't a constant, or else the constants' default (int, or a wider int if
     * needed; double if any has a fraction; string<<longest>>), e.g. `[1, 2, 3]` is an int[3].
     * Null if the elements don't share a type.
     */
    private ArrayT defaultArray(ArrayConstT ac) {
        List<Type> es = ac.elements();
        Type elem = es.stream().filter(t -> !isAdaptive(t)).findFirst().orElse(null);
        if (elem == null) {
            if (es.stream().allMatch(t -> t instanceof IntConstT ic && ic.isChar())) {
                elem = Type.CHAR; // ['a', 'b']
            } else if (es.stream().allMatch(t -> t instanceof IntConstT)) {
                boolean wide = es.stream().anyMatch(t -> ((IntConstT) t).value() != null
                        && !fits(((IntConstT) t).value(), (IntT) Type.INT));
                elem = wide ? new IntT(CVal.of(64), true) : Type.INT;
            } else if (es.stream().allMatch(t -> t instanceof IntConstT || t instanceof FloatConstT)) {
                elem = Type.DOUBLE;
            } else if (es.stream().allMatch(t -> t instanceof StrConstT)) {
                String longest = es.stream().map(t -> ((StrConstT) t).value())
                        .reduce("", (a, b) -> a == null || b == null ? null : a.length() >= b.length() ? a : b);
                elem = longest == null ? null : literalStringType(new StrConstT(longest));
            } else if (es.get(0) instanceof ArrayConstT first) {
                elem = defaultArray(first);
            }
        }
        if (elem == null) {
            return null;
        }
        Type e = elem;
        return es.stream().allMatch(t -> assignable(t, e)) ? new ArrayT(elem, CVal.of(es.size())) : null;
    }

    /** Checks `[a, b, c]`; with an array type expected, each element is checked against its element type. */
    private Type checkArrayLit(Body b, ArrayLit al, Type expected) {
        if (al.elements().isEmpty()) {
            report(al.pos(), "an array literal needs at least one element");
            return Type.ERROR;
        }
        Type want = expected instanceof ArrayT at ? at.element()
                : isArrayObject(expected) && ((ClassT) expected).typeArgs().size() == 1 ? ((ClassT) expected).typeArgs().get(0) : null;
        List<Type> types = new ArrayList<>();
        boolean bad = false;
        for (Expr e : al.elements()) {
            Type t = checkExpr(b, e, want);
            if (t.isError()) {
                bad = true;
            } else if (want != null && !want.isError()) {
                requireConvertible(b, e, t, want, "array element"); // or, when it fits, notes a cast
                if (narrows(t, want)) {
                    t = want; // converted, so the literal fits the array
                } else if (assignable(t, want)) {
                    requireNotCopied(b, e, want, "array element");
                } else {
                    bad = true;
                }
            } else {
                requireNotCopied(b, e, want != null ? want : t, "array element");
            }
            types.add(t);
        }
        if (bad) {
            return Type.ERROR;
        }
        ArrayConstT ac = new ArrayConstT(List.copyOf(types));
        if (want == null && defaultArray(ac) == null) {
            report(al.pos(), "array elements don't share a type ("
                    + types.stream().map(t -> t instanceof IntConstT ? "int" : t instanceof FloatConstT ? "double"
                            : defaultOf(t).toString()).distinct().collect(Collectors.joining(", "))
                    + "); declare the array's type, as in `array<T><<" + types.size() + ">> a = [...];`");
            return Type.ERROR;
        }
        return ac;
    }

    /**
     * The type a string literal takes when nothing else decides it: {@code string<<length>>}
     * (like {@code int} for an integer constant). Null if the text isn't known or there's no string.
     */
    private ClassT literalStringType(StrConstT s) {
        ClassInfo c = classes.get("string");
        if (s.value() == null || c == null) {
            return null;
        }
        for (ClassInfo.Shape shape : c.shapes) {
            if (shape.binders.size() == 1) {
                return new ClassT(c, List.of(), List.of(new IntV(BigInteger.valueOf(s.value().length()))), shape.index);
            }
        }
        return null;
    }

    /** Gives a string or array literal in {@code e} its default type, recording it for code generation. */
    private Type defaultLiteral(Body b, Expr e, Type t) {
        Type d = defaultOf(t);
        if (d != t) {
            b.types.put(e, d);
        }
        return d;
    }

    /** The default type of a string or array literal's type; any other type unchanged. */
    private Type defaultOf(Type t) {
        Type d = switch (t) {
            case StrConstT s -> literalStringType(s);
            case ArrayConstT ac -> defaultArray(ac);
            default -> t;
        };
        return d == null ? t : d;
    }

    /**
     * A value stored where a {@code to} is wanted: in an initializer, assignment, return or
     * argument. As requireAssignable, but a number also narrows to any other number type, as
     * C's do: int<<8>> b = i, int a = 2.5 (§41.6). The conversion is recorded for code generation.
     */
    private void requireConvertible(Body b, Expr e, Type from, Type to, String what) {
        if (narrows(from, to)) {
            b.implicitCasts.put(e, to);
            autoCast(e.pos(), from instanceof FloatConstT ? Type.DOUBLE : from, to, what, lossy(e, from));
            return;
        }
        b.implicitCasts.remove(e); // a value checked again (in a lambda, say) may no longer need one
        requireAssignable(b, from, to, e.pos(), what);
    }

    /**
     * Whether a value of type {@code from} becomes a {@code to} only by narrowing (§41.6): any
     * number to a number type that doesn't hold all its values (int to int<<8>>, double to int,
     * int to char), or a floating-point constant to an integer type its whole part fits, as
     * 2.5 to int. Integer constants still must fit, as for assignments: they are typed, not converted.
     */
    private boolean narrows(Type from, Type to) {
        if (!isNumber(to) || from.equals(to) || assignable(from, to) || to.equals(Type.SIZE)) {
            return false; // a size, by a cast alone (§37.2)
        }
        if (isNumber(from)) {
            return true; // int<<W>> too: each instantiation is checked again with W known
        }
        return from instanceof FloatConstT fc && fc.value() != null && to instanceof IntT it && concrete(it)
                && fits(fc.value().toBigInteger(), it);
    }

    /** A number type with every width known: int<<8>>, not int<<W>> before W is bound. */
    private static boolean concrete(Type t) {
        return switch (t) {
            case IntT it -> it.width() instanceof IntV;
            case FixedT ft -> ft.intBits() instanceof IntV && ft.fracBits() instanceof IntV;
            default -> true;
        };
    }

    /** Whether narrowing this value can lose data: always, except a whole-number constant such as 2.0. */
    private static boolean lossy(Expr e, Type from) {
        return !(from instanceof FloatConstT fc && fc.value() != null && fc.value().stripTrailingZeros().scale() <= 0);
    }

    private void requireAssignable(Body b, Type from, Type to, Pos pos, String what) {
        if (assignable(from, to)) {
            autoCast(pos, from, to, what);
            return;
        }
        if (from instanceof IntConstT ic && ic.value() != null && to.equals(Type.SIZE)) {
            reportValue(pos, ic.value() + " isn't a size: a size is 0 to " + Type.MAX_SIZE);
        } else if (from instanceof IntConstT ic && ic.value() != null && (to instanceof IntT || to instanceof FixedT)) {
            reportValue(pos, "integer " + ic.value() + " does not fit in " + to);
        } else if (from instanceof FloatConstT fc && fc.value() != null && to instanceof FixedT) {
            reportValue(pos, fc.value().toPlainString() + " does not fit in " + to);
        } else if (from instanceof ArrayConstT ac && to instanceof ArrayT at && at.size() != null
                && at.size().intValue() != null && at.size().intValue().intValue() != ac.elements().size()) {
            report(pos, what + ": the array literal has " + ac.elements().size() + " elements, but " + to
                    + " holds " + at.size().intValue());
        } else if (from instanceof LambdaT) {
            report(pos, what + ": a lambda needs a functional interface type (an interface with one method), not " + to);
        } else if (from instanceof VoidT) {
            report(pos, what + ": expression has no value");
        } else {
            String hint = to.equals(Type.SIZE) && from.isNumeric()
                    ? "; a cast makes a number a size, and stops the program if it isn't 0 to " + Type.MAX_SIZE + ": (size)x"
                    : from.isNumeric() && to.isNumeric() ? "; use a cast, e.g. (" + to + ")"
                    : isJavaValue(to) && !isJavaValue(from) && javaOf(from) != null
                    ? "; Seal's " + from + " goes into Java by " + javaOf(from) + "(...) (docs/JAVA.md §3.1)"
                    : isJavaValue(from) && !isJavaValue(to) && !(to instanceof HandleT) ? "; a cast takes a Java value out: (" + to + ")x"
                    : isJavaValue(from) && to instanceof HandleT th && th.target() instanceof ClassT tc && !isJavaType(tc.cls())
                    ? "; Java's objects aren't Seal's; a Seal object that went into Java comes back by a cast from a JavaObject,"
                    + " as (" + tc.cls().name + ")o (docs/JAVA.md §3.1)" : "";
            report(pos, what + ": expected " + to + ", found " + from + hint);
        }
    }

    // ================================================================ expressions

    private Type checkExpr(Body b, Expr e, Type expected) {
        Type t = checkExprInner(b, e, expected);
        b.types.put(e, t);
        return t;
    }

    private Type checkExprInner(Body b, Expr e, Type expected) {
        return switch (e) {
            case SwitchExpr se -> checkSwitchExpr(b, se, expected);
            case IntLit i -> new IntConstT(i.value(), i.text().startsWith("'")); // 'a' is a char constant
            case FloatLit f -> new FloatConstT(exactLiteral(f));
            case StringLit s -> new StrConstT(s.value());
            case ArrayLit al -> checkArrayLit(b, al, expected);
            case InstanceOf io -> checkInstanceOf(b, io);
            case Conditional c -> checkConditional(b, c, expected);
            case Lambda lam -> checkLambda(b, lam, expected);
            case BoolLit bl -> Type.BIT;
            case NullLit n -> Type.NULL;
            case Name n -> checkName(b, n);
            case SuperRef s -> {
                report(s.pos(), "super must be called, super(...), or used for a member, super.name");
                yield Type.ERROR;
            }
            case This t -> {
                if (b.isStatic || b.self == null) {
                    report(t.pos(), "this is only available in the methods and constructors of an object, not static code");
                    yield Type.ERROR;
                }
                noteUsesThis(b);
                yield b.self.cls().dynamic ? new HandleT(b.self) : b.self; // a class: a handle; a struct: the value
            }
            case Select s -> checkSelect(b, s);
            case Call c -> {
                if (expected != null) {
                    b.callExpected.put(c, expected); // a Java generic method's type arguments may come from it (docs/JAVA.md §4.1)
                }
                yield checkCall(b, c);
            }
            case Index i -> checkIndex(b, i);
            case Slice s -> checkSlice(b, s);
            case Unary u -> checkUnary(b, u);
            case IncDec i -> checkIncDec(b, i);
            case Binary bin -> checkBinary(b, bin);
            case Assign a -> checkAssign(b, a);
            case Cast c -> checkCast(b, c);
            case New n -> checkNew(b, n, expected);
            case NewArray n -> {
                Type el = resolveType(n.elementType(), b.env, Opts.PLAIN);
                Type count = checkExpr(b, n.count(), Type.INT);
                if (!count.isInteger() && !count.isError()) {
                    report(n.count().pos(), "element count must be an integer, not " + count);
                }
                yield el.isError() ? el : new AddrT(el);
            }
            case SizeOf s -> {
                Const c = evalConst(s, b.env);
                yield c.value() instanceof ErrV ? Type.ERROR : new IntConstT(c.value().intValue());
            }
            case AlignOf a -> {
                Const c = evalConst(a, b.env);
                yield c.value() instanceof ErrV ? Type.ERROR : new IntConstT(c.value().intValue());
            }
        };
    }

    /** The depth (0 = outermost) of the scope declaring a local, or -1. */
    private static int localDepth(Body b, String name) {
        int depth = b.scopes.size() - 1;
        for (Map<String, Type> scope : b.scopes) {
            if (scope.containsKey(name)) {
                return depth;
            }
            depth--;
        }
        return -1;
    }

    /** A local declared outside the lambdas being checked is captured by each of them. */
    private void noteCapture(Body b, String name, Type t) {
        int depth = localDepth(b, name);
        for (LambdaCtx ctx : b.lambdaCtx) {
            if (depth < ctx.base) {
                ctx.captures.putIfAbsent(name, t);
            }
        }
    }

    /** Whether the innermost lambda being checked captured this local (so may not assign it). */
    private static boolean capturedHere(Body b, String name) {
        LambdaCtx ctx = b.lambdaCtx.peek();
        return ctx != null && localDepth(b, name) >= 0 && localDepth(b, name) < ctx.base;
    }

    private static void noteUsesThis(Body b) {
        b.lambdaCtx.forEach(ctx -> ctx.usesThis = true);
    }

    /** The one method of a functional interface type, with the interface declaring it, or null. */
    private Impl functionalMethod(Type t) {
        if (!(t instanceof HandleT h) || !(h.target() instanceof ClassT ic) || !ic.cls().isInterface) {
            return null;
        }
        if (ic.cls().java != null) {
            return javaFunctionalMethod(javaUnwildcarded(ic));
        }
        List<ClassT> all = new ArrayList<>();
        all.add(ic);
        all.addAll(allInterfacesOf(ic));
        List<Impl> found = new ArrayList<>();
        Set<String> sigs = new HashSet<>();
        for (ClassT i : all) {
            for (MemberInfo m : i.cls().all) {
                if (m.member instanceof Method md && md.body() == null && !isJvmOnly(md)
                        && sigs.add(md.name() + paramTypes(i, m, Map.of()))) {
                    found.add(new Impl(i, m));
                }
            }
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    /**
     * The one abstract method of a Java interface (docs/JAVA.md §5), as Java finds a functional
     * interface's: its own or one it inherits, not a default one, nor one of java.lang.Object's that
     * the interface names again (Comparator's equals); null if it has none, or more than one.
     */
    private Impl javaFunctionalMethod(ClassT ic) {
        List<Impl> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        javaSupertypes(ic.cls(), ic, (c, inst) -> {
            if (!c.isInterface) {
                return false;
            }
            for (MemberInfo m : c.all) {
                if (!(m.member instanceof Method md) || md.modifiers().isStatic()
                        || !(javaMembers.get(md) instanceof JavaClasses.JMember jm)) {
                    continue;
                }
                if (!seen.add(md.name() + paramTypes(inst, m, Map.of()))) {
                    continue; // the nearest declaration decides: a default one implements one above it
                }
                if ((jm.flags() & JavaClasses.ABSTRACT) != 0 && !isObjectMethod(jm)) {
                    found.add(new Impl(inst, m));
                }
            }
            return false;
        });
        return found.size() == 1 && ((Method) found.get(0).member().member).typeParams().isEmpty() ? found.get(0) : null;
    }

    /** One of java.lang.Object's public methods, which an interface may name again without its classes implementing it. */
    private static boolean isObjectMethod(JavaClasses.JMember m) {
        return switch (m.name() + m.descriptor()) {
            case "equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;" -> true;
            default -> false;
        };
    }

    /**
     * A Java interface type with its wildcards as their bounds, as Java makes a lambda's function type
     * (JLS §9.9): a Comparator<? super JavaString> is a Comparator<JavaString> to a lambda.
     */
    private ClassT javaUnwildcarded(ClassT t) {
        if (t.typeArgs().stream().noneMatch(a -> a instanceof WildT)) {
            return t;
        }
        return new ClassT(t.cls(), t.typeArgs().stream().map(a -> a instanceof WildT w ? w.bound() : a).toList(),
                t.valueArgs(), t.header());
    }

    /** Whether a lambda can be a function that returns nothing: an expression that is a statement, or a block that returns no value. */
    private static boolean voidCompatible(Lambda lam) {
        if (lam.expr() != null) {
            return lam.expr() instanceof Call || lam.expr() instanceof Assign || lam.expr() instanceof IncDec
                    || lam.expr() instanceof New;
        }
        return !returnsValue(lam.block());
    }

    /** Whether a lambda can be a function that returns a value: an expression, or a block returning one (or never ending). */
    private static boolean valueCompatible(Lambda lam) {
        return lam.expr() != null || returnsValue(lam.block()) || returns(lam.block());
    }

    /** Whether a statement returns a value somewhere (not in a lambda inside it). */
    private static boolean returnsValue(Stmt s) {
        return switch (s) {
            case Return r -> r.value() != null;
            case Block bl -> bl.stmts().stream().anyMatch(Checker::returnsValue);
            case If i -> returnsValue(i.then()) || (i.otherwise() != null && returnsValue(i.otherwise()));
            case While w -> returnsValue(w.body());
            case For f -> returnsValue(f.body());
            case ForEach fe -> returnsValue(fe.body());
            case Labeled l -> returnsValue(l.body());
            case Switch sw -> sw.cases().stream().anyMatch(c -> c.body().stream().anyMatch(Checker::returnsValue));
            case Try t -> returnsValue(t.body()) || t.catches().stream().anyMatch(c -> returnsValue(c.body()))
                    || (t.finallyBlock() != null && returnsValue(t.finallyBlock()));
            default -> false;
        };
    }

    private Type checkLambda(Body b, Lambda lam, Type expected) {
        if (expected == null || expected instanceof LambdaT) {
            return new LambdaT(lam); // an argument: checked when the call is resolved
        }
        Impl fn = functionalMethod(expected);
        if (fn == null) {
            report(lam.pos(), "a lambda needs a functional interface type (an interface with one method), not "
                    + expected + "; e.g. Function<int, int> f = x -> x + 1;");
            return Type.ERROR;
        }
        Method md = (Method) fn.member().member;
        List<Type> params = paramTypes(fn.owner(), fn.member(), Map.of());
        Type ret = returnTypeOf(fn.owner(), fn.member(), Map.of());
        if (params.size() != lam.params().size()) {
            report(lam.pos(), fn.owner().cls().name + "." + md.name() + " takes " + params.size()
                    + " parameter(s), but the lambda has " + lam.params().size());
            return Type.ERROR;
        }
        Type savedReturn = b.returnType;
        String savedName = b.memberName;
        int savedLoops = b.loopDepth;
        int savedSwitches = b.switchDepth;
        Deque<String> savedLabels = b.labels;
        Deque<SwitchCtx> savedSwitchExprs = b.switchExprs;
        LambdaCtx ctx = new LambdaCtx(b.scopes.size());
        b.lambdaCtx.push(ctx);
        b.scopes.push(new HashMap<>());
        b.returnType = ret;
        b.memberName = "the lambda";
        b.loopDepth = 0;
        b.switchDepth = 0;
        b.labels = new ArrayDeque<>(); // a lambda can't break out to the loops around it
        b.switchExprs = new ArrayDeque<>();
        try {
            for (int i = 0; i < params.size(); i++) {
                Param p = lam.params().get(i);
                if (p.type() != null) {
                    Type declared = resolveType(p.type(), b.env, Opts.PLAIN);
                    if (!declared.equals(params.get(i)) && !declared.isError()) {
                        report(p.pos(), "lambda parameter " + p.name() + " is " + declared + ", but "
                                + fn.owner().cls().name + "." + md.name() + " passes " + params.get(i));
                    }
                }
                b.scopes.peek().put(p.name(), params.get(i));
            }
            if (lam.expr() != null) {
                Type t = checkExpr(b, lam.expr(), ret instanceof VoidT ? null : ret);
                if (!(ret instanceof VoidT)) {
                    requireConvertible(b, lam.expr(), t, ret, "lambda result");
                    requireNotCopied(b, lam.expr(), ret, "lambda result");
                } else if (t instanceof LambdaT) {
                    report(lam.expr().pos(), "a lambda here needs a functional interface type");
                }
            } else {
                checkBlock(b, lam.block(), true);
                if (!(ret instanceof VoidT) && !ret.isError() && !returns(lam.block())) {
                    report(lam.pos(), "the lambda can reach its end without returning a " + ret);
                }
            }
        } finally {
            b.scopes.pop();
            b.lambdaCtx.pop();
            b.returnType = savedReturn;
            b.memberName = savedName;
            b.loopDepth = savedLoops;
            b.switchDepth = savedSwitches;
            b.labels = savedLabels;
            b.switchExprs = savedSwitchExprs;
        }
        ClassT thisType = null;
        if (ctx.usesThis) {
            if (b.self == null || !b.self.cls().dynamic) {
                report(lam.pos(), "a lambda in a " + b.cls.kind.keyword + " method can't use its fields or methods,"
                        + " since there is no shared object to keep; copy what it needs into locals first");
            } else {
                thisType = b.self;
            }
        }
        for (Map.Entry<String, Type> c : ctx.captures.entrySet()) {
            if (isUnique(c.getValue())) {
                report(lam.pos(), "the lambda uses " + c.getKey() + ", a unique, which it would have to copy");
            }
        }
        ClassT target = expected instanceof HandleT eh && eh.target() instanceof ClassT et ? javaUnwildcarded(et) : fn.owner();
        b.lambdas.put(lam, new LambdaInfo(fn.owner(), fn.member(), params, ret,
                List.copyOf(ctx.captures.keySet()), List.copyOf(ctx.captures.values()), thisType, target));
        return expected;
    }

    /**
     * Binds type parameters that only a lambda argument's result can give, as R in
     * map(Function<T, R> f) called with x -> x * 2: once the lambda's parameter types are known,
     * its body is checked to find what it returns.
     */
    private void inferFromLambdas(Body b, Signature sig, List<Type> args, Map<String, CVal> bound) {
        for (int i = 0; i < args.size(); i++) {
            if (!(args.get(i) instanceof LambdaT lt)) {
                continue;
            }
            Type p = substitute(sig.params.get(i), bound);
            Impl fn = functionalMethod(p);
            if (fn == null) {
                continue;
            }
            List<Type> lambdaParams = paramTypes(fn.owner(), fn.member(), Map.of());
            if (lambdaParams.stream().anyMatch(t -> mentionsBinder(t, sig.binders.keySet(), bound))) {
                continue; // its parameter types aren't known yet
            }
            Type result = lambdaResultType(b, lt.lambda(), lambdaParams);
            Type declared = returnTypeOf(fn.owner(), fn.member(), Map.of());
            if (result != null) {
                unify(declared, result, sig.binders.keySet(), bound);
            }
        }
    }

    private static boolean mentionsBinder(Type t, Set<String> binders, Map<String, CVal> bound) {
        return switch (t) {
            case ParamT p -> binders.contains(p.name()) && !bound.containsKey(p.name());
            case ClassT c -> c.typeArgs().stream().anyMatch(a -> mentionsBinder(a, binders, bound));
            case HandleT h -> mentionsBinder(h.target(), binders, bound);
            case ArrayT a -> mentionsBinder(a.element(), binders, bound);
            case AddrT a -> mentionsBinder(a.target(), binders, bound);
            default -> false;
        };
    }

    /** Marks a lambda being checked only to learn what it returns. */
    private static final Type INFER_RETURN = new ParamT("<inferred>");

    /** What a lambda returns when its parameters have these types; null if that can't be told. */
    private Type lambdaResultType(Body b, Lambda lam, List<Type> params) {
        if (params.size() != lam.params().size()) {
            return null;
        }
        Type savedReturn = b.returnType;
        String savedName = b.memberName;
        List<Type> savedInferred = b.inferredReturns;
        int savedLoops = b.loopDepth;
        int savedSwitches = b.switchDepth;
        Deque<String> savedLabels = b.labels;
        Deque<SwitchCtx> savedSwitchExprs = b.switchExprs;
        b.lambdaCtx.push(new LambdaCtx(b.scopes.size()));
        b.scopes.push(new HashMap<>());
        try {
            for (int i = 0; i < params.size(); i++) {
                b.scopes.peek().put(lam.params().get(i).name(), params.get(i));
            }
            b.loopDepth = 0;
            b.switchDepth = 0;
            b.labels = new ArrayDeque<>(); // a lambda can't break out to the loops around it
            b.switchExprs = new ArrayDeque<>();
            Type result;
            if (lam.expr() != null) {
                result = quietly(() -> checkExpr(b, lam.expr(), null));
            } else {
                b.returnType = INFER_RETURN;
                b.memberName = "the lambda";
                b.inferredReturns = new ArrayList<>();
                quietly(() -> {
                    checkBlock(b, lam.block(), true);
                    return null;
                });
                result = b.inferredReturns.isEmpty() ? Type.VOID : b.inferredReturns.get(0);
            }
            return result instanceof VoidT ? result : typeArgOf(result);
        } finally {
            b.scopes.pop();
            b.lambdaCtx.pop();
            b.returnType = savedReturn;
            b.memberName = savedName;
            b.inferredReturns = savedInferred;
            b.loopDepth = savedLoops;
            b.switchDepth = savedSwitches;
            b.labels = savedLabels;
            b.switchExprs = savedSwitchExprs;
        }
    }

    /** Checks the lambdas among a resolved call's arguments against the parameter types. */
    private void checkLambdaArgs(Body b, Expr site, List<Expr> args) {
        CallInfo ci = b.calls.get(site);
        if (ci == null || ci.member() == null) {
            return;
        }
        List<Type> ps = paramTypes(ci.owner() != null ? ci.owner() : selfOrDeclaring(ci.member()), ci.member(), ci.binders());
        for (int i = 0; i < args.size() && i < ps.size(); i++) {
            if (args.get(i) instanceof Lambda lam) {
                b.types.put(lam, checkLambda(b, lam, ps.get(i)));
            }
        }
    }

    private ClassT selfOrDeclaring(MemberInfo m) {
        ClassInfo c = declaringClass(m);
        return c.shapes.isEmpty() ? null : selfType(c, 0);
    }

    private Type lookupLocal(Body b, String name) {
        for (Map<String, Type> scope : b.scopes) {
            Type t = scope.get(name);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private Type checkName(Body b, Name n) {
        Type local = lookupLocal(b, n.name());
        if (local != null) {
            b.nameKinds.put(n, "local");
            noteCapture(b, n.name(), local);
            return local;
        }
        Const value = b.env.values.get(n.name());
        if (value != null) {
            b.nameKinds.put(n, "value");
            return runtimeTypeOfConst(value);
        }
        Found found = findMembers(b, b.self, b.cls, n.name(), n.pos(), b.isStatic);
        if (found != null) {
            if (found.error) {
                return Type.ERROR;
            }
            MemberInfo m = found.members.get(0);
            if (m.member instanceof Method) {
                report(n.pos(), "method " + n.name() + " must be called: " + n.name() + "(...)");
                return Type.ERROR;
            }
            Field f = (Field) m.member;
            if (found.owner != null && found.owner.cls().isVirtual(f)) {
                // name in an interface's default method, or an abstract class's: the class's own, through its getter (§30.4)
                Call get = new Call(n.pos(), new Select(n.pos(), new This(n.pos()), "get " + n.name()), List.of(), List.of());
                b.rewritten.put(n, get);
                return checkExpr(b, get, null);
            }
            if (!f.modifiers().isStatic() && !f.modifiers().isConst()) {
                noteUsesThis(b);
            }
            b.nameKinds.put(n, f.modifiers().isConst() ? "const" : "field");
            b.owners.put(n, found.owner);
            b.fieldOf.put(n, m);
            return memberFieldType(found.owner, m, b.cls, f);
        }
        List<StaticMember> imported = staticImports(b.cls, n.name()).stream()
                .filter(sm -> sm.m().member instanceof Field).toList();
        if (!imported.isEmpty()) {
            // import static Math.PI; and then PI (§2.4)
            if (imported.stream().map(StaticMember::cls).distinct().count() > 1) {
                report(n.pos(), n.name() + " could be " + imported.stream().map(sm -> sm.cls().qualifiedName() + "." + sm.m().name())
                        .distinct().collect(Collectors.joining(" or ")) + ", which static imports bring; write the one you mean");
                return Type.ERROR;
            }
            MemberInfo m = imported.get(0).m();
            Field f = (Field) m.member;
            b.nameKinds.put(n, f.modifiers().isConst() ? "const" : "field");
            b.owners.put(n, null);
            b.fieldOf.put(n, m);
            return memberFieldType(null, m, b.cls, f);
        }
        ClassInfo c = lookupClass(n.name(), b.cls);
        if (c != null && visibleClass(c, b.cls, n.pos()) == null) {
            return Type.ERROR;
        }
        if (c != null) {
            b.nameKinds.put(n, "class");
            return new ClassRefT(c);
        }
        if (b.cls.typeParams.contains(n.name())) {
            report(n.pos(), n.name() + " is a type, not a value");
        } else {
            report(n.pos(), "unknown name " + n.name() + unknownTypeHint(n.name(), b.cls));
        }
        return Type.ERROR;
    }

    /** The type of a compile-time value used in runtime code: constants adapt to their context. */
    private static Type runtimeTypeOfConst(Const c) {
        return switch (c.type()) {
            case IntT i -> new IntConstT(c.value().intValue());
            case IntConstT i -> new IntConstT(c.value().intValue());
            case FloatT f -> new FloatConstT(c.value() instanceof FloatV fv ? exactDouble(fv.value()) : null);
            case DoubleT d -> new FloatConstT(c.value() instanceof FloatV fv ? exactDouble(fv.value()) : null);
            case StrConstT s -> new StrConstT(c.value() instanceof StrV sv ? sv.value() : null);
            default -> c.type();
        };
    }

    private Type memberFieldType(ClassT owner, MemberInfo m, ClassInfo accessor, Field f) {
        if (owner == null || f.modifiers().isStatic()) {
            ClassInfo declaring = declaringClass(m);
            String saved = file;
            file = declaring.file;
            try {
                return quietly(() -> resolveType(f.type(), Env.empty(declaring), Opts.PLAIN));
            } finally {
                file = saved;
            }
        }
        return quietly(() -> fieldType(owner, m));
    }

    private ClassInfo declaringClass(MemberInfo m) {
        ClassInfo known = declaringCache.get(m);
        if (known != null) {
            return known;
        }
        for (ClassInfo c : classes.values()) {
            if (c.all.contains(m)) {
                declaringCache.put(m, c);
                return c;
            }
        }
        throw new IllegalStateException("member without class");
    }

    /** Members found by name, with the instance type they belong to (null for static access). */
    private static final class Found {
        final List<MemberInfo> members;
        final ClassT owner;
        final boolean error;

        Found(List<MemberInfo> members, ClassT owner, boolean error) {
            this.members = members;
            this.owner = owner;
            this.error = error;
        }
    }

    /**
     * Finds members named {@code name} in {@code cls} for the given instance
     * type (or static access when {@code owner} is null), searching parents of
     * dynamic classes. Returns null if the name does not exist at all.
     */
    private Found findMembers(Body b, ClassT owner, ClassInfo cls, String name, Pos pos, boolean staticOnly) {
        List<MemberInfo> list = cls.members.get(name);
        if (list != null) {
            List<MemberInfo> result = new ArrayList<>();
            boolean guarded = list.get(0).guarded;
            for (MemberInfo m : list) {
                // Static members, and const fields (compile-time values with no storage), need no object.
                if (m.member.modifiers().isStatic() || (m.member instanceof Field f && f.modifiers().isConst() && !m.guarded)) {
                    result.add(m);
                } else if (owner != null && (!guarded || m.existsIn(owner.header()))) {
                    result.add(m);
                }
            }
            if (result.isEmpty()) {
                if (owner == null || staticOnly) {
                    report(pos, "`" + name + "` is an instance member of " + cls.name + " and needs an object");
                } else {
                    report(pos, "`" + name + "` does not exist in shape " + describeShape(cls, owner.header())
                            + " (coverage rule: it is only declared for "
                            + list.stream().filter(m -> m.shape >= 0).map(m -> describeShape(cls, m.shape))
                                    .distinct().collect(Collectors.joining(", ")) + ")");
                }
                return new Found(List.of(), owner, true);
            }
            for (MemberInfo m : result) {
                if (!canAccess(b.cls, cls, m.member.modifiers())) {
                    report(pos, "`" + name + "` is private to " + cls.name);
                    return new Found(result, owner, true);
                }
            }
            return new Found(result, owner, false);
        }
        if (cls.dynamic && owner != null) {
            ClassT parent = parentOf(owner);
            if (parent != null && !parent.equals(owner)) {
                Found f = findMembers(b, parent, parent.cls(), name, pos, staticOnly);
                if (f != null) {
                    return f;
                }
            }
            // Methods an (abstract) class gets only from its interfaces, and those of extended interfaces.
            for (ClassT itf : interfacesOf(owner)) {
                Found f = findMembers(b, itf, itf.cls(), name, pos, staticOnly);
                if (f != null) {
                    return f;
                }
            }
            if (cls.isInterface) {
                return findMembers(b, objectType(), objectType().cls(), name, pos, staticOnly);
            }
            return null;
        }
        if (cls.dynamic && owner == null) {
            // Static members are inherited, as in Java: Square.make() finds Shape.make().
            // They can't depend on shape values, so the parents are found by name.
            return findInheritedStatic(b, cls, name, pos, new HashSet<>());
        }
        return null;
    }

    private Found findInheritedStatic(Body b, ClassInfo cls, String name, Pos pos, Set<ClassInfo> seen) {
        if (!seen.add(cls)) {
            return null; // an inheritance cycle, reported elsewhere
        }
        for (String parentName : parentNames(cls)) {
            ClassInfo p = lookupClass(parentName, cls);
            if (p == null) {
                continue;
            }
            List<MemberInfo> list = p.members.get(name);
            if (list != null) {
                return findMembers(b, null, p, name, pos, true);
            }
            Found f = findInheritedStatic(b, p, name, pos, seen);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private boolean canAccess(ClassInfo accessor, ClassInfo declaring, Modifiers mods) {
        if (mods.access() == Access.PUBLIC || accessor == declaring || declaring.isInterface) {
            return true; // interface methods are always public
        }
        if (accessor != null && accessor.topLevel() == declaring.topLevel()) {
            return true; // as in Java, a type and the types nested in it share their private members
        }
        for (TypeRef f : mods.friends()) {
            if (lookupClass(f.name(), declaring) == accessor) {
                return true;
            }
        }
        return false;
    }

    private Type checkSelect(Body b, Select s) {
        if (s.target() instanceof SuperRef sr) {
            ClassT parent = superType(b, sr.pos());
            if (parent == null) {
                return Type.ERROR;
            }
            return selectOn(b, s, parent, true);
        }
        if (packagedType(b, s) instanceof ClassInfo c) {
            return visibleClass(c, b.cls, s.pos()) == null ? Type.ERROR : new ClassRefT(c);
        }
        Type target = checkExpr(b, s.target(), null);
        return selectOn(b, s, target, false);
    }

    /**
     * com.example.Util written in an expression: a type named with its package (§2.4), when
     * its first name means nothing else here (a variable, field or type), as in Java.
     */
    private ClassInfo packagedType(Body b, Select s) {
        StringBuilder dotted = new StringBuilder(s.name());
        Expr e = s.target();
        while (e instanceof Select inner) {
            dotted.insert(0, inner.name() + ".");
            e = inner.target();
        }
        if (!(e instanceof Name head)) {
            return null;
        }
        String first = head.name();
        if (lookupLocal(b, first) != null || b.env.values.containsKey(first) || lookupClass(first, b.cls) != null
                || quietly(() -> findMembers(b, b.self, b.cls, first, head.pos(), b.isStatic)) != null) {
            return null;
        }
        ClassInfo c = lookupClass(first + "." + dotted, b.cls);
        return c != null && !c.pkg.isEmpty() ? c : null;
    }

    private Type selectOn(Body b, Select s, Type target, boolean viaSuper) {
        ClassT owner;
        ClassInfo cls;
        boolean staticAccess = false;
        switch (target) {
            case ErrorT e -> {
                return Type.ERROR;
            }
            case ClassT ct -> {
                owner = ct;
                cls = ct.cls();
            }
            case HandleT h when h.target() instanceof ClassT ct -> {
                owner = ct;
                cls = ct.cls();
            }
            case ClassRefT cr when nestedIn(cr.cls(), s.name()) instanceof ClassInfo inner -> {
                // Outer.Inner: a nested type, used for its statics or its constants (§2.3)
                return visibleClass(inner, b.cls, s.pos()) == null ? Type.ERROR : new ClassRefT(inner);
            }
            case ClassRefT cr -> {
                owner = null;
                cls = cr.cls();
                staticAccess = true;
            }
            case AddrT a -> {
                report(s.pos(), "cannot use . on an address; dereference first: (&p)." + s.name());
                return Type.ERROR;
            }
            case ParamT p -> {
                return Type.ERROR; // unknown until T is: checked for each type it is used with (§12)
            }
            default -> {
                report(s.pos(), target + " has no members");
                return Type.ERROR;
            }
        }
        if (isJavaType(cls)) {
            JavaFound f = javaField(cls, owner, s.name(), staticAccess);
            if (f != null && !javaAccessible(b.cls, f.m())) {
                report(s.pos(), "the field " + s.name() + " of " + javaName(declaringClass(f.m())) + " is protected, so only code of a"
                        + " class below it can use it");
                return Type.ERROR;
            }
            if (f == null) {
                boolean method = !javaMethods(cls, owner, s.name(), staticAccess, null).isEmpty();
                report(s.pos(), method ? "method " + s.name() + " must be called: " + s.name() + "(...)"
                        : javaName(cls) + " has no " + (staticAccess ? "static " : "") + "field " + s.name()
                        + (staticAccess && javaField(cls, null, s.name(), false) != null ? "; it is an instance field, of an object" : ""));
                return Type.ERROR;
            }
            boolean isStatic = f.m().member.modifiers().isStatic();
            b.selected.put(s, f.m());
            b.owners.put(s, isStatic ? null : f.owner());
            return javaCaptured(memberFieldType(isStatic ? null : f.owner(), f.m(), b.cls, (Field) f.m().member), true);
        }
        Found found = findMembers(b, owner, cls, s.name(), s.pos(), staticAccess);
        if (found == null) {
            report(s.pos(), cls.name + " has no member " + s.name());
            return Type.ERROR;
        }
        if (found.error) {
            return Type.ERROR;
        }
        MemberInfo m = found.members.get(0);
        if (m.member instanceof Method) {
            report(s.pos(), "method " + s.name() + " must be called: " + s.name() + "(...)");
            return Type.ERROR;
        }
        if (!javaAccessible(b.cls, m)) {
            report(s.pos(), "the field " + s.name() + " of " + javaName(declaringClass(m)) + " is protected, so only code of a"
                    + " class below it can use it");
            return Type.ERROR;
        }
        if (found.owner != null && found.owner.cls().isVirtual((Field) m.member) && !viaSuper) {
            // shape.name through an interface or abstract class: the object's own, through its getter (§30.4)
            Call get = new Call(s.pos(), new Select(s.pos(), s.target(), "get " + s.name()), List.of(), List.of());
            b.rewritten.put(s, get);
            return checkExpr(b, get, null);
        }
        b.selected.put(s, m);
        b.owners.put(s, found.owner);
        return memberFieldType(found.owner, m, b.cls, (Field) m.member);
    }

    /** The type nested in {@code c} with this name, a Java class's among them (Map.Entry); null if none. */
    private ClassInfo nestedIn(ClassInfo c, String name) {
        ClassInfo inner = classes.get(c.qualifiedName() + "." + name);
        return inner == null && c.java != null ? javaClass(c.java.name() + "$" + name) : inner;
    }

    /** The parent type for `super`, or null (reported). */
    private ClassT superType(Body b, Pos pos) {
        if (!b.cls.dynamic || b.self == null || b.isStatic) {
            report(pos, "super is only available in instance members of classes");
            return null;
        }
        ClassT p = parentOf(b.self);
        if (p == null) {
            report(pos, "the parent of " + b.self + " depends on its values, so super cannot be resolved here");
        }
        return p;
    }

    // ================================================================ calls

    private Type checkCall(Body b, Call c) {
        List<Type> argTypes = new ArrayList<>();
        for (Expr a : c.args()) {
            argTypes.add(checkExpr(b, a, null));
        }
        if (argTypes.stream().anyMatch(Type::isError)) {
            // Still resolve the callee for its own errors.
            if (!(c.callee() instanceof SuperRef)) {
                calleeCandidates(b, c);
            }
            return Type.ERROR;
        }

        if (c.callee() instanceof SuperRef sr) {
            if (!b.inConstructor) {
                report(sr.pos(), "super(...) calls the parent constructor and is only allowed in constructors");
                return Type.ERROR;
            }
            ClassT parent = superType(b, sr.pos());
            if (parent == null) {
                return Type.ERROR;
            }
            if (parent.cls().java != null) {
                // A Java parent's constructor, public or protected, as Java chooses (docs/JAVA.md §5).
                List<MemberInfo> ctors = parent.cls().constructors;
                if (ctors.isEmpty()) {
                    report(c.pos(), javaName(parent.cls()) + " has no constructor a subclass can call");
                    return Type.ERROR;
                }
                resolveJava(b, "super", parent, parent, ctors, Map.of(), argTypes, c.pos(), c);
                if (b.calls.get(c) instanceof CallInfo ci) {
                    b.calls.put(c, new CallInfo(CallKind.SUPER_CONSTRUCTOR, ci.member(), ci.owner(), ci.binders(), false, false));
                }
                checkLambdaArgs(b, c, c.args());
                return Type.VOID;
            }
            resolveConstructor(b, parent, argTypes, c.pos(), c, CallKind.SUPER_CONSTRUCTOR);
            checkLambdaArgs(b, c, c.args());
            return Type.VOID;
        }

        Candidates cands = calleeCandidates(b, c);
        if (cands == null) {
            return b.componentReads.containsKey(c) ? b.componentReads.get(c) : Type.ERROR;
        }
        Type result = resolveCallOverload(b, c, cands, argTypes);
        checkLambdaArgs(b, c, c.args());
        if (isRawAssembly(b.calls.get(c)) && !(argTypes.get(0) instanceof StrConstT s && s.value() != null)) {
            report(c.args().get(0).pos(), "System.rawAssembly needs its code as a string literal: a native compiler"
                    + " would assemble it when compiling, so it can't be computed while the program runs");
        }
        CallInfo resolved = b.calls.get(c);
        if (resolved != null && resolved.kind() != CallKind.STATIC
                && (c.callee() instanceof Name || (c.callee() instanceof Select s && s.target() instanceof SuperRef))) {
            noteUsesThis(b); // an implicit-this call inside a lambda needs the object
        }
        return result;
    }

    /** Whether a member is one of a record's components: its instance fields (§2.5). */
    private static boolean isComponent(ClassInfo c, MemberInfo m) {
        return c != null && c.kind == Kind.RECORD && m.member instanceof Field f && !f.modifiers().isStatic();
    }

    /** p.x() on a record: the call reads the component, as the field p.x. */
    private Candidates componentRead(Body b, Call c, Expr field) {
        b.rewritten.put(c, field);
        b.componentReads.put(c, checkExpr(b, field, null));
        return null;
    }

    private Type resolveCallOverload(Body b, Call c, Candidates cands, List<Type> argTypes) {
        if (cands.methods.stream().allMatch(m -> javaMembers.containsKey(m.member))) {
            Type r = resolveJava(b, cands.name, cands.owner, cands.qualifier, cands.methods,
                    cands.javaOwners == null ? Map.of() : cands.javaOwners, argTypes, c.pos(), c);
            if (cands.viaSuper && b.javaCalls.get(c) instanceof JavaCall jc) {
                b.javaCalls.put(c, new JavaCall(jc.qualifier(), jc.varargsFrom(), true)); // super.m(): the parent's, not an override
                if (b.calls.get(c) instanceof CallInfo ci && ((Method) ci.member().member).modifiers().isAbstract()) {
                    report(c.pos(), cands.name + " is abstract in " + javaName(declaringClass(ci.member()))
                            + "; there is no implementation to call");
                }
            }
            return r;
        }
        List<MemberInfo> seal = cands.methods.stream().filter(m -> !javaMembers.containsKey(m.member)).toList();
        return resolveOverload(b, cands.name, cands.owner, seal, argTypes, c.pos(), cands.staticAccess,
                c, cands.viaSuper ? CallKind.SUPER_METHOD : CallKind.METHOD);
    }

    /**
     * @param qualifier  for a method of a Java class, the type the call goes through (docs/JAVA.md §4)
     * @param javaOwners for those, the type each is found in, as the call's object sees it
     */
    private record Candidates(String name, ClassT owner, List<MemberInfo> methods, boolean staticAccess,
                              boolean viaSuper, ClassT qualifier, Map<MemberInfo, ClassT> javaOwners) {
        Candidates(String name, ClassT owner, List<MemberInfo> methods, boolean staticAccess, boolean viaSuper) {
            this(name, owner, methods, staticAccess, viaSuper, null, null);
        }
    }

    private Candidates calleeCandidates(Body b, Call c) {
        switch (c.callee()) {
            case Name n -> {
                if (lookupLocal(b, n.name()) != null || b.env.values.containsKey(n.name())) {
                    report(n.pos(), n.name() + " is not a method");
                    return null;
                }
                Found f = findMembers(b, b.self, b.cls, n.name(), n.pos(), b.isStatic);
                if (f == null) {
                    // import static Math.max; and then max(a, b): overloads from every class the imports name (§2.4)
                    List<MemberInfo> imported = staticImports(b.cls, n.name()).stream()
                            .filter(sm -> sm.m().member instanceof Method).map(StaticMember::m).toList();
                    if (!imported.isEmpty()) {
                        return new Candidates(n.name(), null, imported, true, false);
                    }
                    String renamed = renamedHint(b.cls, n.name(), true);
                    report(n.pos(), "unknown method " + n.name() + (renamed.isEmpty() ? staticMethodHint(n.name()) : renamed));
                    return null;
                }
                if (f.error) {
                    return null;
                }
                if (!(f.members.get(0).member instanceof Method)) {
                    if (isComponent(b.cls, f.members.get(0)) && c.args().isEmpty()) {
                        return componentRead(b, c, n); // x() inside the record
                    }
                    report(n.pos(), n.name() + " is a field, not a method");
                    return null;
                }
                if (javaMembers.containsKey(f.members.get(0).member)) {
                    // A method a Seal class inherits from its Java parent (docs/JAVA.md §5), on this.
                    return javaInherited(b, n.name(), b.self, f, b.isStatic, false, n.pos());
                }
                return new Candidates(n.name(), f.owner, f.members, b.isStatic, false);
            }
            case Select s -> {
                Type target;
                if (s.target() instanceof SuperRef sr) {
                    ClassT p = superType(b, sr.pos());
                    if (p == null) {
                        return null;
                    }
                    target = p;
                } else {
                    target = b.types.containsKey(s.target()) ? b.types.get(s.target()) : checkExpr(b, s.target(), null);
                    target = defaultLiteral(b, s.target(), target); // "abc".length()
                }
                ClassT owner;
                ClassInfo cls;
                boolean staticAccess = false;
                switch (target) {
                    case ErrorT e -> {
                        return null;
                    }
                    case ClassT ct -> {
                        owner = ct;
                        cls = ct.cls();
                    }
                    case HandleT h when h.target() instanceof ClassT ct -> {
                        owner = ct;
                        cls = ct.cls();
                    }
                    case ClassRefT cr -> {
                        owner = null;
                        cls = cr.cls();
                        staticAccess = true;
                    }
                    case ArrayT at when fixedArrayMethods(at) != null -> {
                        owner = fixedArrayMethods(at); // xs.sort() on an array<T><<N>> (§21.1)
                        cls = owner.cls();
                    }
                    case AddrT a -> {
                        report(s.pos(), "cannot call a method on an address; dereference first: (&p)." + s.name() + "(...)");
                        return null;
                    }
                    case ParamT p -> {
                        return null; // unknown until T is: checked for each type it is used with (§12)
                    }
                    default -> {
                        report(s.pos(), target + " has no methods");
                        return null;
                    }
                }
                if (isJavaType(cls)) {
                    Map<MemberInfo, ClassT> owners = new IdentityHashMap<>();
                    List<MemberInfo> methods = javaMethods(cls, owner, s.name(), staticAccess, owners);
                    boolean any = !methods.isEmpty();
                    methods.removeIf(m -> !javaAccessible(b.cls, m));
                    if (methods.isEmpty() && any) {
                        report(s.pos(), "`" + s.name() + "` of " + javaName(cls) + " is protected, so only code of a class below it can call it");
                        return null;
                    }
                    if (methods.isEmpty()) {
                        report(s.pos(), javaName(cls) + " has no " + (staticAccess ? "static " : "") + "method " + s.name()
                                + javaNoMethod(cls, s.name(), staticAccess));
                        return null;
                    }
                    return new Candidates(s.name(), owner, methods, staticAccess, s.target() instanceof SuperRef,
                            owner != null ? owner : new ClassT(cls, List.of(), List.of(), 0), owners);
                }
                Found f = findMembers(b, owner, cls, s.name(), s.pos(), staticAccess);
                if (f == null) {
                    report(s.pos(), cls.name + " has no method " + s.name() + stringMethodHint(cls, s.name()));
                    return null;
                }
                if (f.error) {
                    return null;
                }
                if (f.members.get(0).member instanceof Method && javaMembers.containsKey(f.members.get(0).member)) {
                    // A method a Seal class inherits from its Java parent (docs/JAVA.md §5).
                    return javaInherited(b, s.name(), owner, f, staticAccess, false, s.pos());
                }
                if (!(f.members.get(0).member instanceof Method)) {
                    if (isComponent(cls, f.members.get(0)) && c.args().isEmpty()) {
                        return componentRead(b, c, s); // p.x(): a record's component, read as in Java (§2.5)
                    }
                    report(s.pos(), s.name() + " is a field, not a method");
                    return null;
                }
                return new Candidates(s.name(), f.owner, f.members, staticAccess, s.target() instanceof SuperRef);
            }
            default -> {
                checkExpr(b, c.callee(), null);
                report(c.pos(), "this expression cannot be called");
                return null;
            }
        }
    }

    /** How a class is named in a message: Java's own as Java's (Java's java.util.ArrayList), Seal's by their names. */
    private static String javaName(ClassInfo c) {
        return c.java != null && !c.pkg.equals("seal.java") ? "Java's " + c.qualifiedName() : c.name;
    }

    /**
     * The methods of this name that a Java type has, as Java finds them: its own and those it
     * inherits from its parent and interfaces, each once (the lowest declaration of a signature);
     * java.lang.Object's for an interface; and seal.java's own, as JavaString's `of`. An interface's
     * static methods are called through it alone, as in Java. Into {@code owners}, if given, goes
     * the type each is found in, as {@code owner} sees it: Collection<JavaString> for an
     * ArrayList<JavaString>'s addAll (docs/JAVA.md §4.1).
     */
    private List<MemberInfo> javaMethods(ClassInfo cls, ClassT owner, String name, boolean staticOnly,
                                         Map<MemberInfo, ClassT> owners) {
        List<MemberInfo> out = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        javaSupertypes(cls, owner, (c, inst) -> {
            for (MemberInfo m : c.members.getOrDefault(name, List.of())) {
                if (!(m.member instanceof Method md) || (staticOnly && !md.modifiers().isStatic())
                        || (c != cls && c.isInterface && md.modifiers().isStatic())) {
                    continue;
                }
                JavaClasses.JMember jm = javaMembers.get(md);
                if (jm == null && c != cls && md.modifiers().isStatic()) {
                    continue; // seal.java's own static methods (JavaObject.of) aren't inherited; hash() is
                }
                String sig = jm == null ? "seal " + System.identityHashCode(md)
                        : jm.descriptor().substring(0, jm.descriptor().indexOf(')') + 1);
                if (signatures.add(sig)) {
                    out.add(m);
                    if (owners != null && inst != null) {
                        owners.put(m, inst);
                    }
                }
            }
            return false;
        });
        return out;
    }

    /** A member of a Java type, and the type it is found in, as the type it was looked for in sees it. */
    private record JavaFound(MemberInfo m, ClassT owner) {
    }

    /** The field of this name a Java type has or inherits, the lowest one; null if none. */
    private JavaFound javaField(ClassInfo cls, ClassT owner, String name, boolean staticOnly) {
        JavaFound[] found = {null};
        javaSupertypes(cls, owner, (c, inst) -> {
            for (MemberInfo m : c.members.getOrDefault(name, List.of())) {
                if (m.member instanceof Field f && (!staticOnly || f.modifiers().isStatic())) {
                    found[0] = new JavaFound(m, inst);
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    /**
     * Visits a Java type and those above it, nearest first, each once, with the type each is as
     * {@code owner} sees it (null when there is no object: static members); then, for an
     * interface, java.lang.Object. Stops when {@code visit} says so.
     */
    private void javaSupertypes(ClassInfo cls, ClassT owner, java.util.function.BiPredicate<ClassInfo, ClassT> visit) {
        record Step(ClassInfo cls, ClassT inst) {
        }
        Deque<Step> todo = new ArrayDeque<>(List.of(new Step(cls, owner)));
        Set<ClassInfo> seen = new HashSet<>();
        boolean object = cls.isInterface;
        while (!todo.isEmpty()) {
            Step st = todo.poll();
            if (!seen.add(st.cls()) || seen.size() > 4 * MAX_INSTANTIATION_DEPTH) {
                continue;
            }
            if (visit.test(st.cls(), st.inst())) {
                return;
            }
            if (st.inst() != null) {
                ClassT parent = parentOf(st.inst());
                if (parent != null) {
                    todo.add(new Step(parent.cls(), parent));
                }
                for (ClassT i : interfacesOf(st.inst())) {
                    todo.add(new Step(i.cls(), i));
                }
            } else {
                for (String parent : parentNames(st.cls())) {
                    if (lookupClass(parent, st.cls()) instanceof ClassInfo p) {
                        todo.add(new Step(p, null));
                    }
                }
            }
            if (todo.isEmpty() && object && classes.get("seal.java.JavaObject") instanceof ClassInfo o) {
                object = false; // an interface's objects are objects
                todo.add(new Step(o, owner == null ? null : new ClassT(o, List.of(), List.of(), 0)));
            }
        }
    }

    /** The type {@code t} is as one of its parents or interfaces, of class {@code c} (List<JavaString> for an ArrayList<JavaString>); or null. */
    private ClassT asSuper(ClassT t, ClassInfo c) {
        ClassT[] found = {null};
        javaSupertypes(t.cls(), t, (k, inst) -> {
            if (k == c) {
                found[0] = inst;
                return true;
            }
            return false;
        });
        return found[0];
    }

    /**
     * The candidates for a call of a method a Seal class inherits from its Java parent (docs/JAVA.md
     * §5): all of Java's of that name the parent has, as Java finds them, through the Seal class's
     * type; the protected ones only for code of a class below the one declaring them.
     */
    private Candidates javaInherited(Body b, String name, ClassT owner, Found f, boolean staticOnly, boolean viaSuper, Pos pos) {
        Map<MemberInfo, ClassT> owners = new IdentityHashMap<>();
        ClassT javaOwner = f.owner != null ? f.owner : null;
        ClassInfo javaCls = f.owner != null ? f.owner.cls() : declaringClass(f.members.get(0));
        List<MemberInfo> methods = javaMethods(javaCls, javaOwner, name, staticOnly, owners);
        methods.removeIf(m -> !javaAccessible(b.cls, m));
        if (methods.isEmpty()) {
            report(pos, "`" + name + "` of " + javaName(javaCls) + " is protected, so only code of a class below it can call it");
            return null;
        }
        return new Candidates(name, javaOwner, methods, staticOnly, viaSuper,
                owner != null ? owner : new ClassT(javaCls, List.of(), List.of(), 0), owners);
    }

    /** Whether code of {@code from} may use a Java member: a public one, or a protected one in a class below the one declaring it. */
    private boolean javaAccessible(ClassInfo from, MemberInfo m) {
        JavaClasses.JMember jm = javaMembers.get(m.member);
        if (jm == null || (jm.flags() & JavaClasses.PROTECTED) == 0) {
            return true;
        }
        ClassInfo declaring = declaringClass(m);
        for (ClassInfo c = from; c != null; c = c.outer) {
            if (inheritsFrom(c, declaring, 0)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a class is, or is below, another, by the names of its parents. */
    private boolean inheritsFrom(ClassInfo c, ClassInfo ancestor, int depth) {
        if (c == ancestor) {
            return true;
        }
        if (depth > MAX_INSTANTIATION_DEPTH) {
            return false;
        }
        for (String parent : parentNames(c)) {
            if (lookupClass(parent, c) instanceof ClassInfo p && inheritsFrom(p, ancestor, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a Java class or interface above {@code self} has a method of this name and these
     * parameter types that isn't abstract: one Java's code already implements an interface's with.
     */
    private boolean javaProvides(ClassT self, String name, List<Type> params) {
        boolean[] found = {false};
        List<ClassT> starts = new ArrayList<>(allInterfacesOf(self));
        int depth = 0;
        for (ClassT p = parentOf(self); p != null && depth++ < MAX_INSTANTIATION_DEPTH; p = parentOf(p)) {
            if (p.cls().java != null) {
                starts.add(0, p);
                break;
            }
        }
        for (ClassT start : starts) {
            if (start.cls().java == null || found[0]) {
                continue;
            }
            javaSupertypes(start.cls(), start, (k, inst) -> {
                for (MemberInfo m : k.members.getOrDefault(name, List.of())) {
                    if (m.member instanceof Method md && !md.modifiers().isStatic() && javaMembers.get(md) instanceof JavaClasses.JMember jm
                            && (jm.flags() & JavaClasses.ABSTRACT) == 0 && paramTypes(inst, m, Map.of()).equals(params)) {
                        found[0] = true;
                        return true;
                    }
                }
                return false;
            });
        }
        return found[0];
    }

    /**
     * A Seal class's Java parent (docs/JAVA.md §5): a public class that isn't final, with a constructor
     * that takes nothing if one of the Seal class's constructors doesn't call another with super(...).
     */
    private void checkJavaParent(ClassInfo c, ClassT parent, Pos pos) {
        JavaClasses.JClass jc = parent.cls().java;
        if ((jc.flags() & JavaClasses.FINAL) != 0) {
            report(pos, javaName(parent.cls()) + " is final, so a class can't extend it");
            return;
        }
        boolean needsNoArgs = c.constructors.isEmpty() || c.constructors.stream().anyMatch(k -> {
            List<Stmt> body = ((Constructor) k.member).body() == null ? List.of() : ((Constructor) k.member).body().stmts();
            return body.isEmpty() || !(body.get(0) instanceof ExprStmt es && es.expr() instanceof Call call && call.callee() instanceof SuperRef);
        });
        if (needsNoArgs && parent.cls().constructors.stream().noneMatch(k -> ((Constructor) k.member).params().isEmpty())) {
            report(pos, javaName(parent.cls()) + " has no constructor that takes nothing, so each constructor of " + c.name
                    + " calls one of its others first, with super(...)");
        }
    }

    /**
     * A Seal class's methods that override its Java parent's (docs/JAVA.md §5): not a final one, nor
     * a static one, and returning what Java's returns.
     */
    private void checkJavaOverrides(ClassInfo c) {
        for (Shape s : c.shapes) {
            ClassT self = selfType(c, s.index);
            ClassT parent = parentOf(self);
            ClassT javaParent = parent;
            for (int depth = 0; javaParent != null && javaParent.cls().java == null; depth++) {
                javaParent = depth < MAX_INSTANTIATION_DEPTH ? parentOf(javaParent) : null; // stops at a cycle, reported elsewhere
            }
            if (javaParent == null) {
                continue;
            }
            for (MemberInfo m : c.all) {
                if (!(m.member instanceof Method md) || md.modifiers().isStatic() || !m.existsIn(s.index) || md.name().contains(" ")) {
                    continue;
                }
                List<Type> params = paramTypes(self, m, Map.of());
                ClassT start = javaParent;
                javaSupertypes(start.cls(), start, (k, inst) -> {
                    for (MemberInfo jm : k.members.getOrDefault(md.name(), List.of())) {
                        if (!(jm.member instanceof Method jmd) || !(javaMembers.get(jmd) instanceof JavaClasses.JMember j)
                                || !paramTypes(inst, jm, Map.of()).equals(params)) {
                            continue;
                        }
                        Type want = javaCaptured(returnTypeOf(inst, jm, Map.of()), true);
                        Type got = returnTypeOf(self, m, Map.of());
                        if (j.isStatic()) {
                            report(md.pos(), md.name() + " hides " + javaName(k) + "." + md.name() + ", which is static and can't be overridden");
                        } else if (j.isFinal()) {
                            report(md.pos(), md.name() + " overrides " + javaName(k) + "." + md.name() + ", which is final");
                        } else if (!want.equals(got) && !want.isError() && !got.isError()) {
                            report(md.pos(), c.name + "." + md.name() + " returns " + got + ", but " + javaName(k) + "." + md.name()
                                    + ", which it overrides, returns " + want);
                        }
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    /** Why a Java type has no method so named, when there is more to say. */
    private String javaNoMethod(ClassInfo cls, String name, boolean staticOnly) {
        if (staticOnly && !javaMethods(cls, null, name, false, null).isEmpty()) {
            return "; it is an instance method, called on an object";
        }
        if (javaField(cls, null, name, false) != null) {
            return "; " + name + " is a field, read without (...)";
        }
        return "";
    }

    /**
     * A call of a Java method or constructor, chosen as Java chooses (docs/JAVA.md §4): of those the
     * arguments fit by Java's rules (an object of a subclass where its class is wanted, a primitive
     * widened), the most specific; if none fits, those taking varargs. Java's middle step, boxing,
     * never happens, as nothing crosses between Seal and Java by itself: a JavaInt doesn't go where
     * an Integer is wanted, and a Seal int doesn't go where Java's int is. A generic method's type
     * arguments are given, or worked out from the arguments, then from the type wanted (§4.1).
     *
     * @param receiver  the object's type (the new one's, for a constructor); null for a static method
     * @param qualifier the type the JVM's call names: the object's, or the class written
     * @param owners    the type each method is found in, as the receiver sees it
     */
    private Type resolveJava(Body b, String name, ClassT receiver, ClassT qualifier, List<MemberInfo> methods,
                             Map<MemberInfo, ClassT> owners, List<Type> args, Pos pos, Expr site) {
        Type expected = site instanceof Call call ? b.callExpected.get(call) : null;
        List<Type> explicit = new ArrayList<>();
        if (site instanceof Call call) {
            for (TypeRef t : call.typeArgs()) {
                explicit.add(resolveType(t, b.env, Opts.PLAIN));
            }
            if (explicit.stream().anyMatch(Type::isError)) {
                return Type.ERROR;
            }
        }
        Body outerCall = javaCallBody;
        javaCallBody = b; // for a lambda argument's result, which may decide which methods it fits
        try {
            return resolveJavaIn(b, name, receiver, qualifier, methods, owners, args, pos, site, expected, explicit);
        } finally {
            javaCallBody = outerCall;
        }
    }

    /** The body a Java call being resolved is in, while it is. */
    private Body javaCallBody;

    private Type resolveJavaIn(Body b, String name, ClassT receiver, ClassT qualifier, List<MemberInfo> methods,
                               Map<MemberInfo, ClassT> owners, List<Type> args, Pos pos, Expr site, Type expected,
                               List<Type> explicit) {
        boolean ctor = methods.get(0).member instanceof Constructor;
        record Fit(MemberInfo m, ClassT owner, List<Type> params, boolean varargs, Map<String, CVal> bound) {
        }
        List<Fit> fits = new ArrayList<>();
        String lastMismatch = null;
        for (int phase = 0; phase < 2 && fits.isEmpty(); phase++) {
            for (MemberInfo m : methods) {
                boolean isStatic = m.member.modifiers().isStatic();
                ClassT mo = isStatic ? null : owners.getOrDefault(m, receiver);
                boolean varargs = phase == 1;
                if (varargs && !javaMembers.get(m.member).isVarargs()) {
                    continue;
                }
                Map<String, CVal> bound = javaTypeArguments(b, m, mo, args, varargs, expected, explicit);
                if (bound == null) {
                    lastMismatch = name + " takes " + ((Method) m.member).typeParams().size() + " type argument(s), not "
                            + explicit.size();
                    continue;
                }
                List<Type> ps = paramTypes(mo, m, bound).stream().map(t -> javaCaptured(t, false)).toList();
                if (!varargs) {
                    if (ps.size() == args.size() && javaFitAll(args, ps)) {
                        fits.add(new Fit(m, mo, ps, false, bound));
                    }
                } else if (args.size() >= ps.size() - 1) {
                    Type element = javaElement(ps.get(ps.size() - 1));
                    List<Type> spread = new ArrayList<>(ps.subList(0, ps.size() - 1));
                    while (spread.size() < args.size()) {
                        spread.add(element);
                    }
                    if (element != null && javaFitAll(args, spread)) {
                        fits.add(new Fit(m, mo, spread, true, bound));
                    }
                }
            }
        }
        String what = ctor ? "the constructor of " + javaName(receiver.cls()) : name;
        if (fits.isEmpty()) {
            List<String> takes = methods.stream().map(m -> "(" + paramTypes(m.member.modifiers().isStatic() ? null
                    : owners.getOrDefault(m, receiver), m, Map.of()).stream().map(t -> javaCaptured(t, false).toString())
                    .collect(Collectors.joining(", ")) + ")").distinct().toList();
            String given = "(" + args.stream().map(Type::toString).collect(Collectors.joining(", ")) + ")";
            report(pos, lastMismatch != null && methods.size() == 1 ? lastMismatch
                    : (methods.size() == 1 ? what + " takes " + takes.get(0) + ", not " + given
                    : "no overload of " + what + " takes " + given + "; " + (ctor ? "they take " : "its overloads take ")
                    + (takes.size() > 6 ? String.join(", ", takes.subList(0, 6)) + ", ..." : String.join(", ", takes)))
                    + javaArgumentHint(args));
            return Type.ERROR;
        }
        List<Fit> maximal = fits.stream().filter(f -> fits.stream().allMatch(o -> o == f
                || javaMoreSpecific(f.params(), o.params(), args))).toList();
        List<Fit> best = maximal.size() > 1 && maximal.stream().allMatch(f -> f.params().equals(maximal.get(0).params()))
                ? List.of(maximal.get(0)) : maximal; // one signature, declared again above (an interface's method a class implements)
        if (best.size() != 1) {
            List<Fit> tied = best.isEmpty() ? fits : best;
            report(pos, "ambiguous call to " + what + ": " + tied.stream().map(f -> "(" + f.params().stream().map(Type::toString)
                    .collect(Collectors.joining(", ")) + ")").distinct().collect(Collectors.joining(" and "))
                    + " fit equally well; cast an argument to the type meant, as (JavaString)null");
            return Type.ERROR;
        }
        Fit chosen = best.get(0);
        boolean isStatic = chosen.m().member.modifiers().isStatic();
        CallKind kind = ctor ? CallKind.CONSTRUCTOR : isStatic ? CallKind.STATIC : CallKind.METHOD;
        if (site != null) {
            b.calls.put(site, new CallInfo(kind, chosen.m(), chosen.owner(), Map.copyOf(chosen.bound()), false, false));
            ClassInfo declaring = declaringClass(chosen.m());
            ClassT q = qualifier != null ? qualifier : receiver != null && !isStatic ? receiver
                    : new ClassT(declaring, List.of(), List.of(), 0);
            int fixed = paramTypes(chosen.owner(), chosen.m(), chosen.bound()).size() - 1;
            b.javaCalls.put(site, new JavaCall(q, chosen.varargs() ? fixed : -1));
        }
        return ctor ? Type.VOID : javaCaptured(returnTypeOf(chosen.owner(), chosen.m(), chosen.bound()), true);
    }

    /**
     * A Java generic method's type arguments for a call (docs/JAVA.md §4.1): those given, in order;
     * or worked out from the arguments (List.of(a, b) of two JavaStrings is a List<JavaString>), then
     * from the type the call's value goes to (List<JavaString> l = List.of()); a type no argument
     * decides is what it erases to. Null if the wrong number are given.
     */
    private Map<String, CVal> javaTypeArguments(Body b, MemberInfo m, ClassT owner, List<Type> args, boolean varargs,
                                                Type expected, List<Type> explicit) {
        if (!(m.member instanceof Method md) || md.typeParams().isEmpty()) {
            return explicit.isEmpty() ? Map.of() : null;
        }
        Map<String, Type> found = new LinkedHashMap<>();
        if (!explicit.isEmpty()) {
            if (explicit.size() != md.typeParams().size()) {
                return null;
            }
            for (int i = 0; i < explicit.size(); i++) {
                found.put(md.typeParams().get(i), explicit.get(i));
            }
        } else {
            List<Type> declared = paramTypes(owner, m, Map.of());
            for (int i = 0; i < args.size() && !declared.isEmpty(); i++) {
                Type p = !varargs || i < declared.size() - 1 ? (i < declared.size() ? declared.get(i) : null)
                        : javaElement(declared.get(declared.size() - 1));
                if (p != null) {
                    inferJava(p, args.get(i), md.typeParams(), found);
                }
            }
            // Then what a lambda gives, once its parameters' types are known: R in map(Function<? super T, ? extends R>).
            for (int i = 0; i < args.size() && i < declared.size(); i++) {
                if (!(args.get(i) instanceof LambdaT lt) || found.keySet().containsAll(md.typeParams())) {
                    continue;
                }
                Map<String, CVal> sofar = new HashMap<>();
                found.forEach((k, v) -> sofar.put(k, new TypeV(v)));
                Impl fn = functionalMethod(substitute(declared.get(i), sofar));
                if (fn == null) {
                    continue;
                }
                List<Type> lps = paramTypes(fn.owner(), fn.member(), Map.of());
                Type fret = returnTypeOf(fn.owner(), fn.member(), Map.of());
                if (lps.stream().anyMatch(Checker::mentionsTypeParam) || !mentionsTypeParam(fret)) {
                    continue;
                }
                Type result = lambdaResultType(b, lt.lambda(), lps);
                if (result != null) {
                    inferJava(fret, result, md.typeParams(), found);
                }
            }
            if (expected != null && !found.keySet().containsAll(md.typeParams())) {
                Map<String, Type> fromTarget = new LinkedHashMap<>();
                inferJava(returnTypeOf(owner, m, Map.of()), expected, md.typeParams(), fromTarget);
                fromTarget.forEach(found::putIfAbsent);
            }
        }
        Map<String, CVal> out = new LinkedHashMap<>();
        Map<String, TypeRef> erased = javaErasures.getOrDefault(md, Map.of());
        ClassInfo declaring = declaringClass(m);
        for (String tp : md.typeParams()) {
            Type t = found.get(tp);
            if (t == null) {
                TypeRef e = erased.get(tp);
                t = e == null ? javaObjectHandle() : quietly(() -> resolveType(e, Env.empty(declaring), Opts.PLAIN));
            }
            out.put(tp, new TypeV(t));
        }
        return out;
    }

    /** What an argument of type {@code a} says of the type variables in a parameter's type {@code p}. */
    private void inferJava(Type p, Type a, List<String> vars, Map<String, Type> found) {
        if (p instanceof ParamT pt && vars.contains(pt.name())) {
            if (!(a instanceof HandleT)) {
                return; // null, or no object: a JavaInt is no type argument
            }
            Type old = found.get(pt.name());
            if (old == null || refSubtype(old, a)) {
                found.put(pt.name(), a);
            } else if (!refSubtype(a, old)) {
                found.put(pt.name(), commonParent(old, a)); // two kinds: List.of(square, circle) holds Shapes, as Java's
            }
            return;
        }
        if (p instanceof WildT w) {
            inferJava(w.bound(), a instanceof WildT aw ? aw.bound() : a, vars, found);
            return;
        }
        if (p instanceof HandleT ph && ph.target() instanceof ClassT pc && !pc.typeArgs().isEmpty()
                && a instanceof HandleT ah && ah.target() instanceof ClassT ac) {
            ClassT as = asSuper(ac, pc.cls());
            if (as != null && as.typeArgs().size() == pc.typeArgs().size()) {
                for (int i = 0; i < pc.typeArgs().size(); i++) {
                    Type arg = as.typeArgs().get(i);
                    inferJava(pc.typeArgs().get(i), arg instanceof WildT aw ? aw.bound() : arg, vars, found);
                }
            }
        }
    }

    /** The nearest type both are, a parent or an interface of the first, as Java's inference finds it; JavaObject if none. */
    private Type commonParent(Type a, Type b) {
        if (!(a instanceof HandleT ah && ah.target() instanceof ClassT ac)) {
            return javaObjectHandle();
        }
        Type[] found = {null};
        javaSupertypes(ac.cls(), ac, (c, inst) -> {
            if (inst != null && !isJavaObject(c) && refSubtype(b, new HandleT(inst))) {
                found[0] = new HandleT(inst);
                return true;
            }
            return false;
        });
        return found[0] != null ? found[0] : javaObjectHandle();
    }

    /**
     * A member's type where it is a wildcard (a List<? extends Number>'s element): what it gives, its
     * bound (or JavaObject for ? super), for a result; its bound, for a parameter, as Seal reads
     * Java's wildcards loosely (docs/JAVA.md §4.1).
     */
    private Type javaCaptured(Type t, boolean result) {
        if (t instanceof WildT w) {
            return result && w.kind() == 2 ? javaObjectHandle() : w.bound();
        }
        return t;
    }

    /**
     * Whether one method's parameters are at least as specific as another's for these arguments,
     * as Java ranks them (JLS §15.12.2.5): each fits where the other's is wanted; for a lambda,
     * a function that gives a value is more specific than one that gives nothing (submit(Callable)
     * over submit(Runnable) for () -> x).
     */
    private boolean javaMoreSpecific(List<Type> mine, List<Type> theirs, List<Type> args) {
        for (int i = 0; i < mine.size() && i < theirs.size(); i++) {
            if (i < args.size() && args.get(i) instanceof LambdaT) {
                Impl a = functionalMethod(mine.get(i));
                Impl o = functionalMethod(theirs.get(i));
                if (a == null || o == null) {
                    return false;
                }
                boolean aVoid = returnTypeOf(a.owner(), a.member(), Map.of()) instanceof VoidT;
                boolean oVoid = returnTypeOf(o.owner(), o.member(), Map.of()) instanceof VoidT;
                if (!(oVoid && !aVoid) && !javaFits(mine.get(i), theirs.get(i))) {
                    return false;
                }
            } else if (!javaFits(mine.get(i), theirs.get(i))) {
                return false;
            }
        }
        return mine.size() == theirs.size();
    }

    private boolean javaFitAll(List<Type> args, List<Type> params) {
        if (args.size() != params.size()) {
            return false;
        }
        for (int i = 0; i < args.size(); i++) {
            if (!javaFits(args.get(i), params.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a value of type {@code a} goes where Java wants a {@code p}, by Java's rules without
     * boxing: the same type, a primitive Java widens (an int to a long), null or an object of a
     * subclass for a reference, an array of objects of a subclass for an array of objects.
     */
    private boolean javaFits(Type a, Type p) {
        if (a.isError() || a.equals(p)) {
            return true;
        }
        if (p instanceof JavaPrimT pp) {
            return a instanceof JavaPrimT ap && javaWidens(ap, pp);
        }
        if (!(p instanceof HandleT ph && ph.target() instanceof ClassT pc)) {
            return false;
        }
        if (a instanceof NullT) {
            return true;
        }
        if (a instanceof LambdaT lt) {
            // A lambda goes where Java wants an interface with one abstract method that takes as many parameters,
            // returning a value if the lambda gives one, and nothing if it can (docs/JAVA.md §5).
            Impl fn = functionalMethod(p);
            if (fn == null || ((Method) fn.member().member).params().size() != lt.lambda().params().size()) {
                return false;
            }
            if (returnTypeOf(fn.owner(), fn.member(), Map.of()) instanceof VoidT) {
                return voidCompatible(lt.lambda());
            }
            if (lt.lambda().expr() != null && javaCallBody != null) {
                // x -> println(x) gives nothing, so it is no Callable's: what the expression gives decides.
                List<Type> lps = paramTypes(fn.owner(), fn.member(), Map.of());
                return lps.stream().anyMatch(Checker::mentionsTypeParam)
                        || !(lambdaResultType(javaCallBody, lt.lambda(), lps) instanceof VoidT);
            }
            return valueCompatible(lt.lambda());
        }
        if (!(a instanceof HandleT ah && ah.target() instanceof ClassT ac)) {
            return false;
        }
        if (!isJavaType(ac.cls())) {
            // A Seal object: of a Seal class as a type argument, of a class implementing a Java interface, or extending
            // a Java class; a Java object then, so also where Java wants a JavaObject.
            return isSubclass(ac, pc);
        }
        if (!isJavaType(pc.cls())) {
            return false;
        }
        if (isJavaArray(ac.cls()) && isJavaArray(pc.cls())) {
            Type ae = ac.typeArgs().get(0);
            Type pe = pc.typeArgs().get(0);
            return ae.equals(pe) || (!(ae instanceof JavaPrimT) && !(pe instanceof JavaPrimT) && javaFits(ae, pe));
        }
        return isSubclass(ac, pc);
    }

    /** Java's widening of primitives (JLS §5.1.2): a byte to a short, int, long, float or double, and so on. */
    private static boolean javaWidens(JavaPrimT from, JavaPrimT to) {
        String wider = switch (from.code()) {
            case 'B' -> "SIJFD";
            case 'S', 'C' -> "IJFD";
            case 'I' -> "JFD";
            case 'J' -> "FD";
            case 'F' -> "D";
            default -> "";
        };
        return wider.indexOf(to.code()) >= 0;
    }

    /** The element type of a JavaArray<T>, T; null for any other type. */
    private static Type javaElement(Type t) {
        return t instanceof HandleT h && h.target() instanceof ClassT ct && isJavaArray(ct.cls()) ? ct.typeArgs().get(0) : null;
    }

    /** "; Seal's int goes into Java by JavaInt.of(...)", for the first argument that is a Seal value, or "". */
    private String javaArgumentHint(List<Type> args) {
        for (Type a : args) {
            String of = javaOf(a);
            if (of != null) {
                return "; Seal's " + a + " goes into Java by " + of + "(...) (docs/JAVA.md §3.1)";
            }
            if (a instanceof LambdaT) {
                return "; a lambda goes where Java wants an interface with one abstract method taking as many parameters";
            }
        }
        return "";
    }

    /** The seal.java method that makes a Java value of a Seal value of this type: JavaInt.of for an int; or null. */
    private String javaOf(Type a) {
        if (isJavaValue(a) || a instanceof NullT || a.isError()) {
            return null;
        }
        Type t = a instanceof IntConstT ic ? (ic.isChar() ? Type.CHAR : Type.INT) : a instanceof FloatConstT ? Type.DOUBLE : a;
        if (t instanceof StrConstT || (t instanceof ClassT ct && ct.cls().name.equals("string") && ct.cls().builtin)) {
            return "JavaString.of";
        }
        if (t instanceof HandleT h && h.target() instanceof ClassT) {
            return "JavaObject.of";
        }
        if (t instanceof ClassT ct && isArrayObject(ct)) {
            return "JavaArray.of";
        }
        for (char code : "IJSBFDCZ".toCharArray()) {
            if (new JavaPrimT(code).sealType().equals(t)) {
                return new JavaPrimT(code) + ".of";
            }
        }
        return t instanceof IntT ? "JavaInt.of" : null;
    }

    /** A candidate method after matching arguments: its return type and whether all arguments matched exactly. */
    private record Match(MemberInfo method, Type returnType, boolean exact, Map<String, CVal> bound,
                         List<Type> params) {
    }

    private Type resolveOverload(Body b, String name, ClassT owner, List<MemberInfo> methods, List<Type> args,
                                 Pos pos, boolean staticAccess, Expr site, CallKind kind) {
        List<Match> matches = new ArrayList<>();
        String lastMismatch = null;
        List<Type> explicitTypes = new ArrayList<>();
        if (site instanceof Call call) {
            for (TypeRef t : call.typeArgs()) {
                explicitTypes.add(resolveType(t, b.env, Opts.PLAIN));
            }
            if (explicitTypes.stream().anyMatch(Type::isError)) {
                return Type.ERROR;
            }
        }
        // Arguments convert as they are or widen; only if no overload takes them so, a number may
        // narrow to its parameter's type, as in an assignment: exit(code) with an int<<64>> code (§41.6).
        String firstMismatch = null;
        boolean narrowed = false;
        for (int phase = 0; phase < 2 && matches.isEmpty() && !args.stream().anyMatch(Checker::mentionsTypeParam); phase++) {
            boolean narrowing = phase == 1;
            narrowed = narrowing;
            for (MemberInfo m : methods) {
                Method md = (Method) m.member;
                if (staticAccess && !md.modifiers().isStatic()) {
                    continue;
                }
                if (md.params().size() != args.size()) {
                    continue;
                }
                Signature sig = signature(owner, m, md.params(), md.returnType());
                Map<String, CVal> bound = new HashMap<>();
                if (!explicitTypes.isEmpty()) {
                    // Main.<int>f(x): the type arguments are given, in order.
                    if (md.typeParams().size() != explicitTypes.size()) {
                        lastMismatch = name + " takes " + md.typeParams().size() + " type argument(s), not " + explicitTypes.size();
                        continue;
                    }
                    for (int i = 0; i < explicitTypes.size(); i++) {
                        bound.put(md.typeParams().get(i), new TypeV(explicitTypes.get(i)));
                    }
                }
                List<Integer> order = inferenceOrder(args);
                Binding binding = bindArguments(sig, args, bound, order, name, narrowing);
                if (!binding.ok() && !sig.binders.isEmpty()) {
                    // Another argument may have to decide: in max(i, l) with an int and an int<<64>>, W is 64
                    // (from l), and i widens to it.
                    // A constant may decide too: max(b, 1000) with an int<<8>> b is max on int, as in Java.
                    for (int k : order.subList(1, order.size())) {
                        List<Integer> first = new ArrayList<>(order);
                        first.remove(Integer.valueOf(k));
                        first.add(0, k);
                        Binding other = bindArguments(sig, args, bound, first, name, narrowing);
                        if (other.ok()) {
                            binding = other;
                            break;
                        }
                    }
                    // Or the type they all widen to, as for operators: max(i, u) with an int and a uint is
                    // max on int<<64>>.
                    Type common = binding.ok() ? null : commonType(args);
                    if (common != null) {
                        List<Type> promoted = args.stream().map(a -> isAdaptive(a) ? a : common).toList();
                        Binding other = bindArguments(sig, promoted, bound, order, name, narrowing);
                        if (other.ok()) {
                            binding = bindArguments(sig, args, other.bound(), order, name, narrowing);
                        }
                    }
                }
                if (!binding.ok()) {
                    lastMismatch = binding.mismatch();
                    continue;
                }
                boolean exact = binding.exact();
                bound.clear();
                bound.putAll(binding.bound());
                if (!bound.keySet().containsAll(sig.binders.keySet())) {
                    inferFromLambdas(b, sig, args, bound);
                }
                if (!bound.keySet().containsAll(sig.binders.keySet())) {
                    if (!md.typeParams().isEmpty()) {
                        lastMismatch = "can't tell " + md.typeParams().stream().filter(t -> !bound.containsKey(t))
                                .collect(Collectors.joining(", ")) + " for " + name + " from its arguments; give the types,"
                                + " as in x.<" + String.join(", ", md.typeParams()) + ">" + name + "(...)";
                    }
                    continue;
                }
                Type ret = sig.returnType;
                if (!sig.binders.isEmpty()) {
                    ret = returnWithBinders(owner, m, md, sig, bound);
                }
                matches.add(new Match(m, ret, exact, bound, sig.params));
            }
            if (!narrowing) {
                firstMismatch = lastMismatch;
            }
        }
        lastMismatch = firstMismatch != null ? firstMismatch : lastMismatch;
        if (narrowed && matches.size() > 1) {
            report(pos, "ambiguous call to " + name + ": no overload takes (" + args.stream().map(Type::toString)
                    .collect(Collectors.joining(", ")) + ") as they are, and " + matches.size()
                    + " do once narrowed; use a cast");
            return Type.ERROR;
        }
        if (matches.isEmpty()) {
            if (args.stream().anyMatch(Checker::mentionsTypeParam)) {
                return Type.ERROR; // Math.max(a, b) on T values: checked for each type T is used with (§12)
            }
            if ((methods.size() == 1 || lastMismatch != null && lastMismatch.startsWith("can't tell")) && lastMismatch != null) {
                report(pos, lastMismatch);
            } else {
                report(pos, "no overload of " + name + " takes (" + args.stream().map(Type::toString)
                        .collect(Collectors.joining(", ")) + ")");
            }
            return Type.ERROR;
        }
        // As in Java, a method without type parameters that fits is chosen over a generic one, even one
        // binding T exactly: print(char) over <T> print(T) for a char.
        List<Match> plainOnes = matches.stream().filter(m -> m.bound.values().stream().noneMatch(v -> v instanceof TypeV)).toList();
        if (!plainOnes.isEmpty()) {
            matches = plainOnes;
        }
        List<Match> exact = matches.stream().filter(Match::exact).toList();
        List<Match> pick = exact.isEmpty() ? matches : exact;
        if (pick.size() > 1) {
            // A plain parameter is more specific than one with value binders: add(int) over add(int<<size W>>).
            List<Match> plain = pick.stream().filter(m -> m.bound.isEmpty()).toList();
            if (plain.size() == 1) {
                pick = plain;
            }
        }
        if (pick.size() > 1) {
            // Value binders are more specific than type parameters: of(int<<size W>> x) over of<K>(K key).
            List<Match> notGeneric = pick.stream().filter(m -> m.bound.values().stream().noneMatch(v -> v instanceof TypeV)).toList();
            if (notGeneric.size() == 1) {
                pick = notGeneric;
            }
        }
        if (pick.size() > 1) {
            // A char argument prefers a char parameter to the int it could widen to: f(char, int) over f(int, int).
            List<Match> chars = pick.stream().filter(m -> keepsChars(m.params, args)).toList();
            if (chars.size() == 1) {
                pick = chars;
            }
        }
        if (pick.size() > 1) {
            // Constants prefer their default types: int for integers, double for floating point,
            // string<<length>> for string literals, int[n] and the like for array literals. The
            // overload that gives the most constants their defaults wins: max(1.5, 2) takes doubles.
            Map<Match, Integer> kept = new HashMap<>();
            for (Match m : pick) {
                List<Type> ps = m.bound.isEmpty() ? m.params : quietly(() -> paramTypes(
                        m.method.member.modifiers().isStatic() ? null : owner, m.method, m.bound));
                kept.put(m, defaultsKept(ps, args));
            }
            int most = kept.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<Match> preferred = pick.stream().filter(m -> kept.get(m) == most).toList();
            if (preferred.size() == 1) {
                pick = preferred;
            } else if (preferred.size() > 1) {
                // Still tied, as add(int) and add(int<<size W>>) are for "x" + 5: the plain parameter wins.
                List<Match> plain = preferred.stream().filter(m -> m.bound.isEmpty()).toList();
                if (plain.size() == 1) {
                    pick = plain;
                } else {
                    pick = preferred;
                }
            }
        }
        if (pick.size() > 1) {
            // As in Java, the most specific overload wins: the one whose parameters each equal or widen to
            // every other's. f(int) over f(int<<64>>) for an int<<8>>; max on int<<64>> over max on double.
            List<Match> tied = pick;
            Map<Match, List<Type>> concrete = new IdentityHashMap<>();
            for (Match m : tied) {
                concrete.put(m, m.bound.isEmpty() ? m.params : quietly(() -> paramTypes(
                        m.method.member.modifiers().isStatic() ? null : owner, m.method, m.bound)));
            }
            List<Match> most = tied.stream().filter(m -> tied.stream().allMatch(o -> o == m
                    || atLeastAsSpecific(concrete.get(m), concrete.get(o)))).toList();
            if (most.size() == 1) {
                pick = most;
            }
        }
        if (pick.size() > 1) {
            report(pos, "ambiguous call to " + name + ": " + pick.size() + " overloads match");
            return Type.ERROR;
        }
        Match chosen = pick.get(0);
        if (kind == CallKind.SUPER_METHOD && chosen.method.member instanceof Method sm && sm.body() == null) {
            report(pos, name + " is abstract in " + (owner != null ? owner.cls().name : "the parent")
                    + "; there is no implementation to call");
            return Type.ERROR;
        }
        ClassT callOwner = chosen.method.member.modifiers().isStatic() ? null : owner;
        List<Type> taken = quietly(() -> paramTypes(callOwner, chosen.method, chosen.bound));
        if (!argumentCasts(b, site, pos, args, taken, "of " + name)) {
            return Type.ERROR;
        }
        if (!chosen.bound.isEmpty()) {
            // A constant must fit the width the other arguments chose: max(x, 1000) with x an int<<8>> is an error.
            List<Type> concrete = taken;
            for (int i = 0; i < args.size(); i++) {
                Type a = args.get(i);
                if ((a instanceof IntConstT || a instanceof FloatConstT) && !concrete.get(i).isError()
                        && !assignable(a, concrete.get(i)) && !narrows(a, concrete.get(i))) {
                    requireAssignable(b, a, concrete.get(i), pos, "argument " + (i + 1) + " of " + name);
                    return Type.ERROR;
                }
            }
        }
        if (site != null) {
            CallKind k = chosen.method.member.modifiers().isStatic() ? CallKind.STATIC : kind;
            b.calls.put(site, new CallInfo(k, chosen.method, chosen.method.member.modifiers().isStatic() ? null : owner,
                    Map.copyOf(chosen.bound), false, false));
        }
        return chosen.returnType;
    }

    /**
     * The conversions of a chosen method's or constructor's arguments to its parameters: noted
     * for -WautoCast, and recorded where one narrows (§41.6). False (reported) if a narrowing
     * argument can't be found in the source, which the code generator would need.
     */
    private boolean argumentCasts(Body b, Expr site, Pos pos, List<Type> args, List<Type> params, String of) {
        for (int i = 0; i < args.size() && i < params.size(); i++) {
            Expr arg = argExpr(site, i);
            String where = "argument " + (i + 1) + " " + of;
            if (narrows(args.get(i), params.get(i))) {
                if (arg == null) {
                    report(pos, where + ": expected " + params.get(i) + ", found " + args.get(i) + "; use a cast");
                    return false;
                }
                b.implicitCasts.put(arg, params.get(i));
                autoCast(arg.pos(), args.get(i) instanceof FloatConstT ? Type.DOUBLE : args.get(i), params.get(i), where,
                        lossy(arg, args.get(i)));
            } else {
                if (arg != null) {
                    b.implicitCasts.remove(arg);
                }
                autoCast(arg != null ? arg.pos() : pos, args.get(i), params.get(i), where);
            }
        }
        return true;
    }

    /** Argument i of a call as written, or null. */
    private static Expr argExpr(Expr site, int i) {
        List<Expr> written = switch (site) {
            case Call c -> c.args();
            case New n -> n.args();
            case Index ix -> List.of(ix.index());
            case Assign a when a.target() instanceof Index ix && a.op() == null -> List.of(ix.index(), a.value());
            case Assign a -> List.of(a.value());                  // v *= k on an object
            case Binary bin -> List.of(bin.right());              // v * k; a swapped b < a has no casts
            case null, default -> List.of();
        };
        return i < written.size() ? written.get(i) : null;
    }

    /** Whether each parameter type equals or widens to the other overload's (§41.6). */
    private static boolean atLeastAsSpecific(List<Type> params, List<Type> others) {
        for (int i = 0; i < params.size(); i++) {
            if (!params.get(i).equals(others.get(i)) && !widens(params.get(i), others.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** How a call's arguments meet one method's parameters. */
    private record Binding(boolean ok, boolean exact, Map<String, CVal> bound, String mismatch) {
    }

    /** Matches arguments to parameters in the given order, binding the method's binders on the way. */
    private Binding bindArguments(Signature sig, List<Type> args, Map<String, CVal> given, List<Integer> order, String name,
                                  boolean narrowing) {
        Map<String, CVal> bound = new HashMap<>(given);
        boolean exact = true;
        for (int i : order) {
            Type p = sig.params.get(i);
            Type a = args.get(i);
            Type unifyWith = defaultOf(a); // a literal binds string<<int K>> to its length, T[N] to its size
            if (!sig.binders.isEmpty() && unify(p, unifyWith, sig.binders.keySet(), bound)) {
                continue;
            }
            if (a.equals(p)) {
                continue;
            }
            exact = false;
            if (!sig.binders.isEmpty() && (a.equals(Type.CHAR) || a.equals(Type.SIZE)) && unify(p, Type.INT, sig.binders.keySet(), bound)) {
                continue; // a char or a size is an int here too: abs(int<<size W>> x) takes one as an int
            }
            if (!sig.binders.isEmpty() && constantDefault(a) instanceof Type d && unify(p, d, sig.binders.keySet(), bound)) {
                continue; // with no variable to decide, a constant decides at its default type: sqrt(2) is sqrt(int)
            }
            Type want = bindValues(p, bound);
            if (!assignable(a, want) && !(narrowing && narrows(a, want))) {
                return new Binding(false, false, bound, "argument " + (i + 1) + " of " + name + ": "
                        + (a instanceof IntConstT ic && ic.value() != null && want instanceof IntT
                                ? "integer " + ic.value() + " does not fit in " + want
                                : "expected " + want + ", found " + a));
            }
        }
        return new Binding(true, exact, bound, null);
    }

    /** {@code t} with the binders bound so far filled in: int<<W>> with W = 64 is an int<<64>>. */
    private static Type bindValues(Type t, Map<String, CVal> bound) {
        return switch (t) {
            case IntT i -> new IntT(bindValue(i.width(), bound), i.signed(), i.isChar(), i.isSize());
            case FixedT f -> new FixedT(bindValue(f.intBits(), bound), bindValue(f.fracBits(), bound), f.signed());
            case ParamT p -> bound.get(p.name()) instanceof TypeV tv ? tv.type() : p;
            case AddrT a -> new AddrT(bindValues(a.target(), bound));
            case HandleT h -> new HandleT(bindValues(h.target(), bound));
            case ArrayT a -> new ArrayT(bindValues(a.element(), bound), a.size() == null ? null : bindValue(a.size(), bound));
            case ClassT c -> new ClassT(c.cls(), c.typeArgs().stream().map(x -> bindValues(x, bound)).toList(),
                    c.valueArgs().stream().map(v -> bindValue(v, bound)).toList(), c.header());
            default -> t;
        };
    }

    private static CVal bindValue(CVal v, Map<String, CVal> bound) {
        return v instanceof CVal.Sym s && bound.get(s.name()) instanceof CVal b && !(b instanceof TypeV) ? b : v;
    }

    /**
     * The order arguments bind a call's binders in: variables before constants, so that in
     * max(0, x) the width comes from x, and the constant 0 then takes it.
     */
    private static List<Integer> inferenceOrder(List<Type> args) {
        List<Integer> order = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < args.size(); i++) {
                if (isAdaptive(args.get(i)) == (pass == 1)) {
                    order.add(i);
                }
            }
        }
        return order;
    }

    /** Whether every char argument meets a char parameter, rather than one it widens to. */
    private static boolean keepsChars(List<Type> params, List<Type> args) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals(Type.CHAR) && !params.get(i).equals(Type.CHAR)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a type is, or is built from, a type parameter whose type isn't known yet. */
    private static boolean mentionsTypeParam(Type t) {
        return switch (t) {
            case ParamT p -> p != INFER_RETURN;
            case ClassT c -> c.typeArgs().stream().anyMatch(Checker::mentionsTypeParam);
            case ArrayT a -> mentionsTypeParam(a.element());
            case HandleT h -> mentionsTypeParam(h.target());
            case AddrT a -> mentionsTypeParam(a.target());
            case WildT w -> mentionsTypeParam(w.bound());
            default -> false;
        };
    }

    /** The type a numeric constant has when nothing else decides: int, char for 'a', double for 1.5; or null. */
    private static Type constantDefault(Type t) {
        return switch (t) {
            case IntConstT ic -> ic.isChar() ? Type.CHAR : Type.INT;
            case FloatConstT f -> Type.DOUBLE;
            default -> null;
        };
    }

    /** How many constant arguments meet a parameter of their default type. */
    private int defaultsKept(List<Type> params, List<Type> args) {
        int kept = 0;
        for (int i = 0; i < args.size(); i++) {
            Type a = args.get(i);
            Type p = params.get(i);
            if ((a instanceof IntConstT ic && p.equals(ic.isChar() ? Type.CHAR : Type.INT))
                    || (a instanceof FloatConstT && p.equals(Type.DOUBLE))
                    || ((a instanceof StrConstT || a instanceof ArrayConstT) && p.equals(defaultOf(a)))
                    || (a instanceof ArrayConstT && defaultOf(a) instanceof ArrayT d && p instanceof ClassT pc
                        && isArrayObject(pc) && pc.valueArgs().isEmpty() && pc.typeArgs().equals(List.of(d.element())))) {
                kept++; // [1.5, 2.5] is an array<double> where it could be an array<float>, as 1.5 is a double
            }
        }
        return kept;
    }

    /** A method's parameter and return types as seen through {@code owner}, with its binders symbolic. */
    private record Signature(List<Type> params, Type returnType, Map<String, Type> binders) {
    }

    private Signature signature(ClassT owner, MemberInfo m, List<Param> params, TypeRef returnType) {
        ClassInfo declaring = owner != null ? owner.cls() : declaringClass(m);
        Env env = owner != null ? instanceEnv(owner, m) : Env.empty(declaring);
        addMethodTypes(env, m, null);
        Map<String, Type> binders = new LinkedHashMap<>();
        if (m.member instanceof Method md) {
            for (String t : md.typeParams()) {
                binders.put(t, new ParamT(t)); // a type binder: unify binds it to a TypeV
            }
        }
        String saved = file;
        file = declaring.file;
        try {
            List<Type> ps = new ArrayList<>();
            for (Param p : params) {
                ps.add(quietly(() -> resolveType(p.type(), env, new Opts(false, false, false, binders))));
            }
            Type ret = returnType == null ? Type.VOID
                    : quietly(() -> resolveType(returnType, env, new Opts(true, false, false, null)));
            return new Signature(ps, ret, binders);
        } finally {
            file = saved;
        }
    }

    /** Re-resolves a method's return type with its binders set to the values inferred at the call (§9.3). */
    private Type returnWithBinders(ClassT owner, MemberInfo m, Method md, Signature sig, Map<String, CVal> bound) {
        ClassInfo declaring = owner != null ? owner.cls() : declaringClass(m);
        Env env = owner != null ? instanceEnv(owner, m) : Env.empty(declaring);
        addMethodTypes(env, m, bound);
        for (Map.Entry<String, Type> e : sig.binders.entrySet()) {
            if (!(bound.get(e.getKey()) instanceof TypeV)) {
                env.values.put(e.getKey(), new Const(bound.get(e.getKey()), e.getValue()));
            }
        }
        String saved = file;
        file = declaring.file;
        try {
            Type t = resolveType(md.returnType(), env, new Opts(true, false, false, null));
            return t;
        } finally {
            file = saved;
        }
    }

    /**
     * Matches an argument type against a parameter type containing binders,
     * recording the binder values. Returns false if they don't match.
     */
    private boolean unify(Type param, Type arg, Set<String> binders, Map<String, CVal> bound) {
        return switch (param) {
            case ClassT p -> arg instanceof ClassT a && a.cls() == p.cls() && a.header() == p.header()
                    && unifyAll(p.typeArgs(), a.typeArgs(), binders, bound)
                    && unifyValues(p.valueArgs(), a.valueArgs(), binders, bound);
            case FixedT p -> arg instanceof FixedT a && a.signed() == p.signed()
                    && unifyValue(p.intBits(), a.intBits(), binders, bound)
                    && unifyValue(p.fracBits(), a.fracBits(), binders, bound);
            case IntT p -> arg instanceof IntT a && a.signed() == p.signed() && a.isChar() == p.isChar() && a.isSize() == p.isSize()
                    && unifyValue(p.width(), a.width(), binders, bound);
            case AddrT p -> arg instanceof AddrT a && unify(p.target(), a.target(), binders, bound);
            case HandleT p -> arg instanceof HandleT a && unify(p.target(), a.target(), binders, bound);
            case ArrayT p -> arg instanceof ArrayT a && unify(p.element(), a.element(), binders, bound)
                    && (p.size() == null ? a.size() == null : a.size() != null && unifyValue(p.size(), a.size(), binders, bound));
            case ParamT p when binders.contains(p.name()) -> bindType(p.name(), arg, bound);
            default -> param.equals(arg);
        };
    }

    /**
     * Binds a generic method's type parameter to an argument's type, or checks the argument
     * against what it is already bound to. Constants bind their default types, and strings
     * the bare `string`.
     */
    private boolean bindType(String name, Type arg, Map<String, CVal> bound) {
        Type t = typeArgOf(arg);
        if (t == null) {
            return false;
        }
        if (bound.get(name) instanceof TypeV tv) {
            return tv.type().equals(t) || assignable(arg, tv.type());
        }
        bound.put(name, new TypeV(t));
        return true;
    }

    /** The type an argument gives a type parameter, or null if it gives none (null, a lambda). */
    private Type typeArgOf(Type arg) {
        Type t = switch (arg) {
            case IntConstT ic -> ic.isChar() ? Type.CHAR : Type.INT;
            case FloatConstT f -> Type.DOUBLE;
            case NullT n -> null;
            case LambdaT l -> null;
            case ErrorT e -> null;
            default -> defaultOf(arg);
        };
        if (t instanceof ClassT ct && ct.cls().name.equals("string") && classes.get("string") == ct.cls()) {
            for (Shape shape : ct.cls().shapes) {
                if (shape.binders.isEmpty()) {
                    return new ClassT(ct.cls(), List.of(), List.of(), shape.index); // any string: a bare string
                }
            }
        }
        return t instanceof StrConstT || t instanceof ArrayConstT ? null : t;
    }

    /** {@code t} with the type parameters bound so far replaced by their types. */
    private static Type substitute(Type t, Map<String, CVal> bound) {
        return switch (t) {
            case ParamT p -> bound.get(p.name()) instanceof TypeV tv ? tv.type() : p;
            case ClassT c -> new ClassT(c.cls(), c.typeArgs().stream().map(a -> substitute(a, bound)).toList(),
                    c.valueArgs(), c.header());
            case HandleT h -> new HandleT(substitute(h.target(), bound));
            case ArrayT a -> new ArrayT(substitute(a.element(), bound), a.size());
            case AddrT a -> new AddrT(substitute(a.target(), bound));
            case WildT w -> new WildT(substitute(w.bound(), bound), w.kind());
            default -> t;
        };
    }

    private boolean unifyAll(List<Type> ps, List<Type> as, Set<String> binders, Map<String, CVal> bound) {
        if (ps.size() != as.size()) {
            return false;
        }
        for (int i = 0; i < ps.size(); i++) {
            if (!unify(ps.get(i), as.get(i), binders, bound)) {
                return false;
            }
        }
        return true;
    }

    private boolean unifyValues(List<CVal> ps, List<CVal> as, Set<String> binders, Map<String, CVal> bound) {
        if (ps.size() != as.size()) {
            return false;
        }
        for (int i = 0; i < ps.size(); i++) {
            if (!unifyValue(ps.get(i), as.get(i), binders, bound)) {
                return false;
            }
        }
        return true;
    }

    private boolean unifyValue(CVal p, CVal a, Set<String> binders, Map<String, CVal> bound) {
        if (p instanceof Sym s && binders.contains(s.name())) {
            CVal prev = bound.putIfAbsent(s.name(), a);
            return prev == null || prev.equals(a);
        }
        return p.equals(a);
    }

    /** Checks a constructor call for the given instance type, recording the choice under {@code site}. */
    private void resolveConstructor(Body b, ClassT t, List<Type> args, Pos pos, Expr site, CallKind kind) {
        ClassInfo c = t.cls();
        List<MemberInfo> ctors = c.constructors.stream().filter(m -> m.existsIn(t.header())).toList();
        if (c.constructors.isEmpty()) {
            if (!args.isEmpty()) {
                report(pos, c.name + " has no constructors, so new " + c.name + "() takes no arguments");
            } else {
                b.calls.put(site, new CallInfo(kind, null, t, Map.of(), false, false));
            }
            return;
        }
        List<MemberInfo> matching = new ArrayList<>();
        List<Map<String, CVal>> matchingBound = new ArrayList<>();
        List<List<Type>> matchingParams = new ArrayList<>();
        String mismatch = null;
        boolean privateMatch = false;
        boolean narrowed = false;
        for (int phase = 0; phase < 2 && matching.isEmpty() && !privateMatch; phase++) {
            boolean narrowing = phase == 1; // as for methods: a number narrows only if nothing takes it as it is
            narrowed = narrowing;
            String before = mismatch;
            for (MemberInfo m : ctors) {
                Constructor ctor = (Constructor) m.member;
                if (ctor.params().size() != args.size()) {
                    continue;
                }
                Signature sig = signature(t, m, ctor.params(), null);
                Map<String, CVal> bound = new HashMap<>();
                boolean ok = true;
                for (int i : inferenceOrder(args)) {
                    if (!ok) {
                        break;
                    }
                    Type p = sig.params.get(i);
                    Type unifyWith = defaultOf(args.get(i)); // a literal binds string<<int K>> to its length, T[N] to its size
                    if (!sig.binders.isEmpty() && unify(p, unifyWith, sig.binders.keySet(), bound)) {
                        continue;
                    }
                    if (!assignable(args.get(i), bindValues(p, bound)) && !(narrowing && narrows(args.get(i), bindValues(p, bound)))) {
                        ok = false;
                        mismatch = "constructor argument " + (i + 1) + ": expected " + p + ", found " + args.get(i);
                    }
                }
                if (ok && !canAccess(b.cls, c, ctor.modifiers())) {
                    privateMatch = true; // it would match, but this code may not use it
                    continue;
                }
                if (ok) {
                    matching.add(m);
                    matchingBound.add(bound);
                    matchingParams.add(sig.params);
                }
            }
            if (narrowing) {
                mismatch = before;
            }
        }
        if (narrowed && matching.size() > 1) {
            report(pos, "ambiguous constructor call for " + c.name + ": none takes (" + args.stream().map(Type::toString)
                    .collect(Collectors.joining(", ")) + ") as they are, and " + matching.size() + " do once narrowed;"
                    + " use a cast");
            return;
        }
        if (matching.size() > 1) {
            // As for methods: exact parameter types first, then char parameters for char arguments, then
            // constants at their default types (new StringBuilder(100) takes an int), then the most
            // specific: parameters that each equal or widen to every other's (§41.6).
            List<List<Type>> all = List.copyOf(matchingParams);
            int mostDefaults = all.stream().mapToInt(ps -> defaultsKept(ps, args)).max().orElse(0);
            for (java.util.function.Predicate<List<Type>> better : List.<java.util.function.Predicate<List<Type>>>of(
                    ps -> ps.equals(args), ps -> keepsChars(ps, args), ps -> defaultsKept(ps, args) == mostDefaults,
                    ps -> all.stream().allMatch(o -> o == ps || atLeastAsSpecific(ps, o)))) {
                List<Integer> keep = new ArrayList<>();
                for (int i = 0; i < matching.size(); i++) {
                    if (better.test(matchingParams.get(i))) {
                        keep.add(i);
                    }
                }
                if (keep.size() == 1) {
                    int k = keep.get(0);
                    matching = new ArrayList<>(List.of(matching.get(k)));
                    matchingBound = new ArrayList<>(List.of(matchingBound.get(k)));
                    matchingParams = new ArrayList<>(List.of(matchingParams.get(k)));
                    break;
                }
            }
        }
        if (matching.size() == 1) {
            b.calls.put(site, new CallInfo(kind, matching.get(0), t, Map.copyOf(matchingBound.get(0)), false, false));
            Map<String, CVal> chosen = matchingBound.get(0);
            List<Type> params = matchingParams.get(0).stream().map(p -> bindValues(p, chosen)).toList();
            argumentCasts(b, site, pos, args, params, "of the constructor of " + c.name);
        }
        if (matching.isEmpty() && privateMatch) {
            report(pos, "the constructor of " + c.name + " taking (" + args.stream().map(Type::toString)
                    .collect(Collectors.joining(", ")) + ") is private");
        } else if (matching.isEmpty()) {
            report(pos, mismatch != null && ctors.size() == 1 ? mismatch
                    : "no constructor of " + t + " takes (" + args.stream().map(Type::toString).collect(Collectors.joining(", ")) + ")");
        } else if (matching.size() > 1) {
            report(pos, "ambiguous constructor call for " + t);
        }
    }

    private Type checkNew(Body b, New n, Type expected) {
        List<Type> args = new ArrayList<>();
        for (Expr a : n.args()) {
            args.add(checkExpr(b, a, null));
        }
        TypeRef ref = n.type();
        ClassInfo c = lookupClass(ref.name(), b.cls);
        if (c != null && visibleClass(c, b.cls, ref.pos()) == null) {
            return Type.ERROR;
        }
        if (c == null) {
            if (Set.of("int", "uint", "bit", "float", "double", "addr", "handle").contains(ref.name())) {
                report(n.pos(), "use new<" + ref.name() + ">(count) to allocate primitive values");
            } else {
                report(ref.pos(), "unknown type " + ref.name() + unknownTypeHint(ref.name(), b.cls));
            }
            return Type.ERROR;
        }
        if (c.isAbstract) {
            report(n.pos(), "cannot create " + (c.isInterface ? "interface " : "abstract class ") + c.name
                    + " with new; create a class that " + (c.isInterface ? "implements" : "extends") + " it");
            return Type.ERROR;
        }
        if (isJavaArray(c) && ref.typeArgs().isEmpty()) {
            return newJavaArray(b, n, expected, args);
        }
        ClassT t;
        boolean explicit = !ref.typeArgs().isEmpty() || ref.valueArgs() != null;
        if (explicit) {
            Type r = resolveType(ref, b.env, new Opts(false, true, false, null));
            if (!(r instanceof ClassT rt)) {
                return Type.ERROR;
            }
            t = rt;
        } else {
            Type target = expected instanceof AddrT a ? a.target() : expected instanceof HandleT h ? h.target() : expected;
            if (target != null && target.isError()) {
                return Type.ERROR; // the declared type was already reported
            }
            if (target instanceof ClassT et && et.cls() == c) {
                t = et;
            } else if (c.java != null) {
                t = javaNewType(c, target); // its type arguments from the type it goes to, else raw (docs/JAVA.md §4.1)
            } else if (c.typeParams.isEmpty() && c.shapes.stream().anyMatch(s -> s.binders.isEmpty())) {
                t = new ClassT(c, List.of(), List.of(),
                        c.shapes.stream().filter(s -> s.binders.isEmpty()).findFirst().orElseThrow().index);
            } else {
                report(n.pos(), "cannot tell which shape of " + c.name + " to create; assign it to a variable of type "
                        + c.name + (c.typeParams.isEmpty() ? "" : "<...>") + "<<...>>");
                return Type.ERROR;
            }
        }
        if (c.java != null) {
            // A Java class's constructor, chosen as Java chooses (docs/JAVA.md §4).
            if (c.constructors.isEmpty()) {
                List<String> makers = c.all.stream().filter(m -> m.member instanceof Method md && md.modifiers().isStatic()
                        && javaMembers.get(md) instanceof JavaClasses.JMember jm
                        && jm.descriptor().endsWith(")L" + c.java.internalName() + ";")).map(MemberInfo::name).distinct().limit(3)
                        .map(m -> c.name + "." + m + "(...)").toList();
                report(n.pos(), javaName(c) + " has no public constructor" + (makers.isEmpty() ? ""
                        : "; its static methods make them: " + String.join(", ", makers)));
            } else if (c.constructors.stream().noneMatch(k -> (javaMembers.get(k.member).flags() & JavaClasses.PUBLIC) != 0)) {
                report(n.pos(), javaName(c) + "'s constructors are protected, so only a class extending it calls them, with super(...)");
            } else if (args.stream().noneMatch(Type::isError)) {
                resolveJava(b, "new", t, t, c.constructors.stream()
                        .filter(k -> (javaMembers.get(k.member).flags() & JavaClasses.PUBLIC) != 0).toList(), Map.of(), args, n.pos(), n);
                checkLambdaArgs(b, n, n.args());
            }
            return new HandleT(t);
        }
        if (isJavaArray(c)) {
            return newJavaArray(b, n, new HandleT(t), args);
        }
        if (args.stream().noneMatch(Type::isError)) {
            resolveConstructor(b, t, args, n.pos(), n, CallKind.CONSTRUCTOR);
            checkLambdaArgs(b, n, n.args());
        }
        if (c.dynamic) {
            return new HandleT(t);
        }
        if (expected instanceof AddrT) {
            return new AddrT(t);
        }
        return t;
    }

    /**
     * The type `new` makes of a Java class with no type arguments written: those the type it goes to
     * says, through a parent if it is one (List<JavaString> l = new ArrayList() makes an
     * ArrayList<JavaString>), as Java's new ArrayList<>(); raw if nothing says them (docs/JAVA.md §4.1).
     */
    private ClassT javaNewType(ClassInfo c, Type target) {
        ClassT raw = new ClassT(c, List.of(), List.of(), 0);
        if (c.typeParams.isEmpty() || !(target instanceof ClassT et) || et.typeArgs().isEmpty()) {
            return raw;
        }
        ClassT self = new ClassT(c, c.typeParams.stream().map(tp -> (Type) new ParamT(tp)).toList(), List.of(), 0);
        ClassT as = asSuper(self, et.cls());
        if (as == null) {
            return raw;
        }
        Map<String, Type> found = new LinkedHashMap<>();
        inferJava(new HandleT(as), new HandleT(et), c.typeParams, found);
        if (!found.keySet().containsAll(c.typeParams)) {
            return raw;
        }
        return new ClassT(c, c.typeParams.stream().map(found::get).toList(), List.of(), 0);
    }

    /**
     * new JavaArray(n): a Java array of n of Java's defaults (0, false, null), for Java to fill; its
     * element type comes from where it goes, as in JavaArray<JavaByte> buffer = new JavaArray(4096).
     */
    private Type newJavaArray(Body b, New n, Type expected, List<Type> args) {
        if (!(expected instanceof HandleT h && h.target() instanceof ClassT t && isJavaArray(t.cls()))) {
            report(n.pos(), "new JavaArray(n) makes the type it goes to, which says its elements' type, as in"
                    + " JavaArray<JavaByte> buffer = new JavaArray(4096);");
            return Type.ERROR;
        }
        if (args.size() != 1) {
            report(n.pos(), "new JavaArray(n) takes the length, an int");
            return Type.ERROR;
        }
        if (!args.get(0).isError()) {
            requireConvertible(b, n.args().get(0), args.get(0), Type.INT, "the length of a Java array");
        }
        b.calls.put(n, new CallInfo(CallKind.CONSTRUCTOR, null, t, Map.of(), false, false));
        return expected;
    }

    // ================================================================ operators

    private Type checkUnary(Body b, Unary u) {
        Type t = checkExpr(b, u.operand(), null);
        if (t.isError()) {
            return t;
        }
        if (t instanceof ParamT && u.op() != UnaryOp.ADDRESS_OF && u.op() != UnaryOp.DEREF) {
            // Checked when the class is instantiated.
            return u.op() == UnaryOp.NOT ? Type.BIT : t;
        }
        if (t instanceof JavaPrimT j && (u.op() == UnaryOp.NEG || u.op() == UnaryOp.BIT_NOT || u.op() == UnaryOp.NOT)) {
            // As Java's: - and ~ on its numbers (a byte's in int), ! on a JavaBoolean (docs/JAVA.md §3.1).
            boolean ok = u.op() == UnaryOp.NOT ? j.code() == 'Z' : u.op() == UnaryOp.NEG ? j.code() != 'Z' : "IJSBC".indexOf(j.code()) >= 0;
            if (!ok) {
                report(u.pos(), "operator " + u.op().symbol + " is not defined for " + t);
                return Type.ERROR;
            }
            return u.op() == UnaryOp.NOT ? j : javaPromoted(j, j);
        }
        switch (u.op()) {
            case NEG, BIT_NOT: {
                if (operandClass(t) instanceof ClassT ct) {
                    return operatorCall(b, ct, u.op().symbol, List.of(), u.pos(), u);
                }
                boolean ok = u.op() == UnaryOp.NEG ? t.isNumeric() : t.isInteger();
                if (!ok) {
                    report(u.pos(), "operator " + u.op().symbol + " is not defined for " + t);
                    return Type.ERROR;
                }
                if (t instanceof IntConstT ic && ic.value() != null) {
                    return new IntConstT(u.op() == UnaryOp.NEG ? ic.value().negate() : ic.value().not());
                }
                if (t instanceof FloatConstT fc) {
                    return new FloatConstT(fc.value() == null ? null : fc.value().negate());
                }
                return t;
            }
            case NOT: {
                if (!(t instanceof BitT)) {
                    report(u.pos(), "! needs a bit, not " + t);
                    return Type.ERROR;
                }
                return t;
            }
            case DEREF: {
                if (t instanceof AddrT a) {
                    if (a.target() instanceof VoidT) {
                        report(u.pos(), "cannot dereference addr<void>; cast it to a typed address first");
                        return Type.ERROR;
                    }
                    return a.target();
                }
                if (t instanceof HandleT) {
                    report(u.pos(), "handles cannot be dereferenced; access members with h.name (§26)");
                } else {
                    report(u.pos(), "& dereferences an address, but this is a " + t + "; to take an address use *x");
                }
                return Type.ERROR;
            }
            case ADDRESS_OF: {
                Lval l = lvalue(b, u.operand());
                if (l == Lval.VIA_HANDLE) {
                    report(u.pos(), "cannot take an address inside a class object; it may move (§26)");
                    return Type.ERROR;
                }
                if (l != Lval.YES) {
                    report(u.pos(), "*x takes the address of a variable, field or element; this expression has no address");
                    return Type.ERROR;
                }
                return new AddrT(t);
            }
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * `cond ? a : b`: the type the context wants if both sides fit it; otherwise the type both
     * share, taking constants at their defaults (1 is an int), a handle's common parent, or a
     * bare string for strings of different sizes (§44.1).
     */
    private Type checkConditional(Body b, Conditional c, Type expected) {
        Type t = checkConditionalSides(b, c, expected);
        if (!t.isError()) {
            autoCast(c.then().pos(), b.types.get(c.then()), t, "?:");
            autoCast(c.otherwise().pos(), b.types.get(c.otherwise()), t, "?:");
        }
        return t;
    }

    private Type checkConditionalSides(Body b, Conditional c, Type expected) {
        requireCondition(b, c.cond());
        Type[] then = new Type[1];
        withBindings(b, c.cond(), () -> then[0] = checkExpr(b, c.then(), expected));
        Type a = then[0];
        Type o = checkExpr(b, c.otherwise(), expected);
        if (a.isError() || o.isError()) {
            return Type.ERROR;
        }
        if (expected != null && !(expected instanceof LambdaT) && assignable(a, expected) && assignable(o, expected)) {
            return expected;
        }
        Type met = meet(a, o);
        if (met != null) {
            return met;
        }
        report(c.pos(), "the two sides of ?: have different types: " + a + " and " + o);
        return Type.ERROR;
    }

    /**
     * The type two values meet in, for ?: and the cases of a switch expression: the same type;
     * the type both numbers widen to (a constant takes the other's); a handle's common parent;
     * a bare string for strings of different sizes. Null if there is none.
     */
    private Type meet(Type a, Type o) {
        if (a.equals(o) && !(a instanceof StrConstT) && !(a instanceof ArrayConstT)) {
            return a; // includes two integer constants, which stay adaptive
        }
        if (a.isNumeric() && o.isNumeric() && !(isAdaptive(a) && isAdaptive(o))) {
            // As for operators: a constant takes the other side's type, else the wider type (§41.6).
            Type u = unifyNumeric(a, o);
            if (u != null) {
                return u;
            }
        }
        Type da = conditionalDefault(a);
        Type db = conditionalDefault(o);
        if (da.equals(db)) {
            return da;
        }
        if (assignable(o, da) && !(a instanceof NullT)) {
            return da;
        }
        if (assignable(a, db) && !(o instanceof NullT)) {
            return db;
        }
        if (da instanceof HandleT ha && db instanceof HandleT hb && ha.target() instanceof ClassT ca
                && hb.target() instanceof ClassT cb) {
            for (ClassT p = ca; p != null; p = parentOf(p)) {
                if (isSubclass(cb, p)) {
                    return new HandleT(p); // the nearest common parent
                }
            }
        }
        ClassInfo s = classes.get("string");
        if (s != null && da instanceof ClassT sa && db instanceof ClassT sb && sa.cls() == s && sb.cls() == s) {
            for (Shape shape : s.shapes) {
                if (shape.binders.isEmpty()) {
                    return new ClassT(s, List.of(), List.of(), shape.index); // fixed strings of two sizes
                }
            }
        }
        return null;
    }

    private Type conditionalDefault(Type t) {
        return t instanceof IntConstT ic ? (ic.isChar() ? Type.CHAR : Type.INT) : t instanceof FloatConstT ? Type.DOUBLE : defaultOf(t);
    }

    private Type checkBinary(Body b, Binary bin) {
        Type left = checkExpr(b, bin.left(), null);
        Type[] right = new Type[1];
        if (bin.op() == BinaryOp.AND) {
            withBindings(b, bin.left(), () -> right[0] = checkExpr(b, bin.right(), left)); // x instanceof A a && a.f()
        } else {
            right[0] = checkExpr(b, bin.right(), left);
        }
        Type l = left;
        Type r = right[0];
        if (l.isError() || r.isError()) {
            return Type.ERROR;
        }
        if (bin.op() == BinaryOp.ADD && l instanceof StrConstT ls && r instanceof StrConstT rs
                && ls.value() != null && rs.value() != null) {
            return new StrConstT(ls.value() + rs.value()); // joined at compile time, still a literal
        }
        boolean stringRight = r instanceof StrConstT || (r instanceof ClassT rc && rc.cls().name.equals("string"));
        boolean stringLeft = l instanceof StrConstT || (l instanceof ClassT lc && lc.cls().name.equals("string"));
        if (bin.op() == BinaryOp.ADD && stringLeft != stringRight && !(stringLeft ? r : l).isNumeric()
                && !((stringLeft ? r : l) instanceof BitT)
                && (objectType(stringLeft ? r : l) != null || (stringLeft ? r : l) instanceof JavaPrimT)) {
            // "box: " + box and box + " apples": the object's cast to string, as Java's toString() (§46)
            Expr object = stringLeft ? bin.right() : bin.left();
            Type ot = stringLeft ? r : l;
            if (!castsToString(ot)) {
                report(object.pos(), noStringCast(ot, "+ needs to join it to a string"));
                return Type.ERROR;
            }
            Expr text = new Cast(object.pos(), new TypeRef(object.pos(), "string", List.of(), null, List.of()), object);
            Binary joined = stringLeft ? new Binary(bin.pos(), BinaryOp.ADD, bin.left(), text)
                    : new Binary(bin.pos(), BinaryOp.ADD, text, bin.right());
            b.rewritten.put(bin, joined);
            return checkExpr(b, joined, null);
        }
        if (bin.op() == BinaryOp.ADD && stringRight && (l.isNumeric() || l instanceof BitT)) {
            // A number (or bit) first, as in Java: 5 + " apples" is "" + 5 + " apples".
            Pos p = bin.pos();
            Expr rewrite = new Binary(p, BinaryOp.ADD, new Binary(p, BinaryOp.ADD, new StringLit(p, ""), bin.left()), bin.right());
            b.rewritten.put(bin, rewrite);
            return checkExpr(b, rewrite, null);
        }
        if (l instanceof JavaPrimT || r instanceof JavaPrimT) {
            return javaOperator(bin, b.written.getOrDefault(bin, bin.op().symbol), l, r);
        }
        l = defaultLiteral(b, bin.left(), l); // "abc" == s
        BinaryOp op = bin.op();
        ClassT operandClass = operandClass(l);
        // A class without its own ==, or any comparison with null, compares handles by identity.
        boolean identity = operandClass != null && operandClass.cls().dynamic
                && (op == BinaryOp.EQ || op == BinaryOp.NE)
                && (!hasOperator(operandClass, "==") || l instanceof NullT || r instanceof NullT);
        if (operandClass != null && !identity && op != BinaryOp.AND && op != BinaryOp.OR) {
            // A class without its own == compares handles by identity, as in Java.
            return classOperator(b, operandClass, op, r, bin.pos(), bin);
        }
        if (l instanceof ClassT ct && op != BinaryOp.AND && op != BinaryOp.OR) {
            return classOperator(b, ct, op, r, bin.pos(), bin);
        }
        if (l instanceof ParamT || r instanceof ParamT) {
            // Operators on a type parameter are checked when the class is instantiated (§12).
            return switch (op) {
                case EQ, NE, LT, GT, LE, GE, AND, OR -> Type.BIT;
                default -> l instanceof ParamT ? l : r;
            };
        }
        switch (op) {
            case AND, OR -> {
                if (!(l instanceof BitT) || !(r instanceof BitT)) {
                    report(bin.pos(), op.symbol + " needs bit operands, not " + l + " and " + r);
                    return Type.ERROR;
                }
                return Type.BIT;
            }
            case EQ, NE -> {
                if (comparable(l, r) || comparable(r, l)) {
                    if (l.isNumeric() && r.isNumeric()) {
                        operandCasts(b, bin, l, r, unifyNumeric(l, r));
                    }
                    return Type.BIT;
                }
                report(bin.pos(), "cannot compare " + l + " with " + r);
                return Type.ERROR;
            }
            case LT, GT, LE, GE -> {
                if (l instanceof AddrT && l.equals(r)) {
                    return Type.BIT; // addresses of the same type compare by position
                }
                Type u = unifyNumeric(l, r);
                if (u == null || !u.isNumeric()) {
                    report(bin.pos(), "cannot compare " + l + " with " + r + " using " + op.symbol
                            + (u == null && l.isNumeric() && r.isNumeric() ? "; use a cast to make the types match" : ""));
                    return Type.ERROR;
                }
                operandCasts(b, bin, l, r, u);
                return Type.BIT;
            }
            case SHIFT -> {
                if (!l.isInteger()) {
                    report(bin.pos(), "<<< shifts an integer, not " + l);
                    return Type.ERROR;
                }
                if (!(r instanceof IntConstT) && !r.equals(Type.INT) && !widens(r, Type.INT)) {
                    report(bin.right().pos(), "the shift amount must be an int, not " + r);
                    return Type.ERROR;
                }
                if (!b.written.containsKey(bin) || b.written.get(bin) != null) {
                    autoCast(bin.right().pos(), r, Type.INT, "shift amount");
                }
                if (l instanceof IntConstT lc && r instanceof IntConstT rc && lc.value() != null && rc.value() != null) {
                    CVal v = CVal.binary("<<<", new IntV(lc.value()), new IntV(rc.value()));
                    return new IntConstT(v.intValue());
                }
                return l instanceof IntConstT ? Type.INT : l;
            }
            case ADD, SUB -> {
                if (l instanceof AddrT && r.isInteger()) {
                    return l;
                }
                if (op == BinaryOp.ADD && r instanceof AddrT && l.isInteger()) {
                    return r;
                }
                if (op == BinaryOp.SUB && l instanceof AddrT && r instanceof AddrT) {
                    if (!l.equals(r)) {
                        report(bin.pos(), "cannot subtract " + r + " from " + l);
                        return Type.ERROR;
                    }
                    return new IntT(CVal.of(64), true);
                }
                return operandCasts(b, bin, l, r, arithmetic(bin, l, r, true));
            }
            case MUL, DIV -> {
                return operandCasts(b, bin, l, r, arithmetic(bin, l, r, true));
            }
            case POW -> {
                Type u = arithmetic(bin, l, r, true);
                if (u instanceof FixedT) {
                    report(bin.pos(), "operator ** works on integers and floating point, not " + u + "; convert to double first");
                    return Type.ERROR;
                }
                return operandCasts(b, bin, l, r, u);
            }
            case MOD -> {
                return operandCasts(b, bin, l, r, arithmetic(bin, l, r, false));
            }
            case BIT_AND, BIT_OR, BIT_XOR -> {
                if (l instanceof BitT && r instanceof BitT) {
                    return Type.BIT;
                }
                if (!l.isInteger() || !r.isInteger()) {
                    report(bin.pos(), "operator " + op.symbol + " needs integers or bits, not " + l + " and " + r);
                    return Type.ERROR;
                }
                return operandCasts(b, bin, l, r, arithmetic(bin, l, r, false));
            }
            default -> throw new IllegalStateException();
        }
    }

    /**
     * An operator on Java's primitives (docs/JAVA.md §3.1), as Java's: on two of Java's numbers, in
     * the type Java promotes them to (a JavaByte + a JavaByte is a JavaInt, a JavaInt + a JavaLong a
     * JavaLong), a comparison giving a JavaBoolean; on two JavaBooleans, &&, ||, &, |, ^, == and !=.
     * Java's values and Seal's don't mix: a JavaInt + an int is an error, a literal's too.
     */
    private Type javaOperator(Binary bin, String symbol, Type l, Type r) {
        BinaryOp op = bin.op();
        if (!(l instanceof JavaPrimT lj) || !(r instanceof JavaPrimT rj)) {
            JavaPrimT j = (JavaPrimT) (l instanceof JavaPrimT ? l : r);
            String of = javaOf(l instanceof JavaPrimT ? r : l);
            report(bin.pos(), "operator " + symbol + " on " + l + " and " + r + ": Java's values and Seal's don't mix;"
                    + " make both Java's" + (of != null ? ", as " + of + "(...)" : "") + ", or both Seal's, as ("
                    + j.sealType() + ")x (docs/JAVA.md §3.1)");
            return Type.ERROR;
        }
        JavaPrimT bool = new JavaPrimT('Z');
        if (lj.equals(bool) || rj.equals(bool)) {
            if (lj.equals(rj) && Set.of(BinaryOp.AND, BinaryOp.OR, BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR,
                    BinaryOp.EQ, BinaryOp.NE).contains(op)) {
                return bool;
            }
            report(bin.pos(), "operator " + symbol + " takes two of Java's numbers, or two JavaBooleans, not " + l + " and " + r);
            return Type.ERROR;
        }
        JavaPrimT promoted = javaPromoted(lj, rj);
        return switch (op) {
            case EQ, NE, LT, GT, LE, GE -> bool;
            case ADD, SUB, MUL, DIV, MOD -> promoted;
            case BIT_AND, BIT_OR, BIT_XOR -> {
                if (promoted.code() == 'F' || promoted.code() == 'D') {
                    report(bin.pos(), "operator " + symbol + " takes integers, not " + l + " and " + r);
                    yield Type.ERROR;
                }
                yield promoted;
            }
            case AND, OR -> {
                report(bin.pos(), op.symbol + " takes two JavaBooleans, not " + l + " and " + r);
                yield Type.ERROR;
            }
            default -> {
                report(bin.pos(), "operator " + symbol + " isn't Java's, so it doesn't take " + l + " and " + r
                        + "; cast them to Seal's first, as (" + lj.sealType() + ")x");
                yield Type.ERROR;
            }
        };
    }

    /** Java's binary numeric promotion (JLS §5.6.2): double if either is, then float, then long, else int. */
    private static JavaPrimT javaPromoted(JavaPrimT a, JavaPrimT b) {
        for (char c : "DFJ".toCharArray()) {
            if (a.code() == c || b.code() == c) {
                return new JavaPrimT(c);
            }
        }
        return new JavaPrimT('I');
    }

    /**
     * -WautoCast: the operands of a number operator converted to the type it works in, as i
     * in i + d; arithmetic on chars works in int. Gives back {@code type}.
     */
    private Type operandCasts(Body b, Binary bin, Type l, Type r, Type type) {
        if (type == null || type.isError() || (b.written.containsKey(bin) && b.written.get(bin) == null)) {
            return type;
        }
        String where = "operand of " + b.written.getOrDefault(bin, bin.op().symbol);
        autoCast(bin.left().pos(), l, type, where);
        autoCast(bin.right().pos(), r, type, where);
        return type;
    }

    /** Numeric operators: both sides must have the same type once constants adapt (§41). */
    private Type arithmetic(Binary bin, Type l, Type r, boolean allowFloat) {
        Type u = unifyNumeric(l, r);
        if (u == null && isAdaptive(l) != isAdaptive(r)) {
            // The constant would adapt, but doesn't fit: x + 1 where x is a ufixed<<0, 8>>.
            Type constant = isAdaptive(l) ? l : r;
            Type other = isAdaptive(l) ? r : l;
            if ((constant instanceof IntConstT ic && ic.value() != null && (other instanceof IntT || other instanceof FixedT))
                    || (constant instanceof FloatConstT fc && fc.value() != null && other instanceof FixedT)) {
                requireAssignable(null, constant, other, bin.pos(), "operand of " + bin.op().symbol);
                return Type.ERROR;
            }
        }
        if (u == null) {
            report(bin.pos(), "operator " + bin.op().symbol + " needs matching types, not " + l + " and " + r
                    + (l.isNumeric() && r.isNumeric() ? "; use a cast" : ""));
            return Type.ERROR;
        }
        if (!allowFloat && !u.isInteger() && !(u instanceof FixedT && bin.op() == BinaryOp.MOD)) {
            report(bin.pos(), "operator " + bin.op().symbol + " needs integers, not " + u);
            return Type.ERROR;
        }
        if (u instanceof FloatConstT) {
            java.math.BigDecimal a = exactConst(l);
            java.math.BigDecimal c = exactConst(r);
            if (a == null || c == null) {
                return new FloatConstT(null);
            }
            // Exact for + - *; division goes through double, like the constant evaluator.
            return new FloatConstT(switch (bin.op()) {
                case ADD -> a.add(c);
                case SUB -> a.subtract(c);
                case MUL -> a.multiply(c);
                default -> {
                    CVal v = CVal.binary(bin.op().symbol, new FloatV(a.doubleValue()), new FloatV(c.doubleValue()));
                    yield v instanceof FloatV fv ? exactDouble(fv.value()) : null;
                }
            });
        }
        if (u instanceof IntConstT && l instanceof IntConstT lc && r instanceof IntConstT rc
                && lc.value() != null && rc.value() != null) {
            CVal v = CVal.binary(bin.op().symbol, new IntV(lc.value()), new IntV(rc.value()));
            if (v instanceof ErrV) {
                report(bin.pos(), bin.op() == BinaryOp.POW && lc.value().signum() != 0
                        ? lc.value() + " ** " + rc.value() + " has more than " + CVal.MAX_POWER_BITS + " bits"
                        : "division by zero");
                return Type.ERROR;
            }
            return new IntConstT(v.intValue());
        }
        if (u instanceof IntConstT) {
            return new IntConstT(null);
        }
        // As in Java, arithmetic on chars gives an int; so does arithmetic on sizes (§37.2).
        return u instanceof IntT it && (it.isChar() || it.isSize()) ? Type.INT : u;
    }

    /** The type two numeric operands are compared or combined in (§41.6), or null if neither widens to the other. */
    public Type operandType(Type l, Type r) {
        return unifyNumeric(l, r);
    }

    /**
     * The common type of two numeric operands, as Java's binary numeric promotion gives it
     * (§41.6): a constant takes the other operand's type if it fits, and otherwise both go to
     * the constant's own type (b + 1000 with an int<<8>> b is an int); of two variables, the
     * one the other widens to. A char counts as an int first. Null if there is none.
     */
    private Type unifyNumeric(Type l, Type r) {
        if (!l.isNumeric() || !r.isNumeric()) {
            return null;
        }
        if (l.equals(r)) {
            return l;
        }
        if (l instanceof IntConstT && r instanceof IntConstT) {
            return new IntConstT(null);
        }
        boolean lc = l instanceof IntConstT || l instanceof FloatConstT;
        boolean rc = r instanceof IntConstT || r instanceof FloatConstT;
        if (lc && rc) {
            return l instanceof FloatConstT ? l : r; // an integer and a floating-point constant
        }
        if (lc || rc) {
            Type constant = lc ? l : r;
            Type other = lc ? r : l;
            Type adapted = adaptConst(constant, other);
            if (adapted != null) {
                return adapted;
            }
            Type own = constType(constant);
            return own != null && widens(other, own) ? own : null;
        }
        if (widens(l, r)) {
            return r;
        }
        if (widens(r, l)) {
            return l;
        }
        // Neither holds the other, as for an int<<8>> and a uint<<8>>, an int and a uint, or a char and
        // an int<<16>>: as Java promotes to int and then long, both go to int, or else int<<64>>.
        for (Type t : List.of(Type.INT, LONG)) {
            if ((l.equals(t) || widens(l, t)) && (r.equals(t) || widens(r, t))) {
                return t;
            }
        }
        return null;
    }

    private static final Type LONG = new IntT(CVal.of(64), true);

    /** The type all the numeric variables among {@code args} widen to, or null if there is none (§41.6). */
    private Type commonType(List<Type> args) {
        Type common = null;
        for (Type a : args) {
            if (!a.isNumeric()) {
                return null;
            }
            if (isAdaptive(a)) {
                continue;
            }
            common = common == null ? a : unifyNumeric(common, a);
            if (common == null) {
                return null;
            }
        }
        return common;
    }

    /** The type a constant has on its own in arithmetic: int (or a 64-bit type it needs), or double. */
    private static Type constType(Type constant) {
        if (constant instanceof FloatConstT) {
            return Type.DOUBLE;
        }
        if (constant instanceof IntConstT ic) {
            if (ic.value() == null || fits(ic.value(), (IntT) Type.INT)) {
                return Type.INT;
            }
            for (boolean signed : new boolean[]{true, false}) {
                IntT wide = new IntT(CVal.of(64), signed);
                if (fits(ic.value(), wide)) {
                    return wide;
                }
            }
        }
        return null;
    }

    /** The type a constant takes next to an operand of type {@code other}. */
    private Type adaptConst(Type constant, Type other) {
        if (other instanceof FixedT ft) {
            return constFitsFixed(constant, ft) ? ft : null;
        }
        if (constant instanceof IntConstT ic) {
            if (other instanceof IntT it) {
                return ic.value() == null || fits(ic.value(), it) ? it : null;
            }
            return other; // float, double, or float constant
        }
        return other instanceof FloatT || other instanceof DoubleT || other instanceof FloatConstT ? other : null;
    }

    private boolean comparable(Type l, Type r) {
        if (l.equals(r) && !(l instanceof ClassT) && !(l instanceof ArrayT) && !(l instanceof VoidT)) {
            return true;
        }
        if (unifyNumeric(l, r) != null) {
            return true;
        }
        if ((l instanceof AddrT || l instanceof HandleT) && r instanceof NullT) {
            return true;
        }
        if (l instanceof HandleT lh && r instanceof HandleT rh && lh.target() instanceof ClassT lc
                && rh.target() instanceof ClassT rc) {
            return isSubclass(lc, rc) || isSubclass(rc, lc);
        }
        return false;
    }

    /** Resolves an overloaded operator on a fixed class, including derived comparisons (§48). */
    private Type classOperator(Body b, ClassT ct, BinaryOp op, Type right, Pos pos, Expr site) {
        String sym = op.symbol;
        if (hasOperator(ct, sym)) {
            return operatorCall(b, ct, sym, List.of(right), pos, site);
        }
        String base = switch (op) {
            case NE -> "==";
            case GT, LE, GE -> "<";
            default -> null;
        };
        if (base != null && hasOperator(ct, base)) {
            boolean sameType = right.equals(ct) || (right instanceof HandleT rh && rh.target().equals(ct));
            if ((op == BinaryOp.GT || op == BinaryOp.LE) && !sameType) {
                report(pos, sym + " is derived from < and needs both operands to be " + ct);
                return Type.ERROR;
            }
            Type t = operatorCall(b, ct, base, List.of(right), pos, site);
            CallInfo ci = b.calls.get(site);
            if (ci != null) {
                // a != b = !(a == b);  a > b = b < a;  a <= b = !(b < a);  a >= b = !(a < b)
                boolean swap = op == BinaryOp.GT || op == BinaryOp.LE;
                boolean negate = op == BinaryOp.NE || op == BinaryOp.LE || op == BinaryOp.GE;
                b.calls.put(site, new CallInfo(ci.kind(), ci.member(), ci.owner(), ci.binders(), swap, negate));
            }
            return t.isError() ? t : Type.BIT;
        }
        report(pos, ct + " does not define operator " + sym + (base != null ? " (or " + base + " to derive it from)" : ""));
        return Type.ERROR;
    }

    /** The type whose operators apply to a value: a struct or unique itself, or the class a handle refers to. */
    private static ClassT operandClass(Type t) {
        if (t instanceof ClassT ct && !ct.cls().dynamic) {
            return ct;
        }
        return t instanceof HandleT h && h.target() instanceof ClassT ct ? ct : null;
    }

    private boolean hasOperator(ClassT ct, String sym) {
        return operatorOwner(ct, sym) != null;
    }

    /** The type in ct's inheritance chain that declares the operator, nearest first; null if none does. */
    private ClassT operatorOwner(ClassT ct, String sym) {
        for (ClassT c = ct; c != null; c = c.cls().dynamic ? parentOf(c) : null) {
            for (MemberInfo m : c.cls().all) {
                if (m.member instanceof Method md && m.existsIn(c.header()) && overloads(md, sym)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static boolean overloads(Method md, String sym) {
        for (Decorator d : md.modifiers().decorators()) {
            if (d.name().equals("overload") && sym.equals(OPERATORS.get(operatorConstant(d)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Operator constants @overload takes (§46), with the operator each stands for. The
     * library's Operator enum lists the same constants.
     */
    private static final Map<String, String> OPERATORS = new LinkedHashMap<>();
    /** The cast operator, (T)x, as OPERATORS names it. */
    private static final String CAST = "(T)";

    static {
        String[][] ops = {{"ADD", "+"}, {"SUBTRACT", "-"}, {"NEGATE", "-"}, {"MULTIPLY", "*"}, {"POWER", "**"}, {"DIVIDE", "/"},
                {"REMAINDER", "%"}, {"BIT_AND", "&"}, {"BIT_OR", "|"}, {"BIT_XOR", "^"}, {"BIT_NOT", "~"},
                {"EQUALS", "=="}, {"NOT_EQUALS", "!="}, {"LESS", "<"}, {"GREATER", ">"}, {"LESS_EQUAL", "<="},
                {"GREATER_EQUAL", ">="}, {"SHIFT", "<<<"}, {"SHIFT_ASSIGN", "<<="}, {"INDEX", "[]"}, {"INDEX_SET", "[]="},
                {"CAST", CAST}, {"ADD_ASSIGN", "+="}, {"SUBTRACT_ASSIGN", "-="}, {"MULTIPLY_ASSIGN", "*="}, {"POWER_ASSIGN", "**="},
                {"DIVIDE_ASSIGN", "/="}, {"REMAINDER_ASSIGN", "%="}, {"BIT_AND_ASSIGN", "&="}, {"BIT_OR_ASSIGN", "|="},
                {"BIT_XOR_ASSIGN", "^="}};
        for (String[] op : ops) {
            OPERATORS.put(op[0], op[1]);
        }
    }

    /** The constant an @overload names, "ADD" for @overload(Operator.ADD); null if it names none. */
    private static String operatorConstant(Decorator d) {
        if (d.args().size() == 1 && d.args().get(0) instanceof Select s && s.target() instanceof Name n
                && n.name().equals("Operator") && OPERATORS.containsKey(s.name())) {
            return s.name();
        }
        return null;
    }

    private static String overloadArgumentError(Decorator d) {
        if (d.args().size() == 1 && d.args().get(0) instanceof StringLit s) {
            List<String> names = OPERATORS.entrySet().stream().filter(e -> e.getValue().equals(s.value()))
                    .map(e -> "Operator." + e.getKey()).toList();
            return names.isEmpty() ? "operator " + s.value() + " cannot be overloaded"
                    : "@overload takes an Operator constant: use @overload(" + String.join(") or @overload(", names)
                            + ") for \"" + s.value() + "\"";
        }
        if (d.args().size() == 1 && d.args().get(0) instanceof Select s && s.target() instanceof Name n && n.name().equals("Operator")) {
            return "Operator has no constant " + s.name() + "; the operators are " + String.join(", ", OPERATORS.keySet());
        }
        return "@overload takes an Operator constant, e.g. @overload(Operator.ADD)";
    }

    private Type operatorCall(Body b, ClassT start, String sym, List<Type> args, Pos pos, Expr site) {
        // Operators are inherited: use the nearest class in the chain that declares one.
        ClassT declared = operatorOwner(start, sym);
        ClassT ct = declared != null ? declared : start;
        List<MemberInfo> methods = new ArrayList<>();
        for (MemberInfo m : ct.cls().all) {
            if (m.member instanceof Method md && m.existsIn(ct.header()) && overloads(md, sym)
                    && md.params().size() == args.size()) {
                if (!canAccess(b.cls, ct.cls(), md.modifiers())) {
                    report(pos, "operator " + sym + " of " + ct.cls().name + " is private");
                    return Type.ERROR;
                }
                methods.add(m);
            }
        }
        if (methods.isEmpty()) {
            report(pos, ct + " does not define " + (args.isEmpty() ? "unary " : "") + "operator " + sym);
            return Type.ERROR;
        }
        return resolveOverload(b, "operator " + sym, ct, methods, args, pos, false, site, CallKind.OPERATOR);
    }

    // ================================================================ assignment and lvalues

    private enum Lval { YES, VIA_HANDLE, NO }

    private Lval lvalue(Body b, Expr e) {
        return switch (e) {
            case Name n -> {
                String kind = b.nameKinds.get(n);
                yield "local".equals(kind) || "field".equals(kind) ? Lval.YES : Lval.NO;
            }
            case Select s -> {
                MemberInfo m = b.selected.get(s);
                if (m == null || !(m.member instanceof Field f) || f.modifiers().isConst()) {
                    yield Lval.NO;
                }
                if (f.modifiers().isStatic()) {
                    yield Lval.YES;
                }
                if (s.target() instanceof SuperRef) {
                    yield Lval.VIA_HANDLE;
                }
                Type tt = b.types.get(s.target());
                if (tt instanceof HandleT) {
                    yield Lval.VIA_HANDLE;
                }
                yield lvalue(b, s.target());
            }
            case Index i -> lvalue(b, i.target());
            case Slice s -> lvalue(b, s.target());
            case Unary u when u.op() == UnaryOp.DEREF -> Lval.YES;
            case This t -> Lval.YES; // its fields: this.x = x (assigning `this` itself is rejected)
            default -> Lval.NO;
        };
    }

    /**
     * The object whose abstract or default field {@code e} names, when the code reaches it through
     * the type that declares it (an interface, an abstract class), and so through accessors
     * (§30.4): `shape` for shape.name, `this` for name in a default method. Null otherwise.
     */
    private Expr virtualReceiver(Body b, Expr e) {
        if (e instanceof Name n && lookupLocal(b, n.name()) == null && !b.env.values.containsKey(n.name())) {
            Found f = quietly(() -> findMembers(b, b.self, b.cls, n.name(), n.pos(), b.isStatic));
            if (f != null && !f.error && f.owner != null && f.members.get(0).member instanceof Field fd && f.owner.cls().isVirtual(fd)) {
                return new This(n.pos());
            }
        }
        if (e instanceof Select s && !(s.target() instanceof SuperRef)) {
            Type t = quietly(() -> checkExpr(b, s.target(), null));
            ClassT ct = objectType(t);
            if (ct != null && ct.cls().dynamic) {
                Found f = quietly(() -> findMembers(b, ct, ct.cls(), s.name(), s.pos(), false));
                if (f != null && !f.error && f.owner != null && f.members.get(0).member instanceof Field fd
                        && f.owner.cls().isVirtual(fd)) {
                    return s.target();
                }
            }
        }
        return null;
    }

    /**
     * `shape.count += 1;` or `count++;` on a field reached through its accessors: read it, change
     * the copy, write it back, with the object evaluated once (§30.4). Null for other statements.
     */
    private Block virtualCompound(Body b, ExprStmt s) {
        Expr target = switch (s.expr()) {
            case Assign a when a.op() != null -> a.target();
            case IncDec i -> i.target();
            default -> null;
        };
        Expr owner = target == null ? null : virtualReceiver(b, target);
        if (owner == null) {
            return null;
        }
        Pos p = s.pos();
        String name = target instanceof Name n ? n.name() : ((Select) target).name();
        String obj = "$object" + (++hiddenNames);
        String held = "$field" + (++hiddenNames);
        List<Stmt> steps = new ArrayList<>();
        Expr access;
        if (owner instanceof This) {
            access = new Name(p, name);
        } else {
            steps.add(new VarDecl(p, null, obj, owner));
            access = new Select(p, new Name(p, obj), name);
        }
        steps.add(new VarDecl(p, null, held, access));
        steps.add(new ExprStmt(p, switch (s.expr()) {
            case Assign a -> new Assign(a.pos(), a.op(), new Name(p, held), a.value());
            case IncDec i -> new IncDec(i.pos(), i.op(), i.prefix(), new Name(p, held));
            default -> throw new IllegalStateException();
        }));
        steps.add(new ExprStmt(p, new Assign(p, null, access, new Name(p, held))));
        return new Block(p, steps);
    }

    /**
     * `xs[i] += v;` through [] and []= on an element that is an object (a struct, a class object,
     * a string): the element's own +=, which changes the copy [] gives, then stored back, with i
     * evaluated once (§46): { var e = xs[i]; e += v; xs[i] = e; }. Null for any other statement.
     */
    private Block elementCompound(Body b, ExprStmt s) {
        if (!(s.expr() instanceof Assign a) || a.op() == null || !(a.target() instanceof Index ix)) {
            return null;
        }
        Type element = quietly(() -> {
            Type indexed = checkExpr(b, ix.target(), null);
            ClassT ct = operandClass(indexed) != null ? operandClass(indexed) : indexed instanceof ClassT c ? c : null;
            if (ct == null || !hasOperator(ct, "[]=") || !hasOperator(ct, "[]")) {
                return null;
            }
            return operatorCall(b, ct, "[]", List.of(checkExpr(b, ix.index(), null)), ix.pos(), null);
        });
        if (element == null || element.isError() || operandClass(element) == null) {
            return null; // numbers and chars: xs[i] = xs[i] + v, as before
        }
        Pos p = a.pos();
        String index = "$index" + (++hiddenNames);
        String held = "$element" + (++hiddenNames);
        return new Block(p, List.of(
                new VarDecl(p, null, index, ix.index()),
                new VarDecl(p, null, held, new Index(ix.pos(), ix.target(), new Name(p, index))),
                new ExprStmt(p, new Assign(p, a.op(), new Name(p, held), a.value())),
                new ExprStmt(p, new Assign(p, null, new Index(ix.pos(), ix.target(), new Name(p, index)), new Name(p, held)))));
    }

    /**
     * xs[i] = v on an object: its []= operator, with the index and the value. A compound
     * assignment, xs[i] += v, is xs[i] = xs[i] + v, narrowed back to the element's type when
     * it computes in a wider one (§41.6). The assignment has no value of its own.
     */
    private Type checkIndexSet(Body b, Assign a, Index ix, ClassT ct) {
        if (a.op() != null) {
            Index read = new Index(ix.pos(), ix.target(), ix.index());
            Binary computed = new Binary(a.pos(), a.op(), read, a.value());
            b.written.put(computed, a.op().symbol + "=");
            Expr next = computed;
            Type r = quietly(() -> checkExpr(b, computed, null));
            Type element = b.types.get(read);
            Type value = b.types.get(a.value());
            boolean narrows = element != null && narrowsBack(r, element) && typeRef(a.pos(), element) != null;
            if (narrows) {
                next = new Cast(a.pos(), typeRef(a.pos(), element), next);
            }
            Assign plain = new Assign(a.pos(), null, ix, next);
            b.rewritten.put(a, plain);
            Type t = checkExpr(b, plain, null);
            if (narrows) {
                narrowingCast(a, r, value, element);
            }
            return t;
        }
        if (lvalue(b, ix.target()) == Lval.NO && !(b.types.get(ix.target()) instanceof HandleT)) {
            report(a.pos(), assignError(b, ix.target()));
            return Type.ERROR;
        }
        Type idx = checkExpr(b, ix.index(), null);
        Type element = hasOperator(ct, "[]") ? quietly(() -> operatorCall(b, ct, "[]", List.of(idx), ix.pos(), null)) : null;
        Type value = checkExpr(b, a.value(), element);
        if (idx.isError() || value.isError()) {
            return Type.ERROR;
        }
        Type r = operatorCall(b, ct, "[]=", List.of(idx, value), a.pos(), a);
        if (!r.isError() && element != null && !element.isError()) {
            requireNotCopied(b, a.value(), element, "assignment");
        }
        return r.isError() ? Type.ERROR : Type.VOID;
    }

    /** The variable an assignment changes, through fields and elements of values (not handles). */
    private static Name assignRoot(Body b, Expr e) {
        return switch (e) {
            case Name n -> n;
            case Select s when !(b.types.get(s.target()) instanceof HandleT) -> assignRoot(b, s.target());
            case Index i when !(b.types.get(i.target()) instanceof AddrT) -> assignRoot(b, i.target());
            case Slice s -> assignRoot(b, s.target());
            default -> null;
        };
    }

    private Type checkAssign(Body b, Assign a) {
        Expr owner = virtualReceiver(b, a.target());
        if (owner != null) {
            String name = a.target() instanceof Name n ? n.name() : ((Select) a.target()).name();
            if (a.op() != null) {
                report(a.pos(), name + " is reached through its accessors here, so " + compoundSymbol(a.op())
                        + " on it is a statement of its own (§30.4)");
                return Type.ERROR;
            }
            Call set = new Call(a.pos(), new Select(a.pos(), owner, "set " + name), List.of(a.value()), List.of());
            b.rewritten.put(a, set);
            checkExpr(b, set, null);
            return Type.VOID;
        }
        if (a.target() instanceof Index ix) {
            Type indexed = quietly(() -> checkExpr(b, ix.target(), null)); // errors come from the normal path below
            ClassT ct = operandClass(indexed) != null ? operandClass(indexed) : indexed instanceof ClassT c ? c : null;
            if (ct != null && hasOperator(ct, "[]=")) {
                return checkIndexSet(b, a, ix, ct);
            }
        }
        Type target = checkExpr(b, a.target(), null);
        Type value = checkExpr(b, a.value(), target);
        if (target.isError() || value.isError()) {
            return Type.ERROR;
        }
        if (!assignable(b, a.target(), a.pos())) {
            return Type.ERROR;
        }
        if (a.op() == null) {
            requireConvertible(b, a.value(), value, target, "assignment");
            requireNotCopied(b, a.value(), target, "assignment");
            return target;
        }
        if (a.op() == BinaryOp.ADD && target instanceof ClassT st && st.cls().name.equals("string") && st.cls().builtin
                && objectType(value) != null && !(value instanceof ClassT vc && vc.cls().name.equals("string"))) {
            // s += box: the object's cast to string (§46)
            if (!castsToString(value)) {
                report(a.value().pos(), noStringCast(value, "+= needs to join it to a string"));
                return Type.ERROR;
            }
            Assign joined = new Assign(a.pos(), BinaryOp.ADD, a.target(),
                    new Cast(a.value().pos(), new TypeRef(a.value().pos(), "string", List.of(), null, List.of()), a.value()));
            b.rewritten.put(a, joined);
            checkExpr(b, joined, null);
            return target;
        }
        if (operandClass(target) instanceof ClassT ct) {
            // On an object, a compound operator is its own overload, which changes the object itself:
            // x += y doesn't come from +, nor x <<= n from <<< (§46).
            String sym = compoundSymbol(a.op());
            if (!hasOperator(ct, sym)) {
                report(a.pos(), noCompound(ct, a.op()));
                return Type.ERROR;
            }
            operatorCall(b, ct, sym, List.of(value), a.pos(), a);
            return target;
        }
        Binary computed = new Binary(a.pos(), a.op(), a.target(), a.value());
        b.written.put(computed, a.op().symbol + "=");
        Type r = checkBinary(b, computed);
        if (target instanceof JavaPrimT && r instanceof JavaPrimT && !r.equals(target)) {
            b.narrowed.put(a, r); // b += c on JavaBytes is b = (byte)(b + c), as in Java
            return target;
        }
        if (narrowsBack(r, target)) {
            b.narrowed.put(a, r);
            narrowingCast(a, r, value, target);
        } else if (!r.isError()) {
            requireAssignable(b, r, target, a.pos(), "compound assignment");
        }
        return target;
    }

    /**
     * Whether a compound assignment computes in a wider number type than its target's and
     * narrows the result back, as Java's do: i += 2.5 is i = (int)(i + 2.5), and b += i on an
     * int<<8>> b is b = (int<<8>>)(b + i) (§41.6).
     */
    private boolean narrowsBack(Type result, Type target) {
        return !result.isError() && isNumber(result) && isNumber(target) && !assignable(result, target);
    }

    /**
     * -WautoCast for a compound assignment's narrowing; -WautoCastLossy too, unless the right
     * side is a constant that fits the target, as c += 1 on a char: that gives what arithmetic
     * in the target's own type would.
     */
    private void narrowingCast(Assign a, Type result, Type value, Type target) {
        autoCast(a.pos(), result, target, "compound assignment", !assignable(value, target));
    }

    /** A number type with a run-time value (not a constant's type): an integer or char, fixed point, float or double. */
    private static boolean isNumber(Type t) {
        return t instanceof IntT || t instanceof FixedT || t instanceof FloatT || t instanceof DoubleT;
    }

    /** A number type as source code would write it, for a cast the checker adds; null if it has no plain spelling. */
    private static TypeRef typeRef(Pos p, Type t) {
        return switch (t) {
            case IntT it when it.isChar() -> new TypeRef(p, "char", List.of(), null, List.of());
            case IntT it when it.isSize() -> new TypeRef(p, "size", List.of(), null, List.of());
            case IntT it when it.width() instanceof IntV w -> new TypeRef(p, it.signed() ? "int" : "uint", List.of(),
                    new ValueList(p, List.of(new ValueExpr(p, intLit(p, w.value().longValueExact())))), List.of());
            case FixedT ft when ft.intBits() instanceof IntV n && ft.fracBits() instanceof IntV m ->
                    new TypeRef(p, ft.signed() ? "fixed" : "ufixed", List.of(), new ValueList(p, List.of(
                            new ValueExpr(p, intLit(p, n.value().longValueExact())),
                            new ValueExpr(p, intLit(p, m.value().longValueExact())))), List.of());
            case FloatT f -> new TypeRef(p, "float", List.of(), null, List.of());
            case DoubleT d -> new TypeRef(p, "double", List.of(), null, List.of());
            default -> null;
        };
    }

    /** Whether code may assign {@code target}; reports why not. */
    private boolean assignable(Body b, Expr target, Pos pos) {
        if (lvalue(b, target) == Lval.NO) {
            report(pos, assignError(b, target));
            return false;
        }
        if (target instanceof This) {
            report(pos, "cannot assign to this");
            return false;
        }
        MemberInfo field = target instanceof Select s ? b.selected.get(s) : target instanceof Name n ? b.fieldOf.get(n) : null;
        if (field != null && isJavaArray(declaringClass(field))) {
            report(pos, "a Java array's length is fixed when it is made");
            return false;
        }
        if (field != null && javaMembers.get(field.member) instanceof JavaClasses.JMember jm && jm.isFinal()) {
            report(pos, "Java's field " + jm.name() + " is final, so it can't be assigned");
            return false;
        }
        if (target instanceof Name n && b.resources.contains(n.name()) && "local".equals(b.nameKinds.get(n))) {
            report(pos, n.name() + " is a resource of try-with-resources, closed at the end of its block, so it can't"
                    + " be assigned");
            return false;
        }
        Name root = assignRoot(b, target);
        if (root != null && "local".equals(b.nameKinds.get(root)) && capturedHere(b, root.name())) {
            report(pos, "a lambda can't assign " + root.name() + ": it holds a copy, made when the lambda was"
                    + " created; use a field, or an object both can see");
            return false;
        }
        String component = recordComponent(b, target);
        if (component != null) {
            report(pos, component);
            return false;
        }
        return true;
    }

    /**
     * Why assigning {@code target} would change a record's component, or null: components are
     * fixed once the record is made (§2.5), so only its constructors assign them. That includes
     * what a component holds inline, as p.from.x; a component that is a class object can still
     * change, as in Java.
     */
    private String recordComponent(Body b, Expr target) {
        Expr e = target;
        while (true) {
            ClassT owner = e instanceof Name || e instanceof Select ? b.owners.get(e) : null;
            String name = e instanceof Name n ? n.name() : e instanceof Select s ? s.name() : null;
            if (owner != null && owner.cls().kind == Kind.RECORD && !(b.inConstructor && b.cls == owner.cls())) {
                return name + " is a component of record " + owner.cls().name + ", fixed when it is made; make a new "
                        + owner.cls().name + " with the value instead";
            }
            Expr inner = switch (e) {
                case Select s when !(b.types.get(s.target()) instanceof HandleT) -> s.target();
                case Index i when !(b.types.get(i.target()) instanceof HandleT) && !(b.types.get(i.target()) instanceof AddrT) -> i.target();
                case Slice sl -> sl.target();
                default -> null;
            };
            if (inner == null) {
                return null;
            }
            e = inner;
        }
    }

    /**
     * `++x`, `x++`, `--x`, `x--` (§41.5): adds or subtracts one in x's own type, so a char
     * stays a char. On an element reached through a []= operator (xs[i]++ on an array<int>)
     * it runs xs[i] = xs[i] + 1, with the index evaluated once.
     */
    private Type checkIncDec(Body b, IncDec e) {
        if (virtualReceiver(b, e.target()) != null) {
            report(e.pos(), "this field is reached through its accessors here, so " + e.symbol()
                    + " on it is a statement of its own (§30.4)");
            return Type.ERROR;
        }
        if (e.target() instanceof Index ix) {
            Type indexed = quietly(() -> checkExpr(b, ix.target(), null)); // errors come from the normal path below
            ClassT ct = operandClass(indexed) != null ? operandClass(indexed) : indexed instanceof ClassT c ? c : null;
            if (ct != null && hasOperator(ct, "[]=")) {
                return checkIndexIncDec(b, e, ix, ct);
            }
        }
        Type t = checkExpr(b, e.target(), null);
        if (t.isError() || !assignable(b, e.target(), e.pos())) {
            return Type.ERROR;
        }
        if (t instanceof ParamT) {
            return t; // checked when the class or method is used with a concrete type
        }
        if (e.target() instanceof Slice) {
            report(e.pos(), e.symbol() + " can't change a bit slice; write x[a..b] " + (e.op() == BinaryOp.ADD ? "+" : "-")
                    + "= 1");
            return Type.ERROR;
        }
        if (t instanceof JavaPrimT j && j.code() != 'Z') {
            return t; // as Java's, in its own type: a JavaByte's wraps as a byte
        }
        if (!(t instanceof IntT || t instanceof FixedT || t instanceof FloatT || t instanceof DoubleT)) {
            report(e.pos(), e.symbol() + " needs a number or a char, not " + t);
            return Type.ERROR;
        }
        if (t instanceof FixedT ft && ft.intBits() instanceof IntV n
                && n.value().compareTo(BigInteger.valueOf(ft.signed() ? 2 : 1)) < 0) {
            report(e.pos(), e.symbol() + " steps by 1, which " + t + " can't hold");
            return Type.ERROR;
        }
        return t;
    }

    /** xs[i]++ on an object with a []= operator: xs[i] = xs[i] + 1, converted back to a char element. */
    private Type checkIndexIncDec(Body b, IncDec e, Index ix, ClassT ct) {
        Pos p = e.pos();
        Type idx = quietly(() -> checkExpr(b, ix.index(), null));
        Type element = hasOperator(ct, "[]") && !idx.isError()
                ? quietly(() -> operatorCall(b, ct, "[]", List.of(idx), ix.pos(), null)) : null;
        Binary step = new Binary(p, e.op(), new Index(ix.pos(), ix.target(), ix.index()), intLit(p, 1));
        b.written.put(step, null); // xs[i]++ keeps a char a char, with no cast of its own
        Expr next = step;
        if (Type.CHAR.equals(element)) {
            next = new Cast(p, new TypeRef(p, "char", List.of(), null, List.of()), next);
        } else if (element != null && !element.isError() && !(element instanceof ParamT) && !element.isNumeric()) {
            report(p, e.symbol() + " needs a number or a char, not " + element);
            return Type.ERROR;
        }
        Assign plain = new Assign(p, null, ix, next);
        b.elementSteps.put(e, plain);
        return checkExpr(b, plain, null).isError() || element == null ? Type.ERROR : element;
    }

    private String assignError(Body b, Expr target) {
        if (target instanceof Unary u && u.op() == UnaryOp.ADDRESS_OF) {
            return "*x is an address, not a location; to store through an address p write &p = value";
        }
        if (target instanceof Name n && "value".equals(b.nameKinds.get(n))) {
            return n.name() + " is a compile-time value and cannot be assigned";
        }
        if (target instanceof Name n && "const".equals(b.nameKinds.get(n))) {
            return n.name() + " is const";
        }
        if (target instanceof Select s && b.selected.get(s) != null
                && b.selected.get(s).member instanceof Field f && f.modifiers().isConst()) {
            return s.name() + " is const";
        }
        return "cannot assign to this expression";
    }

    // ================================================================ indexing and casts

    /** `x instanceof T`, and `x instanceof T name` (§35.1). */
    private Type checkInstanceOf(Body b, InstanceOf io) {
        Type from = checkExpr(b, io.operand(), null);
        Type to = resolveType(io.type(), b.env, new Opts(false, true, false, null));
        if (from.isError() || to.isError()) {
            return Type.ERROR;
        }
        if (!(to instanceof ClassT tc) || !tc.cls().dynamic) {
            report(io.type().pos(), "instanceof tests for a class or interface, not " + to);
            return Type.ERROR;
        }
        boolean ok = from instanceof NullT || (from instanceof HandleT fh && fh.target() instanceof ClassT fc
                && (isSubclass(fc, tc) || isSubclass(tc, fc) || fc.cls().isInterface || tc.cls().isInterface
                    || isJavaObject(fc.cls()))); // a JavaObject may hold a Seal object (docs/JAVA.md §3.1)
        if (!ok) {
            report(io.pos(), from instanceof HandleT ? from + " can never be a " + tc
                    : "instanceof needs an object (a class or interface handle), not " + from);
            return Type.ERROR;
        }
        b.owners.put(io, tc); // the class tested for, read by code generation
        return Type.BIT;
    }

    /** The `instanceof` tests in a condition whose bindings hold where it is true: x instanceof A a && ... */
    private static List<InstanceOf> bindings(Expr cond) {
        return switch (cond) {
            case InstanceOf io when io.binding() != null -> List.of(io);
            case Binary bin when bin.op() == BinaryOp.AND -> {
                List<InstanceOf> out = new ArrayList<>(bindings(bin.left()));
                out.addAll(bindings(bin.right()));
                yield out;
            }
            default -> List.of();
        };
    }

    private Type bindingType(Body b, InstanceOf io) {
        ClassT t = b.owners.get(io);
        return t == null ? Type.ERROR : new HandleT(t);
    }

    /** Runs {@code check} in a scope holding the bindings of {@code cond}. */
    private void withBindings(Body b, Expr cond, Runnable check) {
        List<InstanceOf> bs = bindings(cond);
        if (bs.isEmpty()) {
            check.run();
            return;
        }
        b.scopes.push(new HashMap<>());
        for (InstanceOf io : bs) {
            b.scopes.peek().put(io.binding(), bindingType(b, io));
        }
        try {
            check.run();
        } finally {
            b.scopes.pop();
        }
    }

    private Type checkIndex(Body b, Index i) {
        Type t = defaultLiteral(b, i.target(), checkExpr(b, i.target(), null)); // [1, 2, 3][i]
        ClassT indexed = operandClass(t) != null ? operandClass(t) : t instanceof ClassT ct ? ct : null;
        if (indexed != null && hasOperator(indexed, "[]")) {
            // xs[i] on an object: its [] operator (§46).
            Type idx = checkExpr(b, i.index(), null);
            if (t.isError() || idx.isError()) {
                return Type.ERROR;
            }
            return operatorCall(b, indexed, "[]", List.of(idx), i.pos(), i);
        }
        Type idx = checkExpr(b, i.index(), Type.INT);
        if (t.isError() || idx.isError()) {
            return Type.ERROR;
        }
        if (javaElement(t) != null) {
            // An element of a Java array (docs/JAVA.md §3.1), by an int, Seal's or Java's.
            if (!idx.isInteger() && !(idx instanceof JavaPrimT j && "ISBC".indexOf(j.code()) >= 0)) {
                report(i.index().pos(), "a Java array's index is an int, not " + idx);
                return Type.ERROR;
            }
            if (idx.isInteger()) {
                requireConvertible(b, i.index(), idx, Type.INT, "index");
            }
            return javaElement(t);
        }
        if (!idx.isInteger()) {
            report(i.index().pos(), "index must be an integer, not " + idx);
            return Type.ERROR;
        }
        return switch (t) {
            case ArrayT a -> {
                if (idx instanceof IntConstT ic && ic.value() != null && a.size() != null && a.size().intValue() != null
                        && (ic.value().signum() < 0 || ic.value().compareTo(a.size().intValue()) >= 0)) {
                    reportValue(i.index().pos(), "index " + ic.value() + " is out of bounds for " + a);
                }
                yield a.element();
            }
            case IntT it -> {
                if (idx instanceof IntConstT ic && ic.value() != null && it.width().intValue() != null
                        && (ic.value().signum() < 0 || ic.value().compareTo(it.width().intValue()) >= 0)) {
                    reportValue(i.index().pos(), "bit " + ic.value() + " is out of range for " + it);
                }
                yield Type.BIT;
            }
            case IntConstT ic -> Type.BIT;
            case AddrT a -> {
                report(i.pos(), "addresses cannot be indexed; use &(p + i * sizeof(" + a.target() + "))");
                yield Type.ERROR;
            }
            default -> {
                report(i.pos(), t + " cannot be indexed");
                yield Type.ERROR;
            }
        };
    }

    /** `x[from..to]`: bits from (inclusive) to to (exclusive), as a uint of that width. */
    private Type checkSlice(Body b, Slice s) {
        Type t = checkExpr(b, s.target(), null);
        if (t.isError()) {
            return t;
        }
        if (!t.isInteger()) {
            report(s.pos(), "only integers can be sliced, not " + t);
            return Type.ERROR;
        }
        Const from = evalConst(s.from(), b.env);
        Const to = evalConst(s.to(), b.env);
        if (from.value() instanceof ErrV || to.value() instanceof ErrV) {
            return Type.ERROR;
        }
        CVal width = CVal.binary("-", to.value(), from.value());
        BigInteger w = width == null ? null : width.intValue();
        if (w != null && w.signum() <= 0) {
            reportValue(s.pos(), "empty slice: " + from.value() + ".." + to.value());
            return Type.ERROR;
        }
        if (t instanceof IntT it && it.width().intValue() != null && to.value().intValue() != null
                && to.value().intValue().compareTo(it.width().intValue()) > 0) {
            reportValue(s.pos(), "slice end " + to.value() + " is past the width of " + it);
        }
        return new IntT(width, false);
    }

    /** (R)x on a type that defines casts: its @overload(Operator.CAST) method returning R exactly (§46). */
    private Type castOperator(Body b, Cast c, ClassT cls, Type from, Type to) {
        ClassT owner = operatorOwner(cls, CAST);
        Set<String> results = new LinkedHashSet<>();
        for (MemberInfo m : owner.cls().all) {
            if (!(m.member instanceof Method md) || !m.existsIn(owner.header()) || !overloads(md, CAST)
                    || !md.params().isEmpty()) {
                continue; // one with parameters was reported where it is declared
            }
            Type r = returnTypeOf(owner, m, Map.of());
            if (r instanceof VoidT || r.isError()) {
                continue;
            }
            if (r.equals(to)) {
                if (!canAccess(b.cls, owner.cls(), md.modifiers())) {
                    report(c.pos(), "the cast of " + owner.cls().name + " to " + to + " is private");
                    return Type.ERROR;
                }
                b.calls.put(c, new CallInfo(CallKind.OPERATOR, m, owner, Map.of(), false, false));
                return to;
            }
            results.add(r.toString());
        }
        report(c.pos(), to.equals(bareString()) ? noStringCast(from, null) + " (it casts to " + String.join(", ", results) + ")"
                : "cannot cast " + from + " to " + to + "; " + owner.cls().name + " casts only to " + String.join(", ", results));
        return Type.ERROR;
    }

    /** The class or struct a value of this type is (a class object through its handle), or null for other types. */
    private static ClassT objectType(Type t) {
        return t instanceof HandleT h && h.target() instanceof ClassT ct ? ct : t instanceof ClassT ct ? ct : null;
    }

    /** The bare string type, or null without the standard library. */
    private ClassT bareString() {
        ClassInfo s = classes.get("string");
        if (s == null || !s.builtin) {
            return null;
        }
        for (Shape shape : s.shapes) {
            if (shape.binders.isEmpty()) {
                return new ClassT(s, List.of(), List.of(), shape.index);
            }
        }
        return null;
    }

    /** Whether a value of this type has a cast to string: an @overload(Operator.CAST) method returning string (§46). */
    private boolean castsToString(Type t) {
        if (isJavaValue(t) || javaTextOnly(t)) {
            return true; // Java's text of it (docs/JAVA.md §3.1)
        }
        ClassT ct = objectType(t);
        ClassT owner = ct == null ? null : operatorOwner(ct, CAST);
        ClassT text = bareString();
        if (owner == null || text == null) {
            return false;
        }
        for (MemberInfo m : owner.cls().all) {
            if (m.member instanceof Method md && m.existsIn(owner.header()) && overloads(md, CAST) && md.params().isEmpty()
                    && text.equals(returnTypeOf(owner, m, Map.of()))) {
                return true;
            }
        }
        return false;
    }

    private String noStringCast(Type t, String why) {
        ClassT ct = objectType(t);
        String name = ct == null ? t.toString() : ct.cls().name;
        return name + " has no cast to string" + (why == null ? "" : ", which " + why) + "; give it one, as Java's toString(): "
                + "@overload(Operator.CAST) public string text() { ... }";
    }

    /**
     * A cast between Java's values and Seal's (docs/JAVA.md §3.1): out of Java, a primitive (or
     * its box) as a Seal number or bit, any Java value as its text, a JavaObject as a Seal class's
     * object, a Java array as a copy in a Seal array; between Java's classes, as in Java. Into Java
     * only seal.java's `of` methods go.
     */
    private Type javaCast(Cast c, Type from, Type to) {
        if (from.equals(to) || from instanceof NullT) {
            return to;
        }
        if (isJavaValue(from) && to.equals(bareString())) {
            return to; // its text: Java's String.valueOf (§3.1)
        }
        JavaPrimT prim = from instanceof JavaPrimT p ? p : boxOf(from);
        if (prim != null && (prim.code() == 'Z' ? to instanceof BitT : isNumber(to))) {
            return to;
        }
        if (from instanceof HandleT fh && fh.target() instanceof ClassT fc && isJavaObject(fc.cls()) && unboxedAs(to) != null) {
            return to; // (int)o, as Java's: an Integer, checked while the program runs, unboxed
        }
        if (prim != null && (to instanceof BitT || isNumber(to))) {
            report(c.pos(), "cannot cast " + from + " to " + to + "; " + from + " is Java's " + prim.javaName()
                    + ", which casts to " + (prim.code() == 'Z' ? "bit" : "a number"));
            return Type.ERROR;
        }
        if (from instanceof HandleT fh && fh.target() instanceof ClassT fc && to instanceof HandleT th
                && th.target() instanceof ClassT tc) {
            if (isJavaObject(fc.cls()) && !isJavaType(tc.cls())) {
                return to; // (Tag)o: a Seal object that went into Java, checked while the program runs
            }
            if (isJavaType(fc.cls()) && isJavaType(tc.cls()) && (isSubclass(fc, tc) || isSubclass(tc, fc)
                    || fc.cls().isInterface || tc.cls().isInterface)) {
                return to; // between Java's classes, as in Java
            }
        }
        if (javaElement(from) != null && to instanceof ClassT ac && isArrayObject(ac) && ac.valueArgs().isEmpty()
                && javaArrayCopies(javaElement(from), ac.typeArgs().get(0))) {
            return to; // a copy of a Java array, as a new Seal array (§3.1)
        }
        if (from instanceof HandleT fh2 && fh2.target() instanceof ClassT fc2 && !isJavaType(fc2.cls())
                && to instanceof HandleT th2 && th2.target() instanceof ClassT tc2 && tc2.cls().isInterface && isSubclass(fc2, tc2)) {
            return to; // a Seal object as a Java interface its class implements
        }
        if (from instanceof HandleT fh3 && fh3.target() instanceof ClassT fc3 && to instanceof HandleT th3
                && th3.target() instanceof ClassT tc3 && !isJavaType(tc3.cls()) && isSubclass(tc3, fc3)) {
            return to; // (Worker)thread: down to a Seal class extending a Java one, checked while the program runs
        }
        if (to instanceof HandleT th && th.target() instanceof ClassT tc && !isJavaType(tc.cls()) && isJavaValue(from)) {
            report(c.pos(), "cannot cast " + from + " to " + to + "; Java's objects aren't Seal's, but a Seal object that"
                    + " went into Java casts back from a JavaObject, as (" + tc.cls().name + ")o (docs/JAVA.md §3.1)");
            return Type.ERROR;
        }
        if (isJavaValue(to) && !isJavaValue(from)) {
            String of = javaOf(from);
            report(c.pos(), "cannot cast " + from + " to " + to + "; a Seal value goes into Java by "
                    + (of != null ? of + "(...)" : "seal.java's of methods") + ", not by a cast (docs/JAVA.md §3.1)");
            return Type.ERROR;
        }
        report(c.pos(), "cannot cast " + from + " to " + to);
        return Type.ERROR;
    }

    /** The Java primitive a Seal type is (JavaInt for int, JavaBoolean for bit), whose box a cast from Object takes; or null. */
    public static JavaPrimT unboxedAs(Type t) {
        for (char code : "IJSBFDCZ".toCharArray()) {
            if (new JavaPrimT(code).sealType().equals(t)) {
                return new JavaPrimT(code);
            }
        }
        return null;
    }

    /** The primitive a Java box holds (JavaInt for JavaIntBoxed), or null for any other type. */
    private static JavaPrimT boxOf(Type t) {
        if (!(t instanceof HandleT h && h.target() instanceof ClassT ct && ct.cls().java != null)) {
            return null;
        }
        return switch (ct.cls().java.name()) {
            case "java.lang.Integer" -> new JavaPrimT('I');
            case "java.lang.Long" -> new JavaPrimT('J');
            case "java.lang.Short" -> new JavaPrimT('S');
            case "java.lang.Byte" -> new JavaPrimT('B');
            case "java.lang.Float" -> new JavaPrimT('F');
            case "java.lang.Double" -> new JavaPrimT('D');
            case "java.lang.Character" -> new JavaPrimT('C');
            case "java.lang.Boolean" -> new JavaPrimT('Z');
            default -> null;
        };
    }

    /**
     * Whether a Java array of this element type copies into a Seal array of that one: a primitive's
     * into the Seal number's (a byte[] into an array<int<<8>>> or, by its bits, an array<uint<<8>>>),
     * and objects into an array of their own type.
     */
    private static boolean javaArrayCopies(Type javaElement, Type sealElement) {
        if (javaElement instanceof JavaPrimT p) {
            return p.sealType().equals(sealElement) || (p.code() == 'B' && sealElement.equals(new IntT(CVal.of(8), false)));
        }
        if (javaElement instanceof HandleT h && h.target() instanceof ClassT ct && ct.cls().java != null
                && ct.cls().java.name().equals("java.lang.String")) {
            return sealElement instanceof ClassT sc && sc.cls().name.equals("string") && sc.cls().builtin;
        }
        return javaElement.equals(sealElement);
    }

    private Type checkCast(Body b, Cast c) {
        Type to = resolveType(c.type(), b.env, new Opts(false, false, false, null));
        Type from = checkExpr(b, c.operand(), to);
        if (to.isError() || from.isError()) {
            return Type.ERROR;
        }
        if (from instanceof ParamT) {
            return to; // (string)x on a T: checked for each type T is used with (§12)
        }
        if (isJavaValue(from) || isJavaValue(to)) {
            return javaCast(c, from, to);
        }
        if (to.equals(bareString()) && javaTextOnly(from)) {
            return to; // a Seal class extending a Java one, without a cast of its own: Java's toString() (docs/JAVA.md §5)
        }
        ClassT castsItself = operandClass(from) != null ? operandClass(from) : from instanceof ClassT ct ? ct : null;
        boolean ok = from.equals(to)
                || (from.isNumeric() && to.isNumeric())
                || (from instanceof AddrT && to instanceof AddrT)
                || (from instanceof NullT && (to instanceof AddrT || to instanceof HandleT))
                || (from instanceof HandleT fh && to instanceof HandleT th && fh.target() instanceof ClassT fc
                    && th.target() instanceof ClassT tc && (isSubclass(fc, tc) || isSubclass(tc, fc)
                        || fc.cls().isInterface || tc.cls().isInterface)); // checked at run time
        if (!ok && castsItself != null && hasOperator(castsItself, CAST)) {
            return castOperator(b, c, castsItself, from, to);
        }
        if (!ok && castsItself != null && to.equals(bareString())) {
            report(c.pos(), noStringCast(from, null));
            return Type.ERROR;
        }
        if (!ok) {
            report(c.pos(), "cannot cast " + from + " to " + to);
            return Type.ERROR;
        }
        if ((from instanceof IntConstT || from instanceof FloatConstT) && to instanceof FixedT ft
                && !constFitsFixed(from, ft)) {
            reportValue(c.pos(), "constant does not fit in " + ft);
        }
        if (from instanceof IntConstT ic && ic.value() != null && to instanceof IntT it && !fits(ic.value(), it)) {
            reportValue(c.pos(), "integer " + ic.value() + " does not fit in " + it);
        }
        return to;
    }
}
