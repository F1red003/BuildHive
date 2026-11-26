package com.buildhive.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ArtifactPublicationServicePathTest {
    @Test
    void directoryRedirectCannotEscapeManagedRoot(@TempDir Path temporary) throws Exception {
        Path managed = Files.createDirectory(temporary.resolve("managed"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        Path redirect = managed.resolve("redirect");
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", redirect.toString(), outside.toString())
                    .redirectErrorStream(true).start();
            String output = new String(junction.getInputStream().readAllBytes());
            assertThat(junction.waitFor()).as(output).isZero();
        } else {
            Files.createSymbolicLink(redirect, outside);
        }
        try {
            assertThatThrownBy(() -> ArtifactPublicationService.safeFile(managed, redirect.resolve("private.txt")))
                    .isInstanceOf(IllegalArgumentException.class);
            // An attacker may replace the attempt root itself, not just a child directory.
            assertThatThrownBy(() -> ArtifactPublicationService.safeFile(redirect, redirect.resolve("private.txt")))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            Files.delete(redirect); // Remove only the link/junction entry, never its target.
        }
        assertThat(outside.resolve("private.txt")).hasContent("private");
    }
}
