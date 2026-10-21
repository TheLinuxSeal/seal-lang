package io.github.thelinuxseal.seal;

import java.io.IOException;
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

/**
 * Mutation fuzzing of the whole pipeline. Programs from examples/ and
 * test/fuzz/ are mutated (statements duplicated, deleted, wrapped in loops
 * and blocks; numbers and operators changed) and every mutant that compiles
 * is run, its loops counted, so that one that never ends stops. Invariants:
 *
 * <ol>
 *   <li>no internal crash in the checker or the JVM backend, and classes the
 *       bytecode verifier accepts;</li>
 *   <li>no fault saying the generated classes are wrong (a method or field
 *       the JVM can't find, as the verifier doesn't see);</li>
 *   <li>no fault of an address in a program that doesn't use addr;</li>
 *   <li>an end, by the step limit at the latest.</li>
 * </ol>
 *
 * `java -cp out io.github.thelinuxseal.seal.FuzzTest [rounds] [seed]`. The default (2 rounds, about 80 mutants per
 * program) keeps ./test.sh quick; use more rounds and other seeds for longer runs.
 */
public final class FuzzTest {

    private record Program(String name, Map<String, String> files, List<String> mutable) {
    }

    /** How a program went: an internal problem; else, if it compiled, the fault that stopped it, if any. */
    private record Outcome(String error, boolean compiled, String fault) {
    }

    /** Rounds of loops a mutant may run: enough for the heaviest example, and mutants that loop forever still stop. */
    private static final long STEPS = 20_000_000;

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
            if (!base.compiled() || base.error() != null || base.fault() != null) {
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
                }
                String problem = judge(o, mutated);
                if (problem != null && failures.size() < 20) {
                    failures.add(p.name() + ": " + problem + "\n----- mutated " + target + " -----\n" + mutated);
                } else if (problem != null) {
                    failures.add(p.name() + ": " + problem);
                }
            }
        }
        System.out.println((runs - failures.size()) + " passed, " + failures.size() + " failed"
                + "  (" + runs + " mutants, " + compiled + " compiled and ran, " + skippedBaselines + " programs skipped)");
        failures.stream().limit(5).forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Null if the outcome is acceptable, otherwise a description of the broken invariant. */
    private static String judge(Outcome o, String source) {
        if (o.error() != null) {
            return "internal problem: " + o.error();
        }
        if (!o.compiled() || o.fault() == null) {
            return null;
        }
        if (o.fault().startsWith("the generated classes are wrong")) {
            return o.fault();
        }
        boolean lowLevel = source.contains("addr<") || source.contains("delete ");
        boolean memory = o.fault().startsWith("null address") || o.fault().contains("outside memory")
                || o.fault().contains("outside its allocation") || o.fault().startsWith("misaligned")
                || o.fault().contains("not allocated") || o.fault().contains("different allocations");
        if (memory && !lowLevel) {
            return "an address fault without addr: " + o.fault();
        }
        return null;
    }

    private static Outcome run(Map<String, String> files, Map<String, String> library) {
        Checker c;
        try {
            c = Checker.analyze(files, library);
        } catch (Throwable t) {
            return new Outcome("the checker crashed: " + JvmRun.stack(t), false, null);
        }
        if (!c.diagnostics().isEmpty()) {
            return new Outcome(null, false, null);
        }
        // A mutant must not write or delete real files.
        JvmRun.Outcome o = JvmRun.run(c, "Main", java.io.InputStream.nullInputStream(), false, STEPS);
        if (o.error() != null && o.error().startsWith("errors: ")) {
            return new Outcome(null, false, null); // what the backend reports, as instantiating a generic with a type it can't take
        }
        return new Outcome(o.error(), true, o.fault());
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
