// Тест 100% покрытия всех ветвей криптографического компонента DeterministicAnonymizer.

package ru.botu.db.anonym.migration.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import ru.botu.db.anonym.migration.config.AnonymizationProperties;
import ru.botu.db.anonym.migration.config.AnonymizationStrategy;

/**
 * Полное тестирование граничных условий, ветвлений if/else и стратегий маскирования.
 */
public class DeterministicAnonymizerComprehensiveTest {

    // Экземпляр тестируемого компонента.
    private DeterministicAnonymizer anonymizer;

    /**
     * Инициализация перед каждым тестом.
     */
    @BeforeEach
    public void setUp() {
        AnonymizationProperties props = new AnonymizationProperties("unit-test-salt", 100, 2, "mask.local", Collections.emptyList());
        this.anonymizer = new DeterministicAnonymizer(props);
    }

    /**
     * Проверка ветвления applyStrategy при передаче null.
     */
    @Test
    @DisplayName("applyStrategy возвращает null при входящем значении null")
    public void testApplyStrategyNullHandling() {
        String context = "[TEST][ANON][NULL]";
        Assertions.assertThat(anonymizer.applyStrategy(null, AnonymizationStrategy.FULL_NAME, context)).isNull();
        Assertions.assertThat(anonymizer.applyStrategy(null, AnonymizationStrategy.EMAIL, context)).isNull();
        Assertions.assertThat(anonymizer.applyStrategy(null, AnonymizationStrategy.PHONE, context)).isNull();
        Assertions.assertThat(anonymizer.applyStrategy(null, AnonymizationStrategy.FILE_PATH, context)).isNull();
        Assertions.assertThat(anonymizer.applyStrategy(null, AnonymizationStrategy.UUID_FK, context)).isNull();
    }

    /**
     * Проверка стратегии UUID_FK при передаче строкового UUID и объекта UUID.
     */
    @Test
    @DisplayName("UUID_FK корректно обрабатывает String и UUID типы")
    public void testApplyStrategyUuidVariants() {
        String context = "[TEST][ANON][UUID]";
        UUID original = UUID.randomUUID();
        Object res1 = anonymizer.applyStrategy(original, AnonymizationStrategy.UUID_FK, context);
        Object res2 = anonymizer.applyStrategy(original.toString(), AnonymizationStrategy.UUID_FK, context);
        Assertions.assertThat(res1).isInstanceOf(UUID.class);
        Assertions.assertThat(res1).isEqualTo(res2);
    }

    /**
     * Проверка граничных значений ФИО: null, пустая строка, пробелы.
     */
    @Test
    @DisplayName("anonymizeFullName сохраняет пустые строки и пробелы")
    public void testFullNameEdgeCases() {
        String context = "[TEST][ANON][NAME]";
        Assertions.assertThat(anonymizer.anonymizeFullName(null, context)).isNull();
        Assertions.assertThat(anonymizer.anonymizeFullName("", context)).isEqualTo("");
        Assertions.assertThat(anonymizer.anonymizeFullName(" ", context)).isEqualTo(" ");
        String masked = anonymizer.anonymizeFullName("Иванов Иван", context);
        Assertions.assertThat(masked).startsWith("Anonymized_User_");
    }

    /**
     * Проверка граничных значений email: null, пустая строка, пробелы.
     */
    @Test
    @DisplayName("anonymizeEmail сохраняет пустые строки и пробелы")
    public void testEmailEdgeCases() {
        String context = "[TEST][ANON][EMAIL]";
        Assertions.assertThat(anonymizer.anonymizeEmail(null, context)).isNull();
        Assertions.assertThat(anonymizer.anonymizeEmail("", context)).isEqualTo("");
        Assertions.assertThat(anonymizer.anonymizeEmail(" ", context)).isEqualTo(" ");
        String masked = anonymizer.anonymizeEmail("USER@Domain.COM", context);
        Assertions.assertThat(masked).endsWith("@mask.local");
    }

    /**
     * Проверка граничных значений номера телефона.
     */
    @Test
    @DisplayName("anonymizePhone сохраняет пустые строки и пробелы")
    public void testPhoneEdgeCases() {
        String context = "[TEST][ANON][PHONE]";
        Assertions.assertThat(anonymizer.anonymizePhone(null, context)).isNull();
        Assertions.assertThat(anonymizer.anonymizePhone("", context)).isEqualTo("");
        Assertions.assertThat(anonymizer.anonymizePhone(" ", context)).isEqualTo(" ");
        String masked = anonymizer.anonymizePhone("+79160000000", context);
        Assertions.assertThat(masked).startsWith("+79");
        Assertions.assertThat(masked.length()).isEqualTo(12);
    }

    /**
     * Проверка всех ветвей путей файлов: без слеша, без расширения, с директорией и точкой.
     */
    @Test
    @DisplayName("anonymizeFilePath корректно обрабатывает пути без слешей и без расширений")
    public void testFilePathAllBranches() {
        String context = "[TEST][ANON][PATH]";
        Assertions.assertThat(anonymizer.anonymizeFilePath(null, context)).isNull();
        Assertions.assertThat(anonymizer.anonymizeFilePath("", context)).isEqualTo("");
        Assertions.assertThat(anonymizer.anonymizeFilePath(" ", context)).isEqualTo(" ");
        String noSlashNoExt = anonymizer.anonymizeFilePath("filename", context);
        Assertions.assertThat(noSlashNoExt).startsWith("file_");
        Assertions.assertThat(noSlashNoExt).doesNotContain("/");
        Assertions.assertThat(noSlashNoExt).doesNotContain(".");
        String noSlashWithExt = anonymizer.anonymizeFilePath("contract.pdf", context);
        Assertions.assertThat(noSlashWithExt).startsWith("file_");
        Assertions.assertThat(noSlashWithExt).endsWith(".pdf");
        String withSlashNoExt = anonymizer.anonymizeFilePath("/storage/bin/archive", context);
        Assertions.assertThat(withSlashNoExt).startsWith("/storage/bin/file_");
        String fullPath = anonymizer.anonymizeFilePath("/opt/data/doc.tar.gz", context);
        Assertions.assertThat(fullPath).startsWith("/opt/data/file_");
        Assertions.assertThat(fullPath).endsWith(".gz");
    }

}

