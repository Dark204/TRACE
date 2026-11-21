"""Exact revision-local JVM method to javac declaration binding."""
from __future__ import annotations
from collections import defaultdict
from pathlib import Path
import hashlib
import ntpath
import re


def normalized_path(path):
    return ntpath.normcase(ntpath.normpath(str(path)))


def inside(path, root):
    path, root = normalized_path(path), normalized_path(root)
    return path == root or path.startswith(root.rstrip('\\') + '\\')


def descriptor_types(descriptor):
    """Read array dimensions separately from reference-name byte length."""
    primitive = dict(zip('BCDFIJSZV', ['byte','char','double','float','int','long','short','boolean','void']))
    def one(i):
        dims = 0
        while descriptor[i] == '[':
            dims += 1; i += 1
        if descriptor[i] == 'L':
            end = descriptor.index(';', i)
            name, i = descriptor[i+1:end].replace('/', '.'), end+1
        else:
            name = primitive[descriptor[i]]; i += 1
        return name + '[]'*dims, i
    if not descriptor.startswith('('):
        raise ValueError(descriptor)
    params=[]; i=1
    while descriptor[i] != ')':
        value,i=one(i); params.append(value)
    ret,end=one(i+1)
    if end != len(descriptor):
        raise ValueError(descriptor)
    return params,ret


def simple_type(value):
    depth=0; out=[]
    for c in value.replace('...', '[]'):
        if c=='<': depth+=1
        elif c=='>': depth-=1
        elif not depth: out.append(c)
    value=''.join(out).replace(' ', '')
    dims=0
    while value.endswith('[]'):
        dims+=1; value=value[:-2]
    return value.rsplit('.',1)[-1].rsplit('$',1)[-1]+'[]'*dims


def type_shape_match(row, descriptor):
    params,ret=descriptor_types(descriptor)
    return ([simple_type(x) for x in row.get('parameters',[])] == [simple_type(x) for x in params]
            and (row.get('node_kind')=='CONSTRUCTOR' or simple_type(row.get('return_type',''))==simple_type(ret)))


def exact_source_type(value, target, row, known_owners):
    """No simple-name-only proof for a declaration lacking debug lines."""
    value=re.sub(r'\s+', '', value.replace('...', '[]'))
    depth=0; out=[]
    for c in value:
        if c=='<': depth+=1
        elif c=='>': depth-=1
        elif not depth: out.append(c)
    value=''.join(out)
    source_dims=target_dims=0
    while value.endswith('[]'): source_dims+=1; value=value[:-2]
    while target.endswith('[]'): target_dims+=1; target=target[:-2]
    if source_dims!=target_dims: return False
    if value==target or value==target.replace('$','.'): return True
    if '.' in value: return False
    imports=row.get('imports',[])
    explicit=[x for x in imports if not x.endswith('.*') and x.rsplit('.',1)[-1]==value]
    if explicit: return explicit == [target] or explicit == [target.replace('$','.')]
    pkg=row['owner'].rsplit('.',1)[0]
    local=pkg+'.'+value
    if local in known_owners: return target==local
    possible={x[:-1]+value for x in imports if x.endswith('.*') and x[:-1]+value in known_owners}
    if target.startswith('java.lang.') and target.rsplit('.',1)[-1]==value:
        possible.add(target)
    return possible=={target}


class RevisionBinder:
    def __init__(self, runner, source_rows, specs, class_records):
        self.runner=runner
        self.map_original=runner.map_candidate
        self.specs={x['bug']:x for x in specs}
        self.by_bug={b:defaultdict(list) for b in self.specs}
        self.known=defaultdict(set)
        for row in class_records.values(): self.known[row['bug']].add(row['owner'])
        roots=[(b,normalized_path(x['source_root'])) for b,x in self.specs.items()]
        for row in source_rows:
            if row.get('record')!='declaration': continue
            src=normalized_path(row['source'])
            matches=[b for b,root in roots if src.startswith(root.rstrip('\\')+'\\')]
            if len(matches)>1: raise ValueError('Overlapping source roots: '+row['source'])
            for bug in matches: self.by_bug[bug][row['owner'],row['name']].append(row)
        # javac emits <init>, not a Java source simple/full class name.
        runner.ast_name_for_method=lambda owner,method:method
        runner.source_type_match=type_shape_match

    def bind(self, identity, bug, class_by_owner, ignored_global_lookup=None):
        parsed=self.runner.parse_entity(identity)
        if not parsed: return self.map_original(identity,bug,class_by_owner,{})
        owner,name,desc=parsed
        cls=class_by_owner.get(owner,{})
        methods=[m for m in cls.get('methods',[]) if m['name']==name and m['descriptor']==desc]
        lookup=self.by_bug[bug]
        candidates=list(lookup.get((owner,name),[]))
        if methods:
            method=methods[0]
            # SourceFile is revision-local, including package-private top-level classes.
            source_file=cls.get('source_file')
            if source_file: candidates=[r for r in candidates if Path(r['source']).name==source_file]
            if method.get('synthetic') or method.get('bridge'):
                row,_=self.map_original(identity,bug,class_by_owner,{})
                row['binding_reason']='unsupported_compiler_generated_bridge_or_synthetic'
                return row,None
            lines={int(x['line']) for x in method.get('line_table',[])}
            if name=='<clinit>':
                candidates=[r for r in candidates if any(r['start_line']<=line<=r['end_line'] for line in lines)]
            elif lines:
                entry=min(method['line_table'],key=lambda x:x['offset'])['line']
                at_entry=[r for r in candidates if r['start_line']<=entry<=r['end_line']]
                if at_entry: candidates=at_entry
                else: candidates=[r for r in candidates if any(r['start_line']<=line<=r['end_line'] for line in lines)]
                if len(candidates)>1:
                    candidates=[r for r in candidates if type_shape_match(r,desc)]
            else:
                params,ret=descriptor_types(desc)
                candidates=[r for r in candidates if len(params)==len(r.get('parameters',[]))
                    and all(exact_source_type(v,t,r,self.known[bug]) for v,t in zip(r['parameters'],params))
                    and (name=='<init>' or exact_source_type(r.get('return_type',''),ret,r,self.known[bug]))]
        local={(owner,name):candidates}
        row,text=self.map_original(identity,bug,class_by_owner,local)
        row['source_revision']=bug
        row['binding_scope']='same_bug_source_root_and_SourceFile'
        if text is not None:
            if not inside(row['source_path'],self.specs[bug]['source_root']):
                raise ValueError('Cross-revision source binding: '+identity)
            row['binding_reason']='revision_isolated_'+row['binding_reason']
            row['binding_source_paths']=sorted({r['source'] for r in candidates})
            if len(row['binding_source_paths'])!=1: raise ValueError('Cross-file aggregate: '+identity)
            row['binding_ast_spans']=[{k:r[k] for k in ('source','source_sha256','owner','name','node_kind','start_line','end_line','start_offset','end_offset')} for r in candidates]
            if name=='<clinit>':
                row['static_ast_rows']=row['binding_ast_spans']
        return row,text
