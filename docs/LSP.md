# Seal's language server

**Status:** built (2026-11-12): errors and warnings as you type, hover, go to definition and a
file's outline, in any editor with a Language Server Protocol client. Not yet: completion, find
references, rename (§6).

`seal lsp` is the language server: an editor starts it and speaks the Language Server Protocol
(LSP) to it on its standard input and output. It checks Seal as it is typed, with the compiler's
own checker, so what it reports is what `seal check` would. Its log goes to standard error, which
editors show in an output panel.

```text
seal lsp [--classpath PATH] [--stdlib DIR] [--no-stdlib] [-W...]
```

The options are `seal check`'s, and hold for every program the editor opens: `--classpath` for
the jars a program's Java imports come from, `-Wall` or `-Wvar` for the warnings to show.

## 1. What it does

* **Errors and warnings**, for each file of the program, whether it is open or not: an error in a
  file that changes what another file can use shows up in that one. The checker reports each at
  a place, and the editor underlines the token there. Warnings are those the `-W` flags turn on,
  tagged with their flag. A file that doesn't parse shows its parse errors alone, since what
  checking makes of a line half written is noise.
* **Hover**: what a name is. A local variable or parameter shows its type, a `var`'s as checking
  found it; a method or field, its declaration and its doc comment; a type, its header and doc
  comment; a package, in an import or a `package` line, its name. `super` and `this` show their
  class, or the constructor `super(...)` calls; `case NORTH` the enum's constant; `Sample::twice`
  the method. A call shows the overload it resolved to. Java's classes and members show their
  declarations as Seal sees them: `public Set<Map.Entry<K, V>> entrySet()`.
* **Go to definition**: from a name to where it is declared, in the program's files or the
  standard library's: a local's declaration, a member's name, a type's header, the constructor a
  `new` calls, a record's component in its header. Java's classes have no source to go to.
* **Outline**: the file's type, its members and its nested types, from parsing the file alone,
  so it is there while the program has errors.

## 2. Which files are a program

A file's program is the folder its package starts in, and every `.seal` file under it, as
`seal check DIR` reads them: `src/` for `src/com/example/Main.seal` in `package com.example`, and
the file's own folder for one in no package. Every example in `examples/` is a program that way,
and so is a project laid out as Java's are.

* The text of open files is checked, saved or not; the rest is read from disk at each check, so
  changes made outside the editor count from the next one.
* Folders whose names start with a dot, and the build's (`out`, `build`, `classes`, `target`,
  `result`, `node_modules`), are left out, and so is the standard library's folder. At most 2000
  files are read, in case a file in no package is in a big folder.
* The `.java` files there are compiled as `seal check` compiles them (docs/JAVA.md §11), again when
  one of them changes, so that Seal's code sees their classes.
* A file of the standard library is checked as the library, with the rest of it.

## 3. Editors

### VSCodium and VS Code

The extension in `editors/vscode` starts the server for `.seal` files, besides highlighting them.
It is a small client written against VS Code's API alone, so there is nothing to install with it
(editors/README.md says how to link it in). Its settings:

| Setting | |
| ------- | - |
| `seal.path` | the seal command, `seal` on the PATH unless set: the repository's `seal` script, or `result/bin/seal` |
| `seal.serverArgs` | options for `seal lsp`: `["-Wall"]`, `["--classpath", "lib/fabric.jar"]` |

**Seal: Restart the Language Server** restarts it; changing a setting does too. Its log is in the
Output panel, under Seal.

### IntelliJ IDEA

LSP4IJ, Red Hat's free plugin (Settings → Plugins → Marketplace), runs any language server, in
IntelliJ IDEA Community as in Ultimate. With it installed: Settings → Languages &
Frameworks → Language Servers → `+`, with the name Seal and the command `seal lsp` (the full path
of `seal` if it isn't on the PATH), and under Mappings, the file name pattern `*.seal`. The
highlighting is the TextMate bundle's (editors/README.md).

### Kate

Kate's LSP Client plugin (Settings → Configure Kate → Plugins) runs servers by the highlighting a
file has. In its User Server Settings, `editors/kate/lsp.json` adds Seal's:

```json
{
  "servers": {
    "seal": {
      "command": ["seal", "lsp"],
      "url": "https://github.com/TheLinuxSeal/seal-lang/blob/main/docs/LSP.md",
      "highlightingModeRegex": "^Seal$"
    }
  }
}
```

With Kate's highlighting for Seal installed (editors/README.md), `.seal` files have it, and the
server starts with the first one opened.

### Others

Any LSP client runs it the same way: the command `seal lsp`, for files `*.seal`, the language id
`seal`. Neovim's, for one:

```lua
vim.filetype.add({ extension = { seal = 'seal' } })
vim.lsp.config('seal', { cmd = { 'seal', 'lsp' }, filetypes = { 'seal' } })
vim.lsp.enable('seal')
```

## 4. How it works

The server is the package `io.github.thelinuxseal.seal.lsp`: `LanguageServer` (the protocol, the
open documents, and when to check), `Program` (a program's files, and checking them), `Analysis`
(what one check found: hover, definitions, the outline) and `Json` (Seal has no dependencies, so it
reads and writes JSON itself).

* After each change the server waits for typing to pause for 250 milliseconds, then checks the
  whole program again, and sends each of its files' diagnostics. A request (hover, definition)
  that comes before then checks first, so its answer is about the text as it is. One thread does
  the checking and answers, in the order they came.
* `Checker.analyzeForTools` checks as `analyze` does, and keeps each body it checked: each
  expression's type, what each name, call and `.member` resolved to, the type of each local. The
  parser records where a `.member`'s name is (`Select.namePos`), since an expression's position
  is where it starts.
* For a place in a file, `Analysis` finds the name there among the file's tokens, then the nodes
  of the syntax tree that name is in: a `Name`, a `Select`, a declaration, a type. Locals are
  matched to their declarations by scope, as the checker does, while going over the file.
* The checker reports an error at a position; the editor underlines the token that starts there.

## 5. Testing it

`test/.../lsp/LspTest.java` runs the server in the test's JVM and drives it through pipes, as an
editor does: a program in a package, hover and definitions across files, typing an error in and
fixing it, a line half written, an error in a file the editor hasn't open, the outline, shutting
down. `editors/vscode/extension.js` was run against a stand-in for VS Code's API, and the server
with Neovim's own client.

## 6. Not yet

* Completion, which needs what code half written means: `a.` doesn't parse, so there is nothing
  for `a` to be the type of yet. It takes a parser that keeps what it can of a statement it can't
  finish, or the last check's types for the names before the dot.
* Find references and rename, which the checked bodies have what they need for.
* Signature help, inlay hints (a `var`'s type in the line), code actions (`-Wvar`'s fix: write the type).
