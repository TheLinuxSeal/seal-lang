# Seal on the JVM: design

**Status:** design agreed. M0, the language changes the backend needs, is done on the VM, and the
backend's M1 to M5 are done (§13): `seal run` and `seal build` compile the whole language, numbers of every width and fixed point, classes and interfaces,
generics and shapes, packages, exceptions, lambdas, strings, `array<T>`, the collections and
files, structs, uniques and their destructors, records, enums, fixed arrays and operators in
storage blocks (§4.6), and `addr<T>` (§4.4). Every program in every suite ran on the JVM as on the
VM, and the VM is removed (§13): the JVM is Seal's only backend. Choices made on review are marked **Decided**; the rest were
**Proposed** and accepted with the design, and stand unless revisited.

Seal compiled to bytecode for its own register VM (`vm/`), written in Java, when this document was
written. It describes the backend that compiles Seal to JVM class files instead, so that Seal
programs run on any JVM, fast, and become Minecraft mods; the VM is removed now, and what this
document says of it is how the JVM backend was made to match it.

## 1. Goals

- **Decided: Seal becomes JVM-only.** One backend, so every language feature is implemented once.
  The VM stayed while the JVM backend was built, as the reference it was tested against
  (§12), and is removed (§13).
- Compile every Seal program the test suites and examples hold to class files that run on a
  standard JVM, with the same output.
- Real JVM classes for Seal's classes and interfaces, so that later Java code, and Minecraft, can
  use them, extend them and call them.
- Speed: the JIT compiles Seal code like Java code. Most code should run within a small factor of
  equivalent Java; struct-heavy code somewhat slower (§4.5).

Not goals yet (later phases, §13): native compilation, `System.rawAssembly`. Calling Java classes
from Seal, and Mixins (in Java, beside the Seal code), are in docs/JAVA.md, and built.

## 2. Pipeline

```text
.seal files ─ Lexer ─ Parser ─ Checker ─ JvmGen ─ .class files ─ JVM
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
- **Decided: class files target Java 25** (class-file version 69), the Java the project already
  uses. A mod built this way runs on Minecraft versions that run on Java 25; the target stays a
  setting, so Java 21 (version 65, for Minecraft 1.20.5 to 1.21) can be added later.
- Output: a folder of class files, and later a jar. `seal run` compiles straight into a class
  loader of its own and runs the program in the compiler's JVM; `seal build -d DIR` writes the
  class files, and the runtime's (§9) beside them, so that `java -cp DIR Main` runs it; `seal dump`
  prints them, as javap -c would, with the Seal lines as `; File.seal:line` comments.
- `JvmGen` (`src/.../seal/jvm/`) works as CodeGen does: from `main`, it reaches the methods the
  program calls, one per instantiation, generating each once to find what it calls (and whether it
  can); then it builds each class with all of its methods. Code it can't generate yet is reported
  as an error at its line, never generated wrong.
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
| `string` | `java.lang.String` (§8.2) | immutable |
| `string<<N>>` | a value in storage (§4) | at most N characters, inline; immutable |

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

Done in M4, as planned: a fixed-point value is held as the integer of its width would be (an
`int` for a `fixed<<8, 8>>`, a `long` for a `fixed<<40, 24>>`), so fields, arrays, blocks,
comparisons, `+`, `-` and `%` are the integer's. The runtime's `FixedPoint` has the rest, as the
VM computes it: `mul` (a `long` product while both values are below 2^31, else
`Math.multiplyHigh` or `unsignedMultiplyHigh` for the 128-bit one), `div` (a `long` quotient while
it fits, else `BigInteger`), `rescale` for casts between formats and to and from integers, and the
exact text. Conversions to and from `double` are inline: a multiplication by 2^±M, and rounding
to nearest as the VM's `F2IR`. A literal becomes its scaled value when compiling, exactly from its
digits. Natives and runtime methods get a fixed-point value as a `long`, and after the arguments
the fraction bits of each, as the VM's natives do; `Fixed.toString` and `Format`'s `%f` are
`@jvm` methods of `FixedPoint`, since their Seal bodies (removed with the VM) wrote into `addr` buffers. `mul` and `div`
were checked against the VM's own on 3 million random operands.

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
  working. A reference field (a handle, an `addr`, a string's buffer) keeps its place in `bits`,
  unused, and its value lives in `refs[offset / 32]`: every value that holds one is aligned to at
  least 32 bits, so no two share an index. `refs` is made when the first reference is stored.
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

- **Decided: a frame block per method call** that has struct locals. When such a method starts,
  it makes its own small block (`new long[n]`, and an `Object[]` if its values hold references)
  for the struct locals and temporaries it needs, uses it while it runs, and leaves it to the
  garbage collector when it returns. JVM allocation is a pointer bump in the thread's own buffer,
  short-lived blocks are the cheapest kind of garbage, and every call has its own, so Minecraft's
  many threads need nothing extra.

  The alternative is a stack per thread, as C has: one big block per thread, from which each call
  takes a slice on entry and gives it back on return. That saves the allocations, but every
  method then has to find its thread's stack: through a hidden extra parameter, which would show
  in every Java-visible signature and get in the way of interop, or through a thread-local lookup
  on every call. §4.5's optimization removes most frame blocks anyway.
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

Done in M5, as planned, in the runtime's `Ptr`:

- A load or store through an address is checked as the VM checks one: a null address traps, and
  so does a misaligned 8-, 16-, 32- or 64-bit value (§22 of the spec). **One difference:** an
  address outside the allocation it points into traps on the JVM, where the VM, which checks only
  against its whole memory, reads or writes whatever lies there. Nothing outside an allocation is
  reachable on the JVM. For the same reason, the difference of two addresses in different
  allocations traps; addresses in different allocations still compare, in an order that holds
  while the program runs.
- `new<T>(n)` and `new S()` where an `addr<S>` is wanted make a block of their own (zeroed, in
  whole 64-bit words, as on the VM); `delete p` runs the destructor of the one `T` at p, then marks
  the block freed, so deleting it again, or deleting an address `new` didn't give, traps.
- `*x` gives an address of the place x names. A local or parameter whose address the method takes
  lives in its frame block, as on the VM (the checker's `AddressTaken` finds them for both
  backends), and so does a static field whose address any code takes, in its class's static
  block: the first pass finds them all. `*` of a value that has no place, a call's result or an
  element `[]` gives, is the address of a copy in the frame, as on the VM. Addresses inside class
  objects are an error when compiling, as before.
- A reference kept in a block (a handle, a string, an address) lives in its `refs` (§4.1), so
  reading its bits through an address of another type gives zeros, where the VM gives the
  handle's or address's bits.
- The natives for byte files take a `Ptr`. A program's own `string` class (one that doesn't use
  the library's) gets a literal's `size` and `chars`, as on the VM.

Bit-packed layouts needed nothing more: blocks have the checker's layouts, and reads and writes
of fields that straddle words were there from M3.

### 4.5 Cost, and a later optimization

Field access in a block is a few instructions more than a JVM field, and the JIT can't keep block
contents in registers the way it scalar-replaces small objects. A later pass can do that itself:
a struct local whose address is never taken (the checker already knows, `AddressTaken`) can be
split into one JVM local per field, which is free. That is an optimization, not part of the first
version.

### 4.6 Done in M3

- The runtime's `Block` is §4.1's, with an element count for an `array<T>` of structs, which is one
  block. Layouts are the VM's own: `check/Layouts` computes them for both backends.
- A method that holds values gets a frame block as it starts, sized by the first pass over its
  code: its struct locals, the temporaries its expressions need, and fixed arrays live there.
- A struct is passed as `(Block, int)`. A method that returns one takes the place for the result
  as one more `(Block, int)`, after the receiver and before the parameters, and returns `void`; the
  caller passes its own temporary or the variable being set. A struct's methods and constructors
  are static methods taking the receiver's place first (`newVec`, `plus`).
- A class object keeps its struct fields in a block of its own, the field `$values` its
  constructor makes; a class's struct and fixed-array static fields are in its `$statics` block.
  A string or `array<T>` in a block is a reference in `refs`; one never set reads as `""` or an
  empty array, as zeroed memory does on the VM.
- Fixed arrays are inline, copied as values; an index outside one throws the library's
  `IndexOutOfBoundsException` (through `FixedArray.outOfBounds`), as on the VM. A fixed array
  given where an `array<T>` is wanted is copied into a new one.
- A unique is destroyed at the end of its scope, on each jump out of it (a pad per jump, as for
  `finally`), and by a handler as a Seal exception passes; one returned from a local is moved, not
  destroyed. Unique temporaries are destroyed at the end of their statement, those a condition
  makes right after it, on both ways out, and those made before an exception cuts a statement short
  as it passes. A bit in the frame records that each temporary was made, as code may skip making
  it (`a && f()`). Uniques in static fields are destroyed after `main` returns (`$fini`). As on the
  VM, a fault stops the program without running destructors.
- Assigning to a unique builds the new value aside, destroys the old one, then copies it in, so
  `u = u.next()` works.
- An instance `main()` of a struct or unique runs on a value `$start` makes and destroys.
- Not done: §4.5's optimization, and fixed strings stored inline (§8.2). `addr<T>` came with M5
  (§4.4).

## 5. Classes and interfaces

- **A Seal class becomes a JVM class**, a subclass of its Seal parent's. Methods are JVM instance
  methods; overriding is the JVM's, so virtual calls are `invokevirtual` and interface calls
  `invokeinterface`.
- **Done in M2: Seal's root, `Object`, is a JVM interface**, `io.github.thelinuxseal.seal.lang.Object`,
  which every class implements, with `hash()` as a default method. A runtime root class could not
  work: Seal's `Exception` has to extend Java's `RuntimeException` (§7), and a JVM class has one
  parent. A class without a Seal parent extends `java.lang.Object` (or `RuntimeException`, for
  `Exception`). `new Object()` makes an `Object$Plain`, a class that implements it and adds nothing.
- Done in M2: fields are JVM fields; constructors are `<init>`, which call the parent's (the one
  `super(...)` names, or its no-argument one), then run the field initializers, then the body;
  strings start as `""` and arrays as empty ones, as Seal's default values. Methods and fields are
  all public on the JVM; the checker has already enforced Seal's access rules.
- **Decided: struct fields of a class object live in a block of the object's own.** Numbers of
  every width, bits and handles become JVM fields of the matching type: an `int<<17>>` or a
  `uint<<32>>` field is a JVM `int`, normalized when it is computed (§3.1), like a local. Struct,
  unique, record, enum and fixed-array fields go into the object's `$values` block (§4.2): a
  struct's methods work on storage, `*player.pos` can take a field's address, and every struct
  value has one representation wherever it lives.
- **Operators** stay what they are in Seal, ordinary methods called directly (they aren't virtual).
- **Interfaces become JVM interfaces.** Default methods are the JVM's own default methods. Abstract
  and default fields already compile to `get name()`/`set name(value)` accessor methods (§30.4 of the
  spec), which is how the JVM wants interface properties anyway; their JVM names are `getName` and
  `setName`, so Java can call them.
- **Lambdas** become small generated classes implementing the functional interface, with captured
  values as fields (and the enclosing object, if the lambda uses it); `invokedynamic` and the JDK's
  lambda factory are a possible later refinement. Done in M2: `Main$lambda$1`, and so on.
- Done in M2: a class's JVM class lists every method its objects can run, including those no code
  calls, so that a call through a parent or an interface always finds one. A method that can't be
  generated for some type arguments (`sort()` on a `List<Exception>`, which has no `<`) is an error
  only if the program can run it, following calls from `main` and virtual calls to every override;
  otherwise it becomes one that stops the program, as on the VM.
- **Enums and records** are structs (§4) with their generated methods, as today.

### 5.1 Generics and shapes

Seal compiles generic code once per instantiation, and so will the JVM backend: `List<int>` and
`List<Point>` are separate JVM classes, `Box<T>.map<R>` is a separate method per `R`. Every value
type then has its own exact layout, and no boxing ever happens, as Seal intends.

- **Decided naming:** a class with no type or value parameters keeps its name (`Main`,
  `com.example.Vec2`), which Java code and Minecraft see. An instantiation gets a readable name made
  of its arguments: `List$int`, `HashMap$string$Point`, `Matrix$double$3$3`. Method instantiations do
  the same (`largest$int`).
- The cost is more classes for heavily generic programs; only instantiations reachable from `main`
  are generated (as today), which keeps it in check.

### 5.2 Shapes and value parameters

The values in `<<...>>` are compile-time values, so each set of them is an instantiation of its own,
compiled with the values in place:

- `Matrix<double><<3, 3>>` is the class `Matrix$double$3$3`. In its code `N` and `M` are the
  constants 3 and 3, its fields are laid out for 3 × 3, and `array<double><<N, M>>` is nine doubles at
  a fixed place in a block. A field whose type differs per shape simply has that shape's type.
- A member that exists only in some shapes (a shape guard) is generated only in those shapes'
  instantiations, as the checker already decides.
- A method with value binders, as `Math.abs(int<<size W>> x)`, gets one JVM method per value used:
  `abs$8` for an `int<<8>>`, `abs$32` for an `int`. Binders bound to the same values share one.
- The bare shape `<<>>` adds nothing to a name: a bare `string` is `string`.
- Done in M2: a method with binders or type parameters gets its parameter types in its name, and
  for type parameters only in the result, the types they stand for: `abs$int8`, `print$uint8`,
  `make$$Point`. The values alone would not do: `print(int<<size W>>)` and `print(uint<<size W>>)`
  both bind W = 8.
- Static Seal overloads can share a JVM descriptor (`f(int<<8>>)` and `f(int)` are both `(I)I`):
  the first one reached keeps the name, and the others get a number, `f$2`. Instance methods are
  named by the slot's first declaration, the topmost class or interface that declares the method;
  if two methods visible there would share a name and descriptor, the slot's name gets its
  parameter types, `f$int8`, so that every override has the same name.
- Values that JVM names can't hold are spelled out: a negative number `-1` is `n1`; a string value,
  as in `Quantity<<"m">>`, keeps its letters and digits and writes others as `_` and their hex code.
  A name longer than a few hundred characters keeps its start and adds a hash of the rest.

## 6. Memory management

**Decided: the JVM's garbage collector frees class objects, and classes have no destructors.**
Reference counting goes away entirely: no retain or release on every handle copy, and no leak from
objects that refer to each other in a cycle.

Changes this made to the language, done in M0 (§13) and tested on the VM:

1. **`delete()` is for uniques only.** A class with a destructor is an error.
2. **Decided: resources close explicitly.** `Writer`, `FileWriter` and `Reader` used to close
   themselves when the last reference went. They have Java's way instead: they implement the
   library's `AutoCloseable`, with `close()`, and try-with-resources,
   `try (FileWriter w = new FileWriter(path)) { ... }`, closes them at the end of the block however it
   is left, adding an exception `close()` throws to the one already on its way (`addSuppressed`). A
   `Reader` also closes itself once it has read its last line, so `for (string line : new
   FileReader(path))` leaves nothing open.
3. **A class can't hold a unique that has a destructor**, directly, in a struct or fixed array, or
   through a type argument: its owner is freed whenever the collector decides, so the destructor
   would never run at a known time. Static fields may.
4. **`delete h` on a handle is gone.** It freed an object at once and left other handles stale,
   which the JVM can't express. `delete p` on an `addr` stays (§4.4).
5. **The library frees no memory in destructors.** `List`, `Queue`, `IntMap` and `StringBuilder`
   keep their elements in `array<T>`s (§8.1), and `HashMap` in lists.
6. **Static fields** need no release at the end of the program; uniques in static fields still
   have their destructors run when `main` returns.

On the VM, objects were freed by reference counting, which no program could observe any more.
`System.refCount` went with copy-on-write strings.

## 7. Exceptions and run-time errors

- Seal's `Exception` becomes a JVM class extending `RuntimeException` (Seal has no checked
  exceptions), so `throw`, `try`, `catch` and `finally` are the JVM's own exception tables.
- **Stack traces come for free:** class files record the source file (`Main.seal`) and line
  numbers, so an uncaught exception prints `at com.example.Main.main(Main.seal:12)`, and debuggers
  and profilers show Seal lines.
- The VM's traps (division by zero, null handles, array bounds, failed casts, `System.fail`) become
  the JVM's own exceptions (`ArithmeticException`, `NullPointerException`, ...), which Seal's
  `catch` doesn't catch, since they aren't Seal exceptions. That keeps today's rule that faults
  stop the program. Stale-handle traps went with `delete h`.

## 8. The standard library

The library is Seal code and stays so. What changes:

### 8.1 `array<T>` becomes built in

`array<T>` is the language's own type, with no destructor, so classes may hold one. On the JVM it
is a Java array (`int[]`, `double[]`, `String[]`, `Animal[]`, or for structs a `Block` holding the
elements back to back). Done in M0: `List`, `Queue`, `IntMap` and `StringBuilder` are built on `array<T>`
(growing with `copyOf(n)`, as Java's `Arrays.copyOf`) instead of `addr<T>`, so the collections use no
pointers and free nothing.

`JvmGen` compiles `array<T>`'s core (`new array<T>(n)`, `length`, `[]`, `[]=`) to JVM array
instructions, and the rest of `stdlib/array.seal` (`fill`, `copy`, `copyOf`, `slice`, `sort`, ...)
as ordinary Seal code over that core. (On the VM, `array.seal` held an `addr<T>` buffer that its
destructor freed; that code is removed with the VM, §13.)

Done in M2. A library method the runtime does says what stands for it on the JVM with
**`@jvm`** (library code only, as `@native`), and has no Seal body: `@jvm("Strings.at")` is a
static method of the runtime's `Strings`, and `@jvm("array.get")` is one `JvmGen` generates itself.
Until the library's VM-only code was removed (§13), such a method kept the Seal body the VM ran.
Checks that throw Seal exceptions stay in Seal, around them: `array.get` runs `array`'s own `check(i)`, then reads the
Java array, and `string.charAt` checks the index, then calls `at`, which is `@jvm("Strings.at")`.
Reading `len`, an array's length field, is `arraylength`. An array's methods are static methods
taking the Java array first, in a class per element type (`lang.array$int`).

### 8.2 Strings are immutable

**Decided: strings are immutable, as Java's.** Today `string` is a value with copy-on-write, which
asks the VM how many references its buffer has before changing it in place; without reference
counts that can't work, and immutable strings need nothing of the kind. What changes in the
language (part of M0):

- `s += "x"` and `s = s + x` keep working: they make a new string and store it in `s`. Strings are
  values, so no program could tell the difference.
- Methods that change a string in place return a new one instead, as in Java: `s.toUpper()`
  becomes `s = s.toUpper();`. Methods that only exist to change a string, `set` and `append`, go.
- Building text in a loop uses a builder, as Java's `StringBuilder` (each `+=` copies the text so
  far). The library's `StringBuffer`, the buffer behind growable strings, is gone; `StringBuilder`
  is public, as Java's, on an `array<char>`.
- Done in M0, on the VM: no `string` method changes a string any more; `toUpper`, `toLower`,
  `replace` and `repeat` return new ones, and `new string(chars)` makes one from an `array<char>`.
  Copies of a bare string shared a buffer (`StringChars`, VM only, removed since), and `s += x`
  added to it in place only past the end of every string using it, which needed no reference
  counts and kept `+=` in a loop cheap there. On the JVM, `+=` copies, as Java's does.
- **Decided:** on the JVM a bare `string` *is* a `java.lang.String`: no wrapper, and passing text to
  Java code or Minecraft costs nothing. Its methods compile to `String`'s where they match, or to
  runtime helpers.
- **Decided:** fixed strings, `string<<N>>`, stay values of at most N characters stored inline, in
  storage blocks like other values, and are immutable too. Joining two of them still makes a
  `string<<N + K>>` without allocating, and the conversion of a `string<<N>>` to a bare `string`
  (today an `@implicit` constructor) makes a `java.lang.String` from its characters.
- Done in M2: a string, of either shape, is a `java.lang.String`. M3 kept that: a `string<<N>>`
  is one too, in a block a reference in its `refs`, which no program can tell, as strings never
  change; storing fixed strings inline is still to do: it changes no program's behaviour, and waits
  for when it is worth it. The fixed shape's `toCharArray` reads through `at`, as the rest of
  `string.seal` does. A string's
  methods are static methods taking the `String` first, in a class per instantiation
  (`lang.string$5` for a `string<<5>>`); most compile from `string.seal` as they are. Reading
  `size` is `length()`, and the few whose bodies use the VM's buffer are `@jvm` (§8.1): `at`, `put`,
  `room`, `substring`, `toUpper`, `toLower`, `replace`, `repeat`, `equals`, `compare`, and
  `Double.toString` and `Float.toString`. A `void` method of a string (`put`, `room`, `+=`) returns
  the new `String`, which the caller stores where the string came from; a constructor starts from
  `""` and returns the string it made.

### 8.3 Natives

The 55 `@native` methods (printing, files and folders, math, number text, time, hashing, exit)
become static methods of the runtime library, written in Java with the JDK: `System.out`,
`java.nio.file`, `Math`, `String.format` for `%f`. The VM's implementations show what each must do.
Each is a static method of the runtime's `Natives` named as the native is (`io_write_int`, `sqrt`),
so a call to a native method compiles to a call to it; a Seal object reaches it as a
`java.lang.Object`. Done in M2: the standard streams, files and folders (with the VM's status codes
and error texts, and the text a file operation produces kept for `string.takeText`), math, time,
identity hashes and exiting; `System`, `Writer`, `Reader`, `Files` and `File` compile from the
library as they are. `Format`'s places for a double went through a `@jvm` method in M3, and fixed
point's text (printing, `Fixed.toString`, `Format`'s `%f` and `%s`) in M4. Still to come: the
natives that take `addr` buffers for bytes (byte files) came with M5; every native is done.

**Decided:** the standard library moves into a package of its own on the JVM,
`io.github.thelinuxseal.seal.lang`, still visible everywhere without imports, as `java.lang` is. In
the default package it would clash between programs and mods, and Java code can't refer to classes
in the default package at all.

**Done since (docs/JAVA.md §0, R2):** the library is in Seal packages of its own, and each Seal
package `seal.x` is `io.github.thelinuxseal.seal.x` on the JVM: `seal.util.List` is
`io.github.thelinuxseal.seal.util.List`. `seal.lang`, which every file sees without an import, keeps
`io.github.thelinuxseal.seal.lang`, and so do the built-in types in no package (`string`, `array`).

## 9. Runtime library

A small Java package, `io.github.thelinuxseal.seal.runtime`, shipped with every compiled program:

- `Block` (§4.1): reading and writing bits, references, copying and zeroing values in blocks
  (done in M3); `Ptr` (§4.4): addresses, their arithmetic and checks, `new` and `delete` (done in
  M5);
- `Ints`: the shift operator, truncation, unsigned-to-double (done; unsigned division and text use
  the JDK's `Integer` and `Long` directly);
- `FixedPoint`: fixed-point multiplication, division, rescaling and text (done in M4, §3.2);
- `Strings`: what the library's `@jvm("Strings...")` methods stand for (§8.2);
- `Natives`: the natives (§8.3), and the entry point (§10);
- `Exit` (`System.exit`) and `Trap` (what stops a program, as a VM trap does).

Seal's `Object` is not in the runtime: it is generated from the library like any class (§5).

## 10. Programs

- `main` becomes a JVM `public static void main(String[] args)` that calls Seal's `main()` and,
  for an `int main()`, exits with its result. An instance `main()` creates the main class first,
  as today (from M2).
- Done in M1: the main class gets `static int $run()`, which calls `main()` and gives the exit code,
  and a `main(String[])` that hands `$run` to the runtime's `Natives.start`, which runs it, flushes
  the streams and exits. `System.exit` throws the runtime's `Exit`, which both catch, so the
  compiler's own JVM, running a program with `seal run`, isn't ended by it.
- Static initialization: Seal initializes static fields before `main` runs, in a defined order;
  the JVM initializes a class when it is first used. The generated `main` initializes Seal's
  classes in Seal's order first, so programs behave the same. Done in M2: each class's static
  fields are set by a `$init` method of its own (so that their lines name its file), which
  `$run` calls in Seal's order before `main`. They are every program class's, and the library
  classes' that the program uses: the library's `Operator` holds enum constants for decorators,
  which no program reads at run time.
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
- `RefCountTest` (when the VM's reference counting frees class objects, seen through a live-object
  count only the tests declare) and `VMTest` (the VM itself) retire with the VM; their
  unique-destructor cases move to `LanguageTest`.
- New tests for what only the JVM has: every generated class passes the JVM's bytecode verifier,
  stack traces show Seal lines, classes are callable from Java, the target class-file version holds.
- Done in M1: `JvmTest` runs every program on both backends and compares output and exit codes,
  verifies every class it generates, checks that what isn't covered yet is reported, and runs 150
  random programs over random integer widths, chars and floating point (casts, arithmetic, shifts,
  comparisons, compound assignments, `++`); `java -cp out io.github.thelinuxseal.seal.JvmTest 1000 7`
  runs more, with another seed. `CliTest` runs what `seal build` writes with `java`.
- Done in M2: `LanguageTest`, `RunTest`, `StdlibTest`, `ExamplesTest` and a quarter of `FuzzTest`'s
  mutants run every program on the JVM too (`OnJvm`). A program the JVM backend compiles must pass
  the verifier and end as on the VM: the same output, and the same exit code, the same uncaught
  exception, or a fault where the VM traps. One it doesn't compile yet must say so, at a line, and
  is counted: each suite's line in `./test.sh` reads, say, `(on the JVM: 36 the same, 78 not
  available yet)`.
- Done in M3: `JvmTest` has cases for structs, operators, records, enums, fixed arrays, struct
  arrays, uniques on every path out, temporaries and an instance `main()` of a unique. At M3's end
  `RunTest` has 38 programs the same on the JVM and 8 not yet (pointers), `LanguageTest` 91 and 23
  (fixed point and pointers), `StdlibTest` 52 and 5, `ExamplesTest` 15 and 4, and `FuzzTest` 368
  and 58. `RefCountTest` has no unique cases left to move: `LanguageTest`'s destructor programs
  cover them, and run the same.
- Done in M4: `FixedPointTest` runs its 600 random operations on the JVM too, each line checked
  against the same exact reference. `JvmTest`'s random programs also use random fixed-point
  formats up to 64 bits (casts to and from every other number type, `*`, `/`, `%`, compound
  assignments, `++`), and a case covers fixed point in fields, structs, arrays and statics, its
  text, `Math` and `Format`. At M4's end: `RunTest` 38 the same and 8 not yet, `LanguageTest` 104
  and 10, `StdlibTest` 56 and 1, `ExamplesTest` 17 and 2, `FuzzTest` 398 and 28: everything not yet
  uses `addr<T>`.
- Done in M5: nothing is left not yet. `RunTest` has 46 programs the same on the JVM,
  `LanguageTest` 114, `StdlibTest` 57, `ExamplesTest` all 19, `FixedPointTest` all 600 operations,
  and `FuzzTest` 425 of the quarter it compares; run on every mutant once
  (`-Dseal.jvm.every=1`), 1701 of 1712 are the same, 8 are stopped by the VM's step limit, and 3
  read or write before the start of their allocation, which the JVM stops (§4.4), counted as such.
  `JvmTest` has cases for addresses of every kind of place, arithmetic, comparisons, `new` and
  `delete`, and the traps.
- Done with the VM's removal: the suites run on the JVM alone, against their expected outputs,
  which the VM gave and the JVM gave the same (`JvmRun` runs a program for them). `JvmTest`'s
  programs that compared the two (`same(...)`) have the output they both gave written in; its
  random programs, generated from a fixed seed, are checked against the output recorded in
  `test/random-programs.txt` (`-Dseal.random.record=true` records it anew, after a change to what
  they are). `FuzzTest` runs every mutant that compiles, generated to count the rounds of its loops
  (`JvmGen.generateCountingSteps`, the runtime's `Natives.stepLimit`) so that one that never ends
  stops, as the VM's step limit stopped it; its rules are no internal crash, classes that pass the
  verifier, no fault saying the generated classes are wrong, and no address fault without `addr`.
  `VMTest` and `RefCountTest` went with the VM, and the tests that counted live objects through it
  (`Probe.live()`). Checks only the VM's code generator made moved to `JvmGen`: an override keeps
  the return type, and a type argument's value without an initializer needs a no-argument
  constructor.

## 13. Order of work

| Milestone | Contents | Done when |
| --------- | -------- | --------- |
| **M0** (on the VM), **done** | §6's language changes: no class destructors, `close()` and try-with-resources, `delete` only on `addr`; `array<T>` built in, and the collections on it (§8.1); immutable strings and `StringBuilder` (§8.2) | all suites pass on the VM |
| **M1**, **done** | `JvmGen` skeleton: static methods, `int`/`int<<64>>`/`double`/`bit`/`char`, control flow, calls, recursion, printing; `seal run --jvm` | `examples/01-basics` runs |
| **M2**, **done** | classes, interfaces, virtual and interface calls, fields, exceptions, lambdas, strings, the runtime's natives | everything in the suites that uses only these runs the same; most of `LanguageTest` also needs structs (M3) |
| **M3**, **done** | storage blocks: structs, uniques and their destructors, records, enums, fixed arrays, operators | `RunTest`, `RefCountTest`'s unique cases (all but pointers) |
| **M4**, **done** | generics and shapes with naming, all integer widths, fixed point, packages | `FixedPointTest`, all examples but pointers |
| **M5**, **done** | `addr<T>`, bit-packed layouts; every suite passes on the JVM | the VM retires: the JVM is the default, the VM behind `--vm` |
| **the VM deprecated**, **done** | `--vm` warns that the VM is deprecated, `vm/` and `codegen/` are `@Deprecated`, and the VM gets no new features: what comes next (Java's classes, docs/JAVA.md) is the JVM's alone; the suites still compare the two on what both run | `--vm` warns, every suite passes |
| **the VM removed**, **done** | the VM, `codegen/`, `VMTest` and `RefCountTest`; `--vm` says the VM is removed; the suites check the JVM alone (§12) | every suite passes on the JVM alone |
| **the VM's library code removed**, **done** | the library's VM-only code: the string and array internals on `addr` buffers, `StringChars`, the Seal bodies behind `@jvm` and the natives only they called | every suite passes |
| **M6**, **done** | jars, calling Java classes from Seal and Seal from Java (docs/JAVA.md, T1 to T5: a Fabric mod in Seal loads in Minecraft 26.3 and registers a block; Minecraft runs on Java 25, so the class-file version needed no change), then Mixins, as a program's own `.java` files (docs/JAVA.md §11) | a first Minecraft mod in Seal, with a mixin |

## 14. Later: Java and Minecraft

Not designed here, but the design keeps the door open:

- Seal classes are real JVM classes with readable names, so Java and Minecraft can see them.
  Calling Java from Seal (importing Java classes, values crossing by `JavaInt.of` and the rest,
  implementing Java interfaces and extending Java classes) is designed in [docs/JAVA.md](JAVA.md):
  typed, checked while compiling, and compiled to direct calls.
- Mixins are Java, a program's own `.java` files beside its Seal code, which they call
  (docs/JAVA.md §11): Fabric and NeoForge apply them as a Java mod's. A Seal declaration compiling
  to a Mixin class could come later, if it earns its place.
- Several mods bundling different versions of the Seal runtime and library would clash in one
  game; the usual answers are one shared library mod, or giving each mod its own renamed copy.

## 15. Decisions made on review

- Struct fields of class objects live in a block per object (§5).
- Resources close with `close()` and try-with-resources (§6).
- Strings are immutable; a bare `string` is a `java.lang.String`, and `string<<N>>` an immutable
  value stored inline (§8.2).
- The library is in `io.github.thelinuxseal.seal.lang` (§8.3), and since R2 in packages, `seal.util` as
  `io.github.thelinuxseal.seal.util` and so on.
- Class files target Java 25 (§2).
- Instantiations are named from their arguments, `List$int`, `Matrix$double$3$3` (§5.1, §5.2).
- Each method call that has struct locals gets a frame block of its own (§4.2).

M0 to M5 are done (§13): every suite passes on the JVM, which is the default. On review, the VM was
kept behind `--vm` for a while, and the suites ran every program on both and compared (§12); RunTest's
one program for the VM's step limit, which the JVM has no counterpart to, went. The VM is removed
since (§12, §13), and so is the library's VM-only code.

M1 went further than its row: every integer width already works (§3.1, planned for M4), and so do
switch statements and labeled loops. M2 removed M1's stopgap for printing: `Writer` compiles as the
class it is. Its "done when" was too hopeful: most of `LanguageTest`'s programs use structs,
uniques, enums or fixed arrays, so they wait for M3 (at M2's end: 36 the same on the JVM, 78 not
yet, almost all for storage). What M2 covers, every suite's programs that use only it, runs the
same.

M4 came down to fixed point: of the rest of its row, integer widths came with M1, and generics and
shapes with their names, and packages (a Seal package is the Java package), with M2.
