# hello

A minimal .NET 10 web application whose only job is to prove, from a browser,
that a cluster is wired up: it renders a welcome message and the value of a
**test secret** read out of that cloud's own secret backend.

```
apps/hello/
├── src/            # Program.cs + Hello.csproj — one file, one page
├── Dockerfile      # SDK build → chiseled ASP.NET runtime
└── chart/          # what Argo CD renders on every stage's cluster
```

One image runs unchanged on AKS (staging) and EKS (production) — and would on
GKE or OKE — because everything it shows comes from the environment:

| Variable | Set by | Shown as |
|---|---|---|
| `WELCOME_MESSAGE` | the ApplicationSet, per stage | the heading |
| `CLOUD` / `ENVIRONMENT` | the ApplicationSet | Cloud / Environment |
| `POD_NAME` / `POD_NAMESPACE` | the downward API | Pod / Namespace |
| `TEST_SECRET_NAME` | `secret.name` in values | the secret's label |
| `TEST_SECRET_FILE` | the chart, pointing at the mounted secret | the secret's value |

The secret is read **per request from a mounted file**, not captured from an
environment variable at start-up. The kubelet refreshes a Secret volume in
place, so a value that External Secrets syncs (or rotates) after the pod
started shows up on a reload — and the volume is `optional`, so the pod starts
and says "not available" rather than hanging in `ContainerCreating` when the
secret does not exist yet. `TEST_SECRET` is honoured as a fallback for running
it outside Kubernetes.

The chart is cloud-agnostic in the literal sense: it contains no conditional on
`cloud`, which reaches it only as a string to print and to label objects with.
It renders a Deployment, a Service, an Ingress and an ExternalSecret — all four,
always — and requires two values it refuses to guess:

| Value | Why it is required |
|---|---|
| `ingress.host` | an application nobody can open proves nothing |
| `secret.remoteKey` | the key differs per backend, and guessing it means guessing the cloud |

Whoever deploys knows both. The "hello" ApplicationSets in the
[OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd) repository set
them per stage.

It runs as **UID 1654**, and nothing about it is root: the chiseled base image
defaults to that user, the `Dockerfile` selects it explicitly, and the pod's
`securityContext` pins it alongside a read-only root filesystem, every
capability dropped, `RuntimeDefault` seccomp and no ServiceAccount token. That
is not optional politeness — tenant namespaces enforce the `restricted` Pod
Security Standard, so a pod that had not said all of this would never be
admitted ([tenant module](https://github.com/olljanat-ai/OneK8s/blob/main/modules/tenant-namespace/README.md#non-root-workloads)).

`GET /healthz` is the liveness and readiness probe.

`GET /favicon.svg` is the page's icon: the Kubernetes heptagon with a "1" cut
out of it, held as a string in `Program.cs` rather than a file, because the app
serves no static content and the image has no `wwwroot` to copy in. The
document links it, so no browser falls back to asking for `/favicon.ico`.

## Where it is deployed, and how

Argo CD, from the [OneK8s-argocd](https://github.com/olljanat-ai/OneK8s-argocd)
repository: **staging on Azure**, synced automatically, and **production on
AWS**, which waits for a human. The full story — the two stages, the per-cloud
secret naming, the DNS records, the image tag — is in
[docs/hello-app.md](../../docs/hello-app.md).

## Running it locally

```bash
cd apps/hello/src
TEST_SECRET="a local value" WELCOME_MESSAGE="Hello from my laptop" dotnet run
```

Or as the container image the platform actually runs:

```bash
docker build -t hello apps/hello
docker run --rm -p 8080:8080 -e TEST_SECRET="a local value" hello
```
