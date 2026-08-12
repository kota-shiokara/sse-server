# 困ったとき

## `ssectl` が「応答しているのは sse-server ではありません」と言う

サーバーが起動していないか、そのポートを別のプロセスが使っている。

```sh
docker compose ps
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

`ssh`（ポートフォワード）、他の `docker` コンテナ、別アプリの開発サーバーなどが 8080 を
掴んでいることがよくある。その場合は**接続自体は成功してしまう**ため、
相手のサーバーが返す 404 などが見えて原因が分かりにくい。
sse-server は全レスポンスに `X-Sse-Mock: sse-server` ヘッダを付けているので、
CLI はこれを見て「別のサーバーが応答している」ことを判別している。

別のポートで起動すれば回避できる。

```sh
SSE_PORT=8099 docker compose up -d
ssectl stats --base=http://localhost:8099

# 毎回書くのが面倒なら
export SSE_BASE_URL=http://localhost:8099
```

Android エミュレーターから繋ぐ場合は、アプリ側の URL も
`http://10.0.2.2:8099/events` に合わせる。

## `ssectl` が「接続できません」と言う

そのポートで何も listen していない。`docker compose up -d` で起動する。
起動直後は HEALTHCHECK が healthy になるまで数十秒かかることがある。

## ブラウザから `/events` に繋ぐと CORS で怒られる

`SSE_ALLOW_ORIGINS` を絞っている場合、指定はスキームを含める必要がある
（`localhost:3000` ではなく `http://localhost:3000`）。
既定ポート（80 / 443）のオリジンはブラウザがポートを省略して送ってくるので、
指定側もポートを書かない（`https://app.example.com`）。

## サーバーを直したのに反映されない

イメージを作り直す。

```sh
docker compose up -d --build
```

## CLI を直したのに反映されない

バイナリを作り直す。

```sh
./install-cli.sh
```

`command -v ssectl` が `~/.local/bin/ssectl` 以外を指していないかも確認する。

旧名のバイナリ（`sse` や `ssectl` 以外）が `cli/build/bin/` に残って見えることがある。
これは Gradle のビルドキャッシュが改名前のエントリを復元するためで、
`./gradlew :cli:installCli --rerun-tasks` で解消する。
`install-cli.sh` は `Sync` で管理される `cli/build/install/` からコピーするので影響は受けない。

## ビルドが途中で失敗する / デーモンが落ちる

Gradle デーモンは `org.gradle.jvmargs` を指定していないと `-Xmx512m` で動く。
Kotlin/Native を含むこのビルドでは足りずに落ちることがあり、
リポジトリのルートに `java_pid*.hprof`（ヒープダンプ）が残る。

再発するなら `gradle.properties` に足す。

```properties
org.gradle.jvmargs=-Xmx2g
```

なお `~/.gradle/gradle.properties` はプロジェクトの `gradle.properties` を
上書きする優先順位にあるので、ここには書かない。
