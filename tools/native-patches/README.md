# Native routing backports

The build retains core-legacy base `071503e280d77c2a959d3690845f5e56b2c7e4b3`
and its JNI contract. `build-native-core-wsl.sh` applies the patches in order,
or verifies that they are already applied. It fails on a partial application.

`0001-routing-memory.patch` backports the two commits merged in
https://github.com/osmandapp/OsmAnd-core-legacy/pull/379 :

- `f9ba7b8b99de90df07493e3fdeb1714cf85863aa`: keep decoding rules in sync.
- `503e0ca3e4ce0d03f68dd0c4b67504cfe82e3475`: unload old HH graph edges,
  retaining edited edges and result edges.

The only conflict adaptation keeps the base's immediate recalculation return
in `retrieveSegmentsGeometry`, changing the distance write to `editDist`.
No newer recalculation strategy is imported. Java equivalents are backported
from OsmAnd PR 26396 (c5482303dd8c and 203fe9e81fc7).

`0002-routing-reader-isolation.patch` additionally backports accepted native
fixes without changing the routing algorithm:

- PR 381, `105a339f22d7769faedc67c515e5359db5562cfe`: rendering road-only maps
  uses its rendering descriptor, not the route calculation's file position.
- PR 382, `2e830330ccb0503035692cdc71763412a39767d9`: a per-routing-index mutex
  protects lazy rule initialization from partial reads by other threads.

These address known memory/reader defects, not proof that the reported SIGSEGV has been fixed.
The supplied tombstone matches the previous library build ID exactly, but
does not show concurrent native routing in another thread. Rejected upstream
PR 26653's global lock is deliberately not included.
