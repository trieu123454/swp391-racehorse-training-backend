package com.example.springbootbackend.service;

import org.springframework.stereotype.Component;

import com.example.springbootbackend.entity.AppUser;
import com.example.springbootbackend.entity.UserStatus;
import com.example.springbootbackend.exception.VetException;
import com.example.springbootbackend.repository.AppUserRepository;
import com.example.springbootbackend.repository.HorseRepository;

@Component
public class VetAccess {
    private final AppUserRepository userRepository;
    private final HorseRepository horseRepository;

    public VetAccess(AppUserRepository userRepository, HorseRepository horseRepository) {
        this.userRepository = userRepository;
        this.horseRepository = horseRepository;
    }

    public long requireActiveUser(String email) {
        return requireUser(email, null);
    }

    public long requireRole(String email, String requiredRole) {
        if (requiredRole == null || requiredRole.isBlank()) {
            throw new IllegalArgumentException("requiredRole must be provided");
        }
        return requireUser(email, requiredRole);
    }

    private long requireUser(String email, String requiredRole) {
        AppUser user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new VetException(403, "FORBIDDEN", "Tài khoản không được phép truy cập"));
        if (requiredRole != null) {
            String roleName = user.getRole().getName();
            boolean allowed = requiredRole.equals(roleName);
            // HEAD_VETERINARIAN kế thừa toàn bộ quyền chuyên môn của VETERINARIAN
            if (!allowed && "VETERINARIAN".equals(requiredRole) && "HEAD_VETERINARIAN".equals(roleName)) {
                allowed = true;
            }
            if (!allowed) {
                throw new VetException(403, "FORBIDDEN", "Tài khoản không được phép truy cập");
            }
        }
        return user.getId();
    }

    public void requireHorse(String id, boolean lock) {
        var horse = lock
                ? horseRepository.findActiveByIdForUpdate(id)
                : horseRepository.findByIdAndDeletedAtIsNull(id);
        if (horse.isEmpty()) {
            throw new VetException(404, "HORSE_NOT_FOUND", "Không tìm thấy ngựa");
        }
    }
}
