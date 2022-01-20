/*
 * Copyright 2022 Netflix, Inc.
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
package io.mantisrx.server.worker;

import io.mantisrx.server.core.ExecutionAttemptID;
import io.mantisrx.shaded.com.google.common.base.Preconditions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskTableImpl implements TaskTable {

  private final Map<ExecutionAttemptID, Task> taskMap;

  public TaskTableImpl() {
    this.taskMap = new ConcurrentHashMap<>();
  }

  @Override
  public boolean addTask(Task task) {
    Preconditions.checkArgument(!taskMap.containsKey(task.getExecutionAttemptID()));
    return taskMap.computeIfAbsent(task.getExecutionAttemptID(), (executionAttemptID -> task))
        == task;
  }

  @Override
  public Task getTask(ExecutionAttemptID executionAttemptID) {
    return taskMap.get(executionAttemptID);
  }

  @Override
  public Task removeTask(ExecutionAttemptID executionAttemptID) {
    return taskMap.remove(executionAttemptID);
  }
}
