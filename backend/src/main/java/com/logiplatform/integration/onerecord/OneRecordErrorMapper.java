package com.logiplatform.integration.onerecord;

import org.springframework.stereotype.Component;

@Component
public class OneRecordErrorMapper {
    public String map(Throwable error){if(error==null)return "UNKNOWN";String m=error.getMessage()==null?"":error.getMessage().toLowerCase();if(m.contains("401")||m.contains("403"))return "AUTHORIZATION_ERROR";if(m.contains("429"))return "RATE_LIMITED";if(m.contains("timeout"))return "TIMEOUT";if(m.contains("invalid json"))return "INVALID_RESPONSE";return "PROVIDER_ERROR";}
}
