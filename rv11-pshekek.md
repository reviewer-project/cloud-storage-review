[pshekek/Project6](https://github.com/pshekek/Project6)

## ХОРОШО

1. **Интерфейс `AuthenticationHelper` + `AuthenticationHelperImpl`** — получение текущего пользователя спрятано за интерфейсом. Это правильная инверсия зависимостей: `MinioService` зависит от абстракции, а не от конкретного класса. При необходимости заменить механизм извлечения идентификатора — ни один вызывающий код не изменится.

2. **`MyUserDetails` хранит `id` пользователя** — при каждом запросе `id` достаётся из уже загруженного `UserDetails`

3. **`MinioProperties` через `@ConfigurationProperties`** — все настройки MinIO собраны в одном типобезопасном классе. Это правильно: инфраструктурная конфигурация изолирована, нет `@Value`-полей, рассыпанных по разным бинам.

4. **`JsonAuthenticationFilter` с кастомными обработчиками** — фильтр самостоятельно обрабатывает JSON-тело и возвращает структурированный JSON-ответ при успехе/ошибке аутентификации. Логика разделена от контроллеров, что правильно.

5. **Автоматический вход после регистрации** — `UserService.create()` после сохранения пользователя немедленно аутентифицирует его через `AuthenticationManager` и создаёт сессию. ТЗ требует этого явно, и решение реализовано в правильном слое — сервисе.

6. **MapStruct для маппинга**

---

## ЗАМЕЧАНИЯ

### корневой пакет (rita)

1. Корневой пакет называется `rita` — нарушение Java-конвенции именования пакетов. По стандарту ([JLS §6.1](https://docs.oracle.com/javase/specs/jls/se17/html/jls-6.html#jls-6.1)) имя пакета должно начинаться с перевёрнутого доменного имени организации/автора: `com.example.cloudstorage`, `io.github.username.cloudstorage` и т.д. Проблема минимальная в учебном проекте, но важна для понимания конвенций.

**Рекомендация:** Переименовать корневой пакет например в `com.rita` или `me.rita`

2. Класс: `rita.Main`, метод: `main` — загрузка секретов из `.env`-файла через библиотеку `dotenv-java` прямо в точке входа приложения:
```java
Dotenv dotenv = Dotenv.configure()
        .directory("../")
        .ignoreIfMissing()
        .load();
dotenv.entries().forEach(entry ->
        System.setProperty(entry.getKey(), entry.getValue()));
```
Это антипаттерн сразу по нескольким причинам.

- `.env`-файл — инструмент локальной разработки, не production-деплоя. В production секреты (`DB_PASSWORD`, `MINIO_SECRET_KEY`) должны передаваться через реальные переменные окружения (Docker env, Kubernetes Secrets, Vault). Размещение этой логики в `Main.java` означает, что production-код знает о существовании `.env`-файла и будет его искать при каждом старте.

- `System.setProperty(...)` имеет **более высокий приоритет**, чем реальные переменные окружения (`System.getenv()`). В Spring `Environment` порядок приоритетов: системные свойства (`System.getProperty`) > переменные окружения (`System.getenv`) > `application.yaml`. Это значит, что если в реальном окружении (CI, production) уже выставлены настоящие `DB_URL` и т.д., они будут **перезаписаны** значениями из `.env`-файла, если тот случайно окажется рядом. Это нарушение принципа «переменные окружения управляют конфигурацией» (12-factor app).

- `directory("../")` — относительный путь от рабочей директории при запуске. В IDE, при запуске через `java -jar target/app.jar`, в Docker — рабочая директория разная, `.env` может не найтись. `.ignoreIfMissing()` подавляет ошибку, но тогда Spring не сможет разрешить `${DB_URL}` и упадёт с невнятным `Could not resolve placeholder`.

**Рекомендация:** Убрать Dotenv полностью, для локальной разработки — использовать application-dev.yaml с реальными значениями. В Docker Compose переменные уже передаются через environment секцию

---

### пакет /controller

1. Классы: `rita.controller.resource.ResourceController` и `rita.controller.resource.DirectoryController` — дублированная обработка исключений в каждом методе при наличии `GlobalExceptionHandler`

**Рекомендация:** В `GlobalExceptionHandler.java`` добавить обработчик MinioException

Хоть метод и написано как GET в ТЗ, но все же либо надо было задать в чат вопрос (если не нашел, сорри). Либо самому сделать PUT

2. Некорректный HTTP метод для перемещения — использование GET для операции перемещения нарушает REST конвенции, так как GET не должен изменять состояние ресурса.

**Рекомендация:**
```java
// Использовать PUT: семантика "заменить ресурс по новому адресу"
@PutMapping("/move")
public ResponseEntity<ResourceResponseDto> moveResource(
        @RequestParam("from") String from,
        @RequestParam("to") String to) {
    ResourceResponseDto dto = minioService.moveOrRenameResource(from, to);
    return ResponseEntity.ok(dto);
}
```

3. Класс: `rita.controller.resource.ResourceController`, метод: `downloadResource` — в контроллер утекла бизнес-логика сервисного слоя, и при этом она ещё и содержит баг.

Первое: контроллер инжектирует `NamingService` и напрямую вызывает `namingService.getNameFromPath(path)`, чтобы вычислить имя файла для заголовка `Content-Disposition`. Контроллер — это transport-слой: его задача принять HTTP-запрос и вернуть HTTP-ответ. Определение имени скачиваемого ресурса — это логика сервисного слоя. Если завтра появится второй транспорт (gRPC, WebSocket) или логика именования усложнится, контроллер придётся менять вместе с сервисом.

Второе: вычисленное имя безусловно получает суффикс `.zip`:
```java
String filename = namingService.getNameFromPath(path);
if (!filename.endsWith(".zip")) {
    filename += ".zip";
}
```
При этом `MinioService.downloadResource` для одиночного файла возвращает его «как есть» через `getObject` — без архивации. Пользователь запрашивает `report.pdf`, получает файл с заголовком `Content-Disposition: attachment; filename="report.pdf.zip"`, хотя содержимое — PDF. Современные браузеры (Chrome, Safari) определяют реальный тип по сигнатуре файла через content sniffing и сохраняют с корректным расширением. Однако неверный заголовок остаётся проблемой для других HTTP-клиентов — curl, wget, мобильных приложений, download-менеджеров: они доверяют Content-Disposition и сохранят report.pdf как report.pdf.zip. Помимо этого, это некорректный контракт API: сервер сообщает клиенту заведомо неверную информацию о возвращаемом ресурсе.

Корень обеих проблем один: решение о том, что возвращается и как это называется, принято в контроллере, хотя должно быть принято в сервисе. Сервис знает, архивировал он ответ или нет — и именно он должен сообщить контроллеру имя файла.

**Рекомендация:**
```java
// Ввести DTO результата скачивания, который несёт и данные, и имя файла:
public record DownloadResult(InputStreamResource resource, String filename) {}

// MinioService возвращает DownloadResult — сам определяет имя:
public DownloadResult downloadResource(String clientPath) {
    // ...
    if (isFolder) {
        // архивируем в zip
        String archiveName = folderName.replaceAll("/$", "") + ".zip";
        return new DownloadResult(zippedResource, archiveName);
    } else {
        String filename = namingService.getNameFromPath(clientPath);
        return new DownloadResult(rawResource, filename);
    }
}

// ResourceController — только transport-логика, никакого NamingService:
@GetMapping("/download")
public ResponseEntity<InputStreamResource> downloadResource(@RequestParam("path") String path) {
    DownloadResult result = minioService.downloadResource(path);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + result.filename() + "\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(result.resource());
}
```

4. Класс: `rita.controller.user.UserController`, метод: `getCurrentUser` — избыточная ручная проверка `if (principal == null)`. Эндпоинт `GET /api/user/me` не включён в `permitAll()` в `SecurityConfig`, следовательно, Spring Security не пропустит неаутентифицированный запрос к этому методу — `principal` никогда не будет `null`. Ручная проверка создаёт иллюзию безопасности, обеспечивается на уровне контроллера, хотя реально она обеспечивается фильтром. Это скрывает реальную модель безопасности от читателя кода.

**Рекомендация:** Убрать проверку, это делается на уровне фильтра

---

### пакет /dto

1. Пакет `rita.dto` — все DTO свалены в одну плоскую директорию без разделения на входящие и исходящие. В пакете одновременно лежат реквесты и респонсы. Когда таких классов становится больше, навигация затрудняется. Непонятно с первого взгляда, что является чем

**Рекомендация:** Разделить пакет /dto на подпакеты request и response

2. Класс: `rita.dto.DirectoryResponseDto` — дублирует `ResourceResponseDto` и нигде не используется. `DirectoryResponseDto` содержит поля `path`, `name`, `type` — подмножество `ResourceResponseDto` (`path`, `name`, `size`, `type`). Во всём проекте везде используется `ResourceResponseDto`, включая директории. `DirectoryResponseDto` объявлен, но ни в одном контроллере, сервисе или тесте не фигурирует. Мёртвый код засоряет проект.

**Рекомендация:** Удалить `DirectoryResponseDto.java` полностью, неиспользуемые классы и дто нужно сразу убирать из кода, а так же запускать Optimize import чтобы не было ощущений что оно где-то используется

3. Классы: `rita.dto.UserCredentialsDto` и `rita.dto.UserLoginRequest` — оба нигде не используются. Аутентификация происходит через `JsonAuthenticationFilter`, который читает JSON напрямую из `HttpServletRequest` в `Map<String, String>`. `UserLoginRequest` имеет аннотации `@NotBlank`, но валидация никогда не запускается. Мёртвые DTO создают иллюзию, что логин реализован через контроллер с валидацией DTO, хотя это не так.

**Рекомендация:** Удалить `UserCredentialsDto.java` и `UserLoginRequest.java`, тоже самое выше описал

---

### пакет /exeptions

1. Название пакета — опечатка: `exeptions` вместо `exceptions`

**Рекомендация:** Переименовать пакет

2. Класс: `rita.exeptions.GlobalExceptionHandler` — `MinioException` не обрабатывается. Из-за этого куча копипаста try-catch в контроллерах

**Рекомендация:** Добавить обработку `MinioException` в `GlobalExceptionHandler`

3. Класс: `rita.exeptions.GlobalExceptionHandler`, метод: `authenticationException` — используется `org.apache.tomcat.websocket.AuthenticationException`, а не `org.springframework.security.core.AuthenticationException`. Это внутренний класс Tomcat, предназначенный для WebSocket-аутентификации — он никогда не будет выброшен в контексте Spring Security REST-запросов. Обработчик фактически мёртв. При реальных ошибках аутентификации (например, неверный пароль в `AuthenticationManager`) этот хендлер не сработает.

Кроме того, метод устанавливает HTTP-статус 403 (`FORBIDDEN`) в ответе, но в теле пишет `errorCode: 401` — несоответствие HTTP-заголовка и тела.

**Рекомендация:**
```java
// Правильный импорт и согласованный статус:
import org.springframework.security.core.AuthenticationException;

@ExceptionHandler(AuthenticationException.class)
public ResponseEntity<BaseResponse<Object>> authenticationException(AuthenticationException e) {
    return ResponseEntity
        .status(HttpStatus.UNAUTHORIZED) // 401, согласовано с телом
        .body(BaseResponse.error(401, e.getMessage()));
}
```

4. Класс: `rita.exeptions.MinioException`, конструктор `MinioException(String message, Exception e)` — причина исключения не передаётся в `super`:
```java
public MinioException(String message, Exception e) {
    super(message); // e теряется!
}
```
Исходный стек вызовов (`e.getStackTrace()`) не сохраняется. При логировании или отладке будет виден только `MinioException` с сообщением, без оригинальной причины 

**Рекомендация:**
```java
public class MinioException extends RuntimeException {
    public MinioException(String message) {
        super(message);
    }
    public MinioException(String message, Throwable cause) {
        super(message, cause); // передаём cause в super — стек сохраняется
    }
}
```

---

### пакет /mapping

1. Класс: `rita.mapping.UserMapping`, метод: `toEntityCreate(UserRegisterRequest)` — объявлен, но не используется. В `UserService.create()` вручную создаётся `new User()` и заполняется через сеттеры, хотя маппер уже содержит метод преобразования `UserRegisterRequest -> User`. Смысл введения MapStruct теряется, если его методы не применяются.

**Рекомендация:** Либо использовать методы маппера, либо удалить их. (Лучше конечно использовать)

---

### пакет /minio

1. Класс: `rita.minio.MinioProperties`. `MinioProperties` аннотирован одновременно `@Configuration` и `@ConfigurationProperties`. Аннотация `@Configuration` делает класс источником Spring-бинов и компонентом конфигурационного контекста, хотя единственная его задача — хранить значения из `application.yaml`

**Рекомендация:** Убрать `@Configuration` и на `Main.java` накинуть `@EnableConfigurationProperties(MinioProperties.class)`

---

### пакет /repository

1. Класс: `rita.repository.User` — поле `id` объявлено как примитивный `long`. Для JPA-сущностей идентификатор должен быть объектным типом `Long`. Примитивный `long` всегда имеет значение по умолчанию `0`, поэтому невозможно отличить transient-сущность (несохранённую, `id = 0`) от сущности с реальным `id = 0`. JPA использует `null` в `Long` как признак transient-объекта при принятии решения об `INSERT` vs `UPDATE`. Кроме того, `MyUserDetails` принимает `user.getId()` и возвращает `Long` — здесь происходит неявный boxing, который ничего не сломает, но нарушает согласованность типов.

**Рекомендация:** Поменять примитив на объект

2. Класс: `rita.repository.User` — комбинация `@Builder` + `@AllArgsConstructor` + кастомный конструктор `User(String password, String username)` создаёт три способа конструирования объекта, ни один из которых не используется последовательно. `@Builder` предоставляет builder с полем `id` — JPA-идентификатором, который не должен задаваться вручную. Кастомный конструктор `User(String password, String username)` имеет нестандартный порядок параметров, что является потенциальным источником ошибок перестановки аргументов. При этом в `UserService.create()` используется ни builder, ни кастомный конструктор, а `new User()` + сеттеры

**Рекомендация:** Убрать `@Builder`, `@AllArgsConstructor` и кастомный конструктор. Оставить `@NoArgsConstructor` и создавать через маппер

3. Класс: `rita.repository.User` — отсутствие `equals`/`hashCode` на основе только `id`. Lombok без явного `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` не генерирует эти методы, значит используется реализация `Object`, которая сравнивает по ссылке. Для JPA-сущностей это приемлемо лишь при условии, что сущности не кладут в `HashSet`/`HashMap`. Если это произойдёт (например, в двунаправленных коллекциях JPA), поведение будет непредсказуемым. Правильный подход — `equals`/`hashCode` только по `id`, с явной проверкой `null`.

**Рекомендация:**
```java
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // поля...

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        User other = (User) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode(); // константный hashCode — безопасен для JPA
    }
}
```

4. Класс: `rita.repository.UserFolder` — мёртвая сущность. `UserFolder` содержит только `id` и ссылку на `User`. Она не несёт никакой бизнес-нагрузки: в `MinioService` корневая папка строится через `USER_PREFIX.formatted(userId)` — `UserFolder` не используется нигде в логике хранения, поиска или листинга. При этом она создаётся при каждой регистрации (`UserService.create`) и участвует в `CascadeType.ALL`, добавляя лишний `INSERT` + `JOIN` при загрузке пользователя. Если в будущем планируется хранить метаданные папки в БД — это оправдано, но тогда сущность должна нести реальное состояние.

**Рекомендация:**
```java
// Если UserFolder не несёт логики — удалить полностью:
// 1. Удалить UserFolder.java
// 2. Удалить поле folder из User
// 3. Удалить строки folder = new UserFolder(); folder.setUser(...); в UserService
// 4. Удалить таблицу user_folder из Liquibase-миграции

// Если хранение метаданных папки планируется — добавить смысловые поля:
@Entity
@Table(name = "user_folder")
public class UserFolder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String rootPath; // например "user-{id}-files/"
    private Long totalSize;
}
```

5. Класс: `rita.repository.UserRepository`, метод: `findByUsername`. Явный `@Query("select u from User u where u.username = :username")` избыточен. Spring Data JPA автоматически генерирует этот запрос по имени метода `findByUsername(String username)` через derived query. Наличие `@Query` с тривиальным JPQL создаёт шум и ложное впечатление, что запрос какой-то особенный

**Рекомендация:** Убрать аннотацию, JPA все сделает сам за тебя

---

### пакет /security

1. Класс: `rita.security.SecurityConfig`, метод: `securityFilterChain` — каждый публичный путь оборачивается в отдельный `new AntPathRequestMatcher(...)`:

Spring Security 5.x предоставляет перегруженный `requestMatchers(String... antPatterns)`, который принимает строки напрямую и создаёт `AntPathRequestMatcher` внутри автоматически. Текущий код создаёт 9 объектов вручную там, где достаточно одного вызова с varargs

**Рекомендация:**
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(
        "/",
        "/index.html",
        "/config.js",
        "/favicon.ico",
        "/assets/**",
        "/css/**",
        "/js/**",
        "/auth/sign-in",
        "/auth/sign-up",
        "/api/auth/sign-up",
        "/api/auth/sign-in",
        "/login",
        "/registration"
    ).permitAll()
    .anyRequest().authenticated()
)
```

2. Класс: `rita.security.SecurityConfig`, метод: `securityFilterChain`. `JsonAuthenticationFilter` создаётся через `new` внутри `@Bean`-метода. Объект, созданный через `new`, не управляется Spring-контейнером: на него не применяются BeanPostProcessor-ы, AOP-прокси, он не виден другим бинам и его нельзя переопределить в тестах через `@MockBean`/`@SpyBean`. Если понадобится внедрить в фильтр зависимость - это будет невозможно

**Рекомендация:**
```java
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper; // Spring-овский, со всеми настройками

    @Bean
    public JsonAuthenticationFilter jsonAuthenticationFilter(AuthenticationManager authManager) {
        return new JsonAuthenticationFilter(authManager, objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManager authManager,
            JsonAuthenticationFilter jsonAuthFilter) throws Exception {
        http
            // ...
            .addFilterAt(jsonAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

// JsonAuthenticationFilter принимает ObjectMapper через конструктор:
public class JsonAuthenticationFilter extends UsernamePasswordAuthenticationFilter {
    private final ObjectMapper objectMapper;

    public JsonAuthenticationFilter(AuthenticationManager authManager, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        setAuthenticationManager(authManager);
        // ...
    }
}
```

3. Класс: `rita.security.SecurityConfig`, метод: `securityFilterChain` — отсутствует `authenticationEntryPoint`. При попытке неаутентифицированного пользователя обратиться к защищённому эндпоинту Spring Security по умолчанию возвращает редирект на `/login` (302) или пустой 401 без тела. Оба варианта нарушают контракт REST API: клиент ожидает JSON с полем `message`. По ТЗ все ошибочные ответы должны быть в формате `{"message": "..."}`.

**Рекомендация:**
```java
http
    .exceptionHandling(e -> e
        .authenticationEntryPoint((request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\": \"Unauthorized\"}");
        })
    )
```

4. Класс: `rita.security.JsonAuthenticationFilter`, метод: `attemptAuthentication` — не валидирует входные данные. Если JSON-тело запроса пустое или не содержит полей `username`/`password`, `authRequest.get("username")` вернёт `null`. `UsernamePasswordAuthenticationToken` будет создан с `null`-значениями, и `AuthenticationManager` выбросит исключение, которое перехватится фильтром и вернёт 401 — формально правильно, но без внятного сообщения об ошибке. Кроме того, если `Content-Type` не `application/json` (например, при интеграционных тестах), `super.attemptAuthentication` попытается прочитать form-параметры, что нарушает ожидаемое поведение JSON-API.

**Рекомендация:**
```java
@Override
public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
    try {
        Map<String, String> authRequest =
            objectMapper.readValue(request.getInputStream(), Map.class);
        String username = authRequest.getOrDefault("username", "");
        String password = authRequest.getOrDefault("password", "");

        if (username.isBlank() || password.isBlank()) {
            throw new BadCredentialsException("Username and password must not be blank");
        }

        UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(username.trim(), password);
        return getAuthenticationManager().authenticate(authToken);

    } catch (IOException e) {
        throw new AuthenticationServiceException("Failed to parse authentication request", e);
    }
}
```

5. Класс: `rita.security.JsonAuthenticationFilter` — `ObjectMapper` создаётся через `new ObjectMapper()`. В Spring Boot `ObjectMapper` настраивается автоматически по свойствам из `application.yaml` (например, `fail-on-empty-beans: false`, формат дат, naming strategy). Создание нового экземпляра обходит эту конфигурацию: все кастомные настройки теряются. При сериализации ответов фильтр может вести себя иначе, чем остальное приложение.

**Рекомендация:** Инжектировать `ObjectMapper` через конструктор (см. замечание 1 по пакету /security).

---

### пакет /service

1. Класс: `rita.service.MinioService` — God Class (540 строк, 7 публичных методов). Один класс отвечает за загрузку файлов, скачивание, удаление, перемещение/переименование, просмотр содержимого папки, поиск и создание директорий. Нарушение SRP: изменение логики поиска затрагивает тот же класс, что и логика архивации при скачивании. Добавление новой операции (например, копирование) раздует класс ещё больше. Тестирование одной операции требует конфигурации всего класса с 5 зависимостями.

**Рекомендация:** Разделить по концепциям
```java
// 1. ResourceService — стандартные CRUD-операции над ресурсами.
//    Все методы работают с одними зависимостями, легко меняются вместе.
@Service
@RequiredArgsConstructor
public class ResourceService {
    public ResourceResponseDto getInfo(String clientPath) { ... }
    public List<ResourceResponseDto> showAllFilesFromFolder(String clientPath) { ... }
    public void deleteResource(String clientPath) { ... }
    public ResourceResponseDto moveOrRenameResource(String from, String to) { ... }
    public ResourceResponseDto createEmptyDirectory(String clientPath) { ... }
    public List<ResourceResponseDto> uploadFile(List<MultipartFile> files, String path) { ... }
}

// 2. DownloadService — скачивание заслуживает отдельного класса:
//    уникальная инфраструктурная логика (zip-архивация, streaming),
//    в будущем потребует StreamingResponseBody и отдельного тюнинга.
@Service
@RequiredArgsConstructor
public class DownloadService {
    public DownloadResult download(String clientPath) { ... }
}

// 3. SearchService — поиск отделяется по другой причине:
//    у него другая ось масштабирования. Сейчас — listObjects + фильтр по имени,
//    завтра — Elasticsearch или полнотекстовый индекс. Замена реализации
//    не должна трогать CRUD.
@Service
@RequiredArgsConstructor
public class SearchService {
    public List<ResourceResponseDto> search(String query) { ... }
}
```

2. Класс: `rita.service.MinioService` — магическая строка `"user-files"` во всех вызовах MinIO. `MinioProperties.bucket` уже читает bucket-name из `application.yaml`, но `MinioService` полностью его игнорирует и хардкодит `"user-files"` в десяти местах. Это нарушает принцип единого источника правды: при смене bucket-name в конфигурации сервис продолжит обращаться к `"user-files"`. 

**Рекомендация:** Брать из пропертис название бакита

3. Класс: `rita.service.MinioService` — сервис напрямую работает с `MinioClient` (SDK). Это привязывает бизнес-логику к конкретной реализации хранилища. Если понадобится переехать на Amazon S3, Yandex Object Storage или другое S3-совместимое хранилище — придётся переписывать сервис. Правильнее спрятать SDK за интерфейс и подменять только реализацию.

**Рекомендация:**
```java
// Объявить интерфейс хранилища — сервис зависит только от него:
public interface StorageRepository {
    StatObjectResponse stat(String path) throws Exception;
    InputStream getObject(String path) throws Exception;
    void putObject(String path, InputStream stream, long size, String contentType) throws Exception;
    void removeObject(String path) throws Exception;
    Iterable<Result<Item>> listObjects(String prefix, boolean recursive);
}

// MinIO-реализация:
@Component
@RequiredArgsConstructor
public class MinioStorageRepository implements StorageRepository {
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    public InputStream getObject(String path) throws Exception {
        return minioClient.getObject(
            GetObjectArgs.builder().bucket(minioProperties.getBucket()).object(path).build()
        );
    }
    // ... остальные методы
}

// Сервис инжектирует интерфейс — не знает, MinIO это или Amazon S3:
@Service
@RequiredArgsConstructor
public class ResourceService {
    private final StorageRepository storageRepository;
    // ...
}
```

4. Класс: `rita.service.MinioService` — инжектируется `AuthenticationHelperImpl` вместо интерфейса `AuthenticationHelper`

**Рекомендация:** инжектировать интерфейс, не реализацию. В тестах — мокировать интерфейс

5. Класс: `rita.service.MinioService`, метод: `downloadResource` — zip-архивация папки выполняется в памяти через `ByteArrayOutputStream`. Весь архив целиком собирается в оперативной памяти, и только потом оборачивается в `InputStreamResource`. При скачивании папки с большим количеством файлов это приведёт к `OutOfMemoryError`. Правильный подход — `StreamingResponseBody`: данные передаются клиенту по мере генерации, без буферизации всего архива.

**Рекомендация:**
```java
// Возвращать StreamingResponseBody напрямую из контроллера:
@GetMapping("/download")
public ResponseEntity<StreamingResponseBody> downloadResource(@RequestParam("path") String path) {
    StreamingResponseBody body = outputStream -> {
        minioService.streamDownload(path, outputStream);
    };
    String filename = resolveFilename(path);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(body);
}

// MinioService
public void streamDownload(String clientPath, OutputStream outputStream) {
    Long userId = authenticationHelper.getCurrentUserId();
    String path = buildFullPath(clientPath, userId);

    if (path.endsWith("/")) {
        try (ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
            for (Result<Item> result : minioRepository.listObjects(path, true)) {
                Item item = result.get();
                if (item.objectName().equals(path)) continue;
                zipOut.putNextEntry(new ZipEntry(item.objectName().substring(path.length())));
                try (InputStream is = minioRepository.getObject(item.objectName())) {
                    is.transferTo(zipOut);
                }
                zipOut.closeEntry();
            }
        } catch (Exception e) {
            throw new MinioException("Ошибка архивации", e);
        }
    } else {
        try (InputStream is = minioRepository.getObject(path)) {
            is.transferTo(outputStream);
        } catch (Exception e) {
            throw new MinioException("Ошибка скачивания", e);
        }
    }
}
```

6. Класс: `rita.service.MinioService`, метод: `moveOrRenameResource` — `listObjects` с prefix небезопасен для одиночных файлов. Для файла `user-1-files/docs/report.pdf` prefix `user-1-files/docs/report.pdf` совпадёт со всеми объектами, чьи имена начинаются с этой строки: `user-1-files/docs/report.pdf`, `user-1-files/docs/report.pdf.bak`, `user-1-files/docs/report.pdf-old`. Все они будут скопированы и удалены в рамках одного вызова `moveOrRenameResource`. Пользователь переименует один файл, а потеряет несколько. Это silent data loss.

**Рекомендация:**
```java
public ResourceResponseDto moveOrRenameResource(String fromClient, String toClient) {
    Long userId = authenticationHelper.getCurrentUserId();
    String from = buildFullPath(fromClient, userId);
    String to = buildFullPath(toClient, userId);

    validateName(namingService.getNameFromPath(toClient));

    if (!isNotExist(to)) {
        throw new EntityAlreadyExistsException("Ресурс с таким именем уже существует");
    }

    boolean isDirectory = from.endsWith("/");

    if (isDirectory) {
        // Для папки prefix безопасен — папка заканчивается на "/",
        // и совпадений с другими объектами быть не может
        moveDirectory(from, to);
    } else {
        // Для файла — прямое copy+delete без listObjects
        minioRepository.copyObject(from, to);
        minioRepository.removeObject(from);
    }

    return buildResponseDto(to, isDirectory);
}
```

7. Класс: `rita.service.MinioService`, метод: `isFolderExists` — любое исключение, не связанное с бизнес-логикой, молча проглатывается:
```java
} catch (Exception e) {
    return false; // таймаут, IOException, ошибка сети = "папки не существует"
}
```

**Рекомендация:** Пробрасывать инфраструктурные ошибки, а не глотать их

8. Класс: `rita.service.MinioService` — уязвимость Path Traversal: отсутствует проверка `..` в полном пути запроса.

В коде есть `validateName()`, которая запрещает `..`, но она вызывается только для **имени** (через `namingService.getNameFromPath()`), а не для полного пути `clientPath`. При этом `buildFullPath()` просто стрипает ведущий `/` и добавляет user-prefix — никакой проверки на `..` нет:

```java
private String buildFullPath(String clientPath, Long userId) {
    if (clientPath.startsWith("/")) {
        clientPath = clientPath.substring(1);
    }
    return prefix(userId) + clientPath; // "user-1-files/" + clientPath
}
```

**Три уровня проблемы:**

**Уровень 1 — методы без какой-либо валидации пути:** `getInfo`, `deleteResource`, `downloadResource`, `showAllFilesFromFolder` — `validateName()` вообще не вызывается. Эксплойт напрямую:

```bash
# Прочитать файл другого пользователя
GET /api/resource/download?path=../../user-2-files/private.pdf

# Итоговый MinIO-ключ: user-1-files/../../user-2-files/private.pdf
# MinIO/OkHttp нормализует -> user-2-files/private.pdf

# Удалить файл другого пользователя
DELETE /api/resource?path=../../user-2-files/important.pdf
```

**Уровень 2 — методы с валидацией имени, но не пути:** `uploadFile`, `createEmptyDirectory`, `moveOrRenameResource` — `validateName()` вызывается, но только для конечного имени через `getNameFromPath()`. Директория в пути не проверяется:

```bash
# Загрузить файл в пространство другого пользователя:
# path=../../user-2-files/ + file=evil.txt
POST /api/resource?path=../../user-2-files/
# файл: evil.txt

# validateName("evil.txt") -> проходит! (нет ".." в имени файла)
# buildFullPath("../../user-2-files/evil.txt", 1)
# итог: user-1-files/../../user-2-files/evil.txt -> нормализуется в user-2-files/evil.txt
```

**Уровень 3 — обход через `getNameFromPath`:** даже там, где `validateName` вызывается на имя, она получает только последний сегмент пути:
```java
validateName(namingService.getNameFromPath("../../user-2-files/evil.txt"))
// getNameFromPath возвращает "evil.txt" — проверка пройдена
```

**Рекомендация:**
```java
// В buildFullPath — нормализовать путь и проверить, что он остаётся внутри user-prefix:
private String buildFullPath(String clientPath, Long userId) {
    String userPrefix = prefix(userId); // "user-1-files/"

    // Убрать ведущий слэш
    if (clientPath.startsWith("/")) {
        clientPath = clientPath.substring(1);
    }

    // Запретить ".." в любой части пути — до построения финального ключа
    if (clientPath.contains("..")) {
        throw new ValidationException("Недопустимо использовать .. в пути");
    }

    String fullPath = userPrefix + clientPath;

    // Дополнительная защита: убедиться, что итоговый путь начинается с user-prefix
    if (!fullPath.startsWith(userPrefix)) {
        throw new ValidationException("Недопустимый путь");
    }

    return fullPath;
}

// validateName — оставить как есть, это вторая линия обороны для имён.
// Но основная защита должна быть в buildFullPath, через который проходят ВСЕ пути.
```

9. Класс: `rita.service.NamingService` — аннотация `@Service` и слово `Service` на утилитном классе без зависимостей и состояния. `NamingService` содержит два чистых метода преобразования строк без каких-либо инжектируемых зависимостей и без состояния. `@Service` сигнализирует Spring, что класс несёт бизнес-логику с потенциальными транзакциями и инфраструктурными зависимостями. Здесь это вводит в заблуждение и создаёт лишний singleton-бин в контексте.

**Рекомендация:** Сделать утилитным классом со статическими методами и пренести в /utils

10. Класс: `rita.service.NamingService`, метод: `getParentFolder` — `.skip(1)` убирает первый сегмент пути. Для пути `user-1-files/docs/report.pdf` элементы после `split("/")` = `["user-1-files", "docs", "report.pdf"]`. После `.skip(1)` остаётся `["docs", "report.pdf"]`, и `limit(length - 1)` даёт `["docs"]` → `"docs/"`. Исходный user-prefix (`user-1-files/`) теряется. Этот побочный эффект нигде не задокументирован, а поведение зависит от структуры пути. При рефакторинге структуры хранения (например, добавлении ещё одного уровня) логика сломается неочевидно.

**Рекомендация:** Добавить javadoc где явно описана работа: убираем user-prefix из пути для клиентского ответа. Или сделать два метода с ясными именами:
```java
public static String getParentFolder(String fullPath) {
    int lastSlash = fullPath.lastIndexOf('/', fullPath.length() - 2);
    if (lastSlash < 0) return "/";
    return fullPath.substring(0, lastSlash + 1);
}

public static String stripUserPrefix(String fullPath, Long userId) {
    String prefix = "user-" + userId + "-files/";
    return fullPath.startsWith(prefix) ? fullPath.substring(prefix.length()) : fullPath;
}
```

11. Класс: `rita.service.UserService`, метод: `create` — ручная валидация длины username дублирует Bean Validation:
```java
if (request.getUsername().length() <= 5) {
    throw new ValidationException("Юзернейм должен быть не меньше 5 символов");
}
```

**Рекомендация:** Добавить `@Size` на поле в dto и убрать ручную проверку

12. Класс: `rita.service.UserService`, метод: `create` — сессия создаётся без явного сохранения `SecurityContext`. После строк:
```java
SecurityContextHolder.getContext().setAuthentication(authentication);
httpRequest.getSession(true);
```
`SecurityContextPersistenceFilter` должен сохранить контекст в сессию в конце обработки запроса — это работает в Spring Security 5.x. Однако подход неявный и хрупкий: в Spring Security 6 `SecurityContextPersistenceFilter` заменён на `SecurityContextHolderFilter`, который **не сохраняет** контекст автоматически. При обновлении Spring Boot до 3.x этот код молча перестанет создавать аутентифицированную сессию. Правильный способ — явный вызов `HttpSessionSecurityContextRepository.saveContext()`.

**Рекомендация:**
```java
// SecurityConfig — зарегистрировать репозиторий как бин:
@Bean
public SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
}

// UserService — явно сохранять контекст:
@Service
@RequiredArgsConstructor
public class UserService {
    private final SecurityContextRepository securityContextRepository;
    // ...

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public UserDto create(UserRegisterRequest request,
                          HttpServletRequest httpRequest,
                          HttpServletResponse httpResponse) {
        // ... регистрация ...

        Authentication authentication = authManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // Явно сохраняем в сессию — работает и в Spring Security 5, и в 6
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return userMapping.toDto(user);
    }
}
```

13. Класс: `rita.service.UserService`, метод: `create` — аннотация `@Transactional(isolation = Isolation.REPEATABLE_READ)`.

**Вопрос:** Зачем здесь выбран уровень изоляции `REPEATABLE_READ`? От какого именно сценария он должен защищать в этом методе? Подумай: что делает `REPEATABLE_READ` по определению, и применимо ли это здесь. Есть ли в коде другой механизм, который уже решает эту задачу надёжнее?

---

### пакет /test

1. Класс: `rita.it.ItRegistrationControllerTest` и `rita.service.MinioServiceTest` — большие блоки закомментированного кода. В `ItRegistrationControllerTest` закомментировано ~100 строк, в `MinioServiceTest` — ~170 строк. История изменений — задача системы контроля версий, не комментариев. Закомментированный код создаёт шум при чтении, засоряет diff в git и вводит в заблуждение: непонятно, это рабочий код или намеренно выключенный.

**Рекомендация:** Удалить все закомментированные блоки кода + чистка импортов. Если тесты не работают — исправить и раскомментировать, или удалить. Если по какой-то причине тест сломан, но нужен, лучше повесить аннотацию `@Disabled` и написать почему выключено. При прогоне тестов оно хотябы будет светится как выключенный с комментарием причины

2. Классы: `rita.it.ItDirectoryRestControllerTest` и `rita.service.MinioServiceTest` — `@MockBean`/`@Mock` применяется к конкретному классу `AuthenticationHelperImpl` вместо интерфейса `AuthenticationHelper` в тестах

**Рекомендация:** Мокать интерфейс

3. Класс: `rita.it.AbstractControllerBaseTest` — жёсткая привязка к Windows-специфичным настройкам Docker:
```java
System.setProperty("DOCKER_HOST", "npipe:////./pipe/docker_engine");
System.setProperty("TESTCONTAINERS_RYUK_DISABLED", "true");
```
Эти свойства прописаны в статическом инициализаторе базового класса и применяются ко всем интеграционным тестам. На Linux/macOS `npipe` путь невалиден. Тесты не запустятся в CI/CD-среде (GitHub Actions, GitLab CI, Jenkins). `TESTCONTAINERS_RYUK_DISABLED=true` отключает механизм автоматической очистки контейнеров — при аварийном завершении тестов контейнеры останутся запущенными.

**Рекомендация:** Удалить статический инициализатор из AbstractControllerBaseTest. Настройки Docker для Windows вынести в testcontainers.properties

4. Критические сценарии не покрыты тестами: `uploadFile`, `moveOrRenameResource`, `searchResource`, `createEmptyDirectory` — закомментированы в `MinioServiceTest`, тестовые методы пустые. Именно эти операции содержат самую сложную логику (unsafe prefix в move, архивация в память при download). Тестирование только `getInfo` и `deleteResource` оставляет основную часть функциональности без верификации.

**Рекомендация:** Раскомментировать и исправить тест для download zip

---

## РЕКОМЕНДАЦИИ

1. **Ввести Repository-слой для MinIO.** `MinioService` напрямую использует `MinioClient` (SDK). Необходимо выделить `MinioRepository` (или `MinioStorageClient`) — адаптер, который инкапсулирует все вызовы MinIO SDK: работу с bucket-name, конвертацию исключений в доменные, retry-логику. Сервисный слой должен зависеть от этого адаптера, а не от SDK напрямую.

2. **Разбить `MinioService` по принципу SRP.** 540-строчный класс с 7+ публичными методами — типичный God Class. Разделить на три сервиса по концептуальным осям: `ResourceService` (CRUD), `DownloadService` (streaming/zip), `SearchService` (поиск с возможной заменой реализации). Это упростит тестирование, навигацию и независимое изменение каждой части.

3. **Перенести обработку всех исключений в `GlobalExceptionHandler`.** Сейчас `MinioException` обрабатывается в каждом контроллерном методе вручную, что приводит к дублированию и неконсистентным форматам ошибок. Добавление одного обработчика в `GlobalExceptionHandler` позволит убрать все `try-catch` из контроллеров и получить единообразные ответы.

4. **Устранить мёртвые артефакты.** `DirectoryResponseDto`, `UserCredentialsDto`, `UserLoginRequest`, метод `UserMapping.toEntityCreate` (неиспользуемый), сущность `UserFolder` (без бизнес-нагрузки), массивы закомментированного кода в тестах — всё это увеличивает когнитивную нагрузку и замедляет навигацию по проекту. Неиспользуемые классы стоит удалить или реализовать по назначению.

5. **Использовать `StreamingResponseBody` для скачивания папок.** Текущая реализация буферизует весь zip-архив в `ByteArrayOutputStream` в памяти. При больших папках это ведёт к `OutOfMemoryError`. `StreamingResponseBody` позволяет писать в `OutputStream` клиента напрямую, без буферизации.

6. **Довести тестовое покрытие до требований ТЗ.** Наиболее рискованные методы (`moveOrRenameResource`, `uploadFile`, `downloadResource` для папок) закомментированы или пустые. Интеграционные тесты должны проверять: конфликт при загрузке существующего файла, перемещение файла и папки, поиск по имени, скачивание папки как zip.

7. **Убрать неиспользуемые импорты по всему проекту.** В нескольких файлах присутствуют импорты, которые не используются: например, `import io.minio.StatObjectArgs` и `import io.minio.errors.ErrorResponseException` в `ResourceController`, `import java.util.Collections` в `DirectoryController`. Это следы удалённого кода, которые не были вычищены. В IntelliJ IDEA неиспользуемые импорты подсвечиваются серым цветом. Для массовой очистки: `Code → Optimize Imports` (или `Ctrl+Alt+O` / `⌃⌥O`) — можно применить сразу ко всему проекту через `Analyze → Run Inspection by Name → Unused imports`.

8. **Избавиться от платформо-специфичных настроек в базовом тестовом классе.** Жёстко прописанный `DOCKER_HOST=npipe://...` блокирует запуск тестов в CI/CD и на macOS/Linux. Эти настройки должны быть локальными (в `testcontainers.properties` вне репозитория), а не частью кода.

9. **Обновить Spring Boot.** Проект использует Spring Boot `2.7.0` (май 2022 года) — эта версия давно вышла из официальной поддержки (End of Life — ноябрь 2023). На дворе 2026 год, актуальна Spring Boot `3.x`/`4.x`. Использование EOL-версии означает отсутствие патчей безопасности, несовместимость с современными библиотеками и Java 21+, а также накапливающийся технический долг при будущей миграции (Jakarta EE namespace, `javax.*` → `jakarta.*`, Spring Security 6 и т.д.). Для нового проекта стартовать на 2.7 в 2026 году — заведомо плохой задел.

---

## ИТОГ

Проект реализован на хорошем уровне для учебной работы, весь заявленный функционал присутствует и работает. Ряд решений демонстрирует понимание Spring Boot: `@ConfigurationProperties` для MinIO, `MyUserDetails` с хранением `id`, интерфейс `AuthenticationHelper`, `@RestControllerAdvice`, MapStruct, Testcontainers с реальными контейнерами. Это выше среднего для новичков.

**Ключевые проблемы** — архитектурные:

- `MinioService` взял на себя слишком много (540 строк, God Class) и напрямую работает с `MinioClient`, нарушая изоляцию слоёв;
- bucket-name захардкожен как `"user-files"` в десяти местах, хотя `MinioProperties.bucket` уже есть;
- ошибочный импорт `org.apache.tomcat.websocket.AuthenticationException` в `GlobalExceptionHandler` — обработчик аутентификационных ошибок мёртв;
- `MinioException` не передаёт `cause` в `super` — стек трассировки теряется при каждом оборачивании;
- unsafe prefix-поиск в `moveOrRenameResource` для одиночных файлов может привести к silent data loss;
- zip-архивация в памяти — потенциальный `OutOfMemoryError`.

**Что изучить для роста:**

- **StreamingResponseBody**: потоковая передача данных без буферизации в памяти.
- **Layered Architecture / Hexagonal Architecture**: как правильно выстроить слои `controller → service → repository → infrastructure`, почему бизнес-слой не должен знать о MinIO SDK.
- **SRP на практике**: как определить, когда класс стал слишком большим и где провести границу ответственности.
- **Spring Security internals**: `SecurityContextPersistenceFilter` vs `SecurityContextHolderFilter` (изменение в Spring Security 6), `HttpSessionSecurityContextRepository.saveContext()`.
- **HTTP semantics**: идемпотентность и безопасность методов GET/PUT/POST/DELETE; почему GET не должен изменять состояние сервера.
