import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit
import zipfile


def prepare(source, output, model_id, version, name, kind, languages, url):
    source = Path(source).resolve(strict=True)
    output = Path(output).resolve()
    if not source.is_dir() or output.is_relative_to(source):
        raise ValueError("Output must be outside the model source directory")
    if output.exists():
        raise ValueError("Output already exists; use a new package version or path")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", model_id):
        raise ValueError("Invalid package ID")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,3}", version) or len(version) > 32:
        raise ValueError("Invalid version")
    if not name.strip() or len(name) > 128:
        raise ValueError("Invalid package name")
    if kind not in {"Translation", "SpeechRecognition", "SpeechSynthesis", "Ocr", "Diarization"}:
        raise ValueError("Invalid model kind")
    if not languages or len(languages) > 200 or len(languages) != len(set(languages)):
        raise ValueError("Invalid language list")
    if any(not re.fullmatch(r"[a-z]{2,3}(?:-[A-Z]{2})?", code) for code in languages):
        raise ValueError("Invalid language code")
    if any(character.isspace() for character in url):
        raise ValueError("URL must not contain whitespace")
    address = urlsplit(url)
    if address.scheme != "https" or not address.hostname or address.username is not None or address.password is not None or address.fragment or address.port not in (None, 443):
        raise ValueError("A public HTTPS package URL is required")
    paths = sorted(source.rglob("*"))
    files = []
    for path in paths:
        if path.is_symlink():
            raise ValueError("Symbolic links are not supported")
        relative = path.relative_to(source)
        if "\\" in relative.as_posix() or ".verified" in relative.parts or ":" in relative.as_posix():
            raise ValueError("Reserved or unsafe archive path")
        if path.is_file():
            files.append(path)
        elif not path.is_dir():
            raise ValueError("Only regular files and directories are supported")
    unpacked = sum(path.stat().st_size for path in files)
    if not 1 <= len(files) <= 10000 or not 1 <= unpacked <= 40 * 1024**3:
        raise ValueError("Package is empty or exceeds installer limits")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="lingua-package-", dir=output.parent) as temporary:
        archive = Path(temporary) / "model.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=3) as package:
            for path in files:
                package.write(path, path.relative_to(source).as_posix())
        with zipfile.ZipFile(archive) as package:
            actual_unpacked = sum(entry.file_size for entry in package.infolist())
        if actual_unpacked != unpacked:
            raise ValueError("Source files changed while packaging")
        archive_bytes = archive.stat().st_size
        if archive_bytes > 20 * 1024**3:
            raise ValueError("Archive exceeds installer limits")
        digest = hashlib.sha256()
        with archive.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        checksum = digest.hexdigest()
        archive.replace(output)
    return dict(id=model_id, version=version, name=name, kind=kind, languages=languages,
                url=url, archiveBytes=archive_bytes, unpackedBytes=unpacked, sha256=checksum)


def main():
    parser = argparse.ArgumentParser(description="Package Android-compatible model files and print a catalog entry")
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--id", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--kind", required=True)
    parser.add_argument("--languages", required=True, nargs="+")
    parser.add_argument("--url", required=True)
    args = parser.parse_args()
    try:
        entry = prepare(args.source, args.output, args.id, args.version, args.name, args.kind, args.languages, args.url)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps(entry, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
