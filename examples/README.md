# Examples

## The three kinds of types

Every type is declared with one of three keywords, and the keyword decides
what assignment and function calls do:

| Keyword  | Is | `b = a` | Passing `a` to a function | Freed |
|---|---|---|---|---|
| `struct` | a plain value, like a C# struct | copies it | the function gets a copy | at the end of its scope |
| `unique` | a value that can't be copied, for things that own a resource | not allowed, except from `new` or a function result | lent: the function changes the caller's object | at the end of its scope, running its destructor |
| `class`  | a shared object, like a Java object | both refer to the same object | the function gets the same object | automatically, once nothing refers to it (no destructor) |

```text
struct Point { public int x; public int y; }
unique File { ... delete() { ... } }
class Animal { ... }

Animal a = new Dog();      // a class variable refers to an object; no pointer syntax
List<Animal> zoo = new List();
```

From the standard library, `string` is an immutable value, as Java's (methods
return new strings, and `StringBuilder` builds text), while `List`, `Queue` and
`IntMap` are classes, shared like Java's collections. The library is in
packages: `seal.lang` (`System`, `Math`, the exceptions) needs no import, but a
file using `List` says `import seal.util.List;`, as in Java. Memory is managed for
you: nothing needs `free()`, and `delete` is only for `addr` memory. A class
that holds a resource, as `FileWriter` does, is closed with `close()` or
try-with-resources.

Pointers (`addr<T>`) are optional and only needed for low-level work; just
two examples use them, on purpose (03 and 11).
[13-memory-model](13-memory-model/Main.seal) shows all of this side by side.

## Running them

Each directory is a complete program. Run one with:

```bash
./seal run examples/01-basics
```

`expected.txt` in each directory is the program's output; `ExamplesTest` checks
that every example still produces it.

| Example | Shows |
|---|---|
| [01-basics](01-basics/Main.seal) | integers of any width and how they wrap, control flow, recursion, the single shift operator `<<<`, `bit` as its own type, floats |
| [02-shapes](02-shapes) | a class with two shapes (`Matrix<T><<N>>` and `<<N, M>>`), shape guards, a field whose type differs per shape, the coverage rule, a value binder that computes a result type (matrix multiply), compile-time strings as units (`Quantity<<"m">>`), the bare shape `<<>>` |
| [03-memory](03-memory/Main.seal) | `addr<T>`, optional low-level memory: the reversed operators (`*x` takes an address, `&p` follows it), bit addresses and address arithmetic, `new<T>(n)` and `delete`, a linked list, bit-packed classes and alignment, exact layouts with bit slices |
| [04-objects](04-objects) | constructors and destructors, destruction at scope exit and on early return, static fields, `const`, `private<Bank>`, value semantics and passing by address |
| [05-classes](05-classes) | classes as shared references (Java style), inheritance, virtual methods, `super(...)` and `super.method()`, `private<Bird>` (access for a named subclass), checked downcasts, shape-dependent parents (`Vehicle<<2>>` is a `Bike`), handles sharing one object |
| [06-operators](06-operators) | operator overloading with `@overload(Operator...)`, `Operator.NEGATE` and `Operator.SUBTRACT`, derived comparisons, compound assignment, bit indexing and slicing |
| [07-fixed-point](07-fixed-point/Main.seal) | the Mandelbrot set drawn in `fixed<<10, 22>>`, exact values, `*` versus `Fixed.mulExact`, `floor`, `fraction`, casts, `ufixed` |
| [08-strings](08-strings/Main.seal) | `string<<N>>` and the bare `string`, immutable strings (`+=` and `toUpper` make new ones), `+` with a computed capacity, `StringBuilder`, sorting strings, a `string<<int N>>` binder, ROT13, parsing and formatting numbers |
| [09-collections](09-collections/Main.seal) | `List<T>`, `Queue<T>` and `IntMap<V>` as shared objects (created with `new`): breadth-first search, memoized recursion through a shared map, sorting, `Random` |
| [10-big-numbers](10-big-numbers/Main.seal) | `BigUInt<<BITS>>`: 100!, 2^1000, Fibonacci(300), wrapping at a chosen width, `Integer.mulExact` |
| [11-bits](11-bits/Main.seal) | a prime sieve in an `addr<bit>` bit array, CRC-32, `bitCount` and rotations, reversing bits by indexing, Gray codes, slices of a signed integer |
| [12-generics](12-generics) | type and value parameters together, operators on a type parameter checked per instantiation, the same generic used with `int`, `double`, `fixed`, `string` and `BigUInt`, instantiation-dependent sizes, nested generics |
| [13-memory-model](13-memory-model) | the same operations on a `struct`, a `unique` and a `class`: copying, lending, moving out of a function, a unique's destructor, sharing; strings as immutable values and lists as shared objects |
| [14-interfaces](14-interfaces) | an `abstract class` with abstract methods, fields and a constructor; `interface`s, including a generic `Comparable<T>`; a class implementing several, an unrelated class implementing one; interface handles in a list and an array; checked casts to classes and interfaces, `instanceof` with a binding |
| [15-java-style](15-java-style) | the Java-style conveniences together: an `enum` with a field and methods, `switch` in both styles, for-each over strings, lists and arrays, lambdas with `Predicate`, `Function` and `List.sort`/`forEach`/`removeIf`, joining strings and numbers with `+`, `instanceof`, and exceptions with `try`/`catch`/`finally` |
| [16-files](16-files/Main.seal) | files and folders: making a folder tree in a temporary folder, walking it, `File` (names, extensions, sizes, listing, renaming), `Files` (reading and writing whole files and lines, copying, deleting a tree), `FileWriter` with try-with-resources and `FileReader` a line at a time, `IOException`s |
| [17-nested-types](17-nested-types/Main.seal) | a whole program in one file with nested types: an enum, classes, an interface and a struct inside `Main`, a type nested two deep (`Board.Counts`), bare names inside and dotted names outside, private members shared between a type and its nested types |
| [18-packages](18-packages/shop/Main.seal) | a small shop in five packages (`shop`, `shop.model`, `shop.pricing`, `shop.report`, `shop.stock`), each in its own folder: importing one type by name and a whole package with `*`, using types of the same package without imports, a nested type of an imported one (`Cart.Entry`), a type named in full without an import, two types named `Item` with one renamed by `import ... as`, a static method renamed by `import static ... as`, interfaces and enums across packages, formatting with `printf` |
| [19-static-imports](19-static-imports/space/Main.seal) | `import static`: all of `Math` with `*` (`sqrt`, `sin`, `pow`, `PI`), one library constant, every constant of a program's own class, single methods; a class's own method and a local variable coming before imported ones |
| [20-pure-functions](20-pure-functions) | `static pure` methods that run while compiling: a counter typed `uint<<Bits.needed(1000)>>`, an array sized by the primes a sieve finds, `const` fields worked out by calls, a `Ring<<N>>` whose capacity is worked out for each `N`, arrays made and sorted while compiling; every operator in a compile-time value, `**` (a power), `?:` and casts among them |
| [21-java](21-java/Main.seal) | Java's own classes, imported and used as Seal's: a `TreeMap<JavaString, JavaIntBoxed>` and its entries, a `List<JavaString>` whose type arguments `List.of` works out, for-each over Java's collections and arrays, Java's operators on `JavaInt` and the others, `java.time` dates and an enum constant, overloads chosen as Java chooses (`valueOf(char)` and `valueOf(int)`, `remove(int)` and `remove(Object)`), varargs, values in by `JavaInt.of` and `JavaString.of` and out by casts, a seeded `java.util.Random`, Seal objects in Java lists, Seal lambdas as Java's `Runnable` and `Comparator` (a Java `Thread` runs one), a Java `HashSet` finding Seal objects by their `==` and `hash()`, a Seal class extending `ArrayList` whose `add` Java's `Collections.addAll` calls, catching an `IOException`, try-with-resources closing a Java reader, arrays in and out and in place, `java.util.regex`; on the JVM only |
