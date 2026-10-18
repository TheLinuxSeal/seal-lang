package lang.check;

/** An error, or a warning that the program asked for (§56.1). */
public record Diagnostic(String file, int line, int column, String message, Warning warning) {

    public Diagnostic(String file, int line, int column, String message) {
        this(file, line, column, message, null);
    }

    public boolean isWarning() {
        return warning != null;
    }

    @Override
    public String toString() {
        return file + ":" + line + ":" + column + ": "
                + (warning == null ? "" : "warning: [" + warning.flag() + "] ") + message;
    }
}
