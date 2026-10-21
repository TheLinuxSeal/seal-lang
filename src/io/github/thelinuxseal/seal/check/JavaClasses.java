package io.github.thelinuxseal.seal.check;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.constant.ClassDesc;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Java's classes as the compiler sees them (docs/JAVA.md §6): read from their class files, never
 * loaded, so that no static initializer runs and Minecraft's classes can be read outside the game.
 * The JDK's come from its module image; a jar's or a folder's from the class path. A class is read
 * when it is first asked for, and kept; the JDK's are kept for as long as the compiler runs.
 */
public final class JavaClasses {

    /** The class-file flags the compiler reads. */
    public static final int PUBLIC = 0x0001;
    public static final int PROTECTED = 0x0004;
    public static final int STATIC = 0x0008;
    public static final int FINAL = 0x0010;
    public static final int BRIDGE = 0x0040;
    public static final int VARARGS = 0x0080;
    public static final int INTERFACE = 0x0200;
    public static final int ABSTRACT = 0x0400;
    public static final int SYNTHETIC = 0x1000;
    public static final int ENUM = 0x4000;

    /**
     * A Java class: its binary name (java.util.Map$Entry), flags, parent (null for
     * java.lang.Object and for interfaces), interfaces, members, and, for a member class, the
     * class it is in and its own name there.
     *
     * @param visible   whether a program may name it: public (and a member class public in its
     *                  class), and for the JDK's, in a package its module exports
     * @param signature its generic signature, its type parameters and its parents' type arguments
     *                  (docs/JAVA.md §4.1), or null for a class that has none
     */
    public record JClass(String name, int flags, String superName, List<String> interfaces, List<JMember> members,
                         String outerName, String simpleName, boolean visible, String signature) {

        public boolean isInterface() {
            return (flags & INTERFACE) != 0;
        }

        /** The package: java.util for java.util.Map$Entry. */
        public String pkg() {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? "" : name.substring(0, dot);
        }

        /** The JVM's internal name, java/util/Map$Entry. */
        public String internalName() {
            return name.replace('.', '/');
        }
    }

    /**
     * A method (a constructor is "<init>") or field, with its descriptor, "(I)Ljava/lang/String;" or
     * "J", and its generic signature, "(TE;)Z", or null if it has none.
     */
    public record JMember(String name, String descriptor, int flags, String signature) {

        public boolean isField() {
            return !descriptor.startsWith("(");
        }

        public boolean isStatic() {
            return (flags & STATIC) != 0;
        }

        public boolean isFinal() {
            return (flags & FINAL) != 0;
        }

        public boolean isVarargs() {
            return !isField() && (flags & VARARGS) != 0;
        }

        public boolean isConstructor() {
            return name.equals("<init>");
        }
    }

    /** The JDK's classes, read once for every compile. */
    private static final Map<String, Optional<JClass>> JDK = new ConcurrentHashMap<>();
    private static volatile FileSystem jrt;
    /** Each package of the JDK and its module, and the packages the modules export to everyone. */
    private static volatile Map<String, String> jdkModules;
    private static volatile Set<String> jdkExported;

    private final List<Path> classPath;
    private final Map<Path, ZipFile> jars = new HashMap<>();
    private final Map<Path, Set<String>> jarPackages = new HashMap<>();
    private final Map<String, Optional<JClass>> read = new HashMap<>();

    /** The JDK's classes and those of the jars and folders given, which come after the JDK's, as in Java. */
    public JavaClasses(List<Path> classPath) {
        this.classPath = List.copyOf(classPath);
    }

    /** The JDK's classes alone. */
    public static JavaClasses jdk() {
        return new JavaClasses(List.of());
    }

    /** The class of this binary name (java.util.Map$Entry), or null if there is none. */
    public JClass find(String binaryName) {
        if (binaryName.isEmpty() || binaryName.startsWith(".") || binaryName.endsWith(".")) {
            return null;
        }
        String pkg = binaryName.lastIndexOf('.') < 0 ? "" : binaryName.substring(0, binaryName.lastIndexOf('.'));
        if (modules().containsKey(pkg)) {
            return JDK.computeIfAbsent(binaryName, n -> Optional.ofNullable(parse(jdkBytes(n), true))).orElse(null);
        }
        return read.computeIfAbsent(binaryName, n -> Optional.ofNullable(parse(classPathBytes(n), false))).orElse(null);
    }

    /** Whether there is a package of this name a program may import from: one the JDK exports, or one on the class path. */
    public boolean hasPackage(String pkg) {
        if (exported().contains(pkg)) {
            return true;
        }
        String dir = pkg.replace('.', '/');
        for (Path entry : classPath) {
            if (Files.isDirectory(entry) && Files.isDirectory(entry.resolve(dir))) {
                return true;
            }
            if (Files.isRegularFile(entry) && packagesOf(entry).contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** A class file's bytes, for the stack maps of generated code (which need Java's classes' parents), or null. */
    public InputStream open(ClassDesc c) {
        String name = c.packageName().isEmpty() ? c.displayName() : c.packageName() + "." + c.displayName();
        String pkg = c.packageName();
        byte[] bytes = modules().containsKey(pkg) ? jdkBytes(name) : classPathBytes(name);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    private static JClass parse(byte[] bytes, boolean fromJdk) {
        if (bytes == null) {
            return null;
        }
        ClassModel m = ClassFile.of().parse(bytes);
        String name = m.thisClass().asInternalName().replace('/', '.');
        int flags = m.flags().flagsMask();
        String superName = (flags & INTERFACE) != 0 ? null
                : m.superclass().map(s -> s.asInternalName().replace('/', '.')).orElse(null);
        List<String> interfaces = m.interfaces().stream().map(i -> i.asInternalName().replace('/', '.')).toList();
        List<JMember> members = new ArrayList<>();
        for (FieldModel f : m.fields()) {
            members.add(new JMember(f.fieldName().stringValue(), f.fieldType().stringValue(), f.flags().flagsMask(),
                    f.findAttribute(Attributes.signature()).map(a -> a.signature().stringValue()).orElse(null)));
        }
        for (MethodModel md : m.methods()) {
            members.add(new JMember(md.methodName().stringValue(), md.methodType().stringValue(), md.flags().flagsMask(),
                    md.findAttribute(Attributes.signature()).map(a -> a.signature().stringValue()).orElse(null)));
        }
        String outer = null;
        String simple = name.substring(name.lastIndexOf('.') + 1);
        boolean visible = (flags & PUBLIC) != 0;
        Optional<InnerClassesAttribute> inner = m.findAttribute(Attributes.innerClasses());
        if (inner.isPresent()) {
            for (InnerClassInfo i : inner.get().classes()) {
                if (i.innerClass().asInternalName().equals(m.thisClass().asInternalName()) && i.outerClass().isPresent()
                        && i.innerName().isPresent()) {
                    outer = i.outerClass().get().asInternalName().replace('/', '.');
                    simple = i.innerName().get().stringValue();
                    visible = (i.flagsMask() & PUBLIC) != 0;
                }
            }
        }
        if (fromJdk && !exported().contains(name.substring(0, Math.max(0, name.lastIndexOf('.'))))) {
            visible = false;
        }
        String signature = m.findAttribute(Attributes.signature()).map(a -> a.signature().stringValue()).orElse(null);
        return new JClass(name, flags, superName, interfaces, List.copyOf(members), outer, simple, visible, signature);
    }

    private static byte[] jdkBytes(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        String module = modules().get(dot < 0 ? "" : binaryName.substring(0, dot));
        if (module == null) {
            return null;
        }
        Path p = jrt().getPath("/modules", module, binaryName.replace('.', '/') + ".class");
        try {
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] classPathBytes(String binaryName) {
        String file = binaryName.replace('.', '/') + ".class";
        for (Path entry : classPath) {
            try {
                if (Files.isDirectory(entry)) {
                    Path p = entry.resolve(file);
                    if (Files.isRegularFile(p)) {
                        return Files.readAllBytes(p);
                    }
                } else if (Files.isRegularFile(entry)) {
                    ZipFile jar = jar(entry);
                    ZipEntry e = jar == null ? null : jar.getEntry(file);
                    if (e != null) {
                        try (InputStream in = jar.getInputStream(e)) {
                            return in.readAllBytes();
                        }
                    }
                }
            } catch (IOException e) {
                // An entry that can't be read has nothing to give.
            }
        }
        return null;
    }

    private ZipFile jar(Path p) {
        return jars.computeIfAbsent(p, k -> {
            try {
                return new ZipFile(k.toFile());
            } catch (IOException e) {
                return null;
            }
        });
    }

    private Set<String> packagesOf(Path jarPath) {
        return jarPackages.computeIfAbsent(jarPath, k -> {
            Set<String> out = new HashSet<>();
            ZipFile jar = jar(k);
            if (jar != null) {
                for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (n.endsWith(".class") && n.lastIndexOf('/') > 0) {
                        out.add(n.substring(0, n.lastIndexOf('/')).replace('/', '.'));
                    }
                }
            }
            return out;
        });
    }

    private static FileSystem jrt() {
        if (jrt == null) {
            synchronized (JavaClasses.class) {
                if (jrt == null) {
                    jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
                }
            }
        }
        return jrt;
    }

    private static Map<String, String> modules() {
        if (jdkModules == null) {
            synchronized (JavaClasses.class) {
                if (jdkModules == null) {
                    Map<String, String> pkgs = new HashMap<>();
                    Set<String> exported = new HashSet<>();
                    for (ModuleReference r : ModuleFinder.ofSystem().findAll()) {
                        ModuleDescriptor d = r.descriptor();
                        for (String p : d.packages()) {
                            pkgs.put(p, d.name());
                        }
                        for (ModuleDescriptor.Exports e : d.exports()) {
                            if (!e.isQualified()) {
                                exported.add(e.source());
                            }
                        }
                    }
                    jdkExported = Set.copyOf(exported);
                    jdkModules = Map.copyOf(pkgs);
                }
            }
        }
        return jdkModules;
    }

    private static Set<String> exported() {
        modules();
        return jdkExported;
    }
}
