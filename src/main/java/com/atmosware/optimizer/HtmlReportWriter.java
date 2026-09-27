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