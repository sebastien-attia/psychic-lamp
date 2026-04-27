package ch.owt.boatapp.adapter.in.web.mapper;

import ch.owt.boatapp.adapter.in.web.dto.generated.BoatResponse;
import ch.owt.boatapp.adapter.in.web.dto.generated.PageBoatResponse;
import ch.owt.boatapp.adapter.in.web.dto.generated.Severity;
import ch.owt.boatapp.adapter.in.web.dto.generated.ValidationMessageResponse;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;

/**
 * Hand-written domain → wire DTO mapper.
 *
 * <p>Lives in {@code adapter.in.web.mapper} so the dependency on the
 * generated DTO package stays inside the adapter. The domain layer never
 * imports these types.
 */
public final class BoatWebMapper {

    /**
     * Map a domain {@link Boat} to its wire response DTO.
     *
     * @param boat the domain boat (never {@code null})
     * @return the response DTO populated from {@code boat}
     */
    public static BoatResponse toResponse(Boat boat) {
        BoatResponse response = new BoatResponse(boat.id(), boat.name(), boat.createdAt(), boat.version());
        response.setDescription(boat.description());
        return response;
    }

    /**
     * Map a domain {@link Boat} to its wire response DTO, attaching a list
     * of advisory ({@code WARNING} / {@code INFO}) validation messages on
     * the response body. Used by the create/update success path so the
     * caller can render soft hints alongside the persisted boat.
     *
     * <p>Severity filtering is the bridge layer's responsibility: by the
     * time control reaches this mapper the {@code ERROR}-bearing path has
     * already short-circuited via {@code ValidationFailureException}, so
     * every entry in {@code messages} is expected to be advisory. The
     * mapper does not re-filter — it just formats.
     *
     * @param boat          the domain boat (never {@code null})
     * @param messages      the advisory findings to attach (never
     *                      {@code null}; may be empty)
     * @param messageSource Spring's i18n message source
     * @param locale        the resolved request locale
     * @return the response DTO with both the boat fields and the formatted
     *         advisory messages set
     */
    public static BoatResponse toResponse(Boat boat,
                                          List<ValidationMessage> messages,
                                          MessageSource messageSource,
                                          Locale locale) {
        BoatResponse response = toResponse(boat);
        if (!messages.isEmpty()) {
            response.setMessages(toWire(messages, messageSource, locale));
        }
        return response;
    }

    /**
     * Map a domain {@link PageResult} of {@link Boat} into the paginated wire
     * DTO. Computes {@code first}/{@code last}/{@code empty} flags from the
     * page coordinates (the domain page envelope keeps only the essential
     * fields).
     *
     * @param page the domain page envelope (never {@code null})
     * @return the wire-shaped page response with derived first/last/empty flags
     */
    public static PageBoatResponse toPage(PageResult<Boat> page) {
        List<BoatResponse> content = page.content().stream()
                .map(BoatWebMapper::toResponse)
                .toList();
        boolean first = page.number() == 0;
        boolean last = page.totalPages() == 0 || page.number() >= page.totalPages() - 1;
        boolean empty = content.isEmpty();
        return new PageBoatResponse(content, page.totalElements(), page.totalPages(),
                page.size(), page.number(), first, last, empty);
    }

    /**
     * Map a single domain {@link ValidationMessage} into its wire DTO,
     * resolving the human-readable {@code message} via the supplied
     * {@link MessageSource} against the request locale.
     *
     * <p>The {@code code} on the wire is always
     * {@link ValidationMessage#type()}{@code .applicationCode()} — never the
     * Jakarta constraint name and never the enum {@code name()} symbol.
     *
     * @param vm            the domain validation finding
     * @param messageSource Spring's i18n message source
     * @param locale        the resolved request locale
     * @return the wire DTO carrying severity, code, field and a localized
     *         human-readable message
     */
    public static ValidationMessageResponse toWire(ValidationMessage vm,
                                                   MessageSource messageSource,
                                                   Locale locale) {
        String code = vm.type().applicationCode();
        String fieldArg = vm.field() != null ? vm.field() : "";
        String message = messageSource.getMessage(code, new Object[]{fieldArg}, code, locale);
        ValidationMessageResponse out = new ValidationMessageResponse(toWireSeverity(vm.severity()), code, message);
        out.setField(vm.field());
        return out;
    }

    /**
     * Map a list of domain {@link ValidationMessage}s into their wire DTOs.
     *
     * @param messages      the domain findings (never {@code null})
     * @param messageSource Spring's i18n message source
     * @param locale        the resolved request locale
     * @return one wire DTO per input message, in the same order
     */
    public static List<ValidationMessageResponse> toWire(List<ValidationMessage> messages,
                                                        MessageSource messageSource,
                                                        Locale locale) {
        return messages.stream()
                .map(vm -> toWire(vm, messageSource, locale))
                .toList();
    }

    private static Severity toWireSeverity(ch.owt.boatapp.domain.model.validation.Severity domainSeverity) {
        return switch (domainSeverity) {
            case ERROR -> Severity.ERROR;
            case WARNING -> Severity.WARNING;
            case INFO -> Severity.INFO;
        };
    }

    private BoatWebMapper() {
        // utility class — not instantiable
    }
}
