# codeOptimizer

Java kaynak dosyasını analiz edip, dosyanın içeriğine göre **dinamik prompt** üreten,
bunu Gemini API'ye gönderen ve dönen bulguları satır satır işaretlenmiş HTML rapora
çeviren komut satırı aracı.

Atmosware staj projesi — Yiğit Dal, 17.08.2026 – 18.09.2026.

---

## Neden

Kod incelemesi için dil modeli kullanırken tek bir sabit prompt her dosyaya uymuyor.
Bir servis sınıfına transaction ve N+1 sorgu sormak anlamlı; dört alanlı bir DTO'ya aynı
soruları sorduğunda model "bu geçerli değil" demek yerine spekülatif bir cevap üretiyor.
Birkaç böyle bulgu, doğru bulgulara olan güveni de yok ediyor.

Bu araç prompt'u dosyanın kendisinden üretir: dosyada JPA yoksa N+1 maddesi prompt'a
hiç girmez, entity ise `equals`/`hashCode` maddesi eklenir.

---

## Akış

```
Java dosyası veya klasör + pom.xml
    │
    ├─ CodeAnalyzer     JavaParser ile AST → CodeContext
    │                   (katman, framework'ler, satır/metot sayısı, Java sürümü)
    ├─ PromptBuilder    bağlama göre koşullu kontrol listesi + numaralı kaynak kod
    ├─ AiClient         Gemini API (google-genai SDK), retry + backoff
    ├─ FindingParser    JSON cevap → List<Finding>
    ├─ RiskScore        ağırlıklı puan → HIGH / MEDIUM / LOW / CLEAN
    └─ HtmlReportWriter + IndexWriter → Reports/
```

---

## Kurulum ve kullanım

Gereksinimler: JDK 26, Maven.

```bash
export GOOGLE_API_KEY=<gemini-api-key>     # kodda tutulmaz, ortam değişkeninden okunur
mvn clean package
```

```bash
java -jar target/codeOptimizer-1.0-SNAPSHOT.jar <java-dosyasi|klasor> [mod] [pom.xml]
```

| Argüman | Zorunlu | Açıklama |
|---|---|---|
| 1 | evet | analiz edilecek `.java` dosyası ya da klasör |
| 2 | hayır | `REVIEW` (varsayılan), `PERFORMANCE`, `REFACTOR`, `SECURITY` |
| 3 | hayır | `pom.xml` yolu; verilmezse dosyadan yukarı doğru aranır |

Örnek:

```bash
java -jar target/codeOptimizer-1.0-SNAPSHOT.jar \
     ~/spring-petclinic/src/main/java/org/springframework/samples/petclinic/owner \
     PERFORMANCE
```

Klasör verildiğinde alt klasörler de gezilir; `/test/` altındaki dosyalar ve
`package-info.java` elenir, en fazla 20 dosya işlenir (`MAX_FILES`).
Ayrıştırılamayan dosya `atlandı:` mesajıyla geçilir, tarama durmaz.

---

## Çıktı

Hepsi `Reports/` klasörüne yazılır:

| Dosya | İçerik |
|---|---|
| `<Sınıf>.html` | kaynak kod, bulgulu satırlar önem derecesine göre renkli, yanında açıklama kartı |
| `<Sınıf>-<MOD>-<zaman>.md` | bağlam özeti, modelin ham cevabı, gönderilen prompt |
| `index.html` | taranan bütün dosyalar: sınıf, katman, bulgu sayısı, risk etiketi |
| `<Sınıf>-ham-cevap.txt` | yalnızca cevap JSON olarak ayrıştırılamadıysa |

---

## Sınıflar

### CodeContext

Dosya hakkında bilinen her şeyi tutan `record`. On iki alan: dosya ve sınıf adı, Java
sürümü, satır sayısı, metot sayısı, en uzun metot uzunluğu, katman (`Layer` enum'ı),
Spring / JPA / eşzamanlılık / test bayrakları ve bulunan framework kümesi.
`summary()` metodu bu bilgiyi düz metne çevirir; o metin doğrudan prompt'a gömülür.

**`CodeContext.java`**

```java
package com.atmosware.optimizer;

import java.util.Set;

public record CodeContext(
        String fileName,
        String className,
        String javaVersion,
        int lineCount,
        int methodCount,
        int longestMethodLines,
        Layer layer,
        boolean spring,
        boolean jpa,
        boolean concurrency,
        boolean test,
        Set<String> frameworks
) {
    public enum Layer { CONTROLLER, SERVICE, REPOSITORY, ENTITY, DTO, TEST, UNKNOWN }

    public String summary() {
        return """
                File: %s
                Class: %s
                Java Version: %s
                Line Count: %d
                Method Count: %d
                Longest Method: %d lines
                Layer: %s
                Detected Techs: %s
                """.formatted(fileName, className, javaVersion, lineCount, methodCount,
                longestMethodLines, layer,
                frameworks.isEmpty() ? "none" : String.join(", ", frameworks));
    }
}
```
### CodeAnalyzer

`CodeContext`'i üreten sınıf. Dosyayı `StaticJavaParser.parse` ile AST'ye çevirir ve
metin yerine ağacı sorgular — bu sayede yorum satırındaki `@Service` gerçek annotation
sanılmaz.

- **Katman tespiti:** `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Entity`.
  Spring Data repository arayüzleri annotation taşımaz, o yüzden `extendsRepository()`
  ile extend edilen tipin adına da bakılır.
- **Metrikler:** metot sayısı; en uzun metot, bildirimlerin başlangıç ve bitiş satırından.
- **Java sürümü:** `pom.xml` içinde `maven.compiler.release`, `maven.compiler.source`,
  `java.version` etiketleri sırayla denenir.

**`CodeAnalyzer.java`**

```java
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
```
### PromptBuilder

Projenin dinamik kısmı. System prompt ve dört mod prompt'u
`src/main/resources/prompts/` altında ayrı dosyalarda; metinleri değiştirmek için kodu
derlemek gerekmez. Yer tutucular (`{{context}}`, `{{checklist}}`, `{{code}}`) doldurulur.

Kontrol listesi koşullu kurulur — `checklist()` metodunda, aşağıdaki tam kodda görülebilir.

Kaynak kod modele her satırın başına numarası yazılarak gönderilir (`numbered()`).
Bu eklenmeden önce model satırları kendi sayıyor ve yanlış satır gösteriyordu.

**`PromptBuilder.java`**

```java
package com.atmosware.optimizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PromptBuilder {

    public enum Mode {
        REVIEW("review.md"),
        PERFORMANCE("performance.md"),
        REFACTOR("refactor.md"),
        SECURITY("security.md");

        private final String file;

        Mode(String file) {
            this.file = file;
        }
    }

    public String buildSystemPrompt(CodeContext context) throws IOException {
        return load("system.md").replace("{{context}}", context.summary());
    }

    public String buildUserPrompt(CodeContext context, String source, Mode mode) throws IOException {
        return load(mode.file)
                .replace("{{checklist}}", formatChecklist(context, mode))
                .replace("{{code}}", numbered(source));
    }

    private String load(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/prompts/" + name)) {
            if (in == null) throw new IOException("Prompt dosyası bulunamadı: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String numbered(String source) {
        StringBuilder sb = new StringBuilder();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    private String formatChecklist(CodeContext context, Mode mode) {
        StringBuilder sb = new StringBuilder();
        for (String item : checklist(context, mode)) {
            sb.append("- ").append(item).append("\n");
        }
        return sb.toString();
    }

    private List<String> checklist(CodeContext context, Mode mode) {
        List<String> items = new ArrayList<>();

        if (mode == Mode.REVIEW || mode == Mode.PERFORMANCE) {
            items.add("Collection choice: List.contains instead of Set, wrong initial capacity");
            items.add("Unnecessary intermediate collections and autoboxing");
            items.add("String concatenation inside loops");
            if (context.jpa()) {
                items.add("JPA: N+1 query risk, FetchType.EAGER usage, @Transactional scope");
            }
            if (context.concurrency()) {
                items.add("Concurrency: shared mutable state, synchronization scope");
            }
        }

        if (mode == Mode.REVIEW || mode == Mode.REFACTOR) {
            items.add("Resource management: try-with-resources, unclosed streams");
            items.add("Exception handling: swallowed catch blocks, exceptions used for flow control");
            if (context.spring()) {
                items.add("Spring: field injection instead of constructor injection");
            }
            if (context.longestMethodLines() > 30) {
                items.add("This class has a " + context.longestMethodLines()
                        + "-line method; check whether it does more than one thing");
            }
            if (context.methodCount() > 15) {
                items.add("This class has " + context.methodCount()
                        + " methods; check for single-responsibility violations");
            }
        }

        if (mode == Mode.SECURITY) {
            items.add("Input validation on request parameters and path variables");
            items.add("String concatenation used to build queries or file paths");
            items.add("Hardcoded credentials, tokens or connection strings");
            items.add("Sensitive data written to logs or exception messages");
            if (context.layer() == CodeContext.Layer.CONTROLLER) {
                items.add("Missing authorization checks on endpoints");
            }
        }

        switch (context.layer()) {
            case CONTROLLER -> {
                items.add("Controller: returning entities directly instead of DTOs");
                items.add("Controller: missing pagination on list endpoints");
                items.add("Controller: business logic that belongs in a service");
            }
            case SERVICE -> items.add("Service: transaction boundaries, orchestration logic mixed with data access");
            case REPOSITORY -> items.add("Repository: unbounded queries, missing projections, findAll on large tables");
            case ENTITY -> {
                items.add("Entity: equals/hashCode consistency, mutable identifiers");
                items.add("Entity: bidirectional relationships causing infinite loops");
            }
            case TEST -> items.add("Test: missing assertions, interdependent tests, over-mocking");
            default -> { }
        }

        return items;
    }
}
```
### AiClient

Modelle konuşan tek sınıf. API key `GOOGLE_API_KEY` ortam değişkeninden okunur,
kodda yer almaz.

- `temperature = 0.1` — varsayılan değerde aynı dosya bir çalıştırmada 1, diğerinde
  3 bulgu döndürebiliyordu.
- `maxOutputTokens = 8000`
- 3 deneme, ilk bekleme 2 sn, her denemede iki katına çıkar.
  Yalnızca geçici hatalar tekrarlanır: `503`, `UNAVAILABLE`, `OVERLOADED`, `429`,
  `RESOURCE_EXHAUSTED`. Hatalı API key gibi durumlar anında fırlatılır.

**`AiClient.java`**

```java
package com.atmosware.optimizer;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

public class AiClient {

    private static final int MAX_ATTEMPTS = 3;
    private static final long FIRST_WAIT_MS = 2000;

    private final Client client = new Client();

    public String ask(String systemPrompt, String userPrompt) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature(0.1f)
                .maxOutputTokens(8000)
                .build();

        RuntimeException last = null;
        long wait = FIRST_WAIT_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                GenerateContentResponse response = client.models.generateContent(
                        "gemini-3.1-flash-lite",
                        systemPrompt + "\n\n" + userPrompt,
                        config
                );
                return response.text();
            } catch (RuntimeException e) {
                last = e;
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) throw e;

                System.out.println("  model meşgul, " + (wait / 1000) + " sn sonra tekrar denenecek ("
                        + attempt + "/" + (MAX_ATTEMPTS - 1) + ")");
                sleep(wait);
                wait = wait * 2;
            }
        }
        throw last;
    }

    private boolean isRetryable(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toUpperCase();
        return message.contains("503")
                || message.contains("UNAVAILABLE")
                || message.contains("OVERLOADED")
                || message.contains("429")
                || message.contains("RESOURCE_EXHAUSTED");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
```
### Finding / FindingParser

`Finding`, tek bir bulgunun kaydı: `line`, `method`, `severity`, `confidence`,
`title`, `detail`. `FindingParser` cevaptaki ilk `[` ile son `]` arasını kesip
Jackson ile `List<Finding>`'e çevirir.

Cevap metin kaldığı sürece yalnızca okunabilir; nesneye çevrildikten sonra sayılabilir,
satıra göre gruplanabilir ve puanlanabilir hale gelir.

**`Finding.java`**

```java
package com.atmosware.optimizer;

public record Finding(
        int line,
        String method,
        String severity,
        String confidence,
        String title,
        String detail
) { }
```

**`FindingParser.java`**

```java
package com.atmosware.optimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public class FindingParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<Finding> parse(String answer) throws Exception {
        String json = clean(answer);
        return mapper.readValue(json, new TypeReference<List<Finding>>() {});
    }

    private String clean(String answer) {
        String t = answer.trim();
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start == -1 || end == -1) throw new IllegalStateException("JSON dizisi bulunamadı");
        return t.substring(start, end + 1);
    }
}
```
### RiskScore

```java
HIGH → 10 puan, MEDIUM → 4 puan, diğer → 1 puan
confidence CONFIRMED değilse puan yarıya iner

>= 20 HIGH   >= 8 MEDIUM   > 0 LOW   0 CLEAN
```

Amaç mutlak bir kalite ölçüsü değil, "hangi dosyaya önce bakmalı" sorusuna hızlı cevap
ve aynı dosyanın iki hâlini karşılaştırabilmek.

**`RiskScore.java`**

```java
package com.atmosware.optimizer;

import java.util.List;

public class RiskScore {

    public static int of(List<Finding> findings) {
        double total = 0;
        for (Finding f : findings) {
            double weight = switch (f.severity().toUpperCase()) {
                case "HIGH" -> 10;
                case "MEDIUM" -> 4;
                default -> 1;
            };
            if (!"CONFIRMED".equalsIgnoreCase(f.confidence())) {
                weight = weight / 2;
            }
            total += weight;
        }
        return (int) Math.round(total);
    }

    public static String label(int score) {
        if (score >= 20) return "HIGH";
        if (score >= 8) return "MEDIUM";
        if (score > 0) return "LOW";
        return "CLEAN";
    }
}
```
### HtmlReportWriter / IndexWriter / FileResult

`HtmlReportWriter` bulguları `Collectors.groupingBy(Finding::line)` ile satıra göre
gruplar, kaynağı satır satır basar ve bulgulu satırları önem derecesine göre renklendirir.
`CONFIRMED` yeşil, `INFERRED` gri rozetle gösterilir.
`IndexWriter` tarama sonuçlarını (`FileResult` kayıtları) tek bir özet tabloda toplar.

---

## Prompt dosyaları

`src/main/resources/prompts/`

| Dosya | İşlev |
|---|---|
| `system.md` | rol, davranış kuralları, `{{context}}` |
| `review.md` | genel inceleme (varsayılan) |
| `performance.md` | performans odaklı |
| `refactor.md` | yapı ve okunabilirlik |
| `security.md` | girdi doğrulama, sır sızıntısı, yetkilendirme |

System prompt'taki kurallar: davranışı değiştirme, yeni bağımlılık önerme, kozmetik
öneri verme, kanıt yoksa spekülasyon yerine bunu açıkça yaz.

Her mod prompt'u modelden **yalnızca JSON dizisi** ister ve her bulgunun
`CONFIRMED` (kodda doğrudan görünüyor) ya da `INFERRED` (görünmeyen bağlama bağlı)
olarak işaretlenmesini şart koşar. Bu ayrım ve kanıt zorunluluğu, şirket içi
Cognia projesinin konvansiyonundan alınmıştır.

### Prompt dosyalarının içeriği

**`src/main/resources/prompts/system.md`**

```markdown
You are a senior Java developer. Your task is to review the given code
without changing its behavior and structure.

Rules:
- Behavior must be preserved exactly; edge cases must not change.
- Do not suggest new dependencies.
- Do not suggest cosmetic changes (naming, formatting, import order).
- If you cannot find evidence for something, state it explicitly
  instead of speculating.
- This code runs in production; only report findings with measurable impact
  or real risk of bugs.
- Give your answers in Turkish.

Context of the analyzed file:
{{context}}
```

**`src/main/resources/prompts/review.md`**

```markdown
# TASK
Review this java class. Do not produce code.
List only findings that have measurable impact or real risk of bugs.

# CHECK SPECIFICALLY FOR
{{checklist}}

# OUTPUT FORMAT
Return ONLY a JSON array, no prose, no markdown fences.
Each element:
{"line": <int>, "method": "<method name>",
"severity": "HIGH|MEDIUM|LOW",
"confidence": "CONFIRMED|INFERRED",
"title": "<short label>", "detail": "<one paragraph in Turkish>"}

Use CONFIRMED only when the finding is directly visible in the code below.
Use INFERRED when it depends on context you cannot see.
The line number must match the line numbers of the code below.

# CODE
{{code}}
```

**`src/main/resources/prompts/performance.md`**

```markdown
# TASK
Review this java class for runtime and memory cost only.
Ignore style, naming and structure unless they cause a measurable cost.
Report only findings where you can name the cost: extra queries, extra
allocations, quadratic loops, blocking calls.

# CHECK SPECIFICALLY FOR
{{checklist}}

# OUTPUT FORMAT
Return ONLY a JSON array, no prose, no markdown fences.
Each element:
{"line": <int>, "method": "<method name>",
"severity": "HIGH|MEDIUM|LOW",
"confidence": "CONFIRMED|INFERRED",
"title": "<short label>", "detail": "<one paragraph in Turkish>"}

Use CONFIRMED only when the finding is directly visible in the code below.
Use INFERRED when it depends on context you cannot see.
The line number must match the line numbers of the code below.

# CODE
{{code}}
```

**`src/main/resources/prompts/refactor.md`**

```markdown
# TASK
Review this java class for readability and maintainability only.
Do not report performance issues.
Look for: methods doing more than one thing, duplicated logic, unclear names,
deep nesting, missing error handling.

# CHECK SPECIFICALLY FOR
{{checklist}}

# OUTPUT FORMAT
Return ONLY a JSON array, no prose, no markdown fences.
Each element:
{"line": <int>, "method": "<method name>",
"severity": "HIGH|MEDIUM|LOW",
"confidence": "CONFIRMED|INFERRED",
"title": "<short label>", "detail": "<one paragraph in Turkish>"}

Use CONFIRMED only when the finding is directly visible in the code below.
Use INFERRED when it depends on context you cannot see.
The line number must match the line numbers of the code below.

# CODE
{{code}}
```

**`src/main/resources/prompts/security.md`**

```markdown
# TASK
Review this java class for security weaknesses only.
Look for: unvalidated input reaching queries or file paths, secrets in code,
missing authorization checks, unsafe deserialization, information leaking
through exception messages or logs.
If you find nothing, return an empty array.

# CHECK SPECIFICALLY FOR
{{checklist}}

# OUTPUT FORMAT
Return ONLY a JSON array, no prose, no markdown fences.
Each element:
{"line": <int>, "method": "<method name>",
"severity": "HIGH|MEDIUM|LOW",
"confidence": "CONFIRMED|INFERRED",
"title": "<short label>", "detail": "<one paragraph in Turkish>"}

Use CONFIRMED only when the finding is directly visible in the code below.
Use INFERRED when it depends on context you cannot see.
The line number must match the line numbers of the code below.

# CODE
{{code}}
```

---

## Testler

```bash
mvn test
```

10 test, hepsi geçiyor.

- `CodeAnalyzerTest` (6): servis katmanı ve Spring tespiti, yorum içindeki
  annotation'ın yok sayılması, annotation'sız `JpaRepository` arayüzünün repository
  sayılması, metot sayısı ve en uzun metot hesabı, alternatif sürüm etiketinden
  Java sürümü okuma, sürüm bulunamadığında `Unknown` dönmesi.
- `RiskScoreTest` (4): ağırlıklandırma, `INFERRED` yarılaması, etiket eşikleri,
  boş listede `CLEAN`.

**`src/test/java/com/atmosware/optimizer/CodeAnalyzerTest.java`**

```java
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
```

**`src/test/java/com/atmosware/optimizer/RiskScoreTest.java`**

```java
package com.atmosware.optimizer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskScoreTest {

    private Finding finding(String severity, String confidence) {
        return new Finding(1, "m", severity, confidence, "t", "d");
    }

    @Test
    void returnsZeroWhenThereAreNoFindings() {
        assertEquals(0, RiskScore.of(List.of()));
        assertEquals("CLEAN", RiskScore.label(0));
    }

    @Test
    void sumsSeverityWeights() {
        int score = RiskScore.of(List.of(
                finding("HIGH", "CONFIRMED"),
                finding("MEDIUM", "CONFIRMED"),
                finding("LOW", "CONFIRMED")));

        assertEquals(15, score);
    }

    @Test
    void halvesTheWeightOfInferredFindings() {
        assertEquals(5, RiskScore.of(List.of(finding("HIGH", "INFERRED"))));
    }

    @Test
    void mapsScoresToLabels() {
        assertEquals("LOW", RiskScore.label(4));
        assertEquals("MEDIUM", RiskScore.label(10));
        assertEquals("HIGH", RiskScore.label(25));
    }
}
```

---

## Doğrulama

Açık kaynaklı **Spring PetClinic** projesinin `owner` paketinde çalıştırıldı:

| | |
|---|---|
| Taranan dosya | 11 |
| Bulgu | 9 |
| Temiz dosya | 4 |
| Toplam risk | 33 |
| Elle doğrulamada yanlış pozitif | 0 |

Bu çalıştırma analizördeki iki hatayı ortaya çıkardı (Java sürümünün bulunamaması ve
repository arayüzlerinin `UNKNOWN` çıkması); ikisi de düzeltildi.

---

## Bilinen sınırlar

- `FindingParser`'ın birim testi yok, coverage ölçülmedi.
- Şemalı structured output kullanılmıyor; bozuk cevap ayrıştırmayı kırar
  (bu durumda ham cevap dosyaya yazılır).
- Kaynak kod modele gönderilmeden önce olası sırlar maskelenmiyor. Gerçek bir
  repoda kullanılmadan önce çözülmeli.
- Risk skoru dosya boyutuna göre normalize edilmiyor: 1000 satırlık dosyadaki
  2 bulgu ile 50 satırlıktakiyle aynı puanı alıyor.
- Hatanın geçici olup olmadığı exception mesajının metnine bakılarak belirleniyor;
  SDK metni değiştirirse retry sessizce devre dışı kalır.
- Log için `System.out` kullanılıyor, SLF4J değil.
- Tek dosya bazlı analiz: sınıflar arası çağrı zincirleri görülmüyor.

## Sonraki adımlar

1. Sır maskeleme (API key, connection string) — göndermeden önce.
2. Şemalı structured output ile JSON garantisi.
3. Token ve maliyet takibi.
4. Aynı dosyanın iki sürümünü karşılaştıran "önce/sonra" raporu.
5. CI entegrasyonu: pull request üzerinde değişen dosyaları tarama.

---

## Bağımlılıklar

| Kütüphane | Sürüm | Niçin |
|---|---|---|
| `com.google.genai:google-genai` | 1.68.0 | Gemini API istemcisi |
| `com.github.javaparser:javaparser-core` | 3.28.2 | AST analizi, derleme gerektirmez |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.2 | JSON → `Finding` |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 | birim testler (test scope) |

Java sürümü: `maven.compiler.release` 26.

**`FileResult.java`**

```java
package com.atmosware.optimizer;

import java.util.List;

public record FileResult(
        String className,
        String fileName,
        CodeContext.Layer layer,
        int findingCount,
        int score,
        String htmlName,
        List<Finding> findings
) { }
```

**`HtmlReportWriter.java`**

```java
package com.atmosware.optimizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HtmlReportWriter {

    public Path write(CodeContext ctx, String source, List<Finding> findings, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(ctx.className() + ".html");
        Files.writeString(file, build(ctx, source, findings));
        return file;
    }

    private String build(CodeContext ctx, String source, List<Finding> findings) {
        Map<Integer, List<Finding>> byLine = findings.stream()
                .collect(Collectors.groupingBy(Finding::line));

        long confirmed = findings.stream()
                .filter(f -> "CONFIRMED".equalsIgnoreCase(f.confidence()))
                .count();

        int score = RiskScore.of(findings);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"tr\"><head><meta charset=\"UTF-8\">");
        sb.append("<title>").append(esc(ctx.className())).append("</title>");
        sb.append(css());
        sb.append("</head><body>");

        sb.append("<header><h1>").append(esc(ctx.className())).append("</h1>");
        sb.append("<p>").append(esc(ctx.fileName())).append(" &middot; Java ")
                .append(esc(ctx.javaVersion())).append(" &middot; ").append(ctx.layer())
                .append(" &middot; ").append(findings.size()).append(" bulgu (")
                .append(confirmed).append(" kanıtlı) &middot; risk skoru ")
                .append(score).append(" (").append(RiskScore.label(score)).append(")</p></header>");

        sb.append("<main>");

        sb.append("<section class=\"code\">");
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            List<Finding> onLine = byLine.get(no);
            String cls = onLine == null ? "" : " class=\"hit " + onLine.get(0).severity().toLowerCase() + "\"";
            sb.append("<div").append(cls).append(" id=\"L").append(no).append("\">");
            sb.append("<span class=\"no\">").append(no).append("</span>");
            sb.append("<span class=\"src\">").append(esc(lines[i])).append("</span>");
            sb.append("</div>");
        }
        sb.append("</section>");

        sb.append("<aside>");
        findings.stream()
                .sorted((a, b) -> rank(a.severity()) - rank(b.severity()))
                .forEach(f -> {
                    sb.append("<a class=\"card ").append(f.severity().toLowerCase())
                            .append("\" href=\"#L").append(f.line()).append("\">");

                    sb.append("<div class=\"head\">");
                    sb.append("<span class=\"badge\">").append(esc(f.severity())).append("</span>");
                    sb.append("<span class=\"conf ").append(confClass(f.confidence())).append("\">")
                            .append(esc(f.confidence())).append("</span>");
                    sb.append("<span class=\"loc\">satır ").append(f.line())
                            .append(" &middot; ").append(esc(f.method())).append("</span>");
                    sb.append("</div>");

                    sb.append("<h3>").append(esc(f.title())).append("</h3>");
                    sb.append("<p>").append(esc(f.detail())).append("</p>");
                    sb.append("</a>");
                });
        sb.append("</aside>");

        sb.append("</main></body></html>");
        return sb.toString();
    }

    private String confClass(String confidence) {
        return "CONFIRMED".equalsIgnoreCase(confidence) ? "ok" : "guess";
    }

    private int rank(String severity) {
        return switch (severity) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String css() {
        return """
                <style>
                :root { --bg:#0f1115; --fg:#e6e6e6; --dim:#8b93a1; --line:#1c2029;
                        --high:#e5484d; --medium:#f5a524; --low:#8b93a1;
                        --ok:#30a46c; --guess:#8b93a1; }
                * { box-sizing:border-box; }
                body { margin:0; background:var(--bg); color:var(--fg);
                       font:14px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace; }
                header { padding:24px 28px; border-bottom:1px solid var(--line); }
                header h1 { margin:0 0 4px; font-size:20px; }
                header p { margin:0; color:var(--dim); font-size:13px; }
                main { display:grid; grid-template-columns:1fr 380px; gap:0; align-items:start; }
                .code { padding:20px 0; overflow-x:auto; }
                .code > div { display:flex; padding:0 28px; white-space:pre; }
                .no { width:44px; flex:none; color:var(--dim); text-align:right; padding-right:16px; }
                .hit { background:#1a1d24; }
                .hit.high { box-shadow:inset 3px 0 var(--high); }
                .hit.medium { box-shadow:inset 3px 0 var(--medium); }
                .hit.low { box-shadow:inset 3px 0 var(--low); }
                aside { padding:20px; border-left:1px solid var(--line);
                        position:sticky; top:0; max-height:100vh; overflow-y:auto; }
                .card { display:block; text-decoration:none; color:inherit;
                        border:1px solid var(--line); border-radius:8px;
                        padding:14px; margin-bottom:12px; }
                .card:hover { border-color:var(--dim); }
                .head { display:flex; flex-wrap:wrap; gap:6px; align-items:center;
                        margin-bottom:8px; }
                .badge { font-size:11px; letter-spacing:.04em; padding:2px 8px;
                         border-radius:99px; border:1px solid currentColor; flex:none; }
                .card.high .badge { color:var(--high); }
                .card.medium .badge { color:var(--medium); }
                .card.low .badge { color:var(--low); }
                .conf { font-size:10px; letter-spacing:.06em; padding:2px 6px;
                        border-radius:4px; background:#1a1d24; flex:none; }
                .conf.ok { color:var(--ok); }
                .conf.guess { color:var(--guess); }
                .loc { font-size:12px; color:var(--dim); margin-left:auto;
                       text-align:right; min-width:0; overflow-wrap:anywhere; }
                .card h3 { margin:0 0 6px; font-size:14px; overflow-wrap:anywhere; }
                .card p { margin:0; font-size:13px; color:var(--dim);
                          font-family:system-ui,sans-serif; }
                @media (max-width:900px) { main { grid-template-columns:1fr; }
                  aside { position:static; max-height:none; border-left:0;
                          border-top:1px solid var(--line); } }
                </style>
                """;
    }
}
```

**`IndexWriter.java`**

```java
package com.atmosware.optimizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class IndexWriter {

    public Path write(List<FileResult> results, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("index.html");
        Files.writeString(file, build(results));
        return file;
    }

    private String build(List<FileResult> results) {
        int totalFindings = results.stream().mapToInt(FileResult::findingCount).sum();
        int totalScore = results.stream().mapToInt(FileResult::score).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"tr\"><head><meta charset=\"UTF-8\">");
        sb.append("<title>Analiz Özeti</title>").append(css()).append("</head><body>");

        sb.append("<header><h1>Analiz Özeti</h1><p>")
                .append(results.size()).append(" dosya &middot; ")
                .append(totalFindings).append(" bulgu &middot; toplam risk ")
                .append(totalScore).append("</p></header>");

        sb.append("<table><thead><tr>")
                .append("<th>Sınıf</th><th>Katman</th><th class=\"n\">Bulgu</th>")
                .append("<th class=\"n\">Risk</th><th>Durum</th>")
                .append("</tr></thead><tbody>");

        results.stream()
                .sorted(Comparator.comparingInt(FileResult::score).reversed())
                .forEach(r -> {
                    sb.append("<tr>");
                    sb.append("<td><a href=\"").append(esc(r.htmlName())).append("\">")
                            .append(esc(r.className())).append("</a></td>");
                    sb.append("<td class=\"dim\">").append(r.layer()).append("</td>");
                    sb.append("<td class=\"n\">").append(r.findingCount()).append("</td>");
                    sb.append("<td class=\"n\">").append(r.score()).append("</td>");
                    sb.append("<td><span class=\"tag ").append(labelClass(r.score())).append("\">")
                            .append(RiskScore.label(r.score())).append("</span></td>");
                    sb.append("</tr>");
                });

        sb.append("</tbody></table>");

        sb.append("<section class=\"all\"><h2>Tüm Bulgular</h2>");
        results.stream()
                .sorted(Comparator.comparingInt(FileResult::score).reversed())
                .filter(r -> !r.findings().isEmpty())
                .forEach(r -> r.findings().stream()
                        .sorted((a, b) -> rank(a.severity()) - rank(b.severity()))
                        .forEach(f -> {
                            sb.append("<a class=\"card ").append(f.severity().toLowerCase())
                                    .append("\" href=\"").append(esc(r.htmlName()))
                                    .append("#L").append(f.line()).append("\">");
                            sb.append("<div class=\"head\">");
                            sb.append("<span class=\"badge\">").append(esc(f.severity())).append("</span>");
                            sb.append("<span class=\"conf\">").append(esc(f.confidence())).append("</span>");
                            sb.append("<span class=\"loc\">").append(esc(r.className()))
                                    .append(" &middot; satır ").append(f.line()).append("</span>");
                            sb.append("</div>");
                            sb.append("<h3>").append(esc(f.title())).append("</h3>");
                            sb.append("<p>").append(esc(f.detail())).append("</p>");
                            sb.append("</a>");
                        }));
        sb.append("</section>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private int rank(String severity) {
        return switch (severity.toUpperCase()) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private String labelClass(int score) {
        if (score >= 20) return "high";
        if (score >= 8) return "medium";
        if (score > 0) return "low";
        return "clean";
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String css() {
        return """
                <style>
                :root { --bg:#0f1115; --fg:#e6e6e6; --dim:#8b93a1; --line:#1c2029;
                        --high:#e5484d; --medium:#f5a524; --low:#8b93a1; --clean:#30a46c; }
                * { box-sizing:border-box; }
                body { margin:0; background:var(--bg); color:var(--fg);
                       font:14px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace; }
                header { padding:24px 28px; border-bottom:1px solid var(--line); }
                header h1 { margin:0 0 4px; font-size:20px; }
                header p { margin:0; color:var(--dim); font-size:13px; }
                table { width:100%; border-collapse:collapse; }
                th, td { text-align:left; padding:10px 28px; border-bottom:1px solid var(--line); }
                th { font-size:12px; color:var(--dim); font-weight:400; letter-spacing:.04em; }
                td a { color:var(--fg); text-decoration:none; border-bottom:1px solid var(--line); }
                td a:hover { border-color:var(--dim); }
                .n { text-align:right; }
                .dim { color:var(--dim); }
                .tag { font-size:11px; padding:2px 8px; border-radius:99px;
                       border:1px solid currentColor; }
                .tag.high { color:var(--high); }
                .tag.medium { color:var(--medium); }
                .tag.low { color:var(--low); }
                .tag.clean { color:var(--clean); }
                .all { padding:28px; }
                .all h2 { font-size:16px; margin:0 0 16px; font-weight:400; color:var(--dim); }
                .card { display:block; text-decoration:none; color:inherit;
                        border:1px solid var(--line); border-radius:8px;
                        padding:14px; margin-bottom:12px; max-width:900px; }
                .card:hover { border-color:var(--dim); }
                .head { display:flex; flex-wrap:wrap; gap:6px; align-items:center;
                        margin-bottom:8px; }
                .badge { font-size:11px; padding:2px 8px; border-radius:99px;
                         border:1px solid currentColor; flex:none; }
                .card.high .badge { color:var(--high); }
                .card.medium .badge { color:var(--medium); }
                .card.low .badge { color:var(--low); }
                .conf { font-size:10px; color:var(--dim); padding:2px 6px;
                        border-radius:4px; background:#1a1d24; flex:none; }
                .loc { font-size:12px; color:var(--dim); margin-left:auto;
                       text-align:right; min-width:0; overflow-wrap:anywhere; }
                .card h3 { margin:0 0 6px; font-size:14px; overflow-wrap:anywhere; }
                .card p { margin:0; font-size:13px; color:var(--dim);
                          font-family:system-ui,sans-serif; }
                </style>
                """;
    }
}
```

---

## Giriş noktası — `Main`

```java
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
```

---

## `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.atmosware</groupId>
    <artifactId>codeOptimizer</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>26</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.google.genai</groupId>
            <artifactId>google-genai</artifactId>
            <version>1.68.0</version>
        </dependency>
        <dependency>
            <groupId>com.github.javaparser</groupId>
            <artifactId>javaparser-core</artifactId>
            <version>3.28.2</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.22.2</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>6.1.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

</project>
```
