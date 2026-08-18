using Azure.Identity;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using OneK8s.DbHello;
using OneK8s.DbHello.Data;

// The second example workload, and the one that holds no secret at all.
//
// hello proves that External Secrets handed the pod a value out of the cloud's
// secret backend. This one goes a step further and removes the value: it reads
// and writes an Azure SQL Database as the *tenant's own managed identity*, with
// a token minted from the ServiceAccount token Kubernetes projects into the
// pod. There is no connection string in a Secret, no password in the chart and
// no credential in the image — the two configuration values it takes are a
// host name and a database name, both of them public.
//
//   /var/run/secrets/azure/tokens/azure-identity-token   (projected, rotated)
//        │ federated credential: system:serviceaccount:<tenant>:workload
//        ▼
//   Entra ID  ──access token for https://database.windows.net/──▶  Azure SQL
//
// Data access is Entity Framework Core, code-first: Data/Visit.cs *is* the
// table, and the only SQL written by hand is the one query that asks the
// server who it thinks we are (Data/DatabaseIdentity.cs).

// The one code path that is not the web page. `bootstrap` applies the
// migrations and creates a tenant's database user, and it is run by CI as the
// server's Entra administrator — never by the pod, whose identity the database
// refuses both acts to. See Bootstrap.cs.
if (args is ["bootstrap", ..])
{
    return await Bootstrap.RunAsync(args[1..], CancellationToken.None);
}

var builder = WebApplication.CreateSlimBuilder(args);

builder.Services.AddDbContext<VisitsContext>(VisitsContext.Configure);

var app = builder.Build();

app.MapGet("/healthz", () => Results.Text("ok"));

app.MapGet("/", async (VisitsContext db, CancellationToken ct) =>
    Results.Content(await Page.RenderAsync(db, ct), "text/html; charset=utf-8"));

// The same platform mark the hello application serves, for the same reasons:
// held as a string because the image ships no wwwroot, and linked by the
// document so no browser falls back to asking for /favicon.ico.
const string Favicon = """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
      <rect width="32" height="32" rx="7" fill="#0b1220"/>
      <path fill="#38bdf8" d="M16 2.5 26.55 7.58 29.16 19 21.86 28.16H10.14L2.84 19 5.45 7.58Z"/>
      <path fill="none" stroke="#0b1220" stroke-width="4.2" stroke-linecap="round"
            stroke-linejoin="round" d="M11.9 12.2 16 8.6V23.4M10.6 23.4h10.8"/>
    </svg>
    """;

app.MapGet("/favicon.svg", (HttpContext http) =>
{
    http.Response.Headers.CacheControl = "public, max-age=86400";
    return Results.Content(Favicon, "image/svg+xml; charset=utf-8");
});

app.Run();

return 0;

namespace OneK8s.DbHello
{
    /// <summary>
    /// What the page can say about the database: a <see cref="Reading"/> when
    /// the round trip worked, otherwise the two-part sentence to print in its
    /// place.
    /// </summary>
    internal record Result(Reading? Reading, string? Problem, string? Detail);

    internal record Reading(DatabaseIdentity Identity, IReadOnlyList<Visit> Visits);

    internal static class Database
    {
        // The server's own view of this connection. It is the only SQL string
        // in the application, and it queries no table — see DatabaseIdentity.
        private const string WhoAmI = """
            SELECT SUSER_SNAME() AS Login,
                   USER_NAME()   AS [User],
                   ISNULL((SELECT STRING_AGG(r.name, ', ')
                           FROM sys.database_role_members AS m
                                INNER JOIN sys.database_principals AS r
                                    ON r.principal_id = m.role_principal_id
                           WHERE m.member_principal_id = DATABASE_PRINCIPAL_ID()), '(none)') AS Roles
            """;

        /// <summary>
        /// Record this page view, then read back who we are and the last few
        /// views. The insert is what proves the identity holds db_datawriter
        /// and not merely read access — and those rows are the only state this
        /// application has.
        ///
        /// There is no retry loop here on purpose: the provider's own
        /// execution strategy (VisitsContext.Configure) already retries the
        /// transient errors, "the database is waking up from auto-pause"
        /// included.
        /// </summary>
        public static async Task<Result> ReadAsync(VisitsContext db, CancellationToken ct)
        {
            if (Env.Value("SQL_SERVER") is null || Env.Value("SQL_DATABASE") is null)
            {
                return new Result(null, "not configured",
                    "SQL_SERVER and SQL_DATABASE are unset; the chart requires both.");
            }

            try
            {
                db.Visits.Add(new Visit
                {
                    Pod = Env.Value("POD_NAME") ?? Environment.MachineName,
                    Cloud = Env.Value("CLOUD") ?? "unknown",
                });

                await db.SaveChangesAsync(ct);

                var identity = await db.Set<DatabaseIdentity>()
                    .FromSqlRaw(WhoAmI)
                    .AsNoTracking()
                    .FirstAsync(ct);

                var visits = await db.Visits
                    .AsNoTracking()
                    .OrderByDescending(v => v.Id)
                    .Take(10)
                    .ToListAsync(ct);

                return new Result(new Reading(identity, visits), null, null);
            }
            catch (Exception e) when (e is CredentialUnavailableException or AuthenticationFailedException)
            {
                return new Result(null, "no token",
                    $"Entra ID would not issue a token for this pod's identity. {e.Message}");
            }
            catch (Exception e) when (Sql(e) is not null)
            {
                return Explain(Sql(e)!);
            }
        }

        // EF wraps a failed SaveChanges in DbUpdateException, so the server's
        // own error number — the part that says what to do about it — is one
        // or two levels down.
        private static SqlException? Sql(Exception? e)
        {
            for (; e is not null; e = e.InnerException)
            {
                if (e is SqlException sql)
                {
                    return sql;
                }
            }

            return null;
        }

        // The failures worth naming, because each has a different fix and all
        // of them are ordinary states of a platform being set up.
        private static Result Explain(SqlException e) => e.Number switch
        {
            18456 => new Result(null, "no database user",
                "This identity authenticated to Entra ID but has no user in the database — or has one carrying somebody else's SID. Run Deploy Tenants for this environment, or apps/db-hello/bootstrap.sh as the server's Entra administrator."),
            229 or 230 => new Result(null, "not authorized",
                "The database user exists but is missing db_datareader/db_datawriter. Re-run Deploy Tenants."),
            208 => new Result(null, "no schema",
                "The visits table does not exist yet — Deploy Tenants applies the EF Core migrations that create it."),
            40613 => new Result(null, "resuming",
                "The database is waking up from auto-pause; reload in a moment."),
            _ => new Result(null, "unavailable", $"{e.Message} (error {e.Number})"),
        };
    }

    internal static class Page
    {
        private static string Encode(string? value) => System.Net.WebUtility.HtmlEncode(value ?? string.Empty);

        public static async Task<string> RenderAsync(VisitsContext db, CancellationToken ct)
        {
            var result = await Database.ReadAsync(db, ct);
            var reading = result.Reading;

            // Already-encoded HTML either way: the database user's name, or the
            // reason there is not one to show.
            var user = reading is null
                ? $"""<span class="pending">{Encode(result.Problem)} &mdash; {Encode(result.Detail)}</span>"""
                : Encode(reading.Identity.User);

            var visits = new System.Text.StringBuilder();

            if (reading is null)
            {
                visits.Append("""<tr><td colspan="3" class="pending">no rows &mdash; the database was not reachable</td></tr>""");
            }
            else if (reading.Visits.Count == 0)
            {
                visits.Append("""<tr><td colspan="3" class="pending">no rows yet</td></tr>""");
            }
            else
            {
                foreach (var visit in reading.Visits)
                {
                    visits.Append($"<tr><td>{Encode(visit.VisitedAt.ToString("u"))}</td>")
                          .Append($"<td>{Encode(visit.Pod)}</td>")
                          .Append($"<td>{Encode(visit.Cloud)}</td></tr>");
                }
            }

            return $$"""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>OneK8s db-hello</title>
                <link rel="icon" href="/favicon.svg" type="image/svg+xml">
                <style>
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
                  footer { margin-top: 2.5rem; color: #64748b; font-size: .85rem; }
                </style>
                </head>
                <body>
                <main>
                  <h1>{{Encode(Env.Value("WELCOME_MESSAGE") ?? "Welcome to OneK8s!")}}</h1>
                  <dl>
                    <dt>Cloud</dt><dd>{{Encode(Env.Value("CLOUD") ?? "unknown")}}</dd>
                    <dt>Environment</dt><dd>{{Encode(Env.Value("ENVIRONMENT") ?? "unknown")}}</dd>
                    <dt>Namespace</dt><dd>{{Encode(Env.Value("POD_NAMESPACE") ?? "unknown")}}</dd>
                    <dt>Pod</dt><dd>{{Encode(Env.Value("POD_NAME") ?? Environment.MachineName)}}</dd>
                  </dl>
                  <h2>Azure SQL, without a password</h2>
                  <dl>
                    <dt>Server</dt><dd>{{Encode(Env.Value("SQL_SERVER") ?? "unset")}}</dd>
                    <dt>Database</dt><dd>{{Encode(Env.Value("SQL_DATABASE") ?? "unset")}}</dd>
                    <dt>Workload identity</dt><dd>{{Encode(Env.Value("AZURE_CLIENT_ID") ?? "none injected")}}</dd>
                    <dt>Signed in as</dt><dd>{{Encode(reading?.Identity.Login)}}</dd>
                    <dt>Database user</dt><dd>{{user}}</dd>
                    <dt>Database roles</dt><dd>{{Encode(reading?.Identity.Roles)}}</dd>
                  </dl>
                  <h2>Last 10 page views, read back through Entity Framework</h2>
                  <table>
                    <thead><tr><th>Visited (UTC)</th><th>Pod</th><th>Cloud</th></tr></thead>
                    <tbody>{{visits}}</tbody>
                  </table>
                  <footer>Served by .NET {{Encode(Environment.Version.ToString())}}. Every page view is a row.</footer>
                </main>
                </body>
                </html>
                """;
        }
    }
}
