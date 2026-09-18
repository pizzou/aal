package com.logiplatform.controller;
import com.logiplatform.dto.AuthDtos.*; import com.logiplatform.service.AuthService; import jakarta.validation.Valid; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/auth/password")
public class PasswordResetController{
 private final AuthService service; public PasswordResetController(AuthService service){this.service=service;}
 @PostMapping("/forgot") public ResponseEntity<Void> forgot(@Valid @RequestBody ForgotPasswordRequest r){service.forgotPassword(r.email());return ResponseEntity.noContent().build();}
 @PostMapping("/reset") public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest r){service.resetPassword(r.token(),r.newPassword());return ResponseEntity.noContent().build();}
}
