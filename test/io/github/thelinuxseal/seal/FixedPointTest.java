package io.github.thelinuxseal.seal;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.thelinuxseal.seal.check.Checker;

/**
 * Differential test for fixed-point types (§37.1): random formats and values
 * are run through the compiler, and every result is compared with a reference
 * implementation using exact integer arithmetic.
 * `java -cp out io.github.thelinuxseal.seal.FixedPointTest`.
 */
public final class FixedPointTest {

    private static final int CASES = 600;

    /** A format: N integer bits (including the sign bit if signed) and M fraction bits. */
    private record Format(int n, int m, boolean signed) {
        int width() {
            return n + m;
        }

        String type() {
            return (signed ? "fixed" : "ufixed") + "<<" + n + ", " + m + ">>";
        }

        /** Wraps an exact integer to this format's raw range. */
        BigInteger wrap(BigInteger raw) {
            BigInteger mod = BigInteger.ONE.shiftLeft(width());
            BigInteger r = raw.mod(mod);
            if (signed && r.testBit(width() - 1)) {
                r = r.subtract(mod);
            }
            return r;
        }

        BigInteger random(Random rnd) {
            BigInteger r = new BigInteger(width(), rnd);
            return wrap(r);
        }
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> library = new LinkedHashMap<>();
        Main.readSources(Path.of("stdlib"), library);
        // A fixed seed keeps the suite deterministic; pass a seed to explore other cases.
        Random rnd = new Random(args.length > 0 ? Long.parseLong(args[0]) : 20261013);

        StringBuilder body = new StringBuilder();
        List<String> expected = new ArrayList<>();
        List<String> described = new ArrayList<>();
        for (int i = 0; i < CASES; i++) {
            Format f = randomFormat(rnd);
            BigInteger a = f.random(rnd);
            BigInteger b = f.random(rnd);
            String op = switch (rnd.nextInt(6)) {
                case 0 -> "+";
                case 1 -> "-";
                case 2, 3 -> "*";
                case 4 -> "/";
                default -> "cast";
            };
            if (op.equals("/") && b.signum() == 0) {
                op = "*";
            }
            body.append("        ").append(f.type()).append(" a").append(i).append(" = ").append(literal(a, f)).append(";\n");
            body.append("        ").append(f.type()).append(" b").append(i).append(" = ").append(literal(b, f)).append(";\n");
            String expr;
            String result;
            if (op.equals("cast")) {
                Format g = randomFormat(rnd);
                expr = "(" + g.type() + ")a" + i;
                result = show(g.wrap(rescaleNearest(a, g.m() - f.m())), g);
                op = "cast to " + g.type();
            } else {
                expr = "a" + i + " " + op + " b" + i;
                result = show(compute(op, a, b, f), f);
            }
            body.append("        System.stdout.println(").append(expr).append(");\n");
            expected.add(result);
            described.add(f.type() + ": " + show(a, f) + " " + op + " " + show(b, f));
        }

        String program = "struct Main {\n    static int main() {\n" + body + "        return 0;\n    }\n}\n";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Main.seal", program);
        Checker checker = Checker.analyze(files, library);
        if (!checker.diagnostics().isEmpty()) {
            System.out.println("0 passed, 1 failed");
            System.out.println("FAIL: generated program does not compile: " + checker.diagnostics().subList(0,
                    Math.min(5, checker.diagnostics().size())));
            System.exit(1);
        }
        JvmRun.Outcome o = JvmRun.run(checker, "Main");
        if (o.error() != null || o.fault() != null) {
            System.out.println("0 passed, 1 failed");
            System.out.println("FAIL: the generated program: " + o);
            System.exit(1);
        }
        String[] lines = o.output().split("\n", -1);

        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < CASES; i++) {
            String got = i < lines.length ? lines[i] : "(missing)";
            if (got.equals(expected.get(i))) {
                passed++;
            } else {
                failures.add(described.get(i) + "\n    expected " + expected.get(i) + "\n    got      " + got);
            }
        }
        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.stream().limit(10).forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static Format randomFormat(Random rnd) {
        boolean signed = rnd.nextBoolean();
        // Favor small and boundary widths, but cover everything up to 64.
        int width = switch (rnd.nextInt(4)) {
            case 0 -> 1 + rnd.nextInt(8);
            case 1 -> 60 + rnd.nextInt(5);
            default -> 1 + rnd.nextInt(64);
        };
        if (signed && width < 1) {
            width = 1;
        }
        int m = rnd.nextInt(width + 1);
        int n = width - m;
        if (signed && n < 1) {
            n = 1;
            m = width - 1;
        }
        return new Format(n, m, signed);
    }

    /** Reference arithmetic on raw values: exact results, rounded to nearest (ties up), then wrapped. */
    private static BigInteger compute(String op, BigInteger a, BigInteger b, Format f) {
        BigInteger scale = BigInteger.ONE.shiftLeft(f.m());
        return f.wrap(switch (op) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> roundDiv(a.multiply(b), scale);
            case "/" -> roundDiv(a.multiply(scale), b);
            default -> throw new IllegalStateException(op);
        });
    }

    /** round(x / y), ties toward positive infinity. */
    private static BigInteger roundDiv(BigInteger x, BigInteger y) {
        // floor((2x + y) / 2y)
        BigInteger num = x.shiftLeft(1).add(y);
        BigInteger den = y.shiftLeft(1);
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        if (qr[1].signum() != 0 && qr[1].signum() != den.signum()) {
            q = q.subtract(BigInteger.ONE);
        }
        return q;
    }

    private static BigInteger rescaleNearest(BigInteger raw, int shift) {
        return shift >= 0 ? raw.shiftLeft(shift) : roundDiv(raw, BigInteger.ONE.shiftLeft(-shift));
    }

    /** The exact value as printed by System.stdout: plain decimal with at least one fraction digit. */
    private static String show(BigInteger raw, Format f) {
        BigDecimal v = new BigDecimal(raw).divide(new BigDecimal(BigInteger.ONE.shiftLeft(f.m()))).stripTrailingZeros();
        String s = v.toPlainString();
        return v.scale() <= 0 ? s + ".0" : s;
    }

    /** A literal that converts exactly to the raw value (it is the exact decimal expansion). */
    private static String literal(BigInteger raw, Format f) {
        return show(raw, f);
    }
}
