package io.onek8s.dbjava.data;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import com.microsoft.sqlserver.jdbc.SQLServerAccessTokenCallback;
import com.microsoft.sqlserver.jdbc.SqlAuthenticationToken;
import io.onek8s.dbjava.Env;

/**
 * Where the password would have been.
 *
 * <p>The JDBC URL names a host and a database and carries no credential. When
 * the driver opens a <em>physical</em> connection it asks this callback for a
 * token instead — which is also the only moment at which a fresh one can be
 * guaranteed, since connections come back out of Hikari's pool long after the
 * request that first opened them is gone. db-hello does the same thing with an
 * EF Core connection interceptor; this is the same idea with the driver's own
 * hook, and it is the entire "no password" story on this side.
 *
 * <p>The credential is built once. It caches the tokens it mints and refreshes
 * them before they expire, so a page view that follows another inside the hour
 * does not go back to Entra ID.
 */
public final class EntraTokenCallback implements SQLServerAccessTokenCallback {

    /**
     * The audience is the SQL service, not the database: one token opens any
     * database this identity has a user in. Used when the server does not name
     * one itself — see {@link #getAccessToken}.
     */
    private static final String SCOPE = "https://database.windows.net/.default";

    private final TokenCredential credential;

    /**
     * WorkloadIdentityCredential when the pod carries a federated token — the
     * AZURE_* variables and the token file are injected by the AKS workload
     * identity webhook, which the chart triggers with the
     * {@code azure.workload.identity/use} pod label. DefaultAzureCredential
     * otherwise, which is what makes {@code mvn spring-boot:run} work on a
     * laptop with an ordinary {@code az login}.
     */
    public EntraTokenCallback() {
        this(Env.value("AZURE_FEDERATED_TOKEN_FILE") != null
                ? new WorkloadIdentityCredentialBuilder().build()
                : new DefaultAzureCredentialBuilder().build());
    }

    /** The credential as an argument, so a test can supply one. */
    public EntraTokenCallback(TokenCredential credential) {
        this.credential = credential;
    }

    /**
     * @param spn    the audience the server itself asked for, which is what
     *               makes this work unchanged on a sovereign cloud, where the
     *               SQL endpoint is not {@code database.windows.net}
     * @param stsurl the authority the server named; unused, because the
     *               credential already knows its own (AZURE_AUTHORITY_HOST,
     *               injected by the same webhook)
     */
    @Override
    public SqlAuthenticationToken getAccessToken(String spn, String stsurl) {
        AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(scope(spn)));

        return new SqlAuthenticationToken(token.getToken(), token.getExpiresAt().toInstant().toEpochMilli());
    }

    private static String scope(String spn) {
        if (spn == null || spn.isEmpty()) {
            return SCOPE;
        }

        return spn.endsWith("/") ? spn + ".default" : spn + "/.default";
    }
}
