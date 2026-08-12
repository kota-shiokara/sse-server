# sse-server

Kotlin + Ktor で書いた SSE (Server-Sent Events) のモックサーバーと、それを操作する CLI。

任意の SSE フレームを好きなタイミングで送れるので、SSE を**受け取る側**の開発・検証に使える。
event 名や `id` の指定、連続送信、遅延、サーバー側からの強制切断、
複数イベントのシナリオ再生ができる。

受信側を試すための Android クライアントも同梱している（[`client/`](client/README.md)）。

## ドキュメント

| 文書 | 中身 |
| ---- | ---- |
| [docs/api.md](docs/api.md) | WebAPI の仕様。エンドポイント一覧、フレームの組み立てルール、配信の制約 |
| [docs/cli.md](docs/cli.md) | `ssectl` のインストール、使い方、クロスコンパイル |
| [docs/development.md](docs/development.md) | 設計メモ、テスト、イメージのビルド |
| [docs/troubleshooting.md](docs/troubleshooting.md) | ポート衝突、CORS、変更が反映されないとき |
| [client/README.md](client/README.md) | 受信側の Android アプリ（別ビルド） |

この README には「何であるか」と「起動して設定するまで」だけを置いている。

## 構成

**サーバーは Docker で常駐させ、操作は WebAPI 経由**という前提で 3 モジュールに分かれている。

| モジュール | 中身                             | 成果物                    |
| ---------- | -------------------------------- | ------------------------- |
| `shared`   | WebAPI の契約 (DTO と検証)。KMP  | jvm / native の klib      |
| `server`   | Ktor アプリ。JVM                 | Docker イメージ (fat jar) |
| `cli`      | `ssectl` コマンド。Kotlin/Native | 単一バイナリ              |

`cli` はサーバーのクラスを直接呼ばず、`shared` の DTO を共有した上で
**HTTP しか使わない**。つまり CLI とサーバーは独立にビルド・配布できる。

`client/` の Android アプリは**別の Gradle ビルド**として同居している（`client/gradlew`）。
Android SDK と JDK 17 を要求し、Kotlin のバージョンもこちらとは別なので、
1 つのビルドにまとめていない。受信専用で `shared` も使わないため、分けても失うものがない。

```
sse-server/
├── gradle/libs.versions.toml    # バージョンカタログ
├── settings.gradle.kts          # shared / server / cli
├── compose.yaml
├── install-cli.sh               # CLI をビルドして ~/.local/bin へ
├── uninstall-cli.sh
├── docs/
├── shared/
│   └── src/commonMain/kotlin/com/example/sse/api/ApiModels.kt
├── server/
│   ├── Dockerfile
│   └── src/main/
│       ├── kotlin/com/example/sse/
│       │   ├── Application.kt   # 起動とルーティング
│       │   ├── Broadcaster.kt   # 全セッションへの配信ハブ (SharedFlow)
│       │   └── Frames.kt        # EventRequest → SSE フレーム
│       └── resources/static/    # 送信コンソール (index.html)
├── cli/
│   └── src/commonMain/kotlin/com/example/sse/cli/
├── scenarios/demo.json          # シナリオのサンプル
└── client/                      # 受信側 Android アプリ (独立した Gradle ビルド)
    ├── gradlew
    ├── settings.gradle.kts
    └── app/
```

ビルドは 2 系統ある。

```sh
./gradlew check                        # shared / server / cli
cd client && ./gradlew assembleDebug   # Android アプリ
```

## クイックスタート

```sh
# 1. サーバーを Docker で起動する
docker compose up -d --build

# 2. CLI をビルドして入れる (初回はネイティブツールチェーンの取得で数分かかる)
./install-cli.sh

# 3. 送ってみる
ssectl stats
ssectl send --data='hello'
ssectl scenario scenarios/demo.json

# ブラウザの送信コンソールからでも同じことができる
open http://localhost:8080/
```

受信側の確認だけしたいときは `ssectl watch`（または `curl -N http://localhost:8080/events`）を
別のターミナルで開いておく。

うまくいかないときは [docs/troubleshooting.md](docs/troubleshooting.md) を参照。

## 起動

### Docker (通常こちら)

```sh
docker compose up -d --build     # 起動
docker compose ps                # 状態 (healthy になるまで ~20 秒)
docker compose logs -f           # ログ
docker compose down              # 停止
```

イメージは非 root (uid 10001) で動き、`/health` を見る HEALTHCHECK が入っている。

### ローカル (Gradle)

Docker を挟まずに動かしたいとき。

```sh
./gradlew :server:run
```

fat jar を作る場合:

```sh
./gradlew :server:buildFatJar
java -jar server/build/libs/sse-server.jar
```

## 設定

| 環境変数            | 既定値 | 説明                           |
| ------------------- | ------ | ------------------------------ |
| `PORT`              | `8080` | コンテナ内の待ち受けポート     |
| `SSE_ALLOW_ORIGINS` | `*`    | CORS で許可するオリジン (後述) |

compose 経由なら、ホスト側のポートだけ `SSE_PORT` で変えられる。

```sh
SSE_PORT=8099 docker compose up -d
ssectl stats --base=http://localhost:8099
```

待ち受けアドレスは `0.0.0.0` 固定。

### CORS

別オリジンのブラウザアプリから `EventSource` でこのモックを購読するのが主な用途なので、
**既定では全オリジンを許可**している (`Access-Control-Allow-Origin: *`)。

絞る場合はスキーム付きでカンマ区切りに指定する。空文字にすると CORS プラグイン自体を入れない。

```sh
SSE_ALLOW_ORIGINS=http://localhost:3000,https://app.example.com docker compose up -d
```

`X-Sse-Mock` は `Access-Control-Expose-Headers` に載せているので、
ブラウザ側の JS からも応答元を確かめられる。

## イベントの送り方

3 通りある。どれも同じ WebAPI を叩いている。

| 手段                     | 向いている用途                       |
| ------------------------ | ------------------------------------ |
| CLI (`ssectl`)           | 自動テスト、繰り返し、シナリオ再生   |
| Web コンソール (`/`)     | 手動での試行、デモ、挙動の目視確認   |
| WebAPI を直接叩く        | 他のツールやスクリプトからの組み込み |

CLI は [docs/cli.md](docs/cli.md)、WebAPI は [docs/api.md](docs/api.md) を参照。

### Web コンソール

http://localhost:8080/

1 画面で以下ができる。

- **イベントを送る** — event 名 / data / id / retry / comment / repeat / interval / delay を
  フォームで指定して送信。`id を送らない` チェックで id 行を省略できる。
- **コメントのみ送信** — `: <text>` だけのフレームを送る。
- **全接続を切断** — サーバー側から切って、クライアントの再接続処理を試す。
- **シナリオ再生** — JSON を貼って一括で流す。サンプルが最初から入っている。
- **受信ログ** — このページ自身も `/events` に繋いでいるので、送った結果がそのまま見える。
  接続数と最後のイベント ID は 2 秒ごとに `/stats` を見て更新する。
