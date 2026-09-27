# TASK
Review this java class for security weaknesses only.
Look for: unvalidated input reaching queries or file paths, secrets in code,
missing authorization checks, unsafe deserialization, information leaking
through exception messages or logs.
If you find nothing, return an empty array.

# CHECK SPECIFICALLY FOR
{{checklist}}

# OUTPUT FORMAT
Return ONLY a JSON array, no prose, no markdown fences.
Each element:
{"line": <int>, "method": "<method name>",
"severity": "HIGH|MEDIUM|LOW",
"confidence": "CONFIRMED|INFERRED",
"title": "<short label>", "detail": "<one paragraph in Turkish>"}

Use CONFIRMED only when the finding is directly visible in the code below.
Use INFERRED when it depends on context you cannot see.
The line number must match the line numbers of the code below.

# CODE
{{code}}