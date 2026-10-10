import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parse Java without resolving Android types; this is not an Android compile. */
public final class CheckJavaSyntax {
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length == 0 ? "app/src" : args[0]);
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK with jdk.compiler is required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var units = manager.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-source", "17", "-proc:none"), null, units);
            task.parse();
            boolean failed = false;
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    System.err.println(diagnostic);
                    failed = true;
                }
            }
            if (failed) System.exit(1);
            System.out.println("Java 17 syntax valid: " + files.size() + " files (Android symbols not compiled).");
        }
    }
}
