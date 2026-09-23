// Тестирование оркестратора миграции с разделением баз.

package ru.botu.db.anonym.migration.service;

import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.config.AnonymizationStrategy;
import ru.botu.db.anonym.migration.engine.DeterministicAnonymizer;
import ru.botu.db.anonym.migration.orchestrator.GenericKeysetChunkMigrator;
import ru.botu.db.anonym.migration.repository.DynamicTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import java.util.List;

/**
 * Проверка параллельного запуска сконфигурированных шагов миграции.
 */
public class DataAnonymizationMigrationServiceTest {

    // Мок мигратора.
    private GenericKeysetChunkMigrator chunkMigrator;

    // Мок репозитория.
    private DynamicTableRepository dynamicRepository;

    // Тестируемый сервис.
    private DataAnonymizationMigrationService migrationService;

    /**
     * Инициализация перед тестом с использованием POJO конструктора.
     */
    @BeforeEach
    public void setUp() {
        chunkMigrator = Mockito.mock(GenericKeysetChunkMigrator.class);
        dynamicRepository = Mockito.mock(DynamicTableRepository.class);
        AnonymizationProperties.TableRuleConfig table1 = new AnonymizationProperties.TableRuleConfig(
            "table1", "id", "UUID",
            List.of(new AnonymizationProperties.ColumnRuleConfig("col1", AnonymizationStrategy.EMAIL))
        );
        AnonymizationProperties props = new AnonymizationProperties("salt-123", 500, 2, "test.local", List.of(table1));
        DeterministicAnonymizer anonymizer = new DeterministicAnonymizer(props);
        migrationService = new DataAnonymizationMigrationService(chunkMigrator, props, dynamicRepository, anonymizer);
    }

    /**
     * Проверка запуска миграции для зарегистрированных таблиц.
     */
    @Test
    @DisplayName("Оркестратор вызывает migrateTable для всех настроенных в YAML таблиц")
    public void testExecuteAnonymizationMigration() {
        migrationService.executeAnonymizationMigration();
        Mockito.verify(chunkMigrator, Mockito.times(1))
            .migrateTable(ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

}

