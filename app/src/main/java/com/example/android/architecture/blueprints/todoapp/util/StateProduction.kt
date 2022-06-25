package com.example.android.architecture.blueprints.todoapp.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Data class holding a change transform for a some state [T].
 */
data class StateChange<T : Any>(
    val mutate: T.() -> T
) {
    companion object {
        /**
         * Identity state change function; semantically a no op [StateChange]
         */
        fun <T : Any> identity(): StateChange<T> = StateChange { this }
    }
}

/**
 * Combines two state changes into a single state change
 */
operator fun <T : Any> StateChange<T>.plus(other: StateChange<T>) = StateChange<T> inner@{
    val result = this@plus.mutate(this@inner)
    other.mutate.invoke(result)
}

/**
 * Produces a [StateFlow] by merging [stateChangeFlows] and reducing them into an
 * [initial] state
 */
fun <T : Any> CoroutineScope.produceState(
    initial: T,
    started: SharingStarted = WhileUiSubscribed,
    stateChangeFlows: List<Flow<StateChange<T>>>
): StateFlow<T> {
    // Set the seed for the state
    var seed = initial

    // Use the flow factory function to capture the seed variable
    return flow {
        emitAll(
            merge(*stateChangeFlows.toTypedArray())
                // Reduce into the seed so if resubscribed, the last value of state is persisted
                // when the flow pipeline is started again
                .scan(seed) { state, stateChange -> stateChange.mutate(state) }
                // Set seed after each emission
                .onEach { seed = it }
        )
    }
        .stateIn(
            scope = this,
            started = started,
            initialValue = seed
        )
}

/**
 * Alias to push the [StateChange] defined by [stateChange] into [this]
 */
suspend inline fun <T : Any> MutableSharedFlow<StateChange<T>>.pushStateChange(
    noinline stateChange: T.() -> T
) = emit(StateChange(stateChange))

/**
 * Alias to push the [stateChange] into [this]
 */
suspend inline fun <T : Any> MutableSharedFlow<StateChange<T>>.pushStateChange(
    stateChange: StateChange<T>
) = emit(stateChange)

/**
 * Helper function to run the provided [block] in the [scope]
 */
fun <T : Any> MutableSharedFlow<StateChange<T>>.withScope(
    scope: CoroutineScope,
    block: suspend MutableSharedFlow<StateChange<T>>.() -> Unit
) {
    scope.launch {
        block()
    }
}

/**
 * Helper function to run the provided [block] in the [ViewModel.viewModelScope]
 */
context(ViewModel)
fun <T : Any> MutableSharedFlow<StateChange<T>>.withViewModelScope(
    block: suspend MutableSharedFlow<StateChange<T>>.() -> Unit
) = withScope(
    scope = viewModelScope,
    block = block
)
