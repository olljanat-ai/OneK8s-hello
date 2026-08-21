# Scanning the images

Both applications publish to GHCR, and both are scanned by
[Aikido](https://www.aikido.dev/code/container-image-scanning) — twice, in two
different places, for two different reasons.

| Where | When | What it is good for |
|---|---|---|
| **In the build**, before the push | every merge to `main`, and every `v*` tag | The image does not exist yet. A gate here stops a bad build from being published at all, so Kargo never has it to promote. |
| **On the registry**, continuously | whenever Aikido re-scans | The image was clean when it was built and a CVE was published afterwards. Nothing in a pipeline can catch that, because the pipeline finished weeks ago. |

Neither replaces the other. The first is about the build you are making now;
the second is about the builds you already made, which is where most of a
platform's images spend their lives — `production` on AWS runs whatever was
last promoted to it, and that can be months old.

## What the build stores, and what it does not

**Aikido needs nothing from the build.** It unpacks the image's layers itself
and inventories the OS packages and libraries it finds there, so there is no
SBOM to generate, attach or upload, and no attestation for it to read. An image
built by `docker build` with no arguments at all is scannable.

That is why the published image is unchanged by any of this: `provenance:
false` still stands, no SBOM attestation is attached, and what GHCR holds for a
tag is one manifest and nothing else. Kargo identifies a release by its tag, so
the fewer things a tag can mean, the better.

What the build *does* keep is a copy of the inventory for us:

```
sbom/hello.cdx.json        # CycloneDX, from --output-cyclonedx-json
sbom/db-hello.cdx.json
```

uploaded as a workflow artifact (`sbom-<app>-<sha>`). Aikido keeps its own per
container and can re-derive one from the registry at any time; this copy is for
the times you want to answer "was log4j in the build we shipped in March"
without logging in to anything. It is retained for the repository's Actions
artifact retention — 90 days unless that has been changed — which is shorter
than the images live. If a longer record is ever needed, that is the setting to
raise, or the point at which an SBOM attestation on the image starts to be
worth the second manifest.

## Labels

Nothing Aikido reads is a label. Ownership is a **team** in Aikido, and the
link from an image to its code is a link in Aikido's own model. So no label is
required, and none of the ones below are load-bearing for the scan itself —
with one exception, which is load-bearing for something else:

**`org.opencontainers.image.source`.** GitHub reads this label to attach a
package to a repository. That attachment is what the package's *Inherit access
from source repository* setting hangs off, and Aikido's GHCR integration says
plainly that a container without it cannot be read. It is also how a finding
gets back to the Dockerfile that caused it, which is what makes a suggested fix
possible rather than just a CVE number. `docker/metadata-action` derives it
from `github.repository` for free — the reason it is worth knowing about is so
that nothing ever overrides it.

The rest are for humans and for the container inventory:

| Label | Value | Why |
|---|---|---|
| `org.opencontainers.image.title` | `hello` / `db-hello` | metadata-action's default is the *repository's* name. This repository publishes two images, so left alone both arrive called `OneK8s-hello`, told apart only by their path. |
| `org.opencontainers.image.description` | one line per app | Same problem, same fix: the default is the repository description. |
| `org.opencontainers.image.documentation` | `docs/<app>-app.md` | Where the application is actually explained. |
| `org.opencontainers.image.vendor` | `OneK8s` | Whose platform this is. |
| `org.opencontainers.image.revision`, `.created`, `.version`, `.url`, `.licenses` | derived | metadata-action, unchanged. `revision` is the commit, which is what makes a scanned image traceable to a diff. |

There is deliberately **no ownership label on the image**. The tenant a release
belongs to (`onek8s.io/tenant`) is a property of the deployment, not of the
artefact: the same `hello` image is the same bytes in every tenant's namespace
on both clouds. The chart is where that label belongs and it is already there,
in `apps/*/chart/templates/_helpers.tpl`. Putting a tenant name on the image
would be asserting something about it that is not true.

## Setting it up

### 1. The registry, so Aikido watches GHCR

One-time, in the Aikido console — nothing in this repository changes.

1. Create a **classic** personal access token with the `read:packages` scope
   (`github.com/settings/tokens`). A fine-grained token does not carry that
   scope. It reads packages and nothing else.
2. Add the registry at
   `app.aikido.dev/settings/container-image-registry/add/github` with the
   GitHub username and the `olljanat-ai` organisation, and select the
   `onek8s-hello/hello` and `onek8s-hello/db-hello` packages.
3. On each package's GHCR settings page, make sure **Inherit access from source
   repository** is enabled. Without it Aikido cannot read the package, whatever
   the token says.
4. Link both containers to this code repository, and to the team that owns it.
   Aikido usually suggests the link itself; accepting it is what turns a finding
   into one with a Dockerfile behind it.

The packages are public, which the platform depends on — no cluster on any
cloud has a pull secret — but a token is still needed: listing an
organisation's packages is an authenticated API call even when pulling them is
not.

### 2. The build, so images are scanned before they are published

One secret, and two optional variables, under **Settings → Secrets and
variables → Actions**:

| Name | Kind | Effect |
|---|---|---|
| `AIKIDO_API_KEY` | secret | The CI token from Aikido's settings. **Unset, the scan step is skipped and the build behaves exactly as it did before** — a security integration that has to exist before anyone can build is one nobody finishes setting up. |
| `AIKIDO_TEAM` | variable | The Aikido team the containers belong to. This is the ownership wiring, and a container with no team is a finding with nobody to send it to. |
| `AIKIDO_FAIL_ON` | variable | `low`, `medium`, `high` or `critical`. Unset means report only. |

`AIKIDO_FAIL_ON` is the one with teeth, and worth thinking about before setting
it. The scan runs between the build and the push, so a failure means the image
is never published — which is the point, and also means a critical CVE in the
.NET base image stops `hello` from releasing until the base image is rebuilt.
That is the correct trade for a real application. For these two, which exist to
demonstrate the platform, leaving it unset and letting the registry scan report
is the more honest default; turn it on when you want to demonstrate the gate.

Pull requests build the image but neither scan nor push it. That is on purpose:
Aikido tracks one branch per repository, and reporting feature-branch scans
into it mixes results that have nothing to do with what is deployed. Aikido has
a PR gating mode (`--gating-mode pr`, with base and head commits) for doing
this properly, which is the thing to add if PR-time container findings are ever
wanted.

## Reading the result

Each build's step summary says whether it was scanned, and gating or not. The
findings themselves are in Aikido, against the container — and, once the
containers are linked to this repository, against the repository too, next to
the Dockerfile that would fix them.
