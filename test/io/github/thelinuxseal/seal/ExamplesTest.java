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
        List<String> failures = new ArrayList<>();
        List<Path> dirs;
        try (Stream<Path> s = Files.list(Path.of("examples"))) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        List<String> skipped = new ArrayList<>();
        for (Path dir : dirs) {
            Path expectedFile = dir.resolve("expected.txt");
            if (!Files.exists(expectedFile) && (Files.exists(dir.resolve("run.sh")) || Files.exists(dir.resolve("build.sh")))) {
                // Runs its own way: the Minecraft mods, the LWJGL window.
                skipped.add(dir.getFileName().toString());
                continue;
            }
            if (!Files.exists(expectedFile)) {
                failures.add(dir + ": no expected.txt");
                continue;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = Main.run(new String[]{"run", dir.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            String got = out.toString(StandardCharsets.UTF_8);
            String expected = Files.readString(expectedFile);
            String errText = err.toString(StandardCharsets.UTF_8);
            if (code == 0 && got.equals(expected) && errText.isEmpty()) {
                passed++;
            } else {
                failures.add(dir + ": exit " + code + firstDifference(expected, got)
                        + (errText.isEmpty() ? "" : "\n    stderr: " + errText.strip()));
            }
        }
        // The benchmarks take minutes (benchmarks/Run.java): here, only that their Seal program still compiles.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[]{"check", "benchmarks/seal"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String said = (out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)).strip();
        if (code == 0 && said.isEmpty()) {
            passed++;
        } else {
            failures.add("benchmarks/seal: exit " + code + (said.isEmpty() ? "" : "\n    " + said));
        }
        System.out.println(passed + " passed, " + failures.size() + " failed"
                + (skipped.isEmpty() ? "" : "  (" + String.join(", ", skipped) + " run with their own scripts, not here)"));
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
