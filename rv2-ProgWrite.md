[ProgWrite](https://github.com/ProgWrite/CloudStorage)

# НЕДОСТАТКИ РЕАЛИЗАЦИИ
1. Dockerfile ```FROM eclipse-temurin:17-jdk-alpine``` работает только под amd64, у меня на маке с arm ошибка
2. Dockerfile ``` => ERROR [3/3] COPY build/libs/*.jar app.jar``` сам не билдит проект. Нужно руками запускать сначала билд
3. docker-compose.yml ```MINIO_BUCKET_NAME=your_bucket_name``` не по Amazon стандарт, с таким примером не стартует
4. docker-compose.yml ```MINIO_URL=http://localhost:9000``` указан не верно в примере, не работает по дефолту
5. application.yml почему-то 2 раза дублирует настройки
6. Ограничение длины пароля - зачем? Это снижает безопасность
7. 


# ХОРОШО
1. Написано большое количество тестов
2. Много что залогировано
3. Есть миграции

Чтож, пойдем сверху вниз по иерархии файлов и папок. ApiDocs первый
# ЗАМЕЧАНИЯ
1. apiDocs не лучшее название для пакета. Лучше просто api
2. отсутсвует ```@Override``` на реализациях
3. Возвращать Map не очень красиво, лучше Dto
```
   @GetMapping("/me")
    public ResponseEntity<Map<String,String>> getCurrentUser(Authentication authentication) {
        return ResponseEntity.ok(Map.of("username", authentication.getName()));
    }
```
пакет config
4. Это вполне рабочий вариант
```
@Bean
    public MinioClient minioClient(
            @Value("${MINIO_URL}")
            String url,
            @Value("${MINIO_USER}")
            String username,
            @Value("${MINIO_PASSWORD}")
            String password) {
        return MinioClient.builder()
                .endpoint(url)
                .credentials(username, password)
                .build();
    }
```
 но гораздо лучше создать и потом внедрять в кофниг, пропертю в случае необходимости сможешь внедрить и в другие места не делая каждый раз @Value
```
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {
    private String url;
    private String user;
    private String password;
}
```
5. не везде красивые чистые импорты, есть места с двойными пробелами
```
package org.example.cloudstorage.config;


import jakarta.servlet.http.HttpServletResponse;
//lalalala....

@Configuration
```
6. SecurityConfiguration
   ```.requestMatchers("/", "/index.html", "/static/**", "/favicon.ico", "/manifest.json", "/assets/**", "/config.js", "/login", "/registration", "/files/**").permitAll()``` список кажется немного длинноват. Лучше вынести в что-то типо ```private static final String[] PUBLIC_URLS```
7. SessionConfiguration. Это кажется не правильным, заечем @Setter? Почему поле не final?
```
   @Setter
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 3600)
public class SessionConfiguration {

    private ClassLoader loader;
```
8. DirectoryController
  Контроллеры принято писать "плоскими". У тебя не должно быть userService, ты должен делать ```return directoryService.getDirectory(fileSystemDto, userDetails)``` и все
```
  Long id = userService.getId(userDetails.getUsername());
  List<ResourceResponseDto> resource = directoryService.getDirectory(id, fileSystemDto.path(), TraversalMode.NON_RECURSIVE);
```
9. ResourceController
   Тебе повезло что спринг без ```@ModelAttribute``` понял что ему нужно распарсить данные, но лучше такие вещи помечать аннотаицей
пакет dto
10. ```fileSystemRequestDto``` не соблюден java naming convention. 
1) нельзя применять большие буквы в пакетах
2) dto это класс, а не пакет
лучше назвать filesystem и отдельно внутри dto сделать request и response пакеты
3) ```@Pattern(regexp = "^[^\\\\:*?\"<>|]*$", message = "Path contains invalid characters")``` лучше regexp вынести в коснатанту и избавимся от дублирования

11. ResourceResponseDto
     Хорошее решение. Можно указать ```@Schema(oneOf = {FileResponseDto.class, FolderResponseDto.class})```
12. FolderResponseDto
    Лучше реализовать type вот так, чтобы случайно не перепутать при создании. Ниже затрону тему маппинга, но мне кажется тут будет не плох вариант с ```FolderResponseDto.of()```
```
    public record FolderResponseDto(String path, String name) implements ResourceResponseDto {
    @Override
    public ResourceType type() {
        return ResourceType.DIRECTORY;
    }

    public static FolderResponseDto of(String path, String name) {
        return new FolderResponseDto(path, name);
    }
}
```
13. UserRegistrationRequestDto UserAuthorizationRequestDto почему-то классы) Хотя остальные объекты record
пакет exception
14. Относится ко всем экспешенам
    Есть лишние конструкторы которые не используются. Неиспользуемый код - удаляем.
15. GlobalExceptionHandler
    Можно сильно сократить и сдлеать красивше обработчик и лучше масштабирование. Можно ловить Exception, и сделать мапу твоих экспешенов к httpstatus

пакет mapper
16. FileSystemMapper
1) Не лучшая идея не прозрачный маппинг, могут возникнуть проблемы дебага и тд. Лучше сделай toFolderDto / toFileDto. А еще у тебя где-то нейминг Folder, где-то Directory. Определись что это все-таки
```
  default ResourceResponseDto itemToDto(Item item, String path) {
    boolean isDirectory = item.objectName().endsWith("/");

    if (isDirectory) {
        return itemToFolderDto(item, path);
    } else {
        return itemToFileDto(item, path);
    }
}
```
2) ```@Mapping(target = "type", expression = "java(ResourceType.FILE)")``` Это не правильно.
   Правильно ```@Mapping(target = "type", constant = "FILE")```, мапстракт за тебя все сделает
3) Для чего тебе оно?) Это же тоже самое что ```@Mapping(target = "size", source = "item.size")```
   ```
   @Mapping(target = "size", source = "item", qualifiedByName = "extractSize")
   
   @Named("extractSize")
    default Long extractSize(Item item) {
        return item.size();
    }
   ```
4) Рекомендую установить плагин https://plugins.jetbrains.com/plugin/10036-mapstruct-support очень помогает в работе с мапстрактом
17. UserMapper
    Честно, не проверял, но думаю что toUserDetails можно написать без default
пакет model
18. User
    1) У всех принято по разному, конкретно у меня на работе не принято писать @Table(name = "users"), мы называем таблицы по ентити (не во множественном числе)
19. @AllArgsConstructor не обязателен для ентити
пакет repository
21. UserRepository
    Искать айди по нику - не правильный подход по жизни. Надо всегда делать наоборот. Поэтому предлагаю сделать свою реализацию UserDetails для сессий который не только username и password будет хранить, но и id
```
    @Query("SELECT u.id FROM User u WHERE u.username = :username")
    Long findIdByUsername(@Param("username") String username);
```
Пакет service
22. По ООП, лучше сделать каждому интерфейсу так же как ты делал контроллерам интерфейсы, и их реализовывать (например - разные версии api)
23. DirectoryService
  1) ```if (!path.endsWith("/") || !isPathValid(path))``` а первая проверка точно нужна?
  2) ```public FolderResponseDto createDirectory(Long id, String path) {``` Лучше называть не просто Long id, а Long userId. Потому что я во время чтения несколько раз запутался.
24. MinioClientService
  1) ```private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";``` - > ```MediaType.APPLICATION_OCTET_STREAM_VALUE```
  2) статики идут выше остальных полей. (это замечанеи применимо ко всем файлам)
  3) почему в statObject не логгируешь никакую ошибку и не кидаешь в логи ничего? Просто пустой ответ
     ```
     } catch (Exception exception) {
            return Optional.empty();
        }
     ```
25. ResourceService
    1) Может сначала будем делать проверку на валидность, а потом что-то экстрактить?)
       ```
       public ResourceResponseDto getResourceInfo(Long id, String path) {
        String parentPath = extractParentPath(path);

        if (path.equals(ROOT_PATH) || path.contains("//")) {
            throw new InvalidPathException("Invalid path");
        }
       ```
    2) Я может не понимаю отличия, но мне кажется 1 из этих ифов убрать можно
       ```
       if (isFileExists(files, path, id)) {
            throw new ResourceExistsException("File with this name already exists");
        }
        if (isResourceExists(id, path, files)) {
            throw new ResourceExistsException("Resource with this name already exists in this directory");
        }
        ```
    3) Тебе даже идея подсвечивает, что код упростить можно
    ```
    if (!minioClientService.isPathExists(id, path) && path.endsWith("/")) {
            return getUploadedFiles(files, id, path);
        }
        return getUploadedFiles(files, id, path);
    ```
    4) в delete тоже как-то слишком много проверок. У тебя же delete дергает deleteFolder через ```if (path.endsWith("/")) {```, и внутри deleteFolder опять такая проверка. Можно упростить метод
   
26. StoragePathService
    Больше похоже не на service, а на какой-то util класс
27. UserService
    1) createUser метод выглядит избыточным, усложняя и на то непростой код
    2) ```createUserWithRootDirectory``` Это выглядит и звучит так, что это должно дергаться 2 метода в authService, а не 1 метод в userService

# ВЫВОД
1. Проект выполнен хорошо, есть проблемы с форматированием кода и небольшие замечания. Функциональность соблюдена
2. 
