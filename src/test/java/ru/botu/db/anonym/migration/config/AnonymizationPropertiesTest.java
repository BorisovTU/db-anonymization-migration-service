// Тест 100% покрытия геттеров, сеттеров и конструкторов конфигурационных классов POJO.

package ru.botu.db.anonym.migration.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;

/**
 * Тестирование инкапсуляции параметров конфигурации.
 */
public class AnonymizationPropertiesTest {

    /**
     * Проверка всех методов доступа класса AnonymizationProperties.
     */
    @Test
    @DisplayName("Проверка конструкторов и геттеров/сеттеров AnonymizationProperties")
    public void testPropertiesAccessors() {
        AnonymizationProperties props = new AnonymizationProperties();
        props.setSalt("salt1");
        props.setBatchSize(500);
        props.setConcurrencyLimit(8);
        props.setEmailDomain("local.test");
        List<AnonymizationProperties.TableRuleConfig> tableList = new ArrayList<>();
        props.setTables(tableList);
        Assertions.assertThat(props.getSalt()).isEqualTo("salt1");
        Assertions.assertThat(props.getBatchSize()).isEqualTo(500);
        Assertions.assertThat(props.getConcurrencyLimit()).isEqualTo(8);
        Assertions.assertThat(props.getEmailDomain()).isEqualTo("local.test");
        Assertions.assertThat(props.getTables()).isSameAs(tableList);
        AnonymizationProperties allArgs = new AnonymizationProperties("salt2", 1000, 16, "corp.test", tableList);
        Assertions.assertThat(allArgs.getSalt()).isEqualTo("salt2");
        Assertions.assertThat(allArgs.getBatchSize()).isEqualTo(1000);
        Assertions.assertThat(allArgs.getConcurrencyLimit()).isEqualTo(16);
        Assertions.assertThat(allArgs.getEmailDomain()).isEqualTo("corp.test");
        Assertions.assertThat(allArgs.getTables()).isSameAs(tableList);
    }

    /**
     * Проверка методов TableRuleConfig и ColumnRuleConfig.
     */
    @Test
    @DisplayName("Проверка конструкторов и геттеров/сеттеров TableRuleConfig и ColumnRuleConfig")
    public void testTableAndColumnRuleConfigs() {
        AnonymizationProperties.ColumnRuleConfig col = new AnonymizationProperties.ColumnRuleConfig();
        col.setColumnName("email");
        col.setStrategy(AnonymizationStrategy.EMAIL);
        Assertions.assertThat(col.getColumnName()).isEqualTo("email");
        Assertions.assertThat(col.getStrategy()).isEqualTo(AnonymizationStrategy.EMAIL);
        AnonymizationProperties.ColumnRuleConfig colAllArgs = new AnonymizationProperties.ColumnRuleConfig("phone", AnonymizationStrategy.PHONE);
        Assertions.assertThat(colAllArgs.getColumnName()).isEqualTo("phone");
        Assertions.assertThat(colAllArgs.getStrategy()).isEqualTo(AnonymizationStrategy.PHONE);
        AnonymizationProperties.TableRuleConfig table = new AnonymizationProperties.TableRuleConfig();
        table.setTableName("users");
        table.setIdColumn("id");
        table.setIdType("UUID");
        List<AnonymizationProperties.ColumnRuleConfig> cols = List.of(colAllArgs);
        table.setColumns(cols);
        Assertions.assertThat(table.getTableName()).isEqualTo("users");
        Assertions.assertThat(table.getIdColumn()).isEqualTo("id");
        Assertions.assertThat(table.getIdType()).isEqualTo("UUID");
        Assertions.assertThat(table.getColumns()).isSameAs(cols);
        AnonymizationProperties.TableRuleConfig tableAllArgs = new AnonymizationProperties.TableRuleConfig("orders", "order_id", "BIGINT", cols);
        Assertions.assertThat(tableAllArgs.getTableName()).isEqualTo("orders");
        Assertions.assertThat(tableAllArgs.getIdColumn()).isEqualTo("order_id");
        Assertions.assertThat(tableAllArgs.getIdType()).isEqualTo("BIGINT");
        Assertions.assertThat(tableAllArgs.getColumns()).isSameAs(cols);
    }

}

