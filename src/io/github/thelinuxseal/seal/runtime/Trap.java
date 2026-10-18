package io.github.thelinuxseal.seal.runtime;

/** A fault that stops a program at once, as the VM's traps do (docs/JVM.md §7); Seal's catch doesn't see it. */
public final class Trap extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Trap(String message) {
        super(message);
    }
}
