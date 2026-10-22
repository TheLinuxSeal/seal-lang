# Calling Java from Seal: design

**Status:** reviewed 2026-10-20; T1 to T5 are built (§8), and §10 records what building them
settled: a Fabric mod written in Seal loads in Minecraft 26.3 (`examples/22-fabric-mod`). Its Mixins are Java, a
program's own `.java` files beside its `.seal` ones (§11), built too. Seal uses Java's classes as it uses its own:
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
  and the suites keep comparing it with the JVM there; Java's classes are the JVM's alone. Since
  then, it and its tests are removed (docs/JVM.md §13), and `--vm` says so, and so is the
  library's code that only the VM needed (strings and arrays on `addr` buffers, `StringChars`,
  the Seal bodies behind `@jvm` methods).
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

  The built-in types' files (`string.seal`, `array.seal`, and the `FixedArray` they use) stay in
  no package, at the top of `stdlib/`. On the JVM, Seal's `seal.x` is `io.github.thelinuxseal.seal.x`,
  and the built-in types are in `io.github.thelinuxseal.seal.lang` with `seal.lang`. Every program, test and example that uses a type outside `seal.lang` gained an
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

Not goals yet: Mixins and annotations written in Seal (Mixins are Java, §11); Java code calling Seal's
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

class Main {
    static void main() {
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
| `java.lang.Object` | `JavaObject` | `JavaObject.of(o)`, of an object of a Seal class | `(Tag)o`, a checked cast; `(int)o`, as Java's, an `Integer` unboxed |
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
- **Java's operators on Java's primitives** (decided on review, 2026-10-21): `a + b` on two
  `JavaInt`s is Java's `a + b`, a `JavaInt`; Java promotes as it does (a `JavaByte + JavaByte` is a
  `JavaInt`, a `JavaInt + JavaLong` a `JavaLong`), and so do `-`, `*`, `/`, `%`, `&`, `|`, `^`, `~`,
  `==`, `<` and the rest, compound assignments (which narrow back, `b += c` on bytes being
  `b = (byte)(b + c)`) and `++`. `&&`, `||`, `!` and the bitwise operators take `JavaBoolean`s. A
  comparison is a `JavaBoolean`, so a condition still wants a cast, `if ((bit)(a < b))`, as
  `while ((bit)it.hasNext())`. **Java's values and Seal's don't mix:** a `JavaInt + int` is an
  error, and so is `a + 1`: `JavaInt.of(1)` makes the 1 Java's, or `(int)a` the `a` Seal's. Seal's
  `<<<` and `**` aren't Java's, and take no Java values.
- **Casts out:** `(int)` of a `JavaInt`, `(int<<64>>)` of a `JavaLong`, and so on; also `(int)` of a
  `JavaIntBoxed`, which unboxes: a `null` box stops the program, as a null handle does. As in Java,
  a `JavaObject` casts to the Seal type of a box's primitive (`(int)o`, `(double)o`, `(bit)o`):
  checked to be that box while the program runs, then unboxed (settled in T1). A cast
  converts as Seal's casts do, so `(int<<8>>)` of a `JavaInt` wraps. A cast to `string` of any
  Java value is its text, Java's `String.valueOf` (`"null"` for `null`), so `+` and `println` show
  Java values as Java prints them (settled in B1); of a `JavaString`, it is the string itself.
- **Seal's objects:** an object of a Seal class goes where Java wants a `JavaObject` by
  `JavaObject.of(o)`, and comes back by a checked cast, `(Tag)o`. Where a Java generic's type
  argument is a Seal class (`ArrayList<Tag>`), a `Tag` goes as itself: Java wants a `Tag` there.
  Seal's `Object` stays Seal's own interface (docs/JVM.md §5), and a Java object isn't one.
  Seal's `==` compares Java objects by identity, and `JavaObject`'s `hash()` matches it, so a Java
  object can be a key of Seal's `HashMap` (settled in T1).
- **No Java form:** structs, uniques, records, Seal enums, fixed arrays, fixed point and addresses.
  Passing one is an error that says what would cross (a struct: use a class; fixed point: cast to
  `double`, then `JavaDouble.of`).
- **`null`** goes wherever Java wants a reference, and every Java reference may be `null`, as a
  Seal handle may; `x == null` asks. `==` of two Java references is identity, as for Seal's
  handles; `a.equals(b)` is Java's. A method of a `null` one stops the program, as for a null Seal
  handle.
- **Java's arrays** (as built in B2): `JavaArray.of(xs)` copies a Seal array, a fixed one too, into a new Java one (an
  `array<int<<8>>>` or `array<uint<<8>>>`, by its bits, is a `JavaArray<JavaByte>`, `array<int>` a
  `JavaArray<JavaInt>`, `array<string>` a `JavaArray<JavaString>`), `new JavaArray(n)` is one of
  Java's defaults for Java to fill (`JavaArray<JavaByte> buffer = new JavaArray(4096);`), and a
  cast copies one out (`(array<uint<<8>>>)buffer`). `JavaArray.of(xs, convert)` makes a new one
  of what a function makes of each element, of any array, fixed ones too:
  `JavaArray.of(xs, JavaInt::of)` (built 2026-10-21). Both directions copy: an `array<T>` is a
  unique (§21.3 of the spec), which Java can't keep. In place, as Java writes them (built in T2): `a[i]` is
  an element, read and assigned (`a[i] += x`, `a[i]++`), by an `int` or a `JavaInt`; `a.length` its
  length, a `JavaInt`; and for-each goes over one. `new JavaArray<JavaString>(0)` names the element
  type where nothing else does (an argument).

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

### 4.1 Generics (T2, built)

- **A Java generic takes type arguments that are objects to Java:** Java's classes, `JavaString`,
  the boxes, and Seal's classes: `ArrayList<JavaString>`, `TreeMap<JavaString, JavaIntBoxed>`,
  `ArrayList<Tag>`. `ArrayList<int>` is an error that suggests `JavaIntBoxed`, and
  `ArrayList<string>` one that suggests `JavaString`.
- **Erased, as Java's own code is:** one `java.util.ArrayList` class whatever its arguments (not
  instantiated per type, as Seal's generics are), with a `checkcast` where an erased result comes
  back.
- **Inferred** as Java infers them: `new TreeMap()` from the variable it initializes, also through
  a parent (`List<JavaString> l = new ArrayList()` makes an `ArrayList<JavaString>`, as Java's
  `new ArrayList<>()`); a generic method's (`Collections.max(xs)`, `List.of(...)`, Fabric's
  `Registry.register(...)`) from its arguments, then from the type wanted (`List<JavaString> l =
  List.of()`), or given: `Collections.<JavaString>emptyList()`. Two arguments of different
  classes make a `JavaObject`; one no argument decides is what it erases to.
- **Wildcards** are Java's own: a type argument that is `? extends X` takes X and below, `? super X`
  X and above (`addAll(Collection<? extends E>)` takes an `ArrayList<JavaString>` for a `List<JavaString>`).
  Where a member's type is a wildcard, Seal reads it loosely: a `List<? extends Number>`'s element
  is a `Number`, and its parameter takes one (where Java would take nothing but `null`); the JVM's
  casts check what Java's own code doesn't.
- **Raw types stay,** as Java has them (settled in T2): a generic class without its arguments
  (`ArrayList`), whose type variables are their erasures (`JavaObject`, mostly) and whose parents
  are raw too; it goes where a parameterized one is wanted, and back, unchecked, as in Java.
- **For-each** goes over a Java `Iterable`, its elements of its type argument, and over a Java
  array; **try-with-resources** closes a Java `AutoCloseable`, adding what its `close()` throws to
  what is already on its way, as Java's does.
- Bounds aren't checked (`Collections.sort` of a list of something not `Comparable`): the JVM's
  cast finds it while the program runs.

## 5. Handing Seal to Java (T3 and T4 built)

Through the compiler, which knows Java's interfaces once it reads them (built in T3). B2's plan, an
`implement` making a `java.lang.reflect.Proxy` whose calls went to a handler, and the idea of Java
source beside Seal's for this, are set aside.

- **Lambdas:** a Seal lambda goes wherever Java wants an interface with one abstract method
  (`Runnable`, `Comparator<T>`, Fabric's event callbacks), with Java's types for its parameters and
  result: `names.sort((a, b) -> a.compareTo(b))`, or `(a, b) -> JavaInt.of(...)`. It becomes a class
  implementing the interface, as Seal lambdas become classes now, with the method's erased
  descriptor (`compare(Object, Object)`), whose parameters it casts to their Seal types. The
  interface's method is found as Java finds a functional interface's: not a default one, nor one
  of `Object`'s it names again (`Comparator.equals`); a wildcard in the type it goes to is its bound
  (`Comparator<? super JavaString>`). Among overloads, it fits those whose method takes as many
  parameters, and gives a value if the lambda does (`submit(() -> x)` is `submit(Callable)`,
  `submit(() -> println(x))` `submit(Runnable)`, as Java chooses); a generic method's type
  arguments may come from what it gives (`stream.map(s -> s.toUpperCase())` is a `Stream<JavaString>`).
  A method reference is the lambda it stands for (§30.3 of the spec), so it goes there too:
  `names.sort(JavaString::compareToIgnoreCase)`, `names.forEach(System.stdout::println)`.
- **Implementing a Java interface:**
  `class Version implements(Comparable<Version>) { public JavaInt compareTo(Version o) { ... } }`.
  For a generic interface, the backend also writes the erased bridge the JVM calls
  (`compareTo(Object)` calling `compareTo(Version)`), as `javac` does. A missing method is an
  error, as for Seal's interfaces; the interface's default methods are inherited; and a Seal
  interface may extend a Java one. Fabric starts a mod by creating the class `fabric.mod.json`
  names, `class MyMod implements(ModInitializer)`, with its constructor that takes nothing.
- **Extending a Java class** (built in T4): `class Worker extends(Thread)`. Its constructors call a
  Java one, public or protected, with `super(...)`, chosen as Java chooses, or else the one that
  takes nothing (an error if there is none); it overrides Java's methods, with javac's bridges where
  their JVM descriptors differ (a generic parent's `put(Object)` calling `put(JavaString)`, a
  `byte` parameter, which a `JavaByte` holds as an int), and Java's code calls the overrides;
  `super.m()` calls the parent's own. It reaches the parent's protected members, which no other
  code does; a `final` class can't be extended, nor a `final` method overridden, an override returns
  what Java's returns, and abstract methods must be implemented (Java's own and defaults count, as
  List's `reversed()` for a class extending `ArrayList`). Its objects are Java's: they go where Java
  wants its parent or an `Object` as they are, and without a cast of its own, its cast to `string`
  is Java's `toString()`.
- **Seal objects as Java objects** (built in T3): a Seal class gets `equals(Object)` from its `==`
  (`@overload(Operator.EQUALS)`, true for an object of its parameter's class that == calls equal),
  `hashCode()` from its `hash()` (its low 32 bits), and `toString()` from its cast to `string`, so
  Java's `HashMap` finds Seal keys, and Java prints Seal objects as Seal does. A class without them
  keeps Java's own: identity, as Seal's defaults are. A Seal object goes where Java wants a type
  argument that is its class, or an interface its class implements; where Java wants an `Object`
  (`HashMap.get(Object)`), it still goes by `JavaObject.of(o)`.
- **Java calling Seal:** Seal's classes are JVM classes with readable names (docs/JVM.md §5), so
  Java code calls their methods; a `JavaInt` parameter is Java's `int`, a `JavaString` a `String`.
- **When Java calls first** (built in T5), Fabric creating a Seal class before any `main`: a
  library's build (`seal build --library`) has every class of the program, and each one's static
  initializer sets up all of Seal's static fields first, once, in Seal's order, as `main` would.
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
- **`seal.java` in the library** (as built in T1): its files declare only Seal's ways in, `of`
  methods marked `@jvm` for the runtime's `JavaValues`. The checker knows which Java type each name
  is: `JavaString` is read from `java.lang.String`'s class file, with the library's `of` added;
  `JavaInt` is the type `JavaPrimT` of Java's `int`; `JavaArray<T>` is the library's class, which
  the backend writes as `T[]`.
- **Java's classes in the checker:** each is a `ClassInfo`, as Seal's classes are, built from its
  class file: public members with their erased types and no bodies, and the class-file member each
  was made from. They come in on demand, as a name needs one, and get their shape and members then;
  the steps that check Seal's own classes skip them, as `javac` checked them. A call's methods are
  found through the class's parents and interfaces, as Java finds them, and chosen by §4's rules.
- **One type mapping,** both ways: Java descriptors and generic signatures to Seal types (§3.1),
  and where boxing, unboxing, copies and `checkcast`s happen.
- **The backend:** a Java class isn't generated; calls use its class file's descriptors (§4); the
  stack maps' class hierarchy includes Java's classes, from the reader (the class-file API's own
  resolver sees only the compiler's class loader); Seal classes implementing or extending Java
  ones get bridges and §5's `equals`, `hashCode` and `toString`.
- **The CLI** (built in T1): `--classpath` (`-cp`) for every command, as the compiler reads the
  classes; `seal run` puts the jars in the program's class loader; `seal build` writes the
  program's classes and the runtime's, run beside the jars with `java -cp classes:deps.jar Main`.
- **The VM** got none of it (§0): its code generator reported a program using Java's classes as
  running only on the JVM, until the VM was removed.

## 7. Testing

- A suite, `JavaInteropTest`, with programs using the JDK (`java.util`, `java.time`,
  `java.util.regex`, `java.io`, `java.nio.file`, `java.lang.Thread`) and their expected output.
- A small Java library the test compiles with `javax.tools` into its scratch folder and puts on the
  class path: overloads, varargs, fields, statics, generics, exceptions, a class to extend, an
  interface to implement, and Java code calling back into the Seal objects it is given.
- Every generated class passes the bytecode verifier, as now.
- The errors: an unknown or unexported class, a misspelled method, a Seal value passed without its
  `of`, an ambiguous call, a struct passed to Java, a `final` class extended.
- `examples/21-java`, which grows with each step. Built in T1: `JavaInteropTest`, and a class path
  of a folder and a jar in `CliTest`.

## 8. Order of work

| Step | Contents | Done when |
| ---- | -------- | --------- |
| **R1**, **done** | §0: the VM is deprecated | `--vm` warns, every suite passes |
| **R2**, **done** | §0: the library moves into packages | the suites pass with the library in packages |
| **B1**, **B2**, **removed** | the string bridge: `JavaBridge`, `JavaObject.invoke`, Java's exceptions as `JavaThrownException`s, arrays copied; built 2026-10-19, removed 2026-10-20 for this design. Kept: `--classpath`, and what building it settled (§10) | |
| **T1**, **done** | the class-path reader; Java's classes without generics (raw, §4.1): imports, constructors, methods, fields, statics, enum constants, overloads, varargs; `seal.java`'s types (the primitives, `JavaString`, the boxes, `JavaObject`, `JavaArray`) with their `of`s and casts; `instanceof`; Java's exceptions caught and thrown | a program using `java.time`, `java.util.Random`, `java.util.regex` and `java.nio.file` prints what the same Java program prints (`JavaInteropTest`, which compiles the Java program and compares); `examples/21-java` |
| **T2**, **done** | Java's generics: type arguments, erasure and casts, inference, wildcards; for-each over an `Iterable`; `try` closing an `AutoCloseable`. With it: Java's operators on Java's primitives, and Java arrays' elements in place | a word count in a `TreeMap<JavaString, JavaIntBoxed>` (`JavaInteropTest`) |
| **T3**, **done** | Seal lambdas as Java's functional interfaces; Seal classes implementing Java interfaces, with bridges; Seal objects' `equals`, `hashCode` and `toString` | a Java `Thread` runs Seal code, a Seal `Comparator` sorts a Java list, a Java `HashMap` holds Seal keys (`JavaInteropTest`) |
| **T4**, **done** | Seal classes extending Java classes: constructors, overrides, bridges, protected members | a Seal class extends a class from the test's class folder, and its Java code calls the Seal overrides (`JavaInteropTest`) |
| **T5**, **done** | jars from `seal build` (`--jar`, `--resources`), libraries (`--library`), static fields set up when Java calls first, then a Fabric mod: a Seal `ModInitializer` registering a Seal `Block` | the mod loads in Fabric's server for Minecraft 26.3 and registers its block (`examples/22-fabric-mod/run.sh`) |
| **Mixins**, **done** | a program's own `.java` files, compiled by the JDK's `javac` before Seal's code or after it (§11); the mod gets a mixin | the mod's mixin, in Java, runs in Minecraft's code and calls the mod's Seal code (`examples/22-fabric-mod/run.sh`); `CliTest` |

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
- **Which JDK:** the compiler sees the JDK it runs on, and writes Java 25's class files.
  Minecraft 26.x runs on Java 25, so a mod needs nothing else (§10, item 26); an older target
  would need `javac --release`'s API data.

## 10. Questions

Decided on review:

1. **`seal.java`**, and the whole standard library in packages (§0); **`seal.lang` is imported
   automatically**, and the big numbers are in `seal.util`.
2. **The VM is deprecated** (§0) rather than removed at once: it keeps running what it runs, gets
   no new features, and goes in a later step. It is removed since (docs/JVM.md §13).
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

Proposed, and built so in T1 (to revisit on review):

9. **No automatic boxing in overloads** (§4): a `JavaInt` never goes where an `Integer` is wanted.
10. **Superseded on review** (2026-10-21): Java's primitives have Java's operators, and Java's
    values and Seal's don't mix (§3.1, item 20). A `switch` on one, and a condition that is a
    `JavaBoolean`, still say which cast.
11. **`java.lang.Object`, `String` and the boxes have one name each,** `seal.java`'s; importing them
    by Java's name is an error that names it, and `import java.lang.*;` doesn't bring them (§3.2).
12. **A name a Java `*` import shares with `seal.lang`** is ambiguous where used (§3.2).
13. **Seal's faults** (a null handle, division by zero) stay uncatchable, whatever a `catch` names,
    and `finally` doesn't run for them or for `System.exit`; it runs for Seal's exceptions and
    Java's.

Settled while building T1:

14. **An uncaught Java exception ends the program as an uncaught Seal one does:** `uncaught
    java.nio.file.NoSuchFileException: missing.txt`, then the Seal methods it went through, exit
    code 70. What the JVM raises at an instruction of Seal's own code (a null handle, a failed cast,
    division by zero) is still a fault, reported as the VM reports it.
15. **A Seal class can't extend a Java class or implement a Java interface yet,** nor a lambda go
    where Java wants an interface: each is an error that says it comes with §5. For-each over a Java
    `Iterable` is an error that says it comes with §4.1, and shows the iterator's loop.
16. **`(int)o` of a `JavaObject` unboxes, as Java's does** (§3.1); and `JavaObject.hash()` is by
    identity, as `==` is, so Java objects are keys of Seal's `HashMap`.
17. **An array literal prefers its default element type** where an overload takes an `array<T>`:
    `JavaArray.of([1.5, 2.5])` takes the `array<double>`, as `1.5` is a `double` (a Seal rule, which
    the checker had for fixed arrays only).

Settled while building T2:

18. **Raw generic types stay,** as Java's (§4.1): T1's programs still compile, and a raw type goes
    where a parameterized one is wanted, unchecked.
19. **A `catch` of Java's `Throwable`, `Exception` or `RuntimeException`** doesn't catch a Seal
    trap or `System.exit`. What the JVM raises at Seal's own code (a null handle, division by zero)
    is a Java exception like any other, though, and such a `catch` does catch it.

Decided on review:

20. **Java's operators on Java's primitives** (2026-10-21): `JavaInt + JavaInt` is Java's, but
    `JavaInt + int` isn't allowed, nor `JavaInt + 1`; a condition still wants `(bit)` (§3.1).

Settled while building T3:

21. **Handing Seal to Java through the compiler** (§5): lambdas and classes become JVM classes
    implementing Java's interfaces, with javac's bridges, rather than B2's `Proxy` plan or Java
    source beside Seal's.
22. **A Seal object still goes where Java wants an `Object` by `JavaObject.of(o)`,** as T1 decided,
    though Java's generics take one as itself where a type argument is its class: `map.put(point, v)`
    on a `HashMap<Point, ...>`, but `map.get(JavaObject.of(point))`, as `get` takes an `Object`.
23. **Static fields when Java calls first** wait for T5, which has a Fabric mod to start that way.

Settled while building T4:

24. **A protected Java member is a subclass's alone:** code of the Seal class below it (and of the
    types nested in that class) uses it, other code gets an error saying it is protected. Java also
    lets its package see one, which Seal's code never shares.
25. **Two arguments of different classes infer their nearest common type,** as Java's do:
    `List.of(square, circle)` is a `List<Shape>` (§4.1), where T2 made it a `List<JavaObject>`.

Settled while building T5:

26. **No Java 21 target, and no remapping:** Minecraft moved to Java 25 with 26.1 and stopped
    obfuscating its code, so a Seal mod compiles against Minecraft's own classes, under Mojang's
    names, and Seal's class files load as they are. The development run is Fabric's own server
    launcher with the mod in `mods/`, no Gradle; `examples/22-fabric-mod/run.sh` sets it up. Registering a block
    needs Fabric API beside the mod, which keeps Minecraft's registries open while mods start.
27. **A library's static fields are set up all at once,** the first time Java's code touches one of
    its classes, by a `$Seal` class in the first class's package, so Seal's order of setting them
    up holds, as with `main`.
28. **One Seal mod at a time, for now:** each mod's jar holds Seal's runtime and library classes
    under their own names, so two Seal mods in one game would share (and clash over) them. Giving
    each mod its own copy, in its own package, comes later.

Decided on review:

29. **Mixins are Java,** in a program's own `.java` files (§11), rather than a Seal declaration
    compiled to a Mixin class: Mixin copies a mixin's bytecode into Minecraft's class, which Seal's
    classes, with what Seal's code generation adds to each, don't suit; and a mixin is a few lines
    that hand over to the mod's code, which is Seal. The files sit beside the `.seal` files, rather
    than Java written inside a `.seal` file.

Settled while building them:

30. **Two parts, by what each file uses** (§11): the files that use none of the program's Seal
    classes are compiled first, and Seal's code uses theirs; those that use Seal's are compiled
    after, and Seal's code can't. A file goes after if it may use a Seal class, or a class of a file
    that goes after, so a file the checker can't see is one that couldn't come first.
31. **Only a library's `.java` files may use Seal's classes:** in a program, Seal's code can't use
    theirs, so nothing would run them; `run`, `build` and `dump` say so, without `--library`.
32. **A Java class of a Seal class's package needs no import,** as in Java: one of the program's
    own `.java` files, most often, but a class path's too.

## 11. A program's own Java files, and Mixins

A program's folders can hold `.java` files beside its `.seal` files, and every command compiles
them with the program, with the JDK's own `javac` (`javax.tools`: no dependency, and no other tool
to install; a JRE's `java`, which has no `javac`, says so). Their classes go with the program's:
`seal run` loads them, `build` writes them, `dump` prints them.

They are compiled in two parts, as each side needs the other's classes compiled first to use them:

1. **The files that use none of the program's Seal classes come first,** against the class path.
   Seal's code uses their classes as it uses any Java class: imported, or by its simple name in the
   same package, as in Java. A small helper written in Java is one.
2. **The files that use Seal's classes come after** Seal's classes are generated, against them, the
   first part's and the class path. Seal's code can't use theirs: an import of one, or its name, is
   an error that says which file it is in and why. Only a library's files may (`--library`), as
   Java's code is what starts one: Fabric, or Mixin, by their names.

A file goes in the second part if it may use a Seal class, from what `javac` parses of it: an
import of one, its simple name where the file's package or a `*` import brings it, or its full
name; or a class of another file in the second part. A class can't be declared in both a `.seal`
and a `.java` file. `javac`'s errors are its own, at their lines, and stop the build as Seal's do.

**Mixins** are Java files of the second part: Mixin copies a mixin's bytecode into the class it
targets, as that class loads, and wants a Java class with its annotations for that; what the
mixin does is the mod's own code, in Seal. `examples/22-fabric-mod/sealmod` has one:

```java
@Mixin(DedicatedServerSettings.class)
abstract class SettingsMixin {
    @Inject(method = "forceSave", at = @At("HEAD"))
    private void sealmod$forceSave(CallbackInfo ci) {
        Hooks.savingSettings((DedicatedServerSettings) (Object) this);   // Hooks is Seal
    }
}
```

`Hooks.savingSettings` takes Minecraft's own class, and the Seal methods a mixin calls take Java's
types, or Seal's numbers: Java code can't make a Seal `string`. Mixin's own rules hold: the mixin
package holds mixins alone, so the mod's Seal classes are in another package (`$Seal`, which sets
up a library's static fields, goes in the first one's); `sealmod.mixins.json`, which names the
mixins, is a resource (`--resources`), and `fabric.mod.json` names it; Mixin's annotations come
from Fabric's Mixin jar, on the class path. Minecraft's names need no refmap, as it isn't
obfuscated (§10, item 26).
