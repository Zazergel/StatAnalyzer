package org.zazergel.statanalyzer.view.components;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public class ChartDialog extends Dialog {

    public ChartDialog(List<Map<String, Object>> data) {
        setHeaderTitle("Гистограмма активности");
        setWidth("95vw");
        setHeight("80vh");

        HorizontalLayout container = new HorizontalLayout();
        container.setSizeFull();
        container.setSpacing(false);
        container.getStyle().set("overflow-x", "auto").set("overflow-y", "hidden");
        container.setAlignItems(FlexComponent.Alignment.END);

        long maxVal = 1;
        for (Map<String, Object> r : data) {
            maxVal = Math.max(maxVal, Math.max(((Number) r.get("act_cnt")).longValue(), ((Number) r.get("lock_cnt")).longValue()));
        }

        for (Map<String, Object> r : data) container.add(createColumn(r, maxVal));

        add(container);
        getFooter().add(new Button("Закрыть", e -> close()));
    }

    private VerticalLayout createColumn(Map<String, Object> row, long maxVal) {
        long act = ((Number) row.get("act_cnt")).longValue();
        long lock = ((Number) row.get("lock_cnt")).longValue();
        Timestamp ts = (Timestamp) row.get("snapshot_timestamp");
        String timeStr = ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm"));

        VerticalLayout col = new VerticalLayout();
        col.setPadding(false);
        col.setSpacing(false);
        col.setWidth("32px");
        col.setHeight("100%");
        col.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        col.setAlignItems(FlexComponent.Alignment.CENTER);
        col.getStyle().set("flex-shrink", "0").set("position", "relative");

        if (lock > 0) {
            Span val = new Span(String.valueOf(lock));
            val.getStyle().set("font-size", "9px").set("color", "white").set("background-color", "rgba(235, 64, 52, 0.8)")
                    .set("border-radius", "3px").set("padding", "0 2px").set("margin-bottom", "1px");
            col.add(val);
        }
        if (act > 0) {
            Span val = new Span(String.valueOf(act));
            val.getStyle().set("font-size", "9px").set("color", "white").set("background-color", "rgba(0, 106, 245, 0.7)")
                    .set("border-radius", "3px").set("padding", "0 2px").set("margin-bottom", "1px");
            col.add(val);
        }

        HorizontalLayout barsRow = new HorizontalLayout();
        barsRow.setAlignItems(FlexComponent.Alignment.END);
        barsRow.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        barsRow.setHeight("100%");
        barsRow.setWidth("100%");
        barsRow.setSpacing(false);
        barsRow.getStyle().set("gap", "2px");

        if (act > 0) {
            Div b = new Div();
            b.setWidth("10px");
            float h = (float) act / maxVal * 75f;
            b.setHeight(Math.max(h, 2) + "%");
            b.getStyle().set("background-color", "var(--lumo-primary-color)").set("opacity", "0.7");
            b.getElement().setAttribute("title", "Queries: " + act);
            barsRow.add(b);
        } else {
            Div spacer = new Div();
            spacer.setWidth("10px");
            barsRow.add(spacer);
        }

        if (lock > 0) {
            Div b = new Div();
            b.setWidth("10px");
            float h = (float) lock / maxVal * 75f;
            b.setHeight(Math.max(h, 2) + "%");
            b.getStyle().set("background-color", "var(--lumo-error-color)");
            b.getElement().setAttribute("title", "Locks: " + lock);
            barsRow.add(b);
        }

        col.add(barsRow);

        String[] parts = timeStr.split(":");
        VerticalLayout timeBox = new VerticalLayout();
        timeBox.setSpacing(false);
        timeBox.setPadding(false);
        timeBox.setAlignItems(FlexComponent.Alignment.CENTER);
        Span hh = new Span(parts[0]);
        hh.addClassNames(LumoUtility.FontSize.XSMALL);
        hh.getStyle().set("font-weight", "bold");
        Span mm = new Span(parts[1]);
        mm.addClassNames(LumoUtility.FontSize.XSMALL);
        mm.getStyle().set("color", "var(--lumo-secondary-text-color)");
        timeBox.add(hh, mm);
        col.add(timeBox);

        return col;
    }
}
