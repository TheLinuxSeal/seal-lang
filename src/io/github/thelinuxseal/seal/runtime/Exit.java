package io.github.thelinuxseal.seal.runtime;

/** Thrown by System.exit to end the program with a code; a program's entry point catches it. */
public final class Exit extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public final int code;

    public Exit(int code) {
        super("exit " + code, null, false, false);
        this.code = code;
    }
}
