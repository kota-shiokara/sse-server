# sse-client

同じリポジトリの [sse-server](../README.md) の SSE ストリームを受信する Android アプリ
（Kotlin + Jetpack Compose）。

ビルドはリポジトリのルートとは独立していて、この `client/` 以下に自分の `gradlew` と
バージョンカタログを持つ（Android SDK と JDK 17 を要求するため、サーバー側のビルドと
同じ設定にはできない）。

## できること

- SSE エンドポイントの URL を指定して接続 / 切断
- 受信したイベントを新しい順に一覧表示（時刻・`event` 名・`id`・`data`）
- 接続状態の表示（disconnected / connecting / connected / reconnecting）
- 切断されたら 3 秒間隔で自動再接続

受信専用。サーバーへの送信（`POST /broadcast`）は含まない。

## 構成

```
app/src/main/java/jp/ikanoshiokara/sseclient/
├── MainActivity.kt / Navigation.kt        画面の入口
├── data/SseRepository.kt                 OkHttp SSE で接続し Flow<SseUpdate> を流す
└── ui/main/
    ├── MainScreenViewModel.kt            接続の開始/停止・再接続・受信履歴の保持
    └── MainScreen.kt                     Compose UI
```

SSE の受信には OkHttp の `okhttp-sse` を使用している。ストリームは開いたままなので
`readTimeout` を 0（無効）に設定している点に注意。

再接続は Repository 側では行わず、切断を例外として上位へ伝え、
ViewModel の `retryWhen` で張り直す形にしている。

## 動かし方

1. サーバーを起動する（リポジトリのルートで）。

   ```sh
   cd .. && docker compose up -d --build
   ```

2. アプリをビルドしてエミュレーターへインストールする。

   ```sh
   ./gradlew assembleDebug
   android emulator start <avd-name>
   android run --apks=app/build/outputs/apk/debug/app-debug.apk
   ```

3. アプリで **Connect** を押す。デフォルトの URL は `http://10.0.2.2:8080/events`
   （`10.0.2.2` はエミュレーターから見たホストマシン）。

4. ホスト側からイベントを流す。

   ```sh
   cd .. && ssectl send --data='hello from host'
   # curl でも良い
   curl -X POST http://localhost:8080/broadcast -d 'hello from host'
   ```

   `ssectl` の使い方は [docs/cli.md](../docs/cli.md)、
   送れるフレームの種類は [docs/api.md](../docs/api.md) を参照。

実機から LAN 上のサーバーへ繋ぐ場合は、アプリの URL 欄をそのホストの IP に変え、
`app/src/main/res/xml/network_security_config.xml` にその IP を `domain` として追加する
（http の平文通信を許可するため）。

## テスト

```sh
./gradlew testDebugUnitTest        # ViewModel と SSE パース (MockWebServer)
./gradlew connectedDebugAndroidTest # Compose UI (端末/エミュレーター必須)
```
