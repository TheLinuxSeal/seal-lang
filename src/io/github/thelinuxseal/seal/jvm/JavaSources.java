package io.github.thelinuxseal.seal.jvm;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

/**
 * A program's own .java files (docs/JAVA.md §11), compiled by the JDK's javac in two parts. The
 * files that use none of the program's Seal classes come first, and Seal's code uses their
 * classes as it does any Java class. Those that use Seal's (a Fabric mod's mixins, which call the
 * mod's Seal code) come after Seal's classes are generated, against them, and Seal's code can't
 * use theirs: each part needs the other's classes compiled first. Their classes go with the program's.
 */
public final class JavaSources implements AutoCloseable {

    /** What javac is told: no annotation processors (a jar's, such as Mixin's, would run otherwise). */
    private static final List<String> OPTIONS = List.of("-proc:none");

    /** What a file declares and names, as javac parses it: enough to tell whether it uses a class. */
    private record Unit(Path file, String pkg, List<String> declares, Set<String> imports, Set<String> starImports,
                        Set<String> names, Set<String> qualified) {

        /**
         * Whether the file may use the class of this binary name: by an import, by its simple
         * name where its package or a * import brings it, or written in full. It may say yes for
         * a file that doesn't (a variable of the class's name), never no for one that does.
         */
        boolean uses(String binary) {
            int dot = binary.lastIndexOf('.');
            String classPkg = dot < 0 ? "" : binary.substring(0, dot);
            String simple = binary.substring(dot + 1);
            String prefix = binary + ".";
            if (imports.stream().anyMatch(i -> i.equals(binary) || i.startsWith(prefix))
                    || starImports.stream().anyMatch(i -> i.equals(binary) || i.startsWith(prefix))) {
                return true;
            }
            if (names.contains(simple) && (classPkg.equals(pkg) || starImports.contains(classPkg))) {
                return true;
            }
            return !classPkg.isEmpty() && qualified.stream().anyMatch(q -> q.equals(binary) || q.startsWith(prefix));
        }

        /** Whether it names Seal's standard library or runtime, whose classes come with Seal's. */
        boolean usesSealLibrary() {
            return Stream.of(imports, starImports, qualified).flatMap(Set::stream)
                    .anyMatch(n -> n.startsWith(JvmGen.LIBRARY_ROOT));
        }
    }

    private final JavaCompiler javac;
    private final List<Unit> units;
    private final List<Path> before = new ArrayList<>();
    private final List<Path> after = new ArrayList<>();
    /** The classes the files that come after Seal's declare (top-level, by binary name), and their files. */
    private final Map<String, String> afterClasses = new LinkedHashMap<>();
    private final Path work;
    private Path beforeClasses;

    private JavaSources(JavaCompiler javac, List<Unit> units, Path work) {
        this.javac = javac;
        this.units = units;
        this.work = work;
    }

    /** Whether this Java has javac to compile them with: a JDK's has, a JRE's hasn't. */
    public static boolean available() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    /**
     * Reads the files and sorts them into the two parts, given the binary names of the program's
     * Seal classes (com.example.Main): a file comes after Seal's if it uses one, or uses a class
     * of a file that does. A file that doesn't parse is sorted by what javac could read of it;
     * compiling it reports its errors.
     */
    public static JavaSources sort(List<Path> files, Set<String> sealClasses) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<Unit> units = new ArrayList<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) javac.getTask(Writer.nullWriter(), fm, new DiagnosticCollector<JavaFileObject>(),
                    OPTIONS, null, fm.getJavaFileObjectsFromPaths(files));
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit(fm.asPath(unit.getSourceFile()), unit));
            }
        }
        JavaSources sorted = new JavaSources(javac, units, Files.createTempDirectory("seal-java"));
        Set<Unit> later = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Unit u : units) {
            if (u.usesSealLibrary() || sealClasses.stream().anyMatch(u::uses)) {
                later.add(u);
            }
        }
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Unit u : units) {
                if (!later.contains(u) && later.stream().anyMatch(l -> l.declares().stream().anyMatch(u::uses))) {
                    grew = later.add(u);
                }
            }
        }
        for (Unit u : units) {
            (later.contains(u) ? sorted.after : sorted.before).add(u.file());
            if (later.contains(u)) {
                u.declares().forEach(c -> sorted.afterClasses.put(c, u.file().toString()));
            }
        }
        return sorted;
    }

    private static Unit unit(Path file, CompilationUnitTree tree) {
        String pkg = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
        List<String> declares = new ArrayList<>();
        for (var t : tree.getTypeDecls()) {
            if (t instanceof ClassTree c && !c.getSimpleName().isEmpty()) {
                declares.add(pkg.isEmpty() ? c.getSimpleName().toString() : pkg + "." + c.getSimpleName());
            }
        }
        Set<String> imports = new HashSet<>();
        Set<String> starImports = new HashSet<>();
        for (ImportTree i : tree.getImports()) {
            String name = i.getQualifiedIdentifier().toString();
            if (name.endsWith(".*")) {
                starImports.add(name.substring(0, name.length() - 2));
            } else {
                imports.add(name);
            }
        }
        Set<String> names = new HashSet<>();
        Set<String> qualified = new HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                names.add(node.getName().toString());
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                qualified.add(node.toString());
                return super.visitMemberSelect(node, unused);
            }

            @Override
            public Void visitImport(ImportTree node, Void unused) {
                return null; // already read
            }

            @Override
            public Void visitPackage(PackageTree node, Void unused) {
                return null; // its own package isn't a use
            }
        }.scan(tree, null);
        return new Unit(file, pkg, declares, imports, starImports, names, qualified);
    }

    /** Whether there are files that use Seal's classes, compiled after them. */
    public boolean usesSeal() {
        return !after.isEmpty();
    }

    /** The first of them. */
    public Path firstUsingSeal() {
        return after.get(0);
    }

    /** The classes those files declare, by binary name, and their files: Seal's code can't use them. */
    public Map<String, String> afterClasses() {
        return afterClasses;
    }

    /**
     * Why a class is declared twice, once in Seal and once in Java, given the program's Seal
     * classes and their files; null if none is.
     */
    public String declaredTwice(Map<String, String> sealClasses) {
        for (Unit u : units) {
            for (String c : u.declares()) {
                if (sealClasses.containsKey(c)) {
                    return c + " is declared twice: in " + sealClasses.get(c) + " and in " + u.file();
                }
            }
        }
        return null;
    }

    /**
     * Compiles the files Seal's code may use, against the class path; false, after javac's errors
     * on {@code err}, if they don't compile.
     */
    public boolean compileBefore(List<Path> classPath, PrintStream err) throws IOException {
        if (before.isEmpty()) {
            return true;
        }
        beforeClasses = work.resolve("before");
        return compile(before, classPath, beforeClasses, err);
    }

    /** The folder of their classes, which the checker reads as the class path's; null if there are none. */
    public Path beforeClasses() {
        return beforeClasses;
    }

    /**
     * Compiles the files that use Seal's classes, against the class path, the other part's classes
     * and Seal's; gives the classes of both parts by binary name, or null after javac's errors.
     */
    public Map<String, byte[]> classes(JvmGen.Result seal, List<Path> classPath, PrintStream err) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (beforeClasses != null) {
            read(beforeClasses, out);
        }
        if (!after.isEmpty()) {
            Path sealClasses = work.resolve("seal");
            JvmRunner.write(seal, sealClasses);
            List<Path> path = new ArrayList<>();
            if (beforeClasses != null) {
                path.add(beforeClasses);
            }
            path.add(sealClasses);
            path.addAll(classPath);
            Path afterClasses = work.resolve("after");
            if (!compile(after, path, afterClasses, err)) {
                return null;
            }
            read(afterClasses, out);
        }
        return out;
    }

    private boolean compile(List<Path> files, List<Path> classPath, Path out, PrintStream err) throws IOException {
        Files.createDirectories(out);
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            fm.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of()); // the files given, and no others
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(out));
            PrintWriter messages = new PrintWriter(new OutputStreamWriter(err, StandardCharsets.UTF_8));
            boolean ok = javac.getTask(messages, fm, null, OPTIONS, null, fm.getJavaFileObjectsFromPaths(files)).call();
            messages.flush();
            return ok;
        }
    }

    private static void read(Path dir, Map<String, byte[]> into) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                String name = dir.relativize(f).toString();
                into.put(name.substring(0, name.length() - ".class".length()).replace(f.getFileSystem().getSeparator(), "."),
                        Files.readAllBytes(f));
            }
        }
    }

    /** Deletes the classes compiled on the way. */
    @Override
    public void close() throws IOException {
        try (Stream<Path> files = Files.walk(work)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
