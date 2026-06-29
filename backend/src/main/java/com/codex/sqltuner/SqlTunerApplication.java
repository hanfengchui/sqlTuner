package com.codex.sqltuner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class SqlTunerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SqlTunerApplication.class, args);
    }
}
