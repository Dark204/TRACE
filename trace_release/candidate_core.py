"""Frozen source binding, AST anchor selection and candidate retrieval operators."""
from __future__ import annotations
from collections import defaultdict
import hashlib
import json
import re
from pathlib import Path
from typing import Any
import numpy as np

ENCODING_RULE = "unixcoder_author_encoder_only_v2_cls_encoder_sep_content_sep_nonpadding_mean"

def parse_entity(entity: str) -> tuple[str, str, str] | None:
    m = re.match(r"^(?P<class>[^#]+)#(?P<name>[^()]+)(?P<desc>\(.*)$", entity)
    return (m.group("class"), m.group("name"), m.group("desc")) if m else None


MAX_CONTENT_TOKENS = 508


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def text_hash(value: str) -> str:
    return sha_bytes(value.encode("utf-8"))


def ast_name_for_method(owner: str, method: str) -> str:
    return owner.rsplit("$", 1)[-1] if method == "<init>" else method


def line_range(method_record: dict[str, Any]) -> tuple[int, int] | None:
    lines = [int(x["line"]) for x in method_record.get("line_table", []) if x.get("line") is not None]
    return (min(lines), max(lines)) if lines else None


def erase_generic_type(value: str) -> str:
    value = re.sub(r"@[_A-Za-z$][\w$.]*(?:\([^)]*\))?\s*", "", value)
    value = re.sub(r"\b(?:final|volatile|transient)\b\s*", "", value)
    value = value.replace("...", "[]").strip()
    chars: list[str] = []
    depth = 0
    for char in value:
        if char == "<":
            depth += 1
        elif char == ">" and depth:
            depth -= 1
        elif depth == 0:
            chars.append(char)
    value = "".join(chars).strip()
    parts = value.split()
    if len(parts) > 1 and re.match(r"^[A-Za-z_$][\w$]*$", parts[-1]):
        value = " ".join(parts[:-1])
    return value.replace(" ", "")


def source_simple_type(value: str) -> str:
    value = erase_generic_type(value)
    array = value.endswith("[]")
    if array:
        value = value[:-2]
    value = value.replace("?extends", "Object").replace("?super", "Object").replace("?", "Object")
    value = value.rsplit(".", 1)[-1].rsplit("$", 1)[-1]
    return ("[]" if array else "") + value


def descriptor_params(descriptor: str) -> list[str]:
    if not descriptor.startswith("(") or ")" not in descriptor:
        return []
    value = descriptor[1:descriptor.index(")")]
    out: list[str] = []
    i = 0
    primitive = {"B": "byte", "C": "char", "D": "double", "F": "float", "I": "int", "J": "long", "S": "short", "Z": "boolean"}
    while i < len(value):
        start = i
        while i < len(value) and value[i] == "[":
            i += 1
        if i >= len(value):
            break
        if value[i] in primitive:
            base = primitive[value[i]]; i += 1
        elif value[i] == "L":
            end = value.find(";", i)
            if end < 0: break
            base = value[i + 1:end].replace("/", "."); i = end + 1
        else:
            i += 1; continue
        prefix = "[]" * (i - start - 1)
        out.append(prefix + base.rsplit(".", 1)[-1].rsplit("$", 1)[-1])
    return out


def descriptor_return(descriptor: str) -> str:
    if ")" not in descriptor:
        return ""
    value = descriptor.split(")", 1)[1]
    if value.startswith("L") and value.endswith(";"):
        return value[1:-1].replace("/", ".").rsplit(".", 1)[-1].rsplit("$", 1)[-1]
    primitive = {"V": "void", "B": "byte", "C": "char", "D": "double", "F": "float", "I": "int", "J": "long", "S": "short", "Z": "boolean"}
    return primitive.get(value[-1:], value)


def source_type_match(ast_row: dict[str, Any], descriptor: str) -> bool:
    params = [source_simple_type(x) for x in ast_row.get("parameters", [])]
    target = descriptor_params(descriptor)
    if params != target:
        return False
    if ast_row.get("node_kind") == "CONSTRUCTOR":
        return True
    return source_simple_type(str(ast_row.get("return_type", ""))) == descriptor_return(descriptor)


def normalized_method_text(ast_row: dict[str, Any]) -> str:
    raw = str(ast_row.get("source_text") or "")
    if ast_row.get("node_kind") in {"METHOD", "CONSTRUCTOR"} and "{" in raw:
        opening = raw.find("{")
        return re.sub(r"\s+", " ", raw[:opening]).strip() + "\n" + raw[opening:]
    return raw


def map_candidate(identity: str, bug: str, class_by_owner: dict[str, Any], owner_lookup: dict[tuple[str, str], list[dict[str, Any]]]) -> tuple[dict[str, Any], str | None]:
    parsed = parse_entity(identity)
    if not parsed:
        return {"entity": identity, "semantic_supported": False, "binding_reason": "malformed_compiled_identity"}, None
    owner, method, descriptor = parsed
    class_record = class_by_owner.get(owner)
    base = {"entity": identity, "class_name": owner, "method_name": method, "descriptor": descriptor,
            "observed_dynamic": False, "semantic_supported": False, "source_path": None, "source_sha256": None,
            "source_start_line": None, "source_end_line": None, "source_start_offset": None, "source_end_offset": None,
            "source_text_hash": None, "ast_node": None, "compiler_identity": {"owner": owner, "name": method, "descriptor": descriptor},
            "compiler_metadata": "classfile_method_metadata.read_class", "class_file": None, "class_sha256": None,
            "method_flags": None, "line_table": [], "binding_reason": "unsupported_no_unique_ast_compiler_binding",
            "code_block_keys": []}
    if class_record is None:
        base["binding_reason"] = "unsupported_classfile_owner_not_found"
        return base, None
    base["class_file"] = class_record.get("class_file"); base["class_sha256"] = class_record.get("class_sha256")
    method_records = [x for x in class_record.get("methods", []) if x.get("name") == method and x.get("descriptor") == descriptor]
    if not method_records:
        base["binding_reason"] = "unsupported_no_exact_classfile_method_descriptor"
        return base, None
    compiler = method_records[0]
    base["method_flags"] = {k: compiler.get(k) for k in ["access_flags", "bridge", "synthetic", "static", "abstract", "native", "has_code", "signature"]}
    base["line_table"] = compiler.get("line_table", [])
    ast_name = ast_name_for_method(owner, method)
    candidates = list(owner_lookup.get((owner, ast_name), []))
    if method == "<clinit>":
        candidates = [x for x in candidates if x.get("name") == "<clinit>" and x.get("node_kind") in {"STATIC_BLOCK", "STATIC_FIELD"}]
        if not candidates:
            base["binding_reason"] = "unsupported_classfile_clinit_without_same_owner_static_ast"
            return base, None
        line_span = line_range(compiler)
        in_lines = [x for x in candidates if line_span is None or not (int(x["end_line"]) < line_span[0] or int(x["start_line"]) > line_span[1])]
        chosen = in_lines if in_lines else candidates
        base.update({"semantic_supported": True, "source_path": chosen[0].get("source"), "source_sha256": chosen[0].get("source_sha256"),
                     "source_start_line": min(int(x["start_line"]) for x in chosen), "source_end_line": max(int(x["end_line"]) for x in chosen),
                     "source_start_offset": min(int(x["start_offset"]) for x in chosen), "source_end_offset": max(int(x["end_offset"]) for x in chosen),
                     "ast_node": "STATIC_INITIALIZER_AGGREGATE", "binding_reason": "exact_owner_clinit_line_table_static_field_and_block_aggregate",
                     "static_ast_rows": [{"node_kind": x.get("node_kind"), "start_line": x.get("start_line"), "end_line": x.get("end_line"), "source_sha256": x.get("source_sha256")} for x in chosen]})
        content = "\n".join(str(x.get("source_text") or "") for x in sorted(chosen, key=lambda y: (int(y["start_offset"]), str(y.get("node_kind")))))
        base["source_text_hash"] = text_hash(content)
        base["_source_text"] = content
        return base, content
    line_span = line_range(compiler)
    line_candidates = [x for x in candidates if line_span is None or not (int(x["end_line"]) < line_span[0] or int(x["start_line"]) > line_span[1])]
    if not line_candidates:
        line_candidates = candidates if line_span is None else []
    type_candidates = [x for x in line_candidates if source_type_match(x, descriptor)]
    selected = type_candidates if len(type_candidates) == 1 else (line_candidates if len(line_candidates) == 1 else [])
    if len(selected) != 1:
        base["binding_reason"] = "unsupported_ambiguous_ast_line_type_or_bridge_binding"
        return base, None
    ast = selected[0]
    content = normalized_method_text(ast)
    base.update({"semantic_supported": True, "source_path": ast.get("source"), "source_sha256": ast.get("source_sha256"),
                 "source_start_line": ast.get("start_line"), "source_end_line": ast.get("end_line"),
                 "source_start_offset": ast.get("start_offset"), "source_end_offset": ast.get("end_offset"),
                 "ast_node": ast.get("node_kind"), "binding_reason": "exact_owner_name_descriptor_line_table_unique_ast_type",
                 "source_text_hash": text_hash(content)})
    base["_source_text"] = content
    return base, content


def token_records(wrapper: Any, text: str, prefix: dict[str, Any]) -> list[dict[str, Any]]:
    if not text:
        return []
    raw_tokens = list(wrapper.tokenizer.tokenize(text))
    result: list[dict[str, Any]] = []
    for start in range(0, len(raw_tokens), MAX_CONTENT_TOKENS):
        chunk = raw_tokens[start:start + MAX_CONTENT_TOKENS]
        tokens = [wrapper.tokenizer.cls_token, "<encoder-only>", wrapper.tokenizer.sep_token] + chunk + [wrapper.tokenizer.sep_token]
        ids = [int(x) for x in wrapper.tokenizer.convert_tokens_to_ids(tokens)]
        attention = [1] * len(ids)
        key = sha_bytes(canonical({"rule": ENCODING_RULE, "ids": ids, "attention": attention}).encode("utf-8"))
        result.append({**prefix, "block_key": key, "token_count": len(chunk), "token_start": start,
                       "token_end": start + len(chunk), "token_ids": ids, "attention": attention})
    return result


def encode_with_author(wrapper: Any, records: list[dict[str, Any]]) -> np.ndarray:
    import torch
    pad_id = int(wrapper.config.pad_token_id)
    max_len = max(len(x["token_ids"]) for x in records)
    ids = [x["token_ids"] + [pad_id] * (max_len - len(x["token_ids"])) for x in records]
    model_device = next(wrapper.parameters()).device
    source_ids = torch.tensor(ids, dtype=torch.long, device=model_device)
    with torch.inference_mode():
        _, sentence = wrapper.forward(source_ids)
        sentence = torch.nn.functional.normalize(sentence, p=2, dim=1)
    return sentence.cpu().numpy().astype(np.float32)


def method_for_anchor(anchor: dict[str, Any], ast_rows: list[dict[str, Any]]) -> dict[str, Any] | None:
    owner = str(anchor["owner"])
    name = str(anchor["method_name"])
    start = int(anchor["method_start_line"])
    end = int(anchor["method_end_line"])
    methods = [
        x for x in ast_rows
        if x.get("record") == "method" and x.get("owner") == owner and x.get("name") == name
    ]
    exact = [x for x in methods if int(x.get("start_line", -1)) == start and int(x.get("end_line", -1)) == end]
    if exact:
        return exact[0]
    containing = [x for x in methods if int(x.get("start_line", -1)) <= start and int(x.get("end_line", -1)) >= end]
    return sorted(containing, key=lambda x: int(x["end_offset"]) - int(x["start_offset"]))[0] if containing else None


def matching_anchor_statements(anchor: dict[str, Any], method: dict[str, Any], ast_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    lines = [int(x["line"]) for x in anchor.get("anchors", [])]
    rows = [
        x for x in ast_rows
        if x.get("record") == "statement"
        and x.get("owner") == method.get("owner")
        and x.get("method") == method.get("name")
        and not bool(x.get("is_control"))
    ]
    selected: list[dict[str, Any]] = []
    for line in lines:
        hit = [x for x in rows if int(x["start_line"]) <= line <= int(x["end_line"])]
        if hit:
            selected.append(sorted(hit, key=lambda x: (int(x["end_offset"]) - int(x["start_offset"]), int(x["start_offset"])))[0])
    return selected


def normalized_average(vectors: list[np.ndarray], weights: list[int]) -> np.ndarray | None:
    if not vectors:
        return None
    matrix = np.stack(vectors, axis=0)
    w = np.asarray([max(1, int(x)) for x in weights], dtype=np.float32)
    result = np.average(matrix, axis=0, weights=w)
    norm = float(np.linalg.norm(result))
    return (result / max(norm, np.finfo(float).eps)).astype(np.float32)


def code_matrix_for_bug(bug: str, candidates: list[dict[str, Any]], assets: dict[str, Any]) -> tuple[list[str], np.ndarray, dict[str, int]]:
    entities: list[str] = []
    rows: list[np.ndarray] = []
    positions: dict[str, int] = {}
    for candidate in candidates:
        if not candidate.get("semantic_supported") or not candidate.get("code_block_keys"):
            continue
        keys = [str(x) for x in candidate["code_block_keys"]]
        if any(k not in assets["vectors"] for k in keys):
            raise RuntimeError(f"Missing P150 code vector for {bug} {candidate['entity']}")
        vecs = [assets["vectors"][k] for k in keys]
        weights = [int(assets["block_rows"][k]["token_count"]) for k in keys]
        pooled = normalized_average(vecs, weights)
        if pooled is None:
            continue
        entity = str(candidate["entity"])
        positions[entity] = len(entities)
        entities.append(entity)
        rows.append(pooled)
    matrix = np.stack(rows, axis=0).astype(np.float32) if rows else np.empty((0, 768), dtype=np.float32)
    return entities, matrix, positions


def score_vector(matrix_entities: list[str], matrix: np.ndarray, query: np.ndarray | None,
                 candidates: list[dict[str, Any]]) -> dict[str, float | None]:
    scores = {str(x["entity"]): None for x in candidates}
    if query is None or matrix.size == 0:
        return scores
    values = matrix @ query
    for i, entity in enumerate(matrix_entities):
        scores[entity] = float(values[i])
    return scores


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="latin-1")


def extract_error(stderr: Path) -> tuple[str, dict[str, Any]]:
    if not stderr.exists():
        return "", {"available": False, "path": str(stderr), "reason": "stderr_missing"}
    lines = [line.rstrip("\r\n") for line in read_text(stderr).splitlines()]
    selected: list[str] = []
    for line in lines:
        stripped = line.strip()
        if not stripped or re.match(r"^(at\s+|Tests run:|Results:|Time elapsed:|Surefire|\[INFO\])", stripped):
            continue
        if re.search(r"(?:Exception|Error|Assertion|assertion|expected:|but was:|Caused by:)", stripped, re.I):
            selected.append(stripped)
    return "\n".join(selected), {"available": bool(selected), "path": str(stderr), "line_count": len(selected)}


def extract_production_stack(stderr: Path, owners: set[str]) -> tuple[str, dict[str, Any]]:
    if not stderr.exists():
        return "", {"available": False, "path": str(stderr), "reason": "stderr_missing"}
    frames: list[str] = []
    for line in read_text(stderr).splitlines():
        raw = line.strip()
        match = re.match(r"^at\s+([A-Za-z_$][\w$.]*(?:\$[A-Za-z_$][\w$]*)*)\([^)]*\)", raw)
        if not match:
            continue
        token = match.group(1)
        owner = token.rsplit(".", 1)[0] if "." in token else token
        if owner in owners or any(owner.startswith(x + "$") for x in owners):
            frames.append(raw)
    return "\n".join(frames), {"available": bool(frames), "path": str(stderr), "frame_count": len(frames)}


def create_joint_specs(bug: str, entities: list[str], semantic_scores: dict[str, float | None],
                       dynamic: dict[str, float], mapping: dict[str, dict[str, Any]],
                       query_fields: dict[str, str]) -> tuple[list[dict[str, Any]], list[str], list[str]]:
    semantic_order = sorted((entity for entity, value in semantic_scores.items() if value is not None),
                            key=lambda entity: (-float(semantic_scores[entity]), entity))
    top60 = semantic_order[:60]
    pool = set(dynamic) | set(top60)
    pairable = sorted(entity for entity in pool if entity in mapping and mapping[entity].get("semantic_supported"))
    specs = []
    for index, entity in enumerate(pairable):
        row = mapping[entity]
        code_hash = str(row["source_text_hash"])
        specs.append({"bug": bug, "index": index, "entity": entity,
                      "source_text_hash": code_hash,
                      "code_text": str(row["_code_text"]), "query_fields": query_fields})
    return specs, top60, sorted(pool)


def parse_test_outcome(stdout: str, stderr: str, returncode: int | None) -> dict[str, Any]:
    def number(name: str) -> int | None:
        match = re.search(r"(?m)^" + re.escape(name) + r"=(\d+)", stdout + "\n" + stderr)
        return int(match.group(1)) if match else None
    success_match = re.search(r"(?m)^success=(true|false)", stdout, flags=re.IGNORECASE)
    return {
        "returncode": returncode,
        "run_count": number("run_count"),
        "failure_count": number("failure_count"),
        "error_count": number("error_count"),
        "ignore_count": number("ignore_count"),
        "success_field": None if success_match is None else success_match.group(1).lower() == "true",
        "launcher_success": bool(returncode == 0 and success_match and success_match.group(1).lower() == "true"),
        "failure_text_tail": stderr[-6000:],
    }
