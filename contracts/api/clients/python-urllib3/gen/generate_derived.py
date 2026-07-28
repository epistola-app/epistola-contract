#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

"""Build-time generator: reads the bundled OpenAPI spec and emits three derived
source modules into the hand-written package. Mirrors the Kotlin build's
generateProblemSlugs / generateValidation / generateContractVersionResource tasks
and the .NET Epistola.Client.Gen program.

    python gen/generate_derived.py <openapi.yaml> <output-dir>

Emits, into <output-dir>:
    __init__.py            re-exports the three modules
    contract_version.py    CONTRACT_VERSION, from info.version
    known_problem_slugs.py KnownProblemSlugs + GENERATED_PROBLEM_TYPE_BASE, from x-problem-types
    model_validation.py    validate() dispatch + per-model validators, from schema constraints
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Any

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - surfaced as a clear build error
    sys.stderr.write(
        "PyYAML is required to run the derived-source generator "
        "(add it to the module's dependencies / run via `uv run`).\n"
    )
    raise

# Constraint keywords that make a model worth generating an explicit validator for.
_CONSTRAINT_KEYS = ("pattern", "minLength", "maxLength", "minimum", "maximum", "minItems")


def _camel_to_snake(name: str) -> str:
    """Convert a schema name to the module basename the Python generator uses."""
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", name)
    s = re.sub(r"([a-z\d])([A-Z])", r"\1_\2", s)
    return s.lower()


def _load_spec(spec_path: Path) -> dict[str, Any]:
    with spec_path.open("r", encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def _generate_contract_version(root: dict[str, Any]) -> str:
    version = root["info"]["version"]
    return (
        '"""Auto-generated from the OpenAPI spec\'s info.version — do not edit."""\n\n'
        "#: The Epistola contract version this client library was built against.\n"
        f'CONTRACT_VERSION = "{version}"\n'
    )


def _generate_problem_slugs(root: dict[str, Any]) -> str:
    registry = root.get("x-problem-types")
    if not isinstance(registry, dict):
        raise SystemExit(
            "bundled spec has no x-problem-types extension — KnownProblemSlugs cannot be generated"
        )

    base = registry.get("base")
    if not isinstance(base, str) or not base:
        raise SystemExit("x-problem-types.base is missing from the bundled spec")

    types = registry.get("types")
    if not isinstance(types, list):
        types = []
    if len(types) < 8:
        raise SystemExit(
            f"x-problem-types lists only {len(types)} problem types (expected at least 8) — "
            "was the registry truncated?"
        )

    lines: list[str] = []
    lines.append(
        '"""Auto-generated from the OpenAPI spec\'s x-problem-types extension — do not edit."""\n'
    )
    lines.append("#: Base URI from the spec's x-problem-types registry; must equal ProblemTypes.TYPE_BASE.")
    lines.append(f'GENERATED_PROBLEM_TYPE_BASE = "{base}"\n')
    lines.append("")
    lines.append("class KnownProblemSlugs:")
    lines.append('    """The canonical problem ``type`` slugs the Epistola API emits, from the')
    lines.append("    contract's error-type registry (the spec's ``x-problem-types`` extension /")
    lines.append("    ``docs/error-types.md``).")
    lines.append("")
    lines.append("    Convenience constants for ``match e.type_slug``. ``type_slug`` is a plain")
    lines.append("    ``str | None`` (not an enum) so the API can introduce new problem types without")
    lines.append('    forcing a client release — always keep a fallback branch.')
    lines.append('    """')
    lines.append("")
    for entry in types:
        slug = entry["slug"]
        status = entry.get("status", "")
        description = re.sub(r"\s+", " ", str(entry.get("description", ""))).strip()
        const_name = slug.upper().replace("-", "_")
        lines.append(f"    #: {status} — {description}")
        lines.append(f'    {const_name} = "{slug}"')
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def _resolve_type(prop: dict[str, Any]) -> tuple[str | None, bool]:
    type_val = prop.get("type")
    if isinstance(type_val, str):
        return type_val, False
    if isinstance(type_val, list):
        non_null = next((str(x) for x in type_val if str(x) != "null"), None)
        return non_null, any(str(x) == "null" for x in type_val)
    return None, False


def _model_has_constraints(schema: dict[str, Any]) -> bool:
    if schema.get("type") != "object":
        return False
    properties = schema.get("properties")
    if not isinstance(properties, dict):
        return False
    for prop in properties.values():
        if not isinstance(prop, dict) or "$ref" in prop:
            continue
        base_type, _ = _resolve_type(prop)
        if base_type == "string" and any(k in prop for k in ("pattern", "minLength", "maxLength")):
            return True
        if base_type == "integer" and any(k in prop for k in ("minimum", "maximum")):
            return True
        if base_type == "array" and "minItems" in prop:
            return True
    return False


def _generate_model_validation(root: dict[str, Any]) -> str:
    schemas = root.get("components", {}).get("schemas", {})
    if not isinstance(schemas, dict):
        schemas = {}

    constrained = [name for name, schema in schemas.items()
                   if isinstance(schema, dict) and _model_has_constraints(schema)]
    constrained.sort()

    if not constrained:
        raise SystemExit(
            "model-validation generation found no constrained models — either the spec lost all "
            "its constraints or the schema-walking code no longer matches the spec structure"
        )

    lines: list[str] = []
    lines.append('"""Auto-generated from OpenAPI schema constraints — do not edit.')
    lines.append("")
    lines.append("Explicit fail-fast ``validate()`` helpers for the contract's request/response models.")
    lines.append("The pydantic models already enforce their string/number/array constraints on")
    lines.append("construction; these helpers re-run that validation on demand (delegating to pydantic,")
    lines.append("so there is a single source of truth) to give parity with the Kotlin/.NET ``validate()``")
    lines.append("extensions. The set of models here is derived from the spec, so it fails the build if")
    lines.append('the contract ever silently drops all of its constraints.')
    lines.append('"""')
    lines.append("")
    lines.append("from __future__ import annotations")
    lines.append("")
    lines.append("from typing import TypeVar")
    lines.append("")
    lines.append("from pydantic import BaseModel")
    lines.append("")
    lines.append("import epistola_client_generated as _gen")
    lines.append("")
    lines.append('T = TypeVar("T", bound=BaseModel)')
    lines.append("")
    lines.append("#: Names of the contract models that carry validatable constraints.")
    lines.append("CONSTRAINED_MODELS = (")
    for name in constrained:
        lines.append(f'    "{name}",')
    lines.append(")")
    lines.append("")
    lines.append("")
    lines.append("def validate(model: T) -> T:")
    lines.append('    """Re-validate a contract model against its schema constraints; return it on success.')
    lines.append("")
    lines.append("    Raises ``pydantic.ValidationError`` if the model violates a declared constraint.")
    lines.append('    """')
    lines.append("    type(model).model_validate(model.model_dump(by_alias=True))")
    lines.append("    return model")
    lines.append("")
    for name in constrained:
        snake = _camel_to_snake(name)
        lines.append("")
        lines.append(f"def validate_{snake}(model: _gen.{name}) -> _gen.{name}:")
        lines.append(f'    """Validate a :class:`{name}` against its schema constraints; return it on success."""')
        lines.append("    return validate(model)")
    lines.append("")
    return "\n".join(lines)


def _write(out_dir: Path, name: str, content: str) -> None:
    path = out_dir / name
    path.write_text(content, encoding="utf-8")
    print(f"    wrote {name}")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        sys.stderr.write("usage: generate_derived.py <openapi.yaml> <output-dir>\n")
        return 1

    spec_path = Path(argv[0])
    out_dir = Path(argv[1])

    if not spec_path.is_file():
        sys.stderr.write(f"spec not found: {spec_path}\n")
        return 1

    out_dir.mkdir(parents=True, exist_ok=True)
    root = _load_spec(spec_path)

    _write(out_dir, "contract_version.py", _generate_contract_version(root))
    _write(out_dir, "known_problem_slugs.py", _generate_problem_slugs(root))
    _write(out_dir, "model_validation.py", _generate_model_validation(root))
    _write(
        out_dir,
        "__init__.py",
        (
            '"""Derived sources generated from the OpenAPI spec — do not edit."""\n\n'
            "from epistola_client._generated.contract_version import CONTRACT_VERSION\n"
            "from epistola_client._generated.known_problem_slugs import (\n"
            "    GENERATED_PROBLEM_TYPE_BASE,\n"
            "    KnownProblemSlugs,\n"
            ")\n"
            "from epistola_client._generated.model_validation import (\n"
            "    CONSTRAINED_MODELS,\n"
            "    validate,\n"
            ")\n\n"
            '__all__ = [\n'
            '    "CONTRACT_VERSION",\n'
            '    "GENERATED_PROBLEM_TYPE_BASE",\n'
            '    "KnownProblemSlugs",\n'
            '    "CONSTRAINED_MODELS",\n'
            '    "validate",\n'
            "]\n"
        ),
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
