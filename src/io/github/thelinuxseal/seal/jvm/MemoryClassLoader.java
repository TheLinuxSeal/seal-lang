package io.github.thelinuxseal.seal.jvm;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * A class loader for classes compiled in memory, by binary name: a program's that `seal run` runs,
 * a script's. It gives them as `.class` resources too, and has their packages from the start, so
 * that a compiler running under it (a Seal program's engine, docs/EMBED.md) finds them as it
 * finds a jar's: a script implements the program's interface, a script's script the script's.
 */
public class MemoryClassLoader extends ClassLoader {
    protected final Map<String, byte[]> classes;

    @SuppressWarnings("this-escape") // definePackage, which no loader of these overrides
    public MemoryClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
        super(parent);
        this.classes = classes;
        for (String name : classes.keySet()) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && getDefinedPackage(name.substring(0, dot)) == null) {
                definePackage(name.substring(0, dot), null, null, null, null, null, null, null);
            }
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (name.endsWith(".class")) {
            byte[] bytes = classes.get(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
            if (bytes != null) {
                return new ByteArrayInputStream(bytes);
            }
        }
        return super.getResourceAsStream(name);
    }
}
