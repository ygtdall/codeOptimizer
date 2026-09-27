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

### PromptBuilder

Projenin dinamik kısmı. System prompt ve dört mod prompt'u
`src/main/resources/prompts/` altında ayrı dosyalarda; metinleri değiştirmek için kodu
derlemek gerekmez. Yer tutucular (`{{context}}`, `{{checklist}}`, `{{code}}`) doldurulur.

Kontrol listesi koşullu kurulur:

```java
if (context.jpa())         items.add("JPA: N+1 query risk, FetchType.EAGER, @Transactional");
if (context.spring())      items.add("Spring: field injection instead of constructor");
if (context.concurrency()) items.add("Concurrency: shared mutable state");

if (context.longestMethodLines() > 30)
    items.add("This class has a " + context.longestMethodLines()
            + "-line method; check whether it does more than one thing");

switch (context.layer()) {
    case ENTITY     -> items.add("equals/hashCode consistency");
    case CONTROLLER -> items.add("returning entities instead of DTOs");
    case REPOSITORY -> items.add("unbounded queries, findAll on large tables");
}
```

Kaynak kod modele her satırın başına numarası yazılarak gönderilir (`numbered()`).
Bu eklenmeden önce model satırları kendi sayıyor ve yanlış satır gösteriyordu.

### AiClient

Modelle konuşan tek sınıf. API key `GOOGLE_API_KEY` ortam değişkeninden okunur,
kodda yer almaz.

- `temperature = 0.1` — varsayılan değerde aynı dosya bir çalıştırmada 1, diğerinde
  3 bulgu döndürebiliyordu.
- `maxOutputTokens = 8000`
- 3 deneme, ilk bekleme 2 sn, her denemede iki katına çıkar.
  Yalnızca geçici hatalar tekrarlanır: `503`, `UNAVAILABLE`, `OVERLOADED`, `429`,
  `RESOURCE_EXHAUSTED`. Hatalı API key gibi durumlar anında fırlatılır.

### Finding / FindingParser

`Finding`, tek bir bulgunun kaydı: `line`, `method`, `severity`, `confidence`,
`title`, `detail`. `FindingParser` cevaptaki ilk `[` ile son `]` arasını kesip
Jackson ile `List<Finding>`'e çevirir.

Cevap metin kaldığı sürece yalnızca okunabilir; nesneye çevrildikten sonra sayılabilir,
satıra göre gruplanabilir ve puanlanabilir hale gelir.

### RiskScore

```java
HIGH → 10 puan, MEDIUM → 4 puan, diğer → 1 puan
confidence CONFIRMED değilse puan yarıya iner

>= 20 HIGH   >= 8 MEDIUM   > 0 LOW   0 CLEAN
```

Amaç mutlak bir kalite ölçüsü değil, "hangi dosyaya önce bakmalı" sorusuna hızlı cevap
ve aynı dosyanın iki hâlini karşılaştırabilmek.

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
