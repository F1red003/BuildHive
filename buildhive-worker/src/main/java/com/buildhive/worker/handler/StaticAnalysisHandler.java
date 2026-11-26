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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Runs a pinned Checkstyle goal and publishes its results from the current attempt. */
@Component
public class StaticAnalysisHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(StaticAnalysisHandler.class);
    private static final String CHECKSTYLE_GOAL = "org.apache.maven.plugins:maven-checkstyle-plugin:3.4.0:check";
    private static final String REPORT_TYPE = "CHECKSTYLE_REPORT";
    private static final List<String> TRANSIENT_MAVEN_ERRORS = List.of(
            "Could not transfer artifact", "UnknownHostException", "Connection timed out",
            "Connection reset", "Temporary failure in name resolution");

    @Override
    public boolean supports(JobType type) {
        return type == JobType.STATIC_ANALYSIS;
    }

    @Override
    public JobExecutionResult execute(JobContext context) {
        String resultName = "buildhive-checkstyle-" + UUID.randomUUID() + ".xml";
        try {
            Path rules = copyDefaultRules(context.workspaceDir());
            // Maven uses an explicit POM configLocation when one is configured.
            ProcessResult result = context.mavenRunner().runMavenCommand(
                    context.job().pipelineId(), context.job().jobId(), context.projectRoot(),
                    List.of(CHECKSTYLE_GOAL, "-Dcheckstyle.config.location=" + rules,
                            "-Dcheckstyle.output.file=target/" + resultName,
                            "-Dcheckstyle.output.format=xml", "-Dcheckstyle.skip=false",
                            "-Dcheckstyle.skipExec=false", "-Dcheckstyle.failOnViolation=true"));
            if (result.timedOut()) {
                return JobExecutionResult.failure(result.exitCode(), "Checkstyle timed out",
                        FailureClassification.INFRASTRUCTURE_ERROR);
            }

            List<Path> reports;
            try (var files = Files.find(context.projectRoot(), Integer.MAX_VALUE,
                    (path, attrs) -> attrs.isRegularFile() && path.getFileName().toString().equals(resultName))) {
                reports = files.sorted().toList();
            }
            int violations = 0;
            int index = 0;
            for (Path report : reports) {
                violations += countViolations(report);
                index++;
                String fileName = reports.size() == 1 ? "checkstyle-result.xml" : "checkstyle-result-" + index + ".xml";
                if (!publishReport(context, report, fileName)) {
                    return JobExecutionResult.failure("Could not publish the Checkstyle report",
                            FailureClassification.INFRASTRUCTURE_ERROR);
                }
            }
            if (violations > 0) {
                return JobExecutionResult.failure(Math.max(1, result.exitCode()),
                        "Checkstyle reported " + violations + " rule violation(s). Review the Checkstyle report.",
                        FailureClassification.USER_CODE_ERROR);
            }
            if (!result.isSuccess() || reports.isEmpty()) {
                boolean transientFailure = result.outputLines().stream().anyMatch(
                        line -> TRANSIENT_MAVEN_ERRORS.stream().anyMatch(line::contains));
                return JobExecutionResult.failure(Math.max(1, result.exitCode()),
                        "Checkstyle did not complete successfully with a usable report. Check the Maven log.",
                        transientFailure ? FailureClassification.INFRASTRUCTURE_ERROR : FailureClassification.USER_CODE_ERROR);
            }
            return JobExecutionResult.successful();
        } catch (XMLStreamException invalidReport) {
            log.warn("Checkstyle produced an invalid report for job {}", context.job().jobId(), invalidReport);
            return JobExecutionResult.failure("Checkstyle produced invalid report XML. Check the Maven log.",
                    FailureClassification.USER_CODE_ERROR);
        } catch (IOException | java.io.UncheckedIOException failure) {
            log.warn("Could not prepare or read Checkstyle files for job {}", context.job().jobId(), failure);
            return JobExecutionResult.failure("Could not prepare or read Checkstyle files",
                    FailureClassification.INFRASTRUCTURE_ERROR);
        }
    }

    private Path copyDefaultRules(Path workspace) throws IOException {
        try (InputStream resource = getClass().getResourceAsStream("/buildhive-checkstyle.xml")) {
            if (resource == null) throw new IOException("Default Checkstyle rules are missing from the worker");
            Path rules = Files.createTempFile(workspace, "buildhive-rules-", ".xml");
            Files.copy(resource, rules, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return rules.toAbsolutePath();
        }
    }

    private int countViolations(Path report) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream input = Files.newInputStream(report)) {
            var reader = factory.createXMLStreamReader(input);
            try {
                if (reader.nextTag() != XMLStreamConstants.START_ELEMENT || !reader.getLocalName().equals("checkstyle")) {
                    throw new XMLStreamException("Expected a Checkstyle report");
                }
                int violations = 0;
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && reader.getLocalName().equals("error")
                            && "error".equals(reader.getAttributeValue(null, "severity"))) {
                        violations++;
                    }
                }
                return violations;
            } finally {
                reader.close();
            }
        }
    }

    private boolean publishReport(JobContext context, Path report, String fileName) throws IOException {
        String checksum = HashUtil.sha256(report);
        var artifact = new ArtifactRegistrationDto(REPORT_TYPE, fileName, report.toString(), Files.size(report),
                checksum, context.job().claimToken());
        return context.workerClient().registerArtifact(context.workerId(), context.job().jobId(), artifact).isPresent();
    }
}
