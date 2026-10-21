#!/usr/bin/env bash
# Runs this folder's program, a window drawn with OpenGL through LWJGL (docs/JAVA.md), with LWJGL's
# jars on its class path. The first run downloads them from Maven Central into lib/: LWJGL itself,
# its GLFW (windows and keys) and OpenGL classes, and their natives for this computer.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
version=3.4.3
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) natives=natives-linux ;;
    Linux-aarch64) natives=natives-linux-arm64 ;;
    Darwin-x86_64) natives=natives-macos ;;
    Darwin-arm64) natives=natives-macos-arm64 ;;
    *) echo "LWJGL has no natives for $(uname -s) on $(uname -m) in this script" >&2; exit 1 ;;
esac

mkdir -p "$here/lib"
classpath=""
for module in lwjgl lwjgl-glfw lwjgl-opengl; do
    for jar in "$module-$version.jar" "$module-$version-$natives.jar"; do
        if [ ! -f "$here/lib/$jar" ]; then
            echo "Fetching $jar"
            curl -fsS -o "$here/lib/$jar.part" "https://repo1.maven.org/maven2/org/lwjgl/$module/$version/$jar"
            mv "$here/lib/$jar.part" "$here/lib/$jar"
        fi
        classpath="$classpath${classpath:+:}$here/lib/$jar"
    done
done

# Built into build/, then run by java, as a program using LWJGL ships: its classes and LWJGL's jars
# on the class path. LWJGL loads its natives and reads memory as Java 25 asks to be allowed, and
# GLFW wants the process's first thread on macOS.
"$here/../../seal" build -d "$here/build" --classpath "$classpath" "$here/Main.seal" "$here/Scene.seal"
options=(--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow)
if [ "$(uname -s)" = Darwin ]; then
    options+=(-XstartOnFirstThread)
fi
exec java "${options[@]}" -cp "$here/build:$classpath" Main
