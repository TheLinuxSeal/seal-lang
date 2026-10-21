package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;
import io.github.thelinuxseal.seal.runtime.Natives;

/**
 * Runs a checked program for the suites: its classes generated, verified and run in this JVM, in
 * a thread of its own that has 20 seconds to end.
 */
final class JvmRun {

    private JvmRun() {
    }

    /**
     * How a run went: what it printed on standard output and error, and main's exit code; if a
     * fault stopped it (the code is then 70), what it was, as the command line reports it, without
     * "trap: " (the first line, as "uncaught IllegalStateException: boom", or "division by zero"),
     * and the whole report. If it couldn't run, why: the backend's errors ("errors: ..."), classes
     * that fail verification, a crash of the backend, or no end in time.
     */
    record Outcome(String output, String errOutput, int code, String fault, String report, String error) {
        @Override
        public String toString() {
            return "\"" + output.replace("\n", "\\n") + "\", exit " + code + (fault == null ? "" : ", " + report)
                    + (error == null ? "" : ", " + error);
        }
    }

    static Outcome run(Checker checker, String main) {
        return run(checker, main, InputStream.nullInputStream(), true, Long.MAX_VALUE);
    }

    /**
     * As above, with the program's standard input, whether it may use files, and how many rounds of
     * loops it may run (Long.MAX_VALUE for no limit; with one, it stops past it, or past 5 seconds of
     * running, as a fault).
     */
    static Outcome run(Checker checker, String main, InputStream in, boolean files, long steps) {
        JvmGen.Result gen;
        try {
            gen = steps == Long.MAX_VALUE ? JvmGen.generate(checker, main) : JvmGen.generateCountingSteps(checker, main);
        } catch (RuntimeException e) {
            return failed("the JVM backend crashed: " + stack(e));
        }
        if (!gen.errors().isEmpty()) {
            return failed("errors: " + gen.errors());
        }
        List<String> problems = JvmRunner.verify(gen);
        if (!problems.isEmpty()) {
            return failed("the classes fail verification: " + problems);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        JvmRunner.Outcome[] o = new JvmRunner.Outcome[1];
        Thread t = new Thread(() -> {
            Natives.stepLimit = steps;
            Natives.stepDeadline = steps == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + 5_000_000_000L;
            try {
                o[0] = JvmRunner.run(gen, in, new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8), files);
            } finally {
                Natives.stepLimit = Long.MAX_VALUE;
                Natives.stepDeadline = Long.MAX_VALUE;
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            t.join(20_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (o[0] == null) {
            return failed("it didn't end within 20 seconds");
        }
        String output = out.toString(StandardCharsets.UTF_8);
        String errOutput = err.toString(StandardCharsets.UTF_8);
        if (o[0].fault() == null) {
            return new Outcome(output, errOutput, o[0].code(), null, null, null);
        }
        String report = JvmRunner.describe(o[0].fault());
        String first = report.lines().findFirst().orElse("");
        return new Outcome(output, errOutput, o[0].code(), first.startsWith("trap: ") ? first.substring(6) : first,
                report, null);
    }

    private static Outcome failed(String why) {
        return new Outcome("", "", -1, null, null, why);
    }

    static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        String[] lines = w.toString().split("\n");
        return String.join("\n    ", java.util.Arrays.copyOf(lines, Math.min(lines.length, 12)));
    }
}
