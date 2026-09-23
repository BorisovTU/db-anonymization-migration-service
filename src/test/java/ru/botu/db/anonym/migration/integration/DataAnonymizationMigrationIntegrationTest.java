package ru.botu.db.anonym.migration.integration;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.config.AnonymizationStrategy;
import ru.botu.db.anonym.migration.engine.DeterministicAnonymizer;
import ru.botu.db.anonym.migration.orchestrator.GenericKeysetChunkMigrator;
import ru.botu.db.anonym.migration.repository.DynamicTableRepository;
import ru.botu.db.anonym.migration.repository.MigrationCheckpointRepository;
import ru.botu.db.anonym.migration.service.DataAnonymizationMigrationService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;

/**
 * Комплексный интеграционный тест на Testcontainers с двумя изолированными базами PostgreSQL.
 */
@Testcontainers
public class DataAnonymizationMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> sourceContainer =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("source_db")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Container
    private static final PostgreSQLContainer<?> targetContainer =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("target_db")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static HikariDataSource sourceDataSource;
    private static HikariDataSource targetDataSource;
    private static JdbcTemplate sourceJdbc;
    private static JdbcTemplate targetJdbc;
    private static DataAnonymizationMigrationService migrationService;

    @BeforeAll
    public static void setUpAll() throws Exception {
        sourceDataSource = new HikariDataSource();
        sourceDataSource.setJdbcUrl(sourceContainer.getJdbcUrl());
        sourceDataSource.setUsername(sourceContainer.getUsername());
        sourceDataSource.setPassword(sourceContainer.getPassword());
        sourceDataSource.setDriverClassName("org.postgresql.Driver");
        sourceJdbc = new JdbcTemplate(sourceDataSource);

        targetDataSource = new HikariDataSource();
        targetDataSource.setJdbcUrl(targetContainer.getJdbcUrl() + "?reWriteBatchedInserts=true");
        targetDataSource.setUsername(targetContainer.getUsername());
        targetDataSource.setPassword(targetContainer.getPassword());
        targetDataSource.setDriverClassName("org.postgresql.Driver");
        targetJdbc = new JdbcTemplate(targetDataSource);

        // Инициализация структуры в Source DB
        sourceJdbc.execute("CREATE TABLE customer_profiles (id UUID PRIMARY KEY, full_name VARCHAR(255) NOT NULL, phone_number VARCHAR(64), email VARCHAR(255), created_at TIMESTAMP DEFAULT NOW())");
        sourceJdbc.execute("CREATE TABLE customer_attachments (id BIGINT PRIMARY KEY, customer_id UUID REFERENCES customer_profiles(id), file_storage_path VARCHAR(1024) NOT NULL, mime_type VARCHAR(128) NOT NULL, uploaded_at TIMESTAMP DEFAULT NOW())");

        // Инициализация структуры в Target DB через Liquibase
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(targetDataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        liquibase.afterPropertiesSet();

        // Настройка правил
        AnonymizationProperties props = new AnonymizationProperties();
        props.setSalt("integration-secret-salt-2026");
        props.setBatchSize(2);
        props.setConcurrencyLimit(2);
        props.setEmailDomain("test.local");

        AnonymizationProperties.TableRuleConfig tableProfiles = new AnonymizationProperties.TableRuleConfig(
                "customer_profiles", "id", "UUID",
                List.of(
                        new AnonymizationProperties.ColumnRuleConfig("full_name", AnonymizationStrategy.FULL_NAME),
                        new AnonymizationProperties.ColumnRuleConfig("phone_number", AnonymizationStrategy.PHONE),
                        new AnonymizationProperties.ColumnRuleConfig("email", AnonymizationStrategy.EMAIL)
                )
        );

        AnonymizationProperties.TableRuleConfig tableAttachments = new AnonymizationProperties.TableRuleConfig(
                "customer_attachments", "id", "BIGINT",
                List.of(
                        new AnonymizationProperties.ColumnRuleConfig("customer_id", AnonymizationStrategy.AS_IS),
                        new AnonymizationProperties.ColumnRuleConfig("file_storage_path", AnonymizationStrategy.FILE_PATH),
                        new AnonymizationProperties.ColumnRuleConfig("mime_type", AnonymizationStrategy.AS_IS)
                )
        );

        props.setTables(List.of(tableProfiles, tableAttachments));

        DynamicTableRepository dynamicRepo = new DynamicTableRepository(sourceJdbc, targetJdbc);
        MigrationCheckpointRepository checkpointRepo = new MigrationCheckpointRepository(targetJdbc);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(targetDataSource);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        DeterministicAnonymizer anonymizer = new DeterministicAnonymizer(props);
        GenericKeysetChunkMigrator migrator = new GenericKeysetChunkMigrator(checkpointRepo, txTemplate, props);
        migrationService = new DataAnonymizationMigrationService(migrator, props, dynamicRepo, anonymizer);
    }

    @AfterAll
    public static void tearDownAll() {
        if (sourceDataSource != null) sourceDataSource.close();
        if (targetDataSource != null) targetDataSource.close();
    }

    @Test
    @DisplayName("Сквозная миграция: проверка сохранения связей FK, дублей, NULL и битых файлов в Target DB")
    public void testEndToEndMigrationWithReferentialIntegrity() {
        UUID user1Id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID user2Id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID user3DuplicateId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        sourceJdbc.update("INSERT INTO customer_profiles (id, full_name, phone_number, email) VALUES (?, ?, ?, ?)", user1Id, "Иванов Иван Иванович", "+79161234567", "ivanov@corp.com");
        sourceJdbc.update("INSERT INTO customer_profiles (id, full_name, phone_number, email) VALUES (?, ?, ?, ?)", user2Id, "Сидоров Петр", null, null);
        sourceJdbc.update("INSERT INTO customer_profiles (id, full_name, phone_number, email) VALUES (?, ?, ?, ?)", user3DuplicateId, "Иванов Иван Иванович", "+79161234567", "ivanov@corp.com");

        sourceJdbc.update("INSERT INTO customer_attachments (id, customer_id, file_storage_path, mime_type) VALUES (?, ?, ?, ?)", 501L, user1Id, "/var/docs/contract_2024.pdf", "application/pdf");
        sourceJdbc.update("INSERT INTO customer_attachments (id, customer_id, file_storage_path, mime_type) VALUES (?, ?, ?, ?)", 502L, user1Id, "/var/dead_links/missing_archive.zip", "application/zip");
        sourceJdbc.update("INSERT INTO customer_attachments (id, customer_id, file_storage_path, mime_type) VALUES (?, ?, ?, ?)", 503L, user3DuplicateId, "/var/docs/contract_2024.pdf", "application/pdf");

        // Запуск миграции
        migrationService.executeAnonymizationMigration();

        // Проверка количества перенесенных строк
        Integer profilesCount = targetJdbc.queryForObject("SELECT count(*) FROM customer_profiles", Integer.class);
        Assertions.assertThat(profilesCount).isEqualTo(3);
        Integer attachmentsCount = targetJdbc.queryForObject("SELECT count(*) FROM customer_attachments", Integer.class);
        Assertions.assertThat(attachmentsCount).isEqualTo(3);

        // Проверка сохранения дубликатов
        Map<String, Object> user1Target = targetJdbc.queryForMap("SELECT full_name, phone_number, email FROM customer_profiles WHERE id = ?", user1Id);
        Map<String, Object> user3Target = targetJdbc.queryForMap("SELECT full_name, phone_number, email FROM customer_profiles WHERE id = ?", user3DuplicateId);

        Assertions.assertThat(user1Target.get("full_name")).isEqualTo(user3Target.get("full_name"));
        Assertions.assertThat(user1Target.get("full_name").toString()).startsWith("Anonymized_User_");
        Assertions.assertThat(user1Target.get("email")).isEqualTo(user3Target.get("email"));
        Assertions.assertThat(user1Target.get("email").toString()).endsWith("@test.local");

        // Проверка сохранения NULL значений
        Map<String, Object> user2Target = targetJdbc.queryForMap("SELECT full_name, phone_number, email FROM customer_profiles WHERE id = ?", user2Id);
        Assertions.assertThat(user2Target.get("phone_number")).isNull();
        Assertions.assertThat(user2Target.get("email")).isNull();

        // Проверка сохранения референциальной целостности (FK)
        Map<String, Object> attach501 = targetJdbc.queryForMap("SELECT customer_id, file_storage_path FROM customer_attachments WHERE id = 501");
        Assertions.assertThat(attach501.get("customer_id")).isEqualTo(user1Id);
        Assertions.assertThat(attach501.get("file_storage_path").toString()).startsWith("/var/docs/file_");
        Assertions.assertThat(attach501.get("file_storage_path").toString()).endsWith(".pdf");

        Map<String, Object> attach502 = targetJdbc.queryForMap("SELECT customer_id, file_storage_path FROM customer_attachments WHERE id = 502");
        Assertions.assertThat(attach502.get("file_storage_path").toString()).startsWith("/var/dead_links/file_");
        Assertions.assertThat(attach502.get("file_storage_path").toString()).endsWith(".zip");

        // Проверка контрольных точек
        String profileCheckpoint = targetJdbc.queryForObject("SELECT last_processed_id FROM migration_checkpoints WHERE table_name = 'customer_profiles'", String.class);
        Assertions.assertThat(profileCheckpoint).isNotNull();
        String attachmentCheckpoint = targetJdbc.queryForObject("SELECT last_processed_id FROM migration_checkpoints WHERE table_name = 'customer_attachments'", String.class);
        Assertions.assertThat(attachmentCheckpoint).isEqualTo("503");
    }
}
