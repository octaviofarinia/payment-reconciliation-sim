package org.octavio.paymentreconciliationsim.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("purchases")
public record Purchase(@Id String transactionReference, String merchantId, String businessDate,
                       long amountCentavos, String currency, Instant receivedAt) {}
