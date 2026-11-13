# Benchmarks

Seal compiles to JVM class files, so a Seal program and a Java one run on the same JIT: what
differs between them is the bytecode each compiler writes, and their libraries. These kernels are
the same work written in both, as someone at home in each language would write it:
[`seal/Main.seal`](seal/Main.seal) and [`java/Main.java`](java/Main.java). Each kernel returns a
checksum, which must be the same in both, so they check Seal's results too.

## Running them

From the project root:

```sh
java benchmarks/Run.java                       # every kernel: about 12 minutes
java benchmarks/Run.java matmul sortLib        # only these
java benchmarks/Run.java --rounds 1 --runs 8   # quicker, rougher
```

`Run.java` builds `seal/` with `./seal build` and `java/` with javac, then runs each kernel in a
JVM of its own (`-Xms2g -Xmx2g`), 15 times: the first 5 let the JIT compile it, and the median of
the other 10 is its time. It does that in each of 3 rounds, Seal first in every other one, and
prints a table of the medians, in Markdown. It exits with 1 if a kernel's checksums differ, or one
fails or runs past 10 minutes. `ExamplesTest` checks that the Seal program compiles; running it
is for when speed is what changed.

| Kernel | What it does |
| ------ | ------------ |
| `intArith` | 200 million steps of a random number generator in `int`: multiply, add, shift and xor |
| `intDiv` | `%` and `/` by a variable |
| `longArith` | the generator in `int<<64>>` (Java: `long`) |
| `shortArith` | in `int<<16>>`, which wraps to 16 bits (Java: `short` and casts) |
| `uintArith` | `uint` division and comparison (Java: `Integer.divideUnsigned`, `compareUnsigned`) |
| `doubleArith` | the Leibniz series for π: a `double` division at each step |
| `doubleMath` | `Math.sqrt` and a polynomial |
| `floatMath` | the same in `float` |
| `intArray` | summing and changing an `array<int>` of 4 million (Java: `int[]`) |
| `doubleArray` | y = a·x + y and a dot product, over `array<double>`s of 2 million |
| `matmul` | two 400 × 400 matrices of `double`s multiplied, in flat arrays |
| `byteArray` | Adler-32 over 8 MB of `array<uint<<8>>>` (Java: `byte[]`, and `& 0xFF`) |
| `sieve` | the primes below 20 million, in an `array<bit>` (Java: `boolean[]`) |
| `sortLib` | a million `int`s sorted by the library: `array.sort()`, `Arrays.sort` |
| `sortHeap` | the same, by one heapsort written in both |
| `fixedArray` | a local `array<int><<1024>>`, a fixed array (Java: `int[1024]`) |
| `listInt` | `List<int>`: 5 million added, read and set (Java: `ArrayList<Integer>`) |
| `hashMap` | `HashMap<int, int>`: half a million put and found (Java: `HashMap<Integer, Integer>`) |
| `stringBuilder` | a `StringBuilder` of a million numbers, and its text read back |
| `stringConcat` | `"item" + i + "/" + (i * 3)`, a million times |
| `virtualCalls` | an interface's method, on a million objects of three classes |
| `allocObjects` | 20 million small objects, each kept a while in a ring |
| `structArray` | an `array<Particle>` of 200,000 structs, moved 50 times (Java: objects of a class) |
| `structLocals` | 2D vectors with `+` and `*` as a struct's operators (Java: a record) |
| `fixedMandel` | the Mandelbrot set in `fixed<<10, 22>>` (Java: `int`s, and a multiply that rounds, by hand) |
| `doubleMandel` | the same in `double` |
| `recursion` | `fib(35)`, the slow way |

## Results

On 2026-11-12, on an Intel Xeon W-2195 (2.3 GHz, 18 processors) with OpenJDK 25.0.5: for each
kernel, the median of 3 rounds' medians of 10 runs, after 5 to warm up:

| Kernel | Java (ms) | Seal (ms) | Seal ÷ Java |
| ------ | --------: | --------: | ----------: |
| intArith | 290.6 | 290.7 | 1.00 |
| intDiv | 110.8 | 111.5 | 1.01 |
| longArith | 302.2 | 302.7 | 1.00 |
| shortArith | 381.3 | 429.1 | 1.13 |
| uintArith | 129.5 | 129.4 | 1.00 |
| doubleArith | 95.8 | 95.5 | 1.00 |
| doubleMath | 95.7 | 97.7 | 1.02 |
| floatMath | 120.9 | 121.0 | 1.00 |
| intArray | 44.6 | 42.7 | 0.96 |
| doubleArray | 110.3 | 109.9 | 1.00 |
| matmul | 29.9 | 30.0 | 1.00 |
| byteArray | 296.4 | 307.0 | 1.04 |
| sieve | 109.4 | 102.7 | 0.94 |
| sortLib | 12.0 | 107.3 | 8.91 |
| sortHeap | 110.6 | 129.2 | 1.17 |
| fixedArray | 28.6 | 138.4 | 4.83 |
| listInt | 365.4 | 47.8 | 0.13 |
| hashMap | 73.3 | 58.5 | 0.80 |
| stringBuilder | 24.6 | 105.8 | 4.30 |
| stringConcat | 23.5 | 174.3 | 7.42 |
| virtualCalls | 97.2 | 101.3 | 1.04 |
| allocObjects | 55.0 | 71.8 | 1.30 |
| structArray | 26.7 | 43.5 | 1.63 |
| structLocals | 132.9 | 89.9 | 0.68 |
| fixedMandel | 19.9 | 27.2 | 1.37 |
| doubleMandel | 21.2 | 21.4 | 1.01 |
| recursion | 33.0 | 32.9 | 1.00 |

Seal ÷ Java is Seal's time over Java's: 1 is the same speed, below 1 Seal is faster. A kernel's
rounds are mostly within 3% of each other; `stringBuilder`'s and `allocObjects`' differ by up to a
fifth.

## Where the differences come from

**The same speed**, within a few percent: arithmetic on `int`, `int<<64>>` and `uint`, `double`
and `float`; loops over `array<T>`, which is a Java array (the bounds check `array.get` makes
before reading it is one the JIT removes, so loops vectorize as Java's do); calls through
interfaces; recursion. Seal's code there is the bytecode javac writes.

**Seal faster:**

* `listInt` and `hashMap`: Seal's generics are compiled for each type they are used with, so a
  `List<int>` holds an `array<int>` of plain ints, where Java's `ArrayList<Integer>` holds an
  object for each number.
* `structLocals`: the struct operators work on values in place; Java's record version was slower
  here, which wasn't looked into.

**Seal slower:**

* `sortLib`: the library's `sort()` is a heapsort, and Java's `Arrays.sort` a quicksort, which
  uses the processor's cache far better. The same heapsort in both (`sortHeap`) is about 16%
  slower in Seal, for a reason not found yet: not the bounds check, as Java with the same check
  runs as fast as without it.
* `fixedArray` and `structArray`: fixed arrays and structs live in storage blocks, `long[]`s that
  are read and written a field's bits at a time (docs/JVM.md §4). A class's fields are JVM fields,
  as Java's are; a later optimization can keep a struct's fields in JVM locals (docs/JVM.md §4.5).
* `fixedMandel`: `zr * zi * 2` multiplies by 2 as a fixed-point number, with a long multiply,
  rounding and a shift, where the Java multiplies by the integer 2. With `p + p` in place of
  `p * 2`, Seal takes 21 ms to Java's 20.
* `shortArith`: `x <<< -5` on an `int<<16>>` calls the runtime's `Ints.shift`, though the amount is
  a constant.
* `stringBuilder` and `stringConcat`: `Integer.toString` is Seal code (`digits`), dividing 64-bit
  numbers without sign, and makes its text in a 65-character array; Java's is tuned by hand.
* `allocObjects`: varies between runs, from as fast as Java's to a third slower.

## Fixed since

* 2026-11-12: `new string(chars)` added the characters one at a time, copying the string each
  time, so `StringBuilder.toString()` and `Integer.toString` took time growing with the square of
  the length: `stringBuilder` didn't finish in 5 minutes (16 s for 100,000 numbers), and
  `stringConcat` took 262 ms. Strings are made from the array at once now (docs/JVM.md §8.2):
  106 ms and 174 ms.
