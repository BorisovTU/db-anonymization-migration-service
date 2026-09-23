package ru.botu.db.anonym.migration.config;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Конфигурация изолированных пулов соединений Source DB (чтение) и Target DB (запись/транзакции).
 */
@Configuration
public class DataSourceConfig {

    /**
     * Источник данных БД-источника (Source DB).
     */
    @Bean(name = "sourceDataSource")
    @ConfigurationProperties(prefix = "datasource.source")
    public HikariDataSource sourceDataSource() {
        return new HikariDataSource();
    }

    /**
     * JDBC шаблон для чтения из БД-источника.
     */
    @Bean(name = "sourceJdbcTemplate")
    public JdbcTemplate sourceJdbcTemplate(@Qualifier("sourceDataSource") DataSource sourceDataSource) {
        return new JdbcTemplate(sourceDataSource);
    }

    /**
     * Источник данных целевой БД (Target DB).
     */
    @Primary
    @Bean(name = "targetDataSource")
    @ConfigurationProperties(prefix = "datasource.target")
    public HikariDataSource targetDataSource() {
        return new HikariDataSource();
    }

    /**
     * JDBC шаблон для записи в целевую БД.
     */
    @Primary
    @Bean(name = "targetJdbcTemplate")
    public JdbcTemplate targetJdbcTemplate(@Qualifier("targetDataSource") DataSource targetDataSource) {
        return new JdbcTemplate(targetDataSource);
    }

    /**
     * Менеджер транзакций для целевой БД.
     */
    @Primary
    @Bean(name = "targetTransactionManager")
    public PlatformTransactionManager targetTransactionManager(@Qualifier("targetDataSource") DataSource targetDataSource) {
        return new DataSourceTransactionManager(targetDataSource);
    }

    /**
     * Шаблон чанковых транзакций целевой БД.
     */
    @Bean(name = "targetTransactionTemplate")
    public TransactionTemplate targetTransactionTemplate(@Qualifier("targetTransactionManager") PlatformTransactionManager targetTransactionManager) {
        return new TransactionTemplate(targetTransactionManager);
    }

    /**
     * Интеграция Liquibase для управления структурой исключительно Target DB.
     */
    @Bean
    public SpringLiquibase liquibase(@Qualifier("targetDataSource") DataSource targetDataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(targetDataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        return liquibase;
    }
}
