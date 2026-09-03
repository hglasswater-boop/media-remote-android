# YT Music Remote

Android同士で、片方の端末の **YouTube Music** をもう片方から操作する専用リモートアプリです。

汎用メディアリモコンではありません。対象は公式YouTube Music Androidアプリ (`com.google.android.apps.youtube.music`) に固定し、Android標準のMediaSession、YouTube Musicの共有リンク、DIAL / YouTube Lounge互換の受信経路を組み合わせます。

## できること

- 再生 / 一時停止 / 停止
- 前の曲 / 次の曲
- 10秒戻し / 10秒送り、再生位置指定
- Now Playingの曲名、アーティスト、アルバム、再生位置を取得
- 曲名 / アーティスト名からリモート検索再生を要求
- YouTube Musicの曲・アルバム・プレイリスト共有リンクを再生端末へ転送
- YouTube Musicの共有先に `YT Music Remote` を表示
- **Preview:** 純正YouTube MusicのCast pickerから再生端末の `YT Music Remote ...` を選び、DIAL / YouTube Lounge経由で選曲・再生操作を受信
- ペアリングQRでIP / Port / ペアリングキーを自動登録
- 操作端末にアプリがない場合の最新署名済みAPKダウンロードQR
- 同一LAN上の再生端末をmDNS / NSDで検出
- GitHub Releasesの更新を24時間ごとに確認して通知、手動更新確認

## 基本の使い方

### 1. 再生端末

1. YT Music Remoteで「再生する端末」を選ぶ
2. 通知へのアクセスを許可
3. 「リモート受付を開始」
4. 同一LANのリモート操作と、YouTube Music Cast待受が開始される

### 2. 操作端末をYT Music Remoteでペアリングする場合

1. YT Music Remoteで「操作する端末」を選ぶ
2. 「ペアリングQRを読み取る」
3. 再生端末のQRをスキャン

Google Code Scannerを使うため、アプリ自身にカメラ権限は不要です。

### 3-A. 共有リンクで選曲

1. 操作端末でYouTube Musicを開く
2. 曲・アルバム・プレイリストを選ぶ
3. YouTube Musicの「共有」を押す
4. `YT Music Remote` を選ぶ
5. 共有リンクがペアリング済み再生端末へ送られる

### 3-B. Cast pickerから選曲する Preview 経路

1. 再生端末でYT Music Remoteの「リモート受付」を開始する
2. 操作端末と再生端末を同じLAN / Wi-Fiに接続する
3. 操作端末の**純正YouTube Music**でCastボタンを開く
4. `YT Music Remote <端末名>` が表示されたら選択する
5. YouTube Musicから送られる選曲・再生操作を、再生端末のYT Music RemoteがDIAL / YouTube Lounge経由で受け、同じ端末の純正YouTube Musicへ反映する

このCast経路は非公開のYouTube Lounge互換プロトコルに依存するPreview機能です。Google Castデバイス証明書を偽装せず、ReVancedやAccessibilityにも依存しません。YouTube Music側の仕様変更で互換性が変わる可能性があります。

同じGoogleアカウントを両端末のYouTube Musicで使う前提です。YT Music RemoteはGoogleのログイン情報やYouTube APIキーを保持しません。

## 再生端末への反映方法

YouTube Musicリンクは専用URLへ正規化した後、次の順で処理します。

1. YouTube MusicのMediaSessionが `ACTION_PLAY_FROM_URI` を公開していれば `playFromUri()` を試す
2. MediaSessionの状態・メタデータ・キューが実際に変化した場合だけ成功扱いにする
3. 必要に応じて `prepareFromUri()` + `play()` を試す
4. 反応しなければ再生端末のYouTube Musicへ `ACTION_VIEW` でディープリンクを渡す

曲名検索も同様に、MediaSessionの `playFromSearch()` を優先し、Android標準のmedia search Intentへフォールバックします。

YouTube Music側のバージョンやAndroidのバックグラウンドActivity起動制限によっては、リンクを開いても自動再生しない場合があります。Accessibilityによる画面自動操作は壊れやすく侵襲的なため採用していません。

## 接続

通常のペアリング済みリモート操作:

```text
操作端末 (YT Music Remote)
        |
        | 同一LAN / TCP :50505 + pairing token
        v
再生端末 (YT Music Remote foreground service)
        |
        | MediaSessionManager + YouTube Music link handoff
        v
YouTube Music
```

Cast picker Preview:

```text
操作端末の純正YouTube Music
        |
        | SSDP / DIAL + YouTube Lounge (theme=m)
        v
再生端末のYT Music Remote
        |
        | MediaSessionManager
        v
再生端末の純正YouTube Music
```

mDNSにはIP探索に必要なサービス情報だけを公開し、ペアリングキーは載せません。通常の初回接続はQR推奨です。

## セキュリティ

通常のリモート操作は同一LAN向けです。ペアリングキーによる認証はありますが、TCP通信自体はまだ暗号化していません。ルータでポート50505をインターネットへ公開しないでください。

ペアリングQRにはローカルIP、Port、ペアリングキーが入るため、QRのスクリーンショットを公開しないでください。

DIAL待受もLAN内でのみ利用する前提です。GoogleアカウントのパスワードやOAuthトークンは受信・保存しません。

## 更新

mainへマージされるとGitHub Actionsが固定release鍵でAPKを署名し、`debug-latest` Releaseを置き換えます。

固定ダウンロード名:

`MediaRemote-latest.apk`

アプリは起動時に最大24時間に1回更新確認し、新しいbuildがあれば通知します。アプリ内の「更新を確認」から手動確認もできます。

## Build

- compileSdk / targetSdk: 37
- minSdk: 28
- Compose BOM: 2026.08.00
- JDK: 17
- Google Code Scanner: 16.1.0

GitHub Actionsでdebug APKのCIビルドとlint、署名済みrolling releaseの証明書検証を行います。
