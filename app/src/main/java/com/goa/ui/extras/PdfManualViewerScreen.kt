package com.goa.ui.extras

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfManualViewerScreen(
    pdfFilePath: String,
    title: String,
    onBack: () -> Unit,
    onOpenExternal: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pageCount by remember { mutableStateOf(0) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(pdfFilePath) {
        try {
            val file = File(pdfFilePath)
            if (file.exists() && file.length() > 0) {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfRenderer = PdfRenderer(pfd)
                fileDescriptor = pfd
                renderer = pdfRenderer
                pageCount = pdfRenderer.pageCount
                errorMessage = null
            } else {
                errorMessage = "File not found or empty"
            }
        } catch (e: Exception) {
            Log.e("PdfManualViewer", "Failed to initialize PdfRenderer for $pdfFilePath", e)
            errorMessage = e.message ?: "Failed to open PDF document"
        }

        onDispose {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (e: Exception) {
                Log.w("PdfManualViewer", "Error closing renderer", e)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (onOpenExternal != null) {
                            onOpenExternal(pdfFilePath)
                        } else {
                            openWithSystemPdfViewer(context, pdfFilePath)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open in System Viewer"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Unable to render in-app preview.\n($errorMessage)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        if (onOpenExternal != null) {
                            onOpenExternal(pdfFilePath)
                        } else {
                            openWithSystemPdfViewer(context, pdfFilePath)
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        com.goa.ui.menu.OneLineLabel("Open in System Viewer")
                    }
                }
            }
        } else if (pageCount == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.DarkGray)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color(0xFF222222))
            ) {
                items(pageCount) { pageIndex ->
                    PdfPageItem(
                        renderer = renderer,
                        pageIndex = pageIndex
                    )
                }
            }
        }
    }
}

private fun openWithSystemPdfViewer(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val mime = if (file.extension.equals("pdf", ignoreCase = true)) {
            "application/pdf"
        } else {
            context.contentResolver.getType(uri) ?: "*/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("PdfManualViewer", "Failed to launch external viewer", e)
        Toast.makeText(context, "No app available to open this document", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PdfPageItem(
    renderer: PdfRenderer?,
    pageIndex: Int
) {
    val bitmap = remember(renderer, pageIndex) {
        renderer?.let { r ->
            synchronized(r) {
                try {
                    val page = r.openPage(pageIndex)
                    val width = page.width * 2
                    val height = page.height * 2
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bmp
                } catch (e: Exception) {
                    Log.e("PdfPageItem", "Failed to render page $pageIndex", e)
                    null
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentScale = ContentScale.FillWidth
        )
    }
}
