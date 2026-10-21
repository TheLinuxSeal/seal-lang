package io.github.thelinuxseal.seal.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.thelinuxseal.seal.ast.Ast.Binder;
import io.github.thelinuxseal.seal.ast.Ast.ClassDecl;
import io.github.thelinuxseal.seal.ast.Ast.ClassHeader;
import io.github.thelinuxseal.seal.ast.Ast.Constructor;
import io.github.thelinuxseal.seal.ast.Ast.Destructor;
import io.github.thelinuxseal.seal.ast.Ast.Field;
import io.github.thelinuxseal.seal.ast.Ast.Import;
import io.github.thelinuxseal.seal.ast.Ast.Pos;
import io.github.thelinuxseal.seal.ast.Ast.Member;
import io.github.thelinuxseal.seal.ast.Ast.Method;
import io.github.thelinuxseal.seal.ast.Ast.ValueList;

/** Declaration-level information about one class, gathered before bodies are checked. */
public final class ClassInfo {

    /** One header (shape) of the class. */
    public static final class Shape {
        public final int index;
        public final ClassHeader header;
        /** Binders of the header, in order; empty for the bare shape. */
        public final List<Binder> binders;
        /** Resolved binder types, parallel to {@link #binders}. */
        public final List<Type> binderTypes = new ArrayList<>();

        Shape(int index, ClassHeader header, List<Binder> binders) {
            this.index = index;
            this.header = header;
            this.binders = binders;
        }

        public List<String> binderNames() {
            return binders.stream().map(Binder::name).toList();
        }
    }

    /** A member together with the shapes it exists in. */
    public static final class MemberInfo {
        public final Member member;
        /** Index of the header this member's guard matches; -1 when unguarded or unmatched. */
        public int shape = -1;
        public boolean guarded;

        MemberInfo(Member member) {
            this.member = member;
            this.guarded = guardOf(member) != null;
        }

        public boolean existsIn(int shapeIndex) {
            return !guarded || shape == shapeIndex;
        }

        public String name() {
            return switch (member) {
                case Field f -> f.name();
                case Method m -> m.name();
                case Constructor c -> "new";
                case Destructor d -> "delete";
            };
        }

        public ValueList guard() {
            return guardOf(member);
        }

        static ValueList guardOf(Member m) {
            return switch (m) {
                case Field f -> f.guard();
                case Method md -> md.guard();
                case Constructor c -> c.guard();
                case Destructor d -> d.guard();
            };
        }
    }

    /** The full name: `Entry` nested in `HashMap` is `HashMap.Entry` (§2.3). */
    public final String name;
    /** The name as declared: `Entry`. */
    public final String simpleName;
    /** The type this one is declared in, or null for a top-level type. */
    public final ClassInfo outer;
    /** False for a nested type that isn't `public`: only its outer type (and what that holds) may use it. */
    public final boolean isPublic;
    public final String file;
    public final ClassDecl decl;
    /** The declaration kind: struct, unique, or class. */
    public final io.github.thelinuxseal.seal.ast.Ast.Kind kind;
    /** True for a `class` or `interface`: a shared reference type reached through handles. */
    public final boolean dynamic;
    /** True for a `unique`: an inline value that cannot be copied. */
    public final boolean unique;
    /** True for an `interface`: method signatures that classes implement (§30.2). */
    public final boolean isInterface;
    /** True for an `abstract class` or an interface: it can't be created with `new` (§30.1). */
    public final boolean isAbstract;
    public final List<String> typeParams;
    public final List<Shape> shapes = new ArrayList<>();

    /** Fields and methods by name, in declaration order. */
    public final Map<String, List<MemberInfo>> members = new LinkedHashMap<>();
    public final List<MemberInfo> constructors = new ArrayList<>();
    public final List<MemberInfo> destructors = new ArrayList<>();
    /** Every member, in declaration order. */
    public final List<MemberInfo> all = new ArrayList<>();

    /** True for the built-in prelude (`Object`, `Writer`, `System`) and the standard library. */
    public final boolean builtin;

    /**
     * For one of Java's classes (docs/JAVA.md §3): its class file, which the members were made from
     * (seal.java's JavaString is java.lang.String's, with its own `of` added); null for Seal's.
     */
    public JavaClasses.JClass java;

    /** The package the type is in, as com.example, or "" for none (§2.4). A nested type is in its outer type's. */
    public String pkg = "";
    /** Where the file says its package. */
    Pos pkgPos;
    /** The imports of the type's file, which nested types share. */
    public List<Import> imports = List.of();
    /**
     * Members the checker writes (§30.4): the accessors of abstract and default fields, and in a
     * class that holds such a field, the field (for a default one it doesn't declare) and accessors.
     */
    public final List<Member> synthesized = new ArrayList<>();

    ClassInfo(String file, ClassDecl decl, boolean builtin) {
        this(file, decl, builtin, null, true);
    }

    ClassInfo(String file, ClassDecl decl, boolean builtin, ClassInfo outer, boolean isPublic) {
        this.file = file;
        this.decl = decl;
        this.builtin = builtin;
        this.outer = outer;
        this.isPublic = isPublic;
        ClassHeader first = decl.headers().get(0);
        this.simpleName = first.name();
        this.name = outer == null ? first.name() : outer.name + "." + first.name();
        this.kind = first.kind();
        this.dynamic = first.dynamic();
        this.unique = first.kind() == io.github.thelinuxseal.seal.ast.Ast.Kind.UNIQUE;
        this.isInterface = first.kind() == io.github.thelinuxseal.seal.ast.Ast.Kind.INTERFACE;
        this.isAbstract = first.isAbstract() || isInterface;
        this.typeParams = first.typeParams();
        if (outer != null) {
            this.pkg = outer.pkg;
            this.imports = outer.imports;
        }
    }

    /** The name with its package: com.example.Vec2, com.example.Outer.Inner; the plain name outside packages. */
    public String qualifiedName() {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /** The top-level type this one is (in): itself, or the outermost type it is nested in. */
    public ClassInfo topLevel() {
        ClassInfo c = this;
        while (c.outer != null) {
            c = c.outer;
        }
        return c;
    }

    /** True for the implicit root class `Object`. */
    public boolean isRoot() {
        return builtin && name.equals("Object");
    }

    /**
     * Whether a field is one the type only names, for the classes below it to hold (§30.4): every
     * field of an interface, and an abstract or default field of an abstract class. Code reads
     * and writes it through accessor methods the checker writes.
     */
    public boolean isVirtual(Field f) {
        // In a type that can't have them (reported), abstract and default fields are plain ones.
        return !f.modifiers().isStatic() && (isInterface
                || (isAbstract && dynamic && (f.modifiers().isAbstract() || f.modifiers().isDefault())));
    }

    /**
     * Fields stored in each instance of the given shape, in declaration order.
     * Static fields live in static data, const fields are compile-time values
     * that take no space, and abstract and default fields live in the classes below.
     */
    public List<MemberInfo> storedFields(int shape) {
        List<MemberInfo> out = new ArrayList<>();
        for (MemberInfo m : all) {
            if (m.member instanceof Field f && !f.modifiers().isStatic() && !f.modifiers().isConst() && !isVirtual(f)
                    && m.existsIn(shape)) {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return name;
    }
}
