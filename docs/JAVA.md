# Calling Java from Seal: design

**Status:** reviewed; the first step, B1, is built (§11), and §12 records what building it
settled. Seal reaches Java first through a
**bridge**: a small library that names Java classes, methods and fields as strings and calls them
while the program runs (§2 to §8). The compiler learns to check those calls, then to compile them
directly, later (§9); what a bridge can't do at all (a Seal class Java creates by name, extending
a Java class, Mixins) comes after that (§10). Before it, the VM is deprecated and the standard
library moves into packages (§0). §12 records what review decided and what is left. This is the
middle of milestone M6 in docs/JVM.md §13, and the way to a Minecraft mod in
Seal. A fuller typed design, which this replaces as the first step, is in this file's history
(commit 13c300a).

## 0. Before the bridge

**Decided on review:** two changes come first.

- **The VM is deprecated** (docs/JVM.md §13, done): it can't hold Java objects, and keeping both
  backends would mean building every feature twice. Deprecated, as review chose over removing it at
  once: `--vm` still runs and dumps programs, after a warning on standard error; `vm/` and
  `codegen/` are `@Deprecated`; and it gets no new features. What it runs today it keeps running,
  and the suites keep comparing it with the JVM there; the bridge, and what comes after, is the
  JVM's alone. Removing it, its tests and the library's code that only the VM needs (strings and
  arrays on `addr` buffers, `StringChars`, the Seal bodies behind `@jvm` methods) is a later step
  of its own.
- **The standard library moves into packages** (done), as Java's is: `HashMap` is `seal.util.HashMap`,
  imported as any package's type, and the bridge is `seal.java`. Built in, and visible everywhere
  with no import, are the primitive types, `array<T>` and `array<T><<N>>`, and `string` and
  `string<<N>>`. A `seal.lang` package is imported automatically, as `java.lang` is, so that
  `System.stdout.println` and `throw new IllegalArgumentException(...)` need no import (decided on
  review). The layout:

  | Package | Types |
  | ------- | ----- |
  | `seal.lang` (imported automatically) | `Object`, `System`, `Math`, `Integer`, `Char`, `Double`, `Float`, `Fixed`, `Hash`, `Objects`, `Operator`, `StringBuilder`, `Format`, `Iterable`, `Runnable`, `AutoCloseable`, and the exceptions `Exception`, `RuntimeException`, `ArithmeticException`, `AssertionError`, `IllegalArgumentException`, `IllegalStateException`, `IndexOutOfBoundsException`, `NumberFormatException`, `UnsupportedOperationException` |
  | `seal.util` | `List`, `Queue`, `HashMap`, `IntMap`, `Random`, `Iterator`, `Comparator`, `NoSuchElementException`, `BigUInt` (and a `BigInt`, if one is written: the library has only `BigUInt` today), `Array` (the fixed array of the spec's §13) |
  | `seal.util.function` | `Function`, `BiFunction`, `Consumer`, `BiConsumer`, `Predicate`, `Supplier` |
  | `seal.io` | `Files`, `File`, `Reader`, `Writer`, `FileReader`, `FileWriter`, `IOException`, `FileNotFoundException` |
  | `seal.java` | the bridge (§3) |

  The built-in types' files (`string.seal`, `array.seal`, and the `FixedArray` and `StringChars`
  they use) stay in no package, at the top of `stdlib/`. On the JVM, Seal's `seal.x` is
  `io.github.thelinuxseal.seal.x`, and the built-in types are in `io.github.thelinuxseal.seal.lang`
  with `seal.lang`. Every program, test and example that uses a type outside `seal.lang` gained an
  import; imports name a type in full (`import static seal.lang.Math.*;`, as Java's
  `import static java.lang.Math.*;`); a program's type in no package can't take a `seal.lang` or
  built-in type's name, but can take `seal.util`'s and the others'; and a program can't declare a
  package `seal` or one in it (SPEC §2.4, §42).

## 1. Goals

- Call any public Java class from Seal now: the JDK's, a jar's on the class path, Minecraft's and
  Fabric's inside the game. Constructors, static and instance methods, fields.
- With almost no work in the compiler: the bridge is a library (Seal) over a runtime class (Java)
  that finds and calls methods by reflection, so it can be built in days, not months.
- Types that say which Java class a value is (§6), so Seal code is typed where it holds Java
  objects, and so the compiler can later check and then compile the calls.
- Seal code that Java calls back: lambdas as Java interfaces (event callbacks), through
  `java.lang.reflect.Proxy` (§5).

What a bridge can't do: be a class Java instantiates by name (Fabric's `ModInitializer`
entrypoint), extend a Java class (a `Block` subclass), or be a Mixin. Those need the compiler (§10).

## 2. What it looks like

```text
import seal.java.*;

class Main
{
    static void main()
    {
        JavaObject<<"java.util.ArrayList">> names = new JavaObject<<"java.util.ArrayList">>();
        names.invoke("add", [JavaString.of("Ada")]);       // a java.lang.String
        names.invoke("add", [JavaString.of("Bo")]);
        int n = (int)names.invoke("size");                 // no arguments: no []
        string first = (string)names.invoke("get", [JavaInt.of(0)]);

        JavaObject date = JavaBridge.get("java.time.LocalDate").invokeStatic("of",
                [JavaInt.of(2026), JavaInt.of(10), JavaInt.of(19)]);
        System.stdout.println((string)date.invoke("getDayOfWeek").invoke("toString"));

        JavaObject client = JavaBridge.get("net.minecraft.client.MinecraftClient").invokeStatic("getInstance");
        client.invoke("tick");

        try {
            JavaBridge.get("java.nio.file.Files").invokeStatic("readString",
                    [JavaBridge.get("java.nio.file.Path").invokeStatic("of", [JavaString.of("missing")])]);
        } catch (JavaThrownException e) {                  // what Java threw, as a Seal exception
            if (e.is("java.nio.file.NoSuchFileException")) { System.stdout.println("no file"); }
        }

        JavaObject task = JavaBridge.implement("java.lang.Runnable", (method, args) -> {
            System.stdout.println("run from Java");
            return null;
        });
        JavaBridge.create("java.lang.Thread", [task]).invoke("start");
    }
}
```

## 3. The library: `seal.java`

**In a package, `seal.java`** (decided on review, built in B1), so that programs import it
(`import seal.java.*;`) and its names don't take any program's (§12, question 1). Until the shapes
of §6 come (B3), the types are written `JavaObject` and `JavaClass`; then those will mean the bare
shapes, `JavaObject<<>>` and `JavaClass<<>>`, as `string` means `string<<>>`. `implement` and the
exceptions are B2's.

`JavaBridge`, the way in:

| Member | What it does |
| ------ | ------------ |
| `static JavaClass<<>> get(string className)` | the class so named (`"java.util.ArrayList"`, `"java.util.Map$Entry"`) |
| `static JavaObject<<>> create(string className)` | a new object, with the constructor that takes no arguments |
| `static JavaObject<<>> create(string className, array<JavaObject<<>>> args)` | a new object, with the constructor the arguments choose (§4) |
| `static JavaObject<<>> implement(string interfaceName, JavaHandler handler)` | an object implementing a Java interface, whose methods call the handler (§5) |

`JavaClass`, a Java class:

| Member | What it does |
| ------ | ------------ |
| `invokeStatic(string method)`, `invokeStatic(string method, array<JavaObject<<>>> args)` | calls a static method; its result, or `null` for `void` |
| `create()`, `create(array<JavaObject<<>>> args)` | a new object of this class |
| `getStatic(string field)`, `setStatic(string field, JavaObject<<>> value)` | a static field |
| `name()`, `isInstance(JavaObject<<>> o)` | its name; whether `o` is one of it (or a subclass, or implements it) |

`JavaObject`, a Java object:

| Member | What it does |
| ------ | ------------ |
| `invoke(string method)`, `invoke(string method, array<JavaObject<<>>> args)` | calls an instance method; its result, or `null` for `void` |
| `getField(string field)`, `setField(string field, JavaObject<<>> value)` | a public field |
| `javaClass()` | its class, as a `JavaClass<<>>` |
| shaped only: `new()`, `new(array<JavaObject<<>>> args)` | a new object of the shape's class (§6) |

Every member that invokes or creates has the two forms, with arguments and without, as asked on
review; the form without passes none. `==` compares two `JavaObject`s by identity, as for Seal's
classes; `equals` is Java's, `x.invoke("equals", [y])`. Results are `JavaObject<<>>`.

The exceptions (`JavaClassNotFoundException` and the rest) are §7, `JavaHandler` §5.

### 3.1 Seal values into Java

**Decided on review:** by hand, through one class for each of Java's types, so the call names the
Java type it passes (§2: `JavaString.of("Ada")`, `JavaInt.of(2026)`):

| Call | Java value |
| ---- | ---------- |
| `JavaInt.of(int x)`, `JavaLong.of(int<<64>> x)` | an `int`, a `long` |
| `JavaShort.of(int<<16>> x)`, `JavaByte.of(int<<8>> x)` | a `short`, a `byte` |
| `JavaDouble.of(double x)`, `JavaFloat.of(float x)` | a `double`, a `float` |
| `JavaChar.of(char c)`, `JavaBoolean.of(bit b)` | a `char`, a `boolean` |
| `JavaString.of(string s)` | the `String` itself |
| `JavaObject.of(Object o)` | a Seal class's object, itself (it is a JVM object already) |
| `null` | `null`, with no call |

- **The program picks Java's type,** by the name Java's documentation gives it: a method taking a
  `short` is given `JavaShort.of(5)`, one taking a `float` `JavaFloat.of(2.5)` (reflection won't
  narrow a `Double` into a `float`). A Seal value goes in as Seal passes values: `JavaInt.of(n)` of
  an `int<<64>>` narrows, and unsigned values go by their bits.
- **Nothing converts by itself,** so Seal's rule that programs make objects where they write them
  holds (§52 of the spec), and `@implicit` isn't widened beyond a shape becoming its bare shape
  (§6).
- **Each `of` gives a `JavaObject<<>>`,** so an argument array's elements share a type and array
  literals need nothing new: `[JavaString.of("Ada"), JavaInt.of(5), task]`.
- Structs, uniques, fixed arrays, fixed point and addresses have no Java form, so no class takes
  them. An `array<T>` isn't converted for now (a Java array is a `JavaObject` the bridge can make:
  §11, B2).

### 3.2 Java values into Seal

By casts, which `JavaObject` defines with `@overload(Operator.CAST)` (§46 of the spec):

```text
int n = (int)names.invoke("size");                  // an Integer (or any Number) as an int
string s = (string)names.invoke("get", [JavaInt.of(0)]);   // a String
bit empty = (bit)names.invoke("isEmpty");           // a Boolean
JavaObject<<"java.util.ArrayList">> copy = (JavaObject<<"java.util.ArrayList">>)result;   // §6
```

`(int)`, `(int<<64>>)`, `(double)`, `(float)`, `(char)` and `(bit)` take the matching box, and the
number casts take any `java.lang.Number` (or a `Character`), converted as Seal converts numbers. A
value of the wrong kind, or `null`, throws a `JavaCastException` saying what it was (decided on
review, §12; until B2, it stops the program with that message).

`(string)` is the object's text, Java's `String.valueOf`: a `String` is itself, a list
`[Ada, Bo]`, `null` "null" (settled in B1, §12). Seal's cast to `string` is a type's text, which
`+` and `println` use (§46 of the spec), so it can't be one that fails.

## 4. Calls

- **Finding the class:** by name, through the class loader that loaded the Seal class making the
  call. In `seal run` that loader sees the JDK and the class path's jars (`--classpath`); inside
  Minecraft it is Fabric's, which sees Minecraft's and every mod's classes. Nested classes are
  named as the JVM names them, `java.util.Map$Entry`.
- **Finding the method:** the public methods of that name (with those of the parents and
  interfaces) that take that many arguments, or more through varargs; among them, the ones whose
  parameters accept the arguments as Java would (a box unboxed and widened, an object of a
  subclass); of those, the most specific, as Java picks. None throws a
  `JavaMethodNotFoundException` naming the methods of that name there are; two equally specific, a
  `JavaAmbiguousCallException` naming both.
- **Primitives stay primitives:** what `JavaInt.of` and the others of §3.1 make is a primitive (an
  `Integer` marked as an `int` inside the runtime), so it prefers primitive parameters:
  `list.invoke("remove", [JavaInt.of(0)])` calls `remove(int)`, not `remove(Object)`, as the Java
  source `list.remove(0)` does. A box Java gave back is an object, and prefers `Object` parameters;
  so `remove(Object)` takes a box Java makes, as the Java source
  `list.remove(Integer.valueOf(0))` does:
  `JavaBridge.get("java.lang.Integer").invokeStatic("valueOf", [JavaInt.of(0)])`.
- **Varargs:** the elements one by one (B1), or an array (B2), for the `...` parameter.
- **Methods of private classes:** a public method of a class that isn't public (the iterator an
  `ArrayList` gives, the list `List.of` makes) is called as a public type it extends or implements
  declares it (`Iterator.hasNext`), as Java code calls it through that type.
- **Fast enough:** the method chosen is kept for its class, its name and the kinds of the arguments,
  so a call repeated with the same kinds of arguments (every tick) skips the lookup; reflection's
  own `Method.invoke` calls it. (A `MethodHandle` per call site, from an `invokedynamic`, is C2's.)
- Only public members, through public classes; the bridge never calls `setAccessible`.

## 5. Callbacks: Java calling Seal

`JavaBridge.implement(interfaceName, handler)` makes an object that implements a Java interface,
with `java.lang.reflect.Proxy`: each call of one of its methods calls the handler with the
method's name and its arguments as `JavaObject`s, and returns what the handler returns (converted
back to a primitive where the method returns one). `JavaHandler` is a Seal interface,

```text
interface JavaHandler { JavaObject<<>> call(string method, array<JavaObject<<>>> args); }
```

so a lambda is one: `(method, args) -> { ...; return null; }`. Its `toString`, `equals` and
`hashCode` are the Proxy's own (identity). This covers listeners, comparators, tasks and events:
Fabric's `ServerTickEvents.END_SERVER_TICK.register(...)` takes a functional interface, which
`implement` makes. It doesn't cover classes (only interfaces), nor a class Java instantiates by
name (§10).

## 6. Shapes: `JavaObject<<"java.util.ArrayList">>`

`JavaObject` and `JavaClass` have two shapes: the bare one, `<<>>`, any Java object or class; and
`<<string CLASS>>`, one of a class known by name, as `Quantity<<"m">>` is a quantity known by its
unit.

- **Types that say what they hold:** a Seal method taking a
  `JavaObject<<"net.minecraft.block.Block">>` can't be given a `JavaObject<<"java.util.ArrayList">>`;
  the compiler says so.
- **A shaped object goes where a bare one is wanted,** as a `string<<5>>` goes where a `string` is,
  and by the same means: an `@implicit` constructor of the bare shape, which the library may
  declare for `JavaObject` and `JavaClass` as it does for `string`, and for nothing else (§12,
  question 4).
- **A bare one becomes a shaped one by a cast,** `(JavaObject<<"java.util.List">>)o`, which checks
  `o`'s class while the program runs (a subclass, or a class implementing the interface, is one),
  so a wrong object fails there, not at a later `invoke`. `null` casts to any shape.
- **A shaped one's constructors** use its shape's class:
  `new JavaObject<<"java.util.ArrayList">>()` and `new JavaObject<<"java.util.ArrayList">>([JavaInt.of(16)])`,
  so the name is written once. `JavaBridge.create(name, ...)` remains for a name known only while
  the program runs.
- `JavaBridge.get(name)` gives a bare `JavaClass<<>>`; a shaped class is
  `new JavaClass<<"net.minecraft.client.MinecraftClient">>()`. (Seal's static members can't read
  their class's `<<...>>` values, so the shape can't give `invokeStatic` a class by itself.)
- **The shape is what §9 builds on:** knowing the class of every shaped value, the compiler can
  check its calls, and later compile them directly, without the program changing.

## 7. Exceptions

**Decided on review:** what goes wrong at the bridge is a Seal exception, which `catch` takes, each
named `Java...Exception`, as below (the names also decided on review):

| Exception | When |
| --------- | ---- |
| `JavaException` | the parent of all of them: `catch (JavaException e)` takes any |
| `JavaClassNotFoundException` | no public class by that name |
| `JavaMethodNotFoundException` | no public method (or constructor) of that name taking those arguments |
| `JavaFieldNotFoundException` | no public field of that name |
| `JavaAmbiguousCallException` | two methods fit the arguments equally well |
| `JavaCastException` | a cast out of a `JavaObject` (§3.2) given the wrong kind, or `null` |
| `JavaThrownException` | the Java code threw: `e.cause()` is what it threw, as a `JavaObject`; `e.javaClassName()` its class's name; `e.is("java.io.IOException")` whether it is one (or a subclass); `e.getMessage()` its message |

- The bridge unwraps the `InvocationTargetException` reflection puts around what Java threw.
- A Seal exception thrown by a `JavaHandler`, while Java called it, goes back through the Java
  code as an unchecked exception (Seal's extend `RuntimeException` on the JVM, docs/JVM.md §7),
  and reaches the Seal code that called into Java as itself, unless the Java code caught it.

## 8. In the compiler and the runtime

- **The runtime,** `JavaBridge` (Java, in `io.github.thelinuxseal.seal.runtime`): class lookup
  through the caller's loader (the class that called in, from a `StackWalker`), overload choice,
  conversions, the cache of §4, unwrapping what reflection wraps; and `JavaPrimitive`, a number
  passed in, marked with its primitive type. `Proxy` handlers come in B2. `seal build` copies both
  beside the program, as it does the rest of the runtime.
- **The library,** `stdlib/seal/java/`: `JavaBridge`, `JavaClass`, `JavaObject`, `JavaInt` and the
  other classes of §3.1 (and in B2, `JavaHandler` and the exceptions of §7). Their methods have no
  Seal body: each is `@jvm("JavaBridge.invoke") public JavaObject invoke(string method);`, the
  runtime's method that does it, which only the library may write.
- **The checker:** `JavaObject` and `JavaClass` are classes, so a variable may be `null` and `==`
  is identity; `JavaClass` extends `JavaObject`. They aren't Seal's objects: their root is Java's,
  so neither goes where an `Object` is wanted (an error that suggests `JavaObject.of`). For B3,
  `@implicit` constructors for a shape becoming the bare one (§6); today the checker allows
  `@implicit` in `string` alone.
- **The JVM backend:** a `JavaObject` or `JavaClass` is the Java reference itself, typed
  `java.lang.Object`, with no wrapper and no class of its own, as a `string` is a
  `java.lang.String`; a cast from `JavaObject` down to `JavaClass` checks for a `java.lang.Class`.
  Their methods, casts included, are static calls to the runtime with the object first.
- **The VM** gets none of it (§0): its code generator reports a call into `seal.java` as running
  only on the JVM.
- **The CLI:** `--classpath` (`-cp`) on `seal run`, so programs reach jars; `seal build` writes the
  classes as now, run beside the jars with `java -cp classes:deps.jar Main`.

## 9. Later: checked calls, then direct ones

The bridge's calls are strings; once it works, the compiler can do more with them, step by step,
with no change to programs:

- **C1, checked calls:** for a shaped value, the compiler reads its class's class file (with the
  JDK's class-file API, never loading the class: the JDK's from its module image, jars' from the
  class path) and checks `invoke`, `invokeStatic`, `getField` and their argument counts against
  it: a misspelled method is a compile error, with the names it might be.
- **C2, direct calls:** a call C1 resolves to one method compiles to that method's JVM call
  (`invokevirtual` with its exact descriptor), not reflection, with the same conversions.
- **C3, typed results:** where C1 knows a method's result type, `invoke` gives a shaped
  `JavaObject` of it (`names.invoke("iterator")` a `JavaObject<<"java.util.Iterator">>`).

## 10. Later: what a bridge can't do

These need the compiler to write Java's view of Seal classes, from the earlier typed design:

- **A Seal class implementing a Java interface by name**, which Java can instantiate: Fabric starts
  a mod by creating the class `fabric.mod.json` names, which implements `ModInitializer`. (Fabric
  may also accept a static method there, `my.mod.Main::init`, which would let this wait; to check.)
- **A Seal class extending a Java class** (Minecraft's `Block`, `Item`), with its constructors,
  overrides, the bridges generics need, and protected members.
- **Mixins:** Seal declarations compiled to standard Mixin classes with their annotations, which
  Fabric's own Mixin applies (no port of Mixin: Fabric loads its own).
- **Seal objects as Java objects:** `equals`, `hashCode` and `toString` from Seal's `==`, `hash()`
  and cast to `string`, so Java's maps find Seal keys.

**Decided on review:** integers keep the representation M3 and M5 gave them (a JVM `int` or
`long`, or bits in a block where a value needs a place in memory), so a value crosses to Java as
the number it already is. Keeping every integer in a block, read out by a `toJavaInt()` where it
meets Java, was considered and set aside: every use of every integer would be a call that shifts
and masks bits, which the JIT can't keep in registers.

## 11. Order of work

| Step | Contents | Done when |
| ---- | -------- | --------- |
| **R1**, **done** | §0: the VM is deprecated | `--vm` warns, every suite passes |
| **R2**, **done** | §0: the library moves into packages | the suites pass with the library in packages |
| **B1**, **done** | the runtime bridge; `seal.java` with `JavaBridge`, `JavaClass`, `JavaObject` (bare), calls with and without arguments, fields, conversions in (`JavaInt.of` and the others of §3.1) and out (casts); `--classpath` | a program uses `java.util.ArrayList`, `java.util.HashMap`, `java.time.LocalDate` and `java.util.Random` through the bridge, and prints what the same Java program prints |
| **B2** | the exceptions of §7; `implement` and `JavaHandler`; Java arrays as `JavaObject`s (made with `java.lang.reflect.Array`, for varargs and `byte[]` APIs) | a Java `Thread` runs a Seal lambda, a `Comparator` from Seal sorts a Java list, an `IOException` is caught |
| **B3** | shapes: `JavaObject<<string CLASS>>`, `JavaClass<<string CLASS>>`, their constructors, a shape going where the bare one is wanted (`@implicit`), checked casts | the B1 program written with shaped values |
| **C1** to **C3** | §9 | a misspelled method is a compile error; the shaped calls of B3's program compile to direct JVM calls |
| later | removing the VM (§0), as a step of its own; §10, then jars, the Java 21 target, and a Fabric mod (M6) | a Seal mod loads in a Fabric development run |

## 12. Questions

Decided on review:

1. **`seal.java`**, and the whole standard library in packages (§0).
2. **The VM is deprecated** (§0), before the bridge, rather than removed at once: it keeps running
   what it runs, gets no new features, and goes in a later step.
3. **Failures are Seal exceptions** named `Java...Exception` (§7).
4. **Seal values go into Java by hand,** one class per Java type: `JavaInt.of(5)`,
   `JavaString.of("Ada")` (§3.1), so the call names the Java type, and a value never becomes a
   `JavaObject` by itself. Converting them through `@implicit` constructors, as a `string<<5>>`
   becomes a `string`, was considered and set aside; and a cast method marked `@implicit` would
   be the same thing, declared on the value's type. `@implicit` stays the library's, for a shape
   becoming its bare shape: `string`'s now, `JavaObject`'s and `JavaClass`'s in B3 (§6).
5. **A cast out of the wrong kind, or of `null`,** throws a `JavaCastException` (§3.2, §7); the
   cast to `string` is the exception, below.
6. **`seal.lang` is imported automatically,** and the big numbers are in `seal.util` (§0).
7. **The exceptions' names** in §7.

Settled while building B1:

8. **A `JavaObject`'s cast to `string` is its text,** Java's `String.valueOf`, not a cast that takes
   only a `String`: Seal's cast to `string` is a type's text, which `+` and `println` use, so it
   can't fail (§3.2).
9. **`JavaClass` extends `JavaObject`,** as a Java class is an object: `cls.invoke("getSimpleName")`.
10. **`JavaObject` isn't one of Seal's objects:** it doesn't go where an `Object` is wanted, and
    `JavaObject.of(o)` takes only an object of a Seal class (a struct or record has no Java form).
11. **Until B2, what goes wrong stops the program,** as a trap that says what, and an exception the
    Java code throws stops it as an uncaught one does. B2 makes both Seal exceptions (§7).
12. **Both are `java.lang.Object` on the JVM** (§8), and the runtime casts to `Class` where it needs
    one, which keeps the generated code simple.

Nothing is left to decide before B2.
