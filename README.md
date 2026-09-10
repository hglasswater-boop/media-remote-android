# YT Music Remote

[English](README.en.md)

![Android CI](https://github.com/hglasswater-boop/media-remote-android/actions/workflows/android.yml/badge.svg)
![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)

**Android端末で動いているYouTube Musicを、別の端末のYouTube MusicからCast感覚で遠隔操作するための非公式アプリです。**

操作側には専用アプリを入れません。再生側のAndroid端末だけにYT Music Remoteを入れると、操作側のYouTube Musicから `YT Music Remote <端末名>` をCast先として選べるようになります。

> [!IMPORTANT]
> このプロジェクトはGoogle / YouTube / YouTube Musicの公式製品ではありません。DIAL / YouTube Lounge互換の非公開プロトコルに依存するため、YouTube Music側の変更で動作しなくなる可能性があります。

## 仕組み

```text
操作側 Android
YouTube Music
     │
     │ Cast / DIAL + YouTube Lounge
     ▼
再生側 Android
YT Music Remote
     │
     │ Android MediaSession
     ▼
YouTube Music
```

YT Music RemoteはGoogleアカウントのID・パスワードやCookieを保存しません。Google Castデバイス証明書の偽装やAccessibilityによる画面操作も行いません。

## できること

- YouTube Musicの曲・プレイリストを再生端末で再生
- プレイリスト内の選択曲をvideoIdとプレイリスト文脈付きで再生
- 再生 / 一時停止 / 前の曲 / 次の曲 / シーク
- 曲名・アーティスト・ジャケット・再生位置を操作側へ同期
- 同名・同アーティストの別動画をvideoIdで区別
- GitHub Releasesから署名済みAPKの更新を確認・インストール
- 操作側への専用アプリ導入やGoogleアカウント情報の登録は不要

## 必要なもの

| 役割 | 必要なもの |
| --- | --- |
| 再生側 | Android 9.0（API 28）以上、YouTube Music、YT Music Remote |
| 操作側 | YouTube Musicが動作する端末 |
| ネットワーク | 両端末が同じLAN / Wi-Fiに接続され、端末間通信・マルチキャストが許可されていること |

## インストール

### 1. 再生側にAPKを入れる

**[MediaRemote-latest.apk をダウンロード](https://github.com/hglasswater-boop/media-remote-android/releases/download/debug-latest/MediaRemote-latest.apk)**

または [Releases](https://github.com/hglasswater-boop/media-remote-android/releases) から最新の署名済みAPKを取得してください。

現在の `debug-latest` はmainブランチから自動生成されるローリング版です。安定版タグではなくpre-releaseとして配布しています。

Androidで「この提供元のアプリを許可」が求められた場合は、APKを開いたアプリに対してインストールを許可してください。

### 2. 初回設定

1. 再生側でYouTube Musicをインストールし、一度起動する
2. YT Music Remoteを起動する
3. **通知へのアクセス**を許可する
4. Androidがローカルネットワーク権限を求めた場合は許可する
5. 操作側と再生側を同じLAN / Wi-Fiに接続する

YT Music Remoteは起動時にCast待受を開始します。

長時間後にCast接続できない場合は、アプリまたは常駐通知の「Cast待受を再起動」を押してください。再生中のYouTube Musicは止まりません。

### 3. 操作する

1. 操作側でYouTube Musicを開く
2. Castアイコンをタップする
3. `YT Music Remote <端末名>` を選ぶ
4. 曲やプレイリストを選ぶ
5. 以降は通常のCastと同じ感覚で再生・一時停止・曲送り・シークを操作する

## 権限について

| 権限 / アクセス | 用途 |
| --- | --- |
| 通知へのアクセス | 再生側YouTube MusicのMediaSessionを検出し、曲情報や再生状態を取得・操作するため |
| ローカルネットワーク / Wi-Fi | DIAL / Lounge通信とLAN内の端末発見のため |
| マルチキャスト | Cast先として端末を発見できるようにするため |
| フォアグラウンドサービス | Cast待受を安定して継続するため |
| 通知 | 待受状態などをAndroid上で表示するため |
| 不明なアプリのインストール | アプリ内更新でダウンロードした署名済みAPKをインストールするため |
| インターネット | YouTube関連通信およびGitHub Releasesの更新確認のため |

## うまく接続できないとき

### Cast先に `YT Music Remote` が出ない

- 両端末が同じWi-Fi / LANか確認する
- ゲストWi-FiやAP isolation / client isolationが有効になっていないか確認する
- 再生側でYT Music Remoteを一度開く
- VPNやローカル通信を遮断するセキュリティアプリを一時的に切り分ける
- Wi-Fiルーターでマルチキャスト通信が遮断されていないか確認する

### 接続はできるが再生操作が効かない

- 再生側でYouTube Musicを一度起動する
- YT Music Remoteの通知アクセスが有効か確認する
- Androidのバッテリー最適化やバックグラウンド制限で停止されていないか確認する

### 曲情報や再生位置が合わない

YouTube MusicのMediaSessionとYouTube Loungeの状態同期にはタイミング差があります。曲変更後に数秒待っても更新されない場合は、接続し直すか再生側YouTube Musicの状態を確認してください。

問題が解決しない場合は、再現手順・Androidバージョン・YouTube Musicバージョン・端末名を添えて [Issue](https://github.com/hglasswater-boop/media-remote-android/issues/new/choose) を作成してください。

## 既知の制約

- `Favorite Songs` などのプレイリスト名が操作側で「再生キュー」と表示される場合があります
- 純正Chromecastと完全に同じメタデータ同期を保証するものではありません
- DIAL / YouTube Lounge互換の非公開プロトコルに依存しているため、YouTube Musicの更新で互換性が変わる可能性があります
- DRM解除・Google Cast証明書の偽装・Googleアカウント情報の保存は行いません

## 自動更新

mainブランチへのpushでGitHub Actionsが署名済みAPKを作成し、`debug-latest` Releaseを更新します。

アプリは起動時に最大24時間に1回更新を確認します。アプリ内の「更新を確認」から手動確認もできます。

## 開発

### 必要環境

- JDK 17
- Android SDK Platform 37 / Build Tools 37.0.0
- Android Studio または Gradle Wrapper

### ビルド

macOS / Linux:

```bash
git clone https://github.com/hglasswater-boop/media-remote-android.git
cd media-remote-android
./gradlew :app:assembleDebug
```

Windows:

```powershell
git clone https://github.com/hglasswater-boop/media-remote-android.git
cd media-remote-android
.\gradlew.bat :app:assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

### テスト / Lint

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CIでもJDK 17 / Android SDK 37を使用してビルドとLintを実行します。

## プロジェクト構成

```text
app/src/main/java/dev/mediaremote/
├── dial/      # DIAL / YouTube Lounge互換処理
├── media/     # MediaSession / YouTube Music連携
├── network/   # LAN待受・通信
├── ui/        # Jetpack Compose UI
└── update/    # GitHub Releasesを使ったアプリ更新

docs/         # プロトコル調査・設計・リリース関連資料
```

## コントリビューション

Issue、動作報告、互換性情報、Pull Requestを歓迎します。

開発フローやPR時の確認項目は [CONTRIBUTING.md](CONTRIBUTING.md) を参照してください。

## セキュリティ / プライバシー

脆弱性を見つけた場合は [SECURITY.md](SECURITY.md) を参照してください。

このアプリはGoogleアカウントの認証情報を保存しません。LAN内通信を扱うため、信頼できるネットワークで利用してください。

## License

このプロジェクトは [MIT License](LICENSE) で公開しています。

第三者プロジェクト由来の情報・ライセンス表記は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照してください。

---

YT Music Remote is an independent, unofficial open-source project and is not affiliated with, endorsed by, or sponsored by Google LLC or YouTube.
