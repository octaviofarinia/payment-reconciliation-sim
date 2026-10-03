package org.octavio.paymentreconciliationsim.repository;

import java.util.List;
import org.octavio.paymentreconciliationsim.model.Purchase;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Repository
public class PurchaseRepository {
    private final MongoTemplate mongo;

    public PurchaseRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Purchase findByReference(String reference) {
        return mongo.findById(reference, Purchase.class);
    }

    public Purchase insert(Purchase purchase) {
        return mongo.insert(purchase);
    }

    public List<Purchase> findByBusinessDate(String date) {
        return mongo.find(Query.query(where("businessDate").is(date)).with(Sort.by("_id")), Purchase.class);
    }
}
