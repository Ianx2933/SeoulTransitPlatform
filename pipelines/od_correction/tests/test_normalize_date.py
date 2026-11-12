import sys
import unittest
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from od_correction_pipeline import ODCorrectionPipeline  # noqa: E402


class NormalizeDateTests(unittest.TestCase):
    def test_every_topis_date_format_becomes_yymmdd(self):
        got = ODCorrectionPipeline.normalize_date(pd.Series(
            ["23/10/17", "2023-10-17", "2023/10/17", "20231017", "231017", "20231017.0", "2023-10-17 00:00:00"]))
        self.assertEqual(got.tolist(), ["231017"] * 7)

    def test_an_unreadable_date_stops_the_run(self):
        with self.assertRaises(ValueError):
            ODCorrectionPipeline.normalize_date(pd.Series(["2023-13-45"]))


if __name__ == "__main__":
    unittest.main()
