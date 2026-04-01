[romilMasnaviev/CloudStorage](https://github.com/romilMasnaviev/CloudStorage)

## ХОРОШО

1. **Интеграционные тесты с Testcontainers на всех трёх инфраструктурных зависимостях** — `AbstractIntegrationTest` поднимает реальные контейнеры PostgreSQL, Redis и MinIO. Тесты в `FileControllerIntegrationTest` покрывают как happy path, так и граничные случаи: конфликты, несуществующие ресурсы, защиту корневой директории, попытку смены типа. Каждый тест начинается с чистого состояния через `@BeforeEach`. Это редкость для учебных проектов.

2. **Unit-тесты сервисного слоя с Mockito** — `UserServiceImplUnitTest` и `ResourceBuilderUnitTest` тестируют бизнес-правила без поднятия Spring-контекста, что делает их быстрыми и изолированными. Используется `@Spy` для реального `BCryptPasswordEncoder`, `@Mock` для репозитория.

3. **`LogFilter` с MDC traceId** — каждый входящий HTTP-запрос получает уникальный UUID через `MDC.put("traceId", ...)`, что позволяет коррелировать все лог-сообщения одного запроса. Логируются метод, URI и время выполнения. Это профессиональный подход к наблюдаемости.

4. **`@JsonInclude(NON_NULL)` на `ResourceInfoResponse`** — поле `size` не сериализуется в JSON, когда оно `null` (для директорий). Это соответствует контракту ТЗ: директории не имеют поля `size` в ответе. Реализовано декларативно через аннотацию, без условной логики в маппинге.

5. **Централизованные константы** — все сообщения об ошибках вынесены в `ErrorMessages`, все URL — в `ApiPath`. Это исключает дублирование строк по всему проекту и упрощает изменение.

---

## ЗАМЕЧАНИЯ

### пакет /config

1. Классы: `ClientConfiguration`, `MinioRepository` — дублирование конфигурации MinIO, нет `@ConfigurationProperties`.

Свойство `minio.bucket.name` читается через `@Value` одновременно в `ClientConfiguration` (строка 23) и в `MinioRepository` (строка 28). Остальные параметры MinIO (`minio.endpoint`, `minio.username`, `minio.password`) читаются в `ClientConfiguration` через `env.getProperty(...)`. 
Это два разных механизма для одного набора свойств, причём одно свойство (`bucket.name`) дублируется в двух разных слоях. Конфигурация — это инфраструктурный слой; репозиторий не должен знать об источнике конфигурации (`@Value`, `environment`, файл). 
При добавлении новой настройки придётся искать все места, где читается данный namespace, и менять их. `@ConfigurationProperties` обеспечивает типобезопасность, единый источник правды, IDE-автодополнение в `application.properties` и возможность валидации при старте приложения.

**Рекомендация:** Добавить MinioProperties со всеми настройками, в ClientConfiguration использовать пропертис. В MinioProperties инжектить пропертис
```java
// Новый файл: config/minio/MinioProperties.java
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String username,
        String password,
        Bucket bucket
) {
    public record Bucket(String name) {}
}

@Configuration
@RequiredArgsConstructor
class ClientConfiguration {
    private final MinioProperties minioProperties;

    @Bean
    MinioClient minioClient() {
        MinioClient client = MinioClient.builder()
                .endpoint(minioProperties.endpoint())
                .credentials(minioProperties.username(), minioProperties.password())
                .build();
        createBucketForFiles(minioProperties.bucket().name(), client);
        return client;
    }
}
```

2. Класс: `ClientConfiguration`, метод: `createBucketForFiles()` — потеря stack trace при исключении.

Строка `throw new RuntimeException(e.getMessage())` передаёт только строковое сообщение, исходное исключение теряется. 
Stack trace причины недоступен для диагностики — в логах будет видно только сообщение, без цепочки вызовов, приведшей к ошибке. 
Кроме того, диагностически нет способа понять, какой именно тип исключения стал причиной.

**Рекомендация:** Не терять стектрейс

3. Классы: `CustomAuthenticationEntryPoint`, `CustomAccessDeniedHandler` — `new Gson()` для сериализации JSON.

В обоих классах сериализация `ErrorResponse` в JSON выполняется через `new Gson().toJson(errorResponse)`. Spring Boot уже включает Jackson и автоматически регистрирует `ObjectMapper` как Spring-бин. 
Зачем тащить в проект ещё один JSON-сериализатор для двух строчек кода? Кроме того, `new Gson()` создаётся вручную — это означает, что никакая настройка из Spring-контекста (кастомные сериализаторы, `JsonInclude`, формат дат) на него не распространяется. 
Если завтра понадобится изменить формат JSON-ответов — настройки Jackson на эти два класса не подействуют. Задумайтесь: что мешает просто инжектировать `ObjectMapper` через конструктор так же, как инжектируются остальные зависимости в проекте?

**Рекомендация:** Использовать ObjectMapper

---

### пакет /constatnts

1. Пакет `constatnts` — опечатка в имени пакета.

Имя пакета `constatnts` содержит опечатку: `constatnts` вместо `constants`

**Рекомендация:**  Переименовать пакет

2. Классы: `ApiPath`, `ErrorMessages` — нет `private` конструктора.

Оба класса являются держателями статических констант и никогда не должны инстанциироваться. Без `private` конструктора любой код может выполнить `new ApiPath()` или `new ErrorMessages()`, что семантически бессмысленно. Для утильных классов и классов констант принято делать приватные конструкторы

**Рекомендация:** Добавить приватный конструктор
```java
public final class ApiPath {
    private ApiPath() {} // запрет инстанциации

    public static final String AUTH_SIGN_UP_URL = "/api/auth/sign-up";
    // ...
}
```

---

### пакет /controller

1. Класс: `AuthController` — прямая зависимость от `S3FileService`, нарушение SRP.

`AuthController` инжектирует `S3FileService` и вызывает `s3FileServiceImpl.createUserDirectory(...)` в методе `registration()`. 
Контроллер аутентификации не должен знать о файловом хранилище. Создание корневой директории — это бизнес-правило регистрации нового пользователя, а не аутентификации. 
Эта ответственность принадлежит `UserService.registration()`. Текущая реализация нарушает SRP: один контроллер отвечает и за регистрацию пользователя в БД, и за инициализацию его пространства в MinIO. 
При смене файлового хранилища придётся менять и контроллер.

**Рекомендация:** Убрать логику создания папки из контроллера

2. Класс: `AuthController`, метод: `registration()` — лишний SQL-запрос после регистрации.

После вызова `userService.registration(request)` выполняется `userService.getIdByUsername(response.username())` — ещё один `SELECT` к базе для получения ID только что созданного пользователя. 
`registration()` уже сохранила пользователя и знает его ID, но не возвращает его. Помимо лишнего запроса, это означает, что `UserService.getIdByUsername()` существует во многом ради этого обходного пути. Если вынести создание директории в сервис (как указано в замечании выше), проблема исчезнет.

**Рекомендация:** Автоматически решится после 1 пункта. См п.1

3. Класс: `FileController` — лишний SQL-запрос в каждом методе.

Все семь методов `FileController` начинаются с `Long userId = userService.getIdByUsername(userDetails.getUsername())`. Это дополнительный `SELECT` к PostgreSQL при каждом обращении к API хранилища. При этом `@AuthenticationPrincipal UserDetails userDetails` — это на самом деле объект `SecurityUser`, который уже содержит полный объект `User` с `id`. 
Контроллер получает параметр с типом `UserDetails` (интерфейс), что скрывает доступ к `User.id`. Замена типа параметра на конкретный `SecurityUser` устраняет SQL полностью.

**Рекомендация:** Заменить UserDetails на SecurityUser, убрать лишний запрос

4. Класс: `FileController`, метод: `moveResource()` — HTTP-метод GET для мутирующей операции.

Метод аннотирован `@GetMapping(MOVE_RESOURCE)`. Перемещение и переименование ресурса — это мутирующая операция, которая изменяет состояние сервера. GET по спецификации HTTP (RFC 9110) является безопасным и идемпотентным методом — он не должен иметь побочных эффектов. 
Браузер, прокси или CDN могут кешировать GET-запрос и не выполнить реальный запрос к серверу. Для операции изменения ресурса используется `PUT` (полная замена) или `PATCH` (частичная модификация). ТЗ описывает операцию как "переместить/переименовать" хоть там и указано `GET`, но это ошибка. Нужно использовать `PATCH`.

**Рекомендация:** Заменить на `PUT` или `PATCH`. В крайнем случае `POST`, но явно не `GET`

5. Класс: `UserController`, метод: `me()` — маппинг в контроллере.

В методе выполняется `new UserMeResponse(userDetails.getUsername())` — создание DTO прямо в контроллере. Контроллер должен только принять запрос, делегировать работу сервису и вернуть результат. 
Любая логика трансформации — создание DTO, маппинг полей — должна быть в сервисном слое или маппере. Сейчас `UserController` не имеет сервиса, что само по себе архитектурная аномалия.

**Рекомендация:** Вынести мапинг в сервис

6. Классы: `AuthController`, `FileController`, `UserController` — отсутствуют интерфейсы контроллеров.

Ни один из трёх контроллеров не имеет интерфейса. Это нормально для маленького pet-проекта, но сразу блокирует один из самых распространённых в реальных проектах сценариев — версионирование API. Представь: появляется требование добавить `/api/v2/resource` с изменённым контрактом ответа. Сейчас единственный вариант — либо городить условную логику внутри существующего контроллера, либо копировать класс целиком. Если бы контроллер реализовывал интерфейс, новая версия — это просто новая реализация того же контракта: все `@RequestMapping`, `@Operation` (Swagger) и сигнатуры методов описаны в интерфейсе, а реализации `V1` и `V2` содержат только отличающуюся логику. 
Кроме того, интерфейс контроллера даёт возможность тестировать контракт через типизированный HTTP-клиент (например, Feign или `@FeignClient` в интеграционных тестах).

**Рекомендация:** Добавить абстракцию
```java
// Интерфейс описывает контракт и Swagger-аннотации:
@Tag(name = "Файлы и папки")
public interface FileApi {

    @Operation(summary = "Получение информации о ресурсе")
    @GetMapping(GET_RESOURCE_INFO)
    ResponseEntity<ResourceInfoResponse> getResourceInfo(
            @RequestParam String path,
            @AuthenticationPrincipal SecurityUser userDetails);

    @Operation(summary = "Удаление ресурса")
    @DeleteMapping(DELETE_RESOURCE)
    ResponseEntity<Void> deleteResource(
            @RequestParam String path,
            @AuthenticationPrincipal SecurityUser userDetails);

    // ... остальные методы
}

// Реализация v1 — только логика, без аннотаций маппинга:
@RestController
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class FileController implements FileApi {

    private final S3FileService service;

    @Override
    public ResponseEntity<ResourceInfoResponse> getResourceInfo(String path, SecurityUser userDetails) {
        return ResponseEntity.ok(service.getResourceInfo(userDetails.getUserId(), path));
    }
    // ...
}

// При необходимости v2 — новая реализация того же интерфейса:
@RestController
@RequestMapping("/api/v2")
class FileControllerV2 implements FileApi {
    // только отличающееся поведение
}
```

---

### пакет /dto

1. Класс: `DownloadResourceResponse` — содержит `InputStreamResource` из Spring Framework.

`DownloadResourceResponse` хранит поле `InputStreamResource resource`. `InputStreamResource` — это класс Spring (`org.springframework.core.io.InputStreamResource`), принадлежащий транспортному/презентационному слою. 
Сервисный слой (`S3FileServiceImpl`) возвращает объект, который знает о деталях Spring Web MVC. Это нарушение изоляции слоёв: сервис должен возвращать доменный объект (поток данных), а не объект, привязанный к конкретному фреймворку. При смене фреймворка или при unit-тестировании сервиса придётся работать со Spring-специфичными типами.

**Рекомендация:** Заменить InputStreamResource на java.io.InputStream:

2. Класс: `ResourceInfoResponseBuilder` — misleading нейминг и присутствие логики MinIO-путей в слое DTO.

Имя `Builder` предполагает паттерн Builder с fluent API (цепочка `.with*()`), но класс содержит набор несвязанных статических фабричных методов с разными сигнатурами. Это создаёт путаницу при навигации. 
Более важная проблема: внутри `getResourceInfoResponse()` выполняется `path.subpath(1, path.getNameCount() - 1)` — это стрипинг пользовательского MinIO-префикса (`user-N-files/`). Слой DTO не должен знать о структуре внутренних путей хранилища. Эта логика принадлежит сервисному слою или `Resource`.

**Рекомендация:** Переименовать и перенести в utils какой-нибудь. Это явно не должно находится в dto

---

### пакет /exception

1. Класс: `ControllerAdvice` — имя совпадает с аннотацией Spring.

Класс называется `ControllerAdvice`, так же как аннотация `@RestControllerAdvice` / `@ControllerAdvice` из Spring. При беглом чтении кода трудно сразу понять, речь идёт о классе или об аннотации. 
Стандартное соглашение — называть такой класс `GlobalExceptionHandler`, `RestExceptionHandler` или `ApiExceptionHandler`.

**Рекомендация:** Переименовать в `GlobalExceptionHandler`

2. Класс: `ControllerAdvice`, метод: `errorResponseExceptionHandler()` — утечка инфраструктурной детали в слой exception handler.

Обработчик перехватывает `ErrorResponseException` (исключение MinIO SDK) и проверяет `ex.errorResponse().code().equals("NoSuchKey")`. Это инфраструктурный код MinIO SDK на уровне глобального exception handler — прямая зависимость обработчика ошибок от деталей стороннего SDK.
Если MinIO изменит код ошибки или если сменится хранилище, придётся менять глобальный обработчик. `ErrorResponseException` должна перехватываться в `MinioRepository`, где она и возникает, и там же трансформироваться в доменное исключение.

**Рекомендация:** Перехватывать ErrorResponseException там, где она возникает
```java
public StatObjectResponse getResourceInfo(String path) {
    try {
        return client.statObject(StatObjectArgs.builder()
                .bucket(minioBucketName)
                .object(path)
                .build());
    } catch (ErrorResponseException ex) {
        if ("NoSuchKey".equals(ex.errorResponse().code())) {
            throw new ResourceNotFoundException("Resource not found: " + path);
        }
        throw new MinioOperationException(ex.getMessage(), ex);
    } catch (MinioException | NoSuchAlgorithmException | InvalidKeyException | IOException e) {
        throw new MinioOperationException(e.getMessage(), e);
    }
}
```

3. Класс: `ControllerAdvice`, метод: `noSuchElementExceptionHandler()` — перехват `NoSuchElementException` с кодом 404.

`NoSuchElementException` — это общее исключение из `java.util`, возникающее в т.ч. при вызове `Optional.get()` без проверки `isPresent()`, при пустом `Iterator.next()` и т.д. Регистрация его как «404 Not Found» означает, что любая программная ошибка (например, неожиданный `Optional.get()` где-то в коде) автоматически вернёт клиенту 404 вместо 500. 
Это маскирует баги и вводит клиента в заблуждение. Доменные случаи «не найдено» должны использовать конкретные доменные исключения (`ResourceNotFoundException`, `FileNotFoundException` и т.д.), которые уже есть в проекте.

**Рекомендация:** Удалить обработчик NoSuchElementException

4. Класс: `ControllerAdvice`, метод: `buildResponseEntity()` — магические числа вместо `HttpStatus`.

Во всех обработчиках используются магические числа: `400`, `404`, `409`, `413`, `500`. Параметр `buildResponseEntity(String message, int status)` принимает `int`. 
Это снижает читаемость (нужно держать в уме, что означает каждое число) и отключает compile-time проверку валидности кода. `HttpStatus` enum семантически выражает намерение.

**Рекомендация:** В buildResponseEntity принимать HttpStatus, не int

---

### пакет /model

1. Класс: `SecurityUser` — расположен в пакете `/model` вместо `/security`, не предоставляет `userId`.

`SecurityUser` реализует `UserDetails` — контракт Spring Security. Это не доменная модель (`User`), а адаптер безопасности. Размещение в пакете `model` смешивает два разных слоя. Кроме того, `SecurityUser` не предоставляет публичного доступа к `User.id`. 
Из-за этого контроллеры и сервисы вынуждены выполнять дополнительный SQL-запрос `SELECT ... WHERE username = ?` при каждом обращении к API, хотя ID уже присутствует в `SecurityContext`.

**Рекомендация:** Перенести в пакет security, использовать по коду user из SecurityUser вместо селектов

2. Класс: `User`, поля `username`, `password` — `@NotBlank` на полях JPA-сущности.

На полях `username` и `password` стоят аннотации `@NotBlank` из Bean Validation. Валидация входящих данных — это ответственность транспортного слоя (DTO/request-объект), а не доменной сущности. 
JPA не вызывает Bean Validation автоматически при `save()` в большинстве конфигураций, поэтому эти аннотации на сущности дают ложное ощущение безопасности: ты видишь `@NotBlank` и думаешь, что пустая строка никогда не попадёт в базу, — но это не так, если валидация не настроена явно через `javax.persistence.validation.mode`. 
Настоящая защита уже есть: в `UserRegistrationRequest` стоят `@NotBlank` и `@Size`, а в DDL — `NOT NULL` на уровне базы

**Рекомендация:** `@NotBlank` — убрать, валидация входных данных в UserRegistrationRequest

3. Класс: `User` — `implements Serializable` и `serialVersionUID`.

`User` реализует `Serializable` и объявляет `@Serial private static final long serialVersionUID = 1L`. зачем? 

**ВОПРОС:** Для каких целей Serializable?

---

### пакет /repository

1. Класс: `MinioRepository` — неверное название, аннотирован `@Service`, нет интерфейса.

Здесь сразу три проблемы, связанные между собой. Первая — название. В Spring-экосистеме `Repository` — устойчивое понятие: это объект доступа к данным, обычно ассоциированный с JPA/JDBC/MongoDB и работающий с доменными сущностями. 
`MinioRepository` — это не репозиторий в этом смысле: он не работает с доменными объектами, он является клиентом-адаптером к внешнему S3-совместимому хранилищу. 
Правильное название — `MinioStorageClient` — честно отражает то, чем класс является. Вторая — аннотация `@Service`. Это просто неверно семантически: класс не содержит бизнес-логики. Третья и главная — отсутствие интерфейса. Сейчас `S3FileServiceImpl` напрямую зависит от конкретного класса `MinioRepository`. Написать unit-тест для `S3FileServiceImpl` без поднятия реального MinIO-контейнера невозможно — нет интерфейса, который можно замокать. При смене хранилища (например, AWS S3 SDK вместо MinIO) придётся менять конкретный класс, на который завязан сервис, а не просто подставить другую реализацию. 
Интерфейс решает обе проблемы: изолирует сервис от деталей SDK и открывает возможность для полноценных unit-тестов с Mockito.

**Рекомендация:** Переименовать, перенести в другой пакет + сделать абстракцию `StorageClient`

2. Класс: `MinioRepository`, метод: `getResourcesItemsByPrefix()` — мёртвый код с пустым `forEach`.

Строки 73–75:
```java
results.forEach(r -> {
});
```

**Рекомендация:** Зачем оно? Удалить неиспользуемый код

3. Класс: `MinioRepository`, метод: `toItemMapByPath()` — двойной вызов `result.get()` на одной итерации.

В строке `paths.put(result.get().objectName(), result.get())` метод `result.get()` вызывается дважды. `Result<T>.get()` объявляет `throws` несколько checked exceptions, поэтому каждый вызов — это потенциально дорогостоящая операция с накладными расходами на обработку ошибок. Нужно сохранить результат в локальную переменную.

**Рекомендация:** `result.get()` вынести в переменную

---

### пакет /service

1. Класс: `AuthServiceImpl`, метод: `setSecurityContext()` — вводящее в заблуждение имя и незавершённая установка контекста.

Метод называется `setSecurityContext`, но фактически только создаёт объект `SecurityContext` и возвращает его — `SecurityContextHolder.setContext(context)` нигде не вызывается. В рамках текущего запроса (запроса на вход) `SecurityContextHolder.getContext()` вернёт пустой контекст. 
Код работает на практике, потому что ни одна последующая операция в том же запросе не читает SecurityContextHolder, но имя метода вводит в заблуждение: «установить» означает применить, а не создать.

**Рекомендация:** Переименовать в createSecurityContext

2. Класс: `S3FileServiceImpl` — аннотирован `@Component` вместо `@Service`.

`S3FileServiceImpl` является бизнес-сервисом, но аннотирован `@Component`. Это неверная семантика: `@Service` явно указывает, что класс принадлежит сервисному слою и содержит бизнес-логику

**Рекомендация:** заменить `@Component` на `@Service`

4. Класс: `S3FileServiceImpl`, метод: `searchResource()` — поиск возвращает только файлы, директории исключены.

Строка `filter(r -> r.getType() != DIRECTORY)` отфильтровывает все директории из результатов поиска. По ТЗ поиск по запросу должен возвращать как файлы, так и директории. Исключение директорий нарушает контракт API: пользователь, ищущий папку `documents/`, не получит результата. 
Кроме того, тесты в `FileControllerIntegrationTest` проверяют поиск только по файлам, что маскирует эту проблему.

**Рекомендация:** Должны возвращать и файлы и папки

5. Класс: `S3FileServiceImpl`, метод: `downloadDirectory()` — весь ZIP-архив в памяти.

Метод `downloadDirectory()` загружает все файлы директории в `ByteArrayOutputStream`, создавая ZIP целиком в heap. При директории размером 1 GB это приведёт к `OutOfMemoryError`. 
Правильный подход — использовать `StreamingResponseBody` (запись напрямую в поток ответа) или `PipedOutputStream`/`PipedInputStream`, избегая промежуточного буфера в памяти.

**Рекомендация:** вместо InputStreamResource использовать StreamingResponseBody
```java
// FileController.java
@GetMapping(DOWNLOAD_RESOURCE)
public ResponseEntity<StreamingResponseBody> downloadResource(
        @RequestParam(name = "path", defaultValue = "/") String path,
        @AuthenticationPrincipal SecurityUser userDetails) {

    // Сервис возвращает функцию записи, а не готовый буфер:
    StreamingResourceInfo info = service.downloadResource(userDetails.getUserId(), path);
    String postfix = info.type() == DIRECTORY ? ".zip" : "";
    HttpHeaders headers = new HttpHeaders();
    headers.add(CONTENT_DISPOSITION, "attachment;filename*=utf-8''"
            + encodeFileName(info.name()) + postfix);

    StreamingResponseBody body = outputStream -> info.writer().accept(outputStream);
    return ResponseEntity.ok().headers(headers)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(body);
}

// S3FileServiceImpl — писать ZIP напрямую в OutputStream:
private void writeDirectoryAsZip(Resource resourceData, OutputStream out) throws IOException {
    Set<String> keys = repository.getResourcesItemsByPrefix(resourceData.getFullPath(), true).keySet();
    try (ZipOutputStream zos = new ZipOutputStream(out)) {
        for (String key : keys) {
            String entryName = key.substring(resourceData.getPath().length());
            zos.putNextEntry(new ZipEntry(entryName));
            repository.downloadResource(key).transferTo(zos);
            zos.closeEntry();
        }
    }
}
```

6. Класс: `S3FileServiceImpl`, метод: `createUserDirectory()` — дублирование формулы пути пользователя.

Строка `String userFolder = "user-" + userId + "-files" + "/";` содержит тот же шаблон, что и `ResourceBuilder.initUserFolder()`: `"user-" + userId + "-files"`. Два источника правды для одной и той же бизнес-логики. При изменении формата (например, `"u-" + userId`) нужно менять в двух местах. 
Нет гарантии, что они синхронизированы: в `createUserDirectory` добавлен `/` прямо в строке, тогда как `initUserFolder` не добавляет.

**Рекомендация:**
```java
// ResourceBuilder.java — сделать initUserFolder() публичным статическим методом:
public static String getUserFolder(Long userId) {
    return "user-" + userId + "-files/"; // единый источник правды
}

// S3FileServiceImpl.java — использовать:
@Override
public void createUserDirectory(Long userId) {
    repository.uploadDirectory(ResourceBuilder.getUserFolder(userId));
}

// ResourceBuilder.createFrom() — использовать тот же метод:
String userFolder = getUserFolder(userId); // вместо дублирующего initUserFolder()
```

---

### пакет /util

1. Класс: `ZipBuilder`, метод: `createZipFromResources()` — потеря stack trace.

`catch (IOException ex) { throw new RuntimeException(ex.getMessage()); }` — в `RuntimeException` передаётся только строковое сообщение, без исходного исключения как `cause`. В логах и stack trace не будет информации о том, где именно возникла ошибка, что значительно усложняет диагностику.

**Рекомендация:** Не терять стектрейс

2. Класс: `ResourceBuilder`, метод: `createFrom()` — нечитаемый метод, нетипичный подход к маппингу.

`ResourceBuilder` — это по сути маппер: он берёт `(Long userId, String path)` и превращает их в объект `Resource`. В Java-экосистеме для этой задачи принято использовать либо MapStruct (кодогенерация без рефлексии), либо явный mapper-класс с понятным контрактом. Ручной статический Builder с семью промежуточными переменными — нетипичное решение, которое усложняет код там, где он мог бы быть декларативным.

Теперь о самом методе `createFrom()`. Открой его и попробуй понять с первого чтения, что происходит: семь переменных с похожими именами (`fullPath`, `path`, `resourceName`, `pathWithoutResourceName`, `substring`, `pathWithoutUsernameAndResourceName`, `pathsList`) идут подряд без единой пустой строки, без разделения на логические блоки, без каких-либо комментариев. Каждая строка вычисляет что-то из предыдущей, и к четвёртой строке уже не очевидно, чем `path` отличается от `fullPath`, а `pathWithoutResourceName` — от `pathWithoutUsernameAndResourceName`. Добавь хотя бы визуальные блоки: валидация — отдельно, вычисление путей — отдельно, сборка объекта — отдельно.
Ещё лучше — выдели каждый шаг в именованный приватный метод, тогда `createFrom()` превратится в читаемую последовательность шагов.

**Рекомендация:**
```java
// ResourceBuilder.createFrom() — разбить на именованные шаги:
public static Resource createFrom(Long userId, String path) {
    validatePath(path);
    validateUserId(userId);
    
    String userFolder      = buildUserFolder(userId);
    ResourceType type      = path.endsWith("/") ? DIRECTORY : FILE;
    
    String fullPath        = buildFullPath(path, userFolder, type);
    String resourceName    = buildResourceName(path, fullPath);
    String parentFullPath  = buildParentPath(fullPath, resourceName);
    String relativeParent  = buildRelativeParentPath(path, resourceName); // не "pathWithoutUsernameAndResourceName"
    
    List<String> pathsList = buildPathsList(parentFullPath, userFolder);
    return new Resource(userId, fullPath, type, resourceName,
                        userFolder, parentFullPath, relativeParent, pathsList);
}
```

3. Классы: `Resource`, `ResourceType` — доменные объекты в пакете `util`.

Почему `Resource` лежит в `util`? `util` — это пакет для stateless вспомогательных инструментов без бизнес-смысла: конверторы, хелперы, парсеры. `Resource` — это совсем другое: объект, инкапсулирующий бизнес-понятие «ресурс пользователя в хранилище» с полным набором вычисленных путей. 
`ResourceType` — перечисление доменных типов (`FILE`, `DIRECTORY`). 
Размещение в `util` говорит о том, что в проекте нет осознанного выделения доменного слоя: бизнес-объекты перемешаны с инфраструктурными помощниками. Правильное место — пакет `dto`

Отдельно о конструкторе `Resource`: он принимает 8 параметров, ни один из которых не валидируется внутри самого конструктора. Вся защита вынесена во внешний `ResourceBuilder`. 
Это хрупкая схема: если кто-то создаст `Resource` напрямую (конструктор `protected`, но не `private`), никакой проверки не произойдёт. Класс с таким количеством полей и встроенной логикой — хороший кандидат на `record`, который явно выражает неизменяемость и убирает необходимость в отдельном Builder-классе.

**Рекомендация:** Перенести в другое место

---

## РЕКОМЕНДАЦИИ

1. **Осознанно выделить доменный слой** — в проекте нет пакета, который чётко отвечает за доменные объекты. `Resource` и `ResourceType` лежат в `util`, `User` — в `model`, `SecurityUser` — там же, хотя это адаптер безопасности. Стоит ввести пакет `domain` (или расширить `model`) и поместить туда всё, что представляет бизнес-понятия: `Resource`, `ResourceType`, `User`. Всё остальное — это инфраструктура, транспорт или утилиты. Чёткое разграничение сразу делает архитектуру читаемой.

2. **Ввести интерфейс над инфраструктурным адаптером MinIO** — сейчас `S3FileServiceImpl` напрямую зависит от конкретного класса `MinioRepository`. Это означает: нет unit-тестов для файлового сервиса без реального MinIO, смена хранилища требует переписывания сервиса. Интерфейс `StorageClient` решает обе проблемы и является стандартной практикой при работе с внешними инфраструктурными зависимостями.

3. **Изучить границы ответственности между слоями** — несколько проблем проекта имеют общий корень: размытые границы между controller, service и infrastructure. `AuthController` знает о MinIO, `AuthService` знает о `HttpServletRequest`, `ResourceInfoResponseBuilder` знает о структуре MinIO-путей. Почитай про принцип Dependency Inversion и Clean Architecture — это даст системное понимание того, какой слой что должен знать и в каком направлении должны идти зависимости.

4. **Добавить unit-тесты для `S3FileServiceImpl`** — сейчас тестирование файлового сервиса возможно только через интеграционные тесты с реальным MinIO-контейнером. После введения интерфейса `StorageClient` появится возможность замокать его в Mockito и покрыть unit-тестами всю бизнес-логику сервиса: проверки существования пути, логику перемещения, формирование ответов. Unit-тесты быстрее, дешевле и точнее изолируют конкретное поведение.

5. **Изучить `@ConfigurationProperties` как стандарт для внешних зависимостей** — `@Value` в нескольких местах для одного набора свойств — это антипаттерн. `@ConfigurationProperties` даёт типобезопасность, IDE-автодополнение, единый источник правды и валидацию при старте. Это стандарт для любой внешней зависимости (MinIO, S3, почтовый сервер, платёжный шлюз).

6. **Разобраться с семантикой HTTP-методов** — GET, POST, PUT, PATCH, DELETE имеют чётко определённую семантику в RFC 9110. Использование GET для мутирующих операций — системная ошибка, которая ломает кеширование, идемпотентность и поведение клиентов. Стоит изучить, какой метод для каких операций предназначен, и применять это осознанно, а не по принципу «главное, чтобы работало».

7. **Добавить интерфейсы над контроллерами** — это стандартная практика в проектах, где API версионируется или документируется через OpenAPI. Swagger-аннотации описываются в интерфейсе, реализация содержит только логику. При появлении `/api/v2` не нужно дублировать весь класс — достаточно новой реализации того же контракта.

---

## ИТОГ

Проект выше среднего для учебного уровня. Видно, что ты думал об архитектуре: выделил интерфейсы над сервисами, написал интеграционные тесты с Testcontainers на всех трёх зависимостях, добавил MDC-трейсинг, правильно объявил `MinioClient` синглтоном. Это не «сделал чтобы работало» — это осознанные решения.

Слабые места проекта системные, а не точечные. Границы слоёв размыты: `AuthController` знает о MinIO, `AuthService` знает о `HttpServletRequest`, `ResourceInfoResponseBuilder` знает о структуре MinIO-путей, доменные объекты (`Resource`, `ResourceType`) лежат в `util`. Всё это говорит о том, что архитектурные принципы пока применяются интуитивно, а не осознанно. Добавь к этому отсутствие интерфейса над MinIO-клиентом, GET для мутирующей операции, ZIP целиком в памяти и поиск, который не возвращает директории — и получится список конкретных задач на доработку.

**Продакшн-реди?** Нет. Не из-за количества замечаний, а из-за их характера: OOM при скачивании больших директорий, несоответствие ТЗ в поиске, нарушение семантики HTTP. После их устранения проект станет значительно ближе к уровню, который не стыдно выкатывать в прод. Возможно еще есть уязвимости PathTraversal, но не изучал их у тебя в проекте.
