package io.github.thelinuxseal.seal.lsp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.thelinuxseal.seal.Sources;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.Warning;

/**
 * `seal lsp`: the language server editors start to check Seal as it is typed (docs/LSP.md). It
 * speaks the Language Server Protocol on standard input and output: errors and warnings for
 * each file of the programs open, what a name is on hover, where it is declared, and a file's
 * outline. Its log goes to standard error.
 *
 * <pre>
 *   seal lsp [--classpath PATH] [--stdlib DIR] [--no-stdlib] [-W...]
 * </pre>
 *
 * The options are `seal check`'s, for every program the editor opens.
 */
public final class LanguageServer {

    /** How long typing has to pause before the program is checked again, in milliseconds. */
    static final long PAUSE = 250;

    private final InputStream in;
    private final OutputStream out;
    private final PrintStream log;
    private final Set<Warning> warn;
    private final List<Path> classPath;
    /** The standard library's folder, null when there is none to read (--no-stdlib). */
    private final Path stdlib;

    /** The documents open in the editor, by path. */
    private final Map<String, Doc> docs = new HashMap<>();
    private final Map<Path, Program> programs = new HashMap<>();
    private final Map<Program, ScheduledFuture<?>> pending = new HashMap<>();
    private final ScheduledExecutorService worker;
    private volatile boolean shutdown;
    private boolean hierarchicalSymbols;

    /** An open document: the URI the editor names it by, and its text. */
    private record Doc(String uri, String text, Program program) {
    }

    private LanguageServer(InputStream in, OutputStream out, PrintStream log, Set<Warning> warn, List<Path> classPath,
                           Path stdlib) {
        this.in = in;
        this.out = out;
        this.log = log;
        this.warn = warn;
        this.classPath = classPath;
        this.stdlib = stdlib;
        ScheduledThreadPoolExecutor w = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "seal-lsp");
            t.setDaemon(true);
            return t;
        });
        w.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.worker = w;
    }

    /** Runs the server until the editor says exit, or closes its input; 0 if it asked to shut down first. */
    public static int run(String[] args, InputStream in, OutputStream out, PrintStream err) throws IOException {
        Set<Warning> warn = EnumSet.noneOf(Warning.class);
        List<Path> classPath = new ArrayList<>();
        Path stdlib = null;
        boolean useStdlib = true;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ((a.equals("--classpath") || a.equals("-cp")) && i + 1 < args.length) {
                for (String entry : args[++i].split(java.io.File.pathSeparator)) {
                    if (!entry.isEmpty()) {
                        classPath.add(Path.of(entry).toAbsolutePath());
                    }
                }
            } else if (a.equals("--stdlib") && i + 1 < args.length) {
                stdlib = Path.of(args[++i]);
            } else if (a.equals("--no-stdlib")) {
                useStdlib = false;
            } else if (a.equals("-Wall")) {
                warn.addAll(Warning.all());
            } else if (a.startsWith("-W") && Warning.byFlag(a).isPresent()) {
                warn.add(Warning.byFlag(a).get());
            } else if (a.equals("--stdio")) {
                // what some editors add: standard input and output are the only way this server talks
            } else {
                err.println("seal lsp: unknown option " + a + "; it takes seal check's --classpath, --stdlib, --no-stdlib"
                        + " and -W flags");
                return 2;
            }
        }
        Path folder = null;
        if (useStdlib) {
            folder = stdlib != null ? stdlib : libraryFolder(err);
            if (folder == null || !Files.isDirectory(folder)) {
                err.println("seal lsp: standard library not found" + (folder == null ? "" : " at " + folder)
                        + "; use --stdlib DIR or --no-stdlib");
                return 2;
            }
            folder = folder.toAbsolutePath().normalize();
        }
        return new LanguageServer(in, out, err, warn, classPath, folder).serve();
    }

    /**
     * The standard library's folder: the compiler's ({@link Sources#stdlibFolder}), or seal.jar's
     * files copied to a temporary folder, so that the editor can open them; null if there is neither.
     */
    private static Path libraryFolder(PrintStream err) throws IOException {
        Path folder = Sources.stdlibFolder();
        if (folder != null) {
            return folder;
        }
        Map<String, String> bundled = Sources.bundled(LanguageServer.class.getClassLoader());
        if (bundled == null) {
            return Files.isDirectory(Path.of("stdlib")) ? Path.of("stdlib") : null;
        }
        Path tmp = Files.createTempDirectory("seal-lsp-");
        for (Map.Entry<String, String> e : bundled.entrySet()) {
            Path f = tmp.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // a temporary folder, which the system clears in the end
            }
        }));
        return tmp.resolve("stdlib");
    }

    private int serve() throws IOException {
        BufferedInputStream input = new BufferedInputStream(in);
        try {
            while (true) {
                String body = readMessage(input);
                if (body == null) {
                    break;
                }
                Object msg;
                try {
                    msg = Json.parse(body);
                } catch (Json.Malformed e) {
                    send(error(null, -32700, "not JSON: " + e.getMessage()));
                    continue;
                }
                String method = Json.string(msg, "method");
                if (method == null) {
                    continue; // a response; the server asks the editor nothing
                }
                if (method.equals("exit")) {
                    break;
                }
                Object id = Json.get(msg, "id");
                Object params = Json.get(msg, "params");
                worker.execute(() -> handle(method, id, params));
            }
        } finally {
            worker.shutdown();
            try {
                worker.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (Program p : programs.values()) {
                p.close();
            }
        }
        return shutdown ? 0 : 1;
    }

    // ================================================================ messages

    /** One message's JSON, after its headers; null at the end of the input. */
    static String readMessage(InputStream input) throws IOException {
        int length = -1;
        while (true) {
            String line = readLine(input);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                if (length >= 0) {
                    break;
                }
                continue; // blank lines between messages
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                length = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        byte[] body = input.readNBytes(length);
        if (body.length < length) {
            return null;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') {
                String s = line.toString(StandardCharsets.US_ASCII);
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            line.write(c);
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
    }

    private void send(Map<String, Object> message) {
        byte[] body = Json.write(message).getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            try {
                out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                log.println("seal lsp: can't write to the editor: " + e.getMessage());
            }
        }
    }

    private static Map<String, Object> response(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Json.obj("code", code, "message", message));
        return m;
    }

    private void notify(String method, Object params) {
        send(Json.obj("jsonrpc", "2.0", "method", method, "params", params));
    }

    // ================================================================ requests and notifications

    private void handle(String method, Object id, Object params) {
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "shutdown" -> {
                    shutdown = true;
                    yield null;
                }
                case "textDocument/didOpen" -> {
                    opened(Json.string(params, "textDocument", "uri"), Json.string(params, "textDocument", "text"));
                    yield null;
                }
                case "textDocument/didChange" -> {
                    String uri = Json.string(params, "textDocument", "uri");
                    Doc d = docs.get(path(uri));
                    String text = d == null ? "" : d.text();
                    for (Object c : Json.get(params, "contentChanges") instanceof List<?> l ? l : List.of()) {
                        text = applied(text, c);
                    }
                    changed(uri, text);
                    yield null;
                }
                case "textDocument/didSave" -> {
                    Doc d = docs.get(path(Json.string(params, "textDocument", "uri")));
                    if (d != null) {
                        schedule(d.program(), 0);
                    }
                    yield null;
                }
                case "textDocument/didClose" -> {
                    closed(Json.string(params, "textDocument", "uri"));
                    yield null;
                }
                case "textDocument/hover" -> hover(params);
                case "textDocument/definition" -> definition(params);
                case "textDocument/documentSymbol" -> symbols(params);
                default -> {
                    if (id != null && !method.startsWith("$/")) {
                        send(error(id, -32601, "seal lsp doesn't do " + method));
                    }
                    yield null;
                }
            };
            if (id != null && (result != null || isRequest(method))) {
                send(response(id, result));
            }
        } catch (Throwable e) {
            log.println("seal lsp: " + method + " failed:");
            e.printStackTrace(log);
            if (id != null) {
                send(error(id, -32603, "seal lsp: " + e));
            }
        }
    }

    /**
     * A text with a change made: the whole text, as the server asks for, or a part of it (a
     * range, its lines and UTF-16 characters from 0), as an editor may send all the same.
     */
    static String applied(String text, Object change) {
        String newText = Json.string(change, "text");
        if (Json.get(change, "range") == null) {
            return newText == null ? text : newText;
        }
        int from = Analysis.offset(text, Json.integer(change, "range", "start", "line") + 1,
                Json.integer(change, "range", "start", "character") + 1);
        int to = Analysis.offset(text, Json.integer(change, "range", "end", "line") + 1,
                Json.integer(change, "range", "end", "character") + 1);
        return text.substring(0, from) + (newText == null ? "" : newText) + text.substring(Math.max(from, to));
    }

    private static boolean isRequest(String method) {
        return switch (method) {
            case "initialize", "shutdown", "textDocument/hover", "textDocument/definition", "textDocument/documentSymbol" -> true;
            default -> false;
        };
    }

    private Object initialize(Object params) {
        hierarchicalSymbols = Boolean.TRUE.equals(Json.get(params, "capabilities", "textDocument", "documentSymbol",
                "hierarchicalDocumentSymbolSupport"));
        Map<String, Object> sync = Json.obj("openClose", true, "change", 1, "save", Json.obj("includeText", false));
        return Json.obj("capabilities", Json.obj("textDocumentSync", sync, "hoverProvider", true,
                "definitionProvider", true, "documentSymbolProvider", true), "serverInfo", Json.obj("name", "seal"));
    }

    /** A file URI's path, as the server keys files: absolute and normalized. */
    static String path(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            return Path.of(URI.create(uri)).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return uri; // not a file: (an editor's untitled:), kept as it is
        }
    }

    private String uriOf(String path) {
        Doc d = docs.get(path);
        return d != null ? d.uri() : Path.of(path).toUri().toString();
    }

    private Program programOf(String path, String text) {
        Path file = Path.of(path);
        Path root = file.isAbsolute() ? Program.rootOf(file, text, stdlib) : null;
        return programs.computeIfAbsent(root, r -> new Program(r, stdlib));
    }

    private void opened(String uri, String text) {
        String path = path(uri);
        Program p = programOf(path, text);
        docs.put(path, new Doc(uri, text, p));
        schedule(p, 0);
    }

    private void changed(String uri, String text) {
        String path = path(uri);
        Doc old = docs.get(path);
        Program p = programOf(path, text);
        docs.put(path, new Doc(old == null ? uri : old.uri(), text, p));
        if (old != null && old.program() != p) {
            release(old.program()); // its package changed: it's another program's now
        }
        schedule(p, PAUSE);
    }

    private void closed(String uri) {
        String path = path(uri);
        Doc d = docs.remove(path);
        if (d != null) {
            release(d.program());
        }
    }

    /** Checks a program again without a file it had open; forgets it, and its diagnostics, when it has none open. */
    private void release(Program p) {
        if (docs.values().stream().anyMatch(d -> d.program() == p)) {
            schedule(p, 0);
            return;
        }
        ScheduledFuture<?> f = pending.remove(p);
        if (f != null) {
            f.cancel(false);
        }
        for (String file : p.published) {
            notify("textDocument/publishDiagnostics", Json.obj("uri", uriOf(file), "diagnostics", List.of()));
        }
        p.published = Set.of();
        programs.remove(p.root);
        try {
            p.close();
        } catch (IOException e) {
            log.println("seal lsp: " + e.getMessage());
        }
    }

    private void schedule(Program p, long delay) {
        p.dirty = true;
        ScheduledFuture<?> f = pending.remove(p);
        if (f != null) {
            f.cancel(false);
        }
        pending.put(p, worker.schedule(() -> {
            pending.remove(p);
            checkAndPublish(p);
        }, delay, TimeUnit.MILLISECONDS));
    }

    /** The program's last check, checking it first if a file changed since. */
    private Analysis fresh(Program p) {
        if (p.dirty || p.analysis == null) {
            ScheduledFuture<?> f = pending.remove(p);
            if (f != null) {
                f.cancel(false);
            }
            checkAndPublish(p);
        }
        return p.analysis;
    }

    private void checkAndPublish(Program p) {
        if (!programs.containsValue(p)) {
            return; // closed since
        }
        Program.Checked checked;
        try {
            Set<String> openHere = new HashSet<>();
            docs.forEach((f, d) -> {
                if (d.program() == p) {
                    openHere.add(f);
                }
            });
            checked = p.check(f -> docs.containsKey(f) ? docs.get(f).text() : null, openHere, library(), warn, classPath, log);
        } catch (Throwable e) {
            p.dirty = false; // until the next change: checking it again now would fail again
            log.println("seal lsp: checking " + p.root + " failed:");
            e.printStackTrace(log);
            return;
        }
        if (p.truncated) {
            log.println("seal lsp: " + p.root + " has more than " + Program.MAX_FILES + " .seal files; the rest are left out");
        }
        Set<String> now = new HashSet<>();
        for (Map.Entry<String, List<Diagnostic>> e : checked.diagnostics().entrySet()) {
            String file = e.getKey();
            List<Diagnostic> found = e.getValue();
            if (found.isEmpty() && !p.published.contains(file) && !docs.containsKey(file)) {
                continue;
            }
            List<Object> list = new ArrayList<>();
            for (Diagnostic d : found) {
                Analysis.Span s = checked.analysis().spanAt(file, d.line(), d.column());
                list.add(Json.obj("range", range(s), "severity", d.isWarning() ? 2 : 1, "source", "seal",
                        "code", d.isWarning() ? d.warning().flag() : null, "message", d.message()));
            }
            notify("textDocument/publishDiagnostics", Json.obj("uri", uriOf(file), "diagnostics", list));
            if (!found.isEmpty()) {
                now.add(file);
            }
        }
        for (String file : p.published) {
            if (!checked.diagnostics().containsKey(file)) {
                notify("textDocument/publishDiagnostics", Json.obj("uri", uriOf(file), "diagnostics", List.of()));
            }
        }
        p.published = now;
    }

    /** The standard library's files, read again for each check (they may be the ones being written), with those open. */
    private Map<String, String> library() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        if (stdlib == null) {
            return files;
        }
        Sources.read(stdlib, files);
        for (String f : files.keySet()) {
            Doc d = docs.get(f);
            if (d != null) {
                files.put(f, d.text());
            }
        }
        return files;
    }

    private static Map<String, Object> range(Analysis.Span s) {
        return Json.obj("start", position(s.line(), s.column()), "end", position(s.endLine(), s.endColumn()));
    }

    private static Map<String, Object> position(int line, int column) {
        return Json.obj("line", Math.max(0, line - 1), "character", Math.max(0, column - 1));
    }

    /** The document a request is about, and the place in it, from 1; null if it isn't open. */
    private record At(String path, Program program, int line, int column) {
    }

    private At at(Object params) {
        String path = path(Json.string(params, "textDocument", "uri"));
        Doc d = docs.get(path);
        if (d == null) {
            return null;
        }
        return new At(path, d.program(), Json.integer(params, "position", "line") + 1,
                Json.integer(params, "position", "character") + 1);
    }

    private Object hover(Object params) {
        At at = at(params);
        Analysis a = at == null ? null : fresh(at.program());
        String text = a == null ? null : a.hover(at.path(), at.line(), at.column());
        return text == null ? null : Json.obj("contents", Json.obj("kind", "markdown", "value", text));
    }

    private Object definition(Object params) {
        At at = at(params);
        Analysis a = at == null ? null : fresh(at.program());
        Analysis.Span s = a == null ? null : a.definition(at.path(), at.line(), at.column());
        return s == null ? null : Json.obj("uri", uriOf(s.file()), "range", range(s));
    }

    private Object symbols(Object params) throws IOException {
        String path = path(Json.string(params, "textDocument", "uri"));
        Doc d = docs.get(path);
        String text = d != null ? d.text() : Files.isRegularFile(Path.of(path)) ? Files.readString(Path.of(path)) : null;
        if (text == null) {
            return List.of();
        }
        List<Object> out = new ArrayList<>();
        for (Analysis.Symbol s : Analysis.outline(path, text)) {
            if (hierarchicalSymbols) {
                out.add(symbol(s));
            } else {
                flatten(s, null, uriOf(path), out);
            }
        }
        return out;
    }

    private static Map<String, Object> symbol(Analysis.Symbol s) {
        return Json.obj("name", s.name(), "detail", s.detail(), "kind", s.kind(), "range", range(s.range()),
                "selectionRange", range(s.selection()), "children", s.children().stream().map(LanguageServer::symbol).toList());
    }

    /** For an editor that wants them listed, not nested: each with the type it is in. */
    private static void flatten(Analysis.Symbol s, String container, String uri, List<Object> out) {
        out.add(Json.obj("name", s.name(), "kind", s.kind(), "location", Json.obj("uri", uri, "range", range(s.range())),
                "containerName", container));
        for (Analysis.Symbol c : s.children()) {
            flatten(c, s.name(), uri, out);
        }
    }
}
