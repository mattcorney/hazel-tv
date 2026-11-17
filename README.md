# Hazel Crescent TV

A small Android TV / Fire TV app that opens the Hazel Crescent launcher (`tv.html` on Home Assistant) full screen.

- The launcher itself lives on Home Assistant at `/config/www/tv.html` and still updates itself.
- This app adds: opening other apps from the tiles, an icon in the TV's app list, offering itself as a home screen, and background videos that start on their own.

## Download

Every change pushed to `main` is built automatically by GitHub. The newest app is always at:

`https://github.com/mattcorney/hazel-tv/releases/latest/download/hazel-tv.apk`

## Install on a TV

1. Install **Downloader** from the app store on the TV.
2. Open Downloader and enter the download address above (or copy `hazel-tv.apk` to `/config/www/` on Home Assistant and use `http://192.168.68.27:8123/local/hazel-tv.apk`).
3. Allow Downloader to install apps when asked, then choose **Install**.
4. Open **Hazel Crescent** from the TV's apps.

## Remote

- **Back** – closes panels in the launcher.
- **Hold Back** – change the Home Assistant address.

## Note

The signing key (`app/hazel-tv.keystore`) is kept in the project so every build installs over the last one without losing settings. It only signs this app.
