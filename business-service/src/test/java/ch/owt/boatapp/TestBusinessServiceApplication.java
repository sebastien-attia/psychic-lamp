package ch.owt.boatapp;

import org.springframework.boot.SpringApplication;

public class TestBusinessServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(BusinessServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
