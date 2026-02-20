package com.tuna.proj_01

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch

class VnCreditChargeActivity : AppCompatActivity() {

    private data class VnPackage(
        val id: String,
        val addChars: Long,
        val silverCost: Long
    )

    private lateinit var progressBalance: ProgressBar
    private lateinit var tvPercent: TextView
    private lateinit var tvRemainingChars: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vn_credit_charge)

        progressBalance = findViewById(R.id.progress_vn_balance)
        tvPercent = findViewById(R.id.tv_vn_percent)
        tvRemainingChars = findViewById(R.id.tv_vn_remaining_chars)

        findViewById<Button>(R.id.btn_vn_package_small).setOnClickListener {
            confirmPurchase(VnPackage("SMALL", 5000, 50))
        }
        findViewById<Button>(R.id.btn_vn_package_medium).setOnClickListener {
            confirmPurchase(VnPackage("MEDIUM", 10000, 80))
        }
        findViewById<Button>(R.id.btn_vn_package_large).setOnClickListener {
            confirmPurchase(VnPackage("LARGE", 50000, 350))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBalance()
    }

    private fun refreshBalance() {
        lifecycleScope.launch {
            try {
                val balance = VnCreditsRepository.getVnCharBalance()
                updateBalanceUi(balance)
            } catch (_: Exception) {
                updateBalanceUi(0L)
            }
        }
    }

    private fun updateBalanceUi(balanceRaw: Long) {
        val clamped = balanceRaw.coerceIn(0L, 100000L)
        val percent = ((clamped * 100L) / 100000L).toInt()
        progressBalance.max = 100000
        progressBalance.progress = clamped.toInt()
        tvPercent.text = getString(R.string.vn_percent_format, percent)
        tvRemainingChars.text = getString(R.string.vn_remaining_chars_format, clamped)
    }

    private fun confirmPurchase(pkg: VnPackage) {
        val message = getString(R.string.vn_top_up_confirm_message, pkg.silverCost, pkg.addChars)
        AlertDialog.Builder(this)
            .setTitle(R.string.vn_top_up_confirm_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                purchase(pkg.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun purchase(packageId: String) {
        lifecycleScope.launch {
            try {
                val result = VnCreditsRepository.purchaseVnCredits(packageId)
                updateBalanceUi(result.newVnCharBalance)
                Toast.makeText(this@VnCreditChargeActivity, R.string.vn_top_up_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val messageRes = if (e is FirebaseFunctionsException) {
                    when (e.code) {
                        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> R.string.vn_error_insufficient_silver
                        FirebaseFunctionsException.Code.INVALID_ARGUMENT -> R.string.vn_error_cap_exceeded
                        else -> R.string.vn_error_generic
                    }
                } else {
                    R.string.vn_error_generic
                }
                Toast.makeText(this@VnCreditChargeActivity, getString(messageRes), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
