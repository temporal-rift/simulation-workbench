package io.github.temporalrift.workbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulationWorkbenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimulationWorkbenchApplication.class, args);
    }
}
