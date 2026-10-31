# Seal inside Java programs: design

**Status:** E1 to E4 are built (2026-10-30): a Java program compiles Seal's code while it runs,
gets the errors as data, and uses the classes in its own JVM; it gives its scripts an API of its
own, written in Seal, and calls them through its Java interfaces with its own objects; it makes the
classes of its scripts that implement them, which compiling checks it can, and runs snippets of
code with its values bound by name; and its scripts use no Java class it doesn't allow, while its
API uses any. E5 is planned (§9), with a Minecraft mod for macros, which players write in Seal, as
the program they are for.

Seal is a scripting language for programs on the JVM: Minecraft mods, Java games, Java
applications. It isn't one for C and C++ programs, as Lua is: those would need a JVM, or a runtime
in C, which Seal hasn't got. What it offers a JVM program over Lua's ports and Java's other
scripting languages is that a script is checked before it runs, and is compiled to class files,
which the JVM's JIT compiles as it does Java's.

## 1. What it looks like

```java
SealEngine seal = SealEngine.builder()
        .parent(MacroMod.class.getClassLoader())     // the Java classes scripts may use: the game's, the mod's
        .library(MacroMod.class.getClassLoader(), "macros/api")   // the mod's Seal API, every script has (§3)
        .entry(Hook.class)                // what the mod makes of a script's classes (§4)
        .allowJava("java.util.*")         // the Java classes scripts may use: none, unless allowed (§5)
        .out(log)                         // the scripts' standard output; System.out unset
        .files(false)                     // no files, as unset: every file operation fails
        .build();

SealScript script = seal.compile(Path.of("config/macros"));    // or seal.compile("Main.seal", code)
for (Diagnostic e : script.errors()) {
    log.println(e);                       // AutoEat.seal:3:9: unknown method printn
}
if (script.ok()) {
    List<Hook> macros = script.create(Hook.class);   // each of its classes that is a Hook
    int code = script.run();              // or Main's main(), as `seal run` runs it
    Class<?> tool = script.load("Tool");  // or any class, for Java's code to use
}
```

The package is `io.github.thelinuxseal.seal.embed`:

* **`SealEngine`**, made by its `Builder`, holds what its compiles share:
  * the standard library, read once when it is built;
  * the host's own Seal library (`library`, §3), compiled with every script;
  * the class loader scripts' classes are loaded under (`parent`, the engine's by default), whose
    Java classes they are compiled against: the interfaces the host calls them through, the game's;
  * which Java classes scripts may use (`allowJava`, §5): none but seal.java's and the entry types,
    unless the host allows them; its library may use any;
  * jars and class folders of more Java classes (`classPath`, as `seal --classpath`);
  * the warnings to report (`warn(Warning.AUTO_CAST)`, as `-WautoCast`);
  * each script's standard input, output and error, and whether it may use files.
* **`compile`** takes `.seal` files and folders, one file's code, or several files' code by name.
  A `.java` file among them is an error: `seal` compiles a program's with `javac` (JAVA.md §11),
  but a script's are the host's to compile, with the host.
* **`SealScript`** has its `errors()` and `warnings()`, `Diagnostic`s with a file, line, column and
  message; when it has no errors, it is `ok()`, and `load(name)` gives its classes, by binary
  name (`com.example.Tool`, `Outer$Inner`). Every class is compiled, as `seal build --library`
  compiles a library's (JAVA.md §5), and the static fields are set up, in Seal's order, the first
  time Java's code uses one of the script's classes.
* **`run()`** calls Main's static `main()`, and **`run(name)`** another class's, giving main's
  result, 0 for a void main, or `System.exit`'s code. A fault nothing caught (an exception, a null
  handle, division by zero) is thrown as a **`SealException`**, whose message is what `seal run`
  prints for it, with the Seal lines it happened at, and whose cause is the fault.
* **`create(type)`** makes each of the script's classes that is one of the engine's entry types
  (§4), with `new()`.
* **`close()`** closes the files the script left open and flushes its output. `run` does this as
  main ends, as a program's end does.
* **`snippet(code)`** compiles statements with Java values bound by name, into a **`SealSnippet`**
  that the host runs (§4).

What a Seal class does for Java's code, it does for the host's: implementing Java's interfaces
(JAVA.md §5, T3), extending Java's classes (T4), being created and called. A script's class that
implements `IntUnaryOperator` is created with `script.load("Doubler").getConstructor().newInstance()`,
and the host calls it through the interface, as it would a Java class.

## 2. seal.jar

`./jar.sh` builds `seal.jar`: the compiler, its runtime, and the standard library, which goes in as
resources under `io/github/thelinuxseal/seal/stdlib/`, with an `index` of its files, since a class
loader can read a jar's files but can't list its folders. A host puts the jar on its class path (a
Fabric mod includes it in its own); the jar also runs as the `seal` script does,
`java -jar seal.jar run PATH...`.

The standard library is found, by the engine and by `seal`, in this order: the `seal.stdlib`
property's folder; a `stdlib` folder beside the compiler's classes (the repository's, for `out/`);
the jar's; a `stdlib` folder in the working directory. `SealEngine.Builder.stdlib(folder)` names
one. Seal needs Java 25, so its host does too: Minecraft 26.3 runs on it (`examples/22-fabric-mod`).

## 3. A host's Seal API (E2)

A host gives its scripts an API in Seal, so that they use Seal's types: `game.say("hi")` and
`game.ticks() + 1`, not `JavaString.of("hi")` and `(int) ctx.ticks()`. The API's code is the one
place that crosses into Java. The host calls scripts through a Java interface of its own, in
Java's types, and the API's classes implement it:

```java
// The host's Java: what it calls a macro through, and the object it gives one.
public interface Hook {
    String name();
    int run(Context ctx);
}
```

```seal
// The host's Seal API, in its jar as .seal files (macros/api/api/Macro.seal).
package api;

import demo.*;
import seal.java.*;

abstract class Macro implements(Hook) {
    public abstract string title();
    public abstract int act(Game game);

    public JavaString name() { return JavaString.of(title()); }
    public JavaInt run(Context ctx) { return JavaInt.of(act(new Game(ctx))); }
}
```

```seal
// A player's macro: Seal's types alone.
import api.*;

class Hello extends(Macro) {
    public string title() { return "hello"; }
    public int act(Game game) {
        game.say("hi at tick " + (game.ticks() + 1));
        return 1;
    }
}
```

The host creates `Hello`, and calls it as a `Hook`: `hook.run(new Context(...))`. Its Java objects
reach the script as the arguments of the interface's methods, which the API wraps in Seal's
classes (`new Game(ctx)`).

`Builder.library` takes the API's `.seal` files: folders and files; files in a class loader's
resources (a mod's jar), in a folder whose `index` lists them, one path to a line relative to it,
as `jar.sh` writes the standard library's (`find . -name '*.seal' | sed 's|^\./||' | sort > index`);
or the files' code, by name. `build()` compiles the library by itself, so that its errors are the
host's to see when the engine is made, an `IllegalArgumentException` that lists them, rather than
every script's. A script's warnings are its own files', not the library's.

**Each script is compiled with the library**, and has its own of its classes; the host's Java
code doesn't name them, only its own interfaces. Compiling the library into the host's jar once,
and checking scripts against it, was the first plan, and doesn't work with Seal's backend:

* The backend makes a class per instantiation of a generic class (`List$int`, docs/JVM.md §5.1),
  and of the standard library's classes, only the static methods a program calls. A script would
  need classes and methods the host's jar hasn't got, and couldn't add them to its classes.
* Every Seal class implements the library's root `Object` (and every exception extends its
  `Exception`), so a script making its own of the standard library's classes would have objects
  the host's library classes can't use, and sharing the jar's would share its `System.stdout`, and
  every other static field of the standard library, between scripts (§6).

Compiled with each script, the API's classes are the script's, with its streams and its static
fields, and only Java's classes cross between the host and the script, as they do between any two
Java libraries. What it costs is compiling the API with every script, as the standard library is.

## 4. Entry classes and snippets (E3)

**Entry types.** `Builder.entry(Hook.class)` names a Java interface (or class) that the host makes
scripts' classes of. Compiling a script finds each class of its own files (not the library's)
that is a `Hook`, through the API's classes or by itself, and isn't abstract; each must have a
constructor without parameters that isn't private, or the script has an error where the class
is:

```text
Bye.seal:3:1: Bye is a Hook, which the host makes with new(), but Bye has no constructor without parameters
```

`script.create(Hook.class)` then makes each of them, in the order of the files and their classes,
as `Hook`s: new objects each time it is called. A fault while one is made (its constructor, the
script's static fields) is a `SealException`. A class with type parameters or shapes isn't one
class on the JVM, and isn't one the host makes. `build()` checks that the parent class loader has each entry type, and
that it is the same class: scripts implement the parent's, and the host makes none of them if its
own `Hook` is another loader's.

**Snippets.** A snippet is statements: a chat command's code, a macro typed into a box, a
setting's formula. The host binds its Java values by name, with their Java types, and may add a
prelude of its own statements, which make the code's values from them:

```java
SealSnippet hi = seal.snippet("game.say(\"hi at tick \" + game.ticks());")
        .imports("api.*")                          // as a file's imports; seal.java's are already in
        .bind("ctx", Context.class, ctx)           // a Context; an int is a JavaInt, a String a JavaString
        .prelude("Game game = new Game(ctx);")     // the host's: Seal's types for the code
        .compile();
if (hi.ok()) {
    hi.run();                                      // with ctx; hi.run(other) with another
}
```

The snippet compiles, with the engine's library, into a struct `Snippet` in `Snippet.seal`, whose
`static void run(Context ctx)` holds the prelude and then the code. Its first line holds the
imports, the struct and the method, and the code starts on it, so that the code's lines are the
file's: its errors, and a fault's lines, are at their lines and columns in the code; one in what
the host bound, imported or put in the prelude is at line 0. `run()` runs it with the values bound,
`run(values)` with others, one for each binding in order; a fault is a `SealException`, and
`System.exit` ends the snippet. Each compile is a script of its own. A snippet that the host passes
to Java is `snippet::run`, a `Runnable`.

Java's types are bound as Seal names them (JAVA.md §3): the primitives, `String`, `Object` and the
boxes as `seal.java`'s types, `JavaInt`, `JavaString`, `JavaObject`, `JavaIntBoxed`, and other
classes by their own names, imported. An array can't be bound, and a value must be of its type.

## 5. The Java classes scripts may use (E4)

A script uses no Java class the host doesn't allow; the host's library uses any. A mod's players
share their macros, and a macro from a forum shouldn't read the player's files, or reach into the
game past what the mod's API offers. `Builder.allowJava` allows them:

* a class, `"java.util.ArrayList"` or `ArrayList.class`, and the classes nested in it;
* a nested class alone, `"java.util.Map.Entry"`;
* a package's classes, `"java.util.*"`, not its subpackages' (`java.util.concurrent`'s);
* every class, `"*"`, for a host that trusts its scripts.

Always allowed: seal.java's classes, `JavaObject`, `JavaString` and the boxes (Java's `Object`,
`String`, `Integer`...); what a for-each over a Java collection and a `try` closing a Java resource
call (`Iterable`, `Iterator`, `AutoCloseable`); the engine's entry types, which scripts implement;
and in a snippet, the types of the values bound.

A script uses a class wherever its code names it: an import (a static one too), a type (a
field's, a parameter's, a cast's, a type argument, a parent or interface), `new`, a static
member's class, a method reference. And wherever it uses a member of one without naming it: a
method of a Java object that the library's API returns (`Raw.make(1).say(...)`, where `make`
returns the host's `Context`), a Java parent's method or field of a Seal class (the API's `Dice`
extends `java.util.Random`, so `dice.nextInt(...)` is `Random`'s), and what `getClass()` gives
(`o.getClass().getName()` is `java.lang.Class`'s). A member is the receiver's: a script allowed
`ArrayList` calls the methods it has from `AbstractCollection`. Each use is an error where it is:

```text
Main.seal:5:23: Java's demo.Context isn't one the host lets scripts use
```

The standard library is Seal, not Java, and scripts use it as any program does: `List`, `Math`,
`System.stdout`. Its files are the host's to allow (`files(true)`), and `System.exit` ends a
script, not the JVM (§1). `@jvm` and `@native`, which give a method the runtime's code, are the
standard library's alone.

## 6. Each script a program of its own

Two scripts never share state: each has its own static fields (the library's too, §3), open files,
standard streams, and the text the runtime keeps between a file operation and the library's
reading it. Their classes are loaded by a class loader of their own, which also loads the script's
own copy of the runtime's `Natives`, the only runtime class that holds a program's state; the rest
of the runtime is the engine's, shared. The script's classes come first, before the parent's: a
host whose jar holds Seal classes of its own (a mod built with `seal build --library`, or with the
API built in) doesn't change a script's.

A script is one program, as a Lua state is one: its code runs on one thread at a time. Two
scripts may run on two threads at once.

A script's static fields keep their values for as long as it is loaded: `run()` twice is main
called twice, as in Java. To start over, compile again; the script that is dropped is collected
when nothing uses its classes.

## 7. In the compiler

`Sources` reads `.seal` files and folders, and files from a class loader's resources by an index,
and finds the standard library (§2), for `Main` and the engine. The engine checks a script with the
library's files before its own, with `Checker.analyze`, as `seal check` does, and compiles with
`JvmGen.generateLibrary`, every class, rather than with `JvmGen.generate`, which compiles what
main reaches: the host uses classes main never does. `SealScript` loads the classes, and gives the
script's copy of `Natives` the engine's streams and file access by reflection, as `JvmRunner` sets
the shared one's for `seal run`.

`JavaClasses` reads Java's classes from a class loader's resources too (the parent's), after the
JDK's and before the class path's, as a class loader under the parent finds them. It reads class
files without loading them, as it does a jar's, so no static initializer runs while a script
compiles. A package to import with `*` is the loader's when it has a folder of it among its
resources, or when it, or a loader above it, has defined it, by loading one of its classes. A jar
without folder entries (as `seal build --jar` writes) has no folders, so before any class of a
package is loaded, its classes are imported one by one; a game's packages are loaded long before
its mods compile scripts.

The allowlist (§5) is the checker's: `Checker.analyze` takes the script's files and which Java
classes they may use, by binary name, and denies a Java class where `visibleClass` already denies
a private one (every type a program names: types, `new`, a class in an expression), at imports,
and where a Java method or field is chosen, of the receiver's class or, through a Seal class, of
the Java class declaring it. The class whose code is checked decides, not the file being checked:
the backend asks for types again later, of the library's classes too. `JavaAllowlist` matches the
patterns.

The entry check (§4) loads the script's classes without initializing them, and asks the JVM
which are each entry type, then the checker's view of each, its constructors, why one can't be
made; `JvmGen.binaryName` gives a class's JVM name. A snippet is compiled as a file of its own.

Compiling is as fast as the JVM is warm: a small script takes about 640 ms in a JVM that has
compiled nothing, and about 60 ms once the JVM has compiled the compiler. Every compile checks the
standard library and the host's library again (§10).

## 8. Testing

`EmbedTest` compiles and runs scripts through the engine: exit codes and static fields across
runs; two scripts' fields and streams kept apart; classes loaded from Java, static methods and a
Seal class called through Java's interface; errors and warnings as data; faults as
`SealException`s, `System.exit`; standard input; files off unless allowed; a folder, and a `.java`
file in it; the class path; the standard library read from resources by its index.

For E2, it compiles a host's Java classes (`Hook`, `Context`) into a class folder that only a
class loader of its own has, and a Seal API over them: a script extends the API's `Macro`, and the
test calls it as a `Hook` with a `Context`, then checks what it said; a second script's API has
its own static fields; scripts import the loader's classes, one by one and by `*`, and a jar's
without folders once one of its classes is loaded, and another engine's scripts don't see them;
the API from a class loader's resources; its errors from `build()`; its warnings left out of a
script's; a script's file with an API file's name.

For E3: a script's two macros made, in order, and not its abstract class or its other classes,
nor the library's; new objects each time; a macro without `new()`, and one whose `new()` is
private; a fault in a constructor; `create` of a type that isn't an entry type, and an entry type
the parent hasn't got; a JDK interface as one. Snippets with a binding and a prelude, run with
the value bound and with another; errors and warnings at the code's places, the prelude's at line
0; a fault's line; `System.exit`; an `int` and a `String` bound; an array, and a value of the
wrong type.

For E4, with a library that hands out the host's `Context` and has a class extending
`java.util.Random`: the library compiles, and a script using its Seal types does; a script can't
import `java.io.File`, write `java.lang.System.exit(0)`, statically import `Math`'s `max`, call the
`Context` the library gives, call `Random`'s `nextInt` through the library's class, or call
`getName()` on `getClass()`'s `Class`, each one error where it is; seal.java's types need no
leave. Allowed: a class, by its `Class`; `java.util.*`, with a for-each over an `ArrayList`, but
not `java.util.concurrent`'s; `java.util.Map` with `Map.Entry`, and `java.util.HashMap` without
them; `*`; a pattern it doesn't take. A snippet uses the types it binds, and no other.

## 9. Order of work

| Step | Contents | Done when |
| ---- | -------- | --------- |
| **E1**, **done** | `SealEngine`, `SealScript`, `SealException`; errors as data; every class compiled and loaded in the host's JVM; each script its own `Natives`; `seal.jar` with the standard library (`jar.sh`) | `EmbedTest`; `seal.jar` runs the examples, and a Java program compiles and runs scripts with it alone on its class path |
| **E2**, **done** | The host's Seal API, compiled with every script (§3), from folders, a jar's resources, or code; checked when the engine is built. Java's classes read from the parent class loader (the game's), not only from jars and folders | a script extends the API's class, which implements the host's Java interface, and the host calls it with its own object (`EmbedTest`) |
| **E3**, **done** | Entry classes: `Builder.entry(Hook.class)`, checked while compiling (a script's classes implementing `Hook` can be made, with `new()`); `script.create(Hook.class)`, each of them made, as `Hook`s; snippets, statements compiled with Java's values bound by name and a prelude of the host's (`seal.snippet(code).bind("ctx", Context.class, ctx).prelude(...)`), run by `run()` | `EmbedTest` |
| **E4**, **done** | The Java allowlist (§5): scripts use no Java class but those the engine allows, checked wherever a Java type is used, not only in imports; the host's library may use what its scripts can't | a script can't reach `java.io.File` or the game's classes, unless allowed, while the API it uses does (`EmbedTest`) |
| **E5** | A budget for loops: a check where a loop goes round again, so the host can stop a script that runs on without returning (the tests' `JvmGen.generateCountingSteps` counts rounds already) | a `while (true)` is stopped, and the host goes on |
| **Macro mod** | `examples/24-macro-mod`: macros in `config/macros/`, compiled in the game; a Seal API, `waitTicks` on virtual threads in step with the game's thread; `/macro reload`; errors in chat | the mod runs a macro in Minecraft 26.3 |

## 10. Questions

1. **The libraries' cost.** Every compile checks the standard library and the host's library
   again. If hosts compile often, the engine could keep them checked.
2. **A script's files and the library's.** A script's file can't have the name of one of the
   library's (it is an error); a script's class can't have the name of one of the library's
   classes either, as two of a program's can't. Whether a script may replace an API class, as a
   mod's player might want to, is left until a host asks.
