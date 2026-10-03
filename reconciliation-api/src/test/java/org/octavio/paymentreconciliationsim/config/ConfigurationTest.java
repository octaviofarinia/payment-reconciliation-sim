package org.octavio.paymentreconciliationsim.config;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConfigurationTest {
 @Test void suppliesBuenosAiresClockAndMongoTransactionInfrastructure(){
  assertEquals(ZoneId.of("America/Buenos_Aires"),new ClockConfiguration().applicationClock().getZone());
  var config=new MongoConfiguration();var factory=mock(MongoDatabaseFactory.class);var manager=config.transactionManager(factory);
  assertSame(factory,manager.getDatabaseFactory());assertSame(manager,config.transactionTemplate(manager).getTransactionManager());
 }
 @Test void retryDelayHandlesInterruptionAndRestoresInterruptFlag(){
  var delay=new MongoConfiguration().retryDelay();assertDoesNotThrow(()->delay.pause(1));
  Thread.currentThread().interrupt();
  try {assertEquals(503,assertThrows(ResponseStatusException.class,()->delay.pause(1)).getStatusCode().value());assertTrue(Thread.currentThread().isInterrupted());}
  finally {Thread.interrupted();}
 }
}
