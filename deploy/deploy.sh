#!/usr/bin/env bash
#
# Pull the latest code, rebuild the API image, and restart the stack — run on the EC2 instance.
#
#   cd /opt/cems/api/api/deploy && ./deploy.sh
#
# The database container is not rebuilt or restarted; only the API is replaced, so a deploy
# does not interrupt or risk the data volume.

set -euo pipefail

COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$COMPOSE_DIR"

if [ ! -f .env ]; then
    echo "ERROR: .env not found in $COMPOSE_DIR. Copy .env.example and fill it in first." >&2
    exit 1
fi

echo "==> Fetching latest code"
git -C "$COMPOSE_DIR" pull --ff-only

echo "==> Building API image"
# Built natively on the instance, so the image is arm64 without any cross-build setup.
# A 2 GiB box needs swap for this; see AWS_DEPLOYMENT.md.
docker compose build api

echo "==> Restarting API"
# Only the api service. Flyway migrations in the new image run automatically at startup.
docker compose up -d --no-deps api

echo "==> Waiting for health"
for attempt in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8080/actuator/health > /dev/null 2>&1; then
        echo "API is healthy."
        # Remove the image layers the rebuild orphaned; a small root volume fills up fast otherwise.
        docker image prune -f > /dev/null
        exit 0
    fi
    sleep 5
done

echo "ERROR: API did not become healthy within 150s. Recent logs:" >&2
docker compose logs --tail=50 api >&2
exit 1
