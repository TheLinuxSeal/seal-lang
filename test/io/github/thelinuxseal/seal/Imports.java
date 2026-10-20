package io.github.thelinuxseal.seal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The imports a program the tests put together needs: one for each library type its code names
 * that isn't in seal.lang (seal.util's List, seal.io's Files). They go on one line, before the
 * type, so that the program's lines keep their numbers. Run from the project root.
 */
final class Imports {

    /** The library's types outside seal.lang, by simple name, with their packages. */
    private static final Map<String, String> PACKAGES = read(Path.of("stdlib", "seal"));
    private static final Pattern NAME = Pattern.compile("\\b[A-Z]\\w*\\b");

    private Imports() {
    }

    /** "import seal.util.List; import seal.io.Files; " for code naming List and Files; "" for none. */
    static String of(String code) {
        Set<String> imports = new LinkedHashSet<>();
        Matcher m = NAME.matcher(code);
        while (m.find()) {
            String pkg = PACKAGES.get(m.group());
            if (pkg != null) {
                imports.add("import " + pkg + "." + m.group() + "; ");
            }
        }
        return String.join("", imports);
    }

    private static Map<String, String> read(Path root) {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".seal")).toList()) {
                String pkg = "seal." + root.relativize(f.getParent()).toString().replace('/', '.').replace('\\', '.');
                if (!pkg.equals("seal.lang")) {
                    found.put(f.getFileName().toString().replace(".seal", ""), pkg);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
