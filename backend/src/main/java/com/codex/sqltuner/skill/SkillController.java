package com.codex.sqltuner.skill;

import com.codex.sqltuner.auth.CurrentUser;
import com.codex.sqltuner.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/admin/skills")
public class SkillController {
    private static final Logger log = LoggerFactory.getLogger(SkillController.class);
    private final SkillRepository repository;

    public SkillController(SkillRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<List<SkillVersion>> list(HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("listSkills param 入参: admin: true");
        return ApiResponse.ok(repository.list());
    }

    @PostMapping
    public ApiResponse<SkillVersion> save(@Valid @RequestBody SaveSkillRequest request, HttpSession session) {
        CurrentUser.requireAdmin(session);
        log.info("saveSkill param 入参: skillName: {}, contentLength: {}", request.getName(), request.getContent().length());
        return ApiResponse.ok(repository.save(request.getName(), request.getContent()));
    }
}
