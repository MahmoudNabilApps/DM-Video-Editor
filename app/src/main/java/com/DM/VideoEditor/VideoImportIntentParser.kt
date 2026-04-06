package com.DM.VideoEditor

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Resolves video URIs from external intents ([Intent.ACTION_SEND] / [Intent.ACTION_SEND_MULTIPLE])
 * and from in-app navigation. Order from [Intent.EXTRA_STREAM] lists is preserved.
 */
object VideoImportIntentParser {

    /**
     * Returns video URIs from a share / view intent, or empty if none.
     */
    fun extractMediaUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = getSingleStreamExtra(intent) ?: return emptyList()
                listOf(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = getMultipleStreamExtras(intent) ?: return emptyList()
                list.filterNotNull()
            }
            else -> emptyList()
        }
    }

    private fun getSingleStreamExtra(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun getMultipleStreamExtras(intent: Intent): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
    }

    /**
     * Best-effort persistable read permission for content URIs (gallery / share).
     */
    fun takePersistableReadPermissions(resolver: ContentResolver, uris: Iterable<Uri>) {
        for (uri in uris) {
            try {
                if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            } catch (_: SecurityException) {
                // Share intent may not grant persistable; one-shot read may still work.
            }
        }
    }
}
