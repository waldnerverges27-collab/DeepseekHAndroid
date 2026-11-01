package io.deepseek.harness.android.engine

import io.deepseek.harness.android.container.ExtractionProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Máquina de estados global reactiva del ciclo de vida del contenedor y del motor `dsh`.
 */
sealed interface EngineLifecycleState {
    data object Idle : EngineLifecycleState
    data object CheckingEnvironment : EngineLifecycleState
    data class ExtractingRootfs(val progress: ExtractionProgress) : EngineLifecycleState
    data object VerifyingContainer : EngineLifecycleState
    data class StartingEngine(val port: Int, val attempt: Int = 1) : EngineLifecycleState
    data class Running(
        val port: Int,
        val webUrl: String,
        val latencyMs: Long,
        val restartCount: Int,
    ) : EngineLifecycleState
    data object Stopped : EngineLifecycleState
    data class Error(
        val title: String,
        val detail: String,
        val recoverable: Boolean = true,
    ) : EngineLifecycleState
}

object EngineStateStore {
    private val _state = MutableStateFlow<EngineLifecycleState>(EngineLifecycleState.Idle)
    val state: StateFlow<EngineLifecycleState> = _state.asStateFlow()

    @Volatile
    var latestTokenAuthUrl: String? = null

    fun update(newState: EngineLifecycleState) {
        _state.value = newState
    }
}
