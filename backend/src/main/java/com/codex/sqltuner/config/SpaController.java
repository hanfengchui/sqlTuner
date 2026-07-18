package com.codex.sqltuner.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/login", "/chat", "/admin/skills", "/admin/model", "/admin/rules"})
    public String app() {
        return "forward:/index.html";
    }

    @GetMapping("/tasks/{taskId}")
    public String legacyTaskRoute() {
        return "redirect:/chat";
    }
}
