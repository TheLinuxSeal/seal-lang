package io.github.thelinuxseal.seal.codegen;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.thelinuxseal.seal.ast.Ast;
import io.github.thelinuxseal.seal.ast.Ast.*;
import io.github.thelinuxseal.seal.check.AddressTaken;
import io.github.thelinuxseal.seal.check.CVal;
import io.github.thelinuxseal.seal.check.CVal.BitV;
import io.github.thelinuxseal.seal.check.CVal.FloatV;
import io.github.thelinuxseal.seal.check.CVal.IntV;
import io.github.thelinuxseal.seal.check.Checker;
import io.github.thelinuxseal.seal.check.Checker.CallInfo;
import io.github.thelinuxseal.seal.check.Checker.CallKind;
import io.github.thelinuxseal.seal.check.Checker.TypedBody;
import io.github.thelinuxseal.seal.check.ClassInfo;
import io.github.thelinuxseal.seal.check.ClassInfo.MemberInfo;
import io.github.thelinuxseal.seal.check.Diagnostic;
import io.github.thelinuxseal.seal.check.Layouts;
import io.github.thelinuxseal.seal.check.Layouts.Layout;
import io.github.thelinuxseal.seal.check.Type;
import io.github.thelinuxseal.seal.check.Type.*;
import io.github.thelinuxseal.seal.vm.Op;
import io.github.thelinuxseal.seal.vm.Program;

/**
 * Generates VM bytecode from a checked program.
 *
 * <p>Generics are instantiated here (§55): each function is generated for one
 * concrete owner type and one set of binder values, and only for the
 * instantiations the program actually reaches from its entry point. The VM
 * never sees a generic parameter.
 *
 * <p>Scalars (integers, bit, floats, addresses, handles) are held in
 * registers. Aggregates (fixed class values and arrays) always live in
 * memory: in the frame, the heap, static data, or inside a dynamic object.
 * Scalar locals whose address is taken also live in the frame.
 */
@Deprecated // the VM, behind --vm, is to be removed (docs/JVM.md §13)
public final class CodeGen {

    public record Result(Program program, List<Diagnostic> errors) {
    }

    /** Generates code for a program whose entry point is `static main()` in {@code mainClass}. */
    public static Result generate(Checker checker, String mainClass) {
        CodeGen g = new CodeGen(checker);
        g.run(mainClass);
        return new Result(g.errors.isEmpty() ? g.prog : null, g.errors);
    }

    // ================================================================ state

    private static final String DESTROY_SLOT = "__destroy";

    /**
     * One generated function: a member (or synthesized function) for a concrete owner and binder values.
     *
     * @param extra for a lambda's functions, the Lambda node; null otherwise
     */
    private record FnKey(String kind, ClassInfo cls, ClassT owner, MemberInfo member, Map<String, CVal> binders,
                         Object extra) {
        FnKey(String kind, ClassInfo cls, ClassT owner, MemberInfo member, Map<String, CVal> binders) {
            this(kind, cls, owner, member, binders, null);
        }
    }


    private final Checker ck;
    private final Program prog = new Program();
    private final List<Diagnostic> errors = new ArrayList<>();

    private final Map<FnKey, Integer> fnIds = new HashMap<>();
    private final Deque<FnKey> work = new ArrayDeque<>();
    private final Map<ClassT, Integer> rtClassIds = new LinkedHashMap<>();
    private final List<ClassT> rtPending = new ArrayList<>();
    private final Map<String, Integer> slotIds = new HashMap<>();
    /** The runtime class of each lambda (in each instantiation of the code around it). */
    private final Map<FnKey, Integer> closureIds = new HashMap<>();
    private final Map<String, Integer> nativeIds = new HashMap<>();
    private final Map<String, Integer> stringIds = new HashMap<>();
    private final Layouts layouts;
    private final Map<Field, Long> staticOffsets = new HashMap<>();
    private final Map<Field, Type> staticTypes = new HashMap<>();
    private final Map<Type, Boolean> destroyCache = new HashMap<>();
    /** Static data after the static fields: string literal characters. */
    private final io.github.thelinuxseal.seal.vm.Memory staticImage = new io.github.thelinuxseal.seal.vm.Memory(0);
    private long staticEnd = 0;
    private final Map<String, Long> literalOffsets = new HashMap<>();

    private CodeGen(Checker ck) {
        this.ck = ck;
        this.layouts = new Layouts(ck);
    }

    /** Thrown for programs the generator cannot handle; reported as an error. */
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Pos pos;

        Unsupported(Pos pos, String message) {
            super(message, null, false, false);
            this.pos = pos;
        }
    }

    // ================================================================ driver

    private void run(String mainClass) {
        List<ClassInfo> named = ck.mainCandidates(mainClass);
        if (named.size() > 1) {
            errors.add(new Diagnostic("<program>", 1, 1, "several classes are named " + mainClass + ": "
                    + named.stream().map(ClassInfo::qualifiedName).sorted().collect(Collectors.joining(", "))
                    + "; choose one with --main, as in --main " + named.get(0).qualifiedName()));
            return;
        }
        ClassInfo main = named.isEmpty() ? null : named.get(0);
        Checker.EntryPoint ep = main == null ? null : ck.entryPoint(main);
        if (ep == null) {
            errors.add(new Diagnostic(main == null ? "<program>" : main.file, 1, 1,
                    "no entry point: expected `void main()` or `int main()` in class " + mainClass));
            return;
        }
        MemberInfo entry = ep.method();
        Pos at = entry.member.pos();
        Type ret = ck.returnTypeOf(ep.owner(), entry, Map.of());
        if (!(ret instanceof VoidT) && !(ret instanceof IntT)) {
            errors.add(new Diagnostic(ep.declaring().file, at.line(), at.column(),
                    "main must return void or an integer, not " + ret));
            return;
        }
        String cannotCreate = ep.isStatic() ? null : cannotCreate(main, ep);
        if (cannotCreate != null) {
            errors.add(new Diagnostic(ep.declaring().file, at.line(), at.column(), "main() is not static, so the program "
                    + "creates a " + mainClass + " to run it, but " + cannotCreate + "; make main() static"));
            return;
        }

        allocateStatics();
        prog.destroySlot = slotId(DESTROY_SLOT);
        prog.messageLayout = messageLayout();
        prog.stringLayout = prog.messageLayout == null ? null : new long[]{prog.messageLayout[1], prog.messageLayout[2]};
        prog.init = staticInit();
        prog.fini = staticFini();
        if (ep.isStatic()) {
            prog.entry = fnId(new FnKey("method", ep.declaring(), null, entry, Map.of()));
        } else {
            startEntry = ep;
            prog.entry = fnId(new FnKey("start", main, new ClassT(main, List.of(), List.of(), 0), entry, Map.of()));
            prog.entryCreatesMain = true;
        }

        while (!work.isEmpty() || !rtPending.isEmpty()) {
            while (!work.isEmpty()) {
                FnKey k = work.pop();
                int id = fnIds.get(k);
                String name = fnName(k);
                List<Diagnostic> found = new ArrayList<>();
                current = k;
                try {
                    prog.functions.set(id, generate(k, found));
                } catch (Unsupported u) {
                    ClassInfo c = k.cls();
                    if (u.getMessage() != null) {
                        found.add(new Diagnostic(c.file, u.pos.line(), u.pos.column(), "in " + name + ": " + u.getMessage()));
                    }
                    // A method that can't be built for these types stops the program if it is ever reached.
                    int msg = stringId(name + " is not available for these types");
                    prog.functions.set(id, new Program.Function(name, new int[]{Op.TRAP.ordinal(), msg}, new long[0], 1, 0));
                }
                current = null;
                if (used.contains(k)) {
                    errors.addAll(atCallSite(k, found));
                } else if (!found.isEmpty()) {
                    // Only needed to fill a vtable slot; report it if code ever calls it.
                    deferredErrors.put(k, atCallSite(k, found));
                }
            }
            List<ClassT> pending = new ArrayList<>(rtPending);
            rtPending.clear();
            for (ClassT t : pending) {
                finishClass(t);
            }
        }
    }

    /** Why the program can't create its main class to run an instance main(), or null if it can. */
    private static String cannotCreate(ClassInfo c, Checker.EntryPoint ep) {
        if (c.kind == Kind.ENUM) {
            return c.name + " is an enum, whose only values are its constants";
        }
        if (c.isAbstract) {
            return c.name + " is abstract";
        }
        if (ep.owner() == null) {
            return c.name + " has type parameters or shapes";
        }
        if (!c.constructors.isEmpty()
                && c.constructors.stream().noneMatch(m -> ((Constructor) m.member).params().isEmpty())) {
            return c.name + " has no no-argument constructor";
        }
        return null;
    }

    /** Functions that code calls (as opposed to ones generated only to fill vtable slots). */
    private final java.util.Set<FnKey> used = new java.util.HashSet<>();
    /** Errors from functions generated only for vtables, reported if the function turns out to be called. */
    private final Map<FnKey, List<Diagnostic>> deferredErrors = new HashMap<>();

    /** The function being generated, or null outside any function. */
    private FnKey current;
    /** The generator of {@link #current}, for where it calls other functions. */
    private Fn active;

    /** Where a function was first called from: the caller, and the file and position of the call. */
    private record Origin(FnKey caller, String file, Pos pos) {
    }

    private final Map<FnKey, Origin> origins = new HashMap<>();

    /**
     * Errors in the standard library's code for these types (println on a type with no cast to
     * string, say) are the program's: each is reported at the program's own call that led to
     * it, keeping where in the library it arose.
     */
    private List<Diagnostic> atCallSite(FnKey k, List<Diagnostic> found) {
        List<Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : found) {
            Origin o = ck.isLibraryFile(d.file()) ? origins.get(k) : null;
            while (o != null && ck.isLibraryFile(o.file())) {
                o = origins.get(o.caller());
            }
            out.add(o == null ? d : new Diagnostic(o.file(), o.pos().line(), o.pos().column(), d.message() + " ("
                    + java.nio.file.Path.of(d.file()).getFileName() + ":" + d.line() + ")"));
        }
        return out;
    }
    /** An instance main() that the "start" function creates an object for and calls (§56.1). */
    private Checker.EntryPoint startEntry;
    /** Calls made by functions that are not (yet) known to be used. */
    private final Map<FnKey, List<FnKey>> pendingCallees = new HashMap<>();

    /**
     * A function that code calls. It counts as used, and its errors are
     * reported, if the caller is used; a call from a function compiled only
     * for a vtable is recorded and becomes a use if that function is used.
     */
    private int fnId(FnKey k) {
        if (current != null && active != null && active.tb != null) {
            origins.putIfAbsent(k, new Origin(current, active.tb.file, active.pos));
        }
        if (current == null || used.contains(current)) {
            markUsed(k);
        } else {
            pendingCallees.computeIfAbsent(current, x -> new ArrayList<>()).add(k);
        }
        return vtableFnId(k);
    }

    private void markUsed(FnKey k) {
        if (!used.add(k)) {
            return;
        }
        List<Diagnostic> late = deferredErrors.remove(k);
        if (late != null) {
            errors.addAll(late);
        }
        List<FnKey> callees = pendingCallees.remove(k);
        if (callees != null) {
            callees.forEach(this::markUsed);
        }
    }

    /**
     * A function needed only to fill a vtable slot. Every method of a class
     * is compiled for its vtable, but a method that fails for these type
     * arguments (for example sort() on a list of objects without <) is only
     * an error if code actually calls it.
     */
    private int vtableFnId(FnKey k) {
        Integer id = fnIds.get(k);
        if (id != null) {
            return id;
        }
        id = prog.functions.size();
        prog.functions.add(null);
        fnIds.put(k, id);
        work.add(k);
        return id;
    }

    /** A type as traces and run-time errors name it: with its package, as Java's do (com.example.Vec2). */
    private static String qualified(ClassT t) {
        return t.cls().pkg.isEmpty() ? t.toString() : t.cls().pkg + "." + t;
    }

    private String fnName(FnKey k) {
        String owner = k.owner() != null ? qualified(k.owner()) : k.cls().qualifiedName();
        String binders = k.binders().isEmpty() ? "" : k.binders().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).sorted().collect(Collectors.joining(", ", "[", "]"));
        return switch (k.kind()) {
            case "method" -> owner + "." + ((Method) k.member().member).name() + binders;
            case "ctor" -> owner + ".new" + binders;
            case "default-ctor" -> owner + ".new";
            case "dtor" -> owner + ".delete";
            case "destroy" -> owner + "." + DESTROY_SLOT;
            case "init" -> "<static init>";
            case "fini" -> "<static cleanup>";
            case "start" -> "<start>";
            case "lambda" -> owner + "." + k.member().name() + ".lambda";
            case "lambda-destroy" -> owner + "." + k.member().name() + ".lambda." + DESTROY_SLOT;
            default -> owner + "." + k.kind();
        };
    }

    private int slotId(String key) {
        return slotIds.computeIfAbsent(key, k -> {
            prog.slots.add(k);
            return prog.slots.size() - 1;
        });
    }

    private int nativeId(String name) {
        return nativeIds.computeIfAbsent(name, k -> {
            prog.natives.add(k);
            return prog.natives.size() - 1;
        });
    }

    private int stringId(String s) {
        return stringIds.computeIfAbsent(s, k -> {
            prog.strings.add(k);
            return prog.strings.size() - 1;
        });
    }

    // ================================================================ runtime classes and vtables

    private int rtClassId(ClassT t) {
        Integer id = rtClassIds.get(t);
        if (id != null) {
            return id;
        }
        id = prog.classes.size();
        prog.classes.add(null);
        rtClassIds.put(t, id);
        rtPending.add(t);
        ClassT parent = ck.parentType(t);
        if (parent != null) {
            rtClassId(parent);
        }
        return id;
    }

    /** Builds the vtable of a concrete dynamic class: its methods, then inherited ones it does not override. */
    private void finishClass(ClassT t) {
        Map<Integer, Integer> vtable = new HashMap<>();
        // The most-derived method for each slot, to check that overrides keep the return type.
        Map<Integer, ClassT> slotOwner = new HashMap<>();
        Map<Integer, MemberInfo> slotMethod = new HashMap<>();
        for (ClassT c = t; c != null; c = ck.parentType(c)) {
            for (MemberInfo m : c.cls().all) {
                if (!(m.member instanceof Method md) || md.modifiers().isStatic() || !m.existsIn(c.header())
                        || Checker.isNative(md) || hasBinders(md)) {
                    continue;
                }
                int slot = slotId(slotKey(c, m));
                if (md.body() == null) {
                    // Abstract: a subclass fills the slot (the checker makes sure one does), unless
                    // it is already filled by an override.
                    continue;
                }
                if (vtable.containsKey(slot)) {
                    checkOverride(slotOwner.get(slot), slotMethod.get(slot), c, m);
                    continue;
                }
                vtable.put(slot, vtableFnId(new FnKey("method", c.cls(), c, m, Map.of())));
                slotOwner.put(slot, c);
                slotMethod.put(slot, m);
            }
        }
        defaultMethods(vtable, ck.allInterfacesOf(t));
        vtable.put(slotId(DESTROY_SLOT), fnId(new FnKey("destroy", t.cls(), t, null, Map.of())));
        ClassT parent = ck.parentType(t);
        List<Integer> interfaces = ck.allInterfacesOf(t).stream().map(this::rtInterfaceId).toList();
        prog.classes.set(rtClassIds.get(t), new Program.RtClass(qualified(t), parent == null ? -1 : rtClassIds.get(parent),
                vtable, interfaces));
    }

    /**
     * The default methods of these interfaces, in the slots the class (or lambda) doesn't fill
     * itself (§30.4). A default method is compiled once, for the interface: its `this` is any
     * object implementing it, reached through the vtable like any interface call.
     */
    private void defaultMethods(Map<Integer, Integer> vtable, List<ClassT> interfaces) {
        for (ClassT i : interfaces) {
            for (MemberInfo m : i.cls().all) {
                if (m.member instanceof Method md && md.modifiers().isDefault() && md.body() != null
                        && !md.modifiers().isStatic() && !hasBinders(md)) {
                    vtable.putIfAbsent(slotId(slotKey(i, m)), vtableFnId(new FnKey("method", i.cls(), i, m, Map.of())));
                }
            }
        }
    }

    /** An interface's id among the runtime classes, for casts to it; it has no vtable and no objects. */
    private int rtInterfaceId(ClassT t) {
        return rtClassIds.computeIfAbsent(t, k -> {
            prog.classes.add(new Program.RtClass(qualified(k), -1, Map.of(), List.of()));
            return prog.classes.size() - 1;
        });
    }

    /**
     * An override must return the same type as the method it replaces: a
     * virtual call reserves space for the parent's return type.
     */
    private final java.util.Set<String> overrideChecked = new java.util.HashSet<>();

    private void checkOverride(ClassT child, MemberInfo override, ClassT parent, MemberInfo original) {
        if (!overrideChecked.add(child + "|" + override.hashCode() + "|" + parent + "|" + original.hashCode())) {
            return;
        }
        Type mine = ck.returnTypeOf(child, override, Map.of());
        Type theirs = ck.returnTypeOf(parent, original, Map.of());
        if (!mine.equals(theirs)) {
            Method md = (Method) override.member;
            errors.add(new Diagnostic(child.cls().file, md.pos().line(), md.pos().column(),
                    child + "." + md.name() + " returns " + mine + ", but it overrides " + parent + "." + md.name()
                            + ", which returns " + theirs + "; an override must return the same type"));
        }
    }

    private String slotKey(ClassT owner, MemberInfo m) {
        Method md = (Method) m.member;
        return md.name() + ck.paramTypes(owner, m, Map.of()).stream().map(Type::toString)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /** Methods with value binders or type parameters get a copy per use, so they aren't virtual. */
    private static boolean hasBinders(Method md) {
        if (!md.typeParams().isEmpty()) {
            return true;
        }
        for (Param p : md.params()) {
            if (containsBinder(p.type())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBinder(TypeRef t) {
        if (t.valueArgs() != null && t.valueArgs().items().stream().anyMatch(i -> i instanceof Binder)) {
            return true;
        }
        return t.typeArgs().stream().anyMatch(CodeGen::containsBinder);
    }

    // ================================================================ layout

    /** Where an exception's message is, for the VM's report of an uncaught one (§41.4); null if unknown. */
    private long[] messageLayout() {
        ClassInfo e = ck.classNamed("Exception");
        ClassInfo s = ck.classNamed("string");
        if (e == null || s == null) {
            return null;
        }
        ClassT exception = new ClassT(e, List.of(), List.of(), 0);
        ClassT bare = null;
        for (ClassInfo.Shape shape : s.shapes) {
            if (shape.binders.isEmpty()) {
                bare = new ClassT(s, List.of(), List.of(), shape.index);
            }
        }
        if (bare == null) {
            return null;
        }
        Long message = fieldOffset(exception, "message");
        Long size = fieldOffset(bare, "size");
        Long chars = fieldOffset(bare, "chars");
        return message == null || size == null || chars == null ? null : new long[]{message, size, chars};
    }

    private Long fieldOffset(ClassT t, String name) {
        Layout l = layoutOf(t);
        for (Map.Entry<MemberInfo, Long> f : l.offsets().entrySet()) {
            if (f.getKey().name().equals(name)) {
                return f.getValue();
            }
        }
        return null;
    }

    private Layout layoutOf(ClassT t) {
        return layouts.of(t);
    }

    private long sizeOf(Type t) {
        return layouts.sizeOf(t);
    }

    private long alignOf(Type t) {
        return layouts.alignOf(t);
    }

    private long stride(Type element) {
        return layouts.stride(element);
    }

    private static long roundUp(long v, long a) {
        return Layouts.roundUp(v, a);
    }

    private static boolean isAggregate(Type t) {
        return t instanceof ClassT || t instanceof ArrayT;
    }

    // ================================================================ statics

    private void allocateStatics() {
        long end = 0;
        for (ClassInfo c : ck.allClasses()) {
            for (MemberInfo m : c.all) {
                if (m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()) {
                    Type t = ck.staticFieldType(c, f);
                    long off = roundUp(end, alignOf(t));
                    staticOffsets.put(f, off);
                    staticTypes.put(f, t);
                    end = off + sizeOf(t);
                }
            }
        }
        staticEnd = end;
        prog.staticBits = end;
    }

    /** Places the characters of a literal in static data (once per distinct literal and width); returns its offset. */
    private long literalData(String s, int width, long stride) {
        String key = width + ":" + s;
        Long off = literalOffsets.get(key);
        if (off != null) {
            return off;
        }
        long start = roundUp(staticEnd, 64);
        long end = start + Math.max(1, s.length()) * stride;
        staticImage.resize(roundUp(end, 64));
        for (int i = 0; i < s.length(); i++) {
            staticImage.store(start + i * stride, width, s.charAt(i));
        }
        staticEnd = end;
        prog.staticBits = end;
        prog.staticImage = staticImage;
        literalOffsets.put(key, start);
        return start;
    }

    private int staticInit() {
        boolean any = ck.allClasses().stream().anyMatch(c -> c.all.stream().anyMatch(m ->
                m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()
                        && (f.init() != null || needsInit(staticTypes.get(f)))));
        if (!any) {
            return -1;
        }
        ClassInfo first = ck.allClasses().iterator().next();
        return fnId(new FnKey("init", first, null, null, Map.of()));
    }

    /** The function that releases static fields when the program ends (§26.1), or -1 if none hold anything. */
    private int staticFini() {
        boolean any = ck.allClasses().stream().anyMatch(c -> c.all.stream().anyMatch(m ->
                m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()
                        && needsDestroy(staticTypes.get(f))));
        if (!any) {
            return -1;
        }
        ClassInfo first = ck.allClasses().iterator().next();
        return fnId(new FnKey("fini", first, null, null, Map.of()));
    }

    // ================================================================ construction

    private final Map<Type, Boolean> initCache = new HashMap<>();

    /**
     * Whether a value of this type needs more than zeroed memory when it is
     * default-constructed: a struct or unique with constructors, field
     * initializers or such fields, or an array of them.
     */
    private boolean needsInit(Type t) {
        Boolean cached = initCache.get(t);
        if (cached != null) {
            return cached;
        }
        initCache.put(t, false);
        boolean r = false;
        if (t instanceof ClassT ct && !ct.cls().dynamic) {
            r = !ct.cls().constructors.isEmpty();
            for (MemberInfo m : ct.cls().storedFields(ct.header())) {
                r |= ((Field) m.member).init() != null || needsInit(layoutOf(ct).types().get(m));
            }
        } else if (t instanceof ArrayT a && a.size() != null) {
            r = needsInit(a.element());
        }
        initCache.put(t, r);
        return r;
    }

    // ================================================================ destruction

    /** Whether destroying a value of this type has work to do (§16). */
    private boolean needsDestroy(Type t) {
        Boolean cached = destroyCache.get(t);
        if (cached != null) {
            return cached;
        }
        destroyCache.put(t, false); // cycles cannot occur in fixed layouts, but be safe
        boolean r = false;
        if (t instanceof HandleT) {
            r = true; // dropping a handle releases its reference
        } else if (t instanceof ClassT ct && !ct.cls().dynamic) {
            r = ct.cls().destructors.stream().anyMatch(m -> m.existsIn(ct.header()));
            for (MemberInfo m : ct.cls().storedFields(ct.header())) {
                r |= needsDestroy(layoutOf(ct).types().get(m));
            }
        } else if (t instanceof ArrayT a && a.size() != null) {
            r = needsDestroy(a.element());
        }
        destroyCache.put(t, r);
        return r;
    }

    /** Whether a value of this type holds handles, so copying it must add references. */
    private boolean containsHandles(Type t) {
        if (t instanceof HandleT) {
            return true;
        }
        if (t instanceof ClassT ct && !ct.cls().dynamic) {
            for (MemberInfo m : ct.cls().storedFields(ct.header())) {
                if (containsHandles(layoutOf(ct).types().get(m))) {
                    return true;
                }
            }
            return false;
        }
        return t instanceof ArrayT a && a.size() != null && containsHandles(a.element());
    }

    // ================================================================ function generation

    private Program.Function generate(FnKey k, List<Diagnostic> found) {
        Fn f = new Fn(fnName(k), k.cls(), k.owner(), found);
        active = f;
        switch (k.kind()) {
            case "method" -> f.method(k);
            case "ctor", "default-ctor" -> f.constructor(k);
            case "dtor" -> f.userDestructor(k);
            case "destroy" -> f.destroy(k);
            case "init" -> f.staticInit();
            case "fini" -> f.staticFini();
            case "start" -> f.start(startEntry);
            case "lambda" -> f.lambda(k);
            case "lambda-destroy" -> f.lambdaDestroy(k);
            default -> throw new IllegalStateException(k.kind());
        }
        return f.finish();
    }

    /** Where a value lives. */
    private sealed interface Place permits Mem, Obj, Reg {
    }

    /** Fixed memory at (address in register) + offset bits. */
    private record Mem(int addr, long off) implements Place {
    }

    /** Inside a dynamic object: handle register, optional offset register, constant offset. */
    private record Obj(int handle, int offReg, long off) implements Place {
    }

    /** A scalar local held in a register. */
    private record Reg(int reg) implements Place {
    }

    private record Local(Type type, Place place) {
    }

    private static final class Scope {
        final Map<String, Local> vars = new HashMap<>();
        final List<Local> toDestroy = new ArrayList<>();
    }

    /**
     * A loop, or a switch (which `break` leaves but `continue` passes through). A loop's label,
     * if it has one, is what `break outer;` and `continue outer;` name.
     */
    private record Loop(int breakLabel, int continueLabel, int scopeDepth, boolean isSwitch, String label) {
        Loop(int breakLabel, int continueLabel, int scopeDepth, String label) {
            this(breakLabel, continueLabel, scopeDepth, false, label);
        }
    }

    /** Builds one function. */
    private final class Fn {
        final String name;
        final ClassInfo cls;
        ClassT self;   // changed only for a lambda, to the object it captured
        final List<Integer> code = new ArrayList<>();
        final List<Long> consts = new ArrayList<>();
        final Map<Long, Integer> constIndex = new HashMap<>();
        final List<Integer> labels = new ArrayList<>();
        final List<int[]> fixups = new ArrayList<>();
        int registers = 1;
        long frameBits = 0;
        TypedBody tb;
        /** Where {@link #tb} came from, so a lambda in it can type that body again for its own function. */
        FnKey tbSource;
        /** In a lambda's function: the register holding the enclosing object it captured, or -1. */
        int lambdaThis = -1;
        int thisReg = -1;
        int retPtr = -1;
        Type returnType = Type.VOID;
        final Deque<Scope> scopes = new ArrayDeque<>();
        final Deque<Loop> loops = new ArrayDeque<>();
        /** The label of a labeled loop, until that loop is entered (§41.3). */
        String pendingLabel;

        /** Where a switch expression's yields put its value: a register, or built in place; and how they leave. */
        record SwitchOut(Loop loop, int reg, Place into, Type type) {
        }

        /** The switch expressions being generated, innermost first. */
        final Deque<SwitchOut> switchOuts = new ArrayDeque<>();

        /**
         * A switch expression (§41.3), as a switch statement is: its subject in a hidden local,
         * the tests in order, then the cases, whose yields store the value of type {@code t} (in
         * the returned register, or built in {@code into}) and leave. The temporaries of the
         * expression around it are set aside meanwhile, so that statements in its cases release
         * only their own at their ends.
         */
        int switchExpr(SwitchExpr se, Place into, Type t) {
            Checker.SwitchInfo info = tb.switchExprs.get(se);
            List<Integer> outerHandles = new ArrayList<>(pendingHandles);
            List<Mem> outerTemps = new ArrayList<>(pendingTemps);
            List<Type> outerTempTypes = new ArrayList<>(pendingTempTypes);
            pendingHandles.clear();
            pendingTemps.clear();
            pendingTempTypes.clear();
            int r = isAggregate(t) ? -1 : reg();
            scopes.push(new Scope());
            stmt(info.subject());
            int end = label();
            List<Integer> starts = new ArrayList<>();
            int otherwise = -1;
            for (int i = 0; i < se.cases().size(); i++) {
                starts.add(label());
                if (se.cases().get(i).isDefault()) {
                    otherwise = starts.get(i);
                }
            }
            for (int i = 0; i < se.cases().size(); i++) {
                for (Expr test : info.tests().get(i)) {
                    int hit = value(test, Type.BIT);
                    flushTemps();
                    int next = label();
                    jumpIfZero(hit, next);
                    jump(starts.get(i));
                    mark(next);
                }
            }
            if (otherwise >= 0) {
                jump(otherwise);
            } else {
                // Every constant of the enum has a case, so no value gets here.
                emit(Op.TRAP, stringId("no case of the switch expression matched"));
            }
            Loop loop = new Loop(end, -1, scopes.size(), true, null);
            loops.push(loop);
            switchOuts.push(new SwitchOut(loop, r, into, t));
            for (int i = 0; i < se.cases().size(); i++) {
                Case c = se.cases().get(i);
                mark(starts.get(i));
                scoped(new Block(c.pos(), c.body()));
                if (c.arrow()) {
                    jump(end); // a colon case falls through to the next
                }
            }
            switchOuts.pop();
            loops.pop();
            mark(end);
            destroyScope(scopes.peek());
            scopes.pop();
            pendingHandles.addAll(0, outerHandles);
            pendingTemps.addAll(0, outerTemps);
            pendingTempTypes.addAll(0, outerTempTypes);
            return r;
        }

        String takeLabel() {
            String name = pendingLabel;
            pendingLabel = null;
            return name;
        }

        Loop labeledLoop(String name) {
            return loops.stream().filter(x -> name.equals(x.label())).findFirst().orElseThrow();
        }
        Set<String> addrTaken = Set.of();
        Pos pos = new Pos(1, 1);
        /** Source lines of the code emitted so far (§56.1). */
        final List<Program.Line> lines = new ArrayList<>();
        /** Owned handles from the current statement that nothing took over; released at its end. */
        final List<Integer> pendingHandles = new ArrayList<>();
        /** Copies back into class objects after a call that was lent a unique stored in one. */
        final List<Runnable> afterCall = new ArrayList<>();
        /** Temporary objects from the current statement; destroyed at its end. */
        final List<Mem> pendingTemps = new ArrayList<>();
        final List<Type> pendingTempTypes = new ArrayList<>();

        /** Errors found while generating this function. */
        final List<Diagnostic> fnErrors;

        Fn(String name, ClassInfo cls, ClassT self, List<Diagnostic> fnErrors) {
            this.fnErrors = fnErrors;
            this.name = name;
            this.cls = cls;
            this.self = self;
        }

        Program.Function finish() {
            List<Program.Handler> handlers = cleanupPads();
            for (int[] fx : fixups) {
                int target = labels.get(fx[1]);
                if (target < 0) {
                    throw new IllegalStateException("unplaced label in " + name);
                }
                code.set(fx[0], target);
            }
            int[] c = code.stream().mapToInt(Integer::intValue).toArray();
            long[] k = consts.stream().mapToLong(Long::longValue).toArray();
            return new Program.Function(name, c, k, registers, frameBits, List.copyOf(lines), handlers, excReg);
        }

        // ------------------------------------------------------------ exceptions (§41.4)

        /** A try statement (or its catch clauses) being generated: where an exception in it goes. */
        record TryCtx(int dispatch, int scopeDepth, int loopDepth, Block finallyBlock) {
        }

        /** Innermost first. */
        Deque<TryCtx> tries = new ArrayDeque<>();

        record HandleItem(int reg) {
        }

        record TempItem(Mem place, Type type) {
        }

        /**
         * What an exception at this point must release before going on, innermost first, and
         * the dispatch code of the innermost try (or -1: leave the function).
         */
        record CleanupState(List<Object> items, int dispatch) {
            boolean trivial() {
                return items.isEmpty() && dispatch < 0;
            }
        }

        record Region(int start, int end, CleanupState state) {
        }

        final List<Region> regions = new ArrayList<>();
        CleanupState regionState = new CleanupState(List.of(), -1);
        int regionStart = 0;
        boolean emittingPads = false;
        int excReg = -1;

        int excReg() {
            if (excReg < 0) {
                excReg = reg();
            }
            return excReg;
        }

        CleanupState cleanupState() {
            List<Object> items = new ArrayList<>();
            for (int i = pendingHandles.size() - 1; i >= 0; i--) {
                items.add(new HandleItem(pendingHandles.get(i)));
            }
            for (int i = pendingTemps.size() - 1; i >= 0; i--) {
                items.add(new TempItem(pendingTemps.get(i), pendingTempTypes.get(i)));
            }
            int boundary = tries.isEmpty() ? 0 : tries.peek().scopeDepth();
            int depth = scopes.size() - 1;
            for (Scope sc : scopes) {
                if (depth < boundary) {
                    break;
                }
                for (int i = sc.toDestroy.size() - 1; i >= 0; i--) {
                    items.add(sc.toDestroy.get(i));
                }
                depth--;
            }
            return new CleanupState(items, tries.isEmpty() ? -1 : tries.peek().dispatch());
        }

        /** Called before each instruction: starts a new handler region when the cleanup state changes. */
        void trackRegion() {
            CleanupState st = cleanupState();
            if (!st.equals(regionState)) {
                closeRegion();
                regionState = st;
                regionStart = code.size();
            }
        }

        void closeRegion() {
            if (!regionState.trivial() && code.size() > regionStart) {
                regions.add(new Region(regionStart, code.size(), regionState));
            }
            regionStart = code.size();
        }

        /** Emits one cleanup pad per distinct state, after the code; returns the handler table. */
        List<Program.Handler> cleanupPads() {
            closeRegion();
            emittingPads = true;
            Map<CleanupState, Integer> pads = new HashMap<>();
            for (Region r : regions) {
                if (pads.containsKey(r.state())) {
                    continue;
                }
                int pad = label();
                pads.put(r.state(), pad);
                mark(pad);
                int exc = excReg();
                for (Object item : r.state().items()) {
                    switch (item) {
                        case HandleItem h -> emit(Op.HRELEASE, h.reg());
                        case TempItem t -> {
                            // As in flushTemps: a temporary on a path that didn't run has no address.
                            int skip = label();
                            jumpIfZero(t.place().addr(), skip);
                            destroyValue(t.place(), t.type());
                            mark(skip);
                        }
                        case Local l -> destroyValue(l.place(), l.type());
                        default -> throw new IllegalStateException(String.valueOf(item));
                    }
                }
                if (r.state().dispatch() >= 0) {
                    jump(r.state().dispatch());
                } else {
                    emit(Op.RETHROW, exc);
                }
            }
            List<Program.Handler> out = new ArrayList<>();
            for (Region r : regions) {
                out.add(new Program.Handler(r.start(), r.end(), labels.get(pads.get(r.state()))));
            }
            return out;
        }

        /**
         * Runs the finally blocks of the tries being left, innermost first, for a return
         * (loopDepth -1: all of them) or a break or continue to the loop at that depth. Each
         * runs outside its own try, so an exception in it goes to the next one out.
         */
        void runFinallys(int loopDepth) {
            Deque<TryCtx> saved = new ArrayDeque<>(tries);
            while (!tries.isEmpty() && tries.peek().loopDepth() > loopDepth) {
                TryCtx ctx = tries.pop();
                if (ctx.finallyBlock() != null) {
                    scoped(ctx.finallyBlock());
                }
            }
            tries = saved; // the code after the jump is unreachable; generation goes on inside the tries
        }

        void tryStatement(Try t) {
            int dispatch = label();
            int end = label();
            Block fin = t.finallyBlock();
            tries.push(new TryCtx(dispatch, scopes.size(), loops.size(), fin));
            scoped(t.body());
            tries.pop();
            if (fin != null) {
                scoped(fin);
            }
            jump(end);

            // An exception from the body arrives here, its references released.
            mark(dispatch);
            int exc = reg();
            emit(Op.MOV, exc, excReg());
            int catchDispatch = label();
            for (Catch c : t.catches()) {
                ClassT ct = tb.catchTypes.get(c);
                int test = reg();
                emit(Op.INSTANCEOF, test, exc, rtClassId(ct));
                int next = label();
                jumpIfZero(test, next);
                if (fin != null) {
                    tries.push(new TryCtx(catchDispatch, scopes.size(), loops.size(), fin)); // finally runs after a throwing catch too
                }
                Scope sc = new Scope();
                int held = reg();
                emit(Op.MOV, held, exc);
                Local caught = new Local(new HandleT(ct), new Reg(held)); // owns the exception
                sc.vars.put(c.name(), caught);
                sc.toDestroy.add(caught);
                scopes.push(sc);
                stmt(c.body());
                destroyScope(sc);
                scopes.pop();
                if (fin != null) {
                    tries.pop();
                    scoped(fin);
                }
                jump(end);
                mark(next);
            }
            // No catch matched: finally, then pass it on.
            if (fin != null) {
                scoped(fin);
            }
            emit(Op.RETHROW, exc);
            if (fin != null && !t.catches().isEmpty()) {
                mark(catchDispatch);
                int again = reg();
                emit(Op.MOV, again, excReg());
                scoped(fin);
                emit(Op.RETHROW, again);
            }
            mark(end);
        }

        // ------------------------------------------------------------ emission

        void emit(Op op, int... operands) {
            if (operands.length != op.operands.length()) {
                throw new IllegalStateException(op + " takes " + op.operands.length() + " operands");
            }
            if (!emittingPads) {
                trackRegion();
            }
            if (tb != null && tb.file != null) {
                Program.Line last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
                if (last == null || last.line() != pos.line() || !last.file().equals(tb.file)) {
                    if (last != null && last.pc() == code.size()) {
                        lines.remove(lines.size() - 1); // nothing was emitted for that line
                    }
                    lines.add(new Program.Line(code.size(), tb.file, pos.line()));
                }
            }
            code.add(op.ordinal());
            for (int o : operands) {
                code.add(o);
            }
        }

        int reg() {
            return registers++;
        }

        /** Allocates {@code n} consecutive registers; returns the first. */
        int regs(int n) {
            int first = registers;
            registers += n;
            return first;
        }

        int constant(long v) {
            Integer idx = constIndex.get(v);
            if (idx == null) {
                idx = consts.size();
                consts.add(v);
                constIndex.put(v, idx);
            }
            int r = reg();
            emit(Op.CONST, r, idx);
            return r;
        }

        int label() {
            labels.add(-1);
            return labels.size() - 1;
        }

        void mark(int label) {
            labels.set(label, code.size());
        }

        void jump(int label) {
            emit(Op.JMP, 0);
            fixups.add(new int[]{code.size() - 1, label});
        }

        void jumpIfZero(int reg, int label) {
            emit(Op.JZ, reg, 0);
            fixups.add(new int[]{code.size() - 1, label});
        }

        void jumpIfNotZero(int reg, int label) {
            emit(Op.JNZ, reg, 0);
            fixups.add(new int[]{code.size() - 1, label});
        }

        int imm(long v) {
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new Unsupported(pos, "offset or size " + v + " is too large for this VM");
            }
            return (int) v;
        }

        /** A zeroed-on-entry frame slot. */
        long slot(long bits, long align) {
            frameBits = roundUp(frameBits, Math.max(1, Math.min(64, align)));
            long off = frameBits;
            frameBits += Math.max(bits, 0);
            return off;
        }

        Mem temp(Type t) {
            // Objects and arrays in the frame start on a 64-bit boundary even when their own
            // alignment is smaller, so reading one through a differently typed address is safe.
            long off = slot(sizeOf(t), isAggregate(t) ? 64 : alignOf(t));
            int r = reg();
            emit(Op.FRAME, r, imm(off));
            return new Mem(r, 0);
        }

        Type type(Expr e) {
            Type t = tb.types.get(e);
            if (t == null) {
                throw new IllegalStateException("untyped expression at " + e.pos());
            }
            return t;
        }

        // ------------------------------------------------------------ places

        int width(Type t) {
            return switch (t) {
                case IntT i -> imm(i.width().intValue().longValueExact());
                case FixedT f -> imm(f.width().intValue().longValueExact());
                case BitT b -> 1;
                case FloatT f -> 32;
                case DoubleT d -> 64;
                case AddrT a -> 64;
                case HandleT h -> 64;
                case NullT n -> 64;
                default -> throw new Unsupported(pos, "no scalar width for " + t);
            };
        }

        int signed(Type t) {
            return isSigned(t) ? 1 : 0;
        }

        boolean isSigned(Type t) {
            return (t instanceof IntT i && i.signed()) || (t instanceof FixedT f && f.signed());
        }

        int frac(FixedT f) {
            return imm(f.fracBits().intValue().longValueExact());
        }

        int doubleConstant(double d) {
            return constant(Double.doubleToRawLongBits(d));
        }

        int load(Place p, Type t) {
            switch (p) {
                case Reg r -> {
                    // Copy, so later assignments to the variable don't change this value.
                    int d = reg();
                    emit(Op.MOV, d, r.reg());
                    return d;
                }
                case Mem m -> {
                    int d = reg();
                    emit(Op.LOAD, d, m.addr(), imm(m.off()), width(t), signed(t));
                    return d;
                }
                case Obj o -> {
                    int d = reg();
                    emit(Op.HLOAD, d, o.handle(), o.offReg(), imm(o.off()), width(t), signed(t));
                    return d;
                }
            }
        }

        void store(Place p, Type t, int src) {
            switch (p) {
                case Reg r -> emit(Op.MOV, r.reg(), src);
                case Mem m -> emit(Op.STORE, m.addr(), imm(m.off()), src, width(t));
                case Obj o -> emit(Op.HSTORE, o.handle(), o.offReg(), imm(o.off()), src, width(t));
            }
        }

        int addressOf(Place p) {
            if (p instanceof Mem m) {
                if (m.off() == 0) {
                    return m.addr();
                }
                int d = reg();
                emit(Op.ADDI, d, m.addr(), imm(m.off()));
                return d;
            }
            throw new Unsupported(pos, "this value has no address");
        }

        Place offset(Place p, long bits) {
            return switch (p) {
                case Mem m -> new Mem(m.addr(), m.off() + bits);
                case Obj o -> new Obj(o.handle(), o.offReg(), o.off() + bits);
                case Reg r -> throw new IllegalStateException("offset into a register");
            };
        }

        Place indexed(Place p, int index, long stride) {
            int scaled = reg();
            emit(Op.MULI, scaled, index, imm(stride));
            return switch (p) {
                case Mem m -> {
                    int a = reg();
                    emit(Op.ADD, a, m.addr(), scaled);
                    yield new Mem(a, m.off());
                }
                case Obj o -> {
                    int off = scaled;
                    if (o.offReg() >= 0) {
                        off = reg();
                        emit(Op.ADD, off, o.offReg(), scaled);
                    }
                    yield new Obj(o.handle(), off, o.off());
                }
                case Reg r -> throw new IllegalStateException("index into a register");
            };
        }

        void copy(Place dst, Place src, long bits) {
            if (bits == 0) {
                return;
            }
            if (dst instanceof Mem && src instanceof Mem) {
                emit(Op.MCOPY, addressOf(dst), addressOf(src), imm(bits));
            } else if (dst instanceof Obj o && src instanceof Mem) {
                emit(Op.HCOPYIN, o.handle(), o.offReg(), imm(o.off()), addressOf(src), imm(bits));
            } else if (dst instanceof Mem && src instanceof Obj o) {
                emit(Op.HCOPYOUT, addressOf(dst), o.handle(), o.offReg(), imm(o.off()), imm(bits));
            } else if (dst instanceof Obj && src instanceof Obj) {
                long off = slot(bits, 64);
                int t = reg();
                emit(Op.FRAME, t, imm(off));
                copy(new Mem(t, 0), src, bits);
                copy(dst, new Mem(t, 0), bits);
            } else {
                throw new IllegalStateException("copy between " + dst + " and " + src);
            }
        }

        void zero(Place p, long bits) {
            if (p instanceof Mem m) {
                emit(Op.MZERO, addressOf(m), imm(bits));
            } else {
                Mem t = tempBits(bits);
                copy(p, t, bits);
            }
        }

        Mem tempBits(long bits) {
            long off = slot(bits, 64);
            int r = reg();
            emit(Op.FRAME, r, imm(off));
            emit(Op.MZERO, r, imm(bits));
            return new Mem(r, 0);
        }

        /**
         * Runs {@code body} with the object at {@code p} available at a fixed
         * address. Objects inside dynamic objects are copied out and back.
         */
        void withAddress(Place p, Type t, java.util.function.IntConsumer body) {
            if (p instanceof Mem) {
                body.accept(addressOf(p));
                return;
            }
            Mem tmp = temp(t);
            copy(tmp, p, sizeOf(t));
            body.accept(addressOf(tmp));
            copy(p, tmp, sizeOf(t));
        }

        Place fieldPlace(MemberInfo m, ClassT owner, Place object) {
            long off = layoutOf(owner).offsets().get(m);
            return offset(object, off);
        }

        /** The place of `this` as an object. */
        Place thisObject() {
            if (self == null || thisReg < 0) {
                throw new Unsupported(pos, "no object here");
            }
            return self.cls().dynamic ? new Obj(thisValue(), -1, 0) : new Mem(thisValue(), 0);
        }

        /** The `this` value: the object (or its address); in a lambda, the object it captured. */
        int thisValue() {
            return lambdaThis >= 0 ? lambdaThis : thisReg;
        }

        Place staticPlace(Field f) {
            int r = reg();
            emit(Op.STATIC, r, imm(staticOffsets.get(f)));
            return new Mem(r, 0);
        }

        // ------------------------------------------------------------ function kinds

        /**
         * Sets up hidden parameters: [this][return pointer][declared parameters...].
         * `this` comes first because VCALL dispatches on its first argument.
         * Returns the next register.
         */
        int params(boolean aggregateReturn, boolean hasThis) {
            int next = 1;
            if (hasThis) {
                thisReg = next++;
            }
            if (aggregateReturn) {
                retPtr = next++;
            }
            registers = Math.max(registers, next);
            return next;
        }

        void bindParams(List<Param> ps, List<Type> types, int first) {
            registers = Math.max(registers, first + ps.size());
            Scope s = new Scope();
            for (int i = 0; i < ps.size(); i++) {
                Param p = ps.get(i);
                Type t = types.get(i);
                int r = first + i;
                Place place;
                if (isAggregate(t)) {
                    place = new Mem(r, 0);
                } else if (addrTaken.contains(p.name())) {
                    Mem m = temp(t);
                    store(m, t, r);
                    place = m;
                } else {
                    place = new Reg(r);
                }
                Local local = new Local(t, place);
                s.vars.put(p.name(), local);
                if (needsDestroy(t) && !Checker.isUnique(t)) {
                    s.toDestroy.add(local); // arguments are passed owned; the callee drops them (uniques are lent)
                }
            }
            scopes.push(s);
        }

        void method(FnKey k) {
            Method md = (Method) k.member().member;
            tb = typed(k);
            returnType = tb.returnType;
            addrTaken = AddressTaken.in(md.body());
            boolean isStatic = md.modifiers().isStatic();
            int first = params(isAggregate(returnType), !isStatic);
            bindParams(md.params(), tb.params, first);
            block(md.body(), false);
            endOfFunction();
        }

        void endOfFunction() {
            destroyScopes(0);
            if (returnType instanceof VoidT || retPtr >= 0) {
                emit(Op.RETV);
            } else {
                emit(Op.TRAP, stringId("reached the end of " + name + " without returning"));
            }
        }

        void constructor(FnKey k) {
            ClassT t = k.owner();
            Constructor ctor = k.member() == null ? null : (Constructor) k.member().member;
            int first = params(false, true);
            List<Stmt> body = ctor == null ? List.of() : ctor.body().stmts();
            if (ctor != null) {
                tb = typed(k);
                addrTaken = AddressTaken.in(ctor.body());
                bindParams(ctor.params(), tb.params, first);
            } else {
                scopes.push(new Scope());
            }
            int start = 0;
            if (t.cls().dynamic) {
                ClassT parent = ck.parentType(t);
                if (!body.isEmpty() && body.get(0) instanceof ExprStmt es && es.expr() instanceof Call c
                        && c.callee() instanceof SuperRef) {
                    stmt(body.get(0));
                    start = 1;
                } else if (parent != null) {
                    callDefaultConstructor(parent, thisReg);
                }
            }
            initFields(t);
            for (int i = start; i < body.size(); i++) {
                stmt(body.get(i));
            }
            endOfFunction();
        }

        /** Field initializers and default construction of inline fields, in declaration order. */
        void initFields(ClassT t) {
            TypedBody saved = tb;
            for (MemberInfo m : t.cls().storedFields(t.header())) {
                Field f = (Field) m.member;
                Type ft = layoutOf(t).types().get(m);
                Place p = fieldPlace(m, t, thisObject());
                if (f.init() != null) {
                    tb = ck.typeBody(t.cls(), t, m, Map.of());
                    tbSource = new FnKey("body", t.cls(), t, m, Map.of());
                    reportTypingErrors(tb);
                    pos = f.init().pos();
                    into(p, f.init(), ft);
                    flushTemps();
                } else if (needsInit(ft)) {
                    requireDefaultConstructible(ft, f.pos(), "field " + f.name());
                    constructDefault(p, ft);
                }
            }
            tb = saved;
        }

        /**
         * The same rule as the checker's, found here when a type argument (like
         * File in Slot<File>) makes the type known.
         */
        void requireDefaultConstructible(Type t, Pos at, String what) {
            Type element = t;
            while (element instanceof ArrayT a) {
                element = a.element();
            }
            if (element instanceof ClassT ct && !ct.cls().dynamic && !hasNoArgConstructor(ct)) {
                throw new Unsupported(at, t instanceof ArrayT
                        ? "each element of " + what + " (" + t + ") is default-constructed, but " + ct.cls().name
                                + " has no no-argument constructor"
                        : what + " of type " + ct + " needs an initializer: " + ct.cls().name
                                + " has no no-argument constructor");
            }
        }

        boolean hasNoArgConstructor(ClassT t) {
            return t.cls().constructors.isEmpty() || t.cls().constructors.stream().anyMatch(m ->
                    m.existsIn(t.header()) && ((Constructor) m.member).params().isEmpty());
        }

        void callDefaultConstructor(ClassT t, int thisValue) {
            if (t.cls().isRoot()) {
                return;
            }
            MemberInfo ctor = null;
            for (MemberInfo m : t.cls().constructors) {
                if (m.existsIn(t.header()) && ((Constructor) m.member).params().isEmpty()) {
                    ctor = m;
                }
            }
            if (ctor == null && !t.cls().constructors.isEmpty()) {
                throw new Unsupported(pos, t + " has no no-argument constructor; call super(...) explicitly");
            }
            int fn = ctor == null ? fnId(new FnKey("default-ctor", t.cls(), t, null, Map.of()))
                    : fnId(new FnKey("ctor", t.cls(), t, ctor, Map.of()));
            int base = regs(1);
            emit(Op.MOV, base, thisValue);
            emit(Op.CALL, fn, base, 1, -1);
        }

        /**
         * Default-constructs a value in place: zeroes it, then runs the
         * no-argument (or generated) constructor of a struct or unique, or of
         * every element of an array of them.
         */
        void constructDefault(Place p, Type t) {
            zero(p, sizeOf(t));
            if (needsInit(t)) {
                initZeroed(p, t);
            }
        }

        /** Constructs a value whose memory is already zero. */
        void initZeroed(Place p, Type t) {
            if (t instanceof ClassT ct) {
                withAddress(p, ct, addr -> callDefaultConstructor(ct, addr));
            } else if (t instanceof ArrayT a) {
                forEachElement(p, a, false, e -> initZeroed(e, a.element()));
            }
        }

        /**
         * Runs {@code body} on each element of an array: unrolled for a few
         * elements, otherwise as a loop in the generated code.
         */
        void forEachElement(Place p, ArrayT a, boolean reverse, java.util.function.Consumer<Place> body) {
            long n = a.size().intValue().longValueExact();
            long stride = stride(a.element());
            if (n <= 4) {
                for (long k = 0; k < n; k++) {
                    long i = reverse ? n - 1 - k : k;
                    body.accept(offset(p, i * stride));
                }
                return;
            }
            int i = reg();
            emit(Op.MOV, i, constant(reverse ? n - 1 : 0));
            int limit = constant(reverse ? -1 : n);
            int top = label();
            int end = label();
            mark(top);
            int more = reg();
            emit(Op.NE, more, i, limit);
            jumpIfZero(more, end);
            body.accept(indexed(p, i, stride));
            emit(Op.ADDI, i, i, reverse ? -1 : 1);
            jump(top);
            mark(end);
        }

        void userDestructor(FnKey k) {
            Destructor d = (Destructor) k.member().member;
            tb = typed(k);
            addrTaken = AddressTaken.in(d.body());
            params(false, true);
            scopes.push(new Scope());
            block(d.body(), false);
            endOfFunction();
        }

        /** Destructor body, then fields in reverse order, then the parent's part (§16). */
        void destroy(FnKey k) {
            ClassT t = k.owner();
            params(false, true);
            scopes.push(new Scope());
            for (MemberInfo m : t.cls().destructors) {
                if (m.existsIn(t.header())) {
                    int fn = fnId(new FnKey("dtor", t.cls(), t, m, Map.of()));
                    int base = regs(1);
                    emit(Op.MOV, base, thisReg);
                    emit(Op.CALL, fn, base, 1, -1);
                }
            }
            List<MemberInfo> fields = new ArrayList<>(t.cls().storedFields(t.header()));
            Collections.reverse(fields);
            for (MemberInfo m : fields) {
                Type ft = layoutOf(t).types().get(m);
                if (needsDestroy(ft)) {
                    destroyValue(fieldPlace(m, t, thisObject()), ft);
                }
            }
            if (t.cls().dynamic) {
                ClassT parent = ck.parentType(t);
                if (parent != null) {
                    int fn = fnId(new FnKey("destroy", parent.cls(), parent, null, Map.of()));
                    int base = regs(1);
                    emit(Op.MOV, base, thisReg);
                    emit(Op.CALL, fn, base, 1, -1);
                }
            }
            emit(Op.RETV);
        }

        /** Destroys an inline value (object, array or handle) without releasing its memory. */
        void destroyValue(Place p, Type t) {
            if (t instanceof HandleT) {
                emit(Op.HRELEASE, load(p, t));
            } else if (t instanceof ClassT ct) {
                int fn = fnId(new FnKey("destroy", ct.cls(), ct, null, Map.of()));
                withAddress(p, ct, addr -> {
                    int base = regs(1);
                    emit(Op.MOV, base, addr);
                    emit(Op.CALL, fn, base, 1, -1);
                });
            } else if (t instanceof ArrayT a && needsDestroy(a.element())) {
                forEachElement(p, a, true, e -> destroyValue(e, a.element()));
            }
        }

        /** Adds a reference for every handle inside a value that was just copied. */
        void retainValue(Place p, Type t) {
            if (t instanceof HandleT) {
                emit(Op.HRETAIN, load(p, t));
            } else if (t instanceof ClassT ct && !ct.cls().dynamic) {
                for (MemberInfo m : ct.cls().storedFields(ct.header())) {
                    Type ft = layoutOf(ct).types().get(m);
                    if (containsHandles(ft)) {
                        retainValue(fieldPlace(m, ct, p), ft);
                    }
                }
            } else if (t instanceof ArrayT a && a.size() != null && containsHandles(a.element())) {
                forEachElement(p, a, false, e -> retainValue(e, a.element()));
            }
        }

        /**
         * Ends a full expression: releases owned handles and destroys temporary
         * objects that nothing took over. Registers and slots are cleared
         * afterwards, so a temporary on a path that doesn't run next time
         * (like the right side of && in a loop) is never released twice.
         */
        void flushTemps() {
            if (pendingHandles.isEmpty() && pendingTemps.isEmpty()) {
                return;
            }
            int zero = constant(0);
            for (int r : pendingHandles) {
                emit(Op.HRELEASE, r);
                emit(Op.MOV, r, zero);
            }
            for (int i = 0; i < pendingTemps.size(); i++) {
                Mem m = pendingTemps.get(i);
                Type t = pendingTempTypes.get(i);
                // The temporary may sit on a path that didn't run (the right side of && or ||).
                // Its address register is set only where it is created, and registers start at 0,
                // so skip it unless it was created, and clear the register for the next time round.
                int skip = label();
                jumpIfZero(m.addr(), skip);
                destroyValue(m, t);
                zero(m, sizeOf(t));
                emit(Op.MOV, m.addr(), zero);
                mark(skip);
            }
            pendingHandles.clear();
            pendingTemps.clear();
            pendingTempTypes.clear();
        }

        /** Whether an expression produces a new reference or object that the caller owns. */
        boolean isOwnedTemp(Expr e) {
            if (tb.rewritten.containsKey(e)) {
                return isOwnedTemp(tb.rewritten.get(e)); // p.x() on a record is a field read, which owns nothing
            }
            return e instanceof New || e instanceof Lambda || e instanceof Call
                    || (e instanceof SwitchExpr && type(e) instanceof HandleT) // a case's yield took a reference
                    || (tb.calls.containsKey(e) && (e instanceof Binary || e instanceof Unary || e instanceof Index
                        || e instanceof Cast));
        }

        /** A handle value with a reference the caller now owns (+1). */
        int ownedHandle(Expr e, Type t) {
            if (isOwnedTemp(e)) {
                return valueRaw(e, t);
            }
            int r = value(e, t);
            emit(Op.HRETAIN, r);
            return r;
        }

        void staticInit() {
            scopes.push(new Scope());
            for (ClassInfo c : ck.allClasses()) {
                for (MemberInfo m : c.all) {
                    if (m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()
                            && f.init() != null) {
                        tb = ck.typeBody(c, null, m, Map.of());
                        tbSource = new FnKey("body", c, null, m, Map.of());
                        reportTypingErrors(tb);
                        pos = f.init().pos();
                        into(staticPlace(f), f.init(), staticTypes.get(f));
                        flushTemps();
                    } else if (m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()
                            && needsInit(staticTypes.get(f))) {
                        pos = f.pos();
                        requireDefaultConstructible(staticTypes.get(f), f.pos(), "static field " + f.name());
                        initZeroed(staticPlace(f), staticTypes.get(f));   // static data starts zeroed
                    }
                }
            }
            emit(Op.RETV);
        }

        /**
         * Releases static fields as the program ends, last created first: the program's own
         * classes before the library's, and System (whose streams a destructor may still
         * print to) last.
         */
        void staticFini() {
            scopes.push(new Scope());
            List<ClassInfo> classes = new ArrayList<>(ck.allClasses());
            Collections.reverse(classes);
            classes.sort(java.util.Comparator.comparingInt(c -> c.builtin ? (c.name.equals("System") ? 2 : 1) : 0));
            for (ClassInfo c : classes) {
                List<MemberInfo> members = new ArrayList<>(c.all);
                Collections.reverse(members);
                for (MemberInfo m : members) {
                    if (m.member instanceof Field f && f.modifiers().isStatic() && !f.modifiers().isConst()
                            && needsDestroy(staticTypes.get(f))) {
                        pos = f.pos();
                        Type t = staticTypes.get(f);
                        Place p = staticPlace(f);
                        destroyValue(p, t);
                        zero(p, sizeOf(t)); // nothing refers to what was released
                    }
                }
            }
            emit(Op.RETV);
        }

        /**
         * Runs an instance main() (§56.1): creates the main class's object with its
         * no-argument constructor, calls main() on it, and lets the object go before
         * static fields are released.
         */
        void start(Checker.EntryPoint ep) {
            scopes.push(new Scope());
            ClassT t = self;
            Type rt = ck.returnTypeOf(ep.owner(), ep.method(), Map.of());
            Place obj;
            Type objType;
            int receiver;
            if (t.cls().dynamic) {
                int handle = reg();
                emit(Op.HNEW, handle, rtClassId(t), imm(layoutOf(t).size()));
                obj = new Reg(handle);
                objType = new HandleT(t);
                receiver = handle;
                pendingHandles.add(handle);   // released if the constructor throws
                callDefaultConstructor(t, handle);
                pendingHandles.remove(Integer.valueOf(handle));
            } else {
                Mem m = temp(t);
                constructDefault(m, t);
                obj = m;
                objType = t;
                receiver = addressOf(m);
            }
            Local local = new Local(objType, obj);
            if (needsDestroy(objType)) {
                scopes.peek().toDestroy.add(local);
            }
            int base = regs(1);
            emit(Op.MOV, base, receiver);
            int result = rt instanceof VoidT ? -1 : reg();
            emit(Op.CALL, fnId(new FnKey("method", ep.declaring(), ep.owner(), ep.method(), Map.of())), base, 1, result);
            destroyScopes(0);
            if (result < 0) {
                emit(Op.RETV);
            } else {
                emit(Op.RET, result);
            }
        }

        TypedBody typed(FnKey k) {
            TypedBody t = ck.typeBody(k.cls(), k.owner(), k.member(), k.binders());
            reportTypingErrors(t);
            tbSource = new FnKey("body", k.cls(), k.owner(), k.member(), k.binders());
            return t;
        }

        void reportTypingErrors(TypedBody t) {
            for (Diagnostic d : t.errors) {
                fnErrors.add(new Diagnostic(d.file(), d.line(), d.column(), "in " + name + ": " + d.message()));
            }
            if (!t.errors.isEmpty()) {
                throw new Unsupported(pos, null); // already reported above
            }
        }

        // ------------------------------------------------------------ statements

        void block(Block b, boolean newScope) {
            if (newScope) {
                scopes.push(new Scope());
            }
            for (Stmt s : b.stmts()) {
                stmt(s);
            }
            if (newScope) {
                destroyScope(scopes.peek());
                scopes.pop();
            }
        }

        void destroyScope(Scope s) {
            for (int i = s.toDestroy.size() - 1; i >= 0; i--) {
                Local l = s.toDestroy.get(i);
                destroyValue(l.place(), l.type());
            }
        }

        /** Destroys locals in every scope deeper than {@code depth} (outermost scope is depth 1). */
        void destroyScopes(int depth) {
            destroyScopes(depth, null);
        }

        /** As above, except {@code skip}, a local that has been moved out. */
        void destroyScopes(int depth, Local skip) {
            int level = scopes.size();
            for (Scope s : scopes) {
                if (level > depth) {
                    for (int i = s.toDestroy.size() - 1; i >= 0; i--) {
                        Local l = s.toDestroy.get(i);
                        if (l != skip) {
                            destroyValue(l.place(), l.type());
                        }
                    }
                }
                level--;
            }
        }

        Local lookup(String n) {
            for (Scope s : scopes) {
                Local l = s.vars.get(n);
                if (l != null) {
                    return l;
                }
            }
            throw new IllegalStateException("unknown local " + n);
        }

        void stmt(Stmt s) {
            if (tb.statementSteps.containsKey(s)) {
                stmt(tb.statementSteps.get(s)); // the statements the checker wrote for it
                return;
            }
            pos = s.pos();
            declareBindings(s);
            switch (s) {
                case Block b -> block(b, true);
                case VarDecl v -> {
                    Type t = tb.locals.get(v);
                    Place p;
                    if (!isAggregate(t) && !addrTaken.contains(v.name())) {
                        p = new Reg(reg());
                    } else {
                        p = temp(t);
                    }
                    if (v.init() != null) {
                        into(p, v.init(), t);
                        flushTemps();
                    } else if (isAggregate(t)) {
                        requireDefaultConstructible(t, v.pos(), "variable " + v.name());
                        constructDefault(p, t);
                    } else {
                        store(p, t, constant(0));
                    }
                    Local l = new Local(t, p);
                    scopes.peek().vars.put(v.name(), l);
                    if (needsDestroy(t)) {
                        scopes.peek().toDestroy.add(l);
                    }
                }
                case ExprStmt e -> {
                    effect(e.expr());
                    flushTemps();
                }
                case ForEach fe -> stmt(tb.desugared.get(fe)); // the ordinary loop the checker wrote
                case Throw th -> {
                    int h = ownedHandle(th.value(), type(th.value())); // the exception takes this reference
                    flushTemps();
                    emit(Op.THROW, h);
                }
                case Try t when !t.resources().isEmpty() -> stmt(tb.desugared.get(t)); // the try/finally the checker wrote
                case Try t -> tryStatement(t);
                case Switch sw -> {
                    Checker.SwitchInfo info = tb.switches.get(sw);
                    scopes.push(new Scope());
                    stmt(info.subject());
                    int end = label();
                    List<Integer> starts = new ArrayList<>();
                    int otherwise = end;
                    for (int i = 0; i < sw.cases().size(); i++) {
                        starts.add(label());
                        if (sw.cases().get(i).isDefault()) {
                            otherwise = starts.get(i);
                        }
                    }
                    // Test the labels in order; jump to the first case that matches.
                    for (int i = 0; i < sw.cases().size(); i++) {
                        for (Expr test : info.tests().get(i)) {
                            int r = value(test, Type.BIT);
                            flushTemps();
                            int next = label();
                            jumpIfZero(r, next);
                            jump(starts.get(i));
                            mark(next);
                        }
                    }
                    jump(otherwise);
                    loops.push(new Loop(end, -1, scopes.size(), true, null));
                    for (int i = 0; i < sw.cases().size(); i++) {
                        Case c = sw.cases().get(i);
                        mark(starts.get(i));
                        scoped(new Block(c.pos(), c.body()));
                        if (c.arrow()) {
                            jump(end); // no falling through
                        }
                    }
                    loops.pop();
                    mark(end);
                    destroyScope(scopes.peek());
                    scopes.pop();
                }
                case If i -> {
                    int otherwise = label();
                    int end = label();
                    int cond = value(i.cond(), Type.BIT);
                    flushTemps();
                    jumpIfZero(cond, otherwise);
                    scoped(i.then());
                    if (i.otherwise() != null) {
                        jump(end);
                    }
                    mark(otherwise);
                    if (i.otherwise() != null) {
                        scoped(i.otherwise());
                    }
                    mark(end);
                }
                case While w -> {
                    String name = takeLabel();
                    int top = label();
                    int end = label();
                    mark(top);
                    int cond = value(w.cond(), Type.BIT);
                    flushTemps();
                    jumpIfZero(cond, end);
                    loops.push(new Loop(end, top, scopes.size(), name));
                    scoped(w.body());
                    loops.pop();
                    jump(top);
                    mark(end);
                }
                case For f -> {
                    String name = takeLabel();
                    scopes.push(new Scope());
                    if (f.init() != null) {
                        stmt(f.init());
                    }
                    int top = label();
                    int next = label();
                    int end = label();
                    mark(top);
                    if (f.cond() != null) {
                        int cond = value(f.cond(), Type.BIT);
                        flushTemps();
                        jumpIfZero(cond, end);
                    }
                    loops.push(new Loop(end, next, scopes.size(), name));
                    scoped(f.body());
                    loops.pop();
                    mark(next);
                    if (f.update() != null) {
                        effect(f.update());
                        flushTemps();
                    }
                    jump(top);
                    mark(end);
                    destroyScope(scopes.peek());
                    scopes.pop();
                }
                case Return r -> {
                    if (r.value() == null) {
                        runFinallys(-1);
                        destroyScopes(0);
                        emit(Op.RETV);
                    } else if (retPtr >= 0) {
                        Local moved = Checker.isUnique(returnType) && r.value() instanceof Name n
                                && "local".equals(tb.nameKinds.get(n)) ? lookup(n.name()) : null;
                        if (moved != null) {
                            copy(new Mem(retPtr, 0), moved.place(), sizeOf(returnType)); // moved out
                        } else {
                            into(new Mem(retPtr, 0), r.value(), returnType);
                        }
                        flushTemps();
                        runFinallys(-1);
                        destroyScopes(0, moved);
                        emit(Op.RETV);
                    } else {
                        int v = returnType instanceof HandleT ? ownedHandle(r.value(), returnType)
                                : value(r.value(), returnType);
                        flushTemps();
                        runFinallys(-1);
                        destroyScopes(0);
                        emit(Op.RET, v);
                    }
                }
                case Break b -> {
                    Loop l = b.label() == null ? loops.peek() : labeledLoop(b.label());
                    runFinallys(loops.size() - 1 - List.copyOf(loops).indexOf(l));
                    destroyScopes(l.scopeDepth());
                    jump(l.breakLabel());
                }
                case Continue c -> {
                    Loop l = c.label() == null ? loops.stream().filter(x -> !x.isSwitch()).findFirst().orElseThrow()
                            : labeledLoop(c.label());
                    runFinallys(loops.size() - 1 - List.copyOf(loops).indexOf(l));
                    destroyScopes(l.scopeDepth());
                    jump(l.continueLabel());
                }
                case Yield y -> {
                    // The switch expression's value, then out of it as a break would (§41.3).
                    SwitchOut out = switchOuts.peek();
                    if (out.type() instanceof HandleT) {
                        emit(Op.MOV, out.reg(), ownedHandle(y.value(), out.type()));
                    } else if (isAggregate(out.type())) {
                        into(out.into(), y.value(), out.type());
                    } else {
                        emit(Op.MOV, out.reg(), value(y.value(), out.type()));
                    }
                    flushTemps();
                    Loop l = out.loop();
                    runFinallys(loops.size() - 1 - List.copyOf(loops).indexOf(l));
                    destroyScopes(l.scopeDepth());
                    jump(l.breakLabel());
                }
                case Assert a -> {
                    Stmt check = tb.assertions.get(a);
                    if (check != null) {
                        stmt(check); // with -Wassert: if (!cond) throw new AssertionError(...) (§41.4)
                    }
                }
                case Labeled l -> {
                    // The loop it labels takes the name, also when that is the loop a for-each became.
                    pendingLabel = l.label();
                    stmt(l.body());
                }
                case DeleteStmt d -> {
                    AddrT a = (AddrT) type(d.target()); // only addr memory is freed by hand (§24)
                    int v = value(d.target(), a);
                    int skip = label();
                    jumpIfZero(v, skip); // deleting null does nothing
                    if (needsDestroy(a.target())) {
                        destroyValue(new Mem(v, 0), a.target()); // destroys one T (§16)
                    }
                    emit(Op.FREE, v);
                    mark(skip);
                    flushTemps();
                }
            }
        }

        /** A statement that forms its own scope (the body of if/while/for). */
        void scoped(Stmt s) {
            scopes.push(new Scope());
            stmt(s);
            destroyScope(scopes.peek());
            scopes.pop();
        }

        /** Evaluates an expression for its side effects. */
        void effect(Expr e) {
            if (tb.rewritten.containsKey(e)) {
                if (e instanceof Assign a && a.target() instanceof Index ix) {
                    // xs[i] += v through []=: xs[i] = xs[i] + v, with i evaluated once (§46).
                    pin(ix.index());
                    effect(tb.rewritten.get(e));
                    pinned.remove(ix.index());
                    return;
                }
                effect(tb.rewritten.get(e));
                return;
            }
            Type t = type(e);
            if (t instanceof VoidT) {
                if (e instanceof Call c) {
                    call(c, null);
                } else {
                    value(e, t);
                }
            } else if (isAggregate(t)) {
                place(e);
            } else {
                value(e, t);
            }
        }

        // ------------------------------------------------------------ stores of whole values

        /** Evaluates {@code e} and stores it into {@code dst}, of type {@code t}. */
        void into(Place dst, Expr e, Type t) {
            if (tb.rewritten.containsKey(e)) {
                into(dst, tb.rewritten.get(e), t);
                return;
            }
            if (e instanceof SwitchExpr se && isAggregate(t)) {
                switchExpr(se, dst, t); // whichever case runs builds the value in place
                return;
            }
            if (e instanceof Conditional c && isAggregate(t)) {
                // Whichever side runs builds the value in place, converting as needed.
                int otherwise = label();
                int end = label();
                jumpIfZero(value(c.cond(), Type.BIT), otherwise);
                into(dst, c.then(), t);
                jump(end);
                mark(otherwise);
                into(dst, c.otherwise(), t);
                mark(end);
                return;
            }
            Type et = type(e);
            if (t instanceof HandleT) {
                store(dst, t, ownedHandle(e, t));
                return;
            }
            if (!isAggregate(t)) {
                store(dst, t, value(e, t));
                return;
            }
            if (et instanceof StrConstT s) {
                stringLiteral(dst, (ClassT) t, s.value());
                return;
            }
            if (e instanceof ArrayLit al && t instanceof ClassT ct && ct.cls().name.equals("array") && ct.cls().builtin) {
                arrayObjectLiteral(dst, ct, al);
                return;
            }
            if (et instanceof ArrayT fixed && t instanceof ClassT ct && ct.cls().name.equals("array") && ct.cls().builtin) {
                arrayFromFixed(dst, ct, fixed, place(e));
                return;
            }
            if (e instanceof ArrayLit al && t instanceof ArrayT at) {
                long stride = stride(at.element());
                for (int i = 0; i < al.elements().size(); i++) {
                    into(offset(dst, i * stride), al.elements().get(i), at.element());
                }
                return;
            }
            CallInfo conversion = t instanceof ClassT tct && !et.equals(t) ? ck.implicitConversion(et, tct) : null;
            if (conversion != null) {
                // An @implicit constructor makes the value: string s = fixedString;
                Mem m = dst instanceof Mem mm ? mm : temp(t);
                zero(m, sizeOf(t));
                constructorCall(conversion, addressOf(m), List.of(e));
                if (m != dst) {
                    copy(dst, m, sizeOf(t));
                }
                return;
            }
            if (dst instanceof Mem m && e instanceof Call c && isAggregate(et)) {
                call(c, m);
                return;
            }
            if (dst instanceof Mem m && e instanceof New n && et instanceof ClassT ct) {
                construct(m, ct, n);
                return;
            }
            if (isOwnedTemp(e)) {
                copy(dst, tempPlace(e), sizeOf(t)); // take over the temporary's contents
                return;
            }
            copy(dst, place(e), sizeOf(t));
            if (containsHandles(t)) {
                retainValue(dst, t); // a copy of a value holding handles adds references
            }
        }

        /** Stores into a location that already holds a value: the old value is dropped. */
        void assignTo(Place dst, Expr e, Type t) {
            if (t instanceof HandleT) {
                int v = ownedHandle(e, t);
                int old = load(dst, t);
                store(dst, t, v);
                emit(Op.HRELEASE, old);
                return;
            }
            if (isAggregate(t) && (needsDestroy(t) || e instanceof ArrayLit)) {
                // Built aside first: `a = [a[1], a[0]]` reads a while the new value is made.
                Mem fresh = temp(t);
                into(fresh, e, t);
                if (needsDestroy(t)) {
                    destroyValue(dst, t);
                }
                copy(dst, fresh, sizeOf(t));
                return;
            }
            into(dst, e, t);
        }

        boolean isLvalue(Expr e) {
            return e instanceof Name n ? "local".equals(tb.nameKinds.get(n)) || "field".equals(tb.nameKinds.get(n))
                    : e instanceof Select || e instanceof Index || (e instanceof Unary u && u.op() == UnaryOp.DEREF);
        }

        /**
         * Builds [a, b, c] into an array<T> (§21.3): into its inline items for array<T><<N>>, or,
         * for the bare shape, into a new buffer of that many elements.
         */
        void arrayObjectLiteral(Place dst, ClassT t, ArrayLit al) {
            Type element = t.typeArgs().get(0);
            constructDefault(dst, t);
            MemberInfo items = findField(t, "items");
            if (!t.valueArgs().isEmpty()) {
                assignTo(fieldPlace(items, t, dst), al, layoutOf(t).types().get(items));
                return;
            }
            long stride = stride(element);
            int n = al.elements().size();
            int buffer = reg();
            emit(Op.ALLOC, buffer, constant(Math.max(1, n) * stride));
            store(fieldPlace(items, t, dst), layoutOf(t).types().get(items), buffer);
            MemberInfo len = findField(t, "len");
            store(fieldPlace(len, t, dst), Type.INT, constant(n));
            for (int i = 0; i < n; i++) {
                into(new Mem(buffer, i * stride), al.elements().get(i), element);
            }
        }

        /** A T[N]'s elements, copied into a new array<T> (§21.3). */
        void arrayFromFixed(Place dst, ClassT t, ArrayT from, Place src) {
            Type element = t.typeArgs().get(0);
            long n = from.size().intValue().longValueExact();
            long stride = stride(element);
            constructDefault(dst, t);
            int buffer = reg();
            emit(Op.ALLOC, buffer, constant(Math.max(1, n) * stride));
            MemberInfo items = findField(t, "items");
            store(fieldPlace(items, t, dst), layoutOf(t).types().get(items), buffer);
            store(fieldPlace(findField(t, "len"), t, dst), Type.INT, constant(n));
            for (long i = 0; i < n; i++) {
                copyValue(new Mem(buffer, i * stride), offset(src, i * stride), element);
            }
        }

        /** Builds a string literal into a `string` object using its `size`, `chars` and `capacity` fields. */
        void stringLiteral(Place dst, ClassT t, String s) {
            Layout l = layoutOf(t);
            MemberInfo length = findField(t, "size");
            MemberInfo data = findField(t, "chars");
            if (length == null || data == null) {
                throw new Unsupported(pos, "string literals need a string class with `size` and `chars` fields");
            }
            zero(dst, sizeOf(t));
            Type dataType = l.types().get(data);
            Place dataPlace = fieldPlace(data, t, dst);
            if (dataType instanceof ArrayT a && a.element() instanceof IntT ch) {
                long stride = stride(ch);
                for (int i = 0; i < s.length(); i++) {
                    store(offset(dataPlace, i * stride), ch, constant(s.charAt(i)));
                }
            } else if (dataType instanceof AddrT a && a.target() instanceof IntT ch) {
                // The characters live in static data. Capacity stays 0, meaning the string
                // borrows them: the string class must copy before writing or freeing.
                int buf = reg();
                emit(Op.STATIC, buf, imm(literalData(s, width(ch), stride(ch))));
                store(dataPlace, dataType, buf);
            } else {
                throw new Unsupported(pos, "string literals need `chars` to be an array of characters or an addr to them");
            }
            store(fieldPlace(length, t, dst), l.types().get(length), constant(s.length()));
        }

        MemberInfo findField(ClassT t, String n) {
            for (MemberInfo m : t.cls().storedFields(t.header())) {
                if (((Field) m.member).name().equals(n)) {
                    return m;
                }
            }
            return null;
        }

        // ------------------------------------------------------------ places of expressions

        /** The location an lvalue or aggregate expression refers to. */
        Place place(Expr e) {
            if (tb.rewritten.containsKey(e)) {
                return place(tb.rewritten.get(e));
            }
            Pos outer = pos;
            pos = e.pos();
            try {
                return placeAt(e);
            } finally {
                pos = outer;
            }
        }

        private Place placeAt(Expr e) {
            switch (e) {
                case Name n -> {
                    String kind = tb.nameKinds.get(n);
                    if ("local".equals(kind)) {
                        return lookup(n.name()).place();
                    }
                    if ("field".equals(kind)) {
                        MemberInfo m = tb.fieldOf.get(n);
                        Field f = (Field) m.member;
                        if (f.modifiers().isStatic()) {
                            return staticPlace(f);
                        }
                        return fieldPlace(m, tb.owners.get(n), thisObject());
                    }
                    throw new Unsupported(n.pos(), n.name() + " has no location");
                }
                case Select s -> {
                    MemberInfo m = tb.selected.get(s);
                    Field f = (Field) m.member;
                    if (f.modifiers().isStatic()) {
                        return staticPlace(f);
                    }
                    ClassT owner = tb.owners.get(s);
                    Place object;
                    if (s.target() instanceof SuperRef) {
                        object = thisObject();
                    } else if (type(s.target()) instanceof HandleT) {
                        object = new Obj(value(s.target(), type(s.target())), -1, 0);
                    } else {
                        object = place(s.target());
                    }
                    return fieldPlace(m, owner, object);
                }
                case Index i when tb.calls.containsKey(i) && isAggregate(type(i)) -> {
                    return registerTemp(tempPlace(i), type(i)); // an object's [] giving a struct
                }
                case Index i when type(i.target()) instanceof ArrayT a -> {
                    Place base = place(i.target());
                    int idx = value(i.index(), Type.INT);
                    if (a.size() != null) {
                        boundsCheck(idx, a.size().intValue().longValueExact());
                    }
                    return indexed(base, idx, stride(a.element()));
                }
                case Unary u when u.op() == UnaryOp.DEREF -> {
                    return new Mem(value(u.operand(), type(u.operand())), 0);
                }
                case Call c -> {
                    return registerTemp(tempPlace(c), type(c));
                }
                case New n when type(n) instanceof ClassT ct -> {
                    return registerTemp(tempPlace(n), ct);
                }
                case Assign a when isAggregate(type(a)) -> {
                    Place target = place(a.target());
                    if (a.op() != null) {
                        compoundOnObject(a, target);
                    } else {
                        assignTo(target, a.value(), type(a.target()));
                    }
                    return target;
                }
                case This th -> {
                    return thisObject(); // a struct's own storage
                }
                case SwitchExpr se when isAggregate(type(se)) -> {
                    Mem m = temp(type(se));
                    switchExpr(se, m, type(se));
                    return registerTemp(m, type(se));
                }
                case Conditional c when isAggregate(type(c)) -> {
                    Mem m = temp(type(c));
                    into(m, c, type(c));
                    return registerTemp(m, type(c));
                }
                case ArrayLit al when type(al) instanceof ArrayT at -> {
                    // A literal used as a value of its own, as in [1, 2, 3][i]: an int[3] temporary.
                    Mem m = temp(at);
                    into(m, al, at);
                    return registerTemp(m, at);
                }
                case Expr lit when type(lit) instanceof ClassT ct && constString(lit) != null -> {
                    // A literal used as a value of its own, as in "abc".length(): a string<<3>> temporary.
                    Mem m = temp(ct);
                    stringLiteral(m, ct, constString(lit));
                    return registerTemp(m, ct);
                }
                case Binary b when isAggregate(type(b)) -> {
                    return registerTemp(tempPlace(b), type(b));
                }
                case Unary u when isAggregate(type(u)) -> {
                    return registerTemp(tempPlace(u), type(u));
                }
                case Cast c when tb.calls.containsKey(c) && isAggregate(type(c)) -> {
                    return registerTemp(tempPlace(c), type(c)); // a cast operator giving a struct (§46)
                }
                default -> {
                    Type t = type(e);
                    if (!isAggregate(t)) {
                        // A scalar value used where a location is needed: spill it.
                        Mem m = temp(t);
                        store(m, t, value(e, t));
                        return m;
                    }
                    throw new Unsupported(e.pos(), "unsupported expression");
                }
            }
        }

        /** The text of a string literal, or of literals joined with +; null for anything else. */
        String constString(Expr e) {
            if (e instanceof StringLit sl) {
                return sl.value();
            }
            if (e instanceof Binary b && b.op() == BinaryOp.ADD && type(b.left()) instanceof StrConstT
                    && type(b.right()) instanceof StrConstT) {
                String l = constString(b.left());
                String r = constString(b.right());
                return l == null || r == null ? null : l + r;
            }
            return null;
        }

        // ------------------------------------------------------------ lambdas (§30.3)

        /**
         * Creates a lambda's closure: an object of a runtime class made for this lambda, whose
         * vtable maps the interface method to the lambda's function. It holds copies of the
         * variables the lambda uses (and the enclosing object, if it uses that).
         */
        int closure(Lambda lam) {
            Checker.LambdaInfo li = tb.lambdas.get(lam);
            ClosureLayout lay = closureLayout(li);
            FnKey fk = new FnKey("lambda", tbSource.cls(), tbSource.owner(), tbSource.member(), tbSource.binders(), lam);
            int h = reg();
            emit(Op.HNEW, h, closureClassId(fk, li, lam), imm(lay.size()));
            int i = 0;
            if (li.thisType() != null) {
                int me = thisValue();
                emit(Op.HRETAIN, me);
                store(new Obj(h, -1, lay.offsets().get(i++)), new HandleT(li.thisType()), me);
            }
            for (int c = 0; c < li.captureNames().size(); c++) {
                Type t = li.captureTypes().get(c);
                copyValue(new Obj(h, -1, lay.offsets().get(i++)), lookup(li.captureNames().get(c)).place(), t);
            }
            return h;
        }

        /** Copies a value between places, adding references for the handles it holds. */
        void copyValue(Place dst, Place src, Type t) {
            if (t instanceof HandleT) {
                int v = load(src, t);
                emit(Op.HRETAIN, v);
                store(dst, t, v);
            } else if (!isAggregate(t)) {
                store(dst, t, load(src, t));
            } else {
                copy(dst, src, sizeOf(t));
                if (containsHandles(t)) {
                    retainValue(dst, t);
                }
            }
        }

        record ClosureLayout(List<Type> types, List<Long> offsets, long size) {
        }

        /** Where a closure keeps the enclosing object (first, if captured) and each captured variable. */
        ClosureLayout closureLayout(Checker.LambdaInfo li) {
            List<Type> types = new ArrayList<>();
            if (li.thisType() != null) {
                types.add(new HandleT(li.thisType()));
            }
            types.addAll(li.captureTypes());
            List<Long> offsets = new ArrayList<>();
            long off = 0;
            for (Type t : types) {
                long a = isAggregate(t) ? 64 : Math.max(1, alignOf(t));
                off = (off + a - 1) / a * a;
                offsets.add(off);
                off += sizeOf(t);
            }
            return new ClosureLayout(types, offsets, Math.max(64, (off + 63) / 64 * 64));
        }

        int closureClassId(FnKey fk, Checker.LambdaInfo li, Lambda lam) {
            Integer id = closureIds.get(fk);
            if (id != null) {
                return id;
            }
            id = prog.classes.size();
            prog.classes.add(null);
            closureIds.put(fk, id);
            Map<Integer, Integer> vtable = new HashMap<>();
            vtable.put(slotId(slotKey(li.iface(), li.method())), fnId(fk));
            vtable.put(slotId(DESTROY_SLOT), fnId(new FnKey("lambda-destroy", fk.cls(), fk.owner(), fk.member(),
                    fk.binders(), lam)));
            List<ClassT> ifaces = new ArrayList<>();
            ifaces.add(li.iface());
            ifaces.addAll(ck.allInterfacesOf(li.iface()));
            defaultMethods(vtable, ifaces);
            String name = "lambda (" + java.nio.file.Path.of(tb.file).getFileName() + ":" + lam.pos().line() + ")";
            prog.classes.set(id, new Program.RtClass(name, -1, vtable, ifaces.stream().map(CodeGen.this::rtInterfaceId).toList()));
            return id;
        }

        /** A lambda's function: `this` is its closure; then its parameters (§30.3). */
        void lambda(FnKey k) {
            Lambda lam = (Lambda) k.extra();
            tb = typed(k);
            Checker.LambdaInfo li = tb.lambdas.get(lam);
            returnType = li.returnType();
            Block body = lam.block() != null ? lam.block() : new Block(lam.pos(), List.of(returnType instanceof VoidT
                    ? new ExprStmt(lam.pos(), lam.expr()) : new Return(lam.pos(), lam.expr())));
            addrTaken = AddressTaken.in(body);
            int first = params(isAggregate(returnType), true);
            ClosureLayout lay = closureLayout(li);
            Scope captured = new Scope(); // owned by the closure, so nothing to destroy here
            int i = 0;
            if (li.thisType() != null) {
                self = li.thisType();
                lambdaThis = load(new Obj(thisReg, -1, lay.offsets().get(i++)), new HandleT(li.thisType()));
            } else {
                self = null;
            }
            for (int c = 0; c < li.captureNames().size(); c++) {
                captured.vars.put(li.captureNames().get(c),
                        new Local(li.captureTypes().get(c), new Obj(thisReg, -1, lay.offsets().get(i++))));
            }
            scopes.push(captured);
            bindParams(lam.params(), li.params(), first);
            block(body, false);
            endOfFunction();
        }

        /** Releases what a closure holds when its last reference goes. */
        void lambdaDestroy(FnKey k) {
            Lambda lam = (Lambda) k.extra();
            tb = typed(k);
            Checker.LambdaInfo li = tb.lambdas.get(lam);
            ClosureLayout lay = closureLayout(li);
            params(false, true);
            scopes.push(new Scope());
            for (int i = lay.types().size() - 1; i >= 0; i--) {
                Type t = lay.types().get(i);
                if (needsDestroy(t)) {
                    destroyValue(new Obj(thisReg, -1, lay.offsets().get(i)), t);
                }
            }
            emit(Op.RETV);
        }

        /** Locals for the `instanceof` bindings this statement's own expressions introduce (§35.1). */
        final Map<InstanceOf, Local> bindingLocals = new IdentityHashMap<>();

        /**
         * Declares, in the current scope, a null handle for each `x instanceof T name` in the
         * statement's own expressions (not those of nested statements). It is set when the test
         * passes and released with the scope, so in a loop condition it is reassigned each time.
         */
        void declareBindings(Stmt s) {
            List<Expr> roots = switch (s) {
                case VarDecl v -> v.init() == null ? List.of() : List.of(v.init());
                case ExprStmt e -> List.of(e.expr());
                case If i -> List.of(i.cond());
                case While w -> List.of(w.cond());
                case For f -> {
                    List<Expr> out = new ArrayList<>();
                    if (f.cond() != null) {
                        out.add(f.cond());
                    }
                    if (f.update() != null) {
                        out.add(f.update());
                    }
                    yield out;
                }
                case Return r -> r.value() == null ? List.of() : List.of(r.value());
                case DeleteStmt d -> List.of(d.target());
                default -> List.of();
            };
            Deque<Expr> todo = new ArrayDeque<>(roots);
            while (!todo.isEmpty()) {
                Expr e = todo.pop();
                if (e instanceof InstanceOf io && io.binding() != null && tb.owners.get(io) != null) {
                    Type t = new HandleT(tb.owners.get(io));
                    Reg r = new Reg(reg());
                    store(r, t, constant(0));
                    Local l = new Local(t, r);
                    scopes.peek().vars.put(io.binding(), l);
                    scopes.peek().toDestroy.add(l);
                    bindingLocals.put(io, l);
                }
                todo.addAll(Ast.children(e));
            }
        }

        /**
         * Checks an index into a fixed array: out of range, it throws an IndexOutOfBoundsException
         * through the library's FixedArray.outOfBounds, or (without the library) stops the program.
         */
        void boundsCheck(int idx, long length) {
            ClassInfo fa = ck.classNamed("FixedArray");
            MemberInfo thrower = fa == null || !fa.builtin ? null
                    : fa.all.stream().filter(m -> m.member instanceof Method md && md.name().equals("outOfBounds")).findFirst().orElse(null);
            if (thrower == null) {
                emit(Op.BOUNDS, idx, imm(length));
                return;
            }
            int ok = reg();
            emit(Op.LTU, ok, idx, constant(length)); // a negative index is a huge unsigned one
            int fine = label();
            jumpIfNotZero(ok, fine);
            int fn = fnId(new FnKey("method", fa, null, thrower, Map.of()));
            int base = regs(2);
            emit(Op.MOV, base, idx);
            emit(Op.MOV, base + 1, constant(length));
            emit(Op.CALL, fn, base, 2, -1);
            mark(fine);
        }

        /** A fresh temporary holding the (owned) result of a call, `new` or operator. */
        Mem tempPlace(Expr e) {
            Type t = type(e);
            Mem m = temp(t);
            switch (e) {
                case Call c -> call(c, m);
                case New n -> construct(m, (ClassT) t, n);
                default -> operatorInto(e, m);
            }
            return m;
        }

        /** Destroys a temporary object at the end of the current statement. */
        Mem registerTemp(Mem m, Type t) {
            if (needsDestroy(t)) {
                pendingTemps.add(m);
                pendingTempTypes.add(t);
            }
            return m;
        }

        // ------------------------------------------------------------ scalar values

        /**
         * Evaluates a scalar expression; {@code want} is the type the context
         * expects. A handle from `new` or a call is owned by this statement and
         * released at its end; use {@link #ownedHandle} to take it over instead.
         */
        int value(Expr e, Type want) {
            int r = valueRaw(e, want);
            if (type(e) instanceof HandleT && isOwnedTemp(e)) {
                pendingHandles.add(r);
            }
            return r;
        }

        int valueRaw(Expr e, Type want) {
            Type narrowed = tb.implicitCasts.get(e);
            if (narrowed != null) {
                int v = implicitCast(e, narrowed);
                return want == null ? v : widen(v, narrowed, want);
            }
            return valueUncast(e, want);
        }

        /** int<<8>> b = i: a value narrowed where it is stored or passed, as a cast would (§41.6). */
        int implicitCast(Expr e, Type to) {
            Type from = type(e);
            if (from instanceof FloatConstT && to instanceof IntT) {
                double d = ((FloatV) ck.constantIn(tb, e)).value(); // int a = 2.5: toward zero, as (int)2.5
                return normalize(constant((long) d), to);
            }
            return convert(valueUncast(e, from), from, to);
        }

        /** The value of e in its own type, widened to {@code want}, before any narrowing the checker recorded for it. */
        int valueUncast(Expr e, Type want) {
            Integer early = pinned.get(e);
            if (early != null) {
                int d = reg();
                emit(Op.MOV, d, early);
                return want == null ? d : widen(d, type(e), want);
            }
            if (tb.rewritten.containsKey(e)) {
                return valueRaw(tb.rewritten.get(e), want);
            }
            Pos outer = pos;
            pos = e.pos();
            try {
                int v = valueAt(e, want);
                return want == null ? v : widen(v, type(e), want);
            } finally {
                pos = outer; // so the rest of the enclosing expression keeps its own line
            }
        }

        private int valueAt(Expr e, Type want) {
            Type t = type(e);
            if (t instanceof FloatConstT fc && fc.value() != null && want instanceof FixedT ft) {
                return fixedConstant(fc.value(), ft); // exact, from the literal's digits
            }
            if (t instanceof IntConstT || t instanceof FloatConstT
                    || (e instanceof Name n && "value".equals(tb.nameKinds.get(n)))) {
                return constantValue(ck.constantIn(tb, e), want == null || want instanceof IntConstT ? t : want);
            }
            switch (e) {
                case BoolLit b -> {
                    return constant(b.value() ? 1 : 0);
                }
                case NullLit n -> {
                    return constant(0);
                }
                case Name n -> {
                    String kind = tb.nameKinds.get(n);
                    if ("const".equals(kind)) {
                        MemberInfo m = tb.fieldOf.get(n);
                        return constantValue(ck.constFieldValue(ck.declaringClassOf(m), (Field) m.member), t);
                    }
                    return load(place(n), t);
                }
                case Select s -> {
                    MemberInfo m = tb.selected.get(s);
                    if (((Field) m.member).modifiers().isConst()) {
                        return constantValue(ck.constFieldValue(ck.declaringClassOf(m), (Field) m.member), t);
                    }
                    return load(place(s), t);
                }
                case Call c -> {
                    return call(c, null);
                }
                case Index i -> {
                    if (tb.calls.containsKey(i)) {
                        return operatorValue(i); // xs[i] on an object: its [] (§46)
                    }
                    if (type(i.target()) instanceof ArrayT) {
                        return load(place(i), t);
                    }
                    Type it = type(i.target());
                    int v = value(i.target(), it);
                    int idx = value(i.index(), Type.INT);
                    emit(Op.BOUNDS, idx, it instanceof IntT ix ? width(ix) : 64);
                    int shifted = reg();
                    emit(Op.SHRU, shifted, v, idx);
                    int bit = reg();
                    emit(Op.AND, bit, shifted, constant(1));
                    return bit;
                }
                case Slice s -> {
                    long from = ck.constantIn(tb, s.from()).intValue().longValueExact();
                    long to = ck.constantIn(tb, s.to()).intValue().longValueExact();
                    int v = value(s.target(), type(s.target()));
                    int shifted = reg();
                    emit(Op.SHRU, shifted, v, constant(from));
                    int out = reg();
                    emit(Op.AND, out, shifted, constant(mask(to - from)));
                    return out;
                }
                case Unary u -> {
                    return unary(u, t);
                }
                case Binary b -> {
                    return binary(b, t);
                }
                case Assign a -> {
                    return assign(a);
                }
                case IncDec i -> {
                    return incDec(i);
                }
                case Cast c -> {
                    return cast(c);
                }
                case Lambda lam -> {
                    return closure(lam);
                }
                case This th when !isAggregate(type(th)) -> {
                    return thisValue(); // a class's handle, borrowed like a variable's
                }
                case SwitchExpr se -> {
                    return switchExpr(se, null, type(se));
                }
                case Conditional c -> {
                    Type ct = type(c);
                    Type want2 = ct instanceof IntConstT || ct instanceof FloatConstT ? want : ct;
                    int r = reg();
                    int otherwise = label();
                    int end = label();
                    jumpIfZero(value(c.cond(), Type.BIT), otherwise);
                    emit(Op.MOV, r, value(c.then(), want2));
                    jump(end);
                    mark(otherwise);
                    emit(Op.MOV, r, value(c.otherwise(), want2));
                    mark(end);
                    return r;
                }
                case InstanceOf io -> {
                    ClassT tc = tb.owners.get(io);
                    int h = value(io.operand(), type(io.operand()));
                    int d = reg();
                    emit(Op.INSTANCEOF, d, h, tc.cls().isInterface ? rtInterfaceId(tc) : rtClassId(tc));
                    Local l = bindingLocals.get(io);
                    if (l != null) {
                        // Passed: the binding takes its own reference, dropping the one it had.
                        int skip = label();
                        jumpIfZero(d, skip);
                        emit(Op.HRETAIN, h);
                        int old = load(l.place(), l.type());
                        store(l.place(), l.type(), h);
                        emit(Op.HRELEASE, old);
                        mark(skip);
                    }
                    return d;
                }
                case New n -> {
                    return newObject(n, t);
                }
                case NewArray n -> {
                    Type el = ((AddrT) t).target();
                    int count = value(n.count(), Type.INT);
                    int bits = reg();
                    emit(Op.MULI, bits, count, imm(stride(el)));
                    int a = reg();
                    emit(Op.ALLOC, a, bits);
                    return a;
                }
                default -> throw new Unsupported(e.pos(), "unsupported expression for " + t);
            }
        }

        /** A constant in a fixed-point type: round(v * 2^M), wrapped to the type's width. */
        int fixedConstant(java.math.BigDecimal v, FixedT t) {
            long raw = Checker.fixedRaw(v, frac(t)).longValue();
            int w = width(t);
            if (w < 64) {
                raw = t.signed() ? (raw << (64 - w)) >> (64 - w) : raw & ((1L << w) - 1);
            }
            return constant(raw);
        }

        long mask(long bits) {
            return bits >= 64 ? -1L : (1L << bits) - 1;
        }

        /** Loads a compile-time value represented as {@code want}. */
        int constantValue(CVal v, Type want) {
            if (v instanceof IntV i) {
                BigInteger x = i.value();
                if (want instanceof FloatT) {
                    return constant(Float.floatToRawIntBits(x.floatValue()) & 0xffffffffL);
                }
                if (want instanceof DoubleT || want instanceof FloatConstT) {
                    return constant(Double.doubleToRawLongBits(x.doubleValue()));
                }
                if (want instanceof FixedT ft) {
                    return fixedConstant(new java.math.BigDecimal(x), ft);
                }
                long bits = x.longValue();
                if (want instanceof IntT it) {
                    int w = width(it);
                    if (w < 64) {
                        bits = it.signed() ? (bits << (64 - w)) >> (64 - w) : bits & ((1L << w) - 1);
                    }
                }
                return constant(bits);
            }
            if (v instanceof FloatV f) {
                if (want instanceof FixedT ft) {
                    return fixedConstant(new java.math.BigDecimal(f.value()), ft);
                }
                if (want instanceof FloatT) {
                    return constant(Float.floatToRawIntBits(f.value().floatValue()) & 0xffffffffL);
                }
                return constant(Double.doubleToRawLongBits(f.value()));
            }
            if (v instanceof BitV b) {
                return constant(b.value() ? 1 : 0);
            }
            throw new Unsupported(pos, "cannot use the compile-time value " + v + " at run time");
        }

        int unary(Unary u, Type t) {
            if (tb.calls.containsKey(u)) {
                return operatorValue(u);
            }
            switch (u.op()) {
                case NEG -> {
                    int v = value(u.operand(), t);
                    int d = reg();
                    if (t instanceof FloatT || t instanceof DoubleT) {
                        emit(Op.FNEG, d, v, t instanceof DoubleT ? 1 : 0);
                        return d;
                    }
                    emit(Op.NEG, d, v);
                    return normalize(d, t);
                }
                case BIT_NOT -> {
                    int v = value(u.operand(), t);
                    int d = reg();
                    emit(Op.NOT, d, v);
                    return normalize(d, t);
                }
                case NOT -> {
                    int v = value(u.operand(), Type.BIT);
                    int d = reg();
                    emit(Op.LNOT, d, v);
                    return d;
                }
                case DEREF -> {
                    return load(place(u), t);
                }
                case ADDRESS_OF -> {
                    return addressOf(place(u.operand()));
                }
            }
            throw new IllegalStateException();
        }

        int normalize(int r, Type t) {
            if ((t instanceof IntT || t instanceof FixedT) && width(t) < 64) {
                int d = reg();
                emit(Op.TRUNC, d, r, width(t), signed(t));
                return d;
            }
            return r;
        }

        /** The type both operands of a comparison are converted to. */
        Type operandType(Type l, Type r) {
            Type common = ck.operandType(l, r); // i < d compares as doubles, c < -1 as ints (§41.6)
            if (common != null && isNumber(common)) {
                return common;
            }
            boolean lc = l instanceof IntConstT || l instanceof FloatConstT;
            boolean rc = r instanceof IntConstT || r instanceof FloatConstT;
            if (lc && !rc) {
                return r;
            }
            if (rc && !lc) {
                return l;
            }
            if (lc) {
                return l instanceof FloatConstT || r instanceof FloatConstT ? Type.DOUBLE : new IntT(CVal.of(64), true);
            }
            return l;
        }

        int binary(Binary b, Type t) {
            if (tb.calls.containsKey(b)) {
                return operatorValue(b);
            }
            BinaryOp op = b.op();
            if (op == BinaryOp.AND || op == BinaryOp.OR) {
                int result = reg();
                int end = label();
                emit(Op.MOV, result, value(b.left(), Type.BIT));
                if (op == BinaryOp.AND) {
                    jumpIfZero(result, end);
                } else {
                    jumpIfNotZero(result, end);
                }
                emit(Op.MOV, result, value(b.right(), Type.BIT));
                mark(end);
                return result;
            }
            Type lt = type(b.left());
            Type rt = type(b.right());
            switch (op) {
                case EQ, NE, LT, GT, LE, GE -> {
                    Type ot = operandType(lt, rt);
                    int l = value(b.left(), ot);
                    int r = value(b.right(), ot);
                    return compare(op, ot, l, r);
                }
                case SHIFT -> {
                    int l = value(b.left(), t);
                    int r = value(b.right(), Type.INT);
                    int d = reg();
                    emit(Op.SHIFT, d, l, r, width(t), signed(t));
                    return d;
                }
                default -> {
                    if (t instanceof AddrT) {
                        boolean addrLeft = lt instanceof AddrT;
                        int a = value(addrLeft ? b.left() : b.right(), t);
                        Type it = addrLeft ? rt : lt;
                        int i = value(addrLeft ? b.right() : b.left(), it instanceof IntT ? it : new IntT(CVal.of(64), true));
                        int d = reg();
                        emit(op == BinaryOp.SUB ? Op.SUB : Op.ADD, d, a, i);
                        return d;
                    }
                    if (lt instanceof AddrT && rt instanceof AddrT) {
                        int d = reg();
                        emit(Op.SUB, d, value(b.left(), lt), value(b.right(), rt));
                        return d;
                    }
                    int l = value(b.left(), t);
                    int r = value(b.right(), t);
                    return arithmetic(op, t, l, r);
                }
            }
        }

        int arithmetic(BinaryOp op, Type t, int l, int r) {
            int d = reg();
            if (t instanceof FloatT || t instanceof DoubleT) {
                int dbl = t instanceof DoubleT ? 1 : 0;
                Op fop = switch (op) {
                    case ADD -> Op.FADD;
                    case SUB -> Op.FSUB;
                    case MUL -> Op.FMUL;
                    case DIV -> Op.FDIV;
                    case POW -> Op.FPOW;
                    default -> throw new Unsupported(pos, "operator " + op.symbol + " on " + t);
                };
                emit(fop, d, l, r, dbl);
                return d;
            }
            if (t instanceof BitT) {
                emit(switch (op) {
                    case BIT_AND -> Op.AND;
                    case BIT_OR -> Op.OR;
                    default -> Op.XOR;
                }, d, l, r);
                return d;
            }
            boolean s = isSigned(t);
            if (t instanceof FixedT ft) {
                switch (op) {
                    case MUL -> emit(Op.XMUL, d, l, r, frac(ft), signed(ft));
                    case DIV -> emit(Op.XDIV, d, l, r, frac(ft), signed(ft));
                    default -> {
                        return arithmeticInt(op, t, l, r, d, s);
                    }
                }
                return normalize(d, t);
            }
            return arithmeticInt(op, t, l, r, d, s);
        }

        int arithmeticInt(BinaryOp op, Type t, int l, int r, int d, boolean s) {
            switch (op) {
                case ADD -> emit(Op.ADD, d, l, r);
                case SUB -> emit(Op.SUB, d, l, r);
                case MUL -> emit(Op.MUL, d, l, r);
                case POW -> emit(Op.POW, d, l, r, s ? 1 : 0);
                case DIV -> emit(s ? Op.DIVS : Op.DIVU, d, l, r);
                case MOD -> emit(s ? Op.REMS : Op.REMU, d, l, r);
                case BIT_AND -> emit(Op.AND, d, l, r);
                case BIT_OR -> emit(Op.OR, d, l, r);
                case BIT_XOR -> emit(Op.XOR, d, l, r);
                case SHIFT -> {
                    emit(Op.SHIFT, d, l, r, width(t), signed(t));
                    return d;
                }
                default -> throw new Unsupported(pos, "operator " + op.symbol + " on " + t);
            }
            return normalize(d, t);
        }

        int compare(BinaryOp op, Type t, int l, int r) {
            int d = reg();
            if (t instanceof FloatT || t instanceof DoubleT) {
                int dbl = t instanceof DoubleT ? 1 : 0;
                switch (op) {
                    case EQ -> emit(Op.FEQ, d, l, r, dbl);
                    case NE -> emit(Op.FNE, d, l, r, dbl);
                    case LT -> emit(Op.FLT, d, l, r, dbl);
                    case LE -> emit(Op.FLE, d, l, r, dbl);
                    case GT -> emit(Op.FLT, d, r, l, dbl);
                    case GE -> emit(Op.FLE, d, r, l, dbl);
                    default -> throw new IllegalStateException();
                }
                return d;
            }
            boolean s = isSigned(t);
            switch (op) {
                case EQ -> emit(Op.EQ, d, l, r);
                case NE -> emit(Op.NE, d, l, r);
                case LT -> emit(s ? Op.LTS : Op.LTU, d, l, r);
                case LE -> emit(s ? Op.LES : Op.LEU, d, l, r);
                case GT -> emit(s ? Op.LTS : Op.LTU, d, r, l);
                case GE -> emit(s ? Op.LES : Op.LEU, d, r, l);
                default -> throw new IllegalStateException();
            }
            return d;
        }

        int cast(Cast c) {
            if (tb.calls.containsKey(c)) {
                return operatorValue(c); // the operand's @overload(Operator.CAST) method (§46)
            }
            Type to = type(c);
            Type from = type(c.operand());
            if (from instanceof IntConstT || from instanceof FloatConstT) {
                if (to instanceof IntT && from instanceof FloatConstT) {
                    CVal v = ck.constantIn(tb, c.operand());
                    double d = ((FloatV) v).value();
                    return normalize(constant((long) d), to);
                }
                return value(c.operand(), to);
            }
            int v = value(c.operand(), from);
            if (from.equals(to)) {
                return v;
            }
            return convert(v, from, to);
        }

        /**
         * A value converted where it meets another numeric type without a cast (§41.6): an
         * int<<8>> stored in an int, an int added to a double. A wider integer needs no
         * instruction, since registers hold integers sign- or zero-extended.
         */
        int widen(int v, Type from, Type to) {
            if (from.equals(to) || !isNumber(from) || !isNumber(to) || (from instanceof IntT && to instanceof IntT)) {
                return v;
            }
            return convert(v, from, to);
        }

        boolean isNumber(Type t) {
            return t instanceof IntT || t instanceof FixedT || t instanceof FloatT || t instanceof DoubleT;
        }

        /** Converts between numeric types, as a cast does (§41.1, §37.1). */
        int convert(int v, Type from, Type to) {
            boolean fromFloat = from instanceof FloatT || from instanceof DoubleT;
            boolean toFloat = to instanceof FloatT || to instanceof DoubleT;
            if (from instanceof FixedT || to instanceof FixedT) {
                return fixedCast(v, from, to, fromFloat, toFloat);
            }
            int d = reg();
            if (from instanceof IntT fi && to instanceof IntT) {
                emit(Op.TRUNC, d, v, width(to), signed(to));
                return d;
            }
            if (from instanceof IntT fi && toFloat) {
                emit(Op.I2F, d, v, fi.signed() ? 1 : 0, to instanceof DoubleT ? 1 : 0);
                return d;
            }
            if (fromFloat && to instanceof IntT) {
                emit(Op.F2I, d, v, from instanceof DoubleT ? 1 : 0);
                return normalize(d, to);
            }
            if (fromFloat && toFloat) {
                emit(from instanceof FloatT ? Op.F2D : Op.D2F, d, v);
                return d;
            }
            if (from instanceof HandleT && to instanceof HandleT th && th.target() instanceof ClassT tc) {
                emit(Op.CHECKCAST, d, v, tc.cls().isInterface ? rtInterfaceId(tc) : rtClassId(tc));
                return d;
            }
            return v; // addr to addr, null to addr/handle
        }

        /** Casts involving fixed-point types (§37.1). */
        int fixedCast(int v, Type from, Type to, boolean fromFloat, boolean toFloat) {
            int d = reg();
            if (from instanceof FixedT ff && to instanceof FixedT tf) {
                // Change of scale rounds to nearest; then wrap to the new width.
                emit(Op.XSCALE, d, v, frac(tf) - frac(ff), signed(ff), 1);
                return normalize(d, to);
            }
            if (from instanceof IntT && to instanceof FixedT tf) {
                emit(Op.XSCALE, d, v, frac(tf), signed(from), 1);
                return normalize(d, to);
            }
            if (from instanceof FixedT ff && to instanceof IntT) {
                // Like float to int: truncate toward zero, then wrap.
                emit(Op.XSCALE, d, v, -frac(ff), signed(ff), 0);
                return normalize(d, to);
            }
            if (from instanceof FixedT ff && toFloat) {
                emit(Op.I2F, d, v, signed(ff), 1);
                int scaled = reg();
                emit(Op.FMUL, scaled, d, doubleConstant(Math.scalb(1.0, -frac(ff))), 1);
                if (to instanceof FloatT) {
                    int f = reg();
                    emit(Op.D2F, f, scaled);
                    return f;
                }
                return scaled;
            }
            if (fromFloat && to instanceof FixedT tf) {
                int x = v;
                if (from instanceof FloatT) {
                    x = reg();
                    emit(Op.F2D, x, v);
                }
                int scaled = reg();
                emit(Op.FMUL, scaled, x, doubleConstant(Math.scalb(1.0, frac(tf))), 1);
                emit(Op.F2IR, d, scaled, 1);
                return normalize(d, to);
            }
            throw new Unsupported(pos, "cannot cast " + from + " to " + to);
        }

        // ------------------------------------------------------------ assignment

        int assign(Assign a) {
            if (a.op() == null && a.target() instanceof Index && tb.calls.containsKey(a)) {
                operatorValue(a); // xs[i] = v: the object's []= (§46)
                return -1;
            }
            Type t = type(a.target());
            if (tb.calls.containsKey(a)) {
                if (t instanceof HandleT) {
                    // On a class object, `a += b` and `a <<= n` call its own operator, which changes a itself (§46).
                    CallInfo ci = tb.calls.get(a);
                    int receiver = load(place(a.target()), t);
                    invoke(ci, receiver, List.of(a.value()), null);
                    return receiver;
                }
                // Overloaded compound assignment on a struct or unique.
                compoundOnObject(a, place(a.target()));
                return -1;
            }
            Expr target = a.target();
            // Assigning one bit or a slice of an integer: read, modify, write.
            if (target instanceof Index ix && !(type(ix.target()) instanceof ArrayT)) {
                Type it = type(ix.target());
                Place p = place(ix.target());
                int idx = value(ix.index(), Type.INT);
                emit(Op.BOUNDS, idx, width(it));
                int newBit = a.op() == null ? value(a.value(), Type.BIT)
                        : arithmetic(a.op(), Type.BIT, extractBit(load(p, it), idx), value(a.value(), Type.BIT));
                int one = constant(1);
                int mask = reg();
                emit(Op.SHL, mask, one, idx);
                writeBits(p, it, mask, newBit, idx);
                return newBit;
            }
            if (target instanceof Slice sl) {
                Type it = type(sl.target());
                Place p = place(sl.target());
                long from = ck.constantIn(tb, sl.from()).intValue().longValueExact();
                long to = ck.constantIn(tb, sl.to()).intValue().longValueExact();
                int v = a.op() == null ? value(a.value(), t) : compound(a, t, sliceOf(load(p, it), from, to));
                int mask = constant(mask(to - from) << from);
                writeBits(p, it, mask, v, constant(from));
                return v;
            }
            Place p = place(target);
            if (a.op() == null) {
                if (t instanceof HandleT) {
                    assignTo(p, a.value(), t);
                    return load(p, t);
                }
                int v = value(a.value(), t);
                store(p, t, v);
                return v;
            }
            int old = load(p, t);
            int v;
            if (a.op() == BinaryOp.SHIFT) {
                v = reg();
                emit(Op.SHIFT, v, old, value(a.value(), Type.INT), width(t), signed(t));
            } else if (t instanceof AddrT) {
                v = reg();
                emit(a.op() == BinaryOp.SUB ? Op.SUB : Op.ADD, v, old, value(a.value(), type(a.value()) instanceof IntT it ? it : Type.INT));
            } else {
                v = compound(a, t, old);
            }
            store(p, t, v);
            return v;
        }

        /**
         * x op= v on a number: in x's type, or, when the checker found the operator gives a
         * wider type (i += 2.5), in that one, narrowed back as a cast would (§41.6).
         */
        int compound(Assign a, Type t, int old) {
            Type wide = tb.narrowed.get(a);
            if (wide == null) {
                return arithmetic(a.op(), t, old, value(a.value(), t));
            }
            int v = arithmetic(a.op(), wide, widen(old, t, wide), value(a.value(), wide));
            return convert(v, wide, t);
        }

        /**
         * Values computed ahead of the expressions they stand for: an expression that code
         * rewritten from one use (xs[i] += 1 into xs[i] = xs[i] + 1) reaches twice.
         */
        final Map<Expr, Integer> pinned = new IdentityHashMap<>();

        /** Evaluates a scalar now, so that rewritten code using it twice runs it once. */
        void pin(Expr e) {
            Type t = type(e);
            if (!isAggregate(t) && !(t instanceof HandleT) && !(t instanceof IntConstT) && !(t instanceof FloatConstT)) {
                pinned.put(e, valueUncast(e, t)); // in its own type: a narrowing recorded for it applies at each use
            }
        }

        /** `++x` gives the new value, `x++` the old one; either way x is read and written once (§41.5). */
        int incDec(IncDec i) {
            Assign step = tb.elementSteps.get(i);
            if (step != null) {
                return elementStep(i, step);
            }
            Type t = type(i.target());
            Place p = place(i.target());
            int old = load(p, t);
            int v = arithmetic(i.op(), t, old, constantValue(new IntV(BigInteger.ONE), t));
            store(p, t, v);
            return i.prefix() ? v : old;
        }

        /** xs[i]++ through []=: reads xs[i] once with [], then stores the result with []=. */
        int elementStep(IncDec i, Assign step) {
            Index ix = (Index) step.target();
            Expr next = step.value();   // xs[i] + 1, or (char)(xs[i] + 1)
            Expr read = ((Binary) (next instanceof Cast c ? c.operand() : next)).left();
            pin(ix.index());
            int old = value(read, type(read));
            pinned.put(read, old);
            int now = value(next, type(next));
            pinned.put(next, now);
            assign(step);
            pinned.remove(ix.index());
            pinned.remove(read);
            pinned.remove(next);
            return i.prefix() ? now : old;
        }

        int extractBit(int v, int idx) {
            int s = reg();
            emit(Op.SHRU, s, v, idx);
            int d = reg();
            emit(Op.AND, d, s, constant(1));
            return d;
        }

        int sliceOf(int v, long from, long to) {
            int s = reg();
            emit(Op.SHRU, s, v, constant(from));
            int d = reg();
            emit(Op.AND, d, s, constant(mask(to - from)));
            return d;
        }

        /** p = (p & ~mask) | ((bits << shift) & mask), re-normalized. */
        void writeBits(Place p, Type it, int mask, int bits, int shift) {
            int old = load(p, it);
            int inv = reg();
            emit(Op.NOT, inv, mask);
            int cleared = reg();
            emit(Op.AND, cleared, old, inv);
            int moved = reg();
            emit(Op.SHL, moved, bits, shift);
            int masked = reg();
            emit(Op.AND, masked, moved, mask);
            int combined = reg();
            emit(Op.OR, combined, cleared, masked);
            store(p, it, normalize(combined, it));
        }

        /** `c += x` or `c <<= n` on a struct or unique: its own operator, which changes c where it is (§46). */
        void compoundOnObject(Assign a, Place target) {
            CallInfo ci = tb.calls.get(a);
            withAddress(target, type(a.target()), addr -> invoke(ci, addr, List.of(a.value()), null));
        }

        // ------------------------------------------------------------ operators on objects

        int operatorValue(Expr site) {
            CallInfo ci = tb.calls.get(site);
            Expr left;
            List<Expr> args;
            if (site instanceof Binary b) {
                left = ci.swap() ? b.right() : b.left();
                args = List.of(ci.swap() ? b.left() : b.right());
            } else if (site instanceof Index ix) {
                left = ix.target();                          // xs[i]: xs.get(i)
                args = List.of(ix.index());
            } else if (site instanceof Assign a && a.target() instanceof Index ix) {
                left = ix.target();                          // xs[i] = v: xs.set(i, v)
                args = List.of(ix.index(), a.value());
            } else if (site instanceof Cast c) {
                left = c.operand();                          // (R)x: x's cast to R
                args = List.of();
            } else {
                left = ((Unary) site).operand();
                args = List.of();
            }
            Type lt = type(left);
            int[] out = new int[1];
            if (lt instanceof HandleT) {
                out[0] = invoke(ci, value(left, lt), args, null);    // a class object: `this` is the handle
            } else {
                Place lp = place(left);
                withAddress(lp, lt, addr -> out[0] = invoke(ci, addr, args, null));
            }
            if (ci.negate()) {
                int d = reg();
                emit(Op.LNOT, d, out[0]);
                return d;
            }
            return out[0];
        }

        void operatorInto(Expr site, Mem dst) {
            CallInfo ci = tb.calls.get(site);
            Expr left;
            List<Expr> args;
            if (site instanceof Binary b) {
                left = ci.swap() ? b.right() : b.left();
                args = List.of(ci.swap() ? b.left() : b.right());
            } else if (site instanceof Index ix) {
                left = ix.target();                          // xs[i]: xs.get(i)
                args = List.of(ix.index());
            } else if (site instanceof Assign a && a.target() instanceof Index ix) {
                left = ix.target();                          // xs[i] = v: xs.set(i, v)
                args = List.of(ix.index(), a.value());
            } else if (site instanceof Cast c) {
                left = c.operand();                          // (R)x: x's cast to R
                args = List.of();
            } else {
                left = ((Unary) site).operand();
                args = List.of();
            }
            if (type(left) instanceof HandleT) {
                invoke(ci, value(left, type(left)), args, dst); // a class object: `this` is the handle
                return;
            }
            Place lp = place(left);
            withAddress(lp, type(left), addr -> invoke(ci, addr, args, dst));
        }

        // ------------------------------------------------------------ calls

        /** Calls a method; aggregate results go to {@code dst} (a temporary if null). Returns the result register or -1. */
        int call(Call c, Mem dst) {
            CallInfo ci = tb.calls.get(c);
            if (ci == null) {
                throw new Unsupported(c.pos(), "unresolved call");
            }
            if (ck.isRawAssembly(ci)) {
                return -1; // assembly is for a native compiler; the VM has nothing to run (§42.3)
            }
            Type rt = type(c);
            if (isAggregate(rt) && dst == null) {
                dst = temp(rt);
            }
            switch (ci.kind()) {
                case SUPER_CONSTRUCTOR -> {
                    constructorCall(ci, thisReg, c.args());
                    return -1;
                }
                case STATIC -> {
                    return invoke(ci, -1, c.args(), dst);
                }
                default -> {
                    // Instance method: find the receiver.
                    if (c.callee() instanceof Name || (c.callee() instanceof Select s && s.target() instanceof SuperRef)) {
                        return invoke(ci, thisValue(), c.args(), dst);
                    }
                    Select s = (Select) c.callee();
                    Type tt = type(s.target());
                    if (tt instanceof HandleT) {
                        return invoke(ci, value(s.target(), tt), c.args(), dst);
                    }
                    int[] out = new int[1];
                    Mem d = dst;
                    withAddress(place(s.target()), tt, addr -> out[0] = invoke(ci, addr, c.args(), d));
                    return out[0];
                }
            }
        }

        /**
         * Emits a call. {@code receiver} is the `this` value (an address for
         * fixed classes, a handle for dynamic ones) or -1 for static methods.
         */
        int invoke(CallInfo ci, int receiver, List<Expr> args, Mem dst) {
            Method md = (Method) ci.member().member;
            List<Type> ptypes = ck.paramTypes(ci.owner(), ci.member(), ci.binders());
            Type rt = ck.returnTypeOf(ci.owner(), ci.member(), ci.binders());
            String nat = Checker.nativeName(md);

            List<Integer> argRegs = new ArrayList<>();
            boolean aggregateReturn = isAggregate(rt);
            if (receiver >= 0) {
                argRegs.add(receiver);   // first: VCALL dispatches on it
            }
            if (aggregateReturn && nat == null) {
                argRegs.add(addressOf(dst));
            }
            int inFlight = argsInFlight.size();
            for (int i = 0; i < args.size(); i++) {
                // Natives only look at their arguments, so they are lent, not handed over: a handle
                // as it is, a struct (such as a string) as a copy the caller destroys afterwards.
                Type pt = ptypes.get(i);
                if (nat != null && pt instanceof HandleT) {
                    argRegs.add(value(args.get(i), pt));
                } else if (nat != null && isAggregate(pt)) {
                    Mem copy = temp(pt);
                    into(copy, args.get(i), pt);
                    argRegs.add(addressOf(registerTemp(copy, pt)));
                } else {
                    argRegs.add(argument(args.get(i), pt));
                }
            }
            if (nat != null) {
                // Natives can't see types, so pass the fraction bits of fixed-point arguments too.
                for (Type pt : ptypes) {
                    if (pt instanceof FixedT ft) {
                        argRegs.add(constant(frac(ft)));
                    }
                }
            }
            handOver(inFlight);
            int base = regs(Math.max(1, argRegs.size()));
            for (int i = 0; i < argRegs.size(); i++) {
                emit(Op.MOV, base + i, argRegs.get(i));
            }
            int result = rt instanceof VoidT || aggregateReturn ? -1 : reg();
            if (nat != null) {
                emit(Op.NATIVE, nativeId(nat), base, argRegs.size(), result);
                return result;
            }
            boolean virtual = ci.kind() == CallKind.METHOD && ci.owner() != null && ci.owner().cls().dynamic
                    && !hasBinders(md);
            if (virtual) {
                if (!ci.owner().cls().isInterface) {
                    rtClassId(ci.owner());
                }
                if (md.body() != null) {
                    fnId(new FnKey("method", ci.owner().cls(), ci.owner(), ci.member(), ci.binders())); // report its errors
                }
                emit(Op.VCALL, slotId(slotKey(ci.owner(), ci.member())), base, argRegs.size(), result);
            } else {
                ClassInfo c = ci.owner() != null ? ci.owner().cls() : ck.declaringClassOf(ci.member());
                int fn = fnId(new FnKey("method", c, ci.owner(), ci.member(), ci.binders()));
                emit(Op.CALL, fn, base, argRegs.size(), result);
            }
            runAfterCall();
            return result;
        }

        /**
         * Arguments evaluated so far for the calls being built: owned values the callee will
         * take over, tracked meanwhile so an exception from a later argument releases them.
         */
        final List<Object> argsInFlight = new ArrayList<>();

        /** An argument value: scalars by value, aggregates as the address of a fresh copy. */
        int argument(Expr e, Type t) {
            if (t instanceof HandleT) {
                int r = ownedHandle(e, t); // the callee owns its parameters
                pendingHandles.add(r);
                argsInFlight.add(new HandleItem(r));
                return r;
            }
            if (Checker.isUnique(t)) {
                // Lent, not copied: the callee works on this very object.
                if (isOwnedTemp(e)) {
                    return addressOf(registerTemp(tempPlace(e), t));
                }
                if (!type(e).equals(t)) {
                    Mem m = temp(t);   // sum([1, 2, 3]), or a T[N] for an array<T>: a temporary, destroyed after the statement
                    into(m, e, t);
                    return addressOf(registerTemp(m, t));
                }
                Place p = place(e);
                if (p instanceof Mem) {
                    return addressOf(p);
                }
                // Stored inside a class object, which may move: lend a copy and put it back afterwards.
                Mem m = temp(t);
                long bits = sizeOf(t);
                copy(m, p, bits);
                afterCall.add(() -> copy(p, m, bits));
                return addressOf(m);
            }
            if (isAggregate(t)) {
                Mem copy = temp(t);
                into(copy, e, t);
                if (needsDestroy(t)) {
                    pendingTemps.add(copy);
                    pendingTempTypes.add(t);
                    argsInFlight.add(new TempItem(copy, t));
                }
                return addressOf(copy);
            }
            return value(e, t);
        }

        /** The arguments since {@code mark} now belong to the callee: stop tracking them. */
        void handOver(int mark) {
            while (argsInFlight.size() > mark) {
                Object o = argsInFlight.remove(argsInFlight.size() - 1);
                if (o instanceof HandleItem h) {
                    pendingHandles.remove(Integer.valueOf(h.reg()));
                } else if (o instanceof TempItem t) {
                    int i = pendingTemps.lastIndexOf(t.place());
                    pendingTemps.remove(i);
                    pendingTempTypes.remove(i);
                }
            }
        }

        // ------------------------------------------------------------ object creation

        int newObject(New n, Type t) {
            if (t instanceof HandleT h) {
                ClassT ct = (ClassT) h.target();
                int handle = reg();
                emit(Op.HNEW, handle, rtClassId(ct), imm(layoutOf(ct).size()));
                // Held while the constructor runs, so an exception from it releases the object.
                pendingHandles.add(handle);
                constructorCall(tb.calls.get(n), handle, n.args());
                pendingHandles.remove(Integer.valueOf(handle));
                return handle;
            }
            if (t instanceof AddrT a) {
                ClassT ct = (ClassT) a.target();
                int addr = reg();
                emit(Op.ALLOC, addr, constant(Math.max(1, layoutOf(ct).size())));
                constructorCall(tb.calls.get(n), addr, n.args());
                return addr;
            }
            throw new Unsupported(n.pos(), "unsupported new");
        }

        /** Constructs an inline object in place. */
        void construct(Mem dst, ClassT t, New n) {
            zero(dst, sizeOf(t));
            constructorCall(tb.calls.get(n), addressOf(dst), n.args());
        }

        void constructorCall(CallInfo ci, int thisValue, List<Expr> args) {
            if (ci == null) {
                throw new Unsupported(pos, "unresolved constructor");
            }
            ClassT t = ci.owner();
            if (t.cls().isRoot()) {
                return;
            }
            int fn;
            List<Type> ptypes;
            if (ci.member() == null) {
                fn = fnId(new FnKey("default-ctor", t.cls(), t, null, Map.of()));
                ptypes = List.of();
            } else {
                fn = fnId(new FnKey("ctor", t.cls(), t, ci.member(), ci.binders()));
                ptypes = ck.paramTypes(t, ci.member(), ci.binders());
            }
            List<Integer> argRegs = new ArrayList<>();
            argRegs.add(thisValue);
            int inFlight = argsInFlight.size();
            for (int i = 0; i < args.size(); i++) {
                argRegs.add(argument(args.get(i), ptypes.get(i)));
            }
            handOver(inFlight);
            int base = regs(argRegs.size());
            for (int i = 0; i < argRegs.size(); i++) {
                emit(Op.MOV, base + i, argRegs.get(i));
            }
            emit(Op.CALL, fn, base, argRegs.size(), -1);
            runAfterCall();
        }

        void runAfterCall() {
            List<Runnable> pending = new ArrayList<>(afterCall);
            afterCall.clear();
            pending.forEach(Runnable::run);
        }
    }
}
