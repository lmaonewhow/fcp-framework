"""Single-owner native JVM transition helpers for disposable acceptance only."""
from __future__ import annotations
import subprocess


def stop_host(process, timeout=35):
    """Never start a replacement before the previous JVM has actually exited."""
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=timeout)
        raise RuntimeError('Native JVM required forced termination; replacement was not started')


def replace_host(stop, prepare, start):
    """prepare may run Spring tests against the same isolated runtime roots."""
    stop()
    artifact = prepare()
    start(artifact)
    return artifact
