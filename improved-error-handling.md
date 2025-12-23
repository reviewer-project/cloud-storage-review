# Улучшенная обработка ошибок в mapper

## Решение 1: MapStruct AfterMapping (Самое элегантное) ⭐

Используем `@AfterMapping` для установки всех полей одним значением:

```java
@Mapper(componentModel = "spring")
public interface DetailRefundAmountResponseMapper {

    @Mapping(target = "depositorName", expression = "java(buildDepositorName(clientInfo))")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", expression = "java(mapAddress(clientInfo.getAddress()))")
    void updatePartyFromClientInfo(@MappingTarget PartyDto party, ClientInformationFeignDto clientInfo);

    /**
     * Обновляет все поля Party сообщением об ошибке
     * Использует @AfterMapping для установки всех строковых полей
     */
    @AfterMapping
    default void fillErrorFields(@MappingTarget PartyDto party, @Context String errorMessage) {
        if (errorMessage != null) {
            party.setDepositorName(errorMessage);
            party.setFirstName(errorMessage);
            party.setMiddleName(errorMessage);
            party.setLastName(errorMessage);
            party.setInn(errorMessage);
            party.setAddress(errorMessage);
        }
    }
    
    /**
     * Создает новый PartyDto с заполненными ошибочными данными
     */
    default PartyDto createErrorParty(String partyId, String errorMessage) {
        var party = new PartyDto();
        party.setId(partyId);
        fillErrorFields(party, errorMessage);
        return party;
    }

    default String buildDepositorName(ClientInformationFeignDto clientInfo) {
        if (clientInfo == null) return null;
        return String.format("%s %s %s", 
            clientInfo.getLastName() != null ? clientInfo.getLastName() : "",
            clientInfo.getFirstName() != null ? clientInfo.getFirstName() : "",
            clientInfo.getMiddleName() != null ? clientInfo.getMiddleName() : ""
        ).trim();
    }
}
```

**Использование в методе:**
```java
clientCardService.getClientInformation(party.getId())
    .ifPresentOrElse(
        clientInfo -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, clientInfo),
        () -> detailRefundAmountResponseMapper.fillErrorFields(party, format(EXTERNAL_SYSTEM_ERROR, CSPC))
    );
```

---

## Решение 2: Error DTO + MapStruct (Функциональный подход) ⭐⭐⭐

Создаем специальный DTO для ошибки и используем MapStruct для маппинга:

```java
// Простой DTO для представления ошибочных данных клиента
@Value
@Builder
public class ClientErrorInfo {
    String errorMessage;
    
    public static ClientErrorInfo of(String errorMessage) {
        return ClientErrorInfo.builder()
                .errorMessage(errorMessage)
                .build();
    }
    
    // Все геттеры возвращают одно и то же сообщение
    public String getFirstName() { return errorMessage; }
    public String getMiddleName() { return errorMessage; }
    public String getLastName() { return errorMessage; }
    public String getInn() { return errorMessage; }
    public String getAddress() { return errorMessage; }
}
```

```java
@Mapper(componentModel = "spring")
public interface DetailRefundAmountResponseMapper {

    @Mapping(target = "depositorName", expression = "java(buildDepositorName(clientInfo))")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", expression = "java(mapAddress(clientInfo.getAddress()))")
    void updatePartyFromClientInfo(@MappingTarget PartyDto party, ClientInformationFeignDto clientInfo);

    /**
     * Обновляет Party данными об ошибке
     * Переиспользует существующую логику updatePartyFromClientInfo
     */
    @Mapping(target = "depositorName", source = "errorMessage")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", source = "address")
    void updatePartyFromErrorInfo(@MappingTarget PartyDto party, ClientErrorInfo errorInfo);

    default String buildDepositorName(ClientInformationFeignDto clientInfo) {
        if (clientInfo == null) return null;
        return String.format("%s %s %s", 
            clientInfo.getLastName() != null ? clientInfo.getLastName() : "",
            clientInfo.getFirstName() != null ? clientInfo.getFirstName() : "",
            clientInfo.getMiddleName() != null ? clientInfo.getMiddleName() : ""
        ).trim();
    }
}
```

**Использование в методе:**
```java
clientCardService.getClientInformation(party.getId())
    .ifPresentOrElse(
        clientInfo -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, clientInfo),
        () -> detailRefundAmountResponseMapper.updatePartyFromErrorInfo(party, 
            ClientErrorInfo.of(format(EXTERNAL_SYSTEM_ERROR, CSPC)))
    );
```

---

## Решение 3: Унифицированный интерфейс (Самое чистое) ⭐⭐⭐⭐⭐

Создаем общий интерфейс для обоих типов данных:

```java
/**
 * Интерфейс для источника данных о клиенте
 */
public interface ClientDataSource {
    String getFirstName();
    String getMiddleName();
    String getLastName();
    String getInn();
    AddressDto getAddress();
}

/**
 * Адаптер для реальных данных клиента
 */
@Value
@AllArgsConstructor
public class ClientInfoAdapter implements ClientDataSource {
    ClientInformationFeignDto clientInfo;
    
    @Override
    public String getFirstName() { return clientInfo.getFirstName(); }
    
    @Override
    public String getMiddleName() { return clientInfo.getMiddleName(); }
    
    @Override
    public String getLastName() { return clientInfo.getLastName(); }
    
    @Override
    public String getInn() { return clientInfo.getInn(); }
    
    @Override
    public AddressDto getAddress() { return clientInfo.getAddress(); }
}

/**
 * Адаптер для ошибочных данных - все поля возвращают сообщение об ошибке
 */
@Value
@AllArgsConstructor
public class ErrorDataSource implements ClientDataSource {
    String errorMessage;
    
    @Override
    public String getFirstName() { return errorMessage; }
    
    @Override
    public String getMiddleName() { return errorMessage; }
    
    @Override
    public String getLastName() { return errorMessage; }
    
    @Override
    public String getInn() { return errorMessage; }
    
    @Override
    public AddressDto getAddress() { return null; } // или можно вернуть errorMessage
}
```

```java
@Mapper(componentModel = "spring")
public interface DetailRefundAmountResponseMapper {

    /**
     * Универсальный метод для обновления Party из любого источника данных
     */
    @Mapping(target = "depositorName", expression = "java(buildDepositorName(dataSource))")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", expression = "java(mapAddress(dataSource.getAddress()))")
    void updatePartyFromDataSource(@MappingTarget PartyDto party, ClientDataSource dataSource);

    /**
     * Convenience метод для прямого обновления из ClientInformationFeignDto
     */
    default void updatePartyFromClientInfo(PartyDto party, ClientInformationFeignDto clientInfo) {
        updatePartyFromDataSource(party, new ClientInfoAdapter(clientInfo));
    }
    
    /**
     * Convenience метод для установки ошибочных данных
     */
    default void setPartyErrorData(PartyDto party, String errorMessage) {
        updatePartyFromDataSource(party, new ErrorDataSource(errorMessage));
    }

    default String buildDepositorName(ClientDataSource dataSource) {
        if (dataSource == null) return null;
        return String.format("%s %s %s", 
            dataSource.getLastName() != null ? dataSource.getLastName() : "",
            dataSource.getFirstName() != null ? dataSource.getFirstName() : "",
            dataSource.getMiddleName() != null ? dataSource.getMiddleName() : ""
        ).trim();
    }
    
    String mapAddress(AddressDto address);
}
```

**Использование в методе (не меняется!):**
```java
clientCardService.getClientInformation(party.getId())
    .ifPresentOrElse(
        clientInfo -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, clientInfo),
        () -> detailRefundAmountResponseMapper.setPartyErrorData(party, 
            format(EXTERNAL_SYSTEM_ERROR, CSPC))
    );
```

---

## Решение 4: Java 21 Record Patterns (Если доступна Java 21) 🚀

```java
/**
 * Sealed интерфейс для данных клиента
 */
public sealed interface ClientData permits ClientData.Success, ClientData.Error {
    
    record Success(ClientInformationFeignDto data) implements ClientData {}
    
    record Error(String message) implements ClientData {
        public String getFirstName() { return message; }
        public String getMiddleName() { return message; }
        public String getLastName() { return message; }
        public String getInn() { return message; }
        public String getAddress() { return message; }
    }
}
```

```java
@Mapper(componentModel = "spring")
public interface DetailRefundAmountResponseMapper {

    default void updatePartyFromClientData(PartyDto party, ClientData clientData) {
        switch (clientData) {
            case ClientData.Success(var info) -> updatePartyFromClientInfo(party, info);
            case ClientData.Error(var message) -> setAllFields(party, message);
        }
    }

    @Mapping(target = "depositorName", expression = "java(buildDepositorName(clientInfo))")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", expression = "java(mapAddress(clientInfo.getAddress()))")
    void updatePartyFromClientInfo(@MappingTarget PartyDto party, ClientInformationFeignDto clientInfo);
    
    default void setAllFields(PartyDto party, String value) {
        party.setDepositorName(value);
        party.setFirstName(value);
        party.setMiddleName(value);
        party.setLastName(value);
        party.setInn(value);
        party.setAddress(value);
    }
}
```

---

## Сравнение решений

| Решение | Красота | Простота | Расширяемость | Рекомендация |
|---------|---------|----------|---------------|--------------|
| 1. AfterMapping | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | Для быстрого решения |
| 2. Error DTO | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | Хороший баланс |
| 3. Унифицированный интерфейс | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | **Лучшее для больших проектов** |
| 4. Sealed interfaces | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Если есть Java 21+ |

## Моя рекомендация: Решение 3 (Унифицированный интерфейс) ⭐

**Почему:**
- ✅ Одна логика маппинга для обоих случаев
- ✅ Полностью type-safe
- ✅ Легко добавить новые источники данных
- ✅ Следует принципу Open/Closed
- ✅ API метода не меняется
- ✅ MapStruct делает всю работу
- ✅ Нет дублирования кода

**Код стал:**
```java
// До: явное указание всех сеттеров
default void setPartyErrorData(PartyDto party, String errorMessage) {
    party.setDepositorName(errorMessage);
    party.setFirstName(errorMessage);
    party.setMiddleName(errorMessage);
    party.setLastName(errorMessage);
    party.setInn(errorMessage);
    party.setAddress(errorMessage);
}

// После: один вызов через унифицированный интерфейс
default void setPartyErrorData(PartyDto party, String errorMessage) {
    updatePartyFromDataSource(party, new ErrorDataSource(errorMessage));
}
```

Это на 100% красивее! 🎨
