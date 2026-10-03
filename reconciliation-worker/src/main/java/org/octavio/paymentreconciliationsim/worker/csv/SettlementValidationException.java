package org.octavio.paymentreconciliationsim.worker.csv;

/** Deterministic input rejection; record 0 identifies the header or whole file. */
public final class SettlementValidationException extends RuntimeException {
    private final String code;
    private final long recordNumber;

    public SettlementValidationException(String code, long recordNumber) {
        this(code, recordNumber, null);
    }

    public SettlementValidationException(String code, long recordNumber, Throwable cause) {
        super(code + " at logical data record " + recordNumber, cause);
        this.code = code;
        this.recordNumber = recordNumber;
    }

    public String code() { return code; }
    public long recordNumber() { return recordNumber; }
}
