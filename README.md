# YT Music Remote

片方のAndroid端末のYouTube Musicを、もう片方のYouTube Musicから操作するための再生端末アプリです。

対応する経路は次の1つだけです。

```text
操作側のYouTube Music
        │ Cast / DIAL + YouTube Lounge
        ▼
再生側のYT Music Remote
        │ Android MediaSession
        ▼
再生側のYouTube Music
```

操作側にはYT Music Remoteをインストールしません。操作側のYouTube MusicでCast先の
`YT Music Remote <端末名>` を選びます。

## できること

- YouTube Musicの曲・プレイリストを再生端末で再生
- プレイリスト内の選択曲を、その曲のvideoIdとプレイリスト文脈付きで再生
- 再生 / 一時停止 / 前の曲 / 次の曲 / シーク
- 曲名、アーティスト、ジャケット、再生位置の操作側への同期
- 同名・同アーティストの別動画をvideoIdで区別
- GitHub Releasesの署名済みAPK更新確認

## 使い方

### 再生端末

1. YT Music Remoteをインストールする
2. YouTube Musicをインストールし、起動しておく
3. 通知へのアクセスを許可する
4. 「Cast待受を開始」を押す
5. 操作側と同じLAN / Wi-Fiに接続する

### 操作端末

1. 専用アプリはインストールしない
2. YouTube Musicを開く
3. Castアイコンから `YT Music Remote <端末名>` を選ぶ
4. YouTube Musicで曲、プレイリスト、再生操作を行う

YouTube Music側の仕様により、Favorite Songsなどのキュー名が操作側で「再生キュー」と表示される場合があります。再生対象の曲と位置が正しければ正常です。

## 互換性

受信側はDIAL / YouTube Lounge互換の非公開YouTube Musicプロトコルに依存します。YouTube Musicの更新で互換性が変わる可能性があります。Google Castデバイス証明書の偽装、Accessibility操作、Googleアカウント情報の保存は行いません。

## 更新

mainへのpushでGitHub Actionsが署名済みAPKを作成し、`debug-latest` Releaseを更新します。アプリは起動時に最大24時間に1回更新を確認し、「更新を確認」から手動確認もできます。

## Build

- compileSdk / targetSdk: 37
- minSdk: 28
- Compose BOM: 2026.08.00
- JDK: 17

GitHub Actionsでdebug APKのテスト・Lintと、署名済みAPKの証明書検証を行います。
