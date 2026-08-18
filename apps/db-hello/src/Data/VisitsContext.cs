using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;

namespace OneK8s.DbHello.Data;

/// <summary>
/// The model, and the definition of the schema under it.
///
/// Code-first: nobody writes CREATE TABLE anywhere in this repository. This
/// class and <see cref="Visit"/> are the description of the database, EF Core
/// migrations turn a change here into a migration, and Deploy Tenants applies
/// it. The pod never migrates — at runtime it holds db_datareader and
/// db_datawriter and nothing else, so it *could* not change the schema even if
/// it tried, which is the property worth keeping.
/// </summary>
public sealed class VisitsContext(DbContextOptions<VisitsContext> options) : DbContext(options)
{
    public DbSet<Visit> Visits => Set<Visit>();

    /// <summary>
    /// How to reach the database — one definition, used three times: by the
    /// running application, by `db-hello bootstrap` in the tenants deploy, and
    /// by `dotnet ef migrations add` on a laptop. The connection string carries
    /// no credential; <see cref="EntraTokenInterceptor"/> supplies the token
    /// when the connection opens.
    /// </summary>
    public static void Configure(DbContextOptionsBuilder options)
    {
        var connectionString = new SqlConnectionStringBuilder
        {
            // "localhost" only so that `dotnet ef migrations add` works with no
            // environment at all — it builds the model and writes files
            // without ever opening a connection.
            DataSource = $"tcp:{Env.Value("SQL_SERVER") ?? "localhost"},1433",
            InitialCatalog = Env.Value("SQL_DATABASE") ?? "appdb",
            Encrypt = true,
            TrustServerCertificate = false,
            // A serverless database that has auto-paused takes up to a minute
            // to wake, and the first connection is the one that waits for it.
            ConnectTimeout = int.TryParse(Env.Value("SQL_CONNECT_TIMEOUT_SECONDS"), out var t) ? t : 30,
            ApplicationName = "onek8s-db-hello",
        }.ConnectionString;

        options
            .UseSqlServer(connectionString, sql => sql
                // The provider's own retry strategy, which already knows every
                // Azure SQL transient error including 40613 — what a paused
                // free-offer database answers while it wakes up. It replaces
                // the retry loop this application would otherwise hand-roll,
                // and it covers the migrations in CI for the same reason.
                .EnableRetryOnFailure(maxRetryCount: 3, maxRetryDelay: TimeSpan.FromSeconds(10), errorNumbersToAdd: null))
            .AddInterceptors(new EntraTokenInterceptor());
    }

    protected override void OnModelCreating(ModelBuilder model)
    {
        model.Entity<Visit>(visit =>
        {
            // Named rather than left to the convention, because the table is
            // something operators query by hand and "visits" is what the docs
            // and the portal show.
            visit.ToTable("visits");
            visit.HasKey(v => v.Id);
            visit.Property(v => v.Id).HasColumnName("id").ValueGeneratedOnAdd();
            visit.Property(v => v.VisitedAt)
                 .HasColumnName("visited_at")
                 .HasColumnType("datetime2(0)")
                 // The database stamps it, so the times in the table come from
                 // one clock rather than one per replica. Marking it generated
                 // on add keeps EF from sending a value and then reading the
                 // real one back.
                 .HasDefaultValueSql("SYSUTCDATETIME()")
                 .ValueGeneratedOnAdd();
            visit.Property(v => v.Pod).HasColumnName("pod").HasMaxLength(128).IsRequired();
            visit.Property(v => v.Cloud).HasColumnName("cloud").HasMaxLength(32).IsRequired();
        });

        // Result shape only: no table, no key, and nothing for migrations to
        // create. See DatabaseIdentity.
        model.Entity<DatabaseIdentity>(identity =>
        {
            identity.HasNoKey();
            identity.ToView(null);
        });
    }
}
