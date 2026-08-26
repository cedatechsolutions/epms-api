package com.cems.api.controller;

import com.cems.api.dto.CreateUserRequest;
import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.PaginatedResponse;
import com.cems.api.dto.ResetUserPasswordRequest;
import com.cems.api.dto.UpdateProfileRequest;
import com.cems.api.dto.UpdateUserRequest;
import com.cems.api.dto.UpdateUserStatusRequest;
import com.cems.api.dto.UserListQuery;
import com.cems.api.dto.UserOptionResponse;
import com.cems.api.dto.UserResponse;
import com.cems.api.dto.UserStatsResponse;
import com.cems.api.service.UserManagementService;
import com.cems.api.service.UserPdfExportService;
import com.cems.api.service.UserProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserManagementService userManagementService;
    private final UserProfileService userProfileService;
    private final UserPdfExportService userPdfExportService;

    public UserController(UserManagementService userManagementService,
            UserProfileService userProfileService,
            UserPdfExportService userPdfExportService) {
        this.userManagementService = userManagementService;
        this.userProfileService = userProfileService;
        this.userPdfExportService = userPdfExportService;
    }

    // --- the signed-in user's own account (Profile Settings) ---
    // No @PreAuthorize: these resolve the target from the authenticated principal, so any
    // authenticated role reaches exactly one record — their own.

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        return ResponseEntity.ok(userManagementService.getCurrentUser(requireAuthenticated(authentication)));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUserProfile(@Valid @RequestBody UpdateProfileRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(userProfileService.updateProfile(requireAuthenticated(authentication), request));
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> uploadCurrentUserAvatar(@RequestParam("file") MultipartFile file,
            Authentication authentication) {
        return ResponseEntity.ok(userProfileService.updateAvatar(requireAuthenticated(authentication), file));
    }

    /**
     * Serves the signed-in user's photo bytes. Kept behind the bearer token like every other
     * upload, so clients fetch it as a blob rather than pointing an {@code <img src>} at a public
     * URL. {@code no-cache} because the path never changes when the photo is replaced.
     */
    @GetMapping("/me/avatar")
    public ResponseEntity<org.springframework.core.io.Resource> getCurrentUserAvatar(Authentication authentication) {
        UserProfileService.AvatarDownload avatar =
                userProfileService.loadAvatar(requireAuthenticated(authentication));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, private")
                .body(avatar.resource());
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<UserResponse> deleteCurrentUserAvatar(Authentication authentication) {
        return ResponseEntity.ok(userProfileService.deleteAvatar(requireAuthenticated(authentication)));
    }

    private String requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return authentication.getName();
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @GetMapping
    public ResponseEntity<PaginatedResponse<UserResponse>> getAllUsers(UserListQuery query,
            HttpServletRequest request) {
        return ResponseEntity.ok(new PaginatedResponse<>(
                userManagementService.getUsers(query),
                request.getRequestURI()));
    }


    @PreAuthorize("@permissions.canManageUsers()")
    @GetMapping("/stats")
    public ResponseEntity<UserStatsResponse> getUserStats() {
        return ResponseEntity.ok(userManagementService.getUserStats());
    }

    /**
     * Name-and-role directory backing people pickers, optionally filtered by {@code role}
     * (spec Module 5 §2). Readable by any proposal author — see
     * {@code Permissions.canBrowseUserDirectory()} for why this is wider than the rest of this
     * controller, and {@link com.cems.api.dto.UserOptionResponse} for what bounds the exposure.
     */
    @PreAuthorize("@permissions.canBrowseUserDirectory()")
    @GetMapping("/directory")
    public ResponseEntity<List<UserOptionResponse>> getUserDirectory(
            @RequestParam(value = "role", required = false) String role) {
        return ResponseEntity.ok(userManagementService.getDirectory(role));
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @GetMapping(value = "/print", produces = "application/pdf")
    public ResponseEntity<StreamingResponseBody> printUsers() {
        byte[] pdf = userPdfExportService.buildUsersPdf(userManagementService.getAllUsers());
        StreamingResponseBody responseBody = outputStream -> {
            outputStream.write(pdf);
            outputStream.flush();
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/pdf"))
                .contentLength(pdf.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"cems-users.pdf\"")
                .body(responseBody);
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable String userId) {
        return ResponseEntity.ok(userManagementService.getUserById(userId));
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userManagementService.createManagedUser(request));
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable String userId, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userManagementService.updateManagedUser(userId, request));
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @PatchMapping("/{userId}/status")
    public ResponseEntity<UserResponse> updateUserStatus(@PathVariable String userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(userManagementService.updateManagedUserStatus(userId, request.getStatus()));
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @PatchMapping("/{userId}/password")
    public ResponseEntity<ApiResponse> resetUserPassword(@PathVariable String userId,
            @Valid @RequestBody ResetUserPasswordRequest request) {
        userManagementService.resetManagedUserPassword(
                userId,
                request.getPassword(),
                request.getPasswordConfirmation());
        return ResponseEntity.ok(new ApiResponse("Password reset successfully."));
    }

    // Profile photo of a managed account: an administrator sets or clears it while creating or
    // editing the user. Same storage, validation and 2 MB image rule as the self-service path.

    @PreAuthorize("@permissions.canManageUsers()")
    @PostMapping(value = "/{userId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserResponse> uploadUserAvatar(@PathVariable String userId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(userProfileService.updateAvatarForUser(userId, file));
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @GetMapping("/{userId}/avatar")
    public ResponseEntity<org.springframework.core.io.Resource> getUserAvatar(@PathVariable String userId) {
        UserProfileService.AvatarDownload avatar = userProfileService.loadAvatarForUser(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, private")
                .body(avatar.resource());
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @DeleteMapping("/{userId}/avatar")
    public ResponseEntity<UserResponse> deleteUserAvatar(@PathVariable String userId) {
        return ResponseEntity.ok(userProfileService.deleteAvatarForUser(userId));
    }

    @PreAuthorize("@permissions.canManageUsers()")
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse> deleteUser(@PathVariable String userId) {
        userManagementService.deleteManagedUser(userId);
        return ResponseEntity.ok(new ApiResponse("User deleted successfully."));
    }
}
