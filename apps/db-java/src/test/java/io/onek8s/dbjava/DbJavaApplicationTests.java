package io.onek8s.dbjava;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.CredentialUnavailableException;
import com.microsoft.sqlserver.jdbc.SqlAuthenticationToken;
import io.onek8s.dbjava.data.EntraTokenCallback;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

/**
 * What can be asserted without an Azure subscription — which is more than it
 * looks, because the three things most likely to be got wrong here all fail
 * without one:
 *
 * <ul>
 *   <li>a context that opens a connection while it starts, which would put the
 *       pod in CrashLoopBackOff whenever the database is asleep;</li>
 *   <li>a missing HTTP transport for the token request, which is invisible
 *       until the first page view;</li>
 *   <li>a page that renders an unreachable database as a stack trace.</li>
 * </ul>
 *
 * The round trip through Azure SQL is the part no test here can stand in for;
 * apps/db-java/README.md says how to run it against a real database.
 */
@SpringBootTest
class DbJavaApplicationTests {

    @Autowired
    private DataSource dataSource;

    /**
     * The context starts with no database reachable at all: the pool is not
     * primed, and Hibernate takes its dialect from configuration rather than
     * from the server. A paused database, or a tenant whose database user has
     * not been created yet, must leave a pod that starts and explains itself.
     */
    @Test
    void startsWithNoDatabaseInReach() {
        assertThat(dataSource).isNotNull();
    }

    /**
     * azure-identity finds its HTTP transport through the ServiceLoader, so
     * dropping the wrong dependency leaves an application that builds, starts,
     * and then cannot mint a token. The pom excludes Netty and supplies the
     * JDK's client in its place; this is the assertion that keeps that swap
     * honest.
     */
    @Test
    void hasAnHttpTransportForTheTokenRequest() {
        assertThat(com.azure.core.http.HttpClient.createDefault()).isNotNull();
    }

    /**
     * The token the driver is handed is the one the credential minted, for the
     * audience the server asked for — the SQL service rather than a database,
     * and derived from the server's own answer so that a sovereign cloud needs
     * no change here.
     */
    @Test
    void handsTheDriverTheCredentialsToken() {
        List<List<String>> requested = new ArrayList<>();
        OffsetDateTime expiry = OffsetDateTime.now().plusHours(1);

        TokenCredential credential = request -> {
            requested.add(request.getScopes());

            return Mono.just(new AccessToken("a-token", expiry));
        };

        SqlAuthenticationToken token = new EntraTokenCallback(credential)
                .getAccessToken("https://database.windows.net/", "https://login.microsoftonline.com/tenant");

        assertThat(token.getAccessToken()).isEqualTo("a-token");
        assertThat(token.getExpiresOn().getTime()).isEqualTo(expiry.toInstant().toEpochMilli());
        assertThat(requested).containsExactly(List.of("https://database.windows.net/.default"));
    }

    /**
     * A failure the server reported is diagnosed by its number, whatever it is
     * wrapped in on the way up — and it is wrapped twice, by Hibernate and then
     * by Spring. 18456 is the one an operator meets on a tenant whose bootstrap
     * has not run.
     */
    @Test
    void readsTheServersErrorNumberThroughTheWrappers() {
        RuntimeException wrapped = new IllegalStateException("Could not open JPA EntityManager for transaction",
                new SQLException("Login failed for user '<token-identified principal>'.", "28000", 18456));

        assertThat(Database.classify(wrapped).problem()).isEqualTo("no database user");
    }

    /**
     * And a failure that happens <em>before</em> any SQL is sent has no number
     * at all. Hikari answers a connection it could not create with a pool
     * timeout and hangs the real exception off it as a cause, so a pod whose
     * identity Entra ID will not issue a token for would otherwise report
     * "the pool timed out" — which names neither the problem nor its fix.
     */
    @Test
    void namesAMissingTokenRatherThanThePoolTimeoutThatCarriesIt() {
        RuntimeException wrapped = new IllegalStateException("Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException(
                        "visits - Connection is not available, request timed out after 30001ms", "08S01",
                        new CredentialUnavailableException("no managed identity is available in this environment")));

        Database.Result result = Database.classify(wrapped);

        assertThat(result.problem()).isEqualTo("no token");
        assertThat(result.detail()).contains("no managed identity is available in this environment");
    }

    /**
     * Every state the page can be in is a rendered page. "no database user" is
     * the one an operator meets most often — a foundation applied, the tenants
     * deploy not yet run — and it has to arrive as the sentence that names the
     * fix.
     */
    @Test
    void rendersAnUnreachableDatabaseAsAPage() {
        String html = Page.render(new Database.Result(null, "no database user", "Run Deploy Tenants."));

        assertThat(html).contains("no database user")
                        .contains("Run Deploy Tenants.")
                        .contains("no rows &mdash; the database was not reachable")
                        .doesNotContain("Exception");
    }

    /** And nothing a database returns is written into that page unescaped. */
    @Test
    void escapesWhatTheDatabaseReturns() {
        String html = Page.render(new Database.Result(null, "unavailable", "<script>alert(1)</script>"));

        assertThat(html).doesNotContain("<script>alert(1)</script>")
                        .contains("&lt;script&gt;");
    }
}
