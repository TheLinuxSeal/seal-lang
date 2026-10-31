package io.github.thelinuxseal.seal.embed;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Java classes a host lets its scripts use (docs/EMBED.md §5), by binary name: those it names
 * ("java.util.ArrayList", with the classes nested in it; "java.util.Map.Entry", only that one),
 * those of the packages it names ("java.util.*", not java.util.concurrent's), or every one ("*");
 * and always those Seal names in seal.java and those its loops and try use.
 */
final class JavaAllowlist implements Predicate<String> {

    /**
     * seal.java's classes (JavaObject, JavaString, the boxes), and what a for-each over a Java
     * collection and a try closing a Java resource call: no script needs leave to use them.
     */
    static final Set<String> ALWAYS = Set.of("java.lang.Object", "java.lang.String", "java.lang.Integer", "java.lang.Long",
            "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean", "java.lang.Float",
            "java.lang.Double", "java.lang.Iterable", "java.util.Iterator", "java.lang.AutoCloseable");

    private final boolean all;
    private final Set<String> classes;
    private final Set<String> packages;

    private JavaAllowlist(boolean all, Set<String> classes, Set<String> packages) {
        this.all = all;
        this.classes = Set.copyOf(classes);
        this.packages = Set.copyOf(packages);
    }

    /** The classes {@link #ALWAYS} allowed, and those the patterns name. */
    static JavaAllowlist of(Collection<String> patterns) {
        return new JavaAllowlist(false, ALWAYS, Set.of()).with(patterns);
    }

    /** These, and those the patterns name. */
    JavaAllowlist with(Collection<String> patterns) {
        boolean everything = all;
        Set<String> cs = new HashSet<>(classes);
        Set<String> ps = new HashSet<>(packages);
        for (String p : patterns) {
            if (p.equals("*")) {
                everything = true;
            } else if (p.endsWith(".*")) {
                ps.add(p.substring(0, p.length() - 2));
            } else if (p.isEmpty() || p.contains("*")) {
                throw new IllegalArgumentException("can't allow Java's \"" + p + "\": allow a class (java.util.ArrayList),"
                        + " a package's classes (java.util.*), or every class (*)");
            } else {
                cs.add(p.replace('$', '.'));
            }
        }
        return new JavaAllowlist(everything, cs, ps);
    }

    @Override
    public boolean test(String binary) {
        if (all) {
            return true;
        }
        int dollar = binary.indexOf('$');
        String top = dollar < 0 ? binary : binary.substring(0, dollar);
        int dot = top.lastIndexOf('.');
        return classes.contains(binary.replace('$', '.')) || classes.contains(top)
                || packages.contains(dot < 0 ? "" : top.substring(0, dot));
    }
}
