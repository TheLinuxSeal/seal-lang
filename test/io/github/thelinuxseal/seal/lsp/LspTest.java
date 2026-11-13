package io.github.thelinuxseal.seal.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * `seal lsp` (docs/LSP.md), as an editor drives it: the server runs in this JVM, and the test
 * speaks the Language Server Protocol to it through pipes.
 */
public final class LspTest {
    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static final String MAIN = """
            package com.example;

            import com.example.util.Counter;

            class Main {
                static int main() {
                    Counter c = new Counter();
                    c.bump(2);
                    var total = c.value();
                    return total;
                }
            }
            """;

    private static final String COUNTER = """
            package com.example.util;

            /** Counts up. */
            class Counter {
                private int n;

                /** Adds {@code by} to the count. */
                public void bump(int by) { n += by; }

                public int value() { return n; }
            }
            """;

    public static void main(String[] args) throws Exception {
        json();
        changes();
        Path dir = Files.createTempDirectory("seal-lsp-test");
        Path main = dir.resolve("src/com/example/Main.seal");
        Path counter = dir.resolve("src/com/example/util/Counter.seal");
        Files.createDirectories(counter.getParent());
        Files.writeString(main, MAIN);
        Files.writeString(counter, COUNTER);
        String mainUri = main.toUri().toString();
        String counterUri = counter.toUri().toString();

        try (Session s = new Session("-Wvar")) {
            Object init = s.request("initialize", Json.obj("processId", null, "capabilities",
                    Json.obj("textDocument", Json.obj("documentSymbol", Json.obj("hierarchicalDocumentSymbolSupport", true)))));
            check(Boolean.TRUE.equals(Json.get(init, "result", "capabilities", "hoverProvider"))
                    && Boolean.TRUE.equals(Json.get(init, "result", "capabilities", "definitionProvider"))
                    && Json.integer(init, "result", "capabilities", "textDocumentSync", "change") == 1,
                    "initialize says what the server does: " + Json.write(init));
            s.notify("initialized", Json.obj());
            s.notify("textDocument/didOpen", Json.obj("textDocument", Json.obj("uri", mainUri, "languageId", "seal",
                    "version", 1, "text", MAIN)));

            // The program is the folder its package starts in: src, whose Counter Main imports.
            List<?> ds = s.diagnostics(mainUri, any -> true);
            check(ds.size() == 1 && Json.integer(ds.get(0), "severity") == 2 && "-Wvar".equals(Json.string(ds.get(0), "code"))
                    && Json.integer(ds.get(0), "range", "start", "line") == 8 && Json.integer(ds.get(0), "range", "start", "character") == 8
                    && Json.integer(ds.get(0), "range", "end", "character") == 11,
                    "the -Wvar warning, on var: " + Json.write(ds));

            Object hover = s.request("textDocument/hover", at(mainUri, 7, 11));
            String shown = Json.string(hover, "result", "contents", "value");
            check(shown != null && shown.contains("public void bump(int by)") && shown.contains("Adds `by` to the count.")
                    && shown.contains("in com.example.util.Counter"), "hover over a call shows the method and its doc: " + shown);
            Object def = s.request("textDocument/definition", at(mainUri, 7, 11));
            check(counterUri.equals(Json.string(def, "result", "uri")) && Json.integer(def, "result", "range", "start", "line") == 7
                    && Json.integer(def, "result", "range", "start", "character") == 16,
                    "a call goes to the method's name, in another file: " + Json.write(def));

            shown = Json.string(s.request("textDocument/hover", at(mainUri, 8, 13)), "result", "contents", "value");
            check(shown != null && shown.contains("int total"), "a var's hover has its type: " + shown);
            def = s.request("textDocument/definition", at(mainUri, 9, 16));
            check(mainUri.equals(Json.string(def, "result", "uri")) && Json.integer(def, "result", "range", "start", "line") == 8
                    && Json.integer(def, "result", "range", "start", "character") == 12, "a local goes to where it is declared: " + Json.write(def));
            def = s.request("textDocument/definition", at(mainUri, 2, 27));
            check(counterUri.equals(Json.string(def, "result", "uri")) && Json.integer(def, "result", "range", "start", "line") == 3,
                    "an import goes to the type: " + Json.write(def));
            shown = Json.string(s.request("textDocument/hover", at(mainUri, 2, 13)), "result", "contents", "value");
            check(shown != null && shown.contains("package com.example"), "an import's package hovers as one: " + shown);
            Object nothing = s.request("textDocument/hover", at(mainUri, 4, 0));
            check(((Map<?, ?>) nothing).containsKey("result") && Json.get(nothing, "result") == null, "hovering nothing is null: " + Json.write(nothing));

            Object symbols = s.request("textDocument/documentSymbol", Json.obj("textDocument", Json.obj("uri", counterUri)));
            List<?> top = (List<?>) Json.get(symbols, "result");
            List<?> members = top.size() == 1 ? (List<?>) Json.get(top.get(0), "children") : List.of();
            check(top.size() == 1 && "Counter".equals(Json.string(top.get(0), "name")) && Json.integer(top.get(0), "kind") == Analysis.CLASS
                    && members.size() == 3 && "bump".equals(Json.string(members.get(1), "name"))
                    && Json.integer(members.get(1), "kind") == Analysis.METHOD && Json.integer(members.get(1), "selectionRange", "start", "line") == 7
                    && Json.integer(members.get(1), "range", "start", "line") == 7 && Json.integer(top.get(0), "range", "end", "line") == 10,
                    "the outline of a file not open: " + Json.write(symbols));

            // Typing: an error, a line half written, and back.
            s.change(mainUri, 2, MAIN.replace("c.bump(2);", "c.bump(\"two\");"));
            ds = s.diagnostics(mainUri, l -> l.size() == 2);
            check(ds.size() == 2 && Json.string(ds.get(0), "message").contains("argument 1 of bump") && Json.integer(ds.get(0), "severity") == 1,
                    "an error typed in: " + Json.write(ds));
            s.change(mainUri, 3, MAIN.replace("c.bump(2);", "c.bump(2"));
            ds = s.diagnostics(mainUri, l -> l.size() == 1 && Json.integer(l.get(0), "severity") == 1);
            check(ds.size() == 1 && Json.string(ds.get(0), "message").contains("expected ')'"),
                    "a file that doesn't parse has its parse errors alone: " + Json.write(ds));
            s.change(mainUri, 4, MAIN);
            ds = s.diagnostics(mainUri, l -> l.size() == 1 && Json.integer(l.get(0), "severity") == 2);
            check(ds.size() == 1, "fixed, the warning alone is left: " + Json.write(ds));

            // Another file's errors, in a file the editor hasn't open: what the program has, the editor shows.
            s.change(mainUri, 5, MAIN.replace("c.value()", "c.size()"));
            ds = s.diagnostics(mainUri, l -> l.size() == 1 && Json.integer(l.get(0), "severity") == 1);
            check(ds.size() == 1 && Json.string(ds.get(0), "message").equals("Counter has no method size"),
                    "a method Counter hasn't (and var total has no type, so no -Wvar): " + Json.write(ds));
            Files.writeString(counter, COUNTER.replace("public int value()", "public int size()").replace("return n;", "return m;"));
            s.notify("textDocument/didSave", Json.obj("textDocument", Json.obj("uri", mainUri)));
            List<?> other = s.diagnostics(counterUri, l -> !l.isEmpty());
            check(other.size() == 1 && Json.string(other.get(0), "message").contains("unknown name m"),
                    "a saved file's error is shown for it, unopened: " + Json.write(other));
            Files.writeString(counter, COUNTER);

            Object unknown = s.request("textDocument/formatting", Json.obj("textDocument", Json.obj("uri", mainUri)));
            check(Json.integer(unknown, "error", "code") == -32601, "a request it doesn't do is an error: " + Json.write(unknown));

            s.notify("textDocument/didClose", Json.obj("textDocument", Json.obj("uri", mainUri)));
            ds = s.diagnostics(counterUri, List::isEmpty);
            check(ds.isEmpty(), "closing the program's last file clears what it showed");

            Object down = s.request("shutdown", null);
            check(((Map<?, ?>) down).containsKey("result"), "shutdown answers: " + Json.write(down));
            s.notify("exit", null);
            check(s.exitCode() == 0, "exit after shutdown is 0: " + s.exitCode());
        }

        try (Session s = new Session()) {
            s.request("initialize", Json.obj("capabilities", Json.obj()));
            s.notify("textDocument/didOpen", Json.obj("textDocument", Json.obj("uri", counterUri, "languageId", "seal",
                    "version", 1, "text", COUNTER)));
            Object symbols = s.request("textDocument/documentSymbol", Json.obj("textDocument", Json.obj("uri", counterUri)));
            List<?> flat = (List<?>) Json.get(symbols, "result");
            check(flat.size() == 4 && "bump".equals(Json.string(flat.get(2), "name"))
                    && "Counter".equals(Json.string(flat.get(2), "containerName"))
                    && counterUri.equals(Json.string(flat.get(2), "location", "uri")),
                    "an editor that wants a list of symbols gets one: " + Json.write(symbols));
            s.notify("exit", null);
            check(s.exitCode() == 1, "exit without shutdown is 1: " + s.exitCode());
        }

        check(LanguageServer.run(new String[] {"--bogus"}, InputStream.nullInputStream(), OutputStream.nullOutputStream(),
                new PrintStream(new ByteArrayOutputStream())) == 2, "an unknown option stops it");
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }

        System.out.println(passed + " passed, " + failures.size() + " failed");
        failures.forEach(x -> System.out.println("FAIL: " + x));
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void json() {
        Object v = Json.parse(" {\"a\": [1, -2.5e1, true, false, null, \"x\\n\\u00e9\\\"\"], \"b\": {}} ");
        check(Json.write(v).equals("{\"a\":[1,-25.0,true,false,null,\"x\\né\\\"\"],\"b\":{}}"), "JSON reads and writes: " + Json.write(v));
        check(Json.get(v, "a") instanceof List<?> l && l.get(0) instanceof Long, "a whole number is a long");
        for (String bad : List.of("{", "[1,]", "{\"a\" 1}", "\"\\q\"", "1 2", "")) {
            try {
                Json.parse(bad);
                check(false, "not JSON: " + bad);
            } catch (Json.Malformed e) {
                check(true, "");
            }
        }
    }

    private static void changes() {
        String text = "ab\ncd\nef";
        check(LanguageServer.applied(text, Json.obj("text", "new")).equals("new"), "a change of the whole text");
        Map<String, Object> range = Json.obj("start", Json.obj("line", 1, "character", 1), "end", Json.obj("line", 2, "character", 0));
        check(LanguageServer.applied(text, Json.obj("range", range, "text", "X")).equals("ab\ncXef"),
                "a change of a range: " + LanguageServer.applied(text, Json.obj("range", range, "text", "X")));
        Map<String, Object> end = Json.obj("start", Json.obj("line", 2, "character", 2), "end", Json.obj("line", 2, "character", 2));
        check(LanguageServer.applied(text, Json.obj("range", end, "text", "!")).equals("ab\ncd\nef!"), "a change at the end");
    }

    private static Map<String, Object> at(String uri, int line, int character) {
        return Json.obj("textDocument", Json.obj("uri", uri), "position", Json.obj("line", line, "character", character));
    }

    private static void check(boolean ok, String description) {
        if (ok) {
            passed++;
        } else {
            failures.add(description);
        }
    }

    /** A server in this JVM, and the messages it sends. */
    private static final class Session implements AutoCloseable {
        final PipedOutputStream toServer = new PipedOutputStream();
        final LinkedBlockingQueue<Object> received = new LinkedBlockingQueue<>();
        final List<Object> kept = new ArrayList<>();
        final ByteArrayOutputStream log = new ByteArrayOutputStream();
        final Thread server;
        final Thread reader;
        volatile int code = -1;
        int nextId = 1;

        Session(String... args) throws IOException {
            PipedInputStream serverIn = new PipedInputStream(toServer, 1 << 16);
            PipedOutputStream serverOut = new PipedOutputStream();
            PipedInputStream fromServer = new PipedInputStream(serverOut, 1 << 16);
            List<String> all = new ArrayList<>(List.of("--stdlib", "stdlib"));
            all.addAll(List.of(args));
            server = new Thread(() -> {
                try {
                    code = LanguageServer.run(all.toArray(String[]::new), serverIn, serverOut,
                            new PrintStream(log, true, StandardCharsets.UTF_8));
                    serverOut.close();
                } catch (IOException e) {
                    code = 99;
                }
            });
            server.start();
            reader = new Thread(() -> {
                try {
                    String body;
                    while ((body = LanguageServer.readMessage(fromServer)) != null) {
                        received.add(Json.parse(body));
                    }
                } catch (IOException e) {
                    // the server is gone
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        void notify(String method, Object params) throws IOException {
            write(Json.obj("jsonrpc", "2.0", "method", method, "params", params));
        }

        Object request(String method, Object params) throws Exception {
            int id = nextId++;
            Map<String, Object> m = Json.obj("jsonrpc", "2.0", "id", id, "method", method);
            m.put("params", params);
            write(m);
            return next(x -> Json.get(x, "id") instanceof Long l && l == id);
        }

        void change(String uri, int version, String text) throws IOException {
            notify("textDocument/didChange", Json.obj("textDocument", Json.obj("uri", uri, "version", version),
                    "contentChanges", List.of(Json.obj("text", text))));
        }

        /** The diagnostics next published for a file that {@code ok} takes. */
        List<?> diagnostics(String uri, Predicate<List<?>> ok) throws Exception {
            Object m = next(x -> "textDocument/publishDiagnostics".equals(Json.string(x, "method"))
                    && uri.equals(Json.string(x, "params", "uri")) && ok.test((List<?>) Json.get(x, "params", "diagnostics")));
            return Json.get(m, "params", "diagnostics") instanceof List<?> l ? l : List.of();
        }

        /** The next message that {@code want} takes, among those kept and those to come; null after 30 seconds. */
        Object next(Predicate<Object> want) throws InterruptedException {
            for (int i = 0; i < kept.size(); i++) {
                if (want.test(kept.get(i))) {
                    return kept.remove(i);
                }
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < end) {
                Object m = received.poll(100, TimeUnit.MILLISECONDS);
                if (m == null) {
                    continue;
                }
                if (want.test(m)) {
                    return m;
                }
                kept.add(m);
            }
            failures.add("no message came; those that did: " + kept.stream().map(Json::write).toList()
                    + "\nthe server's log:\n" + log.toString(StandardCharsets.UTF_8));
            return Json.obj();
        }

        void write(Map<String, Object> message) throws IOException {
            byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
            toServer.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            toServer.write(body);
            toServer.flush();
        }

        int exitCode() throws InterruptedException {
            server.join(TimeUnit.SECONDS.toMillis(30));
            return code;
        }

        @Override
        public void close() throws IOException {
            toServer.close();
            try {
                server.join(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
