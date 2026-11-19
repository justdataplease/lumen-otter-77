package com.justdataplease.boringtravel.ui.me

import androidx.compose.runtime.Composable
import com.justdataplease.boringtravel.ui.components.BtConfirmDialog

/*
 * The Android app's ConfirmDialog (ui/me/AccountCard.kt, which isn't read in: Me's account card is ios/Account.kt here),
 * for the community's screens read in at build time (round 24): Block, Delete post, Delete profile.
 */

/** A plain in-app question in the current look. */
@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) =
    BtConfirmDialog(title, body, confirm, onConfirm, onDismiss, danger = danger)
