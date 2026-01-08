[red-eyed-99](https://github.com/red-eyed-99/cloud-storage)

## ХОРОШО

1. **Использование интерфейсов для абстракции внешних зависимостей** — создан интерфейс `SimpleStorageService`, который абстрагирует работу с объектным хранилищем. Это позволяет легко заменить MinIO на другую реализацию (S3, Azure Blob Storage) без изменения бизнес-логики.

2. **Использование @ConfigurationProperties для конфигурации** — настройки вынесены в типизированные классы (`SecurityConfigProperties`, `MinioConfigProperties`) вместо множественных `@Value`. Это обеспечивает type-safety и удобство работы с конфигурацией.

3. **Асинхронная загрузка файлов в MinIO** — в `MinioStorageService.uploadFiles()` используется `ExecutorService` для параллельной загрузки нескольких файлов, что ускоряет операцию при множественной загрузке.

4. **Хорошее покрытие интеграционными тестами** — в проекте присутствуют интеграционные тесты с использованием Testcontainers для PostgreSQL, Redis и MinIO, что обеспечивает проверку работы всех компонентов в связке.

## ЗАМЕЧАНИЯ

### Общая структура проекта

1. **Неправильная организация пакетов — смешение слоёв архитектуры**

В проекте используется организация кода по feature/domain, где в одном пакете смешаны классы из разных архитектурных слоёв.

```
user/
├── dto/
│   ├── UserResponseDto.java
│   └── CreateUserDto.java
├── User.java                      // entity (persistence layer)
├── UserAlreadyExistsException.java  // exception
├── UserApi.java                   // API contract (presentation layer)
├── UserController.java            // controller (presentation layer)
├── UserMapper.java                // mapper
├── UserRepository.java            // repository (data access layer)
├── UserService.java               // service (business logic layer)
└── validation/                    // validation logic

auth/
├── dto/
│   ├── AuthResponseDto.java
│   ├── SignInRequestDto.java
│   └── SignUpRequestDto.java
├── AuthApi.java                   // API contract
├── AuthController.java            // controller
├── AuthService.java               // service
├── AuthUserMapper.java            // mapper
└── UserDetailsImpl.java           // Spring Security implementation

resource/
├── dto/
├── exception/
├── validation/
├── ResourceApi.java               // API contract
├── ResourceController.java        // controller
├── ResourceService.java           // service
├── ResourceMapper.java            // mapper
├── ResourcePathUtil.java          // utility
└── ResourceType.java              // enum
```

**Почему это плохо:**
- **Нарушение принципа слоистой архитектуры** — в одном пакете находятся классы из разных слоёв (presentation, business logic, data access, persistence), что размывает границы между слоями
- **Нарушение Dependency Rule (Clean Architecture)** — сложно контролировать направление зависимостей между слоями, когда все классы в одном пакете
- **Сложность понимания структуры** — непонятно, к какому слою относится класс без открытия файла
- **Проблемы с модульностью** — невозможно выделить слой в отдельный модуль (например, вынести domain в core module, а infrastructure в отдельный модуль)
- **Циклические зависимости** — классы из разных слоёв могут легко создавать циклические зависимости
- **Сложность тестирования** — нельзя протестировать бизнес-логику независимо от инфраструктуры

**Рекомендация:**

Организовать код по архитектурным слоям (layered architecture):

```
ru.redeyed.cloudstorage/
├── domain/                         // Domain layer (core business logic)
│   ├── model/
│   │   ├── User.java
│   │   └── Resource.java
│   ├── service/
│   │   ├── UserService.java
│   │   ├── AuthService.java
│   │   └── ResourceService.java
│   └── exception/
│       ├── UserAlreadyExistsException.java
│       ├── ResourceNotFoundException.java
│       └── ResourceAlreadyExistsException.java
│
├── application/                    // Application layer (use cases, DTOs)
│   ├── dto/
│   │   ├── auth/
│   │   │   ├── SignInRequestDto.java
│   │   │   └── AuthResponseDto.java
│   │   ├── user/
│   │   │   └── UserResponseDto.java
│   │   └── resource/
│   │       └── ResourceResponseDto.java
│   └── mapper/
│       ├── UserMapper.java
│       ├── AuthMapper.java
│       └── ResourceMapper.java
│
├── infrastructure/                 // Infrastructure layer (external dependencies)
│   ├── persistence/
│   │   ├── entity/
│   │   │   └── UserEntity.java
│   │   └── repository/
│   │       └── UserRepository.java
│   ├── storage/
│   │   ├── StorageService.java
│   │   └── minio/
│   │       ├── MinioStorageService.java
│   │       └── MinioConfig.java
│   └── security/
│       ├── SecurityConfig.java
│       └── UserDetailsServiceImpl.java
│
└── presentation/                   // Presentation layer (controllers, API)
    ├── api/
    │   ├── AuthApi.java
    │   ├── UserApi.java
    │   └── ResourceApi.java
    ├── controller/
    │   ├── AuthController.java
    │   ├── UserController.java
    │   └── ResourceController.java
    ├── validation/
    │   ├── annotation/
    │   └── validator/
    └── exception/
        └── GlobalExceptionHandler.java
```

**Преимущества такой структуры:**
- **Чёткие границы между слоями** — видно, какие классы относятся к presentation, business logic, infrastructure
- **Контроль зависимостей** — можно настроить ArchUnit тесты для проверки Dependency Rule (например, domain не должен зависеть от infrastructure)
- **Модульность** — можно выделить domain в отдельный модуль, который не зависит от фреймворков
- **Тестируемость** — бизнес-логику можно тестировать без Spring Context и инфраструктуры
- **Масштабируемость** — проще добавлять новые фичи, понимая, в какой слой добавлять код

### пакет /auth

1. **Мутация DTO в сервисном слое нарушает принцип immutability**

В `AuthService` происходит изменение состояния request DTO, что противоречит принципу неизменяемости данных и может привести к неожиданным побочным эффектам.

```java
// AuthService.java
public Authentication signIn(SignInRequestDto signInRequestDto) {
    var authToken = getUnauthenticatedToken(signInRequestDto);
    
    signInRequestDto.setPassword(null);  // мутация входного параметра!
    
    var authentication = authenticationManager.authenticate(authToken);
    eraseCredentials(authToken, authentication);
    return authentication;
}

public Authentication signUp(SignUpRequestDto signUpRequestDto) {
    encodePassword(signUpRequestDto);  // мутация входного параметра!
    // ...
}
```

**Почему это плохо:**
- **Нарушение принципа неизменяемости** — DTO должны быть immutable, изменение их состояния создаёт неявные побочные эффекты
- **Проблемы с отладкой** — если DTO используется в нескольких местах, изменение его состояния в одном месте влияет на другие
- **Нарушение контракта** — метод получает параметр и неявно изменяет его, что не очевидно из сигнатуры
- **Неэффективная очистка памяти** — `setPassword(null)` не гарантирует удаление пароля из памяти, так как исходная строка остаётся в heap

**Рекомендация:**

Шифровать пароль при маппинге в `CreateUserDto` или в `UserService`, а не мутировать request DTO. Для очистки чувствительных данных из памяти использовать `char[]` вместо `String`.

```java
// Вариант 1: Шифровать при маппинге
@Mapper
public interface AuthUserMapper {
    
    @Mapping(target = "password", expression = "java(encodePassword(signUpRequestDto.getPassword()))")
    CreateUserDto toCreateUserDto(SignUpRequestDto signUpRequestDto, @Context PasswordEncoder passwordEncoder);
    
    default String encodePassword(String password) {
        // Этот метод будет вызван MapStruct с инжектированным PasswordEncoder
        return null; // MapStruct заменит на вызов через @Context
    }
}

// В AuthService
public Authentication signUp(SignUpRequestDto signUpRequestDto) {
    var createUserDto = authUserMapper.toCreateUserDto(signUpRequestDto, passwordEncoder);
    var userId = userService.create(createUserDto);
    return getAuthenticatedToken(signUpRequestDto.getUsername(), userId);
}

// Вариант 2: Шифровать в UserService (проще)
public Authentication signUp(SignUpRequestDto signUpRequestDto) {
    var createUserDto = new CreateUserDto(
        signUpRequestDto.getUsername(),
        passwordEncoder.encode(signUpRequestDto.getPassword())
    );
    
    var userId = userService.create(createUserDto);
    return getAuthenticatedToken(signUpRequestDto.getUsername(), userId);
}

// Вариант 3: Сделать DTO полностью immutable (records)
public record SignUpRequestDto(
    @ValidUsername String username,
    @ValidPassword String password
) {}

public record SignInRequestDto(
    @ValidUsername String username,
    @ValidPassword String password
) {}

// Тогда мутация невозможна, и код становится чище
public Authentication signUp(SignUpRequestDto signUpRequestDto) {
    var encodedPassword = passwordEncoder.encode(signUpRequestDto.password());
    var createUserDto = new CreateUserDto(signUpRequestDto.username(), encodedPassword);
    
    var userId = userService.create(createUserDto);
    return getAuthenticatedToken(signUpRequestDto.username(), userId);
}

**Рекомендация:** а для чего ты делаешь `signInRequestDto.setPassword(null);`? `encodePassword(signUpRequestDto);` тоже убрать нужно. В чем проблема пароль шифровать на этапе записи в бд/мапинге в CreateUserDto?

2. **Избыточная сложность в методе `getAuthenticatedToken`**

Метод создаёт токен через builder, хотя можно использовать стандартный конструктор.

```java
// Сейчас
private UsernamePasswordAuthenticationToken getAuthenticatedToken(SignUpRequestDto signUpRequestDto, UUID userId) {
    var username = signUpRequestDto.getUsername();
    var userDetails = new UserDetailsImpl(userId, username, null);

    return UsernamePasswordAuthenticationToken
            .authenticated(username, null, List.of())
            .toBuilder()
            .principal(userDetails)
            .build();
}
```

**Рекомендация:**

```java
private UsernamePasswordAuthenticationToken getAuthenticatedToken(String username, UUID userId) {
    var userDetails = new UserDetailsImpl(userId, username, null);
    return UsernamePasswordAuthenticationToken.authenticated(userDetails, null, List.of());
}
```

3. **Ручное создание DTO в контроллере вместо использования маппера**

В `AuthController` создаётся `AuthResponseDto` вручную, что нарушает единообразие подхода к маппингу.

```java
// AuthController.java
@PostMapping("/sign-up")
public ResponseEntity<AuthResponseDto> signUp(...) {
    var authentication = authService.signUp(signUpRequestDto);
    
    invalidateExistingSession(request);
    updateSecurityContext(request, response, authentication);
    
    var authResponseDto = new AuthResponseDto(signUpRequestDto.getUsername());  // ручное создание
    
    return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(authResponseDto);
}
```

**Почему это плохо:**
- **Нарушение единообразия** — в других местах используется MapStruct, здесь — ручное создание
- **Дублирование логики** — если добавятся поля в DTO, нужно будет менять код в нескольких местах
- **Сложность поддержки** — непонятно, где искать логику маппинга

**Рекомендация:**

```java
// Создать маппер
@Mapper
public interface AuthMapper {
    AuthResponseDto toAuthResponseDto(UserDetailsImpl userDetails);
}

// В контроллере
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {
    
    private final AuthService authService;
    private final AuthMapper authMapper;
    private final SecurityContextRepository securityContextRepository;

    @Override
    @PostMapping("/sign-up")
    public ResponseEntity<AuthResponseDto> signUp(...) {
        var authentication = authService.signUp(signUpRequestDto);
        
        invalidateExistingSession(request);
        updateSecurityContext(request, response, authentication);
        
        var userDetails = (UserDetailsImpl) authentication.getPrincipal();
        var authResponseDto = authMapper.toAuthResponseDto(userDetails);
        
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(authResponseDto);
    }
}
```

### пакет /user

1. **Смешение ответственности в UserService**

`UserService` реализует `UserDetailsService` (Spring Security) и одновременно содержит бизнес-логику создания пользователя. Это нарушает Single Responsibility Principle.

```java
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {
    
    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) {
        // Spring Security логика
    }

    public UUID create(CreateUserDto createUserDto) {
        // Domain-логика создания пользователя
    }
}
```

**Почему это плохо:**
- **Нарушение SRP** — класс отвечает за две разные задачи: аутентификацию (Spring Security) и управление пользователями (бизнес-логика)
- **Сложность тестирования** — нужно мокировать зависимости для обеих ответственностей
- **Плохая масштабируемость** — при добавлении новых операций с пользователями класс будет расти

**Рекомендация:**

```java
// Разделить на два класса

// 1. Для Spring Security
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {
    
    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(userMapper::toUserDetails)
                .orElseThrow(() -> UsernameNotFoundException.fromUsername(username));
    }
}

// 2. Для domain-логики
@Service
@RequiredArgsConstructor
public class DomainUserService {
    
    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UUID create(CreateUserDto createUserDto) {
        var user = userMapper.toUser(createUserDto);
        
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new UserAlreadyExistsException();
        }
        
        return user.getId();
    }
    
    // Здесь можно добавить другие методы: update, delete, findById и т.д.
}
```

2. **Ручное создание DTO в UserController**

Аналогично `AuthController`, в `UserController` создаётся DTO вручную.

```java
@GetMapping("/me")
public ResponseEntity<UserResponseDto> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
    var userResponseDto = new UserResponseDto(userDetails.getUsername());
    return ResponseEntity.ok(userResponseDto);
}
```

**Рекомендация:**

```java
// Создать метод в существующем UserMapper
@Mapper(unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {
    User toUser(CreateUserDto createUserDto);
    UserDetailsImpl toUserDetails(User user);
    
    // Добавить
    UserResponseDto toUserResponseDto(UserDetails userDetails);
}

// В контроллере
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController implements UserApi {
    
    private final UserMapper userMapper;

    @Override
    @GetMapping("/me")
    public ResponseEntity<UserResponseDto> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
        var userResponseDto = userMapper.toUserResponseDto(userDetails);
        return ResponseEntity.ok(userResponseDto);
    }
}
```

3. **Использование кастомных аннотаций валидации там, где достаточно стандартных**

В проекте созданы кастомные аннотации `@ValidUsername` и `@ValidPassword`, хотя их функциональность можно реализовать стандартными Jakarta Bean Validation аннотациями.

```java
// Сейчас: кастомная аннотация
@ValidUsername
@Schema(
    minLength = UsernameValidator.MIN_LENGTH,
    maxLength = UsernameValidator.MAX_LENGTH,
    pattern = UsernameValidator.REGEX_PATTERN
)
private final String username;
```

**Почему это плохо:**
- **Избыточная сложность** — создание кастомных аннотаций и валидаторов требует дополнительного кода
- **Дублирование** — логика валидации дублируется в аннотации и в `@Schema`
- **Сложность понимания** — разработчику нужно искать реализацию валидатора, чтобы понять правила

**Рекомендация:**

```java
// Использовать стандартные аннотации Jakarta Bean Validation
public record SignUpRequestDto(
    @NotBlank(message = "Username is required")
    @Size(min = 5, max = 20, message = "Username must be between 5 and 20 characters")
    @Pattern(
        regexp = "^[a-zA-Z0-9]+[a-zA-Z_0-9]*[a-zA-Z0-9]+$",
        message = "Username must contain only latin letters, digits and underscores, and cannot start or end with underscore"
    )
    @Schema(
        minLength = 5,
        maxLength = 20,
        pattern = "^[a-zA-Z0-9]+[a-zA-Z_0-9]*[a-zA-Z0-9]+$"
    )
    String username,
    
    @NotBlank(message = "Password is required")
    @Size(min = 5, max = 20, message = "Password must be between 5 and 20 characters")
    @Pattern(
        regexp = "^[a-zA-Z0-9!@#$%^&*(),.?\":{}|<>\\[\\]/`~+=\\-_';]+$",
        message = "Password contains invalid characters"
    )
    @Schema(
        minLength = 5,
        maxLength = 20
    )
    String password
) {}
```

**Когда кастомные аннотации оправданы:**
- Сложная бизнес-логика валидации (например, проверка уникальности в БД)
- Валидация с зависимостями (например, проверка связанных полей)
- Переиспользуемая валидация в нескольких DTO

В данном случае валидация простая (длина, паттерн), поэтому кастомные аннотации избыточны.

### пакет /resource

1. **Отсутствие валидации на null в критических местах**

В `ResourceService.uploadFiles()` используется `Objects.requireNonNull()` без обработки возможного `NullPointerException`, что может привести к падению приложения.

```java
// ResourceService.java
public List<ResourceResponseDto> uploadFiles(UUID userId, String path, List<MultipartFile> files) {
    // ...
    for (var file : files) {
        var filePath = Objects.requireNonNull(file.getOriginalFilename());  // NPE если null!
        
        if (PathUtil.isFileName(filePath)) {
            // ...
        }
    }
}
```

**Почему это плохо:**
- **Неинформативная ошибка** — клиент получит 500 Internal Server Error вместо понятного сообщения
- **Отсутствие валидации** — проверка должна быть на уровне валидатора, а не в бизнес-логике
- **Нарушение fail-fast** — ошибка обнаруживается в середине обработки, а не в начале

**Рекомендация:**

```java
// Добавить проверку в ResourceFilesValidator
@Override
public boolean isValid(List<MultipartFile> files, ConstraintValidatorContext context) {
    // Проверка на пустой список
    if (files == null || files.isEmpty()) {
        setCustomMessage(context, "Files list cannot be empty");
        return false;
    }
    
    // Проверка каждого файла
    for (var file : files) {
        if (file.isEmpty()) {
            setCustomMessage(context, "File cannot be empty");
            return false;
        }
        
        var filePath = file.getOriginalFilename();
        if (filePath == null || filePath.isBlank()) {
            setCustomMessage(context, "File name is missing");
            return false;
        }
        
        if (!isValid(filePath, context)) {
            return false;
        }
    }
    
    return true;
}

// В сервисе можно безопасно использовать
public List<ResourceResponseDto> uploadFiles(UUID userId, String path, List<MultipartFile> files) {
    // ...
    for (var file : files) {
        var filePath = file.getOriginalFilename();  // уже проверено валидатором
        // ...
    }
}
```

2. **Избыточная сложность метода `uploadFiles`**

Метод `ResourceService.uploadFiles()` выполняет слишком много задач: валидацию, создание директорий, загрузку файлов. Это нарушает Single Responsibility Principle и затрудняет тестирование.

```java
// Сейчас: метод на 36 строк с множественной ответственностью
public List<ResourceResponseDto> uploadFiles(UUID userId, String path, List<MultipartFile> files) {
    var uploadedResourcesInfo = new ArrayList<StorageObjectInfo>();
    var userFilesPath = getUserFilesPath(userId, path);
    var checkedDirectoriesPaths = new HashSet<String>();

    for (var file : files) {
        var filePath = Objects.requireNonNull(file.getOriginalFilename());

        if (PathUtil.isFileName(filePath)) {
            var fullFilePath = userFilesPath + filePath;
            checkFileNotExists(fullFilePath);
            validateObjectsConflict(fullFilePath);
            continue;
        }

        var rootDirectoryName = PathUtil.extractRootParentDirectoryName(filePath);
        var rootDirectoryPath = userFilesPath + rootDirectoryName + PathUtil.PATH_DELIMITER;

        if (!checkedDirectoriesPaths.contains(rootDirectoryPath)) {
            var rootDirectoryInfo = createRootDirectory(rootDirectoryPath);
            checkedDirectoriesPaths.add(rootDirectoryPath);
            uploadedResourcesInfo.add(rootDirectoryInfo);
        }

        var nestedDirectoriesInfo = createNestedDirectories(userFilesPath, filePath, checkedDirectoriesPaths);
        uploadedResourcesInfo.addAll(nestedDirectoriesInfo);
    }

    var uploadedFilesInfo = storageService.uploadFiles(BucketName.USER_FILES, userFilesPath, files);
    uploadedResourcesInfo.addAll(uploadedFilesInfo);

    return resourceMapper.toResourceResponseDtos(uploadedResourcesInfo);
}
```

**Почему это плохо:**
- **Нарушение SRP** — метод отвечает за валидацию, создание директорий, загрузку файлов и маппинг
- **Сложность тестирования** — нужно тестировать все сценарии в одном тесте
- **Плохая читаемость** — сложно понять логику работы метода

**Рекомендация:**

```java
// Разбить на несколько методов с чёткой ответственностью

public List<ResourceResponseDto> uploadFiles(UUID userId, String path, List<MultipartFile> files) {
    var userFilesPath = ResourcePathUtil.createUserResourcePath(userId, path);
    
    // 1. Валидация
    validateFilesForUpload(userFilesPath, files);
    
    // 2. Создание структуры директорий
    var createdDirectories = prepareDirectoryStructure(userFilesPath, files);
    
    // 3. Загрузка файлов
    var uploadedFiles = storageService.uploadFiles(BucketName.USER_FILES, userFilesPath, files);
    
    // 4. Объединение результатов и маппинг
    var allResources = new ArrayList<StorageObjectInfo>();
    allResources.addAll(createdDirectories);
    allResources.addAll(uploadedFiles);
    
    return resourceMapper.toResourceResponseDtos(allResources);
}

private void validateFilesForUpload(String userFilesPath, List<MultipartFile> files) {
    for (var file : files) {
        var filePath = file.getOriginalFilename();
        var fullFilePath = userFilesPath + filePath;
        
        if (storageService.fileExists(BucketName.USER_FILES, fullFilePath)) {
            throw new ResourceAlreadyExistsException(ResourceType.FILE);
        }
        
        validateObjectsConflict(fullFilePath);
    }
}

private List<StorageObjectInfo> prepareDirectoryStructure(String userFilesPath, List<MultipartFile> files) {
    var createdDirectories = new ArrayList<StorageObjectInfo>();
    var checkedPaths = new HashSet<String>();
    
    for (var file : files) {
        var filePath = file.getOriginalFilename();
        
        if (PathUtil.isFileName(filePath)) {
            continue;  // файл в корневой директории, создавать ничего не нужно
        }
        
        var directoriesInfo = createDirectoriesForFile(userFilesPath, filePath, checkedPaths);
        createdDirectories.addAll(directoriesInfo);
    }
    
    return createdDirectories;
}

private List<StorageObjectInfo> createDirectoriesForFile(
        String userFilesPath, String filePath, Set<String> checkedPaths) {
    
    var createdDirectories = new ArrayList<StorageObjectInfo>();
    var directoryPath = userFilesPath + PathUtil.removeResourceName(filePath);
    
    while (!directoryPath.equals(userFilesPath) && !checkedPaths.contains(directoryPath)) {
        if (!storageService.directoryExists(BucketName.USER_FILES, directoryPath)) {
            var directoryInfo = storageService.createDirectory(BucketName.USER_FILES, directoryPath);
            createdDirectories.add(directoryInfo);
        }
        
        checkedPaths.add(directoryPath);
        directoryPath = PathUtil.removeResourceName(directoryPath);
    }
    
    return createdDirectories;
}
```

3. **Дублирование кода в методах `moveFile` и `moveDirectory`**

Оба метода содержат похожую логику формирования пути результата.

```java
// moveDirectory
var directoryPath = ResourcePathUtil.removeUserFolder(toPath);
directoryPath = PathUtil.removeResourceName(directoryPath);
directoryPath = directoryPath.isEmpty() ? PathUtil.PATH_DELIMITER : directoryPath;
var directoryName = PathUtil.extractResourceName(toPath);

// moveFile
var filePath = ResourcePathUtil.removeUserFolder(toPath);
filePath = PathUtil.removeResourceName(filePath);
filePath = filePath.isEmpty() ? PathUtil.PATH_DELIMITER : filePath;
var fileName = PathUtil.extractResourceName(toPath);
```

**Рекомендация:**

```java
// Вынести в отдельный метод
private ResourceResponseDto createResourceResponseDto(String toPath, Long size, ResourceType type) {
    var resourcePath = ResourcePathUtil.removeUserFolder(toPath);
    resourcePath = PathUtil.removeResourceName(resourcePath);
    resourcePath = resourcePath.isEmpty() ? PathUtil.PATH_DELIMITER : resourcePath;
    
    var resourceName = PathUtil.extractResourceName(toPath);
    
    return new ResourceResponseDto(resourcePath, resourceName, size, type);
}

// Использование
private ResourceResponseDto moveDirectory(String fromPath, String toPath) {
    // ... логика перемещения
    return createResourceResponseDto(toPath, null, ResourceType.DIRECTORY);
}

private ResourceResponseDto moveFile(String fromPath, String toPath) {
    var fileInfo = storageService.findFileInfo(BucketName.USER_FILES, fromPath)
            .orElseThrow(() -> new ResourceNotFoundException(ResourceType.FILE));
    // ... логика перемещения
    return createResourceResponseDto(toPath, fileInfo.size(), ResourceType.FILE);
}
```

4. **Magic string в методе `validateObjectsConflict`**

Сообщение об ошибке жёстко прописано в коде.

```java
private void validateObjectsConflict(String path) {
    var message = "It is not allowed to create a file and a folder with the same name in the same directory";
    // ...
}
```

**Рекомендация:**

```java
// Создать класс с константами
public final class ErrorMessages {
    public static final String FILE_DIRECTORY_NAME_CONFLICT = 
        "It is not allowed to create a file and a folder with the same name in the same directory";
    
    private ErrorMessages() {}
}

// Использование
private void validateObjectsConflict(String path) {
    if (PathUtil.isDirectory(path)) {
        var filePath = PathUtil.trimLastSlash(path);
        
        if (storageService.fileExists(BucketName.USER_FILES, filePath)) {
            throw new ResourceAlreadyExistsException(ErrorMessages.FILE_DIRECTORY_NAME_CONFLICT);
        }
    }
    
    var directoryPath = path + PathUtil.PATH_DELIMITER;
    
    if (storageService.directoryExists(BucketName.USER_FILES, directoryPath)) {
        throw new ResourceAlreadyExistsException(ErrorMessages.FILE_DIRECTORY_NAME_CONFLICT);
    }
}
```

5. **Неэффективный поиск файлов**

Метод `search()` загружает все файлы пользователя из MinIO и фильтрует их в памяти Java.

```java
public List<ResourceResponseDto> search(UUID userId, String query) {
    var path = ResourcePathUtil.createUserResourcePath(userId);
    
    query = RegexpUtil.escapeSpecialCharacters(query);
    var pattern = SEARCH_PATTERN_FORMAT.formatted(query);
    
    var foundObjectsInfo = storageService.search(BucketName.USER_FILES, path, pattern);
    
    foundObjectsInfo.removeIf(objectInfo -> ResourcePathUtil.isUserFolder(objectInfo.path()));
    
    return resourceMapper.toResourceResponseDtos(foundObjectsInfo);
}
```

**Почему это плохо:**
- **O(N) сложность** — чем больше файлов у пользователя, тем медленнее поиск
- **Нагрузка на MinIO** — приходится получать метаданные всех объектов пользователя
- **Не масштабируется** — при 10,000+ файлов запрос будет очень медленным
- **Нет пагинации** — клиент получает все результаты сразу

**Рекомендация:**

Добавить таблицу `file_metadata` в PostgreSQL для хранения метаданных файлов:

```sql
CREATE TABLE file_metadata (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(200) NOT NULL,
    path VARCHAR(900) NOT NULL,
    size BIGINT,
    type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT unique_user_path UNIQUE (user_id, path)
);

CREATE INDEX idx_file_metadata_user_name ON file_metadata(user_id, name);
CREATE INDEX idx_file_metadata_user_path ON file_metadata(user_id, path);
```

```java
// FileMetadataRepository.java
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
    
    List<FileMetadata> findByUserIdAndNameContainingIgnoreCase(UUID userId, String name);
    
    Page<FileMetadata> findByUserIdAndNameContainingIgnoreCase(
        UUID userId, 
        String name, 
        Pageable pageable
    );
}

// ResourceService.java
public List<ResourceResponseDto> search(UUID userId, String query) {
    return fileMetadataRepository
            .findByUserIdAndNameContainingIgnoreCase(userId, query)
            .stream()
            .map(resourceMapper::toResourceResponseDto)
            .toList();
}

// С пагинацией
public Page<ResourceResponseDto> search(UUID userId, String query, Pageable pageable) {
    return fileMetadataRepository
            .findByUserIdAndNameContainingIgnoreCase(userId, query, pageable)
            .map(resourceMapper::toResourceResponseDto);
}
```

**Trade-off:** Нужна синхронизация между PostgreSQL и MinIO при операциях upload/delete/move. Можно использовать паттерн Outbox или события Spring для обеспечения консистентности.

### пакет /s3/minio

1. **Использование @SneakyThrows скрывает проверяемые исключения**

В `MinioStorageService` повсеместно используется `@SneakyThrows`, что скрывает проверяемые исключения и затрудняет обработку ошибок.

```java
@Override
@SneakyThrows
public Optional<StorageObjectInfo> findFileInfo(BucketName bucketName, String path) {
    try {
        var statObjectResponse = minioClient.statObject(StatObjectArgs.builder()
                .bucket(bucketName.getValue())
                .object(path)
                .build()
        );
        return Optional.of(minioObjectMapper.toStorageObjectInfo(statObjectResponse));
    } catch (ErrorResponseException exception) {
        // ...
    }
}
```

**Почему это плохо:**
- **Скрытие ошибок** — `@SneakyThrows` превращает checked exceptions в unchecked, что делает их необработанными
- **Нарушение контракта** — сигнатура метода не показывает, какие исключения могут быть выброшены
- **Сложность отладки** — при ошибке stack trace будет менее информативным
- **Плохая практика** — Lombok не рекомендует использовать `@SneakyThrows` в production коде

**Рекомендация:** Убрать и сделать нормальную обработку ошибок

2. **Hardcoded значение `BucketName.USER_FILES` в методе `downloadDirectory`**

В методе используется хардкод вместо параметра метода.

```java
@Override
@SneakyThrows
public void downloadDirectory(BucketName bucketName, String path, ZipOutputStream zipOutputStream) {
    // ...
    for (var resultItem : resultItemsToDownload) {
        // ...
        try (var inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(BucketName.USER_FILES.getValue())  // хардкод!
                        .object(item.objectName())
                        .build())
        ) {
            inputStream.transferTo(zipOutputStream);
        }
        // ...
    }
}
```

**Рекомендация:**

```java
try (var inputStream = minioClient.getObject(
        GetObjectArgs.builder()
                .bucket(bucketName.getValue())  // использовать параметр
                .object(item.objectName())
                .build())
) {
    inputStream.transferTo(zipOutputStream);
}
```

3. **Отсутствие обработки ошибок при параллельной загрузке файлов**

В методе `uploadFiles` используется `ExecutorService` для параллельной загрузки, но ошибки загрузки не обрабатываются.

```java
@Override
@SneakyThrows
public List<StorageObjectInfo> uploadFiles(BucketName bucketName, String rootPath, List<MultipartFile> files) {
    var uploadedFilesInfo = new ArrayList<StorageObjectInfo>();
    var executor = Executors.newCachedThreadPool();

    try {
        for (var file : files) {
            var filePath = Objects.requireNonNull(file.getOriginalFilename());
            var fullPath = rootPath + filePath;

            executor.submit(() -> minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName.getValue())
                    .object(fullPath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .build()
            ));  // ошибка загрузки будет проглочена!

            var fileName = PathUtil.extractResourceName(filePath);
            var uploadedFileInfo = new StorageObjectInfo(fullPath, fileName, file.getSize(), false);
            uploadedFilesInfo.add(uploadedFileInfo);
        }
    } finally {
        executor.shutdown();
    }

    try {
        if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
            executor.shutdownNow();
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
    }

    return uploadedFilesInfo;
}
```

**Почему это плохо:**
- **Потеря ошибок** — если загрузка одного файла упадёт, клиент не узнает об этом
- **Неконсистентное состояние** — метод вернёт успех, хотя часть файлов не загружена
- **Сложность отладки** — ошибки теряются в executor

**Рекомендация:**

```java
@Override
public List<StorageObjectInfo> uploadFiles(BucketName bucketName, String rootPath, List<MultipartFile> files) {
    var executor = Executors.newCachedThreadPool();
    var futures = new ArrayList<Future<StorageObjectInfo>>();

    try {
        for (var file : files) {
            var filePath = Objects.requireNonNull(file.getOriginalFilename());
            var fullPath = rootPath + filePath;
            var fileName = PathUtil.extractResourceName(filePath);

            var future = executor.submit(() -> {
                try {
                    minioClient.putObject(PutObjectArgs.builder()
                            .bucket(bucketName.getValue())
                            .object(fullPath)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .build()
                    );
                    return new StorageObjectInfo(fullPath, fileName, file.getSize(), false);
                } catch (Exception e) {
                    throw new StorageException("Failed to upload file: " + fullPath, e);
                }
            });
            
            futures.add(future);
        }
    } finally {
        executor.shutdown();
    }

    // Ждём завершения всех задач и собираем результаты
    var uploadedFilesInfo = new ArrayList<StorageObjectInfo>();
    
    try {
        for (var future : futures) {
            uploadedFilesInfo.add(future.get(1, TimeUnit.HOURS));
        }
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
        executor.shutdownNow();
        throw new StorageException("Failed to upload files", e);
    }

    return uploadedFilesInfo;
}
```

4. **Использование `Executors.newCachedThreadPool()` без ограничений**

`newCachedThreadPool()` создаёт неограниченное количество потоков, что может привести к исчерпанию ресурсов при большом количестве файлов.

**Рекомендация:**

```java
// Использовать фиксированный пул потоков
private static final int UPLOAD_THREAD_POOL_SIZE = 10;

var executor = Executors.newFixedThreadPool(UPLOAD_THREAD_POOL_SIZE);

// Или использовать виртуальные потоки (Java 21)
var executor = Executors.newVirtualThreadPerTaskExecutor();
```

### пакет /security

1. **Создание ObjectMapper в каждом запросе**

В `HttpStatusResponseBodyEntryPoint` создаётся новый `ObjectMapper` при каждой ошибке аутентификации.

```java
public class HttpStatusResponseBodyEntryPoint implements AuthenticationEntryPoint {

    private final HttpStatus httpStatus;
    private final ObjectMapper objectMapper = new ObjectMapper();  // создаётся при каждом создании EntryPoint

    @Override
    public void commence(...) {
        // ...
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
```

**Почему это плохо:**
- **Неэффективность** — `ObjectMapper` тяжёлый объект, его создание затратно
- **Потеря настроек** — не используются настройки Spring Boot для Jackson (например, форматирование дат)
- **Нарушение DI** — вместо инжекции создаётся объект вручную

**Рекомендация:**

```java
@RequiredArgsConstructor
public class HttpStatusResponseBodyEntryPoint implements AuthenticationEntryPoint {

    private final HttpStatus httpStatus;
    private final ObjectMapper objectMapper;  // инжектируется из Spring

    @Override
    public void commence(...) {
        response.setStatus(httpStatus.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        var errorResponse = new ErrorResponseDto(httpStatus.getReasonPhrase());
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}

// В SecurityConfig
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) {
    http.csrf(AbstractHttpConfigurer::disable);
    http.cors(cors -> corsConfigurationSource());

    configureExceptionHandling(http, objectMapper);
    authorizeHttpRequests(http);

    return http.build();
}

private void configureExceptionHandling(HttpSecurity httpSecurity, ObjectMapper objectMapper) {
    var httpStatusEntryPoint = new HttpStatusResponseBodyEntryPoint(HttpStatus.UNAUTHORIZED, objectMapper);
    httpSecurity.exceptionHandling(handling -> handling.authenticationEntryPoint(httpStatusEntryPoint));
}
```

2. **Неиспользуемая настройка CORS в SecurityConfig**

В методе `securityFilterChain` настройка CORS не применяется.

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) {
    http.csrf(AbstractHttpConfigurer::disable);
    http.cors(cors -> corsConfigurationSource());  // метод вызывается, но результат не используется!

    configureExceptionHandling(http);
    authorizeHttpRequests(http);

    return http.build();
}
```

**Рекомендация:** убрать либо начать использовать

### пакет /exception

1. **Использование @Value в GlobalExceptionHandler**

В `GlobalExceptionHandler` используется `@Value` для получения конфигурации, что усложняет тестирование.

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @Value("${spring.servlet.multipart.max-file-size}")
    private String maxFileSize;
    
    // ...
}
```

**Почему это плохо:**
- **Сложность тестирования** — нужно мокировать Spring Environment
- **Нарушение инкапсуляции** — конфигурация разбросана по классам
- **Дублирование** — та же настройка может использоваться в других местах

**Рекомендация:**

```java
// Создать @ConfigurationProperties
@ConfigurationProperties(prefix = "spring.servlet.multipart")
public record MultipartProperties(
    String maxFileSize,
    String maxRequestSize
) {}

// В GlobalExceptionHandler
@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MultipartProperties multipartProperties;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<MaxFileSizeErrorResponseDto> handle(MaxUploadSizeExceededException ignore) {
        var maxFileSize = multipartProperties.maxFileSize();
        
        if (!hasDataUnit(maxFileSize)) {
            maxFileSize = maxFileSize + DataUnit.BYTE.getSuffix();
        }

        var maxFileSizeValue = Long.parseLong(maxFileSize.replaceAll("\\D", ""));
        var maxFileSizeUnit = maxFileSize.replaceAll("\\d", "");

        var responseDto = new MaxFileSizeErrorResponseDto("File too large.", maxFileSizeValue, maxFileSizeUnit);

        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(responseDto);
    }
}
```

2. **Magic strings в сообщениях об ошибках**

Все сообщения об ошибках жёстко прописаны в коде, что затрудняет локализацию и поддержку.

```java
@ExceptionHandler(BadCredentialsException.class)
public ResponseEntity<ErrorResponseDto> handle(BadCredentialsException ignore) {
    return getErrorResponse(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
}

@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<ErrorResponseDto> handle(HttpMessageNotReadableException ignore) {
    return getErrorResponse(HttpStatus.BAD_REQUEST, "Invalid json format.");
}
```

**Рекомендация:**

```java
// Создать класс с константами
public final class ErrorMessages {
    public static final String INVALID_CREDENTIALS = "Invalid username or password.";
    public static final String INVALID_JSON = "Invalid json format.";
    public static final String INTERNAL_ERROR = "Internal server error.";
    public static final String FILE_TOO_LARGE = "File too large.";
    
    private ErrorMessages() {}
}

// Использование
@ExceptionHandler(BadCredentialsException.class)
public ResponseEntity<ErrorResponseDto> handle(BadCredentialsException ignore) {
    return getErrorResponse(HttpStatus.UNAUTHORIZED, ErrorMessages.INVALID_CREDENTIALS);
}

// Или использовать messages.properties для i18n
# messages.properties
error.auth.invalidCredentials=Invalid username or password.
error.validation.invalidJson=Invalid json format.
error.internal=Internal server error.

// В GlobalExceptionHandler
@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponseDto> handle(BadCredentialsException ignore, Locale locale) {
        var message = messageSource.getMessage("error.auth.invalidCredentials", null, locale);
        return getErrorResponse(HttpStatus.UNAUTHORIZED, message);
    }
}
```

### пакет /common/validation

1. **Избыточная абстракция в BaseConstraintValidator (скорее всего ИИ генерация)**

Класс `BaseConstraintValidator` содержит множество методов валидации, многие из которых используются только в одном-двух местах.

```java
public abstract class BaseConstraintValidator<A extends Annotation, T> implements ConstraintValidator<A, T> {
    
    protected boolean checkNotBlank(ConstraintValidatorContext context, String value) { ... }
    protected boolean checkLengthBetween(ConstraintValidatorContext context, String value, int minLength, int maxLength) { ... }
    protected boolean checkMaxLength(ConstraintValidatorContext context, String value, int maxLength) { ... }
    protected boolean checkMaxBytes(ConstraintValidatorContext context, String value, int maxBytes) { ... }
    protected boolean checkNotStartWith(String value, String prefix, ConstraintValidatorContext context) { ... }
    protected boolean checkEndWith(ConstraintValidatorContext context, String value, String suffix) { ... }
    protected boolean checkNotStartOrEndWith(ConstraintValidatorContext context, String value, String pattern) { ... }
    protected boolean patternMatches(ConstraintValidatorContext context, String value, Pattern pattern) { ... }
    protected boolean checkExtraSpaces(ConstraintValidatorContext context, String value) { ... }
    protected void setCustomMessage(ConstraintValidatorContext context, String message) { ... }
}
```

**Почему это плохо:**
- **Over-engineering** — создаётся сложная иерархия классов там, где достаточно простых валидаторов
- **Нарушение YAGNI** — многие методы не используются или используются редко
- **Сложность понимания** — разработчику нужно изучать базовый класс, чтобы понять валидацию

**Рекомендация:**

Использовать стандартные Jakarta Bean Validation аннотации вместо создания сложной иерархии кастомных валидаторов. Кастомные валидаторы создавать только для действительно специфичной бизнес-логики.

```java
// Вместо кастомных валидаторов
public record SignUpRequestDto(
    @NotBlank
    @Size(min = 5, max = 20)
    @Pattern(regexp = "^[a-zA-Z0-9]+[a-zA-Z_0-9]*[a-zA-Z0-9]+$")
    String username,
    
    @NotBlank
    @Size(min = 5, max = 20)
    @Pattern(regexp = "^[a-zA-Z0-9!@#$%^&*(),.?\":{}|<>\\[\\]/`~+=\\-_';]+$")
    String password
) {}

// Кастомный валидатор только для действительно сложной логики
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ResourcePathValidator.class)
public @interface ValidResourcePath {
    String message() default "Invalid resource path";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    boolean onlyDirectory() default false;
}
```

2. **Использование @Value в валидаторе ResourceFilesValidator**

В валидаторе используется `@Value` для получения конфигурации, что нарушает принцип единственной ответственности.

```java
public class ResourceFilesValidator extends BaseConstraintValidator<ValidResourceFiles, List<MultipartFile>> {

    @Value("${multipart-files-count-limit}")
    private int filesCountLimit;
    
    // ...
}
```

**Почему это плохо:**
- **Смешение ответственности** — валидатор зависит от конфигурации приложения
- **Сложность тестирования** — нужно поднимать Spring Context для тестирования валидатора
- **Нарушение принципа изоляции** — валидатор не должен знать об источнике конфигурации

**Рекомендация:**

```java
// Вариант 1: передавать лимит через аннотацию
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ResourceFilesValidator.class)
public @interface ValidResourceFiles {
    String message() default "Invalid files";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    int maxCount() default 50;  // значение по умолчанию
}

public class ResourceFilesValidator implements ConstraintValidator<ValidResourceFiles, List<MultipartFile>> {
    
    private int maxCount;
    
    @Override
    public void initialize(ValidResourceFiles constraintAnnotation) {
        this.maxCount = constraintAnnotation.maxCount();
    }
    
    @Override
    public boolean isValid(List<MultipartFile> files, ConstraintValidatorContext context) {
        if (files.size() > maxCount) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("Too many files. Max - " + maxCount + ".")
                    .addConstraintViolation();
            return false;
        }
        // ...
    }
}

// Использование
public ResponseEntity<List<ResourceResponseDto>> uploadFiles(
    @AuthenticationPrincipal UserDetailsImpl userDetails,
    @RequestParam(defaultValue = PathUtil.PATH_DELIMITER) String path,
    @ValidResourceFiles(maxCount = 50) @RequestPart List<MultipartFile> files
) {
    // ...
}

// Вариант 2: вынести проверку количества файлов в отдельный валидатор
@Size(max = 50, message = "Too many files. Max - 50.")
@RequestPart List<MultipartFile> files
```
### Критичные баги и уязвимости безопасности (честно сделал с помощью ИИ)

1. **Path Traversal уязвимость — отсутствует проверка на `..` в путях**

Валидация путей не проверяет наличие последовательности `..`, что позволяет получить доступ к файлам других пользователей.

```java
// ResourcePathValidator.java
private static final String PATTERN = "^(?:[^/\\\\:*?\"<>|]+/?)+$";

// Этот паттерн НЕ блокирует:
// "folder/../../../user-other-id-files/secret.txt"
// "../../../etc/passwd"
```

**Почему это критично:**
- **Обход изоляции пользователей** — злоумышленник может получить доступ к файлам других пользователей через path traversal
- **Утечка конфиденциальных данных** — возможен доступ к любым файлам в MinIO бакете
- **OWASP Top 10** — это одна из самых распространённых уязвимостей веб-приложений

**Пример атаки:**

```bash
# Злоумышленник с userId = "123"
GET /api/resource?path=../../../user-456-files/private/secret.txt

# После ResourcePathUtil.createUserResourcePath(userId, path):
# "user-123-files/../../../user-456-files/private/secret.txt"
# MinIO нормализует путь к: "user-456-files/private/secret.txt"
# Получен доступ к файлам пользователя 456!
```

**Рекомендация:**

```java
// ResourcePathValidator.java
private static final String PATTERN = "^(?:[^/\\\\:*?\"<>|.]+/?)+$";  // запретить точки
private static final String ONLY_DIRECTORY_PATTERN = "^(?:[^/\\\\:*?\"<>|.]+/)+$";

@Override
public boolean isValid(String path, ConstraintValidatorContext context) {
    // Добавить явную проверку на path traversal
    if (path.contains("..")) {
        setCustomMessage(context, "Path traversal attack detected");
        return false;
    }
    
    if (onlyDirectory && PathUtil.isRootDirectory(path)) {
        return true;
    }

    var resourceName = PathUtil.extractResourceName(path);

    return checkNotBlank(context, path)
            && checkMaxBytes(context, path, PATH_MAX_BYTES)
            && resourceNameIsValid(context, resourceName)
            && checkExtraSpaces(context, path)
            && checkNotStartWith(path, PathUtil.PATH_DELIMITER, context)
            && (!onlyDirectory || checkEndWith(context, path, PathUtil.PATH_DELIMITER))
            && patternMatches(context, path, pattern);
}

// Также нужно нормализовать путь перед использованием
public static String createUserResourcePath(UUID userId, String path) {
    // Удалить все .. из пути
    path = path.replaceAll("\\.\\./", "");
    path = path.replaceAll("/\\.\\.", "");
    
    if (PathUtil.isRootDirectory(path)) {
        path = PathUtil.trimLastSlash(path);
    }

    return String.format(USER_FILES_DIR_FORMAT, userId) + path;
}
```

3. **Race Condition в операциях создания — TOCTOU (Time-Of-Check-Time-Of-Use)**

Проверка существования и создание ресурса не атомарны, что приводит к race condition при параллельных запросах.

```java
// ResourceService.java
public ResourceResponseDto createDirectory(UUID userId, String path) {
    var directoryPath = ResourcePathUtil.createUserResourcePath(userId, path);

    // Проверка существования
    if (storageService.directoryExists(BucketName.USER_FILES, directoryPath)) {
        throw new ResourceAlreadyExistsException(ResourceType.DIRECTORY);
    }

    var parentDirectoryPath = PathUtil.removeResourceName(directoryPath);

    if (!storageService.directoryExists(BucketName.USER_FILES, parentDirectoryPath)) {
        if (!ResourcePathUtil.isUserFolder(parentDirectoryPath)) {
            throw new ResourceNotFoundException("Parent directory does not exist.");
        }
    }

    validateObjectsConflict(directoryPath);

    // Создание директории - между проверкой и созданием может пройти время!
    storageService.createDirectory(BucketName.USER_FILES, directoryPath);
    
    // ...
}
```

**Сценарий race condition:**

```
Поток 1 (user123):                          Поток 2 (user123):
1. directoryExists("folder1/") → false      
2.                                           directoryExists("folder1/") → false
3. createDirectory("folder1/")              
4.                                           createDirectory("folder1/") → Перезапись!
```

**Почему это плохо:**
- **Потеря данных** — вторая операция может перезаписать результат первой
- **Некорректное поведение** — MinIO не выбрасывает ошибку при перезаписи директории
- **Проблемы при конкурентной работе** — несколько пользователей/вкладок могут создавать конфликты

**Рекомендация:**

Использовать conditional requests MinIO (If-None-Match):

```java
// MinIO conditional requests
public StorageObjectInfo createDirectory(BucketName bucketName, String path) {
    try {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName.getValue())
                .object(path)
                .headers(Map.of("If-None-Match", "*"))  // создать только если не существует
                .stream(new ByteArrayInputStream(new byte[]{}), 0, -1)
                .build()
        );
        
        var directoryName = PathUtil.extractResourceName(path);
        return new StorageObjectInfo(path, directoryName, 0, true);
        
    } catch (ErrorResponseException e) {
        if (e.errorResponse().code().equals("PreconditionFailed")) {
            throw new StorageException("Directory already exists", e);
        }
        throw new StorageException("Error during directory creation", e);
    }
}
```


## РЕКОМЕНДАЦИИ

1. **Реорганизовать структуру пакетов по архитектурным слоям** — перейти от организации по feature/domain к организации по layered architecture (presentation, application, domain, infrastructure). Это обеспечит чёткие границы между слоями, упростит контроль зависимостей (можно настроить ArchUnit тесты), повысит модульность и тестируемость. Альтернатива: если сохранять feature/domain структуру, то разделить слои внутри каждого feature (api/, controller/, service/, repository/, entity/, dto/).

2. **Разделить ответственность в сервисах** — выделить отдельные сервисы для разных доменных задач. Например, `UserService` для управления пользователями и `UserDetailsServiceImpl` для Spring Security. `ResourceService` разбить на `ResourceUploadService`, `ResourceDownloadService`, `ResourceSearchService` для соблюдения Single Responsibility Principle.

3. **Использовать стандартные Jakarta Bean Validation аннотации** — заменить кастомные аннотации `@ValidUsername` и `@ValidPassword` на стандартные `@NotBlank`, `@Size`, `@Pattern`. Кастомные валидаторы оставить только для сложной бизнес-логики (например, `@ValidResourcePath` для проверки путей с учётом файловой системы).

4. **Вынести magic strings в константы или properties** — создать класс `ErrorMessages` или использовать `messages.properties` для всех текстовых сообщений об ошибках. Это упростит поддержку и позволит добавить интернационализацию (i18n) в будущем.

5. **Заменить @SneakyThrows на явную обработку исключений** — создать кастомное unchecked исключение `StorageException` и оборачивать в него все checked exceptions от MinIO. Добавить обработку `StorageException` в `GlobalExceptionHandler` для единообразного ответа клиенту.

6. **Добавить таблицу метаданных файлов в PostgreSQL** — создать таблицу `file_metadata` с индексами по `user_id` и `name` для эффективного поиска файлов. Это позволит реализовать быстрый поиск (O(log N) вместо O(N)), пагинацию результатов и дополнительные фильтры (по размеру, дате, типу). Синхронизацию между PostgreSQL и MinIO можно реализовать через паттерн Outbox или Spring Events.

7. **Использовать @ConfigurationProperties вместо @Value** — заменить все `@Value` на типизированные классы конфигурации. Например, создать `MultipartProperties` для настроек загрузки файлов. Это обеспечит type-safety, автодополнение в IDE и упростит тестирование.

8. **Инжектировать ObjectMapper вместо создания вручную** — в `HttpStatusResponseBodyEntryPoint` инжектировать `ObjectMapper` из Spring Context вместо создания нового экземпляра. Это позволит использовать настройки Jackson из Spring Boot (форматирование дат, naming strategy и т.д.).

9. **Обрабатывать ошибки параллельной загрузки файлов** — в `MinioStorageService.uploadFiles()` использовать `Future` для получения результатов загрузки и обработки ошибок. Если загрузка хотя бы одного файла упала, выбросить `StorageException` с информацией о проблеме.

10. **Ограничить количество потоков для загрузки файлов** — заменить `Executors.newCachedThreadPool()` на `Executors.newFixedThreadPool(10)` или использовать виртуальные потоки Java 21 (`Executors.newVirtualThreadPerTaskExecutor()`). Это предотвратит исчерпание ресурсов при загрузке большого количества файлов.

11. **Разбить большие методы на более мелкие** — метод `ResourceService.uploadFiles()` разбить на несколько методов с чёткой ответственностью: `validateFilesForUpload()`, `prepareDirectoryStructure()`, `uploadFilesToStorage()`. Это улучшит читаемость, тестируемость и соответствие SRP.

12. **Сделать DTO полностью immutable** — убрать `@Setter` из `SignUpRequestDto` и `SignInRequestDto`, сделать их records. Вместо мутации создавать новые объекты. Это предотвратит неожиданные побочные эффекты и упростит reasoning о коде.

13. **Добавить валидацию на null для критических параметров** — в `ResourceService.uploadFiles()` добавить проверку `file.getOriginalFilename()` на null в валидаторе `ResourceFilesValidator`, а не в бизнес-логике. Это обеспечит fail-fast и понятные сообщения об ошибках для клиента.

## ИТОГ

Проект выполнен на хорошем уровне, видно уверенное владение Spring Boot и базовыми принципами ООП. Все ключевые требования ТЗ реализованы: REST API для работы с файлами, хранение в MinIO, аутентификация через Spring Security с сессиями в Redis, PostgreSQL для пользователей. Плюсом является абстракция хранилища через SimpleStorageService, что упрощает замену MinIO, а также использование MapStruct, глобальной обработки ошибок и типизированной конфигурации через @ConfigurationProperties.

**Основные проблемы связаны с архитектурой и соблюдением принципов ООП.**

Код организован по feature-пакетам, из-за чего в одном месте смешаны разные слои приложения, что размывает архитектуру и усложняет поддержку. В сервисах встречается мутация входных DTO, создающая неявные побочные эффекты. Есть смешение ответственностей: один и тот же сервис отвечает и за бизнес-логику, и за интеграцию с инфраструктурой безопасности, что нарушает SRP. Валидация избыточно усложнена и дублирует стандартные механизмы. Обработка исключений скрыта и недостаточно прозрачна. Реализация поиска файлов не масштабируется, так как опирается на загрузку и фильтрацию данных в памяти.

Для дальнейшего развития стоит поработать над архитектурой: разделить ответственности, строже соблюдать SOLID, улучшить обработку исключений между слоями. Полезно изучить паттерны согласованности для распределённых систем (Saga, Outbox), оптимизацию работы с объектным хранилищем и добавить метрики и трейсинг (Micrometer, Actuator). Это сделает решение более зрелым и production-ready.
