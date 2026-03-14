[aleksLiss/storage_app](https://github.com/aleksLiss/storage_app)

## ХОРОШО

1. **Интеграционные тесты с Testcontainers охватывают MinIO** — Опциональное задание ТЗ выполнено: `MinioServiceTest` тестирует все сценарии через реальный MinIO-контейнер, включая изоляцию данных между пользователями (`search` — пользователь не видит файлы другого). Тесты покрывают не только happy path, но и ошибки (unauthorized, not found, conflict). Это редкость для учебных проектов.

2. **`equals`/`hashCode` в `User` реализован по бизнес-ключу** — JPA-сущность `User` имеет `equals`/`hashCode` только по полю `username`, а не по `id` и не по всем полям. Это корректный подход: объект правильно идентифицируется и до `persist` (когда `id` ещё null), и после. Большинство новичков допускают ошибку, используя `@Data` на сущностях и получая equals по всем полям.

3. **MapStruct применён корректно там, где нужен** — `UserDetailsMapper` использует генерацию кода MapStruct по-настоящему: `UserDto → User` и `User → UserDetailsImpl` без ручного копирования полей. Маппинг изолирован от бизнес-логики сервиса. Граница применения инструмента правильно осознана (в отличие от `AnswerResponseDtoMapper`, где MapStruct не нужен — об этом в замечаниях).

4. **Иерархия исключений организована по смысловым пакетам** — Исключения разнесены по подпакетам `resource/file`, `resource/folder`, `resource`, `user`, `mapper`. Это лучше, чем складывать всё в плоский пакет, и облегчает навигацию по доменным ошибкам.

5. **`StreamingResponseBody` для скачивания файлов и папок** — Файлы и ZIP-архивы отдаются через Spring `StreamingResponseBody`: данные пишутся прямо в `OutputStream` ответа, не загружаясь целиком в heap. Это правильный выбор для файлового сервиса: память не растёт пропорционально размеру файла, и сервер не «зависает» при скачивании крупных объектов. Большинство новичков возвращают `byte[]` или `InputStreamResource`, не думая о последствиях при больших файлах.

---

## ЗАМЕЧАНИЯ

### пакет /config

1. Класс: `MinioConfig`. 

Свойства конфигурации MinIO читаются через `@Value` в `MinioConfig`, но сам `MinioConfig` инжектируется как зависимость напрямую в `MinioService`, `FileChecker` и `ResourceChecker`. Это архитектурная проблема: репозиторный и утилитарный слои напрямую зависят от конфигурационного класса инфраструктуры. При добавлении новой настройки (например, timeout) нужно лезть в `MinioConfig` и затем менять всех его потребителей. Одно и то же свойство `bucketName` читается через `minioConfig.getBucketName()` в трёх разных классах — это скрытое дублирование и риск рассинхронизации. Конфигурация — это инфраструктурный слой; сервисы и утилиты не должны знать об источнике конфигурации. `@ConfigurationProperties` обеспечивает типобезопасность, единый источник правды и возможность валидации при старте приложения.

**Рекомендация:** создать `@ConfigurationProperties(prefix = "app.minio")` record `MinioProperties` . Включить через `@EnableConfigurationProperties`. Инжектировать `MinioProperties` туда, где сейчас инжектируется `MinioConfig`

2. Класс: `MinioConfig`, метод: `getMinioClient()`. 

Метод `getMinioClient()` не помечен `@Bean` — это обычный getter, создающий `new MinioClient` при каждом вызове. `MinioService`, `FileChecker` и `ResourceChecker` вызывают `minioConfig.getMinioClient()` внутри каждого операционного метода. `MinioClient` — тяжёлый объект: он инициализирует HTTP-клиент с пулом соединений. Создание нового `MinioClient` на каждую операцию (загрузку файла, поиск, удаление) исключает переиспользование TCP-соединений, создаёт нагрузку на GC и радикально снижает производительность при высоком RPS. Правильное решение — объявить `MinioClient` как `@Bean` (singleton) и инжектировать его через DI.

**Рекомендация:** Пометить `minioClient` как Bean и инжектить клиент, а не конфиг

3. Класс: `MinioConfig`. 

Свойства для MinIO используют namespace `spring.minio.*`: `spring.minio.bucket_name`, `spring.minio.credentials.login`, `spring.minio.endpoint` и т.д. Namespace `spring.*` зарезервирован для официальных настроек Spring Boot. Использование кастомных ключей под `spring.*` может вызвать конфликты с будущими версиями Boot, вызывает путаницу при чтении `application.yml` (непонятно, какие свойства стандартные, а какие кастомные), и такие свойства не обрабатываются Spring Boot Autoconfiguration. Кастомные свойства должны использовать отдельный prefix, например `app.*` или `storage.*`.

**Рекомендация:** использовать кастомный namespace

---

### пакет /controller

1. Класс: `AuthController`, метод: `signIn()`. 

`AuthController` напрямую зависит от `MinioService` и вызывает `minioService.createRootFolderForUserByUsername(username)` при каждом входе. Контроллер аутентификации не должен знать о файловом хранилище. Это нарушение SRP: ответственность за инициализацию хранилища пользователя лежит не в слое авторизации, а в слое регистрации. 
Создание корневой папки — это один раз на всю жизнь пользователя, логически принадлежит событию регистрации, а не входа. Текущая реализация делает MinIO-запрос при каждом успешном login (даже если папка уже есть), что снижает производительность и связывает два независимых слоя.

**Рекомендация:** убрать поле minioService и вызов createRootFolderForUserByUsername. В контроллерах вообще не должно быть ничего кроме 1 вызова и возврата результат от сервиса.

2. Класс: `AuthController`, поле `securityContextRepository`. 

В поле контроллера создаётся объект через `new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository())`. Это нарушение IoC: зависимость инстанциируется вручную вместо того, чтобы быть управляемым Spring-бином. 
Такая `SecurityContextRepository` не переиспользуется другими компонентами, которым она может быть нужна (например, фильтрами). Если понадобится поменять стратегию хранения контекста — придётся менять код контроллера, а не конфигурацию.

**Рекомендация:** SecurityContextRepository — объявить бином и подключить к цепочке
```java
@Bean
public SecurityContextRepository securityContextRepository() {
    return new DelegatingSecurityContextRepository(
            new RequestAttributeSecurityContextRepository(),
            new HttpSessionSecurityContextRepository());
}
// http.securityContext(ctx -> ctx.securityContextRepository(securityContextRepository()));

// AuthController — инжектировать через конструктор вместо new
private final SecurityContextRepository securityContextRepository;
```

3. Класс: `AuthController`, метод: `signOut()`. 

В `SecurityConfig` настроен `logout().logoutUrl("/api/auth/sign-out")`. Spring Security обрабатывает этот URL через `LogoutFilter` — фильтр, который запускается до `DispatcherServlet`. Когда приходит `POST /api/auth/sign-out`, `LogoutFilter` перехватывает запрос, инвалидирует сессию и вызывает `logoutSuccessHandler` (который возвращает 204). Запрос никогда не достигает `DispatcherServlet` и, следовательно, `AuthController.signOut()`. Метод `signOut()` в контроллере — мёртвый код. При этом в SecurityConfig logout настроен корректно и справляется с задачей самостоятельно.

**Рекомендация:** удалить метод `signOut()` из `AuthController` полностью — `LogoutFilter` из Spring Security перехватывает запрос раньше и уже возвращает 204 через `logoutSuccessHandler` в `SecurityConfig`.

4. Класс: `UserController`, метод: `me()`. 

Метод содержит проверку `if (principal == null)` с ручным возвратом 401. Эндпоинт `/api/user/me` находится под правилом `.requestMatchers("/api/**").authenticated()` в `SecurityConfig`. Spring Security отклонит неаутентифицированный запрос на уровне `AuthorizationFilter` с 401 — до того, как запрос достигнет контроллера. `principal` при достижении метода никогда не будет `null`. Эта проверка — мёртвый код, демонстрирующий непонимание жизненного цикла Spring Security.

**Рекомендация:** Убрать ручную проверку — она никогда не выполнится

5. Классы: `ResourceController`, методы: `getResource()` и `downloadResource()`; `MinioService`, методы: `getResource()`, `downloadFile()`, `downloadFolder()`. 

Это критическая уязвимость безопасности. Методы `getResource` и `downloadResource` принимают `path` из запроса напрямую, не проверяя принадлежность ресурса текущему пользователю. Злоумышленник, зная формат внутреннего пути MinIO (`user-{uuid}-files/`), может передать `path=user-<чужой-uuid>-files/secret.txt` и получить информацию о чужих файлах или скачать их. При этом все остальные операции (delete, upload, createFolder, search, move) правильно добавляют `createPathToRootFolder(principal)` в качестве префикса и изолируют данные пользователей. Незащищённые операции чтения и скачивания сводят на нет всю изоляцию.

**Рекомендация:** getResource и downloadFile/downloadFolder — добавить principal и строить fullPath так же, как это сделано во всех остальных методах MinioService

---

### пакет /dto

1. Класс: `UserDetailsImpl`. 

`UserDetailsImpl` реализует `UserDetails` из Spring Security и является частью домена безопасности приложения, а не объектом передачи данных. Размещение его в пакете `/dto` нарушает принцип разделения: DTO — это объекты для передачи данных через границы (API, сеть), а `UserDetails` — это внутренний контракт Spring Security. Разработчик, ищущий модель пользователя безопасности, будет искать её в `/security` или `/model`, а не в `/dto`.

**Рекомендация:** перенести `UserDetailsImpl` в пакет `com.storage.app.security` и переименовать в `UserPrincipal`.

2. Класс: `UserDetailsImpl`. 

На классе стоит `@Data`, который генерирует `toString()` включая поле `password` — хешированный пароль будет выведен в лог при любом случайном логировании объекта. Кроме того, `equals`/`hashCode` генерируются по всем полям включая `password`: сравнение пользователей по паролю не имеет смысла для Security Principal. Для реализаций `UserDetails` достаточно `@Getter` и `@RequiredArgsConstructor`.

**Рекомендация:**
```java
@Getter
@RequiredArgsConstructor
public class UserDetailsImpl implements UserDetails {
    private final UUID userId;
    private final String username;
    private final String password;
}
```

3. Класс: `JwtRequest`. 

Название `JwtRequest` вводит в заблуждение: в проекте нет JWT. Используется сессионная аутентификация (Spring Session + Redis). Разработчик, видящий `JwtRequest`, ожидает найти JWT-генерацию, JWT-фильтр, и т.д. Это ложный сигнал об архитектуре. Названия должны отражать реальное поведение системы.

**Рекомендация:** переименовать `JwtRequest` в `SignInRequest` — в проекте нет JWT, используется сессионная аутентификация.

3. Классы: `FoundResourceDto`, `UploadResourceDto`, `MoveResourceDto`. В `FoundResourceDto` поле `path` имеет только `@Size(max=1024)` без `@NotBlank`. Это означает, что `null` и `""` пройдут Bean Validation. В `MinioService` при null-пути произойдёт `NullPointerException` в `getListResource` (передача null в `listObjects`). Аналогично в `UploadResourceDto.path` — только `@Size` без `@NotBlank`, что позволяет null. В `MoveResourceDto` оба поля `from` и `to` без `@NotBlank`.

**Рекомендация:** Добавить `@NotBlank` к полям path, from, to во всех request-DTO

4. Класс: `AnswerResponseDto`, поле `size`. Поле `size` имеет тип `String`. ТЗ явно задаёт формат: `"size": 123` (число в JSON). Использование `String` для числового поля нарушает контракт API: клиент получит `"size": "123"` (строку) вместо `"size": 123` (число). Кроме того, весь сервисный слой конвертирует `Long` → `String` при сборке DTO и обратно нигде не читает. Это потеря типобезопасности без выгоды.

**Рекомендация:** String — должно быть число, как требует ТЗ

5. Классы: `ResourceController` и `AnswerResponseDtoMapper`. Все методы API возвращают `LinkedHashMap<String, String>` или `List<LinkedHashMap<String, String>>`. Это уничтожает типобезопасность: контроллер объявляет `ResponseEntity<?>`, Swagger не может нормально сгенерировать схему, нет compile-time проверки полей. Данные сериализуются через Map-сериализатор, а не через Jackson-маппинг типизированного класса. Любая опечатка в ключе (`"pth"` вместо `"path"`) компилируется без ошибок. DTO уже существует (`AnswerResponseDto`) — нужно использовать его как return type.

**Рекомендация:** Использовать Dto

---

### пакет /exception

1. Класс: `GlobalExceptionHandler`, метод: `handleGeneralException()`. 

`FileBigSizeException` обрабатывается в группе, возвращающей `500 Internal Server Error`. Файл, превышающий допустимый размер — это ошибка клиента (некорректный запрос), а не серверная ошибка. Должен возвращаться `400 Bad Request` или `413 Payload Too Large`. Возврат 500 дезинформирует клиента: он думает, что проблема на стороне сервера, и будет повторять запрос.

**Рекомендация:** Вынести FileBigSizeException в отдельный обработчик

2. Класс: `GlobalExceptionHandler`. 

`FolderCreateException` обрабатывается в методе `handleExistsExceptions()` с HTTP статусом `409 Conflict`. Но `FolderCreateException` в `MinioService` бросается при реальной ошибке MinIO во время создания папки (`catch (Exception ex) { throw new FolderCreateException(...)}`). Ошибка MinIO (сеть недоступна, диск полон, таймаут) — это серверная проблема, а не конфликт. Клиент получит 409 при реальном сбое инфраструктуры, что приведёт к неправильному поведению на стороне клиента (он решит, что ресурс уже существует).

**Рекомендация:** Убрать FolderCreateException из 409-группы, добавить в 500-группу

3. Класс: `FolderAlreadyExistsException`. 

Класс `FolderAlreadyExistsException` существует, но никогда не бросается в коде и не зарегистрирован в `GlobalExceptionHandler`. Это мёртвый код, создающий ложное ощущение, что где-то обрабатывается специальный кейс существующей папки. 
В реальности везде используется `ResourceAlreadyExistsException`. Мёртвый код усложняет навигацию и поддержку.

**Рекомендация:** удалить `FolderAlreadyExistsException` — везде использовать `ResourceAlreadyExistsException`.

4. Класс: `GlobalExceptionHandler`, метод: `handleExistsExceptions()`. 

Внутри обработчика есть `if (ex instanceof UserAlreadyExistsException)` для специальной обработки сообщения. Это нарушение Open/Closed Principle: при добавлении нового типа исключения придётся расширять if-else в существующем методе. Каждый тип исключения должен иметь свой `@ExceptionHandler` — это и есть предназначение механизма. Диспатчинг по типу — ответственность Spring, а не ручного instanceof.

**Рекомендация:** Разделить на два отдельных @ExceptionHandler — Spring сам выберет по типу

5. Класс: `GlobalExceptionHandler`. 

Аннотация `@ResponseStatus` применяется одновременно с `ResponseEntity.status(...)` на каждом методе обработчика. Когда метод возвращает `ResponseEntity`, статус из `@ResponseStatus` игнорируется — `ResponseEntity` всегда имеет приоритет. `@ResponseStatus` полезна, когда метод возвращает `void` или тело ответа напрямую. 
Наличие обеих аннотаций создаёт избыточность и вводит читателя в заблуждение — непонятно, какой из двух источников статуса «реальный».

**Рекомендация:** убрать `@ResponseStatus` со всех методов, возвращающих `ResponseEntity` — статус уже задаётся через `ResponseEntity.status(...)`, двойное указание создаёт путаницу.

---

### пакет /mapper

1. Класс: `AnswerResponseDtoMapper`. 

Интерфейс аннотирован `@Mapper(componentModel = "spring")`, но не содержит ни одного MapStruct-маппинга. Все методы — `default`-методы с ручной логикой. MapStruct здесь используется исключительно как механизм регистрации Spring-бина (аналог `@Component`). Это вводит в заблуждение: читатель видит `@Mapper` и ожидает, что MapStruct генерирует код, но генерации нет. Кроме того, `@Context` из MapStruct используется для передачи `ResourceFinder` — это неправильное использование: `@Context` предназначен для передачи данных в MapStruct-генерированные методы, не в `default`-методы. В `default`-методах он просто становится обычным параметром, теряя смысл аннотации.

**Рекомендация:** дефолт методы должны создаваться для помощи мапинга мапстрактом, а не как основной мапинг, нужно исправить этот маппер

2. Класс: `AnswerResponseDtoMapper`, метод: `answerResponseDtoToMap(ResourceFinder, String)`. 

В методе содержится дублирующаяся и противоречивая логика определения типа ресурса: сначала `if (name.endsWith("/")) { answerResponseDto.setType("DIRECTORY"); }`, а затем `if (answerResponseDto.getSize() == null) { answerResponseDto.setType("DIRECTORY"); }`. 
Если name не оканчивается на "/", но size == null — тип будет DIRECTORY, хотя ранее он не был установлен. Если size != null и name оканчивается на "/" — тип будет DIRECTORY (правильно). Но если size != null и name не оканчивается на "/" — тип не будет установлен вообще (null), что приведёт к NPE или невалидному ответу. Логика неполна и взаимозависима.

**Рекомендация:**
```java
// Единственный источник истины для типа — trailing slash в MinIO-пути
boolean isDirectory = fullMinioPath.endsWith("/");
String type = isDirectory ? "DIRECTORY" : "FILE";
Long size   = isDirectory ? null : item.size();
```

---

### пакет /model

1. Класс: `User`, метод: `toString()`. 

Метод `toString()` включает поле `password`. Если объект `User` случайно попадёт в лог (например, `log.debug("Saving user: {}", user)` или при выбросе исключения), хеш пароля окажется в логах. Хотя это bcrypt-хеш, его утечка в логи — нарушение требований безопасности (OWASP, PCI DSS). Персональные и чувствительные данные не должны логироваться.

**Рекомендация:**
```java
@Override
public String toString() {
    return "User{uuid=" + uuid + ", username='" + username + "'}";
}
```

---

### пакет /service

1. Класс: `MinioService`. 

На сервисе стоит аннотация `@Data`. `@Data` генерирует геттеры, сеттеры, `equals`, `hashCode`, `toString` для всех полей. На Spring-сервисе это создаёт несколько проблем одновременно: сеттеры на `final`-полях (через `@Setter`) ломают иммутабельность DI-бина; `toString()` будет включать `MinioConfig` с паролями; `equals`/`hashCode` по всем полям (включая `MinioClient`) не имеют смысла для синглтон-сервиса и могут сломать коллекции/кеши, если туда попадёт экземпляр. 
Для сервиса нужен только `@RequiredArgsConstructor` (для DI через конструктор) и `@Slf4j`.

**Рекомендация:** Заменить `@Data` на `@RequiredArgsConstructor` + `@Slf4j`

2. Класс: `MinioService`

В проекте отсутствует интерфейс над файловым хранилищем: `MinioService` напрямую работает с `MinioClient` из SDK без какой-либо абстракции. Это нарушение DIP и жёсткая связь с конкретной реализацией. 
Последствия: написать unit-тест для `MinioService` невозможно без поднятия реального MinIO-контейнера — `MinioClient` нельзя замокать через стандартный Spring DI; при смене хранилища (AWS S3, GCS, локальная ФС) придётся переписывать весь сервисный слой; бизнес-логика (путь к файлу, построение имени папки) перемешана с деталями SDK (аргументы `PutObjectArgs`, `ListObjectsArgs`).

**Рекомендация:** Ввести интерфейс — бизнес-слой зависит от абстракции, а не от MinioClient

3. Класс: `MinioService`. Один класс на 478 строк обслуживает все операции с файловым хранилищем: загрузку файлов, скачивание файлов, скачивание папок в ZIP, создание папок, удаление ресурсов, перемещение/переименование, поиск, листинг директорий, инициализацию корневой папки. Это классический God Object, нарушающий SRP. При добавлении новой логики (например, поддержки версионирования файлов или метаданных) класс будет расти бесконтрольно. Тестировать такой класс сложно: нужно мокировать всё сразу. Ни одна операция не изолирована.

**Рекомендация:** разбить `MinioService` на три сервиса по ответственности:
- `FileService` — upload, download файлов, get info
- `FolderService` — создание, листинг, download ZIP
- `ResourceManagementService` — delete, move, search

3. Класс: `MinioService`, метод: `getUUIDFromFoundUser()`. 

`MinioService` содержит `UserRepository userRepository` и при каждой операции (`getResource`, `deleteResource`, `uploadResource`, `searchResource`, `moveResource`, `createFolder`) вызывает `userRepository.findByUsername(principal.getName())` для получения UUID пользователя.
Это: 
1) нарушение SRP — сервис хранилища не должен знать о БД пользователей; 
2) лишний SQL-запрос при каждой файловой операции. При этом `UserDetailsImpl` уже содержит поле `userId` (UUID) — он доступен из `SecurityContext` без запроса к БД.

**Рекомендация:** Контроллер — использовать @AuthenticationPrincipal вместо Principal
```java
public ResponseEntity<AnswerResponseDto> getResource(...,
        @AuthenticationPrincipal UserDetailsImpl userDetails) {
    minioService.getResource(dto.getPath(), userDetails.getUserId()); // UUID без DB-запроса
}
```

4. Класс: `MinioService`, метод: `getUUIDFromFoundUser()`. 

Строка `userRepository.findByUsername(principal.getName()).get()` вызывает `.get()` на `Optional` без проверки. Если пользователь был удалён между аутентификацией и выполнением операции (маловероятно, но теоретически возможно), будет выброшен `NoSuchElementException` вместо понятной `UsernameNotFoundException` или `ResourceNotFoundException`. `NoSuchElementException` не обрабатывается в `GlobalExceptionHandler` и вернёт 500 без внятного сообщения.

**Рекомендация:**
```java
private String getUUIDFromFoundUser(Principal principal) {
    return userRepository.findByUsername(principal.getName())
            .map(user -> String.valueOf(user.getUuid()))
            .orElseThrow(() -> new UsernameNotFoundException(
                "User not found: " + principal.getName()
            ));
}
```

5. Класс: `MinioService`, метод: `searchResource()`. 

Логика поиска проверяет, содержит ли полный MinIO-путь подстроку `query + "/"` или `query + "."`. Это нестрогий матч по полному пути объекта, а не по имени ресурса. Последствия: файл без расширения с именем `"report"` не будет найден по запросу `"report"` (нет ни `"report/"`, ни `"report."`); папка `"reports/"` не будет найдена по запросу `"report"` (нет `"report/"`); файл `"budget_report.xlsx"` будет найден по запросу `"budget"` через `"budget_report.xlsx"` (содержит `"budget_"` — но не `"budget/"` или `"budget."` — значит НЕ найдётся!). Поиск должен производиться по имени ресурса, а не по полному пути.

**Рекомендация:**
```java
// Искать по имени ресурса (последний сегмент пути), а не по полному MinIO-пути
String resourceName = resourceFinder.getResourceNameFromPath(item.objectName()).toLowerCase();
if (resourceName.contains(queryLower)) { ... }
```

6. Классы: `MinioService` и `AnswerResponseDtoMapper`. В ответах API на `GET /resource`, `GET /directory`, `GET /resource/move` поле `path` содержит полный внутренний путь MinIO включая корневую папку `user-{uuid}-files/`. Например, клиент получит `"path": "user-550e8400-e29b-files/folder1/"` вместо `"path": "folder1/"`. Это явная утечка деталей реализации хранилища, прямо упомянутая в чеклисте ТЗ как типовая ошибка. Клиент видит внутреннюю структуру MinIO, что нарушает инкапсуляцию и может облегчить атаку (злоумышленник узнаёт UUID пользователей из ответов API). В `PathParser.parsePath` (используется в `searchResource`) корневой сегмент обрезается через `Arrays.copyOfRange(..., 1, ...)` — поведение для поиска и для листинга/получения ресурсов несогласовано.

**Рекомендация:**
```java
// При формировании ответа обрезать user-root prefix из пути
String relativePath = fullPath.startsWith(userRootPrefix)
        ? fullPath.substring(userRootPrefix.length())
        : fullPath;
// Далее строить path/name из relativePath, а не из fullPath
```

7. Класс: `MinioService`, метод: `moveResource()`. Метод содержит проверку `if (isRelocate(from, to, principal) && isRename(from, to, principal)) { throw new IllegalArgumentException("Must be only rename or relocate"); }`. Логика `isRename` и `isRelocate` при детальном анализе показывает, что это условие никогда не может быть одновременно истинным: `isRename` требует `parentFrom == parentTo`, `isRelocate` при `nameFrom == nameTo` (первое условие) ⇒ `nameFrom == nameTo AND nameFrom != nameTo` — противоречие. При `parentFrom != parentTo` (второе условие OR) `isRename` = false. Таким образом, валидация `isRelocate && isRename` мертва и никогда не срабатывает. При этом комбинированная операция (одновременно сменить имя и папку) проходит без проверки, хотя возможно разработчик хотел её запретить.

**Рекомендация:** удалить `isRename`/`isRelocate` и заменить недостижимую проверку явной:
```java
if (fullFrom.equals(fullTo)) {
    throw new IllegalArgumentException("Source and destination are the same");
}
```

---

### пакет /util

1. FileChecker и ResourceChecker не выглядят как утилитарные классы.

Сейчас оба класса зависят от Spring-инфраструктуры: MinioConfig, а значит — от MinioClient, bucket name и прочих деталей конфигурации. Для утилитарных классов это плохой признак: утилита должна быть stateless и работать только с входными данными, без DI и без знания о конфигурации приложения.
Если для проверки нужен доступ к MinIO или настройкам, это уже не utility, а обычный сервис/компонент, и его нужно так и назвать.

**Рекомендация:** Убрать весь DI, все методы сделать static

2. Класс: `path.PathParser`. На `@Component`-классе стоит `@Data`, что генерирует `equals`/`hashCode` по полям `CopyAnswerResponseDtoCreator` и `ResourceFinder` — сравнение Spring-бинов по значениям инжектируемых зависимостей не имеет смысла. Та же ошибка, что и в `MinioService`, `FileChecker`, `ResourceChecker`. Для Spring-компонента с инъекцией через конструктор достаточно `@RequiredArgsConstructor`.

**Рекомендация:**
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PathParser { ... }  // убрать @Data
```

3. Класс: `path.PathParser`, метод: `parsePath()`. Для различения файла и папки используется эвристика `lastElem.contains(".")`: если имя содержит точку — это файл, иначе — папка. Это ненадёжно: файл без расширения (`Makefile`, `README`, `LICENCE`) будет определён как директория; папка с точкой в имени (`v1.0/`, `my.project/`) будет определена как файл. Правильный признак — trailing slash в объектном пути MinIO: объекты-папки всегда заканчиваются на `/`.

**Рекомендация:**
```java
// Заменить lastElem.contains(".") на trailing slash — надёжный признак папки в MinIO
boolean isDirectory = item.objectName().endsWith("/");
```

3. Класс: `CopyAnswerResponseDtoCreator`, метод: `copyOf()`. Метод копирует только `name` и `path`, молча игнорируя `size` и `type`. Это неполная копия: если вызвать `copyOf` и использовать результат как ответ API, клиент получит DTO с null в `size` и `type`. Кроме того, класс регистрируется как Spring Bean (`@Component`) с единственным методом, который по сути дублирует то, что уже можно сделать через конструктор `AnswerResponseDto` или MapStruct. Наличие `@Component` для такого утилитарного класса избыточно.

**Рекомендация:**
```java
// Удалить CopyAnswerResponseDtoCreator, добавить статический фабричный метод в сам DTO
public class AnswerResponseDto {
    // ...
    public static AnswerResponseDto copyOf(AnswerResponseDto other) {
        return new AnswerResponseDto(other.path, other.name, other.size, other.type);
    }
}
```

4. Класс: `MinioService`, метод: `checkCorrectPathToResource()`. Внутри цикла `for (Result<Item> res : results)` есть пустой `catch (Exception e) {}`. Если `res.get()` выбрасывает исключение (например, MinIO вернул ошибку при итерации), оно молча поглощается. Это маскирует реальные проблемы: ошибки сети или хранилища пройдут незамеченными, метод вернёт частичный результат без предупреждения. Пустые catch-блоки — антипаттерн, особенно опасный в I/O-коде.

**Рекомендация:**
```java
// Минимум — залогировать исключение
for (Result<Item> res : results) {
    try {
        log.debug("Found object: {}", res.get().objectName());
    } catch (Exception e) {
        log.warn("Could not read MinIO item during listing", e); // не глотать!
    }
}
```

5. Класс: `MinioService`, метод: `searchResource()`, и `path.PathParser`, метод: `parsePath()`. Уровень `log.warn` используется для вывода отладочной информации: `log.warn("Found objects count: " + results.size())`, `log.warn("Target: " + res.get().objectName())`, `log.warn(answerResponseDto.getPath())`. `warn` — это уровень для реальных предупреждений, требующих внимания (деградация, нестандартная ситуация). Для отладочного вывода нужен `log.debug`. При включённом `WARN`-уровне в production логи будут засорены отладочными строками.

**Рекомендация:** заменить `log.warn` на `log.debug` для диагностических сообщений (количество объектов, имена, пути). `warn` оставить только для реальных проблем.

---

## РЕКОМЕНДАЦИИ

1. **Ввести `@ConfigurationProperties` для всей конфигурации MinIO** — Создать типизированный `MinioProperties`-класс с `@ConfigurationProperties(prefix = "app.minio")`. Это даёт: валидацию при старте (`@Validated`), единый источник правды, IDE-автодополнение в `application.yml`, удаление `@Value` из сервисов и утилит. Сменить namespace с `spring.minio.*` на `app.minio.*`.

2. **Объявить `MinioClient` Spring-бином (`@Bean` с singleton scope)** — Сейчас `MinioClient` создаётся на каждый вызов операции. Одна операция загрузки файла = несколько новых `MinioClient`-объектов (в `MinioService`, `FileChecker`, `ResourceChecker`). `MinioClient` должен быть объявлен как `@Bean` в `MinioConfig` и инжектироваться через конструктор. Это фундаментальное условие нормальной производительности.

3. **Исправить критическую уязвимость: проверять принадлежность ресурсов пользователю во всех операциях** — `getResource` и `downloadResource` не добавляют user-prefix к пути. Злоумышленник может получить/скачать файлы другого пользователя, зная формат `user-{uuid}-files/`. Все методы MinioService должны принимать UUID пользователя и строить полный путь с его корневой папкой. UUID следует извлекать из `UserDetailsImpl` в `SecurityContext`, а не из БД.

4. **Разбить `MinioService` на специализированные сервисы по ответственности** — God Object на 478 строк мешает тестируемости и расширяемости. Предлагаемое деление: `FileOperationService` (upload, download файлов), `FolderOperationService` (создание, листинг, download ZIP), `ResourceManagementService` (delete, move, search). Опционально — фасадный класс, который объединяет их для контроллера.

5. **Перенести создание корневой папки в момент регистрации** — `createRootFolderForUserByUsername` вызывается при каждом `signIn`. Это лишний MinIO-запрос (и DB-запрос) при каждой авторизации. Создание папки должно происходить один раз в `UserService.save()` после сохранения пользователя в БД.

6. **Устранить утечку внутреннего пути `user-{uuid}-files/` в API-ответах** — Все операции (кроме поиска, который корректно обрезает через `PathParser`) возвращают путь с корневым MinIO-сегментом. Нужен единый метод обрезки user-root prefix при формировании ответа. Это явная проблема из чеклиста ТЗ.

7. **Заменить `LinkedHashMap<String, String>` типизированными DTO** — Возврат `Map<String, String>` уничтожает типобезопасность, исключает нормальную OpenAPI-документацию и позволяет опечаткам в ключах компилироваться без ошибок. `AnswerResponseDto` уже существует — достаточно использовать его как return type. `size` должен быть `Long`, а не `String`. Добавить `@JsonInclude(NON_NULL)` чтобы поле `size` не появлялось в ответе для папок.

8. **Использовать UUID из `SecurityContext` вместо DB-запроса** — `UserDetailsImpl` уже содержит `userId`. Принимать `@AuthenticationPrincipal UserDetailsImpl userDetails` в контроллере и передавать `userDetails.getUserId()` в сервис. Это устраняет зависимость `MinioService` от `UserRepository` и лишний SQL на каждый запрос.

9. **Заменить эвристику `.contains(".")` надёжным признаком** — В `PathParser` и `searchResource` тип ресурса (файл vs папка) определяется по наличию точки в имени. Надёжный признак из MinIO — trailing slash: объекты-папки всегда оканчиваются на `/`. Файлы без расширения и папки с точкой в имени будут корректно классифицированы.

10. **Упорядочить уровни логирования** — `log.warn` используется для отладочного вывода во всём сервисном слое. Отладочные сообщения (количество найденных объектов, имя объекта, результаты парсинга) перевести на `log.debug`. `warn` — только для реальных предупреждений (сбои при инициализации, неожиданное состояние данных).

---

## ИТОГ

Проект функционален и покрывает все требования ТЗ. 

Главные проблемы, которые нужно устранить в первую очередь: **критическая уязвимость** — `getResource` и `downloadResource` не проверяют принадлежность ресурса текущему пользователю, что позволяет читать чужие файлы; **утечка внутреннего пути** `user-{uuid}-files/` в API-ответах (прямая позиция из чеклиста ТЗ); **создание `MinioClient` на каждый вызов** — должен быть singleton-бином. Архитектурно: `MinioService` является God Object, зависящим одновременно от MinIO, UserRepository, конфигурации и утилит — это нужно разделить. `@Data` на сервисе генерирует сеттеры на DI-полях и включает credentials в `toString`. Конфигурация должна использовать `@ConfigurationProperties` вместо `@Getter`-класса с `@Value`.

Для дальнейшего роста: изучить принцип Defense in Depth в Spring Security (проверка владения ресурсами на уровне сервиса, а не только аутентификация на уровне фильтра); разобраться с паттернами Clean/Hexagonal Architecture для правильного разделения на слои (domain, application, infrastructure); изучить `@AuthenticationPrincipal` для извлечения данных пользователя без обращения к БД; практиковать написание unit-тестов для сервисов с моками (дополнить существующие интеграционные тесты).
