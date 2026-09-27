package app.dshpocket

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlin.math.roundToInt

/**
 * Single-screen client for the DeepSeek Harness web UI.
 *
 * The harness is a responsive web app served by another machine, so this shell only has to render
 * it, keep navigation inside the app, keep it clear of the system bars and the keyboard, add the
 * drawer and image gestures the web UI has no touch support for, and keep the session cookie.
 *
 * The entry point is the short address rather than the harness hostname, because the harness mints a
 * one-time login token per process and only the short address hands out the current one. Loading it
 * on every launch is therefore what makes a restarted harness work without any stored credential.
 *
 * Call stack:
 *
 * MainActivity.onCreate
 *   -> {@link MainActivity.buildConsole}         render the remote harness
 *   -> {@link MainActivity.applySystemBarInsets} keep it clear of the bars and the keyboard
 *   -> {@link MainActivity.claimScreenEdgesForGestures} keep the edge swipes for the page
 *
 * Harness page load
 *   -> {@link MainActivity.matchShellToConsole}  tint the bar area to the harness theme
 *   -> {@link DshGestures.script}                add the drawer and image gestures
 */
class MainActivity : AppCompatActivity() {

    private lateinit var console: WebView

    /**
     * Wraps the harness and paints the strips the system bars sit on.
     *
     * Android 15 and later lay every app out edge to edge, so the page would otherwise begin at the
     * very top of the screen and its own header would sit under the status bar clock.
     */
    private lateinit var consoleHost: FrameLayout

    /**
     * Shown over the console until the first page finishes.
     *
     * The shell is a large script bundle, so the app sits on a blank window for several seconds on a
     * phone. Without this the wait is indistinguishable from a hang.
     */
    private var loadingView: android.view.View? = null

    /** The breathing animation on the loading mark, stopped once the page is up. */
    private var loadingAnimator: android.animation.Animator? = null

    /**
     * The colour behind the page, used as the base for any translucent colour the harness paints.
     *
     * Resolved once, because the window background cannot change while the screen exists.
     */
    private val shellBaseColour: Int by lazy {
        val resolved = TypedValue()
        val isColour = theme.resolveAttribute(android.R.attr.windowBackground, resolved, true) &&
            resolved.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
        if (isColour) resolved.data else Color.BLACK
    }

    /** Held between the file chooser opening and its result, because only one can be pending. */
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    /**
     * How many loads in a row have failed on the main frame.
     *
     * Used only to notice that the saved credential pair is wrong. The pair is otherwise sent
     * without asking, so without this a changed password would leave the app failing with no way
     * back to the dialog.
     */
    private var consecutiveLoadFailures = 0

    /**
     * Whether the registered pair was refused by the gate.
     *
     * Set after repeated failures so the enrollment screen comes back with the same pair, which is
     * what lets the owner register it again rather than being left on a failing page.
     */
    private var identityRejected = false

    /** Whether the enrollment dialog is already up, so a second challenge does not stack another. */
    private var enrollmentVisible = false

    /** Whether the key baked into this build has already been spent as a fallback this launch. */
    private var bakedKeyAttempted = false

    /** True while that fallback is in flight, so a code dialog does not compete with it. */
    private var recoveringIdentity = false

    /**
     * The origin this installation was paired against, or null before the first pairing.
     *
     * Held in a field rather than read from storage on each use, because the console address is
     * needed from several callbacks and a pairing that was just written must be visible to them.
     */
    private var pairingBase: String? = null

    /** Flags the pair as refused once enough loads have failed that it can only be the reason. */
    private fun noteLoadFailed() {
        if (!StoredCredentials.exists(this)) return
        consecutiveLoadFailures++
        if (consecutiveLoadFailures < MAX_CONSECUTIVE_LOAD_FAILURES) return
        consecutiveLoadFailures = 0
        identityRejected = true
    }

    /**
     * Registers this device with the key baked into this build. Once per launch.
     *
     * @return true when an attempt was started. False means there is nothing to try, and the
     * caller falls back to asking for a code.
     */
    private fun recoverWithBakedKey(): Boolean {
        if (bakedKeyAttempted) return false
        bakedKeyAttempted = true
        if (BuildConfig.PAIR_BASE.isEmpty() || BuildConfig.PAIR_KEY.isEmpty()) return false
        // The stored pair is about to be replaced, so drop it: leaving it behind would let the
        // next launch load the dead one again before ever reaching this path.
        PairingPrefs.clear(this)
        StoredCredentials.clear(this)
        identityRejected = false
        consecutiveLoadFailures = 0
        recoveringIdentity = true
        enrollSilently(BuildConfig.PAIR_BASE, BuildConfig.PAIR_KEY, BuildConfig.PAIR_BASE.startsWith("http://"))
        return true
    }

    /**
     * Set by the injected "add image" button just before it drives the shell's file input.
     *
     * The accept attribute cannot carry this. That input is rendered by the shell, and a render
     * strips any attribute the shell did not set, so the chooser arrived with no accept types at all
     * and every request opened the document picker. A one-shot flag is read and cleared when the
     * chooser opens instead.
     */
    private var nextPickerIsImage = false

    /**
     * The only bridge the page is given.
     *
     * It exposes a single method that sets one boolean and returns nothing, so nothing on the page
     * can read state or reach the device through it.
     */
    private inner class ChooserBridge {
        @JavascriptInterface
        fun requestImagePicker() {
            nextPickerIsImage = true
        }
    }

    private val fileChooserResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser ?: return@registerForActivityResult
            pendingFileChooser = null
            callback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        console = buildConsole()
        clearShellCacheOnNewBuild()
        // Registered after the field is set: buildConsole builds the WebView but does not assign it.
        console.addJavascriptInterface(ChooserBridge(), "DshHost")
        consoleHost = FrameLayout(this).apply {
            // The harness ships both a light and a dark theme. White matches the light one until the
            // page reports its own colour, which cannot be read any earlier than page load.
            setBackgroundColor(Color.WHITE)
            addView(
                console,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(consoleHost)
        consoleHost.addView(buildSplashView())
        applySystemBarInsets()
        claimScreenEdgesForGestures()

        if (savedInstanceState != null) {
            console.restoreState(savedInstanceState)
        } else {
            startFromPairing(intent)
        }

        // Back closes an open drawer first, then walks the page history, then leaves the app.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // The band claimed for the drawer gestures covers only part of each edge, so a
                    // swipe from anywhere else on an edge arrives here as the system back gesture.
                    console.evaluateJavascript(CLOSE_DRAWER_PROBE) { result ->
                        when {
                            result == "\"1\"" -> Unit
                            console.canGoBack() -> console.goBack()
                            else -> finish()
                        }
                    }
                }
            },
        )
    }

    /**
     * Asks the user for the harness gate's credentials and remembers them on success.
     *
     * The dialog is not cancelable by tapping outside, because dismissing it without an answer
     * would leave the pending challenge unresolved and the page stranded on its error view.
     */
    /**
     * Drops the WebView cache once per installed build.
     *
     * The harness serves its plugin modules under a revision that does not change when a module
     * file does, so a WebView that already holds the old copy keeps using it and a plugin update
     * never arrives on its own. Clearing once when the build changes is what makes one land, and it
     * leaves cookies alone so the harness session survives.
     */
    /**
     * The splash shown while the console loads.
     *
     * The peek animation is a WebGL page rather than a static image, so it needs its own small
     * WebView. That costs a few hundred milliseconds of start-up, which is worth it here: the
     * console takes seconds, and a still image for that long reads as a hang. The progress bar is
     * driven by the console's own `onProgressChanged`, so it reports real progress.
     */
    private var splashProgress: android.widget.ProgressBar? = null

    /** Kept so the exit can make the splash see-through, revealing the console behind it. */
    private var splashAnimation: WebView? = null

    private fun buildSplashView(): android.view.View {
        val density = resources.displayMetrics.density
        val animation = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            loadUrl("file:///android_asset/peek-v7.html")
        }

        splashAnimation = animation
        // The page's own touch handling never fired on the device, so the tap is forwarded here.
        // The page decides whether the point is on the character: it hit-tests the rendered alpha,
        // because the transparent margins of the artwork still belong to the splash view.
        // Ratios rather than pixels keep the mapping valid when CSS size and view size differ.
        // Returning false leaves the touch to the WebView as well.
        animation.setOnTouchListener { view, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val ratioX = (event.x / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                val ratioY = (event.y / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                animation.evaluateJavascript(
                    "window.splashTapAt && window.splashTapAt(${ratioX}*window.innerWidth, ${ratioY}*window.innerHeight)",
                    null,
                )
            }
            false
        }
        val frame = FrameLayout(this).apply {
            // The page paints its own white backdrop, so the overlay stays clear: that is what lets
            // the console show through once the splash fades its backdrop at the end.
            background = null
            isClickable = true
            addView(animation, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        loadingView = frame
        return frame
    }

    /**
     * Real progress, from the console's own load callback.
     *
     * The bulk figure is an estimate: a WebView does not report transferred or remaining bytes, so
     * the remainder is worked out from this measured payload against the reported percentage. It is
     * labelled as approximate on screen for that reason.
     */
    private fun showProgress(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        splashProgress?.progress = clamped
        splashAnimation?.evaluateJavascript(
            "window.splashProgress && window.splashProgress($clamped, $TYPICAL_LOAD_BYTES)", null,
        )
    }

    private fun showLoading() {
        val overlay = loadingView ?: return
        overlay.animate().cancel()
        overlay.visibility = android.view.View.VISIBLE
        overlay.alpha = 1f
        splashProgress?.progress = 0
    }

    /**
     * Ends the splash once the console is up.
     *
     * The character is asked to sink back and the page fades its own white backdrop; the overlay
     * itself is not faded, so what is revealed is the console, not a white rectangle dissolving.
     */
    private fun hideLoading() {
        val overlay = loadingView ?: return
        if (overlay.visibility != android.view.View.VISIBLE) return
        splashProgress?.visibility = android.view.View.GONE
        splashAnimation?.setBackgroundColor(Color.TRANSPARENT)
        splashAnimation?.evaluateJavascript("window.splashLeave && window.splashLeave()", null)
        overlay.postDelayed({
            finishSplash(overlay)
        }, SPLASH_EXIT_MS)
    }

    private fun finishSplash(overlay: android.view.View) {
        runOnUiThread {
            overlay.visibility = android.view.View.GONE
            (overlay as? android.view.ViewGroup)?.let { group ->
                for (index in 0 until group.childCount) {
                    (group.getChildAt(index) as? WebView)?.destroy()
                }
            }
        }
    }

    private fun clearShellCacheOnNewBuild() {
        val seen = getSharedPreferences("dsh-pocket-build", MODE_PRIVATE)
        if (seen.getInt("versionCode", 0) == BuildConfig.VERSION_CODE) return
        seen.edit().putInt("versionCode", BuildConfig.VERSION_CODE).apply()
        console.clearCache(true)
    }

    /**
     * The picker to open for a file chooser request.
     *
     * An image request opens the gallery. The shell's own attachment button produces a document
     * picker that starts in a file list, which turns picking a photo into a hunt through folders;
     * the injected "add image" button marks its request as an image one so it lands here instead.
     * The request falls back to the shell's own intent when the device has no gallery to open.
     */
    private fun pickerFor(params: WebChromeClient.FileChooserParams): Intent {
        val imageRequest = nextPickerIsImage ||
            params.acceptTypes.any { it.startsWith("image/") }
        nextPickerIsImage = false
        if (imageRequest) {
            // The system photo picker first. The older ACTION_PICK against the media store is not
            // handled by every vendor's gallery, and its failure is invisible: the chooser simply
            // never appears and the shell's document picker opens instead, which looks like the
            // image button being ignored. The photo picker is part of the platform from Android 13.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
            }
            return Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
        }
        return params.createIntent()
    }

    private fun askForCredentials(handler: HttpAuthHandler, host: String) {
        val density = resources.displayMetrics.density
        val inset = (16 * density).toInt()
        val saved = StoredCredentials.load(this, host)

        val userField = EditText(this).apply {
            hint = getString(R.string.sign_in_user)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(saved?.userName.orEmpty())
        }
        val passwordField = EditText(this).apply {
            hint = getString(R.string.sign_in_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset / 2, inset, 0)
            addView(userField)
            addView(passwordField)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sign_in_title, host))
            .setMessage(R.string.sign_in_message)
            .setView(fields)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val userName = userField.text.toString()
                val password = passwordField.text.toString()
                // A false return means the pair could not be stored safely, not that it is unusable:
                // this load proceeds either way, and the user is asked again next time. The point is
                // that failing to encrypt degrades to "asked again", never to "written in the clear".
                if (!StoredCredentials.save(this, host, userName, password)) {
                    Log.w(TAG, "pair not persisted; it will be asked for again next launch")
                }
                handler.proceed(userName, password)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // The pair is deliberately kept: cancelling by mistake must not throw away
                // credentials that are otherwise fine.
                handler.cancel()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows the passcode entry for this device.
     *
     * The device's own credential is generated here and kept whatever happens next, so a passcode
     * that is refused can be retried without the device changing identity: the credential that was
     * registered must be the one the device keeps using.
     */
    /**
     * Picks what to load at start-up: a scanned link, a remembered pairing, or the setup dialog.
     *
     * A scanned link wins over anything stored, because opening one is an explicit statement about
     * which harness this phone should now belong to. The link's own address replaces the remembered
     * one only after a code from that link has been spent, so a scan that the owner abandons leaves
     * the existing pairing untouched.
     */
    private fun startFromPairing(intent: Intent?) {
        val scanned = Pairing.linkFrom(intent?.data)
        val stored = PairingPrefs.load(this)
        pairingBase = stored?.base

        if (scanned != null) {
            askForPairingCode(scanned.base, scanned.code, scanned.lanMode)
            return
        }
        if (stored != null) {
            console.loadUrl(stored.origin)
            // 令牌会在重装/恢复备份/长期不用之后被 Firebase 换掉，而"没登记成功"是静默的：
            // 服务端还在往失效的地址发。所以每次启动都补一次。
            startPush(stored.base, stored.deviceToken)
            return
        }
        // 包里自带的是一个地址**和一把一次性配对钥匙**。用户什么都不用填 —— 打开就去
        // 兑现一次，拿回属于自己的设备令牌。
        //
        // 钥匙必须真的发出去。这里曾经什么都不发，靠服务端"配对窗口开着就放行"来配对；
        // 那是鉴权绕过：窗口开着不是秘密，入口公网可达时谁先请求谁就能拿到设备令牌。
        // 现在服务端只认配对码，所以包里没烘钥匙（或钥匙已过期/已用过）时就退回手动配对，
        // 而不是靠放宽服务端来兜底。
        if (BuildConfig.PAIR_BASE.isNotEmpty() && BuildConfig.PAIR_KEY.isNotEmpty()) {
            enrollSilently(BuildConfig.PAIR_BASE, BuildConfig.PAIR_KEY, BuildConfig.PAIR_BASE.startsWith("http://"))
            return
        }
        showPairingSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A link opened while the app is already up replaces the page with the paired harness.
        val scanned = Pairing.linkFrom(intent.data) ?: return
        askForPairingCode(scanned.base, scanned.code, scanned.lanMode)
    }

    /**
     * Asks where the harness is.
     *
     * The address is free text because it can be a public hostname, a LAN address, or a literal
     * address including an IPv6 one. The LAN switch only decides how a bare host is completed — with
     * `http://` instead of `https://` — because a local deployment has no certificate and the
     * failure that produces says nothing useful.
     */
    private fun showPairingSetup() {
        if (enrollmentVisible) return
        enrollmentVisible = true

        val density = resources.displayMetrics.density
        val inset = (16 * density).toInt()
        val remembered = PairingPrefs.load(this)
        val field = EditText(this).apply {
            hint = getString(R.string.pair_address_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(remembered?.base.orEmpty())
            requestFocus()
        }
        val lanSwitch = android.widget.CheckBox(this).apply {
            text = getString(R.string.pair_lan)
            isChecked = remembered?.lanMode ?: false
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset / 2, inset, 0)
            addView(field)
            addView(lanSwitch)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(R.string.pair_message)
            .setView(wrapper)
            .setPositiveButton(R.string.pair_connect) { _, _ ->
                enrollmentVisible = false
                val base = PairingPrefs.normalize(field.text.toString(), lanSwitch.isChecked)
                if (base == null) {
                    toast(R.string.pair_address_invalid)
                    showPairingSetup()
                    return@setPositiveButton
                }
                askForPairingCode(base, null, lanSwitch.isChecked)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Asks for the pairing code, and spends it.
     *
     * `preset` carries the code from a scanned link, which is the whole point of scanning: the owner
     * never reads it. When it is null the dialog is the manual path.
     */
    private fun askForPairingCode(base: String, preset: String?, lanMode: Boolean) {
        if (enrollmentVisible) return
        enrollmentVisible = true

        val density = resources.displayMetrics.density
        val inset = (16 * density).toInt()
        val field = EditText(this).apply {
            hint = getString(R.string.pair_code_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(preset.orEmpty())
            requestFocus()
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset / 2, inset, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_title)
            .setMessage(getString(R.string.pair_code_message, base))
            .setView(wrapper)
            .setPositiveButton(R.string.pair_submit) { _, _ ->
                enrollmentVisible = false
                spendCode(base, field.text.toString().trim(), lanMode)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                enrollmentVisible = false
                if (pairingBase == null) showPairingSetup()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 用包里自带的地址和一次性钥匙静默配对一次，不打断用户。
     *
     * 失败时不弹输入码的框，而是明说下一步该做什么 —— 这条路上用户从没看到过"配对码"
     * 这个东西，弹一个要他填码的框只会让人困惑。最常见的失败是钥匙已经过期或用过了
     * （构建时烘进去的码也有一小时有效期），那也是所有者重新生成一个码就能解决的事。
     */
    private fun enrollSilently(base: String, key: String, lanMode: Boolean) {
        val device = DeviceIdentity.create().userName
        Thread {
            val outcome = Pairing.redeem(base, key, device)
            runOnUiThread {
                when (outcome) {
                    is Pairing.Result.Admitted -> {
                        PairingPrefs.save(this, base, lanMode, outcome.token.orEmpty())
                        pairingBase = base
                        identityRejected = false
                        consecutiveLoadFailures = 0
                        recoveringIdentity = false
                        // The fallback worked. A later revocation should be able to use it again
                        // rather than being refused because it was already tried once.
                        bakedKeyAttempted = false
                        adoptDeviceToken(base, outcome.token.orEmpty())
                        console.loadUrl(base)
                        startPush(base, outcome.token.orEmpty())
                    }
                    is Pairing.Result.Refused -> {
                        recoveringIdentity = false
                        AlertDialog.Builder(this)
                            .setTitle(R.string.pair_failed_title)
                            .setMessage(getString(R.string.pair_silent_failed, base, outcome.message))
                            .setPositiveButton(android.R.string.ok) { _, _ -> showPairingSetup() }
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun spendCode(base: String, code: String, lanMode: Boolean) {
        if (code.isEmpty()) {
            askForPairingCode(base, null, lanMode)
            return
        }
        val device = DeviceIdentity.create().userName
        Thread {
            val outcome = Pairing.redeem(base, code, device)
            runOnUiThread {
                when (outcome) {
                    is Pairing.Result.Admitted -> {
                        // Written only now: a code that was refused must not leave the phone
                        // pointed at a harness it never paired with.
                        PairingPrefs.save(this, base, lanMode)
                        pairingBase = base
                        identityRejected = false
                        consecutiveLoadFailures = 0
                        // The device token is spent against the address this phone paired with, so
                        // the entry it keeps using is the same one it just proved it can reach.
                        val granted = outcome.token
                        val target = if (granted != null) {
                            adoptDeviceToken(base, granted)
                            base
                        } else {
                            outcome.url
                        }
                        console.loadUrl(target)
                    }
                    is Pairing.Result.Refused -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.pair_failed_title)
                            .setMessage(outcome.message)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                askForPairingCode(base, null, lanMode)
                            }
                            .show()
                    }
                }
            }
        }.start()
    }

    /**
     * 打开推送：初始化 Firebase、取令牌、报给 harness。
     *
     * 整个过程是"能成就成"的：任何一步失败（包里没注入 Firebase 配置、手机上没 Google
     * 服务、网络不通）都只是收不到通知，绝不该影响配对和用控制台。所以这里不弹任何错误，
     * 只写日志 —— 一个弹窗会让"推送没配好"看起来像"App 坏了"。
     */
    /**
     * 取这台设备的令牌。
     *
     * 优先用配对时存下来的那份。但**旧配对没有存过**（那个字段是后加的），所以退化到从
     * WebView 的 cookie 里取 —— 设备令牌本来就以 cookie 形式待在那儿。
     * 早先没有这层兜底时，老用户永远登记不上推送令牌，而且失败是静默的。
     */
    private fun deviceTokenFor(base: String): String {
        val stored = PairingPrefs.load(this)?.deviceToken.orEmpty()
        if (stored.isNotEmpty())
            return stored
        val cookie = CookieManager.getInstance().getCookie(base).orEmpty()
        return cookie.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("dsh-pocket-pair=") }
            ?.substringAfter('=')
            .orEmpty()
    }

    /**
     * Whether a basic-auth challenge came from the harness this device paired with.
     *
     * Compares hosts only, deliberately: the scheme and port can legitimately differ between the
     * address the app navigates to and the authority the challenge arrives on (a reverse proxy
     * terminating TLS, a redirect to the LAN address). The host is what the credentials belong to.
     *
     * An unparseable or missing base returns false, so a challenge is never answered with credentials
     * that have no confirmed owner.
     */
    private fun isPairedHost(base: String?, host: String): Boolean {
        if (base.isNullOrEmpty()) return false
        return try {
            java.net.URI(base).host?.equals(host, ignoreCase = true) == true
        } catch (error: Exception) {
            Log.w(TAG, "pairing base is not a URL, refusing to answer a challenge", error)
            false
        }
    }

    /**
     * Hand the device token to the WebView as a cookie instead of putting it in the address.
     *
     * The gate also accepts `?device=<token>` and immediately redirects to the same URL with the token
     * moved into a cookie — but that first request still carries it in the request line, and anything
     * in front of the gate writes full request lines to an access log. A reverse proxy is the common
     * case, and it is exactly the deployment that has one.
     *
     * `Secure` is added only for https, because on a plain-http LAN address a Secure cookie would be
     * dropped and the pairing would silently fail to take.
     */
    private fun adoptDeviceToken(base: String, token: String) {
        val secure = if (base.startsWith("https://")) "; Secure" else ""
        val cookie = "dsh-pocket-pair=$token; Path=/; HttpOnly; SameSite=Strict$secure"
        CookieManager.getInstance().setCookie(base, cookie)
        CookieManager.getInstance().flush()
    }

    private fun startPush(base: String, deviceToken: String) {
        if (BuildConfig.FIREBASE_PROJECT_ID.isEmpty()
            || BuildConfig.FIREBASE_APP_ID.isEmpty()
            || BuildConfig.FIREBASE_API_KEY.isEmpty()
            || BuildConfig.FIREBASE_SENDER_ID.isEmpty()
        ) {
            Log.i(TAG, "push: 这个包没有注入 Firebase 配置，跳过")
            return
        }

        // Android 13 起通知要用户授权。没授权的话推送到了也不会显示，所以先要。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        try {
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                val options = com.google.firebase.FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
                com.google.firebase.FirebaseApp.initializeApp(this, options)
            }
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    // Read `isSuccessful` before touching `result`. Reading `result` on a failed task
                    // throws, so the old order — result first, flag second — threw on the main thread
                    // and took the whole app down whenever a token could not be fetched: a wrong
                    // Firebase configuration, no Google services, no network. The owner sees a crash
                    // on launch and cannot tell it apart from "cannot connect".
                    if (!task.isSuccessful) {
                        Log.w(TAG, "push: 取令牌失败", task.exception)
                        return@addOnCompleteListener
                    }
                    val token = task.result
                    if (token.isNullOrEmpty()) {
                        Log.w(TAG, "push: 取到空令牌")
                        return@addOnCompleteListener
                    }
                    PushRegistration.stash(this, token)
                    reportPushToken(base, deviceToken)
                }
        }
        catch (error: Throwable) {
            Log.w(TAG, "push: 初始化失败", error)
        }
    }

    /** 把令牌报给 harness。失败不重试——下次启动会再走一遍 startPush。 */
    private fun reportPushToken(base: String, deviceToken: String) {
        val token = PushRegistration.pending(this) ?: return
        Thread {
            if (PushRegistration.register(base, token, deviceToken)) {
                PushRegistration.markRegistered(this)
                Log.i(TAG, "push: 令牌已登记")
            } else {
                Log.w(TAG, "push: 令牌登记失败，下次启动重试")
            }
        }.start()
    }

    private fun toast(message: Int) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * The harness stopped accepting this device, so ask for a fresh code.
     *
     * A refusal means the session this phone was riding is gone — the harness was restarted with a
     * different credential store, or the pairing was revoked. The code that admitted it cannot be
     * replayed, so the owner mints a new one.
     */
    private fun showEnrollment() {
        val base = pairingBase ?: PairingPrefs.load(this)?.base
        if (base == null) {
            showPairingSetup()
        } else {
            askForPairingCode(base, null, PairingPrefs.load(this)?.lanMode ?: false)
        }
    }

    /**
     * Shows the code dialog, unless the baked-key fallback is already running.
     *
     * That fallback re-registers this device by itself. A dialog raised over it would ask the owner
     * to go and find a code on a computer while the app is busy using the one from the package they
     * just installed.
     */
    private fun offerEnrollment() {
        if (recoveringIdentity) return
        showEnrollment()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        console.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        consoleHost.post(themeWatcher)
    }

    override fun onPause() {
        consoleHost.removeCallbacks(themeWatcher)
        super.onPause()
    }

    /**
     * Re-reads the harness theme while the screen is visible.
     *
     * The harness owns a light and a dark theme and switches between them inside its own script, so
     * a toggle changes the page without loading it and would never reach
     * {@link WebViewClient#onPageFinished} a second time. Polling keeps the bar colour correct after
     * such a toggle, and runs only while the screen is resumed.
     */
    private val themeWatcher = object : Runnable {
        override fun run() {
            matchShellToConsole()
            consoleHost.postDelayed(this, THEME_POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildConsole(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        // The harness is an application, not a document, so zooming only gets in the way.
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false

        // The session cookie must survive restarts, otherwise every launch would need the token
        // again even while the harness has not restarted.
        CookieManager.getInstance().setAcceptCookie(true)

        webViewClient = object : WebViewClient() {
            /**
             * Answers the harness gate's basic-auth challenge.
             *
             * The gate admits a short list of source addresses without a password and challenges
             * every other source. A phone on mobile data is never on that list, and a WebView that
             * does not answer a challenge drops the load, which the page reports as
             * `ERR_CONNECTION_CLOSED`.
             *
             * **The host is checked first, and that check is the point of this method.** WebView
             * calls it for *whatever* asks for credentials, not just the page the user is looking at.
             * Without the check, any site the WebView happens to load — a link in a message, a
             * redirect — could raise a challenge and be handed the harness password.
             *
             * A challenge that arrives while a saved pair is still in flight means the gate rejected
             * that pair, so the user is asked again instead of being stuck with a wrong password.
             */
            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String,
                realm: String,
            ) {
                val base = pairingBase ?: PairingPrefs.load(this@MainActivity)?.base
                if (!isPairedHost(base, host)) {
                    // Not our harness. Refuse without comment: showing the enrollment screen here
                    // would tell the user to go and pair in the middle of whatever other page raised
                    // the challenge, which is both wrong and confusing.
                    handler.cancel()
                    return
                }
                // Loaded for this host, so a pair stored for a different harness is not offered.
                val saved = StoredCredentials.load(this@MainActivity, host)
                if (saved != null && !identityRejected) {
                    // A registered device answers silently, on every network, with nothing left to
                    // tell a rejection apart from a fresh challenge.
                    handler.proceed(saved.userName, saved.password)
                    return
                }
                // Nothing stored yet, or the stored pair was refused: answering again would only
                // repeat the refusal. The enrollment screen is shown because it is the only thing
                // on screen that can lead anywhere.
                handler.cancel()
                offerEnrollment()
            }

            // The harness picks its theme in its own script, so the colour only becomes readable
            // once the document has run.
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                noteLoadFailed()
                // A page that cannot be reached at all is also a dead end without this: the dialog
                // is the only thing on screen that can lead anywhere.
                offerEnrollment()
            }

            /**
             * Counts a refused main-frame load, which is what a wrong saved password looks like.
             *
             * A rejected credential answers `401`, which is an HTTP error rather than a network
             * one, so `onReceivedError` never fires for it. Counting only there meant a wrong pair
             * was retried forever and the dialog never came back.
             */
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse,
            ) {
                if (!request.isForMainFrame || response.statusCode != 401) return
                noteLoadFailed()
                // A 401 while this install already holds a pair means that pair was refused — the
                // device was revoked, or this build points at a different harness.
                //
                // Before asking the owner to go and find a code on a computer, register again with
                // the key baked into the package this app was installed from: that is exactly what
                // it is for, and re-installing a downloaded package is the likeliest reason this
                // install is here at all. Without this an install landing on top of an older one
                // keeps its stored pair, never reaches the baked key, and dead-ends on a dialog.
                if (recoverWithBakedKey()) return
                // Nothing baked to try. A refusal still offers a way forward: Chromium does not
                // reliably raise an auth challenge for one, so waiting for that callback left the
                // owner looking at an error page with nothing to press.
                offerEnrollment()
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showLoading()
            }


            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                hideLoading()
                consecutiveLoadFailures = 0
                matchShellToConsole()
                // 在这里补一次推送登记：页面加载完时设备 cookie 一定已经种下了，
                // 所以"旧配对没存令牌"那条兜底路才走得通。startPush 内部是幂等的 ——
                // 令牌已经登记过就不会重复提交。
                PairingPrefs.load(this@MainActivity)?.let { paired ->
                    startPush(paired.base, deviceTokenFor(paired.base))
                }
                // Re-injected on every load, because a navigation replaces the document along with
                // the listeners. The script returns early when it is already installed.
                view.evaluateJavascript(DshGestures.script, null)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                showProgress(newProgress)
            }

            // The harness attaches files through an <input type="file">, which a bare WebView
            // ignores unless the chooser is forwarded to the system.
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = callback
                return try {
                    fileChooserResult.launch(pickerFor(params))
                    true
                } catch (galleryFailure: ActivityNotFoundException) {
                    // Nothing on this device answers the image request, so the shell's own picker is
                    // used instead of leaving the owner with a dead button.
                    Log.w(TAG, "no image picker, using the shell's", galleryFailure)
                    try {
                        fileChooserResult.launch(params.createIntent())
                        true
                    } catch (error: Exception) {
                        Log.w(TAG, "cannot open file chooser", error)
                        pendingFileChooser = null
                        false
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "cannot open file chooser", error)
                    pendingFileChooser = null
                    false
                }
            }
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Keeps the page clear of the status and navigation bars, and of the on-screen keyboard.
     *
     * Padding the host moves every descendant, including the harness's fixed-position header and
     * composer, which padding applied inside the page could not do. The insets are consumed so the
     * page never adds its own `env(safe-area-inset-*)` padding on top.
     *
     * The keyboard inset is included because the window is laid out edge to edge, so the system does
     * not resize it when the keyboard opens. Without this the composer stayed underneath the
     * keyboard and could not be seen while typing, even though the activity declares `adjustResize`.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(consoleHost) { host, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            host.updatePadding(
                bars.left,
                bars.top,
                bars.right,
                // While the keyboard is up its inset already contains the navigation bar, so the
                // larger of the two is used rather than their sum.
                maxOf(bars.bottom, keyboard.bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Claims a strip along each screen edge for the harness's drawer gestures.
     *
     * Android 10 and later reserve both edges for the system back gesture, and the system consumes
     * such a swipe before the page ever sees it. Without this the drawer gestures would only work
     * from further inside the screen, where they stop feeling like edge swipes. The strip is narrow,
     * so the back gesture still works over the rest of each edge.
     *
     * Only a band can be claimed, not the full height. The system keeps roughly the first 200dp of
     * exclusion along an edge and discards the rest of the set, which was measured on the emulator:
     * five stacked slices covering the full height were ignored entirely, so a swipe from the left
     * edge closed the app, while a single 190dp band was honoured and opened the drawer. A swipe
     * outside the band still reaches the system back gesture, which is why
     * {@link MainActivity.console} also answers back by closing an open drawer.
     */
    private fun claimScreenEdgesForGestures() {
        console.addOnLayoutChangeListener { view, left, _, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val density = resources.displayMetrics.density
            val strip = (EDGE_GESTURE_STRIP_DP * density).toInt()
            val band = (EDGE_GESTURE_SLICE_DP * density).toInt().coerceAtLeast(1)
            // Only one band of about 200dp is accepted along an edge before the system discards the
            // whole set, so the claim is a single band rather than the full height. It sits in the
            // middle, where a thumb naturally starts a drawer swipe.
            val bandTop = (height / 2 - band / 2).coerceAtLeast(0)
            val bandBottom = (bandTop + band).coerceAtMost(height)
            ViewCompat.setSystemGestureExclusionRects(
                view,
                listOf(
                    Rect(0, bandTop, strip, bandBottom),
                    Rect(width - strip, bandTop, width, bandBottom),
                ),
            )
        }
    }

    /**
     * Tints the bar area with the colour the page is actually painting.
     *
     * A fixed colour would be wrong half the time, because the harness ships a light and a dark theme
     * and chooses between them itself. The same colour decides the bar icon tint, since dark icons
     * need a light background.
     *
     * The colour is read from whatever element covers the top edge of the page, then from its
     * ancestors, because the harness paints its background on a wrapper rather than on `body`.
     */
    private fun matchShellToConsole() {
        console.evaluateJavascript(
            "(function () {" +
                "var el = document.elementFromPoint(Math.floor(window.innerWidth / 2), 1);" +
                "var translucent = null;" +
                "while (el) {" +
                "var colour = getComputedStyle(el).backgroundColor;" +
                "if (colour && colour !== 'rgba(0, 0, 0, 0)' && colour !== 'transparent') {" +
                "if (colour.indexOf('rgba(') !== 0) return colour;" +
                "if (parseFloat(colour.slice(colour.lastIndexOf(',') + 1)) >= 1) return colour;" +
                "if (!translucent) translucent = colour;" +
                "}" +
                "el = el.parentElement;" +
                "}" +
                "return translucent;" +
                "})()",
        ) { value ->
            val colour = parseCssColor(value)
            if (colour == null) {
                // Leaving the previous colour alone is safer than snapping to a default: the top
                // edge can be briefly transparent while the harness swaps views.
                Log.w(TAG, "harness background colour not usable: $value")
                return@evaluateJavascript
            }
            consoleHost.setBackgroundColor(colour)
            val darkIcons = Color.luminance(colour) > 0.5f
            WindowCompat.getInsetsController(window, consoleHost).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
        }
    }

    /**
     * Reads the `rgb()`/`rgba()` value a WebView returns.
     *
     * A translucent value is flattened against {@link shellBaseColour}, because what the bar area
     * shows is the composite, not the translucent layer on its own.
     *
     * @return the colour, or null when the value is invisible or is not a form this shell knows,
     *   because a wrong guess would be worse than the previous bar colour.
     */
    private fun parseCssColor(value: String?): Int? {
        val channels = value?.removeSurrounding("\"")
            ?.let { Regex("""rgba?\(([^)]+)\)""").find(it) }
            ?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim() }
            ?: return null
        if (channels.size < 3) return null
        val red = channels[0].toIntOrNull() ?: return null
        val green = channels[1].toIntOrNull() ?: return null
        val blue = channels[2].toIntOrNull() ?: return null
        val alpha = channels.getOrNull(3)?.toFloatOrNull() ?: 1f
        if (alpha <= 0f) return null
        if (alpha >= 1f) return Color.rgb(red, green, blue)
        return Color.rgb(
            blendOver(red, Color.red(shellBaseColour), alpha),
            blendOver(green, Color.green(shellBaseColour), alpha),
            blendOver(blue, Color.blue(shellBaseColour), alpha),
        )
    }

    /** Applies the `source-over` rule to one colour channel. */
    private fun blendOver(foreground: Int, background: Int, alpha: Float): Int =
        (foreground * alpha + background * (1f - alpha)).roundToInt()

    companion object {
        private const val TAG = "DshPocket"

        /**
         * How often the page theme is re-read while the screen is visible.
         *
         * Long enough to cost nothing noticeable, short enough that a theme toggle looks immediate.
         */
        const val THEME_POLL_INTERVAL_MS = 2_000L

        /**
         * Width of the edge strip reserved for the harness's drawer gestures.
         *
         * Wide enough to start a swipe comfortably at the edge, narrow enough that the system back
         * gesture still works everywhere else.
         */
        const val EDGE_GESTURE_STRIP_DP = 32

        /**
         * Height of the one exclusion band claimed along each edge.
         *
         * This is a ceiling imposed by the system, not a preference: see
         * {@link MainActivity.claimScreenEdgesForGestures}. It is kept below the documented 200dp
         * limit so that rounding cannot push the band over it.
         */
        const val EDGE_GESTURE_SLICE_DP = 190

        /**
         * Asks {@link DshGestures.script} to close an open drawer.
         *
         * Returns the string `"1"` when a drawer was open and has been asked to close, `"0"`
         * otherwise, including before the page has installed the hook.
         */
        /**
         * Roughly what a cold console load transfers, used only to turn the reported percentage
         * into an approximate remainder for the splash. Measured on this host: the plugin bundle is
         * about 4.2 MB compressed and the rest of the page adds a few hundred KB.
         */
        const val TYPICAL_LOAD_BYTES = 4_600_000

        /** How long the character takes to sink back, plus the backdrop fade, before removal. */
        const val SPLASH_EXIT_MS = 320L

        /** Failed loads in a row after which a saved credential pair is treated as wrong. */
        const val MAX_CONSECUTIVE_LOAD_FAILURES = 3

        /** 通知权限的请求码。只有 Android 13+ 会用到。 */
        const val REQUEST_NOTIFICATIONS = 1001

        const val CLOSE_DRAWER_PROBE = "(window.__dshDrawers && window.__dshDrawers.closeAny()) ? '1' : '0'"
    }
}
