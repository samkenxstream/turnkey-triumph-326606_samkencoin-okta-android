/*
 * Copyright 2022-Present Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.okta.totp.otp_display

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okta.totp.coroutine.IoDispatcher
import com.okta.totp.otp_repository.OtpUriSharedPreferences
import com.okta.totp.parsing.OtpUriParser
import com.okta.totp.parsing.OtpUriParsingResults
import com.okta.totp.password_generator.PasswordGenerator
import com.okta.totp.password_generator.PasswordGeneratorFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class OtpDisplayViewModel @Inject constructor(
    private val otpUriSharedPreferences: OtpUriSharedPreferences,
    private val otpUriParser: OtpUriParser,
    private val passwordGeneratorFactory: PasswordGeneratorFactory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private var limitUpdates = false
    private var maxUpdates = 0
    private val events = MutableSharedFlow<OtpDisplayViewModelEvents>()

    private val otpEntryFlow = events.scan(getInitialOtpCodes()) { otpEntryList, event ->
        when (event) {
            is OtpDisplayViewModelEvents.UpdateOtpEntries -> {
                otpEntryList.map { otpEntry ->
                    val currentOtpCode = otpEntry.passwordGenerator.generate()
                    otpEntry.copy(otpCode = currentOtpCode)
                }
            }
            is OtpDisplayViewModelEvents.DeleteOtpViewmodelData -> {
                otpUriSharedPreferences.removeOtpUriString(event.otpViewmodelData.otpUriString)
                otpEntryList - event.otpViewmodelData
            }
        }
    }
        .flowOn(ioDispatcher)

    val otpScreenUiStateFlow = otpEntryFlow.map { otpEntryList ->
        otpEntryList.map { otpEntry ->
            OtpEntry(
                otpCode = otpEntry.otpCode,
                account = otpEntry.account,
                issuer = otpEntry.issuer,
                onDeleteOtpEntry = {
                    viewModelScope.launch(ioDispatcher) {
                        events.emit(OtpDisplayViewModelEvents.DeleteOtpViewmodelData(otpEntry))
                    }
                }
            )
        }
    }
        .flowOn(ioDispatcher)

    init {
        viewModelScope.launch(ioDispatcher) {
            var updates = 0
            while (limitUpdates && updates++ < maxUpdates) {
                delay(OTP_REFRESH_TIME_SECS.seconds)
                events.emit(OtpDisplayViewModelEvents.UpdateOtpEntries)
            }
        }
    }

    private fun getInitialOtpCodes(): List<OtpViewmodelData> {
        return otpUriSharedPreferences.getOtpUriStrings()
            .map { otpUriString ->
                val otpParams =
                    otpUriParser.parseOtpUriString(otpUriString) as OtpUriParsingResults.OtpData
                val passwordGenerator = passwordGeneratorFactory.getPasswordGenerator(otpParams)
                val currentOtpCode = passwordGenerator.generate()
                OtpViewmodelData(
                    otpCode = currentOtpCode,
                    account = otpParams.name,
                    issuer = otpParams.issuer,
                    passwordGenerator = passwordGenerator,
                    otpUriString = otpUriString,
                )
            }
    }

    @VisibleForTesting
    fun setMaxUpdateCycles(maxUpdates: Int) {
        limitUpdates = true
        this.maxUpdates = maxUpdates
    }

    companion object {
        @VisibleForTesting
        const val OTP_REFRESH_TIME_SECS = 5L
    }
}

private sealed interface OtpDisplayViewModelEvents {
    object UpdateOtpEntries : OtpDisplayViewModelEvents
    data class DeleteOtpViewmodelData(
        val otpViewmodelData: OtpViewmodelData
    ) : OtpDisplayViewModelEvents
}

private data class OtpViewmodelData(
    val otpCode: String,
    val account: String,
    val issuer: String?,
    val passwordGenerator: PasswordGenerator,
    val otpUriString: String,
)
