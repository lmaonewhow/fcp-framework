"""Run independent native acceptance modules once, without weakening their gate.

Only the caller's successfully built/live native JVM and completed baseline acceptance
permit execution. Module artifacts remain owned by their fixtures. Any exception,
non-PASS return or lost JVM makes the overall report FAIL; later PASS cannot erase it.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Callable, Iterable
from ci_ingestion import safe_diagnostic


@dataclass(frozen=True)
class NativeModule:
    name: str
    verify: Callable[[], dict]
    summary: Callable[[dict], dict]


def _diagnostic(error, secrets_provider):
    try:
        return safe_diagnostic(str(error), secrets_provider())
    except Exception:
        # Sanitization is fail-closed too: never fall back to the raw exception.
        return 'Diagnostic could not be sanitized; inspect the module artifact'


def run_native_modules(report: dict, modules: Iterable[NativeModule], *,
                       prerequisites_passed: bool, host_alive: Callable[[], bool],
                       secrets_provider: Callable[[], Iterable[str]]):
    modules = tuple(modules)
    if len({module.name for module in modules}) != len(modules):
        raise ValueError('Native acceptance module names must be unique')
    unavailable = not prerequisites_passed
    failed, skipped = [], []
    for module in modules:
        report[module.name] = dict(status='NOT_RUN')

    def alive():
        try:
            return host_alive() is True
        except Exception as error:
            report['nativeModuleHostError'] = _diagnostic(type(error).__name__+': '+str(error), secrets_provider)
            return False

    for module in modules:
        if unavailable or not alive():
            unavailable = True
            reason = 'BASELINE_NOT_PASSED' if not prerequisites_passed else 'NATIVE_JVM_NOT_RUNNING'
            report[module.name] = dict(status='NOT_RUN', reason=reason)
            skipped.append(module.name)
            report['status'] = 'FAIL'
            continue
        try:
            result = module.verify()  # Exactly once. Fixture assertions/artifacts are unchanged.
            if not isinstance(result, dict) or result.get('status') != 'PASS':
                detail = result.get('error', 'Module returned no PASS result') if isinstance(result, dict) else 'Module returned no result object'
                raise RuntimeError(str(detail))
            summary = module.summary(result)
            if not isinstance(summary, dict) or summary.get('status') != 'PASS':
                raise RuntimeError('Module summary did not contain a PASS result')
            report[module.name] = dict(summary)
        except Exception as error:
            report[module.name] = dict(status='FAIL', errorType=type(error).__name__,
                                       error=_diagnostic(type(error).__name__+': '+str(error), secrets_provider))
        # Check after failures too. Never start another module after losing the native JVM,
        # and never restart/retry it to conceal the original module outcome.
        if not alive():
            unavailable = True
            if report[module.name]['status'] == 'PASS':
                report[module.name] = dict(status='FAIL', error='Native JVM stopped during module verification')
            report[module.name]['nativeJvmAliveAfter'] = False
        if report[module.name]['status'] != 'PASS':
            failed.append(module.name)
            report['status'] = 'FAIL'

    report['nativeModuleFailures'] = failed
    report['nativeModulesNotRun'] = skipped
    if failed or skipped or not prerequisites_passed:
        report['status'] = 'FAIL'
        # Raw exceptions are stored only in the sanitized per-module summary, never here.
        report['error'] = 'Native module acceptance failed or did not run: '+', '.join(failed+skipped)
    # Deliberately never assign overall PASS. Pre-existing FAIL/BLOCKED cannot be erased.
