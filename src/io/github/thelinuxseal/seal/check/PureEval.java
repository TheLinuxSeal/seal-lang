package io.github.thelinuxseal.seal.check;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.CVal.BitV;
import io.github.thelinuxseal.seal.check.CVal.FloatV;
import io.github.thelinuxseal.seal.check.CVal.IntV;
import io.github.thelinuxseal.seal.check.CVal.StrV;
import io.github.thelinuxseal.seal.check.Checker.CallInfo;
import io.github.thelinuxseal.seal.check.Checker.CallKind;
import io.github.thelinuxseal.seal.check.Checker.SwitchInfo;
import io.github.thelinuxseal.seal.check.Checker.TypedBody;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Type.*;
import io.github.thelinuxseal.seal.runtime.FixedPoint;
import io.github.thelinuxseal.seal.runtime.Ints;

/**
 * Runs pure methods while compiling (§17.2), for a call in `<<...>>`, an array's size or a const
 * field. It runs the method's checked body, as the backends would compile it: numbers of every
 * width, bits, characters, floating and fixed point, strings, fixed arrays and array<T>, each
 * computed exactly as when the program runs (a value of a width below 64 is kept in a long,
 * sign-extended when signed and zero-extended when not, as the JVM backend keeps it). Both kinds
 * of array are an Object[]: a fixed array, a value, is copied where it is stored; an array<T>,
 * a unique, is lent and moved, as a pure method only reads the arrays it is given. A call's
 * result is kept, so each call with the same arguments runs once.
 */
final class PureEval {

    static final long MAX_STEPS = 50_000_000;
    static final int MAX_DEPTH = 20_000;

    /** Why a call couldn't be worked out, as words that follow "it": "divided by zero". */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String where;

        Failure(String message, String where) {
            super(message, null, false, false);
            this.where = where;
        }
    }

    /** Leaving a method, loop or switch: control flow, not errors. */
    private static final class Jump extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String kind;
        final String label;
        final transient Object value;

        Jump(String kind, String label, Object value) {
            super(null, null, false, false);
            this.kind = kind;
            this.label = label;
            this.value = value;
        }
    }

    private record Key(MemberInfo method, Map<String, CVal> binders, List<Object> args) {
    }

    private record Prepared(Method method, TypedBody tb) {
    }

    /** A value and the type it is in. */
    private record Val(Object v, Type t) {
    }

    /** A local variable. */
    private static final class Cell {
        Object value;
        final Type type;

        Cell(Object value, Type type) {
            this.value = value;
            this.type = type;
        }
    }

    /** Somewhere an assignment can store: a local, or an element of a fixed array. */
    private interface Ref {
        Object get();

        void set(Object v);
    }

    private final Checker ck;
    private final Map<Key, Object> results = new HashMap<>();
    private final Map<List<Object>, Prepared> prepared = new HashMap<>();
    private final Set<MemberInfo> preparing = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean running;
    private long steps;
    private int depth;

    // The method being run.
    private Prepared fn;
    private Deque<Map<String, Cell>> scopes;
    private final Deque<Type> yieldTypes = new ArrayDeque<>();
    private Pos at;
    private String pendingLabel;
    /** Values worked out ahead of the code the checker rewrote, which reaches them twice: xs[i] += v. */
    private final Map<Expr, Val> pinned = new IdentityHashMap<>();

    PureEval(Checker ck) {
        this.ck = ck;
    }

    // ================================================================ calls

    /**
     * m(args), the arguments already in m's parameter types; throws a Failure if it can't be worked
     * out. The first call runs on a thread of its own with a deep stack, so deep recursion is
     * reported, not a crash of the compiler.
     */
    Object call(MemberInfo m, Map<String, CVal> binders, List<Object> args) {
        Key key = new Key(m, binders, keyOf(args));
        if (results.containsKey(key)) {
            return copy(results.get(key));
        }
        if (running) {
            return invoke(m, binders, args);
        }
        running = true;
        steps = 0;
        depth = 0;
        Object[] out = new Object[1];
        RuntimeException[] failed = new RuntimeException[1];
        Thread t = new Thread(null, () -> {
            try {
                out[0] = invoke(m, binders, args);
            } catch (RuntimeException e) {
                failed[0] = e;
            } catch (StackOverflowError e) {
                failed[0] = new Failure("called itself too deeply", null);
            }
        }, "seal-pure", 1L << 30);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("was interrupted", null);
        } finally {
            running = false;
            fn = null;
        }
        if (failed[0] != null) {
            throw failed[0];
        }
        return out[0];
    }

    private Object invoke(MemberInfo m, Map<String, CVal> binders, List<Object> args) {
        Key key = new Key(m, binders, keyOf(args));
        if (results.containsKey(key)) {
            return copy(results.get(key));
        }
        if (++depth > MAX_DEPTH) {
            throw fail("called itself more than " + MAX_DEPTH + " calls deep");
        }
        Prepared p = prepare(m, binders);
        Prepared savedFn = fn;
        Deque<Map<String, Cell>> savedScopes = scopes;
        Pos savedAt = at;
        String savedLabel = pendingLabel;
        fn = p;
        scopes = new ArrayDeque<>();
        scopes.push(new HashMap<>());
        pendingLabel = null;
        try {
            for (int i = 0; i < p.method().params().size(); i++) {
                Type pt = p.tb().params.get(i);
                scopes.peek().put(p.method().params().get(i).name(), new Cell(pt instanceof ArrayT ? copy(args.get(i)) : args.get(i), pt));
            }
            Object result = null;
            try {
                exec(p.method().body());
                if (!(p.tb().returnType instanceof VoidT)) {
                    throw fail("reached its end without returning");
                }
            } catch (Jump j) {
                if (!j.kind.equals("return")) {
                    throw j;
                }
                result = j.value;
            }
            results.put(key, copy(result));
            return result;
        } finally {
            fn = savedFn;
            scopes = savedScopes;
            at = savedAt;
            pendingLabel = savedLabel;
            depth--;
        }
    }

    /** A method's checked body, once for each set of binders; only a pure one without errors runs. */
    private Prepared prepare(MemberInfo m, Map<String, CVal> binders) {
        List<Object> key = List.of(m, binders);
        Prepared p = prepared.get(key);
        if (p != null) {
            return p;
        }
        String name = name(m);
        if (!preparing.add(m)) {
            throw fail("needs " + name + " to work out " + name + "'s own types");
        }
        try {
            ClassInfo cls = ck.declaringClassOf(m);
            Method md = (Method) m.member;
            if (!md.modifiers().isPure()) {
                throw fail("calls " + name + ", which isn't pure");
            }
            TypedBody tb = ck.typeBody(cls, null, m, binders);
            if (!tb.errors.isEmpty()) {
                throw fail("calls " + name + ", which has errors: " + tb.errors.get(0).message());
            }
            List<Purity.Problem> problems = Purity.of(ck, tb, md);
            if (!problems.isEmpty()) {
                throw fail("calls " + name + ", which breaks the rules: " + problems.get(0).message());
            }
            p = new Prepared(md, tb);
            prepared.put(key, p);
            return p;
        } finally {
            preparing.remove(m);
        }
    }

    private String name(MemberInfo m) {
        return ck.declaringClassOf(m).name + "." + m.name();
    }

    // ================================================================ compile-time values

    /** A compile-time value as the value of type t the method gets. */
    Object fromCVal(CVal v, Type t) {
        if (v instanceof FloatV f && t instanceof IntT) {
            return wrap((long) f.value().doubleValue(), t);
        }
        return constant(v, t);
    }

    /** A result as a compile-time value. */
    static CVal toCVal(Object v, Type t) {
        return switch (t) {
            case IntT it when !it.signed() && (Long) v < 0 -> new IntV(BigInteger.valueOf((Long) v).add(BigInteger.ONE.shiftLeft(64)));
            case IntT it -> new IntV(BigInteger.valueOf((Long) v));
            case BitT b -> new BitV((Boolean) v);
            case FloatT f -> new FloatV((double) (Float) v);
            case DoubleT d -> new FloatV((Double) v);
            case FixedT f -> {
                long raw = (Long) v;
                if (Math.abs(raw) >= 1L << 53) {
                    throw new Failure("gave a " + t + " too wide to keep exactly as a compile-time value", null);
                }
                yield new FloatV(Math.scalb((double) raw, -fracOf(f)));
            }
            case ClassT ct when v instanceof String s -> new StrV(s);
            default -> throw new Failure("gave a " + t + ", which can't be a compile-time value", null);
        };
    }

    // ================================================================ statements

    private void exec(Stmt s) {
        if (s == null) {
            return;
        }
        tick(s.pos());
        Stmt step = fn.tb().statementSteps.get(s);
        if (step != null) {
            exec(step);
            return;
        }
        switch (s) {
            case Block b -> {
                scopes.push(new HashMap<>());
                try {
                    for (Stmt x : b.stmts()) {
                        exec(x);
                    }
                } finally {
                    scopes.pop();
                }
            }
            case VarDecl v -> {
                Type t = fn.tb().locals.get(v);
                Object value = v.init() == null ? zero(t) : stored(v.init(), t);
                scopes.peek().put(v.name(), new Cell(value, t));
            }
            case ExprStmt e -> effect(e.expr());
            case If i -> {
                if (test(i.cond())) {
                    scoped(i.then());
                } else {
                    scoped(i.otherwise());
                }
            }
            case While w -> {
                String label = takeLabel();
                while (test(w.cond())) {
                    if (!loopBody(w.body(), label)) {
                        break;
                    }
                }
            }
            case For f -> {
                String label = takeLabel();
                scopes.push(new HashMap<>());
                try {
                    exec(f.init());
                    while (f.cond() == null || test(f.cond())) {
                        if (!loopBody(f.body(), label)) {
                            break;
                        }
                        if (f.update() != null) {
                            effect(f.update());
                        }
                    }
                } finally {
                    scopes.pop();
                }
            }
            case ForEach fe -> {
                Stmt loop = fn.tb().desugared.get(fe);
                if (loop == null) {
                    throw fail("uses a for-each loop that compile-time evaluation doesn't run");
                }
                exec(loop);
            }
            case Labeled l -> {
                pendingLabel = l.label();
                exec(l.body());
            }
            case Break b -> throw new Jump("break", b.label(), null);
            case Continue c -> throw new Jump("continue", c.label(), null);
            case Return r -> throw new Jump("return", null, r.value() == null ? null : stored(r.value(), fn.tb().returnType));
            case Yield y -> throw new Jump("yield", null, stored(y.value(), yieldTypes.peek()));
            case Switch sw -> switchStatement(sw);
            case Throw t -> throw thrown(t);
            case Try t -> throw fail("uses try, which compile-time evaluation doesn't run yet");
            case Assert a -> {
                Stmt check = fn.tb().assertions.get(a);
                if (check != null) {
                    exec(check);
                }
            }
            case DeleteStmt d -> throw fail("uses addresses");
        }
    }

    private void scoped(Stmt s) {
        scopes.push(new HashMap<>());
        try {
            exec(s);
        } finally {
            scopes.pop();
        }
    }

    /** A loop's body; false if a break left the loop. */
    private boolean loopBody(Stmt body, String label) {
        try {
            scoped(body);
            return true;
        } catch (Jump j) {
            boolean mine = j.label == null || j.label.equals(label);
            if (j.kind.equals("break") && mine) {
                return false;
            }
            if (j.kind.equals("continue") && mine) {
                return true;
            }
            throw j;
        }
    }

    private String takeLabel() {
        String l = pendingLabel;
        pendingLabel = null;
        return l;
    }

    private void switchStatement(Switch sw) {
        SwitchInfo info = fn.tb().switches.get(sw);
        String label = takeLabel();
        scopes.push(new HashMap<>());
        try {
            exec(info.subject());
            int start = chooseCase(sw.cases(), info);
            if (start < 0) {
                return;
            }
            try {
                for (int i = start; i < sw.cases().size(); i++) {
                    Case cs = sw.cases().get(i);
                    scoped(new Block(cs.pos(), cs.body()));
                    if (cs.arrow()) {
                        break; // no falling through
                    }
                }
            } catch (Jump j) {
                if (!j.kind.equals("break") || (j.label != null && !j.label.equals(label))) {
                    throw j;
                }
            }
        } finally {
            scopes.pop();
        }
    }

    private Object switchExpr(SwitchExpr se, Type t) {
        SwitchInfo info = fn.tb().switchExprs.get(se);
        scopes.push(new HashMap<>());
        yieldTypes.push(concrete(t));
        try {
            exec(info.subject());
            int start = chooseCase(se.cases(), info);
            if (start < 0) {
                throw fail("reached a switch expression none of whose cases matched");
            }
            try {
                for (int i = start; i < se.cases().size(); i++) {
                    Case cs = se.cases().get(i);
                    scoped(new Block(cs.pos(), cs.body()));
                    if (cs.arrow()) {
                        break;
                    }
                }
            } catch (Jump j) {
                if (j.kind.equals("yield")) {
                    return j.value;
                }
                throw j;
            }
            throw fail("reached the end of a switch expression without a value");
        } finally {
            yieldTypes.pop();
            scopes.pop();
        }
    }

    /** The case the subject selects, as the backends test them: in order; else default; else -1. */
    private int chooseCase(List<Case> cases, SwitchInfo info) {
        int otherwise = -1;
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i).isDefault()) {
                otherwise = i;
            }
            for (Expr test : info.tests().get(i)) {
                if (test(test)) {
                    return i;
                }
            }
        }
        return otherwise;
    }

    /** `throw new E(message)`: while compiling, the call fails with E and its message. */
    private Failure thrown(Throw t) {
        Expr v = fn.tb().rewritten.getOrDefault(t.value(), t.value());
        if (v instanceof New n && fn.tb().calls.get(n) instanceof CallInfo ci && ci.owner() != null) {
            String message = null;
            Type first = n.args().isEmpty() ? null : type(n.args().get(0));
            if (first instanceof StrConstT || (first instanceof ClassT ct && ct.cls().name.equals("string"))) {
                Object m = eval(n.args().get(0), null).v();
                message = m instanceof String s ? s : null;
            }
            return fail("threw " + ci.owner() + (message == null || message.isEmpty() ? "" : ": " + message));
        }
        return fail("threw an exception");
    }

    private void effect(Expr e) {
        Expr rw = fn.tb().rewritten.get(e);
        if (rw != null) {
            if (e instanceof Assign a && a.target() instanceof Index ix) {
                // xs[i] += v through []=: xs[i] = xs[i] + v, with i worked out once, as the backends do.
                boolean held = pin(ix.index()) != null;
                try {
                    effect(rw);
                } finally {
                    if (held) {
                        pinned.remove(ix.index());
                    }
                }
                return;
            }
            effect(rw);
            return;
        }
        switch (e) {
            case Assign a -> assign(a);
            case IncDec i -> incDec(i);
            default -> eval(e, null);
        }
    }

    // ================================================================ conditions

    private boolean test(Expr c) {
        Expr rw = fn.tb().rewritten.get(c);
        if (rw != null) {
            return test(rw);
        }
        tick(c.pos());
        boolean operator = fn.tb().calls.containsKey(c);
        return switch (c) {
            case BoolLit b -> b.value();
            case Unary u when u.op() == UnaryOp.NOT && !operator -> !test(u.operand());
            case Binary b when b.op() == BinaryOp.AND && !operator -> test(b.left()) && test(b.right());
            case Binary b when b.op() == BinaryOp.OR && !operator -> test(b.left()) || test(b.right());
            case Binary b when isComparison(b.op()) && !operator -> compare(b);
            default -> (Boolean) value(c, Type.BIT);
        };
    }

    private static boolean isComparison(BinaryOp op) {
        return op == BinaryOp.EQ || op == BinaryOp.NE || op == BinaryOp.LT || op == BinaryOp.GT
                || op == BinaryOp.LE || op == BinaryOp.GE;
    }

    private boolean compare(Binary b) {
        Type lt = type(b.left());
        Type rt = type(b.right());
        if (lt instanceof AddrT || rt instanceof AddrT || lt instanceof HandleT || rt instanceof HandleT
                || lt instanceof NullT || rt instanceof NullT) {
            throw fail("compares " + lt + " values, which compile-time evaluation doesn't support");
        }
        Type ot = operandType(lt, rt);
        Object l = value(b.left(), ot);
        Object r = value(b.right(), ot);
        int cmp;
        switch (ot) {
            case FloatT f -> {
                float x = (Float) l;
                float y = (Float) r;
                return compareFloating(b.op(), x, y);
            }
            case DoubleT d -> {
                return compareFloating(b.op(), (Double) l, (Double) r);
            }
            case BitT bt -> {
                boolean same = l.equals(r);
                return b.op() == BinaryOp.EQ ? same : !same;
            }
            default -> {
                if (!(l instanceof Long x) || !(r instanceof Long y)) {
                    throw fail("compares " + ot + " values, which compile-time evaluation doesn't support");
                }
                cmp = fullUnsigned(ot) ? Long.compareUnsigned(x, y) : Long.compare(x, y);
            }
        }
        return switch (b.op()) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case LT -> cmp < 0;
            case LE -> cmp <= 0;
            case GT -> cmp > 0;
            default -> cmp >= 0;
        };
    }

    /** As the JVM compares: NaN is unordered, so only != holds. */
    private static boolean compareFloating(BinaryOp op, double x, double y) {
        return switch (op) {
            case EQ -> x == y;
            case NE -> x != y;
            case LT -> x < y;
            case LE -> x <= y;
            case GT -> x > y;
            default -> x >= y;
        };
    }

    /** The type both operands of a comparison are converted to, as the backends choose it. */
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

    // ================================================================ values

    private Object value(Expr e, Type want) {
        return eval(e, want).v();
    }

    /** e's value in want (in its own type if want is null), converted as the backends convert it. */
    private Val eval(Expr e, Type want) {
        tick(e.pos());
        Type narrowed = fn.tb().implicitCasts.get(e);
        if (narrowed != null) {
            Val v = implicitCast(e, narrowed);
            return want == null ? v : widen(v, want);
        }
        return evalUncast(e, want);
    }

    private Val implicitCast(Expr e, Type to) {
        Type from = type(e);
        if (from instanceof FloatConstT && to instanceof IntT) {
            double d = ((FloatV) ck.constantIn(fn.tb(), e)).value(); // int a = 2.5: toward zero, as (int)2.5
            return new Val(wrap((long) d, to), to);
        }
        return convert(evalUncast(e, null), to);
    }

    private Val evalUncast(Expr e, Type want) {
        Val early = pinned.get(e);
        if (early != null) {
            return want == null ? early : widen(early, want);
        }
        Expr rw = fn.tb().rewritten.get(e);
        if (rw != null) {
            return eval(rw, want);
        }
        at = e.pos();
        Type t = type(e);
        if (t instanceof FloatConstT fc && fc.value() != null && want instanceof FixedT ft) {
            return new Val(fixedConstant(fc.value(), ft), want); // exact, from the literal's digits
        }
        if (t instanceof IntConstT || t instanceof FloatConstT
                || (e instanceof Name n && "value".equals(fn.tb().nameKinds.get(n)))) {
            Type as = want == null || want instanceof IntConstT || want instanceof FloatConstT || !isNumber(want)
                    ? concrete(t) : want;
            Val v = new Val(constant(ck.constantIn(fn.tb(), e), as), as);
            return want != null && !as.equals(want) ? widen(v, want) : v;
        }
        if (t instanceof StrConstT sc) {
            String text = sc.value() != null ? sc.value() : constString(e);
            if (text == null) {
                throw fail("uses a string it can't work out");
            }
            return new Val(text, want != null ? want : t);
        }
        if (t instanceof ArrayT && isArray(want)) {
            return new Val(copy(array(e)), want); // a fixed array where an array<T> is wanted: copied into one
        }
        Val v = evalAt(e, t, want);
        return want == null ? v : widen(v, want);
    }

    private Val evalAt(Expr e, Type t, Type want) {
        boolean operator = fn.tb().calls.containsKey(e) && !(e instanceof Call) && !(e instanceof New)
                && !(e instanceof Assign);
        if (operator) {
            CallInfo ci = fn.tb().calls.get(e);
            if (e instanceof Index ix && isArray(ci.owner())) {
                Object[] items = array(ix.target());
                int i = arrayIndex((Long) value(ix.index(), Type.INT), items.length);
                return new Val(items[i], element(ci.owner()));
            }
            throw fail("uses an operator that calls " + ci.owner() + "." + ci.member().name() + ", which isn't pure");
        }
        switch (e) {
            case New n when fn.tb().calls.get(n) instanceof CallInfo ci && isArray(ci.owner()) -> {
                long count = n.args().isEmpty() ? 0 : (Long) value(n.args().get(0), Type.INT);
                return new Val(newArray(element(ci.owner()), count), ci.owner());
            }
            case ArrayLit al when isArray(want != null ? want : t) -> {
                ClassT at = (ClassT) (want != null ? want : t);
                Object[] items = new Object[al.elements().size()];
                for (int i = 0; i < items.length; i++) {
                    items[i] = stored(al.elements().get(i), element(at));
                }
                return new Val(items, at);
            }
            case BoolLit b -> {
                return new Val(b.value(), Type.BIT);
            }
            case Name n -> {
                return name(n, t);
            }
            case Select s -> {
                return select(s, t);
            }
            case Call c -> {
                return call(c);
            }
            case Unary u -> {
                return unary(u, t);
            }
            case IncDec i -> {
                return incDec(i);
            }
            case Binary b -> {
                return binary(b, t);
            }
            case Assign a -> {
                return assign(a);
            }
            case Cast c -> {
                return cast(c);
            }
            case Conditional c -> {
                Type branch = t instanceof IntConstT || t instanceof FloatConstT ? (want != null ? want : concrete(t)) : t;
                return eval(test(c.cond()) ? c.then() : c.otherwise(), branch);
            }
            case Index ix when type(ix.target()) instanceof ArrayT arr && arr.size() != null -> {
                Object[] items = array(ix.target());
                return new Val(items[index(ix.index(), items.length)], arr.element());
            }
            case Index ix when type(ix.target()) instanceof IntT it -> {
                long x = (Long) value(ix.target(), it);
                int i = bit(ix.index(), width(it));
                return new Val(((x >>> i) & 1) == 1, Type.BIT);
            }
            case Slice sl -> {
                IntT it = (IntT) type(sl.target());
                long from = constInt(sl.from());
                long to = constInt(sl.to());
                long x = (Long) value(sl.target(), it);
                return new Val((x >>> from) & mask(to - from), t);
            }
            case SwitchExpr se -> {
                return new Val(switchExpr(se, t), concrete(t));
            }
            case ArrayLit al when t instanceof ArrayT arr -> {
                return new Val(arrayLiteral(al, arr), t);
            }
            default -> throw fail("uses " + describe(e) + ", which compile-time evaluation doesn't run");
        }
    }

    private String describe(Expr e) {
        return switch (e) {
            case NullLit n -> "null";
            case This t -> "this";
            case Lambda l -> "a lambda";
            case New n -> "new";
            case NewArray n -> "addresses";
            case InstanceOf i -> "instanceof";
            case Index i -> "indexing a " + type(i.target());
            case Select s -> "." + s.name();
            default -> "this expression";
        };
    }

    private Val name(Name n, Type t) {
        String kind = fn.tb().nameKinds.get(n);
        if ("local".equals(kind)) {
            Cell cell = lookup(n.name());
            return new Val(cell.value, cell.type);
        }
        if ("const".equals(kind)) {
            MemberInfo m = fn.tb().fieldOf.get(n);
            Type as = concrete(t);
            return new Val(constant(ck.constFieldValue(ck.declaringClassOf(m), (Field) m.member), as), as);
        }
        throw fail("uses " + n.name() + ", which isn't one of its own values");
    }

    private Val select(Select s, Type t) {
        MemberInfo m = fn.tb().selected.get(s);
        if (m != null && m.member instanceof Field f && f.modifiers().isConst()) {
            Type as = concrete(t);
            return new Val(constant(ck.constFieldValue(ck.declaringClassOf(m), f), as), as);
        }
        throw fail("uses ." + s.name() + " of a " + type(s.target()) + ", which compile-time evaluation doesn't support yet");
    }

    private Cell lookup(String name) {
        for (Map<String, Cell> scope : scopes) {
            Cell c = scope.get(name);
            if (c != null) {
                return c;
            }
        }
        throw fail("uses " + name + ", which it can't find");
    }

    private Val call(Call c) {
        CallInfo ci = fn.tb().calls.get(c);
        if (ci != null && ci.kind() == CallKind.METHOD && isArray(ci.owner()) && c.callee() instanceof Select s) {
            return arrayMethod(c, ci, s.target());
        }
        if (ci == null || ci.kind() != CallKind.STATIC || !(ci.member().member instanceof Method md) || !md.modifiers().isPure()) {
            throw fail("calls " + (ci == null || ci.member() == null ? "something" : name(ci.member())) + ", which isn't pure");
        }
        List<Type> params = ck.paramTypes(null, ci.member(), ci.binders());
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < c.args().size(); i++) {
            args.add(stored(c.args().get(i), params.get(i)));
        }
        Object r = invoke(ci.member(), ci.binders(), args);
        return new Val(r, ck.returnTypeOf(null, ci.member(), ci.binders()));
    }

    private Val unary(Unary u, Type t) {
        switch (u.op()) {
            case NEG -> {
                Object v = value(u.operand(), t);
                return new Val(switch (t) {
                    case FloatT f -> -(Float) v;
                    case DoubleT d -> -(Double) v;
                    default -> wrap(-(Long) v, t);
                }, t);
            }
            case BIT_NOT -> {
                return new Val(wrap(~(Long) value(u.operand(), t), t), t);
            }
            case NOT -> {
                return new Val(!(Boolean) value(u.operand(), Type.BIT), Type.BIT);
            }
            default -> throw fail("uses addresses");
        }
    }

    private Val binary(Binary b, Type t) {
        String joined = constString(b);
        if (joined != null) {
            return new Val(joined, t);
        }
        switch (b.op()) {
            case AND, OR, EQ, NE, LT, GT, LE, GE -> {
                return new Val(test(b), Type.BIT);
            }
            case SHIFT -> {
                Object v = value(b.left(), t);
                int amount = (int) (long) (Long) value(b.right(), Type.INT);
                return new Val(shift(t, (Long) v, amount), t);
            }
            default -> {
                if (type(b.left()) instanceof AddrT || type(b.right()) instanceof AddrT) {
                    throw fail("uses addresses");
                }
                Object l = value(b.left(), t);
                Object r = value(b.right(), t);
                return new Val(arithmetic(b.op(), t, l, r), t);
            }
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

    private Val cast(Cast c) {
        Type to = type(c);
        Type from = type(c.operand());
        if (from instanceof IntConstT || from instanceof FloatConstT) {
            if (to instanceof IntT && from instanceof FloatConstT) {
                double d = ((FloatV) ck.constantIn(fn.tb(), c.operand())).value();
                return new Val(wrap((long) d, to), to);
            }
            return eval(c.operand(), to);
        }
        return convert(eval(c.operand(), from), to);
    }

    /** The value e gives, to be stored where a value of type t goes: a fixed array is copied, a literal one built. */
    private Object stored(Expr e, Type t) {
        Expr rw = fn.tb().rewritten.get(e);
        if (rw != null) {
            return stored(rw, t);
        }
        if (t instanceof ArrayT arr) {
            if (e instanceof ArrayLit al) {
                return arrayLiteral(al, arr);
            }
            return copy(array(e));
        }
        return value(e, t);
    }

    private Object[] arrayLiteral(ArrayLit al, ArrayT arr) {
        Object[] items = new Object[al.elements().size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = stored(al.elements().get(i), arr.element());
        }
        return items;
    }

    /** The fixed array an expression names or gives, itself, not a copy. */
    private Object[] array(Expr e) {
        Object v = e instanceof Name n && "local".equals(fn.tb().nameKinds.get(n)) ? lookup(n.name()).value
                : eval(e, null).v();
        if (!(v instanceof Object[] items)) {
            throw fail("uses a " + type(e) + " as an array");
        }
        return items;
    }

    /** A fixed array's index: out of range, the library throws, so the call fails. */
    private int index(Expr e, int n) {
        long i = (Long) value(e, Type.INT);
        if (i < 0 || i >= n) {
            throw fail("threw IndexOutOfBoundsException: index " + i + " out of range for an array of length " + n);
        }
        return (int) i;
    }

    /** A bit's index in an integer: out of range, the program traps, so the call fails. */
    private int bit(Expr e, int width) {
        long i = (Long) value(e, Type.INT);
        if (i < 0 || i >= width) {
            throw fail("stopped with a trap: index " + i + " is out of bounds for length " + width);
        }
        return (int) i;
    }

    private long constInt(Expr e) {
        return ck.constantIn(fn.tb(), e).intValue().longValueExact();
    }

    // ================================================================ assignment

    private Ref ref(Expr target) {
        switch (target) {
            case Name n when "local".equals(fn.tb().nameKinds.get(n)) -> {
                Cell cell = lookup(n.name());
                return new Ref() {
                    public Object get() {
                        return cell.value;
                    }

                    public void set(Object v) {
                        cell.value = v;
                    }
                };
            }
            case Index ix when type(ix.target()) instanceof ArrayT arr && arr.size() != null
                    && !fn.tb().calls.containsKey(ix) -> {
                Object[] items = array(ix.target());
                int i = index(ix.index(), items.length);
                return new Ref() {
                    public Object get() {
                        return items[i];
                    }

                    public void set(Object v) {
                        items[i] = v;
                    }
                };
            }
            default -> throw fail("assigns to " + describe(target) + ", which compile-time evaluation doesn't support");
        }
    }

    private Val assign(Assign a) {
        CallInfo set = fn.tb().calls.get(a);
        if (set != null && a.op() == null && a.target() instanceof Index ix && isArray(set.owner())) {
            // xs[i] = v: the array's []= (§46), with its index checked as the library checks it.
            Object[] items = array(ix.target());
            long i = (Long) value(ix.index(), Type.INT);
            Object v = stored(a.value(), element(set.owner()));
            items[arrayIndex(i, items.length)] = v;
            return new Val(null, Type.VOID);
        }
        if (set != null) {
            throw fail("uses an operator that isn't pure");
        }
        if (a.target() instanceof Slice sl) {
            return assignSlice(a, sl);
        }
        if (a.target() instanceof Index ix && type(ix.target()) instanceof IntT it) {
            return assignBit(a, ix, it);
        }
        Type t = type(a.target());
        Ref r = ref(a.target());
        if (t instanceof ArrayT) {
            Object v = stored(a.value(), t); // the new value is built before the old one changes
            r.set(v);
            return new Val(v, t);
        }
        Object v;
        if (a.op() == null) {
            v = value(a.value(), t);
        } else {
            Object old = r.get();
            if (a.op() == BinaryOp.SHIFT) {
                v = shift(t, (Long) old, (int) (long) (Long) value(a.value(), Type.INT));
            } else {
                Type wide = fn.tb().narrowed.get(a);
                if (wide == null) {
                    v = arithmetic(a.op(), t, old, value(a.value(), t));
                } else {
                    // i += 2.5: in the wider type the operator gives, narrowed back as a cast would (§41.6).
                    Object w = widen(new Val(old, t), wide).v();
                    Object result = arithmetic(a.op(), wide, w, value(a.value(), wide));
                    v = convert(new Val(result, wide), t).v();
                }
            }
        }
        r.set(v);
        return new Val(v, t);
    }

    /** x[i] = b, or x[i] op= b: one bit of an integer (§40). */
    private Val assignBit(Assign a, Index ix, IntT it) {
        Ref r = ref(ix.target());
        long old = (Long) r.get();
        int i = bit(ix.index(), width(it));
        boolean b;
        if (a.op() == null) {
            b = (Boolean) value(a.value(), Type.BIT);
        } else {
            boolean cur = ((old >>> i) & 1) == 1;
            b = (Boolean) arithmetic(a.op(), Type.BIT, cur, value(a.value(), Type.BIT));
        }
        long v = (old & ~(1L << i)) | ((b ? 1L : 0L) << i);
        r.set(wrap(v, it));
        return new Val(b, Type.BIT);
    }

    /** x[a..b] = v, or x[a..b] op= v: a field of bits (§40). */
    private Val assignSlice(Assign a, Slice sl) {
        IntT it = (IntT) type(sl.target());
        Type st = type(sl);
        long from = constInt(sl.from());
        long to = constInt(sl.to());
        long field = mask(to - from) << from;
        Ref r = ref(sl.target());
        long old = (Long) r.get();
        long v;
        if (a.op() == null) {
            v = (Long) value(a.value(), st);
        } else {
            long cur = (old >>> from) & mask(to - from);
            Type wide = fn.tb().narrowed.get(a);
            if (wide == null) {
                v = (Long) arithmetic(a.op(), st, cur, value(a.value(), st));
            } else {
                Object w = widen(new Val(cur, st), wide).v();
                v = (Long) convert(new Val(arithmetic(a.op(), wide, w, value(a.value(), wide)), wide), st).v();
            }
        }
        r.set(wrap((old & ~field) | ((v << from) & field), it));
        return new Val(v, st);
    }

    private Val incDec(IncDec i) {
        Assign step = fn.tb().elementSteps.get(i);
        if (step != null) {
            // xs[i]++ through [] and []=: reads xs[i] once, then stores the result, as the backends do.
            Index ix = (Index) step.target();
            Expr next = step.value();
            Expr read = ((Binary) (next instanceof Cast cast ? cast.operand() : next)).left();
            List<Expr> held = new ArrayList<>();
            try {
                for (Expr e : List.of(ix.index(), read, next)) {
                    if (pin(e) != null) {
                        held.add(e);
                    }
                }
                Val old = pinned.get(read);
                Val now = pinned.get(next);
                assign(step);
                return i.prefix() ? now : old;
            } finally {
                held.forEach(pinned::remove);
            }
        }
        Type t = type(i.target());
        Ref r = ref(i.target());
        Object old = r.get();
        Object now = arithmetic(i.op(), t, old, constant(new IntV(BigInteger.ONE), t));
        r.set(now);
        return new Val(i.prefix() ? now : old, t);
    }

    /** Works out e now, for the rewritten code that uses it twice; null for a constant, which needs no pinning. */
    private Val pin(Expr e) {
        Type own = type(e);
        if (own instanceof IntConstT || own instanceof FloatConstT) {
            return null;
        }
        Val v = evalUncast(e, null);
        pinned.put(e, v);
        return v;
    }

    // ================================================================ array<T>

    private static boolean isArray(Type t) {
        return Purity.isArray(t);
    }

    private static Type element(Type arrayType) {
        return ((ClassT) arrayType).typeArgs().get(0);
    }

    /** new array<T>(n): n default elements, as the library makes them. */
    private Object[] newArray(Type element, long n) {
        if (n < 0) {
            throw fail("threw IllegalArgumentException: an array can't have length " + n);
        }
        Object[] items = new Object[(int) n];
        for (int i = 0; i < items.length; i++) {
            items[i] = zero(element);
        }
        return items;
    }

    /** An index into an array<T>, checked as the library checks it. */
    private int arrayIndex(long i, int length) {
        if (i < 0 || i >= length) {
            throw fail("threw IndexOutOfBoundsException: index " + i + " out of range for an array of length " + length);
        }
        return (int) i;
    }

    /** xs.length(), xs.copy(), xs.sort() and the rest of the array's own methods, as stdlib/array.seal has them. */
    private Val arrayMethod(Call c, CallInfo ci, Expr receiver) {
        Object[] items = array(receiver);
        Type t = element(ci.owner());
        List<Type> params = ck.paramTypes(ci.owner(), ci.member(), ci.binders());
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < c.args().size(); i++) {
            args.add(stored(c.args().get(i), params.get(i)));
        }
        Type ret = ck.returnTypeOf(ci.owner(), ci.member(), ci.binders());
        String name = ci.member().name();
        Object result = switch (name) {
            case "length" -> (long) items.length;
            case "isEmpty" -> items.length == 0;
            case "get" -> items[arrayIndex((Long) args.get(0), items.length)];
            case "set" -> {
                items[arrayIndex((Long) args.get(0), items.length)] = args.get(1);
                yield null;
            }
            case "copy" -> copy(items);
            case "copyOf" -> {
                long n = (Long) args.get(0);
                Object[] out = newArray(t, n);
                for (int i = 0; i < Math.min(out.length, items.length); i++) {
                    out[i] = copy(items[i]);
                }
                yield out;
            }
            case "slice" -> {
                long from = (Long) args.get(0);
                long to = (Long) args.get(1);
                if (from < 0 || to > items.length || from > to) {
                    throw fail("threw IndexOutOfBoundsException: slice(" + from + ", " + to
                            + ") out of range for an array of length " + items.length);
                }
                yield copy(Arrays.copyOfRange(items, (int) from, (int) to));
            }
            case "indexOf", "contains" -> {
                long found = -1;
                for (int i = 0; i < items.length && found < 0; i++) {
                    tick(c.pos());
                    if (same(t, items[i], args.get(0))) {
                        found = i;
                    }
                }
                yield name.equals("contains") ? (Object) (found >= 0) : (Object) found;
            }
            case "fill" -> {
                for (int i = 0; i < items.length; i++) {
                    items[i] = copy(args.get(0));
                }
                yield null;
            }
            case "swap" -> {
                swap(items, (Long) args.get(0), (Long) args.get(1));
                yield null;
            }
            case "reverse" -> {
                for (int i = 0, j = items.length - 1; i < j; i++, j--) {
                    swap(items, i, j);
                }
                yield null;
            }
            case "sort" -> {
                if (!args.isEmpty()) {
                    throw fail("calls sort(Comparator), which isn't pure");
                }
                sort(items, t, c.pos());
                yield null;
            }
            default -> throw fail("calls " + ci.owner() + "." + name + "(), which compile-time evaluation doesn't run");
        };
        return new Val(result, ret);
    }

    /** swap(i, j) as the library's: t = get(i); set(i, get(j)); set(j, t). */
    private void swap(Object[] items, long i, long j) {
        Object t = items[arrayIndex(i, items.length)];
        items[(int) i] = items[arrayIndex(j, items.length)];
        items[(int) j] = t;
    }

    /** The library's sort(): heapsort with <, so equal and unordered elements end up as they do when the program runs. */
    private void sort(Object[] items, Type t, Pos p) {
        int n = items.length;
        for (int start = n / 2 - 1; start >= 0; start--) {
            siftDown(items, t, start, n, p);
        }
        for (int end = n - 1; end > 0; end--) {
            swap(items, 0, end);
            siftDown(items, t, 0, end, p);
        }
    }

    private void siftDown(Object[] items, Type t, int start, int end, Pos p) {
        int root = start;
        while (root * 2 + 1 < end) {
            tick(p);
            int child = root * 2 + 1;
            if (child + 1 < end && less(t, items[child], items[child + 1])) {
                child++;
            }
            if (!less(t, items[root], items[child])) {
                return;
            }
            swap(items, root, child);
            root = child;
        }
    }

    /** a < b for elements of type t, as the program compares them. */
    private boolean less(Type t, Object a, Object b) {
        return switch (a) {
            case Long x when isNumber(t) -> fullUnsigned(t) && width(t) == 64 ? Long.compareUnsigned(x, (Long) b) < 0 : x < (Long) b;
            case Float x -> x < (Float) b;
            case Double x -> x < (Double) b;
            case String x -> x.compareTo((String) b) < 0;
            default -> throw fail("sorts " + t + " values, which compile-time evaluation doesn't support");
        };
    }

    /** a == b for elements of type t, as indexOf compares them. */
    private boolean same(Type t, Object a, Object b) {
        return switch (a) {
            case Float x -> x.floatValue() == (Float) b;
            case Double x -> x.doubleValue() == (Double) b;
            case Long x -> x.equals(b);
            case Boolean x -> x.equals(b);
            case String x -> x.equals(b);
            default -> throw fail("compares " + t + " values, which compile-time evaluation doesn't support");
        };
    }

    // ================================================================ numbers

    /** Both operands in type t: the result, wrapped to t, as the backends compute it. */
    private Object arithmetic(BinaryOp op, Type t, Object l, Object r) {
        if (t instanceof FixedT ft && (op == BinaryOp.MUL || op == BinaryOp.DIV)) {
            try {
                long v = op == BinaryOp.MUL ? FixedPoint.mul((Long) l, (Long) r, fracOf(ft), ft.signed())
                        : FixedPoint.div((Long) l, (Long) r, fracOf(ft), ft.signed());
                return wrap(v, ft);
            } catch (io.github.thelinuxseal.seal.runtime.Trap e) {
                throw fail("divided by zero");
            }
        }
        Type it = raw(t);
        switch (it) {
            case BitT b -> {
                boolean x = (Boolean) l;
                boolean y = (Boolean) r;
                return switch (op) {
                    case BIT_AND -> x & y;
                    case BIT_OR -> x | y;
                    case BIT_XOR -> x ^ y;
                    default -> throw fail("uses operator " + op.symbol + " on bit");
                };
            }
            case FloatT f -> {
                float x = (Float) l;
                float y = (Float) r;
                return switch (op) {
                    case ADD -> x + y;
                    case SUB -> x - y;
                    case MUL -> x * y;
                    case DIV -> x / y;
                    case POW -> Ints.pow(x, y);
                    default -> throw fail("uses operator " + op.symbol + " on float");
                };
            }
            case DoubleT d -> {
                double x = (Double) l;
                double y = (Double) r;
                return switch (op) {
                    case ADD -> x + y;
                    case SUB -> x - y;
                    case MUL -> x * y;
                    case DIV -> x / y;
                    case POW -> Ints.pow(x, y);
                    default -> throw fail("uses operator " + op.symbol + " on double");
                };
            }
            case IntT n -> {
                long x = (Long) l;
                long y = (Long) r;
                boolean unsigned64 = fullUnsigned(it) && width(it) == 64;
                long v = switch (op) {
                    case ADD -> x + y;
                    case SUB -> x - y;
                    case MUL -> x * y;
                    case POW -> {
                        if (n.signed() && !n.isChar() && y < 0 && x == 0) {
                            throw fail("divided by zero");
                        }
                        yield Ints.pow(x, y, n.signed() && !n.isChar());
                    }
                    case DIV -> {
                        if (y == 0) {
                            throw fail("divided by zero");
                        }
                        yield unsigned64 ? Long.divideUnsigned(x, y) : x / y;
                    }
                    case MOD -> {
                        if (y == 0) {
                            throw fail("divided by zero");
                        }
                        yield unsigned64 ? Long.remainderUnsigned(x, y) : x % y;
                    }
                    case BIT_AND -> x & y;
                    case BIT_OR -> x | y;
                    case BIT_XOR -> x ^ y;
                    case SHIFT -> shift(it, x, (int) y);
                    default -> throw fail("uses operator " + op.symbol + " on " + t);
                };
                return wrap(v, it);
            }
            default -> throw fail("does arithmetic on " + t + ", which compile-time evaluation doesn't support");
        }
    }

    /** Seal's <<< (§50), on a value of t's width. */
    private static long shift(Type t, long v, int amount) {
        IntT it = (IntT) raw(t);
        return Ints.shift(v, amount, width(it), it.signed() && !it.isChar());
    }

    /** Converts between number types as a cast does (§41.1, §37.1). */
    private Val convert(Val v, Type to) {
        Type from = v.t();
        if (from.equals(to)) {
            return v;
        }
        boolean fromFloat = from instanceof FloatT || from instanceof DoubleT;
        boolean toFloat = to instanceof FloatT || to instanceof DoubleT;
        if (from instanceof FixedT || to instanceof FixedT) {
            return new Val(fixedCast(v.v(), from, to, fromFloat, toFloat), to);
        }
        if (from instanceof IntT && to instanceof IntT) {
            return new Val(wrap((Long) v.v(), to), to);
        }
        if (from instanceof IntT f && toFloat) {
            double d = toDouble((Long) v.v(), f);
            return new Val(to instanceof FloatT ? (Object) (float) d : (Object) d, to); // through a double, as the VM converts
        }
        if (fromFloat && to instanceof IntT) {
            // Toward zero into a long, then wrapped (§41.1).
            long x = from instanceof FloatT ? (long) (float) (Float) v.v() : (long) (double) (Double) v.v();
            return new Val(wrap(x, to), to);
        }
        if (from instanceof FloatT && to instanceof DoubleT) {
            return new Val((double) (Float) v.v(), to);
        }
        if (from instanceof DoubleT && to instanceof FloatT) {
            return new Val((float) (double) (Double) v.v(), to);
        }
        if (v.v() instanceof String) {
            return new Val(v.v(), to); // both shapes of string
        }
        throw fail("converts " + from + " to " + to + ", which compile-time evaluation doesn't support");
    }

    private static double toDouble(long x, Type t) {
        return fullUnsigned(t) && width(t) == 64 ? Ints.unsignedToDouble(x) : (double) x;
    }

    private Object fixedCast(Object v, Type from, Type to, boolean fromFloat, boolean toFloat) {
        if (from instanceof FixedT ff && to instanceof FixedT tf) {
            return wrap(FixedPoint.rescale((Long) v, fracOf(tf) - fracOf(ff), ff.signed(), true), tf);
        }
        if (from instanceof IntT fi && to instanceof FixedT tf) {
            return wrap(FixedPoint.rescale((Long) v, fracOf(tf), fi.signed() && !fi.isChar(), true), tf);
        }
        if (from instanceof FixedT ff && to instanceof IntT ti) {
            return wrap(FixedPoint.rescale((Long) v, -fracOf(ff), ff.signed(), false), ti); // toward zero
        }
        if (from instanceof FixedT ff && toFloat) {
            double d = toDouble((Long) v, raw(ff)) * Math.scalb(1.0, -fracOf(ff));
            return to instanceof FloatT ? (Object) (float) d : (Object) d;
        }
        if (fromFloat && to instanceof FixedT tf) {
            double d = from instanceof FloatT ? (double) (Float) v : (Double) v;
            return wrap((long) Math.floor(d * Math.scalb(1.0, fracOf(tf)) + 0.5), tf);
        }
        throw fail("converts " + from + " to " + to + ", which compile-time evaluation doesn't support");
    }

    /** A value where it meets a wider type: between integers nothing changes, as they are kept. */
    private Val widen(Val v, Type to) {
        Type from = v.t();
        if (from.equals(to) || !isNumber(from) || !isNumber(to) || (from instanceof IntT && to instanceof IntT)) {
            return new Val(v.v(), to);
        }
        return convert(v, to);
    }

    /** A compile-time value as type t. */
    private Object constant(CVal v, Type t) {
        switch (v) {
            case IntV i when t instanceof FixedT ft -> {
                return fixedConstant(new BigDecimal(i.value()), ft);
            }
            case FloatV f when t instanceof FixedT ft -> {
                return fixedConstant(new BigDecimal(f.value()), ft);
            }
            case IntV i when t instanceof IntT -> {
                return wrap(i.value().longValue(), t);
            }
            case IntV i when t instanceof FloatT -> {
                return i.value().floatValue();
            }
            case IntV i when t instanceof DoubleT -> {
                return i.value().doubleValue();
            }
            case FloatV f when t instanceof FloatT -> {
                return f.value().floatValue();
            }
            case FloatV f when t instanceof DoubleT -> {
                return f.value();
            }
            case BitV b -> {
                return b.value();
            }
            case StrV s -> {
                return s.value();
            }
            default -> throw fail("uses the compile-time value " + v + " as a " + t);
        }
    }

    /** round(v × 2^M), wrapped, as the backends make a fixed-point constant. */
    private static long fixedConstant(BigDecimal v, FixedT t) {
        return wrap(Checker.fixedRaw(v, fracOf(t)).longValue(), t);
    }

    /** The value a variable of type t starts as. */
    private Object zero(Type t) {
        return switch (t) {
            case IntT i -> 0L;
            case FixedT f -> 0L;
            case BitT b -> false;
            case FloatT f -> 0f;
            case DoubleT d -> 0.0;
            case ClassT ct when ct.cls().builtin && ct.cls().name.equals("string") -> "";
            case ClassT ct when isArray(ct) -> new Object[0];
            case ArrayT arr when arr.size() != null -> {
                Object[] items = new Object[arr.size().intValue().intValueExact()];
                for (int i = 0; i < items.length; i++) {
                    items[i] = zero(arr.element());
                }
                yield items;
            }
            default -> throw fail("uses values of type " + t + ", which compile-time evaluation doesn't support yet");
        };
    }

    /** v wrapped to t's width and kept as the backends keep it: sign-extended if signed, zero-extended if not. */
    private static long wrap(long v, Type t) {
        IntT it = (IntT) raw(t);
        if (it.isChar()) {
            return v & 0xffff;
        }
        if (it.isSize()) {
            if (v < 0 || v > Type.MAX_SIZE) {
                throw new Failure("made " + v + " a size, which holds 0 to " + Type.MAX_SIZE, null);
            }
            return v;
        }
        int w = width(it);
        if (w >= 64) {
            return v;
        }
        return it.signed() ? (v << (64 - w)) >> (64 - w) : v & ((1L << w) - 1);
    }

    private static long mask(long bits) {
        return bits >= 64 ? -1L : (1L << bits) - 1;
    }

    private static Type raw(Type t) {
        return t instanceof FixedT f ? new IntT(f.width(), f.signed()) : t;
    }

    private static int width(Type t) {
        return switch (raw(t)) {
            case IntT it -> it.width().intValue().intValueExact();
            default -> throw new IllegalStateException("no width: " + t);
        };
    }

    private static int fracOf(FixedT t) {
        return t.fracBits().intValue().intValueExact();
    }

    private static boolean isNumber(Type t) {
        return t instanceof IntT || t instanceof FixedT || t instanceof FloatT || t instanceof DoubleT;
    }

    private static boolean fullUnsigned(Type t) {
        return raw(t) instanceof IntT it && !it.signed() && !it.isChar() && (width(it) == 32 || width(it) == 64);
    }

    private static Type concrete(Type t) {
        if (t instanceof IntConstT) {
            return new IntT(CVal.of(64), true);
        }
        if (t instanceof FloatConstT) {
            return Type.DOUBLE;
        }
        return t;
    }

    // ================================================================ helpers

    private Type type(Expr e) {
        Type t = fn.tb().types.get(e);
        if (t == null) {
            throw fail("uses an expression the checker didn't type");
        }
        return t;
    }

    private void tick(Pos p) {
        at = p;
        if (++steps > MAX_STEPS) {
            throw fail("ran for more than " + String.format("%,d", MAX_STEPS) + " steps; does it end?");
        }
    }

    private Failure fail(String message) {
        String where = fn == null || at == null ? null
                : java.nio.file.Path.of(fn.tb().file).getFileName() + ":" + at.line();
        return new Failure(message, where);
    }

    /** A value kept, or passed: a fixed array is copied, whatever it holds. */
    private static Object copy(Object v) {
        if (v instanceof Object[] items) {
            Object[] out = new Object[items.length];
            for (int i = 0; i < items.length; i++) {
                out[i] = copy(items[i]);
            }
            return out;
        }
        return v;
    }

    /** Arguments as a key: arrays by their contents. */
    private static List<Object> keyOf(List<Object> args) {
        List<Object> out = new ArrayList<>();
        for (Object a : args) {
            out.add(a instanceof Object[] items ? keyOf(Arrays.asList(items)) : a);
        }
        return out;
    }
}
