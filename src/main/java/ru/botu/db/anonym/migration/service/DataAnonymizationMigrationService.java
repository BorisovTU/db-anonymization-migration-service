package ru.botu.db.anonym.migration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.engine.DeterministicAnonymizer;
import ru.botu.db.anonym.migration.orchestrator.GenericKeysetChunkMigrator;
import ru.botu.db.anonym.migration.repository.DynamicTableRepository;
import ru.botu.db.anonym.migration.step.ConfigurableTableMigrationStep;
import ru.botu.db.anonym.migration.step.TableMigrationStep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сервис оркестрации миграции Source -> Target.
 * Шаги выполняются последовательно для гарантии целостности внешних ключей.
 */
@Service
public class DataAnonymizationMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DataAnonymizationMigrationService.class);

    private final GenericKeysetChunkMigrator chunkMigrator;
    private final List<TableMigrationStep<Object, Map<String, Object>>> migrationSteps;

    public DataAnonymizationMigrationService(
            GenericKeysetChunkMigrator chunkMigrator,
            AnonymizationProperties properties,
            DynamicTableRepository dynamicRepository,
            DeterministicAnonymizer anonymizer) {
        this.chunkMigrator = chunkMigrator;
        List<TableMigrationStep<Object, Map<String, Object>>> steps = new ArrayList<>();
        if (properties.getTables() != null) {
            for (AnonymizationProperties.TableRuleConfig tableConfig : properties.getTables()) {
                steps.add(new ConfigurableTableMigrationStep(tableConfig, dynamicRepository, anonymizer));
            }
        }
        this.migrationSteps = Collections.unmodifiableList(steps);
    }

    /**
     * Запуск миграции таблиц по порядку следования.
     */
    public void executeAnonymizationMigration() {
        String contextId = "[DB-MIGRATOR][ETL_ORCHESTRATOR][" + UUID.randomUUID() + "]";
        long totalStart = System.currentTimeMillis();
        log.debug("[{}] Starting Source->Target migration for {} tables in topological order...", contextId, migrationSteps.size());

        for (TableMigrationStep<Object, Map<String, Object>> step : migrationSteps) {
            log.debug("[{}] Executing migration for table: {}", contextId, step.tableName());
            chunkMigrator.migrateTable(step, contextId);
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        log.debug("[{}] Source->Target migration completed successfully in {} ms", contextId, totalDuration);
    }
}
