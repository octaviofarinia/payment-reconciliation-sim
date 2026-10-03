package org.octavio.paymentreconciliationsim.acceptance;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import com.mongodb.MongoWriteException;
import org.bson.Document;
import org.bson.BsonType;
import org.junit.jupiter.api.*;
import org.octavio.paymentreconciliationsim.acceptance.support.LocalEnvironment;
import static org.junit.jupiter.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PurchaseConcurrencyIT {
 final LocalEnvironment env=new LocalEnvironment();
 HttpClient client;
 @BeforeAll void start(){env.start();client=HttpClient.newHttpClient();}
 @AfterAll void stop(){try{if(client!=null)client.close();}finally{env.close();}}
 @BeforeEach void reset(){env.resetScenario();}
 HttpResponse<String> post(String route,String body)throws Exception{
  return client.send(HttpRequest.newBuilder(env.apiBaseUri().resolve(route)).timeout(Duration.ofSeconds(20))
   .header("Content-Type","application/json").header("Authorization","Bearer "+LocalEnvironment.DEMO_TOKEN)
   .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
 }
 static String purchase(String ref,String date,String amount){
  return "{\"transactionReference\":\""+ref+"\",\"merchantId\":\"MERCHANT-001\",\"businessDate\":\""+date+"\",\"amountCentavos\":"+amount+",\"currency\":\"ARS\"}";
 }
 void createDate(String date)throws Exception{assertEquals(201,post("/api/v1/business-dates","{\"businessDate\":\""+date+"\"}").statusCode());}
 @Test void fractionalAmountIsRejectedAndClosedDateIsImmutable()throws Exception{
  createDate("2026-10-01");
  var fractionalAmountResponse=post("/api/v1/transactions",purchase("Fraction","2026-10-01","1.5"));
  assertEquals(400,fractionalAmountResponse.statusCode());
  var created=post("/api/v1/transactions",purchase("Max","2026-10-01","9223372036854775807"));
  assertEquals(201,created.statusCode());assertEquals(Long.MAX_VALUE,Document.parse(created.body()).getLong("amountCentavos"));
  var close=post("/api/v1/business-dates/2026-10-01/close","{}");assertEquals(200,close.statusCode());
  var boundary=Document.parse(close.body());assertEquals(1,((Number)boundary.get("purchaseCount")).intValue());
  assertEquals("2026-10-02T15:00:00Z",boundary.getString("closedAt"));
  assertEquals(close.body(),post("/api/v1/business-dates/2026-10-01/close","{}").body());
  var closedDateInsertionResponse=post("/api/v1/transactions",purchase("Late","2026-10-01","1"));
  assertEquals(409,closedDateInsertionResponse.statusCode());
  var replay=post("/api/v1/transactions",purchase("Max","2026-10-01","9223372036854775807"));
  assertEquals(200,replay.statusCode());assertEquals(created.body(),replay.body());
  var reCreate=post("/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}");
  assertEquals(200,reCreate.statusCode());assertEquals(close.body(),reCreate.body());
  assertEquals(409,post("/api/v1/transactions",purchase("Max","2026-10-01","2")).statusCode());
  var stored=env.mongoDatabase().getCollection("purchases",org.bson.BsonDocument.class).find().first();
  assertNotNull(stored);assertEquals(BsonType.INT64,stored.get("amountCentavos").getBsonType());assertEquals(Long.MAX_VALUE,stored.getInt64("amountCentavos").getValue());
 }
 @Test void rejectsMalformedNumbersDatesRawCardFieldsAndUnknownResources()throws Exception{
  assertEquals(404,post("/api/v1/transactions",purchase("Missing","2026-10-01","1")).statusCode());
  assertEquals(404,post("/api/v1/business-dates/2026-10-01/close","{}").statusCode());
  createDate("2026-10-01");
  for(String amount:List.of("1.0","1e3","0","-1","9223372036854775808","\"10\"","null","true"))
   assertEquals(400,post("/api/v1/transactions",purchase("Bad","2026-10-01",amount)).statusCode(),amount);
  for(String date:List.of("2026-02-29","2026-13-01","2026-1-01"))
   assertEquals(400,post("/api/v1/business-dates","{\"businessDate\":\""+date+"\"}").statusCode(),date);
  String valid=purchase("Valid","2026-10-01","1");
  for(String bad:List.of(valid.replace("ARS","USD"),valid.replace("Valid","a".repeat(65)),valid.replace("Valid","A B"),
    valid.replace("MERCHANT-001",""),valid.replace("MERCHANT-001","m".repeat(65)),
    valid.replace("}",",\"pan\":\"123456\"}"),valid.replace("}",",\"receivedAt\":\"2026-10-01T00:00:00Z\"}")))
   assertEquals(400,post("/api/v1/transactions",bad).statusCode(),bad);
  assertEquals(0,env.mongoDatabase().getCollection("purchases").countDocuments());
  assertEquals(201,post("/api/v1/transactions",valid).statusCode());
  assertEquals(201,post("/api/v1/transactions",purchase("valid","2026-10-01","1")).statusCode());
  for(String method:List.of("PUT","DELETE"))
   assertEquals(405,client.send(HttpRequest.newBuilder(env.apiBaseUri().resolve("/api/v1/transactions")).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"1.5","1"})
 void duplicateJsonKeysCannotHideFractionalNumbersOrRepeatIdenticalValues(String first)throws Exception {
  createDate("2026-10-01");
  String body=purchase("DuplicateKeys","2026-10-01","1").replace("\"amountCentavos\":1","\"amountCentavos\":"+first+",\"amountCentavos\":1");
  assertEquals(400,post("/api/v1/transactions",body).statusCode());
  assertEquals(0,env.mongoDatabase().getCollection("purchases").countDocuments());
 }
 @Test void legal24HexReferencesKeepStringIdentityAndCaseSensitiveReplay()throws Exception {
  createDate("2026-10-01");
  String lower="0123456789abcdef01234567";
  String upper="0123456789ABCDEF01234567";
  var lowerCreated=post("/api/v1/transactions",purchase(lower,"2026-10-01","10000"));
  assertEquals(201,lowerCreated.statusCode(),"Legal hex references must not become BSON ObjectId");
  assertEquals(lower,Document.parse(lowerCreated.body()).getString("transactionReference"));
  var lowerReplay=post("/api/v1/transactions",purchase(lower,"2026-10-01","10000"));
  assertEquals(200,lowerReplay.statusCode());assertEquals(lowerCreated.body(),lowerReplay.body());
  assertEquals(409,post("/api/v1/transactions",purchase(lower,"2026-10-01","20000")).statusCode());

  var upperCreated=post("/api/v1/transactions",purchase(upper,"2026-10-01","30000"));
  assertEquals(201,upperCreated.statusCode(),"Upper/lower hex references are distinct identifiers");
  assertEquals(upper,Document.parse(upperCreated.body()).getString("transactionReference"));
  var upperReplay=post("/api/v1/transactions",purchase(upper,"2026-10-01","30000"));
  assertEquals(200,upperReplay.statusCode());assertEquals(upperCreated.body(),upperReplay.body());
  assertEquals(409,post("/api/v1/transactions",purchase(upper,"2026-10-01","40000")).statusCode());

  var stored=env.mongoDatabase().getCollection("purchases",org.bson.BsonDocument.class).find().into(new ArrayList<>());
  assertEquals(2,stored.size());
  assertTrue(stored.stream().allMatch(p->p.get("_id").getBsonType()==BsonType.STRING));
  assertEquals(Set.of(lower,upper),stored.stream().map(p->p.getString("_id").getValue()).collect(java.util.stream.Collectors.toSet()));
  assertEquals(Set.of(10000L,30000L),stored.stream().map(p->p.getInt64("amountCentavos").getValue()).collect(java.util.stream.Collectors.toSet()));
  var guard=env.mongoDatabase().getCollection("business_days").find(new Document("_id","2026-10-01")).first();
  assertNotNull(guard);assertEquals(2L,guard.getLong("purchaseCount"));assertEquals(2L,guard.getLong("revision"));

  var close=post("/api/v1/business-dates/2026-10-01/close","{}");
  assertEquals(200,close.statusCode());assertEquals(2,((Number)Document.parse(close.body()).get("purchaseCount")).intValue());
  var closedReplay=post("/api/v1/transactions",purchase(lower,"2026-10-01","10000"));
  assertEquals(200,closedReplay.statusCode());assertEquals(lowerCreated.body(),closedReplay.body());
  assertEquals(2,env.mongoDatabase().getCollection("purchases").countDocuments());
 }
 @Test void emptyDateCanBeClosedAndNeverReopened()throws Exception{
  createDate("2026-10-01");var closed=post("/api/v1/business-dates/2026-10-01/close","{}");
  assertEquals(200,closed.statusCode());assertEquals(0,((Number)Document.parse(closed.body()).get("purchaseCount")).intValue());
  var replay=post("/api/v1/business-dates","{\"businessDate\":\"2026-10-01\"}");assertEquals(200,replay.statusCode());assertEquals(closed.body(),replay.body());
 }
 @Test void closeRacingWithInsertsIncludesEveryCommittedPurchase()throws Exception{
  createDate("2026-10-01");
  Set<String> successful=new HashSet<>();
  for(int i=0;i<20;i++){String ref="Earlier-"+i;assertEquals(201,post("/api/v1/transactions",purchase(ref,"2026-10-01","1")).statusCode());successful.add(ref);}
  var responses=race(13,i->{try{return i==12?post("/api/v1/business-dates/2026-10-01/close","{}"):post("/api/v1/transactions",purchase("Race-"+i,"2026-10-01","1"));}catch(Exception e){throw new CompletionException(e);}});
  // A bounded conflict response can require the caller to retry closing.
  var close=post("/api/v1/business-dates/2026-10-01/close","{}");assertEquals(200,close.statusCode());
  for(int i=0;i<12;i++){int code=responses.get(i).statusCode();assertTrue(code==201||code==409,"Unexpected insert status "+code);if(code==201)successful.add("Race-"+i);}
  assertTrue(Set.of(200,409).contains(responses.get(12).statusCode()));
  var closedInputs=env.mongoDatabase().getCollection("purchases").find(new Document("businessDate","2026-10-01")).into(new ArrayList<>());
  int successfulInsertions=successful.size();assertEquals(successfulInsertions,closedInputs.size());
  assertEquals(successful,closedInputs.stream().map(p->p.getString("_id")).collect(java.util.stream.Collectors.toSet()));
  assertEquals(successfulInsertions,((Number)Document.parse(close.body()).get("purchaseCount")).intValue());
  assertEquals(409,post("/api/v1/transactions",purchase("TooLate","2026-10-01","1")).statusCode());
 }
 @Test void concurrentRequestsNeverAdmitPurchase1001()throws Exception{
  createDate("2026-10-01");
  for(int i=0;i<999;i++)assertEquals(201,post("/api/v1/transactions",purchase("Seed-"+i,"2026-10-01","1")).statusCode());
  var responses=race(12,i->{try{return post("/api/v1/transactions",purchase("Limit-"+i,"2026-10-01","1"));}catch(Exception e){throw new CompletionException(e);}});
  assertEquals(1,responses.stream().filter(r->r.statusCode()==201).count());
  assertTrue(responses.stream().allMatch(r->r.statusCode()==201||r.statusCode()==409));
  assertEquals(1000,env.mongoDatabase().getCollection("purchases").countDocuments());
  var closed=post("/api/v1/business-dates/2026-10-01/close","{}");
  assertEquals(1000,((Number)Document.parse(closed.body()).get("purchaseCount")).intValue());
  assertEquals(409,post("/api/v1/transactions",purchase("Purchase1001","2026-10-01","1")).statusCode());
  assertEquals(200,post("/api/v1/transactions",purchase("Seed-0","2026-10-01","1")).statusCode());
 }
 @Test void concurrentGlobalReferenceRaceHasOnePurchaseAndOneGuardIncrement()throws Exception{
  createDate("2026-10-01");createDate("2026-09-30");
  var responses=race(12,i->{try{return post("/api/v1/transactions",purchase("GloballyUnique",i%2==0?"2026-10-01":"2026-09-30","1"));}catch(Exception e){throw new CompletionException(e);}});
  assertEquals(1,responses.stream().filter(r->r.statusCode()==201).count());
  assertTrue(responses.stream().allMatch(r->Set.of(201,200,409).contains(r.statusCode())));
  assertEquals(1,env.mongoDatabase().getCollection("purchases").countDocuments());
  long guardCount=env.mongoDatabase().getCollection("business_days").find().into(new ArrayList<>()).stream().mapToLong(p->((Number)p.get("purchaseCount")).longValue()).sum();
  assertEquals(1,guardCount,"Losing duplicate inserts must roll back their guard increment");
 }
 @Test void timestampsAndClosedInputsSurviveMongoPrecisionRoundTrip()throws Exception {
  createDate("2026-10-01");
  assertEquals(201,post("/api/v1/transactions",purchase("AbsentFromSettlement","2026-10-01","1")).statusCode());
  var clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-02T15:00:00.123456789Z"),java.time.ZoneOffset.UTC);
  var mongo=new org.springframework.data.mongodb.core.MongoTemplate(env.mongoClient(),env.mongoDatabase().getName());
  var repository=new org.octavio.paymentreconciliationsim.repository.PurchaseRepository(mongo);
  var retry=new org.octavio.paymentreconciliationsim.service.TransactionRetry(i->{});
  var dates=new org.octavio.paymentreconciliationsim.businessdate.BusinessDateService(mongo,repository,clock,retry);
  var transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.data.mongodb.MongoTransactionManager(mongo.getMongoDatabaseFactory()));
  var service=new org.octavio.paymentreconciliationsim.service.PurchaseService(repository,dates,transactions,retry,clock);
  var request=new org.octavio.paymentreconciliationsim.model.CreatePurchase("Precision","M",java.time.LocalDate.of(2026,10,1),Long.MAX_VALUE,"ARS");
  var created=service.create(request);var replay=service.create(request);
  assertTrue(created.created());assertFalse(replay.created());assertEquals(created.value(),replay.value());
  assertEquals(java.time.Instant.parse("2026-10-02T15:00:00.123Z"),created.value().receivedAt());
  var closed=dates.close(request.businessDate());
  assertEquals(java.time.Instant.parse("2026-10-02T15:00:00.123Z"),closed.closedAt());
  assertEquals(closed,dates.close(request.businessDate()));
  var inputs=dates.closedInputs(request.businessDate());
  assertEquals(List.of("AbsentFromSettlement","Precision"),inputs.stream().map(org.octavio.paymentreconciliationsim.model.Purchase::transactionReference).toList());
  assertEquals(created.value(),inputs.get(1));assertEquals(2,closed.purchaseCount());
  assertEquals(created.value(),service.create(request).value());
 }
 @Test void merchantUnicodeCharacterLimitAgreesAtHttpAndMongoBoundaries()throws Exception {
  createDate("2026-10-01");
  var symbol=new String(Character.toChars(0x1F4B3));
  String exact=symbol.repeat(64),over=symbol.repeat(65);
  var accepted=post("/api/v1/transactions",purchase("Merchant64","2026-10-01","1").replace("MERCHANT-001",exact));
  assertEquals(201,accepted.statusCode());assertEquals(exact,Document.parse(accepted.body()).getString("merchantId"));
  assertEquals(400,post("/api/v1/transactions",purchase("Merchant65","2026-10-01","1").replace("MERCHANT-001",over)).statusCode());
  assertEquals(201,post("/api/v1/transactions",purchase("Space","2026-10-01","1").replace("MERCHANT-001"," ")).statusCode());
  var mongo=env.mongoDatabase().getCollection("purchases");
  var stored=mongo.find(new Document("_id","Merchant64")).first();assertNotNull(stored);assertEquals(exact,stored.getString("merchantId"));
  assertDoesNotThrow(()->mongo.insertOne(new Document(stored).append("_id","Mongo64")));
  assertEquals(121,assertThrows(MongoWriteException.class,()->mongo.insertOne(new Document(stored).append("_id","Mongo65").append("merchantId",over))).getError().getCode());
 }
 @Test void mongoSchemaRejectsFloatingPointAmountsAndInvalidGuardStatesAndDateIndexIsUsed()throws Exception{
  createDate("2026-10-01");assertEquals(201,post("/api/v1/transactions",purchase("Indexed","2026-10-01","1")).statusCode());
  var purchases=env.mongoDatabase().getCollection("purchases");
  var valid=new Document("_id","Injected").append("merchantId","M").append("businessDate","2026-10-01").append("amountCentavos",1L).append("currency","ARS").append("receivedAt",new Date());
  for(Document invalid:List.of(new Document(valid).append("amountCentavos",1.0),new Document(valid).append("amountCentavos",1),new Document(valid).append("amountCentavos",0L),
    new Document(valid).append("currency","USD"),new Document(valid).append("cardNumber","1234"),new Document(valid).append("businessDate","2026-02-30"),new Document(valid).append("_id","bad ref"))) {
   assertEquals(121,assertThrows(MongoWriteException.class,()->purchases.insertOne(invalid)).getError().getCode());
  }
  var dates=env.mongoDatabase().getCollection("business_days");var open=dates.find().first();assertNotNull(open);
  for(Document invalid:List.of(new Document(open).append("_id","2026-09-30").append("purchaseCount",1001L),new Document(open).append("_id","2026-09-30").append("state","CLOSED").append("closedAt",null),new Document(open).append("_id","2026-02-30")))
   assertEquals(121,assertThrows(MongoWriteException.class,()->dates.insertOne(invalid)).getError().getCode());
  var explain=env.mongoDatabase().runCommand(new Document("explain",new Document("find","purchases").append("filter",new Document("businessDate","2026-10-01")).append("hint","purchases_by_business_date")));
  assertTrue(explain.toJson().contains("IXSCAN"));assertTrue(explain.toJson().contains("purchases_by_business_date"));
 }
 static <T> List<T> race(int participants,IntFunction<T> operation)throws Exception{
  var barrier=new CyclicBarrier(participants);
  try(var pool=Executors.newFixedThreadPool(participants)){
   var futures=new ArrayList<Future<T>>();
   for(int i=0;i<participants;i++){final int index=i;futures.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return operation.apply(index);}));}
   var results=new ArrayList<T>();for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));return results;
  }
 }
}
