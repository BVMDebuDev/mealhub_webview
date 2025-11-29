package com.mealhub.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.net.URLDecoder
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val websiteUrl = "http://mealhub.wuaze.com"
    
    private val downloadIds = mutableSetOf<Long>()
    private lateinit var downloadManager: DownloadManager
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var pageTitle: TextView
    private lateinit var backButton: ImageButton
    private lateinit var refreshButton: ImageButton

    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Some permissions denied. Features may not work properly.", Toast.LENGTH_LONG).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val downloadId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            
            if (downloadId in downloadIds) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIndex)
                    
                    val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                    val fileName = cursor.getString(titleIndex)
                    
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            Toast.makeText(
                                this@MainActivity,
                                "✓ Download complete: $fileName",
                                Toast.LENGTH_LONG
                            ).show()
                            
                            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val fileUri = cursor.getString(uriIndex)
                            if (fileUri != null) {
                                showOpenFileOption(Uri.parse(fileUri), fileName)
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = cursor.getInt(reasonIndex)
                            Toast.makeText(
                                this@MainActivity,
                                "✗ Download failed: $fileName (Error: $reason)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                cursor.close()
                downloadIds.remove(downloadId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        pageTitle = findViewById(R.id.pageTitle)
        backButton = findViewById(R.id.backButton)
        refreshButton = findViewById(R.id.refreshButton)
        
        setupWebView()
        checkAllPermissions()
        registerDownloadReceiver()
        
        swipeRefreshLayout.setOnRefreshListener { webView.reload() }

        backButton.setOnClickListener { 
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                super.onBackPressed()
            }
        }

        refreshButton.setOnClickListener { 
            webView.reload() 
        }
        
        webView.loadUrl(websiteUrl)
    }

    private fun setupWebView() {
        // Cookie management for persistent login
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                
                // Handle UPI payment links
                if (url.startsWith("upi://") || url.startsWith("phonepe://") || 
                    url.startsWith("paytmmp://") || url.startsWith("gpay://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "No app found to handle payment", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }
                
                // Handle phone calls
                if (url.startsWith("tel:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Cannot make call", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                
                // Handle email
                if (url.startsWith("mailto:")) {
                    try {
                        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "No email app found", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                
                return false
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Flush cookies after page load to ensure persistence
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CookieManager.getInstance().flush()
                }
                swipeRefreshLayout.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                pageTitle.text = title
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    fileChooserLauncher.launch(intent)
                    return true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open file chooser: ${e.message}", Toast.LENGTH_SHORT).show()
                    e.printStackTrace()
                    return false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            var fileName: String

            // Priority 1: Filename from Content-Disposition header.
            val pattern = Pattern.compile("filename\\*?=['\"]?(?:UTF-8['\"]*)?([^'\";]+)['\"]?", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(contentDisposition)
            fileName = if (matcher.find()) {
                val potentialName = matcher.group(1)
                try {
                    URLDecoder.decode(potentialName, "UTF-8").trim().removeSurrounding("\"")
                } catch (e: Exception) {
                    potentialName.trim().removeSurrounding("\"")
                }
            } else {
                // Priority 2: Filename from URL path.
                Uri.parse(url).lastPathSegment ?: "downloadfile"
            }

            // Extension Guarantee: If the filename we found does NOT have an extension,
            // add one based on the mimetype. We DO NOT overwrite an existing extension.
            if (!fileName.contains(".")) {
                val extension = getExtensionFromMimeType(mimetype)
                if (extension.isNotEmpty()) {
                    fileName = "$fileName$extension"
                }
            }

            // Sanitize the final filename to remove illegal characters.
            fileName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                setDescription("Downloading file...")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                allowScanningByMediaScanner()
            }

            val downloadId = downloadManager.enqueue(request)
            downloadIds.add(downloadId)
            
            Toast.makeText(this, "⬇ Downloading: $fileName", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun getExtensionFromMimeType(mimetype: String): String {
        val mime = mimetype.lowercase()
        return when {
            // Comprehensive Documents
            mime.contains("pdf") -> ".pdf"
            mime.contains("msword") -> ".doc" // Covers older .doc
            mime.contains("wordprocessingml.document") -> ".docx"
            mime.contains("ms-excel") -> ".xls" // Covers older .xls
            mime.contains("spreadsheetml.sheet") -> ".xlsx"
            mime.contains("ms-powerpoint") -> ".ppt" // Covers older .ppt
            mime.contains("presentationml.presentation") -> ".pptx"
            mime.contains("opendocument.text") -> ".odt"
            mime.contains("opendocument.spreadsheet") -> ".ods"
            mime.contains("opendocument.presentation") -> ".odp"
            mime.contains("rtf") -> ".rtf"

            // Common Text & Data Formats
            mime.contains("plain") -> ".txt"
            mime.contains("html") -> ".html"
            mime.contains("css") -> ".css"
            mime.contains("javascript") || mime.contains("ecmascript") -> ".js"
            mime.contains("json") -> ".json"
            mime.contains("xml") -> ".xml"
            mime.contains("csv") -> ".csv"
            mime.contains("markdown") -> ".md"

            // Comprehensive Image Formats
            mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
            mime.contains("png") -> ".png"
            mime.contains("gif") -> ".gif"
            mime.contains("webp") -> ".webp"
            mime.contains("svg") -> ".svg"
            mime.contains("bmp") -> ".bmp"
            mime.contains("tiff") -> ".tiff"
            mime.contains("ico") || mime.contains("icon") -> ".ico"
            mime.contains("heic") -> ".heic"
            mime.contains("heif") -> ".heif"

            // Comprehensive Audio Formats
            mime.contains("mpeg") && mime.contains("audio") -> ".mp3"
            mime.contains("mp3") -> ".mp3"
            mime.contains("wav") || mime.contains("x-wav") -> ".wav"
            mime.contains("ogg") && mime.contains("audio") -> ".ogg"
            mime.contains("flac") -> ".flac"
            mime.contains("aac") -> ".aac"
            mime.contains("m4a") || mime.contains("x-m4a") -> ".m4a"
            mime.contains("wma") || mime.contains("x-ms-wma") -> ".wma"
            mime.contains("opus") -> ".opus"
            mime.contains("amr") -> ".amr"

            // Comprehensive Video Formats
            mime.contains("mp4") -> ".mp4"
            mime.contains("webm") -> ".webm"
            mime.contains("ogg") && mime.contains("video") -> ".ogv"
            mime.contains("quicktime") -> ".mov"
            mime.contains("x-msvideo") -> ".avi"
            mime.contains("x-ms-wmv") -> ".wmv"
            mime.contains("x-flv") -> ".flv"
            mime.contains("x-matroska") || mime.contains("mkv") -> ".mkv"
            mime.contains("mpeg") && mime.contains("video") -> ".mpeg"
            mime.contains("3gpp") -> ".3gp"
            mime.contains("3gpp2") -> ".3g2"

            // Common Archive Formats
            mime.contains("zip") || mime.contains("x-zip-compressed") -> ".zip"
            mime.contains("x-rar-compressed") || mime.contains("x-rar") -> ".rar"
            mime.contains("x-7z-compressed") -> ".7z"
            mime.contains("x-tar") -> ".tar"
            mime.contains("gzip") -> ".gz"
            mime.contains("x-bzip2") -> ".bz2"
            mime.contains("x-xz") -> ".xz"

            // Executables & Installers
            mime.contains("vnd.android.package-archive") -> ".apk"
            mime.contains("x-msdownload") || mime.contains("exe") -> ".exe"
            mime.contains("x-msi") -> ".msi"
            mime.contains("x-deb") -> ".deb"
            mime.contains("x-rpm") -> ".rpm"
            mime.contains("x-sh") -> ".sh"

            // Font Formats
            mime.contains("font-woff") -> ".woff"
            mime.contains("font-woff2") -> ".woff2"
            mime.contains("x-font-ttf") || mime.contains("font-sfnt") -> ".ttf"
            mime.contains("x-font-otf") -> ".otf"
            mime.contains("vnd.ms-fontobject") -> ".eot"

            // E-books
            mime.contains("epub+zip") -> ".epub"
            mime.contains("x-mobipocket-ebook") -> ".mobi"
            mime.contains("vnd.amazon.ebook") -> ".azw"

            // Professional & Niche
            mime.contains("photoshop") || mime.contains("vnd.adobe.photoshop") -> ".psd"
            mime.contains("illustrator") -> ".ai"
            mime.contains("postscript") -> ".ps"
            mime.contains("acad") -> ".dwg"
            mime.contains("sla") -> ".stl"
            mime.contains("x-sqlite3") -> ".sqlite3"
            mime.contains("sql") -> ".sql"
            mime.contains("x-bittorrent") -> ".torrent"
            mime.contains("gpx+xml") -> ".gpx"
            mime.contains("keychain") -> ".key"

            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype)?.let { ".$it" } ?: ""
        }
    }

    private fun showOpenFileOption(fileUri: Uri, fileName: String) {
        try {
            val file = File(fileUri.path ?: return)
            if (!file.exists()) return
            
            val contentUri = FileProvider.getUriForFile(
                this,
                "com.mealhub.app.fileprovider",
                file
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val mimeType = contentResolver.getType(contentUri) ?: "*/*"
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "Open $fileName"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(downloadCompleteReceiver, filter)
        }
    }

    private fun checkAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.CAMERA)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6-12
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                        != PackageManager.PERMISSION_GRANTED) {
                        permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.CAMERA)
                }
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            multiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    fun refreshWebView() {
        webView.reload()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        }
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                onBackPressedDispatcher.onBackPressed()
            } else {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
