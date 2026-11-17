# Third-Party Licenses & Notices

**NekoXray** source code (`app/src/`, `docs/`, and Gradle build configuration) is original code dual-licensed under **`MIT OR LGPL-3.0-or-later`** (see [`LICENSE`](LICENSE)).

No source code from GPL-licensed projects (such as NekoBoxForAndroid) is included in this repository; NekoXray implements clean-room compatibility only for public proxy URI schemes and plain-text Per-App package lists (`false`/`true`).

---

## 1. AndroidLibXrayLite (`app/libs/libv2ray.aar`)
- **Upstream:** https://github.com/2dust/AndroidLibXrayLite
- **License:** GNU Lesser General Public License v3.0 (**LGPL-3.0**)
- **License Texts:** [`COPYING.LESSER`](COPYING.LESSER) (LGPL-3.0) and [`COPYING`](COPYING) (GPL-3.0, incorporated by reference)
- **Usage:** Native Xray-core Android AAR (`v26.10.31`) downloaded at build time by `app/build.gradle.kts` (`DownloadLibv2rayTask`) and packaged into APK/AAB builds.
- **LGPL-3.0 Compliance Terms (Section 4 — Combined Works):**
  1. **Notice:** Compiled APK/AAB builds include `AndroidLibXrayLite` under LGPL-3.0 (displayed in `LICENSE`, `README`, `THIRD-PARTY-LICENSES.md`, and the in-app **About** card).
  2. **Accompanying Licenses:** Copies of both the GNU LGPL-3.0 (`COPYING.LESSER`) and GNU GPL-3.0 (`COPYING`) accompany this distribution.
  3. **Source Code & Relinking:** Complete corresponding source code for `AndroidLibXrayLite` is available at https://github.com/2dust/AndroidLibXrayLite. Full NekoXray source code and Gradle build scripts are provided in this repository so any user can replace `app/libs/libv2ray.aar` with a modified LGPL-3.0 version and rebuild the APK/AAB from source.

## 2. Xray-core (bundled inside AndroidLibXrayLite)
- **Upstream:** https://github.com/XTLS/Xray-core
- **License:** Mozilla Public License 2.0 (**MPL-2.0**)
- **Usage:** Core proxy and tunneling engine compiled inside `libv2ray.aar` (including VLESS REALITY, XHTTP, VMess, Trojan, Shadowsocks, WireGuard, and native Hysteria 2).

## 3. tech.kwik:kwik (`0.12.2`)
- **Upstream:** https://github.com/ptrd/kwik
- **License:** GNU Lesser General Public License v3.0 (**LGPL-3.0**)
- **Usage:** Pure-Java QUIC & TLS 1.3 client library powering `TuicTunnelBridge` for TUIC v5 (`tuic://`) connections, 0-RTT session resumption, and RFC 8446 `ExportKeyingMaterial` authentication.

## 4. mwiede/jsch (`2.28.8`)
- **Upstream:** https://github.com/mwiede/jsch
- **License:** Revised BSD License (**BSD-3-Clause**)
- **Usage:** Pure-Java SSH2 client powering `SshTunnelBridge`.

## 5. Routing Geo Data (`geoip.dat` & `geosite.dat`)
- **`v2fly/geoip` (`geoip.dat`):** https://github.com/v2fly/geoip — Creative Commons Attribution-ShareAlike 4.0 International (**CC-BY-SA-4.0**)
- **`v2fly/domain-list-community` (`dlc.dat` / `geosite.dat`):** https://github.com/v2fly/domain-list-community — **MIT License**
- **Usage:** Downloaded automatically at build time (`DownloadGeoAssetsTask` in `app/build.gradle.kts`) into `app/src/main/assets/` and unpacked on first launch for Xray routing rules (`geoip:private`, `geosite:private`, `geosite:category-ads-all`, etc.).

## 6. Open-Source Libraries under Apache License 2.0
- **AndroidX / Jetpack Compose / Material 3 / Room (`2.7.0`) / CameraX (`1.5.0`) / DataStore / Navigation:** Apache-2.0
- **Kotlin Standard Library, Kotlin Coroutines, kotlinx.serialization:** Apache-2.0
- **OkHttp (`com.squareup.okhttp3`):** Apache-2.0
- **ZXing Core (`com.google.zxing:core` `3.5.3`):** Apache-2.0
