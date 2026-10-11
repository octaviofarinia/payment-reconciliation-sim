package org.octavio.paymentreconciliationsim.businessdate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.octavio.paymentreconciliationsim.model.CreationResult;
import org.octavio.paymentreconciliationsim.model.Purchase;
import org.octavio.paymentreconciliationsim.repository.PurchaseRepository;
import org.octavio.paymentreconciliationsim.service.TransactionRetry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
public class BusinessDateService {
    public static final int PURCHASE_LIMIT = 1000;

    private final MongoTemplate mongo;
    private final PurchaseRepository purchases;
    private final Clock clock;
    private final TransactionRetry retry;

    public BusinessDateService(MongoTemplate mongo, PurchaseRepository purchases, Clock clock, TransactionRetry retry) {
        this.mongo = mongo;
        this.purchases = purchases;
        this.clock = clock;
        this.retry = retry;
    }

    public CreationResult<BusinessDay> create(LocalDate date) {
        var day = new BusinessDay(date.toString(), BusinessDay.State.OPEN, 0, 0, null);
        try {
            return new CreationResult<>(mongo.insert(day), true);
        } catch (DuplicateKeyException duplicate) {
            return new CreationResult<>(required(date), false);
        }
    }

    public BusinessDay close(LocalDate date) {
        return retry.execute(() -> {
            var closed = mongo.findAndModify(
                    Query.query(where("_id").is(date.toString()).and("state").is(BusinessDay.State.OPEN)),
                    new Update().set("state", BusinessDay.State.CLOSED)
                            .set("closedAt", clock.instant().truncatedTo(ChronoUnit.MILLIS)).inc("revision", 1L),
                    FindAndModifyOptions.options().returnNew(true), BusinessDay.class);
            if (closed != null) {
                return closed;
            }
            return required(date);
        });
    }

    /** Called inside the purchase transaction. This guard write conflicts with close. */
    public void reserve(LocalDate date) {
        var reserved = mongo.findAndModify(
                Query.query(where("_id").is(date.toString()).and("state").is(BusinessDay.State.OPEN)
                        .and("purchaseCount").lt(PURCHASE_LIMIT)),
                new Update().inc("purchaseCount", 1L).inc("revision", 1L),
                FindAndModifyOptions.options().returnNew(true), BusinessDay.class);
        if (reserved == null) {
            required(date);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Date is closed or purchase limit reached");
        }
    }

    public List<Purchase> closedInputs(LocalDate date) {
        if (required(date).state() != BusinessDay.State.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Date must be CLOSED");
        }
        return List.copyOf(purchases.findByBusinessDate(date.toString()));
    }

    private BusinessDay required(LocalDate date) {
        var day = mongo.findById(date.toString(), BusinessDay.class);
        if (day == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown business date");
        }
        return day;
    }
}
