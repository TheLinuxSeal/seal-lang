# Calling Java from Seal: design

**Status:** reviewed 2026-10-20; T1 is next (§8). Seal uses Java's classes as it uses its own:
`import java.util.ArrayList;`, then `new ArrayList()` and `names.add(...)`, checked while compiling
and compiled to the JVM's own calls. Values cross between the two by hand: a Seal value goes into
Java through the class of the Java type it becomes (`JavaInt.of(5)`, `JavaString.of("Ada")`,
`JavaIntBoxed.of(5)`), and comes out by a cast. The Java types Seal has its own of are named in
`seal.java`: `JavaObject` is `java.lang.Object`, `JavaString` is `java.lang.String`, `JavaInt` is
Java's `int`.

This replaces the bridge built in steps B1 and B2 (2026-10-19), which named classes and methods by
strings and called them by reflection while the program ran
(`JavaBridge.get("java.time.LocalDate").invokeStatic("of", [JavaInt.of(2026), ...])`). Review
chose the typed design on 2026-10-20 and removed the bridge (§10); it is in this file's history at
commit 186ca77, and an earlier typed design, which this one takes much from, at 13c300a. Before
either, the VM was deprecated and the standard library moved into packages (§0). This is the middle
of milestone M6 in docs/JVM.md §13, and the way to a Minecraft mod in Seal.

## 0. Before Java

**Decided on review:** two changes came first.

- **The VM is deprecated** (docs/JVM.md §13, done): it can't hold Java objects, and keeping both
  backends would mean building every feature twice. Deprecated, as review chose over removing it at
  once: `--vm` still runs and dumps programs, after a warning on standard error; `vm/` and
  `codegen/` are `@Deprecated`; and it gets no new features. What it runs today it keeps running,
  and the suites keep comparing it with the JVM there; Java's classes are the JVM's alone. Removing
  it, its tests and the library's code that only the VM needs (strings and arrays on `addr`
  buffers, `StringChars`, the Seal bodies behind `@jvm` methods) is a later step of its own.
- **The standard library moves into packages** (done), as Java's is: `HashMap` is `seal.util.HashMap`,
  imported as any package's type. Built in, and visible everywhere with no import, are the
  primitive types, `array<T>` and `array<T><<N>>`, and `string` and `string<<N>>`. A `seal.lang`
  package is imported automatically, as `java.lang` is, so that `System.stdout.println` and
  `throw new IllegalArgumentException(...)` need no import (decided on review). The layout:

  | Package | Types |
  | ------- | ----- |
  | `seal.lang` (imported automatically) | `Object`, `System`, `Math`, `Integer`, `Char`, `Double`, `Float`, `Fixed`, `Hash`, `Objects`, `Operator`, `StringBuilder`, `Format`, `Iterable`, `Runnable`, `AutoCloseable`, and the exceptions `Exception`, `RuntimeException`, `ArithmeticException`, `AssertionError`, `IllegalArgumentException`, `IllegalStateException`, `IndexOutOfBoundsException`, `NumberFormatException`, `UnsupportedOperationException` |
  | `seal.util` | `List`, `Queue`, `HashMap`, `IntMap`, `Random`, `Iterator`, `Comparator`, `NoSuchElementException`, `BigUInt` (and a `BigInt`, if one is written: the library has only `BigUInt` today), `Array` (the fixed array of the spec's §13) |
  | `seal.util.function` | `Function`, `BiFunction`, `Consumer`, `BiConsumer`, `Predicate`, `Supplier` |
  | `seal.io` | `Files`, `File`, `Reader`, `Writer`, `FileReader`, `FileWriter`, `IOException`, `FileNotFoundException` |
  | `seal.java` | Java's types under Seal's names, and the conversions (§3), from T1 |

  The built-in types' files (`string.seal`, `array.seal`, and the `FixedArray` and `StringChars`
  they use) stay in no package, at the top of `stdlib/`. On the JVM, Seal's `seal.x` is
  `io.github.thelinuxseal.seal.x`, and the built-in types are in `io.github.thelinuxseal.seal.lang`
  with `seal.lang`. Every program, test and example that uses a type outside `seal.lang` gained an
  import; imports name a type in full (`import static seal.lang.Math.*;`, as Java's
  `import static java.lang.Math.*;`); a program's type in no package can't take a `seal.lang` or
  built-in type's name, but can take `seal.util`'s and the others'; and a program can't declare a
  package `seal` or one in it (SPEC §2.4, §42).

## 1. Goals

- Use any public class of the JDK or of a jar on the class path (Minecraft's and Fabric's, in a
  mod): its constructors, methods, fields, static members, generics and exceptions, as Java code
  uses them, checked while compiling.
- Calls are the JVM's own (`invokevirtual` and the rest), as fast as Java's: no reflection.
- **A visible border** (decided on review): every value that crosses goes through a call or a cast
  that names the Java type it becomes.
- Hand Seal to Java: Seal lambdas as Java's functional interfaces, Seal classes implementing Java
  interfaces and extending Java classes (§5), so Fabric can start a Seal mod and Minecraft can call
  a Seal `Block`.
- Read Java's classes, never run them, while compiling.

Not goals yet: Mixins and annotations (their own design, after T4); Java code calling Seal's
generic classes (instantiated per type, `List$int`, which Java can't name usefully); inner
(non-static) classes; arrays of arrays (`int[][]`); the VM, which can't run Java classes (§0).

## 2. What it looks like

```text
import java.util.ArrayList;
import java.util.TreeMap;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import seal.java.*;

class Main
{
    static void main()
    {
        TreeMap<JavaString, JavaIntBoxed> ages = new TreeMap();
        ages.put(JavaString.of("Grace"), JavaIntBoxed.of(85));
        ages.put(JavaString.of("Ada"), JavaIntBoxed.of(36));
        int ada = (int)ages.get(JavaString.of("Ada"));          // unboxed by a cast
        System.stdout.println(ages + " " + ada);                // {Ada=36, Grace=85} 36

        LocalDate start = LocalDate.of(JavaInt.of(2026), JavaInt.of(1), JavaInt.of(1));
        LocalDate end = start.plusDays(JavaLong.of(291));
        int<<64>> days = (int<<64>>)ChronoUnit.DAYS.between(start, end);   // Java's long, as Seal's
        System.stdout.println(days + " days, to a " + end.getDayOfWeek()); // 291 days, to a MONDAY

        ArrayList<JavaString> names = new ArrayList();
        names.add(JavaString.of("Bo"));
        names.add(JavaString.of("Ada"));
        names.sort((a, b) -> a.compareTo(b));                   // a Seal lambda as Java's Comparator
        for (JavaString n : names) {
            string s = (string)n;
            System.stdout.println(s.toUpper());
        }

        try {
            Files.readString(Path.of(JavaString.of("missing.txt")));
        } catch (IOException e) {                               // a Java exception, by its class
            System.stdout.println("no file: " + e.getMessage());
        }
    }
}
```

## 3. Types across the border

### 3.1 Java's types in Seal

| Java | Seal | A Seal value in | Out to Seal |
| ---- | ---- | --------------- | ----------- |
| `int`, `long` | `JavaInt`, `JavaLong` | `JavaInt.of(int)`, `JavaLong.of(int<<64>>)` | `(int)x`, `(int<<64>>)x` |
| `short`, `byte` | `JavaShort`, `JavaByte` | `JavaShort.of(int<<16>>)`, `JavaByte.of(int<<8>>)` | `(int<<16>>)x`, `(int<<8>>)x` |
| `float`, `double` | `JavaFloat`, `JavaDouble` | `JavaFloat.of(float)`, `JavaDouble.of(double)` | `(float)x`, `(double)x` |
| `char`, `boolean` | `JavaChar`, `JavaBoolean` | `JavaChar.of(char)`, `JavaBoolean.of(bit)` | `(char)x`, `(bit)x` |
| `java.lang.String` | `JavaString` | `JavaString.of(string)` | `(string)s` |
| `java.lang.Integer` and the other boxes | `JavaIntBoxed`, `JavaLongBoxed`, `JavaShortBoxed`, `JavaByteBoxed`, `JavaFloatBoxed`, `JavaDoubleBoxed`, `JavaCharBoxed`, `JavaBooleanBoxed` | `JavaIntBoxed.of(int)` and so on | `(int)b` and so on, which unbox |
| `java.lang.Object` | `JavaObject` | `JavaObject.of(o)`, of an object of a Seal class | `(Tag)o`, a checked cast |
| any other class or interface | itself, imported: `ArrayList`, `LocalDate` | | |
| `T[]` | `JavaArray<T>`: `JavaArray<JavaByte>` is a `byte[]` | `JavaArray.of(xs)`, a copy | `(array<int<<8>>>)a`, a copy |

- **Nothing crosses by itself** (decided on review): a Java parameter takes Java's types, so a
  Seal `int` passed where Java wants an `int` is an error that suggests `JavaInt.of(x)`; a Java
  result is Java's type, which a cast makes Seal's. So Seal's rule that a program makes its values
  where it writes them holds (§52 of the spec), and no `@implicit` is involved. This holds for the
  primitives too, though a Seal `int` is a JVM `int` already: the call names the Java type.
- **The names in `seal.java` are Java's types themselves** (decided on review), not wrappers:
  `JavaString` is `java.lang.String`, with Java's methods (`s.length()`, a `JavaInt`;
  `JavaString.valueOf(...)`, `JavaString.format(...)`) and the `of` that takes a Seal string in;
  `JavaIntBoxed` is `java.lang.Integer` (`b.intValue()`, `JavaIntBoxed.valueOf(i)`, which takes a
  `JavaInt`); `JavaObject` is `java.lang.Object` (`equals`, `hashCode`, `getClass`), and every
  Java class extends it. Java's primitives have no members: `JavaInt` is Java's `int`.
- **Free on the JVM:** a Seal `int` is a JVM `int` and a `string` a `java.lang.String` already
  (docs/JVM.md §3), so `JavaInt.of(n)`, `JavaString.of(s)`, `JavaObject.of(o)` and the casts out of
  them compile to nothing, or to the conversion a Seal argument gets: `JavaInt.of` of an
  `int<<64>>` narrows, and unsigned values go by their bits. A box calls Java's `valueOf`, an
  unboxing cast `intValue()` and the rest, and arrays are copied.
- **Java's primitive values have no operators** in Seal (proposed): a cast makes them Seal's first,
  `(int)list.size() + 1`, and a condition wants a `bit`, `while ((bit)it.hasNext())`. They can be
  held in variables and fields, passed and returned.
- **Casts out:** `(int)` of a `JavaInt`, `(int<<64>>)` of a `JavaLong`, and so on; also `(int)` of a
  `JavaIntBoxed`, which unboxes: a `null` box stops the program, as a null handle does. A cast
  converts as Seal's casts do, so `(int<<8>>)` of a `JavaInt` wraps. A cast to `string` of any
  Java value is its text, Java's `String.valueOf` (`"null"` for `null`), so `+` and `println` show
  Java values as Java prints them (settled in B1); of a `JavaString`, it is the string itself.
- **Seal's objects:** an object of a Seal class goes where Java wants a `JavaObject` by
  `JavaObject.of(o)`, and comes back by a checked cast, `(Tag)o`. Where a Java generic's type
  argument is a Seal class (`ArrayList<Tag>`), a `Tag` goes as itself: Java wants a `Tag` there.
  Seal's `Object` stays Seal's own interface (docs/JVM.md §5), and a Java object isn't one.
- **No Java form:** structs, uniques, records, Seal enums, fixed arrays, fixed point and addresses.
  Passing one is an error that says what would cross (a struct: use a class; fixed point: cast to
  `double`, then `JavaDouble.of`).
- **`null`** goes wherever Java wants a reference, and every Java reference may be `null`, as a
  Seal handle may; `x == null` asks. `==` of two Java references is identity, as for Seal's
  handles; `a.equals(b)` is Java's. A method of a `null` one stops the program, as for a null Seal
  handle.
- **Java's arrays** (as built in B2): `JavaArray.of(xs)` copies a Seal array into a new Java one (an
  `array<int<<8>>>` or `array<uint<<8>>>`, by its bits, is a `JavaArray<JavaByte>`, `array<int>` a
  `JavaArray<JavaInt>`, `array<string>` a `JavaArray<JavaString>`), `new JavaArray(n)` is one of
  Java's defaults for Java to fill (`JavaArray<JavaByte> buffer = new JavaArray(4096);`), and a
  cast copies one out (`(array<uint<<8>>>)buffer`). Both directions copy: an `array<T>` is a unique
  (§21.3 of the spec), which Java can't keep. Indexing a `JavaArray` in place waits until a program
  needs it.

### 3.2 Names and imports

- **One `import` for both:** `import java.util.ArrayList;`, `import java.util.*;`,
  `import static java.lang.Math.max;`, and a full name anywhere (`new java.util.Random(JavaLong.of(42))`)
  work for Java's classes as for Seal's packages. A name is looked up as now (§2.4 of the spec);
  a package Seal's program and library don't have is then looked for on the class path. Nested
  classes are named as Seal names its own, `Map.Entry`.
- **`java.lang` isn't imported automatically:** `seal.lang` is Seal's `java.lang`.
  `import java.lang.Thread;` brings Java's `Thread`, and a single import hides `seal.lang`'s type
  of that name in its file, as Java's single imports hide `java.lang`'s (`import java.lang.Math;`
  makes `Math` Java's there).
- **Proposed:** importing `java.lang.Object`, `java.lang.String` or a box (`java.lang.Integer`) is
  an error that names its `seal.java` name, so each has one name in Seal; `import java.lang.*;`
  brings the rest of `java.lang`.
- **Proposed:** a name that a `*` import of a Java package shares with `seal.lang`
  (`import java.lang.*;` brings a `Math`) is ambiguous where it is used: an error naming both, as
  for two `*` imports today. Importing the one meant by name settles it.
- A Java member named as a Seal keyword (a method `pure()` or `bit()`) needs a way to write it;
  one comes when a program meets one.
- A program can't declare packages under `java.` or `javax.`; the JVM refuses to load them anyway.

## 4. Calling Java

- Constructors (`new java.util.Random(JavaLong.of(42))`), static and instance methods, interface
  static and default methods, and `JavaObject`'s methods on any Java object. Public fields, read
  and written, `final` ones only read; static fields and enum constants (`ChronoUnit.DAYS`), also
  through `import static`. Protected members from a Seal class that extends the Java one (§5).
  Only public classes and members, as `javac` allows from another package.
- **Overloads, as Java chooses but without its boxing** (proposed): the methods of that name the
  arguments fit by Java's rules on Java's types (an object of a subclass where its class is wanted,
  a primitive widened where a wider one is, a `JavaInt` to a `long`), then through varargs; the
  most specific wins. Java's middle phase, which boxes, doesn't happen, as nothing crosses by
  itself: a `JavaInt` doesn't go where an `Integer` or an `Object` is wanted, and
  `JavaIntBoxed.of(n)` does. So `list.remove(JavaInt.of(0))` is `remove(int)`, and
  `list.remove(JavaIntBoxed.of(0))` is `remove(Object)`, as in Java. No method fitting, or two
  equally specific, is a compile error naming those there are; a misspelled method one naming the
  methods it might be. (The B1 bridge chose so while the program ran; its chooser is where the
  checker's starts.)
- **Varargs:** a `T...` parameter takes the elements, or a `JavaArray<T>`.
- **The JVM's calls:** `invokevirtual`, `invokeinterface`, `invokestatic` and `invokespecial`, with
  the descriptor from the class file, exactly; an erased generic result gets a `checkcast` (§4.1).
- **Exceptions:** Java's are Java classes. `catch (IOException e)`, with `import java.io.IOException;`,
  catches a Java exception by its class, and `throw new IllegalStateException(JavaString.of("x"))`,
  with `import java.lang.IllegalStateException;`, throws one. Seal has no checked exceptions, so
  nothing is declared. Seal's `catch (Exception e)` still catches only Seal's exceptions, a tree
  of their own (Seal's `seal.io.IOException` isn't `java.io.IOException`); a Seal exception that
  went through Java code comes back as itself. An uncaught Java exception ends the program as an
  uncaught Seal one does: `uncaught java.nio.file.NoSuchFileException: missing.txt`.
- `try (BufferedReader r = ...)` closes a Java `AutoCloseable`, and `for (T x : xs)` goes over a
  Java `Iterable`, its elements of its type argument (T2).

### 4.1 Generics (T2)

- **A Java generic takes type arguments that are objects to Java:** Java's classes, `JavaString`,
  the boxes, and Seal's classes: `ArrayList<JavaString>`, `TreeMap<JavaString, JavaIntBoxed>`,
  `ArrayList<Tag>`. `ArrayList<int>` is an error that suggests `JavaIntBoxed`, and
  `ArrayList<string>` one that suggests `JavaString`.
- **Erased, as Java's own code is:** one `java.util.ArrayList` class whatever its arguments (not
  instantiated per type, as Seal's generics are), with a `checkcast` where an erased result comes
  back.
- **Inferred** as Seal's are: `new TreeMap()` from the variable it initializes, a generic method
  (`Collections.max(xs)`, `List.of(...)`, Fabric's `Registry.register(...)`) from its arguments and
  the type wanted. Wildcards are read loosely: a `List<? extends Number>` gives `Number`s, a
  `Comparator<? super T>` takes a `Comparator<T>`. Where Seal can't decide, a compile error asks
  for a cast or a type argument; it never guesses.
- **Before T2,** a generic class is used raw: its type variables are their bounds (`JavaObject`,
  mostly), so `(JavaString)names.get(JavaInt.of(0))`. Whether raw use stays after T2 is §10.

## 5. Handing Seal to Java (T3, T4)

Proposed: through the compiler, which knows Java's interfaces once it reads them. B2's plan, an
`implement` making a `java.lang.reflect.Proxy` whose calls went to a handler, and the idea of Java
source beside Seal's for this, are set aside.

- **Lambdas:** a Seal lambda goes wherever Java wants an interface with one abstract method
  (`Runnable`, `Comparator<T>`, Fabric's event callbacks), with Java's types for its parameters and
  result: `names.sort((a, b) -> a.compareTo(b))`, or `(a, b) -> JavaInt.of(...)`. It becomes a class
  implementing the interface, as Seal lambdas become classes now.
- **Implementing a Java interface:**
  `class Version implements(Comparable<Version>) { public JavaInt compareTo(Version o) { ... } }`.
  For a generic interface, the backend also writes the erased bridge the JVM calls
  (`compareTo(Object)` calling `compareTo(Version)`), as `javac` does. A missing method is an
  error, as for Seal's interfaces. Fabric starts a mod by creating the class `fabric.mod.json`
  names, `class MyMod implements(ModInitializer)`, with its constructor that takes nothing.
- **Extending a Java class** (T4): `class Worker extends(Thread)`. Its constructors call a Java one
  with `super(...)` (or the one that takes nothing); it overrides Java's methods, with bridges where
  generics need them, and reaches protected members; a `final` class can't be extended, and
  abstract methods must be implemented.
- **Seal objects as Java objects:** a Seal class gets `equals(Object)` from its `==`
  (`@overload(Operator.EQUALS)`), `hashCode()` from its `hash()`, and `toString()` from its cast to
  `string`, so Java's `HashMap` finds Seal keys, and Java prints Seal objects as Seal does.
- **Java calling Seal:** Seal's classes are JVM classes with readable names (docs/JVM.md §5), so
  Java code calls their methods; a `JavaInt` parameter is Java's `int`, a `JavaString` a `String`.
- **When Java calls first** (Fabric creating a Seal class before any `main`), Seal's static fields
  are set up as for `main`.
- The library's `Runnable`, `Comparator`, `Iterable` and `AutoCloseable` stay Seal's own
  interfaces; Java's are imported by name.

## 6. In the compiler

- **A class-path reader** (`check/JavaClasses` or `jvm/ClassPath`): jars, folders, and the JDK's
  classes through its module image (`jrt:/`), only from packages its modules export, as `javac`
  allows (`java.util`, not `jdk.internal.misc`). It reads class files with the JDK's class-file
  API (`java.lang.classfile`), the one the backend writes with: a class's flags, parent, interfaces,
  generic signature, public and protected members with descriptors, generic signatures and varargs
  flags, and nested classes. It never loads a class: Minecraft's can't be loaded outside the game,
  and loading runs static initializers. It reads a class when the program first names it, then
  what its members name as each is needed, and keeps them for the compile.
- **`seal.java` in the library:** each type says which Java type it is, and adds Seal's way in:
  `@java("java.lang.String") class JavaString { public static JavaString of(string s); }`,
  `@java("int") struct JavaInt { public static JavaInt of(int x); }`. The checker gives the type
  Java's members as well (the annotation's name proposed; only the library may write it).
- **Java's classes in the checker:** each is a `ClassInfo`, as Seal's classes are, built from its
  class file: members with types and no bodies, its type parameters erased ones. They come in on
  demand, so the checker's per-class steps (headers, members, signatures) must run for a class
  added while it checks, not only at the start.
- **One type mapping,** both ways: Java descriptors and generic signatures to Seal types (§3.1),
  and where boxing, unboxing, copies and `checkcast`s happen.
- **The backend:** a Java class isn't generated; calls use its class file's descriptors (§4); the
  stack maps' class hierarchy includes Java's classes, from the reader (the class-file API's own
  resolver sees only the compiler's class loader); Seal classes implementing or extending Java
  ones get bridges and §5's `equals`, `hashCode` and `toString`.
- **The CLI:** `--classpath` (`-cp`), which `seal run` has had since B1, for `check`, `dump` and
  `build` too, as the compiler reads the classes; `seal run` puts the jars in the program's class
  loader (`JvmRunner`, since B1); `seal build` writes the program's classes, run beside the jars
  with `java -cp classes:deps.jar Main`.
- **The VM** gets none of it (§0): its code generator reports a program using Java's classes as
  running only on the JVM.

## 7. Testing

- A suite, `JavaInteropTest`, with programs using the JDK (`java.util`, `java.time`,
  `java.util.regex`, `java.io`, `java.nio.file`, `java.lang.Thread`) and their expected output.
- A small Java library the test compiles with `javax.tools` into its scratch folder and puts on the
  class path: overloads, varargs, fields, statics, generics, exceptions, a class to extend, an
  interface to implement, and Java code calling back into the Seal objects it is given.
- Every generated class passes the bytecode verifier, as now.
- The errors: an unknown or unexported class, a misspelled method, a Seal value passed without its
  `of`, an ambiguous call, a struct passed to Java, a `final` class extended.
- `examples/21-java` comes back with T1, and grows with each step.

## 8. Order of work

| Step | Contents | Done when |
| ---- | -------- | --------- |
| **R1**, **done** | §0: the VM is deprecated | `--vm` warns, every suite passes |
| **R2**, **done** | §0: the library moves into packages | the suites pass with the library in packages |
| **B1**, **B2**, **removed** | the string bridge: `JavaBridge`, `JavaObject.invoke`, Java's exceptions as `JavaThrownException`s, arrays copied; built 2026-10-19, removed 2026-10-20 for this design. Kept: `--classpath`, and what building it settled (§10) | |
| **T1** | the class-path reader; Java's classes without generics (raw, §4.1): imports, constructors, methods, fields, statics, enum constants, overloads, varargs; `seal.java`'s types (the primitives, `JavaString`, the boxes, `JavaObject`, `JavaArray`) with their `of`s and casts; `instanceof`; Java's exceptions caught and thrown | a program using `java.time`, `java.util.Random`, `java.util.regex` and `java.nio.file` prints what the same Java program prints |
| **T2** | Java's generics: type arguments, erasure and casts, inference, wildcards; for-each over an `Iterable`; `try` closing an `AutoCloseable` | a word count in a `TreeMap<JavaString, JavaIntBoxed>` |
| **T3** | Seal lambdas as Java's functional interfaces; Seal classes implementing Java interfaces, with bridges; Seal objects' `equals`, `hashCode` and `toString` | a Java `Thread` runs Seal code, a Seal `Comparator` sorts a Java list, a Java `HashMap` holds Seal keys |
| **T4** | Seal classes extending Java classes: constructors, overrides, bridges, protected members | a Seal class extends a class from the test's jar, and its Java code calls the Seal overrides |
| **T5** (rest of M6) | jars from `seal build`, the class-file target (Java 21, for Minecraft), then a Fabric mod: a Seal `ModInitializer` registering a block | the mod loads in a Fabric development run |

## 9. What will be hard

- **Generics:** inferring a Java generic method's type arguments where bounds and wildcards meet
  (`<T extends Comparable<? super T>>`) is the most involved part; §4.1 reads wildcards loosely and
  leaves the exact checks to the casts the JVM makes.
- **Overloads:** Java's rules on Java's types, without the boxing phase; tests against what `javac`
  picks for the same calls.
- **Erasure next to instantiation:** a Seal generic class using a Java one
  (`class Cache<T> { ArrayList<T> items; }`) is instantiated per `T` by Seal while the Java class
  stays one, and a `T` that isn't an object to Java is an error at that instantiation.
- **The checker's phases** assume every class is known when it starts; Java's arriving on demand
  need the per-class steps to run for them later.
- **`null`:** Java hands out `null` where Seal's values never are; casts out (an unboxing) and
  method calls are where it shows.
- **Which JDK:** the compiler sees the JDK it runs on; checking against an older one (Minecraft's
  Java 21) needs `javac --release`'s API data, and waits for T5.

## 10. Questions

Decided on review:

1. **`seal.java`**, and the whole standard library in packages (§0); **`seal.lang` is imported
   automatically**, and the big numbers are in `seal.util`.
2. **The VM is deprecated** (§0) rather than removed at once: it keeps running what it runs, gets
   no new features, and goes in a later step.
3. **Seal values go into Java by hand,** one class per Java type: `JavaInt.of(5)`,
   `JavaString.of("Ada")`, `JavaIntBoxed.of(5)` (§3.1), so the call names the Java type.
   Converting by `@implicit` constructors, or by a cast method marked `@implicit` (the same thing,
   declared on the value's type), was set aside. Asked again for the typed design (2026-10-20), and
   kept for the primitives too, though a Seal `int` is a JVM `int` already.
4. **Java's classes are imported and used as Seal's** (2026-10-20), typed, checked while compiling
   and compiled to the JVM's calls, and **the string bridge of B1 and B2 is removed**: its
   `JavaBridge`, `JavaClass`, the reflective runtime, and the `Java...Exception`s it threw (a
   misspelled class or method is now a compile error, and Java's exceptions are caught by their
   classes).
5. **`JavaString`, `JavaIntBoxed` (and the other boxes) and `JavaObject` are Java's types
   themselves** (2026-10-20), with Java's members, and Seal's `of` added (§3.1).

Settled while building the bridge, and kept:

6. **A Java value's cast to `string` is its text,** Java's `String.valueOf`, and never fails:
   Seal's cast to `string` is a type's text, which `+` and `println` use (§3.1).
7. **Arrays are copied going in and coming out,** as an `array<T>` is a unique (§3.1); a cast
   method that returns a unique makes a new one (§46 of the spec).
8. **A method of a `null` Java object stops the program,** as for a null Seal handle.

Proposed, to settle in T1 or where noted:

9. **No automatic boxing in overloads** (§4): a `JavaInt` never goes where an `Integer` is wanted.
10. **Java's primitive values have no operators** in Seal (§3.1); a cast makes them Seal's.
11. **`java.lang.Object`, `String` and the boxes have one name each,** `seal.java`'s; importing them
    by Java's name is an error that names it (§3.2).
12. **A name a Java `*` import shares with `seal.lang`** is ambiguous where used (§3.2).
13. **Raw generic types after T2:** still allowed, as Java allows them, or an error once type
    arguments work (T2).
14. **Seal's faults** (a null handle, division by zero): proposed, uncatchable, as now, whatever a
    `catch` names; or caught by `catch (java.lang.NullPointerException e)` and its kin, as in Java.
15. **Handing Seal to Java through the compiler** (§5), rather than B2's `Proxy` plan or Java source
    beside Seal's.
