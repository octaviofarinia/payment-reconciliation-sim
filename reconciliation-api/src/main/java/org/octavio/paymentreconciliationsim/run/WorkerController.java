package org.octavio.paymentreconciliationsim.run;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
@RestController
@RequestMapping("/internal/v1/reconciliation-runs")
public class WorkerController {
 private final RunService service;
 public WorkerController(RunService service){this.service=service;}
 @GetMapping("/{runId}") public RunMetadata metadata(@PathVariable UUID runId){return service.metadata(runId);}
 @GetMapping("/{runId}/input") public RunInput input(@PathVariable UUID runId){return service.input(runId);}
 @PutMapping(value="/{runId}/processing",consumes="application/json")
 public void processing(@PathVariable UUID runId,@RequestBody JsonNode body){service.markProcessing(runId,RunRequestDecoder.object(body));}
 @PutMapping(value="/{runId}/results",consumes="application/json")
 public PublicationResult results(@PathVariable UUID runId,@RequestBody JsonNode body){return service.publish(runId,RunRequestDecoder.report(body));}
 @PostMapping(value="/{runId}/failure",consumes="application/json")
 public void failure(@PathVariable UUID runId,@RequestBody JsonNode body){service.fail(runId,RunRequestDecoder.failure(body));}
}
