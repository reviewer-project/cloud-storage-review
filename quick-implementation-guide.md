# Быстрый гайд по применению рефакторинга

## Шаг 1: Добавьте методы в DetailRefundAmountResponseMapper

```java
@Mapper(componentModel = "spring")
public interface DetailRefundAmountResponseMapper {

    // ... существующие методы ...

    // 👇 ДОБАВЬТЕ ЭТИ МЕТОДЫ

    @Mapping(target = "depositorName", expression = "java(buildDepositorName(clientInfo))")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", expression = "java(mapAddress(clientInfo.getAddress()))")
    void updatePartyFromClientInfo(@MappingTarget PartyDto party, ClientInformationFeignDto clientInfo);

    default String buildDepositorName(ClientInformationFeignDto clientInfo) {
        if (clientInfo == null) return null;
        return String.format("%s %s %s", 
            clientInfo.getLastName() != null ? clientInfo.getLastName() : "",
            clientInfo.getFirstName() != null ? clientInfo.getFirstName() : "",
            clientInfo.getMiddleName() != null ? clientInfo.getMiddleName() : ""
        ).trim();
    }

    default void setPartyErrorData(PartyDto party, String errorMessage) {
        party.setDepositorName(errorMessage);
        party.setFirstName(errorMessage);
        party.setMiddleName(errorMessage);
        party.setLastName(errorMessage);
        party.setInn(errorMessage);
        party.setAddress(errorMessage);
    }
}
```

## Шаг 2: Замените старый метод fillDepositorNames

### ❌ БЫЛО (удалите это):

```java
private DetailRefundAmountResponseDto fillDepositorNames(@NonNull DetailRefundAmountResponseDto responseDto) {
    var retailEscrowProductInstance = responseDto.getData().getRetailEscrowProductInstance();
    var participants = retailEscrowProductInstance.getParticipants();
    if (participants != null) {
        var newParticipants = participants.stream().filter(p -> p.getType()
                .equalsIgnoreCase(PARTY_TYPE_DEPOSITOR)).peek(p -> {
            var party = p.getParty();

            Optional<ClientInformationFeignDto> clientInformation = clientCardService
                    .getClientInformation(party.getId());
            String getDepositorName = clientInformation.map(clientInformationFeignDto ->
                            format("%s %s %s", clientInformationFeignDto.getLastName(),
                                    clientInformationFeignDto.getFirstName(),
                                    clientInformationFeignDto.getMiddleName()).trim())
                    .orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC));
            party.setDepositorName(getDepositorName);
            party.setFirstName(clientInformation.map(ClientInformationFeignDto::getFirstName).orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC)));
            party.setMiddleName(clientInformation.map(ClientInformationFeignDto::getMiddleName).orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC)));
            party.setLastName(clientInformation.map(ClientInformationFeignDto::getLastName).orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC)));
            party.setInn(clientInformation.map(ClientInformationFeignDto::getInn).orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC)));
            party.setAddress(clientInformation.map(clientInformationFeignDto -> mapAddress(clientInformationFeignDto.getAddress())).orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC)));
        }).toList();
        retailEscrowProductInstance.setParticipants(newParticipants);
    }
    return responseDto;
}
```

### ✅ СТАЛО (используйте это):

```java
private DetailRefundAmountResponseDto fillDepositorNames(@NonNull DetailRefundAmountResponseDto responseDto) {
    var retailEscrowProductInstance = responseDto.getData().getRetailEscrowProductInstance();
    var participants = retailEscrowProductInstance.getParticipants();
    
    if (participants == null) {
        return responseDto;
    }

    var updatedParticipants = participants.stream()
            .filter(p -> p.getType().equalsIgnoreCase(PARTY_TYPE_DEPOSITOR))
            .map(participant -> {
                var party = participant.getParty();
                
                clientCardService.getClientInformation(party.getId())
                        .ifPresentOrElse(
                            clientInfo -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, clientInfo),
                            () -> detailRefundAmountResponseMapper.setPartyErrorData(party, 
                                format(EXTERNAL_SYSTEM_ERROR, CSPC))
                        );
                
                return participant;
            })
            .toList();
    
    retailEscrowProductInstance.setParticipants(updatedParticipants);
    return responseDto;
}
```

## Шаг 3: Пересоберите проект

```bash
mvn clean compile
# или
./gradlew clean build
```

MapStruct автоматически сгенерирует имплементацию mapper'а.

## Что изменилось? 

### Было проблем:
- ❌ 18 строк в одном методе с логикой
- ❌ Использование `peek()` для мутации (anti-pattern)
- ❌ 6 повторений `.orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC))`
- ❌ Плохая читаемость
- ❌ Сложно тестировать

### Стало лучше:
- ✅ 12 строк чистого кода
- ✅ Использование `map()` вместо `peek()`
- ✅ Один вызов mapper'а вместо 6 сеттеров
- ✅ Отличная читаемость
- ✅ Легко тестировать mapper отдельно

## Бонус: Unit тесты

Теперь можно легко протестировать mapper:

```java
@SpringBootTest
class DetailRefundAmountResponseMapperTest {
    
    @Autowired
    private DetailRefundAmountResponseMapper mapper;
    
    @Test
    void shouldUpdatePartyFromClientInfo() {
        // Given
        var party = new PartyDto();
        party.setId("123");
        
        var clientInfo = new ClientInformationFeignDto();
        clientInfo.setFirstName("Иван");
        clientInfo.setMiddleName("Иванович");
        clientInfo.setLastName("Иванов");
        clientInfo.setInn("1234567890");
        
        // When
        mapper.updatePartyFromClientInfo(party, clientInfo);
        
        // Then
        assertEquals("Иванов Иван Иванович", party.getDepositorName());
        assertEquals("Иван", party.getFirstName());
        assertEquals("Иванович", party.getMiddleName());
        assertEquals("Иванов", party.getLastName());
        assertEquals("1234567890", party.getInn());
    }
    
    @Test
    void shouldBuildDepositorNameCorrectly() {
        // Given
        var clientInfo = new ClientInformationFeignDto();
        clientInfo.setFirstName("Петр");
        clientInfo.setMiddleName("Петрович");
        clientInfo.setLastName("Петров");
        
        // When
        String name = mapper.buildDepositorName(clientInfo);
        
        // Then
        assertEquals("Петров Петр Петрович", name);
    }
    
    @Test
    void shouldSetErrorDataWhenClientInfoNotFound() {
        // Given
        var party = new PartyDto();
        String errorMsg = "Ошибка получения данных из CSPC";
        
        // When
        mapper.setPartyErrorData(party, errorMsg);
        
        // Then
        assertEquals(errorMsg, party.getDepositorName());
        assertEquals(errorMsg, party.getFirstName());
        assertEquals(errorMsg, party.getMiddleName());
        assertEquals(errorMsg, party.getLastName());
        assertEquals(errorMsg, party.getInn());
        assertEquals(errorMsg, party.getAddress());
    }
}
```

## Готово! 🎉

Ваш код теперь:
- Чище и понятнее
- Легче тестировать
- Легче поддерживать
- Следует best practices Java и MapStruct
