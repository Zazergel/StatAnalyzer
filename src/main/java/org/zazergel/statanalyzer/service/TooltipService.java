package org.zazergel.statanalyzer.service;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

@Service
public class TooltipService {

    private final MessageSource messageSource;

    public TooltipService(MessageSource messageSource) {
        this.messageSource = messageSource;
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
        try {
            return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return messageSource.getMessage(defaultKey, new Object[]{param}, "Info: " + param, LocaleContextHolder.getLocale());
        }
    }
}
