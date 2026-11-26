package com.goa.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.goa.data.api.GogAuthClient

/** Google refuses sign-in from embedded WebViews (the "wv" marker), and GOG's "Sign in with Google" opens a popup. */
@SuppressLint("SetJavaScriptEnabled")
private fun configure(web: WebView) {
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(web, true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GogAuthModalSheet(
    onDismissRequest: () -> Unit,
    onAuthCodeReceived: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isLoading by remember { mutableStateOf(true) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    val latestOnCode by rememberUpdatedState(onAuthCodeReceived)

    // Shared by the main page and the Google popup: the login code can arrive in either
    val client = remember {
        object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) Log.w("GogAuth", "Load failed ${request.url}: ${error?.description}")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("embed.gog.com/on_login_success")) {
                    val code = Uri.parse(url).getQueryParameter("code")
                    if (!code.isNullOrBlank()) {
                        latestOnCode(code)
                        return true
                    }
                }
                return false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(0.92f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        configure(this)
                        webViewClient = client
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                val child = WebView(context).also { configure(it); it.webViewClient = client }
                                child.webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView?) {
                                        popup = null
                                        window?.destroy()
                                    }
                                }
                                transport.webView = child
                                resultMsg.sendToTarget()
                                popup = child
                                return true
                            }
                        }
                        loadUrl(GogAuthClient.AUTH_URL)
                    }
                }
            )

            popup?.let { child ->
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { child })
            }

            // Header toolbar
            TopAppBar(
                title = { Text("Sign in to GOG") },
                actions = {
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
