package ru.botu.db.anonym.migration.config;

/**
 * Стратегии детерминированного обезличивания данных колонок.
 */
public enum AnonymizationStrategy {
    FULL_NAME,
    PHONE,
    EMAIL,
    FILE_PATH,
    UUID_FK,
    AS_IS
}
