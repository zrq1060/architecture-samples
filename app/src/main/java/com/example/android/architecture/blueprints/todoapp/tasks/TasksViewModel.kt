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
package com.example.android.architecture.blueprints.todoapp.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.android.architecture.blueprints.todoapp.ADD_EDIT_RESULT_OK
import com.example.android.architecture.blueprints.todoapp.DELETE_RESULT_OK
import com.example.android.architecture.blueprints.todoapp.EDIT_RESULT_OK
import com.example.android.architecture.blueprints.todoapp.R
import com.example.android.architecture.blueprints.todoapp.data.Result
import com.example.android.architecture.blueprints.todoapp.data.Result.Success
import com.example.android.architecture.blueprints.todoapp.data.Task
import com.example.android.architecture.blueprints.todoapp.data.source.TasksRepository
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ACTIVE_TASKS
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ALL_TASKS
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.COMPLETED_TASKS
import com.example.android.architecture.blueprints.todoapp.util.Async
import com.example.android.architecture.blueprints.todoapp.util.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState for the task list screen.
 */
data class TasksUiState(
    val items: List<Task> = emptyList(),
    val isLoading: Boolean = false,
    val filteringUiInfo: FilteringUiInfo = FilteringUiInfo(),
    val userMessage: Int? = null
)

/**
 * ViewModel for the task list screen.
 */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val tasksRepository: TasksRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 过滤类型，3选1
    private val _savedFilterType =
        savedStateHandle.getStateFlow(TASKS_FILTER_SAVED_STATE_KEY, ALL_TASKS)

    // 根据过滤类型，获取对应的过滤UI展示信息，并只在有新对象时通知。
    private val _filterUiInfo = _savedFilterType.map { getFilterUiInfo(it) }.distinctUntilChanged()

    // 提示消息
    private val _userMessage: MutableStateFlow<Int?> = MutableStateFlow(null)

    // 是否加载中
    private val _isLoading = MutableStateFlow(false)

    // 过滤Task，Tasks流、过滤类型更改，则触发过滤。
    private val _filteredTasksAsync =
        combine(tasksRepository.getTasksStream(), _savedFilterType) { tasks, type ->
            // 根据类型进行过滤
            filterTasks(tasks, type)
        }
            // 不管列表是否有数据，都为成功。
            .map { Async.Success(it) }
            // 开始时，为Loading状态。
            .onStart<Async<List<Task>>> { emit(Async.Loading) }

    // uiState，与UI相关的一个更改，则触发更改。
    val uiState: StateFlow<TasksUiState> = combine(
        _filterUiInfo, _isLoading, _userMessage, _filteredTasksAsync
    ) { filterUiInfo, isLoading, userMessage, tasksAsync ->
        when (tasksAsync) {
            // 加载中显示
            Async.Loading -> {
                TasksUiState(isLoading = true)
            }
            // 加载成功显示
            is Async.Success -> {
                TasksUiState(
                    items = tasksAsync.data,
                    filteringUiInfo = filterUiInfo,
                    isLoading = isLoading,
                    userMessage = userMessage
                )
            }
        }
    }
        // 将其转为StateFlow，
        .stateIn(
            scope = viewModelScope,
            // 被订阅的时候开始
            started = WhileUiSubscribed,
            // 初始化为加载中
            initialValue = TasksUiState(isLoading = true)
        )

    // 设置过滤类型
    fun setFiltering(requestType: TasksFilterType) {
        savedStateHandle[TASKS_FILTER_SAVED_STATE_KEY] = requestType
    }

    // 清除已经完成的
    fun clearCompletedTasks() {
        viewModelScope.launch {
            // 清除数据源
            tasksRepository.clearCompletedTasks()
            // 展示提示
            showSnackbarMessage(R.string.completed_tasks_cleared)
            // 重新刷新
            refresh()
        }
    }

    // 标记单个Task是已完成状态，还是激活状态。
    fun completeTask(task: Task, completed: Boolean) = viewModelScope.launch {
        if (completed) {
            // 标记为完成的
            // -修改数据源
            tasksRepository.completeTask(task)
            // -提示
            showSnackbarMessage(R.string.task_marked_complete)
        } else {
            tasksRepository.activateTask(task)
            showSnackbarMessage(R.string.task_marked_active)
        }
    }

    // 展示增加、编辑、删除后的结果提示。
    fun showEditResultMessage(result: Int) {
        when (result) {
            EDIT_RESULT_OK -> showSnackbarMessage(R.string.successfully_saved_task_message)
            ADD_EDIT_RESULT_OK -> showSnackbarMessage(R.string.successfully_added_task_message)
            DELETE_RESULT_OK -> showSnackbarMessage(R.string.successfully_deleted_task_message)
        }
    }

    // Snackbar已经展示，清除数据。
    fun snackbarMessageShown() {
        _userMessage.value = null
    }

    private fun showSnackbarMessage(message: Int) {
        _userMessage.value = message
    }

    fun refresh() {
        // 展示加载中
        _isLoading.value = true
        viewModelScope.launch {
            // 刷新数据源
            tasksRepository.refreshTasks()
            // 隐藏加载中
            _isLoading.value = false
        }
    }

    private fun filterTasks(
        tasksResult: Result<List<Task>>,
        filteringType: TasksFilterType
    ): List<Task> = if (tasksResult is Success) {
        // 获取Tasks成功，根据类型进行过滤。
        filterItems(tasksResult.data, filteringType)
    } else {
        // 获取Tasks失败，展示Snackbar提示，空列表。
        showSnackbarMessage(R.string.loading_tasks_error)
        emptyList()
    }

    private fun filterItems(tasks: List<Task>, filteringType: TasksFilterType): List<Task> {
        val tasksToShow = ArrayList<Task>()
        // We filter the tasks based on the requestType
        for (task in tasks) {
            when (filteringType) {
                ALL_TASKS -> tasksToShow.add(task)
                ACTIVE_TASKS -> if (task.isActive) {
                    tasksToShow.add(task)
                }
                COMPLETED_TASKS -> if (task.isCompleted) {
                    tasksToShow.add(task)
                }
            }
        }
        return tasksToShow
    }

    private fun getFilterUiInfo(requestType: TasksFilterType): FilteringUiInfo =
        when (requestType) {
            ALL_TASKS -> {
                FilteringUiInfo(
                    R.string.label_all, R.string.no_tasks_all,
                    R.drawable.logo_no_fill
                )
            }
            ACTIVE_TASKS -> {
                FilteringUiInfo(
                    R.string.label_active, R.string.no_tasks_active,
                    R.drawable.ic_check_circle_96dp
                )
            }
            COMPLETED_TASKS -> {
                FilteringUiInfo(
                    R.string.label_completed, R.string.no_tasks_completed,
                    R.drawable.ic_verified_user_96dp
                )
            }
        }
}

// Used to save the current filtering in SavedStateHandle.
const val TASKS_FILTER_SAVED_STATE_KEY = "TASKS_FILTER_SAVED_STATE_KEY"

data class FilteringUiInfo(
    val currentFilteringLabel: Int = R.string.label_all,
    val noTasksLabel: Int = R.string.no_tasks_all,
    val noTaskIconRes: Int = R.drawable.logo_no_fill,
)
