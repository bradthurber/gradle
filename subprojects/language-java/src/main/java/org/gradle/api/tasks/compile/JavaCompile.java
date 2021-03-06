/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.tasks.compile;

import org.gradle.api.AntBuilder;
import org.gradle.api.Incubating;
import org.gradle.api.internal.changedetection.changes.IncrementalTaskInputsInternal;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.platform.jvm.JvmPlatform;
import org.gradle.api.internal.tasks.compile.CleaningJavaCompiler;
import org.gradle.api.internal.tasks.compile.DefaultJavaCompileSpec;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.internal.tasks.compile.incremental.IncrementalCompilerFactory;
import org.gradle.api.internal.tasks.compile.incremental.analyzer.ClassAnalysisCache;
import org.gradle.api.internal.tasks.compile.incremental.cache.*;
import org.gradle.api.internal.tasks.compile.incremental.deps.LocalClassSetAnalysisStore;
import org.gradle.api.internal.tasks.compile.incremental.jar.JarSnapshotCache;
import org.gradle.api.internal.tasks.compile.incremental.jar.LocalJarClasspathSnapshotStore;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.incremental.IncrementalTaskInputs;
import org.gradle.cache.CacheRepository;
import org.gradle.internal.Factory;
import org.gradle.jvm.internal.toolchain.JavaToolChainInternal;
import org.gradle.jvm.toolchain.JavaToolChain;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.util.SingleMessageLogger;

import javax.inject.Inject;
import java.io.File;

/**
 * Compiles Java source files.
 *
 * <pre autoTested=''>
 *     apply plugin: 'java'
 *     compileJava {
 *         //enable compilation in a separate daemon process
 *         options.fork = true
 *
 *         //enable incremental compilation
 *         options.incremental = true
 *     }
 * </pre>
 */
public class JavaCompile extends AbstractCompile {
    private File dependencyCacheDir;
    private JvmPlatform platform;
    private final CompileOptions compileOptions = new CompileOptions();

    /**
     * Returns the tool chain that will be used to compile the Java source.
     *
     * @return The tool chain.
     */
    @Incubating @Inject
    public JavaToolChain getToolChain() {
        // Implementation is generated
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the tool chain that should be used to compile the Java source.
     *
     * @param toolChain The tool chain.
     */
    @Incubating
    public void setToolChain(JavaToolChain toolChain) {
        // Implementation is generated
        throw new UnsupportedOperationException();
    }

    @Incubating
    public JvmPlatform getTargetPlatform() {
        return platform;
    }

    public void setTargetPlatform(JvmPlatform platform) {
        this.platform = platform;
    }

    @TaskAction
    protected void compile(IncrementalTaskInputs inputs) {
        if (!compileOptions.isIncremental()) {
            compile();
            return;
        }

        SingleMessageLogger.incubatingFeatureUsed("Incremental java compilation");

        DefaultJavaCompileSpec spec = createSpec();
        final CacheRepository repository1 = getCacheRepository();
        final JavaCompile javaCompile1 = this;
        final GeneralCompileCaches generalCaches1 = getGeneralCompileCaches();
        CompileCaches compileCaches = new CompileCaches() {
            private final CacheRepository repository = repository1;
            private final JavaCompile javaCompile = javaCompile1;
            private final GeneralCompileCaches generalCaches = generalCaches1;

            public ClassAnalysisCache getClassAnalysisCache() {
                return generalCaches.getClassAnalysisCache();
            }

            public JarSnapshotCache getJarSnapshotCache() {
                return generalCaches.getJarSnapshotCache();
            }

            public LocalJarClasspathSnapshotStore getLocalJarClasspathSnapshotStore() {
                return new LocalJarClasspathSnapshotStore(repository, javaCompile);
            }

            public LocalClassSetAnalysisStore getLocalClassSetAnalysisStore() {
                return new LocalClassSetAnalysisStore(repository, javaCompile);
            }
        };
        IncrementalCompilerFactory factory = new IncrementalCompilerFactory(
                (FileOperations) getProject(), getPath(), createCompiler(spec), source, compileCaches, (IncrementalTaskInputsInternal) inputs);
        Compiler<JavaCompileSpec> compiler = factory.createCompiler();
        performCompilation(spec, compiler);
    }

    @Inject protected GeneralCompileCaches getGeneralCompileCaches() {
        throw new UnsupportedOperationException();
    }
    @Inject protected CacheRepository getCacheRepository() {
        throw new UnsupportedOperationException();
    }

    protected void compile() {
        DefaultJavaCompileSpec spec = createSpec();
        performCompilation(spec, createCompiler(spec));
    }

    @Inject
    protected Factory<AntBuilder> getAntBuilderFactory() {
        throw new UnsupportedOperationException();
    }

    private CleaningJavaCompiler createCompiler(JavaCompileSpec spec) {
        Compiler<JavaCompileSpec> javaCompiler = ((JavaToolChainInternal) getToolChain()).newCompiler(spec);
        return new CleaningJavaCompiler(javaCompiler, getAntBuilderFactory(), getOutputs());
    }

    private void performCompilation(JavaCompileSpec spec, Compiler<JavaCompileSpec> compiler) {
        WorkResult result = compiler.execute(spec);
        setDidWork(result.getDidWork());
    }

    private DefaultJavaCompileSpec createSpec() {
        DefaultJavaCompileSpec spec = new DefaultJavaCompileSpec();
        spec.setSource(getSource());
        spec.setDestinationDir(getDestinationDir());
        spec.setWorkingDir(getProject().getProjectDir());
        spec.setTempDir(getTemporaryDir());
        spec.setClasspath(getClasspath());
        spec.setDependencyCacheDir(getDependencyCacheDir());
        final String targetCompatibility;
        final String sourceCompatibility;
        //TODO: freekh This is not right I know. If I do not do this though, we will have to change the way JavaCompile is used from the end-user perspective. Or, we can inject a platform (so a platform is never null). I am uncertain about what we want to inject or not. A platform is something the user decides which is why it felt wrong to inject it, asked around but Daz is the one that should be able to answer that question. In any case the targetCompatibility must be possible to use?
        if (getTargetPlatform() != null) {
            targetCompatibility = getTargetPlatform().getTargetCompatibility().toString();
            if (getSourceCompatibility() != null) { //do not overwrite source compatibility before we find a way to support it
                sourceCompatibility = getSourceCompatibility();
            } else {
                sourceCompatibility = targetCompatibility;
            }
        } else {
            targetCompatibility = getTargetCompatibility();
            sourceCompatibility = getSourceCompatibility();
        }

        spec.setTargetCompatibility(targetCompatibility);
        spec.setSourceCompatibility(sourceCompatibility);
        spec.setCompileOptions(compileOptions);
        return spec;
    }

    @OutputDirectory
    public File getDependencyCacheDir() {
        return dependencyCacheDir;
    }

    public void setDependencyCacheDir(File dependencyCacheDir) {
        this.dependencyCacheDir = dependencyCacheDir;
    }

    /**
     * Returns the compilation options.
     *
     * @return The compilation options.
     */
    @Nested
    public CompileOptions getOptions() {
        return compileOptions;
    }
}
