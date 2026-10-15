package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CsvImportParserTest {
    private final CsvImportParser parser = new CsvImportParser();
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void readsQuotedMultilineLiteralValuesAndOneBom() {
        var csv = parser.parse(bytes("\ufeffCompany,Role,Notes\r\n\"Acme, Inc\",\"A \"\"role\"\"\",\"line 1\nline 2\"\r\n=literal,'literal, x \r\n"));
        assertThat(csv.headers()).containsExactly("Company", "Role", "Notes");
        assertThat(csv.records()).containsExactly(
                java.util.List.of("Acme, Inc", "A \"role\"", "line 1\nline 2"),
                java.util.List.of("=literal", "'literal", " x "));
        assertThat(csv.sourceDigest()).matches("[0-9a-f]{64}");
        assertThat(csv.toString()).doesNotContain("Acme", "literal");
    }

    @Test void rejectsMalformedContentWithoutEchoingSource() {
        for (String source : new String[]{"", "Company,Role\nsecret", "Company,Role\n\n", "Company,Role\n\"secret,x", "Company,Role\nsecret,\u0000", "Company, company \nx,y", "Company,\nx,y", "Company,Owner_ID\nx,y", "Company,Created at\nx,y", "Company,stages.id\nx,y"}) {
            assertThatThrownBy(() -> parser.parse(bytes(source))).isInstanceOf(ApiException.class)
                    .hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> parser.parse(new byte[]{'a', '\n', (byte) 0xc3, 0x28})).isInstanceOf(ApiException.class);
    }

    @Test void acceptsHeaderOnlyAndConsumesThroughThe501stDataRecord() {
        assertThat(parser.parse(bytes("Company,Role\n")).records()).isEmpty();
        assertThat(parser.parse(bytes("Company,Role\n" + "a,b\n".repeat(500))).records()).hasSize(500);
        assertThatThrownBy(() -> parser.parse(bytes("Company,Role\n" + "a,b\n".repeat(501)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse(bytes("Company,Role\n" + "a,b\n".repeat(500) + "\"bad"))).isInstanceOf(ApiException.class);
    }

    @Test void enforcesAllColumnsHeadersCellsAndFileBytesBeforeMapping() {
        String headers = IntStream.range(0, 32).mapToObj(i -> "h" + i).collect(Collectors.joining(","));
        assertThat(parser.parse(bytes(headers + "\n" + String.join(",", java.util.Collections.nCopies(32, "x")) ))).isNotNull();
        assertThatThrownBy(() -> parser.parse(bytes(headers + ",overflow\n"))).isInstanceOf(ApiException.class);
        assertThat(parser.parse(bytes("h".repeat(256) + "\n" + "é".repeat(32768)))).isNotNull();
        assertThatThrownBy(() -> parser.parse(bytes("h".repeat(257)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse(bytes("ignored\n" + "é".repeat(32769)))).isInstanceOf(ApiException.class);
        String row = "x".repeat(65535) + "\n";
        assertThat(parser.parse(bytes("h\n" + row.repeat(31) + "x".repeat(65534)))).isNotNull();
        assertThatThrownBy(() -> parser.parse(new byte[2097153])).isInstanceOf(ApiException.class);
    }
}
