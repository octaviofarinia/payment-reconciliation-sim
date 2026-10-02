package org.octavio.paymentreconciliationsim.acceptance;

import org.springframework.boot.SpringApplication;
import org.octavio.paymentreconciliationsim.PaymentReconciliationSimApplication;

public class TestPaymentReconciliationSimApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentReconciliationSimApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
