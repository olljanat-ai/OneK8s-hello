# db-hello

A minimal .NET 10 web application that reads and writes an **Azure SQL
Database** as the tenant's own managed identity, through **Entity Framework
Core** with a code-first schema. Its whole point is the credential that is not
there: no connection string in a Secret, no password in the chart, nothing in
the image.

```
apps/db-hello/
├── src/
│   ├── Program.cs                       the page, and the queries as LINQ
│   ├── Bootstrap.cs                     `db-hello bootstrap`, run by CI only
│   ├── Data/Visit.cs                    the entity — and therefore the table
│   ├── Data/VisitsContext.cs            the model, and how to connect
│   ├── Data/EntraTokenInterceptor.cs    where the password would have been
│   ├── Data/VisitsContextFactory.cs     a context with no web app around it
│   └── Migrations/                      generated, committed, applied by CI
├── bootstrap.sh    # migrate + grant for every Azure tenant, from CI or by hand
├── Dockerfile      # SDK build → chiseled-extra ASP.NET runtime (SqlClient needs ICU)
└── chart/          # what Argo CD renders on the hub
```

The schema is code-first, and the application never applies it *as the pod*:
its identity holds `db_datareader` + `db_datawriter`, so the database refuses
DDL to it. The same image carries a second entry point for that —

```bash
db-hello bootstrap --user <identity> --client-id <guid>
```

— which applies the migrations and creates the tenant's database user.
`bootstrap.sh` is what invokes it: it resolves the database and every Azure
tenant's identity from Terraform state, opens a firewall rule for the address
it runs from and closes it again, and calls the command once per tenant.

```bash
az login                                # as the server's Entra administrator
ONEK8S_ROOT=../OneK8s ./apps/db-hello/bootstrap.sh prototype
```

`ONEK8S_ROOT` is where the platform repository is checked out: the script reads
`foundations/azure` and `tenants` state from it, and defaults to `../OneK8s`
next to this one.

The **Deploy Tenants** workflow runs that same script for every Azure tenant it
onboards, and re-running either is how a later model change reaches the
database. Nothing else creates the database user — `terraform apply` cannot,
which is why a freshly applied foundation shows `no database user` until this
has run.

Every page view is a row: the app adds a `Visit`, saves it, then reads the last
ten back with LINQ and prints them, along with who the database thinks it is.
Some of those rows are not its own — [db-java](../db-java) maps this same table
and writes to it as the same tenant identity, so the two applications share one
`visits` table and each page lists the other's rows. This model is the only
description of it: db-java owns no schema and applies no migrations.

```
pod (SA: workload)
  │  projected token, audience api://AzureADTokenExchange
  ▼
Entra ID ── federated credential: system:serviceaccount:team-alpha:workload
  │  access token for https://database.windows.net/
  ▼
Azure SQL ── database user for the tenant's UAMI, db_datareader + db_datawriter
```

| Variable | Set by | Used for |
|---|---|---|
| `SQL_SERVER` / `SQL_DATABASE` | the ApplicationSet, from the foundation's outputs | where to connect |
| `SQL_CONNECT_TIMEOUT_SECONDS` | the chart | waiting out an auto-pause resume |
| `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_FEDERATED_TOKEN_FILE` | the AKS **workload identity webhook** | which identity, and the token to exchange |
| `CLOUD` / `ENVIRONMENT` | the ApplicationSet | shown on the page |
| `POD_NAME` / `POD_NAMESPACE` | the downward API | shown on the page, and written to the row |

The webhook only mutates a pod that carries the `azure.workload.identity/use`
label and runs as a ServiceAccount annotated with a client ID — the chart sets
the first, `modules/tenant-namespace/azure` sets the second. That is the entire
wiring; the application asks `Azure.Identity` for a token and hands it to
`SqlConnection.AccessToken`.

Three states are ordinary rather than errors, and the page names each one
instead of failing:

| What the page says | What it means |
|---|---|
| `resuming` | the free-tier database auto-paused; it is waking up (EF's retrying execution strategy tried first) |
| `no database user` | the identity is fine, but the bootstrap has not run since this tenant (or this database) was created — `./apps/db-hello/bootstrap.sh <environment>`, or **Deploy Tenants**, which runs the same script |
| `no schema` | the user exists but the `visits` table does not — same workflow applies the migrations |

`GET /healthz` is the liveness and readiness probe, and deliberately does
**not** touch the database: an auto-paused database must not restart the pod.

## Where it is deployed, and how

Argo CD, from the [OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd)
repository — one Application, on Azure only, because the database is an Azure
resource and the identity is an Entra one. It has no production stage on AWS
for the same reason. The full story
— the free offer, where the bootstrap runs, the DNS record, the known gaps — is
in [docs/db-hello-app.md](../../docs/db-hello-app.md).

## Running it locally

Anything `DefaultAzureCredential` accepts works — the interceptor uses it
whenever there is no projected token — so an `az login` as a user with a
database user of its own is enough:

```bash
cd apps/db-hello/src
SQL_SERVER=sql-onek8s-prototype-ab12.database.windows.net \
SQL_DATABASE=appdb \
WELCOME_MESSAGE="Hello from my laptop" dotnet run
```

Your own address needs a firewall rule on the server (`sql_firewall_rules` in
the foundation, or `az sql server firewall-rule create`) — the cluster gets in
through the AKS subnet's service endpoint, which a laptop is not on.

Changing the model is two commands, and the second one needs no database:

```bash
dotnet ef migrations add AddSomething
dotnet ef migrations has-pending-model-changes   # what PR validation runs
```
