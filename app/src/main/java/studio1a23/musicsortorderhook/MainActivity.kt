package studio1a23.musicsortorderhook

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException


fun Boolean.toInt() = if (this) 1 else 0


class MainActivity : AppCompatActivity() {

    /* Shell.Config methods shall be called before any shell is created
     * This is the why in this example we call it in a static block
     * The followings are some examples, check Javadoc for more details */
    companion object {
        const val TAG = "MAIN_ACTIVITY"
        const val MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1

        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10))
        }
    }

    private val viewModel by viewModels<MainActivityViewModel>()

    private val rationaleDialog by lazy {
        AlertDialog.Builder(this).apply {
            setMessage(R.string.request_file_access_reason)
            setPositiveButton(R.string.dialog_btn_ok) { dialog, _ -> dialog.dismiss() }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        progressBar.min = 0



        viewModel.isRunning.observe(this, {
            updateDatabaseButton.isEnabled = !it
            if (it) {
                statusText.text = ""
                progressBar.progress = 0
            }
        })


        viewModel.totalFiles.observe(this, {
            GlobalScope.launch(Dispatchers.Main) {
                progressBar.max = it
            }
        })
        val updateStatsObserver = Observer<Any> {
            val statsStringId: Int = if (viewModel.isRunning.value == true) {
                R.string.stats_in_progress
            } else {
                R.string.stats_finished
            }

            GlobalScope.launch(Dispatchers.Main) {
                statusText.text = getString(
                    statsStringId,
                    viewModel.processedFiles.value,
                    viewModel.totalFiles.value,
                    viewModel.loadFailed.value,
                    viewModel.missingTitle.value,
                    viewModel.missingArtist.value,
                    viewModel.missingAlbum.value
                )
                statusText.invalidate()

                progressBar.progress = viewModel.processedFiles.value ?: 0
                progressBar.invalidate()
            }
        }
        viewModel.isRunning.observe(this, updateStatsObserver)
        viewModel.processedFiles.observe(this, updateStatsObserver)
        viewModel.loadFailed.observe(this, updateStatsObserver)
        viewModel.missingTitle.observe(this, updateStatsObserver)
        viewModel.missingAlbum.observe(this, updateStatsObserver)
        viewModel.missingArtist.observe(this, updateStatsObserver)

        viewModel.errorMessage.observe(this, {
            if (it != 0) {
                Snackbar.make(activityMainContainer, it, Snackbar.LENGTH_LONG).show()
            }
        })

    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    runUpdateDatabase()
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Snackbar.make(
                        activityMainContainer,
                        getString(R.string.permission_deinied_upon_request),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClickUpdateDatabase(view: View) {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {

            // Should we show an explanation?
            if (shouldShowRequestPermissionRationale(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            ) {
                rationaleDialog.show()
            }

            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE
            )

            return
        } else {
            runUpdateDatabase()
        }
    }

    private fun runUpdateDatabase() {
        val databaseFile = this.getDatabasePath("external.db")
        viewModel.batchUpdateDatabase(databaseFile)
    }

}


class MainActivityViewModel : ViewModel() {

    val isRunning = MutableLiveData(false)
    val hasError = MutableLiveData(false)
    val totalFiles = MutableLiveData(0)
    val processedFiles = MutableLiveData(0)
    val loadFailed = MutableLiveData(0)
    val missingTitle = MutableLiveData(0)
    val missingArtist = MutableLiveData(0)
    val missingAlbum = MutableLiveData(0)
    val errorMessage = SingleLiveEvent<Int>()

    companion object {
        const val MEDIA_STORAGE_DATABASE =
            "/data/data/com.android.providers.media/databases/external.db"
        const val MEDIA_STORAGE_DATABASE_R =
            "/data/data/com.google.android.providers.media.module/databases/external.db"
    }

    /**
     * Copy database to app directory.
     */
    private fun copyDatabase(databaseFile: File): File? {
        val database = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SuFile.open(MEDIA_STORAGE_DATABASE_R)
        } else {
            SuFile.open(MEDIA_STORAGE_DATABASE)
        }

        if (database.exists()) {
            try {
                Log.d(MainActivity.TAG, "Database path: ${databaseFile.absolutePath}")
                SuFileInputStream(database).use { inFile ->
                    SuFileOutputStream(databaseFile.absolutePath).use { outFile ->
                        inFile.copyTo(outFile)
                    }
                }

                val dbf = SuFile.open(databaseFile.absolutePath)
                Log.d(
                    MainActivity.TAG,
                    "Size of new file after copy: sufile = ${dbf.length()}; passed in = ${databaseFile.length()}; original = ${database.length()}"
                )

                return databaseFile
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            Log.d(MainActivity.TAG, "${database.absolutePath} does not exist.")
        }

        return null
    }

    /**
     * Copy edited database back.
     */
    private fun overwriteDatabase(databaseFile: File) {
        viewModelScope.launch {
            val database = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SuFile.open(MEDIA_STORAGE_DATABASE_R)
            } else {
                SuFile.open(MEDIA_STORAGE_DATABASE)
            }

            val currentUser = ShellUtils.fastCmd("stat -c '%U' ${database.absolutePath}")
            val currentGroup = ShellUtils.fastCmd("stat -c '%G' ${database.absolutePath}")
            val currentPermission = ShellUtils.fastCmd("stat -c '%a' ${database.absolutePath}")

            if (database.exists()) {
                try {
                    Log.d(MainActivity.TAG, "Database path: ${databaseFile.absolutePath}")
                    SuFileInputStream(databaseFile.path).use { inFile ->
                        SuFileOutputStream(database).use { outFile ->
                            inFile.copyTo(outFile)
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            ShellUtils.fastCmd("chmod $currentPermission ${database.absolutePath}")
            ShellUtils.fastCmd("chown $currentUser:$currentGroup ${database.absolutePath}")
        }
    }

    private fun loadDatabase(databaseFile: File): SQLiteDatabase? {
        val database = copyDatabase(databaseFile) ?: return null
        val openParams = SQLiteDatabase.OpenParams.Builder().build()

        val db = SQLiteDatabase.openDatabase(database, openParams)

        // Add dummy functions used in Android 11
        // Ref: https://cs.android.com/android/platform/superproject/+/master:packages/providers/MediaProvider/src/com/android/providers/media/DatabaseHelper.java;l=271;bpv=1;bpt=1?q=files_insert

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            db.setCustomScalarFunction("_UPDATE") {
                null
            }
        }

        return db
    }

    private fun updateSortTags(databaseFile: File) {
        isRunning.postValue(true)
        hasError.postValue(false)
        totalFiles.postValue(0)
        processedFiles.postValue(0)
        missingAlbum.postValue(0)
        missingTitle.postValue(0)
        missingArtist.postValue(0)
        loadFailed.postValue(0)
        errorMessage.postValue(0)

        try {
            val database = loadDatabase(databaseFile)
            if (database == null) {
                errorMessage.postValue(R.string.failed_to_load_database)
                return
            }

            val cursor = database.query(
                "files",
                arrayOf("_id", "_data", "artist_id", "album_id"),
                "is_music = 1",
                null,
                null,
                null,
                null
            )

            Log.d(MainActivity.TAG, "Number of music files: ${cursor.count}")

            totalFiles.postValue(cursor.count)

            cursor.moveToFirst()
            if (cursor.count > 0) {
                do {
                    val id = cursor.getInt(0)
                    val filePath = cursor.getString(1)
                    val artistId = if (cursor.isNull(2)) null else cursor.getInt(2)
                    val albumId = if (cursor.isNull(3)) null else cursor.getInt(3)
                    val sortKeys = loadSortKeys(filePath)
                    if (sortKeys != null) {
                        val feedback = updateDatabase(database, id, artistId, albumId, sortKeys)
                        missingTitle.postValue(missingTitle.value?.plus((!feedback.title).toInt()))
                        missingArtist.postValue(missingArtist.value?.plus((!feedback.artist).toInt()))
                        missingAlbum.postValue(missingAlbum.value?.plus((!feedback.album).toInt()))
                    } else {
                        loadFailed.postValue(loadFailed.value?.plus(1))
                    }
                    processedFiles.postValue(processedFiles.value?.plus(1))
                } while (cursor.moveToNext())
            }

            cursor.close()
            database.close()

        } catch (e: Exception) {
            Log.e(TAG, "Error while updating sort tags", e)
            hasError.postValue(true)
        } finally {
            isRunning.postValue(false)
        }
    }

    fun batchUpdateDatabase(databaseFile: File) {
        GlobalScope.launch(Dispatchers.IO) {
            updateSortTags(databaseFile)
            Log.d(MainActivity.TAG, "Database path: ${databaseFile.absolutePath}")
            overwriteDatabase(databaseFile)
        }
    }
}