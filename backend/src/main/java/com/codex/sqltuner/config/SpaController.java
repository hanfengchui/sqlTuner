package com.codex.sqltuner.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/login", "/chat", "/tasks/{taskId}", "/admin/skills", "/admin/model", "/admin/rules"})
    public String app() {
        return "forward:/index.html";
    }
}
