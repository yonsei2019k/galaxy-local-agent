# Product requirements and current boundaries

- Local inference and personal data remain on the phone. Network is used for explicit model/package download, update checks and public extension discovery.
- Fast rule routing and bounded context precede model use. Keep one large model loaded. Benchmark E2B first; select one E4B/Qwen4B quality model after real measurements rather than default-installing three.
- Built-in skills: spreadsheet (CSV/XLSX), document (TXT/Markdown/DOCX), PDF, selected email summarization/reply drafting. Provide Android implementations; desktop Codex skill instructions alone are not an executable phone implementation.
- Default email access is user-shared content/files and drafts through Android Intents, with no automatic sending.
- Background learning means local analysis of usage/corrections and approved memory, not continuous model-weight training. Run under charging/idle/thermal constraints and release the model afterward.
- User opted into optional system app-usage durations via Android Usage Access. Later explicitly requested Meditong Link, KakaoTalk and Telegram notification receipt/replies and direct screen controls. Provide separate per-app switches and system grants; no silent collection from disabled apps. Notification bodies stay in a bounded session inbox; deterministic, scoped reply rules are separately configured.
- Recommend extensions by ranking a public catalog locally against private task statistics. Never send personal histories, app usage, document contents or memories to GitHub search.
- Every external installation/update must verify source, format, integrity/signature, permissions, network access and compatibility. Unsupported executable/native packages fail closed. Static checks do not guarantee absence of malicious behavior.
- Trusted signed channel updates within existing permissions may apply automatically. New permissions, new publishers or unverifiable packages require review.
- Product brand: ZipHap.Agent; premium readable native UI, adaptive icon and restrained system-respecting motion. Preserve installed package/signing identity.

Implementation status is tracked in PROJECT_STATE.md. This requirement list does not claim the features are complete.
