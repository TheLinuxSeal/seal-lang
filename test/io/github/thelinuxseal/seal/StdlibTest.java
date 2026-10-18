package io.github.thelinuxseal.seal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.codegen.CodeGen;
import io.github.thelinuxseal.seal.vm.Trap;
import io.github.thelinuxseal.seal.vm.VM;

/** Tests for the standard library, run from the project root: `java -cp out io.github.thelinuxseal.seal.StdlibTest`. */
public final class StdlibTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();
    private static Map<String, String> library;

    public static void main(String[] args) throws IOException {
        library = new LinkedHashMap<>();
        Main.readSources(Path.of("stdlib"), library);
        // Probe.live(): how many class objects are live, through a native only the VM's tests declare.
        library.put("Probe.seal", "struct Probe { @native(\"live_objects\") public static int live() { } }");

        strings();
        integers();
        exactIntegers();
        chars();
        math();
        random();
        lists();
        queues();
        maps();
        arrays();
        bigints();
        fixedPoint();
        fixedUtilities();
        failures();
        conflicts();
        ownership();
        stringSearching();
        standardStreams();
        hashMaps();
        files();
        filesOutsideTheProgram();

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(f -> System.out.println("FAIL: " + f));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================================================================ tests

    private static void stringSearching() {
        run("endsWith, lastIndexOf, indexOf and contains of strings", """
                string s = "docs/report.final.txt";
                System.stdout.println("" + s.endsWith(".txt") + s.endsWith("doc") + s.endsWith("") + " " + s.lastIndexOf('.')
                        + " " + s.lastIndexOf('#') + " " + s.indexOf("report") + " " + s.indexOf("xyz") + " "
                        + s.contains("final") + s.contains('/'));
                """, "truefalsetrue 17 -1 5 -1 truetrue\n");
        run("split, lines and trim", """
                for (string part : "a,,b,".split(',')) { System.stdout.print("[" + part + "]"); }
                System.stdout.println();
                for (string line : "one\\r\\ntwo\\n\\nthree\\n".lines()) { System.stdout.print("<" + line + ">"); }
                System.stdout.println(" " + "".lines().size() + " " + "x".split(',').size());
                System.stdout.println("[" + "  \\t padded \\n".trim() + "]" + "[" + "   ".trim() + "]");
                """, "[a][][b][]\n<one><two><><three> 0 1\n[padded][]\n");
    }

    private static void files() {
        // Each test works in a new temporary folder and deletes it at the end.
        run("whole files: text, lines, appending, bytes", """
                string dir = Files.createTempDirectory("seal-test");
                string f = Files.join(dir, "a.txt");
                Files.writeString(f, "héllo\\nwörld");
                Files.appendString(f, "\\n!");
                System.stdout.println(Files.readString(f) + " " + Files.size(f));
                List<string> lines = Files.readAllLines(f);
                lines.add("last");
                Files.writeLines(f, lines);
                System.stdout.println(Files.readAllLines(f).size() + " " + Files.readString(f).endsWith("last\\n"));
                List<uint<<8>>> bytes = Files.readBytes(f);
                System.stdout.println(bytes.size() + " " + bytes.get(0) + " " + bytes.get(1) + " " + bytes.get(2));
                bytes.clear();
                bytes.add(0); bytes.add(200);
                Files.writeBytes(f, bytes);
                System.stdout.println(Files.size(f) + " " + Files.readBytes(f).get(1));
                Files.deleteRecursively(dir);
                System.stdout.println(Files.exists(dir));
                """, "héllo\nwörld\n! 15\n4 true\n21 104 195 169\n2 200\nfalse\n");
        run("folders: making, listing, walking, copying, moving, deleting", """
                string dir = Files.createTempDirectory("seal-test");
                Files.createDirectories(dir + "/a/b");
                Files.createDirectory(dir + "/c");
                Files.writeString(dir + "/a/one.txt", "1");
                Files.writeString(dir + "/a/b/two.txt", "22");
                Files.copy(dir + "/a/one.txt", dir + "/c/three.txt");
                Files.move(dir + "/c/three.txt", dir + "/c/four.txt");
                for (string p : Files.walk(dir)) {
                    System.stdout.print(p.substring(dir.length(), p.length()) + (Files.isDirectory(p) ? "/ " : " "));
                }
                System.stdout.println();
                for (string p : Files.list(dir)) { System.stdout.print(new File(p).getName() + " "); }
                System.stdout.println(Files.isFile(dir + "/a/one.txt") + " " + Files.isDirectory(dir + "/a"));
                Files.delete(dir + "/c/four.txt");
                System.stdout.println(Files.deleteIfExists(dir + "/c/four.txt") + " " + Files.deleteIfExists(dir + "/c"));
                Files.deleteRecursively(dir);
                System.stdout.println(Files.exists(dir));
                """, "/ /a/ /a/b/ /a/b/two.txt /a/one.txt /c/ /c/four.txt \na c true true\nfalse true\nfalse\n");
        run("File: names, parents, questions and changes", """
                string dir = Files.createTempDirectory("seal-test");
                File d = new File(dir + "/");
                File f = new File(d, "notes.v2.txt");
                System.stdout.println(f.getName() + " " + f.getExtension() + " " + (f.getParent() == dir) + " " + f.exists());
                Files.writeString(f.getPath(), "abc");
                System.stdout.println(f.exists() + " " + f.isFile() + " " + f.length() + " " + (f.lastModified() > 0));
                File sub = new File(dir, "x/y");
                System.stdout.println(sub.mkdir() + " " + sub.mkdirs() + " " + sub.isDirectory() + " " + sub.mkdirs());
                System.stdout.println(f.renameTo(new File(sub, "moved.txt")) + " " + f.exists());
                List<File> all = d.walk();
                System.stdout.println(all.size() + " " + d.list().size() + " " + d.listFiles().get(0).getName());
                System.stdout.println(new File(dir + "/x").delete() + " " + new File("report").getExtension().isEmpty()
                        + " " + (new File("a/b") == new File("a/b")) + " " + (new File("a.txt").getParentFile() == null));
                Files.deleteRecursively(dir);
                """, "notes.v2.txt txt true false\ntrue true 3 true\nfalse true true false\ntrue false\n4 1 x\nfalse true true true\n");
        run("reading and writing a line at a time", """
                string dir = Files.createTempDirectory("seal-test");
                string f = dir + "/log.txt";
                FileWriter out = new FileWriter(f);
                out.writeLine("one");
                out.write("tw");
                out.write("o");
                out.close();
                try (FileWriter more = new FileWriter(f, true)) {
                    more.writeLine("");
                    more.writeLine("three");
                }
                FileReader in = new FileReader(f);
                while (in.hasNextLine()) { System.stdout.print("[" + in.readLine() + "]"); }
                in.close();
                System.stdout.println();
                for (string line : new FileReader(f)) { System.stdout.print(line.length()); }
                System.stdout.println();
                FileReader empty = new FileReader(f);
                empty.close();
                System.stdout.println(empty.hasNextLine());
                try { out.write("late"); } catch (IOException e) { System.stdout.println(e.getMessage()); }
                Files.deleteRecursively(dir);
                """, "[one][two][three]\n335\nfalse\nthe file is closed\n");
        run("errors are IOExceptions naming the file", """
                string dir = Files.createTempDirectory("seal-test");
                try { Files.readString(dir + "/missing.txt"); }
                catch (FileNotFoundException e) { System.stdout.println("not found " + e.getMessage().endsWith("/missing.txt")); }
                try { FileReader r = new FileReader(dir + "/missing.txt"); }
                catch (IOException e) { System.stdout.println("reader " + (e instanceof FileNotFoundException)); }
                Files.writeString(dir + "/f", "x");
                try { Files.createDirectory(dir + "/f"); } catch (IOException e) { System.stdout.println(e.getMessage().startsWith("already exists")); }
                try { Files.list(dir + "/f"); } catch (IOException e) { System.stdout.println(e.getMessage().startsWith("not a folder")); }
                try { Files.delete(dir); } catch (IOException e) { System.stdout.println(e.getMessage().startsWith("folder is not empty")); }
                Files.deleteRecursively(dir);
                """, "not found true\nreader true\ntrue\ntrue\ntrue\n");
    }

    private static void hashMaps() {
        run("HashMap: counting, ordering, removing, entries", """
                HashMap<string, int> counts = new HashMap();
                for (string w : "the cat and the dog and the bird".split(' ')) {
                    counts.put(w, counts.getOrDefault(w, 0) + 1);
                }
                counts.forEach((w, n) -> System.stdout.print(w + "=" + n + " "));
                System.stdout.println(counts.size() + " " + counts.get("the") + " " + counts.containsKey("cow"));
                System.stdout.println(counts.remove("and") + " " + counts.remove("and") + " " + counts.keys().size());
                for (HashMap.Entry<string, int> e : counts.entries()) { System.stdout.print(e.getKey() + ":" + e.getValue() + " "); }
                System.stdout.println();
                counts.put("cat", 10);
                System.stdout.println(counts.values().get(1));
                counts.clear();
                System.stdout.println(counts.isEmpty() + " " + counts.getOrDefault("the", -1));
                """, "the=3 cat=1 and=2 dog=1 bird=1 5 3 false\ntrue false 4\nthe:3 cat:1 dog:1 bird:1 \n10\ntrue -1\n");
        run("HashMap: many keys, removals and rebuilds", """
                HashMap<int, string> names = new HashMap();
                for (int i = 0; i < 1000; i += 1) { names.put(i * 7, "n" + i); }
                for (int i = 0; i < 1000; i += 2) { names.remove(i * 7); }
                System.stdout.println(names.size() + " " + names.get(7 * 999) + " " + names.containsKey(14));
                names.put(14, "back");
                System.stdout.println(names.keys().get(names.size() - 1) + " " + names.values().get(0) + " " + names.get(14));
                """, "500 n999 false\n14 n1 back\n");
        run("HashMap keys: chars, doubles, objects by identity, objects by their own ==", """
                HashMap<char, int> letters = new HashMap();
                for (char c : "hello") { letters.put(c, letters.getOrDefault(c, 0) + 1); }
                HashMap<double, bit> seen = new HashMap();
                seen.put(0.5, true);
                seen.put(-0.0, true);
                Object a = new Object();
                HashMap<Object, int> ids = new HashMap();
                ids.put(a, 1);
                ids.put(new Object(), 2);
                HashMap<Pt, string> places = new HashMap();
                places.put(new Pt(1, 2), "home");
                System.stdout.println(letters.get('l') + " " + letters.size() + " " + seen.containsKey(0.0) + " "
                        + ids.get(a) + " " + ids.size() + " " + places.get(new Pt(1, 2)) + " " + places.containsKey(new Pt(2, 1)));
                """, "2 4 true 1 2 home false\n");
        run("HashMap get of a missing key throws", """
                HashMap<string, int> m = new HashMap();
                try { m.get("x"); } catch (NoSuchElementException e) { System.stdout.println(e.getMessage()); }
                """, "HashMap.get: the key isn't in the map\n");
    }

    private static void standardStreams() {
        run("System.stdout prints every kind of value", """
                int<<8>> small = -3;
                uint<<64>> big = 18446744073709551615;
                fixed<<8, 8>> f = 1.5;
                System.stdout.print("s"); System.stdout.print('c'); System.stdout.print(1); System.stdout.print(small);
                System.stdout.print(big); System.stdout.print(2.5); System.stdout.print(true); System.stdout.println(f);
                System.stdout.println("line");
                System.stdout.println();
                System.stderr.flush();
                System.stdout.println("in order");
                """, "sc1-3184467440737095516152.5true1.5\nline\n\nin order\n");
        run("a Writer parameter takes stdout or a file", """
                string dir = Files.createTempDirectory("seal-test");
                FileWriter file = new FileWriter(dir + "/out.txt");
                List<Writer> outs = new List();
                outs.add(System.stdout);
                outs.add(file);
                for (Writer w : outs) { w.println("same text"); }
                file.close();
                System.stdout.print(Files.readString(dir + "/out.txt"));
                Files.deleteRecursively(dir);
                """, "same text\nsame text\n");
        run("static objects are released when the program ends, after main", """
                Keep.thing = new Res(7);
                System.stdout.print("main done");
                """, "main done");
        run("stdin with no input has no lines", """
                System.stdout.print("" + System.stdin.hasNextLine() + System.stdin.readAllLines().size());
                """, "false0");
    }

    /** File behavior a program can't see itself: closing at the end, and turning file access off. */
    private static void filesOutsideTheProgram() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("seal-test");
        try {
            Path f = dir.resolve("left-open.txt");
            Map<String, String> files = new LinkedHashMap<>();
            files.put("Keep.seal", "struct Keep { public static FileWriter w; }");
            files.put("Main.seal", "struct Main { static int main() { Keep.w = new FileWriter(\"" + f
                    + "\"); Keep.w.write(\"still written\"); return 0; } }");
            Checker checker = Checker.analyze(files, library);
            VM vm = new VM(CodeGen.generate(checker, "Main").program(), new PrintStream(new ByteArrayOutputStream()));
            vm.run();
            check(java.nio.file.Files.readString(f).equals("still written"),
                    "a writer left open when the program ends is written out");

            files.put("Main.seal", "struct Main { static int main() { try { Files.writeString(\"" + dir.resolve("no.txt")
                    + "\", \"x\"); } catch (IOException e) { System.stdout.print(e.getMessage()); } return 0; } }");
            checker = Checker.analyze(files, library);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            vm = new VM(CodeGen.generate(checker, "Main").program(), new PrintStream(out, true, StandardCharsets.UTF_8));
            vm.setFileAccess(false);
            vm.run();
            check(out.toString(StandardCharsets.UTF_8).equals("file access is disabled")
                    && !java.nio.file.Files.exists(dir.resolve("no.txt")), "file access can be turned off: " + out);
        } finally {
            try (java.util.stream.Stream<Path> s = java.nio.file.Files.walk(dir)) {
                for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.delete(p);
                }
            }
        }
    }

    private static void strings() {
        run("printing literals", """
                System.stdout.println("Hello, world");
                System.stdout.print("no newline");
                """, "Hello, world\nno newline");
        run("toCharArray", """
                array<char><<3>> a = "abc".toCharArray();
                System.stdout.print(a[0]); System.stdout.print(a[2]);
                string<<5>> s = "hi";
                array<char><<5>> b = s.toCharArray();
                System.stdout.println((int)b[1] + (int)b[4]);
                """, "ac105\n");
        run("toCharArray of a bare string gives an array<char>", """
                string bare = "xyz";
                array<char> c = bare.toCharArray();
                c[0] = 'X';
                System.stdout.println(c.length() + " " + c[0] + c[2] + " " + bare);
                """, "3 Xz xyz\n");
        run("fixed strings", """
                string<<5>> a = "Hello";
                string<<7>> b = ", world";
                string<<12>> c = a + b;
                System.stdout.println(c);
                System.stdout.println(c.length());
                System.stdout.println(c.maxLength());
                System.stdout.println(c.replace('H', 'J'));
                System.stdout.println(c);
                """, "Hello, world\n12\n12\nJello, world\nHello, world\n");
        run("bare strings grow with +=", """
                string s;
                for (int i = 0; i < 26; i += 1) { s += (char)(65 + i); }
                s += "!";
                System.stdout.println(s);
                System.stdout.println(s.length());
                s = "";
                System.stdout.println(s.length());
                """, "ABCDEFGHIJKLMNOPQRSTUVWXYZ!\n27\n0\n");
        run("a literal's string never changes", """
                string a = "shared";
                string b = a;
                a = a.replace('s', 'S');
                System.stdout.println(a);
                System.stdout.println(b);
                System.stdout.println("shared");
                """, "Shared\nshared\nshared\n");
        run("StringBuilder", """
                StringBuilder b = new StringBuilder();
                b.append("n=").append(42).append(' ').append(2.5).append(true).append((uint<<8>>)255);
                System.stdout.println(b.toString() + " " + b.length());
                b.insert(0, "[").append(']').setCharAt(1, 'N');
                System.stdout.println(b);
                b.delete(1, 4).deleteCharAt(0).reverse();
                System.stdout.println("" + b + " " + b.charAt(0) + " " + b.indexOf("5.2"));
                b.setLength(2);
                StringBuilder c = new StringBuilder('x');
                System.stdout.println(b.toString() + c + new StringBuilder("abc").insert(1, 'Z') + new StringBuilder(100).isEmpty());
                """, "n=42 2.5true255 15\n[N=42 2.5true255]\n]552eurt5.2 2 ] 8\n]5xaZbctrue\n");
        run("strings made from arrays, and new strings from old", """
                array<char> cs = "hello".toCharArray();
                cs[0] = 'j';
                string s = new string(cs);
                System.stdout.println(s + " " + new string(cs, 1, 3) + " " + s.toUpper() + " " + "AbC".toLower());
                System.stdout.println("ab".repeat(3) + "|" + "x".repeat(0) + "|" + s.replace('l', 'L'));
                string<<5>> word = "hello";
                string<<5>> loud = word.toUpper();
                System.stdout.println(loud + word);
                """, "jello ell JELLO abc\nababab||jeLLo\nHELLOhello\n");
        run("comparison", """
                string a = "apple";
                string<<6>> b = "banana";
                string<<5>> c = "apple";
                System.stdout.println(a == c);
                System.stdout.println(a != b);
                System.stdout.println(a < b);
                System.stdout.println(b < a);
                System.stdout.println(a.compare(c));
                System.stdout.println(a.startsWith("app"));
                """, "true\ntrue\ntrue\nfalse\n0\ntrue\n");
        run("search and slices", """
                string s = "hello world";
                System.stdout.println(s.indexOf(119));
                System.stdout.println(s.contains(122));
                string w = s.substring(6, 11);
                System.stdout.println(w);
                w = w.toUpper();
                System.stdout.println(w);
                string c = s.toUpper();
                System.stdout.println(s);
                System.stdout.println(c);
                """, "6\nfalse\nworld\nWORLD\nhello world\nHELLO WORLD\n");
        run("parsing and hashing", """
                string n = "-1234";
                System.stdout.println(n.toInt() * 2);
                string<<3>> m = "007";
                System.stdout.println(m.toInt());
                string a = "abc";
                string<<3>> b = "abc";
                System.stdout.println(a.hash() == b.hash());
                """, "-2468\n7\ntrue\n");
    }

    private static void integers() {
        run("toString", """
                string a = Integer.toString(0);
                string b = Integer.toString(-9223372036854775808);
                string c = Integer.toString(18446744073709551615);
                string d = Integer.toString(255, 16);
                string e = Integer.toString(5, 2);
                System.stdout.println(a); System.stdout.println(b); System.stdout.println(c); System.stdout.println(d); System.stdout.println(e);
                System.stdout.println(Integer.parse("42") + 1);
                """, "0\n-9223372036854775808\n18446744073709551615\nff\n101\n43\n");
        run("arithmetic helpers", """
                System.stdout.println(Integer.min(3, -4));
                System.stdout.println(Integer.max(3, -4));
                System.stdout.println(Integer.abs(-17));
                System.stdout.println(Integer.gcd(84, -36));
                System.stdout.println(Integer.pow(3, 13));
                System.stdout.println(Integer.sign(-5));
                System.stdout.println(Integer.MAX_INT);
                """, "-4\n3\n17\n12\n1594323\n-1\n2147483647\n");
        run("bit helpers", """
                uint x = 2147483649;
                System.stdout.println(Integer.rol(x, 1));
                System.stdout.println(Integer.ror(x, 1));
                System.stdout.println(Integer.rol(x, -1));
                uint<<64>> y = 1;
                System.stdout.println(Integer.ror(y, 1));
                System.stdout.println(Integer.bitCount(255));
                System.stdout.println(Integer.leadingZeros(1));
                System.stdout.println(Integer.trailingZeros(8));
                """, "3\n3221225472\n3221225472\n9223372036854775808\n8\n63\n3\n");
    }

    private static void exactIntegers() {
        run("Integer.mulExact", """
                int a = 2000000000;
                int b = 3;
                System.stdout.println(Integer.mulExact(a, b));
                System.stdout.println(a * b);
                int<<8>> x = -128;
                int<<4>> y = 7;
                int<<12>> z = Integer.mulExact(x, y);
                System.stdout.println((int)z);
                System.stdout.println((int)Integer.mulExact(x, x));
                uint<<8>> u = 255;
                uint<<16>> uu = Integer.mulExact(u, u);
                System.stdout.println((uint)uu);
                int<<8>> s = -100;
                uint<<8>> v = 200;
                int<<16>> sv = Integer.mulExact(s, v);
                System.stdout.println((int)sv);
                uint<<4>> small = 15;
                int<<4>> neg = -8;
                System.stdout.println((int)Integer.mulExact(small, neg));
                int lo = -2147483648;
                System.stdout.println(Integer.mulExact(lo, lo));
                uint big = 4294967295;
                System.stdout.println(Integer.mulExact(big, big));
                """, "6000000000\n1705032704\n-896\n16384\n65025\n-20000\n-120\n4611686018427387904\n18446744065119617025\n");
        compileError("Integer.mulExact rejects results over 64 bits", """
                int<<64>> a = 1;
                int b = 2;
                System.stdout.println(Integer.mulExact(a, b));
                """, "integer width must be between 1 and 64, not 96");
    }

    private static void chars() {
        run("Char", """
                System.stdout.println(Char.isDigit(55));
                System.stdout.println(Char.isLetter(55));
                System.stdout.println(Char.isSpace(9));
                System.stdout.print(Char.toUpper(113));
                System.stdout.print(Char.toLower(81));
                System.stdout.println();
                System.stdout.println(Char.digitValue(57));
                """, "true\nfalse\ntrue\nQq\n9\n");
    }

    private static void math() {
        run("Math", """
                System.stdout.println(Math.sqrt(2.0));
                System.stdout.println(Math.floor(-1.5));
                System.stdout.println(Math.round(2.5));
                System.stdout.println(Math.pow(2.0, 10.0));
                System.stdout.println(Math.abs(-0.25));
                System.stdout.println(Math.hypot(3.0, 4.0));
                System.stdout.println(Math.cos(Math.PI));
                System.stdout.println(Math.clamp(7.0, 0.0, 5.0));
                """, "1.4142135623730951\n-2.0\n3.0\n1024.0\n0.25\n5.0\n-1.0\n5.0\n");
    }

    private static void random() {
        run("Random", """
                Random a = new Random(42);
                Random b = new Random(42);
                bit same = true;
                bit inRange = true;
                for (int i = 0; i < 1000; i += 1) {
                    int x = a.nextInt(10);
                    if (x != b.nextInt(10)) { same = false; }
                    if (x < 0 || x >= 10) { inRange = false; }
                    double d = a.nextDouble();
                    b.nextDouble();
                    if (d < 0.0 || d >= 1.0) { inRange = false; }
                    int r = a.nextRange(-3, 3);
                    b.nextRange(-3, 3);
                    if (r < -3 || r > 3) { inRange = false; }
                }
                System.stdout.println(same);
                System.stdout.println(inRange);
                """, "true\ntrue\n");
    }

    private static void lists() {
        run("List basics", """
                List<int> xs = new List();
                for (int i = 0; i < 20; i += 1) { xs.add(i * i); }
                System.stdout.println(xs.size());
                System.stdout.println(xs.get(7));
                xs.set(0, -1);
                System.stdout.println(xs.first());
                System.stdout.println(xs.removeLast());
                System.stdout.println(xs.last());
                xs.insert(1, 1000);
                System.stdout.println(xs.get(1));
                System.stdout.println(xs.removeAt(1));
                System.stdout.println(xs.indexOf(49));
                System.stdout.println(xs.contains(50));
                """, "20\n49\n-1\n361\n324\n1000\n1000\n7\nfalse\n");
        run("List sort and reverse", """
                List<int> xs = new List();
                Random r = new Random(7);
                for (int i = 0; i < 200; i += 1) { xs.add(r.nextInt(1000)); }
                xs.sort();
                bit sorted = true;
                for (int i = 1; i < xs.size(); i += 1) {
                    if (xs.get(i - 1) > xs.get(i)) { sorted = false; }
                }
                System.stdout.println(sorted);
                xs.clear();
                xs.add(1); xs.add(2); xs.add(3);
                xs.reverse();
                for (int i = 0; i < xs.size(); i += 1) { System.stdout.print(xs.get(i)); }
                System.stdout.println();
                """, "true\n321\n");
        run("List of strings and objects", """
                List<string<<8>>> words = new List();
                words.add("pear");
                words.add("apple");
                words.add("fig");
                words.sort();
                for (int i = 0; i < words.size(); i += 1) { System.stdout.println(words.get(i)); }
                List<BigUInt<<128>>> nums = new List();
                BigUInt<<128>> n;
                n.set(5);
                nums.add(n);
                nums.add(n * n);
                System.stdout.println(nums.get(1).toString());
                """, "apple\nfig\npear\n25\n");
    }

    private static void queues() {
        run("Queue", """
                Queue<int> q = new Queue();
                for (int i = 0; i < 5; i += 1) { q.push(i); }
                System.stdout.print(q.pop()); System.stdout.print(q.pop());
                for (int i = 5; i < 20; i += 1) { q.push(i); }
                System.stdout.print(q.peek());
                int total = 0;
                while (!q.isEmpty()) { total += q.pop(); }
                System.stdout.println();
                System.stdout.println(total);
                """, "012\n189\n");
    }

    private static void maps() {
        run("IntMap", """
                IntMap<double> m = new IntMap();
                for (int i = 0; i < 100; i += 1) { m.put((int<<64>>)(i * 7), (double)i / 2); }
                System.stdout.println(m.size());
                System.stdout.println(m.get(70));
                System.stdout.println(m.has(71));
                m.put(70, 99.5);
                System.stdout.println(m.get(70));
                for (int i = 0; i < 100; i += 2) { m.remove((int<<64>>)(i * 7)); }
                System.stdout.println(m.size());
                bit ok = true;
                for (int i = 0; i < 100; i += 1) {
                    if (m.has((int<<64>>)(i * 7)) != (i % 2 == 1)) { ok = false; }
                }
                System.stdout.println(ok);
                List<int<<64>>> keys = m.keys();
                keys.sort();
                System.stdout.println(keys.get(0));
                System.stdout.println(keys.size());
                """, "100\n5.0\nfalse\n99.5\n50\ntrue\n7\n50\n");
        run("IntMap of strings", """
                IntMap<string<<5>>> names = new IntMap();
                names.put(1, "one");
                names.put(2, "two");
                names.put(-3, "minus");
                System.stdout.println(names.get(-3));
                System.stdout.println(names.get(1) == "one");
                """, "minus\ntrue\n");
    }

    private static void arrays() {
        run("Array", """
                Array<int><<4>> a;
                a.fill(3);
                a.set(2, 10);
                System.stdout.println(a.get(0) + a.get(2));
                System.stdout.println(a.getLength());
                Array<double><<2, 3>> m;
                m.set(1, 2, 0.5);
                System.stdout.println(m.get(1, 2));
                System.stdout.println(m.getLength());
                """, "13\n4\n0.5\n6\n");
    }

    private static void bigints() {
        run("BigUInt arithmetic", """
                BigUInt<<256>> a;
                a.parse("123456789012345678901234567890");
                BigUInt<<256>> b;
                b.parse("987654321098765432109876543210");
                System.stdout.println((a * b).toString());
                System.stdout.println((b - a).toString());
                System.stdout.println((a + b).toString());
                System.stdout.println(a < b);
                System.stdout.println(a == a);
                """, "121932631137021795226185032733622923332237463801111263526900\n864197532086419753208641975320\n1111111110111111111011111111100\ntrue\ntrue\n");
        run("BigUInt factorial and shifts", """
                BigUInt<<512>> f;
                f.set(1);
                for (int i = 2; i <= 50; i += 1) {
                    BigUInt<<512>> k;
                    k.set((uint<<64>>)i);
                    f = f * k;
                }
                System.stdout.println(f.toString());
                BigUInt<<128>> one;
                one.set(1);
                BigUInt<<128>> big = one <<< 100;
                System.stdout.println(big.toString());
                big <<= -98;
                System.stdout.println(big.toString());
                BigUInt<<128>> top = one <<< 127;
                System.stdout.println((top + top).isZero());
                """, "30414093201713378043612608166064768844377641568960512000000000000\n1267650600228229401496703205376\n4\ntrue\n");
        run("BigUInt odd widths wrap", """
                BigUInt<<70>> x;
                x.parse("1180591620717411303423");
                BigUInt<<70>> one;
                one.set(1);
                System.stdout.println((x + one).isZero());
                System.stdout.println(sizeof(BigUInt<<70>>));
                """, "true\n128\n");
    }

    private static void fixedPoint() {
        run("fixed point in the standard library", """
                List<fixed<<16, 16>>> xs = new List();
                xs.add(2.5); xs.add(-1.25); xs.add(0.75);
                xs.sort();
                for (int i = 0; i < xs.size(); i += 1) { System.stdout.println(xs.get(i)); }
                System.stdout.println(xs.indexOf(0.75));
                ufixed<<8, 8>> u = 3.5;
                System.stdout.print(u);
                System.stdout.println();
                """, "-1.25\n0.75\n2.5\n1\n3.5\n");
    }

    private static void fixedUtilities() {
        run("Fixed.mulExact", """
                fixed<<4, 4>> a = 1.5;
                fixed<<4, 4>> b = -2.25;
                fixed<<8, 8>> p = Fixed.mulExact(a, b);
                System.stdout.println(p);
                fixed<<4, 4>> top = 7.9375;
                System.stdout.println(Fixed.mulExact(top, top));
                System.stdout.println(top * top);
                fixed<<4, 4>> low = -8.0;
                System.stdout.println(Fixed.mulExact(low, low));
                fixed<<8, 0>> whole = 100.0;
                fixed<<12, 4>> mixed = Fixed.mulExact(a, whole);
                System.stdout.println(mixed);
                ufixed<<4, 4>> u = 15.9375;
                ufixed<<8, 8>> uu = Fixed.mulExact(u, u);
                System.stdout.println(uu);
                fixed<<2, 6>> x = 0.015625;
                System.stdout.println(Fixed.mulExact(x, x));
                """, "-3.375\n63.00390625\n-1.0\n64.0\n150.0\n254.00390625\n0.000244140625\n");
        run("Fixed helpers", """
                fixed<<8, 8>> a = -2.75;
                fixed<<8, 8>> b = 1.25;
                System.stdout.println(Fixed.abs(a));
                System.stdout.println(Fixed.min(a, b));
                System.stdout.println(Fixed.max(a, b));
                System.stdout.println(Fixed.floor(a));
                System.stdout.println(Fixed.floor(b));
                System.stdout.println(Fixed.fraction(a));
                System.stdout.println(Fixed.fraction(b));
                fixed<<1, 7>> tiny = -0.5;
                System.stdout.println(Fixed.floor(tiny));
                ufixed<<4, 4>> u = 3.5;
                ufixed<<4, 4>> v = 2.0;
                System.stdout.println(Fixed.min(u, v));
                """, "2.75\n-2.75\n1.25\n-3.0\n1.0\n0.25\n0.25\n-1.0\n2.0\n");
        compileError("mulExact rejects results over 64 bits", """
                fixed<<32, 32>> big = 1.0;
                System.stdout.println(Fixed.mulExact(big, big));
                """, "fixed<<64, 64>> has 128 bits");
    }

    private static void failures() {
        trap("List bounds", """
                List<int> xs = new List();
                xs.add(1);
                System.stdout.println(xs.get(5));
                """, "uncaught IndexOutOfBoundsException: List index 5 out of range for size 1");
        trap("string bounds", """
                string<<4>> s = "abc";
                System.stdout.print(s.charAt(3));
                """, "uncaught IndexOutOfBoundsException: string index 3 out of range for length 3");
        trap("StringBuilder bounds", """
                System.stdout.print(new StringBuilder("ab").charAt(5));
                """, "uncaught IndexOutOfBoundsException: index 5 out of range for length 2");
        trap("a string from part of an array", """
                array<char> cs = new array<char>(3);
                string s = new string(cs, 2, 2);
                """, "uncaught IndexOutOfBoundsException: offset 2 and count 2 out of range for an array of length 3");
        trap("parse error", """
                System.stdout.println(Integer.parse("12x"));
                """, "uncaught NumberFormatException: not a decimal integer: \"12x\"");
        trap("missing key", """
                IntMap<int> m = new IntMap();
                System.stdout.println(m.get(3));
                """, "uncaught NoSuchElementException: IntMap.get: no key 3");
        trap("empty queue", """
                Queue<int> q = new Queue();
                q.pop();
                """, "uncaught NoSuchElementException: pop on an empty Queue");
        trap("sort needs <", """
                List<handle<Thing>> xs = new List();
                xs.sort();
                """, "error:");
    }

    private static void ownership() {
        run("changing a copy of a string never changes the original", """
                string a = "hello";
                string b = a;
                b += "!";
                System.stdout.println(a);
                System.stdout.println(b);
                a = a.replace('h', 'H');
                System.stdout.println(a);
                System.stdout.println(b);
                string c = b;
                c += "?";
                b += ".";
                System.stdout.println(b);
                System.stdout.println(c);
                """, "hello\nhello!\nHello\nhello!\nhello!.\nhello!?\n");
        run("a string passed to a function is a copy", """
                string a = "abc";
                shout(a);
                System.stdout.println(a);
                """, "ABC!\nabc\n");
        run("lists are shared, like Java", """
                List<int> a = new List();
                List<int> b = a;
                b.add(1);
                b.add(2);
                System.stdout.println(a.size());
                fill(a);
                System.stdout.println(b.size());
                """, "2\n5\n");
        run("lists of class objects release them when removed or cleared", """
                List<Res> xs = new List();
                xs.add(new Res(1));
                xs.add(new Res(2));
                xs.add(new Res(3));
                int start = Probe.live();
                xs.removeAt(0);
                System.stdout.print(start - Probe.live());
                xs.removeLast();
                System.stdout.print(start - Probe.live());
                xs.clear();
                System.stdout.println(start - Probe.live());
                """, "123\n");
        run("queues and maps release what they remove", """
                Queue<Res> q = new Queue();
                IntMap<Res> m = new IntMap();
                for (int i = 0; i < 20; i++) { q.push(new Res(i)); m.put(i, new Res(i)); }
                int start = Probe.live();
                for (int i = 0; i < 5; i++) { q.pop(); m.remove(i); }
                System.stdout.print(start - Probe.live());
                q.clear();
                m.clear();
                System.stdout.println(" " + (start - Probe.live()));
                """, "10 40\n");
        run("collections and strings leave nothing behind", """
                List<string> words = new List();
                for (int i = 0; i < 50; i += 1) { words.add(Integer.toString(i)); }
                Queue<string> q = new Queue();
                for (int i = 0; i < 50; i += 1) { q.push(words.get(i)); }
                IntMap<string> m = new IntMap();
                while (!q.isEmpty()) {
                    string w = q.pop();
                    m.put((int<<64>>)w.length(), w);
                }
                List<int<<64>>> keys = m.keys();
                keys.sort();
                System.stdout.println(keys.size());
                System.stdout.println(m.get(2));
                List<List<Res>> nested = new List();
                for (int i = 0; i < 3; i += 1) {
                    List<Res> inner = new List();
                    inner.add(new Res(9));
                    nested.add(inner);
                }
                """, "2\n49\n");
    }

    private static void conflicts() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("List.seal", "struct List { }");
        files.put("Main.seal", "struct Main { static int main() { return 0; } }");
        List<Diagnostic> d = Checker.analyze(files, library).diagnostics();
        check(d.stream().anyMatch(x -> x.message().contains("struct List is part of the standard library")),
                "redefining a stdlib struct is an error: " + d);
    }

    // ================================================================ helpers

    private static void compileError(String name, String body, String expected) {
        Result r = execute(body);
        check(r.errors() != null && r.errors().contains(expected),
                name + "\n    expected a compile error containing " + show(expected) + "\n    got " + r);
    }

    private record Result(String output, String trap, String errors, int live) {
    }

    private static Result execute(String body) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Thing.seal", "class Thing { }");
        files.put("Res.seal", "class Res { public int id; public new(int i) { id = i; } }");
        files.put("Keep.seal", "struct Keep { public static Res thing; }");
        files.put("Pt.seal", "class Pt { public int x; public int y; public new(int x, int y) { this.x = x; this.y = y; }"
                + " @overload(Operator.EQUALS) public bit same(Pt o) { return o != null && x == o.x && y == o.y; }"
                + " public uint<<64>> hash() { return Hash.of(x * 31 + y); } }");
        files.put("Main.seal", "struct Main\n{\n"
                + "    static void shout(string s) { s = s.toUpper(); s += \"!\"; System.stdout.println(s); }\n"
                + "    static void fill(List<int> xs) { while (xs.size() < 5) { xs.add(0); } }\n"
                + "    static int main()\n    {\n" + body + "        return 0;\n    }\n}\n");
        Checker checker = Checker.analyze(files, library);
        if (!checker.diagnostics().isEmpty()) {
            return new Result("", null, "error: " + checker.diagnostics(), 0);
        }
        CodeGen.Result gen = CodeGen.generate(checker, "Main");
        if (!gen.errors().isEmpty()) {
            return new Result("", null, "error: " + gen.errors(), 0);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        VM vm = new VM(gen.program(), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        vm.setMaxSteps(50_000_000);
        try {
            vm.run();
            return new Result(bytes.toString(StandardCharsets.UTF_8), null, null, vm.liveObjects());
        } catch (Trap t) {
            return new Result(bytes.toString(StandardCharsets.UTF_8), t.getMessage(), null, vm.liveObjects());
        } catch (RuntimeException e) {
            return new Result(bytes.toString(StandardCharsets.UTF_8), "crash: " + e, null, vm.liveObjects());
        }
    }

    private static void run(String name, String body, String expected) {
        Result r = execute(body);
        check(r.errors() == null && r.trap() == null && r.output().equals(expected) && r.live() == 0,
                name + "\n    expected " + show(expected) + "\n    got      " + show(r.output())
                        + (r.live() == 0 ? "" : "\n    " + r.live() + " objects still alive at the end (a leak)")
                        + (r.trap() == null ? "" : "\n    trap     " + r.trap())
                        + (r.errors() == null ? "" : "\n    " + r.errors()));
    }

    /** Expects an uncaught exception with this report, or ("error:") a compile error. */
    private static void trap(String name, String body, String expected) {
        Result r = execute(body);
        boolean ok = expected.startsWith("error:") ? r.errors() != null : expected.equals(r.trap());
        check(ok, name + "\n    expected " + show(expected) + "\n    got      output " + show(r.output())
                + ", trap " + r.trap() + (r.errors() == null ? "" : "\n    " + r.errors()));
    }

    private static String show(String s) {
        return "\"" + s.replace("\n", "\\n") + "\"";
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }
}
