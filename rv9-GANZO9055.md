[GANZO9055/cloud_storage](https://github.com/GANZO9055/cloud_storage)

## ХОРОШО

1. **Использование интерфейсов для сервисов** — `FileService`, `FolderService`, `StorageService` объявлены как интерфейсы, но такого не хватает контроллерам

2. **Использование MapStruct для маппинга** — `ResourceMapper` использует MapStruct для преобразования между внутренними объектами и DTO, что уменьшает boilerplate-код и снижает вероятность ошибок.

3. **Интеграционные тесты с Testcontainers** — `UserServiceImplTest` использует Testcontainers, но тестов **ОЧЕНЬ** мало

4. **Валидация входных данных** — использование Jakarta Bean Validation в DTO

---

## ЗАМЕЧАНИЯ

Чтож, давай начнем с структуры

### Структура пакетов

1. **Неправильная организация пакетов по доменам вместо слоёв** — проект разделён на два глобальных пакета `minio` и `user`, внутри каждого из которых дублируется структура подпакетов (`controller`, `service`, `dto`, `config` и т.д.). Это нарушает принципы правильной архитектуры и создаёт проблемы с масштабируемостью и поддержкой.

**Проблемы:**
- одинаковые подпакеты (`dto`, `config`, `service`, `controller`) повторяются в каждом доменном пакете, что усложняет навигацию и поддержку
- правильная архитектура должна быть организована по слоям (controller, service, repository, dto)
- при добавлении нового домена (например, `notification`) придётся снова дублировать всю структуру подпакетов
- общие компоненты (DTO для ошибок, конфигурация) не могут быть легко переиспользованы между доменами - Нарушение принципа DRY
- непонятно, где должны находиться общие компоненты

**Рекомендация:** реорганизовать структуру пакетов по слоям, а не по доменам:
```
com.example.cloud_storage/
├── controller/
│   ├── auth/
│   │   ├── AuthController.java
│   │   └── UserController.java
│   ├── file/
│   │   └── FileController.java
│   └── folder/
│       └── FolderController.java
├── service/
│   ├── user/
│   │   ├── UserService.java
│   │   ├── AuthService.java
│   │   └── impl/
│   ├── storage/
│   │   ├── StorageService.java
│   │   ├── FileStorageService.java
....
```

**Преимущества правильной структуры:**
- все контроллеры в одном месте, все сервисы в одном месте
- DTO, конфигурация, исключения находятся в общих пакетах - легче что-то найти
- добавление нового домена не требует дублирования структуры
- разделение по слоям соответствует принципам разделения ответственности
- разработчику проще найти нужный компонент

Теперь пройдемся по пакетам, сверху вниз

### пакет /exception

**GlobalExceptionHandler**
1. **Некорректная обработка MethodArgumentNotValidException** — в обработчике возвращается полное сообщение исключения, которое содержит техническую информацию и не подходит для клиента.

**Проблемы:**
- Клиент получает техническое сообщение вместо понятного
- Нарушение принципа "Don't Expose Implementation Details"
- Ухудшение UX

**Рекомендация:** форматировать сообщения валидации:
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<?> handleValidation(MethodArgumentNotValidException exception) {
    Map<String, String> errors = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .collect(Collectors.toMap(
                    FieldError::getField,
                    error -> error.getDefaultMessage() != null
                            ? error.getDefaultMessage()
                            : "Validation failed"
            ));

    return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", "Validation failed", "errors", errors));
}
```

2. **Отсутствие логирования ошибок** — в большинстве обработчиков исключений нет логирования, что усложняет диагностику проблем в production.

**Проблемы:**
- Невозможность отследить ошибки в production
- Сложность диагностики проблем
- Отсутствие мониторинга ошибок

**Рекомендация:** добавить логирование

3. **Дублирование кода в исключениях** — все исключения имеют одинаковую структуру (только конструктор с сообщением), что создаёт boilerplate код.

**Проблемы:**
- Нарушение принципа DRY
- Усложнение поддержки — нужно поддерживать множество похожих классов
- Риск ошибок при изменении структуры исключений

**Рекомендация:** использовать базовый класс или records (Java 14+):
```java
// Базовый класс
public abstract class CloudStorageException extends RuntimeException {
    public CloudStorageException(String message) {
        super(message);
    }

    public CloudStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

// Исключения наследуются от базового класса
public class UserAlreadyExistsException extends CloudStorageException {
    public UserAlreadyExistsException(String message) {
        super(message);
    }
}

// Или использовать records (Java 14+)
public record UserAlreadyExistsException(String message) extends RuntimeException {
    public UserAlreadyExistsException {
        super(message);
    }
}
```

### пакет /minio/config

1. **Использование @Value вместо @ConfigurationProperties** — конфигурация MinIO использует множественные `@Value` аннотации вместо типизированного класса конфигурации.

**Проблемы:**
- Отсутствие типобезопасности — значения читаются как строки без валидации
- Сложность централизации конфигурации — все свойства разбросаны по классу
- Нет автодополнения в IDE для свойств конфигурации
- Нарушение принципа инкапсуляции — конфигурация не сгруппирована в одном месте

**Рекомендация:** использовать `@ConfigurationProperties` для типобезопасной конфигурации:
```java
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String url,
        @NotBlank String accessKey,
        @NotBlank String secretKey
) {}

@Configuration
@RequiredArgsConstructor
public class MinioConfig {
    private final MinioProperties minioProperties;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioProperties.url())
                .credentials(minioProperties.accessKey(), minioProperties.secretKey())
                .build();
    }
}
```

**Это не столько минус, сколько просто рекомендация на будущее**
2. **Отсутствие graceful degradation при недоступности MinIO** — если MinIO недоступен при старте приложения, приложение полностью падает. Будет лучше, если приложение сможет запуститься с graceful degradation

**Проблемы:**
- Приложение не может запуститься, даже если MinIO временно недоступен
- Нет возможности использовать приложение в режиме "только чтение" при проблемах с хранилищем
- Сложность диагностики — приложение падает без возможности проверить другие компоненты

**Рекомендация:** добавить graceful degradation (приложение стартует, но действия с минио ничего не делают, пока не поймаем конект с минио)

### пакет /minio/controller

1. **Отсутствие валидации параметров запроса** — в `FileController` параметры запроса не валидируются через джакарту валидейшн (@NonNull/@Nullable).

**Проблемы:**
- Некорректные данные могут попасть в бизнес-логику
- Отсутствие явного контракта API — непонятно, какие ограничения на параметры
- Некорректная генерация OpenAPI схемы

**Рекомендация:** добавить валидацию параметров:
```java
...
public ResponseEntity<Resource> getResource(
        @RequestParam
        @NotBlank
        @Size(max = 500)
        String path
) ....
```

Хоть метод и написано как GET в ТЗ, но все же либо надо было задать в чат вопрос (если не нашел, сорри). Либо сделать по своему)
2. **Некорректный HTTP метод для перемещения** — использование `GET` для операции перемещения нарушает REST конвенции, так как GET не должен изменять состояние ресурса.

**Проблемы:**
- Нарушение REST конвенций — GET должен быть идемпотентным и безопасным
- Проблемы с кэшированием — прокси и браузеры могут кэшировать GET запросы

**Рекомендация:** использовать `PUT` или `PATCH`

### пакет /minio/dto

1. **Отсутствие валидации в response DTO** — `FileResponseDto` и `FolderResponseDto` не содержат аннотаций валидации, что не позволяет документировать контракт API.

**Проблемы:**
- Нет явного контракта API — непонятно, могут ли поля быть `null`
- Некорректная генерация OpenAPI схемы
- Невозможность проверить корректность response в тестах

**Рекомендация:** добавить аннотации `@NotBlunk`, `@Nullable` и тд

2. **Пустой базовый класс Resource** — класс `Resource` не содержит никакой логики и используется только как маркер для полиморфизма

**Проблемы:**
- Избыточная сложность — лишний класс без реальной пользы
- Нарушение принципа YAGNI

**Рекомендация:** использовать интерфейс или общий DTO:
```java
// Вариант 1: Интерфейс
public interface Resource {
    String getPath();
    String getName();
    Type getType();
}

// Вариант 2: Общий DTO
@Schema(description = "Ресурс")
public class ResourceResponseDto {
    @NotBlank
    @Size(max = 500)
    private String path;

    @NotBlank
    @Size(max = 200)
    private String name;

    @Min(0)
    private Long size;

    @NotNull
    private Type type;
}
```

3. **Разбиение пакета DTO по моделям (file/directory) вместо request/response** — в проекте DTO разложены по подпакетам `dto/file/` и `dto/directory/`, что усложняет навигацию и не соответствует распространённой практике.

**Проблемы:**
- При добавлении новых типов ресурсов растёт число подпакетов (file, directory, link, …), а не просто число классов в двух пакетах
- Сложнее искать все response- или все request-контракты API в одном месте

**Рекомендация:** делить DTO по роли в API — **request** и **response**, а не по моделям. Все response DTO в одном пакете, все request DTO в другом. Дополнительное деление внутри request/response (например, file/directory) имеет смысл только при большом количестве DTO и глубокой вложенности; в большинстве случаев достаточно плоской структуры.

Типы вроде `Type` (enum) можно оставить в общем пакете `dto` или рядом с response.

### пакет /minio/service

**ВКУСОВЩИНА**: в разных компаниях/командах принято по‑разному, у меня на работе без Impl например и мне так больше нравится, главное выбрать один стиль и применять его везде.

1. **Нейминг интерфейса и реализации: лучше без `Impl`, а контракт — с `Api`** — сейчас интерфейсы называются `FileService`/`FolderService`, а реализации — `FileServiceImpl`/`FolderServiceImpl`. Это не архитектурная ошибка, но такой нейминг часто ухудшает читаемость: суффикс `Impl` почти не несёт смысла, а по имени интерфейса не видно, что это именно контракт (API) слоя, а не “реальный сервис”.

**Почему это может быть плохо:**
- **Шум в именах** — `*Impl` увеличивает длину и визуальный шум, но редко помогает понять назначение класса
- **Неочевидность контракта** — по `FileService` непонятно, это “API сервиса” или просто один из сервисов, который можно внедрять напрямую
- **Непоследовательность при росте проекта** — когда появятся альтернативные реализации, нейминг с `Impl` начинает расползаться (`FileServiceImplV2`, `FileServiceMinioImpl` и т.п.)

2. **Дублирование валидации** — валидация пути выполняется в каждом методе сервиса, что дублирует код.

**Проблемы:**
- Нарушение принципа DRY
- Сложность поддержки — изменения в валидации нужно вносить в нескольких местах

**Рекомендация:** использовать AOP для валидации:
```java
// Маркерная аннотация
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidatePath {
}

@Aspect
@Component
@RequiredArgsConstructor
public class PathValidationAspect {
    private final ValidationResource validationResource;

    @Before("@annotation(validatePath)")
    public void validatePath(JoinPoint joinPoint, ValidatePath validatePath) {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof String path) {
                validationResource.checkingPath(path);
            }
        }
    }
}

// Сервис без дублирования
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    private final StorageService storageService;

    @Override
    @ValidatePath // валидация через аспект
    public Resource get(String path) {
        return storageService.get(path);
    }

    @Override
    @ValidatePath
    public void delete(String path) {
        storageService.delete(path);
    }
    ....
}
```

### пакет /minio/storage

1. **Большой класс MinioStorageService (400+ строк)** — класс содержит множество методов для работы с файлами, папками, поиском, загрузкой, скачиванием, созданием архива

**Проблемы:**
- Нарушение SRP - например метод search у тебя и ходит в минио и фильтрует и маппит, слишком много всего для Minio Service. MinioService должен **ТОЛЬКО** обращаться к MinIO
- Высокая сложность класса — сложно быстро найти нужный метод среди 400+ строк
- Сложность code review — много кода в одном файле затрудняет просмотр изменений
- Сложность тестирования — один большой тест-класс со множеством сценариев

**Рекомендация:** можно разбить на более мелкие классы по типам операций для удобства

2. **Неэффективный поиск ресурсов** — метод `search()` загружает все файлы пользователя в память и фильтрует их в Java, что неэффективно при большом количестве файлов.

**Проблемы:**
- O(n) сложность — чем больше файлов, тем медленнее поиск
- Загрузка всех метаданных в память — даже если нужен один файл
- Нагрузка на MinIO — приходится получать список всех объектов
- Не масштабируется — при 10,000+ файлов запрос будет очень медленным

**Рекомендация:** добавить таблицу метаданных файлов в PostgreSQL для эффективного поиска:
```sql
CREATE TABLE file_metadata (
                               id BIGSERIAL PRIMARY KEY,
                               user_id INTEGER NOT NULL REFERENCES users(id),
                               name VARCHAR(200) NOT NULL,
                               path VARCHAR(500) NOT NULL,
                               size BIGINT NOT NULL,
                               type VARCHAR(20) NOT NULL,
                               created_at TIMESTAMP NOT NULL DEFAULT NOW(),
                               CONSTRAINT unique_user_path UNIQUE (user_id, path)
);

CREATE INDEX idx_file_metadata_user_name ON file_metadata(user_id, name);
```

3. **Race condition при загрузке файлов** — в методе `upload()` проверка существования файла и его загрузка не атомарны. Два параллельных запроса могут загрузить файл с одинаковым именем.

**Проблемы:**
- Классическая проблема TOCTOU (Time-Of-Check-Time-Of-Use)
- Возможна потеря данных — второй файл перезапишет первый
- Нарушение целостности данных

**Рекомендация:** использовать атомарную загрузку через HTTP-заголовок `If-None-Match: *`:
```java
// If-None-Match для атомарной загрузки
public List<Resource> upload(String path, List<MultipartFile> files) {
    String newPath = getFullPath(path);
    List<Resource> uploadedResources = new ArrayList<>();

    for (MultipartFile file : files) {
        String objectName = newPath + file.getOriginalFilename();

        try {
            // Атомарная загрузка: If-None-Match: * означает "загрузить только если файл НЕ существует"
            // MinIO вернёт ошибку 412 Precondition Failed, если файл уже есть
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(BUCKET)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .headers(Map.of("If-None-Match", "*")) // атомарная проверка + загрузка
                            .build()
            );

            uploadedResources.add(resourceMapper.toResource(objectName));
        } catch (ErrorResponseException e) {
            if (e.errorResponse().code().equals("PreconditionFailed")) {
                throw new ResourceAlreadyExistsException("File already exists: " + file.getOriginalFilename());
            }
            throw new StorageException("Failed to upload file", e);
        } catch (IOException e) {
            throw new StorageException("Failed to read file", e);
        } catch (Exception e) {
            throw new StorageException("Unexpected error", e);
        }
    }

    return uploadedResources;
}
```

4. **Неэффективное удаление папок** — метод `delete()` загружает все объекты папки в память перед удалением, что неэффективно для больших папок.

**Проблемы:**
- Загрузка всех объектов в память перед удалением
- Медленная работа для папок с тысячами файлов
- Риск OutOfMemoryError при очень больших папках

**Рекомендация:** Использовать метаданные на основе постгри

### пакет /minio/validation

1. **Неочевидное название класса ValidationResource** — из названия класса неясно, что он делает запросы к внешней системе (MinIO). Метод `checkingExistenceResource()` выполняет реальный вызов `minioClient.listObjects()`, но название `ValidationResource` звучит как простой валидатор данных, а не клиент внешнего хранилища.

**Проблемы:**
- Нарушение принципа наименьшего удивления — из названия не очевидно, что класс делает сетевые запросы
- Сложность понимания кода — разработчик может не ожидать, что "валидация" вызывает внешний API
- Путаница с семантикой — валидация обычно подразумевает проверку данных без side-effects, а не запросы к внешним системам

**Рекомендация:** переименовать в класс, отражающий взаимодействие с MinIO типо `MinioResourceValidator`

2. **Использование @Component вместо @Service** — класс помечен аннотацией `@Component`, хотя по семантике это сервисный слой, который содержит бизнес-логику и взаимодействует с внешней системой (MinIO).

**Проблемы:**
- Нарушение семантики Spring — `@Service` предназначен для классов с бизнес-логикой
- Потеря явности архитектуры

**Рекомендация:** использовать `@Service` и constructor injection, вместо `@Autowired`

3. **Неэффективная проверка существования ресурса** — метод `checkingExistenceResource()` выполняет запрос к MinIO для каждого вызова, что создаёт лишнюю нагрузку.

**Проблемы:**
- Лишние запросы к MinIO
- Медленная работа при частых проверках
- Нагрузка на хранилище

**Рекомендация:** кэшировать результаты проверки (с осторожностью, чтобы не нарушить консистентность)

### пакет /user/config

1. **Некорректное сообщение об ошибке аутентификации** — сообщение `"User not unauthorized"` содержит грамматическую ошибку и не соответствует HTTP статусу 401 (должно быть "Unauthorized", а не "not unauthorized").

2. **Лишняя конфигурация** - `SessionConfig` можно удалить, а аннотацию редиса перенести просто в Application

### пакет /user/controller

1. **Утечка Entity в контроллер** — сервисный слой возвращает сущность `User`, а контроллер вручную создаёт DTO. Контроллер не должен работать с JPA-сущностями и знать об их структуре.

**Проблемы:**
- Нарушение инкапсуляции — контроллер знает о внутренней структуре `User`
- Дублирование логики маппинга — если маппинг нужен в другом месте, код придётся дублировать
- Нарушение SRP — контроллер занимается не только обработкой HTTP запросов, но и преобразованием данных

**Рекомендация:** вынести маппинг в сервисный слой, контроллер должен работать только с DTO

2. **Некорректная обработка logout** — метод `logout()` не выполняет реального выхода из системы, только возвращает 204. Spring Security должен обрабатывать logout через свой механизм.

**Проблемы:**
- Сессия не инвалидируется
- Пользователь остаётся авторизованным после "выхода"
- Нарушение безопасности — сессия может быть переиспользована

**Рекомендация:** использовать встроенный механизм Spring Security (уже настроен в `SecurityConfig`), метод контроллера не нужен

### пакет /user/dto

1. **Отсутствие валидации в UserResponseDto** — response DTO не содержит аннотаций валидации, что не позволяет документировать контракт API и проверять корректность данных в тестах.

**Проблемы:**
- Нет явного контракта API — непонятно, может ли `username` быть `null`
- Некорректная генерация OpenAPI схемы — Springdoc не знает, что поле обязательное
- Невозможность проверить корректность response в интеграционных тестах

**Рекомендация:**
```java
@AllArgsConstructor
@Getter
@Setter
@Schema(description = "Ответ пользователя")
public class UserResponseDto {
    @Schema(description = "Имя пользователя", example = "user_1")
    @NotBlank(message = "Username cannot be null")
    @Size(max = 50)
    private String username;
}
```

### пакет /user/model

1. **Валидация в JPA-сущности** — использование аннотаций Jakarta Bean Validation (`@NotBlank`, `@Size`) на полях JPA-сущности моветон

**Проблемы:**
- **Смешение ответственности** — JPA-сущность (domain model) не должна знать о правилах валидации бизнес-логики
- **Дублирование валидации** — валидация уже есть в `UserRequestDto`, в сущности она избыточна
- **Неправильная валидация пароля** — валидация `@Size(min=4, max=100)` на поле `password` не имеет смысла, т.к. пароль хранится в зашифрованном виде (BCrypt ~60 символов). Валидация должна быть на DTO перед шифрованием.
- **Разные правила для разных контекстов** — при создании пользователя могут быть одни правила валидации, при обновлении — другие. Сущность не должна об этом знать.

**Рекомендация:** убрать валидацию из сущности, оставить только в DTO. Если прям сильно хочется валидацию, делай constraints чтобы сама БД валидировала записи, а не спринг

### пакет /user/service

1. **Ручное создание сущности через сеттеры** — в методе `create()` сущность `User` создаётся через конструктор и сеттеры вместо использования маппера

**Проблемы:**
- Дублирование кода — если нужно создать `User` в другом месте, придётся повторять эту логику
- Нарушение инкапсуляции — сервис знает о внутренней структуре `User` и напрямую управляет его состоянием
- Сложность поддержки — при добавлении новых полей нужно не забыть обновить все места создания
- Отсутствие явного контракта маппинга — непонятно какие поля откуда берутся
- Смешение ответственности — сервис занимается и бизнес-логикой, и трансформацией данных

**Рекомендация:** использовать MapStruct маппер

2. **Слишком много ответственности в одном методе** — метод `create()` выполняет 4 разные задачи: валидацию, создание пользователя, аутентификацию и создание корневой папки. Это нарушает принцип единственной ответственности.

**Проблемы:**
- Сложность тестирования — нужно мокировать множество зависимостей
- Нарушение SRP — метод делает слишком много
- Сложность переиспользования — невозможно создать пользователя без аутентификации
- Транзакционные проблемы — если создание папки в MinIO упадёт, пользователь останется в БД

**Рекомендация:** разделить на отдельные методы:
```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final StorageService storageService;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public UserResponseDto register(UserRequestDto userRequestDto, HttpServletRequest request) {
        validateUserNotExists(userRequestDto.getUsername());

        User user = createUser(userRequestDto);
        authenticateUser(userRequestDto, request);
        initializeUserStorage(user.getId());

        return userMapper.toDto(user);
    }

    private void validateUserNotExists(String username) {
        if (userRepository.findByUsername(username).isPresent()) {
            log.warn("Registration failed: username={} already exists", username);
            throw new UserAlreadyExistsException("User already exists");
        }
    }

    private User createUser(UserRequestDto dto) {
        User user = userMapper.toEntity(dto);
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        return userRepository.save(user);
    }

    private void authenticateUser(UserRequestDto dto, HttpServletRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword())
        );
        SecurityContext securityContext = SecurityContextHolder.getContext();
        securityContext.setAuthentication(authentication);

        HttpSession session = request.getSession(true);
        session.setAttribute(SPRING_SECURITY_ATTRIBUTE, securityContext);
    }

    private void initializeUserStorage(Integer userId) {
        try {
            storageService.createRootFolder(userId);
        } catch (Exception e) {
            log.error("Failed to create root folder for user: userId={}", userId, e);
            // Решить что делать: откатить создание пользователя или продолжить
            throw new RuntimeException("Failed to initialize user storage", e);
        }
    }
}
```

### пакет /user/util

1. **Неочевидное название класса UserUtil** — из названия `UserUtil` абсолютно неясно, что класс выполняет запросы к БД через `UserRepository`. Утилитные классы обычно содержат stateless вспомогательные методы без side-effects, а не обращаются к внешним системам.

**Рекомендация:** Убрать из Util запрос к бд. Вообще util должен иметь static методы, на то он и утил

## РЕКОМЕНДАЦИИ

1. **Реструктурировать архитектуру пакетов** — отойти от разделения по доменам (`minio/`, `user/`) в сторону стандартной слоистой архитектуры (`controller/`, `service/`, `repository/`, `dto/`, `config/`, `exception/`)

2. **Использовать интерфейсы для контроллеров** — вынести определение REST endpoints в интерфейсы для явного разделения API контракта и его реализации

3. **Организация DTO по типу (request/response)** — разделить DTO на `request/` и `response/` папки на верхнем уровне, а не по моделям (`file/`, `directory/`)

4. **Вынести маппинг из контроллеров в сервисный слой** — контроллеры не должны работать с JPA-сущностями напрямую. Сервисы должны принимать и возвращать DTO, а маппинг происходит внутри сервисного слоя через MapStruct

5. **Централизовать валидацию через AOP** — вынести повторяющуюся валидацию путей в AOP аспект с аннотацией `@ValidatePath`. Это уберёт дублирование кода в каждом методе сервисов и улучшит читаемость.

6. **Использовать @ConfigurationProperties вместо @Value** — создать типизированные классы конфигурации (`MinioProperties`, `SecurityProperties`) для всех параметров конфигурации. Это обеспечивает type-safety, централизацию, валидацию и автокомплит в IDE

7. **Убрать валидацию из JPA-сущностей** — аннотации Jakarta Bean Validation (`@NotBlank`, `@Size`) должны быть только в DTO, а не в entity. Сущности отвечают за структуру данных БД, валидация — задача presentation layer

8. **Исправить race conditions** — использовать HTTP-заголовок `If-None-Match: *` при загрузке файлов в MinIO для атомарной проверки существования и создания. Это решает классическую проблему TOCTOU без необходимости в distributed locks

9. **Добавить таблицу метаданных файлов в PostgreSQL** — для эффективного поиска и масштабируемости создать таблицу `file_metadata` с индексами по `user_id` и `name`. Текущий подход (загрузка всех файлов из MinIO и фильтрация в Java) не масштабируется.

10. **Улучшить семантику классов** — переименовать классы, название которых не отражает их реальное поведение

11. **Реструктурировать сервисный слой** — разделить большие сервисы (`UserServiceImpl`, `MinioStorageService`) на более мелкие специализированные сервисы по принципу единственной ответственности. Это упростит тестирование и поддержку.

12. **Добавить graceful degradation для внешних систем** — приложение должно стартовать даже если MinIO временно недоступен, с понятным сообщением об ошибке и возможностью переподключения. Использовать `@ConditionalOnProperty` или lazy initialization.

---

## ИТОГ

Проект показывает **хорошее** владение базовым стеком Spring Boot: корректно используются Spring Security, JPA, Redis, PostgreSQL, MinIO, есть разделение на controller → service → repository, применяются DTO и MapStruct, настроена глобальная обработка ошибок, подключены Docker Compose и Swagger. Функционально приложение реализовано и демонстрирует понимание основных механизмов фреймворка.

**Главная проблема проекта — архитектура и чистота кода.** Структура пакетов организована нестандартно и будет плохо масштабироваться. Нарушена изоляция слоёв: контроллеры и сервисы работают с Entity, маппинг выполняется не на уровне сервисов. Сервисы перегружены ответственностями, встречаются крупные классы и неочевидная семантика компонентов. Конфигурация реализована без type-safety и с хардкодом значений. Есть проблемы с производительностью (поиск через загрузку всех данных в память) и потенциальные риски при конкурентной работе. Валидация частично смешана с моделью данных.

**Итог:** проект демонстрирует крепкую техническую базу, но нуждается в серьёзном архитектурном рефакторинге. Основная зона роста — не новые технологии, а принципы проектирования, изоляция слоёв, SOLID и масштабируемость решений. Уровень — Junior с хорошим потенциалом роста.
