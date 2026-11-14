package io.github.thelinuxseal.seal.jvm;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.Checker.CallInfo;
import io.github.thelinuxseal.seal.check.Checker.TypedBody;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Type;

/**
 * The value locals of a body that the JVM can hold more simply than in its frame block (docs/JVM.md
 * §4.5), each by its name:
 *
 * <ul>
 * <li>a fixed array the body only indexes (reading an element, setting it, stepping it, a bit or
 * slice of it) and asks the length of: a Java array of its elements in a JVM local. Its
 * declarations have no initializer or a literal one.</li>
 * <li>a struct the body only reads and sets the fields of, and copies whole (its declaration's
 * initializer, the value of {@code x = y}, an argument, what is returned) or sets whole
 * ({@code y = x}): one JVM local per field.</li>
 * </ul>
 *
 * Any other use of the name keeps it in the block: a method called on it, an operator, a cast,
 * a use in a lambda, behind an address. It walks what code generation walks, the checker's
 * rewritten expressions and desugared statements for the ones written, and every part of a node
 * it doesn't know, so a use it hasn't foreseen counts as one of those.
 */
final class LocalForms {

    private enum Form { ARRAY, STRUCT }

    private final TypedBody tb;
    private final Predicate<Type> fits;
    private final Form form;
    private final Set<String> declared = new HashSet<>();
    private final Set<String> bad = new HashSet<>();

    private LocalForms(TypedBody tb, Predicate<Type> fits, Form form) {
        this.tb = tb;
        this.fits = fits;
        this.form = form;
    }

    /** The fixed-array locals to hold as Java arrays: each declaration of the name of a type fits accepts. */
    static Set<String> javaArrays(Stmt body, TypedBody tb, Set<String> addressTaken, Predicate<Type> fits) {
        return new LocalForms(tb, fits, Form.ARRAY).run(body, addressTaken);
    }

    /** The struct locals to hold as a JVM local per field: each declaration of the name of a type fits accepts. */
    static Set<String> splitStructs(Stmt body, TypedBody tb, Set<String> addressTaken, Predicate<Type> fits) {
        return new LocalForms(tb, fits, Form.STRUCT).run(body, addressTaken);
    }

    private Set<String> run(Stmt body, Set<String> addressTaken) {
        if (body != null) {
            stmt(body);
        }
        Set<String> out = new HashSet<>(declared);
        out.removeAll(bad);
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
        switch (s) {
            case VarDecl v -> {
                Type t = tb.locals.get(v);
                boolean init = form == Form.STRUCT || v.init() == null
                        || (v.init() instanceof ArrayLit && !tb.rewritten.containsKey(v.init()));
                if (t != null && fits.test(t) && init) {
                    declared.add(v.name());
                } else {
                    bad.add(v.name());
                }
                copied(v.init());
            }
            case Return r -> copied(r.value());
            default -> parts(s);
        }
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
            case Index ix when form == Form.ARRAY && isLocal(ix.target()) && !tb.calls.containsKey(ix) -> expr(ix.index()); // xs[i]
            case Call c when form == Form.ARRAY && c.callee() instanceof Select s && isLocal(s.target())
                    && s.name().equals("length") && c.args().isEmpty() && !tb.rewritten.containsKey(c.callee()) -> {
                // xs.length()
            }
            case Select s when form == Form.STRUCT && isLocal(s.target()) && tb.selected.get(s) instanceof MemberInfo m
                    && m.member instanceof Field -> {
                // p.x, read or set
            }
            case Assign a when form == Form.STRUCT && a.op() == null -> {
                if (!isLocal(a.target())) {
                    expr(a.target()); // xs[i] = p, p.x = v: a place of its own
                }
                copied(a.value()); // p = q
            }
            case Call c when form == Form.STRUCT && tb.calls.get(c) instanceof CallInfo && !tb.javaCalls.containsKey(c) -> {
                expr(c.callee());
                c.args().forEach(this::copied); // f(p): a copy, which the callee may change
            }
            case New n when form == Form.STRUCT && tb.calls.get(n) instanceof CallInfo && !tb.javaCalls.containsKey(n) -> {
                n.args().forEach(this::copied);
            }
            case Lambda l -> names(l); // what a lambda captures is its own
            default -> parts(e);
        }
    }

    /** An expression whose value is copied: a struct local's whole is a use a struct can have. */
    private void copied(Expr e) {
        if (form == Form.STRUCT && e != null && isLocal(e)) {
            return;
        }
        expr(e);
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
