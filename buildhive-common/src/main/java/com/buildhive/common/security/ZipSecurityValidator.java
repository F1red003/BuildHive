package com.buildhive.common.security;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates the security, integrity, and Maven structure of uploaded project ZIP archives.
 * Protects against Zip Slip, Zip Bomb, forbidden executable scripts, and XXE vulnerabilities.
 */
public class ZipSecurityValidator {

    public static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024; // 100 MB
    public static final int DEFAULT_MAX_ENTRY_COUNT = 5_000;

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".sh", ".bat", ".cmd", ".bin", ".msi", ".dll", ".so", ".dylib", ".vbs", ".ps1"
    );

    private final long maxUncompressedBytes;
    private final int maxEntryCount;

    public ZipSecurityValidator() {
        this(DEFAULT_MAX_UNCOMPRESSED_BYTES, DEFAULT_MAX_ENTRY_COUNT);
    }

    public ZipSecurityValidator(long maxUncompressedBytes, int maxEntryCount) {
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.maxEntryCount = maxEntryCount;
    }

    /**
     * Inspects and validates a ZIP archive, extracting the root Maven pom.xml metadata.
     *
     * @param zipPath path to the ZIP archive on disk
     * @return extracted Maven project metadata
     * @throws ZipSecurityException if any security rule is violated
     * @throws IllegalArgumentException if the archive does not contain a valid pom.xml
     * @throws IOException if an I/O error occurs
     */
    public MavenProjectMetadata validateAndInspect(Path zipPath) throws IOException {
        try (InputStream is = Files.newInputStream(zipPath)) {
            return validateAndInspect(is);
        }
    }

    /**
     * Inspects and validates an input stream containing a ZIP archive.
     */
    public MavenProjectMetadata validateAndInspect(InputStream inputStream) throws IOException {
        long totalBytesRead = 0;
        int totalEntries = 0;

        byte[] pomBytes = null;
        String pomPathInArchive = null;

        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > maxEntryCount) {
                    throw new ZipSecurityException("Archive exceeds maximum entry count of " + maxEntryCount);
                }

                String rawEntryName = entry.getName();
                String entryName = rawEntryName.replace('\\', '/');
                validateEntryPath(entryName);

                boolean isDirectory = entry.isDirectory() || entryName.endsWith("/");
                if (!isDirectory) {
                    validateFileExtension(entryName);

                    ByteArrayOutputStream entryBaos = null;
                    boolean isPom = isRootOrTopLevelPom(entryName);
                    if (isPom && pomBytes == null) {
                        entryBaos = new ByteArrayOutputStream();
                        pomPathInArchive = entryName;
                    }

                    int n;
                    while ((n = zis.read(buffer)) != -1) {
                        totalBytesRead += n;
                        if (totalBytesRead > maxUncompressedBytes) {
                            throw new ZipSecurityException(
                                    "Archive uncompressed size exceeds maximum allowed of " + maxUncompressedBytes + " bytes"
                            );
                        }
                        if (entryBaos != null) {
                            entryBaos.write(buffer, 0, n);
                        }
                    }

                    if (entryBaos != null) {
                        pomBytes = entryBaos.toByteArray();
                    }
                }

                zis.closeEntry();
            }
        }

        if (pomBytes == null) {
            throw new IllegalArgumentException("Project archive must contain a root or top-level 'pom.xml' file");
        }

        return parsePomMetadata(pomBytes, pomPathInArchive);
    }

    /**
     * Safely extracts a ZIP archive into a destination directory with path traversal and size checks.
     *
     * @param zipPath   source ZIP file
     * @param targetDir target extraction root directory
     */
    public void safelyExtract(Path zipPath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path normalizedTargetDir = targetDir.toAbsolutePath().normalize();

        long totalBytesRead = 0;
        int totalEntries = 0;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > maxEntryCount) {
                    throw new ZipSecurityException("Archive exceeds maximum entry count of " + maxEntryCount);
                }

                String rawEntryName = entry.getName();
                String entryName = rawEntryName.replace('\\', '/');
                validateEntryPath(entryName);

                Path destinationPath = normalizedTargetDir.resolve(entryName).normalize();
                if (!destinationPath.startsWith(normalizedTargetDir)) {
                    throw new ZipSecurityException("Zip Slip attack detected in entry: " + entryName);
                }

                boolean isDirectory = entry.isDirectory() || entryName.endsWith("/");
                if (isDirectory) {
                    Files.createDirectories(destinationPath);
                } else {
                    validateFileExtension(entryName);

                    Path parentDir = destinationPath.getParent();
                    if (parentDir != null) {
                        Files.createDirectories(parentDir);
                    }

                    try (OutputStream os = Files.newOutputStream(destinationPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        int n;
                        while ((n = zis.read(buffer)) != -1) {
                            totalBytesRead += n;
                            if (totalBytesRead > maxUncompressedBytes) {
                                throw new ZipSecurityException("Archive exceeded maximum uncompressed size of " + maxUncompressedBytes + " bytes");
                            }
                            os.write(buffer, 0, n);
                        }
                    }
                }

                zis.closeEntry();
            }
        }
    }

    /**
     * Validates that an archive entry name does not attempt directory traversal (Zip Slip).
     */
    public void validateEntryPath(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new ZipSecurityException("Invalid empty zip entry name");
        }

        // Normalize separators
        String normalized = entryName.replace('\\', '/');

        // Check for directory traversal sequences
        if (normalized.startsWith("../") || normalized.contains("/../") || normalized.endsWith("/..") || normalized.equals("..")) {
            throw new ZipSecurityException("Zip Slip attack detected: entry name contains directory traversal sequence '" + entryName + "'");
        }

        // Check for absolute path escapes
        if (normalized.startsWith("/") || normalized.matches("^[a-zA-Z]:.*")) {
            throw new ZipSecurityException("Absolute path detected in zip entry: '" + entryName + "'");
        }
    }

    /**
     * Rejects forbidden executable binaries and shell scripts.
     */
    public void validateFileExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_EXTENSIONS) {
            if (lower.endsWith(blocked)) {
                throw new ZipSecurityException("Forbidden executable or script file detected: '" + fileName + "' (" + blocked + ")");
            }
        }
    }

    private boolean isRootOrTopLevelPom(String entryName) {
        String normalized = entryName.replace('\\', '/').trim();
        // Exact root pom.xml
        if (normalized.equalsIgnoreCase("pom.xml")) {
            return true;
        }
        // Single top-level directory pom.xml (e.g. "my-project/pom.xml")
        String[] parts = normalized.split("/");
        return parts.length == 2 && parts[1].equalsIgnoreCase("pom.xml");
    }

    /**
     * Safely parses Maven POM file on disk with XXE protections enabled.
     */
    public MavenProjectMetadata parsePomFile(Path pomFile) throws IOException {
        byte[] bytes = Files.readAllBytes(pomFile);
        return parsePomMetadata(bytes, pomFile.getFileName().toString());
    }

    /**
     * Safely parses Maven POM XML with XXE protections enabled.
     */
    public MavenProjectMetadata parsePomMetadata(byte[] pomBytes, String pomPath) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Secure XML processing to prevent XXE (XML External Entity injection)
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(pomBytes));
            doc.getDocumentElement().normalize();

            Element projectElem = doc.getDocumentElement();
            if (!"project".equals(projectElem.getNodeName())) {
                throw new IllegalArgumentException("Root element of pom.xml must be <project>");
            }

            String artifactId = getDirectChildText(projectElem, "artifactId");
            String groupId = getDirectChildText(projectElem, "groupId");
            String version = getDirectChildText(projectElem, "version");
            String packaging = getDirectChildText(projectElem, "packaging");

            // If groupId or version are omitted in child, check parent block
            Element parentElem = getDirectChildElement(projectElem, "parent");
            if (parentElem != null) {
                if (groupId == null || groupId.isBlank()) {
                    groupId = getDirectChildText(parentElem, "groupId");
                }
                if (version == null || version.isBlank()) {
                    version = getDirectChildText(parentElem, "version");
                }
            }

            if (artifactId == null || artifactId.isBlank()) {
                throw new IllegalArgumentException("pom.xml is missing required <artifactId>");
            }
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("pom.xml is missing required <groupId>");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("pom.xml is missing required <version>");
            }

            return new MavenProjectMetadata(groupId, artifactId, version, packaging, pomPath);
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new IllegalArgumentException("Failed to parse pom.xml: " + e.getMessage(), e);
        }
    }

    private static String getDirectChildText(Element parent, String tagName) {
        Element child = getDirectChildElement(parent, tagName);
        return child != null ? child.getTextContent().trim() : null;
    }

    private static Element getDirectChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element elem && elem.getNodeName().equals(tagName)) {
                return elem;
            }
        }
        return null;
    }
}
