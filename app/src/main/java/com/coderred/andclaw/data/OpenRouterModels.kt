package com.coderred.andclaw.data

import org.json.JSONObject

data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Int,
    val maxOutputTokens: Int = 4096,
    val isFree: Boolean,
    val pricing: String, // e.g. "Free" or "$0.50/M"
    val supportsReasoning: Boolean = false,
    val supportsImages: Boolean = false,
)

/**
 * OpenRouter API 응답을 파싱하여 tool 지원 모델 목록을 반환한다.
 * 무료 모델이 먼저, 각각 컨텍스트 길이 내림차순으로 정렬된다.
 */
fun parseOpenRouterModels(jsonBody: String): List<OpenRouterModel> {
    val result = mutableListOf<OpenRouterModel>()
    val json = JSONObject(jsonBody)
    val data = json.getJSONArray("data")

    for (i in 0 until data.length()) {
        val model = data.getJSONObject(i)
        val id = model.optString("id", "")

        // supported_parameters에서 tools 지원 여부 확인
        val supportedParams = model.optJSONArray("supported_parameters")
        var supportsTools = false
        if (supportedParams != null) {
            for (j in 0 until supportedParams.length()) {
                if (supportedParams.getString(j) == "tools") {
                    supportsTools = true
                    break
                }
            }
        }

        if (!supportsTools) continue

        // supported_parameters에서 reasoning 지원 여부 확인
        var supportsReasoning = false
        if (supportedParams != null) {
            for (j in 0 until supportedParams.length()) {
                if (supportedParams.getString(j) == "reasoning") {
                    supportsReasoning = true
                    break
                }
            }
        }

        // 이미지 입력 지원 확인
        val modality = model.optString("modality", "")
        val supportsImages = modality.contains("image")

        val name = model.optString("name", id)
        val contextLength = model.optInt("context_length", 0)
        val maxOutputTokens = model.optInt("top_provider_max_completion_tokens",
            model.optInt("max_completion_tokens", 4096)
        )

        // 가격 파싱
        val pricingObj = model.optJSONObject("pricing")
        val promptStr = pricingObj?.optString("prompt", "") ?: ""
        val completionStr = pricingObj?.optString("completion", "") ?: ""
        val promptPrice = promptStr.toDoubleOrNull() ?: -1.0
        val completionPrice = completionStr.toDoubleOrNull() ?: -1.0

        // 무료 판별: :free 접미사 또는 prompt+completion 둘 다 정확히 0
        val isFree = id.endsWith(":free") || (promptPrice == 0.0 && completionPrice == 0.0)

        val pricing = if (isFree) {
            "Free"
        } else {
            val perMillion = promptPrice * 1_000_000
            when {
                perMillion < 0.01 -> "$0/M"
                perMillion < 1.0 -> "$${String.format("%.2f", perMillion)}/M"
                else -> "$${String.format("%.1f", perMillion)}/M"
            }
        }

        result.add(
            OpenRouterModel(
                id = id,
                name = name,
                contextLength = contextLength,
                maxOutputTokens = maxOutputTokens,
                isFree = isFree,
                pricing = pricing,
                supportsReasoning = supportsReasoning,
                supportsImages = supportsImages,
            )
        )
    }

    // 무료 먼저, 각각 컨텍스트 길이 내림차순
    val freeModels = result.filter { it.isFree }.sortedByDescending { it.contextLength }
    val paidModels = result.filter { !it.isFree }.sortedByDescending { it.contextLength }
    return freeModels + paidModels
}
