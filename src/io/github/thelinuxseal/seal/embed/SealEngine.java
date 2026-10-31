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
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * compiles share: the standard library, read once; the host's own Seal library, which every script
 * is compiled with; the Java classes scripts may use, which are none the host doesn't allow; the
 * warnings asked for; and the standard streams and file access each script gets.
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
    private final Map<String, String> library;
    private final List<Path> classPath;
    private final ClassLoader parent;
    private final ClassLoader loader;
    private final Set<Warning> warn;
    private final Set<Class<?>> entries;
    private final JavaAllowlist javaAllowed;
    final boolean countSteps;
    final InputStream in;
    final PrintStream out;
    final PrintStream err;
    final boolean files;

    private SealEngine(Builder b, Map<String, String> stdlib, Map<String, String> library) {
        this.stdlib = stdlib;
        this.library = library;
        this.classPath = List.copyOf(b.classPath);
        this.parent = b.parent;
        this.loader = classPath.isEmpty() ? b.parent : new URLClassLoader(urls(classPath), b.parent);
        this.warn = EnumSet.copyOf(b.warn);
        this.entries = Set.copyOf(b.entries);
        List<String> allowed = new ArrayList<>(b.allowJava);
        // The entry types are the host's for its scripts to implement: they may name them.
        b.entries.forEach(t -> allowed.add(t.getName()));
        this.javaAllowed = JavaAllowlist.of(allowed);
        this.countSteps = b.countSteps;
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

    /**
     * Compiles several files' code, by name, as one script, with the engine's library (its
     * {@link Builder#library} files), whose classes the script then has its own of.
     */
    public SealScript compile(Map<String, String> sources) {
        return compile(sources, javaAllowed);
    }

    /** The Java classes the engine's scripts may use. */
    JavaAllowlist javaAllowed() {
        return javaAllowed;
    }

    /** As above, the script's own files using only the Java classes {@code allowed} takes; the library's, any. */
    SealScript compile(Map<String, String> sources, JavaAllowlist allowed) {
        for (String file : sources.keySet()) {
            if (library.containsKey(file)) {
                return new SealScript(this, List.of(new Diagnostic(file, 1, 1,
                        "the engine's library has a file named " + file + "; a script's files need other names")), List.of(), null);
            }
        }
        Map<String, String> all = new LinkedHashMap<>(library);
        all.putAll(sources);
        Checker checker = Checker.analyze(all, stdlib, warn, new JavaClasses(parent, classPath, Map.of()), sources.keySet(),
                allowed);
        // The script's warnings: the library's are the host's to see, when it is built.
        List<Diagnostic> warnings = checker.warnings().stream().filter(w -> !library.containsKey(w.file())).toList();
        if (!checker.diagnostics().isEmpty()) {
            return new SealScript(this, checker.diagnostics(), warnings, null);
        }
        // Every class, as a library's (docs/JAVA.md §5): the program's Java code uses them, not only main;
        // counting steps, which the host may limit (§6).
        JvmGen.Result result = countSteps ? JvmGen.generateLibraryCountingSteps(checker) : JvmGen.generateLibrary(checker);
        if (!result.errors().isEmpty()) {
            return new SealScript(this, result.errors(), warnings, null);
        }
        SealScript script = new SealScript(this, List.of(), warnings, result.classes());
        List<Diagnostic> entryErrors = script.findEntries(checker, sources.keySet(), entries);
        return entryErrors.isEmpty() ? script : new SealScript(this, entryErrors, warnings, null);
    }

    /**
     * A snippet (docs/EMBED.md §4): statements, compiled as the body of a method that takes the
     * Java values the host binds by name, with the engine's library; a chat command's code, a
     * setting's formula. {@link SealSnippet.Builder#compile} compiles it.
     */
    public SealSnippet.Builder snippet(String code) {
        return new SealSnippet.Builder(this, code);
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
        private final List<LibraryFiles> library = new ArrayList<>();
        private final List<Path> classPath = new ArrayList<>();
        private ClassLoader parent = SealEngine.class.getClassLoader();
        private final Set<Warning> warn = EnumSet.noneOf(Warning.class);
        private final Set<Class<?>> entries = new LinkedHashSet<>();
        private final List<String> allowJava = new ArrayList<>();
        private boolean countSteps = true;
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

        /**
         * The host's own Seal code, `.seal` files and folders, which every script is compiled with
         * and may use: the API a host gives its scripts, with Seal's types, which takes the host's
         * Java objects and calls them (docs/EMBED.md §3). Each script has its own of its classes.
         */
        public Builder library(Path... paths) {
            library.add(into -> {
                for (Path p : paths) {
                    Sources.read(p, into);
                }
            });
            return this;
        }

        /**
         * As above, from a class loader's resources: the `.seal` files in the folder {@code dir}
         * ("api/"), which its file {@code index} lists, one path to a line, relative to the folder,
         * since a class loader can't list a jar's folders. The files are named {@code dir} and
         * their path, in errors.
         */
        public Builder library(ClassLoader loader, String dir) {
            String folder = dir.endsWith("/") ? dir : dir + "/";
            library.add(into -> {
                Map<String, String> files = Sources.resources(loader, folder, folder);
                if (files == null) {
                    throw new IOException("no library at " + folder + ": it has no index of its files");
                }
                into.putAll(files);
            });
            return this;
        }

        /** As above, the files' code, by name. */
        public Builder library(Map<String, String> sources) {
            Map<String, String> copy = new LinkedHashMap<>(sources);
            library.add(into -> into.putAll(copy));
            return this;
        }

        /**
         * Java classes the scripts may use, which are none unless allowed (docs/EMBED.md §5): a class
         * ("java.util.ArrayList", and the classes nested in it), a package's classes
         * ("java.util.*", not its subpackages'), or every class ("*"). A script may use no other,
         * by its name or through an object an API gives it; the library may use any. seal.java's
         * types (JavaString, JavaObject, the boxes) and the entry types are always allowed.
         */
        public Builder allowJava(String... names) {
            allowJava.addAll(List.of(names));
            return this;
        }

        /** As above, these classes, and the classes nested in them. */
        public Builder allowJava(Class<?>... classes) {
            for (Class<?> c : classes) {
                allowJava.add(c.getName());
            }
            return this;
        }

        /**
         * Whether scripts count their steps, rounds of loops and calls, so that the host may limit
         * them and stop them (docs/EMBED.md §6): by default, they do. A host that trusts its
         * scripts may save the time it takes, a little in code that calls, more in a loop that
         * does little else; without it, {@link SealScript#limitSteps} and the others throw.
         */
        public Builder countSteps(boolean count) {
            this.countSteps = count;
            return this;
        }

        /** Jars and class folders of Java classes that scripts may import, after the parent's (docs/JAVA.md §6). */
        public Builder classPath(Path... entries) {
            classPath.addAll(List.of(entries));
            return this;
        }

        /**
         * The class loader under which scripts' classes are loaded, whose Java classes they may use,
         * as the host's code does: the interfaces the host calls scripts through, the game's
         * classes. By default, the engine's.
         */
        public Builder parent(ClassLoader loader) {
            this.parent = loader;
            return this;
        }

        /**
         * Java interfaces (or classes) that scripts' classes implement for the host to make, with
         * {@link SealScript#create}: each class of a script that is one, and isn't abstract, must
         * have a constructor without parameters, or the script doesn't compile. The parent loader
         * must have them, as the host's code does.
         */
        public Builder entry(Class<?>... types) {
            entries.addAll(List.of(types));
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

        /**
         * Reads the standard library and the engine's library, which every compile of the engine
         * then shares, and compiles the engine's library by itself: an IllegalArgumentException
         * gives its errors.
         */
        public SealEngine build() throws IOException {
            for (Class<?> type : entries) {
                // Scripts implement the parent's class of the name: it must be this one, or the host could make none.
                Class<?> seen;
                try {
                    seen = Class.forName(type.getName(), false, parent);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("the parent class loader hasn't got the entry type " + type.getName()
                            + ", so scripts can't implement it");
                }
                if (seen != type) {
                    throw new IllegalArgumentException("the parent class loader's " + type.getName()
                            + " isn't the entry type given, which another loader loaded: scripts would implement the parent's");
                }
            }
            Map<String, String> std = new LinkedHashMap<>();
            if (stdlib == null) {
                std.putAll(Sources.stdlib());
            } else if (!Files.isDirectory(stdlib)) {
                throw new IOException("standard library not found at " + stdlib);
            } else {
                Sources.read(stdlib, std);
            }
            Map<String, String> host = new LinkedHashMap<>();
            for (LibraryFiles files : library) {
                files.readInto(host);
            }
            SealEngine engine = new SealEngine(this, std, Collections.unmodifiableMap(host));
            if (!host.isEmpty()) {
                SealScript alone = engine.compile(Map.of());
                if (!alone.ok()) {
                    throw new IllegalArgumentException("the engine's library has errors:\n" + String.join("\n",
                            alone.errors().stream().map(Diagnostic::toString).toList()));
                }
            }
            return engine;
        }
    }

    /** Where some of an engine's library files come from, read when it is built. */
    private interface LibraryFiles {
        void readInto(Map<String, String> into) throws IOException;
    }
}
