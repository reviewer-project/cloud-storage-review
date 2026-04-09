[Nataliya1111/CloudFileStorage](https://github.com/Nataliya1111/CloudFileStorage)

## ХОРОШО

1. **`AuthenticatedUser` содержит `id` пользователя, исключая лишний DB-запрос** 

2. **`@ValidMovePath` — кросс-field валидация через Bean Validation**

3. **`StreamingResponseBody` для скачивания файлов и директорий** — данные пишутся прямо в `OutputStream` HTTP-ответа через `inputStream.transferTo(outputStream)`. Файл не загружается целиком в heap. Аналогично для ZIP-архивов: `ZipStreamWriter` обёртывает `ZipOutputStream` вокруг того же выходного потока. При скачивании директорий в 500 МБ память не растёт пропорционально размеру — это корректное решение для файлового сервиса.

4. **`ZipStreamWriter implements AutoCloseable`** — вспомогательный класс для ZIP корректно управляет ресурсом через `AutoCloseable`: в `DownloadService` он используется в `try-with-resources`, что гарантирует закрытие `ZipOutputStream` и финализацию архива даже при ошибке.

5. **Кастомные мета-аннотации OpenAPI** — `@CommonStorageErrorResponses`, `@BadRequestAndUnauthorizedResponses`, `@InternalServerErrorResponse` агрегируют повторяющиеся `@ApiResponses` в одну аннотацию. Дублирование документации устранено, все контроллеры аннотированы единообразно.

6. **Создание корневой папки метаданных при регистрации, а не при логине** — `RegistrationService.registerUser()` вызывает `resourceMetadataService.createRootDirectoryMetadata(savedUser.getId())` сразу после сохранения пользователя. Корневая папка создаётся один раз на всю жизнь аккаунта, а не при каждом входе. Это семантически корректно и не создаёт лишних операций при аутентификации.

---

## ЗАМЕЧАНИЯ

### пакет /config

1. Класс: `MinioProperties`.

`@ConfigurationPropertiesScan` расположена непосредственно на классе `MinioProperties`. Это неверное применение аннотации: `@ConfigurationPropertiesScan` — это директива сканирования, которая должна размещаться на `@SpringBootApplication` или на `@Configuration`-классе. 

**Рекомендация:** Убрать @ConfigurationPropertiesScan с MinioProperties, и повесить на главный класс. Либо явно зарегистрировать через `@EnableConfigurationProperties` на `@Configuration`-классе `MinioConfig`

2. Класс: `MinioProperties`.

На классе стоит `@Data`, который генерирует `toString()`, включающий все поля — в том числе `secretKey`. Хотя значение берётся из переменных окружения, хеш или само значение секрета MinIO попадёт в любой лог, куда попадёт объект `MinioProperties` (например, `log.debug("Loaded config: {}", properties)`). 
Для `@ConfigurationProperties`-класса нет необходимости в `equals`, `hashCode` и `toString` по всем полям. Достаточно геттеров (и сеттеров для property binding через setter-injection). 
Альтернатива — использовать `record`-based `@ConfigurationProperties`, тогда binding идёт через конструктор, сеттеры не нужны совсем.

**Рекомендация:** Убрать `@Data`, использовать `@Getter` `@Setter` либо использовать рекорды

3. Класс: `MinioProperties`, поле `userRootDirectory`. Файл: `application.yml`.

Свойство `minio.user-root-directory: user-%d-files/` объявлено в `application.yml` и связано с полем `MinioProperties.userRootDirectory`, однако нигде в коде не вызывается `properties.getUserRootDirectory()`. 
В `MinioService` файлы хранятся по UUID без какого-либо пути-префикса. Это мёртвая конфигурация: она создаёт иллюзию, что где-то есть логика формирования пользовательских директорий в MinIO, которой на самом деле нет

**Рекомендация:** По ТЗ сказано использовать пути вида `user-%d-files/`, но ты хранишь по uuid юзера (это не плохо, но не совсем по тз) надо либо как в тз, либо убрать эту конфигурацию вовсе

---

### пакет /controller

1. Класс: `AuthDocumentationController`.

`AuthDocumentationController` — фиктивный контроллер с пустыми телами методов, существующий исключительно для генерации Swagger-документации по эндпоинтам `/api/auth/sign-in` и `/api/auth/sign-out`, которые обрабатываются Spring Security фильтрами, а не `DispatcherServlet`. 
Проблема в том, что `@PostMapping("/sign-in")` в этом классе регистрирует маппинг в `DispatcherServlet` — он никогда не вызывается (фильтр перехватывает запрос раньше), но Spring MVC видит его как существующий эндпоинт. Это phantom-контроллер: разработчик, читающий код, предполагает, что метод обрабатывает запрос, хотя на самом деле он мёртв. 
Более правильный подход — добавить эти эндпоинты в OpenAPI через `OpenApiCustomizer`, не создавая лишнего контроллера.

**Рекомендация:** Удалить AuthDocumentationController, добавить OpenApiCustomiser
```java
@Bean
public OpenApiCustomizer authEndpointsCustomizer() {
    return openApi -> {
        PathItem signIn = new PathItem().post(new Operation()
                .addTagsItem("Authentication")
                .summary("User login")
                .description("Authenticates the user and creates a session")
                .requestBody(new RequestBody().content(new Content().addMediaType(
                        "application/json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/UserAuthenticationRequestDto"))
                )))
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("Login successful"))
                        .addApiResponse("400", new ApiResponse().description("Validation failed"))
                        .addApiResponse("401", new ApiResponse().description("Invalid credentials"))
                ));
        openApi.getPaths().addPathItem("/api/auth/sign-in", signIn);
        // аналогично для sign-out
    };
}
```

2. Класс: `UserController`, метод: `getUser()`, параметр `@AuthenticationPrincipal`.

Метод принимает `@AuthenticationPrincipal UserDetails userDetails`, тогда как во всех остальных контроллерах (`ResourceController`, `DirectoryController`) используется конкретный тип `@AuthenticationPrincipal AuthenticatedUser user`. 
Принципиальный объект в контексте безопасности этого приложения — всегда `AuthenticatedUser`. Принятие базового интерфейса `UserDetails` ослабляет типизацию: если в будущем добавятся другие реализации `UserDetails` (например, LDAP), код либо сломается с `ClassCastException`, либо потребует instanceof-проверки. Использование `UserDetails` здесь — исключение без причины, нарушающее единообразие с остальными контроллерами.

**Рекомендация:**
```java
@GetMapping("/me")
public UsernameResponseDto getUser(@AuthenticationPrincipal AuthenticatedUser user) {
    return new UsernameResponseDto(user.getUsername());
}
```

3. Классы: `ResourceController`, `DirectoryController`, `AuthController`, `UserController`.

Swagger-аннотации (`@Operation`, `@ApiResponses`, `@ApiResponse`, `@Parameter`) размещены непосредственно в классах контроллеров вперемешку с бизнес-логикой маппинга. 
В результате, например, `ResourceController` содержит несколько десятков строк Swagger-разметки на каждый метод: сам метод занимает 3–5 строк, а аннотации над ним — 15–20. Читаемость бизнес-кода резко падает, навигация по классу затруднена. 
Кроме того, при появлении нескольких версий API (`/api/v1/resource`, `/api/v2/resource`) контроллер либо раздувается conditional-логикой, либо приходится дублировать весь класс целиком. 
Стандартная практика — вынести контракт (Swagger + сигнатуры методов) в отдельный интерфейс, а реализацию оставить чистой.

**Рекомендация:** Сделать всем контроллерам интерфейсы, например `ResourceApi` в котором все описание сваггера. Сама реализация занимаешь меньше места + возможность для дальнейшего версионирования

---

### пакет /dto

1. Классы: `UserAuthenticationRequestDto` и `UserRegistrationRequestDto`.

Оба класса — записи с идентичными полями `username` и `password` и полностью одинаковыми аннотациями валидации на обоих полях. Это 100% дублирование кода и, главное, дублирование бизнес-правил: если требования к формату пароля изменятся (например, добавится минимальное количество цифр), нужно обновить оба класса. 
Разделение по семантике верное (запрос регистрации и запрос входа — разные вещи), но валидационные ограничения должны быть определены один раз.

**Рекомендация:** Как идея, вынести все в константы и в случае чего менять в 1 месте. Либо вообще завести составную аннотацию (мне не очень нравится эта идея из-за кастомных аннотаций)
```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@NotBlank(message = "Username must not be empty")
@Size(min = 5, max = 20, message = "Username must be {min} to {max} characters long")
@Pattern(regexp = "^[a-zA-Z0-9]+[a-zA-Z_0-9]*[a-zA-Z0-9]+$", ...)
public @interface ValidUsername {}

// Использование:
public record UserRegistrationRequestDto(@ValidUsername String username, @ValidPassword String password) {}
public record UserAuthenticationRequestDto(@ValidUsername String username, @ValidPassword String password) {}
```

2. Класс: `PathRequestDto`, поле `path`.

На поле `path` есть `@Pattern`, но нет `@NotNull`. По спецификации Bean Validation, `@Pattern` пропускает `null`-значения: если клиент отправит запрос без параметра `path`, значение будет `null`, `@Pattern` не сработает, и `path` дойдёт до `FileSystemService.listDirectoryContents()` как `null`. В текущей реализации `PathUtil.formatPath(null, ...)` обрабатывает null, возвращая `"/"`, — то есть листинг корневой директории происходит незаметно. Это поведение по умолчанию ("если path не передан — показать корень") может быть намеренным, но тогда оно должно быть явным: либо через `@NotNull` + явная документация опциональности, либо через `@RequestParam(required = false)` без DTO.

**Рекомендация:** Либо явно разрешить null и задокументировать это поведение, либо запретить такое поведение

3. Класс: `ResourceResponseDto`, конструкторы.

В `ResourceResponseDto` объявлены два вспомогательных конструктора: `(String path, String name, Long size)` для файлов и `(String path, String name)` для директорий. Оба — мёртвый код: `ResourceMapper` использует MapStruct, который вызывает канонический конструктор record через генерируемый код, никогда не вызывая вспомогательные. Мёртвые конструкторы — это ложные точки входа: разработчик предполагает, что они где-то используются, ищет вызовы и не находит. При изменении структуры record их нужно поддерживать синхронно с основным.

**Рекомендация:** Удалить неиспользуемый код

4. Пакет: `dto`.

DTO-классы разложены по подпакетам по предметной области: `dto/resource` и `dto/user`. Внутри каждого подпакета перемешаны request-объекты и response-объекты. При навигации по проекту разработчик вынужден открывать подпакет и просматривать все файлы, чтобы понять, что относится к входящим данным, а что — к исходящим. 
Это особенно ощутимо при росте проекта: когда подпакет `dto/resource` содержит 8–10 классов, поиск нужного превращается в перебор. Более читаемая структура — двухуровневая иерархия: сначала направление (`request`/`response`), затем предметная область (`user`/`resource`). Тогда при вопросе «какие ответы возвращает API?» достаточно открыть `dto/response` и сразу увидеть все response-классы.

**Рекомендация:** Сделать рефакторинг пакетов, поделиь на response и request на уровне дто

---

### пакет /exception

1. Класс: `GlobalExceptionHandler`, метод: `handleException(PartialUploadException)`.

`PartialUploadException` возвращает `409 Conflict`. HTTP 409 означает конфликт состояния ресурса: ресурс уже существует в несовместимом с запросом состоянии. Частичная загрузка — это смешанный результат: часть файлов успешно загружена, часть отклонена из-за дублирования. Это не конфликт — загрузка частично состоялась. Корректный статус для частичного успеха — `207 Multi-Status` (WebDAV, но широко применим), либо `200 OK` с подробным телом ответа, описывающим что загружено, а что нет. 
Возврат 409 вводит клиента в заблуждение: он решит, что ни один файл не был загружен.

**Рекомендация:** Изменить на 200 с подробным описанием или 207 использловать

2. Класс: `GlobalExceptionHandler`, метод: `handleException(MultipartException)`.

Внутри `@ExceptionHandler(MultipartException.class)` метод заканчивается `throw ex` для случаев, когда `MultipartException` не является `FileCountLimitExceededException`. 
Повторный выброс исключения внутри `@ExceptionHandler` — нестандартное поведение: исключение проходит через Spring MVC ещё раз и попадает в следующий подходящий обработчик — в данном случае в `handleException(Exception, HttpServletRequest)`, возвращающий 500. 
Это работает, но создаёт неочевидный поток: читатель видит `throw ex` в методе, помеченном как `@ExceptionHandler`, и не сразу понимает, что происходит дальше. Лучше явно вернуть нужный ответ прямо здесь.

**Рекомендация:** Сделать явный fallback вместо re-throw

---

### пакет /mapper

1. Класс: `ResourceMapper`, маппинг поля `path` в методе `resourceToResourceDto()`.

Для поля `path` используется `expression = "java(...)"`. Такой вариант рабочий, но здесь он ухудшает читаемость и поддержку маппера: логика преобразования скрыта внутри строкового выражения в аннотации, её сложнее переиспользовать и отдельно тестировать. MapStruct не проверяет корректность `expression` на этапе генерации маппинга; ошибки всплывут уже при компиляции сгенерированного класса.

В этом случае лучше вынести преобразование в обычный mapping-метод маппера и сослаться на него через `qualifiedByName`. Так логика остаётся типизированной, лучше читается, проще рефакторится и не требует `imports = PathUtil.class`.

**Рекомендация:**
```java
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ResourceMapper {

    @Mapping(target = "path", source = "resource", qualifiedByName = "toParentPath")
    @Mapping(target = "name", source = "resource", qualifiedByName = "toDisplayName")
    @Mapping(source = "resourceType", target = "type")
    ResourceResponseDto resourceToResourceDto(Resource resource);

    List<ResourceResponseDto> resourceListToDtoList(List<Resource> resources);

    @Named("toParentPath")
    default String toParentPath(Resource resource) {
        return PathUtil.extractParentDirectoryPath(resource.getPath());
    }

    @Named("toDisplayName")
    default String toDisplayName(Resource resource) {
        if (resource.getResourceType() == ResourceType.DIRECTORY) {
            return resource.getResourceName() + "/";
        }
        return resource.getResourceName();
    }
}
```

---

### пакет /model

1. Класс: `com.nataliya.model.entity.Resource`, Lombok-аннотации и дизайн сущности.

На сущности одновременно стоят `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`. Это избыточный набор с несколькими архитектурными проблемами.

`@Builder` на JPA-сущности — антипаттерн. Он провоцирует создание экземпляров сущности вручную (`Resource.builder()...build()`) в сервисном слое, что смешивает ответственность сервиса с ответственностью маппера. Сущности не должны конструироваться в бизнес-логике напрямую — для этого есть MapStruct или выделенный фабричный метод. `@Builder` в связке с `@NoArgsConstructor` и `@AllArgsConstructor` требует явного `@AllArgsConstructor` для работы билдера, но тогда Hibernate теряет конструктор по умолчанию без явного `@NoArgsConstructor` — разработчик вынужден держать оба, не понимая зачем. Для JPA-сущности достаточно `@NoArgsConstructor` (для Hibernate) и `@RequiredArgsConstructor` или пакетного конструктора через MapStruct.

`@Setter` на уровне класса генерирует `setId()`, `setUser()`, `setChildren()` — поля, которые не должны мутироваться после создания объекта. Публичный `setId()` нарушает инварианту идентичности сущности. Прямая замена `children` через `setChildren()` рвёт синхронизацию Hibernate (orphan removal, кеш первого уровня). Сеттеры нужны только для изменяемых при бизнес-операциях полей: `path`, `parent`, `resourceName`.

`@Column(nullable = false)` на полях сущности — избыточная аннотация, которая не выполняет реальной защиты. JPA использует её только при автоматической генерации DDL (`hbm2ddl`), которой в реальных проектах нет: схема управляется через Liquibase/Flyway. В runtime Hibernate не бросает исключение при `null`-значении поля с `nullable = false` до момента фактического SQL-запроса, и даже тогда выбрасывает `ConstraintViolationException` от БД, а не от JPA. Реальная защита от `null` — на двух уровнях: Bean Validation (`@NotNull`, `@NotBlank`) на DTO-объектах, чтобы невалидные данные не попали в сервис, и `NOT NULL`-ограничение в миграции Liquibase/Flyway, чтобы БД была защищена независимо от приложения. `@Column(nullable = false)` в сущности — лишний шум между двух настоящих уровней защиты.

**Рекомендация:** Убрать @Builder, @AllArgsConstructor, @Setter с класса, и аннотацию @Column в любом её виде. Создавать Resource через маппер

---

### пакет /persistence

1. Класс: `ResourceTypeConverter`.

`ResourceTypeConverter` реализует `AttributeConverter<ResourceType, Short>` и хранит enum как числовой код (0/1). При этом JPA предоставляет встроенный механизм через `@Enumerated(EnumType.STRING)` прямо на поле сущности — без написания конвертера. 
- `EnumType.STRING` хранит имя константы (`"FILE"`, `"DIRECTORY"`), что читаемо в БД, устойчиво к изменению порядка констант и не требует поддержки кода. 
- `EnumType.ORDINAL` (порядковый номер) — неустойчив к переупорядочиванию констант. 

Числовой код (0/1) через кастомный конвертер не даёт ощутимых преимуществ перед `EnumType.ORDINAL`

**Рекомендация:** Удалить ResourceTypeConverter полностью. И в бд лучше хранить строку (не забудь поменять тип строки в миграции), над resourceType повесить `@Enumerated(EnumType.STRING)`

---

### пакет /repository

1. Класс: `ResourceRepository`, методы: `findByUserIdAndPathStartingWith()`, `searchByResourceName()`.

`findByUserIdAndPathStartingWith()` транслируется в `WHERE user_id = ? AND path LIKE 'prefix%'`. Без составного индекса `(user_id, path)` запрос делает полное сканирование таблицы. 
Этот метод вызывается при каждом скачивании директории, удалении, перемещении и листинге поддерева — то есть при почти любой операции с директорией. Аналогично, `searchByResourceName()` использует `LOWER(r.resourceName) LIKE CONCAT('%', LOWER(:query), '%')` — запрос с двусторонним `%` не использует обычные B-tree индексы, а `LOWER()` отключает даже частичную индексацию. 
Без индекса этот запрос при росте данных становится `O(n)` по всей таблице пользователя.

**Рекомендация:** Добавить индексы
```sql
-- Для findByUserIdAndPathStartingWith и findByUserIdAndPath
CREATE INDEX idx_resources_user_path ON resources (user_id, path);

-- Для searchByResourceName (PostgreSQL — функциональный индекс + pg_trgm для поиска по подстроке)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_resources_resource_name_lower_trgm
    ON resources USING gin (user_id, lower(resource_name) gin_trgm_ops);
```

---

### пакет /security

1. Класс: `AuthenticatedUser`, конструктор `AuthenticatedUser(User user)`.

Конструктор принимает JPA-сущность `User` из доменного слоя напрямую. Это означает, что security-компонент `AuthenticatedUser` зависит от persistence-модели `User`. При загрузке пользователя из сессии Redis Jackson десериализует `AuthenticatedUser` — и здесь нет проблем, так как `User` в этом случае не используется. 
Но при первоначальном создании (в `CustomUserDetailsService`) конструктор связывает security и domain слои. При росте сущности `User` (например, добавление `@JsonIgnore` полей, изменение структуры) может возникнуть несовместимость. Лучше передавать только те поля, которые нужны для security-контекста.

**Рекомендация:** Сделать маппер или статик метод для создания AuthenticatedUser, а не скрытый мапинг внутри конструктора

2. Класс: `JsonAuthenticationFailureHandler`, метод: `onAuthenticationFailure()`.

Обработчик использует `e instanceof BadCredentialsException && e.getCause() instanceof ConstraintViolationException` для разграничения ошибки валидации формата и ошибки неверного пароля. 
Это хрупкая связь с деталями реализации `JsonUsernamePasswordAuthenticationFilter`: именно этот фильтр упаковывает `ConstraintViolationException` в `BadCredentialsException`. Если фильтр изменится (другой тип исключения-обёртки или прямой выброс `ConstraintViolationException`), handler молча начнёт отдавать 401 вместо 400 для ошибок валидации. 
Coupling между filter и handler происходит через тип исключения, а не через явный контракт.

**Рекомендация:** Ввести отдельный тип исключения для валидационных ошибок при аутентификации и, наверное, лучше использовать свич, а не цепочку елсе иф

---

### пакет /service

1. Классы: `MinioService`, `FileSystemService`, `RegistrationService` и другие сервисы.

Ни один сервис в проекте не скрыт за интерфейсом. Это нарушает принцип инверсии зависимостей (DIP): контроллеры и сервисы-потребители зависят от конкретных классов, а не от абстракций. Особенно критично это для `MinioService` — класса, инкапсулирующего работу с конкретным объектным хранилищем. 
Сейчас `FileSystemService`, `DownloadService` и `FileUploadService` напрямую зависят от `MinioService`. Если потребуется сменить хранилище (AWS S3 SDK вместо MinIO SDK, локальная файловая система для тестов, mock в unit-тестах), придётся менять все классы, которые инжектируют `MinioService`. 
При наличии интерфейса `ObjectStorageService` (или любое другое название) достаточно создать новую реализацию — потребители не меняются. Отсутствие интерфейса у `MinioService` также исключает возможность написания unit-тестов через мок: `MinioClient` создаётся как Spring Bean и не имеет интерфейса, поэтому Mockito может замокать его только через CGLIB-прокси, что требует особой настройки и ненадёжно.

Для остальных сервисов (`FileSystemService`, `RegistrationService`) интерфейс менее критичен, но всё равно полезен: он документирует публичный контракт сервиса отдельно от реализации, упрощает замену реализации и позволяет контроллеру зависеть от абстракции.

**Рекомендация:** Выделить интерфейс для объектного хранилища и для всех остальных сервисов так же нужны абстракции

2. Класс: `MinioService`, метод: `init()`.

Метод `@PostConstruct init()` копирует `properties.getBucketName()` в поле `bucketName`. Это лишняя операция: свойство уже хранится в `MinioProperties`, инжектированных через конструктор DI как `final`-поле. Вызов `properties.getBucketName()` в любом методе класса эквивалентен обращению к `bucketName`, но без необходимости в `@PostConstruct` и без изменяемого `private String bucketName`. Поле `bucketName` — это не `final` (не может быть, так как оно записывается в `@PostConstruct`), что нарушает иммутабельность бина.

**Рекомендация:** Убрать поле bucketName и @PostConstruct init(). Брать бакетНейм напрямую из пропертис

3. Класс: `RegistrationService`, метод: `registerUser()`.

Блок `catch (DataIntegrityViolationException ex)` проверяет `ex.getCause() instanceof ConstraintViolationException`, где импортируется `org.hibernate.exception.ConstraintViolationException` — Hibernate-специфичный класс. 
Это создаёт жёсткую зависимость от ORM-реализации в сервисном слое. Если провайдер изменится или версия Hibernate поменяет внутреннюю обёртку, `instanceof` не сработает и код перестанет отлавливать дублирующихся пользователей, пропуская это исключение наверх. 
Spring намеренно оборачивает provider-specific исключения в свои абстракции (`DataIntegrityViolationException`) — не нужно "протыкать" эту абстракцию через `getCause()`.

**Рекомендация:** Проверять только DataIntegrityViolationException (Spring-абстракция), не лезть в getCause() за Hibernate-специфичным типом.

4. Класс: `ResourceMetadataService`, метод: `requireResourceNotExists()`.

Внутри метода есть `resourcePath = PathUtil.formatPath(resourcePath, false, false)` — переприсвоение параметра локальной переменной. 
Это изменение видно только внутри метода и не влияет на вызывающий код. Вызывается из `FileSystemService.renameSubtree()`, который передаёт уже нормализованный путь — двойная нормализация не нарушает логику, но вводит в заблуждение: читатель предполагает, что метод принимает "сырой" путь и нормализует его, хотя это не является контрактом метода.
Если в будущем вызывающий код сменится на передачу ненормализованного пути, поведение будет неожиданным. Контракт должен быть явным.

**Рекомендация:** Явно задокументировать контракт: принимает нормализованный путь. Убрать внутреннюю нормализацию — нормализация должна быть на входе. Не должны быть каких-то неявных преобразований внутри метода

5. Класс: `DownloadService`, методы: `prepareDownload()` и `writeFileToStream()`.

В `prepareDownload()` ресурс загружается из БД: `Resource resource = resourceMetadataService.getResource(userId, normalizedPath)`. Используется только поле `resourceName` (для имени скачиваемого файла/архива). Внутри лямбды `StreamingResponseBody` при скачивании файла вызывается `writeFileToStream()`, который снова делает запрос: `Resource file = resourceMetadataService.getResource(userId, resourcePath)`. 
Один ресурс загружается из БД дважды. `resource.getId()` можно захватить в closure лямбды, избежав повторного запроса. Кроме того, `ensureIsFile()` вызывается в `writeFileToStream()` уже после первого `getResource()` в `prepareDownload()` — тип ресурса известен ещё до формирования лямбды, проверку можно сделать раньше.

**Рекомендация:**
```java
public DownloadResourceDto prepareDownload(Long userId, String resourcePath) {

    String normalizedPath = PathUtil.normalizePath(resourcePath);
    Resource resource = resourceMetadataService.getResource(userId, normalizedPath);

    boolean isDirectory = resource.getResourceType() == ResourceType.DIRECTORY;
    String filename = isDirectory
            ? resource.getResourceName() + ".zip"
            : resource.getResourceName();

    UUID fileId = resource.getId(); // захватить один раз

    StreamingResponseBody body = outputStream -> {
        if (isDirectory) {
            writeDirectoryContentsToStream(userId, normalizedPath, outputStream);
        } else {
            // Передать fileId напрямую — без повторного DB-запроса
            try (InputStream in = minioService.getFileStream(fileId)) {
                in.transferTo(outputStream);
            } catch (IOException e) {
                throw new FileStreamingException("Failed to stream file: " + normalizedPath, e);
            }
        }
    };

    return new DownloadResourceDto(filename, body);
}
```

6. Класс: `FileSystemService`, метод: `moveDirectory()`.

Когда целевой путь уже занят директорией, метод сливает содержимое: перепривязывает `children` source-директории к target-директории и удаляет source. Однако `resource.getChildren()` возвращает только прямых дочерних потомков (Hibernate lazy-коллекцию). Глубокие поддиректории в `children` обрабатываются позже в цикле `moveSubtree` (итерация по `subtree`), но к моменту их обработки их parent уже изменён (или они утратили связь с удалённым source). 
Логика предполагает, что `subtree` отсортирован по длине пути (root-first), и операции выполняются в нужном порядке, — но это неявный и хрупкий вариант. Никакого комментария или assert, поясняющего необходимость сортировки, нет. При параллельных операциях или изменении метода `getDirectorySubtree()` порядок может нарушиться.

**Рекомендация:** Явно задокументировать и зафиксировать инвариант сортировки:
```java
private Resource moveSubtree(Long userId, String sourceFullPath, String destinationPath) {
    List<Resource> subtree = resourceMetadataService.getDirectorySubtree(userId, sourceFullPath);

    // Инвариант: обработка от корня к листьям — родительские узлы должны быть перемещены
    // прежде чем обрабатываются дочерние. Нарушение порядка приводит к потере parent-ссылок.
    subtree.sort(Comparator.comparingInt(r -> r.getPath().length()));

    // Добавить assert для fail-fast в случае нарушения:
    assert subtree.isEmpty() || subtree.get(0).getPath().startsWith(sourceFullPath)
            : "Subtree root must match sourceFullPath";

    // ...остальная логика
}
```

7. Класс: `FileUploadService`, метод `uploadSingleFile()`.

`FileAlreadyExistsException` используется как механизм control flow: `FileUploadService.uploadSingleFile()` бросает её, `FileSystemService.uploadFiles()` ловит в цикле, извлекает `e.getFilePath()` и добавляет в список `failedFilePaths`.
Это антипаттерн: исключения предназначены для исключительных ситуаций (ошибки, которые нарушают нормальный поток программы), а не для передачи данных между слоями.
Текущее решение:
- а) создаёт overhead на генерацию stacktrace при каждом дублирующемся файле;
- б) вынуждает `FileSystemService` знать о внутреннем типе исключения. Правильнее использовать `Optional` или специальный result-объект.

**Рекомендация:** Заменить исключение на sealed result-тип:
```java
public sealed interface UploadResult permits UploadResult.Success, UploadResult.Duplicate {
    record Success(Resource resource) implements UploadResult {}
    record Duplicate(String filePath) implements UploadResult {}
}

// FileUploadService:
@Transactional(propagation = Propagation.REQUIRES_NEW)
public UploadResult uploadSingleFile(Long userId, MultipartFile file, String path) {
    // ...
    try {
        Resource metadata = resourceMetadataService.createFileMetadata(...);
        minioService.uploadFile(metadata.getId(), file);
        return new UploadResult.Success(metadata);
    } catch (DataIntegrityViolationException e) {
        return new UploadResult.Duplicate(/* filePath */);
    }
}

// FileSystemService:
for (MultipartFile file : files) {
    UploadResult result = fileUploadService.uploadSingleFile(userId, file, path);
    switch (result) {
        case UploadResult.Success s -> uploadedResources.add(s.resource());
        case UploadResult.Duplicate d -> failedFilePaths.add(d.filePath());
    }
}
```

---

### пакет /validation

1. Класс: `com.nataliya.validation.UserDtoValidator`, метод: `validate()`.

Класс `UserDtoValidator` можно удалить полностью — он является лишней прослойкой, существующей исключительно как обходной приём. Метод `validate(@Valid UserAuthenticationRequestDto)` имеет пустое тело: вся его "работа" — побочный эффект AOP-прокси от `@Validated` на классе, который запускает Bean Validation на `@Valid`-параметре до входа в тело. Разработчик видит пустой метод и не понимает, зачем он существует. Такой подход ломается при вызове напрямую (не через прокси — например, в тесте), при снятии `@Validated` "для очистки" или при рефакторинге класса. Это непрозрачная магия там, где можно написать явный код.

`JsonUsernamePasswordAuthenticationFilter` уже имеет `jakarta.validation.Validator` в Spring-контексте как готовый бин — его можно инжектировать напрямую в фильтр и выполнять валидацию там же, не создавая лишний класс-посредник.

**Рекомендация:** Удалить UserDtoValidator полностью. Инжектировать Validator прямо в фильтр:

---

### пакет /test

1. Класс: `com.nataliya.integration.service.RegistrationServiceIT`.

Тест использует `@SpringBootTest`, загружающий полный контекст приложения. `@DynamicPropertySource` переопределяет только `spring.datasource.*`. Не переопределяются: `spring.data.redis.*` (Redis) и `minio.*` (MinIO). При старте контекста `MinioInitializer.createBucketIfNotExists()` с `@PostConstruct` пытается подключиться к MinIO по `http://127.0.0.1:9000` — если MinIO не запущен, контекст не стартует и все тесты падают. Аналогично с Redis: `spring-session-data-redis` попытается подключиться к `localhost:6379`. Тесты зависят от запущенной внешней инфраструктуры, что нарушает принцип изоляции.

Кроме того, `@DynamicPropertySource` — это вручную написанный бойлерплейт, который устарел с появлением Spring Boot 3.1. Для стандартных контейнеров (PostgreSQL, Redis) достаточно аннотации `@ServiceConnection` прямо на поле контейнера — Spring Boot сам читает хост и порт из запущенного контейнера и подставляет нужные свойства (`spring.datasource.url`, `spring.data.redis.host` и т.д.) без единой строки конфигурации. Для MinIO готового `@ServiceConnection`-адаптера нет, поэтому там `@DynamicPropertySource` остаётся, но только для свойств `minio.*`.

**Рекомендация:**
```java
@Testcontainers
@SpringBootTest
public class RegistrationServiceIT {

    // @ServiceConnection сам подставит spring.datasource.* — @DynamicPropertySource не нужен
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    // @ServiceConnection сам подставит spring.data.redis.host / port
    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:7-alpine");

    // Для MinIO нет готового адаптера — @DynamicPropertySource только для него
    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry registry) {
        registry.add("minio.url", minio::getS3URL);
        registry.add("minio.access-key", minio::getUserName);
        registry.add("minio.secret-key", minio::getPassword);
    }
}
```

---

## РЕКОМЕНДАЦИИ

1. Ввести интерфейс `ObjectStorageService` для `MinioService`; добавить интерфейсы для `FileSystemService`, `RegistrationService` и остальных сервисов.
2. Вынести Swagger-аннотации в интерфейсы контроллеров (`ResourceApi`, `DirectoryApi` и т.д.); `AuthDocumentationController` заменить на `OpenApiCustomizer`.
3. Разделить пакет `dto` на `dto/request` и `dto/response`.
4. Убрать `@Builder`, class-level `@Setter` и `@Column(nullable = false)` с сущности `Resource`; создание объектов перенести в MapStruct-маппер.
5. Удалить `ResourceTypeConverter`, заменить на `@Enumerated(EnumType.STRING)`.
6. Удалить мёртвую конфигурацию `minio.user-root-directory` из `MinioProperties` и `application.yml`.
7. Заменить `FileAlreadyExistsException` как control flow на `sealed interface UploadResult`.
8. Удалить `UserDtoValidator`; инжектировать `jakarta.validation.Validator` напрямую в фильтр.
9. Устранить двойной DB-запрос в `DownloadService`: захватить `resource.getId()` в closure лямбды.
10. Добавить индексы `(user_id, path)` и GIN/pg_trgm для `resource_name` в миграции Liquibase.
11. Использовать `@ServiceConnection` для PostgreSQL и Redis в интеграционных тестах; `@DynamicPropertySource` оставить только для MinIO.
12. Зафиксировать инвариант сортировки в `moveSubtree()` через явный `assert` и комментарий.

---

## ИТОГ

Проект демонстрирует зрелое понимание ключевых архитектурных решений: хранение файлов в MinIO по UUID полностью отделено от метаданных в PostgreSQL — операции rename и move не требуют никаких обращений к объектному хранилищу. `AuthenticatedUser` с `id` исключает лишние DB-запросы в сервисном слое, `StreamingResponseBody` корректно решает проблему памяти при скачивании больших файлов, кастомная кросс-field валидация через `@ValidMovePath` делает бизнес-правила декларативными. Это уровень выше среднего для учебного проекта.

Главная архитектурная проблема — отсутствие интерфейсов у сервисов: `FileSystemService`, `DownloadService` и `FileUploadService` зависят напрямую от `MinioService`, что нарушает DIP, делает невозможным написание unit-тестов без реального MinIO и жёстко привязывает код к конкретному SDK. Рядом стоят: `@Builder` на JPA-сущности `Resource`, который провоцирует ручное создание объектов в сервисном слое вместо маппера; `app.*`-конфигурация, размазанная через `@Value` по трём разным слоям; `FileAlreadyExistsException` как механизм control flow вместо типизированного result-объекта; лишний `ResourceTypeConverter` там, где достаточно `@Enumerated(EnumType.STRING)`; и интеграционные тесты, зависящие от внешней инфраструктуры.

Для дальнейшего роста: освоить принцип инверсии зависимостей на практике — ввести интерфейсы для сервисов и научиться писать unit-тесты с моками без поднятия контейнеров; изучить sealed interfaces в Java 17+ как замену исключениям для передачи результата; разобраться с паттерном "интерфейс контроллера" для разделения Swagger-документации и реализации с прицелом на версионирование API; углубиться в правильный дизайн JPA-сущностей (без `@Builder`, с минимальным набором сеттеров, с маппером для создания).
