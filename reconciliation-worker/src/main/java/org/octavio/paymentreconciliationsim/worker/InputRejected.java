package org.octavio.paymentreconciliationsim.worker;
/** Deterministic rejection with a safe machine-readable diagnostic. */
public final class InputRejected extends RuntimeException {
 private final String code;
 public InputRejected(String code) { super(code); this.code = code; }
 public String code() { return code; }
}
