package com.campus.tracker.export;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CsvWriterTest {
    @Test void completeCsvHasBomCrLfQuotingAndFormulaProtection() throws Exception {
        var output = new ByteArrayOutputStream(); var csv = new CsvWriter();
        csv.header(output);
        csv.row(output, new CsvWriter.Row("=Formula", "Role, quoted", "Remote", "Offer", LocalDate.parse("2026-09-30"), null,
                LocalDate.parse("2026-10-04"), List.of(new CsvWriter.Stage("Systems \"round\"", "Scheduled", LocalDate.parse("2026-10-05"))), "Line one\nLine two"));
        var bytes = output.toByteArray();
        assertThat(java.util.Arrays.copyOf(bytes, 3)).containsExactly((byte)0xef, (byte)0xbb, (byte)0xbf);
        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("\"'=Formula\"", "\"Role, quoted\"", "Systems \"\"round\"\" | Scheduled | 2026-10-05", "\"Line one\nLine two\"");
        assertThat(text).startsWith("\uFEFF\"Company\",\"Role\",\"Location\",\"Status\",\"Application date\",\"Deadline\",\"Follow-up date\",\"Interview stages\",\"Notes\"\r\n");
        assertThat(text).endsWith("\r\n");
        for (String value : List.of("+formula", "-formula", "@formula", "\tformula", "\rformula")) {
            var single = new ByteArrayOutputStream();
            csv.row(single, new CsvWriter.Row(value, "Role", "", "Saved", null, null, null, List.of(), ""));
            assertThat(single.toString(StandardCharsets.UTF_8)).startsWith("\"'" + value + "\"");
        }
    }
}
