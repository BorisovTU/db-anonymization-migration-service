// Тестирование изоляции Source DB и Target DB в DynamicTableRepository.

package ru.botu.db.anonym.migration.repository;

import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.config.AnonymizationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;

/**
 * Проверка разделения операций чтения (Source) и записи (Target).
 */
public class DynamicTableRepositoryTest {

    // Мок JdbcTemplate источника.
    private JdbcTemplate sourceJdbcTemplate;

    // Мок JdbcTemplate приемника.
    private JdbcTemplate targetJdbcTemplate;

    // Тестируемый репозиторий.
    private DynamicTableRepository repository;

    // Конфигурация тестовой таблицы.
    private AnonymizationProperties.TableRuleConfig tableConfig;

    /**
     * Настройка моков перед тестами.
     */
    @BeforeEach
    public void setUp() {
        sourceJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        targetJdbcTemplate = Mockito.mock(JdbcTemplate.class);
        repository = new DynamicTableRepository(sourceJdbcTemplate, targetJdbcTemplate);
        tableConfig = new AnonymizationProperties.TableRuleConfig(
            "customer_profiles",
            "id",
            "UUID",
            List.of(new AnonymizationProperties.ColumnRuleConfig("email", AnonymizationStrategy.EMAIL))
        );
    }

    /**
     * Проверка вычитки данных строго из Source DB.
     */
    @Test
    @DisplayName("Выборка данных Keyset обращается исключительно к sourceJdbcTemplate")
    public void testFetchFromSourceOnly() {
        String context = "[TEST][SOURCE_READ][1]";
        UUID id = UUID.randomUUID();
        Mockito.when(sourceJdbcTemplate.query(ArgumentMatchers.anyString(), ArgumentMatchers.any(RowMapper.class), ArgumentMatchers.any()))
            .thenReturn(List.of(Map.of("id", id, "email", "src@domain.com")));
        List<Map<String, Object>> result = repository.fetchChunkFromSource(tableConfig, null, 10, context);
        Assertions.assertThat(result).hasSize(1);
        Mockito.verify(sourceJdbcTemplate, Mockito.times(1)).query(ArgumentMatchers.anyString(), ArgumentMatchers.any(RowMapper.class), ArgumentMatchers.eq(10));
        Mockito.verifyNoInteractions(targetJdbcTemplate);
    }

    /**
     * Проверка записи данных строго в Target DB.
     */
    @Test
    @DisplayName("Пакетная вставка данных обращается исключительно к targetJdbcTemplate")
    public void testUpsertToTargetOnly() {
        String context = "[TEST][TARGET_WRITE][1]";
        UUID id = UUID.randomUUID();
        List<Map<String, Object>> rows = List.of(Map.of("id", id, "email", "masked@test.corp"));
        repository.upsertChunkToTarget(tableConfig, rows, context);
        Mockito.verify(targetJdbcTemplate, Mockito.times(1)).batchUpdate(ArgumentMatchers.anyString(), ArgumentMatchers.eq(rows), ArgumentMatchers.eq(1), ArgumentMatchers.any());
        Mockito.verifyNoInteractions(sourceJdbcTemplate);
    }

}

