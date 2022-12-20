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
package com.example.android.architecture.blueprints.todoapp.data.source

import com.example.android.architecture.blueprints.todoapp.data.Result
import com.example.android.architecture.blueprints.todoapp.data.Result.Success
import com.example.android.architecture.blueprints.todoapp.data.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Default implementation of [TasksRepository]. Single entry point for managing tasks' data.
 */
class DefaultTasksRepository(
    private val tasksRemoteDataSource: TasksDataSource,
    private val tasksLocalDataSource: TasksDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TasksRepository {

    // 获取所有Task，强制更新则同步网络，维护本地源，使用本地源。
    override suspend fun getTasks(forceUpdate: Boolean): Result<List<Task>> {
        if (forceUpdate) {
            try {
                updateTasksFromRemoteDataSource()
            } catch (ex: Exception) {
                return Result.Error(ex)
            }
        }
        return tasksLocalDataSource.getTasks()
    }

    // 刷新所有Task，维护本地源。
    override suspend fun refreshTasks() {
        updateTasksFromRemoteDataSource()
    }

    // 获取所有Task的流，使用本地源。
    override fun getTasksStream(): Flow<Result<List<Task>>> {
        // 使用本地数据源，唯一
        return tasksLocalDataSource.getTasksStream()
    }

    // 刷新一个Task，维护本地源。
    override suspend fun refreshTask(taskId: String) {
        updateTaskFromRemoteDataSource(taskId)
    }

    // 刷新所有Task，维护本地源。
    private suspend fun updateTasksFromRemoteDataSource() {
        // 获取远端数据
        val remoteTasks = tasksRemoteDataSource.getTasks()

        if (remoteTasks is Success) {
            // 成功，同步本地（先删后增）。
            // Real apps might want to do a proper sync, deleting, modifying or adding each task.
            // 真正的应用程序可能需要适当地同步、删除、修改或添加每个任务。
            tasksLocalDataSource.deleteAllTasks()
            remoteTasks.data.forEach { task ->
                tasksLocalDataSource.saveTask(task)
            }
        } else if (remoteTasks is Result.Error) {
            // 失败，抛出异常。
            throw remoteTasks.exception
        }
    }

    // 获取一个Task的流，使用本地源。
    override fun getTaskStream(taskId: String): Flow<Result<Task>> {
        return tasksLocalDataSource.getTaskStream(taskId)
    }

    // 刷新一个Task，维护本地源。
    private suspend fun updateTaskFromRemoteDataSource(taskId: String) {
        val remoteTask = tasksRemoteDataSource.getTask(taskId)

        if (remoteTask is Success) {
            tasksLocalDataSource.saveTask(remoteTask.data)
        }
    }

    /**
     * Relies on [getTasks] to fetch data and picks the task with the same ID.
     */
    // 获取一个Task，使用本地源。
    override suspend fun getTask(taskId: String, forceUpdate: Boolean): Result<Task> {
        if (forceUpdate) {
            updateTaskFromRemoteDataSource(taskId)
        }
        return tasksLocalDataSource.getTask(taskId)
    }

    // 保存一个Task，维护远端源、本地源。
    override suspend fun saveTask(task: Task) {
        coroutineScope {
            launch { tasksRemoteDataSource.saveTask(task) }
            launch { tasksLocalDataSource.saveTask(task) }
        }
    }

    // 完成一个Task，维护远端源、本地源。
    override suspend fun completeTask(task: Task) {
        coroutineScope {
            launch { tasksRemoteDataSource.completeTask(task) }
            launch { tasksLocalDataSource.completeTask(task) }
        }
    }

    // 完成一个Task，维护远端源、本地源。
    override suspend fun completeTask(taskId: String) {
        // 在IO线程，先找到Task，然后操作完成。
        withContext(ioDispatcher) {
            (getTaskWithId(taskId) as? Success)?.let { it ->
                completeTask(it.data)
            }
        }
    }

    // 激活一个Task，维护远端源、本地源。
    override suspend fun activateTask(task: Task) = withContext<Unit>(ioDispatcher) {
        coroutineScope {
            launch { tasksRemoteDataSource.activateTask(task) }
            launch { tasksLocalDataSource.activateTask(task) }
        }
    }

    // 激活一个Task，维护远端源、本地源。
    override suspend fun activateTask(taskId: String) {
        // 在IO线程，先找到Task，然后操作激活。
        withContext(ioDispatcher) {
            (getTaskWithId(taskId) as? Success)?.let { it ->
                activateTask(it.data)
            }
        }
    }

    // 清除所有完成的Task，维护远端源、本地源。
    override suspend fun clearCompletedTasks() {
        // 使用coroutineScope结构化并发，要么全部成功，要么全部失败。它会等全部子携程执行完。
        coroutineScope {
            launch { tasksRemoteDataSource.clearCompletedTasks() }
            launch { tasksLocalDataSource.clearCompletedTasks() }
        }
    }

    // 删除所有Task，维护远端源、本地源。
    override suspend fun deleteAllTasks() {
        // 删除所有，在IO线程操作。
        withContext(ioDispatcher) {
            coroutineScope {
                launch { tasksRemoteDataSource.deleteAllTasks() }
                launch { tasksLocalDataSource.deleteAllTasks() }
            }
        }
    }

    // 删除一个Task，维护远端源、本地源。
    override suspend fun deleteTask(taskId: String) {
        coroutineScope {
            launch { tasksRemoteDataSource.deleteTask(taskId) }
            launch { tasksLocalDataSource.deleteTask(taskId) }
        }
    }

    private suspend fun getTaskWithId(id: String): Result<Task> {
        return tasksLocalDataSource.getTask(id)
    }
    // 总结：
    // 增加，增加网络，增加本地，要保证同步，需要coroutineScope。
    //      现实中，网络成功后再增加到本地，但是本地可能会失败，都成功为成功，其它情况为失败，不需要coroutineScope。
    // 删除，删除网络，删除本地，要保证同步，需要coroutineScope。
    // 更改，更改网络，更改本地，要保证同步，需要coroutineScope。
    // 查询，查询本地，不需要coroutineScope。

    // 从远端更新，查询网络，更新本地，不要保证同步，不需要coroutineScope。

    // 增、删、改，维护远端源、本地源。
    // 查，只使用本地源。
}
