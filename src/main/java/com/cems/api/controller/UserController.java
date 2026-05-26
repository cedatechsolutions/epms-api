package com.cems.api.controller;

import com.cems.api.dto.CreateUserRequest;
import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.PaginatedResponse;
import com.cems.api.dto.ResetUserPasswordRequest;
import com.cems.api.dto.UpdateUserRequest;
import com.cems.api.dto.UpdateUserStatusRequest;
import com.cems.api.dto.UserListQuery;
import com.cems.api.dto.UserResponse;
import com.cems.api.service.UserManagementService;
import com.cems.api.service.UserPdfExportService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserManagementService userManagementService;
    private final UserPdfExportService userPdfExportService;

    public UserController(UserManagementService userManagementService,
            UserPdfExportService userPdfExportService) {
        this.userManagementService = userManagementService;
        this.userPdfExportService = userPdfExportService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return ResponseEntity.ok(userManagementService.getCurrentUser(authentication.getName()));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @GetMapping
    public ResponseEntity<PaginatedResponse<UserResponse>> getAllUsers(UserListQuery query,
            HttpServletRequest request) {
        return ResponseEntity.ok(new PaginatedResponse<>(
                userManagementService.getUsers(query),
                request.getRequestURI()));
    }


    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
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

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable String userId) {
        return ResponseEntity.ok(userManagementService.getUserById(userId));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userManagementService.createManagedUser(request));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable String userId, @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userManagementService.updateManagedUser(userId, request));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{userId}/status")
    public ResponseEntity<UserResponse> updateUserStatus(@PathVariable String userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(userManagementService.updateManagedUserStatus(userId, request.getStatus()));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{userId}/password")
    public ResponseEntity<ApiResponse> resetUserPassword(@PathVariable String userId,
            @Valid @RequestBody ResetUserPasswordRequest request) {
        userManagementService.resetManagedUserPassword(
                userId,
                request.getPassword(),
                request.getPasswordConfirmation());
        return ResponseEntity.ok(new ApiResponse("Password reset successfully."));
    }

    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse> deleteUser(@PathVariable String userId) {
        userManagementService.deleteManagedUser(userId);
        return ResponseEntity.ok(new ApiResponse("User deleted successfully."));
    }
}
