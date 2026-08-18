package com.allrounder99.calculator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

final class CalculatorEngine {
    private static final Pattern INTEGER_EXPR = Pattern.compile("-?\\d+(?:[+−×]-?\\d+)*");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_UP);

    static String evaluate(String expression) {
        String cleaned = trimOperators(expression == null ? "" : expression.trim());
        if (cleaned.isEmpty() || cleaned.equals("-")) return "0";
        if (INTEGER_EXPR.matcher(cleaned).matches()) return integerEvaluate(cleaned);
        double value = new Parser(cleaned).parse();
        if (!Double.isFinite(value)) throw new ArithmeticException("Invalid result");
        if (Math.abs(value) < 1e-14) value = 0;
        BigDecimal decimal = new BigDecimal(value, MC).stripTrailingZeros();
        String plain = decimal.toPlainString();
        return plain.length() > 120 ? decimal.toEngineeringString() : plain;
    }

    static String preview(String expression) {
        try { return evaluate(expression); }
        catch (Exception ignored) { return ""; }
    }

    static String trimOperators(String value) {
        while (!value.isEmpty() && isOperator(value.charAt(value.length() - 1))) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isOperator(char c) {
        return c == '+' || c == '−' || c == '×' || c == '÷' || c == '^';
    }

    private static int precedence(char c) {
        return c == '×' ? 2 : 1;
    }

    private static String integerEvaluate(String expression) {
        Deque<BigInteger> values = new ArrayDeque<>();
        Deque<Character> operators = new ArrayDeque<>();
        int i = 0;
        boolean expectNumber = true;
        while (i < expression.length()) {
            if (expectNumber) {
                int start = i;
                if (expression.charAt(i) == '-') i++;
                while (i < expression.length() && Character.isDigit(expression.charAt(i))) i++;
                values.push(new BigInteger(expression.substring(start, i)));
                expectNumber = false;
            } else {
                char op = expression.charAt(i++);
                while (!operators.isEmpty() && precedence(operators.peek()) >= precedence(op)) apply(values, operators.pop());
                operators.push(op);
                expectNumber = true;
            }
        }
        while (!operators.isEmpty()) apply(values, operators.pop());
        return values.pop().toString();
    }

    private static void apply(Deque<BigInteger> values, char op) {
        BigInteger right = values.pop(), left = values.pop();
        values.push(op == '+' ? left.add(right) : op == '−' ? left.subtract(right) : left.multiply(right));
    }

    private static final class Parser {
        private final String source;
        private int pos;
        Parser(String value) { source = value.replace('×', '*').replace('÷', '/').replace('−', '-'); }

        double parse() {
            double value = expression();
            skip();
            if (pos != source.length()) throw new IllegalArgumentException("Unexpected input");
            return value;
        }

        private double expression() {
            double value = term();
            while (true) {
                skip();
                if (eat('+')) value += term();
                else if (eat('-')) value -= term();
                else return value;
            }
        }

        private double term() {
            double value = power();
            while (true) {
                skip();
                if (eat('*')) value *= power();
                else if (eat('/')) {
                    double divisor = power();
                    if (divisor == 0) throw new ArithmeticException("Division by zero");
                    value /= divisor;
                } else if (eat('%')) value /= 100d;
                else return value;
            }
        }

        private double power() {
            double value = unary();
            skip();
            if (eat('^')) value = Math.pow(value, power());
            return value;
        }

        private double unary() {
            skip();
            if (eat('+')) return unary();
            if (eat('-')) return -unary();
            return primary();
        }

        private double primary() {
            skip();
            if (eat('(')) {
                double value = expression();
                if (!eat(')')) throw new IllegalArgumentException("Missing parenthesis");
                return value;
            }
            if (Character.isLetter(peek())) {
                String name = identifier();
                if (name.equals("pi")) return Math.PI;
                if (!eat('(')) throw new IllegalArgumentException("Function parenthesis required");
                double value = expression();
                if (!eat(')')) throw new IllegalArgumentException("Missing parenthesis");
                switch (name) {
                    case "sin": return Math.sin(Math.toRadians(value));
                    case "cos": return Math.cos(Math.toRadians(value));
                    case "tan": return Math.tan(Math.toRadians(value));
                    case "sqrt": return Math.sqrt(value);
                    case "log": return Math.log10(value);
                    default: throw new IllegalArgumentException("Unknown function");
                }
            }
            int start = pos;
            while (Character.isDigit(peek()) || peek() == '.') pos++;
            if (start == pos) throw new IllegalArgumentException("Number expected");
            return Double.parseDouble(source.substring(start, pos));
        }

        private char peek() { return pos < source.length() ? source.charAt(pos) : '\0'; }
        private boolean eat(char c) { skip(); if (peek() == c) { pos++; return true; } return false; }
        private void skip() { while (Character.isWhitespace(peek())) pos++; }
        private String identifier() { int start = pos; while (Character.isLetter(peek())) pos++; return source.substring(start, pos); }
    }
}
