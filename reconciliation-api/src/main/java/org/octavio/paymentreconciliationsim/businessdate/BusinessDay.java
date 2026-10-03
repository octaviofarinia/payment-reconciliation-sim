package org.octavio.paymentreconciliationsim.businessdate;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("business_days")
public record BusinessDay(@Id String businessDate, State state, long purchaseCount, long revision,
                          @Field(write = Field.Write.ALWAYS) Instant closedAt) {
    public enum State { OPEN, CLOSED }
}
