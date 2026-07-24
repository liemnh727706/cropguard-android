package com.cropguard.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout

    // â•â•â• Camera / Gallery state â•â•â•
    private var cameraCallback: String? = null      // tÃªn JS callback function
    private var currentPhotoPath: String = ""       // Ä‘Æ°á»ng dáº«n file áº£nh táº¡m
    private var photoUri: Uri? = null               // URI FileProvider

    // WebChromeClient file chooser callback
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val REQUEST_CAMERA = 200
        private const val REQUEST_GALLERY = 201
        private const val REQUEST_FILE_CHOOSER = 202
    }

    // â•â•â• Activity Result launchers â•â•â•
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            handleCameraResult()
        } else {
            // User há»§y - gá»i callback vá»›i null
            cameraCallback?.let { cb ->
                webView.post {
                    webView.evaluateJavascript("if(window['$cb'])window['$cb'](null)", null)
                }
            }
            cameraCallback = null
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleGalleryResult(uri)
            }
        } else {
            cameraCallback?.let { cb ->
                webView.post {
                    webView.evaluateJavascript("if(window['$cb'])window['$cb'](null)", null)
                }
            }
            cameraCallback = null
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = WebChromeClient.FileChooserParams.parseResult(
                result.resultCode, result.data
            )
            filePathCallback?.onReceiveValue(uris)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)

        setupWebView()
        setupSwipeRefresh()
        setupErrorView()

        webView.loadUrl(Config.BASE_URL)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // WEBVIEW SETUP
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " CropGuardApp/1.0"

        // â•â•â• JAVASCRIPT BRIDGE - Native Camera â•â•â•
        webView.addJavascriptInterface(CropGuardBridge(), "CropGuardNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Link ngoai domain -> mo Chrome
                if (Config.ALLOWED_HOSTS.none { url.contains(it) }) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false

                // Inject bridge helper vao web
                injectBridgeHelper()
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                    swipeRefresh.isRefreshing = false
                }
            }

            override fun onReceivedSslError(
                view: WebView, handler: SslErrorHandler, error: SslError
            ) {
                handler.cancel() // Khong bo qua loi SSL
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }

            // Ho tro input type="file" thong thuong
            override fun onShowFileChooser(
                view: WebView,
                filePath: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePath

                val intent = fileChooserParams.createIntent()
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    filePathCallback = null
                    return false
                }
                return true
            }

            // Permission cho camera trong WebRTC
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        // Cookie
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // JAVASCRIPT BRIDGE CLASS
    // Web goi: CropGuardNative.openCamera('callbackName')
    //          CropGuardNative.openGallery('callbackName')
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    inner class CropGuardBridge {

        @JavascriptInterface
        fun openCamera(callbackName: String) {
            cameraCallback = callbackName
            if (checkCameraPermission()) {
                launchCamera()
            } else {
                requestCameraPermission()
            }
        }

        @JavascriptInterface
        fun openGallery(callbackName: String) {
            cameraCallback = callbackName
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            }
            galleryLauncher.launch(intent)
        }

        @JavascriptInterface
        fun openCameraOrGallery(callbackName: String) {
            // Hien dialog chon Camera hoac Gallery
            cameraCallback = callbackName
            runOnUiThread {
                showCameraGalleryDialog()
            }
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return """{"platform":"android","model":"${Build.MODEL}","version":${Build.VERSION.SDK_INT}}"""
        }
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // CAMERA LOGIC
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                // Khong co quyen - fallback sang gallery
                cameraCallback?.let { cb ->
                    webView.post {
                        webView.evaluateJavascript(
                            "if(window['$cb'])window['$cb'](null,'no_permission')", null
                        )
                    }
                }
                cameraCallback = null
            }
        }
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            val photoFile = createImageFile()
            photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            cameraLauncher.launch(intent)
        }
    }

    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("CROPGUARD_${timestamp}_", ".jpg", storageDir).also {
            currentPhotoPath = it.absolutePath
        }
    }

    private fun handleCameraResult() {
        val cb = cameraCallback ?: return
        cameraCallback = null

        try {
            // Doc anh tu file, compress va chuyen sang base64
            val bitmap = BitmapFactory.decodeFile(currentPhotoPath)
            if (bitmap == null) {
                webView.post {
                    webView.evaluateJavascript("if(window['$cb'])window['$cb'](null,'decode_error')", null)
                }
                return
            }

            // Compress xuong max 1280px de tranh qua lon
            val scaled = scaleBitmap(bitmap, 1280)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            // Goi callback ve JavaScript
            val dataUrl = "data:image/jpeg;base64,$base64"
            webView.post {
                webView.evaluateJavascript(
                    "if(window['$cb'])window['$cb']('$dataUrl','image/jpeg')", null
                )
            }

            // Xoa file tam
            File(currentPhotoPath).delete()

        } catch (e: Exception) {
            webView.post {
                webView.evaluateJavascript("if(window['$cb'])window['$cb'](null,'error')", null)
            }
        }
    }

    private fun handleGalleryResult(uri: Uri) {
        val cb = cameraCallback ?: return
        cameraCallback = null

        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                webView.post {
                    webView.evaluateJavascript("if(window['$cb'])window['$cb'](null,'decode_error')", null)
                }
                return
            }

            val scaled = scaleBitmap(bitmap, 1280)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val dataUrl = "data:image/jpeg;base64,$base64"
            webView.post {
                webView.evaluateJavascript(
                    "if(window['$cb'])window['$cb']('$dataUrl','image/jpeg')", null
                )
            }

        } catch (e: Exception) {
            webView.post {
                webView.evaluateJavascript("if(window['$cb'])window['$cb'](null,'error')", null)
            }
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val ratio = minOf(maxSize.toFloat() / w, maxSize.toFloat() / h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun showCameraGalleryDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Chá»n nguá»“n áº£nh")
            .setItems(arrayOf("ðŸ“· Chá»¥p áº£nh", "ðŸ–¼ï¸ Chá»n tá»« thÆ° viá»‡n")) { _, which ->
                when (which) {
                    0 -> if (checkCameraPermission()) launchCamera() else requestCameraPermission()
                    1 -> {
                        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
                        galleryLauncher.launch(intent)
                    }
                }
            }
            .setNegativeButton("Há»§y") { _, _ ->
                cameraCallback?.let { cb ->
                    webView.post {
                        webView.evaluateJavascript("if(window['$cb'])window['$cb'](null)", null)
                    }
                }
                cameraCallback = null
            }
            .create()
        dialog.show()
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // INJECT BRIDGE HELPER - Them ham tien ich vao web
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    private fun injectBridgeHelper() {
        val js = """
            (function() {
                if (window.__cropguardBridgeInjected) return;
                window.__cropguardBridgeInjected = true;
                
                // Helper: mo camera native
                window.openNativeCameraAndroid = function(callback) {
                    var cbName = '__cgcb_' + Date.now();
                    window[cbName] = function(base64, mimeType) {
                        delete window[cbName];
                        callback(base64, mimeType);
                    };
                    CropGuardNative.openCamera(cbName);
                };
                
                // Helper: mo gallery native
                window.openNativeGalleryAndroid = function(callback) {
                    var cbName = '__cgcb_' + Date.now();
                    window[cbName] = function(base64, mimeType) {
                        delete window[cbName];
                        callback(base64, mimeType);
                    };
                    CropGuardNative.openGallery(cbName);
                };
                
                // Helper: chon camera hoac gallery
                window.openNativeCameraOrGalleryAndroid = function(callback) {
                    var cbName = '__cgcb_' + Date.now();
                    window[cbName] = function(base64, mimeType) {
                        delete window[cbName];
                        callback(base64, mimeType);
                    };
                    CropGuardNative.openCameraOrGallery(cbName);
                };
                
                console.log('CropGuard Android Bridge injected!');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // UI HELPERS
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.cropguard_green)
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
    }

    private fun setupErrorView() {
        val btnRetry = errorView.findViewById<Button>(R.id.btnRetry)
        btnRetry?.setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }
}


