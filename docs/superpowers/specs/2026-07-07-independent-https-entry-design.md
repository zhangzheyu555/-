# Independent HTTPS Entry Design

## Goal

Add an HTTPS browser entry for the cloud-deployed Store Profit System without interrupting the existing `/opt/farm-mall` services that currently own ports `80` and `8080`.

## Approach

The Spring Boot application continues to run as `store-profit-system.service` on `127.0.0.1:18080`. Nginx gets a separate server block listening on `18443` with a local self-signed certificate, proxying traffic to `http://127.0.0.1:18080`.

This is intentionally isolated: no changes to the existing `/etc/nginx/sites-available/farm-mall` routing and no takeover of standard `443` until a real domain is available.

## User-Facing Result

- Current HTTP entry remains: `http://175.178.89.183:18080/index.html`
- New HTTPS entry becomes: `https://175.178.89.183:18443/index.html`
- Browser will show a certificate warning because the endpoint uses the server IP and a self-signed certificate.

## Safety Constraints

- Do not stop or modify `farm-mall.service` or `farm-mall-ai.service`.
- Do not change the existing nginx port `80` site.
- Add only a new nginx server block and a firewall rule for `18443/TCP`.
- Verify the old `http://175.178.89.183/` endpoint still returns HTTP 200 after the change.

## Verification

- `nginx -t` passes before reload.
- `systemctl reload nginx` succeeds.
- Server-local HTTPS health check returns `UP`.
- Public HTTPS health check reaches the new endpoint.
- Browser smoke test logs in and renders the boss dashboard.
- Existing port `80` endpoint remains healthy.
