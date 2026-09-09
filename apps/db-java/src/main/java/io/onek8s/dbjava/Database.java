package io.onek8s.dbjava;

import com.azure.core.exception.ClientAuthenticationException;
import io.onek8s.dbjava.data.DatabaseIdentity;
import io.onek8s.dbjava.data.DatabaseIdentityQuery;
import io.onek8s.dbjava.data.Visit;
import io.onek8s.dbjava.data.VisitRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The round trip the page is made of: record this view, then read back who the
 * database thinks we are and the last few views.
 *
 * <p>The insert is what proves the identity holds {@code db_datawriter} and not
 * merely read access — and those rows are the only state this application has.
 * They are also db-hello's rows: one table, two applications, and a page from
 * either one shows what the other wrote.
 */
@Service
public class Database {

    /** What the page can say about the database. */
    public record Result(Reading reading, String problem, String detail) {
    }

    public record Reading(DatabaseIdentity identity, List<Visit> visits) {
    }

    /**
     * Azure SQL's transient errors — the ones worth trying again rather than
     * reporting. 40613 is the one that matters here: what a free-offer database
     * answers while it wakes from auto-pause.
     *
     * <p>db-hello gets this list and the loop around it for free, from EF
     * Core's retrying execution strategy. Hibernate has no equivalent, so it is
     * written out: the same behaviour, visibly rather than by configuration.
     */
    private static final Set<Integer> TRANSIENT = Set.of(
            4060, 40197, 40501, 40613, 49918, 49919, 49920, 10928, 10929, 1205, 233, 64, 20);

    private static final int MAX_ATTEMPTS = 3;

    private final VisitRepository visits;

    private final DatabaseIdentityQuery whoAmI;

    public Database(VisitRepository visits, DatabaseIdentityQuery whoAmI) {
        this.visits = visits;
        this.whoAmI = whoAmI;
    }

    public Result read() {
        if (Env.value("SQL_SERVER") == null || Env.value("SQL_DATABASE") == null) {
            return new Result(null, "not configured",
                    "SQL_SERVER and SQL_DATABASE are unset; the chart requires both.");
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return new Result(roundTrip(), null, null);
            } catch (RuntimeException e) {
                SQLException sql = failure(e, true);

                if (sql != null && TRANSIENT.contains(sql.getErrorCode()) && attempt < MAX_ATTEMPTS && backOff(attempt)) {
                    continue;
                }

                return classify(e);
            }
        }
    }

    /**
     * What went wrong, in the words the page prints — and the reason this is a
     * method of its own is that everything above it needs a database and this
     * does not.
     *
     * <p>The order is the order the causes nest in. A failure the server
     * reported has a number, and the number is the whole diagnosis. A failure
     * that happened before any SQL was sent has none: the token request is the
     * likeliest, and it arrives buried, because Hikari answers a connection it
     * could not create with a pool timeout and hangs the real exception off it
     * as a cause.
     */
    static Result classify(RuntimeException e) {
        SQLException numbered = failure(e, true);

        if (numbered != null) {
            return explain(numbered);
        }

        if (authentication(e) instanceof ClientAuthenticationException credential) {
            return new Result(null, "no token",
                    "Entra ID would not issue a token for this pod's identity. " + credential.getMessage());
        }

        SQLException any = failure(e, false);

        return new Result(null, "unavailable", any != null ? any.getMessage() : rootCause(e).getMessage());
    }

    private Reading roundTrip() {
        visits.saveAndFlush(new Visit(
                Env.value("POD_NAME", hostname()),
                Env.value("CLOUD", "unknown")));

        return new Reading(whoAmI.run(), visits.findTop10ByOrderByIdDesc());
    }

    /**
     * Spring wraps a failed query in a DataAccessException and Hikari wraps the
     * driver's own failure again, so the server's error number — the part that
     * says what to do about it — is one or two levels down.
     *
     * @param numbered look only for a failure the <em>server</em> reported.
     *                 Everything the client failed at on its own — a pool that
     *                 timed out, a certificate it would not trust — is also a
     *                 SQLException, and carries error number 0.
     */
    private static SQLException failure(Throwable e, boolean numbered) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (!numbered || sql.getErrorCode() != 0)) {
                return sql;
            }
        }

        return null;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;

        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        return root;
    }

    /** The same walk, for the failure that happens before any SQL is sent. */
    private static Throwable authentication(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClientAuthenticationException) {
                return cause;
            }
        }

        return null;
    }

    /**
     * The failures worth naming, because each has a different fix and all of
     * them are ordinary states of a platform being set up. Same numbers and
     * same words as db-hello, so an operator reading either page reads the same
     * sentence.
     */
    private static Result explain(SQLException e) {
        return switch (e.getErrorCode()) {
            case 18456 -> new Result(null, "no database user",
                    "This identity authenticated to Entra ID but has no user in the database — or has one carrying "
                            + "somebody else's SID. Run Deploy Tenants for this environment, or "
                            + "apps/db-hello/bootstrap.sh as the server's Entra administrator.");
            case 229, 230 -> new Result(null, "not authorized",
                    "The database user exists but is missing db_datareader/db_datawriter. Re-run Deploy Tenants.");
            case 208 -> new Result(null, "no schema",
                    "The visits table does not exist yet — Deploy Tenants applies the EF Core migrations in "
                            + "apps/db-hello that create it. This application owns no schema of its own.");
            case 40613 -> new Result(null, "resuming",
                    "The database is waking up from auto-pause; reload in a moment.");
            default -> new Result(null, "unavailable", e.getMessage() + " (error " + e.getErrorCode() + ")");
        };
    }

    /** @return true if the wait completed and another attempt is worth making */
    private static boolean backOff(int attempt) {
        try {
            Thread.sleep(Math.min(attempt * 2000L, 10000L));

            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }
}
