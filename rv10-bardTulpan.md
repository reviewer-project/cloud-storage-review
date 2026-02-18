[bardTulpan/Cloud_file_storage](https://github.com/bardTulpan/Cloud_file_storage)

## ХОРОШО

1. **Асинхронная загрузка файлов через `CompletableFuture`** — в `uploadFiles` каждый файл загружается параллельно на выделенном `Executor`. Для многофайлового upload это уместная оптимизация, которая сокращает общее время ответа.

2. **Потоковая передача данных при скачивании через `StreamingResponseBody`** — файл не буферизуется в памяти целиком, а передаётся клиенту по мере чтения из MinIO. Правильный подход для работы с большими файлами.

3. **Конфигурация через профили (`application-dev.properties`, `application-test.properties`)** — секреты и адреса инфраструктуры не захардкожены в основных свойствах, а вынесены в профильные файлы. Это упрощает переключение между средами.

4. **`PUT /resource/move` вместо `GET`** — ТЗ предписывает использовать `GET` для операции перемещения, что семантически неверно: `GET` не должен изменять состояние сервера

---

## ЗАМЕЧАНИЯ

### пакет /config

1. `SecurityConfig.java` содержит 72 строки закомментированного кода — полностью старая реализация конфигурации. Закомментированный код создаёт шум при чтении файла, засоряет diff в git. История изменений — это задача системы контроля версий, а не комментариев в файле.

**Рекомендация:** Удалить строки 1-72 в SecurityConfig.java целиком, всё, что нужно сохранить для истории, хранится в git log.

2. `RedisConfig` — пустой класс, единственная его цель — нести аннотацию `@EnableRedisHttpSession`. При этом та же аннотация уже стоит на `SecurityConfig`. Пустые классы-носители аннотаций — антипаттерн: неочевидно, где искать причину поведения сессии.

**Рекомендация:** Удалить RedisConfig.java полностью. @EnableRedisHttpSession оставить только в `SecurityConfig` или перенести в `SecurityPracticaApplication`

3. `MinioConfig.minioClient()` — внутри `@Bean` метода выполняется инициализационная логика: проверка существования бакета и его создание. `@Bean`-метод должен только конструировать и настраивать объект. Бизнес/инфраструктурные действия в нём нарушают принцип единственной ответственности: если MinIO недоступен при старте — создание бина `MinioClient` упадёт с `RuntimeException`, Spring не сможет инициализировать контекст, и приложение не запустится вообще, хотя бакет мог бы создаться позже. Кроме того, логика создания бакета тестируется только вместе с конфигурацией.

**Рекомендация:**
```java
// MinioConfig.java — только создание клиента
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;
    @Value("${minio.access-key}")
    private String accessKey;
    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}

// BucketInitializer.java — отдельный компонент для инициализации бакета
@Component
@RequiredArgsConstructor
@Slf4j
public class BucketInitializer {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @EventListener(ApplicationReadyEvent.class)
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
            );
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Bucket '{}' created.", bucketName);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize MinIO bucket: " + bucketName, e);
        }
    }
}
```

4. `SecurityConfig.authenticationEntryPoint` при попытке неавторизованного доступа возвращает HTTP 401 с **пустым телом ответа**. По ТЗ любая ошибка должна возвращать `{"message": "..."}`. Пустой ответ ломает контракт API для всех защищённых эндпоинтов.

**Рекомендация:**
```java
.exceptionHandling(exception -> exception
    .authenticationEntryPoint((request, response, authException) -> {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\": \"Unauthorized\"}");
    })
)
```

---

### пакет /controller

1. `AuthController.signIn` вручную кладёт `SecurityContextHolder.getContext()` в сессию под ключом `"SPRING_SECURITY_CONTEXT"`. Это внутренний ключ Spring Security (`HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY`). Использование внутренних деталей реализации через магическую строку нарушает инкапсуляцию Spring. При обновлении Spring Security этот ключ может измениться. Правильный способ — попросить Spring самого сохранить контекст, используя `HttpSessionSecurityContextRepository`.

**Рекомендация:**
```java
// SecurityConfig — зарегистрировать как бин: один экземпляр на приложение,
// управляется Spring, может переиспользоваться в других бинах
@Bean
public SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
}

// AuthController
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    @PostMapping("/sign-in")
    public RegistrationResponseDto signIn(
            @RequestBody @Valid RegistrationDto dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(dto.getUsername(), dto.getPassword());
        Authentication authentication = authenticationManager.authenticate(token);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // Spring сам сохраняет контекст в сессию
        securityContextRepository.saveContext(context, request, response);

        return new RegistrationResponseDto(dto.getUsername());
    }
}
```

2. `AuthController.signIn` не ставит `@Valid` на `@RequestBody RegistrationDto`. В `registration` он есть, в `signIn` — нет. Это значит, что при попытке войти с пустым `username` или паролем короче 6 символов Spring не вернёт 400, а передаст данные в `authenticationManager.authenticate(...)`

**Рекомендация:** 
```java
@PostMapping("/sign-in")
public RegistrationResponseDto signIn(
        @RequestBody @Valid RegistrationDto registrationDto,  // добавить @Valid, джакарта вложенный объект не будет валидировать без этой анноташки
        HttpServletRequest request) {
    // ...
}
```

3. `AuthController.getCurrentUser` находится по адресу `GET /api/auth/me`. По ТЗ этот эндпоинт должен быть `GET /api/user/me`. Была бы реальная работа, пришлось бы исправлять, тк фронтендер целится на `/api/user/me`

**Рекомендация:** не критично

4. `AuthController.registration` создаёт доменный объект `User` прямо в контроллере: `new User(registrationDto.getUsername(), registrationDto.getPassword())`. Контроллер — это transport-слой, его ответственность — десериализация HTTP-запроса и вызов сервиса. Создание доменного объекта — ответственность сервиса или маппера. Если добавится поле или изменится способ конструирования `User`, придётся менять контроллер.

**Рекомендация:** AuthService.register принимает DTO, а не готовую сущность. А так же сделать чтобы `RegistrationResponseDto` создавался через маппер, а не вручную

5. `ResourceController.move` возвращает `void`. По ТЗ эндпоинт перемещения должен возвращать `ResourceDto` перемещённого ресурса с кодом 200. Отсутствие тела ответа ломает клиентский код: фронтенд не получит обновлённые метаданные ресурса после операции.

**Рекомендация:** возвращать ResourceDto вместо void

6. `ResourceController` и `DirectoryController` содержат идентичный приватный метод `getUserId(Principal principal)`, который на каждый запрос делает **дополнительный запрос к базе данных** для получения `id` пользователя по `username`. При этом Spring Security уже аутентифицировал пользователя и загрузил его. Дублирование кода и лишний SELECT на каждый запрос.

**Рекомендация:** Сделать и хранить id в кастомном UserDetails, чтобы не делать лишний запрос к БД

7. Эндпоинт загрузки файлов реализован как `POST /api/resource/upload`. По ТЗ он должен быть `POST /api/resource?path=$path`

**Рекомендация:** не критично

8. `GlobalExceptionHandler` расположен в пакете `/controller`. Обработчик исключений — это не часть слоя контроллеров, это инфраструктурный компонент. Размещение в `/controller` нарушает логику организации пакетов и затрудняет навигацию.

**Рекомендация:** Перенести в отдельный пакет например `/handler` или `/advice`

---

### пакет /dto

1. `ResourceDto` помечен `@Builder`. Аннотация избыточна: `record` уже имеет канонический конструктор, через который создаются все экземпляры в коде. `@Builder` нигде в проекте не используется (все вызовы — `new ResourceDto(...)` или через `pathService.mapToDto`). Лишние аннотации засоряют код.

**Рекомендация:** убрать `@Builder`

2. `AuthResponse` — record, который нигде не используется в проекте

**Рекомендация:** Удалить AuthResponse.java полностью или разобраться где он должен был быть

---

### пакет /entity

1. `User` помечен `@Data`. Lombok `@Data` генерирует `equals()` и `hashCode()` по **всем полям** (`id`, `username`, `password`, `role`). Для JPA-сущностей это критическая проблема:
   - Новая (transient) сущность имеет `id = null`. После сохранения `id` появляется. `equals`/`hashCode` меняются, и если сущность лежала в `HashSet` или `HashMap`, она «потеряется» — по новому хэшу она не найдётся в старом бакете.
   - `equals` по полю `password` означает сравнение BCrypt-хэшей, что семантически бессмысленно.
   - `@Data` также генерирует `toString()`, который включает `password` — хэш пароля попадёт в логи при любом дебаг-логировании сущности.

**Рекомендация:** Убрать `@Data` и добавить `@Getter`, `@Setter`

2. `User implements UserDetails` — доменная сущность реализует интерфейс Spring Security. Это нарушение принципа инверсии зависимостей: доменный объект зависит от инфраструктурной библиотеки. Проблема на практике: при изменении Spring Security API (например, методов `UserDetails`) придётся изменять доменную сущность. Тестировать `User` в изоляции без Spring Security невозможно. При этом в проекте уже есть `CustomUserDetailsService`, который создаёт `UserDetails`-обёртку — функциональность дублируется.

**Рекомендация:**
```java
// User — чистый доменный объект, ничего не знает о Spring Security
@Getter
@Setter
@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password;
    private String role;
}

// CustomUserDetailsService возвращает UserDetails-обёртку
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new CustomUserDetails(user); // обёртка с id, username, roles
    }
}
```

---

### пакет /exception

1. `ExistException` определён, но нигде в проекте не используется. Есть `FileAlreadyExistsException` для того же смысла

**Рекомендация:** Удалить ExistException.java.

2. `MyBadRequestException` — именование с префиксом `My` не несёт смыслового значения и не соответствует Java-конвенциям. Имя класса должно отражать суть, а не принадлежность автору

**Рекомендация:** Переименовать MyBadRequestException -> BadRequestException

---

### пакет /repository

1. `MinioRepository` помечен `@Repository`. Аннотация `@Repository` в Spring предназначена для компонентов Data Access Layer — JPA-репозиториев, JDBC-шаблонов и т.п. Её семантика — «доступ к персистентному хранилищу». `MinioRepository` — это клиент внешнего S3-совместимого API, не персистентный репозиторий. Неправильная аннотация вводит в заблуждение: разработчик ищет JPA/JDBC-репозитории и находит Minio-клиент. Кроме того, `@Repository` включает трансляцию `DataAccessException`, которая здесь не нужна.

**Рекомендация:** Переименовать `MinioStorageClient`, поменять аннотацию на `@Service` и переместить в другой пакет, например `infrastucture`

2. `bucketName` читается из properties в трёх разных местах. В `MinioRepository` **опечатка** в дефолтном значении: `test-backet` вместо `test-bucket`

**Рекомендация:**
```java
// Один ConfigurationProperties-класс для всех MinIO-настроек
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucketName
) {}

// В MinioRepository и StorageService — инжектировать MinioProperties
@Component
@RequiredArgsConstructor
public class MinioStorageClient {
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public boolean exists(String path) {
        // используем minioProperties.bucketName()
    }
}
```

3. `MinioRepository.exists()` при любом исключении кроме `ErrorResponseException` возвращает `false`. Это означает, что таймаут соединения, IOException, отказ MinIO — всё молча трактуется как «объект не существует». В реальных условиях это приводит к созданию «дублей» (файл существует, но exists говорит false → попытка создать → ошибка непонятного происхождения) или к тому, что несуществующий ресурс возвращает 404, хотя MinIO просто недоступен.

**Рекомендация:**
```java
public boolean exists(String path) {
    try {
        minioClient.statObject(
                StatObjectArgs.builder().bucket(bucketName).object(path).build());
        return true;
    } catch (ErrorResponseException e) {
        if ("NoSuchKey".equals(e.errorResponse().code())) {
            return false;
        }
        // Любая другая ошибка MinIO — пробрасывать, не глотать
        throw new StorageException("MinIO stat error for path: " + path, e);
    } catch (Exception e) {
        // Сетевые ошибки, IO — тоже пробрасывать
        throw new StorageException("Unexpected error checking existence: " + path, e);
    }
}
```

---

### пакет /service

1. `AuthService implements UserDetailsService` — нарушение принципа единственной ответственности. `AuthService` отвечает за регистрацию пользователей. `UserDetailsService` — это интерфейс Spring Security для загрузки пользователя по `username` в процессе аутентификации. Это разные задачи. При этом в проекте уже есть `CustomUserDetailsService`, который также реализует `UserDetailsService`. В контексте Spring присутствуют два бина `UserDetailsService`. `DaoAuthenticationProvider` в `SecurityConfig` явно принимает `CustomUserDetailsService`, но само наличие двух реализаций создаёт путаницу и потенциальные конфликты.

**Рекомендация:** AuthService — только регистрация, без UserDetailsService

2. `AuthService.register` при занятом `username` выбрасывает `InvalidCredentialsException`. Это исключение `GlobalExceptionHandler` маппит на HTTP 401. По ТЗ конфликт при регистрации должен давать 409 Conflict. Неверное исключение ломает контракт API: клиент получит 401 вместо 409 и не поймёт, что username занят.

**Рекомендация:** Создать отдельный класс исключения и бросать правильное исключение. В `GlobalExceptionHandler` — маппить на 409

3. `AuthService.register` не создаёт сессию после регистрации. По ТЗ: «При регистрации юзеру сразу создаётся сессия и выставляется кука». Сейчас пользователь регистрируется, но не входит — ему нужно делать отдельный POST `/sign-in`. Это нарушение функциональных требований.

**Рекомендация:** AuthController.registration — после регистрации сразу аутентифицировать, пример есть в signIn

4. В `AuthService.register` проверка на существующий username написана в одну строку: `userRepository.findByUsername(user.getUsername()).isPresent()`. Цепочка вызовов без промежуточной переменной ухудшает читаемость.

**Рекомендация:**
```java
@Transactional
public User register(RegistrationDto dto) {
    boolean usernameAlreadyTaken = userRepository.findByUsername(dto.getUsername()).isPresent();
    if (usernameAlreadyTaken) {
        throw new UsernameAlreadyTakenException("Username already taken: " + dto.getUsername());
    }
    // ...
}
```

5. `CustomUserDetailsService.loadUserByUsername` создаёт `UserDetails` с `Collections.emptyList()` — пустым списком ролей. Роль пользователя хранится в БД и выставляется при регистрации (`ROLE_USER`), но не передаётся в `UserDetails`. Если в будущем добавится проверка ролей (например, admin-эндпоинты), все пользователи будут неавторизованы, несмотря на корректно сохранённую роль.

**Рекомендация:**
```java
@Override
public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    return userRepository.findByUsername(username)
            .map(user -> new org.springframework.security.core.userdetails.User(
                    user.getUsername(),
                    user.getPassword(),
                    List.of(new SimpleGrantedAuthority(user.getRole()))  // передавать роли
            ))
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
}
```

6. `PathService` помечен `@Service`, но не является сервисом в смысле Spring: у него нет ни одной инжектируемой зависимости, нет состояния, все методы — чистые трансформации входных данных. `@Service` сигнализирует, что класс содержит бизнес-логику и участвует в транзакционной/инфраструктурной работе Spring. Здесь это вводит в заблуждение. Кроме того, `PathService` смешивает две несвязанные ответственности: манипуляции с путями (`normalizePath`, `getParentPath`, `securityCheck`, `getUserRootPath`) и маппинг MinIO-объектов в DTO (`mapToResourceDto`, `mapToDto`). Маппинг DTO — это отдельная забота, не относящаяся к логике путей.

**Рекомендация:** Сделать PathUtils — утилитный класс со статическими методами, а маппинги вынести в отдельный `@Component` или использовать MapStruct

7. `StorageService` — God Class: один сервис содержит загрузку, скачивание, удаление, перемещение, поиск, листинг директорий, создание директорий — 263 строки с 8 публичными методами. Нарушение SRP. Любое изменение в логике поиска затрагивает тот же класс, что и логика загрузки. Тестирование одной операции требует настройки всего класса. По мере роста проекта класс будет только раздуваться.

**Рекомендация:** Разделить на специализированные сервисы:
```java
@Service
public class FileUploadService { /* uploadFiles */ }

@Service
public class FileDownloadService { /* downloadResource, checkResourceExists */ }

@Service
public class ResourceOperationService { /* move, deleteResource, getResource */ }

@Service
public class DirectoryService { /* createDirectory, listItems */ }

@Service
public class SearchService { /* search */ }
```

8. `StorageService` создаёт `Executor` через `new` прямо в конструкторе: `Executors.newFixedThreadPool(countOfThreads)`.

Проблемы:
- нет `shutdown()` при остановке приложения — потоки останутся жить, приложение не завершится корректно;
- нельзя подменить `Executor` в тестах без создания реального пула потоков;
- нарушение принципа DI — зависимости должны инжектироваться извне.

**Рекомендация:** Зарегистрировать Executor как Spring Bean
```java
@Configuration
public class AsyncConfig {

    @Value("${app.storage.threads:10}")
    private int storageThreads;

    @Bean("storageExecutor")
    public Executor storageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(storageThreads);
        executor.setMaxPoolSize(storageThreads);
        executor.setThreadNamePrefix("storage-");
        executor.initialize();
        return executor;
    }
}

// StorageService — инжектировать через конструктор
@Service
@RequiredArgsConstructor
public class StorageService {
    private final MinioRepository minioRepository;
    private final ZipService zipService;
    private final PathService pathService;
    @Qualifier("storageExecutor")
    private final Executor storageExecutor;
    // @Value убрать из конструктора, использовать пропертис
}
```

9. В `StorageService.deleteResource` при ошибке удаления объекта внутри папки выполняется только `log.error(...)` — исключение проглатывается. Пользователь получает `204 No Content` (успех), хотя часть файлов могла остаться в MinIO. Аналогичная проблема в методе `move`: ошибка копирования/удаления одного файла из папки логируется, но не останавливает операцию — папка окажется в частично перемещённом состоянии.

**Рекомендация:** собирать ошибки и пробрасывать после allOf
```java
List<CompletableFuture<Void>> futures = new ArrayList<>();
for (Result<Item> result : items) {
    futures.add(CompletableFuture.runAsync(() -> {
        try {
            String objectName = result.get().objectName();
            minioRepository.delete(objectName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete: " + e.getMessage(), e);
        }
    }, storageExecutor));
}

try {
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
} catch (CompletionException e) {
    throw new StorageException("Partial delete failure", e.getCause());
}
```

10. `StorageService.uploadFiles` оборачивает `FileAlreadyExistsException` в `RuntimeException` в лямбде:
```java
} catch (Exception e) {
    throw new RuntimeException(e); // FileAlreadyExistsException обёрнут в RuntimeException
}
```
`GlobalExceptionHandler` не знает про `RuntimeException` с причиной `FileAlreadyExistsException` — он поймает его в `handleGlobal` и вернёт HTTP 500 вместо 409. Пользователь не узнает, что файл уже существует.

**Рекомендация:** можно делать `throw e`

11. `StorageService.move` при перемещении файла (не папки) вызывает `minioRepository.list(fullFrom, true)`. В S3/MinIO `list` с prefix `fullFrom` вернёт **все объекты, чьё имя начинается с `fullFrom`**. Например, при перемещении файла `docs/report` также будет захвачен `docs/report-final` и `docs/report-2024`. Это приведёт к непредвиденному перемещению файлов с похожими именами.

**Рекомендация:**
```java
public ResourceDto move(String from, String to, Long userId) {
    String root = pathService.getUserRootPath(userId);
    String fullFrom = root + pathService.normalizePath(from);
    String fullTo = root + pathService.normalizePath(to);

    if (!minioRepository.exists(fullFrom)) throw new NotFoundException("Source not found");
    if (minioRepository.exists(fullTo)) throw new FileAlreadyExistsException("Target already exists");

    boolean isDirectory = fullFrom.endsWith("/");

    if (isDirectory) {
        // Для папки — list по prefix безопасен, т.к. папка заканчивается на "/"
        moveDirectory(fullFrom, fullTo);
    } else {
        // Для файла — прямое копирование + удаление, без list
        minioRepository.copy(fullFrom, fullTo);
        minioRepository.delete(fullFrom);
    }

    String normalized = pathService.normalizePath(to);
    return pathService.mapToDto(normalized, /* size */, ResourceType.FILE);
}
```

12. `UserService.findByUsername` выбрасывает `RuntimeException("User not found: " + username)`. Это непроверяемое исключение без доменного типа: `GlobalExceptionHandler` поймает его в `handleGlobal` и вернёт HTTP 500. Если пользователь не найден — это 404 (или 401 в контексте аутентификации), а не 500.

**Рекомендация:**
```java
public User findByUsername(String username) {
    return userRepository.findByUsername(username)
            .orElseThrow(() -> new NotFoundException("User not found: " + username));
}
```

13. `ZipService` инжектирует `MinioClient` напрямую, минуя `MinioRepository`. Это нарушает изоляцию слоёв: вся работа с MinIO должна идти через единую точку (`MinioRepository`/`MinioStorageClient`). Если нужно поменять имя бакета, добавить логирование или retry-логику — придётся делать это в двух местах. `bucketName` в `ZipService` передаётся как аргумент из `StorageService`, хотя мог бы браться из конфигурации напрямую.

**Рекомендация:** Сделать чтобы ZipService использовал MinioRepository, а не MinioClient

14. Lombok используется непоследовательно: в `UserService` и `ZipService` стоит `@RequiredArgsConstructor`, а в `AuthService` и `CustomUserDetailsService` конструктор написан вручную. В `AuthService` ещё и добавлен `@Autowired` — в Spring Boot это аннотация по умолчанию избыточна: если у бина ровно один конструктор, Spring инжектирует его автоматически без `@Autowired`. В `StorageService` ручной конструктор вынужден смешивать обычные зависимости и `@Value`-параметры — это само по себе сигнал, что `@Value`-поля нужно вынести в `@ConfigurationProperties` (см. замечание о `bucketName`), после чего конструктор можно будет убрать и перейти на `@RequiredArgsConstructor`.

**Рекомендация:**
```java
// AuthService — убрать @Autowired, добавить @RequiredArgsConstructor
@Service
@RequiredArgsConstructor  // заменяет ручной конструктор с @Autowired
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
}

// CustomUserDetailsService — аналогично
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;
}

// StorageService — после выноса minio.bucket-name в MinioProperties
// и executor в Spring Bean, конструктор исчезает полностью
@Service
@RequiredArgsConstructor
public class StorageService {
    private final MinioRepository minioRepository;
    private final ZipService zipService;
    private final PathService pathService;
    @Qualifier("storageExecutor")
    private final Executor storageExecutor;
    private final MinioProperties minioProperties; // вместо @Value + ручного конструктора
}
```

---

### пакет /test

1. Все тесты находятся в одном классе `SecurityPracticaApplicationTests` в корневом пакете. Тестируется только `StorageService`. Нет тестов для `AuthService` (регистрация с дублирующим username, сохранение пользователя), для `UserService`, для контроллеров. По ТЗ требуются интеграционные тесты сервиса по работе с пользователями — этого нет

**Рекомендация:** Разделить тесты по классам и добавить тестов

---

## РЕКОМЕНДАЦИИ

1. **Устранить дублирование `UserDetailsService`**

2. **Разделить `StorageService` по SRP**

3. **Унифицировать получение `userId` в контроллерах**

4. **Вынести настройки MinIO в `@ConfigurationProperties`**

5. **Добавить доменное исключение `StorageException`**

6. **Зарегистрировать `Executor` как Spring Bean**

7. **Написать интеграционные тесты для `AuthService`/`UserService`**

8. **Очистить закомментированный код и мёртвые артефакты, а так же двойные пробелы в коде и между методов**
---

## ИТОГ

Проект реализован на хорошем уровне. Весь заявленный в ТЗ функционал присутствует и работает. Есть (но мало) интеграционные тесты с Testcontainers, настроены профили окружений, реализована потоковая передача при скачивании. Замечания носят архитектурный и качественный характер, не блокируют работу приложения.

Главные точки роста: 
- `StorageService` взял на себя слишком много и его стоит разбить по SRP; 
- `User` не должен реализовывать `UserDetails` — это смешение домена с инфраструктурой; 
- `@Data` на JPA-сущности создаёт ненадёжный `equals`/`hashCode`; 
- Lombok применяется непоследовательно; 
- несколько мест молча глотают ошибки вместо того, чтобы пробрасывать их наверх.

**Что изучить для роста:**
- **JPA best practices**: почему `@Data` опасна для сущностей, как правильно реализовывать `equals`/`hashCode` — только по `id`.
- **SOLID/SRP на практике**: как определить границу ответственности сервиса и когда God Class пора дробить.
- **Spring Security internals**: как хранить дополнительные данные в `UserDetails` и получать их через `@AuthenticationPrincipal` без лишних запросов к БД.
- **Доменные исключения**: как строить иерархию исключений, чтобы `@ControllerAdvice` возвращал правильные HTTP-коды в каждом случае.
- **`@ConfigurationProperties`**: типобезопасная замена разрозненным `@Value`, которая исключает опечатки и упрощает рефакторинг конфигурации.
