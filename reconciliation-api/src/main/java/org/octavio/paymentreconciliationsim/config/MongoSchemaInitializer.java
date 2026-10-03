package org.octavio.paymentreconciliationsim.config;
import java.util.List;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
/** Install strict constraints for ingestion. Later tasks add run constraints separately. */
@Component
public class MongoSchemaInitializer implements ApplicationRunner {
 private final MongoTemplate mongo;
 public MongoSchemaInitializer(MongoTemplate mongo){this.mongo=mongo;}
 @Override public void run(ApplicationArguments arguments){
  install("purchases",Document.parse(PURCHASE_VALIDATOR));
  install("business_days",Document.parse(DATE_VALIDATOR));
  mongo.executeCommand(new Document("createIndexes","purchases").append("indexes",List.of(
   new Document("key",new Document("businessDate",1)).append("name","purchases_by_business_date"))));
 }
 private void install(String collection,Document validator){
  mongo.executeCommand(new Document(mongo.collectionExists(collection)?"collMod":"create",collection)
   .append("validator",validator).append("validationLevel","strict").append("validationAction","error"));
 }
 private static final String PURCHASE_VALIDATOR="""
 {
  "$and":[
   {"$jsonSchema":{
    "bsonType":"object","additionalProperties":false,
    "required":["_id","merchantId","businessDate","amountCentavos","currency","receivedAt"],
    "properties":{
     "_id":{"bsonType":"string","pattern":"^[A-Za-z0-9_-]{1,64}$"},
     "merchantId":{"bsonType":"string","minLength":1,"maxLength":64},
     "businessDate":{"bsonType":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
     "amountCentavos":{"bsonType":"long","minimum":1},
     "currency":{"enum":["ARS"]},
     "receivedAt":{"bsonType":"date"},
     "_class":{"bsonType":"string"}
    }
   }},
   {"$expr":{"$eq":[
    {"$dateToString":{"date":{"$dateFromString":{"dateString":"$businessDate","format":"%Y-%m-%d","onError":null,"onNull":null}},"format":"%Y-%m-%d","onNull":null}},
    "$businessDate"
   ]}}
  ]
 }
 """;
 private static final String DATE_VALIDATOR="""
 {
  "$and":[
   {"$jsonSchema":{
    "bsonType":"object","additionalProperties":false,
    "required":["_id","state","purchaseCount","revision","closedAt"],
    "properties":{
     "_id":{"bsonType":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},
     "state":{"enum":["OPEN","CLOSED"]},
     "purchaseCount":{"bsonType":"long","minimum":0,"maximum":1000},
     "revision":{"bsonType":"long","minimum":0},
     "closedAt":{"bsonType":["date","null"]},
     "_class":{"bsonType":"string"}
    }
   }},
   {"$expr":{"$and":[
    {"$eq":[
     {"$dateToString":{"date":{"$dateFromString":{"dateString":"$_id","format":"%Y-%m-%d","onError":null,"onNull":null}},"format":"%Y-%m-%d","onNull":null}},
     "$_id"
    ]},
    {"$or":[
     {"$and":[{"$eq":["$state","OPEN"]},{"$eq":["$closedAt",null]}]},
     {"$and":[{"$eq":["$state","CLOSED"]},{"$ne":["$closedAt",null]}]}
    ]}
   ]}}
  ]
 }
 """;
}
