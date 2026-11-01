package io.github.thelinuxseal.seal.check;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** The warnings a compile can turn on, each with its own flag: `-WautoCast` (§56.1). */
public enum Warning {
    /** Every number converted to another type without a cast: int to double, i += 2.5 (§41.6). */
    AUTO_CAST("-WautoCast"),
    /** Only those that may lose data: narrowing back in i += 2.5, or int to float, which may round. */
    AUTO_CAST_LOSSY("-WautoCastLossy"),
    /** Not a warning: turns on `assert` statements, as Java's -ea does (§41.4). */
    ASSERT("-Wassert"),
    /** A style: every new List<>(), for code that leaves type arguments out as new List() (§7). */
    DIAMOND("-Wdiamond", false),
    /** A style: every new List() of a generic class, for code that writes new List<>() (§7). */
    MISSING_DIAMOND("-WmissingDiamond", false);

    private final String flag;
    private final boolean inAll;

    Warning(String flag) {
        this(flag, true);
    }

    Warning(String flag, boolean inAll) {
        this.flag = flag;
        this.inAll = inAll;
    }

    /** What -Wall turns on: every kind but the styles, which are opposites (§56.1). */
    public static Set<Warning> all() {
        Set<Warning> all = EnumSet.noneOf(Warning.class);
        Arrays.stream(values()).filter(w -> w.inAll).forEach(all::add);
        return all;
    }

    /** The command-line flag that turns it on. */
    public String flag() {
        return flag;
    }

    public static Optional<Warning> byFlag(String flag) {
        return Arrays.stream(values()).filter(w -> w.flag().equals(flag)).findFirst();
    }
}
