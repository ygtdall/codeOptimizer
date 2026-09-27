package com.atmosware.optimizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CodeAnalyzerTest {

    @TempDir
    Path tempDir;

    private final CodeAnalyzer analyzer = new CodeAnalyzer();

    private Path java(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private Path pom(String body) throws IOException {
        Path file = tempDir.resolve("pom.xml");
        Files.writeString(file, "<project><properties>" + body + "</properties></project>");
        return file;
    }

    @Test
    void detectsServiceLayerAndSpring() throws IOException {
        Path file = java("OrderService.java", """
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService { }
                """);

        CodeContext ctx = analyzer.analyze(file, pom("<java.version>17</java.version>"));

        assertEquals(CodeContext.Layer.SERVICE, ctx.layer());
        assertTrue(ctx.spring());
        assertFalse(ctx.jpa());
    }

    @Test
    void ignoresAnnotationsInsideComments() throws IOException {
        Path file = java("Plain.java", """
                // @Service
                public class Plain { }
                """);

        CodeContext ctx = analyzer.analyze(file, pom("<java.version>17</java.version>"));

        assertEquals(CodeContext.Layer.UNKNOWN, ctx.layer());
    }

    @Test
    void treatsJpaRepositoryInterfaceAsRepository() throws IOException {
        Path file = java("OwnerRepository.java", """
                public interface OwnerRepository extends JpaRepository<Owner, Integer> { }
                """);

        CodeContext ctx = analyzer.analyze(file, pom("<java.version>17</java.version>"));

        assertEquals(CodeContext.Layer.REPOSITORY, ctx.layer());
    }

    @Test
    void countsMethodsAndLongestMethodLength() throws IOException {
        Path file = java("Calc.java", """
                public class Calc {
                    int a() {
                        return 1;
                    }
                    int b() {
                        int x = 1;
                        int y = 2;
                        return x + y;
                    }
                }
                """);

        CodeContext ctx = analyzer.analyze(file, pom("<java.version>17</java.version>"));

        assertEquals(2, ctx.methodCount());
        assertEquals(5, ctx.longestMethodLines());
    }

    @Test
    void readsJavaVersionFromAlternativeTag() throws IOException {
        Path file = java("Plain.java", "public class Plain { }");

        CodeContext ctx = analyzer.analyze(file, pom("<maven.compiler.release>21</maven.compiler.release>"));

        assertEquals("21", ctx.javaVersion());
    }

    @Test
    void fallsBackToUnknownWhenVersionIsMissing() throws IOException {
        Path file = java("Plain.java", "public class Plain { }");

        CodeContext ctx = analyzer.analyze(file, pom(""));

        assertEquals("Unknown", ctx.javaVersion());
    }
}