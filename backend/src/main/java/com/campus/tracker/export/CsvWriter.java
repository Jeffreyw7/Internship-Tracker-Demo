package com.campus.tracker.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** Write bounded records directly to the spool, preserving the existing account CSV contract. */
@Component
public class CsvWriter {
    public record Stage(String name, String state, LocalDate date) {}
    public record Row(String company, String role, String location, String status, LocalDate appliedDate,
            LocalDate deadline, LocalDate followUpDate, List<Stage> stages, String notes) {}
    public void header(OutputStream output) throws IOException {
        output.write(new byte[]{(byte)0xef, (byte)0xbb, (byte)0xbf});
        cells(output, List.of("Company", "Role", "Location", "Status", "Application date", "Deadline", "Follow-up date", "Interview stages", "Notes"));
    }
    public void row(OutputStream output, Row row) throws IOException {
        String stages = row.stages().stream().map(stage -> stage.name() + " | " + stage.state() + " | " + date(stage.date())).collect(java.util.stream.Collectors.joining("; "));
        cells(output, List.of(row.company(), row.role(), row.location(), row.status(), date(row.appliedDate()), date(row.deadline()), date(row.followUpDate()), stages, row.notes()));
    }
    private static String date(LocalDate date) { return date == null ? "" : date.toString(); }
    private static void cells(OutputStream output, List<String> values) throws IOException {
        String row = values.stream().map(CsvWriter::cell).collect(java.util.stream.Collectors.joining(",")) + "\r\n";
        output.write(row.getBytes(StandardCharsets.UTF_8));
    }
    private static String cell(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
