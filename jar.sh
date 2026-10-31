#!/usr/bin/env bash
# Builds seal.jar: the compiler, its runtime and the standard library in one jar. It runs as the
# seal script does (java -jar seal.jar run PATH...), and a Java program that embeds Seal puts it
# on its class path (docs/EMBED.md). Writes the jar given, or seal.jar beside this script.
set -eu
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
jar="${1:-$here/seal.jar}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

javac -d "$work/classes" $(find "$here/src" -name '*.java')
# The library goes in as resources, with an index of its files: a class loader can't list a jar's folders.
lib="$work/classes/io/github/thelinuxseal/seal/stdlib"
mkdir -p "$lib"
cp -r "$here/stdlib/." "$lib/"
(cd "$here/stdlib" && find . -name '*.seal' | sed 's|^\./||' | LC_ALL=C sort) > "$lib/index"
jar --create --file "$jar" --main-class io.github.thelinuxseal.seal.Main -C "$work/classes" .
echo "wrote $jar"
