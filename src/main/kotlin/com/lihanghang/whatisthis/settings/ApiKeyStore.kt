package com.lihanghang.whatisthis.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * One API key per provider, stored encrypted in the IDE PasswordSafe.
 */
object ApiKeyStore {
    private const val SERVICE_NAME = "WhatIsThis API Key"

    private fun attributes(providerId: String) =
        CredentialAttributes(serviceName = SERVICE_NAME, userName = providerId)

    fun get(providerId: String): String? =
        PasswordSafe.instance.get(attributes(providerId))
            ?.getPasswordAsString()
            ?.takeIf { it.isNotBlank() }

    fun set(providerId: String, value: String?) {
        val credentials = if (value.isNullOrBlank()) null else Credentials(providerId, value)
        PasswordSafe.instance.set(attributes(providerId), credentials)
    }
}
