# Guard Pal for Android

Byte, your guardian pet, as a real Android app:

- Scans **every installed app** for danger signs (sideloaded, Accessibility, Device admin, SMS, overlays, hidden icons, fake system names)
- Scans **your whole storage** (Downloads, WhatsApp, Telegram, Documents...) for disguised and dangerous files
- **Real-time protection**: checks every new app the moment it's installed, re-checks everything every 6 hours, and alerts you with an Uninstall button
- Uninstall apps and delete files in one tap (you always confirm)

## Build it (no Android Studio needed)

1. Create a free account at github.com and make a **new repository** (e.g. `guard-pal-android`).
2. On the repository page click **Add file → Upload files** and drag in **everything inside this folder**
   (including the `.github` folder). Click **Commit changes**.
3. Open the **Actions** tab. The "Build Guard Pal APK" job starts by itself (about 5 minutes).
   If it doesn't, click it and press **Run workflow**.
4. When it shows a green tick, open the run and download **GuardPal-APK** at the bottom.
5. Unzip it and send `app-debug.apk` to your phone. Open it and tap **Install**.
   (Android will ask to allow installing from this source; allow it, then switch it off again afterwards.)

## Updating

Upload the changed files to the same repository. GitHub builds a new APK; install it over the old one.
Your pet and settings are kept.
