package org.octavio.paymentreconciliationsim.businessdate;

import org.octavio.paymentreconciliationsim.controller.PurchaseRequestDecoder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/business-dates")
public class BusinessDateController {
    private final BusinessDateService dates;

    public BusinessDateController(BusinessDateService dates) {
        this.dates = dates;
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Unchanged replay"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="Created")})
    @PostMapping(consumes="application/json")
    public ResponseEntity<BusinessDay> create(@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(ref="#/components/schemas/CreateBusinessDate"))) @RequestBody JsonNode body) {
        var result = dates.create(PurchaseRequestDecoder.date(body));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.value());
    }

    @PostMapping("/{date}/close")
    public BusinessDay close(@PathVariable String date) {
        return dates.close(PurchaseRequestDecoder.parseDate(date));
    }
}
