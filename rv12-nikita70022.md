[nikita70022/cloudfilestorage](https://github.com/nikita70022/cloudfilestorage)

## ХОРОШО

1. **Корректный `@EqualsAndHashCode` на JPA-сущности.** `User` использует `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` только по `id` — правильно, иначе Hibernate-прокси дают непредсказуемые результаты.

2. **Кастомный `JsonAuthFilter` с переопределённым `unsuccessfulAuthentication`.** Возвращает 401 с JSON-телом вместо стандартного redirect на форму логина — верный паттерн для REST API.

---

## ЗАМЕЧАНИЯ

### пакет /config

1. **Класс: `config.ApplicationConfig`, поля: `URL`, `ACCESS_KEY`, `SECRET_KEY`.
   Класс: `config.security.SecurityConfig`, поле: `NGINX_URL`.**

В проекте несколько классов читают свойства напрямую через `@Value`: `ApplicationConfig` читает три ключа MinIO,
`SecurityConfig` — ключ `spring.url.NGINX_URL`. При этом поля в `ApplicationConfig` и `SecurityConfig` названы в стиле
констант (`URL`, `ACCESS_KEY`, `SECRET_KEY`, `NGINX_URL`), что нарушает Java naming conventions.

Проблема в том, что `@Value` не даёт никакого единого места, где видна вся конфигурация группы: чтобы понять, какие
ключи нужны для MinIO, нужно знать, что искать именно в `ApplicationConfig`. При опечатке в имени ключа (
`@Value("${spring.minio.enpoint}")`) Spring запустится с неочевидной ошибкой, а не с «поле X не заполнено». Изменить имя
ключа — значит найти все `@Value`-инъекции по всему проекту вручную.

`@ConfigurationProperties` решает все эти проблемы: все свойства одной группы собраны в одном классе, имена полей
проверяются при старте через `@Validated`, IDE автодополняет ключи в `application.yaml`. Помимо этого, namespace
`spring.url.*` и `spring.minio.*` могут запутать тк можно подумать что это для конфигурации самого Spring Boot —
пользовательские свойства следует размещать под собственным префиксом, например `app.*`.

**Рекомендация:** Вынести конфигурацию в отдельные `@ConfigurationProperties`-классы с явным, не-spring-namespace'овым
префиксом:

   ```java
   // app/config/MinioProperties.java
@Configuration
@ConfigurationProperties(prefix = "app.minio")
@Validated
public class MinioProperties {
    @NotBlank
    private String endpoint;
    @NotBlank
    private String accessKey;
    @NotBlank
    private String secretKey;
}

// ApplicationConfig.java — только бины, без @Value
@Configuration
public class ApplicationConfig {
    @Bean
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }
}
   ```

2. **Класс: `config.security.SecurityConfig`, метод: `userDetailsService()`.**

`UserDetailsService` реализован как анонимный класс прямо внутри `SecurityConfig`. При этом `SecurityConfig` инжектирует
`UserRepository` напрямую только для этой цели. Это нарушение SRP: конфигурационный класс безопасности не должен знать о
деталях загрузки пользователей из базы данных. Изменение способа загрузки пользователей потребует правки
`SecurityConfig`. Кроме того, `userDetailsService()` возвращает объект Spring's `User`, в то время как класс
`MyUserDetails` (который умеет извлекать `id` и правильно конвертировать роли) игнорируется — это мёртвый код

**Рекомендация:** Вынести `UserDetailsService` в отдельный `@Service`-класс и использовать готовый `MyUserDetails`

3. **Класс: `config.security.SecurityConfig`, метод: `SecurityFilterChain()`.** 

`JsonAuthFilter` создаётся через `new JsonAuthFilter(objectMapper, authenticationManager)` прямо внутри метода `@Bean`. Это ручная инициализация
   объекта, зависимости которого разрешены явно, а не через Spring DI. Если в `JsonAuthFilter` потребуются новые зависимости, нужно будет менять код `SecurityConfig`. 

Имя метода `SecurityFilterChain` написано в PascalCase — это нарушение Java conventions (методы должны быть в camelCase). 

Дополнительно, в методе есть `http.cors(Customizer.withDefaults())` на строке 66, а следом cors(cors -> cors.configurationSource(corsConfigurationSource()))` — двойная CORS-конфигурация: первый вызов применяет умолчания, второй его переопределяет. Первый вызов избыточен.

**Рекомендация:**  Зарегистрировать `JsonAuthFilter` как `@Component`/`@Bean` и использовать constructor injection; переименовать метод

4. **Класс: `config.security.SecurityConfig`, метод: `passwordEncoder()`.** 

`BCryptPasswordEncoder` создаётся с cost factor 5. Это значительно ниже рекомендуемого OWASP минимума — 10 (**стандартное значение**). Cost factor 5 означает порядка 32 итерации, что на современном железе перебирается за миллисекунды. При утечке базы с хэшами пароли будут взломаны значительно быстрее.

**Рекомендация:** Не критично, но лучше повысить значение или хотябы использовать стандартное

5. **Класс: `config.security.MyUserDetails`.** 

6. Класс объявлен, полностью реализует `UserDetails`, умеет правильно извлекать `id` пользователя и конвертировать роли, но нигде не используется.

**Рекомендация:** Задействовать его в `UserDetailsServiceImpl` (см. замечание выше)

6. **Файл: `src/main/resources/application.yaml`, строки 60–70.** 

Структура YAML файла нарушена в двух местах. Во-первых, блок `volumes: - minio-data:/data` на строках 60-61 вложен в `spring.minio` — это не Spring Boot-свойство,
это синтаксис docker-compose. Во-вторых, `springdoc` на строке 66 вложен под ключ `volumes`, а не находится на корневом уровне. В результате Spring Boot никогда не прочитает `springdoc.api-docs.enabled: false` — Swagger UI включён в production

**Рекомендация:** Пересмотреть и исправить конфиг

7. **Класс: `config.ApplicationConfig`, метод: `modelMapper()`.** В проекте подключён и используется `ModelMapper` — библиотека, которая маппит поля между объектами через рефлексию в рантайме. Выбор этой библиотеки вызывает вопрос: в проекте маппятся два простых класса с тремя полями (`RequestUserDto → UserDto → User`), для чего `ModelMapper` явно избыточен. Рефлексия в рантайме означает отсутствие проверок на этапе компиляции — опечатка в имени поля или несовпадение типов проявятся только при запуске или в тесте. Стандартным выбором в экосистеме Spring Boot является **MapStruct**: он генерирует обычный Java-код на этапе компиляции (никакой рефлексии), ошибки маппинга видны сразу в IDE, производительность выше. Если маппинг настолько прост — достаточно статического фабричного метода без каких-либо библиотек.

   **Рекомендация:**
   ```java
   // Вариант 1 — MapStruct (для проектов с несколькими DTO):
   // pom.xml: добавить mapstruct + mapstruct-processor
   @Mapper(componentModel = "spring")
   public interface UserMapper {
       UserDto toDto(User user);
       User toEntity(UserDto dto);
   }

   // Вариант 2 — фабричный метод (достаточно для этого проекта):
   public class UserDto {
       public static UserDto from(RequestUserDto request) {
           return new UserDto(request.getUsername(), request.getPassword());
       }
   }

   // В любом случае — удалить ModelMapper из pom.xml и ApplicationConfig.
   ```

---

### пакет /models

1. **Класс: `models.User`, поле: `role`.** 

Поле `role` объявлено как `String`, хотя в проекте уже есть enum `Role`. Хранить роль как строку означает потерю типобезопасности: нигде нет гарантии, что в базе не окажется значение
`"ADMIN "` (с пробелом) или `"admin"` (в нижнем регистре). В `SecurityConfig.userDetailsService()` это приводит к `Role.valueOf(u.getRole())` — если значение в базе вдруг не совпадёт с именем enum-константы, получим
`IllegalArgumentException` в рантайме. С `@Enumerated(EnumType.STRING)` Hibernate сам контролирует корректность значения при записи и чтении.

**Рекомендация:** Использовать `@Enumerated(EnumType.STRING)`

2. **Класс: `models.User`** 

В проекте подключён Lombok и активно используется в других классах. Однако `User.java` содержит вручную написанные геттеры
и сеттеры (~30 строк). Это непоследовательно: Lombok уже является частью проекта, ручной код без причины создаёт разрыв в стиле кодовой базы

**Рекомендация:** Начать использовать ломбок
```java
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "users", schema = "auth")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "username", unique = true, nullable = false, length = 20)
    private String username;

    @Column(name = "password", nullable = false, length = 60)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Role role;
}
```

---

### пакет /repository

1. **Класс: `repository.UserRepository`.** Интерфейс объявлен как `JpaRepository<User, Integer>`, тогда как `User.id` имеет тип `Long`. Несовпадение типа ключа — скрытая проблема: при вызове `findById(Long id)` Spring Data автоматически приводит тип, и в большинстве случаев это работает, но поведение зависит от реализации. Правильный тип устраняет любую неоднозначность и позволяет компилятору/IDE корректно проверять вызовы.

   **Рекомендация:**
   ```java
   @Repository
   public interface UserRepository extends JpaRepository<User, Long> {
       Optional<User> findByUsernameIgnoreCase(String username);
   }
   ```

2. **Класс: `repository.UserRepository`, метод: `findByUsernameIgnoreCase()`. Файл: `db/migration/V1__init_db.sql`.** 

Метод ищет пользователя без учёта регистра (`WHERE LOWER(username) = LOWER(?)`), что подразумевает: `test` и `Test` — один и тот же пользователь. Однако `@Column(unique = true)` в PostgreSQL создаёт case-sensitive уникальный индекс — с точки зрения базы `test` и `Test` являются **разными** значениями, и оба можно вставить. Это приводит к реальному сценарию поломки: два одновременных запроса на регистрацию `test` и `Test` оба проходят проверку `usernameIsExist()` (каждый поток смотрит в базу до вставки другого), оба успешно вставляются в БД, а при последующем логине `findByUsernameIgnoreCase("test")` находит **два** ряда и бросает `IncorrectResultSizeDataAccessException` → 500. Намерение (case-insensitive уникальность) должно быть закреплено на уровне базы данных, а не только в Java-коде.

**Рекомендация:**
```sql
-- V2__add_username_case_insensitive_index.sql
-- Убрать существующий case-sensitive unique constraint и заменить
-- на функциональный индекс по LOWER(username):
ALTER TABLE auth.users DROP CONSTRAINT IF EXISTS users_username_key;
CREATE UNIQUE INDEX users_username_lower_idx ON auth.users (LOWER(username));
```

```java
// User.java — убрать unique=true с колонки (теперь уникальность через индекс):
@Column(name = "username", nullable = false, length = 20)
private String username;

// UserRepository — метод остаётся, теперь согласован с DB:
Optional<User> findByUsernameIgnoreCase(String username);
```

---

### пакет /services

1. **Класс: `services.AuthService`, метод: `isAuthenticated()`.** 

Метод возвращает `void` и бросает исключение, если пользователь не аутентифицирован. 
Здесь сразу две проблемы. Первая — нейминг: метод называется `is...`, а по Java-конвенции такой префикс означает предикат, возвращающий `boolean`. Читая `authService.isAuthenticated(authentication)` в контроллере, 
разработчик ожидает увидеть `if (authService.isAuthenticated(...))`, а не скрытый бросок исключения — это нарушает принцип наименьшего удивления. Метод, бросающий исключение как основной механизм управления потоком, должен называться `requireAuthenticated()` или `checkAuthenticated()`. 

Вторая проблема — неверный тип исключения: `BadCredentialsException` означает «неверные учётные данные при логине», а не «пользователь не вошёл». Наконец, вызов `authService.isAuthenticated(authentication)` в каждом методе контроллера — дублирование защиты, которую Spring Security уже обеспечивает автоматически через `.authenticated()` в `SecurityConfig` и `RestAuthenticatedEntryPoint`.

**Рекомендация:** Убрать вызовы из контроллеров полностью — SecurityConfig уже гарантирует, что до контроллера дойдут только аутентифицированные запросы.

2. **Класс: `services.AuthService`, метод: `login()`.** 

В методе есть лишняя строка `SecurityContextHolder.getContext().setAuthentication(authentication)` (строка 47), после которой немедленно
   создаётся новый пустой контекст через `securityContextHolderStrategy.createEmptyContext()`. Установка authentication
   в старый контекст бессмысленна, так как контекст сразу заменяется. Это мёртвый код, который вводит в заблуждение о
   намерении: создаётся впечатление двойной установки контекста с разными объектами.

**Рекомендация:**
```java
public void login(String username, String password,
                  HttpServletRequest request, HttpServletResponse response) {
    UsernamePasswordAuthenticationToken token =
        UsernamePasswordAuthenticationToken.unauthenticated(username, password);
    token.setDetails(new WebAuthenticationDetails(request));

    Authentication authentication = authenticationManager.authenticate(token);

    SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
    SecurityContext context = strategy.createEmptyContext();
    context.setAuthentication(authentication);
    strategy.setContext(context);

    securityContextRepository.saveContext(context, request, response);
}
```

3. **Класс: `services.UserService`, конструктор.** 

В классе есть публичный no-arg конструктор без всякой цели рядом с `@Autowired`-конструктором. Spring использует `@Autowired`-конструктор для инъекции зависимостей, а no-arg конструктор при этом позволяет создать `UserService` вне Spring-контейнера — в состоянии, где все поля `null`. Это открытая дверь для `NullPointerException` при случайном `new UserService()` в тестах или другом коде. 

Кроме того, `UserService` использует `ModelMapper` для маппинга `UserDto ↔ User`. Маппинг в сервисе — нормальная практика, но `ModelMapper` делает это через рефлексию в рантайме: ошибки несовпадения полей видны только при запуске, а не в IDE. По всему проекту `ModelMapper` стоит заменить на **MapStruct**, который генерирует обычный Java-код на этапе компиляции и показывает ошибки маппинга сразу.

**Рекомендация:** Заменить моделМаппер на мапстракт и пересмотреть использование ломбок

4. **Класс: `services.minioService.MinioBucketService`, метод: `bucketExist()`.** 

Метод бросает `new Exception()` —  «голое» исключение типа `java.lang.Exception`. Это нарушение нескольких принципов одновременно: нет информативного
   сообщения об ошибке, нет типобезопасности (любой вызывающий код вынужден использовать `@SneakyThrows` или делать
   `catch (Exception e)`), а `GlobalExceptionHandler` поймает это как `Exception.class` и вернёт 500 без тела.
   Пользователь получит 500 вместо корректного 404/503. Также `deleteBucket(User user)` принимает доменную
   JPA-сущность — инфраструктурный MinIO-сервис не должен знать о доменной модели.

**Рекомендация:** Создать специфичное исключение, а так же принимать username, а не User

5. **Пакет: `services.minioService`.** 

Название пакета нарушает сразу два соглашения Java. 
- Во-первых, пакеты называются в нижнем регистре и никогда в camelCase — `minioService` должно быть `minioservice` или, лучше, просто `minio`. 
- Во-вторых, слово `Service` в имени подпакета внутри `services` избыточно — контекст уже задан родительским пакетом. Разработчик, читающий `services.minioService`, видит тавтологию: «сервисы → сервис Minio». Правильное имя — `services.minio` или, если MinIO-классы вынести в инфраструктурный слой (что было бы архитектурно верно), — `infrastructure.storage.minio`.

**Рекомендация:** Переименовать пакеты

6. **Пакет: `services.minioService`, все классы.** 

Ни один из MinIO-сервисов не скрыт за интерфейсом. `MinioObjectService`, `MinioDirectoryService`, `MinioFileService` — это конкретные классы, напрямую работающие с `MinioClient` из SDK. 
Отсутствие абстракции означает, что MinIO жёстко вшит в бизнес-логику: если потребуется сменить хранилище на Amazon S3, Google Cloud Storage или локальную файловую систему — придётся переписывать все три сервиса, а вместе с ними и всё, что их использует. Это нарушение принципа инверсии зависимостей (DIP): модули верхнего уровня не должны зависеть от деталей реализации. 
Вместо этого сервисы должны зависеть от интерфейса хранилища, а MinIO — быть одной из подключаемых реализаций.

**Рекомендация:** Использовать интерфейсы, скрыть minio в конкретную реализацию

8. **Класс: `services.minioService.MinioObjectService`, метод: `searchObjects()`, строки 108–112.** 

Поле `path` у `MinioFile` устанавливается в `item.objectName()` — то есть полный путь объекта (`folder1/file.txt`), а не родительская директория (`folder1/`). 
По ТЗ `path` должен быть путём к папке, в которой лежит ресурс. Кроме того, `size` передаётся как строка в килобайтах (`String.valueOf(((double) item.size() / 1024))`), тогда как ТЗ явно требует числовое значение в байтах: `"size": 123`.

**Рекомендация:** В searchObjects() и во всех местах, где строится MinioFile
```java
String objectName = item.objectName();
String parentPath = MyPath.getParent(objectName); // например "folder1/"
String fileName = MyPath.getName(objectName);     // например "file.txt"
info.add(new MinioFile(
    parentPath,
    fileName,
    item.size(),   // long, в байтах
    Type.FILE));

// Поле size в MinioObject должно быть Long, а не String:
// abstract class MinioObject { ... Long size; ... }
```

9. **Класс: `services.minioService.MinioDirectoryService`, метод: `getDirectory()`, строка 76.** 

`zipOut.finish()` вызывается ВНУТРИ `for`-цикла после записи каждого файла. `ZipOutputStream.finish()` завершает архив и закрывает его
для дальнейшей записи. После первой итерации архив уже закрыт, и все последующие вызовы `zipOut.putNextEntry()` бросят `IOException`. В результате папки с несколькими файлами скачиваются как побитый архив, содержащий только первый файл. **Это критический функциональный баг.**

**Рекомендация:** Код выглядит неверно. Проверить функциональность и исправить

10. **Класс: `services.minioService.MinioDirectoryService`, метод: `getDirectory()`, строка 73.** 

Рекурсивный вызов `getDirectory(username, result.get().objectName())` при обнаружении вложенной директории возвращает новый
`StreamingResponseBody`, но возвращаемое значение никуда не сохраняется и не используется. Содержимое вложенных папок никогда не попадает в zip-архив. 
Это ещё один функциональный баг: скачивание папки не рекурсивно, вложенные папки теряются. Для корректной рекурсивной архивации нужно использовать `recursive(true)` в `listObjects` и обрабатывать все вложенные объекты в одном проходе, сохраняя структуру путей.

**Рекомендация:** Проверить и переписать

11. **Класс: `services.minioService.MinioDirectoryService`, метод: `copyDirectory()`.** 

Метод выполняет единственный `copyObject` — копирует только маркер директории (0-byte объект). Файлы и поддиректории внутри папки не копируются.
Когда `MinioObjectService.moveObject()` вызывает `copyDirectory()` + `deleteDirectory()`, все вложенные файлы теряются: удаляются, но не копируются. 
Это критический функциональный баг — перемещение/переименование директории с содержимым уничтожает данные.

**Рекомендация:** Проверить и переписать

12. **Класс: `services.minioService.MinioDirectoryService`, метод: `deleteDirectory()`.** 

`listObjects` вызывается с `recursive(false)` — перечисляются только прямые потомки директории. Вложенные поддиректории и их содержимое не удаляются. 
При удалении директории с вложенными папками объекты в MinIO остаются висеть «осиротевшими»: родительская
директория удалена, содержимое недостижимо через UI, но данные продолжают занимать место. Также в этом методе используется `System.out.println(error.get())` вместо логгера — нарушение принятого в проекте стиля логирования

 **Рекомендация:** Сделать рекурсивное удаление и поправить логирование

13. **Класс: `services.minioService.MinioDirectoryService` и `MinioFileService`, дублирование `bucketExists`.** В `MinioDirectoryService.getDirectoryInfo()`, `createDirectory()`, `copyDirectory()`, `deleteDirectory()` и в `MinioFileService.copyFile()`, `deleteFile()`, `createDir()` повторяется одна и та же проверка `minioClient.bucketExists(...)`. У этого подхода три независимые проблемы.

- Во-первых, **дублирование**: `MinioObjectService` перед каждым вызовом уже вызывает `bucketService.bucketExist()`. Каждая внутренняя проверка — лишний HTTP-запрос к MinIO ради того, что уже проверено выше по стеку.

- Во-вторых, **проверка не атомарна**: между `bucketExists()` и последующей операцией (`putObject`, `copyObject` и т.д.) бакет теоретически может исчезнуть. Паттерн check-then-act не даёт никаких гарантий в распределённой системе — MinIO сам вернёт ошибку при обращении к несуществующему бакету.

- В-третьих, **при `false` ничего не происходит**: если `bucketFound == false`, методы молча выходят без исключения и без какого-либо результата. Вызывающий код получает тихий no-op вместо ошибки — файл не сохранён, директория не создана, но исключения нет. Это скрытый баг: операция выглядит успешной, хотя ничего не сделала.

**Рекомендация:** Убрать эту проверку вообще

14. **Класс: `services.minioService.MinioFileService`, метод: `putFile()`, строка 43.** 

При загрузке файла в `PutObjectArgs` передаётся `is.available()` как размер потока. `InputStream.available()` возвращает количество байт,
доступных без блокирования — это не то же самое, что общий размер файла. Для HTTP multipart-потоков это значение  часто некорректно (может быть 0 или частичный буфер). MinIO SDK при `-1` в размере использует chunked upload, при
неверном значении `available()` — передаёт неверный `Content-Length`, что ведёт к битым файлам при больших загрузках. Правильный источник размера — `MultipartFile.getSize()`.

 **Рекомендация:** Исправить
 ```java
 @SneakyThrows
 protected void putFile(String username, String path, MultipartFile file) {
     String objectName = path + MyPath.normalized(file.getOriginalFilename());
     fileExist(username, objectName);
     directoryService.createDirectoriesIfNotExist(username, path + MyPath.getParent(file.getOriginalFilename()));

     try (InputStream is = file.getInputStream()) {
         minioClient.putObject(
             PutObjectArgs.builder()
                 .bucket(username)
                 .object(objectName)
                 .stream(is, file.getSize(), -1)  // ← file.getSize() вместо is.available()
                 .contentType(file.getContentType())
                 .build()
         );
     }
 }
 ```

15. **Аннотация `@SneakyThrows` из Lombok повсеместно.** 

`@SneakyThrows` используется практически на всех методах в проекте. Аннотация бросает checked-исключения как unchecked, маскируя их от компилятора. Это разрушает информацию о том, что именно может пойти
 не так: вызывающий код не видит, что метод может бросить `MinioException`, `IOException` и т.д. Когда такое исключение вылетает в рантайме, `GlobalExceptionHandler` ловит его как `Exception.class` и возвращает 500 без
информации об ошибке. Правильный подход — явно обрабатывать `MinioException` и превращать их в доменные исключения.

 **Рекомендация:** Убрать `@SneakyThrows`. В реальной работе почти не используется эта аннотация

---

### пакет /utils

1. **Класс: `utils.minioValidation.S3Valid`.** 

НЕ КРИТИЧНО!! Утильный класс со статическими методами не имеет `private`-конструктора.
Любой код может написать `new S3Valid()` — бессмысленное создание объекта, которое ни на что не влияет, но создаёт путаницу. Для utility-классов в Java принято добавлять приватный конструктор с `throw new UnsupportedOperationException()`.

**Рекомендация:**
 ```java
 public final class S3Valid {

     private S3Valid() {
         throw new UnsupportedOperationException("Utility class");
     }

     // все статические методы...
 }
 ```

2. **Класс: `utils.minioValidation.S3Valid`, метод: `isFile()`.** 

Метод проверяет `!name.endsWith("__XLDIR__")`. `__XLDIR__` — это внутренний маркер MinIO для директорий в некоторых версиях клиента. Знание об этом маркере является деталью реализации MinIO SDK и не должно проникать в класс общей S3-валидации. 
Это утечка инфраструктурной детали в слой бизнес-правил: если MinIO изменит формат маркера, нужно будет искать его не только в MinIO-сервисах, но и в валидаторе. Такая логика должна быть сосредоточена в `MinioDirectoryService`, где определяется, является ли объект директорией.

**Рекомендация:** Убрать из валидатора `__XLDIR__` и изалировать за конкретной реализацией

3. **Класс: `utils.MyPath`, метод: `normalized()`.** 

Метод заменяет `:` на `/`, это хак для обработки Windows-путей из браузера (например, `C:\folder\file.txt`). Логика не документирована ни комментарием, ни именем метода — `normalized`
не намекает на замену двоеточий. Если браузер изменит формат имён, или если в имени файла законно окажется `:` (например, `video:1920x1080.mp4`), метод молча исказит имя. 
Хак должен быть изолирован, явно назван и документирован, а лучше — вынесен в отдельную обработку только при разборе пути из multipart-запроса.

**Рекомендация:** Переименовать и задокументировать

---

### пакет /web/controllers

1. **Пакеты: `web.controllers`, `web.dto`, `web.exceptions`.** 

Все три пакета вложены в `web`, что нетипично для Spring Boot-проектов и создаёт лишний уровень вложенности без смысловой нагрузки. В Spring-приложениях принято держать пакеты плоскими и называть их по роли: `controller`, `dto`, `exception`. 
Префикс `web` не несёт информации — всё приложение является веб-приложением, и выделять «веб-слой» отдельным пакетом не нужно. Кроме того, название `controllers`(так же как и `services`) во множественном числе нарушает Java-конвенцию: пакеты называются в единственном числе (`controller`, `service`, `repository`). Та же проблема с `exceptions` — должно быть `exception`.

**Рекомендация:** Переименовать пакеты

2. **Класс: `web.controllers.AuthController`, конструктор.** 

`AuthController` инжектирует `MinioBucketService` и вызывает `minioBucketService.createBucket(user.getUsername())` прямо в методе `registration()`. Создание MinIO-бакета — это инфраструктурная операция, которая должна происходить на уровне сервиса при создании пользователя,
а не в контроллере. Контроллер оркестрирует HTTP-взаимодействие, но не должен знать о деталях хранилища. Если позднее потребуется добавить событие «пользователь создан» (например, отправить письмо, завести ещё одну инфраструктуру),
снова придётся добавлять вызовы в контроллер. Правильное место — `UserService.create()`

**Рекомендация:** убрать бизнес-логику из контроллера. Контроллер должен ТОЛЬКО вызывать сервис

3. **Класс: `web.controllers.AuthController`, метод: `registration()`, строки 50–58.** 

Валидационные ошибки `@Valid` обрабатываются вручную через `BindingResult`: собирается `Map<field, message>` и возвращается 400. Это обходит
`GlobalExceptionHandler` и создаёт несогласованный формат ответа. ТЗ требует `{"message": "Текст ошибки"}` для всех ошибок, а текущий ответ возвращает `{"username": "Name should not be empty", "password": "..."}`. 
Правильный подход — убрать `BindingResult` из сигнатуры (Spring тогда автоматически бросает `MethodArgumentNotValidException`) и обработать её в `GlobalExceptionHandler`.

**Рекомендация:** убрать BindingResult и написать обработчик в `GlobalExceptionHandler`
 ```java
 // AuthController.java — убрать BindingResult:
 @PostMapping("/sign-up")
 public ResponseEntity<Map<String, String>> registration(
         @RequestBody @Valid RequestUserDto dto,  // без BindingResult
         HttpServletRequest request, HttpServletResponse response) {
     // если @Valid не прошёл — Spring сам бросит MethodArgumentNotValidException
     // ...
 }

 // GlobalExceptionHandler.java — добавить обработчик:
 @ExceptionHandler(MethodArgumentNotValidException.class)
 public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
     String message = ex.getBindingResult().getFieldErrors().stream()
         .map(FieldError::getDefaultMessage)
         .findFirst()
         .orElse("Validation error");
     return ResponseEntity.status(400).body(Map.of("message", message));
 }
 ```

4. **Класс: `web.controllers.AuthController`, метод: `login()`, строка 92.** 

После вызова `authService.login(...)` метод читает имя пользователя из `authentication` — параметра, который Spring инжектирует из SecurityContext на момент
НАЧАЛА обработки запроса, до вызова `authService.login()`. Если пользователь не аутентифицирован (состояние anonymous), `authentication.getPrincipal()` возвращает `String` (имя анонимного пользователя), и
`(UserDetails) authentication.getPrincipal()` бросает `ClassCastException`. Даже если пользователь уже аутентифицирован, читается старый principal, а не тот, что установлен после `login()`. Имя пользователя нужно брать из переданных данных или из нового контекста.

**Рекомендация:**
 ```java
 // Неверно — читаем из старого authentication-объекта:
 return ResponseEntity.ok(Map.of("username",
     ((UserDetails) authentication.getPrincipal()).getUsername())); // ClassCastException если anonymous

 // Верно — имя берём из DTO (оно уже провалидировано):
 @PostMapping("/sign-in")
 public ResponseEntity<Map<String, String>> login(
         @RequestBody @Valid RequestUserDto dto,
         HttpServletRequest request, HttpServletResponse response) {
     authService.login(dto.getUsername(), dto.getPassword(), request, response);
     return ResponseEntity.ok(Map.of("username", dto.getUsername()));
 }
 ```

5. **Класс: `web.controllers.StorageController`, все поля.** 

Все зависимости инжектируются через field injection (`@Autowired` прямо на поле). Field injection — антипаттерн для Spring: класс нельзя создать в тесте без рефлексии, нельзя сделать поля `final`, нарушается явная зависимость (зависимости скрыты). Constructor injection — стандарт с Spring 4.3+.

**Рекомендация:** Сделать поля файнал и использовать `@RequiredArgsConstructor`

6. **Класс: `web.controllers.StorageController`, метод: `moveFile()`, строка 102.** 

Маппинг объявлен как `@GetMapping(value = "/api/resource/move")`, тогда как контроллер уже имеет `@RequestMapping("/api")`. Spring конкатенирует оба пути, и реальный URL становится `/api/api/resource/move`. Эндпоинт переименования/перемещения физически недостижим по URL `/api/resource/move`, который ожидает фронтенд. Это баг, при котором вся функциональность перемещения файлов сломана.

**Рекомендация:** исправить `@GetMapping`

7. **Класс: `web.controllers.StorageController`, метод: `uploadObject()`, строка 69.** 

Параметр `path` объявлен как `@RequestParam(required = false)`. Если клиент не передаёт `path`, значение будет `null`. Далее `path` передаётся в `MinioObjectService.uploadObjects()`, который вызывает `S3Valid.parentIsValid(path)`, а в `Pattern.matcher(null)`
бросает `NullPointerException`. NPE вместо корректного `ValidationException` с сообщением означает, что клиент получит 500 без причины. Путь должен быть либо обязательным, либо иметь явный default (`""`), а NPE-защита должна быть в самом начале.

**Рекомендация:** Сделать path обязательным

8. **Класс: `web.controllers.AuthController`, маппинг `GET /me`.** 

По ТЗ эндпоинт «Текущий пользователь» описан как `GET /user/me`. В проекте он реализован как `GET /api/auth/me`. Это несоответствие ТЗ. Не критично, но все же

**Рекомендация:** Аргументировать почему так, либо сделать как по тз

---

### пакет /web/dto

1. **Класс: `web.dto.UserDto`, поля и методы.** 

Аннотации Bean Validation (`@Size`, `@Pattern`, `@NotNull`, `@NotBlank`) продублированы трижды: на поле `password`, на геттере `getPassword()` и на параметре сеттера `setPassword()`. В Java Bean Validation срабатывает только один раз при валидации объекта — дублирование на геттерах/сеттерах ничего не добавляет, только создаёт расхождение: при изменении ограничения на поле нужно не забыть обновить геттер и сеттер. Это около 30 лишних строк кода.

`@NotNull` и `@NotBlank` на одном поле. `@NotBlank` уже включает в себя проверку на `null` — строка, которая `null`, автоматически не пройдёт `@NotBlank`. Добавлять `@NotNull` рядом с `@NotBlank` избыточно: это два ограничения, проверяющих одно и то же условие.

**Рекомендация:** Привезти код в порядок, избавиться от лишних аннотаций + лучше поделить response/request аннотации на разные пакеты

2. **Класс: `web.dto.RequestUserDto`.** 

Аннотация `@Data` генерирует сразу `equals()`, `hashCode()` и `toString()` по всем полям, включая `password`. Сгенерированный `toString()` выведет пароль в открытом виде в любом логе, где объект попадёт в сообщение: `RequestUserDto(username=user1, password=Secret123!)`. Это прямая утечка пароля — достаточно одного `log.debug("Request: {}", dto)` где угодно в стеке вызовов. `equals()` и `hashCode()` по паролю также бессмысленны для DTO: request-объекты никогда не кладут в `Set` и не сравнивают между собой. Дополнительно, `@Data` без `@NoArgsConstructor` оставляет только all-args конструктор, что создаёт риски при JSON-десериализации: Jackson без явной конфигурации может не найти подходящий конструктор.

Для request/response DTO достаточно только геттеров — никаких сеттеров, `equals`, `hashCode`, `toString`. Оптимальный вариант — Java `record`, который по умолчанию имеет только геттеры и не генерирует ничего лишнего; при необходимости `toString()` можно переопределить вручную, скрыв пароль.

**Рекомендация:** Выбрать какой-то 1 вариант, либо рекорды либо только геттеры с ноАргс

3. **Класс: `web.dto.minio.MinioObject`, поле `size`.** 

Поле `size` объявлено как `String`, а значение формируется как `String.valueOf(((double) item.size() / 1024))` — то есть передаётся размер в килобайтах в виде строки, например `"12.345678"`. ТЗ явно требует `"size": 123` — числовое значение в байтах. 
Текущее поведение нарушает контракт API: фронтенд получает строку вместо числа и в килобайтах вместо байт. Кроме того, тип `String` для числового поля лишает JSON-схему типобезопасности.

**Рекомендация:** Передавать байты, и перейти на Long + переименовать тк не понятно, это реквестДто/респонсДто что это за обжект

4. **Класс: `web.dto.minio.MinioObject`, поля.** 

Поля `path`, `name`, `size`, `type` объявлены без модификатора доступа — то есть они package-private. Lombok `@Getter`/`@Setter` генерирует публичные аксессоры, но прямой доступ к полям из классов того же пакета остаётся без инкапсуляции. Все поля должны быть `private`.

**Рекомендация:** добавить private

5. **Интерфейсы: `web.dto.openApi.authApiDoc`, `web.dto.openApi.storageApiDoc`.** 

У этих интерфейсов три проблемы одновременно.

- Во-первых, все методы объявлены с модификатором `public`. В Java методы интерфейса неявно `public abstract` — явный `public` избыточен и только засоряет код. IDEA уже помечает как лишний код который надо удалить.

- Во-вторых, имена интерфейсов написаны в lowerCamelCase (`authApiDoc`, `storageApiDoc`). По Java-конвенции имена классов и интерфейсов — всегда PascalCase: `AuthApiDoc`, `StorageApiDoc`.

- В-третьих, `StorageController` вообще не объявляет `implements storageApiDoc`. Это означает, что весь интерфейс `storageApiDoc` — мёртвый код: ни один контроллер его не реализует, Swagger-аннотации из него не применяются, и компилятор не проверяет соответствие сигнатур.

**Рекомендация:** Убрать public у методов интерфейса, переименовать в PascalCase, имплементировать/удалить стореджАпиДок

---

### пакет /web/exceptions

1. **Класс: `web.exceptions.GlobalExceptionHandler`.** 

Обработчик не содержит `@ExceptionHandler(MethodArgumentNotValidException.class)`. Когда `@Valid` в контроллере обнаруживает нарушение Bean Validation, Spring бросает `MethodArgumentNotValidException`. Этот тип не обрабатывается явно, поэтому попадает в catch-all `Exception.class` и возвращает 500 без тела. 
Пользователь не получает никакой информации о том, что именно неверно в запросе. Кроме того, ТЗ требует `{"message": "..."}` для всех ошибок, но текущие обработчики `ResourceNotFoundException` и `BadCredentialsException` возвращают `ResponseEntity<Void>` — тело ответа пустое.

**Рекомендация:** добавить обработчик + привести к формату

2. **Класс: `web.exceptions.GlobalExceptionHandler`, метод: `validateExceptionHandler()`, строка 22.**


`log.error(e.getMessage(), request.getRequestURI(), e)` — некорректное использование SLF4J. Первый аргумент воспринимается как format string с `{}` плейсхолдерами. 
В `e.getMessage()` нет плейсхолдеров, поэтому `request.getRequestURI()` как второй аргумент будет проигнорирован. Вместо URI в логе окажется только сообщение об
ошибке без контекста запроса. Аналогично, `log.error("Resource if exist" + request.getRequestURI(), e)` в `conflictExceptionHandler` — конкатенация строк в logger

**Рекомендация:**
 ```java
 // Неверно:
 log.error(e.getMessage(), request.getRequestURI(), e);
 log.error("Resource if exist" + request.getRequestURI(), e);

 // Верно:
 log.error("Validation error on {}: {}", request.getRequestURI(), e.getMessage());
 log.error("Resource conflict on {}", request.getRequestURI(), e);
 ```

---

## РЕКОМЕНДАЦИИ

1. **Ввести абстракцию над MinIO-клиентом.**

2. **Привести структуру бакетов к требованиям ТЗ.** По ТЗ все файлы хранятся в одном бакете `user-files`, а для каждого пользователя создаётся папка `user-${id}-files/`. 
В текущей реализации под каждого пользователя создаётся отдельный бакет с именем `username`. MinIO имеет лимиты на количество бакетов, использование username в качестве имени бакета
накладывает ограничения на допустимые символы (имя бакета в S3 имеет строгие правила), а сами MinIO-пути утекают в API-ответы через поле `path`. Рефакторинг: один бакет, папки по `id`.

3. **Устранить дублирование проверок существования ресурса.** 

4. **Заменить `ModelMapper` на `MapStruct` во всём проекте.** `ModelMapper` выполняет маппинг через рефлексию в рантайме: ошибки несовпадения полей проявляются только при запуске или в тесте, IDE не помогает. `MapStruct` генерирует обычный Java-код на этапе компиляции — ошибки маппинга видны сразу, производительность выше, отладка проще

5. **Выделить конфигурацию из бизнес- и инфраструктурного слоёв.** Ввести `@ConfigurationProperties` и убрать все `@Value`-инъекции из `ApplicationConfig` и `SecurityConfig`.
Переименовать кастомные ключи с `spring.*` пространства на `app.*`, чтобы не конфликтовать с зарезервированными Spring Boot namespace.

6. **Привести форматы ошибок в соответствие с ТЗ.** 

7. **Добавить тесты для MinIO-операций.** Текущее покрытие ограничено тестами авторизации. Критические операции (upload,
   download, move, delete, создание директории) не покрыты. Testcontainers позволяет запустить MinIO в тесте через
   `GenericContainer<>("minio/minio:...")` с кастомным `entrypoint`. Минимальный набор: загрузка файла создаёт объект в
   бакете; перемещение директории с файлами корректно копирует содержимое; удаление директории удаляет все вложенные
   объекты.

8. **Привести использование Lombok к единому стилю по всему проекту.** 

---

## ИТОГ

Проект в текущем состоянии **не работает корректно**. Большинство ключевых функций файлового хранилища либо сломаны, либо работают неверно: скачивание папок отдаёт битый архив из-за `zipOut.finish()` в цикле, перемещение директорий с содержимым уничтожает все вложенные файлы, удаление папок не затрагивает поддиректории, загрузка больших файлов даёт битый результат из-за `is.available()`, а эндпоинт переименования/перемещения физически недостижим из-за задвоенного префикса `/api/api/resource/move`. Из восьми заявленных операций с файлами как минимум половина не работает так, как ожидает пользователь.

Код написан небрежно. Это выражается не в одной-двух ошибках, а в системных признаках: дублирование одних и тех же проверок в пяти местах, `System.out.println` вместо логгера, `new Exception()` без сообщения, аннотации валидации продублированы на полях, геттерах и сеттерах одновременно, конфигурация размазана по классам через `@Value`, структура пакетов нарушает базовые Java-конвенции, Lombok используется вперемешку с ручным кодом без какой-либо логики. Это признак того, что код писался без понимания того, почему те или иные решения принимаются.

Уровень реализации соответствует человеку, который имеет базовое знакомство с Java и Spring, но ещё не имеет опыта написания production-кода: видно желание использовать «взрослые» инструменты (MinIO SDK, Spring Security, Testcontainers, Swagger), но без понимания принципов, которые делают код поддерживаемым и рабочим. Архитектурное мышление — слои, зависимости, изоляция инфраструктуры — пока не сформировано.

Что нужно сделать в первую очередь, чтобы проект хотя бы заработал: исправить критические баги в MinIO-сервисах (zip, copy, delete, upload), починить URL move-эндпоинта. Затем привести API в соответствие с ТЗ (типы полей, пути эндпоинтов, формат ошибок).

Для роста стоит изучить: Java Naming Conventions — пакеты в нижнем регистре и единственном числе, классы и интерфейсы в PascalCase, методы и поля в camelCase, это база, которую нужно довести до автоматизма; интерфейсы как инструмент абстракции — не только для Spring-контрактов, но и для изоляции сервисов, репозиториев и инфраструктурных клиентов от деталей реализации; принцип инверсии зависимостей (DIP) и паттерн Port & Adapter на практических примерах; `@ConfigurationProperties` для типобезопасной конфигурации; MapStruct как альтернативу рефлексивному маппингу; а также официальную документацию Spring Security по жизненному циклу `SecurityContext`.
