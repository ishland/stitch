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

import net.fabricmc.stitch.util.StitchUtil;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.MethodNode;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarInputStream;

public class JarReader {
    public static class Builder {
        private final JarReader reader;

        private Builder(JarReader reader) {
            this.reader = reader;
        }

        public static Builder create(JarRootEntry jar) {
            return new Builder(new JarReader(jar));
        }

        public Builder joinMethodEntries(boolean value) {
            reader.joinMethodEntries = value;
            return this;
        }

        public Builder withRemapper(Remapper remapper) {
            reader.remapper = remapper;
            return this;
        }

        public JarReader build() {
            return reader;
        }
    }

    private final JarRootEntry jar;
    private boolean joinMethodEntries = true;
    private Remapper remapper;

    public JarReader(JarRootEntry jar) {
        this.jar = jar;
    }

    private class VisitorClass extends ClassVisitor {
        private JarClassEntry entry;

        public VisitorClass(int api, ClassVisitor classVisitor) {
            super(api, classVisitor);
        }

        @Override
        public void visit(final int version, final int access, final String name, final String signature,
                          final String superName, final String[] interfaces) {
            this.entry = jar.getClass(name, true);
            this.entry.populate(access, signature, superName, interfaces);

            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(final int access, final String name, final String descriptor,
                                       final String signature, final Object value) {
            JarFieldEntry field = new JarFieldEntry(access, name, descriptor, signature);
            this.entry.fields.put(field.getKey(), field);
            field.recordComponent = this.entry.getRecordComponent(field.getKey());

            return new VisitorField(api, super.visitField(access, name, descriptor, signature, value),
                    entry, field);
        }

        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                         final String signature, final String[] exceptions) {
            JarMethodEntry method = new JarMethodEntry(access, name, descriptor, signature);
            this.entry.methods.put(method.getKey(), method);

            return new VisitorMethod(api, super.visitMethod(access, name, descriptor, signature, exceptions),
                    entry, method);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            JarRecordComponentEntry recordComponent = new JarRecordComponentEntry(name, descriptor, signature);
            this.entry.recordComponents.put(recordComponent.getKey(), recordComponent);

            return new VisitorRecordComponent(api, super.visitRecordComponent(name, descriptor, signature), entry, recordComponent);
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
            super(api, methodVisitor, classEntry, entry);
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

        public VisitorMethod(int api, MethodVisitor methodVisitor, JarClassEntry classEntry, JarMethodEntry entry) {
            super(api, methodVisitor);
            this.classEntry = classEntry;
            this.entry = entry;
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
            if (recordMethodMatchingState == RecordMethodMatchingState.SUCCESS) {
                this.entry.recordComponent = this.recordComponentEntry;
            }
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
                    ClassVisitor visitor = new VisitorClass(StitchUtil.ASM_VERSION, null);
                    reader.accept(visitor, ClassReader.SKIP_FRAMES);
                }
            }
        }

        System.err.println("Read " + this.jar.getAllClasses().size() + " (" + this.jar.getClasses().size() + ") classes.");

        // Stage 2: find subclasses
        this.jar.getAllClasses().forEach((c) -> c.populateParents(jar));
        System.err.println("Populated subclass entries.");

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

                ClassPropagationTree tree = new ClassPropagationTree(jar, entry);
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
                        List<JarClassEntry> mList = m.getMatchingEntries(jar, c);

                        if (mList.size() > 1) {
                            for (int i = 0; i < mList.size(); i++) {
                                JarClassEntry key = mList.get(i);
                                JarMethodEntry value = key.getMethod(m.getKey());
                                if (value != m) {
                                    key.methods.put(m.getKey(), m);
                                    if (m.recordComponent == null) {
                                        m.recordComponent = value.recordComponent;
                                    } else {
                                        System.out.println(String.format("Merging %s/%s into %s/%s", key.getFullyQualifiedName(), value.getKey(), c.getFullyQualifiedName(), m.getKey()));
                                    }
                                    joinedMethods++;
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
}
