"""Offline regression checks for validation decisions and full-row fingerprint.

Each case is a counter-example the aggregate totals would have passed: a
per-OD error that cancels out, a missing hour, a changed method.
"""
from types import SimpleNamespace
import pytest
from pipelines.hourly_od_estimation.validate_rebuild import (
    Report, check_od_totals, fingerprint_rows, od_total_matches,
)


class SummaryConnection:
    def __init__(self, **changes):
        self.row = SimpleNamespace(total_keys=2, missing=0, extra=0, bad_hours=0,
                                   bad_totals=0, source_total=200., estimated_total=200.)
        self.row.__dict__.update(changes)

    def execute(self, statement, params):
        return self

    def one(self):
        return self.row


@pytest.mark.parametrize("changes", [
    {"missing": 1}, {"extra": 1}, {"bad_hours": 1}, {"bad_totals": 2},
    {"total_keys": 0},
])
def test_reconciliation_fails_even_when_grand_totals_match(changes):
    report = Report()
    check_od_totals(SummaryConnection(**changes), report, "20251111", "20251111")
    assert report.failed > 0


def test_complete_reconciliation_passes():
    report = Report()
    check_od_totals(SummaryConnection(), report, "20251111", "20251111")
    assert report.failed == 0


@pytest.mark.parametrize("loss", [1., 50.])
def test_passenger_loss_is_not_hidden_by_dataset_size(loss):
    assert not od_total_matches(5345649., 5345649. - loss)


def test_tiny_float_roundoff_is_accepted():
    assert od_total_matches(1234.5, 1234.5 + 1e-10)
    assert not od_total_matches(1., float("nan"))


def rows():
    return [("20251111", "146", "07616", "01001", h,
             100. if h == 8 else 0., "default_static_ratio") for h in range(24)]


def test_fingerprint_changes_when_hourly_distribution_changes():
    original = rows()
    changed = [(d, r, o, dest, h, 100. if h == 18 else 0., method)
               for d, r, o, dest, h, _, method in original]
    assert sum(x[5] for x in original) == sum(x[5] for x in changed)
    assert fingerprint_rows(original) != fingerprint_rows(changed)
    assert fingerprint_rows(original) == fingerprint_rows(iter(original))


@pytest.mark.parametrize("column,value", [(1, "147"), (2, "07617"), (6, "default_static_ratio_zero_fit"), (5, 0.00000001)])
def test_fingerprint_covers_keys_method_and_small_value_changes(column, value):
    original = rows()
    changed = [list(row) for row in original]
    changed[0][column] = value
    assert fingerprint_rows(original) != fingerprint_rows(changed)
