package com.campus.tracker.application;

import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingDetailsInputTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private static final List<String> AMOUNTS = List.of("compensationMin", "compensationMax");

    @AfterAll static void closeValidator() { VALIDATION.close(); }

    @Test void rejectsOversizedRepresentationsEvenWhenTheirNumericValueWouldFit() {
        for (String field : AMOUNTS) {
            // Without a representation bound these are valid small amounts, so a range check cannot protect parsing.
            for (String value : List.of("0".repeat(60) + "25.10", "0".repeat(100_000) + "1")) {
                assertBadAmount(field, TextNode.valueOf(value));
            }
        }
    }

    @Test void acceptsBoundedDecimalFormsWithoutChangingTheirValues() throws Exception {
        Map<String, String> examples = Map.ofEntries(
                Map.entry("25.10", "25.10"), Map.entry("+25.10", "25.10"),
                Map.entry("00025.10", "25.10"), Map.entry("2.51E1", "25.10"),
                Map.entry(".50", "0.50"), Map.entry("25.", "25.00"),
                Map.entry("-0.00", "0.00"), Map.entry("0E+2147483647", "0.00"),
                Map.entry("999999999999.99", "999999999999.99"),
                Map.entry("0".repeat(59) + "25.10", "25.10"));
        for (String field : AMOUNTS) {
            for (var example : examples.entrySet()) {
                assertThat(amount(parse(field, TextNode.valueOf(example.getKey())), field))
                        .as(field + " for " + example.getKey()).isEqualTo(example.getValue());
            }
        }
    }

    @Test void preservesJsonNumbersAndUnknownEndpoints() throws Exception {
        for (String field : AMOUNTS) {
            assertThat(amount(parse(field, JSON.readTree("25")), field)).isEqualTo("25.00");
            assertThat(amount(parse(field, JSON.readTree("25.10")), field)).isEqualTo("25.10");
            assertThat(amount(parse(field, JSON.readTree("2.51e1")), field)).isEqualTo("25.10");
            assertThat(amount(parse(field, JSON.nullNode()), field)).isNull();
        }
    }

    @Test void rejectsOversizedDigitsAndExponentStrings() {
        for (String field : AMOUNTS) {
            for (String value : List.of("9".repeat(100_000), "1E" + "9".repeat(100_000),
                    "1E+2147483647", "1E-2147483647", "1E2147483648")) {
                assertBadAmount(field, TextNode.valueOf(value));
            }
        }
    }

    @Test void preservesRangeScaleAndMalformedInputRejection() throws Exception {
        for (String field : AMOUNTS) {
            for (String value : List.of("-1", "25.001", "1000000000000", "NaN", " 25.10", "")) {
                assertBadAmount(field, TextNode.valueOf(value));
            }
            assertBadAmount(field, JSON.readTree("false"));
            assertBadAmount(field, JSON.readTree("-1"));
        }
    }

    private void assertBadAmount(String field, JsonNode value) {
        assertThatThrownBy(() -> parse(field, value)).as(field)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private PostingDetails parse(String field, JsonNode value) throws Exception {
        ObjectNode input = (ObjectNode) JSON.readTree("""
                {"postingUrl":"","recruiterName":"","recruiterRole":"","recruiterEmail":"",
                 "recruiterPhone":"","recruiterProfileUrl":"","referralChannel":"UNKNOWN",
                 "referralSource":"","referrerName":"","referrerContact":"",
                 "compensationMin":null,"compensationMax":null,"compensationCurrency":"USD",
                 "compensationPeriod":"HOUR","compensationStatus":"PAID","compensationOriginalText":""}
                """);
        input.set(field, value);
        return PostingDetailsInput.parse(input, VALIDATION.getValidator()).details();
    }

    private String amount(PostingDetails details, String field) {
        var view = details.view(0);
        return field.equals("compensationMin") ? view.compensationMin() : view.compensationMax();
    }
}
