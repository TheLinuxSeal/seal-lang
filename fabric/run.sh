#!/usr/bin/env bash
# Builds the Seal mod in sealmod/, with its mixin in Java, and runs it in a Fabric server for Minecraft
# 26.3 (docs/JAVA.md, T5 and §11).
#
# The first run downloads Fabric's server launcher, Minecraft's server (through the launcher) and
# Fabric API into fabric/server/. Minecraft's server then stops, as its EULA (eula.txt) isn't accepted:
# Fabric has already loaded the mod by then, which prints what it registered. To run the server on,
# read https://aka.ms/MinecraftEULA and set eula=true in fabric/server/eula.txt yourself.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
minecraft=26.3
server="$here/server"
mkdir -p "$server/mods"
cd "$server"

if [ ! -f fabric-server-launch.jar ]; then
    loader=$(curl -fsS "https://meta.fabricmc.net/v2/versions/loader/$minecraft" \
        | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['loader']['version'])")
    installer=$(curl -fsS https://meta.fabricmc.net/v2/versions/installer \
        | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['version'])")
    echo "Fabric loader $loader, installer $installer"
    curl -fsS -o fabric-server-launch.jar \
        "https://meta.fabricmc.net/v2/versions/loader/$minecraft/$loader/$installer/server/jar"
fi
if [ ! -f "versions/$minecraft/server-$minecraft.jar" ]; then
    echo "Fetching Minecraft $minecraft's server (it stops at the EULA)"
    java -jar fabric-server-launch.jar nogui >/dev/null 2>&1 || true
fi
if ! ls mods/fabric-api-*.jar >/dev/null 2>&1; then
    api=$(curl -fsS https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml \
        | grep -o "<version>[^<]*+$minecraft</version>" | tail -1 | sed 's/<[^>]*>//g')
    echo "Fabric API $api"
    curl -fsS -o "mods/fabric-api-$api.jar" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$api/fabric-api-$api.jar"
fi

# The mod compiles against Minecraft's own classes, under their own names (Minecraft is no longer
# obfuscated), with its libraries and Fabric's loader, for ModInitializer and Mixin's annotations.
classpath="versions/$minecraft/server-$minecraft.jar:$(find libraries -name '*.jar' | sort | tr '\n' ':')"
"$here/../seal" build --library --jar mods/sealmod.jar --resources "$here/sealmod/resources" \
    --classpath "$classpath" "$here/sealmod/src"
echo "Built mods/sealmod.jar; starting the server"
java -jar fabric-server-launch.jar nogui
