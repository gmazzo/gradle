/*
 * Copyright 2018 the original author or authors.
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
package org.gradle.api.internal.project;

import com.google.common.collect.Maps;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.internal.artifacts.DefaultProjectComponentIdentifier;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.initialization.DefaultProjectDescriptor;
import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.gradle.internal.Factories;
import org.gradle.internal.Factory;
import org.gradle.internal.build.BuildProjectRegistry;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.model.CalculatedModelValue;
import org.gradle.internal.model.ModelContainer;
import org.gradle.internal.resources.ProjectLeaseRegistry;
import org.gradle.internal.resources.ResourceLock;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.util.Path;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

public class DefaultProjectStateRegistry implements ProjectStateRegistry {
    private final WorkerLeaseService workerLeaseService;
    private final Object lock = new Object();
    private final Map<Path, ProjectStateImpl> projectsByPath = Maps.newLinkedHashMap();
    private final Map<ProjectComponentIdentifier, ProjectStateImpl> projectsById = Maps.newHashMap();
    private final Map<BuildIdentifier, DefaultBuildProjectRegistry> projectsByBuild = Maps.newHashMap();

    public DefaultProjectStateRegistry(WorkerLeaseService workerLeaseService) {
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public void registerProjects(BuildState owner, ProjectRegistry<DefaultProjectDescriptor> projectRegistry) {
        Set<DefaultProjectDescriptor> allProjects = projectRegistry.getAllProjects();
        synchronized (lock) {
            DefaultBuildProjectRegistry buildProjectRegistry = getBuildProjectRegistry(owner);
            if (!buildProjectRegistry.projectsByPath.isEmpty()) {
                throw new IllegalStateException("Projects for " + owner.getDisplayName() + " have already been registered.");
            }
            for (DefaultProjectDescriptor descriptor : allProjects) {
                addProject(owner, buildProjectRegistry, descriptor);
            }
        }
    }

    private DefaultBuildProjectRegistry getBuildProjectRegistry(BuildState owner) {
        DefaultBuildProjectRegistry buildProjectRegistry = projectsByBuild.get(owner.getBuildIdentifier());
        if (buildProjectRegistry == null) {
            buildProjectRegistry = new DefaultBuildProjectRegistry(owner);
            projectsByBuild.put(owner.getBuildIdentifier(), buildProjectRegistry);
        }
        return buildProjectRegistry;
    }

    @Override
    public ProjectState registerProject(BuildState owner, DefaultProjectDescriptor projectDescriptor) {
        synchronized (lock) {
            DefaultBuildProjectRegistry buildProjectRegistry = getBuildProjectRegistry(owner);
            return addProject(owner, buildProjectRegistry, projectDescriptor);
        }
    }

    private ProjectState addProject(BuildState owner, DefaultBuildProjectRegistry projectRegistry, DefaultProjectDescriptor descriptor) {
        Path projectPath = descriptor.path();
        Path identityPath = owner.calculateIdentityPathForProject(projectPath);
        String name = descriptor.getName();
        ProjectComponentIdentifier projectIdentifier = new DefaultProjectComponentIdentifier(owner.getBuildIdentifier(), identityPath, projectPath, name);
        IProjectFactory projectFactory = owner.getMutableModel().getServices().get(IProjectFactory.class);
        ProjectStateImpl projectState = new ProjectStateImpl(owner, identityPath, projectPath, descriptor.getName(), projectIdentifier, descriptor, projectFactory);
        projectsByPath.put(identityPath, projectState);
        projectsById.put(projectIdentifier, projectState);
        projectRegistry.add(projectPath, projectState);
        return projectState;
    }

    @Override
    public Collection<ProjectStateImpl> getAllProjects() {
        synchronized (lock) {
            return projectsByPath.values();
        }
    }

    // TODO - can kill this method, as the caller can use ProjectInternal.getOwner() instead
    @Override
    public ProjectState stateFor(Project project) {
        return ((ProjectInternal) project).getOwner();
    }

    @Override
    public ProjectState stateFor(ProjectComponentIdentifier identifier) {
        synchronized (lock) {
            ProjectStateImpl projectState = projectsById.get(identifier);
            if (projectState == null) {
                throw new IllegalArgumentException(identifier.getDisplayName() + " not found.");
            }
            return projectState;
        }
    }

    @Override
    public BuildProjectRegistry projectsFor(BuildIdentifier buildIdentifier) throws IllegalArgumentException {
        synchronized (lock) {
            BuildProjectRegistry registry = projectsByBuild.get(buildIdentifier);
            if (registry == null) {
                throw new IllegalArgumentException("Projects for " + buildIdentifier + " have not been registered yet.");
            }
            return registry;
        }
    }

    @Override
    public void withMutableStateOfAllProjects(Runnable runnable) {
        withMutableStateOfAllProjects(Factories.toFactory(runnable));
    }

    @Override
    public <T> T withMutableStateOfAllProjects(Factory<T> factory) {
        ResourceLock allProjectsLock = workerLeaseService.getAllProjectsLock();
        Collection<? extends ResourceLock> locks = workerLeaseService.getCurrentProjectLocks();
        if (locks.contains(allProjectsLock)) {
            // Holds the lock so run the action
            return factory.create();
        }
        return workerLeaseService.withLocks(Collections.singletonList(allProjectsLock), () -> workerLeaseService.withoutLocks(locks, factory));
    }

    @Override
    public <T> T allowUncontrolledAccessToAnyProject(Factory<T> factory) {
        return workerLeaseService.allowUncontrolledAccessToAnyProject(factory);
    }

    private static class DefaultBuildProjectRegistry implements BuildProjectRegistry {
        private final BuildState owner;
        private final Map<Path, ProjectStateImpl> projectsByPath = Maps.newLinkedHashMap();

        public DefaultBuildProjectRegistry(BuildState owner) {
            this.owner = owner;
        }

        public void add(Path projectPath, ProjectStateImpl projectState) {
            projectsByPath.put(projectPath, projectState);
        }

        @Override
        public ProjectState getRootProject() {
            return getProject(Path.ROOT);
        }

        @Override
        public ProjectState getProject(Path projectPath) {
            ProjectStateImpl projectState = projectsByPath.get(projectPath);
            if (projectState == null) {
                throw new IllegalArgumentException("Project with path '" + projectPath + "' not found in " + owner.getDisplayName() + ".");
            }
            return projectState;
        }

        @Nullable
        @Override
        public ProjectState findProject(Path projectPath) {
            return projectsByPath.get(projectPath);
        }

        @Override
        public Set<? extends ProjectState> getAllProjects() {
            TreeSet<ProjectState> projects = new TreeSet<>(Comparator.comparing(ProjectState::getIdentityPath));
            projects.addAll(projectsByPath.values());
            return projects;
        }
    }

    private class ProjectStateImpl implements ProjectState {
        private final Path projectPath;
        private final String projectName;
        private final ProjectComponentIdentifier identifier;
        private final DefaultProjectDescriptor descriptor;
        private final IProjectFactory projectFactory;
        private final BuildState owner;
        private final Path identityPath;
        private final ResourceLock projectLock;
        private final ResourceLock taskLock;
        private final Set<Thread> canDoAnythingToThisProject = new CopyOnWriteArraySet<>();
        private ProjectInternal project;

        ProjectStateImpl(BuildState owner, Path identityPath, Path projectPath, String projectName, ProjectComponentIdentifier identifier, DefaultProjectDescriptor descriptor, IProjectFactory projectFactory) {
            this.owner = owner;
            this.identityPath = identityPath;
            this.projectPath = projectPath;
            this.projectName = projectName;
            this.identifier = identifier;
            this.descriptor = descriptor;
            this.projectFactory = projectFactory;
            this.projectLock = workerLeaseService.getProjectLock(owner.getIdentityPath(), identityPath);
            this.taskLock = workerLeaseService.getTaskExecutionLock(owner.getIdentityPath(), identityPath);
        }

        @Override
        public DisplayName getDisplayName() {
            if (projectPath.equals(Path.ROOT)) {
                return Describables.quoted("root project", projectName);
            }
            return Describables.of(identifier);
        }

        @Override
        public String toString() {
            return getDisplayName().getDisplayName();
        }

        @Override
        public BuildState getOwner() {
            return owner;
        }

        @Nullable
        @Override
        public ProjectState getParent() {
            return identityPath.getParent() == null ? null : projectsByPath.get(identityPath.getParent());
        }

        @Override
        public Set<ProjectState> getChildProjects() {
            Set<ProjectState> children = new TreeSet<>(Comparator.comparing(ProjectState::getIdentityPath));
            for (ProjectDescriptor child : descriptor.getChildren()) {
                children.add(projectsByPath.get(identityPath.child(child.getName())));
            }
            return children;
        }

        @Override
        public String getName() {
            return projectName;
        }

        @Override
        public Path getIdentityPath() {
            return identityPath;
        }

        @Override
        public Path getProjectPath() {
            return projectPath;
        }

        @Override
        public File getProjectDir() {
            return descriptor.getProjectDir();
        }

        @Override
        public void createMutableModel(ClassLoaderScope selfClassLoaderScope, ClassLoaderScope baseClassLoaderScope) {
            synchronized (this) {
                if (this.project != null) {
                    throw new IllegalStateException(String.format("The project object for project %s has already been attached.", getIdentityPath()));
                }
                ProjectInternal parent;
                if (projectPath.equals(Path.ROOT)) {
                    parent = null;
                } else {
                    parent = projectsByPath.get(identityPath.getParent()).getMutableModel();
                }
                this.project = projectFactory.createProject(owner.getMutableModel(), descriptor, this, parent, selfClassLoaderScope, baseClassLoaderScope);
            }
        }

        @Override
        public ProjectInternal getMutableModel() {
            synchronized (this) {
                if (project == null) {
                    throw new IllegalStateException(String.format("The project object for project %s has not been attached yet.", getIdentityPath()));
                }
                return project;
            }
        }

        @Override
        public void ensureConfigured() {
            synchronized (this) {
                getMutableModel().evaluate();
            }
        }

        @Override
        public void ensureTasksDiscovered() {
            synchronized (this) {
                ProjectInternal project = getMutableModel();
                project.evaluate();
                project.getTasks().discoverTasks();
                project.bindAllModelRules();
            }
        }

        @Override
        public ProjectComponentIdentifier getComponentIdentifier() {
            return identifier;
        }

        @Override
        public ResourceLock getAccessLock() {
            return projectLock;
        }

        @Override
        public ResourceLock getTaskExecutionLock() {
            return taskLock;
        }

        @Override
        public void applyToMutableState(Consumer<? super ProjectInternal> action) {
            fromMutableState(p -> {
                action.accept(p);
                return null;
            });
        }

        @Override
        public <S> S fromMutableState(Function<? super ProjectInternal, ? extends S> function) {
            Thread currentThread = Thread.currentThread();
            if (workerLeaseService.isAllowedUncontrolledAccessToAnyProject() || canDoAnythingToThisProject.contains(currentThread)) {
                // Current thread is allowed to access anything at any time, so run the function
                return function.apply(getMutableModel());
            }

            Collection<? extends ResourceLock> currentLocks = workerLeaseService.getCurrentProjectLocks();
            if (currentLocks.contains(projectLock) || currentLocks.contains(workerLeaseService.getAllProjectsLock())) {
                // if we already hold the project lock for this project
                if (currentLocks.size() == 1) {
                    // the lock for this project is the only lock we hold, can run the function
                    return function.apply(getMutableModel());
                } else {
                    throw new IllegalStateException("Current thread holds more than one project lock. It should hold only one project lock at any given time.");
                }
            } else {
                // we don't currently hold the project lock
                if (!currentLocks.isEmpty()) {
                    // we hold other project locks that we should release first
                    return workerLeaseService.withoutLocks(currentLocks, () -> withProjectLock(projectLock, function));
                } else {
                    // we just need to get the lock for this project
                    return withProjectLock(projectLock, function);
                }
            }
        }

        @Override
        public <S> S forceAccessToMutableState(Function<? super ProjectInternal, ? extends S> factory) {
            Thread currentThread = Thread.currentThread();
            boolean added = canDoAnythingToThisProject.add(currentThread);
            try {
                return factory.apply(getMutableModel());
            } finally {
                if (added) {
                    canDoAnythingToThisProject.remove(currentThread);
                }
            }
        }

        private <S> S withProjectLock(ResourceLock projectLock, final Function<? super ProjectInternal, ? extends S> function) {
            return workerLeaseService.withLocks(Collections.singleton(projectLock), () -> function.apply(getMutableModel()));
        }

        @Override
        public boolean hasMutableState() {
            Thread currentThread = Thread.currentThread();
            if (canDoAnythingToThisProject.contains(currentThread) || workerLeaseService.isAllowedUncontrolledAccessToAnyProject()) {
                return true;
            }
            Collection<? extends ResourceLock> locks = workerLeaseService.getCurrentProjectLocks();
            return locks.contains(projectLock) || locks.contains(workerLeaseService.getAllProjectsLock());
        }

        @Override
        public <T> CalculatedModelValue<T> newCalculatedValue(@Nullable T initialValue) {
            return new CalculatedModelValueImpl<>(this, workerLeaseService, initialValue);
        }
    }

    private static class CalculatedModelValueImpl<T> implements CalculatedModelValue<T> {
        private final ProjectLeaseRegistry projectLeaseRegistry;
        private final ModelContainer<?> owner;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile T value;

        public CalculatedModelValueImpl(ProjectStateImpl owner, WorkerLeaseService projectLeaseRegistry, @Nullable T initialValue) {
            this.projectLeaseRegistry = projectLeaseRegistry;
            this.value = initialValue;
            this.owner = owner;
        }

        @Override
        public T get() throws IllegalStateException {
            T currentValue = getOrNull();
            if (currentValue == null) {
                throw new IllegalStateException("No calculated value is available for " + owner);
            }
            return currentValue;
        }

        @Override
        public T getOrNull() {
            // Grab the current value, ignore updates that may be happening
            return value;
        }

        @Override
        public void set(T newValue) {
            assertCanMutate();
            value = newValue;
        }

        @Override
        public T update(Function<T, T> updateFunction) {
            acquireUpdateLock();
            try {
                T newValue = updateFunction.apply(value);
                value = newValue;
                return newValue;
            } finally {
                releaseUpdateLock();
            }
        }

        private void acquireUpdateLock() {
            // It's important that we do not block waiting for the lock while holding the project mutation lock.
            // Doing so can lead to deadlocks.

            assertCanMutate();

            if (lock.tryLock()) {
                // Update lock was not contended, can keep holding the project locks
                return;
            }

            // Another thread holds the update lock, release the project locks and wait for the other thread to finish the update
            projectLeaseRegistry.blocking(lock::lock);
        }

        private void assertCanMutate() {
            if (!owner.hasMutableState()) {
                throw new IllegalStateException("Current thread does not hold the state lock for " + owner);
            }
        }

        private void releaseUpdateLock() {
            lock.unlock();
        }
    }
}
