package io.onek8s.dbjava;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The three things this application answers, and there are no others. */
@RestController
public class Routes {

    /**
     * The same platform mark the other two applications serve, for the same
     * reasons: held as a string because the image ships no static content, and
     * linked by the document so no browser falls back to asking for
     * /favicon.ico.
     */
    private static final String FAVICON = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
              <rect width="32" height="32" rx="7" fill="#0b1220"/>
              <path fill="#38bdf8" d="M16 2.5 26.55 7.58 29.16 19 21.86 28.16H10.14L2.84 19 5.45 7.58Z"/>
              <path fill="none" stroke="#0b1220" stroke-width="4.2" stroke-linecap="round"
                    stroke-linejoin="round" d="M11.9 12.2 16 8.6V23.4M10.6 23.4h10.8"/>
            </svg>
            """;

    private final Database database;

    public Routes(Database database) {
        this.database = database;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String page() {
        return Page.render(database.read());
    }

    /**
     * The liveness and readiness probe, and it deliberately does <b>not</b>
     * touch the database: an auto-paused database must not restart the pod or
     * take it out of the Service. A page view is what wakes the database up.
     */
    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String health() {
        return "ok";
    }

    @GetMapping(value = "/favicon.svg", produces = "image/svg+xml;charset=UTF-8")
    public ResponseEntity<String> favicon() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePublic())
                .body(FAVICON);
    }
}
