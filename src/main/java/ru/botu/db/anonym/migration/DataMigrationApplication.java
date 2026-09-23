// Главный класс приложения миграции между БД источником и приемником.

package ru.botu.db.anonym.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Входная точка запуска сервиса с конфигурацией Dual DataSource.
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@ConfigurationPropertiesScan
public class DataMigrationApplication {

    /**
     * Запуск приложения.
     */
    public static void main(String[] args) {
        SpringApplication.run(DataMigrationApplication.class, args);
    }

}

