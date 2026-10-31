import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.example.sealmacros.MacroEntry;
import com.example.sealmacros.MacroRun;

import io.github.thelinuxseal.seal.embed.SealEngine;
import io.github.thelinuxseal.seal.embed.SealScript;

/**
 * Without the game: MacroRun takes turns with the game's thread, which this test's thread plays,
 * calling tick() as the game does each tick. The macros use a small API of their own here, which
 * logs what they do, at the tick they do it; the mod's touches the game.
 *
 * java -cp build/sealmacros.jar:MINECRAFT_JARS test/MacroRunTest.java
 */
public class MacroRunTest {
    private static int failures;
    private static int passed;

    private static final Map<String, String> API = Map.of("macro/Macro.seal", """
            package macro;

            import com.example.sealmacros.MacroEntry;
            import com.example.sealmacros.MacroRun;
            import seal.java.*;

            abstract class Macro implements(MacroEntry) {
                private MacroRun macro;

                public abstract void run();

                public void start(MacroRun given) {
                    macro = given;
                    run();
                }

                public void waitTicks(int ticks) { macro.waitTicks(JavaInt.of(ticks)); }

                public int ticks() { return (int) macro.ticks(); }

                public void log(string text) { System.stdout.println("" + ticks() + " " + text); }
            }
            """);

    private static final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private static SealEngine seal;

    public static void main(String[] args) throws Exception {
        seal = SealEngine.builder().library(API).entry(MacroEntry.class)
                .out(new PrintStream(out, true, StandardCharsets.UTF_8)).build();

        // It runs only in its turns, and waits as long as it asks.
        MacroRun waits = run("Waits", """
                    public void run() {
                        log("start");
                        waitTicks(3);
                        log("three later");
                        waitTicks(0);
                        log("none later");
                        waitTicks(1);
                        log("end");
                    }
                """);
        check(output().isEmpty(), "nothing runs before the first tick");
        List<Boolean> goesOn = ticks(waits, 6);
        check(goesOn.equals(List.of(true, true, true, true, false, false)), "it goes on until it ends " + goesOn);
        check(output().equals("0 start\n3 three later\n3 none later\n4 end\n"), "each part at its tick: " + output());
        check(waits.failure() == null && !waits.stopped(), "it finished");

        // A macro that never waits is stopped, and the game goes on: after 100 ms, or a second in
        // its first turn, which loads its classes.
        MacroRun forever = run("Forever", """
                    public void run() {
                        waitTicks(1);
                        int n = 0;
                        while (true) {
                            n++;
                        }
                    }
                """);
        forever.tick();
        long start = System.nanoTime();
        boolean on = forever.tick();
        long ms = (System.nanoTime() - start) / 1_000_000;
        check(!on && ms >= MacroRun.TICK_BUDGET.toMillis() && ms < MacroRun.START_BUDGET.toMillis(),
                "a macro that never waits is stopped, in " + ms + " ms");
        check(forever.failure() != null && forever.failure().startsWith("stopped: it ran past the time it was given\n    at Forever.run (Forever.seal:7)")
                && forever.failure().endsWith("waitTicks lets the game go on)"), "its failure says so: " + forever.failure());
        MacroRun atOnce = run("AtOnce", """
                    public void run() {
                        while (true) { }
                    }
                """);
        start = System.nanoTime();
        on = atOnce.tick();
        ms = (System.nanoTime() - start) / 1_000_000;
        check(!on && ms >= MacroRun.START_BUDGET.toMillis() && ms < 5_000, "in its first turn, it is stopped in a second: " + ms + " ms");

        // Stopped while it waits, it does nothing more.
        MacroRun loops = run("Loops", """
                    public void run() {
                        while (true) {
                            waitTicks(1);
                            log("tick");
                        }
                    }
                """);
        ticks(loops, 3);
        loops.stop();
        check(output().equals("1 tick\n2 tick\n"), "stopped while it waits, it does nothing more: " + output());
        check(loops.stopped() && loops.failure() == null && !loops.tick(), "it has ended, stopped");

        // Stopped before it starts, it never does.
        MacroRun never = run("Never", """
                    public void run() {
                        log("started");
                    }
                """);
        never.stop();
        check(!never.tick() && output().isEmpty(), "stopped before its first tick, it never starts");

        // Its faults, as Seal tells them.
        MacroRun fails = run("Fails", """
                    public void run() {
                        waitTicks(2);
                        int zero = ticks() - 2;
                        log("" + (10 / zero));
                    }
                """);
        ticks(fails, 4);
        check(fails.failure() != null && fails.failure().contains("division by zero")
                && fails.failure().contains("at Fails.run (Fails.seal:7)"), "a fault, with its line: " + fails.failure());

        // A file whose class isn't a macro.
        SealScript plain = seal.compile("Plain.seal", "class Plain {\n}\n");
        MacroRun notMacro = new MacroRun("Plain", plain);
        notMacro.tick();
        check(notMacro.failure() != null && notMacro.failure().startsWith("Plain.seal has no macro"), "not a macro: " + notMacro.failure());

        System.out.println("MacroRunTest: " + passed + " passed, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** A run of a macro whose class is the body given, compiled with the test's API. */
    private static MacroRun run(String name, String body) {
        out.reset();
        SealScript script = seal.compile(name + ".seal", "import macro.*;\n\nclass " + name + " extends(Macro) {\n" + body + "}\n");
        if (!script.ok()) {
            throw new IllegalStateException(script.errors().toString());
        }
        return new MacroRun(name, script);
    }

    /** n ticks of the game's: whether the macro went on after each. */
    private static List<Boolean> ticks(MacroRun run, int n) {
        Boolean[] on = new Boolean[n];
        for (int i = 0; i < n; i++) {
            on[i] = run.tick();
        }
        return List.of(on);
    }

    private static String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
