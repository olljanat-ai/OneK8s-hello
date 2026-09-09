# db-java

The [db-hello](../db-hello) application written in **Java**: the same Azure SQL
Database, read and written as the tenant's own managed identity, with no
connection string, no password and no `Secret` of any kind. Spring Boot instead
of ASP.NET Core, Hibernate instead of EF Core, `azure-identity` instead of
`Azure.Identity` — and, on the platform's side of the line, nothing different
at all.

```
apps/db-java/
├── pom.xml                                   Spring Boot BOM, four dependencies
├── src/main/java/io/onek8s/dbjava/
│   ├── DbJavaApplication.java                the main method, and the why
│   ├── Routes.java                           /, /healthz, /favicon.svg
│   ├── Database.java                         the round trip, and every failure named
│   ├── Page.java                             the page, as a string
│   ├── Env.java                              configuration, all of it from the environment
│   └── data/
│       ├── Visit.java                        db-hello's table, mapped — not owned
│       ├── VisitRepository.java              the queries, as method names
│       ├── DatabaseIdentity.java             the server's view of the connection
│       ├── DatabaseIdentityQuery.java        the one query written in SQL
│       ├── EntraTokenCallback.java           where the password would have been
│       └── VisitsDataSource.java             how to reach the database
├── src/main/resources/application.yaml       why the pod starts when the database will not
├── src/test/java/…/DbJavaApplicationTests.java
├── Dockerfile      # Maven build → Temurin JRE, non-root UID 1654
└── chart/          # what Argo CD renders on the hub
```

## What it does not have

**A schema.** The `visits` table belongs to db-hello's EF Core migrations,
which the platform's **Deploy Tenants** workflow applies; this application maps
that table and owns none of it (`ddl-auto: none`, no migration tool on the
classpath). One description of the schema in the repository, in the language
that generates it, and a second application that follows it.

**A bootstrap.** db-hello ships a second entry point that applies the
migrations and creates a tenant's database user. This one needs neither: it
runs as the same tenant ServiceAccount, so it authenticates as the same managed
identity and arrives at the same database user, already granted
`db_datareader` + `db_datawriter`. Nothing about adding a second application to
this tenant touches the database.

The consequence is worth stating, because it is visible on both pages: the two
applications write to the same table, so each one's list of recent page views
includes the other's rows. The `pod` column says which wrote them.

## The token, and where it goes

```
pod (SA: workload)
  │  projected token, audience api://AzureADTokenExchange
  ▼
Entra ID ── federated credential: system:serviceaccount:team-alpha:workload
  │  access token for https://database.windows.net/
  ▼
Azure SQL ── database user for the tenant's UAMI, db_datareader + db_datawriter
```

The pod carries the `azure.workload.identity/use` label, so the AKS webhook
injects `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_AUTHORITY_HOST` and
`AZURE_FEDERATED_TOKEN_FILE` — the same four variables `Azure.Identity` reads
in db-hello, and `azure-identity` reads them too. `EntraTokenCallback` asks the
credential for a token and hands it to the JDBC driver, which calls it every
time it opens a *physical* connection:

```java
sql.setAccessTokenCallback(new EntraTokenCallback());   // VisitsDataSource.java
```

That single line is the whole credential story, and it is the exact counterpart
of db-hello's EF Core connection interceptor. No `user`, no `password`, no
`authentication=` in the connection settings.

| Variable | Set by | Used for |
|---|---|---|
| `SQL_SERVER` / `SQL_DATABASE` | the ApplicationSet, from the foundation's outputs | where to connect |
| `SQL_CONNECT_TIMEOUT_SECONDS` | the chart | waiting out an auto-pause resume |
| `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_FEDERATED_TOKEN_FILE` | the AKS **workload identity webhook** | which identity, and the token to exchange |
| `CLOUD` / `ENVIRONMENT` | the ApplicationSet | shown on the page |
| `POD_NAME` / `POD_NAMESPACE` | the downward API | shown on the page, and written to the row |

## Starting when the database will not

A JVM application that asks the database what it is while it starts cannot come
up while the database is asleep — and this database is a free-tier serverless
one that auto-pauses hourly. So `application.yaml` turns Hibernate's metadata
lookup off and names the dialect instead, and the connection pool is told not
to prime itself or fail startup over a connection. The pod comes up, `/healthz`
answers, and the page explains what is missing.

The same four states db-hello names, in the same words:

| What the page says | What it means |
|---|---|
| `resuming` | the free-tier database auto-paused; it is waking up (three attempts are made first) |
| `no database user` | the identity is fine, but the bootstrap has not run since this tenant (or this database) was created — `./apps/db-hello/bootstrap.sh <environment>`, or **Deploy Tenants** |
| `no schema` | the user exists but the `visits` table does not — the same workflow applies db-hello's migrations |
| `no token` | Entra ID would not issue one: the pod is missing the workload-identity label, or its ServiceAccount the client-id annotation |

`GET /healthz` is the liveness, readiness and startup probe, and deliberately
does **not** touch the database: an auto-paused database must not restart the
pod.

## Running it locally

Anything `DefaultAzureCredential` accepts works — the callback uses it whenever
there is no projected token — so an `az login` as a user with a database user
of its own is enough:

```bash
cd apps/db-java
SQL_SERVER=sql-onek8s-prototype-ab12.database.windows.net \
SQL_DATABASE=appdb \
WELCOME_MESSAGE="Hello from my laptop" mvn spring-boot:run
```

Your own address needs a firewall rule on the server (`sql_firewall_rules` in
the foundation, or `az sql server firewall-rule create`) — the cluster gets in
through the AKS subnet's service endpoint, which a laptop is not on.

```bash
mvn -B verify          # what PR validation runs: compile, then the tests below
```

The tests need no database and no Azure subscription, which is the point of the
three of them that matter: that the context starts with nothing reachable, that
a transport for the token request is still on the classpath (the POM drops
Netty in favour of the JDK's HTTP client, and getting that wrong is invisible
until the first token), and that a failure arrives as a page naming its fix
rather than as a stack trace. The round trip through Azure SQL itself is the
part no test here stands in for — run it against a real database, as above.

## Where it is deployed, and how

Argo CD, from the [OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd)
repository — one Application, on Azure only, for exactly the reasons db-hello
is: the database is an Azure resource and the identity is an Entra one. Full
walkthrough: [docs/db-java-app.md](../../docs/db-java-app.md).

<https://azure-db-java.onek8s.lol>
