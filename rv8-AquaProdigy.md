[AquaProdigy/CloudStorage](https://github.com/AquaProdigy/CloudStorage)

## ХОРОШО

1. **Использование интерфейсов для абстракции работы с хранилищем** — `ResourceRepository` (интерфейс) отделен от `ResourceRepositoryImpl` (MinIO реализация), что позволяет заменить хранилище (AWS S3, локальная ФС) без изменения сервисного слоя. Однако DIP применен только частично: контроллеры не выведены в API интерфейсы, сервисы — в бизнес-интерфейсы.

2. **Централизованная валидация и обработка путей** — выделен утилитный класс `PathUtil` с методами валидации путей, защитой от path traversal (`..`, `\\`), нормализацией и построением полных путей пользователей. Вся логика работы с путями в одном месте.

3. **Использование `StreamingResponseBody` для скачивания файлов** — вместо загрузки файла целиком в память, данные стримятся напрямую из MinIO в HTTP-ответ. Это позволяет скачивать файлы любого размера без OutOfMemoryError.

4. **Энум для централизации сообщений об ошибках** — `ApiErrors` содержит все текстовые сообщения с поддержкой форматирования через `String.format()`. Это упрощает поддержку, обеспечивает единообразие и потенциально позволяет добавить i18n.

5. **Правильное использование `@Transactional` в критичных местах** — метод `UserService.register()` помечен `@Transactional`, что гарантирует атомарность операции создания пользователя в БД.

6. **Использование enum для типов ресурсов** — `TypeResource` (FILE/DIRECTORY) вместо строк/констант улучшает type safety, предотвращает опечатки и позволяет компилятору проверять корректность использования.

---

## ЗАМЕЧАНИЯ

### пакет /advice

1. **Некорректная обработка `MaxUploadSizeExceededException`**

В `GlobalExceptionHandler.java` импортирован `MaxUploadSizeExceededException` (строка 15), но нет handler-метода для него:

```java
import org.springframework.web.multipart.MaxUploadSizeExceededException;
```

**Проблема:** при превышении лимита загрузки пользователь получит generic 500 ошибку вместо понятного 400 Bad Request.

**Рекомендация:** написать handler

2. **Возврат `ResponseEntity<?>` вместо конкретного типа**

В `GlobalExceptionHandler.handleMethodArgumentNotValidException()` (строка 26) возвращается `ResponseEntity<?>`:

```java
public ResponseEntity<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
```

**Проблема:** wildcard `?` снижает type safety и читаемость кода.

**Рекомендация:**
```java
public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
        MethodArgumentNotValidException ex
) {
    // ...
}
```

---

### пакет /config

1. **Создание `ObjectMapper` при каждом запросе с ошибкой аутентификации**

В `SecurityConfig.java` (строки 56-57) создаётся новый `ObjectMapper` для сериализации каждого ответа при ошибке аутентификации:

```java
ObjectMapper objectMapper = new ObjectMapper();
objectMapper.writeValue(response.getWriter(), new ErrorResponse(...));
```

**Проблема:**
- Создание `ObjectMapper` — дорогая операция (парсинг аннотаций, инициализация модулей)
- При каждом 401-ответе создаётся новый экземпляр, что неэффективно
- `ObjectMapper` не настроен на использование модулей Spring (Java 8 Date/Time, Kotlin и т.д.)

**Рекомендация:**
```java
@EnableWebSecurity
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {
    private final ObjectMapper objectMapper; // инжектируем из Spring контекста

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getWriter(), 
                        new ErrorResponse(ApiErrors.USER_NOT_AUTHENTICATED.getMessage()));
                })
            );
        return http.build();
    }
}
```

2. **Пустой класс `RedisSessionStorage`**

`RedisSessionStorage.java` содержит только аннотацию без какой-либо конфигурации.

**Проблема:** класс не добавляет ценности, вся конфигурация делается через аннотацию `@EnableRedisHttpSession`

**Рекомендация:** Можно перенести аннотацию в мейн класс:

```java
@SpringBootApplication
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600)
public class CloudStorageApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudStorageApplication.class, args);
    }
}
```

---

### пакет /controller

1. **Дублирование проверки аутентификации в `AuthController`**

В `AuthController.java` проверка на уже авторизованного пользователя дублируется в двух методах (строки 67-71 и 88-92):

```java
// В register()
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
    throw new AlreadyAuthenticatedException(ApiErrors.USER_ALREADY_AUTHENTICATED.getMessage());
}

// В login()
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
    throw new AlreadyAuthenticatedException(ApiErrors.USER_ALREADY_AUTHENTICATED.getMessage());
}
```

**Проблемы:**
- Нарушение DRY принципа — один и тот же код в двух местах
- При изменении логики проверки нужно обновлять оба места
- Логично вынести в отдельный метод

**Рекомендация:**
```java
@RestController
@RequiredArgsConstructor
public class AuthController {
    // ... поля

    private void checkNotAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            throw new AlreadyAuthenticatedException(ApiErrors.USER_ALREADY_AUTHENTICATED.getMessage());
        }
    }

    @PostMapping("${auth.register}")
    public ResponseEntity<UserDTO> register(...) {
        checkNotAuthenticated();
        
        log.info("Registering request for username: {}", userRegisterRequest.getUsername());
        UserDTO userDTO = userService.register(userRegisterRequest);
        authenticateUser(userRegisterRequest, request, response);
        
        return new ResponseEntity<>(userDTO, HttpStatus.CREATED);
    }

    //так же в логине
}
```

2. **Избыточный код в методе `login()`**

В `AuthController.login()` (строка 98) используется `ResponseEntity.status(HttpStatus.OK).body(userDTO)` вместо `ResponseEntity.ok(userDTO)`:

---

---

### пакет /initializer

1. **Отсутствие обработки ошибок при старте приложения**

В `MinioInitializer.init()` (строки 24-42) если MinIO недоступен, приложение упадёт при старте:

```java
@PostConstruct
public void init() {
    createBucketIfNotExists(); // если MinIO недоступен — app не запустится
}
```

**Проблема:** приложение не может запуститься без MinIO, даже если нужно просто посмотреть сваггер или подергать ручки не требующие минио. В проде это еще черевато тем, что ты зависишь от минио

**Рекомендация:** добавить graceful degradation

---

### пакет /model

1. **Некорректная структура пакета `model` — смешение разных типов объектов**

Текущая структура пакета `model`:

```
model/
├── entity/          # JPA сущности для БД (User)
├── dto/             # DTO для передачи данных (ResourceDTO, UserDTO)
├── request/         # Request DTO (AuthUserRequest)
├── response/        # Response DTO (ErrorResponse)
└── exception/       # Доменные исключения
```

**Проблемы:**

- Название `model` слишком общее и не отражает содержимое — в нём смешаны разные слои приложения
- JPA-сущности, DTO, request/response объекты и исключения находятся в одном корневом пакете
- Нарушается принцип разделения ответственности по слоям (Persistence vs Transport vs Domain)
- Непонятно где искать объекты для конкретных целей (что для БД, что для API)
- `dto`, `request`, `response` — это всё DTO, но разнесены по разным пакетам без явной логики

**В чём отличие request/response/dto:**

- **Request DTO** — объекты для входящих данных от клиента (`AuthUserRequest` с валидацией)
- **Response DTO** — объекты для исходящих данных клиенту (`UserDTO`, `ErrorResponse`)
- **DTO** — общий термин для Data Transfer Object (объекты передачи данных между слоями)

В текущей структуре `dto/`, `request/`, `response/` — это всё DTO, но они разнесены на один уровень с `entity` и `exception`, что создаёт путаницу.

**Рекомендация:** реорганизовать пакеты по назначению и слоям:

```
org.example.cloudstorage/
├── entity/              # JPA сущности для БД (вместо model/entity)
├── dto/                 # Все DTO в одном месте
│   ├── request/         # AuthUserRequest
│   └── response/        # UserDTO, ErrorResponse, ResourceDTO
├── exception/           # исключения (если нужно, разделить на домейн и бизес исключения)
├── controller/          # REST API
├── service/             # Бизнес-логика
└── repository/          # Репозитории (JPA + MinIO)
```

**Почему это важно:**

- **Явное разделение слоёв** — сразу понятно что относится к БД (entity), что к API (dto), что к бизнес-логике (service)
- **Изоляция зависимостей** — изменения в API DTO не влияют на JPA сущности
- **Масштабируемость** — проще добавлять новые слои (например, события, команды, проекции для CQRS)
- **Соответствие стандартам Spring** — типичная структура Spring Boot приложений

---

### пакет /model/dto

1. **Избыточные Lombok-аннотации и отсутствие mapper-классов**

В классах DTO используются все способы создания объектов одновременно:

`UserDTO.java` (строки 7-11):
```java
@Data                    // генерирует getters + setters + equals + hashCode + toString
@AllArgsConstructor      // генерирует конструктор
public class UserDTO {
    private String username;
}
```

`ErrorResponse.java` (строки 8-13):
```java
@Data                    // генерирует getters + setters + equals + hashCode + toString
@AllArgsConstructor      // генерирует конструктор со всеми параметрами
@NoArgsConstructor       // генерирует пустой конструктор
public class ErrorResponse {
    private String message;
}
```

`ResourceDTO.java` (строки 7-18):
```java
@NoArgsConstructor       // пустой конструктор
@AllArgsConstructor      // конструктор со всеми параметрами
@Getter                  // геттеры
@Setter                  // сеттеры
@Builder                 // билдер-паттерн
public class ResourceDTO {
    // ...
}
```

**Проблемы:**
- Используются множественные способы создания объектов: конструкторы + сеттеры + билдеры
- DTO создаются напрямую в коде (`new UserDTO(username)`, `new ErrorResponse(message)`) в контроллерах и сервисах
- Нарушается инкапсуляция логики маппинга — дублирование кода создания DTO в 10+ местах
- Сложно централизованно изменить логику маппинга (например, добавить поле)

**Рекомендация:** использовать один способ создания + mapper класс:

```java
// DTO - иммутабельные records (Java 17+)
public record UserDTO(String username) {}

public record ErrorResponse(String message) {}

public record ResourceDTO(
    String path,
    String name,
    Long size,
    TypeResource type
) {}

// Лучше использовать MapStruct, это просто пример который не будет использовать в продакшн коде
@Component
public class UserMapper {
    
    public UserDTO toDTO(User user) {
        return new UserDTO(user.getUsername());
    }
    
    public UserDTO toDTO(String username) {
        return new UserDTO(username);
    }
}

@Component
public class ResourceMapper {
    
    public ResourceDTO toDTO(String path, String name, Long size, TypeResource type) {
        return new ResourceDTO(path, name, size, type);
    }
    
    public List<ResourceDTO> toDTOList(List<Result<Item>> minioItems) {
        return minioItems.stream()
            .map(result -> toDTO(
                result.get().objectName(),
                PathUtil.getFileName(result.get().objectName()),
                result.get().size(),
                result.get().isDir() ? TypeResource.DIRECTORY : TypeResource.FILE
            ))
            .toList();
    }
}

// Использование в контроллере
@RestController
@RequiredArgsConstructor
public class UserController {
    
    private final UserMapper userMapper;
    
    @GetMapping("/api/user")
    public ResponseEntity<UserDTO> getCurrentUser(Authentication authentication) {
        return ResponseEntity.ok(userMapper.toDTO(authentication.getName()));
    }
}
```

2. **Отсутствие Jakarta Bean Validation в DTO**

`ResourceDTO.java` (строки 7-18) не содержит аннотаций валидации:

```java
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResourceDTO {
    private String path;
    private String name;
    private Long size;
    private TypeResource type;
}
```

**Проблемы:**
- Нет гарантии что поля не null
- Нет ограничений на длину строк
- OpenAPI схема не содержит информацию о required полях

**Рекомендация:**
```java
public record ResourceDTO(
    @NotBlank @Size(max = 500)
    String path,
    
    @NotBlank @Size(max = 255)
    String name,
    
    @Min(0)
    Long size, // может быть null для директорий
    
    @NotNull
    TypeResource type
) {}
```

---

### пакет /model/exception

1. **Дублирование кода в кастомных исключениях**

Все 8 классов исключений имеют идентичную структуру (например, `ResourceNotFoundException.java`, строки 3-7):

```java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

**Проблемы:**
- Дублирование кода — каждый класс имеет один и тот же конструктор
- Можно было создать базовый класс исключения
- Некоторые исключения имеют дополнительный конструктор без параметров (например `UnauthorizedException`)

**Рекомендация:** создать базовое исключение и наследоваться от него:

```java
// BaseCloudStorageException.java
public abstract class BaseCloudStorageException extends RuntimeException {
    public BaseCloudStorageException(String message) {
        super(message);
    }

    public BaseCloudStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

// ResourceNotFoundException.java
public class ResourceNotFoundException extends BaseCloudStorageException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

// FileStorageException.java
public class FileStorageException extends BaseCloudStorageException {
    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

### пакет /repository

1. **Неправильная структура пакетов**

Репозиторий `UserRepository` находится в пакете `repository.Impl`:

```
repository/
├── ResourceRepository.java (интерфейс s3)
└── Impl/
    ├── UserRepository.java (интерфейс JPA)
    └── ResourceRepositoryImpl.java (реализация s3)
```

**Проблемы:**
- `UserRepository` — это интерфейс Spring Data JPA, а не имплементация
- Смешение JPA репозиториев и кастомных реализаций в одной директории
- Нарушение naming conventions — `Impl` обычно содержит реализации интерфейсов

**Рекомендация:** лучше в repository оставить только jpa, сделать пакет infrastucture (или что-то типо) куда вынести s3

2. **Методы `assertExists` и `assertNotExists` нарушают SRP в ResourceRepositoryImpl**

В `ResourceRepository` (строки 13-14) методы `assert*` проверяют существование И выбрасывают исключения:

```java
void assertExists(String path) throws ResourceNotFoundException;
void assertNotExists(String path) throws ResourceAlreadyExistsException;
```

**Проблемы:**
- Метод делает две вещи: проверку и выброс исключения
- Не переиспользуется метод `isFilePathExists()`
- Дублирование вызова `isFilePathExists()` в двух методах

**Рекомендация:** оставить только `isFilePathExists()` и делать assert в сервисном слое:

```java
// ResourceRepository.java
public interface ResourceRepository {
    boolean isFilePathExists(String path);
    // убрать assertExists и assertNotExists
}

// ResourceService.java
public ResourceDTO getInfoResource(Long userId, String path) {
    String fullUserPath = PathUtil.buildUserFullPath(userId, path);
    
    if (!resourceRepository.isFilePathExists(fullUserPath)) {
        String fileName = PathUtil.getFileName(fullUserPath);
        throw new ResourceNotFoundException(
            ApiErrors.RESOURCE_NOT_FOUND.getMessage().formatted(fileName)
        );
    }
    
    return toResourceDTO(fullUserPath, userId);
}
```

Или оставить assert методы, но сделать их default в интерфейсе:

```java
public interface ResourceRepository {
    boolean isFilePathExists(String path);
    
    default void assertExists(String path) {
        if (!isFilePathExists(path)) {
            String fileName = PathUtil.getFileName(path);
            throw new ResourceNotFoundException(
                ApiErrors.RESOURCE_NOT_FOUND.getMessage().formatted(fileName)
            );
        }
    }
    
    default void assertNotExists(String path) {
        if (isFilePathExists(path)) {
            String fileName = PathUtil.getFileName(path);
            throw new ResourceAlreadyExistsException(
                ApiErrors.RESOURCE_ALREADY_EXISTS.getMessage().formatted(fileName)
            );
        }
    }
}
```

3. **Неэффективная проверка существования в MinIO**

В `ResourceRepositoryImpl.isFilePathExists()` (строки 35-45) используется `listObjects()` для проверки существования:

```java
@Override
public boolean isFilePathExists(String path) throws FileStorageException {
    try {
        Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(path)
                .build());
        return results.iterator().hasNext();
    } catch (Exception ex) {
        throw new FileStorageException(ApiErrors.UNEXPECTED_EXCEPTION.getMessage(), ex);
    }
}
```

**Проблемы:**
- `listObjects()` может вернуть много результатов, хотя нам нужен только факт существования
- Для файлов эффективнее использовать `statObject()`
- Нет различия между файлом и директорией

**Рекомендация:**
```java
@Override
public boolean isFilePathExists(String path) {
    try {
        // Для файлов используем statObject (быстрее)
        if (!PathUtil.isDirectory(path)) {
            try {
                minioClient.statObject(StatObjectArgs.builder()
                        .bucket(bucketName)
                        .object(path)
                        .build());
                return true;
            } catch (ErrorResponseException e) {
                if ("NoSuchKey".equals(e.errorResponse().code())) {
                    return false;
                }
                throw e;
            }
        }
        
        // Для директорий используем listObjects с maxKeys=1
        Iterable<Result<Item>> results = minioClient.listObjects(
            ListObjectsArgs.builder()
                .bucket(bucketName)
                .prefix(path)
                .maxKeys(1) // оптимизация
                .build()
        );
        return results.iterator().hasNext();
        
    } catch (Exception ex) {
        throw new FileStorageException(ApiErrors.UNEXPECTED_EXCEPTION.getMessage(), ex);
    }
}
```

4. **Неоптимальная обработка ошибок удаления директории**

В `ResourceRepositoryImpl.deleteDirectory()` (строки 102-105) ошибки удаления только логируются, но не выбрасываются:

```java
for (Result<DeleteError> error : errors) {
    DeleteError e = error.get();
    log.error(e.objectName(), e.message()); // только лог!
}
```

**Проблема:** метод вернёт успех, даже если часть файлов не удалилась. Пользователь получит 204 No Content, но файлы останутся в MinIO.

---

### пакет /security

1. **Утечка сущности JPA в контроллеры**

`UserDetails` предоставляет метод `getUser()`, который возвращает JPA-сущность `User`:

```java
public User getUser() {
    return user;
}
```

**Проблемы:**
- Контроллеры получают прямой доступ к JPA-сущности
- Нарушение архитектурной изоляции слоёв
- Риск утечки Hibernate lazy-loading в JSON-сериализацию
- При изменении entity придётся проверять все места использования в контроллерах

**Рекомендация:**
```java
public class UserDetails implements org.springframework.security.core.userdetails.UserDetails {
    private final User user;

    // Вместо getUser() предоставить только необходимые данные
    public Long getUserId() {
        return user.getId();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }
    
    // Не предоставлять getUser()
}

// В контроллерах
@GetMapping("${resource}")
public ResponseEntity<ResourceDTO> getResource(
        @AuthenticationPrincipal UserDetails userDetails,
        @RequestParam(name = "path") String path
) {
    ResourceDTO resourceDTO = resourceService.getInfoResource(
        userDetails.getUserId(), // вместо userDetails.getUser().getId()
        path
    );
    return ResponseEntity.ok(resourceDTO);
}
```

---

### пакет /service

1. **Неэффективный поиск файлов в методе `searchResources()` в ResourceService**

В `ResourceService.searchResources()` загружаются ВСЕ файлы пользователя для поиска:

```java
public List<ResourceDTO> searchResources(Long userId, String query) {
    if (query.isBlank()) {
        throw new IllegalArgumentException(ApiErrors.QUERY_IS_BLANK.getMessage());
    }
    String rootPath = PathUtil.buildRootPath(userId);
    List<String> files = resourceRepository.getFilesFromDirectory(rootPath, true); // загружаем ВСЕ файлы!

    return files.stream()
            .filter(str -> PathUtil.getFileName(str).toLowerCase().contains(query.toLowerCase()))
            .map(str -> toResourceDTO(str, userId))
            .toList();
}
```

**Проблемы:**
- **O(N) сложность** — чем больше файлов у пользователя, тем медленнее поиск
- Загружаются метаданные всех объектов из MinIO, даже если нужен только один файл
- Не масштабируется — при 10,000+ файлов запрос будет очень медленным
- Нет пагинации — возвращаются все результаты сразу

**Рекомендация:** создать таблицу метаданных файлов в PostgreSQL
**Примечание:** требуется синхронизация между PostgreSQL и MinIO при upload/delete/move.

2. **Использование `InvalidParameterException` вместо доменного исключения**

В `ResourceService.listDirectories()` (строка 102):

```java
if (!isDirectory) {
    throw new InvalidParameterException("Not valid directory: %s".formatted(path));
}
```

**Проблема:** `InvalidParameterException` из `java.security` не является доменным исключением и не обрабатывается в `GlobalExceptionHandler`.

**Рекомендация:**
```java
if (!isDirectory) {
    throw new InvalidPathResourceException(
        ApiErrors.INVALID_PATH.getMessage().formatted(path)
    );
}
```

3. **Отсутствие проверки на null при работе с MultipartFile**

В `ResourceService.uploadResources()` (строки 218-236) нет проверки что `file.getOriginalFilename()` может вернуть `null`:

```java
for (MultipartFile file : multipartFiles) {
    String fileNameOriginal = file.getOriginalFilename(); // может быть null!
    resourceRepository.assertNotExists(fullUserPath + fileNameOriginal); // NPE!
    
    if (fileNameOriginal.contains("/")) { // NPE!
        // ...
    }
}
```

**Рекомендация:** Добавить проверка на null

---

### пакет /util

1. **`PathUtil` имеет non-static поле в утилитном классе**

`PathUtil.java` (строка 16) объявлен как `@UtilityClass` (Lombok), но содержит non-static поле:

```java
@UtilityClass
public class PathUtil {
    private final String ROOT_PATH = "user-%d-files/"; // не static, но @UtilityClass делает класс final с private конструктором
```

**Рекомендация:** Лучше явно объявлять константы как `static final` для читаемости.

2. **Метод `directorySeparator()` объявлен как non-static**

В `PathUtil.java` (строки 78-80) метод `directorySeparator()` не объявлен как `static`, хотя класс помечен как `@UtilityClass`:

```java
public List<String> directorySeparator(String path) {
    return Arrays.asList(path.split("/"));
}
```

**Рекомендация:** Lombok `@UtilityClass` сделает метод static автоматически, но для явности лучше объявлять `static` вручную.

---

### пакет /test

1. **Отсутствие тестов для `ResourceService`**

В проекте есть только тесты для `UserService` (unit и integration), но нет тестов для `ResourceService`, который содержит основную бизнес-логику приложения.

**Проблемы:**
- Не покрыты критичные сценарии: upload, download, move, delete, search
- Нет проверки граничных случаев (пустые файлы, большие файлы, спецсимволы в именах)
- Нет тестов на безопасность (доступ к чужим файлам)

**Рекомендация:** добавить интеграционные тесты с Testcontainers для MinIO:

2. **Отсутствие тестов для `PathUtil`**

`PathUtil` содержит критичную логику валидации и нормализации путей, но не покрыт тестами.

**Рекомендация:** добавить unit-тесты

---

### УЯЗВИМОСТИ БЕЗОПАСНОСТИ

1. **CRITICAL: Возможность доступа к файлам других пользователей через Path Traversal**

В `PathUtil.buildUserFullPath()` (строки 30-34) проверка владельца через `contains()` обходится:

```java
public static String buildUserFullPath(Long userId, String path) {
    String normalized = validateAndNormalizePath(path);
    return isContainsRootPath(normalized, userId)  // УЯЗВИМОСТЬ!
            ? normalized.replace("//", "/")
            : (buildRootPath(userId) + normalized).replace("//", "/");
}

public static boolean isContainsRootPath(String path, Long userId) {
    return path.contains(ROOT_PATH.formatted(userId));  // слабая проверка!
}
```

**Exploit сценарий:**
```bash
# Пользователь с userId=123 может получить доступ к файлам пользователя userId=456:
GET /api/resource?path=../user-456-files/secret.txt
# После нормализации: user-123-files/../user-456-files/secret.txt
# Path.normalize() превратит в: user-456-files/secret.txt
# contains("user-123-files") = false, поэтому добавится префикс
# Но атакующий может использовать: user-123-files/../../user-456-files/secret.txt
```

**Проблемы:**
- Метод `contains()` не проверяет что путь **начинается** с корневой директории пользователя
- `Path.normalize()` вызывается **после** проверки, что позволяет обойти валидацию
- Нет проверки что финальный путь находится внутри директории пользователя

**Рекомендация:** проверять что итоговый путь **начинается** с корневой директории пользователя:

```java
public static String buildUserFullPath(Long userId, String path) {
    if (userId == null || userId < 1) {
        throw new IllegalArgumentException("Invalid userId: " + userId);
    }
    
    String rootPath = buildRootPath(userId);
    String normalized = validateAndNormalizePath(path);
    
    // Если уже содержит rootPath, используем как есть
    String fullPath = normalized.startsWith(rootPath) 
        ? normalized 
        : rootPath + normalized;
    
    // Нормализуем полный путь
    String normalizedFullPath = normalizePath(fullPath);
    
    // КРИТИЧНО: проверяем что финальный путь находится в директории пользователя
    if (!normalizedFullPath.startsWith(rootPath)) {
        throw new InvalidPathResourceException(
            "Path traversal detected: attempting to access files outside user directory"
        );
    }
    
    return normalizedFullPath;
}

private static String normalizePath(String path) {
    try {
        return Path.of(path).normalize().toString().replace("\\", "/");
    } catch (InvalidPathException e) {
        throw new InvalidPathResourceException("Invalid path: " + path);
    }
}
```

2. **CRITICAL: Отсутствие проверки владельца файла перед операциями**

Я в этом не до конца уверен, но думаю что вполне правда

Во всех методах `ResourceService` нет проверки что файл принадлежит текущему пользователю:

```java
// ResourceService.java
public ResourceDTO getInfoResource(Long userId, String path) {
    String fullUserPath = PathUtil.buildUserFullPath(userId, path);
    resourceRepository.assertExists(fullUserPath);  // проверяет только существование!
    return toResourceDTO(fullUserPath, userId);
}
```

**Проблема:** если атакующий узнает полный путь к файлу другого пользователя (например, `user-456-files/secret.txt`), он может:
- Скачать файл через `/api/resource/download?path=../../user-456-files/secret.txt`
- Удалить файл через `DELETE /api/resource?path=../../user-456-files/secret.txt`
- Переместить файл другого пользователя

**Рекомендация:** добавить явную проверку владельца на уровне сервиса

3. **CRITICAL: Race condition в операции перемещения файлов**

Эту вещь мне подсказал 1 из людей которые был на ревью, тебе на подумать

В `ResourceService.moveOrRenameFile()` (строки 163-166) copy-then-delete не является атомарной:

```java
private ResourceDTO moveOrRenameFile(String from, String to, Long userId) {
    resourceRepository.copyObject(from, to);      // Шаг 1
    resourceRepository.deleteFile(from);          // Шаг 2 - может упасть!
    return toResourceDTO(to, userId);
}
```

**Проблемы:**
- Если между `copyObject` и `deleteFile` произойдет сбой — файл будет дублирован
- Два параллельных запроса на перемещение одного файла приведут к race condition
- Нет идемпотентности операции

**Рекомендация:** использовать distributed lock или conditional operations

---

### Общие замечания по качеству кода

1. **Отсутствие маппер-классов для преобразования между слоями**

Во всём проекте DTO создаются напрямую в контроллерах и сервисах без использования отдельных маппер-классов:

**В контроллерах:**
- `UserController.getUser()` (строка 26): `new UserDTO(authentication.getName())`
- `AuthController.register()` и `login()`: прямое создание `UserDTO`

**В сервисах:**
- `UserService.register()` и `login()`: `new UserDTO(user.getUsername())`
- `ResourceService.toResourceDTO()` (строки 39-50): приватный метод маппинга внутри сервиса

**Проблемы:**
- **Нарушение инкапсуляции** — контроллеры и сервисы знают о внутренней структуре DTO
- **Смешение ответственности** — логика маппинга размазана по контроллерам и сервисам
- **Дублирование кода** — логика создания `UserDTO` повторяется в нескольких местах
- **Сложность поддержки** — при добавлении новых полей в DTO придётся править множество мест
- **Нет переиспользования** — приватный метод `toResourceDTO()` в сервисе нельзя использовать в других местах

**Рекомендация:** создать отдельные маппер-классы (можно использовать MapStruct (предпочтительнее) или простые Spring компоненты):

```java
// UserMapper.java
@Component
public class UserMapper {
    public UserDTO toDto(String username) {
        return new UserDTO(username);
    }
    
    public UserDTO toDto(User user) {
        return new UserDTO(user.getUsername());
    }
}

// ResourceMapper.java
@Component
@RequiredArgsConstructor
public class ResourceMapper {
    private final ResourceRepository resourceRepository;

    public ResourceDTO toDto(String fullUserPath, Long userId) {
        boolean isDirectory = PathUtil.isDirectory(fullUserPath);
        String clearUserPath = PathUtil.removeRootPath(fullUserPath, userId);

        return ResourceDTO.builder()
                .path(PathUtil.getParentPath(clearUserPath))
                .name(isDirectory ? PathUtil.getFileName(clearUserPath) + "/" : PathUtil.getFileName(clearUserPath))
                .size(isDirectory ? null : resourceRepository.checkObjectSize(fullUserPath))
                .type(isDirectory ? TypeResource.DIRECTORY : TypeResource.FILE)
                .build();
    }
}

// Использование в контроллерах
@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserMapper userMapper;

    @GetMapping("${user.me}")
    public ResponseEntity<UserDTO> getUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(userMapper.toDto(authentication.getName()));
    }
}

// Использование в сервисах
@Service
@RequiredArgsConstructor
public class ResourceService {
    private final ResourceRepository resourceRepository;
    private final ResourceMapper resourceMapper;

    public ResourceDTO getInfoResource(Long userId, String path) {
        String fullUserPath = PathUtil.buildUserFullPath(userId, path);
        resourceRepository.assertExists(fullUserPath);
        return resourceMapper.toDto(fullUserPath, userId);
    }
}
```

**Альтернатива:** использовать MapStruct для автоматической генерации маппер-классов:

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "username", source = "username")
    UserDTO toDto(User user);
    
    default UserDTO toDto(String username) {
        return new UserDTO(username);
    }
}
```

2. **Неиспользуемые импорты в нескольких файлах**

Во многих классах проекта присутствуют неиспользуемые импорты

**Проблемы:**
- Загромождение кода неиспользуемыми зависимостями
- Увеличение времени компиляции
- Снижение читаемости кода
- Затруднение поддержки и рефакторинга

**Рекомендация:** выполнить `Optimize Imports` (Ctrl+Alt+O / Cmd+Option+O в IntelliJ IDEA) во всех классах проекта для автоматического удаления неиспользуемых импортов

---

## РЕКОМЕНДАЦИИ

1. **Реализовать полноценный маппинг между слоями**

Создать отдельные маппер-классы (можно использовать MapStruct) для конвертации между:
- `User` ↔ `UserDTO`
- MinIO объекты ↔ `ResourceDTO`

Это улучшит разделение слоёв и упростит поддержку.

2. **Улучшить обработку больших файлов**

Текущая реализация загружает весь файл в память при upload. Для файлов >100MB рекомендуется:
- Использовать multipart upload MinIO для файлов >5MB
- Добавить прогресс-бар для больших загрузок
- Реализовать resumable uploads

3. **Оптимизировать работу с директориями**

Использовать материализованные представления (materialized views) или отдельную таблицу для хранения дерева директорий, что ускорит:
- Получение списка файлов в директории
- Подсчёт размера директории
- Поиск файлов по пути

4. **Добавить полноценное логирование аудита**

Создать таблицу `audit_log` для логирования всех операций пользователей:
- Кто, когда, что сделал (upload, download, delete, move)
- IP-адрес, user agent
- Результат операции (success/failure)

5. **Улучшить тестовое покрытие**

- Добавить интеграционные тесты для `ResourceService` с Testcontainers MinIO
- Добавить unit-тесты для `PathUtil`, маппер-классов
- Добавить e2e тесты для критичных user flows (регистрация → upload → download)
- Добавить тесты на безопасность (попытка доступа к чужим файлам)

6. **Документировать архитектурные решения**

В целом мало javadoc и документированного кода, но это вкусовщина. На каких-то проектах требуют больше, а на каких-то вовсе запрещают

---

## ИТОГ

Реализованы все основные требования ТЗ: регистрация/авторизация через Spring Security, хранение сессий в Redis, работа с файлами через MinIO, интеграция фронтенда. Есть Docker Compose для локального запуска, миграции БД через Flyway, интеграционные тесты (МАЛО!!) с Testcontainers для PostgreSQL. 
**Ключевые плюсы:**
- Использование интерфейсов для абстракции слоя хранилища (`ResourceRepository`), упрощающее замену MinIO
- Централизованная валидация путей через `PathUtil`
- Использование `StreamingResponseBody` для экономии памяти при скачивании
- Корректное управление ресурсами через try-with-resources
- Наличие unit и интеграционных тестов с Testcontainers

**Архитектурные проблемы:**
- Смешение ответственности в `UserService` (аутентификация + регистрация + вызов ResourceService)
- Неправильная структура пакетов — `UserRepository` (JPA интерфейс) в `repository/Impl`
- Утечка JPA-сущностей в контроллеры через `UserDetails.getUser()`
- Отсутствие маппер-классов — DTO создаются напрямую в контроллерах

**Проблемы производительности:**
- **O(N) поиск файлов** — загружаются ВСЕ файлы пользователя для поиска по имени. Необходима таблица метаданных в PostgreSQL с индексами.
- Копирование файлов по одному при перемещении директории (N запросов к MinIO)
- Отсутствие кеширования метаданных файлов

**Для роста дальше:**
1. Освоить Clean Architecture и правильное разделение на слои
2. Расширить тестовое покрытие — security тесты, chaos engineering, penetration testing
3. Изучить паттерны distributed systems (Saga, Outbox, distributed locks)
4. Изучить паттерны работы с внешними API и обеспечение eventual consistency
5. Добавить мониторинг, метрики и алерты для production (Prometheus, Grafana)

**Оценка:** проект на уровне **начинающего разработчика**, демонстрирует базовое понимание Spring Boot. Функциональность реализована. Необходимо глубокое изучение security best practices перед работой над production проектами.
