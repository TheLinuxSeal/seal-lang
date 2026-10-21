package io.github.thelinuxseal.seal;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.JavaClasses;
import io.github.thelinuxseal.seal.check.Warning;
import io.github.thelinuxseal.seal.jvm.ClassText;
import io.github.thelinuxseal.seal.jvm.JavaSources;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;
import io.github.thelinuxseal.seal.parser.Parser;

/**
 * Command-line driver.
 *
 * <pre>
 *   seal run   [options] PATH...   compile to JVM class files and run them in this JVM; exit code is main's result
 *   seal check [options] PATH...   report errors only
 *   seal dump  [options] PATH...   print the generated class files
 *   seal build [options] PATH...   write the class files, and the runtime's, so that `java -cp DIR Main` runs
 *                                  the program (docs/JVM.md)
 *
 *   -d DIR            where build writes the class files (default: classes)
 *   --jar FILE        build writes a jar instead, with the runtime's classes; a program's runs with java -jar FILE
 *   --resources DIR   build puts the files of DIR beside the classes: a Fabric mod's fabric.mod.json, its assets
 *   --library         build every class, with no main: a library Java's code uses, or a Fabric mod (docs/JAVA.md)
 *   --main Class      the class whose main() is the entry point (default Main); com.example.Main for one in a package
 *   --classpath PATH  (or -cp) the jars and class folders of the Java classes the program imports, after the
 *                     JDK's (docs/JAVA.md), separated as Java's class path is (: on Linux and macOS)
 *   --stdlib DIR      where the standard library is (default: the stdlib directory
 *                     next to the compiler's class files, or the seal.stdlib property)
 *   --no-stdlib       do not load the standard library
 *   -WautoCast        warn about every number converted without a cast (int to double, i += 2.5)
 *   -WautoCastLossy   warn only about those that may lose data (i += 2.5, int to float)
 *   -Wassert          run assert statements (as Java's -ea); without it they are skipped
 *   -Wall             turn on every -W flag
 * </pre>
 *
 * A PATH is a `.seal` file or a directory searched recursively for them. The `.java` files given,
 * or found there, are compiled by the JDK's javac with the program (docs/JAVA.md §11): those that
 * use none of its Seal classes first, so that Seal's code may use theirs, and those that do (a
 * Fabric mod's mixins) after them.
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.in, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        return run(args, java.io.InputStream.nullInputStream(), out, err);
    }

    /** Runs the command line with the program's standard input, output and error. */
    public static int run(String[] args, java.io.InputStream in, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 2) {
            err.println("usage: seal run|check|dump|build [--main Class] [--classpath PATH] [-d DIR] [--jar FILE]"
                    + " [--resources DIR] [--library] [-WautoCast] [-WautoCastLossy] [-Wassert] [-Wall] PATH...");
            return 2;
        }
        String command = args[0];
        String mainClass = "Main";
        Path stdlib = defaultStdlib();
        List<Path> paths = new ArrayList<>();
        Set<Warning> warn = EnumSet.noneOf(Warning.class);
        Path classDir = Path.of("classes");
        Path jar = null;
        Path resources = null;
        boolean asLibrary = false;
        List<Path> classPath = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--main") && i + 1 < args.length) {
                mainClass = args[++i];
            } else if ((args[i].equals("--classpath") || args[i].equals("-cp")) && i + 1 < args.length) {
                for (String entry : args[++i].split(java.io.File.pathSeparator)) {
                    if (!entry.isEmpty()) {
                        classPath.add(Path.of(entry));
                    }
                }
            } else if (args[i].equals("--vm")) {
                err.println(VM_REMOVED);
                return 2;
            } else if (args[i].equals("--jvm")) {
                // the only backend; kept so that older command lines still work
            } else if (args[i].equals("-d") && i + 1 < args.length) {
                classDir = Path.of(args[++i]);
            } else if (args[i].equals("--jar") && i + 1 < args.length) {
                jar = Path.of(args[++i]);
            } else if (args[i].equals("--resources") && i + 1 < args.length) {
                resources = Path.of(args[++i]);
            } else if (args[i].equals("--library")) {
                asLibrary = true;
            } else if (args[i].equals("--stdlib") && i + 1 < args.length) {
                stdlib = Path.of(args[++i]);
            } else if (args[i].equals("--no-stdlib")) {
                stdlib = null;
            } else if (args[i].equals("-Wall")) {
                warn.addAll(EnumSet.allOf(Warning.class));
            } else if (args[i].startsWith("-W")) {
                if (Warning.byFlag(args[i]).isEmpty()) {
                    err.println("unknown flag " + args[i] + "; the -W flags are "
                            + EnumSet.allOf(Warning.class).stream().map(Warning::flag).collect(Collectors.joining(", "))
                            + ", and -Wall for all of them");
                    return 2;
                }
                warn.add(Warning.byFlag(args[i]).get());
            } else {
                paths.add(Path.of(args[i]));
            }
        }

        Map<String, String> sources = new LinkedHashMap<>();
        List<Path> javaFiles = new ArrayList<>();
        for (Path p : paths) {
            readSources(p, sources, javaFiles);
        }
        Map<String, String> library = new LinkedHashMap<>();
        if (stdlib != null) {
            if (!Files.isDirectory(stdlib)) {
                err.println("standard library not found at " + stdlib + "; use --stdlib DIR or --no-stdlib");
                return 2;
            }
            readSources(stdlib, library);
        }

        for (Path entry : classPath) {
            if (!Files.exists(entry)) {
                err.println("class path entry not found: " + entry);
                return 2;
            }
        }
        if (javaFiles.isEmpty()) {
            return compile(command, sources, library, warn, null, mainClass, classDir, jar, resources, asLibrary,
                    classPath, in, out, err);
        }
        if (!JavaSources.available()) {
            err.println("compiling .java files needs javac, which this Java hasn't got: run seal with a JDK's java");
            return 2;
        }
        Map<String, String> sealClasses = sealClasses(sources);
        try (JavaSources java = JavaSources.sort(javaFiles, sealClasses.keySet())) {
            if (java.declaredTwice(sealClasses) instanceof String twice) {
                err.println(twice);
                return 1;
            }
            if (java.usesSeal() && !asLibrary && !command.equals("check")) {
                err.println(java.firstUsingSeal() + " uses Seal's classes, which only a library's .java files can (--library):"
                        + " Seal's code can't use its classes, so in a program nothing would run them (docs/JAVA.md §11)");
                return 2;
            }
            if (!java.compileBefore(classPath, err)) {
                return 1;
            }
            return compile(command, sources, library, warn, java, mainClass, classDir, jar, resources, asLibrary,
                    classPath, in, out, err);
        }
    }

    /** Checks the program, then runs, prints or writes it, with its .java files' classes if it has any. */
    private static int compile(String command, Map<String, String> sources, Map<String, String> library, Set<Warning> warn,
                               JavaSources java, String mainClass, Path classDir, Path jar, Path resources,
                               boolean asLibrary, List<Path> classPath, java.io.InputStream in, PrintStream out,
                               PrintStream err) throws IOException {
        List<Path> javaPath = new ArrayList<>(classPath);
        if (java != null && java.beforeClasses() != null) {
            javaPath.add(java.beforeClasses());
        }
        Checker checker = Checker.analyze(sources, library, warn,
                new JavaClasses(javaPath, java == null ? Map.of() : java.afterClasses()));
        checker.warnings().forEach(err::println);
        List<Diagnostic> errors = checker.diagnostics();
        // Checking .java files that use Seal's classes compiles them against Seal's, which takes generating those.
        boolean checkJava = java != null && java.usesSeal();
        if (!errors.isEmpty() || (command.equals("check") && !checkJava)) {
            errors.forEach(err::println);
            return errors.isEmpty() ? 0 : 1;
        }
        if (resources != null && !Files.isDirectory(resources)) {
            err.println("resources folder not found: " + resources);
            return 2;
        }
        if (asLibrary && !command.equals("build") && !command.equals("dump") && !command.equals("check")) {
            err.println("a library has no main to run; build it, or dump its classes");
            return 2;
        }
        return runOnJvm(command, checker, asLibrary || command.equals("check") ? null : mainClass, classDir, jar,
                resources, java, classPath, in, out, err);
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

    /** What `--vm` says, on standard error, now that Seal's VM is removed (docs/JVM.md §13). */
    public static final String VM_REMOVED =
            "--vm: Seal's VM is removed, and programs run on the JVM alone; run without --vm";

    /** `seal run`, `dump` and `build`: the program as JVM class files, run, printed or written (docs/JVM.md). */
    private static int runOnJvm(String command, Checker checker, String mainClass, Path classDir, Path jar, Path resources,
                                JavaSources java, List<Path> classPath, java.io.InputStream in, PrintStream out,
                                PrintStream err) throws IOException {
        JvmGen.Result result = mainClass == null ? JvmGen.generateLibrary(checker) : JvmGen.generate(checker, mainClass);
        if (!result.errors().isEmpty()) {
            result.errors().forEach(err::println);
            return 1;
        }
        if (java != null) {
            Map<String, byte[]> javaClasses = java.classes(result, classPath, err);
            if (javaClasses == null) {
                return 1;
            }
            Map<String, byte[]> all = new LinkedHashMap<>(result.classes());
            all.putAll(javaClasses);
            result = new JvmGen.Result(all, result.mainClass(), List.of());
        }
        if (command.equals("check")) {
            return 0;
        }
        if (command.equals("build") && jar != null) {
            JvmRunner.writeJar(result, jar, resources);
            return 0;
        }
        if (command.equals("build")) {
            JvmRunner.write(result, classDir);
            if (resources != null) {
                JvmRunner.copyResources(resources, classDir);
            }
            return 0;
        }
        if (command.equals("dump")) {
            out.print(ClassText.of(result.classes()));
            return 0;
        }
        if (!command.equals("run")) {
            err.println("unknown command " + command + "; the commands are run, check, dump and build");
            return 2;
        }
        JvmRunner.Outcome o = JvmRunner.run(result, in, out, err, true, classPath);
        if (o.fault() != null) {
            err.println(JvmRunner.describe(o.fault()));
        }
        return o.code();
    }

    /** Reads a `.seal` file, or every `.seal` file under a directory. */
    static void readSources(Path p, Map<String, String> into) throws IOException {
        readSources(p, into, null);
    }

    /** As above, and adds the `.java` files given, or under the directory, to {@code java} (docs/JAVA.md §11). */
    static void readSources(Path p, Map<String, String> into, List<Path> java) throws IOException {
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

    /** The `seal.stdlib` property, else a `stdlib` directory beside the compiler's class directory. */
    static Path defaultStdlib() {
        String prop = System.getProperty("seal.stdlib");
        if (prop != null) {
            return Path.of(prop);
        }
        try {
            Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = classes.toAbsolutePath().getParent().resolve("stdlib");
            return Files.isDirectory(candidate) ? candidate : Path.of("stdlib");
        } catch (Exception e) {
            return Path.of("stdlib");
        }
    }
}
