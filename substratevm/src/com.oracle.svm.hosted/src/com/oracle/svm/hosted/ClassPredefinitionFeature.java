/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2021, Alibaba Group Holding Limited. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted;

import static com.oracle.svm.shaded.org.objectweb.asm.Opcodes.ACC_FINAL;
import static com.oracle.svm.shaded.org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static com.oracle.svm.shaded.org.objectweb.asm.Opcodes.ACC_STATIC;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.configure.ConfigurationFile;
import com.oracle.svm.configure.PredefinedClassesConfigurationParser;
import com.oracle.svm.configure.PredefinedClassesRegistry;
import com.oracle.svm.core.configure.ConfigurationFiles;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.hub.PredefinedClassesSupport;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FeatureImpl.AfterRegistrationAccessImpl;
import com.oracle.svm.hosted.config.ConfigurationParserUtils;
import com.oracle.svm.hosted.reflect.ReflectionFeature;
import com.oracle.svm.shaded.org.objectweb.asm.ClassReader;
import com.oracle.svm.shaded.org.objectweb.asm.ClassVisitor;
import com.oracle.svm.shaded.org.objectweb.asm.ClassWriter;
import com.oracle.svm.shaded.org.objectweb.asm.FieldVisitor;
import com.oracle.svm.shaded.org.objectweb.asm.MethodVisitor;
import com.oracle.svm.shaded.org.objectweb.asm.Opcodes;

import jdk.graal.compiler.java.LambdaUtils;
import jdk.graal.compiler.util.Digest;

@AutomaticallyRegisteredFeature
public class ClassPredefinitionFeature implements InternalFeature {
    private final Map<String, PredefinedClass> nameToRecord = new HashMap<>();
    private boolean sealed = false;

    @Override
    public List<Class<? extends Feature>> getRequiredFeatures() {
        /*
         * We are registering all predefined lambda classes for reflection. If the reflection
         * feature is not executed before this feature, class RuntimeReflectionSupport won't be
         * present in the ImageSingletons.
         */
        return List.of(ReflectionFeature.class);
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess arg) {
        ImageSingletons.add(PredefinedClassesSupport.class, new PredefinedClassesSupport());

        /*
         * NOTE: loading the class predefinition configuration should be done as early as possible
         * so that their classes are already known for other configuration (reflection, proxies).
         */
        AfterRegistrationAccessImpl access = (AfterRegistrationAccessImpl) arg;
        PredefinedClassesRegistry registry = new PredefinedClassesRegistryImpl();
        ImageSingletons.add(PredefinedClassesRegistry.class, registry);
        PredefinedClassesConfigurationParser parser = new PredefinedClassesConfigurationParser(registry, ConfigurationFiles.Options.getConfigurationParserOptions());
        ConfigurationParserUtils.parseAndRegisterConfigurations(parser, access.getImageClassLoader(), "class predefinition",
                        ConfigurationFiles.Options.PredefinedClassesConfigurationFiles, ConfigurationFiles.Options.PredefinedClassesConfigurationResources,
                        ConfigurationFile.PREDEFINED_CLASSES_NAME.getFileName());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        FeatureImpl.BeforeAnalysisAccessImpl impl = (FeatureImpl.BeforeAnalysisAccessImpl) access;
        PredefinedClassesSupport support = ImageSingletons.lookup(PredefinedClassesSupport.class);
        support.setRegistrationValidator(clazz -> {
            Optional<AnalysisType> analysisType = impl.getMetaAccess().optionalLookupJavaType(clazz);
            analysisType.map(impl.getHostVM()::dynamicHub).ifPresent(
                            hub -> VMError.guarantee(hub.isLoaded(), "Classes that should be predefined must not have a class loader."));
        });

        sealed = true;

        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        nameToRecord.forEach((name, record) -> {
            if (record.definedClass != null) {
                /*
                 * Initialization of a class at image build time can have unintended side effects
                 * when the class is not supposed to be loaded yet, so we generally disallow it.
                 *
                 * Exempt are annotations, which must be initialized at image build time, and enums,
                 * which are initialized during the image build if they are used in an annotation.
                 */
                if (!record.definedClass.isAnnotation() && !record.definedClass.isEnum()) {
                    RuntimeClassInitialization.initializeAtRunTime(record.definedClass);
                }
            } else if (record.pendingSubtypes != null) {
                StringBuilder msg = new StringBuilder();
                msg.append("Type ").append(name).append(" is neither on the classpath nor predefined and prevents the predefinition of these subtypes (and potentially their subtypes): ");
                boolean first = true;
                for (PredefinedClass superRecord : record.pendingSubtypes) {
                    msg.append(first ? "" : ", ").append(superRecord.name);
                    first = false;
                }
                errors.add(msg.toString());
            } else if (record.data == null) {
                skipped.add(record.name);
            }
        });
        if (!skipped.isEmpty()) {
            int limit = 10;
            String names = skipped.stream().limit(limit).collect(Collectors.joining(", "));
            if (skipped.size() > limit) {
                names += ", ...";
            }
            System.out.printf("Skipped %d predefined class(es) because the classpath already contains a class with the same name: %s%n", skipped.size(), names);
        }
        if (!errors.isEmpty()) {
            throw UserError.abort(errors);
        }
    }

    private final class PredefinedClassesRegistryImpl implements PredefinedClassesRegistry {
        @Override
        public void add(String nameInfo, String providedHash, URI baseUri) {
            if (!PredefinedClassesSupport.supportsBytecodes()) {
                throw UserError.abort("Cannot predefine class with hash %s from %s because class predefinition is disabled. Enable this feature using option %s.",
                                providedHash, baseUri, PredefinedClassesSupport.ENABLE_BYTECODES_OPTION);
            }
            UserError.guarantee(!sealed, "Too late to add predefined classes. Registration must happen in a Feature before the analysis has started.");

            VMError.guarantee(baseUri != null, "Cannot prepare class with hash %s for predefinition because its location is unknown", providedHash);
            try {
                byte[] data;
                try (InputStream in = PredefinedClassesConfigurationParser.openClassdataStream(baseUri, providedHash)) {
                    data = in.readAllBytes();
                }

                // Compute our own hash code, the files could have been messed with.
                String hash = Digest.digest(data);

                if (LambdaUtils.isLambdaClassName(nameInfo)) {
                    /**
                     * We have to cut off calculated hash since lambda's name in the bytecode does
                     * not contain it. Also, the name in the bytecode must match the name we load
                     * class with, so we have to update it in the bytecode.
                     */
                    data = PredefinedClassesSupport.changeLambdaClassName(data, nameInfo.substring(0, nameInfo.indexOf(LambdaUtils.LAMBDA_CLASS_NAME_SUBSTRING) +
                                    LambdaUtils.LAMBDA_CLASS_NAME_SUBSTRING.length()), nameInfo);
                    data = makeLambdaInstanceFieldAndConstructorPublic(data);
                }

                /*
                 * Compute a "canonical hash" that does not incorporate debug information such as
                 * source file names, line numbers, local variable names, etc.
                 */
                ClassReader reader = new ClassReader(data);
                ClassWriter writer = new ClassWriter(0);
                reader.accept(writer, ClassReader.SKIP_DEBUG);
                byte[] canonicalData = writer.toByteArray();
                String canonicalHash = Digest.digest(canonicalData);

                String className = transformClassName(reader.getClassName());
                PredefinedClass record = nameToRecord.computeIfAbsent(className, PredefinedClass::new);
                if (record.canonicalHash != null) {
                    if (!canonicalHash.equals(record.canonicalHash)) {
                        throw UserError.abort("More than one predefined class with the same name provided: " + className);
                    }
                    if (record.definedClass != null) {
                        PredefinedClassesSupport.registerClass(hash, record.definedClass);
                    } else {
                        record.addAliasHash(hash);
                    }
                    return;
                }
                record.canonicalHash = canonicalHash;
                record.data = data;
                record.addAliasHash(hash);

                // A class cannot be defined unless its superclass and all interfaces are loaded
                boolean pendingSupertypes = false;
                String superclassName = transformClassName(reader.getSuperName());
                if (NativeImageSystemClassLoader.singleton().forNameOrNull(superclassName, false) == null) {
                    addPendingSupertype(record, superclassName);
                    pendingSupertypes = true;
                }
                for (String intf : reader.getInterfaces()) {
                    String interfaceName = transformClassName(intf);
                    if (NativeImageSystemClassLoader.singleton().forNameOrNull(interfaceName, false) == null) {
                        addPendingSupertype(record, interfaceName);
                        pendingSupertypes = true;
                    }
                }

                if (!pendingSupertypes) {
                    defineClass(record);
                }
            } catch (IOException t) {
                throw UserError.abort(t, "Failed to prepare class with hash %s from %s for predefinition", providedHash, baseUri);
            }
        }

        private void addPendingSupertype(PredefinedClass record, String superName) {
            PredefinedClass superRecord = nameToRecord.computeIfAbsent(superName, PredefinedClass::new);
            assert superRecord.definedClass == null : "Must have been found with forName above";
            superRecord.addPendingSubtype(record);
            record.addPendingSupertype(superRecord);
        }

        private void defineClass(PredefinedClass record) {
            if (NativeImageSystemClassLoader.singleton().forNameOrNull(record.name, false) == null) {
                record.definedClass = NativeImageSystemClassLoader.singleton().predefineClass(record.name, record.data, 0, record.data.length);

                if (record.aliasHashes != null) {
                    /*
                     * Note that we don't register the class with the canonical hash because we only
                     * use it to unify different representations of the class (to some extent) and
                     * it is synthetic or equal to another hash anyway.
                     */
                    for (String hash : record.aliasHashes) {
                        PredefinedClassesSupport.registerClass(hash, record.definedClass);
                    }
                }
            }
            // else: will be reported as skipped

            record.data = null;
            record.aliasHashes = null;

            if (record.pendingSubtypes != null) {
                for (PredefinedClass subtype : record.pendingSubtypes) {
                    boolean removed = subtype.pendingSupertypes.remove(record);
                    assert removed : "must have been in list";
                    if (subtype.pendingSupertypes.isEmpty()) {
                        record.pendingSupertypes = null;
                        defineClass(subtype);
                    }
                }
                record.pendingSubtypes = null;
            }
        }

        private String transformClassName(String className) {
            return className.replace('/', '.');
        }
    }

    static final class PredefinedClass {
        final String name;

        /** If already loaded, the {@link Class} object, otherwise null. */
        Class<?> definedClass;

        /** If not yet loaded, classes which need this class to be loaded first, otherwise null. */
        List<PredefinedClass> pendingSubtypes;

        /** If pending, classes which need to be loaded before this class, otherwise null. */
        List<PredefinedClass> pendingSupertypes;

        /** If loaded or pending load, the canonical hash of this class, otherwise null. */
        String canonicalHash;

        /** If pending, the class data for this class, otherwise null. */
        byte[] data;

        /** If pending, hashes that alias to this class, otherwise null. */
        List<String> aliasHashes;

        PredefinedClass(String name) {
            this.name = name;
        }

        void addAliasHash(String hash) {
            assert definedClass == null : "must not already be loaded";
            if (aliasHashes == null) {
                aliasHashes = new ArrayList<>();
            }
            aliasHashes.add(hash);
        }

        void addPendingSubtype(PredefinedClass record) {
            assert definedClass == null : "must not already be loaded";
            if (pendingSubtypes == null) {
                pendingSubtypes = new ArrayList<>();
            }
            pendingSubtypes.add(record);
        }

        public void addPendingSupertype(PredefinedClass record) {
            assert definedClass == null : "must not already be loaded";
            if (pendingSupertypes == null) {
                pendingSupertypes = new ArrayList<>();
            }
            pendingSupertypes.add(record);
        }
    }

    /**
     * We need to make field LAMBDA_INSTANCE$ and lambda class constructor public in order to be
     * able to predefine lambda classes so that {@code java.lang.invoke.InnerClassLambdaMetafactory}
     * can access them.
     */
    private static byte[] makeLambdaInstanceFieldAndConstructorPublic(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (name.equals("LAMBDA_INSTANCE$")) {
                    return super.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, name, descriptor, signature, value);
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
                if (name.equals("<init>")) {
                    return super.visitMethod(ACC_PUBLIC, name, descriptor, signature, exceptions);
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        return cw.toByteArray();
    }
}
