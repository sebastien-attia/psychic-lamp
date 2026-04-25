package ch.owt.boatapp.bff;

import org.springframework.boot.SpringApplication;

public class TestBffApplication {

	public static void main(String[] args) {
		SpringApplication.from(BffApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
