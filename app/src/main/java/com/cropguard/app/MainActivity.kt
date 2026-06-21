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
    private val LOAD_TIMEOUT_MS = 20_000L  // 20 giây - nếu trang chưa load xong, coi như lỗi mạng

    // ── Result launchers ─────────────────────────────────────────
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
                    // Người dùng chọn nhiều file từ thư viện
                    data?.clipData != null -> {
                        val clip = data.clipData!!
                        Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    }
                    // Người dùng chọn 1 file từ thư viện
                    data?.data != null -> arrayOf(data.data!!)
                    // Người dùng vừa chụp ảnh bằng camera (data thường null/rỗng trong trường hợp này)
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
            // Trường hợp 1: đang chờ mở file chooser (input type="file")
            pendingCameraPermissionRequest?.let { action ->
                if (granted) {
                    action.invoke()
                } else {
                    Toast.makeText(this, "Cần quyền camera để chụp ảnh cây trồng", Toast.LENGTH_SHORT).show()
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                }
                pendingCameraPermissionRequest = null
            }

            // Trường hợp 2: đang chờ getUserMedia() (camera trực tiếp trong trang, dùng MediaStream)
            pendingWebPermissionRequest?.let { request ->
                if (granted) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                    Toast.makeText(this, "Cần cấp quyền Camera để dùng tính năng chụp ảnh", Toast.LENGTH_LONG).show()
                }
                pendingWebPermissionRequest = null
            }
        }

    // ── Lifecycle ────────────────────────────────────────────────
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

        // Hardware layer giúp render <video> (luồng camera) mượt hơn khi pinch-zoom
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Cho phép cookie (đăng nhập) tồn tại giữa các session
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webViewClient = CropGuardWebViewClient()
        webChromeClient = CropGuardWebChromeClient()
    }

    // ── WebViewClient: điều hướng + lỗi mạng + SSL ─────────────────
    private inner class CropGuardWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val host = uri.host ?: return false

            return when {
                // Domain chính -> load trong WebView
                Config.ALLOWED_HOSTS.any { host.endsWith(it) } -> false

                // Link mailto/tel -> mở app tương ứng
                uri.scheme == "mailto" || uri.scheme == "tel" -> {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }

                // OAuth, link ngoài -> mở Chrome thật
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
            // Chỉ hiện màn hình lỗi nếu lỗi xảy ra ở trang chính (không phải resource phụ như ảnh/script)
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
            // KHÔNG bỏ qua lỗi SSL trong production - bảo mật người dùng
            handler.cancel()
            binding.webView.visibility = View.GONE
            binding.errorView.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            cancelLoadTimeoutWatchdog()
            Toast.makeText(this@MainActivity, "Lỗi chứng chỉ bảo mật, không thể tải trang", Toast.LENGTH_LONG).show()
        }
    }

    // ── WebChromeClient: progress bar + file chooser (camera/gallery) ─
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

        // Bắt buộc cho navigator.mediaDevices.getUserMedia() (camera trực tiếp
        // trong trang web, dùng <video> + canvas, KHÔNG qua <input type="file">).
        // Nếu thiếu override này, web luôn báo "Không thể truy cập camera".
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
     * Mở dialog chọn file. Đọc đúng loại file web yêu cầu (params.acceptTypes)
     * thay vì luôn cố định "image/*" - quan trọng cho tab "Dạy AI" chấp nhận
     * cả PDF lẫn ảnh (accept=".pdf,image/*").
     * Toàn bộ bọc try-catch vì ActivityNotFoundException (máy không có app
     * xử lý MIME type đó) trước đây làm crash toàn bộ app.
     */
    private fun launchImageChooser(params: WebChromeClient.FileChooserParams?) {
        try {
            // Xác định MIME type thực tế web yêu cầu
            val acceptTypes = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
            val mimeType = when {
                acceptTypes.isEmpty() -> "*/*"
                acceptTypes.size == 1 && acceptTypes[0] == "image/*" -> "image/*"
                acceptTypes.any { it == "image/*" } &&
                    acceptTypes.any { it.contains("pdf", ignoreCase = true) } -> "*/*"
                else -> "*/*"
            }
            val wantsImage = acceptTypes.isEmpty() || acceptTypes.any { it.startsWith("image") }

            // Intent camera - chỉ thêm vào nếu web có chấp nhận ảnh
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

            // Intent chọn file từ thư viện/trình quản lý file, đúng MIME type
            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = mimeType
                addCategory(Intent.CATEGORY_OPENABLE)
                if (mimeType == "*/*" && acceptTypes.isNotEmpty()) {
                    // Gợi ý cụ thể hơn cho launcher biết các loại MIME được chấp nhận
                    putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
                }
            }

            val chooserIntent = Intent.createChooser(galleryIntent, "Chọn file").apply {
                if (cameraIntent != null) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }
            }

            fileChooserLauncher.launch(chooserIntent)
        } catch (e: Exception) {
            // Không để bất kỳ lỗi nào ở bước chọn file làm crash cả app
            Toast.makeText(this, "Không thể mở trình chọn file", Toast.LENGTH_SHORT).show()
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
            // Trang vẫn chưa load xong sau LOAD_TIMEOUT_MS -> coi như lỗi mạng, không để app treo
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
