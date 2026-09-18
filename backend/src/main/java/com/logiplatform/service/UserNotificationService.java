package com.logiplatform.service;
import com.logiplatform.model.UserNotification;
import com.logiplatform.repository.UserNotificationRepository;
import com.logiplatform.tenancy.TenantContext;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant; import java.util.UUID;
@Service
public class UserNotificationService{
 private final UserNotificationRepository repo; public UserNotificationService(UserNotificationRepository repo){this.repo=repo;}
 @Transactional public UserNotification notifyUser(UUID userId,String type,String title,String message,String link){return repo.save(new UserNotification(TenantContext.getTenantId(),userId,type,title,message,link));}
 @Transactional(readOnly=true) public Page<UserNotification> list(UUID userId,Pageable pageable){return repo.findAllByTenantIdAndUserIdOrderByCreatedAtDesc(TenantContext.getTenantId(),userId,pageable);}
 @Transactional(readOnly=true) public long unread(UUID userId){return repo.countByTenantIdAndUserIdAndReadFalse(TenantContext.getTenantId(),userId);}
 @Transactional public void markRead(UUID id,UUID userId){UserNotification n=repo.findByIdAndTenantIdAndUserId(id,TenantContext.getTenantId(),userId).orElseThrow(()->new IllegalArgumentException("Notification not found"));n.setRead(true);n.setReadAt(Instant.now());}
}
