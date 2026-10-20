# Calling Java from Seal: design

**Status:** proposed, for review; nothing is built yet. Seal reaches Java first through a
**bridge**: a small library that names Java classes, methods and fields as strings and calls them
while the program runs (§2 to §8). The compiler learns to check those calls, then to compile them
directly, later (§9); what a bridge can't do at all (a Seal class Java creates by name, extending
a Java class, Mixins) comes after that (§10). The questions in §12 need answers before work
starts. This is the middle of milestone M6 in docs/JVM.md §13, and the way to a Minecraft mod in
Seal. A fuller typed design, which this replaces as the first step, is in this file's history
(commit 13c300a).

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
        names.invoke("add", ["Ada"]);                      // "Ada" becomes a java.lang.String
        names.invoke("add", ["Bo"]);
        int n = (int)names.invoke("size");                 // no arguments: no []
        string first = (string)names.invoke("get", [0]);

        JavaObject date = JavaBridge.get("java.time.LocalDate").invokeStatic("of", [2026, 10, 19]);
        System.stdout.println((string)date.invoke("getDayOfWeek").invoke("toString"));

        JavaObject client = JavaBridge.get("net.minecraft.client.MinecraftClient").invokeStatic("getInstance");
        client.invoke("tick");

        try {
            JavaBridge.get("java.nio.file.Files").invokeStatic("readString",
                    [JavaBridge.get("java.nio.file.Path").invokeStatic("of", ["missing"])]);
        } catch (JavaException e) {                        // what Java threw, as a Seal exception
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

**Proposed: in a package, `seal.java`,** as the first package of the standard library, so that
programs import it (`import seal.java.*;`) and its names don't take any program's (§12, question 1).

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

`JavaException` and `JavaBridgeException` are §7, `JavaHandler` §5.

### 3.1 Seal values into Java

**Proposed:** a Seal value converts into a `JavaObject` where one is wanted, by itself, through
`@implicit` constructors, as a `string<<5>>` converts into a `string`. `@implicit` is allowed only
for the library's `string` today; it would be allowed for the library's `JavaObject` too, and no
other type (§12, question 4). An array literal whose elements differ (`[2026, "Ada", task]`) where
an `array<JavaObject<<>>>` is wanted converts each element, which array literals don't do today.

| Seal value | Java object |
| ---------- | ----------- |
| `int<<8>>`, `int<<16>>`, `int` (any width up to 32), `int<<64>>` (up to 64) | `Byte`, `Short`, `Integer`, `Long` (unsigned ones by their bits) |
| `char`, `bit` | `Character`, `Boolean` |
| `float`, `double` | `Float`, `Double` |
| `string` | the `String` itself |
| a Seal class's object | the object itself (it is a JVM object already) |
| `null` | `null` |

Structs, uniques, fixed arrays, fixed point and addresses have no Java form, and don't convert: an
error says so where one is passed. An `array<T>` isn't converted for now (a Java array is a
`JavaObject` the bridge can make: §11, B2).

### 3.2 Java values into Seal

By casts, which `JavaObject` defines with `@overload(Operator.CAST)` (§46 of the spec):

```text
int n = (int)names.invoke("size");                  // an Integer (or any Number) as an int
string s = (string)names.invoke("get", [0]);        // a String
bit empty = (bit)names.invoke("isEmpty");           // a Boolean
JavaObject<<"java.util.ArrayList">> copy = (JavaObject<<"java.util.ArrayList">>)result;   // §6
```

`(int)`, `(int<<64>>)`, `(double)`, `(float)`, `(char)` and `(bit)` take the matching box, and the
number casts take any `java.lang.Number`, converted as Seal converts numbers. `(string)` takes a
`String`; to make text of any object, call its `toString`. A value of the wrong kind, or `null`,
throws a `JavaBridgeException` saying what it was (§12, question 5).

## 4. Calls

- **Finding the class:** by name, through the class loader that loaded the Seal class making the
  call. In `seal run` that loader sees the JDK and the class path's jars (`--classpath`); inside
  Minecraft it is Fabric's, which sees Minecraft's and every mod's classes. Nested classes are
  named as the JVM names them, `java.util.Map$Entry`.
- **Finding the method:** the public methods of that name (with those of the parents and
  interfaces) that take that many arguments, or more through varargs; among them, the ones whose
  parameters accept the arguments as Java would (a box unboxed and widened, an object of a
  subclass); of those, the most specific, as Java picks. None, or two equally specific, throws a
  `JavaBridgeException` listing the candidates.
- **Seal's numbers keep their meaning:** a `JavaObject` made from a Seal number remembers it was a
  number (it is an `Integer` marked as such inside the runtime), so it prefers primitive
  parameters: `list.invoke("remove", [0])` calls `remove(int)`, not `remove(Object)`, as the Java
  source `list.remove(0)` does. A box Java gave back is an object, and prefers `Object` parameters.
- **Varargs:** the elements, or an array (B2), for the `...` parameter.
- **Fast enough:** each call site keeps the `MethodHandle` it found for the argument classes it
  saw, so a call repeated with the same kinds of arguments (every tick) skips the lookup.
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
- **A shaped object goes where a bare one is wanted,** as a `string<<5>>` goes where a `string` is.
- **A bare one becomes a shaped one by a cast,** `(JavaObject<<"java.util.List">>)o`, which checks
  `o`'s class while the program runs (a subclass, or a class implementing the interface, is one),
  so a wrong object fails there, not at a later `invoke`. `null` casts to any shape.
- **A shaped one's constructors** use its shape's class:
  `new JavaObject<<"java.util.ArrayList">>()` and `new JavaObject<<"java.util.ArrayList">>([16])`,
  so the name is written once. `JavaBridge.create(name, ...)` remains for a name known only while
  the program runs.
- `JavaBridge.get(name)` gives a bare `JavaClass<<>>`; a shaped class is
  `new JavaClass<<"net.minecraft.client.MinecraftClient">>()`. (Seal's static members can't read
  their class's `<<...>>` values, so the shape can't give `invokeStatic` a class by itself.)
- **The shape is what §9 builds on:** knowing the class of every shaped value, the compiler can
  check its calls, and later compile them directly, without the program changing.

## 7. Exceptions

- **What Java throws** from a method the bridge called reaches Seal as a `JavaException`, a Seal
  exception (it extends the library's `Exception`), so `catch` takes it as any other. It holds the
  Java one: `e.cause()` is it, as a `JavaObject`; `e.javaClassName()` its class's name;
  `e.is("java.io.IOException")` whether it is one (or a subclass); `e.getMessage()` its message.
  The bridge unwraps the `InvocationTargetException` reflection puts around it.
- **What the bridge itself finds wrong** (no class or method by that name, no overload matching,
  a value converted to the wrong kind) is a `JavaBridgeException`, a Seal exception too.
- A Seal exception thrown by a `JavaHandler`, while Java called it, goes back through the Java
  code as an unchecked exception (Seal's extend `RuntimeException` on the JVM, docs/JVM.md §7),
  and reaches the Seal code that called into Java as itself, unless the Java code caught it.

## 8. In the compiler and the runtime

- **The runtime,** a new class `JavaBridge` (Java, in `io.github.thelinuxseal.seal.runtime`): class
  lookup through the caller's loader, overload choice, conversions, `MethodHandle` caches per call
  site, `Proxy` handlers, exception unwrapping.
- **The library,** `stdlib/seal/java/`: `JavaBridge`, `JavaClass`, `JavaObject`, `JavaHandler`,
  `JavaException` and `JavaBridgeException`, their members `@jvm` methods of the runtime class
  (docs/JVM.md §8). The standard library having a package needs the checker to load library files
  from folders as it loads programs' (§12, question 1).
- **The checker:** `@implicit` for `JavaObject` too (§3.1), and array literals converting their
  elements to an `array<JavaObject<<>>>` wanted.
- **The JVM backend:** a `JavaObject` or `JavaClass` is the Java reference itself (`java.lang.Object`,
  and `java.lang.Class` for a class), with no wrapper, as a `string` is a `java.lang.String`; a
  number converted in is marked (§4) inside the runtime's box. Casts are calls to the runtime.
- **The VM** can't hold Java objects: `--vm` on a program that uses `seal.java` says so (§12,
  question 2).
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
| **B1** | the runtime bridge; `seal.java` with `JavaBridge`, `JavaClass`, `JavaObject` (bare), calls with and without arguments, fields, conversions in (`@implicit`, array literals) and out (casts); `--classpath` | a program uses `java.util.ArrayList`, `java.util.HashMap`, `java.time.LocalDate` and `java.util.Random` through the bridge, and prints what the same Java program prints |
| **B2** | `JavaException` and `JavaBridgeException`; `implement` and `JavaHandler`; Java arrays as `JavaObject`s (made with `java.lang.reflect.Array`, for varargs and `byte[]` APIs) | a Java `Thread` runs a Seal lambda, a `Comparator` from Seal sorts a Java list, an `IOException` is caught |
| **B3** | shapes: `JavaObject<<string CLASS>>`, `JavaClass<<string CLASS>>`, their constructors, checked casts | the B1 program written with shaped values |
| **C1** to **C3** | §9 | a misspelled method is a compile error; the shaped calls of B3's program compile to direct JVM calls |
| later | §10, then jars, the Java 21 target, and a Fabric mod (M6) | a Seal mod loads in a Fabric development run |

## 12. Questions to decide

1. **The library's first package, `seal.java`?** Proposed: yes, as asked; programs
   `import seal.java.*;`, and the checker loads library files from folders as it does programs'.
   Or: in no package, as the rest of the library, so `JavaObject` needs no import but takes the
   name from every program.
2. **The VM:** the bridge needs the JVM. Proposed: `--vm` refuses programs that use `seal.java`, and
   the bridge's tests run on the JVM only (the VM can't hold Java objects without a new kind of
   value of its own).
3. **Failures as exceptions:** proposed: what Java throws and what the bridge finds wrong are Seal
   exceptions (`JavaException`, `JavaBridgeException`), which `catch` takes. Or: faults that stop
   the program, as a null handle does.
4. **`@implicit` for `JavaObject`:** proposed: yes, for that one library class besides `string`, so
   `[2026, "Ada"]` and `invoke("add", ["Ada"])` read as they do above. Or: by hand,
   `JavaObject.of(2026)`.
5. **Converting `null` or the wrong kind out** (`(int)` of a `String`, `(string)` of `null`):
   proposed: a `JavaBridgeException`. Or: `null` gives the type's zero (`0`, `""`, `false`).
