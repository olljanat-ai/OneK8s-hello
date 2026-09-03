# OneK8s-hello

**The example applications of the [OneK8s](https://github.com/olljanat-ai/OneK8s)
platform** — their source, their Dockerfiles and their Helm charts. Nothing in
here decides where or when they are deployed: that is a delivery plane's
business — and the platform now runs two of them,
[OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd) and
[OneK8s-fluxcd](https://github.com/olljanat-ai/OneK8s-fluxcd), which deploy
these charts unchanged to different tenants so the two can be compared.

```
apps/
├── hello/       # .NET 10 page showing a secret read out of the cloud's vault
└── db-hello/    # .NET 10 page reading and writing Azure SQL with no credential
docs/
├── hello-app.md
└── db-hello-app.md
```

| Repository | Owns |
|---|---|
| [OneK8s](https://github.com/olljanat-ai/OneK8s) | The clusters and the platform: foundations, tenants, the Argo CD hub, Kargo, and the root `Application` that bootstraps the delivery plane. |
| [OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd) | Where and when an application is deployed: the `AppProject`, the `ApplicationSet`s, and the Kargo `Warehouse` and `Stage`s that decide which build each cluster runs. |
| [OneK8s-fluxcd](https://github.com/olljanat-ai/OneK8s-fluxcd) | The same, without a hub or a promotion engine: one directory per cluster, and the commit that says which build that cluster runs. |
| **OneK8s-hello** (this one) | What is deployed. |

## The two applications

### `hello` — one artefact, two clouds, two meanings

A page showing a welcome message and a **test secret** read out of the host
cloud's own backend by External Secrets, through the tenant's namespaced
`SecretStore`. It travels a release path of two stages:

| Stage | Cloud | Cluster | How a build gets there | URL |
|---|---|---|---|---|
| `staging` | `azure` | AKS — the Argo CD hub | Kargo promotes every new build automatically | https://azure-hello.onek8s.lol |
| `production` | `aws` | EKS — a spoke, reached by its own agent | **a person promotes it, and only from `staging`** | https://aws-hello.onek8s.lol |

Staging and production are two clusters on two providers, so the same image and
the same chart have to satisfy both. Exactly one string differs between them —
the key the secret is stored under, a path on Secrets Manager and a flat name in
Key Vault — and the ApplicationSet resolves it and passes it in, so the chart
contains no `if aws` of any kind.

**Nothing reaches AWS without a person.** [Kargo](https://kargo.io) freezes
each build together with the chart it is deployed with, promotes that to staging
by itself, and then stops: production takes only what staging has already run,
and only when somebody promotes it. Promoting writes the image tag and chart
revision into the delivery-plane repository, so what each cluster runs is a line
in Git and every release is a commit naming who asked for it. Full walkthrough:
[docs/hello-app.md](docs/hello-app.md).

### `db-hello` — Azure only, and no credential at all

A page that reads and writes an **Azure SQL Database** as the tenant's own
managed identity: no connection string, no password, no `Secret` of any kind.
Its database is an Azure resource and its identity is an Entra one, so it is
deployed to Azure and nowhere else — there is no AWS production stage it could
honestly be promoted to. Its schema is code-first (EF Core), and the migrations
plus each tenant's database user are applied by `apps/db-hello/bootstrap.sh`,
which the platform's **Deploy Tenants** workflow runs. Full walkthrough:
[docs/db-hello-app.md](docs/db-hello-app.md).

That script is the one place these two repositories meet: it reads the
platform's Terraform state, so it needs a checkout of OneK8s and takes its path
from `ONEK8S_ROOT` (default: `../OneK8s`).

## Images

Both applications publish to **GHCR** on every merge to `main` that touches
them. `hello` publishes exactly one immutable tag per build, `sha-<short>`, and
no moving tag: Kargo identifies a release by its tag, so a tag that can point
somewhere else tomorrow would make "production runs `sha-a1b2c3d`" a statement
with no content.

```
ghcr.io/olljanat-ai/onek8s-hello/hello
ghcr.io/olljanat-ai/onek8s-hello/db-hello
```

The packages must be **public** — no cluster on any cloud has a pull secret,
which is deliberate — and GHCR packages default to private, so make each one
public once under its package settings.

## Working on them

```bash
# hello, on a laptop
cd apps/hello/src
TEST_SECRET="a local value" WELCOME_MESSAGE="Hello from my laptop" dotnet run

# the charts, as the delivery plane renders them. image.tag is required and has
# no default: in the platform it is whatever Kargo last promoted to that stage.
helm template hello-staging apps/hello/chart \
  --set cloud=azure --set ingress.host=azure-hello.onek8s.lol \
  --set secret.remoteKey=team-alpha-test --set image.tag=sha-0000000
helm template hello-production apps/hello/chart \
  --set cloud=aws --set ingress.host=aws-hello.onek8s.lol \
  --set secret.remoteKey=prototype/team-alpha/test --set image.tag=sha-0000000
```

PR validation renders both charts on both stages, checks that they still satisfy
the `restricted` Pod Security Standard the tenant namespaces enforce (non-root,
no privilege escalation, all capabilities dropped, `RuntimeDefault` seccomp) and
that db-hello's EF model and its migrations still agree.

Merging to `main` builds the image; Kargo turns it into Freight and promotes it
to staging by itself; production waits for someone to promote it.
