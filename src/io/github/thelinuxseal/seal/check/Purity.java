package io.github.thelinuxseal.seal.check;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.thelinuxseal.seal.ast.Ast;
import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.Checker.CallInfo;
import io.github.thelinuxseal.seal.check.Checker.CallKind;
import io.github.thelinuxseal.seal.check.Checker.TypedBody;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Type.*;

/**
 * The rules a pure method's body keeps (§17.2), so that its result depends only on its arguments
 * and it can run while compiling: it reads no field but its arguments' and `const` ones, changes
 * no class object, calls only pure methods (it may `throw new` an exception), and uses no
 * addresses. Arrays are the one object it may make and use: `new array<T>(n)`, indexing, and the
 * array's own methods; an array it was given, which its caller lent it, it only reads. Lambdas
 * inside it keep the same rules.
 */
final class Purity {

    record Problem(Pos pos, String message) {
    }

    /** The methods of array<T> a pure method may call on any array, and those it may call only on its own. */
    static final Set<String> ARRAY_READS = Set.of("length", "isEmpty", "get", "copy", "copyOf", "slice", "indexOf", "contains");
    static final Set<String> ARRAY_CHANGES = Set.of("set", "fill", "swap", "reverse", "sort");

    private final Checker ck;
    private final TypedBody tb;
    /** The method's parameters, and its lambdas': what its caller gave it. */
    private final Set<String> given = new HashSet<>();
    private final List<Problem> problems = new ArrayList<>();

    private Purity(Checker ck, TypedBody tb) {
        this.ck = ck;
        this.tb = tb;
    }

    /** What in a pure method's body breaks the rules; empty if nothing does. */
    static List<Problem> of(Checker ck, TypedBody tb, Method md) {
        Purity p = new Purity(ck, tb);
        md.params().forEach(param -> p.given.add(param.name()));
        if (md.body() != null) {
            p.stmt(md.body());
        }
        return p.problems;
    }

    /** array<T>, the run-time-sized array (§21.3). */
    static boolean isArray(Type t) {
        return t instanceof ClassT ct && ct.cls().builtin && ct.cls().name.equals("array") && ct.valueArgs().isEmpty();
    }

    private void problem(Pos at, String message) {
        problems.add(new Problem(at, "a pure method " + message));
    }

    private void stmt(Stmt s) {
        if (s == null) {
            return;
        }
        Stmt instead = tb.desugared.get(s);
        if (instead == null) {
            instead = tb.statementSteps.get(s);
        }
        if (instead != null) {
            stmt(instead);
            return;
        }
        switch (s) {
            case Block b -> b.stmts().forEach(this::stmt);
            case VarDecl v -> expr(v.init());
            case ExprStmt e -> expr(e.expr());
            case If i -> {
                expr(i.cond());
                stmt(i.then());
                stmt(i.otherwise());
            }
            case While w -> {
                expr(w.cond());
                stmt(w.body());
            }
            case For f -> {
                stmt(f.init());
                expr(f.cond());
                expr(f.update());
                stmt(f.body());
            }
            case ForEach f -> {
                expr(f.iterable());
                stmt(f.body());
            }
            case Switch sw -> {
                expr(sw.subject());
                for (Case c : sw.cases()) {
                    c.labels().forEach(this::expr);
                    c.body().forEach(this::stmt);
                }
            }
            case Throw t -> {
                // Throwing is how a pure method refuses its arguments: the exception it makes is the one object it may.
                if (t.value() instanceof New n) {
                    n.args().forEach(this::expr);
                } else {
                    expr(t.value());
                }
            }
            case Try t -> {
                t.resources().forEach(this::stmt);
                stmt(t.body());
                t.catches().forEach(c -> stmt(c.body()));
                stmt(t.finallyBlock());
            }
            case Return r -> expr(r.value());
            case Yield y -> expr(y.value());
            case Assert a -> {
                expr(a.cond());
                expr(a.message());
            }
            case Labeled l -> stmt(l.body());
            case DeleteStmt d -> problem(d.pos(), "can't use addresses: delete frees memory outside its arguments");
            case Break b -> {
            }
            case Continue c -> {
            }
        }
    }

    private void expr(Expr e) {
        if (e == null) {
            return;
        }
        Expr instead = tb.rewritten.get(e);
        if (instead != null) {
            expr(instead);
            return;
        }
        CallInfo ci = tb.calls.get(e);
        if (ci != null) {
            call(e, ci, receiver(e));
        }
        switch (e) {
            case Name n when "field".equals(tb.nameKinds.get(n)) -> field(n.pos(), n.name(), tb.fieldOf.get(n));
            case Select s when tb.selected.get(s) instanceof MemberInfo m && m.member instanceof Field -> field(s.pos(), s.name(), m);
            case Assign a -> changes(a.target());
            case IncDec i -> {
                changes(i.target());
                expr(tb.elementSteps.get(i)); // xs[i]++ on an array: xs[i] = xs[i] + 1
            }
            case Unary u when u.op() == UnaryOp.ADDRESS_OF || u.op() == UnaryOp.DEREF ->
                    problem(u.pos(), "can't use addresses: they reach memory outside its arguments");
            case NewArray n -> problem(n.pos(), "can't use addresses: new<T>(n) makes memory outside its arguments");
            case Lambda l -> {
                l.params().forEach(param -> given.add(param.name()));
                expr(l.expr());
                stmt(l.block());
            }
            case MethodRef r when tb.methodRefs.get(r) instanceof Lambda l -> {
                l.params().forEach(param -> given.add(param.name()));
                expr(l.expr()); // the lambda it is
            }
            case SwitchExpr se -> se.cases().forEach(c -> {
                c.labels().forEach(this::expr);
                c.body().forEach(this::stmt);
            });
            default -> {
            }
        }
        for (Expr child : Ast.children(e)) {
            expr(child);
        }
    }

    /** What a method or operator is called on: the array of xs.length() and xs[i]; null for others. */
    private static Expr receiver(Expr e) {
        return switch (e) {
            case Call c when c.callee() instanceof Select s -> s.target();
            case Index ix -> ix.target();
            case Assign a when a.target() instanceof Index ix -> ix.target();
            default -> null;
        };
    }

    /** A call, `new` or overloaded operator: only to a pure method, an array's, or a string literal made into a string. */
    private void call(Expr at, CallInfo ci, Expr receiver) {
        MemberInfo m = ci.member();
        if (ci.kind() == CallKind.STATIC && m != null && m.member instanceof Method md && md.modifiers().isPure()) {
            return;
        }
        if (isArray(ci.owner())) {
            arrayCall(at, ci, receiver);
            return;
        }
        if (ci.kind() == CallKind.CONSTRUCTOR && ci.owner() != null && ci.owner().cls().builtin
                && ci.owner().cls().name.equals("string")) {
            return; // a string from a literal's characters
        }
        String owner = ci.owner() != null ? ci.owner().toString() : m != null ? ck.declaringClassOf(m).qualifiedName() : "?";
        String name = m == null ? "new" : m.member instanceof Method md ? md.name() : "new";
        String what = owner + "." + name;
        switch (ci.kind()) {
            case CONSTRUCTOR, SUPER_CONSTRUCTOR -> problem(at.pos(), "can't make objects: new " + owner
                    + " runs its constructor (a pure method may only `throw new` an exception)");
            case OPERATOR -> problem(at.pos(), "can only call pure methods, and this operator calls " + what
                    + ", which isn't pure");
            case METHOD, SUPER_METHOD -> problem(at.pos(), "can only call pure methods, which are static, and "
                    + what + " belongs to an object");
            default -> problem(at.pos(), "can only call pure methods, and " + what + " isn't pure; declare it `static pure`");
        }
    }

    /** new array<T>(n), xs[i], xs[i] = v and the array's own methods; those that change it only on its own arrays. */
    private void arrayCall(Expr at, CallInfo ci, Expr receiver) {
        if (ci.kind() == CallKind.CONSTRUCTOR) {
            return;
        }
        String name = ci.member() == null ? "?" : ci.member().name();
        int params = ci.member() != null && ci.member().member instanceof Method md ? md.params().size() : 0;
        boolean changes = ARRAY_CHANGES.contains(name) && !(name.equals("sort") && params > 0);
        if (!changes && !ARRAY_READS.contains(name)) {
            problem(at.pos(), "can use an array's own methods, but not " + name + (name.equals("sort") ? "(Comparator)" : "()")
                    + ", which " + (name.equals("toList") ? "makes a List, a class object" : "calls code that isn't pure"));
            return;
        }
        String root = changes ? root(receiver) : null;
        if (root != null && given.contains(root)) {
            problem(at.pos(), "can't change an array it was given: " + root + " is its caller's, lent to it; change a copy, "
                    + root + ".copy()");
        }
    }

    /** The variable at the root of x, x.f, x[i]: the one a change to them changes. */
    private String root(Expr e) {
        return switch (e) {
            case Name n when "local".equals(tb.nameKinds.get(n)) -> n.name();
            case Select s -> root(s.target());
            case Index i -> root(i.target());
            case null, default -> null;
        };
    }

    private void field(Pos at, String name, MemberInfo m) {
        if (m != null && m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()) {
            problem(at, "can't use the static field " + name + ": its result may depend only on its arguments"
                    + " (const fields are fine)");
        }
    }

    /** An assignment's target: its own variables and copies, not a class object its caller can see. */
    private void changes(Expr target) {
        for (Expr t = target; t instanceof Select || t instanceof Index || t instanceof Slice; ) {
            Expr inner = switch (t) {
                case Select s -> s.target();
                case Index i -> i.target();
                case Slice s -> s.target();
                default -> throw new IllegalStateException();
            };
            if (tb.types.get(inner) instanceof HandleT h) {
                problem(target.pos(), "can't change " + h.target() + " objects: its caller shares them");
                return;
            }
            t = inner;
        }
    }
}
