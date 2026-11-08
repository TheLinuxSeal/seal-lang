# Seal in editors

Syntax highlighting for `.seal` files in three editors, from two grammars:

| Editor | Grammar | |
| ------ | ------- | - |
| VSCodium (and VS Code) | `vscode/`, a TextMate grammar in an extension | also comments, brackets and auto-closing quotes |
| IntelliJ IDEA (Community too) | the same `vscode/` folder, through the bundled TextMate Bundles plugin | |
| Kate (and KWrite, KDevelop) | `kate/seal.xml`, KDE's syntax format | also folding of braces and comments |

`Sample.seal` uses everything the grammars color, and compiles (`seal check editors/Sample.seal`):
open it to see the highlighting.

## Installing

**VSCodium**: link the extension into VSCodium's extensions folder, then restart it.

```sh
ln -s "$PWD/editors/vscode" ~/.vscode-oss/extensions/thelinuxseal.seal-0.1.0
```

For VS Code the folder is `~/.vscode/extensions/`.

**IntelliJ IDEA**: Settings → Editor → TextMate Bundles → `+`, and choose the `editors/vscode`
folder. `.seal` files open with it from then on.

**Kate**: copy the definition into KDE's folder for your own syntax files, then restart Kate.

```sh
mkdir -p ~/.local/share/org.kde.syntax-highlighting/syntax
cp editors/kate/seal.xml ~/.local/share/org.kde.syntax-highlighting/syntax/
```

Seal appears under Tools → Highlighting → Sources, and `.seal` files use it by themselves.

## What they color

* Keywords, with `if`, `for`, `return` and the like as control flow; the modifiers (`public`,
  `static`, `pure`, `const`); `new` and `delete`.
* Types: the primitives (`int`, `uint`, `bit`, `char`, `float`, `double`, `fixed`, `ufixed`, `addr`,
  `handle`, `void`), the built-in `string` and `array`, and names that start with a capital.
* Words Seal knows by where they are, only there:
  * `var` in a declaration (`var n = 1;`, but not `int var = 3;`);
  * `size` as a type (`size n`, `int<<size W>>`, but not `xs.size()`);
  * `record` declaring a record;
  * `yield` in a switch;
  * `as` in an import.
* Shapes' brackets `<<` and `>>`, apart from the shift `<<<`; `**`, `->`, `::` and `..`.
* Numbers (`1_000`, `0x1F`, `0b1010`, `2.5e3`), characters, strings, text blocks and their
  escapes; `@overload`, `@jvm` and the other decorators; `ALL_CAPS` constants; comments, and
  `/** ... */` as documentation.

The grammars read words, not Seal's syntax: the `>>` that closes two type arguments
(`List<List<int>>`) has a shape's color, and `<` and `>` around type arguments an operator's.

## Checking them

`EditorsTest` checks that both grammars have every keyword the lexer has, and the words above,
and that `Sample.seal` compiles. To see what a grammar makes of a file without an editor:

```sh
# Kate's, with KDE's own highlighter: each token's format under it
XDG_DATA_HOME=/tmp/seal-syntax ksyntaxhighlighter6 -s Seal -f ansi --syntax-trace format editors/Sample.seal
```

after copying `kate/seal.xml` into `/tmp/seal-syntax/org.kde.syntax-highlighting/syntax/`. The
TextMate grammar can be run the same way with `vscode-textmate`, the library VSCodium uses.
