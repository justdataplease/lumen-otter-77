package com.justdataplease.boringtravel.ui.community

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.community.FollowState
import com.justdataplease.boringtravel.community.PostText
import com.justdataplease.boringtravel.ui.components.BtButton
import com.justdataplease.boringtravel.ui.components.BtGhostButton
import com.justdataplease.boringtravel.ui.icons.BtIcons
import com.justdataplease.boringtravel.ui.nav.AppActions
import com.justdataplease.boringtravel.ui.theme.Bt

/*
 * Follow pieces the profile, the lists and the Following feed share: the button, a person's row and the tap itself.
 */

/**
 * Follow (the main button) or Following (the quiet one, with a tick): one tap back and forth, nothing asked. It
 * changes at once (see Community.toggleFollow); [state] null is not known yet.
 */
@Composable
fun FollowButton(state: FollowState?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) {
    if (state?.following == true) {
        BtGhostButton("Following", onClick, modifier, icon = BtIcons.Check, enabled = enabled)
    } else {
        BtButton("Follow", onClick, modifier, icon = BtIcons.Add, enabled = enabled, small = small)
    }
}

/** Why the last Follow tap didn't go, under the button. */
@Composable
fun FollowProblem(state: FollowState?, modifier: Modifier = Modifier) {
    state?.problem?.let { Text(it, modifier, style = Bt.text.sub, color = Bt.colors.danger) }
}

/** A person on a list: their avatar, @username and a line (their name); a tap opens their profile. */
@Composable
fun PersonRow(
    username: String,
    avatar: String?,
    line: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(username, avatar, size = 44.dp)
        Column(Modifier.weight(1f)) {
            Text(PostText.handle(username), style = Bt.text.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!line.isNullOrBlank()) Text(line, style = Bt.text.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/**
 * The Follow tap on someone ([uid], [username] now). Following needs a username of the traveller's own (others see it
 * on their lists): without a profile yet they are sent to make one.
 */
@Composable
internal fun rememberFollowTap(actions: AppActions): (uid: String, username: String) -> Unit {
    val community = communityOf()
    val context = LocalContext.current
    return remember(community, actions, context) {
        val tap: (String, String) -> Unit = { uid, username ->
            when {
                !community.profileKnown.value ->
                    Toast.makeText(context, "Couldn't follow. Try again.", Toast.LENGTH_SHORT).show()
                community.profile.value == null -> {
                    Toast.makeText(context, "Pick a username to follow people.", Toast.LENGTH_SHORT).show()
                    actions.editCommunityProfile()
                }
                else -> community.toggleFollow(uid, username)
            }
        }
        tap
    }
}
