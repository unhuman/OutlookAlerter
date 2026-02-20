# Azure AD App Registration Guide

OutlookAlerter uses Microsoft's MSAL (Microsoft Authentication Library) for OAuth2 browser-based authentication with Microsoft Graph API. This eliminates the need to manually copy-paste access tokens.

## Built-in App Registration

OutlookAlerter ships with a pre-configured **multi-tenant** Azure AD application:

| Property | Value |
|---|---|
| **Application (client) ID** | `ce88d638-d3c7-42fd-b46c-6b55e9decf12` |
| **Supported account types** | Accounts in any organizational directory (multi-tenant) and personal Microsoft accounts |
| **Redirect URI** | `http://localhost:8888/redirect` (Public client/native) |
| **Public client flows** | Enabled (no client secret required) |

### API Permissions (Delegated)

| Permission | Purpose |
|---|---|
| `Calendars.Read` | Read calendar events |
| `User.Read` | Validate token / get user profile |
| `offline_access` | Obtain refresh tokens for silent re-authentication |

**Note:** If your organization requires admin consent for third-party apps, an IT administrator will need to grant consent for this app once. See the [Admin Consent](#admin-consent) section below.

## How It Works

1. When authentication is needed, OutlookAlerter opens your system browser to Microsoft's login page
2. You sign in with your Microsoft 365 credentials (including MFA if required)
3. The browser redirects to `http://localhost:8888/redirect` — MSAL captures the authorization code automatically
4. MSAL exchanges the code for access + refresh tokens
5. Tokens are cached locally in `~/.outlookalerter/msal_token_cache.json` (owner-only permissions)
6. On subsequent runs, MSAL silently refreshes the token using the cached refresh token — no browser needed

## Registering Your Own App (Optional)

If you prefer to use your own Azure AD app registration instead of the built-in Microsoft Office app ID, follow these steps:

### Prerequisites

- Access to [Azure Portal](https://portal.azure.com) or [Microsoft Entra admin center](https://entra.microsoft.com)
- Any Microsoft account (personal @outlook.com/@hotmail.com accounts work — you get a free Azure AD tenant)

### Steps

1. **Sign in** to [portal.azure.com](https://portal.azure.com)

2. **Navigate** to **Azure Active Directory** → **App registrations** → **New registration**

3. **Fill in the registration form:**
   - **Name:** `OutlookAlerter` (or any name you prefer)
   - **Supported account types:** Select **"Accounts in any organizational directory (Any Azure AD directory - Multitenant) and personal Microsoft accounts"**
   - **Redirect URI:**
     - Platform: **Public client/native (mobile & desktop)**
     - URI: `http://localhost:8888/redirect`
   - Click **Register**

4. **Copy the Application (client) ID** from the Overview page

5. **Add API permissions:**
   - Go to **API permissions** → **Add a permission**
   - Select **Microsoft Graph** → **Delegated permissions**
   - Add: `Calendars.Read`, `User.Read`, `offline_access`
   - **Do NOT** click "Grant admin consent" — users will consent individually

6. **Enable public client flows:**
   - Go to **Authentication** → scroll to **Advanced settings**
   - Set **"Allow public client flows"** to **Yes**
   - Click **Save**

7. **Configure in OutlookAlerter:**
   - Open **Settings** in OutlookAlerter
   - Enter your **Client ID** in the "Client ID (Azure AD App)" field
   - Set **Tenant ID** to `common` (or your specific tenant ID if single-tenant)
   - Click **Save**

### Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| "Selected user account does not exist in tenant" | App registered as single-tenant | Go to app's **Authentication** page and change "Supported account types" to multi-tenant, or edit **Manifest** and set `"signInAudience": "AzureADandPersonalMicrosoftAccount"` |
| "AADSTS65001: The user or administrator has not consented" | Organization requires admin consent | Ask your IT admin to grant consent for the app (see Admin Consent below), or use the Graph Explorer manual token flow instead |
| "AADSTS65002: Consent between first party application..." | Tried using a Microsoft first-party app ID | Microsoft blocks third-party code from using first-party app IDs. Use a custom app registration instead |
| "AADSTS700016: Application not found in directory" | Wrong tenant ID or app was deleted | Verify the Client ID is correct and the app exists in Azure Portal |
| Browser opens but sign-in fails silently | Redirect URI mismatch | Ensure the app registration has exactly `http://localhost:8888/redirect` as a **Public client** redirect URI |

## Admin Consent

If your organization requires admin approval for third-party applications, an IT administrator needs to grant consent once for the app. After this one-time approval, all users in the organization can sign in without seeing the consent prompt.

### Option 1: Admin Consent URL (Recommended)

Share this URL with your IT admin — they can approve the app by visiting it and signing in with an admin account:

```
https://login.microsoftonline.com/common/adminconsent?client_id=ce88d638-d3c7-42fd-b46c-6b55e9decf12
```

Replace the client_id if you registered your own app.

### Option 2: Via Entra Admin Center

1. Admin signs in to [entra.microsoft.com](https://entra.microsoft.com)
2. Navigate to **Enterprise applications** → search for the app by client ID
3. Go to **Permissions** → click **"Grant admin consent for [org]"**
4. Review and accept the requested permissions (`Calendars.Read`, `User.Read`, `offline_access`)

### What Gets Approved

The app only requests **delegated** (user-context) permissions — it cannot access other users' data. Each user still signs in with their own credentials and MFA.

## Fallback: Manual Token Entry

If OAuth doesn't work in your environment, the manual Graph Explorer workflow is always available:

1. In the token dialog, click **"Open Graph Explorer"**
2. Sign in at Graph Explorer
3. Click your profile picture → "Access token" tab
4. Copy the token and paste it into OutlookAlerter

This method requires re-authentication approximately every hour as Graph Explorer tokens expire quickly.
