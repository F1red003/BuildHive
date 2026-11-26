package com.buildhive.server.controller;

import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.dto.SubmissionResponseDto;
import com.buildhive.server.exception.GlobalExceptionHandler;
import com.buildhive.server.service.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SubmissionControllerTest {

    @Mock
    private SubmissionService submissionService;

    @InjectMocks
    private SubmissionController submissionController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(submissionController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Valid upload returns HTTP 201 with submission and pipeline identifiers")
    void testSuccessfulSubmission() throws Exception {
        UUID submissionId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        SubmissionResponseDto mockResponse = new SubmissionResponseDto(
                submissionId,
                pipelineId,
                projectId,
                "my-project",
                PipelineStatus.PENDING,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Instant.now()
        );

        when(submissionService.processSubmission(any(), eq("my-project"))).thenReturn(mockResponse);

        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "project.zip",
                "application/zip",
                "dummy content".getBytes()
        );

        mockMvc.perform(multipart("/api/submissions")
                        .file(zipFile)
                        .param("projectName", "my-project")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").value(submissionId.toString()))
                .andExpect(jsonPath("$.pipelineId").value(pipelineId.toString()))
                .andExpect(jsonPath("$.projectName").value("my-project"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("Security violation during upload returns HTTP 400 with error message")
    void testSecurityViolationReturns400() throws Exception {
        when(submissionService.processSubmission(any(), any()))
                .thenThrow(new SecurityException("Zip Slip attack detected"));

        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "evil.zip",
                "application/zip",
                "malicious content".getBytes()
        );

        mockMvc.perform(multipart("/api/submissions")
                        .file(zipFile)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Security Violation"))
                .andExpect(jsonPath("$.message").value("Zip Slip attack detected"));
    }

    @Test
    @DisplayName("Validation error returns HTTP 400 Bad Request")
    void testValidationErrorReturns400() throws Exception {
        when(submissionService.processSubmission(any(), any()))
                .thenThrow(new IllegalArgumentException("Project archive must contain a root or top-level 'pom.xml' file"));

        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "nopom.zip",
                "application/zip",
                "content".getBytes()
        );

        mockMvc.perform(multipart("/api/submissions")
                        .file(zipFile)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid Request"))
                .andExpect(jsonPath("$.message").value("Project archive must contain a root or top-level 'pom.xml' file"));
    }
}
