package com.campus.tracker.application;

import com.campus.tracker.common.ApiException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static com.campus.tracker.application.ApplicationDtos.*;
import static org.assertj.core.api.Assertions.*;

class ApplicationRulesTest {
    private final ApplicationRules rules = new ApplicationRules(Validation.buildDefaultValidatorFactory().getValidator());

    private ApplicationRequest draft(String company, String role, List<StageRequest> stages) {
        return new ApplicationRequest(company, role, " Remote ", ApplicationStatus.SAVED,
                null, null, null, " literal\nnotes ", stages);
    }

    @Test void normalizationRetainsLiteralNotesAndStageIdentity() {
        UUID id = UUID.randomUUID();
        var result = rules.normalizeAndValidate(draft(" Acme ", " Engineer ",
                List.of(new StageRequest(id, " Screen ", null, StageState.PLANNED))));
        assertThat(result.company()).isEqualTo("Acme");
        assertThat(result.role()).isEqualTo("Engineer");
        assertThat(result.location()).isEqualTo("Remote");
        assertThat(result.notes()).isEqualTo(" literal\nnotes ");
        assertThat(result.stages().get(0)).isEqualTo(new StageRequest(id, "Screen", null, StageState.PLANNED));
    }

    @Test void internalCallsEnforceNestedBeanConstraintsBeforeDereferencingFields() {
        for (var invalid : List.of(draft("x".repeat(101), "Role", List.of()),
                draft("Acme", " ", List.of()), draft(null, "Role", List.of()),
                draft("Acme", "Role", Arrays.asList((StageRequest) null)),
                draft("Acme", "Role", List.of(new StageRequest(null, "x".repeat(121), null, StageState.PLANNED))))) {
            assertThatThrownBy(() -> rules.normalizeAndValidate(invalid)).isInstanceOf(ApiException.class)
                    .hasMessageNotContaining("x".repeat(101));
        }
    }

    @Test void sharedRulesRejectMissingApplicationDateFutureDatesAndScheduledWithoutDate() {
        assertThatThrownBy(() -> rules.validate(new ApplicationRequest("Acme", "Role", "", ApplicationStatus.APPLIED,
                null, null, null, "", List.of()))).isInstanceOf(ApiException.class).hasMessageContaining("date");
        assertThatThrownBy(() -> rules.validate(new ApplicationRequest("Acme", "Role", "", ApplicationStatus.SAVED,
                LocalDate.now().plusDays(1), null, null, "", List.of()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> rules.validate(draft("Acme", "Role",
                List.of(new StageRequest(null, "Screen", null, StageState.SCHEDULED)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("date");
    }

    @Test void duplicateServerStageIdentitiesCannotEnterInternalSaves() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> rules.validate(draft("Acme", "Role", List.of(
                new StageRequest(id, "One", null, StageState.PLANNED),
                new StageRequest(id, "Two", null, StageState.PLANNED)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Duplicate");
    }
}
