package io.github.thelinuxseal.seal.ast;

import java.util.List;
import java.util.stream.Collectors;

import io.github.thelinuxseal.seal.ast.Ast.*;

/**
 * Prints the syntax tree as compact S-expressions, e.g. `(+ a (* b c))`.
 * Used for tests and debugging.
 */
public final class AstPrinter {

    private AstPrinter() {
    }

    // ================================================================ classes and members

    public static String print(ClassDecl c) {
        StringBuilder sb = new StringBuilder("(class");
        for (ClassHeader h : c.headers()) {
            sb.append("\n  ").append(print(h));
        }
        for (Member m : c.members()) {
            sb.append("\n  ").append(print(m));
        }
        for (NestedType n : c.nested()) {
            sb.append("\n  (nested").append(mods(n.modifiers())).append(" ")
                    .append(print(n.decl()).replace("\n", "\n  ")).append(")");
        }
        return sb.append(")").toString();
    }

    public static String print(ClassHeader h) {
        StringBuilder sb = new StringBuilder("(header ");
        sb.append(h.isAbstract() ? "abstract " : "").append(h.kind().keyword).append(' ').append(h.name());
        if (!h.typeParams().isEmpty()) {
            sb.append("<").append(String.join(", ", h.typeParams())).append(">");
        }
        if (h.valueParams() != null) {
            sb.append(print(h.valueParams()));
        }
        for (ExtendsClause e : h.extendsClauses()) {
            sb.append(" extends");
            if (e.guard() != null) {
                sb.append(print(e.guard()));
            }
            sb.append("(").append(print(e.parent())).append(")");
        }
        if (!h.interfaces().isEmpty()) {
            sb.append(h.kind() == Kind.INTERFACE ? " extends(" : " implements(")
                    .append(join(h.interfaces().stream().map(AstPrinter::print).toList())).append(")");
        }
        return sb.append(")").toString();
    }

    public static String print(Member m) {
        return switch (m) {
            case Field f -> "(field" + mods(f.modifiers()) + " " + print(f.type()) + " " + f.name()
                    + guard(f.guard()) + (f.init() == null ? "" : " = " + print(f.init())) + ")";
            case Method md -> "(method" + mods(md.modifiers())
                    + (md.typeParams().isEmpty() ? "" : " <" + String.join(", ", md.typeParams()) + ">")
                    + " " + print(md.returnType()) + " " + md.name()
                    + guard(md.guard()) + " " + params(md.params()) + (md.body() == null ? "" : " " + print(md.body())) + ")";
            case Constructor c -> "(new" + mods(c.modifiers()) + guard(c.guard()) + " " + params(c.params())
                    + " " + print(c.body()) + ")";
            case Destructor d -> "(delete" + mods(d.modifiers()) + guard(d.guard()) + " " + print(d.body()) + ")";
        };
    }

    private static String mods(Modifiers m) {
        StringBuilder sb = new StringBuilder();
        for (Decorator d : m.decorators()) {
            sb.append(" @").append(d.name());
            if (!d.args().isEmpty()) {
                sb.append("(").append(join(d.args().stream().map(AstPrinter::print).toList())).append(")");
            }
        }
        sb.append(m.access() == Access.PUBLIC ? " public" : " private");
        if (!m.friends().isEmpty()) {
            sb.append("<").append(join(m.friends().stream().map(AstPrinter::print).toList())).append(">");
        }
        if (m.isStatic()) {
            sb.append(" static");
        }
        if (m.isAbstract()) {
            sb.append(" abstract");
        }
        if (m.isConst()) {
            sb.append(" const");
        }
        return sb.toString();
    }

    private static String guard(ValueList g) {
        return g == null ? "" : print(g);
    }

    private static String params(List<Param> ps) {
        return "(" + join(ps.stream().map(p -> print(p.type()) + " " + p.name()).toList()) + ")";
    }

    // ================================================================ types

    public static String print(TypeRef t) {
        StringBuilder sb = new StringBuilder(t.name());
        if (!t.typeArgs().isEmpty()) {
            sb.append("<").append(join(t.typeArgs().stream().map(AstPrinter::print).toList())).append(">");
        }
        if (t.valueArgs() != null) {
            sb.append(print(t.valueArgs()));
        }
        for (Expr dim : t.arrayDims()) {
            sb.append("[").append(dim == null ? "" : print(dim)).append("]");
        }
        return sb.toString();
    }

    public static String print(ValueList v) {
        return "<<" + join(v.items().stream().map(AstPrinter::print).toList()) + ">>";
    }

    private static String print(ValueItem item) {
        return switch (item) {
            case Binder b -> print(b.type()) + " " + b.name();
            case ValueExpr e -> print(e.expr());
        };
    }

    // ================================================================ statements

    public static String print(Stmt s) {
        return switch (s) {
            case Block b -> "(block" + b.stmts().stream().map(x -> " " + print(x)).collect(Collectors.joining()) + ")";
            case VarDecl v -> "(var " + print(v.type()) + " " + v.name()
                    + (v.init() == null ? "" : " " + print(v.init())) + ")";
            case ExprStmt e -> print(e.expr());
            case If i -> "(if " + print(i.cond()) + " " + print(i.then())
                    + (i.otherwise() == null ? "" : " " + print(i.otherwise())) + ")";
            case While w -> "(while " + print(w.cond()) + " " + print(w.body()) + ")";
            case Throw t -> "(throw " + print(t.value()) + ")";
            case Try t -> "(try " + print(t.body()) + t.catches().stream().map(c -> " (catch " + print(c.type()) + " "
                    + c.name() + " " + print(c.body()) + ")").collect(Collectors.joining())
                    + (t.finallyBlock() == null ? "" : " (finally " + print(t.finallyBlock()) + ")") + ")";
            case Switch sw -> "(switch " + print(sw.subject()) + sw.cases().stream().map(c -> " (" + (c.isDefault()
                    ? "default" : "case " + c.labels().stream().map(AstPrinter::print).collect(Collectors.joining(" ")))
                    + (c.arrow() ? " ->" : " :") + c.body().stream().map(x -> " " + print(x)).collect(Collectors.joining())
                    + ")").collect(Collectors.joining()) + ")";
            case ForEach f -> "(foreach " + print(f.type()) + " " + f.name() + " " + print(f.iterable()) + " "
                    + print(f.body()) + ")";
            case For f -> "(for " + (f.init() == null ? "_" : print(f.init()))
                    + " " + (f.cond() == null ? "_" : print(f.cond()))
                    + " " + (f.update() == null ? "_" : print(f.update()))
                    + " " + print(f.body()) + ")";
            case Return r -> r.value() == null ? "(return)" : "(return " + print(r.value()) + ")";
            case Break b -> b.label() == null ? "(break)" : "(break " + b.label() + ")";
            case Continue c -> c.label() == null ? "(continue)" : "(continue " + c.label() + ")";
            case Labeled l -> "(label " + l.label() + " " + print(l.body()) + ")";
            case Yield y -> "(yield " + print(y.value()) + ")";
            case Assert a -> "(assert " + print(a.cond()) + (a.message() == null ? "" : " " + print(a.message())) + ")";
            case DeleteStmt d -> "(delete " + print(d.target()) + ")";
        };
    }

    // ================================================================ expressions

    public static String print(Expr e) {
        return switch (e) {
            case IntLit i -> i.text();
            case FloatLit f -> f.text();
            case StringLit s -> quote(s.value());
            case ArrayLit a -> a.elements().stream().map(AstPrinter::print).collect(java.util.stream.Collectors.joining(" ", "[", "]"));
            case BoolLit b -> String.valueOf(b.value());
            case NullLit n -> "null";
            case Name n -> n.name();
            case SuperRef s -> "super";
            case This t -> "this";
            case Select s -> "(. " + print(s.target()) + " " + s.name() + ")";
            case Call c -> "(call " + print(c.callee())
                    + (c.typeArgs().isEmpty() ? "" : " <" + c.typeArgs().stream().map(AstPrinter::print)
                            .collect(Collectors.joining(", ")) + ">")
                    + c.args().stream().map(a -> " " + print(a)).collect(Collectors.joining()) + ")";
            case Index i -> "(index " + print(i.target()) + " " + print(i.index()) + ")";
            case Slice s -> "(slice " + print(s.target()) + " " + print(s.from()) + " " + print(s.to()) + ")";
            case Unary u -> "(" + u.op().symbol + " " + print(u.operand()) + ")";
            case IncDec i -> "(" + (i.prefix() ? i.symbol() + "x " : "x" + i.symbol() + " ") + print(i.target()) + ")";
            case Binary b -> "(" + b.op().symbol + " " + print(b.left()) + " " + print(b.right()) + ")";
            case Assign a -> "(" + (a.op() == null ? "" : a.op() == BinaryOp.SHIFT ? "<<" : a.op().symbol)
                    + "= " + print(a.target()) + " " + print(a.value()) + ")";
            case Cast c -> "(cast " + print(c.type()) + " " + print(c.operand()) + ")";
            case New n -> "(new " + print(n.type())
                    + n.args().stream().map(a -> " " + print(a)).collect(Collectors.joining()) + ")";
            case Lambda l -> "(lambda (" + l.params().stream().map(p -> (p.type() == null ? "" : print(p.type()) + " ") + p.name())
                    .collect(Collectors.joining(" ")) + ") " + (l.expr() != null ? print(l.expr()) : print(l.block())) + ")";
            case Conditional c -> "(? " + print(c.cond()) + " " + print(c.then()) + " " + print(c.otherwise()) + ")";
            case SwitchExpr sw -> "(switch-expr " + print(sw.subject()) + sw.cases().stream().map(c -> " (" + (c.isDefault()
                    ? "default" : "case " + c.labels().stream().map(AstPrinter::print).collect(Collectors.joining(" ")))
                    + " ->" + c.body().stream().map(x -> " " + print(x)).collect(Collectors.joining()) + ")")
                    .collect(Collectors.joining()) + ")";
            case InstanceOf i -> "(instanceof " + print(i.operand()) + " " + print(i.type())
                    + (i.binding() == null ? "" : " " + i.binding()) + ")";
            case NewArray n -> "(new<" + print(n.elementType()) + "> " + print(n.count()) + ")";
            case SizeOf s -> "(sizeof " + print(s.type()) + ")";
            case AlignOf a -> "(alignof " + print(a.type()) + ")";
        };
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String join(List<String> parts) {
        return String.join(", ", parts);
    }
}
