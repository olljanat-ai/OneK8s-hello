#!/usr/bin/env bash
# Give every Azure tenant a user in the Azure SQL database, and apply the
# schema — the one act of onboarding that cannot be a Terraform resource.
#
#   ONEK8S_ROOT=../OneK8s ./apps/db-hello/bootstrap.sh [environment]
#
# Run it as the SQL server's Entra administrator, which by default is whoever
# applied foundations/azure (`az login` as that principal is enough). The
# "Azure SQL access" job of the OneK8s repository's deploy-tenants workflow runs
# this same script as the deploy service principal, so there is one definition
# of the bootstrap and not a CI-shaped one and a by-hand one that drift apart.
#
# The two repositories meet here, and ONEK8S_ROOT is where: this script lives
# with the application, and the state it reads belongs to the platform. It
# defaults to a checkout of OneK8s sitting next to this one, which is what both
# a laptop and the CI job arrange.
#
# Everything is resolved from state rather than taken as configuration: the
# server and database from foundations/azure, every Azure tenant's identity
# from the tenants stack. Nothing is written down, so a rebuilt foundation (the
# server name carries a random suffix) needs no edit here.
#
#   1. resolve the database, and the tenants that should reach it
#   2. open a firewall rule for this machine's address, and close it again
#      whatever happens — the server carries no standing exception for either
#      an operator or CI
#   3. `db-hello bootstrap` per tenant: the migrations, then CREATE USER for
#      that tenant's managed identity with db_datareader + db_datawriter
#
# It is idempotent, so running it again is how a later schema change and a
# newly onboarded tenant both reach the database.
set -euo pipefail

environment="${1:-${TF_ENV:-prototype}}"

# This repository, and the platform repository whose Terraform state answers
# every question below.
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
root="${ONEK8S_ROOT:-$here/../OneK8s}"

if [ ! -d "$root/foundations/azure" ] || [ ! -d "$root/tenants" ]; then
  echo "error: no OneK8s checkout at ${root} (set ONEK8S_ROOT to one)" >&2
  exit 1
fi
root="$(cd -- "$root" && pwd)"

for tool in terraform az curl jq; do
  command -v "$tool" >/dev/null || { echo "error: $tool is required but not on PATH" >&2; exit 1; }
done

# Published output in CI, `dotnet run` on a laptop. Same code either way — and
# the same interceptor, so the connection is authenticated by whatever Azure
# login the caller already has and carries no password in either case.
command -v dotnet >/dev/null || { echo "error: dotnet is required but not on PATH" >&2; exit 1; }

if [ -n "${DB_HELLO_DLL:-}" ]; then
  bootstrap=(dotnet "$DB_HELLO_DLL" bootstrap)
else
  bootstrap=(dotnet run --project "$here/apps/db-hello/src" -c Release -- bootstrap)
fi

tf() {
  local dir="$1"; shift
  terraform -chdir="$root/$dir" "$@"
}

# -reconfigure rather than a bare init, because a working copy left pointing at
# another environment's state would answer every question below with the wrong
# environment's answers — a plausible way to grant a tenant access to the wrong
# database, and a silent one.
tf_ready() {
  tf "$1" init -input=false -reconfigure -backend-config="backend/${environment}.hcl" >/dev/null
}

tf_out() {
  tf "$1" output -raw "$2" 2>/dev/null || true
}

# --- 1. the database, and who should reach it --------------------------------
tf_ready foundations/azure

server="$(tf_out foundations/azure sql_server_fqdn)"
database="$(tf_out foundations/azure sql_database_name)"

if [ -z "$server" ] || [ "$server" = "null" ] || [ -z "$database" ] || [ "$database" = "null" ]; then
  echo "No SQL server in the ${environment} Azure foundation (enable_sql = false); nothing to do."
  exit 0
fi

group="$(tf_out foundations/azure resource_group_name)"
name="${server%%.*}"

echo "database   ${database} on ${server}"

tf_ready tenants

tenants="$(tf "tenants" output -json tenants |
  jq -r '.[] | select(.cloud == "azure") | [.namespace, .identity] | @tsv')"

if [ -z "$tenants" ]; then
  echo "No Azure tenants in the ${environment} tenants stack; nothing to do."
  exit 0
fi

echo "tenants    $(wc -l <<< "$tenants")"

# The database user is named after the managed identity — what `CREATE USER …
# FROM EXTERNAL PROVIDER` would have called it, so the application's page names
# something recognisable in the portal. The lookup is an ARM one; the directory
# is never read.
identities="$(az identity list --resource-group "$group" \
  --query "[].{name:name,clientId:clientId}" -o json)"

# --- 2. a firewall rule for this run, and only this run ----------------------
address="$(curl -fsS https://api.ipify.org)"
[ -n "$address" ] || { echo "error: could not determine this machine's public address" >&2; exit 1; }

rule="db-hello-bootstrap-${GITHUB_RUN_ID:-$$}"

close_firewall() {
  az sql server firewall-rule delete \
    --resource-group "$group" --server "$name" --name "$rule" --output none 2>/dev/null || true
  echo "firewall   removed ${rule}"
}
trap close_firewall EXIT

az sql server firewall-rule create \
  --resource-group "$group" --server "$name" \
  --name "$rule" --start-ip-address "$address" --end-ip-address "$address" \
  --output none
echo "firewall   opened ${rule} for ${address}"

# --- 3. the migrations, and a user per tenant --------------------------------
export SQL_SERVER="$server"
export SQL_DATABASE="$database"

while IFS=$'\t' read -r namespace client_id; do
  user="$(jq -r --arg c "$client_id" '.[] | select(.clientId == $c) | .name' <<< "$identities")"
  if [ -z "$user" ]; then
    echo "error: no managed identity with client ID ${client_id} in ${group} (tenant ${namespace})" >&2
    exit 1
  fi
  echo "--- ${namespace}"
  "${bootstrap[@]}" --user "$user" --client-id "$client_id"
done <<< "$tenants"
