# NOTICE

AzureBranches — an experimental Folia downstream for command block semantics.

## Derivative work statement

AzureBranches is a derivation of the following projects, each of which is
licensed under the GNU General Public License version 3 (GPLv3) and each of
which in turn inherits that license from the chain below:

- [Folia](https://github.com/PaperMC/Folia) — Regionalized thread project by
  the PaperMC team (Spottedleaf / Aikar / jmp / the PaperMC community)
- [Paper](https://github.com/PaperMC/Paper)
- [Spigot](https://hub.spigotmc.org/stash/projects/SPIGOT/repos/spigot)
- [Bukkit](http://bukkit.org/) and [CraftBukkit](https://hub.spigotmc.org/stash/projects/CRAFTBUKKIT/repos/craftbukkit)

As such, **AzureBranches is licensed under the GNU General Public License
version 3** (see [LICENSE](LICENSE)). The MIT License statement previously
present in this repository was incorrect and has been removed.

## Modifications (GPLv3 section 5(a))

This work is distributed with modifications. The modifications are recorded
in this repository rather than in a separate change list:

- `minecraft-patches/` — AzureBranches patch series applied over the pinned
  Folia upstream commit (`foliaRef` in `folia-server/build.gradle.kts`)
- `folia-server/azurepatches-src/` — full-file overlays applied after
  patch application (fail-fast on missing targets)
- `folia-server/azurepatches-new/` — AzureBranches-only classes
- `folia-server/build.gradle.kts` — `transformSource` anchors injecting
  the EXP Phase/OCC machinery at build time
- `azurebranches-common/` — the EXP runtime (PhaseSnapshot / ChainHead /
  Continuation / ExpChainSupport layers), compiled into the server jar

The build is deterministic and pinned: the exact upstream Folia commit used
is declared in `folia-server/build.gradle.kts` (`folioRef`), and
[paperweight](https://github.com/PaperMC/paperweight) reproduces the patched
sources from it.

## Acknowledgements

- **Kaiiju** — EntityLimiter's design inspiration (rebased and simplified).
- **LuminolMC** (EarthMe) — configuration system and entity optimization
  ideas; AzureBranches' implementations are simplified and redesigned for
  its own architectural direction.
- **GNU** — the license text is the GNU General Public License version 3.

## Planned integration attribution

The upcoming storage-engine integration (b_linear region format) is derived
from the Luminol → Arbor lineage (author xymb / Little / the ArborTeam
maintainers). This NOTICE will be extended with the exact upstream
attributions when that integration lands.
