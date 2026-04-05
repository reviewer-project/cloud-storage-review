[Metty1337/cloud-file-storage](https://github.com/Metty1337/cloud-file-storage)

## ХОРОШО

1. **`sealed interface StorageObjectResponse`** — использование sealed interface с `permits StorageFileResponse, StorageDirectoryResponse` — современный Java 17+ подход для полиморфных ответов. Компилятор гарантирует исчерпывающее покрытие всех подтипов в `switch`-выражениях, исключая появление неожиданных реализаций в рантайме. Это типобезопасно и документирует домен на уровне типов.

2. **`MinioProperties` как `@ConfigurationProperties` record с вложенными records** — конфигурация MinIO оформлена как типобезопасный record с вложенными record'ами (`Access`, `Bucket`) и декларативной валидацией (`@Valid`, `@NotBlank`). Приложение не стартует с некорректными параметрами MinIO. Это образцовый подход: единый источник правды, типобезопасность без `@Value`-дублирования, ошибка конфигурации видна сразу при старте.

3. **`@ValidConsistentObjectType` — кастомная аннотация Bean Validation** — бизнес-правило "оба пути в операции `move` должны быть одного типа (файл или директория)" оформлено как переиспользуемая аннотация BV с отдельным `ConstraintValidator`. Логика не дублируется ни в контроллере, ни в сервисе. Это правильная точка расширения Jakarta Bean Validation.

4. **Интерфейс `StorageClient` — изоляция MinIO от бизнес-логики** — большинство методов `StorageClient` оперируют только доменными типами (`String`, `InputStream`, `long`), и `StorageServiceImpl` не импортирует классы `io.minio` напрямую. Намерение изолировать хранилище за абстракцией верное. Единственное исключение — `listObjectsByPrefix`, который течёт MinIO-типами через интерфейс; это разбирается отдельно в замечаниях.

5. **Разделение OpenAPI-описания и реализации контроллеров** — все Swagger-аннотации (`@Operation`, `@ApiResponse`, `@Tag`) вынесены в интерфейсы `*ControllerApi`; реализующие классы остаются чистыми. Это снижает шум в production-коде и обеспечивает единое место для документирования HTTP-контрактов.

---

## ЗАМЕЧАНИЯ

### пакет /config

1. **Файл: `application.yml`** — все профили (`dev`, `prod`) сведены в один файл через `---` и `spring.config.activate.on-profile`. Это работает, но стандартный и более чистый подход в Spring Boot — разбить конфигурацию по отдельным файлам: `application.yaml` для общих настроек и `application-{profile}.yaml` для каждой среды. Тогда Spring Boot сам подмешивает нужный файл при активном профиле, не нужны лишние `---` разделители и `on-profile` в каждом блоке, а диффы при ревью затрагивают только один файл конкретной среды.

**Рекомендация:**
```text
src/main/resources/
  application.yaml        # общее для всех сред: jpa, flyway, multipart, springdoc
  application-dev.yaml    # только dev: datasource, redis, minio, show-sql: true, session.timeout: 1d
  application-prod.yaml   # только prod: datasource, redis, minio, show-sql: false, session.timeout: 30m
```

Дополнительно: в dev-блоке стоит `spring.session.timeout: 1000000m` (≈ 2 года) — лучше поставить разумное значение, например `1d`, чтобы случайная активация dev-профиля в проде не оставляла вечные сессии в Redis.

---

### пакет /controller

1. **Класс: `AuthController`, методы: `register()`, `login()`;

интерфейс: `AuthControllerApi`** — оба метода контроллера помечены `@Transactional`/`@Transactional(readOnly = true)`, эти же аннотации задублированы в интерфейсе `AuthControllerApi`. 
Контроллер — транспортный слой; он отвечает за HTTP-координацию и не должен управлять транзакциями. `@Transactional` в интерфейсе ещё хуже: транзакционность — это деталь реализации, не API-контракт. `UserServiceImpl.createUser` уже имеет свой `@Transactional` — именно там и должна находиться граница транзакции. 
Объявление транзакции в контроллере оборачивает в неё также Redis-запросы (`SecurityContextRepository.saveContext`) и вызов `AuthenticationManager.authenticate`, которые к JDBC-транзакции никакого отношения не имеют.

**Рекомендация:** Убрать @Transactional из интерфейса (никогда там не прописывать это) + убрать из контроллера вообще

2. **Класс: `AuthController`** 

Контроллер напрямую зависит от `AuthenticationManager`, `SecurityContextHolder` и `HttpSessionSecurityContextRepository` и выполняет в себе инфраструктурный код Spring Security: формирует `UsernamePasswordAuthenticationToken`, вызывает `authenticationManager.authenticate()`, создаёт `SecurityContext`, сохраняет его в Redis-сессию. 
Это ответственность инфраструктурного/сервисного слоя безопасности, а не транспортного слоя. Контроллер нарушает SRP: он одновременно обрабатывает HTTP и управляет жизненным циклом Security-сессии. 
При необходимости переиспользовать логику аутентификации (WebSocket, gRPC, другой контроллер) весь блок придётся дублировать.

**Рекомендация:** Можно сделать краисвый фасад/сервис и его дергать:
```java
@Service
@RequiredArgsConstructor
public class AuthenticationFacade {

    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository securityContextRepository;

    public UserPrincipal authenticateAndSave(String username, String password,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        var token = new UsernamePasswordAuthenticationToken(username, password);
        Authentication auth = authenticationManager.authenticate(token);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return (UserPrincipal) auth.getPrincipal();
    }
}

// AuthController — только HTTP-координация
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController implements AuthControllerApi {

    private final UserService userService;
    private final UserMapper userMapper; //наверное лучше чтоб и маппера тут не было
    private final AuthenticationFacade authenticationFacade;

    @PostMapping("/sign-up")
    @Override
    public ResponseEntity<SignUpResponse> register(@Valid @RequestBody SignUpRequest request,
                                                   HttpServletRequest httpRequest,
                                                   HttpServletResponse httpResponse) {
        SignUpResponse response = userService.createUser(request);
        authenticationFacade.authenticateAndSave(request.username(), request.password(),
                httpRequest, httpResponse);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PostMapping("/sign-in")
    @Override
    public ResponseEntity<SignInResponse> login(@Valid @RequestBody SignInRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        UserPrincipal principal = authenticationFacade.authenticateAndSave(
                request.username(), request.password(), httpRequest, httpResponse);
        return ResponseEntity.ok(userMapper.toSignInResponse(principal));
    }
}
```

3. **Класс: `StorageController`, метод: `downloadObject()`** 

Контроллер вызывает `StoragePathResolver.isFile(request.path())` для выбора стратегии скачивания (одиночный файл vs ZIP). Это бизнес-решение — определение типа объекта и стратегии его раздачи — принимается в транспортном слое. 
Контроллер получает прямую зависимость от `StoragePathResolver` из пакета `storage`. При изменении логики определения типа (например, добавлении нового типа объекта — архива, симлинка) придётся менять контроллер. Эта логика принадлежит сервису.

**Рекомендация:** Перенести эту логику в сервис. Контроллеру должно быть все равно

4. **Класс: `StorageController`, метод: `uploadObject()`** 

Ручная проверка `files.isEmpty()` и выброс `EmptyFileException` выполняются в теле контроллера. Это ручная валидация в транспортном слое вместо декларативной. 
`StorageController` аннотирован `@Validated`, что позволяет использовать Bean Validation прямо на параметре `@RequestParam`-списка.

**Рекомендация:** Можно убрать if и повесить `@NotEmpty` на лист, джакарта на подлете сама поймает и отвалидирует все. В `GlobalExceptionHandler` не забудь добавить `ConstraintViolationException` (ниже будет описано подробнее)

5. **Класс: `DirectoryController`, метод: `createDirectory()`** 

При создании новой директории возвращается `ResponseEntity.ok()` (HTTP 200). По семантике HTTP, успешное создание нового ресурса должно сопровождаться статусом `201 Created`. 
HTTP 200 означает успешное выполнение операции над уже существующим ресурсом, а не его создание

**Рекомендация:** Лучше возвращать 201, так правильнее семантически

6. **Класс: `UserController`, метод: `me()`** 

DTO `UserResponse` создаётся вручную прямо в контроллере: `new UserResponse(userPrincipal.getUsername())`. 
При расширении ответа (добавлении `id`, `email`, `createdAt`) придётся менять контроллер. Маппинг данных — не ответственность транспортного слоя. `UserMapper` уже существует в проекте и используется для аналогичных задач.

**Рекомендация:** Добавь маппер сюда, зачем руками

---

### пакет /dto

1. **Интерфейс: `StorageObjectResponse`; записи: `StorageFileResponse`, `StorageDirectoryResponse`** 

Поле `type()` имеет тип `String`. Значение заполняется через `ObjectType.FILE.name()` и `ObjectType.DIRECTORY.name()` в `StorageServiceImpl` в 7+ местах. Это loose typing: нет статической проверки, что поле содержит допустимое значение. При добавлении нового типа объекта (например, `SYMLINK`) необходимо найти и обновить все места с `.name()`. Jackson автоматически сериализует `enum` в строку — смысла в ручном вызове `.name()` нет.

**Рекомендация:** Замени `String type();` на `ObjectType type();`. Оно и так под капотом сразу будет брать .name, если нужно другое значение, то можно сделать @JsonConstrucor (или как-то так, я забылч честно) и там будет метод который будет по твоей логике десериализацию делать

2. **Записи: `SignInRequest`, `SignUpRequest`** 

Ограничение `@Size(max = 20)` на поле `password`. OWASP и NIST SP 800-63B рекомендуют не ограничивать максимальную длину пароля или устанавливать её не менее 64 символов. Пароли хранятся через BCrypt, хеш которого всегда имеет фиксированный размер 60 символов независимо от длины входной строки — т.е. ограничение длины пароля не даёт никакого технического преимущества, но снижает безопасность: пользователи не могут использовать надёжные парафразы.

**Рекомендация:** Поднять лимит

---

### пакет /exception

1. **Класс: `StorageSearingException`** 

Опечатка в имени класса: "Searing" вместо "Searching" 

**Рекомендация:** Поправить очепятку

2. **Класс: `GlobalExceptionHandler`** 

Семь storage-исключений (`StorageMovementException`, `StorageDownloadingException`, `StorageCopyingException`, `StorageUploadException`, `StorageCreatingException`, `StorageObjectDeletionException`, `StorageAccessException`) не имеют явных обработчиков и попадают в generic `handleException(Exception.class)`. 
Все они возвращают HTTP 500 с `errorId` — без семантической классификации и без контекста ошибки в логе. Клиент не может отличить "файл не удалось скачать" от "БД упала". 
Нет иерархии исключений, что делает добавление единого обработчика невозможным без перечисления всех 7 классов.

**Рекомендация:** Ввести общий базовый класс для всех storage-исключений и один обработчик для всех storage-исключений

3. **Класс: `GlobalExceptionHandler`** — отсутствует обработчик `jakarta.validation.ConstraintViolationException`. 

`StorageController` и `DirectoryController` аннотированы `@Validated` на уровне класса, что активирует AOP-based метод validation. При добавлении constraint-аннотаций (`@NotBlank`, `@Pattern`) непосредственно на `@RequestParam`-параметрах Spring бросит `ConstraintViolationException`, а не `MethodArgumentNotValidException`. 
Без явного обработчика такой запрос попадёт в generic `handleException(Exception.class)` и вернёт HTTP 500 вместо HTTP 400. Обработчик должен присутствовать проактивно, пока `@Validated` стоит на контроллерах.

**Рекомендация:** добавить обработчик ConstraintViolationException

---

### пакет /mapper

1. **Интерфейс: `UserMapper`**, аннотация: `@Mapping(target = "withPassword", ignore = true)` 

MapStruct видит метод `withPassword(String)`, сгенерированный Lombok `@With` на поле `password` в `User`, как потенциальный "setter" и пытается смаппить в него значение из `SignUpRequest`. Требуется явное `ignore = true` для подавления. Это симптом конфликта двух кодогенераторов: Lombok `@With` семантически не является setter'ом, но синтаксически похож. 
Дополнительная проблема: `toEntity` создаёт `User` с `null` паролем, а пароль устанавливается отдельно через `.withPassword(encoder.encode(...))` в сервисе — логика инициализации сущности размазана между маппером и сервисом.

**Рекомендация:** Убрать @With из User и UserMapper — убрать toEntity. Можно в маппер запихнуть PasswordEncoder и сразу чтоб пароль енкодить и класть в User. Или передавать 2м параметром (так наверное даже лучше)

---

### пакет /service

1. **Интерфейс: `UserService`** — методы интерфейса объявлены с `@Transactional` и `@Transactional(readOnly = true)`. 

Транзакционность — это деталь реализации, не API-контракт. Объявляя `@Transactional` в интерфейсе, разработчик вносит реализационное ограничение в абстракцию: 
любая альтернативная реализация `UserService` вынуждена либо нарушить ожидания, либо слепо следовать транзакционной семантике. Кроме того, Spring проксирует `@Transactional` через AOP на классах, не на интерфейсах — на интерфейсе аннотация работает только если используется JDK dynamic proxy (не CGLIB).

**Рекомендация:** убрать @Transactional из интерфейса

2. **Интерфейс: `StorageService`** — сигнатуры методов `uploadObject(List<MultipartFile> files, ...)`, `downloadFile(...) : Resource`, `downloadFolder(..., OutputStream outputStream)` содержат типы из web/Spring инфраструктурного слоя: `MultipartFile` (`org.springframework.web.multipart`), `Resource` (`org.springframework.core.io`), `OutputStream`. 

Сервисный интерфейс — это контракт бизнес-логики; он не должен знать о деталях HTTP-транспорта. 
Последствия: 
- (1) `StorageServiceImpl` нельзя протестировать без Spring Web контекста; 
- (2) сервис невозможно переиспользовать вне HTTP-контекста; 
- (3) нарушается принцип инверсии зависимостей — бизнес-слой зависит от инфраструктурных типов.

**Рекомендация:** Доменная абстракция для загружаемого файла — без зависимости на Spring Web
```java
public record FileUploadData(
        String originalFilename,
        InputStream content,
        long size,
        String contentType
) {}
```

3. **Класс: `StorageServiceImpl`, метод: `uploadObject()`, вспомогательный: `isDirectoryFiles()`** 

Тип загружаемого объекта определяется эвристикой `files.size() > 1`: более одного файла = директория. 
Это ненадёжно: если пользователь загружает ровно два независимых файла в один запрос — они будут ошибочно классифицированы как директория; 
если директория содержит один файл — будет классифицирована как одиночный файл. Намерение клиента нигде явно не выражено в запросе.

**Рекомендация:** Я если честно не понимаю как это работает, возможно тут и все ок, но выглядит не правильно. Хотя и на деплое все норм работает

4. **Класс: `StorageServiceImpl`, метод: `moveObject()`** 

Для файлов после `storageClient.moveFile(oldObjectName, newObjectName)` вызывается `storageClient.getFileSize(newObjectName)`. Внутри: `getFileSize` → `getObjectData` → `minioClient.statObject` — это отдельный сетевой запрос к MinIO. Размер файла при перемещении не меняется.
Можно получить его до перемещения через уже имеющийся `storageClient.getObjectData(oldObjectName)` — единый запрос вместо двух.

**Рекомендация:**
```java
@Override
public StorageObjectResponse moveObject(String from, String to, long userId) {
    String oldObjectName = StoragePathResolver.getObjectName(from, userId);

    if (StoragePathResolver.isFile(oldObjectName)) {
        ensureFileExists(oldObjectName);
        // Получить размер ДО перемещения — один round-trip вместо двух
        long size = storageClient.getFileSize(oldObjectName);

        String newObjectName = StoragePathResolver.getObjectName(to, userId);
        ensureFileNotExist(newObjectName);
        storageClient.moveFile(oldObjectName, newObjectName);

        return new StorageFileResponse(
                StoragePathResolver.getViewFilePath(newObjectName, userId),
                StoragePathResolver.getFileName(newObjectName),
                size,
                ObjectType.FILE
        );
    }
    // ... остальная логика без изменений
}
```

5. **Класс: `StorageServiceImpl`** — создание `StorageFileResponse` и `StorageDirectoryResponse` дублируется в 7+ местах: `uploadObject`, `getObjectData`, `moveObject`, `searchObject`, `getDirectoryContents`, `createDirectory`. 

Одни и те же аргументы `StoragePathResolver.getViewFilePath(...)`, `StoragePathResolver.getFileName(...)`, `ObjectType.FILE` повторяются в каждом методе. При изменении структуры response DTO (добавлении нового поля) придётся вручную найти и обновить все 7+ мест. Логика маппинга из domain-данных в DTO не централизована.

**Рекомендация:** Что мешает создать Mapper? Сделай 1 маппер и дергай его везде

---

### пакет /storage

1. **Интерфейс: `StorageClient`, метод: `listObjectsByPrefix()`** 

Метод возвращает `Iterable<Result<Item>>`, где `Result` и `Item` — типы из пакета `io.minio`. Это утечка реализации через интерфейс: `StorageServiceImpl` вынужден импортировать `io.minio.Result` и `io.minio.messages.Item` чтобы работать с результатами — несмотря на то что зависит только от `StorageClient`. 
Если заменить MinIO на AWS S3 SDK или другой провайдер, придётся менять не только `MinioStorageClient`, но и интерфейс и все вызывающие места. Цель абстракции `StorageClient` полностью обесценивается для этого метода.

**Рекомендация:** Создать тип для элемента списка объектов — без зависимости на io.minio

2. **Класс: `MinioStorageClient`, методы: `moveFile()`, `moveDirectory()`**

Перемещение реализовано как `copyFile + removeFile`. Операция не атомарна: если `removeFile` завершится с ошибкой после успешного `copyFile`, файл окажется в двух местах одновременно. 
Для `moveDirectory` ситуация критичнее: при ошибке на середине цикла часть файлов уже в новом месте, часть осталась в старом — данные в inconsistent state. Нет механизма компенсации и rollback.

**Рекомендация:** MinioStorageClient.moveDirectory — сначала скопировать всё, потом удалить всё

3. **Класс: `StoragePathResolver`, метод: `getObjectName()`** 

Метод просто конкатенирует `getUserDirectory(userId) + path` без какой-либо санитизации входного `path`. Если клиент передаст путь с `..` компонентами (например, `path = "../user-2-files/secret.txt"`), итоговый ключ MinIO будет `user-1-files/../user-2-files/secret.txt`. 
MinIO хранит S3-ключи как литеральные строки и не нормализует их — поэтому прямой доступ к чужим файлам невозможен. 
Однако: 
- (1) строковые операции в других методах `StoragePathResolver` (`substring`, `lastIndexOf('/')`) ведут себя непредсказуемо с `..` в пути; 
- (2) часть S3-совместимых клиентов/прокси нормализуют URL-пути до отправки запроса. Входные пути должны валидироваться на уровне DTO.

**Рекомендация:** Добавить валидацию через @Pattern в DTO-запросах
```java
@Pattern(regexp = "^(?!\\.\\.)(?!.*/\\.\\.)(?!.*\\.\\.$)[^/].*")
```

4. **Класс: `MinioStorageClient`, метод: `isDirectoryExist()`** 

Присутствует проверка `objects != null` перед `objects.iterator().hasNext()`. Метод `minioClient.listObjects()` гарантированно возвращает `Iterable` (никогда не `null`) — это часть контракта MinIO SDK. 
Лишняя null-проверка вводит ложное предположение о nullable-возврате, что вводит в заблуждение читателей кода и намекает на ненадёжность API там, где её нет.

**Рекомендация:**  убрать `objects != null &&`

---

## РЕКОМЕНДАЦИИ

1. **Вынести логику аутентификации и создания сессии из контроллера.** `AuthController` содержит Spring Security инфраструктурный код (`AuthenticationManager`, `SecurityContextHolder`, `HttpSessionSecurityContextRepository`). Создать `AuthenticationFacade` или `SessionAuthenticationService`, который инкапсулирует весь Security-lifecycle. Контроллер должен только вызывать этот сервис и формировать HTTP-ответ.

2. **Очистить `StorageService` интерфейс от web-типов.** Заменить `MultipartFile` на доменную абстракцию `FileUploadData`, убрать `Resource` и `OutputStream` из сигнатур сервиса. Конвертация HTTP-типов в доменные должна происходить в контроллере до вызова сервиса. Это ключевой шаг для тестируемости бизнес-логики без Spring Web контекста.

3. **Ввести иерархию storage-исключений с базовым классом `StorageException`.** Создать общий родитель для всех `Storage*Exception`, добавить единый `@ExceptionHandler(StorageException.class)` в `GlobalExceptionHandler`. Это устранит семь необработанных исключений, которые сейчас падают в generic handler, и позволит централизованно логировать storage-ошибки с контекстом.

4. **Устранить утечку MinIO-типов из интерфейса `StorageClient`.** Метод `listObjectsByPrefix` возвращает `Iterable<Result<Item>>` из `io.minio`. Заменить на доменный `List<StorageObjectInfo>`. Это сделает интерфейс действительно независимым от MinIO и позволит протестировать `StorageServiceImpl` с простым in-memory моком.

5. **Централизовать создание response DTO через маппер.** Создать `StorageResponseMapper` с методами `toFileResponse` и `toDirectoryResponse`. Исключить дублирование `new StorageFileResponse(...)` / `new StorageDirectoryResponse(...)` в 7+ местах `StorageServiceImpl`. Изменение структуры DTO будет требовать правки в одном месте.

6. **Добавить компенсирующую логику в move-операции.** `moveFile` и `moveDirectory` реализованы как `copyFile + removeFile` без какого-либо rollback: при падении `removeFile` файл остаётся в двух местах. Для директории ситуация критичнее — при ошибке в середине цикла данные в inconsistent state. Нужно либо сначала копировать всё, потом удалять всё (с компенсацией при ошибке на фазе копирования), либо логировать и алертить на такие ситуации.

7. **Разбить `application.yml` по профильным файлам.** Вместо одного файла с `---` разделителями использовать `application.yaml` + `application-dev.yaml` + `application-prod.yaml`. Spring Boot подмешивает нужный файл автоматически — убираются дублирующиеся блоки и диффы при ревью затрагивают только один файл среды.

8. **Убрать `@Transactional` из интерфейсов `UserService` и `AuthControllerApi`.** Транзакционность — деталь реализации, не контракт. `@Transactional` должен быть только на классах реализации (`UserServiceImpl`). В интерфейсах аннотация создаёт ложное ощущение контракта и может не сработать при переходе с JDK proxy на CGLIB.

---

## ИТОГ

Проект написан на уровне выше среднего для учебного задания: прослеживается разделение на слои, абстракция хранилища через `StorageClient`, образцовый `@ConfigurationProperties` для MinIO, кастомная Bean Validation аннотация. Код читается и структурирован.

Главная системная проблема — несоблюдение изоляции слоёв в нескольких ключевых точках: `AuthController` содержит Spring Security инфраструктурный код и `@Transactional`; `StorageService` интерфейс тянет за собой Spring Web типы (`MultipartFile`, `Resource`, `OutputStream`); `StorageClient` интерфейс утекает MinIO-специфичные типы через сигнатуру `listObjectsByPrefix`.
Это снижает тестируемость и переиспользуемость компонентов. Дополнительно: маппинг response DTO размазан по всему `StorageServiceImpl`, семь storage-исключений не обрабатываются явно, опечатка в имени `StorageSearingException`.

Для роста: детально изучить принцип инверсии зависимостей (DIP) — почему web-типы в сервисном интерфейсе это нарушение архитектурных границ и как это исправляется через доменные DTO; изучить атомарность составных операций в объектных хранилищах и паттерн компенсирующих транзакций.
