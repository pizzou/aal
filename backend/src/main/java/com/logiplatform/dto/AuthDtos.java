package com.logiplatform.dto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
public final class AuthDtos {
 private AuthDtos(){}
 public record LoginRequest(@NotBlank @Email String email,@NotBlank String password,String otp,String otpChallengeToken){}
 public record LoginOtpRequest(@NotBlank String otpChallengeToken){}
 public record AuthResponse(@JsonIgnore String accessToken,String tenantId,String userId,String role,boolean mustChangePassword,boolean otpRequired,String otpChallengeToken){}
 public record ForgotPasswordRequest(@NotBlank @Email String email){}
 public record ResetPasswordRequest(@NotBlank String token,@NotBlank String newPassword){}
}
