[WayneHays/cloud-file-storage](https://github.com/WayneHays/cloud-file-storage)

## ХОРОШО

1. **Upload pipeline через Chain of Responsibility с rollback-механизмом** — `ValidateStep → ReserveQuotaStep → StorageUploadStep → SaveMetadataStep → CreateDirectoriesStep`. Каждый шаг реализует `execute()` и опционально `rollback()`. `ResourceUploadService` при ошибке итерирует выполненные шаги в обратном порядке и откатывает каждый. Для учебного проекта это production-ready решение: загрузка атомарна с точки зрения квоты, хранилища и метаданных.

2. **Трёхуровневая абстракция хранилища** — `MinioResourceStorage` реализует `ResourceStorageApi` (низкоуровневые MinIO-операции), `ResourceStorageService` реализует `ResourceStorageServiceApi` (разрешает ключ хранения по `userId`) и именно этот интерфейс инжектируют бизнес-сервисы. Вся MinIO-специфика изолирована за двумя слоями абстракции: при смене SDK нужно переписать только `MinioResourceStorage`.

3. **`DownloadResult` как sealed interface с двумя вариантами** — `File` и `Archive` позволяют исчерпывающий `switch` в `ResourceController` без `instanceof`. Это современный идиоматический Java 17, который делает тип-ошибку невозможной на этапе компиляции.

4. **Event-driven инициализация квоты через `ApplicationEventPublisher`** — `UserService` инжектирует `ApplicationEventPublisher` и публикует `UserRegisteredEvent` после регистрации. `StorageQuotaInitializer` слушает событие через `@EventListener` и создаёт квоту. `UserService` не зависит от `StorageQuotaService` и ничего о нём не знает. Правильный выбор интерфейса — `ApplicationEventPublisher` вместо `ApplicationContext` — показывает понимание принципа минимальной зависимости.

5. **`@ConfigurationProperties` с `@Validated` на всех конфигурационных классах** — `MinioStorageProperties`, `MinioSecurityProperties`, `RateLimitProperties`, `CleanupProperties`, `ResourceLimitsProperties`, `StorageQuotaProperties`, `ExecutorProperties` — все с Bean Validation аннотациями, включая `@DurationMin`/`@DurationMax` и `@AssertTrue` для кросс-field проверок. Некорректная конфигурация приводит к падению приложения на старте, а не в рантайме.

6. **Все сервисы скрыты за интерфейсами** — `ResourceUploadServiceApi`, `ResourceDeletionServiceApi`, `ResourceDownloadServiceApi`, `DirectoryServiceApi`, `ResourceStorageServiceApi`, `StorageQuotaServiceApi` и другие. Контроллеры и сервисы-потребители зависят от абстракций, а не от реализаций. DIP соблюдён корректно.

7. **Атомарный `UPDATE` для операции move через нативный запрос** — один SQL-запрос обновляет `path`, `normalized_path`, `parent_path` и `name` через `SUBSTRING` и `CASE WHEN` сразу для корневого ресурса и всего поддерева. Нет итерации по ресурсам в Java, нет N+1 запросов.

8. **Resilience4j с retry и circuit breaker для MinIO** — `getObject()`, `deleteObject()`, `deleteList()` аннотированы `@Retry` и `@CircuitBreaker`. Transient-ошибки (`ResourceStorageTransientException`) реплицируются, фатальные ошибки нет. Конфигурация вынесена в `application.yaml`.

9. **Streaming download через `StreamingResponseBody`** — файл и ZIP-архив пишутся напрямую в `OutputStream` HTTP-ответа без загрузки в heap. Это корректное решение для файлового сервиса, где размер ответа может быть сотни мегабайт.

10. **Система квот с pessimistic locking и reconciliation** — `StorageQuota` хранит `usedSpace` и `storageLimit` на каждого пользователя. `reserveSpace()` захватывает pessimistic lock на строку пользователя (`findByUserIdWithLock`), что исключает превышение лимита при конкурентных загрузках. `releaseSpace()` защищён от ухода в минус через `Math.max(0, ...)`. Отдельный `StorageQuotaReconciliationJob` периодически пересчитывает фактическое потребление из хранилища и корректирует счётчик, если тот разошёлся после сбоя. `CleanupService` батч-освобождает квоту после удаления файлов. Для учебного проекта это нетривиальное решение: большинство ограничивается простой проверкой без учёта гонок и дрейфа счётчика.

11. **MDC-фильтр для трассировки запросов по userId** — `MdcFilter` extends `OncePerRequestFilter` кладёт `userId` в MDC до начала обработки запроса, после чего каждая строка лога автоматически содержит идентификатор пользователя. `finally { MDC.clear() }` гарантирует очистку контекста после запроса — без этого поток из thread pool унёс бы чужой `userId` в следующий запрос. Это production-паттерн для структурированного логирования, который большинство пропускает.

12. **Единая иерархия исключений через `ApplicationException`** — все доменные и инфраструктурные исключения проекта наследуют от одного базового класса: `UserAlreadyExistsException`, `ResourceNotFoundException`, `ResourceAlreadyExistsException`, `QuotaLimitException`, `QuotaNotFoundException`, `InvalidMoveException`, `ArchiveException`, `UploadValidationException`. `GlobalExceptionHandler` перехватывает весь граф наследования единственным методом `@ExceptionHandler(ApplicationException.class)` и транслирует в `ErrorDto`. Добавление нового типа ошибки не требует правки обработчика — достаточно унаследовать от `ApplicationException`.

---

## ЗАМЕЧАНИЯ

### общая структура

1. DTO-пакеты — в каждом домене своя конвенция, единого правила нет.

В проекте одновременно используются четыре подхода:
- `core/user/api/dto/` — request DTOs рядом с контроллером, но `core/user/dto/response/UserDto` вынесен в другое место
- `core/metadata/dto/` — плоский микс: `DirectoryRowDto`, `FileRowDto` (внутренние для batch-операций), `ResourceMetadataDto` (ответ сервиса) — все в одной папке без разделения
- `files/dto/internal/` + `files/dto/response/` — разбивка по типу, но оба вида далеко от использующего кода
- `files/api/resource/dto/` + `files/api/directory/dto/` — request DTOs снова рядом с контроллерами

В итоге request и response одного эндпоинта живут в разных ветках, `UploadObjectDto` и `DownloadResult` оторваны от своих операций, а вопрос "куда положить новый DTO" не имеет очевидного ответа.

**Рекомендация:** Прими одну конвенцию и примени везде: HTTP-запросы — `*Request` в `dto/request/` рядом с контроллером, HTTP-ответы — `*Response` в `dto/response/` рядом с контроллером, внутренние DTO между сервисами — `*Dto` в корне пакета операции, которому они принадлежат. 

2. `ApplicationException` — доменный базовый класс лежит в `infrastructure.errorhandling`, отчего `core` зависит от `infrastructure`.

Все доменные исключения импортируют `ApplicationException` из `infrastructure`. Это нарушение послойной архитектуры: зависимости должны идти в одну сторону — `infrastructure` знает о `core`, но не наоборот. Кроме того, пакет `errorhandling` — нестандартное название (обычно `exception`).

**Рекомендация:** Перемести `ApplicationException`, `GlobalExceptionHandler` и `ErrorDto` в `core` (например, `core/exception/`). Переименуй пакет в `exception`.

3. `ResourceMetadataMapper#toDirectoryEntity()`, `BatchInsertMapper#toFileRow()`, `BatchInsertMapper#toDirectoryRow()` — основные маппинг-методы реализованы как `default`.

Когда основной метод `default`, MapStruct его не трогает: нет генерации кода и нет предупреждений о непроммапленных полях при добавлении новых полей в entity. `default` в MapStruct предназначен для `@Named`-хелперов, которые вызываются через `qualifiedByName`.

**Рекомендация:** Сделай основные методы абстрактными с `@Mapping`, PathUtils-вызовы вынеси в `@Named`-хелперы:

```java
// BatchInsertMapper.java
@Mapper(componentModel = "spring")
interface BatchInsertMapper {

    @Mapping(source = "fullPath", target = "path")
    @Mapping(source = "fullPath", target = "normalizedPath", qualifiedByName = "normalizePath")
    @Mapping(source = "fullPath", target = "parentPath",     qualifiedByName = "normalizeParentPath")
    @Mapping(source = "fullPath", target = "name",           qualifiedByName = "extractName")
    FileRowDto toFileRow(UploadObjectDto uploadObject);

    @Mapping(source = "path", target = "path")
    @Mapping(source = "path", target = "normalizedPath", qualifiedByName = "normalizePath")
    @Mapping(source = "path", target = "parentPath",     qualifiedByName = "normalizeParentPath")
    @Mapping(source = "path", target = "name",           qualifiedByName = "extractName")
    DirectoryRowDto toDirectoryRow(String path);

    List<FileRowDto>      toFileRows(List<UploadObjectDto> uploadObjects);
    List<DirectoryRowDto> toDirectoryRows(Set<String> paths);

    @Named("normalizePath")
    default String normalizePath(String path) {
        return PathUtils.normalizePath(path);
    }

    @Named("normalizeParentPath")
    default String normalizeParentPath(String path) {
        return PathUtils.normalizePath(PathUtils.extractParentPath(path));
    }

    @Named("extractName")
    default String extractName(String path) {
        return PathUtils.extractName(path);
    }
}
```

---

### пакет /core/metadata

1. `ResourceMetadata` — class-level `@Setter` генерирует публичные сеттеры для identity-полей.

`setId()`, `setUserId()`, `setStorageKey()` не должны быть публичными — эти поля не меняются после создания. Та же проблема в `StorageQuota` и `AuditableEntity`: `setCreatedAt()` публичен, хотя поле помечено `updatable = false`.

**Рекомендация:** Убери `@Setter` с уровня класса, оставь только на полях, которые меняются в бизнес-операциях: в `ResourceMetadata` это `markedForDeletion` и поля, обновляемые при move.

2. `ResourceMetadataRepository#existsByNormalizedPath()` — не фильтрует по `markedForDeletion = false`, тогда как `findByNormalizedPath()` фильтрует.

При сбое MinIO в середине удаления запись остаётся в БД с `markedForDeletion = true`. После этого `existsByPath()` → `existsByNormalizedPath()` вернёт `true`, и операция move к тому же пути будет ошибочно отклонена до следующего прогона cleanup.

**Рекомендация:** Добавь `AND r.markedForDeletion = false` в `existsByNormalizedPath()`.

3. `ResourceMetadataRepositoryCustomImpl`, `StorageQuotaRepositoryCustomImpl` — нет Spring-аннотаций, механизм создания неочевиден.

Оба класса создаются Spring Data JPA fragment-механизмом по naming convention, а не компонент-сканированием. Следствие: `@Transactional` на их методах не работает (нет AOP-прокси). При этом JdbcTemplate здесь в большинстве случаев не нужен.

**Рекомендация:** Удали fragment-классы и перейди на стандартные Spring Data JPA механизмы:

- `findMissingPaths` — запроси существующие пути через JPQL, вычти в Java:
```java
@Query("SELECT r.normalizedPath FROM ResourceMetadata r WHERE r.userId = :userId AND r.normalizedPath IN :paths AND r.markedForDeletion = false")
Set<String> findExistingNormalizedPaths(@Param("userId") Long userId, @Param("paths") Set<String> paths);
```
- `batchSaveDirectories` — запроси существующие директории одним JPQL-запросом, отфильтруй в Java, вставь только отсутствующие через `saveAll()`. `ON CONFLICT` не нужен: уникальный индекс не будет нарушен, потому что новые сущности уже проверены на отсутствие.
- `markFilesForDeletionAndCollectKeys` — два метода репозитория (SELECT + `@Modifying` UPDATE) + агрегация в Java внутри `@Transactional` сервиса.
- `batchSaveFiles` — единственное исключение: `@GeneratedValue(IDENTITY)` запрещает Hibernate батчить `saveAll()`. Либо смени стратегию на `SEQUENCE` и используй `saveAll()` с `hibernate.jdbc.batch_size`, либо оставь JdbcTemplate только для этого метода в явном `@Repository`-классе.
---

### пакет /core/quota

1. `StorageQuotaServiceApi` — содержит батч-методы `findAllUserIds()`, `reconcileUsedSpace()`, `batchReleaseUsedSpace()`, которые нужны только джобам.

Доменный интерфейс должен описывать бизнес-операции: reserve, release, create. Батч-методы — инфраструктурный контракт для reconciliation и cleanup. Любой класс, инжектирующий `StorageQuotaServiceApi`, видит методы, которые ему не нужны.

**Рекомендация:** Вынеси батч-методы в `StorageQuotaBatchApi`, джобы инжектируют его:

```java
public interface StorageQuotaServiceApi {
    void createStorageQuota(Long userId, long storageLimit);
    void reserveSpace(Long userId, long bytes);
    void releaseSpace(Long userId, long bytes);
}

public interface StorageQuotaBatchApi {
    Page<Long> findAllUserIds(Pageable pageable);
    void reconcileUsedSpace(List<Long> userIds);
    void batchReleaseUsedSpace(List<SpaceReleaseDto> spaceToRelease);
}

class StorageQuotaService implements StorageQuotaServiceApi, StorageQuotaBatchApi { ... }
```

2. `StorageQuotaReconciliationJob`, `CleanupJob` — реализуют `SchedulingConfigurer` вместо `@Scheduled`.

`SchedulingConfigurer` нужен, когда расписание меняется в рантайме. Здесь интервал читается один раз из `@ConfigurationProperties` — достаточно `@Scheduled(fixedRateString = ...)`:

```java
// StorageQuotaReconciliationJob
@Scheduled(fixedRateString = "${cloud-file-storage.reconciliation.interval}")
void reconcile() {
    service.reconcileStorageQuotas();
}

// CleanupJob
@Scheduled(fixedRateString = "${cloud-file-storage.cleanup.interval}")
void processDeletedFiles() {
    service.processDeletedFiles(properties.limit());
}
```

3. `StorageQuotaReconciliationService#reconcileStorageQuotas()` — `while (true)` с двумя break-ами для пагинации.

`isEmpty()`-break избыточен: пустая страница означает `hasNext() == false`, второй break сработал бы сам. Цикл можно выразить явным условием через `Pageable`:

```java
Pageable pageable = PageRequest.of(0, properties.batchSize());
Page<Long> page;
do {
    page = quotaService.findAllUserIds(pageable);
    quotaService.reconcileUsedSpace(page.getContent());
    totalUsersProcessed += page.getNumberOfElements();
    pageable = page.nextPageable();
} while (page.hasNext());
```

**Рекомендация:** Замени `while (true)` на `do-while (page.hasNext())`, передай в `findAllUserIds` объект `Pageable` вместо `int currentPage`.

---

### пакет /core/user

2. `SignInRequest`, `SignUpRequest` — дублируют аннотации валидации `@Pattern`, `@Size`, `@NotBlank` на полях `username` и `password`.

Изменение правил валидации требует правки обоих классов одновременно.

**Рекомендация:** Вынеси в составные аннотации или в конфиг, тогда не нужны кастомные аннотации

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@NotBlank @Pattern(regexp = "^\w{3,50}$")
public @interface ValidUsername {}

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@NotBlank @Size(min = 6, max = 128)
public @interface ValidPassword {}

public record SignInRequest(@ValidUsername String username, @ValidPassword String password) {}
public record SignUpRequest(@ValidUsername String username, @ValidPassword String password) {}
```

---

### пакет /files/api/resource

1. `ResourceController#contentDisposition()` — логика формирования заголовка RFC 6266 в контроллере.

Метод нельзя переиспользовать в другом контроллере без копирования. Логику кодирования нельзя протестировать без подъёма Spring MVC-контекста.

**Рекомендация:** Вынеси в `@Component`:

```java
// infrastructure/http/ContentDispositionBuilder.java
@Component
public class ContentDispositionBuilder {
    public String attachment(String filename) {
        String ascii = filename.replaceAll("[^\x20-\x7E]", "_").replace("\"", "\\\"");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
```

4. `CustomUserDetails`, `UserDetailsServiceImpl` — Spring Security адаптеры живут в доменном пакете `core/user/`.

Оба класса реализуют фреймворковые интерфейсы (`UserDetails`, `UserDetailsService`) — это инфраструктурный адаптер, не доменная логика. `UserDetailsServiceImpl` оказался в `core/user/` потому что ему нужен `UserRepository`, но `infrastructure` может зависеть от `core` — это нормально. В итоге `core` тянет зависимость на `spring-security-core`, а security-логика разбита: конфиг в `infrastructure/security/`, реализация в `core/user/`.

**Рекомендация:** Перенеси `CustomUserDetails` и `UserDetailsServiceImpl` в `infrastructure/security/`. `UserDetailsServiceImpl` продолжит инжектировать `UserRepository` из `core` — слои будут соблюдены.

---

### пакет /files/cleanup

1. `CleanupService#executeCleanup()` — при сбое `releaseQuotas()` выполнение продолжается к `deleteMetadata()`.

Файлы удаляются из MinIO и БД, но `usedSpace` не уменьшается. Пользователь получает `QuotaLimitException` до следующего прогона reconciliation. Блок `deleteFromStorage()` при ошибке делает `return 0` — `releaseQuotas()` нет.

**Рекомендация:** Добавь `return 0` в catch-блок `releaseQuotas()`:

```java
try {
    releaseQuotas(files);
} catch (Exception e) {
    log.error("Cleanup: failed to release quotas — will retry", e);
    return 0;
}
```

---

### пакет /files/operation/upload

1. `CreateDirectoriesStep#execute()` — читает пути из `context.getResult()` вместо `context.getObjects()`.

Шаг неявно зависит от того, что `StorageUploadStep` уже записал результаты в `result`. При перестановке шагов или вставке нового шага, пишущего в `result`, директории создадутся для неверных путей.

**Рекомендация:** Вычисляй пути из входных объектов:

```java
Set<String> allDirectoryPaths = context.getObjects().stream()
        .flatMap(o -> PathUtils.getAllAncestorDirectories(o.fullPath()).stream())
        .collect(Collectors.toSet());
```

---

### пакет /files/operation/move

1. `ResourceMoveService#move()` — нет `@Transactional`, TOCTOU между `validate()` и `moveMetadata()`.

Два конкурентных запроса проходят валидацию одновременно. Второй вызывает `moveMetadata()` — 0 строк обновлено — и получает `ResourceNotFoundException`, хотя на этапе проверки всё было корректно.

**Рекомендация:** Добавь `@Transactional` на `move()`.

2. `InputStreamSupplier` — повторяет Spring-интерфейс `InputStreamSource`.

`@FunctionalInterface InputStreamSupplier { InputStream get() throws IOException; }` — точная копия `org.springframework.core.io.InputStreamSource`, который уже есть в `spring-core`. Отличие только в названии метода (`get()` vs `getInputStream()`).

**Рекомендация:** Удали `InputStreamSupplier`, замени на `InputStreamSource` везде где используется.

---

### пакет /infrastructure/storage/minio

1. `MinioResourceStorage#putObject()` — нет `@Retry` и `@CircuitBreaker`, хотя `getObject()`, `deleteObject()`, `deleteList()` защищены.

Transient-ошибка при загрузке немедленно инициирует rollback пайплайна: удаляются уже загруженные файлы, освобождается квота. Пользователь получает ошибку вместо прозрачного повтора.

**Рекомендация:** Добавь аннотации по аналогии

---

### пакет /infrastructure/ratelimit

1. `RateLimitInterceptor`, `RateLimitService`, `BucketRegistry`, `RuleRegistry` — ручная реализация того, что даёт `bucket4j-spring-boot-starter`.

Выбор Bucket4j вместо Resilience4j оправдан: Resilience4j `@RateLimiter` защищает исходящие вызовы, а не входящие HTTP-запросы от пользователей. Но `bucket4j-spring-boot-starter` предоставляет per-user per-endpoint rate limiting через interceptor из коробки — `BucketRegistry`, `RuleRegistry`, `RateLimitService` и `RateLimitInterceptor` заменяются конфигурацией в `application.yaml`. Четыре класса пришлось писать и тестировать вручную.

**Рекомендация:** Добавь зависимость `com.bucket4j:bucket4j-spring-boot-starter` и перенеси логику в конфиг.

---

### пакет /test

1. `AbstractControllerTest`, `AbstractRepositoryTest` — ручная регистрация свойств через `@DynamicPropertySource` для PostgreSQL и Redis.

Начиная со Spring Boot 3.1 достаточно `@ServiceConnection` — Spring сам читает хост и порт из запущенного контейнера. `@DynamicPropertySource` — лишний бойлерплейт, который нужно синхронизировать вручную при смене конфигурации.

**Рекомендация:** Замени на `@ServiceConnection` для PostgreSQL и Redis, оставь `@DynamicPropertySource` только для MinIO:

```java
@Container
@ServiceConnection
static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

@Container
@ServiceConnection
static final GenericContainer<?> REDIS =
        new GenericContainer<>("redis:8.6.0").withExposedPorts(6379);

@Container
static final MinIOContainer MINIO = new MinIOContainer("minio/minio:...");

@DynamicPropertySource
static void minioProperties(DynamicPropertyRegistry registry) {
    registry.add("minio.security.url", MINIO::getS3URL);
    registry.add("minio.security.access-key", MINIO::getUserName);
    registry.add("minio.security.secret-key", MINIO::getPassword);
}
```


## РЕКОМЕНДАЦИИ

1. Принять единую конвенцию именования и структуры DTO для всего проекта: HTTP-ответные классы переименовать с `*Dto` на `*Response`, внутренние DTO между сервисами оставить с `*Dto`, request-классы переложить в подпакет `dto/request/`, response-классы — в `dto/response/`. Применить конвенцию последовательно во всех пакетах: `core/user`, `core/metadata`, `core/quota`, `files/api/resource`, `files/api/directory`, `files/dto`.

2. Разделить `StorageQuotaServiceApi` на доменный интерфейс (reserve, release, create) и батч-интерфейс (`StorageQuotaBatchApi`) для reconciliation и cleanup: джобы должны зависеть от батч-абстракции, а не видеть все доменные операции с квотой.

3. Обеспечить консистентность фильтрации по `markedForDeletion = false` во всех query-методах репозитория: любой запрос, который возвращает или проверяет наличие ресурса с точки зрения пользователя, должен исключать ресурсы, помеченные на удаление.

4. Ограничить публичный API JPA-сущностей: убрать `@Setter` с уровня классов `ResourceMetadata`, `StorageQuota` и `AuditableEntity`, оставив сеттеры только для полей, которые действительно меняются в бизнес-операциях. Инициализацию в тестах (`AbstractRepositoryTest.file()`, `directory()`) перевести на пакетный конструктор или статический фабричный метод.

5. Покрыть `putObject()` в `MinioResourceStorage` теми же аннотациями `@Retry` и `@CircuitBreaker`, что и остальные методы: несимметричная защита resilience создаёт непредсказуемое поведение при сбоях именно во время загрузки — самой затратной операции.

6. Перевести `CreateDirectoriesStep` на чтение из `context.getObjects()` вместо `context.getResult()` и упаковать `List<UploadStep>` в типизированный объект `UploadPipeline`: шаги пайплайна должны зависеть от входных данных, а не от артефактов других шагов.

7. Добавить `@Transactional` на `ResourceMoveService.move()` чтобы валидация и обновление выполнялись в единой транзакции и устранить TOCTOU-гонку между проверкой пути и его фактическим изменением.

8. Переписать `default`-методы в маперах через `@Named` + `qualifiedByName`: `ResourceMetadataMapper.toDirectoryEntity()`, `BatchInsertMapper.toFileRow()` и `toDirectoryRow()` должны быть абстрактными маппинг-методами с `@Mapping`-аннотациями, а PathUtils-вызовы — вынесены в `@Named`-хелперы. Это даст compile-time проверку непроммапленных полей и сохранит логику внутри маппера.

9. Добавить `return 0` в catch-блок `releaseQuotas()` в `CleanupService.executeCleanup()`: при сбое освобождения квоты метаданные не должны удаляться, иначе пользователь теряет освобождённое место до следующего прогона reconciliation.

10. Удали fragment-классы `ResourceMetadataRepositoryCustomImpl` и `StorageQuotaRepositoryCustomImpl`: `findMissingPaths` заменяется JPQL-запросом + Java set difference, `batchSaveDirectories` — `@Modifying @Query(nativeQuery = true)` в цикле, `markFilesForDeletionAndCollectKeys` — двумя JPQL-методами + агрегацией в сервисе. Для `batchSaveFiles` смени `GenerationType.IDENTITY` на `SEQUENCE` и используй `saveAll()` с `hibernate.jdbc.batch_size` — так Hibernate сможет батчить INSERT-ы.
---

## ИТОГ

Знание Spring — на высоком уровне. Система квот с pessimistic locking и reconciliation-джобом, Bucket4j per-user rate limiting, Resilience4j с разделением transient и fatal ошибок, event-driven инициализация через `ApplicationEventPublisher`, streaming download без буферизации в heap, upload pipeline с Chain of Responsibility и rollback, MDC-фильтр для трассировки — это выбор правильных инструментов и понимание того, как они работают

Слабое место — архитектурная дисциплина. `core` зависит от `infrastructure`. Spring Security адаптеры живут в доменном пакете. DTO-структура в каждом пакете следует своей конвенции: где-то плоский микс, где-то `response/`/`internal/`, где-то подпапки только для request. Доменные сервисы знают о HTTP-контракте (`SignUpRequest`). Джобовые методы загрязняют доменный интерфейс. Нет ощущения единого архитектурного решения — каждый пакет устроен по-своему.

Конкретные шаги: перенести `ApplicationException` в `core`, договориться об одной конвенции DTO и применить её везде, вынести Security-адаптеры в `infrastructure`, разделить доменные и батч-интерфейсы. Знания инструментов уже достаточно, чтобы писать production-код — не хватает привычки соблюдать одни и те же правила во всём проекте.
