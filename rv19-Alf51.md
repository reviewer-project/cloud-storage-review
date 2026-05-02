[Alf51/cloud-storage](https://gitlab.com/Alf51/cloud-storage)

[Ревью сделано в рамках учебной подписки](https://zhukovsd.it/services/student-subscription/)

## ХОРОШО

1. **Facade Pattern.** `FileStorageFacade` — единственная точка входа в storage-домен. Контроллер не знает ни о Minio, ни о метаданных — только о фасаде.

2. **Интерфейсы для сервисов.** `CloudStorageService`, `FileMetaDataService`, `FileStorageFacade` вынесены в `/storage/api`. Реализации спрятаны в `/storage/impl` — контракт отделён от реализации.

3. **JDBC для рекурсивных CTE-запросов.** `WITH RECURSIVE` через JPQL недоступен — использование `NamedParameterJdbcTemplate` в `StoragePathRepositoryImpl` архитектурно оправдано.

4. **Интеграционные тесты с Testcontainers.** `BaseConfigIntegrationTest` поднимает реальные PostgreSQL, Minio, Redis. Конфигурация переиспользуется через базовый класс — хороший фундамент.

5. **`StreamingResponseBody` для скачивания.** Файл передаётся напрямую из Minio в ответ без буферизации в памяти — единственно верный подход для файлового хранилища.

---

## ЗАМЕЧАНИЯ

### пакет /config

**1. `SessionConfig` использует `@Value` вместо `@ConfigurationProperties`**

Класс: `SessionConfig`

Конфигурация Redis читается через три `@Value`, хотя для MinIO уже есть `@ConfigurationProperties`. Два разных механизма для одной задачи — нарушение единообразия. При рефакторинге легко пропустить `@Value`-поля. Конфиг-класс с `@Value`-состоянием нельзя создать в тесте без Spring-контекста.

**Рекомендация:** создать `RedisProperties` по аналогии с `MinioProperties`.

---

### пакет /common

**2. `GlobalControllerAdvice` не обрабатывает `ConstraintViolationException`**

Класс: `common.controllers.GlobalControllerAdvice`

`@NotBlank` на `@RequestParam` бросает `ConstraintViolationException`, а не `MethodArgumentNotValidException`. В `GlobalControllerAdvice` обрабатывается только второй. Результат: невалидный `?path=` вернёт клиенту **500** вместо 400.

**Рекомендация:**

```kotlin
@ExceptionHandler(ConstraintViolationException::class)
fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ErrorAnswer> {
    val message = e.constraintViolations.joinToString("; ") { "${it.propertyPath}: ${it.message}" }
    LOG.warn("CONSTRAINT_VIOLATION: {}", message)
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorAnswer(message))
}
```

**3. `GlobalControllerAdvice` не обрабатывает непредвиденные исключения**

Класс: `common.controllers.GlobalControllerAdvice`

Нет catch-all обработчика `Exception`. `MinioException` или любой другой необработанный runtime уйдёт в стандартный Spring error handler — клиент не получит твой структурированный `ErrorAnswer`.

**Рекомендация:** добавить `@ExceptionHandler(Exception::class)`, логировать как `error`, возвращать 500 с `ErrorAnswer("Internal server error")`.

**4. `ErrorValidatorMessage` — лишний Spring-бин для одной строки**

Класс без состояния, без зависимостей, без lifecycle — не нужен как Spring-бин. Единственная причина его существования — выделить форматирование ошибок. Но ради одной строки создавать `@Component`, инжектировать его в `GlobalControllerAdvice` и регистрировать в Spring-контексте — избыточно. 
В Kotlin это делается через extension function или top-level функцию.

**Рекомендация:** удалить класс, вынести логику как extension function прямо в файл `GlobalControllerAdvice.kt`:

```kotlin
private fun BindingResult.toErrorMessage(): String =
    fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }

// Использование в handler:
val message = exception.bindingResult.toErrorMessage()
```

**6. `companion object` для логгеров — Java-стиль в Kotlin**

`companion object` — это Java-`static`, перенесённый в Kotlin 1:1. В Kotlin идиоматичная замена `static`-поля — top-level `private val`. `companion object` создаёт лишний объект в байткоде (`ClassName$Companion`), требует отдельного блока и делает файл тяжелее для чтения. Для хранения логгера — избыточно.

Тянуть библиотеку ради логгера не нужно — достаточно один раз определить inline-функцию в проекте:

```kotlin
// utils/LoggerExtensions.kt — один раз на весь проект
inline fun <reified T : Any> logger(): Logger =
    LoggerFactory.getLogger(T::class.java)
```

**Рекомендация:** убрать `companion object`, вынести логгер как top-level `private val`:

```kotlin
private val log = logger<ClassName>()

@Service
class ClassName(...) {
    // companion object не нужен
}
```

Это решает и баг из замечания 6 (`::class.toString()`), и баг из замечания 27 (`this::class.java` в companion), и убирает Java-стиль разом во всём проекте.

**7. Бизнес-исключения наследуются от `RuntimeException` напрямую**

Все бизнес-исключения наследуют `RuntimeException` напрямую. В `GlobalControllerAdvice` нельзя поймать их одним обработчиком — нужен отдельный `@ExceptionHandler` на каждый класс. При добавлении нового исключения легко забыть добавить обработчик — оно уйдёт в catch-all с 500.

**Рекомендация:** создать базовый класс `CloudStorageException`:

```kotlin
sealed class CloudStorageException(message: String) : RuntimeException(message)

class DataNotUniqueException(message: String) : CloudStorageException(message)
class ResourceNotFoundException(message: String) : CloudStorageException(message)
// остальные — аналогично
```

Тогда в `GlobalControllerAdvice` достаточно одного обработчика:

```kotlin
@ExceptionHandler(CloudStorageException::class)
fun handleDomainException(e: CloudStorageException): ResponseEntity<ErrorAnswer> =
    ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorAnswer(e.message ?: "Error"))
```

`sealed class` гарантирует, что все наследники объявлены в одном модуле — компилятор не даст создать исключение вне иерархии.

---

### пакет /person/entity

**8. `Person` — cross-domain зависимость на `StorageObject`**

`Person` импортирует `StorageObject` (домен storage). Поле нигде не используется — мёртвая связь, которая создаёт циклическую зависимость между доменами. Зависимость должна быть однонаправленной: `StorageObject → Person`.

**Рекомендация:** удалить `@OneToMany` из `Person`. Если нужен список объектов пользователя — запрос к `StorageRepository`.

**9. `Person` — nullable поля без необходимости**

`login` и `password` — `String?`, хотя в `PersonDetails` ты сам бросаешь `IllegalStateException` при их null. Null здесь не смысловое состояние, а артефакт no-args конструктора JPA.

**Рекомендация:** плагин `kotlin("plugin.jpa")` у тебя подключён — он генерирует no-args конструктор автоматически. Убери nullable: `var login: String`, `var password: String`, `@Column(nullable = false)`.

```kotlin
@Entity
@Table(name = "Persons")
class Person(
    @Column(name = "login", nullable = false)
    var login: String,

    @Column(name = "password", nullable = false)
    var password: String,

    @Column(name = "name")
    var name: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null

    @ElementCollection(targetClass = Role::class, fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "users_role", joinColumns = [JoinColumn(name = "id_client")])
    var roles: Set<Role> = HashSet()
}
```

---

### пакет /person

**10. `PersonService` нарушает SRP**

Один класс: реализует `UserDetailsService` (Spring Security инфраструктура), регистрирует пользователей (бизнес-логика) и читает `SecurityContextHolder` (web-слой). Три разные ответственности — класс невозможно протестировать без Spring Security контекста.

**Рекомендация:** выделить `UserDetailsServiceImpl : UserDetailsService` с единственным методом `loadUserByUsername`. `PersonService` оставить только с `register()`. Методы `getCurrentUserId()` и `getLogin()` — убрать (см. замечание 11).

**11. `PersonService.getCurrentUserId()` / `getLogin()` — `SecurityContextHolder` в сервисе**

Сервисный слой не должен знать о `SecurityContextHolder`. Из-за этого каждый сервис, которому нужен `personId`, тянет зависимость от web-инфраструктуры.

**Рекомендация:** извлекать пользователя в контроллере через `@AuthenticationPrincipal`, передавать `personId` явным параметром:

```kotlin
@GetMapping("/directory")
fun getDirInfoContent(
    @RequestParam path: String,
    @AuthenticationPrincipal principal: PersonDetails
): ResponseEntity<List<ResourceSuccessUploadedDto>> {
    val dto = ResourceMetaDataDto(principal.person.id, path)
    return ResponseEntity.ok(fileStorageFacade.getDirInfoContent(dto))
}
```

**12. `PersonService` и `AuthService` — нет интерфейсов**

В storage-домене все сервисы скрыты за интерфейсами. `PersonService` и `AuthService` — нет. Нарушение единообразия: потребители жёстко привязаны к реализации. При замене реализации или написании unit-теста без Spring-контекста — нет контракта для подстановки.

**Рекомендация:** создать интерфейсы `PersonService` и `AuthService`, текущие классы переименовать в `PersonServiceImpl` и `AuthServiceImpl` (или название оставить таким же, а интерфейсы называть с припиской Api (конкретно у нас на работе именно 2 стиль)). Инжектировать везде интерфейс.

**13. Java-стиль в Kotlin: `HashSet()`, `@Transactional(readOnly = false)`**

Классы: `person.entity.Person`, `person.PersonService`

В `Person` используются Java-коллекции напрямую:
```kotlin
var roles: Set<Role> = HashSet()
var storageObject: MutableSet<StorageObject> = HashSet()
```
В `PersonService` — явный дефолт, который не нужно писать:
```kotlin
@Transactional(readOnly = false)
fun register(...) { ... }
```

Kotlin предоставляет собственные фабричные функции для коллекций. `readOnly = false` — дефолтное значение, явное указание только создаёт шум.

**Рекомендация:**

```kotlin
// Person.kt
var roles: Set<Role> = mutableSetOf()
var storageObject: MutableSet<StorageObject> = mutableSetOf()

// PersonService.kt — просто:
@Transactional
fun register(...) { ... }
```

---

### пакет /person/dto

**14. `UsernameDto` — неясное именование, непонятно request или response**

`UsernameDto` возвращается из `PersonController.getLoginUser()` и из `AuthController.makeLogin()` — это response. Но имя не говорит об этом. Читая сигнатуру метода, невозможно понять, идёт этот объект к клиенту или от него.

**Рекомендация:** переименовать в `UsernameResponseDto`. Общее правило для всех DTO: суффикс `Request` / `Response` снимает любую двусмысленность. Для красоты лучше еще и пакеты делать request и response. Где будут лежать `....Response/RequestDto`

---

### пакет /security/controller

**15. `AuthController` зависит от `PersonMapper` напрямую**

Контроллер вызывает `personMapper.toAuthClientDto(dto)` для передачи данных в `AuthService`. Маппинг — не ответственность контроллера. При изменении сигнатуры `authPerson()` меняется и контроллер.

**Рекомендация:** добавить в `AuthService` перегрузку, принимающую `RegistrationPersonRequestDto` напрямую, убрать `PersonMapper` из зависимостей контроллера.

### пакет /security/dto

**16. `PersonDto` — не `data class`, мутабельные поля**

`PersonDto` сериализуется в Redis-сессию. Поля `var` — объект аутентификации не должен меняться после создания. Нет `data class` — нет `equals`/`hashCode` по значению. +не понятно request/response это?

**Рекомендация:** `data class PersonDto(val id: Long, val login: String, val password: String, val name: String?, val roles: Set<Role>) : Serializable`

**17. `ResponseMoveResourceDto` / `ResponseTrashResourceDto` — неверное именование**

Оба класса принимаются как `@RequestBody` — они **request**, а не response. Префикс `Response` вводит в заблуждение любого, кто открывает эти файлы.

**Рекомендация:** переименовать в `MoveResourceRequest` и `TrashResourceRequest`.

**18. `AuthClientDto` — нет валидации и неверное именование**

Поля без `@NotBlank` — пустые строки пройдут в Spring Security и вернут клиенту 401 (`BadCredentialsException`) там, где должен быть 400 с ошибкой валидации. Имя `AuthClientDto` не говорит о направлении — это request.

**Рекомендация:** переименовать в `SignInRequest`, добавить валидацию:

```kotlin
data class SignInRequest(
    @field:NotBlank(message = "{validation.not-blank}")
    val username: String,

    @field:NotBlank(message = "{validation.not-blank}")
    val password: String
)
```

---

### пакет /storage/entity

**19. `StorageObject` — UUID генерируется в коде, JPA вызывает `merge` вместо `persist`**

`@GeneratedValue` отсутствует. Spring Data JPA считает сущность новой только если ID == null. Здесь ID никогда не null — при каждом `save()` вызывается `merge()`, который делает лишний SELECT перед INSERT.

**Рекомендация:** использовать `@UuidGenerator` из Hibernate 6:

```kotlin
@Id
@GeneratedValue
@UuidGenerator
@Column(name = "uuid", updatable = false, nullable = false)
var uuid: UUID
```

**20. `StorageRepository` расширяет `StoragePathRepository` — нарушение ISP**

Один интерфейс объединяет JPA (Hibernate) и нативный JDBC — два принципиально разных механизма. При тестировании нужно мокировать всё сразу. Изменение одного из механизмов затрагивает единый интерфейс.

**Рекомендация:** инжектировать `StoragePathRepository` и `StorageRepository` в сервис отдельно, не смешивать их в одном интерфейсе.

**21. SQL-константы в `StoragePathRepositoryImpl` — `val` в `companion object` вместо `private val` на уровне файла**

SQL-строки объявлены как `val` в `companion object`, хотя `companion object` здесь нужен только ради этих констант. В Kotlin идиоматичная замена — `private val` на уровне файла: никакого лишнего объекта в байткоде, меньше вложенности.

**Рекомендация:** удалить `companion object`, вынести SQL-строки как `private val` на уровень файла:

---

### пакет /storage/impl

**22. `FileMetaDataServiceImpl` зависит от `PersonRepository` — cross-domain**

Storage-сервис напрямую импортирует `PersonRepository` из домена person. `PersonRepository` используется только ради `getReferenceById(personId)` — чтобы получить proxy для создания `StorageObject`. Это следствие замечания 8 (двусторонняя связь Person ↔ StorageObject).

**Рекомендация:** добавить в `StorageObject` явное поле `personId: Long` (`@Column`) — им владеет JPA при записи. `@ManyToOne` пометить `insertable = false, updatable = false` — тогда он read-only и не требует объекта `Person` при создании:

```kotlin
@Column(name = "person_id", nullable = false)
var personId: Long,

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "person_id", insertable = false, updatable = false)
var person: Person? = null
```

В сервисе `PersonRepository` больше не нужен — `personId: Long` передаётся напрямую:

```kotlin
// было:
val person = personRepository.getReferenceById(personId)  // cross-domain
val obj = StorageObject(person = person, ...)

// стало:
val obj = StorageObject(personId = personId, ...)  // PersonRepository не нужен
```

**23. `FileMetaDataServiceImpl.saveUploadedObject()` — `saveAll` внутри цикла с условием**

`storageRepository.saveAll(newObjects)` вызывается внутри цикла только при `i == splitPath.lastIndex` — то есть всегда один раз, в конце. Условие внутри цикла скрывает простой факт: сохранение происходит после построения списка.

**Рекомендация:** построить `newObjects` через `map`, вынести `saveAll` за пределы цикла — явно, одной строкой после него.

**24. `getTrash()` и `searchResource()` без `@Transactional(readOnly = true)`**

Read-only методы без транзакционных аннотаций. Hibernate не отключает dirty checking, flush mode остаётся стандартным — лишняя нагрузка. Остальные read-методы в классе помечены `@Transactional` без `readOnly = true` — тот же вопрос.

**Рекомендация:** добавить `@Transactional(readOnly = true)` на все методы-геттеры. Пересмотреть существующие `@Transactional` без `readOnly`.

---

**25. `CloudStorageServiceImpl` — дублирование `try/catch` во всех методах**

Каждый метод оборачивает вызов Minio в одинаковый `try/catch`. `logAndThrowException` убирает дублирование `log + throw` внутри catch, но сам блок `try/catch` всё равно повторяется в каждом методе. 
При этом `getStream()` не может использовать `logAndThrowException` из-за несовместимости возвращаемого типа — пишет catch вручную.

**Рекомендация:** заменить `logAndThrowException` на higher-order функцию `withMinio`, которая инкапсулирует весь `try/catch`:

```kotlin
private fun <T> withMinio(block: () -> T): T =
    try {
        block()
    } catch (e: MinioException) {
        log.warn("Storage error: {}", e.message, e)
        throw e
    }
```

Все методы становятся однострочными, `logAndThrowException` удаляется:

```kotlin
override fun getStream(objectKey: String): InputStream = withMinio {
    minioClient.getObject(
        GetObjectArgs.builder().bucket(minioProperties.bucketName).`object`(objectKey).build()
    )
}
```

**27. `CloudStorageServiceImpl.deleteResource()` — `println()` вместо logger**

`println` идёт в `stdout`, не захватывается logging-фреймворком, не имеет уровня. В production потеряется или уйдёт без контекста.

**Рекомендация:** заменить на нормальный логгер

---

**28. `FileStorageFacadeImpl.upload()` — неполный rollback при ошибке**

При ошибке загрузки в Minio удаляется только листовой файл (`fileMetaDataService.delete(metaDataDto.uuid)`). Но `uploadMetaData()` мог создать промежуточные директории. После rollback в метаданных остаются пустые «призрак»-директории, которых пользователь не создавал.

**Рекомендация:** добавить в `FileMetaDataService` метод `deleteCreatedPath(uuid, personId)`, удаляющий объект и пустые родительские директории. Вызывать его вместо `delete()` при rollback.

---

**29. `DownloadResourceDto` содержит типы Spring MVC**

```kotlin
data class DownloadResourceDto(
    val streamBody: StreamingResponseBody,  // spring-webmvc
    val name: String,
    val mediaType: MediaType                // spring-web
)
```

DTO пересекает границу service → controller и тащит в себе HTTP-абстракции. В тесте или batch-задаче нельзя использовать фасад без spring-webmvc.

**Рекомендация:** заменить Spring-типы на чистые: `inputStreamProvider: () -> InputStream` и `contentType: String`. `StreamingResponseBody` и `MediaType` строить в контроллере из этих данных:

```kotlin
// DTO — без Spring-зависимостей
data class DownloadResourceDto(
    val inputStreamProvider: () -> InputStream,
    val name: String,
    val contentType: String
)

// Контроллер — собирает HTTP-ответ сам
@GetMapping("/download")
fun download(...): ResponseEntity<StreamingResponseBody> {
    val dto = fileStorageFacade.download(...)
    val body = StreamingResponseBody { out ->
        dto.inputStreamProvider().use { it.copyTo(out) }
    }
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(dto.contentType))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${dto.name}\"")
        .body(body)
}
```

---

### пакет /storage/controllers

**30. `StorageController.uploadObjects()` — force unwrap `originalFilename!!`**

`MultipartFile.originalFilename` возвращает `String?`. `!!` вызовет `NullPointerException` если клиент не передал `filename` в `Content-Disposition`. NPE уйдёт наверх без превращения в понятный HTTP-ответ.

**Рекомендация:**

```kotlin
val fileName = it.originalFilename?.takeIf { name -> name.isNotBlank() }
    ?: throw InvalidResourceNameException("File name cannot be null or blank")
```

**31. `@NotBlank` на `@RequestParam` без `@Validated` на контроллере**

Bean Validation на параметрах методов работает только при наличии `@Validated` на классе или методе. Без него `@NotBlank` на `@RequestParam` молча игнорируется — пустой `?path=` пройдёт в сервис без ошибки.

**Рекомендация:** добавить `@Validated` на класс `StorageController`. После этого невалидные запросы будут бросать `ConstraintViolationException` — добавь обработчик в `GlobalControllerAdvice` (замечание 2).

---

### пакет /storage/jobs

**32. `MetaDataCleaner` — `@EnableScheduling` на `@Service`**

`@EnableScheduling` — конфигурационная аннотация уровня приложения. Её место — на `@Configuration`-классе, а не на сервисном бине.

**Рекомендация:** создать `SchedulerConfig` с `@Configuration @EnableScheduling`. Убрать аннотацию из `MetaDataCleaner`.

**33. `MetaDataCleaner` — `jakarta.transaction.Transactional` вместо Spring-варианта**

`jakarta.transaction.Transactional` не поддерживает `readOnly`, `propagation`, `rollbackFor` и не интегрируется с Spring `TransactionManager`.

**Рекомендация:** заменить импорт на `org.springframework.transaction.annotation.Transactional` везде в проекте.

---

**34. Пакеты названы во множественном числе**

Пакеты `controllers`, `exceptions`, `jobs` нарушают соглашение Java/Kotlin: имена пакетов всегда в единственном числе. Это стандарт, закреплённый в официальном Java Code Conventions и Kotlin Coding Conventions.

**Рекомендация:** переименовать: `controllers` → `controller`, `exceptions` → `exception`, `jobs` → `job`.

---

## РЕКОМЕНДАЦИИ

1. **Разделить пакеты `/security` и `/person`.** `RegistrationPersonRequestDto`, `PersonDto` лежат в `/security/dto`, хотя относятся к домену person. Перенести их в `/person/dto`. В `/security` оставить только `AuthClientDto` и `PersonDetails`.

2. **Убрать `SecurityContextHolder` из сервисов, перейти на `@AuthenticationPrincipal`.** Это единственное изменение, которое сделает `PersonService` и `StorageController` тестируемыми без Spring Security контекста.

3. **Унифицировать конфигурацию через `@ConfigurationProperties`.** `MinioProperties` — правильный образец. Redis и CORS привести к тому же стандарту. Итог: проверка типов на старте, автодополнение в IDE, единое место изменений.

4. **Добавить unit-тесты для сервисного слоя.** Интеграционные тесты с Testcontainers — медленные. `FileMetaDataServiceImpl` содержит нетривиальную логику (обход путей, нормализация, конфликты) — это всё легко покрывается быстрыми unit-тестами с замоканными репозиториями.

5. **Вынести логику формирования ключей Minio в `CloudStorageService`.** Строка `user-$personId-files/` формируется в `FileStorageFacadeImpl`. Структура ключей — деталь хранилища, фасад не должен о ней знать. Методы `putInFileStorage`, `getStream`, `deleteResource` должны принимать `personId` и `uuid` — сборку ключа делать внутри.

6. **Устранить дублирование доменов на уровне зависимостей.** `FileMetaDataServiceImpl → PersonRepository` и `Person → StorageObject` — это два проявления одной проблемы: домены знают друг о друге. Убрав `@OneToMany` из `Person` и добавив `personId: Long` в `StorageObject`, ты разорвёшь обе связи.

7. **Применить единый стандарт именования DTO.** Сейчас в проекте смешаны разные конвенции: `RegistrationPersonRequestDto` (суффикс Request), `ResponseMoveResourceDto` (Response на request-объекте), `UsernameDto` (без суффикса вообще), `AuthClientDto` (без направления). Правило: входящие → `*Request`, исходящие → `*Response`. Применить ко всем классам.

8. **Привести логгеры к единому Kotlin-идиоматичному стилю.** Сейчас в проекте смешаны разные способы: `this::class.java` (даёт имя Companion), `::class.toString()` (даёт "class " prefix), `ClassName::class.java` (верно, но в companion). Единый стандарт — top-level `private val`:

---

## ИТОГ

Проект на хорошем уровне для учебного: правильный Facade, интерфейсы для сервисов в storage-домене, нативный SQL для CTE, Testcontainers в тестах. Это не стандарт для большинства таких работ.

**Сильные стороны:** архитектура storage-домена (facade + interfaces + JDBC для сложных запросов), интеграционные тесты. Отдельного внимания заслуживает решение хранить метаданные файлов в PostgreSQL отдельно от самих файлов в Minio: поиск, листинг директорий, корзина и фильтрация работают через SQL без обращения к объектному хранилищу. Большинство учебных реализаций файловых хранилищ этого не делают и либо обходят Minio при каждом запросе, либо вовсе не реализуют поиск и корзину

**Слабые стороны:** `PersonService` нарушает SRP и знает о `SecurityContextHolder`; домены person и storage зависят друг от друга; `PersonService` и `AuthService` без интерфейсов; `@Value` вместо `@ConfigurationProperties` для Redis и CORS; `@NotBlank` на `@RequestParam` без `@Validated` не работает; именование DTO непоследовательно; `AuthClientDto` без валидации; два реальных бага с логгерами (`this::class.java` в companion, `::class.toString()` с "class " префиксом); `ErrorValidatorMessage` — лишний бин для одной строки.

**Куда расти:** изоляция доменов, `@AuthenticationPrincipal` вместо `SecurityContextHolder` в сервисе, единый стандарт именования DTO и логгеров, Kotlin-идиомы вместо Java-стиля, unit-тесты для бизнес-логики.

**Вопрос по архитектуре:** проект организован по доменам (`person`, `storage`, `security`), а не по слоям (`controller`, `service`, `repository`). Это осознанный выбор? Доменная структура оправдана, когда домены действительно изолированы и не знают друг о друге — но в этом проекте `Person` зависит от `StorageObject`, `FileMetaDataServiceImpl` тянет `PersonRepository`, `AuthController` инжектирует `PersonMapper`. То есть граница между доменами размыта, а преимущества доменной структуры не реализованы. Если изоляцию доменов не планируется доводить до конца, классическая слоистая архитектура была бы проще и честнее для проекта такого масштаба.
