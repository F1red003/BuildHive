package com.buildhive.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for BuildHive API Server and Scheduler Daemon.
 */
@SpringBootApplication
@EnableScheduling
public class BuildHiveServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildHiveServerApplication.class, args);
    }
}
