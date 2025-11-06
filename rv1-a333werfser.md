https://github.com/a333werfser/cloud-file-storage
[a333werfser]

# Ловлю различные ошибки, не могу потестить приложение

# НЕДОСТАТКИ РЕАЛИЗАЦИИ
1. Неработающий фронтенд в докере, из-за него не поднимаются остальные контейнеры
2. Нет файлов-заглушек для ```file: ./secrets/.psql_password```
3. Странный api_base_url у фронтенда "http://192.168.0.190:8080", не работает из коробки
4. Очень долгий фронтенд, грузится много лишнего, есть запросы которые заведомо не отработают, но ты их кидаешь, например
  ```/api/user/me``` на странице авторизации/регистрации, кидает 401
6. Плохо настроены корсы, пришлось много че редактировать чтоб завелось
   
# ХОРОШО
1. Секреты для Докера БД вынесены в отдельные файлы
2. Большое покрытие тестами

# ЗАМЕЧАНИЯ
1. Если делаешь монорепозиторий, дели на модули. А то бэкенд у тебя лежит просто в src, а фронтенд в модуле app-frontend, тогда надо было app-backend/app-frontend
2. Ограничивать длину пароля для юзера - bad practice, ты снижаешь автоматически безопасность своей системы
3. Снова про безопасность, зачем отключил csrf? Потанциальная уязвимость ```.csrf(csrf -> csrf.disable())```
4. Делаешь сеттеры и вешаешь на них ```@SuppressWarnings("unused")``` может тогда они не нужны? Раз ты их не используешь?
5. Какие-то костыли с ```@ValidPath```, используй лучше встроенный ```@Pattern(regexp="")```
6. Проблемы нейминга
В модуле dto, часть классов лежит с припиской Dto (UserDto) часть нет, это выглядит плохо, нужен единный стиль
7. Лишние объекты в дто
   Как интерфейс оказался внутри дто?
```java
public class PathRequest {

    private static final String PATH_NULL = "Path must not be null";

    public interface Copy {}

    @ValidPath
    @NotNull(message = PATH_NULL)
    private String path;

```
7. Плохое использование lombok
Почему не добавить @AllArgsConstructor?
```java
@Getter
@Setter
@NoArgsConstructor
public class ResponseMessage {
      public ResponseMessage(String message) {
        this.message = message;
    }


@NoArgsConstructor
public class User {

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }
```
 Можно навесить @Getter @Setter на класс UserDetailsImpl и убрать
 ```java
    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

@Override
    public boolean isAccountNonExpired() {
        return UserDetails.super.isAccountNonExpired();
    }

    @Override
    public boolean isAccountNonLocked() {
        return UserDetails.super.isAccountNonLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return UserDetails.super.isCredentialsNonExpired();
    }

    @Override
    public boolean isEnabled() {
        return UserDetails.super.isEnabled();
    }
```

8.  Почему не вынесено в import?
```requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody```

9. Маппинг в контроллерах
   Контроллер ничего не должен мапить, это задача сервиса, +контроллер по сути бесполезный, никуда не ходит, просто достает из приципала юзернейм
   Испльзовать технический класс UserDetailsImpl в контроллере тоже плохая идея
```java
@GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(@AuthenticationPrincipal UserDetailsImpl principal) {
        UserDto userDto = new UserDto();
        userDto.setUsername(principal.getUsername());
        return ResponseEntity.ok().body(userDto);
    }
```
10. Есть неиспользуемые классы, например AuthenticationFailedException
11. Аннотацию @Table можно не указывать если имя таблицы совпадает с Entity
12. @PostConstruct в BucketService может сработать слишком рано, минио или сам контекст спринга не до конца может быть поднят -> ошибка
Лучше это вовсе убрать либо поставить dev-flag. В реальном мире, такая штука вероятнее будет создана DevOps
13. FileService.
    С точки зрения архитектуры, было бы лучше если бы у нас был возврат boolean, мы бы могли понимать, отработал метод успешно/неуспешно
    ```java
    protected void moveFile(String from, String to) {
        if (from.equals(to)) {
            return;
        }
        minioService.copyObject(from, to);
        try {
            minioService.statObject(to);
        } catch (ResourceNotFoundException exception) {
            throw new RuntimeException("Copy failed", exception);
        }
        minioService.removeObject(from);
    }
    ```
    Почему ты statObject обернул в трай кеч, а другие методы - нет?
    Зачем ``` if (exception instanceof ErrorResponseException && exception.getMessage().equals("Object does not exist")) {```?
    Почему ты не ловишь отдельно ErrorResponseException?
    Кидать throw из catch - моветон.
    ```java
    protected StatObjectResponse statObject(String path) throws ResourceNotFoundException {
        try {
            return minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketProperties.getDefaultName())
                            .object(path)
                            .build()
            );
        } catch (Exception exception) {
            if (exception instanceof ErrorResponseException && exception.getMessage().equals("Object does not exist")) {
                throw new ResourceNotFoundException("Object does not exist", exception);
            }
            throw new RuntimeException(exception);
        }
    }
    ```
15. FolderService.
  15.1 Что-то у тебя ```ensureFolderPath``` натыкано чуть ли не в каждом методе. Причем есть места где ты вызываешь его, потом дергаешь другой метод и там опять дергаешь этот метод. Надо привести в порядок, неадекватное кол-во вызовов
  15.2 Дублирование кода
    Сочитание 3х этих методов повторяется минимум 3 раза, лучше тогда создать 1 метод в котором будет вызов этих 3х
    ```
    minioService.copyObject(item.objectName(), destPath);
    minioService.statObject(destPath);
    minioService.removeObject(item.objectName());
    ```
  15.3 Что-то всяких resolve черезчур много, выглядит так, что половину убрать можно
16. MinioService, Кидать throw из catch - моветон.
17. PathResolverService не понятно для чего существует этот интерфейс
18. RegistrationService
18.1 registerUser не помечен Transcational
18.2 registerUser было бы хорошо, если бы возвраща boolean для метки создался/не создался юзер
18.3 уже писал, это плохо ```if (e.getCause() instanceof ConstraintViolationException) {```
19. ResourceService
почему у тебя FolderService выполняет какие-то утилитарные функции потипу ensureFolderPath/resolvePathToFolder/mapFolderToDto/pathHasObjectsInside?
createFolder должен находится в FolderService, а не в ResourceService
    ```java
    private enum ResourceType {
        FOLDER,
        FILE
    }
    ```
    енум не должен лежать внутри сервиса

# Архитектура
1. Отсутствует уровень абстракции для сервисов (нет интерфейсов) — реализация сервисов жёстко связана с вызывающим кодом, что снижает гибкость и тестируемость.
Рекомендуется определить интерфейсы для сервисного слоя.
2. Контроллеры реализованы напрямую без интерфейсов, что допустимо, но стоит учитывать, что интерфейсы могут быть полезны при развитии API или внедрении разных представлений (REST, gRPC и т.д.).
3. @ExceptionHandler Размещены в контроллерах, некоторые из них дублируются с GlobalExceptionHandler
4. Игнорирование ошибок, зачем и почему игнорируются эти ошибки? Почему они есть в GlobalExceptionHandler?
```java
    /**
     * return AuthenticationException back to ExceptionTranslationFilter
     *
     * @throws AuthenticationException
     */
    @ExceptionHandler(AuthenticationException.class)
    public void handle(AuthenticationException ignored) {
        throw ignored;
    }

    /**
     * return Spring-specific exceptions back to Spring
     *
     * @throws MissingServletRequestParameterException
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ResponseMessage> handle(MissingServletRequestParameterException ignored) throws MissingServletRequestParameterException {
        throw ignored;
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ResponseMessage> handle(MissingServletRequestPartException ignored) throws MissingServletRequestPartException {
        throw ignored;
    }

```
5. Нет использования MapStruct. Ты пишешь проект на spring boot, используй для мапинга объектов MapStruct, код гораздо проще и лаконичнее нежели
   ```
   protected ResourceDto mapFileToDto(String path, Long size) {
        ensureFilePath(path); //почему в маппинге какие-то проверки на пути делаешь?
        ResourceDto resourceDto = new ResourceDto();
        path = eraseUserRootFolder(path);
        resourceDto.setPath(resolvePathToFile(path));
        resourceDto.setName(resolveFileName(path));
        resourceDto.setSize(size);
        resourceDto.setType("FILE");
        return resourceDto;
    }
   ```



# Вывод
Проект не могу протестировать в полном объеме, ломается на каждом шагу

### Субъективные замечания
1. Не сделан import optimize
2. Хорошо в Entity хранить updateAt и createAt (малоли какие ситуации бывают в жизни)
3. твой if-else выглядит не очень, гораздо лучше
 ```java
if() {
   } else {
   }
```
   ```java
   private ResourceType getResourceType(String path) {
        if (path.endsWith("/")) {
            return ResourceType.FOLDER;
        }
        else {
            return ResourceType.FILE;
        }
    }
   ```
