# YT Music Remote

Android同士で、片方の端末の **YouTube Music** をもう片方から操作する専用リモートアプリです。

汎用メディアリモコンではありません。対象は公式YouTube Music Androidアプリ (`com.google.android.apps.youtube.music`) に固定し、Android標準のMediaSessionとYouTube Musicの共有リンクを組み合わせます。

## できること

- 再生 / 一時停止
- 前の曲 / 次の曲
- 10秒戻し / 10秒送り
- Now Playingの曲名、アーティスト、アルバム、再生位置を取得
- 曲名 / アーティスト名からリモート検索再生を要求
- YouTube Musicの曲・アルバム・プレイリスト共有リンクを再生端末へ転送
- YouTube Musicの共有先に `YT Music Remote` を表示
- ペアリングQRでIP / Port / ペアリングキーを自動登録
- 操作端末にアプリがない場合の最新署名済みAPKダウンロードQR
- 同一LAN上の再生端末をmDNS / NSDで検出
- GitHub Releasesの更新を24時間ごとに確認して通知

## 基本の使い方

### 1. 再生端末

1. YT Music Remoteで「再生する端末」を選ぶ
2. 通知へのアクセスを許可
3. 「リモート受付を開始」
4. 画面にペアリングQRを表示しておく

### 2. 操作端末

1. YT Music Remoteで「操作する端末」を選ぶ
2. 「ペアリングQRを読み取る」
3. 再生端末のQRをスキャン

Google Code Scannerを使うため、アプリ自身にカメラ権限は不要です。

### 3. YouTube Musicから選曲

1. 操作端末でYouTube Musicを開く
2. 曲・アルバム・プレイリストを選ぶ
3. YouTube Musicの「共有」を押す
4. `YT Music Remote` を選ぶ
5. 共有リンクがペアリング済み再生端末へ送られる

同じGoogleアカウントを両端末のYouTube Musicで使う前提です。MediaRemoteはGoogleのログイン情報やYouTube APIキーを保持しません。

## 再生端末への反映方法

共有リンクはYouTube Music専用URLへ正規化した後、次の順で処理します。

1. YouTube MusicのMediaSessionが `ACTION_PLAY_FROM_URI` を公開していれば `playFromUri()`
2. 利用できない場合は再生端末のYouTube MusicへURLを `ACTION_VIEW` で渡す

曲名検索も同様に、MediaSessionの `playFromSearch()` を優先し、Android標準のmedia search Intentへフォールバックします。

YouTube Music側のバージョンによってはリンクを開いても自動再生せず、プレイリスト画面を開くところまでになる場合があります。Accessibilityによる画面自動操作は壊れやすく侵襲的なため採用していません。

## 接続

```text
操作端末 (YT Music Remote)
        |
        | 同一LAN / TCP :50505
        v
再生端末 (YT Music Remote foreground service)
        |
        | MediaSessionManager + YouTube Music link handoff
        v
YouTube Music
```

mDNSにはIP探索に必要なサービス情報だけを公開し、ペアリングキーは載せません。初回接続はQR推奨です。

## セキュリティ

現在は同一LAN向けです。ペアリングキーによる認証はありますが、TCP通信自体はまだ暗号化していません。ルータでポート50505をインターネットへ公開しないでください。

ペアリングQRにはローカルIP、Port、ペアリングキーが入るため、QRのスクリーンショットを公開しないでください。

## 更新

mainへマージされるとGitHub Actionsが固定release鍵でAPKを署名し、`debug-latest` Releaseを置き換えます。

固定ダウンロード名:

`MediaRemote-latest.apk`

アプリは起動時に最大24時間に1回更新確認し、新しいbuildがあれば通知します。

## Build

- compileSdk / targetSdk: 37
- minSdk: 28
- Compose BOM: 2026.08.00
- JDK: 17
- Google Code Scanner: 16.1.0

GitHub Actionsでdebug APKのCIビルドと、署名済みrolling releaseの証明書検証を行います。
