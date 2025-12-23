// Дополнения к DetailRefundAmountResponseMapper

/**
 * Обновляет Party данными из ClientInformationFeignDto
 * Использует MapStruct для автоматического маппинга
 */
@Mapping(target = "depositorName", expression = "java(buildDepositorName(clientInfo))")
@Mapping(target = "firstName", source = "firstName")
@Mapping(target = "middleName", source = "middleName")
@Mapping(target = "lastName", source = "lastName")
@Mapping(target = "inn", source = "inn")
@Mapping(target = "address", expression = "java(mapAddress(clientInfo.getAddress()))")
void updatePartyFromClientInfo(@MappingTarget PartyDto party, ClientInformationFeignDto clientInfo);

/**
 * Создает полное имя депозитора из компонентов
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
 * Устанавливает данные об ошибке во все поля Party
 * Используется когда не удалось получить информацию из внешней системы
 */
default void setPartyErrorData(PartyDto party, String errorMessage) {
    party.setDepositorName(errorMessage);
    party.setFirstName(errorMessage);
    party.setMiddleName(errorMessage);
    party.setLastName(errorMessage);
    party.setInn(errorMessage);
    party.setAddress(errorMessage);
}
