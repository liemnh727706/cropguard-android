package com.cropguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.cropguard.app.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingCameraPermissionRequest: (() -> Unit)? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private val loadTimeoutHandler = Handler(Looper.getMainLooper())
    private var loadTimeoutRunnable: Runnable? = null
    private val LOAD_TIMEOUT_MS = 20_000L  // 20 seconds - if page is not loaded yet, treat as network error

    // Result launchers
    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult

            try {
                if (result.resultCode != RESULT_OK) {
                    callback.onReceiveValue(null)
                    return@registerForActivityResult
                }

                val data = result.data
                val resultUris: Array<Uri>? = when {
                    // User selected multiple files from the gallery
                    data?.clipData != null -> {
                        val clip = data.clipData!!
                        Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    }
                    // User selected a single file from the gallery
                    data?.data != null -> arrayOf(data.data!!)
                    // User just took a photo with the camera (data is usually null in this case)
                    cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
                    else -> null
                }
                callback.onReceiveValue(resultUris)
            } catch (e: Exception) {
                callback.onReceiveValue(null)
            } finally {
                cameraPhotoUri = null
            }
        }

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Case 1: waiting to open the file chooser (input type="file")
            pendingCameraPermissionRequest?.let { action ->
                if (granted) {
                    action.invoke()
                } else {
                    Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                }
                pendingCameraPermissionRequest = null
            }

            // Case 2: waiting for getUserMedia() (direct in-page camera, using MediaStream)
            pendingWebPermissionRequest?.let { request ->
                if (granted) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                    Toast.makeText(this, "Camera permission is required for this feature", Toast.LENGTH_LONG).show()
                }
                pendingWebPermissionRequest = null
            }
        }

    // Lifecycle
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupSwipeRefresh()
        setupBackPressHandler()

        binding.btnRetry.setOnClickListener { retryLoad() }

        if (savedInstanceState == null) {
            binding.webView.loadUrl(Config.BASE_URL)
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }
        binding.swipeRefresh.setColorSchemeResources(
            android.R.color.holo_green_dark
        )
    }

    private fun setupWebView() = with(binding.webView) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "$userAgentString CropGuardAndroidApp/1.0"
        }

        // Hardware layer helps render <video> (camera stream) more smoothly during pinch-zoom
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Allow cookies (login session) to persist between app sessions
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = CropGuardWebViewClient()
        webChromeClient = CropGuardWebChromeClient()
    }

    // WebViewClient: navigation + network errors + SSL
    private inner class CropGuardWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val host = uri.host ?: return false

            return when {
                // Main domain -> load inside the WebView
                Config.ALLOWED_HOSTS.any { host.endsWith(it) } -> false

                // mailto/tel links -> open the relevant app
                uri.scheme == "mailto" || uri.scheme == "tel" -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }

                // OAuth, external links -> open in real Chrome
                else -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            binding.progressBar.visibility = View.VISIBLE
            binding.errorView.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            startLoadTimeoutWatchdog()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            cancelLoadTimeoutWatchdog()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            // Only show the error screen if the error happened on the main frame
            // (not on a sub-resource like an image or script)
            if (request.isForMainFrame) {
                binding.webView.visibility = View.GONE
                binding.errorView.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                cancelLoadTimeoutWatchdog()
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            // Never bypass SSL errors in production - protects user security
            handler.cancel()
            binding.webView.visibility = View.GONE
            binding.errorView.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            cancelLoadTimeoutWatchdog()
            Toast.makeText(this@MainActivity, "Security certificate error, cannot load page", Toast.LENGTH_LONG).show()
        }
    }

    // WebChromeClient: progress bar + file chooser (camera/gallery)
    private inner class CropGuardWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.progress = newProgress
            if (newProgress >= 100) binding.progressBar.visibility = View.GONE
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback = callback
            pendingFileChooserParams = params

            if (isCameraPermissionGranted()) {
                launchImageChooser(params)
            } else {
                pendingCameraPermissionRequest = { launchImageChooser(pendingFileChooserParams) }
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return true
        }

        // Required for navigator.mediaDevices.getUserMedia() (direct in-page camera,
        // using <video> + canvas, NOT through <input type="file">).
        // Without this override, the web page always reports "Cannot access camera".
        override fun onPermissionRequest(request: PermissionRequest) {
            val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            val needsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

            if (!needsCamera && !needsMic) {
                request.deny()
                return
            }

            runOnUiThread {
                if (isCameraPermissionGranted()) {
                    request.grant(request.resources)
                } else {
                    pendingWebPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private fun isCameraPermissionGranted(): Boolean =
        packageManager.checkPermission(Manifest.permission.CAMERA, packageName) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Opens the file chooser dialog. Reads the actual file type the web page
     * requested (params.acceptTypes) instead of always hardcoding "image/*" -
     * important for the "Teach AI" tab which accepts both PDF and images
     * (accept=".pdf,image/*").
     * Entire body wrapped in try-catch because ActivityNotFoundException
     * (device has no app to handle that MIME type) used to crash the whole app.
     */
    private fun launchImageChooser(params: WebChromeClient.FileChooserParams?) {
        try {
            // Determine the actual MIME type the web page requested
            val acceptTypes = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
            val mimeType = when {
                acceptTypes.isEmpty() -> "*/*"
                acceptTypes.size == 1 && acceptTypes[0] == "image/*" -> "image/*"
                acceptTypes.any { it == "image/*" } &&
                    acceptTypes.any { it.contains("pdf", ignoreCase = true) } -> "*/*"
                else -> "*/*"
            }
            val wantsImage = acceptTypes.isEmpty() || acceptTypes.any { it.startsWith("image") }

            // Camera intent - only added if the web page accepts images
            val cameraIntent: Intent? = if (wantsImage) {
                val photoFile = createImageFile()
                photoFile?.let { file ->
                    cameraPhotoUri = FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", file
                    )
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                }
            } else null

            // Intent to pick a file from the gallery/file manager, with the correct MIME type
            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = mimeType
                addCategory(Intent.CATEGORY_OPENABLE)
                if (mimeType == "*/*" && acceptTypes.isNotEmpty()) {
                    // Give the launcher a more specific hint about accepted MIME types
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                }
            }

            val chooserIntent = Intent.createChooser(galleryIntent, "Choose file").apply {
                if (cameraIntent != null) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }
            }

            fileChooserLauncher.launch(chooserIntent)
        } catch (e: Exception) {
            // Never let an error in the file chooser step crash the whole app
            Toast.makeText(this, "Could not open file chooser", Toast.LENGTH_SHORT).show()
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        } finally {
            pendingFileChooserParams = null
        }
    }

    private fun createImageFile(): File? = try {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        File.createTempFile("cropguard_${System.currentTimeMillis()}_", ".jpg", storageDir)
    } catch (e: Exception) {
        null
    }

    private fun startLoadTimeoutWatchdog() {
        cancelLoadTimeoutWatchdog()
        val runnable = Runnable {
            // Page still hasn't finished loading after LOAD_TIMEOUT_MS -> treat as
            // a network error, do not let the app hang
            binding.webView.stopLoading()
            binding.webView.visibility = View.GONE
            binding.errorView.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
        }
        loadTimeoutRunnable = runnable
        loadTimeoutHandler.postDelayed(runnable, LOAD_TIMEOUT_MS)
    }

    private fun cancelLoadTimeoutWatchdog() {
        loadTimeoutRunnable?.let { loadTimeoutHandler.removeCallbacks(it) }
        loadTimeoutRunnable = null
    }

    fun retryLoad() {
        binding.errorView.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.reload()
    }

    override fun onDestroy() {
        cancelLoadTimeoutWatchdog()
        binding.webView.destroy()
        super.onDestroy()
    }
}
