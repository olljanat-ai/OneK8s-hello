package io.onek8s.dbjava.data;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one query in the application written in SQL, for the one question that
 * has no model behind it.
 *
 * <p>It reads no table: {@code SUSER_SNAME()}, {@code USER_NAME()} and the role
 * membership are server state, and asking for them proves the token was
 * accepted, mapped to a database user, and given exactly the two roles the
 * bootstrap grants. It is the same query db-hello runs, character for
 * character, so the two applications' pages can be compared line by line.
 */
@Component
public class DatabaseIdentityQuery {

    private static final String WHO_AM_I = """
            SELECT SUSER_SNAME() AS Login,
                   USER_NAME()   AS [User],
                   ISNULL((SELECT STRING_AGG(r.name, ', ')
                           FROM sys.database_role_members AS m
                                INNER JOIN sys.database_principals AS r
                                    ON r.principal_id = m.role_principal_id
                           WHERE m.member_principal_id = DATABASE_PRINCIPAL_ID()), '(none)') AS Roles
            """;

    private final JdbcClient jdbc;

    public DatabaseIdentityQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DatabaseIdentity run() {
        return jdbc.sql(WHO_AM_I).query(DatabaseIdentity.class).single();
    }
}
