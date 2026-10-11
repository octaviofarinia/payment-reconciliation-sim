package org.octavio.paymentreconciliationsim.controller;

import org.octavio.paymentreconciliationsim.model.Purchase;
import org.octavio.paymentreconciliationsim.service.PurchaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/transactions")
public class PurchaseController {
    private final PurchaseService purchases;

    public PurchaseController(PurchaseService purchases) {
        this.purchases = purchases;
    }

    @io.swagger.v3.oas.annotations.responses.ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Unchanged replay"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="Created")})
    @PostMapping(consumes="application/json")
    public ResponseEntity<Purchase> create(@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(ref="#/components/schemas/CreatePurchase"))) @RequestBody JsonNode body) {
        var result = purchases.create(PurchaseRequestDecoder.purchase(body));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.value());
    }
}
