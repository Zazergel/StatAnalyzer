package org.zazergel.statanalyzer.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
public class PsqlParser {

    /**
     * Парсит psql aligned output.
     * keyColumnKeywords - список имен колонок. Наличие значения в ЛЮБОЙ из них
     * означает, что это начало новой записи.
     */
    public void parse(InputStream inputStream, List<String> keyColumnKeywords, Consumer<Map<String, String>> rowConsumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8), 65536)) {

            String line;
            List<String> headers = null;
            StringBuilder multilineBuffer = new StringBuilder();
            Map<String, String> currentRecord = null;
            int queryColumnIndex = -1;

            List<Integer> keyColumnIndices = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("----")
                        || (line.startsWith("(") && (line.contains("rows") || line.contains("строк")))) {
                    continue;
                }

                if (line.trim().isEmpty()) {
                    continue;
                }

                if (headers == null) {
                    boolean isHeaderCandidate = line.contains("|");
                    boolean hasKeyword = false;
                    for (String k : keyColumnKeywords) {
                        if (line.toLowerCase().contains(k.toLowerCase())) {
                            hasKeyword = true;
                            break;
                        }
                    }

                    if (isHeaderCandidate && hasKeyword) {
                        log.trace("Found headers line: {}", line);
                        headers = parseHeaders(line);

                        for (int i = 0; i < headers.size(); i++) {
                            String h = headers.get(i);
                            if ((h.contains("query") || h.contains("statement")) && !h.contains("duration")) {
                                queryColumnIndex = i;
                            }
                            for (String keyword : keyColumnKeywords) {
                                if (h.contains(keyword.toLowerCase())) {
                                    keyColumnIndices.add(i);
                                }
                            }
                        }
                        if (queryColumnIndex == -1) {
                            queryColumnIndex = headers.size() - 1;
                        }
                    }
                    continue;
                }

                String[] parts = splitLine(line);
                boolean looksLikeTableLine = parts.length >= headers.size() - 1;

                boolean hasKey = false;
                for (int idx : keyColumnIndices) {
                    if (parts.length > idx && !parts[idx].trim().isEmpty()) {
                        hasKey = true;
                        break;
                    }
                }

                if (looksLikeTableLine && hasKey) {
                    if (currentRecord != null) {
                        finalizeAndEmit(currentRecord, multilineBuffer, headers.get(queryColumnIndex), rowConsumer);
                        multilineBuffer.setLength(0);
                    }

                    currentRecord = mapRow(parts, headers, queryColumnIndex);

                    String initialQueryPart = currentRecord.get(headers.get(queryColumnIndex));
                    if (initialQueryPart != null && !initialQueryPart.isEmpty()) {
                        multilineBuffer.append(initialQueryPart);
                    }
                } else {
                    if (currentRecord != null) {
                        String queryPart;
                        if (looksLikeTableLine && parts.length > queryColumnIndex) {
                            queryPart = parts[queryColumnIndex];
                        } else {
                            queryPart = line;
                            queryPart = queryPart.stripLeading();
                            if (queryPart.startsWith("+")) {
                                queryPart = queryPart.substring(1).stripLeading();
                            }
                            if (queryPart.startsWith("|")) {
                                queryPart = queryPart.substring(1).stripLeading();
                            }
                        }

                        queryPart = queryPart.trim();
                        if (!queryPart.isEmpty()) {
                            if (!multilineBuffer.isEmpty()
                                    && multilineBuffer.charAt(multilineBuffer.length() - 1) != ' ') {
                                multilineBuffer.append(' ');
                            }
                            multilineBuffer.append(queryPart);
                        }
                    }
                }
            }
            if (currentRecord != null) {
                finalizeAndEmit(currentRecord, multilineBuffer, headers.get(queryColumnIndex), rowConsumer);
            }
        }
    }

    private void finalizeAndEmit(Map<String, String> record,
                                 StringBuilder queryBuffer,
                                 String queryKey,
                                 Consumer<Map<String, String>> consumer) {
        if (!queryBuffer.isEmpty()) {
            String q = queryBuffer.toString();
            q = q.replaceAll("\\s*\\+\\s+", " ");
            q = q.replaceAll("^\\+\\s+", " ");
            record.put(queryKey, q.trim());
        }
        consumer.accept(record);
    }

    private List<String> parseHeaders(String line) {
        String[] raw = line.split("\\|");
        List<String> headers = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();

        for (String r : raw) {
            String h = r.trim().toLowerCase();
            if (h.isEmpty()) h = "col";

            if (counts.containsKey(h)) {
                int cnt = counts.get(h) + 1;
                counts.put(h, cnt);
                headers.add(h + "_" + cnt);
            } else {
                counts.put(h, 1);
                headers.add(h);
            }
        }
        return headers;
    }

    private String[] splitLine(String line) {
        String safe = line.replace("||", "##CONCAT##");
        String[] parts = safe.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].replace("##CONCAT##", "||");
        }
        return parts;
    }

    private Map<String, String> mapRow(String[] parts, List<String> headers, int queryColIdx) {
        Map<String, String> row = new HashMap<>();
        int limit = Math.min(parts.length, headers.size());

        for (int i = 0; i < limit; i++) {
            row.put(headers.get(i), parts[i].trim());
        }

        if (parts.length > headers.size()) {
            StringBuilder sb = new StringBuilder();
            String existing = row.get(headers.get(queryColIdx));
            if (existing != null) sb.append(existing);

            for (int i = headers.size(); i < parts.length; i++) {
                if (!sb.isEmpty()) sb.append('|');
                sb.append(parts[i].trim());
            }
            row.put(headers.get(queryColIdx), sb.toString());
        }
        return row;
    }
}
