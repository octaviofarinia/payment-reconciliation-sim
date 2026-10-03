package org.octavio.paymentreconciliationsim.run;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RunSchemaInitializerTest {
 @Test void createsOrUpdatesStrictRunSchemaAndInstallsImmutableIdentityAndListingIndexes(){
  for(boolean exists:List.of(false,true)){
   var mongo=mock(MongoTemplate.class);when(mongo.collectionExists("reconciliation_runs")).thenReturn(exists);
   var commands=new ArrayList<Document>();when(mongo.executeCommand(any(Document.class))).thenAnswer(inv->{commands.add(inv.getArgument(0));return new Document("ok",1);});
   new RunSchemaInitializer(mongo).run(new DefaultApplicationArguments());
   assertEquals(2,commands.size());assertEquals("reconciliation_runs",commands.getFirst().getString(exists?"collMod":"create"));
   assertEquals("strict",commands.getFirst().getString("validationLevel"));assertEquals("error",commands.getFirst().getString("validationAction"));
   var schema=commands.getFirst().get("validator",Document.class).getList("$and",Document.class).getFirst().get("$jsonSchema",Document.class);
   assertEquals(false,schema.getBoolean("additionalProperties"));
   var properties=schema.get("properties",Document.class);
   assertEquals(new Document("bsonType","long").append("minimum",1).append("maximum",2097152),properties.get("byteLength"));
   assertEquals(List.of("AWAITING_UPLOAD","PROCESSING","COMPLETED","FAILED"),properties.get("status",Document.class).get("enum"));
   var indexes=commands.get(1).getList("indexes",Document.class);
   assertEquals(new Document("source",1).append("businessDate",1).append("sha256",1).append("rulesVersion",1),indexes.getFirst().get("key"));
   assertTrue(indexes.getFirst().getBoolean("unique"));
   assertEquals(new Document("businessDate",1).append("createdAt",-1).append("_id",1),indexes.get(1).get("key"));
  }
 }
}
