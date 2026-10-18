package io.github.thelinuxseal.seal.parser;

public record ParseError(String message, int line, int column) {

    @Override
    public String toString() {
        return line + ":" + column + ": " + message;
    }
}
