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
 @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Unchanged replay"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="Created")})
 @PostMapping(consumes="application/json")
 public ResponseEntity<RegistrationResult> register(@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(ref="#/components/schemas/RegisterRun"))) @RequestBody JsonNode body){
  var registered=service.register(RunRequestDecoder.registration(body));return ResponseEntity.status(registered.created()?201:200).body(registered);
 }
 @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Completed run unchanged"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202",description="Worker invocation accepted")})
 @PostMapping("/{runId}/reprocess")
 public ResponseEntity<RunMetadata> reprocess(@PathVariable UUID runId){
  var result=recovery.reprocess(runId);return ResponseEntity.status(result.accepted()?202:200).body(result.run());
 }
 @GetMapping public List<RunMetadata> list(@RequestParam String businessDate){return service.list(PurchaseRequestDecoder.parseDate(businessDate));}
 @GetMapping("/{runId}") public RunMetadata metadata(@PathVariable UUID runId){return service.metadata(runId);}
 @GetMapping("/{runId}/results")
 public ResultPage results(@PathVariable UUID runId,@RequestParam(required=false) Outcome outcome,@io.swagger.v3.oas.annotations.Parameter(description="Zero-based page index",schema=@io.swagger.v3.oas.annotations.media.Schema(minimum="0",defaultValue="0")) @RequestParam(defaultValue="0") int page,@io.swagger.v3.oas.annotations.Parameter(description="Requested page size; capped at 100",schema=@io.swagger.v3.oas.annotations.media.Schema(minimum="1",defaultValue="50")) @RequestParam(defaultValue="50") int size){return service.results(runId,outcome,page,size);}
}
