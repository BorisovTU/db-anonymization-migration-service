// Тест 100% покрытия DatabaseMigrationRunner, включая перехват исключений и хук PreDestroy.

package ru.botu.db.anonym.migration.runner;

import ru.botu.db.anonym.migration.orchestrator.GenericKeysetChunkMigrator;
import ru.botu.db.anonym.migration.service.DataAnonymizationMigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.assertj.core.api.Assertions;

/**
 * Тестирование жизненного цикла запуска и остановки раннера.
 */
public class DatabaseMigrationRunnerTest {

    // Мок сервиса.
    private DataAnonymizationMigrationService migrationService;

    // Мок мигратора.
    private GenericKeysetChunkMigrator chunkMigrator;

    // Тестируемый раннер.
    private DatabaseMigrationRunner runner;

    /**
     * Настройка перед тестами.
     */
    @BeforeEach
    public void setUp() {
        migrationService = Mockito.mock(DataAnonymizationMigrationService.class);
        chunkMigrator = Mockito.mock(GenericKeysetChunkMigrator.class);
        runner = new DatabaseMigrationRunner(migrationService, chunkMigrator);
    }

    /**
     * Проверка успешного запуска run().
     */
    @Test
    @DisplayName("run() успешно вызывает executeAnonymizationMigration")
    public void testRunSuccess() throws Exception {
        runner.run();
        Mockito.verify(migrationService, Mockito.times(1)).executeAnonymizationMigration();
    }

    /**
     * Проверка выброса исключения в блоке catch метода run().
     */
    @Test
    @DisplayName("run() пробрасывает исключение при сбое сервиса")
    public void testRunFailurePropagatesException() {
        Mockito.doThrow(new IllegalStateException("Fatal migration failure"))
         .when(migrationService).executeAnonymizationMigration();
        Assertions.assertThatThrownBy(() -> runner.run())
         .isInstanceOf(IllegalStateException.class)
         .hasMessage("Fatal migration failure");
    }

    /**
     * Проверка хука onShutdown (@PreDestroy).
     */
    @Test
    @DisplayName("onShutdown() активирует запрос плавной остановки в миграторе")
    public void testOnShutdownInvokesGracefulStop() {
        runner.onShutdown();
        Mockito.verify(chunkMigrator, Mockito.times(1)).requestGracefulShutdown();
    }

}

