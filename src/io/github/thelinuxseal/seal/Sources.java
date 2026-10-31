package io.github.thelinuxseal.seal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where Seal's source files come from: `.seal` files and folders, and the standard library, which
 * is a folder beside the compiler's classes or, in seal.jar (jar.sh), the jar's own resources.
 */
public final class Sources {

    private Sources() {
    }

    /**
     * Where jar.sh puts the standard library in seal.jar, with {@code index}, which lists its files,
     * one to a line: a class loader can read a jar's files but can't list its folders.
     */
    public static final String BUNDLED = "io/github/thelinuxseal/seal/stdlib/";

    /** Reads a `.seal` file, or every `.seal` file under a directory. */
    public static void read(Path p, Map<String, String> into) throws IOException {
        read(p, into, null);
    }

    /** As above, and adds the `.java` files given, or under the directory, to {@code java} (docs/JAVA.md §11). */
    public static void read(Path p, Map<String, String> into, List<Path> java) throws IOException {
        if (Files.isDirectory(p)) {
            try (Stream<Path> files = Files.walk(p)) {
                for (Path f : files.sorted().toList()) {
                    if (f.toString().endsWith(".seal")) {
                        into.put(f.toString(), Files.readString(f));
                    } else if (java != null && f.toString().endsWith(".java") && Files.isRegularFile(f)) {
                        java.add(f);
                    }
                }
            }
        } else if (java != null && p.toString().endsWith(".java")) {
            java.add(p);
        } else {
            into.put(p.toString(), Files.readString(p));
        }
    }

    /**
     * The standard library's folder: the `seal.stdlib` property's, else a `stdlib` folder beside the
     * compiler's class folder. Null when there is neither: then the library is the jar's
     * ({@link #bundled}), or, failing that, a `stdlib` folder in the working directory.
     */
    public static Path stdlibFolder() {
        String prop = System.getProperty("seal.stdlib");
        if (prop != null) {
            return Path.of(prop);
        }
        try {
            Path classes = Path.of(Sources.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = classes.toAbsolutePath().getParent().resolve("stdlib");
            return Files.isDirectory(candidate) ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The standard library's files: those of {@link #stdlibFolder}, else seal.jar's, else those of ./stdlib. */
    public static Map<String, String> stdlib() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        Path folder = stdlibFolder();
        if (folder == null) {
            Map<String, String> bundled = bundled(Sources.class.getClassLoader());
            if (bundled != null) {
                return bundled;
            }
            folder = Path.of("stdlib");
        }
        if (!Files.isDirectory(folder)) {
            throw new IOException("standard library not found at " + folder);
        }
        read(folder, files);
        return files;
    }

    /**
     * The standard library that jar.sh put among a class loader's resources, its files named
     * stdlib/seal/lang/Math.seal; null if there is none.
     */
    public static Map<String, String> bundled(ClassLoader loader) throws IOException {
        InputStream index = loader.getResourceAsStream(BUNDLED + "index");
        if (index == null) {
            return null;
        }
        Map<String, String> files = new LinkedHashMap<>();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                try (InputStream in = loader.getResourceAsStream(BUNDLED + line)) {
                    if (in == null) {
                        throw new IOException("the standard library's index names " + line + ", which isn't there");
                    }
                    files.put("stdlib/" + line, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return files;
    }
}
