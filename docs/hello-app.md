# The hello application

The platform's first workload, and the smallest end-to-end proof that it works:
a .NET 10 web page showing a welcome message and the value of a **test secret**
read out of the host cloud's own secret backend. One image and one chart travel
a release path of two stages, on two different clouds:

| Stage | Cloud | Cluster | Sync | URL |
|---|---|---|---|---|
| `staging` | `azure` | AKS — the Argo CD hub | automatic | https://azure-hello.onek8s.lol |
| `production` | `aws` | EKS — a registered spoke | **manual promotion** | https://aws-hello.onek8s.lol |

That is the point of running it on two clouds rather than four copies of the
same thing: staging and production are not two configurations of one cluster,
they are two clusters on two providers, and the same artefact has to satisfy
both. A merge to `main` reaches Azure by itself; nothing reaches AWS until a
human says so.

The platform's other example, [db-hello](db-hello-app.md), is the deliberate
opposite: one cloud, a real Azure SQL database, and no secret at all. This one
is about a value that reaches both stages identically; that one is about not
having a value in the first place.

Nothing in the application or its chart is cloud-specific. Exactly one string
differs between the two — the key the secret is stored under, because Secrets
Manager names are paths where Key Vault names are flat — and that string is
resolved by the ApplicationSet and passed in, so the chart itself contains no
`if aws` of any kind.

```
   OneK8s-hello (this repository)        OneK8s-argocd            OneK8s
   ──────────────────────────────        ─────────────            ──────
   apps/hello/src ──build──▶ ghcr.io/…/hello
   apps/hello/chart  ◀──── syncs ──── argocd/  ◀── syncs ── Application
                                       ├── AppProject         platform-gitops
                                       ├── AppSet hello-staging   (gitops/root-app.tf)
                                       ├── AppSet hello-production
                                       └── AppSet db-hello
                                             │
                    ┌────────────────────────┴───────────────────┐
                    ▼                                            ▼
          hello-staging  (AKS, in-cluster)          hello-production  (EKS, spoke)
          auto-synced                               manual: approval, then sync
                    │                                            │
                    ▼                                            ▼
               Key Vault                                  Secrets Manager
                  via External Secrets, per tenant identity
```

## Three repositories, and who owns what

The application is here; where and when it is deployed is not:

| Repository | Owns |
|---|---|
| **OneK8s-hello** (this one) | `apps/hello` — the source, the Dockerfile, the chart, the image build |
| [OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd) | The `AppProject` and the `hello-staging` / `hello-production` ApplicationSets: the stages, and the gate in front of production |
| [OneK8s](https://github.com/olljanat-ai/OneK8s) | The clusters, the tenants, the Argo CD hub, and the one root `Application` that points Argo CD at the delivery plane |

Exactly one Argo CD object is created by Terraform: the **root Application**,
`gitops/root-app.tf` in OneK8s. It points Argo CD at `argocd/` in the
OneK8s-argocd repository and owns nothing else. Everything else — the
AppProject, the ApplicationSets and every Application they generate — is YAML
there, so a change to how this application is released is a commit in that
repository, and a change to the application itself is a commit here.

```hcl
# OneK8s: gitops/envs/prototype.tfvars
platform_apps = {
  repo_url             = "https://github.com/olljanat-ai/OneK8s-argocd.git"
  target_revision      = "main"
  apps_repo_url        = "https://github.com/olljanat-ai/OneK8s-hello.git"
  apps_target_revision = "main"
  tenant               = "team-alpha"
  domain               = "onek8s.lol"
}
```

Those values are handed to the delivery-plane chart as Helm values rather than
committed into it, which is what lets **one copy** of it serve prototype, dev,
staging and prod: the platform environment decides which spokes the cluster
generator selects, which revision is synced and which hosts the applications
get. `platform_apps = { enabled = false }` registers spokes without deploying
anything, and the root Application is skipped automatically when the hub's
foundation was applied with `enable_argocd = false`.

The root Application carries Argo CD's cascade finalizer, so `terraform
destroy` on the gitops stack takes the ApplicationSets — and the workloads they
put on the spokes — with it.

> The platform *environment* (`prototype`, `dev`, …) and an application *stage*
> (`staging`, `production`) are different axes. One platform environment holds
> both the Azure staging cluster and the AWS production cluster of this app.

## The two stages

`argocd/templates/applicationset-hello.yaml` in OneK8s-argocd ranges over
`apps.hello.stages` and produces one ApplicationSet per stage. How each stage
finds its cluster follows from the topology rather than from the stage:

- **staging** names its cluster (`in-cluster`) in a one-element `list`
  generator. Argo CD's built-in entry for the cluster it runs on has no Secret
  and therefore no labels to select on, so it cannot come from a cluster
  generator.
- **production** names only its cloud, and a `clusters` generator selects the
  spoke labelled `onek8s.io/cloud: aws` for this environment — the label
  `modules/argocd-spoke` puts on the cluster Secret. No AWS spoke registered in
  this environment, no production Application: the release path follows the
  gitops stack by itself.

Applications address their cluster by `destination.name`, not by server URL:
the EKS endpoint is whatever that service handed out, and Argo CD already knows
it from the cluster Secret.

Per-stage values reach the chart as Helm parameters — `cloud`, `environment`,
`tenant`, `ingress.host` (`<cloud>-hello.onek8s.lol`), `secret.name`,
`secret.remoteKey` and the welcome message. Two of them are required and the
chart fails to render without them: `ingress.host`, because an application
nobody can open proves nothing, and `secret.remoteKey`, because guessing a key
would mean guessing the cloud.

### The gate in front of AWS

The staging Application carries `syncPolicy.automated` (prune + selfHeal); the
production one deliberately carries none. Argo CD still tracks production — it
turns `OutOfSync` the moment the chart or the image tag moves ahead — but it
applies nothing to EKS until a human syncs it:

```bash
argocd app diff hello-production --grpc-web     # what would change
argocd app sync hello-production --grpc-web     # open the gate
```

The recorded path is the **Promote to production** workflow in OneK8s-argocd:
it prints the diff, then waits on a GitHub environment with required reviewers
before syncing, so the approval and what was approved end up in one run log.
The missing `automated` block is the guarantee; the workflow is the paperwork.
PR validation there renders the chart and fails if the two ever disagree.

### The AppProject

Every generated Application belongs to `onek8s-platform`, which draws three
boundaries Argo CD enforces before applying anything:

| Field | Effect |
|---|---|
| `sourceRepos` | only the platform's own two repositories may be synced |
| `destinations` | only the tenant namespace, on any registered cluster |
| `clusterResourceWhitelist: []` | nothing cluster-scoped, ever |

The last one turns the convention *"tenant onboarding stays in Terraform, only
workloads belong in GitOps"* — listed as a gap in
[argocd.md](https://github.com/olljanat-ai/OneK8s/blob/main/docs/argocd.md#known-gaps) — into a mechanism. An Application that tried
to manage the `team-alpha` Namespace is refused rather than left to fight the
tenants stack over it. `CreateNamespace=false` is set for the same reason: a
missing namespace should fail the sync loudly, not produce a namespace with
none of the tenant's quota, network policy or SecretStore.

## The test secret

This is the part worth looking at. The application's manifest names a secret
and a store, and no credential at all:

```yaml
apiVersion: external-secrets.io/v1
kind: ExternalSecret
spec:
  secretStoreRef:
    name: tenant-store       # namespaced — only this namespace may use it
    kind: SecretStore
  data:
    - secretKey: test
      remoteRef:
        key: team-alpha-test
```

`tenant-store` is created by the platform's `modules/tenant-namespace` and authenticates as
the tenant's ServiceAccount, exchanged for the tenant's cloud identity through
workload identity federation. That identity can read only its own name-prefix
slice of the shared backend, enforced in the cloud IAM plane
([ADR-0001](https://github.com/olljanat-ai/OneK8s/blob/main/docs/adr/0001-per-tenant-identities-and-namespaced-secretstores.md)).
Asking for another tenant's secret is not a mistake this chart can make
quietly: the read is refused and the ExternalSecret goes `SecretSyncedError`.

The prefix is spelled differently on AWS — and that difference is **not in the
chart**. `apps/hello/chart` branches on nothing: it takes `secret.remoteKey` as
a plain required value and asks the backend for exactly that. The ApplicationSet
resolves it per stage (`argocd/templates/applicationset-hello.yaml` in
OneK8s-argocd), which is the right place for it, because which cloud a cluster
is happens to be the platform's business and never the application's:

```yaml
# rendered per stage, because the stage fixes the cloud
- name: secret.remoteKey
  value: "prototype/team-alpha/test"     # production, on Secrets Manager
- name: secret.remoteKey
  value: "team-alpha-test"               # staging, on Key Vault
```

The chart is handed the finished key and never learns which cloud it landed on
— `cloud` reaches it only as a string to print on the page and stamp on a
label. That is the property this application exists to demonstrate, so it is
worth keeping literally true.

| Stage | Cloud | Backend | Remote key | Enforced by |
|---|---|---|---|---|
| `staging` | `azure` | Key Vault | `team-alpha-test` | ABAC `StringStartsWith 'team-alpha-'` |
| `production` | `aws` | Secrets Manager | `prototype/team-alpha/test` | IAM ARN prefix `prototype/team-alpha/*` |

(The platform distributes the same value to GCP's Secret Manager and OCI Vault
as well, flat-named like Key Vault's; this application simply has no stage
there.)

### Where the value comes from

The secret is **not** created by any Terraform stack, and not by this
repository at all — tenant data is the tenant's, and the tenants stack
deliberately writes none of it. It is created where the platform's other shared
value is: the **Renew Certificate** workflow in
[OneK8s](https://github.com/olljanat-ai/OneK8s), which generates it in Key
Vault beside the wildcard and publishes it to the other clouds in the same
`distribute` run.

```
   Renew Certificate (renew)                Renew Certificate (distribute)
        │                                          │
        ▼                                          ▼
   Key Vault  team-alpha-test  ──────────▶  Secrets Manager  prototype/team-alpha/test
   (the source of truth)                    Secret Manager   team-alpha-test
                                            OCI Vault        team-alpha-test
```

Both objects travel the same road for the same reason — one vault as the
source of truth, one run to copy it out — and the value rotates whenever the
certificate is renewed, so every renewal exercises the rotation path end to
end: ESO re-reads the backend within the hour, the kubelet refreshes the
mounted file, and the page shows the new value without a restart. A run whose
certificate was **not** due leaves the secret alone, unless it is missing
altogether, which is what seeds a fresh environment on the first run.

The value itself is a line naming the environment and the run that wrote it,
identical on every cloud it is distributed to — comparing the staging page with
the production one is the whole end-to-end check:

```
OneK8s prototype test secret — issued 2026-08-14T03:17:12Z by run 17482913 (a1b2c3d4)
```

It is not confidential (a public page publishes it verbatim) and the run
summary prints it, which is what makes "is `aws-hello` showing the current
value?" answerable without opening a vault. `tenant` is a workflow input,
defaulting to `team-alpha` — it must name the tenant the application is
released into (`platform_apps.tenant` in OneK8s' `gitops/envs/<env>.tfvars`),
because that is the only prefix its identity may read.

Until the first renewal run the page renders *"not available"* instead of a
value, which is also what it looks like while External Secrets is still
syncing. To seed it by hand instead — or to put a value of your own choosing
in front of the page — write it once per cloud, from a checkout of the platform
repository (the vault names live in its state):

```bash
# Azure — the vault name carries a random suffix, so read it from the state
KV=$(cd foundations/azure && terraform output -raw key_vault_uri)
az keyvault secret set --id "${KV}secrets/team-alpha-test" --value "hello from Key Vault"

# AWS — must be encrypted with the tenant CMK: the tenant's kms:Decrypt grant
# is scoped to that key, so a secret under the default aws/secretsmanager key
# is unreadable to it.
aws secretsmanager create-secret \
  --name prototype/team-alpha/test \
  --kms-key-id alias/onek8s-prototype-secrets \
  --secret-string "hello from Secrets Manager"

# GCP
printf 'hello from Secret Manager' \
  | gcloud secrets create team-alpha-test --data-file=- --replication-policy=automatic

# OCI — content is base64, and the secret belongs to the foundation's vault/key
cd foundations/oci
oci vault secret create-base64 \
  --compartment-id "$(terraform output -raw compartment_id)" \
  --vault-id       "$(terraform output -raw vault_id)" \
  --key-id         "$(terraform output -raw vault_key_id)" \
  --secret-name    team-alpha-test \
  --secret-content-content "$(printf 'hello from OCI Vault' | base64 -w0)"
```

A hand-written value survives until the next renewal overwrites it, which is
the trade for having the workflow keep every cloud in step.

Rotating the value needs nothing on the Kubernetes side: External Secrets
re-reads the backend hourly, the kubelet refreshes the mounted file in place,
and the app reads that file per request — so the new value appears on a reload
without a pod restart.

## The image

`.github/workflows/build-hello.yml` builds `apps/hello` on every push to `main`
that touches it and pushes to **GHCR** as
`ghcr.io/olljanat-ai/onek8s-hello/hello`, tagged `latest` and `sha-<short>`.
Pull requests build without pushing. The build is the whole of what CI does to
a release: staging picks the image up on Argo CD's next sync, and production
waits for the promotion. The package must be **public** for the clusters
to pull it — none of them has a pull secret, which is deliberate: an image
pull credential per cloud is exactly the kind of sprawl the platform avoids
elsewhere. GHCR packages default to private, so make it public once, under the
package's settings.

The image is a two-stage build: the .NET 10 SDK compiles, and a **chiseled**
ASP.NET runtime serves — Ubuntu with no shell, no package manager and a
non-root default user. The pod adds the rest: read-only root filesystem, all
capabilities dropped, no ServiceAccount token mounted.

Nothing in it runs as root, and that is stated in four places rather than
inherited from one:

| Where | What it says |
|---|---|
| the base image | `User: "1654"` — its own default, before anything of ours |
| `Dockerfile` | `USER $APP_UID`, which resolves to the same 1654 |
| the pod's `securityContext` | `runAsUser: 1654`, `runAsGroup: 1654`, `runAsNonRoot: true` |
| the namespace | `pod-security.kubernetes.io/enforce: restricted` — the tenant namespace admits nothing else |

The third is both a statement and a check: `runAsNonRoot` makes the kubelet
refuse to start the pod at all if the image ever resolves to UID 0, so a base
image that changed underneath us fails the deploy instead of quietly handing
the process root. There is no `fsGroup` — the secret volume is read-only and
world-readable and `/tmp` is an emptyDir, so there is nothing to chown.

The fourth is the platform's, not the application's, and it is the one that
does not depend on this chart being written carefully: every tenant namespace
enforces the `restricted` Pod Security Standard, so a workload that says
nothing about who it runs as is refused at admission on all four clusters
(see [architecture.md](https://github.com/olljanat-ai/OneK8s/blob/main/docs/architecture.md#nothing-runs-as-root)). The chart
satisfies it exactly — non-root, no privilege escalation, all capabilities
dropped, `RuntimeDefault` seccomp — and PR validation asserts those four
fields in the rendered output, so a regression fails the pull request rather
than turning into an Argo CD sync error in four places at once.

## Operating it

```bash
# The hub's view of the whole thing
kubectl -n argocd get application platform-gitops
kubectl -n argocd get applicationset hello-staging hello-production
kubectl -n argocd get applications -L onek8s.io/stage,onek8s.io/cloud

argocd app list --grpc-web
argocd app get hello-production --grpc-web        # OutOfSync until promoted
argocd app diff hello-production --grpc-web       # what a promotion would do

# On any cluster: did the secret actually arrive?
kubectl -n team-alpha get externalsecret hello
kubectl -n team-alpha describe externalsecret hello     # the reason, when it did not
kubectl -n team-alpha get pods -l app.kubernetes.io/name=hello

# What is published, and where the A record has to point
kubectl -n team-alpha get ingress hello
kubectl -n traefik get svc traefik
```

DNS is out of band on every cloud, as everywhere else here: create an A record
for `azure-hello.onek8s.lol` and `aws-hello.onek8s.lol` pointing at their
cluster's Traefik Service address. TLS needs nothing — the Ingress carries no certificate and Traefik's
default TLSStore serves the `*.onek8s.lol` wildcard.

## Known gaps

- **The image tag is `latest`, which blunts the promotion gate.** The build
  workflow pushes an immutable `sha-<short>` tag alongside it, but nothing
  writes that tag back into `apps/hello/chart/values.yaml`, so what Git says is
  deployed is not by itself the whole truth. Argo CD sees no diff when only the
  image moves, so a production pod that restarts for its own reasons pulls the
  newest build without anyone approving it — `imagePullPolicy: Always` and a
  moving tag, not the sync policy, are what let that happen. Pin `image.tag` to
  a `sha-` tag (or set `apps.hello.stages.production.targetRevision` to a
  release tag in OneK8s-argocd) to make a promotion the only way production
  moves.
- **The test secret follows the certificate's schedule, not its own.** The
  Renew Certificate workflow generates it, so it rotates when the wildcard is
  renewed (roughly quarterly) and reaches the other clouds only on a
  `distribute` run, which is manual by design. That is deliberate — one
  source of truth and one copy-out path for both objects — but it does mean a
  rotation is not visible on EKS until someone distributes it.
- **Staging and production share one tenant and one secret.** The two stages
  read the same tenant's value out of two backends, so "production" here is a
  second cluster on a second cloud, not a second blast radius: a bad value
  reaches both. A real production stage would have its own tenant, its own
  vault entry and its own environment.
- **Hostnames are one label deep and carry no environment.** That is what the
  wildcard covers, so a second environment cannot also publish
  `aws-hello.onek8s.lol`. A second environment needs its own `domain` in
  `platform_apps` (and a wildcard to match).
- **One tenant.** The application is released into `team-alpha` on both stages
  because that tenant exists everywhere in this environment. The AppProject is
  scoped to that one namespace; a second tenant means a second destination.
- **The hub deploys to itself.** `hello-staging` targets `in-cluster`, so the
  cluster running Argo CD also runs a workload. That is fine for a lab and is
  the reason staging appears as a static list element rather than as a
  registered spoke — but it does mean the delivery plane and staging share a
  cluster, and production does not.
- **Promotion syncs, it does not build.** Approving the promotion applies
  whatever `main` says at that moment. With `targetRevision` left empty,
  staging and production track the same branch, so a promotion always carries
  everything merged since the last one.
