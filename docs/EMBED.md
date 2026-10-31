# Seal inside Java programs: design

**Status:** E1 is built (2026-10-30): a Java program compiles Seal's code while it runs, gets the
errors as data, and uses the classes in its own JVM. E2 to E5 are planned (§6), with a Minecraft
mod for macros, which players write in Seal, as the program they are for.

Seal is a scripting language for programs on the JVM: Minecraft mods, Java games, Java
applications. It isn't one for C and C++ programs, as Lua is: those would need a JVM, or a runtime
in C, which Seal hasn't got. What it offers a JVM program over Lua's ports and Java's other
scripting languages is that a script is checked before it runs, and is compiled to class files,
which the JVM's JIT compiles as it does Java's.

## 1. What it looks like

```java
SealEngine seal = SealEngine.builder()
        .out(log)                         // the scripts' standard output; System.out unset
        .files(false)                     // no files, as unset: every file operation fails
        .build();

SealScript script = seal.compile(Path.of("config/scripts"));   // or seal.compile("Main.seal", code)
for (Diagnostic e : script.errors()) {
    log.println(e);                       // Main.seal:3:9: unknown method printn
}
if (script.ok()) {
    int code = script.run();              // Main's main(), as `seal run` runs it
    Class<?> tool = script.load("Tool");  // or any class, for Java's code to use
}
```

The package is `io.github.thelinuxseal.seal.embed`:

* **`SealEngine`**, made by its `Builder`, holds what its compiles share: the standard library,
  read once when it is built; the jars and class folders of the Java classes scripts may use
  (`classPath`, as `seal --classpath`), and the class loader they are loaded under (`parent`,
  the engine's by default); the warnings to report (`warn(Warning.AUTO_CAST)`, as `-WautoCast`);
  and each script's standard input, output and error, and whether it may use files.
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
* **`close()`** closes the files the script left open and flushes its output. `run` does this as
  main ends, as a program's end does.

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

## 3. Each script a program of its own

Two scripts never share state: each has its own static fields, open files, standard streams,
and the text the runtime keeps between a file operation and the library's reading it. Their
classes are loaded by a class loader of their own, which also loads the script's own copy of the
runtime's `Natives`, the only runtime class that holds a program's state; the rest of the runtime
is the engine's, shared. The script's classes come first, before the parent's: a host whose jar
holds Seal classes of its own (a mod built with `seal build --library`) doesn't change a script's.

A script is one program, as a Lua state is one: its code runs on one thread at a time. Two
scripts may run on two threads at once.

A script's static fields keep their values for as long as it is loaded: `run()` twice is main
called twice, as in Java. To start over, compile again; the script that is dropped is collected
when nothing uses its classes.

## 4. In the compiler

`Sources` reads `.seal` files and folders, and finds the standard library (§2), for `Main` and the
engine. The engine checks with `Checker.analyze`, as `seal check` does, and compiles with
`JvmGen.generateLibrary`, every class, rather than with `JvmGen.generate`, which compiles what
main reaches: the host uses classes main never does. `SealScript` loads the classes, and gives the
script's copy of `Natives` the engine's streams and file access by reflection, as `JvmRunner` sets
the shared one's for `seal run`.

Compiling is as fast as the JVM is warm: a small script takes about 640 ms in a JVM that has
compiled nothing, and about 60 ms once the JVM has compiled the compiler. Every compile checks the
standard library again (§7).

## 5. Testing

`EmbedTest` compiles and runs scripts through the engine: exit codes and static fields across
runs; two scripts' fields and streams kept apart; classes loaded from Java, static methods and a
Seal class called through Java's interface; errors and warnings as data; faults as
`SealException`s, `System.exit`; standard input; files off unless allowed; a folder, and a `.java`
file in it; the class path; the standard library read from resources by its index.

## 6. Order of work

| Step | Contents | Done when |
| ---- | -------- | --------- |
| **E1**, **done** | `SealEngine`, `SealScript`, `SealException`; errors as data; every class compiled and loaded in the host's JVM; each script its own `Natives`; `seal.jar` with the standard library (`jar.sh`) | `EmbedTest`; `seal.jar` runs the examples, and a Java program compiles and runs scripts with it alone on its class path |
| **E2** | Seal libraries the host already has: a script is checked against a library compiled into the host's jar (from its `.seal` files there), and uses that library's classes rather than making its own, so the host's Java code and the script's share its types. And Java's classes read from a class loader, the game's, not only from jars and folders | a script uses a Seal API the host built with `seal build --library`, and the host passes it an object of the API's class |
| **E3** | Entry classes: `Builder.entry(Macro.class, MacroApi.class)`, checked while compiling (every class implementing `Macro` has `new(MacroApi)`); `script.create(Macro.class, args)`; snippets, a method's body compiled with named values bound (`seal.snippet(Runnable.class, code).bind("api", MacroApi.class, api)`) | `EmbedTest` |
| **E4** | The Java allowlist: scripts name no Java class but those the engine allows, checked wherever a Java type is used, not only in imports | a script can't reach `java.io.File` or the game's classes, unless allowed |
| **E5** | A budget for loops: a check where a loop goes round again, so the host can stop a script that runs on without returning (the tests' `JvmGen.generateCountingSteps` counts rounds already) | a `while (true)` is stopped, and the host goes on |
| **Macro mod** | `examples/24-macro-mod`: macros in `config/macros/`, compiled in the game; a Seal API, `waitTicks` on virtual threads in step with the game's thread; `/macro reload`; errors in chat | the mod runs a macro in Minecraft 26.3 |

## 7. Questions

1. **A precompiled library's `Natives` (E2).** A Seal library in the host's jar has the host's
   copy of `Natives`, not the script's, so what it prints goes to the host's streams. Its code
   could take the script's from the thread it runs on, or the library could be loaded per script.
2. **The standard library's cost.** Every compile checks the standard library again. If hosts
   compile often, the engine could keep it checked.
