package io.github.thelinuxseal.seal.check;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

/** Semantic types, produced by resolving {@link io.github.thelinuxseal.seal.ast.Ast.TypeRef}s. */
public sealed interface Type {

    /**
     * `int<<W>>` (signed) or `uint<<W>>`. The width may be symbolic, e.g. `int<<N>>`.
     * `char` is a 16-bit unsigned integer kept apart from `uint<<16>>` by {@code isChar} (§39).
     */
    record IntT(CVal width, boolean signed, boolean isChar) implements Type {
        public IntT(CVal width, boolean signed) {
            this(width, signed, false);
        }

        @Override
        public String toString() {
            if (isChar) {
                return "char";
            }
            if (width.equals(CVal.of(32))) {
                return signed ? "int" : "uint";
            }
            return (signed ? "int" : "uint") + "<<" + width + ">>";
        }
    }

    /**
     * A fixed-point number (§37.1): `fixed<<N, M>>` (signed) or `ufixed<<N, M>>`.
     * N bits before the binary point (including the sign bit when signed) and
     * M after. Stored as an (N + M)-bit integer scaled by 2^M.
     */
    record FixedT(CVal intBits, CVal fracBits, boolean signed) implements Type {
        public CVal width() {
            CVal w = CVal.binary("+", intBits, fracBits);
            return w == null ? new CVal.ErrV() : w;
        }

        @Override
        public String toString() {
            return (signed ? "fixed" : "ufixed") + "<<" + intBits + ", " + fracBits + ">>";
        }
    }

    record BitT() implements Type {
        @Override
        public String toString() {
            return "bit";
        }
    }

    record FloatT() implements Type {
        @Override
        public String toString() {
            return "float";
        }
    }

    record DoubleT() implements Type {
        @Override
        public String toString() {
            return "double";
        }
    }

    record VoidT() implements Type {
        @Override
        public String toString() {
            return "void";
        }
    }

    record AddrT(Type target) implements Type {
        @Override
        public String toString() {
            return "addr<" + target + ">";
        }
    }

    record HandleT(Type target) implements Type {
        @Override
        public String toString() {
            return "handle<" + target + ">";
        }
    }

    /**
     * An instance of a class shape.
     *
     * @param header    index of the class header (shape) this instance uses
     * @param valueArgs the values bound by that header, in order; empty for the bare shape
     */
    record ClassT(ClassInfo cls, List<Type> typeArgs, List<CVal> valueArgs, int header) implements Type {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(cls.name);
            if (!typeArgs.isEmpty()) {
                sb.append(typeArgs.stream().map(Type::toString).collect(Collectors.joining(", ", "<", ">")));
            }
            if (!valueArgs.isEmpty()) {
                sb.append(valueArgs.stream().map(CVal::toString).collect(Collectors.joining(", ", "<<", ">>")));
            }
            return sb.toString();
        }
    }

    /** A class type parameter such as `T`, inside the generic class body. */
    record ParamT(String name) implements Type {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * `array<T><<N>>`: N elements of T, inline, copied as a value (§21.1). `array<T><<N, M>>`
     * is N elements of array<T><<M>>, and prints that way.
     */
    record ArrayT(Type element, CVal size) implements Type {
        @Override
        public String toString() {
            List<String> lengths = new java.util.ArrayList<>();
            Type t = this;
            while (t instanceof ArrayT a) {
                lengths.add(a.size == null ? "?" : a.size.toString());
                t = a.element;
            }
            return "array<" + t + "><<" + String.join(", ", lengths) + ">>";
        }
    }

    /** The type of `null`, assignable to any `addr` or `handle`. */
    record NullT() implements Type {
        @Override
        public String toString() {
            return "null";
        }
    }

    /**
     * An integer constant whose type is decided by context: a literal, or a
     * compile-time value such as `N`. The value is null when not yet known. {@code isChar} marks a
     * character literal, which is a char unless the context wants another integer type.
     */
    record IntConstT(BigInteger value, boolean isChar) implements Type {
        public IntConstT(BigInteger value) {
            this(value, false);
        }

        @Override
        public String toString() {
            if (isChar) {
                return value == null || value.intValue() < 32 ? "character constant" : "character constant '" + (char) value.intValue() + "'";
            }
            return value == null ? "integer constant" : "integer constant " + value;
        }
    }

    /**
     * A floating-point constant whose type is decided by context. The value is
     * exact (a literal's decimal digits, not a double approximation) so that
     * fixed-point conversions are exact; it is null when not known.
     */
    record FloatConstT(java.math.BigDecimal value) implements Type {
        @Override
        public String toString() {
            return "floating-point constant";
        }
    }

    /** A string literal. */
    record StrConstT(String value) implements Type {
        @Override
        public String toString() {
            return "string literal";
        }
    }

    /**
     * An array literal, `[a, b, c]`, whose type is decided by context: it fits any `T[n]`
     * with n elements that each fit T. Holds the elements' own (possibly constant) types.
     */
    record ArrayConstT(List<Type> elements) implements Type {
        @Override
        public String toString() {
            return "array literal of " + elements.size() + (elements.size() == 1 ? " element" : " elements");
        }
    }

    /**
     * A lambda passed as an argument, before the call is resolved: it fits any functional
     * interface whose method takes as many parameters, and is checked once the call is known.
     */
    record LambdaT(io.github.thelinuxseal.seal.ast.Ast.Lambda lambda) implements Type {
        @Override
        public String toString() {
            return "lambda";
        }
    }

    /**
     * One of Java's primitive types, as seal.java names it (docs/JAVA.md §3.1): JavaInt is Java's
     * int. A value of one is held as the Seal number with the same values (a JavaInt as an int,
     * a JavaByte as an int<<8>>), and has no operators: a cast makes it Seal's.
     *
     * @param code its JVM descriptor: I, J, S, B, F, D, C or Z
     */
    record JavaPrimT(char code) implements Type {

        /** The Java type for a descriptor's letter, or null if it is none. */
        public static JavaPrimT of(char code) {
            return "IJSBFDCZ".indexOf(code) < 0 ? null : new JavaPrimT(code);
        }

        /** The Seal type holding the same values, as the JVM holds them. */
        public Type sealType() {
            return switch (code) {
                case 'I' -> INT;
                case 'J' -> new IntT(CVal.of(64), true);
                case 'S' -> new IntT(CVal.of(16), true);
                case 'B' -> new IntT(CVal.of(8), true);
                case 'F' -> FLOAT;
                case 'D' -> DOUBLE;
                case 'C' -> CHAR;
                default -> BIT;
            };
        }

        /** Java's name for it: int, boolean. */
        public String javaName() {
            return switch (code) {
                case 'I' -> "int";
                case 'J' -> "long";
                case 'S' -> "short";
                case 'B' -> "byte";
                case 'F' -> "float";
                case 'D' -> "double";
                case 'C' -> "char";
                default -> "boolean";
            };
        }

        @Override
        public String toString() {
            return switch (code) {
                case 'I' -> "JavaInt";
                case 'J' -> "JavaLong";
                case 'S' -> "JavaShort";
                case 'B' -> "JavaByte";
                case 'F' -> "JavaFloat";
                case 'D' -> "JavaDouble";
                case 'C' -> "JavaChar";
                default -> "JavaBoolean";
            };
        }
    }

    /** A class name used as a value, as in `Integer.toString(32)`. */
    record ClassRefT(ClassInfo cls) implements Type {
        @Override
        public String toString() {
            return "class " + cls.name;
        }
    }

    /** The type of an expression that failed to check; suppresses follow-on errors. */
    record ErrorT() implements Type {
        @Override
        public String toString() {
            return "<error>";
        }
    }

    Type INT = new IntT(CVal.of(32), true);
    Type CHAR = new IntT(CVal.of(16), false, true);
    Type UINT = new IntT(CVal.of(32), false);
    Type BIT = new BitT();
    Type FLOAT = new FloatT();
    Type DOUBLE = new DoubleT();
    Type VOID = new VoidT();
    Type ERROR = new ErrorT();
    Type NULL = new NullT();

    default boolean isNumeric() {
        return this instanceof IntT || this instanceof FloatT || this instanceof DoubleT
                || this instanceof IntConstT || this instanceof FloatConstT || this instanceof FixedT;
    }

    default boolean isInteger() {
        return this instanceof IntT || this instanceof IntConstT;
    }

    default boolean isError() {
        return this instanceof ErrorT;
    }
}
