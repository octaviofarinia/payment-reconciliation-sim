package org.octavio.paymentreconciliationsim.model;

import java.time.Instant;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("purchases")
public record Purchase(@MongoId(FieldType.STRING) String transactionReference, String merchantId, String businessDate,
                       long amountCentavos, String currency, Instant receivedAt) {}
