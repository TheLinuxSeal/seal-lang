package io.github.thelinuxseal.seal.lsp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.JavaClasses;
import io.github.thelinuxseal.seal.check.Warning;
import io.github.thelinuxseal.seal.jvm.JavaSources;
import io.github.thelinuxseal.seal.parser.ParseError;
import io.github.thelinuxseal.seal.parser.Parser;

/**
 * A program the editor has a file of open (docs/LSP.md §2): the `.seal` files of a folder and the
 * folders in it, as `seal check DIR` reads them, with the text of those open in the editor in
 * place of what is saved. The folder is the one the file's package starts in: src for
 * src/com/example/Main.seal in package com.example, and the file's own for one in no package.
 * The standard library's own files are a program of their own, checked as the library.
 */
final class Program {

    /** Folders a program's files aren't looked for in: the build's, and those whose names start with a dot. */
    private static final Set<String> SKIPPED = Set.of("node_modules", "out", "build", "classes", "target", "result");
    /** At most this many files are read, in case a file in no package is in a big folder (a home folder). */
    static final int MAX_FILES = 2000;

    final Path root;
    /** The standard library's folder, whose files are the library's, not the program's; null for none. */
    private final Path stdlib;
    /** Whether the program is the standard library itself. */
    final boolean isLibrary;

    /** The last check, null before the first. */
    Analysis analysis;
    /** Whether a file changed since the last check. */
    boolean dirty = true;
    /** The files whose diagnostics were last published as some, to clear when they have none. */
    Set<String> published = Set.of();
    /** Whether there were more files than {@link #MAX_FILES}, which the last check left out. */
    boolean truncated;

    private JavaSources java;
    private String javaSignature;
    private JavaClasses javaClasses;
    private String javaClassesKey;

    Program(Path root, Path stdlib) {
        this.root = root;
        this.stdlib = stdlib;
        this.isLibrary = root != null && root.equals(stdlib);
    }

    /**
     * The folder of the program a file is in: the one its package starts in, or its own. The
     * standard library's for a file in it.
     */
    static Path rootOf(Path file, String text, Path stdlib) {
        if (stdlib != null && file.startsWith(stdlib)) {
            return stdlib;
        }
        Path dir = file.getParent();
        String pkg = Parser.parse(text).pkg();
        if (pkg == null || dir == null) {
            return dir;
        }
        String[] parts = pkg.split("\\.");
        Path up = dir;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (up == null || up.getFileName() == null || !up.getFileName().toString().equals(parts[i])) {
                return dir; // not in the folders its package says (reported), so its own folder's
            }
            up = up.getParent();
        }
        return up == null ? dir : up;
    }

    /** The result of checking: each file's diagnostics, the program's files' all there, if none. */
    record Checked(Analysis analysis, Map<String, List<Diagnostic>> diagnostics) {
    }

    /**
     * Checks the program, with the text of open files from {@code open} (null for those that
     * aren't), the program's among them {@code openHere}, and the standard library's files.
     */
    Checked check(Function<String, String> open, Set<String> openHere, Map<String, String> library, Set<Warning> warn,
                  List<Path> classPath, PrintStream log) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        Map<String, String> sources = isLibrary ? new LinkedHashMap<>() : read(open, javaFiles);
        for (String f : openHere) {
            if (!isLibrary && !sources.containsKey(f) && f.endsWith(".seal")) {
                sources.put(f, open.apply(f)); // left out of the folder's, but open: it is the program's all the same
            }
        }
        Map<String, String> lib = library;
        if (isLibrary) {
            lib = new LinkedHashMap<>(library);
            for (String f : library.keySet()) {
                String text = open.apply(f);
                if (text != null) {
                    lib.put(f, text);
                }
            }
        }
        JavaClasses jc = javaClasses(javaFiles, sources, classPath, log);
        Checker checker = Checker.analyzeForTools(sources, lib, warn, jc);
        Map<String, String> texts = new LinkedHashMap<>(lib);
        texts.putAll(sources);
        Analysis a = new Analysis(checker, texts);

        Map<String, List<Diagnostic>> byFile = new TreeMap<>();
        Set<String> mine = isLibrary ? lib.keySet() : sources.keySet();
        for (String f : mine) {
            byFile.put(f, new ArrayList<>());
        }
        Map<String, List<ParseError>> parseErrors = new LinkedHashMap<>();
        for (String f : mine) {
            List<ParseError> errors = Parser.parse(texts.get(f)).errors();
            if (!errors.isEmpty()) {
                parseErrors.put(f, errors);
            }
        }
        List<Diagnostic> all = new ArrayList<>(checker.diagnostics());
        all.addAll(checker.warnings());
        for (Diagnostic d : all) {
            List<Diagnostic> list = byFile.get(d.file());
            // A file that doesn't parse has those errors alone: the rest come of what is half written.
            if (list != null && !parseErrors.containsKey(d.file())) {
                list.add(d);
            }
        }
        parseErrors.forEach((f, errors) -> {
            for (ParseError e : errors) {
                byFile.get(f).add(new Diagnostic(f, e.line(), e.column(), e.message()));
            }
        });
        analysis = a;
        dirty = false;
        return new Checked(a, byFile);
    }

    /** The program's `.seal` files, by path, and its `.java` files into {@code javaFiles}. */
    private Map<String, String> read(Function<String, String> open, List<Path> javaFiles) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        List<Path> found = new ArrayList<>();
        truncated = false;
        if (root == null || !Files.isDirectory(root)) {
            return sources;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (name.startsWith(".") || SKIPPED.contains(name) || dir.equals(stdlib))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".seal")) {
                    if (found.size() >= MAX_FILES) {
                        truncated = true;
                        return FileVisitResult.TERMINATE;
                    }
                    found.add(file);
                } else if (name.endsWith(".java") && attrs.isRegularFile()) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        for (Path f : found) {
            String key = f.toString();
            String text = open.apply(key);
            if (text == null) {
                try {
                    text = Files.readString(f);
                } catch (IOException e) {
                    continue; // gone since, or not text
                }
            }
            sources.put(key, text);
        }
        javaFiles.sort(null);
        return sources;
    }

    /**
     * The Java classes the program sees: the JDK's, the class path's, and its own `.java` files',
     * compiled as `seal check` does (docs/JAVA.md §11) when they change.
     */
    private JavaClasses javaClasses(List<Path> javaFiles, Map<String, String> sources, List<Path> classPath, PrintStream log)
            throws IOException {
        List<Path> path = new ArrayList<>(classPath);
        Map<String, String> after = Map.of();
        if (!javaFiles.isEmpty() && JavaSources.available()) {
            StringBuilder sig = new StringBuilder();
            for (Path f : javaFiles) {
                sig.append(f).append('@').append(Files.getLastModifiedTime(f).toMillis()).append(';');
            }
            if (!sig.toString().equals(javaSignature)) {
                close();
                javaSignature = sig.toString();
                java = JavaSources.sort(javaFiles, sealClasses(sources).keySet());
                if (!java.compileBefore(classPath, log)) {
                    log.println("seal lsp: the .java files of " + root + " don't compile; their classes are left out");
                }
            }
            if (java.beforeClasses() != null) {
                path.add(java.beforeClasses());
            }
            after = java.afterClasses();
        } else {
            close();
        }
        String key = path + " " + javaSignature;
        if (javaClasses == null || !key.equals(javaClassesKey)) {
            javaClasses = new JavaClasses(path, after);
            javaClassesKey = key;
        }
        return javaClasses;
    }

    /** The binary names of the program's top-level Seal classes (com.example.Main), and their files. */
    private static Map<String, String> sealClasses(Map<String, String> sources) {
        Map<String, String> classes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Parser.Result r = Parser.parse(e.getValue());
            if (!r.ast().headers().isEmpty()) {
                String name = r.ast().headers().get(0).name();
                classes.put(r.pkg() == null ? name : r.pkg() + "." + name, e.getKey());
            }
        }
        return classes;
    }

    /** Deletes the classes its `.java` files were compiled to. */
    void close() throws IOException {
        if (java != null) {
            java.close();
            java = null;
            javaSignature = null;
        }
    }
}
