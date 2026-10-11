package org.octavio.paymentreconciliationsim.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.octavio.paymentreconciliationsim.model.CreatePurchase;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

public final class PurchaseRequestDecoder {
    private PurchaseRequestDecoder() {}

    public static CreatePurchase purchase(JsonNode json) {
        fields(json, Set.of("transactionReference", "merchantId", "businessDate", "amountCentavos", "currency"));
        JsonNode amount = json.get("amountCentavos");
        if (!amount.isIntegralNumber() || !amount.canConvertToLong()) {
            throw invalid();
        }
        return new CreatePurchase(text(json, "transactionReference"), text(json, "merchantId"),
                parseDate(text(json, "businessDate")), amount.longValue(), text(json, "currency"));
    }

    public static LocalDate date(JsonNode json) {
        fields(json, Set.of("businessDate"));
        return parseDate(text(json, "businessDate"));
    }

    public static LocalDate parseDate(String date) {
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw invalid();
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException failure) {
            throw invalid();
        }
    }

    private static void fields(JsonNode json, Set<String> expected) {
        if (!json.isObject() || !Set.copyOf(json.propertyNames()).equals(expected)) {
            throw invalid();
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (!value.isString()) {
            throw invalid();
        }
        return value.stringValue();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request");
    }
}
