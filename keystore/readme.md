# Keystore Setup Instructions

## A. Generate a Keystore (if you don't have one)

Run this command in your terminal:

```bash
keytool -genkey -v -keystore release.keystore -alias smsmatrix -keyalg RSA -keysize 2048 -validity 10000
```

Remember the `storePassword`, `keyPassword`, and `alias` (e.g. `smsmatrix`).

## B. Encode Keystore to Base64

Encode the file to place it into GitHub Secrets:

```bash
base64 -w 0 release.keystore > keystore_base64.txt
```

## C. Add Secrets to GitHub
1. Open your repository on GitHub.
2. Go to **Settings** → **Secrets and variables** → **Actions**.
3. Click **New repository secret** and add the following 4 secrets:
   * `RELEASE_KEYSTORE`: Paste the entire content of `keystore_base64.txt`.
   * `KEYSTORE_PASSWORD`: The password chosen for the keystore.
   * `KEY_ALIAS`: The key alias (e.g., `smsmatrix`).
   * `KEY_PASSWORD`: The key password (often same as keystore password).

---

### Verification

1. **Trigger Manual Run:**
   * Go to **Actions** $\rightarrow$ **Build Android Release** $\rightarrow$ **Run workflow**.
   * Verify the workflow builds `app-release.apk` and uploads it as an artifact.
2. **Trigger Release on Tag:**
   * Push a tag to verify GitHub Release creation:
     ```bash
     git tag v1.0.0
     git push origin v1.0.0
     ```
   * Verify the release is created under the repository's **Releases** tab with the attached APK.