package com.fall.automation.ocr

/**
 * 一行 OCR 识别结果（display-local 坐标，与原截图分辨率一致）。
 */
data class OcrLine(
    val text: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val score: Float,
)