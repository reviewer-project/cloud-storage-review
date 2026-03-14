[UmarShabazov-file-storage](https://github.com/UmarShabazov/file-storage)

## ХОРОШО

1. **Двухуровневая абстракция хранилища.**
   Сделаны два интерфейса: `StorageRepository` (бизнес-уровень, работает с `ownerId` и путями) и `ObjectStorageAdapter` (адаптер к MinIO с объектными ключами). Сервис зависит только от `StorageRepository` и не знает о MinIO. В ТЗ требовался один интерфейс, но разделение упрощает тестирование и замену хранилища.

2. **`PathService` вынесен в отдельный сервис.**
   Вся логика работы с путями (нормализация, валидация, разбор) собрана в одном месте. ТЗ этого не требовало — её можно было разбросать по сервисам. Отдельный компонент лучше соблюдает принцип SRP.

3. **Индексы вынесены в `V2__indexes.sql`.**
   Индексы `(owner_id, parent_path)` и `(owner_id, name)` соответствуют реальным запросам репозитория. Они добавлены осознанно, а не «на всякий случай». Отдельная миграция для индексов — нормальная практика: схема и оптимизация разделены.

4. **`TestStorageConfig` с in-memory реализацией.**
   В тестах используется собственная in-memory реализация `ObjectStorageAdapter` на `ConcurrentHashMap`, а не `@MockBean`. Благодаря этому сервисы тестируются без MinIO. `@Profile("!test")` исключает `MinioStorageSDK` из тестового контекста.

5. **Метаданные хранятся в PostgreSQL.**
   Таблица `resources` содержит путь, имя, тип и размер ресурса. Поэтому листинг папок и поиск выполняются обычными SQL-запросами по индексам, без `listObjects` в MinIO. MinIO хранит только файлы, PostgreSQL — структуру.

6. **CHECK-constraint в БД.**
   В схеме добавлено правило: у `FILE` размер обязателен, у `DIRECTORY` — всегда `NULL`. ТЗ этого не требовало, но это защищает инварианты даже при ошибке в коде.

---

## ЗАМЕЧАНИЯ

### пакет /config

2. Класс: `JacksonConfig`

`JacksonConfig` создаёт `ObjectMapper` через `new ObjectMapper()` — без модулей Spring Boot, без поддержки Java Time API, без настроек сериализации. Аннотация `@ConditionalOnMissingBean(ObjectMapper.class)` выглядит как защита, но работает наоборот: Spring Boot's `JacksonAutoConfiguration` регистрирует полностью настроенный `ObjectMapper` раньше, поэтому этот бин никогда не будет зарегистрирован. Класс не несёт никакой функции, но при этом создаёт ложное впечатление, что `ObjectMapper` где-то переопределён.

Если `JacksonAutoConfiguration` когда-либо отключат или `@ConditionalOnMissingBean` сработает неожиданно, в контекст попадёт «голый» маппер без модулей, что приведёт к ошибкам сериализации `LocalDate`, `Optional` и других типов. Это скрытая мина.

**Рекомендация:** Удалить JacksonConfig полностью
   
1. Класс: `MinioConfig`, метод `minioClient()` + класс: `MinioStorageSDK`, конструктор.

Конфигурация MinIO разделена между двумя классами без единого `@ConfigurationProperties`. `MinioConfig` читает `minio.endpoint`, `minio.access-key`, `minio.secret-key` через `@Value` в параметрах `@Bean`-метода. `MinioStorageSDK` (физически находится в `repository/storage`) читает `minio.bucket` через `@Value` в конструкторе. Единого класса, агрегирующего все Minio-свойства, нет.

Конфигурация — это инфраструктурный слой. `@Value` в нескольких классах создаёт связанность: при переименовании ключа `minio.bucket` придётся найти все `@Value("${minio.*}")` по всему проекту. Нет compile-time проверки имён. `@ConfigurationProperties` даёт типобезопасность, единый источник правды, автодополнение в IDE и возможность валидировать конфигурацию при старте.

**Рекомендация:** Создать единый класс конфигурации через `@ConfigurationProperties`

3. Класс: `SecurityConfig`, метод `userDetailsService()`.

`UserDetailsService` реализован прямо внутри `SecurityConfig` в виде анонимного класса. Для этого `UserRepository` инжектируется в security-конфигурацию. Конфигурация безопасности напрямую зависит от персистентного слоя.

Security-конфиг должен знать только об интерфейсах Spring Security. Смешение конфигурационного и data access слоёв нарушает разделение ответственности: при смене источника пользователей (LDAP, другая БД, кэш) придётся менять `SecurityConfig`. `UserDetailsService` — это самостоятельная ответственность с отдельным жизненным циклом.

**Рекомендация:** Вынести UserDetailsService в отдельный @Service

---

### пакет /controller

1. Класс: `AuthController` — расположение в подпакете `common`.

`AuthController` вынесен в `controller/common/`, хотя никакого смысла в этом подпакете нет: в нём находится ровно один класс. `UserController` и `ResourceController` лежат прямо в `controller`, а `AuthController` — в `controller/common`. Критерий разделения непонятен: что делает `AuthController` "общим"? Остальные контроллеры тоже общие. Пустой подпакет с одним классом только запутывает навигацию по проекту.

**Рекомендация:** Убрать подпакет common

2. Класс: `AuthController`, поле `securityContextRepository`.

`SecurityContextRepository` создаётся через `new HttpSessionSecurityContextRepository()` прямо в поле класса, а не через DI. Это нарушение принципа инверсии зависимостей: объект жёстко прошит, его невозможно заменить, сконфигурировать извне или подменить моком в тестах. Если потребуется другой способ хранения контекста, придётся менять код контроллера.

**Рекомендация:** Объявить SecurityContextRepository как бин в SecurityConfig и инжектировать через конструктор

3. Класс: `AuthController`, метод `registrate()`.

Метод называется `registrate` — это не английское слово. В английском языке существуют `register` (глагол) и `signup` / `signUp` (устоявшийся термин для эндпоинта регистрации). `registrate` — калька с русского "регистрировать", не используемая в профессиональном Java-коде. Это ухудшает читаемость для любого, кто работает с кодом.

Кроме того, `userService.register(dto)` возвращает `UserDTO`, но результат отбрасывается. Затем формируется `new UserDTO(dto.userName())` — DTO строится заново из входного запроса, а не из результата сервиса. Если `register()` когда-нибудь нормализует username (например, `trim()` или `toLowerCase()`), ответ API будет возвращать оригинальное написание из запроса, а не то, что реально сохранено в БД. Расхождение между ответом и данными.

**Рекомендация:** Переименовать метод и использовать возвращаемое значение `register()` из сервиса

4. Класс: `AuthController`, конструктор. Та же проблема в `UserService`, конструктор.

Оба класса используют `@Autowired` на конструкторе. Начиная с Spring 4.3, `@Autowired` на единственном конструкторе избыточна — Spring инжектирует автоматически. В `ResourceController` `@Autowired` уже нет, что создаёт стилистическую несогласованность в рамках одного проекта.

**Рекомендация:** убрать @Autowired

5. Класс: `ResourceController`, поля `PATH_ANY` и `PATH_DIR`.

Regex-константы `PATH_ANY` и `PATH_DIR` в контроллере дублируют логику `PathService`. Синтаксис валидного пути описан в двух местах: regex-паттерны в контроллере и проверки в `PathService.validateRelativePath()`. 
При изменении правил (например, разрешить Unicode в именах) нужно синхронно обновить оба места. Контроллер знает о внутреннем синтаксисе путей — это детали домена, не transport-слоя.

**Рекомендация:** Перенести валидацию пути целиком в PathService, а в контроллере использовать только @NotBlank

6. Класс: `ResourceController`, метод `upload()`, параметр `objects`.

Параметр multipart-файлов называется `@RequestParam("object")`. "object" — крайне перегруженный термин в Java. Клиент, читая документацию API, не может интуитивно понять, что поле называется "object", а не "file" или "files". В ТЗ используется понятие "файлов" (Upload файлов).

**Рекомендация:** Не критично, но лучше чтобы было `@RequestParam("files")`. Это просто удобнее и привычнее читать

---

### пакет /dto

1. Класс: `UserCreateUpdateDTO` — используется одновременно для `/api/auth/sign-up` и `/api/auth/sign-in`.

Один DTO для двух разных операций с разной семантикой нарушает SRP. Имя `UserCreateUpdateDTO` для класса, применяемого при авторизации, семантически некорректно: авторизация — это не "создание" и не "обновление" пользователя. Правила валидации для регистрации и входа могут расходиться: при входе не нужна жёсткая валидация пароля (`@Size(min=5)` делает невозможным вход, если политика паролей когда-нибудь изменится).

**Рекомендация:** Разделить на два DTO + сделать response/request дто, а так же лучше чтобы поле было `String username,` это тоже стандарт, и не нужно дополнительно `JsonProperty`

---

### пакет /entity

1. Классы: `UserEntity` и `ResourceEntity` — все поля, конструкторы, геттеры и сеттеры.

В проекте нет Lombok. Обе сущности содержат значительный объём boilerplate: ~35 строк геттеров/сеттеров в `UserEntity`, ~55 строк в `ResourceEntity`. При добавлении нового поля необходимо вручную добавить геттер и сеттер — это легко забыть. Вся эта механическая работа решается подключением Lombok.

Для JPA-сущностей правильный выбор — `@Getter @Setter` без `@Data`, чтобы не генерировать проблемный `equals()`/`hashCode()` по всем полям.

**Рекомендация:** Не обязательно, но можно добавить в проект lombok, будет гораздо меньше кода, не нужны конструкторы и тд

3. Классы: `UserEntity`, поле `userName` (`@Size(min = 3)`) и `UserCreateUpdateDTO`, поле `userName` (`@Size(min = 2)`).

Jakarta Bean Validation аннотации (`@NotBlank`, `@Size`) вообще не должны находиться на полях JPA-сущности. Сущность — это объект персистентного слоя, её задача — отражать состояние строки в БД. Входная валидация данных — ответственность DTO на границе приложения (контроллер + `@Valid`). Когда `@Size` стоит на сущности, Hibernate подхватывает её через механизм Bean Validation и проверяет ограничения при flush — это скрытое, неожиданное поведение, которое трудно отследить.

Именно из-за этого смешения и возникает видимый симптом: `@Size(min=2)` в DTO пропускает username длиной 2 символа, а `@Size(min=3)` на сущности падает уже внутри транзакции с `jakarta.validation.ConstraintViolationException`. Это исключение не обрабатывается в `ApiExceptionHandler` как 400 — оно попадает в общий `Exception` handler → 500 "Unknown error".

**Рекомендация:** Убрать Bean Validation с полей сущности — там ей не место

---

### пакет /exception

1. Класс: `ApiExceptionHandler`, метод `handleMinio(StorageException)`.

`StorageException` обрабатывается с возвратом `HttpStatus.BAD_REQUEST` (400). `StorageException` оборачивает ошибки инфраструктуры: MinIO недоступен, сеть упала, bucket не создан, объект не удалён. 
HTTP 400 означает "клиент отправил некорректный запрос". Возвращать 400 при инфраструктурной ошибке вводит клиента в заблуждение: запрос был корректным, проблема на стороне сервера. Это осложняет диагностику и мониторинг (клиент будет думать, что ошибся в параметрах).

**Рекомендация:** Лучше поменять на 500 + скрыть сообщение из exception, можно случайно что-то засветить. Лучше что-то типо `Storage error. Please try again later.`

2. Класс: `ApiExceptionHandler` — все `@ExceptionHandler` методы.

Все обрабатываемые исключения — включая ожидаемые бизнес-сценарии `ResourceNotFoundException`, `IllegalArgumentException`, `ConstraintViolationException` — логируются как `log.error()` с полным stack trace. `ERROR` — уровень для неожиданных, критичных сбоев. Ошибка валидации пути, "ресурс не найден", "неверные параметры" — это нормальные рабочие сценарии. Засорение ERROR-логов ожидаемыми событиями создаёт alert fatigue: мониторинг начинает игнорировать ERROR-алерты, потому что они срабатывают постоянно, и реальные проблемы теряются.

**Рекомендация:** 4xx — бизнес-ошибки, WARN без stack trace. 5xx — неожиданные ошибки, ERROR со stack trace

---

### пакет /repository/storage

1. Пакет `repository.storage` в целом — неверное расположение.

В Spring-проектах `repository` — устоявшееся соглашение для Spring Data репозиториев: интерфейсов, наследующих `JpaRepository` / `CrudRepository` и инкапсулирующих доступ к реляционной БД. Когда разработчик открывает пакет `repository`, он ожидает найти там именно их. Вместо этого здесь находятся `MinioStorageSDK`, `MinioStorageRepository`, `ObjectStorageAdapter`, `ObjectKeyBuilder` — клиент и адаптер для внешнего S3-хранилища.

MinIO — это не база данных, а внешний инфраструктурный сервис. Код, работающий с ним, — это адаптер (в терминах Hexagonal Architecture — Secondary Adapter / Driven Adapter), а не репозиторий в смысле Spring Data. Смешение двух несвязанных вещей под одним пакетом затрудняет навигацию и создаёт ложное впечатление об архитектуре.

**Рекомендация:** Вынести MinIO-код в отдельный пакет верхнего уровня

2. Класс: `repository.storage.MinioStorageSDK`, метод `deleteBatch()`.

Структура try-catch логически некорректна. `result.get()` вызывается внутри try-блока: если он успешно возвращает `DeleteError` (объект не удалён), код явно бросает `new StorageException(...)`, который тут же перехватывается catch-блоком ниже и перебрасывается с потерей информации об имени объекта. В результате сообщение об ошибке будет вложенным: `"Failed to delete objects: Failed to delete object: {name}"`. Если же сам `result.get()` бросает исключение, оно тоже обрабатывается в том же catch — невозможно различить два разных сценария ошибки.

**Рекомендация:** пересмотреть код, исправить ошибку

3. Класс: `repository.storage.MinioStorageSDK`, метод `ensureBucketExists()`.

Метод использует `System.out.println("Created bucket" + bucket + ".")` и `System.out.println(bucket + " was already created.")` вместо логгера. Помимо непрофессиональной практики, в строке первого вывода отсутствует пробел: `"Created bucket" + bucket` даст `"Created bucketmy-bucket."` без пробела между "bucket" и именем. Вывод в stdout не управляется уровнем логирования и не попадает в структурированные логи.

**Рекомендация:** переписать на логгер

4. Класс: `repository.storage.MinioStorageSDK`, метод `put()`, параметр `ResourceType contentType`. Та же проблема в интерфейсе `repository.storage.ObjectStorageAdapter`.

Сигнатура метода `put(String objectKey, InputStream data, long size, ResourceType contentType)` принимает `ResourceType` как параметр `contentType`. `ResourceType` — доменный enum со значениями `FILE` и `DIRECTORY`, он не является MIME-типом. При этом параметр вообще не используется: в `PutObjectArgs.builder()` нет вызова `.contentType(...)`. Это мёртвый параметр, который вводит в заблуждение и смешивает доменный тип с инфраструктурным.

**Рекомендация:** Убрать параметр contentType из интерфейса и реализации

5. Класс: `repository.storage.ObjectKeyBuilder`, метод `createKey()`.

Генерируемый ключ `"user-" + userId + "-" + path` не соответствует формату, описанному в ТЗ. Согласно ТЗ: "для каждого пользователя будет создана папка с именем в формате `user-${id}-files`", т.е. файл `docs/test.txt` пользователя с id=1 должен храниться по пути `user-1-files/docs/test.txt`. Текущая реализация генерирует `user-1-docs/test.txt` — сегмент `-files` отсутствует. Это отклонение от спецификации хранилища.

**Рекомендация:** Сделать как в тз

---

### пакет /service

1. Класс: `ResourceService`, метод `requireUser()` и все публичные методы.

Каждый публичный метод `ResourceService` вызывает `requireUser(username)`, который делает `userRepository.findByUserName(username)` — запрос к БД только для того, чтобы получить `UserEntity` по имени, уже присутствующему в сессии. Кроме того, `ensureDirectoryExists()` вызывает `createDirectory()`, который снова вызывает `requireUser()`. Итого: при загрузке 1 файла с 3 уровнями вложенности выполняется 4 лишних запроса к `userRepository`.

`ResourceService` не должен знать о том, как найти пользователя — это ответственность `UserService`. Наличие зависимости `ResourceService → UserRepository` нарушает изоляцию сервисного слоя. Правильный подход: контроллер получает `UserEntity` (или `userId`) из SecurityContext один раз и передаёт в сервис.

**Рекомендация:** Разобраться с вызовами, сделать чтобы 1 вызов был

2. Класс: `ResourceService`, метод `upload()`, строка `resourceRepository.save(entity)`.

Внутри цикла по файлам каждый `ResourceEntity` сохраняется отдельным вызовом `save()`. При загрузке 200 файлов (максимум по лимиту) — 200 отдельных INSERT-запросов к БД. Spring Data JPA `saveAll()` может использовать JDBC batch insert, если включён `spring.jpa.properties.hibernate.jdbc.batch_size`, что даст существенный прирост производительности.

**Рекомендация:** Собрать все сущности в список, сохранить одним вызовом

3. Класс: `ResourceService`, метод `deleteResource()`, строка `resourceRepository.deleteAll(descendants)`.

При удалении директории `deleteAll(descendants)` генерирует по одному DELETE-запросу на каждую дочернюю сущность. `JpaRepository.deleteAllInBatch()` выполняет один запрос `DELETE FROM resources WHERE id IN (...)`. При большом количестве вложенных файлов разница в производительности существенная.

**Рекомендация:** Делать батч делит

4. Класс: `ResourceService`, метод `getOrCreateRoot()`.

Корневая директория пользователя создаётся лениво при первом обращении к любому методу: `getResourceInfo`, `getDirectoryContents`, `upload` и другим — всем им в итоге попадёт `getResourceEntity("")`, который вызовет `getOrCreateRoot()`. Read-метод создаёт данные — это неожиданный side effect. Если `getResourceInfo()` когда-нибудь будет вызван в read-only транзакции, Hibernate выбросит исключение при попытке сохранить корень.

Создание корневой директории — часть бизнес-логики регистрации пользователя. Оно должно находиться в `UserService.register()`, где логически принадлежит, а не прятаться внутри service-методов для работы с ресурсами.

**Рекомендация:** `UserService.register()` — явно создаёт корень при регистрации, убрать создание из `ResourceService.getOrCreateRoot()`

5. Класс: `ResourceService`, вложенный record `DownloadPayload`.

`DownloadPayload` объявлен как `public record` внутри сервиса, а контроллер зависит на `ResourceService.DownloadPayload`. Внутренняя структура данных сервиса стала частью его публичного API. `StreamingResponseBody` — абстракция Spring MVC, она не должна находиться в доменном сервисе. Так сервис начинает знать о деталях HTTP-слоя, что нарушает изоляцию.

**Рекомендация:** Вынести DownloadPayload в отдельный файл в dto

6. Класс: `ResourceService`, метод `find()`, параметр `Pageable pageable`.

`Pageable` из `org.springframework.data.domain` — это абстракция persistence-слоя. Она передаётся через контроллер напрямую в сервис, минуя какое-либо преобразование. Сервисный слой теперь зависит от Spring Data API. При переходе на другой механизм пагинации (например, курсорная пагинация или кэш) сигнатура сервисного метода потребует изменений.

**Рекомендация:**
```java
// Вариант 1: принимать примитивные параметры, строить Pageable внутри сервиса:
public List<ResourceDTO> find(String query, String username, int page, int size) {
    UserEntity owner = requireUser(username);
    Pageable pageable = PageRequest.of(page, size);
    return resourceRepository
        .findAllByOwnerAndNameContainingIgnoreCase(owner, query, pageable)
        .stream()
        .map(this::entityToDTOConverter)
        .toList();
}

// ResourceController передаёт page/size:
@GetMapping("/resource/search")
public List<ResourceDTO> find(
        @RequestParam("query") @NotBlank @Size(max = 256) String query,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @AuthenticationPrincipal UserDetails user) {
    return resourceService.find(query, user.getUsername(), page, size);
}
```

7. Класс: `ResourceService`, метод `createDirectory()`, вызов `ensureNoConflictOnMove(owner, path)`.

Метод с именем `ensureNoConflictOnMove` вызывается при создании директории. Читая `createDirectory()`, видишь метод "для move" и не понимаешь, почему он здесь. Это затрудняет понимание кода. По сути метод просто проверяет, что ресурс с таким путём не существует — это нейтральная проверка, не специфичная для move.

**Рекомендация:**
```java
// Переименовать метод, отражая суть проверки:
private void ensureResourceDoesNotExist(UserEntity owner, String path) {
    if (resourceRepository.existsByOwnerAndFullPath(owner, path)) {
        throw new ResourceAlreadyExistsException("Resource already exists: " + path);
    }
}

// Использовать одинаково в createDirectory() и moveResource():
ensureResourceDoesNotExist(owner, path);     // при создании
ensureResourceDoesNotExist(owner, toPath);  // при перемещении
```

8. Класс: `PathService`, метод `normalizeUploadRelativePath()`.

Метод содержит мёртвый код:
```java
if (normalized.indexOf('/') < 0) {
    return normalized;   // путь без '/'
}
return normalized;       // путь с '/' — возвращает то же самое!
```
Обе ветки `if` и `else` возвращают `normalized`. Метод полностью эквивалентен `normalizePath(path, ResourceType.FILE)`. Мёртвый код вводит читателя в заблуждение: кажется, что ветки делают что-то разное, хотя это не так.

**Рекомендация:**
```java
// Либо удалить метод и в ResourceService вызывать напрямую:
relative = pathService.normalizePath(relative, ResourceType.FILE);

// Либо реализовать реальную логику, если она подразумевалась:
public String normalizeUploadRelativePath(String path) {
    return normalizePath(path, ResourceType.FILE);
    // if-ветку — убрать, это был dead code
}
```

---

## РЕКОМЕНДАЦИИ

1. **Вынести конфигурацию внешних сервисов в `@ConfigurationProperties`.**
   Создать `MinioProperties` (и при необходимости `RedisProperties`) и убрать `@Value("${minio.*}")` из обычных классов. Это даст типобезопасную конфигурацию, единое место настроек и возможность валидировать её при старте.

2. **Вынести `UserDetailsService` в отдельный сервис.**
   Сделать `UserDetailsServiceImpl` и убрать зависимость `SecurityConfig` от `UserRepository`. Конфигурация безопасности должна работать через интерфейсы Spring Security, а не через репозитории.

3. **Убрать зависимость `ResourceService` от `UserRepository`.**
   Передавать в сервис `userId` или `UserEntity`, а не `username`. Пользователя лучше получать один раз в контроллере. Это уберёт лишние запросы к БД.

4. **Создавать корневую директорию при регистрации пользователя.**
   Перенести эту логику в `UserService.register()` и убрать lazy-создание из методов чтения `ResourceService`.

5. **Использовать batch-операции JPA.**
   Заменить множественные `save()` на `saveAll()`, а `deleteAll()` на `deleteAllInBatch()`. Также включить batching через
   `spring.jpa.properties.hibernate.jdbc.batch_size=50`.

6. **Убрать зависимость сервиса от Spring MVC.**
   `DownloadPayload` лучше вынести в transport-слой или вернуть из сервиса более нейтральные данные (например `InputStream` + имя файла), а `StreamingResponseBody` формировать уже в контроллере.

7. **Упростить сущности через Lombok.**
   Использовать `@Getter`, `@Setter`, `@NoArgsConstructor` вместо ручных геттеров/сеттеров и добавить `equals()`/`hashCode()` по `id`.

8. **Разделить DTO для входа и регистрации.**
   Вместо `UserCreateUpdateDTO` сделать `SignUpRequest` и `SignInRequest` с разными правилами валидации.

9. **Исправить уровни ошибок и логирования.**
   `StorageException` → HTTP 500.
   Ошибки клиента (4xx) логировать как `WARN` без stack trace, серверные (5xx) — как `ERROR` со stack trace.

10. **Синхронизировать валидацию DTO и сущностей.**
    Ограничения (например `@Size` для `userName`) должны совпадать. Лучше оставить Bean Validation на уровне DTO, а не на JPA-сущностях.

---

## ИТОГ

студент уверенно использует стек **Spring Boot, Spring Security, JPA, Flyway, MinIO, Redis Sessions, Testcontainers**, реализовал все требуемые API-эндпоинты, разделил код на слои `controller / service / repository`, добавил глобальный обработчик ошибок и написал тесты с in-memory заглушкой MinIO.
Код рабочий и соответствует ТЗ.

Основные точки роста связаны не с синтаксисом, а с архитектурой и изоляцией слоёв.

* **Связность сервисов.**
  `ResourceService` зависит от `UserRepository`, что создаёт лишние запросы к БД и размывает границы доменных сервисов. Важно продумывать, какие сервисы должны знать друг о друге.

* **Производительность JPA.**
  Используются отдельные `save()` и `deleteAll()` вместо batch-операций (`saveAll()`, `deleteAllInBatch()`). Это типичная ошибка при первом знакомстве с ORM.

* **Конфигурация как отдельный слой.**
  `@Value` разбросан по сервисам и репозиториям. Лучше собирать конфигурацию в `@ConfigurationProperties` — это упрощает поддержку и даёт типобезопасность.

* **Инкапсуляция слоёв.**
  Сервисный слой знает о деталях транспорта и ORM:
  `StreamingResponseBody` внутри `DownloadPayload`, `Pageable` в сигнатуре сервиса. Сервисы должны быть независимы от HTTP и конкретных ORM-типов.

* **Классификация ошибок.**
  `StorageException` возвращает `400`, а все ошибки логируются как `ERROR`. Нужно различать ожидаемые ошибки клиента (4xx) и реальные сбои сервера (5xx).

Что полезно изучить дальше:

* `@ConfigurationProperties` и типобезопасную конфигурацию
* **Ports and Adapters (Hexagonal Architecture)** для лучшего разделения сервисов и адаптеров
* batch-операции Hibernate и диагностику **N+1** (`spring.jpa.show-sql`)
* базовые идеи **CQRS** для разделения команд и запросов.
