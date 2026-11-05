from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
tokens = re.compile(
    r'(?P<string>"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27)'
    r'|(?P<comment>//[^\n]*|/\*[\s\S]*?\*/)'
)
violations = []
for path in sorted(root.rglob("*")):
    if any(part in {"build", ".gradle", ".idea", ".git"} for part in path.relative_to(root).parts):
        continue
    if path.suffix not in {".kt", ".kts", ".xml"} or not path.is_file():
        continue
    source = path.read_text(encoding="utf-8")
    matches = re.finditer(r"<!--", source) if path.suffix == ".xml" else (
        token for token in tokens.finditer(source) if token.lastgroup == "comment"
    )
    for match in matches:
        line = source.count("\n", 0, match.start()) + 1
        violations.append(f"{path.relative_to(root)}:{line}: comments are forbidden")
    if path.suffix != ".xml":
        code = tokens.sub(lambda token: " " * len(token.group()), source)
        for match in re.finditer(r"!!|\bGlobalScope\b", code):
            line = source.count("\n", 0, match.start()) + 1
            violations.append(f"{path.relative_to(root)}:{line}: forbidden construct {match.group()}")
if violations:
    print("\n".join(violations), file=sys.stderr)
    sys.exit(1)
print("Source policy passed")
