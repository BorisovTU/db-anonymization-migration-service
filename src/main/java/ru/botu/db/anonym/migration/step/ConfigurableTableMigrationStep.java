// Динамический шаг миграции Source -> Target по правилам из application.yml.

package ru.botu.db.anonym.migration.step;

import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.engine.DeterministicAnonymizer;
import ru.botu.db.anonym.migration.repository.DynamicTableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Динамический шаг миграции: вычитывает из Source DB, маскирует в памяти, заливает в Target DB с типизацией TableMigrationStep<Object, Map<String, Object>>.
 */
public class ConfigurableTableMigrationStep implements TableMigrationStep<Object, Map<String, Object>> {

    // Логгер класса.
    private static final Logger log = LoggerFactory.getLogger(ConfigurableTableMigrationStep.class);

    // Конфигурация таблицы.
    private final AnonymizationProperties.TableRuleConfig tableConfig;

    // Репозиторий динамического доступа Source/Target.
    private final DynamicTableRepository repository;

    // Обезличиватель данных.
    private final DeterministicAnonymizer anonymizer;

    /**
     * Конструктор шага миграции.
     */
    public ConfigurableTableMigrationStep(AnonymizationProperties.TableRuleConfig tableConfig, DynamicTableRepository repository, DeterministicAnonymizer anonymizer) {
        this.tableConfig = tableConfig;
        this.repository = repository;
        this.anonymizer = anonymizer;
    }

    /**
     * Имя целевой таблицы.
     */
    @Override
    public String tableName() {
        return tableConfig.getTableName();
    }

    /**
     * Парсинг чекпоинта с учетом типа ключа через Lombok геттер getIdType().
     */
    @Override
    public Optional<Object> parseCheckpoint(String rawCheckpoint) {
        if (rawCheckpoint == null || rawCheckpoint.isBlank()) {
            return Optional.empty();
        }
        if ("UUID".equalsIgnoreCase(tableConfig.getIdType())) {
            return Optional.of(UUID.fromString(rawCheckpoint));
        }
        return Optional.of(Long.parseLong(rawCheckpoint));
    }

    /**
     * Сериализация объекта ключа в строку.
     */
    @Override
    public String serializeCheckpoint(Object id) {
        return String.valueOf(id);
    }

    /**
     * Извлечение ID из записи через getIdColumn().
     */
    @Override
    public Object extractId(Map<String, Object> record) {
        return record.get(tableConfig.getIdColumn());
    }

    /**
     * Выборка чанка из Source DB.
     */
    @Override
    public List<Map<String, Object>> fetchChunk(Object lastSeenId, int batchSize, String contextId) {
        return repository.fetchChunkFromSource(tableConfig, lastSeenId, batchSize, contextId);
    }

    /**
     * Обезличивание полей по стратегиям через Lombok геттеры.
     */
    @Override
    public List<Map<String, Object>> anonymizeChunk(List<Map<String, Object>> chunk, String contextId) {
        List<Map<String, Object>> anonymizedList = new ArrayList<>(chunk.size());
        for (Map<String, Object> sourceRow : chunk) {
            Map<String, Object> anonymizedRow = new HashMap<>(sourceRow);
            for (AnonymizationProperties.ColumnRuleConfig colRule : tableConfig.getColumns()) {
                Object rawValue = sourceRow.get(colRule.getColumnName());
                Object maskedValue = anonymizer.applyStrategy(rawValue, colRule.getStrategy(), contextId);
                anonymizedRow.put(colRule.getColumnName(), maskedValue);
            }
            anonymizedList.add(anonymizedRow);
        }
        return anonymizedList;
    }

    /**
     * Запись порции данных в Target DB.
     */
    @Override
    public void updateChunk(List<Map<String, Object>> chunk, String contextId) {
        repository.upsertChunkToTarget(tableConfig, chunk, contextId);
    }

}

