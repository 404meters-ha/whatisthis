package com.lihanghang.whatisthis.settings

import com.lihanghang.whatisthis.llm.Providers

/**
 * Everything a request needs, resolved from settings + PasswordSafe.
 */
data class EffectiveConfig(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val language: String,
    /** False = model is not on the known-vision list -> warn before sending screenshots. */
    val knownVision: Boolean,
)

object ConfigResolver {

    /** Null when the user has not finished setup (no key / blank base URL or model). */
    fun resolve(): EffectiveConfig? {
        val state = WhatIsThisSettings.instance.current
        val preset = Providers.byId(state.providerId)
        val baseUrl = if (state.providerId == Providers.CUSTOM) {
            state.customBaseUrl.trim()
        } else {
            preset.baseUrl
        }
        val model = state.model.trim().ifBlank { preset.defaultModel }
        val apiKey = ApiKeyStore.get(state.providerId) ?: return null
        if (baseUrl.isBlank() || model.isBlank()) return null
        return EffectiveConfig(
            providerId = state.providerId,
            providerName = preset.displayName,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            language = state.language,
            knownVision = Providers.isKnownVision(state.providerId, model),
        )
    }
}
