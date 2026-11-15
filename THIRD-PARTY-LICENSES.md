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
- **Usage:** Core proxy and tunneling engine compiled inside `libv2ray.aar`.

## 3. mwiede/jsch
- **Upstream:** https://github.com/mwiede/jsch
- **License:** Revised BSD License (**BSD-3-Clause**)
- **Usage:** Pure-Java SSH2 client powering `SshTunnelBridge`.

## 4. Open-Source Libraries under Apache License 2.0
- **AndroidX / Jetpack Compose / Material 3 / Room / CameraX / DataStore / Navigation:** Apache-2.0
- **Kotlin Standard Library, Kotlin Coroutines, kotlinx.serialization:** Apache-2.0
- **OkHttp (`com.squareup.okhttp3`):** Apache-2.0
- **ZXing Core (`com.google.zxing:core`):** Apache-2.0
