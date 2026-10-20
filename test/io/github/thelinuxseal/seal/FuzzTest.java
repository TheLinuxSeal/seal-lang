package io.github.thelinuxseal.seal;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.codegen.CodeGen;
import io.github.thelinuxseal.seal.vm.Trap;
import io.github.thelinuxseal.seal.vm.VM;

/**
 * Mutation fuzzing of the whole pipeline. Programs from examples/ and
 * test/fuzz/ are mutated (statements duplicated, deleted, wrapped in loops
 * and blocks; numbers and operators changed) and every mutant that compiles
 * is run. Invariants:
 *
 * <ol>
 *   <li>no internal crash in the checker, code generator or VM;</li>
 *   <li>a program that finishes leaves no live objects (programs whose
 *       unmutated version leaves some, through cycles or statics, are skipped);</li>
 *   <li>no "stale handle" in a program that never deletes (an object was
 *       freed while still referenced);</li>
 *   <li>no low-level memory errors in a program that doesn't use addr.</li>
 * </ol>
 *
 * `java -cp out io.github.thelinuxseal.seal.FuzzTest [rounds] [seed]`. The default (2 rounds, about 80 mutants per
 * program) keeps ./test.sh quick; use more rounds and other seeds for longer runs.
 */
@SuppressWarnings("deprecation") // runs programs on the deprecated VM too, while it is kept
public final class FuzzTest {

    private record Program(String name, Map<String, String> files, List<String> mutable) {
    }

    private record Outcome(String error, String trap, int live, boolean compiled, Checker checker, String output,
                           long exit, Trap fault) {
        Outcome(String error, String trap, int live, boolean compiled) {
            this(error, trap, live, compiled, null, "", 0, null);
        }
    }

    /** A share of the mutants runs on the JVM too, and must end as on the VM (docs/JVM.md §12). */
    private static final OnJvm ON_JVM = new OnJvm();
    private static final int JVM_EVERY = Integer.getInteger("seal.jvm.every", 4);

    private static final String[] NUMBERS = {"0", "1", "2", "3", "7", "8", "-1", "64", "100"};
    private static final String[][] SWAPS = {{"+", "-"}, {"<", "<="}, {">", ">="}, {"==", "!="}, {"&&", "||"}, {"+=", "-="}};
    private static final Pattern STATEMENT = Pattern.compile("^(\\s{8,})(\\S.*;)\\s*$");

    public static void main(String[] args) throws IOException {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;
        Map<String, String> library = new LinkedHashMap<>();
        Main.readSources(Path.of("stdlib"), library);

        List<Program> programs = new ArrayList<>();
        for (String root : List.of("examples", "test/fuzz")) {
            try (Stream<Path> dirs = Files.list(Path.of(root))) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    Map<String, String> files = new LinkedHashMap<>();
                    Main.readSources(dir, files);
                    List<String> mutable = files.keySet().stream().filter(f -> f.endsWith("Main.seal")).toList();
                    programs.add(new Program(dir.toString(), files, mutable));
                }
            }
        }

        Random rnd = new Random(seed);
        int runs = 0;
        int compiled = 0;
        int skippedBaselines = 0;
        List<String> failures = new ArrayList<>();
        for (Program p : programs) {
            Outcome base = run(p.files(), library);
            if (!base.compiled() || base.error() != null || base.trap() != null || base.live() != 0) {
                skippedBaselines++;
                continue;
            }
            for (int r = 0; r < rounds * 40; r++) {
                Map<String, String> files = new LinkedHashMap<>(p.files());
                String target = p.mutable().get(rnd.nextInt(p.mutable().size()));
                String mutated = mutate(files.get(target), rnd);
                if (mutated == null) {
                    continue;
                }
                files.put(target, mutated);
                runs++;
                Outcome o = run(files, library);
                if (o.compiled()) {
                    compiled++;
                    if (compiled % JVM_EVERY == 0 && o.checker() != null) {
                        ON_JVM.compare(p.name() + ", a mutant of " + target + ":\n" + mutated, o.checker(), "Main",
                                java.io.InputStream.nullInputStream(), false, o.output(), o.exit(), o.fault());
                    }
                }
                String problem = judge(o, mutated);
                if (problem != null && failures.size() < 20) {
                    failures.add(p.name() + ": " + problem + "\n----- mutated " + target + " -----\n" + mutated);
                } else if (problem != null) {
                    failures.add(p.name() + ": " + problem);
                }
            }
        }
        List<String> jvmFailures = new ArrayList<>();
        String jvm = ON_JVM.finish(jvmFailures);
        failures.addAll(jvmFailures);
        System.out.println((runs - failures.size()) + " passed, " + failures.size() + " failed"
                + "  (" + runs + " mutants, " + compiled + " compiled and ran, " + skippedBaselines + " programs skipped)" + jvm);
        failures.stream().limit(5).forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Null if the outcome is acceptable, otherwise a description of the broken invariant. */
    private static String judge(Outcome o, String source) {
        if (o.error() != null) {
            return "internal crash: " + o.error();
        }
        if (!o.compiled()) {
            return null;
        }
        boolean deletes = source.contains("delete ");
        boolean lowLevel = source.contains("addr<") || deletes;
        if (o.trap() != null) {
            if (o.trap().startsWith("stale handle") && !deletes) {
                return "an object was freed while still referenced: " + o.trap();
            }
            boolean memory = o.trap().startsWith("null address") || o.trap().contains("outside memory")
                    || o.trap().startsWith("misaligned") || o.trap().contains("not allocated")
                    || o.trap().contains("outside the object");
            if (memory && !lowLevel) {
                return "memory error without addr: " + o.trap();
            }
            return null;
        }
        if (o.live() != 0) {
            return o.live() + " objects leaked";
        }
        return null;
    }

    private static Outcome run(Map<String, String> files, Map<String, String> library) {
        try {
            Checker c = Checker.analyze(files, library);
            if (!c.diagnostics().isEmpty()) {
                return new Outcome(null, null, 0, false);
            }
            CodeGen.Result g = CodeGen.generate(c, "Main");
            if (!g.errors().isEmpty()) {
                return new Outcome(null, null, 0, false);
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            VM vm = new VM(g.program(), new PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8));
            vm.setMaxSteps(40_000_000);   // enough for the heaviest example; mutants that loop forever still stop
            vm.setFileAccess(false);      // a mutant must not write or delete real files
            vm.setError(new PrintStream(OutputStream.nullOutputStream()));
            try {
                long code = vm.run();
                return new Outcome(null, null, vm.liveObjects(), true, c,
                        bytes.toString(java.nio.charset.StandardCharsets.UTF_8), code, null);
            } catch (Trap t) {
                return new Outcome(null, t.getMessage(), vm.liveObjects(), true, c,
                        bytes.toString(java.nio.charset.StandardCharsets.UTF_8), -1, t);
            }
        } catch (Throwable t) {
            StringBuilder where = new StringBuilder(t.toString());
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("lang.")) {
                    where.append("\n    at ").append(e);
                    if (where.length() > 600) {
                        break;
                    }
                }
            }
            return new Outcome(where.toString(), null, 0, false);
        }
    }

    /** One random mutation of a statement line, a number or an operator; null if none applies. */
    private static String mutate(String src, Random rnd) {
        String[] lines = src.split("\n", -1);
        List<Integer> statements = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (STATEMENT.matcher(lines[i]).matches() && !lines[i].trim().startsWith("//")) {
                statements.add(i);
            }
        }
        int kind = rnd.nextInt(7);
        if (kind <= 3 && !statements.isEmpty()) {
            int i = statements.get(rnd.nextInt(statements.size()));
            Matcher m = STATEMENT.matcher(lines[i]);
            m.matches();
            String indent = m.group(1);
            String stmt = m.group(2);
            switch (kind) {
                case 0 -> lines[i] = indent + stmt + " " + stmt;                                    // duplicate
                case 1 -> lines[i] = "";                                                             // delete
                case 2 -> lines[i] = indent + "for (int fz = 0; fz < 2; fz += 1) { " + stmt + " }"; // run twice
                default -> lines[i] = indent + "{ " + stmt + " }";                                 // new scope
            }
            return String.join("\n", lines);
        }
        if (kind <= 5) {
            Matcher nm = Pattern.compile("(?<![\\w.<])\\d+(?![\\w.])").matcher(src);
            List<int[]> spots = new ArrayList<>();
            while (nm.find()) {
                spots.add(new int[]{nm.start(), nm.end()});
            }
            if (spots.isEmpty()) {
                return null;
            }
            int[] s = spots.get(rnd.nextInt(spots.size()));
            return src.substring(0, s[0]) + NUMBERS[rnd.nextInt(NUMBERS.length)] + src.substring(s[1]);
        }
        String[] sw = SWAPS[rnd.nextInt(SWAPS.length)];
        boolean forward = rnd.nextBoolean();
        String from = " " + (forward ? sw[0] : sw[1]) + " ";
        String to = " " + (forward ? sw[1] : sw[0]) + " ";
        int idx = src.indexOf(from, rnd.nextInt(Math.max(1, src.length())));
        if (idx < 0) {
            idx = src.indexOf(from);
        }
        if (idx < 0) {
            return null;
        }
        return src.substring(0, idx) + to + src.substring(idx + from.length());
    }
}
