package com.justdataplease.boringtravel.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * BoringTravel Pro: a yearly subscription ([PRO], base plan [PRO_BASE_PLAN]) or Pro for life ([PRO_LIFE], a
 * non-consumable product). Same product ids and approach as Street Gym's billing. Ownership is cached in its own
 * preferences; only Play's formatted prices are ever shown. Debug builds can pretend to be Pro to test features.
 */
class ProBilling(context: Context) : PurchasesUpdatedListener {
    enum class Status { Loading, Ready, Unavailable }

    data class State(
        val status: Status = Status.Loading,
        val yearlyOwned: Boolean = false,
        val lifeOwned: Boolean = false,
        val pending: Boolean = false,
        val yearlyPrice: String? = null,
        val lifePrice: String? = null,
        /** Debug builds only: act as Pro without buying. */
        val pretend: Boolean = false,
    ) {
        val isPro: Boolean get() = EVERYTHING_UNLOCKED || yearlyOwned || lifeOwned || pretend

        /**
         * Clips without the small BoringTravel mark: only for Pro while Pro is for sale. The free launch opens every
         * feature but keeps the mark on exported clips (owner, 2026-11-08: "Keep the mark").
         */
        val markOff: Boolean get() = PRO_FOR_SALE && isPro
    }

    private val prefs = context.applicationContext.getSharedPreferences("pro", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(
        State(yearlyOwned = prefs.getBoolean(KEY_YEARLY, false), lifeOwned = prefs.getBoolean(KEY_LIFE, false), pretend = prefs.getBoolean(KEY_PRETEND, false)),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var yearlyDetails: ProductDetails? = null
    private var lifeDetails: ProductDetails? = null
    private var connecting = false
    private val acknowledging = mutableSetOf<String>()
    private val appContext = context.applicationContext

    /** Built only when Pro is for sale ([PRO_FOR_SALE]): the free launch never talks to Play Billing. */
    private val client: BillingClient by lazy {
        BillingClient.newBuilder(appContext).setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection().build()
    }

    fun connect() {
        if (!PRO_FOR_SALE) { _state.update { it.copy(status = Status.Unavailable) }; return }
        if (connecting) return
        if (client.isReady) { queryProducts(); refresh(); return }
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) { queryProducts(); refresh() }
                else _state.update { it.copy(status = Status.Unavailable) }
            }
            override fun onBillingServiceDisconnected() {
                connecting = false
                _state.update { it.copy(status = Status.Unavailable) }
            }
        })
    }

    private fun queryProducts() {
        query(PRO, BillingClient.ProductType.SUBS) { d ->
            yearlyDetails = d
            _state.update { it.copy(yearlyPrice = d?.let(::yearlyOffer)?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice) }
        }
        query(PRO_LIFE, BillingClient.ProductType.INAPP) { d ->
            lifeDetails = d
            _state.update { it.copy(lifePrice = d?.oneTimePurchaseOfferDetails?.formattedPrice) }
        }
    }

    private fun query(id: String, type: String, found: (ProductDetails?) -> Unit) {
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, products ->
            handler.post {
                found(if (result.responseCode == BillingClient.BillingResponseCode.OK) products.productDetailsList.firstOrNull { it.productId == id } else null)
                _state.update { it.copy(status = if (it.yearlyPrice != null || it.lifePrice != null) Status.Ready else Status.Unavailable) }
            }
        }
    }

    private fun yearlyOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? =
        details.subscriptionOfferDetails.orEmpty().filter { it.basePlanId == PRO_BASE_PLAN }
            .let { offers -> offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull() }

    /** Asks Play again (Restore purchases); [done] gets true when Pro is owned. */
    fun restore(done: (Boolean?) -> Unit = {}) {
        if (!PRO_FOR_SALE) { done(null); return }
        if (!client.isReady) { connect(); done(null); return }
        refresh(done)
    }

    private fun refresh(done: (Boolean?) -> Unit = {}) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { inappResult, inapp ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { subsResult, subs ->
                handler.post {
                    val inappOk = inappResult.responseCode == BillingClient.BillingResponseCode.OK
                    val subsOk = subsResult.responseCode == BillingClient.BillingResponseCode.OK
                    handle((if (inappOk) inapp else emptyList()) + (if (subsOk) subs else emptyList()), lifeComplete = inappOk, yearlyComplete = subsOk)
                    done(if (inappOk && subsOk) _state.value.isPro else null)
                }
            }
        }
    }

    fun buyYearly(activity: Activity): Boolean {
        if (!PRO_FOR_SALE) return false
        val details = yearlyDetails ?: return false
        val offer = yearlyOffer(details) ?: return false
        if (_state.value.yearlyOwned) return false
        return launch(activity, BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer.offerToken).build())
    }

    fun buyLife(activity: Activity): Boolean {
        if (!PRO_FOR_SALE) return false
        val details = lifeDetails ?: return false
        if (_state.value.lifeOwned) return false
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        details.oneTimePurchaseOfferDetails?.offerToken?.takeIf(String::isNotBlank)?.let(item::setOfferToken)
        return launch(activity, item.build())
    }

    private fun launch(activity: Activity, item: BillingFlowParams.ProductDetailsParams): Boolean =
        client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build())
            .responseCode == BillingClient.BillingResponseCode.OK

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handler.post { handle(purchases.orEmpty(), lifeComplete = false, yearlyComplete = false) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> Unit
        }
    }

    private fun handle(purchases: List<Purchase>, lifeComplete: Boolean, yearlyComplete: Boolean) {
        val evidence = purchases.flatMap { p ->
            val state = when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> PaymentState.Purchased
                Purchase.PurchaseState.PENDING -> PaymentState.Pending
                else -> PaymentState.Other
            }
            p.products.map { Evidence(it, state) }
        }
        val s = _state.value
        val yearly = reconcile(PRO, s.yearlyOwned, evidence, yearlyComplete)
        val life = reconcile(PRO_LIFE, s.lifeOwned, evidence, lifeComplete)
        prefs.edit().putBoolean(KEY_YEARLY, yearly.owned).putBoolean(KEY_LIFE, life.owned).apply()
        _state.update { it.copy(yearlyOwned = yearly.owned, lifeOwned = life.owned, pending = yearly.pending || life.pending) }
        purchases.filter { p -> p.products.any { it == PRO || it == PRO_LIFE } && p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged }
            .forEach { acknowledge(it.purchaseToken, 0) }
    }

    private fun acknowledge(token: String, attempt: Int) {
        if (!PRO_FOR_SALE || !client.isReady || !acknowledging.add(token)) return
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { result ->
            handler.post {
                acknowledging.remove(token)
                val done = result.responseCode in listOf(BillingClient.BillingResponseCode.OK, BillingClient.BillingResponseCode.ITEM_NOT_OWNED, BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                if (!done && attempt < 3) handler.postDelayed({ acknowledge(token, attempt + 1) }, 2000L shl attempt)
            }
        }
    }

    /** Debug builds only (the Pro screen hides the switch in release). */
    fun setPretend(on: Boolean) {
        prefs.edit().putBoolean(KEY_PRETEND, on).apply()
        _state.update { it.copy(pretend = on) }
    }

    companion object {
        const val PRO = "pro"
        const val PRO_BASE_PLAN = "yearly"
        const val PRO_LIFE = "pro_life"
        /**
         * The one switch for BoringTravel Pro. False = the free Google Play launch (owner, 2026-11-08: "launch free for
         * now and we will push a version later with pro"): every feature is open to everyone, nothing offers Pro or
         * shows a price, a PRO tag or the Pro screen, and Play Billing is never contacted (no client is built, no
         * purchase can start). The manifest also removes `com.android.vending.BILLING` while this is false
         * (`ProSwitchTest` keeps the two in step). To sell Pro later: set true, delete that manifest line, create the
         * products `pro` (base plan `yearly`) and `pro_life` in Play Console, and add a reviewer path for paid content.
         */
        const val PRO_FOR_SALE = false

        /** Every Pro feature open to everyone: always while Pro isn't for sale. */
        const val EVERYTHING_UNLOCKED = !PRO_FOR_SALE
        private const val KEY_YEARLY = "yearly_owned"
        private const val KEY_LIFE = "life_owned"
        private const val KEY_PRETEND = "pretend_pro"

        fun manageUrl(packageName: String) = "https://play.google.com/store/account/subscriptions?sku=$PRO&package=$packageName"
    }
}
