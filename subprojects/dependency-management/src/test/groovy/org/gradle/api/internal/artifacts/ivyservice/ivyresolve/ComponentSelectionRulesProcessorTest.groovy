/*
 * Copyright 2014 the original author or authors.
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



package org.gradle.api.internal.artifacts.ivyservice.ivyresolve

import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.gradle.api.InvalidUserCodeException
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.ivy.IvyModuleDescriptor
import org.gradle.api.internal.ClosureBackedRuleAction
import org.gradle.api.internal.DelegatingTargetedRuleAction
import org.gradle.api.internal.artifacts.ComponentSelectionInternal
import org.gradle.api.internal.artifacts.DefaultComponentSelection
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.ivyservice.resolutionstrategy.DefaultComponentSelectionRules
import org.gradle.internal.Factory
import org.gradle.internal.component.external.model.DefaultIvyModuleResolveMetaData
import org.gradle.internal.component.external.model.DefaultMavenModuleResolveMetaData
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.ModuleComponentResolveMetaData
import spock.lang.Specification

class ComponentSelectionRulesProcessorTest extends Specification {
    def processor = new ComponentSelectionRulesProcessor()
    def rules = []
    ComponentSelectionInternal componentSelection

    def setup() {
        def componentIdentifier = DefaultModuleComponentIdentifier.newId("group", "module", "version")
        componentSelection = new DefaultComponentSelection(componentIdentifier)
    }

    def "all non-rejecting rules are evaluated" () {
        def moduleAccess = Stub(Factory) {
            create() >> {
                return new DefaultIvyModuleResolveMetaData(Stub(ModuleDescriptor))
            }
        }
        def closureCalled = []
        when:
        rule { ComponentSelection cs -> closureCalled << 0 }
        rule { ComponentSelection cs, ComponentMetadata cm -> closureCalled << 1 }
        rule { ComponentSelection cs, IvyModuleDescriptor imd -> closureCalled << 2 }
        rule { ComponentSelection cs, ModuleComponentResolveMetaData mvm -> closureCalled << 3 }
        rule { ComponentSelection cs, IvyModuleDescriptor imd, ComponentMetadata cm -> closureCalled << 4 }
        rule { ComponentSelection cs, ComponentMetadata cm, IvyModuleDescriptor imd -> closureCalled << 5 }
        rule { ComponentSelection cs, ComponentMetadata cm, ModuleComponentResolveMetaData mvm -> closureCalled << 6 }
        rule { ComponentSelection cs, ModuleComponentResolveMetaData mvm, ComponentMetadata cm -> closureCalled << 7 }
        targetedRule("group", "module") { ComponentSelection cs -> closureCalled << 8 }

        and:
        apply(moduleAccess)

        then:
        !componentSelection.rejected
        closureCalled == [0, 8, 1, 2, 3, 4, 5, 6, 7]
    }

    def "short-circuits evaluate when rule rejects candidate" () {
        def moduleAccess = Mock(Factory)
        def closuresCalled = []

        when:
        rule { ComponentSelection cs -> closuresCalled << 0 }
        rule { ComponentSelection cs -> cs.reject("rejecting") }
        rule { ComponentSelection cs -> closuresCalled << 1 }

        and:
        apply(moduleAccess)

        then:
        componentSelection.rejected
        componentSelection.rejectionReason == "rejecting"
        closuresCalled == [0]

        and:
        0 * moduleAccess._
    }

    def "prefers non-metadata rules over rules requiring metadata"() {
        def moduleAccess = Mock(Factory)
        def closuresCalled = []

        when:
        rule { ComponentSelection cs -> closuresCalled << 0 }
        targetedRule("group", "module") { ComponentSelection cs -> closuresCalled << 1 }
        rule { ComponentSelection cs, ComponentMetadata cm -> closuresCalled << 2 }
        rule { ComponentSelection cs, IvyModuleDescriptor imd -> closuresCalled << 3}
        rule { ComponentSelection cs, ModuleComponentResolveMetaData mvm -> closuresCalled << 4}
        targetedRule("group", "module") { ComponentSelection cs, ModuleComponentResolveMetaData mvm -> closuresCalled << 5}
        rule { ComponentSelection cs -> closuresCalled << 6 }
        rule { ComponentSelection cs -> cs.reject("rejected") }

        and:
        apply(moduleAccess)

        then:
        componentSelection.rejected
        componentSelection.rejectionReason == "rejected"

        and:
        closuresCalled == [0, 1, 6]

        and:
        0 * moduleAccess._
    }

    def "metadata is not requested for rules that don't require it"() {
        def moduleAccess = Mock(Factory)
        def closuresCalled = []

        when:
        rule { ComponentSelection cs -> closuresCalled << 0 }
        targetedRule("group", "module") { ComponentSelection cs -> closuresCalled << 1 }

        apply(moduleAccess)

        then:
        !componentSelection.rejected
        closuresCalled == [0, 1]

        and:
        0 * moduleAccess._
    }

    def "metadata is not requested for non-targeted components"() {
        def moduleAccess = Mock(Factory)
        def closuresCalled = []

        when:
        targetedRule("group", "module1") { ComponentSelection cs, IvyModuleDescriptor ivm -> closuresCalled << 0 }
        targetedRule("group1", "module") { ComponentSelection cs, ComponentMetadata cm -> closuresCalled << 1 }
        targetedRule("group1", "module") { ComponentSelection cs, IvyModuleDescriptor ivm, ComponentMetadata cm -> closuresCalled << 2 }

        apply(moduleAccess)

        then:
        !componentSelection.rejected
        closuresCalled == []

        and:
        0 * moduleAccess._
    }

    def "produces sensible error when rule action throws exception" () {
        def moduleAccess = Mock(Factory)

        when:
        rule { ComponentSelection selection -> throw new Exception("From test")}
        apply(moduleAccess)

        then:
        def e = thrown(InvalidUserCodeException)
        e.message == "Could not apply component selection rule with all()."
        e.cause.message == "From test"
    }

    def "rule expecting IvyMetadataDescriptor does not get called when not an ivy component" () {
        def moduleAccess = Stub(Factory) {
            create() >> {
                return new DefaultMavenModuleResolveMetaData(Stub(ModuleDescriptor), "bundle", false)
            }
        }
        def closuresCalled = []

        when:
        rule { ComponentSelection cs, IvyModuleDescriptor ivm, ComponentMetadata cm -> closuresCalled << 0 }
        targetedRule("group", "module") { ComponentSelection cs, IvyModuleDescriptor ivm, ComponentMetadata cm -> closuresCalled << 1 }
        apply(moduleAccess)

        then:
        closuresCalled == []
    }

    def "only matching targeted rules get called" () {
        def moduleAccess = Stub(Factory) {
            create() >> {
                return new DefaultIvyModuleResolveMetaData(Stub(ModuleDescriptor))
            }
        }
        def closuresCalled = []
        when:
        targetedRule("group", "module") { ComponentSelection cs -> closuresCalled << 0 }
        targetedRule("group1", "module") { ComponentSelection cs -> closuresCalled << 1 }
        targetedRule("group", "module") { ComponentSelection cs, ComponentMetadata cm -> closuresCalled << 2 }
        targetedRule("group", "module1") { ComponentSelection cs, ComponentMetadata cm -> closuresCalled << 3 }
        targetedRule("group", "module") { ComponentSelection cs, IvyModuleDescriptor imd -> closuresCalled << 4 }
        targetedRule("group", "module") { ComponentSelection cs, ModuleComponentResolveMetaData mvm -> closuresCalled << 5 }
        targetedRule("group", "module") { ComponentSelection cs, IvyModuleDescriptor imd, ComponentMetadata cm -> closuresCalled << 6 }
        targetedRule("group1", "module") { ComponentSelection cs, IvyModuleDescriptor imd, ComponentMetadata cm -> closuresCalled << 7 }
        targetedRule("group", "module") { ComponentSelection cs, ComponentMetadata cm, IvyModuleDescriptor imd -> closuresCalled << 8 }
        targetedRule("group", "module") { ComponentSelection cs, ComponentMetadata cm, ModuleComponentResolveMetaData mvm -> closuresCalled << 9 }
        targetedRule("group", "module") { ComponentSelection cs, ModuleComponentResolveMetaData mvm, ComponentMetadata cm -> closuresCalled << 10 }
        targetedRule("group", "module1") { ComponentSelection cs, ModuleComponentResolveMetaData mvm, ComponentMetadata cm -> closuresCalled << 11 }

        and:
        apply(moduleAccess)

        then:
        !componentSelection.rejected
        closuresCalled == [0, 2, 4, 5, 6, 8, 9, 10]
    }

    def rule(Closure<?> closure) {
        rules << new ClosureBackedRuleAction<ComponentSelection>(ComponentSelection, closure)
    }

    def targetedRule(String group, String module, Closure<?> closure) {
        rules << new DelegatingTargetedRuleAction(
                new DefaultComponentSelectionRules.ComponentSelectionMatchingSpec(DefaultModuleIdentifier.newId(group, module)),
                new ClosureBackedRuleAction<ComponentSelection>(ComponentSelection, closure)
        )
    }

    def apply(def moduleAccess) {
        processor.apply(componentSelection, rules, moduleAccess)
    }
}
