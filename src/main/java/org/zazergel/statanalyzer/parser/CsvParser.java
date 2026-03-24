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

/**
 * CSV-парсер для файлов выгрузки.
 * Поддерживает поля в кавычках, экранирование кавычек (""), пустые значения.
 * Заголовки приводятся к нижнему регистру без модификации символов (underscore сохраняется).
 */
@Slf4j
@Component
public class CsvParser {

    /**
     * Парсит CSV поток (UTF-8) и отдаёт каждую строку в виде Map(header->value).
     *
     * @param inputStream входной поток CSV
     * @param rowConsumer обработчик строк
     * @throws IOException ошибка чтения
     */
    public void parse(InputStream inputStream, Consumer<Map<String, String>> rowConsumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8), 65536)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return;
            }

            List<String> headers = parseCsvLine(headerLine).stream()
                    .map(s -> s == null ? "" : s.trim().toLowerCase())
                    .toList();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                List<String> fields = parseCsvLine(line);
                Map<String, String> row = getStringStringMap(headers, fields);

                rowConsumer.accept(row);
            }
        }
    }

    private Map<String, String> getStringStringMap(List<String> headers, List<String> fields) {
        Map<String, String> row = new HashMap<>(headers.size() * 2);

        int limit = Math.min(headers.size(), fields.size());
        for (int i = 0; i < limit; i++) {
            String key = headers.get(i);
            if (key == null || key.isBlank()) {
                continue;
            }

            String v = fields.get(i);
            if (v != null) {
                v = v.trim();
                if (v.isEmpty()) {
                    v = null;
                }
            }
            row.put(key, v);
        }
        return row;
    }

    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder(line.length());
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(ch);
                }
            } else {
                if (ch == ',') {
                    out.add(sb.toString());
                    sb.setLength(0);
                } else if (ch == '"') {
                    inQuotes = true;
                } else {
                    sb.append(ch);
                }
            }
        }

        out.add(sb.toString());
        return out;
    }
}
