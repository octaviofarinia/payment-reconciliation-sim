package org.octavio.paymentreconciliationsim.config;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MongoSchemaInitializerTest {
 @Test void createsStrictCollectionsAndIncrementallyUpdatesExistingValidators(){
  for(boolean existing:List.of(false,true)){
   var mongo=mock(MongoTemplate.class);when(mongo.collectionExists(anyString())).thenReturn(existing);
   List<Document> commands=new ArrayList<>();when(mongo.executeCommand(any(Document.class))).thenAnswer(inv->{commands.add(inv.getArgument(0));return new Document("ok",1);});
   new MongoSchemaInitializer(mongo).run(new DefaultApplicationArguments());
   assertEquals(3,commands.size());
   assertEquals("purchases",commands.get(0).getString(existing?"collMod":"create"));
   assertEquals("business_days",commands.get(1).getString(existing?"collMod":"create"));
   for(var command:commands.subList(0,2)){
    assertEquals("strict",command.getString("validationLevel"));assertEquals("error",command.getString("validationAction"));
    var validator=command.get("validator",Document.class);assertEquals(2,validator.getList("$and",Document.class).size());
    var json=validator.getList("$and",Document.class).getFirst().get("$jsonSchema",Document.class);
    assertEquals(false,json.getBoolean("additionalProperties"));assertEquals("object",json.getString("bsonType"));
    assertTrue(validator.toJson().contains("$dateFromString"));assertTrue(validator.toJson().contains("$dateToString"));
   }
   var purchase=commands.get(0).get("validator",Document.class).getList("$and",Document.class).getFirst().get("$jsonSchema",Document.class);
   assertEquals(List.of("_id","merchantId","businessDate","amountCentavos","currency","receivedAt"),purchase.getList("required",String.class));
   var properties=purchase.get("properties",Document.class);
   assertEquals(new Document("bsonType","long").append("minimum",1),properties.get("amountCentavos"));
   assertEquals(new Document("bsonType","string").append("pattern","^[A-Za-z0-9_-]{1,64}$"),properties.get("_id"));
   assertEquals(new Document("bsonType","string").append("minLength",1).append("maxLength",64),properties.get("merchantId"));
   assertEquals(new Document("enum",List.of("ARS")),properties.get("currency"));assertEquals(new Document("bsonType","date"),properties.get("receivedAt"));
   var dates=commands.get(1).get("validator",Document.class).getList("$and",Document.class).getFirst().get("$jsonSchema",Document.class);
   assertEquals(List.of("_id","state","purchaseCount","revision","closedAt"),dates.getList("required",String.class));
   var dateProps=dates.get("properties",Document.class);
   assertEquals(new Document("bsonType","long").append("minimum",0).append("maximum",1000),dateProps.get("purchaseCount"));
   assertEquals(new Document("bsonType","long").append("minimum",0),dateProps.get("revision"));
   assertEquals(new Document("bsonType",List.of("date","null")),dateProps.get("closedAt"));
   assertEquals(new Document("enum",List.of("OPEN","CLOSED")),dateProps.get("state"));
   assertEquals(new Document("createIndexes","purchases").append("indexes",List.of(new Document("key",new Document("businessDate",1)).append("name","purchases_by_business_date"))),commands.get(2));
  }
 }
}
