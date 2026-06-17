[YoXaron/cloud-storage](https://github.com/YoXaron/cloud-storage)

## ХОРОШО

1. **Файлы в MinIO привязаны к UUID, а пользовательский путь живёт только в PostgreSQL** — переименование и перемещение не трогают бакет. Для S3-совместимого хранилища это сильное решение, которое убирает дорогое копирование объектов.

2. **`StreamingResponseBody` при скачивании** — одиночный файл и ZIP директории отдаются потоком, без загрузки всего содержимого в heap.

3. **Частичный уникальный индекс `uq_resource_not_failed`** — дубли активных файлов отсекаются на уровне БД, а не только проверкой `exists()` в Java.

4. **Мягкое удаление и фоновая очистка через `DataCleanupService`** — пользовательский запрос завершается быстро, физическая уборка в MinIO вынесена в отдельный сценарий.

5. **Корневая директория создаётся при регистрации в `AuthService#register()`** — метаданные пользователя появляются один раз вместе с аккаунтом, а не при первом входе.

6. **`SecurityUser#getId()`** — `userId` доступен в сервисах без дополнительного запроса к `users`.

7. **`PathUtil` закрывает path traversal, двойные слэши и пустые сегменты** — правила работы с путём собраны в одном месте.

8. **`AuthControllerIT` на Testcontainers PostgreSQL и Redis** — auth-сценарии проверяются в окружении, близком к рабочему, а не на in-memory заглушках.

---

## ЗАМЕЧАНИЯ

### пакет /config

1. В `MinioProperties` класс лежит прямо в `config`, а не в `config/properties`

Типизированные настройки MinIO смешаны с конфиг-классами `MinioConfig` и `SecurityConfig`. При росте проекта сложнее отличить бины от проперти и быстрее найти все `@ConfigurationProperties`.

**Рекомендация:**

Перенеси `MinioProperties` в подпакет `config.properties`.

### пакет /controller

1. В `ResourceController`, `DirectoryController`, `AuthController` и `UserController` нет отдельного API-интерфейса

HTTP-mapping и OpenAPI-документация живут в `@RestController`-классах. Контракт эндпоинта и его реализация не разделены: при появлении `/api/v2` придётся дублировать mapping и Swagger-описание.

**Рекомендация:**

Вынеси mapping, параметры, `@Valid` и OpenAPI-аннотации в интерфейс `*Api`, реализацию оставь тонким делегатом сервису.

```java
@RequestMapping("/api/resource")
public interface ResourceApi {

    @Operation(summary = "Get resource information")
    @GetMapping
    @GetResourceInfoDocs
    ResponseEntity<ResourceResponseDto> getResourceInfo(
            @RequestParam("path") String path,
            @AuthenticationPrincipal SecurityUser user
    );
}
```

```java
@RestController
@RequiredArgsConstructor
class ResourceController implements ResourceApi {

    private final StorageService storageService;
    private final ResourceMetadataService resourceMetadataService;

    @Override
    public ResponseEntity<ResourceResponseDto> getResourceInfo(String path, SecurityUser user) {
        return ResponseEntity.ok(resourceMetadataService.getResourceInfo(path, user.getId()));
    }
}
```

2. В `UserController#me()` используется `Principal` вместо `SecurityUser`

`ResourceController` и `DirectoryController` принимают `@AuthenticationPrincipal SecurityUser` с `getId()`. В `UserController` — базовый `Principal` только с именем. Сейчас для `/me` этого хватает, но тип principal в проекте получается разный.

**Рекомендация:**

Замени параметр на `@AuthenticationPrincipal SecurityUser user`, как в остальных контроллерах.

3. В `ResourceController`, `DirectoryController` и других методах query-параметры `path`, `query`, `from`, `to` принимаются как голые `String`

Валидация пути выполняется в `PathUtil` уже внутри сервиса. Transport-слой не отсекает невалидный ввод до бизнес-логики, а Bean Validation на границе API не задействована.

**Рекомендация:**

Введи request-record для query-параметров и валидируй их через `@Valid @ModelAttribute` в API-интерфейсе.

```java
public record ResourcePathRequest(
        @NotBlank String path
) {}
```

4. В `ResourceController#uploadResources()` список `files` не проверяется на пустоту

Клиент может отправить multipart-запрос без файлов и получить `201 Created` с пустым телом. По смыслу upload это невалидное тело запроса, которое ТЗ относит к 400.

**Рекомендация:**

Добавь проверку `files` на пустоту в начале `uploadAll()` и бросай `InvalidPathException` или отдельное исключение с кодом 400.

### пакет /docs

1. В пакете `docs` лежат составные аннотации с `@Operation` для каждого эндпоинта

`UploadResourceDocs`, `MoveResourceDocs`, `SignUpDocs` и остальные одновременно скрывают summary операции и набор ответов. Чтобы понять контракт `GET /api/resource/move`, нужно открывать отдельный файл вне контроллера и API-интерфейса.

**Рекомендация:**

Перенеси `@Operation` на методы API-интерфейса. В пакете `docs` оставь только составные аннотации для повторяющихся `@ApiResponse`.

2. В doc-аннотациях повторяются блоки `@ApiResponse` для 401 и 500

`GetResourceInfoDocs`, `UploadResourceDocs`, `MoveResourceDocs` и ещё восемь файлов содержат почти одинаковые ответы авторизации и внутренней ошибки. Изменение формата ошибки потребует правок во всех аннотациях.

**Рекомендация:**

Собери общие ответы в составную аннотацию и подключай её к методам API-интерфейса.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "User is not authenticated",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class),
                        examples = @ExampleObject(value = SwaggerExamples.NOT_AUTHENTICATED))),
        @ApiResponse(responseCode = "500", description = "Internal server error",
                content = @Content(schema = @Schema(implementation = ErrorResponseDto.class),
                        examples = @ExampleObject(value = SwaggerExamples.INTERNAL_ERROR)))
})
public @interface CommonAuthErrorResponses {
}
```

### пакет /dto

1. Request- и response-модели лежат в одном пакете `dto`

`UserAuthRequestDto` и `UserAuthResponseDto`, `ResourceResponseDto` и `ErrorResponseDto` не разделены по направлению данных. При поиске контракта конкретного эндпоинта приходится просматривать весь пакет.

**Рекомендация:**

Раздели transport DTO на подпакеты `dto.request` и `dto.response`.

2. В пакете `dto` лежат `ParsedPath` и `DownloadResult`

Это внутренние модели парсинга пути и формирования HTTP-ответа, а не transport-контракт. Пакет `dto` начинает смешивать HTTP-модели и application-детали.

**Рекомендация:**

Перенеси `ParsedPath` в пакет рядом с `PathUtil`, а `DownloadResult` — в отдельный пакет.

3. В `UserAuthRequestDto` один тип используется и для регистрации, и для входа

Сейчас поля совпадают, поэтому решение рабочее. При расширении одного сценария — например, отдельные правила пароля при регистрации — изменения затронут оба эндпоинта.

**Рекомендация:**

Можно разделить DTO на `SignUpRequest` и `SignInRequest`, общие ограничения вынеси в составную аннотацию `@ValidUsername`. Если хочешь, можно и так оставить, если наперед загоняться, это уже будет нарушать KISS

### пакет /entity

1. У директорий в `Resource` не задаётся `status`

`createRootDirectoryForNewUser()` и `createDirectory()` сохраняют директории без статуса, хотя поле помечено `@Column(nullable = false)`. Запросы в `ResourceRepository` компенсируют это условием `status IS NULL`. Маппинг entity и фактическое поведение расходятся.

**Рекомендация:**

Задавай `ResourceStatus.READY` при создании директорий и убери `OR r.status IS NULL` из запросов репозитория.

### пакет /exception

1. В `GlobalExceptionHandler#handleException()` клиенту уходит `ex.getMessage()`

Любое необработанное исключение возвращает технический текст — SQL-ошибки, сообщения MinIO SDK, `NullPointerException`. Клиент видит детали, которых в контракте API нет.

**Рекомендация:**

Логируй полное исключение, в ответе отдавай стабильное сообщение `"Internal server error"`.

2. В `GlobalExceptionHandler#handleMethodArgumentNotValidException()` стоит `assert fieldError != null`

При выключенных assertions в production `fieldError` может оказаться `null`, и обработчик упадёт с `NullPointerException` вместо 400.

**Рекомендация:**

Замени assert проверкой с запасным сообщением `"Validation failed"`.

3. Для `UploadingFailedException` нет отдельного обработчика

Исключение из `StorageService#uploadAll()` попадает в общий `handleException()` и возвращает 500 с техническим текстом из `e.getMessage()`. Клиент не получает предсказуемое сообщение об ошибке загрузки.

**Рекомендация:**

Добавь `@ExceptionHandler(UploadingFailedException.class)` с кодом 500 и стабильным текстом `"Uploading failed"`.

### пакет /repository

1. Нет индексов под частые запросы по `user_id` и `path`

`findAllByPathStartingWithAndTypeAndUserId` и `findAllByQueryAndUserId` вызываются при листинге, скачивании ZIP и поиске. На текущем объёме данных это незаметно, но при росте таблицы `resources` запросы начнут сканировать все строки пользователя.

**Рекомендация:**

Добавь в Liquibase индексы `(user_id, path)` и `(user_id, name)`.

### пакет /scheduler

1. В `DataCleanupScheduler#scheduleCleanup()` захардкожены cron и порог очистки

`0 * * * * *` и `minus(5, ChronoUnit.MINUTES)` зашиты в код. Изменить частоту или retention можно только через правку Java и redeploy.

**Рекомендация:**

Вынеси параметры в `CleanupProperties` в `config.properties` и читай их в scheduler.

### пакет /security

1. В `SecurityUser` хранится JPA-сущность `User`

`UserDetailsServiceImpl` передаёт в security-контекст полный объект из persistence-слоя. Любое изменение `User` — новые поля, lazy-связи — потенциально затрагивает объект аутентификации.

**Рекомендация:**

Передавай в `SecurityUser` только `id`, `username` и `password` через фабричный метод `SecurityUser.from(User user)`.

### пакет /service

1. `StorageService` и `DataCleanupService` зависят от `MinioService` напрямую

`MinioService` использует `MinioClient` и классы `PutObjectArgs`, `GetObjectArgs`, `RemoveObjectArgs`. Замена MinIO на другой S3-совместимый провайдер потребует правок в application-сервисах, а не только в инфраструктурном адаптере.

**Рекомендация:**

Введи нейтральный интерфейс `ObjectStorage`, application-сервисы завяжи на него, текущий код `MinioService` перенеси в `MinioObjectStorage` в инфраструктурном пакете.

```java
public interface ObjectStorage {
    void put(UUID objectId, Long userId, InputStream content, long size, String contentType);
    InputStream get(UUID objectId, Long userId);
    void delete(UUID objectId, Long userId);
}
```

```java
@Service
@RequiredArgsConstructor
class StorageService {

    private final ObjectStorage objectStorage;
    private final ResourceMetadataService resourceMetadataService;
}
```

2. В `StorageService#getZipAsStream()` не закрываются потоки MinIO

В цикле по файлам директории `minioService.getObjectAsStream()` открывает `InputStream`, но `close()` нигде не вызывается. При скачивании папки с десятками файлов соединения к MinIO будут копиться до сборки мусора.

**Рекомендация:**

Оберни каждый поток в `try-with-resources` внутри цикла.

```java
for (Resource resource : resources) {
    try (InputStream inputStream = minioService.getObjectAsStream(resource.getUuid(), userId)) {
        String entryName = (resource.getPath() + resource.getName()).substring(prefix.length());
        zipOut.putNextEntry(new ZipEntry(entryName));
        StreamUtils.copy(inputStream, zipOut);
        zipOut.closeEntry();
    }
}
```

3. В `StorageService#rollback()` не удаляются объекты из MinIO

При ошибке на втором файле в `uploadAll()` первый файл уже лежит в бакете, а rollback только помечает метаданные как `FAILED` и удаляет созданные директории. Объект в MinIO останется до срабатывания scheduler — между ответом клиенту и фактической очисткой есть задержка в несколько минут.

**Рекомендация:**

Добавь в `rollback()` вызов `minioService.deleteObject()` для каждого UUID из `uploadingUUIDs`.

4. В `StorageService#uploadAll()` нет общей транзакционной границы

Метод вызывает несколько `@Transactional`-операций `ResourceMetadataService`, и каждая коммитится отдельно, потому что сам `uploadAll()` не обёрнут в транзакцию. При сбое посередине цикла часть метаданных уже сохранена в БД со статусом `UPLOADING`.

**Рекомендация:**

Поставь `@Transactional` на `uploadAll()` для атомарности метаданных. Вызовы MinIO оставь с компенсирующим удалением в `rollback()`.

5. В `ResourceMetadataService#createDirectory()` автоматически создаются родительские папки

Метод `upsertParentDirectories()` молча создаёт все промежуточные директории. По ТЗ `POST /directory` должен вернуть 404, если родительской папки нет. Сейчас клиент может создать `/a/b/c/` без явного создания `/a/` и `/a/b/`.

**Рекомендация:**

Проверяй существование непосредственного родителя и убери автоматическое создание промежуточных директорий из `createDirectory()`.

```java
ParsedPath parent = parse(parsedPath.path());
if (!isRootDir(parent) && !resourceRepository.existsByPathAndNameAndTypeAndUserId(
        parent.path(), parent.name(), ResourceType.DIRECTORY, userId)) {
    throw new ResourceNotFoundException("Parent directory not found");
}
```

6. В `MinioService` захардкожен префикс пользовательских файлов

`USER_FILES_PREFIX = "user-%d-files/"` используется только в этом классе, поэтому текущее решение рабочее. При этом в проекте уже есть `MinioProperties` с остальными настройками MinIO — префикс логичнее держать рядом с ними.

**Рекомендация:**

Добавь поле `userFilesPrefix` в существующий `MinioProperties` и используй его в `MinioService` при формировании имени объекта.

7. В `DataCleanupService#cleanup()` метаданные удаляются из БД до MinIO

`cleanupMetadata()` сначала удаляет строки, затем `cleanupMinio()` пытается удалить объекты. Если удаление из MinIO падает, в бакете останется объект без записи в БД, и scheduler больше не сможет его найти.

**Рекомендация:**

Сначала удаляй объекты из `ObjectStorage`, затем строки из `ResourceRepository`. Ошибки MinIO пробрасывай или складывай в отдельную очередь повторной очистки.

### пакет /test

1. `StorageServiceTest` — пустая заглушка

Метод `getResourceInfoTest()` не содержит ни настройки, ни проверок. Класс создаёт видимость покрытия storage-слоя, но регрессию в upload, rollback и download он не поймает.

**Рекомендация:**

Напиши unit-тест `StorageService` с mock `ResourceMetadataService` и `ObjectStorage` — хотя бы на сценарий rollback при `ResourceAlreadyExistsException`.

2. Нет интеграционного теста `UserService`

По ТЗ нужно проверить, что `register()` создаёт запись в `users` и бросает исключение при дубликате username. Есть `UserRepositoryTest` и `AuthControllerIT`, но сценарий на уровне сервиса не зафиксирован отдельно.

**Рекомендация:**

Добавь `@DataJpaTest` с `@Import(UserService.class)` и Testcontainers PostgreSQL: один тест на успешную регистрацию, один на `UserAlreadyExistsException`.

---

## РЕКОМЕНДАЦИИ

1. Закрой сценарии сбоя в upload и download: закрытие потоков при ZIP, удаление объектов в `rollback()`, транзакция на метаданные в `uploadAll()`.
2. Введи `ObjectStorage` как границу между application-сервисами и MinIO SDK, адаптер размести в инфраструктурном пакете.
3. Вынеси HTTP-контракты в API-интерфейсы контроллеров, `@Operation` держи на методах интерфейса, повторяющиеся `@ApiResponse` — в составных аннотациях.
4. Приведи структуру пакетов к единому виду: `config/properties`, `dto/request`, `dto/response`, без внутренних моделей в transport DTO.
5. Выровняй модель `Resource` и `createDirectory()` с ТЗ: явный `status` у директорий, проверка родителя вместо автосоздания цепочки папок.
6. Доведи тесты до требований задания: убери заглушки, добавь тест `UserService`, покрой rollback upload unit-тестом.

---

## ИТОГ

Проект заметно сильнее типичного учебного: UUID-хранение в MinIO отделено от пользовательских путей, streaming-download решён правильно, partial unique index и soft delete со scheduler показывают, что ты думал о связке БД и бакета, а не просто подключил MinIO.

Слабые места сгруппированы вокруг границ слоёв и сценариев сбоя. Application-сервисы завязаны на конкретный `MinioService`, REST-контракты не вынесены в API-интерфейсы, OpenAPI размазан по пакету `docs`, а upload при ошибке оставляет объекты в бакете и незакрытые потоки при ZIP-скачивании. Рядом — расхождение `createDirectory()` с ТЗ и несколько структурных мелочей в расположении проперти и DTO.

Дальше имеет смысл сначала закрыть конкретные дыры в upload, cleanup и скачивании директорий, затем выровнять пакетную структуру и контракты API. Когда сценарии сбоя станут предсказуемыми, абстракция `ObjectStorage` и API-интерфейсы лягут на уже стабильную основу, а не на код, который ещё меняется по ходу рефакторинга.
