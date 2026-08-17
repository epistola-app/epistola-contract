# JVM ABI compatibility

`epistola-catalog.api` is a generated snapshot of the public JVM interface exposed by the catalog
JAR. It is an ABI lockfile, not a REST API definition or a catalog wire schema. The baseline lets a
minor release detect changes that would break Kotlin or Java applications already compiled against
the latest published catalog artifact.

The snapshot uses JVM notation. Common entries include:

```text
public fun <init> (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
public final fun getName ()Ljava/lang/String;
public static synthetic fun copy$default (...)
```

`<init>` denotes a constructor, `Ljava/lang/String;` denotes `java.lang.String`, and primitives use
single-letter descriptors such as `I` for integer and `Z` for boolean. Kotlin also generates public
JVM members for features such as default arguments, data-class copying, destructuring, and companion
objects. Those generated members can be used by already-compiled consumers and therefore belong in
the compatibility baseline.

## Checking changes

Run the compatibility check with:

```bash
./gradlew checkLegacyAbi
```

The task compares the compiled catalog API with [`../api/epistola-catalog.api`](../api/epistola-catalog.api).
It fails when the snapshots differ, making both accidental removals and intentional additions visible
in review.

For a backwards-compatible minor release, inspect the diff before accepting it. Existing classes,
constructors, methods, fields, and descriptors must remain unchanged. Additive declarations are
normally acceptable. Pay particular attention when changing a Kotlin `data class`: adding a property
can replace its constructor, `copy`, and generated default-argument methods even though the source
change appears additive.

After confirming that the new surface is intentional and compatible, update the baseline with:

```bash
./gradlew updateLegacyAbi
git diff -- api/epistola-catalog.api
./gradlew checkLegacyAbi
```

Commit the baseline update with the implementation that changes the public API. Do not manually edit
the generated snapshot or update it merely to silence a failing check. A removal or signature change
requires either a compatibility bridge or an explicitly planned major release.

## ABI versus wire compatibility

The ABI baseline protects compiled JVM consumers. JSON Schemas, wire fixtures, and migrations protect
catalog archive compatibility. A change can be safe at one boundary and breaking at the other, so both
sets of checks remain necessary.
