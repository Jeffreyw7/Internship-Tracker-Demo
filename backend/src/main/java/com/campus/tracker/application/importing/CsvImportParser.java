package com.campus.tracker.application.importing;

import com.campus.tracker.common.ApiException;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CsvImportParser {
    private final ImportInputLimits limits;
    public CsvImportParser() { this(new ImportInputLimits()); }
    @Autowired public CsvImportParser(ImportInputLimits limits) { this.limits = limits; }
    public record ParsedCsv(List<String> headers, List<List<String>> records, String sourceDigest) {
        public ParsedCsv {
            headers = List.copyOf(headers);
            records = records.stream().map(List::copyOf).toList();
        }
        @Override public String toString() { return "ParsedCsv[redacted]"; }
    }
    private static final Set<String> RESERVED = Set.of("owner", "ownerid", "userid", "accountid", "version",
            "targetversion", "created", "createdat", "updated", "updatedat", "deleted", "deletedat", "isdeleted",
            "sample", "issample", "sampledata", "stageid", "stagesid", "interviewstageid", "interviewstagesid");
    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false)
            .setIgnoreSurroundingSpaces(false).setTrim(false).setLenientEof(false).setTrailingData(false).get();

    public ParsedCsv parse(byte[] file) {
        if (file == null || file.length > limits.fileBytes()) throw limit();
        long parseStart = System.nanoTime();
        try {
            String text = decodeUtf8(file);
            if (text.startsWith("\ufeff")) text = text.substring(1);
            if (text.indexOf('\0') >= 0) throw invalid();
            List<String> headers = null;
            var rows = new ArrayList<List<String>>();
            try (CSVParser parser = FORMAT.parse(new StringReader(text))) {
                for (CSVRecord record : parser) {
                    ImportUploadPolicy.checkCurrentDeadline();
                    if (System.nanoTime() - parseStart > limits.parseWorkMs() * 1_000_000L) throw ImportUploadPolicy.timeout();
                    if (record.size() > limits.sourceColumns()) throw limit();
                    var cells = record.toList();
                    if (headers == null) {
                        var names = new HashSet<String>();
                        for (String header : cells) {
                            String key = header.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                            String reservedKey = key.replaceAll("[\\s_\\-.]", "");
                            if (header.getBytes(StandardCharsets.UTF_8).length > limits.headerBytes()) throw limit();
                            if (key.isEmpty() || !names.add(key) || RESERVED.contains(reservedKey)) throw invalid();
                        }
                        headers = cells;
                    } else {
                        if (cells.size() != headers.size() || (cells.size() == 1 && cells.get(0).isEmpty())) throw invalid();
                        if (rows.size() == limits.dataRecords()) throw limit();
                        for (String cell : cells) if (cell.getBytes(StandardCharsets.UTF_8).length > limits.cellBytes()) throw limit();
                        rows.add(cells);
                    }
                }
            }
            if (headers == null) throw invalid();
            return new ParsedCsv(headers, rows, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file)));
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw invalid();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Required digest unavailable"); }
    }
    static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_INVALID_CSV", "The CSV structure, headers, or UTF-8 encoding is invalid."); }
    private static ApiException limit() { return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_SOURCE_LIMIT", "The CSV exceeds an import source limit."); }
}
