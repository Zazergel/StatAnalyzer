package org.zazergel.statanalyzer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Сервис для получения подсказок (tooltip) из внешнего JSON файла.
 */
@Slf4j
@Service
public class TooltipService {

    private final Map<String, String> messages = new HashMap<>();

    public TooltipService(ObjectMapper objectMapper) {
        loadMessages(objectMapper);
    }

    private void loadMessages(ObjectMapper objectMapper) {
        try {
            File externalFile = new File("messages.json");
            if (externalFile.exists()) {
                log.info("Загрузка подсказок из внешнего файла messages.json");
                messages.putAll(objectMapper.readValue(externalFile, new TypeReference<Map<String, String>>() {}));
            } else {
                log.info("Внешний файл не найден, загрузка messages.json из ресурсов");
                try (InputStream is = getClass().getResourceAsStream("/messages.json")) {
                    if (is != null) {
                        messages.putAll(objectMapper.readValue(is, new TypeReference<Map<String, String>>() {}));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Ошибка при загрузке messages.json", e);
        }
    }

    public String getWaitEventTooltip(String event) {
        if (event == null) return "";
        String key = "tooltip.wait." + event;
        return getMessage(key, event, "tooltip.wait.default");
    }

    public String getStateTooltip(String state) {
        if (state == null) return "";
        String key = "tooltip.state." + state.replace(" ", "_");
        return getMessage(key, state, "tooltip.state.default");
    }

    public String getLockTooltip(String mode) {
        if (mode == null) return "";
        String key = "tooltip.lock." + mode;
        return getMessage(key, mode, "tooltip.lock.default");
    }

    private String getMessage(String key, String param, String defaultKey) {
        String msg = messages.get(key);
        if (msg != null) return msg;

        String defaultMsg = messages.get(defaultKey);
        if (defaultMsg != null) {
            return defaultMsg.replace("{0}", param);
        }
        return "Info: " + param;
    }
}
