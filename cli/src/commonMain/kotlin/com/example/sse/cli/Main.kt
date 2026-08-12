package com.example.sse.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking

internal const val DEFAULT_BASE = "http://localhost:8080"

/** ルートコマンドからサブコマンドへ `--base` を引き継ぐための入れ物。 */
internal class InheritedBase(var value: String = DEFAULT_BASE)

fun main(args: Array<String>) =
    SseCommand()
        .subcommands(Send(), Comment(), Scenario(), Disconnect(), Stats(), Watch())
        .main(args)

class SseCommand : CliktCommand(name = "ssectl") {

    override fun help(context: Context) = "SSE モックサーバー (sse-server) へイベントを送る"

    // ヘルプは Mordant が折り返すので、1 行ずつ空行で区切って段落にしている。
    // 1 行にまとめると全部つながって読めなくなる。
    override fun helpEpilog(context: Context) = listOf(
        "例:",
        "ssectl send --data='hello'",
        "ssectl send --event=progress --data='50%' --repeat=5 --interval=500",
        "ssectl send --id= --data='id なしのイベント'",
        "ssectl send --retry=1000 --data='再接続間隔を 1 秒に指示'",
        "ssectl scenario scenarios/demo.json",
        "ssectl watch",
    ).joinToString("\n\n")

    private val inherited by findOrSetObject { InheritedBase() }

    private val base by option(
        "--base",
        envvar = "SSE_BASE_URL",
        help = "サーバーのベース URL",
    ).default(DEFAULT_BASE)

    override fun run() {
        // 各サブコマンドが自分の --base を持たない場合のフォールバックとして渡す
        inherited.value = base.trimEnd('/')
    }
}

/**
 * WebAPI を 1 回叩いて終わるサブコマンドの共通部分。
 *
 * `--base` はここにも生やしてあるので、`sse --base=... stats` と
 * `sse stats --base=...` のどちらでも書ける。
 */
abstract class SseSubcommand(name: String) : CliktCommand(name) {

    private val baseOverride by option("--base", help = "サーバーのベース URL")
    private val inherited by findOrSetObject { InheritedBase() }

    /** サブコマンドの指定 → ルートの指定 / 環境変数 → 既定 の順で決まる。 */
    protected val base: String
        get() = baseOverride?.trimEnd('/') ?: inherited.value

    final override fun run() {
        SseApi(base).use { api -> runBlocking { execute(api) } }
    }

    protected abstract suspend fun execute(api: SseApi)

    /**
     * 使い方の誤りとして終了する。
     *
     * `UsageError` をそのまま投げるとコンテキストが埋まらず、ルートコマンドの
     * Usage 行が出てしまうので、自分のコンテキストを明示する。
     */
    protected fun usageError(message: String): Nothing =
        throw UsageError(message).also { it.context = currentContext }
}
