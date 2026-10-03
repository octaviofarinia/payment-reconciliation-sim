package org.octavio.paymentreconciliationsim.acceptance.support;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared real-database transaction verification for acceptance callers. */
public final class MongoTransactionProbe {
    private MongoTransactionProbe() {}

    public static void assertCommitAndRollback(MongoClient client, MongoCollection<Document> collection) {
        // Create collection outside transactions: no implicit DDL dependency in the probe.
        collection.insertOne(new Document("_id", "baseline"));
        try (var session = client.startSession()) {
            session.startTransaction();
            collection.insertOne(session, new Document("_id", "committed"));
            session.commitTransaction();
            assertEquals(1, collection.countDocuments(new Document("_id", "committed")));
            session.startTransaction();
            collection.insertOne(session, new Document("_id", "aborted"));
            session.abortTransaction();
            assertEquals(0, collection.countDocuments(new Document("_id", "aborted")));
        }
    }
}
