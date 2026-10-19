package org.octavio.paymentreconciliationsim.model;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

import java.time.Instant;

@Document("purchases")
public record Purchase(@MongoId(FieldType.STRING) String transactionReference, String merchantId, String businessDate,
                       long amountCentavos, String currency, Instant receivedAt) {
}
