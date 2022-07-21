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

package com.example.android.architecture.blueprints.todoapp.addedittask

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android.architecture.blueprints.todoapp.R
import com.example.android.architecture.blueprints.todoapp.TodoDestinationsArgs
import com.example.android.architecture.blueprints.todoapp.data.Result.Success
import com.example.android.architecture.blueprints.todoapp.data.Task
import com.example.android.architecture.blueprints.todoapp.data.source.TasksRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState for the Add/Edit screen
 */
data class AddEditTaskUiState(
    val title: String = "",
    val description: String = "",
    val isTaskCompleted: Boolean = false,
    val isLoading: Boolean = false,
    val userMessage: Int? = null,
    val isTaskSaved: Boolean = false
)

/**
 * ViewModel for the Add/Edit screen.
 */
@HiltViewModel
class AddEditTaskViewModel @Inject constructor(
    private val tasksRepository: TasksRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 任务Id，可能是编辑
    private val taskId: String? = savedStateHandle[TodoDestinationsArgs.TASK_ID_ARG]

    // A MutableStateFlow needs to be created in this ViewModel. The source of truth of the current
    // editable Task is the ViewModel, we need to mutate the UI state directly in methods such as
    // `updateTitle` or `updateDescription`
    private val _uiState = MutableStateFlow(AddEditTaskUiState())
    val uiState: StateFlow<AddEditTaskUiState> = _uiState.asStateFlow()

    init {
        if (taskId != null) {
            // 是编辑，加载Task，进行展示。
            loadTask(taskId)
        }
    }

    // Called when clicking on fab.
    // 保存操作
    fun saveTask() {
        if (uiState.value.title.isEmpty() || uiState.value.description.isEmpty()) {
            // 有一个为空，提示Snackbar。
            _uiState.update {
                it.copy(userMessage = R.string.empty_task_message)
            }
            return
        }

        if (taskId == null) {
            // 创建操作
            createNewTask()
        } else {
            // 更新操作
            updateTask()
        }
    }

    // 清空SnackBar
    fun snackbarMessageShown() {
        _uiState.update {
            it.copy(userMessage = null)
        }
    }

    // 更新标题
    fun updateTitle(newTitle: String) {
        _uiState.update {
            it.copy(title = newTitle)
        }
    }

    // 更新描述
    fun updateDescription(newDescription: String) {
        _uiState.update {
            it.copy(description = newDescription)
        }
    }

    // 创建新的Task
    private fun createNewTask() = viewModelScope.launch {
        val newTask = Task(uiState.value.title, uiState.value.description)
        // 进行保存
        tasksRepository.saveTask(newTask)
        // 更新UI
        _uiState.update {
            it.copy(isTaskSaved = true)
        }
    }

    // 更新Task
    private fun updateTask() {
        if (taskId == null) {
            throw RuntimeException("updateTask() was called but task is new.")
        }
        viewModelScope.launch {
            val updatedTask = Task(
                title = uiState.value.title,
                description = uiState.value.description,
                isCompleted = uiState.value.isTaskCompleted,
                id = taskId
            )
            // 进行保存
            tasksRepository.saveTask(updatedTask)
            // 更新UI
            _uiState.update {
                it.copy(isTaskSaved = true)
            }
        }
    }

    private fun loadTask(taskId: String) {
        // 先显示为加载中
        _uiState.update {
            it.copy(isLoading = true)
        }
        viewModelScope.launch {
            tasksRepository.getTask(taskId).let { result ->
                if (result is Success) {
                    // 成功，更新uiState进行展示。
                    val task = result.data
                    _uiState.update {
                        it.copy(
                            title = task.title,
                            description = task.description,
                            isTaskCompleted = task.isCompleted,
                            isLoading = false
                        )
                    }
                } else {
                    // 失败，更新uiState进行展示。
                    _uiState.update {
                        it.copy(isLoading = false)
                    }
                }
            }
        }
    }
}
