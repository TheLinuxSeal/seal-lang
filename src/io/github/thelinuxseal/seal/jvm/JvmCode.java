package io.github.thelinuxseal.seal.jvm;

import static io.github.thelinuxseal.seal.jvm.JvmGen.concrete;
import static io.github.thelinuxseal.seal.jvm.JvmGen.fullUnsigned;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isArray;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isNumber;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isString;
import static io.github.thelinuxseal.seal.jvm.JvmGen.isValue;
import static io.github.thelinuxseal.seal.jvm.JvmGen.kind;
import static io.github.thelinuxseal.seal.jvm.JvmGen.replacesReceiver;
import static io.github.thelinuxseal.seal.jvm.JvmGen.width;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_double;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import io.github.thelinuxseal.seal.jvm.JvmGen.FieldSpec;
import io.github.thelinuxseal.seal.jvm.JvmGen.FnKey;
import io.github.thelinuxseal.seal.jvm.JvmGen.Invoke;
import io.github.thelinuxseal.seal.jvm.JvmGen.JClass;
import io.github.thelinuxseal.seal.jvm.JvmGen.MethodRef;
import io.github.thelinuxseal.seal.jvm.JvmGen.Unsupported;

/**
 * The code of one generated method (docs/JVM.md). It follows CodeGen's rules, on the JVM's
 * operand stack: values are converted where they meet another type as on the VM, integers of a
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

    /** Variables by name, innermost scope first. */
    private final Deque<Map<String, Var>> scopes = new ArrayDeque<>();
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

    sealed interface Var permits Local, Captured {
        Type type();
    }

    /** A local variable: its type and JVM slot. */
    record Local(Type type, int slot) implements Var {
    }

    /** A variable a lambda captured: a field of its class. */
    record Captured(Type type, FieldSpec field) implements Var {
    }

    /** Where break and continue go; continueTo is null for a switch. */
    record Loop(Label breakTo, Label continueTo, String name, int fins) {
    }

    record SwitchOut(Loop loop, int slot, Type type) {
    }

    /** A try with a finally block: where jumps out of it run the finally block first. */
    private static final class Fin {
        final Block block;
        final List<Exit> exits = new ArrayList<>();
        int returnSlot = -1;

        Fin(Block block) {
            this.block = block;
        }
    }

    /** A jump out of a try with a finally block: to a loop (break or continue) or out of the method. */
    private record Exit(Label pad, String kind, Loop loop) {
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
            switch (k.kind()) {
                case "init" -> allStatics();
                case "class-init" -> staticInit();
                case "ctor", "default-ctor" -> {
                    if (isValue(k.owner())) {
                        valueConstructor();
                    } else {
                        constructor();
                    }
                }
                case "lambda" -> lambda();
                default -> method();
            }
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

    private TypedBody typed(ClassInfo cls, ClassT owner, MemberInfo m, Map<String, CVal> binders) {
        TypedBody t = ck.typeBody(cls, owner, m, binders);
        if (!t.errors.isEmpty()) {
            found.addAll(t.errors);
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
        } else if (k.owner() != null) {
            thisType = k.owner();
        }
        for (int i = 0; i < md.params().size(); i++) {
            params.put(md.params().get(i).name(), new Local(tb.params.get(i), c.parameterSlot(i + first)));
        }
        scopes.push(params);
        block(md.body());
        endOfMethod();
    }

    private void endOfMethod() {
        if (returnType instanceof VoidT) {
            returnVoid();
        } else {
            // The checker makes sure every path returns; this end can't be reached.
            trap("reached the end of a method without returning");
        }
    }

    /** `return;`, which in a string's method gives back the string it made. */
    private void returnVoid() {
        if (thisLocal != null && replacesReceiver(thisLocal.type()) && self.type().returnType() != CD_void
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
            for (int i = 0; i < ctor.params().size(); i++) {
                params.put(ctor.params().get(i).name(), new Local(tb.params.get(i), c.parameterSlot(i)));
            }
            body = ctor.body().stmts();
        }
        scopes.push(params);
        int start = 0;
        if (!body.isEmpty() && body.get(0) instanceof ExprStmt es && es.expr() instanceof Call call
                && call.callee() instanceof SuperRef) {
            stmt(body.get(0));
            start = 1;
        } else {
            superDefault(t);
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
            if (f.init() != null) {
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
            for (int i = 0; i < ctor.params().size(); i++) {
                params.put(ctor.params().get(i).name(), new Local(tb.params.get(i), c.parameterSlot(i)));
            }
        }
        thisLocal = new Local(t, c.allocateLocal(TypeKind.REFERENCE));
        zero(t);
        c.astore(thisLocal.slot());
        scopes.push(params);
        if (ctor != null) {
            block(ctor.body());
        }
        c.aload(thisLocal.slot());
        c.areturn();
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
        scopes.push(new HashMap<>());
        for (ClassInfo cls : List.of(k.cls())) {
            for (MemberInfo m : cls.all) {
                if (!(m.member instanceof Field f) || !f.modifiers().isStatic() || f.modifiers().isConst()) {
                    continue;
                }
                Type t = ck.staticFieldType(cls, f);
                pos = f.pos();
                ClassDesc fd = g.desc(t, f.pos());
                JClass home = g.staticClass(cls);
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
            captured.put(li.captureNames().get(n), new Captured(li.captureTypes().get(n), lambdaClass.captures.get(i++)));
        }
        scopes.push(captured);
        Map<String, Var> params = new HashMap<>();
        for (int n = 0; n < lam.params().size(); n++) {
            Type pt = li.params().get(n);
            int slot = c.parameterSlot(n);
            // The interface's parameter types may be wider (an Object for a T): take the lambda's own.
            params.put(lam.params().get(n).name(), new Local(pt, slot));
        }
        scopes.push(params);
        Block body = lam.block() != null ? lam.block() : new Block(lam.pos(), List.of(returnType instanceof VoidT
                ? new ExprStmt(lam.pos(), lam.expr()) : new Return(lam.pos(), lam.expr())));
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

    /** A new Java array of the length on the stack, whose elements start as their default: "" for strings. */
    private void newArray(Type element) {
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

    // ================================================================ statements

    private void block(Block b) {
        scopes.push(new HashMap<>());
        for (Stmt s : b.stmts()) {
            stmt(s);
        }
        scopes.pop();
    }

    /** A statement that forms its own scope: the body of an if, while or for. */
    private void scoped(Stmt s) {
        scopes.push(new HashMap<>());
        stmt(s);
        scopes.pop();
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
                int slot = c.allocateLocal(kind(t));
                if (v.init() != null) {
                    value(v.init(), t);
                } else {
                    zero(t);
                }
                c.storeLocal(load(t), slot);
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
                loops.push(new Loop(end, top, name, fins.size()));
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
                loops.push(new Loop(end, next, name, fins.size()));
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
            case Break b -> jump("break", b.label() == null ? loops.peek() : labeled(b.label()));
            case Continue co -> jump("continue", co.label() == null
                    ? loops.stream().filter(x -> x.continueTo() != null).findFirst().orElseThrow()
                    : labeled(co.label()));
            case Return r -> {
                if (r.value() != null) {
                    value(r.value(), returnType);
                }
                jump("return", null);
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
                value(y.value(), out.type());
                c.storeLocal(load(out.type()), out.slot());
                jump("break", out.loop());
            }
            case DeleteStmt d -> throw unsupported(d.pos(), "delete");
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
        int crossing = kind.equals("return") ? fins.size() : fins.size() - loop.fins();
        if (crossing == 0) {
            switch (kind) {
                case "return" -> {
                    if (returnType instanceof VoidT) {
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
        if (kind.equals("return") && !(returnType instanceof VoidT)) {
            if (f.returnSlot < 0) {
                f.returnSlot = c.allocateLocal(kind(returnType));
            }
            c.storeLocal(load(returnType), f.returnSlot);
        }
        for (Exit e : f.exits) {
            if (e.kind().equals(kind) && e.loop() == loop) {
                c.goto_(e.pad());
                return;
            }
        }
        Exit e = new Exit(c.newLabel(), kind, loop);
        f.exits.add(e);
        c.goto_(e.pad());
    }

    /** A switch statement: the subject in a hidden local, then each case's tests in order (§41.3). */
    private void switchStatement(Switch sw) {
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
        scopes.pop();
    }

    /** A switch expression (§41.3): as a switch statement, with each yield storing the value. */
    private Type switchExpr(SwitchExpr se, Type t) {
        Checker.SwitchInfo info = tb.switchExprs.get(se);
        Type vt = concrete(t);
        int slot = c.allocateLocal(kind(vt));
        scopes.push(new HashMap<>());
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
        switchOuts.push(new SwitchOut(loop, slot, vt));
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
        scopes.pop();
        c.loadLocal(load(vt), slot);
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
        Fin fin = finBlock == null ? null : new Fin(finBlock);
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
        for (Catch cl : t.catches()) {
            ClassT ct = tb.catchTypes.get(cl);
            Label handler = c.newLabel();
            Label catchEnd = c.newLabel();
            c.exceptionCatch(start, bodyEnd, handler, g.instance(ct).desc);
            c.labelBinding(handler);
            scopes.push(new HashMap<>());
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
            scopes.pop();
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
                ClassDesc ed = g.instance(new ClassT(exception, List.of(), List.of(), 0)).desc;
                for (Label[] r : guarded) {
                    c.exceptionCatch(r[0], r[1], handler, ed);
                }
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
                if (e.kind().equals("return") && !(returnType instanceof VoidT)) {
                    c.loadLocal(load(returnType), fin.returnSlot);
                }
                jump(e.kind(), e.loop());
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
     * what it pushed. As in CodeGen: a value narrowed where it is stored or passed is converted as a
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
            case New n -> {
                return newObject(n, t);
            }
            case Index ix when tb.calls.containsKey(ix) -> {
                return operator(ix);
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

    /** [1, 2, 3] made into an array<T>: a new Java array with the elements stored in it. */
    private Type arrayLiteral(ArrayLit al, ClassT t) {
        Type element = t.typeArgs().get(0);
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
        if (f.modifiers().isStatic()) {
            return getStatic(m, s.pos());
        }
        Type target = value(s.target(), null);
        return getField(target, m, s.pos());
    }

    private Type getStatic(MemberInfo m, Pos at) {
        ClassInfo cls = ck.declaringClassOf(m);
        Field f = (Field) m.member;
        Type t = ck.staticFieldType(cls, f);
        c.getstatic(g.staticClass(cls).desc, f.name(), g.desc(t, at));
        return t;
    }

    private void putStatic(MemberInfo m, Pos at) {
        ClassInfo cls = ck.declaringClassOf(m);
        Field f = (Field) m.member;
        c.putstatic(g.staticClass(cls).desc, f.name(), g.desc(ck.staticFieldType(cls, f), at));
    }

    /** A field of the object on the stack, of type `target`; a string's size and an array's len are their lengths. */
    private Type getField(Type target, MemberInfo m, Pos at) {
        Field f = (Field) m.member;
        if (isString(target) && f.name().equals("size")) {
            c.invokevirtual(CD_String, "length", MethodTypeDesc.of(CD_int));
            return Type.INT;
        }
        if (isArray(target) && f.name().equals("len")) {
            c.arraylength();
            return Type.INT;
        }
        if (!(target instanceof HandleT h) || !(h.target() instanceof ClassT ct)) {
            throw unsupported(at, "the field " + f.name() + " of " + target);
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
        ClassT owner = g.declaringType(ct, m);
        c.putfield(g.instance(owner).desc, f.name(), g.desc(ck.fieldTypeOf(owner, m), at));
    }

    // ------------------------------------------------------------ constants

    /** Pushes a compile-time value as type {@code as}. */
    private void constant(CVal v, Type as, Pos at) {
        switch (v) {
            case IntV i when as instanceof IntT -> intConstant(i.value(), as);
            case IntV i when as instanceof FloatT -> c.loadConstant(i.value().floatValue());
            case IntV i when as instanceof DoubleT -> c.loadConstant(i.value().doubleValue());
            case FloatV f when as instanceof FloatT -> c.loadConstant(f.value().floatValue());
            case FloatV f when as instanceof DoubleT -> c.loadConstant(f.value());
            case BitV b -> c.loadConstant(b.value() ? 1 : 0);
            case CVal.StrV s -> c.loadConstant(s.value());
            default -> throw unsupported(at, "the constant " + v + " as " + as);
        }
    }

    /** An integer constant wrapped to the type's width, as the VM's constants are. */
    private void intConstant(BigInteger x, Type as) {
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
     * A value where it meets another type without a cast (§41.6): an int<<8>> stored in an int, an
     * int added to a double, a Dog where an Animal goes. Between integers only the JVM
     * representation can change (int to long); references need nothing.
     */
    private void widen(Type from, Type to) {
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
                c.checkcast(g.instance(tc).desc);
            }
            return;
        }
        if (isString(from) && isString(to)) {
            return; // both shapes of string are Strings
        }
        throw unsupported(pos, "converting " + from + " to " + to);
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

    private Type cast(Cast cast) {
        if (tb.calls.containsKey(cast)) {
            return operator(cast); // the operand's @overload(Operator.CAST) method (§46)
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
            default -> throw unsupported(u.pos(), "addresses");
        }
    }

    private Type binary(Binary b, Type t) {
        if (tb.calls.containsKey(b)) {
            return operator(b);
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
        c.loadConstant(((IntT) t).signed() ? 1 : 0);
        ClassDesc v = kind(t) == TypeKind.LONG ? CD_long : CD_int;
        c.invokestatic(JvmGen.INTS, "shift", MethodTypeDesc.of(v, v, CD_int, CD_int, CD_boolean));
    }

    /** Both operands of type t on the stack: the result, normalized to t. */
    private void arithmetic(BinaryOp op, Type t, Pos at) {
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

    /** The type both operands of a comparison are converted to, as in CodeGen. */
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
    private sealed interface Place permits LocalPlace, FieldPlace, StaticPlace {
        Type type();
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

    /** The place an expression names, or null if it isn't one (a call's result, say). */
    private Place place(Expr e) {
        if (tb.rewritten.containsKey(e)) {
            return place(tb.rewritten.get(e));
        }
        switch (e) {
            case Name n when "local".equals(tb.nameKinds.get(n)) -> {
                if (lookup(n.name()) instanceof Local l) {
                    return new LocalPlace(l);
                }
                return null;
            }
            case Name n when "field".equals(tb.nameKinds.get(n)) -> {
                MemberInfo m = tb.fieldOf.get(n);
                if (((Field) m.member).modifiers().isStatic()) {
                    return new StaticPlace(m, ck.staticFieldType(ck.declaringClassOf(m), (Field) m.member));
                }
                return new FieldPlace(null, thisValueType(), m, type(n));
            }
            case Select s when tb.selected.get(s) instanceof MemberInfo m && m.member instanceof Field f
                    && !f.modifiers().isConst() -> {
                if (f.modifiers().isStatic()) {
                    return new StaticPlace(m, ck.staticFieldType(ck.declaringClassOf(m), f));
                }
                return new FieldPlace(s.target(), type(s.target()), m, type(s));
            }
            case This th when thisLocal != null -> {
                return new LocalPlace(thisLocal);
            }
            default -> {
                return null;
            }
        }
    }

    private int baseSize(Place p) {
        return p instanceof FieldPlace ? 1 : 0;
    }

    private void base(Place p) {
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
            case LocalPlace l -> c.loadLocal(load(l.type()), l.local().slot());
            case FieldPlace f -> getField(f.objectType(), f.field(), pos);
            case StaticPlace s -> getStatic(s.field(), pos);
        }
    }

    private void put(Place p) {
        switch (p) {
            case LocalPlace l -> c.storeLocal(load(l.type()), l.local().slot());
            case FieldPlace f -> putField(f.objectType(), f.field(), pos);
            case StaticPlace s -> putStatic(s.field(), pos);
        }
    }

    private void dupBase(Place p) {
        if (baseSize(p) == 1) {
            c.dup();
        }
    }

    /** With the base and the value on the stack, keeps a copy of the value below the base. */
    private void keepUnderBase(Place p, Type value) {
        boolean wide = kind(value).slotSize() == 2;
        if (baseSize(p) == 0) {
            if (wide) c.dup2(); else c.dup();
        } else {
            if (wide) c.dup2_x1(); else c.dup_x1();
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
        Place p = target(a.target());
        base(p);
        if (a.op() == null) {
            value(a.value(), t);
        } else {
            dupBase(p);
            get(p);
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
        constant(new IntV(BigInteger.ONE), t, i.pos());
        arithmetic(i.op(), t, i.pos());
        if (keep && i.prefix()) {
            keepUnderBase(p, t);
        }
        put(p);
    }

    // ================================================================ calls

    private Type call(Call call) {
        CallInfo ci = tb.calls.get(call);
        if (ci == null) {
            throw unsupported(call.pos(), "this call");
        }
        if (ck.isRawAssembly(ci)) {
            return Type.VOID; // assembly is for a native compiler (§42.3)
        }
        Pos at = call.pos();
        switch (ci.kind()) {
            case STATIC -> {
                return invokeStatic(ci, call.args(), at);
            }
            case SUPER_CONSTRUCTOR -> {
                c.aload(0);
                ClassT parent = ci.owner();
                List<Type> params = ci.member() == null ? List.of() : ck.paramTypes(parent, ci.member(), ci.binders());
                for (int i = 0; i < call.args().size(); i++) {
                    value(call.args().get(i), params.get(i));
                }
                JvmGen.invoke(c, g.constructor(parent, ci.member(), ci.binders(), at));
                return Type.VOID;
            }
            case SUPER_METHOD -> {
                c.aload(0);
                MethodRef r = instanceRef(ci, at);
                pushArgs(ci, call.args());
                c.invokespecial(r.owner(), r.name(), r.type());
                return ck.returnTypeOf(ci.owner(), ci.member(), ci.binders());
            }
            default -> {
                Expr receiver = call.callee() instanceof Select s ? s.target() : null;
                return invokeOn(ci, receiver, call.args(), at);
            }
        }
    }

    private void pushArgs(CallInfo ci, List<Expr> args) {
        List<Type> params = ck.paramTypes(ci.owner(), ci.member(), ci.binders());
        for (int i = 0; i < args.size(); i++) {
            value(args.get(i), params.get(i));
        }
    }

    private Type invokeStatic(CallInfo ci, List<Expr> args, Pos at) {
        MemberInfo m = ci.member();
        Method md = (Method) m.member;
        String jvm = Checker.jvmName(md);
        pushArgs(ci, args);
        Type ret = ck.returnTypeOf(null, m, ci.binders());
        if (jvm != null) {
            runtimeCall(jvm, null, ck.paramTypes(null, m, ci.binders()), ret, at);
            return ret;
        }
        String nat = Checker.nativeName(md);
        if (nat != null) {
            native_(nat, ck.paramTypes(null, m, ci.binders()), ret, at);
            return ret;
        }
        MethodRef r = g.ref(new FnKey("method", ck.declaringClassOf(m), null, m, ci.binders()), at);
        JvmGen.invoke(c, r);
        return ret;
    }

    /** A native (§8.3): a static method of the runtime's Natives, named as the native is. */
    private void native_(String name, List<Type> params, Type ret, Pos at) {
        ClassDesc[] ps = new ClassDesc[params.size()];
        for (int i = 0; i < ps.length; i++) {
            ps[i] = erased(params.get(i), at);
        }
        c.invokestatic(JvmGen.NATIVES, name, MethodTypeDesc.of(erased(ret, at), ps));
    }

    /** A type as the runtime, which knows no Seal classes, sees it: an object as java.lang.Object. */
    private ClassDesc erased(Type t, Pos at) {
        return t instanceof HandleT ? CD_Object : g.desc(t, at);
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
        ClassDesc rd = ret instanceof VoidT && receiver != null && replacesReceiver(receiver) ? erased(receiver, at)
                : erased(ret, at);
        c.invokestatic(owner, spec.substring(dot + 1), MethodTypeDesc.of(rd, ps.toArray(ClassDesc[]::new)));
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
        Type recvType = receiver == null ? thisValueType() : type(receiver);
        Type ret = ck.returnTypeOf(ci.owner(), ci.member(), ci.binders());
        if (recvType instanceof HandleT || recvType instanceof NullT) {
            if (receiver == null) {
                loadThis(at);
            } else {
                value(receiver, null);
            }
            MethodRef r = instanceRef(ci, at);
            pushArgs(ci, args);
            JvmGen.invoke(c, r);
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
            arrayIntrinsic(jvm, ci, args, receiver, at);
            return ret;
        }
        for (int i = 0; i < args.size(); i++) {
            value(args.get(i), params.get(i));
        }
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
    private void arrayIntrinsic(String spec, CallInfo ci, List<Expr> args, Expr receiver, Pos at) {
        ClassT owner = ci.owner();
        Type element = owner.typeArgs().get(0);
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
        Type ret = invokeOn(ci, left, args, site.pos());
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
        if (isValue(ct)) {
            List<Type> params = ci.member() == null ? List.of() : ck.paramTypes(ct, ci.member(), ci.binders());
            for (int i = 0; i < n.args().size(); i++) {
                value(n.args().get(i), params.get(i));
            }
            JvmGen.invoke(c, g.constructor(ct, ci.member(), ci.binders(), n.pos()));
            return ct;
        }
        if (!(t instanceof HandleT)) {
            throw unsupported(n.pos(), "new " + ct + " as a value");
        }
        if (ct.cls().isAbstract) {
            throw unsupported(n.pos(), "new of an abstract class");
        }
        MethodRef init = g.constructor(ct, ci.member(), ci.binders(), n.pos());
        c.new_(init.owner());
        c.dup();
        List<Type> params = ci.member() == null ? List.of() : ck.paramTypes(ct, ci.member(), ci.binders());
        for (int i = 0; i < n.args().size(); i++) {
            value(n.args().get(i), params.get(i));
        }
        JvmGen.invoke(c, init);
        return t;
    }

    /** x instanceof T, and x instanceof T name, which then holds x as a T (§35.1). */
    private Type instanceOf(InstanceOf io) {
        ClassT tc = tb.owners.get(io);
        ClassDesc d = g.instance(tc).desc;
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
                fields.add(new FieldSpec("cap$" + li.captureNames().get(i), g.desc(li.captureTypes().get(i), lam.pos()),
                        java.lang.classfile.ClassFile.ACC_PUBLIC));
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
            loadVar(lookup(name));
        }
        c.invokespecial(lc.desc, "<init>", MethodTypeDesc.of(CD_void,
                lc.captures.stream().map(FieldSpec::type).toArray(ClassDesc[]::new)));
        return new HandleT(li.iface());
    }
}
