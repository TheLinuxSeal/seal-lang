# Seal on the JVM: design

**Status:** proposal, for review before any code. Decisions already taken are marked **Decided**;
the rest are **Proposed**, with the reasoning, and the questions still open are collected at the end.

Seal today compiles to bytecode for its own register VM (`vm/`), written in Java. This document
describes a backend that compiles Seal to JVM class files instead, so that Seal programs run on any
JVM, fast, and can later become Minecraft mods.

## 1. Goals

- **Decided: Seal becomes JVM-only.** One backend, so every language feature is implemented once.
  The VM stays only while the JVM backend is built, as the reference it is tested against
  (§12), and is then retired.
- Compile every Seal program the test suites and examples hold to class files that run on a
  standard JVM, with the same output.
- Real JVM classes for Seal's classes and interfaces, so that later Java code, and Minecraft, can
  use them, extend them and call them.
- Speed: the JIT compiles Seal code like Java code. Most code should run within a small factor of
  equivalent Java; struct-heavy code somewhat slower (§4.5).

Not goals yet (later phases, §13): calling Java classes from Seal (the language imports no Java
classes yet), Mixins, native compilation, `System.rawAssembly`.

## 2. Pipeline

```text
.seal files ─ Lexer ─ Parser ─ Checker ─┬─ CodeGen ─ VM bytecode ─ VM      (today; retired later)
                                        └─ JvmGen  ─ .class files ─ JVM   (new)
```

The front end stays as it is. The checker already resolves everything a backend needs and hands it
over as a `TypedBody` per method instantiation: types of every expression, the method or operator
each call resolves to, implicit casts (`implicitCasts`, `narrowed`), and the code it rewrote or
desugared (`rewritten`, `desugared` for-each loops, `switches`, `switchExprs`, `statementSteps`,
`assertions`, accessors of abstract fields). `JvmGen` reads the same structures CodeGen reads.

- **Proposed: class files are written with the JDK's class-file API** (`java.lang.classfile`,
  final since JDK 24, present in the JDK 25 the project uses). No dependencies, no build tool, and
  it generates the StackMapTable frames the verifier needs. For that it must know the class
  hierarchy of Seal's own classes, which `JvmGen` supplies from the checker through a
  `ClassHierarchyResolver`.
- **Proposed: the target class-file version is a setting.** The compiler runs on JDK 25, but a mod
  must run on the Java version its Minecraft requires (Java 21 for Minecraft 1.20.5 to 1.21), so
  the default should be the oldest version the targeted Minecraft versions run on.
- Output: a folder of class files, and later a jar. `seal run` compiles to a temporary folder, or
  straight into a class loader, and runs it; `seal build` writes them.
- The runtime support (§9) is a small library of Java classes in
  `io.github.thelinuxseal.seal.runtime`, shipped with compiled programs.

## 3. Types

| Seal | JVM | Notes |
| ---- | --- | ----- |
| `bit` | `boolean` (`int` on the stack) | |
| `int<<W>>`, `uint<<W>>`, W ≤ 32 | `int` | W = 32 is free; narrower widths are normalized after arithmetic (§3.1) |
| `int<<W>>`, `uint<<W>>`, 33 ≤ W ≤ 64 | `long` | W = 64 free; others normalized |
| `char` | `char` | the JVM's char is an unsigned 16-bit code unit, as Seal's |
| `float`, `double` | `float`, `double` | same IEEE semantics; Java's `Float.toString` and `Double.toString` give the same text the VM prints |
| `fixed<<N, M>>`, `ufixed<<N, M>>` | `int` (N+M ≤ 32) or `long` (≤ 64) | the scaled integer; `*` and `/` through runtime helpers (§3.2) |
| `handle<C>`, a class or interface type | a reference to C's JVM class or interface | `null` is `null` |
| struct, unique, record, enum, `array<T><<N>>` | a storage reference (§4) | values in `long[]` storage |
| `addr<T>` | a pointer object (§4.4) | |
| `array<T>` (sized at run time) | a JVM array (§8.1) | |
| `string` | a struct (§8.2) | |

### 3.1 Integers of any width

- The JVM wraps 32- and 64-bit arithmetic exactly as Seal does, so `int`, `uint`, `int<<64>>` and
  `uint<<64>>` cost nothing extra.
- Other widths keep the value in an `int` or `long` and normalize it after each arithmetic result:
  shift left by 32 − W (or 64 − W) and back, arithmetic shift for signed, logical for unsigned.
  That is what the VM's `TRUNC` does, and two instructions.
- Unsigned comparison flips the sign bit of both sides (`x ^ MIN_VALUE`) and compares signed.
  Unsigned division, remainder and text use the JDK's `Integer`/`Long.divideUnsigned`,
  `remainderUnsigned` and `toUnsignedString` from the runtime.
- Division by zero is the JVM's `ArithmeticException`, where the VM traps (§7).
- Seal's single shift operator `<<<` shifts left for a positive amount and right for a negative
  one; it becomes a branch, or a runtime helper where the amount isn't a constant.
- Bit indexing (`x[3]`) and slices (`x[0..4]`) become shifts and masks, as in the VM.
- Widening and narrowing (§41.6 of the spec) are `i2l`, `l2i`, `i2d`, `d2i` and friends, plus
  normalization; float to integer uses Seal's rules (truncate, then wrap), which differ from the
  JVM's saturating `d2i` for out-of-range values, so out-of-range conversions go through a helper.

### 3.2 Fixed point

A `fixed<<N, M>>` is its scaled integer. `+` and `-` are integer arithmetic plus normalization. `*`
and `/` need a 128-bit intermediate and round-to-nearest, ties up: for formats up to 32 bits, a
`long` intermediate suffices; for wider ones the runtime uses `Math.multiplyHigh` and, for
division, a short exact routine. Text and conversions to and from `double` come from the runtime,
which already exists in Java form in the VM (`formatFixed`).

## 4. Values: structs and uniques in storage

**Decided: structs and uniques live in `long[]` storage; classes are real JVM classes.** This
follows the line the language already draws: values have fixed layouts, can be addressed with
`addr<T>`, held inline and copied bit for bit; class objects are shared and reached through handles.

**Decided: classes have no destructors** (§6), so nothing frees storage by hand. That rules out one
global `long[]` heap with an allocator, which something would have to free.

### 4.1 Storage blocks

**Proposed:** value storage comes in blocks, each an ordinary JVM object that the garbage
collector frees when nothing refers to it:

```java
final class Block {
    final long[] bits;     // every value's bits, at the bit offsets the checker's layout gives
    final Object[] refs;   // the references inside those values: class objects, other blocks; null if none
}
```

- The layout is the checker's, unchanged: the same bit offsets, sizes and alignment as the VM, so
  `sizeof`, `alignof`, bit-packed layouts, casts between `addr` types and address arithmetic keep
  working. A reference field (a handle, an `addr`, a string's buffer) keeps its 64-bit place in
  `bits`, unused, and its value lives in `refs[offset / 64]`.
- Because references are in `refs`, the garbage collector sees every reference inside a struct, so
  no object table and no reference counting are needed anywhere.
- Reading a field of width W at bit offset O is `bits[O >>> 6]`, a shift and a mask. Where the
  layout keeps a field within one word, which the checker knows per instantiation, that is the
  whole cost; a field that straddles two words (bit-packed layouts only) reads both.

### 4.2 Where values live

| A value held in | Lives in |
| --------------- | -------- |
| a local variable or temporary | the method's frame block, created when the method starts |
| a field of a class object | the object's own block, a field `$values` created by its constructor |
| a static field | the class's static block, created in its static initializer |
| another struct, a fixed array | its container's block, inline, as today |
| `new<T>(n)` | a block of its own |

- **Proposed: a frame block per method call** that has struct locals: `new long[n]` (and
  `Object[]` if needed) when the method starts. JVM allocation is a pointer bump in the thread's
  own buffer, the block dies young, and every thread has its own, so Minecraft's many threads need
  nothing extra. A shared per-thread stack would avoid the allocation but needs a hidden parameter
  or a thread-local lookup in every method, which would also show in Java-visible signatures.
- A struct value is passed to a method as `(Block, int offset)`: two JVM parameters, the way the
  VM passes an address today. Copying a value is copying its words (`System.arraycopy`) and its
  references.

### 4.3 Struct methods

A struct's methods are static JVM methods taking the receiver as `(Block, int)` first. A unique's
destructor is one more such method, which the generated code calls at scope exit and on every
path out (returns, breaks, exceptions), exactly where CodeGen calls it today.

### 4.4 Pointers

`addr<T>` is a pointer object `Ptr(Block block, long bitOffset)`; address arithmetic makes a new
one (or is folded at compile time), `&p` reads or writes through it, and `null` is `null`. `delete
p` runs the destructors of the uniques in the block (for `new<T>(n)`, all n) and drops it; the
memory goes when nothing refers to it. Pointers are optional low-level Seal, so their allocation
cost is accepted; they can be split into two JVM locals later if it matters.

### 4.5 Cost, and a later optimization

Field access in a block is a few instructions more than a JVM field, and the JIT can't keep block
contents in registers the way it scalar-replaces small objects. A later pass can do that itself:
a struct local whose address is never taken (the checker already knows, `AddressTaken`) can be
split into one JVM local per field, which is free. That is an optimization, not part of the first
version.

## 5. Classes and interfaces

- **A Seal class becomes a JVM class**, a subclass of a runtime root `SealObject` (Seal's `Object`,
  with `hash()`), or of its Seal parent. Methods are JVM instance methods; overriding is the JVM's,
  so virtual calls are `invokevirtual` and interface calls `invokeinterface`.
- **Fields:** numbers, bits and handles become JVM fields of the matching type. Struct, unique and
  fixed-array fields go into the object's `$values` block (§4.2), so a field's address can still be
  taken and struct methods work on it.
- **Operators** stay what they are in Seal, ordinary methods called directly (they aren't virtual).
- **Interfaces become JVM interfaces.** Default methods are the JVM's own default methods. Abstract
  and default fields already compile to `get name()`/`set name(value)` accessor methods (§30.4 of the
  spec), which is how the JVM wants interface properties anyway; their JVM names will be
  `getName`/`setName`-style, so Java can call them.
- **Lambdas** become small generated classes implementing the functional interface, with captured
  values as fields; `invokedynamic` and the JDK's lambda factory are a possible later refinement.
- **Enums and records** are structs (§4) with their generated methods, as today.

### 5.1 Generics and shapes

Seal compiles generic code once per instantiation, and so will the JVM backend: `List<int>` and
`List<Point>` are separate JVM classes, `Box<T>.map<R>` is a separate method per `R`. Every value
type then has its own exact layout, and no boxing ever happens, as Seal intends.

- **Proposed naming:** a class with no type or value parameters keeps its name (`Main`,
  `com.example.Vec2`), which Java code and Minecraft see. An instantiation gets a readable mangled
  name: `List$int`, `Matrix$double$3$3`, `HashMap$string$Point`, with a short escape for characters
  JVM names can't hold. Method instantiations do the same (`largest$int`).
- The cost is more classes for heavily generic programs; only instantiations reachable from `main`
  are generated (as today), which keeps it in check.

## 6. Memory management

**Decided: the JVM's garbage collector frees class objects, and classes have no destructors.**
Reference counting goes away entirely: no retain or release on every handle copy, and no leak from
objects that refer to each other in a cycle.

Changes this makes to the language, to be done before or with the backend (§13, M0):

1. **`delete()` is for uniques only.** A class with a destructor is an error.
2. **Resources close explicitly.** `Writer`, `FileWriter` and `Reader` close themselves today when
   the last reference goes. They get Java's way instead: `close()`, and try-with-resources,
   `try (FileWriter w = new FileWriter(path)) { ... }`, closing at the end of the block. (The other
   option, making them uniques, would stop them being shared, which `System.stdout` needs.)
3. **A class can't hold a unique that has a destructor**: its owner is freed whenever the
   collector decides, so the destructor would never run at a known time.
4. **`delete h` on a handle goes away.** It frees an object at once and leaves other handles stale,
   which the JVM can't express. `delete p` on an `addr` stays (§4.4).
5. **The library stops freeing memory in destructors.** `List`, `Queue`, `IntMap`, `array` and
   `StringBuffer` keep their storage in GC-managed storage instead (§8).
6. **Static fields** need no release at the end of the program; uniques in static fields still
   have their destructors run when `main` returns.

## 7. Exceptions and run-time errors

- Seal's `Exception` becomes a JVM class extending `RuntimeException` (Seal has no checked
  exceptions), so `throw`, `try`, `catch` and `finally` are the JVM's own exception tables.
- **Stack traces come for free:** class files record the source file (`Main.seal`) and line
  numbers, so an uncaught exception prints `at com.example.Main.main(Main.seal:12)`, and debuggers
  and profilers show Seal lines.
- The VM's traps (division by zero, null handles, array bounds, failed casts, `System.fail`) become
  the JVM's own exceptions (`ArithmeticException`, `NullPointerException`, ...), which Seal's
  `catch` doesn't catch, since they aren't Seal exceptions. That keeps today's rule that faults
  stop the program. Stale-handle traps disappear with `delete h`.

## 8. The standard library

The library is Seal code and stays so. What changes:

### 8.1 `array<T>` becomes built in

**Proposed:** today `array<T>` is a library class built on `addr<T>`. It becomes a type the compiler
knows: on the JVM, a Java array (`int[]`, `double[]`, `SealObject[]`, or for structs a `Block`
holding the elements back to back). `List`, `Queue`, `IntMap`, `HashMap` and `StringBuffer` are
rebuilt on `array<T>` instead of `addr<T>`, so the library needs no pointers and nothing to free.
This step is independent of the JVM and can be done first, on the VM, with the existing tests.

### 8.2 Strings without reference counts

`string` is a value with copy-on-write: today it asks the VM how many references its buffer has
before changing it in place. Without reference counts it needs another way. **Proposed:** the way
Go shares slices. A string is a buffer (a `char[]` through `array<char>`), a length, and the buffer
records how far it is used. Appending writes in place when this string's end is the buffer's used
end (nobody else has written past it), and copies otherwise; `set(i, c)` copies unless the string
was just made. Appends in a loop stay amortized O(1), copies stay cheap, and two copies can never
see each other's changes.

### 8.3 Natives

The 55 `@native` methods (printing, files and folders, math, number text, time, hashing, exit)
become static methods of the runtime library, written in Java with the JDK: `System.out`,
`java.nio.file`, `Math`, `String.format` for `%f`. The VM's implementations show what each must do.

**Proposed:** the standard library moves into a package of its own on the JVM,
`io.github.thelinuxseal.seal.lang`, still visible everywhere without imports, as `java.lang` is. In
the default package it would clash between programs and mods, and Java code can't refer to classes
in the default package at all.

## 9. Runtime library

A small Java package, `io.github.thelinuxseal.seal.runtime`, shipped with every compiled program:

- `Block` and `Ptr` (§4), and copying, zeroing and comparing values in blocks;
- `SealObject`, the root class;
- integer helpers: unsigned division, remainder and text, the shift operator, float-to-integer
  conversion with Seal's rules;
- fixed-point multiplication, division, text and conversions;
- the natives (§8.3);
- the entry point (§10).

## 10. Programs

- `main` becomes a JVM `public static void main(String[] args)` that calls Seal's `main()` and,
  for an `int main()`, exits with its result. An instance `main()` creates the main class first,
  as today.
- Static initialization: Seal initializes static fields before `main` runs, in a defined order;
  the JVM initializes a class when it is first used. The generated `main` initializes Seal's
  classes in Seal's order first, so programs behave the same.
- `-Wassert` keeps deciding at compile time whether asserts are generated, as now.

## 11. What maps easily, and what doesn't

Easy, close to one-to-one: classes, interfaces, default methods, abstract and default fields,
virtual calls, exceptions, `try`/`finally`, labeled `break`/`continue`, switch statements and
expressions, packages, static imports, asserts, `float`/`double`, 32- and 64-bit integers, lambdas.

The real work: storage blocks and everything that reads and writes values in them (§4), integer
widths other than 32 and 64 (§3.1), fixed point (§3.2), naming instantiations (§5.1), `addr<T>`
(§4.4), the library without destructors and reference counts (§6, §8), and generating stack map
frames correctly for every control-flow shape (the class-file API does this, given the hierarchy).

## 12. Testing

- Every existing suite that runs programs (`LanguageTest`, `RunTest`, `StdlibTest`, `ExamplesTest`,
  `FixedPointTest`, `FuzzTest`, `CliTest`) gets a backend switch and runs on both the VM and the
  JVM, comparing output: the VM is the reference until the JVM backend passes everything.
- `RefCountTest` (reference counts, destructor order of class objects) and `VMTest` (the VM itself)
  retire with the VM; their unique-destructor cases move to `LanguageTest`.
- New tests for what only the JVM has: every generated class passes the JVM's bytecode verifier,
  stack traces show Seal lines, classes are callable from Java, the target class-file version holds.

## 13. Order of work

| Milestone | Contents | Done when |
| --------- | -------- | --------- |
| **M0** (on the VM) | §6's language changes: no class destructors, `close()` and try-with-resources, `delete` only on `addr`; `array<T>` built in, and the collections on it (§8.1); strings without reference counts (§8.2) | all suites pass on the VM |
| **M1** | `JvmGen` skeleton: static methods, `int`/`int<<64>>`/`double`/`bit`/`char`, control flow, calls, recursion, printing; `seal run --jvm` | `examples/01-basics` runs |
| **M2** | classes, interfaces, virtual and interface calls, fields, exceptions, lambdas, strings, the runtime's natives | most of `LanguageTest` |
| **M3** | storage blocks: structs, uniques and their destructors, records, enums, fixed arrays, operators | `RunTest`, `RefCountTest`'s unique cases |
| **M4** | generics and shapes with naming, all integer widths, fixed point, packages | `FixedPointTest`, all examples but pointers |
| **M5** | `addr<T>`, bit-packed layouts; every suite passes on the JVM | the VM retires |
| **M6** (later) | jars, the target class-file version, calling Java classes from Seal, then Mixins (§14) | a first Minecraft mod in Seal |

## 14. Later: Java and Minecraft

Not designed here, but the design keeps the door open:

- Seal classes are real JVM classes with readable names, so Java and Minecraft can see them.
  Calling Java from Seal (importing Java classes, mapping `String` to `string` at the boundary,
  extending Java classes) is the next design.
- Mixins: a Seal declaration that compiles to a standard Mixin class, so Fabric and NeoForge apply
  it like a Java mod's.
- Several mods bundling different versions of the Seal runtime and library would clash in one
  game; the usual answers are one shared library mod, or giving each mod its own renamed copy.

## 15. Open questions

1. **Storage per method call** (§4.2): a new frame block per call that has struct locals
   (proposed), or a per-thread stack with a hidden parameter or thread-local lookup?
2. **Struct fields of class objects** (§5): in a `$values` block per object (proposed: addressable,
   one design for all values), or flattened into JVM fields (faster to read, not addressable, and
   struct methods would work on a copy)?
3. **Resources** (§6): `close()` with try-with-resources (proposed), or uniques?
4. **Strings** (§8.2): Go-style shared buffers (proposed), or immutable strings with a builder, as
   Java?
5. **The library's package** (§8.3): `io.github.thelinuxseal.seal.lang` (proposed)?
6. **The default target class-file version** (§2): which Minecraft versions to support first?
7. **Instantiation names** (§5.1): the `List$int` scheme, or another?
