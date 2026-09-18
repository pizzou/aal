package com.logiplatform.repository;

import com.logiplatform.model.DocumentTemplate;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate,UUID>{List<DocumentTemplate> findAllByTenantIdAndActiveTrueOrderByTemplateCodeAsc(UUID t);}
