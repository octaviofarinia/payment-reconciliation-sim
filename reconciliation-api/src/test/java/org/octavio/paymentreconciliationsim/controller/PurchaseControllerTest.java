package org.octavio.paymentreconciliationsim.controller;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.model.*;
import org.octavio.paymentreconciliationsim.service.PurchaseService;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
class PurchaseControllerTest {
 @Test void exposesPurchaseCreationAndReplayContract() throws Exception {
  var service=mock(PurchaseService.class);
  var purchase=new Purchase("MATCH-001","MERCHANT-001","2026-10-01",10000,"ARS",Instant.parse("2026-10-02T15:00:00Z"));
  when(service.create(any())).thenReturn(new CreationResult<>(purchase,true),new CreationResult<>(purchase,false));
  var controller=new PurchaseController(service);
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  var request=post("/api/v1/transactions").contentType("application/json").content("{\"transactionReference\":\"MATCH-001\",\"merchantId\":\"MERCHANT-001\",\"businessDate\":\"2026-10-01\",\"amountCentavos\":10000,\"currency\":\"ARS\"}");
  String body=mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
  assertTrue(body.contains("\"receivedAt\":\"2026-10-02T15:00:00Z\""));
  assertEquals(body,mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  verify(service,times(2)).create(new CreatePurchase("MATCH-001","MERCHANT-001",LocalDate.of(2026,10,1),10000,"ARS"));
 }
}
