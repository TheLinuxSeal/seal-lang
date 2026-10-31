package io.github.thelinuxseal.seal.runtime;

/**
 * Thrown at a step of a program that counts them (a host's script, docs/EMBED.md §6) once it
 * passes the limits its host set, or its host asked it to stop. It is thrown again at every step
 * after, so a script that catches it can't go on.
 */
public final class Stopped extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Stopped(String message) {
        super(message);
    }
}
