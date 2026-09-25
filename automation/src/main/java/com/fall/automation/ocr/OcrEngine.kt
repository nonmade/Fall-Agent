package com.fall.automation.ocr

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧 OCR 引擎：PP-OCRv4 mobile(det + rec) 跑在 ONNX Runtime。
 *
 * - 进程内懒加载单例；assets("ocr/") 首次拷贝到 filesDir 后建 session。
 * - det：整图缩放最长边 960 → DB 阈值二值 → 4 邻域连通域 → 轴对齐 bbox（UI 文本多为水平，省略旋转框/minAreaRect）。
 * - rec：每行裁剪 resize 高 32 宽固定 320 → CTC 贪心解码 + ppocr_keys_v1 词典。
 * - 坐标输出与原图一致（display-local），det 内部缩放通过 scale 换算回原图。
 *
 * 线程：recognize 切 [Dispatchers.Default]，绝不阻塞 AgentLoop 协程。
 */
object OcrEngine {

    private const val DET_FILE = "PP-OCRv4_det.onnx"
    private const val REC_FILE = "PP-OCRv4_rec.onnx"
    private const val DICT_FILE = "ppocr_keys_v1.txt"

    /** 模型 I/O 名与尺寸约定：
     * - det（PaddleX 动态导出）：输入 [1,3,H,W]，输出与输入**同分辨率** [1,1,H,W]。
     *   动态导出的 Add 广播对非 16 倍数宽高会崩（实测 540/720 失败，480/512/544 成功），
     *   故输入尺寸必须按 16 对齐。
     * - rec：固定输入高 48 / 宽 320（PP-OCRv4 mobile rec 规格 3×48×320）。 */
    private const val DET_MAX_SIDE = 960
    private const val REC_HEIGHT = 48
    private const val REC_WIDTH = 320
    private const val DB_THRESH = 0.22f
    /** 最小文本域（原图像素）：UI 小字（B 站卡片标题等）常只有几百像素，阈值调低提召回。 */
    private const val MIN_BOX_AREA_RATIO = 0.00035f
    private const val MIN_BOX_W = 12
    private const val MIN_BOX_H = 8

    private val initialized = AtomicBoolean(false)
    private lateinit var env: OrtEnvironment
    private lateinit var detSession: OrtSession
    private lateinit var recSession: OrtSession
    private var dict: List<String> = emptyList()

    // 模型 I/O 名由运行时探测（转换产物命名可能不同，不写死）
    private lateinit var detInput: String
    private lateinit var detOutput: String
    private lateinit var recInput: String
    private lateinit var recOutput: String

    /** 是否已可识别（模型未就绪时返回 false，调用方降级为纯坐标提示）。 */
    fun isReady(): Boolean = initialized.get()

    /** 首次调用建立 session（拷贝模型 + 建图，约 0.5~1s）。 */
    fun ensureInitialized(context: Context) {
        if (initialized.get()) return
            synchronized(this) {
                if (initialized.get()) return
                val app = context.applicationContext
                val dir = File(app.filesDir, "ocr").apply { mkdirs() }
                val detPath = copyAsset(app, "ocr/$DET_FILE", dir, DET_FILE)
                val recPath = copyAsset(app, "ocr/$REC_FILE", dir, REC_FILE)
            val dictPath = copyAsset(app, "ocr/$DICT_FILE", dir, DICT_FILE)

            env = OrtEnvironment.getEnvironment()
            val so = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            detSession = env.createSession(detPath, so)
            recSession = env.createSession(recPath, OrtSession.SessionOptions())
            dict = File(dictPath).readLines()

            detInput = detSession.inputNames.first()
            detOutput = detSession.outputNames.first()
            recInput = recSession.inputNames.first()
            recOutput = recSession.outputNames.first()
            initialized.set(true)
        }
    }

    /**
     * 识别整图。@param bmp 截帧（display-local）。失败/未初始化返回空列表。
     */
    suspend fun recognize(bmp: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        if (!initialized.get()) return@withContext emptyList()
        runCatching { recognizeInternal(bmp) }.getOrDefault(emptyList())
    }

    // ---------- 内部实现 ----------

    private fun recognizeInternal(bmp: Bitmap): List<OcrLine> {
        val w = bmp.width
        val h = bmp.height
        if (w == 0 || h == 0) return emptyList()

        // ---- det：缩放（最长边 960，宽高 16 对齐）+ 归一化输入 ----
        val scale = minOf(1f, DET_MAX_SIDE.toFloat() / maxOf(w, h))
        val rw = align16((w * scale).toInt().coerceAtLeast(16))
        val rh = align16((h * scale).toInt().coerceAtLeast(16))
        val scaled = if (rw == w && rh == h) bmp else Bitmap.createScaledBitmap(bmp, rw, rh, true)
        val chw = bitmapToChwFloat(scaled, rw, rh)
        if (scaled !== bmp) scaled.recycle()

        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { input ->
            detSession.run(mapOf(detInput to input)).use { res ->
                val value = runCatching { res.get(detOutput).orElse(null)?.value }.getOrNull() ?: return emptyList()
                val prob = detProb(value, rh, rw) ?: return emptyList()
                return maskToBoxes(prob, rh, rw, rw, rh, w, h)
                    .mapNotNull { box -> recognizeLine(bmp, box) }
            }
        }
    }

    private fun align16(v: Int): Int = (v + 15) / 16 * 16

    /** det 输出期望 [1,1,oh,ow]（Java 表示 float[1][1][oh][ow]）；展平成 FloatArray。 */
    private fun detProb(value: Any?, oh: Int, ow: Int): FloatArray? {
        val batch = value as? Array<*> ?: return null
        val planes = batch.getOrNull(0) as? Array<*> ?: return null      // C 层
        val rows = planes.getOrNull(0) as? Array<*> ?: return null        // H 层（float[oh][ow]）
        val flat = FloatArray(oh * ow)
        var idx = 0
        for (row in rows) {
            val r = row as? FloatArray ?: return null
            for (v in r) if (idx < flat.size) flat[idx++] = v
        }
        return flat
    }

    private data class RawBox(val x: Int, val y: Int, val w: Int, val h: Int)

    /** DB 阈值 → mask → 4 邻域连通域 → 轴对齐 bbox（映射回原图坐标）。 */
    private fun maskToBoxes(
        prob: FloatArray,
        oh: Int,
        ow: Int,
        rw: Int,
        rh: Int,
        origW: Int,
        origH: Int,
    ): List<RawBox> {
        val sx = origW.toFloat() / ow
        val sy = origH.toFloat() / rh
        val visit = BooleanArray(ow * oh)
        val boxes = ArrayList<RawBox>()
        val stack = ArrayDeque<Int>()
        val minArea = (origW * origH) * MIN_BOX_AREA_RATIO

        for (i in prob.indices) {
            if (prob[i] < DB_THRESH || visit[i]) continue
            visit[i] = true
            stack.addLast(i)
            var minX = ow; var minY = oh; var maxX = 0; var maxY = 0
            var area = 0
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val x = idx % ow; val y = idx / ow
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                area++
                if (x > 0 && !visit[idx - 1] && prob[idx - 1] >= DB_THRESH) { visit[idx - 1] = true; stack.addLast(idx - 1) }
                if (x < ow - 1 && !visit[idx + 1] && prob[idx + 1] >= DB_THRESH) { visit[idx + 1] = true; stack.addLast(idx + 1) }
                if (y > 0 && !visit[idx - ow] && prob[idx - ow] >= DB_THRESH) { visit[idx - ow] = true; stack.addLast(idx - ow) }
                if (y < oh - 1 && !visit[idx + ow] && prob[idx + ow] >= DB_THRESH) { visit[idx + ow] = true; stack.addLast(idx + ow) }
            }
            val bw = (maxX - minX + 1) * sx
            val bh = (maxY - minY + 1) * sy
            if (bw < MIN_BOX_W || bh < MIN_BOX_H || bw * bh < minArea) continue
            boxes.add(RawBox((minX * sx).toInt(), (minY * sy).toInt(), bw.toInt(), bh.toInt()))
        }
        // 同行合并：det 常把一行文字切成若干相邻块，纵向重叠 + 横向间隔小的合并成完整行，提升 rec 质量
        val merged = mergeLines(boxes)
        // 行高校正：det 常只切出文字笔画窄条，拉伸到 rec 48 会严重变形，扩大到不小于原图 5% 行高
        val minH = (origH * 0.05f).toInt().coerceAtLeast(40)
        return merged.map { b ->
            if (b.h >= minH) b else {
                val grow = (minH - b.h) / 2
                val ny = (b.y - grow).coerceAtLeast(0)
                val nh = (b.y + b.h + grow).coerceAtMost(origH) - ny
                RawBox(b.x, ny, b.w, nh.coerceAtLeast(1))
            }
        }
    }

    /** 同一行的相邻文本块合并（纵向带重叠 + 横向间隔小于行高的 1.5 倍）。 */
    private fun mergeLines(boxes: List<RawBox>): List<RawBox> {
        val sorted = boxes.sortedBy { it.y }
        val out = ArrayList<RawBox>(boxes.size)
        for (b in sorted) {
            val line = out.lastOrNull()
            val gap = if (line != null) b.x - (line.x + line.w) else Int.MAX_VALUE
            if (line != null &&
                b.y < line.y + line.h && line.y < b.y + b.h &&   // 纵向重叠（同一行带）
                gap < maxOf(line.h, 40).let { (it * 1.5f).toInt() } // 横向间隔可容忍
            ) {
                val nx = minOf(line.x, b.x)
                val ny = minOf(line.y, b.y)
                val nw = maxOf(line.x + line.w, b.x + b.w) - nx
                val nh = maxOf(line.y + line.h, b.y + b.h) - ny
                out[out.size - 1] = RawBox(nx, ny, nw, nh)
            } else {
                out.add(b)
            }
        }
        return out
    }

    /** 对 bbox 区域裁剪 → rec 识别 → OcrLine（坐标回原图）。 */
    private fun recognizeLine(bmp: Bitmap, box: RawBox): OcrLine? {
        val crop = runCatching {
            Bitmap.createBitmap(bmp, box.x, box.y, box.w, box.h)
        }.getOrNull() ?: return null
        val rw = REC_WIDTH
        val rh = REC_HEIGHT
        val resized = Bitmap.createScaledBitmap(crop, rw, rh, true)
        crop.recycle()
        val chw = bitmapToChwFloat(resized, rw, rh)
        resized.recycle()

        val text = try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { input ->
                recSession.run(mapOf(recInput to input)).use { res ->
                    val value = res.get(recOutput).orElse(null)?.value
                    decodeCtc(value)
                }
            }
        } catch (e: Throwable) {
            null
        }
        if (text.isNullOrBlank()) return null
        return OcrLine(text, box.x, box.y, box.w, box.h, 1f)
    }

    /** CTC 贪心解码：取每步 argmax → 合并重复 → 去空白。
     * 模型输出类别 = 词典索引 + 1（类别 0 与类别 1 均为 blank，dict[0] 为 ''）。 */
    private fun decodeCtc(value: Any?): String? {
        val seq = when (value) {
            is Array<*> -> value.getOrNull(0) as? Array<*> // [T, 1, C] → 第0 batch
            is FloatArray -> return ctcFromSingle(value)
            else -> return null
        } ?: return null
        val logits = seq.mapNotNull { it as? FloatArray } // [T, C]
        if (logits.isEmpty()) return null
        val sb = StringBuilder()
        var prev = -1
        for (row in logits) {
            var best = 0; var bestV = row.getOrElse(0) { Float.NEGATIVE_INFINITY }
            for (i in 1 until row.size) if (row[i] > bestV) { bestV = row[i]; best = i }
            if (best == prev) continue
            prev = best
            val j = best - 1 // 类别 → 词典索引偏移
            if (j <= 0) continue // blank
            val ch = dict.getOrNull(j) ?: continue
            if (ch.isNotBlank()) sb.append(ch)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** 输出已是平坦 [T*C] 的兜底（基本不会走到，防御）。 */
    private fun ctcFromSingle(value: FloatArray): String? {
        if (dict.isEmpty()) return null
        val c = value.size / (dict.size + 1) // 近似列数
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until c) {
            val base = t * (dict.size + 1)
            if (base + dict.size + 1 > value.size) break
            var best = 0; var bestV = Float.NEGATIVE_INFINITY
            for (i in 0..dict.size) {
                val v = value[base + i]
                if (v > bestV) { bestV = v; best = i }
            }
            if (best == prev) continue
            prev = best
            val j = best - 1
            if (j <= 0) continue
            val ch = dict.getOrNull(j) ?: continue
            if (ch.isNotBlank()) sb.append(ch)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** Bitmap → CHW float（mean/std 归一化，PP-OCR 约定）。 */
    private fun bitmapToChwFloat(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val meanR = 0.485f; val meanG = 0.456f; val meanB = 0.406f
        val stdR = 0.229f; val stdG = 0.224f; val stdB = 0.225f
        for (i in 0 until w * h) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            out[i] = (r - meanR) / stdR
            out[w * h + i] = (g - meanG) / stdG
            out[2 * w * h + i] = (b - meanB) / stdB
        }
        return out
    }

    private fun copyAsset(context: Context, asset: String, dir: File, name: String): String {
        val target = File(dir, name)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(asset).use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return target.absolutePath
    }

    /** 释放会话（进程退出/设置切换时可选调用）。 */
    fun close() {
        synchronized(this) {
            runCatching { detSession.close() }
            runCatching { recSession.close() }
            initialized.set(false)
        }
    }
}