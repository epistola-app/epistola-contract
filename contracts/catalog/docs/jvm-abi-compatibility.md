# JVM ABI compatibility

`epistola-catalog.api` is a generated snapshot of the public JVM interface exposed by the catalog
JAR. It is an ABI review lockfile, not a REST API definition or a catalog wire schema. The baseline
makes JVM surface changes explicit in review, including changes caused implicitly by Kotlin.

The snapshot uses JVM notation. Common entries include:

```text
public fun <init> (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
public final fun getName ()Ljava/lang/String;
public static synthetic fun copy$default (...)
```

`<init>` denotes a constructor, `Ljava/lang/String;` denotes `java.lang.String`, and primitives use
single-letter descriptors such as `I` for integer and `Z` for boolean. Kotlin also generates public
JVM members for features such as default arguments, data-class copying, destructuring, and companion
objects. Those generated members appear in JVM bytecode even when they are not part of the intended
source API, which is why reviewing the generated surface is useful.

The catalog artifact guarantees compatible catalog JSON within a minor line. The Kotlin API is a
recompile-on-upgrade boundary and does not guarantee drop-in replacement for already-compiled JVM
consumers. Epistola Suite and Exchange recompile when upgrading this dependency. Consequently, an
intentional ABI removal may be accepted in a minor release when known consumers can migrate by
recompiling and the wire contract remains compatible.

## Checking changes

Run the compatibility check with:

```bash
./gradlew checkLegacyAbi
```

The task compares the compiled catalog API with [`../api/epistola-catalog.api`](../api/epistola-catalog.api).
It fails when the snapshots differ, making both accidental removals and intentional additions visible
in review.

Inspect the diff before accepting it. Pay particular attention when changing a Kotlin `data class`:
adding a property can replace its constructor, `copy`, and generated default-argument methods even
though the source change appears additive. Decide whether each change affects supported Kotlin
source usage or only already-compiled JVM bytecode.

After confirming that the new surface is intentional and compatible, update the baseline with:

```bash
./gradlew updateLegacyAbi
git diff -- api/epistola-catalog.api
./gradlew checkLegacyAbi
```

Commit the baseline update with the implementation that changes the public API. Do not manually edit
the generated snapshot or update it merely to silence a failing check. Document intentional removals
and ensure all known consumers recompile as part of their dependency upgrade.

## ABI versus wire compatibility

The ABI baseline exposes changes that could affect compiled JVM consumers. JSON Schemas, wire
fixtures, and migrations protect catalog archive compatibility. A change can be safe at one boundary
and breaking at the other, so both sets of checks remain necessary even though the wire is the
versioned minor-release compatibility boundary.
