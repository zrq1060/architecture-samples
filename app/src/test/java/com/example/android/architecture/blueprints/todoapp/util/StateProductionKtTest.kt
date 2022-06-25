package com.example.android.architecture.blueprints.todoapp.util

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private data class State(
    val value: Int = 0
)

class StateProductionKtTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var eventStateChanges: MutableSharedFlow<StateChange<State>>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        scope = TestScope(testDispatcher)
        eventStateChanges = MutableSharedFlow()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun test_simple_state_production() = runTest {
        val state = scope.produceState(
            initial = State(),
            stateChangeFlows = listOf(
                eventStateChanges
            )
        )

        state.test {
            assertEquals(State(0), awaitItem())
            eventStateChanges.pushStateChange { copy(value = value + 1) }
            assertEquals(State(1), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_state_production_persists_after_unsubscribing() = runTest {
        val state = scope.produceState(
            initial = State(),
            started = SharingStarted.WhileSubscribed(),
            stateChangeFlows = listOf(
                eventStateChanges
            )
        )

        // Subscribe the first time
        state.test {
            assertEquals(State(0), awaitItem())
            eventStateChanges.pushStateChange { copy(value = value + 1) }
            assertEquals(State(1), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        // Subscribe again. The state flow value should not be reset by the pipeline restarting
        state.test {
            assertEquals(State(1), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_state_production_with_merged_flows() = runTest {
        val state = scope.produceState(
            initial = State(),
            started = SharingStarted.WhileSubscribed(),
            stateChangeFlows = listOf(
                eventStateChanges,
                flow {
                    delay(1000)
                    emit(StateChange { copy(value = 3) })

                    delay(1000)
                    emit(StateChange { copy(value = 7) })
                }
            )
        )

        state.test {
            assertEquals(State(0), awaitItem())

            advanceTimeBy(1200)
            assertEquals(State(3), awaitItem())

            advanceTimeBy(1200)
            assertEquals(State(7), awaitItem())

            eventStateChanges.pushStateChange { copy(value = 0) }
            assertEquals(State(0), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun test_state_change_addition() {
        val additionStateChange = StateChange<State> {
            copy(value = value + 1)
        } +
            StateChange {
                copy(value = value + 1)
            } +
            StateChange {
                copy(value = value + 1)
            }

        val state = additionStateChange.mutate(State())

        assertEquals(State(3), state)
    }
}
