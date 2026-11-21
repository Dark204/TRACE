# Copyright (c) Microsoft Corporation.
# Licensed under the MIT license (see THIRD_PARTY.md).
import torch
import torch.nn as nn
from transformers import RobertaTokenizer, RobertaModel, RobertaConfig

class UniXcoder(nn.Module):
    def __init__(self, model_name):
        """
            Build UniXcoder.

            Parameters:

            * `model_name`- huggingface model card name. e.g. microsoft/unixcoder-base
        """        
        super(UniXcoder, self).__init__()
        self.tokenizer = RobertaTokenizer.from_pretrained(model_name)
        self.config = RobertaConfig.from_pretrained(model_name)
        self.config.is_decoder = True
        self.model = RobertaModel.from_pretrained(model_name, config=self.config)
        
        self.register_buffer("bias", torch.tril(torch.ones((1024, 1024), dtype=torch.uint8)).view(1,1024, 1024))
        self.lm_head = nn.Linear(self.config.hidden_size, self.config.vocab_size, bias=False)
        self.lm_head.weight = self.model.embeddings.word_embeddings.weight
        self.lsm = nn.LogSoftmax(dim=-1)
        
        self.tokenizer.add_tokens(["<mask0>"],special_tokens=True)

    def forward(self, source_ids):   
        """ Obtain token embeddings and sentence embeddings """
        mask = source_ids.ne(self.config.pad_token_id)
        token_embeddings = self.model(source_ids,attention_mask = mask.unsqueeze(1) * mask.unsqueeze(2))[0]
        sentence_embeddings = (token_embeddings * mask.unsqueeze(-1)).sum(1) / mask.sum(-1).unsqueeze(-1)
        return token_embeddings, sentence_embeddings
