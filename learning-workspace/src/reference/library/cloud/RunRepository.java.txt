package org.octavio.paymentreconciliationsim.run;
import java.time.Instant;
import java.util.*;
import org.bson.*;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
@Repository
public class RunRepository {
 private final MongoTemplate mongo;
 public RunRepository(MongoTemplate mongo){this.mongo=mongo;}
 public ReconciliationRun insert(ReconciliationRun run){return mongo.insert(run);}
 public ReconciliationRun find(String id){return mongo.findById(id,ReconciliationRun.class);}
 public ReconciliationRun logical(String date,String sha256){
  return mongo.findOne(Query.query(where("source").is("SIMULATED").and("businessDate").is(date).and("sha256").is(sha256).and("rulesVersion").is("v1")),ReconciliationRun.class);
 }
 public List<ReconciliationRun> list(String date){
  return mongo.find(Query.query(where("businessDate").is(date)).with(Sort.by(Sort.Order.desc("createdAt"),Sort.Order.asc("_id"))),ReconciliationRun.class);
 }
 public ReconciliationRun processing(String id,ObjectIdentity object,Instant now){
  var predicate=where("_id").is(id).and("status").ne(Status.COMPLETED).orOperator(where("objectIdentity").is(null),where("objectIdentity").is(object));
  return update(predicate,new Update().set("status",Status.PROCESSING).set("objectIdentity",object).set("error",null).set("updatedAt",now));
 }
 public ReconciliationRun publish(String id,ObjectIdentity object,ReportSubmission report,Instant now){
  return update(where("_id").is(id).and("status").ne(Status.COMPLETED).and("objectIdentity").is(object),
   new Update().set("status",Status.COMPLETED).set("report",report).set("error",null).set("updatedAt",now));
 }
 public ReconciliationRun fail(String id,FailureSubmission error,Instant now){
  return update(where("_id").is(id).and("status").ne(Status.COMPLETED),new Update().set("status",Status.FAILED).set("error",error).set("updatedAt",now));
 }
 private ReconciliationRun update(Criteria predicate,Update update){
  return mongo.findAndModify(Query.query(predicate),update,FindAndModifyOptions.options().returnNew(true),ReconciliationRun.class);
 }
 public void checkSize(ReconciliationRun run){
  var document=new Document();mongo.getConverter().write(run,document);
  try(var buffer=new BasicOutputBuffer();var writer=new BsonBinaryWriter(buffer)){
   new DocumentCodec().encode(writer,document,EncoderContext.builder().build());
   if(buffer.getSize()>8388608)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Serialized run exceeds 8 MiB");
  }
 }
 public ResultPage results(String id,Outcome outcome,int page,int size){
  var pipeline=new ArrayList<AggregationOperation>();
  pipeline.add(context->new Document("$match",new Document("_id",id)));
  pipeline.add(context->new Document("$unwind","$report.results"));
  if(outcome!=null)pipeline.add(context->new Document("$match",new Document("report.results.outcome",outcome.name())));
  pipeline.add(context->new Document("$sort",new Document("report.results.reference",1)));
  pipeline.add(context->new Document("$facet",new Document("results",List.of(new Document("$skip",(long)page*size),new Document("$limit",size),new Document("$replaceRoot",new Document("newRoot","$report.results")))).append("total",List.of(new Document("$count","count")))));
  var result=mongo.aggregate(Aggregation.newAggregation(pipeline),"reconciliation_runs",Document.class).getUniqueMappedResult();
  var totals=result.getList("total",Document.class);
  long count=totals.isEmpty()?0:((Number)totals.getFirst().get("count")).longValue();
  var values=result.getList("results",Document.class).stream().map(doc->mongo.getConverter().read(Result.class,doc)).toList();
  return new ResultPage(page,size,count,values);
 }
}
