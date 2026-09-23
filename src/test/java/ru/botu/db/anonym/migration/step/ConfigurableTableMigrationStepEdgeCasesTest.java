// Тест 100% покрытия всех ветвей ConfigurableTableMigrationStep.

package ru.botu.db.anonym.migration.step;

import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.config.AnonymizationStrategy;
import ru.botu.db.anonym.migration.engine.DeterministicAnonymizer;
import ru.botu.db.anonym.migration.repository.DynamicTableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;

/**
 * Тестирование обработки первичных ключей UUID и BIGINT, чекпоинтов и делегирования выборки.
 */
public class ConfigurableTableMigrationStepEdgeCasesTest {

    // Мок репозитория.
    private DynamicTableRepository repository;

    // Обезличиватель.
    private DeterministicAnonymizer anonymizer;

    /**
     * Настройка перед тестами.
     */
    @BeforeEach
    public void setUp() {
        repository = Mockito.mock(DynamicTableRepository.class);
        AnonymizationProperties props = new AnonymizationProperties("salt-1", 10, 1, "loc.test", Collections.emptyList());
        anonymizer = new DeterministicAnonymizer(props);
    }

    /**
     * Проверка парсинга чекпоинтов для BIGINT и UUID включая пустые строки и null.
     */
    @Test
    @DisplayName("Проверка всех ветвей парсинга чекпоинтов для типов UUID и BIGINT")
    public void testCheckpointParsingAllBranches() {
        AnonymizationProperties.TableRuleConfig uuidConfig = new AnonymizationProperties.TableRuleConfig("t_uuid", "id", "UUID", List.of());
        ConfigurableTableMigrationStep stepUuid = new ConfigurableTableMigrationStep(uuidConfig, repository, anonymizer);
        Assertions.assertThat(stepUuid.parseCheckpoint(null)).isEmpty();
        Assertions.assertThat(stepUuid.parseCheckpoint(" ")).isEmpty();
        UUID uuid = UUID.randomUUID();
        Assertions.assertThat(stepUuid.parseCheckpoint(uuid.toString())).contains(uuid);
        AnonymizationProperties.TableRuleConfig bigintConfig = new AnonymizationProperties.TableRuleConfig("t_bigint", "id", "BIGINT", List.of());
        ConfigurableTableMigrationStep stepBigint = new ConfigurableTableMigrationStep(bigintConfig, repository, anonymizer);
        Assertions.assertThat(stepBigint.parseCheckpoint(null)).isEmpty();
        Assertions.assertThat(stepBigint.parseCheckpoint("")).isEmpty();
        Assertions.assertThat(stepBigint.parseCheckpoint("998877")).contains(998877L);
    }

    /**
     * Проверка методов fetchChunk, serializeCheckpoint и updateChunk.
     */
    @Test
    @DisplayName("Проверка методов fetchChunk и updateChunk с делегированием в репозиторий")
    public void testFetchAndUpsertDelegation() {
        AnonymizationProperties.TableRuleConfig config = new AnonymizationProperties.TableRuleConfig(
         "customers", "id", "BIGINT",
         List.of(new AnonymizationProperties.ColumnRuleConfig("name", AnonymizationStrategy.FULL_NAME))
        );
        ConfigurableTableMigrationStep step = new ConfigurableTableMigrationStep(config, repository, anonymizer);
        String context = "[TEST][STEP][DELEGATE]";
        Mockito.when(repository.fetchChunkFromSource(ArgumentMatchers.eq(config), ArgumentMatchers.eq(50L), ArgumentMatchers.eq(10), ArgumentMatchers.eq(context)))
         .thenReturn(List.of(Map.of("id", 51L, "name", "Иванов")));
        List<Map<String, Object>> fetched = step.fetchChunk(50L, 10, context);
        Assertions.assertThat(fetched).hasSize(1);
        Assertions.assertThat(step.serializeCheckpoint(51L)).isEqualTo("51");
        step.updateChunk(fetched, context);
        Mockito.verify(repository, Mockito.times(1)).upsertChunkToTarget(ArgumentMatchers.eq(config), ArgumentMatchers.eq(fetched), ArgumentMatchers.eq(context));
    }

}

