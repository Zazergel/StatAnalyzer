package org.zazergel.statanalyzer.util;

import java.util.regex.Pattern;

public class SqlUtils {

    private static final String[] KEYWORDS = {
            "SELECT", "FROM", "WHERE", "AND", "OR", "LEFT JOIN", "INNER JOIN", "JOIN",
            "ON", "GROUP BY", "ORDER BY", "LIMIT", "CASE", "WHEN", "THEN", "ELSE", "END",
            "IN", "NOT", "NULL", "INSERT INTO", "UPDATE", "DELETE", "VALUES", "SET", "HAVING",
            "DISTINCT", "ALTER", "TRUNCATE", "DROP", "CREATE", "COPY"
    };

    public static String prettifySql(String sql) {
        if (sql == null || sql.isBlank()) return "";
        String s = sql.replaceAll("\\s+", " ");
        s = s.replaceAll("(?i)(SELECT|FROM|WHERE|GROUP BY|ORDER BY|LIMIT|HAVING|WITH|INSERT INTO|UPDATE|DELETE)", "\n$1");
        s = s.replaceAll("(?i)(LEFT JOIN|INNER JOIN|RIGHT JOIN|JOIN|OUTER JOIN)", "\n  $1");
        s = s.replaceAll("(?i)( AND | OR )", "\n  $1");
        return s.trim();
    }

    public static String highlightHtml(String text, boolean sqlSyntax, String searchTerm) {
        if (text == null || text.isEmpty()) return "";

        String highlighted = text;
        if (searchTerm != null && !searchTerm.isBlank()) {
            String pattern = "(?i)(" + Pattern.quote(searchTerm.trim()) + ")";
            highlighted = highlighted.replaceAll(pattern, "<<<MARK_START>>>$1<<<MARK_END>>>");
        }

        String safe = highlighted
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        safe = safe.replace("&lt;&lt;&lt;MARK_START&gt;&gt;&gt;",
                "<mark style='background-color: var(--lumo-warning-color-50pct, rgba(255, 221, 0, 0.5)); color: var(--lumo-body-text-color); padding: 0 2px; border-radius: 2px;'>");
        safe = safe.replace("&lt;&lt;&lt;MARK_END&gt;&gt;&gt;", "</mark>");

        if (sqlSyntax) {
            if (isModifyingQuery(text)) {
                safe = "✏️ " + safe;
            }
            for (String kw : KEYWORDS) {
                safe = safe.replaceAll("(?i)\\b" + kw + "\\b",
                        "<span style='color: var(--lumo-primary-text-color); font-weight: bold;'>" + kw + "</span>");
            }
        }

        return safe;
    }

    private static boolean isModifyingQuery(String sql) {
        if (sql == null) return false;
        String s = sql.trim().toUpperCase();
        return s.startsWith("INSERT") || s.startsWith("UPDATE") || s.startsWith("DELETE") ||
                s.startsWith("TRUNCATE") || s.startsWith("ALTER") || s.startsWith("DROP");
    }
}
