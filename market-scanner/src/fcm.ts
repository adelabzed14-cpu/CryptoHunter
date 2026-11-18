import type { Env } from "./index";

type FcmPayload = { title: string; body: string; data?: Record<string, string> };

export async function sendFcmNotification(deviceToken: string, payload: FcmPayload, env: Env): Promise<boolean> {
  const accessToken = await getGoogleAccessToken(env);
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      message: {
        token: deviceToken,
        notification: { title: payload.title, body: payload.body },
        data: payload.data ?? {},
        android: { priority: "HIGH", notification: { channel_id: "market_alerts" } }
      }
    })
  });
  if (!response.ok) {
    const errorText = await response.text();
    console.error("FCM send failed", response.status, errorText.slice(0, 300));
    return false;
  }
  return true;
}

async function getGoogleAccessToken(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64Url(JSON.stringify({
    iss: env.FCM_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }));
  const unsigned = `${header}.${claims}`;
  const keyPem = env.FCM_PRIVATE_KEY.replace(/\\n/g, "\n");
  const keyBytes = pemToBytes(keyPem);
  const privateKey = await crypto.subtle.importKey(
    "pkcs8", keyBytes, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, new TextEncoder().encode(unsigned));
  const jwt = `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt })
  });
  if (!tokenResponse.ok) throw new Error(`Google OAuth token request failed: ${tokenResponse.status}`);
  const tokenData = await tokenResponse.json() as { access_token?: string };
  if (!tokenData.access_token) throw new Error("Google OAuth response missing access_token");
  return tokenData.access_token;
}

function base64Url(text: string): string {
  return base64UrlBytes(new TextEncoder().encode(text));
}
function base64UrlBytes(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\\+/g, "-").replace(/\\//g, "_").replace(/=+$/g, "");
}
function pemToBytes(pem: string): ArrayBuffer {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\\s/g, "");
  const binary = atob(base64);
  const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
  return bytes.buffer;
}
