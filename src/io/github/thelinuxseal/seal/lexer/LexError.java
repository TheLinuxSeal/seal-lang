package io.github.thelinuxseal.seal.lexer;

public record LexError(String message, int line, int column) {

    @Override
    public String toString() {
        return line + ":" + column + ": " + message;
    }
}
