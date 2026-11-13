package com.serkantkn.synapse.data.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.serkantkn.synapse.data.network.socket.TcpTransferManager
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Contacts, photos and received files the iPhone app can browse. The list formats match what
 * the iOS app sends for its own data (DataManager.swift), so both sides read each other's lists.
 */
class SharedDataProvider(private val context: Context) {

    companion object {
        private const val TAG = "SharedData"
        private const val PHOTO_LIMIT = 50
        /** Folder (under Downloads) where Synapse saves received files; the only one shared back. */
        const val SHARED_FOLDER = "Synapse"

        /**
         * Largest raw payload that fits one frame once Base64 (×4/3), the JSON around it and
         * encryption are added. Anything bigger would exceed the peer's 10 MB frame limit,
         * which drops the connection.
         */
        const val MAX_INLINE_BYTES = (TcpTransferManager.MAX_FRAME_SIZE - 64 * 1024) / 4 * 3
    }

    fun hasContactsPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Same shape as iOS: name, phones, emails. Null when contacts access isn't granted. */
    fun contacts(): List<Map<String, Any>>? {
        if (!hasContactsPermission()) return null
        val phones = mutableMapOf<Long, MutableList<String>>()
        val emails = mutableMapOf<Long, MutableList<String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(1) ?: continue
                phones.getOrPut(c.getLong(0)) { mutableListOf() }.add(number)
            }
        }
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                emails.getOrPut(c.getLong(0)) { mutableListOf() }.add(address)
            }
        }
        val result = mutableListOf<Map<String, Any>>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1)?.trim().orEmpty()
                if (name.isEmpty() && phones[id] == null) continue
                result += mapOf(
                    "name" to name,
                    "phones" to (phones[id] ?: emptyList<String>()),
                    "emails" to (emails[id] ?: emptyList<String>()),
                )
            }
        }
        Log.d(TAG, "📇 ${result.size} contacts")
        return result
    }

    /** Newest photos, same shape as iOS: id, width, height, creationDate (ms), mediaType. */
    fun photos(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
            ),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext() && result.size < PHOTO_LIMIT) {
                val taken = c.getLong(3)
                result += mapOf(
                    "id" to c.getLong(0).toString(),
                    "width" to c.getInt(1),
                    "height" to c.getInt(2),
                    "creationDate" to if (taken > 0) taken else c.getLong(4) * 1000,
                    "mediaType" to "image",
                )
            }
        }
        return result
    }

    /** Files Synapse received (Downloads/Synapse), same shape as iOS: name, path, size, creationDate. */
    fun files(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_ADDED,
                ),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(sharedRelativePath()),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    result += mapOf(
                        "name" to (c.getString(1) ?: "—"),
                        "path" to ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)).toString(),
                        "size" to c.getLong(2),
                        "creationDate" to c.getLong(3) * 1000,
                    )
                }
            }
        } else {
            legacyFolder().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { file ->
                result += mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "size" to file.length(),
                    "creationDate" to file.lastModified(),
                )
            }
        }
        return result
    }

    sealed interface FileResult {
        data class Data(val name: String, val bytes: ByteArray) : FileResult
        data class TooLarge(val name: String, val size: Long) : FileResult
        data object NotFound : FileResult
    }

    /**
     * Reads a file from [files]. The peer-supplied path must name a file in the shared folder;
     * anything else (other downloads, "..", other apps' files) is refused.
     */
    fun file(path: String): FileResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return FileResult.NotFound
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return FileResult.NotFound
            val expected = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            if (uri != expected) return FileResult.NotFound
            val row = context.contentResolver.query(
                expected,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.RELATIVE_PATH),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) Triple(c.getString(0) ?: "dosya", c.getLong(1), c.getString(2)) else null }
                ?: return FileResult.NotFound
            if (row.third != sharedRelativePath()) return FileResult.NotFound
            if (row.second > MAX_INLINE_BYTES) return FileResult.TooLarge(row.first, row.second)
            val bytes = context.contentResolver.openInputStream(expected)?.use { it.readBytes() } ?: return FileResult.NotFound
            return if (bytes.size > MAX_INLINE_BYTES) FileResult.TooLarge(row.first, bytes.size.toLong())
                else FileResult.Data(row.first, bytes)
        }
        val folder = legacyFolder().canonicalFile
        val file = File(path).canonicalFile
        if (file.parentFile != folder || !file.isFile) return FileResult.NotFound
        if (file.length() > MAX_INLINE_BYTES) return FileResult.TooLarge(file.name, file.length())
        return FileResult.Data(file.name, file.readBytes())
    }

    /**
     * The photo as a JPEG small enough for one frame. Full resolution when it fits; otherwise
     * scaled down step by step (a 50 MP photo at JPEG 85 is often well over the limit).
     */
    fun photoForTransfer(uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode at most ~8K on the long edge so a huge image can't exhaust memory.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 8192) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        try {
            repeat(6) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                if (out.size() <= MAX_INLINE_BYTES) return out.toByteArray()
                // JPEG size scales roughly with pixel count: shrink the area to fit, with margin.
                val scale = kotlin.math.sqrt(MAX_INLINE_BYTES.toDouble() / out.size()) * 0.9
                val smaller = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun sharedRelativePath() = "${Environment.DIRECTORY_DOWNLOADS}/$SHARED_FOLDER/"

    @Suppress("DEPRECATION")
    private fun legacyFolder() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SHARED_FOLDER)
}
