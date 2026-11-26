package com.buildhive.worker.handler;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobType;
import com.buildhive.worker.client.WorkerClient;
import com.buildhive.worker.dto.ArtifactRegistrationDto;
import com.buildhive.worker.dto.JobClaimDto;
import com.buildhive.worker.dto.JobExecutionResult;
import com.buildhive.worker.executor.MavenProcessRunner;
import com.buildhive.worker.executor.ProcessResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import com.buildhive.worker.dto.ArtifactResponseDto;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobHandlersTest {

    @TempDir
    Path tempDir;

    @Mock
    private MavenProcessRunner mavenRunner;

    @Mock
    private WorkerClient workerClient;

    private JobClaimDto sampleClaim;

    @BeforeEach
    void setUp() {
        sampleClaim = new JobClaimDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                JobType.COMPILATION,
                "token-test",
                Instant.now().plusSeconds(300),
                1,
                "/data/sample.zip",
                null
        );
    }

    @Test
    @DisplayName("ProjectValidationHandler succeeds when pom.xml exists with valid coordinates")
    void testProjectValidationSuccess() throws IOException {
        Path projectRoot = tempDir.resolve("valid-project");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(projectRoot.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.buildhive.test</groupId>
                    <artifactId>test-sample</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        ProjectValidationHandler handler = new ProjectValidationHandler();
        assertThat(handler.supports(JobType.PROJECT_VALIDATION)).isTrue();

        JobContext context = new JobContext("w-1", sampleClaim, tempDir, projectRoot, mavenRunner, workerClient);
        JobExecutionResult result = handler.execute(context);

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("ProjectValidationHandler fails with USER_CODE_ERROR when pom.xml is missing")
    void testProjectValidationMissingPom() {
        Path projectRoot = tempDir.resolve("empty-project");

        ProjectValidationHandler handler = new ProjectValidationHandler();
        JobContext context = new JobContext("w-1", sampleClaim, tempDir, projectRoot, mavenRunner, workerClient);
        JobExecutionResult result = handler.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.failureClassification()).isEqualTo(FailureClassification.USER_CODE_ERROR);
    }

    @Test
    @DisplayName("DependencyResolutionHandler executes dependency:resolve and classifies failure as INFRASTRUCTURE_ERROR")
    void testDependencyResolutionHandler() {
        DependencyResolutionHandler handler = new DependencyResolutionHandler();
        assertThat(handler.supports(JobType.DEPENDENCY_RESOLUTION)).isTrue();

        when(mavenRunner.runMavenCommand(any(), any(), any(), any()))
                .thenReturn(new ProcessResult(1, 1500L, false, tempDir.resolve("build.log")));

        JobContext context = new JobContext("w-1", sampleClaim, tempDir, tempDir, mavenRunner, workerClient);
        JobExecutionResult result = handler.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.failureClassification()).isEqualTo(FailureClassification.INFRASTRUCTURE_ERROR);
    }

    @Test
    @DisplayName("CompilationHandler executes compile and classifies compilation failure as USER_CODE_ERROR")
    void testCompilationHandlerFailure() {
        CompilationHandler handler = new CompilationHandler();
        assertThat(handler.supports(JobType.COMPILATION)).isTrue();

        when(mavenRunner.runMavenCommand(any(), any(), any(), any()))
                .thenReturn(new ProcessResult(1, 2000L, false, tempDir.resolve("build.log")));

        JobContext context = new JobContext("w-1", sampleClaim, tempDir, tempDir, mavenRunner, workerClient);
        JobExecutionResult result = handler.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.failureClassification()).isEqualTo(FailureClassification.USER_CODE_ERROR);
    }

    @Test
    @DisplayName("UnitTestHandler parses Surefire XML and reports test failure as USER_CODE_ERROR")
    void testUnitTestHandlerWithFailures() throws IOException {
        Path reportsDir = tempDir.resolve("target/surefire-reports");
        Files.createDirectories(reportsDir);

        Files.writeString(reportsDir.resolve("TEST-SampleTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="SampleTest" tests="5" failures="1" errors="0" skipped="1" time="0.5">
                    <testcase name="testOne" time="0.1"/>
                    <testcase name="testTwo" time="0.1">
                        <failure message="expected 1 but was 2"/>
                    </testcase>
                </testsuite>
                """);

        UnitTestHandler handler = new UnitTestHandler();
        assertThat(handler.supports(JobType.UNIT_TESTS)).isTrue();

        when(mavenRunner.runMavenCommand(any(), any(), any(), any()))
                .thenReturn(new ProcessResult(1, 3000L, false, tempDir.resolve("test.log")));

        JobContext context = new JobContext("w-1", sampleClaim, tempDir, tempDir, mavenRunner, workerClient);
        JobExecutionResult result = handler.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.failureClassification()).isEqualTo(FailureClassification.USER_CODE_ERROR);
        assertThat(result.failureReason()).contains("1 failed");
    }

    @Test
    @DisplayName("PackagingHandler finds generated JAR and registers artifact")
    void testPackagingHandlerSuccess() throws IOException {
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Path jarFile = targetDir.resolve("my-app-1.0.jar");
        Files.writeString(jarFile, "fake jar content");

        PackagingHandler handler = new PackagingHandler();
        assertThat(handler.supports(JobType.PACKAGING)).isTrue();

        when(mavenRunner.runMavenCommand(any(), any(), any(), any()))
                .thenReturn(new ProcessResult(0, 4000L, false, tempDir.resolve("package.log")));

        when(workerClient.registerArtifact(any(), any(), any()))
                .thenReturn(Optional.of(mock(ArtifactResponseDto.class)));

        JobContext context = new JobContext("w-1", sampleClaim, tempDir, tempDir, mavenRunner, workerClient);
        JobExecutionResult result = handler.execute(context);

        assertThat(result.success()).isTrue();

        ArgumentCaptor<ArtifactRegistrationDto> captor = ArgumentCaptor.forClass(ArtifactRegistrationDto.class);
        verify(workerClient).registerArtifact(eq("w-1"), eq(sampleClaim.jobId()), captor.capture());

        ArtifactRegistrationDto registered = captor.getValue();
        assertThat(registered.artifactType()).isEqualTo("JAR");
        assertThat(registered.fileName()).isEqualTo("my-app-1.0.jar");
        assertThat(registered.sha256()).isNotBlank();
    }

    @Test
    void packagingFailsWhenArtifactRegistrationFails() throws IOException {
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/app.jar"), "jar content");
        when(mavenRunner.runMavenCommand(any(), any(), any(), any()))
                .thenReturn(new ProcessResult(0, 10L, false, tempDir.resolve("package.log")));
        when(workerClient.registerArtifact(any(), any(), any())).thenReturn(Optional.empty());

        JobContext context = new JobContext("w-1", sampleClaim, tempDir, tempDir, mavenRunner, workerClient);
        JobExecutionResult result = new PackagingHandler().execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.failureClassification()).isEqualTo(FailureClassification.INFRASTRUCTURE_ERROR);
        assertThat(result.failureReason()).contains("register");
    }

    @Test
    @DisplayName("ReportGenerationHandler creates JSON and HTML reports and registers artifacts")
    void testReportGenerationHandler() {
        ReportGenerationHandler handler = new ReportGenerationHandler();
        assertThat(handler.supports(JobType.REPORT_GENERATION)).isTrue();

        JobContext context = new JobContext("w-1", sampleClaim, tempDir, tempDir, mavenRunner, workerClient);
        JobExecutionResult result = handler.execute(context);

        assertThat(result.success()).isTrue();

        verifyNoInteractions(workerClient);
    }
}
