[Aslamkhanov/cloud_file_storage](https://github.com/Aslamkhanov/cloud_file_storage)

## ХОРОШО

1. **`CustomUserDetails` хранит `id` пользователя** — в `ResourceController` и `DirectoryController` владелец файлов берётся из principal без лишнего запроса в БД.

2. **Файловые операции разбиты по сценариям** — upload, delete, move, search вынесены в отдельные классы, а не свалены в один фасад на тысячу строк.

3. **Работа с путями сосредоточена в `PathUtils`** — префикс пользователя, parent path и определение типа ресурса не копируются в каждый класс заново.

4. **Публичные application-сервисы имеют интерфейсы** — `AuthService`, `UserService`, `MinioService` отделены от реализаций, контроллеры зависят от контракта, а не от `*Impl`.

5. **Удаление директории идёт батчами** — `DeleteManager` читает `deleteBatchSize` из проперти и не пытается снести всё дерево одним вызовом.

---

## ЗАМЕЧАНИЯ

### пакет /advice

1. В `GlobalExceptionHandler` нет обработчиков для `StorageException`, `UploadException`, `MoveResourceException` и `SearchException`. 

Эти ошибки улетают в стандартный механизм Spring с другим телом ответа, и клиент не получает требуемый по ТЗ формат `{"message": "..."}`.

**Рекомендация:**

Добавь единый обработчик инфраструктурных исключений хранилища, наследуй UploadException от StorageException и лови его в адвайсе

2. В `GlobalExceptionHandler#handleDataIntegrity()` 

ты пытаешься угадать бизнес-смысл ошибки по тексту constraint из PostgreSQL. Handler не должен знать, что `uk3g1j96g94xpk3lpxl2qbl985x` — это занятый username: это деталь persistence-слоя, которая меняется при каждой миграции. 
Одной предварительной проверки `findByNameIgnoreCase` перед `save` недостаточно: при параллельных запросах оба потока не увидят запись, дойдут до INSERT, и один из них упадёт с `DataIntegrityViolationException`, если конфликт не перехватить в сервисе.

**Рекомендация:**

unique constraint в БД, а в `AuthServiceImpl#register()` переводи любой конфликт при сохранении в доменное исключение. Handler маппит только его в 409.

```java
@Transactional
public UserResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
    if (userRepository.findByNameIgnoreCase(request.getUserName()).isPresent()) {
        throw new UsernameAlreadyExistsException(request.getUserName());
    }

    User user = userMapper.toEntity(request);
    user.setPassword(passwordEncoder.encode(request.getPassword()));

    try {
        user = userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException ex) { //это будет невероятно редкое или никогда произошедшее событие, но если произойдет, надо ожидать
        throw new UsernameAlreadyExistsException(request.getUserName());
    }

    establishSession(user, httpRequest);
    return userMapper.toResponse(user);
}
```

```java
@ExceptionHandler(UsernameAlreadyExistsException.class)
public ResponseEntity<Map<String, String>> handleUsernameConflict(UsernameAlreadyExistsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("message", "Username already exists"));
}
```

---

### пакет /controller

1. В `ResourceController`, `DirectoryController`, `AuthController` и `UserController` HTTP-mapping, Bean Validation и OpenAPI-аннотации живут в классах-реализациях. 

Контракт REST API не отделён от делегирования в сервис, поэтому при изменении документации или версионировании endpoint придётся править классы с бизнес-логикой вызова.

**Рекомендация:**

Вынеси HTTP-контракт в интерфейс, а в `@RestController` оставь только делегирование. Это так же нужно для версионирования апи, если вдруг будет в2 ручка с другой реализацией, тебе это будет проще сделать

```java
@RequestMapping("/api/resource")
public interface ResourceApi {

    @GetMapping
    @GetResourceResponses
    ResourceDto get(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam("path") @NotBlank String path
    );
}

@RestController
@RequiredArgsConstructor
public class ResourceController implements ResourceApi {

    private final MinioService minioService;

    @Override
    public ResourceDto get(CustomUserDetails user, String path) {
        return minioService.get(path, user.getId());
    }
}
```

2. В `ResourceController` и `DirectoryController` одинаковые наборы `@ApiResponse` с кодами 400/401/404/500 повторяются на каждом методе. 

Документация раздувается, а при добавлении нового общего ответа придётся править десятки мест.

**Рекомендация:**

Собери повторяющиеся ответы в составные аннотации.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Невалидный или отсутствующий путь"),
        @ApiResponse(responseCode = "401", description = "Пользователь не авторизован"),
        @ApiResponse(responseCode = "404", description = "Ресурс не найден"),
        @ApiResponse(responseCode = "500", description = "Неизвестная ошибка")
})
public @interface ResourceEndpointResponses {}
```

3. В `UserController#findByName()` не используется `@AuthenticationPrincipal`, хотя `ResourceController` уже берёт `CustomUserDetails` через аннотацию. Из-за этого проверка авторизации уезжает в `UserServiceImpl`, а контроллер передаёт бесполезный `HttpServletRequest`.

**Рекомендация:**

Извлекай principal в контроллере — Spring Security уже гарантирует authenticated-доступ на `/api/user/me`.

```java
@GetMapping("/user/me")
public UserResponse getCurrentUser(@AuthenticationPrincipal CustomUserDetails user) {
    return userService.getCurrentUser(user.getUsername());
}
```

---

### пакет /dto

1. В `RegisterRequest` и `LoginRequest` на полях стоит только `@Size` без `@NotBlank`. 

Bean Validation пропускает `null`, поэтому запрос с пустым телом пройдёт в сервис. Правила валидации username и password полностью продублированы в двух классах.

**Рекомендация:**

Добавь `@NotBlank` как вариант можно сделать составной интрфейс чтобы не дублировать аннотации

2. DTO в проекте размазаны по разным пакетам

user-контракты лежат в `com.cloud_file.dto`, а ответы по файлам — в `com.cloud_file.minio.dto`. Контроллеры REST напрямую возвращают `ResourceDto` из инфраструктурного пакета MinIO, поэтому transport-слой зависит от деталей хранилища. 
При этом внутри `dto` request и response перемешаны в одной папке — по имени файла не сразу понятно, что входит в API, а что отдаётся наружу.

**Рекомендация:**

Собери transport-DTO в `dto.request` и `dto.response`, перенеси `ResourceDto` в dto/response и переименуй в ResourceResponseDto или просто ResourceResponse 

---

### пакет /entity

1. В `User` на entity повешен полный набор Lombok 

Для JPA-сущности это перебор: `@Builder` провоцирует ручную сборку в сервисе вместо маппера, class-level `@Setter` открывает `setId()` и ломает инвариант идентичности, `@ToString` протащит `password` в логи, а связка `@Builder` + `@AllArgsConstructor` + `@NoArgsConstructor` держится только ради билдера, который entity не нужен.

**Рекомендация:**

Оставь минимум для JPA — `@Getter`, `@NoArgsConstructor`. ЭквалсХешКод обычно не нужен для ентити, если тебе нужно ентити с чем-то сравнивать, обычно это значит что какие-то проблемы с архитектурой

---

### пакет /mapper

1. В `UserMapper#toEntity(LoginRequest)` есть маппинг login-запроса в `User`, но `AuthServiceImpl#login()` этот метод не вызывает. 

Мёртвый код создаёт ложное ожидание, что при логине создаётся сущность, и размывает ответственность маппера между регистрацией и аутентификацией.

**Рекомендация:**

Удали неиспользуемый метод и оставь маппер только для сценариев, где DTO реально превращается в сущность.

```java
@Component
public class UserMapper {

    public User toEntity(RegisterRequest request) {
        return User.builder()
                .name(request.getUserName())
                .password(request.getPassword())
                .build();
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getName());
    }
}
```

---

### пакет /minio/manager

1. Классы `UploadManager`, `DeleteManager`, `MoveManager` и остальные помечены `@Component` и лежат в пакете `manager` — при этом они выполняют роль инфраструктурных сервисов: оркестрируют сценарии upload, delete, move поверх MinIO. Название `*Manager` в Spring-проекте не несёт семантики — непонятно, чем они отличаются от `MinioService`, а `@Component` маскирует назначение класса как сервисного бина storage-слоя.

**Рекомендация:**

Перенеси классы в `storage.service`, назови по операции и пометь `@Service`. Зависимость — от `ObjectStorage`, не от `MinioUtils`.

```java
@Service
@RequiredArgsConstructor
public class ResourceUploadService {

    private final ObjectStorage objectStorage;

    public List<ResourceDto> upload(String path, long userId, List<UploadFileCommand> files) {
        // логика бывшего UploadManager
    }
}
```

2. В `MoveManager#renameResource()` и `#moveResource()` в сигнатуру прокидываются `directoryType` и `fileType`, хотя `MinioServiceImpl#move()` всегда передаёт туда константы `ResourceType.DIRECTORY` и `ResourceType.FILE`. Тип ресурса уже определяется по пути через `pathUtils.isDirectory(to)`, а в ответ кладётся переданный enum, а не вычисленный. Параметр `userId` в `moveResource()` объявлен, но нигде в методе не используется.

**Рекомендация:**

Убери лишние параметры и вычисляй `ResourceType` из пути при сборке ответа.

3. В `GetResourceManager#get()` для любого пути вызывается `minioUtils.buildStatObject()`, который работает только с объектами. 

Папка без маркера-объекта, появившаяся только через вложенную загрузку файлов, в листинге видна, а `GET /resource?path=.../` вернёт 404. Это узкий edge case, основной сценарий с явно созданными папками работает.

**Рекомендация:**

Раздели получение метаданных файла и директории.

```java
public ResourceDto get(String path, long userId, String bucket) {
    String fullPath = pathUtils.buildFullPath(userId, path);
    if (pathUtils.isDirectory(path)) {
        if (!minioUtils.isDirectoryExists(bucket, fullPath)) {
            throw new ResourceNotFoundException("Resource not found: " + path);
        }
        return MinioUtils.buildResourceDto(
                pathUtils.getParentPath(path),
                pathUtils.extractNameFromPath(path),
                null,
                ResourceType.DIRECTORY
        );
    }
    StatObjectResponse stat = minioUtils.buildStatObject(fullPath, bucket);
    return MinioUtils.buildResourceDto(
            pathUtils.getParentPath(path),
            pathUtils.extractNameFromPath(path),
            stat.size(),
            ResourceType.FILE
    );
}
```

4. В `DownloadManager#downloadDirectory()` весь ZIP сначала собирается во временный файл на диске, и только потом отдаётся клиенту. 

Для больших папок это бьёт по диску и времени ответа, хотя HTTP-слой мог бы принимать потоковую запись архива.

**Рекомендация:**

Пиши ZIP прямо в `OutputStream` ответа через `StreamingResponseBody`


5. В `MoveManager#moveOrRenameDirectory()` копирование объектов и удаление исходной папки не атомарны. 

При сбое на середине переноса в bucket останутся и старые, и новые копии, а клиент получит 500 без возможности понять, в каком состоянии хранилище.

**Рекомендация:**

Собери перенос в пошаговый сценарий с компенсацией при ошибке.

```java
List<String> copiedKeys = new ArrayList<>();
try {
    for (Result<Item> itemResult : minioUtils.listObjects(bucket, from, true)) {
        String oldKey = itemResult.get().objectName();
        String newKey = to + oldKey.substring(from.length());
        minioUtils.copyObject(newKey, oldKey, bucket);
        copiedKeys.add(newKey);
    }
    deleteManager.deleteDirectory(from, bucket);
} catch (Exception e) {
    copiedKeys.forEach(key -> minioUtils.removeObject(bucket, key));
    throw new MoveResourceException("Move failed, changes rolled back", e);
}
```

6. В `AllInDirectory#getAllInDirectory()` для директорий к имени добавляется суффикс `/`

`CreateDirectoryManager` и `GetResourceManager` возвращают имя папки без него. Фронтенд в `config.js` умеет добавить слэш сам, поэтому листинг работает, но формат `name` разный на разных endpoint-ах.

**Рекомендация:**

Возвращай имя папки без завершающего `/`, а тип различай через `type`

---

### пакет /config

1. В `MinioBucketInitializer` лежит в `minio.util`, хотя это инфраструктурный startup-код, а не утилита. 

Класс создаёт bucket при старте приложения и должен жить рядом с `MinioConfig`, а параметры retry (`retries = 5`, `delaySeconds = 3`) зашиты в метод `run()` без выноса в проперти.

**Рекомендация:**

Перенеси инициализатор в `config` и читай настройки из `@ConfigurationProperties`

---

### пакет /minio/props -> /config/properties

1. `MinioProperties` и `StorageProperties` лежат в `minio.props`, хотя это конфигурация приложения, а не часть MinIO-домена. 

В `MinioProperties` стоит `@Data`, который генерирует `toString()` по всем полям, включая `secretKey` — секрет попадёт в лог при любом выводе объекта проперти.

**Рекомендация:**

Вынеси классы в `config.properties` и зарегистрируй через `@EnableConfigurationProperties` в `MinioConfig`.

```java
// config/properties/MinioProperties.java
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        @NotBlank String bucket,
        @NotBlank String url,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotNull StorageSettings storage
) {
    public record StorageSettings(
            int deleteBatchSize,
            long maxFileSize,
            String userRootPrefix
    ) {}
}

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.url())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
```

2. Шаблон корневой папки пользователя `user-{id}-files/` и лимит размера файла не описаны в проперти: префикс захардкожен в `PathUtils#buildFullPath()`, а лимит 209_715_200 байт продублирован в `UploadManager#uploadResources()` отдельно от `spring.servlet.multipart.max-file-size`. 

При смене лимита в `application.yaml` бизнес-проверка в загрузчике останется со старым значением.

**Рекомендация:**

Сделай `userRootPrefix` и `maxFileSize` полями `MinioProperties.storage` и используй их как единый источник настроек

---

### пакет /minio/util

1. `MinioUtils` оформлен как `@Component` в пакете `util`

Класс инжектит `MinioClient` и инкапсулирует все операции с bucket. По сути это полноценный адаптер MinIO SDK, спрятанный под именем «утилиты».

**Рекомендация:**

Переименуй класс в `MinioObjectStorage`, вынеси за интерфейс `ObjectStorage` и зарегистрируй как инфраструктурный бин в `config`. 

2. В проекте нет абстракции над хранилищем: `MinioService`, все manager-ы и `MinioUtils` напрямую завязаны на MinIO SDK

Подмена `MinioClient` на другой бин не спасёт — придётся переписывать каждый manager, потому что контракт storage-операций нигде не зафиксирован.

**Рекомендация:**

Введи `ObjectStorage` как границу инфраструктуры и переведи manager-ы на него.

```java
@Service
@RequiredArgsConstructor
public class MinioServiceImpl implements MinioService {

    private final ObjectStorage objectStorage;
    private final UploadManager uploadManager;
    // manager-ы принимают ObjectStorage, не MinioClient и не MinioUtils
}
```

3. `PathUtils` и статический `MinioUtils#buildResourceDto()` смешаны с инфраструктурным `@Component` в одном пакете `util`. 

Чистые функции работы с путями не должны быть Spring-бинами — у `PathUtils` нет зависимостей, а создание DTO не требует DI.

**Рекомендация:**

Оставь в `util` только статические хелперы без `@Component`.

---

### пакет /security

1. В `AuthServiceImpl#login()` и `#register()` аутентификация выполняется вручную

Пароль сверяется в сервисе, `SecurityContext` и сессия заполняются прямым кодом. Spring Security не участвует через `AuthenticationManager`, поэтому стандартные механизмы вроде `DaoAuthenticationProvider`, обработчиков ошибок входа и единой точки аудита обходятся стороной.

**Рекомендация:**

Делегируй вход `AuthenticationManager` и выноси создание сессии в отдельный компонент.

```java
@Transactional
public UserResponse login(LoginRequest request, HttpServletRequest httpRequest) {
    Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUserName(), request.getPassword())
    );
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);

    HttpSession session = httpRequest.getSession(true);
    session.setAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            context
    );
    CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
    return new UserResponse(principal.getUsername());
}
```

2. В `SecurityConfig#securityFilterChain()` пути `/api/auth/sign-in` и `/api/auth/sign-out` открыты через `permitAll()`

По ТЗ должен возвращать 401 неавторизованному клиенту. Сейчас это контролируется только ручной проверкой в `AuthServiceImpl#logout()`, а не политикой Spring Security.

**Рекомендация:**

Оставь `permitAll` только для `sign-up` и `sign-in`, а `sign-out` защити authenticated-доступом.

3. В `SecurityConfig#userDetailsService()` `UserDetailsService` объявлен анонимным внутренним классом прямо в конфиг-классе. 

Security-инфраструктура смешана с HTTP-правилами, сервис нельзя протестировать отдельно и переиспользовать вне `SecurityConfig`.

**Рекомендация:**

Вынеси загрузку пользователя в отдельный бин и подключи его в цепочку фильтров.

```java
@Component
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByNameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        return new CustomUserDetails(
                user.getId(),
                user.getName(),
                user.getPassword(),
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}

@Bean
public SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        UserDetailsService userDetailsService
) throws Exception {
    return http
            .userDetailsService(userDetailsService)
            // ...
            .build();
}
```

---

### пакет /service

1. В `UserServiceImpl#findByName()` внутри application-сервиса ты вручную лезешь в `SecurityContextHolder`, проверяешь `instanceof CustomUserDetails` и кидаешь 401. 

Это зона ответственности Spring Security: endpoint `/api/user/me` уже защищён `anyRequest().authenticated()`, а principal нужно извлекать в контроллере через `@AuthenticationPrincipal`. 
Сервис не должен знать про `Authentication`, `HttpServletRequest` и статус UNAUTHORIZED — он получает готовый `userId` или `username` и отдаёт данные.

**Рекомендация:**

Убери проверки principal из сервиса. Для `/user/me` достаточно username из `CustomUserDetails` — повторный запрос в БД не нужен.

```java
@GetMapping("/user/me")
public UserResponse getCurrentUser(@AuthenticationPrincipal CustomUserDetails user) {
    return userService.getCurrentUser(user.getUsername());
}
```

```java
public UserResponse getCurrentUser(String username) {
    return new UserResponse(username);
}
```

3. В `MinioService` метод `uploadFiles()` принимает `List<MultipartFile>` — тип из web-слоя. 

Application-сервис оказывается привязан к Spring MVC, его нельзя вызвать из другого входного адаптера без тяги servlet-зависимостей.

**Рекомендация:**

Принимай на границе сервиса нейтральную команду загрузки.

```java
public interface MinioService {
    List<ResourceDto> uploadFiles(String path, long userId, List<UploadFileCommand> files);
}

public record UploadFileCommand(String filename, long size, InputStream content) {}
```

```java
public List<ResourceDto> uploadFiles(String path, long userId, List<MultipartFile> files) {
    List<UploadFileCommand> commands = files.stream()
            .map(file -> new UploadFileCommand(
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getInputStream()
            ))
            .toList();
    return uploadFiles(path, userId, commands);
}
```

4. В `MinioServiceImpl#delete()`, `#downloadObject()` и `#move()` 

ты дублируешь проверки существования ресурса и выбор ветки файл/папка, хотя эта логика уже размазана по manager-ам. Фасад превратился в оркестратор с повторяющимися `if (pathUtils.isDirectory(...))`, и любое изменение правил проверки придётся копировать в несколько методов.

**Рекомендация:**

Вынеси разрешение пути и проверку существования в один компонент, а в фасаде оставь делегирование.

---

## РЕКОМЕНДАЦИИ

1. Построй storage-слой вокруг интерфейса `ObjectStorage`: `MinioUtils` переименуй в адаптер, бывшие `*Manager` перенеси в `storage.service` с `@Service`, application-слой зависит только от контракта хранилища.

2. Разложи инфраструктуру по пакетам `config`, `config.properties` и `util`: проперти, bootstrap MinIO и security-бины — в config, чистые функции путей и фабрики DTO — статикой в util.

3. Отдели transport-DTO от MinIO: `dto.request` / `dto.response` на уровне API, единый mapper с одним правилом сборки `path` и `name` для всех endpoint-ов.

4. Вынеси HTTP-контракт в API-интерфейсы с составными OpenAPI-аннотациями, а security-данные извлекай через `@AuthenticationPrincipal` в контроллерах — application-сервисы не должны трогать `SecurityContextHolder`.

5. Переведи login/register на `AuthenticationManager` и единый компонент установки security-контекста в сессию вместо ручной сборки в `AuthServiceImpl`.

6. Покрой `UserService` и файловые сценарии интеграционными тестами на Testcontainers — не ограничивайся REST-тестом регистрации.

7. Перейди на потоковую отдачу ZIP при скачивании папок через `StreamingResponseBody`, чтобы размер архива не упирался во временные файлы на диске.

---

## ИТОГ

Видно, что ты умеешь связать Spring, MinIO и фронтенд в end-to-end сценарий. Проект рабочий

Сильные стороны — декомпозиция файловых операций, `CustomUserDetails` с `id`, интерфейсы application-сервисов, батчевое удаление и аккуратная JPA-настройка.

Слабые стороны — нет абстракции над хранилищем, инфраструктура размазана по `minio.*` с неверными ролями пакетов, сборка `ResourceDto` расходится между endpoint-ами, security-логика попадает в сервисы, конфигурация и OpenAPI не отделены от реализации.

Двигайся в сторону `ObjectStorage` + `storage.service`, чистой раскладки `config` / `dto`, единого mapper для ответов и `@AuthenticationPrincipal` на границе REST. Это даст предсказуемые границы слоёв и упростит тестирование без переписывания всего при смене хранилища.
