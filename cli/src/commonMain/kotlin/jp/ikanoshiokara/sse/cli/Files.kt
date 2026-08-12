package jp.ikanoshiokara.sse.cli

import com.github.ajalt.clikt.core.CliktError
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * ファイルを UTF-8 で読む。無ければ CLI のエラーにする。
 *
 * パスの間違いに Usage 行は役に立たないので、UsageError ではなく CliktError。
 */
internal fun readTextFile(path: String): String {
    val file = Path(path)
    if (SystemFileSystem.metadataOrNull(file)?.isRegularFile != true) {
        throw CliktError("ファイルが見つかりません: $path")
    }
    return SystemFileSystem.source(file).buffered().use { it.readString() }
}
