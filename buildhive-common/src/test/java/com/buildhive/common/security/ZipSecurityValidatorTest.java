package com.buildhive.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipSecurityValidatorTest {

    private final ZipSecurityValidator validator = new ZipSecurityValidator();

    @Test
    @DisplayName("Valid zip archive with pom.xml and App.java succeeds and extracts metadata")
    void testValidProjectArchive() throws IOException {
        String pomXml = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo-app</artifactId>
                    <version>1.2.3</version>
                    <packaging>jar</packaging>
                </project>
                """;
        String javaCode = "package com.example; public class App {}";

        byte[] zipBytes = createZip(Map.of(
                "pom.xml", pomXml,
                "src/main/java/com/example/App.java", javaCode
        ));

        MavenProjectMetadata metadata = validator.validateAndInspect(new ByteArrayInputStream(zipBytes));

        assertThat(metadata.groupId()).isEqualTo("com.example");
        assertThat(metadata.artifactId()).isEqualTo("demo-app");
        assertThat(metadata.version()).isEqualTo("1.2.3");
        assertThat(metadata.packaging()).isEqualTo("jar");
        assertThat(metadata.getCoordinates()).isEqualTo("com.example:demo-app:1.2.3");
    }

    @Test
    @DisplayName("Archive wrapped in a single top-level directory is recognized")
    void testTopLevelFolderArchive() throws IOException {
        String pomXml = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.sample</groupId>
                    <artifactId>sub-project</artifactId>
                    <version>2.0.0</version>
                </project>
                """;

        byte[] zipBytes = createZip(Map.of(
                "my-project/pom.xml", pomXml,
                "my-project/src/main/java/App.java", "public class App {}"
        ));

        MavenProjectMetadata metadata = validator.validateAndInspect(new ByteArrayInputStream(zipBytes));
        assertThat(metadata.groupId()).isEqualTo("org.sample");
        assertThat(metadata.artifactId()).isEqualTo("sub-project");
        assertThat(metadata.pomPathInArchive()).isEqualTo("my-project/pom.xml");
    }

    @Test
    @DisplayName("Zip Slip path traversal attack (../evil.txt) throws SecurityException")
    void testZipSlipAttack() throws IOException {
        byte[] zipBytes = createZip(Map.of(
                "../evil.txt", "malicious payload",
                "pom.xml", "<project></project>"
        ));

        assertThatThrownBy(() -> validator.validateAndInspect(new ByteArrayInputStream(zipBytes)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Zip Slip");
    }

    @Test
    @DisplayName("Archive missing a pom.xml throws IllegalArgumentException")
    void testMissingPomXml() throws IOException {
        byte[] zipBytes = createZip(Map.of(
                "src/main/java/App.java", "public class App {}"
        ));

        assertThatThrownBy(() -> validator.validateAndInspect(new ByteArrayInputStream(zipBytes)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pom.xml");
    }

    @Test
    @DisplayName("Forbidden executable or shell script is rejected")
    void testForbiddenBinaryRejected() throws IOException {
        byte[] zipBytes = createZip(Map.of(
                "pom.xml", "<project></project>",
                "run.bat", "@echo off"
        ));

        assertThatThrownBy(() -> validator.validateAndInspect(new ByteArrayInputStream(zipBytes)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Forbidden executable or script");
    }

    @Test
    @DisplayName("Archive exceeding configured uncompressed size limit is blocked")
    void testZipBombSizeLimit() throws IOException {
        ZipSecurityValidator smallLimitValidator = new ZipSecurityValidator(200, 100);

        byte[] largeContent = new byte[1000];
        byte[] zipBytes = createZip(Map.of(
                "pom.xml", new String(largeContent, StandardCharsets.UTF_8)
        ));

        assertThatThrownBy(() -> smallLimitValidator.validateAndInspect(new ByteArrayInputStream(zipBytes)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("exceeds maximum allowed");
    }

    @Test
    @DisplayName("Safely extracts files to target directory without escaping")
    void testSafeExtraction(@TempDir Path tempDir) throws IOException {
        String pomXml = "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version></project>";
        byte[] zipBytes = createZip(Map.of(
                "pom.xml", pomXml,
                "src/main/java/App.java", "class App {}"
        ));

        Path zipPath = tempDir.resolve("test.zip");
        Files.write(zipPath, zipBytes);

        Path extractDir = tempDir.resolve("extracted");
        validator.safelyExtract(zipPath, extractDir);

        assertThat(extractDir.resolve("pom.xml")).exists();
        assertThat(extractDir.resolve("src/main/java/App.java")).exists();
        assertThat(Files.readString(extractDir.resolve("pom.xml"))).isEqualTo(pomXml);
    }

    private byte[] createZip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(entry.getKey());
                zos.putNextEntry(ze);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
