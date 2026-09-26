package com.logiplatform.integration.cargo_xml;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CargoXmlMessageRegistry {
    private final Map<String,String> handlers=new ConcurrentHashMap<>();
    public void register(String messageType,String handler){handlers.put(messageType,handler);}
    public Optional<String> handler(String messageType){return Optional.ofNullable(handlers.get(messageType));}
    public Map<String,String> all(){return Map.copyOf(handlers);}
}
