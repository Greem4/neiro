package ru.greemlab.neiro.ui.yclients

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * Экран с WebView для YClients.
 * 
 * Пользователь авторизуется на сайте, затем приложение
 * извлекает данные о записях со страницы расписания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YClientsWebViewScreen(
    companyId: Int = 520135,
    onBack: () -> Unit,
    onDataExtracted: (List<ExtractedRecord>) -> Unit,
) {
    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val timetableUrl = "https://yclients.com/timetable/$companyId"

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
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Обновить",
                        )
                    }
                    IconButton(
                        onClick = {
                            webView?.let { wv ->
                                extractDataFromPage(wv) { records ->
                                    if (records.isNotEmpty()) {
                                        onDataExtracted(records)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Загружено ${records.size} записей"
                                            )
                                        }
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Записи не найдены. Откройте страницу расписания."
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Загрузить записи",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            YClientsWebView(
                url = timetableUrl,
                onWebViewCreated = { webView = it },
                onLoadingChanged = { isLoading = it },
                onProgressChanged = { progress = it / 100f },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YClientsWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onProgressChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    // Для корректной работы форм
                    javaScriptCanOpenWindowsAutomatically = true
                    // User-Agent как у обычного браузера
                    userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // Включаем куки для авторизации
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@apply, true)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onLoadingChanged(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoadingChanged(false)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }
                }

                loadUrl(url)
                onWebViewCreated(this)
            }
        },
        modifier = modifier,
    )
}

/**
 * Извлекает данные о записях со страницы расписания.
 */
private fun extractDataFromPage(webView: WebView, onResult: (List<ExtractedRecord>) -> Unit) {
    // JavaScript для извлечения данных записей со страницы
    val extractScript = """
        (function() {
            var records = [];
            
            // Ищем записи на странице расписания YClients
            // Структура может отличаться, это примерный селектор
            var recordElements = document.querySelectorAll('.record-item, .timetable-record, [data-record-id], .event-item');
            
            recordElements.forEach(function(el) {
                var record = {};
                
                // Пытаемся извлечь имя клиента
                var clientEl = el.querySelector('.client-name, .record-client, [data-client-name]');
                if (clientEl) {
                    record.clientName = clientEl.textContent.trim();
                }
                
                // Время записи
                var timeEl = el.querySelector('.record-time, .time, [data-time]');
                if (timeEl) {
                    record.time = timeEl.textContent.trim();
                }
                
                // Дата
                var dateEl = el.querySelector('.record-date, .date, [data-date]');
                if (dateEl) {
                    record.date = dateEl.textContent.trim();
                }
                
                // Услуга
                var serviceEl = el.querySelector('.service-name, .record-service, [data-service]');
                if (serviceEl) {
                    record.service = serviceEl.textContent.trim();
                }
                
                // ID записи
                var recordId = el.getAttribute('data-record-id') || el.getAttribute('data-id');
                if (recordId) {
                    record.id = recordId;
                }
                
                if (record.clientName || record.time) {
                    records.push(record);
                }
            });
            
            // Альтернативный способ — ищем данные в глобальных переменных страницы
            if (records.length === 0 && typeof window.__INITIAL_STATE__ !== 'undefined') {
                var state = window.__INITIAL_STATE__;
                if (state.records) {
                    state.records.forEach(function(r) {
                        records.push({
                            id: r.id,
                            clientName: r.client ? r.client.name : '',
                            date: r.date,
                            time: r.datetime,
                            service: r.services ? r.services.map(s => s.title).join(', ') : ''
                        });
                    });
                }
            }
            
            return JSON.stringify(records);
        })();
    """.trimIndent()

    webView.evaluateJavascript(extractScript) { result ->
        try {
            // Результат приходит как JSON-строка в кавычках
            val json = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
            val records = parseExtractedRecords(json)
            onResult(records)
        } catch (e: Exception) {
            onResult(emptyList())
        }
    }
}

/**
 * Парсит JSON с извлечёнными записями.
 */
private fun parseExtractedRecords(json: String): List<ExtractedRecord> {
    if (json.isBlank() || json == "[]" || json == "null") return emptyList()

    return try {
        val gson = com.google.gson.Gson()
        val type = object : com.google.gson.reflect.TypeToken<List<ExtractedRecord>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Данные извлечённой записи.
 */
data class ExtractedRecord(
    val id: String? = null,
    val clientName: String? = null,
    val date: String? = null,
    val time: String? = null,
    val service: String? = null,
)
