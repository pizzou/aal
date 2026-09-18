package com.logiplatform.repository;

import com.logiplatform.model.CommercialQuote;


import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface CommercialQuoteRepository extends JpaRepository<CommercialQuote,UUID>{List<CommercialQuote> findAllByTenantIdOrderByQuoteDateDesc(UUID t); Optional<CommercialQuote> findByTenantIdAndQuoteId(UUID t,String id);}
