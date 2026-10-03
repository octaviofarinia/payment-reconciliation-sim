package org.octavio.paymentreconciliationsim.businessdate;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.octavio.paymentreconciliationsim.model.CreationResult;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BusinessDateControllerTest {
 @Test void creationAndReplayReturn201And200AndCloseReturnsStoredBoundary(){
  var dates=mock(BusinessDateService.class);var date=LocalDate.of(2026,10,1);
  var open=new BusinessDay("2026-10-01",BusinessDay.State.OPEN,0,0,null);
  var closed=new BusinessDay("2026-10-01",BusinessDay.State.CLOSED,0,1,Instant.EPOCH);
  when(dates.create(date)).thenReturn(new CreationResult<>(open,true),new CreationResult<>(closed,false));
  when(dates.close(date)).thenReturn(closed);
  var controller=new BusinessDateController(dates);var json=JsonMapper.builder().build().readTree("{\"businessDate\":\"2026-10-01\"}");
  var created=controller.create(json);assertEquals(201,created.getStatusCode().value());assertEquals(open,created.getBody());
  var replay=controller.create(json);assertEquals(200,replay.getStatusCode().value());assertEquals(closed,replay.getBody());
  assertEquals(closed,controller.close("2026-10-01"));
 }
}
