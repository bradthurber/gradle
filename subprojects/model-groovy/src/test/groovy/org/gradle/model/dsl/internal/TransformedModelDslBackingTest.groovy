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

package org.gradle.model.dsl.internal

import org.gradle.api.Transformer
import org.gradle.model.dsl.internal.inputs.RuleInputAccessBacking
import org.gradle.model.internal.core.*
import org.gradle.model.internal.core.rule.describe.SimpleModelRuleDescriptor
import org.gradle.model.internal.registry.DefaultModelRegistry
import spock.lang.Specification

class TransformedModelDslBackingTest extends Specification {

    def modelRegistry = new DefaultModelRegistry()
    Transformer<List<ModelReference<?>>, Closure<?>> referenceExtractor = Mock()
    def modelDsl = new TransformedModelDslBacking(getModelRegistry(), referenceExtractor)

    void register(String pathString, Object element) {
        def path = new ModelPath(pathString)
        def type = ModelType.of(element.class)
        modelRegistry.create(InstanceBackedModelCreator.of(ModelReference.of(path, type), new SimpleModelRuleDescriptor("register"), element))
    }

    def "can add rules via dsl"() {
        given:
        register("foo", [])
        referenceExtractor.transform(_) >> []

        when:
        modelDsl.configure("foo") { add 1 }

        then:
        modelRegistry.get(ModelPath.path("foo"), ModelType.of(List)) == [1]
    }

    def "can registers extracted references"() {
        given:
        register("foo.bar", [])
        register("value", "123")
        referenceExtractor.transform(_) >> [ModelReference.of("value", Object)]

        when:
        modelDsl.with {
            configure("foo.bar") {
                // this is effectively what it gets transformed to
                add RuleInputAccessBacking.access.$("value")
            }
        }

        then:
        modelRegistry.get(ModelPath.path("foo.bar"), ModelType.of(List)) == ["123"]
    }

}

