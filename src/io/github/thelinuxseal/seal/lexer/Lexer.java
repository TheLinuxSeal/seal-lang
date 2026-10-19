package io.github.thelinuxseal.seal.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts source text into tokens.
 *
 * <p>Operators are matched longest-first. For angle brackets that means
 * `<<<`, `<<=`, `<<`, `<=`, `<` and `>>`, `>=`, `>`. `<<` only ever opens a
 * value list. `>>` is ambiguous between closing a value list and closing two
 * type-argument lists (`Array<addr<int>>`); the parser resolves it by
 * splitting `>>` into two `>` when it needs one.
 *
 * <p>Lexing never stops at an error. Invalid input produces an
 * {@link TokenKind#ERROR} token and a {@link LexError}, and lexing resumes.
 */
public final class Lexer {

    private static final Map<String, TokenKind> KEYWORDS = Map.ofEntries(
            Map.entry("class", TokenKind.CLASS),
            Map.entry("struct", TokenKind.STRUCT),
            Map.entry("unique", TokenKind.UNIQUE),
            Map.entry("interface", TokenKind.INTERFACE),
            Map.entry("enum", TokenKind.ENUM),
            Map.entry("package", TokenKind.PACKAGE),
            Map.entry("import", TokenKind.IMPORT),
            Map.entry("assert", TokenKind.ASSERT),
            Map.entry("switch", TokenKind.SWITCH),
            Map.entry("throw", TokenKind.THROW),
            Map.entry("try", TokenKind.TRY),
            Map.entry("catch", TokenKind.CATCH),
            Map.entry("finally", TokenKind.FINALLY),
            Map.entry("case", TokenKind.CASE),
            Map.entry("default", TokenKind.DEFAULT),
            Map.entry("abstract", TokenKind.ABSTRACT),
            Map.entry("extends", TokenKind.EXTENDS),
            Map.entry("implements", TokenKind.IMPLEMENTS),
            Map.entry("instanceof", TokenKind.INSTANCEOF),
            Map.entry("this", TokenKind.THIS),
            Map.entry("public", TokenKind.PUBLIC),
            Map.entry("private", TokenKind.PRIVATE),
            Map.entry("static", TokenKind.STATIC),
            Map.entry("const", TokenKind.CONST),
            Map.entry("pure", TokenKind.PURE),
            Map.entry("int", TokenKind.INT),
            Map.entry("char", TokenKind.CHAR),
            Map.entry("uint", TokenKind.UINT),
            Map.entry("bit", TokenKind.BIT),
            Map.entry("float", TokenKind.FLOAT),
            Map.entry("double", TokenKind.DOUBLE),
            Map.entry("void", TokenKind.VOID),
            Map.entry("addr", TokenKind.ADDR),
            Map.entry("handle", TokenKind.HANDLE),
            Map.entry("fixed", TokenKind.FIXED),
            Map.entry("ufixed", TokenKind.UFIXED),
            Map.entry("new", TokenKind.NEW),
            Map.entry("delete", TokenKind.DELETE),
            Map.entry("return", TokenKind.RETURN),
            Map.entry("if", TokenKind.IF),
            Map.entry("else", TokenKind.ELSE),
            Map.entry("while", TokenKind.WHILE),
            Map.entry("for", TokenKind.FOR),
            Map.entry("break", TokenKind.BREAK),
            Map.entry("continue", TokenKind.CONTINUE),
            Map.entry("sizeof", TokenKind.SIZEOF),
            Map.entry("alignof", TokenKind.ALIGNOF),
            Map.entry("super", TokenKind.SUPER),
            Map.entry("null", TokenKind.NULL),
            Map.entry("true", TokenKind.TRUE),
            Map.entry("false", TokenKind.FALSE));

    private final String src;
    private final List<Token> tokens = new ArrayList<>();
    private final List<LexError> errors = new ArrayList<>();

    private int pos = 0;
    private int line = 1;
    private int column = 1;

    // Start of the token currently being lexed.
    private int startPos;
    private int startLine;
    private int startColumn;
    private int errorsAtTokenStart;

    public Lexer(String src) {
        this.src = src;
    }

    public static Result lex(String src) {
        return new Lexer(src).run();
    }

    public record Result(List<Token> tokens, List<LexError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public Result run() {
        while (true) {
            skipTrivia();
            begin();
            if (atEnd()) {
                emit(TokenKind.EOF);
                break;
            }
            lexToken();
        }
        return new Result(List.copyOf(tokens), List.copyOf(errors));
    }

    // ---------------------------------------------------------------- tokens

    private void lexToken() {
        char c = advance();
        switch (c) {
            case '(' -> emit(TokenKind.LPAREN);
            case ')' -> emit(TokenKind.RPAREN);
            case '{' -> emit(TokenKind.LBRACE);
            case '}' -> emit(TokenKind.RBRACE);
            case '[' -> emit(TokenKind.LBRACKET);
            case ']' -> emit(TokenKind.RBRACKET);
            case ';' -> emit(TokenKind.SEMICOLON);
            case ':' -> emit(TokenKind.COLON);
            case '?' -> emit(TokenKind.QUESTION);
            case ',' -> emit(TokenKind.COMMA);
            case '@' -> emit(TokenKind.AT);
            case '~' -> emit(TokenKind.TILDE);
            case '.' -> emit(match('.') ? TokenKind.DOT_DOT : TokenKind.DOT);
            case '+' -> emit(match('=') ? TokenKind.PLUS_ASSIGN : match('+') ? TokenKind.PLUS_PLUS : TokenKind.PLUS);
            case '-' -> emit(match('=') ? TokenKind.MINUS_ASSIGN : match('>') ? TokenKind.ARROW
                    : match('-') ? TokenKind.MINUS_MINUS : TokenKind.MINUS);
            case '*' -> emit(match('*') ? (match('=') ? TokenKind.STAR_STAR_ASSIGN : TokenKind.STAR_STAR)
                    : match('=') ? TokenKind.STAR_ASSIGN : TokenKind.STAR);
            case '/' -> emit(match('=') ? TokenKind.SLASH_ASSIGN : TokenKind.SLASH);
            case '%' -> emit(match('=') ? TokenKind.PERCENT_ASSIGN : TokenKind.PERCENT);
            case '^' -> emit(match('=') ? TokenKind.CARET_ASSIGN : TokenKind.CARET);
            case '=' -> emit(match('=') ? TokenKind.EQ : TokenKind.ASSIGN);
            case '!' -> emit(match('=') ? TokenKind.NEQ : TokenKind.BANG);
            case '&' -> emit(match('&') ? TokenKind.AMP_AMP
                    : match('=') ? TokenKind.AMP_ASSIGN : TokenKind.AMP);
            case '|' -> emit(match('|') ? TokenKind.PIPE_PIPE
                    : match('=') ? TokenKind.PIPE_ASSIGN : TokenKind.PIPE);
            case '>' -> emit(match('>') ? TokenKind.DOUBLE_GT
                    : match('=') ? TokenKind.GE : TokenKind.GT);
            case '<' -> lexLess();
            case '"' -> {
                if (peek() == '"' && peek(1) == '"') {
                    lexTextBlock();
                } else {
                    lexString();
                }
            }
            case '\'' -> lexChar();
            default -> {
                if (isDigit(c)) {
                    lexNumber(c);
                } else if (isIdentStart(c)) {
                    lexIdentifier();
                } else {
                    error("unexpected character '" + printable(c) + "'");
                    emit(TokenKind.ERROR);
                }
            }
        }
    }

    private void lexLess() {
        if (peek() == '<' && peek(1) == '<') {
            advance();
            advance();
            emit(TokenKind.SHIFT);
        } else if (peek() == '<' && peek(1) == '=') {
            advance();
            advance();
            emit(TokenKind.SHIFT_ASSIGN);
        } else if (match('<')) {
            emit(TokenKind.DOUBLE_LT);
        } else if (match('=')) {
            emit(TokenKind.LE);
        } else {
            emit(TokenKind.LT);
        }
    }

    private void lexIdentifier() {
        while (isIdentPart(peek())) {
            advance();
        }
        String text = src.substring(startPos, pos);
        emit(KEYWORDS.getOrDefault(text, TokenKind.IDENTIFIER));
    }

    private void lexNumber(char first) {
        if (first == '0' && (peek() == 'x' || peek() == 'X')) {
            advance();
            lexDigits(16, "hexadecimal");
            finishInteger();
            return;
        }
        if (first == '0' && (peek() == 'b' || peek() == 'B')) {
            advance();
            lexDigits(2, "binary");
            finishInteger();
            return;
        }

        consumeDigitRun(10);
        boolean isFloat = false;

        // `4..8` is a range, not a float, so require a digit after the dot.
        if (peek() == '.' && isDigit(peek(1))) {
            isFloat = true;
            advance();
            consumeDigitRun(10);
        }
        if (peek() == 'e' || peek() == 'E') {
            int save = pos;
            int saveLine = line;
            int saveColumn = column;
            advance();
            if (peek() == '+' || peek() == '-') {
                advance();
            }
            if (isDigit(peek())) {
                isFloat = true;
                consumeDigitRun(10);
            } else {
                // Not an exponent after all; leave `e` for the next token.
                pos = save;
                line = saveLine;
                column = saveColumn;
            }
        }

        if (isFloat) {
            rejectTrailingIdentChars();
            emit(TokenKind.FLOAT_LITERAL);
        } else {
            finishInteger();
        }
    }

    private void lexDigits(int radix, String name) {
        if (digitValue(peek()) < 0 || digitValue(peek()) >= radix) {
            error("expected " + name + " digits");
            return;
        }
        consumeDigitRun(radix);
    }

    /** Consumes digits of the given radix, allowing `_` separators between digits. */
    private void consumeDigitRun(int radix) {
        while (true) {
            char c = peek();
            if (c == '_') {
                int next = digitValue(peek(1));
                if (next < 0 || next >= radix) {
                    advance();
                    error("'_' must be followed by a digit");
                    return;
                }
                advance();
            } else {
                int v = digitValue(c);
                if (v < 0 || v >= radix) {
                    return;
                }
                advance();
            }
        }
    }

    private void finishInteger() {
        rejectTrailingIdentChars();
        emit(TokenKind.INT_LITERAL);
    }

    /** Catches things like `12abc` or `0b102` as one bad literal instead of several tokens. */
    private void rejectTrailingIdentChars() {
        if (isIdentPart(peek())) {
            while (isIdentPart(peek())) {
                advance();
            }
            // Don't pile a second error onto a literal that already has one.
            if (errors.size() == errorsAtTokenStart) {
                error("invalid numeric literal '" + src.substring(startPos, pos) + "'");
            }
        }
    }

    private void lexString() {
        while (true) {
            if (atEnd() || peek() == '\n') {
                error("unterminated string literal");
                emit(TokenKind.ERROR);
                return;
            }
            char c = advance();
            if (c == '"') {
                emit(TokenKind.STRING_LITERAL);
                return;
            }
            if (c == '\\') {
                lexEscape();
            }
        }
    }

    /**
     * A text block (§39): three quotes and a line break, then the text up to the closing three
     * quotes, over any number of lines. The parser takes off the indentation, as Java does.
     */
    private void lexTextBlock() {
        advance();
        advance(); // the other two opening quotes
        while (!atEnd() && (peek() == ' ' || peek() == '\t' || peek() == '\r')) {
            advance();
        }
        if (atEnd() || peek() != '\n') {
            error("a text block's text starts on the line after its opening \"\"\"");
        }
        while (true) {
            if (atEnd()) {
                error("unterminated text block: it ends with \"\"\"");
                emit(TokenKind.ERROR);
                return;
            }
            char c = advance();
            if (c == '"' && peek() == '"' && peek(1) == '"') {
                advance();
                advance();
                emit(TokenKind.STRING_LITERAL);
                return;
            }
            if (c == '\\') {
                if (peek() == '\n') {
                    advance(); // \ at the end of a line joins it to the next
                } else {
                    lexEscape();
                }
            }
        }
    }

    /** A character literal: exactly one character or escape between single quotes. */
    private void lexChar() {
        int chars = 0;
        while (true) {
            if (atEnd() || peek() == '\n') {
                error("unterminated character literal");
                emit(TokenKind.ERROR);
                return;
            }
            char c = advance();
            if (c == '\'') {
                if (chars != 1) {
                    error(chars == 0 ? "empty character literal"
                            : "a character literal holds one character; use \"...\" for a string");
                }
                emit(TokenKind.CHAR_LITERAL); // still a literal, so the parser doesn't report it again
                return;
            }
            if (c == '\\') {
                lexEscape();
            }
            chars++;
        }
    }

    private void lexEscape() {
        int escLine = line;
        int escColumn = column - 1;
        if (atEnd()) {
            return; // reported as unterminated by lexString
        }
        char c = advance();
        switch (c) {
            case 'n', 't', 'r', '0', '\\', '"', '\'', 's' -> { }
            case 'u' -> {
                for (int i = 0; i < 4; i++) {
                    if (digitValue(peek()) < 0 || digitValue(peek()) >= 16) {
                        errors.add(new LexError("\\u escape requires 4 hex digits", escLine, escColumn));
                        return;
                    }
                    advance();
                }
            }
            default -> errors.add(new LexError(
                    "unknown escape sequence '\\" + printable(c) + "'", escLine, escColumn));
        }
    }

    // ---------------------------------------------------------------- trivia

    private void skipTrivia() {
        while (!atEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                while (!atEnd() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
                begin();
                advance();
                advance();
                while (!(peek() == '*' && peek(1) == '/')) {
                    if (atEnd()) {
                        error("unterminated block comment");
                        return;
                    }
                    advance();
                }
                advance();
                advance();
            } else {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void begin() {
        startPos = pos;
        startLine = line;
        startColumn = column;
        errorsAtTokenStart = errors.size();
    }

    private void emit(TokenKind kind) {
        tokens.add(new Token(kind, src.substring(startPos, pos), startLine, startColumn));
    }

    private void error(String message) {
        errors.add(new LexError(message, startLine, startColumn));
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private char peek() {
        return peek(0);
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private char advance() {
        char c = src.charAt(pos++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    private boolean match(char expected) {
        if (peek() != expected || atEnd()) {
            return false;
        }
        advance();
        return true;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    private static int digitValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String printable(char c) {
        return c < 0x20 || c == 0x7f ? String.format("\\x%02x", (int) c) : String.valueOf(c);
    }
}
