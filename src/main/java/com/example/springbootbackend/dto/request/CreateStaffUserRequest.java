package com.example.springbootbackend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStaffUserRequest(
        @NotBlank(message = "Họ và tên không được để trống")
        @Size(max = 100, message = "Họ và tên không được quá 100 ký tự")
        String fullName,

        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không đúng định dạng")
        @Size(max = 100, message = "Email không được quá 100 ký tự")
        String email,

        @Pattern(
                regexp = "^(?:(?:\\+84|0)[35789]\\d{8})?$",
                message = "Số điện thoại không đúng định dạng Việt Nam (ví dụ: 0912345678 hoặc +84912345678)"
        )
        @Size(max = 20, message = "Số điện thoại không được quá 20 ký tự")
        String phone,

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(min = 8, max = 100, message = "Mật khẩu phải từ 8 đến 100 ký tự")
        String password,

        @NotBlank(message = "Vai trò không được để trống")
        String roleName
) {
    public CreateStaffUserRequest {
        if (fullName != null) fullName = fullName.trim();
        if (email != null) email = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (roleName != null) roleName = roleName.trim().toUpperCase(java.util.Locale.ROOT);
        if (phone != null) {
            phone = phone.trim();
            if (phone.isEmpty()) {
                phone = null;
            }
        }
    }
}
 