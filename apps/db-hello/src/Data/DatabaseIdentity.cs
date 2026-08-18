namespace OneK8s.DbHello.Data;

/// <summary>
/// The database's own answer to "who is this connection, and what may it do?".
///
/// Keyless, and mapped to no table: it is the shape of a three-column result
/// set rather than something that is stored. This is the one query in the
/// application that is written in SQL, because there is no model to query —
/// SUSER_SNAME() and the role membership are the *server's* view of the
/// connection, and that view is the whole reason this application exists.
/// </summary>
public class DatabaseIdentity
{
    /// <summary>SUSER_SNAME() — the Entra principal the token belongs to.</summary>
    public string Login { get; set; } = string.Empty;

    /// <summary>USER_NAME() — the contained user that principal maps to.</summary>
    public string User { get; set; } = string.Empty;

    /// <summary>The database roles that user is a member of.</summary>
    public string Roles { get; set; } = string.Empty;
}
