# Cloudflare Workers deployment setup

The Worker source is in `market-scanner/`. The KV namespace is already configured in `wrangler.toml`:

- Worker name: `crypto-hunter-market-scanner`
- Production branch: `main`
- Root directory: `market-scanner`
- KV binding: `ALERT_COOLDOWN`
- KV namespace ID: configured in `wrangler.toml`
- Cron: every 5 minutes (`*/5 * * * *`)

## Deploy through Cloudflare Workers Builds

In Cloudflare Dashboard:

1. Open **Workers & Pages** and create/open the Worker connected to the GitHub repository `adelabzed14-cpu/CryptoHunter`.
2. Select the production branch `main`.
3. Set the project root/build working directory to `market-scanner`.
4. Set the build command to `npm run typecheck`.
5. Set the deploy command to `npm run deploy` (equivalent to `wrangler deploy`).
6. Save and trigger a deployment. Confirm the deployment is marked **Success** and the Worker is named `crypto-hunter-market-scanner`.

If the dashboard presents a single command field rather than separate build and deploy commands, do not guess: use the Workers Builds configuration fields shown by the account UI and ensure the deployment command runs `npm run deploy` after dependencies are installed and type checking passes.

## Verify after deployment

- Open the Worker and confirm the latest deployment succeeded.
- Confirm the Cron Trigger `*/5 * * * *` is listed.
- Confirm the KV namespace binding is named `ALERT_COOLDOWN` and points to the configured namespace.
- Open the Worker URL. A healthy deployed Worker responds with: `Crypto Hunter market scanner is running.`

## Push notifications are not enabled yet

The scanner currently checks Binance Spot USDT markets and logs qualifying signal candidates. It does **not** yet send push notifications. The FCM helper exists, but device-token registration, secure token storage, and the scan-to-FCM send flow still need implementation and testing.

Do not add Firebase service-account credentials until that FCM work is ready. Never commit service-account JSON or private keys, and never paste them into chat. When FCM is implemented, store credentials as Cloudflare Worker secrets only.

## Local deployment alternative

From a computer with Node.js installed:

```sh
cd market-scanner
npm install
npx wrangler login
npm run typecheck
npm run deploy
```
