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