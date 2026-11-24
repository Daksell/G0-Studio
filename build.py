import argparse
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


ROOT = Path(__file__).resolve().parent
WORKSPACE_SDK = ROOT.parent.parent / "work" / "build-tools"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
EXE = ".exe" if os.name == "nt" else ""


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def default_sdk():
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured)
    if WORKSPACE_SDK.is_dir():
        return WORKSPACE_SDK
    local = os.environ.get("LOCALAPPDATA")
    return Path(local) / "Android" / "Sdk" if local else None


def java_tools(jdk):
    if jdk is None and os.environ.get("JAVA_HOME"):
        jdk = Path(os.environ["JAVA_HOME"])
    if jdk is None:
        jdk = next((WORKSPACE_SDK / "jdk").glob("jdk-*"), None)
    return (jdk / "bin") if jdk else Path()


def build(args, folder):
    if args.sdk is None:
        raise SystemExit("Provide an Android SDK with --sdk or ANDROID_SDK_ROOT.")
    android = args.sdk / "platforms" / "android-35" / "android.jar"
    if not android.is_file():
        android = args.sdk / "platform" / "android-35" / "android.jar"
    tools = args.build_tools or args.sdk / "build-tools" / "35.0.0"
    if not tools.is_dir() and not args.build_tools:
        tools = args.sdk / "build" / "android-15"
    aapt2 = tools / f"aapt2{EXE}"
    zipalign = tools / f"zipalign{EXE}"
    d8 = tools / "lib" / "d8.jar"
    for required in (android, aapt2, zipalign, d8):
        if not required.is_file():
            raise SystemExit(f"Required Android SDK file missing: {required}")

    jdk = java_tools(args.jdk)
    manifest = ROOT / "AndroidManifest.xml"
    version = ET.parse(manifest)
    if args.version_code is not None:
        if not 1 <= args.version_code <= 2100000000:
            raise SystemExit("--version-code must be between 1 and 2100000000.")
        ET.register_namespace("android", ANDROID_NS)
        version.getroot().set(f"{{{ANDROID_NS}}}versionCode", str(args.version_code))
        manifest = folder / manifest.name
        version.write(manifest, encoding="utf-8", xml_declaration=True)

    classes = folder / "classes"
    dex = folder / "dex"
    classes.mkdir()
    dex.mkdir()
    compiled_res = folder / "resources.zip"
    res_apk = folder / "resources.apk"
    run(aapt2, "compile", "--dir", ROOT / "res", "-o", compiled_res)
    run(aapt2, "link", "-I", android, "--manifest", manifest, "-A", ROOT / "assets", "-o", res_apk, compiled_res)
    run(jdk / f"javac{EXE}", "-source", "8", "-target", "8", "-encoding", "UTF-8", "-cp", android,
        "-d", classes, *sorted((ROOT / "src").rglob("*.java")))
    jar = folder / "classes.jar"
    run(jdk / f"jar{EXE}", "cf", jar, "-C", classes, ".")
    run(jdk / f"java{EXE}", "-cp", d8, "com.android.tools.r8.D8", "--min-api", "26", "--lib", android,
        "--output", dex, jar)

    unsigned = folder / "unsigned.apk"
    with ZipFile(res_apk) as resources, ZipFile(unsigned, "w", ZIP_DEFLATED) as apk:
        for name in resources.namelist():
            compression = ZIP_STORED if name == "resources.arsc" else ZIP_DEFLATED
            apk.writestr(name, resources.read(name), compress_type=compression)
        for file in sorted(dex.glob("*.dex")):
            apk.write(file, file.name)

    suffix = "" if args.keystore else "-unsigned"
    output = (args.output or ROOT / "build" / f"G0Studio-0.4.5{suffix}.apk").resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    aligned = folder / "aligned.apk" if args.keystore else output
    run(zipalign, "-f", "4", unsigned, aligned)
    if args.keystore:
        signer = tools / "lib" / "apksigner.jar"
        sign = [jdk / f"java{EXE}", "-jar", signer, "sign", "--ks", args.keystore]
        if args.key_alias:
            sign.extend(("--ks-key-alias", args.key_alias))
        if "G0STUDIO_KS_PASS" in os.environ:
            sign.extend(("--ks-pass", "env:G0STUDIO_KS_PASS"))
        if "G0STUDIO_KEY_PASS" in os.environ:
            sign.extend(("--key-pass", "env:G0STUDIO_KEY_PASS"))
        run(*sign, "--out", output, aligned)
        run(jdk / f"java{EXE}", "-jar", signer, "verify", output)
    run(zipalign, "-c", "4", output)
    print(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build G0 Studio 0.4.5")
    parser.add_argument("--sdk", type=Path, default=default_sdk())
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--jdk", type=Path)
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--key-alias")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--version-code", type=int)
    options = parser.parse_args()
    with TemporaryDirectory(prefix="g0studio-") as temp:
        build(options, Path(temp))
