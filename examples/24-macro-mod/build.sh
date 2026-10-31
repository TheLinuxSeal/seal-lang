#!/usr/bin/env bash
# Builds the macro mod: a Fabric mod for Minecraft 26.3's client, in Java, that runs macros written
# in Seal (docs/EMBED.md). It writes build/sealmacros.jar, with Seal (seal.jar) inside it.
#
# The first run downloads Minecraft 26.3's client and its libraries from Mojang, and Fabric's
# loader and Fabric API, into minecraft/ beside this script: the mod compiles against them. Then it
# checks the mod's Seal API and the macros in macros/ against the game's classes, and runs the
# macros' scheduling (MacroRun) through its paces without the game (test/).
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
minecraft=26.3
mc="$here/minecraft"
build="$here/build"
mkdir -p "$mc/libraries"
cd "$mc"

if [ ! -f "client-$minecraft.jar" ]; then
    echo "Fetching Minecraft $minecraft's client and its libraries"
    version=$(curl -fsS https://piston-meta.mojang.com/mc/game/version_manifest_v2.json \
        | python3 -c "import json,sys; print(next(v['url'] for v in json.load(sys.stdin)['versions'] if v['id'] == '$minecraft'))")
    curl -fsS -o version.json "$version"
    # The client, and the libraries its classes compile against: not the natives, which only run it.
    python3 - <<'EOF' > downloads.txt
import json
v = json.load(open("version.json"))
print(v["downloads"]["client"]["url"], "client.jar")
for lib in v["libraries"]:
    a = lib.get("downloads", {}).get("artifact")
    if a and "rules" not in lib and ":natives-" not in lib["name"]:
        print(a["url"], "libraries/" + a["path"])
EOF
    while read -r url path; do
        mkdir -p "$(dirname "$path")"
        curl -fsS -o "$path" "$url"
    done < downloads.txt
    mv client.jar "client-$minecraft.jar"
fi
if ! ls fabric-loader-*.jar >/dev/null 2>&1; then
    loader=$(curl -fsS "https://meta.fabricmc.net/v2/versions/loader/$minecraft" \
        | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['loader']['version'])")
    echo "Fabric loader $loader"
    curl -fsS -o "fabric-loader-$loader.jar" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-loader/$loader/fabric-loader-$loader.jar"
fi
if ! ls fabric-api-*.jar >/dev/null 2>&1; then
    api=$(curl -fsS https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml \
        | grep -o "<version>[^<]*+$minecraft</version>" | tail -1 | sed 's/<[^>]*>//g')
    echo "Fabric API $api"
    curl -fsS -o "fabric-api-$api.jar" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$api/fabric-api-$api.jar"
    # Its modules are jars inside it, which javac can't see there.
    rm -rf fabric-api && mkdir fabric-api
    (cd fabric-api && unzip -q -j "../fabric-api-$api.jar" 'META-INF/jars/*.jar')
fi

game="$mc/client-$minecraft.jar:$(find "$mc/libraries" -name '*.jar' | sort | tr '\n' ':')"
fabric="$(ls "$mc"/fabric-loader-*.jar):$(find "$mc/fabric-api" -name '*.jar' | sort | tr '\n' ':')"

# Seal, which the mod carries inside it: the compiler, its runtime and the standard library.
rm -rf "$build" && mkdir -p "$build/jar"
"$here/../../jar.sh" "$build/seal.jar" >/dev/null
(cd "$build/jar" && unzip -q "$build/seal.jar" -x 'META-INF/*')

# The mod's Java classes, and its resources: fabric.mod.json, and the Seal API with an index of its
# files, as SealEngine.Builder.library reads a library from a jar.
javac --release 25 -d "$build/jar" -cp "$build/seal.jar:$game:$fabric" $(find "$here/src" -name '*.java')
cp -r "$here/resources/." "$build/jar/"
api="$build/jar/sealmacros/api"
(cd "$here/resources/sealmacros/api" && find . -name '*.seal' | sed 's|^\./||' | LC_ALL=C sort) > "$api/index"
jar --create --file "$build/sealmacros.jar" -C "$build/jar" .
echo "Built build/sealmacros.jar"

# Without the game: the API and the macros compile against Minecraft's classes, and MacroRun takes
# turns with the game's thread as it should.
java -cp "$build/sealmacros.jar:$game:$fabric" "$here/test/CheckMacros.java" "$here/macros"
java -cp "$build/sealmacros.jar:$game:$fabric" "$here/test/MacroRunTest.java"
