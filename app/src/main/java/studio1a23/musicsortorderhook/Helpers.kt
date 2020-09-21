package studio1a23.musicsortorderhook

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.tag.FieldKey
import java.io.File
import androidx.annotation.MainThread
import androidx.annotation.Nullable
import androidx.lifecycle.*
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException
import java.lang.Exception
import java.util.concurrent.atomic.AtomicBoolean


const val TAG = "HELPER_FN"

data class SortKeys(val title: String?, val artist: String?, val album: String?)

fun loadSortKeys(path: String): SortKeys? {
    try {
        val file = File(path)
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tag ?: return null
        var titleKey: String? = tag.getFirst(FieldKey.TITLE_SORT)
        var artistKey: String? = tag.getFirst(FieldKey.ARTIST_SORT)
        if (artistKey?.isBlank() == true) {
            artistKey = tag.getFirst(FieldKey.ALBUM_ARTIST_SORT)
        }
        if (artistKey?.isBlank() == true) {
            artistKey = tag.getFirst(FieldKey.COMPOSER_SORT)
        }
        var albumKey: String? = tag.getFirst(FieldKey.ALBUM_SORT)
        if (titleKey?.isBlank() == true) {
            titleKey = null
        }
        if (artistKey?.isBlank() == true) {
            artistKey = null
        }
        if (albumKey?.isBlank() == true) {
            albumKey = null
        }
        return SortKeys(title = titleKey, artist = artistKey, album = albumKey)
    } catch (e: CannotReadException) {
        return null
    } catch (e: InvalidAudioFrameException) {
        return null
    }
}

data class UpdateDatabaseFeedback(
    var title: Boolean = false, var artist: Boolean = false, var album: Boolean = false)

fun updateDatabase(db: SQLiteDatabase, id: Int,
                   albumId: Int?, artistId: Int?,
                   sortKeys: SortKeys): UpdateDatabaseFeedback {

    db.beginTransaction()
    val feedback = UpdateDatabaseFeedback()
    try {
        if (sortKeys.title != null) {
            // You yourself are still using this deprecated method, so don’t blame me.
            // Ref: https://cs.android.com/android/platform/superproject/+/master:packages/providers/MediaProvider/src/com/android/providers/media/MediaProvider.java;l=1207?q=artist_key%20keyfor
            val collatedTitle = MediaStore.Audio.keyFor(sortKeys.title)
            val updateTitle = ContentValues().apply {
                put("title_key", collatedTitle)
            }
            db.update("files", updateTitle, "_id = ?", arrayOf(id.toString()))
            feedback.title = true
        }
        if (albumId != null && sortKeys.album != null) {
            // You yourself are still using this deprecated method, so don’t blame me.
            // Ref: https://cs.android.com/android/platform/superproject/+/master:packages/providers/MediaProvider/src/com/android/providers/media/MediaProvider.java;l=1207?q=artist_key%20keyfor
            val collatedAlbum = MediaStore.Audio.keyFor(sortKeys.album)
            val updateAlbum = ContentValues().apply {
                put("album_key", collatedAlbum)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    db.update("files", updateAlbum, "_id = ?", arrayOf(id.toString()))
                } else {
                    db.update("albums", updateAlbum, "album_id = ?", arrayOf(albumId.toString()))
                }
                feedback.album = true
            } catch (e: SQLiteConstraintException) {
                Log.e(TAG, "Error on updating album key: unique constraint [${sortKeys.album}]")
            } catch (e: Exception) {
                Log.e(TAG, "Error on updating album key", e)
            }
        }
        if (artistId != null && sortKeys.artist != null) {
            // You yourself are still using this deprecated method, so don’t blame me.
            // Ref: https://cs.android.com/android/platform/superproject/+/master:packages/providers/MediaProvider/src/com/android/providers/media/MediaProvider.java;l=1207?q=artist_key%20keyfor
            val collatedArtist = MediaStore.Audio.keyFor(sortKeys.artist)
            val updateArtist = ContentValues().apply {
                put("artist_key", collatedArtist)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    db.update("files", updateArtist, "_id = ?", arrayOf(id.toString()))
                } else {
                    db.update("artists", updateArtist, "artist_id = ?", arrayOf(artistId.toString()))
                }
                feedback.artist = true
            } catch (e: SQLiteConstraintException) {
                Log.e(TAG, "Error on updating artist key: unique constraint [${sortKeys.artist}]")
            } catch (e: Exception) {
                Log.e(TAG, "Error on updating artist key", e)
            }
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
    return feedback
}

/**
 * A lifecycle-aware observable that sends only new updates after subscription, used for events like
 * navigation and Snackbar messages.
 *
 *
 * This avoids a common problem with events: on configuration change (like rotation) an update
 * can be emitted if the observer is active. This LiveData only calls the observable if there's an
 * explicit call to setValue() or call().
 *
 *
 * Note that only one observer is going to be notified of changes.
 */
class SingleLiveEvent<T> : MutableLiveData<T>() {

    private val mPending = AtomicBoolean(false)


    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {

        if (hasActiveObservers()) {
            Log.w(TAG, "Multiple observers registered but only one will be notified of changes.")
        }

        // Observe the internal MutableLiveData
        super.observe(owner, { t ->
            if (mPending.compareAndSet(true, false)) {
                observer.onChanged(t)
            }
        })
    }

    @MainThread
    override fun setValue(@Nullable t: T?) {
        mPending.set(true)
        super.setValue(t)
    }

    /**
     * Used for cases where T is Void, to make calls cleaner.
     */
    @MainThread
    fun call() {
        value = null
    }

    companion object {

        private const val TAG = "SingleLiveEvent"
    }
}