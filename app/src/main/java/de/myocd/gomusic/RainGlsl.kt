package de.myocd.gomusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 雨季背景 —— Lavender-z/demo「雨特效（css+js）」的原实现，严格照搬。
 *
 * 下面两段 GLSL 与 script.js 里的 shader.vertex / shader.fragment 逐字一致：
 * N12 噪声、Layer() 四层叠加、32 次散景采样、smoothstep 水滴/拖尾，
 * 一个常量、一个公式都没有改动。
 *
 * 浏览器里的 curtains.js 负责平面网格与 uniform：widthSegments/heightSegments
 * 为 40×40（9600 个顶点），onRender 每帧把 uTime 加 1，uReso 为窗口尺寸。
 * 这里用 EGL14 pbuffer + GLES20 做同一件事：40×40 网格、每帧 uTime 加 1，
 * dispImage 绑成专辑封面（demo 里是 unsplash 图），uMouse 固定 0。
 * 渲染结果 glReadPixels 成 Bitmap，交给录制帧画布。
 *
 * 26.11.28：浏览器桌面 GPU 把 mediump 当 fp32 用，手机 GPU 的 mediump 是 fp16，
 * N12 噪声与 sin 采样在 fp16 下会退化成条带花屏。片元着色器改用 highp
 * （GL_FRAGMENT_PRECISION_HIGH 保护），公式、常量、层数、采样数全部不变。
 */
object RainGlsl {

    // ↓↓↓ 原样复制自 script.js，禁止修改 ↓↓↓
    private val VERTEX_SHADER = """    
      #ifdef GL_ES
      precision mediump float;
      #endif
      
      // lib设置的强制属性
      attribute vec3 aVertexPosition;
      attribute vec2 aTextureCoord;
  
      // lib设置的强制统一，包含模型视图和投影矩阵
      uniform mat4 uMVMatrix;
      uniform mat4 uPMatrix;
  
      uniform mat4 dispImageMatrix;
  
      // 将顶点和纹理坐标传递给着色器
      varying vec3 vVertexPosition;
      varying vec2 vTextureCoord;
  
      void main() {
          vec3 vertexPosition = aVertexPosition;
          gl_Position = uPMatrix * uMVMatrix * vec4(vertexPosition, 1.0);
  
          // 设置varyings
          vTextureCoord = (dispImageMatrix * vec4(aTextureCoord, 0., 1.)).xy;
          vVertexPosition = vertexPosition;
      }"""

    private val FRAGMENT_SHADER = """
      #ifdef GL_ES
      #ifdef GL_FRAGMENT_PRECISION_HIGH
      precision highp float;
      #else
      precision mediump float;
      #endif
      #endif
      
      #define PI2 6.28318530718
      #define PI 3.14159265359
      #define S(a,b,n) smoothstep(a,b,n)
      
      // 获得varyings
      varying vec3 vVertexPosition;
      varying vec2 vTextureCoord;
  
      // 用uniform声明
      uniform float uTime;
      uniform vec2 uReso;
      uniform vec2 uMouse;
  
      // 纹理采样器
      uniform sampler2D dispImage;
      uniform sampler2D blurImage;
    
      // 噪声
      float N12(vec2 p){
        p = fract(p * vec2(123.34, 345.45));
        p += dot(p, p + 34.345);
  
        return fract(p.x * p.y);
      }
  
      vec3 Layer(vec2 uv0, float t){
  
        vec2 asp = vec2(2., 1.);
  
        vec2 uv1 = uv0 * 3. * asp;
  
        uv1.y += t * .25;
  
        vec2 gv = fract(uv1) - .5;
        vec2 id = floor(uv1);
  
        float n = N12(id);
  
        t+= n * PI2;
  
        float w = uv0.y * 10.;
        float x = (n - .5) * .8;
        x += (.4 - abs(x)) * sin(3. * w) * pow(sin(w), 6.) * .45;
        float y = -sin(t + sin(t + sin(t) * .5)) * (.5 - .06);
        y -= (gv.x - x) * (gv.x - x); // sesgar;
  
        vec2 dropPos = (gv - vec2(x, y)) / asp; 
        float drop = S(.03, .02, length(dropPos));
  
        vec2 trailPos = (gv - vec2(x, t * .25)) / asp; 
        trailPos.y = (fract(trailPos.y * 8.) - .5) / 8.;
        float trail = S(.02, .015, length(trailPos));
  
        float fogTrail = S(-.05, .05, dropPos.y);
  
        fogTrail *= S(.5, y, gv.y);
        trail *= fogTrail;
        fogTrail *= S(.03, .015, abs(dropPos.x));
  
        vec2 off = drop * dropPos + trail * trailPos;
  
        return vec3(off, fogTrail);
      }
    
      void main() {      
            float dist = 5.;
            float blurSize = 5.;
            float t = mod(uTime * .03, 7200.);
  
            vec4 c = vec4(0);
            vec2 uv = vTextureCoord;    
  
            vec3 drops = Layer(uv, t);
            drops += Layer(uv * 1.25 + 7.54, t);
            drops += Layer(uv * 1.35 + 1.54, t);
            drops += Layer(uv * 1.57 - 7.54, t);
  
            float blur = blurSize * 7. * (1. - drops.z);
  
            vec4 col = vec4(0.);
            int numSamples = 32;
            float a = N12(uv) * PI2;
        
            blur *= .0005;
            uv += drops.xy * dist;
              
            for(int n = 0; n < 32; n++){
              vec2 off = vec2(sin(a), cos(a)) * blur;
              float d = fract(sin((float(n) + 1.) * 546.) * 5424.);
              d = sqrt(d);         
              off *= d;
              col += texture2D(dispImage, uv + off);
              a++;
            }
        
            col /= float(numSamples);
   
            gl_FragColor = col;
      }
      """
    // ↑↑↑ 原样复制自 script.js，禁止修改 ↑↑↑

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var surfW = 0
    private var surfH = 0
    private var program = 0
    private var vbo = 0
    private var ibo = 0
    private var tex = 0
    private var coverId = 0
    private var coverKey = ""
    private var frame = 0f
    private var idxCount = 0
    private var bytesBuf: ByteBuffer? = null
    private var pxBuf: IntArray? = null
    private var outBmp: Bitmap? = null
    private var lastTime = -1f

    /**
     * 26.11.29：专辑封面完整置入纹理，原始宽高比不变 —— 不拉伸、不裁切。
     * 四周用同一封面等比放大后的模糊版本补齐（与录制帧的封面底一致），
     * 片元着色器采样 dispImage 得到的仍是「以专辑封面为背景」的雨景。
     * 垂直翻转：WebGL 纹理 t=0 在左下、Android Bitmap 第 0 行在顶部，
     * glReadPixels 后再翻回来即可与 demo 的取样方向一致。
     * 注意：VERTEX_SHADER / FRAGMENT_SHADER 逐字未改，只调整 CPU 侧纹理准备。
     */
    private fun coverTexture(cover: Bitmap, w: Int, h: Int): Bitmap {
        val ratio = w.toFloat() / h.coerceAtLeast(1).toFloat()
        val maxSide = 1024
        val tw: Int
        val th: Int
        if (ratio >= 1f) {
            tw = maxSide
            th = (maxSide / ratio).toInt().coerceAtLeast(1)
        } else {
            th = maxSide
            tw = (maxSide * ratio).toInt().coerceAtLeast(1)
        }
        val cw = cover.width.toFloat()
        val ch = cover.height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val flat = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val fc = Canvas(flat)
        // 垫底：等比放大铺满，再缩小放大做柔和模糊（模糊的是延伸背景，不是封面主体）
        val sf = kotlin.math.max(tw / cw, th / ch)
        val fw = cw * sf
        val fh = ch * sf
        fc.drawBitmap(cover, null, RectF((tw - fw) / 2f, (th - fh) / 2f, (tw + fw) / 2f, (th + fh) / 2f), p)
        val bw = (tw / 10).coerceAtLeast(2)
        val bh = (th / 10).coerceAtLeast(2)
        val small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(flat, null, RectF(0f, 0f, bw.toFloat(), bh.toFloat()), p)
        fc.drawBitmap(small, null, RectF(0f, 0f, tw.toFloat(), th.toFloat()), p)
        small.recycle()
        // 主体：contain，完整等比、不裁切
        val s = kotlin.math.min(tw / cw, th / ch)
        val dw = cw * s
        val dh = ch * s
        fc.drawBitmap(cover, null, RectF((tw - dw) / 2f, (th - dh) / 2f, (tw + dw) / 2f, (th + dh) / 2f), p)
        val flip = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val flipC = Canvas(flip)
        flipC.scale(1f, -1f, tw / 2f, th / 2f)
        flipC.drawBitmap(flat, 0f, 0f, null)
        flat.recycle()
        return flip
    }

    /** 每帧调用一次；uTime 在内部每渲染一帧 +1，与 demo 的 time.value++ 完全一致。 */
    @Synchronized
    fun render(cover: Bitmap?, w: Int, h: Int): Bitmap? {
        if (cover == null || cover.isRecycled || w <= 0 || h <= 0) return null
        if (!ensure(w, h)) return null
        val d = display ?: return null
        val s = surface ?: return null
        val c = context ?: return null
        if (!EGL14.eglMakeCurrent(d, s, s, c)) return null

        val key = "${System.identityHashCode(cover)}:$w:$h"
        if (key != coverKey) {
            // 封面完整置入纹理（不拉伸、不裁切），GLSL 与 demo 一字不改。
            val texBmp = coverTexture(cover, w, h)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, texBmp, 0)
            texBmp.recycle()
            coverKey = key
        }

        GLES20.glViewport(0, 0, w, h)
        // 26.11.30：pbuffer 不会自动清屏，后台进程里残留帧会被 glReadPixels
        // 读回成纯色/旧帧；先清屏，绘制完成后再同步读回。GLSL 一字未改。
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val aPos = GLES20.glGetAttribLocation(program, "aVertexPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)

        fun mat4(name: String) {
            val l = GLES20.glGetUniformLocation(program, name)
            if (l >= 0) GLES20.glUniformMatrix4fv(l, 1, false, IDENTITY, 0)
        }
        mat4("uMVMatrix")
        mat4("uPMatrix")
        mat4("dispImageMatrix")

        val lt = GLES20.glGetUniformLocation(program, "uTime")
        if (lt >= 0) {
            frame += 1f // demo: plane.onRender(() => plane.uniforms.time.value++)
            GLES20.glUniform1f(lt, frame)
        }
        val lr = GLES20.glGetUniformLocation(program, "uReso")
        if (lr >= 0) GLES20.glUniform2f(lr, w.toFloat(), h.toFloat())
        val lm = GLES20.glGetUniformLocation(program, "uMouse")
        if (lm >= 0) GLES20.glUniform2f(lm, 0f, 0f)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val ld = GLES20.glGetUniformLocation(program, "dispImage")
        if (ld >= 0) GLES20.glUniform1i(ld, 0)
        // demo 声明了 blurImage 但片元着色器未采样；仍按 1 号单元绑定，不改 shader。
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val lb = GLES20.glGetUniformLocation(program, "blurImage")
        if (lb >= 0) GLES20.glUniform1i(lb, 1)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glFinish()
        lastTime = frame

        val size = w * h * 4
        var bb = bytesBuf
        if (bb == null || bb.capacity() < size) {
            bb = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            bytesBuf = bb
        }
        bb.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)

        var px = pxBuf
        if (px == null || px.size < w * h) {
            px = IntArray(w * h)
            pxBuf = px
        }
        for (y in 0 until h) {
            var i = (h - 1 - y) * w * 4
            val o = y * w
            for (x in 0 until w) {
                val r = bb.get(i).toInt() and 0xFF
                val g = bb.get(i + 1).toInt() and 0xFF
                val b = bb.get(i + 2).toInt() and 0xFF
                val a = bb.get(i + 3).toInt() and 0xFF
                px[o + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i += 4
            }
        }
        var out = outBmp
        if (out == null || out.isRecycled || out.width != w || out.height != h) {
            if (out != null && !out.isRecycled) out.recycle()
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBmp = out
        }
        out!!.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun ensure(w: Int, h: Int): Boolean {
        if (display != null && surfW == w && surfH == h && program != 0) return true
        release()
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(d, ver, 0, ver, 1)) return false
        val cfgAttrs = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(d, cfgAttrs, 0, cfgs, 0, 1, num, 0) || num[0] == 0) {
            EGL14.eglTerminate(d)
            return false
        }
        val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(d, cfgs[0], pbAttrs, 0)
        val cAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val c = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, cAttrs, 0)
        if (s == EGL14.EGL_NO_SURFACE || c == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(d)
            return false
        }
        if (!EGL14.eglMakeCurrent(d, s, s, c)) {
            EGL14.eglDestroySurface(d, s)
            EGL14.eglDestroyContext(d, c)
            EGL14.eglTerminate(d)
            return false
        }
        display = d
        context = c
        surface = s
        surfW = w
        surfH = h
        return build()
    }

    private fun loadShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val st = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }

    private fun build(): Boolean {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vs == 0 || fs == 0) return false
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val link = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (link[0] == 0) {
            GLES20.glDeleteProgram(p)
            return false
        }
        program = p

        // demo: widthSegments = 40, heightSegments = 40 => 40*40*6 = 9600 顶点索引
        val seg = 40
        val n = (seg + 1) * (seg + 1)
        val verts = FloatArray(n * 5)
        var k = 0
        for (y in 0..seg) {
            for (x in 0..seg) {
                verts[k++] = x.toFloat() / seg * 2f - 1f
                verts[k++] = y.toFloat() / seg * 2f - 1f
                verts[k++] = 0f
                verts[k++] = x.toFloat() / seg
                verts[k++] = y.toFloat() / seg
            }
        }
        val idx = ShortArray(seg * seg * 6)
        var q = 0
        for (y in 0 until seg) {
            for (x in 0 until seg) {
                val a = (y * (seg + 1) + x).toShort()
                val b = (a + 1).toShort()
                val cc = ((y + 1) * (seg + 1) + x).toShort()
                val dd = (cc + 1).toShort()
                idx[q++] = a; idx[q++] = b; idx[q++] = cc
                idx[q++] = b; idx[q++] = dd; idx[q++] = cc
            }
        }
        idxCount = idx.size
        val bufs = IntArray(2)
        GLES20.glGenBuffers(2, bufs, 0)
        vbo = bufs[0]
        ibo = bufs[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, FloatBuffer.wrap(verts), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ShortBuffer.wrap(idx), GLES20.GL_STATIC_DRAW)

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        coverId = 0
        coverKey = ""
        return true
    }

    private fun release() {
        val d = display
        if (d != null) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (vbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
            if (ibo != 0) GLES20.glDeleteBuffers(1, intArrayOf(ibo), 0)
            if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            surface?.let { EGL14.eglDestroySurface(d, it) }
            context?.let { EGL14.eglDestroyContext(d, it) }
            EGL14.eglTerminate(d)
        }
        display = null
        context = null
        surface = null
        program = 0
        vbo = 0
        ibo = 0
        tex = 0
        coverId = 0
        coverKey = ""
        lastTime = -1f
    }
}
