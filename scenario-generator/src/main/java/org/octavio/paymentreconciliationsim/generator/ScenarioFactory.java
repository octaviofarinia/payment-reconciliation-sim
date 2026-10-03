package org.octavio.paymentreconciliationsim.generator;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
public final class ScenarioFactory {
 private final java.util.function.Supplier<java.io.InputStream> fixture;
 public ScenarioFactory(){this(()->ScenarioFactory.class.getResourceAsStream("/scenarios/canonical-expected.json"));}
 ScenarioFactory(java.util.function.Supplier<java.io.InputStream> fixture){this.fixture=fixture;}
 public Scenario generate(long seed,LocalDate date,ScenarioKind kind){
  if(kind==ScenarioKind.CANONICAL){
   return new Scenario(date,List.of(purchase("MATCH-001",date,10000),purchase("MISS-001",date,20000),purchase("AMOUNT-001",date,40000),purchase("DUP-001",date,50000)),
    List.of(new SettlementRow(1,"MATCH-001",10000),new SettlementRow(2,"EXT-001",30000),new SettlementRow(3,"AMOUNT-001",45000),new SettlementRow(4,"DUP-001",50000),new SettlementRow(5,"DUP-001",50000)));
  }
  var random=new SplittableRandom(seed);
  var purchases=new ArrayList<Purchase>();var rows=new ArrayList<SettlementRow>();
  String namespace="S"+Long.toUnsignedString(seed,16)+"_D"+date+"_";
  for(int i=1;i<=100;i++){
   var p=purchase(namespace+String.format(Locale.ROOT,"%03d",i),date,random.nextLong(1,1_000_001));
   purchases.add(p);rows.add(new SettlementRow(i,p.transactionReference(),p.amountCentavos()));
  }
  return new Scenario(date,List.copyOf(purchases),List.copyOf(rows));
 }
 private Purchase purchase(String reference,LocalDate date,long amount){return new Purchase(reference,"MERCHANT-001",date,amount,"ARS");}
 public ExpectedReport expected(Scenario scenario,ScenarioKind kind){
  if(kind==ScenarioKind.CANONICAL){
   try(var in=fixture.get()){
    return JsonMapper.builder().build().readValue(in,ExpectedReport.class);
   }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
  }
  var results=new ArrayList<Result>();
  for(int i=0;i<scenario.purchases().size();i++){
   var p=scenario.purchases().get(i);
   results.add(new Result(p.transactionReference(),"MATCHED",p.amountCentavos(),p.merchantId(),List.of(new SettlementRow(i+1,p.transactionReference(),p.amountCentavos()))));
  }
  int count=results.size();
  return new ExpectedReport(new Summary(count,count,count,count,Map.of("MATCHED",count,"MISSING_IN_SETTLEMENT",0,"MISSING_INTERNALLY",0,"AMOUNT_MISMATCH",0,"DUPLICATE",0)),List.copyOf(results));
 }
}
