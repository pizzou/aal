package com.logiplatform.migration;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationConsistencyTest {
    private static final Pattern FILE=Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern INDEX=Pattern.compile("(?i)CREATE\\s+(?:UNIQUE\\s+)?INDEX(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+([a-z0-9_]+)");
    @Test void migrationVersionsAreUniqueAndOrdered(){
        try{Path dir=Paths.get("src/main/resources/db/migration");List<Integer> versions=new ArrayList<>();try(var stream=Files.list(dir)){stream.sorted().forEach(p->{Matcher m=FILE.matcher(p.getFileName().toString());if(m.matches())versions.add(Integer.valueOf(m.group(1)));});}List<Integer> sorted=new ArrayList<>(versions);Collections.sort(sorted);assertEquals(sorted,versions,"Migration versions must be monotonic");assertEquals(versions.size(),new HashSet<>(versions).size(),"Migration versions must be unique");}catch(Exception ex){throw new AssertionError(ex);}
    }
    @Test void indexNamesAreNotDuplicatedWithoutIntentionalCreateIfNotExists(){
        try{Path dir=Paths.get("src/main/resources/db/migration");Map<String,Integer> seen=new HashMap<>();try(var stream=Files.list(dir)){for(Path p:stream.toList()){String sql=Files.readString(p);Matcher m=INDEX.matcher(sql);while(m.find()){String name=m.group(1).toLowerCase(Locale.ROOT);String snippet=sql.substring(Math.max(0,m.start()-30),Math.min(sql.length(),m.end()+10)).toLowerCase(Locale.ROOT);if(!snippet.contains("if not exists"))seen.merge(name,1,Integer::sum);}}}seen.forEach((k,v)->assertTrue(v<=1,"Duplicate index without IF NOT EXISTS: "+k));}catch(Exception ex){throw new AssertionError(ex);}
    }
}
