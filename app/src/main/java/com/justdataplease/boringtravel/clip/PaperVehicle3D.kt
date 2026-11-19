package com.justdataplease.boringtravel.clip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import com.justdataplease.boringtravel.model.Vehicle
import com.justdataplease.boringtravel.ui.theme.BtTokens
import com.justdataplease.boringtravel.ui.theme.BtVehicle
import kotlin.math.roundToInt

/**
 * Tiny real 3D paper models. Use one instance per preview/export renderer: mesh topology is shared, mutable
 * camera/Canvas workspaces are not. Draw changes no Canvas transform/clip state and allocates nothing after warm-up.
 * The travel picker draws the same models at a fixed three-quarter view ([VehicleSprites]), so picker and replay match.
 */
class PaperVehicle3D {
    /**
     * A theme's paper: [body] (car body, fuselage, carriages), [card] (lighter paper), [ink] (hull, cab, details),
     * [accent] (roof, bike frame, stripes), [glass] (windows), [shadow] (tyres, ground shadow), and how strongly the
     * folds are inked ([outline], 0–1: Postcard Pop's chunky outlines, Sketchbook's ballpoint).
     */
    data class Palette(
        val body: Int, val card: Int, val ink: Int, val accent: Int, val glass: Int, val shadow: Int,
        val dark: Boolean = false, val outline: Float = 0.10f,
    ) {
        companion object {
            fun of(v: BtVehicle) = Palette(
                body = v.body.toArgb(), card = v.card.toArgb(), ink = v.ink.toArgb(), accent = v.accent.toArgb(),
                glass = v.glass.toArgb(), shadow = v.shadow.toArgb(), dark = v.dark, outline = v.outline,
            )

            fun of(tokens: BtTokens) = of(tokens.vehicle)

            /**
             * Each material's colour: the theme's colour with a little of the approved paper family left in, so every
             * theme still reads as folded card (factors tuned per material: the accent and ink carry the theme).
             */
            fun materials(p: Palette): IntArray {
                val out = IntArray(PaperMaterial.entries.size)
                out[PaperMaterial.Ivory.ordinal] = mix(0xfff3e7d1.toInt(), p.body, .88f)
                out[PaperMaterial.Paper.ordinal] = mix(0xfffff9eb.toInt(), p.card, .85f)
                out[PaperMaterial.Navy.ordinal] = mix(0xff293952.toInt(), p.ink, .90f)
                out[PaperMaterial.Terracotta.ordinal] = mix(0xffbf7053.toInt(), p.accent, .92f)
                out[PaperMaterial.Glass.ordinal] = mix(0xff293c55.toInt(), p.glass, .85f)
                out[PaperMaterial.Rubber.ordinal] = mix(0xff243044.toInt(), p.shadow, .70f)
                out[PaperMaterial.Metal.ordinal] = mix(0xffc8c1b2.toInt(), p.card, .40f)
                out[PaperMaterial.Skin.ordinal] = 0xffefdab8.toInt()
                out[PaperMaterial.Foam.ordinal] = mix(0xffffffff.toInt(), p.card, .25f)
                return out
            }

            internal fun mix(a: Int, b: Int, f: Float): Int {
                fun c(shift: Int) = (((a ushr shift and 255) * (1f - f) + (b ushr shift and 255) * f).roundToInt()).coerceIn(0, 255)
                return (255 shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
            }
        }
    }
    private val projection = PaperVehicleProjection()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeJoin=Paint.Join.ROUND }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.FILL_AND_STROKE; strokeJoin=Paint.Join.ROUND }
    private val path = Path()
    private val colors = IntArray(PaperMaterial.entries.size)
    private var previousPalette: Palette? = null
    /** The ride's own Body and Trim colours for this draw (0: the theme's). */
    private var body = 0
    private var trim = 0

    /**
     * [phase] is elapsed seconds. [screenHeadingDegrees] is clockwise from screen north, not world yaw. [ride] is the
     * vehicle's model and colours ([Rides]); null, or another vehicle's, draws today's look.
     */
    fun draw(
        canvas: Canvas, vehicle: Vehicle, centerX: Float, centerY: Float, sizePx: Float,
        screenHeadingDegrees: Float, tiltDegrees: Float, phase: Float, palette: Palette, ride: Ride? = null,
    ): Boolean {
        val own=ride?.takeIf { it.vehicle==vehicle }
        val mesh=own?.let { PaperVehicleMeshes.get(it.model) } ?: PaperVehicleMeshes.get(vehicle) ?: return false
        if(!valid(centerX,centerY,sizePx)) return false
        body=own?.body?.argb ?: 0
        trim=own?.trim?.argb ?: 0
        return drawMesh(canvas,mesh,centerX,centerY,sizePx,projection.project(mesh,screenHeadingDegrees,tiltDegrees,phase),palette,fitShadow=false,silhouette=true)
    }

    /** [ride] in its own vehicle's place: Me's and the editor's preview. */
    fun drawRide(
        canvas: Canvas, ride: Ride, centerX: Float, centerY: Float, sizePx: Float,
        screenHeadingDegrees: Float, tiltDegrees: Float, phase: Float, palette: Palette,
    ): Boolean = draw(canvas, ride.vehicle, centerX, centerY, sizePx, screenHeadingDegrees, tiltDegrees, phase, palette, ride)

    private var travellerKey: Traveller? = null
    private var travellerMesh: PaperMesh? = null

    /**
     * The [traveller] (one person or a couple) in their own colours, the theme's [palette] for shoes and the backpack,
     * with the limbs at [stride] (a [WalkCycle]'s, −1..1). The figure is built once per look and kept.
     */
    fun drawTraveller(
        canvas: Canvas, traveller: Traveller, centerX: Float, centerY: Float, sizePx: Float,
        screenHeadingDegrees: Float, tiltDegrees: Float, stride: Float, palette: Palette,
    ): Boolean {
        if(!valid(centerX,centerY,sizePx)) return false
        body=0; trim=0
        val mesh=travellerMesh.takeIf { travellerKey==traveller } ?: TravellerMesh.build(traveller).also { travellerMesh=it; travellerKey=traveller }
        return drawMesh(canvas,mesh,centerX,centerY,sizePx,projection.pose(mesh,screenHeadingDegrees,tiltDegrees,stride),palette,fitShadow=true,silhouette=true)
    }

    private fun valid(x:Float,y:Float,size:Float)=x.isFinite() && y.isFinite() && size.isFinite() && size>0f

    /** [fitShadow]: the ground shadow as wide as the model shows (a couple side by side), else the vehicles' own. */
    private fun drawMesh(
        canvas: Canvas, mesh: PaperMesh, centerX: Float, centerY: Float, sizePx: Float, projected: PaperVehicleProjection,
        palette: Palette, fitShadow: Boolean, silhouette: Boolean = false,
    ): Boolean {
        if(previousPalette!=palette) {
            Palette.materials(palette).copyInto(colors)
            previousPalette=palette
        }
        val size=sizePx*.94f
        fill.color=(palette.shadow and 0x00ffffff) or (28 shl 24)
        val ground=centerY+projected.groundY*size
        var left=-.23f; var right=.23f
        if(fitShadow) {
            left=Float.MAX_VALUE; right=-Float.MAX_VALUE
            for(i in 0 until mesh.vertices.size) {val x=projected.coordinates[i*3]; if(x<left) left=x; if(x>right) right=x}
            val middle=(left+right)/2; val half=((right-left)/2*.62f).coerceIn(.12f,.5f)
            left=middle-half; right=middle+half
        }
        canvas.drawOval(centerX+left*size,ground-size*.038f,centerX+right*size,ground+size*.038f,fill)
        if(silhouette) {
            drawSilhouette(canvas,mesh,centerX,centerY,size,projected,palette)
            return true
        }
        // Folds inked in the theme's ink: faint on most themes, bold on Postcard Pop and Sketchbook.
        seam.color=(palette.ink and 0x00ffffff) or ((palette.outline.coerceIn(0f,1f)*255).roundToInt() shl 24)
        seam.strokeWidth=(size*(.0042f+.006f*palette.outline)).coerceIn(.30f,2.4f)
        for(slot in 0 until projected.visibleFaces) {
            val faceIndex=projected.faceOrder[slot]; val face=mesh.faces[faceIndex]
            facePath(mesh,faceIndex,centerX,centerY,size,projected)
            fill.color=shade(faceColor(face,colors,body,trim),projected.faceLight[faceIndex])
            canvas.drawPath(path,fill)
            if(face.material!=PaperMaterial.Foam) canvas.drawPath(path,seam)
        }
        return true
    }

    /**
     * One ink outline round the whole shape (every face drawn fat in ink first, in one layer), then the faces in their
     * own colours, each edged in its own colour so neighbours meet without a hairline gap. Inking every fold turned a
     * figure into a wireframe of black lines (owner, 2026-11-08); the vehicles are drawn the same way since their own
     * colours came in (folds inked between a red body and a white roof read as noise at 54 dp). Foam has no ink.
     */
    private fun drawSilhouette(
        canvas: Canvas, mesh: PaperMesh, centerX: Float, centerY: Float, size: Float, projected: PaperVehicleProjection, palette: Palette,
    ) {
        val strength=(.45f+.55f*palette.outline.coerceIn(0f,1f)).coerceAtMost(1f)
        outline.color=palette.ink or (0xff shl 24)
        outline.strokeWidth=(size*(.016f+.012f*palette.outline)).coerceIn(1.2f,4.5f)
        // One translucent layer: fat strokes overlap everywhere and would darken where they cross.
        val layer=if(strength<1f) canvas.saveLayerAlpha(null,(strength*255).roundToInt()) else -1
        for(slot in 0 until projected.visibleFaces) {
            val faceIndex=projected.faceOrder[slot]
            if(mesh.faces[faceIndex].material==PaperMaterial.Foam) continue
            facePath(mesh,faceIndex,centerX,centerY,size,projected)
            canvas.drawPath(path,outline)
        }
        if(layer>=0) canvas.restoreToCount(layer)
        seam.strokeWidth=(size*.004f).coerceIn(.6f,1.2f)
        for(slot in 0 until projected.visibleFaces) {
            val faceIndex=projected.faceOrder[slot]; val face=mesh.faces[faceIndex]
            facePath(mesh,faceIndex,centerX,centerY,size,projected)
            fill.color=shade(faceColor(face,colors,body,trim),projected.faceLight[faceIndex])
            seam.color=fill.color
            canvas.drawPath(path,fill)
            canvas.drawPath(path,seam)
        }
    }

    private fun facePath(mesh: PaperMesh, faceIndex: Int, centerX: Float, centerY: Float, size: Float, projected: PaperVehicleProjection) {
        val face=mesh.faces[faceIndex]
        path.rewind()
        for(i in face.indices.indices) {
            val at=face.indices[i]*3
            val x=centerX+projected.coordinates[at]*size; val y=centerY+projected.coordinates[at+1]*size
            if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        path.close()
    }

    private fun shade(color:Int,light:Float):Int = (255 shl 24) or
        (((color ushr 16 and 255)*light).roundToInt().coerceIn(0,255) shl 16) or
        (((color ushr 8 and 255)*light).roundToInt().coerceIn(0,255) shl 8) or
        ((color and 255)*light).roundToInt().coerceIn(0,255)
}

/**
 * A face's colour before shading: its own ([PaperFace.color]), else the ride's [body] or [trim] where it takes one
 * ([PaperFace.paint]; 0: none chosen), else its material's in the theme ([materials]). Pure.
 */
internal fun faceColor(face: PaperFace, materials: IntArray, body: Int, trim: Int): Int = when {
    face.color != 0 -> face.color
    face.paint == PaperPaint.BODY && body != 0 -> body
    face.paint == PaperPaint.TRIM && trim != 0 -> trim
    else -> materials[face.material.ordinal]
}

/** The colours a ride's "Theme" swatches show. Pure. */
internal object RidePaint {
    /** What [model]'s Body or Trim ([role]) is in the theme: the material most of its faces in that role are. */
    fun themeColor(model: RideModel, role: Int, palette: PaperVehicle3D.Palette): Int {
        val counts = IntArray(PaperMaterial.entries.size)
        PaperVehicleMeshes.get(model).faces.forEach { if (it.paint == role && it.color == 0) counts[it.material.ordinal]++ }
        val most = counts.indices.maxByOrNull { counts[it] }?.takeIf { counts[it] > 0 } ?: return palette.body
        return PaperVehicle3D.Palette.materials(palette)[most]
    }
}
