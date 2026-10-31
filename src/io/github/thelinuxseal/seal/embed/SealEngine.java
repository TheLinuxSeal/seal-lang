package io.github.thelinuxseal.seal.embed;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.thelinuxseal.seal.Sources;
import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.JavaClasses;
import io.github.thelinuxseal.seal.check.Warning;
import io.github.thelinuxseal.seal.jvm.JvmGen;

/**
 * Seal inside a Java program (docs/EMBED.md): compiles Seal's code while the program runs, into
 * classes in the program's own JVM, which its Java code then uses. An engine holds what its
 * compiles share: the standard library, read once; the Java classes scripts may use; the warnings
 * asked for; and the standard streams and file access each script gets.
 *
 * <pre>
 *   SealEngine seal = SealEngine.builder().out(log).build();
 *   SealScript script = seal.compile(Path.of("scripts"));
 *   script.errors().forEach(e -&gt; log.println(e));   // Main.seal:3:9: unknown method printn
 *   int code = script.run();                          // Main's main()
 * </pre>
 */
public final class SealEngine {

    private final Map<String, String> stdlib;
    private final List<Path> classPath;
    private final ClassLoader loader;
    private final Set<Warning> warn;
    final InputStream in;
    final PrintStream out;
    final PrintStream err;
    final boolean files;

    private SealEngine(Builder b, Map<String, String> stdlib) {
        this.stdlib = stdlib;
        this.classPath = List.copyOf(b.classPath);
        this.loader = classPath.isEmpty() ? b.parent : new URLClassLoader(urls(classPath), b.parent);
        this.warn = EnumSet.copyOf(b.warn);
        this.in = b.in;
        this.out = b.out;
        this.err = b.err;
        this.files = b.files;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Compiles `.seal` files, and every one in the folders given, as one script. Its errors are
     * the script's {@link SealScript#errors}; an IOException is a file that couldn't be read.
     */
    public SealScript compile(Path... paths) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        List<Path> java = new ArrayList<>();
        for (Path p : paths) {
            Sources.read(p, sources, java);
        }
        if (!java.isEmpty()) {
            // javac compiles a program's .java files with it (docs/JAVA.md §11); a script's are the host's to compile.
            return new SealScript(this, List.of(new Diagnostic(java.get(0).toString(), 1, 1,
                    "a script can't have .java files: compile them with the program that runs it")), List.of(), null);
        }
        return compile(sources);
    }

    /** Compiles one file's code, named {@code file} in errors and in the lines a fault reports. */
    public SealScript compile(String file, String code) {
        return compile(Map.of(file, code));
    }

    /** Compiles several files' code, by name, as one script. */
    public SealScript compile(Map<String, String> sources) {
        Checker checker = Checker.analyze(sources, stdlib, warn, new JavaClasses(classPath));
        if (!checker.diagnostics().isEmpty()) {
            return new SealScript(this, checker.diagnostics(), checker.warnings(), null);
        }
        // Every class, as a library's (docs/JAVA.md §5): the program's Java code uses them, not only main.
        JvmGen.Result result = JvmGen.generateLibrary(checker);
        if (!result.errors().isEmpty()) {
            return new SealScript(this, result.errors(), checker.warnings(), null);
        }
        return new SealScript(this, List.of(), checker.warnings(), result.classes());
    }

    /** The class loader a script's classes are loaded under. */
    ClassLoader loader() {
        return loader;
    }

    private static URL[] urls(List<Path> classPath) {
        URL[] out = new URL[classPath.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = classPath.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("class path entry " + classPath.get(i) + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** What an engine is made with. Unset, scripts write to System.out and System.err, read nothing, and can't use files. */
    public static final class Builder {
        private Path stdlib;
        private final List<Path> classPath = new ArrayList<>();
        private ClassLoader parent = SealEngine.class.getClassLoader();
        private final Set<Warning> warn = EnumSet.noneOf(Warning.class);
        private InputStream in = InputStream.nullInputStream();
        private PrintStream out = System.out;
        private PrintStream err = System.err;
        private boolean files;

        private Builder() {
        }

        /** The standard library's folder. Unset, it is found as `seal` finds it: seal.jar's own, when the compiler is in it. */
        public Builder stdlib(Path folder) {
            this.stdlib = folder;
            return this;
        }

        /** Jars and class folders of Java classes that scripts may import, after the parent's (docs/JAVA.md §6). */
        public Builder classPath(Path... entries) {
            classPath.addAll(List.of(entries));
            return this;
        }

        /** The class loader under which scripts' classes are loaded, whose classes they use: by default, the engine's. */
        public Builder parent(ClassLoader loader) {
            this.parent = loader;
            return this;
        }

        /** Warnings to report, as `seal`'s -W flags do (§56.1): they are the script's {@link SealScript#warnings}. */
        public Builder warn(Warning... flags) {
            warn.addAll(List.of(flags));
            return this;
        }

        /** What scripts read as standard input. */
        public Builder in(InputStream in) {
            this.in = in;
            return this;
        }

        /** Where scripts' standard output goes. */
        public Builder out(PrintStream out) {
            this.out = out;
            return this;
        }

        /** Where scripts' standard error goes. */
        public Builder err(PrintStream err) {
            this.err = err;
            return this;
        }

        /** Whether scripts may use files and folders; without it, every file operation fails. */
        public Builder files(boolean allowed) {
            this.files = allowed;
            return this;
        }

        /** Reads the standard library, which every compile of the engine then shares. */
        public SealEngine build() throws IOException {
            Map<String, String> library = new LinkedHashMap<>();
            if (stdlib == null) {
                library.putAll(Sources.stdlib());
            } else if (!Files.isDirectory(stdlib)) {
                throw new IOException("standard library not found at " + stdlib);
            } else {
                Sources.read(stdlib, library);
            }
            return new SealEngine(this, library);
        }
    }
}
