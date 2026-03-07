[klimov-project/spring-boot-filestorage](https://github.com/klimov-project/spring-boot-filestorage/)

## ХОРОШО

1. **Интерфейс `StorageService` как точка расширения** — бизнес-логика работы с файлами скрыта за интерфейсом, контроллеры зависят только от него. Это правильное направление зависимостей: верхние слои не знают о MinIO, AWS или любом другом провайдере.

2. **Контекст в исключениях хранилища** — кастомные исключения (`ResourceNotFoundException`, `ResourceAlreadyExistsException` и др.) несут поля `userId`, `relativePath`, `operation`. Это существенно упрощает диагностику: из логов сразу видно, кто, с каким путём и в какой операции получил ошибку. Намного лучше, чем `RuntimeException("something went wrong")`.

3. **`@RestControllerAdvice` с `GlobalExceptionHandler`** — централизованная обработка ошибок присутствует. Все кастомные исключения хранилища обработаны отдельными методами с правильными HTTP-кодами 400/404/409.

4. **Rollback при ошибке переименования папки** — `MinioServiceImpl.renameDirectory()` пытается откатить уже скопированные объекты при ошибке посередине операции. Для S3-хранилища без транзакций это важный защитный механизм.

5. **Интеграционные тесты** — написаны тесты для аутентификации (`AuthControllerIntegrationTest`) и работы с ресурсами (`ResourceIntegrationTests`), покрывающие основные сценарии: регистрацию, вход, создание/удаление/перемещение/поиск файлов и папок.

---

## ЗАМЕЧАНИЯ

### структура пакетов

1. **Пакет `com.project` — смешение двух несовместимых стратегий организации кода** — проект одновременно использует два подхода к структуре пакетов. На верхнем уровне применяется слоевая организация (`controller/`, `service/`, `dto/`, `entity/`, `repository/`, `security/`). Внутри подпакета `storage/` — фичевая: `storage/controller/`, `storage/service/`, `storage/dto/`, `storage/util/`. В результате одни контроллеры лежат в `com.project.controller` (`AuthController`, `UserController`), другие — в `com.project.storage.controller` (`ResourceController`, `DownloadController`); одни сервисы в `com.project.service` (`AuthService`), другие — в `com.project.storage.service` (`MinioStorageService`). Это нарушает единый принцип навигации по проекту: новый разработчик не может предсказать, в каком пакете искать конкретный класс. При добавлении новой фичи (например, уведомлений) непонятно, куда класть сервис — в `service/` или создавать `notifications/service/`. Отсутствие единой стратегии — архитектурный долг, который накапливается с ростом проекта.

**Рекомендация:**
```
// слоевая организация (layer-by-layer), всё по типу класса:
com.project
├── config/
├── controller/          ← AuthController, UserController, ResourceController, DownloadController
├── dto/
│   ├── request/
│   └── response/        ← включая ResourceResponse, MoveResourceRequest
├── entity/
├── exception/
├── repository/
├── security/
└── service/             ← AuthService, MinioStorageService, MinioDownloadService, ...
```

---

### пакет /config

1. **Класс: `MinioConfig`, `MinioServiceImpl`, `MinioDownloadService`, поле `bucket`** — несмотря на то что `MinioConfig` использует `@ConfigurationProperties`, значение `spring.minio.bucket` дополнительно читается через `@Value("${spring.minio.bucket}")` в двух сервисных классах. Это нарушение принципа единого источника правды: при переименовании ключа нужно менять его в трёх местах. Сервисный слой не должен напрямую знать об именах конфигурационных ключей — это задача инфраструктурного слоя.

**Рекомендация:** создать отдельный `MinioProperties` и его инжектировать в конфиг/сервисы

2. **Класс: `MinioConfig`, метод: `minioClient()`** — бизнес-логика инициализации бакета (`initBucket`) вызывается прямо внутри `@Bean`-метода. Конфигурационный класс должен создавать и возвращать бин, а не запускать побочные операции с внешними сервисами. Если MinIO недоступен при старте, `@Bean` упадёт с ошибкой до того, как Spring завершит сборку контекста. Это затрудняет стратегию ретраев и делает старт приложения хрупким.

**Рекомендация:** Лучше сделать отдельный компонент в котором будет `@EventListener(ApplicationReadyEvent.class)` и будет создавать бакет

3. **Класс: `com.project.config.SecurityConfig`, метод: `getCustomAuthenticationEntryPoint()`** — в конце класса объявлен публичный геттер `getCustomAuthenticationEntryPoint()`, который нигде не вызывается. Это мёртвый код, нарушающий инкапсуляцию конфигурационного класса. `@Configuration`-классы не должны раскрывать свои поля через публичные геттеры — бины должны получаться через DI-контейнер.

**Рекомендация:**  Удалить метод:

4. **Класс: `com.project.config.SecurityConfig`, метод: `securityFilterChain()`, вызов `.deleteCookies("JSESSIONID")`** — при выходе из системы удаляется кука `JSESSIONID`. Однако в `application.yml` настроено Redis-хранилище сессий (`spring.session.store-type: redis`), при котором Spring Session использует куку `SESSION`, а не `JSESSIONID`. Куки `JSESSIONID` при нормальной работе не существует — она не будет удалена, и браузер сохранит куку `SESSION`, что не инвалидирует сессию на клиенте.

**Рекомендация:** исправить имя куки

5. **Файл: `application.yml`, свойство `spring.security.enabled: false`** — свойство `spring.security.enabled` не является стандартным свойством Spring Security и не имеет никакого эффекта. Spring Security не отключается таким образом. Это мёртвая конфигурация, вводящая в заблуждение: разработчик может подумать, что безопасность отключена, хотя это не так.

**Рекомендация:** Удалить эти настройки или действительно добавить feature-toggle

6. **Файл: `application.yml`, свойство `spring.jpa.hibernate.ddl-auto: update`** — использование `ddl-auto: update` в production-окружении опасно. Hibernate не может корректно удалять колонки, переименовывать таблицы или создавать индексы. Любое изменение схемы может пройти незаметно или привести к несоответствию структуры БД ожидаемой. ТЗ прямо рекомендует использовать Flyway или Liquibase — миграции в проекте полностью отсутствуют.

**Рекомендация:** Добавить ликвибейз или флайвей и отключить ддл-авто.

7. **Файл: `application.yml`, секция `app.auth.*`** — в `application.yml` объявлены свойства `app.auth.password.min-length: 6`, `app.auth.username.min-length: 3`, `app.auth.username.max-length: 50`. Однако в коде они нигде не читаются: ограничения жёстко прописаны в аннотациях `@Size(min = 6)` и `@Size(min = 3, max = 50)` в DTO. 
Это мёртвая конфигурация, которая создаёт иллюзию управляемости без реального эффекта.

**Рекомендация:** Либо удалить мёртвые свойства из application.yml, либо вынести их в @ConfigurationProperties и использовать их

---

### пакет /controller

1. **Класс: `AuthController`, метод: `signin()`** — метод перехватывает `BadCredentialsException` и общий `Exception` локально с помощью `try/catch`, хотя `GlobalExceptionHandler` уже объявлен с `@ExceptionHandler(AuthenticationException.class)`. В результате `@ControllerAdvice` никогда не сработает для `/sign-in`, логика маппинга ошибок дублируется в двух местах и расходится при будущих изменениях. Ответ на `Exception` возвращает 500 с текстом "Ошибка при авторизации", скрывая возможные реальные проблемы.

**Рекомендация:** Убрать try/catch — Spring сам передаст исключение в GlobalExceptionHandler

2. **Класс: `AuthController`, метод: `signup()`** — управление HTTP-сессией (`httpRequest.getSession(true)`, установка `SPRING_SECURITY_CONTEXT_KEY`) выполняется прямо в контроллере. Контроллер не должен работать с деталями Spring Security session management — это инфраструктурная ответственность. Разделение операции "зарегистрировать пользователя" между `AuthService.registerUser()` и `AuthController.signup()` нарушает SRP.

**Рекомендация:** Контроллер должен вызывать ТОЛЬКО сервис. Никакого мапинга и тд в нем не должно быть

3. **Класс: `UserController`, метод: `getCurrentUser()`** — метод проверяет `user == null` вручную и оборачивает весь код в `try/catch (Exception e)`. Если в `SecurityConfig` стоит `.anyRequest().authenticated()`, principal никогда не будет `null` для аутентифицированного пользователя. Общий `try/catch` скрывает неожиданные ошибки, а ручная проверка на `null` дублирует то, что уже делает `CustomAuthenticationEntryPoint`.

**Рекомендация:** Убрать проверку, этим занимается секьюрити

4. **Классы: `AuthController`, `UserController` — `ResponseEntity<?>` с wildcard-типом** — методы объявлены с возвращаемым типом `ResponseEntity<?>`.** Wildcard-тип лишает компилятор и Springdoc/Swagger возможности вывести схему ответа, документация API будет неполной, а несоответствие типа ответа не обнаружится до runtime.

**Рекомендация:** Указывать конкретный тип

---

### пакет /storage/controller

1. **Классы: `ResourceController`, `DownloadController`** - та же проблема, что и в `/controller` с вайлдкардами

**Рекомендация:** см выше

---

### пакет /dto

1. **Классы: `SignupRequest`, `SigninRequest`, `UserResponse`, `ErrorResponse`** — все четыре DTO написаны вручную с геттерами, сеттерами и конструкторами

**Рекомендация:** Использовать ломбок

---

### пакет /storage/dto

1. **Класс: `com.project.storage.dto.ResourceInfo`, поля `userId`, `downloadUrl`, `contentType`** — `ResourceInfo` одновременно используется как внутренний доменный объект и как тело ответа REST API. В результате API отдаёт поле `userId`. Это утечка деталей реализации через API, нарушение изоляции domain-объекта от transport-слоя.

**Рекомендация:** Разделить Точно ли надо возвращать userId? если нет, то исправить

2. **Класс: `com.project.storage.dto.ResourceResponse`** — класс уже существует и имеет правильную структуру для API-ответа (без `userId`, с `@JsonInclude`), но нигде не используется: контроллеры возвращают `ResourceInfo` напрямую. Это мёртвый код, который к тому же является именно тем решением, которое нужно применить. +без ломбок. Снова

**Рекомендация:** Ломбок+использовать либо удалить

---

### пакет /entity

1. **Класс: `com.project.entity.User`, объявление класса** — `User` одновременно является JPA-сущностью (`@Entity`) и реализует `UserDetails` из Spring Security. Это смешение persistence-слоя и security-слоя в одном классе: изменение схемы таблицы `users` (добавление/удаление поля) затронет контракт Spring Security, и наоборот. Сериализация `UserDetails` в Redis сессию потребует, чтобы JPA-сущность была `Serializable`, что создаёт риск сериализации прокси-объектов Hibernate. При появлении новых полей в `User` (например, lazy-коллекций) возможна `LazyInitializationException` при сериализации сессии.

**Рекомендация:** сделать отдельный класс для Spring Security

2. **Класс: `com.project.entity.User`, поля `createdAt`/`updatedAt` и методы `onCreate()`/`onUpdate()`** — временны́е метки устанавливаются вручную через `@PrePersist`/`@PreUpdate`, хотя Spring Data JPA предоставляет готовый механизм аудита: аннотации `@CreationTimestamp` и `@UpdateTimestamp`. Помимо этого, весь класс написан вручную: геттеры, сеттеры, конструкторы — при наличии Lombok в проекте это лишний boilerplate

**Рекомендация:**  Hibernate-аннотации + Lombok, без Serializable
```java
@Entity
@Table(name = "users") //UniqueConstraint Убран тоже. Это делается через миграцию
@Getter
@Setter
@NoArgsConstructor              
public class User {                
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

3. **Класс: `com.project.entity.MinioObject`, расположение в пакете `entity`** — `MinioObject` не является JPA-сущностью (`@Entity` отсутствует), не привязан к базе данных. Это value object, который служит промежуточным объектом для передачи данных между слоями MinIO и сервисным слоем. Его расположение в `com.project.entity` вводит в заблуждение, нарушает семантику пакета и противоречит соглашению о том, что в `entity` хранятся только бд объекты.

**Рекомендация:** Перенести в storage/dto

---

### пакет /exception

1. **Класс: `com.project.exception.GlobalExceptionHandler`, метод: `handleAllExceptions()`** — в обработчике всех непредвиденных исключений используются `System.out.println` и `ex.printStackTrace()`. В production-окружении это неприемлемо: stdout не попадает в стандартную систему сбора логов (Logback/Log4j2), нет уровня логирования, нет форматирования, нет traceId. Кроме того, `ex.getMessage()` в теле ответа (`"Внутренняя ошибка сервера: " + ex.getMessage()`) раскрывает клиенту детали реализации — пути к файлам, имена классов, SQL-ошибки.

**Рекомендация:**
```java
private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleAllExceptions(Exception ex) {
    log.error("Unhandled exception", ex);  // полный стек в логах, но не в ответе
    return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("Внутренняя ошибка сервера"));  // без деталей клиенту
}
```

2. **Класс: `com.project.exception.GlobalExceptionHandler`, отсутствие обработчика `StorageException.StorageOperationException`** — иерархия `StorageException` включает `StorageOperationException` (HTTP 500), но явного обработчика для него нет. Он попадает в общий `@ExceptionHandler(Exception.class)`, который раскрывает `ex.getMessage()` клиенту. Между тем `StorageOperationException` несёт внутреннее сообщение с деталями MinIO-операции (например, `"Ошибка при создании папки: ..."`), которое не должно попасть в ответ API.

**Рекомендация:**
```java
// GlobalExceptionHandler.java — добавить явный обработчик:
@ExceptionHandler(StorageException.StorageOperationException.class)
public ResponseEntity<ErrorResponse> handleStorageOperationException(
        StorageException.StorageOperationException ex) {
    log.error("Storage operation failed: userId={}, path={}, operation={}",
            ex.getUserId(), ex.getRelativePath(), ex.getOperation(), ex);
    return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("Ошибка хранилища"));  // без внутренних деталей
}
```

3. **Класс: `com.project.exception.StorageException` — вложенные классы исключений** — все конкретные исключения (`ResourceNotFoundException`, `ResourceAlreadyExistsException`, `InvalidPathException`, `StorageOperationException`) объявлены как статические вложенные классы внутри `StorageException`. Это неочевидный и запутывающий подход: разработчик, ищущий `ResourceNotFoundException`, не догадается искать его внутри другого файла. IDE-поиск по имени класса (`Ctrl+N`) возвращает `StorageException`, а не нужное исключение. В `GlobalExceptionHandler` каждый обработчик приходится писать как `StorageException.ResourceNotFoundException` — длинно и неинтуитивно. Наконец, сам `StorageException` как класс оказывается "пустой обёрткой": его никто не бросает и не ловит напрямую, он существует только как namespace-контейнер — роль, для которой в Java предназначены пакеты, а не классы.

**Рекомендация:** Каждому исключению — отдельный файл в пакете com.project.exception:
```java
// StorageBaseException.java — общий предок для catch-all в GlobalExceptionHandler:
public abstract class StorageBaseException extends RuntimeException {
    private final Long userId;
    private final String relativePath;
    private final String operation;
}

// ResourceNotFoundException.java:
public class ResourceNotFoundException extends StorageBaseException { ... }

// ResourceAlreadyExistsException.java:
public class ResourceAlreadyExistsException extends StorageBaseException { ... }

// InvalidPathException.java:
public class InvalidPathException extends StorageBaseException { ... }

// StorageOperationException.java:
public class StorageOperationException extends StorageBaseException { ... }

// GlobalExceptionHandler.java — читается естественно, без вложенных имён:
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) { ... }

@ExceptionHandler(StorageBaseException.class) // fallback для всей иерархии
public ResponseEntity<ErrorResponse> handleStorageBase(StorageBaseException ex) { ... }
```

---

### пакет /security

1. **Класс: `com.project.security.CustomAuthenticationEntryPoint`, поле `objectMapper`** — `ObjectMapper` создаётся через `new ObjectMapper()` в поле класса, обходя Spring DI. Spring Boot автоматически конфигурирует `ObjectMapper` с нужными модулями (Jackson JavaTimeModule, настройки сериализации). Создание нового экземпляра «вручную» означает, что все кастомные настройки Jackson (например, формат дат, обработка `null`) не применятся к ответам этого компонента.

**Рекомендация:** Инжектить сприговый обжектМаппер

---

### пакет /service

> `com.project.service` — `AuthService`, `UserDetailsServiceImpl`

1. **Класс: `com.project.service.AuthService`, зависимость от `StorageService`** — `AuthService` напрямую зависит от `StorageService` и создаёт директорию в MinIO прямо внутри транзакции регистрации. Текущее решение рабочее, но с ростом проекта в нём появятся трудности: если нужно будет добавить ещё одно действие при регистрации (например, отправку письма), `AuthService` будет обрастать всё новыми зависимостями, которые не имеют прямого отношения к аутентификации. Кроме того, MinIO-операция внутри транзакции означает, что при недоступности MinIO регистрация упадёт целиком, хотя пользователь в БД мог бы быть создан нормально.

**Рекомендация:**
```java
// UserRegisteredEvent.java:
public record UserRegisteredEvent(Long userId) {}

// AuthService.java — публикует событие, больше не зависит от StorageService:
@Transactional
public User registerUser(SignupRequest request) {
    if (userRepository.existsByUsername(request.getUsername())) {
        throw new UsernameExistsException("Username already exists");
    }
    User user = new User(request.getUsername(), passwordEncoder.encode(request.getPassword()));
    user = userRepository.save(user);
    eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));
    return user;
}

// MinioUserInitializer.java — реагирует на событие после коммита транзакции:
@Component
@RequiredArgsConstructor
public class MinioUserInitializer {
    private final StorageService storageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        storageService.createUserDirectory(event.userId());
    }
}
```

2. **Класс: `com.project.service.UserDetailsServiceImpl`, метод: `loadUserByUsername()`** — метод помечен `@Transactional`, хотя выполняет только операцию чтения (`SELECT`). Для read-only операций следует использовать `@Transactional(readOnly = true)`: это сигнализирует Hibernate об отключении dirty checking и flush, что снижает нагрузку и позволяет использовать read-only реплики.

**Рекомендация:**
```java
@Override
@Transactional(readOnly = true)
public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    return userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
}
```

---

### пакет /storage/service

1. **Классы: `MinioService`, `MinioServiceImpl`, `MinioServiceAdapter` — избыточная трёхуровневая цепочка, которая запутывает больше, чем помогает** — в проекте выстроена такая цепочка: `MinioStorageService` → `MinioServiceAdapter` → `MinioService` (interface) → `MinioServiceImpl`. На первый взгляд это похоже на паттерн Adapter, но реальная причина появления `MinioServiceAdapter` — не адаптация несовместимых интерфейсов, а компенсация недостатков `MinioServiceImpl`. `MinioServiceImpl` выбрасывает `RuntimeException` с текстовыми сообщениями (`"Folder already exists"`, `"File already exists"`), и `MinioServiceAdapter` вынужден ловить эти сообщения и угадывать тип исключения через `errorMessage.contains("already exists")`. Это не архитектурное разделение, а попытка починить один класс через другой. При этом у `MinioService`/`MinioServiceImpl` нет ни одного потребителя кроме `MinioServiceAdapter` — интерфейс `MinioService` реально не даёт возможности подменить реализацию, потому что весь бизнес-слой работает только через `MinioServiceAdapter`. В итоге три класса делают то, что мог бы делать один.

**Рекомендация:**
```java
// Ввести интерфейс S3Client — единственная точка абстракции над конкретным провайдером.
// Реализации: MinioS3Client, AwsS3Client, YandexS3Client — каждая работает с fullPath и своим SDK.
// StorageService (бывший MinioStorageService) содержит бизнес-логику: строит пути, валидирует,
// обрабатывает исключения — и вызывает S3Client. MinioService, MinioServiceImpl, MinioServiceAdapter
// удаляются как лишние слои:

// S3Client.java — интерфейс низкоуровневых операций с хранилищем:
public interface S3Client {
    void createFolder(String fullPath);
    void uploadFile(String fullPath, InputStream stream, long size, String contentType);
    void deleteObject(String fullPath);
    void copyObject(String sourceFullPath, String targetFullPath);
    boolean objectExists(String fullPath);
    List<S3Object> listObjects(String prefix, boolean recursive);
    InputStream getObject(String fullPath);
}

// MinioS3Client.java — реализация для MinIO, работает с MinioClient SDK:
@Component
@RequiredArgsConstructor
public class MinioS3Client implements S3Client {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public void createFolder(String fullPath) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(fullPath)
                    .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                    .build());
        } catch (ErrorResponseException e) {
            // Типизируем по коду S3-протокола, не по тексту сообщения:
            throw switch (e.errorResponse().code()) {
                case "NoSuchBucket" -> new StorageOperationException("Bucket не найден", null, fullPath, "createFolder");
                default -> new StorageOperationException("S3 error: " + e.errorResponse().code(), null, fullPath, "createFolder");
            };
        } catch (Exception e) {
            throw new StorageOperationException(e.getMessage(), null, fullPath, "createFolder");
        }
    }
    // остальные методы аналогично...
}

// StorageService.java — интерфейс бизнес-логики хранилища:
public interface StorageService {
    void createFolder(Long userId, String relativePath);
    List<ResourceInfo> uploadFiles(Long userId, String destinationPath, MultipartFile[] files);
    void deleteResource(Long userId, String relativePath);
    void moveResource(Long userId, String fromPath, String toPath);
    ResourceInfo getResourceInfo(Long userId, String relativePath);
    List<ResourceInfo> getDirectoryContents(Long userId, String relativePath);
    List<ResourceInfo> searchFiles(Long userId, String query);
}

// S3StorageService.java — реализация поверх S3Client, общая для MinIO/AWS/Yandex:
@Service
@RequiredArgsConstructor
public class S3StorageService implements StorageService {

    private final S3Client s3Client;

    @Override
    public void createFolder(Long userId, String relativePath) {
        String fullPath = toFullPath(userId, relativePath);
        if (s3Client.objectExists(fullPath)) {
            throw new ResourceAlreadyExistsException("Папка уже существует", userId, relativePath, "createFolder");
        }
        s3Client.createFolder(fullPath);
    }

    private String toFullPath(Long userId, String relativePath) {
        String clean = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
        return "user-" + userId + "-files/" + clean;
    }
}

// Если понадобится реализация со специфичной логикой (например, квоты, версионирование):
// class QuotaAwareStorageService implements StorageService { ... }
// class VersionedStorageService implements StorageService { ... }
```

2. **Интерфейс: `com.project.storage.service.MinioService`, метод: `isObjectExists()`** — публичный интерфейс объявляет `boolean isObjectExists(String fullPath) throws Exception`. Декларация `throws Exception` в интерфейсном методе — антипаттерн: весь вызывающий код вынужден либо объявлять `throws Exception` в сигнатуре, либо оборачивать вызов в `try/catch (Exception)`, что уничтожает типобезопасность. Это распространяется по всей цепочке вызовов — в `MinioServiceAdapter.isObjectExists()` тоже есть `throws Exception`.

**Рекомендация:** убрать checked exception из интерфейса

3. **Класс: `com.project.storage.service.MinioServiceImpl`, обработка исключений во всех методах** — по всему классу исключения оборачиваются конструкцией `catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }`. Это полностью уничтожает иерархию исключений: конкретный `ErrorResponseException` от MinIO (с кодом ошибки S3 протокола) превращается в обобщённый `RuntimeException`. Далее `MinioServiceAdapter` пытается восстановить тип исключения по строке сообщения (`contains("already exists")`), что хрупко: при изменении текста сообщения или работе с MinIO в другой локали трансформация сломается.

**Рекомендация:** ловить конкретные типы и пробрасывать кастомные исключения
```java
@Override
public void createFolder(String fullPath, boolean strict) {
    try {
        boolean exists = isObjectExists(fullPath);
        if (exists) {
            if (strict) {
                throw new StorageException.ResourceAlreadyExistsException(
                        "Folder already exists: " + fullPath, fullPath);
            }
            return;
        }
        createFolderInMinio(fullPath);
    } catch (StorageException e) {
        throw e; // пробрасываем кастомные без изменений
    } catch (ErrorResponseException e) {
        // проверяем код ошибки протокола S3, а не текст сообщения:
        String code = e.errorResponse().code();
        throw new StorageException.StorageOperationException(
                "MinIO error [" + code + "]: " + e.getMessage(), null, fullPath, "createFolder");
    } catch (Exception e) {
        throw new StorageException.StorageOperationException(
                "Unexpected error: " + e.getMessage(), null, fullPath, "createFolder");
    }
}
```

4. **Класс: `com.project.storage.service.MinioServiceAdapter`, методы `transform*Exception()`** — трансформация исключений реализована через проверку текстовых подстрок в сообщении (`errorMessage.contains("already exists")`, `errorMessage.contains("NoSuchKey")`). Это крайне хрупкий подход: MinIO SDK может изменить формулировку сообщения, текст может отличаться в разных версиях или локалях, а русскоязычные строки из кастомных исключений (`"Папка уже существует"`) только усиливают связность. Правильно — проверять коды ошибок S3-протокола.

**Рекомендация:**
```java
// Если MinioServiceImpl уже выбрасывает типизированные StorageException (см. замечание выше),
// MinioServiceAdapter упрощается: он только добавляет toFullPath() и не занимается трансформацией:
public void createFolder(Long userId, String relativePath) {
    createFolder(userId, relativePath, true);
}

public void createFolder(Long userId, String relativePath, boolean strict) {
    String fullPath = toFullPath(userId, relativePath);
    // StorageException прилетит уже типизированным из MinioServiceImpl:
    minioService.createFolder(fullPath, strict);
}
```

5. **Класс: `com.project.storage.service.MinioDownloadService`, методы: `downloadFileFromMinio()` и `downloadDirectoryAsZip()`** — при скачивании файла его содержимое целиком читается в `byte[]` (`readAllBytes(stream)`), а при скачивании папки — ZIP-архив создаётся во временном файле, затем читается в `byte[]` и оборачивается в `ByteArrayResource`. При лимите в 100MB (`max-file-size: 100MB`) каждый запрос скачивания может потребовать 100MB+ heap JVM. При нескольких одновременных скачиваниях это приведёт к `OutOfMemoryError` или существенной деградации.

**Рекомендация:**
```java
// Для файлов — стриминг через InputStreamResource или прямая запись в HttpServletResponse:
// DownloadController.java:
@GetMapping("/resource/download")
public void downloadResource(
        @AuthenticationPrincipal CustomUserDetails principal,
        @RequestParam String path,
        HttpServletResponse response) throws IOException {

    String fullPath = getFullPathForMinio(principal.getId(), path);
    String filename = extractFilename(path);

    response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + filename + "\"");
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

    try (InputStream stream = minioClient.getObject(
            GetObjectArgs.builder().bucket(bucket).object(fullPath).build())) {
        stream.transferTo(response.getOutputStream());
    }
}

// Для папок — писать ZIP прямо в response.getOutputStream() без временного файла:
try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
    for (String objectPath : getAllObjectPaths(userId, path)) {
        zos.putNextEntry(new ZipEntry(relativizeForZip(objectPath)));
        try (InputStream stream = minioClient.getObject(...)) {
            stream.transferTo(zos);
        }
        zos.closeEntry();
    }
}
```

6. **Класс: `com.project.storage.service.MinioDownloadService`, метод: `getAllFilesInFolder()`** — рекурсивный обход содержимого папки при формировании ZIP реализован через повторяющиеся вызовы `storageService.getDirectoryContents()` (нерекурсивный листинг). Это порождает N+1 запросов к MinIO: для папки с 3 уровнями вложенности будет сделано `1 + N₁ + N₂ + ...` запросов. MinIO поддерживает рекурсивный листинг через `ListObjectsArgs.builder().recursive(true)`.

**Рекомендация:**
```java
// MinioServiceImpl.java — добавить метод рекурсивного листинга:
public List<String> listAllObjectPaths(String rootFullPath) {
    List<String> paths = new ArrayList<>();
    String prefix = ensureTrailingSlash(rootFullPath);

    for (Result<Item> result : minioClient.listObjects(
            ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)  // один запрос вместо N+1
                    .build())) {
        Item item = result.get();
        if (!item.isDir()) {  // только файлы, папки-маркеры не нужны
            paths.add(item.objectName());
        }
    }
    return paths;
}

// MinioDownloadService.downloadDirectoryAsZip() — использовать один вызов:
List<String> allFilePaths = minioService.listAllObjectPaths(getFullPathForMinio(userId, path));
```

7. **Класс: `com.project.storage.service.MinioStorageService`** — большинство методов класса содержат конструкцию `try { ... } catch (Exception e) { throw e; }`, которая ничего не делает и является шаблонным мусором. Пустой `catch (Exception e) { throw e; }` не добавляет никакой ценности: исключение было бы проброшено и без него. Об этом даже пишет IDEA и предлагает удалить try-catch

**Рекомендация:** Убрать пустые try/catch

8. **Класс: `com.project.storage.service.MinioStorageService`, метод: `uploadFiles()`** — метод проверяет, что массив `files` не `null` и не пуст, но не проверяет `getOriginalFilename()` у каждого элемента. В следующей строке `String relativePath = file.getOriginalFilename()` результат сразу используется в конкатенации `destinationRelativePath + relativePath`. Метод `getOriginalFilename()` может вернуть `null` (для программно созданных `MultipartFile` без явно заданного имени). Конкатенация `String + null` в Java не даёт `NullPointerException`, но даёт строку `"path/null"` — MinIO создаст объект с буквальным именем `null`. Кроме того, если имя пустое, `fullFilePath` совпадёт с путём директории, и объект перезапишет метку папки в MinIO.

**Рекомендация:**
```java
// MinioStorageService.java — добавить проверку имени каждого файла:
@Override
public List<ResourceInfo> uploadFiles(Long userId, String destinationRelativePath, MultipartFile[] files) {
    if (files == null || files.length == 0) {
        throw new StorageException.InvalidPathException("Не указаны файлы для загрузки");
    }

    for (MultipartFile file : files) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new StorageException.InvalidPathException("Имя файла не может быть пустым или null");
        }
    }

    // дальше существующая логика createParentFoldersIfNeeded и uploadFiles
}
```

9. **Классы: `com.project.storage.service.MinioStorageService`, `MinioServiceAdapter`, `MinioServiceImpl`** — строка формата пользовательской директории `"user-" + userId + "-files"` встречается в трёх местах: `MinioStorageService.getUserFolderPath()`, `MinioServiceAdapter.toFullPath()` и `MinioServiceImpl.searchFiles()` (строка `"user-" + userId + "-files"`). Magic string без единой константы — изменение формата потребует поиска по всему проекту, риск рассинхронизации высок.

**Рекомендация:** Вынести в константу для всего проекта

10. **Класс: `com.project.storage.service.MinioServiceAdapter`, метод: `getDownloadResource()`** — метод не вызывается нигде

**Рекомендация:** Удалить метод getDownloadResource()

11. **Интерфейс: `com.project.storage.service.DownloadService`, вложенный класс `DownloadResult`** — класс `DownloadResult` определён внутри интерфейса `DownloadService`. Это смешивает контракт сервиса (interface) с конкретным типом данных. Кроме того, `DownloadResult` написан вручную с конструктором и геттерами при наличии Lombok в проекте.

**Рекомендация:** Вынести в отдельный класс + ломбок

12. **Класс: `com.project.storage.service.MinioStorageService`, метод: `extractNameFromPath()`; класс: `com.project.storage.util.PathValidator`, метод: `extractName()`** — в `MinioStorageService` есть `extractNameFromPath()`, которая дублирует логику уже существующего `PathValidator.extractName()`. Оба метода: убирают завершающий слэш, ищут последний `/`, возвращают имя без пути. Нарушение DRY — при исправлении бага в логике нужно менять в двух местах.

**Рекомендация:** Удалить extractNameFromPath() из MinioStorageService, использовать уже инжектированный pathValidator

---

### пакет /storage/util

1. **Класс: `com.project.storage.util.PathValidator`, метод: `validateAndGetType()`** — для нормализации пути используется `Paths.get(path).normalize()`, а защита от path traversal реализована через `normalizedStr.contains("..")`. Это ненадёжно: на macOS/Linux `Paths.get("folder/../etc/passwd").normalize()` вернёт `etc/passwd` (traversal произошёл, но `..` уже удалён), и проверка `contains("..")` вернёт `false`, пропустив атаку. Кроме того, `Paths.get()` может по-разному обрабатывать пути на Windows и Unix из-за различия разделителей.

**Рекомендация:** Явная проверка каждого сегмента пути
```java
public ResourceType validateAndGetType(String rawPath) {
    if (!StringUtils.hasText(rawPath)) return null;

    String path = rawPath.trim();
    if ("/".equals(path)) return ResourceType.DIRECTORY;
    if (path.startsWith("/") || path.startsWith("\\")) return null;

    boolean isDirectory = path.endsWith("/");
    String pathToValidate = isDirectory ? path.substring(0, path.length() - 1) : path;

    // Проверяем каждый сегмент (защита от path traversal без Paths.get()):
    String[] segments = pathToValidate.split("/");
    for (String segment : segments) {
        if (segment.isEmpty() || "..".equals(segment) || ".".equals(segment)) return null;
        if (!validateSegment(segment)) return null;
    }

    return isDirectory ? ResourceType.DIRECTORY : ResourceType.FILE;
}

private boolean validateSegment(String segment) {
    if (!StringUtils.hasText(segment)) return false;
    if (segment.length() > MAX_NAME_LENGTH) return false;
    if (segment.startsWith(".")) return false; // скрытые файлы
    if (segment.endsWith(" ") || segment.endsWith(".")) return false;
    // проверка запрещённых символов
    return !segment.matches(".*[\\\\?*:\"<>|].*");
}
```

2. **Класс: `com.project.storage.util.PathValidator`, метод: `validatePath()`, правило `name.startsWith(".")`** — файлы и папки, начинающиеся с точки (`.gitignore`, `.env`, `.htaccess`, `.DS_Store`), запрещены без каких-либо пояснений в коде. Это неочевидное ограничение, не указанное в ТЗ. Пользователь, пытающийся загрузить `.gitignore` или `.env`, получит ошибку валидации без понятного объяснения. Если ограничение умышленное — оно должно быть явно задокументировано и отражено в сообщении об ошибке.

**Рекомендация:** Если ограничение сохраняется, добавить явное исключение с понятным сообщением

---

### пакет /tests

1. **Классы: `com.project.AuthControllerIntegrationTest`, `com.project.ResourceIntegrationTests`** — оба тестовых класса аннотированы `@SpringBootTest` и требуют запущенных PostgreSQL, Redis и MinIO. Testcontainers добавлен в `build.gradle` (`testImplementation 'org.testcontainers:testcontainers:1.19.0'`), но не используется. Тесты не смогут выполниться в CI/CD без внешних сервисов, а результат тестирования зависит от состояния окружения (загрязнение данными между запусками).

**Рекомендация:** Использовать Testcontainers для изоляции

2. **Класс: `com.project.ResourceIntegrationTests`, аннотация: `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)`** — пересоздание всего Spring Application Context после каждого из 20 тестов означает 20 полных циклов поднятия Spring, что может занять минуты. Правильный подход — разделять тесты через уникальные данные (что уже частично делается через `UUID.randomUUID()`) и очищать базу через `@BeforeEach`, а не пересоздавать контекст.

**Рекомендация:**
```java
// Убрать @DirtiesContext, использовать @Transactional:
@SpringBootTest
class ResourceIntegrationTests {

    @Autowired UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll(); // только данные, контекст сохраняется
    }
}
```

3. **Класс: `com.project.AuthControllerIntegrationTest`, метод: `password_isHashed_inDatabase()`** — тест проверяет, что пароль хэширован, через `storedPassword.length() > 20`. Это слабая проверка: любая строка длиннее 20 символов пройдёт, включая plaintext с добавленными символами. Правильная проверка — использовать `BCryptPasswordEncoder.matches()`.

**Рекомендация:**
```java
@Autowired
private PasswordEncoder passwordEncoder;

@Test
void password_isHashed_inDatabase() throws Exception {
    String username = "hashuser";
    String plainPassword = "plainpassword";
    // ... регистрация ...

    User user = userRepository.findByUsername(username).orElseThrow();
    // Проверяем что пароль не хранится в открытом виде:
    assertNotEquals(plainPassword, user.getPassword());
    // Проверяем что BCrypt может верифицировать:
    assertTrue(passwordEncoder.matches(plainPassword, user.getPassword()),
            "BCrypt должен успешно верифицировать пароль");
}
```

4. **Класс: `com.project.ResourceIntegrationTests`, метод: `test11_renameFile()`** — тест ожидает, что в `$.name` будет полный путь (`renamedPath = basePath + "renamed.txt"`), но по ТЗ и реализации `ResourceInfo.name` должно содержать только имя файла без пути (`"renamed.txt"`). Это означает, что тест написан под некорректное поведение и маскирует нарушение API-контракта.

**Рекомендация:**
```java
// test11_renameFile() — исправить ожидание:
mockMvc.perform(patch("/api/resource/move")
        .contentType(MediaType.APPLICATION_JSON)
        .content(moveJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("renamed.txt"))  // только имя, не полный путь
        .andExpect(jsonPath("$.type").value("FILE"));
```

---

## РЕКОМЕНДАЦИИ

1. **Пересмотреть архитектуру слоя хранилища: ввести `S3Client` интерфейс.** Текущая цепочка `MinioStorageService → MinioServiceAdapter → MinioService → MinioServiceImpl` избыточна и запутана. Правильная структура: `StorageService` (интерфейс бизнес-логики) → `S3StorageService` (реализация, строит пути, валидирует) → `S3Client` (интерфейс низкоуровневых операций) → `MinioS3Client` / `AwsS3Client` / `YandexS3Client`. `MinioServiceAdapter`, `MinioService`, `MinioServiceImpl` — удалить.

2. **Выработать единую стратегию организации пакетов.** Проект одновременно использует слоевую организацию на верхнем уровне и фичевую внутри `/storage`. Нужно выбрать одно: либо всё по слоям (`controller/`, `service/`, `dto/` для всех классов), либо всё по фичам (`auth/`, `storage/` с вложенными контроллерами и сервисами). Первый способ общепринятый, и используется практически везде

3. **Заменить вложенные классы исключений на отдельные файлы.** Хранить `ResourceNotFoundException`, `ResourceAlreadyExistsException` и др. внутри `StorageException` — неочевидно. Каждому исключению — отдельный файл в пакете `exception/`, общий абстрактный предок `StorageBaseException`. IDE-поиск по имени класса перестанет вести к неожиданному файлу.

4. **Разделить `ResourceInfo` на domain-объект и DTO-ответ.** `ResourceInfo` не должен возвращаться из контроллеров напрямую — он несёт поля `userId`, `downloadUrl`, `contentType`, которые не предусмотрены API-контрактом. `ResourceResponse` уже написан в проекте, но не используется — активировать его через `ResourceResponse.from(ResourceInfo)`.

5. **Разделить `User` и `UserDetails`.** JPA-сущность не должна реализовывать `UserDetails`. Ввести отдельный `CustomUserDetails implements UserDetails, Serializable` — он хранится в Redis-сессии, `User` остаётся чистой JPA-сущностью без `Serializable`.

6. **Перейти от чтения файлов в память к стримингу при скачивании.** `byte[]` на каждый запрос скачивания при лимите 100MB — прямой путь к `OutOfMemoryError` в production. Писать напрямую в `HttpServletResponse.getOutputStream()` без промежуточного буфера, для ZIP — через `ZipOutputStream(response.getOutputStream())`.

7. **Ввести Flyway-миграции вместо `ddl-auto: update`.** Схема БД должна быть под версионным контролем. Flyway уже есть в `build.gradle` как тестовая зависимость — переместить в `implementation` и добавить начальный скрипт миграции.

8. **Применять Lombok последовательно.** Часть классов использует Lombok (`MinioObject`, `ResourceInfo`), часть — нет (`SignupRequest`, `UserResponse`, `ResourceResponse`). Единое правило: `@Getter @Setter` + `@NoArgsConstructor` для JPA-сущностей, `@Data` для простых DTO без JPA-нюансов.

9. **Не раскрывать внутренние детали в ответе 500.** `GlobalExceptionHandler` передаёт `ex.getMessage()` клиенту. Пути к файлам, имена классов, SQL-ошибки в ответе API — это утечка информации. Логировать полный стек через `log.error(...)`, клиенту отдавать только обобщённое сообщение.

10. **Ввести Testcontainers для интеграционных тестов.** Зависимость уже добавлена в `build.gradle`, но не используется. Тесты не должны зависеть от внешних сервисов — `PostgreSQLContainer`, `GenericContainer` для Redis и MinIO дадут полностью воспроизводимый запуск.

---

## ИТОГ

**Оценка: хорошо.**

Проект реализует заявленный функционал ТЗ: регистрация/аутентификация, CRUD-операции над файлами и папками в MinIO, скачивание с ZIP-архивацией, поиск. Видно стремление к архитектурному разделению: есть интерфейс `StorageService`, кастомные исключения с контекстом, `@ControllerAdvice`, rollback при переименовании. Это хорошая база — большинство новичков не доходит до этого уровня.

**Ключевые плюсы:** `StorageService` как интерфейсная абстракция над хранилищем; кастомные исключения с полями `userId/relativePath/operation` существенно упрощают диагностику; rollback при ошибке переименования папки; написаны интеграционные тесты, покрывающие основные сценарии.

**Ключевые проблемы:** трёхуровневая цепочка `MinioStorageService → MinioServiceAdapter → MinioService → MinioServiceImpl` не является паттерном Adapter — это компенсация ошибок одного класса через другой; трансформация исключений по текстовым подстрокам (`contains("already exists")`) хрупка и сломается при изменении SDK; `ResourceInfo` утекает во внешний API с внутренними полями; `User implements UserDetails` смешивает слои JPA и Security; скачивание файлов через `byte[]` создаёт риск OOM; `ddl-auto: update` без миграций опасен в production.

**Что изучить и улучшить:**
- **Интерфейсы как точки расширения** — паттерн `S3Client` интерфейс с реализациями под MinIO/AWS/Yandex: один интерфейс, несколько провайдеров, `StorageService` не знает о конкретном SDK.
- **Spring Events** (`ApplicationEventPublisher`, `@TransactionalEventListener`) — для развязки `AuthService` от инфраструктуры MinIO при регистрации.
- **Streaming в Spring MVC** — прямая запись в `HttpServletResponse.getOutputStream()` и `ZipOutputStream` без промежуточных `byte[]` для экономии памяти.
- **Flyway/Liquibase** — версионирование схемы БД вместо `ddl-auto: update`.
- **Testcontainers** — самодостаточные интеграционные тесты без зависимости от запущенных внешних сервисов.
- **S3/MinIO SDK** — типизация ошибок через `ErrorResponseException.errorResponse().code()` вместо парсинга текста сообщений.
