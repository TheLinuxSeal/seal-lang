package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs every program in examples/ and compares its output with the
 * expected.txt beside it, so the examples keep working as the language
 * changes. Run from the project root: `java -cp out io.github.thelinuxseal.seal.ExamplesTest`.
 */
public final class ExamplesTest {

    public static void main(String[] args) throws IOException {
        int passed = 0;
        int jvmSame = 0;
        int jvmNotYet = 0;
        List<String> failures = new ArrayList<>();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(Path.of("examples"))) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : dirs) {
            Path expectedFile = dir.resolve("expected.txt");
            if (!Files.exists(expectedFile)) {
                failures.add(dir + ": no expected.txt");
                continue;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            // On the VM, the reference the JVM is compared with while both are kept (docs/JVM.md §12).
            int code = Main.run(new String[]{"run", "--vm", dir.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            String got = out.toString(StandardCharsets.UTF_8);
            String expected = Files.readString(expectedFile);
            // --vm says first that it is deprecated; nothing else should reach standard error.
            String errText = err.toString(StandardCharsets.UTF_8).lines()
                    .filter(l -> !l.equals(Main.VM_DEPRECATED)).reduce("", (a, b) -> a + b + "\n");
            if (code == 0 && got.equals(expected) && errText.isEmpty()) {
                passed++;
            } else {
                failures.add(dir + ": exit " + code + firstDifference(expected, got)
                        + (errText.isEmpty() ? "" : "\n    stderr: " + errText.strip()));
            }
            // On the JVM too (docs/JVM.md §12): the same output, or an error saying it isn't available yet.
            ByteArrayOutputStream jout = new ByteArrayOutputStream();
            ByteArrayOutputStream jerr = new ByteArrayOutputStream();
            int jcode = Main.run(new String[]{"run", dir.toString()},
                    new PrintStream(jout, true, StandardCharsets.UTF_8), new PrintStream(jerr, true, StandardCharsets.UTF_8));
            String jerrText = jerr.toString(StandardCharsets.UTF_8);
            if (jcode == 1 && !jerrText.isEmpty() && jerrText.lines().allMatch(l -> l.contains("not available on the JVM"))) {
                jvmNotYet++;
            } else if (jcode == 0 && jout.toString(StandardCharsets.UTF_8).equals(expected) && jerrText.isEmpty()) {
                jvmSame++;
            } else {
                failures.add("on the JVM: " + dir + ": exit " + jcode + firstDifference(expected, jout.toString(StandardCharsets.UTF_8))
                        + (jerrText.isEmpty() ? "" : "\n    stderr: " + jerrText.strip()));
            }
        }
        System.out.println(passed + " passed, " + failures.size() + " failed  (on the JVM: " + jvmSame + " the same, "
                + jvmNotYet + " not available yet)");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static String firstDifference(String expected, String got) {
        String[] e = expected.split("\n", -1);
        String[] g = got.split("\n", -1);
        for (int i = 0; i < Math.max(e.length, g.length); i++) {
            String a = i < e.length ? e[i] : "(end)";
            String b = i < g.length ? g[i] : "(end)";
            if (!a.equals(b)) {
                return "\n    line " + (i + 1) + ": expected \"" + a + "\"\n             got      \"" + b + "\"";
            }
        }
        return "";
    }
}
