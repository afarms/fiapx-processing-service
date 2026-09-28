package br.com.fiap.fiapx.processing.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreIsolationTest {
    @TempDir Path temporary;

    @Test
    void coreCompilesWithJdkOnlyAndNoFrameworkOrInfrastructureOnClasspath() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require JDK 21");
        Path source = Path.of("src/main/java/br/com/fiap/fiapx/processing/core");
        Path emptyClasspath = Files.createDirectory(temporary.resolve("empty-classpath"));
        Path output = Files.createDirectory(temporary.resolve("classes"));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = Files.walk(source); var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertFalse(sources.isEmpty(), "Core sources must exist");
            var units = manager.getJavaFileObjectsFromPaths(sources);
            var options = List.of("--release", "21", "-proc:none", "-encoding", "UTF-8",
                    "-classpath", emptyClasspath.toString(), "-sourcepath", emptyClasspath.toString(),
                    "-d", output.toString());
            assertTrue(compiler.getTask(null, manager, diagnostics, options, null, units).call(),
                    () -> "Core depends on external classes: " + diagnostics.getDiagnostics());
        }
    }
}
