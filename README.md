# MediaRemote

Android同士で、片方の端末で再生している **YouTube Music** をもう片方の端末から操作する実験的なリモートコントローラです。

Spotify Connectそのものを再実装するのではなく、Android標準の `MediaSession` / `MediaController` を使って再生端末側のYouTube Musicを操作します。

## 現在できること

- 再生 / 一時停止
- 前の曲 / 次の曲
- 10秒戻し / 10秒送り
- 曲名、アーティスト、再生位置の取得
- 同一LAN上のAndroid端末からTCPで操作（Android 17ではローカルネットワーク権限を要求）
- ランダムなペアリングキーによる簡易認証
- 再生端末ではForeground Serviceとして待受

## 仕組み

```text
操作端末 (MediaRemote)
        |
        | TCP :50505 + pairing token
        v
再生端末 (MediaRemote foreground service)
        |
        | MediaSessionManager
        v
YouTube Music
```

再生端末ではNotification Listener権限を利用して、YouTube Music (`com.google.android.apps.youtube.music`) のアクティブなMediaSessionを取得します。

## 初回セットアップ

### 再生端末

1. MediaRemoteを起動
2. 「再生端末」を選ぶ
3. 「通知へのアクセスを開く」からMediaRemoteを許可
4. YouTube Musicで何か再生
5. MediaRemoteに戻って「LANリモートを開始」
6. 表示されたIPアドレスとペアリングキーを操作端末に入力

### 操作端末

1. MediaRemoteを起動
2. 「操作端末」を選ぶ
3. 再生端末のIPアドレスとペアリングキーを入力
4. 「接続 / 状態取得」
5. 再生ボタン等で操作

## セキュリティ

現段階はLAN内プロトタイプです。通信自体は暗号化していないため、インターネットへポートを公開しないでください。今後はQRペアリング、端末鍵、暗号化通信、端末検出を追加します。

## Build

- Android Gradle Plugin: 9.4.0
- Gradle: 9.6.0
- Kotlin: 2.4.10
- compileSdk / targetSdk: 37
- minSdk: 28
- Compose BOM: 2026.08.00
- JDK: 17

Gradle Wrapperはリポジトリに含めています。GitHub ActionsはAndroid 17 SDKを導入し、Wrapper経由でdebug APKをビルドしてartifactとして保存します。
