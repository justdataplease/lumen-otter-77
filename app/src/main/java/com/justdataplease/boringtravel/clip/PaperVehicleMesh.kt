package com.justdataplease.boringtravel.clip

import com.justdataplease.boringtravel.model.Vehicle
import kotlin.math.*

/** Original low-poly models: x right, y nose/forward, z up. No textures or external 3D runtime. */
/** [Foam]: water churned white (a wake), drawn without ink. */
internal enum class PaperMaterial { Ivory, Paper, Navy, Terracotta, Glass, Rubber, Metal, Skin, Foam }
internal data class PaperVertex(val x: Float, val y: Float, val z: Float, val bone: Int = 0)

/** Which of a ride's own colours ([Ride.body], [Ride.trim]) a face takes instead of its material's, if any. */
internal object PaperPaint {
    /** Builder only: by material, the theme's body paper ([PaperMaterial.Ivory]) as Body, its accent as Trim. */
    const val AUTO = -1
    const val NONE = 0
    const val BODY = 1
    const val TRIM = 2
}

/**
 * [color]: the face's own ARGB colour (a traveller's skin or clothes); 0 = the theme's [material]. [lift]: how much
 * nearer the camera it sorts (model units), for details laid on a bigger surface (eyes, pockets, straps). [paint]: the
 * ride colour it takes when one is chosen ([PaperPaint]); without one it stays the material's (today's look). [over]: the
 * face a detail lies on (a window on a door), drawn straight after it whatever its own depth; -1 for none.
 */
internal data class PaperFace(
    val indices: IntArray, val material: PaperMaterial, val color: Int = 0, val lift: Float = 0f, val paint: Int = PaperPaint.NONE,
    val over: Int = -1,
)

/** A joint: [swing] degrees about x at full stride (a limb), or [spin] turns a second about z (a rotor). */
internal data class PaperBone(val x: Float, val y: Float, val z: Float, val swing: Float, val spin: Float = 0f)
internal class PaperMesh(
    val vertices: List<PaperVertex>, val faces: List<PaperFace>, val bones: List<PaperBone>,
    normalizationRadius: Float? = null,
    /** The faces of each closed part (a box, a rod, a loft), for the winding check; single faces are left out. */
    val solids: List<IntRange> = emptyList(),
) {
    val centerZ = (vertices.maxOf { it.z } + vertices.minOf { it.z }) / 2f
    // A fixed sphere keeps size steady through turns. Extra gait clearance is reserved only for the walker.
    val radius = normalizationRadius ?: (vertices.maxOf { sqrt(it.x * it.x + it.y * it.y + (it.z - centerZ).pow(2)) } * if (bones.any { it.swing != 0f }) 1.12f else 1.025f)
}

/** Topology is constructed once, then shared read-only by independently owned preview/export workspaces. */
internal object PaperVehicleMeshes {
    /** Every ride's model; each vehicle's default ([RideModel.default]) is today's look. */
    private val rides by lazy { RideModel.entries.associateWith(::build) }
    private val walker by lazy { walk() }

    /** [vehicle]'s default model (the walker for Walk), or null for Auto. */
    fun get(vehicle: Vehicle): PaperMesh? = if (vehicle == Vehicle.Walk) walker else RideModel.default(vehicle)?.let { rides[it] }

    fun get(model: RideModel): PaperMesh = rides.getValue(model)

    private fun build(model: RideModel): PaperMesh = when (model) {
        RideModel.Hatchback -> car()
        RideModel.Suv -> RideMeshes.suv()
        RideModel.Camper -> RideMeshes.camper()
        RideModel.Convertible -> RideMeshes.convertible()
        RideModel.Bicycle -> bike()
        RideModel.Scooter -> RideMeshes.scooter()
        RideModel.Motorbike -> RideMeshes.motorbike()
        RideModel.Steam -> train()
        RideModel.HighSpeed -> RideMeshes.highSpeed()
        RideModel.Regional -> RideMeshes.regional()
        RideModel.Tram -> RideMeshes.tram()
        RideModel.Ferry -> ferry()
        RideModel.Sailboat -> RideMeshes.sailboat()
        RideModel.Speedboat -> RideMeshes.speedboat()
        RideModel.Cruise -> RideMeshes.cruise()
        RideModel.Airliner -> plane()
        RideModel.Prop -> RideMeshes.prop(floats = false)
        RideModel.Seaplane -> RideMeshes.prop(floats = true)
        RideModel.Helicopter -> RideMeshes.helicopter()
    }

    /** Narrow every part together while preserving the original rendered length and height. */
    internal fun PaperMesh.narrowWidth(factor: Float) = PaperMesh(
        vertices.map { it.copy(x = it.x * factor) }, faces, bones.map { it.copy(x = it.x * factor) }, radius, solids,
    )

    internal data class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(b: V) = V(x + b.x, y + b.y, z + b.z)
        operator fun minus(b: V) = V(x - b.x, y - b.y, z - b.z)
        operator fun times(s: Float) = V(x * s, y * s, z * s)
        fun dot(b: V) = x * b.x + y * b.y + z * b.z
        fun cross(b: V) = V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
        fun unit(): V { val length = sqrt(dot(this)).coerceAtLeast(.00001f); return this * (1f / length) }
    }

    internal class Builder {
        val vertices = ArrayList<PaperVertex>()
        val faces = ArrayList<PaperFace>()
        val bones = arrayListOf(PaperBone(0f, 0f, 0f, 0f))
        var bone = 0
        /** The ARGB colour new faces take instead of their material's (0: the material's). */
        var color = 0
        /** The sorting lift new faces take ([PaperFace.lift]). */
        var lift = 0f
        /** The ride colour new faces take ([PaperPaint]); [PaperPaint.AUTO] goes by material. */
        var paint = PaperPaint.AUTO
        private val solids = ArrayList<IntRange>()
        fun build(normalizationRadius: Float? = null) = PaperMesh(vertices.toList(), faces.toList(), bones.toList(), normalizationRadius, solids.toList())
        fun bone(x: Float, y: Float, z: Float, swing: Float, spin: Float = 0f): Int {
            bones += PaperBone(x, y, z, swing, spin); return bones.lastIndex
        }
        /** [block]'s faces in [role] ([PaperPaint]). */
        inline fun paint(role: Int, block: () -> Unit) {
            val before = paint; paint = role
            try { block() } finally { paint = before }
        }
        fun face(points: List<V>, material: PaperMaterial, outward: V) {
            // The first corner's turn, or the whole outline's when the first three points lie on one line.
            val first = (points[1] - points[0]).cross(points[2] - points[0])
            val turn = if (first.dot(first) > 1e-10f) first else outline(points)
            val order = startOnACorner(if (turn.dot(outward) < 0f) points.reversed() else points)
            val start = vertices.size
            order.forEach { vertices += PaperVertex(it.x, it.y, it.z, bone) }
            val role = if (paint != PaperPaint.AUTO) paint else when (material) {
                PaperMaterial.Ivory -> PaperPaint.BODY
                PaperMaterial.Terracotta -> PaperPaint.TRIM
                else -> PaperPaint.NONE
            }
            faces += PaperFace(IntArray(order.size) { start + it }, material, color, lift, role)
        }
        /** The outline's turn (Newell's normal: as long as twice the area, the way the points wind). */
        private fun outline(points: List<V>): V {
            var normal = V(0f, 0f, 0f)
            for (i in points.indices) {
                val a = points[i]; val b = points[(i + 1) % points.size]
                normal += V((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y))
            }
            return normal
        }
        /**
         * [points] starting on a real corner: the camera's facing test reads the first three points, and a
         * square-cornered section (a flat bottom running into a straight side) can start on a straight edge. Such
         * outlines start where they turn most; the rest keep their order.
         */
        private fun startOnACorner(points: List<V>): List<V> {
            val first = (points[1] - points[0]).cross(points[2] - points[0])
            if (points.size == 3 || first.dot(first) > 1e-10f) return points
            val normal = outline(points)
            val best = points.indices.maxBy { i ->
                val a = points[i]; val b = points[(i + 1) % points.size]; val c = points[(i + 2) % points.size]
                (b - a).cross(c - a).dot(normal)
            }
            return List(points.size) { points[(best + it) % points.size] }
        }
        /** [block]'s faces as one closed part. */
        private inline fun closed(block: () -> Unit) {
            val start = faces.size
            block()
            if (faces.size > start) solids += start until faces.size
        }
        /** [materialOf] picks a side's own material by its index in [sides] (else [material]). */
        fun solid(points: List<V>, sides: List<IntArray>, material: PaperMaterial, materialOf: ((Int) -> PaperMaterial)? = null) = closed {
            val center = points.reduce { a, b -> a + b } * (1f / points.size)
            sides.forEachIndexed { index, side ->
                val p = side.map { points[it] }; val middle = p.reduce { a, b -> a + b } * (1f / p.size)
                face(p, materialOf?.invoke(index) ?: material, middle - center)
            }
        }
        /**
         * A detail laid just off a bigger face (glass, a stripe, a panel): it sorts straight after the face it lies on,
         * so it shows however low on a tall side it sits. Without a face under it, it is lifted a little instead.
         */
        fun decal(points: List<V>, material: PaperMaterial, outward: V) {
            val before = lift
            face(points, material, outward)
            val index = faces.lastIndex
            val under = faceUnder(index)
            faces[index] = if (under >= 0) faces[index].copy(over = under) else faces[index].copy(lift = maxOf(before, .04f))
        }
        private fun corners(face: PaperFace) = face.indices.map { vertices[it].let { v -> V(v.x, v.y, v.z) } }
        /** The earlier face facing the same way that face [index] lies on (within a few hundredths), or -1. */
        private fun faceUnder(index: Int): Int {
            val p = corners(faces[index]); val n = outline(p).unit()
            val c = p.reduce { a, b -> a + b } * (1f / p.size)
            var best = -1; var bestDistance = .035f
            for (i in 0 until index) {
                val q = corners(faces[i]); val m = outline(q)
                if (m.dot(m) < 1e-10f) continue
                val u = m.unit()
                if (u.dot(n) < .95f) continue
                val distance = abs(u.dot(c - q[0]))
                if (distance >= bestDistance || !inside(c, q, u)) continue
                best = i; bestDistance = distance
            }
            return best
        }
        /** Whether [c], dropped onto [polygon]'s plane, lies inside it. */
        private fun inside(c: V, polygon: List<V>, normal: V): Boolean {
            val ax = abs(normal.x); val ay = abs(normal.y); val az = abs(normal.z)
            fun uv(v: V): Pair<Float, Float> = when { ax >= ay && ax >= az -> v.y to v.z; ay >= az -> v.x to v.z; else -> v.x to v.y }
            val (px, py) = uv(c)
            var inside = false
            for (i in polygon.indices) {
                val (x1, y1) = uv(polygon[i]); val (x2, y2) = uv(polygon[(i + 1) % polygon.size])
                if ((y1 > py) != (y2 > py) && px < (x2 - x1) * (py - y1) / (y2 - y1) + x1) inside = !inside
            }
            return inside
        }
        /** A face seen from both sides (a sail, a propeller disc). */
        fun sheet(points: List<V>, material: PaperMaterial) {
            val normal = (points[1] - points[0]).cross(points[2] - points[0])
            face(points, material, normal); face(points, material, normal * -1f)
        }
        /** The quad [corners] thickened by [depth]: a closed slab at any angle (a windscreen, a fin, a leg shield). */
        fun slab(corners: List<V>, depth: V, material: PaperMaterial) =
            solid(corners + corners.map { it + depth }, BOX_SIDES, material)
        /** A box turned to [along] and [across] (unit vectors at right angles): a rotor blade. */
        fun turnedBox(center: V, along: V, across: V, length: Float, width: Float, height: Float, material: PaperMaterial) {
            val up = along.cross(across).unit()
            fun p(a: Float, b: Float, c: Float) = center + along * (a * length / 2) + across * (b * width / 2) + up * (c * height / 2)
            solid(listOf(p(-1f,-1f,-1f),p(1f,-1f,-1f),p(1f,1f,-1f),p(-1f,1f,-1f),p(-1f,-1f,1f),p(1f,-1f,1f),p(1f,1f,1f),p(-1f,1f,1f)), BOX_SIDES, material)
        }
        fun box(x: Float, y: Float, z: Float, w: Float, l: Float, h: Float, material: PaperMaterial) {
            val p = listOf(V(x-w/2,y-l/2,z-h/2),V(x+w/2,y-l/2,z-h/2),V(x+w/2,y+l/2,z-h/2),V(x-w/2,y+l/2,z-h/2),
                V(x-w/2,y-l/2,z+h/2),V(x+w/2,y-l/2,z+h/2),V(x+w/2,y+l/2,z+h/2),V(x-w/2,y+l/2,z+h/2))
            solid(p, BOX_SIDES, material)
        }
        fun bevelBox(x: Float, y: Float, z: Float, w: Float, l: Float, h: Float, material: PaperMaterial, bevel: Float = .14f) {
            val bx = w * bevel; val by = l * bevel
            val xy = listOf(-w/2+bx to -l/2, w/2-bx to -l/2, w/2 to -l/2+by, w/2 to l/2-by,
                w/2-bx to l/2, -w/2+bx to l/2, -w/2 to l/2-by, -w/2 to -l/2+by)
            prism(xy.map { (px,py) -> px+x to py+y }, z-h/2, z+h/2, material)
        }
        fun prism(xy: List<Pair<Float,Float>>, low: Float, high: Float, material: PaperMaterial) {
            val p = xy.map { V(it.first,it.second,low) } + xy.map { V(it.first,it.second,high) }
            val n=xy.size
            val sides=ArrayList<IntArray>(); sides += IntArray(n){it}; sides += IntArray(n){it+n}
            for(i in 0 until n) {val j=(i+1)%n; sides += intArrayOf(i,j,j+n,i+n)}
            solid(p,sides,material)
        }
        /**
         * Ring stations allow rounded little car/aircraft bodies without spheres or a high polygon count. [side] picks
         * the material of the side between rings k and k+1 at ring edge i (a deck, a glass canopy); the end caps are
         * [material].
         */
        fun loft(rings: List<List<V>>, material: PaperMaterial, side: ((segment: Int, edge: Int) -> PaperMaterial)? = null) {
            val n=rings.first().size; val p=rings.flatten(); val sides=ArrayList<IntArray>()
            sides += IntArray(n){it}; sides += IntArray(n){p.size-n+it}
            for(k in 0 until rings.size-1) for(i in 0 until n) {val j=(i+1)%n; sides += intArrayOf(k*n+i,k*n+j,(k+1)*n+j,(k+1)*n+i)}
            solid(p,sides,material,side?.let { pick -> { index: Int -> if(index<2) material else pick((index-2)/n,(index-2)%n) } })
        }
        fun rod(a: V, b: V, radius: Float, material: PaperMaterial, segments: Int = 6, cap: PaperMaterial = material) = closed {
            val axis=(b-a).unit(); val helper=if(abs(axis.z)<.9f) V(0f,0f,1f) else V(0f,1f,0f)
            val u=axis.cross(helper).unit()*radius; val v=axis.cross(u).unit()*radius
            val first=(0 until segments).map { val t=it*2*PI/segments; a+u*cos(t).toFloat()+v*sin(t).toFloat() }
            val second=first.map {it+(b-a)}
            face(first,cap,axis*-1f); face(second,cap,axis)
            for(i in 0 until segments) {val j=(i+1)%segments; face(listOf(first[i],first[j],second[j],second[i]),material,(first[i]+first[j])*.5f-a)}
        }
        fun ringX(x: Float, y: Float, z: Float, outer: Float, inner: Float, thick: Float, material: PaperMaterial, innerMaterial: PaperMaterial = material, segments: Int = 12) = closed {
            for(i in 0 until segments) {
                val a=i*2*PI/segments; val b=(i+1)*2*PI/segments
                fun p(xx:Float,r:Float,t:Double)=V(xx,y+cos(t).toFloat()*r,z+sin(t).toFloat()*r)
                val left=x-thick/2; val right=x+thick/2
                face(listOf(p(left,outer,a),p(left,outer,b),p(left,inner,b),p(left,inner,a)),material,V(-1f,0f,0f))
                face(listOf(p(right,outer,a),p(right,outer,b),p(right,inner,b),p(right,inner,a)),material,V(1f,0f,0f))
                val radial=V(0f,cos((a+b)/2).toFloat(),sin((a+b)/2).toFloat())
                face(listOf(p(left,outer,a),p(right,outer,a),p(right,outer,b),p(left,outer,b)),material,radial)
                face(listOf(p(left,inner,a),p(right,inner,a),p(right,inner,b),p(left,inner,b)),innerMaterial,radial*-1f)
            }
        }
    }

    /** The side order of an eight-cornered solid (box, slab): bottom, top, then the four walls. */
    internal val BOX_SIDES = listOf(intArrayOf(0,1,2,3),intArrayOf(4,5,6,7),intArrayOf(0,1,5,4),intArrayOf(1,2,6,5),intArrayOf(2,3,7,6),intArrayOf(3,0,4,7))

    internal fun bodyRing(y: Float, w: Float, low: Float, high: Float): List<V> {
        val b=(high-low)*.22f
        return ring(y,w,low,high,b,b)
    }

    /**
     * An eight-cornered section at [y]: [w] half-wide, from [low] to [high], its lower and upper corners cut by
     * [bLow] and [bHigh] (0: square). Edges, from the bottom round: 0 bottom, 2 right side, 4 top, 6 left side.
     */
    internal fun ring(y: Float, w: Float, low: Float, high: Float, bLow: Float, bHigh: Float): List<V> =
        listOf(V(-w*.76f,y,low),V(w*.76f,y,low),V(w,y,low+bLow),V(w,y,high-bHigh),V(w*.76f,y,high),V(-w*.76f,y,high),V(-w,y,high-bHigh),V(-w,y,low+bLow))

    /**
     * A boat's section at [y]: a narrow keel at [low], the widest at the [high] deck edge, the bilge at [mid].
     * Edges: 0 keel, 1 and 5 the lower sides, 2 and 4 the upper sides, 3 the deck.
     */
    internal fun hullRing(y: Float, w: Float, low: Float, mid: Float, high: Float): List<V> =
        listOf(V(-w*.3f,y,low),V(w*.3f,y,low),V(w*.88f,y,mid),V(w,y,high),V(-w,y,high),V(-w*.88f,y,mid))

    /** An oval section at [y] round ([cx], [cz]): [n] corners, edge i from angle i to i+1 (0 on the right, then up). */
    internal fun oval(y: Float, rx: Float, rz: Float, cz: Float, n: Int = 10, cx: Float = 0f): List<V> =
        (0 until n).map { val a=it*2*PI/n; V(cx+cos(a).toFloat()*rx,y,cz+sin(a).toFloat()*rz) }

    private fun car() = Builder().apply {
        loft(listOf(bodyRing(-.94f,.39f,.30f,.55f),bodyRing(-.78f,.53f,.25f,.66f),bodyRing(.60f,.53f,.25f,.67f),bodyRing(.94f,.44f,.31f,.56f)),PaperMaterial.Ivory)
        loft(listOf(bodyRing(-.65f,.40f,.61f,.79f),bodyRing(-.36f,.43f,.63f,1.12f),bodyRing(.18f,.43f,.63f,1.12f),bodyRing(.53f,.40f,.62f,.81f)),PaperMaterial.Ivory)
        // Terracotta fabric roof, separate sloping front/rear glass and two windows on each door.
        face(listOf(V(-.325f,-.34f,1.125f),V(.325f,-.34f,1.125f),V(.325f,.17f,1.125f),V(-.325f,.17f,1.125f)),PaperMaterial.Terracotta,V(0f,0f,1f))
        face(listOf(V(-.30f,.205f,1.114f),V(.30f,.205f,1.114f),V(.285f,.505f,.85f),V(-.285f,.505f,.85f)),PaperMaterial.Glass,V(0f,1f,1f))
        face(listOf(V(-.30f,-.385f,1.104f),V(.30f,-.385f,1.104f),V(.285f,-.625f,.83f),V(-.285f,-.625f,.83f)),PaperMaterial.Glass,V(0f,-1f,1f))
        for(sign in listOf(-1f,1f)) {
            face(listOf(V(sign*.455f,-.39f,.76f),V(sign*.455f,-.30f,.985f),V(sign*.455f,-.06f,.985f),V(sign*.455f,-.06f,.76f)),PaperMaterial.Glass,V(sign,0f,0f))
            face(listOf(V(sign*.455f,-.015f,.76f),V(sign*.455f,-.015f,.985f),V(sign*.455f,.20f,.985f),V(sign*.455f,.40f,.76f)),PaperMaterial.Glass,V(sign,0f,0f))
            box(sign*.538f,-.22f,.68f,.025f,.15f,.027f,PaperMaterial.Navy)
            for(y in listOf(-.61f,.62f)) {
                rod(V(sign*.49f,y,.265f),V(sign*.63f,y,.265f),.225f,PaperMaterial.Rubber,12)
                paint(PaperPaint.NONE) { rod(V(sign*.632f,y,.265f),V(sign*.645f,y,.265f),.125f,PaperMaterial.Ivory,10) }
            }
            // Head and tail lights keep the theme's paper whatever the car's colours.
            paint(PaperPaint.NONE) {
                rod(V(sign*.29f,.905f,.51f),V(sign*.29f,.975f,.51f),.105f,PaperMaterial.Ivory,10)
                box(sign*.32f,-.938f,.48f,.12f,.025f,.10f,PaperMaterial.Terracotta)
            }
        }
        bevelBox(0f,.965f,.325f,.76f,.075f,.085f,PaperMaterial.Navy)
        bevelBox(0f,-.95f,.325f,.72f,.07f,.08f,PaperMaterial.Navy)
        box(0f,.975f,.46f,.22f,.014f,.06f,PaperMaterial.Metal)
    }.build().narrowWidth(.8f)

    /** Body: the frame and cranks; Trim: the saddle and grips; the rest stays the theme's. */
    private fun bike() = Builder().apply {
        paint = PaperPaint.NONE
        for(y in listOf(-.73f,.73f)) {
            ringX(0f,y,.46f,.44f,.335f,.12f,PaperMaterial.Rubber,PaperMaterial.Ivory,14)
            rod(V(-.09f,y,.46f),V(.09f,y,.46f),.065f,PaperMaterial.Ivory,8)
            for(i in 0 until 6) {val t=i*2*PI/6; rod(V(0f,y,.46f),V(0f,y+cos(t).toFloat()*.34f,.46f+sin(t).toFloat()*.34f),.018f,PaperMaterial.Ivory,4)}
        }
        val rear=V(0f,-.73f,.46f); val crank=V(0f,-.05f,.45f); val seat=V(0f,-.31f,1.02f); val front=V(0f,.73f,.46f); val head=V(0f,.48f,1.04f)
        paint(PaperPaint.BODY) { for((a,b) in listOf(rear to seat,seat to crank,crank to rear,seat to head,head to crank,head to front)) rod(a,b,.048f,PaperMaterial.Terracotta) }
        rod(seat,V(0f,-.33f,1.18f),.035f,PaperMaterial.Ivory)
        paint(PaperPaint.TRIM) { bevelBox(0f,-.38f,1.20f,.28f,.34f,.075f,PaperMaterial.Navy) }
        rod(head,V(0f,.44f,1.27f),.033f,PaperMaterial.Ivory)
        rod(V(-.29f,.44f,1.27f),V(.29f,.44f,1.27f),.035f,PaperMaterial.Ivory)
        paint(PaperPaint.TRIM) { for(sign in listOf(-1f,1f)) rod(V(sign*.29f,.44f,1.27f),V(sign*.29f,.32f,1.27f),.045f,PaperMaterial.Navy) }
        rod(V(-.16f,-.05f,.45f),V(.16f,-.05f,.45f),.07f,PaperMaterial.Ivory,8)
        paint(PaperPaint.BODY) { rod(V(.16f,-.05f,.45f),V(.16f,.09f,.27f),.028f,PaperMaterial.Terracotta) }
        box(.19f,.09f,.27f,.18f,.12f,.055f,PaperMaterial.Navy)
    }.build()

    /** Body: the hull; Trim: the roof, the funnel's band and the flag; decks and lifebuoys stay the theme's. */
    private fun ferry() = Builder().apply {
        val hull=listOf(0f to 1.18f,.35f to .95f,.49f to .52f,.49f to -.78f,.31f to -1.00f,-.31f to -1.00f,-.49f to -.78f,-.49f to .52f,-.35f to .95f)
        paint(PaperPaint.BODY) { loft(listOf(hull.map {V(it.first*.72f,it.second*.89f,.10f)},hull.map {V(it.first,it.second,.43f)}),PaperMaterial.Navy) }
        paint = PaperPaint.NONE
        prism(hull,.43f,.49f,PaperMaterial.Ivory)
        bevelBox(0f,-.12f,.69f,.66f,1.18f,.40f,PaperMaterial.Ivory,.08f)
        bevelBox(0f,.10f,.97f,.64f,.78f,.20f,PaperMaterial.Paper,.06f)
        paint = PaperPaint.AUTO
        bevelBox(0f,.08f,1.10f,.73f,.87f,.065f,PaperMaterial.Terracotta,.06f)
        for(x in listOf(-.22f,0f,.22f)) box(x,.495f,.98f,.16f,.016f,.105f,PaperMaterial.Glass)
        for(sign in listOf(-1f,1f)) {
            for(y in listOf(-.50f,-.20f,.10f,.38f)) box(sign*.337f,y,.74f,.018f,.19f,.12f,PaperMaterial.Glass)
            for(y in listOf(-.12f,.19f)) box(sign*.327f,y,.98f,.018f,.22f,.105f,PaperMaterial.Glass)
            rod(V(sign*.45f,-.72f,.65f),V(sign*.45f,.69f,.65f),.021f,PaperMaterial.Paper,4)
            for(y in listOf(-.72f,.65f)) rod(V(sign*.45f,y,.48f),V(sign*.45f,y,.65f),.021f,PaperMaterial.Paper,4)
            paint(PaperPaint.NONE) { ringX(sign*.35f,-.43f,.72f,.105f,.055f,.035f,PaperMaterial.Terracotta,PaperMaterial.Ivory,8) }
        }
        bevelBox(0f,-.49f,1.16f,.24f,.25f,.42f,PaperMaterial.Navy,.10f)
        bevelBox(0f,-.49f,1.38f,.27f,.28f,.09f,PaperMaterial.Terracotta,.10f)
        rod(V(0f,.43f,1.12f),V(0f,.43f,1.42f),.018f,PaperMaterial.Navy,4)
        // Seen from both sides: one-sided, the flag was gone from the front.
        sheet(listOf(V(0f,.43f,1.42f),V(.22f,.43f,1.35f),V(0f,.43f,1.28f)),PaperMaterial.Terracotta)
    }.build()

    private fun plane() = Builder().apply {
        val stations=listOf(Triple(-1.20f,.055f,.45f),Triple(-.88f,.14f,.43f),Triple(.55f,.20f,.43f),Triple(1.02f,.13f,.43f),Triple(1.24f,.025f,.42f))
        loft(stations.map {(y,r,z)->(0 until 10).map {val a=it*2*PI/10;V(cos(a).toFloat()*r,y,z+sin(a).toFloat()*r)}},PaperMaterial.Ivory)
        for(sign in listOf(-1f,1f)) {
            prism(listOf(0f to .33f,sign*1.32f to -.24f,sign*1.32f to -.47f,sign*.11f to -.24f),.37f,.425f,PaperMaterial.Paper)
            box(sign*1.30f,-.34f,.47f,.035f,.24f,.16f,PaperMaterial.Terracotta)
            prism(listOf(0f to -.69f,sign*.54f to -1.01f,sign*.54f to -1.16f,0f to -.99f),.44f,.485f,PaperMaterial.Paper)
            rod(V(sign*.59f,-.28f,.255f),V(sign*.59f,.17f,.255f),.105f,PaperMaterial.Ivory,10)
            rod(V(sign*.59f,.172f,.255f),V(sign*.59f,.185f,.255f),.075f,PaperMaterial.Navy,10)
            for(y in listOf(-.35f,-.14f,.07f,.28f,.49f)) {
                val radius=.14f+(y+.88f)/1.43f*.06f
                box(sign*(radius+.005f),y,.46f,.012f,.07f,.065f,PaperMaterial.Glass)
            }
        }
        solid(listOf(V(-.028f,-1.12f,.47f),V(-.028f,-.70f,.47f),V(-.028f,-1.01f,.94f),V(-.028f,-1.18f,.94f),
            V(.028f,-1.12f,.47f),V(.028f,-.70f,.47f),V(.028f,-1.01f,.94f),V(.028f,-1.18f,.94f)),
            listOf(intArrayOf(0,1,2,3),intArrayOf(4,5,6,7),intArrayOf(0,1,5,4),intArrayOf(1,2,6,5),intArrayOf(2,3,7,6),intArrayOf(3,0,4,7)),PaperMaterial.Terracotta)
        face(listOf(V(-.09f,.76f,.60f),V(.09f,.76f,.60f),V(.075f,1.015f,.568f),V(-.075f,1.015f,.568f)),PaperMaterial.Glass,V(0f,1f,1f))
    }.build()

    private fun walk() = Builder().apply {
        bevelBox(0f,0f,1.34f,.53f,.34f,.64f,PaperMaterial.Navy,.13f)
        box(0f,.174f,1.36f,.16f,.016f,.51f,PaperMaterial.Ivory)
        bevelBox(0f,-.255f,1.35f,.39f,.23f,.50f,PaperMaterial.Terracotta,.14f)
        box(0f,-.382f,1.46f,.30f,.028f,.15f,PaperMaterial.Terracotta)
        rod(V(0f,0f,1.64f),V(0f,0f,1.75f),.10f,PaperMaterial.Skin,6)
        bevelBox(0f,.015f,1.91f,.37f,.34f,.39f,PaperMaterial.Skin,.18f)
        bevelBox(0f,-.015f,2.08f,.40f,.36f,.13f,PaperMaterial.Navy,.18f)
        box(0f,-.15f,1.96f,.36f,.08f,.26f,PaperMaterial.Navy)
        bevelBox(0f,.20f,1.91f,.075f,.09f,.08f,PaperMaterial.Skin,.18f)
        for(sign in listOf(-1f,1f)) {
            box(sign*.20f,.183f,1.43f,.045f,.024f,.40f,PaperMaterial.Terracotta)
            bone=bone(sign*.34f,0f,1.55f,sign*28f)
            rod(V(sign*.32f,0f,1.55f),V(sign*.39f,.015f,1.19f),.095f,PaperMaterial.Navy,5)
            rod(V(sign*.39f,.015f,1.19f),V(sign*.39f,.12f,.99f),.075f,PaperMaterial.Navy,5)
            bevelBox(sign*.39f,.14f,.95f,.14f,.15f,.17f,PaperMaterial.Skin,.18f)
            bone=bone(sign*.155f,0f,1.04f,-sign*27f)
            rod(V(sign*.155f,0f,1.05f),V(sign*.16f,.015f,.59f),.105f,PaperMaterial.Navy,5)
            rod(V(sign*.16f,.015f,.59f),V(sign*.16f,0f,.18f),.085f,PaperMaterial.Navy,5)
            bevelBox(sign*.16f,.10f,.12f,.225f,.38f,.16f,PaperMaterial.Ivory,.18f)
            bone=0
        }
    }.build()

    private fun train() = Builder().apply {
        // Original small steam locomotive: longitudinal boiler, rear cab and railway running gear.
        bevelBox(0f,-.02f,.48f,.92f,2.18f,.16f,PaperMaterial.Navy,.05f)
        bevelBox(0f,-.02f,.59f,1.02f,2.20f,.09f,PaperMaterial.Ivory,.04f)
        rod(V(0f,-.50f,.94f),V(0f,.88f,.94f),.37f,PaperMaterial.Ivory,12)
        for(y in listOf(-.23f,.36f)) rod(V(0f,y-.018f,.94f),V(0f,y+.018f,.94f),.386f,PaperMaterial.Paper,12)
        rod(V(0f,.88f,.94f),V(0f,.925f,.94f),.385f,PaperMaterial.Paper,12)
        rod(V(0f,.928f,.94f),V(0f,.95f,.94f),.275f,PaperMaterial.Ivory,12)
        rod(V(0f,.953f,.94f),V(0f,.972f,.94f),.11f,PaperMaterial.Paper,10)
        // Faceted chimney with a dark open-looking top and a terracotta rim.
        rod(V(0f,.58f,1.27f),V(0f,.58f,1.66f),.14f,PaperMaterial.Terracotta,10)
        rod(V(0f,.58f,1.62f),V(0f,.58f,1.72f),.185f,PaperMaterial.Terracotta,10)
        face((0 until 10).map { val a=it*2*PI/10; V(cos(a).toFloat()*.135f,.58f+sin(a).toFloat()*.135f,1.723f) },PaperMaterial.Navy,V(0f,0f,1f))
        bevelBox(0f,-.79f,1.09f,.88f,.65f,.96f,PaperMaterial.Ivory,.04f)
        // Arched paper roof, with the same silhouette seen from the front and rear.
        val roof = listOf(-.52f to 1.53f,-.43f to 1.64f,-.22f to 1.73f,.22f to 1.73f,.43f to 1.64f,.52f to 1.53f)
        loft(listOf(-1.17f,-.41f).map { y -> roof.map { (x,z) -> V(x,y,z) } },PaperMaterial.Terracotta)
        box(0f,-1.121f,1.26f,.59f,.018f,.38f,PaperMaterial.Glass)
        box(0f,-.455f,1.39f,.56f,.018f,.23f,PaperMaterial.Glass)
        for(sign in listOf(-1f,1f)) {
            box(sign*.446f,-.79f,1.29f,.018f,.40f,.35f,PaperMaterial.Glass)
            box(sign*.46f,-.79f,.72f,.045f,.52f,.08f,PaperMaterial.Paper)
            for(y in listOf(-.68f,-.06f,.56f)) {
                // Thin metal rims and an inner flange; open navy spokes, never rubber tyres/fenders.
                ringX(sign*.49f,y,.32f,.29f,.235f,.075f,PaperMaterial.Metal,segments=8)
                for(i in 0 until 8) {
                    val a=i*2*PI/8; val b=(i+1)*2*PI/8
                    fun p(r:Float,t:Double)=V(sign*.449f,y+cos(t).toFloat()*r,.32f+sin(t).toFloat()*r)
                    face(listOf(p(.31f,a),p(.31f,b),p(.235f,b),p(.235f,a)),PaperMaterial.Metal,V(sign,0f,0f))
                }
                for(i in 0 until 3) {
                    val a=i*PI/3; val dy=cos(a).toFloat()*.24f; val dz=sin(a).toFloat()*.24f
                    rod(V(sign*.49f,y-dy,.32f-dz),V(sign*.49f,y+dy,.32f+dz),.027f,PaperMaterial.Navy,4)
                }
                rod(V(sign*.45f,y,.32f),V(sign*.555f,y,.32f),.065f,PaperMaterial.Metal,6)
            }
            // Small leading steel wheels sit behind the cowcatcher, below the front platform.
            rod(V(sign*.39f,.98f,.19f),V(sign*.47f,.98f,.19f),.16f,PaperMaterial.Metal,10,PaperMaterial.Navy)
            rod(V(sign*.47f,.98f,.19f),V(sign*.485f,.98f,.19f),.06f,PaperMaterial.Metal,6)
            rod(V(sign*.558f,-.68f,.32f),V(sign*.558f,.56f,.32f),.033f,PaperMaterial.Metal,4)
            box(sign*.38f,.99f,.69f,.17f,.12f,.17f,PaperMaterial.Navy)
            rod(V(sign*.38f,1.052f,.69f),V(sign*.38f,1.07f,.69f),.058f,PaperMaterial.Paper,8)
        }
        solid(listOf(V(-.49f,1.02f,.48f),V(.49f,1.02f,.48f),V(.55f,1.32f,.10f),V(-.55f,1.32f,.10f),
            V(-.45f,1.0f,.15f),V(.45f,1.0f,.15f)),
            listOf(intArrayOf(0,1,2,3),intArrayOf(3,2,5,4),intArrayOf(0,4,5,1),intArrayOf(0,3,4),intArrayOf(1,5,2)),PaperMaterial.Navy)
    }.build()
}
