package com.goa.ui.extras

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    title: String,
    images: List<File>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(0) }
    val currentImage = images.getOrNull(currentIndex)

    BackHandler {
        onBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title)
                        if (images.size > 1) {
                            Text(
                                text = "Image ${currentIndex + 1} of ${images.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        currentImage?.let { file ->
                            openInSystemGallery(context, file)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open in Gallery"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (images.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (images.size > 1) {
                            IconButton(
                                onClick = {
                                    if (currentIndex > 0) currentIndex--
                                },
                                enabled = currentIndex > 0
                            ) {
                                Icon(Icons.Default.NavigateBefore, contentDescription = "Previous")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(48.dp))
                        }

                        Button(
                            onClick = {
                                currentImage?.let { file ->
                                    setAsSystemWallpaper(context, file)
                                }
                            },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            com.goa.ui.menu.OneLineLabel("Set as Wallpaper")
                        }

                        if (images.size > 1) {
                            IconButton(
                                onClick = {
                                    if (currentIndex + 1 < images.size) currentIndex++
                                },
                                enabled = currentIndex + 1 < images.size
                            ) {
                                Icon(Icons.Default.NavigateNext, contentDescription = "Next")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(48.dp))
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (currentImage != null) {
                AsyncImage(
                    model = currentImage,
                    contentDescription = currentImage.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "No images found.",
                    color = Color.White
                )
            }
        }
    }
}

private fun openInSystemGallery(context: Context, imageFile: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("ImageViewer", "Failed to launch external gallery", e)
        Toast.makeText(context, "No gallery app available to open this image", Toast.LENGTH_SHORT).show()
    }
}

private fun setAsSystemWallpaper(context: Context, imageFile: File) {
    try {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bitmap != null) {
            val wm = WallpaperManager.getInstance(context)
            wm.setBitmap(bitmap)
            Toast.makeText(context, "Wallpaper updated successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to decode image", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("ImageViewer", "Failed to set wallpaper", e)
        Toast.makeText(context, "Failed to set wallpaper: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
