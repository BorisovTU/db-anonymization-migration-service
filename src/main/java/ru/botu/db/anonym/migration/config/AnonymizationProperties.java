// Иерархическая конфигурация правил миграции таблиц на базе Project Lombok.

package ru.botu.db.anonym.migration.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Главный класс конфигурационных параметров приложения на базе Lombok.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Validated
@ConfigurationProperties(prefix = "anonymization")
public class AnonymizationProperties {

    // Секретная соль проекта для HMAC-SHA256.
    @NotBlank
    private String salt;

    // Размер пакета Keyset-выборки.
    @Positive
    private int batchSize = 2000;

    // Лимит параллельных задач виртуальных потоков.
    @Positive
    private int concurrencyLimit = 16;

    // Тестовый домен для псевдонимизированных email.
    @NotBlank
    private String emailDomain;

    // Список правил для таблиц.
    @NotEmpty
    @Valid
    private List<TableRuleConfig> tables;


    /**
     * Правила маскирования для конкретной таблицы (Lombok POJO).
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class TableRuleConfig {

        // Имя таблицы.
        @NotBlank
        private String tableName;

        // Имя колонки первичного ключа.
        @NotBlank
        private String idColumn;

        // Тип первичного ключа (UUID или BIGINT).
        @NotBlank
        private String idType;

        // Список колонок для маскирования.
        @NotEmpty
        @Valid
        private List<ColumnRuleConfig> columns;

    }

    /**
     * Правило маскирования отдельной колонки (Lombok POJO).
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class ColumnRuleConfig {

        // Имя колонки.
        @NotBlank
        private String columnName;

        // Стратегия маскирования.
        @NotNull
        private AnonymizationStrategy strategy;

    }

}