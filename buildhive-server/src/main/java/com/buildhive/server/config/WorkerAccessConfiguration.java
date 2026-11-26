package com.buildhive.server.config;

import com.buildhive.server.repository.WorkerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Worker credentials are provisioned by operators; an empty configuration denies mutations. */
@Configuration
public class WorkerAccessConfiguration implements WebMvcConfigurer {
    private final Map<String, String> credentials;
    private final WorkerRepository workers;

    public WorkerAccessConfiguration(@Value("${buildhive.security.worker-credentials:{}}") String json,
                                     ObjectMapper mapper, WorkerRepository workers) throws Exception {
        this.credentials = Map.copyOf(mapper.readValue(json, new TypeReference<Map<String, String>>() {}));
        if (credentials.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue().length() < 32)) {
            throw new IllegalArgumentException("Worker credentials require a name and at least 32 characters");
        }
        this.workers = workers;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                if (request.getMethod().equals("GET") || request.getMethod().equals("OPTIONS")) return true;
                String name = request.getHeader("X-Worker-Name");
                String supplied = request.getHeader("X-Worker-Key");
                String expected = name == null ? null : credentials.get(name);
                if (expected == null || supplied == null || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                    response.sendError(401, "Worker credentials required");
                    return false;
                }
                @SuppressWarnings("unchecked")
                Map<String, String> variables = (Map<String, String>) request.getAttribute(
                        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                String workerId = variables == null ? null : variables.get("workerId");
                if (workerId == null && request.getRequestURI().contains("/api/internal/")) {
                    workerId = request.getHeader("X-Worker-Id");
                    if (workerId == null) { response.sendError(403); return false; }
                }
                if (workerId != null && workers.findById(workerId)
                        .filter(w -> name.equals(w.getWorkerName())).isEmpty()) {
                    response.sendError(403, "Worker identity mismatch");
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/api/workers/**", "/api/internal/**");
    }
}
