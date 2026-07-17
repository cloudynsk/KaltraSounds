package net.kaltra.sounds;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class Criteria {
    private Criteria() {}

    static boolean matches(String criterion, String value) {
        if (criterion == null) return false;
        String c = criterion.trim();
        String v = value == null ? "" : value;
        if (c.equalsIgnoreCase("Any")) return true;

        int open = c.indexOf('[');
        int close = c.lastIndexOf(']');
        if (open > 0 && close > open) {
            String mode = c.substring(0, open).trim().toLowerCase(Locale.ROOT);
            String[] options = Arrays.stream(c.substring(open + 1, close).split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
            String lower = v.toLowerCase(Locale.ROOT);
            for (String option : options) {
                String o = option.toLowerCase(Locale.ROOT);
                boolean hit = switch (mode) {
                    case "contains" -> lower.contains(o);
                    case "startswith" -> lower.startsWith(o);
                    case "endswith" -> lower.endsWith(o);
                    case "equals" -> lower.equals(o);
                    case "regex" -> safeRegex(option, v);
                    default -> false;
                };
                if (hit) return true;
            }
            return false;
        }
        return c.equalsIgnoreCase(v);
    }

    static boolean matchesFilter(String filter, String needle, String text) {
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        String n = needle == null ? "" : needle;
        String t = text == null ? "" : text;
        return switch (f) {
            case "contains" -> Arrays.stream(t.split("\\s+")).anyMatch(word -> word.equalsIgnoreCase(n));
            case "contains substring" -> t.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT));
            case "ends with" -> t.endsWith(n);
            case "equals exactly" -> t.equals(n);
            case "equals ignore case" -> t.equalsIgnoreCase(n);
            case "starts with" -> t.startsWith(n);
            case "regex" -> safeRegex(n, t);
            default -> false;
        };
    }

    private static boolean safeRegex(String expression, String value) {
        try {
            return Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(value).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }
}
