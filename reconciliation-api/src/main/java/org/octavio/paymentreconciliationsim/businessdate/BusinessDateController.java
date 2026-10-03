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

    @PostMapping
    public ResponseEntity<BusinessDay> create(@RequestBody JsonNode body) {
        var result = dates.create(PurchaseRequestDecoder.date(body));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.value());
    }

    @PostMapping("/{date}/close")
    public BusinessDay close(@PathVariable String date) {
        return dates.close(PurchaseRequestDecoder.parseDate(date));
    }
}
