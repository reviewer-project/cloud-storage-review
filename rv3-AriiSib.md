[AriiSib](https://github.com/AriiSib/cloud-storage)

# НЕДОСТАТКИ РЕАЛИЗАЦИИ

## 1. Безопасность

### 1.1. CSRF отключен при использовании cookie-сессий (HIGH)
```java
// SecurityConfig.java
.csrf(AbstractHttpConfigurer::disable)
```
При использовании stateful сессий с cookies отключение CSRF открывает уязвимость к cross-site request forgery атакам. Злоумышленник может заставить авторизованного пользователя выполнить нежелательные действия.

### 1.2. Ограничение длины пароля снижает безопасность (MEDIUM)
```java
// AuthRequest.java
@Size(min = 6, max = 32)
String password
```
Максимальная длина пароля 32 символа искусственно ограничивает энтропию. BCrypt принимает пароли до 72 байт, а в БД поле на 100 символов.

**Рекомендация:** Убрать или увеличить верхний лимит до 72-128 символов.

### 1.3. Утечка внутренних сообщений об ошибках (MEDIUM)
```java
// RestControllerExceptionHandler.java
@ExceptionHandler(Exception.class)
Map<String, Object> onException(Exception ex, HttpServletRequest req) {
    return Map.of("message", ex.getMessage());
}
```
Сообщения из любых исключений (включая системные) попадают в ответ клиенту. Это может раскрыть внутреннюю структуру приложения.

**Рекомендация:** Возвращать generic сообщение типа "Internal server error" для непредвиденных исключений.

## 2. Логические ошибки

### 2.1. Неконсистентная валидация родительской директории (MEDIUM)
```java
// ResourceCommandService.java
public ResourceResponse createDirectory(long userId, String relPath) {
    String objectName = StorageObjectBuilder.normalizePath(userId, relPath);
    String parent = objectName.replace(getDirectory(relPath), "");
    if (!storage.isResourceExists(parent) && !parent.equals(StorageObjectBuilder.getUserRoot(userId)))
        throw new StorageNotFoundException("Parent directory not exist");
    // ...
}
```
Код пытается проверить существование родительской директории, но использует `replace()`, который удаляет **все** вхождения подстроки.

**Пример:** При создании `docs1/docs1/`:
- `objectName = "user-1-files/docs1/docs1/"`
- `getDirectory()` возвращает `"docs1/"`
- `replace("docs1/", "")` удаляет **оба** вхождения → `parent = "user-1-files/"`
- Проверка проходит против корня пользователя, а не против реального родителя `docs1/`

**Результат:** Папка `docs1/docs1/` создаётся успешно, при этом `docs1/` тоже создаётся автоматически (особенность MinIO). Функционально работает, но валидация родителя фактически не выполняется для путей с повторяющимися именами.

**Рекомендация:** 
- Если проверка родителя нужна — исправить на `lastIndexOf` + `substring`
- Если MinIO сам создаёт промежуточные директории и проверка избыточна — убрать её совсем для консистентности

### 2.2. Некорректная конкатенация пути при загрузке файлов (MEDIUM)
```java
// StorageObjectBuilder.java
public static String normalizePath(Long userId, String relPath, String fileName) {
    return getUserRoot(userId) + relPath + fileName;
}
```
Если `path` указан без trailing slash (например `docs/file.txt` вместо `docs/`), имя загружаемого файла конкатенируется напрямую:
- `relPath = "docs/file.txt"`, `fileName = "1.png"` → `user-X-files/docs/file.txt1.png`

**Ожидаемое поведение:** либо валидация должна требовать `/` на конце пути для upload, либо автоматически добавлять разделитель.

**Рекомендация:** Добавить проверку `if (!relPath.isEmpty() && !relPath.endsWith("/"))` и либо кидать ошибку, либо добавлять `/`.

### 2.3. 404 вместо пустого списка при поиске (LOW)
```java
// ResourceQueryService.java
public List<ResourceResponse> searchResource(long userId, String query) {
    List<String> objects = storage.listObjects(userRoot, true);
    if (objects.isEmpty())
        throw new StorageNotFoundException("Resource not found");
    // ...
    if (responses.isEmpty())
        throw new StorageNotFoundException("Resource not found");
```
По ТЗ поиск должен возвращать пустой массив `[]`, а не 404. Пустой результат поиска — это валидный ответ.

## 4. Ограничения

### 4.1. Лимит длины пути 200 символов (MEDIUM)
```java
// PathValidationUtils.java
private static final int MAX_LENGTH = 200;
```
При глубокой вложенности (5-10 уровней по 20 символов) легко превысить лимит. Сообщение об ошибке говорит о 255 символах, а лимит 200.

### 4.2. Username ограничен 5-20 символами (LOW)
```java
// AuthRequest.java
@Size(min = 5, max = 20)
String username
```
Ограничение кажется излишне строгим

---

# ХОРОШО

## 1. Архитектура

### 1.1. Попытка абстрагироваться от MinIO через порт/адаптер
```java
public interface StoragePort {
    void save(String normalizedPath, InputStream inputStream, long size, String contentType);
    void copy(String from, String to);
    // ...
}
```
Хороший подход к изоляции инфраструктурного слоя. Можно заменить MinIO на S3, GCS и др.

### 1.2. Кастомные аннотации для валидации
```java
@SafePath
@SafePathOrRoot
```
Чистый подход к валидации путей через аннотации. Легко переиспользовать.

### 1.3. Correlation ID для логирования
```java
// CorrelationAndAccessLogFilter.java
String cid = req.getHeader("X-Request-Id");
if (cid == null || cid.isBlank()) cid = UUID.randomUUID().toString();
MDC.put("cid", cid);
```
Хорошая практика для отслеживания запросов в распределённых системах.

### 1.4. Case-insensitive поиск username
```java
boolean existsByUsernameIgnoreCase(String username);
Optional<User> findByUsernameIgnoreCase(String username);
```
С индексом `LOWER(username)` это работает эффективно.

### 1.5. Стриминг файлов
```java
public InputStream download(String objectName) {
    return minioClient.getObject(...);
}
```
Файлы не загружаются в память целиком — используется стриминг.

---

# ЗАМЕЧАНИЯ

Чтож, пойдем сверху вниз по иерархии файлов и папок. adapter первый.

## adapter/

### 1. `StoragePort` — протечка абстракции
```java
MinioResponse checkObject(String objectName);
```
Интерфейс порта возвращает `MinioResponse` — название привязано к конкретной реализации.

**Рекомендация:** Переименовать в `StorageObjectMeta` или `StorageResponse`.

### 2. `MinioStorageAdapter.checkObject` — возврат null
```java
public MinioResponse checkObject(String objectName) {
    // ...
    if ((e).errorResponse().code().equals("NoSuchKey"))
        return null;
}
```
Если важна null-safety, можно рассмотреть использование `Optional<MinioResponse>` — это делает контракт метода более явным и напоминает вызывающему коду о необходимости обработки отсутствующего значения.

### 3. Дублирование обработки исключений
```java
} catch (ErrorResponseException | InvalidResponseException e) {
    throw new StorageErrorResponseException(...);
} catch (InsufficientDataException | InternalException | IOException | NoSuchAlgorithmException |
         ServerException | XmlParserException e) {
    throw new StorageException(...);
} catch (InvalidKeyException e) {
    throw new StorageAccessException(...);
}
```
Этот блок повторяется в каждом методе. Варианты решения:

**Вариант 1: Функциональный интерфейс + приватный метод**
```java
@FunctionalInterface
private interface MinioOperation<T> {
    T execute() throws Exception;
}

private <T> T executeWithExceptionHandling(MinioOperation<T> operation, String errorMessage) {
    try {
        return operation.execute();
    } catch (ErrorResponseException | InvalidResponseException e) {
        throw new StorageErrorResponseException(errorMessage, e);
    } catch (InsufficientDataException | InternalException | IOException | 
             NoSuchAlgorithmException | ServerException | XmlParserException e) {
        throw new StorageException(errorMessage, e);
    } catch (InvalidKeyException e) {
        throw new StorageAccessException(errorMessage, e);
    }
}

// Использование:
@Override
public void createDirectory(String objectName) {
    executeWithExceptionHandling(() -> {
        minioClient.putObject(PutObjectArgs.builder()...build());
        return null;
    }, "Failed to create directory");
}
```

**Вариант 2: AOP аспект**
```java
@Aspect
@Component
public class MinioExceptionHandlingAspect {
    
    @Around("execution(* com.khokhlov.cloudstorage.adapter.MinioStorageAdapter.*(..))")
    public Object handleMinioExceptions(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (ErrorResponseException | InvalidResponseException e) {
            throw new StorageErrorResponseException("Storage operation failed", e);
        } catch (InsufficientDataException | InternalException | IOException | 
                 NoSuchAlgorithmException | ServerException | XmlParserException e) {
            throw new StorageException("Unexpected storage error", e);
        } catch (InvalidKeyException e) {
            throw new StorageAccessException("Storage access error", e);
        }
    }
}
```

---

## config/

### 4. `MinioConfig` — использование `@Data` на `@Configuration`
```java
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {
```
`@Data` генерирует equals/hashCode/toString, что избыточно для конфигурации. Лучше `@Getter @Setter`.

---

## controller/

### 5. `ResourceController` — смешение ModelAttribute и RequestParam
```java
@PostMapping(value = "/resource", ...)
public ResponseEntity<List<ResourceResponse>> uploadResource(
        @Valid @ModelAttribute RootOrResourceRequest request,
        @RequestParam(name = "object") List<MultipartFile> files)
```
Путь приходит как `@ModelAttribute`, файлы как `@RequestParam`. Нестандартный подход — можно объединить в один DTO.

### 6. `AuthService` в папке controller
```java
package com.khokhlov.cloudstorage.controller.auth;
public class AuthService { ... }
```
Сервис лежит в пакете controller. Лучше перенести в `service/auth/`.

---

## handler/

### 7. `RestControllerExceptionHandler` — не используется `ErrorResponse` DTO
```java
Map<String, String> conflict(Exception ex, HttpServletRequest req) {
    return Map.of("message", ex.getMessage());
}
```
Есть готовый `ErrorResponse` record, но вместо него используется `Map`. Нарушает консистентность.

### 8. Закомментированный код
```java
// Current frontend does not support errors list
//    @ExceptionHandler(MethodArgumentNotValidException.class)
//    ...
```
Закомментированный код лучше удалить

---

## mapper/

### 9. `ResourceMapper` — использование replace для вычисления пути
```java
public ResourceResponse toResponse(String objectName, Long size) {
    String name = isDirectory(relPath) ? getDirectory(relPath) : getFileName(relPath);
    String path = relPath.replace(name, "");
}
```
Та же проблема с `replace()` — заменяет все вхождения.

---

## model/

### 10. `MinioResponse` — название привязано к реализации
```java
public record MinioResponse(long size) {}
```
Переименовать в `StorageObjectMeta` или подобное.

### 11. Отсутствует валидация на уровне DTO request
```java
public record RenameOrMoveRequest(
        @SafePath String from,
        @SafePath String to
) {}
```
Нет проверки, что `from != to` на уровне DTO. Проверка в сервисе:
```java
if (pathFrom.equals(pathTo)) throw new StorageAlreadyExistsException("");
```
Лучше добавить cross-field валидацию через `@AssertTrue` или кастомный валидатор.

---

## service/

### 12. `ResourceCommandService` — множественные вызовы `storage.isResourceExists`
```java
if (!storage.isResourceExists(parent) && !parent.equals(...))
if (storage.isResourceExists(objectName))
```
Каждый вызов — запрос к MinIO. Можно оптимизировать.

### 13. `ResourceQueryService` — неэффективный поиск
```java
public List<ResourceResponse> searchResource(long userId, String query) {
    List<String> objects = storage.listObjects(userRoot, true);
    // Перебор всех объектов в памяти
}
```
При большом количестве файлов загружает весь список в память. Для production нужна пагинация или поиск на стороне БД/индекса.

---

## util/

### 14. `PathUtil` — @Component на utility class
```java
@Component
public class PathUtil {
    public static String stripUserRoot(String normalizedPath) { ... }
}
```
Все методы статические, `@Component` не нужен. Лучше `@UtilityClass` от Lombok.

### 15. `StorageObjectBuilder` — потенциальное переполнение при конкатенации
```java
public static String normalizePath(Long userId, String relPath, String fileName) {
    return getUserRoot(userId) + relPath + fileName;
}
```
Не проверяется итоговая длина пути.

---

## validation/

### 16. Дублирование аннотаций `@SafePath` и `@SafePathOrRoot`
```java
@Constraint(validatedBy = PathSafeValidator.class)
public @interface SafePath { ... }

@Constraint(validatedBy = PathOrRootValidator.class)  
public @interface SafePathOrRoot { ... }
```
Отличаются только флагом `allowBlankRoot`. Можно объединить:
```java
@SafePath(allowRoot = true)
```

### 17. Regex для сегмента пути компилируется при каждой проверке
```java
private static final String SEGMENT_REGEX = "^[a-zA-Zа-яА-Я0-9 _().\\[\\]{}+@-]+$";
// ...
if (!segment.matches(SEGMENT_REGEX)) return false;
```
`String.matches()` компилирует Pattern каждый раз. Лучше:
```java
private static final Pattern SEGMENT_PATTERN = Pattern.compile("...");
// ...
if (!SEGMENT_PATTERN.matcher(segment).matches()) return false;
```

---

## test/

### 18. Нет интеграционных тестов для работы с файлами
По ТЗ рекомендуется покрыть тестами:
- Загрузка файла → появление в MinIO
- Переименование, удаление
- Проверка прав доступа (пользователь не видит чужие файлы)
- Поиск

Сейчас есть только тесты для auth.

---

## docker/

### 19. Dev compose — minio data в локальной папке
```yaml
volumes:
  - "./minio/data:/data"
```
В отличие от postgres и redis, данные minio монтируются в локальную папку, а не в named volume. Может вызвать проблемы с правами.

### 20. Prod compose — нет лимитов ресурсов
```yaml
services:
  app:
    image: ariisib/cloud-storage
    # Нет deploy.resources.limits
```
Для production рекомендуется ограничивать memory/cpu.

---

## Прочее

### 21. Hardcoded лимиты в сообщении об ошибке
```java
return Map.of("message", ex.getMessage() + ". Maximum file size: 2GB. " +
        "Maximum number of uploaded files: 100");
```
Лимиты указаны в application.yml, но здесь захардкожены.


---

# СООТВЕТСТВИЕ ТЗ

## ✅ Реализовано корректно:
- Регистрация (201 Created + сессия)
- Авторизация (200 OK)
- Logout (204 No Content)
- Получение текущего пользователя
- Создание директории
- Получение информации о ресурсе
- Удаление файлов и папок (рекурсивно)
- Скачивание файлов и папок (ZIP)
- Redis для сессий
- Flyway миграции
- Swagger документация
- Docker Compose

## ⚠️ Требует исправления:
| Пункт ТЗ | Проблема |
|----------|----------|
| Поиск файлов | 404 вместо пустого массива |
| Загрузка файлов | Некорректная конкатенация пути без trailing slash |
| Интеграционные тесты MinIO | Отсутствуют |

## ❓ Из чеклиста ТЗ:
| Пункт | Статус |
|-------|--------|
| Несоответствие формата API | ✅ Соответствует ТЗ |
| Ошибки валидации имён | ✅ Есть валидация через @SafePath |
| Затирание файлов при переименовании | ✅ Проверяется существование |
| Битые файлы при скачивании | ✅ Работает корректно |
| Попадание в несуществующую папку | ✅ Папка создаётся автоматически |
| Доступ к чужим файлам | ✅ Путь привязан к userId |
| Низкие лимиты загрузки | ✅ 2GB — хороший лимит |
| Протечка user-${id}-files | ✅ Скрыто в сервисах |
| Работа с пустыми папками | ✅ Через 0-byte объекты |

---

# ВЫВОД

Проект выполнен **хорошо**. Основная функциональность реализована, код структурирован, есть документация и тесты.

## Критичные проблемы (нужно исправить):
1. **CSRF отключен** → включить или обосновать
2. **Валидация родителя через replace()** → исправить или убрать если избыточна
3. **Конкатенация пути без `/` при upload** → валидировать или добавлять слеш

## Рекомендации по улучшению:
1. Добавить интеграционные тесты для работы с файлами (MinIO + Testcontainers)
2. Переименовать `MinioResponse` → `StorageObjectMeta`
3. Объединить `@SafePath` и `@SafePathOrRoot` в одну аннотацию с параметром
4. Возвращать `[]` вместо 404 при пустом поиске
5. Использовать `ErrorResponse` DTO везде вместо `Map`
6. Вынести `AuthService` из пакета controller

**Оценка:** 7.5/10 — функционально рабочий проект с незначительными архитектурными и логическими недочётами.

