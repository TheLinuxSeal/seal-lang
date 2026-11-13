package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.github.thelinuxseal.seal.lexer.Lexer;

/**
 * The editors' grammars (editors/README.md) keep up with the language: every keyword the lexer
 * has, and each word Seal knows by where it is (var, record, yield, size, as) and the built-in
 * types, is in the TextMate grammar (VSCodium, IntelliJ IDEA) and in Kate's; the extension and
 * Kate's settings start the language server (docs/LSP.md); and Sample.seal, which shows them all,
 * compiles.
 */
public final class EditorsTest {
    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final List<String> CONTEXTUAL = List.of("var", "record", "yield", "size", "as", "string", "array");

    public static void main(String[] args) throws Exception {
        Field f = Lexer.class.getDeclaredField("KEYWORDS");
        f.setAccessible(true);
        TreeSet<String> words = new TreeSet<>(((Map<?, ?>) f.get(null)).keySet().stream().map(Object::toString).toList());
        words.addAll(CONTEXTUAL);
        for (String grammar : List.of("editors/vscode/syntaxes/seal.tmLanguage.json", "editors/kate/seal.xml")) {
            String text = Files.readString(Path.of(grammar));
            // A word is there as itself, or as a regex has it: \bvar\b.
            List<String> missing = words.stream().filter(w -> !Pattern.compile("\\b" + w + "\\b").matcher(text).find()
                    && !text.contains("\\b" + w + "\\b")).toList();
            check(missing.isEmpty(), grammar + " has every keyword; it lacks " + missing);
        }
        String manifest = Files.readString(Path.of("editors/vscode/package.json"));
        check(manifest.contains("\"path\": \"./syntaxes/seal.tmLanguage.json\"") && manifest.contains("\".seal\""),
                "package.json gives .seal files the grammar");
        String client = Files.readString(Path.of("editors/vscode/extension.js"));
        check(manifest.contains("\"main\": \"./extension.js\"") && manifest.contains("\"onLanguage:seal\"")
                && client.contains("['lsp', ") && client.contains("textDocument/publishDiagnostics"),
                "the extension starts seal lsp for .seal files, and shows its diagnostics");
        String kate = Files.readString(Path.of("editors/kate/lsp.json"));
        check(kate.contains("\"command\": [\"seal\", \"lsp\"]") && kate.contains("\"highlightingModeRegex\": \"^Seal$\"")
                && Files.readString(Path.of("editors/kate/seal.xml")).contains("<language name=\"Seal\""),
                "Kate's server settings start seal lsp for files with the Seal highlighting");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"check", "editors/Sample.seal"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        check(code == 0, "editors/Sample.seal compiles: " + err.toString(StandardCharsets.UTF_8));

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(x -> System.out.println("FAIL: " + x));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
