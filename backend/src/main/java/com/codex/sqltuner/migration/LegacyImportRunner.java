package com.codex.sqltuner.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Paths;

@Component
public class LegacyImportRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyImportRunner.class);
    private final LegacyImportProperties properties;
    private final LegacyStateImporter importer;
    private final ConfigurableApplicationContext applicationContext;

    public LegacyImportRunner(LegacyImportProperties properties,
                              LegacyStateImporter importer,
                              ConfigurableApplicationContext applicationContext) {
        this.properties = properties;
        this.importer = importer;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getPath() == null || properties.getPath().trim().isEmpty()) {
            throw new IllegalStateException("启用 legacy 导入时必须配置 app.legacy-import.path");
        }
        LegacyImportResult result = importer.importFile(
                Paths.get(properties.getPath()),
                properties.isDryRun(),
                properties.getAdminPassword(),
                properties.getUserPassword());
        log.info("legacyImport result 结果: sha256: {}, dryRun: {}, noOp: {}, counts: {}",
                result.getSourceSha256(), result.isDryRun(), result.isNoOp(), result.getCounts());
        applicationContext.close();
    }
}
