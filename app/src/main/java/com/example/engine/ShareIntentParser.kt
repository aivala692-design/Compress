package com.example.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

data class ParsedSharePayload(
  val uris: List<Uri> = emptyList(),
  val captionText: String? = null,
  val hasReadPermissionFlag: Boolean = false,
  val hasWritePermissionFlag: Boolean = false,
  val hasPersistableFlag: Boolean = false,
  val action: String? = null,
) {
  val isEmpty: Boolean
    get() = uris.isEmpty()
}

object ShareIntentParser {

  private const val TAG = "CompressXShare"

  fun isShareIntent(intent: Intent?): Boolean {
    val action = intent?.action ?: return false
    return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
  }

  fun parse(context: Context, intent: Intent?): ParsedSharePayload {
    if (intent == null || !isShareIntent(intent)) {
      return ParsedSharePayload()
    }

    val action = intent.action
    val flags = intent.flags
    val hasReadFlag = (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
    val hasWriteFlag = (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
    val hasPersistableFlag = (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0

    val collected = LinkedHashSet<Uri>()

    when (action) {
      Intent.ACTION_SEND -> {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
          collected.add(it)
        }
      }
      Intent.ACTION_SEND_MULTIPLE -> {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
          ?.forEach { uri -> collected.add(uri) }
      }
    }

    // Many Android Share Sheet providers (Files, Gallery, Photos) deliver URIs via ClipData
    val clipData = intent.clipData
    if (clipData != null) {
      for (i in 0 until clipData.itemCount) {
        val itemUri = clipData.getItemAt(i)?.uri
        if (itemUri != null) {
          collected.add(itemUri)
        }
      }
    }

    // Fallback to intent.data if a provider placed a content URI there
    val dataUri = intent.data
    if (collected.isEmpty() && dataUri != null && (dataUri.scheme == "content" || dataUri.scheme == "file")) {
      collected.add(dataUri)
    }

    val rawExtraText =
      intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    var captionText = rawExtraText

    if (collected.isEmpty() && rawExtraText != null && rawExtraText.startsWith("content://", ignoreCase = true)) {
      runCatching { Uri.parse(rawExtraText) }
        .getOrNull()
        ?.takeIf { !it.authority.isNullOrBlank() }
        ?.let {
          collected.add(it)
          captionText = null
        }
    }

    // Retain persistable permissions whenever offered by the source provider
    val grantModes =
      when {
        hasReadFlag && hasWriteFlag ->
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        else -> Intent.FLAG_GRANT_READ_URI_PERMISSION
      }

    for (uri in collected) {
      if (hasPersistableFlag) {
        runCatching {
          context.contentResolver.takePersistableUriPermission(uri, grantModes)
        }.onFailure {
          if (hasWriteFlag) {
            runCatching {
              context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
              )
            }
          }
        }
      }
    }

    CompressionLogger.i(
      TAG,
      "Parsed share intent action=$action uris=${collected.size} readGrant=$hasReadFlag writeGrant=$hasWriteFlag persistable=$hasPersistableFlag",
    )

    return ParsedSharePayload(
      uris = collected.toList(),
      captionText = captionText,
      hasReadPermissionFlag = hasReadFlag,
      hasWritePermissionFlag = hasWriteFlag,
      hasPersistableFlag = hasPersistableFlag,
      action = action,
    )
  }
}
