# AWS Deployment — Single EC2 Instance

The CEMS backend runs on one EC2 instance: the Spring Boot API and PostgreSQL as Docker
containers, nginx on the host terminating TLS, uploads on the EBS root volume. The frontend is
deployed separately on Vercel and is not covered here beyond the settings it needs from this side.

There is deliberately no load balancer (an ALB would cost more than the instance), no RDS, and no
S3. Because every database setting is supplied through environment variables, moving to RDS later
is a change to `SPRING_DATASOURCE_URL` and nothing else.

## Contents

- [Architecture](#architecture)
- [What you need first](#what-you-need-first)
- [1. Launch the instance](#1-launch-the-instance)
- [2. Point DNS at it](#2-point-dns-at-it)
- [3. Prepare the instance](#3-prepare-the-instance)
- [4. Configure and start the stack](#4-configure-and-start-the-stack)
- [5. Put nginx and TLS in front](#5-put-nginx-and-tls-in-front)
- [6. Verify](#6-verify)
- [7. Backups](#7-backups)
- [Redeploying](#redeploying)
- [Connecting the Vercel frontend](#connecting-the-vercel-frontend)
- [Troubleshooting](#troubleshooting)

## Architecture

```
Vercel frontend (https://your-app.vercel.app)
        │  HTTPS
        ▼
   api.yourdomain.com  ─►  EC2 t4g.small (Ubuntu LTS, arm64)
                            ├─ nginx :443        TLS, proxy, 12 MB body cap
                            ├─ api container     127.0.0.1:8080 only
                            ├─ db container      compose network only, not published
                            └─ /var/lib/cems/storage  (EBS, uploads + reports)
```

Only ports 22, 80, and 443 are reachable from outside. Neither container publishes a public port.

### Approximate monthly cost (us-east-1)

| Item | Cost |
|---|---|
| t4g.small on-demand | ~$12.26 |
| 20 GB gp3 EBS | ~$1.60 |
| Public IPv4 address | ~$3.60 |
| Snapshots (~10 GB) | ~$0.50 |
| **Total** | **~$18/mo** |

A 1-year no-upfront Compute Savings Plan takes the instance down roughly 30%, to about $13/mo
overall. Confirm current figures against the AWS Pricing Calculator, and check what your account's
free tier covers — free-tier terms differ for newer accounts.

## What you need first

- An AWS account.
- A domain you control, so the API can have a hostname and a TLS certificate. **HTTPS is not
  optional**: Vercel serves the frontend over HTTPS and browsers block mixed content, so an
  `http://` API — including a bare EC2 IP — cannot be called from the deployed frontend. A
  subdomain of a domain you already own is fine and costs nothing extra. Managing DNS at your
  existing registrar avoids the $0.50/mo Route 53 hosted-zone charge.
- An SSH keypair for the instance.

## 1. Launch the instance

EC2 → Launch instance:

- **AMI**: Ubuntu Server LTS (24.04 or later), **architecture arm64**. The Quick Start panel
  defaults the Architecture dropdown to 64-bit (x86); switch it to **64-bit (Arm)** or the image
  will not boot on a `t4g`. Confirm afterwards that the instance-type list offers `t4g.*`.
- **Instance type**: `t4g.small` (2 vCPU, 2 GiB).
- **Key pair**: your SSH key.
- **Storage**: 20 GiB `gp3`.
- **Security group**:

  | Port | Source | Why |
  |---|---|---|
  | 22 | your IP only | SSH. Do not open to `0.0.0.0/0`. |
  | 80 | `0.0.0.0/0` | ACME HTTP-01 challenge and the HTTPS redirect. |
  | 443 | `0.0.0.0/0` | The API itself. |

  Postgres (5432) is deliberately absent — the database is only reachable from the API container.

Then allocate an **Elastic IP** and associate it with the instance, so the address survives a stop
or start. An Elastic IP attached to a running instance carries no charge beyond the standard public
IPv4 fee already in the table above.

## 2. Point DNS at it

Create an `A` record for `api.yourdomain.com` pointing at the Elastic IP. Verify before continuing
— certbot cannot issue a certificate until this resolves publicly:

```bash
dig +short api.yourdomain.com
```

## 3. Prepare the instance

SSH in as `ubuntu`, then:

```bash
sudo apt-get update && sudo apt-get upgrade -y
```

**Swap.** A 2 GiB instance runs the JVM, Postgres, and — during a deploy — a Maven build. Without
swap the build gets OOM-killed:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**Docker**, from Docker's own apt repository:

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker ubuntu
```

Log out and back in for the group change to apply, then confirm with `docker ps`.

> **If `apt-get update` fails with "Release file not found"**, Docker has not published a suite for
> this Ubuntu release's codename yet — likely on a recently released LTS. Check first:
>
> ```bash
> . /etc/os-release && curl -fsI \
>   "https://download.docker.com/linux/ubuntu/dists/$VERSION_CODENAME/Release" \
>   > /dev/null && echo "suite exists" || echo "suite missing"
> ```
>
> If it is missing, take Docker from Ubuntu's own repositories instead. Slightly older, fully
> supported, and it provides the same `docker compose` subcommand this deployment relies on:
>
> ```bash
> sudo rm -f /etc/apt/sources.list.d/docker.list
> sudo apt-get update
> sudo apt-get install -y docker.io docker-compose-v2 docker-buildx
> sudo systemctl enable --now docker
> sudo usermod -aG docker ubuntu
> ```
>
> Verify with `docker compose version` — it must be v2.x, since `docker-compose.yml` uses
> `deploy.resources.limits` and `docker compose cp`, neither of which works on the v1 script.

**nginx and certbot.**

```bash
sudo apt-get install -y nginx certbot python3-certbot-nginx
```

**Storage directory.** Uploads are bind-mounted from the host. The owner must match the container's
unprivileged user (uid/gid 10001, fixed in the Dockerfile) or the API cannot write:

```bash
sudo mkdir -p /var/lib/cems/storage
sudo chown -R 10001:10001 /var/lib/cems
```

**Code.**

```bash
sudo apt-get install -y git
sudo mkdir -p /opt/cems
sudo chown ubuntu:ubuntu /opt/cems
git clone https://github.com/cedatechsolutions/epms-api.git /opt/cems
```

This repository's root is the Spring Boot project itself, so the checkout puts `pom.xml`,
`Dockerfile`, and `deploy/` directly under `/opt/cems`. The paths in `cems.service` and `backup.sh`
assume exactly that; adjust them if you clone elsewhere.

If the repository is private, HTTPS will prompt for credentials that GitHub no longer accepts.
Use a read-only **deploy key** instead — it scopes access to this one repository, which a personal
access token does not:

```bash
ssh-keygen -t ed25519 -C "cems-ec2-deploy" -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub
```

Add that public key under the repository's **Settings → Deploy keys** on GitHub (leave "Allow write
access" unchecked), then clone over SSH instead:

```bash
git clone git@github.com:cedatechsolutions/epms-api.git /opt/cems
```

## 4. Configure and start the stack

```bash
cd /opt/cems/deploy
cp .env.example .env
chmod 600 .env
nano .env
```

Fill in every value. Generate the secrets rather than inventing them:

```bash
openssl rand -base64 32              # POSTGRES_PASSWORD
openssl rand -base64 64 | tr -d '\n' # JWT_SECRET
```

Set `APP_CORS_ALLOWED_ORIGINS` and `APP_FRONTEND_RESET_PASSWORD_URL` to your Vercel origin, and for
this first start only, uncomment `APP_BOOTSTRAP_ADMIN_EMAIL` and `APP_BOOTSTRAP_ADMIN_PASSWORD`
(minimum 12 characters). Without them the database comes up with no account you can log into: the
development seeder is disabled under the `prod` profile and no migration inserts users.

Install the systemd unit and start:

```bash
sudo cp cems.service /etc/systemd/system/cems.service
sudo systemctl daemon-reload
sudo systemctl enable --now cems
```

The first start builds the API image from source, which takes several minutes on this instance
size. Follow it with:

```bash
docker compose -f /opt/cems/deploy/docker-compose.yml logs -f
```

Flyway applies migrations `V1`–`V9` automatically before the app finishes starting. Look for the
bootstrap admin line in the log confirming the account was created.

Once the API is healthy, **remove the two bootstrap variables from `.env`** and restart so the
credentials are no longer sitting on disk:

```bash
nano .env          # re-comment APP_BOOTSTRAP_ADMIN_EMAIL / APP_BOOTSTRAP_ADMIN_PASSWORD
sudo systemctl reload cems
```

Leaving them set will not reset the existing admin's password — the initializer never touches an
account that already exists — but there is no reason to keep them.

## 5. Put nginx and TLS in front

Order matters here. The site config references a certificate, and nginx refuses to start a
`listen ... ssl` server whose certificate file does not exist — installing the site first would
take nginx down. So issue the certificate against the stock default site, which already serves
`/var/www/html` on port 80, and only then swap in the real config.

**Get the certificate first:**

```bash
sudo systemctl enable --now nginx
sudo certbot certonly --webroot -w /var/www/html -d api.yourdomain.com \
  --deploy-hook "systemctl reload nginx"
```

The deploy hook makes nginx pick up each renewal automatically. Certbot installs its own systemd
timer for renewals; confirm the whole path works with `sudo certbot renew --dry-run`.

**Then install the site:**

```bash
cd /opt/cems/deploy
sudo cp nginx/cems-api.conf /etc/nginx/sites-available/cems-api
sudo sed -i 's/api\.example\.com/api.yourdomain.com/g' /etc/nginx/sites-available/cems-api
sudo ln -sf /etc/nginx/sites-available/cems-api /etc/nginx/sites-enabled/cems-api
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

The `sed` covers the certificate paths as well as `server_name`, so all three pick up your real
hostname. The port-80 block keeps serving `/.well-known/acme-challenge/` from `/var/www/html`, so
renewals continue to work after the default site is gone.

Two things in that config matter beyond the proxying:

- `client_max_body_size 12m` — nginx's 1 MB default would reject document uploads before they
  reach the API. It sits just above the application's 10 MB cap so oversized files produce a
  proper JSON error from the API rather than a bare nginx 413.
- `proxy_set_header X-Forwarded-For $remote_addr` — an overwrite, not the usual
  `$proxy_add_x_forwarded_for` append. The API reads the first hop of that header to rate-limit
  login attempts and public survey submissions; appending would let a caller inject a forged value
  into first position and evade the limit.

## 6. Verify

```bash
# From the instance — should report {"status":"UP"}
curl -s localhost:8080/actuator/health

# From your machine — TLS terminates and the request reaches the application.
# A 404 with a JSON error body is the expected success signal here: the token does not
# exist, but only the app could have produced that response.
curl -s https://api.yourdomain.com/api/public/surveys/does-not-exist

# CORS preflight should echo your Vercel origin back
curl -si -X OPTIONS https://api.yourdomain.com/api/auth/login \
  -H 'Origin: https://your-app.vercel.app' \
  -H 'Access-Control-Request-Method: POST' | grep -i access-control-allow-origin
```

Then log in as the bootstrap admin through the frontend. The account is flagged
`mustChangePassword`, so it should route straight to the change-password screen — change it
immediately, and create real accounts from there.

`/actuator/**` is restricted to localhost in the nginx config, so a health check from outside is
expected to fail. That is intentional.

## 7. Backups

Two independent layers, matching the policy in [DATABASE_OPERATIONS.md](DATABASE_OPERATIONS.md).

**EBS snapshots** cover the whole volume, including `/var/lib/cems/storage`. Set up an AWS Backup
plan or a Data Lifecycle Manager policy: daily snapshot, 7-day retention.

**Logical dumps** cover what a snapshot handles badly — a bad migration or an accidental delete,
where you want the data back without rolling the entire machine back:

```bash
sudo cp /opt/cems/deploy/backup.sh /usr/local/bin/cems-backup
sudo chmod +x /usr/local/bin/cems-backup
sudo crontab -e
# 15 3 * * * /usr/local/bin/cems-backup >> /var/log/cems-backup.log 2>&1
```

The script keeps 7 daily and 4 weekly dumps, and verifies each archive is readable before letting
it rotate an older one out.

A backup is not a backup until it has been restored. Run a restore test monthly, following the
restore procedure in `DATABASE_OPERATIONS.md`.

## Redeploying

```bash
cd /opt/cems/deploy && ./deploy.sh
```

This pulls, rebuilds the API image, restarts only the `api` service, waits for health, and prunes
orphaned image layers. The database container and its volume are untouched. New Flyway migrations
in the pulled code apply automatically at startup.

If a deploy fails health, the script prints the last 50 log lines and exits non-zero. Roll back
with `git checkout <previous-sha>` followed by `./deploy.sh` again.

Building on the instance keeps this setup free of a container registry, and the image is natively
arm64 with no cross-build. If build time on a small instance becomes annoying, the upgrade path is
to build in GitHub Actions and push to GHCR, then have `deploy.sh` pull the image instead of
building it — replace the `build:` block in `docker-compose.yml` with the registry image.

## Connecting the Vercel frontend

Full detail belongs with the frontend, but from this side:

1. In Vercel → Project → Settings → Environment Variables, set
   `VITE_API_BASE_URL=https://api.yourdomain.com/api`. Vite inlines this **at build time**, so
   changing it requires a redeploy — it cannot be swapped at runtime.
2. Set `APP_CORS_ALLOWED_ORIGINS` in this stack's `.env` to the exact production origin.
3. **Vercel preview deployments will fail CORS.** Each preview gets a fresh hostname
   (`cems-client-<hash>.vercel.app`), and `SecurityConfig` matches allowed origins by exact string.
   If previews need to reach this API, `corsConfigurationSource` has to switch from
   `setAllowedOrigins` to `setAllowedOriginPatterns` so a wildcard pattern can match. That change
   has not been made.

## Troubleshooting

**API container restarts repeatedly.** `docker compose logs api`. Usually a missing `.env` value —
compose fails loudly for the required ones — or Flyway refusing to run against a drifted schema.

**`Failed to store uploaded file` / permission denied on upload.** The host directory owner does
not match the container user: `sudo chown -R 10001:10001 /var/lib/cems`.

**Uploads over 1 MB fail with a 413.** nginx config was not installed or not reloaded; check
`client_max_body_size` is present in the active site.

**Build gets killed partway through.** Out of memory — confirm swap is active with `swapon --show`.

**CORS errors in the browser despite a correct-looking origin.** The value must match scheme, host,
and port exactly, with no trailing slash. `https://your-app.vercel.app/` will not match
`https://your-app.vercel.app`.

**Login returns 401 for the bootstrap admin.** If the account already existed, the initializer left
it alone by design and the password in `.env` was never applied. Reset it against the database
directly, or delete the row and restart with the variables set.

**Everything is healthy locally but the domain times out.** Security group is missing 80/443, or
DNS still points elsewhere.
