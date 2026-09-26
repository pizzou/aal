package com.logiplatform.integration;

import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public class ProviderErrorMapper {
    public String map(Integer status,String body){
        if(status==null)return "PROVIDER_TRANSPORT_ERROR";
        if(status==401||status==403)return "PROVIDER_AUTHENTICATION_ERROR";
        if(status==404)return "PROVIDER_NOT_FOUND";
        if(status==409)return "PROVIDER_CONFLICT";
        if(status==429)return "PROVIDER_RATE_LIMITED";
        if(status>=500)return "PROVIDER_SERVER_ERROR";
        String b=body==null?"":body.toLowerCase(Locale.ROOT);
        if(b.contains("capacity"))return "PROVIDER_CAPACITY_ERROR";
        if(b.contains("invalid"))return "PROVIDER_VALIDATION_ERROR";
        return "PROVIDER_REQUEST_REJECTED";
    }
}
