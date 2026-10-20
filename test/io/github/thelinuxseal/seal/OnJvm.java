package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;
import io.github.thelinuxseal.seal.vm.Trap;

/**
 * The JVM backend beside the VM in the suites that run programs (docs/JVM.md §12): each program
 * a suite runs on the VM runs on the JVM too. One the JVM backend can compile must print the
 * same and end the same way: with the same exit code, the same uncaught exception, or a fault
 * where the VM traps. One it can't compile yet must say so, at a line, and is counted.
 */
@SuppressWarnings("deprecation") // runs programs on the deprecated VM too, while it is kept
final class OnJvm {

    private int same;
    private int notYet;
    private int checked;
    private final List<String> differences = new ArrayList<>();

    /**
     * Runs on the JVM what ran on the VM, and compares. vmTrap is what stopped the VM's run, or
     * null if it ended with vmExit.
     */
    void compare(String name, Checker checker, String main, InputStream in, boolean files, String vmOutput,
                 long vmExit, Trap vmTrap) {
        if (vmTrap != null && vmTrap.getMessage().contains("step limit")) {
            return; // stopped by the VM's step limit, which the JVM has no counterpart to
        }
        JvmGen.Result gen;
        try {
            gen = JvmGen.generate(checker, main);
        } catch (RuntimeException e) {
            differences.add(name + "\n    the JVM backend crashed: " + stack(e));
            return;
        }
        if (!gen.errors().isEmpty()) {
            boolean later = gen.errors().stream().allMatch(d -> d.message().contains("not available on the JVM")
                    && d.line() >= 1);
            if (later) {
                notYet++;
                if (Boolean.getBoolean("seal.jvm.verbose")) {
                    System.out.println("NOT YET: " + name + ": " + gen.errors().get(0).message());
                }
            } else {
                differences.add(name + "\n    the JVM backend reports " + gen.errors());
            }
            return;
        }
        List<String> problems = JvmRunner.verify(gen);
        if (!problems.isEmpty()) {
            differences.add(name + "\n    the classes fail verification: " + problems);
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JvmRunner.Outcome[] o = new JvmRunner.Outcome[1];
        Thread t = new Thread(() -> o[0] = JvmRunner.run(gen, in, new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), files));
        t.setDaemon(true);
        t.start();
        try {
            t.join(20_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (o[0] == null) {
            differences.add(name + "\n    on the JVM it didn't end within 20 seconds");
            return;
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        String vmEnd = vmTrap == null ? "exit " + (int) vmExit
                : vmTrap.uncaught ? vmTrap.getMessage() : "trap";
        String jvmEnd = o[0].fault() == null ? "exit " + o[0].code()
                : firstLine(JvmRunner.describe(o[0].fault())).startsWith("uncaught ") ? firstLine(JvmRunner.describe(o[0].fault()))
                : "trap";
        if (output.equals(vmOutput) && vmEnd.equals(jvmEnd)) {
            same++;
        } else if (o[0].fault() instanceof io.github.thelinuxseal.seal.runtime.Trap trap
                && trap.getMessage().contains("outside its allocation") && vmOutput.startsWith(output)) {
            // An address outside its allocation: the JVM stops there, where the VM, which checks
            // addresses only against its whole memory, reads what lies next to it (docs/JVM.md §4.4).
            checked++;
        } else {
            differences.add(name + "\n    VM  " + show(vmOutput) + ", " + vmEnd + "\n    JVM " + show(output) + ", " + jvmEnd
                    + (o[0].fault() == null ? "" : "\n    " + JvmRunner.describe(o[0].fault()).replace("\n", "\n    ")
                    + (jvmEnd.equals("trap") && !(o[0].fault() instanceof io.github.thelinuxseal.seal.runtime.Trap)
                            ? "\n    " + stack(o[0].fault()) : "")));
        }
    }

    /** "On the JVM: 40 the same, 12 not available yet"; and adds each difference to the suite's failures. */
    String finish(List<String> failures) {
        for (String d : differences) {
            failures.add("on the JVM: " + d);
        }
        return "  (on the JVM: " + same + " the same, " + notYet + " not available yet"
                + (checked == 0 ? "" : ", " + checked + " stopped at an address outside its allocation")
                + (differences.isEmpty() ? "" : ", " + differences.size() + " different") + ")";
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        String s = w.toString();
        String[] lines = s.split("\n");
        return String.join("\n    ", java.util.Arrays.copyOf(lines, Math.min(lines.length, 12)));
    }

    private static String show(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }

    static boolean usesVmOnly(java.util.Map<String, String> files) {
        return files.values().stream().anyMatch(src -> src.contains("Probe.live"));
    }

    static boolean anyErrors(List<Diagnostic> d) {
        return !d.isEmpty();
    }
}
