package org.octavio.paymentreconciliationsim.acceptance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(classes = org.octavio.paymentreconciliationsim.PaymentReconciliationSimApplication.class)
class PaymentReconciliationSimApplicationIT {

	@Test
	void contextLoads() {
	}

}
