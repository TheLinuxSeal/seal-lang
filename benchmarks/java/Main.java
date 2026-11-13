// The Java side of the benchmarks (benchmarks/README.md): the kernels of ../seal/Main.seal,
// written as a Java programmer would, with the same checksums. Reads a kernel's name and how
// many times to run it from standard input, and prints "nanoseconds checksum" for each run.
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

public class Main {
    // ------------------------------------------------------------ integers

    static long intArith() {
        int x = 1;
        int acc = 0;
        for (int i = 0; i < 200_000_000; i++) {
            x = x * 1103515245 + 12345;
            x = x ^ (x >> 13);
            acc = acc + (x & 1023);
        }
        return acc + x;
    }

    static long intDiv() {
        int acc = 0;
        for (int i = 1; i < 50_000_000; i++) {
            acc = acc + (1_000_000_007 % i) + (i / 3);
        }
        return acc;
    }

    static long longArith() {
        long x = 1;
        long acc = 0;
        for (int i = 0; i < 200_000_000; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            x = x ^ (x >> 29);
            acc = acc + (x & 1023);
        }
        return acc + x;
    }

    static long shortArith() {
        short x = 1;
        int acc = 0;
        for (int i = 0; i < 200_000_000; i++) {
            x = (short) (x * 25173 + 13849);
            x = (short) (x ^ (x >> 5));
            acc = acc + (x & 255);
        }
        return acc + x;
    }

    static long uintArith() {
        int x = 1;
        int acc = 0;
        int high = 0;
        for (int i = 1; i < 50_000_000; i++) {
            x = x * 1664525 + 1013904223;
            int d = i;
            acc = acc + Integer.divideUnsigned(x, d);
            if (Integer.compareUnsigned(x, 0x80000000) > 0) { high = high + 1; }
        }
        return Integer.toUnsignedLong(acc) + Integer.toUnsignedLong(high);
    }

    // ------------------------------------------------------------ floating point

    static long doubleArith() {
        double sum = 0.0;
        double sign = 1.0;
        for (int i = 0; i < 100_000_000; i++) {
            sum = sum + sign / (2.0 * i + 1.0);
            sign = -sign;
        }
        return (long) (sum * 1000000000000.0);
    }

    static long doubleMath() {
        double acc = 0.0;
        for (int i = 0; i < 50_000_000; i++) {
            double x = i * 0.0000001;
            acc = acc + Math.sqrt(x) + ((((x * 0.3 + 0.2) * x + 0.1) * x - 0.5) * x + 1.0);
        }
        return (long) (acc * 1000.0);
    }

    static long floatMath() {
        float acc = 0.0f;
        for (int i = 0; i < 50_000_000; i++) {
            float x = (float) ((i % 10000) * 0.0001);
            acc = acc + (float) Math.sqrt(x) + ((((x * 0.3f + 0.2f) * x + 0.1f) * x - 0.5f) * x + 1.0f);
        }
        return (long) (acc * 10.0f);
    }

    // ------------------------------------------------------------ arrays

    static long intArray() {
        int n = 4_000_000;
        int[] a = new int[n];
        for (int i = 0; i < n; i++) { a[i] = (i * 7) ^ i; }
        long sum = 0;
        for (int pass = 0; pass < 25; pass++) {
            for (int i = 0; i < n; i++) { sum = sum + a[i]; }
            for (int i = 0; i < n; i++) { a[i] = a[i] * 3 + pass; }
        }
        return sum;
    }

    static long doubleArray() {
        int n = 2_000_000;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i * 0.5;
            y[i] = 1.0 / (i + 1);
        }
        double dot = 0.0;
        for (int pass = 0; pass < 25; pass++) {
            for (int i = 0; i < n; i++) { y[i] = 1.0001 * x[i] + y[i]; }
            for (int i = 0; i < n; i++) { dot = dot + x[i] * y[i]; }
        }
        return (long) (dot / 1000000000.0);
    }

    static long matmul() {
        int n = 400;
        double[] a = new double[n * n];
        double[] b = new double[n * n];
        double[] c = new double[n * n];
        for (int i = 0; i < n * n; i++) {
            a[i] = (i % 13) * 0.25;
            b[i] = (i % 7) * 0.5;
        }
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                double aik = a[i * n + k];
                for (int j = 0; j < n; j++) {
                    c[i * n + j] = c[i * n + j] + aik * b[k * n + j];
                }
            }
        }
        double sum = 0.0;
        for (int i = 0; i < n * n; i++) { sum = sum + c[i]; }
        return (long) sum;
    }

    static long byteArray() {
        int n = 8_000_000;
        byte[] data = new byte[n];
        int x = 7;
        for (int i = 0; i < n; i++) {
            x = x * 1664525 + 1013904223;
            data[i] = (byte) (x >>> 24);
        }
        long total = 0;
        for (int pass = 0; pass < 10; pass++) {
            int a = 1;
            int b = 0;
            for (int i = 0; i < n; i++) {
                a = (a + (data[i] & 0xFF)) % 65521;
                b = (b + a) % 65521;
            }
            total = total + ((b << 16) | a);
        }
        return total;
    }

    static long sieve() {
        int n = 20_000_000;
        boolean[] composite = new boolean[n + 1];
        int count = 0;
        for (int i = 2; i <= n; i++) {
            if (!composite[i]) {
                count++;
                if (i <= n / i) {
                    for (int j = i * i; j <= n; j += i) { composite[j] = true; }
                }
            }
        }
        return count;
    }

    static int[] randomInts(int n) {
        int[] a = new int[n];
        int x = 12345;
        for (int i = 0; i < n; i++) {
            x = x * 1103515245 + 12345;
            a[i] = x;
        }
        return a;
    }

    static long sortedSum(int[] a) {
        long sum = 0;
        for (int i = 0; i < a.length; i += 1000) { sum = sum * 31 + a[i]; }
        return sum;
    }

    static long sortLib() {
        int[] a = randomInts(1_000_000);
        Arrays.sort(a);
        return sortedSum(a);
    }

    static void siftDown(int[] a, int start, int end) {
        int root = start;
        while (root * 2 + 1 < end) {
            int child = root * 2 + 1;
            if (child + 1 < end && a[child] < a[child + 1]) { child++; }
            if (!(a[root] < a[child])) { return; }
            int t = a[root];
            a[root] = a[child];
            a[child] = t;
            root = child;
        }
    }

    static long sortHeap() {
        int[] a = randomInts(1_000_000);
        int n = a.length;
        for (int start = n / 2 - 1; start >= 0; start--) { siftDown(a, start, n); }
        for (int end = n - 1; end > 0; end--) {
            int t = a[0];
            a[0] = a[end];
            a[end] = t;
            siftDown(a, 0, end);
        }
        return sortedSum(a);
    }

    static long fixedArray() {
        int[] a = new int[1024];
        for (int i = 0; i < 1024; i++) { a[i] = i; }
        long total = 0;
        for (int pass = 0; pass < 50_000; pass++) {
            for (int i = 1; i < 1024; i++) { a[i] = a[i] + a[i - 1] - pass; }
            total = total + a[1023];
        }
        return total;
    }

    // ------------------------------------------------------------ collections and text

    static long listInt() {
        ArrayList<Integer> xs = new ArrayList<>();
        for (int i = 0; i < 5_000_000; i++) { xs.add(i ^ 0x5555); }
        long sum = 0;
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 0; i < xs.size(); i++) { sum = sum + xs.get(i); }
            for (int i = 0; i < xs.size(); i++) { xs.set(i, xs.get(i) + 1); }
        }
        return sum;
    }

    static long hashMap() {
        HashMap<Integer, Integer> m = new HashMap<>();
        int n = 500_000;
        int x = 1;
        for (int i = 0; i < n; i++) {
            x = x * 1103515245 + 12345;
            m.put(x, i);
        }
        long sum = 0;
        x = 1;
        for (int i = 0; i < n; i++) {
            x = x * 1103515245 + 12345;
            sum = sum + m.get(x);
            sum = sum + m.getOrDefault(i, -1);
        }
        return sum + m.size();
    }

    static long stringBuilder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1_000_000; i++) {
            sb.append(i);
            sb.append(',');
        }
        String s = sb.toString();
        long sum = s.length();
        for (int i = 0; i < s.length(); i++) { sum = sum + s.charAt(i); }
        return sum;
    }

    static long stringConcat() {
        long total = 0;
        for (int i = 0; i < 1_000_000; i++) {
            String s = "item" + i + "/" + (i * 3);
            total = total + s.length() + s.charAt(s.length() - 1);
        }
        return total;
    }

    // ------------------------------------------------------------ objects

    interface Shape { double area(); }

    static final class Circle implements Shape {
        final double r;
        Circle(double radius) { r = radius; }
        public double area() { return 3.14159 * r * r; }
    }

    static final class Square implements Shape {
        final double s;
        Square(double side) { s = side; }
        public double area() { return s * s; }
    }

    static final class Tri implements Shape {
        final double b, h;
        Tri(double base, double height) { b = base; h = height; }
        public double area() { return 0.5 * b * h; }
    }

    static long virtualCalls() {
        int n = 1_000_000;
        Shape[] shapes = new Shape[n];
        for (int i = 0; i < n; i++) {
            int k = i % 3;
            if (k == 0) { shapes[i] = new Circle(i % 10); }
            else if (k == 1) { shapes[i] = new Square(i % 10); }
            else { shapes[i] = new Tri(i % 10, i % 7); }
        }
        double total = 0.0;
        for (int pass = 0; pass < 20; pass++) {
            for (int i = 0; i < n; i++) { total = total + shapes[i].area(); }
        }
        return (long) total;
    }

    static final class Pair {
        final int a, b;
        Pair(int x, int y) { a = x; b = y; }
        int sum() { return a + b; }
    }

    static long allocObjects() {
        Pair[] ring = new Pair[1024];
        for (int i = 0; i < 1024; i++) { ring[i] = new Pair(0, 0); }
        long acc = 0;
        for (int i = 0; i < 20_000_000; i++) {
            ring[i & 1023] = new Pair(i, i + 1);
            acc = acc + ring[(i + 512) & 1023].sum();
        }
        return acc;
    }

    static final class Particle {
        double x, y, vx, vy;
        Particle(double x0, double y0, double vx0, double vy0) { x = x0; y = y0; vx = vx0; vy = vy0; }
    }

    static long structArray() {
        int n = 200_000;
        Particle[] ps = new Particle[n];
        for (int i = 0; i < n; i++) { ps[i] = new Particle(i, -i, (i % 7) * 0.1, (i % 5) * 0.2); }
        for (int step = 0; step < 50; step++) {
            for (int i = 0; i < n; i++) {
                Particle p = ps[i];
                p.x = p.x + p.vx * 0.01;
                p.y = p.y + p.vy * 0.01;
                if (p.x > 1000.0) { p.vx = -p.vx; }
            }
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) { sum = sum + ps[i].x + ps[i].y; }
        return (long) sum;
    }

    record Vec2(double x, double y) {
        Vec2 add(Vec2 o) { return new Vec2(x + o.x, y + o.y); }
        Vec2 sub(Vec2 o) { return new Vec2(x - o.x, y - o.y); }
        Vec2 scale(double k) { return new Vec2(x * k, y * k); }
    }

    static long structLocals() {
        Vec2 pos = new Vec2(0.0, 0.0);
        Vec2 vel = new Vec2(1.0, 0.5);
        for (int i = 0; i < 20_000_000; i++) {
            pos = pos.add(vel.scale(0.001));
            vel = vel.sub(pos.scale(0.0001));
        }
        return (long) ((pos.x() + pos.y()) * 1000000.0);
    }

    // ------------------------------------------------------------ mixed

    /** Fixed point with 22 fraction bits, by hand: the product rounded to nearest. */
    static int fmul(int a, int b) { return (int) (((long) a * b + (1L << 21)) >> 22); }

    static long fixedMandel() {
        long total = 0;
        int step = (int) Math.round(0.005 * (1 << 22));
        for (int row = 0; row < 400; row++) {
            int ci = fmul(row << 22, step) - (1 << 22);
            for (int col = 0; col < 600; col++) {
                int cr = fmul(col << 22, step) - (2 << 22);
                int zr = 0;
                int zi = 0;
                int i = 0;
                while (i < 100) {
                    int zr2 = fmul(zr, zr);
                    int zi2 = fmul(zi, zi);
                    if (zr2 + zi2 > (4 << 22)) { break; }
                    zi = fmul(zr, zi) * 2 + ci;
                    zr = zr2 - zi2 + cr;
                    i++;
                }
                total = total + i;
            }
        }
        return total;
    }

    static long doubleMandel() {
        long total = 0;
        for (int row = 0; row < 400; row++) {
            double ci = row * 0.005 - 1.0;
            for (int col = 0; col < 600; col++) {
                double cr = col * 0.005 - 2.0;
                double zr = 0.0;
                double zi = 0.0;
                int i = 0;
                while (i < 100) {
                    double zr2 = zr * zr;
                    double zi2 = zi * zi;
                    if (zr2 + zi2 > 4.0) { break; }
                    zi = zr * zi * 2.0 + ci;
                    zr = zr2 - zi2 + cr;
                    i++;
                }
                total = total + i;
            }
        }
        return total;
    }

    static int fib(int n) {
        if (n < 2) { return n; }
        return fib(n - 1) + fib(n - 2);
    }

    static long recursion() { return fib(35); }

    // ------------------------------------------------------------ harness

    /** The kernel of this name's checksum; -1 for a name that isn't one. */
    static long run(String name) {
        return switch (name) {
            case "intArith" -> intArith();
            case "intDiv" -> intDiv();
            case "longArith" -> longArith();
            case "shortArith" -> shortArith();
            case "uintArith" -> uintArith();
            case "doubleArith" -> doubleArith();
            case "doubleMath" -> doubleMath();
            case "floatMath" -> floatMath();
            case "intArray" -> intArray();
            case "doubleArray" -> doubleArray();
            case "matmul" -> matmul();
            case "byteArray" -> byteArray();
            case "sieve" -> sieve();
            case "sortLib" -> sortLib();
            case "sortHeap" -> sortHeap();
            case "fixedArray" -> fixedArray();
            case "listInt" -> listInt();
            case "hashMap" -> hashMap();
            case "stringBuilder" -> stringBuilder();
            case "stringConcat" -> stringConcat();
            case "virtualCalls" -> virtualCalls();
            case "allocObjects" -> allocObjects();
            case "structArray" -> structArray();
            case "structLocals" -> structLocals();
            case "fixedMandel" -> fixedMandel();
            case "doubleMandel" -> doubleMandel();
            case "recursion" -> recursion();
            default -> -1;
        };
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String name = in.readLine().trim();
        int runs = Integer.parseInt(in.readLine().trim());
        for (int r = 0; r < runs; r++) {
            long t0 = System.nanoTime();
            long check = run(name);
            if (check == -1) {
                System.out.println("no kernel " + name);
                System.exit(1);
            }
            long t1 = System.nanoTime();
            System.out.println((t1 - t0) + " " + check);
        }
    }
}
