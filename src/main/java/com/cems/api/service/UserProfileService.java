package com.cems.api.service;

import com.cems.api.dto.UpdateProfileRequest;
import com.cems.api.dto.UserResponse;
import com.cems.api.entity.User;
import com.cems.api.repository.UserRepository;
import com.cems.api.storage.StorageService;
import com.cems.api.storage.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Profile-photo and personal-information handling. Two entry points share one implementation:
 *
 * <ul>
 *   <li>the {@code *ForCurrentUser} methods resolve the record from the authenticated principal's
 *       email, so a caller can only ever reach their own account (Profile Settings);</li>
 *   <li>the {@code *ForUser} methods take a user id and are called only from endpoints gated by
 *       {@code canManageUsers()}, so an administrator can set a photo while creating or editing
 *       an account.</li>
 * </ul>
 *
 * Password changes stay in {@link AuthService}, which already owns credential verification, and
 * the rest of account administration stays in {@link UserManagementService}.
 */
@Service
public class UserProfileService {

    /**
     * Avatars are capped well below the generic 10 MB upload limit — this is a small square image
     * shown at 40–80 px, and it is re-fetched by every client on session bootstrap.
     */
    static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private static final Set<String> ALLOWED_AVATAR_MIME_TYPES = Set.of("image/jpeg", "image/png");
    private static final Set<String> ALLOWED_AVATAR_EXTENSIONS = Set.of("jpg", "jpeg", "png");

    private final UserRepository userRepository;
    private final StorageService storageService;
    private final ActivityLogService activityLogService;

    public UserProfileService(UserRepository userRepository,
            StorageService storageService,
            ActivityLogService activityLogService) {
        this.userRepository = userRepository;
        this.storageService = storageService;
        this.activityLogService = activityLogService;
    }

    public UserResponse updateProfile(String email, UpdateProfileRequest request) {
        User user = findByEmail(email);

        user.setFirstName(trimmed(request.getFirstName()));
        user.setLastName(trimmed(request.getLastName()));
        user.setMiddleName(trimmedOrNull(request.getMiddleName()));
        user.setContactNumber(trimmedOrNull(request.getContactNumber()));

        User saved = userRepository.save(user);
        activityLogService.record("user.profile_updated", "user", saved.getId(), null);
        return UserResponse.fromEntity(saved);
    }

    /**
     * Replaces the signed-in user's profile photo. The previous file is deleted after the new one
     * is stored, so a failed upload never leaves the account without a photo.
     */
    public UserResponse updateAvatar(String email, MultipartFile file) {
        return applyAvatar(findByEmail(email), file);
    }

    public UserResponse deleteAvatar(String email) {
        return clearAvatar(findByEmail(email));
    }

    @Transactional(readOnly = true)
    public AvatarDownload loadAvatar(String email) {
        return readAvatar(findByEmail(email));
    }

    // --- administrator acting on another account (gated by canManageUsers) ---

    public UserResponse updateAvatarForUser(String userId, MultipartFile file) {
        return applyAvatar(findManagedUser(userId), file);
    }

    public UserResponse deleteAvatarForUser(String userId) {
        return clearAvatar(findManagedUser(userId));
    }

    @Transactional(readOnly = true)
    public AvatarDownload loadAvatarForUser(String userId) {
        return readAvatar(findManagedUser(userId));
    }

    // --- shared implementation ---

    private UserResponse applyAvatar(User user, MultipartFile file) {
        validateAvatar(file);

        String previousPath = user.getAvatarPath();
        StoredFile stored = storageService.store(file, "avatars/" + user.getId());

        user.setAvatarPath(stored.path());
        user.setAvatarMimeType(resolveMimeType(stored.mimeType(), stored.originalFilename()));
        user.setAvatarUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        if (previousPath != null && !previousPath.equals(stored.path())) {
            storageService.delete(previousPath);
        }

        activityLogService.record("user.avatar_updated", "user", saved.getId(),
                Map.of("sizeBytes", stored.sizeBytes()));
        return UserResponse.fromEntity(saved);
    }

    private UserResponse clearAvatar(User user) {
        String path = user.getAvatarPath();
        if (path == null) {
            throw new NoSuchElementException("No profile photo to remove.");
        }

        user.setAvatarPath(null);
        user.setAvatarMimeType(null);
        user.setAvatarUpdatedAt(null);
        User saved = userRepository.save(user);
        storageService.delete(path);

        activityLogService.record("user.avatar_removed", "user", saved.getId(), null);
        return UserResponse.fromEntity(saved);
    }

    private AvatarDownload readAvatar(User user) {
        if (user.getAvatarPath() == null) {
            throw new NoSuchElementException("No profile photo has been uploaded.");
        }

        Resource resource = storageService.load(user.getAvatarPath());
        String mimeType = user.getAvatarMimeType() == null
                ? "application/octet-stream"
                : user.getAvatarMimeType();
        return new AvatarDownload(resource, mimeType, user.getAvatarUpdatedAt());
    }

    /** Bytes + metadata for an avatar response (Resource is a Spring type, kept out of the DTO layer). */
    public record AvatarDownload(Resource resource, String mimeType, Instant updatedAt) {
    }

    // --- helpers ---

    /**
     * Images only, and smaller than the generic upload cap. The storage layer would happily accept
     * a PDF under its own whitelist, which would then be served back with an image content type.
     */
    private void validateAvatar(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A profile photo file is required.");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new IllegalArgumentException("Profile photo exceeds the 2 MB maximum.");
        }

        String extension = extensionOf(file.getOriginalFilename());
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase(Locale.ROOT);

        if (!ALLOWED_AVATAR_EXTENSIONS.contains(extension) || !ALLOWED_AVATAR_MIME_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Profile photo must be a JPG or PNG image.");
        }
    }

    private String resolveMimeType(String storedMimeType, String originalFilename) {
        if (storedMimeType != null && ALLOWED_AVATAR_MIME_TYPES.contains(storedMimeType.toLowerCase(Locale.ROOT))) {
            return storedMimeType.toLowerCase(Locale.ROOT);
        }
        return "png".equals(extensionOf(originalFilename)) ? "image/png" : "image/jpeg";
    }

    private String extensionOf(String filename) {
        String extension = StringUtils.getFilenameExtension(
                StringUtils.cleanPath(filename == null ? "" : filename));
        return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    }

    private User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Authenticated user not found."));
    }

    /** Soft-deleted accounts are invisible to management just as they are to the list (rule 3). */
    private User findManagedUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found."));
        if (user.getDeletedAt() != null) {
            throw new NoSuchElementException("User not found.");
        }
        return user;
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private String trimmedOrNull(String value) {
        String trimmed = trimmed(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
