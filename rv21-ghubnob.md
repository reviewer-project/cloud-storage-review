[ghubnob/filecloud](https://github.com/ghubnob/filecloud)

## ХОРОШО

1. **Абстракция `PathResolver` и `PathObject` отделяет нормализацию путей от операций с хранилищем.** Интерфейсы лежат в пакете `paths`, а детали формирования S3-ключей вынесены в отдельные классы. `FileService` работает с контрактом, а не собирает ключи в каждом методе вручную.

2. **`DownloadContainer` в связке с `StreamingResponseBody` в `MainController#downloadResource()` реализует потоковую отдачу файлов и ZIP-архивов.** Данные пишутся в `OutputStream` HTTP-ответа через callback, а не загружаются целиком в heap — корректное решение для скачивания больших директорий.

3. **`WebMvcAsyncConfig` настраивает async MVC на virtual threads через `TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())`.** Это согласовано с `spring.threads.virtual.enabled=true` и не блокирует platform threads при длительной записи в response stream.

4. **Валидация сегментов пути сосредоточена в resolver-слое** — ограничение длины, запрет сегментов из одних точек, фильтрация недопустимых символов не размазаны по методам `FileService`.

5. **Сущность `User` не использует `@Data` и имеет `equals`/`hashCode` только по идентификатору через `Hibernate.getClass()`.** JPA-сущность защищена от типичных проблем с Lombok-generated `equals`/`hashCode` по всем полям.

6. **Transport DTO оформлены как records** — `PathResponse`, `ErrorResponse`, `RegisterUserRequest` компактны и не протекают persistence-моделью в REST-контракт.
---

## ЗАМЕЧАНИЯ

### пакет /configuration

1. В `S3Config` настройки MinIO/S3 читаются через четыре поля с `@Value`, а не через типизированный `@ConfigurationProperties`. 

Endpoint, access key, secret key и region объявлены как отдельные injection points, а имя бакета дублируется вторым `@Value` в `FileService`. Конфигурация — инфраструктурный слой и должна быть единым источником правды: разрозненные `@Value` не дают compile-time структуры настроек, усложняют подмену в тестах и повышают риск рассинхронизации между config-классом и сервисами.

**Рекомендация:**

Вынеси все S3-настройки в `@ConfigurationProperties` и используй этот класс как единый источник конфигурации.

2. В `SecurityConfig#filterChain()` отсутствует `AuthenticationManager` и `UserDetailsService`, из-за чего аутентификация реализована в контроллере в обход Spring Security. 

Filter chain разрешает `/api/auth/sign-up` и `/api/auth/sign-in`, но не содержит `AuthenticationProvider`. Проверка пароля и создание сессии происходят в `MainController`, а не в security-слое. 
Ты смешиваешь transport-слой и security-инфраструктуру: `@AuthenticationPrincipal` и `authenticated()` работают непредсказуемо, потому что principal создаётся вручную без прохождения через `AuthenticationManager`.

**Рекомендация:**

Реализуй `UserDetailsService`, зарегистрируй `DaoAuthenticationProvider` и используй `AuthenticationManager` для sign-in.

```java
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return User.withUsername(user.getUsername())
                .password(user.getPassword())
                .roles("USER")
                .build();
    }
}

@Bean
public AuthenticationManager authenticationManager(PasswordEncoder encoder,
                                                   DatabaseUserDetailsService userDetailsService) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
}
```

3. В `SecurityConfig#filterChain()` обработчик успешного logout возвращает HTTP 200, хотя контракт API требует `204 No Content`. 

Строка `.logoutSuccessHandler((request, response, authentication) -> response.setStatus(200))` явно переопределяет статус ответа. Клиент фронтенда из ТЗ ориентируется на контракт `/api/auth/sign-out`, и расхождение статус-кода ломает совместимость с React-приложением.

**Рекомендация:**

Верни `204 No Content` в logout success handler.

### пакет /controller

3. В `MainController` сосредоточены все REST- и RPC-эндпoинты приложения. 

Один класс содержит 11 endpoint-методов разных предметных областей, Swagger-аннотации и HTTP-детали для streaming download. Ты нарушаешь SRP на transport-слое: контроллер становится точкой изменения для любой новой фичи, а HTTP-контракт не отделён от реализации.

**Рекомендация:**

Вынеси mapping и OpenAPI в интерфейсЫ API, а в реализации оставь только делегирование сервису. Это так же в дальнейшем упрощает версиноирование ендпоинтов

4. В `MainController` на каждом endpoint повторяется один и тот же набор `@ApiResponse`. 

Одинаковые пять аннотаций копируются в каждый метод, что раздувает класс и создаёт риск рассинхронизации документации при изменении контракта ошибок.

**Рекомендация:**

Собери повторяющиеся ответы в составную аннотацию и используй её на методах API-интерфейса.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Невалидный или отсутствующий путь"),
        @ApiResponse(responseCode = "401", description = "Пользователь не авторизован"),
        @ApiResponse(responseCode = "404", description = "Ресурс не найден"),
        @ApiResponse(responseCode = "500", description = "Неизвестная ошибка")
})
public @interface ResourceOperationResponses {}

@GetMapping
@ResourceOperationResponses
PathResponse getResource(@RequestParam String path);
```

1. В `MainController#register()` ты вручную создаёшь `UsernamePasswordAuthenticationToken` и сохраняешь `SecurityContext` в сессию до вызова `UserService#register()`. 

Двухаргументный конструктор создаёт **неаутентифицированный** token с пустыми authorities, пароль при этом не проверяется, а `HttpSessionSecurityContextRepository` инстанцируется через `new`, минуя Spring bean. 
После регистрации Spring Security считает пользователя неаутентифицированным, поэтому запросы к `/api/user/me` получат 401, хотя по ТЗ сессия должна быть создана сразу.

**Рекомендация:**

Убери ручное управление `SecurityContext` из контроллера. После успешной регистрации выполни auto-login через `AuthenticationManager`.

```java
@PostMapping("/auth/sign-up")
@ResponseStatus(HttpStatus.CREATED)
public UserResponse register(@Valid @RequestBody RegisterUserRequest request) {
    UserResponse response = userService.register(request);
    Authentication authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password())
    );
    SecurityContextHolder.getContext().setAuthentication(authentication);
    return response;
}
```

2. В `MainController#authorization()` контекст безопасности сохраняется до проверки пароля, а `UserService#authorization()` только сравнивает credentials и возвращает DTO. 

Sign-in формально проходит через контроллер, но Spring Security не знает об успешной аутентификации: business-сервис проверяет пароль, а security-слой остаётся в состоянии anonymous. Любой код, опирающийся на `SecurityContextHolder` или `@AuthenticationPrincipal`, работает ненадёжно.

**Рекомендация:**

Перенеси проверку credentials в `AuthenticationManager` и убери `UserService#authorization()` как отдельный сценарий проверки пароля.

```java
@PostMapping("/auth/sign-in")
public UserResponse authorization(@Valid @RequestBody AuthorizationRequest request) {
    Authentication authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password())
    );
    SecurityContextHolder.getContext().setAuthentication(authentication);
    return new UserResponse(request.username());
}
```

5. В `MainController#register()` и `MainController#authorization()` ты логируешь объекты `RegisterUserRequest` и `AuthorizationRequest` через `log.info("... {}", request)`. 

Record содержит поле `password`, и стандартный `toString()` выведет его в application logs — credentials попадут в centralized storage с длительным retention.

**Рекомендация:**

Логируй только безопасные идентификаторы операции, без password.

6. Во всех файловых методах `MainController` principal извлекается как `@AuthenticationPrincipal String username`. 

Transport-слой и service-слой завязаны на строковый логин как на ключ хранилища, хотя по ТЗ корневая папка должна строиться по numeric user id. Typed principal даёт compile-time гарантию и исключает передачу произвольной строки в `@AuthenticationPrincipal`.

**Рекомендация:**

Введи `AuthenticatedUser` и используй его во всех контроллерах.

```java
public record AuthenticatedUser(Integer id, String username) implements UserDetails {

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() { return null; }

    @Override
    public String getUsername() { return username; }
}

@GetMapping("/resource")
public PathResponse getResource(@RequestParam String path,
                                @AuthenticationPrincipal AuthenticatedUser user) {
    return fileService.getResource(path, user.id());
}
```

7. В `MainController#uploadResource()` параметр `@RequestPart("object")` жёстко задаёт имя multipart-части. 

Фронтенд из ТЗ и типичные HTML file input отправляют файлы с part name `file`. Несовпадение имени part ломает интеграцию с React frontend: upload silently fails с 400, хотя тело запроса формально содержит файлы.

**Рекомендация:**

Принимай список файлов через `@RequestParam("file")`, как ожидает multipart upload из browser file input.

```java
@PostMapping("/resource")
@ResponseStatus(HttpStatus.CREATED)
public List<PathResponse> uploadResource(@RequestParam String path,
                                         @AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestParam("file") List<MultipartFile> files) throws IOException {
    return fileService.uploadResource(path, user.id(), files);
}
```

8. В `MainController` стоит `@Slf4j`, но constructor injection реализован вручную: объявлены `private final` поля и явный конструктор с присваиванием. Для Spring-бинов в проекте `@RequiredArgsConstructor` нигде не применяется — в `FileService` и `UserService` та же схема. Lombok подключён, но приносит только логгер, а основной boilerplate DI ты переписываешь сам.

**Рекомендация:**

Используй `@RequiredArgsConstructor` для constructor injection во всех Spring-компонентах с final-зависимостями.

```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class MainController {

    private final UserService userService;
    private final FileService fileService;

    @PostMapping("/auth/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterUserRequest request) {
        // ...
    }
}
```

### пакет /dto

1. В `RegisterUserRequest` на поле `password` сообщение валидации скопировано от username. 

Клиент при ошибке пароля получает текст про username — Bean Validation на transport DTO существует, чтобы клиент понимал, какое поле невалидно.

**Рекомендация:**

Исправь message для password и вынеси повторяющиеся ограничения в композитную анноташку

2. В `AuthorizationRequest` на полях стоит только `@NotBlank`, без `@Size`, хотя для sign-up ты ограничиваешь длину username и password. 

Короткий username `"ab"` пройдёт sign-in и дойдёт до `UserRepository`, хотя по контракту API должен получить 400. Transport-слой перестаёт быть единой точкой валидации входных данных.

**Рекомендация:**

Примени те же composable annotations, что и для регистрации.

```java
public record AuthorizationRequest(
        @NotBlank @Size(min = 3, max = 20, message = "Username must be from 3 to 20 characters")
        String username,
        @ValidPassword
        String password
) {}
```

3. Query-параметры `path`, `from`, `to`, `query` в `MainController` принимаются как plain `String` без Bean Validation. 

Валидация пути полностью отложена на `MinIOPathResolver` внутри сервиса, а пустой `query` на `/resource/search` дойдёт до `FileService#searchResources()` и вернёт все файлы пользователя. Transport-слой не защищает API от невалидного input на границе.

**Рекомендация:**

Введи request record для query params с validation annotations и используй `@Validated` на controller.

```java
public record ResourcePathRequest(
        @NotBlank(message = "Path must not be blank")
        String path
) {}

@GetMapping("/resource")
public PathResponse getResource(@Valid ResourcePathRequest request,
                                @AuthenticationPrincipal AuthenticatedUser user) {
    return fileService.getResource(request.path(), user.id());
}

@GetMapping("/resource/search")
public List<PathResponse> searchResources(
        @RequestParam @NotBlank(message = "Search query must not be blank") String query,
        @AuthenticationPrincipal AuthenticatedUser user) {
    return fileService.searchResources(query, user.id());
}
```
4. Разделение на request/response

У тебя сейчас все дтошки лежат в 1 папке, что создает путаницу и в дальнейшем можно будет долго искать файлы

**Рекомендация:** создать в Dto подпакеты request/response и разделить классы

### пакет /model

1. В `User` ты смешал Lombok-аннотации `@Getter`, `@NoArgsConstructor`, `@FieldDefaults` с ручным конструктором `User(String username, String password)`. Lombok в проекте подключён, но используется выборочно: часть boilerplate генерируется, часть пишется вручную без единого правила. При добавлении поля легко обновить только конструктор и забыть про геттер, либо наоборот — код выглядит как недоделанный рефакторинг.

**Рекомендация:**

Зафиксируй для JPA-сущностей минимальный набор Lombok и не дублируй им то, что аннотации уже генерируют.

```java
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    @Column(unique = true, nullable = false)
    String username;

    @Column(nullable = false)
    String password;

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }
    // equals/hashCode без изменений
}
```

### пакет /exception

1. В `ExceptionsHandler` отсутствует обработчик для `DownloadException`, хотя `FileService#downloadResource()` бросает его при ошибках streaming. 

Исключение не перехватывается `@RestControllerAdvice`, Spring вернёт generic 500 без тела `{ "message": "..." }` по контракту API.

**Рекомендация:**

Добавь handler для `DownloadException` с HTTP 500 и `ErrorResponse`.

2. В `ExceptionsHandler#handleNoSuchS3Key()` техническое исключение AWS SDK `NoSuchKeyException` пробрасывается наружу как `"Resource (S3 key) not found!"`. 

Handler раскрывает детали object storage в REST-ответе, а `FileService` уже переводит `NoSuchKeyException` в `ResourceNotFoundException` локально — глобальный handler создаёт два разных формата 404.

**Рекомендация:**

Удали handler для `NoSuchKeyException` из REST-слоя и оставь mapping только на domain exceptions в сервисе.

```java
// ExceptionsHandler — только domain/application exceptions
@ExceptionHandler(ResourceNotFoundException.class)
@ResponseStatus(HttpStatus.NOT_FOUND)
public ErrorResponse handleResourceNotFound(ResourceNotFoundException e) {
    return new ErrorResponse(e.getMessage());
}

// FileService
try {
    head = s3Client.headObject(headObjectRequest);
} catch (NoSuchKeyException ex) {
    throw new ResourceNotFoundException("File '" + path + "' not found");
}
```

3. В `ExceptionsHandler#handleMethodArgumentNotValid()` в message добавляется `e.getMessage()` от Spring BindingResult. 

Клиент получает `"Validation failed! Validation failed for argument..."` с техническими деталями внутри — framework internals нестабильны между версиями Spring и не пригодны для отображения пользователю.

**Рекомендация:**

Собери message из полей ошибки.

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
@ResponseStatus(HttpStatus.BAD_REQUEST)
public ErrorResponse handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
    return new ErrorResponse(message);
}
```

4. В `ExceptionsHandler` отсутствует fallback handler для непредвиденных исключений. 

Любой unchecked exception вне явного списка `@ExceptionHandler` вернёт стандартный Spring error body, а не `{ "message": "..." }`. Frontend не может универсально парсить ошибки.

**Рекомендация:**

Добавь общий handler с HTTP 500 и безопасным message.

```java
@ExceptionHandler(Exception.class)
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public ErrorResponse handleUnexpectedException(Exception e) {
    log.error("Unexpected error", e);
    return new ErrorResponse("Internal server error");
}
```

### пакет /paths

1. В `MinIOPathObject#getFullPath()` корневая папка пользователя формируется как `username + "/" + path`, а не `user-{id}-files/` согласно ТЗ. 

`PathResolver#resolve()` принимает `String username` и передаёт его в `MinIOPathObject` как `rootFolder`, numeric id из таблицы `users` в формировании storage key не участвует. Username виден в S3 keys и теоретически изменяем — это расходится с учебным контрактом хранилища и усложняет migration пользователей.

**Рекомендация:**

Передавай numeric user id в resolver и формируй root prefix через `@ConfigurationProperties`.

```java
@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(
        String bucketName,
        String userRootDirectory // user-%d-files/
) {}

public record UserStorageRoot(String value) {
    public static UserStorageRoot forUser(S3Properties properties, Integer userId) {
        return new UserStorageRoot(properties.userRootDirectory().formatted(userId));
    }
}

@Override
public PathObject resolve(String path, UserStorageRoot root) {
    return new S3PathObject(root.value(), normalizedRelativePath);
}
```

2. Классы `MinIOPathResolver` и `MinIOPathObject` лежат в пакете `paths.minio`, хотя к MinIO SDK они не обращаются — это обычная сборка S3-ключей из строк. 

Реальный клиент хранилища — `S3Client` из AWS SDK в `S3Config`. Название вводит в заблуждение: кажется, что path-слой завязан на MinIO, хотя MinIO здесь только S3-compatible backend, а код path-слоя от провайдера не зависит.

**Рекомендация:**

Переименуй классы и пакет под фактическую ответственность — работу с S3-ключами, а не с конкретным vendor SDK.

### пакет /service

1. В проекте нет интерфейсов на ключевых границах application- и infrastructure-слоёв. `MainController` внедряет конкретные `UserService` и `FileService`, `FileService` напрямую зависит от `S3Client` из AWS SDK, REST API не вынесен в controller-интерфейсы. Абстракции есть только у `PathResolver`/`PathObject` — этого недостаточно. Из-за жёсткой привязки к конкретным классам и vendor SDK быстро сменить реализацию с AWS S3 SDK на MinIO Java SDK из ТЗ нереально: придётся переписывать контроллеры, сервисы и тесты, а не подменить одну implementation.

**Рекомендация:**

Введи интерфейсы на каждой границе слоя и внедряй только их.

```java
public interface FileService {
    List<PathResponse> searchResources(String query, Integer userId);
}

public interface ObjectStorage {
    List<StoredObjectSummary> listByPrefix(String prefix);
}

@RestController
@RequiredArgsConstructor
public class ResourceController implements ResourceApi {
    private final FileService fileService;
}

@Service
@RequiredArgsConstructor
class S3ObjectStorage implements ObjectStorage {
    private final S3Client s3Client;
}
```

2. В `FileService` весь функционал работы с файлами — реализован в одном классе с прямыми вызовами `S3Client`. 

Класс на 330+ строк одновременно содержит DTO mapping, ZIP-архивацию, pagination по bucket и проверку существования объектов. Файлы лежат в MinIO, но доступ к ним идёт через AWS S3 SDK (`software.amazon.awssdk.services.s3.S3Client`), и каждый метод `FileService` завязан на его типы и API. 
Быстро переехать на MinIO Java SDK из ТЗ или на другое хранилище не получится — нужно переписывать весь сервис, а не подменить один адаптер. Unit-тестировать business-сценарии без поднятия S3-совместимого backend тоже сложно.

**Рекомендация:**

Вынеси физическое хранение в инфраструктурную абстракцию, а `FileService` оставь orchestration-слоем.

```java
public interface ObjectStorage {
    void putObject(String key, InputStream content, long size, String contentType);
    InputStream getObject(String key);
    void deleteObject(String key);
    void copyObject(String sourceKey, String destinationKey);
    boolean exists(String key);
    List<StoredObjectSummary> listByPrefix(String prefix);
}

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final ObjectStorage objectStorage;
    private final PathResolver pathResolver;

    public List<PathResponse> uploadResource(String path, Integer userId, List<MultipartFile> files) throws IOException {
        PathObject targetDirectory = pathResolver.resolve(path, UserStorageRoot.forUser(properties, userId));
        // business checks + objectStorage.putObject(...)
    }
}
```

3. В `UserService#register()` application-сервис пользователей напрямую вызывает `FileService#createRootFolderOnRegistration()`. 

Registration use case знает о файловом хранилище и инициализирует S3 prefix при создании аккаунта. `UserService` нельзя протестировать изолированно без mock `FileService`, а если инициализация storage fail после `userRepository.save`, в БД останется пользователь без root folder.

**Рекомендация:**

Публикуй domain event после сохранения пользователя и обрабатывай создание root folder отдельным listener.

```java
@Transactional
public UserResponse register(RegisterUserRequest request) {
    User saved = userRepository.save(new User(request.username(), passwordEncoder.encode(request.password())));
    events.publishEvent(new UserRegisteredEvent(saved.getId(), saved.getUsername()));
    return new UserResponse(saved.getUsername());
}

@EventListener
public void onUserRegistered(UserRegisteredEvent event) {
    String rootKey = properties.userRootDirectory().formatted(event.userId());
    objectStorage.putDirectoryMarker(rootKey + "/");
}
```

4. В `FileService#createDirectory()` возвращается `PathResponse` с перепутанными полями `path` и `name`

`return new PathResponse(folderName, parentName, null, FileType.DIRECTORY)`. `folderName` — имя создаваемой папки, `parentName` — путь родителя, но в DTO они переданы в обратном порядке. Frontend построит неверную навигацию — это функциональный баг на границе application и transport слоёв.

**Рекомендация:**

Верни parent path в поле `path`, имя новой папки — в `name`.

5. В `FileService#uploadResource()` загрузка обрабатывает только `file.getOriginalFilename()` как один сегмент имени, без нормализации вложенных путей из имени файла. 

ТЗ требует: если в имени файла указана поддиректория `upload_folder/test.txt`, в storage должна создаться соответствующая структура каталогов. Browser upload директории silently flatten, а `../` в original filename открывает path traversal в prefix пользователя.

**Рекомендация:**

Нормализуй relative path из original filename через `PathResolver` и создавай nested keys.

```java
for (MultipartFile file : files) {
    PathObject relativeFile = pathResolver.resolve(file.getOriginalFilename(), UserStorageRoot.empty());
    String targetKey = pathObj.getFullPath()
            + (pathObj.getFullPath().endsWith("/") ? "" : "/")
            + relativeFile.getFullPath();

    if (objectStorage.exists(targetKey)) {
        throw new FileAlreadyExistsException("File '" + file.getOriginalFilename() + "' already exists!");
    }

    objectStorage.putObject(targetKey, file.getInputStream(), file.getSize(), file.getContentType());
    response.add(new PathResponse(folderName, relativeFile.getLastSegment(), file.getSize(), FileType.FILE));
}
```

6. В `FileService#moveResource()` операции copy/delete для каждого объекта S3 выполняются без компенсации при ошибке. 

При перемещении директории в цикле `paginator.forEach` для каждого объекта вызываются `copyObject` и `deleteObject`. Если середина операции упадёт, часть файлов окажется продублирована, часть — удалена.

**Рекомендация:**

Сначала скопируй все объекты, при ошибке удали скопированные объекты.

7. В `FileService#searchResources()` поиск выполняется полным перебором всех object keys пользователя через `listObjectsV2Paginator`, хотя в PostgreSQL уже есть инфраструктура для пользователей, а таблицы метаданных файлов нет. Каждый search request делает LIST по всему prefix в bucket — это O(n) на количество объектов пользователя, дорого по latency и по стоимости S3-операций. Поиск через object storage вместо SQL metadata table — архитектурный потолок: при росте файлов endpoint `/resource/search` будет тормозить линейно, а индексировать bucket LIST-ом нельзя.

**Рекомендация:**

Заведи таблицу метаданных ресурсов в SQL и выполняй search через repository query по `user_id` и `name`.

```java
@Entity
@Table(name = "resources", indexes = @Index(columnList = "userId, name"))
public class ResourceEntity {
    @Id @GeneratedValue
    Long id;
    Integer userId;
    String path;
    String name;
    Long size;
    @Enumerated(EnumType.STRING)
    FileType type;
}

public interface ResourceMetadataRepository extends JpaRepository<ResourceEntity, Long> {

    @Query("""
            select r from ResourceEntity r
            where r.userId = :userId
              and lower(r.name) like lower(concat('%', :query, '%'))
            """)
    List<ResourceEntity> searchByUserIdAndName(@Param("userId") Integer userId,
                                               @Param("query") String query);
}

public List<PathResponse> searchResources(String query, Integer userId) {
    return resourceMetadataRepository.searchByUserIdAndName(userId, query).stream()
            .map(PathResponse::from)
            .toList();
}
```

8. В `UserService#register()` отсутствует `@Transactional`, а проверка уникальности username выполняется через `findByUsername()` до `save()`. 

Между check и insert другой request может создать пользователя с тем же username, UNIQUE constraint выбросит exception, который не мапится в `UserExistsException`. Клиент получит 500 вместо 409.

**Рекомендация:**

Пометь register transactional и обрабатывай `DataIntegrityViolationException` как conflict.

9. Lombok подключён в проект, но используется неполноценно и без единого правила. В `MainController` и `FileService` есть `@Slf4j`, но constructor injection написан вручную; в `UserService` Lombok отсутствует; в `User` — `@Getter`/`@NoArgsConstructor` рядом с ручным конструктором. Зависимость подключаешь ради логгера, а основной boilerplate DI и entity-кода всё равно пишешь сам — Lombok не снижает объём кода и не убирает риск рассинхронизации при добавлении полей.

**Рекомендация:**

Зафиксируй единый стиль Lombok для всего проекта: `@RequiredArgsConstructor` на Spring-бинах, `@Slf4j` только где нужен логгер, для JPA — `@Getter` + `@NoArgsConstructor(access = PROTECTED)`.

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final S3Client s3Client;
    private final PathResolver pathResolver;
}

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileService fileService;
}
```

---

## РЕКОМЕНДАЦИИ

1. Выстрой чёткую слоистую архитектуру: transport (`controller` + DTO + API-интерфейсы) → application services с явными интерфейсами → path/domain abstractions → infrastructure (`ObjectStorage` → `S3Client`/MinIO SDK). Сейчас `FileService` одновременно orchestration, ZIP-логика и AWS SDK adapter.

2. Централизуй security в Spring Security: `UserDetailsService`, `AuthenticationManager`, typed `AuthenticatedUser` с `user id`. Убери ручное управление `SecurityContext` из контроллеров и приведи logout к контракту `204 No Content`.

3. Введи SQL metadata layer для файлов и папок, а object storage оставь только для binary content. Это разблокирует search без full bucket scan, атомарные move/delete сценарии и изоляцию по `user_id`, а не по username prefix.

4. Собери всю инфраструктурную конфигурацию в `@ConfigurationProperties` records и создавай клиенты хранилища как beans в `configuration`-пакете. Business-сервисы должны получать typed abstractions, а не `@Value` и не vendor SDK types.

5. Раздели `MainController` по bounded context, вынеси HTTP-контракт и OpenAPI в API-интерфейсы, собери повторяющиеся `@ApiResponse` в составные аннотации. Transport-слой должен читаться как список сценариев, а не как swagger-документация.

6. Заведи `ObjectStorage`-адаптер между application-слоем и AWS S3 SDK. Тогда смена клиента (MinIO Java SDK из ТЗ, другой S3 provider, mock в unit-тестах) потребует новой реализации адаптера, а не переписывания `FileService`.

7. Доведи `@RestControllerAdvice` до полного контракта API: закрой все domain exceptions, убери mapping SDK-ошибок, добавь fallback handler и человекочитаемые validation messages. Клиент всегда должен получать `{ "message": "..." }`.

8. Приведи naming и структуру пакетов к фактической ответственности: path-слой и config — про S3-ключи и S3-клиент, а не про MinIO SDK; DTO — в подпакеты `request`/`response`; Lombok — `@RequiredArgsConstructor` для Spring-бинов и минимальный набор аннотаций для JPA-сущностей.

9. Развяжи регистрацию пользователя и инициализацию хранилища через domain events, добавь компенсацию для multi-step S3 workflows и покрой auth/file flows MockMvc-тестами с реальной security chain.

---

## ИТОГ

Проект демонстрирует рабочий каркас файлового облака. Заметны удачные зачатки — абстракция `PathResolver`/`PathObject`, аккуратная JPA-сущность `User`, `DownloadContainer` для streaming.

Ключевые слабости архитектурные и системные. Интерфейсы на границах слоёв фактически не используются: контроллеры, сервисы и хранилище завязаны на конкретные классы и AWS S3 SDK, поэтому быстро перейти на MinIO Java SDK из ТЗ или подменить adapter нереально — только переписывание. Lombok подключён, но применяется обрывками: `@Slf4j` есть, constructor injection везде руками, единого стиля нет. Поиск файлов реализован через LIST по bucket в S3, хотя для этого нужна SQL-таблица метаданных — при росте данных search и list-сценарии упрутся в O(n) и стоимость object storage API. Плюс auth в обход Spring Security и god controller/service без отделения HTTP-контракта.

Главный вектор роста: интерфейсы на каждой границе (`ResourceApi`, `FileService`, `ObjectStorage`); typed security principal с user id; SQL metadata для файлов; единые правила Lombok; `ObjectStorage`-адаптер вместо прямых вызовов `S3Client` в business-слое. Это минимальный набор, чтобы проект стал поддерживаемым и соответствовал архитектуре учебного Cloud File Storage.
