import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.example.sealmacros.MacroEntry;

import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.embed.SealEngine;
import io.github.thelinuxseal.seal.embed.SealScript;

/**
 * Without the game: the mod's Seal API compiles against Minecraft 26.3's classes, as the mod's
 * engine compiles it in the game; the macros in the folder given compile with it; and a macro
 * can't use Java's classes, the game's or the JDK's, itself.
 *
 * java -cp build/sealmacros.jar:MINECRAFT_JARS test/CheckMacros.java macros
 */
public class CheckMacros {
    public static void main(String[] args) throws Exception {
        // As MacroMod builds it, with the class path's loader for Fabric's: the mod's jar and the game's.
        SealEngine seal = SealEngine.builder()
                .library(MacroEntry.class.getClassLoader(), "sealmacros/api")
                .entry(MacroEntry.class)
                .build();
        int failed = 0;
        List<Path> macros;
        try (Stream<Path> files = Files.list(Path.of(args[0]))) {
            macros = files.filter(p -> p.toString().endsWith(".seal")).sorted().toList();
        }
        for (Path file : macros) {
            SealScript script = seal.compile(file.getFileName().toString(), Files.readString(file));
            if (!script.ok()) {
                failed++;
                script.errors().forEach(System.out::println);
            }
        }
        failed += denied(seal, "Files.seal", """
                import macro.*;
                import java.nio.file.Files;

                class Files extends(Macro) {
                    public void run() { }
                }
                """, "Files.seal:2:1: Java's java.nio.file.Files isn't one the host lets scripts use");
        failed += denied(seal, "Cheat.seal", """
                import macro.*;
                import net.minecraft.client.Minecraft;

                class Cheat extends(Macro) {
                    public void run() { }
                }
                """, "Cheat.seal:2:1: Java's net.minecraft.client.Minecraft isn't one the host lets scripts use");
        System.out.println(failed == 0
                ? "The API and " + macros.size() + " macros compile against Minecraft's classes; macros use no Java class themselves"
                : failed + " checks failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 0 if the macro doesn't compile, for the error expected; else 1, saying so. */
    private static int denied(SealEngine seal, String file, String code, String expected) {
        List<Diagnostic> errors = seal.compile(file, code).errors();
        if (!errors.isEmpty() && errors.get(0).toString().equals(expected)) {
            return 0;
        }
        System.out.println("expected " + expected + ", got " + errors);
        return 1;
    }
}
