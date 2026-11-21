/*
 * Parse-only AST evidence for TRACE151 failure-conditioned queries.
 *
 * The program deliberately uses JavacTask.parse(), not source-line pattern
 * matching. It emits method and executable-statement records together with
 * AST-derived reads, definitions, calls, receiver roots and control parents.
 * No attribution, compilation, labels or patch information is read here.
 *
 * Usage:
 *   java FailureQueryAstIndex.java output.jsonl source1.java source2.java ...
 */
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.net.URI;
import java.security.*;
import java.util.*;

public class FailureQueryAstIndex {
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32) b.append(String.format("\\u%04x", (int)c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
    static String arrStrings(Collection<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) out.add(q(x));
        return "[" + String.join(",", out) + "]";
    }
    static String arrInts(Collection<Integer> xs) {
        List<String> out = new ArrayList<>();
        for (Integer x : xs) out.add(String.valueOf(x));
        return "[" + String.join(",", out) + "]";
    }
    static String digest(byte[] bytes) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            s.append(String.format("%02x", b & 255));
        return s.toString();
    }
    static class Input extends SimpleJavaFileObject {
        final String text, hash, path;
        Input(Path p) throws Exception {
            super(p.toAbsolutePath().toUri(), Kind.SOURCE);
            path = p.toAbsolutePath().toString();
            byte[] bytes = Files.readAllBytes(p);
            String t;
            try {
                t = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException ex) {
                t = new String(bytes, StandardCharsets.ISO_8859_1);
            }
            text = t;
            hash = digest(bytes);
        }
        public CharSequence getCharContent(boolean ignored) { return text; }
    }
    static class Context {
        final CompilationUnitTree unit;
        final SourcePositions positions;
        final Input input;
        final PrintWriter out;
        final String pkg;
        int statementId = 0;
        Context(CompilationUnitTree u, SourcePositions p, Input i, PrintWriter w) {
            unit = u; positions = p; input = i; out = w;
            pkg = u.getPackageName() == null ? "" : u.getPackageName().toString();
        }
        long start(Tree t) { return positions.getStartPosition(unit, t); }
        long end(Tree t) { return positions.getEndPosition(unit, t); }
        int line(long pos) {
            return pos < 0 ? -1 : (int)unit.getLineMap().getLineNumber(pos);
        }
        String text(Tree t) {
            long a = start(t), b = end(t);
            if (a < 0 || b < a || b > input.text.length()) return null;
            return input.text.substring((int)a, (int)b);
        }
        String controlArray(Deque<Integer> controls) {
            List<Integer> ids = new ArrayList<>(controls);
            Collections.reverse(ids);
            return arrInts(ids);
        }
        String statement(Tree t, String kind, String methodOwner, String methodName,
                         Deque<Integer> controls, boolean control, int id,
                         Collector c) {
            long a = start(t), b = end(t);
            StringBuilder s = new StringBuilder();
            s.append("{\"record\":\"statement\"");
            s.append(",\"id\":").append(id);
            s.append(",\"source\":").append(q(input.path));
            s.append(",\"source_sha256\":").append(q(input.hash));
            s.append(",\"owner\":").append(q(methodOwner));
            s.append(",\"method\":").append(q(methodName));
            s.append(",\"kind\":").append(q(kind));
            s.append(",\"start_offset\":").append(a);
            s.append(",\"end_offset\":").append(b);
            s.append(",\"start_line\":").append(line(a));
            s.append(",\"end_line\":").append(line(Math.max(a, b - 1)));
            s.append(",\"text\":").append(q(text(t)));
            s.append(",\"is_control\":").append(control);
            s.append(",\"control_parents\":").append(controlArray(controls));
            s.append(",\"reads\":").append(arrStrings(c.reads));
            s.append(",\"defines\":").append(arrStrings(c.defines));
            s.append(",\"receiver_roots\":").append(arrStrings(c.receiverRoots));
            s.append(",\"writes\":").append(arrStrings(c.writes));
            s.append(",\"calls\":").append(c.callsJson());
            s.append("}");
            return s.toString();
        }
        int nextId() { return ++statementId; }
    }

    static class CallInfo {
        String text, method, receiver;
        CallInfo(String t, String m, String r) { text=t; method=m; receiver=r; }
    }

    /* AST-derived lexical facts for one complete executable statement. */
    static class Collector extends TreeScanner<Void, Void> {
        final LinkedHashSet<String> reads = new LinkedHashSet<>();
        final LinkedHashSet<String> defines = new LinkedHashSet<>();
        final LinkedHashSet<String> receiverRoots = new LinkedHashSet<>();
        final LinkedHashSet<String> writes = new LinkedHashSet<>();
        final List<CallInfo> calls = new ArrayList<>();
        final Deque<Tree> parents = new ArrayDeque<>();

        static String root(Tree t) {
            if (t == null) return null;
            if (t instanceof IdentifierTree) return ((IdentifierTree)t).getName().toString();
            if (t instanceof MemberSelectTree) return root(((MemberSelectTree)t).getExpression());
            if (t instanceof ArrayAccessTree) return root(((ArrayAccessTree)t).getExpression());
            if (t instanceof ParenthesizedTree) return root(((ParenthesizedTree)t).getExpression());
            return null;
        }
        static String methodName(ExpressionTree t) {
            if (t instanceof IdentifierTree) return ((IdentifierTree)t).getName().toString();
            if (t instanceof MemberSelectTree) return ((MemberSelectTree)t).getIdentifier().toString();
            return t == null ? null : t.toString();
        }
        static String receiver(ExpressionTree t) {
            if (t instanceof MemberSelectTree) return ((MemberSelectTree)t).getExpression().toString();
            return null;
        }
        @Override public Void scan(Tree t, Void v) {
            if (t == null) return null;
            parents.push(t);
            Void out = super.scan(t, v);
            parents.pop();
            return out;
        }
        @Override public Void visitIdentifier(IdentifierTree t, Void v) {
            String n = t.getName().toString();
            if (!defines.contains(n)) reads.add(n);
            return null;
        }
        @Override public Void visitVariable(VariableTree t, Void v) {
            String n = t.getName().toString();
            defines.add(n);
            if (t.getInitializer() != null) scan(t.getInitializer(), v);
            return null;
        }
        @Override public Void visitAssignment(AssignmentTree t, Void v) {
            String root = root(t.getVariable());
            if (root != null) { defines.add(root); writes.add(root); }
            scan(t.getExpression(), v);
            return null;
        }
        @Override public Void visitCompoundAssignment(CompoundAssignmentTree t, Void v) {
            String root = root(t.getVariable());
            if (root != null) { reads.add(root); defines.add(root); writes.add(root); }
            scan(t.getExpression(), v);
            return null;
        }
        @Override public Void visitUnary(UnaryTree t, Void v) {
            String root = root(t.getExpression());
            if (root != null && (t.getKind() == Tree.Kind.PREFIX_INCREMENT ||
                    t.getKind() == Tree.Kind.PREFIX_DECREMENT ||
                    t.getKind() == Tree.Kind.POSTFIX_INCREMENT ||
                    t.getKind() == Tree.Kind.POSTFIX_DECREMENT)) {
                reads.add(root); defines.add(root); writes.add(root);
            } else scan(t.getExpression(), v);
            return null;
        }
        @Override public Void visitMethodInvocation(MethodInvocationTree t, Void v) {
            String method = methodName(t.getMethodSelect());
            String rec = receiver(t.getMethodSelect());
            calls.add(new CallInfo(t.toString(), method, rec));
            if (rec != null) {
                String r = root(t.getMethodSelect() instanceof MemberSelectTree
                        ? ((MemberSelectTree)t.getMethodSelect()).getExpression() : null);
                if (r != null) { reads.add(r); receiverRoots.add(r); }
            }
            for (ExpressionTree a : t.getArguments()) scan(a, v);
            return null;
        }
        @Override public Void visitMemberSelect(MemberSelectTree t, Void v) {
            scan(t.getExpression(), v);
            String r = root(t);
            if (r != null) reads.add(r);
            return null;
        }
        @Override public Void visitNewClass(NewClassTree t, Void v) {
            for (ExpressionTree a : t.getArguments()) scan(a, v);
            if (t.getEnclosingExpression() != null) scan(t.getEnclosingExpression(), v);
            return null;
        }
        @Override public Void visitLambdaExpression(LambdaExpressionTree t, Void v) {
            for (VariableTree p : t.getParameters()) defines.add(p.getName().toString());
            scan(t.getBody(), v);
            return null;
        }
        String callsJson() {
            List<String> out = new ArrayList<>();
            for (CallInfo c : calls) {
                out.add("{\"text\":" + q(c.text) + ",\"method\":" + q(c.method) +
                        ",\"receiver\":" + q(c.receiver) + "}");
            }
            return "[" + String.join(",", out) + "]";
        }
    }

    static class BodyScanner extends TreeScanner<Void, Void> {
        final Context c;
        final String owner, method;
        final Deque<Integer> controls = new ArrayDeque<>();
        BodyScanner(Context x, String o, String m) { c=x; owner=o; method=m; }
        void emit(Tree t, String kind, boolean isControl) {
            Collector x = new Collector(); x.scan(t, null);
            int id = c.nextId();
            c.out.println(c.statement(t, kind, owner, method, controls, isControl, id, x));
        }
        void withControl(Tree t, String kind, Runnable scanBody) {
            Collector x = new Collector(); x.scan(t, null);
            int id = c.nextId();
            c.out.println(c.statement(t, kind, owner, method, controls, true, id, x));
            controls.push(id);
            scanBody.run();
            controls.pop();
        }
        @Override public Void visitIf(IfTree t, Void v) {
            withControl(t, "IF", () -> { scan(t.getCondition(), v); scan(t.getThenStatement(), v); if (t.getElseStatement()!=null) scan(t.getElseStatement(), v); });
            return null;
        }
        @Override public Void visitForLoop(ForLoopTree t, Void v) {
            withControl(t, "FOR_LOOP", () -> { for (StatementTree s:t.getInitializer()) scan(s,v); scan(t.getCondition(),v); for(ExpressionStatementTree u:t.getUpdate()) scan(u,v); scan(t.getStatement(),v); });
            return null;
        }
        @Override public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void v) {
            withControl(t, "ENHANCED_FOR_LOOP", () -> { scan(t.getVariable(),v); scan(t.getExpression(),v); scan(t.getStatement(),v); });
            return null;
        }
        @Override public Void visitWhileLoop(WhileLoopTree t, Void v) {
            withControl(t, "WHILE_LOOP", () -> { scan(t.getCondition(),v); scan(t.getStatement(),v); });
            return null;
        }
        @Override public Void visitDoWhileLoop(DoWhileLoopTree t, Void v) {
            withControl(t, "DO_WHILE_LOOP", () -> { scan(t.getStatement(),v); scan(t.getCondition(),v); });
            return null;
        }
        @Override public Void visitSwitch(SwitchTree t, Void v) {
            withControl(t, "SWITCH", () -> { scan(t.getExpression(),v); for (CaseTree x:t.getCases()) scan(x,v); });
            return null;
        }
        @Override public Void visitTry(TryTree t, Void v) {
            withControl(t, "TRY", () -> { for (Tree r:t.getResources()) scan(r,v); scan(t.getBlock(),v); for(CatchTree x:t.getCatches()) scan(x,v); scan(t.getFinallyBlock(),v); });
            return null;
        }
        @Override public Void visitCatch(CatchTree t, Void v) {
            emit(t, "CATCH", true); scan(t.getParameter(),v); scan(t.getBlock(),v); return null;
        }
        @Override public Void visitSynchronized(SynchronizedTree t, Void v) {
            withControl(t, "SYNCHRONIZED", () -> { scan(t.getExpression(),v); scan(t.getBlock(),v); }); return null;
        }
        @Override public Void visitExpressionStatement(ExpressionStatementTree t, Void v) { emit(t,"EXPRESSION_STATEMENT",false); return super.visitExpressionStatement(t,v); }
        @Override public Void visitVariable(VariableTree t, Void v) { emit(t,"VARIABLE",false); return super.visitVariable(t,v); }
        @Override public Void visitReturn(ReturnTree t, Void v) { emit(t,"RETURN",false); return super.visitReturn(t,v); }
        @Override public Void visitThrow(ThrowTree t, Void v) { emit(t,"THROW",false); return super.visitThrow(t,v); }
        @Override public Void visitAssert(AssertTree t, Void v) { emit(t,"ASSERT",false); return super.visitAssert(t,v); }
        @Override public Void visitBreak(BreakTree t, Void v) { emit(t,"BREAK",false); return super.visitBreak(t,v); }
        @Override public Void visitContinue(ContinueTree t, Void v) { emit(t,"CONTINUE",false); return super.visitContinue(t,v); }
        @Override public Void visitLabeledStatement(LabeledStatementTree t, Void v) { emit(t,"LABELED",false); return super.visitLabeledStatement(t,v); }
    }

    static class Indexer extends TreeScanner<Void, Void> {
        final Context c;
        final Deque<String> owners = new ArrayDeque<>();
        Indexer(Context x) { c=x; }
        @Override public Void visitClass(ClassTree t, Void v) {
            String n=t.getSimpleName().toString();
            if (n.isEmpty()) n="anonymous@"+c.line(c.start(t));
            String owner=owners.isEmpty() ? (c.pkg.isEmpty()?n:c.pkg+"."+n) : owners.peek()+"$"+n;
            owners.push(owner); super.visitClass(t,v); owners.pop(); return null;
        }
        @Override public Void visitMethod(MethodTree t, Void v) {
            if (t.getBody()!=null && !owners.isEmpty()) {
                String name=t.getName().toString();
                long a=c.start(t), b=c.end(t);
                c.out.println("{\"record\":\"method\",\"source\":"+q(c.input.path)+
                    ",\"source_sha256\":"+q(c.input.hash)+",\"owner\":"+q(owners.peek())+
                    ",\"name\":"+q(name)+",\"kind\":"+q(t.getReturnType()==null?"CONSTRUCTOR":"METHOD")+
                    ",\"start_offset\":"+a+",\"end_offset\":"+b+
                    ",\"start_line\":"+c.line(a)+",\"end_line\":"+c.line(Math.max(a,b-1))+
                    ",\"text\":"+q(c.text(t))+"}");
                new BodyScanner(c, owners.peek(), name).scan(t.getBody(), null);
            }
            return super.visitMethod(t,v);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("output.jsonl source.java ...");
        Path output=Paths.get(args[0]);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if (compiler==null) throw new IllegalStateException("JDK compiler unavailable");
        List<Input> inputs=new ArrayList<>(); Map<URI,Input> byUri=new HashMap<>();
        for (int i=1;i<args.length;i++) { Input x=new Input(Paths.get(args[i])); inputs.add(x); byUri.put(x.toUri(),x); }
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (StandardJavaFileManager fm=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8);
             PrintWriter out=new PrintWriter(Files.newBufferedWriter(output,StandardCharsets.UTF_8))) {
            JavacTask task=(JavacTask)compiler.getTask(null,fm,diagnostics,
                Arrays.asList("-proc:none","-Xlint:none"),null,inputs);
            Iterable<? extends CompilationUnitTree> units=task.parse();
            Trees trees=Trees.instance(task);
            for (CompilationUnitTree u:units) {
                Input in=byUri.get(u.getSourceFile().toUri());
                Context c=new Context(u,trees.getSourcePositions(),in,out);
                out.println("{\"record\":\"file\",\"source\":"+q(in.path)+
                    ",\"source_sha256\":"+q(in.hash)+",\"package\":"+q(c.pkg)+"}");
                new Indexer(c).scan(u,null);
            }
            for (Diagnostic<? extends JavaFileObject> d:diagnostics.getDiagnostics())
                out.println("{\"record\":\"diagnostic\",\"kind\":"+q(d.getKind().toString())+
                    ",\"source\":"+q(d.getSource()==null?null:d.getSource().toUri().getPath())+
                    ",\"line\":"+d.getLineNumber()+",\"message\":"+q(d.getMessage(Locale.ROOT))+"}");
        }
    }
}
