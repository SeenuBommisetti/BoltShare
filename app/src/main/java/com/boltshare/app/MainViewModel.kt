package com.boltshare.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import android.webkit.MimeTypeMap
import java.util.concurrent.TimeUnit

data class LocalDownloadState(
    val progress: Int = 0,
    val status: String = "downloading", // "downloading", "completed", "failed"
    val localUri: android.net.Uri? = null
)

data class TransferRecord(
    val transferId: String = "",
    val senderId: String = "",
    val receiverCode: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val progress: Int = 0,
    val status: String = "", // "uploading", "sent", "failed"
    val fileUrl: String? = null,
    val timestamp: Long = 0L
)

class MainViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val _myCode = MutableStateFlow<String?>(null)
    val myCode: StateFlow<String?> = _myCode.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)

    // Sender UI State
    val receiverCode = MutableStateFlow("")

    private val _selectedFileUri = MutableStateFlow<android.net.Uri?>(null)
    val selectedFileUri: StateFlow<android.net.Uri?> = _selectedFileUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _selectedFileSize = MutableStateFlow<Long?>(null)
    val selectedFileSize: StateFlow<Long?> = _selectedFileSize.asStateFlow()

    // Error styling
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Phase 5/6: Sender Transfer State
    private val _activeSenderTransfer = MutableStateFlow<TransferRecord?>(null)
    val activeSenderTransfer: StateFlow<TransferRecord?> = _activeSenderTransfer.asStateFlow()

    // Phase 7: Receiver Transfer State
    private val _incomingTransfers = MutableStateFlow<List<TransferRecord>>(emptyList())
    val incomingTransfers: StateFlow<List<TransferRecord>> = _incomingTransfers.asStateFlow()

    private val _localDownloads = MutableStateFlow<Map<String, LocalDownloadState>>(emptyMap())
    val localDownloads: StateFlow<Map<String, LocalDownloadState>> = _localDownloads.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.Q)
    fun initializeIdentity(context: Context) {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("boltshare_prefs", Context.MODE_PRIVATE)
            var savedId = prefs.getString("user_id", null)
            var savedCode = prefs.getString("user_code", null)

            // If no identity exists locally, we authenticate and generate one.
            if (savedId == null || savedCode == null) {
                try {
                    // Sign in anonymously to get a UID
                    val authResult = auth.signInAnonymously().await()
                    savedId = authResult.user?.uid ?: UUID.randomUUID().toString()

                    // Generate and register 6-character unique code
                    savedCode = generateAndRegisterUniqueCode(savedId)
                    
                    // Save locally via SharedPreferences
                    prefs.edit()
                        .putString("user_id", savedId)
                        .putString("user_code", savedCode)
                        .apply()
                        
                } catch (e: Exception) {
                    Log.e("Identity", "Failed to initialize identity", e)
                    return@launch
                }
            }
            
            _userId.value = savedId
            _myCode.value = savedCode
            Log.d("Identity", "Identity Setup Complete! ID: $savedId, Code: $savedCode")

            // Phase 7: Start Listening for Incoming files
            listenForIncomingTransfers(savedCode, context.applicationContext)
        }
    }

    private suspend fun generateAndRegisterUniqueCode(userId: String): String {
        // Omitting ambiguous chars like O, 0, I, 1
        val allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" 
        var uniqueCode = ""
        var isUnique = false

        while (!isUnique) {
            uniqueCode = (1..6)
                .map { allowedChars.random() }
                .joinToString("")

            try {
                // Check if code exists in Firestore by querying
                val snapshot = db.collection("users")
                    .whereEqualTo("code", uniqueCode)
                    .get()
                    .await()

                if (snapshot.isEmpty) {
                    isUnique = true
                    // Claim the code in Firestore
                    val userRecord = hashMapOf(
                        "userId" to userId,
                        "code" to uniqueCode,
                        "createdAt" to System.currentTimeMillis()
                    )
                    db.collection("users").document(userId).set(userRecord).await()
                } else {
                    Log.d("Identity", "Collision for $uniqueCode. Retrying...")
                }
            } catch (e: Exception) {
                Log.e("Identity", "Error checking code uniqueness", e)
                throw e
            }
        }
        return uniqueCode
    }

    fun updateReceiverCode(code: String) {
        receiverCode.value = code.uppercase()
    }

    fun setSelectedFile(context: Context, uri: android.net.Uri?) {
        _selectedFileUri.value = uri
        _errorMessage.value = null

        if (uri == null) {
            _selectedFileName.value = null
            _selectedFileSize.value = null
            return
        }

        try {
            // Extract File Name and Size using Document uri
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)

                if (cursor.moveToFirst()) {
                    _selectedFileName.value = if (nameIndex >= 0) cursor.getString(nameIndex) else "Unknown File"
                    _selectedFileSize.value = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L

                    // 100MB limit check (Constraint specified)
                    val sizeInMb = (_selectedFileSize.value ?: 0) / (1024 * 1024)
                    if (sizeInMb > 100) {
                        _errorMessage.value = "File exceeds Max sharing limit."
                        _selectedFileUri.value = null
                        _selectedFileName.value = null
                        _selectedFileSize.value = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FilePicker", "Error reading metadata", e)
            _errorMessage.value = "Error reading file metadata."
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // Phase 5 & 6: Validate Receiver, Create Firestore Record, Upload to Server
    fun sendFile(context: Context) {
        val rCode = receiverCode.value
        val fName = _selectedFileName.value
        val fSize = _selectedFileSize.value
        val sId = _userId.value
        val uri = _selectedFileUri.value

        if (rCode.length != 6 || fName == null || fSize == null || sId == null || uri == null) {
            _errorMessage.value = "Missing requirements to send."
            return
        }

        viewModelScope.launch {
            _errorMessage.value = null
            try {
                // 1. Verify Invalid Receiver Code Edge Case
                val userQuery = db.collection("users")
                    .whereEqualTo("code", rCode)
                    .get()
                    .await()

                if (userQuery.isEmpty) {
                    _errorMessage.value = "Invalid receiver code. No user owns this code."
                    return@launch
                }

                // 2. Create the transfer record
                val transferId = UUID.randomUUID().toString()
                var record = TransferRecord(
                    transferId = transferId,
                    senderId = sId,
                    receiverCode = rCode,
                    fileName = fName,
                    fileSize = fSize,
                    progress = 0,
                    status = "uploading",
                    timestamp = System.currentTimeMillis()
                )
                
                _activeSenderTransfer.value = record
                val docRef = db.collection("transfers").document(transferId)
                docRef.set(record).await()

                // 3. Upload File
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _errorMessage.value = "Could not open file"
                    return@launch
                }

                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val requestBody = object : RequestBody() {
                    override fun contentType() = mimeType.toMediaTypeOrNull()
                    override fun contentLength() = fSize

                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(8192)
                        var uploaded: Long = 0
                        var read: Int
                        var lastProgress = 0
                        
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            uploaded += read
                            val currentProgress = ((uploaded.toDouble() / fSize) * 100).toInt()
                            
                            if (currentProgress - lastProgress >= 2 || currentProgress == 100) {
                                lastProgress = currentProgress
                                _activeSenderTransfer.value = _activeSenderTransfer.value?.copy(progress = currentProgress)
                                
                                if (currentProgress % 10 == 0 || currentProgress == 100) {
                                    docRef.update("progress", currentProgress)
                                }
                            }
                        }
                        inputStream.close()
                    }
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fName, requestBody)
                    .build()

                val request = Request.Builder()
                    .url("https://boltshare-backend.onrender.com/upload")
                    .post(multipartBody)
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS) // No timeout for large files
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()
                
                withContext(Dispatchers.IO) {
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected code $response")
                    }
                    
                    val responseBody = response.body?.string() ?: throw IOException("Empty response")
                    val json = JSONObject(responseBody)
                    val fileUrl = json.getString("url")
                    
                    // 4. Update Firestore with Success
                    record = record.copy(progress = 100, status = "sent", fileUrl = fileUrl)
                    _activeSenderTransfer.value = record
                    docRef.update(
                        "progress", 100,
                        "status", "sent",
                        "fileUrl", fileUrl
                    ).await()
                }

                _selectedFileUri.value = null
                _selectedFileName.value = null
                _selectedFileSize.value = null
                receiverCode.value = ""

            } catch (e: Exception) {
                Log.e("Upload", "Network/Upload Failure", e)
                _errorMessage.value = "Network failed during upload: ${e.message}"
                _activeSenderTransfer.value = _activeSenderTransfer.value?.copy(status = "failed")
                _activeSenderTransfer.value?.transferId?.let { id ->
                    db.collection("transfers").document(id).update("status", "failed")
                }
            }
        }
    }

    // Phase 7: Receiver SnapshotListener
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listenForIncomingTransfers(myReceiverCode: String, context: Context) {
        db.collection("transfers")
            .whereEqualTo("receiverCode", myReceiverCode)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("Receiver", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val transfers = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(TransferRecord::class.java)
                    }.sortedByDescending { it.timestamp } // Show newest first
                    
                    _incomingTransfers.value = transfers

                    // Trigger downloads for new ready transfers
                    transfers.forEach { transfer ->
                        if (transfer.status == "sent" && !_localDownloads.value.containsKey(transfer.transferId)) {
                            downloadFile(context, transfer)
                        }
                    }
                }
            }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun downloadFile(context: Context, transfer: TransferRecord) {
        if (transfer.fileUrl == null) return

        _localDownloads.value = _localDownloads.value + (transfer.transferId to LocalDownloadState(progress = 0, status = "downloading"))

        viewModelScope.launch {
            try {
                val extension = transfer.fileName.substringAfterLast('.', "").lowercase()
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, transfer.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BoltShare")
                }
                
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri == null) {
                    _localDownloads.value = _localDownloads.value + (transfer.transferId to LocalDownloadState(status = "failed"))
                    return@launch
                }

                val request = Request.Builder().url(transfer.fileUrl).build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS) // No timeout for writing to disk
                    .readTimeout(0, TimeUnit.SECONDS)  // No timeout for streaming large downloads
                    .build()

                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Failed to download file: $response")
                        
                        val body = response.body ?: throw IOException("Null body")
                        val contentLength = body.contentLength()
                        val inputStream = body.byteStream()
                        
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            var downloaded = 0L
                            var lastProgress = 0
                            
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                                downloaded += read
                                
                                val currentProgress = if (contentLength > 0) {
                                    ((downloaded.toDouble() / contentLength) * 100).toInt()
                                } else 0
                                
                                if (currentProgress - lastProgress >= 2 || currentProgress == 100) {
                                    lastProgress = currentProgress
                                    _localDownloads.value = _localDownloads.value + (transfer.transferId to LocalDownloadState(progress = currentProgress, status = "downloading"))
                                }
                            }
                        } ?: throw IOException("Could not open output stream")
                    }
                }
                
                _localDownloads.value = _localDownloads.value + (transfer.transferId to LocalDownloadState(progress = 100, status = "completed", localUri = uri))
                db.collection("transfers").document(transfer.transferId).update("status", "completed")
                
            } catch (e: Exception) {
                Log.e("Download", "Failed to download", e)
                _localDownloads.value = _localDownloads.value + (transfer.transferId to LocalDownloadState(status = "failed"))
            }
        }
    }

    fun openFile(context: Context, uri: android.net.Uri, fileName: String) {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open File"))
        } catch (e: Exception) {
            _errorMessage.value = "No application found to open this file."
        }
    }
}
