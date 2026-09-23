// Репозиторий чтения из Source DB и пакетной вставки/обновления в Target DB.

package ru.botu.db.anonym.migration.repository;

import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Координирует вычитку из Source DB и пакетную вставку в Target DB по правилам, доступным через Lombok геттеры.
 */
@Repository
public class DynamicTableRepository {

    // Логгер класса.
    private static final Logger log = LoggerFactory.getLogger(DynamicTableRepository.class);

    // JDBC шаблон БД источника (Source DB).
    private final JdbcTemplate sourceJdbcTemplate;

    // JDBC шаблон БД приемника (Target DB).
    private final JdbcTemplate targetJdbcTemplate;

    /**
     * Конструктор внедрения изолированных пулов БД.
     */
    public DynamicTableRepository(JdbcTemplate sourceJdbcTemplate, JdbcTemplate targetJdbcTemplate) {
        this.sourceJdbcTemplate = sourceJdbcTemplate;
        this.targetJdbcTemplate = targetJdbcTemplate;
    }

    /**
     * Keyset-выборка данных из БД-источника (Source DB).
     */
    public List<Map<String, Object>> fetchChunkFromSource(AnonymizationProperties.TableRuleConfig tableConfig, Object lastSeenId, int limit, String contextId) {
        long start = System.currentTimeMillis();
        String tableName = tableConfig.getTableName();
        String idCol = tableConfig.getIdColumn();
        List<String> selectCols = new ArrayList<>();
        selectCols.add(idCol);
        for (AnonymizationProperties.ColumnRuleConfig col : tableConfig.getColumns()) {
            if (!selectCols.contains(col.getColumnName())) {
                selectCols.add(col.getColumnName());
            }
        }
        String columnsList = String.join(", ", selectCols);
        String sql = (lastSeenId == null) ?
            String.format("SELECT %s FROM %s ORDER BY %s ASC LIMIT ?", columnsList, tableName, idCol) :
            String.format("SELECT %s FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT ?", columnsList, tableName, idCol, idCol);
        List<Map<String, Object>> rows = (lastSeenId == null) ?
            sourceJdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                for (String col : selectCols) {
                    map.put(col, rs.getObject(col));
                }
                return map;
            }, limit) :
            sourceJdbcTemplate.query(sql, (rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                for (String col : selectCols) {
                    map.put(col, rs.getObject(col));
                }
                return map;
            }, lastSeenId, limit);
        long duration = System.currentTimeMillis() - start;
        log.debug("[{}] Source DB keyset query for table {} fetched {} rows in {} ms (lastSeenId={})", contextId, tableName, rows.size(), duration, lastSeenId);
        return rows;
    }

    /**
     * Пакетная вставка (Upsert) обезличенных данных в целевую БД (Target DB).
     */
    public void upsertChunkToTarget(AnonymizationProperties.TableRuleConfig tableConfig, List<Map<String, Object>> rows, String contextId) {
        if (rows.isEmpty()) {
            log.debug("[{}] No rows to insert into target table {}. Skipping.", contextId, tableConfig.getTableName());
            return;
        }
        long start = System.currentTimeMillis();
        String tableName = tableConfig.getTableName();
        String idCol = tableConfig.getIdColumn();
        List<String> allCols = new ArrayList<>();
        allCols.add(idCol);
        for (AnonymizationProperties.ColumnRuleConfig col : tableConfig.getColumns()) {
            if (!allCols.contains(col.getColumnName())) {
                allCols.add(col.getColumnName());
            }
        }
        String colNames = String.join(", ", allCols);
        String placeholders = String.join(", ", allCols.stream().map(c -> "?").toList());
        StringBuilder updateSet = new StringBuilder();
        for (int i = 0; i < allCols.size(); i++) {
            String c = allCols.get(i);
            if (!c.equalsIgnoreCase(idCol)) {
                if (!updateSet.isEmpty()) updateSet.append(", ");
                updateSet.append(c).append(" = EXCLUDED.").append(c);
            }
        }
        String sql = (updateSet.isEmpty()) ?
            String.format("INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO NOTHING", tableName, colNames, placeholders, idCol) :
            String.format("INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO UPDATE SET %s", tableName, colNames, placeholders, idCol, updateSet);
        targetJdbcTemplate.batchUpdate(sql, rows, rows.size(), (PreparedStatement ps, Map<String, Object> row) -> {
            int paramIndex = 1;
            for (String col : allCols) {
                ps.setObject(paramIndex++, row.get(col));
            }
        });
        long duration = System.currentTimeMillis() - start;
        log.debug("[{}] Target DB upsert executed for table {} ({} rows) in {} ms", contextId, tableName, rows.size(), duration);
    }

}

