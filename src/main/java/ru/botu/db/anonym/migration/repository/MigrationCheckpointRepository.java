// Репозиторий хранения контрольных точек миграции в Target DB.

package ru.botu.db.anonym.migration.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Управление контрольными точками миграции в целевой БД.
 */
@Repository
public class MigrationCheckpointRepository {

    // Логгер класса.
    private static final Logger log = LoggerFactory.getLogger(MigrationCheckpointRepository.class);

    // JDBC шаблон Target DB.
    private final JdbcTemplate targetJdbcTemplate;

    /**
     * Конструктор репозитория чекпоинтов.
     */
    public MigrationCheckpointRepository(JdbcTemplate targetJdbcTemplate) {
        this.targetJdbcTemplate = targetJdbcTemplate;
    }

    /**
     * Чтение последней контрольной точки из Target DB.
     */
    public Optional<String> getLastProcessedId(String tableName, String contextId) {
        long start = System.currentTimeMillis();
        String sql = "SELECT last_processed_id FROM migration_checkpoints WHERE table_name = ?";
        List<String> list = targetJdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("last_processed_id"), tableName);
        long duration = System.currentTimeMillis() - start;
        Optional<String> result = list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        log.debug("[{}] Target DB checkpoint for table {} fetched in {} ms: {}", contextId, tableName, duration, result.orElse("NONE"));
        return result;
    }

    /**
     * Атомарное сохранение контрольной точки в Target DB.
     */
    public void saveCheckpoint(String tableName, String lastProcessedId, String contextId) {
        long start = System.currentTimeMillis();
        String sql = "INSERT INTO migration_checkpoints (table_name, last_processed_id, updated_at) " +
                     "VALUES (?, ?, NOW()) " +
                     "ON CONFLICT (table_name) DO UPDATE SET last_processed_id = EXCLUDED.last_processed_id, updated_at = NOW()";
        targetJdbcTemplate.update(sql, tableName, lastProcessedId);
        long duration = System.currentTimeMillis() - start;
        log.debug("[{}] Target DB checkpoint updated for table {} (id={}) in {} ms", contextId, tableName, lastProcessedId, duration);
    }

}

