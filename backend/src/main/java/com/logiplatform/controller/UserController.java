package com.logiplatform.controller;

import com.logiplatform.model.Role;
import com.logiplatform.model.RoleName;
import com.logiplatform.model.User;
import com.logiplatform.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/users") @PreAuthorize("hasRole('ADMIN')")
public class UserController {
 private final UserService service; public UserController(UserService service){this.service=service;}
 @GetMapping public List<UserResponse> getAll(){return service.getAll().stream().map(UserResponse::from).toList();}
 @GetMapping("/roles") public List<RoleResponse> roles(){return service.getRoles().stream().map(RoleResponse::from).toList();}
 @GetMapping("/{id}") public UserResponse get(@PathVariable UUID id){return UserResponse.from(service.getById(id));}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public UserResponse create(@Valid @RequestBody CreateUserRequest r){return UserResponse.from(service.createUser(r.email(),r.displayName(),r.phone(),RoleName.from(r.role())));}
 @PatchMapping("/{id}/role") public UserResponse role(@PathVariable UUID id,@Valid @RequestBody ChangeRoleRequest r){return UserResponse.from(service.changeRole(id,RoleName.from(r.role())));}
 @PatchMapping("/{id}/customer-link") public UserResponse customerLink(@PathVariable UUID id,@Valid @RequestBody CustomerLinkRequest r){return UserResponse.from(service.linkCustomer(id,r.clientId()));}
 @PatchMapping("/{id}/email") public UserResponse email(@PathVariable UUID id,@Valid @RequestBody UpdateEmailRequest r){return UserResponse.from(service.updateEmail(id,r.email()));}
 @PatchMapping("/me/password") public UserResponse ownPassword(@Valid @RequestBody ChangeOwnPasswordRequest r, org.springframework.security.core.Authentication authentication){
  com.logiplatform.security.TenantPrincipal p=(com.logiplatform.security.TenantPrincipal)authentication.getPrincipal();
  return UserResponse.from(service.changeOwnPassword(p.userId(),r.currentPassword(),r.newPassword()));
 }
 @PatchMapping("/{id}/password") public UserResponse password(@PathVariable UUID id,@Valid @RequestBody UpdatePasswordRequest r){return UserResponse.from(service.updatePassword(id,r.password()));}
 @PostMapping("/{id}/deactivate") public UserResponse deactivate(@PathVariable UUID id){return UserResponse.from(service.deactivate(id));}
 @PostMapping("/{id}/reactivate") public UserResponse reactivate(@PathVariable UUID id){return UserResponse.from(service.reactivate(id));}
 public record CreateUserRequest(@NotBlank @Email @Size(max=255) String email,String displayName,@Size(max=50) String phone,@NotBlank String role){}
 public record ChangeRoleRequest(@NotBlank String role){}
 public record CustomerLinkRequest(@NotBlank String clientId){}
 public record UpdateEmailRequest(@NotBlank @Email @Size(max=255) String email){}
 public record UpdatePasswordRequest(@NotBlank @Size(min=10,max=128) String password){}
 public record ChangeOwnPasswordRequest(@NotBlank String currentPassword,@NotBlank @Size(min=10,max=128) String newPassword){}
 public record UserResponse(UUID id,String email,String displayName,String phone,String role,boolean active,boolean mustChangePassword,Instant createdAt,String customerClientId){static UserResponse from(User u){return new UserResponse(u.getId(),u.getEmail(),u.getDisplayName(),u.getPhone(),u.getRole(),u.isActive(),u.isMustChangePassword(),u.getCreatedAt(),u.getCustomerClientId());}}
 public record RoleResponse(String roleName,String displayName,String description){static RoleResponse from(Role r){return new RoleResponse(r.getRoleName().name(),r.getDisplayName(),r.getDescription());}}
}
