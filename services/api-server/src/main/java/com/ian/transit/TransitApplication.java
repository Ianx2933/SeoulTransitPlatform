package com.ian.transit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the SeoulTransitPlatform Spring API server.
 *
 * Domain modules such as curated OD, congestion, correction, and prediction are discovered
 * through Spring Boot component scanning.
 */
@SpringBootApplication
public class TransitApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransitApplication.class, args);
	}

}
