package io.github.thelinuxseal.seal.jvm;

import static java.lang.classfile.ClassFile.ACC_ABSTRACT;
import static java.lang.classfile.ClassFile.ACC_INTERFACE;
import static java.lang.classfile.ClassFile.ACC_PUBLIC;
import static java.lang.classfile.ClassFile.ACC_STATIC;
import static java.lang.classfile.ClassFile.ACC_SUPER;
import static java.lang.constant.ConstantDescs.CD_Object;
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
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.CVal;
import io.github.thelinuxseal.seal.check.CVal.BitV;
import io.github.thelinuxseal.seal.check.CVal.IntV;
import io.github.thelinuxseal.seal.check.CVal.TypeV;
import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.ClassInfo;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.Layouts;
import io.github.thelinuxseal.seal.check.Type;
import io.github.thelinuxseal.seal.check.Type.*;

/**
 * Generates JVM class files from a checked program (docs/JVM.md). Each Seal class becomes a JVM
 * class, one per instantiation (List$int), and each interface a JVM interface; the standard
 * library's are in {@link #LIBRARY_PACKAGE}. The root, Seal's Object, is an interface every class
 * implements, so that exceptions can extend Java's RuntimeException (§7). A string is a
 * java.lang.String and an array<T> a Java array; their methods are static methods taking them
 * first. As CodeGen does for the VM, it generates what the program reaches from main.
 *
 * <p>It works in two passes: first every method the program reaches is generated once, which
 * finds what it calls and the classes it uses and whether it can be generated; then each class is
 * written with all its members. Code it can't generate yet is reported at its line. A method a
 * class needs only so that its objects are complete (one no code calls) becomes, if it can't be
 * generated, one that stops the program, as on the VM.
 */
public final class JvmGen {

    /**
     * The generated classes by binary name ("com.example.Main"), the main class's binary name, and
     * the errors (when there are any, there are no classes).
     */
    public record Result(Map<String, byte[]> classes, String mainClass, List<Diagnostic> errors) {
    }

    /**
     * Where the standard library's packages are on the JVM (docs/JVM.md §8.3): seal.util is
     * io.github.thelinuxseal.seal.util. The built-in types in no package (string, array) are in
     * {@link #LIBRARY_PACKAGE}, with seal.lang.
     */
    public static final String LIBRARY_ROOT = "io.github.thelinuxseal.";

    /** seal.lang on the JVM, where the root Object and Exception are. */
    public static final String LIBRARY_PACKAGE = LIBRARY_ROOT + Checker.LANG;

    /** The runtime's classes, which compiled programs use and `seal build` copies beside them (§9). */
    public static final List<String> RUNTIME_CLASSES = List.of(
            "io.github.thelinuxseal.seal.runtime.Natives",
            "io.github.thelinuxseal.seal.runtime.Ints",
            "io.github.thelinuxseal.seal.runtime.Strings",
            "io.github.thelinuxseal.seal.runtime.FixedPoint",
            "io.github.thelinuxseal.seal.runtime.Block",
            "io.github.thelinuxseal.seal.runtime.Ptr",
            "io.github.thelinuxseal.seal.runtime.Exit",
            "io.github.thelinuxseal.seal.runtime.Trap",
            "io.github.thelinuxseal.seal.runtime.JavaValues");

    /** The method every main class gets that runs the program and returns its exit code. */
    public static final String RUN_METHOD = "$run";
    /** The method of the main class that initializes every static field, in Seal's order (§10). */
    static final String INIT_METHOD = "$init";

    static final String RUNTIME = "io.github.thelinuxseal.seal.runtime.";
    static final ClassDesc NATIVES = ClassDesc.of(RUNTIME + "Natives");
    static final ClassDesc INTS = ClassDesc.of(RUNTIME + "Ints");
    static final ClassDesc FIXED_POINT = ClassDesc.of(RUNTIME + "FixedPoint");
    static final ClassDesc TRAP = ClassDesc.of(RUNTIME + "Trap");
    static final ClassDesc CD_RuntimeException = ClassDesc.of("java.lang.RuntimeException");
    static final ClassDesc CD_MethodHandle = ClassDesc.of("java.lang.invoke.MethodHandle");
    static final ClassDesc BLOCK = ClassDesc.of(RUNTIME + "Block");
    static final ClassDesc PTR = ClassDesc.of(RUNTIME + "Ptr");
    /** The field of an object (or a class) holding its block of struct fields. */
    static final String VALUES = "$values";
    static final String STATICS = "$statics";

    /** Generates the classes of a program whose entry point is main() in {@code mainClass}. */
    public static Result generate(Checker checker, String mainClass) {
        return new JvmGen(checker).run(mainClass);
    }

    // ================================================================ state

    /**
     * One method to generate. Kinds: "method" (static when owner is null), "ctor" and "default-ctor"
     * (a class's constructors, or a string's or array's, which make a value), "init" (every class's
     * static fields, in order), "class-init" (one class's), "lambda" (extra is the Lambda; cls, owner,
     * member and binders are the method around it).
     */
    record FnKey(String kind, ClassInfo cls, ClassT owner, MemberInfo member, Map<String, CVal> binders, Object extra) {
        FnKey(String kind, ClassInfo cls, ClassT owner, MemberInfo member, Map<String, CVal> binders) {
            this(kind, cls, owner, member, binders, null);
        }
    }

    enum Invoke { STATIC, VIRTUAL, INTERFACE, SPECIAL }

    /** Where a method is on the JVM, and how it is called. */
    record MethodRef(ClassDesc owner, String name, MethodTypeDesc type, Invoke invoke) {
    }

    /** The most bits a block holds, as it is addressed by int offsets (§4.1). */
    static final long MAX_BLOCK_BITS = Integer.MAX_VALUE;

    /** A block's size or an offset in it, which must fit an int, as the VM checks its own. */
    static long blockBits(long bits, Pos at) {
        if (bits > MAX_BLOCK_BITS) {
            throw new Unsupported(at, "offset or size " + bits + " is too large: a block of values on the JVM holds at most "
                    + MAX_BLOCK_BITS + " bits");
        }
        return bits;
    }

    /** Thrown for code the JVM backend can't generate yet; reported as an error at its position. */
    static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Pos pos;

        Unsupported(Pos pos, String message) {
            super(message, null, false, false);
            this.pos = pos;
        }
    }

    record FieldSpec(String name, ClassDesc type, int flags) {
    }

    record AbstractSpec(String name, MethodTypeDesc type) {
    }

    /**
     * A method a class gets so that Java's code reaches its Seal methods (docs/JAVA.md §5): one with
     * a Java interface method's erased descriptor, calling the Seal method with its arguments cast
     * ("call"); Java's toString() calling the cast to string ("call"); hashCode() calling hash()
     * ("hashCode"); equals(Object) calling == on an object of its parameter's class ("equals").
     */
    record Bridge(String name, MethodTypeDesc type, MethodRef target, String kind) {
    }

    /** A JVM class being put together: everything it holds is known before it is written. */
    static final class JClass {
        final String name;
        final ClassDesc desc;
        ClassDesc superclass = CD_Object;
        final Set<ClassDesc> interfaces = new LinkedHashSet<>();
        boolean isInterface;
        boolean isAbstract;
        String source = "Seal";
        final Map<String, FieldSpec> fields = new LinkedHashMap<>();
        final List<FnKey> methods = new ArrayList<>();
        final List<AbstractSpec> abstracts = new ArrayList<>();
        final List<Bridge> bridges = new ArrayList<>();
        /** Name and descriptor of every method, so that two never coincide. */
        final Set<String> signatures = new HashSet<>();
        /** For a lambda's class: the fields its constructor fills, from what the lambda captures. */
        List<FieldSpec> captures;
        /** How big each object's block for its struct fields is, if it has any (§5). */
        long valuesBits;
        /** The class's static block for static fields of struct types, and where each is (§4.2). */
        long staticBits;
        Map<Field, Long> staticOffsets;

        JClass(String name) {
            this.name = name;
            this.desc = ClassDesc.of(name);
        }
    }

    final Checker ck;
    final Layouts layouts;
    /** How big each method's frame block is, as the first pass found (§4.2). */
    final Map<FnKey, Long> frameSizes = new HashMap<>();
    private final List<Diagnostic> errors = new ArrayList<>();
    final Map<FnKey, MethodRef> methods = new HashMap<>();
    private final Map<FnKey, JClass> homes = new HashMap<>();
    private final Deque<FnKey> work = new ArrayDeque<>();
    /** What each method calls directly, and virtually (a method of a class or interface, which overrides may answer). */
    private final Map<FnKey, Set<FnKey>> calls = new HashMap<>();
    private final Map<FnKey, Set<MethodRef>> virtualCalls = new HashMap<>();
    /** The method being generated, whose calls are being recorded; null outside any. */
    private FnKey current;
    /** The library classes whose static fields the program initializes: those it uses (with every program class). */
    final Set<ClassInfo> initialized = new LinkedHashSet<>();
    /** Methods that couldn't be generated, with their errors: reported if used, else stubbed. */
    private final Map<FnKey, List<Diagnostic>> failed = new LinkedHashMap<>();
    final Map<String, JClass> classes = new LinkedHashMap<>();
    private final Map<ClassT, JClass> instances = new HashMap<>();
    /** The lambdas' classes and what each is, by its "lambda" key. */
    final Map<FnKey, JClass> lambdaClasses = new HashMap<>();
    final Map<FnKey, Checker.LambdaInfo> lambdaInfos = new HashMap<>();
    /** False while methods are first generated, to find what they use; true while classes are written. */
    boolean writing;
    JClass mainClass;

    private JvmGen(Checker ck) {
        this.ck = ck;
        this.layouts = new Layouts(ck);
    }

    // ================================================================ driver

    private Result run(String mainName) {
        List<ClassInfo> named = ck.mainCandidates(mainName);
        if (named.size() > 1) {
            return failed(new Diagnostic("<program>", 1, 1, "several classes are named " + mainName + ": "
                    + named.stream().map(ClassInfo::qualifiedName).sorted().collect(Collectors.joining(", "))
                    + "; choose one with --main, as in --main " + named.get(0).qualifiedName()));
        }
        ClassInfo main = named.isEmpty() ? null : named.get(0);
        Checker.EntryPoint ep = main == null ? null : ck.entryPoint(main);
        if (ep == null) {
            return failed(new Diagnostic(main == null ? "<program>" : main.file, 1, 1,
                    "no entry point: expected `void main()` or `int main()` in class " + mainName));
        }
        Pos at = ep.method().member.pos();
        Type ret = ck.returnTypeOf(ep.owner(), ep.method(), Map.of());
        if (!(ret instanceof VoidT) && !(ret instanceof IntT)) {
            return failed(new Diagnostic(ep.declaring().file, at.line(), at.column(),
                    "main must return void or an integer, not " + ret));
        }
        String cannot = ep.isStatic() ? null : cannotCreate(main, ep);
        if (cannot != null) {
            return failed(new Diagnostic(ep.declaring().file, at.line(), at.column(), "main() is not static, so the program "
                    + "creates a " + mainName + " to run it, but " + cannot + "; make main() static"));
        }

        mainClass = staticClass(main);
        FnKey initKey = new FnKey("init", main, null, null, Map.of());
        FnKey root = new FnKey("root", main, null, null, Map.of()); // what $run calls
        MethodRef init;
        MethodRef entry;
        MethodRef create = null;
        MethodRef fini = null;
        current = root;
        try {
            for (ClassInfo c : ck.allClasses()) {
                if (!c.builtin) {
                    initialized.add(c);
                }
            }
            init = ref(initKey, at);
            boolean staticUniques = ck.allClasses().stream().filter(c -> !Checker.isJavaType(c)).anyMatch(c -> c.all.stream().anyMatch(m ->
                    m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()
                            && needsDestroy(ck.staticFieldType(c, f))));
            if (staticUniques) {
                fini = ref(new FnKey("fini", main, null, null, Map.of()), at);
            }
            if (ep.isStatic()) {
                entry = ref(new FnKey("method", ep.declaring(), null, ep.method(), Map.of()), at);
            } else if (!main.dynamic) {
                // An instance main() of a struct or unique runs on a value $start makes (§56.1).
                entry = ref(new FnKey("start", main, ep.owner(), ep.method(), Map.of()), at);
            } else {
                ClassT mainType = new ClassT(main, List.of(), List.of(), 0);
                create = constructor(mainType, noArgConstructor(main), Map.of(), at);
                entry = virtualRef(ep.owner(), ep.method(), at);
                calledVirtually(entry);
            }
        } catch (Unsupported u) {
            Pos p = u.pos == null ? at : u.pos;
            return failed(new Diagnostic(main.file, p.line(), p.column(), u.getMessage()));
        }
        current = null;

        // Every method reached, then the static fields again, until the library classes they cover are all
        // those the program uses.
        while (true) {
            drain(at);
            Set<ClassInfo> before = new LinkedHashSet<>(initialized);
            for (ClassInfo c : ck.allClasses()) {
                if (c.builtin && classes.containsKey(binaryName(c)) && hasStatics(c)) {
                    initialized.add(c);
                }
            }
            if (initialized.equals(before)) {
                break;
            }
            failed.remove(initKey);
            work.add(initKey);
        }
        Set<FnKey> used = used(root);
        for (Map.Entry<FnKey, List<Diagnostic>> e : failed.entrySet()) {
            if (used.contains(e.getKey())) {
                errors.addAll(e.getValue());
            }
        }
        if (!errors.isEmpty()) {
            return new Result(Map.of(), null, List.copyOf(new LinkedHashSet<>(errors)));
        }

        writing = true;
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy()));
        Map<String, byte[]> out = new LinkedHashMap<>();
        MethodRef theEntry = entry;
        MethodRef theCreate = create;
        MethodRef theFini = fini;
        for (JClass jc : new ArrayList<>(classes.values())) {
            out.put(jc.name, cf.build(jc.desc, cb -> {
                cb.withVersion(ClassFile.JAVA_25_VERSION, 0);
                cb.withFlags(jc.isInterface ? ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT
                        : ACC_PUBLIC | ACC_SUPER | (jc.isAbstract ? ACC_ABSTRACT : 0));
                cb.withSuperclass(jc.superclass);
                cb.withInterfaceSymbols(new ArrayList<>(jc.interfaces));
                cb.with(SourceFileAttribute.of(jc.source));
                for (FieldSpec f : jc.fields.values()) {
                    cb.withField(f.name(), f.type(), f.flags());
                }
                for (AbstractSpec a : jc.abstracts) {
                    cb.withMethod(a.name(), a.type(), ACC_PUBLIC | ACC_ABSTRACT, mb -> { });
                }
                for (Bridge b : jc.bridges) {
                    bridge(cb, b);
                }
                if (jc.captures != null) {
                    lambdaConstructor(cb, jc);
                }
                for (FnKey k : jc.methods) {
                    generate(cb, k, new ArrayList<>());
                }
                if (jc == mainClass) {
                    entryPoints(cb, init, theEntry, theCreate, theFini, ret);
                }
            }));
        }
        return new Result(out, mainClass.name, List.of());
    }

    /** Generates every method reached and not yet generated, once, recording what it calls and whether it can be. */
    private void drain(Pos at) {
        while (!work.isEmpty()) {
            FnKey k = work.pop();
            List<Diagnostic> found = new ArrayList<>();
            calls.remove(k);
            virtualCalls.remove(k);
            current = k;
            try {
                ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS).build(ClassDesc.of("Scratch"),
                        cb -> generate(cb, k, found));
            } catch (Unsupported u) {
                if (u.getMessage() != null) {
                    Pos p = u.pos == null ? at : u.pos;
                    found.add(new Diagnostic(fileOf(k), p.line(), p.column(), u.getMessage()));
                }
                if (found.isEmpty()) {
                    found.add(new Diagnostic(fileOf(k), at.line(), at.column(), describe(k) + " can't be generated"));
                }
            }
            current = null;
            if (!found.isEmpty()) {
                failed.put(k, found);
            }
        }
    }

    private static boolean hasStatics(ClassInfo c) {
        return c.all.stream().anyMatch(m -> m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst());
    }

    /**
     * The methods the program can run, from what $run calls: through direct calls, and through
     * virtual calls to every method of the class called, or of a class below it, that overrides it.
     */
    private Set<FnKey> used(FnKey root) {
        Set<FnKey> used = new HashSet<>();
        Deque<FnKey> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            FnKey u = todo.pop();
            if (!used.add(u)) {
                continue;
            }
            todo.addAll(calls.getOrDefault(u, Set.of()));
            for (MethodRef v : virtualCalls.getOrDefault(u, Set.of())) {
                for (Map.Entry<FnKey, MethodRef> m : methods.entrySet()) {
                    MethodRef r = m.getValue();
                    if (r.name().equals(v.name()) && r.type().equals(v.type()) && below(homes.get(m.getKey()), v.owner())) {
                        todo.add(m.getKey());
                    }
                }
            }
        }
        return used;
    }

    /** Whether a class is, extends or implements the one named. */
    private boolean below(JClass jc, ClassDesc ancestor) {
        if (jc == null) {
            return false;
        }
        if (jc.desc.equals(ancestor) || jc.interfaces.contains(ancestor)) {
            return true;
        }
        for (ClassDesc i : jc.interfaces) {
            JClass ij = classes.get(i.packageName().isEmpty() ? i.displayName() : i.packageName() + "." + i.displayName());
            if (ij != null && ij != jc && below(ij, ancestor)) {
                return true;
            }
        }
        JClass parent = classes.get(jc.superclass.packageName().isEmpty() ? jc.superclass.displayName()
                : jc.superclass.packageName() + "." + jc.superclass.displayName());
        return parent != null && parent != jc && below(parent, ancestor);
    }

    /** Records a virtual or interface call by the method being generated. */
    void calledVirtually(MethodRef r) {
        virtualCalls.computeIfAbsent(current, x -> new HashSet<>()).add(r);
    }

    private Result failed(Diagnostic d) {
        return new Result(Map.of(), null, List.of(d));
    }

    private String fileOf(FnKey k) {
        return k.cls().file;
    }

    /** What stops a program from creating its main class for an instance main(), as CodeGen says it. */
    private static String cannotCreate(ClassInfo c, Checker.EntryPoint ep) {
        if (c.kind == Kind.ENUM) {
            return c.name + " is an enum, whose only values are its constants";
        }
        if (c.isAbstract) {
            return c.name + " is abstract";
        }
        if (ep.owner() == null) {
            return c.name + " has type parameters or shapes";
        }
        if (!c.constructors.isEmpty()
                && c.constructors.stream().noneMatch(m -> ((Constructor) m.member).params().isEmpty())) {
            return c.name + " has no no-argument constructor";
        }
        return null;
    }

    private static MemberInfo noArgConstructor(ClassInfo c) {
        for (MemberInfo m : c.constructors) {
            if (((Constructor) m.member).params().isEmpty()) {
                return m;
            }
        }
        return null;
    }

    /**
     * {@code static int $run()}, which initializes the static fields, calls main and gives the exit
     * code (0 for a void main), and {@code public static void main(String[])}, so that {@code java
     * Main} runs the program (§10).
     */
    private void entryPoints(ClassBuilder cb, MethodRef init, MethodRef main, MethodRef create, MethodRef fini, Type ret) {
        cb.withMethodBody(RUN_METHOD, MethodTypeDesc.of(CD_int), ACC_PUBLIC | ACC_STATIC, c -> {
            invoke(c, init);
            if (create != null) {
                // An instance main() runs on a new main object, made with its no-argument constructor.
                c.new_(create.owner());
                c.dup();
                invoke(c, create);
            }
            invoke(c, main);
            if (ret instanceof VoidT) {
                c.iconst_0();
            } else if (kind(ret) == TypeKind.LONG) {
                c.l2i(); // as the VM's runner takes the low 32 bits of main's result
            }
            if (fini != null) {
                invoke(c, fini); // uniques in static fields, destroyed as main returns (§26.1)
            }
            c.ireturn();
        });
        MethodTypeDesc mainType = MethodTypeDesc.of(CD_void, CD_String.arrayType());
        cb.withMethodBody("main", mainType, ACC_PUBLIC | ACC_STATIC, c -> {
            c.loadConstant(MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, mainClass.desc, RUN_METHOD,
                    MethodTypeDesc.of(CD_int)));
            c.invokestatic(NATIVES, "start", MethodTypeDesc.of(CD_void, CD_MethodHandle));
            c.return_();
        });
    }

    /** A bridge (docs/JAVA.md §5): the Seal method it reaches, called with this and its arguments. */
    private static void bridge(ClassBuilder cb, Bridge b) {
        cb.withMethodBody(b.name(), b.type(), ACC_PUBLIC, c -> {
            if (b.kind().equals("equals")) {
                // equals(Object o): o is one of =='s parameter's class, and == says they are equal.
                ClassDesc other = b.target().type().parameterType(0);
                java.lang.classfile.Label no = c.newLabel();
                c.aload(1);
                c.instanceOf(other);
                c.ifeq(no);
                c.aload(0);
                c.aload(1);
                c.checkcast(other);
                invoke(c, b.target());
                c.ireturn();
                c.labelBinding(no);
                c.iconst_0();
                c.ireturn();
                return;
            }
            c.aload(0);
            for (int i = 0; i < b.type().parameterCount(); i++) {
                ClassDesc given = b.type().parameterType(i);
                TypeKind k = TypeKind.from(given);
                c.loadLocal(k.asLoadable(), c.parameterSlot(i));
                ClassDesc wanted = b.target().type().parameterType(i);
                if (k == TypeKind.REFERENCE && !wanted.equals(given)) {
                    c.checkcast(wanted);
                }
            }
            invoke(c, b.target());
            if (b.kind().equals("hashCode")) {
                c.l2i(); // Seal's hash is 64 bits; Java's hashCode its low 32
            }
            c.return_(TypeKind.from(b.type().returnType()).asLoadable());
        });
    }

    /**
     * The bridges a Seal class's objects need for Java (docs/JAVA.md §5): a Java interface method
     * whose erased descriptor isn't its Seal method's; and toString(), hashCode() and equals(Object)
     * from the class's own cast to string, hash() and ==, so that Java prints Seal objects as Seal
     * does and its HashMap finds Seal keys.
     */
    private void bridges(JClass jc, ClassT t) {
        for (Checker.JavaImpl ji : ck.javaImplementations(t)) {
            MethodRef target = virtualRef(ji.implOwner(), ji.impl(), ji.impl().member.pos());
            MethodTypeDesc java = MethodTypeDesc.ofDescriptor(ji.java().descriptor());
            if (!(target.name().equals(ji.java().name()) && target.type().equals(java))
                    && jc.signatures.add(ji.java().name() + java.descriptorString())) {
                jc.bridges.add(new Bridge(ji.java().name(), java, target, "call"));
            }
        }
        for (MemberInfo m : t.cls().all) {
            if (!(m.member instanceof Method md) || md.modifiers().isStatic() || !m.existsIn(t.header()) || hasBinders(md)) {
                continue;
            }
            try {
                if (ck.isStringCast(t, m)) {
                    objectBridge(jc, "toString", MethodTypeDesc.of(CD_String), virtualRef(t, m, md.pos()), "call");
                } else if (md.name().equals("hash") && md.params().isEmpty()
                        && ck.returnTypeOf(t, m, Map.of()) instanceof IntT it && width(it) == 64) {
                    objectBridge(jc, "hashCode", MethodTypeDesc.of(CD_int), virtualRef(t, m, md.pos()), "hashCode");
                } else if (Checker.isEqualsOperator(md) && ck.paramTypes(t, m, Map.of()).get(0) instanceof HandleT
                        && ck.returnTypeOf(t, m, Map.of()) instanceof BitT) {
                    objectBridge(jc, "equals", MethodTypeDesc.of(CD_boolean, CD_Object), virtualRef(t, m, md.pos()), "equals");
                }
            } catch (Unsupported u) {
                // A method not on the JVM yet: Java gets Object's own.
            }
        }
    }

    private static void objectBridge(JClass jc, String name, MethodTypeDesc type, MethodRef target, String kind) {
        if (!(target.name().equals(name) && target.type().equals(type)) && jc.signatures.add(name + type.descriptorString())) {
            jc.bridges.add(new Bridge(name, type, target, kind));
        }
    }

    /** A lambda class's constructor: it stores what the lambda captures, in order (§30.3). */
    private void lambdaConstructor(ClassBuilder cb, JClass jc) {
        MethodTypeDesc type = MethodTypeDesc.of(CD_void, jc.captures.stream().map(FieldSpec::type).toArray(ClassDesc[]::new));
        cb.withMethodBody("<init>", type, ACC_PUBLIC, c -> {
            c.aload(0);
            c.invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void));
            for (int i = 0; i < jc.captures.size(); i++) {
                FieldSpec f = jc.captures.get(i);
                c.aload(0);
                c.loadLocal(TypeKind.from(f.type()).asLoadable(), c.parameterSlot(i));
                c.putfield(jc.desc, f.name(), f.type());
            }
            c.return_();
        });
    }

    static void invoke(CodeBuilder c, MethodRef r) {
        switch (r.invoke()) {
            case STATIC -> c.invokestatic(r.owner(), r.name(), r.type(), false);
            case VIRTUAL -> c.invokevirtual(r.owner(), r.name(), r.type());
            case INTERFACE -> c.invokeinterface(r.owner(), r.name(), r.type());
            case SPECIAL -> c.invokespecial(r.owner(), r.name(), r.type());
        }
    }

    /**
     * The classes this program defines, for the stack maps the class-file API computes; then Java's,
     * from their class files (docs/JAVA.md §6), the class path's among them.
     */
    private ClassHierarchyResolver hierarchy() {
        Set<ClassDesc> interfaces = new HashSet<>();
        Map<ClassDesc, ClassDesc> supers = new HashMap<>();
        for (JClass jc : classes.values()) {
            if (jc.isInterface) {
                interfaces.add(jc.desc);
            } else {
                supers.put(jc.desc, jc.superclass);
            }
        }
        return ClassHierarchyResolver.of(interfaces, supers)
                .orElse(ClassHierarchyResolver.ofResourceParsing(ck.javaClasses()::open))
                .orElse(ClassHierarchyResolver.defaultResolver());
    }

    // ================================================================ methods

    /** Generates one method into the class being built (or, in the first pass, a scratch one). */
    private void generate(ClassBuilder cb, FnKey k, List<Diagnostic> found) {
        MethodRef r = methods.get(k);
        int flags = ACC_PUBLIC | (r.invoke() == Invoke.STATIC ? ACC_STATIC : 0);
        if (writing && failed.containsKey(k)) {
            // Reached only to complete a class, and not available for these types: stops the program if run.
            cb.withMethodBody(r.name(), r.type(), flags, c -> {
                if (r.name().equals("<init>")) {
                    c.aload(0);
                    c.invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void));
                }
                c.new_(TRAP);
                c.dup();
                c.loadConstant(describe(k) + " is not available on the JVM for these types");
                c.invokespecial(TRAP, "<init>", MethodTypeDesc.of(CD_void, CD_String));
                c.athrow();
            });
            return;
        }
        cb.withMethodBody(r.name(), r.type(), flags, c -> new JvmCode(this, c, k, r, found).generate());
    }

    String describe(FnKey k) {
        String owner = k.owner() != null ? k.owner().toString() : k.cls().qualifiedName();
        return switch (k.kind()) {
            case "method" -> owner + "." + ((Method) k.member().member).name();
            case "ctor", "default-ctor" -> owner + ".new";
            case "init" -> "the static fields";
            case "class-init" -> "the static fields of " + owner;
            case "fini" -> "the unique static fields' destruction";
            case "start" -> owner + ".main";
            case "destroy", "dtor" -> owner + ".delete";
            case "lambda" -> "a lambda in " + owner;
            default -> owner + "." + k.kind();
        };
    }

    /** The method a call reaches: generated once, when first reached. */
    MethodRef ref(FnKey k, Pos at) {
        if (current != null) {
            calls.computeIfAbsent(current, x -> new HashSet<>()).add(k);
        }
        return reach(k, at);
    }

    private MethodRef reach(FnKey k, Pos at) {
        MethodRef known = methods.get(k);
        if (known != null) {
            return known;
        }
        if (writing) {
            throw new IllegalStateException("a method was first reached while writing classes: " + describe(k));
        }
        MethodRef r;
        JClass home;
        switch (k.kind()) {
            case "init" -> {
                home = mainClass;
                r = new MethodRef(home.desc, unique(home, INIT_METHOD, MethodTypeDesc.of(CD_void)),
                        MethodTypeDesc.of(CD_void), Invoke.STATIC);
            }
            case "class-init" -> {
                // One class's static fields, in its own class, so that its lines name its own file.
                home = staticClass(k.cls());
                r = new MethodRef(home.desc, unique(home, INIT_METHOD, MethodTypeDesc.of(CD_void)),
                        MethodTypeDesc.of(CD_void), Invoke.STATIC);
            }
            case "fini" -> {
                home = mainClass;
                r = new MethodRef(home.desc, unique(home, "$fini", MethodTypeDesc.of(CD_void)),
                        MethodTypeDesc.of(CD_void), Invoke.STATIC);
            }
            case "start" -> {
                home = mainClass;
                MethodTypeDesc type = methodType(List.of(), ck.returnTypeOf(k.owner(), k.member(), Map.of()), List.of(), at);
                r = new MethodRef(home.desc, unique(home, "$start", type), type, Invoke.STATIC);
            }
            case "destroy", "dtor" -> {
                // Destroying a unique (§16): "destroy" is the whole of it, "dtor" its own destructor's body.
                home = structClass(k.owner());
                MethodTypeDesc type = MethodTypeDesc.of(CD_void, BLOCK, CD_int);
                r = new MethodRef(home.desc, unique(home, k.kind().equals("dtor") ? "$delete" : "$destroy", type), type,
                        Invoke.STATIC);
            }
            case "ctor", "default-ctor" -> {
                List<Type> params = k.member() == null ? List.of() : ck.paramTypes(k.owner(), k.member(), k.binders());
                if (isValue(k.owner())) {
                    // A string's or array's constructor makes the value: a static method returning it.
                    home = valueClass(k.owner());
                    MethodTypeDesc type = methodType(List.of(), k.owner(), params, at);
                    r = new MethodRef(home.desc, unique(home, "new" + suffix(params, k.binders()), type), type,
                            Invoke.STATIC);
                } else if (isAggregate(k.owner())) {
                    // A struct's or unique's: a static method making the value in the place given first.
                    home = structClass(k.owner());
                    MethodTypeDesc type = methodType(List.of(BLOCK, CD_int), Type.VOID, params, at);
                    r = new MethodRef(home.desc, unique(home, "new" + suffix(params, k.binders()), type), type,
                            Invoke.STATIC);
                } else {
                    home = instance(k.owner());
                    MethodTypeDesc type = methodType(List.of(), Type.VOID, params, at);
                    if (!home.signatures.add("<init>" + type.descriptorString())) {
                        throw new Unsupported(at, "two constructors of " + k.owner() + " with the same JVM parameters ("
                                + type.displayDescriptor() + ") are not available on the JVM yet");
                    }
                    r = new MethodRef(home.desc, "<init>", type, Invoke.SPECIAL);
                }
            }
            case "lambda" -> {
                home = lambdaClasses.get(k);
                Checker.LambdaInfo li = lambdaInfos.get(k);
                if (li.iface().cls().java != null) {
                    // A Java interface's method, with its erased descriptor (docs/JAVA.md §5).
                    r = new MethodRef(home.desc, ((Method) li.method().member).name(),
                            MethodTypeDesc.ofDescriptor(ck.javaMember(li.method().member).descriptor()), Invoke.VIRTUAL);
                } else {
                    MethodRef iface = virtualRef(li.iface(), li.method(), at);
                    r = new MethodRef(home.desc, iface.name(), iface.type(), Invoke.VIRTUAL);
                }
            }
            default -> {
                Method md = (Method) k.member().member;
                List<Type> params = ck.paramTypes(k.owner(), k.member(), k.binders());
                Type ret = ck.returnTypeOf(k.owner(), k.member(), k.binders());
                if (k.owner() == null) {
                    home = staticClass(k.cls());
                    MethodTypeDesc type = methodType(List.of(), ret, params, at);
                    r = new MethodRef(home.desc, unique(home, md.name() + suffix(params, k.binders()), type), type,
                            Invoke.STATIC);
                } else if (isValue(k.owner())) {
                    // A string's or array's method: static, with the value first. On a string, one that
                    // changes it returns the changed string (§8.2).
                    home = valueClass(k.owner());
                    Type r2 = ret instanceof VoidT && replacesReceiver(k.owner()) ? k.owner() : ret;
                    MethodTypeDesc type = methodType(List.of(desc(k.owner(), at)), r2, params, at);
                    r = new MethodRef(home.desc, unique(home, md.name() + suffix(params, k.binders()), type), type,
                            Invoke.STATIC);
                } else if (isAggregate(k.owner())) {
                    // A struct's or unique's method: static, with its place first (§4.3).
                    home = structClass(k.owner());
                    MethodTypeDesc type = methodType(List.of(BLOCK, CD_int), ret, params, at);
                    r = new MethodRef(home.desc, unique(home, md.name() + suffix(params, k.binders()), type), type,
                            Invoke.STATIC);
                } else {
                    home = instance(k.owner());
                    if (hasBinders(md)) {
                        MethodTypeDesc type = methodType(List.of(), ret, params, at);
                        r = new MethodRef(home.desc, unique(home, accessorName(md.name()) + suffix(params, k.binders()),
                                type), type, home.isInterface ? Invoke.INTERFACE : Invoke.VIRTUAL);
                    } else {
                        MethodRef v = virtualRef(k.owner(), k.member(), at);
                        if (!home.signatures.add(v.name() + v.type().descriptorString())) {
                            throw new Unsupported(at, "two methods of " + k.owner() + " named " + v.name()
                                    + " with the same JVM parameters are not available on the JVM yet");
                        }
                        r = new MethodRef(home.desc, v.name(), v.type(), home.isInterface ? Invoke.INTERFACE : Invoke.VIRTUAL);
                    }
                }
            }
        }
        methods.put(k, r);
        homes.put(k, home);
        home.methods.add(k);
        work.add(k);
        return r;
    }

    /** A name for a method in a class that no other method there has with the same descriptor. */
    private static String unique(JClass home, String name, MethodTypeDesc type) {
        if (home.signatures.add(name + type.descriptorString())) {
            return name;
        }
        for (int n = 2; ; n++) {
            if (home.signatures.add(name + "$" + n + type.descriptorString())) {
                return name + "$" + n;
            }
        }
    }

    /**
     * For a method instantiated for binders (§5.2), what tells its instantiations apart: its
     * parameter types, and the types its type parameters stand for ($int8, $string$$Point); "" for
     * one without binders.
     */
    String suffix(List<Type> params, Map<String, CVal> binders) {
        if (binders.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Type p : params) {
            sb.append('$').append(typeText(p));
        }
        List<String> types = binders.entrySet().stream().filter(e -> e.getValue() instanceof TypeV)
                .sorted(Map.Entry.comparingByKey()).map(e -> typeText(((TypeV) e.getValue()).type())).toList();
        if (!types.isEmpty()) {
            sb.append("$$").append(String.join("$", types));
        }
        return sb.length() == 0 ? "$" : sb.toString();
    }

    /** A synthesized accessor's JVM name: "get name" is getName, as Java would call it (§30.4). */
    static String accessorName(String name) {
        int space = name.indexOf(' ');
        if (space < 0) {
            return name;
        }
        String field = name.substring(space + 1);
        return name.substring(0, space) + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    /** Methods with value binders or type parameters get a copy per use, so they aren't virtual. */
    static boolean hasBinders(Method md) {
        if (!md.typeParams().isEmpty()) {
            return true;
        }
        for (Param p : md.params()) {
            if (containsBinder(p.type())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBinder(TypeRef t) {
        if (t.valueArgs() != null && t.valueArgs().items().stream().anyMatch(i -> i instanceof Binder)) {
            return true;
        }
        return t.typeArgs().stream().anyMatch(JvmGen::containsBinder);
    }

    /**
     * An instance method as virtual calls name it: the name and descriptor of the slot's first
     * declaration, the topmost class or interface that declares it. If that class sees another
     * method with the same name and descriptor (f(int<<8>>) and f(int)), the slot's name gets its
     * Seal parameter types, so that every override has the same name.
     */
    MethodRef virtualRef(ClassT owner, MemberInfo m, Pos at) {
        Method md = (Method) m.member;
        List<Type> params = ck.paramTypes(owner, m, Map.of());
        ClassT root = owner;
        MemberInfo rootMember = m;
        for (ClassT c = ck.parentType(owner); c != null; c = ck.parentType(c)) {
            MemberInfo same = declared(c, md.name(), params);
            if (same != null) {
                root = c;
                rootMember = same;
            }
        }
        for (ClassT i : ck.allInterfacesOf(root)) {
            MemberInfo same = declared(i, md.name(), params);
            if (same != null) {
                root = i;
                rootMember = same;
                break;
            }
        }
        Type ret = ck.returnTypeOf(root, rootMember, Map.of());
        MethodTypeDesc type = methodType(List.of(), ret, params, at);
        String name = accessorName(md.name());
        if (collides(root, rootMember, name, type)) {
            name += "$" + params.stream().map(this::typeText).collect(Collectors.joining("$"));
        }
        JClass home = instance(owner);
        return new MethodRef(home.desc, name, type, home.isInterface ? Invoke.INTERFACE : Invoke.VIRTUAL);
    }

    /** The instance method of this name and these parameter types that the class itself declares, or null. */
    private MemberInfo declared(ClassT c, String name, List<Type> params) {
        for (MemberInfo m : c.cls().members.getOrDefault(name, List.of())) {
            if (m.member instanceof Method md && !md.modifiers().isStatic() && m.existsIn(c.header()) && !hasBinders(md)
                    && ck.paramTypes(c, m, Map.of()).equals(params)) {
                return m;
            }
        }
        return null;
    }

    /** Whether a class, with what it inherits, has another method that this one's JVM name and descriptor would hit. */
    private boolean collides(ClassT root, MemberInfo m, String name, MethodTypeDesc type) {
        List<Type> mine = ck.paramTypes(root, m, Map.of());
        List<ClassT> seen = new ArrayList<>();
        for (ClassT c = root; c != null; c = ck.parentType(c)) {
            seen.add(c);
        }
        seen.addAll(ck.allInterfacesOf(root));
        for (ClassT c : seen) {
            for (MemberInfo other : c.cls().all) {
                if (other == m || !(other.member instanceof Method md) || md.modifiers().isStatic()
                        || !other.existsIn(c.header()) || hasBinders(md) || !accessorName(md.name()).equals(name)) {
                    continue;
                }
                List<Type> theirs = ck.paramTypes(c, other, Map.of());
                if (theirs.equals(mine)) {
                    continue; // the same slot, overridden
                }
                try {
                    if (methodType(List.of(), Type.VOID, theirs, null).parameterList().equals(
                            methodType(List.of(), Type.VOID, mine, null).parameterList())) {
                        return true;
                    }
                } catch (Unsupported u) {
                    // Not on the JVM yet; it can't collide.
                }
            }
        }
        return false;
    }

    /** The constructor `new` or `super(...)` calls: a declared one, or the one a class without any gets. */
    MethodRef constructor(ClassT t, MemberInfo ctor, Map<String, CVal> binders, Pos at) {
        if (t.cls().isRoot()) {
            return new MethodRef(plainObject().desc, "<init>", MethodTypeDesc.of(CD_void), Invoke.SPECIAL);
        }
        if (ctor == null) {
            return ref(new FnKey("default-ctor", t.cls(), t, null, Map.of()), at);
        }
        return ref(new FnKey("ctor", t.cls(), t, ctor, binders), at);
    }

    // ================================================================ classes

    /** A class's JVM binary name: as in Seal, with nested types after a $; the library's in its own packages. */
    static String binaryName(ClassInfo c) {
        if (c.java != null) {
            return c.java.name(); // one of Java's classes, which the program uses and doesn't define
        }
        String name = c.name.replace('.', '$');
        if (c.builtin) {
            return (c.pkg.isEmpty() ? LIBRARY_PACKAGE : LIBRARY_ROOT + c.pkg) + "." + name;
        }
        return c.pkg.isEmpty() ? name : c.pkg + "." + name;
    }

    /** Whether a JVM binary name is a class of the standard library's. */
    public static boolean isLibrary(String binaryName) {
        return binaryName.startsWith(LIBRARY_ROOT + "seal.");
    }

    /**
     * The Seal name of a generated class from its binary name, as a program with no imports writes
     * it: IllegalStateException (seal.lang's), seal.io.IOException, com.example.Main.Inner.
     */
    public static String sealName(String binaryName) {
        String name = binaryName.startsWith(LIBRARY_PACKAGE + ".") ? binaryName.substring(LIBRARY_PACKAGE.length() + 1)
                : isLibrary(binaryName) ? binaryName.substring(LIBRARY_ROOT.length()) : binaryName;
        return name.replace('$', '.');
    }

    /** The name of an instantiation: List$int, Matrix$double$3$3 (docs/JVM.md §5.1). */
    String instName(ClassT t) {
        StringBuilder sb = new StringBuilder(binaryName(t.cls()));
        for (Type a : t.typeArgs()) {
            sb.append('$').append(typeText(a));
        }
        for (CVal v : t.valueArgs()) {
            sb.append('$').append(valueText(v));
        }
        return sb.toString();
    }

    /** A type as names show it: int, uint8, char, string, Point, List_3cint_3e. */
    String typeText(Type t) {
        return switch (t) {
            case IntT it when it.isChar() -> "char";
            case IntT it -> (it.signed() ? "int" : "uint") + (width(it) == 32 ? "" : String.valueOf(width(it)));
            case FixedT f -> (f.signed() ? "fixed" : "ufixed") + valueText(f.intBits()) + "_" + valueText(f.fracBits());
            case BitT b -> "bit";
            case FloatT f -> "float";
            case DoubleT d -> "double";
            case HandleT h -> typeText(h.target());
            case AddrT a -> "addr_" + typeText(a.target());
            case ClassT ct when ct.typeArgs().isEmpty() && ct.valueArgs().isEmpty() -> ct.cls().name.replace('.', '_');
            default -> sanitize(t.toString());
        };
    }

    /** A compile-time value in a name: 8, n1 for -1 (docs/JVM.md §5.2). */
    static String valueText(CVal v) {
        if (v instanceof IntV i) {
            return i.value().signum() < 0 ? "n" + i.value().negate() : i.value().toString();
        }
        if (v instanceof BitV b) {
            return String.valueOf(b.value());
        }
        return sanitize(v.toString());
    }

    static String sanitize(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : s.toCharArray()) {
            out.append(Character.isLetterOrDigit(ch) ? String.valueOf(ch) : "_" + Integer.toHexString(ch));
        }
        return out.toString();
    }

    private JClass jclass(String name, String file) {
        JClass jc = classes.get(name);
        if (jc == null) {
            if (writing) {
                throw new IllegalStateException("a class was first reached while writing classes: " + name);
            }
            jc = new JClass(name);
            jc.source = Path.of(file).getFileName().toString();
            classes.put(name, jc);
        }
        return jc;
    }

    /** The class holding a type's static members (and, for a type with no parameters, its instances' too). */
    JClass staticClass(ClassInfo c) {
        return jclass(binaryName(c), c.file);
    }

    /** The class of a string's or array's instantiation, which holds its methods as static ones. */
    JClass valueClass(ClassT t) {
        return jclass(instName(t), t.cls().file);
    }

    /**
     * The JVM class (or interface) of a class's instantiation, made complete when first needed: its
     * parent and interfaces, fields and abstract methods, and every method its objects can be asked
     * to run, so that a call through a parent or an interface finds it.
     */
    JClass instance(ClassT t) {
        JClass done = instances.get(t);
        if (done != null) {
            return done;
        }
        ClassInfo c = t.cls();
        if (Checker.isJavaType(c)) {
            // One of Java's classes: named by the program's code, never generated (docs/JAVA.md §6).
            JClass jc = new JClass(c.java != null ? c.java.name() : "java.lang.Object");
            jc.isInterface = c.isInterface;
            instances.put(t, jc);
            return jc;
        }
        if (!c.dynamic) {
            throw new Unsupported(null, "values of type " + t + " are not available on the JVM yet");
        }
        JClass jc = jclass(instName(t), c.file);
        instances.put(t, jc);
        jc.isInterface = c.isInterface || c.isRoot();
        jc.isAbstract = c.isAbstract;
        ClassT parent = ck.parentType(t);
        if (jc.isInterface) {
            jc.superclass = CD_Object;
        } else if (c.builtin && c.name.equals("Exception")) {
            jc.superclass = CD_RuntimeException; // Seal's exceptions are Java's (§7)
            jc.interfaces.add(instance(root()).desc);
        } else if (parent == null || parent.cls().isRoot()) {
            jc.superclass = CD_Object;
            jc.interfaces.add(instance(root()).desc);
        } else {
            jc.superclass = instance(parent).desc;
            if (Checker.isJavaType(parent.cls())) {
                jc.interfaces.add(instance(root()).desc); // a Seal class extending a Java one is a Seal object too (docs/JAVA.md §5)
            }
        }
        for (ClassT i : ck.allInterfacesOf(t)) {
            jc.interfaces.add(instance(i).desc);
        }
        if (!jc.isInterface) {
            for (MemberInfo m : c.storedFields(t.header())) {
                Field f = (Field) m.member;
                Type ft = ck.fieldTypeOf(t, m);
                if (isAggregate(ft)) {
                    // Struct fields of an object live in its block, at the offsets the layout gives (§5).
                    jc.valuesBits = blockBits(Math.max(jc.valuesBits, layouts.of(t).offsets().get(m) + Math.max(1, layouts.sizeOf(ft))),
                            f.pos());
                    continue;
                }
                try {
                    jc.fields.put(f.name(), new FieldSpec(f.name(), desc(ft, f.pos()), ACC_PUBLIC));
                } catch (Unsupported u) {
                    // A field of a type that isn't on the JVM yet: code that uses it says so.
                }
            }
            if (jc.valuesBits > 0) {
                jc.fields.put(VALUES, new FieldSpec(VALUES, BLOCK, ACC_PUBLIC));
            }
        }
        for (MemberInfo m : c.all) {
            if (!(m.member instanceof Method md) || md.modifiers().isStatic() || !m.existsIn(t.header())
                    || Checker.isNative(md) || hasBinders(md)) {
                continue;
            }
            try {
                if (md.body() == null) {
                    MethodRef v = virtualRef(t, m, md.pos());
                    if (jc.signatures.add(v.name() + v.type().descriptorString())) {
                        jc.abstracts.add(new AbstractSpec(v.name(), v.type()));
                    }
                } else {
                    reach(new FnKey("method", c, t, m, Map.of()), md.pos());
                }
            } catch (Unsupported u) {
                // Its signature uses what isn't on the JVM yet, so no code here can call it.
            }
        }
        if (!jc.isInterface && !c.isRoot()) {
            bridges(jc, t);
        }
        return jc;
    }

    ClassT root() {
        return new ClassT(ck.classNamed("Object"), List.of(), List.of(), 0);
    }

    /**
     * What `new Object()` makes: Seal's Object is an interface on the JVM, so a plain object is
     * one of this class, which implements it and adds nothing.
     */
    JClass plainObject() {
        JClass root = instance(root());
        JClass jc = classes.get(root.name + "$Plain");
        if (jc == null) {
            jc = jclass(root.name + "$Plain", ck.classNamed("Object").file);
            jc.interfaces.add(root.desc);
            jc.captures = List.of(); // its constructor only calls Object's, as a lambda's with nothing captured
        }
        return jc;
    }

    /** The class that declares a member, as an ancestor of (or the same as) {@code t}. */
    ClassT declaringType(ClassT t, MemberInfo m) {
        ClassInfo declaring = ck.declaringClassOf(m);
        for (ClassT c = t; c != null; c = ck.parentType(c)) {
            if (c.cls() == declaring) {
                return c;
            }
        }
        for (ClassT i : ck.allInterfacesOf(t)) {
            if (i.cls() == declaring) {
                return i;
            }
        }
        if (declaring.isRoot()) {
            return root();
        }
        return t;
    }

    /** The class of a struct's or unique's instantiation, which holds its methods as static ones (§4.3). */
    JClass structClass(ClassT t) {
        return jclass(instName(t), t.cls().file);
    }

    /**
     * Where a class's static field of a struct type is in the class's static block (§4.2), which
     * holds them all, each aligned as the layout says.
     */
    long staticOffset(ClassInfo c, Field f) {
        JClass jc = staticClass(c);
        if (jc.staticOffsets == null) {
            jc.staticOffsets = new HashMap<>();
            long end = 0;
            for (MemberInfo m : c.all) {
                if (m.member instanceof Field sf && sf.modifiers().isStatic() && !sf.modifiers().isConst()) {
                    Type t = ck.staticFieldType(c, sf);
                    if (isAggregate(t) || addressTaken.contains(sf)) {
                        long off = Layouts.roundUp(end, Math.max(32, layouts.alignOf(t)));
                        jc.staticOffsets.put(sf, off);
                        end = off + Math.max(1, layouts.sizeOf(t)); // even a value of no bits has a place
                    }
                }
            }
            jc.staticBits = blockBits(end, c.decl.pos());
            if (end > 0) {
                jc.fields.put(STATICS, new FieldSpec(STATICS, BLOCK, ACC_PUBLIC | ACC_STATIC));
            }
        }
        return jc.staticOffsets.getOrDefault(f, -1L);
    }

    /**
     * The static fields some code takes the address of (`*Registry.count`): they live in their
     * class's static block, as values do, where an address can reach them. All are found in the
     * first pass, which lays the blocks out again as each is.
     */
    private final Set<Field> addressTaken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    void takeAddress(ClassInfo c, Field f) {
        if (addressTaken.add(f)) {
            if (writing) {
                throw new IllegalStateException("the address of " + f.name() + " was first taken while writing classes");
            }
            staticClass(c).staticOffsets = null;
        }
    }

    /** Whether a static field is in its class's static block rather than a JVM field. */
    boolean inStaticBlock(ClassInfo c, Field f) {
        return isAggregate(ck.staticFieldType(c, f)) || addressTaken.contains(f);
    }

    /** A lambda's class: one per lambda and instantiation of the method around it (§30.3). */
    JClass lambdaClass(FnKey key, Checker.LambdaInfo li, String file) {
        JClass jc = lambdaClasses.get(key);
        if (jc != null) {
            return jc;
        }
        jc = jclass(binaryName(key.cls()) + "$lambda$" + (lambdaClasses.size() + 1), file);
        jc.interfaces.add(instance(li.target()).desc); // the interface the lambda is, which may extend the method's
        jc.interfaces.add(instance(li.iface()).desc);
        jc.interfaces.add(instance(root()).desc);
        lambdaClasses.put(key, jc);
        lambdaInfos.put(key, li);
        return jc;
    }

    // ================================================================ types

    /** A value that lives in storage blocks (§4): a struct, unique, record or enum, or a fixed array. */
    boolean isAggregate(Type t) {
        return (t instanceof ClassT ct && !ct.cls().dynamic && !isValue(ct)) || (t instanceof ArrayT a && a.size() != null);
    }

    /** The JVM parameters a value of this type takes: one, or its block and offset. */
    List<ClassDesc> slots(Type t, Pos at) {
        return isAggregate(t) ? List.of(BLOCK, CD_int) : List.of(desc(t, at));
    }

    /**
     * A method's descriptor: what comes first (a receiver), then the place a struct result goes,
     * then the parameters, each struct as its block and offset (§4.2).
     */
    MethodTypeDesc methodType(List<ClassDesc> first, Type ret, List<Type> params, Pos at) {
        List<ClassDesc> ps = new ArrayList<>(first);
        ClassDesc rd;
        if (isAggregate(ret)) {
            ps.add(BLOCK);
            ps.add(CD_int);
            rd = CD_void;
        } else {
            rd = desc(ret, at);
        }
        for (Type p : params) {
            ps.addAll(slots(p, at));
        }
        return MethodTypeDesc.of(rd, ps.toArray(ClassDesc[]::new));
    }

    /**
     * Whether a value of this type needs more than zeroed storage when it is made without an
     * initializer: a struct or unique with constructors or field initializers, or one holding such.
     */
    boolean needsInit(Type t) {
        Boolean known = initCache.get(t);
        if (known != null) {
            return known;
        }
        initCache.put(t, false);
        boolean r = false;
        if (t instanceof ClassT ct && isAggregate(ct)) {
            r = !ct.cls().constructors.isEmpty();
            for (MemberInfo m : ct.cls().storedFields(ct.header())) {
                r |= ((Field) m.member).init() != null || needsInit(layouts.of(ct).types().get(m));
            }
        } else if (t instanceof ArrayT a && a.size() != null) {
            r = needsInit(a.element());
        }
        initCache.put(t, r);
        return r;
    }

    /** Whether destroying a value of this type runs code: a unique's destructor, or one inside it (§16). */
    boolean needsDestroy(Type t) {
        Boolean known = destroyCache.get(t);
        if (known != null) {
            return known;
        }
        destroyCache.put(t, false);
        boolean r = false;
        if (t instanceof ClassT ct && isAggregate(ct)) {
            r = ct.cls().destructors.stream().anyMatch(m -> m.existsIn(ct.header()));
            for (MemberInfo m : ct.cls().storedFields(ct.header())) {
                r |= needsDestroy(layouts.of(ct).types().get(m));
            }
        } else if (t instanceof ArrayT a && a.size() != null) {
            r = needsDestroy(a.element());
        }
        destroyCache.put(t, r);
        return r;
    }

    private final Map<Type, Boolean> initCache = new HashMap<>();
    private final Map<Type, Boolean> destroyCache = new HashMap<>();

    /** A string or an array<T>: on the JVM a String or a Java array, whose methods take it first. */
    static boolean isValue(Type t) {
        return isString(t) || isArray(t);
    }

    static boolean isString(Type t) {
        return t instanceof ClassT ct && ct.cls().builtin && ct.cls().name.equals("string");
    }

    static boolean isArray(Type t) {
        return t instanceof ClassT ct && ct.cls().builtin && ct.cls().name.equals("array") && ct.valueArgs().isEmpty();
    }

    /** A string's methods that change it give back the changed string, which the caller stores (§8.2). */
    static boolean replacesReceiver(Type t) {
        return isString(t);
    }

    /** How a value of this type is held on the JVM (docs/JVM.md §3). */
    static TypeKind kind(Type t) {
        return switch (t) {
            case BitT b -> TypeKind.BOOLEAN;
            case IntT it when it.isChar() -> TypeKind.CHAR;
            case IntT it -> width(it) <= 32 ? TypeKind.INT : TypeKind.LONG;
            case FixedT f -> width(f) <= 32 ? TypeKind.INT : TypeKind.LONG; // its scaled integer (§3.2)
            case FloatT f -> TypeKind.FLOAT;
            case DoubleT d -> TypeKind.DOUBLE;
            case VoidT v -> TypeKind.VOID;
            case JavaPrimT j -> kind(j.sealType()); // held as the Seal number with its values (docs/JAVA.md §3.1)
            case HandleT h -> TypeKind.REFERENCE;
            case AddrT a -> TypeKind.REFERENCE;
            case NullT n -> TypeKind.REFERENCE;
            case ClassT ct when isValue(ct) -> TypeKind.REFERENCE;
            default -> throw new Unsupported(null, "values of type " + t + " are not available on the JVM yet");
        };
    }

    ClassDesc desc(Type t, Pos at) {
        try {
            return switch (t) {
                case HandleT h when h.target() instanceof ClassT ct && Checker.isJavaArray(ct.cls()) ->
                        javaDesc(ct.typeArgs().get(0), at).arrayType(); // JavaArray<JavaByte> is a byte[]
                case HandleT h when h.target() instanceof ClassT ct -> instance(ct).desc;
                case AddrT a -> PTR; // §4.4
                case NullT n -> CD_Object;
                case ClassT ct when isString(ct) -> CD_String;
                case ClassT ct when isArray(ct) && isAggregate(ct.typeArgs().get(0)) -> BLOCK; // its elements in a row (§8.1)
                case ClassT ct when isArray(ct) -> desc(ct.typeArgs().get(0), at).arrayType();
                default -> switch (kind(t)) {
                    case BOOLEAN -> CD_boolean;
                    case CHAR -> CD_char;
                    case INT -> CD_int;
                    case LONG -> CD_long;
                    case FLOAT -> CD_float;
                    case DOUBLE -> CD_double;
                    case VOID -> CD_void;
                    default -> throw new Unsupported(at, "values of type " + t + " are not available on the JVM yet");
                };
            };
        } catch (Unsupported u) {
            throw u.pos == null && at != null ? new Unsupported(at, u.getMessage()) : u;
        }
    }

    /**
     * A Java type's own descriptor (docs/JAVA.md §3.1), as Java's arrays and calls name it: JavaByte
     * is a byte here, though a value of one is held as an int, as Seal's int<<8>> is.
     */
    ClassDesc javaDesc(Type t, Pos at) {
        if (t instanceof JavaPrimT j) {
            return ClassDesc.ofDescriptor(String.valueOf(j.code()));
        }
        return desc(t, at);
    }

    /** How Java's arrays hold elements of a Java type: a JavaByte's as bytes. */
    static TypeKind javaKind(Type t) {
        return t instanceof JavaPrimT j ? TypeKind.fromDescriptor(String.valueOf(j.code())) : TypeKind.REFERENCE;
    }

    ClassDesc[] descs(List<Type> types, Pos at) {
        ClassDesc[] out = new ClassDesc[types.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = desc(types.get(i), at);
        }
        return out;
    }

    static int width(Type t) {
        return switch (t) {
            case IntT it -> ((IntV) it.width()).value().intValueExact();
            case FixedT f -> ((IntV) f.width()).value().intValueExact();
            default -> throw new IllegalStateException("no width: " + t);
        };
    }

    static boolean isNumber(Type t) {
        return t instanceof IntT || t instanceof FixedT || t instanceof FloatT || t instanceof DoubleT;
    }

    /** An unsigned integer that fills its JVM type, uint<<32>> or uint<<64>>: its comparisons and division need care. */
    static boolean fullUnsigned(Type t) {
        Type r = raw(t);
        return r instanceof IntT it && !it.signed() && !it.isChar() && (width(it) == 32 || width(it) == 64);
    }

    /** The integer a fixed-point type is held as, its scaled value: int<<16>> for a fixed<<8, 8>> (§3.2); any other type as it is. */
    static Type raw(Type t) {
        return t instanceof FixedT f ? new IntT(f.width(), f.signed()) : t;
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
}
