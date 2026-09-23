// Раннер старта процесса миграции с интеграцией в жизненный цикл Spring Boot.

package ru.botu.db.anonym.migration.runner;

import ru.botu.db.anonym.migration.orchestrator.GenericKeysetChunkMigrator;
import ru.botu.db.anonym.migration.service.DataAnonymizationMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * Запуск миграции и обработка сигнала Graceful Shutdown.
 */
@Component
public class DatabaseMigrationRunner implements CommandLineRunner {

    // Логгер раннера.
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    // Сервис оркестрации.
    private final DataAnonymizationMigrationService migrationService;

    // Универсальный мигратор.
    private final GenericKeysetChunkMigrator chunkMigrator;

    /**
     * Конструктор раннера.
     */
    public DatabaseMigrationRunner(DataAnonymizationMigrationService migrationService, GenericKeysetChunkMigrator chunkMigrator) {
        this.migrationService = migrationService;
        this.chunkMigrator = chunkMigrator;
    }

    /**
     * Запуск миграции после старта приложения.
     */
    @Override
    public void run(String... args) throws Exception {
        log.debug("[RUNNER][START] Initiating Source->Target database migration and anonymization...");
        try {
            migrationService.executeAnonymizationMigration();
            log.debug("[RUNNER][FINISH] Migration and anonymization completed successfully.");
        } catch (Exception ex) {
            log.error("[RUNNER][FAIL] Migration aborted due to critical error: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Обработчик PreDestroy для плавной остановки.
     */
    @PreDestroy
    public void onShutdown() {
        log.info("[RUNNER][SHUTDOWN] Application shutdown signal detected. Triggering graceful stop...");
        chunkMigrator.requestGracefulShutdown();
    }

}

