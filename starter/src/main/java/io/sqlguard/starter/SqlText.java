package io.sqlguard.starter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlText {
    private static final int MAX_LENGTH = 16_384;
    private static final Pattern NUMBER = Pattern.compile("0[xX][0-9a-fA-F]+|0[bB][01]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[Ee][+-]?[0-9]+)?");
    private static final Pattern OPERATOR = Pattern.compile("<=>|>=|<=|<>|!=|:=|&&|\\|\\||<<|>>|->>|->");
    private static final Pattern LITERAL_PREFIX = Pattern.compile("(?i)[xbn]|_[a-z0-9]+");

    record Redacted(String sql, String shape, Long offset, boolean alwaysTrue, boolean explainable, int binds) {}

    private enum Kind { WORD, IDENTIFIER, LITERAL, BIND, SYMBOL }

    private record Token(String text, Kind kind, int bind, Long number) {
        String canonical() {
            return kind == Kind.WORD ? text.toLowerCase(Locale.ROOT) : text;
        }

        Long number(IntFunction<Long> params) {
            return kind == Kind.BIND ? params.apply(bind) : number;
        }
    }

    static Redacted redact(String sql, IntFunction<Long> params) {
        if (sql.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("SQL exceeds the capture limit");
        }
        List<Token> tokens = new ArrayList<>();
        Matcher number = NUMBER.matcher(sql);
        Matcher operator = OPERATOR.matcher(sql);
        boolean explainable = true;
        int binds = 0;
        for (int start = 0; start < sql.length();) {
            char c = sql.charAt(start);
            if (Character.isWhitespace(c)) {
                start++;
                continue;
            }
            if (c == '#' || (sql.startsWith("--", start)
                    && (start + 2 == sql.length() || sql.charAt(start + 2) <= ' '))) {
                explainable = false;
                while (start < sql.length() && sql.charAt(start) != '\r' && sql.charAt(start) != '\n') {
                    start++;
                }
                continue;
            }
            if (sql.startsWith("/*", start)) {
                if (sql.startsWith("/*!", start) || sql.regionMatches(true, start, "/*M!", 0, 4)) {
                    throw new IllegalArgumentException("Executable SQL comments are not captured");
                }
                explainable = false;
                int end = sql.indexOf("*/", start + 2);
                start = end < 0 ? sql.length() : end + 2;
                continue;
            }
            int end = start + 1;
            Token token;
            if (c == '\'' || c == '"' || c == '`') {
                end = quotedEnd(sql, start, c);
                if (c == '`') {
                    token = new Token(sql.substring(start, end), Kind.IDENTIFIER, 0, null);
                } else {
                    token = new Token("?", Kind.LITERAL, 0, null);
                    explainable = false;
                }
            } else if (c == '?') {
                token = new Token("?", Kind.BIND, binds++, null);
            } else if (number.region(start, sql.length()).lookingAt()) {
                end = number.end();
                token = new Token("?", Kind.LITERAL, 0, integer(number.group()));
                explainable = false;
            } else if (isWord(c)) {
                while (end < sql.length() && isWord(sql.charAt(end))) {
                    end++;
                }
                String word = sql.substring(start, end);
                if (end < sql.length() && (sql.charAt(end) == '\'' || sql.charAt(end) == '"')
                        && LITERAL_PREFIX.matcher(word).matches()) {
                    end = quotedEnd(sql, end, sql.charAt(end));
                    token = new Token("?", Kind.LITERAL, 0, null);
                    explainable = false;
                } else {
                    token = new Token(word, Kind.WORD, 0, null);
                }
            } else {
                if (operator.region(start, sql.length()).lookingAt()) {
                    end = operator.end();
                }
                token = new Token(sql.substring(start, end), Kind.SYMBOL, 0, null);
                explainable &= c != '@' && c != '\\';
            }
            tokens.add(token);
            start = end;
        }

        Long offset = null;
        boolean alwaysTrue = false;
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String word = tokens.get(i).canonical();
            if ("(".equals(word)) {
                depth++;
            } else if (")".equals(word)) {
                depth--;
            } else if (depth == 0 && "limit".equals(word) && i + 3 < tokens.size()) {
                String separator = tokens.get(i + 2).canonical();
                if (",".equals(separator)) {
                    offset = tokens.get(i + 1).number(params);
                } else if ("offset".equals(separator)) {
                    offset = tokens.get(i + 3).number(params);
                }
            } else if (depth == 0 && "where".equals(word)) {
                alwaysTrue = tautology(tokens, i + 1);
            }
        }
        return new Redacted(render(tokens, false), render(tokens, true), offset, alwaysTrue, explainable, binds);
    }

    private static boolean tautology(List<Token> tokens, int start) {
        int end = start;
        while (end < tokens.size()
                && !List.of("order", "limit", "returning", ";").contains(tokens.get(end).canonical())) {
            end++;
        }
        while (end - start > 2 && "(".equals(tokens.get(start).text()) && ")".equals(tokens.get(end - 1).text())) {
            start++;
            end--;
        }
        if (end - start == 1) {
            Token token = tokens.get(start);
            return "true".equals(token.canonical()) || (token.number() != null && token.number() != 0);
        }
        if (end - start == 3 && "=".equals(tokens.get(start + 1).text())) {
            Long left = tokens.get(start).number();
            return left != null && left.equals(tokens.get(start + 2).number());
        }
        return false;
    }

    private static String render(List<Token> tokens, boolean canonical) {
        StringBuilder result = new StringBuilder();
        String previous = "";
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String text = canonical ? token.canonical() : token.text();
            if (canonical && "in".equals(text) && i + 3 < tokens.size() && "(".equals(tokens.get(i + 1).text())) {
                int end = i + 2;
                while (end + 2 < tokens.size() && "?".equals(tokens.get(end).text())
                        && ",".equals(tokens.get(end + 1).text())) {
                    end += 2;
                }
                if ("?".equals(tokens.get(end).text()) && ")".equals(tokens.get(end + 1).text())) {
                    result.append(" in (?)");
                    previous = ")";
                    i = end + 1;
                    continue;
                }
            }
            if (canonical && ";".equals(text) && i == tokens.size() - 1) {
                break;
            }
            if (!result.isEmpty() && !List.of(")", ",", ".", ";").contains(text)
                    && !List.of("(", ".").contains(previous)) {
                result.append(' ');
            }
            result.append(text);
            previous = text;
        }
        return result.toString();
    }

    private static int quotedEnd(String sql, int start, char quote) {
        for (int i = start + 1; i < sql.length(); i++) {
            if (sql.charAt(i) == quote) {
                if (i + 1 == sql.length() || sql.charAt(i + 1) != quote) {
                    return i + 1;
                }
                i++;
            } else if (sql.charAt(i) == '\\' && quote != '`') {
                if (i + 1 < sql.length() && (sql.charAt(i + 1) == quote || sql.charAt(i + 1) == '\\')) {
                    throw new IllegalArgumentException("SQL quote depends on the server escape mode");
                }
                i++;
            }
        }
        throw new IllegalArgumentException("Unterminated SQL quote");
    }

    private static boolean isWord(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static Long integer(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
