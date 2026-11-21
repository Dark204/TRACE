"""Run frozen TRACE for one compiled, buggy Java checkout; never reads labels."""
import argparse
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time

from . import candidate_core as core
from .classfile import read_class
from .pointwise import make_plan, ModelRunner, query
from .source_binding import RevisionBinder

ROOT = Path(__file__).resolve().parents[1]
TRIGGER = re.compile(r"(?m)^\s*([A-Za-z_$][\w$.]*::[A-Za-z_$][\w$]*)\s*$")
FRAME = re.compile(r"(?m)^\s*at\s+([A-Za-z_$][\w$.]*(?:\$[A-Za-z_$][\w$]*)*)\.([A-Za-z_$][\w$]*)\(([^():]+\.java):(\d+)\)")


def save(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, allow_nan=False)
        stream.write("\n")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def build_agent(args, out):
    classes = out / "agent"
    classes.mkdir()
    subprocess.run([args.javac, "-encoding", "UTF-8", "-source", "7", "-target", "7", "-proc:none",
                    "-cp", args.classpath, "-d", str(classes),
                    *map(str, sorted((ROOT/"java/coverage").glob("*.java")))], check=True, timeout=180)
    manifest = out / "agent.mf"
    manifest.write_bytes(b"Premain-Class: trace2code.auto.TraceAgent\nCan-Redefine-Classes: false\n\n")
    jar = out / "agent.jar"
    subprocess.run([args.jar, "cfm", str(jar), str(manifest), "-C", str(classes), "trace2code"], check=True, timeout=120)
    return jar


def capture(args, out):
    test = args.trigger or next(iter(sorted(set(TRIGGER.findall(core.read_text(args.triggers))))), None)
    if not test or not TRIGGER.fullmatch(test):
        raise ValueError("No valid official trigger was supplied")
    if args.triggers and test not in TRIGGER.findall(core.read_text(args.triggers)):
        raise ValueError("Trigger is not present in the supplied official trigger list")
    owner, method = test.split("::")
    jar = build_agent(args, out)
    raw = out/"events.jsonl"
    err = out/"stderr.txt"
    stdout = out/"stdout.txt"
    launcher = "SingleJUnit3Launcher" if args.junit3 else "SingleJUnitLauncher"
    cmd = [args.java, "-verbose:class", "-Djava.awt.headless=true", "-Duser.language=en",
           "-Duser.country=US", "-Duser.variant=", "-Dtrace2code.p39.asyncQueue=true",
           "-Dtrace2code.p39.queueCapacity=32768", "-Dtrace2code.p39.serializerWorkers=3",
           "-Djava.io.tmpdir="+str(out), "-cp", os.pathsep.join((str(jar), args.classpath)),
           f"-javaagent:{jar}=out={raw}", "trace2code.auto."+launcher, owner, method]
    env = {k:v for k,v in os.environ.items() if k not in {"JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"}}
    env.update(TEMP=str(out),TMP=str(out))
    start = time.monotonic()
    with stdout.open("xb") as streams_out, err.open("xb") as streams_err:
        with subprocess.Popen(cmd, cwd=out, env=env, stdout=streams_out, stderr=streams_err) as p:
            while p.poll() is None:
                if time.monotonic()-start>120 or any(
                    x.exists() and x.stat().st_size>cap for x,cap in ((raw,1<<30),(stdout,16<<20),(err,16<<20))
                ):
                    p.kill()
                    p.wait()
                    raise RuntimeError("Original capture time or byte guard exceeded")
                time.sleep(.05)
    so,se = core.read_text(stdout),core.read_text(err)
    outcome = core.parse_test_outcome(so,se,p.returncode)
    if not (p.returncode==1 and outcome["run_count"]==1 and
            sum(outcome[k] or 0 for k in ("failure_count","error_count"))==1 and
            (f"{method}({owner})" in se or f"test={test}" in so)):
        raise RuntimeError("The selected test did not reproduce exactly one failure")
    if re.search(r'(?m)Method "[^"]+" not found|No tests found|Could not find class|java\.lang\.(?:VerifyError|LinkageError|ClassFormatError)',se):
        raise RuntimeError("The selected launcher did not reproduce a valid test failure")
    with raw.open("rb") as f:
        opener = gzip.open if f.read(2)==b"\x1f\x8b" else open
    hits, exists, n = set(), {}, 0
    with opener(raw,"rt",encoding="utf-8") as stream:
        for line in stream:
            if not line.endswith("\n"):
                raise ValueError("Unterminated capture event")
            obj = json.loads(line)
            n += 1
            name = obj.get("file")
            if name:
                if name not in exists:
                    exists[name] = (args.source_root/name).is_file()
                if exists[name]:
                    hits.add(f"{obj.get('class')}#{obj.get('method')}{obj.get('descriptor')}")
    receipt = raw.with_name(raw.name+".capture.json")
    if receipt.is_file():
        r = json.loads(receipt.read_text(encoding="utf-8"))
        assert r["complete"] and r["rows"]==n and r["compressed_sha256"]==sha(raw)
    if not hits:
        raise ValueError("No production methods executed by the trigger")
    save(out/"coverage.json",dict(test_id=test,methods=sorted(hits),n_events=n))
    return test,err,hits


def jsonl(path):
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def production_methods(args, out, hits):
    files = sorted(args.source_root.rglob("*.java"),key=lambda p:str(p).lower())
    idx = out/"decl"
    idx.mkdir()
    subprocess.run([args.javac,"-encoding","UTF-8","-d",str(idx),
                    str(ROOT/"java/index/SourceDeclarationIndex.java")],check=True,timeout=180)
    source_rows = []
    for i in range(0,len(files),32):
        f = idx/f"{i}.jsonl"
        subprocess.run([args.java,"-cp",str(idx),"SourceDeclarationIndex",str(f),*map(str,files[i:i+32])],
                       check=True,timeout=300)
        source_rows.extend(jsonl(f))
    by_owner = {}
    domain = set(hits)
    for p in sorted(args.production_classes.rglob("*.class")):
        relative = ".".join(p.relative_to(args.production_classes).with_suffix("").parts)
        if relative in {"module-info","package-info"} or relative.endswith(".module-info"):
            continue
        c = read_class(p)
        if c["owner"] != relative:
            raise ValueError("Class root does not identify the declared class owner")
        by_owner[c["owner"]] = {**c,"bug":args.case}
        domain.update(m["entity"] for m in c["methods"])
    if not by_owner:
        raise ValueError("No production class files")
    binder = RevisionBinder(core,source_rows,[dict(bug=args.case,source_root=args.source_root)],by_owner)
    mapping = {}
    for name in sorted(domain):
        row,text = binder.bind(name,args.case,by_owner)
        mapping[name]=row
        if text is not None:
            row["_code_text"]=text
    return mapping


def query_inputs(args, out, trigger, stderr, mapping):
    owner, method = trigger.split("::")
    stderr_text = core.read_text(stderr)
    observed = next((m for m in FRAME.finditer(stderr_text)
                     if m[2]==method and m[3].lower()==args.test_source.name.lower()),None)
    if observed is None and args.anchor_line is None:
        raise ValueError("No unique runtime anchor in the supplied source; pass the observed/declaration anchor explicitly")
    owner = args.anchor_owner or (observed[1] if observed is not None else owner)
    line = args.anchor_line or int(observed[4])
    idx = out/"test-ast.jsonl"
    subprocess.run([args.java,str(ROOT/"java/index/FailureQueryAstIndex.java"),str(idx),str(args.test_source)],
                   check=True,timeout=300)
    rows = jsonl(idx)
    methods = [m for m in rows if m.get("record")=="method" and m.get("owner")==owner and m.get("name")==method
               and m["start_line"]<=line<=m["end_line"]]
    if len(methods)!=1 or any(r.get("record")=="diagnostic" for r in rows):
        raise ValueError("Could not uniquely bind the failure anchor to a javac method")
    m = methods[0]
    src = args.test_source.read_text(encoding="utf-8")
    anchor = {"line":line,"source_line":src.splitlines()[line-1]}
    nodes = core.matching_anchor_statements({"anchors":[anchor]},m, rows)
    if not nodes:
        prefix = str(m.get("text") or "")
    else:
        controls = {int(y) for x in nodes for y in x.get("control_parents",[])}
        n = sum(r.get("record")=="statement" and int(r.get("id",-1)) in controls and
                "{" in str(r.get("text") or "") for r in rows)
        prefix = src[int(m["start_offset"]):max(int(x["end_offset"]) for x in nodes)]+"\n"+"\n".join("}" for _ in range(n+1))
    error,_ = core.extract_error(stderr)
    stack,_ = core.extract_production_stack(stderr,{r["class_name"] for r in mapping.values() if r.get("class_name")})
    if not error:
        raise ValueError("Frozen error field unavailable")
    pos = prefix.find("{")
    fields = dict(test_context="void failingTest() "+prefix[pos:] if pos>=0 else prefix,
                  failure_statement=anchor["source_line"].strip(),
                  observed_error=re.sub(r"(?m)^(?:failure|error)=[^|\n]*\|","",error),
                  production_stack=stack)
    return fields, (prefix,error,stack)


def retrieve(model_path, bug, mapping, query_views):
    import torch
    from .unixcoder import UniXcoder
    torch.set_num_threads(4)
    wrapper = UniXcoder(str(model_path)).eval().to("cpu")
    blocks = {}
    def block_keys(text):
        recs = core.token_records(wrapper,text,{})
        for r in recs:
            blocks[r["block_key"]]=r
        return [r["block_key"] for r in recs]
    for r in mapping.values():
        r["code_block_keys"] = block_keys(r.get("_code_text",""))
    query_keys = [k for text in query_views for k in block_keys(text)]
    keys = sorted(blocks,key=lambda k:(blocks[k]["token_count"],k))
    vectors = {}
    for i in range(0,len(keys),16):
        batch = keys[i:i+16]
        vectors.update(zip(batch,core.encode_with_author(wrapper,[blocks[k] for k in batch])))
    candidates = list(mapping.values())
    vq = core.normalized_average([vectors[k] for k in query_keys],[blocks[k]["token_count"] for k in query_keys])
    names,matrix,_ = core.code_matrix_for_bug(bug,candidates,dict(vectors=vectors,block_rows=blocks))
    return core.score_vector(names,matrix,vq,candidates)


def score(model_path, specs):
    import torch
    from transformers import AutoTokenizer, AutoModelForCausalLM
    lock = json.loads((ROOT/"data/model.json").read_text(encoding="utf-8"))
    for r in lock["files"]:
        if r["file"].endswith((".json",".txt",".safetensors")) and sha(model_path/r["file"])!=r["sha256"]:
            raise ValueError("Model artifact differs from frozen digest: "+r["file"])
    torch.set_num_threads(1)
    torch.backends.cuda.matmul.allow_tf32=False
    torch.backends.cuda.matmul.allow_bf16_reduced_precision_reduction=False
    device = torch.device("cuda:0")
    if not torch.cuda.is_bf16_supported() or torch.cuda.mem_get_info(device)[0]<12*2**30:
        raise RuntimeError("Qwen3 4B requires bf16-capable CUDA and 12 GiB free for the frozen run")
    tokenizer = AutoTokenizer.from_pretrained(str(model_path),local_files_only=True)
    tokenizer.padding_side="left"
    if tokenizer.pad_token_id is None:
        tokenizer.pad_token=tokenizer.eos_token
    labels = [tokenizer.encode(s,add_special_tokens=False) for s in ("yes","no")]
    assert all(len(x)==1 for x in labels)
    model = AutoModelForCausalLM.from_pretrained(str(model_path),torch_dtype=torch.bfloat16,
                        attn_implementation="sdpa",local_files_only=True).to(device).eval()
    model.config.use_cache=False
    torch.cuda.synchronize(device)
    runner = ModelRunner(tokenizer,model,device,labels[0][0],labels[1][0])
    items,meta,lengths = [],[],{}
    for s in specs:
        plan = make_plan(tokenizer,s)
        name = s["entity"]
        lengths[name] = plan["code_tokens"]
        if lengths[name]<=0:
            raise ValueError(f"Empty candidate document: {name}")
        for c in plan["chunks"]:
            assert len(c["joint_ids"])<=plan["budget_tokens"]
            items.append(dict(ids=c["joint_ids"]))
            meta.append(name)
    best = {}
    for i in range(0,len(items),8):
        vals = runner.infer(items[i:i+8])
        for name,(yes,no,p) in zip(meta[i:i+8],vals,strict=True):
            assert math.isfinite(yes) and math.isfinite(no) and 0<=p<=1
            if name not in best or p>best[name]["rawP"]:  # first max, even for saturated scores
                best[name]=dict(rawP=p,s=yes-no,L=lengths[name])
    return best


def ranks(domain, hits, scored):
    assert len(set(domain))==len(domain) and set(scored)<=set(domain) and set(hits)<=set(domain)
    result = {}
    for arm in ("e","a","f","b"):
        keyed = {m:(r["s"]+math.log(r["L"]) if arm in ("e","f") else r["rawP"]) for m,r in scored.items()}
        result[arm] = sorted(domain,key=lambda m:(2 if m not in keyed else
                           int(arm in ("e","a") and m not in hits),-keyed.get(m,0.),m))
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--case",required=True)
    p.add_argument("--java",default="java")
    p.add_argument("--javac",default="javac")
    p.add_argument("--jar",default="jar")
    p.add_argument("--classpath",required=True,help="absolute paths to ASM, production classes, test classes, and test dependencies")
    p.add_argument("--junit3",action="store_true")
    p.add_argument("--triggers",type=Path)
    p.add_argument("--trigger")
    p.add_argument("--anchor-line",type=int)
    p.add_argument("--anchor-owner")
    for k in ("source-root","production-classes","test-source","unixcoder","qwen","out"):
        p.add_argument("--"+k,required=True,type=lambda x:Path(x).resolve())
    args = p.parse_args()
    if not (args.triggers or args.trigger):
        p.error("supply --triggers or --trigger")
    os.environ.update(TRANSFORMERS_OFFLINE="1",HF_HUB_OFFLINE="1",
                      HF_HUB_DISABLE_TELEMETRY="1",TOKENIZERS_PARALLELISM="false")
    args.out.mkdir(parents=True,exist_ok=False)
    test,stderr,hits = capture(args,args.out)
    mapping = production_methods(args,args.out,hits)
    fields,views = query_inputs(args,args.out,test,stderr,mapping)
    (args.out/"Q0.txt").write_bytes(query(fields).encode())
    sem = retrieve(args.unixcoder,args.case,mapping,views)
    specs,top,pool = core.create_joint_specs(args.case,sorted(mapping),sem,dict.fromkeys(hits,0.0),mapping,fields)
    if not specs:
        raise ValueError("P has no pairable candidate")
    save(args.out/"pool.json",dict(semantic_top60=top,pool=pool,pairable=[s["entity"] for s in specs]))
    scores = score(args.qwen,specs)
    assert scores.keys()=={s["entity"] for s in specs}
    save(args.out/"scores.json",scores)
    save(args.out/"rankings.json",dict(bug=args.case,nD=len(hits),ranks=ranks(sorted(mapping),hits,scores),truth_loaded=False))


if __name__=="__main__":
    main()
