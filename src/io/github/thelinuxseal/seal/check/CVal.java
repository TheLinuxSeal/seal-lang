package io.github.thelinuxseal.seal.check;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * A compile-time value: the contents of `<<...>>`, array sizes, integer
 * widths and `sizeof` results.
 *
 * <p>Inside a generic class body, values such as `N` are not yet known, so a
 * value may be symbolic ({@link Sym}, {@link Op}). Values are kept in a
 * normal form so that structurally equal values are {@code equals}: constants
 * are folded, and the operands of `+` and `*` are flattened and sorted, so
 * `N + K`, `K + N` and `(N + 0) + K` are all the same value.
 */
public sealed interface CVal {

    record IntV(BigInteger value) implements CVal {
        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** Identity is by bit pattern: {@link Double#equals} compares bits, so 0.0 and -0.0 differ (§8). */
    record FloatV(Double value) implements CVal {
        @Override
        public String toString() {
            return value.toString();
        }
    }

    record StrV(String value) implements CVal {
        @Override
        public String toString() {
            return "\"" + value + "\"";
        }
    }

    record BitV(boolean value) implements CVal {
        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /** A compile-time variable whose value is not yet known, e.g. `N`. */
    record Sym(String name) implements CVal {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A symbolic operation, e.g. `N + 1` or `-N`. */
    record Op(String op, List<CVal> args) implements CVal {
        @Override
        public String toString() {
            if (op.equals("?:")) {
                return wrap(args.get(0)) + " ? " + wrap(args.get(1)) + " : " + wrap(args.get(2));
            }
            if (args.size() == 1) {
                return op + wrap(args.get(0));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    sb.append(' ').append(op).append(' ');
                }
                sb.append(wrap(args.get(i)));
            }
            return sb.toString();
        }

        private static String wrap(CVal v) {
            return v instanceof Op o && o.args.size() > 1 ? "(" + v + ")" : v.toString();
        }
    }

    /** `sizeof(T)` or `alignof(T)` where T's layout is not yet known. */
    record LayoutOf(String op, Type type) implements CVal {
        @Override
        public String toString() {
            return op + "(" + type + ")";
        }
    }

    /** A type bound to a generic method's type parameter, `T = int` (§12.2). */
    record TypeV(Type type) implements CVal {
        @Override
        public String toString() {
            return type.toString();
        }
    }

    /**
     * A pure method's call on values not known yet, as `Fib.of(N)` inside a generic class (§17.2):
     * computed when they are. Calls of one method on the same values are the same value.
     */
    record PureCall(String name, Object method, List<CVal> args, Function<List<CVal>, CVal> compute) implements CVal {
        @Override
        public boolean equals(Object o) {
            return o instanceof PureCall c && c.method == method && c.args.equals(args);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(method) * 31 + args.hashCode();
        }

        @Override
        public String toString() {
            return name + "(" + String.join(", ", args.stream().map(CVal::toString).toList()) + ")";
        }
    }

    /** Placeholder for a value that failed to evaluate; errors have been reported. */
    record ErrV() implements CVal {
        @Override
        public String toString() {
            return "?";
        }
    }

    // ================================================================ construction

    static CVal of(long v) {
        return new IntV(BigInteger.valueOf(v));
    }

    static CVal of(BigInteger v) {
        return new IntV(v);
    }

    default boolean isConstant() {
        return switch (this) {
            case IntV i -> true;
            case FloatV f -> true;
            case StrV s -> true;
            case BitV b -> true;
            default -> false;
        };
    }

    default BigInteger intValue() {
        return this instanceof IntV i ? i.value() : null;
    }

    /**
     * Applies a binary operator, folding constants and normalizing.
     * Returns null if the operator does not apply to constant operands of
     * these kinds (the caller reports the error).
     */
    static CVal binary(String op, CVal a, CVal b) {
        if (a instanceof ErrV || b instanceof ErrV) {
            return new ErrV();
        }
        if (a.isConstant() && b.isConstant()) {
            return foldBinary(op, a, b);
        }
        if (op.equals("+") || op.equals("*")) {
            return normalizeCommutative(op, List.of(a, b));
        }
        if (op.equals("-")) {
            // a - b  ==  a + (-1 * b), so that N + 1 - 1 simplifies to N.
            return normalizeCommutative("+", List.of(a, binary("*", of(-1), b)));
        }
        return new Op(op, List.of(a, b));
    }

    static CVal unary(String op, CVal a) {
        if (a instanceof ErrV) {
            return a;
        }
        switch (op) {
            case "-":
                if (a instanceof IntV i) return new IntV(i.value().negate());
                if (a instanceof FloatV f) return new FloatV(-f.value());
                return a.isConstant() ? null : binary("*", of(-1), a);
            case "~":
                if (a instanceof IntV i) return new IntV(i.value().not());
                return a.isConstant() ? null : new Op("~", List.of(a));
            case "!":
                if (a instanceof BitV b) return new BitV(!b.value());
                return a.isConstant() ? null : new Op("!", List.of(a));
            case "alignfor":
                if (a instanceof IntV i) return new IntV(BigInteger.valueOf(alignFor(i.value())));
                return a.isConstant() ? null : new Op("alignfor", List.of(a));
            default:
                return null;
        }
    }

    /**
     * Alignment in bits for a scalar of the given size (§22): 8-, 16-, 32- and
     * 64-bit values align to their size; every other width can start at any bit.
     */
    static long alignFor(BigInteger size) {
        long s = size.bitLength() <= 7 ? size.longValue() : 0;
        return s == 8 || s == 16 || s == 32 || s == 64 ? s : 1;
    }

    private static CVal foldBinary(String op, CVal a, CVal b) {
        if (a instanceof IntV x && b instanceof IntV y) {
            BigInteger l = x.value();
            BigInteger r = y.value();
            return switch (op) {
                case "+" -> new IntV(l.add(r));
                case "-" -> new IntV(l.subtract(r));
                case "*" -> new IntV(l.multiply(r));
                case "**" -> power(l, r);
                case "/" -> r.signum() == 0 ? new ErrV() : new IntV(l.divide(r));
                case "%" -> r.signum() == 0 ? new ErrV() : new IntV(l.remainder(r));
                case "&" -> new IntV(l.and(r));
                case "|" -> new IntV(l.or(r));
                case "^" -> new IntV(l.xor(r));
                case "<<<" -> r.bitLength() > 31 ? new ErrV()
                        : new IntV(r.signum() >= 0 ? l.shiftLeft(r.intValue()) : l.shiftRight(-r.intValue()));
                case "==" -> new BitV(l.equals(r));
                case "!=" -> new BitV(!l.equals(r));
                case "<" -> new BitV(l.compareTo(r) < 0);
                case ">" -> new BitV(l.compareTo(r) > 0);
                case "<=" -> new BitV(l.compareTo(r) <= 0);
                case ">=" -> new BitV(l.compareTo(r) >= 0);
                case "max" -> new IntV(l.max(r));
                // Round l up to a multiple of r.
                case "roundup" -> r.signum() <= 0 ? new ErrV()
                        : new IntV(l.add(r).subtract(BigInteger.ONE).divide(r).multiply(r));
                default -> null;
            };
        }
        if (a instanceof FloatV x && b instanceof FloatV y) {
            double l = x.value();
            double r = y.value();
            return switch (op) {
                case "+" -> new FloatV(l + r);
                case "-" -> new FloatV(l - r);
                case "*" -> new FloatV(l * r);
                case "**" -> new FloatV(StrictMath.pow(l, r));
                case "/" -> new FloatV(l / r);
                case "==" -> new BitV(l == r);
                case "!=" -> new BitV(l != r);
                case "<" -> new BitV(l < r);
                case ">" -> new BitV(l > r);
                case "<=" -> new BitV(l <= r);
                case ">=" -> new BitV(l >= r);
                default -> null;
            };
        }
        if (a instanceof BitV x && b instanceof BitV y) {
            return switch (op) {
                case "&&", "&" -> new BitV(x.value() && y.value());
                case "||", "|" -> new BitV(x.value() || y.value());
                case "^" -> new BitV(x.value() ^ y.value());
                case "==" -> new BitV(x.value() == y.value());
                case "!=" -> new BitV(x.value() != y.value());
                default -> null;
            };
        }
        if (a instanceof StrV x && b instanceof StrV y) {
            return switch (op) {
                case "+" -> new StrV(x.value() + y.value());
                case "==" -> new BitV(x.value().equals(y.value()));
                case "!=" -> new BitV(!x.value().equals(y.value()));
                default -> null;
            };
        }
        return null;
    }

    /** c ? a : b: the one c picks, or, while c isn't known, a value that picks once it is. */
    static CVal cond(CVal c, CVal a, CVal b) {
        if (c instanceof ErrV) {
            return c;
        }
        if (c instanceof BitV bit) {
            return bit.value() ? a : b;
        }
        return new Op("?:", List.of(c, a, b));
    }

    /** The most bits a compile-time power may have: 2 ** 65536 is plenty for a constant. */
    static final int MAX_POWER_BITS = 1 << 16;

    /**
     * base ** exp exactly; a negative exponent gives 1 / base ** -exp in integer division, as at
     * run time; an error for 0 ** -1 (division by zero) and for a result over MAX_POWER_BITS bits.
     */
    static CVal power(BigInteger base, BigInteger exp) {
        if (exp.signum() < 0) {
            if (base.signum() == 0) {
                return new ErrV();
            }
            if (base.equals(BigInteger.ONE)) {
                return of(1);
            }
            return base.equals(BigInteger.ONE.negate()) ? of(exp.testBit(0) ? -1 : 1) : of(0);
        }
        if (base.abs().compareTo(BigInteger.ONE) <= 0) {
            return new IntV(exp.signum() == 0 ? BigInteger.ONE : base.signum() < 0 && !exp.testBit(0) ? BigInteger.ONE : base);
        }
        if (exp.bitLength() > 31 || (long) base.abs().bitLength() * exp.longValue() > MAX_POWER_BITS + 1L) {
            return new ErrV();
        }
        return new IntV(base.pow(exp.intValue()));
    }

    /** Flattens nested `op`, folds the integer constants, and sorts the rest. */
    private static CVal normalizeCommutative(String op, List<CVal> operands) {
        List<CVal> terms = new ArrayList<>();
        flatten(op, operands, terms);

        BigInteger constant = op.equals("+") ? BigInteger.ZERO : BigInteger.ONE;
        List<CVal> symbolic = new ArrayList<>();
        for (CVal t : terms) {
            if (t instanceof IntV i) {
                constant = op.equals("+") ? constant.add(i.value()) : constant.multiply(i.value());
            } else {
                symbolic.add(t);
            }
        }
        if (op.equals("*") && constant.signum() == 0) {
            return of(0);
        }
        symbolic.sort(Comparator.comparing(CVal::toString));
        boolean identity = op.equals("+") ? constant.signum() == 0 : constant.equals(BigInteger.ONE);
        if (!identity) {
            symbolic.add(0, new IntV(constant));
        }
        if (symbolic.isEmpty()) {
            return new IntV(constant);
        }
        if (symbolic.size() == 1) {
            return symbolic.get(0);
        }
        return new Op(op, List.copyOf(symbolic));
    }

    private static void flatten(String op, List<CVal> operands, List<CVal> out) {
        for (CVal v : operands) {
            if (v instanceof Op o && o.op().equals(op) && o.args().size() > 1) {
                flatten(op, o.args(), out);
            } else {
                out.add(v);
            }
        }
    }

    /**
     * Replaces symbols according to {@code subst} (null means "leave as is"),
     * re-evaluates {@code sizeof}/{@code alignof} with {@code layout}, and
     * re-normalizes.
     */
    static CVal substitute(CVal v, Function<String, CVal> subst, Function<LayoutOf, CVal> layout) {
        return switch (v) {
            case Sym s -> {
                CVal r = subst.apply(s.name());
                yield r == null ? s : r;
            }
            case LayoutOf l -> layout.apply(l);
            case PureCall c -> {
                List<CVal> args = c.args().stream().map(a -> substitute(a, subst, layout)).toList();
                if (args.stream().anyMatch(a -> a instanceof ErrV)) {
                    yield new ErrV();
                }
                yield args.stream().allMatch(CVal::isConstant) ? c.compute().apply(args)
                        : new PureCall(c.name(), c.method(), args, c.compute());
            }
            case Op o when o.op().equals("?:") -> {
                // Only the side the condition picks is worked out.
                CVal c = substitute(o.args().get(0), subst, layout);
                yield c instanceof BitV bit ? substitute(o.args().get(bit.value() ? 1 : 2), subst, layout)
                        : cond(c, substitute(o.args().get(1), subst, layout), substitute(o.args().get(2), subst, layout));
            }
            case Op o when (o.op().equals("&&") || o.op().equals("||")) && o.args().size() == 2 -> {
                CVal l = substitute(o.args().get(0), subst, layout);
                if (l instanceof BitV bit && bit.value() == o.op().equals("||")) {
                    yield l; // decided without the right side, as when the program runs
                }
                CVal r = binary(o.op(), l, substitute(o.args().get(1), subst, layout));
                yield r == null ? new ErrV() : r;
            }
            case Op o -> {
                List<CVal> args = o.args().stream().map(a -> substitute(a, subst, layout)).toList();
                if (args.size() == 1) {
                    CVal r = unary(o.op(), args.get(0));
                    yield r == null ? new ErrV() : r;
                }
                CVal acc = args.get(0);
                for (int i = 1; i < args.size(); i++) {
                    CVal r = binary(o.op(), acc, args.get(i));
                    acc = r == null ? new ErrV() : r;
                }
                yield acc;
            }
            default -> v;
        };
    }
}
