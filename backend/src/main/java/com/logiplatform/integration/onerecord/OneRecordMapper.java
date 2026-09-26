package com.logiplatform.integration.onerecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class OneRecordMapper {
    private final ObjectMapper mapper;
    public OneRecordMapper(ObjectMapper mapper){this.mapper=mapper;}
    public ObjectNode toJsonLd(String type,String id,Map<String,Object> properties,String ontologyVersion){
        ObjectNode root=mapper.createObjectNode(); root.put("@id",id); root.put("@type",type); root.put("aal:ontologyVersion",ontologyVersion); properties.forEach((k,v)->root.set(k,mapper.valueToTree(v))); return root;
    }
    public String reference(JsonNode object){return object.path("@id").asText(null);}
}
