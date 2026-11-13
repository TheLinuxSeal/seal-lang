package io.github.thelinuxseal.seal.lsp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.ast.AstPrinter;
import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Checker.CallInfo;
import io.github.thelinuxseal.seal.check.ClassInfo;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Type;
import io.github.thelinuxseal.seal.lexer.Lexer;
import io.github.thelinuxseal.seal.lexer.Token;
import io.github.thelinuxseal.seal.lexer.TokenKind;
import io.github.thelinuxseal.seal.parser.Parser;

/**
 * One check of a program, and what an editor asks of it (docs/LSP.md): what the name at a place
 * is (hover), where it is declared (go to definition), and the types and members a file declares
 * (its outline). Places are a file's path and a 1-based line and column, as the compiler's.
 */
final class Analysis {

    private final Checker checker;
    /** Every file checked, the program's and the standard library's, by path. */
    private final Map<String, String> texts;
    private final Map<String, Tokens> tokens = new HashMap<>();
    private final Map<String, FileIndex> files = new HashMap<>();
    private final Map<ClassDecl, ClassInfo> classOf = new IdentityHashMap<>();

    // What checking every body found, together: each node is in one body (or one for each of its class's shapes).
    private final Map<Expr, Type> types = new IdentityHashMap<>();
    private final Map<Name, String> nameKinds = new IdentityHashMap<>();
    private final Map<Name, MemberInfo> fieldOf = new IdentityHashMap<>();
    private final Map<Select, MemberInfo> selected = new IdentityHashMap<>();
    private final Map<Expr, CallInfo> calls = new IdentityHashMap<>();
    private final Map<VarDecl, Type> locals = new IdentityHashMap<>();
    private final Map<MethodRef, Lambda> methodRefs = new IdentityHashMap<>();

    Analysis(Checker checker, Map<String, String> texts) {
        this.checker = checker;
        this.texts = texts;
        for (ClassInfo c : checker.allClasses()) {
            classOf.putIfAbsent(c.decl, c);
        }
        for (Checker.Checked k : checker.checkedBodies()) {
            Checker.TypedBody b = k.body();
            b.types.forEach(types::putIfAbsent);
            b.nameKinds.forEach(nameKinds::putIfAbsent);
            b.fieldOf.forEach(fieldOf::putIfAbsent);
            b.selected.forEach(selected::putIfAbsent);
            b.calls.forEach(calls::putIfAbsent);
            b.locals.forEach(locals::putIfAbsent);
            b.methodRefs.forEach(methodRefs::putIfAbsent);
        }
    }

    // ================================================================ what a place names

    /** What a name stands for: a type, a member, or a local variable or parameter. */
    sealed interface Target permits ClassTarget, MemberTarget, LocalTarget, PackageTarget {
    }

    /** A package's name, in an import: there is nothing to go to, but hovering says what it is. */
    record PackageTarget(String name) implements Target {
    }

    record ClassTarget(ClassInfo cls) implements Target {
    }

    record MemberTarget(MemberInfo member) implements Target {
    }

    /** @param type its type, as checking found it; null if unknown */
    record LocalTarget(String name, String file, Pos namePos, TypeRef written, Type type) implements Target {
    }

    /** A place in a file: the start and the end (just past it), lines and columns from 1. */
    record Span(String file, int line, int column, int endLine, int endColumn) {
    }

    /** The token at a place, where a diagnostic is: the whole of it, or the place alone if no token starts there. */
    Span spanAt(String file, int line, int column) {
        Tokens ts = tokens(file);
        Token t = ts == null ? null : ts.tokenAt(new Pos(line, column));
        return t != null ? ts.span(file, t) : new Span(file, line, column, line, column + 1);
    }

    /** What the name at a place is; null if it is none the program's checking knows. */
    Target targetAt(String file, int line, int column) {
        Tokens ts = tokens(file);
        FileIndex index = index(file);
        if (ts == null || index == null) {
            return null;
        }
        Token t = ts.nameAt(line, column);
        if (t == null) {
            return null;
        }
        Pos p = new Pos(t.line(), t.column());
        for (Object node : index.at.getOrDefault(p, List.of())) {
            Target found = target(node, index);
            if (found != null) {
                return found;
            }
        }
        // A type's name, in a type or `new`: the part of Map.Entry up to the name (Map, or Map.Entry).
        for (TypeAt ta : index.typeRefs) {
            TypeRef r = ta.ref();
            if (r.pos().line() == t.line() && r.pos().column() <= t.column()
                    && t.column() < r.pos().column() + r.name().length()) {
                String upTo = r.name().substring(0, Math.min(r.name().length(), t.column() - r.pos().column() + t.text().length()));
                ClassInfo c = lookupClass(upTo, ta.context());
                if (c != null) {
                    if (ta.made() != null && upTo.equals(r.name()) && calls.get(ta.made()) instanceof CallInfo ci
                            && ci.member() != null) {
                        return new MemberTarget(ci.member()); // new Box(...): the constructor
                    }
                    return new ClassTarget(c);
                }
            }
        }
        // A name in an import: import com.example.Vec2; import static Math.max; and an alias, import seal.util.List as Items;
        if (ts.inImport(t)) {
            Token named = ts.aliased(t);
            String dotted = ts.dottedTo(named);
            ClassInfo c = qualified(dotted);
            if (c != null) {
                return new ClassTarget(c);
            }
            int dot = dotted.lastIndexOf('.');
            ClassInfo owner = dot < 0 ? null : qualified(dotted.substring(0, dot));
            if (owner != null) {
                List<MemberInfo> ms = owner.members.get(named.text());
                if (ms != null && !ms.isEmpty()) {
                    return new MemberTarget(ms.get(0));
                }
            }
            return named == t ? new PackageTarget(dotted) : null;
        }
        return null;
    }

    private Target target(Object node, FileIndex index) {
        switch (node) {
            case Name n -> {
                Call call = index.callOf.get(n);
                if (call != null && calls.get(call) instanceof CallInfo ci && ci.member() != null) {
                    return new MemberTarget(ci.member());
                }
                String kind = nameKinds.get(n);
                LocalTarget local = index.localOf.get(n);
                if (local != null && (kind == null || kind.equals("local") || kind.equals("value"))) {
                    Type t = types.get(n);
                    return new LocalTarget(local.name(), local.file(), local.namePos(), local.written(),
                            t != null ? t : typeOf(local, index));
                }
                if (fieldOf.get(n) instanceof MemberInfo m) {
                    return new MemberTarget(m);
                }
                if (types.get(n) instanceof Type.ClassRefT cr) {
                    return new ClassTarget(cr.cls());
                }
                return null;
            }
            case Select s -> {
                Call call = index.callOf.get(s);
                if (call != null && calls.get(call) instanceof CallInfo ci && ci.member() != null) {
                    return new MemberTarget(ci.member());
                }
                if (selected.get(s) instanceof MemberInfo m) {
                    return new MemberTarget(m);
                }
                if (types.get(s) instanceof Type.ClassRefT cr) {
                    return new ClassTarget(cr.cls());
                }
                return null;
            }
            case SuperIn si -> {
                Call call = index.callOf.get(si.ref());
                if (call != null && calls.get(call) instanceof CallInfo ci && ci.member() != null) {
                    return new MemberTarget(ci.member()); // super(...): the parent's constructor
                }
                ClassTarget typed = classOf(types.get(si.ref()));
                if (typed != null || si.in() == null) {
                    return typed;
                }
                // super.m(): the parent its class extends
                for (ClassHeader h : si.in().decl.headers()) {
                    for (ExtendsClause e : h.extendsClauses()) {
                        ClassInfo parent = lookupClass(e.parent().name(), si.in());
                        if (parent != null) {
                            return new ClassTarget(parent);
                        }
                    }
                }
                return null;
            }
            case This th -> {
                Call call = index.callOf.get(th);
                if (call != null && calls.get(call) instanceof CallInfo ci && ci.member() != null) {
                    return new MemberTarget(ci.member()); // this(...): another constructor
                }
                return classOf(types.get(th));
            }
            case CaseLabel cl -> {
                // case NORTH: one of the enum's constants, which the switch is on
                if (classOf(types.get(cl.subject())) instanceof ClassTarget ct) {
                    List<MemberInfo> ms = ct.cls().members.get(cl.name().name());
                    if (ms != null && !ms.isEmpty()) {
                        return new MemberTarget(ms.get(0));
                    }
                }
                return target(cl.name(), index);
            }
            case MethodRef r -> {
                // Sample::twice is the lambda that calls it, (n) -> Sample.twice(n): the method it calls
                Lambda lam = methodRefs.get(r);
                CallInfo ci = lam == null ? null : calledIn(lam.expr(), lam.block());
                return ci == null || ci.member() == null ? null : new MemberTarget(ci.member());
            }
            case Decl d when d.target() instanceof LocalTarget l && l.type() == null -> {
                Type t = typeOf(l, index);
                return t == null ? l : new LocalTarget(l.name(), l.file(), l.namePos(), l.written(), t);
            }
            case Decl d -> {
                return d.target();
            }
            default -> {
                return null;
            }
        }
    }

    /** The class of a value of a type, a handle's or a struct's; null for any other type. */
    private static ClassTarget classOf(Type t) {
        return switch (t) {
            case Type.HandleT h -> classOf(h.target());
            case Type.ClassT ct -> new ClassTarget(ct.cls());
            case Type.ClassRefT cr -> new ClassTarget(cr.cls());
            case null, default -> null;
        };
    }

    /** The first call that resolved, in an expression or a block: what a method reference's lambda calls. */
    private CallInfo calledIn(Expr e, Block block) {
        if (e != null) {
            if ((e instanceof Call || e instanceof New) && calls.get(e) instanceof CallInfo ci) {
                return ci;
            }
            for (Expr child : io.github.thelinuxseal.seal.ast.Ast.children(e)) {
                CallInfo ci = calledIn(child, null);
                if (ci != null) {
                    return ci;
                }
            }
        }
        if (block != null) {
            for (Stmt st : block.stmts()) {
                Expr inner = switch (st) {
                    case ExprStmt es -> es.expr();
                    case Return r -> r.value();
                    default -> null;
                };
                CallInfo ci = calledIn(inner, null);
                if (ci != null) {
                    return ci;
                }
            }
        }
        return null;
    }

    /** A local's type: as declared, else as where it is used (one declared with var, or a lambda's parameter). */
    private Type typeOf(LocalTarget l, FileIndex index) {
        if (l.type() != null) {
            return l.type();
        }
        for (Map.Entry<Name, LocalTarget> e : index.localOf.entrySet()) {
            if (e.getValue() == l && types.get(e.getKey()) instanceof Type t && !t.isError()) {
                return t;
            }
        }
        return null;
    }

    private ClassInfo lookupClass(String name, ClassInfo from) {
        try {
            ClassInfo c = from == null ? qualified(name) : checker.lookupClass(name, from);
            return c != null ? c : qualified(name);
        } catch (RuntimeException e) {
            return null; // a name the checker reports, or can't look up from here
        }
    }

    /** A Seal type by its full name, com.example.Vec2 or Math (seal.lang's). */
    private ClassInfo qualified(String name) {
        for (ClassInfo c : checker.allClasses()) {
            if (c.qualifiedName().equals(name)) {
                return c;
            }
        }
        return checker.classNamed(name);
    }

    // ================================================================ hover

    /** What hovering over a place shows, in Markdown; null for nothing. */
    String hover(String file, int line, int column) {
        Target t = targetAt(file, line, column);
        if (t == null) {
            return null;
        }
        return switch (t) {
            case ClassTarget c when c.cls().java != null -> {
                String java = c.cls().java.name().replace('$', '.');
                yield code((c.cls().isInterface ? "interface " : "class ") + java)
                        + (c.cls().qualifiedName().equals(java) ? "\n\nJava's" : "\n\nJava's, which Seal calls " + c.cls().name);
            }
            case ClassTarget c -> code(header(c.cls())) + doc(c.cls().file, c.cls().decl.pos())
                    + (c.cls().pkg.isEmpty() ? "" : "\n\npackage " + c.cls().pkg);
            case MemberTarget m -> {
                ClassInfo owner = checker.declaringClassOf(m.member());
                if (owner != null && owner.java != null) {
                    yield code(unqualified(signature(m.member(), owner))) + "\n\nin Java's " + owner.java.name().replace('$', '.');
                }
                String in = owner == null ? "" : "\n\nin " + owner.qualifiedName();
                yield code(signature(m.member(), owner)) + doc(owner == null ? null : owner.file, m.member().member.pos()) + in;
            }
            case LocalTarget l -> code((l.written() != null && !l.written().name().equals("var") ? AstPrinter.print(l.written())
                    : l.type() != null && !l.type().isError() ? Checker.written(l.type()) : "var") + " " + l.name());
            case PackageTarget p -> code("package " + p.name());
        };
    }

    /** A Java member's declaration with its types' plain names: Set<Map.Entry<K, V>>, not java.util.Set<java.util.Map$Entry<K, V>>. */
    private static String unqualified(String s) {
        return s.replaceAll("\\b(?:[a-z][a-z0-9_]*\\.)+(?=[A-Z])", "").replace('$', '.');
    }

    private static String code(String s) {
        return "```seal\n" + s + "\n```";
    }

    /** A type's first header as written: `public class Dog extends(Animal)`. */
    private static String header(ClassInfo c) {
        ClassHeader h = c.decl.headers().get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(h.isAbstract() && h.kind() != Kind.INTERFACE ? "abstract " : "").append(h.kind().keyword).append(' ')
                .append(c.name);
        if (!h.typeParams().isEmpty()) {
            sb.append('<').append(String.join(", ", h.typeParams())).append('>');
        }
        if (h.valueParams() != null) {
            sb.append(AstPrinter.print(h.valueParams()));
        }
        for (ExtendsClause e : h.extendsClauses()) {
            sb.append(" extends(").append(AstPrinter.print(e.parent())).append(')');
        }
        if (!h.interfaces().isEmpty()) {
            sb.append(h.kind() == Kind.INTERFACE ? " extends(" : " implements(")
                    .append(h.interfaces().stream().map(AstPrinter::print).collect(Collectors.joining(", "))).append(')');
        }
        return sb.toString();
    }

    /** A member's declaration without its body: `public static int max(int a, int b)`. */
    private static String signature(MemberInfo m, ClassInfo owner) {
        Member member = m.member;
        StringBuilder sb = new StringBuilder();
        Modifiers mods = member.modifiers();
        sb.append(mods.access() == Access.PRIVATE ? "private " : "public ");
        sb.append(mods.isStatic() ? "static " : "").append(mods.isAbstract() ? "abstract " : "")
                .append(mods.isDefault() ? "default " : "").append(mods.isPure() ? "pure " : "")
                .append(mods.isConst() ? "const " : "");
        switch (member) {
            case Field f -> {
                sb.append(AstPrinter.print(f.type())).append(' ').append(f.name());
                if (f.modifiers().isConst() && f.init() != null) {
                    sb.append(" = ").append(AstPrinter.print(f.init()));
                }
            }
            case Method md -> {
                if (!md.typeParams().isEmpty()) {
                    sb.append('<').append(String.join(", ", md.typeParams())).append("> ");
                }
                sb.append(AstPrinter.print(md.returnType())).append(' ').append(md.name())
                        .append(md.guard() == null ? "" : AstPrinter.print(md.guard())).append(params(md.params()));
            }
            case Constructor c -> sb.append("new ").append(owner == null ? "" : owner.simpleName)
                    .append(c.guard() == null ? "" : AstPrinter.print(c.guard())).append(params(c.params()));
            case Destructor d -> sb.append("delete()");
        }
        return sb.toString();
    }

    private static String params(List<Param> ps) {
        return ps.stream().filter(p -> !p.name().startsWith("__"))
                .map(p -> (p.type() == null ? "" : AstPrinter.print(p.type()) + " ") + p.name())
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /** The doc comment just before a declaration, as Markdown after a blank line; "" if it has none. */
    private String doc(String file, Pos pos) {
        String text = file == null ? null : texts.get(file);
        if (text == null || pos == null) {
            return "";
        }
        int at = offset(text, pos.line(), pos.column());
        int end = at;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end < 2 || !text.startsWith("*/", end - 2)) {
            return "";
        }
        int start = text.lastIndexOf("/**", end - 2);
        if (start < 0 || text.substring(start + 2, end - 2).contains("*/")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : text.substring(start + 3, end - 2).split("\n", -1)) {
            String s = l.strip();
            s = s.startsWith("*") ? s.substring(1).strip() : s;
            sb.append(s).append('\n');
        }
        String body = sb.toString().strip().replaceAll("\\{@(?:code|link|linkplain) ([^}]*)}", "`$1`")
                .replaceAll("(?m)^<pre>$|^</pre>$", "```");
        return body.isEmpty() ? "" : "\n\n" + body;
    }

    /** The index in a text of a line and column from 1; a column past the line's end is its end. */
    static int offset(String text, int line, int column) {
        int at = 0;
        for (int l = 1; l < line && at >= 0; l++) {
            at = text.indexOf('\n', at);
            at = at < 0 ? -1 : at + 1;
        }
        if (at < 0) {
            return text.length();
        }
        int end = text.indexOf('\n', at);
        return Math.min(end < 0 ? text.length() : end, at + Math.max(0, column - 1));
    }

    // ================================================================ go to definition

    /** Where the name at a place is declared; null if nowhere the editor can open (Java's, or the built-in types). */
    Span definition(String file, int line, int column) {
        Target t = targetAt(file, line, column);
        return t == null ? null : declaration(t);
    }

    private Span declaration(Target t) {
        return switch (t) {
            case ClassTarget c -> classSpan(c.cls());
            case MemberTarget m -> {
                ClassInfo owner = checker.declaringClassOf(m.member());
                if (owner == null || owner.java != null || !texts.containsKey(owner.file)) {
                    yield null;
                }
                Tokens ts = tokens(owner.file);
                Token name = ts.memberName(m.member().member, owner.decl);
                yield name == null ? classSpan(owner) : ts.span(owner.file, name);
            }
            case PackageTarget p -> null;
            case LocalTarget l -> {
                Tokens ts = tokens(l.file());
                Token name = ts == null ? null : ts.tokenAt(l.namePos());
                yield name == null ? null : ts.span(l.file(), name);
            }
        };
    }

    private Span classSpan(ClassInfo c) {
        if (c.java != null || !texts.containsKey(c.file)) {
            return null;
        }
        Tokens ts = tokens(c.file);
        Token name = ts.nameAfter(c.decl.headers().get(0).pos(), c.simpleName);
        return name == null ? null : ts.span(c.file, name);
    }

    // ================================================================ outline

    /** One entry of a file's outline: a type or a member, and what it holds. */
    record Symbol(String name, String detail, int kind, Span range, Span selection, List<Symbol> children) {
    }

    // LSP's SymbolKind
    static final int CLASS = 5, METHOD = 6, FIELD = 8, CONSTRUCTOR = 9, ENUM = 10, INTERFACE = 11, CONSTANT = 14,
            ENUM_MEMBER = 22, STRUCT = 23;

    /** The types and members a file's text declares, from parsing it alone. */
    static List<Symbol> outline(String file, String text) {
        Parser.Result r = Parser.parse(text);
        Tokens ts = new Tokens(text);
        List<Symbol> out = new ArrayList<>();
        if (!r.ast().headers().isEmpty()) {
            Symbol s = typeSymbol(file, r.ast(), ts);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static Symbol typeSymbol(String file, ClassDecl decl, Tokens ts) {
        ClassHeader h = decl.headers().get(0);
        Token name = ts.nameAfter(h.pos(), h.name());
        if (name == null) {
            return null;
        }
        List<Symbol> children = new ArrayList<>();
        for (Member m : decl.members()) {
            String memberName = switch (m) {
                case Field f -> f.name();
                case Method md -> md.name();
                case Constructor c -> "new";
                case Destructor d -> "delete";
            };
            Token at = memberName.startsWith("__") ? null : ts.memberName(m, decl);
            if (at == null || ts.tokenAt(m.pos()) == null && !(m instanceof Field)) {
                continue; // written by the parser, as an enum's values() is
            }
            Span range = ts.tokenAt(m.pos()) == null ? ts.span(file, at) : ts.memberSpan(file, m, at);
            int kind = switch (m) {
                case Field f when h.kind() == Kind.ENUM && f.modifiers().isStatic() && f.init() instanceof New -> ENUM_MEMBER;
                case Field f when f.modifiers().isConst() -> CONSTANT;
                case Field f -> FIELD;
                case Method md -> METHOD;
                default -> CONSTRUCTOR;
            };
            String detail = switch (m) {
                case Field f when kind == ENUM_MEMBER -> null;
                case Field f -> AstPrinter.print(f.type());
                case Method md -> params(md.params()) + " " + AstPrinter.print(md.returnType());
                case Constructor c -> params(c.params().stream().filter(p -> !p.name().startsWith("__")).toList());
                case Destructor d -> null;
            };
            children.add(new Symbol(memberName, detail, kind, range, ts.span(file, at), List.of()));
        }
        for (NestedType n : decl.nested()) {
            Symbol s = typeSymbol(file, n.decl(), ts);
            if (s != null) {
                children.add(s);
            }
        }
        children.sort((a, b) -> a.selection().line() != b.selection().line() ? a.selection().line() - b.selection().line()
                : a.selection().column() - b.selection().column());
        int kind = switch (h.kind()) {
            case INTERFACE -> INTERFACE;
            case ENUM -> ENUM;
            case STRUCT, UNIQUE, RECORD -> STRUCT;
            case CLASS -> CLASS;
        };
        Token close = ts.closingBrace(name);
        Span range = new Span(file, h.pos().line(), h.pos().column(), close == null ? name.line() : close.line(),
                close == null ? name.column() + name.text().length() : close.column() + 1);
        return new Symbol(h.name(), h.kind().keyword, kind, range, ts.span(file, name), children);
    }

    // ================================================================ the index of a file

    /** A declaration's name, at the place it is written. */
    private record Decl(Target target) {
    }

    /** `super`, and the class it is in. */
    private record SuperIn(SuperRef ref, ClassInfo in) {
    }

    /** A case's label that is a name, `case NORTH`, and the switch's subject: an enum's constant. */
    private record CaseLabel(Name name, Expr subject) {
    }

    /** A type written in code, and the class it is written in (which its name is looked up from). */
    private record TypeAt(TypeRef ref, ClassInfo context, New made) {
    }

    /** The nodes of one file, by where the names they hold are. */
    private static final class FileIndex {
        final Map<Pos, List<Object>> at = new HashMap<>();
        final List<TypeAt> typeRefs = new ArrayList<>();
        /** Each call's callee (a Name or a Select): the call. */
        final Map<Expr, Call> callOf = new IdentityHashMap<>();
        /** The local variable or parameter each Name that is one names. */
        final Map<Name, LocalTarget> localOf = new IdentityHashMap<>();

        void put(Pos p, Object node) {
            at.computeIfAbsent(p, k -> new ArrayList<>()).add(node);
        }
    }

    private Tokens tokens(String file) {
        String text = texts.get(file);
        return text == null ? null : tokens.computeIfAbsent(file, f -> new Tokens(text));
    }

    private FileIndex index(String file) {
        FileIndex found = files.get(file);
        if (found != null || !texts.containsKey(file)) {
            return found;
        }
        FileIndex index = new FileIndex();
        Walker w = new Walker(file, tokens(file), index);
        for (ClassInfo c : checker.allClasses()) {
            if (c.file.equals(file) && c.outer == null && c.java == null) {
                w.type(c.decl, c);
            }
        }
        files.put(file, index);
        return index;
    }

    /** Goes over a file's types, recording each name where it is, and which local each local's name is. */
    private final class Walker {
        final String file;
        final Tokens ts;
        final FileIndex index;
        final Deque<Map<String, LocalTarget>> scopes = new ArrayDeque<>();
        ClassInfo cls;

        Walker(String file, Tokens ts, FileIndex index) {
            this.file = file;
            this.ts = ts;
            this.index = index;
        }

        void type(ClassDecl decl, ClassInfo c) {
            ClassInfo saved = cls;
            cls = c;
            scopes.push(new HashMap<>()); // its header's binders
            for (ClassHeader h : decl.headers()) {
                Token name = ts.nameAfter(h.pos(), h.name());
                if (name != null && c != null) {
                    index.put(new Pos(name.line(), name.column()), new Decl(new ClassTarget(c)));
                }
                if (h.valueParams() != null) {
                    values(h.valueParams());
                }
                for (ExtendsClause e : h.extendsClauses()) {
                    type(e.parent());
                }
                h.interfaces().forEach(this::type);
            }
            for (Member m : decl.members()) {
                member(m, c);
            }
            for (NestedType n : decl.nested()) {
                type(n.decl(), classOf.get(n.decl()));
            }
            scopes.pop();
            cls = saved;
        }

        void member(Member m, ClassInfo c) {
            MemberInfo info = c == null ? null : c.all.stream().filter(mi -> mi.member == m).findFirst().orElse(null);
            Token name = c == null ? null : ts.memberName(m, c.decl);
            if (name != null && info != null) {
                index.put(new Pos(name.line(), name.column()), new Decl(new MemberTarget(info)));
            }
            if (ts.tokenAt(m.pos()) == null) {
                return; // written by the parser: a record's component, or an enum's values(), with nothing in the file
            }
            m.modifiers().friends().forEach(this::type);
            scopes.push(new HashMap<>());
            switch (m) {
                case Field f -> {
                    type(f.type());
                    if (f.init() != null) {
                        expr(f.init());
                    }
                }
                case Method md -> {
                    type(md.returnType());
                    if (md.guard() != null) {
                        values(md.guard());
                    }
                    md.params().forEach(this::param);
                    if (md.body() != null) {
                        stmt(md.body());
                    }
                }
                case Constructor k -> {
                    k.params().forEach(this::param);
                    stmt(k.body());
                }
                case Destructor d -> stmt(d.body());
            }
            scopes.pop();
        }

        void param(Param p) {
            if (p.type() != null) {
                type(p.type());
            }
            declare(p.name(), p.pos(), p.type(), null);
        }

        /** A local variable or parameter, declared from where its name is written after {@code from}. */
        void declare(String name, Pos from, TypeRef written, Type type) {
            Token t = ts.nameAfter(from, name);
            Pos at = t == null ? from : new Pos(t.line(), t.column());
            LocalTarget local = new LocalTarget(name, file, at, written, type);
            scopes.peek().put(name, local);
            if (t != null) {
                index.put(at, new Decl(local));
            }
        }

        LocalTarget lookup(String name) {
            for (Map<String, LocalTarget> scope : scopes) {
                LocalTarget l = scope.get(name);
                if (l != null) {
                    return l;
                }
            }
            return null;
        }

        void type(TypeRef t) {
            type(t, null);
        }

        void type(TypeRef t, New made) {
            if (t == null) {
                return;
            }
            if (!t.name().equals("var")) {
                index.typeRefs.add(new TypeAt(t, cls, made));
            }
            t.typeArgs().forEach(this::type);
            if (t.valueArgs() != null) {
                values(t.valueArgs());
            }
            for (Expr dim : t.arrayDims()) {
                if (dim != null) {
                    expr(dim);
                }
            }
        }

        void values(ValueList v) {
            for (ValueItem item : v.items()) {
                switch (item) {
                    case Binder b -> {
                        type(b.type());
                        declare(b.name(), b.pos(), b.type(), null); // a value the members it is in see
                    }
                    case ValueExpr e -> expr(e.expr());
                }
            }
        }

        void block(List<Stmt> stmts) {
            scopes.push(new HashMap<>());
            stmts.forEach(this::stmt);
            scopes.pop();
        }

        void stmt(Stmt s) {
            switch (s) {
                case Block b -> block(b.stmts());
                case VarDecl v -> local(v);
                case ExprStmt e -> expr(e.expr());
                case If i -> {
                    expr(i.cond());
                    scoped(i.then());
                    if (i.otherwise() != null) {
                        scoped(i.otherwise());
                    }
                }
                case While w -> {
                    expr(w.cond());
                    scoped(w.body());
                }
                case For f -> {
                    scopes.push(new HashMap<>());
                    if (f.init() != null) {
                        stmt(f.init());
                    }
                    if (f.cond() != null) {
                        expr(f.cond());
                    }
                    if (f.update() != null) {
                        expr(f.update());
                    }
                    scoped(f.body());
                    scopes.pop();
                }
                case ForEach f -> {
                    expr(f.iterable());
                    scopes.push(new HashMap<>());
                    type(f.type());
                    declare(f.name(), f.pos(), f.type(), null);
                    scoped(f.body());
                    scopes.pop();
                }
                case Switch sw -> {
                    expr(sw.subject());
                    sw.cases().forEach(c -> switchCase(c, sw.subject()));
                }
                case Return r -> {
                    if (r.value() != null) {
                        expr(r.value());
                    }
                }
                case Labeled l -> stmt(l.body());
                case DeleteStmt d -> expr(d.target());
                case Throw t -> expr(t.value());
                case Try t -> {
                    scopes.push(new HashMap<>());
                    t.resources().forEach(this::local);
                    stmt(t.body());
                    scopes.pop();
                    for (Catch c : t.catches()) {
                        scopes.push(new HashMap<>());
                        type(c.type());
                        declare(c.name(), c.pos(), c.type(), null);
                        stmt(c.body());
                        scopes.pop();
                    }
                    if (t.finallyBlock() != null) {
                        stmt(t.finallyBlock());
                    }
                }
                case Yield y -> expr(y.value());
                case Assert a -> {
                    expr(a.cond());
                    if (a.message() != null) {
                        expr(a.message());
                    }
                }
                case Break b -> {
                }
                case Continue c -> {
                }
            }
        }

        /** A statement in a scope of its own: the body of an if or a loop. */
        void scoped(Stmt s) {
            scopes.push(new HashMap<>());
            stmt(s);
            scopes.pop();
        }

        void local(VarDecl v) {
            type(v.type());
            if (v.init() != null) {
                expr(v.init());
            }
            declare(v.name(), v.pos(), v.type(), locals.get(v));
        }

        void switchCase(Case c, Expr subject) {
            for (Expr label : c.labels()) {
                if (label instanceof Name n) {
                    index.put(n.pos(), new CaseLabel(n, subject));
                } else {
                    expr(label);
                }
            }
            block(c.body());
        }

        void expr(Expr e) {
            switch (e) {
                case Name n -> {
                    index.put(n.pos(), n);
                    LocalTarget l = lookup(n.name());
                    if (l != null) {
                        index.localOf.put(n, l);
                    }
                }
                case Select s -> {
                    index.put(s.namePos(), s);
                    expr(s.target());
                }
                case MethodRef r -> {
                    index.put(r.namePos(), r);
                    expr(r.target());
                }
                case SuperRef sr -> index.put(sr.pos(), new SuperIn(sr, cls));
                case This th -> index.put(th.pos(), th);
                case Call c -> {
                    index.callOf.put(c.callee(), c);
                    c.typeArgs().forEach(this::type);
                    expr(c.callee());
                    c.args().forEach(this::expr);
                }
                case New n -> {
                    type(n.type(), n);
                    n.args().forEach(this::expr);
                }
                case NewArray n -> {
                    type(n.elementType());
                    expr(n.count());
                }
                case Cast c -> {
                    type(c.type());
                    expr(c.operand());
                }
                case InstanceOf i -> {
                    expr(i.operand());
                    type(i.type());
                    if (i.binding() != null) {
                        // Seen where the test passed, which may be past its statement: if (!(x instanceof T t)) return;
                        declare(i.binding(), i.type().pos(), i.type(), null);
                    }
                }
                case SizeOf s -> type(s.type());
                case AlignOf a -> type(a.type());
                case Lambda l -> {
                    scopes.push(new HashMap<>());
                    l.params().forEach(this::param);
                    if (l.expr() != null) {
                        expr(l.expr());
                    } else {
                        stmt(l.block());
                    }
                    scopes.pop();
                }
                case SwitchExpr sw -> {
                    expr(sw.subject());
                    sw.cases().forEach(c -> switchCase(c, sw.subject()));
                }
                default -> {
                    for (Expr child : io.github.thelinuxseal.seal.ast.Ast.children(e)) {
                        expr(child);
                    }
                }
            }
        }
    }

    // ================================================================ tokens

    /** A file's tokens, by line, to find what is at a place and where a declaration's name is. */
    static final class Tokens {
        final List<Token> all;
        /** Each line's first token's index in {@link #all}, by line. */
        private final Map<Integer, Integer> lineStart = new HashMap<>();
        /** For each bracket, the index of the one matching it. */
        private final Map<Integer, Integer> matching = new HashMap<>();

        Tokens(String text) {
            all = Lexer.lex(text).tokens().stream().filter(t -> t.kind() != TokenKind.ERROR && t.kind() != TokenKind.EOF)
                    .toList();
            Deque<Integer> open = new ArrayDeque<>();
            for (int i = 0; i < all.size(); i++) {
                lineStart.putIfAbsent(all.get(i).line(), i);
                switch (all.get(i).kind()) {
                    case LBRACE, LPAREN, LBRACKET -> open.push(i);
                    case RBRACE, RPAREN, RBRACKET -> {
                        if (!open.isEmpty()) {
                            int o = open.pop();
                            matching.put(o, i);
                            matching.put(i, o);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        /** The index of the first token at or after a place. */
        int indexAt(Pos p) {
            for (int line = p.line(); line <= p.line() + 2000; line++) {
                Integer start = lineStart.get(line);
                if (start == null) {
                    if (!all.isEmpty() && line > all.get(all.size() - 1).line()) {
                        return all.size();
                    }
                    continue;
                }
                for (int i = start; i < all.size(); i++) {
                    Token t = all.get(i);
                    if (t.line() > p.line() || t.column() >= p.column()) {
                        return i;
                    }
                }
                return all.size();
            }
            return all.size();
        }

        Token tokenAt(Pos p) {
            int i = indexAt(p);
            return i < all.size() && all.get(i).line() == p.line() && all.get(i).column() == p.column() ? all.get(i) : null;
        }

        /** The name the place is in, or just after (where the cursor is at the end of a word). */
        Token nameAt(int line, int column) {
            Integer start = lineStart.get(line);
            if (start == null) {
                return null;
            }
            Token after = null;
            for (int i = start; i < all.size() && all.get(i).line() == line; i++) {
                Token t = all.get(i);
                if (t.kind() != TokenKind.IDENTIFIER && t.kind() != TokenKind.THIS && t.kind() != TokenKind.SUPER) {
                    continue;
                }
                if (t.column() <= column && column < t.column() + t.text().length()) {
                    return t;
                }
                if (column == t.column() + t.text().length()) {
                    after = t;
                }
            }
            return after;
        }

        /**
         * The name {@code name} where a declaration starting at {@code p} writes it: the first that
         * is, before its body or its end (`{`, `;` or `=`), within a few lines.
         */
        Token nameAfter(Pos p, String name) {
            for (int i = indexAt(p); i < all.size(); i++) {
                Token t = all.get(i);
                if (t.line() > p.line() + 3) {
                    return null;
                }
                if (t.kind() == TokenKind.IDENTIFIER && t.text().equals(name)) {
                    return t;
                }
                if (t.kind() == TokenKind.LBRACE || t.kind() == TokenKind.SEMICOLON || t.kind() == TokenKind.ASSIGN) {
                    return null;
                }
            }
            return null;
        }

        /** The first token of a kind at or after a place, before a body or its end: a constructor's `new`. */
        Token kindAfter(Pos p, TokenKind kind) {
            for (int i = indexAt(p); i < all.size(); i++) {
                Token t = all.get(i);
                if (t.kind() == kind) {
                    return t;
                }
                if (t.kind() == TokenKind.LBRACE || t.kind() == TokenKind.SEMICOLON || t.line() > p.line() + 3) {
                    return null;
                }
            }
            return null;
        }

        /**
         * Where a member's name is: a constructor's `new`; a record's component in the record's
         * header. Null for one the parser wrote that has none, an enum's values().
         */
        Token memberName(Member m, ClassDecl in) {
            if (tokenAt(m.pos()) == null) {
                return m instanceof Field f && !in.headers().isEmpty() ? nameOnLine(in.headers().get(0).pos().line(), f.name()) : null;
            }
            return switch (m) {
                case Constructor c -> kindAfter(c.pos(), TokenKind.NEW);
                case Destructor d -> kindAfter(d.pos(), TokenKind.DELETE);
                case Field f -> nameAfter(f.pos(), f.name());
                case Method md -> nameAfter(md.pos(), md.name());
            };
        }

        /** The name on a line, as a record's component is in its header. */
        Token nameOnLine(int line, String name) {
            Integer start = lineStart.get(line);
            for (int i = start == null ? all.size() : start; i < all.size() && all.get(i).line() == line; i++) {
                if (all.get(i).kind() == TokenKind.IDENTIFIER && all.get(i).text().equals(name)) {
                    return all.get(i);
                }
            }
            return null;
        }

        /** `a.b.C` up to and including the name, when the tokens before it are names and dots; null otherwise. */
        String dottedTo(Token name) {
            int i = all.indexOf(name);
            if (i < 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder(name.text());
            while (i >= 2 && all.get(i - 1).kind() == TokenKind.DOT && all.get(i - 2).kind() == TokenKind.IDENTIFIER) {
                sb.insert(0, all.get(i - 2).text() + ".");
                i -= 2;
            }
            return sb.toString();
        }

        /** For the alias of an import, `Items` in import seal.util.List as Items;, the name it is for; else the name itself. */
        Token aliased(Token name) {
            int i = all.indexOf(name);
            return i >= 2 && all.get(i - 1).text().equals("as") && all.get(i - 2).kind() == TokenKind.IDENTIFIER
                    ? all.get(i - 2) : name;
        }

        /** Whether a token is in an import, or the file's package. */
        boolean inImport(Token name) {
            for (int i = all.indexOf(name); i >= 0; i--) {
                TokenKind k = all.get(i).kind();
                if (k == TokenKind.IMPORT || k == TokenKind.PACKAGE) {
                    return true;
                }
                if (k != TokenKind.IDENTIFIER && k != TokenKind.DOT && k != TokenKind.STATIC) {
                    return false;
                }
            }
            return false;
        }

        /** The `}` closing the first `{` after a token: a type's body. */
        Token closingBrace(Token from) {
            for (int i = all.indexOf(from); i >= 0 && i < all.size(); i++) {
                if (all.get(i).kind() == TokenKind.LBRACE) {
                    Integer close = matching.get(i);
                    return close == null ? null : all.get(close);
                }
            }
            return null;
        }

        /** A member's whole declaration: from its start to its body's `}`, or its `;`. */
        Span memberSpan(String file, Member m, Token name) {
            int i = all.indexOf(name);
            int depth = 0;
            for (; i >= 0 && i < all.size(); i++) {
                Token t = all.get(i);
                if (t.kind() == TokenKind.LBRACE && depth == 0) {
                    Integer close = matching.get(i);
                    i = close == null ? i : close;
                    break;
                }
                if (t.kind() == TokenKind.SEMICOLON && depth == 0) {
                    break;
                }
                if (t.kind() == TokenKind.COMMA && depth == 0 || t.kind() == TokenKind.RBRACE && depth == 0) {
                    i--; // an enum's constant, ended by , or the enum's }
                    break;
                }
                if (t.kind() == TokenKind.LPAREN || t.kind() == TokenKind.LBRACKET) {
                    depth++;
                } else if (t.kind() == TokenKind.RPAREN || t.kind() == TokenKind.RBRACKET) {
                    if (--depth < 0) {
                        i--; // the end of the list it is in
                        break;
                    }
                }
            }
            Token end = all.get(Math.max(all.indexOf(name), Math.min(i, all.size() - 1)));
            Token first = tokenAt(m.pos());
            Token start = first != null && (first.line() < name.line() || first.line() == name.line() && first.column() <= name.column())
                    ? first : name;
            int[] e = end(end);
            return new Span(file, start.line(), start.column(), e[0], e[1]);
        }

        Span span(String file, Token t) {
            int[] e = end(t);
            return new Span(file, t.line(), t.column(), e[0], e[1]);
        }

        /** The line and column just past a token, which may be a text block of several lines. */
        static int[] end(Token t) {
            String text = t.text();
            int nl = text.lastIndexOf('\n');
            if (nl < 0) {
                return new int[] {t.line(), t.column() + text.length()};
            }
            int lines = (int) text.chars().filter(c -> c == '\n').count();
            return new int[] {t.line() + lines, text.length() - nl};
        }
    }
}
