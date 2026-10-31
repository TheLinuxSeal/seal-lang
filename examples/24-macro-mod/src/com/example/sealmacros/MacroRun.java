package com.example.sealmacros;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

import com.mojang.blaze3d.platform.InputConstants;

import io.github.thelinuxseal.seal.embed.SealException;
import io.github.thelinuxseal.seal.embed.SealScript;
import net.minecraft.client.KeyMapping;

/**
 * One macro running. It has a virtual thread of its own, but runs only while the game's thread
 * waits for it: each tick, the game's thread hands it the turn ({@link #tick}), and it runs until
 * it waits for ticks ({@link #waitTicks}) or ends, then hands the turn back. So a macro uses the
 * game's objects as the game's own code does, never at the same time as the game, and is written
 * straight through, waits and all.
 *
 * The first methods are the macro's, which the Seal API calls on the macro's thread; from
 * {@link #tick} on, they are the mod's, on the game's thread.
 */
public final class MacroRun {

    /** How long a macro may run without waiting for a tick: past it, it is stopped, and the game goes on. */
    public static final Duration TICK_BUDGET = Duration.ofMillis(100);
    /** And its first turn, which loads its classes, and Minecraft's they use that the game hasn't yet. */
    public static final Duration START_BUDGET = Duration.ofSeconds(1);

    private final String name;
    private final SealScript script;
    private final Semaphore macroTurn = new Semaphore(0);
    private final Semaphore gameTurn = new Semaphore(0);
    private Thread thread;

    // The two threads take turns through the semaphores, which order what each wrote before the
    // other reads it: these need no more.
    private final Set<KeyMapping> held = new LinkedHashSet<>();
    private int waiting;
    private long ticks;
    private boolean ended;
    private boolean stopped;
    private String failure;

    /** A run of the script's macro, which starts at the next {@link #tick}. */
    public MacroRun(String name, SealScript script) {
        this.name = name;
        this.script = script;
    }

    /** The macro's name: its file's, without .seal. */
    public String name() {
        return name;
    }

    // The macro's side.

    /** Waits this many ticks (20 a second) while the game goes on: none, for 0, or once stopped. */
    public void waitTicks(int n) {
        if (n <= 0 || stopped) {
            return;
        }
        waiting = n - 1;
        gameTurn.release();
        macroTurn.acquireUninterruptibly();
        // Stopped meanwhile, the macro stops at its next step in Seal's code (docs/EMBED.md §6).
    }

    /** The ticks since the macro started: 0 as it starts, 20 a second after. */
    public long ticks() {
        return ticks;
    }

    /** Holds a key down, as the player would, until it is released or the macro ends. */
    public void press(KeyMapping key) {
        key.setDown(true);
        held.add(key);
    }

    /** Lets go of a key. */
    public void release(KeyMapping key) {
        key.setDown(false);
        held.remove(key);
    }

    /** Clicks a key once, as attacking or using an item at once wants, where holding it repeats. */
    public void click(KeyMapping key) {
        KeyMapping.click(InputConstants.getKey(key.saveString()));
    }

    // The mod's side, on the game's thread.

    /**
     * Each tick: the macro runs until it waits or ends, unless it is waiting still. Whether it
     * goes on; once it has ended, its keys are released, and {@link #failure} says whether it failed.
     */
    public boolean tick() {
        if (ended) {
            return false;
        }
        if (waiting > 0) {
            waiting--;
            ticks++;
            return true;
        }
        if (thread == null) {
            thread = Thread.ofVirtual().name("Seal macro " + name).start(this::body);
            script.limitTime(START_BUDGET);
        } else {
            script.limitTime(TICK_BUDGET);
        }
        macroTurn.release();
        gameTurn.acquireUninterruptibly();
        ticks++;
        if (ended) {
            for (KeyMapping key : held) {
                key.setDown(false);
            }
            held.clear();
        }
        return !ended;
    }

    /**
     * Stops it: a macro waiting for ticks stops there, at once, and one that hasn't started
     * doesn't. After it, the macro has ended.
     */
    public void stop() {
        stopped = true;
        if (ended) {
            return;
        }
        if (thread == null) {
            ended = true;
            return;
        }
        script.stop();
        waiting = 0;
        tick();
    }

    /** Whether {@link #stop} stopped it. */
    public boolean stopped() {
        return stopped;
    }

    /**
     * How it failed, if it did, rather than finishing or being stopped: what Seal says of its
     * fault, with the lines it happened at; or null.
     */
    public String failure() {
        return stopped ? null : failure;
    }

    /** The macro's thread: it waits for its first turn, then makes the macro and runs it. */
    private void body() {
        macroTurn.acquireUninterruptibly();
        try {
            List<MacroEntry> macros = script.create(MacroEntry.class);
            if (macros.size() == 1) {
                macros.get(0).start(this);
            } else {
                failure = name + ".seal has " + (macros.isEmpty() ? "no macro" : macros.size() + " macros")
                        + ": a macro's file has one class, which extends Macro (import macro.*;)";
            }
        } catch (RuntimeException | Error e) {
            // A fault the macro didn't catch, as Seal tells it: a Seal exception, a trap, or Java's.
            SealException fault = e instanceof SealException s ? s : new SealException(e);
            failure = fault.getMessage();
            if (fault.stopped()) {
                failure += "\n(a macro may run " + TICK_BUDGET.toMillis() + " ms at a time, and a second as it starts:"
                        + " waitTicks lets the game go on)";
            }
        } finally {
            ended = true;
            script.close();
            gameTurn.release();
        }
    }
}
