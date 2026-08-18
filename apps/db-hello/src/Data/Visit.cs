namespace OneK8s.DbHello.Data;

/// <summary>
/// One page view. The only entity in this application, and — because the
/// schema is code-first — the definition of the table it lives in: change this
/// class, add a migration, and the database follows.
/// </summary>
public class Visit
{
    public long Id { get; set; }

    /// <summary>
    /// Stamped by the database (SYSUTCDATETIME) rather than by the pod, so the
    /// times in the table are one clock's and not one per replica.
    /// </summary>
    public DateTime VisitedAt { get; set; }

    public string Pod { get; set; } = string.Empty;

    public string Cloud { get; set; } = string.Empty;
}
