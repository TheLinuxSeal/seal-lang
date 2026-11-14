package io.github.thelinuxseal.seal.jvm;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.Checker.TypedBody;
import io.github.thelinuxseal.seal.check.Type;

/**
 * The fixed-array locals of a body that can be Java arrays in JVM locals, not values in its frame
 * block (docs/JVM.md §4.5): those the body only indexes (reading an element, setting it, stepping
 * it, a bit or slice of it) and asks the length of. Any other use of the name, as a whole value
 * (passed, returned, copied, assigned, a method called on it), in a lambda, or behind an address,
 * keeps it in the block, and so does an initializer but a literal's. It walks what code generation
 * walks: the checker's rewritten expressions and desugared statements for the ones written, and
 * every part of a node it doesn't know, so a use it hasn't foreseen counts as an unknown one.
 */
final class LocalForms {

    private final TypedBody tb;
    private final Predicate<Type> plain;
    private final Set<String> declared = new HashSet<>();
    private final Set<String> bad = new HashSet<>();

    private LocalForms(TypedBody tb, Predicate<Type> plain) {
        this.tb = tb;
        this.plain = plain;
    }

    /**
     * The names, in a body, of fixed-array locals to hold as Java arrays: each declaration of the
     * name is one of a type that plain accepts, and each use of it is one of those above.
     */
    static Set<String> javaArrays(Stmt body, TypedBody tb, Set<String> addressTaken, Predicate<Type> plain) {
        LocalForms f = new LocalForms(tb, plain);
        if (body != null) {
            f.stmt(body);
        }
        Set<String> out = new HashSet<>(f.declared);
        out.removeAll(f.bad);
        out.removeAll(addressTaken);
        return out;
    }

    private void stmt(Stmt s) {
        if (tb.statementSteps.containsKey(s)) {
            stmt(tb.statementSteps.get(s));
            return;
        }
        if ((s instanceof ForEach || s instanceof Try t && !t.resources().isEmpty()) && tb.desugared.get(s) instanceof Stmt d) {
            stmt(d);
            return;
        }
        if (s instanceof VarDecl v) {
            Type t = tb.locals.get(v);
            boolean literal = v.init() == null || (v.init() instanceof ArrayLit && !tb.rewritten.containsKey(v.init()));
            if (t != null && plain.test(t) && literal) {
                declared.add(v.name());
            } else {
                bad.add(v.name());
            }
            expr(v.init());
            return;
        }
        parts(s);
    }

    private void expr(Expr e) {
        if (e == null) {
            return;
        }
        if (tb.rewritten.get(e) instanceof Expr instead) {
            expr(instead);
            return;
        }
        switch (e) {
            case Name n -> {
                if ("local".equals(tb.nameKinds.get(n))) {
                    bad.add(n.name()); // the whole value
                }
            }
            case Index ix when isLocal(ix.target()) && !tb.calls.containsKey(ix) -> expr(ix.index()); // xs[i]
            case Call c when c.callee() instanceof Select s && isLocal(s.target()) && s.name().equals("length")
                    && c.args().isEmpty() && !tb.rewritten.containsKey(c.callee()) -> {
                // xs.length()
            }
            case Lambda l -> names(l); // what a lambda captures is its own
            default -> parts(e);
        }
    }

    private boolean isLocal(Expr e) {
        return e instanceof Name n && "local".equals(tb.nameKinds.get(n)) && !tb.rewritten.containsKey(n);
    }

    /** Every statement and expression a node holds, in its record components and lists of them. */
    private void parts(Object node) {
        RecordComponent[] rcs = node.getClass().getRecordComponents();
        if (rcs == null) {
            return; // the AST's nodes are all records
        }
        for (RecordComponent rc : rcs) {
            Object v;
            try {
                v = rc.getAccessor().invoke(node);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
            part(v);
        }
    }

    private void part(Object v) {
        switch (v) {
            case Stmt s -> stmt(s);
            case Expr x -> expr(x);
            case List<?> list -> list.forEach(this::part);
            case Record r -> parts(r);
            case null, default -> {
            }
        }
    }

    /** Marks every name inside a node as used in a way that keeps it in the block. */
    private void names(Object v) {
        switch (v) {
            case Name n -> bad.add(n.name());
            case List<?> list -> list.forEach(this::names);
            case Record r -> {
                for (RecordComponent rc : r.getClass().getRecordComponents()) {
                    try {
                        names(rc.getAccessor().invoke(r));
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
            case null, default -> {
            }
        }
    }
}
