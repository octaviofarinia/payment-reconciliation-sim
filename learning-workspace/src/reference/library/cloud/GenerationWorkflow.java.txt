package org.octavio.paymentreconciliationsim.generator;
import java.time.*;
import static org.octavio.paymentreconciliationsim.generator.GeneratorContracts.*;
public final class GenerationWorkflow {
 @FunctionalInterface public interface Delay {void pause(Duration duration)throws InterruptedException;}
 private final SimulatorClient client;private final Clock clock;private final Delay delay;private final Duration window;
 public GenerationWorkflow(SimulatorClient client,Clock clock,Delay delay,Duration window){this.client=client;this.clock=clock;this.delay=delay;this.window=window;}
 public VerifiedRun run(Scenario scenario,ExpectedReport expected){
  client.createDate(scenario.businessDate());
  for(var purchase:scenario.purchases())client.createPurchase(purchase);
  client.closeDate(scenario.businessDate());
  var writer=new SettlementWriter();byte[] csv=writer.write(scenario);
  var registration=client.register(scenario.businessDate(),writer.sha256(csv),csv.length);
  String runId=registration.runId();
  try{
   // A completed identity replay needs no new upload/version.
   if(!registration.status().equals("COMPLETED"))client.upload(registration.uploadInstructions(),csv);
   Instant deadline=clock.instant().plus(window);
   while(clock.instant().isBefore(deadline)){
    var run=client.metadata(runId,Duration.between(clock.instant(),deadline));
    if(!clock.instant().isBefore(deadline))throw new IllegalStateException("Polling deadline exceeded");
    if(run.status().equals("COMPLETED")){
     var actual=new ExpectedReport(run.summary(),client.results(runId));
     new ResultVerifier().verify(expected,actual);return new VerifiedRun(runId,actual);
    }
    if(run.status().equals("FAILED"))throw new IllegalStateException("Run FAILED "+run.error().code());
    Duration remaining=Duration.between(clock.instant(),deadline);
    if(!remaining.isNegative() && !remaining.isZero())delay.pause(remaining.compareTo(Duration.ofSeconds(1))<0?remaining:Duration.ofSeconds(1));
   }
   throw new IllegalStateException("Polling deadline exceeded");
  }catch(InterruptedException interrupted){
   Thread.currentThread().interrupt();throw recovery(runId,"Polling interrupted");
  }catch(RuntimeException failure){throw recovery(runId,failure.getMessage());}
 }
 private IllegalStateException recovery(String runId,String detail){return new IllegalStateException(detail+"; runId="+runId+"; inspect public run status and POST /api/v1/reconciliation-runs/"+runId+"/reprocess to recover");}
}
