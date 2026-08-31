"""Shared pytest fixtures without mutating ``sys.path``.

Pipeline modules are imported through package-qualified names so each
``common.py`` stays scoped to its own pipeline package.
"""

from pathlib import Path

import pytest


@pytest.fixture
def project_root() -> Path:
    return Path(__file__).resolve().parents[1]
