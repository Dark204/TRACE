/* Parse-only source index for matching declarations to compiled method metadata.
 * No attribution, compilation output, patch access or experiment labels.
 * Usage: java SourceDeclarationIndex.java output.jsonl source1.java ...
 */
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.Modifier;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class SourceDeclarationIndex {
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
                default: if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
    static String arr(List<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) out.add(q(x));
        return "[" + String.join(",", out) + "]";
    }
    static String digest(byte[] bytes) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) s.append(String.format("%02x", b & 255));
        return s.toString();
    }
    static class Input extends SimpleJavaFileObject {
        final String text, hash, path;
        Input(Path p) throws Exception {
            super(p.toAbsolutePath().toUri(), Kind.SOURCE);
            path = p.toAbsolutePath().toString();
            byte[] bytes = Files.readAllBytes(p);
            String t;
            try { t = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException ex) { t = new String(bytes, StandardCharsets.ISO_8859_1); }
            text = t; hash = digest(bytes);
        }
        public CharSequence getCharContent(boolean ignored) { return text; }
    }
    static class Indexer extends TreePathScanner<Void, Void> {
        final CompilationUnitTree unit;
        final SourcePositions positions;
        final Input input;
        final PrintWriter out;
        final Deque<String> owners = new ArrayDeque<>();
        final String pkg;
        Indexer(CompilationUnitTree u, Trees trees, Input in, PrintWriter w) {
            unit=u; positions=trees.getSourcePositions(); input=in; out=w;
            pkg=u.getPackageName()==null ? "" : u.getPackageName().toString();
        }
        long start(Tree t) { return positions.getStartPosition(unit,t); }
        long end(Tree t) { return positions.getEndPosition(unit,t); }
        String text(Tree t) { long a=start(t), b=end(t); return a>=0 && b>=a ? input.text.substring((int)a,(int)b) : null; }
        public Void visitClass(ClassTree t, Void unused) {
            String n=t.getSimpleName().toString();
            if(n.isEmpty()) n="anonymous@"+unit.getLineMap().getLineNumber(start(t));
            String owner=owners.isEmpty() ? (pkg.isEmpty()?n:pkg+"."+n) : owners.peek()+"$"+n;
            owners.push(owner);
            super.visitClass(t,unused);
            owners.pop(); return null;
        }
        void emit(Tree t, String name, String kind, List<String> params, String ret, boolean hasBody) {
            if(owners.isEmpty() || start(t)<0 || end(t)<0) return;
            long a=start(t),b=end(t);
            List<String> imports=new ArrayList<>();
            for(ImportTree it:unit.getImports()) if(!it.isStatic()) imports.add(it.getQualifiedIdentifier().toString());
            out.println("{\"record\":\"declaration\",\"parser\":\"JDK_JavacTask.parse\",\"source\":"+q(input.path)
                +",\"source_sha256\":"+q(input.hash)+",\"owner\":"+q(owners.peek())+",\"name\":"+q(name)
                +",\"node_kind\":"+q(kind)+",\"start_offset\":"+a+",\"end_offset\":"+b
                +",\"start_line\":"+unit.getLineMap().getLineNumber(a)+",\"end_line\":"+unit.getLineMap().getLineNumber(Math.max(a,b-1))
                +",\"parameters\":"+arr(params)+",\"return_type\":"+q(ret)+",\"imports\":"+arr(imports)
                +",\"has_body\":"+hasBody+",\"source_text\":"+q(text(t))+"}");
        }
        public Void visitMethod(MethodTree t, Void unused) {
            List<String> params=new ArrayList<>();
            for(VariableTree p:t.getParameters()) params.add(p.getType().toString());
            emit(t,t.getName().toString(),t.getReturnType()==null?"CONSTRUCTOR":"METHOD",params,
                t.getReturnType()==null?"void":t.getReturnType().toString(),t.getBody()!=null);
            return super.visitMethod(t,unused);
        }
        public Void visitBlock(BlockTree t, Void unused) {
            if(t.isStatic() && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree)
                emit(t,"<clinit>","STATIC_BLOCK",Collections.emptyList(),"void",true);
            return super.visitBlock(t,unused);
        }
        public Void visitVariable(VariableTree t, Void unused) {
            if(getCurrentPath().getParentPath().getLeaf() instanceof ClassTree && t.getInitializer()!=null
                    && t.getModifiers().getFlags().contains(Modifier.STATIC))
                emit(t,"<clinit>","STATIC_FIELD",Collections.emptyList(),t.getType().toString(),true);
            return super.visitVariable(t,unused);
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length<2) throw new IllegalArgumentException("output.jsonl source.java ...");
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null) throw new IllegalStateException("JDK compiler unavailable");
        List<Input> inputs=new ArrayList<>(); Map<URI,Input> byUri=new HashMap<>();
        for(int i=1;i<args.length;i++){Input x=new Input(Paths.get(args[i]));inputs.add(x);byUri.put(x.toUri(),x);}
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager fm=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8);
            PrintWriter out=new PrintWriter(Files.newBufferedWriter(Paths.get(args[0]),StandardCharsets.UTF_8))) {
            JavacTask task=(JavacTask)compiler.getTask(null,fm,diagnostics,Arrays.asList("-proc:none","-Xlint:none"),null,inputs);
            Iterable<? extends CompilationUnitTree> units=task.parse(); Trees trees=Trees.instance(task);
            for(CompilationUnitTree u:units) new Indexer(u,trees,byUri.get(u.getSourceFile().toUri()),out).scan(u,null);
            for(Diagnostic<? extends JavaFileObject> d:diagnostics.getDiagnostics())
                out.println("{\"record\":\"diagnostic\",\"kind\":"+q(d.getKind().toString())+",\"source\":"
                    +q(d.getSource()==null?null:d.getSource().toUri().getPath())+",\"line\":"+d.getLineNumber()
                    +",\"message\":"+q(d.getMessage(Locale.ROOT))+"}");
        }
    }
}
