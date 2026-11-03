import java.io.File;
import java.util.*;
import java.util.stream.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

public final class JavaSyntaxAudit {
 public static void main(String[] args) throws Exception {
  List<File> files;
  try(var stream = java.nio.file.Files.walk(java.nio.file.Path.of(args[0]))) {
   files = stream.filter(p->p.toString().endsWith(".java")).map(java.nio.file.Path::toFile).toList();
  }
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  DiagnosticCollector<JavaFileObject> errors=new DiagnosticCollector<>();
  try(StandardJavaFileManager manager=compiler.getStandardFileManager(errors,Locale.ROOT,null)) {
   var units=manager.getJavaFileObjectsFromFiles(files);
   JavacTask parser=(JavacTask)compiler.getTask(null,manager,errors, List.of("-proc:none","--release","21"), null, units);
   int total=0;for(var u:parser.parse()) total++;
   List<Diagnostic<? extends JavaFileObject>> bad=errors.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).toList();
   for(var b:bad)System.out.println(b.getSource().getName()+":"+b.getLineNumber()+": "+b.getMessage(Locale.ROOT));
   System.out.println("Java files parsed: "+total+"; syntax errors: "+bad.size());
   if(!bad.isEmpty())System.exit(1);
  }
 }
}
