package io.github.thelinuxseal.seal.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The standard library's natives on the JVM (docs/JVM.md §8.3): static methods named after their
 * {@code @native} names, which compiled programs call. They do what the VM's do (vm/VM.java), with
 * the same results, error texts and status codes, so a program behaves the same on both. As on
 * the VM, a file operation returns its status, and the text it produces (a file's contents, a line,
 * an error message) is kept for the library to take with {@link #takeText}.
 */
public final class Natives {

    private Natives() {
    }

    /** The program's standard input, output and error; a runner or a test may point them elsewhere. */
    public static InputStream in = System.in;
    public static PrintStream out = System.out;
    public static PrintStream err = System.err;
    /** Whether programs may use files and folders; off, every file operation fails, as on the VM. */
    public static boolean fileAccess = true;

    /** The file ids of the standard streams, as the library's System makes its Readers and Writers (§42.2). */
    private static final long STDIN = 1;
    private static final long STDOUT = 2;
    private static final long STDERR = 3;
    /** A file operation's status: an error (its message the text kept), a missing file, the end of a file. */
    private static final long IO_ERROR = -1;
    private static final long IO_NOT_FOUND = -2;
    private static final long IO_END = -3;

    private static String ioText = "";
    /** The bytes the last io_read_bytes read. */
    private static byte[] ioBytes = new byte[0];
    private static final Map<Long, Closeable> openFiles = new HashMap<>();
    private static long nextFileId = 4;
    private static BufferedReader stdinLines;

    /** Forgets what an earlier run left: open files, kept text, buffered input. */
    public static void reset() {
        ioText = "";
        ioBytes = new byte[0];
        openFiles.clear();
        nextFileId = 4;
        stdinLines = null;
    }

    /** As a program ends: what is still open is written out and closed, as on the VM. */
    public static void finish() {
        for (Closeable f : openFiles.values()) {
            try {
                f.close();
            } catch (IOException e) {
                // Nothing to report it to as the program ends.
            }
        }
        openFiles.clear();
        out.flush();
        err.flush();
    }

    /** The text the last file operation produced: a string the library makes (string.takeText). */
    public static String takeText(int n) {
        return ioText;
    }

    public static long io_text_length() {
        return ioText.length();
    }

    // ---------------------------------------------------------------- writing

    public static long io_write(long file, String text) {
        return write(file, text);
    }

    public static long io_write_char(long file, char c) {
        return write(file, String.valueOf(c));
    }

    public static long io_write_int(long file, long x) {
        return write(file, Long.toString(x));
    }

    public static long io_write_uint(long file, long x) {
        return write(file, Long.toUnsignedString(x));
    }

    public static long io_write_float(long file, float x) {
        return write(file, Float.toString(x));
    }

    public static long io_write_double(long file, double x) {
        return write(file, Double.toString(x));
    }

    public static long io_write_bit(long file, boolean x) {
        return write(file, x ? "true" : "false");
    }

    /** A fixed-point value's exact text: its scaled integer, and its fraction bits. */
    public static long io_write_fixed(long file, long x, int frac) {
        return write(file, FixedPoint.text(x, frac, true));
    }

    public static long io_write_ufixed(long file, long x, int frac) {
        return write(file, FixedPoint.text(x, frac, false));
    }

    /** Text to a standard stream, flushed at once so output and errors interleave as written, or to an open file. */
    private static long write(long file, String text) {
        if (file == STDOUT || file == STDERR) {
            PrintStream to = file == STDERR ? err : out;
            to.print(text);
            to.flush();
            return 0;
        }
        if (file == STDIN) {
            ioText = "standard input can't be written";
            return IO_ERROR;
        }
        if (!fileAccess) {
            ioText = "file access is disabled";
            return IO_ERROR;
        }
        if (!(openFiles.get(file) instanceof BufferedWriter w)) {
            ioText = "the file is closed";
            return IO_ERROR;
        }
        try {
            w.write(text);
            return 0;
        } catch (IOException e) {
            ioText = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return IO_ERROR;
        }
    }

    public static long io_flush(long file) {
        if (file >= STDIN && file <= STDERR) {
            (file == STDERR ? err : out).flush();
            return 0;
        }
        return file(() -> {
            if (openFiles.get(file) instanceof BufferedWriter w) {
                w.flush();
            }
            return 0;
        });
    }

    public static long io_close(long file) {
        if (file >= STDIN && file <= STDERR) {
            (file == STDERR ? err : out).flush(); // the standard streams stay open
            return 0;
        }
        return file(() -> {
            Closeable f = openFiles.remove(file);
            if (f != null) {
                f.close();
            }
            return 0;
        });
    }

    public static long io_read_line(long file) {
        if (file == STDIN) {
            try {
                if (stdinLines == null) {
                    stdinLines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                out.flush(); // a prompt written before reading is seen first
                String line = stdinLines.readLine();
                if (line == null) {
                    return IO_END;
                }
                ioText = line;
                return line.length();
            } catch (IOException e) {
                ioText = "standard input: " + e.getMessage();
                return IO_ERROR;
            }
        }
        if (file == STDOUT || file == STDERR) {
            ioText = "standard output can't be read";
            return IO_ERROR;
        }
        return file(() -> {
            if (!(openFiles.get(file) instanceof BufferedReader r)) {
                ioText = "the file is closed";
                return IO_ERROR;
            }
            String line = r.readLine();
            if (line == null) {
                return IO_END;
            }
            ioText = line;
            return line.length();
        });
    }

    // ---------------------------------------------------------------- files and folders (§42.1)

    public static long io_open(String path, int mode) {
        return file(() -> {
            Path p = Path.of(path);
            Closeable f = mode == 0
                    ? new BufferedReader(new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8))
                    : new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(p, writeOptions(mode == 2)),
                            StandardCharsets.UTF_8));
            long id = nextFileId++;
            openFiles.put(id, f);
            return id;
        });
    }

    public static long io_read_text(String path) {
        return file(() -> {
            ioText = new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
            return ioText.length();
        });
    }

    /** A file's bytes, kept for io_bytes_take; gives how many. */
    public static long io_read_bytes(String path) {
        return file(() -> {
            ioBytes = Files.readAllBytes(Path.of(path));
            return ioBytes.length;
        });
    }

    /** The bytes io_read_bytes read, stored as uint<<8>>s from buf on. */
    public static long io_bytes_take(Ptr buf) {
        Block b = Ptr.at(buf, ioBytes.length * 8, 8);
        for (int i = 0; i < ioBytes.length; i++) {
            Block.store(b, (int) buf.off + i * 8, ioBytes[i] & 0xff, 8);
        }
        return ioBytes.length;
    }

    /** n uint<<8>>s from buf on, written to a file. */
    public static long io_write_bytes(String path, Ptr buf, int n, boolean append) {
        Block b = Ptr.at(buf, n * 8, 8);
        byte[] data = new byte[n];
        for (int i = 0; i < n; i++) {
            data[i] = (byte) Block.load(b, (int) buf.off + i * 8, 8);
        }
        return file(() -> {
            Files.write(Path.of(path), data, writeOptions(append));
            return 0;
        });
    }

    public static long io_write_text(String path, String text, boolean append) {
        return file(() -> {
            Files.writeString(Path.of(path), text, StandardCharsets.UTF_8, writeOptions(append));
            return 0;
        });
    }

    public static long io_exists(String path) {
        return file(() -> Files.exists(Path.of(path)) ? 1 : 0);
    }

    public static long io_is_file(String path) {
        return file(() -> Files.isRegularFile(Path.of(path)) ? 1 : 0);
    }

    public static long io_is_dir(String path) {
        return file(() -> Files.isDirectory(Path.of(path)) ? 1 : 0);
    }

    public static long io_size(String path) {
        return file(() -> Files.size(Path.of(path)));
    }

    public static long io_modified(String path) {
        return file(() -> Files.getLastModifiedTime(Path.of(path)).toMillis());
    }

    public static long io_delete(String path) {
        return file(() -> {
            Files.delete(Path.of(path));
            return 0;
        });
    }

    public static long io_mkdir(String path, boolean parents) {
        return file(() -> {
            if (parents) {
                Files.createDirectories(Path.of(path));
            } else {
                Files.createDirectory(Path.of(path));
            }
            return 0;
        });
    }

    /** The names in a folder, sorted, so a program sees the same order on every system; joined with NUL. */
    public static long io_list(String folder) {
        return file(() -> {
            try (Stream<Path> s = Files.list(Path.of(folder))) {
                List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
                ioText = String.join("\0", names);
                return names.size();
            }
        });
    }

    public static long io_copy(String from, String to) {
        return file(() -> {
            Files.copy(Path.of(from), Path.of(to), StandardCopyOption.REPLACE_EXISTING);
            return 0;
        });
    }

    public static long io_move(String from, String to) {
        return file(() -> {
            Files.move(Path.of(from), Path.of(to), StandardCopyOption.REPLACE_EXISTING);
            return 0;
        });
    }

    public static long io_absolute(String path) {
        return file(() -> {
            ioText = Path.of(path).toAbsolutePath().normalize().toString();
            return ioText.length();
        });
    }

    public static long io_temp_dir(String prefix) {
        return file(() -> {
            ioText = Files.createTempDirectory(prefix).toString();
            return ioText.length();
        });
    }

    private interface FileOp {
        long run() throws IOException;
    }

    /** A file operation, with the VM's checks and error texts. */
    private static long file(FileOp op) {
        if (!fileAccess) {
            ioText = "file access is disabled";
            return IO_ERROR;
        }
        try {
            return op.run();
        } catch (NoSuchFileException e) {
            ioText = "no such file or directory: " + e.getFile();
            return IO_NOT_FOUND;
        } catch (FileAlreadyExistsException e) {
            ioText = "already exists: " + e.getFile();
            return IO_ERROR;
        } catch (DirectoryNotEmptyException e) {
            ioText = "folder is not empty: " + e.getFile();
            return IO_ERROR;
        } catch (NotDirectoryException e) {
            ioText = "not a folder: " + e.getFile();
            return IO_ERROR;
        } catch (AccessDeniedException e) {
            ioText = "permission denied: " + e.getFile();
            return IO_ERROR;
        } catch (IOException e) {
            ioText = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return IO_ERROR;
        } catch (InvalidPathException e) {
            ioText = "invalid path: " + e.getMessage();
            return IO_ERROR;
        }
    }

    private static OpenOption[] writeOptions(boolean append) {
        return append
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
    }

    // ---------------------------------------------------------------- math (§42.5)

    public static double sqrt(double x) {
        return Math.sqrt(x);
    }

    public static double sin(double x) {
        return Math.sin(x);
    }

    public static double cos(double x) {
        return Math.cos(x);
    }

    public static double tan(double x) {
        return Math.tan(x);
    }

    public static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static double exp(double x) {
        return Math.exp(x);
    }

    public static double log(double x) {
        return Math.log(x);
    }

    public static double pow(double x, double y) {
        return Math.pow(x, y);
    }

    public static double floor(double x) {
        return Math.floor(x);
    }

    public static double ceil(double x) {
        return Math.ceil(x);
    }

    public static long double_bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    public static long float_bits(float x) {
        return Float.floatToRawIntBits(x) & 0xffffffffL;
    }

    /** An object's hash from its identity, as Object.hash() gives (§31), spread over all the bits. */
    public static long identity_hash(Object o) {
        return System.identityHashCode(o) * 0x9E3779B97F4A7C15L;
    }

    // ---------------------------------------------------------------- the process

    public static long time_ns() {
        return System.nanoTime();
    }

    /** System.exit(code): ends the program at once, through {@link Exit}, which its entry point catches. */
    public static void exit(int code) {
        throw new Exit(code);
    }

    /** System.fail's end: stops the program, as a VM trap does. */
    public static void abort() {
        out.flush();
        throw new Trap("abort");
    }

    /**
     * A compiled program's {@code main(String[])}: runs it ({@code $run}, which gives main's result),
     * and ends the process as the VM's runner does.
     */
    public static void start(MethodHandle run) {
        int code;
        try {
            code = (int) run.invokeExact();
        } catch (Exit e) {
            code = e.code;
        } catch (RuntimeException | Error e) {
            finish();
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        end(code);
    }

    /** Writes out what is open and, for a code other than 0, exits with it. */
    public static void end(int code) {
        finish();
        if (code != 0) {
            System.exit(code);
        }
    }
}
