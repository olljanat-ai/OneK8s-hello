# The db-java application, and what a second language proves

The platform's third workload, and the only one that exists to be *compared*.
[db-hello](db-hello-app.md) reads and writes an Azure SQL Database as the
tenant's managed identity with no credential anywhere; db-java does the same
thing in Java, and the interesting part is the list of things that had to
change to make that work.

| Stage | Cloud | Cluster | URL |
|---|---|---|---|
| — | `azure` | AKS — the Argo CD hub | https://azure-db-java.onek8s.lol |

> The host follows `platform_apps.domain` in OneK8s' `gitops/envs/<env>.tfvars`,
> which is `onek8s.lol` — the domain the platform's wildcard certificate and DNS
> zone are for.

## The list

Nothing in the platform's half.

| Piece | db-hello | db-java |
|---|---|---|
| ServiceAccount | `workload`, annotated by `modules/tenant-namespace/azure` | the same one |
| Pod label | `azure.workload.identity/use: "true"` | the same label |
| Token audience | `https://database.windows.net/` | the same audience |
| Database user | the tenant's UAMI, `db_datareader` + `db_datawriter` | **the same user** — it is the same identity |
| Ingress, TLS, DNS | Traefik, platform wildcard, A record out of band | the same |
| Pod Security | non-root, UID 1654, read-only root, `RuntimeDefault` | the same, same UID |

And everything in the application's half.

| Piece | db-hello | db-java |
|---|---|---|
| Runtime | ASP.NET Core (.NET 10) | Spring Boot 4 on Temurin 21 |
| Data access | EF Core, code-first | Hibernate/Spring Data, mapping a table it does not own |
| Token library | `Azure.Identity` | `azure-identity` |
| Where the token is attached | an EF Core `DbConnectionInterceptor` | the JDBC driver's `SQLServerAccessTokenCallback` |
| Transient retries | the provider's execution strategy | thirteen error numbers and a loop |
| Memory | 256Mi limit | 512Mi limit |
| Start-up | milliseconds | seconds, so the chart adds a `startupProbe` |

That is the whole delta, and it is the answer to the question the platform is
built to answer: **whose problem is a workload's identity?** If it were the
application's, a second language would mean a second arrangement — another
federated credential, another database user, another grant in the tenants
deploy. It means none of those. The pod's identity is the *cluster's*
statement about the pod, so the application only has to ask for a token and put
it on a connection, in whatever language it is written in.

## The schema belongs to the other one

There is exactly one description of the `visits` table in this repository, and
it is db-hello's EF Core model. db-java maps it:

```java
@Entity
@Table(name = "visits")
public class Visit { … }        // apps/db-java/src/main/java/io/onek8s/dbjava/data/Visit.java
```

— with `ddl-auto: none`, no migration tool on its classpath, and a database
user that holds `db_datareader` and `db_datawriter`, so the *database* would
refuse it DDL in any case. A model change is made in db-hello, generated into a
migration there, applied by Deploy Tenants, and followed here.

The alternative — a second code-first model over the same table, or a second
table — was rejected on the same grounds the platform rejects a second copy of
anything: two owners of one schema is a drift waiting to happen, and two tables
would have made the demonstration a comparison of two isolated applications
rather than of two applications sharing a database.

So both applications write to the same table and each page shows the other's
rows:

```
Last 10 page views, read back through Hibernate
2026-09-09 05:11:32Z   db-java-5c9f7b4d8-abcde     azure
2026-09-09 05:11:02Z   db-hello-7d4f9c8b6d-x2k9p   azure
```

That is not a curiosity — it is the proof that the two identities resolve to
the same database user, which is the claim the whole page is making.

## Where the password would have been

```java
// data/VisitsDataSource.java — the connection, and no credential in it
sql.setServerName(Env.value("SQL_SERVER", "localhost"));
sql.setDatabaseName(Env.value("SQL_DATABASE", "appdb"));
sql.setEncrypt("true");
sql.setAccessTokenCallback(new EntraTokenCallback());
```

```java
// data/EntraTokenCallback.java — called by the driver on every physical connection
AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(scope(spn)));
return new SqlAuthenticationToken(token.getToken(), token.getExpiresAt().toInstant().toEpochMilli());
```

`WorkloadIdentityCredential` when the pod carries a federated token,
`DefaultAzureCredential` otherwise — the same choice db-hello makes, from the
same environment variables, injected by the same webhook. The credential caches
and refreshes; the driver asks again for each new physical connection, which is
what keeps a pooled connection from outliving its token.

The audience comes from the server rather than a constant, because the driver
hands the callback the SPN it was told to use — so a sovereign cloud, where the
SQL endpoint is not `database.windows.net`, needs no change here.

## Starting when the database will not

This is the one place where the JVM made the platform's habits inconvenient,
and it is worth reading before changing `application.yaml`.

Hibernate decides what dialect to speak by asking the driver for metadata,
which means **opening a connection while the application context starts** — and
failing to start when it cannot. The database this connects to is a free-tier
serverless one that auto-pauses after an hour of idleness, and a tenant that
has not been through Deploy Tenants has no database user at all. Either would
turn into a pod in `CrashLoopBackOff`: precisely the states the page exists to
explain, and it explains nothing if it never starts.

```yaml
spring.jpa.database-platform: org.hibernate.dialect.SQLServerDialect
spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access: false
```

The two lines are one decision, and neither works alone — remove the dialect
and the context fails with *"Unable to determine Dialect without JDBC
metadata"*; remove the second line and the first is decoration. The connection
pool is told the same thing in its own words (`initializationFailTimeout: -1`),
so it neither primes itself at startup nor fails the pod over a connection it
could not make. A test asserts the result, because the failure would otherwise
only appear on a morning when the database happened to be asleep.

## What is not in the image

The application makes exactly one kind of outbound HTTP call — a token request
— and `azure-identity` brings Netty, four platforms' worth of native QUIC
libraries and about 20 MB of jar to make it. The POM excludes that and adds
`azure-core-http-jdk-httpclient`, so the request goes through
`java.net.http` instead.

The catch is that azure-identity finds its transport through the
`ServiceLoader`: get the swap wrong and the application builds, starts, serves
`/healthz`, and then cannot mint a token. `DbJavaApplicationTests` asserts that
a transport is found, which is the cheapest possible guard on the most
expensive possible mistake.

## Non-root, like everything else in a tenant namespace

The pod runs as **UID 1654** — the same number as the other two applications,
which is a choice rather than a coincidence: 1654 is the `app` user of the .NET
images, and the Dockerfile here creates a user with that UID so the platform
has one number to reason about instead of three.

There is no chiseled JRE the way there is a chiseled .NET runtime, so the
runtime image is an ordinary Ubuntu one with a shell and a root user in it. The
`USER` instruction and the pod's `securityContext` are what keep the
application from being either, exactly as for db-hello (whose image is also a
full one, because `Microsoft.Data.SqlClient` needs ICU). PR validation checks
both halves — the rendered chart for the four fields the `restricted` Pod
Security Standard demands, and the Dockerfile for a `USER` instruction —
because a chart that stops saying it would be refused at admission, after the
merge, on the cluster.

## What CI checks

| Job | What it would catch |
|---|---|
| `helm (lint + render)` | a chart that stopped rendering, or stopped demanding `sql.server`, `sql.database` and `ingress.host` |
| `Applications, non-root…` | a pod that could no longer be admitted to a tenant namespace |
| `maven (db-java)` | a build error, and the three properties the tests assert |
| Build DB Java App | an image that does not build; on `main`, the image itself |

The image is published to GHCR on every merge to `main` that touches
`apps/db-java/**`:

```
ghcr.io/olljanat-ai/onek8s-hello/db-java:latest
ghcr.io/olljanat-ai/onek8s-hello/db-java:sha-<short>
```

The package must be **public**, like the other two — no cluster on any cloud
has a pull secret, which is deliberate — and GHCR packages default to private,
so make it public once under its package settings after the first push.

## Deploying it

Two things outside this repository, both the same as db-hello's:

1. **An Application.** Add `db-java` to the ApplicationSet in
   [OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd), with
   `ingress.host=azure-db-java.onek8s.lol` and `sql.server` / `sql.database`
   from the Azure foundation's outputs. Argo CD renders the chart in this
   repository; nothing here decides where or when it is deployed.
2. **A DNS record.** An A record for `azure-db-java.onek8s.lol` pointing at the
   AKS cluster's Traefik Service. TLS needs nothing — the Ingress carries no
   certificate and Traefik's default TLSStore serves the platform wildcard.

Nothing has to be done to the database, the tenants stack or the bootstrap. If
db-hello works in a tenant, db-java works in that tenant.

```bash
# the chart, as the delivery plane renders it
helm template db-java-azure apps/db-java/chart \
  --set ingress.host=azure-db-java.onek8s.lol \
  --set sql.server="$(terraform -chdir=$ONEK8S_ROOT/foundations/azure output -raw sql_server_fqdn)" \
  --set sql.database="$(terraform -chdir=$ONEK8S_ROOT/foundations/azure output -raw sql_database_name)"

# the pod, and whether the webhook mutated it
kubectl -n team-alpha get pod -l app.kubernetes.io/name=db-java \
  -o jsonpath='{.items[0].spec.containers[0].env[?(@.name=="AZURE_CLIENT_ID")].value}{"\n"}'
```

## Known gaps

- **The image tag is `latest`,** exactly as for db-hello, and for the same
  reason: one cluster, no release path, and a Warehouse for a single stage
  would be ceremony rather than a gate. Pin `image.tag` to an `sha-<short>` for
  a reproducible deploy.
- **No dependency lock file.** The .NET applications commit
  `packages.lock.json`; Maven has no equivalent in the box, and the versions
  here come from the Spring Boot BOM plus two explicit pins. A resolved-version
  lock would make a build reproducible against a compromised repository, and is
  not in place.
- **It shares db-hello's row space.** Deliberate, and the section above says
  why — but it does mean the two applications cannot be reasoned about
  separately at the data layer, and a model change is a change to both.
- **A JVM costs more than the demonstration does.** 512Mi and a couple of
  seconds of start-up against 256Mi and none, for a page that prints six lines.
  That is the honest price of the comparison, and it is why this application
  exists next to db-hello rather than instead of it.
