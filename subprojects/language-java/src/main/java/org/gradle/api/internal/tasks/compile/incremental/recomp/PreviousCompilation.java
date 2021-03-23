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

package org.gradle.api.internal.tasks.compile.incremental.recomp;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassSetAnalysis;
import org.gradle.api.internal.tasks.compile.incremental.deps.ClassSetAnalysisData;
import org.gradle.api.internal.tasks.compile.incremental.deps.DependentsSet;

import javax.annotation.Nullable;
import java.util.Set;

public class PreviousCompilation {
    private final PreviousCompilationData data;
    private final ClassSetAnalysis classAnalysis;

    public PreviousCompilation(PreviousCompilationData data, ClassSetAnalysisData classAnalysis) {
        this.data = data;
        this.classAnalysis = new ClassSetAnalysis(classAnalysis, data.getAnnotationProcessingData());
    }

    @Nullable
    public ClassSetAnalysis getClasspath() {
        return new ClassSetAnalysis(data.getClasspathSnapshot());
    }

    public DependentsSet getDependents(ClassSetAnalysis.ClassSetDiff diff) {
        if (diff.getDependents().isDependencyToAll()) {
            return diff.getDependents();
        }
        return classAnalysis.getRelevantDependents(diff.getDependents().getAllDependentClasses(), diff.getConstants());
    }

    public DependentsSet getDependents(String className, IntSet newConstants) {
        IntSet constants = new IntOpenHashSet(classAnalysis.getConstants(className));
        constants.removeAll(newConstants);
        return classAnalysis.getRelevantDependents(className, constants);
    }

    public Set<String> getTypesToReprocess() {
        return classAnalysis.getTypesToReprocess();
    }
}
