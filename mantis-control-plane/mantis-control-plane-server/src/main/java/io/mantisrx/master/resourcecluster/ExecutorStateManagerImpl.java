/*
 * Copyright 2023 Netflix, Inc.
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

package io.mantisrx.master.resourcecluster;

import io.mantisrx.common.WorkerConstants;
import io.mantisrx.master.resourcecluster.ResourceClusterActor.BestFit;
import io.mantisrx.master.resourcecluster.ResourceClusterActor.GetActiveJobsRequest;
import io.mantisrx.master.resourcecluster.ResourceClusterActor.GetClusterUsageRequest;
import io.mantisrx.master.resourcecluster.ResourceClusterActor.TaskExecutorBatchAssignmentRequest;
import io.mantisrx.master.resourcecluster.proto.GetClusterIdleInstancesRequest;
import io.mantisrx.master.resourcecluster.proto.GetClusterUsageResponse;
import io.mantisrx.master.resourcecluster.proto.GetClusterUsageResponse.GetClusterUsageResponseBuilder;
import io.mantisrx.master.resourcecluster.proto.GetClusterUsageResponse.UsageByGroupKey;
import io.mantisrx.master.scheduler.CpuWeightedFitnessCalculator;
import io.mantisrx.master.scheduler.FitnessCalculator;
import io.mantisrx.runtime.MachineDefinition;
import io.mantisrx.server.core.domain.WorkerId;
import io.mantisrx.server.core.scheduler.SchedulingConstraints;
import io.mantisrx.server.core.scheduler.SizeDefinition;
import io.mantisrx.server.master.resourcecluster.ContainerSkuID;
import io.mantisrx.server.master.resourcecluster.ResourceCluster.ResourceOverview;
import io.mantisrx.server.master.resourcecluster.TaskExecutorAllocationRequest;
import io.mantisrx.server.master.resourcecluster.TaskExecutorID;
import io.mantisrx.server.master.resourcecluster.TaskExecutorRegistration;
import io.mantisrx.server.master.resourcecluster.TaskExecutorRegistration.TaskExecutorGroupKey;
import io.mantisrx.shaded.com.google.common.cache.Cache;
import io.mantisrx.shaded.com.google.common.cache.CacheBuilder;
import io.mantisrx.shaded.com.google.common.cache.RemovalListener;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

@Slf4j
class ExecutorStateManagerImpl implements ExecutorStateManager {
    private final Map<TaskExecutorID, TaskExecutorState> taskExecutorStateMap = new HashMap<>();
    Cache<String, JobRequirements> pendingJobRequests = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .removalListener((RemovalListener<String, JobRequirements>) notification -> {
            log.info("Removing key {} from pending job requests due to reason {}", notification.getKey(), notification.getCause());
        })
        .build();

    @Getter
    @ToString
    class JobRequirements {
        private final Map<TaskExecutorGroupKey, Integer> groupToTaskExecutorCount;

        JobRequirements(Map<SchedulingConstraints, Integer> constraintsToTaskExecutorCount) {
            this.groupToTaskExecutorCount = constraintsToTaskExecutorCount
                .entrySet()
                .stream()
                .collect(Collectors.toMap(entry -> findBestFitGroupOrDefault(entry.getKey()), Map.Entry::getValue));
        }

        public int getTotalWorkers() {
            return groupToTaskExecutorCount.values().stream().mapToInt(Integer::intValue).sum();
        }

        private TaskExecutorGroupKey findBestFitGroupOrDefault(SchedulingConstraints constraints) {
            return findBestGroup(constraints).orElse(new TaskExecutorGroupKey(constraints.getSize(), constraints.getSchedulingAttributes()));
        }
    }

    /**
     * Cache the available executors ready to accept assignments.
     */
    private final Map<TaskExecutorGroupKey, NavigableSet<TaskExecutorHolder>> executorsByGroup = new HashMap<>();

    // TODO(fdichiara): make this configurable
    private final FitnessCalculator fitnessCalculator = new CpuWeightedFitnessCalculator();

    private final Map<String, String> schedulingAttributes;

    private final Cache<TaskExecutorID, TaskExecutorState> archivedState = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .removalListener(notification ->
            log.info("Archived TaskExecutor: {} removed due to: {}", notification.getKey(), notification.getCause()))
        .build();

    ExecutorStateManagerImpl(Map<String, String> schedulingAttributes) {
        this.schedulingAttributes = schedulingAttributes;
    }

    @Override
    public void trackIfAbsent(TaskExecutorID taskExecutorID, TaskExecutorState state) {
        this.taskExecutorStateMap.putIfAbsent(taskExecutorID, state);
        if (this.archivedState.getIfPresent(taskExecutorID) != null) {
            log.info("Reviving archived executor: {}", taskExecutorID);
            this.archivedState.invalidate(taskExecutorID);
        }

        tryMarkAvailable(taskExecutorID, state);
    }

    /**
     * Add to buckets. however new executors won't have valid registration at this moment and requires marking
     * again later when registration is ready.
     * @param taskExecutorID taskExecutorID
     * @param state state
     * @return whether the target executor is marked as available.
     */
    private boolean tryMarkAvailable(TaskExecutorID taskExecutorID, TaskExecutorState state) {
        if (state.isAvailable() && state.getRegistration() != null) {
            TaskExecutorHolder teHolder = TaskExecutorHolder.of(taskExecutorID, state.getRegistration());
            log.debug("Marking executor {} as available for matching.", teHolder);
            TaskExecutorGroupKey taskExecutorGroupKey = state.getRegistration().getTaskExecutorGroupKey();
            if (!this.executorsByGroup.containsKey(taskExecutorGroupKey)) {
                log.info("[executorsByGroup] adding {} from TE: {}", taskExecutorGroupKey, teHolder);
                this.executorsByGroup.putIfAbsent(
                    taskExecutorGroupKey,
                    new TreeSet<>(TaskExecutorHolder.generationFirstComparator));
            }

            log.info("Assign {} to available.", teHolder.getId());
            return this.executorsByGroup.get(taskExecutorGroupKey).add(teHolder);
        }
        else {
            log.debug("Ignore unavailable TE: {}", taskExecutorID);
            return false;
        }
    }

    @Override
    public boolean tryMarkAvailable(TaskExecutorID taskExecutorID) {
        if (!this.taskExecutorStateMap.containsKey(taskExecutorID)) {
            log.warn("marking invalid executor as available: {}", taskExecutorID);
            return false;
        }

        TaskExecutorState taskExecutorState = this.taskExecutorStateMap.get(taskExecutorID);
        return tryMarkAvailable(taskExecutorID, taskExecutorState);
    }

    @Override
    public boolean tryMarkUnavailable(TaskExecutorID taskExecutorID) {
        if (this.taskExecutorStateMap.containsKey(taskExecutorID)) {
            TaskExecutorState taskExecutorState = this.taskExecutorStateMap.get(taskExecutorID);
            if (taskExecutorState.getRegistration() != null) {
                TaskExecutorGroupKey taskExecutorGroupKey = taskExecutorState.getRegistration().getTaskExecutorGroupKey();
                if (this.executorsByGroup.containsKey(taskExecutorGroupKey)) {
                    this.executorsByGroup.get(taskExecutorGroupKey)
                        .remove(TaskExecutorHolder.of(taskExecutorID, taskExecutorState.getRegistration()));
                }
                return true;
            }
        }

        // todo: check archive map as well?
        log.warn("invalid task executor to mark as unavailable: {}", taskExecutorID);
        return false;
    }

    @Override
    public ResourceOverview getResourceOverview() {
        long numRegistered = taskExecutorStateMap.values().stream().filter(TaskExecutorState::isRegistered).count();
        long numAvailable = taskExecutorStateMap.values().stream().filter(TaskExecutorState::isAvailable).count();
        long numOccupied = taskExecutorStateMap.values().stream().filter(TaskExecutorState::isRunningTask).count();
        long numAssigned = taskExecutorStateMap.values().stream().filter(TaskExecutorState::isAssigned).count();
        long numDisabled = taskExecutorStateMap.values().stream().filter(TaskExecutorState::isDisabled).count();

        return new ResourceOverview(numRegistered, numAvailable, numOccupied, numAssigned, numDisabled);
    }

    @Override
    public List<TaskExecutorID> getIdleInstanceList(GetClusterIdleInstancesRequest req) {
        return this.taskExecutorStateMap.entrySet().stream()
            .filter(kv -> {
                if (kv.getValue().getRegistration() == null) {
                    return false;
                }

                Optional<ContainerSkuID> skuIdO =
                    kv.getValue().getRegistration().getTaskExecutorContainerDefinitionId();
                return skuIdO.isPresent() && skuIdO.get().equals(req.getSkuId());
            })
            .filter(isAvailable)
            .map(Entry::getKey)
            .limit(req.getMaxInstanceCount())
            .collect(Collectors.toList());
    }

    @Override
    public TaskExecutorState get(TaskExecutorID taskExecutorID) {
        return this.taskExecutorStateMap.get(taskExecutorID);
    }

    @Override
    public TaskExecutorState getIncludeArchived(TaskExecutorID taskExecutorID) {
        if (this.taskExecutorStateMap.containsKey(taskExecutorID)) {
            return this.taskExecutorStateMap.get(taskExecutorID);
        }
        return this.archivedState.getIfPresent(taskExecutorID);
    }

    @Override
    public TaskExecutorState archive(TaskExecutorID taskExecutorID) {
        if (this.taskExecutorStateMap.containsKey(taskExecutorID)) {
            this.archivedState.put(taskExecutorID, this.taskExecutorStateMap.get(taskExecutorID));
            this.taskExecutorStateMap.remove(taskExecutorID);
            return this.archivedState.getIfPresent(taskExecutorID);
        }
        else {
            log.warn("archiving invalid TaskExecutor: {}", taskExecutorID);
            return null;
        }
    }

    @Override
    public List<TaskExecutorID> getTaskExecutors(Predicate<Entry<TaskExecutorID, TaskExecutorState>> predicate) {
        return this.taskExecutorStateMap
            .entrySet()
            .stream()
            .filter(predicate)
            .map(Entry::getKey)
            .collect(Collectors.toList());
    }

    @Override
    public List<String> getActiveJobs(GetActiveJobsRequest req) {
        return this.taskExecutorStateMap
            .values()
            .stream()
            .map(TaskExecutorState::getWorkerId)
            .filter(Objects::nonNull)
            .map(WorkerId::getJobId)
            .distinct()
            .sorted((String::compareToIgnoreCase))
            .skip(req.getStartingIndex().orElse(0))
            .limit(req.getPageSize().orElse(3000))
            .collect(Collectors.toList());
    }

    @Override
    public Optional<Entry<TaskExecutorID, TaskExecutorState>> findFirst(
        Predicate<Entry<TaskExecutorID, TaskExecutorState>> predicate) {
        return taskExecutorStateMap
            .entrySet()
            .stream()
            .filter(predicate)
            .findFirst();
    }

    @Override
    public Optional<BestFit> findBestFit(TaskExecutorBatchAssignmentRequest request) {

        if (request.getAllocationRequests().isEmpty()) {
            log.warn("TaskExecutorBatchAssignmentRequest {} with empty allocation requests.", request);
            return Optional.empty();
        }

        boolean noResourcesAvailable = false;
        final BestFit bestFit = new BestFit();
        final boolean isJobIdAlreadyPending = pendingJobRequests.getIfPresent(request.getJobId()) != null;

        for (Entry<SchedulingConstraints, List<TaskExecutorAllocationRequest>> entry : request.getGroupedBySchedulingConstraints().entrySet()) {
            final SchedulingConstraints schedulingConstraints = entry.getKey();
            final List<TaskExecutorAllocationRequest> allocationRequests = entry.getValue();

            Optional<Map<TaskExecutorID, TaskExecutorState>> taskExecutors = findTaskExecutorsFor(request, schedulingConstraints, allocationRequests, isJobIdAlreadyPending, bestFit);

            // Mark noResourcesAvailable if we can't find enough TEs for a given set of scheduling constraints
            if (!taskExecutors.isPresent()) {
                noResourcesAvailable = true;
                break;
            }

            // Map each TE to a given allocation request
            int index = 0;
            for (Entry<TaskExecutorID, TaskExecutorState> taskToStateEntry : taskExecutors.get().entrySet()) {
                bestFit.add(allocationRequests.get(index), Pair.of(taskToStateEntry.getKey(), taskToStateEntry.getValue()));
                index++;
            }
        }

        if (noResourcesAvailable) {
            log.warn("Not all scheduling constraints had enough workers available to fulfill the request {}", request);
            return Optional.empty();
        } else {
            // Return best fit only if there are enough available TEs for all scheduling constraints
            return Optional.of(bestFit);
        }

    }

    private Optional<Map<TaskExecutorID, TaskExecutorState>> findBestFitFor(TaskExecutorBatchAssignmentRequest request, SchedulingConstraints schedulingConstraints, Integer numWorkers, BestFit currentBestFit) {
        // only allow allocation in the lowest CPU cores matching group.
        Optional<TaskExecutorGroupKey> bestFitTeGroupKey = findBestGroup(schedulingConstraints);

        if (!bestFitTeGroupKey.isPresent()) {
            log.warn("Cannot find any matching sku for request: {}", request);
            return Optional.empty();
        }

        log.info("Applying assignment request: {} to constraints {}.", request, bestFitTeGroupKey);
        if (!this.executorsByGroup.containsKey(bestFitTeGroupKey.get())) {
            log.warn("No available TE found for constraints: {}, request: {}", bestFitTeGroupKey.get(), request);
            return Optional.empty();
        }

        return Optional.of(
            this.executorsByGroup.get(bestFitTeGroupKey.get())
                .descendingSet()
                .stream()
                .filter(teHolder -> {
                    if (!this.taskExecutorStateMap.containsKey(teHolder.getId())) {
                        return false;
                    }
                    if (currentBestFit.contains(teHolder.getId())) {
                        return false;
                    }
                    TaskExecutorState st = this.taskExecutorStateMap.get(teHolder.getId());
                    return st.isAvailable() &&
                        st.getRegistration() != null;
                })
                .limit(numWorkers)
                .map(TaskExecutorHolder::getId)
                .collect(Collectors.toMap(
                    taskExecutorID -> taskExecutorID,
                    this.taskExecutorStateMap::get)));
    }

    /**
     * Verifies if all scheduling attributes constraints are satisfied.
     *
     * For each entry in 'schedulingAttributes':
     * - Fetch the corresponding attribute value from Task Executor scheduling attributes, if not present, default to the value from the current entry.
     * - Fetch the corresponding attribute value from Schedule Request scheduling attributes, if not present, default to the value from the current entry.
     * - Checks if these two values are equal ignoring case. If any pair is not equal, the function returns false.
     *
     * Hence, the function ensures that the TaskExecutor scheduling attributes match or satisfy the constraints required. If either the TaskExecutor registration or the scheduling request lacks an attribute, it uses the provided defaults.
     *
     * @param constraints The schedule request constraints to be satisfied.
     * @param teAssignmentAttributes The scheduling attributes of a Task Executor that needs to satisfy scheduling constraints.
     *
     * @return true if all allocation constraints are satisfied, false otherwise.
     */
    public boolean areSchedulingAttributeConstraintsSatisfied(SchedulingConstraints constraints, Map<String, String> teAssignmentAttributes) {
        return schedulingAttributes.entrySet()
            .stream()
            .allMatch(entry -> teAssignmentAttributes
                .getOrDefault(entry.getKey(), entry.getValue())
                .equalsIgnoreCase(constraints.getSchedulingAttributes()
                    .getOrDefault(entry.getKey(), entry.getValue())));
    }

    @Override
    public Set<Entry<TaskExecutorID, TaskExecutorState>> getActiveExecutorEntry() {
        return this.taskExecutorStateMap.entrySet();
    }

    @Override
    public GetClusterUsageResponse getClusterUsage(GetClusterUsageRequest req) {
        // default grouping is containerSkuID to usage
        Map<String, Integer> pendingCountByGroupKey = new HashMap<>();
        Map<String, Pair<Integer, Integer>> usageByGroupKey = new HashMap<>();
        // helper struct to verify job has been fully deployed so we can remove it from pending
        Map<String, List<MachineDefinition>> jobIdToMachineDef = new HashMap<>();

        taskExecutorStateMap.forEach((key, value) -> {
            if (value == null ||
                value.getRegistration() == null) {
                log.info("Empty registration: {}, {}. Skip usage request.", req.getClusterID(), key);
                return;
            }

            // do not count the disabled TEs.
            if (value.isDisabled()) {
                return;
            }

            Optional<String> groupKeyO =
                req.getGroupKeyFunc().apply(value.getRegistration());

            if (!groupKeyO.isPresent()) {
                log.info("Empty groupKey from: {}, {}. Skip usage request.", req.getClusterID(), key);
                return;
            }

            String groupKey = groupKeyO.get();

            Pair<Integer, Integer> kvState = Pair.of(
                value.isAvailable() ? 1 : 0,
                value.isRegistered() ? 1 : 0);

            if (usageByGroupKey.containsKey(groupKey)) {
                Pair<Integer, Integer> prevState = usageByGroupKey.get(groupKey);
                usageByGroupKey.put(
                    groupKey,
                    Pair.of(
                        kvState.getLeft() + prevState.getLeft(), kvState.getRight() + prevState.getRight()));
            } else {
                usageByGroupKey.put(groupKey, kvState);
            }

            if ((value.isAssigned() || value.isRunningTask()) && value.getWorkerId() != null) {
                if (pendingJobRequests.getIfPresent(value.getWorkerId().getJobId()) != null) {
                    List<MachineDefinition> workers = jobIdToMachineDef.getOrDefault(value.getWorkerId().getJobId(), new ArrayList<>());
                    workers.add(value.getRegistration().getMachineDefinition());
                    jobIdToMachineDef.put(value.getWorkerId().getJobId(), workers);
                }
            }

            if (!pendingCountByGroupKey.containsKey(groupKey)) {
                pendingCountByGroupKey.put(
                    groupKey,
                    getPendingCountyByTaskExecutorGroup(value.getRegistration().getTaskExecutorGroupKey()));
            }
        });

        // remove jobs from pending set which have all pending workers
        jobIdToMachineDef.forEach((jobId, workers) -> {
            final JobRequirements jobStats = pendingJobRequests.getIfPresent(jobId);
            if (jobStats != null && jobStats.getTotalWorkers() <= workers.size()) {
                log.info("Removing job {} from pending requests", jobId);
                pendingJobRequests.invalidate(jobId);
            }
        });

        GetClusterUsageResponseBuilder resBuilder = GetClusterUsageResponse.builder().clusterID(req.getClusterID());
        usageByGroupKey.forEach((key, value) -> resBuilder.usage(UsageByGroupKey.builder()
            .usageGroupKey(key)
            .idleCount(value.getLeft() - pendingCountByGroupKey.get(key))
            .totalCount(value.getRight())
            .build()));

        GetClusterUsageResponse res = resBuilder.build();
        log.info("Usage result: {}", res);
        return res;
    }

    private int getPendingCountyByTaskExecutorGroup(TaskExecutorGroupKey teGroup) {
        return pendingJobRequests
            .asMap()
            .values()
            .stream()
            .map(req -> req.getGroupToTaskExecutorCount().getOrDefault(teGroup, 0))
            .reduce(Integer::sum)
            .orElse(0);
    }

    private Optional<Map<TaskExecutorID, TaskExecutorState>> findTaskExecutorsFor(TaskExecutorBatchAssignmentRequest request, SchedulingConstraints schedulingConstraints, List<TaskExecutorAllocationRequest> allocationRequests, boolean isJobIdAlreadyPending, BestFit currentBestFit) {
        // Finds best fit for N workers of the same scheduling constraints
        final Optional<Map<TaskExecutorID, TaskExecutorState>> taskExecutors = findBestFitFor(
            request, schedulingConstraints, allocationRequests.size(), currentBestFit);

        // Verify that the number of task executors returned matches the asked
        if (taskExecutors.isPresent() && taskExecutors.get().size() == allocationRequests.size()) {
            return taskExecutors;
        } else {
            SizeDefinition size = schedulingConstraints.getSize();
            log.warn("Not enough available TEs found for size {}, request: {}", size, request);

            // If there are not enough workers with the given spec then add the request the pending ones
            if (!isJobIdAlreadyPending && request.getAllocationRequests().size() > 2) {
                // Add jobId to pending requests only once
                if (pendingJobRequests.getIfPresent(request.getJobId()) == null) {
                    log.info("Adding job {} to pending requests for {} machine {}", request.getJobId(), allocationRequests.size(), size);
                    pendingJobRequests.put(request.getJobId(), new JobRequirements(request.getGroupedByConstraintsCount()));
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Finds the best fit Task Executor Group Key from the current set of executors
     * based on the provided `requestedConstraints`.
     *
     * It filters the keys such that they satisfy the `requestedConstraints` and have a positive fitness score,
     * then decides the best fit based on the highest fitness score.
     *
     * @param requestedConstraints Constraints of the scheduling request, serving as the reference for determining the best fit.
     *
     * @return An Optional wrapping the Task Executor Group Key with the best fit constraints. If no fitting constraints
     * are found, it returns an empty Optional.
     */
    private Optional<TaskExecutorGroupKey> findBestGroup(SchedulingConstraints requestedConstraints) {
        return executorsByGroup.keySet()
            .stream()
            // filter in the keys that have satisfied allocation constraints
            .filter(taskExecutorGroupKey -> areSchedulingAttributeConstraintsSatisfied(requestedConstraints, taskExecutorGroupKey.getSchedulingAttributes()))
            // Map each TaskExecutorGroupKey to a Pair containing the key and its corresponding fitness score
            .map(key -> new AbstractMap.SimpleEntry<>(
                key,
                calculateFitness(requestedConstraints.getSize(), key.getSizeDefinition())
            ))
            // Filter out entries with non-positive fitness scores
            .filter(entry -> entry.getValue() > 0)
            // Find the entry with the highest fitness score
            .max(Entry.comparingByValue())
            // If a suitable entry was found, extract the TaskExecutorGroupKey
            .map(AbstractMap.SimpleEntry::getKey);
    }

    public double calculateFitness(SizeDefinition requestedSize, SizeDefinition teSize) {
        if (requestedSize.getLabel() != null && teSize.getLabel() != null) {
            if (requestedSize.getLabel().equalsIgnoreCase(teSize.getLabel())) {
                return 1.0;
            } else {
                return 0.0;
            }
        }
        if (requestedSize.getMachineDefinition() != null && teSize.getMachineDefinition() != null) {
            return fitnessCalculator.calculate(requestedSize.getMachineDefinition(), teSize.getMachineDefinition());
        }
        return 0.0;
    }

    /**
     * Holder class in {@link ExecutorStateManagerImpl} to wrap task executor ID with other metatdata needed during
     * scheduling e.g. generation.
     */
    @Builder
    @Value
    protected static class TaskExecutorHolder {
        TaskExecutorID Id;
        String generation;

        static TaskExecutorHolder of(TaskExecutorID id, TaskExecutorRegistration reg) {
            String generation = reg.getAttributeByKey(WorkerConstants.MANTIS_WORKER_CONTAINER_GENERATION)
                .orElse(reg.getAttributeByKey(WorkerConstants.AUTO_SCALE_GROUP_KEY).orElse("empty-generation"));
            return TaskExecutorHolder.builder()
                .Id(id)
                .generation(generation)
                .build();
        }

        static Comparator<TaskExecutorHolder> generationFirstComparator =
            Comparator.comparing(TaskExecutorHolder::getGeneration)
                .thenComparing(teh -> teh.getId().getResourceId());
    }
}
