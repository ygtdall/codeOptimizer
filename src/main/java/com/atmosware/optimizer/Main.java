package com.atmosware.optimizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class Main {

    private static final Path REPORT_DIR = Path.of("Reports");
    private static final int MAX_FILES = 20;
    private static final int LARGE_FILE_LINES = 1500;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("""
                    Kullanım: java -jar codeOptimizer.jar <java-dosyasi|klasor> [mod] [pom.xml]
                    Modlar:   REVIEW (varsayılan), PERFORMANCE, REFACTOR, SECURITY
                    Örnek:    .../owner PERFORMANCE""");
            return;
        }

        Path target = Path.of(args[0]);
        if (!Files.exists(target)) {
            System.err.println("Bulunamadı: " + target.toAbsolutePath());
            return;
        }

        PromptBuilder.Mode mode = PromptBuilder.Mode.REVIEW;
        if (args.length > 1) {
            try {
                mode = PromptBuilder.Mode.valueOf(args[1].toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Unable mod: " + args[1]
                        + " (REVIEW, PERFORMANCE, REFACTOR, SECURITY)");
                return;
            }
        }

        Path pomPath = args.length > 2 ? Path.of(args[2]) : findPom(target);
        if (pomPath == null) {
            System.err.println("pom.xml bulunamadı. Üçüncü parametre olarak yolunu verin.");
            return;
        }

        System.out.println("Mod: " + mode + "\n");

        try {
            if (Files.isDirectory(target)) {
                scanFolder(target, pomPath, mode);
            } else {
                analyze(target, pomPath, mode);
            }
        } catch (Exception e) {
            System.err.println("Hata: " + e.getMessage());
        }
    }

    private static void scanFolder(Path dir, Path pomPath, PromptBuilder.Mode mode) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .limit(MAX_FILES)
                    .toList();
        }

        if (files.isEmpty()) {
            System.err.println("Klasörde .java dosyası yok: " + dir.toAbsolutePath());
            return;
        }

        System.out.println(files.size() + " dosya analiz edilecek.\n");

        List<FileResult> results = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            System.out.println("[" + (i + 1) + "/" + files.size() + "] " + file.getFileName());
            try {
                results.add(analyze(file, pomPath, mode));
            } catch (Exception e) {
                System.err.println("  atlandı: " + e.getMessage());
            }
        }

        Path index = new IndexWriter().write(results, REPORT_DIR);
        System.out.println("\nÖzet: " + index.toAbsolutePath());
    }

    private static FileResult analyze(Path javaPath, Path pomPath, PromptBuilder.Mode mode) throws Exception {
        CodeContext context;
        try {
            context = new CodeAnalyzer().analyze(javaPath, pomPath);
        } catch (Exception e) {
            throw new Exception("kod ayrıştırılamadı: " + e.getMessage());
        }

        if (context.lineCount() > LARGE_FILE_LINES) {
            System.out.println("  uyarı: " + context.lineCount()
                    + " satır, analiz kalitesi düşebilir");
        }

        String source = Files.readString(javaPath);
        PromptBuilder builder = new PromptBuilder();
        String prompt = builder.buildUserPrompt(context, source, mode);

        String answer;
        try {
            answer = new AiClient().ask(builder.buildSystemPrompt(context), prompt);
        } catch (Exception e) {
            throw new Exception("modele ulaşılamadı: " + e.getMessage());
        }

        List<Finding> findings;
        try {
            findings = new FindingParser().parse(answer);
        } catch (Exception e) {
            Files.createDirectories(REPORT_DIR);
            Path raw = REPORT_DIR.resolve(context.className() + "-ham-cevap.txt");
            Files.writeString(raw, answer);
            throw new Exception("cevap JSON değil, ham hâli: " + raw.getFileName());
        }

        int score = RiskScore.of(findings);
        System.out.println("  " + findings.size() + " bulgu, risk " + score
                + " (" + RiskScore.label(score) + ")");

        writeReport(context, mode, prompt, answer);
        Path html = new HtmlReportWriter().write(context, source, findings, REPORT_DIR);

        return new FileResult(
                context.className(),
                context.fileName(),
                context.layer(),
                findings.size(),
                score,
                html.getFileName().toString(),
                findings
        );
    }

    private static Path findPom(Path start) {
        Path dir = Files.isDirectory(start) ? start.toAbsolutePath() : start.toAbsolutePath().getParent();
        while (dir != null) {
            Path candidate = dir.resolve("pom.xml");
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    private static void writeReport(CodeContext ctx, PromptBuilder.Mode mode,
                                    String prompt, String answer) throws IOException {
        Files.createDirectories(REPORT_DIR);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = REPORT_DIR.resolve(ctx.className() + "-" + mode + "-" + stamp + ".md");

        String content = """
                # %s — %s

                ## Context
                %s

                ## Model response
                %s

                ## Prompt sent
                %s
                """.formatted(ctx.className(), mode, ctx.summary(), answer, prompt);

        Files.writeString(file, content);
    }
}