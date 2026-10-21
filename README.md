# Seal

Seal is a programming language that feels like Java, with value types, integers of any width, and
compile-time sizes built into its types.

```java
import seal.util.List;

class Main
{
    record Point(int x, int y) { }

    enum Shape { CIRCLE, SQUARE }

    static void main()
    {
        List<Point> points = new List();
        for (int i = 1; i <= 3; i++) { points.add(new Point(i, i * i)); }
        for (Point p : points) {
            System.stdout.printf("%-16s is %.2f from the origin%n", p, Math.sqrt(p.x * p.x + p.y * p.y));
        }

        int<<8>> small = 100;
        small += 100;                    // 8 bits wrap: -56
        fixed<<16, 16>> price = 19.75;   // fixed point: 16 integer bits, 16 fraction bits
        Shape s = Shape.CIRCLE;
        string kind = switch (s) { case CIRCLE -> "round"; case SQUARE -> "boxy"; };
        System.stdout.println(small + " " + price + " " + kind);
    }
}
```

```text
Point[x=1, y=1]  is 1.41 from the origin
Point[x=2, y=4]  is 4.47 from the origin
Point[x=3, y=9]  is 9.49 from the origin
-56 19.75 round
```

## What it has

- **Java's comforts:** classes, interfaces, abstract classes, generics, lambdas, exceptions, enums,
  records, switch expressions, labeled `break`/`continue`, text blocks, try-with-resources, packages
  with imports and static imports (renamed with `as` if need be), immutable strings with `StringBuilder`, and
  `string.format`/`printf`.
- **Values and objects.** A `struct` is a value that is copied. A `unique` is a value that can't be
  copied, so its destructor runs exactly once, when its scope ends. A `class` is a shared object, as in
  Java, freed automatically once nothing refers to it; what it holds open, it releases in `close()`.
- **Shapes:** compile-time values in types. `string<<16>>` is a string of at most 16 characters stored
  inline, `array<int><<4>>` four ints, and `Matrix<double><<3, 3>>` a 3×3 matrix. Code can take them
  as parameters: `int<<int W>>` is an integer of any width W. A `static pure` method, whose result
  depends only on its arguments, also runs while compiling: `uint<<Bits.needed(1000)>>`.
- **Numbers:** signed and unsigned integers of any width from 1 to 64 bits (`int<<8>>`, `uint<<64>>`),
  fixed point (`fixed<<16, 16>>`), `float` and `double`, with `**` for powers (`^` is XOR, as in
  Java). They widen as in Java and narrow where
  stored, as in C; `-WautoCast` and `-WautoCastLossy` show every conversion the compiler makes.
- **Operator overloading** with `@overload(Operator.ADD)`, including casts. A type's cast to `string`
  is its text, as Java's `toString()`.
- **Optional low-level memory:** `addr<T>` pointers into bit-addressed memory, `sizeof`, and bit
  slices (`x[0..4]`), for code that wants them. Most code never does.
- **The standard library is written in Seal** ([`stdlib/`](stdlib)), in packages as Java's is:
  `seal.lang` (`System`, math, `StringBuilder`, exceptions; no import needed), `seal.util` (lists, maps, big
  integers), `seal.util.function` and `seal.io` (files).
- **Java's own classes,** imported and used as Seal's are: `import java.time.LocalDate;`, then
  `LocalDate.of(JavaInt.of(2026), JavaInt.of(1), JavaInt.of(1))`, checked while compiling and called
  as the JVM calls them, with overloads chosen as Java chooses them, and generics
  (`TreeMap<JavaString, JavaIntBoxed>`); Seal lambdas and classes go where Java wants its
  interfaces (`names.sort((a, b) -> a.compareTo(b))`). Values cross by hand, through
  `seal.java`'s `JavaInt.of`, `JavaString.of` and casts; jars come from `--classpath`
  ([`docs/JAVA.md`](docs/JAVA.md)).

The full language is described in [`SPEC.md`](SPEC.md).

## Building and running

You need JDK 25. There is no build tool and there are no dependencies. The `seal` script builds the
compiler into `out/` the first time, and again whenever its source changes:

```sh
./seal run examples/01-basics     # compile and run a program
./test.sh                         # build, then run every test suite
```

With Nix, the repository is a flake:

```sh
nix develop                                # a shell with JDK 25, where seal runs the repository's script
nix run . -- run examples/01-basics        # the packaged compiler, with its standard library
nix build                                  # ./result/bin/seal
nix flake check                            # build the package and run every test suite
```

With [direnv](https://direnv.net), the `.envrc` enters the dev shell by itself (run `direnv allow` once).

A program is a folder of `.seal` files, each holding one top-level type and named after it. Its
entry point is `main()` in the class `Main`.

```text
seal run   [options] PATH...   compile to JVM class files and run them
seal check [options] PATH...   only report errors
seal dump  [options] PATH...   print the class files
seal build [options] PATH...   write the class files, for java to run

  --main Class       the class whose main() runs (default Main; com.example.Main in a package)
  --classpath PATH   jars and class folders of the Java classes the program imports (also -cp)
  --vm               run or dump on Seal's old VM instead (deprecated, see Status)
  -d DIR             where build writes class files (default classes)
  -WautoCast         warn about every number converted without a cast
  -WautoCastLossy    warn only about those that may lose data
  -Wassert           run assert statements
  -Wall              all of the -W flags
```

## Examples

[`examples/`](examples) has 21 programs, each with the output it should give, from the basics to
generics, fixed point, files, packages, static imports, pure functions and Java's own classes. [`examples/README.md`](examples/README.md)
says what each one shows.

## Status

Seal is young and still changing. Programs compile to JVM class files, with Minecraft modding as
one of the goals; [`docs/JVM.md`](docs/JVM.md) is the backend's design. It compiles the whole
language, from numbers of every width and fixed point, classes, generics, exceptions, lambdas and
strings to structs, uniques and their destructors, records, enums, fixed arrays, operators and
`addr<T>`:

```sh
./seal run examples/01-basics                # compile to class files and run them in this JVM
./seal build -d classes examples/01-basics   # write them, with the runtime they need
java -cp classes Main                        # and run them with plain java
```

Seal's first backend, a register VM written in Java, is deprecated: `--vm` still runs a program on
it, after a warning, and the test suites run every program on both and compare. It gets no new
features, and goes in a later step.

## Layout

The compiler is in the Java package `io.github.thelinuxseal.seal`, under
`src/io/github/thelinuxseal/seal/`:

| Path | What's there |
| ---- | ------------ |
| `lexer/`, `parser/`, `ast/` | reading source into a syntax tree |
| `check/` | the type checker, which resolves everything a backend needs |
| `codegen/` | code generation for the VM |
| `vm/` | the virtual machine |
| `jvm/` | code generation for the JVM: class files, and running them |
| `runtime/` | the Java classes compiled programs use on the JVM |
| `Main.java` | the command line, which the `seal` script runs |

And at the top of the repository:

| Path | What's there |
| ---- | ------------ |
| `seal` | the command-line script |
| `stdlib/` | the standard library, in Seal: `seal/lang/`, `seal/util/`, `seal/io/`, and the built-in `string` and `array` |
| `examples/` | example programs and their output |
| `test/` | the test suites, run by `./test.sh` |
| `SPEC.md` | the language specification |

## License

GPL-3.0; see [`LICENSE`](LICENSE).
