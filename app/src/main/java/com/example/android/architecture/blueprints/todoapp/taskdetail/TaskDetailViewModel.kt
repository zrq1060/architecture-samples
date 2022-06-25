/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.architecture.blueprints.todoapp.taskdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android.architecture.blueprints.todoapp.R
import com.example.android.architecture.blueprints.todoapp.TodoDestinationsArgs
import com.example.android.architecture.blueprints.todoapp.data.Result
import com.example.android.architecture.blueprints.todoapp.data.Result.Success
import com.example.android.architecture.blueprints.todoapp.data.Task
import com.example.android.architecture.blueprints.todoapp.data.source.TasksRepository
import com.example.android.architecture.blueprints.todoapp.util.Async
import com.example.android.architecture.blueprints.todoapp.util.StateChange
import com.example.android.architecture.blueprints.todoapp.util.plus
import com.example.android.architecture.blueprints.todoapp.util.produceState
import com.example.android.architecture.blueprints.todoapp.util.pushStateChange
import com.example.android.architecture.blueprints.todoapp.util.withViewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/**
 * UiState for the Details screen.
 */
data class TaskDetailUiState(
    val task: Task? = null,
    val isLoading: Boolean = false,
    val userMessage: Int? = null,
    val isTaskDeleted: Boolean = false
)

/**
 * ViewModel for the Details screen.
 */
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val tasksRepository: TasksRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val taskId: String = savedStateHandle[TodoDestinationsArgs.TASK_ID_ARG]!!

    private val eventStateChanges = MutableSharedFlow<StateChange<TaskDetailUiState>>()

    private val loadStateChanges = tasksRepository.getTaskStream(taskId)
        .map { handleResult(it) }
        .onStart { emit(Async.Loading) }
        .loadStateChanges()

    val uiState: StateFlow<TaskDetailUiState> = viewModelScope.produceState(
        initial = TaskDetailUiState(isLoading = true),
        stateChangeFlows = listOf(
            loadStateChanges,
            eventStateChanges,
        )
    )

    fun deleteTask() = eventStateChanges.withViewModelScope {
        tasksRepository.deleteTask(taskId)
        pushStateChange { copy(isTaskDeleted = true) }
    }

    fun setCompleted(completed: Boolean) = eventStateChanges.withViewModelScope {
        val task = uiState.value.task ?: return@withViewModelScope
        if (completed) {
            tasksRepository.completeTask(task)
            pushStateChange(snackBarStateChange(R.string.task_marked_complete))
        } else {
            tasksRepository.activateTask(task)
            pushStateChange(snackBarStateChange(R.string.task_marked_active))
        }
    }

    fun refresh() = eventStateChanges.withViewModelScope {
        pushStateChange { copy(isLoading = true) }
        tasksRepository.refreshTask(taskId)
        pushStateChange { copy(isLoading = false) }
    }

    fun snackbarMessageShown() = eventStateChanges.withViewModelScope {
        pushStateChange { copy(isLoading = false) }
    }

    private fun snackBarStateChange(message: Int) = StateChange<TaskDetailUiState> {
        copy(userMessage = message)
    }

    private fun handleResult(tasksResult: Result<Task>): Async<Task?> =
        if (tasksResult is Success) {
            Async.Success(tasksResult.data)
        } else {
            Async.Success(null)
        }

    private fun Flow<Async<Task?>>.loadStateChanges(): Flow<StateChange<TaskDetailUiState>> =
        mapLatest { tasksResult: Async<Task?> ->
            when (tasksResult) {
                Async.Loading -> StateChange {
                    copy(isLoading = true)
                }
                is Async.Success -> when (val task = tasksResult.data) {
                    null -> snackBarStateChange(R.string.loading_tasks_error) + StateChange {
                        copy(task = null, isLoading = false)
                    }
                    else -> StateChange {
                        copy(task = task, isLoading = false)
                    }
                }
            }
        }
}
