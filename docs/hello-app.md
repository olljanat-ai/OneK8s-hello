# The hello application

The platform's first workload, and the smallest end-to-end proof that it works:
a .NET 10 web page showing a welcome message and the value of a **test secret**
read out of the host cloud's own secret backend. One image and one chart travel
a release path of two stages, on two different clouds:

| Stage | Cloud | Cluster | How a build gets there | URL |
|---|---|---|---|---|
| `staging` | `azure` | AKS — the Argo CD hub | Kargo promotes it automatically | https://azure-hello.onek8s.lol |
| `production` | `aws` | EKS — a spoke, reached by its own agent | **a person promotes it, from `staging`** | https://aws-hello.onek8s.lol |

That is the point of running it on two clouds rather than four copies of the
same thing: staging and production are not two configurations of one cluster,
they are two clusters on two providers, and the same artefact has to satisfy
both. A merge to `main` reaches Azure by itself; nothing reaches AWS until a
person says so.

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
   apps/hello/src ──build──▶ ghcr.io/…/hello:sha-a1b2c3d ─┐
   apps/hello/chart @ 9f4e2b1 ────────────────────────────┤ watched by
                                                          ▼
                                       Kargo Warehouse "hello"
                                             │  Freight = tag + chart commit
                                    ┌────────┴────────┐
                          auto      ▼                 ▼   a person promotes,
                              Stage staging      Stage production   only from staging
                                    │                 │
                                    └── commit ───────┘
                                      stages/hello/<stage>.yaml
                                             │
                                       read back by
                                             ▼
   apps/hello/chart  ◀──── syncs ──── argocd/  ◀── syncs ── Application
                                       ├── AppProject         platform-gitops
                                       ├── AppSet hello-staging   (gitops/root-app.tf)
                                       ├── AppSet hello-production
                                       └── AppSet db-hello
                                             │
                    ┌────────────────────────┴───────────────────┐
                    ▼                                            ▼
          hello-staging  (AKS, in-cluster)          hello-production  (EKS, spoke)
          auto-synced, to the promoted build        auto-synced, to the promoted build
                    │                                            │
                    ▼                                            ▼
               Key Vault                                  Secrets Manager
                  via External Secrets, per tenant identity
```

Both Applications are auto-synced, and the gate is upstream of them: Argo CD
makes each cluster match what Git says, and Git says what Kargo last promoted.

## Four repositories, and who owns what

The application is here; where and when it is deployed is not — and it is now
answered twice, by two delivery planes that share nothing but this chart:

| Repository | Owns |
|---|---|
| **OneK8s-hello** (this one) | `apps/hello` — the source, the Dockerfile, the chart, the image build |
| [OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd) | The `AppProject`, the `hello-staging` / `hello-production` ApplicationSets, and the Kargo `Warehouse` and `Stage`s: the release path, and the gate in front of production |
| [OneK8s-fluxcd](https://github.com/olljanat-ai/OneK8s-fluxcd) | The same question with no hub and no promotion engine: one directory per cluster, and the commit that says which build that cluster runs |
| [OneK8s](https://github.com/olljanat-ai/OneK8s) | The clusters, the tenants, the Argo CD hub, Kargo itself, the per-cluster Flux, and the one root `Application` that points Argo CD at its delivery plane |

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
get. `platform_apps = { enabled = false }` attaches spokes without deploying
anything, and the root Application is skipped automatically when the hub's
foundation was applied with `enable_argocd = false`.

The root Application carries Argo CD's cascade finalizer, so `terraform
destroy` on the gitops stack takes the ApplicationSets — and the workloads they
put on the spokes — with it.

> The platform *environment* (`prototype`, `dev`, …) and an application *stage*
> (`staging`, `production`) are different axes. One platform environment holds
> both the Azure staging cluster and the AWS production cluster of this app.

## The same chart, delivered by Flux

Everything above describes the Argo CD plane, which is the one this
application's release path travels. AKS and EKS also run **Flux** — installed
per cluster, with no hub and nothing registered between them — and it deploys
*this same chart, unchanged* for a different tenant:

| Cluster | Argo CD + Kargo — `team-alpha` | Flux — `team-beta` |
|---|---|---|
| AKS | https://azure-hello.onek8s.lol | https://azure-hello2.onek8s.lol |
| EKS | https://aws-hello.onek8s.lol | https://aws-hello2.onek8s.lol |

Nothing in this repository changed to make that work, which is the interesting
part: the chart takes `cloud`, `environment`, `tenant`, `ingress.host` and
`secret.remoteKey` from whoever deploys it, and has no opinion about who that
is. On the Flux side those arrive as `${VARIABLE}` substitutions from a
ConfigMap Terraform writes, where here they are Helm parameters rendered by an
ApplicationSet.

The difference is above the chart, and it is the whole reason both are
installed: there is no `Warehouse` on that plane, so a build reaches a cluster
when somebody edits `clusters/<cloud>/hello2-release.yaml` in OneK8s-fluxcd —
review as the gate, rather than a promotion policy. See
[fluxcd.md](https://github.com/olljanat-ai/OneK8s/blob/main/docs/fluxcd.md).

## The two stages

`argocd/templates/applicationsets.yaml` in OneK8s-argocd ranges over every
application's stages and produces one ApplicationSet per application-stage.
Nothing in that template names `hello` — this application is an entry under
`apps:` in the chart's values, which is the whole of what onboarding one costs. How each stage
finds its cluster follows from the topology rather than from the stage:

- **staging** names its cluster (`in-cluster`) in a one-element `list`
  generator. Argo CD's built-in entry for the cluster it runs on has no Secret
  and therefore no labels to select on, so it cannot come from a cluster
  generator.
- **production** names only its cloud, and a `clusters` generator selects the
  spoke labelled `onek8s.io/cloud: aws` for this environment — the label
  `modules/argocd-spoke` puts on the cluster Secret. No AWS spoke attached in
  this environment, no production Application: the release path follows the
  gitops stack by itself.

Applications address their cluster by `destination.name`, not by server URL.
That was always the stable choice — the EKS endpoint is whatever that service
handed out — and it is now also how the Application is *routed*: a spoke is
reached through an [argocd-agent](https://github.com/argoproj-labs/argocd-agent)
agent that dials the hub, and the principal reads `destination.name` to decide
which agent an Application belongs to. So `hello-production` is created on the
hub, handed to the AWS cluster's agent, and applied by that cluster's own Argo
CD. Nothing on the hub calls the EKS API server.

Per-stage values reach the chart as Helm parameters — `cloud`, `environment`,
`tenant`, `ingress.host` (`<cloud>-hello.onek8s.lol`), `secret.name`,
`secret.remoteKey` and the welcome message. Those describe the *stage* and
change only when the delivery plane does.

What changes per *release* arrives differently, and that difference is the whole
design: `image.tag` and the chart's own revision come from
`stages/hello/<stage>.yaml`, a file Kargo writes and the ApplicationSet reads
back — `chartRevision` as the chart source's `targetRevision`, `image.tag` as a
Helm values file on that source. So the Application is a constant and the
release is a line in Git.

Three values are required and the chart fails to render without them:
`ingress.host`, because an application nobody can open proves nothing;
`secret.remoteKey`, because guessing a key would mean guessing the cloud; and
`image.tag`, because no moving tag is published and a stage nobody has promoted
to has nothing to deploy.

### The gate in front of AWS

Both Applications carry `syncPolicy.automated` (prune + selfHeal). The gate is
not a withheld sync — it is that **no commit says production runs that build
yet**.

[Kargo](https://kargo.io) on the hub watches the image repository and the
chart's directory, and freezes each new build together with the chart it is
deployed with as a piece of immutable **Freight**. `staging` takes new Freight
automatically. `production` takes Freight only from `staging`, and only when
somebody promotes it:

```bash
kargo login https://kargo.onek8s.lol --sso
kargo get freight --project onek8s-hello                    # what staging has run
kargo promote --project onek8s-hello --stage production --freight <name>
kargo get promotions --project onek8s-hello                 # who promoted what, when
```

A promotion clones OneK8s-argocd, writes the tag and the chart revision into
`stages/hello/production.yaml`, commits, pushes, and asks Argo CD to sync. Four
consequences, and they are the reasons for changing this at all:

- **The gate cannot be lifted by editing the delivery plane.** Adding a sync
  policy back changes nothing: the Application is already synced — to the
  previous Freight.
- **It covers the chart too.** A chart change is part of the Freight, so it
  reaches production by promotion rather than the moment it merges.
- **It cannot be jumped.** Production's Freight comes from staging, so a build
  that has never run on AKS cannot be put on EKS even by somebody who is
  allowed to promote.
- **The record is where the change is.**
  `git log stages/hello/production.yaml` in OneK8s-argocd names every build
  production has ever run and who asked for it.

Who may promote is a Kargo `Role` in this application's own Project namespace
(`onek8s-hello`), rendered from `apps.hello.promoters` in the delivery-plane
chart's values — a list of Entra ID group object IDs — and scoped to `promote`
on exactly the stages that wait for a person. One Kargo Project per application
is what keeps that list, and the stage names, from being shared with everything
else the platform runs.

The steps a promotion runs are *not* per application: every Stage delegates to
one cluster-scoped `ClusterPromotionTask`. PR validation in OneK8s-argocd
renders the chart and fails if the promotion policy, the Freight sources or the
Kargo authorization on an Application ever disagree with `apps.<name>.stages` —
for every application, not just this one.

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
resolves it per stage — `apps.hello.parameters` in the delivery-plane chart's
values, rendered with the stage's cloud in scope — which is the right place for
it, because which cloud a cluster is happens to be the platform's business and
never the application's:

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
`ghcr.io/olljanat-ai/onek8s-hello/hello`, tagged `sha-<short>` — one immutable
tag per build and **no moving tag at all**. (A `v*` git tag additionally
publishes the semver tag, for an application that has releases; `hello` does not
and its Warehouse selects by build time.) Pull requests build without pushing.
The build is the whole of what CI does to a release: Kargo turns the new tag
into Freight, promotes it to staging, and production waits for a person.

The missing `latest` is load-bearing. Kargo identifies a release by its tag, so
a tag that can point at a different image tomorrow would make "production runs
`sha-a1b2c3d`" a statement with no content — and, with `imagePullPolicy:
Always`, would let a pod that restarted for its own reasons pull a build nobody
promoted. `image.tag` in the chart therefore has no default either: it is
required, and it is written per stage by a promotion.

The package must be **public** for the clusters
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
# The release path
kargo get stages     --project onek8s-hello       # what each stage runs now
kargo get freight    --project onek8s-hello       # what could be promoted
kargo get promotions --project onek8s-hello       # who promoted what, when

# The same without the CLI: they are ordinary objects on the hub
kubectl -n onek8s-hello get warehouses,stages,freight
kubectl -n onek8s-hello get promotions --sort-by=.metadata.creationTimestamp

# What Git says production runs — the authoritative answer
git -C ../OneK8s-argocd log --oneline -- stages/hello/production.yaml

# The hub's view of the whole thing
kubectl -n argocd get application platform-gitops
kubectl -n argocd get applicationset hello-staging hello-production
kubectl -n argocd get applications -L onek8s.io/stage,onek8s.io/cloud

argocd app list --grpc-web
argocd app get hello-production --grpc-web        # synced, to the promoted build

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

- **Nothing verifies a stage beyond "the pods are healthy".** Kargo can hold
  Freight behind a verification — an Argo Rollouts `AnalysisTemplate`, a smoke
  test as a `Job` — before it becomes promotable, and this platform installs no
  Argo Rollouts, so `production` accepts anything that ran in `staging` without
  falling over. `soakTime` on the production stage is the blunt version of the
  same idea and is available today.
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
- **One tenant per delivery plane.** The release path above is released into
  `team-alpha` on both stages because that tenant exists everywhere in this
  environment, and the AppProject is scoped to that one namespace; a second
  tenant on this plane means a second destination. (The Flux plane runs the
  same chart in `team-beta`, which is a second *tenant* but not a second
  stage — it has no release path at all.)
- **The hub deploys to itself.** `hello-staging` targets `in-cluster`, so the
  cluster running Argo CD also runs a workload. That is fine for a lab and is
  the reason staging appears as a static list element rather than as a spoke —
  but it does mean the delivery plane and staging share a cluster, and
  production does not. It is also why the hub keeps its own
  application-controller under argocd-agent, and why the Applications for a
  spoke carry a label the hub's own do not.
- **A promotion is a push to `main` of the delivery-plane repository.** Kargo
  commits straight to the branch Argo CD syncs, so a promotion is not reviewed
  the way a pull request is — the review is the approval to promote, and Kargo's
  RBAC is what stands in for branch protection. The `git-open-pr` step is the
  alternative if a promotion should be reviewed as a diff, at the cost of a
  second click on every staging deploy.
- **Kargo's Git credential can write to the whole delivery-plane repository.**
  It is one Secret, scoped to one repository, and a promotion only ever touches
  `stages/`, but nothing enforces that: a compromised credential could rewrite
  the ApplicationSets too. A GitHub App restricted to the repository is the
  better long-lived answer to how it is issued, not to what it may reach.
