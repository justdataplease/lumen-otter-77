package com.justdataplease.boringtravel.ui.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.justdataplease.boringtravel.clip.ClipTags
import com.justdataplease.boringtravel.clip.Spots
import com.justdataplease.boringtravel.clip.TagColor
import com.justdataplease.boringtravel.ui.studio.StudioColors
import com.justdataplease.boringtravel.ui.studio.StudioNote
import com.justdataplease.boringtravel.ui.studio.StudioSwitchRow
import com.justdataplease.boringtravel.ui.studio.StudioText

/** Where tags come from, said where tags are shown (owner, 2026-11-12: "clip inherits tags from plan items"). */
internal const val TAGS_FROM_PLAN = "Tags come from the plan. Add them on a plan item."

/**
 * The places' tags for the whole clip: on or off, the stickers' colour and size. The tags themselves are the plan
 * items' ([com.justdataplease.boringtravel.clip.ClipPlan.tags]); a place can have its own switch, colour, size and spot.
 */
@Composable
internal fun TagsAll(ed: ClipEditing) {
    val d = ed.design
    val on = ed.enabled
    StudioSwitchRow("Tags", "Each place's tags as stickers under its name", d.tags) { show -> if (on) ed.style { it.copy(tags = show) } }
    StudioNote(if (ed.plan.tags.isEmpty()) "$TAGS_FROM_PLAN None yet." else TAGS_FROM_PLAN)
    if (!d.tags) return
    ColourChoice("Tag colour", TagColor.entries, d.tagColor, { it.label }, { Color(it.fill) }, on) { v -> ed.style { it.copy(tagColor = v) } }
    ValueSlider("Tag size", d.tagScale, ClipTags.SCALE, ::percent, on) { v -> ed.style { it.copy(tagScale = Spots.scale(v, ClipTags.SCALE)) } }
}

/**
 * A place's tags (its plan items', in plan order): shown or not, their colour, size and spot, each only here; Auto
 * goes back to the clip's. None without tags on its plan items.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SceneTags(ed: ClipEditing, key: String, auto: Pair<Float, Float>) {
    val d = ed.design
    val o = d.scene(key)
    val tags = ed.plan.tags[key].orEmpty()
    val on = ed.enabled
    Part("Tags")
    if (tags.isEmpty()) {
        StudioNote("No tags here. $TAGS_FROM_PLAN")
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { t ->
            Text(
                "#$t", Modifier.clip(RoundedCornerShape(10.dp)).background(StudioColors.raised).padding(horizontal = 10.dp, vertical = 6.dp),
                style = StudioText.chip, color = StudioColors.ink, maxLines = 1,
            )
        }
    }
    StudioNote(TAGS_FROM_PLAN)
    val shown = d.tagsShown(key)
    StudioSwitchRow("Show the tags", null, shown) { show -> if (on) ed.change(key) { it.copy(tagsHidden = (!show).takeIf { show != d.tags }) } }
    if (!shown) return
    ColourChoice("Tag colour", TagColor.entries, d.tagColor(key), { it.label }, { Color(it.fill) }, on) { v ->
        ed.change(key) { it.copy(tagColor = v.takeIf { c -> c != d.tagColor }) }
    }
    ValueSlider("Tag size", d.tagScale(key), ClipTags.SCALE, ::percent, on) { v ->
        ed.change(key) { it.copy(tagScale = Spots.scale(v, ClipTags.SCALE).takeIf { s -> kotlin.math.abs(s - d.tagScale) > 0.001f }) }
    }
    val spot = o.tagX?.let { x -> o.tagY?.let { y -> x to y } }
    ThumbPad("Tags go", spot, auto, ed.aspect, on) { p -> ed.change(key) { it.copy(tagX = p?.first, tagY = p?.second) } }
    StudioNote("Auto: under the name.")
}
