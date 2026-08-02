package ru.greemlab.neiro.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Экран авторизации в YClients.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    viewModel: AuthViewModel = viewModel(),
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // Пароль не должен оставаться в StateFlow после ухода с экрана (E7).
    DisposableEffect(Unit) {
        onDispose { viewModel.clearPassword() }
    }

    // Отслеживаем переход «не авторизован → авторизован», чтобы автоматически
    // закрыть экран и запустить синхронизацию. Это и есть «возврат в приложение
    // после авторизации»: пользователь видит результат сразу в календаре.
    var wasLoggedIn by remember { mutableStateOf(state.isLoggedIn) }
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn && !wasLoggedIn) {
            onLoginSuccess()
        }
        wasLoggedIn = state.isLoggedIn
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YClients") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                if (state.isLoggedIn) {
                    LoggedInContent(
                        userName = state.userName,
                        onLogout = { viewModel.logout() },
                        onSyncClick = onLoginSuccess,
                    )
                } else {
                    LoginForm(
                        login = state.login,
                        password = state.password,
                        isLoading = state.isLoading,
                        error = state.error,
                        onLoginChange = { viewModel.updateLogin(it) },
                        onPasswordChange = { viewModel.updatePassword(it) },
                        onLogin = {
                            focusManager.clearFocus()
                            viewModel.login()
                        },
                        onFocusNext = { focusManager.moveFocus(FocusDirection.Down) },
                    )
                }

                if (state.showPartnerTokenSetup) {
                    Spacer(modifier = Modifier.height(24.dp))
                    PartnerTokenSetup(
                        partnerToken = state.partnerToken,
                        companyId = state.companyId,
                        onPartnerTokenChange = { viewModel.updatePartnerToken(it) },
                        onCompanyIdChange = { viewModel.updateCompanyId(it) },
                        onSave = { viewModel.savePartnerSettings() },
                        onCancel = { viewModel.hidePartnerTokenSetup() },
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun LoginForm(
    login: String,
    password: String,
    isLoading: Boolean,
    error: String?,
    onLoginChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onFocusNext: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Text(
        text = "Вход в YClients",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Используйте логин и пароль от yclients.com",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(32.dp))

    OutlinedTextField(
        value = login,
        onValueChange = onLoginChange,
        label = { Text("Email или телефон") },
        singleLine = true,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onNext = { onFocusNext() }),
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (shouldApplyPhoneMask(login)) {
            PhoneLoginVisualTransformation
        } else {
            VisualTransformation.None
        },
    )

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Пароль") },
        singleLine = true,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Rounded.VisibilityOff
                    } else {
                        Icons.Rounded.Visibility
                    },
                    contentDescription = if (passwordVisible) "Скрыть" else "Показать",
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onLogin() }),
        shape = RoundedCornerShape(12.dp),
    )

    AnimatedVisibility(visible = error != null) {
        Text(
            text = error ?: "",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onLogin,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Войти")
        }
    }
}

private fun shouldApplyPhoneMask(value: String): Boolean =
    value.isNotEmpty() && value.length <= 11 && value.all { it.isDigit() }

private object PhoneLoginVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val transformed = StringBuilder()
        val originalToTransformed = IntArray(raw.length + 1)

        raw.forEachIndexed { index, char ->
            when (index) {
                1 -> transformed.append('(')
                4 -> transformed.append(")-")
                7, 9 -> transformed.append('-')
            }
            originalToTransformed[index] = transformed.length
            transformed.append(char)
            originalToTransformed[index + 1] = transformed.length
        }

        val transformedToOriginal = IntArray(transformed.length + 1)
        var originalIndex = 0
        for (transformedIndex in transformedToOriginal.indices) {
            while (
                originalIndex < originalToTransformed.lastIndex &&
                originalToTransformed[originalIndex + 1] <= transformedIndex
            ) {
                originalIndex++
            }
            transformedToOriginal[transformedIndex] = originalIndex
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val safe = offset.coerceIn(0, raw.length)
                return originalToTransformed[safe]
            }

            override fun transformedToOriginal(offset: Int): Int {
                val safe = offset.coerceIn(0, transformed.length)
                return transformedToOriginal[safe]
            }
        }

        return TransformedText(
            text = AnnotatedString(transformed.toString()),
            offsetMapping = offsetMapping,
        )
    }
}

@Composable
private fun LoggedInContent(
    userName: String?,
    onLogout: () -> Unit,
    onSyncClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Вы авторизованы",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (!userName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = userName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onSyncClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Синхронизировать записи")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Выйти")
            }
        }
    }
}

@Composable
private fun PartnerTokenSetup(
    partnerToken: String,
    companyId: String,
    onPartnerTokenChange: (String) -> Unit,
    onCompanyIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = "Настройки API",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Partner Token можно получить на developer.yclients.com",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = partnerToken,
                onValueChange = onPartnerTokenChange,
                label = { Text("Partner Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = companyId,
                onValueChange = onCompanyIdChange,
                label = { Text("ID компании") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text("Отмена")
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onSave) {
                    Text("Сохранить")
                }
            }
        }
    }
}
