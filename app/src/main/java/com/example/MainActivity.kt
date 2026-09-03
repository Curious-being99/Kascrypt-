package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.crypto.BiometricAuthManager
import com.example.model.VaultItem
import com.example.ui.VaultUiState
import com.example.ui.VaultViewModel
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {
    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.updateBiometricHardwareStatus(this)
        
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val biometricHardwareStatus by viewModel.biometricStatus.collectAsStateWithLifecycle()
                    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()

                    when (uiState) {
                        is VaultUiState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        is VaultUiState.Setup -> SetupScreen(
                            isBiometricAvailable = biometricHardwareStatus == BiometricAuthManager.BiometricStatus.AVAILABLE,
                            onSetup = { pw, enableBio -> viewModel.setup(pw, enableBio) }
                        )
                        is VaultUiState.Locked -> {
                            val context = LocalContext.current
                            val activity = context as? FragmentActivity

                            fun triggerBiometricUnlock() {
                                if (activity != null) {
                                    BiometricAuthManager.showBiometricPrompt(
                                        activity = activity,
                                        title = "Unlock Kascrypt Vault",
                                        subtitle = "Biometric authentication",
                                        description = "Verify fingerprint or face to decrypt vault keys",
                                        negativeButtonText = "Use Password",
                                        onSuccess = {
                                            viewModel.unlockWithBiometricCredentials(
                                                onFailure = { err ->
                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                        },
                                        onCancel = {
                                            // Handled gracefully: User stays on password prompt
                                        }
                                    )
                                }
                            }

                            LockedScreen(
                                onUnlock = { pw -> viewModel.unlock(pw) },
                                onBiometricUnlock = { triggerBiometricUnlock() },
                                onResetVault = { viewModel.resetVault() },
                                isBiometricEnabled = isBiometricEnabled,
                                biometricHardwareStatus = biometricHardwareStatus,
                                errorMessage = viewModel.unlockErrorMessage.collectAsStateWithLifecycle().value,
                                isUnlocking = viewModel.isUnlocking.collectAsStateWithLifecycle().value
                            )
                        }
                        is VaultUiState.Unlocked -> VaultScreen(viewModel)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Immediately lock vault and zeroize keys when minimized or sent to background
        viewModel.onAppBackgrounded()
    }
}

@Composable
fun KascryptHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF70C7BA), ambientColor = Color(0xFF70C7BA))
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF70C7BA), Color(0xFF2E635C)))
                )
                .border(1.5.dp, Color(0xFF70C7BA).copy(alpha = 0.6f), RoundedCornerShape(16.dp))
        ) {
            Text(
                "K",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
                style = MaterialTheme.typography.headlineLarge.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.scale(scaleX = -1f, scaleY = 1f)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                "KASCRYPT",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                letterSpacing = 2.sp
            )
            Text(
                "POST-QUANTUM KASPA VAULT",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF70C7BA),
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SetupScreen(
    isBiometricAvailable: Boolean,
    onSetup: (String, Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var enableBiometrics by remember { mutableStateOf(isBiometricAvailable) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KascryptHeader()

        Text(
            text = "Create your Master Vault Key",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Secured with Argon2id (64MB RAM, 3 iterations) + ML-DSA Post-Quantum Signatures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { 
                password = it 
                errorMessage = null
            },
            label = { Text("Master Password (8+ characters)") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("setup_password")
        )

        if (password.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            PasswordStrengthView(password = password)
        }

        Spacer(modifier = Modifier.height(14.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { 
                confirm = it 
                errorMessage = null
            },
            label = { Text("Confirm Master Password") },
            visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = { confirmVisible = !confirmVisible },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (confirmVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (confirmVisible) "Hide confirm password" else "Show confirm password",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            singleLine = true,
            isError = confirm.isNotEmpty() && password != confirm,
            supportingText = {
                if (confirm.isNotEmpty() && password != confirm) {
                    Text("Passwords do not match yet", color = MaterialTheme.colorScheme.error)
                } else if (confirm.isNotEmpty() && password == confirm) {
                    Text("Passwords match ✓", color = MaterialTheme.colorScheme.primary)
                }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("setup_confirm")
        )

        if (isBiometricAvailable) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Biometrics",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Enable Biometric Unlock",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Fingerprint or Face unlock",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = enableBiometrics,
                        onCheckedChange = { enableBiometrics = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF70C7BA),
                            checkedTrackColor = Color(0xFF70C7BA).copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { 
                val trimmedPass = password.trim()
                val trimmedConfirm = confirm.trim()
                if (trimmedPass.length < 8) {
                    errorMessage = "Password must be at least 8 characters long for cryptographic security."
                } else if (trimmedPass != trimmedConfirm) {
                    errorMessage = "Passwords do not match. Please verify your confirm password."
                } else {
                    onSetup(trimmedPass, enableBiometrics)
                }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF70C7BA))
                .testTag("setup_btn")
        ) {
            Text("INITIALIZE SECURE VAULT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun LockedScreen(
    onUnlock: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
    onResetVault: () -> Unit,
    isBiometricEnabled: Boolean,
    biometricHardwareStatus: BiometricAuthManager.BiometricStatus,
    errorMessage: String?,
    isUnlocking: Boolean
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    val canUseBiometrics = isBiometricEnabled && biometricHardwareStatus == BiometricAuthManager.BiometricStatus.AVAILABLE

    // Automatically prompt for biometric unlock once when entering Locked screen if enabled
    LaunchedEffect(isBiometricEnabled) {
        if (canUseBiometrics) {
            onBiometricUnlock()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KascryptHeader()
        
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .border(3.dp, Color(0xFF70C7BA).copy(alpha = 0.3f), CircleShape)
                .shadow(32.dp, CircleShape, spotColor = Color(0xFF70C7BA).copy(alpha = 0.2f))
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(92.dp)
                    .border(2.dp, Color(0xFF70C7BA).copy(alpha = 0.7f), CircleShape)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (canUseBiometrics) Icons.Default.Fingerprint else Icons.Default.Lock,
                        contentDescription = "Locked",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("QUANTUM READY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        
        Text(
            text = "Vault Status: Post-Quantum Encrypted",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "XChaCha20-Poly1305 + ML-DSA Active",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (canUseBiometrics) {
            Button(
                onClick = onBiometricUnlock,
                enabled = !isUnlocking,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF70C7BA),
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(16.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF70C7BA))
                    .testTag("biometric_unlock_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = "Fingerprint",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "UNLOCK WITH BIOMETRICS",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "  OR USE PASSWORD  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Enter Master Password") },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = errorMessage != null,
            trailingIcon = {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("login_password")
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = { 
                if (password.isNotEmpty() && !isUnlocking) {
                    onUnlock(password.trim())
                }
            },
            enabled = password.isNotEmpty() && !isUnlocking,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF70C7BA))
                .testTag("unlock_btn")
        ) {
            if (isUnlocking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("VERIFYING & DERIVING KEY...", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            } else {
                Text("OPEN VAULT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = { showResetDialog = true }
        ) {
            Text(
                "Forgot password? Reset Vault",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Secure Vault?") },
            text = { 
                Text("Resetting will erase the local encrypted database so you can set a new master password. Ensure you have backed up any critical keys or mnemonics.") 
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showResetDialog = false
                        onResetVault()
                    }
                ) {
                    Text("RESET VAULT")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(viewModel: VaultViewModel) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.resetAutoLockTimer()
    }
    var isSettingsPageOpen by remember { mutableStateOf(false) }

    if (isSettingsPageOpen) {
        SettingsScreen(
            viewModel = viewModel,
            onBack = { isSettingsPageOpen = false }
        )
    } else {
        val items by viewModel.filteredVaultItems.collectAsStateWithLifecycle(initialValue = emptyList())
        val rawItems by viewModel.rawVaultItems.collectAsStateWithLifecycle()
        val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
        val networkStatus by viewModel.kaspaNetworkStatus.collectAsStateWithLifecycle()
        val kfsProgress by viewModel.kfsUploadProgress.collectAsStateWithLifecycle()
        val kfsStatus by viewModel.kfsUploadStatus.collectAsStateWithLifecycle()
        val kfsLastResult by viewModel.kfsLastResult.collectAsStateWithLifecycle()
        val addressResult by viewModel.addressLookupResult.collectAsStateWithLifecycle()
        val txResult by viewModel.txLookupResult.collectAsStateWithLifecycle()
        val kaspaWallet by viewModel.kaspaWallet.collectAsStateWithLifecycle()
        val walletBalance by viewModel.kaspaWalletBalance.collectAsStateWithLifecycle()
        val walletSompis by viewModel.kaspaWalletSompis.collectAsStateWithLifecycle()
        val walletUtxos by viewModel.kaspaWalletUtxos.collectAsStateWithLifecycle()
        val isRefreshingBalance by viewModel.isRefreshingBalance.collectAsStateWithLifecycle()

        var showAddDialog by remember { mutableStateOf(false) }
        var showSyncDialog by remember { mutableStateOf(false) }
        var showWalletDialog by remember { mutableStateOf(false) }
        var itemToDelete by remember { mutableStateOf<VaultItem?>(null) }
        var previewImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isSearching by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var tempCameraImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showPhotoSourceDialog by remember { mutableStateOf(false) }
    var showImageUploadDialog by remember { mutableStateOf(false) }
    var imageUploadTitle by remember { mutableStateOf("") }
    var isEncryptingImage by remember { mutableStateOf(false) }
    var showSeedAuthFromWallet by remember { mutableStateOf(false) }
    var isSeedRevealedInWallet by remember { mutableStateOf(false) }

    val onImageSelected: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            selectedImageUri = uri
            imageUploadTitle = ""
            showImageUploadDialog = true
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success && tempCameraImageUri != null) {
                onImageSelected(tempCameraImageUri)
            }
        }
    )

    val launchHardwareCamera: () -> Unit = {
        try {
            val cacheDir = File(context.cacheDir, "camera_photos")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val tempFile = File.createTempFile("kascrypt_cam_${System.currentTimeMillis()}", ".jpg", cacheDir)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                tempFile
            )
            tempCameraImageUri = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch hardware camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                launchHardwareCamera()
            } else {
                Toast.makeText(context, "Camera permission is required to capture photos.", Toast.LENGTH_SHORT).show()
            }
        }
    )

    val checkAndLaunchCamera: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchHardwareCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = onImageSelected
    )

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = onImageSelected
    )

    val activityResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val uri = result.data?.data
            onImageSelected(uri)
        }
    )

    val launchSafeImagePicker: () -> Unit = {
        try {
            pickVisualMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            try {
                getContentLauncher.launch("image/*")
            } catch (e2: Exception) {
                try {
                    val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    activityResultLauncher.launch(pickIntent)
                } catch (e3: Exception) {
                    try {
                        val getImgIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "image/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        activityResultLauncher.launch(Intent.createChooser(getImgIntent, "Select Image to Encrypt"))
                    } catch (e4: Exception) {
                        Toast.makeText(context, "No gallery or photo picker app found on device.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search vault...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(onClick = { 
                                    viewModel.setSearchQuery("")
                                    isSearching = false 
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .shadow(8.dp, RoundedCornerShape(10.dp), spotColor = Color(0xFF70C7BA))
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF70C7BA), Color(0xFF3D7A72))))
                            ) {
                                Text("K", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic), modifier = Modifier.scale(scaleX = -1f, scaleY = 1f))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("KASCRYPT", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, letterSpacing = 1.sp)
                                Text("${rawItems.size} items secured", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                actions = {
                    if (!isSearching) {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                    IconButton(onClick = { isSettingsPageOpen = true }, modifier = Modifier.testTag("settings_btn")) {
                        Icon(Icons.Default.Settings, contentDescription = "Vault Settings", tint = Color(0xFF70C7BA))
                    }
                    IconButton(onClick = { showWalletDialog = true }, modifier = Modifier.testTag("wallet_btn")) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Kaspa Wallet", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { 
                        showPhotoSourceDialog = true
                    }, modifier = Modifier.testTag("img_btn")) {
                        Icon(Icons.Default.Image, contentDescription = "Upload Image")
                    }
                    IconButton(onClick = { 
                        viewModel.checkKaspaNetwork()
                        showSyncDialog = true 
                    }, modifier = Modifier.testTag("sync_btn")) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Sync to Kaspa")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }, 
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_btn").shadow(12.dp, CircleShape, spotColor = Color(0xFF70C7BA))
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Entry")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(2.dp)) }

            // Kaspa Built-in Wallet Card
            if (kaspaWallet != null) {
                val fullAddr = kaspaWallet!!.address
                val shortAddr = if (fullAddr.length > 22) "${fullAddr.take(12)}...${fullAddr.takeLast(8)}" else fullAddr
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showWalletDialog = true }
                            .border(1.dp, Color(0xFF70C7BA).copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF70C7BA).copy(alpha = 0.2f))
                                    ) {
                                        Icon(
                                            Icons.Default.AccountBalanceWallet,
                                            contentDescription = null,
                                            tint = Color(0xFF70C7BA),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Kaspa Wallet",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isRefreshingBalance) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color(0xFF70C7BA)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        "Fund & Receive →",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF70C7BA),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column {
                                    Text(
                                        "${String.format(Locale.US, "%.6f", walletBalance)} KAS",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF70C7BA)
                                    )
                                    Text(
                                        "$walletSompis Sompis • ${walletUtxos.size} UTXOs",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                                    modifier = Modifier.clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Kaspa Address", fullAddr)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Kaspa address copied!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            shortAddr,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy",
                                            modifier = Modifier.size(12.dp),
                                            tint = Color(0xFF70C7BA)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No entries match '$searchQuery'" else "Your quantum vault is empty.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tap '+' to add a password or secret, or tap the image icon to encrypt photos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(items, key = { it.id }) { item ->
                    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()) }
                    val formattedDate = remember(item.timestamp) { dateFormat.format(Date(item.timestamp)) }
                    
                    var decryptedBitmap by remember(item.imagePath) { mutableStateOf<Bitmap?>(null) }
                    var isSecretRevealed by remember(item.id) { mutableStateOf(false) }
                    LaunchedEffect(item.imagePath) {
                        if (item.imagePath != null) {
                            decryptedBitmap = viewModel.getDecryptedBitmap(context, item.imagePath)
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Icon(
                                        imageVector = if (item.imagePath != null) Icons.Default.Image else Icons.Default.Key,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { isSecretRevealed = !isSecretRevealed },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isSecretRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (isSecretRevealed) "Hide secret" else "Reveal secret",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Secret Content", item.content)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied secret to clipboard", Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy secret", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { itemToDelete = item }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            if (decryptedBitmap != null) {
                                Image(
                                    bitmap = decryptedBitmap!!.asImageBitmap(),
                                    contentDescription = "Decrypted Image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { previewImageBitmap = decryptedBitmap }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            val displayContent = if (isSecretRevealed || item.imagePath != null) {
                                item.content
                            } else {
                                "•".repeat(item.content.length.coerceIn(8, 24))
                            }

                            Text(
                                text = displayContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSecretRevealed) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formattedDate,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = "ML-DSA Signed ✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(88.dp)) }
        }

        // Full Screen Image Preview Dialog
        if (previewImageBitmap != null) {
            Dialog(onDismissRequest = { previewImageBitmap = null }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = previewImageBitmap!!.asImageBitmap(),
                            contentDescription = "Full Preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { previewImageBitmap = null }) {
                            Text("Close")
                        }
                    }
                }
            }
        }

        // Delete confirmation dialog
        if (itemToDelete != null) {
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text("Delete Entry") },
                text = { Text("Are you sure you want to delete '${itemToDelete?.title}'? This action cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        itemToDelete?.let {
                            viewModel.deleteEntry(context, it.id, it.imagePath)
                        }
                        itemToDelete = null
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { itemToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Add Entry Dialog
        if (showAddDialog) {
            var title by remember { mutableStateOf("") }
            var content by remember { mutableStateOf("") }
            var contentVisible by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.primary,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                title = { Text("New Secure Entry") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title / Account") },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("add_title")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("Secret / Password / Notes") },
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (contentVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { contentVisible = !contentVisible },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (contentVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (contentVisible) "Hide secret" else "Reveal secret",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().testTag("add_content")
                        )

                        if (content.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            PasswordStrengthView(password = content)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                showAddDialog = false
                                showPhotoSourceDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Encrypt Photo (Camera or Gallery)")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (title.isNotBlank()) {
                            viewModel.addEntry(title.trim(), content)
                            showAddDialog = false
                        }
                    }, modifier = Modifier.testTag("save_btn")) {
                        Text("Encrypt & Sign", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            )
        }

        // Photo Source Selection Dialog (Hardware Camera vs Gallery)
        if (showPhotoSourceDialog) {
            PhotoSourceDialog(
                onDismiss = { showPhotoSourceDialog = false },
                onSelectCamera = {
                    showPhotoSourceDialog = false
                    checkAndLaunchCamera()
                },
                onSelectGallery = {
                    showPhotoSourceDialog = false
                    launchSafeImagePicker()
                }
            )
        }

        // Dedicated Image Upload and Encrypt Dialog
        if (showImageUploadDialog && selectedImageUri != null) {
            var previewThumbBitmap by remember(selectedImageUri) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(selectedImageUri) {
                if (selectedImageUri != null) {
                    try {
                        context.contentResolver.openInputStream(selectedImageUri!!)?.use { stream ->
                            val fullBmp = android.graphics.BitmapFactory.decodeStream(stream)
                            if (fullBmp != null) {
                                val maxDim = 800
                                val w = fullBmp.width
                                val h = fullBmp.height
                                if (w > maxDim || h > maxDim) {
                                    val scale = maxDim.toFloat() / maxOf(w, h)
                                    previewThumbBitmap = Bitmap.createScaledBitmap(fullBmp, (w * scale).toInt(), (h * scale).toInt(), true)
                                } else {
                                    previewThumbBitmap = fullBmp
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore preview decode failure
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { 
                    if (!isEncryptingImage) {
                        showImageUploadDialog = false
                        selectedImageUri = null
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.primary,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Encrypt Photo / Image")
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (previewThumbBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, Color(0xFF70C7BA).copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = previewThumbBitmap!!.asImageBitmap(),
                                    contentDescription = "Photo Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        Text(
                            "This image will be encrypted on-device with XChaCha20-Poly1305 and signed with ML-DSA Post-Quantum cryptography.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = imageUploadTitle,
                            onValueChange = { imageUploadTitle = it },
                            label = { Text("Image Title / Label (Optional)") },
                            placeholder = { Text("e.g. ID Document, Secret Photo") },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            enabled = !isEncryptingImage,
                            modifier = Modifier.fillMaxWidth().testTag("image_upload_title")
                        )
                        if (isEncryptingImage) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Encrypting & signing asset...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val uri = selectedImageUri ?: return@Button
                            isEncryptingImage = true
                            val finalTitle = if (imageUploadTitle.isBlank()) "Secured Image Asset" else imageUploadTitle.trim()
                            viewModel.addImageEntry(
                                context = context,
                                uri = uri,
                                title = finalTitle,
                                onSuccess = {
                                    isEncryptingImage = false
                                    showImageUploadDialog = false
                                    selectedImageUri = null
                                    Toast.makeText(context, "Image encrypted with XChaCha20-Poly1305 and stored!", Toast.LENGTH_SHORT).show()
                                },
                                onError = { errorMsg ->
                                    isEncryptingImage = false
                                    Toast.makeText(context, "Error: $errorMsg", Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        enabled = !isEncryptingImage,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Encrypt & Save")
                    }
                },
                dismissButton = {
                    if (!isEncryptingImage) {
                        TextButton(onClick = { 
                            showImageUploadDialog = false
                            selectedImageUri = null
                        }) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            )
        }

        // Kaspa BlockDAG & KFS Dialog
        if (showSyncDialog) {
            AlertDialog(
                onDismissRequest = { showSyncDialog = false },
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.primary,
                textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Kaspa Storage Sync (KFS)")
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (kfsProgress != null) {
                            Text(kfsStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { kfsProgress ?: 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        } else {
                            Button(
                                onClick = { viewModel.broadcastVaultToKaspa() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Broadcast Vault to Kaspa (KFS)")
                            }
                        }

                        if (kfsLastResult != null) {
                            val result = kfsLastResult!!
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Last KFS Execution:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("Merkle Root: ${result.manifest.merkleRoot}", style = MaterialTheme.typography.labelSmall)
                                    Text("Total Chunks: ${result.manifest.totalChunks} | Payload: ${result.manifest.totalBytes} bytes", style = MaterialTheme.typography.labelSmall)
                                    if (result.logs.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Execution logs (${result.logs.size} steps):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                        Text(result.logs.takeLast(4).joinToString("\n"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSyncDialog = false }) {
                        Text("Close", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Dedicated Kaspa Wallet Dialog
        if (showWalletDialog && kaspaWallet != null) {
            KaspaWalletDialog(
                wallet = kaspaWallet!!,
                balance = walletBalance,
                sompis = walletSompis,
                utxos = walletUtxos,
                isRefreshing = isRefreshingBalance,
                onRefresh = { viewModel.refreshKaspaWalletBalance() },
                onRequestRevealSeed = { showSeedAuthFromWallet = true },
                onHideSeed = { isSeedRevealedInWallet = false },
                isSeedRevealed = isSeedRevealedInWallet,
                onDismiss = { 
                    showWalletDialog = false
                    isSeedRevealedInWallet = false
                }
            )
        }

        // Seed Phrase Authentication Dialog from Wallet
        if (showSeedAuthFromWallet && kaspaWallet != null) {
            SeedPhraseAuthDialog(
                viewModel = viewModel,
                activity = activity,
                onAuthenticated = {
                    showSeedAuthFromWallet = false
                    isSeedRevealedInWallet = true
                },
                onDismiss = { showSeedAuthFromWallet = false }
            )
        }

        }
    }
}

@Composable
fun KaspaQrCode(address: String, modifier: Modifier = Modifier) {
    val matrix = remember(address) { com.example.crypto.QrCodeGenerator.encodeToMatrix(address) }
    val matrixDim = matrix.size

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(2.dp, Color(0xFF70C7BA).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val cellW = size.width / matrixDim
            val cellH = size.height / matrixDim
            for (r in 0 until matrixDim) {
                for (c in 0 until matrixDim) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = Color(0xFF0C1F1D),
                            topLeft = androidx.compose.ui.geometry.Offset(c * cellW, r * cellH),
                            size = androidx.compose.ui.geometry.Size(cellW + 0.5f, cellH + 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun KaspaWalletDialog(
    wallet: com.example.crypto.KaspaWalletManager.KaspaWallet,
    balance: Double,
    sompis: Long,
    utxos: List<com.example.network.KaspaUtxoEntry>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRequestRevealSeed: () -> Unit,
    onHideSeed: () -> Unit,
    isSeedRevealed: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.primary,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF70C7BA).copy(alpha = 0.2f))
                ) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = Color(0xFF70C7BA),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text("Kaspa Wallet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // QR Code
                KaspaQrCode(
                    address = wallet.address,
                    modifier = Modifier
                        .size(160.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp), spotColor = Color(0xFF70C7BA))
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Address Box with 1-click Copy
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Kaspa Deposit Address", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF70C7BA))
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Kaspa Address", wallet.address)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Kaspa address copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = Color(0xFF70C7BA))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            wallet.address,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Balance & Live Refresh Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Confirmed Balance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${String.format(Locale.US, "%.6f", balance)} KAS",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF70C7BA)
                            )
                            Text("$sompis Sompis • ${utxos.size} UTXOs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Button(
                            onClick = onRefresh,
                            enabled = !isRefreshing,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF70C7BA).copy(alpha = 0.2f), contentColor = Color(0xFF70C7BA))
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF70C7BA))
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Recovery Seed Phrase Section
                if (isSeedRevealed) {
                    RevealedSeedPhraseCard(
                        mnemonic = wallet.mnemonic,
                        onHide = onHideSeed,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Vault Recovery Seed", wallet.mnemonic)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Recovery seed copied! Clear clipboard after use.", Toast.LENGTH_LONG).show()
                        }
                    )
                } else {
                    OutlinedButton(
                        onClick = onRequestRevealSeed,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF70C7BA))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View Recovery Seed Phrase", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF70C7BA), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun SeedPhraseAuthDialog(
    viewModel: VaultViewModel,
    activity: FragmentActivity?,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit
) {
    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val biometricStatus by viewModel.biometricStatus.collectAsStateWithLifecycle()
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    val triggerBiometrics: () -> Unit = {
        if (activity != null && biometricStatus == BiometricAuthManager.BiometricStatus.AVAILABLE) {
            BiometricAuthManager.showBiometricPrompt(
                activity = activity,
                title = "Unlock Seed Phrase",
                subtitle = "Biometric Verification",
                description = "Scan your fingerprint or face to view your recovery phrase",
                negativeButtonText = "Use Password",
                onSuccess = {
                    onAuthenticated()
                },
                onError = { err ->
                    errorMessage = err
                },
                onCancel = { }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (isBiometricEnabled && biometricStatus == BiometricAuthManager.BiometricStatus.AVAILABLE && activity != null) {
            triggerBiometrics()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.primary,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF70C7BA).copy(alpha = 0.2f))
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color(0xFF70C7BA), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text("Unlock Seed Phrase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    "Your BIP-39 recovery seed controls all your encrypted secrets and Kaspa funds. Authenticate via biometrics or master password to reveal it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isBiometricEnabled && biometricStatus == BiometricAuthManager.BiometricStatus.AVAILABLE) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { triggerBiometrics() },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF70C7BA), contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("UNLOCK WITH BIOMETRICS", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Text(" OR MASTER PASSWORD ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { 
                        passwordInput = it
                        errorMessage = null 
                    },
                    label = { Text("Master Password") },
                    placeholder = { Text("Enter your master password") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = Color(0xFF70C7BA)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isVerifying,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (passwordInput.isBlank()) {
                        errorMessage = "Please enter your master password."
                        return@Button
                    }
                    isVerifying = true
                    viewModel.verifyMasterPassword(passwordInput) { success, err ->
                        isVerifying = false
                        if (success) {
                            onAuthenticated()
                        } else {
                            errorMessage = err ?: "Incorrect master password."
                        }
                    }
                },
                enabled = !isVerifying,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF70C7BA), contentColor = Color.Black)
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Text("VERIFY & REVEAL", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun RevealedSeedPhraseCard(
    mnemonic: String,
    onHide: () -> Unit,
    onCopy: () -> Unit
) {
    val words = remember(mnemonic) { mnemonic.trim().split("\\s+".toRegex()) }
    var secondsRemaining by remember { mutableIntStateOf(60) }

    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            kotlinx.coroutines.delay(1000L)
            secondsRemaining -= 1
        }
        onHide()
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF70C7BA).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, tint = Color(0xFF70C7BA), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Recovery Phrase (Revealed)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF70C7BA))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Concealing in ${secondsRemaining}s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onHide, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = "Hide", tint = Color(0xFF70C7BA), modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2-column layout of word chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in words.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (w in row) {
                            val idx = words.indexOf(w) + 1
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "$idx.",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF70C7BA)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        w,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                        if (row.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF70C7BA))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Phrase", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onHide,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Hide Now", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun PasswordStrengthView(
    password: String,
    modifier: Modifier = Modifier
) {
    val strength = remember(password) {
        com.example.crypto.CryptoManager.detectPasswordStrength(password)
    }

    val barColor = when (strength.score) {
        0 -> Color(0xFFE57373) // Red
        1 -> Color(0xFFFFB74D) // Orange
        2 -> Color(0xFFFFD54F) // Yellow
        3 -> Color(0xFF81C784) // Green
        else -> Color(0xFF70C7BA) // Kaspa Teal (Cryptographic Grade)
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strength.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = barColor
                )
                Text(
                    text = "${String.format(Locale.US, "%.0f", strength.entropyBits)} bits entropy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 4 Segment Strength Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 1..4) {
                    val active = strength.score >= i || (i == 1 && password.isNotEmpty() && strength.score == 0)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (active) barColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Cryptographic Rules Checklist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StrengthCheckChip(label = "8+ chars", valid = strength.isValidMinLength)
                StrengthCheckChip(label = "A-Z", valid = strength.hasUppercase)
                StrengthCheckChip(label = "a-z", valid = strength.hasLowercase)
                StrengthCheckChip(label = "0-9", valid = strength.hasDigits)
                StrengthCheckChip(label = "#$%", valid = strength.hasSymbols)
            }
        }
    }
}

@Composable
fun StrengthCheckChip(label: String, valid: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (valid) Color(0xFF70C7BA) else Color.Gray.copy(alpha = 0.4f))
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = if (valid) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    val isBiometricEnabled by viewModel.isBiometricEnabled.collectAsStateWithLifecycle()
    val isAutoLockEnabled by viewModel.isAutoLockEnabled.collectAsStateWithLifecycle()
    val biometricStatus by viewModel.biometricStatus.collectAsStateWithLifecycle()
    val kaspaWallet by viewModel.kaspaWallet.collectAsStateWithLifecycle()
    val networkStatus by viewModel.kaspaNetworkStatus.collectAsStateWithLifecycle()
    val addressResult by viewModel.addressLookupResult.collectAsStateWithLifecycle()
    val txResult by viewModel.txLookupResult.collectAsStateWithLifecycle()

    var masterPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var bioErrorMessage by remember { mutableStateOf<String?>(null) }
    var bioSuccessMessage by remember { mutableStateOf<String?>(null) }

    var revealMnemonic by remember { mutableStateOf(false) }
    var showSeedAuthDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var showImportSection by remember { mutableStateOf(false) }

    var customNodeUrl by remember { mutableStateOf("https://api.kaspa.org/") }
    var inspectAddress by remember { mutableStateOf("") }
    var inspectTxId by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF70C7BA).copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF70C7BA), modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Vault Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Security, Keys, Node & Explorer", style = MaterialTheme.typography.labelSmall, color = Color(0xFF70C7BA))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Vault")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = Color(0xFF70C7BA)
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Security & Lock", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Seed & Keys", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Tab(selected = selectedTab == 2, onClick = { 
                    selectedTab = 2 
                    viewModel.checkKaspaNetwork()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("DAG Monitor", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Explorer", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        // Security & Lock Tab
                        Text(
                            "Auto-Lock Settings",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Automatically lock your vault after inactivity when master password is set.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isAutoLockEnabled) Color(0xFF70C7BA).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("auto_lock_card")
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Auto-Lock Vault",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isAutoLockEnabled) Color(0xFF70C7BA) else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        if (isAutoLockEnabled) "Locks automatically after 5 minutes of inactivity" else "Auto-lock disabled",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = isAutoLockEnabled,
                                    onCheckedChange = { viewModel.toggleAutoLock(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.Black,
                                        checkedTrackColor = Color(0xFF70C7BA)
                                    ),
                                    modifier = Modifier.testTag("auto_lock_switch")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { viewModel.lock() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("lock_vault_now_btn")
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Lock Vault Now", fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            "Biometric Hardware Authentication",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Configure fingerprint or face recognition as an alternative key layer for your Kascrypt vault.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isBiometricEnabled) Color(0xFF70C7BA).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (isBiometricEnabled) "Biometric Unlock Enabled" else "Biometric Unlock Disabled",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isBiometricEnabled) Color(0xFF70C7BA) else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        when (biometricStatus) {
                                            BiometricAuthManager.BiometricStatus.AVAILABLE -> "Hardware sensors active & ready"
                                            BiometricAuthManager.BiometricStatus.NOT_ENROLLED -> "No biometrics registered on device"
                                            BiometricAuthManager.BiometricStatus.UNAVAILABLE -> "Biometric hardware unavailable"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isBiometricEnabled) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF70C7BA), modifier = Modifier.size(28.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        if (biometricStatus != BiometricAuthManager.BiometricStatus.AVAILABLE) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Note: Fingerprint or face unlock is not set up in system settings, but you can still enter your master password below to enable biometric unlock on this device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        if (!isBiometricEnabled) {
                            Text(
                                "Enter Password to Enable Biometrics on Device",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = masterPassword,
                                onValueChange = { masterPassword = it; bioErrorMessage = null },
                                label = { Text("Master Password") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().testTag("biometric_password_input")
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    if (masterPassword.isBlank()) {
                                        bioErrorMessage = "Password cannot be empty"
                                        return@Button
                                    }
                                    bioErrorMessage = null
                                    bioSuccessMessage = null
                                    if (activity != null && biometricStatus == BiometricAuthManager.BiometricStatus.AVAILABLE) {
                                        BiometricAuthManager.showBiometricPrompt(
                                            activity = activity,
                                            title = "Confirm Biometrics",
                                            subtitle = "Register biometric key",
                                            description = "Authenticate to link biometrics to your master key",
                                            negativeButtonText = "Cancel",
                                            onSuccess = {
                                                viewModel.enableBiometric(masterPassword) { success, err ->
                                                    if (success) {
                                                        bioSuccessMessage = "Biometric unlock successfully enabled on device!"
                                                        masterPassword = ""
                                                    } else {
                                                        bioErrorMessage = err ?: "Failed to enable biometrics."
                                                    }
                                                }
                                            },
                                            onError = { err -> bioErrorMessage = err },
                                            onCancel = { }
                                        )
                                    } else {
                                        viewModel.enableBiometric(masterPassword) { success, err ->
                                            if (success) {
                                                bioSuccessMessage = "Biometric unlock successfully enabled on device!"
                                                masterPassword = ""
                                            } else {
                                                bioErrorMessage = err ?: "Failed to enable biometrics."
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF70C7BA), contentColor = Color.Black),
                                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("enable_biometric_btn")
                            ) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("ENABLE BIOMETRIC UNLOCK", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            }
                        } else {
                            Button(
                                onClick = {
                                    viewModel.disableBiometric {
                                        bioSuccessMessage = "Biometric unlock disabled."
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) {
                                Text("DISABLE BIOMETRIC UNLOCK", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (bioErrorMessage != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(bioErrorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (bioSuccessMessage != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(bioSuccessMessage!!, color = Color(0xFF70C7BA), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    1 -> {
                        // Seed & Keys Tab
                        Text("Vault Mnemonic & Recovery Phrase", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Your 12-word BIP-39 phrase encrypts your master vault and restores your Kaspa wallet keys. Authentication is required to view it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))

                        if (kaspaWallet != null) {
                            if (revealMnemonic) {
                                RevealedSeedPhraseCard(
                                    mnemonic = kaspaWallet!!.mnemonic,
                                    onHide = { revealMnemonic = false },
                                    onCopy = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Vault Seed", kaspaWallet!!.mnemonic)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Recovery seed copied! Clear clipboard after use.", Toast.LENGTH_LONG).show()
                                    }
                                )
                            } else {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF70C7BA), modifier = Modifier.size(20.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    "••••••••••••••••••••••••",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF70C7BA)
                                                )
                                            }
                                            IconButton(
                                                onClick = { showSeedAuthDialog = true },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Visibility,
                                                    contentDescription = "Unlock Seed Phrase",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = Color(0xFF70C7BA)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            "Seed phrase is locked and protected. Authenticate using your master password or device biometrics to reveal.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = { showSeedAuthDialog = true },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF70C7BA), contentColor = Color.Black),
                                            modifier = Modifier.fillMaxWidth().height(44.dp)
                                        ) {
                                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("UNLOCK SEED PHRASE", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            if (showSeedAuthDialog) {
                                SeedPhraseAuthDialog(
                                    viewModel = viewModel,
                                    activity = activity,
                                    onAuthenticated = {
                                        showSeedAuthDialog = false
                                        revealMnemonic = true
                                    },
                                    onDismiss = { showSeedAuthDialog = false }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (!showImportSection) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showImportSection = true },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Import Seed Phrase", style = MaterialTheme.typography.labelLarge)
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.generateNewKaspaSeed(12) },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Generate Fresh Seed", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = importText,
                                    onValueChange = { 
                                        importText = it 
                                        importError = null
                                    },
                                    label = { Text("Paste 12 or 24-word Seed Phrase") },
                                    shape = RoundedCornerShape(12.dp),
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (importError != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(importError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val words = importText.trim().split("\\s+".toRegex())
                                            if (words.size != 12 && words.size != 24) {
                                                importError = "Please enter exactly 12 or 24 words."
                                            } else {
                                                val (success, err) = viewModel.importKaspaSeed(importText, importPassphrase.trim())
                                                if (success) {
                                                    Toast.makeText(context, "Imported seed successfully!", Toast.LENGTH_SHORT).show()
                                                    showImportSection = false
                                                    importText = ""
                                                } else {
                                                    importError = err ?: "Failed to derive keys from seed."
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Save & Derive Keys")
                                    }
                                    TextButton(
                                        onClick = { showImportSection = false },
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        } else {
                            Text("No wallet key loaded.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    2 -> {
                        // DAG Monitor Tab
                        Text("Live BlockDAG Network Status", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Real-time node connection telemetry, DAG difficulty, and block height.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = networkStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = customNodeUrl,
                            onValueChange = { customNodeUrl = it },
                            label = { Text("Kaspa Node Endpoint") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.updateKaspaNodeUrl(customNodeUrl) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text("CONNECT & REFRESH NODE", fontWeight = FontWeight.Bold)
                        }
                    }
                    3 -> {
                        // Live Explorer Tab
                        Text("Kaspa BlockDAG On-Chain Explorer", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Query real-time balances, UTXO entries, and transaction status directly from Kaspa nodes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = inspectAddress,
                            onValueChange = { inspectAddress = it },
                            label = { Text("Kaspa Address (kaspa:...)") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.checkKaspaAddressBalance(inspectAddress) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Query Address Balance & UTXOs")
                        }
                        if (addressResult != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(addressResult!!, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedTextField(
                            value = inspectTxId,
                            onValueChange = { inspectTxId = it },
                            label = { Text("Kaspa Transaction Hash") },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.checkKaspaTransaction(inspectTxId) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Query Transaction Status")
                        }
                        if (txResult != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(txResult!!, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoSourceDialog(
    onDismiss: () -> Unit,
    onSelectCamera: () -> Unit,
    onSelectGallery: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.primary,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Add Encrypted Photo", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Capture a photo using device hardware camera or choose an image from your device gallery/files. All images are zero-knowledge encrypted on-device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Hardware Camera Option
                Surface(
                    onClick = onSelectCamera,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF70C7BA).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().testTag("option_camera")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF70C7BA).copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFF70C7BA), modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Take Hardware Photo", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text("Use device camera to snap and encrypt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Gallery Option
                Surface(
                    onClick = onSelectGallery,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth().testTag("option_gallery")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Choose from Gallery / Files", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text("Pick existing photo or document", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

