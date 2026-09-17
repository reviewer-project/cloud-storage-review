# ХОРОШО

1. Каждая операция с ресурсами (создать, удалить, переместить, скачать) вынесена в свой сервис — легко понять, куда смотреть при правке конкретной операции.

2. При переносе файла или папки хранилище само откатывает изменения, если что-то пошло не так на середине операции — пользователь не рискует получить одновременно старую и новую копию ресурса.

3. Сущность пользователя защищена от случайных правок снаружи: нет лишних сеттеров, обязательные поля задаются только через конструктор.

4. Наружу в API уходит только username — то, что сервис хранит внутри (например, id), остаётся внутри и не просачивается в ответ.

5. Документация ошибок для 404 и 409 прописана один раз для всего API централизованно.

6. Проверки при переносе ресурса разложены на понятно названные шаги — сразу видно, какое правило за что отвечает.

7. При неизвестной ошибке клиент видит только общее сообщение, подробности остаются в логах.

# ЗАМЕЧАНИЯ

## пакет /config

1. Настройки MinIO в `MinioConfig` не собраны в проперти-класс

url, user, password и bucket.name читаются как четыре независимых `@Value`-поля, а имя бакета затем "экспортируется" отдельным бином типа `String` (`minioBucketName()`). Из-за этого `String minioBucketName` внедряется по типу сразу в `MinioRepository`, `StorageInitializer` и во все восемь сервисов ресурсов/директорий, а в тестах (например, `ResourceUploadServiceTest`) значение приходится выставлять через `ReflectionTestUtils`, потому что обычным конструктором сервис с мок-зависимостями это поле не заполнит.

**Рекомендация:**

Заведи `@ConfigurationProperties(prefix = "minio")` класс `MinioProperties` в `config/properties` с полями url, user, password и bucket-name, и внедряй его вместо отдельных `@Value` и бина-строки.

## пакет /controller

1. У `UserController`, `DirectoryController` и `ResourcesController` нет отдельного API-интерфейса

Все три контроллера сразу несут на себе и HTTP-контракт (`@PostMapping`, `@RequestParam`, `@Pattern`, `@Valid`), и документацию (`@Operation`, `@ApiResponses`), и реализацию. Это рабочий вариант для небольшого проекта, но контракт эндпоинта и его реализация оказываются в одном файле: чтобы понять, что отдаёт `POST /api/resource/move`, нужно читать тот же класс, где лежит логика авторизации и вызов сервиса.

**Рекомендация:**

Вынеси mapping, параметры, `@Valid` и OpenAPI-аннотации в отдельные `*Api`-интерфейсы (`UserApi`, `DirectoryApi`, `ResourceApi`), а в самих контроллерах оставь `@RestController implements ...Api` с делегированием в сервис, как показано в задании к ревью.

2. Проверка `userId == null` дублируется в каждом методе всех трёх контроллеров

`@SessionAttribute(name = "userId", required = false) Long userId` и следующий за ним `if (userId == null) throw new UnauthorizedActionException(...)` повторяются дословно девять раз: в четырёх методах `UserController`, двух методах `DirectoryController` и шести методах `ResourcesController`. Каждый новый эндпоинт с доступом к ресурсам пользователя требует не забыть скопировать эту проверку — компилятор здесь не поможет, если её пропустить.

**Рекомендация:**

Вынеси чтение `userId` из сессии и выброс `UnauthorizedActionException` в `HandlerMethodArgumentResolver`, который резолвит параметр вроде `@CurrentUserId Long userId`, и убери проверку из тела методов контроллеров.

3. В `UserController` наборы `@ApiResponse` продублированы вручную вместо переиспользования уже готового способа их прописать

`DirectoryController` и `ResourcesController` получают 400/401/500 через один класс-level `@CommonApiErrorResponses`, а недостающие 404/409 — автоматически от кастомайзера в `OpenApiConfig`. `UserController` вместо этого вручную пишет `@ApiResponse` с одинаковым блоком `content = @Content(mediaType = ..., schema = @Schema(implementation = ErrorResponse.class))` в каждом методе (для 400 — дважды, для 401 — трижды), да ещё и отдельно объявляет 500 на уровне класса, хотя тот же 500 уже описан внутри `CommonApiErrorResponses`.

**Рекомендация:**

Расширь существующий кастомайзер в `OpenApiConfig` так, чтобы он проставлял схему `ErrorResponse` не только для 404/409, но и для 400/401/500, и убери из `UserController` ручные блоки `content` — оставь на аннотациях только `responseCode` и `description`.

4. Регулярки валидации пути продублированы между `DirectoryController` и `ResourcesController`

`PATH_GET_STRICT_VALIDATOR_REGEXP` в `DirectoryController` и `PATH_UPLOAD_VALIDATOR_REGEXP` в `ResourcesController` — это один и тот же паттерн `"^$|^([a-zA-Zа-яА-ЯёЁ0-9_\s.-]+/)+$"`, просто под разными именами в двух классах. Внутри `ResourcesController` `PATH_COMMON_VALIDATOR_REGEXP` вместе с `@NotBlank` при этом повторяется ещё четыре раза подряд для параметров `move`, `download`, `delete` и `info`.

**Рекомендация:**

Вынеси регулярки путей в один класс-держатель констант, общий для обоих контроллеров, вместо четырёх параллельно живущих строк в двух разных файлах.

## пакет /exception

1. `UserNotAuthenticatedException` и `UnauthorizedActionException` описывают один и тот же случай

`UserNotAuthenticatedException` используется только в `UserController#logout()`, а `UnauthorizedActionException` — во всех остальных местах, включая `getCurrentUser()` в том же контроллере, где ситуация ровно та же: в сессии нет `userId`. `GlobalExceptionHandler` мапит оба класса на один и тот же `HttpStatus.UNAUTHORIZED`, так что разница между ними не несёт смысла и только заставляет помнить, какое исключение бросать в каком месте.

**Рекомендация:**

Убери `UserNotAuthenticatedException` и в `logout()` брось уже используемый везде `UnauthorizedActionException`.

## пакет /repository

1. `MinioRepository` — единственная реализация без интерфейса, и наружу отдаёт типы MinIO SDK

`MinioRepository` — конкретный класс, который напрямую внедряется в восемь сервисов, а его публичные методы `getFolderInfo()` и `search()` возвращают `List<io.minio.messages.Item>`, `getObjectResponse()` — `io.minio.StatObjectResponse`. Из-за этого `DirectoryGetInfoService`, `ResourceDeleteService`, `ResourceInfoService`, `ResourceMoveService`, `ResourceSearchService` и `ResourceDownloadService` напрямую читают поля `Item`/`StatObjectResponse`, хотя им нужны только имя объекта, размер и признак папки. Замена MinIO на другого S3-совместимого провайдера потребует правки сигнатур всех этих сервисов вместе с реализацией репозитория.

**Рекомендация:**

Заведи нейтральный интерфейс `ObjectStorage` с методами, которые уже фактически есть у `MinioRepository`, но возвращающими свою модель вместо `Item`/`StatObjectResponse`, и перенеси текущую реализацию в `MinioObjectStorage implements ObjectStorage`:

```java
public interface ObjectStorage {
    List<StorageItem> list(String prefix, boolean recursive);
    StorageItem stat(String path);
    // upload, moveFile, moveDirectory, doesPathExist, readData,
    // deleteFile, deleteResources — сигнатуры остаются как у MinioRepository сейчас
}

public record StorageItem(String path, boolean directory, long size) {
}
```

2. `StorageInitializer` дублирует проверку существования из `MinioRepository`

`StorageInitializer#initRoot()` и `MinioRepository#checkParentFolder()` делают одно и то же: `listObjects` по префиксу с `maxKeys(1)` и проверка, есть ли хоть один результат. Это рабочий, но написанный дважды в двух соседних классах инфраструктурного слоя код.

**Рекомендация:**

Перенеси проверку существования по префиксу в `MinioRepository` в виде отдельного метода и вызывай его из `StorageInitializer` вместо параллельной реализации через `listObjects`.

## пакет /request

1. `UserLoginRequest` и `UserRegisterRequest` дословно дублируют ограничения на username и password

Оба record несут абсолютно одинаковые `@NotBlank`, `@Size(min = 5, max = 20)` и `@Pattern` для username и для password. Правило и про длину пароля, и про допустимые символы логина дублируется сразу в двух местах, и при изменении легко поправить одно и забыть про второе.

**Рекомендация:**

Вынеси общие ограничения в составные аннотации `@ValidUsername` и `@ValidPassword`, объединяющие текущие `@NotBlank`/`@Size`/`@Pattern`, и используй их в обоих record вместо копирования одинаковых блоков:

```java
@NotBlank
@Size(min = 5, max = 20, message = "User name must be between {min} and {max} characters")
@Pattern(regexp = "^[a-zA-Z0-9]+[a-zA-Z_0-9]*[a-zA-Z0-9]+$", message = "Invalid username")
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidUsername {
    String message() default "Invalid username";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

## пакет /service

1. Инициализация корневой папки пользователя дублируется в начале всех восьми сервисов

`DirectoryCreateService`, `DirectoryGetInfoService`, `ResourceDeleteService`, `ResourceInfoService`, `ResourceMoveService`, `ResourceSearchService`, `ResourceUploadService` и `ResourceDownloadService` начинаются с одинаковых двух строк:

```java
String preparedRoot = buildPreparedRoot(userId, minioBucketName);
initializer.initStorage(preparedRoot);
```

Из-за этого `initStorage()` — проверка бакета плюс отдельный запрос в MinIO на существование корневой папки пользователя — выполняется заново при каждом обращении к любому ресурсному эндпоинту, хотя после первого захода пользователя в хранилище эта проверка уже не даёт ничего нового.

**Рекомендация:**

Перенеси вызов `initializer.initStorage()` в `UserService.register()` и `login()`, чтобы корневая папка пользователя создавалась один раз — при регистрации и при входе, — и её не нужно было пересоздавать в начале каждого из восьми сервисов.

2. Тип ресурса определяется строкой, хотя рядом есть enum `Type`

В `ResourceInfoService`, `ResourceMoveService`, `ResourceDeleteService` и `ResourceDownloadService` тип вычисляется как `path.endsWith("/") ? Type.DIRECTORY.name() : Type.FILE.name()`, а затем сравнивается через `"FILE".equals(type)`. Сам enum `Type` для этого и существует, но используется только для получения строки, а дальше сравнения идут по строковым литералам, которые компилятор не проверит на опечатку.

**Рекомендация:**

Работай с `Type` до момента формирования `ResourceResponse` — сравнивай `Type.FILE == type` вместо строковых литералов — и переводи его в строку только там, где собираешь ответ.

3. `ResourceServiceUtils.buildPreparedRoot()` строит путь пользователя, разбирая имя бакета по дефису

```java
String[] splitBucket = minioBucketName.split("-");
return splitBucket[0] + "-" + userId + "-" + splitBucket[1] + "/";
```

Метод предполагает, что имя бакета состоит ровно из двух частей через один дефис ("user-files"). Если в `MINIO_BUCKET_NAME` окажется, например, "cloud-user-files", результат станет "cloud-{userId}-user" — путь для всех операций пользователя будет неверным, и код не проверяет длину массива, чтобы упасть с понятной ошибкой сразу. Прямого юнит-теста на этот метод в проекте нет, поэтому такая ошибка не будет поймана быстрым тестом и проявится только на реальном бакете с другим именем.

**Рекомендация:**

Строй путь пользователя через отдельный шаблон вида `user-%d-files/`, заданный явно константой рядом с `buildPreparedRoot`, вместо разбора `minioBucketName`, и подставляй в него `userId` через `String.format`.

4. `ResourceDownloadService` копирует поток вручную вместо `transferTo`

`processFile()` и `processFolder()` дважды читают данные через `byte[] buffer = new byte[1024]` и ручной `while`-цикл, хотя тут же используется `try-with-resources` и проект собран на Java 21.

**Рекомендация:**

Замени оба цикла на `inputStream.transferTo(outputStream)` и `inputStream.transferTo(zos)` соответственно.

# РЕКОМЕНДАЦИИ

1. Добавь прослойку интерфейсов на двух реальных границах проекта — публичном REST-контракте и работе с объектным хранилищем. Сейчас обе границы задаются напрямую конкретными классами (`*Controller`, `MinioRepository`), и правка контракта, и смена провайдера хранилища одинаково требуют правок во всех местах, где эти классы используются напрямую.

2. Собери сквозные проверки, которые сейчас размазаны по методам сервисов и контроллеров — проверку авторизации по сессии и инициализацию хранилища пользователя, — в одном месте вместо копирования в начале каждого метода.

3. Реши, где по проекту должна жить логика построения путей в MinIO (сейчас она — в статических методах `ResourceServiceUtils`, вызываемых из восьми сервисов), и покрой её прямыми юнит-тестами отдельно от тестов на сами сервисы: сейчас баг в разборе пути не поймает быстрый юнит-тест, и обнаружится он только на уровне более медленных сервисных и интеграционных тестов.

4. Собери настройки внешних систем (MinIO) в типизированные проперти-классы вместо наборов `@Value`-полей и бинов примитивных типов — это уменьшит число мест, куда нужно заглядывать при смене окружения.

# ИТОГ

Читать проект приятно: код разложен по смыслу, в нём легко сориентироваться. Но в паре важных мест не хватает именно базовой прослойки между "что мы делаем" и "как именно это устроено технически" — из-за этого части кода приходится знать друг о друге больше, чем следовало бы. Стоит поправить это в первую очередь, пока проект небольшой.

Остальные замечания мельче: местами одна и та же мысль реализована параллельно сразу в нескольких классах, вместо того чтобы жить в одном. Собери такие вещи воедино — и с проектом станет заметно спокойнее работать дальше.
