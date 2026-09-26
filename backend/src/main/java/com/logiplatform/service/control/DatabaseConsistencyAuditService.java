package com.logiplatform.service.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import jakarta.persistence.Basic;
import jakarta.persistence.Transient;
import jakarta.persistence.JoinColumn;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.*;

@Service
public class DatabaseConsistencyAuditService {
    private final EntityManager em; private final JdbcTemplate db;
    public DatabaseConsistencyAuditService(EntityManager em, @Qualifier("tenantJdbcTemplate") JdbcTemplate db){this.em=em;this.db=db;}
    public Map<String,Object> audit(){
        List<Map<String,Object>> issues=new ArrayList<>();
        for(var entity:em.getMetamodel().getEntities()){
            Class<?> type=entity.getJavaType(); Entity ann=type.getAnnotation(Entity.class); if(ann==null)continue; Table table=type.getAnnotation(Table.class); if(table==null||table.name().isBlank())continue; String tableName=table.name();
            Set<String> dbCols=new HashSet<>(db.query("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=?",(rs,n)->rs.getString(1),tableName));
            Set<String> javaCols=new HashSet<>();
            for(Field f:allFields(type)){
                if(f.isAnnotationPresent(Transient.class) || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Column c=f.getAnnotation(Column.class); JoinColumn j=f.getAnnotation(JoinColumn.class);
                String expected=null;
                if(c!=null && !c.name().isBlank()) expected=c.name();
                else if(j!=null && !j.name().isBlank()) expected=j.name();
                else if(f.isAnnotationPresent(Id.class)||f.isAnnotationPresent(Version.class)||f.isAnnotationPresent(Basic.class)||isSimple(f.getType())) expected=snake(f.getName());
                if(expected!=null){javaCols.add(expected);if(!dbCols.contains(expected))issues.add(issue(tableName,expected,"JAVA_COLUMN_MISSING_IN_DB",f.getName()));}
            }
            for(String dbCol:dbCols) if(!javaCols.contains(dbCol) && !Set.of("created_at","updated_at","tenant_id").contains(dbCol)) issues.add(issue(tableName,dbCol,"DB_COLUMN_MISSING_IN_ENTITY",type.getSimpleName()));
            if(dbCols.isEmpty())issues.add(issue(tableName,null,"ENTITY_TABLE_MISSING_IN_DB",type.getSimpleName()));
        }
        return Map.of("checkedEntities",em.getMetamodel().getEntities().size(),"issueCount",issues.size(),"issues",issues);
    }
    private static boolean isSimple(Class<?> type){return type.isPrimitive()||type.isEnum()||type==String.class||type==UUID.class||type==java.math.BigDecimal.class||type==java.time.Instant.class||type==java.time.LocalDate.class||type==java.time.LocalDateTime.class||type==java.time.OffsetDateTime.class||type==Boolean.class||type==Integer.class||type==Long.class||type==Short.class||type==Double.class||type==Float.class;}
    private static String snake(String value){return value.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static List<Field> allFields(Class<?> type){List<Field> fields=new ArrayList<>();for(Class<?> c=type;c!=null&&c!=Object.class;c=c.getSuperclass())fields.addAll(Arrays.asList(c.getDeclaredFields()));return fields;}
    private static Map<String,Object> issue(String table,String column,String code,String javaField){Map<String,Object> m=new LinkedHashMap<>();m.put("table",table);m.put("column",column);m.put("code",code);m.put("javaField",javaField);return m;}
}
