package io.github.thelinuxseal.seal.check;

import java.util.Arrays;
import java.util.Optional;

/** The warnings a compile can turn on, each with its own flag: `-WautoCast` (§56.1). */
public enum Warning {
    /** Every number converted to another type without a cast: int to double, i += 2.5 (§41.6). */
    AUTO_CAST("-WautoCast"),
    /** Only those that may lose data: narrowing back in i += 2.5, or int to float, which may round. */
    AUTO_CAST_LOSSY("-WautoCastLossy"),
    /** Not a warning: turns on `assert` statements, as Java's -ea does (§41.4). */
    ASSERT("-Wassert");

    private final String flag;

    Warning(String flag) {
        this.flag = flag;
    }

    /** The command-line flag that turns it on. */
    public String flag() {
        return flag;
    }

    public static Optional<Warning> byFlag(String flag) {
        return Arrays.stream(values()).filter(w -> w.flag().equals(flag)).findFirst();
    }
}
