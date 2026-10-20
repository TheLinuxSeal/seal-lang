package io.github.thelinuxseal.seal.runtime;

/**
 * A Seal number, character or bit passed into Java by JavaInt.of and the others (docs/JAVA.md
 * §3.1): Java's value, boxed, marked with the primitive type it is, so that overload resolution
 * treats it as Java treats an int and not an Integer (§4). The bridge unwraps it wherever it meets
 * Java.
 */
public record JavaPrimitive(Class<?> type, Object box) {

    @Override
    public String toString() {
        return String.valueOf(box);
    }
}
