package com.buildhive.worker.handler;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobType;
import com.buildhive.common.security.MavenProjectMetadata;
import com.buildhive.common.security.ZipSecurityValidator;
import com.buildhive.worker.dto.JobExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handler for PROJECT_VALIDATION: verifies project directory structure,
 * parses pom.xml, and validates Maven project coordinates.
 */
@Component
public class ProjectValidationHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ProjectValidationHandler.class);
    private final ZipSecurityValidator zipSecurityValidator = new ZipSecurityValidator();

    @Override
    public boolean supports(JobType type) {
        return type == JobType.PROJECT_VALIDATION;
    }

    @Override
    public JobExecutionResult execute(JobContext context) {
        Path pomFile = context.projectRoot().resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            return JobExecutionResult.failure(
                    "Validation failed: pom.xml not found in project directory " + context.projectRoot(),
                    FailureClassification.USER_CODE_ERROR
            );
        }

        try {
            MavenProjectMetadata metadata = zipSecurityValidator.parsePomFile(pomFile);
            log.info("Project validation successful for {}:{}:{}",
                    metadata.groupId(), metadata.artifactId(), metadata.version());

            // Check Java source folder presence
            Path srcJava = context.projectRoot().resolve("src/main/java");
            if (!Files.exists(srcJava)) {
                log.warn("Project does not contain a standard src/main/java directory");
            }

            return JobExecutionResult.successful();
        } catch (Exception e) {
            log.error("Failed to parse and validate pom.xml: {}", e.getMessage(), e);
            return JobExecutionResult.failure(
                    "Invalid Maven project configuration: " + e.getMessage(),
                    FailureClassification.USER_CODE_ERROR
            );
        }
    }
}
