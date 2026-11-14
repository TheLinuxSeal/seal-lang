import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

/**
 * Runs the benchmarks (benchmarks/README.md): builds seal/ with the repository's compiler and
 * java/ with javac, then runs each kernel in a JVM of its own, Seal's and Java's in turn, and
 * prints a table of the median times. Run from the project root:
 *
 * <pre>
 * java benchmarks/Run.java                       every kernel, 3 rounds
 * java benchmarks/Run.java matmul sortLib        only these
 * java benchmarks/Run.java --rounds 1 --runs 8   quicker, rougher
 * </pre>
 *
 * Exits with 1 if a kernel's checksums differ between the two, or one fails.
 */
public final class Run {

    /** Every kernel, in the order of the table. Both programs have each, by this name. */
    static final List<String> KERNELS = List.of(
            "intArith", "intDiv", "longArith", "shortArith", "uintArith",
            "doubleArith", "doubleMath", "floatMath",
            "intArray", "doubleArray", "matmul", "byteArray", "sieve", "sortLib", "sortHeap", "sortDoubles", "fixedArray",
            "listInt", "hashMap", "stringBuilder", "stringConcat",
            "virtualCalls", "allocObjects", "structArray", "structLocals",
            "fixedMandel", "doubleMandel", "recursion");

    /** The same for both: a fixed heap, so that growing it isn't timed. */
    static final List<String> JVM_FLAGS = List.of("-Xms2g", "-Xmx2g");

    static final long TIMEOUT_SECONDS = 600;

    record Result(List<Double> medians, String checksum, String failure) {
    }

    public static void main(String[] args) throws Exception {
        int rounds = 3;
        int runs = 15;
        int warmup = 5;
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rounds" -> rounds = Integer.parseInt(args[++i]);
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                default -> {
                    if (args[i].startsWith("-") || !KERNELS.contains(args[i])) {
                        System.err.println("usage: java benchmarks/Run.java [--rounds N] [--runs N] [--warmup N] [kernel...]");
                        System.err.println("kernels: " + String.join(" ", KERNELS));
                        System.exit(2);
                    }
                    chosen.add(args[i]);
                }
            }
        }
        if (rounds < 1 || warmup < 0 || warmup >= runs) {
            System.err.println("--rounds must be at least 1, and --warmup from 0 to less than --runs");
            System.exit(2);
        }
        List<String> kernels = chosen.isEmpty() ? KERNELS : chosen;
        if (!Files.isDirectory(Path.of("benchmarks/seal"))) {
            System.err.println("run from the project root: java benchmarks/Run.java");
            System.exit(2);
        }

        Path work = Files.createTempDirectory("seal-benchmarks");
        boolean ok;
        try {
            Map<String, Path> classes = build(work);
            String java = ProcessHandle.current().info().command().orElse("java");
            Map<String, Map<String, Result>> results = new LinkedHashMap<>();
            for (String lang : classes.keySet()) {
                results.put(lang, new LinkedHashMap<>());
            }
            for (int round = 0; round < rounds; round++) {
                for (String kernel : kernels) {
                    // Each language goes first in every other round, so that neither always runs on a warmer machine.
                    List<String> order = new ArrayList<>(classes.keySet());
                    if (round % 2 == 1) {
                        order = order.reversed();
                    }
                    for (String lang : order) {
                        Result before = results.get(lang).get(kernel);
                        if (before != null && before.failure() != null) {
                            continue;
                        }
                        Result now = runKernel(java, classes.get(lang), kernel, runs, warmup);
                        List<Double> medians = new ArrayList<>(before == null ? List.of() : before.medians());
                        if (now.failure() == null) {
                            medians.add(now.medians().getFirst());
                        }
                        results.get(lang).put(kernel, new Result(medians, now.checksum(), now.failure()));
                        System.err.printf(Locale.ROOT, "round %d/%d  %-14s %-5s %s%n", round + 1, rounds, kernel, lang,
                                now.failure() != null ? now.failure() : String.format(Locale.ROOT, "%.1f ms", now.medians().getFirst()));
                    }
                }
            }
            ok = report(kernels, results, rounds, runs, warmup);
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.exit(ok ? 0 : 1);
    }

    /** Compiles both programs into folders of work: seal/ with ./seal build, java/ with javac. */
    static Map<String, Path> build(Path work) throws IOException, InterruptedException {
        Path seal = work.resolve("seal");
        Path java = work.resolve("java");
        Process p = new ProcessBuilder("./seal", "build", "-d", seal.toString(), "benchmarks/seal")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            System.err.print(out);
            throw new IllegalStateException("seal build benchmarks/seal failed");
        }
        Files.createDirectories(java);
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", java.toString(), "benchmarks/java/Main.java");
        if (code != 0) {
            throw new IllegalStateException("javac benchmarks/java/Main.java failed");
        }
        Map<String, Path> classes = new LinkedHashMap<>();
        classes.put("java", java);
        classes.put("seal", seal);
        return classes;
    }

    /** One JVM running the kernel runs times: the median of the runs after the warm-up ones, in milliseconds. */
    static Result runKernel(String java, Path classes, String kernel, int runs, int warmup)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(java));
        command.addAll(JVM_FLAGS);
        command.addAll(List.of("-cp", classes.toString(), "Main"));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write((kernel + "\n" + runs + "\n").getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder text = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream s = p.getInputStream()) {
                text.append(new String(s.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The process was stopped; what it printed so far is in text.
            }
        });
        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join();
            return new Result(List.of(), null, "over " + TIMEOUT_SECONDS + " s");
        }
        reader.join();
        String[] lines = text.toString().strip().split("\n");
        if (p.exitValue() != 0 || lines.length != runs) {
            return new Result(List.of(), null, "failed: " + text.toString().strip().replace('\n', ' '));
        }
        double[] ms = new double[runs - warmup];
        String checksum = null;
        for (int i = 0; i < runs; i++) {
            String[] parts = lines[i].trim().split(" ");
            if (i >= warmup) {
                ms[i - warmup] = Long.parseLong(parts[0]) / 1e6;
            }
            if (checksum != null && !checksum.equals(parts[1])) {
                return new Result(List.of(), null, "its checksum changed between runs");
            }
            checksum = parts[1];
        }
        return new Result(List.of(median(ms)), checksum, null);
    }

    static double median(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    /** Prints the table, in Markdown; false if a kernel failed or its checksums differ. */
    static boolean report(List<String> kernels, Map<String, Map<String, Result>> results, int rounds, int runs, int warmup) {
        boolean ok = true;
        System.out.printf(Locale.ROOT, "%s, %s, %d processors; median of %d runs after %d to warm up, the median of %d rounds%n%n",
                cpu(), "Java " + Runtime.version(), Runtime.getRuntime().availableProcessors(), runs - warmup, warmup, rounds);
        System.out.println("| Kernel | Java (ms) | Seal (ms) | Seal ÷ Java |");
        System.out.println("| ------ | --------: | --------: | ----------: |");
        for (String kernel : kernels) {
            Result j = results.get("java").get(kernel);
            Result s = results.get("seal").get(kernel);
            String ratio;
            if (j.failure() != null || s.failure() != null) {
                ratio = "";
                ok = false;
            } else if (!j.checksum().equals(s.checksum())) {
                ratio = "checksums differ: " + j.checksum() + ", " + s.checksum();
                ok = false;
            } else {
                ratio = String.format(Locale.ROOT, "%.2f", median(s) / median(j));
            }
            System.out.printf(Locale.ROOT, "| %s | %s | %s | %s |%n", kernel, cell(j), cell(s), ratio);
        }
        return ok;
    }

    static double median(Result r) {
        return median(r.medians().stream().mapToDouble(Double::doubleValue).toArray());
    }

    static String cell(Result r) {
        return r.failure() != null ? r.failure() : String.format(Locale.ROOT, "%.1f", median(r));
    }

    /** The processor's name, where Linux says it. */
    static String cpu() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/cpuinfo"))) {
            return lines.filter(l -> l.startsWith("model name")).findFirst()
                    .map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse(System.getProperty("os.arch"));
        } catch (IOException e) {
            return System.getProperty("os.arch");
        }
    }
}
