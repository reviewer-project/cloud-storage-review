# Альтернативные подходы к рефакторингу

## Решение 1: Минимальные изменения (самое простое)

Если вы не хотите менять mapper, можно просто вынести логику в отдельный метод:

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
                enrichPartyWithClientInfo(participant.getParty());
                return participant;
            })
            .toList();
    
    retailEscrowProductInstance.setParticipants(updatedParticipants);
    return responseDto;
}

private void enrichPartyWithClientInfo(PartyDto party) {
    var clientInfo = clientCardService.getClientInformation(party.getId());
    
    if (clientInfo.isPresent()) {
        var info = clientInfo.get();
        party.setDepositorName(buildDepositorName(info));
        party.setFirstName(info.getFirstName());
        party.setMiddleName(info.getMiddleName());
        party.setLastName(info.getLastName());
        party.setInn(info.getInn());
        party.setAddress(mapAddress(info.getAddress()));
    } else {
        String errorMsg = format(EXTERNAL_SYSTEM_ERROR, CSPC);
        party.setDepositorName(errorMsg);
        party.setFirstName(errorMsg);
        party.setMiddleName(errorMsg);
        party.setLastName(errorMsg);
        party.setInn(errorMsg);
        party.setAddress(errorMsg);
    }
}

private String buildDepositorName(ClientInformationFeignDto info) {
    return String.format("%s %s %s", 
        info.getLastName() != null ? info.getLastName() : "",
        info.getFirstName() != null ? info.getFirstName() : "",
        info.getMiddleName() != null ? info.getMiddleName() : ""
    ).trim();
}
```

**Плюсы**: Минимальные изменения, не требует модификации mapper  
**Минусы**: Все еще есть множественные сеттеры

---

## Решение 2: Использование Builder Pattern

Если у вас есть возможность добавить builder для PartyDto:

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
                var updatedParty = createEnrichedParty(participant.getParty());
                participant.setParty(updatedParty);
                return participant;
            })
            .toList();
    
    retailEscrowProductInstance.setParticipants(updatedParticipants);
    return responseDto;
}

private PartyDto createEnrichedParty(PartyDto originalParty) {
    var clientInfo = clientCardService.getClientInformation(originalParty.getId());
    
    return clientInfo
        .map(info -> PartyDto.builder()
            .id(originalParty.getId())
            .type(originalParty.getType())
            .depositorName(buildDepositorName(info))
            .firstName(info.getFirstName())
            .middleName(info.getMiddleName())
            .lastName(info.getLastName())
            .inn(info.getInn())
            .address(mapAddress(info.getAddress()))
            .build())
        .orElseGet(() -> createErrorParty(originalParty));
}

private PartyDto createErrorParty(PartyDto originalParty) {
    String errorMsg = format(EXTERNAL_SYSTEM_ERROR, CSPC);
    return PartyDto.builder()
        .id(originalParty.getId())
        .type(originalParty.getType())
        .depositorName(errorMsg)
        .firstName(errorMsg)
        .middleName(errorMsg)
        .lastName(errorMsg)
        .inn(errorMsg)
        .address(errorMsg)
        .build();
}
```

**Плюсы**: Иммутабельный подход, избавление от сеттеров  
**Минусы**: Требует наличия builder, создает новые объекты

---

## Решение 3: Functional Approach с Record (Java 14+)

Если можете использовать records:

```java
private DetailRefundAmountResponseDto fillDepositorNames(@NonNull DetailRefundAmountResponseDto responseDto) {
    var retailEscrowProductInstance = responseDto.getData().getRetailEscrowProductInstance();
    var participants = retailEscrowProductInstance.getParticipants();
    
    if (participants == null) {
        return responseDto;
    }

    var updatedParticipants = participants.stream()
            .map(participant -> participant.getType().equalsIgnoreCase(PARTY_TYPE_DEPOSITOR)
                ? enrichParticipantWithClientData(participant)
                : participant)
            .toList();
    
    retailEscrowProductInstance.setParticipants(updatedParticipants);
    return responseDto;
}

private ParticipantDto enrichParticipantWithClientData(ParticipantDto participant) {
    var party = participant.getParty();
    var clientInfo = clientCardService.getClientInformation(party.getId());
    
    var enrichmentData = clientInfo
        .map(info -> new PartyEnrichmentData(
            buildDepositorName(info),
            info.getFirstName(),
            info.getMiddleName(),
            info.getLastName(),
            info.getInn(),
            mapAddress(info.getAddress())
        ))
        .orElseGet(() -> {
            String errorMsg = format(EXTERNAL_SYSTEM_ERROR, CSPC);
            return new PartyEnrichmentData(errorMsg, errorMsg, errorMsg, errorMsg, errorMsg, errorMsg);
        });
    
    applyEnrichmentData(party, enrichmentData);
    return participant;
}

private void applyEnrichmentData(PartyDto party, PartyEnrichmentData data) {
    party.setDepositorName(data.depositorName());
    party.setFirstName(data.firstName());
    party.setMiddleName(data.middleName());
    party.setLastName(data.lastName());
    party.setInn(data.inn());
    party.setAddress(data.address());
}

// Helper record для передачи данных
private record PartyEnrichmentData(
    String depositorName,
    String firstName,
    String middleName,
    String lastName,
    String inn,
    String address
) {}
```

**Плюсы**: Чистое разделение логики получения и применения данных  
**Минусы**: Дополнительный record, все еще есть сеттеры

---

## Решение 4: С оптимизацией Feign вызовов (Batch)

Для улучшения производительности при множественных вызовах:

```java
private DetailRefundAmountResponseDto fillDepositorNames(@NonNull DetailRefundAmountResponseDto responseDto) {
    var retailEscrowProductInstance = responseDto.getData().getRetailEscrowProductInstance();
    var participants = retailEscrowProductInstance.getParticipants();
    
    if (participants == null) {
        return responseDto;
    }

    // Собираем всех депозиторов
    var depositors = participants.stream()
            .filter(p -> p.getType().equalsIgnoreCase(PARTY_TYPE_DEPOSITOR))
            .toList();
    
    if (depositors.isEmpty()) {
        return responseDto;
    }
    
    // Собираем ID для batch запроса
    var partyIds = depositors.stream()
            .map(p -> p.getParty().getId())
            .toList();
    
    // Один batch запрос вместо N запросов
    Map<String, ClientInformationFeignDto> clientInfoMap = 
        fetchClientInfoBatch(partyIds);
    
    // Обогащаем данные
    depositors.forEach(participant -> {
        var party = participant.getParty();
        var clientInfo = Optional.ofNullable(clientInfoMap.get(party.getId()));
        
        clientInfo.ifPresentOrElse(
            info -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, info),
            () -> detailRefundAmountResponseMapper.setPartyErrorData(party, 
                format(EXTERNAL_SYSTEM_ERROR, CSPC))
        );
    });
    
    return responseDto;
}

private Map<String, ClientInformationFeignDto> fetchClientInfoBatch(List<String> partyIds) {
    // Если API поддерживает batch запрос
    try {
        return clientCardService.getClientInformationBatch(partyIds);
    } catch (Exception e) {
        log.warn("Batch request failed, falling back to individual requests", e);
        // Fallback к индивидуальным запросам
        return partyIds.stream()
            .collect(Collectors.toMap(
                id -> id,
                id -> clientCardService.getClientInformation(id).orElse(null)
            ));
    }
}
```

**Плюсы**: Оптимизация N+1 проблемы, лучшая производительность  
**Минусы**: Требует batch API на стороне сервиса

---

## Сравнительная таблица решений

| Решение | Сложность | Чистота кода | Производительность | Тестируемость |
|---------|-----------|--------------|-------------------|---------------|
| 1. Минимальные изменения | ⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| 2. Builder Pattern | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ |
| 3. Record + Functional | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 4. MapStruct (рекомендуемое) | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 5. Batch оптимизация | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |

## Рекомендация

**Оптимальное решение**: Решение 4 с MapStruct (из основного файла)

**Причины**:
1. Уже используется MapStruct в проекте
2. Декларативный подход - легко читать и поддерживать
3. MapStruct генерирует оптимизированный код
4. Легко тестировать mapper отдельно
5. Избавляет от boilerplate кода

**Для высоконагруженных систем**: Добавить batch оптимизацию (решение 5)
