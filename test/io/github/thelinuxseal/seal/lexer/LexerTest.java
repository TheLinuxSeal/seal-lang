package io.github.thelinuxseal.seal.lexer;

import static io.github.thelinuxseal.seal.lexer.TokenKind.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Dependency-free test runner: `java -cp out io.github.thelinuxseal.seal.lexer.LexerTest`. */
public final class LexerTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        // Generic brackets.
        kinds("Foo<<N>>", IDENTIFIER, DOUBLE_LT, IDENTIFIER, DOUBLE_GT);
        kinds("Array<addr<int>>", IDENTIFIER, LT, ADDR, LT, INT, DOUBLE_GT);
        kinds("Array<int><<64, 2>>",
                IDENTIFIER, LT, INT, GT, DOUBLE_LT, INT_LITERAL, COMMA, INT_LITERAL, DOUBLE_GT);
        kinds("Array<addr<int>> <<8>> x;",
                IDENTIFIER, LT, ADDR, LT, INT, DOUBLE_GT, DOUBLE_LT, INT_LITERAL, DOUBLE_GT, IDENTIFIER, SEMICOLON);
        kinds("Array<addr<int>><<8>>", IDENTIFIER, LT, ADDR, LT, INT, DOUBLE_GT, DOUBLE_LT, INT_LITERAL, DOUBLE_GT);
        kinds("Array<Foo<<3>>>", IDENTIFIER, LT, IDENTIFIER, DOUBLE_LT, INT_LITERAL, DOUBLE_GT, GT);
        kinds("struct Foo<<>>", STRUCT, IDENTIFIER, DOUBLE_LT, DOUBLE_GT);
        kinds("Foo<<-1>>", IDENTIFIER, DOUBLE_LT, MINUS, INT_LITERAL, DOUBLE_GT);
        kinds("Foo<<N>>= x", IDENTIFIER, DOUBLE_LT, IDENTIFIER, DOUBLE_GT, ASSIGN, IDENTIFIER);
        kinds("a < < b", IDENTIFIER, LT, LT, IDENTIFIER);

        // Shift operators.
        kinds("x <<< 3", IDENTIFIER, SHIFT, INT_LITERAL);
        kinds("x <<< -3", IDENTIFIER, SHIFT, MINUS, INT_LITERAL);
        kinds("x <<= n;", IDENTIFIER, SHIFT_ASSIGN, IDENTIFIER, SEMICOLON);
        kinds("2 ** 16 - 1", INT_LITERAL, STAR_STAR, INT_LITERAL, MINUS, INT_LITERAL);
        kinds("x **= 3; a*b", IDENTIFIER, STAR_STAR_ASSIGN, INT_LITERAL, SEMICOLON, IDENTIFIER, STAR, IDENTIFIER);
        kinds("x *= 2 ** -1", IDENTIFIER, STAR_ASSIGN, INT_LITERAL, STAR_STAR, MINUS, INT_LITERAL);
        kinds("a<=b>=c", IDENTIFIER, LE, IDENTIFIER, GE, IDENTIFIER);

        // Increment and decrement: the longest token wins, as in Java.
        kinds("i++; --j", IDENTIFIER, PLUS_PLUS, SEMICOLON, MINUS_MINUS, IDENTIFIER);
        kinds("a+++b", IDENTIFIER, PLUS_PLUS, PLUS, IDENTIFIER);
        kinds("a - -b", IDENTIFIER, MINUS, MINUS, IDENTIFIER);
        kinds("n-->0", IDENTIFIER, MINUS_MINUS, GT, INT_LITERAL);
        kinds("x -= 1 -> y", IDENTIFIER, MINUS_ASSIGN, INT_LITERAL, ARROW, IDENTIFIER);

        // Address operators and `&&`.
        kinds("addr<int> p = *x;", ADDR, LT, INT, GT, IDENTIFIER, ASSIGN, STAR, IDENTIFIER, SEMICOLON);
        kinds("&p = 32;", AMP, IDENTIFIER, ASSIGN, INT_LITERAL, SEMICOLON);
        kinds("&(&pp)", AMP, LPAREN, AMP, IDENTIFIER, RPAREN);
        kinds("&&pp", AMP_AMP, IDENTIFIER);
        kinds("a&&&p", IDENTIFIER, AMP_AMP, AMP, IDENTIFIER);
        kinds("a & &p", IDENTIFIER, AMP, AMP, IDENTIFIER);
        kinds("a &= b || c |= d", IDENTIFIER, AMP_ASSIGN, IDENTIFIER, PIPE_PIPE, IDENTIFIER, PIPE_ASSIGN, IDENTIFIER);

        // Arrays, slices, member access.
        kinds("T[N] data<<int N>>;",
                IDENTIFIER, LBRACKET, IDENTIFIER, RBRACKET, IDENTIFIER, DOUBLE_LT, INT, IDENTIFIER, DOUBLE_GT, SEMICOLON);
        kinds("x[4..8]", IDENTIFIER, LBRACKET, INT_LITERAL, DOT_DOT, INT_LITERAL, RBRACKET);
        kinds("(&p).field", LPAREN, AMP, IDENTIFIER, RPAREN, DOT, IDENTIFIER);

        // Decorators.
        kinds("@native(\"<<<\")", AT, IDENTIFIER, LPAREN, STRING_LITERAL, RPAREN);
        kinds("@overload(Operator.SHIFT)", AT, IDENTIFIER, LPAREN, IDENTIFIER, DOT, IDENTIFIER, RPAREN);

        // Keywords vs identifiers.
        kinds("class Dog extends<<3>>(Pet)",
                CLASS, IDENTIFIER, EXTENDS, DOUBLE_LT, INT_LITERAL, DOUBLE_GT, LPAREN, IDENTIFIER, RPAREN);
        kinds("struct unique class dynamic", STRUCT, UNIQUE, CLASS, IDENTIFIER);
        kinds("string handle<Foo> Object", IDENTIFIER, HANDLE, LT, IDENTIFIER, GT, IDENTIFIER);
        kinds("classy newer _x x1", IDENTIFIER, IDENTIFIER, IDENTIFIER, IDENTIFIER);
        kinds("true false static", TRUE, FALSE, STATIC);
        kinds("fixed<<8, 8>> ufixed", FIXED, DOUBLE_LT, INT_LITERAL, COMMA, INT_LITERAL, DOUBLE_GT, UFIXED);

        // Numbers.
        kinds("0 42 1_000_000 0xFF 0b1010_0101", INT_LITERAL, INT_LITERAL, INT_LITERAL, INT_LITERAL, INT_LITERAL);
        kinds("1.5 2e10 3.0e-2 4E+1", FLOAT_LITERAL, FLOAT_LITERAL, FLOAT_LITERAL, FLOAT_LITERAL);
        kinds("1.x", INT_LITERAL, DOT, IDENTIFIER);
        texts("0xDEAD_beef 1_0.2_5", "0xDEAD_beef", "1_0.2_5");
        errors("12abc", "invalid numeric literal '12abc'");
        errors("0b102", "invalid numeric literal '0b102'");
        errors("0x", "expected hexadecimal digits");
        errors("1__0", "'_' must be followed by a digit");

        // Strings.
        kinds("\"hi\" \"a\\\"b\" \"\\u0041\\n\"", STRING_LITERAL, STRING_LITERAL, STRING_LITERAL);
        errors("\"abc", "unterminated string literal");
        errors("\"abc\nx\"", "unterminated string literal", "unterminated string literal");

        // Character literals
        kinds("'a' '\\n' '\\'' '\"' '\\u00e9'", CHAR_LITERAL, CHAR_LITERAL, CHAR_LITERAL, CHAR_LITERAL, CHAR_LITERAL);
        kinds("c - 'a'", IDENTIFIER, MINUS, CHAR_LITERAL);
        errors("''", "empty character literal");
        errors("'ab'", "a character literal holds one character; use \"...\" for a string");
        errors("'a", "unterminated character literal");
        errors("'\\q'", "unknown escape sequence '\\q'");
        errors("\"it\\'s\"");
        errors("\"\\q\"", "unknown escape sequence '\\q'");
        errors("\"\\u12\"", "\\u escape requires 4 hex digits");
        errors("\"a\\sb\"");

        // Text blocks.
        kinds("x = \"\"\"\n  one\n  \"two\"\n  \"\"\";", IDENTIFIER, ASSIGN, STRING_LITERAL, SEMICOLON);
        positions("\"\"\"\n a\n \"\"\" b", "1:1", "3:6", "3:7");
        kinds("\"\"\"\n a \\\"\"\" b \\\n c\"\"\"", STRING_LITERAL);
        errors("\"\"\" same line \"\"\"", "a text block's text starts on the line after its opening \"\"\"");
        errors("\"\"\"\n never closed", "unterminated text block: it ends with \"\"\"");

        // Comments.
        kinds("a // comment <<<\nb", IDENTIFIER, IDENTIFIER);
        kinds("a /* multi\nline */ b", IDENTIFIER, IDENTIFIER);
        errors("a /* never closed", "unterminated block comment");

        // Bad characters don't stop lexing.
        kinds("a # b", IDENTIFIER, ERROR, IDENTIFIER);
        errors("a # b $", "unexpected character '#'", "unexpected character '$'");

        // Positions.
        positions("a\n  bb\n\tc", "1:1", "2:3", "3:2", "3:3");
        positions("/* x\n */ y", "2:5", "2:6");

        // A whole class from the spec.
        String array = """
                struct Array<T><<int N>>
                struct Array<T><<int N, int K>>
                {
                    int occupied;
                    T[N] data<<int N>>;
                    public T get<<int N>>(int x) {
                        return data[x];
                    }
                }
                """;
        Lexer.Result r = Lexer.lex(array);
        check(r.ok(), "spec example lexes cleanly: " + r.errors());
        check(r.tokens().size() == 56, "spec example token count: " + r.tokens().size());

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Asserts the token kinds, excluding the trailing EOF, and that there were no errors. */
    private static void kinds(String src, TokenKind... expected) {
        Lexer.Result r = Lexer.lex(src);
        List<TokenKind> actual = withoutEof(r.tokens()).stream().map(Token::kind).toList();
        boolean expectErrors = actual.contains(ERROR);
        check(actual.equals(List.of(expected)) && (expectErrors || r.ok()),
                src + "\n    expected " + List.of(expected) + "\n    got      " + actual
                        + (r.ok() ? "" : "\n    errors   " + r.errors()));
    }

    private static void texts(String src, String... expected) {
        List<String> actual = withoutEof(Lexer.lex(src).tokens()).stream().map(Token::text).toList();
        check(actual.equals(List.of(expected)), src + "\n    expected " + List.of(expected) + "\n    got      " + actual);
    }

    private static void errors(String src, String... expected) {
        List<String> actual = Lexer.lex(src).errors().stream().map(LexError::message).toList();
        check(actual.equals(List.of(expected)), src + " errors\n    expected " + List.of(expected) + "\n    got      " + actual);
    }

    private static void positions(String src, String... expected) {
        List<String> actual = Lexer.lex(src).tokens().stream()
                .map(t -> t.line() + ":" + t.column())
                .collect(Collectors.toList());
        check(actual.equals(List.of(expected)), src + " positions\n    expected " + List.of(expected) + "\n    got      " + actual);
    }

    private static List<Token> withoutEof(List<Token> tokens) {
        return tokens.subList(0, tokens.size() - 1);
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
