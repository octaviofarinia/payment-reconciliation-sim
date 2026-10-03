package org.octavio.paymentreconciliationsim.run;
import java.time.LocalDate;
import java.util.*;
import org.octavio.paymentreconciliationsim.controller.PurchaseRequestDecoder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static org.octavio.paymentreconciliationsim.run.RunContracts.*;
@RestController
@RequestMapping("/api/v1/reconciliation-runs")
public class RunController {
 private final RunService service;
 public RunController(RunService service){this.service=service;}
 @PostMapping(consumes="application/json")
 public ResponseEntity<RegistrationResult> register(@RequestBody JsonNode body){
  var registered=service.register(RunRequestDecoder.registration(body));return ResponseEntity.status(registered.created()?201:200).body(registered);
 }
 @GetMapping public List<RunMetadata> list(@RequestParam String businessDate){return service.list(PurchaseRequestDecoder.parseDate(businessDate));}
 @GetMapping("/{runId}") public RunMetadata metadata(@PathVariable UUID runId){return service.metadata(runId);}
 @GetMapping("/{runId}/results")
 public ResultPage results(@PathVariable UUID runId,@RequestParam(required=false) Outcome outcome,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.results(runId,outcome,page,size);}
}
