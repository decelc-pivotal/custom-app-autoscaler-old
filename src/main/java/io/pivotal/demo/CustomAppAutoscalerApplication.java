package io.pivotal.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CustomAppAutoscalerApplication {

	public static void main(String[] args) {
		SpringApplication.run(CustomAppAutoscalerApplication.class, args);
	}
}
