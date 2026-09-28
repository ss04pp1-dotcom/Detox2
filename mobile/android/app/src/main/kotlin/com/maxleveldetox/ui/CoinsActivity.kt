package com.maxleveldetox.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.maxleveldetox.MldApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CoinsActivity : AppCompatActivity() {

    private lateinit var balanceTv: TextView
    private lateinit var txContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(MldDesign.COLOR_BG))
        }

        // Header
        root.addView(MldDesign.headerView(
            this,
            "Coin Vault",
            "Emergency coin balance, rewarded ad faucet & transaction ledger"
        ) {
            finish()
        })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(MldDesign.dp(this@CoinsActivity, 20), 0, MldDesign.dp(this@CoinsActivity, 20), MldDesign.dp(this@CoinsActivity, 30))
        }

        // 1. Big Balance Hero Card
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = MldDesign.roundedDrawable(this@CoinsActivity, "#1F1B12", "#78350F", 18)
            setPadding(MldDesign.dp(this@CoinsActivity, 20), MldDesign.dp(this@CoinsActivity, 26), MldDesign.dp(this@CoinsActivity, 20), MldDesign.dp(this@CoinsActivity, 26))

            val iconTv = TextView(this@CoinsActivity).apply {
                text = "🪙"
                textSize = 36f
            }
            balanceTv = TextView(this@CoinsActivity).apply {
                text = "0 COINS"
                textSize = 34f
                setTextColor(Color.parseColor(MldDesign.ACCENT_WARNING))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, MldDesign.dp(this@CoinsActivity, 4), 0, 0)
            }
            val subTv = TextView(this@CoinsActivity).apply {
                text = "Safe emergency balance · immutable ledger"
                textSize = 12f
                setTextColor(Color.parseColor("#FDE68A"))
                setPadding(0, MldDesign.dp(this@CoinsActivity, 4), 0, 0)
            }
            addView(iconTv)
            addView(balanceTv)
            addView(subTv)
        }
        content.addView(heroCard)

        // 2. Faucet Card (Watch Ad -> Earn Coin)
        val faucetCard = MldDesign.cardContainer(this, 16).apply {
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = MldDesign.dp(this@CoinsActivity, 16)
            }
            layoutParams = params

            val title = TextView(this@CoinsActivity).apply {
                text = "REWARDED AD FAUCET"
                textSize = 11f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
            val desc = TextView(this@CoinsActivity).apply {
                text = "The only legal coin faucet. Watch a sponsored video to earn +1 coin. Coins can be used for temporary unlock during emergency lockout."
                textSize = 13f
                setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_SECONDARY))
                setPadding(0, MldDesign.dp(this@CoinsActivity, 4), 0, MldDesign.dp(this@CoinsActivity, 14))
            }
            val watchBtn = MldDesign.primaryButton(this@CoinsActivity, "🎬 WATCH AD · EARN +1 COIN", MldDesign.ACCENT_WARNING) {
                earnCoinFromAd()
            }
            addView(title)
            addView(desc)
            addView(watchBtn)
        }
        content.addView(faucetCard)

        // 3. Transactions Section
        val txSectionTitle = TextView(this).apply {
            text = "RECENT TRANSACTIONS"
            textSize = 11f
            setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, MldDesign.dp(this@CoinsActivity, 22), 0, MldDesign.dp(this@CoinsActivity, 10))
        }
        content.addView(txSectionTitle)

        txContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(txContainer)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as MldApp
            val bal = try {
                app.coinLedger.balance()
            } catch (_: Exception) {
                0
            }
            val txs = try {
                app.coinLedger.recent(20)
            } catch (_: Exception) {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                balanceTv.text = "$bal COINS"
                txContainer.removeAllViews()

                if (txs.isEmpty()) {
                    val emptyTv = TextView(this@CoinsActivity).apply {
                        text = "No transactions yet. Watch an ad to earn your first coin!"
                        textSize = 13f
                        setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                        setPadding(0, MldDesign.dp(this@CoinsActivity, 8), 0, 0)
                    }
                    txContainer.addView(emptyTv)
                } else {
                    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    for (tx in txs) {
                        val row = LinearLayout(this@CoinsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            background = MldDesign.roundedDrawable(this@CoinsActivity, MldDesign.COLOR_CARD, MldDesign.COLOR_CARD_BORDER, 12)
                            setPadding(MldDesign.dp(this@CoinsActivity, 14), MldDesign.dp(this@CoinsActivity, 10), MldDesign.dp(this@CoinsActivity, 14), MldDesign.dp(this@CoinsActivity, 10))
                            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = MldDesign.dp(this@CoinsActivity, 8)
                            }
                            layoutParams = params

                            val col = LinearLayout(this@CoinsActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                val title = TextView(this@CoinsActivity).apply {
                                    text = tx.type.name
                                    textSize = 13f
                                    setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_PRIMARY))
                                    typeface = Typeface.DEFAULT_BOLD
                                }
                                val date = TextView(this@CoinsActivity).apply {
                                    text = sdf.format(Date(tx.timestampWall))
                                    textSize = 11f
                                    setTextColor(Color.parseColor(MldDesign.COLOR_TEXT_MUTED))
                                }
                                addView(title)
                                addView(date)
                            }
                            val deltaTv = TextView(this@CoinsActivity).apply {
                                val isPos = tx.delta >= 0
                                text = if (isPos) "+${tx.delta}" else "${tx.delta}"
                                textSize = 15f
                                setTextColor(if (isPos) Color.parseColor(MldDesign.ACCENT_SUCCESS) else Color.parseColor(MldDesign.ACCENT_DANGER))
                                typeface = Typeface.DEFAULT_BOLD
                            }

                            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            addView(deltaTv)
                        }
                        txContainer.addView(row)
                    }
                }
            }
        }
    }

    private fun earnCoinFromAd() {
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext as MldApp
            val txId = "faucet_ad_" + System.currentTimeMillis()
            val awarded = try {
                app.coinLedger.awardAd(txId)
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (awarded) {
                    Toast.makeText(this@CoinsActivity, "🎉 +1 Coin Earned!", Toast.LENGTH_SHORT).show()
                    loadData()
                } else {
                    Toast.makeText(this@CoinsActivity, "Reward already claimed or cooldown active", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
