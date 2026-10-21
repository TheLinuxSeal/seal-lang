# A Fabric mod in Seal

`sealmod/` is a Minecraft mod written in Seal (docs/JAVA.md, T5): `SealMod` implements Fabric's
`ModInitializer`, and registers `SealBlock`, a Seal class extending Minecraft's `Block`, through
Minecraft's own registries, under Mojang's names (Minecraft 26.x isn't obfuscated, and runs on
Java 25, as Seal's class files are). `resources/fabric.mod.json` tells Fabric about it.

```sh
./fabric/run.sh
```

downloads Fabric's server launcher, Minecraft 26.3's server and Fabric API into `fabric/server/`
(the first time), builds the mod with

```sh
seal build --library --jar mods/sealmod.jar --resources sealmod/resources --classpath MINECRAFT_JARS sealmod/src
```

and starts the server, which loads it:

```text
Seal mod: registered sealmod:seal_block, 1 block, a block made in Seal
```

The server then stops at Minecraft's EULA, which the script doesn't accept for you: to run the
server on, read https://aka.ms/MinecraftEULA and set `eula=true` in `fabric/server/eula.txt`.
