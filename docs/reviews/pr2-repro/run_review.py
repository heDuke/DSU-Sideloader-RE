#!/usr/bin/env python3
"""Run the original JVM tests and review regressions against a supplied checkout."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ARTIFACTS = {
    "compiler.jar": "org/jetbrains/kotlin/kotlin-compiler-embeddable/1.8.20/kotlin-compiler-embeddable-1.8.20.jar",
    "stdlib.jar": "org/jetbrains/kotlin/kotlin-stdlib/1.8.20/kotlin-stdlib-1.8.20.jar",
    "reflect.jar": "org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar",
    "script-runtime.jar": "org/jetbrains/kotlin/kotlin-script-runtime/1.8.20/kotlin-script-runtime-1.8.20.jar",
    "trove.jar": "org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar",
    "annotations.jar": "org/jetbrains/annotations/13.0/annotations-13.0.jar",
    "junit.jar": "junit/junit/4.13.2/junit-4.13.2.jar",
    "hamcrest.jar": "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
    "compress.jar": "org/apache/commons/commons-compress/1.23.0/commons-compress-1.23.0.jar",
    "xz.jar": "org/tukaani/xz/1.9/xz-1.9.jar",
    "coroutines.jar": "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar",
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path, help="Source checkout; reviewed revision is 03d11904")
    parser.add_argument("--deps", type=Path, default=Path(tempfile.gettempdir()) / "dsu-pr2-review-deps")
    args = parser.parse_args()
    source = args.checkout.resolve()
    here = Path(__file__).resolve().parent
    head = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    print("Testing source revision:", head, flush=True)
    args.deps.mkdir(parents=True, exist_ok=True)
    for name, path in ARTIFACTS.items():
        target = args.deps / name
        if not target.exists():
            partial = target.with_suffix(".part")
            try:
                with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + path, timeout=60) as response, partial.open("wb") as output:
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
                partial.replace(target)
            finally:
                partial.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="dsu-pr2-review-") as work:
        classes = Path(work) / "classes"
        classes.mkdir()
        production = source / "app/src/main/java/vegabobo/dsusideloader"
        cp = os.pathsep.join([str(classes)] + [str(args.deps / name) for name in ARTIFACTS])
        subprocess.run(["java", "com.sun.tools.javac.Main", "-source", "17", "-target", "17", "-d", str(classes), str(production / "installer/root/SparseInputStream.java")], check=True)
        kotlin = [production / path for path in [
            "installer/root/DsuInstallPipeline.kt", "installer/root/DsuPartitionPolicy.kt",
            "preparation/GzipImageSize.kt", "preparation/InstallationStep.kt",
            "installer/privileged/SdCardRemounter.kt",
        ]]
        kotlin += list((source / "app/src/test").rglob("*.kt"))
        kotlin += sorted((here / "stubs").glob("*.kt")) + [here / "ReviewRegressionTest.kt"]
        subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp, "-d", str(classes)] + list(map(str, kotlin)), check=True)
        runner = ["java", "-cp", cp, "org.junit.runner.JUnitCore"]
        baseline = subprocess.run(runner + [
            "vegabobo.dsusideloader.installer.root.DsuInstallPipelineTest",
            "vegabobo.dsusideloader.installer.root.DsuPartitionPolicyTest",
            "vegabobo.dsusideloader.preparation.GzipImageSizeTest",
            "vegabobo.dsusideloader.installer.privileged.PendingSdVolumesTest",
        ])
        regressions = subprocess.run(runner + ["review.ReviewRegressionTest"])
        print("At 03d11904: expected baseline=45 passing; regression probes=3 failing. A fixed revision should make the probes pass.", flush=True)
        return baseline.returncode or regressions.returncode

if __name__ == "__main__":
    raise SystemExit(main())
