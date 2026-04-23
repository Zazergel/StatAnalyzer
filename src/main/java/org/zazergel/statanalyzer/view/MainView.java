package org.zazergel.statanalyzer.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dependency.Uses;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.tabs.TabsVariant;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.dom.ThemeList;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.Lumo;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.NonNull;
import org.zazergel.statanalyzer.service.IngestService;
import org.zazergel.statanalyzer.service.StatisticsService;
import org.zazergel.statanalyzer.service.TooltipService;
import org.zazergel.statanalyzer.util.SqlUtils;
import org.zazergel.statanalyzer.view.components.ChartDialog;
import org.zazergel.statanalyzer.view.components.TimelineComponent;
import org.zazergel.statanalyzer.view.components.UploadToolbar;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Route("")
@Uses(Icon.class)
@Uses(Span.class)
public class MainView extends VerticalLayout {

    private final StatisticsService statisticsService;
    private final TooltipService tooltipService;

    private final UploadToolbar toolbar;
    private final TimelineComponent timeline;

    private final Grid<Map<String, Object>> activityGrid = new Grid<>();
    private final Grid<Map<String, Object>> locksGrid = new Grid<>();
    private final Grid<Map<String, Object>> rootCauseGrid = new Grid<>();

    private final Tab tabActivity = new Tab("Activity");
    private final Tab tabLocks = new Tab("Locks");
    private final Tab tabAnalysis = new Tab("Root Cause Analysis");
    private final Tabs tabs = new Tabs(tabActivity, tabLocks, tabAnalysis);

    private final HorizontalLayout statsPanel = new HorizontalLayout();
    private final Button columnSettingsBtn = new Button("Настройки",
            new Icon(com.vaadin.flow.component.icon.VaadinIcon.COG));
    private Integer currentSnapshotId = null;
    private Map<String, Object> lastLoadedSnapshot = null;
    private String currentSearchFilter = "";

    private List<Map<String, Object>> currentActivityData = List.of();
    private List<Map<String, Object>> currentLocksData = List.of();

    private String activeStateFilter = null;
    private String activeLockFilter = null;

    private int highlightThresholdMillis = 60000;

    public MainView(IngestService ingestService,
                    StatisticsService statisticsService,
                    TooltipService tooltipService) {
        this.statisticsService = statisticsService;
        this.tooltipService = tooltipService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setMargin(false);
        getStyle().set("overflow", "hidden");

        configureGrids();

        this.toolbar = new UploadToolbar(
                ingestService,
                this::refreshAll,
                this::clearData,
                () -> new ChartDialog(statisticsService.getChartData()).open(),
                this::toggleTheme,
                this::onSearch,
                this::onTimezoneChanged
        );

        this.timeline = new TimelineComponent(this::loadSnapshotDetails);

        VerticalLayout workspace = createWorkspace();
        add(toolbar, workspace);
        expand(workspace);

        refreshAll();
    }

    private void clearData() {
        statisticsService.clearAllData();
        refreshAll();
    }

    private void toggleTheme() {
        ThemeList themeList = UI.getCurrent().getElement().getThemeList();
        if (themeList.contains(Lumo.DARK)) {
            themeList.remove(Lumo.DARK);
        } else {
            themeList.add(Lumo.DARK);
        }
    }

    private void onSearch(String filter) {
        this.currentSearchFilter = filter;
        refreshAll();
    }

    private void onTimezoneChanged(Integer offset) {
        if (activityGrid.isVisible()) {
            updateActivityGridItems();
        }
        if (rootCauseGrid.isVisible()) {
            rootCauseGrid.getDataProvider().refreshAll();
        }
    }

    private void refreshAll() {
        List<Map<String, Object>> snapshots = statisticsService.getSnapshots(currentSearchFilter);
        timeline.update(snapshots);

        boolean isSearchActive = currentSearchFilter != null && !currentSearchFilter.isBlank();

        boolean hasAnyData = isSearchActive || (snapshots != null && !snapshots.isEmpty());

        toolbar.setDataLoadedState(hasAnyData);

        if (lastLoadedSnapshot != null && Objects.requireNonNull(snapshots).stream().anyMatch(s -> s.get("id").equals(lastLoadedSnapshot.get("id")))) {
            loadSnapshotDetails(lastLoadedSnapshot);
        } else if (!(snapshots != null && snapshots.isEmpty())) {
            loadSnapshotDetails(Objects.requireNonNull(snapshots).getFirst());
        } else {
            this.currentSnapshotId = null;
            this.activeStateFilter = null;
            this.activeLockFilter = null;

            activityGrid.setItems(List.of());
            locksGrid.setItems(List.of());
            rootCauseGrid.setItems(List.of());

            tabActivity.setLabel("Activity (0)");
            tabLocks.setLabel("Locks (0)");
            tabAnalysis.setLabel("Root Cause Analysis (0)");

            currentActivityData = List.of();
            currentLocksData = List.of();

            if (!hasAnyData) {
                manageTabsVisibility(true, false, false);
            } else {
                manageTabsVisibility(true, true, true);
            }

            updateGridVisibility();
            updateStatsPanel();
        }
    }

    private VerticalLayout createWorkspace() {
        tabs.addThemeVariants(TabsVariant.LUMO_SMALL);
        tabs.addSelectedChangeListener(e -> {
            updateGridVisibility();
            updateStatsPanel();
        });

        statsPanel.setSpacing(true);
        statsPanel.setAlignItems(Alignment.CENTER);

        columnSettingsBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY, com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL);
        columnSettingsBtn.addClickListener(e -> openColumnSettingsDialog());

        HorizontalLayout rightControls = new HorizontalLayout(columnSettingsBtn, statsPanel);
        rightControls.setAlignItems(Alignment.CENTER);
        rightControls.setSpacing(true);

        HorizontalLayout header = new HorizontalLayout(tabs, rightControls);
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        VerticalLayout content = new VerticalLayout(activityGrid, locksGrid, rootCauseGrid);
        content.setSizeFull();
        content.setPadding(false);
        content.setSpacing(false);

        VerticalLayout topPane = new VerticalLayout(header, content);
        topPane.setSizeFull();
        topPane.setPadding(true);
        topPane.getStyle().set("padding-bottom", "0");

        VerticalLayout workspace = new VerticalLayout(topPane, timeline);
        workspace.setSizeFull();
        workspace.setPadding(false);
        workspace.setSpacing(false);
        workspace.expand(topPane);

        return workspace;
    }

    private void updateGridVisibility() {
        boolean isActivity = tabs.getSelectedTab().equals(tabActivity);
        activityGrid.setVisible(isActivity);
        locksGrid.setVisible(tabs.getSelectedTab().equals(tabLocks));
        rootCauseGrid.setVisible(tabs.getSelectedTab().equals(tabAnalysis));
        columnSettingsBtn.setVisible(isActivity);
    }

    private void loadSnapshotDetails(Map<String, Object> snapshot) {
        if (snapshot == null) return;
        Integer snapId = (Integer) snapshot.get("id");

        if (!Objects.equals(snapId, this.currentSnapshotId)) {
            this.activeStateFilter = null;
            this.activeLockFilter = null;
        }

        this.currentSnapshotId = snapId;
        this.lastLoadedSnapshot = snapshot;
        long lockCount = ((Number) snapshot.get("lock_cnt")).longValue();

        this.currentActivityData = statisticsService.getActivity(snapId, currentSearchFilter);
        this.currentLocksData = lockCount > 0 ? statisticsService.getLocks(snapId, currentSearchFilter) : List.of();
        List<Map<String, Object>> rootCauseData = lockCount > 0 ? statisticsService.getRootCause(snapId, currentSearchFilter) : List.of();

        tabActivity.setLabel("Activity (" + currentActivityData.size() + ")");
        tabLocks.setLabel("Locks (" + currentLocksData.size() + ")");
        tabAnalysis.setLabel("Root Cause Analysis (" + rootCauseData.size() + ")");

        manageTabsVisibility(!currentActivityData.isEmpty(), !currentLocksData.isEmpty(), !rootCauseData.isEmpty());

        updateActivityGridItems();
        updateLocksGridItems();

        rootCauseGrid.setItems(rootCauseData);

        updateGridVisibility();
        updateStatsPanel();
    }

    private void manageTabsVisibility(boolean hasAct, boolean hasLocks, boolean hasRoot) {
        if (currentSearchFilter != null && !currentSearchFilter.isBlank()) {
            tabActivity.setVisible(hasAct);
            tabLocks.setVisible(hasLocks);
            tabAnalysis.setVisible(hasRoot);
        } else {
            tabActivity.setVisible(true);
            tabLocks.setVisible(hasLocks);
            tabAnalysis.setVisible(hasLocks);
        }

        if (!tabs.getSelectedTab().isVisible()) {
            if (tabActivity.isVisible()) tabs.setSelectedTab(tabActivity);
            else if (tabLocks.isVisible()) tabs.setSelectedTab(tabLocks);
        }
    }

    private void updateStatsPanel() {
        statsPanel.removeAll();
        if (tabs.getSelectedTab().equals(tabActivity)) {
            Map<String, Long> counts = currentActivityData.stream()
                    .collect(Collectors.groupingBy(r -> (String) r.getOrDefault("state", "unknown"), Collectors.counting()));

            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> addBadge(
                            e.getKey(),
                            e.getValue(),
                            getThemeForState(e.getKey()),
                            () -> handleStateFilter(e.getKey()),
                            e.getKey().equals(activeStateFilter)
                    ));

        } else if (tabs.getSelectedTab().equals(tabLocks)) {
            Map<String, Long> counts = currentLocksData.stream()
                    .map(r -> (String) r.getOrDefault("locking_mode", ""))
                    .filter(s -> s != null && !s.isBlank())
                    .flatMap(s -> Arrays.stream(s.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

            counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> addBadge(
                            e.getKey(),
                            e.getValue(),
                            getThemeForLock(e.getKey()),
                            () -> handleLockFilter(e.getKey()),
                            e.getKey().equals(activeLockFilter)
                    ));
        }
    }

    private void addBadge(String label, Long count, String theme, Runnable onClick, boolean isActive) {
        Span badge = new Span(label + ": " + count);
        applyBadgeStyle(badge, theme);
        badge.getStyle().set("cursor", "pointer");

        if (isActive) {
            badge.getStyle().set("box-shadow", "0 0 0 2px var(--lumo-primary-color)");
        }

        badge.addClickListener(e -> onClick.run());
        statsPanel.add(badge);
    }

    private void applyBadgeStyle(Span badge, String theme) {
        badge.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("box-sizing", "border-box")
                .set("padding", "0.125em 0.25em")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("font-size", "var(--lumo-font-size-xs)")
                .set("line-height", "1")
                .set("font-weight", "500");
        switch (theme == null ? "" : theme) {
            case "success" -> badge.getStyle()
                    .set("background-color", "var(--lumo-success-color-10pct)")
                    .set("color", "var(--lumo-success-text-color)");
            case "error" -> badge.getStyle()
                    .set("background-color", "var(--lumo-error-color-10pct)")
                    .set("color", "var(--lumo-error-text-color)");
            case "contrast" -> badge.getStyle()
                    .set("background-color", "var(--lumo-contrast-20pct)")
                    .set("color", "var(--lumo-body-text-color)");
            default -> badge.getStyle()
                    .set("background-color", "var(--lumo-contrast-10pct)")
                    .set("color", "var(--lumo-body-text-color)");
        }
    }

    private void handleStateFilter(String state) {
        if (state.equals(activeStateFilter)) {
            activeStateFilter = null;
        } else {
            activeStateFilter = state;
        }
        updateActivityGridItems();
        updateStatsPanel();
    }

    private void handleLockFilter(String mode) {
        if (mode.equals(activeLockFilter)) {
            activeLockFilter = null;
        } else {
            activeLockFilter = mode;
        }
        updateLocksGridItems();
        updateStatsPanel();
    }

    private void updateActivityGridItems() {
        if (currentActivityData == null) return;

        List<Map<String, Object>> itemsToSet;
        if (activeStateFilter == null) {
            // Create a new list to force grid re-render
            itemsToSet = new ArrayList<>(currentActivityData);
        } else {
            itemsToSet = currentActivityData.stream()
                    .filter(row -> activeStateFilter.equals(row.get("state")))
                    .collect(Collectors.toList());
        }
        activityGrid.setItems(itemsToSet);
        applyHighlightStyles();
    }

    private void updateLocksGridItems() {
        if (currentLocksData == null) return;

        if (activeLockFilter == null) {
            locksGrid.setItems(currentLocksData);
        } else {
            List<Map<String, Object>> filtered = currentLocksData.stream()
                    .filter(row -> {
                        String modes = (String) row.get("locking_mode");
                        if (modes == null) return false;
                        return Arrays.stream(modes.split(","))
                                .map(String::trim)
                                .anyMatch(s -> s.equals(activeLockFilter));
                    })
                    .toList();
            locksGrid.setItems(filtered);
        }
    }

    private String getThemeForState(String state) {
        if (state == null) return "";
        String s = state.toLowerCase();

        if (s.contains("idle in transaction")) return "error";
        if (s.contains("idle")) return "contrast";
        if (s.contains("active")) return "success";
        return "";
    }

    private String getThemeForLock(String mode) {
        if (mode == null) return "";
        String m = mode.toLowerCase();
        if (m.contains("exclusive")) return "error";
        if (m.contains("share")) return "contrast";
        return "";
    }

    private void configureGrids() {
        configureActivityGrid();
        configureLocksGrid();
        configureRootCauseGrid();
    }

    private void configureActivityGrid() {
        activityGrid.setSizeFull();
        activityGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);

        activityGrid.addColumn(createSimpleHighlightRenderer("pid"))
                .setHeader("PID").setKey("PID")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(new ComponentRenderer<>(row -> {
                    String state = (String) row.get("state");
                    if (state == null || state.isBlank()) return new Span();
                    Span badge = new Span(state);
                    applyBadgeStyle(badge, getThemeForState(state));
                    badge.getElement().setAttribute("title", tooltipService.getStateTooltip(state));
                    return badge;
                })).setHeader("State").setKey("State")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(r -> formatTime(r.get("xact_start")))
                .setHeader("Tx Start").setKey("Tx Start")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(r -> extractAppParam((String) r.get("application_name"), "Time"))
                .setHeader("Java Start").setKey("Java Start")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(new ComponentRenderer<>(row -> {
                    String val = extractAppParam((String) row.get("application_name"), "ID");
                    Span span = new Span();
                    if (val == null || val.isBlank()) return span;

                    if (currentSearchFilter != null && !currentSearchFilter.isBlank() && val.toLowerCase().contains(currentSearchFilter.toLowerCase())) {
                        String safeVal = val.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                        String safeFilter = currentSearchFilter.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                        String regex = "(?i)(" + java.util.regex.Pattern.quote(safeFilter) + ")";
                        String highlighted = safeVal.replaceAll(regex, "<mark>$1</mark>");
                        span.getElement().setProperty("innerHTML", highlighted);
                    } else {
                        span.setText(val);
                    }
                    return span;
                })).setHeader("Thread ID").setKey("Thread ID")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(createSimpleHighlightRenderer("usename"))
                .setHeader("User").setKey("User")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(createSimpleHighlightRenderer("application_name"))
                .setHeader("App").setKey("App")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(new ComponentRenderer<>(row -> {
                    String waitEvent = (String) row.get("wait_event");
                    Span badge = new Span();
                    if (waitEvent == null || waitEvent.isBlank()) return badge;

                    applyBadgeStyle(badge, "contrast");
                    badge.getElement().setAttribute("title", tooltipService.getWaitEventTooltip(waitEvent));

                    if (currentSearchFilter != null && !currentSearchFilter.isBlank() && waitEvent.toLowerCase().contains(currentSearchFilter.toLowerCase())) {
                        String safeVal = waitEvent.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                        String safeFilter = currentSearchFilter.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                        String regex = "(?i)(" + java.util.regex.Pattern.quote(safeFilter) + ")";
                        String highlighted = safeVal.replaceAll(regex, "<mark>$1</mark>");
                        badge.getElement().setProperty("innerHTML", highlighted);
                    } else {
                        badge.setText(waitEvent);
                    }
                    return badge;
                })).setHeader("Wait Event").setKey("Wait Event")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        activityGrid.addColumn(createHighlightRenderer("query"))
                .setHeader("Query").setKey("Query")
                .setAutoWidth(true).setFlexGrow(1).setResizable(true);

        activityGrid.addItemDoubleClickListener(e -> showQueryDialog((String) e.getItem().get("query"), null));
    }

    private void configureLocksGrid() {
        locksGrid.setSizeFull();
        locksGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);

        locksGrid.addColumn(createSimpleHighlightRenderer("waiting_pid")).setHeader("Waiting PID")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);
        locksGrid.addColumn(createSimpleHighlightRenderer("locking_pid")).setHeader("Locking PID")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        locksGrid.addColumn(new ComponentRenderer<>(row -> {
                    String rawModes = (String) row.get("locking_mode");
                    if (rawModes == null || rawModes.isBlank()) return new Span();
                    Div container = new Div();
                    container.getStyle().set("display", "flex");
                    container.getStyle().set("flex-wrap", "wrap");
                    container.getStyle().set("gap", "4px");

                    for (String mode : rawModes.split(",")) {
                        mode = mode.trim();
                        if (mode.isEmpty()) continue;
                        Span badge = new Span(mode);
                        applyBadgeStyle(badge, getThemeForLock(mode));
                        badge.getElement().setAttribute("title", tooltipService.getLockTooltip(mode));
                        container.add(badge);
                    }
                    return container;
                })).setHeader("Lock Mode")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);

        locksGrid.addColumn(createHighlightRenderer("waiting_query")).setHeader("Waiting Query")
                .setFlexGrow(1).setResizable(true);
        locksGrid.addColumn(createHighlightRenderer("locking_query")).setHeader("Locking Query")
                .setFlexGrow(1).setResizable(true);

        locksGrid.addItemDoubleClickListener(e -> showQueryDialog((String) e.getItem().get("waiting_query"), (String) e.getItem().get("locking_query")));
    }

    private void configureRootCauseGrid() {
        rootCauseGrid.setSizeFull();
        rootCauseGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);

        rootCauseGrid.addColumn(createSimpleHighlightRenderer("locking_pid")).setHeader("Root PID")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);
        rootCauseGrid.addColumn(r -> r.get("cnt")).setHeader("Victims")
                .setAutoWidth(true).setFlexGrow(0).setResizable(true);
        rootCauseGrid.addColumn(createHighlightRenderer("query")).setHeader("Root Query")
                .setFlexGrow(1).setResizable(true);

        rootCauseGrid.setItemDetailsRenderer(new ComponentRenderer<>(() -> {
            VerticalLayout d = new VerticalLayout();
            d.setPadding(true);
            d.getStyle().set("background-color", "var(--lumo-contrast-5pct)");
            return d;
        }, (layout, rootRow) -> {
            layout.removeAll();
            Integer pid = (Integer) rootRow.get("locking_pid");
            if (pid != null && currentSnapshotId != null) {
                List<Map<String, Object>> v = statisticsService.getVictims(pid, currentSnapshotId, currentSearchFilter);
                if (v.isEmpty()) layout.add(new Span("Нет данных"));
                else {
                    Grid<Map<String, Object>> g = new Grid<>();
                    g.addThemeVariants(GridVariant.LUMO_COMPACT);

                    g.addColumn(createSimpleHighlightRenderer("waiting_pid")).setHeader("PID").setAutoWidth(true).setFlexGrow(0).setResizable(true);

                    g.addColumn(row -> formatTime(row.get("xact_start"))).setHeader("Tx Start")
                            .setAutoWidth(true).setFlexGrow(0).setResizable(true);
                    g.addColumn(row -> extractAppParam((String) row.get("application_name"), "Time")).setHeader("Java Start")
                            .setAutoWidth(true).setFlexGrow(0).setResizable(true);
                    g.addColumn(row -> extractAppParam((String) row.get("application_name"), "ID")).setHeader("Thread ID")
                            .setAutoWidth(true).setFlexGrow(0).setResizable(true);
                    g.addColumn(row -> row.get("wait_event")).setHeader("Wait").setAutoWidth(true).setFlexGrow(0).setResizable(true);
                    g.addColumn(createHighlightRenderer("waiting_query")).setHeader("Query").setFlexGrow(1).setResizable(true);

                    g.setItems(v);
                    g.setHeight("250px");
                    layout.add(g);
                }
            }
        }));

        rootCauseGrid.addItemClickListener(e -> rootCauseGrid.setDetailsVisible(e.getItem(), !rootCauseGrid.isDetailsVisible(e.getItem())));
    }

    /**
     * Новый рендерер для простой подсветки текста/чисел, не прибегающий к SQL парсеру
     */
    private Renderer<Map<String, Object>> createSimpleHighlightRenderer(String key) {
        return LitRenderer.<Map<String, Object>>of("<span .innerHTML='${item.htmlContent}'></span>")
                .withProperty("htmlContent", row -> {
                    String val = row.get(key) != null ? String.valueOf(row.get(key)) : "";

                    String safeVal = val.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                    if (currentSearchFilter != null && !currentSearchFilter.isBlank() && val.toLowerCase().contains(currentSearchFilter.toLowerCase())) {
                        String safeFilter = currentSearchFilter.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                        String regex = "(?i)(" + java.util.regex.Pattern.quote(safeFilter) + ")";
                        return safeVal.replaceAll(regex, "<mark>$1</mark>");
                    }
                    return safeVal;
                });
    }

    private Renderer<Map<String, Object>> createHighlightRenderer(String key) {
        return LitRenderer.<Map<String, Object>>of(
                "<span style='font-family: monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: block; font-size: 12px;' .innerHTML='${item.htmlContent}'></span>"
        ).withProperty("htmlContent", row -> {
            String val = row.get(key) != null ? row.get(key).toString() : "";
            return SqlUtils.highlightHtml(val, true, currentSearchFilter);
        });
    }

    private @NonNull String formatTime(Object tsObj) {
        if (tsObj == null) return "";
        try {
            long millis = ((Timestamp) tsObj).getTime();
            int offset = toolbar.getTimezoneOffset() != null ? toolbar.getTimezoneOffset() : 0;
            return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
                    .plusHours(offset).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        } catch (Exception e) {
            return "";
        }
    }

    private String extractAppParam(String appName, String key) {
        if (appName == null || appName.isBlank()) return "";
        Matcher m = Pattern.compile("(?i)" + key + ":(\\S+)").matcher(appName);
        if (m.find()) {
            String val = m.group(1);
            if ("Time".equalsIgnoreCase(key) && val.matches("\\d{10,}")) {
                try {
                    long millis = Long.parseLong(val);
                    int offset = toolbar.getTimezoneOffset() != null ? toolbar.getTimezoneOffset() : 0;
                    return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.ofHours(offset))
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
                } catch (Exception e) {
                    return val;
                }
            }
            return val;
        }
        return "";
    }

    private void showQueryDialog(String sql1, String sql2) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Query Details");
        d.setWidth("80vw");
        d.setHeight("80vh");

        if (sql2 == null) {
            d.add(createQueryLayout(sql1));
        } else {
            Tabs t = new Tabs(new Tab("Blocked"), new Tab("Locking"));
            VerticalLayout v1 = createQueryLayout(sql1);
            VerticalLayout v2 = createQueryLayout(sql2);
            v2.setVisible(false);

            t.addSelectedChangeListener(e -> {
                v1.setVisible(t.getSelectedIndex() == 0);
                v2.setVisible(t.getSelectedIndex() == 1);
            });

            d.add(t, v1, v2);
        }
        d.getFooter().add(new Button("Close", e -> d.close()));
        d.open();
    }

    private VerticalLayout createQueryLayout(String sql) {
        Span c = new Span();
        c.getElement().setProperty("innerHTML", SqlUtils.highlightHtml(SqlUtils.prettifySql(sql), true, null));
        c.getStyle().set("white-space", "pre-wrap").set("font-family", "monospace").set("padding", "10px");

        VerticalLayout s = new VerticalLayout(c);
        s.setSizeFull();
        s.getStyle().set("overflow", "auto");
        return s;
    }

    /**
     * Открывает диалог настройки отображаемых столбцов
     */
    private void openColumnSettingsDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Отображаемые столбцы");
        dialog.setWidth("400px");

        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);

        List<Grid.Column<Map<String, Object>>> cols = activityGrid.getColumns();
        List<com.vaadin.flow.component.checkbox.Checkbox> checkBoxes = new ArrayList<>();

        com.vaadin.flow.component.checkbox.Checkbox pidCheck = new com.vaadin.flow.component.checkbox.Checkbox("PID", true);
        pidCheck.setEnabled(false);
        layout.add(pidCheck);

        for (Grid.Column<Map<String, Object>> col : cols) {
            String key = col.getKey();
            if (key == null || key.equals("PID")) continue;

            com.vaadin.flow.component.checkbox.Checkbox cb = new com.vaadin.flow.component.checkbox.Checkbox(key, col.isVisible());
            checkBoxes.add(cb);
            layout.add(cb);

            cb.addValueChangeListener(e -> {
                if (!e.getValue()) {
                    long visibleCount = checkBoxes.stream().filter(com.vaadin.flow.component.checkbox.Checkbox::getValue).count();
                    if (visibleCount < 2) {
                        cb.setValue(true);
                        com.vaadin.flow.component.notification.Notification.show(
                                "Должно быть выбрано минимум 3 столбца (включая PID)",
                                3000, com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
                    } else {
                        col.setVisible(false);
                    }
                } else {
                    col.setVisible(true);
                }
            });
        }

        Div separator = new Div();
        separator.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)");
        separator.getStyle().set("margin", "var(--lumo-space-m) 0");
        layout.add(separator);

        Span thresholdLabel = new Span("Порог подсветки (TX Start vs Timeline):");
        thresholdLabel.getStyle().set("font-weight", "bold");
        layout.add(thresholdLabel);

        HorizontalLayout thresholdLayout = new HorizontalLayout();
        thresholdLayout.setSpacing(true);
        thresholdLayout.setAlignItems(Alignment.CENTER);

        IntegerField hoursField = new IntegerField("Часы");
        hoursField.setWidth("80px");
        hoursField.setMin(0);
        hoursField.setValue(highlightThresholdMillis / 3600000);

        IntegerField minutesField = new IntegerField("Минуты");
        minutesField.setWidth("80px");
        minutesField.setMin(0);
        minutesField.setMax(59);
        minutesField.setValue((highlightThresholdMillis % 3600000) / 60000);

        IntegerField secondsField = new IntegerField("Секунды");
        secondsField.setWidth("80px");
        secondsField.setMin(0);
        secondsField.setMax(59);
        secondsField.setValue((highlightThresholdMillis % 60000) / 1000);

        IntegerField millisField = new IntegerField("Миллисекунды");
        millisField.setWidth("80px");
        millisField.setMin(0);
        millisField.setMax(999);
        millisField.setValue(highlightThresholdMillis % 1000);

        thresholdLayout.add(hoursField, minutesField, secondsField, millisField);
        layout.add(thresholdLayout);

        Button applyBtn = new Button("Применить порог", e -> {
            int hours = hoursField.getValue() != null ? hoursField.getValue() : 0;
            int minutes = minutesField.getValue() != null ? minutesField.getValue() : 0;
            int seconds = secondsField.getValue() != null ? secondsField.getValue() : 0;
            int millis = millisField.getValue() != null ? millisField.getValue() : 0;

            highlightThresholdMillis = hours * 3600000 + minutes * 60000 + seconds * 1000 + millis;
            updateActivityGridItems();

            com.vaadin.flow.component.notification.Notification.show(
                    "Порог подсветки: " + formatThreshold(highlightThresholdMillis),
                    2000, com.vaadin.flow.component.notification.Notification.Position.BOTTOM_CENTER);
        });
        applyBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        layout.add(applyBtn);

        dialog.add(layout);

        Button closeBtn = new Button("Закрыть", e -> dialog.close());
        closeBtn.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(closeBtn);

        dialog.open();
    }

    private String formatThreshold(long millis) {
        long h = millis / 3600000;
        long m = (millis % 3600000) / 60000;
        long s = (millis % 60000) / 1000;
        long ms = millis % 1000;
        return String.format("%dч %dм %dс %dмс", h, m, s, ms);
    }

    // ========================================================================
    // Row highlighting — timestamp helpers and inline-style application
    // ========================================================================

    /**
     * Converts various timestamp representations to epoch millis.
     * Supports: java.sql.Timestamp, java.time.LocalDateTime, java.time.LocalTime,
     * Long, Number, and ISO-formatted String.
     * Returns -1 if conversion fails.
     */
    private long toMillis(Object obj) {
        switch (obj) {
            case null -> {
                return -1;
            }
            case Timestamp ts -> {
                return ts.getTime();
            }
            case java.time.LocalDateTime ldt -> {
                return Timestamp.valueOf(ldt).getTime();
            }
            case java.time.LocalTime lt -> {
                // No date context — use epoch day 0
                return Timestamp.valueOf(
                        java.time.LocalDate.ofEpochDay(0).atTime(lt)).getTime();
                // No date context — use epoch day 0
            }
            case Number n -> {
                return n.longValue();
            }
            case String s -> {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException e) {
                    // try ISO format
                    try {
                        return Timestamp.valueOf(s.trim()).getTime();
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            default -> {
            }
        }
        return -1;
    }

    /**
     * Extracts the time-of-day portion (milliseconds since midnight UTC)
     * from an epoch millis value. Used for comparing displayed times
     * independently of date components.
     */
    private long getTimeOfDayMillis(long epochMillis) {
        return (epochMillis % 86400000L + 86400000L) % 86400000L;
    }

    /**
     * Resolves the snapshot timestamp from lastLoadedSnapshot.
     * Tries multiple possible keys: snapshot_timestamp, snap_ts, timestamp, ts.
     * Returns -1 if none found.
     */
    private long resolveSnapshotMillis() {
        if (lastLoadedSnapshot == null) return -1;
        for (String key : new String[]{"snapshot_timestamp", "snap_ts", "timestamp", "ts"}) {
            Object val = lastLoadedSnapshot.get(key);
            if (val != null) {
                long ms = toMillis(val);
                if (ms >= 0) return ms;
            }
        }
        return -1;
    }

    /**
     * Applies row highlight styles based on TX Start vs snapshot timestamp.
     * Computes the set of highlighted PIDs server-side, passes them to JS,
     * then JS reads each row's PID from the first cell's slotted content
     * and applies inline styles accordingly.
     */
    private void applyHighlightStyles() {
        if (lastLoadedSnapshot == null || currentActivityData == null) return;

        long snapshotTimeMillis = resolveSnapshotMillis();
        if (snapshotTimeMillis < 0) return;

        int offset = toolbar.getTimezoneOffset() != null ? toolbar.getTimezoneOffset() : 0;

        // Compute set of PIDs that should be highlighted
        java.util.Set<Integer> highlightPids = new java.util.HashSet<>();
        int diagCount = 0;
        for (Map<String, Object> row : currentActivityData) {
            try {
                Object txStartObj = row.get("xact_start");
                if (txStartObj == null) continue;
                long txStartMillis = toMillis(txStartObj);
                if (txStartMillis < 0) continue;
                long adjustedTxStart = txStartMillis + (offset * 3600000L);

                // Compare DISPLAYED times only (time-of-day, no dates).
                // The timeline displays snapshot time in the SYSTEM timezone
                // (e.g. Europe/Moscow), while the TX START column uses the
                // user-configurable UTC offset. We must match both to what
                // the user actually sees on screen.
                long txTimeOfDay = getTimeOfDayMillis(adjustedTxStart);
                long systemOffsetMs = java.util.TimeZone.getDefault().getRawOffset();
                long snapTimeOfDay = getTimeOfDayMillis(snapshotTimeMillis + systemOffsetMs);

                long diff = snapTimeOfDay - txTimeOfDay;
                if (diff < 0) diff += 86400000L; // midnight wrap-around
                if (diff > 43200000L) diff = 0;  // >12h means TX is after timeline

                // Diagnostic: log first 2 rows
                if (diagCount < 2) {
                    diagCount++;
                    System.err.println("[HL-DIAG] row#" + diagCount
                            + " snap_epoch=" + snapshotTimeMillis
                            + " snap_tod=" + snapTimeOfDay
                            + " (" + (snapTimeOfDay/3600000) + "h" + ((snapTimeOfDay%3600000)/60000) + "m)"
                            + " tx_epoch=" + txStartMillis
                            + " tx_tod=" + txTimeOfDay
                            + " (" + (txTimeOfDay/3600000) + "h" + ((txTimeOfDay%3600000)/60000) + "m)"
                            + " sys_tz_offset=" + (systemOffsetMs/3600000) + "h"
                            + " user_offset=" + offset
                            + " diff=" + diff + "ms"
                            + " threshold=" + highlightThresholdMillis + "ms"
                            + " => " + (diff > highlightThresholdMillis ? "HIGHLIGHT" : "skip")
                            + " pid=" + row.get("pid"));
                }

                if (diff > highlightThresholdMillis) {
                    Object pidObj = row.get("pid");
                    if (pidObj instanceof Number) {
                        highlightPids.add(((Number) pidObj).intValue());
                    }
                }
            } catch (Exception ignored) { }
        }
        System.err.println("[HL-DIAG] Total highlighted PIDs: " + highlightPids.size()
                + " of " + currentActivityData.size() + " rows (threshold="
                + formatThreshold(highlightThresholdMillis) + ", offset=" + offset + ")");

        String pidList = highlightPids.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        boolean isDark = UI.getCurrent().getElement().getThemeList().contains(Lumo.DARK);

        UI.getCurrent().beforeClientResponse(activityGrid, ctx -> {
            activityGrid.getElement().executeJs("""
                (function() {
                    var grid = $0;
                    var isDark = $1;
                    var pidStr = $2;
                    var bg = isDark ? 'rgba(255,82,82,0.18)' : 'rgba(244,67,54,0.12)';
                    var bar = isDark ? 'inset 3px 0 0 0 rgba(255,82,82,0.55)' : 'inset 3px 0 0 0 rgba(244,67,54,0.7)';

                    // Build set of highlighted PIDs
                    var hlPids = {};
                    if (pidStr) {
                        pidStr.split(',').forEach(function(p) { hlPids[p.trim()] = true; });
                    }
                    var hlCount = Object.keys(hlPids).length;

                    // Function to read PID from a <tr>'s first cell
                    function getPid(tr) {
                        var firstTd = tr.querySelector('td');
                        if (!firstTd) return null;
                        var slot = firstTd.querySelector('slot');
                        if (slot) {
                            var assigned = slot.assignedElements();
                            if (assigned.length > 0) {
                                var text = assigned[0].textContent.trim();
                                return text;
                            }
                        }
                        return null;
                    }

                    // Function to highlight a <tr>
                    function highlight(tr) {
                        tr.style.setProperty('background-color', bg, 'important');
                        tr.querySelectorAll('td').forEach(function(td) {
                            td.style.setProperty('background-color', bg, 'important');
                        });
                        var firstTd = tr.querySelector('td');
                        if (firstTd) firstTd.style.setProperty('box-shadow', bar, 'important');
                    }

                    // Function to unhighlight a <tr>
                    function unhighlight(tr) {
                        tr.style.removeProperty('background-color');
                        tr.querySelectorAll('td').forEach(function(td) {
                            td.style.removeProperty('background-color');
                        });
                        var firstTd = tr.querySelector('td');
                        if (firstTd) firstTd.style.removeProperty('box-shadow');
                    }

                    if (!grid.shadowRoot) {
                        console.error('[Highlight] Grid has no shadow root');
                        return;
                    }

                    // Clear ALL inline styles first (both tbody and tfoot rows)
                    var allTrs = grid.shadowRoot.querySelectorAll('tr');
                    allTrs.forEach(function(tr) { unhighlight(tr); });

                    // Apply highlight to matching tbody rows
                    var tbody = grid.shadowRoot.querySelector('tbody');
                    if (!tbody) {
                        console.error('[Highlight] No tbody found');
                        return;
                    }

                    var bodyRows = tbody.querySelectorAll('tr');
                    var styledCount = 0;

                    bodyRows.forEach(function(tr) {
                        var pid = getPid(tr);
                        if (pid !== null && hlPids[pid]) {
                            highlight(tr);
                            styledCount++;
                        }
                    });

                    console.log('[Highlight] Applied: ' + styledCount + '/' + bodyRows.length +
                        ' rows (PIDs to highlight: ' + hlCount + ', dark=' + isDark + ')');

                    // MutationObserver for virtual scrolling - style new rows as they appear
                    if (tbody._hlObserver) tbody._hlObserver.disconnect();
                    tbody._hlObserver = new MutationObserver(function(mutations) {
                        mutations.forEach(function(m) {
                            m.addedNodes.forEach(function(node) {
                                if (node.nodeName === 'TR') {
                                    var pid = getPid(node);
                                    if (pid !== null && hlPids[pid]) {
                                        highlight(node);
                                    }
                                }
                            });
                        });
                    });
                    tbody._hlObserver.observe(tbody, { childList: true });
                })();
                """,
                    activityGrid.getElement(), isDark, pidList
            );
        });
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI.getCurrent().getPage().executeJs(
                "const u=document.querySelector('vaadin-upload');" +
                        "if(u) u.shadowRoot.querySelector('vaadin-upload-file-list').style.display='none';"
        );
    }

    @PreDestroy
    public void destroy() {
        if (toolbar != null) toolbar.shutdown();
    }
}
