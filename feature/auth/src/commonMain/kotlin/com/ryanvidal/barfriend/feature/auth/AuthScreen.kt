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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.ryanvidal.barfriend.core.data.ServerCheckResult
import com.ryanvidal.barfriend.core.designsystem.StatusReadyGreen
import com.ryanvidal.barfriend.core.platform.rememberLocalNetworkPermissionState
import kotlinx.serialization.Serializable

@Serializable
data object AuthRoute : NavKey

private enum class PendingAuthAction {
    VERIFY,
    SIGN_IN,
}

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

    val localNetworkPermission = rememberLocalNetworkPermissionState { _ ->
        when (pendingAction) {
            PendingAuthAction.VERIFY -> viewModel.checkServer()
            PendingAuthAction.SIGN_IN -> viewModel.signIn()
            null -> Unit
        }
        pendingAction = null
    }

    LaunchedEffect(initialServerUrl) {
        if (!didSeedServerUrl && !initialServerUrl.isNullOrBlank() && uiState.serverUrl.isBlank()) {
            viewModel.onServerUrlChanged(initialServerUrl)
        }
        didSeedServerUrl = true
    }

    val runWithLocalNetworkCheck: (PendingAuthAction, () -> Unit) -> Unit = { action, proceed ->
        if (localNetworkPermission.isRequired && !localNetworkPermission.isGranted && !hasPromptedLocalNetwork) {
            hasPromptedLocalNetwork = true
            pendingAction = action
            localNetworkPermission.requestPermission()
        } else {
            proceed()
        }
    }

    AuthContent(
        uiState = uiState,
        onServerUrlChanged = viewModel::onServerUrlChanged,
        onEmailChanged = viewModel::onEmailChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onCheckServerClick = {
            runWithLocalNetworkCheck(PendingAuthAction.VERIFY, viewModel::checkServer)
        },
        onSignInClick = {
            runWithLocalNetworkCheck(PendingAuthAction.SIGN_IN, viewModel::signIn)
        },
        showLocalNetworkBanner = localNetworkPermission.isRequired && !localNetworkPermission.isGranted,
        onGrantLocalNetworkClick = localNetworkPermission.requestPermission,
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
) {
    val canSignIn = uiState.serverUrl.isNotBlank() &&
        uiState.email.isNotBlank() &&
        uiState.password.isNotBlank() &&
        !uiState.isSigningIn

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
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
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
                                text = "Allow Local Network access to reach servers on Wi-Fi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onGrantLocalNetworkClick,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text("Grant Access")
                            }
                        }
                    }
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
                            enabled = uiState.serverUrl.isNotBlank() &&
                                !uiState.isCheckingServer &&
                                !uiState.isSigningIn,
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
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
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
                            if (canSignIn) onSignInClick()
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                AnimatedVisibility(visible = uiState.errorMessage != null) {
                    uiState.errorMessage?.let { message ->
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
private fun ServerStatusBanner(
    status: ServerCheckResult,
    modifier: Modifier = Modifier,
) {
    when (status) {
        is ServerCheckResult.Compatible -> {
            Surface(
                modifier = modifier,
                shape = MaterialTheme.shapes.medium,
                color = StatusReadyGreen.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, StatusReadyGreen.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = StatusReadyGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Bar Assistant v${status.serverInfo.version}",
                        style = MaterialTheme.typography.labelLarge,
                        color = StatusReadyGreen,
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
