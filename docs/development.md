# 開発

## 設計メモ

配信ハブは `StreamSignal` を流す `SharedFlow` 1 本で、`Frame`（送出するフレーム）と
`Close`（切断）の 2 種類を扱う。各 SSE セッションはこれを `takeWhile` しながら collect し、
`Close` が来ると collect が完了してハンドラを抜け、接続が閉じる。

`shared` は WebAPI の契約だけを持ち、ktor には依存しない（Kotlin/Native の CLI から
使うため）。`EventRequest` を SSE フレームへ変換する処理はサーバー側の関心なので
`server/Frames.kt` にある。

## テスト

```sh
./gradlew check       # 全モジュール
./gradlew :server:test :shared:jvmTest :cli:macosArm64Test
```

- `shared/EventRequestTest` — 検証ルールと JSON の既定値。jvm と native の両方で走る
- `server/BroadcasterTest` — ID の採番、フレーム組み立て（`id` / `event` の省略、
  コメントのみ）、切断シグナル
- `server/ApplicationTest` — 各エンドポイントのステータスとレスポンス JSON、
  識別ヘッダ、CORS
- `cli/SseApiTest` — CLI が実際に送る JSON とパス、エラーレスポンスの翻訳
  （`MockEngine` を使う）。サーバーと別モジュールになった分の**ワイヤ形式のずれ**を
  ここで止める

Linux ターゲットのテストは macOS ホストでは実行できない（ビルドはできる）。

## Docker イメージのビルド

`server/Dockerfile` のビルドコンテキストはリポジトリのルート（`shared/` も必要なため）。

```sh
docker build -f server/Dockerfile -t sse-server:local .
```

`-Psse.serverOnly=true` を渡して CLI モジュールを Gradle の構成から外しているので、
イメージのビルドに Kotlin/Native のツールチェーンは要らない。

## Android クライアント (client/)

`client/` は**独立した Gradle ビルド**で、ルートの `settings.gradle.kts` には含めていない。

```sh
cd client
./gradlew assembleDebug        # APK
./gradlew testDebugUnitTest    # ViewModel と SSE パース (MockWebServer)
```

分けている理由:

- Android SDK と JDK 17 を要求する。1 つのビルドにすると、サーバーだけ触りたいときや
  SDK の無い CI でも設定フェーズで SDK を要求されるようになる
- Kotlin のバージョンが違う（client 2.3.20 / こちら 2.4.0）。カタログを統合すると
  client 側を引き上げることになり、Compose コンパイラの整合確認が付いてくる
- client は受信専用で `shared` の DTO を使わない。共有するコードが無いので、
  分けても失うものがない

将来 client からサーバーへ POST する機能を入れるなら、`shared` に `androidTarget()` を
足して DTO を共有する価値が出る。その時点で統合を検討する。

詳細は [client/README.md](../client/README.md)。

## バージョン管理

依存のバージョンは [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) に集約している。
Kotlin プラグインはルートの `build.gradle.kts` で `apply false` して一度だけ読み込む
（サブプロジェクトごとにバージョン付きで宣言すると「loaded multiple times」の警告が出る）。
