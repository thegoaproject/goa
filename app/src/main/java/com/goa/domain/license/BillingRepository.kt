package com.goa.domain.license

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The one "GOA Pro" purchase (a non-consumable Google Play product). Purchases are restored on every start, so a
 * reinstall or a new phone picks registration back up; a refunded purchase un-registers, but never stops installed
 * games from playing.
 */
class BillingRepository(context: Context, private val license: LicenseManager) : PurchasesUpdatedListener {

    sealed interface State {
        data object Loading : State
        /** Google Play has the product: [price] is already formatted for the player's country. */
        data class Ready(val price: String) : State
        /** No Google Play billing (sideloaded build, no Play account, product not published yet). */
        data object Unavailable : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var productDetails: ProductDetails? = null
    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Shown by the Register screen: purchase pending, failed or cancelled. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refreshInBackground() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        if (!connect()) {
            _state.value = State.Unavailable
            return
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        ).build()
        val details = runCatching { client.queryProductDetails(params).productDetailsList?.firstOrNull() }.getOrNull()
        productDetails = details
        val price = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
        _state.value = if (price != null) State.Ready(price) else State.Unavailable
        restore()
    }

    /** Re-reads what this Google account owns. Only a successful answer can change registration. */
    suspend fun restore() {
        if (!connect()) return
        val result = runCatching {
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            )
        }.getOrNull() ?: return
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        val ours = result.purchasesList.filter { PRODUCT_ID in it.products }
        ours.forEach { handle(it) }
        val owned = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (!owned && license.registered.value && license.source == LicenseManager.SOURCE_PLAY) {
            license.revoke() // refunded, charged back or revoked: games beyond the free ones (GameSlots.FREE_SLOTS) lock until re-registering
        }
    }

    /** Opens Google Play's purchase sheet; false if billing isn't available right now. */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: return false
        _message.value = null
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build())
        ).build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { scope.launch { handle(it) } }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restore() }
            BillingClient.BillingResponseCode.USER_CANCELED -> _message.value = null
            else -> _message.value = "Google Play couldn't complete the purchase (${result.debugMessage.ifBlank { "code ${result.responseCode}" }})."
        }
    }

    private suspend fun handle(purchase: Purchase) {
        if (PRODUCT_ID !in purchase.products) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                license.setRegistered(true, LicenseManager.SOURCE_PLAY)
                _message.value = null
                if (!purchase.isAcknowledged) {
                    // Unacknowledged purchases are refunded by Google after 3 days
                    val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                    runCatching { client.acknowledgePurchase(ack) }.onFailure { Log.w(TAG, "Acknowledge failed; retried on next start", it) }
                }
            }
            Purchase.PurchaseState.PENDING ->
                _message.value = "Your payment is pending. GOA Pro unlocks as soon as Google Play confirms it."
            else -> Unit
        }
    }

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    companion object {
        /** Create this one-time (non-consumable) product in Play Console → Monetize → In-app products. */
        const val PRODUCT_ID = "goa_pro"
        private const val TAG = "GoaBilling"
    }
}
