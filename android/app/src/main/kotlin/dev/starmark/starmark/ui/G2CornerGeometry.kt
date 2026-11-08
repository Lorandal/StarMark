package dev.starmark.starmark.ui

import kotlin.math.*

/** Platform-independent boundary shared by Compose and Android masks. Coordinates are pixels. */
interface G2PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
    fun arcTo(centerX: Float, centerY: Float, radius: Float, startDegrees: Float, sweepDegrees: Float)
    fun close()
}

enum class G2CornerProfile { Miuix, Circular, MiuixSurface }

object G2CornerGeometry {
    const val EXTENSION = 1.1f
    private const val STRAIGHT_EXTENSION = .15
    private const val CIRCLE_CONTROL = .5522847498307936

    fun write(sink: G2PathSink, width: Float, height: Float, radius: Float,
        extension: Float = EXTENSION, profile: G2CornerProfile = G2CornerProfile.Miuix) =
        write(sink, width, height, radius, radius, radius, radius, extension, profile)

    /**
     * Approved local joins: circular ends retain the original circle beyond 15 degrees;
     * Miuix retains its original t=.12..88 section. Only actual straight junctions change.
     * Space is taken from the adjacent straight edge, never from another corner's radius.
     */
    fun write(sink: G2PathSink, width: Float, height: Float,
        topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float,
        extension: Float = EXTENSION, profile: G2CornerProfile = G2CornerProfile.Miuix) {
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return
        val ext = if (extension.isFinite()) extension.coerceIn(1f, 2f) else EXTENSION
        val halfMin = minOf(width, height) / 2f
        fun extent(r: Float) = if (r.isFinite()) (r.coerceAtLeast(0f) * ext).coerceAtMost(halfMin).toDouble() else 0.0
        val a=extent(topLeft); val b=extent(topRight); val c=extent(bottomRight); val d=extent(bottomLeft)
        fun budget(length: Float, first: Double, second: Double): Double {
            val wanted=STRAIGHT_EXTENSION*(first+second)
            return if (wanted==0.0) 0.0 else ((length-first-second)/wanted).coerceIn(0.0,1.0)
        }
        val top=budget(width,a,b); val right=budget(height,b,c)
        val bottom=budget(width,c,d); val left=budget(height,d,a)
        fun control(r: Double): Double? = when (profile) {
            G2CornerProfile.Circular -> null
            G2CornerProfile.Miuix -> .643
            G2CornerProfile.MiuixSurface -> {
                val blend=((r/halfMin-.7853982)/(1-.7853982)).coerceIn(0.0,1.0)
                if (blend==1.0) null else .643+(CIRCLE_CONTROL-.643)*blend
            }
        }
        fun line(x: Double,y: Double) = sink.lineTo(x.toFloat(),y.toFloat())
        sink.moveTo((a*(1+STRAIGHT_EXTENSION*top)).toFloat(),0f)
        line(width-b*(1+STRAIGHT_EXTENSION*top),0.0)
        corner(sink,width-b,0.0,b,1,0,control(b),top,right)
        line(width.toDouble(),height-c*(1+STRAIGHT_EXTENSION*right))
        corner(sink,width.toDouble(),height-c,c,0,1,control(c),right,bottom)
        line(d*(1+STRAIGHT_EXTENSION*bottom),height.toDouble())
        corner(sink,d,height.toDouble(),d,-1,0,control(d),bottom,left)
        line(0.0,a*(1+STRAIGHT_EXTENSION*left))
        corner(sink,0.0,a,a,0,-1,control(a),left,top)
        sink.close()
    }

    private data class Point(val x: Double,val y: Double) {
        operator fun plus(p: Point)=Point(x+p.x,y+p.y)
        operator fun minus(p: Point)=Point(x-p.x,y-p.y)
        operator fun times(k: Double)=Point(x*k,y*k)
    }
    private data class Shoulder(val q: Point,val a: Double,val b: Double)
    private fun point(c: Double,t: Double)=Point(
        3*(1-t).pow(2)*t*c+3*(1-t)*t*t+t*t*t,
        3*(1-t)*t*t*(1-c)+t*t*t)
    private fun velocity(c: Double,t: Double)=Point(
        3*((1-t).pow(2)*c+2*(1-t)*t*(1-c)),
        3*(2*(1-t)*t*(1-c)+t*t*c))

    private fun shoulder(c: Double?,scale: Double): Shoulder {
        if (c==null) {
            val theta=PI/12*scale
            val h=tan(theta/2)
            return Shoulder(Point(sin(theta),2*sin(theta/2).pow(2)),h-1.5*h*h/sin(theta),h)
        }
        val t=.12*scale
        val q=point(c,t); val v=velocity(c,t)
        val ax=6*((1-t)*(1-2*c)+t*(c-1))
        val ay=6*((1-t)*(1-c)+t*(2*c-1))
        val curvature=(v.x*ay-v.y*ax)/(v.x*v.x+v.y*v.y).pow(1.5)
        val b=q.x-v.x*q.y/v.y
        val distance=hypot(q.x-b,q.y)
        return Shoulder(q,b-1.5*curvature*distance.pow(3)/q.y,b)
    }

    private fun corner(sink: G2PathSink,x: Double,y: Double,r: Double,ux: Int,uy: Int,
        c: Double?,startScale: Double,endScale: Double) {
        if (r<=0.0) return
        fun px(p: Point)=(x+(ux*p.x-uy*p.y)*r).toFloat()
        fun py(p: Point)=(y+(uy*p.x+ux*p.y)*r).toFloat()
        fun cubic(a: Point,b: Point,q: Point)=sink.cubicTo(px(a),py(a),px(b),py(b),px(q),py(q))
        val start=if(startScale>0) shoulder(c,startScale) else null
        val end=if(endScale>0) shoulder(c,endScale) else null
        if(start!=null) cubic(Point(start.a,0.0),Point(start.b,0.0),start.q)
        if(c==null) {
            val center=Point(0.0,1.0)
            val rotation=atan2(uy.toDouble(),ux.toDouble())*180/PI
            sink.arcTo(px(center),py(center),r.toFloat(),(rotation-90+15*startScale).toFloat(),
                (90-15*startScale-15*endScale).toFloat())
        } else {
            val t0=.12*startScale; val t1=1-.12*endScale; val dt=(t1-t0)/3
            val p0=point(c,t0); val p1=point(c,t1)
            cubic(p0+velocity(c,t0)*dt,p1-velocity(c,t1)*dt,p1)
        }
        if(end!=null) cubic(Point(1.0,1-end.b),Point(1.0,1-end.a),Point(1.0,1+STRAIGHT_EXTENSION*endScale))
    }
}
