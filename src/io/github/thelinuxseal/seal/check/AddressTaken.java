package io.github.thelinuxseal.seal.check;

import java.util.HashSet;
import java.util.Set;

import io.github.thelinuxseal.seal.ast.Ast.*;

/**
 * Finds local variables whose address is taken with `*x` (directly, or of a
 * field or element inside them). Those must live in frame memory; other
 * scalar locals can live in registers (on the VM) or JVM locals.
 */
public final class AddressTaken {

    private final Set<String> names = new HashSet<>();

    public static Set<String> in(Stmt body) {
        AddressTaken a = new AddressTaken();
        if (body != null) {
            a.stmt(body);
        }
        return a.names;
    }

    private void stmt(Stmt s) {
        switch (s) {
            case Block b -> b.stmts().forEach(this::stmt);
            case VarDecl v -> expr(v.init());
            case ExprStmt e -> expr(e.expr());
            case If i -> {
                expr(i.cond());
                stmt(i.then());
                if (i.otherwise() != null) {
                    stmt(i.otherwise());
                }
            }
            case While w -> {
                expr(w.cond());
                stmt(w.body());
            }
            case Throw t -> expr(t.value());
            case Try t -> {
                t.resources().forEach(this::stmt);
                stmt(t.body());
                t.catches().forEach(c -> stmt(c.body()));
                if (t.finallyBlock() != null) {
                    stmt(t.finallyBlock());
                }
            }
            case Switch sw -> {
                expr(sw.subject());
                for (Case c : sw.cases()) {
                    c.labels().forEach(this::expr);
                    c.body().forEach(this::stmt);
                }
            }
            case ForEach f -> {
                expr(f.iterable());
                stmt(f.body());
            }
            case For f -> {
                if (f.init() != null) {
                    stmt(f.init());
                }
                expr(f.cond());
                expr(f.update());
                stmt(f.body());
            }
            case Return r -> expr(r.value());
            case DeleteStmt d -> expr(d.target());
            case Labeled l -> stmt(l.body());
            case Yield y -> expr(y.value());
            case Assert a -> {
                expr(a.cond());
                expr(a.message());
            }
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
        switch (e) {
            case Unary u -> {
                if (u.op() == UnaryOp.ADDRESS_OF) {
                    String root = root(u.operand());
                    if (root != null) {
                        names.add(root);
                    }
                }
                expr(u.operand());
            }
            case Select s -> expr(s.target());
            case IncDec i -> expr(i.target());
            case Call c -> {
                expr(c.callee());
                c.args().forEach(this::expr);
            }
            case Index i -> {
                expr(i.target());
                expr(i.index());
            }
            case Slice s -> {
                expr(s.target());
                expr(s.from());
                expr(s.to());
            }
            case Binary b -> {
                expr(b.left());
                expr(b.right());
            }
            case Assign a -> {
                expr(a.target());
                expr(a.value());
            }
            case Cast c -> expr(c.operand());
            case Conditional c -> {
                expr(c.cond());
                expr(c.then());
                expr(c.otherwise());
            }
            case InstanceOf i -> expr(i.operand());
            case New n -> n.args().forEach(this::expr);
            case NewArray n -> expr(n.count());
            case ArrayLit a -> a.elements().forEach(this::expr);
            case SwitchExpr sw -> {
                expr(sw.subject());
                sw.cases().forEach(c -> c.body().forEach(this::stmt));
            }
            default -> {
            }
        }
    }

    /** The variable at the root of `x`, `x.f`, `x[i]`, ... or null. */
    private static String root(Expr e) {
        return switch (e) {
            case Name n -> n.name();
            case Select s -> root(s.target());
            case Index i -> root(i.target());
            case Slice s -> root(s.target());
            default -> null;
        };
    }
}
