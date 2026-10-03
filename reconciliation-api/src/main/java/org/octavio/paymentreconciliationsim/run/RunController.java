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
 private final RecoveryService recovery;
 public RunController(RunService service,RecoveryService recovery){this.service=service;this.recovery=recovery;}
 @PostMapping(consumes="application/json")
 public ResponseEntity<RegistrationResult> register(@RequestBody JsonNode body){
  var registered=service.register(RunRequestDecoder.registration(body));return ResponseEntity.status(registered.created()?201:200).body(registered);
 }
 @ExceptionHandler(RecoveryService.RecoveryConflict.class)
 public ResponseEntity<org.springframework.http.ProblemDetail> recoveryConflict(RecoveryService.RecoveryConflict conflict){
  return ResponseEntity.status(conflict.getStatusCode()).body(conflict.getBody());
 }
 @PostMapping("/{runId}/reprocess")
 public ResponseEntity<RunMetadata> reprocess(@PathVariable UUID runId){
  var result=recovery.reprocess(runId);return ResponseEntity.status(result.accepted()?202:200).body(result.run());
 }
 @GetMapping public List<RunMetadata> list(@RequestParam String businessDate){return service.list(PurchaseRequestDecoder.parseDate(businessDate));}
 @GetMapping("/{runId}") public RunMetadata metadata(@PathVariable UUID runId){return service.metadata(runId);}
 @GetMapping("/{runId}/results")
 public ResultPage results(@PathVariable UUID runId,@RequestParam(required=false) Outcome outcome,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return service.results(runId,outcome,page,size);}
}
