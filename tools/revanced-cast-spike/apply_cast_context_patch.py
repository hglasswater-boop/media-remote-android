from pathlib import Path

path = Path("patches/src/main/kotlin/app/morphe/patches/shared/gms/GmsCoreSupportPatch.kt")
text = path.read_text(encoding="utf-8")
needle = """        val earlyReturnFingerprints = mutableListOf(\n            castContextFetchFingerprint,\n            googlePlayUtilityFingerprint,\n            serviceCheckFingerprint,\n        )\n"""
replacement = """        val earlyReturnFingerprints = mutableListOf(\n            googlePlayUtilityFingerprint,\n            serviceCheckFingerprint,\n        )\n"""
count = text.count(needle)
if count != 1:
    raise SystemExit(f"expected exactly one CastContext early-return block, found {count}")
path.write_text(text.replace(needle, replacement), encoding="utf-8")
