# VS Gravity (Gravity API)

A Forge 1.20.1 library mod that lets you change entity gravity direction — including
arbitrary (non-cardinal) gravity on [Valkyrien Skies](https://valkyrienskies.org/) ships.

credit : https://github.com/qouteall/GravityChanger/tree/1.20.4

## Using as a dependency

### Option A: GitHub Packages

This repo publishes to GitHub Packages at `maven.pkg.github.com/MemezForBeanz/VS-Gravity`.
GitHub requires authentication to *read* packages even on a public repo, so you need a
[personal access token](https://github.com/settings/tokens) with the `read:packages` scope
(classic PAT; fine-grained tokens need packages read permission for this repo).

To publish a new version (maintainers only), you additionally need `write:packages`, then:

```
./gradlew publish
```

(credentials come from `~/.gradle/gradle.properties` — `gpr.user` / `gpr.token` — or the
`GITHUB_ACTOR` / `GITHUB_TOKEN` env vars; never commit a token to this repo)

In the **consuming** mod's `build.gradle`:

```groovy
repositories {
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/MemezForBeanz/VS-Gravity")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    // Compile against + run with the library in dev
    implementation fg.deobf("net.memezforbeanz.gravityapi:gravityapi:1.0.6")

    // Nest it inside your mod jar (Jar-in-Jar) so players don't need a separate download
    jarJar(group: 'net.memezforbeanz.gravityapi', name: 'gravityapi', version: '[1.0.6,)')
}
```

Same `gpr.user` / `gpr.token` (or `GITHUB_ACTOR` / `GITHUB_TOKEN`) credentials are needed on
the consuming side to resolve the dependency — put them in that project's
`~/.gradle/gradle.properties`, not its repo.

### Option B: local maven (no GitHub auth needed, good for same-machine dev)

```
./gradlew publishToMavenLocal
```

Then in the consuming mod:

```groovy
repositories {
    mavenLocal()
}
```

with the same `dependencies` block as above.

---

The published jar already nests its own runtime dependencies (MixinExtras, MixinSquared)
via Jar-in-Jar, so nothing else is required.

## Gravity Generator API

To make your own block that projects a gravity field (ship-aware, arbitrary angles),
extend the two classes in `net.memezforbeanz.gravityapi.api.generator`:

```java
public class MyGeneratorBlock extends AbstractGravityGeneratorBlock {
    public MyGeneratorBlock() {
        super(BlockBehaviour.Properties.of().strength(3.0f));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MyGeneratorBlockEntity(pos, state);
    }
}

public class MyGeneratorBlockEntity extends AbstractGravityGeneratorBlockEntity {
    public MyGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(MyBlockEntities.MY_GENERATOR.get(), pos, state);
    }

    @Override
    public double getFieldRadius() {
        return 32.0; // optional: default is 20
    }
}
```

Everything else — registration, client-side prediction, Valkyrien Skies ship detection and
ship-space → world-space gravity transforms — is handled by the base classes. Overridable
hooks:

| Method | Default |
|---|---|
| `getFieldRadius()` | 20 blocks (spherical) |
| `getGravityPriority()` | 50 |
| `getRotationParameters()` | `RotationParameters.getDefault()` |
| `getBaseGravityDirection()` | `DOWN` — the projected direction in the generator's local frame (ship-local on a ship, world space otherwise); override for any direction incl. arbitrary vectors |
| `computeGravityDirection()` | transforms the base direction ship→world when on a ship; only override to bypass the ship transform |
| `isInField(Entity)` | sphere check (override for other field shapes) |
| `canAffect(Entity)` | `EntityTags.canChangeGravity` |
| `isGeneratorActive()` | reads the `ACTIVE` blockstate property |
| `onActivated()` / `onDeactivated()` / `onFieldTick(...)` | no-ops |

The in-repo debug block (`compat/vs2/block/GravityGeneratorBlock`) is the reference
implementation of these classes.

VS2 integration is reflection-based: none of this requires Valkyrien Skies to be installed —
without it, generators simply work in world space.
