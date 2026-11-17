package com.socialapp.usermanagement.infrastructure.web;

import com.socialapp.shared.domain.ForbiddenOperationException;
import com.socialapp.shared.security.AuthenticatedUser;
import com.socialapp.usermanagement.application.usecase.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AvatarController {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private final UserService userService;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.upload.dir:./uploads/avatars}")
    private String uploadDir;

    @PostMapping("/{id}/avatar")
    public ResponseEntity<AvatarResponse> upload(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser me,
            @RequestParam("file") MultipartFile file) {

        if (!me.id().equals(id)) {
            throw new ForbiddenOperationException("You can only change your own avatar");
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        }

        String ext = switch (contentType) {
            case "image/png"  -> "png";
            case "image/gif"  -> "gif";
            case "image/webp" -> "webp";
            default           -> "jpg";
        };

        try {
            Path dir = Path.of(uploadDir);
            Files.createDirectories(dir);
            String filename = id + "_" + UUID.randomUUID() + "." + ext;
            file.transferTo(dir.resolve(filename));

            String avatarUrl = baseUrl + "/uploads/avatars/" + filename;
            userService.updateAvatar(id, avatarUrl);
            return ResponseEntity.ok(new AvatarResponse(avatarUrl));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    record AvatarResponse(String avatarUrl) {}
}
