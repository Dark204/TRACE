# Sources of the comparison ranks

- [BoostN, Ochiai and SBIR method rankings](https://github.com/ZJU-CTAG/FlexFL/tree/f221e88bd10bc1d908469a36a71fda0cfa8c2dd2/FlexFL/data/FL_results): first matching method in the ordered CSV.
- [Agent4SR](https://github.com/ZJU-CTAG/FlexFL/tree/f221e88bd10bc1d908469a36a71fda0cfa8c2dd2/Results/Llama3_Defects4J_SR): numbered method slots in the final assistant response. Empty, incomplete and non-method slots do not shift the remaining positions.
- [Full FlexFL](https://github.com/ZJU-CTAG/FlexFL/tree/f221e88bd10bc1d908469a36a71fda0cfa8c2dd2/Results/Llama3_Defects4J_All): the ordered final recommendations, through five inspections.
- [AutoFL](https://github.com/coinse/autofl/tree/477190e2513b10be2b6079f6fc1ca132ab839e60/combined_fl_results): GPT-3.5 R1–R5, GPT-4 R1–R2, and LLM+Test (five runs). Use the authors' combined ranks for the common targets. For targets outside the summary, use the released predictions and the authors' scoring rule.

All methods are evaluated against the same repaired methods, using declaring class, method name, and parameter types. A missing baseline key means the configuration is outside the panel; a null means no matching predicted method. A bug with no TRACE ranking remains in the denominator. The full case roster, prediction files and matched ranks are verified against `seal.json`. No model weights, raw dialogues or source checkouts are included.
