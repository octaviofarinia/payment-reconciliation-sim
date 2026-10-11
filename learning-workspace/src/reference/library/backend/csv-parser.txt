package org.octavio.paymentreconciliationsim.worker.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.octavio.paymentreconciliationsim.worker.domain.ReconciliationModel.SettlementRow;

/** Bounded, strict ingestion of externally owned settlement bytes. */
public final class SettlementCsvParser {
    private static final int MAX_BYTES = 2_097_152;
    private static final int MAX_ROWS = 2_000;
    private static final List<String> HEADER = List.of(
            "business_date", "transaction_reference", "amount_centavos", "currency");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern AMOUNT = Pattern.compile("[0-9]+");

    /** IO failures propagate unchanged; no success value escapes unless the entire file is valid. */
    public List<SettlementRow> parse(InputStream bytes, LocalDate expectedDate) throws IOException {
        // Buffer at most the allowed bytes plus one overflow probe, never trusting object metadata.
        byte[] raw = new BoundedInputStream(bytes).readAllBytes();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException failure) {
            throw new SettlementValidationException("INVALID_UTF8", 0, failure);
        }
        // This reader owns only the decoded String, so closing it cannot close the caller's stream.
        try (CSVParser csv = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false)
                .setTrim(false).setIgnoreSurroundingSpaces(false).get().parse(new StringReader(text))) {
            Iterator<CSVRecord> records = csv.iterator();
            CSVRecord header = next(records, 0);
            if (header == null) {
                throw new SettlementValidationException("MISSING_HEADER", 0);
            }
            if (!HEADER.equals(header.toList())) {
                throw new SettlementValidationException("INVALID_HEADER", 0);
            }
            List<SettlementRow> rows = new ArrayList<>();
            CSVRecord record;
            while ((record = next(records, rows.size() + 1L)) != null) {
                int rowNumber = rows.size() + 1;
                if (rowNumber > MAX_ROWS) {
                    throw new SettlementValidationException("TOO_MANY_ROWS", rowNumber);
                }
                rows.add(validate(record, rowNumber, expectedDate));
            }
            return List.copyOf(rows);
        }
    }

    private CSVRecord next(Iterator<CSVRecord> records, long rowNumber) {
        try {
            return records.hasNext() ? records.next() : null;
        } catch (UncheckedIOException failure) {
            throw new SettlementValidationException("MALFORMED_CSV", rowNumber, failure.getCause());
        }
    }

    private SettlementRow validate(CSVRecord record, int row, LocalDate date) {
        if (record.size() != 4) {
            throw new SettlementValidationException("INVALID_FIELD_COUNT", row);
        }
        if (!date.toString().equals(record.get(0))) {
            throw new SettlementValidationException("INVALID_DATE", row);
        }
        String reference = record.get(1);
        if (!REFERENCE.matcher(reference).matches()) {
            throw new SettlementValidationException("INVALID_REFERENCE", row);
        }
        String amount = record.get(2);
        if (!AMOUNT.matcher(amount).matches()) {
            throw new SettlementValidationException("INVALID_AMOUNT", row);
        }
        long centavos;
        try {
            centavos = Long.parseLong(amount);
        } catch (NumberFormatException failure) {
            throw new SettlementValidationException("INVALID_AMOUNT", row, failure);
        }
        if (centavos <= 0) {
            throw new SettlementValidationException("INVALID_AMOUNT", row);
        }
        if (!"ARS".equals(record.get(3))) {
            throw new SettlementValidationException("INVALID_CURRENCY", row);
        }
        return new SettlementRow(row, reference, centavos);
    }

    /** Counts actual reads, including a one-byte overflow probe, without owning the source. */
    static final class BoundedInputStream extends InputStream {
        private final InputStream source;
        private int count;

        BoundedInputStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            int read = source.read(target, offset, Math.min(length, MAX_BYTES - count + 1));
            count += Math.max(read, 0);
            if (count > MAX_BYTES) {
                throw new SettlementValidationException("FILE_TOO_LARGE", 0);
            }
            return read;
        }
    }
}
