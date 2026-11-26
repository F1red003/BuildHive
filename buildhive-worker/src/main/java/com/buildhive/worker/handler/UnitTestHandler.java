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
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Handler for UNIT_TESTS: executes tests with Surefire and parses report XMLs.
 * Failing tests are classified as non-retryable USER_CODE_ERROR.
 */
@Component
public class UnitTestHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(UnitTestHandler.class);

    public record TestMetrics(int total, int failures, int errors, int skipped) {}

    @Override
    public boolean supports(JobType type) {
        return type == JobType.UNIT_TESTS;
    }

    @Override
    public JobExecutionResult execute(JobContext context) {
        log.info("Executing Maven test for job {}", context.job().jobId());

        ProcessResult result = context.mavenRunner().runMavenCommand(
                context.job().pipelineId(),
                context.job().jobId(),
                context.projectRoot(),
                List.of("test")
        );

        // Parse Surefire reports
        Path surefireReportsDir = context.projectRoot().resolve("target/surefire-reports");
        TestMetrics metrics = parseSurefireReports(surefireReportsDir);

        log.info("Test results for job {}: run={}, failures={}, errors={}, skipped={}",
                context.job().jobId(), metrics.total(), metrics.failures(), metrics.errors(), metrics.skipped());

        // Register reports as artifacts if present
        registerTestArtifacts(context, surefireReportsDir);

        if (result.timedOut()) {
            return JobExecutionResult.failure(
                    result.exitCode(),
                    "Unit test execution timed out",
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }

        if (metrics.failures() > 0 || metrics.errors() > 0 || !result.isSuccess()) {
            String reason = String.format("Unit tests failed: %d failed, %d errors out of %d tests run",
                    metrics.failures(), metrics.errors(), metrics.total());
            return JobExecutionResult.failure(result.exitCode(), reason, FailureClassification.USER_CODE_ERROR);
        }

        return JobExecutionResult.successful();
    }

    public TestMetrics parseSurefireReports(Path reportsDir) {
        if (!Files.exists(reportsDir)) {
            return new TestMetrics(0, 0, 0, 0);
        }

        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = dbf.newDocumentBuilder();

            for (Path xmlFile : stream) {
                try (InputStream is = Files.newInputStream(xmlFile)) {
                    Document doc = builder.parse(is);
                    Element suite = doc.getDocumentElement();
                    if (suite != null) {
                        total += getIntAttr(suite, "tests");
                        failures += getIntAttr(suite, "failures");
                        errors += getIntAttr(suite, "errors");
                        skipped += getIntAttr(suite, "skipped");
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse Surefire report {}: {}", xmlFile.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error scanning surefire reports directory: {}", e.getMessage());
        }

        return new TestMetrics(total, failures, errors, skipped);
    }

    private void registerTestArtifacts(JobContext context, Path reportsDir) {
        if (!Files.exists(reportsDir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
            for (Path reportFile : stream) {
                try {
                    String sha256 = HashUtil.sha256(reportFile);
                    long size = Files.size(reportFile);

                    ArtifactRegistrationDto artifact = new ArtifactRegistrationDto(
                            "TEST_REPORT",
                            reportFile.getFileName().toString(),
                            reportFile.toString(),
                            size,
                            sha256,
                            context.job().claimToken()
                    );
                    context.workerClient().registerArtifact(context.workerId(), context.job().jobId(), artifact);
                } catch (Exception e) {
                    log.warn("Failed to register test report artifact {}: {}", reportFile.getFileName(), e.getMessage());
                }
            }
        } catch (Exception ignored) {}
    }

    private int getIntAttr(Element elem, String attr) {
        String val = elem.getAttribute(attr);
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
