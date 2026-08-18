using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace OneK8s.DbHello.Data;

/// <summary>
/// How the EF Core tools build a context without starting the web application.
///
/// `dotnet ef migrations add` needs a model and no database; `dotnet ef
/// database update` needs both, and gets its credential the same way the
/// running pod does — through the interceptor, from whatever Azure login the
/// caller has (the deploy service principal, in Deploy Tenants).
/// Because it goes through <see cref="VisitsContext.Configure"/>, there is no
/// second copy of the connection settings to drift.
/// </summary>
internal sealed class VisitsContextFactory : IDesignTimeDbContextFactory<VisitsContext>
{
    public VisitsContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<VisitsContext>();
        VisitsContext.Configure(options);

        return new VisitsContext(options.Options);
    }
}
