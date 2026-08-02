package com.cems.api.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores uploads on the local filesystem under a configured base directory, outside the webroot
 * (config {@code app.storage.local.base-path}, default {@code ./storage}). Validates MIME type,
 * extension, and size (≤10 MB; pdf/docx/xlsx/jpg/png) per spec Module 2 before writing.
 *
 * <p>Files are written with a random name to avoid collisions and path-traversal via the original
 * filename; the original name is preserved separately in DB metadata for download.
 */
@Service
public class LocalDiskStorageService implements StorageService {

    private static final Logger logger = LoggerFactory.getLogger(LocalDiskStorageService.class);

    /** Maximum upload size: 10 MB (spec Module 2). */
    static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    /** Allowed extension -> permitted MIME types (spec Module 2: pdf, docx, xlsx, jpg, png). */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "jpg", Set.of("image/jpeg"),
            "jpeg", Set.of("image/jpeg"),
            "png", Set.of("image/png"));

    private final Path baseDir;

    public LocalDiskStorageService(@Value("${app.storage.local.base-path:./storage}") String basePath) {
        this.baseDir = Paths.get(basePath).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(MultipartFile file, String subdir) {
        validate(file);

        String extension = extensionOf(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + "." + extension;
        Path targetDir = resolveSubdir(subdir);
        Path target = targetDir.resolve(storedName);

        try {
            Files.createDirectories(targetDir);
            file.transferTo(target);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to store uploaded file.", ex);
        }

        String relativePath = baseDir.relativize(target).toString().replace('\\', '/');
        return new StoredFile(
                relativePath,
                StringUtils.cleanPath(file.getOriginalFilename()),
                file.getContentType(),
                file.getSize());
    }

    @Override
    public StoredFile storeBytes(byte[] content, String originalFilename, String mimeType, String subdir) {
        if (content == null || content.length == 0) {
            throw new IllegalStateException("Refusing to store an empty generated file.");
        }

        String extension = extensionOf(originalFilename);
        String storedName = UUID.randomUUID() + (extension.isEmpty() ? "" : "." + extension);
        Path targetDir = resolveSubdir(subdir);
        Path target = targetDir.resolve(storedName);

        try {
            Files.createDirectories(targetDir);
            Files.write(target, content);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to store the generated file.", ex);
        }

        String relativePath = baseDir.relativize(target).toString().replace('\\', '/');
        return new StoredFile(relativePath, StringUtils.cleanPath(originalFilename), mimeType, content.length);
    }

    @Override
    public Resource load(String path) {
        Path target = resolveStoredPath(path);
        try {
            Resource resource = new UrlResource(target.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new java.util.NoSuchElementException("File not found.");
            }
            return resource;
        } catch (MalformedURLException ex) {
            throw new UncheckedIOException(new IOException("Invalid file path.", ex));
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(resolveStoredPath(path));
        } catch (IOException ex) {
            // Non-fatal: the DB row is the source of truth; log and continue.
            logger.warn("Could not delete stored file '{}': {}", path, ex.getMessage());
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty file is required.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("File exceeds the 10 MB maximum.");
        }

        String extension = extensionOf(file.getOriginalFilename());
        Set<String> allowedMimes = ALLOWED.get(extension);
        if (allowedMimes == null) {
            throw new IllegalArgumentException(
                    "File type not allowed. Accepted types: pdf, docx, xlsx, jpg, png.");
        }

        String contentType = file.getContentType();
        if (contentType != null && !allowedMimes.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "File content type '" + contentType + "' does not match its ." + extension + " extension.");
        }
    }

    private String extensionOf(String filename) {
        String cleaned = StringUtils.cleanPath(filename == null ? "" : filename);
        String ext = StringUtils.getFilenameExtension(cleaned);
        return ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    }

    private Path resolveSubdir(String subdir) {
        Path resolved = baseDir.resolve(StringUtils.cleanPath(subdir == null ? "" : subdir)).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid storage location.");
        }
        return resolved;
    }

    private Path resolveStoredPath(String path) {
        Path resolved = baseDir.resolve(StringUtils.cleanPath(path)).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid file path.");
        }
        return resolved;
    }
}
