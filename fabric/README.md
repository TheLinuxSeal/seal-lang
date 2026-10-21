# A Fabric mod in Seal

`sealmod/` is a Minecraft mod written in Seal (docs/JAVA.md, T5): `SealMod` implements Fabric's
`ModInitializer`, and registers `SealBlock`, a Seal class extending Minecraft's `Block`, through
Minecraft's own registries, under Mojang's names (Minecraft 26.x isn't obfuscated, and runs on
Java 25, as Seal's class files are). `resources/fabric.mod.json` tells Fabric about it.

Its mixin, `mixin/SettingsMixin.java`, is Java, as Mixin wants (docs/JAVA.md §11): it puts a call
to `Hooks.savingSettings`, Seal code, at the start of Minecraft's
`DedicatedServerSettings.forceSave()`. `seal build` compiles it with the mod, after the Seal
classes it calls; `resources/sealmod.mixins.json` names it, and `fabric.mod.json` names that.

```sh
./fabric/run.sh
```

downloads Fabric's server launcher, Minecraft 26.3's server and Fabric API into `fabric/server/`
(the first time), builds the mod with

```sh
seal build --library --jar mods/sealmod.jar --resources sealmod/resources --classpath MINECRAFT_JARS sealmod/src
```

and starts the server, which loads it, and runs the mixin as it saves its settings:

```text
Seal mod: registered sealmod:seal_block, 1 block, a block made in Seal
Seal mixin: Minecraft is saving server.properties, for the world "world" on port 25565
```

The server then stops at Minecraft's EULA, which the script doesn't accept for you: to run the
server on, read https://aka.ms/MinecraftEULA and set `eula=true` in `fabric/server/eula.txt`.
