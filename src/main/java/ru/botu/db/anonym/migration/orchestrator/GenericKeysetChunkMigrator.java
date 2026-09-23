// Универсальный исполнитель миграции чанков с контролем транзакций в Target DB.

package ru.botu.db.anonym.migration.orchestrator;

import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.repository.MigrationCheckpointRepository;
import ru.botu.db.anonym.migration.step.TableMigrationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Универсальный раннер Keyset-миграции с поддержкой Graceful Shutdown.
 */
@Component
public class GenericKeysetChunkMigrator {

    // Логгер класса.
    private static final Logger log = LoggerFactory.getLogger(GenericKeysetChunkMigrator.class);

    // Репозиторий чекпоинтов Target DB.
    private final MigrationCheckpointRepository checkpointRepository;

    // Транзакционный шаблон Target DB.
    private final TransactionTemplate targetTransactionTemplate;

    // Параметры приложения.
    private final AnonymizationProperties properties;

    // Флаг корректной остановки.
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /**
     * Конструктор процессора.
     */
    public GenericKeysetChunkMigrator(MigrationCheckpointRepository checkpointRepository, TransactionTemplate targetTransactionTemplate, AnonymizationProperties properties) {
        this.checkpointRepository = checkpointRepository;
        this.targetTransactionTemplate = targetTransactionTemplate;
        this.properties = properties;
    }

    /**
     * Запрос плавной остановки.
     */
    public void requestGracefulShutdown() {
        log.warn("[MIGRATOR][SHUTDOWN_REQUESTED] Graceful shutdown flag activated. Finishing current chunks...");
        this.shutdownRequested.set(true);
    }

    /**
     * Универсальный цикл миграции таблицы с параметризованной сигнатурой <ID, T>.
     */
    public <ID, T> long migrateTable(TableMigrationStep<ID, T> step, String contextId) {
        String tableName = step.tableName();
        int batchSize = properties.getBatchSize();
        ID lastSeenId = checkpointRepository.getLastProcessedId(tableName, contextId)
            .flatMap(step::parseCheckpoint)
            .orElse(null);
        log.debug("[{}] Starting Source->Target migration for table {} from checkpoint {}", contextId, tableName, lastSeenId);
        boolean hasMore = true;
        long totalRowsProcessed = 0L;
        long taskStartTime = System.currentTimeMillis();
        while (hasMore && !shutdownRequested.get()) {
            final ID currentLastSeen = lastSeenId;
            List<T> rawChunk = step.fetchChunk(currentLastSeen, batchSize, contextId);
            if (rawChunk.isEmpty()) {
                log.debug("[{}] Table {} read complete from Source DB. Reached end of data.", contextId, tableName);
                break;
            }
            List<T> anonymizedChunk = step.anonymizeChunk(rawChunk, contextId);
            ID nextLastSeen = step.extractId(rawChunk.getLast());
            String serializedCheckpoint = step.serializeCheckpoint(nextLastSeen);
            targetTransactionTemplate.executeWithoutResult(status -> {
                step.updateChunk(anonymizedChunk, contextId);
                checkpointRepository.saveCheckpoint(tableName, serializedCheckpoint, contextId);
            });
            lastSeenId = nextLastSeen;
            totalRowsProcessed += rawChunk.size();
            if (rawChunk.size() < batchSize) {
                hasMore = false;
            }
        }
        if (shutdownRequested.get()) {
            log.warn("[{}] Table {} migration paused due to Graceful Shutdown at ID={}", contextId, tableName, lastSeenId);
        }
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - taskStartTime);
        double rowsPerSec = (totalRowsProcessed * 1000.0) / elapsedMs;
        log.debug("[{}] Table {} migrated to Target: {} rows in {} ms (avg speed: {} rows/sec)", contextId, tableName, totalRowsProcessed, elapsedMs, String.format("%.2f", rowsPerSec));
        return totalRowsProcessed;
    }

}

