package org.octavio.paymentreconciliationsim.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.octavio.paymentreconciliationsim.businessdate.BusinessDateService;
import org.octavio.paymentreconciliationsim.model.CreatePurchase;
import org.octavio.paymentreconciliationsim.model.CreationResult;
import org.octavio.paymentreconciliationsim.model.Purchase;
import org.octavio.paymentreconciliationsim.repository.PurchaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PurchaseService {
    private final PurchaseRepository purchases;
    private final BusinessDateService dates;
    private final TransactionTemplate transactions;
    private final TransactionRetry retry;
    private final Clock clock;

    public PurchaseService(PurchaseRepository purchases, BusinessDateService dates, TransactionTemplate transactions,
                           TransactionRetry retry, Clock clock) {
        this.purchases = purchases;
        this.dates = dates;
        this.transactions = transactions;
        this.retry = retry;
        this.clock = clock;
    }

    public CreationResult<Purchase> create(CreatePurchase request) {
        return retry.execute(() -> transactions.execute(status -> {
            var existing = purchases.findByReference(request.transactionReference());
            if (existing != null) {
                if (!request.matches(existing)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Reference already has different business fields");
                }
                return new CreationResult<>(existing, false);
            }
            dates.reserve(request.businessDate());
            var purchase = new Purchase(request.transactionReference(), request.merchantId(),
                    request.businessDate().toString(), request.amountCentavos(), request.currency(),
                    clock.instant().truncatedTo(ChronoUnit.MILLIS));
            return new CreationResult<>(purchases.insert(purchase), true);
        }));
    }
}
