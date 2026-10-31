# Macros in Seal, for Minecraft

A Fabric mod for Minecraft 26.3's client, written in Java, that runs macros written in Seal: Seal
embedded in a Java program (docs/EMBED.md). A macro is a file in `config/macros/`:

```java
import macro.*;

// Walks a square: a second forward, then a quarter turn right, four times.
class Square extends(Macro) {
    public void run() {
        look(Math.round(yaw() / 90) * 90, 0); // straight along the blocks
        for (int side = 0; side < 4; side++) {
            hold(Key.FORWARD, 20);
            turn(90, 0);
            waitTicks(5);
        }
    }
}
```

and `/macro run Square` runs it. It is written straight through, waits and all: `hold` presses the
key, waits 20 ticks while the game goes on, and lets go.

| Command | |
| ------- | - |
| `/macro run NAME` | compiles `config/macros/NAME.seal` as it is now, and runs it from the next tick; its errors go to the chat, at their lines |
| `/macro stop [NAME]` | stops a macro, or all of them |
| `/macro check` | compiles every macro, to see their errors |
| `/macro list` | the macros, and which are running |

## The API

`Macro` and `Key` are the mod's Seal API (`resources/sealmacros/api/macro/`), which every macro is
compiled with:

| | |
| - | - |
| `waitTicks(n)`, `waitSeconds(s)`, `ticks()` | wait while the game goes on (20 ticks a second); the ticks since the macro started |
| `say(text)`, `command(text)`, `print(text)` | chat as the player, run a command as the player, show text to the player alone |
| `press(key)`, `release(key)`, `hold(key, ticks)`, `click(key)` | the player's own bindings for `Key.FORWARD`, `BACK`, `LEFT`, `RIGHT`, `JUMP`, `SNEAK`, `SPRINT`, `ATTACK`, `USE`, `DROP`, `SWAP_HANDS`; a held key is let go as the macro ends |
| `look(yaw, pitch)`, `turn(right, down)`, `yaw()`, `pitch()` | where the player looks, in degrees |
| `x()`, `y()`, `z()`, `onGround()`, `health()`, `food()`, `slot()`, `selectSlot(n)` | where the player is, and how; the hotbar's slots are 1 to 9, as their keys |

`System.stdout.println` shows a line in the chat too, and `System.stderr`'s in red.

The API is Seal over Minecraft's own classes: `print` is
`Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(...))`. A macro
uses Minecraft through it alone: the mod lets macros use no Java class themselves
(docs/EMBED.md §5), so a macro someone shares can't read your files or reach into the game.

## How it runs

* **Compiled in the game.** Each `/macro run` compiles the macro's file with the API
  (`SealEngine.compile`), on a thread of the mod's, not the game's: a macro edited while the game
  runs is run as it is now. Each run is a program of its own, with its own static fields.
* **In step with the game** (`MacroRun`). A macro runs on a virtual thread of its own, but only
  while the game's thread waits for it: each tick, the game's thread hands the macro its turn, and
  the macro runs until it waits for ticks, then hands it back. So a macro uses the game's objects as
  the game's own code does, never at the same time as the game.
* **Stopped when it must be.** A macro that runs 100 ms without waiting is stopped where it is, and
  the game goes on (`limitTime`, docs/EMBED.md §6); its first turn, which loads its classes, may
  take a second. `/macro stop` stops one while it waits, and it does nothing more. Leaving the world
  stops them all; pausing the game pauses them.
* **Errors in the chat**: a compile error at its line and column, and a fault the macro didn't
  catch with the lines it happened at, the API's too (`hld` for `hold`, and a `selectSlot(10)`):

  ```text
  Square.seal:8:13: unknown method hld
  ```
  ```text
  Square: uncaught IllegalArgumentException: no hotbar slot 10: they are 1 to 9
      at macro.Macro.selectSlot (Macro.seal:158)
      at Square.run (Square.seal:11)
      at macro.Macro.start (Macro.seal:30)
  ```

The mod carries Seal inside its jar (`seal.jar`, which `jar.sh` builds), with Seal's standard
library and the API as resources.

## Building it

```sh
./examples/24-macro-mod/build.sh
```

downloads Minecraft 26.3's client and its libraries from Mojang, and Fabric's loader and Fabric
API, into `minecraft/` in this folder (the first time): the mod compiles against them. It writes
`build/sealmacros.jar`, then checks it without the game:

```text
Built build/sealmacros.jar
The API and 3 macros compile against Minecraft's classes; macros use no Java class themselves
MacroRunTest: 12 passed, 0 failed
```

## Running it

In a Minecraft 26.3 installation with Fabric's loader (from Fabric's installer, or a launcher
that sets it up):

1. put `build/sealmacros.jar` and Fabric API (`minecraft/fabric-api-*.jar`) in its `mods/`;
2. put the macros in its `config/macros/`: `Hello.seal`, `Square.seal`, and `AutoEat.seal`, which
   eats what you hold whenever you're hungry until `/macro stop AutoEat`;
3. start the game, join a world, and `/macro run Hello`.
