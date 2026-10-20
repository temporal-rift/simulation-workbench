package io.github.temporalrift.workbench.experiment.domain.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.IntPredicate;

import io.github.temporalrift.workbench.experiment.EffectiveRules;

/**
 * A parameter space constraint as the space publishes it, evaluated on the effective rules.
 *
 * <pre>
 * constraint := chain { "and" chain }
 * chain      := sum ( ("&lt;" | "&lt;=" | "&gt;" | "&gt;=" | "==") sum )+      a chain holds when every link does
 * sum        := product { ("+" | "-") product }
 * product    := unary { "*" unary }
 * unary      := "-" unary | number | key | "max" "(" sum "," sum ")" | "(" sum ")"
 * </pre>
 *
 * A key is a full {@code game.rules.} path. Keys may contain {@code -}, so a subtraction is written with spaces around
 * the operator. Arithmetic is exact decimal arithmetic.
 */
public final class ConstraintExpression {

    private static final String KEY_PREFIX = "game.rules.";

    private final String source;
    private final List<List<Link>> chains;
    private final SortedSet<String> keys;

    private ConstraintExpression(String source, List<List<Link>> chains, SortedSet<String> keys) {
        this.source = source;
        this.chains = chains;
        this.keys = Collections.unmodifiableSortedSet(keys);
    }

    public static ConstraintExpression parse(String source) {
        return new Parser(source).constraint();
    }

    public String source() {
        return source;
    }

    /** The rules properties the expression reads. */
    public SortedSet<String> keys() {
        return keys;
    }

    /** Whether every chain holds on the rules; every key it reads must be a number there. */
    public boolean holds(EffectiveRules rules) {
        return chains.stream().allMatch(chain -> holds(chain, rules));
    }

    private static boolean holds(List<Link> chain, EffectiveRules rules) {
        var left = chain.getFirst().operand().evaluate(rules);
        for (var link : chain.subList(1, chain.size())) {
            var right = link.operand().evaluate(rules);
            if (!link.comparison().test(left.compareTo(right))) {
                return false;
            }
            left = right;
        }
        return true;
    }

    @Override
    public String toString() {
        return source;
    }

    private enum Comparison {
        LESS("<") {
            @Override
            boolean test(int order) {
                return order < 0;
            }
        },
        LESS_OR_EQUAL("<=") {
            @Override
            boolean test(int order) {
                return order <= 0;
            }
        },
        GREATER(">") {
            @Override
            boolean test(int order) {
                return order > 0;
            }
        },
        GREATER_OR_EQUAL(">=") {
            @Override
            boolean test(int order) {
                return order >= 0;
            }
        },
        EQUAL("==") {
            @Override
            boolean test(int order) {
                return order == 0;
            }
        };

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        abstract boolean test(int order);

        static Comparison of(String symbol) {
            for (var comparison : values()) {
                if (comparison.symbol.equals(symbol)) {
                    return comparison;
                }
            }
            return null;
        }
    }

    /** One operand of a chain and the comparison that joins it to the operand before it; none for the first. */
    private record Link(Comparison comparison, Operand operand) {}

    private sealed interface Operand {
        BigDecimal evaluate(EffectiveRules rules);
    }

    private record Literal(BigDecimal value) implements Operand {
        @Override
        public BigDecimal evaluate(EffectiveRules rules) {
            return value;
        }
    }

    private record Key(String path) implements Operand {
        @Override
        public BigDecimal evaluate(EffectiveRules rules) {
            return rules.number(path).orElseThrow(() -> new IllegalStateException(path + " is not a number"));
        }
    }

    private record Negation(Operand operand) implements Operand {
        @Override
        public BigDecimal evaluate(EffectiveRules rules) {
            return operand.evaluate(rules).negate();
        }
    }

    private record Sum(Operand left, boolean add, Operand right) implements Operand {
        @Override
        public BigDecimal evaluate(EffectiveRules rules) {
            var l = left.evaluate(rules);
            var r = right.evaluate(rules);
            return add ? l.add(r) : l.subtract(r);
        }
    }

    private record Product(Operand left, Operand right) implements Operand {
        @Override
        public BigDecimal evaluate(EffectiveRules rules) {
            return left.evaluate(rules).multiply(right.evaluate(rules));
        }
    }

    private record Maximum(Operand left, Operand right) implements Operand {
        @Override
        public BigDecimal evaluate(EffectiveRules rules) {
            return left.evaluate(rules).max(right.evaluate(rules));
        }
    }

    private static final class Parser {

        private final String source;
        private final List<String> tokens = new ArrayList<>();
        private final SortedSet<String> keys = new TreeSet<>();
        private int position;

        Parser(String source) {
            this.source = source;
            tokenize();
        }

        ConstraintExpression constraint() {
            var chains = new ArrayList<List<Link>>();
            chains.add(chain());
            while (accept("and")) {
                chains.add(chain());
            }
            if (position < tokens.size()) {
                throw error("unexpected '" + tokens.get(position) + "'");
            }
            return new ConstraintExpression(source, List.copyOf(chains), keys);
        }

        private List<Link> chain() {
            var links = new ArrayList<Link>();
            links.add(new Link(null, sum()));
            Comparison comparison;
            while (position < tokens.size() && (comparison = Comparison.of(tokens.get(position))) != null) {
                position++;
                links.add(new Link(comparison, sum()));
            }
            if (links.size() < 2) {
                throw error("a comparison is missing");
            }
            return List.copyOf(links);
        }

        private Operand sum() {
            var left = product();
            while (peekIs("+") || peekIs("-")) {
                var add = tokens.get(position++).equals("+");
                left = new Sum(left, add, product());
            }
            return left;
        }

        private Operand product() {
            var left = unary();
            while (accept("*")) {
                left = new Product(left, unary());
            }
            return left;
        }

        private Operand unary() {
            if (accept("-")) {
                return new Negation(unary());
            }
            return primary();
        }

        private Operand primary() {
            if (position >= tokens.size()) {
                throw error("an operand is missing");
            }
            var token = tokens.get(position++);
            if (token.equals("(")) {
                var inner = sum();
                expect(")");
                return inner;
            }
            if (token.equals("max")) {
                expect("(");
                var left = sum();
                expect(",");
                var right = sum();
                expect(")");
                return new Maximum(left, right);
            }
            if (token.startsWith(KEY_PREFIX)) {
                keys.add(token);
                return new Key(token);
            }
            if (Character.isDigit(token.charAt(0))) {
                return new Literal(new BigDecimal(token));
            }
            throw error("unexpected '" + token + "'");
        }

        private boolean peekIs(String token) {
            return position < tokens.size() && tokens.get(position).equals(token);
        }

        private boolean accept(String token) {
            if (peekIs(token)) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(String token) {
            if (!accept(token)) {
                throw error("'" + token + "' is missing");
            }
        }

        private void tokenize() {
            int i = 0;
            while (i < source.length()) {
                var c = source.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (source.startsWith(KEY_PREFIX, i)) {
                    i = take(i, Parser::isKeyCharacter);
                } else if (Character.isDigit(c)) {
                    i = take(i, ch -> Character.isDigit(ch) || ch == '.');
                } else if (Character.isLetter(c)) {
                    i = take(i, Character::isLetter);
                } else if ("()+-*,".indexOf(c) >= 0) {
                    tokens.add(String.valueOf(c));
                    i++;
                } else if (c == '<' || c == '>' || c == '=') {
                    var twice = i + 1 < source.length() && source.charAt(i + 1) == '=';
                    var symbol = twice ? source.substring(i, i + 2) : String.valueOf(c);
                    if (Comparison.of(symbol) == null) {
                        throw error("unexpected '" + symbol + "'");
                    }
                    tokens.add(symbol);
                    i += symbol.length();
                } else {
                    throw error("unexpected '" + c + "'");
                }
            }
        }

        private int take(int start, IntPredicate part) {
            int end = start;
            while (end < source.length() && part.test(source.charAt(end))) {
                end++;
            }
            tokens.add(source.substring(start, end));
            return end;
        }

        private static boolean isKeyCharacter(int c) {
            return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("constraint '" + source + "': " + message);
        }
    }
}
