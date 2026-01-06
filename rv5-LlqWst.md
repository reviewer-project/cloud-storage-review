[LlqWst/cloud-file-storage](https://github.com/LlqWst/cloud-file-storage)

## ХОРОШО

1. **Абстракция хранилища от бизнес-логики** — введены интерфейсы `ResourceStorage`, `FindStorage`, `FindAlStorage`, `BucketStorage`, что позволяет легко заменить MinIO на другую реализацию (AWS S3, локальную ФС и т.д.).

2. **Отделение API-контрактов от реализации** — использование интерфейсов `*Api` (`ResourceApi`, `DirectoryApi` и др.) для объявления эндпоинтов. Улучшает читаемость и поддерживаемость

3. **Правильное использование DTO** — разделение на request/response DTO. Сущности JPA не утекают в REST-ответы.

4. **Использование MapStruct** — `ResourceResponseMapper` для маппинга между внутренними объектами и DTO, что уменьшает boilerplate-код.

5. **Корректная работа с путями** — выделены `PathProcessor`, `PathNormalizer`, `PathParser`, `PathValidator` для централизованной обработки и валидации путей.

6. **Хорошее тестовое покрытие** — интеграционные тесты с Testcontainers для PostgreSQL и Redis, unit-тесты для сервисного слоя.

---

## ЗАМЕЧАНИЯ

### пакет /annotation

1. Кастомные аннотации `@Username` и `@StrongPassword` избыточны — можно заменить стандартными Jakarta Bean Validation:

```java
// Сейчас — кастомные валидаторы с дублированием логики
@Username
String username;

@StrongPassword  
String password;
```

**Проблемы:**
- Дублируют функциональность стандартных аннотаций
- `Pattern.compile()` на каждый вызов в `UsernameValidator` (строка 26)
- Усложняют кодовую базу без реальной пользы

**Рекомендация:** использовать стандартные аннотации Jakarta:
```java
public record RegistrationRequestDto(
    @NotBlank(message = "Username is required")
    @Size(min = 5, max = 20, message = "Username must be 5-20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9 ~!#$%^&*()_=+/'\".-]+$", message = "Invalid username characters")
    String username,

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    String password
) {}
```

2. **БАГ в `StrongPasswordValidator`** — пароль из пробелов с одним символом проходит валидацию:

```java
// Сейчас — баг с пробелами
public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null || value.isBlank() || ...) {  // "  a  ".isBlank() = false!
        return false;
    }
    return true;  // пароль "     a" пройдёт валидацию!
}
```

**Проблема:** `isBlank()` проверяет только строки из одних пробелов. Пароль `"     a     "` (пробелы + буква) пройдёт валидацию, хотя реальная длина 1 символ.

**Рекомендация:** либо запретить пробелы в паролях, либо использовать `trim()`:
```java
@Size(min = 8)
@Pattern(regexp = "^\\S+$", message = "Password cannot contain spaces")
String password
```

3. **Плохая практика** — ограничение максимальной длины пароля (20 символов):

```properties
app.max.length.password=20  # слишком мало!
```

**Проблема:** NIST и OWASP рекомендуют НЕ ограничивать максимальную длину или ставить минимум 64-128 символов. Длинные парафразы (`correct-horse-battery-staple`) безопаснее коротких сложных паролей.

**Рекомендация:** убрать ограничение max или поставить 128+:
```java
@Size(min = 8, max = 128)
String password
```

---

### пакет /config

1. `JacksonConfig` создаёт `ObjectMapper` вручную без настроек Spring Boot:

```java
// JacksonConfig.java — создаёт ObjectMapper без настроек
@Bean
public ObjectMapper objectMapper() {
    return new ObjectMapper(); // без поддержки Java 8 Date/Time и др.
}
```

Используется в `RedisSessionConfig`:
```java
// RedisSessionConfig.java — инжектит JacksonConfig напрямую
@RequiredArgsConstructor
public class RedisSessionConfig {
    private final JacksonConfig jacksonConfig;  // зависимость от конкретного класса

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        ObjectMapper mapper = jacksonConfig.objectMapper().copy();  // вызов метода напрямую
        // ...
    }
}
```

**Рекомендация:** удалить `JacksonConfig`, инжектировать стандартный бин `ObjectMapper`:
```java
@Configuration
@RequiredArgsConstructor
public class RedisSessionConfig {
    private final ObjectMapper objectMapper;  // Spring Boot инжектирует автоматически

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        ObjectMapper mapper = objectMapper.copy();
        mapper.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
```

2. `BucketInitializer` не обрабатывает ошибки при недоступности MinIO:

```java
// Сейчас
@EventListener(ApplicationReadyEvent.class)
public void onApplicationReady() {
    bucketStorage.createBucketIfNotExists(); // если MinIO недоступен — молча падает
}
```

**Рекомендация:**
```java
@EventListener(ApplicationReadyEvent.class)
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
public void onApplicationReady() {
    bucketStorage.createBucketIfNotExists();
}

@Recover
public void recover(Exception e) {
    throw new IllegalStateException("Failed to initialize MinIO bucket after retries", e);
}
```

---

### пакет /controller

1. В `RegistrationController` несколько проблем:

```java
// Сейчас
return buildCreatedResponse(new UserResponseDto(user.getUsername()), "id/" + user.getId());
```

**Проблемы:**
- Ручное создание DTO вместо маппера
- **Magic string** `"id/"` — жёстко прописана строка

**Рекомендация:** использовать MapStruct и константу:
```java
// Создать UserMapper (аналогично существующему ResourceResponseMapper)
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserResponseDto toDto(User user);
}

// В контроллере
@RestController
@RequiredArgsConstructor
public class RegistrationController extends BaseController implements RegistrationApi {
    
    private static final String USER_LOCATION_PATTERN = "id/%d";  // или вынести в общий класс констант
    
    private final AuthService authService;
    private final UserMapper userMapper;

    @Override
    public ResponseEntity<UserResponseDto> createUser(RegistrationRequestDto registrationRequest) {
        User user = authService.registrationAndLogin(registrationRequest);
        String location = String.format(USER_LOCATION_PATTERN, user.getId());
        return buildCreatedResponse(userMapper.toDto(user), location);
    }
}
```

2. В `UploadService` нет валидации имени файла и содержимого:

```java
// Сейчас — file.getOriginalFilename() может быть null/empty
private static String getFilePath(MultipartFile file, String requestedFolderPath) {
    return requestedFolderPath + file.getOriginalFilename();  // NPE или путь = директория!
}
```

**Проблема:** если `getOriginalFilename()` вернет `null` или пустую строку:
- `getFilePath` вернет просто `requestedFolderPath` (директорию)
- `validateOnExistence` проверит существование директории
- Директория существует → выбросится `AlreadyExistException("Resource already exists: ")`

**Рекомендация:**
```java
public List<ResourceResponseDto> upload(String rawPath, long id, MultipartFile[] files) {
    // Валидация массива файлов
    if (files == null || files.length == 0) {
        throw new BadRequestException("No files provided");
    }
    
    // Валидация каждого файла
    for (MultipartFile file : files) {
        if (file.isEmpty()) {
            throw new BadRequestException("File is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BadRequestException("File name is missing");
        }
    }
    
    String requestedFolderPath = pathProcessor.processDir(rawPath).requestedPath();
    // ... остальная логика
}
```

---

### пакет /controller/api

1. Избыточная Swagger-документация с ручными примерами:

```java
// Сейчас (избыточно) — 700+ строк в ResourceApi
@ApiResponse(
    responseCode = "200",
    content = @Content(
        examples = @ExampleObject(
            value = """
                {
                  "path": "",
                  "name": "file",
                  "size": 180771,
                  "type": "FILE"
                }
                """
        )
    )
)
```

**Рекомендация:** Springdoc автоматически генерирует примеры из `@Schema`. Кроме того, использовать Jakarta Bean Validation в DTO:
```java
// В DTO используйте @Schema + валидация
public record FileResponseDto(
    @Schema(example = "folder1/folder2/", description = "Path to resource")
    @NotNull String path,
    
    @Schema(example = "test.txt", description = "Resource name")
    @NotBlank @Size(max = 200) String name,
    
    @Schema(example = "123", description = "Size in bytes")
    @NotNull @Min(0) Long size,
    
    @Schema(example = "FILE", description = "Resource type")
    @NotNull Type type
) {}

// В API — только ссылка на схему
@ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = FileResponseDto.class)))
```

**Зачем валидация в response DTO:**
- **Защита от багов** — если сервис вернул некорректные данные (null, отрицательный size), валидация поймает проблему до отправки клиенту
- **Документация** — аннотации служат явным контрактом API ("name не может быть null")
- **Автоматическая генерация OpenAPI schema** — Springdoc использует аннотации для генерации корректной схемы (required fields, constraints)
- **Fail-fast** — ошибки обнаруживаются на стороне сервера, а не у клиента

2. `GET /resource/move` семантически неверен — GET не должен изменять состояние.

**Примечание:** это ошибка в ТЗ проекта (я разговаривал с Сергеем), реализация корректно следует ТЗ. В реальном проекте использовать `PUT` или `PATCH`.

---

### пакет /dto

1. **Jakarta Bean Validation практически не используется** — только в `RegistrationRequestDto` есть валидация. Все остальные DTO (request и response) не имеют аннотаций валидации:

```java
// Сейчас — нет валидации
public record AuthRequestDto(String username, String password) {}

public record ResourceResponseDto(String path, String name, Long size, Type type) {}

public record FileResponseDto(String path, String name, Long size, Type type) {}

public record DirectoryResponseDto(String path, String name, Type type) {}
// ... и другие
```

**Проблемы:**
- **Request DTO без валидации** — некорректные данные доходят до бизнес-логики
- **Response DTO без валидации** — если сервис вернул `null` в обязательном поле, клиент получит невалидный JSON
- **Нет документации контракта** — непонятно какие поля обязательны, какие ограничения
- **Некорректная OpenAPI схема** — Springdoc не знает что поля required

**Рекомендация:** добавить Jakarta Bean Validation во все DTO:

**Request DTO:**
```java
public record AuthRequestDto(
    @NotBlank(message = "Username is required")
    @Size(min = 5, max = 20, message = "Username must be 5-20 characters")
    @Pattern(regexp = "^[a-zA-Z0-9 ~!#$%^&*()_=+/'\".-]+$")
    String username,
    
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
    String password
) {}
```

**Response DTO:**
```java
public record ResourceResponseDto(
    @NotNull String path,
    @NotBlank @Size(max = 200) String name,
    @NotNull @Min(0) Long size,
    @NotNull Type type
) {}
```

**Зачем валидация в Response DTO:**
- **Документация контракта API** — аннотации явно показывают какие поля обязательны, какие ограничения (`@NotNull`, `@Size`, `@Min`)
- **Автогенерация OpenAPI схемы** — Springdoc использует аннотации для генерации корректной схемы с `required: true` и constraints
- **Защита от багов в тестах** — если написать тест с `@Valid` на response, можно поймать ошибки маппинга на этапе разработки
- **Явный контракт** — код самодокументируется, понятно что может быть null, а что нет

**Примечание:** автоматическая валидация каждого response в production избыточна (performance overhead). Достаточно использовать аннотации для документации и проверять в тестах:
```java
// В интеграционных тестах
@Test
void shouldReturnValidResponse() {
    ResourceResponseDto response = restTemplate.getForObject("/api/resource?path=/", ResourceResponseDto.class);
    
    Set<ConstraintViolation<ResourceResponseDto>> violations = validator.validate(response);
    assertTrue(violations.isEmpty(), "Response DTO should be valid");
}
```

---

### пакет /entity

1. Константа `TIME_TO_EXPIRE` имеет разный смысл в разных контекстах:

```java
// Сейчас (сбивает с толку)
public static final int TIME_TO_EXPIRE = 30;

private LocalDateTime credentialsExpireAt = LocalDateTime.now().plusMonths(TIME_TO_EXPIRE); // месяцы
private LocalDateTime accountExpiresAt = LocalDateTime.now().plusDays(TIME_TO_EXPIRE);      // дни
```

**Рекомендация:**
```java
public static final int CREDENTIALS_EXPIRE_MONTHS = 30;
public static final int ACCOUNT_EXPIRE_DAYS = 30;

private LocalDateTime credentialsExpireAt = LocalDateTime.now().plusMonths(CREDENTIALS_EXPIRE_MONTHS);
private LocalDateTime accountExpiresAt = LocalDateTime.now().plusDays(ACCOUNT_EXPIRE_DAYS);
```

---

### пакет /exception

1. `StorageException` не обрабатывается в `ApplicationExceptionHandler`:

```java
// Сейчас — StorageException попадает в общий Exception handler
@ExceptionHandler({Exception.class, InternalErrorException.class, SerializationException.class})
public ResponseEntity<ErrorResponseDto> handleUniversalException(Exception e) {
    return buildInternalServerErrorResponse("Internal error"); // generic сообщение
}
```

**Рекомендация:**
```java
@ExceptionHandler(StorageException.class)
public ResponseEntity<ErrorResponseDto> handleStorageException(StorageException e) {
    log.error("Storage error: {}", e.getMessage(), e);
    return buildInternalServerErrorResponse("Storage service error: " + e.getMessage());
}
```

---

### пакет /exception_handler

1. Название пакета с underscore не соответствует Java conventions:

```
// Сейчас
exception_handler/
├── ApplicationExceptionHandler.java
└── BaseHandler.java
```

**Рекомендация:**
```
exception/
├── AlreadyExistException.java
├── BadRequestException.java
├── ...
└── handler/
    ├── ApplicationExceptionHandler.java
    └── BaseExceptionHandler.java
```

2. В `BaseHandler` инжекция `@Value` в абстрактный класс:

```java
// Сейчас (неявная зависимость)
public abstract class BaseHandler {
    @Value("${spring.servlet.multipart.max-file-size}")
    protected String maxFileSize;
}
```

**Рекомендация:**
```java
@ConfigurationProperties(prefix = "spring.servlet.multipart")
public record MultipartProperties(String maxFileSize, String maxRequestSize) {}

@RequiredArgsConstructor
public abstract class BaseExceptionHandler {
    protected final MultipartProperties multipartProperties;
}
```

3. **Magic strings** в сообщениях об ошибках:

```java
// Сейчас — дублирование сообщений по всему коду
// ApplicationExceptionHandler.java
return buildBadRequestResponse("Missing required parameter: " + ex.getParameterName());
return buildBadRequestResponse("Missing required parameter: " + ex.getRequestPartName());
return buildBadRequestResponse("Missing body type");
return buildBadRequestResponse("Incorrect body Type");
return buildBadRequestResponse("Failed to process uploaded");
return buildNotAllowed("Method not supported: " + e.getMethod());
return buildNotFoundResponse("Not Found");
return buildInternalServerErrorResponse("Internal error");

// ValidationStorageServiceImpl.java
throw new AlreadyExistException("Resource already exists: " + path);
throw new NotFoundException("Resource doesn't exists: " + path);
throw new NotFoundException("Parent path doesn't exist: " + parentPath);

// ModificationService.java
throw new BadRequestException("You cannot cut resource to itself");
throw new BadRequestException("You can't move the root directory");
throw new BadRequestException("The resource types must match");

// PathValidator.java
throw new BadRequestException("Path cannot start with '/'");
throw new BadRequestException("Path cannot be null");
// ... и еще 20+ мест
```

**Проблемы:**
- Дублирование строк по всему проекту
- Сложность поддержки (изменить текст = найти все вхождения)
- Нет единого стиля сообщений
- Невозможность локализации (i18n)

**Рекомендация:** создать enum или класс констант для сообщений об ошибках:
```java
// ErrorMessages.java
public final class ErrorMessages {
    public static final String MISSING_PARAMETER = "Missing required parameter: %s";
    public static final String RESOURCE_NOT_FOUND = "Resource doesn't exists: %s";
    public static final String RESOURCE_ALREADY_EXISTS = "Resource already exists: %s";
    public static final String PATH_CANNOT_START_WITH_SLASH = "Path cannot start with '/'";
    public static final String CANNOT_MOVE_TO_ITSELF = "You cannot cut resource to itself";
    // ...
    
    private ErrorMessages() {} // utility class
}

// Использование
throw new NotFoundException(String.format(ErrorMessages.RESOURCE_NOT_FOUND, path));
```

Или вынести в `messages.properties` для i18n (разные языки):
```properties
error.resource.notFound=Resource doesn't exists: {0}
error.resource.alreadyExists=Resource already exists: {0}
error.path.cannotStartWithSlash=Path cannot start with '/'
```

---

### пакет /infrastructure/parser

1. Выглядит как опечатка в имени метода `pars()`:

```java
// Сейчас
public ProcessedPath pars(String normalizedPath) { ... }
```

**Рекомендация:**
```java
public ProcessedPath parse(String normalizedPath) { ... }
```

---

### пакет /infrastructure/path_processor

1. уже писал что эт не джава нейм конвеншн

---

### пакет /infrastructure/validator

1. `CredentialsValidator` дублирует логику валидации длины, которая уже есть в `UsernameValidator` и `StrongPasswordValidator` (пакет `/annotation`). Нарушается принцип DRY + я уже сказал что лучше избавиться вообще от этого:

```java
// Сейчас — дублирование логики
public class CredentialsValidator {
    private boolean isIncorrectUsernameLength(String username) {
        return username.length() < minLengthUsername || username.length() > maxLengthUsername;
    }
    
    private boolean isIncorrectPasswordLength(String password) {
        return password.length() < minLengthPassword || password.length() > maxLengthPassword;
    }
}
```

**Рекомендация:** удалить `CredentialsValidator`, добавить аннотации валидации в `AuthRequestDto`

2. Можно чуть-чуть оптимизировать итерацию по символам в `PathValidator`:

```java
// Сейчас
private void validateOnForbiddenChars(String path) {
    for (char c : path.toCharArray()) {  // создаёт новый массив
        if (forbiddenSet.contains(c)) {
            throw new BadRequestException(...);
        }
    }
}
```

**Рекомендация:**
```java
private void validateOnForbiddenChars(String path) {
    boolean hasForbidden = path.chars()
        .anyMatch(c -> forbiddenSet.contains((char) c));
    if (hasForbidden) {
        throw new BadRequestException(...);
    }
}
```

---

### пакет /repository

1. `UserRoleRepository` не используется в коде:

```java
// UserRoleRepository.java — пустой интерфейс, нигде не инжектится
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
}
```

**Проблема:** работа с ролями происходит через каскад от `User` (поле `userRoles` с `CascadeType.ALL`). Отдельный репозиторий для `UserRole` не нужен и создаёт путаницу.

**Рекомендация:** удалить `UserRoleRepository`.

---

### пакет /repository/storage

1. **Неправильная структура пакетов** — `storage` находится внутри `repository`, хотя это разные слои архитектуры:

```
repository/
├── UserRepository.java          # JPA репозиторий (доступ к БД)
├── UserRoleRepository.java      # JPA репозиторий (доступ к БД)
└── storage/                     # клиенты для MinIO (внешний API)
    ├── ResourceStorage.java
    ├── minio/
    │   ├── MinioResourceStorage.java
    │   └── ...
```

**Проблема:**
- **Смешение слоёв** — `repository` в Spring/JPA контексте означает доступ к БД через Spring Data. MinIO клиенты — это адаптеры для внешнего API, не репозитории.
- **Нарушение Clean Architecture** — storage это Infrastructure слой, а не Data Access слой
- **Путаница** — новый разработчик ожидает найти в `repository` только JPA репозитории

**Рекомендация:** переименовать и реструктурировать:
```
repository/                      # только JPA репозитории
├── UserRepository.java
└── UserRoleRepository.java

infrastructure/
└── storage/                     # клиенты для внешнего хранилища
    ├── StorageClient.java       # или MinioClient
    ├── minio/
    │   ├── MinioStorageClient.java
    │   └── ...

service/
└── storage/
    └── provider/
        ├── StorageService.java  # интерфейс
        └── impl/
            └── MinioStorageService.java  # реализация через MinioClient
```

2. Опечатка в имени интерфейса:

```java
// Сейчас
public interface FindAlStorage<T> { ... }
```

**Рекомендация:**
```java
public interface FindAllStorage<T> { ... }
```

3. Дублирование `@Value` для `bucketName` в нескольких классах:

```java
// Сейчас — в каждом Minio*Storage
@Value("${app.bucket.name}")
private String bucketName;
```

**Рекомендация:**
```java
@ConfigurationProperties(prefix = "app.bucket")
public record MinioProperties(String name) {}

@Component
@RequiredArgsConstructor
public class MinioResourceStorage implements ResourceStorage {
    private final MinioProperties minioProperties;
    
    // использование: minioProperties.name()
}
```

4. Некорректное сообщение в логе:

```java
// Сейчас
if (e instanceof ErrorResponseException respExp) {
    log.error("Race condition happened. ...");  // не всегда race condition
}
```

**Рекомендация:**
```java
if (e instanceof ErrorResponseException respExp) {
    log.error("MinIO error response: code={}, message={}", 
        respExp.errorResponse().code(), 
        respExp.errorResponse().message(), 
        respExp);
}
```

---

### пакет /security

1. В `SecurityConfig` поля и методы нарушают Java naming conventions:

```java
// Сейчас (плохо)
private final RequestMatcher LogoutMatcher = ...;  // должно быть logoutMatcher

private static void UnauthorizedResponse(HttpServletResponse res) // должно быть unauthorizedResponse
```

**Рекомендация:**
```java
private final RequestMatcher logoutMatcher = PathPatternRequestMatcher
        .withDefaults()
        .matcher(SIGN_OUT_URL);

private static void unauthorizedResponse(HttpServletResponse res) throws IOException {
    // ...
}
```

2. В `SecurityConfig` жёстко прописаны IP-адреса:

```java
// Сейчас (плохо)
private static final List<String> ALLOWED_ORIGINS = Arrays.asList(
    "http://localhost:3000",
    "http://217.60.5.12:8080"  // hardcoded IP
);
```

**Рекомендация:**
```properties
# application.properties
app.cors.allowed-origins=http://localhost:3000,http://localhost:8080
```
```java
@Value("${app.cors.allowed-origins}")
private List<String> allowedOrigins;
```

3. **Magic string** `"SESSION"` в конфигурации logout:

```java
// Сейчас — жёстко прописано имя cookie
.logout(conf -> conf
    .deleteCookies("SESSION")  // magic string
)
```

**Рекомендация:**
```java
private static final String SESSION_COOKIE_NAME = "SESSION";

.logout(conf -> conf
    .deleteCookies(SESSION_COOKIE_NAME)
)
```

4. `@Data` на `CustomUserDetails` генерирует проблемный `equals()`/`hashCode()`:

```java
// Сейчас
@Data
public class CustomUserDetails implements UserDetails {
    private Collection<? extends GrantedAuthority> authorities;
    // @Data генерирует equals/hashCode по authorities — проблема при десериализации
}
```

**Рекомендация:**
```java
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomUserDetails implements UserDetails {
    // без автогенерации equals/hashCode
}
```

5. `TomcatErrorFilter` создаёт `ObjectMapper` при каждой ошибке:

```java
// Сейчас
public void doFilter(...) {
    // ...
    new ObjectMapper().writeValue(httpResponse.getOutputStream(), ...);
}
```

**Рекомендация:**
```java
@RequiredArgsConstructor
public class TomcatErrorFilter implements Filter {
    private final ObjectMapper objectMapper;
    
    public void doFilter(...) {
        objectMapper.writeValue(httpResponse.getOutputStream(), ...);
    }
}
```

---

### пакет /service

1. **Race conditions** в `CreationService` и `ModificationService` — проверка существования и операция не атомарны:

```java
// CreationService.java
/*
TODO: возможен race condition, т.к. сначала идет проверка.
Возможно, следует блокировать папку во время чека.
MINIO не выбрасывает exception, если существует файл с именем, создаваемой папки
*/

// ModificationService.java
/*
TODO: возможен race condition, т.к. сначала идет проверка.
Возможно, следует блокировать папку во время чека.
MINIO перезаписывает папку, если существует файл с именем папки
*/
```

**Проблема:** классический TOCTOU (Time-Of-Check-Time-Of-Use):
```java
// Поток 1 и Поток 2 одновременно создают папку
validationService.validateOnExistence(path, userId);  // оба проходят проверку
storageService.createDir(path, userId);               // оба создают → race condition
```

**Решения:** (были сгенерированы ИИ и провалидиованы мной)

**Вариант 1: Использовать conditional requests MinIO**
```java
// MinioResourceStorage.java
public void createDir(String path, long userId) {
    try {
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .object(path)
                .headers(Map.of("x-amz-if-none-match", "*"))  // создать только если не существует
                .stream(new ByteArrayInputStream(new byte[0]), 0, -1)
                .build()
        );
    } catch (ErrorResponseException e) {
        if (e.errorResponse().code().equals("PreconditionFailed")) {
            throw new AlreadyExistException("Resource already exists: " + path);
        }
        throw new StorageException("Error during creation", e);
    }
}
```

**Вариант 2: Оптимистичная стратегия (проще)**
```java
// Убрать предварительную проверку, полагаться на исключения от MinIO
public DirectoryResponseDto createDir(String rawPath, long id) {
    ProcessedPath path = pathProcessor.processDir(rawPath);
    
    try {
        creationStorageService.createDir(path.requestedPath(), id);  // MinIO вернёт ошибку если существует
        return mapper.toDirectoryResponseDto(path);
    } catch (StorageException e) {
        // Проверить код ошибки MinIO
        if (isAlreadyExistsError(e)) {
            throw new AlreadyExistException("Resource already exists: " + path.requestedPath());
        }
        throw e;
    }
}
```

**Рекомендация:** для файлового хранилища оптимален **Вариант 1** (conditional requests) — нативная поддержка MinIO/S3, без внешних зависимостей типа Redis.

2. **Неэффективный поиск** в `FindService.searchResource()` — загрузка всех файлов пользователя в память:

```java
// FindService.java
/*
TODO: Для поиска по query загружаются все ресурсы пользователя.
Необходим более оптимальный подход
*/

public List<ResourceResponseDto> searchResource(String query, long id) {
    validator.validatePath(query);
    return findStorageService.findAllResources(id)  // загрузка ВСЕХ файлов пользователя!
            .filter(resource -> resource
                    .name()
                    .toLowerCase()
                    .contains(query.toLowerCase()))  // фильтрация в Java
            .toList();
}
```

**Проблемы:**
- **O(N) сложность** — чем больше файлов у пользователя, тем медленнее поиск
- **Загрузка всех метаданных** — даже если нужен 1 файл, загружаются все
- **Нагрузка на MinIO** — приходится получать список всех объектов пользователя
- **Не масштабируется** — при 10,000+ файлов запрос будет очень медленным

**Рекомендация:** добавить таблицу `file_metadata` в PostgreSQL:

```sql
CREATE TABLE file_metadata (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(200) NOT NULL,
    path VARCHAR(500) NOT NULL,
    size BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,  -- FILE или DIRECTORY
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_user_path UNIQUE (user_id, path)
);

CREATE INDEX idx_file_metadata_user_name ON file_metadata(user_id, name);
CREATE INDEX idx_file_metadata_user_path ON file_metadata(user_id, path);
```

Использование:
```java
// FileMetadataRepository.java — Spring Data генерирует запрос автоматически
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
    
    List<FileMetadata> findByUserIdAndNameContainingIgnoreCase(Long userId, String name);
    
    Page<FileMetadata> findByUserIdAndNameContainingIgnoreCase(
        Long userId, 
        String name, 
        Pageable pageable
    );
}

// FindService.java — O(1) поиск по индексу
public List<ResourceResponseDto> searchResource(String query, long id) {
    validator.validatePath(query);
    return fileMetadataRepository
            .findByUserIdAndNameContainingIgnoreCase(id, query)
            .stream()
            .map(mapper::toDto)
            .toList();
}

// С пагинацией
public Page<ResourceResponseDto> searchResource(String query, long id, Pageable pageable) {
    return fileMetadataRepository
            .findByUserIdAndNameContainingIgnoreCase(id, query, pageable)
            .map(mapper::toDto);
}
```

**Дополнительные преимущества:**
- Full-text search через PostgreSQL (tsvector)
- Фильтрация по размеру, дате создания, типу
- Пагинация результатов
- Аналитика (топ файлов, общий размер и т.д.)

**Trade-off:** нужна синхронизация между PostgreSQL и MinIO (при upload/delete/move обновлять обе системы).

---

### пакет /service/storage/provider/impl

1. Зависимость от реализации вместо интерфейса (нарушение DIP):

```java
// Сейчас (плохо)
@Service
public class ModificationStorageServiceImpl implements ModificationStorageService {
    private final FindStorageServiceImpl findService;  // конкретная реализация!
}
```

**Рекомендация:**
```java
@Service
public class ModificationStorageServiceImpl implements ModificationStorageService {
    private final FindStorageService findService;  // интерфейс
}
```

---

## РЕКОМЕНДАЦИИ

1. **Реструктурировать пакеты** — вынести MinIO клиенты из `repository/storage` в отдельный пакет `adapter/minio` или `infrastructure/storage`. `repository` должен содержать только JPA репозитории для доступа к БД.

2. **Исправить race conditions** — использовать conditional requests MinIO (`x-amz-if-none-match: *`) для атомарных операций создания/изменения ресурсов, либо distributed locks через Redis.

3. **Выделить отдельный сервис для управления пользователями** — `UserService` для CRUD-операций с пользователями, отделив его от `AuthService`.

4. **Можно ввести транзакционность для составных операций** — регистрация пользователя с созданием директории должна быть атомарной. Рассмотреть паттерн Saga или Outbox.

5. **Добавить индекс файлов в БД** — создать таблицу `file_metadata` с индексом по `name` и `owner_id` для эффективного поиска.

6. **Использовать Jakarta Bean Validation во всех DTO** — добавить аннотации (`@NotNull`, `@NotBlank`, `@Size`, `@Min`, `@Pattern`) как в request, так и в response DTO. Это обеспечит:
   - Валидацию входных данных на уровне контроллера (request)
   - Документацию контракта API (явный контракт через аннотации)
   - Корректную генерацию OpenAPI схемы (required fields, constraints)
   - Проверку корректности response в интеграционных тестах

7. **Использовать `@ConfigurationProperties`** — вместо множественных `@Value` создать типизированные классы конфигурации.

8. **Вынести magic strings в константы или properties** — создать `ErrorMessages` или использовать `messages.properties` для всех текстовых сообщений об ошибках. Это улучшит читаемость, упростит поддержку и позволит добавить i18n.
---

## ИТОГ

Проект выполнен на хорошем уровне, демонстрирует понимание Spring Boot, слоистой архитектуры и базовых принципов ООП. Основные требования ТЗ выполнены: REST API для работы с файлами, интеграция с MinIO, аутентификация через Spring Security с сессиями в Redis, интеграционные тесты с Testcontainers. Хорошо реализована абстракция хранилища через интерфейсы и корректное разделение на request/response DTO.

Основные замечания касаются архитектурных решений: неправильная структура пакетов (`repository/storage` для MinIO клиентов), race conditions в операциях создания/изменения (check-then-act не атомарен), практически полное отсутствие Jakarta Bean Validation в DTO. Также стоит обратить внимание на неэффективный поиск файлов (загружаются все файлы пользователя в память) и обилие magic strings в сообщениях об ошибках — это усложняет поддержку и делает невозможной локализацию. Рекомендую использовать кастомные аннотации валидации (`@Username`, `@StrongPassword`) только если они действительно добавляют ценность — в текущей реализации они избыточны и лучше заменить их стандартными Jakarta аннотациями.

Для дальнейшего роста рекомендую: изучить паттерны работы с распределёнными системами (Saga, Outbox для согласованности между PostgreSQL и MinIO), углубиться в S3 API (conditional requests для атомарных операций, multipart upload), добавить метрики и трейсинг для production-ready решения.
