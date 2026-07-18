package com.codex.sqltuner.config;

import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;

@Controller
public class SpaController {
    @GetMapping({"/login", "/chat", "/admin/skills", "/admin/model", "/admin/rules"})
    public String app() {
        return "forward:/index.html";
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Void> legacyTaskRoute() {
        // A relative Location preserves the HTTPS origin selected by the reverse proxy.
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/chat")).build();
    }
}
