package io.onek8s.dbjava.data;

/**
 * The database's own answer to "who is this connection, and what may it do?".
 *
 * <p>A record rather than an entity, because it is the shape of a three-column
 * result set rather than something that is stored — the counterpart of the
 * keyless type db-hello maps. The query behind it is the one piece of SQL
 * written by hand in this application (see {@link DatabaseIdentityQuery}):
 * {@code SUSER_SNAME()} and the role membership are the <em>server's</em> view
 * of the connection, and that view is the whole reason the application exists.
 *
 * @param login SUSER_SNAME() — the Entra principal the token belongs to
 * @param user  USER_NAME() — the contained user that principal maps to
 * @param roles the database roles that user is a member of
 */
public record DatabaseIdentity(String login, String user, String roles) {
}
