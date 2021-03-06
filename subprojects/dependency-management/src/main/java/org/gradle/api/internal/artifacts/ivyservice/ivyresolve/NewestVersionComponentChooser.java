/*
 * Copyright 2013 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.ivyresolve;

import com.google.common.collect.Lists;
import org.gradle.api.RuleAction;
import org.gradle.api.artifacts.ComponentSelection;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.ComponentSelectionInternal;
import org.gradle.api.internal.artifacts.ComponentSelectionRulesInternal;
import org.gradle.api.internal.artifacts.DefaultComponentSelection;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.LatestStrategy;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionMatcher;
import org.gradle.internal.Factory;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetaData;
import org.gradle.internal.component.external.model.MutableModuleComponentResolveMetaData;
import org.gradle.internal.component.model.ComponentResolveMetaData;
import org.gradle.internal.component.model.DependencyMetaData;
import org.gradle.internal.resolve.result.DefaultBuildableModuleComponentMetaDataResolveResult;
import org.gradle.internal.resolve.result.ModuleVersionListing;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

class NewestVersionComponentChooser implements ComponentChooser {
    private final ComponentSelectionRulesProcessor rulesProcessor = new ComponentSelectionRulesProcessor();
    private final VersionMatcher versionMatcher;
    private final LatestStrategy latestStrategy;
    private final ComponentSelectionRulesInternal componentSelectionRules;

    NewestVersionComponentChooser(LatestStrategy latestStrategy, VersionMatcher versionMatcher, ComponentSelectionRulesInternal componentSelectionRules) {
        this.latestStrategy = latestStrategy;
        this.versionMatcher = versionMatcher;
        this.componentSelectionRules = componentSelectionRules;
    }

    public boolean canSelectMultipleComponents(ModuleVersionSelector selector) {
        return versionMatcher.isDynamic(selector.getVersion());
    }

    public ComponentResolveMetaData choose(ComponentResolveMetaData one, ComponentResolveMetaData two) {
        if (one == null || two == null) {
            return two == null ? one : two;
        }

        int comparison = latestStrategy.compare(new VersionInfo(one.getId().getVersion()), new VersionInfo(two.getId().getVersion()));

        if (comparison == 0) {
            if (isGeneratedModuleDescriptor(one) && !isGeneratedModuleDescriptor(two)) {
                return two;
            }
            return one;
        }

        return comparison < 0 ? two : one;
    }

    private boolean isGeneratedModuleDescriptor(ComponentResolveMetaData componentResolveMetaData) {
        return componentResolveMetaData.isGenerated();
    }

    public ModuleComponentIdentifier choose(ModuleVersionListing versions, DependencyMetaData dependency, ModuleComponentRepositoryAccess moduleAccess) {
        ModuleVersionSelector requested = dependency.getRequested();
        List<RuleAction<? super ComponentSelection>> rules = buildRulesForSelector(requested);

        for (Versioned candidate : sortLatestFirst(versions)) {
            ModuleComponentIdentifier candidateIdentifier = DefaultModuleComponentIdentifier.newId(requested.getGroup(), requested.getName(), candidate.getVersion());

            if (isRejectedByRules(candidateIdentifier, rules, new MetaDataSupplier(dependency, candidateIdentifier, moduleAccess))) {
                continue;
            }

            return candidateIdentifier;
        }
        return null;
    }

    private List<RuleAction<? super ComponentSelection>> buildRulesForSelector(ModuleVersionSelector requested) {
        List<RuleAction<? super ComponentSelection>> rules = Lists.newArrayList();
        if (versionMatcher.needModuleMetadata(requested.getVersion())) {
            rules.add(new MetadataVersionMatchingRule(requested.getVersion()));
        } else {
            rules.add(new SimpleVersionMatchingRule(requested.getVersion()));
        }
        rules.addAll(componentSelectionRules.getRules());
        return rules;
    }

    public boolean isRejectedByRules(ModuleComponentIdentifier candidateIdentifier, Factory<? extends MutableModuleComponentResolveMetaData> metaDataSupplier) {
        return isRejectedByRules(candidateIdentifier, componentSelectionRules.getRules(), metaDataSupplier);
    }

    private boolean isRejectedByRules(ModuleComponentIdentifier candidateIdentifier, Collection<RuleAction<? super ComponentSelection>> rules, Factory<? extends MutableModuleComponentResolveMetaData> metaDataSupplier) {
        ComponentSelectionInternal selection = new DefaultComponentSelection(candidateIdentifier);
        rulesProcessor.apply(selection, rules, metaDataSupplier);
        return selection.isRejected();
    }

    private List<Versioned> sortLatestFirst(ModuleVersionListing listing) {
        List<Versioned> sorted = latestStrategy.sort(listing.getVersions());
        Collections.reverse(sorted);
        return sorted;
    }

    private static class MetaDataSupplier implements Factory<MutableModuleComponentResolveMetaData> {
        private final DependencyMetaData dependency;
        private final ModuleComponentIdentifier id;
        private final ModuleComponentRepositoryAccess repository;

        private MetaDataSupplier(DependencyMetaData dependency, ModuleComponentIdentifier id, ModuleComponentRepositoryAccess repository) {
            this.dependency = dependency;
            this.id = id;
            this.repository = repository;
        }

        public MutableModuleComponentResolveMetaData create() {
            DefaultBuildableModuleComponentMetaDataResolveResult result = new DefaultBuildableModuleComponentMetaDataResolveResult();
            repository.resolveComponentMetaData(dependency.withRequestedVersion(id.getVersion()), id, result);
            return result.getMetaData();
        }
    }

    private final class SimpleVersionMatchingRule implements RuleAction<ComponentSelection> {
        private final String requestedVersion;

        private SimpleVersionMatchingRule(String requestedVersion) {
            this.requestedVersion = requestedVersion;
        }

        public List<Class<?>> getInputTypes() {
            return Collections.emptyList();
        }

        public void execute(ComponentSelection selection, List<?> inputs) {
            if (!versionMatcher.accept(requestedVersion, selection.getCandidate().getVersion())) {
                selection.reject("Incorrect version");
            }
        }
    }

    private final class MetadataVersionMatchingRule implements RuleAction<ComponentSelection> {
        private final String requestedVersion;

        private MetadataVersionMatchingRule(String requestedVersion) {
            this.requestedVersion = requestedVersion;
        }

        public List<Class<?>> getInputTypes() {
            return Collections.<Class<?>>singletonList(ModuleComponentResolveMetaData.class);
        }

        public void execute(ComponentSelection selection, List<?> inputs) {
            ModuleComponentResolveMetaData metadata = (ModuleComponentResolveMetaData) inputs.get(0);
            if (!versionMatcher.accept(requestedVersion, metadata)) {
                selection.reject("Does not match requested version or status");
            }
        }
    }
}
