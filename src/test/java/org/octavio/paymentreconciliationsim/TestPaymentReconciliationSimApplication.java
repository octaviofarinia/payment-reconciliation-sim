package org.octavio.paymentreconciliationsim;

import org.springframework.boot.SpringApplication;

public class TestPaymentReconciliationSimApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentReconciliationSimApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
