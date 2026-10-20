package io.github.thelinuxseal.seal.parser;

import static io.github.thelinuxseal.seal.lexer.TokenKind.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.ast.AstPrinter;
import io.github.thelinuxseal.seal.lexer.LexError;
import io.github.thelinuxseal.seal.lexer.Lexer;
import io.github.thelinuxseal.seal.lexer.Token;
import io.github.thelinuxseal.seal.lexer.TokenKind;

/**
 * Recursive-descent parser producing a {@link ClassDecl} for one source file.
 *
 * <p>Two ambiguities are resolved here rather than in the lexer:
 * <ul>
 *   <li>`>>` closes a value list, or two type-argument lists. When a single
 *       `>` is expected and the current token is `>>`, the token is split and
 *       only its first half is consumed.</li>
 *   <li>`Foo<T> x` vs `a < b > c`, and `(T) x` vs `(a) - b`. These are decided
 *       by speculative parsing: try the type interpretation first and
 *       backtrack if it does not fit.</li>
 * </ul>
 *
 * <p>Errors do not stop parsing. After an error the parser skips to the next
 * statement or member and continues.
 */
public final class Parser {

    /**
     * A file: its type, and the package it is in (null for none) and the types it imports,
     * which come before the type (§2.4).
     */
    public record Result(ClassDecl ast, List<ParseError> errors, String pkg, Pos pkgPos, List<Import> imports) {
        public Result(ClassDecl ast, List<ParseError> errors) {
            this(ast, errors, null, null, List.of());
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** Lexes and parses a source file. Lexer errors are included in the result. */
    public static Result parse(String src) {
        Lexer.Result lexed = Lexer.lex(src);
        List<ParseError> errors = new ArrayList<>();
        for (LexError e : lexed.errors()) {
            errors.add(new ParseError(e.message(), e.line(), e.column()));
        }
        // ERROR tokens have already been reported by the lexer.
        List<Token> tokens = lexed.tokens().stream().filter(t -> t.kind() != ERROR).toList();
        Parser parser = new Parser(tokens);
        ClassDecl ast = parser.parseFile();
        errors.addAll(parser.errors);
        return new Result(ast, errors, parser.pkg, parser.pkgPos, List.copyOf(parser.imports));
    }

    /** Parses a single expression. Intended for tests and tooling. */
    public static Expr parseExpression(String src, List<ParseError> errorsOut) {
        Lexer.Result lexed = Lexer.lex(src);
        for (LexError e : lexed.errors()) {
            errorsOut.add(new ParseError(e.message(), e.line(), e.column()));
        }
        Parser parser = new Parser(lexed.tokens().stream().filter(t -> t.kind() != ERROR).toList());
        Expr expr = null;
        try {
            expr = parser.parseExpr();
            if (!parser.check(EOF)) {
                parser.error("unexpected '" + parser.peek().text() + "' after expression");
            }
        } catch (Bail ignored) {
            // already reported
        }
        errorsOut.addAll(parser.errors);
        return expr;
    }

    /** Parses a single statement. Intended for tests and tooling. */
    public static Stmt parseStatement(String src, List<ParseError> errorsOut) {
        Lexer.Result lexed = Lexer.lex(src);
        for (LexError e : lexed.errors()) {
            errorsOut.add(new ParseError(e.message(), e.line(), e.column()));
        }
        Parser parser = new Parser(lexed.tokens().stream().filter(t -> t.kind() != ERROR).toList());
        Stmt stmt = null;
        try {
            stmt = parser.parseStatement();
            if (!parser.check(EOF)) {
                parser.error("unexpected '" + parser.peek().text() + "' after statement");
            }
        } catch (Bail ignored) {
            // already reported
        }
        errorsOut.addAll(parser.errors);
        return stmt;
    }

    // ================================================================ state

    /** Thrown after an error has been reported, to unwind to a recovery point. */
    private static final class Bail extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bail() {
            super(null, null, false, false);
        }
    }

    private static final Set<TokenKind> PRIMITIVE_TYPES =
            EnumSet.of(INT, UINT, BIT, CHAR, FLOAT, DOUBLE, VOID, ADDR, HANDLE, FIXED, UFIXED);

    /** Tokens that may follow `(Name)` for it to be read as a cast rather than a parenthesized expression. */
    private static final Set<TokenKind> CAST_FOLLOWERS = EnumSet.of(
            IDENTIFIER, INT_LITERAL, FLOAT_LITERAL, STRING_LITERAL, CHAR_LITERAL, THIS, TRUE, FALSE, NULL,
            LPAREN, BANG, TILDE, NEW, SIZEOF, ALIGNOF, SUPER);

    private static final Map<TokenKind, BinaryOp> ASSIGN_OPS = Map.ofEntries(
            Map.entry(PLUS_ASSIGN, BinaryOp.ADD),
            Map.entry(MINUS_ASSIGN, BinaryOp.SUB),
            Map.entry(STAR_ASSIGN, BinaryOp.MUL),
            Map.entry(SLASH_ASSIGN, BinaryOp.DIV),
            Map.entry(PERCENT_ASSIGN, BinaryOp.MOD),
            Map.entry(STAR_STAR_ASSIGN, BinaryOp.POW),
            Map.entry(AMP_ASSIGN, BinaryOp.BIT_AND),
            Map.entry(PIPE_ASSIGN, BinaryOp.BIT_OR),
            Map.entry(CARET_ASSIGN, BinaryOp.BIT_XOR),
            Map.entry(SHIFT_ASSIGN, BinaryOp.SHIFT));

    private static final Map<TokenKind, BinaryOp> COMPARISON_OPS = Map.of(
            EQ, BinaryOp.EQ, NEQ, BinaryOp.NE, LT, BinaryOp.LT, GT, BinaryOp.GT, LE, BinaryOp.LE, GE, BinaryOp.GE);

    private final List<Token> tokens;
    private final List<ParseError> errors = new ArrayList<>();
    private int pos = 0;

    /** True when the current `>>` token has had its first `>` consumed. */
    private boolean splitGt = false;

    /** While positive, errors are not recorded: we are only testing whether something parses. */
    private int speculating = 0;

    /** In a case label, where `x ->` ends the label instead of starting a lambda. */
    private boolean inCaseLabel = false;

    /** Depth of `<<...>>` nesting, so `>>` inside a value list is not reported as a bad operator. */
    private int valueListDepth = 0;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    // ================================================================ file and headers

    private String pkg;
    private Pos pkgPos;
    private final List<Import> imports = new ArrayList<>();

    private ClassDecl parseFile() {
        try {
            while (check(PACKAGE) || check(IMPORT)) {
                Pos at = here();
                if (accept(PACKAGE)) {
                    String name = parseQualifiedName("package name");
                    expect(SEMICOLON, "';' after the package name");
                    if (pkg != null || !imports.isEmpty()) {
                        errors.add(new ParseError(pkg != null ? "a file is in one package" : "the package comes first,"
                                + " before the imports", at.line(), at.column()));
                    } else {
                        pkg = name;
                        pkgPos = at;
                    }
                    continue;
                }
                advance();
                boolean isStatic = accept(STATIC);
                StringBuilder name = new StringBuilder(expectIdent("name to import"));
                boolean all = false;
                while (accept(DOT)) {
                    if (accept(STAR)) {
                        all = true; // import com.example.*;
                        break;
                    }
                    name.append('.').append(expectIdent("name to import"));
                }
                String alias = null;
                if (check(IDENTIFIER) && peek().text().equals("as")) {
                    // `as` is a word only here, so it stays a name everywhere else.
                    Pos asAt = here();
                    advance();
                    alias = expectIdent("the name to import it as");
                    if (all) {
                        errors.add(new ParseError("a * import brings many names, and as renames one: import each by name,"
                                + " as in import " + name + ".Name as Other;", asAt.line(), asAt.column()));
                        alias = null;
                    }
                }
                expect(SEMICOLON, "';' after the import");
                if (!all && name.indexOf(".") < 0) {
                    errors.add(new ParseError(isStatic ? "import static names a type and its member, as in import static"
                            + " seal.lang.Math.max; or all of its static members, as in import static seal.lang.Math.*;"
                            : "import names a type in a package by its full name, as in import seal.util." + name
                            + "; or import com.example." + name + "; a type in no package needs no import",
                            at.line(), at.column()));
                } else {
                    imports.add(new Import(at, name.toString(), all, isStatic, alias));
                }
            }
        } catch (Bail b) {
            // reported; read the type anyway
        }
        ClassDecl decl = parseTypeDecl(false);
        if (!check(EOF)) {
            errors.add(new ParseError("unexpected '" + peek().text() + "' after the type's body; each file holds one"
                    + " top-level type (others can be nested inside it)", peek().line(), peek().column()));
        }
        return decl;
    }

    /** a.b.c */
    private String parseQualifiedName(String what) {
        StringBuilder name = new StringBuilder(expectIdent(what));
        while (accept(DOT)) {
            name.append('.').append(expectIdent(what));
        }
        return name.toString();
    }

    /**
     * One type: its headers and body, or an enum. {@code isAbstract} is set when a nested type's
     * modifiers, read before it, included `abstract`.
     */
    private ClassDecl parseTypeDecl(boolean isAbstract) {
        Pos start = here();
        if (check(ENUM)) {
            return parseEnum();
        }
        if (isRecordStart()) {
            return parseRecord();
        }
        List<ClassHeader> headers = new ArrayList<>();
        List<Member> members = new ArrayList<>();
        List<NestedType> nested = new ArrayList<>();
        try {
            while (check(STRUCT) || check(UNIQUE) || check(CLASS) || check(INTERFACE) || check(ABSTRACT)) {
                ClassHeader h = parseHeader();
                if (isAbstract && !h.isAbstract()) {
                    h = new ClassHeader(h.pos(), h.kind(), true, h.name(), h.typeParams(), h.valueParams(),
                            h.extendsClauses(), h.interfaces());
                }
                headers.add(h);
            }
            if (headers.isEmpty()) {
                error("expected a type declaration: struct, unique, class, interface or enum");
            }
            expect(LBRACE, "'{' to start the class body");
        } catch (Bail b) {
            return new ClassDecl(start, headers, members, nested);
        }
        boolean outerRecord = inRecord;
        inRecord = false;
        parseBody(members, nested);
        inRecord = outerRecord;
        try {
            expect(RBRACE, "'}' to end the class body");
        } catch (Bail ignored) {
            // reported
        }
        return new ClassDecl(start, headers, members, nested);
    }

    /** Members and nested types up to the closing brace. */
    private void parseBody(List<Member> members, List<NestedType> nested) {
        while (!check(RBRACE) && !check(EOF)) {
            int before = pos;
            try {
                Pos start = here();
                Modifiers mods = parseModifiers();
                if (check(CLASS) || check(STRUCT) || check(UNIQUE) || check(INTERFACE) || check(ENUM) || isRecordStart()) {
                    nested.add(new NestedType(start, mods, parseTypeDecl(mods.isAbstract())));
                } else {
                    members.add(parseMember(start, mods));
                }
            } catch (Bail b) {
                syncMember();
                if (pos == before) {
                    advance();
                }
            }
        }
    }

    /** `(a, b) ->` or `(int a, string b) ->` or `() ->`, up to and including the arrow. */
    private List<Param> parseLambdaParams() {
        expect(LPAREN, "'('");
        List<Param> params = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                Pos p = here();
                TokenKind after = pos + 1 < tokens.size() ? tokens.get(pos + 1).kind() : EOF;
                if (check(IDENTIFIER) && (after == COMMA || after == RPAREN)) {
                    params.add(new Param(p, null, advance().text()));
                } else {
                    TypeRef type = parseType();
                    params.add(new Param(p, type, expectIdent("parameter name")));
                }
            } while (accept(COMMA));
        }
        expect(RPAREN, "')'");
        expect(ARROW, "'->'");
        return params;
    }

    private Expr lambdaBody(Pos start, List<Param> params) {
        if (check(LBRACE)) {
            return new Lambda(start, params, null, parseBlock());
        }
        return new Lambda(start, params, parseExpr(), null);
    }

    private Stmt parseSwitch() {
        Pos start = here();
        advance();
        expect(LPAREN, "'(' after switch");
        Expr subject = parseExpr();
        expect(RPAREN, "')'");
        expect(LBRACE, "'{' to start the switch");
        List<Case> cases = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            Pos cp = here();
            List<Expr> labels = new ArrayList<>();
            boolean isDefault = accept(DEFAULT);
            if (!isDefault) {
                expect(CASE, "case or default");
                inCaseLabel = true;
                try {
                    do {
                        labels.add(parseExpr());
                    } while (accept(COMMA));
                } finally {
                    inCaseLabel = false;
                }
            }
            List<Stmt> body = new ArrayList<>();
            boolean arrow = accept(ARROW);
            if (arrow) {
                body.add(parseStatement());
            } else {
                expect(COLON, "':' or '->' after the case");
                while (!check(CASE) && !check(DEFAULT) && !check(RBRACE) && !check(EOF)) {
                    body.add(parseStatement());
                }
            }
            cases.add(new Case(cp, labels, isDefault, arrow, body));
        }
        expect(RBRACE, "'}' to end the switch");
        return new Switch(start, subject, cases);
    }

    /**
     * `switch (x) { case 1, 2 -> a; case 3 -> { ...; yield b; } default -> throw ...; }` used as
     * a value (§41.3). A case is an expression, a block that yields, or a throw.
     */
    private Expr parseSwitchExpr() {
        Pos start = here();
        advance();
        expect(LPAREN, "'(' after switch");
        Expr subject = parseExpr();
        expect(RPAREN, "')'");
        expect(LBRACE, "'{' to start the switch");
        List<Case> cases = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            Pos cp = here();
            List<Expr> labels = new ArrayList<>();
            boolean isDefault = accept(DEFAULT);
            if (!isDefault) {
                expect(CASE, "case or default");
                inCaseLabel = true;
                try {
                    do {
                        labels.add(parseExpr());
                    } while (accept(COMMA));
                } finally {
                    inCaseLabel = false;
                }
            }
            List<Stmt> body = new ArrayList<>();
            if (!accept(ARROW)) {
                // case 1: ... yield 2; as in Java, falling through to the next case unless it yields
                expect(COLON, "'->' or ':' after the case");
                while (!check(CASE) && !check(DEFAULT) && !check(RBRACE) && !check(EOF)) {
                    body.add(parseStatement());
                }
                cases.add(new Case(cp, labels, isDefault, false, body));
                continue;
            }
            if (check(LBRACE)) {
                body.addAll(parseBlock().stmts());
            } else if (check(THROW)) {
                body.add(parseStatement());
            } else {
                Pos vp = here();
                Expr value = parseExpr();
                expect(SEMICOLON, "';' after the case's value");
                body.add(new Yield(vp, value));
            }
            cases.add(new Case(cp, labels, isDefault, true, body));
        }
        expect(RBRACE, "'}' to end the switch");
        return new SwitchExpr(start, subject, cases);
    }

    /** The source of tokens from..to-1, spaced as written (on one line), for messages. */
    private String sourceText(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to && i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (i > from) {
                Token prev = tokens.get(i - 1);
                boolean touching = prev.line() == t.line() && prev.column() + prev.text().length() == t.column();
                if (!touching) {
                    out.append(' ');
                }
            }
            out.append(t.text());
        }
        return out.toString();
    }

    // ================================================================ records

    /** Whether a record declaration starts here: `record` is a word only before a type's name, as in Java. */
    private boolean isRecordStart() {
        return check(IDENTIFIER) && peek().text().equals("record") && pos + 1 < tokens.size()
                && tokens.get(pos + 1).kind() == IDENTIFIER;
    }

    /** Set while a record's body is read: only there may a constructor be compact, `new { ... }`. */
    private boolean inRecord;

    /**
     * `record Point(int x, int y) { members }`, turned into the struct it stands for (§2.5):
     * the components as private fields fixed when it is made; a default value with each one
     * zero or empty, for collections; a constructor taking them all, after a compact
     * constructor's checks if it has one; and unless it declares its own, an accessor for
     * each, == comparing them, hash() combining them, and a cast to string: "Point[x=1, y=2]".
     */
    private ClassDecl parseRecord() {
        Pos start = here();
        advance(); // record
        String name;
        List<String> typeParams = new ArrayList<>();
        List<String> types = new ArrayList<>();       // as written, for the generated code
        List<TypeRef> typeRefs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Pos> namePos = new ArrayList<>();
        List<Member> userMembers = new ArrayList<>();
        List<NestedType> nested = new ArrayList<>();
        try {
            name = expectIdent("record name");
            if (accept(LT)) {
                do {
                    typeParams.add(expectIdent("type parameter name"));
                } while (accept(COMMA));
                expectGt();
            }
            if (check(DOUBLE_LT) || check(EXTENDS) || check(IMPLEMENTS)) {
                throw error("a record has no shapes, parents or interfaces");
            }
            expect(LPAREN, "'(' and the record's components, as in record Point(int x, int y)");
            if (!check(RPAREN)) {
                do {
                    namePos.add(here());
                    int from = pos;
                    TypeRef t = parseType();
                    types.add(sourceText(from, pos));
                    typeRefs.add(t);
                    names.add(expectIdent("component name"));
                } while (accept(COMMA));
            }
            expect(RPAREN, "')'");
            if (check(IMPLEMENTS) || check(EXTENDS)) {
                Token t = peek();
                errors.add(new ParseError("a record has no parents or interfaces; it is a struct (§2.5)", t.line(), t.column()));
                advance();
                if (check(LPAREN)) {
                    int depth = 0;
                    do {
                        depth += check(LPAREN) ? 1 : check(RPAREN) ? -1 : 0;
                        advance();
                    } while (depth > 0 && !check(EOF));
                }
            }
            expect(LBRACE, "'{' to start the record's body");
        } catch (Bail b) {
            return new ClassDecl(start, List.of(), List.of());
        }
        boolean outerRecord = inRecord;
        inRecord = true;
        parseBody(userMembers, nested);
        inRecord = outerRecord;
        try {
            expect(RBRACE, "'}' to end the record");
        } catch (Bail ignored) {
            // reported
        }
        for (int i = names.size() - 1; i > 0; i--) {
            if (names.subList(0, i).contains(names.get(i))) {
                errors.add(new ParseError("duplicate component " + names.get(i), namePos.get(i).line(), namePos.get(i).column()));
                names.remove(i);
                types.remove(i);
                typeRefs.remove(i);
                namePos.remove(i);
            }
        }

        Constructor compact = null;
        boolean ownCanonical = false;
        List<Member> kept = new ArrayList<>();
        for (Member m : userMembers) {
            if (m instanceof Field f && !f.modifiers().isStatic() && !f.modifiers().isConst()) {
                errors.add(new ParseError("a record's fields are its components: put " + f.name() + " in " + name
                        + "(...), or make it static", f.pos().line(), f.pos().column()));
            } else if (m instanceof Constructor k && k.params() == null) {
                if (compact != null) {
                    errors.add(new ParseError("a record has one compact constructor", k.pos().line(), k.pos().column()));
                }
                compact = k;
            } else {
                if (m instanceof Constructor k && k.params().size() == names.size()
                        && k.params().stream().map(Param::name).toList().equals(names)) {
                    ownCanonical = true; // new(int x, int y) written out
                }
                kept.add(m);
            }
        }
        if (compact != null && ownCanonical) {
            errors.add(new ParseError("a record takes a compact constructor or one taking every component, not both",
                    compact.pos().line(), compact.pos().column()));
        }

        String self = typeParams.isEmpty() ? name : name + "<" + String.join(", ", typeParams) + ">";
        StringBuilder src = new StringBuilder("\n".repeat(start.line() - 1)); // generated code reports the record's line
        src.append("struct ").append(self).append(" { ");
        for (int i = 0; i < names.size(); i++) {
            // Public, but fixed once made (the checker sees to that): read as p.x, or p.x() as in Java.
            src.append("public ").append(types.get(i)).append(' ').append(names.get(i)).append("; ");
        }
        src.append("public new() { } "); // the default value, which collections fill empty slots with
        if (!ownCanonical && compact == null) {
            src.append("public new(");
            for (int i = 0; i < names.size(); i++) {
                src.append(i == 0 ? "" : ", ").append(types.get(i)).append(' ').append(names.get(i));
            }
            src.append(") { ");
            for (String n : names) {
                src.append("this.").append(n).append(" = ").append(n).append("; ");
            }
            src.append("} ");
        }
        kept.removeIf(m -> {
            if (m instanceof Method md && names.contains(md.name())) {
                errors.add(new ParseError("a record's component " + md.name() + " is read as p." + md.name() + " or p."
                        + md.name() + "(), so a method can't have its name", md.pos().line(), md.pos().column()));
                return true;
            }
            return false;
        });
        if (kept.stream().noneMatch(m -> m instanceof Method md && overloadsConstant(md, "EQUALS"))) {
            src.append("@overload(Operator.EQUALS) public bit __equal(").append(self).append(" other) { return ");
            src.append(names.isEmpty() ? "true" : String.join(" && ", names.stream()
                    .map(n -> "Objects.equals(" + n + ", other." + n + ")").toList()));
            src.append("; } ");
        }
        if (kept.stream().noneMatch(m -> m instanceof Method md && md.name().equals("hash") && md.params().isEmpty())) {
            src.append("public uint<<64>> hash() { uint<<64>> h = 17; ");
            for (String n : names) {
                src.append("h = h * 31 + Objects.hash(").append(n).append("); ");
            }
            src.append("return h; } ");
        }
        if (!hasStringCast(kept)) {
            src.append("@overload(Operator.CAST) public string __string() { return \"").append(name).append("[\"");
            for (int i = 0; i < names.size(); i++) {
                src.append(" + \"").append(i == 0 ? "" : ", ").append(names.get(i)).append("=\" + Objects.toString(")
                        .append(names.get(i)).append(")");
            }
            src.append(" + \"]\"; } ");
        }
        src.append("}");
        Result generated = parse(src.toString());
        errors.addAll(generated.errors());
        if (generated.ast().headers().isEmpty()) {
            return new ClassDecl(start, List.of(), List.of());
        }

        List<Member> members = new ArrayList<>(generated.ast().members());
        if (compact != null) {
            // new { checks } is new(int x, int y) { checks; this.x = x; this.y = y; }: the checks may change the values.
            List<Param> params = new ArrayList<>();
            List<Stmt> body = new ArrayList<>(compact.body().stmts());
            Pos p = compact.pos();
            for (int i = 0; i < names.size(); i++) {
                params.add(new Param(namePos.get(i), typeRefs.get(i), names.get(i)));
                body.add(new ExprStmt(p, new Assign(p, null, new Select(p, new This(p), names.get(i)), new Name(p, names.get(i)))));
            }
            Modifiers m = compact.modifiers(); // public, as Java requires of a record's constructor
            members.add(new Constructor(p, new Modifiers(m.decorators(), Access.PUBLIC, m.friends(), false, false, false),
                    null, params, new Block(compact.body().pos(), body)));
        }
        members.addAll(kept);
        ClassHeader g = generated.ast().headers().get(0);
        ClassHeader header = new ClassHeader(start, Kind.RECORD, false, name, g.typeParams(), null, List.of(), List.of());
        return new ClassDecl(start, List.of(header), members, nested);
    }

    /** Whether a method is @overload(Operator.NAME). */
    private static boolean overloadsConstant(Method md, String constant) {
        return md.modifiers().decorators().stream().anyMatch(d -> d.name().equals("overload") && d.args().size() == 1
                && d.args().get(0) instanceof Select sel && sel.name().equals(constant));
    }

    /** Whether the members declare a cast to string: @overload(Operator.CAST) with no parameters, returning string. */
    private static boolean hasStringCast(List<Member> members) {
        return members.stream().anyMatch(m -> m instanceof Method md && md.params().isEmpty()
                && md.returnType().name().equals("string") && md.returnType().valueArgs() == null
                && overloadsConstant(md, "CAST"));
    }

    // ================================================================ enums

    /**
     * `enum Color { RED, GREEN(args), BLUE; members }`, turned into the struct it stands for
     * (§38.1): a private ordinal, a static field per constant, a constructor taking the
     * ordinal first, and generated ordinal(), name(), values(), valueOf(), == and <.
     */
    private ClassDecl parseEnum() {
        Pos start = here();
        advance();
        String name;
        List<String> constants = new ArrayList<>();
        List<Pos> constantPos = new ArrayList<>();
        List<List<Expr>> constantArgs = new ArrayList<>();
        List<Member> userMembers = new ArrayList<>();
        List<NestedType> nested = new ArrayList<>();
        try {
            name = expectIdent("enum name");
            if (check(LT) || check(DOUBLE_LT) || check(EXTENDS) || check(IMPLEMENTS)) {
                throw error("an enum has no type parameters, shapes, parents or interfaces");
            }
            expect(LBRACE, "'{' to start the enum body");
            while (check(IDENTIFIER)) {
                constantPos.add(here());
                constants.add(advance().text());
                constantArgs.add(check(LPAREN) ? parseArgs() : List.of());
                if (!accept(COMMA)) {
                    break;
                }
            }
            if (constants.isEmpty()) {
                error("an enum needs at least one constant");
            }
        } catch (Bail b) {
            return new ClassDecl(start, List.of(), List.of());
        }
        if (accept(SEMICOLON)) {
            boolean outerRecord = inRecord;
            inRecord = false;
            parseBody(userMembers, nested);
            inRecord = outerRecord;
        }
        try {
            expect(RBRACE, "'}' to end the enum");
        } catch (Bail ignored) {
            // reported
        }
        for (int i = 0; i < constants.size(); i++) {
            String c = constants.get(i);
            if (constants.indexOf(c) != i) {
                errors.add(new ParseError("duplicate enum constant " + c, constantPos.get(i).line(), constantPos.get(i).column()));
            }
            if (Set.of("ordinal", "name", "values", "valueOf").contains(c) || c.startsWith("__")) {
                errors.add(new ParseError("enum constant can't be named " + c, constantPos.get(i).line(), constantPos.get(i).column()));
            }
        }
        ClassDecl decl = enumStruct(start, name, constants, constantPos, constantArgs, userMembers);
        return new ClassDecl(decl.pos(), decl.headers(), decl.members(), nested);
    }

    private ClassDecl enumStruct(Pos start, String name, List<String> constants, List<Pos> constantPos,
                                 List<List<Expr>> constantArgs, List<Member> userMembers) {
        boolean userConstructors = userMembers.stream().anyMatch(m -> m instanceof Constructor);
        StringBuilder src = new StringBuilder("\n".repeat(start.line() - 1)); // generated code reports the enum's line
        src.append("struct ").append(name).append(" { int __ordinal; ");
        src.append("public new() { } ");   // a default value: ordinal 0, the first constant
        if (!userConstructors) {
            src.append("new(int __o) { __ordinal = __o; } ");
        }
        src.append("public int ordinal() { return __ordinal; } ");
        src.append("public string name() { ");
        for (int i = 0; i < constants.size() - 1; i++) {
            src.append("if (__ordinal == ").append(i).append(") { return \"").append(constants.get(i)).append("\"; } ");
        }
        src.append("return \"").append(constants.get(constants.size() - 1)).append("\"; } ");
        if (!hasStringCast(userMembers)) {
            // "" + Color.RED and println(Color.RED) show the name, as in Java (§46)
            src.append("@overload(Operator.CAST) public string __string() { return name(); } ");
        }
        src.append("public static array<").append(name).append("> values() { return [")
                .append(String.join(", ", constants)).append("]; } ");
        src.append("public static ").append(name).append(" valueOf(string s) { ");
        for (String c : constants) {
            src.append("if (s == \"").append(c).append("\") { return ").append(c).append("; } ");
        }
        src.append("throw new IllegalArgumentException(\"no ").append(name).append(" constant named \" + s); } ");
        src.append("@overload(Operator.EQUALS) public bit __equal(").append(name).append(" other) { return __ordinal == other.__ordinal; } ");
        src.append("@overload(Operator.LESS) public bit __less(").append(name).append(" other) { return __ordinal < other.__ordinal; } ");
        src.append("}");
        Result generated = parse(src.toString());
        errors.addAll(generated.errors());

        List<Member> members = new ArrayList<>();
        TypeRef self = new TypeRef(start, name, List.of(), null, List.of());
        Modifiers publicStatic = new Modifiers(List.of(), Access.PUBLIC, List.of(), true, false, false);
        for (int i = 0; i < constants.size(); i++) {
            Pos p = constantPos.get(i);
            List<Expr> args = new ArrayList<>();
            args.add(new IntLit(p, String.valueOf(i), BigInteger.valueOf(i)));
            args.addAll(constantArgs.get(i));
            members.add(new Field(p, publicStatic, self, constants.get(i), null, new New(p, self, args)));
        }
        members.addAll(generated.ast().members());
        TypeRef intType = new TypeRef(start, "int", List.of(), null, List.of());
        for (Member m : userMembers) {
            if (m instanceof Constructor k) {
                // Every constructor takes the ordinal first; constants are the only callers.
                List<Param> params = new ArrayList<>();
                params.add(new Param(k.pos(), intType, "__o"));
                params.addAll(k.params());
                List<Stmt> body = new ArrayList<>();
                body.add(new ExprStmt(k.pos(), new Assign(k.pos(), null, new Name(k.pos(), "__ordinal"), new Name(k.pos(), "__o"))));
                body.addAll(k.body().stmts());
                members.add(new Constructor(k.pos(), k.modifiers(), k.guard(), params, new Block(k.body().pos(), body)));
            } else {
                members.add(m);
            }
        }
        ClassHeader header = new ClassHeader(start, Kind.ENUM, false, name, List.of(), null, List.of(), List.of());
        return new ClassDecl(start, List.of(header), members);
    }

    private ClassHeader parseHeader() {
        Pos start = here();
        boolean isAbstract = accept(ABSTRACT);
        Kind kind = switch (kind()) {
            case STRUCT -> Kind.STRUCT;
            case UNIQUE -> Kind.UNIQUE;
            case CLASS -> Kind.CLASS;
            case INTERFACE -> Kind.INTERFACE;
            default -> throw error("expected struct, unique, class or interface");
        };
        advance();
        String name = expectIdent("type name");

        List<String> typeParams = new ArrayList<>();
        if (accept(LT)) {
            do {
                typeParams.add(expectIdent("type parameter name"));
            } while (accept(COMMA));
            expectGt();
        }

        ValueList valueParams = check(DOUBLE_LT) ? parseValueList() : null;
        if (check(LT)) {
            error("type parameters <...> must come before value parameters <<...>>");
        }

        List<ExtendsClause> extendsClauses = new ArrayList<>();
        List<TypeRef> interfaces = new ArrayList<>();
        if (kind == Kind.INTERFACE) {
            // An interface extends any number of interfaces: interface A extends(B, C)
            if (accept(EXTENDS)) {
                interfaces.addAll(parseTypeList("an interface"));
            }
            if (check(IMPLEMENTS)) {
                error("an interface extends other interfaces; use extends(...)");
            }
            return new ClassHeader(start, kind, isAbstract, name, typeParams, valueParams, extendsClauses, interfaces);
        }
        while (check(EXTENDS)) {
            Pos extStart = here();
            advance();
            ValueList guard = check(DOUBLE_LT) ? parseValueList() : null;
            expect(LPAREN, "'(' after extends");
            TypeRef parent = parseType();
            expect(RPAREN, "')' after the parent class");
            extendsClauses.add(new ExtendsClause(extStart, guard, parent));
        }
        while (accept(IMPLEMENTS)) {
            interfaces.addAll(parseTypeList("an interface"));
        }
        return new ClassHeader(start, kind, isAbstract, name, typeParams, valueParams, extendsClauses, interfaces);
    }

    /** `(A, B<T>, ...)` after extends or implements. */
    private List<TypeRef> parseTypeList(String what) {
        expect(LPAREN, "'('");
        List<TypeRef> types = new ArrayList<>();
        do {
            types.add(parseType());
        } while (accept(COMMA));
        expect(RPAREN, "')' after " + what);
        return types;
    }

    // ================================================================ value lists and types

    /** Parses `<<...>>`, where each item is a binder (`int N`) or a value (`N + 1`). */
    private ValueList parseValueList() {
        Pos start = here();
        expect(DOUBLE_LT, "'<<'");
        valueListDepth++;
        try {
            List<ValueItem> items = new ArrayList<>();
            if (!check(DOUBLE_GT)) {
                do {
                    items.add(parseValueItem());
                } while (accept(COMMA));
            }
            expect(DOUBLE_GT, "'>>' to close '<<'");
            return new ValueList(start, items);
        } finally {
            valueListDepth--;
        }
    }

    private ValueItem parseValueItem() {
        Pos start = here();
        Binder binder = speculate(() -> {
            TypeRef type = parseType();
            String name = expectIdent("name");
            return new Binder(start, type, name);
        });
        if (binder != null) {
            return binder;
        }
        return new ValueExpr(start, parseExpr());
    }

    private TypeRef parseType() {
        Pos start = here();
        String name;
        if (PRIMITIVE_TYPES.contains(kind())) {
            name = advance().text();
        } else if (check(IDENTIFIER)) {
            name = advance().text();
            // A nested type: Outer.Inner (§2.3)
            while (check(DOT) && pos + 1 < tokens.size() && tokens.get(pos + 1).kind() == IDENTIFIER) {
                advance();
                name = name + "." + advance().text();
            }
        } else {
            throw error("expected a type");
        }

        List<TypeRef> typeArgs = new ArrayList<>();
        if (accept(LT)) {
            do {
                typeArgs.add(parseType());
            } while (accept(COMMA));
            expectGt();
        }

        ValueList valueArgs = check(DOUBLE_LT) ? parseValueList() : null;

        List<Expr> arrayDims = new ArrayList<>();
        while (check(LBRACKET)) {
            advance();
            arrayDims.add(check(RBRACKET) ? null : parseExpr());
            expect(RBRACKET, "']'");
        }
        return new TypeRef(start, name, typeArgs, valueArgs, arrayDims);
    }

    // ================================================================ members

    private Member parseMember(Pos start, Modifiers mods) {
        // A generic method: public <T> T largest(List<T> xs)
        List<String> typeParams = new ArrayList<>();
        if (accept(LT)) {
            do {
                typeParams.add(expectIdent("type parameter name"));
            } while (accept(COMMA));
            expectGt();
            if (check(NEW) || check(DELETE)) {
                throw error("only methods can have type parameters, not constructors or destructors");
            }
        }

        if (accept(NEW)) {
            if (check(LBRACE)) {
                // A record's compact constructor: new { checks }, its parameters being the components (§2.5).
                if (!inRecord) {
                    throw error("only a record's constructor can leave out its parameters; write new(...)");
                }
                return new Constructor(start, mods, null, null, parseBlock());
            }
            ValueList guard = check(DOUBLE_LT) ? parseValueList() : null;
            List<Param> params = parseParams();
            return new Constructor(start, mods, guard, params, parseBlock());
        }
        if (accept(DELETE)) {
            ValueList guard = check(DOUBLE_LT) ? parseValueList() : null;
            expect(LPAREN, "'(' after delete");
            expect(RPAREN, "')': destructors take no parameters");
            return new Destructor(start, mods, guard, parseBlock());
        }

        TypeRef type = parseType();
        // `delete` can name a method, as in Java's Files.delete; with no return type it is a destructor.
        String name = check(DELETE) ? advance().text() : expectIdent("member name");
        ValueList guard = check(DOUBLE_LT) ? parseValueList() : null;

        if (check(LPAREN)) {
            List<Param> params = parseParams();
            if (accept(SEMICOLON)) {
                return new Method(start, mods, typeParams, type, name, guard, params, null); // abstract: no body
            }
            return new Method(start, mods, typeParams, type, name, guard, params, parseBlock());
        }

        Expr init = accept(ASSIGN) ? parseExpr() : null;
        expect(SEMICOLON, "';' after field declaration");
        return new Field(start, mods, type, name, guard, init);
    }

    private Modifiers parseModifiers() {
        List<Decorator> decorators = new ArrayList<>();
        Access access = null;
        List<TypeRef> friends = new ArrayList<>();
        boolean isStatic = false;
        boolean isConst = false;
        boolean isAbstract = false;
        boolean isDefault = false;
        boolean isPure = false;

        while (true) {
            if (check(AT) && pos + 1 < tokens.size() && tokens.get(pos + 1).kind() == PURE) {
                advance();
                error("pure is a keyword, as static is, not a decorator: write `static pure` before the return type");
                advance();
                isPure = true;
            } else if (check(AT)) {
                Pos start = here();
                advance();
                String name = expectIdent("decorator name");
                List<Expr> args = check(LPAREN) ? parseArgs() : List.of();
                decorators.add(new Decorator(start, name, args));
            } else if (check(PUBLIC) || check(PRIVATE)) {
                if (access != null) {
                    error("duplicate access modifier");
                }
                access = advance().kind() == PUBLIC ? Access.PUBLIC : Access.PRIVATE;
                if (access == Access.PRIVATE && accept(LT)) {
                    do {
                        friends.add(parseType());
                    } while (accept(COMMA));
                    expectGt();
                }
            } else if (check(STATIC)) {
                if (isStatic) {
                    error("duplicate 'static'");
                }
                advance();
                isStatic = true;
            } else if (check(ABSTRACT)) {
                if (isAbstract) {
                    error("duplicate 'abstract'");
                }
                advance();
                isAbstract = true;
            } else if (check(CONST)) {
                if (isConst) {
                    error("duplicate 'const'");
                }
                advance();
                isConst = true;
            } else if (check(DEFAULT)) {
                if (isDefault) {
                    error("duplicate 'default'");
                }
                advance();
                isDefault = true;
            } else if (check(PURE)) {
                if (isPure) {
                    error("duplicate 'pure'");
                }
                advance();
                isPure = true;
            } else {
                break;
            }
        }
        return new Modifiers(decorators, access == null ? Access.PRIVATE : access, friends, isStatic, isConst, isAbstract,
                isDefault, isPure);
    }

    private List<Param> parseParams() {
        expect(LPAREN, "'('");
        List<Param> params = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                if (check(RPAREN)) {
                    error("a parameter list can't end with a comma"); // unlike arguments: f(a, b,) is fine
                    break;
                }
                Pos start = here();
                TypeRef type = parseType();
                params.add(new Param(start, type, expectIdent("parameter name")));
            } while (accept(COMMA));
        }
        expect(RPAREN, "')' after parameters");
        return params;
    }

    // ================================================================ statements

    private Block parseBlock() {
        Pos start = here();
        expect(LBRACE, "'{'");
        List<Stmt> stmts = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) {
            int before = pos;
            try {
                stmts.add(parseStatement());
            } catch (Bail b) {
                syncStatement();
                if (pos == before) {
                    advance();
                }
            }
        }
        expect(RBRACE, "'}'");
        return new Block(start, stmts);
    }

    private Stmt parseStatement() {
        Pos start = here();
        switch (kind()) {
            case LBRACE:
                return parseBlock();
            case IF: {
                advance();
                expect(LPAREN, "'(' after if");
                Expr cond = parseExpr();
                expect(RPAREN, "')'");
                Stmt then = parseStatement();
                Stmt otherwise = accept(ELSE) ? parseStatement() : null;
                return new If(start, cond, then, otherwise);
            }
            case WHILE: {
                advance();
                expect(LPAREN, "'(' after while");
                Expr cond = parseExpr();
                expect(RPAREN, "')'");
                return new While(start, cond, parseStatement());
            }
            case FOR: {
                advance();
                expect(LPAREN, "'(' after for");
                // for (T x : items)
                Object[] head = speculate(() -> {
                    TypeRef type = parseType();
                    String name = expectIdent("name");
                    expect(COLON, "':'");
                    return new Object[]{type, name};
                });
                if (head != null) {
                    Expr iterable = parseExpr();
                    expect(RPAREN, "')'");
                    return new ForEach(start, (TypeRef) head[0], (String) head[1], iterable, parseStatement());
                }
                Stmt init = check(SEMICOLON) ? null : parseSimpleStatement();
                expect(SEMICOLON, "';'");
                Expr cond = check(SEMICOLON) ? null : parseExpr();
                expect(SEMICOLON, "';'");
                Expr update = check(RPAREN) ? null : parseExpr();
                expect(RPAREN, "')'");
                return new For(start, init, cond, update, parseStatement());
            }
            case SWITCH:
                return parseSwitch();
            case THROW: {
                advance();
                Expr value = parseExpr();
                expect(SEMICOLON, "';' after throw");
                return new Throw(start, value);
            }
            case TRY: {
                advance();
                List<VarDecl> resources = new ArrayList<>();
                if (accept(LPAREN)) {
                    // try (R r = ...; S s = ...) { }: try-with-resources, a trailing ';' allowed as in Java.
                    do {
                        if (check(RPAREN) && !resources.isEmpty()) {
                            break;
                        }
                        Pos rp = here();
                        TypeRef type = parseType();
                        String name = expectIdent("resource variable name");
                        expect(ASSIGN, "'=' and the resource after " + name);
                        resources.add(new VarDecl(rp, type, name, parseExpr()));
                    } while (accept(SEMICOLON));
                    expect(RPAREN, "')' after the resources");
                }
                Block body = parseBlock();
                List<Catch> catches = new ArrayList<>();
                while (check(CATCH)) {
                    Pos cp = here();
                    advance();
                    expect(LPAREN, "'(' after catch");
                    TypeRef type = parseType();
                    String name = expectIdent("exception variable name");
                    expect(RPAREN, "')'");
                    catches.add(new Catch(cp, type, name, parseBlock()));
                }
                Block fin = accept(FINALLY) ? parseBlock() : null;
                if (catches.isEmpty() && fin == null && resources.isEmpty()) {
                    throw error("try needs a catch or a finally");
                }
                return new Try(start, resources, body, catches, fin);
            }
            case RETURN: {
                advance();
                Expr value = check(SEMICOLON) ? null : parseExpr();
                expect(SEMICOLON, "';' after return");
                return new Return(start, value);
            }
            case BREAK: {
                advance();
                String label = check(IDENTIFIER) ? expectIdent("label") : null;
                expect(SEMICOLON, "';' after break");
                return new Break(start, label);
            }
            case CONTINUE: {
                advance();
                String label = check(IDENTIFIER) ? expectIdent("label") : null;
                expect(SEMICOLON, "';' after continue");
                return new Continue(start, label);
            }
            case DELETE: {
                advance();
                Expr target = parseExpr();
                expect(SEMICOLON, "';' after delete");
                return new DeleteStmt(start, target);
            }
            case ASSERT: {
                advance();
                int from = pos;
                Expr cond = parseExpr();
                String text = sourceText(from, pos);
                Expr message = accept(COLON) ? parseExpr() : null;
                expect(SEMICOLON, "';' after assert");
                return new Assert(start, cond, message, text);
            }
            default: {
                if (check(IDENTIFIER) && peek().text().equals("yield") && pos + 1 < tokens.size()
                        && !Set.of(ASSIGN, DOT, LBRACKET, SEMICOLON, PLUS_PLUS, MINUS_MINUS, PLUS_ASSIGN, MINUS_ASSIGN,
                                STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN, AMP_ASSIGN, PIPE_ASSIGN, CARET_ASSIGN, SHIFT_ASSIGN,
                                COLON).contains(tokens.get(pos + 1).kind())) {
                    // yield value; in a switch expression's case block (a variable named yield still works)
                    advance();
                    Expr value = parseExpr();
                    expect(SEMICOLON, "';' after yield");
                    return new Yield(start, value);
                }
                if (check(IDENTIFIER) && pos + 1 < tokens.size() && tokens.get(pos + 1).kind() == COLON) {
                    // outer: for (...) { ... break outer; ... }
                    String label = expectIdent("label");
                    advance();
                    return new Labeled(start, label, parseStatement());
                }
                Stmt stmt = parseSimpleStatement();
                expect(SEMICOLON, "';'");
                return stmt;
            }
        }
    }

    /** A variable declaration or expression, without the trailing `;`. */
    private Stmt parseSimpleStatement() {
        Pos start = here();
        if (PRIMITIVE_TYPES.contains(kind())) {
            TypeRef type = parseType();
            return finishVarDecl(start, type, expectIdent("variable name"));
        }
        if (check(IDENTIFIER)) {
            // `Foo<T> x = ...` and `a < b > c` start the same way. Try `Type name`
            // followed by `=` or `;`; only that prefix is speculative, so errors in
            // the initializer are still reported normally.
            record Head(TypeRef type, String name) {
            }
            Head head = speculate(() -> {
                TypeRef type = parseType();
                String name = expectIdent("variable name");
                if (!check(ASSIGN) && !check(SEMICOLON)) {
                    throw error("not a declaration");
                }
                return new Head(type, name);
            });
            if (head != null) {
                return finishVarDecl(start, head.type(), head.name());
            }
        }
        return new ExprStmt(start, parseExpr());
    }

    private VarDecl finishVarDecl(Pos start, TypeRef type, String name) {
        Expr init = accept(ASSIGN) ? parseExpr() : null;
        return new VarDecl(start, type, name, init);
    }

    // ================================================================ expressions
    //
    // Precedence, lowest to highest:
    //   = += -= *= /= %= &= |= ^= <<=   (right associative)
    //   ||
    //   &&
    //   == != < > <= >=                 (not chainable)
    //   |
    //   ^
    //   &
    //   <<<
    //   + -
    //   * / %
    //   unary - ! ~ & * ++ -- and casts
    //   postfix . () [] [..] ++ --

    private Expr parseExpr() {
        return parseAssignment();
    }

    private Expr parseAssignment() {
        Expr left = parseConditional();
        Pos start = left.pos();
        if (check(ASSIGN)) {
            advance();
            return new Assign(start, null, left, parseAssignment());
        }
        BinaryOp op = ASSIGN_OPS.get(kind());
        if (op != null) {
            advance();
            return new Assign(start, op, left, parseAssignment());
        }
        return left;
    }

    /** `cond ? a : b`, grouping to the right: a ? b : c ? d : e is a ? b : (c ? d : e). */
    private Expr parseConditional() {
        Expr cond = parseOr();
        if (!accept(QUESTION)) {
            return cond;
        }
        boolean savedCase = inCaseLabel;
        inCaseLabel = false; // inside ?: a `:` ends the branch, not the case label
        Expr then;
        try {
            then = parseExpr();
        } finally {
            inCaseLabel = savedCase;
        }
        expect(COLON, "':' in ?:");
        return new Conditional(cond.pos(), cond, then, parseConditional());
    }

    private Expr parseOr() {
        Expr left = parseAnd();
        while (check(PIPE_PIPE)) {
            advance();
            left = new Binary(left.pos(), BinaryOp.OR, left, parseAnd());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseComparison();
        while (check(AMP_AMP)) {
            advance();
            left = new Binary(left.pos(), BinaryOp.AND, left, parseComparison());
        }
        return left;
    }

    private Expr parseComparison() {
        Expr left = parseBitOr();
        if (check(INSTANCEOF)) {
            advance();
            TypeRef type = parseType();
            String binding = check(IDENTIFIER) ? advance().text() : null;
            if (COMPARISON_OPS.containsKey(kind()) || check(INSTANCEOF)) {
                throw error("comparisons cannot be chained; use parentheses");
            }
            return new InstanceOf(left.pos(), left, type, binding);
        }
        BinaryOp op = COMPARISON_OPS.get(kind());
        if (op == null) {
            return left;
        }
        advance();
        Expr result = new Binary(left.pos(), op, left, parseBitOr());
        if (COMPARISON_OPS.containsKey(kind())) {
            throw error("comparisons cannot be chained; use parentheses");
        }
        return result;
    }

    private Expr parseBitOr() {
        Expr left = parseBitXor();
        while (check(PIPE)) {
            advance();
            left = new Binary(left.pos(), BinaryOp.BIT_OR, left, parseBitXor());
        }
        return left;
    }

    private Expr parseBitXor() {
        Expr left = parseBitAnd();
        while (check(CARET)) {
            advance();
            left = new Binary(left.pos(), BinaryOp.BIT_XOR, left, parseBitAnd());
        }
        return left;
    }

    private Expr parseBitAnd() {
        Expr left = parseShift();
        while (check(AMP)) {
            advance();
            left = new Binary(left.pos(), BinaryOp.BIT_AND, left, parseShift());
        }
        return left;
    }

    private Expr parseShift() {
        Expr left = parseAdditive();
        while (true) {
            if (check(SHIFT)) {
                advance();
                left = new Binary(left.pos(), BinaryOp.SHIFT, left, parseAdditive());
            } else if (check(DOUBLE_LT)) {
                throw error("'<<' is not a shift operator; use '<<<' (e.g. x <<< 3)");
            } else if (check(DOUBLE_GT) && valueListDepth == 0) {
                throw error("'>>' is not a shift operator; use '<<<' with a negative amount (e.g. x <<< -3)");
            } else {
                return left;
            }
        }
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        while (check(PLUS) || check(MINUS)) {
            BinaryOp op = advance().kind() == PLUS ? BinaryOp.ADD : BinaryOp.SUB;
            left = new Binary(left.pos(), op, left, parseMultiplicative());
        }
        return left;
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        while (check(STAR) || check(SLASH) || check(PERCENT)) {
            BinaryOp op = switch (advance().kind()) {
                case STAR -> BinaryOp.MUL;
                case SLASH -> BinaryOp.DIV;
                default -> BinaryOp.MOD;
            };
            left = new Binary(left.pos(), op, left, parseUnary());
        }
        return left;
    }

    private Expr parseUnary() {
        Pos start = here();
        switch (kind()) {
            case MINUS:
                advance();
                return new Unary(start, UnaryOp.NEG, parseUnary());
            case BANG:
                advance();
                return new Unary(start, UnaryOp.NOT, parseUnary());
            case TILDE:
                advance();
                return new Unary(start, UnaryOp.BIT_NOT, parseUnary());
            case PLUS_PLUS:
            case MINUS_MINUS:
                return new IncDec(start, advance().kind() == PLUS_PLUS ? BinaryOp.ADD : BinaryOp.SUB, true, parseUnary());
            case STAR:
                advance();
                return new Unary(start, UnaryOp.ADDRESS_OF, parseUnary());
            case AMP:
                advance();
                if (check(AMP) || check(AMP_AMP)) {
                    throw error("nested dereference requires parentheses: write &(&p)");
                }
                return new Unary(start, UnaryOp.DEREF, parseUnary());
            case AMP_AMP:
                throw error("'&&' is logical AND; to dereference twice write &(&p)");
            case LPAREN: {
                if (!inCaseLabel) {
                    List<Param> lambdaParams = speculate(this::parseLambdaParams);
                    if (lambdaParams != null) {
                        return lambdaBody(start, lambdaParams);
                    }
                }
                TypeRef castType = speculate(this::parseCastPrefix);
                if (castType != null) {
                    return new Cast(start, castType, parseUnary());
                }
                return parsePower();
            }
            default:
                return parsePower();
        }
    }

    /**
     * `a ** b`, a power: tighter than the unary operators before it, so -2 ** 2 is -(2 ** 2), and
     * grouping to the right, so 2 ** 3 ** 2 is 2 ** 9; the exponent may have a sign of its own, 2 ** -1.
     */
    private Expr parsePower() {
        Expr base = parsePostfix();
        if (check(STAR_STAR)) {
            advance();
            return new Binary(base.pos(), BinaryOp.POW, base, parseUnary());
        }
        return base;
    }

    /**
     * The `(Type)` part of a cast. A primitive type in parentheses is always a
     * cast. A class type is a cast only when followed by something that can
     * start an operand but not continue an expression, so `(a) - b` stays a
     * subtraction.
     */
    private TypeRef parseCastPrefix() {
        expect(LPAREN, "'('");
        TypeRef type = parseType();
        expect(RPAREN, "')'");
        if (!isPrimitiveName(type.name()) && !CAST_FOLLOWERS.contains(kind())) {
            throw error("not a cast");
        }
        return type;
    }

    private static boolean isPrimitiveName(String name) {
        return switch (name) {
            case "int", "uint", "bit", "float", "double", "void", "addr", "handle", "fixed", "ufixed" -> true;
            default -> false;
        };
    }

    private Expr parsePostfix() {
        Expr expr = parsePrimary();
        while (true) {
            if (check(DOT)) {
                advance();
                if (check(LT)) {
                    // Explicit type arguments of a generic method: Main.<int>largest(xs)
                    advance();
                    List<TypeRef> typeArgs = new ArrayList<>();
                    do {
                        typeArgs.add(parseType());
                    } while (accept(COMMA));
                    expectGt();
                    Select callee = new Select(expr.pos(), expr, expectIdent("method name after the type arguments"));
                    if (!check(LPAREN)) {
                        throw error("type arguments <...> go only before a method call: x.<T>f(...)");
                    }
                    expr = new Call(expr.pos(), callee, parseArgs(), typeArgs);
                    continue;
                }
                expr = new Select(expr.pos(), expr, check(DELETE) ? advance().text() : expectIdent("member name after '.'"));
            } else if (check(PLUS_PLUS) || check(MINUS_MINUS)) {
                expr = new IncDec(expr.pos(), advance().kind() == PLUS_PLUS ? BinaryOp.ADD : BinaryOp.SUB, false, expr);
            } else if (check(LPAREN)) {
                expr = new Call(expr.pos(), expr, parseArgs());
            } else if (check(LBRACKET)) {
                advance();
                Expr index = parseExpr();
                if (accept(DOT_DOT)) {
                    Expr to = parseExpr();
                    expect(RBRACKET, "']'");
                    expr = new Slice(expr.pos(), expr, index, to);
                } else {
                    expect(RBRACKET, "']'");
                    expr = new Index(expr.pos(), expr, index);
                }
            } else {
                return expr;
            }
        }
    }

    private Expr parsePrimary() {
        Pos start = here();
        Token t = peek();
        switch (t.kind()) {
            case INT_LITERAL:
                advance();
                return new IntLit(start, t.text(), parseIntLiteral(t.text()));
            case FLOAT_LITERAL:
                advance();
                return new FloatLit(start, t.text(), Double.parseDouble(t.text().replace("_", "")));
            case STRING_LITERAL:
                advance();
                return new StringLit(start, decodeString(t.text()));
            case CHAR_LITERAL: {
                advance();
                // A character is just its code: an integer constant that adapts to context like `97`.
                String decoded = decodeString(t.text());
                return new IntLit(start, t.text(), BigInteger.valueOf(decoded.isEmpty() ? 0 : decoded.charAt(0)));
            }
            case LBRACKET: {
                advance();
                List<Expr> elements = new ArrayList<>();
                if (!check(RBRACKET)) {
                    do {
                        if (check(RBRACKET)) {
                            break; // trailing comma
                        }
                        elements.add(parseExpr());
                    } while (accept(COMMA));
                }
                expect(RBRACKET, "']'");
                return new ArrayLit(start, elements);
            }
            case TRUE:
                advance();
                return new BoolLit(start, true);
            case FALSE:
                advance();
                return new BoolLit(start, false);
            case NULL:
                advance();
                return new NullLit(start);
            case IDENTIFIER:
                advance();
                if (check(ARROW) && !inCaseLabel) {
                    advance();
                    return lambdaBody(start, List.of(new Param(start, null, t.text()))); // x -> ...
                }
                return new Name(start, t.text());
            case SUPER:
                advance();
                return new SuperRef(start);
            case THIS:
                advance();
                return new This(start);
            case LPAREN: {
                advance();
                Expr inner = parseExpr();
                expect(RPAREN, "')'");
                return inner;
            }
            case SWITCH:
                return parseSwitchExpr();
            case NEW: {
                advance();
                if (accept(LT)) {
                    TypeRef element = parseType();
                    expectGt();
                    expect(LPAREN, "'(' with the element count");
                    Expr count = parseExpr();
                    expect(RPAREN, "')'");
                    return new NewArray(start, element, count);
                }
                TypeRef type = parseType();
                if (!check(LPAREN) && type.arrayDims().size() == 1 && type.arrayDims().get(0) != null) {
                    // Java's new int[n]: reported, and read as new array<int>(n) to go on.
                    TypeRef element = new TypeRef(type.pos(), type.name(), type.typeArgs(), type.valueArgs(), List.of());
                    errors.add(new ParseError("arrays are made with new array<" + AstPrinter.print(element) + ">(n), not new "
                            + AstPrinter.print(element) + "[n]", start.line(), start.column()));
                    TypeRef array = new TypeRef(type.pos(), "array", List.of(element), null, List.of());
                    return new New(start, array, List.of(type.arrayDims().get(0)));
                }
                return new New(start, type, parseArgs());
            }
            case SIZEOF:
            case ALIGNOF: {
                advance();
                expect(LPAREN, "'(' after " + t.text());
                TypeRef type = parseType();
                expect(RPAREN, "')'");
                return t.kind() == SIZEOF ? new SizeOf(start, type) : new AlignOf(start, type);
            }
            default:
                if (t.kind() == EOF) {
                    throw error("expected an expression but reached the end of the file");
                }
                throw error("expected an expression, found '" + t.text() + "'");
        }
    }

    private List<Expr> parseArgs() {
        expect(LPAREN, "'('");
        List<Expr> args = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                if (check(RPAREN)) {
                    break; // trailing comma: f(a, b,)
                }
                args.add(parseExpr());
            } while (accept(COMMA));
        }
        expect(RPAREN, "')' after arguments");
        return args;
    }

    // ================================================================ literals

    private static BigInteger parseIntLiteral(String text) {
        String digits = text.replace("_", "");
        try {
            if (digits.startsWith("0x") || digits.startsWith("0X")) {
                return new BigInteger(digits.substring(2), 16);
            }
            if (digits.startsWith("0b") || digits.startsWith("0B")) {
                return new BigInteger(digits.substring(2), 2);
            }
            return new BigInteger(digits);
        } catch (NumberFormatException e) {
            return BigInteger.ZERO; // malformed; the lexer has already reported it
        }
    }

    /** Decodes the escapes in a string literal token (including its quotes). */
    private static String decodeString(String text) {
        if (text.startsWith("\"\"\"")) {
            return decodeEscapes(textBlock(text));
        }
        return decodeEscapes(text.substring(1, text.length() - 1));
    }

    /**
     * A text block's text, before its escapes, as Java makes it (§39): the lines after the
     * opening quotes' line, less the indentation they all share (the closing quotes' line
     * counts too), and less each line's trailing spaces. Closing quotes on a line of their own
     * end the text with a line break.
     */
    static String textBlock(String raw) {
        String inner = raw.substring(3, raw.length() - 3);
        int firstBreak = inner.indexOf('\n');
        String content = firstBreak < 0 ? "" : inner.substring(firstBreak + 1).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = content.split("\n", -1);
        int last = lines.length - 1;
        int indent = Integer.MAX_VALUE;
        for (int i = 0; i <= last; i++) {
            if (i < last && lines[i].isBlank()) {
                continue; // blank lines don't count, but the closing quotes' line does
            }
            int lead = 0;
            while (lead < lines[i].length() && (lines[i].charAt(lead) == ' ' || lines[i].charAt(lead) == '\t')) {
                lead++;
            }
            indent = Math.min(indent, lead);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i <= last; i++) {
            if (i == last && lines[i].isBlank()) {
                break; // the closing quotes on their own line: the text ends with the line break before them
            }
            String line = lines[i].isBlank() ? "" : lines[i].substring(Math.min(indent, lines[i].length()));
            out.append(line.stripTrailing());
            if (i < last) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String decodeEscapes(String body) {
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 >= body.length()) {
                sb.append(c);
                continue;
            }
            char e = body.charAt(++i);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '0' -> sb.append('\0');
                case '\\' -> sb.append('\\');
                case '"' -> sb.append('"');
                case '\'' -> sb.append('\'');
                case 's' -> sb.append(' ');
                case '\n' -> { } // in a text block, \ at the end of a line joins it to the next
                case 'u' -> {
                    if (i + 4 < body.length() && body.substring(i + 1, i + 5).chars().allMatch(
                            ch -> Character.digit(ch, 16) >= 0)) {
                        sb.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        sb.append("\\u"); // malformed; the lexer has already reported it
                    }
                }
                default -> sb.append('\\').append(e);
            }
        }
        return sb.toString();
    }

    // ================================================================ error recovery

    /** Skips to the end of the current member: past a `;` or a `{...}` body, or up to the class's `}`. */
    private void syncMember() {
        splitGt = false;
        while (!check(EOF)) {
            if (check(SEMICOLON)) {
                advance();
                return;
            }
            if (check(RBRACE)) {
                return;
            }
            if (check(LBRACE)) {
                skipBalancedBraces();
                return;
            }
            advance();
        }
    }

    /** Skips to the end of the current statement: past a `;`, or up to a `}`. */
    private void syncStatement() {
        splitGt = false;
        while (!check(EOF)) {
            if (check(SEMICOLON)) {
                advance();
                return;
            }
            if (check(RBRACE)) {
                return;
            }
            if (check(LBRACE)) {
                skipBalancedBraces();
                return;
            }
            advance();
        }
    }

    private void skipBalancedBraces() {
        int depth = 0;
        while (!check(EOF)) {
            TokenKind k = advance().kind();
            if (k == LBRACE) {
                depth++;
            } else if (k == RBRACE && --depth == 0) {
                return;
            }
        }
    }

    /**
     * Runs {@code attempt} without recording errors. Returns its result if it
     * succeeds; otherwise restores the position and returns null.
     */
    private <T> T speculate(Supplier<T> attempt) {
        int savedPos = pos;
        boolean savedSplit = splitGt;
        int savedDepth = valueListDepth;
        speculating++;
        try {
            return attempt.get();
        } catch (Bail b) {
            pos = savedPos;
            splitGt = savedSplit;
            valueListDepth = savedDepth;
            return null;
        } finally {
            speculating--;
        }
    }

    // ================================================================ token helpers

    private Token peek() {
        Token t = tokens.get(pos);
        if (splitGt) {
            // The second half of a `>>` whose first half was consumed.
            return new Token(GT, ">", t.line(), t.column() + 1);
        }
        return t;
    }

    private TokenKind kind() {
        return peek().kind();
    }

    private Pos here() {
        Token t = peek();
        return new Pos(t.line(), t.column());
    }

    private boolean check(TokenKind k) {
        return kind() == k;
    }

    private Token advance() {
        Token t = peek();
        splitGt = false;
        if (t.kind() != EOF) {
            pos++;
        }
        return t;
    }

    private boolean accept(TokenKind k) {
        if (check(k)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenKind k, String what) {
        if (check(k)) {
            return advance();
        }
        throw error("expected " + what + ", found " + describe(peek()));
    }

    /** Consumes a single `>`, splitting a `>>` token if necessary. */
    private void expectGt() {
        if (check(GT)) {
            advance();
        } else if (check(DOUBLE_GT)) {
            splitGt = true;
        } else {
            throw error("expected '>', found " + describe(peek()));
        }
    }

    private String expectIdent(String what) {
        return expect(IDENTIFIER, what).text();
    }

    private static String describe(Token t) {
        return t.kind() == EOF ? "end of file" : "'" + t.text() + "'";
    }

    private Bail error(String message) {
        if (speculating == 0) {
            Token t = peek();
            errors.add(new ParseError(message, t.line(), t.column()));
        }
        return new Bail();
    }
}
