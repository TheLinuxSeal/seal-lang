package io.github.thelinuxseal.seal.lexer;

/**
 * A lexical token.
 *
 * @param kind   the token kind
 * @param text   the exact source text of the token
 * @param line   1-based line of the first character
 * @param column 1-based column of the first character
 */
public record Token(TokenKind kind, String text, int line, int column) {

    @Override
    public String toString() {
        return kind + "('" + text + "')@" + line + ":" + column;
    }
}
