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
import io.github.thelinuxseal.seal.codegen.CodeGen;
import io.github.thelinuxseal.seal.jvm.ClassText;
import io.github.thelinuxseal.seal.jvm.JvmGen;
import io.github.thelinuxseal.seal.jvm.JvmRunner;
import io.github.thelinuxseal.seal.vm.Trap;
import io.github.thelinuxseal.seal.vm.VM;

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
 *   --vm              run (or dump) on Seal's own VM instead; deprecated, to be removed (docs/JVM.md §13)
 *   -d DIR            where build writes the class files (default: classes)
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
 * A PATH is a `.seal` file or a directory searched recursively for them.
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
            err.println("usage: seal run|check|dump|build [--main Class] [--classpath PATH] [--vm] [-d DIR] [-WautoCast]"
                    + " [-WautoCastLossy] [-Wassert] [-Wall] PATH...");
            return 2;
        }
        String command = args[0];
        String mainClass = "Main";
        Path stdlib = defaultStdlib();
        List<Path> paths = new ArrayList<>();
        Set<Warning> warn = EnumSet.noneOf(Warning.class);
        boolean vm = false;
        Path classDir = Path.of("classes");
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
                vm = true;
            } else if (args[i].equals("--jvm")) {
                vm = false; // the default now; kept so that older command lines still work
            } else if (args[i].equals("-d") && i + 1 < args.length) {
                classDir = Path.of(args[++i]);
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
        for (Path p : paths) {
            readSources(p, sources);
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
        Checker checker = Checker.analyze(sources, library, warn, new JavaClasses(classPath));
        checker.warnings().forEach(err::println);
        List<Diagnostic> errors = checker.diagnostics();
        if (!errors.isEmpty() || command.equals("check")) {
            errors.forEach(err::println);
            return errors.isEmpty() ? 0 : 1;
        }
        if (!vm) {
            return runOnJvm(command, checker, mainClass, classDir, classPath, in, out, err);
        }
        if (command.equals("build")) {
            err.println("build writes JVM class files; it has no --vm form");
            return 2;
        }
        err.println(VM_DEPRECATED);
        return runOnVm(command, checker, mainClass, in, out, err);
    }

    /** What `--vm` prints first, on standard error. */
    public static final String VM_DEPRECATED =
            "warning: --vm is deprecated: the VM will be removed, and new features run only on the JVM";

    /** `seal run --vm` and `dump --vm`: the program on the deprecated VM (docs/JVM.md §13). */
    @SuppressWarnings("deprecation")
    private static int runOnVm(String command, Checker checker, String mainClass, java.io.InputStream in,
                               PrintStream out, PrintStream err) {
        CodeGen.Result result = CodeGen.generate(checker, mainClass);
        if (!result.errors().isEmpty()) {
            result.errors().forEach(err::println);
            return 1;
        }
        if (command.equals("dump")) {
            out.print(result.program().disassemble());
            return 0;
        }
        try {
            VM machine = new VM(result.program(), out);
            machine.setInput(in);
            machine.setError(err);
            long code = machine.run();
            out.flush();
            return (int) code;
        } catch (Trap t) {
            out.flush();
            err.println(t);
            return 70;
        }
    }

    /** `seal run`, `dump` and `build`: the program as JVM class files, run, printed or written (docs/JVM.md). */
    private static int runOnJvm(String command, Checker checker, String mainClass, Path classDir, List<Path> classPath,
                                java.io.InputStream in, PrintStream out, PrintStream err) throws IOException {
        JvmGen.Result result = JvmGen.generate(checker, mainClass);
        if (!result.errors().isEmpty()) {
            result.errors().forEach(err::println);
            return 1;
        }
        if (command.equals("build")) {
            JvmRunner.write(result, classDir);
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
        if (Files.isDirectory(p)) {
            try (Stream<Path> files = Files.walk(p)) {
                for (Path f : files.filter(f -> f.toString().endsWith(".seal")).sorted().toList()) {
                    into.put(f.toString(), Files.readString(f));
                }
            }
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
