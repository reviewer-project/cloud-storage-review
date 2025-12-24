[EGladki](https://github.com/EGladki/CloudStorage)

# НЕДОСТАТКИ РЕАЛИЗАЦИИ

## 1. Безопасность

### 1.1. Ограничение длины пароля снижает безопасность
```java
// AuthRequestDto.java
@Size(min = 5, max = 20, message = "Password must be between 5 and 20 length")
private String password;
```
Максимальная длина пароля 20 символов искусственно ограничивает энтропию. BCrypt принимает пароли до 72 байт.

**Рекомендация:** Убрать или увеличить верхний лимит до 72-128 символов.

### 1.2. Утечка внутренних сообщений об ошибках
```java
// GlobalExceptionHandler.java
@ExceptionHandler(Exception.class)
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public Map<String, String> handleCommonException(Exception ex) {
    return Map.of("message", ex.getMessage());
}
```
Сообщения из любых исключений (включая системные) попадают в ответ клиенту. Это может раскрыть внутреннюю структуру приложения, стек-трейсы, SQL-запросы и т.д.

**Рекомендация:** Возвращать generic сообщение типа "Internal server error" для непредвиденных исключений, а детали логировать.

### 1.3. Username регистр-зависимый
```java
// UserRepository.java
Optional<User> findByUsername(String userName);
boolean existsByUsername(String userName);
```
Пользователи `Admin` и `admin` считаются разными. Это может создать путаницу.

**Рекомендация:** Использовать case-insensitive поиск:
```java
boolean existsByUsernameIgnoreCase(String userName);
Optional<User> findByUsernameIgnoreCase(String userName);
```

## 2. Логические ошибки

### 2.1. Move использует GET вместо POST/PATCH
```java
// FileManagerController.java
@GetMapping("/resource/move")
public ResponseEntity<?> move(@RequestParam(defaultValue = "") String from, String to) {
```
Операция move изменяет состояние на сервере, но использует HTTP GET. Это нарушение REST-семантики, хоть в ТЗ и сказано использовать ГЕТ, но должны были бы возникнуть вопросы, правильно ли это?

### 2.2. 404 вместо пустого списка при поиске
```java
// FileStorageMinioService.java
public List<Resource> search(String path, UserResponseDto userResponseDto) {
    // ...
    if (list.isEmpty()) {
        throw new NotFoundException("Resource not found");
    }
    return list;
}
```
По ТЗ поиск должен возвращать пустой массив `[]`, а не 404. Пустой результат поиска — это валидный ответ, а не ошибка.

**Рекомендация:** Убрать выброс исключения и возвращать пустой список.

### 2.3. Файлы полностью загружаются в память при скачивании
```java
// FileStorageMinioService.java
public byte[] downloadFile(String path, UserResponseDto userResponseDto) {
    // ...
    InputStream stream = minioClient.getObject(...);
    result = stream.readAllBytes();  // Загрузка всего файла в память
    // ...
    return result;
}
```
Лимит загрузки 300MB. При скачивании файлы полностью загружаются в память, что может вызвать OutOfMemoryError при большом количестве параллельных запросов.

**Рекомендация:** Использовать стриминг через `StreamingResponseBody`:
```java
@GetMapping("/resource/download")
public ResponseEntity<StreamingResponseBody> download(@RequestParam String path) {
    StreamingResponseBody stream = outputStream -> {
        try (InputStream is = minioClient.getObject(...)) {
            is.transferTo(outputStream);
        }
    };
    return ResponseEntity.ok().body(stream);
}
```

### 2.4. Move копирует файл в память перед перезаписью
```java
// FileStorageMinioService.java
private List<Resource> moveFile(String from, String to, UserResponseDto userResponseDto) {
    // ...
    InputStream is = minioClient.getObject(...);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    is.transferTo(baos);
    byte[] byteArray = baos.toByteArray();  // Полный файл в памяти
    // ...
}
```
Для перемещения файла он полностью загружается в память, хотя MinIO поддерживает серверное копирование.

**Рекомендация:** Использовать `minioClient.copyObject()` для серверного копирования без загрузки в приложение.

### 2.5. Рекурсивное удаление не использует batch операцию
```java
// FileStorageMinioService.java
public void deleteDirectory(String path, UserResponseDto userResponseDto) {
    List<Resource> content = getContent(path, userResponseDto);
    for (Resource resource : content) {
        delete(resource.getPath() + resource.getName(), userResponseDto);
    }
    // ...
}
```
Каждый файл удаляется отдельным запросом. При большом количестве файлов это очень медленно.

**Рекомендация:** Использовать `minioClient.removeObjects()` для batch-удаления.

### 2.6. Параметр `to` в move не объявлен как @RequestParam
```java
// FileManagerController.java
@GetMapping("/resource/move")
public ResponseEntity<?> move(@RequestParam(defaultValue = "") String from, String to) {
```
Параметр `to` не аннотирован `@RequestParam`. Spring всё равно его свяжет, но это неконсистентно и может вызвать путаницу.

## 3. Архитектура

### 3.1. Дублирование бинов AuthenticationProvider
```java
// SecurityConfig.java
@Bean
public DaoAuthenticationProvider authenticationProvider(UserDetailServiceImpl userDetailsService) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
}

@Bean
public DaoAuthenticationProvider daoAuthenticationProvider() {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder());
    return provider;
}
```
Два идентичных бина `DaoAuthenticationProvider`. Достаточно одного.

### 3.2. Использование ResponseEntity<?> вместо конкретных типов
```java
// FileManagerController.java
public ResponseEntity<?> createDirectory(@RequestParam String path)
public ResponseEntity<?> getContent(@RequestParam(defaultValue = "") String path)
```
Wildcard `?` затрудняет генерацию OpenAPI документации и понимание контракта API.

**Рекомендация:** Использовать конкретные типы:
```java
public ResponseEntity<Directory> createDirectory(...)
public ResponseEntity<List<Resource>> getContent(...)
```

### 3.3. Сервис возвращает ResponseEntity
```java
// AuthService.java
public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null) {
        securityContextLogoutHandler.logout(request, response, authentication);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    } else {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
```
Сервис не должен знать о HTTP-слое. Формирование `ResponseEntity` — ответственность контроллера.

**Рекомендация:** Сервис должен выбрасывать исключение или возвращать boolean/void:
```java
public void logout(...) {
    if (authentication == null) throw new UnauthorizedException();
    securityContextLogoutHandler.logout(...);
}
```

## 4. Ограничения

### 4.1. Нет валидации длины пути
```java
// FileStorageMinioService.java
private void validateCreationDirectoryPath(String path) {
    if (!path.endsWith("/") || path.trim().equals("/") || path.startsWith("/") 
        || path.trim().isBlank() || path.matches(".*[:|<>*?\"].*")) {
        throw new BadRequestException("Invalid directory name");
    }
}
```
Нет проверки максимальной длины пути. S3/MinIO имеет лимит ~1024 символа на ключ объекта.

**Рекомендация:** Добавить проверку длины:
```java
private static final int MAX_PATH_LENGTH = 900; // С запасом на user prefix
if (path.length() > MAX_PATH_LENGTH) {
    throw new BadRequestException("Path too long");
}
```

### 4.2. Username разрешает только буквы
```java
// AuthRequestDto.java
@Pattern(regexp = "^[a-zA-Zа-яА-ЯёЁ\\s]+$", message = "Only letters allowed")
private String username;
```
Нельзя использовать цифры и спецсимволы в username. Пробелы разрешены, что может быть проблемой.

### 4.3. Нет лимита на количество загружаемых файлов
```java
// FileManagerController.java
public ResponseEntity<?> upload(@RequestParam(defaultValue = "") String path,
                                @RequestParam("object") MultipartFile[] files)
```
Нет ограничения на количество файлов в одном запросе.

## 5. Обработка ошибок

### 5.1. Глотание оригинальных исключений
```java
// FileStorageMinioService.java
} catch (Exception e) {
    throw new InternalFileStorageException();
}
```
Оригинальное исключение теряется, что затрудняет отладку. При этом логирование отсутствует.

**Рекомендация:** Логировать оригинальное исключение и передавать как cause:
```java
} catch (Exception e) {
    log.error("Failed to create directory: {}", path, e);
    throw new InternalFileStorageException("Failed to create directory", e);
}
```

### 5.2. Ловятся все Exception вместо конкретных типов
```java
} catch (Exception e) {
    throw new InternalFileStorageException();
}
```
MinIO выбрасывает специфичные исключения (`ErrorResponseException`, `InvalidKeyException` и др.), которые можно обработать по-разному.

---

# ХОРОШО

## 1. Инфраструктура

### 1.1. Использование Testcontainers для интеграционных тестов
```java
// AbstractIntegrationTest.java
@Container
protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
@Container
protected static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2.3");
@Container
protected static final MinIOContainer minio = new MinIOContainer("minio/minio:latest");
```
Отличный подход к тестированию с реальными зависимостями. Позволяет проверить интеграцию с PostgreSQL, Redis и MinIO.

### 1.2. Автоматическая инициализация MinIO bucket
```java
// MinioRootBucketInitializer.java
@PostConstruct
private void initRootBucket() {
    boolean found = minioClient.bucketExists(...);
    if (!found) {
        minioClient.makeBucket(...);
    }
}
```
Bucket создаётся автоматически при старте приложения. Удобно для деплоя.

### 1.3. Хранение сессий в Redis
```java
// application.properties
spring.session.store-type=redis
spring.session.redis.namespace=cloudstorage:sessions
spring.session.timeout=6h
```
Правильный подход для stateful сессий — хранение в Redis позволяет горизонтальное масштабирование.

### 1.4. Secure cookies
```java
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.secure=true
```
Хорошие настройки безопасности для сессионных cookies.

## 2. Архитектура

### 2.1. Иерархия моделей ресурсов
```java
public abstract class Resource {
    private String path;
    private String name;
    private Type type;
}

public class File extends Resource {
    private Long size;
}

public class Directory extends Resource { }
```
Чистая иерархия моделей с общим базовым классом.

## 3. Тестирование

### 3.1. Хорошее покрытие auth-флоу
```java
// AuthIntegrationTest.java
@Test void givenValidCredentials_whenRegister_then201()
@Test void givenUsernameIsBlank_whenRegister_then400()
@Test void givenAlreadyTakenCredentials_whenRegister_then409()
@Test void givenValidCredentials_whenLogin_then200()
@Test void givenAuthorizedUser_whenLogout_then204()
@Test void givenAuthorizedUser_whenLoggedIn_thenSessionExists()
```
Комплексное покрытие сценариев регистрации, логина и логаута.

### 3.2. Unit-тесты с Mockito
```java
// AuthServiceUnitTest.java
@ExtendWith(MockitoExtension.class)
public class AuthServiceUnitTest {
    @Mock private UserRepository userRepository;
    @Mock private AuthenticationManager authenticationManager;
    @InjectMocks private AuthService authService;
}
```
Хорошая структура unit-тестов с изолированными моками.

## 4. Документация

### 4.1. Swagger/OpenAPI
```java
// FileManagerController.java
@Operation(summary = "create directory")
public ResponseEntity<?> createDirectory(...)

@Operation(summary = "download resource (single file or zip archive)")
public ResponseEntity<byte[]> download(...)
```
Документирование API через аннотации OpenAPI.

---

# ЗАМЕЧАНИЯ

## config/

### 1. `SecurityConfig` — неиспользуемый `LogoutHandler` bean
```java
@Bean
public LogoutHandler logoutHandler() {
    return new LogoutHandler();
}
```
Бин создаётся, но logout отключен (`.logout(logout -> logout.disable())`), и логаут реализован вручную в `AuthService`. Либо использовать стандартный механизм, либо убрать бин.

### 2. Hardcoded JSON в handlers
```java
// RestAuthenticationEntryPoint.java
response.getWriter().write("""
        {"message": "Unauthorized"}
        """);
```
JSON формируется вручную. Лучше использовать `ObjectMapper` для консистентности.

---

## controllers/

### 3. `FileManagerController` — дублирование получения пользователя
```java
private UserResponseDto getUserDtoFromAuthentication() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    UserDetailsImpl principal = (UserDetailsImpl) authentication.getPrincipal();
    return UserMapper.INSTANCE.principalToUserResponseDto(principal);
}
```
Этот метод вызывается в каждом endpoint. Можно использовать `@AuthenticationPrincipal`:
```java
@GetMapping("/directory")
public ResponseEntity<?> getContent(
        @RequestParam(defaultValue = "") String path,
        @AuthenticationPrincipal UserDetailsImpl principal) {
    // ...
}
```
---

## services/

### 4. `FileStorageMinioService` — God-object
Класс содержит ~700 строк с логикой:
- Валидации путей
- CRUD операций с файлами
- CRUD операций с директориями
- ZIP-архивирования
- Вспомогательных методов

**Рекомендация:** Разделить на:
- `PathValidator` — валидация путей
- `DirectoryService` — операции с директориями
- `FileService` — операции с файлами
- `ZipService` — архивирование

### 5. Множественные вызовы `isDirectoryExist` и `isFileExist`
```java
if (!isDirectoryExist(extractParentDirectory(path), userResponseDto)) { ... }
if (isDirectoryExist(path, userResponseDto)) { ... }
```
Каждый вызов — запрос к MinIO. В одном методе может быть 3-4 запроса только для проверок.

### 6. Magic strings для формирования пути пользователя
```java
private String specificUserPath(Long id) {
    return "user-" + id + "-files/";
}
```
Лучше вынести шаблон в константу или конфигурацию.

---

## exceptions/

### 7. `InternalFileStorageException` — нет параметров для причины
```java
public class InternalFileStorageException extends CustomException {
    public InternalFileStorageException() {
        super("Unknown file storage exception", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```
Не принимает message и cause, что затрудняет отладку. Все ошибки MinIO выглядят одинаково.

**Рекомендация:**
```java
public InternalFileStorageException(String message, Throwable cause) {
    super(message, HttpStatus.INTERNAL_SERVER_ERROR);
    initCause(cause);
}
```

---

## test/

### 8. Отсутствуют интеграционные тесты для файловых операций
По ТЗ рекомендуется покрыть тестами:
- Загрузка файла → появление в MinIO
- Скачивание файла
- Создание/удаление директорий
- Переименование/перемещение
- Проверка изоляции пользователей (доступ к чужим файлам)
- Поиск

Сейчас есть только тесты для auth.

---

## docker/

### 9. Docker Compose — нет healthcheck для зависимостей
```yaml
services:
  db:
    image: postgres:17
    # Нет healthcheck
```
При запуске приложение может стартовать до готовности PostgreSQL/Redis/MinIO.

**Рекомендация:** Добавить healthcheck:
```yaml
db:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U db"]
    interval: 5s
    timeout: 5s
    retries: 5
```

### 10. Нет Dockerfile для приложения
В репозитории есть только `docker-compose.yml` для инфраструктуры, но нет Dockerfile для сборки самого приложения.

---

## Прочее

### 11. Нет логирования
В коде практически отсутствует логирование операций, кроме `MinioRootBucketInitializer`. При возникновении проблем будет сложно отладить.

### 12. Нет README с инструкциями по development
README содержит только инструкции по запуску, но нет информации о:
- Архитектуре
- Структуре API
- Запуске тестов
- Деплое

---

# СООТВЕТСТВИЕ ТЗ

## ✅ Реализовано корректно:
- Регистрация (201 Created + сессия)
- Авторизация (200 OK)
- Logout (204 No Content)
- Получение текущего пользователя (`/api/user/me`)
- Создание директории
- Получение содержимого директории
- Получение информации о ресурсе
- Удаление файлов и папок (рекурсивно)
- Загрузка файлов
- Скачивание файлов и папок (ZIP)
- Redis для сессий
- Liquibase миграции
- Swagger документация
- Docker Compose для инфраструктуры
- Интеграционные тесты (частично)

## ⚠️ Требует исправления:
| Пункт ТЗ | Проблема |
|----------|----------|
| Поиск файлов | 404 вместо пустого массива при отсутствии результатов |
| Move/Rename | Использует GET вместо POST/PATCH |
| Скачивание | Файлы загружаются в память целиком (нет стриминга) |
| Интеграционные тесты MinIO | Отсутствуют тесты для файловых операций |

---

# ВЫВОД

Проект выполнен **хорошо**. Основная функциональность реализована, код структурирован, есть тесты и документация.

## Критичные проблемы (нужно исправить):
1. **Файлы загружаются в память** → использовать стриминг

## Рекомендации по улучшению:
1. **Move использует GET** → изменить на POST/PATCH
2. **Поиск возвращает 404** → возвращать пустой массив
3. Добавить интеграционные тесты для файловых операций (MinIO + Testcontainers)
4. Реализовать стриминг для скачивания больших файлов
5. Использовать `copyObject` для перемещения вместо загрузки в память
6. Добавить логирование операций
7. Разбить `FileStorageMinioService` на более мелкие классы
8. Использовать `@AuthenticationPrincipal` вместо ручного получения пользователя
9. Добавить валидацию длины пути

**Оценка:** 7/10 — функционально рабочий проект с хорошей тестовой базой для auth, но с проблемами в обработке файлов и нарушениями REST-семантики.

