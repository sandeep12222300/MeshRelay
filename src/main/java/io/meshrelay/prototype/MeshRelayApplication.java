package io.meshrelay.prototype;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point for the MeshRelay offline payment mesh prototype.
 * <p>
 * Run from terminal:
 * ./mvnw spring-boot:run        (Linux/Mac)
 * mvnw.cmd spring-boot:run      (Windows)
 * <p>
 * Then open http://localhost:8080
 */
@SpringBootApplication
@EnableScheduling
public class MeshRelayApplication {

    /**
     * Starts the embedded web server, persistence layer, and simulation services.
     */
    public static void main(String[] args) {
        SpringApplication.run(MeshRelayApplication.class, args);
    }
}
