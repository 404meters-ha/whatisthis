package com.lihanghang.whatisthis.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Non-secret settings. The API key lives in [ApiKeyStore] (PasswordSafe).
 */
@State(name = "WhatIsThisSettings", storages = [Storage("whatisthis.xml")])
class WhatIsThisSettings : PersistentStateComponent<WhatIsThisSettings.State> {

    class State {
        var providerId: String = "deepseek"
        /** Blank = use the provider preset's default model. */
        var model: String = ""
        /** Only used when providerId == "custom". */
        var customBaseUrl: String = ""
        /** "zh" or "en". */
        var language: String = "zh"
    }

    private val state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    val current: State get() = state

    companion object {
        val instance: WhatIsThisSettings
            get() = ApplicationManager.getApplication().getService(WhatIsThisSettings::class.java)
    }
}
