package com.ryanvidal.barfriend.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.ryanvidal.barfriend.core.data.ServerCheckResult
import com.ryanvidal.barfriend.core.designsystem.BarFriendTheme
import com.ryanvidal.barfriend.core.designsystem.LocalBarFriendStatusColors
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.ServerInfo
import com.ryanvidal.barfriend.core.network.ServerUrlNormalizer
import com.ryanvidal.barfriend.core.platform.rememberLocalNetworkPermissionState
import kotlinx.serialization.Serializable

@Serializable
data object AuthRoute : NavKey

private enum class PendingAuthAction {
    VERIFY,
    SIGN_IN,
}

private const val LOCAL_NETWORK_DENIED_MESSAGE =
    "Local network access was denied. Enable Nearby Devices / Local Network access in Settings to connect to local servers."

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    modifier: Modifier = Modifier,
    initialServerUrl: String? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var didSeedServerUrl by rememberSaveable(initialServerUrl) { mutableStateOf(false) }
    var hasPromptedLocalNetwork by rememberSaveable { mutableStateOf(false) }
    var pendingAction by rememberSaveable { mutableStateOf<PendingAuthAction?>(null) }
    var localNetworkDeniedMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val localNetworkPermission = rememberLocalNetworkPermissionState { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) {
            localNetworkDeniedMessage = null
            when (action) {
                PendingAuthAction.VERIFY -> viewModel.checkServer()
                PendingAuthAction.SIGN_IN -> viewModel.signIn()
                null -> Unit
            }
        } else {
            localNetworkDeniedMessage = LOCAL_NETWORK_DENIED_MESSAGE
        }
    }

    LaunchedEffect(initialServerUrl) {
        if (!didSeedServerUrl && !initialServerUrl.isNullOrBlank() && uiState.serverUrl.isBlank()) {
            viewModel.onServerUrlChanged(initialServerUrl)
        }
        didSeedServerUrl = true
    }

    LaunchedEffect(localNetworkPermission.isGranted) {
        if (localNetworkPermission.isGranted) {
            localNetworkDeniedMessage = null
        }
    }

    val isLiteralLocalUrl = ServerUrlNormalizer.isLocalHostUrl(uiState.serverUrl)
    val showLocalNetworkBanner = localNetworkPermission.isRequired &&
        !localNetworkPermission.isGranted &&
        (isLiteralLocalUrl || uiState.showLocalNetworkHintAfterFailure)

    val runWithLocalNetworkCheck: (PendingAuthAction, () -> Unit) -> Unit = { action, proceed ->
        if (
            localNetworkPermission.isRequired &&
            !localNetworkPermission.isGranted &&
            isLiteralLocalUrl &&
            !hasPromptedLocalNetwork &&
            !localNetworkPermission.isPermanentlyDenied
        ) {
            hasPromptedLocalNetwork = true
            pendingAction = action
            localNetworkDeniedMessage = null
            localNetworkPermission.requestPermission()
        } else {
            localNetworkDeniedMessage = null
            proceed()
        }
    }

    AuthContent(
        uiState = uiState,
        onServerUrlChanged = { value ->
            localNetworkDeniedMessage = null
            viewModel.onServerUrlChanged(value)
        },
        onEmailChanged = viewModel::onEmailChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onCheckServerClick = {
            runWithLocalNetworkCheck(PendingAuthAction.VERIFY, viewModel::checkServer)
        },
        onSignInClick = {
            runWithLocalNetworkCheck(PendingAuthAction.SIGN_IN, viewModel::signIn)
        },
        showLocalNetworkBanner = showLocalNetworkBanner,
        isLocalNetworkPermanentlyDenied = localNetworkPermission.isPermanentlyDenied,
        onGrantLocalNetworkClick = {
            if (localNetworkPermission.isPermanentlyDenied) {
                localNetworkPermission.openAppSettings()
            } else {
                pendingAction = null
                localNetworkDeniedMessage = null
                localNetworkPermission.requestPermission()
            }
        },
        isPermissionPending = pendingAction != null,
        localErrorMessage = localNetworkDeniedMessage,
        modifier = modifier,
    )
}

@Composable
fun AuthContent(
    uiState: AuthUiState,
    onServerUrlChanged: (String) -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onCheckServerClick: () -> Unit,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLocalNetworkBanner: Boolean = false,
    onGrantLocalNetworkClick: () -> Unit = {},
    isLocalNetworkPermanentlyDenied: Boolean = false,
    isPermissionPending: Boolean = false,
    localErrorMessage: String? = null,
) {
    val serverUrlFocusRequester = remember { FocusRequester() }
    val emailFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val isCleartextPublicUrl = remember(uiState.serverUrl) {
        ServerUrlNormalizer.isCleartextPublicUrl(uiState.serverUrl)
    }
    val nonDuplicateUiError = uiState.errorMessage?.takeUnless { message ->
        isDuplicateStatusError(uiState.serverStatus, message)
    }
    val displayedErrorMessage = localErrorMessage ?: nonDuplicateUiError
    val canVerify = uiState.serverUrl.isNotBlank() &&
        !uiState.isCheckingServer &&
        !uiState.isSigningIn &&
        !isPermissionPending
    val canSignIn = uiState.serverUrl.isNotBlank() &&
        uiState.email.isNotBlank() &&
        uiState.password.isNotBlank() &&
        !uiState.isSigningIn &&
        !isPermissionPending

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocalBar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(20.dp)
                            .size(40.dp),
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Bar Friend",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Connect to your Bar Assistant v6+ server",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedVisibility(visible = showLocalNetworkBanner) {
                    LocalNetworkPermissionBanner(
                        isPermanentlyDenied = isLocalNetworkPermanentlyDenied,
                        onActionClick = onGrantLocalNetworkClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))

                OutlinedTextField(
                    value = uiState.serverUrl,
                    onValueChange = onServerUrlChanged,
                    label = { Text("Server URL") },
                    placeholder = { Text("https://bar.example.com") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = onCheckServerClick,
                            enabled = canVerify,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            if (uiState.isCheckingServer) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Verify")
                            }
                        }
                    },
                    singleLine = true,
                    enabled = !uiState.isSigningIn,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { emailFocusRequester.requestFocus() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(serverUrlFocusRequester),
                )

                AnimatedVisibility(visible = uiState.serverStatus != null) {
                    uiState.serverStatus?.let { status ->
                        ServerStatusBanner(
                            status = status,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                }

                AnimatedVisibility(visible = isCleartextPublicUrl) {
                    CleartextWarningBanner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = onEmailChanged,
                    label = { Text("Email") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Email,
                            contentDescription = null,
                        )
                    },
                    singleLine = true,
                    enabled = !uiState.isSigningIn,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { passwordFocusRequester.requestFocus() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(emailFocusRequester)
                        .semantics { contentType = ContentType.EmailAddress },
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = onPasswordChanged,
                    label = { Text("Password") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                imageVector = if (uiState.passwordVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (uiState.passwordVisible) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                },
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !uiState.isSigningIn,
                    shape = MaterialTheme.shapes.medium,
                    visualTransformation = if (uiState.passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            if (canSignIn) onSignInClick()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocusRequester)
                        .semantics { contentType = ContentType.Password },
                )

                AnimatedVisibility(visible = displayedErrorMessage != null) {
                    displayedErrorMessage?.let { message ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                Button(
                    onClick = onSignInClick,
                    enabled = canSignIn,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    if (uiState.isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Signing In...")
                    } else {
                        Text("Sign In")
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalNetworkPermissionBanner(
    isPermanentlyDenied: Boolean,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (isPermanentlyDenied) {
                    "Local network access is turned off. Open Settings to allow access to servers on Wi-Fi."
                } else {
                    "Allow Local Network access to reach servers on Wi-Fi."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onActionClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(if (isPermanentlyDenied) "Open Settings" else "Grant Access")
            }
        }
    }
}

@Composable
private fun CleartextWarningBanner(modifier: Modifier = Modifier) {
    val warningColor = LocalBarFriendStatusColors.current.substitute
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = warningColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, warningColor.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = warningColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Warning: connecting over unencrypted HTTP sends your credentials in plaintext.",
                style = MaterialTheme.typography.bodySmall,
                color = warningColor,
            )
        }
    }
}

@Composable
private fun ServerStatusBanner(
    status: ServerCheckResult,
    modifier: Modifier = Modifier,
) {
    val readyColor = LocalBarFriendStatusColors.current.ready
    when (status) {
        is ServerCheckResult.Compatible -> {
            Surface(
                modifier = modifier,
                shape = MaterialTheme.shapes.medium,
                color = readyColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, readyColor.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = readyColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Bar Assistant v${status.serverInfo.version}",
                        style = MaterialTheme.typography.labelLarge,
                        color = readyColor,
                    )
                }
            }
        }

        is ServerCheckResult.UnsupportedVersion -> {
            StatusWarningBanner(
                icon = Icons.Rounded.Warning,
                text = "Bar Assistant v${status.actual} is unsupported. Requires v${status.minimum} or newer.",
                modifier = modifier,
            )
        }

        is ServerCheckResult.PasswordLoginDisabled -> {
            StatusWarningBanner(
                icon = Icons.Rounded.Warning,
                text = "Bar Assistant v${status.serverInfo.version} has password login disabled.",
                modifier = modifier,
            )
        }

        is ServerCheckResult.Error -> {
            StatusWarningBanner(
                icon = Icons.Rounded.ErrorOutline,
                text = status.message,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun StatusWarningBanner(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private fun isDuplicateStatusError(
    serverStatus: ServerCheckResult?,
    errorMessage: String,
): Boolean = when (serverStatus) {
    is ServerCheckResult.UnsupportedVersion ->
        errorMessage.contains("not supported", ignoreCase = true) ||
            errorMessage.contains("unsupported", ignoreCase = true)

    is ServerCheckResult.PasswordLoginDisabled ->
        errorMessage.contains("password login is disabled", ignoreCase = true)

    is ServerCheckResult.Error -> errorMessage == serverStatus.message
    is ServerCheckResult.Compatible,
    null,
    -> false
}

@Composable
private fun AuthContentPreview(
    themeMode: AppThemeMode,
    uiState: AuthUiState,
    showLocalNetworkBanner: Boolean = false,
) {
    BarFriendTheme(themeMode = themeMode) {
        AuthContent(
            uiState = uiState,
            onServerUrlChanged = {},
            onEmailChanged = {},
            onPasswordChanged = {},
            onTogglePasswordVisibility = {},
            onCheckServerClick = {},
            onSignInClick = {},
            showLocalNetworkBanner = showLocalNetworkBanner,
        )
    }
}

@Preview
@Composable
private fun AuthDefaultDarkPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.DARK,
        uiState = AuthUiState(),
    )
}

@Preview
@Composable
private fun AuthDefaultLightPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.LIGHT,
        uiState = AuthUiState(),
    )
}

@Preview
@Composable
private fun AuthVerifiedV6DarkPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.DARK,
        uiState = AuthUiState(
            serverUrl = "https://bar.example.com",
            email = "ryan@example.com",
            serverStatus = ServerCheckResult.Compatible(
                ServerInfo(
                    version = "6.2.0",
                    latestVersion = "6.2.0",
                    isPasswordLoginEnabled = true,
                    isAiEnabled = false,
                    isAiImageEnabled = false,
                ),
            ),
        ),
    )
}

@Preview
@Composable
private fun AuthVerifiedV6LightPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.LIGHT,
        uiState = AuthUiState(
            serverUrl = "https://bar.example.com",
            email = "ryan@example.com",
            serverStatus = ServerCheckResult.Compatible(
                ServerInfo(
                    version = "6.2.0",
                    latestVersion = "6.2.0",
                    isPasswordLoginEnabled = true,
                    isAiEnabled = false,
                    isAiImageEnabled = false,
                ),
            ),
        ),
    )
}

@Preview
@Composable
private fun AuthUnsupportedV5DarkPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.DARK,
        uiState = AuthUiState(
            serverUrl = "https://legacy.example.com",
            email = "ryan@example.com",
            serverStatus = ServerCheckResult.UnsupportedVersion(actual = "5.4.1", minimum = "6.0.0"),
        ),
    )
}

@Preview
@Composable
private fun AuthUnsupportedV5LightPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.LIGHT,
        uiState = AuthUiState(
            serverUrl = "https://legacy.example.com",
            email = "ryan@example.com",
            serverStatus = ServerCheckResult.UnsupportedVersion(actual = "5.4.1", minimum = "6.0.0"),
        ),
    )
}

@Preview
@Composable
private fun AuthLocalNetworkBannerDarkPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.DARK,
        uiState = AuthUiState(
            serverUrl = "http://192.168.1.50",
            email = "ryan@example.com",
        ),
        showLocalNetworkBanner = true,
    )
}

@Preview
@Composable
private fun AuthLocalNetworkBannerLightPreview() {
    AuthContentPreview(
        themeMode = AppThemeMode.LIGHT,
        uiState = AuthUiState(
            serverUrl = "http://192.168.1.50",
            email = "ryan@example.com",
        ),
        showLocalNetworkBanner = true,
    )
}
