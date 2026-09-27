# TASK
Review this java class for readability and maintainability only.
Do not report performance issues.
Look for: methods doing more than one thing, duplicated logic, unclear names,
deep nesting, missing error handling.

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