# Cloudflare deployment setup (manual account steps)

This scanner has **not** been deployed yet. Complete these steps from your Cloudflare account; don't paste private keys or credentials into chat or GitHub.

## 1. Create the KV namespace
In Cloudflare Dashboard, go to **Workers & Pages → KV → Create namespace**. Name it `CryptoHunterAlertCooldown`. Copy the namespace ID.

Edit `wrangler.toml` and replace `REPLACE_WITH_KV_NAMESPACE_ID` with the actual ID.

## 2. Install and authenticate Wrangler
From a computer with Node.js installed:
```sh
cd market-scanner
npm install
npx wrangler login
```

## 3. Deploy
```sh
npm run deploy
```
Accept the prompt to create the Worker if asked. Confirm the Worker appears in Cloudflare Dashboard.

## 4. Configure Firebase credentials (only when enabling push)
Create a Firebase service account key only if you are ready to connect FCM. Store the private key and service-account email as Cloudflare Worker secrets, not in files:
```sh
npx wrangler secret put FIREBASE_PROJECT_ID
npx wrangler secret put FCM_CLIENT_EMAIL
npx wrangler secret put FCM_PRIVATE_KEY
```
Paste values into the terminal prompts locally. Never commit the service-account JSON/key.

## 5. Current limitation
The scanner currently logs qualifying signals. FCM sender helper exists, but push delivery is not wired to registered phone tokens yet. Device-token registration and secure authenticated endpoint must be completed before real push notifications can be sent.

## Security
- Do not share service-account private keys.
- Do not place tokens or keys in Android source, repository files, screenshots, or chat.
- Rotate any key that is accidentally exposed.
