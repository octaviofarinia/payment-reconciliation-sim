package org.octavio.paymentreconciliationsim.run;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
class RunRepositoryTest {
 final MongoTemplate mongo=mock(MongoTemplate.class);final RunRepository repository=new RunRepository(mongo);
 static final ObjectIdentity OBJECT=RunServiceTest.OBJECT;static final Instant NOW=RunServiceTest.NOW;static final String ID=RunServiceTest.ID.toString();
 @Test void identityQueriesHaveFixedSourceRulesAndDateSort(){
  var run=RunServiceTest.run(Status.AWAITING_UPLOAD,null,null);
  when(mongo.insert(run)).thenReturn(run);assertEquals(run,repository.insert(run));
  when(mongo.findById(ID,ReconciliationRun.class)).thenReturn(run);assertEquals(run,repository.find(ID));
  when(mongo.findOne(any(),eq(ReconciliationRun.class))).thenAnswer(inv->{assertEquals(new Document("source","SIMULATED").append("businessDate","2026-10-01").append("sha256",RunServiceTest.HASH).append("rulesVersion","v1"),((Query)inv.getArgument(0)).getQueryObject());return run;});
  assertEquals(run,repository.logical("2026-10-01",RunServiceTest.HASH));
  when(mongo.find(any(),eq(ReconciliationRun.class))).thenAnswer(inv->{var q=(Query)inv.getArgument(0);assertEquals(new Document("businessDate","2026-10-01"),q.getQueryObject());assertEquals(new Document("createdAt",-1).append("_id",1),q.getSortObject());return List.of(run);});
  assertEquals(List.of(run),repository.list("2026-10-01"));
 }
 @Test void objectBindingPublicationAndFailureUseAtomicPredicatesAndWholeUpdates(){
  var run=RunServiceTest.run(Status.COMPLETED,OBJECT,RunServiceTest.report(OBJECT));
  List<Query> queries=new ArrayList<>();List<Update> updates=new ArrayList<>();
  when(mongo.findAndModify(any(Query.class),any(Update.class),any(FindAndModifyOptions.class),eq(ReconciliationRun.class))).thenAnswer(inv->{queries.add(inv.getArgument(0));updates.add(inv.getArgument(1));assertTrue(((FindAndModifyOptions)inv.getArgument(2)).isReturnNew());return run;});
  assertEquals(run,repository.processing(ID,OBJECT,NOW));assertEquals(run,repository.publish(ID,OBJECT,run.report(),NOW));
  var failure=new FailureSubmission("TRANSPORT",true,"attempt");assertEquals(run,repository.fail(ID,failure,NOW));
  var processing=queries.get(0).getQueryObject();assertEquals(ID,processing.get("_id"));assertEquals(new Document("$ne",Status.COMPLETED),processing.get("status"));
  assertEquals(List.of(new Document("objectIdentity",null),new Document("objectIdentity",OBJECT)),processing.get("$or"));
  assertEquals(new Document("_id",ID).append("status",new Document("$ne",Status.COMPLETED)).append("objectIdentity",OBJECT),queries.get(1).getQueryObject());
  assertEquals(new Document("$set",new Document("status",Status.PROCESSING).append("objectIdentity",OBJECT).append("error",null).append("updatedAt",NOW)),updates.get(0).getUpdateObject());
  assertEquals(new Document("$set",new Document("status",Status.COMPLETED).append("report",run.report()).append("error",null).append("updatedAt",NOW)),updates.get(1).getUpdateObject());
  assertEquals(new Document("_id",ID).append("status",new Document("$ne",Status.COMPLETED)),queries.get(2).getQueryObject());
  assertEquals(new Document("$set",new Document("status",Status.FAILED).append("error",failure).append("updatedAt",NOW)),updates.get(2).getUpdateObject());
 }
 @Test void serializedBsonSizeIncludesActualPayloadAndRejectsAboveEightMiB(){
  var converter=mock(MongoConverter.class);when(mongo.getConverter()).thenReturn(converter);
  doAnswer(inv->{((Document)inv.getArgument(1)).put("payload","X".repeat(8388589));return null;}).when(converter).write(any(),any());
  assertDoesNotThrow(()->repository.checkSize(RunServiceTest.run(Status.PROCESSING,OBJECT,null)));
  doAnswer(inv->{((Document)inv.getArgument(1)).put("payload","X".repeat(8388590));return null;}).when(converter).write(any(),any());
  assertEquals(413,assertThrows(ResponseStatusException.class,()->repository.checkSize(RunServiceTest.run(Status.COMPLETED,OBJECT,RunServiceTest.report(OBJECT)))).getStatusCode().value());
 }
 @Test void resultsUseSortedAggregationFacetsAndLongOffsetsWithoutOverflow(){
  for(var outcome:Arrays.asList(null,Outcome.DUPLICATE)){
   when(mongo.aggregate(any(Aggregation.class),eq("reconciliation_runs"),eq(Document.class))).thenAnswer(inv->{
    var pipeline=((Aggregation)inv.getArgument(0)).toPipeline(Aggregation.DEFAULT_CONTEXT);
    assertEquals(new Document("$match",new Document("_id",ID)),pipeline.get(0));
    assertEquals(new Document("$unwind","$report.results"),pipeline.get(1));
    int next=2;if(outcome!=null)assertEquals(new Document("$match",new Document("report.results.outcome",outcome.name())),pipeline.get(next++));
    assertEquals(new Document("$sort",new Document("report.results.reference",1)),pipeline.get(next++));
    var facets=pipeline.get(next).get("$facet",Document.class);
    assertEquals(List.of(new Document("$skip",214748364700L),new Document("$limit",100),new Document("$replaceRoot",new Document("newRoot","$report.results"))),facets.get("results"));
    assertEquals(List.of(new Document("$count","count")),facets.get("total"));
    return new AggregationResults<>(List.of(new Document("results",List.of()).append("total",List.of(new Document("count",4)))),new Document());
   });
   assertEquals(new ResultPage(Integer.MAX_VALUE,100,4,List.of()),repository.results(ID,outcome,Integer.MAX_VALUE,100));
  }
 }
 @Test void resultDocumentsAreConvertedAndEmptyCountsReturnZero(){
  var converter=mock(MongoConverter.class);when(mongo.getConverter()).thenReturn(converter);
  var doc=new Document("reference","R");var result=new Result("R",Outcome.MISSING_INTERNALLY,null,null,List.of(new SettlementRow(1,"R",1)));
  when(converter.read(Result.class,doc)).thenReturn(result);
  when(mongo.aggregate(any(Aggregation.class),eq("reconciliation_runs"),eq(Document.class))).thenReturn(new AggregationResults<>(List.of(new Document("results",List.of(doc)).append("total",List.of(new Document("count",1)))),new Document()),new AggregationResults<>(List.of(new Document("results",List.of()).append("total",List.of())),new Document()));
  assertEquals(new ResultPage(0,50,1,List.of(result)),repository.results(ID,null,0,50));
  assertEquals(new ResultPage(0,50,0,List.of()),repository.results(ID,null,0,50));
 }
}
