package io.github.thelinuxseal.seal.jvm;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.reflect.AccessFlag;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The text `seal dump` prints: each generated class, its fields, and its methods' bytecode, as
 * javap -c would show it, with the Seal line each run of instructions comes from as a
 * `; File.seal:line` comment.
 */
public final class ClassText {

    private ClassText() {
    }

    /** Every class, by name; the program's own before the library's. */
    public static String of(Map<String, byte[]> classes) {
        StringBuilder out = new StringBuilder();
        classes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator
                        .comparing(JvmGen::isLibrary)
                        .thenComparing(java.util.Comparator.naturalOrder())))
                .forEach(e -> classText(ClassFile.of().parse(e.getValue()), out));
        return out.toString();
    }

    private static void classText(ClassModel m, StringBuilder out) {
        String source = m.findAttribute(Attributes.sourceFile()).map(a -> a.sourceFile().stringValue()).orElse("?");
        boolean isInterface = m.flags().has(AccessFlag.INTERFACE);
        out.append(isInterface ? "interface " : "class ").append(name(m.thisClass().asInternalName()));
        m.superclass().filter(s -> !s.asInternalName().equals("java/lang/Object"))
                .ifPresent(s -> out.append(" extends ").append(name(s.asInternalName())));
        if (!m.interfaces().isEmpty()) {
            out.append(isInterface ? " extends " : " implements ")
                    .append(m.interfaces().stream().map(i -> name(i.asInternalName())).collect(Collectors.joining(", ")));
        }
        out.append("  (").append(source).append(")\n");
        for (FieldModel f : m.fields()) {
            out.append("  ").append(f.flags().has(AccessFlag.STATIC) ? "static " : "")
                    .append(f.fieldTypeSymbol().displayName()).append(' ').append(f.fieldName().stringValue()).append('\n');
        }
        for (MethodModel method : m.methods()) {
            out.append('\n').append("  ").append(method.flags().has(AccessFlag.STATIC) ? "static " : "")
                    .append(method.flags().has(AccessFlag.ABSTRACT) ? "abstract " : "")
                    .append(method.methodName().stringValue()).append(method.methodTypeSymbol().displayDescriptor()).append('\n');
            method.code().ifPresent(code -> codeText(code, source, out));
        }
        out.append('\n');
    }

    private static void codeText(CodeModel code, String source, StringBuilder out) {
        Map<Label, Integer> labels = new IdentityHashMap<>();
        for (CodeElement e : code) {
            if (e instanceof LabelTarget t) {
                labels.putIfAbsent(t.label(), labels.size());
            }
        }
        java.util.Set<Label> used = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (CodeElement e : code) {
            switch (e) {
                case BranchInstruction b -> used.add(b.target());
                case TableSwitchInstruction t -> {
                    used.add(t.defaultTarget());
                    t.cases().forEach(c -> used.add(c.target()));
                }
                case LookupSwitchInstruction l -> {
                    used.add(l.defaultTarget());
                    l.cases().forEach(c -> used.add(c.target()));
                }
                case ExceptionCatch c -> {
                    used.add(c.tryStart());
                    used.add(c.tryEnd());
                    used.add(c.handler());
                }
                default -> { }
            }
        }
        for (CodeElement e : code) {
            switch (e) {
                case LabelTarget t when used.contains(t.label()) -> out.append("   L").append(labels.get(t.label())).append(":\n");
                case LineNumber n -> out.append("        ; ").append(source).append(':').append(n.line()).append('\n');
                case ExceptionCatch c -> out.append("        catch L").append(labels.get(c.tryStart())).append(" to L")
                        .append(labels.get(c.tryEnd())).append(" -> L").append(labels.get(c.handler()))
                        .append(c.catchType().map(t -> " " + name(t.asInternalName())).orElse(" any")).append('\n');
                case Instruction i -> out.append("        ").append(instruction(i, labels)).append('\n');
                default -> { }
            }
        }
    }

    private static String instruction(Instruction i, Map<Label, Integer> labels) {
        String op = i.opcode().name().toLowerCase(java.util.Locale.ROOT);
        String operands = switch (i) {
            case LoadInstruction l -> op.matches(".*_\\d") ? "" : String.valueOf(l.slot());
            case StoreInstruction s -> op.matches(".*_\\d") ? "" : String.valueOf(s.slot());
            case IncrementInstruction n -> n.slot() + " " + n.constant();
            case ConstantInstruction c -> op.matches(".*(const_m?\\d|const_null)") ? "" : constant(c.constantValue());
            case FieldInstruction f -> name(f.owner().asInternalName()) + "." + f.name().stringValue() + " "
                    + f.typeSymbol().displayName();
            case InvokeInstruction v -> name(v.owner().asInternalName()) + "." + v.name().stringValue()
                    + v.typeSymbol().displayDescriptor();
            case BranchInstruction b -> "L" + labels.get(b.target());
            case TableSwitchInstruction t -> cases(t.cases(), labels) + " default L" + labels.get(t.defaultTarget());
            case LookupSwitchInstruction l -> cases(l.cases(), labels) + " default L" + labels.get(l.defaultTarget());
            case NewObjectInstruction n -> name(n.className().asInternalName());
            case NewReferenceArrayInstruction n -> name(n.componentType().asInternalName());
            case NewPrimitiveArrayInstruction n -> n.typeKind().upperBound().displayName();
            case TypeCheckInstruction t -> name(t.type().asInternalName());
            default -> "";
        };
        return operands.isEmpty() ? op : op + " " + operands;
    }

    private static String cases(java.util.List<SwitchCase> cases, Map<Label, Integer> labels) {
        return cases.stream().map(c -> c.caseValue() + ": L" + labels.get(c.target())).collect(Collectors.joining(", "));
    }

    private static String constant(Object v) {
        return v instanceof String s ? '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'
                : v instanceof Long ? v + "L" : v instanceof Float ? v + "f" : String.valueOf(v);
    }

    /** A class's name as Java writes it: io.github.thelinuxseal.seal.lang.Writer, Main$lambda$1. */
    private static String name(String internal) {
        return internal.replace('/', '.');
    }
}
