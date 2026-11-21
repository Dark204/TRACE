package trace2code.auto;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * P40 constructor-safe trace transformer.
 *
 * The old visitor treated the first INVOKESPECIAL <init> as the current
 * constructor's initialisation.  That is not valid when a constructor first
 * constructs an argument (NEW ... INVOKESPECIAL).  This visitor keeps a
 * small abstract operand/local stack and starts constructor tracing only
 * when the receiver token at the call is the method's own uninitialised-this.
 * It therefore preserves the JVM uninitialised-object verifier rule without
 * relying on an owner-name shortcut.
 */
public final class TraceTransformer implements ClassFileTransformer {
    private static final String RUNTIME = "trace2code/auto/TraceRuntime";
    private static int transformedClasses;

    @Override
    public byte[] transform(ClassLoader loader, String className,
                             Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain,
                             byte[] classfileBuffer) {
        if (!isProjectClass(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            MethodLines lines = new MethodLines();
            reader.accept(lines, ClassReader.SKIP_FRAMES);
            final FrameHierarchy hierarchy = new FrameHierarchy(loader, className, classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return hierarchy.commonSuperClass(type1, type2);
                }
            };
            reader.accept(new Visitor(writer, lines), ClassReader.EXPAND_FRAMES);
            synchronized (TraceTransformer.class) {
                transformedClasses++;
            }
            return writer.toByteArray();
        } catch (RuntimeException error) {
            throw new IllegalStateException("P40 instrumentation failed for " + className,
                    error);
        }
    }

    public static int transformedClassCount() {
        synchronized (TraceTransformer.class) {
            return transformedClasses;
        }
    }

    private static boolean isProjectClass(String className) {
        return className != null
                && (className.startsWith("org/jfree/")
                    || className.startsWith("org/apache/commons/lang3/")
                    || className.startsWith("org/apache/commons/lang/")
                    || className.startsWith("org/apache/commons/math3/")
                    || className.startsWith("org/apache/commons/math/")
                    || className.startsWith("org/joda/time/")
                    || className.startsWith("com/google/javascript/")
                    || className.startsWith("com/google/debugging/sourcemap/")
                    || className.startsWith("org/apache/commons/cli/"))
                && !className.contains("/junit/");
    }

    /**
     * Resolves frame joins from class-file metadata rather than Class.forName.
     * The default ASM resolver can recursively define the class currently
     * passing through this transformer (observed for Closure's JSType).
     */
    private static final class FrameHierarchy {
        private final ClassLoader loader;
        private final String currentName;
        private final byte[] currentBytes;
        private final Map<String, FrameTypeInfo> cache = new HashMap<String, FrameTypeInfo>();

        private FrameHierarchy(ClassLoader loader, String currentName, byte[] currentBytes) {
            this.loader = loader;
            this.currentName = currentName;
            this.currentBytes = currentBytes;
        }

        private String commonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if (type1.startsWith("[") || type2.startsWith("[")) return "java/lang/Object";
            if (isAssignable(type1, type2, new HashSet<String>())) return type1;
            if (isAssignable(type2, type1, new HashSet<String>())) return type2;
            FrameTypeInfo first = info(type1);
            if ((first.access & Opcodes.ACC_INTERFACE) != 0) return "java/lang/Object";
            String candidate = first.superName;
            while (candidate != null) {
                if (isAssignable(candidate, type2, new HashSet<String>())) return candidate;
                candidate = info(candidate).superName;
            }
            return "java/lang/Object";
        }

        private boolean isAssignable(String target, String candidate, Set<String> seen) {
            if (target.equals(candidate) || "java/lang/Object".equals(target)) return true;
            if (candidate == null || !seen.add(candidate)) return false;
            if (candidate.startsWith("[")) {
                return "java/lang/Cloneable".equals(target)
                        || "java/io/Serializable".equals(target);
            }
            FrameTypeInfo candidateInfo = info(candidate);
            if (candidateInfo.superName != null
                    && isAssignable(target, candidateInfo.superName, seen)) return true;
            for (int i = 0; i < candidateInfo.interfaces.length; i++) {
                if (isAssignable(target, candidateInfo.interfaces[i], seen)) return true;
            }
            return false;
        }

        private static boolean isPlatformType(String internalName) {
            return internalName.startsWith("java/") || internalName.startsWith("javax/")
                    || internalName.startsWith("sun/") || internalName.startsWith("jdk/")
                    || internalName.startsWith("com/sun/");
        }

        private static Class<?> loadPlatformType(String internalName) throws ClassNotFoundException {
            String binaryName = internalName.replace('/', '.');
            try {
                return Class.forName(binaryName, false, null);
            } catch (ClassNotFoundException bootstrapMiss) {
                return Class.forName(binaryName, false, ClassLoader.getPlatformClassLoader());
            }
        }

        private FrameTypeInfo info(String internalName) {
            FrameTypeInfo found = cache.get(internalName);
            if (found != null) return found;
            FrameTypeInfo parsed = new FrameTypeInfo();
            cache.put(internalName, parsed);
            try {
                if (internalName.equals(currentName)) {
                    new ClassReader(currentBytes).accept(parsed,
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return parsed;
                }
                // Legacy ASM cannot parse modern JDK constant pools. Only platform
                // types use non-initializing platform reflection; application types
                // remain resource-based to avoid recursive definitions while weaving.
                if (isPlatformType(internalName)) {
                    try {
                        Class<?> platformType = loadPlatformType(internalName);
                        parsed.access = platformType.isInterface() ? Opcodes.ACC_INTERFACE : 0;
                        Class<?> platformParent = platformType.getSuperclass();
                        parsed.superName = platformParent == null ? null
                                : platformParent.getName().replace('.', '/');
                        Class<?>[] platformInterfaces = platformType.getInterfaces();
                        parsed.interfaces = new String[platformInterfaces.length];
                        for (int index = 0; index < platformInterfaces.length; index++) {
                            parsed.interfaces[index] = platformInterfaces[index].getName().replace('.', '/');
                        }
                        return parsed;
                    } catch (ClassNotFoundException | LinkageError | SecurityException unavailable) {
                        // Retain the existing resource fallback for unavailable types.
                    }
                }
                String resource = internalName + ".class";
                InputStream stream = loader == null
                        ? ClassLoader.getSystemResourceAsStream(resource)
                        : loader.getResourceAsStream(resource);
                if (stream == null) {
                    stream = TraceTransformer.class.getClassLoader().getResourceAsStream(resource);
                }
                if (stream != null) {
                    try {
                        new ClassReader(stream).accept(parsed,
                                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } finally {
                        stream.close();
                    }
                }
            } catch (IOException ignored) {
                // A missing resource conservatively falls back to Object.
            }
            if (parsed.superName == null && !"java/lang/Object".equals(internalName)) {
                parsed.superName = "java/lang/Object";
            }
            return parsed;
        }
    }

    private static final class FrameTypeInfo extends ClassAdapter {
        private String superName;
        private String[] interfaces = new String[0];
        private int access;

        private FrameTypeInfo() {
            super(new ClassWriter(0));
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
            super.visit(version, access, name, signature, superName, interfaces);
        }
    }

    private static final class MethodLines extends ClassAdapter {
        private final Map<String, Integer> firstLines = new HashMap<String, Integer>();
        private String sourceFile = "";

        private MethodLines() {
            super(new ClassWriter(0));
        }

        @Override
        public void visitSource(String source, String debug) {
            sourceFile = source == null ? "" : source;
            super.visitSource(source, debug);
        }

        @Override
        public MethodVisitor visitMethod(int access, final String name,
                                         final String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor,
                    signature, exceptions);
            return new MethodAdapter(delegate) {
                @Override
                public void visitLineNumber(int line, Label start) {
                    if (!firstLines.containsKey(key(name, descriptor))) {
                        firstLines.put(key(name, descriptor), line);
                    }
                    super.visitLineNumber(line, start);
                }
            };
        }

        private String sourceFile() {
            return sourceFile;
        }

        private int line(String name, String descriptor) {
            Integer value = firstLines.get(key(name, descriptor));
            return value == null ? -1 : value.intValue();
        }
    }

    private static final class Visitor extends ClassAdapter {
        private final MethodLines lines;
        private String className;
        private String sourceFile;

        private Visitor(ClassWriter writer, MethodLines lines) {
            super(writer);
            this.lines = lines;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            className = name.replace('/', '.');
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public void visitSource(String source, String debug) {
            sourceFile = source == null ? lines.sourceFile() : source;
            super.visitSource(source, debug);
        }

        @Override
        public MethodVisitor visitMethod(int access, final String name,
                                         final String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor,
                    signature, exceptions);
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return delegate;
            }
            final String stableFile = packageQualifiedSourceFile(
                    className, sourceFile == null ? lines.sourceFile() : sourceFile);
            final int sourceLine = lines.line(name, descriptor);
            final String returnType = Type.getReturnType(descriptor).getClassName();
            if ("<init>".equals(name)) {
                return new ConstructorAdapter(delegate, className, descriptor,
                        stableFile, sourceLine, returnType);
            }
            return new OrdinaryAdapter(delegate, className, name, descriptor,
                    stableFile, sourceLine, returnType);
        }
    }

    private static final class OrdinaryAdapter extends MethodAdapter {
        private final String className;
        private final String methodName;
        private final String descriptor;
        private final String sourceFile;
        private final int sourceLine;
        private final String returnType;
        private final Label start = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        private OrdinaryAdapter(MethodVisitor delegate, String className,
                                String methodName, String descriptor, String sourceFile,
                                int sourceLine, String returnType) {
            super(delegate);
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
            this.returnType = returnType;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitLdcInsn(className);
            super.visitLdcInsn(methodName);
            super.visitLdcInsn(descriptor);
            super.visitLdcInsn(sourceFile == null ? "" : sourceFile);
            super.visitIntInsn(Opcodes.SIPUSH, sourceLine);
            super.visitLdcInsn(returnType);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "enter",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
            super.visitLabel(start);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                    || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
                    || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME,
                        "exitReturn", "()V");
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitLabel(end);
            super.visitTryCatchBlock(start, end, handler, null);
            super.visitLabel(handler);
            super.visitVarInsn(Opcodes.ASTORE, maxLocals);
            super.visitVarInsn(Opcodes.ALOAD, maxLocals);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME,
                    "exitException", "(Ljava/lang/Throwable;)V");
            super.visitVarInsn(Opcodes.ALOAD, maxLocals);
            super.visitInsn(Opcodes.ATHROW);
            super.visitMaxs(maxStack, maxLocals + 1);
        }
    }

    /** Constructor adapter with receiver provenance, not owner heuristics. */
    private static final class ConstructorAdapter extends MethodAdapter {
        private final String className;
        private final String descriptor;
        private final String sourceFile;
        private final int sourceLine;
        private final String returnType;
        private final Label start = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();
        private final List<Token> stack = new ArrayList<Token>();
        private final Map<Integer, Token> locals = new HashMap<Integer, Token>();
        private boolean initialized;
        private int nextObjectId = 1;

        private ConstructorAdapter(MethodVisitor delegate, String className,
                                   String descriptor, String sourceFile,
                                   int sourceLine, String returnType) {
            super(delegate);
            this.className = className;
            this.descriptor = descriptor;
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
            this.returnType = returnType;
            locals.put(Integer.valueOf(0), Token.THIS);
        }

        private void push(Token token) { stack.add(token); }

        private Token pop() {
            return stack.isEmpty() ? Token.UNKNOWN : stack.remove(stack.size() - 1);
        }

        private void discard(int count) {
            for (int i = 0; i < count && !stack.isEmpty(); i++) {
                pop();
            }
        }

        private void emitEnter() {
            super.visitLdcInsn(className);
            super.visitLdcInsn("<init>");
            super.visitLdcInsn(descriptor);
            super.visitLdcInsn(sourceFile == null ? "" : sourceFile);
            super.visitIntInsn(Opcodes.SIPUSH, sourceLine);
            super.visitLdcInsn(returnType);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "enter",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
            super.visitLabel(start);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
            if (opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD
                    || opcode == Opcodes.FLOAD || opcode == Opcodes.LLOAD
                    || opcode == Opcodes.DLOAD) {
                Token value = locals.get(Integer.valueOf(var));
                if (value == null) {
                    value = (!initialized && var == 0) ? Token.THIS : Token.UNKNOWN;
                }
                push(value);
            } else if (opcode == Opcodes.ASTORE || opcode == Opcodes.ISTORE
                    || opcode == Opcodes.FSTORE || opcode == Opcodes.LSTORE
                    || opcode == Opcodes.DSTORE) {
                locals.put(Integer.valueOf(var), pop());
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            super.visitTypeInsn(opcode, type);
            if (opcode == Opcodes.NEW) {
                push(new Token(Token.NEW, nextObjectId++));
            } else if (opcode == Opcodes.ANEWARRAY || opcode == Opcodes.CHECKCAST) {
                if (opcode == Opcodes.ANEWARRAY) {
                    discard(1);
                } else {
                    Token value = pop();
                    push(value);
                }
            } else if (opcode == Opcodes.INSTANCEOF) {
                discard(1);
                push(Token.OTHER);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner,
                                    String methodName, String methodDescriptor) {
            int argc = Type.getArgumentTypes(methodDescriptor).length;
            Token[] args = new Token[argc];
            for (int i = argc - 1; i >= 0; i--) {
                args[i] = pop();
            }
            Token receiver = (opcode == Opcodes.INVOKESTATIC) ? Token.UNKNOWN : pop();
            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor);
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(methodName)) {
                if (!initialized && receiver == Token.THIS) {
                    initialized = true;
                    emitEnter();
                } else if (receiver.kind == Token.NEW) {
                    markObjectInitialized(receiver.id);
                }
            }
            Type returnValue = Type.getReturnType(methodDescriptor);
            if (returnValue.getSort() != Type.VOID) {
                push(Token.OTHER);
            }
        }

        private void markObjectInitialized(int id) {
            for (int i = 0; i < stack.size(); i++) {
                Token value = stack.get(i);
                if (value.kind == Token.NEW && value.id == id) {
                    stack.set(i, Token.OTHER);
                }
            }
            for (Map.Entry<Integer, Token> entry : locals.entrySet()) {
                Token value = entry.getValue();
                if (value.kind == Token.NEW && value.id == id) {
                    entry.setValue(Token.OTHER);
                }
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if (initialized && (opcode == Opcodes.IRETURN
                    || opcode == Opcodes.LRETURN
                    || opcode == Opcodes.FRETURN
                    || opcode == Opcodes.DRETURN
                    || opcode == Opcodes.ARETURN
                    || opcode == Opcodes.RETURN)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME,
                        "exitReturn", "()V");
            }
            super.visitInsn(opcode);
            switch (opcode) {
                case Opcodes.DUP:
                    if (!stack.isEmpty()) push(stack.get(stack.size() - 1));
                    break;
                case Opcodes.DUP_X1:
                    if (stack.size() >= 2) {
                        Token a = pop(); Token b = pop();
                        push(a); push(b); push(a);
                    } else { stack.clear(); }
                    break;
                case Opcodes.DUP_X2:
                    if (stack.size() >= 3) {
                        Token a = pop(); Token b = pop(); Token c = pop();
                        push(a); push(c); push(b); push(a);
                    } else { stack.clear(); }
                    break;
                case Opcodes.SWAP:
                    if (stack.size() >= 2) {
                        Token a = pop(); Token b = pop(); push(a); push(b);
                    } else { stack.clear(); }
                    break;
                case Opcodes.POP: pop(); break;
                case Opcodes.POP2: discard(2); break;
                case Opcodes.ACONST_NULL:
                case Opcodes.ICONST_M1: case Opcodes.ICONST_0:
                case Opcodes.ICONST_1: case Opcodes.ICONST_2:
                case Opcodes.ICONST_3: case Opcodes.ICONST_4:
                case Opcodes.ICONST_5: case Opcodes.LCONST_0:
                case Opcodes.LCONST_1: case Opcodes.FCONST_0:
                case Opcodes.FCONST_1: case Opcodes.FCONST_2:
                case Opcodes.DCONST_0: case Opcodes.DCONST_1:
                    push(Token.OTHER); break;
                case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL:
                case Opcodes.IDIV: case Opcodes.IREM: case Opcodes.IAND:
                case Opcodes.IOR: case Opcodes.IXOR:
                case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL:
                case Opcodes.LDIV: case Opcodes.LREM:
                case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL:
                case Opcodes.FDIV: case Opcodes.FREM:
                case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL:
                case Opcodes.DDIV: case Opcodes.DREM:
                    discard(2); push(Token.OTHER); break;
                case Opcodes.INEG: case Opcodes.LNEG: case Opcodes.FNEG:
                case Opcodes.DNEG: break;
                default: break;
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            super.visitIntInsn(opcode, operand);
            if (opcode == Opcodes.NEWARRAY || opcode == Opcodes.BIPUSH
                    || opcode == Opcodes.SIPUSH) {
                if (opcode == Opcodes.NEWARRAY) discard(1);
                push(Token.OTHER);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            super.visitLdcInsn(value);
            push(Token.OTHER);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String fieldName,
                                   String fieldDescriptor) {
            super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
            if (opcode == Opcodes.GETSTATIC) push(Token.OTHER);
            else if (opcode == Opcodes.GETFIELD) { pop(); push(Token.OTHER); }
            else if (opcode == Opcodes.PUTFIELD) discard(2);
            else if (opcode == Opcodes.PUTSTATIC) discard(1);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            super.visitJumpInsn(opcode, label);
            if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR) discard(1);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (initialized) {
                super.visitLabel(end);
                super.visitTryCatchBlock(start, end, handler, null);
                super.visitLabel(handler);
                super.visitVarInsn(Opcodes.ASTORE, maxLocals);
                super.visitVarInsn(Opcodes.ALOAD, maxLocals);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME,
                        "exitException", "(Ljava/lang/Throwable;)V");
                super.visitVarInsn(Opcodes.ALOAD, maxLocals);
                super.visitInsn(Opcodes.ATHROW);
                super.visitMaxs(maxStack, maxLocals + 1);
            } else {
                super.visitMaxs(maxStack, maxLocals);
            }
        }
    }

    private static final class Token {
        private static final int THIS_KIND = 1;
        private static final int NEW = 2;
        private static final int OTHER_KIND = 3;
        private static final int UNKNOWN_KIND = 4;
        private static final Token THIS = new Token(THIS_KIND, 0);
        private static final Token OTHER = new Token(OTHER_KIND, 0);
        private static final Token UNKNOWN = new Token(UNKNOWN_KIND, 0);
        private final int kind;
        private final int id;
        private Token(int kind, int id) { this.kind = kind; this.id = id; }
    }

    private static String key(String name, String descriptor) {
        return name + descriptor;
    }

    private static String packageQualifiedSourceFile(String qualifiedClassName,
                                                     String sourceFile) {
        if (sourceFile == null || sourceFile.length() == 0) return "";
        int packageEnd = qualifiedClassName.lastIndexOf('.');
        if (packageEnd < 0) return sourceFile;
        return qualifiedClassName.substring(0, packageEnd).replace('.', '/')
                + "/" + sourceFile;
    }
}
