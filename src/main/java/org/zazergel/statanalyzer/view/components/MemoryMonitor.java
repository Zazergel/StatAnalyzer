package org.zazergel.statanalyzer.view.components;


import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Изолированный UI-компонент для мониторинга состояния оперативной памяти.
 * Управляет собственным фоновым потоком и блокирует переданные зависимые компоненты.
 */
public class MemoryMonitor extends Span {

    private ScheduledExecutorService scheduler;
    private final Button chartBtn;
    private boolean memoryWarningShown = false;

    public MemoryMonitor(Button chartBtn) {
        this.chartBtn = chartBtn;

        getElement().getThemeList().add("badge");
        getElement().getStyle().set("flex-shrink", "0");
        setText("RAM: --%");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> performMemoryCheck(ui), 2, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    private void performMemoryCheck(UI ui) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        long usedMemory = allocatedMemory - freeMemory;
        double usedPercentage = ((double) usedMemory / maxMemory) * 100.0;

        long maxMb = maxMemory / (1024 * 1024);
        long usedMb = usedMemory / (1024 * 1024);

        ui.access(() -> updateMemoryUiState(usedMb, maxMb, usedPercentage, ui));
    }

    private void updateMemoryUiState(long usedMb, long maxMb, double usedPercentage, UI ui) {
        updateBadgeVisuals(usedMb, maxMb, usedPercentage);

        boolean isMemoryCritical = usedPercentage >= 85.0;
        manageDependentComponents(isMemoryCritical);
        handleWarningNotification(isMemoryCritical);

        ui.push();
    }

    private void updateBadgeVisuals(long usedMb, long maxMb, double usedPercentage) {
        setText(String.format("RAM: %d / %d MB (%.0f%%)", usedMb, maxMb, usedPercentage));
        Arrays.asList("success", "error", "contrast").forEach(getElement().getThemeList()::remove);

        if (usedPercentage < 70) {
            getElement().getThemeList().add("success");
        } else if (usedPercentage >= 70 && usedPercentage < 85) {
            getElement().getThemeList().add("contrast");
        } else {
            getElement().getThemeList().add("error");
        }
    }

    private void manageDependentComponents(boolean isMemoryCritical) {
        if (isMemoryCritical) {
            if (chartBtn.isEnabled()) {
                chartBtn.setEnabled(false);
                chartBtn.getElement().setAttribute("title", "Недостаточно памяти для построения графика. Пожалуйста, очистите данные.");
            }
        } else {
            if (!chartBtn.isEnabled()) {
                chartBtn.setEnabled(true);
                chartBtn.getElement().removeAttribute("title");
            }
        }
    }

    private void handleWarningNotification(boolean isMemoryCritical) {
        if (isMemoryCritical) {
            if (!memoryWarningShown) {
                Notification.show(
                        "Внимание: Заканчивается оперативная память! Приложение может зависнуть. " +
                                "Функция построения графиков временно отключена.",
                        5000, Notification.Position.TOP_END
                ).addThemeVariants(NotificationVariant.LUMO_ERROR);
                memoryWarningShown = true;
            }
        } else {
            memoryWarningShown = false;
        }
    }
}
