package lang.check;

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

import lang.ast.Ast.*;
import lang.check.CVal.*;
import lang.check.ClassInfo.MemberInfo;
import lang.check.ClassInfo.Shape;
import lang.check.Type.*;
import lang.parser.ParseError;
import lang.parser.Parser;

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
            class Object
            {
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
            class Writer
            {
                int<<64>> file;
                private<System> new(int<<64>> f) { file = f; }
                @native("io_write_char") static int<<64>> nChar(int<<64>> file, char c) { }
                @native("io_write_int") static int<<64>> nInt(int<<64>> file, int<<64>> x) { }
                @native("io_write_uint") static int<<64>> nUint(int<<64>> file, uint<<64>> x) { }
                @native("io_write_float") static int<<64>> nFloat(int<<64>> file, float x) { }
                @native("io_write_double") static int<<64>> nDouble(int<<64>> file, double x) { }
                @native("io_write_bit") static int<<64>> nBit(int<<64>> file, bit x) { }
                @native("io_write_fixed") static int<<64>> nFixed(int<<64>> file, fixed<<int N, int M>> x) { }
                @native("io_write_ufixed") static int<<64>> nUfixed(int<<64>> file, ufixed<<int N, int M>> x) { }
                public void print(char c) { nChar(file, c); }
                public void println(char c) { print(c); nChar(file, '\\n'); }
                public void print(int x) { nInt(file, (int<<64>>)x); }
                public void println(int x) { print(x); nChar(file, '\\n'); }
                public void print(int<<int W>> x) { nInt(file, (int<<64>>)x); }
                public void println(int<<int W>> x) { print(x); nChar(file, '\\n'); }
                public void print(uint<<int W>> x) { nUint(file, (uint<<64>>)x); }
                public void println(uint<<int W>> x) { print(x); nChar(file, '\\n'); }
                public void print(float x) { nFloat(file, x); }
                public void println(float x) { print(x); nChar(file, '\\n'); }
                public void print(double x) { nDouble(file, x); }
                public void println(double x) { print(x); nChar(file, '\\n'); }
                public void print(bit x) { nBit(file, x); }
                public void println(bit x) { print(x); nChar(file, '\\n'); }
                public void print(fixed<<int A, int B>> x) { nFixed(file, x); }
                public void println(fixed<<int A, int B>> x) { print(x); nChar(file, '\\n'); }
                public void print(ufixed<<int A, int B>> x) { nUfixed(file, x); }
                public void println(ufixed<<int A, int B>> x) { print(x); nChar(file, '\\n'); }
                public void println() { nChar(file, '\\n'); }
            }
            """;

    private static final String PRELUDE_SYSTEM = """
            struct System
            {
                public static Writer stdout = new Writer(2);
                public static Writer stderr = new Writer(3);
            }
            """;

    private static final int MAX_INSTANTIATION_DEPTH = 64;

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
        Checker c = new Checker();
        c.warn.addAll(warn);
        c.run(sources, library);
        return c;
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
     * A checked switch: the subject, held in a hidden local, and for each case the
     * `$subject == label` tests that select it (empty for default).
     */
    public record SwitchInfo(VarDecl subject, List<List<Expr>> tests) {
    }

    /**
     * A checked lambda (§30.3): the interface and method it implements, the parameter and
     * return types, the variables it copies when created (in order), and the enclosing
     * object's type if it uses that object's fields or methods (null otherwise).
     */
    public record LambdaInfo(ClassT iface, MemberInfo method, List<Type> params, Type returnType,
                             List<String> captureNames, List<Type> captureTypes, ClassT thisType) {
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
        /** Each for-each loop as the ordinary loop it stands for; generate that instead. */
        public final Map<ForEach, Stmt> desugared;
        public final Map<Switch, SwitchInfo> switches;
        /** A switch expression's hidden subject and its tests, as for a switch statement (§41.3). */
        public final Map<SwitchExpr, SwitchInfo> switchExprs;
        /** Asserts that run (with -Wassert), as the if-and-throw each stands for (§41.4). */
        public final Map<Assert, Stmt> assertions;
        public final Map<Lambda, LambdaInfo> lambdas;
        /** The class each catch clause catches. */
        public final Map<Catch, ClassT> catchTypes;
        /** Expressions to generate in place of others: 5 + "x" is "" + 5 + "x". */
        public final Map<Expr, Expr> rewritten;
        /** xs[i]++ on an object with a []= operator: the xs[i] = xs[i] + 1 it runs, reading xs[i] once (§41.5). */
        public final Map<IncDec, Assign> elementSteps;
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
            this.lambdas = b.lambdas;
            this.catchTypes = b.catchTypes;
            this.rewritten = b.rewritten;
            this.elementSteps = b.elementSteps;
            this.narrowed = b.narrowed;
            this.implicitCasts = b.implicitCasts;
            this.params = params;
            this.returnType = returnType;
            this.errors = errors;
            this.env = b.env;
        }
    }

    public ClassInfo classNamed(String name) {
        return classes.get(name);
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

    public java.util.Collection<ClassInfo> allClasses() {
        return classes.values();
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
                env.values.put(k, bound.get(k)); // for later parameters that use it: min(int<<int W>> a, int<<W>> b)
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

    /** The VM function implementing a `@native("name")` method, or null. */
    public static String nativeName(Method md) {
        for (Decorator d : md.modifiers().decorators()) {
            if (d.name().equals("native") && d.args().size() == 1 && d.args().get(0) instanceof StringLit s) {
                return s.value();
            }
        }
        return null;
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
            classes.put(builtin.name, builtin);
        }
        Set<String> libraryNames = new HashSet<>();
        libraryFiles.addAll(library.keySet());
        for (Map.Entry<String, String> e : library.entrySet()) {
            ClassInfo info = load(e.getKey(), e.getValue(), true);
            if (info != null && addClass(e.getKey(), info, true, order)) {
                libraryNames.add(info.name);
            }
        }
        for (Map.Entry<String, String> e : sources.entrySet()) {
            ClassInfo info = load(e.getKey(), e.getValue(), false);
            if (info != null) {
                if (libraryNames.contains(info.name)) {
                    file = e.getKey();
                    report(info.decl.pos(), info.kind.keyword + " " + info.name + " is part of the standard library ("
                            + classes.get(info.name).file + "); choose another name");
                    continue;
                }
                addClass(e.getKey(), info, false, order);
            }
        }

        for (ClassInfo c : classes.values()) {
            file = c.file;
            declareHeaders(c);
        }
        for (ClassInfo c : classes.values()) {
            file = c.file;
            declareMembers(c);
        }
        for (ClassInfo c : classes.values()) {
            if (!c.builtin && c.outer == null) {
                file = c.file;
                checkImports(c); // after the members, which static imports name
            }
        }
        for (ClassInfo c : classes.values()) {
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
            checkStructRules(c);
        }
        checkInheritanceCycles(order);
        for (ClassInfo c : order) {
            file = c.file;
            checkAbstractMembers(c);
            checkImplements(c);
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
            if (isLibrary) {
                report(info.pkgPos, "the standard library's types are in no package");
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
     * one in the same package; one a `*` import brings; and the standard library's. `A.B` is
     * B nested in what A means, or the type B in package A. Null if there is none, or if two
     * `*` imports bring one each ({@link #unknownTypeHint} says which).
     */
    public ClassInfo lookupClass(String name, ClassInfo from) {
        int dot = name.indexOf('.');
        if (dot > 0) {
            ClassInfo head = lookupClass(name.substring(0, dot), from);
            if (head != null) {
                return classes.get(head.qualifiedName() + name.substring(dot));
            }
            ClassInfo full = classes.get(name); // com.example.Vec2, com.example.Outer.Inner
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
            if (!i.all() && i.name().endsWith("." + name) && classes.get(i.name()) instanceof ClassInfo c) {
                return c;
            }
        }
        if (!pkg.isEmpty() && classes.get(pkg + "." + name) instanceof ClassInfo c) {
            return c;
        }
        List<ClassInfo> brought = imported(name, imports);
        if (!brought.isEmpty()) {
            return brought.size() == 1 ? brought.get(0) : null;
        }
        // The standard library's types are seen everywhere; other types outside packages only outside packages.
        ClassInfo c = classes.get(name);
        return c != null && (c.builtin || pkg.isEmpty()) ? c : null;
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
            if (!i.all() && !i.name().endsWith("." + name)) {
                continue;
            }
            ClassInfo c = classes.get(type);
            if (c == null || (c.pkg.isEmpty() && !c.builtin && !from.pkg.isEmpty())) {
                continue; // reported by checkImports
            }
            for (MemberInfo m : c.members.getOrDefault(name, List.of())) {
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
        List<String> owners = classes.values().stream().filter(c -> c.builtin && c.outer == null
                && c.members.getOrDefault(name, List.of()).stream().anyMatch(m -> m.member instanceof Method md
                        && md.modifiers().isStatic() && md.modifiers().access() == Access.PUBLIC))
                .map(c -> c.name).sorted(java.util.Comparator.comparing((String n) -> !n.equals("Math")) // the general one first
                        .thenComparing(n -> n)).toList();
        if (owners.isEmpty()) {
            return "";
        }
        String list = owners.size() == 1 ? owners.get(0)
                : String.join(", ", owners.subList(0, owners.size() - 1)) + " and " + owners.get(owners.size() - 1);
        return "; it is a static method of " + list + ": call " + owners.get(0) + "." + name + "(...), or import static "
                + owners.get(0) + "." + name + ";";
    }

    /** The types named {@code name} that `*` imports bring. */
    private List<ClassInfo> imported(String name, List<Import> imports) {
        List<ClassInfo> found = new ArrayList<>();
        for (Import i : imports) {
            if (i.all() && classes.get(i.name() + "." + name) instanceof ClassInfo c && !found.contains(c)) {
                found.add(c);
            }
        }
        return found;
    }

    /** Why a type name means nothing where {@code from} is written, when there is more to say, as "; ...". */
    private String unknownTypeHint(String name, ClassInfo from) {
        if (name.indexOf('.') >= 0) {
            return "";
        }
        List<ClassInfo> brought = imported(name, from == null ? List.of() : from.imports);
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
                .map(ClassInfo::qualifiedName).sorted().toList();
        if (!elsewhere.isEmpty()) {
            return "; import it: import " + String.join("; or import ", elsewhere) + ";";
        }
        return "";
    }

    /** `import static T.member;` needs a static member (or nested type) of T so named; `import static T.*;` a type T. */
    private void checkStaticImport(ClassInfo c, Import i) {
        String type = i.all() ? i.name() : i.name().substring(0, i.name().lastIndexOf('.'));
        ClassInfo t = classes.get(type);
        if (t == null || (t.pkg.isEmpty() && !t.builtin && !c.pkg.isEmpty())) {
            report(i.pos(), "no type " + type + " to import from" + (t == null ? unknownTypeHint(type, c) : "; " + type
                    + " is in no package, and as in Java, a type in a package can't use one that isn't"));
            return;
        }
        if (!canSeeType(c, t)) {
            report(i.pos(), t.name + " is private to " + t.outer.name);
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
                boolean exists = classes.values().stream().anyMatch(k -> k.pkg.equals(i.name()) || k.qualifiedName().equals(i.name()));
                if (!exists) {
                    report(i.pos(), "no package " + i.name() + " to import from");
                }
                continue;
            }
            ClassInfo t = classes.get(i.name());
            String simple = i.name().substring(i.name().lastIndexOf('.') + 1);
            if (t == null || t.pkg.isEmpty()) {
                ClassInfo library = classes.get(simple);
                report(i.pos(), "no type " + i.name() + " to import"
                        + (library != null && library.builtin ? "; " + simple + " is in the standard library, which needs no import"
                                : unknownTypeHint(simple, c)));
            } else if (!canSeeType(c, t)) {
                report(i.pos(), t.name + " is private to " + t.outer.name);
            } else if (simple.equals(c.simpleName) && t != c) {
                report(i.pos(), "import " + i.name() + " clashes with " + c.qualifiedName() + ", declared in this file");
            } else if (named.containsKey(simple) && named.get(simple) != t) {
                report(i.pos(), "import " + i.name() + " clashes with the import of " + named.get(simple).qualifiedName());
            } else {
                named.put(simple, t);
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
        for (Member m : c.decl.members()) {
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
                if (parent instanceof ClassT pc && pc.cls().isInterface) {
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

    /** A struct is copied freely, so it can't have a destructor or hold a unique (§2.1). */
    private void checkStructRules(ClassInfo c) {
        if (c.dynamic || c.unique) {
            return;
        }
        for (MemberInfo d : c.destructors) {
            report(d.member.pos(), "struct " + c.name + " cannot have a destructor: copies of it would run it more than once;"
                    + " declare it `unique " + c.name + "` instead");
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
        if (isUnique(t) && !isTemporary(src)) {
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
        report(ref.pos(), "arrays are written " + arraySpelling(ref) + ", not " + lang.ast.AstPrinter.print(ref));
        return Type.ERROR;
    }

    /** How T[N][K] (or T[]) is written now: array<T><<N, K>>, array<T>. */
    private static String arraySpelling(TypeRef ref) {
        TypeRef base = new TypeRef(ref.pos(), ref.name(), ref.typeArgs(), ref.valueArgs(), List.of());
        String s = lang.ast.AstPrinter.print(base);
        List<Expr> dims = ref.arrayDims();
        if (dims.contains(null)) {
            for (int i = dims.size() - 1; i >= 0; i--) {
                s = dims.get(i) == null ? "array<" + s + ">" : "array<" + s + "><<" + lang.ast.AstPrinter.print(dims.get(i)) + ">>";
            }
            return s;
        }
        return "array<" + s + "><<" + dims.stream().map(lang.ast.AstPrinter::print).collect(Collectors.joining(", ")) + ">>";
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
                lengths.add(len.value());
            }
            Type t = element;
            for (int i = lengths.size() - 1; i >= 0; i--) {
                t = new ArrayT(t, lengths.get(i));
            }
            return t;
        }
        switch (n) {
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
                    if (!w.type().isInteger()) {
                        report(items.get(0).pos(), "integer width must be an integer, not " + w.type());
                        return Type.ERROR;
                    }
                    width = w.value();
                    BigInteger v = width.intValue();
                    if (v != null && (v.signum() <= 0 || v.compareTo(BigInteger.valueOf(64)) > 0)) {
                        reportValue(items.get(0).pos(), "integer width must be between 1 and 64, not " + v
                                + "; use a class such as BigUInt<<" + v + ">> for wider values");
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

        if (ref.typeArgs().size() != c.typeParams.size()) {
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
                    if (sm.m().member instanceof Field cf && constField(sm.cls(), n.name()) == cf) {
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
                            + " " + r.value() + (b.op() == BinaryOp.DIV || b.op() == BinaryOp.MOD ? " divides by zero" : ""));
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
            default:
                report(e.pos(), "not a compile-time constant; <<...>> values and array sizes may use literals, "
                        + "arithmetic, const fields, sizeof and other compile-time values");
                return new Const(new ErrV(), Type.ERROR);
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
            return undetermined ? null : objectType();
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
        ClassInfo o = classes.get("Object");
        return new ClassT(o, List.of(), List.of(), 0);
    }

    /** Whether {@code sub} is {@code sup}, inherits from it, or implements it (an interface). */
    private boolean isSubclass(ClassT sub, ClassT sup) {
        if (sup.cls().isRoot() && sub.cls().dynamic) {
            return true; // every class and interface handle is an Object
        }
        ClassT t = sub;
        for (int i = 0; t != null && i < MAX_INSTANTIATION_DEPTH; i++) {
            if (t.equals(sup) || (sup.cls().isInterface && implementsInterface(t, sup, 0))) {
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
            if (i.equals(itf) || implementsInterface(i, itf, depth + 1)) {
                return true;
            }
        }
        return false;
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
                    out.add(ic);
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
                    case Method md when md.body() != null -> report(md.pos(), "interface methods have no body;"
                            + " end " + md.name() + "(...) with ';' and implement it in the classes");
                    case Method md when md.modifiers().isStatic() -> report(md.pos(), "interfaces can't have static methods");
                    case Method md when !md.modifiers().decorators().isEmpty() -> report(md.pos(),
                            "interface methods can't have decorators");
                    case Method md when isAbstract -> report(md.pos(), "interface methods are always abstract; leave out `abstract`");
                    case Method md -> { }
                    case Field f -> report(f.pos(), "interfaces can't have fields; only method signatures");
                    case Constructor k -> report(k.pos(), "interfaces can't have constructors");
                    case Destructor d -> report(d.pos(), "interfaces can't have destructors");
                }
                continue;
            }
            if (!(member instanceof Method md)) {
                if (isAbstract) {
                    report(member.pos(), "only methods can be abstract");
                }
                continue;
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
            } else if (md.body() == null) {
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
                    if (!(m.member instanceof Method md) || md.body() != null || !m.existsIn(owner.header())
                            || md.modifiers().isStatic()) {
                        continue;
                    }
                    List<Type> params = paramTypes(owner, m, Map.of());
                    String sig = md.name() + params.stream()
                            .map(t -> t instanceof HandleT h ? h.target().toString() : t.toString()) // as written: Animal
                            .collect(Collectors.joining(", ", "(", ")"));
                    Impl impl = findImplementation(self, md.name(), params);
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
        final Map<ForEach, Stmt> desugared = new IdentityHashMap<>();
        final Map<Switch, SwitchInfo> switches = new IdentityHashMap<>();
        final Map<Catch, ClassT> catchTypes = new IdentityHashMap<>();
        /** Expressions that stand for others the checker wrote, e.g. 5 + "x" for "" + 5 + "x". */
        final Map<Expr, Expr> rewritten = new IdentityHashMap<>();
        final Map<IncDec, Assign> elementSteps = new IdentityHashMap<>();
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
                        // Implemented by the VM.
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
        if (!t.isError() && !t.isInteger() && !(t instanceof ClassT ct && (ct.cls().name.equals("string") || enumClass != null))) {
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

    /** Whether a call is to System.rawAssembly (§42.3), which generates no code in the VM. */
    public boolean isRawAssembly(CallInfo ci) {
        if (ci == null || ci.member() == null || !(ci.member().member instanceof Method md)
                || !md.name().equals("rawAssembly") || !md.modifiers().isStatic()) {
            return false;
        }
        ClassInfo c = declaringClass(ci.member());
        return c.builtin && c.name.equals("System");
    }

    /** Whether a type is a handle to an Exception (or a subclass). */
    private boolean isException(Type t) {
        ClassInfo e = classes.get("Exception");
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
        boolean simple = isSimplePath(items);
        String hidden = "$items" + (++hiddenNames);
        Expr source = simple ? items : new Name(p, hidden);
        List<Stmt> out = new ArrayList<>();
        if (!simple) {
            out.add(new VarDecl(p, null, hidden, items)); // evaluated once
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
            case Try tr -> {
                checkStmt(b, tr.body());
                List<ClassT> caught = new ArrayList<>();
                for (Catch c : tr.catches()) {
                    Type t = resolveType(c.type(), b.env, new Opts(false, true, false, null));
                    if (!t.isError() && !(t instanceof ClassT ct && isException(new HandleT(ct)))) {
                        report(c.type().pos(), "catch takes a class extending Exception, not " + t);
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
                if (!(t instanceof AddrT) && !(t instanceof HandleT) && !t.isError()) {
                    report(d.pos(), "delete needs an addr<...> or handle<...>, not " + t
                            + (t instanceof ClassT ? "; inline objects are destroyed automatically at scope exit" : ""));
                }
            }
        }
    }

    private void requireCondition(Body b, Expr cond) {
        Type t = checkExpr(b, cond, Type.BIT);
        if (!(t instanceof BitT) && !t.isError()) {
            report(cond.pos(), "condition must be a bit, not " + t + (t.isInteger() ? "; compare explicitly, e.g. x != 0" : ""));
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
        if (from.equals(to)) {
            return false;
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
        if (!isNumber(to) || from.equals(to) || assignable(from, to)) {
            return false;
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
        if (from instanceof IntConstT ic && ic.value() != null && (to instanceof IntT || to instanceof FixedT)) {
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
            String hint = from.isNumeric() && to.isNumeric() ? "; use a cast, e.g. (" + to + ")" : "";
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
            case Call c -> checkCall(b, c);
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
        List<ClassT> all = new ArrayList<>();
        all.add(ic);
        all.addAll(allInterfacesOf(ic));
        List<Impl> found = new ArrayList<>();
        Set<String> sigs = new HashSet<>();
        for (ClassT i : all) {
            for (MemberInfo m : i.cls().all) {
                if (m.member instanceof Method md && md.body() == null
                        && sigs.add(md.name() + paramTypes(i, m, Map.of()))) {
                    found.add(new Impl(i, m));
                }
            }
        }
        return found.size() == 1 ? found.get(0) : null;
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
        b.lambdas.put(lam, new LambdaInfo(fn.owner(), fn.member(), params, ret,
                List.copyOf(ctx.captures.keySet()), List.copyOf(ctx.captures.values()), thisType));
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
                report(n.pos(), n.name() + " could be " + imported.stream().map(sm -> sm.cls().qualifiedName() + "." + n.name())
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
        for (ClassInfo c : classes.values()) {
            if (c.all.contains(m)) {
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
            case ClassRefT cr when classes.get(cr.cls().qualifiedName() + "." + s.name()) instanceof ClassInfo inner -> {
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
        b.selected.put(s, m);
        b.owners.put(s, found.owner);
        return memberFieldType(found.owner, m, b.cls, (Field) m.member);
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
        return resolveOverload(b, cands.name, cands.owner, cands.methods, argTypes, c.pos(), cands.staticAccess,
                c, cands.viaSuper ? CallKind.SUPER_METHOD : CallKind.METHOD);
    }

    private record Candidates(String name, ClassT owner, List<MemberInfo> methods, boolean staticAccess,
                              boolean viaSuper) {
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
                    report(n.pos(), "unknown method " + n.name() + staticMethodHint(n.name()));
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
                Found f = findMembers(b, owner, cls, s.name(), s.pos(), staticAccess);
                if (f == null) {
                    report(s.pos(), cls.name + " has no method " + s.name());
                    return null;
                }
                if (f.error) {
                    return null;
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
            // A plain parameter is more specific than one with value binders: add(int) over add(int<<int W>>).
            List<Match> plain = pick.stream().filter(m -> m.bound.isEmpty()).toList();
            if (plain.size() == 1) {
                pick = plain;
            }
        }
        if (pick.size() > 1) {
            // Value binders are more specific than type parameters: of(int<<int W>> x) over of<K>(K key).
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
                // Still tied, as add(int) and add(int<<int W>>) are for "x" + 5: the plain parameter wins.
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
            if (!sig.binders.isEmpty() && a.equals(Type.CHAR) && unify(p, Type.INT, sig.binders.keySet(), bound)) {
                continue; // a char is an int here too: abs(int<<int W>> x) takes one as an int
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
            case IntT i -> new IntT(bindValue(i.width(), bound), i.signed(), i.isChar());
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
                    || ((a instanceof StrConstT || a instanceof ArrayConstT) && p.equals(defaultOf(a)))) {
                kept++;
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
            case IntT p -> arg instanceof IntT a && a.signed() == p.signed() && a.isChar() == p.isChar()
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
            // the most specific: parameters that each equal or widen to every other's (§41.6).
            List<List<Type>> all = List.copyOf(matchingParams);
            for (java.util.function.Predicate<List<Type>> better : List.<java.util.function.Predicate<List<Type>>>of(
                    ps -> ps.equals(args), ps -> keepsChars(ps, args),
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
            } else if (c.typeParams.isEmpty() && c.shapes.stream().anyMatch(s -> s.binders.isEmpty())) {
                t = new ClassT(c, List.of(), List.of(),
                        c.shapes.stream().filter(s -> s.binders.isEmpty()).findFirst().orElseThrow().index);
            } else {
                report(n.pos(), "cannot tell which shape of " + c.name + " to create; assign it to a variable of type "
                        + c.name + (c.typeParams.isEmpty() ? "" : "<...>") + "<<...>>");
                return Type.ERROR;
            }
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
                && !((stringLeft ? r : l) instanceof BitT) && objectType(stringLeft ? r : l) != null) {
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
                report(bin.pos(), "division by zero");
                return Type.ERROR;
            }
            return new IntConstT(v.intValue());
        }
        if (u instanceof IntConstT) {
            return new IntConstT(null);
        }
        return u instanceof IntT it && it.isChar() ? Type.INT : u; // as in Java, arithmetic on chars gives an int
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
        String[][] ops = {{"ADD", "+"}, {"SUBTRACT", "-"}, {"NEGATE", "-"}, {"MULTIPLY", "*"}, {"DIVIDE", "/"},
                {"REMAINDER", "%"}, {"BIT_AND", "&"}, {"BIT_OR", "|"}, {"BIT_XOR", "^"}, {"BIT_NOT", "~"},
                {"EQUALS", "=="}, {"NOT_EQUALS", "!="}, {"LESS", "<"}, {"GREATER", ">"}, {"LESS_EQUAL", "<="},
                {"GREATER_EQUAL", ">="}, {"SHIFT", "<<<"}, {"SHIFT_ASSIGN", "<<="}, {"INDEX", "[]"}, {"INDEX_SET", "[]="},
                {"CAST", CAST}};
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
            if (a.op() == BinaryOp.SHIFT) {
                // `<<=` is its own operator and has no fallback (§51).
                operatorCall(b, ct, "<<=", List.of(value), a.pos(), a);
                return target;
            }
            Type r = classOperator(b, ct, a.op(), value, a.pos(), a);
            if (!r.isError()) {
                requireAssignable(b, r, target, a.pos(), "compound assignment");
            }
            return target;
        }
        Binary computed = new Binary(a.pos(), a.op(), a.target(), a.value());
        b.written.put(computed, a.op().symbol + "=");
        Type r = checkBinary(b, computed);
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
                && (isSubclass(fc, tc) || isSubclass(tc, fc) || fc.cls().isInterface || tc.cls().isInterface));
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

    private Type checkCast(Body b, Cast c) {
        Type to = resolveType(c.type(), b.env, new Opts(false, false, false, null));
        Type from = checkExpr(b, c.operand(), to);
        if (to.isError() || from.isError()) {
            return Type.ERROR;
        }
        if (from instanceof ParamT) {
            return to; // (string)x on a T: checked for each type T is used with (§12)
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
