package com.justdataplease.boringtravel.clip

import kotlin.math.*

/** Reusable camera workspace. Pure Kotlin, deterministic, and owned by one renderer/thread. */
internal class PaperVehicleProjection {
    var coordinates = FloatArray(0); private set // screen x, screen y, camera depth; normalized to marker size
    var faceOrder = IntArray(0); private set
    var faceDepth = FloatArray(0); private set
    var faceLight = FloatArray(0); private set
    var visibleFaces = 0; private set
    var groundY = 0f; private set
    var yawRadians = 0f; private set
    private var boneSin = FloatArray(0)
    private var boneCos = FloatArray(0)
    private var spinSin = FloatArray(0)
    private var spinCos = FloatArray(0)
    private var shown = BooleanArray(0)

    /** heading is clockwise from screen north; tilt is degrees away from a top-down camera. */
    fun project(mesh: PaperMesh, heading: Float, tilt: Float, seconds: Float): PaperVehicleProjection {
        val s = if (seconds.isFinite()) seconds % 60f else 0f
        return pose(mesh, heading, tilt, sin(s * (2f*PI.toFloat()) * 1.15f), s)
    }

    /**
     * The same with the limbs at [stride] (−1..1: each bone turned by its swing × stride), e.g. a [WalkCycle]'s, and
     * rotors [seconds] into their turning ([PaperBone.spin]).
     */
    fun pose(mesh: PaperMesh, heading: Float, tilt: Float, stride: Float, seconds: Float = 0f): PaperVehicleProjection {
        if (coordinates.size < mesh.vertices.size * 3) coordinates = FloatArray(mesh.vertices.size * 3)
        if (faceOrder.size < mesh.faces.size) {
            faceOrder = IntArray(mesh.faces.size); faceDepth = FloatArray(mesh.faces.size); faceLight = FloatArray(mesh.faces.size)
            shown = BooleanArray(mesh.faces.size)
        }
        if (boneSin.size < mesh.bones.size) {
            boneSin = FloatArray(mesh.bones.size); boneCos = FloatArray(mesh.bones.size)
            spinSin = FloatArray(mesh.bones.size); spinCos = FloatArray(mesh.bones.size)
        }
        val cameraTilt = (if (tilt.isFinite()) tilt else 40f).coerceIn(30f,65f) * (PI.toFloat()/180f)
        val ct = cos(cameraTilt); val st = sin(cameraTilt)
        val direction = (if (heading.isFinite()) heading % 360f else 0f) * (PI.toFloat()/180f)
        // Camera compression of the ground's y-axis must not turn the nose away from the visible route tangent.
        yawRadians = atan2(sin(direction)*ct, cos(direction))
        val sy = sin(yawRadians); val cy = cos(yawRadians)
        val swing = if(stride.isFinite()) stride.coerceIn(-1f,1f) else 0f
        val turning = if(seconds.isFinite()) seconds else 0f
        for(i in mesh.bones.indices) {
            val angle = mesh.bones[i].swing * swing * (PI.toFloat()/180f)
            boneSin[i]=sin(angle); boneCos[i]=cos(angle)
            val spin = mesh.bones[i].spin * turning * 2f * PI.toFloat()
            spinSin[i]=sin(spin); spinCos[i]=cos(spin)
        }
        val scale = 1f/(mesh.radius*2f)
        groundY = mesh.centerZ * st * scale
        for(i in mesh.vertices.indices) {
            val v=mesh.vertices[i]; val b=mesh.bones[v.bone]
            // A rotor turns about its upright axis first; limbs swing about x.
            var vx=v.x; var vy=v.y
            if(b.spin!=0f) {
                val dx=v.x-b.x; val dy=v.y-b.y
                vx=b.x+dx*spinCos[v.bone]-dy*spinSin[v.bone]; vy=b.y+dx*spinSin[v.bone]+dy*spinCos[v.bone]
            }
            val posedY = b.y + (vy-b.y)*boneCos[v.bone] - (v.z-b.z)*boneSin[v.bone]
            val posedZ = b.z + (vy-b.y)*boneSin[v.bone] + (v.z-b.z)*boneCos[v.bone] - mesh.centerZ
            val x=vx*cy+posedY*sy; val y=-vx*sy+posedY*cy
            coordinates[i*3]=x*scale
            coordinates[i*3+1]=(-y*ct-posedZ*st)*scale
            coordinates[i*3+2]=(-y*st+posedZ*ct)*scale
        }
        visibleFaces=0
        for(i in mesh.faces.indices) {
            shown[i]=false
            val ids=mesh.faces[i].indices
            val a=ids[0]*3; val b=ids[1]*3; val c=ids[2]*3
            val ux=coordinates[b]-coordinates[a]; val uy=coordinates[b+1]-coordinates[a+1]; val uz=coordinates[b+2]-coordinates[a+2]
            val vx=coordinates[c]-coordinates[a]; val vy=coordinates[c+1]-coordinates[a+1]; val vz=coordinates[c+2]-coordinates[a+2]
            // The screen y inversion reverses winding, hence the minus sign for a camera-space outward normal.
            val nx=uz*vy-uy*vz; val ny=ux*vz-uz*vx; val nz=uy*vx-ux*vy
            if(nz <= .0000001f) continue
            val length=sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(.000001f)
            val light=((-.38f*nx-.49f*ny+.785f*nz)/length).coerceIn(0f,1f)
            faceLight[i]=.66f+.34f*light
            var depth=0f
            for(index in ids) depth+=coordinates[index*3+2]
            // A detail goes straight after the face it lies on (the stable sort keeps it after on a tie).
            val over=mesh.faces[i].over
            faceDepth[i]=if(over in 0 until i && shown[over]) faceDepth[over] else depth/ids.size+mesh.faces[i].lift*scale
            shown[i]=true
            faceOrder[visibleFaces++]=i
        }
        // In-place stable insertion sort: a few hundred faces, no boxed comparator or per-frame collections.
        for(i in 1 until visibleFaces) {
            val value=faceOrder[i]; val depth=faceDepth[value]; var j=i-1
            while(j>=0 && faceDepth[faceOrder[j]]>depth) {faceOrder[j+1]=faceOrder[j]; j--}
            faceOrder[j+1]=value
        }
        return this
    }
}
