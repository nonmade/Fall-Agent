package com.fall.core.agent

/**
 * 静默链（VirtualDisplay）的 UI 感知方式，用户可在设置页切换：
 * - [MULTIMODAL]：截帧 → 图片直发模型（多模态视觉直读）。感知质量对齐模型视觉能力，
 *   无需端侧 OCR 且无几何启发式误判；依赖模型支持图片输入（deepseek-flash / 视觉 VL 模型）。
 * - [OCR]：端侧 PP-OCRv4 文字识别（离线可用），输出"文本 + 坐标 + 索引"，模型按 index/坐标操作。
 */
enum class PerceptionMode { MULTIMODAL, OCR }