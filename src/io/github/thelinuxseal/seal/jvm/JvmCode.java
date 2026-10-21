package io.github.thelinuxseal.seal.jvm;

import static io.github.thelinuxseal.seal.jvm.JvmGen.concrete;
import static io.github.thelinuxseal.seal.jvm.JvmGen.fullUnsigned;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isArray;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isNumber;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isString;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isValue;
import static io.github.thelinuxseal.seal.jvm.JvmGen.kind;
import static io.github.thelinuxseal.seal.jvm.JvmGen.raw;
import static io.github.thelinuxseal.seal.jvm.JvmGen.replacesReceiver;
import static io.github.thelinuxseal.seal.jvm.JvmGen.width;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_float;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.AddressTaken;
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
import io.github.thelinuxseal.seal.check.Layouts;
import io.github.thelinuxseal.seal.check.Type;
import io.github.thelinuxseal.seal.check.Type.*;
import io.github.thelinuxseal.seal.jvm.JvmGen.FieldSpec;
import io.github.thelinuxseal.seal.jvm.JvmGen.FnKey;
import io.github.thelinuxseal.seal.jvm.JvmGen.Invoke;
import io.github.thelinuxseal.seal.jvm.JvmGen.JClass;
import io.github.thelinuxseal.seal.jvm.JvmGen.MethodRef;
import io.github.thelinuxseal.seal.jvm.JvmGen.Unsupported;

/**
 * The code of one generated method (docs/JVM.md), on the JVM's operand stack: values are
 * converted where they meet another type, as the removed VM's code generator did, integers of a
 * width below their JVM type's are normalized after arithmetic, code the checker rewrote is
 * generated in its place.
 */
final class JvmCode {

    private static final ClassDesc CD_Integer = ClassDesc.of("java.lang.Integer");
    private static final ClassDesc CD_Long = ClassDesc.of("java.lang.Long");
    private static final ClassDesc CD_Arrays = ClassDesc.of("java.util.Arrays");

    private final JvmGen g;
    private final Checker ck;
    private final CodeBuilder c;
    private final FnKey k;
    private final MethodRef self;
    private final List<Diagnostic> found;

    private TypedBody tb;
    /** What `return` gives back: the method's Seal return type. */
    private Type returnType;
    /** The class `this` is, in an instance method or constructor; null in static code. */
    private ClassT thisType;
    /** In a string's or array's method or constructor: `this` is this local, which can be replaced. */
    private Local thisLocal;
    /** In a lambda: where the enclosing object is kept, if it uses it. */
    private FieldSpec lambdaThis;
    private JClass lambdaClass;
    /** In a struct's or unique's method or constructor: `this`, a value in a block. */
    private Mem thisMem;
    /** For a method returning a struct: where the caller wants it (§4.2). */
    private Mem retMem;
    /** The method's frame block (§4.2), in this local, and how much of it the method uses so far. */
    private int frameSlot = -1;
    private long frameEnd;
    /** Uniques made for the statement being generated, destroyed at its end (§16). */
    private List<Temp> stmtTemps = new ArrayList<>();
    /** The locals and parameters whose address the body takes: they live in the frame block (§4.2). */
    private java.util.Set<String> addressTaken = java.util.Set.of();

    /** A unique made for a statement, and a flag in the frame set when it is: code may skip making it (`a && f()`). */
    private record Temp(Stored value, Mem made) {
    }

    /** Variables by name, innermost scope first. */
    private final Deque<Map<String, Var>> scopes = new ArrayDeque<>();
    /** For each scope, the uniques declared in it that it destroys at its end, as finally blocks do. */
    private final Deque<List<Fin>> scopeFins = new ArrayDeque<>();
    private final Deque<Loop> loops = new ArrayDeque<>();
    /** The try statements with a finally block that code is inside, innermost first. */
    private final Deque<Fin> fins = new ArrayDeque<>();
    /** The switch expressions being generated: where their values go. */
    private final Deque<SwitchOut> switchOuts = new ArrayDeque<>();
    private String pendingLabel;
    private int line = -1;
    private Pos pos;
    /**
     * Values computed ahead of the expressions they stand for: an expression that code rewritten
     * from one use (xs[i]++ into xs[i] = xs[i] + 1) reaches twice.
     */
    private final Map<Expr, Local> pinned = new java.util.IdentityHashMap<>();

    sealed interface Var permits Local, Captured, Stored {
        Type type();
    }

    /** A local variable: its type and JVM slot. */
    record Local(Type type, int slot) implements Var {
    }

    /** A struct, unique or fixed array variable, or one whose address is taken: its value in a block (§4.2). */
    record Stored(Type type, Mem mem) implements Var {
    }

    /**
     * A place in a block: the block in a local, and an offset in bits: a constant, plus the value of
     * a local int if offSlot isn't -1.
     */
    record Mem(int block, int offSlot, long off) {
        Mem at(long bits) {
            return new Mem(block, offSlot, off + bits);
        }
    }

    /** A variable a lambda captured: a field of its class. */
    record Captured(Type type, FieldSpec field) implements Var {
    }

    /** Where break and continue go; continueTo is null for a switch. */
    record Loop(Label breakTo, Label continueTo, String name, int fins) {
    }

    record SwitchOut(Loop loop, int slot, Type type, Mem into) {
    }

    /**
     * Code that runs however a part of the method is left: a try's finally block, or the destruction
     * of a unique, from its declaration to the end of its scope. Jumps out go through its pads.
     */
    private static final class Fin {
        final Block block;
        final Stored destroyed;
        Label start;
        final List<Exit> exits = new ArrayList<>();
        int returnSlot = -1;

        Fin(Block block, Stored destroyed) {
            this.block = block;
            this.destroyed = destroyed;
        }
    }

    /**
     * A jump out of code a Fin covers: to a loop (break or continue) or out of the method; moved is a
     * unique local that the return hands to the caller, so it isn't destroyed.
     */
    private record Exit(Label pad, String kind, Loop loop, Stored moved) {
    }

    JvmCode(JvmGen g, CodeBuilder c, FnKey k, MethodRef self, List<Diagnostic> found) {
        this.g = g;
        this.ck = g.ck;
        this.c = c;
        this.k = k;
        this.self = self;
        this.found = found;
    }

    // ================================================================ methods

    void generate() {
        try {
            openFrame();
            switch (k.kind()) {
                case "init" -> allStatics();
                case "class-init" -> staticInit();
                case "fini" -> staticFini();
                case "start" -> start();
                case "destroy" -> destroy();
                case "dtor" -> userDestructor();
                case "ctor", "default-ctor" -> {
                    if (isValue(k.owner())) {
                        valueConstructor();
                    } else if (g.isAggregate(k.owner())) {
                        structConstructor();
                    } else {
                        constructor();
                    }
                }
                case "lambda" -> lambda();
                default -> method();
            }
            closeFrame();
        } catch (Unsupported u) {
            if (u.getMessage() != null) {
                // Reported here, so that it names the file the code is in (a static field's, a library method's).
                Pos p = u.pos == null ? pos : u.pos;
                String file = tb != null ? tb.file : k.cls().file;
                found.add(new Diagnostic(file, p == null ? 1 : p.line(), p == null ? 1 : p.column(), u.getMessage()));
            }
            throw new Unsupported(null, null);
        }
    }

    /** A body typed for its instance; what fails there (an operator T's type lacks) is reported in the instance's name. */
    private TypedBody typed(ClassInfo cls, ClassT owner, MemberInfo m, Map<String, CVal> binders) {
        TypedBody t = ck.typeBody(cls, owner, m, binders);
        if (!t.errors.isEmpty()) {
            for (Diagnostic d : t.errors) {
                found.add(new Diagnostic(d.file(), d.line(), d.column(), "in " + g.instanceName(k) + ": " + d.message()));
            }
            throw new Unsupported(m.member.pos(), null);
        }
        return t;
    }

    private void method() {
        Method md = (Method) k.member().member;
        pos = md.pos();
        tb = typed(k.cls(), k.owner(), k.member(), k.binders());
        returnType = tb.returnType;
        Map<String, Var> params = new HashMap<>();
        int first = 0;
        if (k.owner() != null && isValue(k.owner())) {
            thisLocal = new Local(k.owner(), c.parameterSlot(0));
            first = 1;
        } else if (k.owner() != null && g.isAggregate(k.owner())) {
            thisMem = new Mem(c.parameterSlot(0), c.parameterSlot(1), 0);
            first = 2;
        } else if (k.owner() != null) {
            thisType = k.owner();
        }
        addressTaken = AddressTaken.in(md.body());
        first = bindParams(md.params(), tb.params, first, params);
        openScope(params);
        block(md.body());
        endOfMethod();
    }

    /**
     * The parameters from JVM parameter {@code first} on, after the place a struct result goes:
     * a struct takes two, its block and offset (§4.2). Gives the index after them.
     */
    private int bindParams(List<Param> ps, List<Type> types, int first, Map<String, Var> into) {
        int j = first;
        if (g.isAggregate(returnType)) {
            retMem = new Mem(c.parameterSlot(j), c.parameterSlot(j + 1), 0);
            j += 2;
        }
        for (int i = 0; i < ps.size(); i++) {
            Type t = types.get(i);
            if (g.isAggregate(t)) {
                into.put(ps.get(i).name(), new Stored(t, new Mem(c.parameterSlot(j), c.parameterSlot(j + 1), 0)));
                j += 2;
            } else if (addressTaken.contains(ps.get(i).name())) {
                // Its address is taken: copied into the frame, where an address can reach it.
                Mem m = temp(t);
                push(m);
                c.loadLocal(load(t), c.parameterSlot(j));
                storePushed(t);
                into.put(ps.get(i).name(), new Stored(t, m));
                j++;
            } else {
                into.put(ps.get(i).name(), new Local(t, c.parameterSlot(j)));
                j++;
            }
        }
        return j;
    }

    private void endOfMethod() {
        if (!returnsValue()) {
            returnVoid();
        } else {
            // The checker makes sure every path returns; this end can't be reached.
            trap("reached the end of a method without returning");
        }
    }

    /** `return;`, which in a string's method gives back the string it made. */
    private void returnVoid() {
        if (thisLocal != null && replacesReceiver(thisLocal.type()) && !self.type().returnType().equals(CD_void)
                && !self.name().equals("<init>")) {
            c.aload(thisLocal.slot());
            c.areturn();
        } else {
            c.return_();
        }
    }

    private void trap(String message) {
        c.new_(JvmGen.TRAP);
        c.dup();
        c.loadConstant(message);
        c.invokespecial(JvmGen.TRAP, "<init>", MethodTypeDesc.of(CD_void, CD_String));
        c.athrow();
    }

    /** A class's constructor: the parent's, then field initializers, then the body (§15, §56.1). */
    private void constructor() {
        ClassT t = k.owner();
        thisType = t;
        Constructor ctor = k.member() == null ? null : (Constructor) k.member().member;
        pos = ctor != null ? ctor.pos() : t.cls().decl.pos();
        returnType = Type.VOID;
        Map<String, Var> params = new HashMap<>();
        List<Stmt> body = List.of();
        if (ctor != null) {
            tb = typed(k.cls(), t, k.member(), k.binders());
            addressTaken = AddressTaken.in(ctor.body());
            bindParams(ctor.params(), tb.params, 0, params);
            body = ctor.body().stmts();
        }
        openScope(params);
        int start = 0;
        if (!body.isEmpty() && body.get(0) instanceof ExprStmt es && es.expr() instanceof Call call
                && call.callee() instanceof SuperRef) {
            stmt(body.get(0));
            start = 1;
        } else {
            superDefault(t);
        }
        JClass jc = g.instance(t);
        if (jc.valuesBits > 0) {
            // The block for the object's struct fields (§5).
            c.aload(0);
            newBlock(jc.valuesBits);
            c.putfield(jc.desc, JvmGen.VALUES, JvmGen.BLOCK);
        }
        initFields(t);
        for (int i = start; i < body.size(); i++) {
            stmt(body.get(i));
        }
        c.return_();
    }

    /** The parent's no-argument constructor, or Object's (RuntimeException's for an exception). */
    private void superDefault(ClassT t) {
        JClass jc = g.instance(t);
        ClassT parent = ck.parentType(t);
        c.aload(0);
        if (parent == null || parent.cls().isRoot()) {
            c.invokespecial(jc.superclass, "<init>", MethodTypeDesc.of(CD_void));
            return;
        }
        if (parent.cls().java != null) {
            // A Java parent's constructor that takes nothing (the checker made sure it has one, docs/JAVA.md §5).
            c.invokespecial(jc.superclass, "<init>", MethodTypeDesc.of(CD_void));
            return;
        }
        MemberInfo noArgs = null;
        for (MemberInfo m : parent.cls().constructors) {
            if (m.existsIn(parent.header()) && ((Constructor) m.member).params().isEmpty()) {
                noArgs = m;
            }
        }
        if (noArgs == null && !parent.cls().constructors.isEmpty()) {
            throw new Unsupported(pos, parent + " has no no-argument constructor; call super(...) explicitly");
        }
        JvmGen.invoke(c, g.constructor(parent, noArgs, Map.of(), pos));
    }

    /** Field initializers, and the values strings and arrays start as, in declaration order. */
    private void initFields(ClassT t) {
        TypedBody saved = tb;
        for (MemberInfo m : t.cls().storedFields(t.header())) {
            Field f = (Field) m.member;
            Type ft = ck.fieldTypeOf(t, m);
            pos = f.pos();
            if (g.isAggregate(ft)) {
                c.aload(0);
                Mem fm = objectFieldMem(t, m);
                if (f.init() != null) {
                    tb = typed(t.cls(), t, m, Map.of());
                    into(fm, f.init(), ft);
                    tb = saved;
                } else {
                    requireDefaultConstructible(ft, "field " + f.name());
                    initZeroed(fm, ft);
                }
            } else if (f.init() != null) {
                tb = typed(t.cls(), t, m, Map.of());
                c.aload(0);
                value(f.init(), ft);
                c.putfield(g.instance(t).desc, f.name(), g.desc(ft, f.pos()));
                tb = saved;
            } else if (isValue(ft)) {
                c.aload(0);
                zero(ft);
                c.putfield(g.instance(t).desc, f.name(), g.desc(ft, f.pos()));
            } else {
                g.desc(ft, f.pos()); // a field of a type the JVM backend can't hold yet is an error here
            }
        }
        tb = saved;
    }

    /** A string's or array's constructor: `this` starts as the empty value, and the body makes it. */
    private void valueConstructor() {
        ClassT t = k.owner();
        Constructor ctor = k.member() == null ? null : (Constructor) k.member().member;
        pos = ctor != null ? ctor.pos() : t.cls().decl.pos();
        returnType = Type.VOID;
        Map<String, Var> params = new HashMap<>();
        if (ctor != null) {
            tb = typed(k.cls(), t, k.member(), k.binders());
            addressTaken = AddressTaken.in(ctor.body());
            bindParams(ctor.params(), tb.params, 0, params);
        }
        thisLocal = new Local(t, c.allocateLocal(TypeKind.REFERENCE));
        zero(t);
        c.astore(thisLocal.slot());
        openScope(params);
        if (ctor != null) {
            block(ctor.body());
        }
        c.aload(thisLocal.slot());
        c.areturn();
    }

    /** A struct's or unique's constructor (§15): it makes the value in the place it is given, zeroed. */
    private void structConstructor() {
        ClassT t = k.owner();
        Constructor ctor = k.member() == null ? null : (Constructor) k.member().member;
        pos = ctor != null ? ctor.pos() : t.cls().decl.pos();
        returnType = Type.VOID;
        thisMem = new Mem(c.parameterSlot(0), c.parameterSlot(1), 0);
        Map<String, Var> params = new HashMap<>();
        if (ctor != null) {
            tb = typed(k.cls(), t, k.member(), k.binders());
            addressTaken = AddressTaken.in(ctor.body());
            bindParams(ctor.params(), tb.params, 2, params);
        }
        openScope(params);
        structFields(t);
        if (ctor != null) {
            block(ctor.body());
        }
        c.return_();
    }

    /** A struct's field initializers, and the default construction of its struct fields, as declared. */
    private void structFields(ClassT t) {
        TypedBody saved = tb;
        for (MemberInfo m : t.cls().storedFields(t.header())) {
            Field f = (Field) m.member;
            Type ft = g.layouts.of(t).types().get(m);
            pos = f.pos();
            Mem fm = fieldMem(thisMem, t, m);
            if (f.init() != null) {
                tb = typed(t.cls(), t, m, Map.of());
                if (g.isAggregate(ft)) {
                    into(fm, f.init(), ft);
                } else {
                    push(fm);
                    value(f.init(), ft);
                    storePushed(ft);
                }
                tb = saved;
            } else if (g.isAggregate(ft)) {
                requireDefaultConstructible(ft, "field " + f.name());
                initZeroed(fm, ft);
            }
        }
        tb = saved;
    }

    /** Destroys a unique (§16): its destructor's body, then its fields, the last first. */
    private void destroy() {
        ClassT t = k.owner();
        returnType = Type.VOID;
        thisMem = new Mem(c.parameterSlot(0), c.parameterSlot(1), 0);
        for (MemberInfo m : t.cls().destructors) {
            if (m.existsIn(t.header())) {
                push(thisMem);
                JvmGen.invoke(c, g.ref(new FnKey("dtor", t.cls(), t, m, Map.of()), pos));
            }
        }
        List<MemberInfo> fields = new ArrayList<>(t.cls().storedFields(t.header()));
        java.util.Collections.reverse(fields);
        for (MemberInfo m : fields) {
            destroyValue(fieldMem(thisMem, t, m), g.layouts.of(t).types().get(m));
        }
        c.return_();
    }

    /** A unique's own destructor body. */
    private void userDestructor() {
        ClassT t = k.owner();
        Destructor d = (Destructor) k.member().member;
        pos = d.pos();
        returnType = Type.VOID;
        tb = typed(k.cls(), t, k.member(), Map.of());
        thisMem = new Mem(c.parameterSlot(0), c.parameterSlot(1), 0);
        addressTaken = AddressTaken.in(d.body());
        openScope(new HashMap<>());
        block(d.body());
        c.return_();
    }

    /**
     * Uniques in static fields, destroyed as main returns (§26.1): the last made first, the
     * program's classes before the library's, and System last.
     */
    private void staticFini() {
        returnType = Type.VOID;
        List<ClassInfo> order = new ArrayList<>(ck.allClasses());
        java.util.Collections.reverse(order);
        order.sort(java.util.Comparator.comparingInt(cls -> cls.builtin ? (cls.name.equals("System") ? 2 : 1) : 0));
        for (ClassInfo cls : order) {
            if (!g.initialized.contains(cls)) {
                continue;
            }
            List<MemberInfo> members = new ArrayList<>(cls.all);
            java.util.Collections.reverse(members);
            for (MemberInfo m : members) {
                if (m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()) {
                    Type t = ck.staticFieldType(cls, f);
                    if (g.needsDestroy(t)) {
                        destroyValue(staticMem(m), t);
                    }
                }
            }
        }
        c.return_();
    }

    /**
     * Runs an instance main() of a struct or unique (§56.1): makes the value with its no-argument
     * constructor, calls main() on it, and destroys it before the static fields are.
     */
    private void start() {
        ClassT t = k.owner();
        pos = k.member().member.pos();
        returnType = ck.returnTypeOf(t, k.member(), Map.of());
        openScope(new HashMap<>());
        Mem m = temp(t);
        initZeroed(m, t);
        if (g.needsDestroy(t)) {
            destroyLater(new Stored(t, m));
        }
        push(m);
        JvmGen.invoke(c, g.ref(new FnKey("method", k.cls(), t, k.member(), Map.of()), pos));
        int result = -1;
        if (!(returnType instanceof VoidT)) {
            result = c.allocateLocal(load(returnType));
            c.storeLocal(load(returnType), result);
        }
        closeScope();
        if (result < 0) {
            c.return_();
        } else {
            c.loadLocal(load(returnType), result);
            c.return_(load(returnType));
        }
    }

    /** Every static field, in Seal's order: the classes as the program lists them, the fields as declared (§10). */
    private void allStatics() {
        for (ClassInfo cls : ck.allClasses()) {
            if (g.initialized.contains(cls) && cls.all.stream().anyMatch(m -> m.member instanceof Field f
                    && f.modifiers().isStatic() && !f.modifiers().isConst())) {
                JvmGen.invoke(c, g.ref(new FnKey("class-init", cls, null, null, Map.of()), pos));
            }
        }
        c.return_();
    }

    /** One class's static fields, as declared. */
    private void staticInit() {
        returnType = Type.VOID;
        openScope(new HashMap<>());
        for (ClassInfo cls : List.of(k.cls())) {
            JClass home = g.staticClass(cls);
            for (MemberInfo m : cls.all) {
                if (m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()) {
                    g.staticOffset(cls, f); // lays out the class's static block
                    break;
                }
            }
            if (home.staticBits > 0) {
                newBlock(home.staticBits);
                c.putstatic(home.desc, JvmGen.STATICS, JvmGen.BLOCK);
            }
            for (MemberInfo m : cls.all) {
                if (!(m.member instanceof Field f) || !f.modifiers().isStatic() || f.modifiers().isConst()) {
                    continue;
                }
                Type t = ck.staticFieldType(cls, f);
                pos = f.pos();
                if (!g.isAggregate(t) && g.inStaticBlock(cls, f)) {
                    // A number or reference whose address some code takes: in the block.
                    if (f.init() != null) {
                        tb = typed(cls, null, m, Map.of());
                        at(f.init().pos());
                        push(staticMem(m));
                        value(f.init(), t);
                        storePushed(t);
                    }
                    continue;
                }
                if (g.isAggregate(t)) {
                    Mem sm = staticMem(m);
                    if (f.init() != null) {
                        tb = typed(cls, null, m, Map.of());
                        at(f.init().pos());
                        into(sm, f.init(), t);
                    } else {
                        requireDefaultConstructible(t, "static field " + f.name());
                        initZeroed(sm, t);
                    }
                    continue;
                }
                ClassDesc fd = g.desc(t, f.pos());
                home.fields.putIfAbsent(f.name(), new FieldSpec(f.name(), fd, java.lang.classfile.ClassFile.ACC_PUBLIC
                        | java.lang.classfile.ClassFile.ACC_STATIC));
                if (f.init() != null) {
                    tb = typed(cls, null, m, Map.of());
                    at(f.init().pos());
                    value(f.init(), t);
                    c.putstatic(home.desc, f.name(), fd);
                } else if (isValue(t)) {
                    zero(t);
                    c.putstatic(home.desc, f.name(), fd);
                }
            }
        }
        c.return_();
    }

    /** A lambda's method: its parameters, with what it captured in its class's fields (§30.3). */
    private void lambda() {
        Lambda lam = (Lambda) k.extra();
        tb = typed(k.cls(), k.owner(), k.member(), k.binders());
        Checker.LambdaInfo li = tb.lambdas.get(lam);
        lambdaClass = g.lambdaClasses.get(k);
        pos = lam.pos();
        returnType = li.returnType();
        Map<String, Var> captured = new HashMap<>();
        int i = 0;
        if (li.thisType() != null) {
            lambdaThis = lambdaClass.captures.get(i++);
            thisType = li.thisType();
        }
        for (int n = 0; n < li.captureNames().size(); n++) {
            Type ct = li.captureTypes().get(n);
            FieldSpec f = lambdaClass.captures.get(i++);
            if (g.isAggregate(ct)) {
                // A struct it captured: a copy in a block of its own, which the lambda's code uses in place.
                c.aload(0);
                c.getfield(lambdaClass.desc, f.name(), f.type());
                int slot = c.allocateLocal(TypeKind.REFERENCE);
                c.astore(slot);
                captured.put(li.captureNames().get(n), new Stored(ct, new Mem(slot, -1, 0)));
            } else {
                captured.put(li.captureNames().get(n), new Captured(ct, f));
            }
        }
        openScope(captured);
        Map<String, Var> params = new HashMap<>();
        Block body = lam.block() != null ? lam.block() : new Block(lam.pos(), List.of(returnType instanceof VoidT
                ? new ExprStmt(lam.pos(), lam.expr()) : new Return(lam.pos(), lam.expr())));
        addressTaken = AddressTaken.in(body);
        bindParams(lam.params(), li.params(), 0, params);
        if (li.iface().cls().java != null) {
            // Java passes its erased types, compare(Object, Object): each parameter checked as the Seal type it is (docs/JAVA.md §5).
            for (int n = 0; n < li.params().size(); n++) {
                Type pt = li.params().get(n);
                if (kind(pt) == TypeKind.REFERENCE && !g.desc(pt, pos).equals(self.type().parameterType(n))
                        && params.get(lam.params().get(n).name()) instanceof Local l) {
                    c.aload(l.slot());
                    c.checkcast(g.desc(pt, pos));
                    c.astore(l.slot());
                }
            }
        }
        openScope(params);
        block(body);
        endOfMethod();
    }

    // ================================================================ helpers

    private Type type(Expr e) {
        Type t = tb.types.get(e);
        if (t == null) {
            throw new Unsupported(e.pos(), "an expression the checker didn't type");
        }
        return t;
    }

    private Unsupported unsupported(Pos at, String what) {
        return new Unsupported(at == null ? pos : at, what + " is not available on the JVM yet");
    }

    private void at(Pos p) {
        pos = p;
        if (p != null && p.line() != line && p.line() > 0) {
            line = p.line();
            c.lineNumber(line);
        }
    }

    private TypeKind load(Type t) {
        return kind(t).asLoadable();
    }

    private void loadVar(Var v) {
        switch (v) {
            case Local l -> c.loadLocal(load(l.type()), l.slot());
            case Captured cp -> {
                c.aload(0);
                c.getfield(lambdaClass.desc, cp.field().name(), cp.field().type());
            }
            case Stored st when !g.isAggregate(st.type()) -> loadFrom(st.mem(), st.type());
            case Stored st -> throw new IllegalStateException("a struct variable used as a single value: " + st.type());
        }
    }

    /** Pushes `this`: the object, or a string's or array's value. */
    private void loadThis(Pos at) {
        if (thisLocal != null) {
            c.aload(thisLocal.slot());
        } else if (lambdaThis != null) {
            c.aload(0);
            c.getfield(lambdaClass.desc, lambdaThis.name(), lambdaThis.type());
        } else if (thisType != null) {
            c.aload(0);
        } else {
            throw unsupported(at, "this here");
        }
    }

    private Type thisValueType() {
        if (thisMem != null) {
            return k.owner();
        }
        return thisLocal != null ? thisLocal.type() : new HandleT(thisType);
    }

    /** The value a variable of this type starts as: zero, false, null, "" or an empty array. */
    private void zero(Type t) {
        if (isString(t)) {
            c.loadConstant("");
            return;
        }
        if (isArray(t)) {
            c.iconst_0();
            newArray(((ClassT) t).typeArgs().get(0));
            return;
        }
        switch (kind(t)) {
            case LONG -> c.lconst_0();
            case FLOAT -> c.fconst_0();
            case DOUBLE -> c.dconst_0();
            case REFERENCE -> c.aconst_null();
            default -> c.iconst_0();
        }
    }

    /**
     * A new Java array of the length on the stack, whose elements start as their default: "" for
     * strings. For structs, a block of that many in a row, each made as a variable without an
     * initializer is (§8.1).
     */
    private void newArray(Type element) {
        if (g.isAggregate(element)) {
            long stride = g.layouts.stride(element);
            int n = c.allocateLocal(TypeKind.INT);
            c.istore(n);
            c.new_(JvmGen.BLOCK);
            c.dup();
            c.iload(n);
            c.i2l();
            c.loadConstant(stride);
            c.lmul();
            c.iload(n);
            c.invokespecial(JvmGen.BLOCK, "<init>", MethodTypeDesc.of(CD_void, CD_long, CD_int));
            if (g.needsInit(element)) {
                int block = c.allocateLocal(TypeKind.REFERENCE);
                c.dup();
                c.astore(block);
                int off = c.allocateLocal(TypeKind.INT);
                int i = c.allocateLocal(TypeKind.INT);
                c.iconst_0();
                c.istore(off);
                c.iconst_0();
                c.istore(i);
                Label top = c.newLabel();
                Label end = c.newLabel();
                c.labelBinding(top);
                c.iload(i);
                c.iload(n);
                c.if_icmpge(end);
                initZeroed(new Mem(block, off, 0), element);
                c.iinc(off, (int) stride);
                c.iinc(i, 1);
                c.goto_(top);
                c.labelBinding(end);
            }
            return;
        }
        ClassDesc ed = g.desc(element, pos);
        TypeKind ek = kind(element);
        if (ek == TypeKind.REFERENCE) {
            c.anewarray(ed);
            if (isString(element)) {
                c.dup();
                c.loadConstant("");
                c.invokestatic(CD_Arrays, "fill", MethodTypeDesc.of(CD_void, CD_Object.arrayType(), CD_Object));
            }
        } else {
            c.newarray(ek);
        }
    }

    // ================================================================ blocks (§4)

    private void openScope(Map<String, Var> vars) {
        scopes.push(vars);
        scopeFins.push(new ArrayList<>());
    }

    /** Ends a scope: the uniques declared in it are destroyed, the last first, on each way out (§16). */
    private void closeScope() {
        List<Fin> ending = scopeFins.pop();
        for (int i = ending.size() - 1; i >= 0; i--) {
            finishDestroy(ending.get(i));
        }
        scopes.pop();
    }

    /** The frame block, made as the method starts if it holds any values (§4.2): its size is what the first pass used. */
    private void openFrame() {
        frameSlot = c.allocateLocal(TypeKind.REFERENCE);
        if (g.writing) {
            long size = g.frameSizes.getOrDefault(k, 0L);
            if (size > 0) {
                newBlock(size);
                c.astore(frameSlot);
            }
        }
    }

    private void closeFrame() {
        if (!g.writing) {
            g.frameSizes.put(k, frameEnd);
        } else if (frameEnd > g.frameSizes.getOrDefault(k, 0L)) {
            throw new IllegalStateException("the frame of " + g.describe(k) + " grew between passes");
        }
    }

    private void newBlock(long bits) {
        c.new_(JvmGen.BLOCK);
        c.dup();
        c.loadConstant(bits);
        c.invokespecial(JvmGen.BLOCK, "<init>", MethodTypeDesc.of(CD_void, CD_long));
    }

    /** Room in the frame for a value of this type: values start on a 64-bit boundary, as on the VM. */
    private Mem temp(Type t) {
        long off = Layouts.roundUp(frameEnd, 64);
        frameEnd = JvmGen.blockBits(off + Math.max(1, g.layouts.sizeOf(t)), pos);
        return new Mem(frameSlot, -1, off);
    }

    /** Pushes a place's block and offset, as a method taking a value receives them. */
    private void push(Mem m) {
        c.aload(m.block());
        pushOffset(m);
    }

    private void pushOffset(Mem m) {
        if (m.offSlot() < 0) {
            c.loadConstant((int) m.off());
        } else {
            c.iload(m.offSlot());
            if (m.off() != 0) {
                c.loadConstant((int) m.off());
                c.iadd();
            }
        }
    }

    /** A block and offset on the stack, kept in locals as a place. */
    private Mem storeMem() {
        int off = c.allocateLocal(TypeKind.INT);
        c.istore(off);
        int block = c.allocateLocal(TypeKind.REFERENCE);
        c.astore(block);
        return new Mem(block, off, 0);
    }

    private void copy(Mem dst, Mem src, Type t) {
        long bits = g.layouts.sizeOf(t);
        if (bits == 0) {
            return;
        }
        push(dst);
        push(src);
        c.loadConstant((int) bits);
        c.invokestatic(JvmGen.BLOCK, "copy", MethodTypeDesc.of(CD_void, JvmGen.BLOCK, CD_int, JvmGen.BLOCK, CD_int, CD_int));
    }

    private void zeroMem(Mem m, Type t) {
        long bits = g.layouts.sizeOf(t);
        if (bits == 0) {
            return;
        }
        push(m);
        c.loadConstant((int) bits);
        c.invokestatic(JvmGen.BLOCK, "zero", MethodTypeDesc.of(CD_void, JvmGen.BLOCK, CD_int, CD_int));
    }

    /** A field of a value in a block. */
    private Mem fieldMem(Mem object, ClassT owner, MemberInfo m) {
        return object.at(g.layouts.of(owner).offsets().get(m));
    }

    /** A struct field of a class object, whose block the object on the stack holds (§5). */
    private Mem objectFieldMem(ClassT objectType, MemberInfo m) {
        ClassT owner = g.declaringType(objectType, m);
        c.getfield(g.instance(owner).desc, JvmGen.VALUES, JvmGen.BLOCK);
        int block = c.allocateLocal(TypeKind.REFERENCE);
        c.astore(block);
        return new Mem(block, -1, g.layouts.of(owner).offsets().get(m));
    }

    /** A static field of a struct type, in its class's static block (§4.2). */
    private Mem staticMem(MemberInfo m) {
        ClassInfo cls = ck.declaringClassOf(m);
        long off = g.staticOffset(cls, (Field) m.member);
        c.getstatic(g.staticClass(cls).desc, JvmGen.STATICS, JvmGen.BLOCK);
        int block = c.allocateLocal(TypeKind.REFERENCE);
        c.astore(block);
        return new Mem(block, -1, off);
    }

    /**
     * Checks the index on the stack, leaving it, against a fixed array's length: out of range, it
     * throws an IndexOutOfBoundsException through the library's FixedArray.outOfBounds, as on the
     * VM, or (without the library) stops the program.
     */
    private void boundsCheck(int n, Pos at) {
        ClassInfo fa = ck.classNamed("FixedArray");
        MemberInfo thrower = fa == null || !fa.builtin ? null
                : fa.all.stream().filter(m -> m.member instanceof Method md && md.name().equals("outOfBounds")).findFirst().orElse(null);
        if (thrower == null) {
            c.loadConstant(n);
            c.invokestatic(JvmGen.INTS, "bounds", MethodTypeDesc.of(CD_int, CD_int, CD_int));
            return;
        }
        Label fine = c.newLabel();
        c.dup();
        c.loadConstant(n);
        c.invokestatic(CD_Integer, "compareUnsigned", MethodTypeDesc.of(CD_int, CD_int, CD_int));
        c.iflt(fine); // a negative index is a huge unsigned one
        c.dup();
        c.loadConstant(n);
        JvmGen.invoke(c, g.ref(new FnKey("method", fa, null, thrower, Map.of()), at));
        c.labelBinding(fine);
    }

    /** A round of a loop, counted in a program generated for the tests to count them (JvmGen.generateCountingSteps). */
    private void countStep() {
        if (g.countSteps) {
            c.invokestatic(JvmGen.NATIVES, "step", MethodTypeDesc.of(CD_void));
        }
    }

    /** An element of a fixed array (§21.1), its index checked so that one out of range stops the program. */
    private Mem elementMem(Mem array, ArrayT at, Expr index) {
        long stride = g.layouts.stride(at.element());
        long n = at.size().intValue().longValueExact();
        Type it = type(index);
        if (it instanceof IntConstT) {
            long i = ck.constantIn(tb, index).intValue().longValueExact();
            if (i >= 0 && i < n) {
                return array.at(i * stride);
            }
        }
        value(index, Type.INT);
        boundsCheck((int) n, index.pos());
        c.loadConstant((int) stride);
        c.imul();
        if (array.offSlot() >= 0) {
            c.iload(array.offSlot());
            c.iadd();
        }
        int slot = c.allocateLocal(TypeKind.INT);
        c.istore(slot);
        return new Mem(array.block(), slot, array.off());
    }

    /** Pushes the value of type t stored at m. */
    private void loadFrom(Mem m, Type t) {
        push(m);
        loadPushed(t);
    }

    /** With a block and offset on the stack: the value of type t stored there. */
    private void loadPushed(Type t) {
        if (t instanceof JavaPrimT j) {
            loadPushed(j.sealType()); // stored as the Seal number with its values (docs/JAVA.md §3.1)
            return;
        }
        if (isString(t)) {
            c.invokestatic(JvmGen.BLOCK, "string", MethodTypeDesc.of(CD_String, JvmGen.BLOCK, CD_int));
            return;
        }
        if (kind(t) == TypeKind.REFERENCE) {
            c.invokestatic(JvmGen.BLOCK, "ref", MethodTypeDesc.of(CD_Object, JvmGen.BLOCK, CD_int));
            c.checkcast(g.desc(t, pos));
            if (isArray(t)) {
                // An array never stored is the empty one, as a zeroed array is on the VM.
                Label set = c.newLabel();
                c.dup();
                c.ifnonnull(set);
                c.pop();
                zero(t);
                c.labelBinding(set);
            }
            return;
        }
        c.loadConstant(bitWidth(t));
        c.invokestatic(JvmGen.BLOCK, "load", MethodTypeDesc.of(CD_long, JvmGen.BLOCK, CD_int, CD_int));
        switch (raw(t)) {
            case IntT it when it.signed() && !it.isChar() -> {
                int w = width(it);
                if (w < 64) {
                    c.loadConstant(64 - w);
                    c.lshl();
                    c.loadConstant(64 - w);
                    c.lshr();
                }
                if (load(it) == TypeKind.INT) {
                    c.l2i();
                }
            }
            case IntT it -> {
                if (load(it) == TypeKind.INT) {
                    c.l2i();
                }
            }
            case BitT b -> c.l2i();
            case FloatT f -> {
                c.l2i();
                c.invokestatic(ClassDesc.of("java.lang.Float"), "intBitsToFloat", MethodTypeDesc.of(CD_float, CD_int));
            }
            case DoubleT d -> c.invokestatic(ClassDesc.of("java.lang.Double"), "longBitsToDouble",
                    MethodTypeDesc.of(CD_double, CD_long));
            default -> throw unsupported(pos, "values of type " + t + " in a block");
        }
    }

    /** With a block, an offset and a value of type t on the stack: stores the value there. */
    private void storePushed(Type t) {
        if (t instanceof JavaPrimT j) {
            storePushed(j.sealType());
            return;
        }
        if (kind(t) == TypeKind.REFERENCE) {
            c.invokestatic(JvmGen.BLOCK, "setRef", MethodTypeDesc.of(CD_void, JvmGen.BLOCK, CD_int, CD_Object));
            return;
        }
        switch (load(t)) {
            case INT -> c.i2l();
            case FLOAT -> {
                c.invokestatic(ClassDesc.of("java.lang.Float"), "floatToRawIntBits", MethodTypeDesc.of(CD_int, CD_float));
                c.i2l();
            }
            case DOUBLE -> c.invokestatic(ClassDesc.of("java.lang.Double"), "doubleToRawLongBits",
                    MethodTypeDesc.of(CD_long, CD_double));
            default -> { }
        }
        c.loadConstant(bitWidth(t));
        c.invokestatic(JvmGen.BLOCK, "store", MethodTypeDesc.of(CD_void, JvmGen.BLOCK, CD_int, CD_long, CD_int));
    }

    private static int bitWidth(Type t) {
        return switch (t) {
            case JavaPrimT j -> bitWidth(j.sealType());
            case IntT it -> width(it);
            case FixedT f -> width(f);
            case BitT b -> 1;
            case FloatT f -> 32;
            case DoubleT d -> 64;
            default -> 64;
        };
    }

    /**
     * A value made without an initializer is default-constructed, through its no-argument
     * constructor: the checker's rule, here for what a type argument makes known (Fd in Slot<Fd>).
     */
    private void requireDefaultConstructible(Type t, String what) {
        Type element = t;
        while (element instanceof ArrayT a) {
            element = a.element();
        }
        if (element instanceof ClassT ct && !ct.cls().dynamic && !ct.cls().constructors.isEmpty()
                && ct.cls().constructors.stream().noneMatch(m -> m.existsIn(ct.header())
                        && ((Constructor) m.member).params().isEmpty())) {
            throw new Unsupported(pos, t instanceof ArrayT
                    ? "each element of " + what + " (" + t + ") is default-constructed, but " + ct.cls().name
                            + " has no no-argument constructor"
                    : what + " of type " + ct + " needs an initializer: " + ct.cls().name + " has no no-argument constructor");
        }
    }

    /** Makes a value in a place where nothing was: a struct's no-argument constructor, and each element of an array of them. */
    private void initZeroed(Mem m, Type t) {
        if (!g.needsInit(t)) {
            return;
        }
        if (t instanceof ClassT ct) {
            MemberInfo noArgs = null;
            for (MemberInfo ctor : ct.cls().constructors) {
                if (ctor.existsIn(ct.header()) && ((Constructor) ctor.member).params().isEmpty()) {
                    noArgs = ctor;
                }
            }
            push(m);
            JvmGen.invoke(c, g.constructor(ct, noArgs, Map.of(), pos));
        } else if (t instanceof ArrayT a) {
            eachElement(m, a, e -> initZeroed(e, a.element()));
        }
    }

    /** Runs body on each element of a fixed array, first to last. */
    private void eachElement(Mem m, ArrayT a, java.util.function.Consumer<Mem> body) {
        eachElement(m, a, false, body);
    }

    /** As above; reverse goes last to first, as destruction does (§16). */
    private void eachElement(Mem m, ArrayT a, boolean reverse, java.util.function.Consumer<Mem> body) {
        long n = a.size().intValue().longValueExact();
        long stride = g.layouts.stride(a.element());
        if (n <= 8) {
            for (long j = 0; j < n; j++) {
                long i = reverse ? n - 1 - j : j;
                body.accept(m.at(i * stride));
            }
            return;
        }
        int off = c.allocateLocal(TypeKind.INT);
        pushOffset(m);
        if (reverse) {
            c.loadConstant((int) ((n - 1) * stride));
            c.iadd();
        }
        c.istore(off);
        int i = c.allocateLocal(TypeKind.INT);
        c.iconst_0();
        c.istore(i);
        Label top = c.newLabel();
        Label end = c.newLabel();
        c.labelBinding(top);
        c.iload(i);
        c.loadConstant((int) n);
        c.if_icmpge(end);
        body.accept(new Mem(m.block(), off, 0));
        c.iinc(off, (int) (reverse ? -stride : stride));
        c.iinc(i, 1);
        c.goto_(top);
        c.labelBinding(end);
    }

    /** Destroys a value (§16): a unique's destructor, then its fields', last first; each element of an array of them. */
    private void destroyValue(Mem m, Type t) {
        if (!g.needsDestroy(t)) {
            return;
        }
        if (t instanceof ClassT ct) {
            push(m);
            JvmGen.invoke(c, g.ref(new FnKey("destroy", ct.cls(), ct, null, Map.of()), pos));
        } else if (t instanceof ArrayT a) {
            eachElement(m, a, true, e -> destroyValue(e, a.element()));
        }
    }

    /** A local unique with a destructor: destroyed however its scope is left, from here on. */
    private void destroyLater(Stored s) {
        Fin f = new Fin(null, s);
        f.start = c.newLabel();
        c.labelBinding(f.start);
        c.nop(); // so that the code it covers is never empty
        fins.push(f);
        scopeFins.peek().add(f);
    }

    /** At the end of a unique's scope: destroyed there, on each jump out, and when an exception passes. */
    private void finishDestroy(Fin f) {
        Label end = c.newLabel();
        Label after = c.newLabel();
        c.labelBinding(end);
        if (fins.peek() != f) {
            throw new IllegalStateException("scopes and finally blocks out of order");
        }
        fins.pop();
        destroyValue(f.destroyed.mem(), f.destroyed.type());
        c.goto_(after);
        ClassInfo exception = ck.classNamed("Exception");
        if (exception != null && exception.builtin) {
            Label handler = c.newLabel();
            c.exceptionCatch(f.start, end, handler, g.instance(new ClassT(exception, List.of(), List.of(), 0)).desc);
            c.labelBinding(handler);
            int slot = c.allocateLocal(TypeKind.REFERENCE);
            c.astore(slot);
            destroyValue(f.destroyed.mem(), f.destroyed.type());
            c.aload(slot);
            c.athrow();
        }
        for (Exit e : f.exits) {
            c.labelBinding(e.pad());
            if (e.moved() != f.destroyed) {
                destroyValue(f.destroyed.mem(), f.destroyed.type());
            }
            if (e.kind().equals("return") && returnsValue()) {
                c.loadLocal(load(returnType), f.returnSlot);
            }
            jump(e.kind(), e.loop(), e.moved());
        }
        c.labelBinding(after);
    }

    private boolean returnsValue() {
        return !(returnType instanceof VoidT) && !g.isAggregate(returnType);
    }

    /** Destroys the uniques made for the statement just generated (§16), those it made. */
    private void flushTemps() {
        destroyTemps(stmtTemps);
        stmtTemps.clear();
    }

    private void destroyTemps(List<Temp> temps) {
        for (int i = temps.size() - 1; i >= 0; i--) {
            Temp t = temps.get(i);
            Label skip = c.newLabel();
            push(t.made());
            c.iconst_1();
            c.invokestatic(JvmGen.BLOCK, "load", MethodTypeDesc.of(CD_long, JvmGen.BLOCK, CD_int, CD_int));
            c.l2i();
            c.ifeq(skip);
            destroyValue(t.value().mem(), t.value().type());
            setFlag(t.made(), 0);
            c.labelBinding(skip);
        }
    }

    /**
     * Ends the code from start that made uniques for its statement: an exception it throws destroys
     * those made so far, then goes on to whatever catches it, as on the VM (§16).
     */
    private void guardTemps(Label start, List<Temp> made) {
        ClassInfo exception = ck.classNamed("Exception");
        if (made.isEmpty() || exception == null || !exception.builtin) {
            return;
        }
        Label end = c.newLabel();
        Label handler = c.newLabel();
        Label on = c.newLabel();
        c.labelBinding(end);
        c.goto_(on);
        c.exceptionCatch(start, end, handler, g.instance(new ClassT(exception, List.of(), List.of(), 0)).desc);
        c.labelBinding(handler);
        int slot = c.allocateLocal(TypeKind.REFERENCE);
        c.astore(slot);
        destroyTemps(made);
        c.aload(slot);
        c.athrow();
        c.labelBinding(on);
    }

    private Label here() {
        Label l = c.newLabel();
        c.labelBinding(l);
        return l;
    }

    private void setFlag(Mem flag, long v) {
        push(flag);
        c.loadConstant(v);
        c.iconst_1();
        c.invokestatic(JvmGen.BLOCK, "store", MethodTypeDesc.of(CD_void, JvmGen.BLOCK, CD_int, CD_long, CD_int));
    }

    /** A unique just made for the statement: destroyed at its end. */
    private void madeTemp(Type t, Mem m) {
        Mem flag = temp(Type.BIT);
        setFlag(flag, 1);
        stmtTemps.add(new Temp(new Stored(t, m), flag));
    }

    /**
     * Jumps to no unless the condition holds; uniques it makes for itself are destroyed right after
     * it, on both ways on (§16).
     */
    private void condition(Expr cond, Label no) {
        if (!mayMakeTemps(cond)) {
            branchFalse(cond, no);
            return;
        }
        List<Temp> outer = stmtTemps;
        stmtTemps = new ArrayList<>();
        Label failed = c.newLabel();
        Label on = c.newLabel();
        Label start = here();
        branchFalse(cond, failed);
        List<Temp> made = stmtTemps;
        stmtTemps = outer;
        guardTemps(start, made);
        destroyTemps(made);
        c.goto_(on);
        c.labelBinding(failed);
        destroyTemps(made);
        c.goto_(no);
        c.labelBinding(on);
    }

    /** Whether evaluating e may make a unique with a destructor that only the statement holds. */
    private boolean mayMakeTemps(Expr e) {
        if (e == null) {
            return false;
        }
        if (tb.rewritten.containsKey(e) && mayMakeTemps(tb.rewritten.get(e))) {
            return true;
        }
        Type t = tb.types.get(e);
        if (t != null && (e instanceof Call || e instanceof New || e instanceof Binary || e instanceof Unary
                || e instanceof Cast) && g.isAggregate(t) && g.needsDestroy(t)) {
            return true;
        }
        return switch (e) {
            case Binary b -> mayMakeTemps(b.left()) || mayMakeTemps(b.right());
            case Unary u -> mayMakeTemps(u.operand());
            case Call call -> mayMakeTemps(call.callee()) || call.args().stream().anyMatch(this::mayMakeTemps);
            case Select s -> mayMakeTemps(s.target());
            case Index ix -> mayMakeTemps(ix.target()) || mayMakeTemps(ix.index());
            case Conditional cd -> mayMakeTemps(cd.cond()) || mayMakeTemps(cd.then()) || mayMakeTemps(cd.otherwise());
            case Cast cast -> mayMakeTemps(cast.operand());
            case Assign a -> mayMakeTemps(a.target()) || mayMakeTemps(a.value());
            case InstanceOf io -> mayMakeTemps(io.operand());
            case New n -> n.args().stream().anyMatch(this::mayMakeTemps);
            default -> false;
        };
    }

    // ================================================================ statements

    private void block(Block b) {
        openScope(new HashMap<>());
        for (Stmt s : b.stmts()) {
            stmt(s);
        }
        closeScope();
    }

    /** A statement that forms its own scope: the body of an if, while or for. */
    private void scoped(Stmt s) {
        openScope(new HashMap<>());
        stmt(s);
        closeScope();
    }

    private void stmt(Stmt s) {
        if (tb.statementSteps.containsKey(s)) {
            stmt(tb.statementSteps.get(s));
            return;
        }
        at(s.pos());
        switch (s) {
            case Block b -> block(b);
            case VarDecl v -> {
                Type t = tb.locals.get(v);
                if (g.isAggregate(t)) {
                    Stored st = new Stored(t, temp(t));
                    Label start = here();
                    if (v.init() != null) {
                        into(st.mem(), v.init(), t);
                    } else {
                        pos = v.pos();
                        requireDefaultConstructible(t, "variable " + v.name());
                        zeroMem(st.mem(), t);
                        initZeroed(st.mem(), t);
                    }
                    guardTemps(start, stmtTemps);
                    flushTemps();
                    scopes.peek().put(v.name(), st);
                    if (g.needsDestroy(t)) {
                        destroyLater(st);
                    }
                    return;
                }
                if (addressTaken.contains(v.name())) {
                    // Its address is taken (§19): it lives in the frame block.
                    Stored st = new Stored(t, temp(t));
                    Label start = here();
                    push(st.mem());
                    if (v.init() != null) {
                        value(v.init(), t);
                    } else {
                        zero(t);
                    }
                    guardTemps(start, stmtTemps);
                    flushTemps();
                    storePushed(t);
                    scopes.peek().put(v.name(), st);
                    return;
                }
                int slot = c.allocateLocal(kind(t));
                Label start = here();
                if (v.init() != null) {
                    value(v.init(), t);
                } else {
                    zero(t);
                }
                guardTemps(start, stmtTemps);
                flushTemps();
                c.storeLocal(load(t), slot);
                scopes.peek().put(v.name(), new Local(t, slot));
            }
            case ExprStmt e -> {
                Label start = here();
                effect(e.expr());
                guardTemps(start, stmtTemps);
                flushTemps();
            }
            case ForEach fe -> stmt(tb.desugared.get(fe));
            case If i -> {
                Label otherwise = c.newLabel();
                Label end = c.newLabel();
                condition(i.cond(), otherwise);
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
                countStep();
                condition(w.cond(), end);
                loops.push(new Loop(end, top, name, fins.size()));
                scoped(w.body());
                loops.pop();
                c.goto_(top);
                c.labelBinding(end);
            }
            case For f -> {
                String name = takeLabel();
                openScope(new HashMap<>());
                if (f.init() != null) {
                    stmt(f.init());
                }
                Label top = c.newLabel();
                Label next = c.newLabel();
                Label end = c.newLabel();
                c.labelBinding(top);
                countStep();
                if (f.cond() != null) {
                    condition(f.cond(), end);
                }
                loops.push(new Loop(end, next, name, fins.size()));
                scoped(f.body());
                loops.pop();
                c.labelBinding(next);
                if (f.update() != null) {
                    effect(f.update());
                }
                c.goto_(top);
                c.labelBinding(end);
                closeScope();
            }
            case Labeled l -> {
                pendingLabel = l.label();
                stmt(l.body());
            }
            case Break b -> jump("break", b.label() == null ? loops.peek() : labeled(b.label()));
            case Continue co -> jump("continue", co.label() == null
                    ? loops.stream().filter(x -> x.continueTo() != null).findFirst().orElseThrow()
                    : labeled(co.label()));
            case Return r -> {
                Stored moved = null;
                Label start = here();
                if (r.value() != null && g.isAggregate(returnType)) {
                    // A struct or unique result goes where the caller said; a local unique returned is moved, not destroyed.
                    if (r.value() instanceof Name n && "local".equals(tb.nameKinds.get(n)) && lookup(n.name()) instanceof Stored st
                            && Checker.isUnique(returnType)) {
                        moved = st;
                        copy(retMem, st.mem(), returnType);
                    } else {
                        into(retMem, r.value(), returnType);
                    }
                } else if (r.value() != null) {
                    value(r.value(), returnType);
                }
                guardTemps(start, stmtTemps);
                flushTemps();
                jump("return", null, moved);
            }
            case Switch sw -> switchStatement(sw);
            case Assert a -> {
                Stmt check = tb.assertions.get(a);
                if (check != null) {
                    stmt(check); // with -Wassert: the if-and-throw it stands for
                }
            }
            case Try t when !t.resources().isEmpty() -> stmt(tb.desugared.get(t));
            case Try t -> tryStatement(t);
            case Throw t -> {
                value(t.value(), type(t.value()));
                c.athrow();
            }
            case Yield y -> {
                SwitchOut out = switchOuts.peek();
                if (out.into() != null) {
                    into(out.into(), y.value(), out.type());
                } else {
                    value(y.value(), out.type());
                    c.storeLocal(load(out.type()), out.slot());
                }
                jump("break", out.loop());
            }
            case DeleteStmt d -> deleteStatement(d);
        }
    }

    private String takeLabel() {
        String l = pendingLabel;
        pendingLabel = null;
        return l;
    }

    private Loop labeled(String name) {
        for (Loop l : loops) {
            if (name.equals(l.name())) {
                return l;
            }
        }
        throw new IllegalStateException("unknown label " + name);
    }

    /**
     * A break, continue or return (its value on the stack). Leaving try statements with finally
     * blocks goes through each one's pad, outside its protected code, which runs the finally block
     * and then goes on with the jump.
     */
    private void jump(String kind, Loop loop) {
        jump(kind, loop, null);
    }

    /** delete p (§24): destroys the T at p, then frees what new made; deleting null does nothing. */
    private void deleteStatement(DeleteStmt d) {
        AddrT a = (AddrT) type(d.target());
        Label start = here();
        value(d.target(), a);
        int ptr = c.allocateLocal(TypeKind.REFERENCE);
        c.astore(ptr);
        Label skip = c.newLabel();
        c.aload(ptr);
        c.ifnull(skip);
        if (g.needsDestroy(a.target())) {
            destroyValue(memAt(ptr, a.target()), a.target()); // one T (§16)
        }
        c.aload(ptr);
        c.invokestatic(JvmGen.PTR, "free", MethodTypeDesc.of(CD_void, JvmGen.PTR));
        c.labelBinding(skip);
        guardTemps(start, stmtTemps);
        flushTemps();
    }

    /** As jump(kind, loop); moved is a local unique a return hands to the caller, which isn't destroyed. */
    private void jump(String kind, Loop loop, Stored moved) {
        int crossing = kind.equals("return") ? fins.size() : fins.size() - loop.fins();
        if (crossing == 0) {
            switch (kind) {
                case "return" -> {
                    if (!returnsValue()) {
                        returnVoid();
                    } else {
                        c.return_(load(returnType));
                    }
                }
                case "break" -> c.goto_(loop.breakTo());
                default -> c.goto_(loop.continueTo());
            }
            return;
        }
        Fin f = fins.peek();
        if (kind.equals("return") && returnsValue()) {
            if (f.returnSlot < 0) {
                f.returnSlot = c.allocateLocal(kind(returnType));
            }
            c.storeLocal(load(returnType), f.returnSlot);
        }
        for (Exit e : f.exits) {
            if (e.kind().equals(kind) && e.loop() == loop && e.moved() == moved) {
                c.goto_(e.pad());
                return;
            }
        }
        Exit e = new Exit(c.newLabel(), kind, loop, moved);
        f.exits.add(e);
        c.goto_(e.pad());
    }

    /** A switch statement: the subject in a hidden local, then each case's tests in order (§41.3). */
    private void switchStatement(Switch sw) {
        Checker.SwitchInfo info = tb.switches.get(sw);
        openScope(new HashMap<>());
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
        loops.push(new Loop(end, null, takeLabel(), fins.size()));
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
        closeScope();
    }

    /** A switch expression (§41.3): as a switch statement, with each yield storing the value. */
    private Type switchExpr(SwitchExpr se, Type t) {
        return switchExpr(se, t, null);
    }

    /** As above; a struct value goes to dst. */
    private Type switchExpr(SwitchExpr se, Type t, Mem dst) {
        Checker.SwitchInfo info = tb.switchExprs.get(se);
        Type vt = concrete(t);
        int slot = dst != null ? -1 : c.allocateLocal(kind(vt));
        openScope(new HashMap<>());
        stmt(info.subject());
        Label end = c.newLabel();
        List<Label> starts = new ArrayList<>();
        Label otherwise = null;
        for (int i = 0; i < se.cases().size(); i++) {
            starts.add(c.newLabel());
            if (se.cases().get(i).isDefault()) {
                otherwise = starts.get(i);
            }
        }
        for (int i = 0; i < se.cases().size(); i++) {
            for (Expr test : info.tests().get(i)) {
                Label next = c.newLabel();
                branchFalse(test, next);
                c.goto_(starts.get(i));
                c.labelBinding(next);
            }
        }
        if (otherwise != null) {
            c.goto_(otherwise);
        } else {
            trap("no case of the switch expression matched"); // every enum constant has a case
        }
        Loop loop = new Loop(end, null, null, fins.size());
        loops.push(loop);
        switchOuts.push(new SwitchOut(loop, slot, vt, dst));
        for (int i = 0; i < se.cases().size(); i++) {
            Case cs = se.cases().get(i);
            c.labelBinding(starts.get(i));
            scoped(new Block(cs.pos(), cs.body()));
            if (cs.arrow()) {
                c.goto_(end);
            }
        }
        switchOuts.pop();
        loops.pop();
        c.labelBinding(end);
        closeScope();
        if (dst == null) {
            c.loadLocal(load(vt), slot);
        }
        return vt;
    }

    /**
     * try, catch and finally (§41.4). The catches catch Seal's exceptions by class; the finally
     * block runs on every way out: at the end of the body or a catch, through a pad for a jump out,
     * and through a handler for an exception. That handler takes Seal's exceptions only, so a fault
     * (a Java exception, as a VM trap) or System.exit stops the program without it, as on the VM.
     */
    private void tryStatement(Try t) {
        Block finBlock = t.finallyBlock();
        Fin fin = finBlock == null ? null : new Fin(finBlock, null);
        Label start = c.newLabel();
        Label bodyEnd = c.newLabel();
        Label after = c.newLabel();
        List<Label[]> guarded = new ArrayList<>();
        c.labelBinding(start);
        c.nop(); // so that the protected code is never empty
        if (fin != null) {
            fins.push(fin);
        }
        scoped(t.body());
        if (fin != null) {
            fins.pop();
        }
        c.labelBinding(bodyEnd);
        guarded.add(new Label[]{start, bodyEnd});
        if (fin != null) {
            scoped(finBlock);
        }
        c.goto_(after);
        if (t.catches().stream().map(tb.catchTypes::get).anyMatch(ct -> ct != null && ct.cls().java != null
                && Set.of("java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException").contains(ct.cls().java.name()))) {
            // A catch of Java's Throwable doesn't catch a fault or System.exit, which stop the program (docs/JAVA.md §10).
            Label fault = c.newLabel();
            c.exceptionCatch(start, bodyEnd, fault, JvmGen.TRAP);
            c.exceptionCatch(start, bodyEnd, fault, ClassDesc.of(JvmGen.RUNTIME + "Exit"));
            c.labelBinding(fault);
            c.athrow();
        }
        for (Catch cl : t.catches()) {
            ClassT ct = tb.catchTypes.get(cl);
            Label handler = c.newLabel();
            Label catchEnd = c.newLabel();
            c.exceptionCatch(start, bodyEnd, handler, g.instance(ct).desc);
            c.labelBinding(handler);
            openScope(new HashMap<>());
            int slot = c.allocateLocal(TypeKind.REFERENCE);
            c.astore(slot);
            scopes.peek().put(cl.name(), new Local(new HandleT(ct), slot));
            if (fin != null) {
                fins.push(fin);
            }
            stmt(cl.body());
            if (fin != null) {
                fins.pop();
            }
            closeScope();
            c.labelBinding(catchEnd);
            guarded.add(new Label[]{handler, catchEnd});
            if (fin != null) {
                scoped(finBlock);
            }
            c.goto_(after);
        }
        if (fin != null) {
            ClassInfo exception = ck.classNamed("Exception");
            if (exception != null && exception.builtin) {
                Label handler = c.newLabel();
                Label fault = c.newLabel();
                ClassDesc ed = g.instance(new ClassT(exception, List.of(), List.of(), 0)).desc;
                for (Label[] r : guarded) {
                    // A fault and System.exit stop the program at once, finally or not; Seal's exceptions and
                    // Java's run it on their way (docs/JAVA.md §4).
                    c.exceptionCatch(r[0], r[1], fault, JvmGen.TRAP);
                    c.exceptionCatch(r[0], r[1], fault, ClassDesc.of(JvmGen.RUNTIME + "Exit"));
                    c.exceptionCatch(r[0], r[1], handler, ed);
                    c.exceptionCatch(r[0], r[1], handler, ClassDesc.of("java.lang.Exception"));
                }
                c.labelBinding(fault);
                c.athrow();
                c.labelBinding(handler);
                int slot = c.allocateLocal(TypeKind.REFERENCE);
                c.astore(slot);
                scoped(finBlock);
                c.aload(slot);
                c.athrow();
            }
            // The pads: each jump out runs the finally block, then goes on, through any outer one.
            for (Exit e : fin.exits) {
                c.labelBinding(e.pad());
                scoped(finBlock);
                if (e.kind().equals("return") && returnsValue()) {
                    c.loadLocal(load(returnType), fin.returnSlot);
                }
                jump(e.kind(), e.loop(), e.moved());
            }
        }
        c.labelBinding(after);
    }

    private Var lookup(String name) {
        for (Map<String, Var> s : scopes) {
            Var v = s.get(name);
            if (v != null) {
                return v;
            }
        }
        throw new IllegalStateException("unknown local " + name);
    }

    /** Evaluates an expression for its side effects only. */
    private void effect(Expr e) {
        if (tb.rewritten.containsKey(e)) {
            if (e instanceof Assign a && a.target() instanceof Index ix) {
                // xs[i] += v through []=: xs[i] = xs[i] + v, with i evaluated once (§46).
                Local held = pin(ix.index());
                effect(tb.rewritten.get(e));
                if (held != null) {
                    pinned.remove(ix.index());
                }
                return;
            }
            effect(tb.rewritten.get(e));
            return;
        }
        Type et = tb.types.get(e);
        if (et != null && g.isAggregate(et) && !(e instanceof Assign)) {
            // A struct made only to be dropped (a call's result): made aside, and a unique destroyed after the statement.
            Mem t = temp(et);
            into(t, e, et);
            if (g.needsDestroy(et)) {
                madeTemp(et, t);
            }
            return;
        }
        switch (e) {
            case Assign a -> assign(a, false);
            case IncDec i -> incDec(i, false);
            default -> pop(value(e, null));
        }
    }

    private void pop(Type t) {
        if (t instanceof VoidT) {
            return;
        }
        if (kind(t).slotSize() == 2) {
            c.pop2();
        } else {
            c.pop();
        }
    }

    // ================================================================ values

    /**
     * Pushes e's value converted to {@code want} (in e's own type if null), and returns the type of
     * what it pushed. A value narrowed where it is stored or passed is converted as a
     * cast would (§41.6), and code the checker rewrote is generated in its place.
     */
    private Type value(Expr e, Type want) {
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

    private void implicitCast(Expr e, Type to) {
        Type from = type(e);
        if (from instanceof FloatConstT && to instanceof IntT) {
            double d = ((FloatV) ck.constantIn(tb, e)).value(); // int a = 2.5: toward zero, as (int)2.5
            intConstant(BigInteger.valueOf((long) d), to);
            return;
        }
        Type pushed = valueUncast(e, null);
        convert(pushed, to);
    }

    private Type valueUncast(Expr e, Type want) {
        Local early = pinned.get(e);
        if (early != null) {
            c.loadLocal(load(early.type()), early.slot());
            if (want != null) {
                widen(early.type(), want);
                return want;
            }
            return early.type();
        }
        if (tb.rewritten.containsKey(e)) {
            return value(tb.rewritten.get(e), want);
        }
        Pos outer = pos;
        pos = e.pos();
        try {
            Type t = type(e);
            if (t instanceof FloatConstT fc && fc.value() != null && want instanceof FixedT ft) {
                fixedConstant(fc.value(), ft); // exact, from the literal's digits, as on the VM
                return want;
            }
            if (t instanceof IntConstT || t instanceof FloatConstT
                    || (e instanceof Name n && "value".equals(tb.nameKinds.get(n)))) {
                Type as = want == null || want instanceof IntConstT || want instanceof FloatConstT || !isNumber(want)
                        ? concrete(t) : want;
                constant(ck.constantIn(tb, e), as, e.pos());
                if (want != null && !as.equals(want)) {
                    widen(as, want);
                    return want;
                }
                return as;
            }
            if (t instanceof StrConstT sc) {
                c.loadConstant(sc.value() != null ? sc.value() : ((StringLit) e).value());
                return want != null ? want : t;
            }
            if (g.isAggregate(t)) {
                if (t instanceof ArrayT at && isArray(want)) {
                    return fixedToArray(e, at, (ClassT) want);
                }
                throw new IllegalStateException("a struct used as a single value at " + e.pos() + ": " + t);
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
    private Type valueAt(Expr e, Type t, Type want) {
        switch (e) {
            case BoolLit b -> {
                c.loadConstant(b.value() ? 1 : 0);
                return t;
            }
            case NullLit n -> {
                c.aconst_null();
                return t;
            }
            case StringLit s -> {
                c.loadConstant(s.value());
                return t;
            }
            case This th -> {
                loadThis(th.pos());
                return thisValueType();
            }
            case Name n -> {
                return name(n, t);
            }
            case Select s -> {
                return select(s, t);
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
                return tb.elementSteps.containsKey(i) ? pinnedType(i) : type(i.target());
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
            case New n when t instanceof AddrT a -> {
                // new S() where an addr<S> is wanted: made in memory of its own (§24).
                c.loadConstant(Math.max(1, g.layouts.sizeOf(a.target())));
                c.invokestatic(JvmGen.PTR, "allocate", MethodTypeDesc.of(JvmGen.PTR, CD_long));
                int ptr = c.allocateLocal(TypeKind.REFERENCE);
                c.astore(ptr);
                c.aload(ptr);
                c.getfield(JvmGen.PTR, "block", JvmGen.BLOCK);
                int block = c.allocateLocal(TypeKind.REFERENCE);
                c.astore(block);
                construct(new Mem(block, -1, 0), tb.calls.get(n), n.args());
                c.aload(ptr);
                return t;
            }
            case New n -> {
                return newObject(n, t);
            }
            case NewArray n -> {
                // new<T>(count): count Ts of raw, zeroed memory (§24).
                Type element = ((AddrT) t).target();
                value(n.count(), Type.INT);
                c.i2l();
                c.loadConstant(g.layouts.stride(element));
                c.lmul();
                c.invokestatic(JvmGen.PTR, "allocate", MethodTypeDesc.of(JvmGen.PTR, CD_long));
                return t;
            }
            case Index ix when tb.calls.containsKey(ix) -> {
                return operator(ix);
            }
            case Index ix when type(ix.target()) instanceof ArrayT at && at.size() != null -> {
                loadFrom(elementMem(aggregateOf(ix.target()), at, ix.index()), t);
                return t;
            }
            case Index ix when javaElement(type(ix.target())) != null -> {
                // An element of a Java array (docs/JAVA.md §3.1).
                value(ix.target(), null);
                value(ix.index(), Type.INT);
                c.arrayLoad(JvmGen.javaKind(t));
                return t;
            }
            case Index ix when type(ix.target()) instanceof IntT it -> {
                // One bit of an integer (§40): checked against its width, as on the VM.
                value(ix.target(), it);
                value(ix.index(), Type.INT);
                c.loadConstant(width(it));
                c.invokestatic(JvmGen.INTS, "bounds", MethodTypeDesc.of(CD_int, CD_int, CD_int));
                if (load(it) == TypeKind.LONG) {
                    c.lushr();
                    c.l2i();
                } else {
                    c.iushr();
                }
                c.iconst_1();
                c.iand();
                return Type.BIT;
            }
            case Slice sl -> {
                IntT it = (IntT) type(sl.target());
                long from = ck.constantIn(tb, sl.from()).intValue().longValueExact();
                long to = ck.constantIn(tb, sl.to()).intValue().longValueExact();
                value(sl.target(), it);
                sliceOf(it, from, to, t);
                return t;
            }
            case InstanceOf io -> {
                return instanceOf(io);
            }
            case Lambda lam -> {
                return closure(lam);
            }
            case SwitchExpr se -> {
                return switchExpr(se, t);
            }
            case ArrayLit al when isArray(want != null ? want : t) -> {
                return arrayLiteral(al, (ClassT) (want != null ? want : t));
            }
            default -> throw unsupported(e.pos(), describe(e));
        }
    }

    private String describe(Expr e) {
        return switch (e) {
            case StringLit s -> "a string here";
            case New n -> "new here";
            case Lambda l -> "a lambda";
            case Index i -> "indexing";
            case Slice s -> "a bit slice";
            case InstanceOf i -> "instanceof";
            case ArrayLit a -> "an array literal here";
            case Unary u when u.op() == UnaryOp.DEREF || u.op() == UnaryOp.ADDRESS_OF -> "addresses";
            case SizeOf s -> "sizeof of this";
            default -> "this expression";
        };
    }

    // ------------------------------------------------------------ values in blocks

    /** Makes e's value, of a struct, unique or fixed-array type, in the place dst (§4). */
    private void into(Mem dst, Expr e, Type t) {
        if (tb.rewritten.containsKey(e)) {
            into(dst, tb.rewritten.get(e), t);
            return;
        }
        Pos outer = pos;
        pos = e.pos();
        try {
            if (tb.types.get(e) instanceof StrConstT sc && t instanceof ClassT ct && !isString(ct)) {
                // A literal into a program's own string class (without the library's).
                String text = constString(e);
                stringLiteral(dst, ct, text != null ? text : sc.value());
                return;
            }
            switch (e) {
                case New n when tb.calls.get(n) instanceof CallInfo ci && g.isAggregate(ci.owner()) -> {
                    zeroMem(dst, t);
                    construct(dst, ci, n.args());
                    return;
                }
                case Call call -> {
                    call(call, dst);
                    return;
                }
                case Binary b when tb.calls.containsKey(b) -> {
                    operator(b, dst);
                    return;
                }
                case Unary u when tb.calls.containsKey(u) -> {
                    operator(u, dst);
                    return;
                }
                case Cast cast when tb.calls.containsKey(cast) -> {
                    operator(cast, dst);
                    return;
                }
                case Index ix when tb.calls.containsKey(ix) -> {
                    operator(ix, dst);
                    return;
                }
                case Conditional cond -> {
                    Label otherwise = c.newLabel();
                    Label end = c.newLabel();
                    branchFalse(cond.cond(), otherwise);
                    into(dst, cond.then(), t);
                    c.goto_(end);
                    c.labelBinding(otherwise);
                    into(dst, cond.otherwise(), t);
                    c.labelBinding(end);
                    return;
                }
                case ArrayLit al when t instanceof ArrayT at -> {
                    long stride = g.layouts.stride(at.element());
                    for (int i = 0; i < al.elements().size(); i++) {
                        Expr el = al.elements().get(i);
                        if (g.isAggregate(at.element())) {
                            into(dst.at(i * stride), el, at.element());
                        } else {
                            push(dst.at(i * stride));
                            value(el, at.element());
                            storePushed(at.element());
                        }
                    }
                    return;
                }
                case SwitchExpr se -> {
                    switchExpr(se, t, dst);
                    return;
                }
                default -> {
                    Mem src = mem(e);
                    if (src == null) {
                        throw unsupported(e.pos(), describe(e) + " as a " + t);
                    }
                    copy(dst, src, t);
                }
            }
        } finally {
            pos = outer;
        }
    }

    /**
     * The place a struct-valued expression names: a variable, a field (of a struct, of a class
     * object, a static one), an element of a fixed array, `this`; or null if it names none (a call's
     * result, which is made somewhere instead).
     */
    private Mem mem(Expr e) {
        if (tb.rewritten.containsKey(e)) {
            return mem(tb.rewritten.get(e));
        }
        switch (e) {
            case Name n when "local".equals(tb.nameKinds.get(n)) -> {
                return lookup(n.name()) instanceof Stored st ? st.mem() : null;
            }
            case Name n when "field".equals(tb.nameKinds.get(n)) -> {
                MemberInfo m = tb.fieldOf.get(n);
                if (((Field) m.member).modifiers().isStatic()) {
                    return staticMem(m);
                }
                if (thisMem != null) {
                    return fieldMem(thisMem, k.owner(), m);
                }
                if (thisType == null) {
                    throw unsupported(n.pos(), "the field " + n.name() + " of " + thisValueType());
                }
                loadThis(n.pos());
                return objectFieldMem(thisType, m);
            }
            case Select s when tb.selected.get(s) instanceof MemberInfo m && m.member instanceof Field f -> {
                if (f.modifiers().isStatic()) {
                    return staticMem(m);
                }
                Type tt = type(s.target());
                if (tt instanceof HandleT h && h.target() instanceof ClassT ct) {
                    value(s.target(), null);
                    return objectFieldMem(ct, m);
                }
                if (tt instanceof ClassT ct && g.isAggregate(ct)) {
                    return fieldMem(aggregateOf(s.target()), ct, m);
                }
                return null;
            }
            case Index ix when type(ix.target()) instanceof ArrayT at && at.size() != null && !tb.calls.containsKey(ix) -> {
                return elementMem(aggregateOf(ix.target()), at, ix.index());
            }
            case This th when thisMem != null -> {
                return thisMem;
            }
            case Unary u when u.op() == UnaryOp.DEREF -> {
                return derefMem(u);
            }
            default -> {
                return null;
            }
        }
    }

    /** &p's place (§19): the T at the address p gives, checked as the VM checks a load or store. */
    private Mem derefMem(Unary u) {
        value(u.operand(), null);
        int ptr = c.allocateLocal(TypeKind.REFERENCE);
        c.astore(ptr);
        return memAt(ptr, type(u));
    }

    /** The place of a T at the address in a local: not null, inside its block, aligned (§22). */
    private Mem memAt(int ptr, Type t) {
        c.aload(ptr);
        c.loadConstant((int) Math.max(1, g.layouts.sizeOf(t)));
        c.loadConstant((int) g.layouts.alignOf(t));
        c.invokestatic(JvmGen.PTR, "at", MethodTypeDesc.of(JvmGen.BLOCK, JvmGen.PTR, CD_int, CD_int));
        int block = c.allocateLocal(TypeKind.REFERENCE);
        c.astore(block);
        c.aload(ptr);
        c.getfield(JvmGen.PTR, "off", CD_long);
        c.l2i();
        int off = c.allocateLocal(TypeKind.INT);
        c.istore(off);
        return new Mem(block, off, 0);
    }

    /**
     * *e (§19): the place e names, in a block. A value with no place (a call's result, an element
     * that [] gives) is copied into the frame first, and the copy's address is the result, as on
     * the VM.
     */
    private Mem addressed(Expr e) {
        if (tb.rewritten.containsKey(e)) {
            return addressed(tb.rewritten.get(e));
        }
        MemberInfo field = e instanceof Name n && "field".equals(tb.nameKinds.get(n)) ? tb.fieldOf.get(n)
                : e instanceof Select s ? tb.selected.get(s) : null;
        if (field != null && field.member instanceof Field f && f.modifiers().isStatic()) {
            ClassInfo cls = ck.declaringClassOf(field);
            g.takeAddress(cls, f);
            return staticMem(field);
        }
        Mem m = mem(e);
        if (m != null) {
            return m;
        }
        Place p = place(e);
        if (p instanceof MemPlace mp) {
            return mp.mem();
        }
        if (p != null) {
            throw unsupported(e.pos(), "the address of " + describe(e));
        }
        Type t = type(e);
        if (g.isAggregate(t)) {
            return aggregateOf(e);
        }
        Mem tmp = temp(t);
        push(tmp);
        value(e, t);
        storePushed(t);
        return tmp;
    }

    /**
     * A string literal made into a program's own `string` class, as on the VM (§56): its `size`,
     * and its `chars`, an array the characters are stored in or an address of memory holding them,
     * which the string borrows.
     */
    private void stringLiteral(Mem dst, ClassT t, String s) {
        Layouts.Layout l = g.layouts.of(t);
        MemberInfo size = null;
        MemberInfo data = null;
        for (MemberInfo m : t.cls().storedFields(t.header())) {
            String name = ((Field) m.member).name();
            if (name.equals("size")) {
                size = m;
            } else if (name.equals("chars")) {
                data = m;
            }
        }
        if (size == null || data == null) {
            throw unsupported(pos, "a string literal as a " + t + ", which has no `size` and `chars` fields,");
        }
        zeroMem(dst, t);
        Type dataType = l.types().get(data);
        Mem dataMem = dst.at(l.offsets().get(data));
        if (dataType instanceof ArrayT a && a.element() instanceof IntT ch) {
            long stride = g.layouts.stride(ch);
            for (int i = 0; i < s.length(); i++) {
                push(dataMem.at(i * stride));
                intConstant(BigInteger.valueOf(s.charAt(i)), ch);
                storePushed(ch);
            }
        } else if (dataType instanceof AddrT a && a.target() instanceof IntT ch) {
            long stride = g.layouts.stride(ch);
            newBlock(Math.max(1, s.length() * stride));
            int block = c.allocateLocal(TypeKind.REFERENCE);
            c.astore(block);
            for (int i = 0; i < s.length(); i++) {
                c.aload(block);
                c.loadConstant((int) (i * stride));
                c.loadConstant((long) s.charAt(i));
                c.loadConstant(width(ch));
                c.invokestatic(JvmGen.BLOCK, "store", MethodTypeDesc.of(CD_void, JvmGen.BLOCK, CD_int, CD_long, CD_int));
            }
            push(dataMem);
            c.aload(block);
            c.iconst_0();
            c.invokestatic(JvmGen.PTR, "of", MethodTypeDesc.of(JvmGen.PTR, JvmGen.BLOCK, CD_int));
            storePushed(dataType);
        } else {
            throw unsupported(pos, "a string literal as a " + t + ", whose `chars` is neither characters nor their address,");
        }
        Type sizeType = l.types().get(size);
        push(dst.at(l.offsets().get(size)));
        constant(new IntV(BigInteger.valueOf(s.length())), sizeType, pos);
        storePushed(sizeType);
    }

    /** A struct-valued expression's place: the one it names, or a new one in the frame with its value. */
    private Mem aggregateOf(Expr e) {
        Mem m = mem(e);
        if (m != null) {
            return m;
        }
        Type t = type(e);
        Mem tmp = temp(t);
        into(tmp, e, t);
        if (g.needsDestroy(t)) {
            madeTemp(t, tmp);
        }
        return tmp;
    }

    /** A fixed array where an array<T> is wanted (§21.3): a new one with the elements copied. */
    private Type fixedToArray(Expr e, ArrayT at, ClassT want) {
        Mem src = aggregateOf(e);
        Type element = at.element();
        long n = at.size().intValue().longValueExact();
        long stride = g.layouts.stride(element);
        if (g.isAggregate(element)) {
            arrayOfBlock(element, n);
            c.dup();
            int block = c.allocateLocal(TypeKind.REFERENCE);
            c.astore(block);
            copyRegion(new Mem(block, -1, 0), src, n * stride);
            return want;
        }
        c.loadConstant((int) n);
        newArray(element);
        for (long i = 0; i < n; i++) {
            c.dup();
            c.loadConstant((int) i);
            loadFrom(src.at(i * stride), element);
            c.arrayStore(kind(element));
        }
        return want;
    }

    private void copyRegion(Mem dst, Mem src, long bits) {
        push(dst);
        push(src);
        c.loadConstant((int) bits);
        c.invokestatic(JvmGen.BLOCK, "copy", MethodTypeDesc.of(CD_void, JvmGen.BLOCK, CD_int, JvmGen.BLOCK, CD_int, CD_int));
    }

    /** A new array<T> of n structs, as a block of n elements in a row (§8.1); pushed. */
    private void arrayOfBlock(Type element, long n) {
        c.new_(JvmGen.BLOCK);
        c.dup();
        c.loadConstant(n * g.layouts.stride(element));
        c.loadConstant((int) n);
        c.invokespecial(JvmGen.BLOCK, "<init>", MethodTypeDesc.of(CD_void, CD_long, CD_int));
    }

    /** Makes a struct with a constructor, in a zeroed place. */
    private void construct(Mem dst, CallInfo ci, List<Expr> args) {
        ClassT ct = ci.owner();
        MethodRef init = g.constructor(ct, ci.member(), ci.binders(), pos);
        push(dst);
        List<Type> params = ci.member() == null ? List.of() : ck.paramTypes(ct, ci.member(), ci.binders());
        pushArgs(params, args);
        JvmGen.invoke(c, init);
    }

    /** [1, 2, 3] made into an array<T>: a new Java array with the elements stored in it. */
    private Type arrayLiteral(ArrayLit al, ClassT t) {
        Type element = t.typeArgs().get(0);
        if (g.isAggregate(element)) {
            long stride = g.layouts.stride(element);
            arrayOfBlock(element, al.elements().size());
            int block = c.allocateLocal(TypeKind.REFERENCE);
            c.dup();
            c.astore(block);
            for (int i = 0; i < al.elements().size(); i++) {
                into(new Mem(block, -1, i * stride), al.elements().get(i), element);
            }
            return t;
        }
        c.loadConstant(al.elements().size());
        newArray(element);
        for (int i = 0; i < al.elements().size(); i++) {
            c.dup();
            c.loadConstant(i);
            value(al.elements().get(i), element);
            c.arrayStore(kind(element));
        }
        return t;
    }

    private Type name(Name n, Type t) {
        String kind = tb.nameKinds.get(n);
        if ("const".equals(kind)) {
            MemberInfo m = tb.fieldOf.get(n);
            constant(ck.constFieldValue(ck.declaringClassOf(m), (Field) m.member), concrete(t), n.pos());
            return concrete(t);
        }
        if ("local".equals(kind)) {
            Var v = lookup(n.name());
            loadVar(v);
            return v.type();
        }
        if ("field".equals(kind)) {
            MemberInfo m = tb.fieldOf.get(n);
            Field f = (Field) m.member;
            if (f.modifiers().isStatic()) {
                return getStatic(m, n.pos());
            }
            if (thisMem != null) {
                loadFrom(fieldMem(thisMem, k.owner(), m), t);
                return t;
            }
            loadThis(n.pos());
            return getField(thisValueType(), m, n.pos());
        }
        throw unsupported(n.pos(), "the name " + n.name() + " here");
    }

    private Type select(Select s, Type t) {
        MemberInfo m = tb.selected.get(s);
        if (!(m != null && m.member instanceof Field f)) {
            throw unsupported(s.pos(), "." + s.name() + " here");
        }
        if (f.modifiers().isConst()) {
            constant(ck.constFieldValue(ck.declaringClassOf(m), f), concrete(t), s.pos());
            return concrete(t);
        }
        if (ck.javaMember(f) != null) {
            // A Java field: ChronoUnit.DAYS through the class written, an object's through its type, as javac names them.
            ClassDesc fd = ClassDesc.ofDescriptor(ck.javaMember(f).descriptor());
            if (f.modifiers().isStatic()) {
                ClassInfo through = type(s.target()) instanceof ClassRefT cr ? cr.cls() : ck.declaringClassOf(m);
                c.getstatic(ClassDesc.of(through.java.name()), f.name(), fd);
            } else {
                Type target = value(s.target(), null);
                c.getfield(g.desc(target, s.pos()), f.name(), fd);
            }
            javaResult(t, fd, s.pos());
            return t;
        }
        if (f.modifiers().isStatic()) {
            return getStatic(m, s.pos());
        }
        Type tt = type(s.target());
        if (tt instanceof ClassT ct && g.isAggregate(ct)) {
            loadFrom(fieldMem(aggregateOf(s.target()), ct, m), t);
            return t;
        }
        Type target = value(s.target(), null);
        return getField(target, m, s.pos());
    }

    private Type getStatic(MemberInfo m, Pos at) {
        ClassInfo cls = ck.declaringClassOf(m);
        Field f = (Field) m.member;
        Type t = ck.staticFieldType(cls, f);
        if (ck.javaMember(f) != null) {
            c.getstatic(ClassDesc.of(cls.java.name()), f.name(), ClassDesc.ofDescriptor(ck.javaMember(f).descriptor()));
            return t;
        }
        if (g.inStaticBlock(cls, f)) {
            loadFrom(staticMem(m), t);
            return t;
        }
        c.getstatic(g.staticClass(cls).desc, f.name(), g.desc(t, at));
        return t;
    }

    private void putStatic(MemberInfo m, Pos at) {
        ClassInfo cls = ck.declaringClassOf(m);
        Field f = (Field) m.member;
        if (ck.javaMember(f) != null) {
            c.putstatic(ClassDesc.of(cls.java.name()), f.name(), ClassDesc.ofDescriptor(ck.javaMember(f).descriptor()));
            return;
        }
        c.putstatic(g.staticClass(cls).desc, f.name(), g.desc(ck.staticFieldType(cls, f), at));
    }

    /** A field of the object on the stack, of type `target`; a string's size and an array's len are their lengths. */
    private Type getField(Type target, MemberInfo m, Pos at) {
        Field f = (Field) m.member;
        if (javaElement(target) != null && f.name().equals("length")) {
            c.arraylength(); // a Java array's length, a JavaInt (docs/JAVA.md §3.1)
            return new JavaPrimT('I');
        }
        if (isString(target) && f.name().equals("size")) {
            c.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int));
            return Type.INT;
        }
        if (isArray(target) && f.name().equals("len")) {
            if (g.isAggregate(((ClassT) target).typeArgs().get(0))) {
                c.invokestatic(JvmGen.BLOCK, "count", MethodTypeDesc.of(CD_int, JvmGen.BLOCK));
            } else {
                c.arraylength();
            }
            return Type.INT;
        }
        if (!(target instanceof HandleT h) || !(h.target() instanceof ClassT ct)) {
            throw unsupported(at, "the field " + f.name() + " of " + target);
        }
        if (ck.javaMember(f) != null) {
            // A Java object's field, through the object's type, as javac names it (docs/JAVA.md §4).
            ClassDesc fd = ClassDesc.ofDescriptor(ck.javaMember(f).descriptor());
            c.getfield(g.desc(target, at), f.name(), fd);
            Type ft = ck.fieldTypeOf(ct, m);
            javaResult(ft, fd, at);
            return ft;
        }
        ClassT owner = g.declaringType(ct, m);
        Type ft = ck.fieldTypeOf(owner, m);
        c.getfield(g.instance(owner).desc, f.name(), g.desc(ft, at));
        return ft;
    }

    private void putField(Type target, MemberInfo m, Pos at) {
        Field f = (Field) m.member;
        if (!(target instanceof HandleT h) || !(h.target() instanceof ClassT ct)) {
            throw unsupported(at, "assigning the field " + f.name() + " of " + target);
        }
        if (ck.javaMember(f) != null) {
            c.putfield(g.desc(target, at), f.name(), ClassDesc.ofDescriptor(ck.javaMember(f).descriptor()));
            return;
        }
        ClassT owner = g.declaringType(ct, m);
        c.putfield(g.instance(owner).desc, f.name(), g.desc(ck.fieldTypeOf(owner, m), at));
    }

    // ------------------------------------------------------------ constants

    /** Pushes a compile-time value as type {@code as}. */
    private void constant(CVal v, Type as, Pos at) {
        switch (v) {
            case IntV i when as instanceof IntT -> intConstant(i.value(), as);
            case IntV i when as instanceof FixedT ft -> fixedConstant(new BigDecimal(i.value()), ft);
            case FloatV f when as instanceof FixedT ft -> fixedConstant(new BigDecimal(f.value()), ft);
            case IntV i when as instanceof FloatT -> c.loadConstant(i.value().floatValue());
            case IntV i when as instanceof DoubleT -> c.loadConstant(i.value().doubleValue());
            case FloatV f when as instanceof FloatT -> c.loadConstant(f.value().floatValue());
            case FloatV f when as instanceof DoubleT -> c.loadConstant(f.value());
            case BitV b -> c.loadConstant(b.value() ? 1 : 0);
            case CVal.StrV s -> c.loadConstant(s.value());
            default -> throw unsupported(at, "the constant " + v + " as " + as);
        }
    }

    /** A constant in a fixed-point type (§37.1): round(v × 2^M), wrapped to the type's width, as on the VM. */
    private void fixedConstant(BigDecimal v, FixedT t) {
        intConstant(Checker.fixedRaw(v, frac(t)), raw(t));
    }

    private static int frac(FixedT t) {
        return t.fracBits().intValue().intValueExact();
    }

    /** An integer constant wrapped to the type's width, as the VM's constants are. */
    private void intConstant(BigInteger x, Type as) {
        long bits = x.longValue();
        int w = width(as);
        if (w < 64) {
            bits = ((IntT) raw(as)).signed() ? (bits << (64 - w)) >> (64 - w) : bits & ((1L << w) - 1);
        }
        if (kind(as) == TypeKind.LONG) {
            c.loadConstant(bits);
        } else {
            c.loadConstant((int) bits);
        }
    }

    // ------------------------------------------------------------ conversions

    /**
     * A value where it meets another type without a cast (§41.6): an int<<8>> stored in an int, an
     * int added to a double, a Dog where an Animal goes. Between integers only the JVM
     * representation can change (int to long); references need nothing.
     */
    private void widen(Type from, Type to) {
        if (from instanceof JavaPrimT f && to instanceof JavaPrimT t && !f.equals(t)) {
            javaWiden(f, t); // a JavaInt where Java wants a long (docs/JAVA.md §4)
            return;
        }
        if (from.equals(to) || !isNumber(from) || !isNumber(to)) {
            return;
        }
        if (from instanceof IntT f && to instanceof IntT) {
            if (load(f) == TypeKind.INT && kind(to) == TypeKind.LONG) {
                toLong(f);
            }
            return;
        }
        convert(from, to);
    }

    /** An int-held integer as a long: sign-extended if signed, zero-extended otherwise. */
    private void toLong(IntT from) {
        c.i2l();
        if (fullUnsigned(from)) {
            c.loadConstant(0xffffffffL);
            c.land();
        }
    }

    /** Converts between numeric types, as a cast does (§41.1); the value is on the stack. */
    private void convert(Type from, Type to) {
        if (from.equals(to)) {
            return;
        }
        boolean fromFloat = from instanceof FloatT || from instanceof DoubleT;
        boolean toFloat = to instanceof FloatT || to instanceof DoubleT;
        if (from instanceof FixedT || to instanceof FixedT) {
            fixedCast(from, to, fromFloat, toFloat);
            return;
        }
        if (to instanceof AddrT && (from instanceof AddrT || from instanceof NullT)) {
            return; // one Ptr, whatever it points at (§23)
        }
        if (from instanceof IntT f && to instanceof IntT t) {
            TypeKind fk = load(f);
            TypeKind tk = load(t);
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
            if (load(f) == TypeKind.INT && !fullUnsigned(f)) {
                c.i2d();
            } else {
                if (load(f) == TypeKind.INT) {
                    toLong(f);
                }
                if (fullUnsigned(f) && width(f) == 64) {
                    c.invokestatic(JvmGen.INTS, "unsignedToDouble", MethodTypeDesc.of(CD_double, CD_long));
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
            if (load(t) == TypeKind.INT) {
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
        if (to instanceof HandleT th && th.target() instanceof ClassT tc && (from instanceof HandleT || from instanceof NullT)) {
            if (from instanceof HandleT) {
                c.checkcast(g.desc(to, pos));
            }
            return;
        }
        if (isString(from) && isString(to)) {
            return; // both shapes of string are Strings
        }
        throw unsupported(pos, "converting " + from + " to " + to);
    }

    /** Casts involving fixed point (§37.1), as the VM does them; the value is on the stack. */
    private void fixedCast(Type from, Type to, boolean fromFloat, boolean toFloat) {
        if (from instanceof FixedT ff && to instanceof FixedT tf) {
            // A change of scale rounds to nearest; then the value wraps to the new width.
            asLong(ff);
            rescale(frac(tf) - frac(ff), ff.signed(), true);
            fromLong(tf);
        } else if (from instanceof IntT fi && to instanceof FixedT tf) {
            asLong(fi);
            rescale(frac(tf), fi.signed() && !fi.isChar(), true);
            fromLong(tf);
        } else if (from instanceof FixedT ff && to instanceof IntT ti) {
            // As float to int: toward zero, then wrapped.
            asLong(ff);
            rescale(-frac(ff), ff.signed(), false);
            fromLong(ti);
        } else if (from instanceof FixedT ff && toFloat) {
            convert(raw(ff), Type.DOUBLE);
            c.loadConstant(Math.scalb(1.0, -frac(ff)));
            c.dmul();
            if (to instanceof FloatT) {
                c.d2f();
            }
        } else if (fromFloat && to instanceof FixedT tf) {
            // Scaled, then rounded to nearest (ties up) into a long, as the VM's F2IR.
            if (from instanceof FloatT) {
                c.f2d();
            }
            c.loadConstant(Math.scalb(1.0, frac(tf)));
            c.dmul();
            c.loadConstant(0.5);
            c.dadd();
            c.invokestatic(ClassDesc.of("java.lang.Math"), "floor", MethodTypeDesc.of(CD_double, CD_double));
            c.d2l();
            fromLong(tf);
        } else {
            throw unsupported(pos, "converting " + from + " to " + to);
        }
    }

    /** The number on the stack, held as an int, as a long: sign-extended if signed. */
    private void asLong(Type t) {
        if (load(t) == TypeKind.INT) {
            toLong((IntT) raw(t));
        }
    }

    /** A long on the stack as a value of t: narrowed to an int if t is one, and wrapped to t's width. */
    private void fromLong(Type t) {
        if (load(t) == TypeKind.INT) {
            c.l2i();
        }
        normalize(t);
    }

    /** The long on the stack times 2^shift; a negative shift divides, rounding to nearest or toward zero. */
    private void rescale(int shift, boolean signed, boolean nearest) {
        if (shift == 0) {
            return;
        }
        if (shift > 0 && shift < 64) {
            c.loadConstant(shift);
            c.lshl();
            return;
        }
        c.loadConstant(shift);
        c.loadConstant(signed ? 1 : 0);
        c.loadConstant(nearest ? 1 : 0);
        c.invokestatic(JvmGen.FIXED_POINT, "rescale", MethodTypeDesc.of(CD_long, CD_long, CD_int, CD_boolean, CD_boolean));
    }

    /** Whether every value of integer type f is a value of t, so converting needs no wrapping. */
    private static boolean fits(IntT f, IntT t) {
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
    private void normalize(Type t) {
        if (!(raw(t) instanceof IntT it)) {
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

    private Type cast(Cast cast) {
        if (tb.calls.containsKey(cast)) {
            return operator(cast); // the operand's @overload(Operator.CAST) method (§46)
        }
        Type to = type(cast);
        Type from = type(cast.operand());
        if (Checker.isJavaValue(from) && !from.equals(to)) {
            javaCast(cast.operand(), from, to);
            return to;
        }
        if (isString(to) && from instanceof HandleT) {
            // An object of a Seal class extending a Java one: Java's toString() (docs/JAVA.md §5).
            value(cast.operand(), from);
            c.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, CD_Object));
            return to;
        }
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

    private Type unary(Unary u, Type t) {
        if (tb.calls.containsKey(u)) {
            return operator(u);
        }
        switch (u.op()) {
            case NEG -> {
                value(u.operand(), t);
                switch (load(t)) {
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
            case DEREF -> {
                Type pointed = type(u);
                loadFrom(derefMem(u), pointed);
                return pointed;
            }
            case ADDRESS_OF -> {
                push(addressed(u.operand()));
                c.invokestatic(JvmGen.PTR, "of", MethodTypeDesc.of(JvmGen.PTR, JvmGen.BLOCK, CD_int));
                return type(u);
            }
        }
        throw new IllegalStateException();
    }

    private Type binary(Binary b, Type t) {
        if (tb.calls.containsKey(b)) {
            return operator(b);
        }
        String joined = constString(b);
        if (joined != null) {
            c.loadConstant(joined); // literals joined with +, one string as on the VM
            return t;
        }
        Type lt = type(b.left());
        Type rt = type(b.right());
        if (lt instanceof JavaPrimT lj && rt instanceof JavaPrimT rj) {
            return javaBinary(b, lj, rj, (JavaPrimT) t);
        }
        if ((b.op() == BinaryOp.ADD || b.op() == BinaryOp.SUB) && (lt instanceof AddrT || rt instanceof AddrT)) {
            if (lt instanceof AddrT && rt instanceof AddrT) {
                // p - q: how many bits apart (§20).
                value(b.left(), lt);
                value(b.right(), rt);
                c.invokestatic(JvmGen.PTR, "diff", MethodTypeDesc.of(CD_long, JvmGen.PTR, JvmGen.PTR));
                fromLong(t);
                return t;
            }
            // p + n, n + p and p - n: bits past p, in the same block.
            boolean addrLeft = lt instanceof AddrT;
            value(addrLeft ? b.left() : b.right(), null);
            bitCount(addrLeft ? b.right() : b.left());
            if (b.op() == BinaryOp.SUB) {
                c.lneg();
            }
            c.invokestatic(JvmGen.PTR, "add", MethodTypeDesc.of(JvmGen.PTR, JvmGen.PTR, CD_long));
            return t;
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
    private void shift(Type t) {
        c.loadConstant(width(t));
        c.loadConstant(((IntT) raw(t)).signed() ? 1 : 0);
        ClassDesc v = kind(t) == TypeKind.LONG ? CD_long : CD_int;
        c.invokestatic(JvmGen.INTS, "shift", MethodTypeDesc.of(v, v, CD_int, CD_int, CD_boolean));
    }

    /** Both operands of type t on the stack: the result, normalized to t. */
    private void arithmetic(BinaryOp op, Type t, Pos at) {
        if (t instanceof FixedT ft) {
            if (op == BinaryOp.MUL || op == BinaryOp.DIV) {
                // The exact product or quotient, rounded to nearest (§37.1), then wrapped.
                ClassDesc v = load(ft) == TypeKind.LONG ? CD_long : CD_int;
                c.loadConstant(frac(ft));
                c.loadConstant(ft.signed() ? 1 : 0);
                c.invokestatic(JvmGen.FIXED_POINT, op == BinaryOp.MUL ? "mul" : "div",
                        MethodTypeDesc.of(CD_long, v, v, CD_int, CD_boolean));
                fromLong(ft);
                return;
            }
            t = raw(ft); // + and - (and %, << on the bits) are its integer's
        }
        TypeKind k2 = load(t);
        if (t instanceof BitT) {
            switch (op) {
                case BIT_AND -> c.iand();
                case BIT_OR -> c.ior();
                case BIT_XOR -> c.ixor();
                default -> throw unsupported(at, "operator " + op.symbol + " on bit");
            }
            return;
        }
        if (k2 == TypeKind.FLOAT || k2 == TypeKind.DOUBLE) {
            boolean d = k2 == TypeKind.DOUBLE;
            switch (op) {
                case ADD -> { if (d) c.dadd(); else c.fadd(); }
                case SUB -> { if (d) c.dsub(); else c.fsub(); }
                case MUL -> { if (d) c.dmul(); else c.fmul(); }
                case POW -> {
                    ClassDesc v = d ? CD_double : CD_float;
                    c.invokestatic(JvmGen.INTS, "pow", MethodTypeDesc.of(v, v, v));
                }
                case DIV -> { if (d) c.ddiv(); else c.fdiv(); }
                default -> throw unsupported(at, "operator " + op.symbol + " on " + t);
            }
            return;
        }
        boolean l = k2 == TypeKind.LONG;
        boolean unsignedDivision = fullUnsigned(t);
        switch (op) {
            case ADD -> { if (l) c.ladd(); else c.iadd(); }
            case SUB -> { if (l) c.lsub(); else c.isub(); }
            case MUL -> { if (l) c.lmul(); else c.imul(); }
            case POW -> {
                ClassDesc v = l ? CD_long : CD_int;
                c.loadConstant(raw(t) instanceof IntT it && it.signed() && !it.isChar() ? 1 : 0);
                c.invokestatic(JvmGen.INTS, "pow", MethodTypeDesc.of(v, v, v, CD_boolean));
            }
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

    private void unsignedOp(String name, boolean isLong) {
        ClassDesc v = isLong ? CD_long : CD_int;
        c.invokestatic(isLong ? CD_Long : CD_Integer, name, MethodTypeDesc.of(v, v, v));
    }

    /** The type both operands of a comparison are converted to. */
    private Type operandType(Type l, Type r) {
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
    private void branchFalse(Expr cond, Label no) {
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
                Type lt = type(b.left());
                Type rt = type(b.right());
                if (lt instanceof AddrT || rt instanceof AddrT) {
                    // Addresses compare by where they point (§20); null is the lowest.
                    value(b.left(), null);
                    value(b.right(), null);
                    c.invokestatic(JvmGen.PTR, "compare", MethodTypeDesc.of(CD_int, JvmGen.PTR, JvmGen.PTR));
                    jumpUnless(b.op(), no);
                    return;
                }
                if (isReference(lt) || isReference(rt)) {
                    referenceCompare(b, lt, rt, no);
                    return;
                }
                Type ot = operandType(lt, rt);
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

    /** An integer added to an address, as a long: sign-extended if signed, as the VM's registers hold it. */
    private void bitCount(Expr e) {
        Type it = type(e);
        if (it instanceof IntT) {
            value(e, it);
            asLong(it);
        } else {
            value(e, new IntT(CVal.of(64), true));
        }
    }

    /** The text of a string literal, or of literals joined with +; null for anything else. */
    private String constString(Expr e) {
        if (e instanceof StringLit sl) {
            return sl.value();
        }
        if (e instanceof Binary b && b.op() == BinaryOp.ADD && type(b.left()) instanceof StrConstT
                && type(b.right()) instanceof StrConstT) {
            String l = constString(b.left());
            String r = constString(b.right());
            return l == null || r == null ? null : l + r;
        }
        return null;
    }

    private static boolean isReference(Type t) {
        return t instanceof HandleT || t instanceof NullT || isValue(t);
    }

    /** a == b and a != b on handles: the same object (§31), or null. */
    private void referenceCompare(Binary b, Type lt, Type rt, Label no) {
        boolean eq = b.op() == BinaryOp.EQ;
        if (b.op() != BinaryOp.EQ && b.op() != BinaryOp.NE) {
            throw unsupported(b.pos(), "operator " + b.op().symbol + " on " + lt);
        }
        if (rt instanceof NullT || lt instanceof NullT) {
            value(rt instanceof NullT ? b.left() : b.right(), null);
            if (eq) {
                c.ifnonnull(no);
            } else {
                c.ifnull(no);
            }
            return;
        }
        value(b.left(), null);
        value(b.right(), null);
        if (eq) {
            c.if_acmpne(no);
        } else {
            c.if_acmpeq(no);
        }
    }

    private static boolean isComparison(BinaryOp op) {
        return op == BinaryOp.EQ || op == BinaryOp.NE || op == BinaryOp.LT || op == BinaryOp.GT
                || op == BinaryOp.LE || op == BinaryOp.GE;
    }

    /** Two operands of type t on the stack: jumps to {@code no} unless {@code op} holds between them. */
    private void compareFalse(BinaryOp op, Type t, Label no) {
        TypeKind k2 = load(t);
        if (k2 == TypeKind.FLOAT || k2 == TypeKind.DOUBLE) {
            // NaN compares false either way: fcmpg makes it "greater" for < and <=, fcmpl "less" for the rest.
            boolean greaterOnNaN = op == BinaryOp.LT || op == BinaryOp.LE;
            if (k2 == TypeKind.DOUBLE) {
                if (greaterOnNaN) c.dcmpg(); else c.dcmpl();
            } else {
                if (greaterOnNaN) c.fcmpg(); else c.fcmpl();
            }
            jumpUnless(op, no);
            return;
        }
        if (fullUnsigned(t)) {
            ClassDesc v = k2 == TypeKind.LONG ? CD_long : CD_int;
            c.invokestatic(k2 == TypeKind.LONG ? CD_Long : CD_Integer, "compareUnsigned", MethodTypeDesc.of(CD_int, v, v));
            jumpUnless(op, no);
            return;
        }
        if (k2 == TypeKind.LONG) {
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
    private void jumpUnless(BinaryOp op, Label no) {
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

    // ================================================================ places

    /**
     * Somewhere a value can be stored: a variable, a field (of an object pushed first) or a static
     * field. {@link #base} pushes what the place needs (nothing, or the object); {@link #get} and
     * {@link #put} use it up.
     */
    private sealed interface Place permits LocalPlace, FieldPlace, StaticPlace, MemPlace, JavaElementPlace {
        Type type();
    }

    /** A number, bit or reference in a block (§4.1): a struct's field, an element of a fixed array. */
    private record MemPlace(Mem mem, Type type) implements Place {
    }

    private record LocalPlace(Local local) implements Place {
        public Type type() {
            return local.type();
        }
    }

    private record FieldPlace(Expr object, Type objectType, MemberInfo field, Type type) implements Place {
    }

    private record StaticPlace(MemberInfo field, Type type) implements Place {
    }

    /** An element of a Java array (docs/JAVA.md §3.1): the array and the index are its base. */
    private record JavaElementPlace(Expr array, Expr index, Type type) implements Place {
    }

    /** The place an expression names, or null if it isn't one (a call's result, say). */
    private Place place(Expr e) {
        if (tb.rewritten.containsKey(e)) {
            return place(tb.rewritten.get(e));
        }
        switch (e) {
            case Name n when "local".equals(tb.nameKinds.get(n)) -> {
                Var v = lookup(n.name());
                if (v instanceof Local l) {
                    return new LocalPlace(l);
                }
                if (v instanceof Stored st && !g.isAggregate(st.type())) {
                    return new MemPlace(st.mem(), st.type());
                }
                return null;
            }
            case Name n when "field".equals(tb.nameKinds.get(n)) -> {
                MemberInfo m = tb.fieldOf.get(n);
                if (((Field) m.member).modifiers().isStatic()) {
                    return staticPlace(m);
                }
                if (thisMem != null) {
                    return new MemPlace(fieldMem(thisMem, k.owner(), m), type(n));
                }
                return new FieldPlace(null, thisValueType(), m, type(n));
            }
            case Select s when tb.selected.get(s) instanceof MemberInfo m && m.member instanceof Field f
                    && !f.modifiers().isConst() -> {
                if (f.modifiers().isStatic()) {
                    return staticPlace(m);
                }
                if (type(s.target()) instanceof ClassT ct && g.isAggregate(ct)) {
                    return new MemPlace(fieldMem(aggregateOf(s.target()), ct, m), type(s));
                }
                return new FieldPlace(s.target(), type(s.target()), m, type(s));
            }
            case Index ix when type(ix.target()) instanceof ArrayT at && at.size() != null && !tb.calls.containsKey(ix) -> {
                return new MemPlace(elementMem(aggregateOf(ix.target()), at, ix.index()), type(ix));
            }
            case Index ix when javaElement(type(ix.target())) != null -> {
                return new JavaElementPlace(ix.target(), ix.index(), type(ix));
            }
            case This th when thisLocal != null -> {
                return new LocalPlace(thisLocal);
            }
            case Unary u when u.op() == UnaryOp.DEREF -> {
                return new MemPlace(derefMem(u), type(u));
            }
            default -> {
                return null;
            }
        }
    }

    /** A static field's place: a JVM static field, or a place in its class's static block. */
    private Place staticPlace(MemberInfo m) {
        ClassInfo cls = ck.declaringClassOf(m);
        Field f = (Field) m.member;
        Type t = ck.staticFieldType(cls, f);
        return g.inStaticBlock(cls, f) ? new MemPlace(staticMem(m), t) : new StaticPlace(m, t);
    }

    private int baseSize(Place p) {
        return p instanceof FieldPlace ? 1 : p instanceof MemPlace || p instanceof JavaElementPlace ? 2 : 0;
    }

    private void base(Place p) {
        if (p instanceof MemPlace mp) {
            push(mp.mem());
        }
        if (p instanceof JavaElementPlace j) {
            value(j.array(), null);
            value(j.index(), Type.INT);
        }
        if (p instanceof FieldPlace f) {
            if (f.object() == null) {
                loadThis(pos);
            } else {
                value(f.object(), null);
            }
        }
    }

    private void get(Place p) {
        switch (p) {
            case MemPlace mp -> loadPushed(mp.type());
            case LocalPlace l -> c.loadLocal(load(l.type()), l.local().slot());
            case FieldPlace f -> getField(f.objectType(), f.field(), pos);
            case JavaElementPlace j -> c.arrayLoad(JvmGen.javaKind(j.type()));
            case StaticPlace s -> getStatic(s.field(), pos);
        }
    }

    private void put(Place p) {
        switch (p) {
            case MemPlace mp -> storePushed(mp.type());
            case LocalPlace l -> c.storeLocal(load(l.type()), l.local().slot());
            case FieldPlace f -> putField(f.objectType(), f.field(), pos);
            case JavaElementPlace j -> c.arrayStore(JvmGen.javaKind(j.type()));
            case StaticPlace s -> putStatic(s.field(), pos);
        }
    }

    private void dupBase(Place p) {
        if (baseSize(p) == 1) {
            c.dup();
        } else if (baseSize(p) == 2) {
            c.dup2();
        }
    }

    /** With the base and the value on the stack, keeps a copy of the value below the base. */
    private void keepUnderBase(Place p, Type value) {
        boolean wide = kind(value).slotSize() == 2;
        switch (baseSize(p)) {
            case 0 -> { if (wide) c.dup2(); else c.dup(); }
            case 1 -> { if (wide) c.dup2_x1(); else c.dup_x1(); }
            default -> { if (wide) c.dup2_x2(); else c.dup_x2(); }
        }
    }

    private Place target(Expr target) {
        Place p = place(target);
        if (p == null) {
            throw unsupported(target.pos(), "assigning " + describe(target));
        }
        return p;
    }

    /** An assignment; with {@code keep}, its value stays on the stack. */
    private void assign(Assign a, boolean keep) {
        if (tb.calls.containsKey(a)) {
            assignOperator(a, keep);
            return;
        }
        Type t = type(a.target());
        if (g.isAggregate(t)) {
            if (keep) {
                throw unsupported(a.pos(), "the value of an assignment of a " + t);
            }
            Mem dst = mem(a.target());
            if (dst == null) {
                throw unsupported(a.target().pos(), "assigning " + describe(a.target()));
            }
            if (g.needsDestroy(t) || a.value() instanceof ArrayLit) {
                // Made aside first: the old value is destroyed, and `a = [a[1], a[0]]` reads a while the new one is made.
                Mem fresh = temp(t);
                into(fresh, a.value(), t);
                destroyValue(dst, t);
                copy(dst, fresh, t);
            } else {
                into(dst, a.value(), t);
            }
            return;
        }
        if (a.target() instanceof Index ix && type(ix.target()) instanceof IntT it) {
            assignBit(a, ix, it, keep);
            return;
        }
        if (a.target() instanceof Slice sl) {
            assignSlice(a, sl, keep);
            return;
        }
        Place p = target(a.target());
        base(p);
        if (a.op() == null) {
            value(a.value(), t);
        } else if (t instanceof AddrT) {
            // p += n, p -= n (§20).
            dupBase(p);
            get(p);
            bitCount(a.value());
            if (a.op() == BinaryOp.SUB) {
                c.lneg();
            }
            c.invokestatic(JvmGen.PTR, "add", MethodTypeDesc.of(JvmGen.PTR, JvmGen.PTR, CD_long));
        } else {
            dupBase(p);
            get(p);
            if (a.op() == BinaryOp.SHIFT) {
                value(a.value(), Type.INT);
                shift(t);
            } else if (t instanceof JavaPrimT jt) {
                // In the type Java promotes to, then back to the target's, as Java's b += c is b = (byte)(b + c).
                JavaPrimT wide = tb.narrowed.get(a) instanceof JavaPrimT w ? w : jt;
                javaConvert(jt, wide);
                value(a.value(), null);
                javaConvert((JavaPrimT) type(a.value()), wide);
                javaOperate(a.op(), wide, a.pos());
                javaConvert(wide, jt);
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
            keepUnderBase(p, t);
        }
        put(p);
    }

    /** The type xs[i]++ through []= leaves: the element's, as its read gives it. */
    private Type pinnedType(IncDec i) {
        Assign step = tb.elementSteps.get(i);
        Expr next = step.value();
        return type(((Binary) (next instanceof Cast cast ? cast.operand() : next)).left());
    }

    /** Evaluates a value now, into a temporary, so that rewritten code using it twice runs it once. */
    private Local pin(Expr e) {
        Type own = type(e);
        if (own instanceof IntConstT || own instanceof FloatConstT) {
            return null; // a constant gives the same value each time, in whatever type the use wants
        }
        Type t = valueUncast(e, null);
        Local l = new Local(t, c.allocateLocal(kind(t)));
        c.storeLocal(load(t), l.slot());
        pinned.put(e, l);
        return l;
    }

    private static long mask(long bits) {
        return bits >= 64 ? -1L : (1L << bits) - 1;
    }

    /** With an integer of type it on the stack: bits from (inclusive) to to (exclusive), as type st. */
    private void sliceOf(IntT it, long from, long to, Type st) {
        boolean wide = load(it) == TypeKind.LONG;
        c.loadConstant((int) from);
        if (wide) {
            c.lushr();
            c.loadConstant(mask(to - from));
            c.land();
            if (load(st) == TypeKind.INT) {
                c.l2i();
            }
        } else {
            c.iushr();
            c.loadConstant((int) mask(to - from));
            c.iand();
            if (load(st) == TypeKind.LONG) {
                c.i2l();
            }
        }
    }

    /** x[i] = b, or x[i] op= b: one bit of an integer, read, changed and written back (§40). */
    private void assignBit(Assign a, Index ix, IntT it, boolean keep) {
        boolean wide = load(it) == TypeKind.LONG;
        Place p = target(ix.target());
        base(p);
        dupBase(p);
        get(p);
        value(ix.index(), Type.INT);
        c.loadConstant(width(it));
        c.invokestatic(JvmGen.INTS, "bounds", MethodTypeDesc.of(CD_int, CD_int, CD_int));
        int i = c.allocateLocal(TypeKind.INT);
        c.istore(i);
        if (a.op() == null) {
            value(a.value(), Type.BIT);
        } else {
            if (wide) c.dup2(); else c.dup();
            c.iload(i);
            if (wide) {
                c.lushr();
                c.l2i();
            } else {
                c.iushr();
            }
            c.iconst_1();
            c.iand();
            value(a.value(), Type.BIT);
            arithmetic(a.op(), Type.BIT, a.pos());
        }
        int bit = c.allocateLocal(TypeKind.INT);
        c.istore(bit);
        if (wide) {
            c.lconst_1();
            c.iload(i);
            c.lshl();
            c.loadConstant(-1L);
            c.lxor();
            c.land();
            c.iload(bit);
            c.i2l();
            c.iload(i);
            c.lshl();
            c.lor();
        } else {
            c.iconst_1();
            c.iload(i);
            c.ishl();
            c.iconst_m1();
            c.ixor();
            c.iand();
            c.iload(bit);
            c.iload(i);
            c.ishl();
            c.ior();
        }
        normalize(it);
        put(p);
        if (keep) {
            c.iload(bit);
        }
    }

    /** x[a..b] = v, or x[a..b] op= v: a field of bits, read, changed and written back (§40). */
    private void assignSlice(Assign a, Slice sl, boolean keep) {
        IntT it = (IntT) type(sl.target());
        Type st = type(sl);
        boolean wide = load(it) == TypeKind.LONG;
        long from = ck.constantIn(tb, sl.from()).intValue().longValueExact();
        long to = ck.constantIn(tb, sl.to()).intValue().longValueExact();
        long field = mask(to - from) << from;
        Place p = target(sl.target());
        base(p);
        dupBase(p);
        get(p);
        if (a.op() == null) {
            value(a.value(), st);
        } else {
            if (wide) c.dup2(); else c.dup();
            sliceOf(it, from, to, st);
            Type wideT = tb.narrowed.get(a);
            if (wideT == null) {
                value(a.value(), st);
                arithmetic(a.op(), st, a.pos());
            } else {
                widen(st, wideT);
                value(a.value(), wideT);
                arithmetic(a.op(), wideT, a.pos());
                convert(wideT, st);
            }
        }
        int v = c.allocateLocal(kind(st));
        c.storeLocal(load(st), v);
        c.loadLocal(load(st), v);
        if (wide && load(st) == TypeKind.INT) {
            toLong((IntT) st);
        } else if (!wide && load(st) == TypeKind.LONG) {
            c.l2i();
        }
        c.loadConstant((int) from);
        if (wide) {
            c.lshl();
            c.loadConstant(field);
            c.land();
        } else {
            c.ishl();
            c.loadConstant((int) field);
            c.iand();
        }
        // The old value without the field, joined with the new field: the new field is on top.
        if (wide) {
            c.dup2_x2();
            c.pop2();
            c.loadConstant(~field);
            c.land();
            c.lor();
        } else {
            c.swap();
            c.loadConstant((int) ~field);
            c.iand();
            c.ior();
        }
        normalize(it);
        put(p);
        if (keep) {
            c.loadLocal(load(st), v);
        }
    }

    /** `++x` gives the new value, `x++` the old one (§41.5); with {@code keep} it stays on the stack. */
    private void incDec(IncDec i, boolean keep) {
        Assign step = tb.elementSteps.get(i);
        if (step != null) {
            // xs[i]++ through []: reads xs[i] once with [], then stores the result with []=.
            Index ix = (Index) step.target();
            Expr next = step.value();   // xs[i] + 1, or (char)(xs[i] + 1)
            Expr read = ((Binary) (next instanceof Cast cast ? cast.operand() : next)).left();
            pin(ix.index());
            Local old = pin(read);
            Local now = pin(next);
            assign(step, false);
            pinned.remove(ix.index());
            pinned.remove(read);
            pinned.remove(next);
            if (keep) {
                Local result = i.prefix() ? now : old;
                c.loadLocal(load(result.type()), result.slot());
            }
            return;
        }
        Type t = type(i.target());
        Place p = target(i.target());
        base(p);
        dupBase(p);
        get(p);
        if (keep && !i.prefix()) {
            keepUnderBase(p, t);
        }
        Type st = t instanceof JavaPrimT j ? j.sealType() : t; // a JavaByte steps as an int<<8>>, wrapping as Java's byte
        constant(new IntV(BigInteger.ONE), st, i.pos());
        arithmetic(i.op(), st, i.pos());
        if (keep && i.prefix()) {
            keepUnderBase(p, t);
        }
        put(p);
    }

    // ================================================================ calls

    private Type call(Call call) {
        return call(call, null);
    }

    /** A call; one returning a struct makes it in dst (a new place in the frame if null). */
    private Type call(Call call, Mem dst) {
        CallInfo ci = tb.calls.get(call);
        if (ci == null) {
            throw unsupported(call.pos(), "this call");
        }
        if (ck.isRawAssembly(ci)) {
            return Type.VOID; // assembly is for a native compiler (§42.3)
        }
        Checker.JavaCall java = tb.javaCalls.get(call);
        if (java != null) {
            return javaCall(call, ci, java);
        }
        Pos at = call.pos();
        switch (ci.kind()) {
            case STATIC -> {
                return invokeStatic(ci, call.args(), at, dst);
            }
            case SUPER_CONSTRUCTOR -> {
                c.aload(0);
                ClassT parent = ci.owner();
                List<Type> params = ci.member() == null ? List.of() : ck.paramTypes(parent, ci.member(), ci.binders());
                pushArgs(params, call.args());
                JvmGen.invoke(c, g.constructor(parent, ci.member(), ci.binders(), at));
                return Type.VOID;
            }
            case SUPER_METHOD -> {
                c.aload(0);
                MethodRef r = instanceRef(ci, at);
                Type ret = ck.returnTypeOf(ci.owner(), ci.member(), ci.binders());
                pushResult(ret, dst);
                pushArgs(ci, call.args());
                c.invokespecial(r.owner(), r.name(), r.type());
                return ret;
            }
            default -> {
                Expr receiver = call.callee() instanceof Select s ? s.target() : null;
                return invokeOn(ci, receiver, call.args(), at, dst);
            }
        }
    }

    /** For a call returning a struct: pushes the place it goes (§4.2). */
    private void pushResult(Type ret, Mem dst) {
        if (g.isAggregate(ret)) {
            if (dst == null) {
                throw new IllegalStateException("a struct result with nowhere to go");
            }
            push(dst);
        }
    }

    private void pushArgs(CallInfo ci, List<Expr> args) {
        pushArgs(ck.paramTypes(ci.owner(), ci.member(), ci.binders()), args);
    }

    /**
     * Arguments, as JVM parameters: a number or reference as it is; a struct as a copy made in the
     * frame, which the callee may change; a unique lent, as its own place (§16.1).
     */
    private void pushArgs(List<Type> params, List<Expr> args) {
        for (int i = 0; i < args.size(); i++) {
            Type pt = params.get(i);
            Expr a = args.get(i);
            if (!g.isAggregate(pt)) {
                value(a, pt);
            } else if (Checker.isUnique(pt)) {
                push(aggregateOf(a));
            } else {
                Mem copy = temp(pt);
                into(copy, a, pt);
                push(copy);
            }
        }
    }

    private Type invokeStatic(CallInfo ci, List<Expr> args, Pos at, Mem dst) {
        MemberInfo m = ci.member();
        Method md = (Method) m.member;
        String jvm = Checker.jvmName(md);
        Type ret = ck.returnTypeOf(null, m, ci.binders());
        if (g.isAggregate(ret) && dst == null) {
            dst = temp(ret);
        }
        pushResult(ret, dst);
        String nat = Checker.nativeName(md);
        if (jvm != null || nat != null) {
            List<Type> params = ck.paramTypes(null, m, ci.binders());
            pushRuntimeArgs(params, args);
            if (jvm != null) {
                runtimeCall(jvm, null, params, ret, at);
            } else {
                native_(nat, params, ret, at);
            }
            return ret;
        }
        pushArgs(ci, args);
        MethodRef r = g.ref(new FnKey("method", ck.declaringClassOf(m), null, m, ci.binders()), at);
        JvmGen.invoke(c, r);
        return ret;
    }

    /**
     * Arguments to a native or a runtime method, which can't see types: a fixed-point value as a
     * long, and after them the fraction bits of each, as the VM passes them to its natives.
     */
    private void pushRuntimeArgs(List<Type> params, List<Expr> args) {
        for (int i = 0; i < args.size(); i++) {
            Type pt = params.get(i);
            pushArgs(List.of(pt), List.of(args.get(i)));
            if (pt instanceof FixedT ft) {
                asLong(ft);
            }
        }
        for (Type pt : params) {
            if (pt instanceof FixedT ft) {
                c.loadConstant(frac(ft));
            }
        }
    }

    /** A native (§8.3): a static method of the runtime's Natives, named as the native is. */
    private void native_(String name, List<Type> params, Type ret, Pos at) {
        List<ClassDesc> ps = new ArrayList<>();
        for (Type p : params) {
            ps.add(erased(p, at));
        }
        addFractions(ps, params);
        c.invokestatic(JvmGen.NATIVES, name, MethodTypeDesc.of(erased(ret, at), ps.toArray(ClassDesc[]::new)));
        if (ret instanceof FixedT) {
            fromLong(ret);
        }
    }

    private static void addFractions(List<ClassDesc> ps, List<Type> params) {
        for (Type p : params) {
            if (p instanceof FixedT) {
                ps.add(CD_int);
            }
        }
    }

    /** A type as the runtime, which knows no Seal classes, sees it: an object as java.lang.Object, a fixed-point value as a long. */
    private ClassDesc erased(Type t, Pos at) {
        return t instanceof HandleT && !Checker.isJavaValue(t) ? CD_Object : t instanceof FixedT ? CD_long : g.desc(t, at);
    }

    /** A method marked @jvm: a static method of a runtime class ("Strings.at"), or one this backend generates. */
    private void runtimeCall(String spec, Type receiver, List<Type> params, Type ret, Pos at) {
        int dot = spec.indexOf('.');
        ClassDesc owner = ClassDesc.of(JvmGen.RUNTIME + spec.substring(0, dot));
        List<ClassDesc> ps = new ArrayList<>();
        if (receiver != null) {
            ps.add(erased(receiver, at));
        }
        for (Type p : params) {
            ps.add(erased(p, at));
        }
        addFractions(ps, params);
        ClassDesc rd = ret instanceof VoidT && receiver != null && replacesReceiver(receiver) ? erased(receiver, at)
                : erased(ret, at);
        c.invokestatic(owner, spec.substring(dot + 1), MethodTypeDesc.of(rd, ps.toArray(ClassDesc[]::new)));
        if (ret instanceof FixedT) {
            fromLong(ret);
        }
    }

    /** The method an instance call reaches, generated for the class that declares it. */
    private MethodRef instanceRef(CallInfo ci, Pos at) {
        ClassT owner = g.declaringType(ci.owner(), ci.member());
        Method md = (Method) ci.member().member;
        if (JvmGen.hasBinders(md)) {
            return g.ref(new FnKey("method", owner.cls(), owner, ci.member(), ci.binders()), at);
        }
        MethodRef r = g.virtualRef(owner, ci.member(), at);
        g.calledVirtually(r);
        if (md.body() != null) {
            g.ref(new FnKey("method", owner.cls(), owner, ci.member(), Map.of()), at);
        }
        return r;
    }

    /**
     * An instance method called on a receiver (null: on `this`). A class object's method is a
     * virtual or interface call. A string's or array's is a static method with the value first;
     * on a string, one that changes it gives back the changed string, which goes where the
     * receiver came from.
     */
    private Type invokeOn(CallInfo ci, Expr receiver, List<Expr> args, Pos at) {
        return invokeOn(ci, receiver, args, at, null);
    }

    private Type invokeOn(CallInfo ci, Expr receiver, List<Expr> args, Pos at, Mem dst) {
        Type recvType = receiver == null ? thisValueType() : type(receiver);
        Type ret = ck.returnTypeOf(ci.owner(), ci.member(), ci.binders());
        if (g.isAggregate(ret) && dst == null) {
            dst = temp(ret);
        }
        if (Checker.isJavaValue(recvType) && Checker.jvmName((Method) ci.member().member) instanceof String spec) {
            // seal.java's own method of a Java object (JavaObject's hash()): the runtime's, with the object first.
            value(receiver, null);
            List<Type> params = ck.paramTypes(ci.owner(), ci.member(), ci.binders());
            pushArgs(params, args);
            List<ClassDesc> ps = new ArrayList<>(List.of(CD_Object));
            for (Type p : params) {
                ps.add(erased(p, at));
            }
            int dot = spec.indexOf('.');
            c.invokestatic(ClassDesc.of(JvmGen.RUNTIME + spec.substring(0, dot)), spec.substring(dot + 1),
                    MethodTypeDesc.of(erased(ret, at), ps.toArray(ClassDesc[]::new)));
            return ret;
        }
        if (recvType instanceof HandleT || recvType instanceof NullT) {
            if (receiver == null) {
                loadThis(at);
            } else {
                value(receiver, null);
            }
            MethodRef r = instanceRef(ci, at);
            pushResult(ret, dst);
            pushArgs(ci, args);
            JvmGen.invoke(c, r);
            return ret;
        }
        if (g.isAggregate(ci.owner())) {
            // A struct's or unique's method works on the value where it is (§4.3); a fixed array's are FixedArray's.
            Mem rm = receiver == null ? thisMem : aggregateOf(receiver);
            push(rm);
            pushResult(ret, dst);
            pushArgs(ci, args);
            ClassT owner = ci.owner();
            JvmGen.invoke(c, g.ref(new FnKey("method", owner.cls(), owner, ci.member(), ci.binders()), at));
            return ret;
        }
        if (!isValue(ci.owner())) {
            throw unsupported(at, "calling a method of " + recvType);
        }
        Method md = (Method) ci.member().member;
        boolean replaces = ret instanceof VoidT && replacesReceiver(ci.owner());
        Place p = replaces ? (receiver == null ? new LocalPlace(thisLocal) : place(receiver)) : null;
        if (p != null) {
            base(p);
            dupBase(p);
            get(p);
        } else if (receiver == null) {
            loadThis(at);
        } else {
            value(receiver, ci.owner());
        }
        String jvm = Checker.jvmName(md);
        List<Type> params = ck.paramTypes(ci.owner(), ci.member(), ci.binders());
        if (jvm != null && jvm.startsWith("array.")) {
            arrayIntrinsic(jvm, ci, args, receiver, at, dst);
            return ret;
        }
        pushResult(ret, dst);
        pushArgs(params, args);
        if (jvm != null) {
            runtimeCall(jvm, ci.owner(), params, ret, at);
        } else {
            ClassT owner = ci.owner();
            JvmGen.invoke(c, g.ref(new FnKey("method", owner.cls(), owner, ci.member(), ci.binders()), at));
        }
        if (replaces) {
            if (p != null) {
                put(p);
            } else {
                c.pop(); // a string that was a temporary: its change goes nowhere
            }
        }
        return ret;
    }

    /**
     * What array<T> is on the JVM (§8.1): get and set check the index with array's own check(),
     * then read or write the Java array; allocate makes a new one of default elements, which
     * becomes `this`. The array is on the stack.
     */
    private void arrayIntrinsic(String spec, CallInfo ci, List<Expr> args, Expr receiver, Pos at, Mem dst) {
        ClassT owner = ci.owner();
        Type element = owner.typeArgs().get(0);
        if (g.isAggregate(element)) {
            structArrayIntrinsic(spec, ci, args, at, dst, element);
            return;
        }
        TypeKind ek = kind(element);
        switch (spec) {
            case "array.allocate" -> {
                c.pop();
                value(args.get(0), Type.INT);
                newArray(element);
                if (thisLocal == null) {
                    throw unsupported(at, "allocate outside array's constructor");
                }
                c.astore(thisLocal.slot());
            }
            case "array.get", "array.set" -> {
                int array = c.allocateLocal(TypeKind.REFERENCE);
                int index = c.allocateLocal(TypeKind.INT);
                c.astore(array);
                value(args.get(0), Type.INT);
                c.istore(index);
                c.aload(array);
                c.iload(index);
                JvmGen.invoke(c, g.ref(new FnKey("method", owner.cls(), owner, member(owner, "check"), Map.of()), at));
                c.aload(array);
                c.iload(index);
                if (spec.equals("array.get")) {
                    c.arrayLoad(ek);
                } else {
                    value(args.get(1), element);
                    c.arrayStore(ek);
                }
            }
            default -> throw unsupported(at, "the JVM intrinsic " + spec);
        }
    }

    /** array<T> of structs (§8.1): a block of the elements in a row, the array on the stack. */
    private void structArrayIntrinsic(String spec, CallInfo ci, List<Expr> args, Pos at, Mem dst, Type element) {
        ClassT owner = ci.owner();
        long stride = g.layouts.stride(element);
        switch (spec) {
            case "array.allocate" -> {
                c.pop();
                value(args.get(0), Type.INT);
                newArray(element);
                if (thisLocal == null) {
                    throw unsupported(at, "allocate outside array's constructor");
                }
                c.astore(thisLocal.slot());
            }
            case "array.get", "array.set" -> {
                int array = c.allocateLocal(TypeKind.REFERENCE);
                int index = c.allocateLocal(TypeKind.INT);
                c.astore(array);
                value(args.get(0), Type.INT);
                c.istore(index);
                c.aload(array);
                c.iload(index);
                JvmGen.invoke(c, g.ref(new FnKey("method", owner.cls(), owner, member(owner, "check"), Map.of()), at));
                c.iload(index);
                c.loadConstant((int) stride);
                c.imul();
                c.istore(index);
                Mem element_ = new Mem(array, index, 0);
                if (spec.equals("array.get")) {
                    copy(dst, element_, element);
                } else {
                    into(element_, args.get(1), element);
                }
            }
            default -> throw unsupported(at, "the JVM intrinsic " + spec);
        }
    }

    private MemberInfo member(ClassT t, String name) {
        for (MemberInfo m : t.cls().members.getOrDefault(name, List.of())) {
            if (m.member instanceof Method && m.existsIn(t.header())) {
                return m;
            }
        }
        throw new IllegalStateException(t + " has no method " + name);
    }

    /**
     * An overloaded operator (§46): the method of the left operand (or the right, for a derived
     * comparison with swap), negated for one derived with negate.
     */
    private Type operator(Expr site) {
        return operator(site, null);
    }

    private Type operator(Expr site, Mem dst) {
        CallInfo ci = tb.calls.get(site);
        Expr left;
        List<Expr> args;
        if (site instanceof Binary b) {
            left = ci.swap() ? b.right() : b.left();
            args = List.of(ci.swap() ? b.left() : b.right());
        } else if (site instanceof Index ix) {
            left = ix.target();
            args = List.of(ix.index());
        } else if (site instanceof Assign a && a.target() instanceof Index ix) {
            left = ix.target();
            args = List.of(ix.index(), a.value());
        } else if (site instanceof Cast cast) {
            left = cast.operand();
            args = List.of();
        } else {
            left = ((Unary) site).operand();
            args = List.of();
        }
        Type ret = invokeOn(ci, left, args, site.pos(), dst);
        if (ci.negate()) {
            c.iconst_1();
            c.ixor();
        }
        return ret;
    }

    /** `a += b` and the like through an operator method: on a class object it changes the object; on a string, the string goes back where it was. */
    private void assignOperator(Assign a, boolean keep) {
        if (a.op() == null && a.target() instanceof Index) {
            operator(a); // xs[i] = v: the []= (§46)
            if (keep) {
                throw unsupported(a.pos(), "the value of an element assignment");
            }
            return;
        }
        CallInfo ci = tb.calls.get(a);
        Type t = type(a.target());
        if (g.isAggregate(t)) {
            // On a struct, `a += b` calls its own operator, which changes a where it is (§46).
            invokeOn(ci, a.target(), List.of(a.value()), a.pos());
            if (keep) {
                throw unsupported(a.pos(), "the value of a compound assignment of a " + t);
            }
            return;
        }
        if (t instanceof HandleT) {
            value(a.target(), null);
            if (keep) {
                c.dup();
            }
            MethodRef r = instanceRef(ci, a.pos());
            pushArgs(ci, List.of(a.value()));
            JvmGen.invoke(c, r);
            return;
        }
        invokeOn(ci, a.target(), List.of(a.value()), a.pos());
        if (keep) {
            value(a.target(), null);
        }
    }

    // ================================================================ objects

    private Type newObject(New n, Type t) {
        CallInfo ci = tb.calls.get(n);
        if (ci == null) {
            throw unsupported(n.pos(), "new here");
        }
        ClassT ct = ci.owner();
        if (Checker.isJavaArray(ct.cls())) {
            // new JavaArray(n): a Java array of n of Java's defaults (docs/JAVA.md §3.1).
            value(n.args().get(0), Type.INT);
            newJavaArray(ct.typeArgs().get(0), n.pos());
            return t;
        }
        Checker.JavaCall java = tb.javaCalls.get(n);
        if (java != null) {
            // A Java class's constructor (docs/JAVA.md §4).
            ClassDesc owner = g.desc(new HandleT(ct), n.pos());
            c.new_(owner);
            c.dup();
            pushJavaArgs(ck.paramTypes(ct, ci.member(), Map.of()), n.args(), java.varargsFrom(), n.pos());
            c.invokespecial(owner, "<init>", MethodTypeDesc.ofDescriptor(ck.javaMember(ci.member().member).descriptor()));
            return t;
        }
        if (isValue(ct)) {
            List<Type> params = ci.member() == null ? List.of() : ck.paramTypes(ct, ci.member(), ci.binders());
            pushArgs(params, n.args());
            JvmGen.invoke(c, g.constructor(ct, ci.member(), ci.binders(), n.pos()));
            return ct;
        }
        if (!(t instanceof HandleT)) {
            throw new IllegalStateException("new " + ct + " as a single value at " + n.pos());
        }
        if (ct.cls().isAbstract) {
            throw unsupported(n.pos(), "new of an abstract class");
        }
        MethodRef init = g.constructor(ct, ci.member(), ci.binders(), n.pos());
        c.new_(init.owner());
        c.dup();
        List<Type> params = ci.member() == null ? List.of() : ck.paramTypes(ct, ci.member(), ci.binders());
        pushArgs(params, n.args());
        JvmGen.invoke(c, init);
        return t;
    }

    /** x instanceof T, and x instanceof T name, which then holds x as a T (§35.1). */
    private Type instanceOf(InstanceOf io) {
        ClassT tc = tb.owners.get(io);
        ClassDesc d = g.desc(new HandleT(tc), io.pos());
        value(io.operand(), null);
        if (io.binding() == null) {
            c.instanceOf(d);
            return Type.BIT;
        }
        int slot = c.allocateLocal(TypeKind.REFERENCE);
        scopes.peek().put(io.binding(), new Local(new HandleT(tc), slot));
        Label no = c.newLabel();
        Label end = c.newLabel();
        c.dup();
        c.instanceOf(d);
        c.ifeq(no);
        c.checkcast(d);
        c.astore(slot);
        c.iconst_1();
        c.goto_(end);
        c.labelBinding(no);
        c.pop();
        c.aconst_null();
        c.astore(slot);
        c.iconst_0();
        c.labelBinding(end);
        return Type.BIT;
    }

    // ------------------------------------------------------------ Java's classes (docs/JAVA.md)

    /**
     * A call of a Java method (docs/JAVA.md §4): the JVM's own call, with the descriptor from its
     * class file, through the class the call names: the object's type, or the class written before
     * a static method's name. A method of java.lang.Object called on an interface is Object's.
     */
    private Type javaCall(Call call, CallInfo ci, Checker.JavaCall java) {
        MemberInfo m = ci.member();
        Pos at = call.pos();
        if (ci.kind() == CallKind.SUPER_CONSTRUCTOR) {
            // super(...) of a Seal class extending a Java one: the Java constructor the checker chose (docs/JAVA.md §5).
            c.aload(0);
            pushJavaArgs(ck.paramTypes(ci.owner(), m, Map.of()), call.args(), java.varargsFrom(), at);
            c.invokespecial(g.desc(new HandleT(ci.owner()), at), "<init>",
                    MethodTypeDesc.ofDescriptor(ck.javaMember(m.member).descriptor()));
            return Type.VOID;
        }
        boolean isStatic = m.member.modifiers().isStatic();
        ClassT owner = isStatic ? null : ci.owner();
        Type ret = type(call);
        String name = ((Method) m.member).name();
        String descriptor = ck.javaMember(m.member).descriptor();
        MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
        if (!isStatic) {
            Expr receiver = call.callee() instanceof Select s ? s.target() : null;
            if (receiver == null || receiver instanceof SuperRef) {
                loadThis(at);
            } else {
                value(receiver, null);
            }
        }
        pushJavaArgs(ck.paramTypes(owner, m, ci.binders()), call.args(), java.varargsFrom(), at);
        ClassInfo declaring = ck.declaringClassOf(m);
        ClassT through = java.qualifier();
        if (java.special()) {
            c.invokespecial(g.desc(new HandleT(through), at), name, type, through.cls().isInterface); // super.m(): the parent's own
        } else if (isStatic) {
            // An interface's static method is called through the interface itself.
            ClassInfo cls = declaring.isInterface ? declaring : through.cls();
            c.invokestatic(ClassDesc.of(cls.java.name()), name, type, cls.isInterface);
        } else if (through.cls().isInterface && Checker.isJavaObject(declaring)) {
            c.invokevirtual(CD_Object, name, type);
        } else if (through.cls().isInterface) {
            c.invokeinterface(g.desc(new HandleT(through), at), name, type);
        } else {
            c.invokevirtual(g.desc(new HandleT(through), at), name, type);
        }
        javaResult(ret, type.returnType(), at);
        return ret;
    }

    /**
     * A Java method's or field's value as its Seal type: an erased generic one (get() giving an
     * Object where a List<JavaString>'s gives a JavaString) checked as that type (docs/JAVA.md §4.1).
     */
    private void javaResult(Type sealType, ClassDesc erased, Pos at) {
        if (!(sealType instanceof VoidT) && kind(sealType) == TypeKind.REFERENCE) {
            ClassDesc want = g.desc(sealType, at);
            if (!want.equals(erased)) {
                c.checkcast(want);
            }
        }
    }

    /**
     * A Java call's arguments, each as its parameter wants it; with varargs, those from
     * {@code varargsFrom} in a new array of the last parameter's type, as javac passes them.
     */
    private void pushJavaArgs(List<Type> params, List<Expr> args, int varargsFrom, Pos at) {
        int fixed = varargsFrom < 0 ? args.size() : varargsFrom;
        for (int i = 0; i < fixed; i++) {
            value(args.get(i), params.get(i));
        }
        if (varargsFrom < 0) {
            return;
        }
        Type element = ((ClassT) ((HandleT) params.get(params.size() - 1)).target()).typeArgs().get(0);
        c.loadConstant(args.size() - fixed);
        newJavaArray(element, at);
        for (int i = fixed; i < args.size(); i++) {
            c.dup();
            c.loadConstant(i - fixed);
            value(args.get(i), element);
            c.arrayStore(JvmGen.javaKind(element));
        }
    }

    /** A new Java array of the length on the stack, of a Java type's elements: Java's defaults, 0, false and null. */
    private void newJavaArray(Type element, Pos at) {
        TypeKind k = JvmGen.javaKind(element);
        if (k == TypeKind.REFERENCE) {
            c.anewarray(g.javaDesc(element, at));
        } else {
            c.newarray(k);
        }
    }

    /** Java's widening of a primitive on the stack (JLS §5.1.2): an int as a long, a long as a float. */
    private void javaWiden(JavaPrimT from, JavaPrimT to) {
        javaConvert(from, to);
    }

    /** A Java primitive on the stack as another, by Java's conversion (JLS §5.1): i2l, d2i, and i2b for a byte. */
    private void javaConvert(JavaPrimT from, JavaPrimT to) {
        if (from.equals(to)) {
            return;
        }
        TypeKind f = javaStack(from);
        TypeKind t = javaStack(to);
        if (f != t) {
            c.conversion(f, t);
        }
        switch (to.code()) {
            case 'B' -> c.i2b();
            case 'S' -> c.i2s();
            case 'C' -> c.i2c();
            default -> { }
        }
    }

    /** How the JVM's stack holds a Java primitive: a byte, short, char or boolean as an int. */
    private static TypeKind javaStack(JavaPrimT t) {
        return switch (t.code()) {
            case 'J' -> TypeKind.LONG;
            case 'F' -> TypeKind.FLOAT;
            case 'D' -> TypeKind.DOUBLE;
            default -> TypeKind.INT;
        };
    }

    /**
     * An operator on Java's primitives (docs/JAVA.md §3.1): both operands converted to the type Java
     * promotes them to, then the operator in it, a comparison giving a JavaBoolean; && and || on
     * JavaBooleans don't look at the right side when the left decides.
     */
    private Type javaBinary(Binary b, JavaPrimT lt, JavaPrimT rt, JavaPrimT t) {
        BinaryOp op = b.op();
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            Label decided = c.newLabel();
            Label end = c.newLabel();
            value(b.left(), lt);
            if (op == BinaryOp.AND) {
                c.ifeq(decided);
            } else {
                c.ifne(decided);
            }
            value(b.right(), rt);
            c.goto_(end);
            c.labelBinding(decided);
            c.loadConstant(op == BinaryOp.AND ? 0 : 1);
            c.labelBinding(end);
            return t;
        }
        JavaPrimT operands = lt.code() == 'Z' ? lt : promoted(lt, rt);
        value(b.left(), lt);
        javaConvert(lt, operands);
        value(b.right(), rt);
        javaConvert(rt, operands);
        if (isComparison(op)) {
            Label no = c.newLabel();
            Label end = c.newLabel();
            compareFalse(op, operands.sealType(), no);
            c.iconst_1();
            c.goto_(end);
            c.labelBinding(no);
            c.iconst_0();
            c.labelBinding(end);
            return t;
        }
        javaOperate(op, operands, b.pos());
        return t;
    }

    /** The operator on two values of a Java primitive type on the stack, as Seal's number of the same values computes it. */
    private void javaOperate(BinaryOp op, JavaPrimT t, Pos at) {
        arithmetic(op == BinaryOp.AND ? BinaryOp.BIT_AND : op == BinaryOp.OR ? BinaryOp.BIT_OR : op, t.sealType(), at);
    }

    /** Java's binary numeric promotion (JLS §5.6.2): double if either is, then float, then long, else int. */
    private static JavaPrimT promoted(JavaPrimT a, JavaPrimT b) {
        for (char k : "DFJ".toCharArray()) {
            if (a.code() == k || b.code() == k) {
                return new JavaPrimT(k);
            }
        }
        return new JavaPrimT('I');
    }

    /**
     * A cast out of a Java value (docs/JAVA.md §3.1), whose operand is not yet pushed: to string,
     * Java's text of it; a primitive, or a box unboxed, to a Seal number or bit, as Seal converts
     * numbers; a Java array to a new Seal array, a copy; a JavaObject to a Seal class's object, or
     * between Java's classes, checked.
     */
    private void javaCast(Expr operand, Type from, Type to) {
        value(operand, from);
        if (isString(to)) {
            ClassDesc arg = from instanceof JavaPrimT j ? g.javaDesc(j, pos) : CD_Object;
            if (from instanceof JavaPrimT j && (j.code() == 'B' || j.code() == 'S')) {
                arg = CD_int; // held as an int, and its text is the int's
            }
            c.invokestatic(CD_String, "valueOf", MethodTypeDesc.of(CD_String, arg));
            return;
        }
        if (from instanceof JavaPrimT j) {
            convert(j.sealType(), to);
            return;
        }
        String box = from instanceof HandleT h && h.target() instanceof ClassT ct && ct.cls().java != null ? ct.cls().java.name() : "";
        if (box.equals("java.lang.Object") && !(to instanceof HandleT) && Checker.unboxedAs(to) instanceof JavaPrimT p) {
            // (int)o, as Java's: the Integer it must be, then its int.
            box = switch (p.code()) {
                case 'I' -> "java.lang.Integer";
                case 'J' -> "java.lang.Long";
                case 'S' -> "java.lang.Short";
                case 'B' -> "java.lang.Byte";
                case 'F' -> "java.lang.Float";
                case 'D' -> "java.lang.Double";
                case 'C' -> "java.lang.Character";
                default -> "java.lang.Boolean";
            };
            c.checkcast(ClassDesc.of(box));
        }
        String[] unbox = switch (box) {
            case "java.lang.Integer" -> new String[]{"intValue", "I"};
            case "java.lang.Long" -> new String[]{"longValue", "J"};
            case "java.lang.Short" -> new String[]{"shortValue", "S"};
            case "java.lang.Byte" -> new String[]{"byteValue", "B"};
            case "java.lang.Float" -> new String[]{"floatValue", "F"};
            case "java.lang.Double" -> new String[]{"doubleValue", "D"};
            case "java.lang.Character" -> new String[]{"charValue", "C"};
            case "java.lang.Boolean" -> new String[]{"booleanValue", "Z"};
            default -> null;
        };
        if (unbox != null && !(to instanceof HandleT)) {
            c.invokevirtual(ClassDesc.of(box), unbox[0], MethodTypeDesc.ofDescriptor("()" + unbox[1]));
            convert(new JavaPrimT(unbox[1].charAt(0)).sealType(), to);
            return;
        }
        if (isArray(to)) {
            javaArrayOut(from, (ClassT) to);
            return;
        }
        c.checkcast(g.desc(to, pos)); // checked while the program runs
    }

    /** A Java array on the stack copied into a new Seal array (docs/JAVA.md §3.1), by the runtime's JavaValues. */
    private void javaArrayOut(Type from, ClassT to) {
        Type element = ((ClassT) ((HandleT) from).target()).typeArgs().get(0);
        ClassDesc fd = g.desc(from, pos);
        ClassDesc td = g.desc(to, pos);
        if (element instanceof JavaPrimT j) {
            boolean unsigned = j.code() == 'B' && to.typeArgs().get(0) instanceof IntT it && !it.signed();
            String name = switch (j.code()) {
                case 'B' -> unsigned ? "unsignedBytesOut" : "bytesOut";
                case 'S' -> "shortsOut";
                case 'I' -> "intsOut";
                case 'J' -> "longsOut";
                case 'F' -> "floatsOut";
                case 'D' -> "doublesOut";
                case 'C' -> "charsOut";
                default -> "booleansOut";
            };
            c.invokestatic(JAVA_VALUES, name, MethodTypeDesc.of(td, fd));
            return;
        }
        c.invokestatic(JAVA_VALUES, "objectsOut", MethodTypeDesc.of(CD_Object.arrayType(), CD_Object.arrayType()));
        c.checkcast(td);
    }

    private static final ClassDesc JAVA_VALUES = ClassDesc.of(JvmGen.RUNTIME + "JavaValues");

    /** The element type of a JavaArray<T>, T; null for any other type. */
    private static Type javaElement(Type t) {
        return t instanceof HandleT h && h.target() instanceof ClassT ct && Checker.isJavaArray(ct.cls()) ? ct.typeArgs().get(0) : null;
    }

    /** A lambda's object (§30.3): its class, made with the enclosing object and the copies it captures. */
    private Type closure(Lambda lam) {
        Checker.LambdaInfo li = tb.lambdas.get(lam);
        FnKey key = new FnKey("lambda", k.cls(), k.owner(), k.member(), k.binders(), lam);
        JClass lc = g.lambdaClass(key, li, tb.file);
        if (lc.captures == null) {
            List<FieldSpec> fields = new ArrayList<>();
            if (li.thisType() != null) {
                fields.add(new FieldSpec("this$0", g.desc(new HandleT(li.thisType()), lam.pos()),
                        java.lang.classfile.ClassFile.ACC_PUBLIC));
            }
            for (int i = 0; i < li.captureNames().size(); i++) {
                Type ct = li.captureTypes().get(i);
                fields.add(new FieldSpec("cap$" + li.captureNames().get(i), g.isAggregate(ct) ? JvmGen.BLOCK
                        : g.desc(ct, lam.pos()), java.lang.classfile.ClassFile.ACC_PUBLIC));
            }
            lc.captures = fields;
            for (FieldSpec f : fields) {
                lc.fields.put(f.name(), f);
            }
        }
        g.ref(key, lam.pos());
        c.new_(lc.desc);
        c.dup();
        if (li.thisType() != null) {
            loadThis(lam.pos());
        }
        for (String name : li.captureNames()) {
            Var v = lookup(name);
            if (v instanceof Stored st && g.isAggregate(st.type())) {
                // A struct is captured as a copy, in a block of the lambda's own.
                newBlock(Math.max(1, g.layouts.sizeOf(st.type())));
                c.dup();
                int block = c.allocateLocal(TypeKind.REFERENCE);
                c.astore(block);
                copy(new Mem(block, -1, 0), st.mem(), st.type());
            } else {
                loadVar(v);
            }
        }
        c.invokespecial(lc.desc, "<init>", MethodTypeDesc.of(CD_void,
                lc.captures.stream().map(FieldSpec::type).toArray(ClassDesc[]::new)));
        return new HandleT(li.target());
    }
}
