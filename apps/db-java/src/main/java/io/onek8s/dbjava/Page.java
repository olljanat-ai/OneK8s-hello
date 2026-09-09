package io.onek8s.dbjava;

import io.onek8s.dbjava.data.Visit;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.SpringBootVersion;
import org.springframework.web.util.HtmlUtils;

/**
 * The page, built as a string for the same reason db-hello builds one: the
 * image ships no static content and one file is easier to compare with the
 * other application's than a template plus a model plus a view resolver.
 * Deliberately the same layout, so the two can be opened side by side and the
 * only differences are the ones that are real.
 */
final class Page {

    /**
     * Held apart from the document rather than inlined, and not for tidiness:
     * the page is assembled with {@link String#formatted}, and CSS is full of
     * per-cent signs that a format string would read as conversions. Passing
     * the stylesheet as a value leaves it alone.
     */
    private static final String STYLE = """
            :root { color-scheme: dark; }
            body { margin: 0; padding: 3rem 1.5rem; background: #0b1220; color: #e2e8f0;
                   font-family: system-ui, -apple-system, "Segoe UI", sans-serif; line-height: 1.5; }
            main { max-width: 46rem; margin: 0 auto; }
            h1 { margin: 0 0 2rem; font-size: 1.6rem; font-weight: 600; }
            h2 { margin: 2.5rem 0 .8rem; font-size: 1rem; font-weight: 600; color: #94a3b8; }
            dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: .6rem 1.5rem; margin: 0; }
            dt { color: #94a3b8; }
            dd { margin: 0; overflow-wrap: anywhere;
                   font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
            table { width: 100%; border-collapse: collapse;
                   font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .9rem; }
            th { text-align: left; color: #94a3b8; font-weight: 400; font-family: system-ui, sans-serif; }
            th, td { padding: .35rem .75rem .35rem 0; border-bottom: 1px solid #1e293b; overflow-wrap: anywhere; }
            .pending { color: #fbbf24; font-family: inherit; }
            .note { color: #64748b; font-size: .85rem; margin: .4rem 0 0; }
            footer { margin-top: 2.5rem; color: #64748b; font-size: .85rem; }
            """;

    /** "2026-09-09 05:12:33Z" — what .NET's "u" format produces, so the two tables read alike. */
    private static final DateTimeFormatter VISITED_AT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss'Z'");

    private Page() {
    }

    static String render(Database.Result result) {
        Database.Reading reading = result.reading();

        // Already-encoded HTML either way: the database user's name, or the
        // reason there is not one to show.
        String user = reading == null
                ? "<span class=\"pending\">" + encode(result.problem()) + " &mdash; " + encode(result.detail()) + "</span>"
                : encode(reading.identity().user());

        StringBuilder visits = new StringBuilder();

        if (reading == null) {
            visits.append("<tr><td colspan=\"3\" class=\"pending\">no rows &mdash; the database was not reachable</td></tr>");
        } else if (reading.visits().isEmpty()) {
            visits.append("<tr><td colspan=\"3\" class=\"pending\">no rows yet</td></tr>");
        } else {
            for (Visit visit : reading.visits()) {
                visits.append("<tr><td>")
                      .append(encode(visit.getVisitedAt() == null ? "" : VISITED_AT.format(visit.getVisitedAt())))
                      .append("</td><td>").append(encode(visit.getPod()))
                      .append("</td><td>").append(encode(visit.getCloud()))
                      .append("</td></tr>");
            }
        }

        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>OneK8s db-java</title>
                <link rel="icon" href="/favicon.svg" type="image/svg+xml">
                <style>
                %s
                </style>
                </head>
                <body>
                <main>
                  <h1>%s</h1>
                  <dl>
                    <dt>Cloud</dt><dd>%s</dd>
                    <dt>Environment</dt><dd>%s</dd>
                    <dt>Namespace</dt><dd>%s</dd>
                    <dt>Pod</dt><dd>%s</dd>
                  </dl>
                  <h2>Azure SQL, without a password</h2>
                  <dl>
                    <dt>Server</dt><dd>%s</dd>
                    <dt>Database</dt><dd>%s</dd>
                    <dt>Workload identity</dt><dd>%s</dd>
                    <dt>Signed in as</dt><dd>%s</dd>
                    <dt>Database user</dt><dd>%s</dd>
                    <dt>Database roles</dt><dd>%s</dd>
                  </dl>
                  <h2>Last 10 page views, read back through Hibernate</h2>
                  <table>
                    <thead><tr><th>Visited (UTC)</th><th>Pod</th><th>Cloud</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                  <p class="note">One table, two applications: db-hello owns the schema and this one shares it,
                  so rows written by either appear here. The pod name says which wrote them.</p>
                  <footer>Served by Java %s and Spring Boot %s. Every page view is a row.</footer>
                </main>
                </body>
                </html>
                """.formatted(
                STYLE.strip(),
                encode(Env.value("WELCOME_MESSAGE", "Welcome to OneK8s!")),
                encode(Env.value("CLOUD", "unknown")),
                encode(Env.value("ENVIRONMENT", "unknown")),
                encode(Env.value("POD_NAMESPACE", "unknown")),
                encode(Env.value("POD_NAME", System.getenv().getOrDefault("HOSTNAME", "unknown"))),
                encode(Env.value("SQL_SERVER", "unset")),
                encode(Env.value("SQL_DATABASE", "unset")),
                encode(Env.value("AZURE_CLIENT_ID", "none injected")),
                encode(reading == null ? "" : reading.identity().login()),
                user,
                encode(reading == null ? "" : reading.identity().roles()),
                visits.toString(),
                encode(System.getProperty("java.version")),
                encode(SpringBootVersion.getVersion()));
    }

    private static String encode(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
