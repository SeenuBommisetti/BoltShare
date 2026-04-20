package com.boltshare.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boltshare.app.ui.theme.BoltShareTheme

@OptIn(ExperimentalMaterial3Api::class)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize User Identity on launch
        viewModel.initializeIdentity(this)
        
        enableEdgeToEdge()
        setContent {
            BoltShareTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("BoltShare", fontWeight = FontWeight.Bold) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    },
                    snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) } // We can use snacbar later if needed, but error states directly in UI is simpler
                ) { innerPadding ->
                    DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun DashboardScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val myCode by viewModel.myCode.collectAsState()
    val receiverCode by viewModel.receiverCode.collectAsState()
    val fileName by viewModel.selectedFileName.collectAsState()
    val fileSize by viewModel.selectedFileSize.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val activeTransfer by viewModel.activeSenderTransfer.collectAsState()
    val incoming by viewModel.incomingTransfers.collectAsState()
    val localDownloads by viewModel.localDownloads.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Phase 4: File Picker Launcher (Handles permissions automatically via Storage Access Framework)
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.setSelectedFile(context, uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- MY IDENTITY SECTION ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (myCode == null) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Generating Identity...", color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text("Your Sharing Code", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    Text(myCode ?: "", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 4.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- SEND FILE SECTION ---
        Text("Send a File", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = receiverCode,
            onValueChange = viewModel::updateReceiverCode,
            label = { Text("Receiver Code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pick File")
            }
            Spacer(modifier = Modifier.width(16.dp))
            if (fileName != null) {
                val sizeMb = fileSize?.let { it / (1024 * 1024) } ?: 0
                Text("$fileName ($sizeMb MB)", maxLines = 1, fontSize = 14.sp, modifier = Modifier.weight(1f))
            } else {
                Text("No file selected", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (activeTransfer != null && activeTransfer!!.status == "uploading") {
            val t = activeTransfer!!
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Simulating Upload to ${t.receiverCode}...", fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { t.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Text("${t.progress}%", fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
            }
        } else {
            Button(
                onClick = { viewModel.sendFile(context) },
                enabled = receiverCode.length == 6 && fileName != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send File", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (activeTransfer?.status == "sent") {
                 Text("Sent successfully!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- INCOMING TRANSFERS SECTION ---
        Text("Incoming Transfers", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
        Spacer(modifier = Modifier.height(12.dp))
        
        if (incoming.isEmpty()) {
            Text("Listening for incoming files...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(incoming) { transfer ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = transfer.fileName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "From: ${transfer.senderId.take(6)}...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            val localState = localDownloads[transfer.transferId]
                            val displayStatus = localState?.status ?: transfer.status
                            val displayProgress = localState?.progress ?: transfer.progress

                            if (displayStatus == "uploading") {
                                LinearProgressIndicator(
                                    progress = { displayProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Sender is uploading... $displayProgress%",
                                    fontSize = 12.sp,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            } else if (displayStatus == "downloading") {
                                LinearProgressIndicator(
                                    progress = { displayProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Downloading... $displayProgress%",
                                    fontSize = 12.sp,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            } else {
                                Text(
                                    text = "Status: ${displayStatus.uppercase()}",
                                    color = if (displayStatus == "completed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )

                                if (displayStatus == "completed" && localState?.localUri != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { viewModel.openFile(context, localState.localUri, transfer.fileName) },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Open File")
                                    }
                                } else if (displayStatus == "failed" && transfer.status == "sent") {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { viewModel.downloadFile(context, transfer) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Retry Download")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}