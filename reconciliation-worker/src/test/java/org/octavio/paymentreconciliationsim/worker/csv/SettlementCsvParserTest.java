package org.octavio.paymentreconciliationsim.worker.csv;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.octavio.paymentreconciliationsim.worker.domain.ReconciliationModel.SettlementRow;

class SettlementCsvParserTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final String HEADER = "business_date,transaction_reference,amount_centavos,currency";
    private final SettlementCsvParser parser = new SettlementCsvParser();

    @Test
    void preservesCanonicalRowsAndDuplicateEvidence() throws IOException {
        assertEquals(List.of(new SettlementRow(1, "MATCH-001", 10000),
                new SettlementRow(2, "EXT-001", 30000), new SettlementRow(3, "AMOUNT-001", 45000),
                new SettlementRow(4, "DUP-001", 50000), new SettlementRow(5, "DUP-001", 50000)),
                parse(HEADER + "\n2026-10-01,MATCH-001,10000,ARS\n2026-10-01,EXT-001,30000,ARS\n"
                        + "2026-10-01,AMOUNT-001,45000,ARS\n2026-10-01,DUP-001,50000,ARS\n"
                        + "2026-10-01,DUP-001,50000,ARS\n"));
    }

    @ParameterizedTest
    @MethodSource("validRepresentations")
    void acceptsQuotedFieldsAndBothLineEndings(String csv) throws IOException {
        assertEquals(List.of(new SettlementRow(1, "a_Z-09", Long.MAX_VALUE)), parse(csv));
    }

    static Stream<String> validRepresentations() {
        return Stream.of(HEADER + "\n2026-10-01,a_Z-09,9223372036854775807,ARS",
                HEADER + "\r\n2026-10-01,a_Z-09,9223372036854775807,ARS\r\n",
                "\"business_date\",\"transaction_reference\",\"amount_centavos\",\"currency\"\n"
                        + "\"2026-10-01\",\"a_Z-09\",\"9223372036854775807\",\"ARS\"\n");
    }

    @Test
    void headerOnlyHasZeroExternalTransactions() throws IOException {
        assertEquals(List.of(), parse(HEADER));
        assertEquals(List.of(), parse(HEADER + "\r\n"));
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void requiresExactMandatoryHeader(String csv, String code) {
        invalid(csv, code, 0);
    }

    static Stream<Arguments> invalidHeaders() {
        return Stream.of(Arguments.of("", "MISSING_HEADER"),
                Arguments.of("\n" + HEADER, "INVALID_HEADER"),
                Arguments.of("\uFEFF" + HEADER, "INVALID_HEADER"),
                Arguments.of("business_date,amount_centavos,transaction_reference,currency", "INVALID_HEADER"),
                Arguments.of(HEADER + ",", "INVALID_HEADER"),
                Arguments.of("business_date,transaction_reference,amount_centavos", "INVALID_HEADER"),
                Arguments.of(HEADER.replace("currency", "Currency"), "INVALID_HEADER"),
                Arguments.of(" " + HEADER, "INVALID_HEADER"),
                Arguments.of("\"business_date", "MALFORMED_CSV"));
    }

    @ParameterizedTest
    @MethodSource("invalidRows")
    void rejectsWholeFileAtInvalidLogicalRecord(String row, String code) {
        invalid(HEADER + "\n2026-10-01,GOOD,1,ARS\n" + row, code, 2);
    }

    static Stream<Arguments> invalidRows() {
        return Stream.of(
                Arguments.of("2026-10-01,A,1", "INVALID_FIELD_COUNT"),
                Arguments.of("2026-10-01,A,1,ARS,", "INVALID_FIELD_COUNT"),
                Arguments.of("\n", "INVALID_FIELD_COUNT"),
                Arguments.of("2026-09-30,A,1,ARS", "INVALID_DATE"),
                Arguments.of("garbage,A,1,ARS", "INVALID_DATE"),
                Arguments.of(" 2026-10-01,A,1,ARS", "INVALID_DATE"),
                Arguments.of("2026-10-01,,1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01, A,1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01,A ,1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01,\"A,B\",1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01,\"A\nB\",1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01,\"A\"\"B\",1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01,\u00E9,1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01," + "A".repeat(65) + ",1,ARS", "INVALID_REFERENCE"),
                Arguments.of("2026-10-01,A,0,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,-1,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,+1,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,1.0,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A, 1,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,1 ,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,\u0661,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,9223372036854775808,ARS", "INVALID_AMOUNT"),
                Arguments.of("2026-10-01,A,1,ars", "INVALID_CURRENCY"),
                Arguments.of("2026-10-01,A,1,USD", "INVALID_CURRENCY"),
                Arguments.of("2026-10-01,A,1,ARS ", "INVALID_CURRENCY"),
                Arguments.of("2026-10-01,A,1,", "INVALID_CURRENCY"),
                Arguments.of("2026-10-01,\"A\"garbage,1,ARS", "MALFORMED_CSV"),
                Arguments.of("2026-10-01,\"A\nB", "MALFORMED_CSV"));
    }

    @Test
    void preservesCaseAndAcceptsReferenceAndAmountBoundaries() throws IOException {
        String ref = "a".repeat(64);
        assertEquals(List.of(new SettlementRow(1, ref, 1), new SettlementRow(2, "A", 1)),
                parse(HEADER + "\n2026-10-01," + ref + ",0001,ARS\n2026-10-01,A,1,ARS"));
    }

    @Test
    void rejectsMalformedUtf8InsteadOfReplacingBytes() {
        byte[] prefix = (HEADER + "\n2026-10-01,").getBytes(StandardCharsets.UTF_8);
        byte[] csv = java.util.Arrays.copyOf(prefix, prefix.length + 1);
        csv[prefix.length] = (byte) 0xc3;
        var failure = assertThrows(SettlementValidationException.class,
                () -> parser.parse(new ByteArrayInputStream(csv), DATE));
        assertEquals("INVALID_UTF8", failure.code());
        assertEquals(0, failure.recordNumber());
        assertInstanceOf(java.nio.charset.CharacterCodingException.class, failure.getCause());
        assertFalse(failure.getMessage().contains("2026-10-01"));
    }

    @Test
    void settlementLimitsAreInclusive() throws IOException {
        String row = "2026-10-01,A,1,ARS\n";
        assertEquals(2000, parse(HEADER + "\n" + row.repeat(2000)).size());
        var rowOverflow = invalid(HEADER + "\n" + row.repeat(2001), "TOO_MANY_ROWS", 2001);
        assertEquals("TOO_MANY_ROWS", rowOverflow.code());
        String prefix = HEADER + "\n2026-10-01,A,1,\"ARS\"";
        String atLimit = prefix + " ".repeat(2097152 - prefix.length() - 1) + "\n";
        assertEquals(2097152, atLimit.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(List.of(new SettlementRow(1, "A", 1)), parse(atLimit));
        var byteOverflow = invalid(prefix + " ".repeat(2097152 - prefix.length()) + "\n", "FILE_TOO_LARGE", 0);
        assertEquals("FILE_TOO_LARGE", byteOverflow.code());
    }

    @Test
    void preservesTransportFailureAndExternallyOwnedStream() throws IOException {
        IOException transport = new IOException("transport failed");
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw transport; }
        };
        assertSame(transport, assertThrows(IOException.class, () -> parser.parse(broken, DATE)));
        var stream = new ByteArrayInputStream((HEADER + "\n2026-10-01,A,1,ARS").getBytes(StandardCharsets.UTF_8)) {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        assertEquals(1, parser.parse(stream, DATE).size());
        assertFalse(stream.closed);
        var invalidStream = new ByteArrayInputStream(new byte[0]) {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        assertThrows(SettlementValidationException.class, () -> parser.parse(invalidStream, DATE));
        assertFalse(invalidStream.closed);
    }

    @Test
    void boundedStreamPreservesUnsignedSingleByteAndEof() throws IOException {
        var stream = new SettlementCsvParser.BoundedInputStream(
                new ByteArrayInputStream(new byte[] {(byte) 0xff}));
        assertEquals(255, stream.read());
        assertEquals(-1, stream.read());
    }

    @Test
    void boundedStreamReadsOnlyOneOverflowProbeByte() throws IOException {
        var source = new ByteArrayInputStream(new byte[2097154]);
        var stream = new SettlementCsvParser.BoundedInputStream(source);
        assertEquals(2097152, stream.readNBytes(2097152).length);
        assertEquals(0, stream.read(new byte[0], 0, 0));
        var failure = assertThrows(SettlementValidationException.class, () -> stream.read());
        assertEquals("FILE_TOO_LARGE", failure.code());
        assertEquals(0, failure.recordNumber());
        assertEquals(1, source.available());
    }

    @Test
    void boundedBulkReadStopsAfterOneOverflowProbe() throws IOException {
        var source = new ByteArrayInputStream(new byte[2097156]);
        var stream = new SettlementCsvParser.BoundedInputStream(source);
        assertEquals(2097151, stream.readNBytes(2097151).length);
        var failure = assertThrows(SettlementValidationException.class,
                () -> stream.read(new byte[6], 0, 6));
        assertEquals("FILE_TOO_LARGE", failure.code());
        assertEquals(3, source.available());
    }

    private List<SettlementRow> parse(String csv) throws IOException {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), DATE);
    }

    private SettlementValidationException invalid(String csv, String code, long row) {
        var failure = assertThrows(SettlementValidationException.class, () -> parse(csv));
        assertEquals(code, failure.code());
        assertEquals(row, failure.recordNumber());
        return failure;
    }
}
