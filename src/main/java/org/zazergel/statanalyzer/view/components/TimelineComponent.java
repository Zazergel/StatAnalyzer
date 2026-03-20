package org.zazergel.statanalyzer.view.components;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Unit;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Компонент временной шкалы (Timeline).
 * Отображает последовательность снимков (snapshots) в виде горизонтальной ленты.
 * Поддерживает гибридную навигацию: "Grab and Drag" + скроллбар.
 */
public class TimelineComponent extends Div {
    private static final int TOTAL_HEIGHT = 180;
    private static final int ITEM_WIDTH = 80;
    private static final int BAR_AREA_HEIGHT = 100;
    private static final int BAR_MAX_HEIGHT = BAR_AREA_HEIGHT - 25;

    private final Consumer<Map<String, Object>> onSnapshotSelected;
    private final HorizontalLayout container;
    private Div selectedMarker = null;

    /**
     * Конструирует компонент таймлайна с обработчиком выбора snapshot.
     *
     * @param onSnapshotSelected callback при выборе snapshot
     */
    public TimelineComponent(Consumer<Map<String, Object>> onSnapshotSelected) {
        this.onSnapshotSelected = onSnapshotSelected;
        setWidthFull();
        setHeight(TOTAL_HEIGHT + "px");
        getStyle().set("overflow-x", "auto");
        getStyle().set("overflow-y", "hidden");
        getStyle().set("white-space", "nowrap");
        getStyle().set("cursor", "grab");
        getStyle().set("user-select", "none");
        getStyle().set("background-color", "var(--lumo-contrast-5pct)");

        this.container = new HorizontalLayout();
        container.setSpacing(false);
        container.setPadding(false);
        container.getStyle().set("padding-top", "25px");
        container.getStyle().set("padding-left", "10px");
        container.getStyle().set("padding-right", "10px");
        container.setHeightFull();
        container.setDefaultVerticalComponentAlignment(HorizontalLayout.Alignment.END);
        add(container);
    }

    /**
     * Обновляет таймлайн новыми данными снимков.
     *
     * @param snapshots список снимков для отображения
     */
    public void update(List<Map<String, Object>> snapshots) {
        container.removeAll();
        selectedMarker = null;
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        long maxVal = snapshots.stream()
                .mapToLong(s -> Math.max(
                        ((Number) s.get("act_cnt")).longValue(),
                        ((Number) s.get("lock_cnt")).longValue()))
                .max().orElse(10);
        if (maxVal == 0) maxVal = 10;

        for (Map<String, Object> snap : snapshots) {
            container.add(createSnapshotMarker(snap, maxVal));
        }
    }

    /**
     * Создает визуальный маркер для одного snapshot.
     *
     * @param snap      данные snapshot
     * @param globalMax максимальное значение для нормализации высоты столбцов
     * @return визуальный компонент маркера
     */
    private Div createSnapshotMarker(Map<String, Object> snap, long globalMax) {
        long actCnt = ((Number) snap.get("act_cnt")).longValue();
        long lockCnt = ((Number) snap.get("lock_cnt")).longValue();
        long matches = snap.containsKey("search_matches")
                ? ((Number) snap.get("search_matches")).longValue()
                : 0;

        java.sql.Timestamp ts = (java.sql.Timestamp) snap.get("snapshot_timestamp");
        String timeStr = ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String shortTime = ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm"));

        Div itemWrapper = new Div();
        itemWrapper.setWidth(ITEM_WIDTH + "px");
        itemWrapper.setHeightFull();
        itemWrapper.getStyle().set("display", "inline-flex");
        itemWrapper.getStyle().set("flex-direction", "column");
        itemWrapper.getStyle().set("align-items", "center");
        itemWrapper.getStyle().set("justify-content", "flex-end");
        itemWrapper.getStyle().set("flex-shrink", "0");
        itemWrapper.getStyle().set("cursor", "pointer");
        itemWrapper.getStyle().set("margin-right", "8px");
        itemWrapper.getStyle().set("position", "relative");
        itemWrapper.getStyle().set("transition", "background-color 0.2s");
        itemWrapper.getStyle().set("padding-bottom", "5px");

        if (matches > 0) {
            itemWrapper.getStyle().set("background-color", "rgba(255, 235, 59, 0.3)");
        }

        HorizontalLayout barsArea = new HorizontalLayout();
        barsArea.setSpacing(true);
        barsArea.setPadding(false);
        barsArea.setAlignItems(HorizontalLayout.Alignment.END);
        barsArea.setHeight(BAR_AREA_HEIGHT + "px");

        Div actBarCol = createBarColumn(actCnt, globalMax, "var(--lumo-primary-color)");
        Div lockBarCol = createBarColumn(lockCnt, globalMax, "var(--lumo-error-color)");
        barsArea.add(actBarCol, lockBarCol);

        Span timeLabel = new Span(shortTime);
        timeLabel.getStyle().set("font-size", "13px");
        timeLabel.getStyle().set("font-weight", "500");
        timeLabel.getStyle().set("color", "var(--lumo-body-text-color)");
        timeLabel.getStyle().set("margin-top", "8px");
        timeLabel.getStyle().set("flex-shrink", "0");

        itemWrapper.add(barsArea, timeLabel);

        if (matches > 0) {
            Span matchBadge = new Span(String.valueOf(matches));
            matchBadge.getElement().getThemeList().add("badge contrast pill");
            matchBadge.getStyle()
                    .set("position", "absolute")
                    .set("top", "2px")
                    .set("right", "2px")
                    .set("font-size", "10px")
                    .set("min-width", "18px")
                    .set("height", "18px")
                    .set("padding", "2px 4px")
                    .set("background", "var(--lumo-warning-color)")
                    .set("color", "var(--lumo-base-color)")
                    .set("font-weight", "bold");
            itemWrapper.add(matchBadge);
        }

        String tooltip = String.format("Time: %s\nActive: %d\nLocks: %d", timeStr, actCnt, lockCnt);
        if (matches > 0) tooltip += "\nMatches: " + matches;
        itemWrapper.setTitle(tooltip);

        itemWrapper.addClickListener(e -> {
            if (selectedMarker != null) {
                selectedMarker.getStyle().remove("background-color");
                selectedMarker.getStyle().remove("border-bottom");
            }
            selectedMarker = itemWrapper;
            if (matches > 0) {
                itemWrapper.getStyle().set("background-color", "rgba(255, 235, 59, 0.5)");
            } else {
                itemWrapper.getStyle().set("background-color", "var(--lumo-contrast-10pct)");
            }
            itemWrapper.getStyle().set("border-bottom", "4px solid var(--lumo-primary-color)");
            onSnapshotSelected.accept(snap);
        });

        return itemWrapper;
    }

    /**
     * Создает столбец графика с числовой меткой.
     *
     * @param value     значение для отображения
     * @param globalMax максимум для нормализации
     * @param color     цвет столбца
     * @return визуальный компонент столбца
     */
    private Div createBarColumn(long value, long globalMax, String color) {
        VerticalLayout col = new VerticalLayout();
        col.setPadding(false);
        col.setSpacing(false);
        col.setAlignItems(VerticalLayout.Alignment.CENTER);
        col.setJustifyContentMode(VerticalLayout.JustifyContentMode.END);
        col.setWidth("24px");
        col.setHeightFull();
        col.getStyle().set("overflow", "visible");

        if (value > 0) {
            Span valLabel = new Span(String.valueOf(value));
            valLabel.getStyle().set("font-size", "11px");
            valLabel.getStyle().set("font-weight", "bold");
            valLabel.getStyle().set("color", "var(--lumo-secondary-text-color)");
            valLabel.getStyle().set("margin-bottom", "2px");
            valLabel.getStyle().set("line-height", "1");
            valLabel.getStyle().set("white-space", "nowrap");

            Div bar = new Div();
            bar.setWidth("18px");
            double ratio = (double) value / globalMax;
            int h = (int) (ratio * BAR_MAX_HEIGHT);
            if (h < 6) h = 6;
            if (h > BAR_MAX_HEIGHT) h = BAR_MAX_HEIGHT;
            bar.setHeight(h, Unit.PIXELS);
            bar.getStyle().set("background-color", color);
            bar.getStyle().set("border-radius", "3px 3px 0 0");
            bar.getStyle().set("opacity", "0.9");
            bar.getStyle().set("flex-shrink", "0");

            col.add(valLabel, bar);
        } else {
            Div placeholder = new Div();
            placeholder.setHeight("1px");
            placeholder.setWidth("18px");
            placeholder.getStyle().set("background-color", "transparent");
            col.add(placeholder);
        }

        Div wrapper = new Div();
        wrapper.add(col);
        return wrapper;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        getElement().executeJs("""
                    const slider = this;
                    let isDown = false;
                    let startX;
                    let scrollLeft;
                    let isDragging = false;
                    slider.addEventListener('mousedown', (e) => {
                        if (e.offsetY > slider.clientHeight - 15) return;
                        isDown = true;
                        isDragging = false;
                        slider.style.cursor = 'grabbing';
                        startX = e.pageX - slider.offsetLeft;
                        scrollLeft = slider.scrollLeft;
                    });
                    slider.addEventListener('mouseleave', () => {
                        isDown = false;
                        slider.style.cursor = 'grab';
                    });
                    slider.addEventListener('mouseup', (e) => {
                        isDown = false;
                        slider.style.cursor = 'grab';
                        if(isDragging) {
                            e.stopPropagation();
                        }
                    });
                    slider.addEventListener('click', (e) => {
                        if(isDragging) {
                            e.stopImmediatePropagation();
                            e.preventDefault();
                            isDragging = false;
                        }
                    }, true);
                    slider.addEventListener('mousemove', (e) => {
                        if(!isDown) return;
                        const x = e.pageX - slider.offsetLeft;
                        const walk = (x - startX);
                        if(Math.abs(walk) > 5) {
                            isDragging = true;
                            e.preventDefault();
                            slider.scrollLeft = scrollLeft - walk;
                        }
                    });
                """);
    }
}
