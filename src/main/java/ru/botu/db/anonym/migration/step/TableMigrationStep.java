// Интерфейс полиморфного шага миграции отдельной таблицы (SOLID OCP).

package ru.botu.db.anonym.migration.step;

import java.util.List;
import java.util.Optional;

/**
 * Контракт выполнения чанковой миграции таблицы из Source в Target с параметризованными типами ID и T.
 */
public interface TableMigrationStep<ID, T> {

    /**
     * Имя целевой таблицы БД.
     */
     String tableName();

    /**
     * Парсинг строкового чекпоинта в типизированный ID.
     */
     Optional<ID> parseCheckpoint(String rawCheckpoint);

    /**
     * Сериализация типизированного ID в строку для сохранения чекпоинта.
     */
     String serializeCheckpoint(ID id);

    /**
     * Извлечение первичного ключа из записи.
     */
     ID extractId(T record);

    /**
     * Выборка порции данных из Source DB методом Keyset.
     */
     List<T> fetchChunk(ID lastSeenId, int batchSize, String contextId);

    /**
     * Обезличивание порции записей по правилам в памяти JVM.
     */
     List<T> anonymizeChunk(List<T> chunk, String contextId);

    /**
     * Пакетная вставка обезличенных данных в Target DB.
     */
     void updateChunk(List<T> chunk, String contextId);

}

