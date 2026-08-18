using System.Data.Common;
using Azure.Core;
using Azure.Identity;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore.Diagnostics;

namespace OneK8s.DbHello.Data;

/// <summary>
/// Attaches an Entra ID access token to every connection EF Core opens.
///
/// This is where the password would have been. The connection string names a
/// host and a database and carries no credential, and this interceptor puts a
/// token on the connection at the moment it is opened — which is also the only
/// moment at which a fresh one can be guaranteed, since connections come back
/// out of the pool long after the context that first opened them is gone.
/// </summary>
internal sealed class EntraTokenInterceptor : DbConnectionInterceptor
{
    // The audience is the SQL service, not the database: one token opens any
    // database this identity has a user in.
    private static readonly string[] Scope = ["https://database.windows.net/.default"];

    // Built once: the credential caches the tokens it mints and refreshes them
    // before they expire, so a page view that follows another inside the hour
    // does not go back to Entra ID.
    //
    // WorkloadIdentityCredential when the pod carries a federated token — the
    // AZURE_* variables and the token file are injected by the AKS workload
    // identity webhook, which the chart triggers with the
    // "azure.workload.identity/use" pod label. DefaultAzureCredential
    // otherwise, which is what makes `dotnet run` (and `dotnet ef database
    // update` in CI) work with an ordinary Azure login.
    private static readonly TokenCredential Credential =
        Env.Value("AZURE_FEDERATED_TOKEN_FILE") is not null
            ? new WorkloadIdentityCredential()
            : new DefaultAzureCredential();

    public override InterceptionResult ConnectionOpening(
        DbConnection connection, ConnectionEventData eventData, InterceptionResult result)
    {
        if (connection is SqlConnection sql)
        {
            sql.AccessToken = Credential.GetToken(new TokenRequestContext(Scope), default).Token;
        }

        return result;
    }

    public override async ValueTask<InterceptionResult> ConnectionOpeningAsync(
        DbConnection connection,
        ConnectionEventData eventData,
        InterceptionResult result,
        CancellationToken cancellationToken = default)
    {
        if (connection is SqlConnection sql)
        {
            var token = await Credential.GetTokenAsync(new TokenRequestContext(Scope), cancellationToken);
            sql.AccessToken = token.Token;
        }

        return result;
    }
}
