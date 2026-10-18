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
    lang.lexer.LexerTest
    lang.parser.ParserTest
    lang.check.CheckerTest
    lang.vm.VMTest
    lang.RunTest
    lang.LanguageTest
    lang.RefCountTest
    lang.FixedPointTest
    lang.StdlibTest
    lang.CliTest
    lang.ExamplesTest
    lang.FuzzTest
)

failed=0
for suite in "${suites[@]}"; do
    result=$(java -cp out "$suite" 2>&1)
    status=$?
    printf "%-24s %s\n" "$suite" "$(echo "$result" | grep -E '^[0-9]+ passed' | tail -1)"
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
