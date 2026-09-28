package com.maxleveldetox.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * BillingManager (v2.2 Phase D) — Google Play Billing wrapper.
 *
 * SECURITY MODEL:
 *  - This class is a PURCHASE LAUNCHER, never an entitlement authority.
 *    Entitlements only exist after the Worker verifies the purchase token
 *    against the Play Developer API (`POST /api/v1/subscription/verify`).
 *    A rooted client could fake every local signal here and still get
 *    nothing, because the server re-checks with Google.
 *  - Purchases are acknowledged as soon as they are observed (acknowledging
 *    is safe even before server verification — it just prevents automatic
 *    refunds).
 *  - Products are the 4 frozen SKUs: maxlevel_monthly / _3monthly /
 *    _6monthly / _yearly (mirrored in the Worker plan catalog seeds).
 *
 * The purchase result is surfaced to Flutter via [purchaseEvents]; Flutter
 * then calls the Worker verify endpoint with the purchase token and its
 * auth session. Nothing about enforcement is ever tied to billing state —
 * PRO is a supporter tier, core blocking stays free for everyone.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    /** Emitted exactly once per completed purchase for Flutter to verify. */
    private val _purchaseEvents = MutableSharedFlow<PlayPurchase>(extraBufferCapacity = 8)
    val purchaseEvents: SharedFlow<PlayPurchase> = _purchaseEvents

    @Volatile
    var connectionState: Int = BillingClient.ConnectionState.DISCONNECTED
        private set

    /**
     * v2.5.7 (W-7): the signed-in user id, embedded into every purchase as
     * the obfuscated account id. Google echoes it back via the Play
     * Developer API and the Worker verifies the purchase belongs to this
     * account — closing the first-come-first-serve entitlement grab when
     * two app accounts share one Play account. Set by NativeBridge (from
     * the Flutter session) before any purchase can launch.
     */
    @Volatile
    var accountId: String? = null

    // -----------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------

    fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connectionState = result.responseCode
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Surface any purchases completed while we were offline.
                    // v2.5.7 (H-6): always emit — the old notify=false meant
                    // a purchase completed OFFLINE was acknowledged but never
                    // emitted, so the Worker verify call never happened and
                    // the user had to press Restore manually to get PRO they
                    // had already paid for.
                    scope.launch { restorePurchasesInternal() }
                }
            }

            override fun onBillingServiceDisconnected() {
                connectionState = BillingClient.ConnectionState.DISCONNECTED
            }
        })
    }

    fun disconnect() {
        try {
            client.endConnection()
        } catch (_: Exception) {
        }
        connectionState = BillingClient.ConnectionState.DISCONNECTED
    }

    // -----------------------------------------------------------------
    // Product catalog
    // -----------------------------------------------------------------

    /**
     * Fetch the 4 subscription products. Returns an empty list on any
     * failure (offline, Play unavailable) — the paywall then falls back to
     * the server plan catalog + bKash.
     */
    suspend fun queryProducts(): List<PlayProduct> {
        if (!client.isReady) return emptyList()
        val subs = QueryProductDetailsParams.newBuilder()
            .setProductList(
                PLAY_PRODUCT_IDS.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()
        val result = client.queryProductDetails(subs)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            return emptyList()
        }
        cacheDetails(result.productDetailsList.orEmpty())
        return result.productDetailsList.orEmpty().map { details ->
            val offer = details.subscriptionOfferDetails?.firstOrNull()
            val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
            PlayProduct(
                productId = details.productId,
                title = details.title,
                formattedPrice = phase?.formattedPrice ?: "",
                billingPeriodIso = phase?.billingPeriod ?: "",
            )
        }
    }

    // -----------------------------------------------------------------
    // Purchase flow
    // -----------------------------------------------------------------

    /**
     * Launch the Play purchase sheet. Returns false when billing is not
     * available (no Play services / not ready) so the UI can route the user
     * to the bKash path instead.
     */
    fun launchPurchase(activity: Activity, productId: String): Boolean {
        if (!client.isReady) {
            connect()
            return false
        }
        val details = cachedDetails.firstOrNull { it.productId == productId } ?: return false
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        val paramsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
        // v2.5.7 (W-7): bind the purchase to the signed-in account at LAUNCH
        // time (Google's recommended pattern) — the Worker cross-checks the
        // echoed obfuscatedExternalAccountId on verify.
        accountId?.takeIf { it.isNotBlank() }?.let { aid ->
            try {
                paramsBuilder.setObfuscatedAccountId(aid)
            } catch (_: Exception) {
                // Not fatal — the server-side user binding still applies.
            }
        }
        val result = client.launchBillingFlow(activity, paramsBuilder.build())
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    @Volatile
    private var cachedDetails: List<ProductDetails> = emptyList()

    private fun cacheDetails(list: List<ProductDetails>) {
        cachedDetails = list
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit // silent by design
            else -> Unit // surfaced via queryProducts / next restore
        }
    }

    /** Restore/verify any unacknowledged purchases (app start, paywall open,
     *  periodic sync). v2.5.7 (H-6): every PURCHASED row is emitted — the
     *  Worker verify endpoint is idempotent, so a duplicate emit costs one
     *  round-trip while a MISSING emit costs a paid user their PRO. */
    fun restorePurchases() {
        scope.launch { restorePurchasesInternal() }
    }

    private suspend fun restorePurchasesInternal() {
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        result.purchasesList.orEmpty().forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                acknowledge(purchase)
                _purchaseEvents.tryEmit(
                    PlayPurchase(
                        productId = purchase.products.firstOrNull() ?: "",
                        purchaseToken = purchase.purchaseToken,
                        orderId = purchase.orderId,
                    )
                )
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        acknowledge(purchase)
        _purchaseEvents.tryEmit(
            PlayPurchase(
                productId = purchase.products.firstOrNull() ?: "",
                purchaseToken = purchase.purchaseToken,
                orderId = purchase.orderId,
            )
        )
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        try {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            ) { /* best-effort; retried on next restore */ }
        } catch (_: Exception) {
        }
    }

    // -----------------------------------------------------------------
    // Bridge-shaped helpers (raw JSON for NativeBridge)
    // -----------------------------------------------------------------

    fun productsJson(products: List<PlayProduct>): JSONArray = JSONArray().apply {
        products.forEach { p ->
            put(JSONObject().apply {
                put("productId", p.productId)
                put("title", p.title)
                put("formattedPrice", p.formattedPrice)
                put("billingPeriodIso", p.billingPeriodIso)
            })
        }
    }

    data class PlayProduct(
        val productId: String,
        val title: String,
        val formattedPrice: String,
        val billingPeriodIso: String,
    )

    data class PlayPurchase(
        val productId: String,
        val purchaseToken: String,
        val orderId: String?,
    )

    companion object {
        /** Frozen SKU set — mirrors the Worker subscription_plans seeds. */
        val PLAY_PRODUCT_IDS = listOf(
            "maxlevel_monthly",
            "maxlevel_3monthly",
            "maxlevel_6monthly",
            "maxlevel_yearly",
        )
    }
}
