package io.onek8s.dbjava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The third example workload, and the second one that holds no secret at all:
 * <b>db-hello, written in Java</b>.
 *
 * <p>It does exactly what {@code apps/db-hello} does — reads and writes an
 * Azure SQL Database as the tenant's own managed identity — with a different
 * runtime, a different data-access library and a different token library, and
 * with none of the parts around it changed. The platform's claim is that a
 * workload's identity is the cluster's business and not the application's;
 * this is that claim tested by a second language rather than restated.
 *
 * <pre>
 *   /var/run/secrets/azure/tokens/azure-identity-token   (projected, rotated)
 *        │ federated credential: system:serviceaccount:&lt;tenant&gt;:workload
 *        ▼
 *   Entra ID  ──access token for https://database.windows.net/──▶  Azure SQL
 * </pre>
 *
 * <p>What is deliberately <em>not</em> here is a schema. The {@code visits}
 * table belongs to db-hello's EF Core migrations, which the platform's Deploy
 * Tenants workflow applies; this application maps that table and owns none of
 * it, so there is one description of the schema in the repository and not two
 * that can disagree. It needs no bootstrap of its own either: it runs as the
 * same tenant ServiceAccount, so it authenticates as the same managed identity
 * and uses the same database user, already granted.
 */
@SpringBootApplication
public class DbJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbJavaApplication.class, args);
    }
}
