package com.buildhive.worker.handler;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobType;
import com.buildhive.worker.dto.ArtifactRegistrationDto;
import com.buildhive.worker.dto.JobExecutionResult;
import com.buildhive.worker.executor.ProcessResult;
import com.buildhive.worker.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler for PACKAGING: packages the application (e.g. JAR artifact) skipping tests
 * (since tests were already verified), computes SHA-256, and registers the artifact.
 */
@Component
public class PackagingHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PackagingHandler.class);

    @Override
    public boolean supports(JobType type) {
        return type == JobType.PACKAGING;
    }

    @Override
    public JobExecutionResult execute(JobContext context) {
        log.info("Executing Maven package for job {}", context.job().jobId());

        ProcessResult result = context.mavenRunner().runMavenCommand(
                context.job().pipelineId(),
                context.job().jobId(),
                context.projectRoot(),
                List.of("package", "-DskipTests")
        );

        if (!result.isSuccess()) {
            FailureClassification classification = result.timedOut()
                    ? FailureClassification.INFRASTRUCTURE_ERROR
                    : FailureClassification.USER_CODE_ERROR;
            return JobExecutionResult.failure(result.exitCode(), "Packaging failed", classification);
        }

        Path targetDir = context.projectRoot().resolve("target");
        List<Path> jarFiles = findGeneratedJars(targetDir);

        if (jarFiles.isEmpty()) {
            return JobExecutionResult.failure(
                    "No generated JAR artifact found in " + targetDir,
                    FailureClassification.USER_CODE_ERROR
            );
        }

        // Register primary JAR artifact
        Path mainJar = jarFiles.getFirst();
        try {
            String sha256 = HashUtil.sha256(mainJar);
            long size = Files.size(mainJar);
            String fileName = mainJar.getFileName().toString();

            log.info("Registering JAR artifact '{}' (size={} bytes, sha256={})", fileName, size, sha256);

            ArtifactRegistrationDto artifactDto = new ArtifactRegistrationDto(
                    "JAR",
                    fileName,
                    mainJar.toString(),
                    size,
                    sha256,
                    context.job().claimToken()
            );

            if (context.workerClient().registerArtifact(context.workerId(), context.job().jobId(), artifactDto).isEmpty()) {
                return JobExecutionResult.failure(
                        "Failed to register packaged JAR artifact",
                        FailureClassification.INFRASTRUCTURE_ERROR
                );
            }

        } catch (IOException e) {
            log.error("Failed to compute hash or register JAR artifact: {}", e.getMessage(), e);
            return JobExecutionResult.failure(
                    "Failed to process packaged JAR artifact: " + e.getMessage(),
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }

        return JobExecutionResult.successful();
    }

    public List<Path> findGeneratedJars(Path targetDir) {
        List<Path> jars = new ArrayList<>();
        if (!Files.exists(targetDir)) {
            return jars;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, "*.jar")) {
            for (Path jar : stream) {
                String name = jar.getFileName().toString().toLowerCase();
                if (!name.startsWith("original-") && !name.contains("-sources") && !name.contains("-javadoc")) {
                    jars.add(jar);
                }
            }
        } catch (IOException e) {
            log.warn("Error scanning target directory for JARs: {}", e.getMessage());
        }

        return jars;
    }
}
