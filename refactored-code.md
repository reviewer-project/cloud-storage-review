# Рефакторинг метода fillDepositorNames

## Проблемы в оригинальном коде:
1. Использование `peek()` для модификации объектов (anti-pattern)
2. Множество повторяющихся вызовов сеттеров
3. Дублирование логики обработки Optional с `.orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC))`

## Решение:

### 1. Расширяем DetailRefundAmountResponseMapper

```java
@Mapper(componentModel = "spring")
public interface DetailRefundAmountResponseMapper {

    // ... существующие методы ...

    /**
     * Обновляет Party данными из ClientInformationFeignDto
     * @param party целевой объект
     * @param clientInfo источник данных
     */
    @Mapping(target = "depositorName", expression = "java(buildDepositorName(clientInfo))")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", expression = "java(mapAddress(clientInfo.getAddress()))")
    void updatePartyFromClientInfo(@MappingTarget PartyDto party, ClientInformationFeignDto clientInfo);

    /**
     * Создает полное имя депозитора
     */
    default String buildDepositorName(ClientInformationFeignDto clientInfo) {
        if (clientInfo == null) return null;
        return String.format("%s %s %s", 
            clientInfo.getLastName() != null ? clientInfo.getLastName() : "",
            clientInfo.getFirstName() != null ? clientInfo.getFirstName() : "",
            clientInfo.getMiddleName() != null ? clientInfo.getMiddleName() : ""
        ).trim();
    }

    /**
     * Устанавливает данные об ошибке в Party
     */
    default void setPartyErrorData(PartyDto party, String errorMessage) {
        party.setDepositorName(errorMessage);
        party.setFirstName(errorMessage);
        party.setMiddleName(errorMessage);
        party.setLastName(errorMessage);
        party.setInn(errorMessage);
        party.setAddress(errorMessage);
    }

    // Предполагается, что этот метод уже существует
    String mapAddress(AddressDto address);

    @Named("getFactDays")
    default Long getFactDays(@Nullable LocalDateTime effectiveTime) {
        if (effectiveTime == null) return null;
        LocalDate currentDate = LocalDate.now();
        LocalDate effectiveDate = effectiveTime.toLocalDate();
        long between = ChronoUnit.DAYS.between(effectiveDate, currentDate);
        return between > 0 ? between : null;
    }
}
```

### 2. Отрефакторенный метод fillDepositorNames

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

## Альтернативное решение (более функциональное):

Если вы хотите полностью избежать мутации объектов:

```java
private DetailRefundAmountResponseDto fillDepositorNames(@NonNull DetailRefundAmountResponseDto responseDto) {
    var retailEscrowProductInstance = responseDto.getData().getRetailEscrowProductInstance();
    var participants = retailEscrowProductInstance.getParticipants();
    
    if (participants == null) {
        return responseDto;
    }

    var updatedParticipants = participants.stream()
            .map(participant -> {
                if (!participant.getType().equalsIgnoreCase(PARTY_TYPE_DEPOSITOR)) {
                    return participant;
                }
                
                var party = participant.getParty();
                var clientInfo = clientCardService.getClientInformation(party.getId());
                
                return enrichParticipantWithClientInfo(participant, clientInfo);
            })
            .toList();
    
    retailEscrowProductInstance.setParticipants(updatedParticipants);
    return responseDto;
}

private ParticipantDto enrichParticipantWithClientInfo(
        ParticipantDto participant, 
        Optional<ClientInformationFeignDto> clientInfo) {
    
    var party = participant.getParty();
    
    clientInfo.ifPresentOrElse(
        info -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, info),
        () -> detailRefundAmountResponseMapper.setPartyErrorData(party, 
            format(EXTERNAL_SYSTEM_ERROR, CSPC))
    );
    
    return participant;
}
```

## Преимущества рефакторинга:

1. **Использование MapStruct**: Вся логика маппинга инкапсулирована в mapper
2. **Отсутствие дублирования**: Повторяющийся код `.orElseGet(() -> format(EXTERNAL_SYSTEM_ERROR, CSPC))` убран
3. **Использование `map()` вместо `peek()`**: Более правильный подход для трансформации элементов стрима
4. **Лучшая читаемость**: Код стал более декларативным
5. **Упрощенное тестирование**: Логику mapper можно тестировать отдельно
6. **Централизованная обработка ошибок**: Метод `setPartyErrorData()` инкапсулирует логику установки ошибочных данных

## Дополнительная оптимизация (опционально):

Если вы хотите избежать множественных Feign вызовов, можно добавить batch-метод:

```java
private DetailRefundAmountResponseDto fillDepositorNames(@NonNull DetailRefundAmountResponseDto responseDto) {
    var retailEscrowProductInstance = responseDto.getData().getRetailEscrowProductInstance();
    var participants = retailEscrowProductInstance.getParticipants();
    
    if (participants == null) {
        return responseDto;
    }

    // Собираем все ID депозиторов
    var depositorIds = participants.stream()
            .filter(p -> p.getType().equalsIgnoreCase(PARTY_TYPE_DEPOSITOR))
            .map(p -> p.getParty().getId())
            .toList();
    
    // Делаем один batch запрос вместо множества отдельных (если API поддерживает)
    Map<String, ClientInformationFeignDto> clientInfoMap = 
        clientCardService.getClientInformationBatch(depositorIds);

    var updatedParticipants = participants.stream()
            .map(participant -> {
                if (!participant.getType().equalsIgnoreCase(PARTY_TYPE_DEPOSITOR)) {
                    return participant;
                }
                
                var party = participant.getParty();
                var clientInfo = Optional.ofNullable(clientInfoMap.get(party.getId()));
                
                clientInfo.ifPresentOrElse(
                    info -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, info),
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
