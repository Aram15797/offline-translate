import argparse
import base64
import json
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--private-key", required=True, type=Path)
parser.add_argument("--payload", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--key-id", default="development-v1")
parser.add_argument("--public-key-output", type=Path)
parser.add_argument("--key-id-output", type=Path)
args = parser.parse_args()
payload = args.payload.read_bytes()
json.loads(payload)
with tempfile.TemporaryDirectory(prefix="lingua-sign-") as directory:
    signature_path = Path(directory) / "signature.bin"
    subprocess.run([
        "openssl", "dgst", "-sha256", "-sign", str(args.private_key),
        "-out", str(signature_path), str(args.payload),
    ], check=True)
    envelope = {
        "keyId": args.key_id,
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": base64.b64encode(signature_path.read_bytes()).decode("ascii"),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(envelope, separators=(",", ":")) + "\n", encoding="utf-8")
if args.public_key_output:
    args.public_key_output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        "openssl", "pkey", "-in", str(args.private_key), "-pubout", "-outform", "DER",
        "-out", str(args.public_key_output),
    ], check=True)
if args.key_id_output:
    args.key_id_output.parent.mkdir(parents=True, exist_ok=True)
    args.key_id_output.write_text(args.key_id + "\n", encoding="utf-8")
print(f"Signed catalog written to {args.output}")
