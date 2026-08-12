# WebAPI 仕様

sse-server の HTTP インターフェース。これが唯一の操作経路で、
[`ssectl`](cli.md) も Web コンソールもここを叩いている。

- 起動と設定 → [README](../README.md)
- CLI → [docs/cli.md](cli.md)

## curl で直接叩く

```sh
curl -X POST localhost:8080/publish -H 'Content-Type: application/json' \
  -d '{"event":"progress","data":"50%"}'

curl -X POST localhost:8080/scenario -H 'Content-Type: application/json' \
  -d @scenarios/demo.json

curl -X POST localhost:8080/disconnect

# event 名固定・id 自動採番でよければ、プレーンテキストの簡易版もある
curl -X POST localhost:8080/broadcast -d 'hello sse'
```

受信側を眺めるだけなら `curl -N http://localhost:8080/events`。

## エンドポイント一覧

| メソッド | パス            | 説明                                       |
| -------- | --------------- | ------------------------------------------ |
| GET      | `/`             | 送信コンソール (HTML)                      |
| GET      | `/events`       | SSE ストリーム                             |
| POST     | `/publish`      | フレームの各フィールドを指定して配信する   |
| POST     | `/scenario`     | 複数ステップをまとめて再生する             |
| POST     | `/disconnect`   | 接続中のストリームをサーバー側から切断する |
| POST     | `/broadcast`    | ボディをそのまま配信する簡易版             |
| GET      | `/stats`        | 接続数と最後に採番したイベント ID          |
| GET      | `/health`       | ヘルスチェック                             |

未定義のパスは `404`。定義済みパスに非対応のメソッドで来た場合も（`GET /publish` など）
ルーティングに一致しないため `404` になる。
認証・認可は無し。CORS は既定で全許可（[README の設定](../README.md#cors) 参照）。

全レスポンスに `X-Sse-Mock: sse-server` ヘッダが付く。ポートを別のプロセスに
取られていた場合に、応答しているのが sse-server かどうかを見分けるための目印
（`ssectl` はこれを見てエラーメッセージを出し分ける）。この目印は WebAPI の一部として
`shared` に定義してある。

---

## GET /events — SSE ストリーム

`200` で以下のヘッダを返し、接続は開いたままになる。

```
Content-Type: text/event-stream
Cache-Control: no-store
Connection: keep-alive
X-Accel-Buffering: no
Transfer-Encoding: chunked
```

流れるフレームは 3 種類。

**1. 接続確立の通知** — 接続直後に 1 度だけ。`id` は付かない。

```
event: open
data: connected

```

**2. キープアライブ** — コメント行。接続直後と、以降 15 秒ごと。
プロキシやモバイル回線にアイドル接続を切られるのを防ぐためのもので、
クライアント側では無視される（`EventSource` はイベントとして扱わない）。

```
: keep-alive

```

**3. 送信したフレーム** — `/publish`・`/scenario`・`/broadcast` の内容。

```
event: message
data: hello sse
id: 1

```

サーバー側から切断すると（`POST /disconnect` またはシナリオの `disconnect` ステップ）、
ストリームはその場で閉じられる。クライアントから見ると通常の切断と区別がつかない。

## POST /publish

ボディは JSON。

```jsonc
{
  "event": "message",   // 省略 → "message"、"" → event 行を送らない
  "data": "hello",      // 改行を含めるとその分 data 行が増える
  "id": "custom-1",     // 省略 → 自動採番、"" → id 行を送らない
  "retry": 5000,        // 省略 → retry 行を送らない
  "comment": "note",    // コメント行 (`: note`)
  "repeat": 3,          // 送信回数 (既定 1)
  "intervalMs": 500,    // 2 回目以降の間隔
  "delayMs": 1000,      // 送信を始めるまでの待ち時間
  "disconnect": false   // true なら送信せず全接続を切断する
}
```

成功時は `202 {"scheduled": <送信予定数>, "subscribers": <その時点の接続数>}`。

`delayMs` や `repeat` を伴う送信はレスポンスを待たせずバックグラウンドで進むので、
`scheduled` は「これから送る本数」であって「送り終えた本数」ではない。

エラー:

| 条件                                             | レスポンス                                                     |
| ------------------------------------------------ | -------------------------------------------------------------- |
| `data`・`comment`・`retry` がどれも無い           | `400 {"error":"one of data, comment or retry is required"}`     |
| `repeat` が 1 未満                               | `400 {"error":"repeat must be >= 1"}`                           |
| `intervalMs` / `delayMs` が負                    | `400 {"error":"intervalMs and delayMs must be >= 0"}`           |
| JSON が壊れている / 知らないキーがある           | `400 {"error":"<パーサのメッセージ>"}`                          |

`disconnect: true` のときは `data` などの指定は不要になるが、
`repeat` と `intervalMs` / `delayMs` の範囲チェックは同じように効く。

## POST /scenario

`/publish` と同じ形のステップを並べて順に再生する。各ステップの `delayMs` は
「そのステップを送る前の待ち時間」として効く。

```jsonc
{
  "name": "demo",
  "steps": [
    { "event": "status", "data": "処理を開始しました" },
    { "delayMs": 500, "event": "progress", "data": "50%" },
    { "delayMs": 500, "comment": "まだ処理中" },
    { "delayMs": 1000, "disconnect": true }
  ]
}
```

- 成功時は `202 {"name": ..., "steps": <ステップ数>, "subscribers": <接続数>}` を返し、
  再生はバックグラウンドで進む。
- ステップが空なら `400 {"error":"steps is empty"}`。
- 個々のステップが不正なら `400 {"error":"steps[1]: repeat must be >= 1"}` のように
  何番目が悪いかを返す。
- サンプルは [`scenarios/demo.json`](../scenarios/demo.json)。

## POST /disconnect

接続中の全ストリームをサーバー側から閉じる。ボディは不要。
`202 {"disconnected": <切断したセッション数>}` を返す。

## POST /broadcast

リクエストボディ（プレーンテキスト）をそのまま `data` として配信する簡易版。
`event: message` 固定、`id` は自動採番。

| 条件                       | ステータス | ボディ                                     |
| -------------------------- | ---------- | ------------------------------------------ |
| 成功                       | `202`      | `published id=<採番> subscribers=<接続数>` |
| ボディが空、または空白のみ | `400`      | `body is empty`                            |

`Content-Type` は見ていない。ボディは UTF-8 として解釈される。

## GET /stats

`200 {"subscribers": <接続数>, "lastEventId": <最後に自動採番した ID>}`。
まだ採番していなければ `lastEventId` は 0。

## GET /health

常に `200` で本文 `OK`。プロセスが生きているかだけを見る単純なもの。
Docker の HEALTHCHECK もこれを見ている。

---

## フレームの組み立てルール

`/publish` と `/scenario` の各ステップで共通。

| フィールド | 省略したとき         | 空文字を渡したとき | 明示したとき     |
| ---------- | -------------------- | ------------------ | ---------------- |
| `event`    | `event: message`     | event 行を送らない | その名前を送る   |
| `id`       | 自動採番した連番     | id 行を送らない    | その値をそのまま |
| `retry`    | retry 行を送らない   | —                  | その値を送る     |
| `comment`  | コメント行を送らない | —                  | `: <値>` を送る  |

- `data` に改行を含めると、SSE の仕様どおり 1 行ごとに `data:` へ分割される。

  ```sh
  ssectl send --data=$'line1\nline2'
  # -> event: message / data: line1 / data: line2 / id: N
  ```

- **`data` が無いフレームはコメント / retry のみ**として送られ、`event` と `id` は付かない。
  data が空のイベントは SSE の仕様上クライアントで破棄されるため、意味を持たせていない。
  結果として、コメントだけのフレームはイベント ID を消費しない。

## イベント ID の採番

- サーバープロセス内の単一カウンタで、`id` を省略した送信ごとに 1 から連番で払い出す。
- クライアントごとではなく**サーバー全体で共通**。接続数が 0 のときの送信でも消費される。
- `id` を明示した送信、およびコメント / retry のみのフレームでは消費しない。
- プロセスを再起動すると 1 に戻る。現在値は `GET /stats` で見られる。

## 配信の性質（重要な制約）

- **リプレイ無し。** イベントは保持されず、その瞬間に接続しているクライアントにだけ届く。
  接続数が 0 のときの送信も `202` を返すが、誰にも届かず捨てられる。
- **`Last-Event-ID` は未対応。** 再接続時に取りこぼしを埋める仕組みは無い。
- **バッファ溢れ時は古いイベントを捨てる。** 配信ハブは 128 件のバッファを持ち、
  読み取りが遅いクライアントがいてもサーバー全体は詰まらせず、
  そのクライアント向けの古いイベントから破棄する
  （`MutableSharedFlow` + `BufferOverflow.DROP_OLDEST`）。
  同じ理由で、極端に混んでいる状況では `/disconnect` の切断シグナルも落ちうる。
- **永続化・クラスタ対応無し。** 状態はプロセス内のみ。複数インスタンスに分散すると、
  あるインスタンスへの送信は別インスタンスの接続には届かない。

モックとして割り切った設計なので、本番用の SSE 実装の参考にはしないこと。
