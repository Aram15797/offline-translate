package com.offline.translator.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offline.translator.data.cloud.CloudGateway
import com.offline.translator.domain.translation.CloudConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CloudMediaViewModel(context: Context, configuration: () -> CloudConfiguration, gateway: CloudGateway) : ViewModel() {
    private val mutableText = MutableStateFlow<String?>(null)
    val pendingText = mutableText.asStateFlow()
    val controller = CloudMediaController(context.applicationContext, viewModelScope, configuration, gateway) { mutableText.value = it }

    fun cancel() { controller.cancel(); mutableText.value = null }

    fun consume(text: String) { if (mutableText.value == text) mutableText.value = null }

    override fun onCleared() { controller.cancel() }
}
