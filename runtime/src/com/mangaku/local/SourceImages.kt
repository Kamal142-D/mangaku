package com.mangaku.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Same image bytes and source cookies for the recovered reader AND downloader.
 * Loopback paths are random capabilities, never arbitrary remote URLs. They live only in memory.
 */
internal object SourceImages {
    private data class Entry(val source: Source, val page: SourcePage, val created: Long)
    private val entries=linkedMapOf<String,Entry>()
    private val workers=java.util.concurrent.ThreadPoolExecutor(2,2,0,java.util.concurrent.TimeUnit.SECONDS,java.util.concurrent.ArrayBlockingQueue<Runnable>(64))
    private val server by lazy {
        ServerSocket(0,32,InetAddress.getByName("127.0.0.1")).also { socket ->
            Thread({ while(!socket.isClosed) {
                val client=socket.accept()
                try { workers.execute { client.use { c ->
                    c.soTimeout=15000
                    try {
                        val input=c.getInputStream(); val request=StringBuilder()
                        while(request.length<8192 && !request.endsWith("\r\n\r\n")) { val b=input.read(); if(b<0) break; request.append(b.toChar()) }
                        require(request.endsWith("\r\n\r\n"))
                        val line=request.lineSequence().first().split(' ')
                        require(line.size==3 && line[0]=="GET")
                        val entry=synchronized(entries) { entries[line[1].removePrefix("/")] } ?: error("Expired image request")
                        check(System.currentTimeMillis()-entry.created<6*60*60*1000) { "Expired image request" }
                        val bytes=load(entry.source,entry.page)
                        val out=c.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes); out.flush()
                    } catch(e: Exception) { runCatching { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }; android.util.Log.w("MangakuSources","Image failed: ${e.javaClass.simpleName}") }
                } } } catch(_: java.util.concurrent.RejectedExecutionException) { client.close() }
            } },"Mangaku source images").apply { isDaemon=true; start() }
        }
    }
    fun register(source: Source, pages: List<SourcePage>): List<String> {
        val port=server.localPort; val now=System.currentTimeMillis()
        return synchronized(entries) {
            entries.entries.removeAll { now-it.value.created>6*60*60*1000 }
            // ponytail: retain 20,000 active page capabilities; reopen a chapter after eviction.
            while(entries.size+pages.size>20000) entries.remove(entries.keys.first())
            pages.map { page -> val key=UUID.randomUUID().toString(); entries[key]=Entry(source,page,now); "http://127.0.0.1:$port/$key" }
        }
    }
    fun load(source: Source, page: SourcePage): ByteArray {
        var bytes=SourceHttp.bytes(page.url,source,referer=page.referer,limit=64*1024*1024)
        val extra=page.extra ?: return bytes
        if(extra.has("key")) {
            require(bytes.size>28); val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(Base64.decode(extra.getString("key"),Base64.DEFAULT),"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            return cipher.doFinal(bytes.copyOfRange(12,bytes.size))
        }
        if(extra.has("right")) {
            val left=bitmap(bytes); val right=try { bitmap(SourceHttp.bytes(extra.getString("right"),source,referer=page.referer,limit=64*1024*1024)) } catch(e: Throwable) { left.recycle(); throw e }
            try {
                require((left.width.toLong()+right.width)*maxOf(left.height,right.height)<=24000000) { "Split image too large" }
                val joined=Bitmap.createBitmap(left.width+right.width,maxOf(left.height,right.height),Bitmap.Config.ARGB_8888)
                try { val canvas=Canvas(joined); canvas.drawBitmap(left,0f,0f,null); canvas.drawBitmap(right,left.width.toFloat(),0f,null); return encode(joined) } finally { joined.recycle() }
            } finally { left.recycle(); right.recycle() }
        }
        if(extra.has("overlays")) {
            val bitmap=bitmap(bytes); try {
                val canvas=Canvas(bitmap)
                for(b in extra.array("overlays").objects()) {
                    val text=b.getString("text"); require(text.length<=20000)
                    val width=b.getDouble("w").toInt(); val height=b.getDouble("h").toFloat()
                    require(width in 1..bitmap.width*2 && height>0 && height<=bitmap.height*2)
                    val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=b.optDouble("font_size_px",37.3).toFloat().coerceIn(1f,512f) }
                    fun layout()=StaticLayout.Builder.obtain(text,0,text.length,paint,width).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).setLineSpacing(0f,b.optDouble("line_height",1.1).toFloat().coerceIn(0.5f,4f)).build()
                    var block=layout()
                    while(block.height>height && paint.textSize>1f) { paint.textSize-=0.5f; block=layout() }
                    val fontHeight=paint.fontMetrics.let { it.bottom-it.top }
                    val top=b.getDouble("y").toFloat() + if(block.lineCount<height/fontHeight) (height-block.lineCount*fontHeight)/2 else 0f
                    canvas.save(); canvas.translate(b.getDouble("x").toFloat(),top); canvas.rotate(b.optDouble("angle",0.0).toFloat())
                    paint.color=Color.parseColor(b.string("stroke_color").ifBlank { "#ffffff" }); paint.style=Paint.Style.FILL_AND_STROKE; paint.strokeWidth=b.optDouble("stroke_width_px",3.0).toFloat().coerceIn(0f,100f); block.draw(canvas)
                    paint.color=Color.parseColor(b.string("color").ifBlank { "#000000" }); paint.style=Paint.Style.FILL; paint.strokeWidth=0f; block.draw(canvas); canvas.restore()
                }
                bytes=encode(bitmap)
            } finally { bitmap.recycle() }
        }
        return bytes
    }
    private fun bitmap(bytes: ByteArray): Bitmap {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=24000000) { "Image too large or invalid" }
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inMutable=true; inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("Image decode failed")
    }
    private fun encode(bitmap: Bitmap)=ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); out.toByteArray() }
}
