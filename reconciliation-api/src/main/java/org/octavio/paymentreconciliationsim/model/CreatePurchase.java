package org.octavio.paymentreconciliationsim.model;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record CreatePurchase(String transactionReference, String merchantId, LocalDate businessDate,
                             long amountCentavos, String currency) {
    public CreatePurchase {
        if (transactionReference == null || !transactionReference.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transactionReference");
        }
        if (merchantId == null || merchantId.isEmpty()
                || merchantId.codePointCount(0, merchantId.length()) > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid merchantId");
        }
        if (businessDate == null || businessDate.getYear() < 0 || businessDate.getYear() > 9999) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid businessDate");
        }
        if (amountCentavos <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountCentavos must be positive");
        }
        if (!"ARS".equals(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency must be ARS");
        }
    }

    public boolean matches(Purchase purchase) {
        return transactionReference.equals(purchase.transactionReference())
                && merchantId.equals(purchase.merchantId())
                && businessDate.toString().equals(purchase.businessDate())
                && amountCentavos == purchase.amountCentavos()
                && currency.equals(purchase.currency());
    }
}
