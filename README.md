```markdown
# Архитектурное руководство и документация: Сервис детерминированного обезличивания и миграции БД (ETL)

## Оглавление
1. [Общее описание и бизнес-цели](#1-общее-описание-и-бизнес-цели)
2. [Архитектурная модель: Dual DataSource (Source → Target)](#2-архитектурная-модель-dual-datasource-source--target)
3. [Алгоритмы детерминированного маскирования](#3-алгоритмы-детерминированного-маскирования)
   * 3.1. Сохранение ссылочной целостности (PK/FK)
   * 3.2. Сохранение кардинальности и распределения дубликатов
   * 3.3. Обработка структурных аномалий и ссылок на файлы
4. [Инфраструктура и начальные данные](#4-инфраструктура-и-начальные-данные)
5. [Пошаговый процесс выполнения миграции](#5-пошаговый-процесс-выполнения-миграции)
6. [Подробное описание классов и компонентов системы](#6-подробное-описание-классов-и-компонентов-системы)
7. [Конфигурация (application.yml)](#7-конфигурация-applicationyml)
8. [Отказоустойчивость и мониторинг](#8-отказоустойчивость-и-мониторинг)
9. [Руководство по локальному запуску и проверке](#9-руководство-по-локальному-запуску-и-проверке)
```
---

## 1. Общее описание и бизнес-цели

Сервис предназначен для регулярной выгрузки, криптографического обезличивания (псевдонимизации) и загрузки продуктовых баз данных PostgreSQL в тестовые контуры (DEV, TEST, STAGE, UAT).

### Ключевые решаемые задачи:
1. **Соответствие 152-ФЗ / GDPR:** Полное исключение утечки реальных персональных данных (ФИО, телефоны, email, пути к файлам документов) в тестовые среды.
2. **Гарантия валидности тестирования:**
   * Все внешние ключи (`FOREIGN KEY`) остаются валидными: если профиль пользователя был связан с 10 вложениями, обезличенный профиль связывается с теми же 10 обезличенными записями вложений.
   * Частота дубликатов сохраняется: одинаковые исходные данные превращаются в одинаковые маскированные значения, сохраняя селективность индексов B-Tree и корректность планов запросов оптимизатора PostgreSQL.
   * Битые ссылки на удалённые файлы сохраняют структуру каталогов и расширений (`.pdf`, `.docx`), позволяя тестировать негативные сценарии в бизнес-логике.

---

## 2. Архитектурная модель: Dual DataSource (Source → Target)

Сервис работает по модели **ETL (Extract - Transform - Load)** с жестким физическим и логическим разделением контуров:

```
┌─────────────────────────┐             ┌────────────────────────────────────────────────────────┐             ┌─────────────────────────┐
│        SOURCE DB        │             │           МИГРАЦИОННЫЙ СЕРВИС (SPRING BOOT)            │             │        TARGET DB        │
│    (Прод / Источник)    │             │                                                        │             │   (Тестовый приемник)   │
│       Порт: 5432        │             │                                                        │             │       Порт: 5433        │
├─────────────────────────┤             ├────────────────────────────────────────────────────────┤             ├─────────────────────────┤
│ customer_profiles       │ ──Keyset──► │ [In-Memory Anonymizer Engine]                          │ ──Upsert──► │ customer_profiles       │
│ customer_attachments    │   SELECT    │  - HMAC-SHA256 + Project Salt                          │    Batch    │ customer_attachments    │
│                         │  (Read-Only)│  - ThreadLocal Mac (No Lock Contention)                │   (Write)   │ migration_checkpoints   │
│                         │             │  - Virtual Threads Concurrency (Java 23/25)            │             │                         │
└─────────────────────────┘             └────────────────────────────────────────────────────────┘             └─────────────────────────┘
```

1. **Source DB (Источник):**
   * Подключение открыто **строго в режиме Read-Only**.
   * Запросы на выборку выполняются методом **Keyset-пагинации** (`WHERE id > :lastSeenId ORDER BY id ASC LIMIT :batchSize`) со сложностью $O(\log N)$.
   * На источнике не создаются блокировки и не генерируется мусор MVCC (`dead tuples`).
2. **Target DB (Приемник):**
   * Схема и сервисные таблицы автоматически создаются через **Liquibase** до старта переноса.
   * Данные записываются пакетами через `INSERT INTO ... ON CONFLICT (...) DO UPDATE` с оптимизацией драйвера `reWriteBatchedInserts=true`.
   * Каждая порция фиксируется в отдельной короткой транзакции с сохранением контрольной точки в таблице `migration_checkpoints`.

---

## 3. Алгоритмы детерминированного маскирования

В основе механизма обезличивания лежит криптографический хеш-алгоритм **HMAC-SHA256**, инициализируемый секретной солью проекта (`anonymization.salt`).

### 3.1. Сохранение ссылочной целостности (PK/FK)
Для внешних ключей типа `UUID` вычисляется HMAC от строкового представления исходного идентификатора. Первые 16 байт результата формируют старшие (`mostSigBits`) и младшие (`leastSigBits`) биты нового детерминированного `UUID`:
$$\text{Source UUID} \xrightarrow{\text{HMAC-SHA256}(\text{Salt})} \text{Deterministic Target UUID}$$
Поскольку алгоритм чистый (pure function), одинаковый `UUID` клиента в таблице `customer_profiles` и в таблице `customer_attachments` превратится в идентичный новый `UUID`. Внешний ключ не разрушается.

### 3.2. Сохранение кардинальности и распределения дубликатов
* Если в исходной базе Иванов Иван Иванович встречается 5 раз, после миграции во всех 5 строках появится одно и то же псевдонимизированное значение: `Anonymized_User_<hex_hash>`.
* Одинаковые номера телефонов преобразуются в одинаковые маски вида `+79XXXXXXXXX`.
* Одинаковые email адреса преобразуются в одинаковые адреса вида `masked_<hex>@test-anonymized.local`.
* Различные входные значения дают различные маски (отсутствие коллизий гарантируется криптостойкостью SHA-256).

### 3.3. Обработка структурных аномалий и ссылок на файлы
* **Значения NULL и пустые строки:** Если поле не было заполнено в источнике, оно остается `NULL` в приемнике.
* **Файловые пути и битые ссылки:** При обработке путей вида `/var/storage/dead_links/missing_doc_99.docx` исходный каталог (`/var/storage/dead_links/`) и расширение (`.docx`) остаются нетронутыми, а хешируется только имя файла. Это сохраняет валидность тестов, проверяющих реакцию системы на отсутствующие архивные файлы.

---

## 4. Инфраструктура и начальные данные

Инфраструктура локального окружения разворачивается через `docker-compose.yaml`:
* **`postgres-source` (порт 5432):** База данных источника. При первом старте монтирует каталог `./docker/seed-data` и выполняет скрипт `docker/init-source-db.sql`.
* **`postgres-target` (порт 5433):** База данных приемника. Изначально пустая.
* **`docker/seed-data/*.csv`:**
  * `customer_profiles.csv` — тестовые записи профилей клиентов, включающие дубликаты персон и пустые `NULL`-поля.
  * `customer_attachments.csv` — вложения, ссылающиеся на профили через `customer_id`, включая дублирующиеся файлы и битые пути.
* Заливка в источнике осуществляется через потоковую команду PostgreSQL:
  ```sql
  COPY customer_profiles(id, full_name, phone_number, email, created_at)
  FROM '/docker-entrypoint-initdb.d/seed-data/customer_profiles.csv'
  WITH (FORMAT csv, HEADER true);
  ```

---

## 5. Пошаговый процесс выполнения миграции

1. **Старт контекста Spring Boot:**
   * Инициализируется класс `DataMigrationApplication`.
   * Валидируются параметры `AnonymizationProperties` (Bean Validation).
2. **Конфигурация источников данных (`DataSourceConfig`):**
   * Создается `sourceDataSource` с флагом `readOnly = true`.
   * Создается `targetDataSource`.
   * Запускается **Liquibase**, накатывающий чейнджлоги на целевую базу (создание таблиц `customer_profiles`, `customer_attachments`, `migration_checkpoints`).
3. **Инициализация конвейера (`DataAnonymizationMigrationService`):**
   * На основе секции `anonymization.tables` в `application.yml` создаются шаги `ConfigurableTableMigrationStep` для каждой таблицы.
4. **Запуск миграции (`DatabaseMigrationRunner`):**
   * Компонент `CommandLineRunner` инициирует процесс миграции.
5. **Параллельное выполнение через виртуальные потоки (Virtual Threads):**
   * Каждая таблица обрабатывается в отдельном виртуальном потоке через `GenericKeysetChunkMigrator.migrateTable(...)`.
6. **Чанковый цикл (Keyset-итерации):**
   * **Чтение:** Считывается последняя контрольная точка из `migration_checkpoints` целевой БД. Из `Source DB` запрашивается батч размером `batchSize` записей с условием `WHERE id > :lastSeenId ORDER BY id ASC`.
   * **Трансформация:** В памяти JVM `DeterministicAnonymizer` накладывает маски по заданным стратегиям.
   * **Запись:** В `Target DB` открывается короткая транзакция через `TransactionTemplate`. Выполняется пакетный `INSERT ... ON CONFLICT DO UPDATE`, и атомарно обновляется `migration_checkpoints`.
7. **Завершение или Graceful Shutdown:**
   * Процесс повторяется, пока в источнике есть строки.
   * При получении сигнала ОС `SIGTERM` срабатывает `@PreDestroy`, активируя флаг `shutdownRequested`. Текущий батч корректно завершается и сохраняет чекпоинт, предотвращая дублирование и потерю прогресса при рестарте.

---

## 6. Подробное описание классов и компонентов системы

### Пакет `ru.architect.migration.config`

#### 1. `DataSourceConfig`
* **Назначение:** Конфигурационный класс Spring, разделяющий контексты доступа к двум физическим базам данных.
* **Создаваемые бины:**
  * `sourceDataSource()` — пул соединений HikariCP к БД-источнику (`datasource.source`). Имеет флаг `read-only: true`.
  * `sourceJdbcTemplate()` — экземпляр `JdbcTemplate`, используемый только для селектов из источника.
  * `targetDataSource()` — первичный (`@Primary`) пул соединений к БД-приемнику (`datasource.target`).
  * `targetJdbcTemplate()` — экземпляр `JdbcTemplate` для записи в приемник.
  * `targetTransactionManager()` & `targetTransactionTemplate()` — инфраструктура транзакций целевой базы для атомарной фиксации каждого батча.
  * `liquibase()` — бин `SpringLiquibase`, привязанный к `targetDataSource`. Гарантирует актуальность схемы целевой БД до старта чтения.

#### 2. `AnonymizationStrategy`
* **Назначение:** Перечисление поддерживаемых криптографических стратегий маскирования.
* **Элементы:**
  * `FULL_NAME` — замена ФИО на псевдоним `Anonymized_User_<hex>`.
  * `PHONE` — замена номера телефона на маску формата `+79XXXXXXXXX`.
  * `EMAIL` — генерация фиктивного email в домене `test-anonymized.local`.
  * `FILE_PATH` — сохранение пути и расширения файла с маскированием имени.
  * `UUID_FK` — детерминированное псевдонимирование первичных и внешних ключей типа UUID.

#### 3. `AnonymizationProperties` (с вложенными `TableRuleConfig` и `ColumnRuleConfig`)
* **Назначение:** Типизированное Java Bean/POJO представление секции `anonymization` из `application.yml`.
* **Поля:**
  * `salt` — секретный ключ проекта.
  * `batchSize` — количество строк в одном батче (по умолчанию 2000).
  * `concurrencyLimit` — лимит параллельных задач.
  * `emailDomain` — домен для генерации тестовых почтовых ящиков.
  * `tables` — список объектов `TableRuleConfig`, определяющих имя таблицы, первичный ключ, его тип (`UUID` или `BIGINT`) и список правил для колонок (`ColumnRuleConfig`).

---

### Пакет `ru.architect.migration.engine`

#### 4. `DeterministicAnonymizer`
* **Назначение:** Потокобезопасный криптографический движок маскирования без блокировок.
* **Архитектурная деталь (`ThreadLocal<Mac>`):** Экземпляр `javax.crypto.Mac` не является потокобезопасным, а его создание через `Mac.getInstance("HmacSHA256")` на каждую строчку вызывает тяжелую синхронизацию в JVM Security Registry. Движок инициализирует `ThreadLocal<Mac>`, предоставляя каждому потоку собственный предварительно инициализированный объект без lock contention.
* **Методы:**
  * `applyStrategy(Object val, AnonymizationStrategy strategy, String contextId)` — диспетчер, применяющий нужное правило в зависимости от конфигурации колонки.
  * `anonymizeUuid(UUID sourceUuid, String contextId)` — генерация детерминированного UUID на основе первых 16 байт HMAC.
  * `anonymizeFullName(String sourceName, String contextId)` — формирование псевдонима персоны.
  * `anonymizeEmail(String sourceEmail, String contextId)` — генерация валидного email адреса.
  * `anonymizePhone(String sourcePhone, String contextId)` — детерминированное маскирование цифр телефона.
  * `anonymizeFilePath(String sourcePath, String contextId)` — выделение каталога, маскирование имени файла и склеивание с исходным расширением.

---

### Пакет `ru.architect.migration.repository`

#### 5. `DynamicTableRepository`
* **Назначение:** Репозиторий доступа к данным, изолирующий вызовы к источнику и приемнику.
* **Методы:**
  * `fetchChunkFromSource(TableRuleConfig config, Object lastSeenId, int limit, String contextId)` — формирует и выполняет Keyset SQL-запрос к БД-источнику через `sourceJdbcTemplate`:
    ```sql
    SELECT id, col1, col2 FROM table_name WHERE id > :lastSeenId ORDER BY id ASC LIMIT :limit
    ```
  * `upsertChunkToTarget(TableRuleConfig config, List<Map<String, Object>> rows, String contextId)` — формирует динамический пакетный запрос к целевой БД через `targetJdbcTemplate`:
    ```sql
    INSERT INTO table_name (id, col1, col2) VALUES (?, ?, ?)
    ON CONFLICT (id) DO UPDATE SET col1 = EXCLUDED.col1, col2 = EXCLUDED.col2
    ```

#### 6. `MigrationCheckpointRepository`
* **Назначение:** Управление контрольными точками миграции в целевой БД.
* **Методы:**
  * `getLastProcessedId(String tableName, String contextId)` — получение последнего сохраненного идентификатора для таблицы из `migration_checkpoints`.
  * `saveCheckpoint(String tableName, String lastProcessedId, String contextId)` — атомарное обновление контрольной точки (`ON CONFLICT (table_name) DO UPDATE`).

---

### Пакет `ru.architect.migration.step`

#### 7. `TableMigrationStep<ID, T>`
* **Назначение:** Обобщенный интерфейс шага миграции (SOLID Open/Closed Principle). Декларирует контракт жизненного цикла переноса сущности без привязки к конкретным типам.
* **Методы:** `tableName()`, `parseCheckpoint()`, `serializeCheckpoint()`, `extractId()`, `fetchChunk()`, `anonymizeChunk()`, `updateChunk()`.

#### 8. `ConfigurableTableMigrationStep`
* **Назначение:** Универсальная реализация интерфейса `TableMigrationStep<Object, Map<String, Object>>`, управляемая конфигурацией из `application.yml`.
* **Функции:**
  * Преобразует строковые чекпоинты в `UUID` или `Long` в зависимости от настройки `idType`.
  * Итерируется по строкам выборки и вызывает `DeterministicAnonymizer.applyStrategy` для каждой указанной в конфигурации колонки.
  * Делегирует чтение и запись в `DynamicTableRepository`.

---

### Пакет `ru.architect.migration.orchestrator`

#### 9. `GenericKeysetChunkMigrator`
* **Назначение:** Обобщенный процессор чанкового цикла миграции (SOLID DRY).
* **Функции:**
  * Содержит единственный в системе цикл `while(hasMore && !shutdownRequested.get())`.
  * Считывает стартовую позицию из `MigrationCheckpointRepository`.
  * Оборачивает сохранение данных и контрольной точки в атомарную транзакцию через `TransactionTemplate` целевой базы.
  * Замеряет скорость обработки и логирует метрики (`rows/sec`).
  * `requestGracefulShutdown()` — активирует флаг остановки при получении сигнала завершения приложения.

---

### Пакет `ru.architect.migration.service`

#### 10. `DataAnonymizationMigrationService`
* **Назначение:** Оркестратор параллельного выполнения конвейера миграции.
* **Функции:**
  * В конструкторе создает экземпляры `ConfigurableTableMigrationStep` на основе списка таблиц из `AnonymizationProperties`.
  * Метод `executeAnonymizationMigration()` запускает обработку всех шагов параллельно с использованием пула виртуальных потоков Java 23/25 (`Executors.newVirtualThreadPerTaskExecutor()`).
  * Ожидает завершения всех задач через `CompletableFuture.allOf(...)`.

---

### Пакет `ru.architect.migration.runner`

#### 11. `DatabaseMigrationRunner`
* **Назначение:** Точка интеграции с жизненным циклом Spring Boot (`CommandLineRunner`).
* **Функции:**
  * Автоматически вызывает `migrationService.executeAnonymizationMigration()` сразу после готовности контекста Spring.
  * Аннотация `@PreDestroy` перехватывает системный сигнал остановки (SIGTERM) и вызывает `chunkMigrator.requestGracefulShutdown()`.

---

## 7. Конфигурация (application.yml)

Пример полной конфигурации сервиса:

```yaml
spring:
  application:
    name: db-anonymization-migration-service
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.xml

# Настройки изолированных пулов БД
datasource:
  source:
    jdbc-url: ${SOURCE_DB_URL:jdbc:postgresql://localhost:5432/source_db}
    username: ${SOURCE_DB_USER:postgres}
    password: ${SOURCE_DB_PASS:postgres_source_pwd}
    driver-class-name: org.postgresql.Driver
    maximum-pool-size: 16
    minimum-idle: 4
    connection-timeout: 30000
    read-only: true # Защита источника от случайных изменений
  target:
    jdbc-url: ${TARGET_DB_URL:jdbc:postgresql://localhost:5433/target_db?reWriteBatchedInserts=true&stringtype=unspecified}
    username: ${TARGET_DB_USER:postgres}
    password: ${TARGET_DB_PASS:postgres_target_pwd}
    driver-class-name: org.postgresql.Driver
    maximum-pool-size: 32
    minimum-idle: 8
    connection-timeout: 30000

# Декларативные правила маскирования
anonymization:
  salt: ${ANONYMIZATION_SALT:c3VwZXItc2VjcmV0LXNhbHQtMTUydnotZ2Rwcg==}
  batch-size: 2000
  concurrency-limit: 16
  email-domain: test-anonymized.local
  tables:
    - table-name: customer_profiles
      id-column: id
      id-type: UUID
      columns:
        - column-name: full_name
          strategy: FULL_NAME
        - column-name: phone_number
          strategy: PHONE
        - column-name: email
          strategy: EMAIL
    - table-name: customer_attachments
      id-column: id
      id-type: BIGINT
      columns:
        - column-name: customer_id
          strategy: UUID_FK
        - column-name: file_storage_path
          strategy: FILE_PATH

logging:
  level:
    ru.architect.migration: DEBUG
```

---

## 8. Отказоустойчивость и мониторинг

1. **Возобновление после сбоев (Failover Resume):**
   Если процесс прерван на середине (падение пода K8s, перезагрузка хоста), при повторном запуске сервис вычитывает `last_processed_id` из `migration_checkpoints` целевой БД и продолжает чтение из источника ровно с прерванной записи.
2. **Метрики в логах:**
   Каждый этап фиксируется в логах уровня `DEBUG` в формате:
   `[adapterId][eventId][entityId] Table customer_profiles migrated to Target: 50000 rows in 1820 ms (avg speed: 27472.53 rows/sec)`

---

## 9. Руководство по локальному запуску и проверке

### Шаг 1. Запуск тестовых баз данных
```bash
docker compose down -v # Очистить предыдущие тома при необходимости
docker compose up -d
```
Убедитесь, что контейнеры запустились:
```bash
docker compose ps
```

### Шаг 2. Проверка данных в источнике (Source DB)
```bash
docker exec -it postgres-source psql -U postgres -d source_db -c "SELECT id, full_name, email FROM customer_profiles;"
```
Вы увидите исходные персонализированные данные из CSV:
```text
                  id                  |       full_name        |            email            
--------------------------------------+------------------------+-----------------------------
 a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11 | Иванов Иван Иванович   | ivanov@corp.com
 b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22 | Петров Петр Петрович   | petrov@corp.com
 c2eebc99-9c0b-4ef8-bb6d-6bb9bd380a33 | Иванов Иван Иванович   | ivanov@corp.com
 d3eebc99-9c0b-4ef8-bb6d-6bb9bd380a44 | Сидоров Алексей        | 
```

### Шаг 3. Сборка и запуск сервиса миграции
```bash
mvn clean test
mvn spring-boot:run
```

### Шаг 4. Проверка обезличенных данных в приемнике (Target DB)
Подключитесь к целевой БД (порт 5433):
```bash
docker exec -it postgres-target psql -U postgres -d target_db -c "SELECT id, full_name, email FROM customer_profiles;"
```
Результат:
```text
                  id                  |       full_name        |                  email                   
--------------------------------------+------------------------+------------------------------------------
 a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11 | Anonymized_User_a7b1c2 | masked_8f3d1b9a2c4e11fa@test-anonymized.local
 b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22 | Anonymized_User_9f4e2d | masked_3a1c7e9b0d2f88aa@test-anonymized.local
 c2eebc99-9c0b-4ef8-bb6d-6bb9bd380a33 | Anonymized_User_a7b1c2 | masked_8f3d1b9a2c4e11fa@test-anonymized.local
 d3eebc99-9c0b-4ef8-bb6d-6bb9bd380a44 | Anonymized_User_11b8ca | 
```
*Обратите внимание:*
* Строки 1 и 3 имели одинаковое ФИО и email — они превратились в **идентичные маскированные значения**.
* Строка 4 сохранила `NULL` в поле email.

### Шаг 5. Проверка сохранения связей внешних ключей (FK)
```bash
docker exec -it postgres-target psql -U postgres -d target_db -c "SELECT id, customer_id, file_storage_path FROM customer_attachments;"
```
Результат:
```text
  id  |             customer_id              |               file_storage_path               
------+--------------------------------------+-----------------------------------------------
 1001 | 5fa1c99b-4412-89cd-a01b-c12e09bb3312 | /var/storage/contracts/file_98bc12a4df871234.pdf
 1003 | 11be4a88-21cd-4ef1-bb22-3899f1124401 | /var/storage/dead_links/file_44d189bb7712ca00.docx
```
* `customer_id` детерминированно соответствует обезличенному `UUID` профиля из родительской таблицы `customer_profiles`.
* Структура каталогов и расширений файлов сохранена, имена обезличены.
```