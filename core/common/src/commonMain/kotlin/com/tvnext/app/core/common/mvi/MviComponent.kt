package com.tvnext.app.core.common.mvi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class MviComponent<S : MviState, I : MviIntent, E : MviEffect>(
    initialState: S,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<E>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<E> = _effects.asSharedFlow()

    protected abstract fun reduce(state: S, intent: I): S

    fun dispatch(intent: I) {
        _state.update { reduce(it, intent) }
        handle(intent)
    }

    protected open fun handle(intent: I) = Unit

    protected fun setState(reducer: (S) -> S) {
        _state.update(reducer)
    }

    protected fun emitEffect(effect: E) {
        _effects.tryEmit(effect)
    }

    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block)
}
