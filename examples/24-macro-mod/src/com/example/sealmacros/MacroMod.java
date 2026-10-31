package com.example.sealmacros;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.embed.SealEngine;
import io.github.thelinuxseal.seal.embed.SealScript;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * Macros in Seal, for Minecraft's client: each file in config/macros is a macro, compiled as it is
 * when it is run, with the mod's Seal API (resources/sealmacros/api), and run in step with the
 * game (MacroRun).
 *
 * <pre>
 *   /macro run NAME     compiles NAME.seal and runs it; its errors go to the chat
 *   /macro stop [NAME]  stops one macro, or all of them
 *   /macro check        compiles every macro, to see their errors
 *   /macro list         the macros, and which are running
 * </pre>
 */
public final class MacroMod implements ClientModInitializer {

    /** Compiles off the game's thread, one script at a time. */
    private final ExecutorService compiler =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("Seal macro compiler").daemon().factory());
    /** The macros running, on the game's thread, in the order they started. */
    private final List<MacroRun> running = new ArrayList<>();
    /** The macros being compiled to run. */
    private final Set<String> starting = new HashSet<>();
    private Path folder;
    private CompletableFuture<SealEngine> engine;

    @Override
    public void onInitializeClient() {
        folder = FabricLoader.getInstance().getConfigDir().resolve("macros");
        // Building the engine reads Seal's library and compiles the API, which takes a moment: not on the game's thread.
        engine = CompletableFuture.supplyAsync(MacroMod::buildEngine, compiler);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> register(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /**
     * The engine every macro is compiled with: the mod's Seal API, from the mod's jar; macros
     * made as MacroEntry's; their output in the chat. Macros may use no Java class themselves.
     */
    private static SealEngine buildEngine() {
        try {
            return SealEngine.builder()
                    .library(MacroMod.class.getClassLoader(), "sealmacros/api")
                    .entry(MacroEntry.class)
                    .out(new PrintStream(new ChatLines(ChatFormatting.GRAY), true, StandardCharsets.UTF_8))
                    .err(new PrintStream(new ChatLines(ChatFormatting.RED), true, StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("macro")
                .then(ClientCommands.literal("run")
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                                .executes(c -> run(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(ClientCommands.literal("stop")
                        .executes(c -> stop(c, null))
                        .then(ClientCommands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(running.stream().map(MacroRun::name), b))
                                .executes(c -> stop(c, StringArgumentType.getString(c, "name")))))
                .then(ClientCommands.literal("check").executes(c -> check(c.getSource())))
                .then(ClientCommands.literal("list").executes(c -> list(c.getSource()))));
    }

    /** /macro run NAME: compiles the macro, then runs it from the next tick. */
    private int run(FabricClientCommandSource source, String name) {
        Path file = folder.resolve(name + ".seal");
        if (!Files.isRegularFile(file)) {
            source.sendError(Component.literal("No macro " + name + ": macros are .seal files in " + folder));
            return 0;
        }
        if (starting.contains(name) || running.stream().anyMatch(r -> r.name().equals(name))) {
            source.sendError(Component.literal(name + " is running already: /macro stop " + name + " stops it"));
            return 0;
        }
        starting.add(name);
        Minecraft client = source.getClient();
        compile(file).whenCompleteAsync((script, failed) -> {
            starting.remove(name);
            if (failed != null) {
                chat(Component.literal(name + " didn't compile: " + reason(failed)).withStyle(ChatFormatting.RED));
            } else if (!script.ok()) {
                showErrors(script);
            } else if (client.player == null) {
                chat(Component.literal(name + " can't run outside a world").withStyle(ChatFormatting.RED));
            } else {
                running.add(new MacroRun(name, script));
                chat(Component.literal("Running " + name).withStyle(ChatFormatting.GRAY));
            }
        }, client);
        return 1;
    }

    /** /macro stop [NAME] */
    private int stop(CommandContext<FabricClientCommandSource> c, String name) {
        List<MacroRun> stopping = running.stream().filter(r -> name == null || r.name().equals(name)).toList();
        if (stopping.isEmpty()) {
            c.getSource().sendError(Component.literal(name == null ? "No macro is running" : name + " isn't running"));
            return 0;
        }
        for (MacroRun run : stopping) {
            run.stop();
            running.remove(run);
            ended(run);
        }
        return stopping.size();
    }

    /** /macro check: compiles every macro, and shows the errors. */
    private int check(FabricClientCommandSource source) {
        List<String> names = names();
        if (names.isEmpty()) {
            source.sendFeedback(Component.literal("No macros: they are .seal files in " + folder));
            return 0;
        }
        Minecraft client = source.getClient();
        for (String name : names) {
            compile(folder.resolve(name + ".seal")).whenCompleteAsync((script, failed) -> {
                if (failed != null) {
                    chat(Component.literal(name + " didn't compile: " + reason(failed)).withStyle(ChatFormatting.RED));
                } else if (script.ok()) {
                    chat(Component.literal(name + " compiles").withStyle(ChatFormatting.GREEN));
                } else {
                    showErrors(script);
                }
            }, client);
        }
        return names.size();
    }

    /** /macro list */
    private int list(FabricClientCommandSource source) {
        List<String> names = names();
        source.sendFeedback(Component.literal(names.isEmpty() ? "No macros: they are .seal files in " + folder
                : "Macros: " + String.join(", ", names.stream().map(n -> isRunning(n) ? n + " (running)" : n).toList())));
        return names.size();
    }

    /** Each tick of the game's: each macro runs its turn, and those that ended are told of. */
    private void tick(Minecraft client) {
        if (running.isEmpty()) {
            return;
        }
        if (client.player == null) {
            // Out of the world: what the macros use is gone.
            for (MacroRun run : running) {
                run.stop();
            }
            running.clear();
            return;
        }
        if (client.isPaused()) {
            return; // they wait for the game
        }
        for (MacroRun run : List.copyOf(running)) {
            if (!run.tick()) {
                running.remove(run);
                ended(run);
            }
        }
    }

    private void ended(MacroRun run) {
        String failure = run.failure();
        if (failure != null) {
            chat(Component.literal(run.name() + ": " + failure).withStyle(ChatFormatting.RED));
        } else {
            chat(Component.literal(run.name() + (run.stopped() ? " stopped" : " finished")).withStyle(ChatFormatting.GRAY));
        }
    }

    /** A macro's file, compiled as it is now, off the game's thread. */
    private CompletableFuture<SealScript> compile(Path file) {
        return engine.thenApplyAsync(seal -> {
            try {
                return seal.compile(file.getFileName().toString(), Files.readString(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, compiler);
    }

    /** What went wrong, without the CompletableFuture's wrapping. */
    private static String reason(Throwable failed) {
        Throwable cause = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
        return cause instanceof UncheckedIOException io ? io.getCause().toString() : cause.getMessage();
    }

    private void showErrors(SealScript script) {
        for (Diagnostic d : script.errors()) {
            chat(Component.literal(d.toString()).withStyle(ChatFormatting.RED));
        }
    }

    private boolean isRunning(String name) {
        return running.stream().anyMatch(r -> r.name().equals(name));
    }

    /** The macros: the .seal files in config/macros, by name. */
    private List<String> names() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".seal"))
                    .map(n -> n.substring(0, n.length() - ".seal".length())).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Shows the player a line in the chat; on the game's thread. */
    private static void chat(Component text) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(text);
    }

    /**
     * The scripts' standard output, or error: each line, as it ends, shown in the chat. A macro
     * writes on its own thread, while the game's waits; the chat is the game's, so the line is
     * shown from the game's thread, as it goes on.
     */
    private static final class ChatLines extends OutputStream {
        private final ChatFormatting color;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        ChatLines(ChatFormatting color) {
            this.color = color;
        }

        @Override
        public synchronized void write(int b) {
            if (b != '\n') {
                line.write(b);
                return;
            }
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            Minecraft.getInstance().execute(() -> chat(Component.literal(text).withStyle(color)));
        }
    }
}
