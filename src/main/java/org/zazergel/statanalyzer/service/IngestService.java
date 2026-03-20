package org.zazergel.statanalyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.zazergel.statanalyzer.parser.CsvParser;
import org.zazergel.statanalyzer.parser.PsqlParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Сервис загрузки данных из файлов в базу данных.
 * Обрабатывает ZIP-архивы с файлами pg_stat_activity и pg_locks (TXT и CSV форматы).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private static final Pattern FILENAME_DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2})");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final int BATCH_SIZE = 1000;

    private final PsqlParser parser;
    private final CsvParser csvParser;
    private final JdbcTemplate jdbcTemplate;

    private final Map<LocalDateTime, Integer> snapshotCache = new ConcurrentHashMap<>();
    private final Map<LocalDateTime, List<RawLockRow>> pendingRawLocks = new ConcurrentHashMap<>();
    private final Map<LocalDateTime, Boolean> activityReady = new ConcurrentHashMap<>();

    /**
     * Очищает кэш snapshot ID и временные буферы.
     */
    public void clearCache() {
        snapshotCache.clear();
        pendingRawLocks.clear();
        activityReady.clear();
    }

    /**
     * Обрабатывает ZIP-архив с файлами данных.
     *
     * @param zipStream       поток ZIP-архива
     * @param offsetHours     смещение часового пояса
     * @param onFileProcessed callback после обработки каждого файла
     */
    @CacheEvict(value = {"snapshots", "snapshotData", "victims"}, allEntries = true)
    public void ingestZip(InputStream zipStream, int offsetHours, Consumer<Integer> onFileProcessed) {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(zipStream)) {
            java.util.zip.ZipEntry entry;
            log.info("Parsing ZIP data...");
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("__MACOSX") || entry.getName().startsWith(".")) {
                    continue;
                }

                String filename = entry.getName();
                ShieldedInputStream shieldedStream = new ShieldedInputStream(zis);
                processSingleFile(filename, shieldedStream, offsetHours);
                onFileProcessed.accept(1);
                zis.closeEntry();
            }
        } catch (java.io.IOException e) {
            log.error("Error reading ZIP stream", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Загружает один файл (вызывается при загрузке не-ZIP файла).
     *
     * @param filename    имя файла
     * @param fileContent содержимое файла
     * @param offsetHours смещение часового пояса
     */
    @CacheEvict(value = {"snapshots", "snapshotData", "victims"}, allEntries = true)
    public void ingest(String filename, InputStream fileContent, int offsetHours) {
        processSingleFile(filename, fileContent, offsetHours);
    }

    /**
     * Читает данные напрямую с локального диска сервера. Поддерживает как конкретные файлы (включая ZIP),
     * так и папки со множеством лог-файлов.
     *
     * @param path            полный путь к папке или файлу
     * @param offsetHours     смещение часового пояса
     * @param onFileProcessed callback для обновления UI
     */
    @CacheEvict(value = {"snapshots", "snapshotData", "victims"}, allEntries = true)
    public void ingestLocalPath(String path, int offsetHours, Consumer<Integer> onFileProcessed) {
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            throw new RuntimeException("Файл или папка не найдены: " + path);
        }

        try {
            if (file.isDirectory()) {
                try (Stream<Path> paths = Files.walk(file.toPath())) {
                    paths
                            .filter(java.nio.file.Files::isRegularFile)
                            .forEach(p -> {
                                try {
                                    String name = p.getFileName().toString();
                                    if (name.toLowerCase().endsWith(".zip")) {
                                        try (InputStream is = new BufferedInputStream(java.nio.file.Files.newInputStream(p), 65536)) {
                                            ingestZip(is, offsetHours, onFileProcessed);
                                        }
                                    } else if (name.toLowerCase().contains("stat") || name.toLowerCase().contains("lock")) {
                                        try (InputStream is = new BufferedInputStream(java.nio.file.Files.newInputStream(p), 65536)) {
                                            processSingleFile(name, is, offsetHours);
                                            onFileProcessed.accept(1);
                                        }
                                    }
                                } catch (Exception e) {
                                    log.error("Failed to process local file: {}", p, e);
                                }
                            });
                }
            } else if (path.toLowerCase().endsWith(".zip")) {
                try (InputStream is = new BufferedInputStream(java.nio.file.Files.newInputStream(file.toPath()), 65536)) {
                    ingestZip(is, offsetHours, onFileProcessed);
                }
            } else {
                try (InputStream is = new BufferedInputStream(java.nio.file.Files.newInputStream(file.toPath()), 65536)) {
                    processSingleFile(file.getName(), is, offsetHours);
                    onFileProcessed.accept(1);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Ошибка чтения пути: " + path, e);
        }
    }



    /**
     * Определяет формат файла (CSV или TXT) и вызывает соответствующий метод загрузки.
     */
    private void processSingleFile(String filename, InputStream fileContent, int offsetHours) {
        try {
            BufferedInputStream buffered = new BufferedInputStream(fileContent, 65536);
            String lowerFilename = filename.toLowerCase();
            boolean csv = looksLikeCsv(filename, buffered);

            if (lowerFilename.contains("stat")) {
                if (csv) {
                    ingestPgStatCsv(filename, buffered, offsetHours);
                } else {
                    Integer snapshotId = getOrCreateSnapshotId(filename, offsetHours);
                    ingestPgStat(snapshotId, buffered, offsetHours);
                }
            } else if (lowerFilename.contains("lock")) {
                if (csv) {
                    ingestLocksCsv(filename, buffered, offsetHours);
                } else {
                    Integer snapshotId = getOrCreateSnapshotId(filename, offsetHours);
                    ingestLocks(snapshotId, buffered);
                }
            } else {
                log.warn("Unknown file type, skipping: {}", filename);
            }
        } catch (Exception e) {
            log.error("Failed to ingest file: {}", filename, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Получает или создает ID snapshot для старого формата (из имени файла).
     *
     * @param filename    имя файла
     * @param offsetHours смещение часового пояса
     * @return ID snapshot
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Integer getOrCreateSnapshotId(String filename, int offsetHours) {
        LocalDateTime snapshotTime = parseFilenameTimestamp(filename);
        if (offsetHours != 0) {
            snapshotTime = snapshotTime.plusHours(offsetHours);
        }
        return getOrCreateSnapshotId(filename, snapshotTime);
    }

    /**
     * Базовый метод получения/создания snapshot ID по времени.
     *
     * @param filename     имя файла (для логирования)
     * @param snapshotTime точное время snapshot
     * @return ID snapshot
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Integer getOrCreateSnapshotId(String filename, LocalDateTime snapshotTime) {
        return snapshotCache.computeIfAbsent(snapshotTime, ts -> {
            Timestamp sqlTs = Timestamp.valueOf(ts);
            try {
                List<Integer> ids = jdbcTemplate.queryForList(
                        "SELECT id FROM snapshots WHERE snapshot_timestamp = ?",
                        Integer.class,
                        sqlTs
                );
                if (!ids.isEmpty()) {
                    return ids.getFirst();
                }

                KeyHolder keyHolder = new GeneratedKeyHolder();

                jdbcTemplate.update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO snapshots (filename, snapshot_timestamp) VALUES (?, ?)",
                            new String[]{"id"}
                    );
                    ps.setString(1, filename);
                    ps.setTimestamp(2, sqlTs);
                    return ps;
                }, keyHolder);

                Number key = keyHolder.getKey();
                if (key != null) {
                    return key.intValue();
                } else {
                    throw new RuntimeException("Failed to generate ID for snapshot");
                }

            } catch (DuplicateKeyException e) {
                return jdbcTemplate.queryForObject(
                        "SELECT id FROM snapshots WHERE snapshot_timestamp = ?",
                        Integer.class,
                        sqlTs
                );
            }
        });
    }

    /**
     * Извлекает timestamp из имени файла (старый TXT-формат).
     *
     * @param filename имя файла
     * @return извлеченная дата-время
     */
    private LocalDateTime parseFilenameTimestamp(String filename) {
        Pattern newPattern = Pattern.compile("(\\d{8})_(\\d{4})");
        Matcher newMatcher = newPattern.matcher(filename);
        if (newMatcher.find()) {
            String datePart = newMatcher.group(1);
            String timePart = newMatcher.group(2);
            int year = Integer.parseInt(datePart.substring(0, 4));
            int month = Integer.parseInt(datePart.substring(4, 6));
            int day = Integer.parseInt(datePart.substring(6, 8));
            int hour = Integer.parseInt(timePart.substring(0, 2));
            int minute = Integer.parseInt(timePart.substring(2, 4));
            return LocalDateTime.of(year, month, day, hour, minute, 0);
        }

        Matcher oldMatcher = FILENAME_DATE_PATTERN.matcher(filename);
        if (oldMatcher.find()) {
            return LocalDateTime.parse(oldMatcher.group(1), DATE_FMT);
        }

        log.warn("Could not parse timestamp from filename: {}. Using current time.", filename);
        return LocalDateTime.now().withSecond(0).withNano(0);
    }

    /**
     * Загружает данные блокировок в базу (старый TXT формат).
     * Использует простую @Transactional без разделения на подтранзакции.
     *
     * @param snapshotId ID snapshot
     * @param content    содержимое файла
     */
    @Transactional
    protected void ingestLocks(Integer snapshotId, InputStream content) throws Exception {
        String sql = """
                INSERT INTO locks_data (
                    snapshot_id, waiting_pid, waiting_mode, waiting_query,
                    locking_pid, locking_mode, locking_query, wait_event_type, wait_event
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        parser.parse(content, List.of("waiting_pid", "locking_pid", "locktype", "pid", "mode"), row -> {
            Integer waitingPid = parseInt(row.get("waiting_pid"));
            Integer lockingPid = parseInt(row.get("locking_pid"));
            String waitingMode = row.get("waiting_mode");

            if (waitingPid == null && lockingPid == null) {
                Integer rawPid = parseInt(row.get("pid"));
                String granted = row.get("granted");
                if (rawPid != null && ("f".equalsIgnoreCase(granted) || "false".equalsIgnoreCase(granted))) {
                    waitingPid = rawPid;
                    waitingMode = row.get("mode");
                }
            }

            if (waitingPid != null || lockingPid != null) {
                batch.add(new Object[]{
                        snapshotId, waitingPid, waitingMode, row.get("waiting_query"),
                        lockingPid, row.get("locking_mode"), row.get("locking_query"),
                        row.get("wait_event_type"), row.get("wait_event")
                });
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
        });

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
    }

    /**
     * Загружает данные pg_stat_activity в базу (старый TXT формат).
     * Использует простую @Transactional без разделения на подтранзакции.
     *
     * @param snapshotId  ID snapshot
     * @param content     содержимое файла
     * @param offsetHours смещение часового пояса
     */
    @Transactional
    protected void ingestPgStat(Integer snapshotId, InputStream content, int offsetHours) throws Exception {
        String sql = """
                INSERT INTO stat_activity (
                    snapshot_id, datid, datname, pid, usename, application_name,
                    client_addr, wait_event_type, wait_event, state, query, xact_start
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        parser.parse(content, List.of("datid", "pid", "xact_start"), row -> {
            batch.add(new Object[]{
                    snapshotId, parseLong(row.get("datid")), row.get("datname"),
                    parseInt(row.get("pid")), row.get("usename"), row.get("application_name"),
                    row.get("client_addr"), row.get("wait_event_type"), row.get("wait_event"),
                    row.get("state"), row.get("query"), parseTimestamp(row.get("xact_start"))
            });
            if (batch.size() >= BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        });

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
    }

    /**
     * Загружает pg_stat_activity из CSV.
     * Группирует по snap_ts и сбрасывает батчи при смене времени.
     *
     * @param filename    имя файла
     * @param content     содержимое CSV
     * @param offsetHours смещение часового пояса
     */
    @Transactional
    protected void ingestPgStatCsv(String filename, InputStream content, int offsetHours) throws Exception {
        String sql = """
                INSERT INTO stat_activity (
                    snapshot_id, datid, datname, pid, usename, application_name,
                    client_addr, wait_event_type, wait_event, state, query, xact_start
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        LocalDateTime[] currentSnapHolder = new LocalDateTime[1];

        csvParser.parse(content, row -> {
            LocalDateTime snapTime = parseSnapshotTimestamp(row.get("snap_ts"), offsetHours);
            if (snapTime == null) {
                return;
            }

            LocalDateTime currentSnap = currentSnapHolder[0];
            if (currentSnap == null) {
                currentSnapHolder[0] = snapTime;
                currentSnap = snapTime;
            }

            if (!snapTime.equals(currentSnap)) {
                if (!batch.isEmpty()) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }

                activityReady.put(currentSnap, Boolean.TRUE);
                flushPendingLocksIfAny(filename, currentSnap);

                currentSnapHolder[0] = snapTime;
            }

            Integer snapshotId = getOrCreateSnapshotId(filename, snapTime);

            batch.add(new Object[]{
                    snapshotId,
                    parseLong(row.get("datid")),
                    row.get("datname"),
                    parseInt(row.get("pid")),
                    row.get("usename"),
                    row.get("application_name"),
                    row.get("client_addr"),
                    row.get("wait_event_type"),
                    row.get("wait_event"),
                    row.get("state"),
                    row.get("query"),
                    parseTimestamp(row.get("xact_start"))
            });

            if (batch.size() >= BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        });

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }

        LocalDateTime finalSnap = currentSnapHolder[0];
        if (finalSnap != null) {
            activityReady.put(finalSnap, Boolean.TRUE);
            flushPendingLocksIfAny(filename, finalSnap);
        }
    }

    /**
     * Определяет тип CSV locks (precomputed пары или raw pg_locks).
     */
    @Transactional
    protected void ingestLocksCsv(String filename, InputStream content, int offsetHours) throws Exception {
        BufferedInputStream bis = (content instanceof BufferedInputStream)
                ? (BufferedInputStream) content
                : new BufferedInputStream(content, 65536);

        String headerLine = peekFirstLine(bis);
        List<String> headers = parseCsvHeader(headerLine);

        boolean precomputedPairs = headers.contains("waiting_pid") || headers.contains("locking_pid");
        boolean rawPgLocks = headers.contains("locktype") && headers.contains("pid") && headers.contains("mode");

        if (precomputedPairs) {
            ingestLocksCsvPrecomputed(filename, bis, offsetHours);
        } else if (rawPgLocks) {
            ingestLocksCsvRawPgLocks(filename, bis, offsetHours);
        } else {
            log.warn("CSV locks file has unknown header set, skipping: {}", filename);
        }
    }

    /**
     * CSV locks с уже вычисленными парами waiting_pid/locking_pid.
     */
    @Transactional
    protected void ingestLocksCsvPrecomputed(String filename, InputStream content, int offsetHours) throws Exception {
        String sql = """
                INSERT INTO locks_data (
                    snapshot_id, waiting_pid, waiting_mode, waiting_query,
                    locking_pid, locking_mode, locking_query, wait_event_type, wait_event
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        csvParser.parse(content, row -> {
            LocalDateTime snapTime = parseSnapshotTimestamp(row.get("snap_ts"), offsetHours);
            if (snapTime == null) return;

            Integer snapshotId = getOrCreateSnapshotId(filename, snapTime);
            Integer waitingPid = parseInt(row.get("waiting_pid"));
            Integer lockingPid = parseInt(row.get("locking_pid"));

            if (waitingPid != null || lockingPid != null) {
                batch.add(new Object[]{
                        snapshotId, waitingPid, row.get("waiting_mode"), row.get("waiting_query"),
                        lockingPid, row.get("locking_mode"), row.get("locking_query"),
                        row.get("wait_event_type"), row.get("wait_event")
                });
                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
        });

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
    }

    /**
     * CSV locks с сырыми данными pg_locks (locktype, pid, mode, granted).
     * Вычисляем пары waiter/blocker на лету.
     */
    @Transactional
    protected void ingestLocksCsvRawPgLocks(String filename, InputStream content, int offsetHours) throws Exception {
        List<RawLockRow> buffer = new ArrayList<>(1024);
        LocalDateTime[] currentSnapHolder = new LocalDateTime[1];

        csvParser.parse(content, row -> {
            LocalDateTime snapTime = parseSnapshotTimestamp(row.get("snap_ts"), offsetHours);
            if (snapTime == null) return;

            RawLockRow lr = RawLockRow.fromCsvRow(snapTime, row);
            if (lr == null) return;

            LocalDateTime currentSnap = currentSnapHolder[0];
            if (currentSnap == null) {
                currentSnapHolder[0] = snapTime;
                currentSnap = snapTime;
            }

            if (!snapTime.equals(currentSnap)) {
                flushRawLocksSnapshot(filename, currentSnap, new ArrayList<>(buffer));
                buffer.clear();
                currentSnapHolder[0] = snapTime;
            }

            buffer.add(lr);
        });

        LocalDateTime finalSnap = currentSnapHolder[0];
        if (finalSnap != null && !buffer.isEmpty()) {
            flushRawLocksSnapshot(filename, finalSnap, buffer);
        }
    }

    /**
     * Сбрасывает буфер raw locks для одного snapshot.
     * Если activity готов — вставляем сразу, иначе кладем в pending.
     */
    private void flushRawLocksSnapshot(String filename, LocalDateTime snapTime, List<RawLockRow> rows) {
        if (Boolean.TRUE.equals(activityReady.get(snapTime))) {
            Integer snapshotId = getOrCreateSnapshotId(filename, snapTime);
            insertComputedLocks(snapshotId, rows);
        } else {
            pendingRawLocks.merge(snapTime, rows, (a, b) -> {
                ArrayList<RawLockRow> merged = new ArrayList<>(a.size() + b.size());
                merged.addAll(a);
                merged.addAll(b);
                return merged;
            });
        }
    }

    /**
     * Обрабатывает отложенные locks после завершения загрузки activity.
     */
    private void flushPendingLocksIfAny(String filename, LocalDateTime snapTime) {
        List<RawLockRow> pending = pendingRawLocks.remove(snapTime);
        if (pending != null && !pending.isEmpty()) {
            Integer snapshotId = getOrCreateSnapshotId(filename, snapTime);
            insertComputedLocks(snapshotId, pending);
        }
    }

    /**
     * Вычисляет пары waiter/blocker из сырых lock-строк и вставляет в БД.
     */
    private void insertComputedLocks(Integer snapshotId, List<RawLockRow> rows) {
        String sql = """
                INSERT INTO locks_data (
                    snapshot_id, waiting_pid, waiting_mode, waiting_query,
                    locking_pid, locking_mode, locking_query, wait_event_type, wait_event
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        Map<Integer, ActivityInfo> activityByPid = loadActivityInfo(snapshotId);
        Map<LockKey, List<RawLockRow>> byKey = new HashMap<>();

        for (RawLockRow r : rows) {
            if (r.lockKey != null && r.pid != null && r.granted != null) {
                byKey.computeIfAbsent(r.lockKey, k -> new ArrayList<>()).add(r);
            }
        }

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        for (List<RawLockRow> group : byKey.values()) {
            List<RawLockRow> waiters = new ArrayList<>();
            List<RawLockRow> holders = new ArrayList<>();

            for (RawLockRow r : group) {
                if (Boolean.TRUE.equals(r.granted)) {
                    holders.add(r);
                } else {
                    waiters.add(r);
                }
            }

            if (!waiters.isEmpty() && !holders.isEmpty()) {
                for (RawLockRow w : waiters) {
                    for (RawLockRow h : holders) {
                        if (w.pid.equals(h.pid)) continue;

                        ActivityInfo wAct = activityByPid.get(w.pid);
                        ActivityInfo hAct = activityByPid.get(h.pid);

                        batch.add(new Object[]{
                                snapshotId,
                                w.pid, w.mode, wAct != null ? wAct.query : null,
                                h.pid, h.mode, hAct != null ? hAct.query : null,
                                wAct != null ? wAct.waitEventType : null,
                                wAct != null ? wAct.waitEvent : null
                        });

                        if (batch.size() >= BATCH_SIZE) {
                            jdbcTemplate.batchUpdate(sql, batch);
                            batch.clear();
                        }
                    }
                }
            }
        }

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
    }

    /**
     * Загружает информацию об активности для заданного snapshot (для связки с locks).
     */
    private Map<Integer, ActivityInfo> loadActivityInfo(Integer snapshotId) {
        Map<Integer, ActivityInfo> out = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, query, wait_event_type, wait_event FROM stat_activity WHERE snapshot_id = ?",
                snapshotId
        );
        for (Map<String, Object> r : rows) {
            Integer pid = (Integer) r.get("pid");
            if (pid != null) {
                out.put(pid, new ActivityInfo(
                        (String) r.get("query"),
                        (String) r.get("wait_event_type"),
                        (String) r.get("wait_event")
                ));
            }
        }
        return out;
    }

    /**
     * Парсит timestamp из CSV (snap_ts).
     * НЕ ОКРУГЛЯЕТ — берет точное значение из CSV.
     */
    private LocalDateTime parseSnapshotTimestamp(String s, int offsetHours) {
        if (s == null || s.isBlank()) return null;
        try {
            String v = s.trim();
            LocalDateTime result;

            if (v.contains("+") || (v.lastIndexOf("-") > 10 && v.length() - v.lastIndexOf("-") <= 6)) {
                String normalized = v.replace(" ", "T");
                if (normalized.matches(".*[+-]\\d{2}$")) {
                    normalized = normalized + ":00";
                }
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(
                        normalized,
                        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                );
                result = odt.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime();
            } else {
                result = Timestamp.valueOf(v).toLocalDateTime();
            }

            if (offsetHours != 0) {
                result = result.plusHours(offsetHours);
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse snap_ts: {}", s);
            return null;
        }
    }

    private boolean looksLikeCsv(String filename, BufferedInputStream content) throws IOException {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".csv")) return true;

        String first = peekFirstLine(content);
        if (first == null) return false;

        String f = first.trim().toLowerCase();
        return f.contains(",") && (f.contains("snap_ts") || f.contains("datid") || f.contains("locktype") || f.contains("wait_event"));
    }

    private String peekFirstLine(BufferedInputStream content) throws IOException {
        content.mark(8192);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        int b;
        while ((b = content.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
            if (baos.size() >= 8192) break;
        }
        content.reset();
        if (baos.size() == 0) return null;
        return baos.toString(StandardCharsets.UTF_8);
    }

    private List<String> parseCsvHeader(String headerLine) {
        if (headerLine == null || headerLine.isBlank()) return List.of();

        List<String> cols = new ArrayList<>();
        StringBuilder sb = new StringBuilder(headerLine.length());
        boolean inQuotes = false;

        for (int i = 0; i < headerLine.length(); i++) {
            char ch = headerLine.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < headerLine.length() && headerLine.charAt(i + 1) == '"') {
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
                    cols.add(sb.toString().trim().toLowerCase());
                    sb.setLength(0);
                } else if (ch == '"') {
                    inQuotes = true;
                } else {
                    sb.append(ch);
                }
            }
        }
        cols.add(sb.toString().trim().toLowerCase());
        return cols;
    }

    private Long parseLong(String s) {
        try {
            return s == null ? null : Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(String s) {
        try {
            return s == null ? null : Integer.parseInt(s);
        } catch (Exception e) {
            return null;
        }
    }

    private java.sql.Timestamp parseTimestamp(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            s = s.trim();
            if (s.contains("+") || (s.lastIndexOf("-") > 10 && s.length() - s.lastIndexOf("-") <= 6)) {
                String normalized = s.replace(" ", "T");
                if (normalized.matches(".*[+-]\\d{2}$")) {
                    normalized = normalized + ":00";
                }
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(
                        normalized,
                        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                );
                return java.sql.Timestamp.from(odt.toInstant());
            } else {
                return java.sql.Timestamp.valueOf(s);
            }
        } catch (Exception e) {
            log.warn("Failed to parse timestamp: {}", s);
            return null;
        }
    }

    private record ActivityInfo(String query, String waitEventType, String waitEvent) {
    }

    private record LockKey(
            String locktype, String database, String relation, String page, String tuple,
            String virtualxid, String transactionid, String classid, String objid, String objsubid
    ) {
    }

    private record RawLockRow(
            LocalDateTime snapMinute, Integer pid, String mode, Boolean granted, LockKey lockKey
    ) {
        static RawLockRow fromCsvRow(LocalDateTime snapMinute, Map<String, String> row) {
            Integer pid = tryParseInt(row.get("pid"));
            if (pid == null) return null;

            String mode = row.get("mode");
            Boolean granted = parseGranted(row.get("granted"));

            LockKey key = new LockKey(
                    row.get("locktype"), row.get("database"), row.get("relation"),
                    row.get("page"), row.get("tuple"), row.get("virtualxid"),
                    row.get("transactionid"), row.get("classid"), row.get("objid"), row.get("objsubid")
            );

            return new RawLockRow(snapMinute, pid, mode, granted, key);
        }

        private static Integer tryParseInt(String s) {
            try {
                if (s == null || s.isBlank()) return null;
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return null;
            }
        }

        private static Boolean parseGranted(String s) {
            if (s == null || s.isBlank()) return null;
            String v = s.trim().toLowerCase();
            if ("t".equals(v) || "true".equals(v)) return Boolean.TRUE;
            if ("f".equals(v) || "false".equals(v)) return Boolean.FALSE;
            return null;
        }
    }

    private static class ShieldedInputStream extends FilterInputStream {
        protected ShieldedInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
        }
    }
}
