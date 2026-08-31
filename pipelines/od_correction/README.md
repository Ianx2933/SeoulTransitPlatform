# OD correction

The active implementation is `src/od_correction_pipeline.py`; historical
one-off preprocessing scripts are retained under `archive/` only for provenance.

The active pipeline performs deterministic normalization, direct reference
mapping, and ordered fallback recovery. Ambiguous key-to-code mappings are left
unresolved rather than selecting an arbitrary first candidate.

- Implementation and input/output contract: [`src/README.md`](src/README.md)
- Executable regression tests: [`../../tests/test_od_correction.py`](../../tests/test_od_correction.py)
- Test strategy: [`../../docs/engineering/testing_strategy.md`](../../docs/engineering/testing_strategy.md)
