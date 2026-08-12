package com.example.sse.cli

import com.example.sse.api.EventRequest
import com.example.sse.api.ScenarioRequest
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long

class Send : SseSubcommand("send") {

    override fun help(context: Context) = "イベントを 1 件 (または連続で) 送信する"

    private val data by option("--data", help = "送信する data。改行を含めると data 行が複数になる")
    private val dataFile by option("--data-file", help = "ファイルの内容を data として送る")
    private val event by option(
        "--event",
        help = "event 名 (既定: message、--event= と空にすると event 行なし)",
    ).default("message")
    private val id by option("--id", help = "id (省略時は自動採番、--id= と空にすると id 行なし)")
    private val retry by option("--retry", help = "retry フィールド (ms)").long()
    private val comment by option("--comment", help = "コメント行を併せて送る")
    private val repeat by option("--repeat", help = "送信回数").int().default(1)
    private val interval by option("--interval", help = "連続送信の間隔 (ms)").long().default(0)
    private val delay by option("--delay", help = "送信開始までの待ち時間 (ms)").long().default(0)

    override suspend fun execute(api: SseApi) {
        if (data != null && dataFile != null) {
            usageError("--data と --data-file は同時に指定できません")
        }

        val request = EventRequest(
            event = event,
            data = data ?: dataFile?.let { readTextFile(it) },
            id = id,
            retry = retry,
            comment = comment,
            repeat = repeat,
            intervalMs = interval,
            delayMs = delay,
        )
        // 壊れたリクエストはサーバーへ投げる前に弾く
        request.validate()?.let { usageError(it) }

        val result = api.publish(request)
        echo("scheduled=${result.scheduled} subscribers=${result.subscribers}")
    }
}

class Comment : SseSubcommand("comment") {

    override fun help(context: Context) = "コメントフレームを送信する"

    private val text by argument("text", help = "コメント本文")

    override suspend fun execute(api: SseApi) {
        val result = api.publish(EventRequest(event = null, comment = text))
        echo("scheduled=${result.scheduled} subscribers=${result.subscribers}")
    }
}

class Scenario : SseSubcommand("scenario") {

    override fun help(context: Context) = "JSON で定義したシナリオを再生する"

    private val file by argument("file", help = "シナリオ JSON のパス")

    override suspend fun execute(api: SseApi) {
        val parsed = runCatching { json.decodeFromString<ScenarioRequest>(readTextFile(file)) }
            .getOrElse { usageError("$file を読めません: ${it.message}") }

        if (parsed.steps.isEmpty()) usageError("$file に steps がありません")
        parsed.steps.forEachIndexed { index, step ->
            step.validate()?.let { usageError("steps[$index]: $it") }
        }

        val result = api.scenario(parsed)
        echo("name=${result.name} steps=${result.steps} subscribers=${result.subscribers}")
    }
}

class Disconnect : SseSubcommand("disconnect") {

    override fun help(context: Context) = "接続中のストリームを全て切断する"

    override suspend fun execute(api: SseApi) {
        echo("disconnected=${api.disconnect().disconnected}")
    }
}

class Stats : SseSubcommand("stats") {

    override fun help(context: Context) = "接続数と最後のイベント ID を表示する"

    override suspend fun execute(api: SseApi) {
        val stats = api.stats()
        echo("subscribers=${stats.subscribers} lastEventId=${stats.lastEventId}")
    }
}

class Watch : SseSubcommand("watch") {

    override fun help(context: Context) = "ストリームを購読して受信内容をそのまま表示する"

    override suspend fun execute(api: SseApi) {
        echo("watching $base/events ... (Ctrl-C で終了)", err = true)
        // 空行はフレームの区切り。生の行を見たいツールなので加工しない。
        api.watch { line -> echo(if (line.isEmpty()) "--" else line) }
    }
}
