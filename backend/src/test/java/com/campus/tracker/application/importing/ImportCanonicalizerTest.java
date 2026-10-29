package com.campus.tracker.application.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.campus.tracker.common.ApiException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.campus.tracker.application.importing.ImportDtos.*;
import static org.assertj.core.api.Assertions.*;

class ImportCanonicalizerTest {
    private final ImportCanonicalizer canonical=new ImportCanonicalizer(new ObjectMapper().registerModule(new JavaTimeModule()));
    @Test void maximumMinimalReceiptFitsReservationAndHasNoSourceValues() {
        List<ReceiptRow> rows=new ArrayList<>();
        for(int i=1;i<=500;i++) rows.add(new ReceiptRow(i,"Unchanged",UUID.randomUUID(),Long.MAX_VALUE));
        Instant time=Instant.parse("2026-10-15T12:00:00.123456Z");
        Receipt receipt=new Receipt(UUID.randomUUID(),UUID.randomUUID(),time,time.plusSeconds(30*86400),new Counts(0,0,0,500,0),rows);
        String serialized=canonical.write(receipt,ImportCanonicalizer.RECEIPT_LIMIT);
        assertThat(ImportCanonicalizer.bytes(serialized)).isLessThan(128*1024);
        assertThat(serialized).doesNotContain("company","notes","cells","stages");
        assertThat(canonical.read(serialized,Receipt.class)).isEqualTo(receipt);
    }
    @Test void outputCapIsEnforcedDuringSerialization() {
        assertThatThrownBy(()->canonical.write(Map.of("notes","x".repeat(100_000)),32)).isInstanceOf(ApiException.class);
    }
    @Test void optionsAndConfirmationRejectDuplicateUnknownAndTrailingJson() {
        for(String input:List.of("{\"reviewDigest\":\"a\",\"reviewDigest\":\"b\"}","{\"ownerId\":\"x\"}","{} {}",
                "{\"reviewDigest\":\"a\",\"acknowledgement\":{\"existingApplicationsToUpdate\":\"0\"}}",
                "{\"reviewDigest\":\"a\",\"acknowledgement\":{\"existingApplicationsToUpdate\":0.5}}"))
            assertThatThrownBy(()->canonical.read(input,CommitRequest.class)).isInstanceOf(ApiException.class);
        assertThat(canonical.read("{\"reviewDigest\":\"a\",\"acknowledgement\":{}}",CommitRequest.class).acknowledgement().existingApplicationsToUpdate()).isNull();
        assertThatThrownBy(()->canonical.read("{\"schemaVersion\":1,\"mapping\":{},\"dateFormat\":\"ISO\",\"rows\":[{\"rowNumber\":1,\"values\":{\"notes\":42}}]}",Options.class)).isInstanceOf(ApiException.class);
    }
}
