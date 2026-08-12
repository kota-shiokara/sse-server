# ssectl (CLI)

サーバーへイベントを送る CLI。Kotlin/Native の単一バイナリで、JVM を必要としない。

サーバーのクラスは呼ばず、[WebAPI](api.md) しか使わない。
共有しているのは `shared` モジュールの DTO（契約）だけなので、
サーバーと独立にビルド・配布できる。

## インストール

```sh
./install-cli.sh      # ビルドして ~/.local/bin/ssectl に置く
./uninstall-cli.sh    # ~/.local/bin/ssectl を消す
```

初回は Kotlin/Native のツールチェーン（LLVM や sysroot、~1GB）を取りに行くので数分かかる。
2 回目以降は数秒。

ビルドだけしたいときは `./gradlew :cli:installCli`
（成果物は `cli/build/install/ssectl`）。

## 使い方

```sh
ssectl --help                                                # 使い方
ssectl stats                                                 # 接続数を見る
ssectl send --data='hello'                                   # 1 件送る
ssectl send --event=progress --data='50%' --id=p-1           # event 名と id を指定
ssectl send --event=tick --data=t --repeat=5 --interval=500  # 500ms 間隔で 5 回
ssectl send --data='遅れて届く' --delay=3000                  # 3 秒後に送る
ssectl send --id= --data='id 行なしのイベント'                # id 行を省く
ssectl send --event= --data='event 行なしのイベント'          # event 行を省く
ssectl send --retry=1000 --data='再接続間隔の指示'            # retry 行を付ける
ssectl send --data-file=payload.json                         # ファイルの中身を data に
ssectl comment 'keep-alive'                                  # コメントフレーム
ssectl scenario scenarios/demo.json                          # シナリオ再生
ssectl disconnect                                            # 全接続を切断
ssectl watch                                                 # 受信して流し見る
```

各フィールドの意味（`--event=` と空にしたときの挙動など）は
[WebAPI 仕様のフレームの組み立てルール](api.md#フレームの組み立てルール) と同じ。

サーバーに投げる前に CLI 側でも `repeat` などの検証をするので、
明らかに不正な指定は HTTP を待たずに弾かれる。

`ssectl watch` はサーバー側から切断されると（`ssectl disconnect` やシナリオの
`disconnect` ステップ）**exit 0 で自動終了する**ので、
「切断されるまで受信する」というスクリプトが書ける。

## 接続先の指定

次の優先順で決まる。

1. サブコマンドの `--base`（`ssectl stats --base=http://localhost:8099`）
2. ルートの `--base`（`ssectl --base=http://localhost:8099 stats`）
3. 環境変数 `SSE_BASE_URL`
4. 既定 `http://localhost:8080`

```sh
export SSE_BASE_URL=http://localhost:8099    # 毎回書くのが面倒なとき
```

## 他アーキテクチャ向け

macOS ホストから Linux 向けもクロスコンパイルできる。

```sh
./gradlew :cli:linkReleaseExecutableLinuxArm64   # → cli/build/bin/linuxArm64/releaseExecutable/ssectl.kexe
./gradlew :cli:linkReleaseExecutableLinuxX64
./gradlew :cli:linkReleaseExecutableMacosArm64
```

対応ターゲットは `macosArm64` / `linuxX64` / `linuxArm64`。
Intel Mac (`macosX64`) は Kotlin/Native 側で非推奨になっているため入れていない。
必要になったら `cli/build.gradle.kts` の targets に足す。

## コンテナの中から使う

compose のネットワークに入れば、サービス名で引ける。

```sh
docker run --rm --network sse-server_default \
  -v "$PWD/cli/build/bin/linuxArm64/releaseExecutable/ssectl.kexe:/usr/local/bin/ssectl:ro" \
  -e SSE_BASE_URL=http://sse-server:8080 \
  debian:12-slim ssectl stats
```

## 実装

```
cli/src/commonMain/kotlin/jp/ikanoshiokara/sse/cli/
├── Main.kt        # コマンド定義 (Clikt)
├── Commands.kt    # send / comment / scenario / disconnect / stats / watch
├── SseApi.kt      # WebAPI クライアント (Ktor client CIO)
└── Files.kt       # ファイル読み (kotlinx-io)
```

`watch` は SSE プラグインを使わず生の行を読んで出している。
SSE の枠組みそのものを観察するツールなので、パース済みより生フレームの方が目的に合う。
