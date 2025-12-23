// ============================================================================
// ФИНАЛЬНОЕ РЕКОМЕНДУЕМОЕ РЕШЕНИЕ
// ============================================================================

// ----------------------------------------------------------------------------
// 1. Создайте интерфейс ClientDataSource (новый файл)
// ----------------------------------------------------------------------------

package your.package.dto;

/**
 * Унифицированный интерфейс для источника данных о клиенте.
 * Позволяет использовать одну логику маппинга для успешных и ошибочных случаев.
 */
public interface ClientDataSource {
    String getFirstName();
    String getMiddleName();
    String getLastName();
    String getInn();
    AddressDto getAddress();
}

// ----------------------------------------------------------------------------
// 2. Создайте ClientInfoAdapter (новый файл)
// ----------------------------------------------------------------------------

package your.package.dto;

import lombok.Value;
import lombok.AllArgsConstructor;

/**
 * Адаптер для данных клиента из внешней системы.
 * Оборачивает ClientInformationFeignDto и предоставляет унифицированный интерфейс.
 */
@Value
@AllArgsConstructor
public class ClientInfoAdapter implements ClientDataSource {
    ClientInformationFeignDto clientInfo;
    
    @Override
    public String getFirstName() {
        return clientInfo.getFirstName();
    }
    
    @Override
    public String getMiddleName() {
        return clientInfo.getMiddleName();
    }
    
    @Override
    public String getLastName() {
        return clientInfo.getLastName();
    }
    
    @Override
    public String getInn() {
        return clientInfo.getInn();
    }
    
    @Override
    public AddressDto getAddress() {
        return clientInfo.getAddress();
    }
}

// ----------------------------------------------------------------------------
// 3. Создайте ErrorDataSource (новый файл)
// ----------------------------------------------------------------------------

package your.package.dto;

import lombok.Value;
import lombok.AllArgsConstructor;

/**
 * Адаптер для ошибочных данных.
 * Все поля возвращают сообщение об ошибке для единообразной обработки.
 */
@Value
@AllArgsConstructor
public class ErrorDataSource implements ClientDataSource {
    String errorMessage;
    
    @Override
    public String getFirstName() {
        return errorMessage;
    }
    
    @Override
    public String getMiddleName() {
        return errorMessage;
    }
    
    @Override
    public String getLastName() {
        return errorMessage;
    }
    
    @Override
    public String getInn() {
        return errorMessage;
    }
    
    @Override
    public AddressDto getAddress() {
        return null; // или можно вернуть errorMessage, если нужно
    }
}

// ----------------------------------------------------------------------------
// 4. Обновите DetailRefundAmountResponseMapper
// ----------------------------------------------------------------------------

package your.package.mapper;

import org.mapstruct.*;
import javax.annotation.Nullable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Mapper(componentModel = "spring")
public interface DetailRefundAmountResponseMapper {

    // ... существующие методы (toDetailRefundAmountResponse и т.д.) ...

    // ========================================================================
    // НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ДАННЫМИ КЛИЕНТА
    // ========================================================================

    /**
     * Универсальный метод для обновления Party из любого источника данных.
     * Работает как для реальных данных клиента, так и для ошибочных данных.
     * 
     * @param party целевой объект для обновления
     * @param dataSource источник данных (ClientInfoAdapter или ErrorDataSource)
     */
    @Mapping(target = "depositorName", expression = "java(buildDepositorName(dataSource))")
    @Mapping(target = "firstName", source = "firstName")
    @Mapping(target = "middleName", source = "middleName")
    @Mapping(target = "lastName", source = "lastName")
    @Mapping(target = "inn", source = "inn")
    @Mapping(target = "address", expression = "java(mapAddress(dataSource.getAddress()))")
    void updatePartyFromDataSource(@MappingTarget PartyDto party, ClientDataSource dataSource);

    /**
     * Convenience-метод для обновления Party из ClientInformationFeignDto.
     * Внутри использует универсальный метод updatePartyFromDataSource.
     * 
     * @param party целевой объект
     * @param clientInfo данные клиента из внешней системы
     */
    default void updatePartyFromClientInfo(PartyDto party, ClientInformationFeignDto clientInfo) {
        updatePartyFromDataSource(party, new ClientInfoAdapter(clientInfo));
    }
    
    /**
     * Устанавливает сообщение об ошибке во все поля Party.
     * Использует тот же механизм маппинга, что и для успешных данных.
     * 
     * @param party целевой объект
     * @param errorMessage сообщение об ошибке для установки во все поля
     */
    default void setPartyErrorData(PartyDto party, String errorMessage) {
        updatePartyFromDataSource(party, new ErrorDataSource(errorMessage));
    }

    /**
     * Создает полное имя депозитора из компонентов имени.
     * 
     * @param dataSource источник данных о клиенте
     * @return полное имя в формате "Фамилия Имя Отчество"
     */
    default String buildDepositorName(ClientDataSource dataSource) {
        if (dataSource == null) return null;
        return String.format("%s %s %s", 
            dataSource.getLastName() != null ? dataSource.getLastName() : "",
            dataSource.getFirstName() != null ? dataSource.getFirstName() : "",
            dataSource.getMiddleName() != null ? dataSource.getMiddleName() : ""
        ).trim();
    }
    
    // Предполагается, что этот метод уже существует
    String mapAddress(AddressDto address);

    // ... остальные существующие методы (toProductLifeSpan, getFactDays и т.д.) ...
    
    @Named("getFactDays")
    default Long getFactDays(@Nullable LocalDateTime effectiveTime) {
        if (effectiveTime == null) return null;
        LocalDate currentDate = LocalDate.now();
        LocalDate effectiveDate = effectiveTime.toLocalDate();
        long between = ChronoUnit.DAYS.between(effectiveDate, currentDate);
        return between > 0 ? between : null;
    }
}

// ----------------------------------------------------------------------------
// 5. Обновленный метод fillDepositorNames (остается БЕЗ ИЗМЕНЕНИЙ!)
// ----------------------------------------------------------------------------

/**
 * Заполняет имена депозиторов информацией из внешней системы CSPC.
 * Для каждого участника типа "депозитор" загружает данные клиента
 * и обновляет информацию о Party.
 * 
 * @param responseDto DTO с данными о продукте и участниках
 * @return обновленный responseDto с заполненными данными депозиторов
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
                
                // Используем единый API для обоих случаев (успех/ошибка)
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

// ============================================================================
// ПРЕИМУЩЕСТВА ЭТОГО РЕШЕНИЯ:
// ============================================================================
//
// 1. ✅ DRY: Одна логика маппинга для обоих случаев
// 2. ✅ Type-safe: Компилятор проверяет корректность
// 3. ✅ Open/Closed: Легко добавить новые источники данных
// 4. ✅ Single Responsibility: Каждый класс отвечает за одно
// 5. ✅ Testable: Легко тестировать каждый компонент отдельно
// 6. ✅ Clean API: Метод fillDepositorNames не меняется
// 7. ✅ MapStruct magic: Фреймворк делает всю работу
// 8. ✅ Zero duplication: Нет повторяющегося кода
//
// ============================================================================
