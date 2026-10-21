#!/usr/bin/env bash
# Builds the compiler and runs every test suite. Run from the project root.
set -u
cd "$(dirname "$0")"

rm -rf out
if ! javac -Xlint:all -d out $(find src test -name '*.java'); then
    echo "build failed"
    exit 1
fi

suites=(
    io.github.thelinuxseal.seal.lexer.LexerTest
    io.github.thelinuxseal.seal.parser.ParserTest
    io.github.thelinuxseal.seal.check.CheckerTest
    io.github.thelinuxseal.seal.vm.VMTest
    io.github.thelinuxseal.seal.RunTest
    io.github.thelinuxseal.seal.LanguageTest
    io.github.thelinuxseal.seal.RefCountTest
    io.github.thelinuxseal.seal.FixedPointTest
    io.github.thelinuxseal.seal.StdlibTest
    io.github.thelinuxseal.seal.CliTest
    io.github.thelinuxseal.seal.ExamplesTest
    io.github.thelinuxseal.seal.FuzzTest
    io.github.thelinuxseal.seal.JvmTest
    io.github.thelinuxseal.seal.JavaInteropTest
)

failed=0
for suite in "${suites[@]}"; do
    result=$(java -cp out "$suite" 2>&1)
    status=$?
    printf "%-24s %s\n" "${suite#io.github.thelinuxseal.seal.}" "$(echo "$result" | grep -E '^[0-9]+ passed' | tail -1)"
    if [ $status -ne 0 ]; then
        echo "$result" | grep -v -E '^[0-9]+ passed'
        failed=1
    fi
done

if [ $failed -ne 0 ]; then
    echo "SOME TESTS FAILED"
    exit 1
fi
echo "all suites passed"
