"""Frozen Q0 formatter, instruction, token planning and batch inference."""
import hashlib
import math
import time

INSTRUCTION=('Given a Java test execution up to an observed failure, retrieve method implementations '
             'relevant to explaining the violated behavior, including earlier state-changing operations. '
             'Assess the actual conditions and operations in the code, not shared names alone. '
             'A method that merely observes or reports an error is not necessarily its source.')


PREFIX='<|im_start|>system\nJudge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be "yes" or "no".<|im_end|>\n<|im_start|>user\n'


SUFFIX='<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n'


def query(fields,detail=True):
    q='Test execution context:\n'+fields['test_context']
    if detail:
        q+='\nObserved failure statement:\n'+fields['failure_statement']
        q+='\nObserved error:\n'+(fields['observed_error'] or '[not observed]')
        q+='\nProduction stack:\n'+(fields['production_stack'] or '[not observed]')
    return q


def body(q,entity,code_chunk):
    return f'<Instruct>: {INSTRUCTION}\n<Query>: {q}\n<Document>: Java method {entity}\n{code_chunk}'


def query_text(fields: dict, detail: bool) -> str:
    return query(fields, detail=detail)


def prompt_text(query: str, entity: str, code_chunk: str) -> str:
    return PREFIX + body(query, entity, code_chunk) + SUFFIX


def digest_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def make_plan(tokenizer, spec: dict) -> dict:
    q_joint = query_text(spec["query_fields"], True)
    q_context = query_text(spec["query_fields"], False)
    base_joint = len(tokenizer(prompt_text(q_joint, spec["entity"], ""), add_special_tokens=False)["input_ids"])
    base_context = len(tokenizer(prompt_text(q_context, spec["entity"], ""), add_special_tokens=False)["input_ids"])
    base_max = max(base_joint, base_context)
    budget = 4096 if base_max + 1 < 4096 else 8192
    code_ids = tokenizer(spec["code_text"], add_special_tokens=False)["input_ids"]
    cap = min(2048, budget - base_max)
    if cap <= 0:
        budget = 8192
        cap = min(2048, budget - base_max)
    if cap <= 0:
        raise ValueError(f"No code budget for {spec['bug']} {spec['entity']}: base={base_max}")

    while True:
        chunks = []
        for start in range(0, max(1, len(code_ids)), cap):
            end = min(len(code_ids), start + cap)
            ids = code_ids[start:end]
            text = tokenizer.decode(ids, skip_special_tokens=False, clean_up_tokenization_spaces=False)
            pj = prompt_text(q_joint, spec["entity"], text)
            pc = prompt_text(q_context, spec["entity"], text)
            ij = tokenizer(pj, add_special_tokens=False)["input_ids"]
            ic = tokenizer(pc, add_special_tokens=False)["input_ids"]
            chunks.append({
                "chunk_index": len(chunks),
                "code_start_token": start,
                "code_end_token": end,
                "code_tokens": end - start,
                "joint_prompt": pj,
                "context_prompt": pc,
                "joint_ids": ij,
                "context_ids": ic,
                "joint_input_tokens": len(ij),
                "context_input_tokens": len(ic),
            })
        max_len = max(max(x["joint_input_tokens"], x["context_input_tokens"]) for x in chunks)
        if max_len <= budget or cap <= 1:
            break
        cap = max(1, cap - max(1, max_len - budget))
    return {
        "query_joint": q_joint,
        "query_context_only": q_context,
        "query_joint_sha256": digest_text(q_joint),
        "query_context_only_sha256": digest_text(q_context),
        "query_joint_tokens": len(tokenizer(q_joint, add_special_tokens=False)["input_ids"]),
        "query_context_only_tokens": len(tokenizer(q_context, add_special_tokens=False)["input_ids"]),
        "base_joint_tokens": base_joint,
        "base_context_only_tokens": base_context,
        "budget_tokens": budget,
        "chunk_cap_tokens": cap,
        "code_tokens": len(code_ids),
        "chunks": chunks,
    }


class ModelRunner:
    def __init__(self, tokenizer, model, device, yes_id, no_id):
        self.tokenizer = tokenizer
        self.model = model
        self.device = device
        self.yes_id = yes_id
        self.no_id = no_id
        self.calls = 0
        self.tokens = 0
        self.elapsed = 0.0

    def infer(self, items):
        if not items:
            return []
        import torch

        out = []
        start_time = time.perf_counter()
        batch = []
        token_sum = 0
        for item in items:
            length = len(item["ids"])
            if batch and (token_sum + length > 4096 or len(batch) >= 8):
                out.extend(self._infer_batch(batch))
                batch, token_sum = [], 0
            batch.append(item)
            token_sum += length
        if batch:
            out.extend(self._infer_batch(batch))
        self.elapsed += time.perf_counter() - start_time
        return out

    def _infer_batch(self, batch):
        import torch

        encoded = self.tokenizer.pad(
            [{"input_ids": x["ids"]} for x in batch],
            padding=True,
            return_tensors="pt",
        )
        encoded = {k: v.to(self.device) for k, v in encoded.items()}
        with torch.inference_mode():
            output = self.model(**encoded, use_cache=False, logits_to_keep=1)
        logits = output.logits[:, -1, :]
        diff = logits[:, self.yes_id] - logits[:, self.no_id]
        probabilities = torch.sigmoid(diff.float()).detach().cpu().numpy()
        yes = logits[:, self.yes_id].detach().float().cpu().numpy()
        no = logits[:, self.no_id].detach().float().cpu().numpy()
        self.calls += 1
        self.tokens += sum(len(x["ids"]) for x in batch)
        return [(float(yes[i]), float(no[i]), float(probabilities[i])) for i in range(len(batch))]
