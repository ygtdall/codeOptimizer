package com.atmosware.optimizer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.SynchronizedStmt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CodeAnalyzer {

    private static final List<String> VERSION_TAGS =
            List.of("maven.compiler.release", "maven.compiler.source", "java.version");

    public CodeContext analyze(Path javaFile, Path pomFile) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(javaFile);
        String source = Files.readString(javaFile);

        boolean spring = hasImport(cu, "org.springframework");
        boolean jpa = hasImport(cu, "jakarta.persistence")
                || hasImport(cu, "javax.persistence")
                || hasImport(cu, "org.hibernate");
        boolean concurrency = hasImport(cu, "java.util.concurrent") || hasSynchronized(cu);
        boolean test = hasImport(cu, "org.junit");

        String className = cu.getPrimaryTypeName()
                .orElse(javaFile.getFileName().toString().replace(".java", ""));

        Set<String> frameworks = new LinkedHashSet<>();
        if (spring) frameworks.add("Spring");
        if (jpa) frameworks.add("JPA/Hibernate");
        if (test) frameworks.add("JUnit");

        return new CodeContext(
                javaFile.getFileName().toString(),
                className,
                readJavaVersion(pomFile),
                (int) source.lines().count(),
                countMethods(cu),
                longestMethod(cu),
                detectLayer(cu),
                spring, jpa, concurrency, test,
                frameworks
        );
    }

    private boolean hasSynchronized(CompilationUnit cu) {
        boolean block = !cu.findAll(SynchronizedStmt.class).isEmpty();
        boolean method = cu.findAll(MethodDeclaration.class).stream()
                .anyMatch(MethodDeclaration::isSynchronized);
        return block || method;
    }

    private int countMethods(CompilationUnit cu) {
        return cu.findAll(MethodDeclaration.class).size();
    }

    private int longestMethod(CompilationUnit cu) {
        return cu.findAll(MethodDeclaration.class).stream()
                .mapToInt(m -> m.getEnd().get().line - m.getBegin().get().line + 1)
                .max()
                .orElse(0);
    }

    private boolean hasImport(CompilationUnit cu, String prefix) {
        return cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().startsWith(prefix));
    }

    private CodeContext.Layer detectLayer(CompilationUnit cu) {
        if (hasAnnotation(cu, "RestController") || hasAnnotation(cu, "Controller")) return CodeContext.Layer.CONTROLLER;
        if (hasAnnotation(cu, "Service")) return CodeContext.Layer.SERVICE;
        if (hasAnnotation(cu, "Repository") || extendsRepository(cu)) return CodeContext.Layer.REPOSITORY;
        if (hasAnnotation(cu, "Entity")) return CodeContext.Layer.ENTITY;
        return CodeContext.Layer.UNKNOWN;
    }

    private boolean extendsRepository(CompilationUnit cu) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(c -> c.getExtendedTypes().stream())
                .anyMatch(t -> t.getNameAsString().endsWith("Repository"));
    }

    private boolean hasAnnotation(CompilationUnit cu, String name) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(cls -> cls.getAnnotationByName(name).isPresent());
    }

    private String readJavaVersion(Path pomFile) throws IOException {
        String pom = Files.readString(pomFile);
        for (String tag : VERSION_TAGS) {
            String value = between(pom, "<" + tag + ">", "</" + tag + ">");
            if (value != null) return value;
        }
        return "Unknown";
    }

    private String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start == -1) return null;
        int valueStart = start + open.length();
        int valueEnd = text.indexOf(close, valueStart);
        if (valueEnd == -1) return null;
        return text.substring(valueStart, valueEnd).trim();
    }
}