// Отрефакторенный метод fillDepositorNames

/**
 * Заполняет имена депозиторов из внешней системы CSPC
 * Рефакторинг: использует MapStruct mapper вместо множественных сеттеров
 */
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
                
                // Используем ifPresentOrElse для чистой обработки Optional
                clientCardService.getClientInformation(party.getId())
                        .ifPresentOrElse(
                            // При успехе - используем mapper для обновления всех полей
                            clientInfo -> detailRefundAmountResponseMapper.updatePartyFromClientInfo(party, clientInfo),
                            // При ошибке - устанавливаем сообщение об ошибке
                            () -> detailRefundAmountResponseMapper.setPartyErrorData(party, 
                                format(EXTERNAL_SYSTEM_ERROR, CSPC))
                        );
                
                return participant;
            })
            .toList();
    
    retailEscrowProductInstance.setParticipants(updatedParticipants);
    return responseDto;
}
