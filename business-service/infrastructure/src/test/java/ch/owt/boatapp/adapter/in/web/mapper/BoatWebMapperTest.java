package ch.owt.boatapp.adapter.in.web.mapper;

import ch.owt.boatapp.adapter.in.web.dto.generated.BoatResponse;
import ch.owt.boatapp.adapter.in.web.dto.generated.PageBoatResponse;
import ch.owt.boatapp.adapter.in.web.dto.generated.Severity;
import ch.owt.boatapp.adapter.in.web.dto.generated.ValidationMessageResponse;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.validation.MessageType;
import ch.owt.boatapp.domain.model.validation.ValidationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java unit tests for {@link BoatWebMapper}. Pin the domain → wire DTO
 * conversion contract: every {@code Boat} field surfaces verbatim, page
 * envelopes derive {@code first}/{@code last}/{@code empty} flags from page
 * coordinates, and validation messages always use the
 * {@link MessageType#applicationCode()} (never the Jakarta name or the enum
 * symbol).
 */
class BoatWebMapperTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 4, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    /** {@code toResponse}: copies every component including nullable description. */
    @Test
    void toResponse_copiesEveryField() {
        Boat boat = new Boat(ID, "Argo", "trireme", CREATED_AT, 7L);

        BoatResponse response = BoatWebMapper.toResponse(boat);

        assertThat(response.getId()).isEqualTo(ID);
        assertThat(response.getName()).isEqualTo("Argo");
        assertThat(response.getDescription()).isEqualTo("trireme");
        assertThat(response.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(response.getVersion()).isEqualTo(7L);
    }

    /** {@code toResponse}: tolerates {@code null} description. */
    @Test
    void toResponse_nullDescription_passesThrough() {
        Boat boat = new Boat(ID, "Argo", null, CREATED_AT, 0L);

        BoatResponse response = BoatWebMapper.toResponse(boat);

        assertThat(response.getDescription()).isNull();
    }

    /** Empty page: {@code first=true}, {@code last=true}, {@code empty=true}. */
    @Test
    void toPage_emptyPage_setsAllFlags() {
        PageResult<Boat> page = new PageResult<>(List.of(), 0L, 0, 10, 0);

        PageBoatResponse response = BoatWebMapper.toPage(page);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
        assertThat(response.getSize()).isEqualTo(10);
        assertThat(response.getNumber()).isZero();
        assertThat(response.getFirst()).isTrue();
        assertThat(response.getLast()).isTrue();
        assertThat(response.getEmpty()).isTrue();
    }

    /** Single full page: {@code first=true, last=true, empty=false}. */
    @Test
    void toPage_singleFullPage_isFirstAndLast() {
        Boat boat = new Boat(ID, "Argo", "trireme", CREATED_AT, 0L);
        PageResult<Boat> page = new PageResult<>(List.of(boat), 1L, 1, 10, 0);

        PageBoatResponse response = BoatWebMapper.toPage(page);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getFirst()).isTrue();
        assertThat(response.getLast()).isTrue();
        assertThat(response.getEmpty()).isFalse();
    }

    /** First of multiple pages: {@code first=true, last=false}. */
    @Test
    void toPage_firstOfMany_setsFirstNotLast() {
        Boat boat = new Boat(ID, "Argo", "trireme", CREATED_AT, 0L);
        PageResult<Boat> page = new PageResult<>(List.of(boat), 25L, 3, 10, 0);

        PageBoatResponse response = BoatWebMapper.toPage(page);

        assertThat(response.getFirst()).isTrue();
        assertThat(response.getLast()).isFalse();
        assertThat(response.getEmpty()).isFalse();
    }

    /** Last of multiple pages: {@code first=false, last=true}. */
    @Test
    void toPage_lastOfMany_setsLastNotFirst() {
        Boat boat = new Boat(ID, "Argo", "trireme", CREATED_AT, 0L);
        PageResult<Boat> page = new PageResult<>(List.of(boat), 25L, 3, 10, 2);

        PageBoatResponse response = BoatWebMapper.toPage(page);

        assertThat(response.getFirst()).isFalse();
        assertThat(response.getLast()).isTrue();
    }

    /** Middle page: {@code first=false, last=false}. */
    @Test
    void toPage_middleOfMany_setsNeitherFirstNorLast() {
        Boat boat = new Boat(ID, "Argo", "trireme", CREATED_AT, 0L);
        PageResult<Boat> page = new PageResult<>(List.of(boat), 25L, 3, 10, 1);

        PageBoatResponse response = BoatWebMapper.toPage(page);

        assertThat(response.getFirst()).isFalse();
        assertThat(response.getLast()).isFalse();
    }

    /**
     * {@code toWire}: uses the {@link MessageType#applicationCode()} for both
     * the wire {@code code} field AND the message-source key. Every domain
     * severity maps to the matching wire severity.
     */
    @Test
    void toWire_singleMessage_usesApplicationCodeAsKeyAndWireCode() {
        ValidationMessage vm = new ValidationMessage(
                ch.owt.boatapp.domain.model.validation.Severity.ERROR,
                MessageType.SIZE_EXCEEDED, "Boat.name");
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("field.size.invalid", Locale.ENGLISH, "Field {0} exceeds size");

        ValidationMessageResponse out = BoatWebMapper.toWire(vm, source, Locale.ENGLISH);

        assertThat(out.getCode()).isEqualTo("field.size.invalid");
        assertThat(out.getField()).isEqualTo("Boat.name");
        assertThat(out.getMessage()).isEqualTo("Field Boat.name exceeds size");
        assertThat(out.getSeverity()).isEqualTo(Severity.ERROR);
    }

    /** Unknown code → fallback to the code itself (defensive). */
    @Test
    void toWire_missingMessageKey_fallsBackToCode() {
        ValidationMessage vm = new ValidationMessage(
                ch.owt.boatapp.domain.model.validation.Severity.WARNING,
                MessageType.INVALID_FORMAT, "Boat.name");
        StaticMessageSource source = new StaticMessageSource();

        ValidationMessageResponse out = BoatWebMapper.toWire(vm, source, Locale.ENGLISH);

        assertThat(out.getCode()).isEqualTo("field.format.invalid");
        assertThat(out.getMessage()).isEqualTo("field.format.invalid");
        assertThat(out.getSeverity()).isEqualTo(Severity.WARNING);
    }

    /** {@code field == null} is tolerated; the message-source argument becomes empty. */
    @Test
    void toWire_nullField_isTolerated() {
        ValidationMessage vm = new ValidationMessage(
                ch.owt.boatapp.domain.model.validation.Severity.INFO,
                MessageType.CANNOT_BE_BLANK, null);
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("field.required", Locale.ENGLISH, "Field [{0}] required");

        ValidationMessageResponse out = BoatWebMapper.toWire(vm, source, Locale.ENGLISH);

        assertThat(out.getField()).isNull();
        assertThat(out.getMessage()).isEqualTo("Field [] required");
        assertThat(out.getSeverity()).isEqualTo(Severity.INFO);
    }

    /** Bulk {@code toWire(List)}: preserves order. */
    @Test
    void toWire_listPreservesOrder() {
        ValidationMessage a = new ValidationMessage(
                ch.owt.boatapp.domain.model.validation.Severity.ERROR,
                MessageType.SIZE_EXCEEDED, "Boat.name");
        ValidationMessage b = new ValidationMessage(
                ch.owt.boatapp.domain.model.validation.Severity.ERROR,
                MessageType.CANNOT_BE_BLANK, "Boat.description");
        StaticMessageSource source = new StaticMessageSource();

        List<ValidationMessageResponse> out = BoatWebMapper.toWire(List.of(a, b), source, Locale.ENGLISH);

        assertThat(out).extracting(ValidationMessageResponse::getField)
                .containsExactly("Boat.name", "Boat.description");
    }
}
