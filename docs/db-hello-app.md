# The db-hello application, and the database under it

The platform's second workload, and the one that carries **no credential at
all**. `hello` proves that External Secrets handed a pod a value out of the
cloud's secret backend; `db-hello` removes the value: it reads and writes an
**Azure SQL Database** as the tenant's own managed identity, with a token it
mints per request from the ServiceAccount token Kubernetes projects into the
pod.

| Stage | Cloud | Cluster | URL |
|---|---|---|---|
| — | `azure` | AKS — the Argo CD hub | https://azure-db-hello.onek8s.lol |

One cloud, and no stages, and that is the point of it. Every other piece of the
platform is written so the cloud is a parameter, and [hello](hello-app.md) uses
that to put staging on Azure and production on AWS; this one is the honest
exception — its database is an Azure resource and its identity is an Entra one,
so there is no AWS cluster it could be promoted to, and the application says
`azure` instead of pretending. What did *not* change is
everything around it: the same ingress, the same wildcard certificate, the same
tenant namespace, the same ServiceAccount, the same Argo CD.

> The host follows `platform_apps.domain` in OneK8s' `gitops/envs/<env>.tfvars`, which
> is `onek8s.lol` — the domain the platform's wildcard certificate and DNS zone
> are for. Publishing under a different domain (`onek8s.io`, say) means a
> wildcard and a zone for that domain first; changing `domain` alone would
> leave the host without a certificate.

```
   pod  (ServiceAccount: workload, label azure.workload.identity/use)
    │
    │  projected token, audience api://AzureADTokenExchange
    ▼
   Entra ID  ── federated credential on the tenant's user-assigned identity:
    │            system:serviceaccount:team-alpha:workload
    │  access token for https://database.windows.net/
    ▼
   Azure SQL ── database user for that identity: db_datareader + db_datawriter
```

Nowhere in that chain is there a password, a connection string in a `Secret`,
or a value to rotate. The chart passes the application two strings — a host
name and a database name — and neither of them authorizes anybody.

## Who owns what

Three owners, and the split is not arbitrary: each thing lives in the only
place that *can* own it.

| Piece | Owner | Why not somewhere else |
|---|---|---|
| Logical server, database, network rules | OneK8s, `foundations/azure/sql.tf` | ARM resources, and the cluster's own VNet is here |
| The **tables** | the EF Core model in `apps/db-hello/src/Data` | code-first: the schema is a consequence of the model, versioned with the code that uses it |
| Applying the migrations, and the tenant's **database user** | `db-hello bootstrap`, run by the tenants deploy | data-plane acts over TDS — neither a table nor a database user has an ARM representation, on any provider |
| The Application, its host and its image | OneK8s-argocd, `argocd/` | a commit, like every other workload |

The two middle rows are the interesting ones. There is no `CREATE TABLE`
anywhere in these repositories: `Visit.cs` is the table, and `dotnet ef migrations
add` turns a change to it into a migration. A contained database user is the
other half — `CREATE USER`, executed *inside* the database, which Terraform
cannot express without a third-party provider that would need a database
connection at plan time.

Both are one command, `db-hello bootstrap`, which is part of the application
rather than a script in CI: the same image, the same context, the same
interceptor, so there is one definition of how to reach this database and the
SQL that grants access sits beside the model it grants access to. Around it,
`apps/db-hello/bootstrap.sh` resolves the inputs from Terraform state and opens
a firewall for as long as the run takes — the same shape as the tenant test
secret the Renew Certificate workflow writes into every cloud's vault. The
tenants deploy calls that script and so can an operator, because a stack
applied from a laptop needs the grant just as much as one applied by CI.

Because the script reads platform state and lives with the application, it
needs to be told where the platform repository is: `ONEK8S_ROOT`, defaulting to
a checkout of [OneK8s](https://github.com/olljanat-ai/OneK8s) next to this
one. The tenants workflow checks out both and sets it.

## Code-first, and why the pod never migrates

```
apps/db-hello/src/
├── Data/Visit.cs             the table
├── Data/VisitsContext.cs     the model, and how to connect
├── Data/EntraTokenInterceptor.cs   where the password would have been
├── Data/VisitsContextFactory.cs    a context with no web app around it
├── Bootstrap.cs              migrate + grant, run by CI as the admin
└── Migrations/               generated, committed, applied by bootstrap
```

The application holds `db_datareader` and `db_datawriter` and nothing else, so
it *could not* change the schema even if it tried — which is why `Migrate()` is
reached through the `bootstrap` command, run by the deploy identity, and never
on pod start-up. A `Migrate()` call at boot would mean handing the pod DDL
rights permanently to save one manual step, and permanent DDL rights are
exactly what a compromised workload would want.

```bash
# after changing the model
cd apps/db-hello/src
dotnet ef migrations add AddSomething   # generates, you review, you commit
```

PR validation runs `dotnet ef migrations has-pending-model-changes`, which
contacts no database and fails the build when the model and the migrations have
drifted apart — so "changed the model, forgot the migration" is caught at
review time rather than as a deployment that quietly does nothing.

The one query written in SQL is the one with no model behind it: `SUSER_SNAME()`
and the role membership, which are the *server's* view of the connection. It
maps to a keyless type (`DatabaseIdentity`) that owns no table and produces no
migration.

## The free offer

`foundations/azure/sql.tf` creates the database with **azapi**, not azurerm:
the free offer is two ARM properties (`useFreeLimit`,
`freeLimitExhaustionBehavior`) that the pinned azurerm provider does not carry,
and azapi is already in this stack for the AKS managed namespace.

| Setting | Value | What it buys |
|---|---|---|
| `useFreeLimit` | `true` | 100,000 vCore seconds + 32 GB a month, free, for the life of the subscription |
| `freeLimitExhaustionBehavior` | `AutoPause` | when the month's allowance runs out the database sleeps instead of billing |
| sku | `GP_S_Gen5`, 2 vCores | serverless General Purpose — the only tier the offer exists on (4 vCores max) |
| `autoPauseDelay` | 60 minutes | an idle lab database stops spending the allowance by itself |
| `maxSizeBytes` | 32 GB | the free ceiling |

Up to **10** free databases per subscription, and the first one fixes the
region for the rest of them. `BillOverUsage` is the other exhaustion behaviour
and it does what it says; `AutoPause` is the default here because an
unattended lab should never be able to produce a bill.

Auto-pause is visible from the browser, and deliberately not hidden: the first
request after an idle hour waits for the database to wake. EF Core's own
retrying execution strategy (`EnableRetryOnFailure`) covers that — 40613 is on
its list of Azure SQL transient errors — and if the wake takes longer than the
retries allow, the page says **“resuming”** rather than showing an error. The
`/healthz` probe never touches the database, so a sleeping database cannot
restart the pod or take it out of the Service.

## No password exists to leak

The logical server is created in **Microsoft Entra-only** authentication mode
(`azuread_authentication_only = true`). With that set, the provider refuses an
`administrator_login`/`administrator_login_password` pair — there is no SQL
login on this server at all, so there is nothing to store, rotate or forget.

The server's Entra administrator defaults to **the identity running the
deploy**, which is what makes the tenants deploy (the same service principal)
able to create a user for somebody else's identity. Point
`sql_admin_object_id` at a break-glass Entra group instead if you would rather
CI were not it; nothing in this stack writes to the directory either way, the
same rule the Argo CD SSO app registration follows.

## How the cluster reaches it

```
AKS subnet (snet-aks)  ──Microsoft.Sql service endpoint──▶  Azure SQL
        allowed by name in a virtual network rule, not by IP address
```

The subnet carries the `Microsoft.Sql` service endpoint and is allowed on the
server as a VNet rule. Pods on Azure CNI overlay SNAT to their node's address,
which is in that subnet, so nothing depends on the pod CIDR and no node address
is written down anywhere.

There are **no firewall rules by default**, so the public endpoint answers
nobody else. `sql_firewall_rules` adds standing exceptions (an office range, a
jump host); the tenants deploy needs none, because it opens a rule for its own
runner address and removes it again in the same run, whatever happens.

## Where the bootstrap happens

Nowhere of its own: it is the second job of the tenants deploy, so onboarding a
tenant and giving it a database are one action.

```bash
gh workflow run deploy-tenants.yml -f environment=prototype
```

```
tenants  namespace, quota, netpol, cloud identity, namespaced SecretStore
sql      ↳ for every Azure tenant that stack onboarded
```

That job is a single call to **`apps/db-hello/bootstrap.sh`**, which is also the
whole story for anyone applying the stacks by hand — the database user is a
data-plane act with no Terraform resource behind it, so `terraform apply` alone
leaves the page saying `no database user` however many times it is run:

```bash
# as the SQL server's Entra administrator, which by default is whoever
# applied foundations/azure
az login
ONEK8S_ROOT=../OneK8s ./apps/db-hello/bootstrap.sh prototype
```

One script, two callers, so a laptop and CI cannot drift apart. It resolves
everything from state rather than taking it as configuration — the server and
database from `foundations/azure`, every Azure tenant's identity from the
`tenants` stack — opens a firewall rule for the address it is running from and
removes it again whatever happens, then, once per tenant, does the two things an
empty database needs:

```
db-hello bootstrap --user <identity> --client-id <guid>
   ├── Database.MigrateAsync()     the schema, from the committed migrations
   └── CREATE USER … / ALTER ROLE  the access, for this tenant's identity
```

Every step is guarded, so a deploy with nothing to do is a no-op that still
prints what it found — which is what makes it safe to run on every tenants
deploy, and is also how a *later* schema change reaches the database: add a
migration, merge it, deploy tenants. A rebuilt foundation needs one too (the
server name carries a random suffix, so a new foundation is a new, empty
database).

The job is separate from the apply rather than part of it, so a database that
cannot be reached shows up as its own red mark and never touches a tenant
onboarding that already succeeded. An environment whose Azure foundation has
`enable_sql = false`, or which has no Azure tenants, skips it entirely.

It authenticates exactly as the pod does: the same `VisitsContextFactory`, so
the same interceptor puts an Entra token on the connection — from the deploy
service principal in CI, from a federated ServiceAccount token in the pod. One
code path, two credentials, and no connection string with a password in either.
The pod runs the same image and cannot do any of this: the database refuses
both acts to `db_datareader`/`db_datawriter`, so the boundary is the grant and
not the absence of the code.

```sql
-- apps/db-hello/src/Bootstrap.cs, in essence
CREATE USER [id-tenant-team-alpha-prototype] WITH SID = 0x…, TYPE = E;
ALTER ROLE db_datareader ADD MEMBER [id-tenant-team-alpha-prototype];
ALTER ROLE db_datawriter ADD MEMBER [id-tenant-team-alpha-prototype];
```

`WITH SID`, **not** `FROM EXTERNAL PROVIDER`, and that choice is the reason the
platform still holds no directory permissions. `FROM EXTERNAL PROVIDER` makes
the SQL server look the principal up in Microsoft Entra ID, which requires the
*server's own identity* to hold Directory Readers (or `User.Read.All` +
`GroupMember.Read.All` + `Application.Read.All` on Graph). Supplying the SID
directly skips the lookup entirely: a managed identity's SID is its client ID
in binary, which is exactly what `CAST(… AS uniqueidentifier)` produces.

The SID is also what the bootstrap *checks*, not just what it writes. A
database user is identified by its SID and only labelled by its name, so one
carrying the right name and a stale SID — which is what a rebuilt tenants stack
leaves behind, since the managed identity keeps its name and gets a new client
ID — authenticates nobody:

```
Login failed for user '<token-identified principal>'.  (error 18456)
```

The grant drops and recreates a user whose SID is not this identity's, and
reads the SID back afterwards to compare rather than to print, so a run that
prints a user is a run whose token will be accepted.

The two roles are the whole of the application's authorization. It may read and
write rows; it may not create a table, grant anything, or reach another
database. That is why the migrations are applied by the bootstrap and not by
the application on first run.

## What the page shows

```
Cloud              azure
Environment        prototype
Namespace          team-alpha
Pod                db-hello-7d4f9c8b6d-x2k9p

Azure SQL, without a password
Server             sql-onek8s-prototype-ab12.database.windows.net
Database           appdb
Workload identity  8f3c…-…-…   (AZURE_CLIENT_ID, injected by the webhook)
Signed in as       id-tenant-team-alpha-prototype
Database user      id-tenant-team-alpha-prototype
Database roles     db_datareader, db_datawriter

Last 10 page views, read back through Entity Framework
```

`Signed in as` is the interesting line: it is `SUSER_SNAME()`, the database's
own answer to “who is this connection?”, and it names the tenant's managed
identity. Every page view inserts a row and reads the last ten back, so the
table is both the demonstration and the only state the application has.

Rows written by `db-java-…` pods are [db-java](db-java-app.md), the Java
application on the same database: it maps this table as the same tenant
identity, and therefore as the same database user. It owns none of the schema —
the EF Core model here remains the only description of `visits` — so a model
change is made in this application and followed by that one.

Three states are ordinary rather than broken, and each names its own fix:

| Page says | Fix |
|---|---|
| `resuming` | nothing — the free-tier database is waking up |
| `no database user` | run **Deploy Tenants**, or `./apps/db-hello/bootstrap.sh <environment>` — either grants access |
| `no schema` | same workflow; it applies the migration that creates `visits` |
| `no token` | the pod is missing the workload-identity label or the ServiceAccount annotation — check the tenants stack applied |

## Non-root, like everything else in a tenant namespace

The pod runs as **UID 1654**: `USER $APP_UID` in the `Dockerfile`, and
`runAsNonRoot: true` with `runAsUser`/`runAsGroup` 1654, a read-only root
filesystem, `RuntimeDefault` seccomp and every capability dropped in the
chart. No Kubernetes ServiceAccount token is mounted either — the projected
*Azure* token the workload identity webhook adds is a separate volume and is
unaffected.

None of that is this chart being tidy. The tenant namespace enforces the
`restricted` Pod Security Standard, so a pod that had not said all of it would
be refused at admission
([architecture.md](https://github.com/olljanat-ai/OneK8s/blob/main/docs/architecture.md#nothing-runs-as-root)).

It is worth stating here more than for `hello`, because this application's
runtime image is the **full `aspnet:10.0-noble`** rather than a chiseled one —
`Microsoft.Data.SqlClient` refuses to open a connection in Globalization
Invariant Mode, so ICU has to be present. That image has a shell and a root
user; the `USER` instruction and the pod's `securityContext` are what keep the
application from being either, and PR validation checks both before a merge.

## Operating it

```bash
# The Application, on the hub
kubectl -n argocd get applicationset db-hello
kubectl -n argocd get application db-hello-azure

# The pod, and whether the webhook mutated it
kubectl -n team-alpha get pods -l app.kubernetes.io/name=db-hello
kubectl -n team-alpha get pod -l app.kubernetes.io/name=db-hello \
  -o jsonpath='{.items[0].spec.containers[0].env[?(@.name=="AZURE_CLIENT_ID")].value}{"\n"}'

# The database, from the foundation's state (in the OneK8s checkout)
cd "$ONEK8S_ROOT/foundations/azure"
terraform output -raw sql_server_fqdn
terraform output -raw sql_database_name

# Is it awake, and is it actually on the free offer? (a paused database
# reports status "Paused", and wakes on the next page view)
az sql db show \
  --resource-group "$(terraform output -raw resource_group_name)" \
  --server "$(terraform output -raw sql_server_name)" \
  --name "$(terraform output -raw sql_database_name)" \
  --query "{status:status, sku:currentServiceObjectiveName, freeLimit:useFreeLimit}" -o table
```

DNS is out of band here as everywhere else: create an A record for
`azure-db-hello.onek8s.lol` pointing at the AKS cluster's Traefik Service. TLS
needs nothing — the Ingress carries no certificate and Traefik's default
TLSStore serves the platform wildcard.

## Known gaps

- **The database is reached over its public endpoint.** A VNet rule means only
  the AKS subnet may use it, which is a real boundary and not a cosmetic one,
  but a **private endpoint** is the stronger answer. It needs private DNS the
  platform does not run yet, and it would put the database out of reach of the
  tenants deploy — which would then have to run somewhere inside the VNet.
- **One database, shared by tenants — and one table, shared by two
  applications.** The free offer allows ten, but this is one database with one
  table; a second tenant would get its own user in the *same* database and
  could read the first one's rows. Isolation here is the
  Entra identity and the roles, not the schema. Per-tenant databases, or
  row-level security keyed on `DATABASE_PRINCIPAL_ID()`, is the next step and
  is deliberately not taken in a lab that has one application.
- **The bootstrap is a separate act.** Deploying the foundation does not make
  the page work; the tenants stack has to be deployed *after* it, because the
  grant lives in that deploy's second job. That is the price of not giving
  Terraform a database connection, and it is visible rather than silent — the
  page says which step is missing.
- **The image tag is `latest`.** The build workflow pushes an immutable
  `sha-<short>` alongside it, but nothing writes that tag back into
  `values.yaml`, so a pod that restarts pulls whatever `latest` points at.
  `hello` no longer has this gap — Kargo writes an immutable tag into the
  delivery-plane repository per stage — but db-hello has one cluster and so no
  release path to promote along, and a Warehouse for a single stage would be
  ceremony rather than a gate. Pin `image.tag` for a reproducible deploy.
- **`AutoPause` costs the first visitor a wait.** Roughly a minute, once an
  hour of idleness. `sql_auto_pause_delay_in_minutes = -1` turns it off and
  spends the free allowance continuously instead — 100,000 vCore seconds is
  about 13 days of a single always-on 0.5-vCore database, so an environment
  that never pauses will run out before the month does.
