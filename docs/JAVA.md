# Calling Java from Seal: design

**Status:** proposed, for review; nothing is built yet. The questions in §12 need answers before
work starts. This is the middle of milestone M6 in docs/JVM.md §13 (jars and the class-file
target, then calling Java, then Mixins), and the way to a Minecraft mod in Seal.

Seal compiles to JVM classes (docs/JVM.md). This document plans the other direction: a Seal
program using classes it didn't write, from the JDK (`java.util.ArrayList`, `java.time.LocalDate`)
or from a jar (Minecraft, Fabric, any library), and Seal classes that Java code uses: implementing
Java interfaces, extending Java classes, and behaving as Java expects objects to.

## 1. Goals

- Use any public class of the JDK or of a jar on the class path: construct it, call its methods,
  read and write its fields, use its static members, its generics, its exceptions.
- Hand Seal code to Java: a Seal lambda where Java wants a `Comparator` or a `Runnable`, a Seal
  class that implements a Java interface or extends a Java class (Fabric's `ModInitializer`,
  Minecraft's `Block`), and Seal objects that work as keys of Java maps and print as Java expects.
- Keep Seal's own model intact: values, uniques, shapes and Seal's generics behave as they do now.
  What Java can't express (structs, fixed point, addresses) stays on the Seal side, with an error
  that says so where it would cross.
- Read Java's classes, never run them, while compiling: no static initializers run, any JDK works.

Not goals yet: Mixins and annotations (their own design, after this); Java code calling Seal's
generic classes (they are instantiated per type, `List$int`, which Java can't name usefully); inner
(non-static) classes; arrays of arrays (`int[][]`); the VM, which can't run Java classes (§10).

## 2. What it looks like

```text
import java.util.ArrayList;
import java.util.HashMap as JavaHashMap;   // the library's HashMap keeps its name (§4)
import java.time.LocalDate;
import java.io.IOException;
import static java.lang.Math.floorMod;

class Main
{
    static void main()
    {
        ArrayList<string> names = new ArrayList();
        names.add("Ada");
        names.add("Bo");
        names.sort((a, b) -> b.length() - a.length());       // a Seal lambda as a java.util.Comparator
        for (string n : names) { System.stdout.println(n); }  // for-each over a java.lang.Iterable

        JavaHashMap<string, int> ages = new JavaHashMap();    // int boxes to Integer and back (§5.4)
        ages.put("Ada", 36);
        int age = ages.get("Ada");

        LocalDate d = LocalDate.of(2026, 10, 19);
        System.stdout.println(d.getDayOfWeek() + " " + floorMod(-7, 3));

        try {
            java.nio.file.Files.readString(java.nio.file.Path.of("missing"));
        } catch (IOException e) {                             // a Java exception, caught by its class
            System.stdout.println("no file: " + e.getMessage());
        }
    }
}

class Worker extends(java.lang.Thread)                         // a Seal class extending a Java one
{
    public void run() { System.stdout.println("working"); }
}

class Version implements(java.lang.Comparable<Version>)       // and implementing a Java interface
{
    public int major;
    public int compareTo(Version o) { return major - o.major; }
}
```

## 3. Finding Java's classes

- **Proposed:** `--classpath` (`-cp`) on `seal run`, `check`, `build` and `dump` names jars and
  folders of class files, as `java -cp` does. The JDK's own classes come from the JDK the compiler
  runs on, through its module image (`jrt:/`), and only from packages its modules export, as
  `javac` allows: `java.util` yes, `jdk.internal.misc` no.
- **Proposed: the compiler reads class files; it never loads them.** It reads them with the JDK's
  class-file API (`java.lang.classfile`), the same one the backend writes with: a class's name,
  flags, parent and interfaces, its generic signature (the `Signature` attribute), its public and
  protected fields and methods with their descriptors and generic signatures, varargs flags, and
  nested classes. Loading the classes instead (reflection) would run their static initializers and
  tie the compiler to one class loader; reading them needs neither.
- **Proposed: lazily.** The JDK has thousands of classes and Minecraft tens of thousands. A Java
  class is read when the program first names it (an import, a full name, a type in a member it
  uses), then its parents, then the types its members name as each is first needed, and kept for
  the rest of the compile.
- Running: `seal run` gives its class loader the class path's jars. `seal build` writes the
  program's classes as now; the jars are run beside them, `java -cp classes:deps.jar Main`.

## 4. Names and imports

- **Proposed: one `import` for both.** `import java.util.ArrayList;`, `import java.util.*;`,
  `import static java.lang.Math.floorMod;`, and a full name anywhere (`new java.util.Random(42)`)
  work for Java classes as for Seal's packages. A name is looked up as now (§2.4 of the spec): the
  type itself and around it, imports by name, its own package, `*` imports, the standard library;
  a name in a package Seal's program doesn't have is then looked for on the class path.
- `java.lang` isn't imported automatically: Seal's standard library is Seal's `java.lang`.
  `Thread`, `StringBuilder` (Java's) and `Integer` (Java's) need an import, and Seal's `Math`,
  `Integer`, `Exception`, `List`, `HashMap` and `Object` keep their meanings.
- Clashes with the library are what `import ... as` is for: `import java.util.HashMap as
  JavaHashMap;`. A `*` import of a Java package bringing a name the library has is §12, question 3.
- A program can't declare packages under `java.` or `javax.`; the JVM refuses to load them anyway.

## 5. Types across the border

### 5.1 Numbers

| Java | Seal |
| ---- | ---- |
| `boolean` | `bit` |
| `byte`, `short`, `int`, `long` | `int<<8>>`, `int<<16>>`, `int`, `int<<64>>` |
| `char` | `char` |
| `float`, `double` | `float`, `double` |

A Java parameter or field takes a Seal number by Seal's own rules (§41.6 of the spec): it widens
without a cast and narrows where it is passed, as when calling a Seal method, with `-WautoCast`
showing each. Unsigned types pass their bits (`uint` to Java's `int`), as a cast does; Java has no
unsigned types, so it reads them as signed. Fixed point has no Java form: cast to `double`.

### 5.2 Strings, objects, arrays

- **String:** a Java `String` is a Seal `string`, the same object; it already is on the JVM. What a
  `null` String from Java becomes is §12, question 2.
- **Java classes and interfaces** are Seal class types, held by handles like Seal's classes:
  shared, garbage-collected, possibly `null`. `==` compares them by identity, as for a Seal class
  without its own `==`; `a.equals(b)` is Java's.
- **Seal's `Object`:** §12, question 1, proposes that Seal's root `Object` become
  `java.lang.Object`, so that every Java object is a Seal `Object` (in a `List<Object>`, for
  `Object` parameters) and every Seal object a Java one.
- **Arrays:** a Java `T[]` is a Seal `array<T>` for any `T` this table maps: `int[]` is
  `array<int>`, `String[]` is `array<string>`, `Thread[]` is `array<Thread>`. On the JVM an
  `array<T>` already is a Java array, but `array<int<<8>>>` is an `int[]` today, which a Java
  method taking `byte[]` can't use; §12, question 6, proposes 8- and 16-bit integers become
  `byte` and `short` in arrays and fields. Arrays of arrays (`int[][]`) wait: Seal's `array<T>` is
  a unique and can't hold arrays.

### 5.3 What stays on the Seal side

Structs, uniques, records, Seal enums, fixed arrays, fixed point and addresses have no Java form.
Passing one where Java wants an object is an error that says what would cross (a struct: use a
class, or copy its fields; a fixed array: `array<T>` takes a copy; fixed point: cast to `double`).

### 5.4 Generics and boxing

- **Proposed: a Java generic takes Seal type arguments,** `ArrayList<string>`,
  `HashMap<string, Point>`, checked as Seal checks types. On the JVM it is erased, as Java's own
  code is: one `java.util.ArrayList` class whatever its argument (not instantiated per type as
  Seal's generics are), with a `checkcast` where an erased result comes back.
- **Proposed: numbers box where Java needs an object** (§12, question 4): `ArrayList<int>` holds
  `Integer`s; a Seal `int` passed to an `Object` parameter or a `T` becomes an `Integer`, and one
  read back as an `int` is unboxed; unboxing `null` stops the program, as a null handle does. Each
  width boxes as the JVM holds it: `int<<8>>` as `Byte`, `int<<64>>` and `uint<<64>>` as `Long`,
  `bit` as `Boolean`, `char` as `Character`. Seal's own generics never box, as now.
- Generic methods (`Collections.max(xs)`, `List.of(1, 2)`, `Map.of(...)`) infer their type
  arguments from the arguments and the type wanted, as Seal's generic methods do. Wildcards are
  read loosely: a `List<? extends Number>` gives `Number`s, a `Comparator<? super T>` takes a
  `Comparator<T>`. Raw types (`ArrayList` with no arguments) aren't allowed; the JVM checks the
  casts the erasure needs, so a mistake there is a fault where it happens.

## 6. Calling Java

- Constructors (`new java.util.Random(42)`), static and instance methods, interface static and
  default methods, and `java.lang.Object`'s methods on any Java object.
- Public fields, read and written; `final` ones only read; static ones too, also through
  `import static`. Protected members from a Seal class that extends the Java one (§7).
- **Overloads:** a Java method's overloads are Seal overloads of the mapped signatures, and Seal's
  resolution picks one; where Java would prefer one candidate over another (`println(char)` over
  `println(int)` for a `char`), Seal's rules already agree. Boxing is a last resort, as in Java.
- **Varargs:** a `T...` parameter takes an `array<T>`, or the elements, which are put in one.
- **Exceptions:** a Java method may throw anything, and Seal has no checked exceptions, so nothing
  is declared. `catch (java.io.IOException e)` catches a Java exception by its class; Seal's
  `catch (Exception e)` still catches Seal's exceptions, which are a separate tree (Seal's
  `IOException` isn't `java.io.IOException`). `throw new java.lang.IllegalStateException("x")`
  throws a Java one. Faults are §12, question 5.
- `try (java.io.BufferedReader r = ...)` closes anything that implements `java.lang.AutoCloseable`,
  as it closes Seal's `AutoCloseable`s.
- `for (T x : xs)` goes over a `java.lang.Iterable`, as over Seal's.
- **Text:** `"day " + d` with a Java object uses its `toString()`, as a Seal class's cast to
  `string` is used, and `System.stdout.println(d)` prints it so.
- A Seal `string`'s methods are Seal's; Java's `String` methods it lacks are §12, question 7.

## 7. Handing Seal to Java

- **Lambdas:** a Seal lambda goes wherever Java wants an interface with one abstract method
  (`Runnable`, `Comparator<T>`, `java.util.function.Function<A, B>`, Minecraft's callbacks): it
  becomes a class implementing that interface, as Seal lambdas already become classes. Its erased
  method takes `Object`s and casts or unboxes them for the Seal code.
- **Implementing a Java interface:** `class Version implements(java.lang.Comparable<Version>)`.
  A Seal method implements a Java one with the same name and mapped parameter types; for a generic
  interface, the backend also writes the erased bridge the JVM calls (`compareTo(Object)` calling
  `compareTo(Version)`), as `javac` does. Missing methods are an error, as for Seal interfaces.
- **Extending a Java class:** `class Worker extends(java.lang.Thread)`. Its constructors call a
  Java constructor with `super(...)` (or the no-argument one); it overrides Java methods, with
  bridges where generics need them, and reaches protected members; a `final` class can't be
  extended, and abstract methods must be implemented.
- **Seal objects as Java objects:** a Seal class gets `equals(Object)` from its `@overload(
  Operator.EQUALS)`, `hashCode()` from its `hash()`, and `toString()` from its cast to `string`, so
  Java's `HashMap` and `HashSet` find Seal keys, and Java prints Seal objects as Seal does. (This
  needs §12, question 1.)
- The library's `Runnable`, `Comparator`, `Function`, `Iterable` and `AutoCloseable` stay Seal's
  own interfaces: a Seal lambda can be either, by what the code wants. Making them Java's would
  mean erasing Seal's generic interfaces, which Seal instantiates per type; that can come later.

## 8. In the compiler

- **A class-path reader** (`check/JavaClasses` or `jvm/ClassPath`): jars, folders and `jrt:/`,
  read with `java.lang.classfile`, lazily, cached, knowing which JDK packages are exported.
- **Java classes in the checker:** each becomes a `ClassInfo`, as Seal's classes are, built from a
  declaration made from its class file: members with types and no bodies, marked external (as the
  library's `@native` methods have no bodies), its type parameters Seal's, and a flag saying a
  Java generic is erased rather than instantiated. They come in on demand, so the checker's
  per-class steps (headers, members, signatures) must run for a class added while it checks, not
  only at the start.
- **A type mapping,** both ways, in one place: Java descriptors and generic signatures to Seal
  types (§5), and where boxing, unboxing and `checkcast` happen.
- **The backend:** an external class isn't generated; calls use the descriptor from its class
  file, exactly, with `invokevirtual`, `invokeinterface`, `invokestatic` or `invokespecial`; erased
  results get a `checkcast`; numbers box and unbox where §5.4 says; Seal classes implementing or
  extending Java ones get bridges and the `equals`, `hashCode` and `toString` of §7; the class
  hierarchy the stack maps need includes Java's classes, from the reader.
- **The runner and the CLI:** `--classpath`, the jars in `seal run`'s class loader, and a note in
  `seal build`'s output of what to run it with.
- **The VM:** can't run Java classes; `--vm` on a program that uses them says so (§12, question 8).

## 9. Testing

- A new suite, `JavaInteropTest`, with programs using the JDK (`java.util`, `java.time`,
  `java.util.regex`, `java.io`, `java.nio.file`, `java.util.function`, `java.lang.Thread`) and
  their expected output on the JVM.
- A small Java library the test compiles with `javac` into its scratch folder and puts on the
  class path: overloads, varargs, fields, statics, generics, exceptions, a class to extend, an
  interface to implement, and Java code calling back into the Seal objects it is given, which is
  the direction only a test written in Java checks.
- Every generated class passes the bytecode verifier, as now.
- The errors: an unknown or unexported class, a final class extended, a missing method, a struct
  passed to Java, unboxing `null`, a `*` import clash.

## 10. Order of work

| Step | Contents | Done when |
| ---- | -------- | --------- |
| **J0** | §12 decided; the class-path reader; `--classpath`; 8- and 16-bit numbers as `byte` and `short` (if decided); Seal's `Object` as `java.lang.Object` (if decided) | the suites pass as now, and the reader lists any JDK class's members |
| **J1** | Java classes without generics: constructors, methods, fields, statics, overloads, varargs, arrays, strings, exceptions caught and thrown, imports and `import static` | a program using `java.time`, `java.util.Random`, `java.util.regex` and `java.nio.file` prints what the same Java program prints |
| **J2** | Java's generics: type arguments, erasure and casts, boxing, generic methods, wildcards, for-each over `Iterable` | a word count in a `java.util.TreeMap<string, int>` |
| **J3** | Seal lambdas as Java's functional interfaces; Seal classes implementing Java interfaces, with bridges; Seal objects with `equals`, `hashCode` and `toString` | a stream pipeline (`names.stream().filter(...).map(...).toList()`), and a Java `HashMap` with Seal keys |
| **J4** | Seal classes extending Java classes: constructors, overrides, bridges, protected members | a Seal class extends a class from the test's jar, and its Java code calls the Seal overrides |
| **J5** (rest of M6) | jars from `seal build`, the class-file target (Java 21 for Minecraft), then a Fabric mod: a Seal `ModInitializer` registering a block | the mod loads in a Fabric development run |

## 11. What will be hard

- **Generics:** inferring a Java generic method's type arguments where bounds and wildcards meet
  (`<T extends Comparable<? super T>>`) is the most involved part; the plan reads wildcards
  loosely (§5.4) and leaves the exact checks to the casts the JVM makes.
- **Overloads** where Java's three phases (exact, then boxing, then varargs) and Seal's rules pick
  differently; tests against what `javac` picks.
- **Erasure next to monomorphization:** a Seal generic class using a Java generic one
  (`class Cache<T> { java.util.ArrayList<T> items; }`) is instantiated per `T` by Seal while the
  Java class stays one, so the boxing and casts depend on each instantiation's `T`.
- **The checker's phases** assume every class is known when it starts; Java classes arriving on
  demand need the per-class steps to run for them later.
- **`null`:** Java hands out `null` where Seal's values never are; strings (§12) and unboxing are
  where it shows.
- **Which JDK:** the compiler sees the JDK it runs on; checking against an older one (Minecraft's
  Java 21) needs `javac --release`'s API data, and waits for J5.

## 12. Questions to decide

1. **Seal's `Object` becomes `java.lang.Object`?** Proposed: yes. Every Java object is then a Seal
   `Object`, and Seal objects get `equals`, `hashCode` and `toString` (§7). Today Seal's `Object`
   is an interface of its own (docs/JVM.md §5), with `hash()`; that would become `hashCode()`.
2. **A `null` String from Java:** (a) proposed: a string may be `null` when it comes from Java, as
   in Java; `s == null` is allowed on any string (an error today), and using a `null` one stops the
   program, as a null handle does; Seal's own strings still start as `""` and are never `null`.
   (b) It becomes `""` where it arrives, as a string field never set reads as `""`, losing the
   difference (`map.get` can't say "absent"). (c) It stops the program where it arrives.
3. **`import java.util.*;` brings `List` and `HashMap`, which the library has:** (a) proposed:
   using such a name is an error naming both, as for two `*` imports today; import the one meant
   by name, or rename it. (b) Java's wins, as `*` imports come before the library. (c) The library
   always wins over a `*` import.
4. **Boxing:** proposed: numbers box and unbox by themselves where Java needs objects
   (`ArrayList<int>`, `Object` parameters), and only there. Or: only Java's wrapper classes
   (`ArrayList<java.lang.Integer>`), boxed by hand.
5. **Faults:** proposed: `catch (java.lang.NullPointerException e)` and
   `catch (java.lang.Throwable t)` catch what Java throws for them, including Seal's faults (a null
   handle, division by zero), as in Java; Seal's `catch (Exception e)` still never catches a fault.
   Or: faults stay uncatchable, whatever the `catch` names.
6. **8- and 16-bit numbers as `byte` and `short`:** proposed: yes, in arrays and fields on the JVM,
   so `array<int<<8>>>` is a Java `byte[]` (and four times smaller); values on the stack stay
   `int`s, as the JVM's own are.
7. **Java's `String` methods on Seal strings** (`s.strip()`, `s.chars()`): proposed: not now;
   Seal's `string` keeps its own methods.
8. **The VM:** Java classes can't run on it, and the suites' VM-against-JVM comparison can't cover
   them. Proposed: retire the VM (docs/JVM.md §13) before J1, so the work is done once; or keep it,
   with `--vm` refusing programs that use Java and the interop suite JVM-only.
