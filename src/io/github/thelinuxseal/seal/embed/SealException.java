package io.github.thelinuxseal.seal.embed;

import io.github.thelinuxseal.seal.jvm.JvmRunner;
import io.github.thelinuxseal.seal.runtime.Stopped;

/**
 * A fault in a script that nothing in it caught: a Seal exception, a trap (a null handle,
 * division by zero), or what Java's code that it called threw. Its message is what `seal run`
 * prints for it, with the Seal lines it happened at; its cause is the fault.
 */
public final class SealException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SealException(Throwable fault) {
        super(JvmRunner.describe(fault), fault);
    }

    /**
     * Whether the script was stopped (docs/EMBED.md §6): past the limits its host set, or by the
     * host's {@link SealScript#stop}, rather than by a fault of its own.
     */
    public boolean stopped() {
        return getCause() instanceof Stopped;
    }
}
