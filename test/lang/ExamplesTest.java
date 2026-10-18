package lang;

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
 * changes. Run from the project root: `java -cp out lang.ExamplesTest`.
 */
public final class ExamplesTest {

    public static void main(String[] args) throws IOException {
        int passed = 0;
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
            int code = Main.run(new String[]{"run", dir.toString()},
                    new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
            String got = out.toString(StandardCharsets.UTF_8);
            String expected = Files.readString(expectedFile);
            if (code == 0 && got.equals(expected) && err.size() == 0) {
                passed++;
            } else {
                failures.add(dir + ": exit " + code + firstDifference(expected, got)
                        + (err.size() == 0 ? "" : "\n    stderr: " + err.toString(StandardCharsets.UTF_8).strip()));
            }
        }
        System.out.println(passed + " passed, " + failures.size() + " failed");
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
