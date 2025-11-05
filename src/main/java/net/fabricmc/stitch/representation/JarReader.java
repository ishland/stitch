/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
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

package net.fabricmc.stitch.representation;

import net.fabricmc.stitch.util.Pair;
import net.fabricmc.stitch.util.StitchUtil;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarInputStream;

public class JarReader {
//    public static class Builder {
//        private final JarReader reader;
//
//        private Builder(JarReader reader) {
//            this.reader = reader;
//        }
//
//        public static Builder create(JarRootEntry jar) {
//            return new Builder(new JarReader(jar));
//        }
//
//        public Builder joinMethodEntries(boolean value) {
//            reader.joinMethodEntries = value;
//            return this;
//        }
//
//        public Builder withRemapper(Remapper remapper) {
//            reader.remapper = remapper;
//            return this;
//        }
//
//        public JarReader build() {
//            return reader;
//        }
//    }

    private final JarRootEntry jar;
    private final File classpathDir;
    private final LazyClasspathStorage lazyClasspathStorage;
    private boolean joinMethodEntries = true;
    private Remapper remapper;
    private final Set<Pair<String, String>> functionalInterfaceMethods = new HashSet<>();

    public JarReader(JarRootEntry jar, File classpathDir) {
        this.jar = jar;
        this.classpathDir = classpathDir;
        this.lazyClasspathStorage = new LazyClasspathStorage(this.jar, this.classpathDir);
    }

    private class VisitorClass extends ClassVisitor {
        private JarClassEntry entry;
        private boolean isNonObfuscated;
        private final ClassStorage backing;
        private List<JarRecordComponentEntry> orderedRecordComponent;
        private int readingRecordComponentIndex = -1;

        public VisitorClass(int api, ClassVisitor classVisitor, boolean isNonObfuscated, ClassStorage backing) {
            super(api, classVisitor);
            this.isNonObfuscated = isNonObfuscated;
            this.backing = backing;
        }

        @Override
        public void visit(final int version, final int access, final String name, final String signature,
                          final String superName, final String[] interfaces) {
            this.entry = backing.getClass(name, true);
            this.entry.populate(access, signature, superName, interfaces, this.isNonObfuscated);

            this.orderedRecordComponent = new ArrayList<>();
            this.readingRecordComponentIndex = -3; // toString, hashCode, equals

            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public FieldVisitor visitField(final int access, final String name, final String descriptor,
                                       final String signature, final Object value) {
            JarFieldEntry field = new JarFieldEntry(access, name, descriptor, signature);
            this.entry.fields.put(field.getKey(), field);
            field.recordComponent = this.entry.getRecordComponent(field.getKey());
            field.isNonObfuscated = this.isNonObfuscated;

            return new VisitorField(api, super.visitField(access, name, descriptor, signature, value),
                    entry, field);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                         final String signature, final String[] exceptions) {
            JarMethodEntry method = new JarMethodEntry(access, name, descriptor, signature);
            this.entry.methods.put(method.getKey(), method);
            method.isNonObfuscated = this.isNonObfuscated;

            int index;
            if (this.readingRecordComponentIndex >= 0) {
                index = this.readingRecordComponentIndex++;
            } else {
                index = -1;
            }

            // order these after index fetch
            if ("java/lang/Record".equals(this.entry.superclass)) {
                if (name.equals("toString") && descriptor.equals("()Ljava/lang/String;")) {
                    this.readingRecordComponentIndex ++;
                } else if (name.equals("equals") && descriptor.equals("(Ljava/lang/Object;)Z")) {
                    this.readingRecordComponentIndex ++;
                } else if (name.equals("hashCode") && descriptor.equals("()I")) {
                    this.readingRecordComponentIndex ++;
                }
            }

            return new VisitorMethod(api, super.visitMethod(access, name, descriptor, signature, exceptions),
                    entry, method, index, this.orderedRecordComponent);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            JarRecordComponentEntry recordComponent = new JarRecordComponentEntry(name, descriptor, signature);
            this.entry.recordComponents.put(recordComponent.getKey(), recordComponent);
            recordComponent.isNonObfuscated = this.isNonObfuscated;
            this.orderedRecordComponent.add(recordComponent);

            return new VisitorRecordComponent(api, super.visitRecordComponent(name, descriptor, signature), entry, recordComponent);
        }

        @Override
        public void visitEnd() {
            Set<JarRecordComponentEntry> recordComponentEntries = StitchUtil.newIdentityHashSet();
            recordComponentEntries.addAll(this.entry.recordComponents.values());
            for (JarMethodEntry methodEntry : this.entry.methods.values()) {
                if (methodEntry.recordComponent != null) {
                    if (!recordComponentEntries.remove(methodEntry.recordComponent)) {
                        System.out.println(String.format("Duplicate assignment for record component %s;%s", this.entry.getFullyQualifiedName(), methodEntry.recordComponent.getKey()));
                    }
                }
            }
            outer_loop:
            for (Iterator<JarRecordComponentEntry> iterator = recordComponentEntries.iterator(); iterator.hasNext(); ) {
                JarRecordComponentEntry unassignedEntry = iterator.next();
                JarFieldEntry field = this.entry.getField(unassignedEntry.getKey());
                if (field == null) {
                    System.out.println(String.format("Dangling record component %s in class %s", unassignedEntry.getKey(), this.entry.getFullyQualifiedName()));
                    continue outer_loop;
                }
                for (JarMethodEntry methodEntry : this.entry.methods.values()) {
                    if (methodEntry.recordComponent == null && methodEntry.desc.startsWith("()") && Type.getReturnType(methodEntry.desc).getDescriptor().equals(unassignedEntry.desc) &&
                            ((methodEntry.access & 0xffff) == Opcodes.ACC_PUBLIC || (methodEntry.access & 0xffff) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL)) &&
                            methodEntry.referencedSelfFields.contains(field)) {
                        System.out.println(String.format("Promoting %s;%s as record getter for %s", this.entry.getFullyQualifiedName(), methodEntry.getKey(), unassignedEntry.getKey()));
                        methodEntry.recordComponent = unassignedEntry;
                        iterator.remove();
                        continue outer_loop;
                    }
                }
            }
            if (!recordComponentEntries.isEmpty()) {
                System.out.println(String.format("Record %s still have %d unclaimed record getters", this.entry.getFullyQualifiedName(), recordComponentEntries.size()));
            }

            super.visitEnd();
        }
    }

    private class VisitorClassStageTwo extends ClassVisitor {
        private JarClassEntry entry;

        public VisitorClassStageTwo(int api, ClassVisitor classVisitor) {
            super(api, classVisitor);
        }

        @Override
        public void visit(final int version, final int access, final String name, final String signature,
                          final String superName, final String[] interfaces) {
            this.entry = jar.getClass(name, true);
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                         final String signature, final String[] exceptions) {
            JarMethodEntry method = new JarMethodEntry(access, name, descriptor, signature);
            this.entry.methods.put(method.getKey(), method);

            if ((access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) {
                return new VisitorBridge(api, access, super.visitMethod(access, name, descriptor, signature, exceptions),
                        entry, method);
            } else {
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }
    }

    private class VisitorField extends FieldVisitor {
        private final JarClassEntry classEntry;
        private final JarFieldEntry entry;

        public VisitorField(int api, FieldVisitor fieldVisitor, JarClassEntry classEntry, JarFieldEntry entry) {
            super(api, fieldVisitor);
            this.classEntry = classEntry;
            this.entry = entry;
        }
    }

    private class VisitorRecordComponent extends RecordComponentVisitor {
        private final JarClassEntry classEntry;
        private final JarRecordComponentEntry entry;

        public VisitorRecordComponent(int api, RecordComponentVisitor recordComponentVisitor, JarClassEntry classEntry, JarRecordComponentEntry entry) {
            super(api, recordComponentVisitor);
            this.classEntry = classEntry;
            this.entry = entry;
        }
    }


    private static class MethodRef {
        final String owner, name, descriptor;

        MethodRef(String owner, String name, String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    private class VisitorBridge extends VisitorMethod {
        private final boolean hasBridgeFlag;
        private final List<MethodRef> methodRefs = new ArrayList<>();

        public VisitorBridge(int api, int access, MethodVisitor methodVisitor, JarClassEntry classEntry, JarMethodEntry entry) {
            super(api, methodVisitor, classEntry, entry, -1, null);
            hasBridgeFlag = ((access & Opcodes.ACC_BRIDGE) != 0);
        }

        @Override
        public void visitMethodInsn(
                final int opcode,
                final String owner,
                final String name,
                final String descriptor,
                final boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            methodRefs.add(new MethodRef(owner, name, descriptor));
        }

        @Override
        public void visitEnd() {
            /* boolean isBridge = hasBridgeFlag;

            if (!isBridge && methodRefs.size() == 1) {
                System.out.println("Found suspicious bridge-looking method: " + classEntry.getFullyQualifiedName() + ":" + entry);
            }

            if (isBridge) {
                for (MethodRef ref : methodRefs) {
                    JarClassEntry targetClass = jar.getClass(ref.owner, true);
                    JarMethodEntry targetMethod = new JarMethodEntry(0, ref.name, ref.descriptor, null);
                    String targetKey = targetMethod.getKey();

                    targetClass.relatedMethods.computeIfAbsent(targetKey, (a) -> new HashSet<>()).add(Pair.of(classEntry, entry.getKey()));
                    classEntry.relatedMethods.computeIfAbsent(entry.getKey(), (a) -> new HashSet<>()).add(Pair.of(targetClass, targetKey));
                }
            } */
        }
    }

    private class VisitorMethod extends MethodVisitor {
        final JarClassEntry classEntry;
        final JarMethodEntry entry;
        final int readingRecordComponentIndex;
        private final List<JarRecordComponentEntry> orderedRecordComponent;

        public VisitorMethod(int api, MethodVisitor methodVisitor, JarClassEntry classEntry, JarMethodEntry entry, int readingRecordComponentIndex, List<JarRecordComponentEntry> orderedRecordComponent) {
            super(api, methodVisitor);
            this.classEntry = classEntry;
            this.entry = entry;
            this.readingRecordComponentIndex = readingRecordComponentIndex;
            this.orderedRecordComponent = orderedRecordComponent;
            this.recordMethodMatchingState = RecordMethodMatchingState.NOT_STARTED;
            if (!entry.desc.startsWith("()")) {
                this.recordMethodMatchingState = RecordMethodMatchingState.FAIL;
            }
        }

        private enum RecordMethodMatchingState {
            NOT_STARTED,
            INIT,
            ALOAD_THIS,
            GETFIELD_RECORD_COMP,
            RETURN,
            SUCCESS,
            FAIL,
            ;
        }
        RecordMethodMatchingState recordMethodMatchingState;
        JarRecordComponentEntry recordComponentEntry = null;

        private void failRecordMatching() {
            recordMethodMatchingState = RecordMethodMatchingState.FAIL;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (recordMethodMatchingState != RecordMethodMatchingState.NOT_STARTED) {
                failRecordMatching();
                return;
            }
            recordMethodMatchingState = RecordMethodMatchingState.INIT;
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            super.visitVarInsn(opcode, varIndex);
            if (recordMethodMatchingState != RecordMethodMatchingState.INIT) {
                failRecordMatching();
                return;
            }
            if (opcode != Opcodes.ALOAD || varIndex != 0) {
                failRecordMatching();
                return;
            }
            recordMethodMatchingState = RecordMethodMatchingState.ALOAD_THIS;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            if (opcode == Opcodes.GETFIELD && owner.equals(this.classEntry.fullyQualifiedName)) {
                JarFieldEntry field = this.classEntry.getField(name + descriptor);
                if (field != null) {
                    this.entry.referencedSelfFields.add(field);
                }
            }

            if (recordMethodMatchingState != RecordMethodMatchingState.ALOAD_THIS) {
                failRecordMatching();
                return;
            }
            if (opcode != Opcodes.GETFIELD || !owner.equals(this.classEntry.fullyQualifiedName)) {
                failRecordMatching();
                return;
            }
            this.recordComponentEntry = this.classEntry.getRecordComponent(name + descriptor);
            if (this.recordComponentEntry == null) {
                failRecordMatching();
                return;
            }
            this.recordMethodMatchingState = RecordMethodMatchingState.RETURN;
        }

        @Override
        public void visitInsn(int opcode) {
            super.visitInsn(opcode);
            if (recordMethodMatchingState != RecordMethodMatchingState.RETURN) {
                failRecordMatching();
                return;
            }
            if (opcode != Opcodes.ARETURN && opcode != Opcodes.DRETURN && opcode != Opcodes.IRETURN &&
                opcode != Opcodes.FRETURN && opcode != Opcodes.LRETURN) {
                // TODO actually check return type
                failRecordMatching();
                return;
            }
            this.recordMethodMatchingState = RecordMethodMatchingState.SUCCESS;
        }

        @Override
        public void visitEnd() {
            int index = this.readingRecordComponentIndex;
            if (recordMethodMatchingState == RecordMethodMatchingState.SUCCESS) {
                if (index >= 0) {
//                    if (index >= this.orderedRecordComponent.size() || this.orderedRecordComponent.get(index) != this.recordComponentEntry) {
//                        System.out.println(String.format("Suspicious getter %s;%s : field expected %s but got %s", this.classEntry.getFullyQualifiedName(), this.entry.getKey(), this.orderedRecordComponent.get(index), this.recordComponentEntry));
//                    }
                    this.entry.recordComponent = this.recordComponentEntry;
                } else {
                    System.out.println(String.format("Demoting record getter %s;%s", this.classEntry.getFullyQualifiedName(), this.entry.getKey()));
                }
            }
//            else {
//                if (index >= 0) {
//                    String typeDescriptor = Type.getReturnType(this.entry.desc).getDescriptor();
//                    JarRecordComponentEntry entry = index < this.orderedRecordComponent.size() ? this.orderedRecordComponent.get(index) : null;
//                    if (entry != null && this.entry.desc.startsWith("()") && typeDescriptor.equals(entry.desc)) {
//                        System.out.println(String.format("Promoting possibly overridden record getter: %s;%s", this.classEntry.getFullyQualifiedName(), this.entry.getKey()));
//                        this.entry.recordComponent = entry;
//                    } else if (index < this.orderedRecordComponent.size()) { // <clinit> and other synthetics can exist after this
//                        System.out.println(String.format("Suspicious method in record getters ordering: %s;%s", this.classEntry.getFullyQualifiedName(), this.entry.getKey()));
//                    }
//                }
//            }
            super.visitEnd();
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            this.failRecordMatching();
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            this.failRecordMatching();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            this.failRecordMatching();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            this.failRecordMatching();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            this.failRecordMatching();
            if (bootstrapMethodHandle.getOwner().equals("java/lang/invoke/LambdaMetafactory") &&
                bootstrapMethodHandle.getName().equals("metafactory") &&
                bootstrapMethodHandle.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;") &&
                !bootstrapMethodHandle.isInterface()) {
                // lambda implementation
                Type functionalInterfaceType = Type.getReturnType(descriptor);
                String functionalInterfaceMethodName = name;
                String functionalInterfaceMethodDescriptor = ((Type) bootstrapMethodArguments[0]).getDescriptor();
                functionalInterfaceMethods.add(Pair.of(functionalInterfaceType.getInternalName(), functionalInterfaceMethodName + functionalInterfaceMethodDescriptor));
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            this.failRecordMatching();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            this.failRecordMatching();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            this.failRecordMatching();
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            this.failRecordMatching();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            this.failRecordMatching();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            this.failRecordMatching();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            this.failRecordMatching();
            return super.visitInsnAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            this.failRecordMatching();
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            this.failRecordMatching();
            return super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            super.visitLocalVariable(name, descriptor, signature, start, end, index);
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
            return super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, descriptor, visible);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
        }
    }

    public void apply() throws IOException {
        // Stage 1: read .JAR class/field/method meta
        try (FileInputStream fileStream = new FileInputStream(jar.file)) {
            try (JarInputStream jarStream = new JarInputStream(fileStream)) {
                java.util.jar.JarEntry entry;

                while ((entry = jarStream.getNextJarEntry()) != null) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }

                    ClassReader reader = new ClassReader(jarStream);
                    ClassVisitor visitor = new VisitorClass(StitchUtil.ASM_VERSION, null, false, jar);
                    reader.accept(visitor, ClassReader.SKIP_FRAMES);
                }
            }
        }

        System.err.println("Read " + this.jar.getAllClasses().size() + " (" + this.jar.getClasses().size() + ") classes.");

        // Stage 2: find subclasses
        this.jar.getAllClasses().forEach((c) -> c.populateParents(this.lazyClasspathStorage));
        System.err.println("Populated subclass entries.");

//        for (Pair<String, String> pair : this.functionalInterfaceMethods) {
//            String className = pair.getLeft();
//            String methodKey = pair.getRight();
//            JarClassEntry classEntry = this.lazyClasspathStorage.getClass(className, false);
//            JarMethodEntry method = classEntry.getMethod(methodKey);
//            if (method == null) {
//                System.out.println(String.format("Functional interface %s;%s not found", className, methodKey));
//                continue;
//            }
////            System.out.println(String.format("Marking functional interface %s;%s unobfuscated", className, methodKey));
//            method.isNonObfuscated = true;
//        }

        // Stage 3: join identical MethodEntries
        if (joinMethodEntries) {
            System.err.println("Joining MethodEntries...");
            Set<JarClassEntry> traversedClasses = StitchUtil.newIdentityHashSet();

            int joinedMethods = 1;
            int uniqueMethods = 0;

            Collection<JarMethodEntry> checkedMethods = StitchUtil.newIdentityHashSet();

            for (JarClassEntry entry : jar.getAllClasses()) {
                if (traversedClasses.contains(entry)) {
                    continue;
                }

                ClassPropagationTree tree = new ClassPropagationTree(this.lazyClasspathStorage, entry);
                if (tree.getClasses().size() == 1) {
                    traversedClasses.add(entry);
                    continue;
                }

                for (JarClassEntry c : tree.getClasses()) {
                    for (JarMethodEntry m : c.getMethods()) {
                        if (!checkedMethods.add(m)) {
                            continue;
                        }

                        // get all matching entries
                        List<JarClassEntry> mList = m.getMatchingEntries(this.lazyClasspathStorage, c, true);

                        if (mList.size() > 1) {
                            boolean hasNonObfuscated = false;
                            for (JarClassEntry key : mList) {
                                JarMethodEntry value = key.getMethod(m.getKey());
                                hasNonObfuscated |= value.isNonObfuscated;
                            }
                            if (hasNonObfuscated) {
                                for (JarClassEntry key : mList) {
                                    JarMethodEntry value = key.getMethod(m.getKey());
                                    value.isNonObfuscated = true;
                                }
                            } else {
                                for (JarClassEntry key : mList) {
                                    JarMethodEntry value = key.getMethod(m.getKey());
                                    if (value != m) {
                                        key.methods.put(m.getKey(), m);
                                        if (m.recordComponent == null) {
                                            m.recordComponent = value.recordComponent;
                                        }
                                        joinedMethods++;
                                    }
                                }
                            }
                        }
                    }
                }

                traversedClasses.addAll(tree.getClasses());
            }

            System.err.println("Joined " + joinedMethods + " MethodEntries (" + uniqueMethods + " unique, " + traversedClasses.size() + " classes).");
        }

        System.err.println("Collecting additional information...");

        // Stage 4: collect additional info
        /* try (FileInputStream fileStream = new FileInputStream(jar.file)) {
            try (JarInputStream jarStream = new JarInputStream(fileStream)) {
                java.util.jar.JarEntry entry;

                while ((entry = jarStream.getNextJarEntry()) != null) {
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }

                    ClassReader reader = new ClassReader(jarStream);
                    ClassVisitor visitor = new VisitorClassStageTwo(StitchUtil.ASM_VERSION, null);
                    reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        } */

        if (remapper != null) {
            System.err.println("Remapping...");

            Map<String, JarClassEntry> classTree = new HashMap<>(jar.classTree);
            jar.classTree.clear();

            for (Map.Entry<String, JarClassEntry> entry : classTree.entrySet()) {
                entry.getValue().remap(remapper);
                jar.classTree.put(entry.getValue().getKey(), entry.getValue());
            }
        }

        System.err.println("- Done. -");
    }

    class LazyClasspathStorage implements ClassStorage {
        private final ClassStorage delegate;
        private final URLClassLoader classpath;
        private final JarRootEntry classpathCache = new JarRootEntry();

        public LazyClasspathStorage(ClassStorage delegate, File classpathDir) {
            this.delegate = delegate;
            List<URL> urls = new ArrayList<>();
            File[] files = classpathDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    try {
                        urls.add(file.toURI().toURL());
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            this.classpath = new URLClassLoader(urls.toArray(URL[]::new));
        }

        @Override
        public JarClassEntry getClass(String name, boolean create) {
            if (create) {
                throw new UnsupportedOperationException();
            }
            JarClassEntry entry = this.delegate.getClass(name, false);
            if (entry != null) {
                return entry;
            }
            entry = this.classpathCache.getClass(name, false);
            if (entry != null && entry.populated) {
                return entry;
            }
            try (InputStream resourceAsStream = this.classpath.getResourceAsStream(name + ".class")) {
                if (resourceAsStream == null) {
                    System.out.println("%s not found from classpath".formatted(name));
                    return null;
                }
//                System.out.println("Loading %s from classpath".formatted(name));
                ClassReader reader = new ClassReader(resourceAsStream);
                VisitorClass visitor = new VisitorClass(StitchUtil.ASM_VERSION, null, true, this.classpathCache);
                reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                JarClassEntry entry1 = visitor.entry;
                entry1.populateParents(this);
                return entry1;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }
}
