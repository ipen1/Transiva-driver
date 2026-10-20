#!/usr/bin/env python3
"""Print test failures and suite-level installation/runner errors, including zero-test runs."""
from android_runtime_result import main
import sys

if __name__=='__main__':
    sys.argv.insert(1,'report')
    raise SystemExit(main())
