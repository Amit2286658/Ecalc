package com.ecalc

import android.content.Context
import android.util.Log
import com.mathengine.MathEngineBridge
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Currency Converter & Host Extern Provider for the C11 Math Engine (MEP).
 *
 * Uses a multi-provider free, no-API-key exchange rate pipeline with automatic fallback,
 * on-disk caching (`filesDir/currency_rates_cache.json`), and an offline baseline table:
 * 1. **ExchangeRate-API Open Access** (`https://open.er-api.com/v6/latest/USD`) —
 *    160+ ISO 4217 currencies, no API key required.
 * 2. **fawazahmed0/exchange-api** (`https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json`
 *    with Cloudflare fallback `https://latest.currency-api.pages.dev/v1/currencies/usd.json`) —
 *    200+ currencies, no API key or rate limits.
 * 3. **Frankfurter API** (`https://api.frankfurter.dev/v1/latest?base=USD` and
 *    `https://api.frankfurter.app/latest?from=USD`) —
 *    Open-source central bank reference rates, no API key required.
 *
 * Hooks into the native C11 engine via [MathEngineBridge.nativeRegisterExtern] for:
 * - `@currency_convert(amount, "EUR")` -> converts `amount` from `EUR` into `USD`
 *   (powers postfix currency units like `100 USD in INR` and `50 EUR in GBP`)
 * - `@currency_convert(amount, "USD", "INR")` -> converts `amount` from `USD` to `INR`
 * - `@fx_rate("USD", "INR")` / `@currency_rate("USD", "INR")` / `@exchange_rate("USD", "INR")`
 */
object CurrencyConverter {

    private const val TAG = "CurrencyConverter"
    private const val CACHE_FILE_NAME = "currency_rates_cache.json"
    private const val CACHE_TTL_MS = 30L * 60L * 1000L // 30 minutes
    private const val CONNECT_TIMEOUT_MS = 3_500L
    private const val READ_TIMEOUT_MS = 4_500L
    private const val SYNC_WAIT_TIMEOUT_MS = 4_500L

    /** Extern names registered on every native C11 Math Engine instance. */
    val EXTERN_NAMES = listOf(
        "currency_convert",
        "convert_currency",
        "fx_rate",
        "currency_rate",
        "exchange_rate",
        "rate_to_usd",
        "rate_from_usd"
    )

    private const val OPEN_ER_API_URL = "https://open.er-api.com/v6/latest/USD"
    private const val FAWAZ_CDN_URL =
        "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json"
    private const val FAWAZ_PAGES_URL =
        "https://latest.currency-api.pages.dev/v1/currencies/usd.json"
    private const val FRANKFURTER_DEV_URL = "https://api.frankfurter.dev/v1/latest?base=USD"
    private const val FRANKFURTER_APP_URL = "https://api.frankfurter.app/latest?from=USD"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val networkExecutor = Executors.newCachedThreadPool()
    private val refreshInProgress = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastFetchTimeMs: Long = 0L

    @Volatile
    var lastProviderName: String = "Offline Baseline"
        private set

    /**
     * Stores units of `CURRENCY` per `1.0 USD` (e.g. `USD = 1.0`, `INR = 84.10`, `EUR = 0.92`).
     */
    private val ratesPerUsd = ConcurrentHashMap<String, Double>()

    private val symbolAliases = mapOf(
        "$" to "USD",
        "US$" to "USD",
        "€" to "EUR",
        "£" to "GBP",
        "₹" to "INR",
        "¥" to "JPY",
        "₩" to "KRW",
        "₽" to "RUB",
        "₺" to "TRY",
        "₪" to "ILS",
        "₫" to "VND",
        "฿" to "THB",
        "₱" to "PHP",
        "₦" to "NGN",
        "₴" to "UAH",
        "₸" to "KZT",
        "R$" to "BRL",
        "C$" to "CAD",
        "A$" to "AUD",
        "S$" to "SGD",
        "HK$" to "HKD",
        "NZ$" to "NZD"
    )

    private val pairPattern = Regex(
        "^\\s*([A-Za-z$€£₹¥₩₽₺₪₫฿₱₦₴₸]{1,5})\\s*(?:->|/|:|_|\\s+to\\s+|\\s+in\\s+)\\s*([A-Za-z$€£₹¥₩₽₺₪₫฿₱₦₴₸]{1,5})\\s*$",
        RegexOption.IGNORE_CASE
    )

    init {
        seedBaselineRates()
    }

    private fun seedBaselineRates() {
        val defaults = mapOf(
            "USD" to 1.0,
            "EUR" to 0.923,
            "GBP" to 0.772,
            "INR" to 84.08,
            "JPY" to 152.40,
            "CNY" to 7.12,
            "AUD" to 1.52,
            "CAD" to 1.39,
            "CHF" to 0.865,
            "HKD" to 7.77,
            "SGD" to 1.32,
            "NZD" to 1.67,
            "KRW" to 1380.0,
            "SEK" to 10.65,
            "NOK" to 10.98,
            "DKK" to 6.88,
            "MXN" to 20.02,
            "BRL" to 5.76,
            "ZAR" to 17.65,
            "RUB" to 97.0,
            "TRY" to 34.28,
            "AED" to 3.6725,
            "SAR" to 3.75,
            "THB" to 33.80,
            "MYR" to 4.38,
            "IDR" to 15720.0,
            "PHP" to 58.10,
            "PLN" to 4.01,
            "TWD" to 32.05,
            "VND" to 25280.0,
            "EGP" to 48.75,
            "PKR" to 277.8,
            "BDT" to 119.5,
            "LKR" to 293.0,
            "NPR" to 134.5,
            "NGN" to 1640.0,
            "KES" to 129.0,
            "GHS" to 16.2,
            "ARS" to 990.0,
            "CLP" to 960.0,
            "COP" to 4320.0,
            "PEN" to 3.76,
            "ILS" to 3.72,
            "QAR" to 3.64,
            "KWD" to 0.307,
            "BHD" to 0.376,
            "OMR" to 0.385,
            "CZK" to 23.4,
            "HUF" to 374.0,
            "RON" to 4.59,
            "BGN" to 1.805,
            "ISK" to 138.0,
            "UAH" to 41.3,
            "KZT" to 488.0,
            "MAD" to 9.88
        )
        for ((k, v) in defaults) {
            ratesPerUsd.putIfAbsent(k, v)
        }
    }

    /**
     * Initializes disk cache and starts an asynchronous background fetch of live exchange rates.
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        loadCachedRatesFromDisk(app)
        refreshRatesAsync()
    }

    /**
     * Registers the currency conversion host extern callbacks on the given C11 engine [handle].
     */
    fun registerWithEngine(handle: Long): Boolean {
        if (handle == 0L) return false
        var allOk = true
        for (extName in EXTERN_NAMES) {
            val ok = runCatching {
                MathEngineBridge.nativeRegisterExtern(handle, extName, externCallback)
            }.getOrDefault(false)
            if (!ok) allOk = false
        }
        return allOk
    }

    /**
     * Callback invoked by JNI `jni_bridge_cb` whenever a `.math` extension calls
     * `@currency_convert(...)`, `@fx_rate(...)`, etc.
     */
    val externCallback = MathEngineBridge.ExternCallback { name, numbers, strings ->
        evaluateExternCall(name ?: "currency_convert", numbers, strings)
    }

    fun evaluateExternCall(
        name: String,
        numbers: DoubleArray?,
        strings: Array<String>?
    ): Double {
        ensureFreshRatesBounded()

        val nums = numbers ?: DoubleArray(0)
        val cleanStrings = strings
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val amount = if (nums.isNotEmpty()) nums[0] else 1.0
        if (amount.isNaN() || amount.isInfinite()) return Double.NaN

        val lowerName = name.lowercase(Locale.US)
        if (lowerName == "rate_from_usd" && cleanStrings.size == 1) {
            val r = ratePerUsd(cleanStrings[0])
            return if (r.isNaN()) Double.NaN else amount * r
        }

        return when {
            cleanStrings.size >= 2 -> {
                convert(amount, cleanStrings[0], cleanStrings[1])
            }
            cleanStrings.size == 1 -> {
                val single = cleanStrings[0]
                val pairMatch = pairPattern.matchEntire(single)
                if (pairMatch != null) {
                    val fromCode = pairMatch.groupValues[1]
                    val toCode = pairMatch.groupValues[2]
                    convert(amount, fromCode, toCode)
                } else {
                    // Single currency code: returns `amount * rateToUsd(code)` (value in USD)
                    // as required by `[currency]` postfix unit operators (`100 EUR in INR`).
                    val toUsd = rateToUsd(single)
                    if (toUsd.isNaN()) Double.NaN else amount * toUsd
                }
            }
            else -> Double.NaN
        }
    }

    /**
     * Normalizes a currency code or symbol (e.g. `"usd"`, `"$"`, `"₹"`) to uppercase ISO 4217.
     */
    fun normalizeCurrencyCode(raw: String): String {
        val trimmed = raw.trim()
        symbolAliases[trimmed]?.let { return it }
        symbolAliases[trimmed.uppercase(Locale.US)]?.let { return it }
        return trimmed.uppercase(Locale.US)
    }

    /**
     * Returns how many units of [code] equal `1.0 USD`, or `Double.NaN` if unknown.
     */
    fun ratePerUsd(code: String): Double {
        val norm = normalizeCurrencyCode(code)
        if (norm == "USD") return 1.0
        return ratesPerUsd[norm] ?: Double.NaN
    }

    /**
     * Returns how many `USD` equal `1.0` unit of [code] (`1.0 / ratePerUsd(code)`),
     * matching `fxProvider.rateToUsd(code)` in `math_engine_jni.c`.
     */
    fun rateToUsd(code: String): Double {
        val perUsd = ratePerUsd(code)
        if (perUsd.isNaN() || perUsd <= 0.0) return Double.NaN
        return 1.0 / perUsd
    }

    /**
     * Converts [amount] from [fromCurrency] to [toCurrency].
     */
    fun convert(amount: Double, fromCurrency: String, toCurrency: String): Double {
        val fromCode = normalizeCurrencyCode(fromCurrency)
        val toCode = normalizeCurrencyCode(toCurrency)
        if (fromCode == toCode) {
            return if (ratePerUsd(fromCode).isNaN()) Double.NaN else amount
        }
        val fromPerUsd = ratePerUsd(fromCode)
        val toPerUsd = ratePerUsd(toCode)
        if (fromPerUsd.isNaN() || toPerUsd.isNaN() || fromPerUsd <= 0.0) {
            return Double.NaN
        }
        return amount * (toPerUsd / fromPerUsd)
    }

    /**
     * Ensures live rates are fresh; if stale or never fetched, runs a bounded fetch on
     * [networkExecutor] so callers on any thread (including UI or JNI callbacks) never
     * trigger `NetworkOnMainThreadException` or block indefinitely.
     */
    private fun ensureFreshRatesBounded() {
        val now = System.currentTimeMillis()
        if (lastFetchTimeMs > 0L && (now - lastFetchTimeMs) < CACHE_TTL_MS) {
            return
        }
        if (!refreshInProgress.compareAndSet(false, true)) {
            return
        }
        try {
            val future = networkExecutor.submit(Callable {
                fetchAndCacheLiveRatesSync()
            })
            future.get(SYNC_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Offline or timed out; cached/baseline rates remain active
        } finally {
            refreshInProgress.set(false)
        }
    }

    /**
     * Triggers an asynchronous background refresh of live currency rates.
     */
    fun refreshRatesAsync(onDone: ((Boolean, String) -> Unit)? = null) {
        if (!refreshInProgress.compareAndSet(false, true)) {
            onDone?.invoke(true, lastProviderName)
            return
        }
        networkExecutor.execute {
            try {
                val ok = fetchAndCacheLiveRatesSync()
                onDone?.invoke(ok, lastProviderName)
            } finally {
                refreshInProgress.set(false)
            }
        }
    }

    private fun fetchAndCacheLiveRatesSync(): Boolean {
        fetchFromOpenErApi()?.let { parsed ->
            if (applyFetchedRates(parsed, "open.er-api.com")) return true
        }
        fetchFromFawazApi(FAWAZ_CDN_URL)?.let { parsed ->
            if (applyFetchedRates(parsed, "fawazahmed0/currency-api (jsDelivr)")) return true
        }
        fetchFromFawazApi(FAWAZ_PAGES_URL)?.let { parsed ->
            if (applyFetchedRates(parsed, "fawazahmed0/currency-api (Cloudflare)")) return true
        }
        fetchFromFrankfurter(FRANKFURTER_DEV_URL)?.let { parsed ->
            if (applyFetchedRates(parsed, "api.frankfurter.dev")) return true
        }
        fetchFromFrankfurter(FRANKFURTER_APP_URL)?.let { parsed ->
            if (applyFetchedRates(parsed, "api.frankfurter.app")) return true
        }
        return false
    }

    private fun applyFetchedRates(fetched: Map<String, Double>, provider: String): Boolean {
        if (fetched.isEmpty()) return false
        ratesPerUsd["USD"] = 1.0
        for ((code, rate) in fetched) {
            if (rate > 0.0 && !rate.isNaN() && !rate.isInfinite()) {
                ratesPerUsd[code.uppercase(Locale.US)] = rate
            }
        }
        lastFetchTimeMs = System.currentTimeMillis()
        lastProviderName = provider
        appContext?.let { saveCachedRatesToDisk(it, provider, lastFetchTimeMs) }
        Log.i(TAG, "Updated ${fetched.size} live currency rates from $provider")
        return true
    }

    /**
     * Provider 1: ExchangeRate-API Open Access (`https://open.er-api.com/v6/latest/USD`)
     */
    private fun fetchFromOpenErApi(): Map<String, Double>? {
        val body = httpGet(OPEN_ER_API_URL) ?: return null
        return runCatching {
            val root = JSONObject(body)
            val ratesObj = root.optJSONObject("rates") ?: root.optJSONObject("conversion_rates") ?: return null
            parseRatesObject(ratesObj)
        }.getOrNull()
    }

    /**
     * Provider 2: fawazahmed0/exchange-api (`.../v1/currencies/usd.json`)
     */
    private fun fetchFromFawazApi(url: String): Map<String, Double>? {
        val body = httpGet(url) ?: return null
        return runCatching {
            val root = JSONObject(body)
            val usdObj = root.optJSONObject("usd") ?: return null
            parseRatesObject(usdObj)
        }.getOrNull()
    }

    /**
     * Provider 3: Frankfurter API (`.../latest?base=USD` or `.../latest?from=USD`)
     */
    private fun fetchFromFrankfurter(url: String): Map<String, Double>? {
        val body = httpGet(url) ?: return null
        return runCatching {
            val root = JSONObject(body)
            val ratesObj = root.optJSONObject("rates") ?: return null
            parseRatesObject(ratesObj)
        }.getOrNull()
    }

    private fun parseRatesObject(ratesObj: JSONObject): Map<String, Double> {
        val map = HashMap<String, Double>()
        val keys = ratesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = ratesObj.optDouble(key, Double.NaN)
            if (!value.isNaN() && !value.isInfinite() && value > 0.0) {
                map[key.uppercase(Locale.US)] = value
            }
        }
        return map
    }

    private fun httpGet(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Throwable) {
        null
    }

    private fun loadCachedRatesFromDisk(context: Context) {
        runCatching {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            if (!file.isFile || !file.canRead()) return
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val ratesObj = root.optJSONObject("rates") ?: return
            val parsed = parseRatesObject(ratesObj)
            if (parsed.isNotEmpty()) {
                for ((k, v) in parsed) {
                    ratesPerUsd[k] = v
                }
                ratesPerUsd["USD"] = 1.0
                lastFetchTimeMs = root.optLong("timestampMs", 0L)
                lastProviderName = root.optString("provider", "Cached Rates")
            }
        }
    }

    private fun saveCachedRatesToDisk(context: Context, provider: String, timestampMs: Long) {
        runCatching {
            val root = JSONObject()
            root.put("provider", provider)
            root.put("timestampMs", timestampMs)
            val ratesObj = JSONObject()
            for ((k, v) in ratesPerUsd) {
                ratesObj.put(k, v)
            }
            root.put("rates", ratesObj)
            val file = File(context.filesDir, CACHE_FILE_NAME)
            val tmp = File(context.filesDir, ".$CACHE_FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
