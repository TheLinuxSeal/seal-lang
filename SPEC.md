# Seal Language Specification

> **Status:** Draft
> **Source files:** `.seal`
> **Repository:** https://github.com/TheLinuxSeal/seal-lang

---

## 1. Overview

Seal combines:

* **Java-style object-oriented programming** with shared, reference-typed classes
* **Value types with exact layouts** (`struct` and `unique`)
* **Compile-time instanced generics**
* **Optional low-level memory access** (`addr<T>`) into a bit-addressed pool; ordinary code never needs it
* **Compilation to JVM class files**, beside Java's own classes, which programs use too

The central design principle is that compile-time values can participate directly in type identity.

For example:

```text
Array<int><<64>>
Array<int><<128>>
Array<int><<64, 2>>
```

are distinct concrete types.

Generic instantiation is performed at compile time. Each instantiation is effectively stamped out as its own concrete type, giving the compiler exact information about its size, layout, and compile-time parameters.

---

# 2. Source Files and Types

Each type occupies one source file, named after it, in a folder named after its package (§2.4).

The file system therefore acts as the module system.

## 2.1 Kinds of types

There are three kinds of type declaration:

| Keyword  | What it is | Assignment | Lives | Inherits | Destructor |
| -------- | ---------- | ---------- | ----- | -------- | ---------- |
| `struct` | a value, like a C struct or a C# struct | copies it | inline: in variables, fields, arrays | no | not allowed |
| `unique` | a value that cannot be copied, for things that own a resource | not allowed from a variable; lend it instead | inline | no | allowed |
| `class`  | a shared reference object, like a Java class | shares the same object | in the class-object pool, reached through handles | yes, plus interfaces and `abstract` (§30) | not allowed: freed automatically, at no set time (§26.1) |

```text
struct Point { public int x; public int y; }
unique File { ... delete() { ... } }
class Animal { ... }
```

A class name used as a type means a reference (a handle) to an object of that class, as in Java:

```text
Animal a = new Dog();      // a handle<Animal>
List<Animal> zoo;          // a list of handles
```

`handle<T>` is the explicit spelling of the same type.

`interface` and `abstract class` (§30.1, §30.2) belong to the class side: an interface is not a fourth kind of value, but a set of methods that classes implement, and an interface name used as a type is a handle too.

Structs and uniques have exact, fixed layouts (§21-22). A struct or unique cannot contain a class object inline; a field of class type is a handle.

Pointers (`addr<T>`, §18-24) are optional: everything except low-level work (reinterpreting bits, writing allocators) can be done without them.

> **Status.** Implemented: the three kinds, implicit handles, automatic freeing of class objects (§26.1), the `struct`/`unique`/`class` rules (§16.1), resources closed with try-with-resources (§41.4), and a standard library that frees its own memory (§39, §42).

## 2.2 Headers

A source file may begin with multiple headers followed by one shared body.

```text
struct Array<T><<int N>>
struct Array<T><<int N, int K>> {
    ...
}
```

The headers describe the possible **shapes** of the type. Every header uses the same keyword.

## 2.3 Nested types

A type can be declared inside another: a `class`, `struct`, `unique`, `interface`, `enum` or `abstract class`, nested as deeply as needed. Each source file holds one top-level type, named after the file, and any number of nested ones.

```text
class HashMap<K, V> {
    public static class Entry<A, B> { ... }   // used outside as HashMap.Entry<string, int>
    struct Slot { ... }                       // private: only for HashMap
    ...
}
```

* Inside the outer type, and inside the types nested with it, a nested type is used by its name: `Entry`. Elsewhere it is used by its full name: `HashMap.Entry`, `Outer.Kind.FOOD`, `new Outer.Item(...)`, `Outer.Item.Stats.count`.
* A nested type is private to its outer type unless it is `public`, as members are (§17). `static` may be written, as in Java, and changes nothing: every nested type is like Java's static nested class.
* A nested type has no hidden reference to an object of its outer type, and doesn't see its outer type's type parameters; it can have its own (`Entry<A, B>`).
* As in Java, a type and the types nested in it (at any depth) may use each other's private members.
* A nested type can't have the name of its outer type's member, type parameter, or another nested type.
* A name is looked up from where it is written: the type itself and the types nested in it, then each enclosing type and the types nested in it, then the top-level types (§2.4).

## 2.4 Packages and imports

As in Java, a file can put its type in a package and import types from other packages, before the type:

```text
package com.example.game;            // this file's type is com.example.game.Player

import com.example.util.Vec2;        // one type, by name
import com.example.net.*;            // every type of a package

class Player {
    Vec2 pos;                        // com.example.util.Vec2
    Connection link;                 // com.example.net.Connection
    com.example.util.Color tint;     // a type can always be named in full
}
```

* A file without `package` is in no package, as small programs are. A file in package `com.example.game` goes in a folder `com/example/game`, which is checked, as the file name is.
* A type is named in full (`com.example.util.Vec2`) in types and in expressions: `com.example.util.Tools.zero()`, `new org.other.Point(1, 2)`. A nested type adds its own name: `com.example.util.Vec2.Unit`. In an expression, a name that is a variable, field or type comes first, as in Java.
* A plain name is looked up in this order: the type itself and the types around it (§2.3), the types the file imports by name (by the names it imports them as, below), the types of its own package (for a file in no package, the program's other types in no package), the types `*` imports bring, then `seal.lang`'s and the built-in `string` and `array`. If two `*` imports bring a type of the same name, using it is an error that names both; import the one meant by name.
* The standard library is in packages too, as Java's is (§42). `seal.lang`'s types (`System`, `Math`, `Integer`, the exceptions, ...) need no import, as `java.lang`'s don't; the others are imported as any package's are: `import seal.util.List;`, `import seal.io.*;`. Using one without an import says which to write: `unknown type List; import it: import seal.util.List;`.
* A type in no package can't take the name of a `seal.lang` type, nor of `string` or `array`, which every file sees. A type in a package can, as Java's `com.example.String` can: in its own package, and in a file that imports it, its name means it; elsewhere the name means the library's. The names of the library's other packages are free: a program's own `List`, in no package, comes before `seal.util.List` even in a file that imports `seal.util.*`. Package `seal`, and the packages in it, are the library's: a program can't declare one.
* As in Java, a type in a package can't use one that is in no package, the built-in `string` and `array` aside.
* `import a.b.Outer;` also makes `Outer.Inner` usable, and `import a.b.Outer.Inner;` and `import a.b.Outer.*;` import nested types themselves. Private nested types can't be imported.
* Packages don't change access (§17): members are private unless declared otherwise, and top-level types are public, so another package can use any top-level type and its public members.
* Two packages may have types of the same name (`a.Tools`, `b.Tools`). Stack traces and run-time errors name types in full: `at com.example.game.Main.main (Main.seal:12)`, `uncaught seal.util.NoSuchElementException`. `seal.lang`'s go by their own names, as programs write them: `uncaught IllegalStateException`.

### Renaming imports

`import ... as` imports a type under another name, so a file can use two types of the same name:

```text
import seal.util.HashMap as SealHashMap;             // the standard library's
import some.util.HashMap as UtilHashMap;             // another one

SealHashMap<string, int> ages = new SealHashMap();
UtilHashMap.Entry e;                                 // its nested types come with it
```

* The file knows the type by the new name only: using a renamed type by its own name says so (`unknown type Widget; this file imports other.Widget as W`).
* An import names a type in full, with its package: `import HashMap;` is an error, and so is `import string as Text;`, as a type in no package needs no import.
* `as` renames one name, so `import some.util.* as U;` is an error. Two imports can't take one name, nor the name of the file's own type. `as` is a word only here: it is still a name everywhere else.

### Static imports

`import static` brings a type's static members (methods, fields and constants) so they can be used without the type's name, as in Java:

```text
import static seal.lang.Math.*;             // every static member of Math: sqrt(x), sin(a), PI
import static seal.lang.Integer.MAX_INT;    // one member
import static com.example.util.Units.*;     // a program's own types too

double r = sqrt(x * x + y * y);
double area = PI * r * r;
array<int><<SIZE>> cells;                   // an imported constant is a compile-time value
```

* `import static T.name;` brings every static member of T so named (all overloads of a method), and a nested type so named; `import static T.*;` brings all of them. T is named in full, the standard library's too, as in Java's `import static java.lang.Math.*;`: `import static Math.*;` is an error that gives the full name, `seal.lang.Math`.
* `import static T.name as other;` brings them under another name: after `import static seal.lang.Math.max as biggest;`, `biggest(a, b)` calls `Math.max`, and `max` isn't imported. Fields and constants too (`import static Units.count as total;`, then `total += 1` changes `Units.count`).
* Where a plain name is used, local variables and the type's own members (and those it inherits) come first; then members imported by name; then those `*` static imports bring. A method name brings the overloads of every type imported with it, and the call picks among them as usual.
* An imported static field can be assigned (`count = 10;` changes `Units.count`). Private and instance members can't be imported.
* If two `*` static imports bring a field of the same name, using it is an error that names both; write the one meant, as `Math.PI`.
* An unknown method that a library type has suggests it: `unknown method max; it is a static method of Math, Fixed and Integer: call Math.max(...), or import static seal.lang.Math.max;`, and outside `seal.lang`, `call Files.readString(...) (with import seal.io.Files;), or import static seal.io.Files.readString;`.

## 2.5 Records

A record is a struct made only to hold some values, its components, as Java's records are. It says what they are, and the rest is written for it:

```text
record Point(int x, int y) {
    public Point plus(Point o) { return new Point(x + o.x, y + o.y); }   // methods as in any struct
}

record Range(int lo, int hi) {
    new {                                 // a compact constructor: checks (or adjusts) the components
        if (lo > hi) { throw new IllegalArgumentException("lo is above hi"); }
    }
}

record Pair<A, B>(A first, B second) { }

Point p = new Point(3, 4);
p.x(), p.x                                // 3: a component is read either way
p == new Point(3, 4)                      // true: components compared with ==
"at " + p                                 // "at Point[x=3, y=4]"
```

What it gets:

* **Its components, fixed when it is made.** They are public fields that only its constructors assign: `p.x = 5`, `p.x++`, and changing what a component holds inline (`seg.from.x = 0`) are errors, in its own methods too. A component that is a class object can still change itself, as in Java. To change a value, make a new record: `p = new Point(5, p.y);`.
* **A constructor taking every component,** in order. A compact constructor, `new { ... }`, runs first with the components as its parameters, so it can check them or change them; it is public, as Java requires. A record can instead write out `new(int x, int y) { ... }` itself, and can have other constructors.
* **`p.x()` as well as `p.x`,** as Java reads components; no method may have a component's name.
* **`==`** (and `!=`) comparing the components with their `==`, **`hash()`** combining their hashes, so records work as `HashMap` keys, and **a cast to string** giving `Point[x=3, y=4]` (§46). A record that declares its own `@overload(Operator.EQUALS)`, `hash()` or cast to string keeps it.
* **A default value** with every component zero or empty (`Point p;` is `Point[x=0, y=0]`), as an enum's is its first constant, so records work in `List`, `array` and `HashMap`, which fill empty slots with it.

A record is a struct: it is copied, it can't extend or implement anything, and it has no fields besides its components but static ones. Its `==`, `hash()` and text use `Objects.equals`, `Objects.hash` and `Objects.toString`, so a component whose type has no `==` (or no cast to string) is an error only where the record's `==` (or text) is used. `record` is a word only where it starts a record, so a variable can still be named `record`.

---

# 3. Generic Parameters

There are two fundamentally different kinds of generic parameters.

## 3.1 Type parameters

Type parameters use single angle brackets:

```text
<T>
```

Example:

```text
struct Array<T>
```

## 3.2 Compile-time value parameters

Compile-time values use double angle brackets:

```text
<<int N>>
```

Example:

```text
struct Array<<int N>>
```

A class may contain:

* no generic parameters
* type parameters only
* value parameters only
* type parameters followed by value parameters

The order is always:

```text
<T> <<...>>
```

The reverse ordering is invalid:

```text
<<...>> <T>    // error
```

The `<T>` portion must be identical across all headers for a class. Only the `<<...>>` portion may vary.

Whitespace before `<<` is permitted.

The conventional formatting is:

```text
Array<addr<int>> <<8>> x;
```

when the preceding type already ends in `>`.

---

# 4. Generic Delimiters

`<<` and `>>` are reserved for compile-time generic parameters and shape syntax.

They do not represent shift operators.

For example:

```text
a << b
```

is invalid syntax.

The compiler should report the error in a way that points toward the `<<<` shift syntax.

## 4.1 Lexing

The lexer matches operators longest-first, so `<<` and `>>` are single tokens:

* `<<` always opens a value list.
* `>>` closes a value list, or closes two type-argument lists at once, as in `Array<addr<int>>`. When the parser expects a single `>` and finds `>>`, it splits the token and consumes only the first `>`.

`<<<` (shift) and `<<=` (shift-assign) are matched before `<<`.

Alternative delimiters were considered, including:

```text
[]
{}
()
>...<
|...|
`...`
<| ... |>
```

but `<<...>>` is the current syntax.

## 4.2 Trailing commas

Array literals and call arguments may end with a comma, so lists written one item per line can end every line the same way:

```text
array<int><<3>> a = [
    1,
    2,
    3,
];
draw(
    x,
    y,
);
```

Parameter lists may not: `void f(int a, int b,)` is an error.

---

# 5. Class Shapes

A class may have multiple headers representing different shapes.

```text
struct Array<T><<int N>>
struct Array<T><<int N, int K>> {
    ...
}
```

A shape describes which compile-time values are bound.

A parameter omitted from a particular header is considered **null** at compile time.

`null` is distinct from numeric zero.

Using an unbound parameter as an actual value is a compile-time error.

For example, a hypothetical:

```text
struct Foo<<int N, int K>> {
    ...
}
```

could have a shape in which `N` is bound while `K` is unbound.

The header:

```text
struct Foo<<>>
```

declares the **bare shape**, in which no values are bound. It is the shape selected when the class is used without `<<...>>`:

```text
Foo x;         // bare shape, same as Foo<<>>
```

---

# 6. Shape Identity

Shape identity is based on the resulting compile-time values.

Therefore:

```text
Foo<<2 + 2>>
Foo<<4>>
```

refer to the same type.

However:

```text
Foo<<0>>
Foo<<-1>>
```

are distinct types.

Different shapes are always distinct types, even when their layouts happen to be identical. For example, if `Foo<<>>` and `Foo<<16>>` have the same fields and sizes, they are still not interchangeable.

There are no default values in headers.

---

# 7. Generic Instantiation

The shape is determined by the type on the left-hand side when using `new`.

```text
Array<int><<64>> x = new Array();
Array<int><<64,2>> y = new Array();
```

The `new` expression therefore does not need to repeat the shape.

The compile-time values are part of the resulting type, while constructor arguments represent ordinary runtime state.

Only instantiations that actually occur in the program are generated.

---

# 8. Compile-Time Values

Arguments inside `<<...>>` must be compile-time constants.

Allowed expressions include:

* literals
* every operator on compile-time constants: arithmetic, `**` (§45.1), bitwise and shift operators, comparisons, `!` `&&` `||`, `?:`, casts (`(uint<<8>>)300` is 44), and `+` joining strings
* `const` variables
* `sizeof`
* other bound generic parameters
* calls to `pure` methods (§17.2), which run while compiling: `int<<Bits.needed(1000)>>`

```text
array<int><<2 ** 16 - 1>> table;                  // 65535 elements
int<<N > 32 ? 64 : 32>> value;                    // in a class with <<int N>>: chosen for each N
Quantity<<"k" + "m">> distance;
```

In a generic class, a value that isn't known yet (`2 ** N`, `N > 32 ? 64 : 32`, `Fib.of(N)`) is worked out for each shape the program uses; `?:` picks a side then, and only the side it picks is worked out.

Primitive values may be used as compile-time parameters.

This includes values such as:

* integers
* unsigned integers
* floating-point values
* strings

The resulting value determines generic identity.

### Open/proposed constant semantics

Current proposed rules include:

* floating-point identity based on bit representation
* NaN rejected as a generic argument
* strict IEEE constant arithmetic
* recursive instantiation depth limits
* a limit on the total number of instantiations

Compile-time functions are `pure` methods (§17.2), with a step limit.

---

# 9. Generic Member Guards

Members may have a `<<...>>` guard.

```text
int get<<int N>>() {
    return N;
}
```

The guard determines which class shape contains that member.

Matching is exact according to the shape rules.

A member may therefore have multiple shape-specific overloads:

```text
int getLength<<int N>>() {
    return N;
}

int getLength<<int N, int K>>() {
    return N * K;
}
```

Members overload according to:

```text
(shape, parameter types)
```

## 9.1 The empty guard `<<>>`

An empty guard `<<>>` is a shape guard. It matches only the bare shape, in which no compile-time values are bound (the class used without `<<...>>`).

```text
struct Buf<<>>
struct Buf<<int N>> {
    array<char><<N>> data<<int N>>;
    array<char><<16>> data<<>>;
}

Buf<<32>> a;   // data has 32 elements
Buf b;         // data has 16 elements
```

`<<>>` is distinct from an unguarded member:

| Guard              | Member exists in                      |
| ------------------ | ------------------------------------- |
| none: `foo()`      | every shape (unguarded)               |
| `<<>>`: `foo<<>>()`| only the bare shape                   |
| `<<int N>>`        | only the shape with `N` bound         |

For the all-or-nothing rule (§11), a `<<>>` member is guarded.

## 9.2 Per-shape member types

Guarded members sharing a name may have different types in different shapes:

```text
array<char><<N>> data<<int N>>;        // inline array
addr<char> data<<>>;          // address of an external buffer
```

Unguarded code referring to a guarded name is type-checked separately against each declared header, using that header's member types.

## 9.3 Value binders in parameter types

A parameter type may contain value binders of the form `<<int K>>`, or for a width, `<<size W>>` (§37.2). Each binder introduces a compile-time variable whose value is taken from the argument's type at the call site.

```text
struct string<<>>
struct string<<int N>> {
    public string<<N + K>> add<<int N>>(string<<int K>> other) {
        ...
    }
}

string<<3>> a;
string<<4>> b;
string<<7>> c = a.add(b);   // K = 4, returns string<<7>>
```

Rules:

* A binder is distinguished from a use by its type prefix: `<<int K>>` binds a new variable, `<<K>>` refers to an existing value.
* Bound variables are available in the return type, in later parameter types, and in the method body.
* Binders may appear only in parameter types. A variable that appears only in the return type cannot be inferred and is an error.
* A binder matches only a shape with that value bound: `string<<int K>>` does not accept `string<<>>`. A separate overload is required for the bare shape.
* A binder name must not shadow a class-level parameter.
* Variables decide binders before constants do. In `max(int<<size W>> a, int<<W>> b)`, `max(0, x)` with an `int<<64>>` x binds `W = 64` from `x`, and `0` then takes that type.
* When the arguments need different values, the binder takes the one every argument widens to (§41.6): `max(i, l)` with an `int` and an `int<<64>>` binds `W = 64`; `max(b, 1000)` with an `int<<8>>` b binds `W = 32`, as the constant is an `int`; `max(i, u)` with an `int` and a `uint` binds `W = 64`. A constant that fits no such type is an error: `max(u, -1)` on `uint<<size W>>`.
* When only constants are given, each binds at its default type (`int`, `char` for `'a'`, `double` for `1.5`): `max(2, 3)` is `max` with `W = 32`. A char argument binds as an `int` (§39), and so does a size (§37.2).
* A plain parameter is preferred to one with value binders that also fits: of `add(int)` and `add(int<<size W>>)`, `"x" + 5` uses `add(int)`.

Methods with value binders are instantiated once per distinct set of bound values that occurs in the program, in the same way as class instantiation (§55). Identical combinations share a single instance. Combinations that are never used are never generated.

---

# 10. Unguarded Members

An unguarded member has no compile-time shape restriction.

```text
int occupied;
```

Unguarded members exist in every shape.

An unguarded member can:

* access other unguarded members
* be accessed by guarded members
* be accessed by other unguarded members

An unguarded member cannot directly depend on a guarded member unless the required overload exists for **every declared shape**.

This is called the **coverage rule**.

Because guarded members may have different types per shape (§9.2), satisfying the coverage rule also requires that the unguarded code type-checks against every declared shape.

---

# 11. All-or-Nothing Member Guarding

Within a class, a member name must be either entirely unguarded or entirely guarded.

An unguarded member and a guarded member may **not** share the same name.

This is invalid:

```text
int size;

int getSize() {
    return size;
}

int getSize<<int N>>() {
    return N;
}
```

The name `getSize` cannot simultaneously represent an unguarded member and a guarded member.

This applies to both fields and methods.

Therefore:

```text
int data;
int data<<int N>>;
```

is also invalid.

A name must be:

* entirely unguarded, or
* entirely shape-guarded.

The empty guard `<<>>` counts as a shape guard. Therefore:

```text
int data<<>>;
int data<<int N>>;
```

is valid: both members are guarded.

Guarded members sharing a name may differ in type between shapes (§9.2).

This removes ambiguity from member lookup.

---

# 12. Member Lookup

Member resolution first determines whether a member name is unguarded or guarded.

For an unguarded name:

```text
getSize
```

only the universal member set is considered.

For a guarded name:

```text
get
```

the compiler selects the member whose shape guard matches the current instantiation.

Shape and name resolution occurs when the class definition is checked.

Checks depending on actual values occur during instantiation.

For example:

```text
array<T><<N>> data;
```

may be syntactically valid in the class definition while a particular invalid `N` is rejected when instantiated.

Within a class body, members are referred to by bare name, or through `this`.

## 12.1 `this`

`this` is the object a method or constructor runs on, as in Java:

```text
struct Point {
    public int x;
    public int y;
    public new(int x, int y) { this.x = x; this.y = y; }   // fields, past parameters of the same name
}

class Node {
    public Node next;
    public Node prepend(int v) { return new Node(v, this); }   // passing the object on
    public Iterator<int> iterator() { return new NodeIterator(this); }
}
```

* In a class, `this` is a handle to the object, used like any other: returned, passed, stored, compared with `==`. A lambda that uses it keeps the object alive (§30.3).
* In a struct or unique, `this` is the value itself: `this.x = 1` changes it, and using `this` as a whole value copies it, which a unique allows only when lending it to a method.
* `this.f()` and `f()` call the same method. `this` can't be assigned, and doesn't exist in static methods or static field initializers.

## 12.2 Generic methods

A method can have type parameters of its own, written before its return type, as in Java:

```text
static <T> T largest(List<T> xs) {
    T best = xs.get(0);
    for (T x : xs) { if (best < x) { best = x; } }
    return best;
}

static <T, R> List<R> mapAll(List<T> xs, Function<T, R> f) { ... }

class Box<T> {
    public T value;
    public <R> Box<R> map(Function<T, R> f) { return new Box<R>(f.apply(value)); }
}
```

* The type arguments are worked out from the arguments: `largest(nums)` with a `List<int>` makes `T` an `int`; a constant gives its default type (`1` an `int`, `"x"` a `string`); and a lambda gives what it returns, so `mapAll(nums, n -> "n" + n)` makes `R` a `string`. Variables decide before constants: in `<T> T second(T a, T b)`, `second(1, c)` with a char `c` makes `T` a `char`.
* When the arguments don't tell, the call gives them: `Main.<string>empty()`, `box.<int>convert(...)`.
* Like a generic class (§12), a generic method is compiled once for each set of types it is used with, and what it does with `T` (operators, members, and calls that take `T` values, like `Math.max(a, b)`) is checked then; an error names the types: `in Main.largest[T=handle<Object>]: Object does not define operator <`.
* A generic method can't be overridden: it is not virtual, and can't be abstract or declared in an interface. Its type parameters can't reuse its class's names.
* Overloads: a method without type parameters is chosen over a generic one that also fits, as in Java, even when the generic one would take the argument exactly: `print(char c)` over `<T> print(T x)` for a char.

---

# 13. Example Generic Class

```text
struct Array<T><<int N>>
struct Array<T><<int N, int K>> {
    int occupied;

    array<T><<N>> data<<int N>>;
    array<T><<N, K>> data<<int N, int K>>;

    int getLength<<int N>>() {
        return N;
    }

    int getLength<<int N, int K>>() {
        return N * K;
    }

    float getOccFrac<<int N>>() {
        return (float)occupied / N;
    }

    float getOccFrac<<int N, int K>>() {
        return (float)occupied / (N * K);
    }

    int getOccupied() {
        return occupied;
    }

    T get<<int N>>(int x) {
        return data[x];
    }

    T get<<int N, int K>>(int x, int y) {
        return data[x][y];
    }
}
```

Here:

* `occupied` and `getOccupied()` are unguarded.
* `data`, `getLength`, `getOccFrac`, and `get` are guarded.
* `get` has no unguarded counterpart.
* `getLength` has only guarded variants.

---

# 14. Constructors and Destructors

Constructors use:

```text
public new<<shape>>(args)
```

Destructors use:

```text
delete<<shape>>()
```

The class name is not repeated. Only a `unique` has a destructor (§16): a `struct` is copied freely, and a `class` object is freed by the runtime at no set time.

Example:

```text
public new<<int N>>(int initial) {
    occupied = initial;
}
```

A constructor or destructor may also be unguarded:

```text
public new() {
    ...
}

delete() {
    ...
}
```

An unguarded constructor/destructor applies to every shape.

The syntax:

```text
new<<>>()
delete<<>>()
```

refers specifically to the bare, no-value-bound shape, following the same rule as the empty member guard (§9.1).

Constructors overload according to:

```text
(shape, argument types)
```

---

# 15. Constructor Coverage

Every shape must have a matching constructor.

An unguarded constructor covers every shape.

If no constructors are declared, the compiler may generate a default constructor.

Inline objects require a matching no-argument constructor.

If one does not exist, the declaration is an error.

---

# 16. Destruction and Copying

A value is destroyed when its variable goes out of scope, on every path out (falling off the end, `return`, `break`, `continue`, an exception), when the object or value holding it is destroyed, or when a new value is assigned over it. Destroying a value runs, in order:

1. its destructor's body, if it is a `unique` with one
2. the destruction of its fields, in reverse declaration order
3. the release of its memory

A class object is freed automatically once nothing refers to it (§26.1), and has no destructor: nothing runs at that moment, which the program can't predict. A class whose object holds something that must be released, such as an open file, gives it a `close()` method and implements `AutoCloseable`, and its users close it with try-with-resources (§41.4).

## 16.1 Copying, sharing and lending

What assignment and passing do depends on the kind of type (§2.1):

| Kind | `b = a` | Passing `a` to a function |
| ---- | ------- | ------------------------- |
| `struct` | copies `a` (handles inside it gain a reference) | the function gets its own copy |
| `unique` | an error, unless `a` is a fresh value (`new`, a function result, an operator result) | lent: the function works on the caller's object |
| `class`  | shares the object (a handle) | the function gets a handle to the same object |

Rules for `struct`, `unique` and `class`:

* A `struct` cannot have a destructor, since each copy would run it. Use `unique` instead.
* A `class` cannot have a destructor, since its objects are freed at no set time (§26.1). Use `close()` and try-with-resources.
* A `struct` cannot contain a `unique`, directly, in an array, or through a type argument such as `Box<File>`.
* A `class` cannot hold a `unique` that has a destructor (or holds one that does), directly, in a struct or fixed array, or through a type argument such as `Box<File>`, as the destructor would run whenever the object happens to be freed. A unique without one is fine, and so is `array<T>` (§21.3). A static field may hold one: it is destroyed after `main` returns (§26.1).
* A `unique` may be initialized or assigned from a fresh value. Assigning to one that already holds a value destroys the old value first.
* Returning a local `unique` moves it out to the caller; it is not destroyed at the return. A lent parameter cannot be returned or stored, because that would copy it.
* A lent `unique` stays owned by the caller: the function does not destroy it.

```text
unique File { ... delete() { ... } }

static void write(File f, int b) { ... }   // f is lent

File log = new File(3);
write(log, 42);           // lends log; no pointer needed
File copy = log;          // error: File is unique and can't be copied
log = new File(4);        // fine: the old File is destroyed first
```

To change a caller's `struct`, call a method on it (`p.moveRight(1)`; methods act on the object itself) or return the new value. `addr<T>` remains available for low-level code.

---

# 17. Access Control

Access modifiers are checked at compile time.

Supported modifiers:

```text
public
private
private<A, B>
```

`private` is the default.

`private` means only the current class may access the member.

```text
private<A, B>
```

allows the current class and the listed classes to access it.

The listed classes include all of their shapes.

Modifiers precede shape guards:

```text
public int getLength<<int N>>() {
    ...
}
```

There is no `protected` access modifier. To give subclasses access, list them: `private<Dog, Cat> int legs;`.

## 17.1 `static`

A member marked `static` belongs to the class rather than an instance and is called through the class name:

```text
struct Integer {
    public static string toString(int x) { ... }
}

Integer.toString(32);
```

Static methods and fields work the same way in a `struct`, a `unique` and a `class`. Static fields live in static data for the whole program; a static field of class type keeps its object alive. Static members are inherited: a subclass's name reaches its parents' static members, as in Java (`Square.make()` finds `Shape.make()`). A static member cannot use the type's type parameters or `<<...>>` values, since it belongs to no instance.

Modifiers may appear in any order before the member's type. Decorators come first:

```text
@overload(Operator.ADD) public static const ...
```

Access control is a compile-time restriction. Since `addr<T>` provides low-level memory access, it cannot be treated as a runtime security boundary.

## 17.2 `pure`

A `pure` method's result depends only on its arguments, so it can also run while compiling: wherever a compile-time value goes (§8), in `<<...>>`, an array's size or a `const` field.

```text
struct Bits {
    public static pure int needed(int n) {
        int bits = 1;
        while ((1 <<< bits) <= n) { bits++; }
        return bits;
    }
}

struct Fib {
    public static pure int of(int n) {
        if (n < 0) { throw new IllegalArgumentException("n must not be negative"); }
        int a = 0;
        int b = 1;
        for (int i = 0; i < n; i++) { int t = a + b; a = b; b = t; }
        return a;
    }
}

uint<<Bits.needed(1000)>> count;              // a uint<<10>>
array<int><<Fib.of(6)>> table;                // 8 elements
const int LIMIT = Fib.of(20);
struct Table<<int N>> { array<int><<Fib.of(N) + 1>> cells; }   // worked out for each N used
```

`pure` is a modifier, as `static` is, and a pure method must also be `static`. Its body keeps these rules, which the compiler checks:

* It reads no field but its arguments' (a struct argument's, or a class object's) and `const` fields: no static fields, so it can't print, read files or count calls.
* It changes no class object: a class object belongs to its caller too. Its own variables, and its arguments (copies, as values are), it may change.
* It calls only pure methods, and may `throw new` an exception: `throw new IllegalArgumentException("...")`.
* Arrays are the one kind of object it makes and uses: `new array<T>(n)`, `[1, 2, 3]`, indexing, and the array's own methods `length`, `isEmpty`, `copy`, `copyOf`, `slice`, `indexOf`, `contains`, `fill`, `swap`, `reverse` and `sort()` (not `toList()`, which makes a `List`, nor `sort(Comparator)`). An array it was given is its caller's, lent to it (§21.3), so it only reads it; to change one, it changes a copy, `xs.copy()`.
* It uses no addresses (`*x`, `&p`, `new<T>(n)`, `delete`).
* A lambda inside it keeps the same rules.

While the program runs, a pure method is an ordinary static method. While compiling, the compiler runs it exactly as the program would: numbers of every width wrap as they do at run time, and fixed point, floating point, bits, characters, strings, fixed arrays and `array<T>` work as they do (`sort()` orders elements as the library's does, NaN and all). An array literal can be an argument, `Stats.sum([1, 2, 3])`, and so can an array another pure call makes, `Stats.sum(Stats.squares(10))`; an array itself isn't a compile-time value. A call's result is a known value, like a literal, which must fit where it goes. Each call with the same arguments runs once; a pure method can't tell. A call on values not known yet, as `Fib.of(N)` in the generic class above, is worked out for each set of values the program uses.

A call that can't be worked out while compiling is an error at the call, saying why and where: it threw (`Fib.of(-1) can't be worked out while compiling: it threw IllegalArgumentException: n must not be negative (Fib.seal:5)`), divided by zero, indexed outside an array, recursed more than 20,000 calls deep, or ran for more than 50,000,000 steps. While compiling, structs and `try` don't run yet; a pure method using them still works while the program runs.

---

# 18. Fixed Memory Pool

The language provides a fixed linear memory pool.

Memory is **bit-addressed**.

The official address type is:

```text
addr<T>
```

An address is a 64-bit typed bit address.

There is no separate `address<T>` spelling.

---

# 19. Address Operations

The address operators are intentionally the reverse of C.

| Expression | Operand    | Result    | Category             |
| ---------- | ---------- | --------- | -------------------- |
| `&p`       | `addr<T>`  | `T`       | lvalue (dereference) |
| `*x`       | lvalue `T` | `addr<T>` | rvalue (address-of)  |

`&p` names the object stored at address `p`. It may be read, assigned, or used for member access:

```text
addr<int> p = *x;    // take the address of x
int v = &p;          // load through p
&p = 32;             // store through p
(&p).field = 32;     // store to a field through p
```

`*x` requires an lvalue operand and produces an rvalue. It cannot be assigned:

```text
*x = 32;             // error: address-of is not assignable
```

## 19.1 Nested dereference

Dereferences may be chained, but each inner dereference must be parenthesized:

```text
int x;
addr<int>       p  = *x;
addr<addr<int>> pp = *p;

&pp                  // addr<int> (p)
&(&pp)               // int (x)

int w = &&pp;        // error: `&&` is logical AND
int u = & &pp;       // error: nested dereference requires parentheses
```

The token `&&` is always logical AND. Consequently:

```text
a&&&p
```

lexes as `a && (&p)`.

---

# 20. Address Arithmetic

Pointer/address arithmetic operates in bits.

```text
p + 1
```

advances the address by exactly one bit.

To reach element `i` of an array of `T`:

```text
&(p + i * sizeof(T))
```

(`sizeof` is measured in bits.)

Objects do not move in the fixed pool.

An address reaches only the allocation it points into (what `new` made, a variable, a static field): loading or storing outside it traps (docs/JVM.md §4.4).

Deletion is explicit:

```text
delete p;
```

---

# 21. Fixed Object Layout

Structs and uniques contain no hidden allocation headers or length fields.

The compiler knows their complete size from their static type.

`sizeof` is therefore calculated recursively at compile time.

A class cannot contain itself inline:

```text
struct Node {
    Node next;       // invalid
}
```

but it may contain an address to itself:

```text
struct Node {
    addr<Node> next;
}
```

## 21.1 Arrays

Arrays have one name, `array`, in two shapes, as strings do (§39):

| Type | Length | Kind |
| ---- | ------ | ---- |
| `array<T><<N>>` | `N`, part of the type | a value: `N` elements inline, copied by `b = a`, like a struct |
| `array<T>` | chosen when it is made (§21.3) | a unique, on the heap |

There is no `T[N]` or `T[]` syntax; the compiler reports them with their new spelling (`int[5]` is `array<int><<5>>`, `int[]` is `array<int>`).

```text
array<int><<5>> data;                  // 5 ints, zeroed
array<double><<3, 4>> grid;            // 3 rows of 4 doubles
array<char><<2, 3, 4>> cube;           // any number of dimensions
array<int><<3>> xs = [1, 2, 3];
array<int><<2, 2>> m = [[1, 2], [3, 4]];
```

A fixed array with several lengths has a dimension for each: `array<T><<N, M>>` is `N` elements of `array<T><<M>>`, and is the same type as `array<array<T><<M>>><<N>>`. Indexing follows the nesting: `grid[r][c]` requires `r < 3` and `c < 4`, and `grid[r]` is a row (a copy, as values are). A parameter can bind every length: `static int trace(array<int><<int N, int M>> m)`. An index out of range throws an `IndexOutOfBoundsException` (one known when compiling is an error then). A length is less than 2 ** 31, as Java's arrays' are.

A fixed array has the run-time array's methods (`length`, `fill`, `indexOf`, `contains`, `swap`, `reverse`, `sort`, `sort(Comparator)`, `toList`, `toArray`, `slice`), working on it in place; they come from the library struct `FixedArray`, which has its layout. A parameter `array<T><<int N>>` takes a fixed array of any length, with `N` bound to it (§9.3):

```text
static int total(array<int><<int N>> xs) { int t = 0; for (int x : xs) { t += x; } return t; }
total([1, 2, 3])
```

A shape guard follows the name:

```text
array<T><<N>> data<<int N>>;
```

### Default construction of elements

An array declared without an initializer (a local, a field, a static field, or an element of another array) is default-constructed, following the same rule as a single value:

* struct and unique elements: every element is constructed with the element type's no-argument constructor, in order. If the element type has constructors but no no-argument one, the declaration is an error.
* class elements: every element is a null handle until assigned, as in Java.
* other elements (numbers, `bit`, addresses): zero.

Elements are destroyed in reverse order when the array is. Memory from `new<T>(n)` is the exception: it is raw, zeroed memory, and no constructors run (§24).

## 21.2 Array literals

`[a, b, c]` is an array value. Like a number or string literal, its type comes from where it is used: it fits any `array<T><<n>>` with exactly `n` elements, each of which fits `T`.

```text
array<int><<3>> a = [1, 2, 3];
array<uint<<8>>><<2>> b = [200, 255];        // each constant must fit: [1, 300] is an error
array<char><<3>> c = ['x', 'y', 'z'];
array<string><<2>> words = ["hi", "there"];
array<int><<2, 3>> grid = [[1, 2, 3], [4, 5, 6]];
array<Box><<2>> boxes = [new Box(1), null];
sum([4, 5, 6]);                     // a parameter of type int[3]
a = [a[2], a[1], a[0]];             // the new value is built before a changes
```

Where nothing decides the type (indexing a literal, overload resolution), it takes the type of its first element that isn't a constant, or else the constants' own default: `int` (`int<<64>>` if a value needs it), `double` if any has a fraction, `string<<longest>>` for strings. `[1, 2, 3]` is an `array<int><<3>>`, `[1, 2.5]` a `array<double><<2>>`, and `[x, 7]` with an `int<<8>>` `x` an `array<int<<8>>><<2>>`. Elements with no shared type, like `[1, "x"]`, are an error; declare the array's type instead.

Rules:

* A literal needs at least one element; `[]` is an error. A trailing comma is allowed (§4.2).
* The elements are stored in order. Struct elements are copied, class elements are handles (each adds a reference), and unique elements must be fresh values (`new`, a call), never a variable, since that would copy it (§16.1).
* Arrays of different sizes are different types, so `[1, 2]` doesn't fit an `array<int><<3>>`.

## 21.3 `array<T>`: arrays sized at run time

`array<T>` is the standard library's array whose length is chosen when it is made, as Java's arrays are:

```text
array<int> xs = new array<int>(n);          // n zeros
array<int> ys = [1, 2, 3];
array<string> words = line.split(' ').toArray();
xs[0] = 7;
xs[1] += xs[0];
for (int y : ys) { ... }
xs.length()
```

* Elements start as their type's default (0, `false`, null, `""`, a struct's no-argument constructor). An index outside `0` to `length() - 1` throws an `IndexOutOfBoundsException`.
* **An array is a unique** (§16.1). Passing it to a method lends it, so the method works on the caller's array: `fill(xs)` fills it, `sort(xs)` sorts it. Returning one moves it out. It can't be copied: `array<int> b = a;` is an error; `a.copy()` makes a second array. It is freed, releasing what its elements refer to, when it goes out of scope (or when the object holding it in a field is freed). It has no destructor of its own, so a class may hold one (§16.1).
* Its elements are copied in and out, so they can't be uniques: no `array<array<int>>` and no `List<array<int>>`. Fixed arrays are values, so `array<array<int><<3>>>` (rows of 3) is fine; so are `array<T><<N, K>>` grids, or one array indexed by `row * width + column`.
* Methods: `length`, `isEmpty`, `fill`, `copy`, `copyOf(n)` (the first `n` elements, padded with defaults, as Java's `Arrays.copyOf`), `slice(from, to)`, `toList`, `indexOf`, `contains`, `swap`, `reverse`, `sort()` (by `<`) and `sort(Comparator<T>)` (stable). `List.toArray()` and `string.toCharArray()` give arrays, and `new string(chars)` a string.
* An `array<T><<N>>` converts to an `array<T>` by copying, wherever one is wanted (an initializer, an argument).

`array<T>` is the language's own type, declared in `stdlib/array.seal`: on the JVM it is a Java array, or a block of storage for an array of structs (docs/JVM.md). The library's collections (`List`, `Queue`, `IntMap`, `HashMap`, `StringBuilder`) keep their elements in `array<T>`s, so they use no pointers and free nothing themselves.

---

# 22. Alignment

Values must begin at bit positions divisible by their required alignment.

The language uses:

* LSB-first bit ordering
* little-endian byte ordering

Alignment rules:

* 8-, 16-, 32- and 64-bit values (`int<<8>>`, `int`, `float`, `double`, `addr`, `handle`, `fixed<<4, 4>>`, ...) align to their own size.
* Values of every other width (`bit`, `uint<<5>>`, `uint<<6>>`, `int<<24>>`, ...) can start at any bit.
* An array aligns like its element type. A class aligns to its most-aligned field, and its size is rounded up to that alignment.

So odd-sized fields pack with no padding:

```text
struct Rgb565 { uint<<5>> b; uint<<6>> g; uint<<5>> r; }   // 16 bits: b at 0, g at 5, r at 11
struct Mixed { int<<8>> a; int b; int<<8>> c; }           // 96 bits: b is padded to bit 32
```

`sizeof` is therefore not always the sum of the field sizes, because standard-width fields may be preceded by padding.

Loading or storing an 8-, 16-, 32- or 64-bit value at a misaligned address traps.

Allocations with `new` are rounded up to whole 64-bit words. Class and array locals are placed on 64-bit boundaries in the frame, so reading one through a differently typed address (for example, a 16-bit class through `addr<uint<<16>>>`) does not trap.

---

# 23. Null Address

The proposed memory model reserves byte `0` as the null address.

Explicit casts are proposed between:

```text
addr<A>
addr<B>
```

as well as an untyped address form.

---

# 24. Runtime-Sized Allocation

Runtime-sized memory may be allocated directly:

```text
new<int>(n)
```

This produces a bare:

```text
addr<int>
```

rather than a compile-time-sized class instance.

---

# 25. Class Objects

Class objects live in a separate, resizable memory pool and are reached through handles, never through `addr`:

```text
class Foo { ... }

Foo f = new Foo();      // f is a handle<Foo>
```

---

# 26. Handles

A handle (`handle<T>`, or simply `T` for a class `T`) is opaque.

Handles cannot:

* perform arithmetic
* be cast to integers
* be dereferenced with `&`
* contain interior pointers

This allows the runtime to move and compact class objects.

A handle refers to a live object or is null: nothing frees an object while a handle to it remains.

Class objects carry a hidden size header.

## 26.1 Freeing class objects

Class objects are freed automatically, as in Java, once nothing refers to them. When that happens is up to the runtime, so a class has no destructor (§16) and nothing can watch it happen; there is no `delete` for a handle either. To let go of an object, drop the references to it: set a variable to `null`, or let it go out of scope. Something that must be released at a known time, such as an open file, is closed with `close()`, best through try-with-resources (§41.4).

```text
{
    Animal a = new Dog();
    Animal b = a;             // the same Dog
}                             // nothing refers to it any more: it will be freed
```

Static fields keep what they hold until the program ends. A `unique` in a static field has its destructor run after `main` returns (and after the object an instance `main()` ran on is let go, §56.1), the program's own classes first, then the library's. A program that ends through `System.exit`, an uncaught exception or a trap skips this, as Java skips `finally` blocks on exit.

The JVM's garbage collector frees an object once nothing refers to it, objects that refer to each other in a cycle too (docs/JVM.md §6).

---

# 27. Class Object Access

Fields and methods of class objects are used through handles, as in Java: `f.count`, `f.run()`.

A class object is a JVM object, and a handle a reference to it (docs/JVM.md §5).

---

# 28. Structs and Classes Together

Classes may contain:

* struct and unique values
* fields of class type (handles)
* `addr<T>` fields

Structs and uniques cannot contain class objects inline; a field of class type is always a handle:

```text
struct Holder {
    Doc doc;            // a handle<Doc>
    array<Doc><<4>> docs;        // four handles
    List<Doc> more;     // a list of handles
}
```

Classes may themselves be instanced generics:

```text
Buffer<int><<16>> b = new Buffer();     // Buffer is a class
```

---

# 29. Resizable Tails

The syntax for a resizable tail remains open. (`array<T> items;` was a candidate; it now means an `array<T>` field, §21.3.)

---

# 30. Inheritance

Inheritance is available only to classes.

Structs do not support inheritance.

Structs instead use composition, while generic instancing provides compile-time polymorphism.

Inheritance is single-parent only.

Multiple inheritance is not supported; a class can instead implement any number of interfaces (§30.2).

Abstract classes, abstract methods and interfaces are likewise only for classes. A struct or unique can't be `abstract`, can't have abstract methods and can't `implement` anything.

## 30.1 Abstract classes

`abstract class` declares a class that can't be created with `new`; it exists to be extended. It can have everything a class has (fields, constructors, static members, ordinary methods) plus abstract methods: public methods with no body, ending in `;`.

```text
abstract class Shape {
    public abstract int area();
    public string describe() { ... area() ... }   // calls the subclass's area
}

class Square extends(Shape) {
    int side;
    public int area() { return side * side; }
}
```

Rules:

* `new Shape()` is an error. A handle of type `Shape` holds an object of some concrete subclass.
* A class that isn't abstract must implement every abstract method it inherits, with the same parameter types and return type, or it is an error naming the missing method.
* An abstract method must be public, can't be static, and can't have value binders (`<<int K>>` in a parameter type).
* Only an abstract class can declare abstract methods. A method without `abstract` needs a body.
* An abstract class can also have abstract and default fields, which the classes below it hold (§30.4).
* `super.area()` is an error when the parent's `area` is abstract.
* Every header of an abstract class says `abstract`.

## 30.2 Interfaces

An interface is a list of method signatures, which classes promise to implement, and of fields they promise to hold (§30.4):

```text
interface Describable {
    string describe();
}

interface Comparable<T> {
    int compareTo(T other);
}

interface Thing extends(Describable, Named) { // an interface extends interfaces
    int id();
}

class Circle extends(Shape) implements(Describable, Comparable<Circle>) {
    ...
}
```

* An interface contains method signatures, `default` methods with the body its classes get, and abstract and `default` fields (§30.4); no constructors, destructors, static members or decorators. Its methods are public, and the methods implementing them must be public too.
* An interface can have type parameters, but no value parameters or shapes.
* A class lists its interfaces after `extends`, as `implements(A, B)`. It implements them for every header that lists them, and its subclasses implement them too. A class that isn't abstract must implement all their methods, including those of interfaces they extend. An abstract class may leave them to its subclasses.
* An interface name used as a type is a handle, like a class name: `Describable d = new Circle(1);`. Calling a method through it calls the object's own method (dispatch uses the same vtable slots as class methods: a slot is a method name and parameter types).
* Upcasts to an interface are implicit. Casts from an interface to a class, from a class to an interface it doesn't statically implement, and between interfaces are written explicitly and checked at run time; a failed cast traps, e.g. `cannot cast Note to Shape`.
* Every interface handle is an `Object`. `==` compares interface handles by identity.
* `new` on an interface is an error, as is `extends(Interface)` on a class (use `implements`) and `implements(Class)`.

## 30.3 Lambdas

A lambda is a function written where a value is expected. Its type is a functional interface: an interface with exactly one method without a body, which the lambda implements. Its default methods (§30.4) come with it, and an interface with fields can't be one, since a lambda holds none.

```text
Function<int, int> twice = x -> x * 2;
BiFunction<int, int, int> add = (a, b) -> a + b;
Comparator<int> backwards = (int a, int b) -> {
    if (a > b) { return -1; }
    return 1;
};
Runnable hello = () -> System.stdout.println("hello");

twice.apply(21);                       // 42
names.sort((a, b) -> a.length() - b.length());
names.forEach(n -> System.stdout.println(n));
```

* The forms are `x -> expr`, `(a, b) -> expr`, `() -> expr`, and any of them with a block `{ ... }` instead of an expression. Parameter types come from the interface, or may be written out, `(int a, int b) -> ...`, and must then match.
* The interface comes from context: a declared type, a parameter (the call is resolved first, then the lambda is checked against the parameter it lands on), a return type. A lambda with no such context is an error.
* A lambda with an expression body returns its value (or just evaluates it, if the method returns `void`). A block body returns with `return`, and must return on every path unless the method is `void`.
* **Captures.** A lambda can use the local variables and parameters around it. It copies them when it is created, as a struct copy would (a handle is copied, so the object is shared). It can't assign them, which would change only its copy; changes made outside after it was created aren't seen. It can't use a unique, which it would have to copy.
* In a class method, a lambda can use the object's fields and methods; it keeps a reference to the object. In a struct or unique method it can't, since there is no shared object to keep.
* At run time a lambda is a class object, freed like any other, whose class implements the interface. `instanceof` and casts work on it. Each evaluation of a lambda expression creates a new object.

The standard library defines the common functional interfaces:

| Interface | Method |
| --------- | ------ |
| `Runnable` | `void run()` |
| `Supplier<T>` | `T get()` |
| `Consumer<T>` | `void accept(T x)` |
| `BiConsumer<A, B>` | `void accept(A a, B b)` |
| `Predicate<T>` | `bit test(T x)` |
| `Function<T, R>` | `R apply(T x)` |
| `BiFunction<A, B, R>` | `R apply(A a, B b)` |
| `Comparator<T>` | `int compare(T a, T b)` |

and `List<T>` takes them in `sort(Comparator<T>)` (stable), `forEach(Consumer<T>)` and `removeIf(Predicate<T>)`.

## 30.4 Abstract and default fields, and default methods

An interface or abstract class can name fields that the classes below it hold, as it names methods they implement. An **abstract field** has no value: every class that isn't abstract must hold it and give it one. A **default field** has a value, which a class gets unless it declares the field itself. An interface's **default methods** have a body its classes get, unless they implement the method themselves; like an abstract class's methods, they can use the fields:

```text
interface Named {
    string name;                         // every class implementing Named holds a name
    default string greeting = "Hello";   // and a greeting, "Hello" unless it says otherwise

    default string greet() { return greeting + ", " + name + "!"; }
    default void rename(string to) { name = to; }
}

abstract class Shape implements(Named) {
    public abstract int sides;            // each kind of shape gives its own
    public default string color = "red";

    public string describe() { return color + " " + name + " with " + sides + " sides"; }
}

class Square extends(Shape) {
    public int sides = 4;                 // fills in Shape's sides
    public string name;                   // fills in Named's name, given in the constructor
    public new(string n) { name = n; }
}                                         // and gets greeting = "Hello" and color = "red"

Named n = new Square("box");
n.greet()                                 // "Hello, box!"
n.name = "crate";                         // the Square's own name changes
```

* In an interface, a field without `default` is abstract; in an abstract class it is written `abstract`. Only interfaces and abstract classes have them. They can't be static or const.
* **Filling one in.** A class holds a field by declaring it, with the same type: `public int sides = 4;`. A class filling in an abstract or default field gives it a value, where it declares it or by assigning it in every constructor. A class that isn't abstract and doesn't hold an abstract field, and no class above it does, is an error naming the field; for a default one it gets the field, with the default value. Its subclasses share the field it holds, as any inherited field.
* **Reaching it.** Through the class that holds it (`square.sides`), a field is the class's own. Through the interface or abstract class (`shape.sides`, `n.name`, or `name` in a default method), it is reached through accessor methods the compiler writes, `get name()` and `set name(value)`, which every holding class implements: so it is the object's own field, whichever class that is. `x.f = v` assigns it, and `x.f += v;` and `x.f++;` work as statements (read, change, write back, with `x` evaluated once).
* The field keeps its access: `public abstract int sides;` can be read from outside, `abstract int sides;` only by the class's own code, as other fields.
* A default field's value is computed in the class that gets it, as if written there, and its type can't use the declaring type's type parameters. A field reached through a getter can't be a unique, which the getter would copy.
* A default method is compiled once, for the interface: its `this` is the object, whatever its class, and calling another of its methods calls the object's.

---

# 31. Root Object

All classes implicitly inherit from:

```text
Object
```

`Object` provides:

* class identity: `==` compares handles unless a class defines its own `==`
* `hash()`, a `uint<<64>>` hash code from the object's identity, for `HashMap` keys (§42.4). A class that defines `==` should define `hash()` to match, as in Java: objects that are `==` must have equal hashes.

If no matching explicit parent exists, a shape extends `Object`.

This can also be written explicitly:

```text
extends<<3>>(Object)
```

to specify a shape-specific relationship to `Object`.

---

# 32. Shape-Dependent Inheritance

The `extends` syntax always uses parentheses:

```text
extends(Parent)
```

A shape guard may precede it:

```text
extends<<3>>(Parent)
```

Different shapes may have different parents.

Example:

```text
class Dog extends(Animal)

class Dog<<int size>> extends(Pet<<size>>)

class Dog<<int size, int weight>>
```

The third shape implicitly extends `Object`.

Another example:

```text
class Shape<<int kind>>
    extends(Base)
    extends<<0>>(Circle)
    extends<<1>>(Square)
```

---

# 33. Parent Resolution

An unguarded `extends` clause is the default parent.

A matching guarded clause overrides the default.

Duplicate guards are errors.

Two unguarded parent declarations are errors.

Proposed precedence:

1. literal guard
2. binding guard
3. unguarded default

At most one binding guard should match a given header.

---

# 34. Inheritance and Shape Coverage

Inherited members participate in the shape system.

Inherited members therefore count as guarded for purposes of the coverage rules.

---

# 35. Subtyping

Proposed inheritance semantics:

* subtyping is per shape
* upcasts are free
* an upcast retains the same handle
* downcasts are runtime checked
* runtime class identity is used for downcast validation

Methods are virtual by default.

```text
super()
```

refers to the single parent.

## 35.1 `instanceof`

`x instanceof T` is true when the handle `x` is not null and its object's class is `T`, extends it, or implements it (`T` a class or interface). It never traps, so it is the safe way to ask before a cast:

```text
if (d instanceof Shape) { Shape s = (Shape)d; ... }
```

Followed by a name, it also gives the object that type where the test is known to have passed, as in Java:

```text
if (d instanceof Rect r && r.area() > 1) { ... r ... }   // r: a Rect handle
while (next instanceof Node n) { ... n ... }
```

* The name is visible on the right of `&&`, in the `if` branch (not `else`), and in a `while` or `for` body. It is not visible after the statement or across `||`.
* It holds its own reference to the object, released when it goes out of scope.
* The operand must be a class or interface handle (or `null`), `T` a class or interface, and a test that can never pass, between unrelated classes, is an error.
* `instanceof` binds like a comparison and can't be chained with one: `(x instanceof A) == b`.

---

# 36. Primitive Types

The primitive types are:

| Type          | Meaning                       |
| ------------- | ----------------------------- |
| `int`         | signed 32-bit integer         |
| `int<<N>>`    | signed N-bit integer          |
| `uint`        | unsigned 32-bit integer       |
| `uint<<N>>`   | unsigned N-bit integer        |
| `size`        | a width: 0 to 64 (§37.2)      |
| `bit`         | one-bit Boolean value         |
| `float`       | IEEE 32-bit floating point    |
| `double`      | IEEE 64-bit floating point    |
| `fixed<<N, M>>`  | signed fixed point: N integer bits (including the sign bit), M fraction bits (§37.1) |
| `ufixed<<N, M>>` | unsigned fixed point (§37.1) |
| `addr<T>`     | 64-bit typed bit address      |
| `handle<T>`   | reference to a class object; a class type `T` written alone means `handle<T>` |

Integer widths may not exceed 64 bits. Widths are `size`s (§37.2).

`string` is not a primitive. It is a fixed standard-library class (§39).

---

# 37. Integer Types

`int` is equivalent to:

```text
int<<32>>
```

and `uint` is equivalent to:

```text
uint<<32>>
```

For example:

```text
int<<8>>
uint<<17>>
int<<64>>
```

are valid widths.

Widths greater than 64 bits are represented using user-defined classes.

For example:

```text
struct BigUInt<<int BITS>> {
    ...
}
```

Because each instantiation is concrete, large fixed-width integer types can be implemented without requiring special compiler support for arbitrary-width primitive integers.

## 37.2 `size`

`size` is the type of widths: a number of bits, from 0 to 64. `int<<W>>`, `uint<<W>>`, `fixed<<N, M>>` and `ufixed<<N, M>>` take sizes, and a binder of a width is one:

```text
static int<<W>> twice(int<<size W>> x) { return x + x; }
static string show(ufixed<<size N, size M>> x) { ... }
static pure size needed(int<<64>> n) { ... }      // uint<<needed(1000)>>
```

* A binder of a width that isn't a size is an error: `int<<int W>>` is written `int<<size W>>`. Other values in `<<...>>` (an array's length, a string's capacity, a class's own) keep the types they are declared with, and an `int` value goes where a width does as a constant does: `int<<N > 32 ? 64 : 32>>`.
* A constant from 0 to 64 is a size: `int<<8>>`, `size bits = 12;`. One outside is an error (`size s = 65;`). A type takes the widths it can have: an integer's is 1 to 64 (`int<<0>>` is an error), and fixed point's N + M too (§37.1), so `ufixed<<0, 8>>` and `fixed<<16, 0>>` are valid.
* `size` is a primitive, as `int` is: variables, fields, parameters, results, arrays and `const` fields may be sizes, and `pure` methods (§17.2) take and give them, so a width can be worked out while compiling. `size` is the type's name where a type is written; elsewhere it is a name like any other (`list.size()`), and a program's own type can't take it.
* Arithmetic on sizes gives an `int`, as Java's on `char` does: `s * 2` is an `int`. A size widens to every type that holds 0 to 64: `int`, `uint<<7>>`, `int<<64>>`, `double`, and the rest.
* Nothing else becomes a size by itself, except a constant that is one. A cast makes one, `(size)n`, and stops the program if the value isn't 0 to 64; a cast of a constant that isn't is an error. A compound assignment, `++` and `--` on a size convert back the same way: `s += 1` on a size holding 64 stops the program, where other integers wrap.
* Where a method takes `int<<size W>>`, a size goes as an `int` (`W = 32`), as a char does; `print`, `+` on strings and `Integer.toString` take sizes as they are.
* A size is stored in 7 bits, as `uint<<7>>` is (§21).

---

## 37.1 Fixed-point types

`fixed<<N, M>>` and `ufixed<<N, M>>` are numbers with N bits before the binary point and M bits after it. They are stored as an (N + M)-bit integer scaled by 2^M:

```text
fixed<<8, 8>> x = 1.5;     // stored as the 16-bit integer 384 (1.5 × 256)
ufixed<<0, 16>> f = 0.25;  // a pure fraction in [0, 1)
```

Unlike `float`, the resolution is the same everywhere (2^-M), and arithmetic is exact within it. Fixed-point types use integer instructions only.

### Formats

* For `fixed`, N includes the sign bit, as in `int<<N>>`, so N ≥ 1. `fixed<<8, 8>>` covers −128 to just under 128 in steps of 1/256.
* For `ufixed`, N ≥ 0. `ufixed<<0, 8>>` covers 0 to just under 1.
* M ≥ 0, and N + M is between 1 and 64.
* N and M may be compile-time values, e.g. `fixed<<I, F>>` inside `class Q<<int I, int F>>`. Invalid widths are reported when the type is instantiated.
* Fixed-point values cannot themselves be compile-time values (§8).
* Layout follows integers of the same width (§22): `fixed<<4, 4>>` is 8 bits with 8-bit alignment.

### Arithmetic

* `+`, `-` and unary `-` are exact, then wrap to the format on overflow, like integers (§41).
* `*` and `/` take two values of the same format and return that format. The exact result (from a 128-bit intermediate) is rounded to the nearest representable value, with ties rounded up (toward +∞), then wrapped.
* For a product that can neither round nor overflow, use `Fixed.mulExact` from the standard library (§42), which widens the result: `fixed<<N, M>> × fixed<<P, Q>> → fixed<<N + P, M + Q>>`. It also accepts two different formats without casts. `*` does not widen by default because wide formats would exceed 64 bits, chained products would grow quickly, generic code relies on `T * T` being a `T`, and integers don't widen either.
* `%` gives the remainder of the scaled values, with the sign of the left operand.
* Comparisons work as for numbers.
* Bitwise operators, shifts, bit indexing and slicing are not defined.
* Operands of different formats meet in the one the other widens to (§41.6): a `fixed<<8, 8>>` plus a `fixed<<16, 16>>` is a `fixed<<16, 16>>`, and an `int<<8>>` widens into it too. A format that would lose integer or fraction bits needs a cast, as does a fixed-point value going to an integer. Constants adapt: `a * 2 + 0.25` is valid for any fixed-point `a`.

### Constants

An integer or floating-point constant converts to a fixed-point type by rounding to the nearest representable value (ties up). It must then be in range, or it is an error:

```text
fixed<<8, 8>> a = 0.1;     // stored as 26/256 = 0.1015625
fixed<<8, 8>> b = 200.0;   // error: 200.0 does not fit in fixed<<8, 8>>
```

Floating-point literals convert from their exact decimal digits, not through a `double`, so formats finer than a double, such as `fixed<<1, 63>>`, can be written exactly.

### Casts

| From → to              | Rule |
| ---------------------- | ---- |
| fixed → fixed          | rescale, rounding to nearest (ties up), then wrap |
| integer → fixed        | exact scale, then wrap |
| fixed → integer        | truncate toward zero, then wrap (like `float` → integer) |
| fixed → float / double | convert through `double` |
| float / double → fixed | round to nearest (ties up), then wrap |

### Printing

`System.stdout.print` prints the exact decimal value, with at least one fraction digit: `fixed<<8, 8>>` holding 0.1 prints `0.1015625`. Every fixed-point value has a finite decimal expansion with at most M fraction digits, so nothing is rounded.

---

# 38. `bit`

`bit` is a distinct primitive type.

It is not equivalent to:

```text
uint<<1>>
```

In particular, Boolean conditions require an actual `bit`.

```text
if (condition) {
    ...
}
```

requires `condition` to have type `bit`.

The `bit` literals are `true` and `false`.

Logical operators on `bit`:

| Operator | Meaning                  |
| -------- | ------------------------ |
| `!a`     | not                      |
| `a && b` | and (short-circuiting)   |
| `a \|\| b` | or (short-circuiting)    |

## 38.1 Enums

An enum is a type with a fixed list of named values, as in Java:

```text
enum Color { RED, GREEN, BLUE }

Color c = Color.GREEN;
c.name()                 // "GREEN"
c.ordinal()              // 1: its position in the list
Color.values()           // a Color[] (array<Color>): RED, GREEN, BLUE
Color.valueOf("BLUE")    // Color.BLUE; IllegalArgumentException for a name that isn't a constant
c == Color.GREEN         // compares ordinals; <, <=, >, >= too
```

Like Java's, an enum can have fields, constructors and methods. The constants come first, with their constructor arguments, then `;` and the members:

```text
enum Planet {
    MERCURY(3, 24),
    JUPITER(18980, 699);

    int mass;
    int radius;
    new(int m, int r) { mass = m; radius = r; }
    public bit isBig() { return mass > 100; }
}
```

* An enum is a struct: a value that is copied, holding its ordinal and its fields. It works anywhere a struct does (locals, fields, arrays, `List<Color>`, parameters).
* Its constants are static fields, created when the program starts. Only they can call its constructors.
* A variable declared without a value holds the first constant's ordinal (`Color c;` is `RED`), with fields zero; assign a constant to get its fields.
* An enum has no type parameters, shapes, parents or interfaces, and its constants can't be named `ordinal`, `name`, `values` or `valueOf`.
* `switch` on an enum may name constants bare: `case RED ->` (§41.3).
* An enum casts to `string` as its constant's name, so `"color " + c` and `println(c)` show `GREEN` (§46). An enum can declare its own cast to string instead.

---

# 39. Characters and Strings

`char` is a character: a 16-bit unsigned UTF-16 code unit. It is an integer type of its own, kept apart from `uint<<16>>`, so code can tell a character from a number: `System.stdout.print(c)` prints the character, and `"x" + c` appends it. Strings hold chars: `charAt` returns one, `toCharArray` gives an `array<char><<N>>` from a `string<<N>>` and an `array<char>` from a bare string, `new string(chars)` makes a string from an `array<char>`, a `StringBuilder` appends them, and the `Char` library (`isDigit`, `toUpper`, ...) works on them.

A character literal, in single quotes, is a char constant. Like any integer constant it also fits other integer types that can hold its code (`int code = 'A';`), but where nothing decides, it is a char.

```text
char c = s.charAt(i);
if (c >= 'a' && c <= 'z') { ... }        // comparisons stay chars
int offset = c - 'a';                    // arithmetic gives an int, as in Java
char next = (char)(c + 1);               // and a cast makes a char again
"x" + c                                  // "xq"
switch (c) { case 'a', 'b' -> ... }
```

* Arithmetic and bitwise operators on chars give an `int`, as in Java; a char also widens to an `int` next to one.
* A char is an `int` wherever one is wanted, as in Java: `int code = c;`, an `int` argument or return value, an index (`counts[c]++`). Where overloads take either, a char argument picks the `char` one, so `print(c)` prints the character.
* A char also widens to the other types that hold it, `int<<64>>`, `uint`, `float` and `double`, but not to `uint<<16>>` (§41.6). Going the other way narrows, implicitly where a value is stored or passed (`char c = n;`, `uint<<16>> u = c;`) or with a cast: `(char)n`.
* `c++` and `c--` step to the next or previous character and keep it a `char` (§41.5): `for (char c = 'a'; c <= 'z'; c++)`.
* An integer constant that fits converts to a char: `char c = 65;`.

A character literal holds exactly one character or escape. The escapes are those of strings: `\n`, `\t`, `\r`, `\0`, `\\`, `\'`, `\"`, `\s` (a space) and `\uXXXX` (four hex digits). `''` and `'ab'` are errors.

### String literals

A string literal adapts to where it is used, like an integer constant: it fits a bare `string`, or a `string<<N>>` with `N` at least its length. Where nothing decides, it is a `string<<length>>`, just as `1` is an `int`:

```text
"hello".length()          // 5; the receiver is a string<<5>>
string<<4>> ab = "ab" + "c" + "d";
show("hello");            // prefers show(string<<5>>) over show(string)
"abc" == s                // string<<3>>'s == with s
```

Literals joined with `+` are joined at compile time and stay a literal.

### Text blocks

A text block is a string literal over several lines, as in Java 15: three quotes, a line break, the text, and three closing quotes:

```text
string page = """
    <html>
        <body>"no escapes needed for quotes"</body>
    </html>
    """;
// "<html>\n    <body>\"no escapes needed for quotes\"</body>\n</html>\n"
```

* The indentation all its lines share is taken off, counting the closing quotes' line, so the text can be indented with the code around it; lines keep any indentation beyond that.
* Spaces at the ends of lines are dropped. Closing quotes on a line of their own end the text with a line break; on the last line of text, they end it without one.
* Escapes work as in other strings, and two more: `\s` is a space that is kept at a line's end, and `\` at the end of a line joins it to the next. `\"""` puts three quotes in the text.
* It is a string literal like any other: a `string<<length>>` where nothing decides, joined at compile time with other literals, usable as a `string.format` format.

### Joining and converting

A `string<<N>>` converts to a bare `string` wherever one is wanted: in an initializer or assignment, as an argument, as a return value. (`string` declares this as an `@implicit` constructor, §52.) The other direction needs no conversion: a bare string's length isn't known at compile time, so it never fits a `string<<N>>` implicitly.

`+` joins strings and numbers, as in Java:

| Left | Right | Result |
| ---- | ----- | ------ |
| `string<<N>>` | `string<<K>>` | `string<<N + K>>` (no allocation) |
| any string | bare `string` | `string` |
| bare `string` | any string | `string` |
| any string | a number (any integer width, `float`, `double`, `fixed`, `ufixed`) or `bit` | `string`, with the number's text |

```text
"n = " + n                  // "n = 42"
"pi ~ " + 3.14159           // doubles print as in Java: 0.1, 1.0E10
"hello, " + name + "!"
s += 3;                     // stores s + 3 in s: string's own += (§46)
```

A number or `bit` on the left works too, as in Java: `n + " apples"` means `"" + n + " apples"`, and `1 + 2 + "!"` is `"3!"` (left to right). A `char` joins as the character itself: `"x" + c`.

Any other value joins as its type's cast to string (§46), on either side and with `+=`: `"p = " + point` is `"p = " + (string)point`. That cast is what Java's `toString()` is: a type gives itself one with `@overload(Operator.CAST) public string text() { ... }` (any name), and an enum has one already, giving its constant's name. A value whose type has none can't be joined to a string; the error says how to give it one. `print`, `println` (§42.2) and `%s` (§42.6) show values the same way.

`Double.toString`, `Float.toString` and `Fixed.toString` give a number's text on its own, alongside `Integer.toString`.

`string` is an ordinary struct in the standard library (`stdlib/string.seal`) with two shapes:

| Type          | Storage                                                    |
| ------------- | ---------------------------------------------------------- |
| `string<<N>>` | inline, fixed capacity of `N` characters; never allocates  |
| `string`      | bare shape; any number of characters, in a buffer on the heap |

A bare `string` is a `java.lang.String`, and a `string<<N>>` keeps its length and characters inline (docs/JVM.md §8.2).

### Strings are immutable

Strings never change, as in Java. `string` is a value, so `b = a` gives `b` the same text; no method changes either, so they stay the same until one of the variables is given a new string:

```text
string a = "hello";
string b = a;
b += "!";                   // a new string in b; a is still "hello"
string loud = a.toUpper();  // a new string: "HELLO"
```

* Methods that make other text return a new string: `substring`, `trim`, `toUpper`, `toLower`, `replace(old, new)`, `repeat(n)`, `+`. For a `string<<N>>`, `toUpper` and `toLower` give a `string<<N>>`.
* `s += x` stores `s + x` in `s`. Building text a piece at a time is a `StringBuilder`'s job, as in Java (§42).
* A string can be made from characters: `new string(chars)` from an `array<char>`, and `new string(chars, offset, count)` from part of one.

Copies of a bare string share its characters, as Java's strings do.

Characters are 16-bit (UCS-2 style). The character width must be fixed.

---

# 40. Bit Operations

Integers support both arithmetic and bitwise operations.

Bit indexing:

```text
x[3]
```

Bit slicing:

```text
x[4..8]
```

are supported.

The exact indexing convention and slice endpoint semantics should be specified by the language reference.

---

# 41. Constants and Numeric Semantics

Proposed integer semantics include wrapping overflow.

A number converts to any other number type without a cast: it widens to a type that holds it, as in Java, and narrows where it is stored or passed, as in C (§41.6).

Arithmetic keeps the operands' type: `int<<8>> * int<<8>>` is an `int<<8>>` and wraps. Operands of different types meet in the wider one (§41.6).

`a ** b` is a power, as `*` is a product: its operands meet as `*`'s do, and on integers it wraps as repeated `*` would (`(int<<8>>)3 ** 5` is -13). A negative exponent gives `1 / a ** -b` in integer division: 0, except 1 for a base of 1, ±1 for -1, and division by zero for 0. On `float` and `double` it is `StrictMath.pow`, so the result is the same bits on every backend and while compiling (`2.0 ** 0.5`, `x ** -1`). It isn't defined on fixed point; convert to `double`. Between constants it is exact: `2 ** 64` is 18446744073709551616, and a constant power may have up to 65,536 bits. For a product that cannot overflow, `Integer.mulExact` (§42) returns an integer wide enough to hold it, `int<<N + P>>`, and accepts operands of different widths and signedness without casts. `Fixed.mulExact` does the same for fixed-point values (§37.1).

Integer literals must fit their target type.

For floating-point compile-time values:

* IEEE semantics are intended
* NaN generic parameters are rejected
* identity is proposed to use bit representation

## 41.1 Casts

Casts use C/Java syntax:

```text
(float)occupied
(addr<int>)p
```

A parenthesized primitive type is always a cast. A parenthesized class name is a cast only when followed by something that can start an operand but cannot continue an expression (a name, literal, `(`, `!`, `~`, `new`, `sizeof`, `alignof`, `super`). Therefore:

```text
(Foo)x      // cast
(a) - b     // subtraction
(a) & b     // bitwise and
```

## 41.2 Declarations vs expressions

A statement beginning with a class name, such as `Foo<T> x = ...;` or `a < b;`, is read as a declaration when it has the form `Type name` followed by `=` or `;`. Otherwise it is an expression.

## 41.3 Statements

The statements are Java's: blocks, declarations, expression statements, `if`/`else`, `while`, `for`, `return`, `break`, `continue`, `assert` (§41.4), plus `delete` (§24) and the ones below. A local may shadow one in an enclosing block.

### Labeled loops

As in Java, a loop can have a label, which `break` and `continue` name to leave or go on with that loop from inside loops nested in it:

```text
outer:
for (int r = 0; r < rows; r++) {
    for (int c = 0; c < cols; c++) {
        if (grid[r][c] == target) { break outer; }     // leaves both loops
        if (grid[r][c] < 0) { continue outer; }        // the next r
    }
}
```

* A label goes on a `for`, for-each or `while` loop, and a nested loop can't reuse a label of a loop around it. `break outer;` from inside a `switch` leaves the loop too.
* `finally` blocks run and objects in the scopes being left are destroyed on the way out, as for any `break` or `continue` (§41.4, §16).
* A lambda can't name the loops around it: its body is a method of its own.
* `while (true)` with a `break outer;` somewhere inside it can end, so a method that ends with one needs a return after it.

### For-each

`for (T x : items) body` runs `body` once per element, with `x` a new variable each time (copied from the element, as an initializer would). `items` is evaluated once and can be:

| `items` | Elements |
| ------- | -------- |
| an array `array<E><<N>>`, including a literal (`for (int x : [1, 2, 3])`, whose elements take `x`'s type) | `items[0]` ... `items[N - 1]` |
| a string | its characters, as `char` |
| a class implementing `Iterable<E>` | `iterator()`, then `next()` while `hasNext()` |
| any other object with `size()` and `get(int)`, such as `List<E>` | `get(0)` ... `get(size() - 1)`, with no iterator object |

```text
for (int x : xs) { sum += x; }
for (char c : name) { ... }
for (string n : names) { if (n == "bob") { continue; } ... }
for (array<int><<3>> row : grid) { for (int v : row) { ... } }
```

* `break` and `continue` work as in any loop, labeled ones too.
* `x`'s type is written out and must accept the element: `for (bit b : ints)` is an error, and so is a loop over an array of uniques, which would copy them.
* `Iterable<T>` and `Iterator<T>` are standard interfaces:

```text
interface Iterable<T> { Iterator<T> iterator(); }
interface Iterator<T> { bit hasNext(); T next(); }
```

* The compiler rewrites the loop into an ordinary `for` or `while` loop, so it costs the same as writing that by hand.

### Switch

`switch` picks a case by value, in either of Java's two styles, but not both in one switch:

```text
switch (month) {                      // `:` cases fall through until break
    case 2:
        days = 28;
        break;
    case 4, 6, 9, 11:
        days = 30;
        break;
    default:
        days = 31;
}

switch (c) {                          // `->` cases run one statement or block, never falling through
    case RED -> warm++;
    case GREEN, BLUE -> { cool++; }
    default -> { }
}
```

* The subject is evaluated once. It can be an integer of any width (characters included), a string, or an enum.
* Case labels are constants: numbers, character literals, string literals, or enum constants (`Color.RED`, or bare `RED` when switching on a `Color`). A label may appear only once.
* Strings and enums are compared with their `==`.
* `break` leaves the switch; `continue` goes to the enclosing loop. With no matching case and no `default`, nothing runs.
* A switch whose cases all return, and that has a `default`, counts as returning.

### Switch expressions

As in Java 14, a switch can also be a value. Each case gives one: an expression, a block that ends with `yield value;`, or a `throw`:

```text
string size = switch (n) {
    case 0 -> "none";
    case 1, 2, 3 -> "few";
    default -> {
        if (n < 0) { yield "negative"; }
        yield "many: " + n;
    }
};

int points = switch (color) { case RED -> 1; case GREEN -> 2; case BLUE -> 3; };   // every constant: no default needed
Animal a = switch (kind) { case "cat" -> new Cat(); default -> throw new IllegalArgumentException(kind); };
```

* Its type is what the cases' values meet in, as for `?:` (§44.1): the type the context wants if every value fits it, else the type they all widen to (`1` and `0.5` give a `double`), a common parent class, or a bare string. Values made only of constants give `int`, `double` or `string`.
* It needs a value for every subject: a `default`, or for an enum, a case for every constant (the error names the ones missing).
* A case can't leave the switch any other way: `return`, and `break` or `continue` to a loop around it, are errors. Loops inside a case use `break` and `continue` as usual, and `finally` blocks run when a `yield` leaves a `try`.
* The `:` style works too, with `yield`, and falls through to the next case until one yields: `case 1: case 2: yield "low";`.
* `yield` is a statement only in a switch expression's case; a variable can still be named `yield`.
* A switch expression can't give a unique, which a case would have to copy out.

## 41.4 Exceptions

Errors a program can handle are exceptions, as in Java: objects of classes extending `Exception`, thrown with `throw` and caught with `try`/`catch`.

```text
class BadInput extends(IllegalArgumentException) {
    public int code;
    public new(string m, int c) { super(m); code = c; }
}

try {
    int n = Integer.parse(text);
    if (n < 0) { throw new BadInput("negative", n); }
} catch (BadInput e) {
    System.stdout.println(e.getMessage() + " " + e.code);
} catch (NumberFormatException e) {
    System.stdout.println("not a number: " + e.getMessage());
} finally {
    System.stdout.println("done");
}
```

* `throw e;` takes a handle to an `Exception` (or subclass); `throw null` stops the program.
* The catches are tried in order, and the first whose class the exception is (`instanceof`) runs, with the exception in its variable. A catch for a class an earlier catch already covers is an error. With no match, the exception goes on to the enclosing `try`, or out of the method to its caller.
* `finally` runs however the `try` (and its catches) is left: at the end, by an exception (caught or not), or by `return`, `break` or `continue`. A `try` needs at least one catch or a finally, unless it has resources.
* Exceptions are unchecked: methods don't declare what they throw.
* **Cleanup.** An exception leaving a block or a method runs the same cleanup as leaving it normally: uniques are destroyed, handles released, temporaries of the statement released, so nothing leaks.
* **Uncaught.** An exception nothing catches stops the program (exit code 70) with its class, message and where it was thrown:

```text
uncaught IllegalArgumentException: negative: -3
    at Main.check (Main.seal:5)
    at Main.main (Main.seal:10)
```

### try-with-resources

A `try` can declare resources, as in Java: objects that hold something until they are closed, such as an open file. Each is closed when the block is left, however it is left:

```text
try (FileWriter out = new FileWriter("report.txt")) {
    out.println("total: " + total);
}                                        // out.close() runs here

try (FileReader in = new FileReader(a); FileWriter out = new FileWriter(b)) {
    for (string line : in) { out.println(line.toUpper()); }
} catch (IOException e) {
    System.stderr.println("copy failed: " + e.getMessage());
}
```

* A resource's type must implement `AutoCloseable`, whose one method is `void close()`. `Writer`, `FileWriter`, `Reader` and `FileReader` do.
* Resources are declared in order, each in scope for the ones after it, and closed in reverse order. A null resource isn't closed. A resource variable can't be assigned.
* Any `catch` and `finally` blocks of the statement run after the resources are closed.
* If the block throws and `close()` throws too, the block's exception goes on, and the one from `close()` is added to it with `addSuppressed`; `getSuppressed()` lists them. If only `close()` throws, its exception goes on.

The standard library's exceptions, and what throws them:

| Class | Extends | Thrown by |
| ----- | ------- | --------- |
| `Exception` | | the root; `getMessage()`, `addSuppressed(e)`, `getSuppressed()` |
| `RuntimeException` | `Exception` | |
| `IllegalArgumentException` | `RuntimeException` | bad arguments, `Enum.valueOf` of an unknown name, `Random` bounds |
| `NumberFormatException` | `IllegalArgumentException` | `Integer.parse`, `string.toInt`, `BigUInt.parse` |
| `IllegalStateException` | `RuntimeException` | |
| `IndexOutOfBoundsException` | `RuntimeException` | `List`, `array`, `string` and `StringBuilder` indexes, `substring` |
| `NoSuchElementException` | `RuntimeException` | a missing `IntMap` key, an empty `Queue`, `removeLast` on an empty `List` |
| `ArithmeticException` | `RuntimeException` | `BigUInt` division by zero |
| `UnsupportedOperationException` | `RuntimeException` | |
| `AssertionError` | `RuntimeException` | a failed `assert`, with `-Wassert` (§41.4) |

### Assertions

`assert cond;` and `assert cond : message;` state what must be true, as in Java. They run only when the program is compiled with `-Wassert` (Java's `-ea`); otherwise they are checked by the compiler but skipped when running, condition and message alike, so they can't slow a program or change what it does:

```text
static int divide(int a, int b) {
    assert b != 0 : "divide by zero: " + a + " / " + b;
    return a / b;
}
```

* With `-Wassert`, a false condition throws an `AssertionError` with the message (any value that joins a string, §39), or else the condition as written: `uncaught AssertionError: x >= 0`, with the line of the assert.
* The condition is a `bit`. `AssertionError` extends `RuntimeException`, so it can be caught.

Faults the runtime detects (division by zero on plain integers, a null handle, an array index out of bounds, a failed cast, `System.fail`, running out of memory or stack) are not exceptions: they stop the program at once, like Java's `Error`s, with `trap:` and the stack.

How it works: Seal's exceptions are Java's, so `throw`, `try` and `catch` are the JVM's own exception tables; around what makes uniques, a handler destroys those made so far (locals, the statement's temporaries), then lets the exception go on to the innermost `try`'s catch tests, or to the caller (docs/JVM.md §7).

## 41.5 Increment and decrement

`++` adds one and `--` subtracts one, as in Java. Written before the variable, the expression's value is the new value; written after it, the old one:

```text
int i = 5;
int a = i++;                  // a is 5, i is 6
int b = ++i;                  // b is 7, i is 7
for (int k = 0; k < n; k++) { ... }
while (left-- > 0) { ... }
xs[count++] = value;
counts[word.length()]++;      // an array<int> element, through [] and []=
```

* The operand is anything that can be assigned (a variable, a field, an element) holding a number of any kind: an integer of any width, fixed point, `float` or `double`. It is read and written once, so in `xs[i++]++` the index moves once.
* The result has the operand's own type, and it wraps like other arithmetic: a `uint<<8>>` holding 255 becomes 0. On a `char`, `c++` moves to the next character and stays a `char` (unlike `c + 1`, which is an `int`, §39).
* On an element reached through `[]=` (§46), `xs[i]++` reads the element with `[]`, then stores the result with `[]=`, evaluating `i` once. It has a value too: `int old = counts[i]++;`.
* Not for `bit`, addresses, strings or other objects; write `x += 1` where the type defines `+=` (§46). A bit slice (`x[0..4]`) takes `x[0..4] += 1`. A fixed-point type needs room for 1: `ufixed<<0, 8>>` has none.
* In generic code, `++` on a `T` is checked when `T` is known (§12).
* `x+++y` is `x++ + y`: the lexer takes the longest operator, as Java's does.

## 41.6 Widening and Narrowing

Numbers convert to wider types without a cast, as Java's do (byte to int to long to double):

```text
int<<64>> total = count;       // an int widens to an int<<64>>
double average = sum / 2.0;    // sum (an int) widens to double
big + i                        // with an int<<64>> big, i widens to it
fixed<<16, 16>> f = smallInt;  // an int<<8>> fits a fixed<<16, 16>> exactly
Math.pow(x, n)                 // n (an int) widens to double
```

A type widens to another when:

* both are integers or fixed point, and the other holds every value it can have: at least as many integer bits (one more when an unsigned type goes to a signed one) and at least as many fraction bits. So `int<<8>>` widens to `int`, `int<<64>>` and `fixed<<8, 8>>`; `uint` widens to `uint<<64>>` and `int<<64>>`, but not to `int`; a signed type never widens to an unsigned one.
* it is an integer or fixed point and the other is `float` or `double` (which may round, as Java's `long` to `double` does), or it is `float` and the other `double`.

A `char` widens like a 16-bit unsigned integer (to `int`, `int<<64>>`, `uint`, `float`, `double`), except to `uint<<16>>`, which is kept apart from it (§39). Nothing widens to a `char`, and fixed point never widens to a plain integer; those conversions narrow (below).

Where widening happens:

* **Assignments, initializers, arguments and return values** take any type that widens to theirs.
* **Operators** (`+`, `<`, `==`, `&`, ...) on two different types convert both to the one the other widens to: `i + d` is a `double`, `b + i` with an `int<<8>> b` an `int`. When neither holds the other, as for an `int` and a `uint`, both go to `int` if they fit it, or else `int<<64>>`, as Java promotes to `int` and then `long`: `i < u` compares correctly. A `uint<<64>>` and an `int` have no common type. A constant takes the other operand's type when it fits (`b + 1` stays an `int<<8>>`), and otherwise both go to the constant's type (`b + 1000` is an `int`).
* **`?:`** does the same with its two sides (§44.1).
* **Overloads:** a call may widen its arguments, but a method taking them exactly is chosen first; otherwise, as in Java, the most specific one, whose parameters all widen to the others': `f(int)` over `f(int<<64>>)` for an `int<<8>>`. Value binders take the width that every argument widens to: `Math.max(i, l)` with an `int` and an `int<<64>>` is `max` on `int<<64>>` (§9.3).

**Numbers narrow where they are stored or passed**, as C's do. A number converts to any other number type in an initializer, an assignment, a return value, a lambda result, an array element or an argument, as the cast to that type would: an integer wraps to a narrower width, floating point and fixed point truncate toward zero to an integer, and a double rounds to a float or a fixed format.

```text
int a = 2.5;          // 2
int<<8>> b = big;     // big's low 8 bits: 1000 is -24
char c = code;        // the character with that code
uint u = -1 + i - i;  // 4294967295
show(big);            // show(int<<8>> b) takes it narrowed
```

* Integer constants still must fit, `int<<8>> b = 1000;` is an error, since a constant that can't is always a mistake. A floating-point constant narrows to an integer when its whole part fits: `int a = 2.5;`.
* Operators don't narrow: they work in the type both operands widen to, as above, and their result narrows only where it is stored. `int<<64>> + uint<<64>>` still has no common type.
* An argument narrows only if no overload takes the arguments as they are or widened, so `f(double)` is chosen over `f(int<<8>>)` for an `int`. If several overloads would take them narrowed, the call is ambiguous and needs a cast.
* Only numbers convert like this. An object is made with `new`: `Meters m = 2.5;` is an error, `new Meters(2.5)` is right.

**Compound assignments narrow back** in the same way. `x op= v` computes `x op v`, in whatever type the operator gives, and converts the result back to `x`'s type as a cast would:

```text
int i = 10;
i += 2.5;        // i = (int)(i + 2.5): 12
i *= 1.5;        // computed in double, then truncated: 18
int<<8>> b;
b += i;          // b = (int<<8>>)(b + i): wraps like a cast
char c = 'a';
c += 1;          // 'b'
xs[k] += 0.5;    // through []= as well, with k evaluated once
```

This works for every number type (integers, `char`, fixed point, `float` and `double`), not for `bit`, addresses or objects. `-WautoCastLossy` reports every compound assignment that narrows (as javac's `-Xlint:lossy-conversions` does), except where the right side is a constant that fits the variable's type, as in `c += 1`, which gives what arithmetic in a `char` would (§56.1).

The conversion itself is free between integers, which registers already hold sign- or zero-extended; to floating point and fixed point it is the same conversion a cast does (§37.1).

Two warnings show where these conversions happen (§56.1). `-WautoCast` reports every one: each operand, argument, assigned or returned value, `?:` side and array element converted to another number type without a cast, widening or narrowing. `-WautoCastLossy` reports only those that may lose data: every narrowing (but a whole-number constant such as `int a = 2.0;`, and compound assignments with a constant that fits), and widening to `float` or `double` from a type with more significant bits than they hold (24 and 53), as `int` to `float` and `int<<64>>` to `double` may round. Constants are never reported, since they take the type they are used as rather than being converted, and neither are the indexes of fixed arrays and bits, which take any integer as it is.

```text
double d = i;      // -WautoCast: implicit cast from int to double in initializer of d
float f = i;       // both: implicit cast from int to float in initializer of f may round
int<<8>> b = i;    // both: implicit cast from int to int<<8>> in initializer of b may lose data
i += 2.5;          // -WautoCast: from int to double in operand of +=
                   // both: from double to int in compound assignment may lose data
```

---

# 42. Standard Library

The standard library is a directory of ordinary source files (`stdlib/`), compiled together with every program. As Java's, it is in packages (§2.4), each in its own folder:

* `seal.lang` (`stdlib/seal/lang/`): what every program uses, which every file sees without an import, as Java's `java.lang`;
* `seal.util`: collections and other utilities, as `java.util`; `seal.util.function`: the functional interfaces lambdas implement, as `java.util.function`;
* `seal.io`: files, folders and streams, as `java.io` and `java.nio.file`;
* `seal.java`: Java's types as Seal names them, for using Java's own classes (§42.7).

A file imports the types it uses outside `seal.lang`: `import seal.util.List;`, or `import seal.io.*;`. `string` and `array` are built in: they are in no package, and every file sees them. A program's type in no package can't take the name of a `seal.lang` type or a built-in one, and a program can't declare a type in package `seal` or one in it (§2.4).

Library classes may declare `@native("name")` methods, which the runtime's `Natives` class implements (docs/JVM.md §8.3). Programs cannot.

| Type | Package | Contents |
| ---- | ------- | -------- |
| `string` | built in | strings (§39), immutable values: `length`, `charAt`, `+` and `+=`, `toCharArray`, `==`, `<`, `compare`, `startsWith`, `endsWith`, `indexOf`, `lastIndexOf`, `contains`, `split`, `lines`, `trim`, `substring`, `copy`, `toUpper`, `toLower`, `replace`, `repeat`, `toInt`, `hash`; `new string(chars)`; `string.format` (§42.6) |
| `array` | built in | arrays sized at run time, `array<T>` (§21.3) |
| `StringChars` | built in | on the VM, the buffer behind bare strings' characters; programs don't use it |
| `System` | `seal.lang` | `stdin`, `stdout`, `stderr` (§42.2), `exit(code)`, `rawAssembly(code)` (§42.3), `fail(message)`: print a message and stop with a trap; `nanoTime()` |
| `Object` | `seal.lang` | the root of every class (§31) |
| `Iterable` | `seal.lang` | what for-each goes over (§41.3) |
| `Runnable` | `seal.lang` | a functional interface for lambdas (§30.3) |
| `AutoCloseable` | `seal.lang` | what try-with-resources closes: `close()` (§41.4) |
| `Double`, `Float` | `seal.lang` | `toString` |
| `Char` | `seal.lang` | `isDigit`, `isLetter`, `isUpper`, `isLower`, `isSpace`, `toUpper`, `toLower`, `digitValue` |
| `Integer` | `seal.lang` | `toString` (decimal or any base), `parse`, `mulExact` (exact, widening multiply: `int<<N>> × int<<P>> → int<<N + P>>`, for any mix of signed and unsigned), `min`, `max`, `abs`, `sign`, `gcd`, `pow`, `rol`, `ror` (§50), `bitCount`, `leadingZeros`, `trailingZeros`, and range constants |
| `Math` | `seal.lang` | for every kind of number (§42.5): `PI`, `E`, `abs`, `min`, `max`, `clamp`, `floor`, `ceil`, `round`, `sqrt`, `sin`, `cos`, `tan`, `atan2`, `exp`, `log`, `pow`, `hypot` |
| `Fixed` | `seal.lang` | fixed-point utilities (§37.1): `mulExact` (exact, widening multiply), `fraction`; also `abs`, `min`, `max`, `floor`, which `Math` has for every number |
| `Hash` | `seal.lang` | hash codes (§42.4) |
| `Objects` | `seal.lang` | `equals(a, b)`, `hash(x)`, `toString(x)` for values of any type, as Java's `java.util.Objects`; records use them (§2.5) |
| `Operator` | `seal.lang` | the operators `@overload` can name (§46) |
| `StringBuilder` | `seal.lang` | text built a piece at a time, as Java's (a class): `append` (anything `print` takes; returns the builder, so calls chain), `insert`, `delete`, `deleteCharAt`, `reverse`, `charAt`, `setCharAt`, `setLength`, `length`, `indexOf`, `toString` (its cast to string) |
| `Format` | `seal.lang` | the formatter behind `string.format` and `printf` (§42.6) |
| `Exception` and subclasses | `seal.lang` | `RuntimeException`, `IllegalArgumentException`, `NumberFormatException`, `IllegalStateException`, `IndexOutOfBoundsException`, `ArithmeticException`, `UnsupportedOperationException`, `AssertionError` (§41.4) |
| `List<T>` | `seal.util` | growable list (a class): `add`, `get`, `set`, `insert`, `removeAt`, `removeLast`, `first`, `last`, `indexOf`, `contains`, `sort`, `reverse`, `swap`, `reserve`, `clear` |
| `Queue<T>` | `seal.util` | FIFO ring buffer (a class): `push`, `pop`, `peek`, `clear` |
| `HashMap`, `HashMap.Entry` | `seal.util` | a hash map keeping insertion order, and its entries (§42.4) |
| `IntMap<V>` | `seal.util` | hash map with `int<<64>>` keys (a class): `put`, `get`, `has`, `remove`, `keys`, `clear` |
| `Iterator` | `seal.util` | what an `Iterable` gives for-each (§41.3) |
| `Comparator` | `seal.util` | an order, for `sort` (§30.3) |
| `Random` | `seal.util` | xorshift64 generator: `nextU64`, `nextInt`, `nextRange`, `nextDouble`, `nextBit` |
| `BigUInt<<BITS>>` | `seal.util` | fixed-width unsigned integers (§54): `+`, `-`, `*`, `==`, `<`, `<<<`, `<<=`, `divSmall`, `mulAddSmall`, `parse`, `toString` |
| `Array<T><<N>>`, `Array<T><<N, K>>` | `seal.util` | the fixed array from §13, plus `set` and `fill` |
| `NoSuchElementException` | `seal.util` | what a collection throws for an element it doesn't have (§41.4) |
| `Supplier`, `Consumer`, `BiConsumer`, `Predicate`, `Function`, `BiFunction` | `seal.util.function` | functional interfaces for lambdas (§30.3) |
| `Files`, `File`, `Reader`, `FileReader`, `Writer`, `FileWriter`, `IOException`, `FileNotFoundException` | `seal.io` | files, folders and the standard streams (§42.1, §42.2); `printf` (§42.6) |
| `JavaInt`, `JavaLong`, `JavaShort`, `JavaByte`, `JavaFloat`, `JavaDouble`, `JavaChar`, `JavaBoolean` | `seal.java` | Java's primitive types, and `of`, which makes one of a Seal number (§42.7) |
| `JavaObject`, `JavaString`, `JavaIntBoxed` and the other boxes (`JavaLongBoxed` ... `JavaBooleanBoxed`), `JavaArray` | `seal.java` | `java.lang.Object`, `java.lang.String`, `java.lang.Integer` and the others, with Java's members and an `of`; Java's arrays (§42.7) |

`List`, `Queue` and `IntMap` are classes, like Java's collections: create one with `new`, and variables share it. They keep their elements in `array<T>`s, release them as they are removed, and are freed automatically once nothing refers to them:

```text
List<Animal> zoo = new List();
List<Animal> same = zoo;     // the same list
zoo.add(new Dog());          // visible through `same`
```

Methods that need an operator on the element type, such as `List<T>.sort()` (`<`) and `indexOf` (`==`), are only checked when used (§59.1), so `List<Foo>` for a class `Foo` is fine until `sort` is called. (Every method of a class is compiled for its vtable, but one that fails for the element type is reported only if the program calls it; reaching it otherwise stops the program.)

Errors in library calls (an index out of range, a missing key, text that isn't a number) throw exceptions (§41.4), which a program can catch. `System.fail` is still there for stopping the program at once.

## 42.1 Files and folders

The standard library reads and writes files and works with folders, in the style of Java's `java.nio.file.Files` and `java.io.File`, in package `seal.io` (`import seal.io.*;`). Paths are strings, absolute or relative to the folder the program runs in, with `/` between names. Text is read and written as UTF-8.

**`Files`**: whole files and folders at once, as static methods.

| Method | Does |
| ------ | ---- |
| `readString(path)`, `writeString(path, text)`, `appendString(path, text)` | a whole text file |
| `readAllLines(path)`, `writeLines(path, lines)` | a text file as a `List<string>` of lines (without line ends) |
| `readBytes(path)`, `writeBytes(path, bytes)` | a file as a `List<uint<<8>>>` |
| `exists`, `isFile`, `isDirectory`, `size`, `lastModified`, `absolutePath` | questions |
| `createDirectory`, `createDirectories`, `createTempDirectory(prefix)` | making folders |
| `list(folder)` | the paths of what a folder holds, sorted by name |
| `walk(path)` | the folder and everything below it, each folder before what it holds, sorted by name |
| `copy`, `move`, `delete`, `deleteIfExists`, `deleteRecursively` | changing things |
| `join(folder, name)` | `folder/name` |

**`File`**: a path, with Java's methods: `getName`, `getExtension`, `getParent`, `getParentFile`, `getPath`, `getAbsolutePath`, `exists`, `isFile`, `isDirectory`, `length`, `lastModified`, `list` (names), `listFiles`, `walk`, and `delete`, `mkdir`, `mkdirs`, `renameTo`, which return whether they worked. `new File(folder, name)` joins paths, and two Files with the same path are `==`.

**`FileReader`** reads a text file a line at a time (`hasNextLine`, `readLine`, `close`), and can be the subject of a for-each: `for (string line : new FileReader(path))`; it closes itself once it has read the last line. **`FileWriter`** writes one (`write`, `writeLine`, `flush`, `close`; `new FileWriter(path, true)` appends). Both are `AutoCloseable`: close them with `close()`, or with try-with-resources (§41.4). The VM writes out any file still open when the program ends.

```text
string dir = Files.createTempDirectory("demo");
Files.writeString(dir + "/notes.txt", "one\ntwo\n");
for (string line : Files.readAllLines(dir + "/notes.txt")) { ... }
for (File f : new File(dir).walk()) {
    if (f.isFile() && f.getExtension() == "txt") { System.stdout.println(f.getName() + " " + f.length()); }
}
try (FileWriter out = new FileWriter(dir + "/log.txt")) {
    out.writeLine("done");
}
Files.deleteRecursively(dir);
```

* A failure throws an `IOException`, or a `FileNotFoundException` (a subclass) for a missing file, whose message says what went wrong and with which path: `no such file or directory: ...`, `already exists: ...`, `folder is not empty: ...`, `not a folder: ...`, `permission denied: ...`.
* Listings are sorted by name, so a program sees the same order on every system.
* Whoever runs a program can turn file access off (the runtime's `Natives.fileAccess`); every file operation then throws `IOException: file access is disabled`. The fuzz tests run that way.
* `delete` is a keyword, but may also name a method, as Java's does: `Files.delete(path)`, `file.delete()`. With no return type, `delete() { }` is a destructor.

Strings gained the methods file and path work needs: `endsWith`, `lastIndexOf(char)`, `indexOf(string)`, `contains(string)`, `split(char)`, `lines()` and `trim()`.

How it works: each `@native` method is a static method of the runtime's `Natives` class, on `java.nio.file` and Java's streams (docs/JVM.md §8.3). The text a file operation produces (file contents, a line, a listing, an error message) is kept there, and the library takes it as a new string.

## 42.2 Standard input, output and error

`System` holds the program's standard streams, as Java's `System.in`, `System.out` and `System.err` do:

```text
System.stdout.print("name? ");
string name = System.stdin.readLine();
System.stdout.println("hello, " + name);
for (string line : System.stdin) { total += (int)Integer.parse(line); }
if (total < 0) {
    System.stderr.println("negative total");
    System.exit(1);
}
```

* `System.stdin` is a `Reader`: `hasNextLine()`, `readLine()` (a `NoSuchElementException` past the end), `readAllLines()`, and for-each over its lines.
* `System.stdout` and `System.stderr` are `Writer`s: `print` and `println` for strings, chars, integers of every width, `float`, `double`, fixed point and `bit`, and any value whose type casts to string (§46), such as an enum; `printf` (§42.6); `write`, `writeLine`, `flush`. Output is written as it is printed, and reading input first shows what was printed (a prompt).
* `Reader` and `Writer` are also the bases of `FileReader` and `FileWriter` (§42.1), so a method taking a `Writer` can write to a file or to the terminal.
* `System.exit(code)` ends the program at once with that exit code.
* A program's standard input is empty unless whoever runs it gives it one (the command line passes the process's), so a program run by a test never waits for a keyboard. Unlike files, the standard streams work when file access is turned off.

## 42.3 Raw assembly

`System.rawAssembly(code)` is a place for machine-specific assembly, for a future native compiler (§58) to put into the program at that point, as it is:

```text
System.rawAssembly("mov rax, 60\n" + "xor rdi, rdi\n" + "syscall");
```

* The JVM runs bytecode, not machine code, so the call compiles to no instructions at all.
* The code must be a string literal, or literals joined with `+`. A native compiler would assemble it when compiling the program, so it can't be computed while the program runs; a variable or other expression is an error.
* Which assembly language (instruction set, syntax) it is written in, and how it may use the program's variables and registers, are left to the native compiler that adds it.

## 42.4 HashMap

`HashMap<K, V>` (`import seal.util.HashMap;`) maps keys to values, as Java's does:

```text
HashMap<string, int> counts = new HashMap();
for (string w : words) { counts.put(w, counts.getOrDefault(w, 0) + 1); }
counts.get("the")                       // NoSuchElementException if absent
counts.containsKey("cow")
counts.remove("and")                    // true if it was there
for (string w : counts.keys()) { ... }
for (HashMap.Entry<string, int> e : counts.entries()) { e.getKey() ... e.getValue() }
counts.forEach((w, n) -> System.stdout.println(w + " " + n));
```

It also has `size`, `isEmpty`, `clear` and `values`. Unlike Java's `HashMap`, it keeps the order in which keys were first put in, as Java's `LinkedHashMap` does, so a program lists them the same way on every run.

**Keys** need `==` and a hash, from `Hash.of(key)`:

* numbers, `char`, `bit` and strings hash by value (`0.0` and `-0.0` are the same key);
* class objects use their `hash()` method: `Object`'s hashes by identity, matching the default `==`, and a class that defines `==` should define `hash()` to match;
* a struct used as a key must define `==` and `hash()`.

`Hash.mix(x)` spreads a value's bits, for writing `hash()` methods: `public uint<<64>> hash() { return Hash.of(x * 31 + y); }`.

How it works: entries are kept in lists in insertion order, and an index table, searched by linear probing from each key's hash, finds them. Removed entries are dropped when the table is rebuilt, which also keeps it at most three quarters full.

## 42.5 Math

`Math` works on every kind of number: signed and unsigned integers of any width, `float`, `double`, and fixed point of any format.

```text
Math.max(a, b)              // int, int<<64>>, uint<<8>>, float, fixed<<16, 16>>, ...
Math.abs(-7)                // 7
Math.clamp(health, 0, 100)
Math.sqrt(2)                // 1.4142135623730951, a double
Math.sin(angle)             // a fixed<<16, 16>> angle gives a fixed<<16, 16>>
Math.floor(position)        // exact on fixed point: no trip through double
```

| Functions | Result |
| --------- | ------ |
| `abs`, `min`, `max`, `clamp`, `floor`, `ceil`, `round` | the type they are given |
| `sqrt`, `sin`, `cos`, `tan`, `exp`, `log`, `atan2`, `pow`, `hypot` | `double` for integers (as Java's do), `float` for a float, the same format for fixed point |

* Arguments of different types widen to a common one, as Java's do (§41.6): `Math.max(i, d)` with an `int` and a `double` is a `double`, `Math.max(i, l)` with an `int<<64>>` is an `int<<64>>`, and `Math.pow(x, n)` takes an `int` exponent. A constant takes the other arguments' type (`Math.max(x, 0)`) when it fits. With only constants, integers are `int`s and decimals `double`s: `Math.max(2, 3)` is `3`, `Math.max(1.5, 2)` is `2.0`.
* A `char` counts as an `int` (§39): `Math.max(c, 'z')` is an `int`.
* `abs` of the most negative value of a signed type stays negative, as in Java. On unsigned types it gives the value itself.
* `round` rounds half up: `round(2.5)` is 3, `round(-2.5)` is -2.
* On integers, `floor`, `ceil` and `round` give the value itself, so generic code can use them on any number.
* On fixed point, `floor`, `ceil` and `round` are exact and use integer instructions; the other functions convert through `double` and round back to the format (§37.1). A result outside the format wraps, as a cast does, and a format that can't hold 1 (`ufixed<<0, 8>>`) has no `ceil` or `round`.
* `float` functions compute in `double` and convert back.

---

## 42.6 Formatting

`string.format` makes text from a format and values, as Java's `String.format` does, and a `Writer` (such as `System.stdout`) has `printf`:

```text
string line = string.format("%-8s %8.2f %5d", name, price, count);  // "Ada       1234.50    42"
System.stdout.printf("%,d bytes (%.1f%%)%n", size, percent);        // "1,234,567 bytes (12.5%)"
```

A specifier is `%[flags][width][.precision]conversion`:

| Conversion | Shows |
| ---------- | ----- |
| `d` | an integer (any width, signed or unsigned) in decimal; a char's code |
| `x` `X` `o` | an integer in hex (lower or upper case) or octal; a negative one as its bits at its own width, so `-1` is `ffffffff` for an `int` and `ff` for an `int<<8>>` |
| `f` | a number with `precision` decimals (6 if not given), rounded half up; fixed point exactly |
| `e` `E` | a number in scientific notation: `1.234568e+04` |
| `s` `S` | any value as text, as `+` on a string shows it: a number's text, or an object's cast to string (§46); `S` in capitals; a precision cuts the text short |
| `c` `C` | a character, or the character with an integer's code |
| `b` `B` | a bit: `true` or `false` |
| `%%` `%n` | a percent sign; a line break |

| Flag | Effect |
| ---- | ------ |
| `-` | left-justify within the width |
| `0` | pad a number with zeros after its sign: `%08.2f` gives `-0002.50` |
| `+` | always show a sign; ` ` (space) shows a space where a plus would be |
| `,` | group thousands: `1,234,567` |
| `#` | `0x` before hex, `0` before octal |

* Up to eight values. Each may be of any type: `format` is generic, and each value is shown by the method for its type, chosen when the program is compiled.
* A value the conversion can't show (`%d` with a string), a malformed specifier, or too few values throws an `IllegalArgumentException` naming the specifier and the format. Extra values are ignored, as in Java.
* `%s` of an object uses its type's cast to string (§46), as Java's calls `toString()`; formatting an object whose type has none is a compile error, reported at the call.
* Doubles are formatted by the same rules as Java's, so `%.2f` of `1.005` is `1.01`.

## 42.7 Java classes

On the JVM, a program uses Java's own classes (the JDK's, and those of the jars and folders `--classpath` names) as it uses Seal's: imported by name, checked while compiling, and called as the JVM calls them (docs/JAVA.md). Values cross between the two by hand, through `seal.java`'s types.

```text
import java.time.LocalDate;
import java.util.ArrayList;
import seal.java.*;

LocalDate d = LocalDate.of(JavaInt.of(2026), JavaInt.of(10), JavaInt.of(20));
ArrayList names = new ArrayList();
names.add(JavaString.of("Ada"));
int n = (int)names.size();
string first = (string)names.get(JavaInt.of(0));
System.stdout.println(d.getDayOfWeek() + " " + names + " " + n);   // TUESDAY [Ada] 1
```

* **Names.** `import java.util.ArrayList;`, `import java.util.*;`, `import static java.lang.Math.max;`, and a full name anywhere (`java.time.LocalDate.now()`) name Java's classes; a nested one is named after its class, `Map.Entry`. `java.lang` isn't imported by itself, and an import by name hides a `seal.lang` type of the same name in its file. A name that a `*` import of Java's shares with `seal.lang` is an error where it is used. Only public classes, and public members, are seen.
* **Types.** A Java class or interface is a class type, held by a handle that may be `null`; `==` is identity. Java's primitive types are `seal.java`'s `JavaInt` (`int`), `JavaLong`, `JavaShort`, `JavaByte`, `JavaFloat`, `JavaDouble`, `JavaChar` and `JavaBoolean`. `java.lang.Object` is `JavaObject`, `java.lang.String` is `JavaString`, and the boxes are `JavaIntBoxed`, `JavaLongBoxed`, `JavaShortBoxed`, `JavaByteBoxed`, `JavaFloatBoxed`, `JavaDoubleBoxed`, `JavaCharBoxed` and `JavaBooleanBoxed`, each with Java's own members; importing one of those by Java's name is an error. A Java array `T[]` is `JavaArray<T>`.
* **Operators.** Java's primitives have Java's operators, as Java computes them: `JavaInt + JavaInt` is a `JavaInt`, `JavaByte + JavaByte` a `JavaInt` and `JavaInt + JavaLong` a `JavaLong` (Java's promotion), `/` and `%` as Java's, a comparison a `JavaBoolean`; `&&`, `||` and `!` take `JavaBoolean`s; compound assignments narrow back as Java's do, and `++` steps. Java's values and Seal's don't mix: `JavaInt + int` and `JavaInt + 1` are errors. A condition is a `bit`, so a `JavaBoolean` is cast: `if ((bit)(a < b))`, `while ((bit)it.hasNext())`.
* **Generics.** A Java generic takes type arguments that are objects to Java, Java's classes and Seal's: `ArrayList<JavaString>`, `TreeMap<JavaString, JavaIntBoxed>`, `ArrayList<Point>` (not `ArrayList<int>`: `JavaIntBoxed`). They are checked while compiling and erased on the JVM, where the element a `get` gives is checked as its type. `new ArrayList()` takes its arguments from the type it goes to, through a parent too (`List<JavaString> l = new ArrayList();`); a generic method's come from its arguments, then from the type its value goes to, or are given, `Collections.<JavaString>emptyList()`. A type argument Java writes as a wildcard takes what Java's does (`? extends X`: X and below); a member whose type is one gives its bound. A generic used without arguments is raw, as in Java: its type variables are what they erase to (`JavaObject`, mostly), and it goes where a parameterized one is wanted, unchecked.
* **Into Java,** nothing goes by itself: a Seal value goes in by the `of` of the Java type it becomes. `JavaInt.of(int)`, `JavaLong.of(int<<64>>)`, `JavaShort.of(int<<16>>)`, `JavaByte.of(int<<8>>)`, `JavaFloat.of`, `JavaDouble.of`, `JavaChar.of(char)`, `JavaBoolean.of(bit)`, `JavaString.of(string)`, the boxes' `of` (`JavaIntBoxed.of(5)`, an `Integer`), and `JavaObject.of(o)` for an object of a Seal class. `null` goes to any Java reference.
* **Out of Java,** a cast: `(int)` of a `JavaInt` (and `(int<<64>>)` of a `JavaLong`, and so on), converted as Seal converts numbers; of a box, which unboxes it; of a `JavaObject`, which unboxes the box of that type's primitive, checked while the program runs (`(int)o` an `Integer`), as Java's does. `(string)` of any Java value is Java's text of it (`"null"` for `null`), which `+` and `println` use too. A `JavaObject` that holds a Seal object casts back to its class, checked, and `instanceof` tests it.
* **Calls.** Constructors (`new ArrayList()`), static and instance methods, and fields, read and assigned (`final` ones only read). Among overloads, the arguments choose as in Java, but without boxing: the methods they fit (an object of a subclass, a primitive Java widens, as a `JavaInt` to a `long`), then those taking varargs, whose arguments are put in an array; the most specific wins, and two equally specific are an error. A `JavaInt` doesn't go where an `Integer` or `Object` is wanted: `JavaIntBoxed.of` makes one that does.
* **Arrays** are copied both ways, as an `array<T>` is a unique: `JavaArray.of(xs)` makes a Java array of a Seal one (an `array<int<<8>>>` or `array<uint<<8>>>` is a `byte[]`, `array<int>` an `int[]`, `array<string>` a `String[]`, `array<JavaObject>` an `Object[]`), a cast copies one out (`(array<uint<<8>>>)bytes`), and `JavaArray<JavaByte> b = new JavaArray(n);` makes one of Java's defaults, for Java to fill. In place, `a[i]` is an element, read and assigned, and `a.length` its length, a `JavaInt`.
* **For-each and resources.** For-each goes over a Java `Iterable` (`for (JavaString s : names)`) and a Java array; try-with-resources closes a Java `AutoCloseable`.
* **Exceptions.** Java's are classes: `catch (IOException e)` catches one by its class, and `throw` throws one; Seal's `catch (Exception e)` catches Seal's alone. `finally` runs for both. A `catch` of Java's `Throwable` doesn't catch a trap or `System.exit`. One nothing catches ends the program as a Seal exception does: `uncaught java.nio.file.NoSuchFileException: notes.txt`.
* **Seal into Java.** A lambda goes where Java wants an interface with one abstract method, taking Java's types: `names.sort((a, b) -> a.compareTo(b))`, `new Thread(() -> ...)`. A Seal class implements a Java interface as a Seal one: `class Version implements(Comparable<Version>) { public JavaInt compareTo(Version o) { ... } }`, and Java's code calls it. A Seal class's `==`, `hash()` and cast to string are Java's `equals`, `hashCode` and `toString`, so Java's `HashMap` finds Seal keys and Java prints Seal objects as Seal does.
* **Extending Java's classes.** A Seal class extends a public Java class that isn't final: `class Worker extends(Thread) { public void run() { ... } }`. Its constructors call one of Java's with `super(...)` (or else the one that takes nothing); its methods override Java's, which Java's code then calls (not a final one, and returning what Java's returns); `super.m()` calls the parent's own; and it reaches the parent's protected members, which other code doesn't. Its objects are Java objects, and without a cast to string of its own, its text is Java's `toString()`.

---

# 43. String Ownership

Functions that produce text, such as `Integer.toString(32)`, `string.substring` and `StringBuilder.toString`, return a new bare `string`. Like every string it is an immutable value, and its memory is freed automatically.

---

# 44. Operators

Operator overloading uses decorators.

Example:

```text
struct BigUInt<<int BITS>> {
    array<uint<<64>>><<(BITS + 63) / 64>> words;

    @overload(Operator.ADD)
    public BigUInt<<BITS>> add(BigUInt<<BITS>> o) {
        ...
    }

    @overload(Operator.LESS)
    public bit isLess(BigUInt<<BITS>> o) {
        ...
    }
}
```

The method itself retains a normal identifier.

The decorator specifies which operator it implements.

## 44.1 The conditional operator

`cond ? a : b` is `a` if `cond` (a `bit`) is true, otherwise `b`; only that side is evaluated. It binds more loosely than `||` and groups to the right, so `x > 0 ? 1 : x < 0 ? -1 : 0` needs no parentheses.

Its type is the one the context wants, when both sides fit it (`int<<8>> b = c ? 100 : 7;`, `Shape s = c ? new Circle() : new Square();`, lambdas on both sides). Otherwise it is the type the two sides share: numbers meet as an operator's operands do (§41.6), so `c ? i : 2.5` is a `double` and `c ? f : 0.0` a `float`; two constants take their defaults (`c ? 1 : 2.5` is a `double`); a handle and `null` give the handle, two classes give their nearest common parent, and two strings of different sizes give a bare `string`. Sides with no common type are an error. An `instanceof` binding in the condition is visible in the first side.

---

# 45. Operator Resolution

The operator implementation is selected according to the type of the left operand.

Normal overload resolution is then applied to:

```text
(shape, argument types)
```

The underlying method remains callable by its ordinary name.

Unary versus binary operators are distinguished by parameter count.

## 45.1 Precedence

From lowest to highest:

| Level | Operators                                   | Associativity |
| ----- | ------------------------------------------- | ------------- |
| 1     | `=` `+=` `-=` `*=` `**=` `/=` `%=` `&=` `\|=` `^=` `<<=` | right  |
| 2     | `\|\|`                                        | left          |
| 3     | `&&`                                        | left          |
| 4     | `==` `!=` `<` `>` `<=` `>=`                 | none          |
| 5     | `\|`                                        | left          |
| 6     | `^`                                         | left          |
| 7     | `&`                                         | left          |
| 8     | `<<<`                                       | left          |
| 9     | `+` `-`                                     | left          |
| 10    | `*` `/` `%`                                 | left          |
| 11    | unary `-` `!` `~` `&` `*` `++` `--`, casts  | right         |
| 12    | `**`                                        | right         |
| 13    | postfix `.` `()` `[]` `[..]` `++` `--`      | left          |

Bitwise operators bind tighter than comparisons, so `a & b == c` means `(a & b) == c`.

`**`, a power, binds tighter than the unary operators before it, as in mathematics: `-2 ** 2` is `-(2 ** 2)`, -4, and `(double)n ** 2` is `(double)(n ** 2)`. It groups to the right, `2 ** 3 ** 2` is `2 ** 9`, and its exponent may have a sign of its own, `2 ** -1`. `^` is XOR, as in Java, not a power: `2 ^ 16 - 1` is `2 ^ 15`, 13.

Comparisons do not chain: `a < b < c` is an error and must be parenthesized.

---

# 46. Overloadable Operators

A class, struct or unique defines an operator with a method marked `@overload`, naming the operator by a constant of the standard library's `Operator` enum:

```text
struct Vec2 {
    public double x;
    public double y;
    @overload(Operator.ADD)    public Vec2 plus(Vec2 o) { return new Vec2(x + o.x, y + o.y); }    // a + b
    @overload(Operator.NEGATE) public Vec2 negated() { return new Vec2(-x, -y); }                  // -a
    @overload(Operator.EQUALS) public bit same(Vec2 o) { return x == o.x && y == o.y; }           // a == b
}
```

| Constant | Operator | Parameters |
| -------- | -------- | ---------- |
| `ADD`, `SUBTRACT`, `MULTIPLY`, `POWER`, `DIVIDE`, `REMAINDER` | `+` `-` `*` `**` `/` `%` | the right operand |
| `NEGATE` | `-x` | none |
| `BIT_AND`, `BIT_OR`, `BIT_XOR` | `&` `\|` `^` | the right operand |
| `BIT_NOT` | `~x` | none |
| `EQUALS`, `NOT_EQUALS`, `LESS`, `GREATER`, `LESS_EQUAL`, `GREATER_EQUAL` | `==` `!=` `<` `>` `<=` `>=` | the right operand; returns `bit` |
| `SHIFT` | `<<<` | the shift amount |
| `SHIFT_ASSIGN` | `<<=` | the shift amount; changes the object itself |
| `ADD_ASSIGN`, `SUBTRACT_ASSIGN`, `MULTIPLY_ASSIGN`, `POWER_ASSIGN`, `DIVIDE_ASSIGN`, `REMAINDER_ASSIGN` | `+=` `-=` `*=` `**=` `/=` `%=` | the right operand; changes the object itself |
| `BIT_AND_ASSIGN`, `BIT_OR_ASSIGN`, `BIT_XOR_ASSIGN` | `&=` `\|=` `^=` | the right operand; changes the object itself |
| `INDEX` | `xs[i]` | the index |
| `INDEX_SET` | `xs[i] = v` | the index and the value |
| `CAST` | `(T)x` | none; returns `T`, the type it casts to |

* The argument must be one of these constants, written `Operator.NAME`. `Operator` is an ordinary enum (`Operator.ADD.symbol()` is `"+"`), but `@overload` reads the name when compiling.
* `-` is two operators: `SUBTRACT` (`a - b`) and `NEGATE` (`-a`).
* `!=`, `>`, `<=` and `>=` are derived from `==` and `<` when a type doesn't define them (§48).
* **Compound assignments are operators of their own.** On a type that defines operators, `x += y` calls its `ADD_ASSIGN` method, which changes `x` itself and returns `void`; it never falls back to `x = x + y`, nor `x <<= n` to `x = x <<< n` (§51). A type has the compound operators it declares, and an error says which to declare:

  ```text
  @overload(Operator.ADD)        public Vec2 plus(Vec2 o) { return new Vec2(x + o.x, y + o.y); }   // a + b: a new vector
  @overload(Operator.ADD_ASSIGN) public void move(Vec2 o) { x += o.x; y += o.y; }                 // a += b: a itself changes
  ```

  On a class object, `a += b` changes the object `a` refers to, which every reference to it sees. Built-in types keep their own: `i += 2` on an `int` is `i = i + 2` (§41.6), and the library's `string`, `BigUInt` and others declare the compound operators they have (`s += "x"` stores `s + "x"` in `s`, a new string).
* Operators are inherited by subclasses and are not virtual (§52).

Indexing is for types that act like containers:

```text
@overload(Operator.INDEX)     public T get(int i) { ... }              // xs[i]
@overload(Operator.INDEX_SET) public void set(int i, T value) { ... }  // xs[i] = v
```

`[]` takes the index (of any type: a map could take a string) and gives the element; `[]=` takes the index and the new value. `xs[i] += v` on a number element is `xs[i] = xs[i] + v` with `i` evaluated once (when it is a number), narrowed back to the element's type as other compound assignments are (§41.6). On an element that is an object (a struct, a class object, a string) it uses the element's own `+=`: `var e = xs[i]; e += v; xs[i] = e;`, with `i` evaluated once, as a statement of its own. An assignment through `[]=` has no value of its own; `xs[i]++` does have one (§41.5). `array<T>` (§21.3) is indexed this way.

A type casts itself to other types with `CAST` methods, one per type it casts to. `(T)x` calls the one returning `T` exactly, so overloads differ only in their return type:

```text
struct Meters {
    public double v;
    @overload(Operator.CAST) public double toDouble() { return v; }       // (double)m
    @overload(Operator.CAST) public int toInt() { return (int)v; }        // (int)m
    @overload(Operator.CAST) public Feet toFeet() { return new Feet(v * 3.28084); }
}
```

* A cast operator is only called by an explicit cast, never implicitly (§41.6), with one exception: **a cast to `string` is the type's text**, which Java calls `toString()`. String `+` and `+=` (§39), `print` and `println` (§42.2) and `%s` (§42.6) use it, and an enum has one already that gives the constant's name (declaring its own replaces it):

  ```text
  struct Point {
      public int x;
      public int y;
      @overload(Operator.CAST) public string text() { return "(" + x + ", " + y + ")"; }
  }

  "at " + p                 // "at (1, 2)"
  System.stdout.println(p); // (1, 2)
  "color " + Color.RED      // "color RED"
  ```

* The other way, a value becomes a struct only through a constructor: `new Meters(2.5)`.
* The built-in casts come first: between numbers, between related class handles (upcasts and checked downcasts), and of a value to its own type. A `CAST` method is used only where none of those applies, so `(Account)savings` stays an upcast.
* Two `CAST` methods can't return the same type, and a cast to a type with none is an error that lists the types it does cast to.
* A `CAST` method that returns a unique (an `array<T>`, §21.3) makes a new one, which the cast hands over: with `@overload(Operator.CAST) public array<int> toArray()` on a class `Bag`, `array<int> xs = (array<int>)bag;` holds.

Compound operators don't derive from their base operators: each one a type has, it declares (above).

---

# 47. Non-Overloadable Operators

The following are not overloadable:

```text
=
&&
||
*x
&p
```

Assignment and fundamental memory operations therefore retain fixed language semantics.

---

# 48. Comparisons

Comparisons return:

```text
bit
```

The six comparison operators are independently overloadable:

```text
==
!=
<
>
<=
>=
```

Proposed derived behavior:

If not explicitly implemented:

```text
!=
>
<=
>=
```

may be derived from:

```text
==
<
```

---

# 49. Shifts

The language has one shift operator:

```text
<<<
```

Its meaning depends on the sign of the shift amount.

```text
x <<< 3
```

means left shift.

```text
x <<< -3
```

means right shift.

The in-place form is:

```text
<<=
```

Example:

```text
x <<= n;
```

`<<=` is a separate operator overload and takes a plain signed `int`.

There are no:

```text
>>>
>>=
<<<=
<-<
>->
```

operators.

---

# 50. Shift Semantics

The proposed semantics are:

* shift amount is a signed `int`
* unsigned shifts are zero-filled
* signed right shifts are sign-filled
* shifting by the width or more produces zero for unsigned values
* shifting signed values by the width or more produces all sign bits
* no undefined shift behavior

The shift operator's precedence lies between additive and comparison operators.

Rotations are library operations:

```text
Integer.rol(...)
Integer.ror(...)
```

---

# 51. Compound Operators Don't Fall Back

A type that defines `<<<` but not `<<=`, or `+` but not `+=`, has no `<<=` or `+=`: `x <<= n` and `x += y` are errors that name the operator to declare (`@overload(Operator.SHIFT_ASSIGN)`, `@overload(Operator.ADD_ASSIGN)`). They don't fall back to `x = x <<< n` or `x = x + y`. The same holds for `-=`, `*=`, `/=`, `%=`, `&=`, `|=` and `^=`.

A compound operator changes the object itself, so it can do what the base operator can't do cheaply: `BigUInt`'s `<<=` shifts its words in place, where `<<<` makes a new value. Keeping them apart means `a += b` never quietly builds a new object the way `a + b` does.

---

# 52. Decorators

Decorators currently support operator overloading, naming the operator by an `Operator` constant (§46):

```text
@overload(Operator.ADD)
```

and, in the standard library only, implicit conversions. Programs make objects with `new`, so `Meters m = 2.5;` is an error however Meters is declared. The library's `string` uses one `@implicit` constructor, `new<<>>(string<<int K>> other)`, so that a fixed string becomes a bare one wherever a bare one is wanted (§39):

* An `@implicit` constructor has exactly one parameter, and converts a value of exactly that type (or one that binds its value binders) wherever the struct is wanted. Conversions never chain.
* A conversion is not an exact match in overload resolution, so an overload taking the value's own type wins.
* Only structs and uniques can have one: a class object would be created without `new`.

Two more decorators are for the standard library's own code: `@native("name")` marks a method the runtime's `Natives` implements, and `@jvm("Strings.at")` names the runtime's method, or the backend's own code, that stands for a method on the JVM (docs/JVM.md §8). A program can use neither.

Possible future decorators include:

```text
@pure
@override
```

A general decorator system remains undecided.

Operator overloading works in all three kinds of type. For a `class`, the operator's `this` is the object, results are new objects (temporaries are released at the end of their statement, §26.1), operators are inherited by subclasses and are not virtual, and `==`/`!=` compare identity (as in Java) unless the class declares its own `==`. A comparison with `null` is always an identity check and never calls the class's `==`, so `other != null` is safe inside it.

---

# 53. Memory and Object Ownership

The language deliberately separates values from shared objects.

### Structs and uniques: values

* stored inline, with an exact layout
* a struct is copied on assignment; a unique cannot be copied
* never moves
* destroyed automatically at the end of its scope

### Classes: shared objects

* reached through handles (`Foo` or `handle<Foo>`)
* assignment shares the object
* the runtime may move it
* freed automatically once nothing refers to it, with no destructor; resources are closed with `close()`

### Raw addresses (optional)

`addr<T>` is a physical, bit-granular address with arithmetic, for low-level work. Memory allocated with `new<T>(n)` is released with `delete`, the only thing `delete` frees. Ordinary code does not need addresses.

This distinction is fundamental to the memory model.

---

# 54. Generic Big Integer Example

A large unsigned integer can be expressed entirely in user code:

```text
struct BigUInt<<int BITS>> {
    array<uint<<64>>><<(BITS + 63) / 64>> words;

    @overload(Operator.ADD)
    public BigUInt<<BITS>> add(BigUInt<<BITS>> o) {
        ...
    }

    @overload(Operator.LESS)
    public bit isLess(BigUInt<<BITS>> o) {
        ...
    }

    @overload(Operator.SHIFT)
    public BigUInt<<BITS>> shifted(int n) {
        ...
    }

    @overload(Operator.SHIFT_ASSIGN)
    public void shiftInPlace(int n) {
        ...
    }
}
```

Examples:

```text
BigUInt<<128>>
BigUInt<<256>>
BigUInt<<4096>>
```

are distinct concrete types.

---

# 55. Execution Model

The language compiles to bytecode for a lightweight **register virtual machine**.

Generics do not exist at the bytecode level.

Generic instantiation is performed by the compiler before bytecode generation.

Thus:

```text
Array<int><<64>>
Array<int><<128>>
```

produce separate concrete representations.

The same applies to methods with value binders (§9.3): each distinct set of bound values used in the program produces one concrete method.

Only combinations that occur in the program are generated; the compiler never generates all possible instantiations.

---

# 56. Implementation

Seal compiles to JVM class files (docs/JVM.md). Its first backend, a register VM written in Java, ran every program the test suites hold the same as the JVM does before it was removed.

## 56.1 Implementation

The compiler, in the Java package `io.github.thelinuxseal.seal` (`src/io/github/thelinuxseal/seal`), checks a program (`check/`), generates its classes (`jvm/`), and runs them with the runtime's classes (`runtime/`). It works as follows.

### Running programs

* The entry point is a `main()` with no parameters, returning `void` or an integer, in class `Main` (`--main` chooses another class). The program's exit code is `main`'s result. A class in a package is found by its plain name when only one class has it; otherwise `--main` gives its full name: `--main com.example.game.Main` (§2.4).
* As in Java 25, `main` may be static or not, and a static one is chosen first. For an instance `main()`, the program creates a `Main` with its no-argument constructor (which may be private), calls `main()` on it, and releases the object when `main` returns, before static fields (§26.1). Any kind that can be created works: a class, a struct or a unique. An abstract class, an enum, or a type with type parameters or shapes needs a static `main()`.

  ```text
  class Main {
      int count;

      void main() {
          count++;
          System.stdout.println("count " + count);
      }
  }
  ```

* `main` may be inherited, as in Java: `class Main extends(App)` runs the `main()` that `App` declares (which can call methods `Main` overrides).
* `seal run DIR` compiles every `.seal` file under `DIR` to JVM class files and runs them in the compiler's JVM (the `seal` script builds the compiler and runs `io.github.thelinuxseal.seal.Main`); `check` only reports errors; `dump` prints the class files, with the Seal line each run of instructions comes from; `seal build -d OUT DIR` writes them, with the runtime classes they use, so that `java -cp OUT Main` runs the program (docs/JVM.md). `--jar FILE` writes a jar instead, which `java -jar FILE` runs; `--resources DIR` puts DIR's files beside the classes. `--library` builds every class of the program and no `main`, for Java's code to use (a Fabric mod is one): the static fields are set up, in Seal's order, the first time Java's code touches one of its classes. The `.java` files given, or under the folders, are compiled with the program by the JDK's `javac` (docs/JAVA.md §11): those that use none of its Seal classes first, so that Seal's code uses theirs; those that use Seal's (a library's alone, such as a Fabric mod's mixins) after, against them.
* `--vm` is refused, saying the VM is removed; `--jvm`, from when it chose the backend, is still accepted.
* The standard library (§42) is loaded from `stdlib/` next to the compiler; `--stdlib DIR` chooses another directory and `--no-stdlib` turns it off. Without it, a minimal built-in `System.stdout` and `System.stderr` (printing numbers, characters and bits) are available.
* Warnings are off unless asked for: each kind has a `-W` flag that turns it on, and `-Wall` turns on every kind. They go to standard error, before any errors, tagged with their flag (`File.seal:6:9: warning: [-WautoCast] ...`), and don't stop a program from running or make `check` fail. Code in the standard library gives none. The kinds:

  | Flag              | Reports |
  | ----------------- | ------- |
  | `-WautoCast`      | every number converted to another type without a cast: `double d = i`, `i + d`, `f(i)` for `f(double)`, `i += 2.5` (§41.6) |
  | `-WautoCastLossy` | only those that may lose data: narrowing (`int<<8>> b = i`, `int a = 2.5`, the narrowing in `i += 2.5`), and `int` to `float` or `int<<64>>` to `double`, which may round |
  | `-Wassert` | not a warning: runs `assert` statements, as Java's `-ea` does (§41.4); without it they are skipped |

  With both on (as with `-Wall`, which turns on every `-W` flag, `-Wassert` too), each cast is reported once, under `-WautoCastLossy` if it may lose data.
* When several overloads accept a constant argument, the one that gives the most constants their default type, `int` for integer constants and `double` for floating-point ones, is chosen; if that still ties, one without value binders (§9.3). Among overloads that need widening, the most specific is chosen, as in Java; an argument narrows only when no overload takes it otherwise, and only one may then (§41.6).

### Instantiation

* Code is generated per concrete instantiation, and only for instantiations reachable from `main`. A method of `Array<int><<64>>` and the same method of `Array<int><<128>>` are separate functions.
* Bodies are type-checked again with actual values when instantiated, so value-dependent errors inside methods (e.g. a constant index out of bounds for `array<int><<N>>`) are reported as errors in that instantiation. An error in the standard library's code for the types a program uses is reported at the program's call that led to it, with the library line in parentheses: `Main.seal:6:9: in Writer.println[T=handle<Box>]: Box has no cast to string; ... (Writer.seal:54)`.

### Memory

* A class is a JVM class, and a class object a JVM object, which the garbage collector frees once nothing refers to it (§26.1).
* Structs, uniques and fixed arrays live in blocks of 64-bit words, laid out as `sizeof` and addresses describe them: a local's in its frame's block, an object's struct fields in its own (docs/JVM.md §4). Scalar locals are JVM locals unless their address is taken. Locals without an initializer start at zero.
* An address is a block and a bit offset in it. Loads and stores through one trap at the null address, outside the allocation it points into, or at a misaligned bit address.
* Array indexing is bounds-checked.
* `delete` of a null address does nothing. Deleting an address that was not returned by `new` traps.

### Calls and objects

* Arguments are passed as the JVM passes them. An aggregate argument is a fresh copy, so the callee cannot change the caller's value.
* Methods of classes are virtual, as Java's are. `super.m()` calls the parent's method directly. Methods with value binders are not virtual: each instance is a method of its own.
* Construction: construct the parent (classes, via `super(...)` or the parent's no-argument constructor), run field initializers and default-construct inline fields in declaration order, then run the constructor body.
* Destruction (§16): a unique's destructor body, then fields in reverse order. Inline values are destroyed at scope exit, including on `return`, `break`, `continue` and exceptions. `delete p` on an `addr<T>` destroys one `T`.
* `const` fields take no storage and can be read through the class name, like static fields.

### Runtime errors

* Class files record the source file and the line of each instruction. Code for an expression is attributed to the line where that expression starts, so a call written over several lines reports its first line.
* A trap prints its message and the Seal methods it happened in, innermost first, each with its source position:

```text
trap: division by zero
    at Main.divide (Main.seal:6)
    at Main.main (Main.seal:14)
```

* `dump` shows the same positions as `; File.seal:line` comments in the listing.

### Arithmetic

* Integer arithmetic wraps to the type's width. Division or remainder by zero traps.
* `x <<< n` with `n` at least the width gives 0; `x <<< -n` with `n` at least the width gives 0 for unsigned values and all sign bits for signed ones.
* Float to integer conversion truncates toward zero, then wraps to the target width.

---

# 57. Generic Distribution

Generic classes may be distributed as source or an intermediate representation because instantiation occurs at the use site.

A precompiled generic class cannot simply assume that every possible `<<...>>` instantiation exists.

---

# 58. Future Backends

Programs compile to JVM class files alone: Seal's first backend, a register VM, is removed (docs/JVM.md). Other possible targets, later:

* ahead-of-time native code
* C interoperability
* WebAssembly

---

# 59. Design Principles

The language is built around several major principles.

## Compile-time values are types

```text
Buffer<<16>>
Buffer<<32>>
```

are distinct concrete types.

## Layout is known when possible

Structs and uniques have statically calculable:

```text
sizeof(T)
alignof(T)
```

and field offsets.

## Values and shared objects are explicitly different

`struct` and `unique` declare values; `class` declares shared objects. The keyword says which, so a reader always knows whether assignment copies or shares.

## Pointers are optional

Ordinary code uses values, classes and methods. `addr<T>` exists for low-level work and is never required.

## Generic code does not require boxing

Primitive values work directly in generics:

```text
Array<int>
Array<uint>
Array<float>
```

No object wrapper is required.

## Inheritance is runtime polymorphism

Inheritance exists only for classes.

Structs and uniques use composition and compile-time generic specialization.

## Shape guards are explicit

A shape-specific member says exactly which instantiation it belongs to:

```text
foo<<int N>>()
foo<<>>()
```

while universal members remain unguarded:

```text
foo()
```

## Names cannot mix guarded and unguarded members

A member name is either:

```text
unguarded
```

or:

```text
guarded
```

never both.

## Copies are shallow

As in C, copying an object copies its bits. Ownership of memory reached through `addr` is the programmer's responsibility.

---

# 59.1 Checker Rules

These rules are implemented by the semantic checker (`src/io/github/thelinuxseal/seal/check`). Several resolve questions that were previously open.

### Files and shapes

* A class must be in a file whose name (up to the first `.`) is the class name, e.g. `Array.seal`.
* Generic code is checked once per header, with that header's values symbolic. Unguarded members are checked against every header, which enforces the coverage rule (§10) and per-shape member types (§9.2). Unguarded code may therefore use a value such as `N` only if every header binds it.
* Operators applied to values of a type parameter (`a < b` where `a` and `b` are `T`), and members used on them (`a.size()`, `a.name`), are accepted in the generic definition and checked when it is used with a concrete `T`, as with C++ templates: `Box<int>` reports that `int` has no methods only if the code calling `size()` on it is used.
* Checks that depend on actual values (negative array sizes, integer widths, compile-time division by zero) run when a concrete type such as `Buf<<0>>` is used, and are reported there: `in Buf<<0>>: array size -1 is negative`.
* Compile-time values are compared after normalization, so `N + K`, `K + N` and `N + K + 0` are the same value.
* Member guards may only declare values (`<<int N>>`); fixed values such as `<<0>>` are only allowed in `extends` guards.
* A field and a method cannot share a name.
* Static members cannot be guarded and cannot use the class's type parameters or values.

### Types and conversions

* Numbers widen without a cast to a type that holds every value they can have, and any number to `float` or `double`, as Java's do; they also narrow where stored or passed (`int<<8>> b = i;`, `int a = 2.5;`), as C's do, and a compound assignment narrows its result back (§41.6). Integer constants must fit. Objects don't convert implicitly: they are made with `new`.
* Integer constants (literals and compile-time values like `N`) take their type from context: `int<<8>> x = 100;`, `x + 1`, and `(float)occupied / N` are all valid. A literal must fit its target.
* Casts are allowed between numeric types, between `addr` types, and between related `handle` types (upcasts and runtime-checked downcasts, §35). `null` converts to any `addr` or `handle`.
* A string literal converts to the bare `string`, or to `string<<N>>` when it has at most `N` characters.
* `x[a..b]` selects bits `a` (inclusive) to `b` (exclusive). The bounds must be compile-time constants, and the result is `uint<<b - a>>`.

### Addresses and handles

* `addr + int` and `addr - int` give an `addr` (the offset is in bits); `addr - addr` gives `int<<64>>`.
* Members cannot be accessed directly through an `addr`: write `(&p).x`. Handles do allow direct access: `h.x`, `h.method()`.
* `*x` cannot take the address of anything reached through a handle, since class objects move.
* `new Foo()` produces an inline value, an `addr<Foo>`, or a `handle<Foo>`, depending on the declared type it is assigned to.
* `delete` takes an `addr` only; a handle's object is freed automatically (§26.1).

### Memory and resources

* Only a `unique` can have a destructor (§16.1).
* A class can't hold a unique with a destructor in a field (directly, in a struct or fixed array, or through a type argument); a static field can.
* A resource of try-with-resources must implement `AutoCloseable`, and its variable can't be assigned (§41.4).

### Layout

* 8-, 16-, 32- and 64-bit values align to their size; other widths can start at any bit. A class aligns to its most-aligned field and its size is rounded up to that alignment (§22).
* `addr<T>` and `handle<T>` are 64 bits.

### Operators

* Operator overloading works in structs, uniques and classes (§52).
* `!=` is derived from `==`, and `>`, `<=`, `>=` from `<`, when not declared (§48).
* Compound assignments don't fall back to their base operators: `x <<= n` is not `x = x <<< n`, nor `x += y` `x = x + y`; a type declares each one it has (§51).

---

# 60. Current Open Questions

The following areas remain unresolved or proposed.

### Fixed memory

* Exact behavior of misaligned access
* Explicit address cast syntax
* Untyped address syntax
* Whether zero-sized arrays are legal

### Dynamic memory

* Exact resizable-tail syntax
* Exact semantics of bulk copying into and out of class objects
* Exact hidden class-object header

### Type system

* Full assignability/conversion rules
* Exact downcast syntax
* Detailed shape-matching algorithm
* Exact inherited-member coverage rules

### Compile-time evaluation

* Whether restricted `pure` functions may be used inside `<<...>>`
* Constant-evaluation and instantiation-count limits

### Strings

* Whether to switch from UCS-2 to UTF-8 or UTF-16 with surrogates

### Object construction

* Whether `delete` of an `addr<T>` from `new<T>(n)` should destroy all `n` elements (it cannot know `n` without a header)

### Fixed point

* Saturating arithmetic (clamping instead of wrapping). Library code cannot yet name a format's largest or smallest value generically; this needs either built-in constants such as `MAX`/`MIN` for numeric types, or a way to read and write a value's raw bits.

### Runtime

* More of the standard library (files are §42.1, standard input and output §42.2)

---

# 61. Example: Shape-Specialized Class

```text
struct Matrix<T><<int N>>
struct Matrix<T><<int N, int M>> {
    int flags;

    array<T><<N>> data<<int N>>;
    array<T><<N, M>> data<<int N, int M>>;

    public new() {
        flags = 0;
    }

    public int size<<int N>>() {
        return N;
    }

    public int size<<int N, int M>>() {
        return N * M;
    }

    public T get<<int N>>(int x) {
        return data[x];
    }

    public T get<<int N, int M>>(int x, int y) {
        return data[x][y];
    }

    public int getFlags() {
        return flags;
    }
}
```

`flags` and `getFlags()` are universal.

`data`, `size`, and `get` are shape-specific.

The class cannot additionally contain an unguarded `get()` because the name `get` is already guarded.

---

# 62. Example: Shape-Dependent Inheritance

```text
class Shape<<int KIND>>
    extends(Base)
    extends<<0>>(Circle)
    extends<<1>>(Square) {
    ...
}
```

The resulting inheritance relationships are:

```text
Shape<<0>>  -> Circle
Shape<<1>>  -> Square
Shape<<other>> -> Base
```

If no applicable parent exists, the parent is:

```text
Object
```

---

# 63. Example: Struct vs Class

A struct buffer:

```text
struct Buffer<<int N>> {
    array<uint<<8>>><<N>> data;
}
```

is a value, stored directly where it is declared:

```text
Buffer<<1024>> buffer;          // 1024 bytes, inline
Buffer<<1024>> copy = buffer;   // an independent copy
```

Its complete layout is known statically.

A buffer class:

```text
class SharedBuffer {
    ...
}
```

is a shared object, reached through a handle:

```text
SharedBuffer a = new SharedBuffer();
SharedBuffer b = a;             // the same object
```

The runtime may move the object without invalidating the handle.

---

# 64. Conceptual Architecture

The language can be viewed as four layers:

```text
                    Source Language
                           |
                           v
                 Generic / Shape System
                           |
                  compile-time instancing
                           |
                           v
                   Concrete Type System
                           |
              layout + overload resolution
                           |
                           v
                   JVM class files
                           |
                           v
                          JVM
```

The key boundary is between generic source code and concrete instantiated types.

The JVM never needs to understand generic parameters: each instance is a class of its own.

---

# 65. Summary

The language provides:

```text
Java-like classes
        +
compile-time instanced generics
        +
compile-time value parameters
        +
C-like manual memory
        +
bit-addressed fixed memory
        +
classes: shared references through opaque, movable handles
        +
shape-dependent members
        +
shape-dependent inheritance
        +
decorator-based operator overloading
        +
register-based bytecode
```

Its defining feature is the ability to treat compile-time values as part of a type's identity without requiring a separate template/metaprogramming language.

A class such as:

```text
Array<T><<int N>>
```

is not merely a generic runtime object with `N` stored somewhere.

Each instantiation is a concrete type:

```text
Array<int><<16>>
Array<int><<32>>
Array<int><<1024>>
```

with its own statically determined layout and members.

The language therefore aims to provide low-level control without requiring every compile-time specialization to be expressed through macros or an independent metaprogramming system.
