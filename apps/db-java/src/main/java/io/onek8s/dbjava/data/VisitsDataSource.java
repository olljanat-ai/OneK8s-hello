package io.onek8s.dbjava.data;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.onek8s.dbjava.Env;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How to reach the database — the counterpart of db-hello's
 * {@code VisitsContext.Configure}, and like it the only place the connection is
 * described.
 *
 * <p>Built in code rather than declared in {@code application.yaml} for two
 * reasons: the token callback is an object and not a string, and the two
 * settings that vary (the server and the database) are environment variables
 * the chart sets, which keeps them out of the jar entirely. Neither is a
 * secret — a host name and a database name authorize nobody.
 */
@Configuration(proxyBeanMethods = false)
public class VisitsDataSource {

    /** Long enough that a page view survives a serverless database waking up. */
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;

    /**
     * A small pool, because a page view is two or three short queries and the
     * pod's whole job is to render one page.
     */
    private static final int MAXIMUM_POOL_SIZE = 5;

    @Bean
    public DataSource dataSource() {
        SQLServerDataSource sql = new SQLServerDataSource();

        // "localhost" only so that the application starts with no environment
        // at all — it renders a page saying "not configured" rather than
        // failing to boot, exactly as db-hello does.
        sql.setServerName(Env.value("SQL_SERVER", "localhost"));
        sql.setPortNumber(1433);
        sql.setDatabaseName(Env.value("SQL_DATABASE", "appdb"));
        sql.setEncrypt("true");
        sql.setTrustServerCertificate(false);
        sql.setApplicationName("onek8s-db-java");
        // A serverless database that has auto-paused takes up to a minute to
        // wake, and the first connection is the one that waits for it.
        sql.setLoginTimeout(connectTimeoutSeconds());
        // No user, no password, no "authentication=..." — this one line is the
        // whole credential story. The driver calls it on every physical
        // connection it opens.
        sql.setAccessTokenCallback(new EntraTokenCallback());

        HikariConfig pool = new HikariConfig();
        pool.setDataSource(sql);
        pool.setPoolName("visits");
        pool.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        // Hikari's own timeout has to outlast the driver's login timeout, or
        // the pool gives up first and the page reports a pool timeout instead
        // of the database that is waking up behind it.
        pool.setConnectionTimeout((connectTimeoutSeconds() + 5) * 1000L);
        // Do not open a connection at startup, and never fail startup over one.
        // A paused database, a missing database user and an unapplied migration
        // are all states this application is expected to *describe* on its
        // page; a pod in CrashLoopBackOff describes nothing.
        pool.setInitializationFailTimeout(-1);

        return new HikariDataSource(pool);
    }

    private static int connectTimeoutSeconds() {
        String configured = Env.value("SQL_CONNECT_TIMEOUT_SECONDS");

        if (configured == null) {
            return DEFAULT_CONNECT_TIMEOUT_SECONDS;
        }

        try {
            return Integer.parseInt(configured);
        } catch (NumberFormatException e) {
            return DEFAULT_CONNECT_TIMEOUT_SECONDS;
        }
    }
}
