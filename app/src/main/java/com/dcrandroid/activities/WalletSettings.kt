/*
 * Copyright (c) 2018-2019 The Decred developers
 * Use of this source code is governed by an ISC
 * license that can be found in the LICENSE file.
 */

package com.dcrandroid.activities

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import com.dcrandroid.R
import com.dcrandroid.data.Constants
import com.dcrandroid.dialog.FullScreenBottomSheetDialog
import com.dcrandroid.dialog.InfoDialog
import com.dcrandroid.dialog.PasswordPromptDialog
import com.dcrandroid.dialog.PinPromptDialog
import com.dcrandroid.extensions.show
import com.dcrandroid.preference.ListPreference
import com.dcrandroid.preference.SwitchPreference
import com.dcrandroid.util.*
import dcrlibwallet.Dcrlibwallet
import dcrlibwallet.Wallet
import kotlinx.android.synthetic.main.activity_wallet_settings.*
import kotlinx.android.synthetic.main.activity_wallet_settings.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WalletSettings : BaseActivity() {

    private var walletID = -1L
    private lateinit var wallet: Wallet

    private lateinit var useFingerprint: SwitchPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet_settings)

        walletID = intent.getLongExtra(Constants.WALLET_ID, -1)
        wallet = multiWallet!!.walletWithID(walletID)

        tv_subtitle?.text = wallet.name

        change_spending_pass.setOnClickListener {
            ChangePassUtil(this, walletID).begin()
        }

        useFingerprint = SwitchPreference(this, walletID.toString() + Dcrlibwallet.UseFingerprintConfigKey, spendable_fingerprint) { newValue ->

            if (newValue) {
                setupFingerprint()
                !newValue // ignore new value
            } else {
                clearSpendingPassFromKeystore()
                newValue
            }
        }
        loadFingerprintPreference()

        val incomingNotificationsKey = walletID.toString() + Dcrlibwallet.IncomingTxNotificationsConfigKey
        setTxNotificationSummary(multiWallet!!.readInt32ConfigValueForKey(incomingNotificationsKey, Constants.DEF_TX_NOTIFICATION))
        ListPreference(this, incomingNotificationsKey, Constants.DEF_TX_NOTIFICATION,
                R.array.notification_options, incoming_transactions) {
            setTxNotificationSummary(it)
        }

        change_spending_pass.setOnClickListener {
            ChangePassUtil(this, walletID).begin()
        }
        
        rescan_blockchain.setOnClickListener {
            if (multiWallet!!.isSyncing) {
                SnackBar.showError(this, R.string.err_sync_in_progress)
            } else if (!multiWallet!!.isSynced) {
                SnackBar.showError(this, R.string.not_connected)
            } else if (multiWallet!!.isRescanning) {
                SnackBar.showError(this, R.string.err_rescan_in_progress)
            } else {
                InfoDialog(this)
                        .setDialogTitle(getString(R.string.rescan_blockchain))
                        .setMessage(getString(R.string.rescan_blockchain_warning))
                        .setPositiveButton(getString(R.string.yes), DialogInterface.OnClickListener { _, _ ->
                            multiWallet!!.rescanBlocks(walletID)
                            SnackBar.showText(this, R.string.rescan_progress_notification)
                        })
                        .setNegativeButton(getString(R.string.no))
                        .show()
            }

        }

        remove_wallet.setOnClickListener {
            if (multiWallet!!.isSyncing || multiWallet!!.isSynced) {
                SnackBar.showError(this, R.string.cancel_sync_delete_wallet)
                return@setOnClickListener
            }

            val dialog = InfoDialog(this)
                    .setDialogTitle(getString(R.string.remove_wallet_prompt))
                    .setMessage(getString(R.string.remove_wallet_message))
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.remove), DialogInterface.OnClickListener { _, _ ->

                        val title = PassPromptTitle(R.string.confirm_to_remove, R.string.confirm_to_remove, R.string.confirm_to_remove)

                        PassPromptUtil(this, walletID, title, false) { dialog, pass ->
                            if (pass != null) {
                                deleteWallet(pass, dialog)
                            }

                            false
                        }.show()
                    })

            dialog.btnPositiveColor = R.color.orangeTextColor
            dialog.show()
        }

        go_back.setOnClickListener {
            finish()
        }
    }

    private fun loadFingerprintPreference() {
        if (BiometricUtils.isFingerprintEnrolled(this)) {
            spendable_fingerprint.show()
        }
    }

    private fun setupFingerprint() {
        val title = PassPromptTitle(R.string.spending_password, R.string.enter_spending_pin)
        PassPromptUtil(this, walletID, title, false) { dialog, pass ->

            if (pass == null) {
                return@PassPromptUtil true
            }

            GlobalScope.launch(Dispatchers.IO) {
                try {
                    wallet.unlockWallet(pass.toByteArray())
                    BiometricUtils.saveToKeystore(this@WalletSettings, pass, BiometricUtils.getWalletAlias(walletID))
                    wallet.lockWallet()

                    withContext(Dispatchers.Main) {
                        dialog?.dismiss()
                        useFingerprint.setValue(true)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()

                    val err = if (wallet.privatePassphraseType == Dcrlibwallet.PassphraseTypePin) {
                        R.string.invalid_pin
                    } else {
                        R.string.invalid_password
                    }

                    withContext(Dispatchers.Main) {
                        dialog?.dismiss()

                        SnackBar.showError(this@WalletSettings, err)
                    }
                }
            }
            false
        }.show()
    }

    private fun clearSpendingPassFromKeystore() {
        BiometricUtils.saveToKeystore(this@WalletSettings, "", BiometricUtils.getWalletAlias(walletID))
    }

    private fun setTxNotificationSummary(index: Int) {
        val preferenceSummary = resources.getStringArray(R.array.notification_options)[index]
        incoming_transactions.pref_subtitle.text = preferenceSummary
    }

    private fun deleteWallet(pass: String, dialog: FullScreenBottomSheetDialog?) = GlobalScope.launch(Dispatchers.IO) {
        try {
            multiWallet!!.unlockWallet(walletID, pass.toByteArray()) // test if pass is correct
            clearSpendingPassFromKeystore()

            multiWallet!!.deleteWallet(walletID, pass.toByteArray())

            withContext(Dispatchers.Main) {
                dialog?.dismiss()
            }

            if (multiWallet!!.openedWalletsCount() == 0) {
                multiWallet!!.shutdown()
                walletData.multiWallet = null
                startActivity(Intent(this@WalletSettings, SplashScreen::class.java))
                finishAffinity()
            } else {
                setResult(Activity.RESULT_OK)
                finish()
            }

            SnackBar.showText(this@WalletSettings, R.string.wallet_removed)
        } catch (e: Exception) {
            e.printStackTrace()

            if(dialog is PinPromptDialog){
                dialog.setProcessing(false)
            }else if(dialog is PasswordPromptDialog){
                dialog.setProcessing(false)
            }

            if (e.message == Dcrlibwallet.ErrInvalidPassphrase) {
                val errMessage = when (wallet.privatePassphraseType) {
                    Dcrlibwallet.PassphraseTypePin -> R.string.invalid_pin
                    else -> R.string.invalid_password
                }
                SnackBar.showError(this@WalletSettings, errMessage)
            }
        }
    }
}