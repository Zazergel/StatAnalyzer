package org.zazergel.statanalyzer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Сервис для получения статистических данных из базы данных.
 * Использует кеширование для оптимизации повторных запросов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {
    private final JdbcTemplate jdbcTemplate;

    /**
     * Очищает все таблицы данных и инвалидирует все кеши.
     */
    @CacheEvict(value = {"snapshots", "snapshotData", "victims"}, allEntries = true)
    public void clearAllData() {
        jdbcTemplate.execute("DELETE FROM stat_activity");
        jdbcTemplate.execute("DELETE FROM locks_data");
        jdbcTemplate.execute("DELETE FROM snapshots");
        log.info("Cleared all data and invalidated caches");
    }

    /**
     * Возвращает список снимков с кешированием по searchFilter.
     * Кеш инвалидируется при загрузке новых данных или очистке.
     *
     * @param searchFilter фильтр поиска (может быть null)
     * @return список снимков с метаданными
     */
    @Cacheable(value = "snapshots", key = "#searchFilter != null ? #searchFilter : 'all'")
    public List<Map<String, Object>> getSnapshots(String searchFilter) {
        log.debug("Fetching snapshots from DB with filter: {}", searchFilter);

        String coreSql = """
            SELECT s.id, s.snapshot_timestamp,
                   (SELECT count(*) FROM stat_activity a WHERE a.snapshot_id = s.id) as act_cnt,
                   (SELECT count(*) FROM locks_data l WHERE l.snapshot_id = s.id) as lock_cnt
            """;

        String orderSql = " ORDER BY s.snapshot_timestamp ASC";

        if (searchFilter != null && !searchFilter.isBlank()) {
            String p = "%" + searchFilter.trim() + "%";
            String sql = coreSql + """
                , (
                    (SELECT count(*) FROM stat_activity a WHERE a.snapshot_id = s.id
                     AND (a.query ILIKE ? OR a.usename ILIKE ? OR a.wait_event ILIKE ? OR a.application_name ILIKE ? OR CAST(a.pid AS TEXT) ILIKE ?))
                    +
                    (SELECT count(*) FROM locks_data l WHERE l.snapshot_id = s.id
                     AND (l.waiting_query ILIKE ? OR l.locking_query ILIKE ? OR CAST(l.waiting_pid AS TEXT) ILIKE ? OR CAST(l.locking_pid AS TEXT) ILIKE ?))
                  ) as search_matches
                FROM snapshots s
                WHERE (
                    EXISTS (
                        SELECT 1 FROM stat_activity a WHERE a.snapshot_id = s.id
                        AND (a.query ILIKE ? OR a.usename ILIKE ? OR a.wait_event ILIKE ? OR a.application_name ILIKE ? OR CAST(a.pid AS TEXT) ILIKE ?)
                    ) OR EXISTS (
                        SELECT 1 FROM locks_data l WHERE l.snapshot_id = s.id
                        AND (l.waiting_query ILIKE ? OR l.locking_query ILIKE ? OR CAST(l.waiting_pid AS TEXT) ILIKE ? OR CAST(l.locking_pid AS TEXT) ILIKE ?)
                    )
                )
                """ + orderSql;
            return jdbcTemplate.queryForList(sql, p, p, p, p, p, p, p, p, p, p, p, p, p, p, p, p, p, p);
        } else {
            return jdbcTemplate.queryForList(coreSql + " FROM snapshots s" + orderSql);
        }
    }

    /**
     * Возвращает данные активности для snapshot с кешированием.
     * Ключ кеша комбинирует snapshotId и filter.
     *
     * @param snapshotId ID снимка
     * @param filter     фильтр поиска
     * @return список записей активности
     */
    @Cacheable(value = "snapshotData", key = "'activity_' + #snapshotId + '_' + (#filter != null ? #filter : 'none')")
    public List<Map<String, Object>> getActivity(Integer snapshotId, String filter) {
        log.debug("Fetching activity from DB for snapshot {} with filter: {}", snapshotId, filter);

        String sql = """
                    SELECT
                        a.pid,
                        a.usename,
                        a.application_name,
                        a.state,
                        a.wait_event,
                        a.query,
                        a.xact_start
                    FROM stat_activity a
                    WHERE a.snapshot_id = ?
                """;

        if (filter != null && !filter.isBlank()) {
            String pattern = "%" + filter.toLowerCase() + "%";
            sql += """
                        AND (
                            LOWER(CAST(a.pid AS TEXT)) LIKE ?
                            OR LOWER(a.usename) LIKE ?
                            OR LOWER(a.application_name) LIKE ?
                            OR LOWER(a.state) LIKE ?
                            OR LOWER(COALESCE(a.wait_event, '')) LIKE ?
                            OR LOWER(a.query) LIKE ?
                        )
                    """;
            return jdbcTemplate.queryForList(sql, snapshotId, pattern, pattern, pattern, pattern, pattern, pattern);
        }

        return jdbcTemplate.queryForList(sql, snapshotId);
    }

    /**
     * Возвращает данные блокировок с кешированием.
     *
     * @param snapshotId ID снимка
     * @param filter     фильтр поиска
     * @return список блокировок
     */
    @Cacheable(value = "snapshotData", key = "'locks_' + #snapshotId + '_' + (#filter != null ? #filter : 'none')")
    public List<Map<String, Object>> getLocks(Integer snapshotId, String filter) {
        log.debug("Fetching locks from DB for snapshot {} with filter: {}", snapshotId, filter);

        String sql = """
                    SELECT
                        waiting_pid,
                        locking_pid,
                        STRING_AGG(DISTINCT locking_mode, ', ') as locking_mode,
                        MAX(waiting_query) as waiting_query,
                        MAX(locking_query) as locking_query
                    FROM locks_data
                    WHERE snapshot_id = ?
                """;

        if (filter != null && !filter.isBlank()) {
            String f = "%" + filter.trim() + "%";
            sql += """
                        AND (
                            CAST(waiting_pid AS TEXT) ILIKE ? OR
                            CAST(locking_pid AS TEXT) ILIKE ? OR
                            waiting_query ILIKE ? OR
                            locking_query ILIKE ?
                        )
                    """;
            sql += " GROUP BY waiting_pid, locking_pid ORDER BY waiting_pid";
            return jdbcTemplate.queryForList(sql, snapshotId, f, f, f, f);
        }

        sql += " GROUP BY waiting_pid, locking_pid ORDER BY waiting_pid";
        return jdbcTemplate.queryForList(sql, snapshotId);
    }

    /**
     * Возвращает корневые причины блокировок с кешированием.
     *
     * @param snapshotId   ID снимка
     * @param searchFilter фильтр поиска
     * @return список виновников блокировок
     */
    @Cacheable(value = "snapshotData", key = "'rootcause_' + #snapshotId + '_' + (#searchFilter != null ? #searchFilter : 'none')")
    public List<Map<String, Object>> getRootCause(Integer snapshotId, String searchFilter) {
        log.debug("Fetching root cause from DB for snapshot {} with filter: {}", snapshotId, searchFilter);

        String sql = """
                    WITH blockers AS (
                        SELECT DISTINCT locking_pid
                        FROM locks_data
                        WHERE snapshot_id = ?
                    )
                    SELECT
                        b.locking_pid,
                        COUNT(DISTINCT l.waiting_pid) as cnt,
                        MAX(l.locking_query) as query
                    FROM blockers b
                    JOIN locks_data l ON l.locking_pid = b.locking_pid AND l.snapshot_id = ?
                    WHERE
                        b.locking_pid NOT IN (
                            SELECT waiting_pid
                            FROM locks_data
                            WHERE snapshot_id = ?
                        )
                """;

        if (searchFilter != null && !searchFilter.isBlank()) {
            String p = "%" + searchFilter.trim() + "%";
            sql += """
                        AND (
                            l.locking_query ILIKE ? OR
                            CAST(b.locking_pid AS TEXT) ILIKE ?
                        )
                    """;
            sql += " GROUP BY b.locking_pid ORDER BY cnt DESC";
            return jdbcTemplate.queryForList(sql, snapshotId, snapshotId, snapshotId, p, p);
        }

        sql += " GROUP BY b.locking_pid ORDER BY cnt DESC";
        return jdbcTemplate.queryForList(sql, snapshotId, snapshotId, snapshotId);
    }

    /**
     * Возвращает жертв блокировок с кешированием.
     * Кешируется отдельно, т.к. запрос происходит при раскрытии деталей.
     *
     * @param rootPid      PID виновника
     * @param snapshotId   ID снимка
     * @param searchFilter фильтр поиска
     * @return список жертв
     */
    @Cacheable(value = "victims", key = "'pid_' + #rootPid + '_snap_' + #snapshotId + '_filter_' + (#searchFilter != null ? #searchFilter : 'none')")
    public List<Map<String, Object>> getVictims(Integer rootPid, Integer snapshotId, String searchFilter) {
        log.debug("Fetching victims from DB for PID {} in snapshot {} with filter: {}", rootPid, snapshotId, searchFilter);

        String sql = """
                    SELECT
                        l.waiting_pid,
                        STRING_AGG(DISTINCT l.wait_event, ', ') as wait_event,
                        MAX(l.waiting_query) as waiting_query,
                        MIN(s.xact_start) as xact_start,
                        MAX(s.application_name) as application_name
                    FROM locks_data l
                    LEFT JOIN stat_activity s ON s.snapshot_id = l.snapshot_id AND s.pid = l.waiting_pid
                    WHERE l.locking_pid = ? AND l.snapshot_id = ?
                """;

        if (searchFilter != null && !searchFilter.isBlank()) {
            String p = "%" + searchFilter.trim() + "%";
            sql += """
                        AND (
                            l.waiting_query ILIKE ? OR
                            CAST(l.waiting_pid AS TEXT) ILIKE ?
                        )
                    """;
            sql += " GROUP BY l.waiting_pid ORDER BY MIN(s.xact_start) ASC NULLS LAST, l.waiting_pid ASC";
            return jdbcTemplate.queryForList(sql, rootPid, snapshotId, p, p);
        }

        sql += " GROUP BY l.waiting_pid ORDER BY MIN(s.xact_start) ASC NULLS LAST, l.waiting_pid ASC";
        return jdbcTemplate.queryForList(sql, rootPid, snapshotId);
    }

    /**
     * Возвращает данные для графика без кеширования.
     * Вызывается редко, только при открытии диалога.
     *
     * @return данные для построения графика
     */
    public List<Map<String, Object>> getChartData() {
        return jdbcTemplate.queryForList("""
                    SELECT s.snapshot_timestamp,
                        (SELECT count(*) FROM stat_activity WHERE snapshot_id = s.id) as act_cnt,
                        (SELECT count(*) FROM locks_data WHERE snapshot_id = s.id) as lock_cnt
                    FROM snapshots s ORDER BY s.snapshot_timestamp
                """);
    }
}
