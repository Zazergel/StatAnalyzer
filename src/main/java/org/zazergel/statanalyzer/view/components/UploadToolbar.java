package org.zazergel.statanalyzer.view.components;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.zazergel.statanalyzer.service.IngestService;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class UploadToolbar extends FlexLayout {

    private final IngestService ingestService;
    private final Runnable onDataRefresh;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final TextField pathField;
    private final Button loadBtn;
    private final Button browseBtn;
    private final ProgressBar progressBar;
    private final Div statusLabel;

    private final TextField searchField;
    private final Button chartBtn;
    private final Button clearBtn;

    private final AtomicReference<Instant> uploadStartTime = new AtomicReference<>();
    private Integer currentTimezoneOffset = 0;

    public UploadToolbar(IngestService ingestService,
                         Runnable onDataRefresh,
                         Runnable onClear,
                         Runnable onChartOpen,
                         Runnable onThemeToggle,
                         Consumer<String> onSearch,
                         Consumer<Integer> onTimezoneChange) {

        this.ingestService = ingestService;
        this.onDataRefresh = onDataRefresh;

        setWidthFull();
        getStyle().set("padding", "10px");
        getStyle().set("gap", "10px");
        getStyle().set("flex-wrap", "wrap");
        setAlignItems(Alignment.CENTER);
        getStyle().set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        Select<Integer> timezoneSelect = new Select<>();
        timezoneSelect.setItems(-12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        timezoneSelect.setValue(0);
        timezoneSelect.setPrefixComponent(new Icon(VaadinIcon.GLOBE));
        timezoneSelect.setWidth("140px");
        preventShrink(timezoneSelect);
        timezoneSelect.setItemLabelGenerator(offset -> {
            if (offset == 0) return "UTC";
            return (offset > 0 ? "UTC+" : "UTC") + offset;
        });
        timezoneSelect.addValueChangeListener(e -> {
            this.currentTimezoneOffset = e.getValue();
            onTimezoneChange.accept(e.getValue());
        });

        loadBtn = new Button("Загрузить", new Icon(VaadinIcon.PLAY));
        loadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        preventShrink(loadBtn);
        loadBtn.setVisible(false);

        pathField = new TextField();
        pathField.setPlaceholder("Путь к папке или архиву");
        pathField.setClearButtonVisible(true);
        pathField.setWidth("300px");
        preventShrink(pathField);
        pathField.setValueChangeMode(ValueChangeMode.EAGER);
        pathField.addValueChangeListener(e -> {
            String val = e.getValue();
            loadBtn.setVisible(val != null && !val.isBlank());
        });

        loadBtn.addClickListener(e -> handleLocalUpload(pathField.getValue()));

        browseBtn = new Button("Обзор", new Icon(VaadinIcon.FOLDER_OPEN));
        preventShrink(browseBtn);
        browseBtn.addClickListener(e -> openVaadinDirectoryChooser());

        searchField = new TextField();
        searchField.setPlaceholder("Поиск...");
        searchField.setPrefixComponent(new Icon(VaadinIcon.SEARCH));
        searchField.setClearButtonVisible(true);
        searchField.setValueChangeMode(ValueChangeMode.LAZY);
        searchField.setValueChangeTimeout(500);
        searchField.addValueChangeListener(e -> onSearch.accept(e.getValue()));
        searchField.setWidth("150px");
        preventShrink(searchField);

        chartBtn = new Button("График", new Icon(VaadinIcon.CHART), e -> onChartOpen.run());
        chartBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        preventShrink(chartBtn);

        Button themeBtn = new Button(new Icon(VaadinIcon.MOON_O), e -> onThemeToggle.run());
        themeBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        preventShrink(themeBtn);

        clearBtn = new Button("Очистить", new Icon(VaadinIcon.TRASH), e -> {
            searchField.clear();
            onClear.run();
            ingestService.clearCache();
        });
        clearBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
        preventShrink(clearBtn);

        progressBar = new ProgressBar();
        progressBar.setVisible(false);
        progressBar.setWidth("150px");
        preventShrink(progressBar);

        statusLabel = new Div();
        statusLabel.setVisible(false);
        statusLabel.getStyle()
                .set("font-size", "12px")
                .set("white-space", "nowrap")
                .set("color", "var(--lumo-secondary-text-color)");
        preventShrink(statusLabel);

        add(timezoneSelect, pathField, browseBtn, loadBtn, progressBar, statusLabel, searchField, chartBtn, themeBtn, clearBtn);
        setDataLoadedState(false);
    }

    public void setDataLoadedState(boolean hasData) {
        if (!hasData) {
            pathField.setVisible(true);
            browseBtn.setVisible(true);

            pathField.setEnabled(true);
            browseBtn.setEnabled(true);
            loadBtn.setEnabled(true);

            pathField.clear();

            String currentPath = pathField.getValue();
            loadBtn.setVisible(currentPath != null && !currentPath.isBlank());
        } else {
            pathField.setVisible(false);
            browseBtn.setVisible(false);
            loadBtn.setVisible(false);
        }

        searchField.setVisible(hasData);
        chartBtn.setVisible(hasData);
        clearBtn.setVisible(hasData);

        if (hasData) {
            progressBar.setVisible(false);
            statusLabel.setVisible(false);
        }
    }


    private void preventShrink(com.vaadin.flow.component.Component component) {
        component.getElement().getStyle().set("flex-shrink", "0");
    }

    public Integer getTimezoneOffset() {
        return currentTimezoneOffset;
    }

    private void openVaadinDirectoryChooser() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Выберите папку с логами или архив");
        dialog.setWidth("700px");
        dialog.setHeight("80vh");
        dialog.setResizable(true);
        dialog.setDraggable(true);

        TextField currentPathField = new TextField();
        currentPathField.setWidthFull();

        Grid<FileItem> grid = new Grid<>();
        grid.addComponentColumn(item -> {
            Icon icon;
            if (item.isUpDir()) icon = new Icon(VaadinIcon.ARROW_UP);
            else if (item.isDir()) icon = new Icon(VaadinIcon.FOLDER);
            else if (item.name().endsWith(".zip")) icon = new Icon(VaadinIcon.FILE_ZIP);
            else icon = new Icon(VaadinIcon.FILE_TEXT_O);

            icon.getStyle().set("margin-right", "10px");
            if (item.isDir() || item.isUpDir()) {
                icon.getStyle().set("color", "var(--lumo-primary-color)");
            }

            Span nameSpan = new Span(item.name());
            HorizontalLayout hl = new HorizontalLayout(icon, nameSpan);
            hl.setAlignItems(Alignment.CENTER);
            return hl;
        }).setHeader("Имя").setAutoWidth(true);

        Runnable refreshGrid = () -> {
            File dir = new File(currentPathField.getValue());
            if (!dir.exists() || !dir.isDirectory()) return;

            File[] files = dir.listFiles(f ->
                    f.isDirectory() || f.getName().endsWith(".zip") || f.getName().endsWith(".csv") || f.getName().endsWith(".txt")
            );

            List<FileItem> items = new ArrayList<>();
            if (dir.getParentFile() != null) {
                items.add(new FileItem(dir.getParentFile(), "..", true, true));
            }

            if (files != null) {
                List<File> sortedFiles = Arrays.asList(files);
                sortedFiles.sort((f1, f2) -> {
                    if (f1.isDirectory() && !f2.isDirectory()) return -1;
                    if (!f1.isDirectory() && f2.isDirectory()) return 1;
                    return f1.getName().compareToIgnoreCase(f2.getName());
                });

                for (File f : sortedFiles) {
                    items.add(new FileItem(f, f.getName(), false, f.isDirectory()));
                }
            }
            grid.setItems(items);
        };

        currentPathField.setValueChangeMode(ValueChangeMode.LAZY);
        currentPathField.addValueChangeListener(e -> refreshGrid.run());

        grid.addItemDoubleClickListener(e -> {
            FileItem clicked = e.getItem();
            if (clicked == null) return;

            if (clicked.isDir() || clicked.isUpDir()) {
                currentPathField.setValue(clicked.file().getAbsolutePath());
            } else {
                pathField.setValue(clicked.file().getAbsolutePath());
                dialog.close();
                handleLocalUpload(clicked.file().getAbsolutePath());
            }
        });

        Button selectFolderBtn = new Button("Импортировать", e -> {
            pathField.setValue(currentPathField.getValue());
            dialog.close();
            handleLocalUpload(currentPathField.getValue());
        });
        selectFolderBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelBtn = new Button("Отмена", e -> dialog.close());

        VerticalLayout layout = new VerticalLayout(currentPathField, grid);
        layout.setSizeFull();
        layout.setPadding(false);
        layout.getStyle().set("margin-top", "10px");

        dialog.add(layout);
        dialog.getFooter().add(cancelBtn, selectFolderBtn);

        String startPath = System.getProperty("user.dir");
        currentPathField.setValue(startPath != null ? startPath : System.getProperty("user.home"));
        refreshGrid.run();

        dialog.open();
    }

    private void handleLocalUpload(String path) {
        if (path == null || path.isBlank()) {
            return;
        }

        UI ui = UI.getCurrent();
        uploadStartTime.set(Instant.now());

        ui.access(() -> {
            progressBar.setVisible(true);
            progressBar.setIndeterminate(true);
            statusLabel.setVisible(true);
            statusLabel.setText("Чтение с диска...");
            loadBtn.setEnabled(false);
            browseBtn.setEnabled(false);
            pathField.setEnabled(false);
            ui.push();
        });

        scheduler.scheduleAtFixedRate(() -> {
            Instant start = uploadStartTime.get();
            if (start != null) {
                long secondsElapsed = Duration.between(start, Instant.now()).getSeconds();
                if (secondsElapsed > 5) {
                    ui.access(() -> {
                        statusLabel.setText(String.format("Парсинг... (%d сек)", secondsElapsed));
                        ui.push();
                    });
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        executor.submit(() -> {
            try {
                AtomicInteger fileCount = new AtomicInteger(0);
                ingestService.ingestLocalPath(path, currentTimezoneOffset, cnt -> {
                    int current = fileCount.addAndGet(cnt);
                    ui.access(() -> {
                        statusLabel.setText("Обработано " + current + " файлов...");
                        ui.push();
                    });
                });

                ui.access(() -> {
                    onDataRefresh.run();
                    uploadStartTime.set(null);
                    ui.push();
                });
            } catch (Exception e) {
                ui.access(() -> {
                    progressBar.setVisible(false);
                    statusLabel.setText("Ошибка: " + e.getMessage());
                    statusLabel.getStyle().set("color", "var(--lumo-error-color)");
                    loadBtn.setEnabled(true);
                    browseBtn.setEnabled(true);
                    pathField.setEnabled(true);
                    uploadStartTime.set(null);
                    ui.push();
                });
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
        scheduler.shutdown();
    }

    private record FileItem(File file, String name, boolean isUpDir, boolean isDir) {
    }
}
