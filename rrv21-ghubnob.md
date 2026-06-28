## ЗАМЕЧАНИЯ

### пакет /controller

1. **`@GetMapping("/move")` для операции перемещения нарушает HTTP-семантику.**

В `ResourceApi` операция перемещения / переименования ресурса объявлена как `GET /resource/move`. GET — идемпотентный метод для чтения; он не должен изменять состояние. Фронтенд и HTTP-кэши вправе кэшировать GET-запросы, что сделает move непредсказуемым. Да, в ТЗ написан GET, но это не верно.

**Рекомендация:**

Используй `@PatchMapping` или `@PutMapping` для операций изменения ресурса. Да, я знаю что в ТЗ написано использовать GET, но это не верно

4. **Пакет `/api` с интерфейсами лежит отдельно от контроллеров.**

Сейчас `api`-интерфейсы находятся в отдельном пакете верхнего уровня, а их реализации — в `controller`

**Рекомендация:**

Перенеси `api`-интерфейсы внутрь `controller` как подпакет (`controller.api`), это не критично, но так проще читать код

### пакет /dto

1. **`AuthenticatedUser` хранит пароль в поле record.**

`AuthenticatedUser(Integer id, String username, String password)` — стандартный `toString()` record включит все поля, в том числе `password`. Если кто-то залогирует объект целиком (например, `log.debug("Principal: {}", user)`), пароль попадёт в логи

**Рекомендация:**

Не храни пароль в principal после аутентификации. Тем более что ты его нигде и не используешь

### пакет /minio

1. **`S3Config` и `S3Properties` лежат в пакете `minio.s3`, хотя они ни к MinIO SDK, ни к MinIO-специфике не относятся.**

Path-слой корректно переименован в `infrastructure.paths.s3keys`, но конфиг всё ещё в `minio.s3`. Это рассинхронизация: разработчик, открывающий `infrastructure`, не найдёт там конфигурацию клиента. `S3Config` и `S3Properties` — инфраструктурная конфигурация хранилища, им место в `infrastructure.config` рядом с `StorageConfig` и `PathConfig`.

**Рекомендация:**

Перемести `S3Config` и `S3Properties` в `infrastructure.config`, удали пакет `minio.s3`.

### пакет /service

1. **`FileService` интерфейс содержит `onUserRegistered(UserRegisteredEvent event)` — деталь реализации просочилась в контракт.**

Application-интерфейс `FileService` описывает use cases для файлов, но `onUserRegistered` — это инфраструктурный event listener, специфичный для `S3FileServiceImpl`. Любая другая реализация `FileService` будет вынуждена реализовать этот метод. Слушатель событий не должен быть частью публичного API сервиса.

**Рекомендация:**

Убери `onUserRegistered()` из `FileService` и перенеси `@TransactionalEventListener` в отдельный сервис

2. **`ensureDirectoryMetadata()` делает N отдельных SELECT-запросов на каждый сегмент пути.**

При загрузке файла в `upload_folder/sub/file.txt` метод вызывается для `upload_folder/` и `upload_folder/sub/`, каждый раз проходя по сегментам и выполняя `findByUserIdAndPathAndName()` на каждый. 
Для глубокой вложенности это несколько round-trip к БД в цикле внутри транзакции.

**Рекомендация:**

Загружай сразу все существующие директории за один запрос и вычисляй разность:

```java
private void ensureDirectoryMetadata(Integer userId, String directoryPath) {
    List<String> segments = buildSegments(directoryPath); // ["dir", "dir/sub", ...]
    Set<String> existing = new HashSet<>(resourceRepository.findAllPathsByUserIdAndPathIn(userId, segments));
    List<ResourceEntity> toCreate = segments.stream()
            .filter(s -> !existing.contains(s))
            .map(s -> ResourceEntity.createOf(userId, parentOf(s), nameOf(s), 0L, FileType.DIRECTORY))
            .toList();
    resourceRepository.saveAll(toCreate);
}
```

3. **`UserService.register()` — race condition обрабатывается с потерей контекста.**

Проверка `findByUsername()` до `save()` не атомарна: при двух одновременных запросах оба пройдут check, один из них получит `DataIntegrityViolationException` от UNIQUE constraint. 
В `ExceptionsHandler` этот exception отображается как `409 "Database writing failed!"` — клиент не может отличить конфликт по username от любой другой ошибки целостности БД.

**Рекомендация:**

Убери check-then-save и обрабатывай `DataIntegrityViolationException` как именно конфликт username:

---

## ИТОГ

Рефакторинг масштабный и в целом успешный. Большинство системных замечаний закрыто: 
- Spring Security теперь работает корректно через `AuthenticationManager` и `UserDetailsService`; 
- `MainController` разбит по контекстам с API-интерфейсами; 
- `ObjectStorage` абстрагирует S3 SDK от бизнес-логики; 
- SQL metadata layer заменил O(n) bucket scan, но есть нюансы с кучей SQL запросами; 
- domain events развязали регистрацию и инициализацию хранилища; 
- Lombok приведён к единому стилю.

Остаются точечные проблемы, часть из которых возникла в процессе рефакторинга: 
- `minio.s3` пакет не переименован; 
- `onUserRegistered` протёк в `FileService`-интерфейс; 
- `AuthenticatedUser` хранит пароль;  
- `ensureDirectoryMetadata` добавляет N+1 запросов на глубину пути. 
Всё это локальные правки, не требующие структурных изменений.
