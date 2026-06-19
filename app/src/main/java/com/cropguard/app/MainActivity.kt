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

    private val loadTimeoutHandler = Handler(Looper.getMainLooper())
    private var loadTimeoutRunnable: Runnable? = null
    private val LOAD_TIMEOUT_MS = 20_000L  // 20 giây - nếu trang chưa load xong, coi như lỗi mạng

    // ── Result launchers ─────────────────────────────────────────
    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return@registerForActivityResult

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
            cameraPhotoUri = null
        }

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingCameraPermissionRequest?.invoke()
            } else {
                Toast.makeText(this, "Cần quyền camera để chụp ảnh cây trồng", Toast.LENGTH_SHORT).show()
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
            pendingCameraPermissionRequest = null
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
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = "$userAgentString CropGuardAndroidApp/1.0"
        }

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

            if (isCameraPermissionGranted()) {
                launchImageChooser()
            } else {
                pendingCameraPermissionRequest = { launchImageChooser() }
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return true
        }
    }

    private fun isCameraPermissionGranted(): Boolean =
        packageManager.checkPermission(Manifest.permission.CAMERA, packageName) ==
            PackageManager.PERMISSION_GRANTED

    /** Mở dialog chọn: chụp ảnh mới HOẶC chọn từ thư viện. */
    private fun launchImageChooser() {
        // Intent camera
        val photoFile = createImageFile()
        val cameraIntent: Intent? = photoFile?.let { file ->
            cameraPhotoUri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }

        // Intent chọn ảnh từ thư viện
        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        val chooserIntent = Intent.createChooser(galleryIntent, "Chọn ảnh cây trồng").apply {
            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        fileChooserLauncher.launch(chooserIntent)
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
