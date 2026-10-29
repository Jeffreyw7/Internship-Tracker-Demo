package com.campus.tracker.application.importing;

import com.campus.tracker.application.ApplicationRules;
import com.campus.tracker.application.ApplicationStatus;
import com.campus.tracker.application.StageState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.campus.tracker.application.ApplicationDtos.*;
import static com.campus.tracker.application.importing.ImportDtos.*;
import static org.assertj.core.api.Assertions.*;

class ImportSemanticsTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ImportSemantics semantics = new ImportSemantics(new ApplicationRules(
            Validation.buildDefaultValidatorFactory().getValidator()), json);

    @Test void addDefaultsAndNormalizationAreReviewed() {
        var rows = project(Map.of("0", "company", "1", "role"), List.of(" Acme ", " Engineer "), List.of(), List.of());
        assertThat(rows.get(0).after().company()).isEqualTo("Acme");
        assertThat(rows.get(0).after().status()).isEqualTo(ApplicationStatus.SAVED);
        assertThat(rows.get(0).after().stages()).isEmpty();
        assertThat(rows.get(0).action()).isEqualTo("Add");
        assertThat(rows.get(0).issues()).isEmpty();
    }

    @Test void blankUpdateKeepsAndExplicitClearClears() {
        ApplicationView before = existing();
        var correction = new RowOverride(1,"Update",before.id(),Map.of(),List.of("notes"),"KEEP",false);
        var row = project(Map.of("0","company","1","role"),List.of("", ""),List.of(correction),List.of(before)).get(0);
        assertThat(row.after().company()).isEqualTo("Acme");
        assertThat(row.after().notes()).isEmpty();
        assertThat(row.intents().get("company").intent()).isEqualTo("KEEP");
        assertThat(row.intents().get("notes").intent()).isEqualTo("CLEAR");
        assertThat(row.action()).isEqualTo("Update");
    }

    @Test void advisoryMatchesNeverSelectUpdateAndUnavailableIdsNeverBecomeAdds() {
        var before = existing();
        var row = project(Map.of("0","company","1","role"),List.of("Acme","Engineer"),List.of(),List.of(before)).get(0);
        assertThat(row.action()).isEqualTo("Add");
        assertThat(row.candidates()).hasSize(1);
        var invalid = project(Map.of("0","applicationId","1","role"),List.of(UUID.randomUUID().toString(),"Engineer"),List.of(),List.of(before)).get(0);
        assertThat(invalid.issues()).extracting(Issue::code).contains("IMPORT_TARGET_UNAVAILABLE");
        assertThat(invalid.after()).isNull();
    }

    @Test void legacyDisclosureAndDuplicateTargetsBlockIncludedRows() {
        var before = existing();
        var csv = new CsvImportParser.ParsedCsv(List.of("ID","Legacy"),List.of(
                List.of(before.id().toString(),"Screen | Planned"),List.of(before.id().toString(),"")),"digest");
        var options = new Options(1,Map.of("0","applicationId","1","legacyStages"),"ISO",List.of(),null);
        var rows = semantics.project(csv,options,List.of(before));
        assertThat(rows.get(0).issues()).extracting(Issue::code).contains("IMPORT_DUPLICATE_TARGET","IMPORT_LEGACY_ACK_REQUIRED");
        assertThat(rows.get(1).issues()).extracting(Issue::code).contains("IMPORT_DUPLICATE_TARGET");
    }

    @Test void importedStageIdsAreRejectedEvenOnSkippedRows() {
        var override = new RowOverride(1,"Skip",null,Map.of(),List.of(),"REPLACE",false);
        assertThatThrownBy(() -> project(Map.of("0","company","1","stages"),
                List.of("Acme","[{\"id\":\""+UUID.randomUUID()+"\",\"name\":\"Screen\",\"date\":null,\"state\":\"Planned\"}]"),
                List.of(override),List.of())).hasMessageContaining("stage");
    }

    @Test void identicalStageReplacementKeepsIdentityAndIsUnchanged() {
        var base = existing();
        UUID stageId = UUID.randomUUID();
        var before = new ApplicationView(base.id(),base.company(),base.role(),base.location(),base.status(),"","","",base.notes(),
                List.of(new StageView(stageId,"Screen","",StageState.PLANNED)),base.createdAt(),base.updatedAt(),base.version(),false,false);
        var override = new RowOverride(1,"Update",before.id(),Map.of(),List.of(),"REPLACE",false);
        var row = project(Map.of("0","company","1","stages"),List.of("", "[{\"name\":\"Screen\",\"date\":null,\"state\":\"Planned\"}]"),
                List.of(override),List.of(before)).get(0);
        assertThat(row.action()).isEqualTo("Unchanged");
        assertThat(row.after().stages().get(0).id()).isEqualTo(stageId);
    }

    @Test void impossibleDatesAndFullMergedValidationBlockRows() {
        var correction = new RowOverride(1,null,null,Map.of("appliedDate","02/30/2025","status","Applied"),List.of(),null,false);
        var csv = new CsvImportParser.ParsedCsv(List.of("Company","Role"),List.of(List.of("Acme","Engineer")),"digest");
        var row = semantics.project(csv,new Options(1,Map.of("0","company","1","role"),"MDY",List.of(correction),null),List.of()).get(0);
        assertThat(row.issues()).isNotEmpty();
    }

    @Test void completeRecordErrorsIdentifyTheirEditableField() {
        var missing=project(Map.of("0","company","1","role"),List.of(" ","Engineer"),List.of(),List.of()).get(0);
        assertThat(missing.issues()).anySatisfy(i->{assertThat(i.field()).isEqualTo("company");assertThat(i.message()).isNotBlank();});
        var stages=project(Map.of("0","company","1","role"),List.of("Acme","Engineer"),
                List.of(new RowOverride(1,null,null,Map.of("stages","[{\"name\":\" \",\"date\":null,\"state\":\"Planned\"}]"),List.of(),null,false)),List.of()).get(0);
        assertThat(stages.issues()).anySatisfy(i->assertThat(i.field()).isEqualTo("stages"));
    }

    @Test void tightenedScalarLimitAlsoAppliesToExcludedCorrections() {
        var limits=new ImportInputLimits(2097152,262144,3145728,500,32,3,256,30000,10000,5000,4);
        var narrow=new ImportSemantics(new ApplicationRules(Validation.buildDefaultValidatorFactory().getValidator()),json,limits);
        var csv=new CsvImportParser.ParsedCsv(List.of("Company","Role"),List.of(List.of("A","B")),"digest");
        var options=new Options(1,Map.of("0","company","1","role"),"ISO",List.of(new RowOverride(1,"Skip",null,Map.of("notes","four"),List.of(),null,false)),null);
        assertThatThrownBy(()->narrow.project(csv,options,List.of())).isInstanceOfSatisfying(com.campus.tracker.common.ApiException.class,e->assertThat(e.getCode()).isEqualTo("IMPORT_SIZE_LIMIT"));
    }

    private List<PreviewRow> project(Map<String,String> mapping,List<String> cells,List<RowOverride> overrides,List<ApplicationView> apps) {
        var csv = new CsvImportParser.ParsedCsv(List.of("First","Second"),List.of(cells),"digest");
        return semantics.project(csv,new Options(1,mapping,"ISO",overrides,null),apps);
    }
    private ApplicationView existing() {
        return new ApplicationView(UUID.randomUUID(),"Acme","Engineer","Remote",ApplicationStatus.SAVED,"","","","keep me",List.of(),Instant.EPOCH,Instant.EPOCH,3,false,false);
    }
}
