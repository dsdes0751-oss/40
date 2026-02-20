package com.tuna.proj_01

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.min
import kotlin.math.pow

class BillingManager(
    context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val tag = "BillingDebug"
    private val functions = FirebaseFunctions.getInstance("asia-northeast3")
    private val processingTokens = mutableSetOf<String>()

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private val productIds = listOf(
        "silver_50", "silver_100", "silver_500",
        "gold_50", "gold_100", "gold_500"
    )

    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetailsList = _productDetailsList.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(tag, "Purchase canceled by user")
        } else {
            Log.e(tag, "Purchase failed: ${billingResult.debugMessage}")
        }
    }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases()
        .build()

    fun startConnection() {
        if (billingClient.isReady) {
            queryProductDetails()
            queryUnconsumedPurchases()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(tag, "Billing setup finished")
                    reconnectAttempt = 0
                    queryProductDetails()
                    queryUnconsumedPurchases()
                } else {
                    Log.e(tag, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(tag, "Billing service disconnected")
                scheduleReconnect()
            }
        })
    }

    fun endConnection() {
        reconnectJob?.cancel()
        reconnectJob = null

        synchronized(processingTokens) {
            processingTokens.clear()
        }

        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }

    fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails) {
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope.launch(Dispatchers.IO) {
            reconnectAttempt += 1
            val exp = min(5, reconnectAttempt)
            val delaySeconds = 2.0.pow(exp.toDouble()).toLong()
            delay(delaySeconds * 1000L)
            startConnection()
        }
    }

    private fun queryProductDetails() {
        val productList = productIds.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, detailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetailsList.value = detailsList
                Log.d(tag, "Loaded products: ${detailsList.size}")
            } else {
                Log.e(tag, "Failed to load products: ${billingResult.debugMessage}")
            }
        }
    }

    private fun queryUnconsumedPurchases() {
        if (!billingClient.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(tag, "Failed to query purchases: ${billingResult.debugMessage}")
                return@queryPurchasesAsync
            }

            purchases.forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                    handlePurchase(purchase)
                }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.isAcknowledged) return

        val token = purchase.purchaseToken
        synchronized(processingTokens) {
            if (!processingTokens.add(token)) return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val productId = purchase.products.firstOrNull()
                if (productId.isNullOrBlank()) {
                    Log.e(tag, "Missing product id in purchase")
                    return@launch
                }

                val data = hashMapOf(
                    "productId" to productId,
                    "purchaseToken" to token
                )

                functions.getHttpsCallable("verifyPurchase").call(data).await()

                val consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(token)
                    .build()

                billingClient.consumeAsync(consumeParams) { result, _ ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(tag, "Purchase consumed successfully")
                    } else {
                        Log.e(tag, "Failed to consume purchase: ${result.debugMessage}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Purchase processing failed", e)
            } finally {
                synchronized(processingTokens) {
                    processingTokens.remove(token)
                }
            }
        }
    }
}
